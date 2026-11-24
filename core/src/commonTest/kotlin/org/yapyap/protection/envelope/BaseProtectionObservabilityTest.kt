package org.yapyap.protection.envelope

import kotlinx.coroutines.test.runTest
import org.yapyap.protection.FixturePeerIds
import org.yapyap.protocol.ContentId
import org.yapyap.protocol.EnvelopeObservability
import org.yapyap.protocol.FieldSensitivity
import org.yapyap.protocol.PacketType
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.envelopes.BootstrapHeader
import org.yapyap.protocol.envelopes.BootstrapSecurityScheme
import org.yapyap.protocol.envelopes.FileCancelReason
import org.yapyap.protocol.envelopes.FileHeader
import org.yapyap.protocol.envelopes.MessageHeader
import org.yapyap.protocol.envelopes.SignalHeader
import org.yapyap.protocol.envelopes.SystemPayload
import org.yapyap.protocol.envelopes.TransferShape
import org.yapyap.protocol.envelopes.WebRtcSignalKind
import org.yapyap.testfixtures.epochSeconds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * The observability contract after the envelope redesign: every routing header's
 * [observableValues] must stay inside its [EnvelopeObservability] profile, and no PROTECTED
 * file field may appear in a routing header. SYSTEM content sensitivity is gone — SYSTEM is
 * header-only and hop-signed.
 */
class BaseProtectionObservabilityTest {

    private fun shape() = TransferShape(chunkSizeBytes = 64 * 1024, byteFrom = 0L, byteTo = 1000L)

    @Test
    fun messageHeader_observableValues_matchPolicy() = runTest {
        val header = MessageHeader(origin = FixturePeerIds.A, finalTarget = FixturePeerIds.B)
        assertEquals(
            EnvelopeObservability.messageHeader.fields.keys,
            header.observableValues().keys,
        )
        assertTrue(EnvelopeObservability.messageHeader.fields.values.all { it == FieldSensitivity.ROUTING_VISIBLE })
    }

    @Test
    fun signalHeader_observableValues_matchPolicy() = runTest {
        val header = SignalHeader(kind = WebRtcSignalKind.ICE)
        assertEquals(
            EnvelopeObservability.signalHeader.fields.keys,
            header.observableValues().keys,
        )
    }

    @Test
    fun bootstrapHeader_observableValues_matchPolicy() = runTest {
        val header = BootstrapHeader(BootstrapSecurityScheme.SECRET_AEAD, Uuid.random())
        assertEquals(
            EnvelopeObservability.bootstrapHeader.fields.keys,
            header.observableValues().keys,
        )
        assertTrue(EnvelopeObservability.bootstrapHeader.fields.values.all { it == FieldSensitivity.ROUTING_VISIBLE })
    }

    @Test
    fun fileHeaders_observableValues_stayInsidePolicy() = runTest {
        val transfer = org.yapyap.protocol.TransferId(Uuid.random())
        val headers: List<FileHeader> = listOf(
            FileHeader.Offer(transfer, FixturePeerIds.A, FixturePeerIds.B, shape()),
            FileHeader.Chunk(transfer, 0),
            FileHeader.Ack(transfer, 0, emptyList()),
            FileHeader.Cancel(transfer, FileCancelReason.DECLINED),
            FileHeader.Complete(ContentId(ByteArray(32) { 9 })),
            FileHeader.Request,
        )
        val policyKeys = EnvelopeObservability.fileHeader.fields.keys
        for (header in headers) {
            assertTrue(policyKeys.containsAll(header.observableValues().keys), "leak in $header")
        }
    }

    @Test
    fun fileHeaders_neverExposeProtectedFields() = runTest {
        val transfer = org.yapyap.protocol.TransferId(Uuid.random())
        val headers: List<FileHeader> = listOf(
            FileHeader.Offer(transfer, FixturePeerIds.A, FixturePeerIds.B, shape()),
            FileHeader.Chunk(transfer, 0),
            FileHeader.Ack(transfer, 0, emptyList()),
            FileHeader.Cancel(transfer, FileCancelReason.DECLINED),
            FileHeader.Complete(ContentId(ByteArray(32) { 9 })),
            FileHeader.Request,
        )
        val protectedKeys = EnvelopeObservability.fileHeader.fields
            .filterValues { it == FieldSensitivity.PROTECTED }.keys
        assertTrue(protectedKeys.isNotEmpty(), "the file profile must keep PROTECTED content fields")
        for (header in headers) {
            assertTrue(header.observableValues().keys.none { it in protectedKeys })
        }
    }

    @Test
    fun binaryEnvelope_observableHeaderValues_matchPolicy() = runTest {
        val now = epochSeconds(1_700_000_000L)
        val envelope = BinaryEnvelope(
            packetId = Uuid.random(),
            packetType = PacketType.MESSAGE,
            dispositionRequested = false,
            createdAt = now,
            expiresAt = now,
            source = FixturePeerIds.A,
            target = FixturePeerIds.B,
            routingHeader = byteArrayOf(),
            content = byteArrayOf(),
            signature = ByteArray(BinaryEnvelope.SIGNATURE_BYTES),
        )
        assertEquals(
            EnvelopeObservability.binaryEnvelope.fields.keys,
            envelope.observableHeaderValues().keys,
        )
    }

    @Test
    fun contentSensitivities_ratchetFamiliesProtected_systemAbsent() = runTest {
        assertEquals(FieldSensitivity.PROTECTED, EnvelopeObservability.content[PacketType.MESSAGE])
        assertEquals(FieldSensitivity.PROTECTED, EnvelopeObservability.content[PacketType.SIGNAL])
        assertEquals(FieldSensitivity.PROTECTED, EnvelopeObservability.content[PacketType.FILE])
        assertEquals(FieldSensitivity.ENDPOINT_VISIBLE, EnvelopeObservability.content[PacketType.BOOTSTRAP])
        // SYSTEM has no content entry: it is header-only.
        assertTrue(!EnvelopeObservability.content.containsKey(PacketType.SYSTEM))
    }

    @Test
    fun systemHeader_profile_isRoutingVisible() = runTest {
        assertTrue(EnvelopeObservability.systemHeader.fields.isNotEmpty())
        assertTrue(EnvelopeObservability.systemHeader.fields.values.all { it == FieldSensitivity.ROUTING_VISIBLE })
        // Every SYSTEM payload kind encodes with its kind byte first.
        val ack = SystemPayload.PacketAck(Uuid.random(), PacketType.MESSAGE)
        assertEquals(ack, SystemPayload.decode(ack.encode()))
    }
}
