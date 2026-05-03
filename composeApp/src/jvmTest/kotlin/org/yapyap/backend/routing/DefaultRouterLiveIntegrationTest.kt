package org.yapyap.backend.routing

import io.matthewnelson.kmp.file.File
import java.nio.file.Files
import java.util.UUID
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.absolutePathString
import kotlin.io.path.deleteRecursively
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.yapyap.backend.crypto.AccountId
import org.yapyap.backend.crypto.DeviceIdentityRecord
import org.yapyap.backend.crypto.IdentityKeyPurpose
import org.yapyap.backend.crypto.IdentityPublicKeyRecord
import org.yapyap.backend.db.MessageLifecycleState
import org.yapyap.backend.logging.NoopAppLogger
import org.yapyap.backend.protocol.MessagePayload
import org.yapyap.backend.protocol.PeerId
import org.yapyap.backend.protocol.TorEndpoint
import org.yapyap.backend.transport.tor.DefaultTorTransport
import org.yapyap.backend.transport.tor.KmpTorNoExecBackend
import org.yapyap.backend.transport.tor.TorBackendConfig
import org.yapyap.backend.transport.webrtc.DefaultWebRtcTransport
import org.yapyap.backend.transport.webrtc.JvmWebRtcBackend

/**
 * Two [DefaultRouter] instances over real [DefaultTorTransport]/[KmpTorNoExecBackend] and real WebRTC stacks.
 * Verifies a text message from Alice → Bob over **Tor** through the full router encode/decode path.
 *
 * Opt-in: `./gradlew :composeApp:jvmTest -PintegrationTests=true`
 */
@OptIn(ExperimentalPathApi::class)
class DefaultRouterLiveIntegrationTest {

    private val alicePeer =
        PeerId("routeraliceaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
    private val bobPeer =
        PeerId("routerbobbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")

    private fun localDevice(peer: PeerId): DeviceIdentityRecord =
        DeviceIdentityRecord(
            deviceId = peer,
            signing = IdentityPublicKeyRecord("sg", 0L, IdentityKeyPurpose.SIGNING, byteArrayOf(1)),
            encryption = IdentityPublicKeyRecord("en", 0L, IdentityKeyPurpose.ENCRYPTION, byteArrayOf(2)),
        )

    private fun sampleText(id: String): MessagePayload.Text =
        MessagePayload.Text(
            messageId = id,
            roomId = "room-live",
            senderAccountId = "alice-acct",
            prevId = null,
            lamportClock = 1L,
            messagePayload = "hello-live-router".encodeToByteArray(),
            lifecycleState = MessageLifecycleState.CREATED,
            isOrphaned = false,
        )

    @Test
    fun twoRouters_torTransport_deliversTextMessageEndToEnd() = runBlocking {
        val bobAccount = AccountId("bob-live-account")

        val aliceTorDir = Files.createTempDirectory("yapyap-router-alice-tor")
        val bobTorDir = Files.createTempDirectory("yapyap-router-bob-tor")
        val torConfig = TorBackendConfig(startupTimeoutMillis = 180_000L)

        val aliceTorBackend =
            KmpTorNoExecBackend(
                deviceId = alicePeer,
                torStateRootPath = File(aliceTorDir.absolutePathString()),
                config = torConfig,
            )
        val bobTorBackend =
            KmpTorNoExecBackend(
                deviceId = bobPeer,
                torStateRootPath = File(bobTorDir.absolutePathString()),
                config = torConfig,
            )

        val aliceTorTransport = DefaultTorTransport(aliceTorBackend)
        val bobTorTransport = DefaultTorTransport(bobTorBackend)
        val aliceWebRtc = DefaultWebRtcTransport(JvmWebRtcBackend())
        val bobWebRtc = DefaultWebRtcTransport(JvmWebRtcBackend())

        val aliceTorMap = mutableMapOf<PeerId, TorEndpoint>()
        val bobTorMap = mutableMapOf<PeerId, TorEndpoint>()

        val aliceIdentity =
            FakeIdentityResolverForRouter(
                localDevice = localDevice(alicePeer),
                peersByAccount = mapOf(bobAccount to listOf(bobPeer)),
                torByPeer = aliceTorMap,
            )
        val bobIdentity =
            FakeIdentityResolverForRouter(
                localDevice = localDevice(bobPeer),
                peersByAccount = emptyMap(),
                torByPeer = bobTorMap,
            )

        val time = FixedEpochSecondsProvider(10_000L)
        val aliceRouter =
            DefaultRouter(
                torTransport = aliceTorTransport,
                webRtcTransport = aliceWebRtc,
                identityResolver = aliceIdentity,
                packetIdAllocator = SequencedPacketIdAllocator(),
                packetDeduplicator = InMemoryPacketDeduplicator(),
                envelopeProtectionService = PassthroughFakeEnvelopeProtectionService(),
                timeProvider = time,
                cryptoProvider = StubCryptoProvider(),
                logger = NoopAppLogger,
            )
        val bobRouter =
            DefaultRouter(
                torTransport = bobTorTransport,
                webRtcTransport = bobWebRtc,
                identityResolver = bobIdentity,
                packetIdAllocator = SequencedPacketIdAllocator(),
                packetDeduplicator = InMemoryPacketDeduplicator(),
                envelopeProtectionService = PassthroughFakeEnvelopeProtectionService(),
                timeProvider = time,
                cryptoProvider = StubCryptoProvider(),
                logger = NoopAppLogger,
            )

        try {
            bobRouter.start()
            aliceRouter.start()

            aliceTorMap[bobPeer] = bobIdentity.resolveTorEndpointForDevice(bobPeer)
            bobTorMap[alicePeer] = aliceIdentity.resolveTorEndpointForDevice(alicePeer)

            val outbound = sampleText("live-msg-${UUID.randomUUID()}")

            val inbound =
                withTimeout(420_000L) {
                    coroutineScope {
                        val waitMsg =
                            async {
                                bobRouter.incomingMessages.first()
                            }
                        delay(300L)
                        aliceRouter.sendMessage(bobAccount, outbound, RouterTransport.TOR)
                        waitMsg.await()
                    }
                }

            val text = assertIs<MessagePayload.Text>(inbound)
            assertEquals(outbound.messageId, text.messageId)
            assertContentEquals(outbound.messagePayload, text.messagePayload)
            assertEquals(outbound.roomId, text.roomId)
        } finally {
            runCatching { aliceRouter.stop() }
            runCatching { bobRouter.stop() }
            runCatching { aliceTorDir.deleteRecursively() }
            runCatching { bobTorDir.deleteRecursively() }
        }
    }
}
