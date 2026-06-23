package org.yapyap.backend.db

import org.yapyap.backend.crypto.e2ee.RatchetSkippedKeyId
import org.yapyap.backend.crypto.e2ee.SessionWireCodec

internal object RatchetSkippedKeysCodec {

    fun encode(skipped: Map<RatchetSkippedKeyId, ByteArray>): ByteArray {
        var size = 4
        for ((keyId, messageKey) in skipped) {
            size += 4 + keyId.dhPublicKey.size + 4 + 4 + messageKey.size
        }
        val bytes = ByteArray(size)
        var offset = SessionWireCodec.writeInt(bytes, 0, skipped.size)
        for ((keyId, messageKey) in skipped) {
            offset = SessionWireCodec.writeByteArray(bytes, offset, keyId.dhPublicKey)
            offset = SessionWireCodec.writeInt(bytes, offset, keyId.messageNumber)
            offset = SessionWireCodec.writeByteArray(bytes, offset, messageKey)
        }
        return bytes
    }

    fun decode(bytes: ByteArray): Map<RatchetSkippedKeyId, ByteArray> {
        if (bytes.isEmpty()) {
            return emptyMap()
        }
        var offset = 0
        val count = SessionWireCodec.readInt(bytes, offset)
        offset += 4
        val result = LinkedHashMap<RatchetSkippedKeyId, ByteArray>(count)
        repeat(count) {
            val (dhPublicKey, nextOffset) = SessionWireCodec.readByteArrayAt(bytes, offset)
            offset = nextOffset
            val messageNumber = SessionWireCodec.readInt(bytes, offset)
            offset += 4
            val (messageKey, endOffset) = SessionWireCodec.readByteArrayAt(bytes, offset)
            offset = endOffset
            result[RatchetSkippedKeyId(dhPublicKey, messageNumber)] = messageKey
        }
        require(offset == bytes.size) { "trailing bytes in skipped message keys blob" }
        return result
    }
}
