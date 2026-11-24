package org.yapyap.routing.router

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.yapyap.crypto.primitives.DefaultCryptoProvider
import org.yapyap.crypto.store.BootstrapKeySource
import org.yapyap.protection.PassthroughFileProtection
import org.yapyap.protection.envelope.BootstrapProtection
import org.yapyap.protection.service.DefaultEnvelopeProtections
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.DeviceType
import org.yapyap.protocol.PacketType
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.RoomId
import org.yapyap.protocol.SignalSecurityScheme
import org.yapyap.protocol.TorEndpoint
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.envelopes.DagPayload
import org.yapyap.protocol.envelopes.Intro
import org.yapyap.protocol.envelopes.SystemEnvelope
import org.yapyap.protocol.envelopes.SystemPayload
import org.yapyap.protocol.identity.AccountIdentity
import org.yapyap.protocol.identity.DeviceIdentity
import org.yapyap.protocol.identity.EncryptionPublicKey
import org.yapyap.protocol.identity.SigningPublicKey
import org.yapyap.testfixtures.FakeClock
import org.yapyap.testfixtures.epochSeconds
import org.yapyap.transport.tor.RecordingTorTransport
import org.yapyap.transport.tor.TorIncomingEnvelope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

class PerCarrierDeliveriesTest {

    private val local = PeerId("percarrierlocalaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
    private val remote = PeerId("percarrierremotebbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
    private val remoteAccount = AccountId("percarrier-account")
    private val remoteTor = TorEndpoint("remote.onion", 80)
    private val now = 10_000L

    private fun localRecord() = DeviceIdentity(
        deviceId = local,
        signingKey = SigningPublicKey(byteArrayOf(1)),
        encryptionKey = EncryptionPublicKey(byteArrayOf(2)),
    )

    private fun textPayload() = DagPayload.Text(
        messageId = Uuid.random(),
        roomId = RoomId(Uuid.random()),
        senderAccountId = AccountId("sender"),
        authorDeviceId = local,
        prevIds = emptyList(),
        createdAt = epochSeconds(now),
        text = "hi",
    )

    private fun introPayload() = Intro(
        version = 1,
        account = AccountIdentity(AccountId("newcomer"), "Newcomer", signingKey = null),
        device = DeviceIdentity(remote, SigningPublicKey(byteArrayOf(3)), EncryptionPublicKey(byteArrayOf(4))),
        deviceType = DeviceType.DESKTOP,
        torEndpoint = TorEndpoint("newcomer.onion", 80),
        dagHeadTipIds = emptyList(),
    )

    private fun ackEnvelope(packetId: Uuid, packetType: PacketType): TorIncomingEnvelope {
        val system = SystemEnvelope(
            systemEnvelopeId = Uuid.random(),
            source = remote,
            target = local,
            createdAt = epochSeconds(now),
            nonce = ByteArray(SignalSecurityScheme.SIGNED.nonceSize) { 3 },
            securityScheme = SignalSecurityScheme.SIGNED,
            signature = ByteArray(64) { 9 },
            payload = SystemPayload.PacketAck(packetId, packetType).encode(),
        )
        return TorIncomingEnvelope(
            remoteTor,
            BinaryEnvelope(
                packetId = Uuid.random(),
                packetType = PacketType.SYSTEM,
                dispositionRequested = true,
                createdAt = epochSeconds(now),
                expiresAt = epochSeconds(now + 1_000),
                source = remote,
                target = local,
                payload = system.encode(),
            ),
        )
    }

    @Test
    fun messageAck_onlyOnMessages_bootstrapAck_onlyOnBootstrap() = runBlocking {
        val tor = RecordingTorTransport(TorEndpoint("self.onion", 80))
        val outbox = TrackingPacketOutbox()
        val identity = FakeIdentityResolverForRouter(
            localDevice = localRecord(),
            peersByAccount = mapOf(remoteAccount to listOf(remote)),
            torByPeer = mutableMapOf(remote to remoteTor),
        )
        val crypto = DefaultCryptoProvider()
        val bootstrapSecret = ByteArray(32) { 7 }
        val router = defaultRouterUnderTest(
            tor = tor,
            identity = identity,
            outbox = outbox,
            clock = FakeClock(epochSeconds(now)),
            routerConfig = RouterConfig(retryLoopMaxIdlePoll = 1.seconds),
            envelopeProtections = DefaultEnvelopeProtections(
                signal = LenientPlaintextWebRtcSignalProtection(crypto),
                message = LenientPlaintextMessageProtection(crypto),
                system = LenientPlaintextSystemProtection(crypto),
                bootstrap = BootstrapProtection(crypto, BootstrapKeySource { bootstrapSecret }),
                file = PassthroughFileProtection(),
            ),
        )
        router.start()
        try {
            val messageDeliveries = mutableListOf<MessageAck>()
            val bootstrapDeliveries = mutableListOf<BootstrapAck>()
            val mj = launch { router.messages.deliveries.collect { messageDeliveries += it } }
            val bj = launch { router.bootstrap.deliveries.collect { bootstrapDeliveries += it } }

            // MESSAGE path.
            router.messages.sendMessage(remoteAccount, textPayload())
            withTimeout(10.seconds) {
                while (outbox.enqueued.none { it.packetType == PacketType.MESSAGE }) delay(20.milliseconds)
            }
            val messagePacketId = outbox.enqueued.first { it.packetType == PacketType.MESSAGE }.packetId
            tor.tryEmitIncoming(ackEnvelope(messagePacketId, PacketType.MESSAGE))
            withTimeout(10.seconds) {
                while (messageDeliveries.isEmpty()) delay(20.milliseconds)
            }
            delay(400.milliseconds)
            assertEquals(1, messageDeliveries.size)
            assertTrue(bootstrapDeliveries.isEmpty(), "MESSAGE ACK must not appear on bootstrap.deliveries")

            // BOOTSTRAP path.
            val bootstrapPacketId = router.bootstrap.sendBootstrap(
                introPayload(),
                target = remote,
                sharedSecret = bootstrapSecret,
            )
            tor.tryEmitIncoming(ackEnvelope(bootstrapPacketId, PacketType.BOOTSTRAP))
            withTimeout(10.seconds) {
                while (bootstrapDeliveries.isEmpty()) delay(20.milliseconds)
            }
            delay(400.milliseconds)
            assertEquals(1, bootstrapDeliveries.size)
            assertEquals(1, messageDeliveries.size, "BOOTSTRAP ACK must not appear on messages.deliveries")
            mj.cancel()
            bj.cancel()
        } finally {
            router.stop()
        }
    }
}
