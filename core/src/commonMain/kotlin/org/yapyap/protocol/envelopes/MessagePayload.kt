package org.yapyap.protocol.envelopes

import org.yapyap.protocol.ByteReader
import org.yapyap.protocol.MessagePayloadFamily
import kotlin.uuid.Uuid

/**
 * What MESSAGE content carries, once opened. Decoding is a hierarchy of dispatches:
 *
 * 1. [decode] reads the family byte ([MessagePayloadFamily]) and runs that family's decode.
 * 2. [DagPayload.decode] reads the DAG node-type byte and runs the node's decode;
 *    [SyncPayload.decode] reads the sync verb byte the same way.
 * 3. Each concrete type decodes its own bytes.
 *
 * The split that matters is [DagPayload] vs [SyncPayload]. A DAG payload is history — fan-out,
 * relay backed, deduped by [DagPayload.messageId]; a sync payload is point-to-point pull control.
 * They share the envelope's protection and wire family, never their vocabulary: the
 * orchestrator's DAG pipeline only ever receives [DagPayload].
 */
internal sealed interface MessagePayload {
    /** Envelope correlation id (the message id for a DAG payload, a fresh id for a request). */
    val contentId: Uuid

    /** First wire byte: which family's decode runs. */
    val family: MessagePayloadFamily

    fun encode(): ByteArray

    companion object {
        /** Reads the family byte, then runs that family's decode. */
        fun decode(bytes: ByteArray): MessagePayload {
            val reader = ByteReader(bytes)
            return when (MessagePayloadFamily.fromWireValue(reader.readByte())) {
                MessagePayloadFamily.DAG -> DagPayload.decode(bytes)
                MessagePayloadFamily.SYNC -> SyncPayload.decode(bytes)
            }
        }
    }
}
