package org.yapyap.routing.file

import kotlinx.coroutines.test.runTest
import org.yapyap.protocol.FileId
import org.yapyap.protocol.PacketType
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.envelopes.FileHeader
import org.yapyap.protocol.envelopes.FileRequest
import org.yapyap.protocol.envelopes.PacketNackReason
import org.yapyap.routing.file.port.FileManifest
import org.yapyap.routing.router.InboundHandleResult
import org.yapyap.routing.store.FileSealKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * Holder side: a sealed REQUEST is answered by serving — gate pass + fresh floor-aligned
 * transfer — or DECLINED so the requester moves on at once.
 */
class FileInboundServeTest {

    private val localDevice = PeerId("serve-local-device-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
    private val holderDevice = PeerId("serve-holder-device-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
    private val holderAccount = org.yapyap.protocol.AccountId("serve-holder-account")
    private val fileId = FileId("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
    private val chunkSize = 220 * 1024
    private val totalBytes = 1_000_000L

    private fun harness(
        serveAllowed: Boolean = true,
        held: Boolean = true,
    ): FileInboundHarness {
        val ports = ScriptedFilePorts(
            serveAllowed = serveAllowed,
            coverage = org.yapyap.routing.file.port.FileCoverage(0L, emptyList()),
            totalBytes = totalBytes,
        )
        val plaintext = ScriptedPlaintext(
            describeImpl = { id ->
                if (held && id == fileId) {
                    FileManifest(
                        fileId = id,
                        totalBytes = totalBytes,
                        blockSize = 1024 * 1024,
                        blockHashes = emptyList(),
                        fileName = "big.bin",
                        mimeType = "application/octet-stream",
                    )
                } else null
            },
        )
        return fileInboundHarness(
            localId = localDevice,
            remoteId = holderDevice,
            remoteAccount = holderAccount,
            ports = ports,
            plaintext = plaintext,
            chunkSize = chunkSize,
        )
    }

    private suspend fun requestEnvelope(h: FileInboundHarness, fromOffset: Long = 300_000L): BinaryEnvelope {
        val content = h.fileProtection.sealRequest(
            FileRequest(fileId, fromOffset, emptyList()),
            h.localId,
        )
        return BinaryEnvelope(
            packetId = Uuid.random(),
            packetType = PacketType.FILE,
            dispositionRequested = true,
            createdAt = h.clock.now(),
            expiresAt = h.clock.now() + 90.seconds,
            source = holderDevice,
            target = localDevice,
            routingHeader = FileHeader.Request.encode(),
            content = content,
            signature = ByteArray(BinaryEnvelope.SIGNATURE_BYTES),
        )
    }

    @Test
    fun requestWithGatePass_servesFromHeldOffset() = runTest {
        val h = harness(serveAllowed = true)

        val result = h.handler.handle(requestEnvelope(h, fromOffset = 300_000L))

        assertIs<InboundHandleResult.Success>(result)
        assertEquals(listOf(fileId to holderAccount), h.ports.servedFor)
        val seal = h.sealStore.byFileAndTarget(fileId, holderDevice).single()
        assertEquals(FileSealKind.SERVE, seal.kind)
        val transfer = h.transferStore.bySeal(seal.sealId).single()
        assertEquals(holderDevice, transfer.peer)
        assertEquals(
            (300_000L / chunkSize) * chunkSize,
            transfer.shape.byteFrom,
            "serve floor-aligns at the held offset"
        )
        assertEquals(totalBytes, transfer.shape.byteTo)
        val offer = h.outbox.enqueued.single()
        assertEquals(holderDevice, offer.target)
        val header = assertIs<FileHeader.Offer>(FileHeader.decode(offer.routingHeader))
        assertEquals(transfer.transferId, header.transferId)
        assertEquals(holderDevice, header.finalTarget)
    }

    @Test
    fun requestWithGateRefusal_isDeclinedAtOnce() = runTest {
        val h = harness(serveAllowed = false)

        val result = h.handler.handle(requestEnvelope(h))

        assertEquals(InboundHandleResult.Rejected(PacketNackReason.DECLINED), result)
        assertTrue(h.sealStore.byFile(fileId).isEmpty(), "no serve started on refusal")
        assertTrue(h.outbox.enqueued.isEmpty())
    }

    @Test
    fun requestWhenNotHeld_isDeclinedAtOnce() = runTest {
        val h = harness(serveAllowed = true, held = false)

        val result = h.handler.handle(requestEnvelope(h))

        assertEquals(InboundHandleResult.Rejected(PacketNackReason.DECLINED), result)
        assertTrue(h.sealStore.byFile(fileId).isEmpty(), "nothing held: no serve started")
        assertTrue(h.outbox.enqueued.isEmpty())
    }
}
