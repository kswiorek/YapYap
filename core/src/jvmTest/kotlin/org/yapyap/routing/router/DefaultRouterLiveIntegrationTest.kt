package org.yapyap.routing.router

import io.matthewnelson.kmp.file.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.yapyap.crypto.identity.AccountId
import org.yapyap.crypto.identity.DeviceIdentityRecord
import org.yapyap.crypto.identity.IdentityKeyPurpose
import org.yapyap.crypto.identity.IdentityPublicKeyRecord
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.TorEndpoint
import org.yapyap.protocol.envelopes.MessagePayload
import org.yapyap.time.FixedEpochSecondsProvider
import org.yapyap.transport.tor.backend.KmpTorBackend
import org.yapyap.transport.tor.backend.TorBackendConfig
import org.yapyap.transport.tor.transport.DefaultTorTransport
import org.yapyap.transport.webrtc.backend.JvmWebRtcBackend
import org.yapyap.transport.webrtc.transport.DefaultWebRtcTransport
import java.nio.file.Files
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.absolutePathString
import kotlin.io.path.deleteRecursively
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid

/**
 * Two [org.yapyap.routing.router.DefaultRouter] instances over real [DefaultTorTransport]/[KmpTorBackend] and real WebRTC stacks.
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

    private fun sampleText(): MessagePayload.Text =
        MessagePayload.Text(
            messageId = Uuid.random(),
            roomId = "room-live",
            senderAccountId = AccountId("alice-acct"),
            prevId = null,
            lamportClock = 1L,
            createdAtEpochSeconds = 0L,
            text = "hello-live-router",
            authorDeviceId = alicePeer,
        )

    @Test
    fun twoRouters_torTransport_deliversTextMessageEndToEnd() = runBlocking {
        val bobAccount = AccountId("bob-live-account")

        val aliceTorDir = Files.createTempDirectory("yapyap-router-alice-tor")
        val bobTorDir = Files.createTempDirectory("yapyap-router-bob-tor")
        val torConfig = TorBackendConfig(startupTimeoutMillis = 180_000L)

        val aliceTorBackend =
            KmpTorBackend(
                torStateRootPath = File(aliceTorDir.absolutePathString()),
                config = torConfig,
            )
        val bobTorBackend =
            KmpTorBackend(
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
                packetDeduplicator = InMemoryPacketDeduplicator(),
                packetOutbox = TrackingPacketOutbox(),
                envelopeProtectionService = PassthroughFakeEnvelopeProtectionService(),
                timeProvider = time,
                routerConfig = RouterConfig(),
                syncPayloadProvider = FakeSyncPayloadProvider(),
            )
        val bobRouter =
            DefaultRouter(
                torTransport = bobTorTransport,
                webRtcTransport = bobWebRtc,
                identityResolver = bobIdentity,
                packetDeduplicator = InMemoryPacketDeduplicator(),
                packetOutbox = TrackingPacketOutbox(),
                envelopeProtectionService = PassthroughFakeEnvelopeProtectionService(),
                timeProvider = time,
                routerConfig = RouterConfig(),
                syncPayloadProvider = FakeSyncPayloadProvider(),
            )

        try {
            bobRouter.start()
            aliceRouter.start()

            aliceTorMap[bobPeer] = bobIdentity.resolveTorEndpointForDevice(bobPeer)
            bobTorMap[alicePeer] = aliceIdentity.resolveTorEndpointForDevice(alicePeer)

            val outbound = sampleText()

            val inbound =
                withTimeout(420_000L.milliseconds) {
                    coroutineScope {
                        val waitMsg =
                            async {
                                bobRouter.incomingMessages.first()
                            }
                        delay(300L.milliseconds)
                        aliceRouter.sendMessage(bobAccount, outbound, RouterTransport.TOR)
                        waitMsg.await()
                    }
                }

            val text = assertIs<MessagePayload.Text>(inbound)
            assertEquals(outbound.messageId, text.messageId)
            assertEquals(outbound.text, text.text)
            assertEquals(outbound.roomId, text.roomId)
        } finally {
            runCatching { aliceRouter.stop() }
            runCatching { bobRouter.stop() }
            runCatching { aliceTorDir.deleteRecursively() }
            runCatching { bobTorDir.deleteRecursively() }
        }
    }
}
