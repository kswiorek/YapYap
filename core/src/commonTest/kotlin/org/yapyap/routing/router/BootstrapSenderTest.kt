package org.yapyap.routing.router


import kotlinx.coroutines.runBlocking
import org.yapyap.crypto.primitives.DefaultCryptoProvider
import org.yapyap.crypto.store.BootstrapKeySource
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.DeviceType
import org.yapyap.protocol.PacketType
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.TorEndpoint
import org.yapyap.protocol.envelopes.BootstrapHeader
import org.yapyap.protocol.envelopes.BootstrapSecurityScheme
import org.yapyap.protocol.envelopes.Intro
import org.yapyap.protocol.identity.AccountIdentity
import org.yapyap.protocol.identity.DeviceIdentity
import org.yapyap.protocol.identity.EncryptionPublicKey
import org.yapyap.protocol.identity.SigningPublicKey
import org.yapyap.testfixtures.FakeClock
import org.yapyap.testfixtures.epochSeconds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

class BootstrapSenderTest {

    private val localDevice =
        PeerId("localbootstrapaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
    private val newcomerDevice =
        PeerId("newbootstrapbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")

    private fun localDeviceRecord(): DeviceIdentity =
        DeviceIdentity(
            deviceId = localDevice,
            signingKey = SigningPublicKey(byteArrayOf(1)),
            encryptionKey = EncryptionPublicKey(byteArrayOf(2)),
        )

    private fun newcomerIntroPayload(): Intro =
        Intro(
            version = 1,
            account = AccountIdentity(
                accountId = AccountId("newcomer-account"),
                displayName = "Newcomer",
                signingKey = null,
            ),
            device = DeviceIdentity(
                deviceId = newcomerDevice,
                signingKey = SigningPublicKey(byteArrayOf(3)),
                encryptionKey = EncryptionPublicKey(byteArrayOf(4)),
            ),
            deviceType = DeviceType.DESKTOP,
            torEndpoint = TorEndpoint("newcomer.onion", 80),
            dagHeadTipIds = emptyList(),
        )

    @Test
    fun sendBootstrapIntro_protectsAndEnqueuesBootstrapEnvelope() = runBlocking {
        val clock = FakeClock(epochSeconds(10_000L))
        val outbox = TrackingPacketOutbox()
        val crypto = DefaultCryptoProvider()
        val secret = ByteArray(32) { 7 }
        val protections = passthroughProtections(
            crypto = crypto,
            keySource = BootstrapKeySource { secret },
        )
        val router = defaultRouterUnderTest(
            identity = FakeIdentityResolverForRouter(localDevice = localDeviceRecord()),
            outbox = outbox,
            clock = clock,
            routerConfig = RouterConfig(bootstrapIntroLifetime = 2.hours),
            envelopeProtections = protections,
        )
        router.start()

        val payload = newcomerIntroPayload()
        router.bootstrap.sendBootstrap(payload, target = newcomerDevice, sharedSecret = secret)

        assertEquals(1, outbox.enqueued.size)
        val env = outbox.enqueued.single()
        assertEquals(PacketType.BOOTSTRAP, env.packetType)
        assertTrue(env.dispositionRequested, "intro must request an ACK so the sponsor's outbox clears")
        assertEquals(localDevice, env.source)
        assertEquals(newcomerDevice, env.target)
        assertEquals(clock.now() + 2.hours, env.expiresAt, "intro must carry the short bootstrap lifetime")

        // The intro is AEAD-sealed with the sender's secret: the sponsor recovers it with the same secret.
        val header = BootstrapHeader.decode(env.routingHeader)
        assertEquals(BootstrapSecurityScheme.SECRET_AEAD, header.scheme)
        val opened = protections.bootstrap.open(header, env.content, env.source, env.target) as Intro
        assertEquals(newcomerDevice, opened.device.deviceId)
        router.stop()
    }
}
