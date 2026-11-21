package org.yapyap.routing.file

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.yapyap.crypto.e2ee.testTransportLimits
import org.yapyap.protection.service.EnvelopeProtectContext
import org.yapyap.protection.service.EnvelopeProtectionService
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.FileId
import org.yapyap.protocol.PacketType
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.SignalSecurityScheme
import org.yapyap.protocol.TransferId
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.envelopes.BootstrapEnvelope
import org.yapyap.protocol.envelopes.BootstrapPayload
import org.yapyap.protocol.envelopes.ChunkRange
import org.yapyap.protocol.envelopes.FileCancelReason
import org.yapyap.protocol.envelopes.FileEnvelope
import org.yapyap.protocol.envelopes.FilePayload
import org.yapyap.protocol.envelopes.MessageEnvelope
import org.yapyap.protocol.envelopes.MessagePayload
import org.yapyap.protocol.envelopes.OpenedFileEnvelope
import org.yapyap.protocol.envelopes.PacketNackReason
import org.yapyap.protocol.envelopes.SystemEnvelope
import org.yapyap.protocol.envelopes.SystemPayload
import org.yapyap.protocol.envelopes.WebRtcSignal
import org.yapyap.protocol.envelopes.WebRtcSignalEnvelope
import org.yapyap.protocol.identity.DeviceIdentity
import org.yapyap.protocol.identity.EncryptionPublicKey
import org.yapyap.protocol.identity.SigningPublicKey
import org.yapyap.routing.file.port.FileCoverage
import org.yapyap.routing.file.port.FileIngestResult
import org.yapyap.routing.file.port.FileManifest
import org.yapyap.routing.file.port.FileOfferVerdict
import org.yapyap.routing.file.port.FileSendState
import org.yapyap.routing.inbound.handlers.FileInboundHandler
import org.yapyap.routing.inbound.handlers.FileRelayDoor
import org.yapyap.routing.inbound.handlers.FileServeHandler
import org.yapyap.routing.inbound.handlers.FileTargetHandler
import org.yapyap.routing.router.FakeFilePorts
import org.yapyap.routing.router.FakeIdentityResolverForRouter
import org.yapyap.routing.router.InMemoryFileRelayStore
import org.yapyap.routing.router.InMemoryPacketDeduplicator
import org.yapyap.routing.router.InboundHandleResult
import org.yapyap.routing.router.RouterConfig
import org.yapyap.routing.router.RoutingContext
import org.yapyap.routing.router.TrackingPacketOutbox
import org.yapyap.routing.store.FileIncomingTransfer
import org.yapyap.routing.store.FileIncomingTransferStore
import org.yapyap.testfixtures.FakeClock
import org.yapyap.testfixtures.epochSeconds
import org.yapyap.transport.tor.RecordingTorTransport
import org.yapyap.transport.webrtc.RecordingWebRtcTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Step 16, holder side: a sealed REQUEST is answered by serving — gate pass + fresh
 * floor-aligned transfer — or DECLINED so the requester moves on at once.
 */
class FileInboundServeTest {

