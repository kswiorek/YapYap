package org.yapyap.persistence.crypto

import org.yapyap.crypto.e2ee.CryptoWireLimits
import org.yapyap.crypto.e2ee.RatchetSkippedKeyId
import org.yapyap.crypto.e2ee.SessionWireCodec
import kotlin.collections.iterator

internal object RatchetSkippedKeysCodec {

    fun encode(skipped: Map<RatchetSkippedKeyId, ByteArray>): ByteArray {
        var size = 4
        for ((keyId, messageKey) in skipped) {
            size += 4 + keyId.dhPublicKey.size + 4 + 4 + messageKey.size
        }
        CryptoWireLimits.requireSkippedKeysBlobSize(size)
        val bytes = ByteArray(size)
        var offset = SessionWireCodec.writeInt(bytes, 0, skipped.size)
        for ((keyId, messageKey) in skipped) {
            CryptoWireLimits.requireDhPublicKeySize(keyId.dhPublicKey.size)
            CryptoWireLimits.requireSkippedMessageKeySize(messageKey.size)
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
        CryptoWireLimits.requireSkippedKeysBlobSize(bytes.size)
        var offset = 0
        val count = SessionWireCodec.readInt(bytes, offset)
        require(count in 0..CryptoWireLimits.MAX_SKIPPED_KEYS_COUNT) {
            "skipped key count $count exceeds max ${CryptoWireLimits.MAX_SKIPPED_KEYS_COUNT}"
        }
        offset += 4
        val result = LinkedHashMap<RatchetSkippedKeyId, ByteArray>(count)
        repeat(count) {
            val (dhPublicKey, nextOffset) = SessionWireCodec.readByteArrayAt(
                bytes,
                offset,
                CryptoWireLimits.MAX_DH_PUBLIC_KEY_BYTES,
            )
            offset = nextOffset
            val messageNumber = SessionWireCodec.readInt(bytes, offset)
            offset += 4
            val (messageKey, endOffset) = SessionWireCodec.readByteArrayAt(
                bytes,
                offset,
                CryptoWireLimits.MAX_MESSAGE_KEY_BYTES,
            )
            offset = endOffset
            result[RatchetSkippedKeyId(dhPublicKey, messageNumber)] = messageKey
        }
        require(offset == bytes.size) { "trailing bytes in skipped message keys blob" }
        return result
    }
}