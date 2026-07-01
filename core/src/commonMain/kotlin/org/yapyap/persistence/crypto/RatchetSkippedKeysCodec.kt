package org.yapyap.persistence.crypto

import org.yapyap.crypto.e2ee.CryptoWireLimits
import org.yapyap.crypto.e2ee.RatchetSkippedKeyId
import org.yapyap.protocol.ByteReader
import org.yapyap.protocol.ByteWriter

internal object RatchetSkippedKeysCodec {

    fun encode(skipped: Map<RatchetSkippedKeyId, ByteArray>): ByteArray {
        val writer = ByteWriter(4 + skipped.size * 32)
        writer.writeInt(skipped.size)
        for ((keyId, messageKey) in skipped) {
            CryptoWireLimits.requireDhPublicKeySize(keyId.dhPublicKey.size)
            CryptoWireLimits.requireSkippedMessageKeySize(messageKey.size)
            writer.writeByteArray(keyId.dhPublicKey, CryptoWireLimits.MAX_DH_PUBLIC_KEY_BYTES)
            writer.writeInt(keyId.messageNumber)
            writer.writeByteArray(messageKey, CryptoWireLimits.MAX_MESSAGE_KEY_BYTES)
        }
        val bytes = writer.toByteArray()
        CryptoWireLimits.requireSkippedKeysBlobSize(bytes.size)
        return bytes
    }

    fun decode(bytes: ByteArray): Map<RatchetSkippedKeyId, ByteArray> {
        if (bytes.isEmpty()) {
            return emptyMap()
        }
        CryptoWireLimits.requireSkippedKeysBlobSize(bytes.size)
        val reader = ByteReader(bytes)
        val count = reader.readInt()
        require(count in 0..CryptoWireLimits.MAX_SKIPPED_KEYS_COUNT) {
            "skipped key count $count exceeds max ${CryptoWireLimits.MAX_SKIPPED_KEYS_COUNT}"
        }
        val result = LinkedHashMap<RatchetSkippedKeyId, ByteArray>(count)
        repeat(count) {
            val dhPublicKey = reader.readByteArray(CryptoWireLimits.MAX_DH_PUBLIC_KEY_BYTES)
            val messageNumber = reader.readInt()
            val messageKey = reader.readByteArray(CryptoWireLimits.MAX_MESSAGE_KEY_BYTES)
            result[RatchetSkippedKeyId(dhPublicKey, messageNumber)] = messageKey
        }
        reader.requireFullyRead()
        return result
    }
}
