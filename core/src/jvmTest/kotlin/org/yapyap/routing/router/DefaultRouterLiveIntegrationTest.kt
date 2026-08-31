package org.yapyap.routing.router

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import org.yapyap.crypto.e2ee.testTransportLimits
import org.yapyap.crypto.identity.AccountId
import org.yapyap.crypto.identity.DeviceIdentityRecord
import org.yapyap.crypto.identity.IdentityKeyPurpose
import org.yapyap.crypto.identity.IdentityPublicKeyRecord
import org.yapyap.orchestrator.dag.RoomId
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.TorEndpoint
import org.yapyap.protocol.envelopes.MessagePayload
import org.yapyap.time.FixedEpochProvider
import org.yapyap.transport.tor.backend.KmpTorBackend
import org.yapyap.transport.tor.backend.TorBackendConfig
import org.yapyap.transport.tor.transport.DefaultTorTransport
import org.yapyap.transport.webrtc.backend.JvmWebRtcBackend
import org.yapyap.transport.webrtc.backend.WebRtcBackendConfig
import org.yapyap.transport.webrtc.transport.DefaultWebRtcTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * Two [org.yapyap.routing.router.DefaultRouter] instances over real [DefaultTorTransport]/[KmpTorBackend] and real WebRTC stacks.
 * Verifies a text message from Alice → Bob over **Tor** through the full router encode/decode path.
 *
 * Opt-in: `./gradlew :composeApp:jvmTest -PintegrationTests=true`
 */
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
            roomId = RoomId(Uuid.random()),
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

        val aliceTorDir = Path(SystemTemporaryDirectory, "yapyap-router-alice-tor-${Uuid.random()}")
        val bobTorDir = Path(SystemTemporaryDirectory, "yapyap-router-bob-tor-${Uuid.random()}")
        SystemFileSystem.createDirectories(aliceTorDir)
        SystemFileSystem.createDirectories(bobTorDir)
        val torConfig = MutableStateFlow(TorBackendConfig(startupTimeout = 180.seconds))
        val webRtcConfig = MutableStateFlow(WebRtcBackendConfig())

        val aliceTorBackend =
            KmpTorBackend(
                torStateRootPath = aliceTorDir,
                config = torConfig,
            )
        val bobTorBackend =
            KmpTorBackend(
                torStateRootPath = bobTorDir,
                config = torConfig,
            )

        val aliceTorTransport = DefaultTorTransport(aliceTorBackend)
        val bobTorTransport = DefaultTorTransport(bobTorBackend)
        val aliceWebRtc = DefaultWebRtcTransport(JvmWebRtcBackend(webRtcConfig))
        val bobWebRtc = DefaultWebRtcTransport(JvmWebRtcBackend(webRtcConfig))

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

        val time = FixedEpochProvider(10_000L)
        val aliceRouter =
            DefaultRouter(
                torTransport = aliceTorTransport,
                webRtcTransport = aliceWebRtc,
                identityResolver = aliceIdentity,
                packetDeduplicator = InMemoryPacketDeduplicator(),
                packetOutbox = TrackingPacketOutbox(),
                envelopeProtectionService = PassthroughFakeEnvelopeProtectionService(),
                timeProvider = time,
                routerConfig = MutableStateFlow(RouterConfig()),
                transportLimits = MutableStateFlow(testTransportLimits()),
                syncRepository = InMemoryPendingSyncRepository(),
                syncPayloadProvider = FakeSyncPayloadProvider(),
                lamportSnapshotProvider = FakeLamportSnapshotProvider(),
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
                routerConfig = MutableStateFlow(RouterConfig()),
                transportLimits = MutableStateFlow(testTransportLimits()),
                syncRepository = InMemoryPendingSyncRepository(),
                syncPayloadProvider = FakeSyncPayloadProvider(),
                lamportSnapshotProvider = FakeLamportSnapshotProvider(),
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
            runCatching { deleteRecursively(aliceTorDir) }
            runCatching { deleteRecursively(bobTorDir) }
        }
    }

    private fun deleteRecursively(path: Path) {
        if (SystemFileSystem.metadataOrNull(path)?.isDirectory == true) {
            SystemFileSystem.list(path).forEach { deleteRecursively(it) }
        }
        SystemFileSystem.delete(path, mustExist = false)
    }
}
