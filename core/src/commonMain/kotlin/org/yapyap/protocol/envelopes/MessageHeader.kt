package org.yapyap.protocol.envelopes

import org.yapyap.protocol.ByteReader
import org.yapyap.protocol.ByteWriter
import org.yapyap.protocol.PeerId

/**
 * The MESSAGE family's routing header: who wrote the content ([origin]) and whom it is for
 * ([finalTarget]). A direct send writes both too (`origin == source`, `finalTarget == target`),
 * so direct and relayed packets have one shape; a relay reads them to admit and forward the
 * packet. The content is the ratchet frame from [origin] to [finalTarget].
 */
internal data class MessageHeader(
    val origin: PeerId,
    val finalTarget: PeerId,
) {
    fun encode(): ByteArray {
        val writer = ByteWriter(ENCODED_BYTES)
        writer.writePeerId(origin)
        writer.writePeerId(finalTarget)
        return writer.toByteArray()
    }

    fun observableValues(): Map<String, Any?> = mapOf(
        Fields.ORIGIN to origin,
        Fields.FINAL_TARGET to finalTarget,
    )

    companion object {
        object Fields {
            const val ORIGIN = "origin"
            const val FINAL_TARGET = "finalTarget"
        }

        /** origin(2+64) + finalTarget(2+64), for a 64-char hex [PeerId]. */
        const val ENCODED_BYTES: Int = 132

        fun decode(bytes: ByteArray): MessageHeader {
            val reader = ByteReader(bytes)
            val header = MessageHeader(origin = reader.readPeerId(), finalTarget = reader.readPeerId())
            reader.requireFullyRead()
            return header
        }
    }
}
