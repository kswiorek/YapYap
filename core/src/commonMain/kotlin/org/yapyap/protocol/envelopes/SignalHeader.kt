package org.yapyap.protocol.envelopes

import org.yapyap.protocol.ByteReader
import org.yapyap.protocol.ByteWriter

/**
 * The SIGNAL family's routing header: only the signal's [kind]. Signals are always direct (one
 * hop), and the content is the ratchet frame carrying the signal's payload.
 */
internal data class SignalHeader(val kind: WebRtcSignalKind) {
    fun encode(): ByteArray {
        val writer = ByteWriter(1)
        writer.writeByte(kind.wireValue)
        return writer.toByteArray()
    }

    fun observableValues(): Map<String, Any?> = mapOf(Fields.KIND to kind)

    companion object {
        object Fields {
            const val KIND = "kind"
        }

        fun decode(bytes: ByteArray): SignalHeader {
            val reader = ByteReader(bytes)
            val header = SignalHeader(WebRtcSignalKind.fromWireValue(reader.readByte()))
            reader.requireFullyRead()
            return header
        }
    }
}