    private val localDevice = PeerId("serve-local-device-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
    private val holderDevice = PeerId("serve-holder-device-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
    private val holderAccount = AccountId("serve-holder-account")
    private val fileId = FileId("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
    private val now = epochSeconds(10_000L)

    private class FakeIngest(var canServe: Boolean = true) : FakeFilePorts {
        var coverage: FileCoverage? = FileCoverage(0L, emptyList())
        val servedFor = mutableListOf<Pair<FileId, AccountId>>()
        override suspend fun checkOffer(offer: FileManifest) = FileOfferVerdict.Accept
        override suspend fun ingest(
            fileId: FileId,
            byteOffset: Long,
            plaintext: ByteArray,
        ) =
            FileIngestResult.Duplicate(0L, 0L)
        override suspend fun heldCoverage(fileId: FileId): FileCoverage? = coverage
        override suspend fun requestCandidates(fileId: FileId): List<AccountId> = emptyList()
        override suspend fun requestTotalBytes(fileId: FileId): Long? = null
        override suspend fun canServe(fileId: FileId, requester: AccountId): Boolean {
            servedFor += fileId to requester
            return canServe
        }
    }

    private class FakePusher : FilePushOps {
        val serves = mutableListOf<Triple<FileId, PeerId, Long>>()
        var serveResult = true
        override suspend fun pushFile(fileId: FileId, target: AccountId) = Unit
        override suspend fun cancelFile(fileId: FileId, target: AccountId) = Unit
        override suspend fun serveFile(fileId: FileId, device: PeerId, fromOffset: Long): Boolean {
            serves += Triple(fileId, device, fromOffset)
            return serveResult
        }
        override suspend fun sendState(fileId: FileId): Map<AccountId, FileSendState> =
            emptyMap()
    }

    private class FakeIncoming : FileIncomingTransferStore {
        override suspend fun insert(row: FileIncomingTransfer) = Unit
        override suspend fun find(transferId: TransferId): FileIncomingTransfer? = null
        override suspend fun findByFile(fileId: FileId): List<FileIncomingTransfer> = emptyList()
        override suspend fun delete(transferId: TransferId) = Unit
        override suspend fun deleteByFile(fileId: FileId) = Unit
        override suspend fun pruneExpired(now: Instant): List<FileIncomingTransfer> = emptyList()
    }

    private class OpenOnlyProtection(
        val request: FilePayload.Request,
        val createdAt: kotlin.time.Instant,
    ) : EnvelopeProtectionService {
        override suspend fun protectSignal(
            input: WebRtcSignal,
            context: EnvelopeProtectContext,
        ): WebRtcSignalEnvelope = error("not used")

        override suspend fun openSignal(envelope: WebRtcSignalEnvelope): WebRtcSignal = error("not used")
        override suspend fun protectFile(input: FilePayload, context: EnvelopeProtectContext): FileEnvelope =
            error("not used")

        override suspend fun openFile(envelope: FileEnvelope) = OpenedFileEnvelope(
            transferId = envelope.transferId,
            source = envelope.source.id,
            target = envelope.target.id,
            createdAt = createdAt,
            securityScheme = envelope.securityScheme,
            payload = request,
        )

        override suspend fun decryptFileChunk(
            transferId: TransferId,
            chunkIndex: Int,
            key: ByteArray,
            chunk: FilePayload.EncryptedChunk,
        ): ByteArray = error("not used")

        override suspend fun encryptFileChunk(
            transferId: TransferId,
            chunkIndex: Int,
            key: ByteArray,
            plaintext: ByteArray,
        ): ByteArray = error("not used")

        override suspend fun sealFileOffer(
            offer: FilePayload.SealedOffer,
            target: PeerId,
        ): ByteArray = error("not used")

        override suspend fun openFileOffer(sealed: ByteArray, source: PeerId): FilePayload.SealedOffer =
            error("not used")

        override fun generateFileTransferKey(): ByteArray = error("not used")
        override suspend fun protectMessage(input: MessagePayload, context: EnvelopeProtectContext): MessageEnvelope =
            error("not used")

        override suspend fun openMessage(envelope: MessageEnvelope): MessagePayload = error("not used")
        override suspend fun protectSystem(input: SystemPayload, context: EnvelopeProtectContext): SystemEnvelope =
            error("not used")

        override suspend fun openSystem(envelope: SystemEnvelope): SystemPayload = error("not used")
        override suspend fun protectBootstrap(
            input: BootstrapPayload,
            context: EnvelopeProtectContext,
            sharedSecret: ByteArray?,
        ): BootstrapEnvelope = error("not used")

        override suspend fun openBootstrap(envelope: BootstrapEnvelope): BootstrapPayload = error("not used")
        override suspend fun isBootstrapSessionActive(): Boolean = false
    }

    private fun deviceIdentity(id: PeerId) = DeviceIdentity(
        deviceId = id,
        signingKey = SigningPublicKey(byteArrayOf(1)),
        encryptionKey = EncryptionPublicKey(byteArrayOf(2)),
    )

    private fun harness(
        canServe: Boolean = true,
        serveResult: Boolean = true,
    ): Triple<FileInboundHandler, FakeIngest, FakePusher> {
        val identity = FakeIdentityResolverForRouter(
            localDevice = deviceIdentity(localDevice),
            peersByAccount = mapOf(holderAccount to listOf(holderDevice)),
        )
        val clock = FakeClock(now)
        val ctx = RoutingContext(
            localIdentity = identity,
            localDeviceId = localDevice,
            devices = identity,
            routes = identity,
            packetDeduplicator = InMemoryPacketDeduplicator(),
            envelopeProtectionService = OpenOnlyProtection(
                FilePayload.Request(fileId, 300_000L, emptyList()),
                now,
            ),
            torTransport = RecordingTorTransport(),
            webRtcTransport = RecordingWebRtcTransport(),
            clock = clock,
            routerConfig = MutableStateFlow(RouterConfig()),
            transportLimits = MutableStateFlow(testTransportLimits()),
        )
        val ingest = FakeIngest(canServe)
        val pusher = FakePusher().also { it.serveResult = serveResult }
        val incomingTransfers = FileIncomingTransfers(FakeIncoming(), clock)
        val signals = object : FileTransferSignals {
            override suspend fun coverageAck(
                transferId: TransferId,
                from: PeerId,
                contiguousChunks: Int,
                missing: List<ChunkRange>,
            ) = Unit

            override suspend fun complete(transferId: TransferId, from: PeerId) = Unit
            override suspend fun cancelled(
                transferId: TransferId,
                from: PeerId,
                reason: FileCancelReason,
            ) = Unit
        }
        val handler = FileInboundHandler(
            ctx = ctx,
            target = FileTargetHandler(
                ctx, incomingTransfers, ingest, signals, onOfferReceived = {},
            ),
            door = FileRelayDoor(
                ctx, InMemoryFileRelayStore(),
                TrackingPacketOutbox(),
                onRelayWhole = {},
            ),
            serve = FileServeHandler(ctx, ingest, pusher),
        )
        return Triple(handler, ingest, pusher)
    }

    private fun requestEnvelope(fromOffset: Long = 300_000L): BinaryEnvelope {
        val fileEnvelope = FileEnvelope(
            transferId = TransferId(Uuid.random()),
            source = holderDevice,
            target = localDevice,
            createdAt = now,
            nonce = ByteArray(12),
            securityScheme = SignalSecurityScheme.ENCRYPTED_AND_SIGNED,
            signature = null,
            payload = FilePayload.Request(fileId, fromOffset, emptyList()).encode(),
        )
        return BinaryEnvelope(
            packetId = Uuid.random(),
            packetType = PacketType.FILE,
            dispositionRequested = true,
            createdAt = now,
            expiresAt = now + 90.seconds,
            source = holderDevice,
            target = localDevice,
            payload = fileEnvelope.encode(),
        )
    }

    @Test
    fun requestWithGatePass_servesFromHeldOffset() = runTest {
        val (handler, ingest, pusher) = harness(canServe = true)

        val result = handler.handle(requestEnvelope(fromOffset = 300_000L))

        assertIs<InboundHandleResult.Success>(result)
        assertEquals(listOf(fileId to holderAccount), ingest.servedFor)
        assertEquals(listOf(Triple(fileId, holderDevice, 300_000L)), pusher.serves)
    }

    @Test
    fun requestWithGateRefusal_isDeclinedAtOnce() = runTest {
        val (handler, _, pusher) = harness(canServe = false)

        val result = handler.handle(requestEnvelope())

        assertEquals(InboundHandleResult.Rejected(PacketNackReason.DECLINED), result)
        assertTrue(pusher.serves.isEmpty(), "no serve started on refusal")
    }

    @Test
    fun requestWhenNotHeld_isDeclinedAtOnce() = runTest {
        val (handler, _, pusher) = harness(canServe = true, serveResult = false)

        val result = handler.handle(requestEnvelope())

        assertEquals(InboundHandleResult.Rejected(PacketNackReason.DECLINED), result)
    }
}
