package org.yapyap.protocol

/**
 * A file seal's symmetric key (ChaCha20-Poly1305): every chunk of one seal is encrypted under
 * it, whatever hop carries the chunk. It travels only inside the seal itself (ratchet-encrypted
 * to the seal's target device) and never outlives the seal. Wraps the bytes, never prints them.
 *
 * Public only because the generated SQLDelight row classes are: the constructor and the bytes are
 * internal, so nothing outside `core` can make a key or read one.
 */
public class SealKey internal constructor(internal val bytes: ByteArray) {
    init {
        require(bytes.size == SEAL_KEY_BYTES) {
            "SealKey must be $SEAL_KEY_BYTES bytes but was ${bytes.size}"
        }
    }

    override fun equals(other: Any?): Boolean =
        this === other || (other is SealKey && bytes.contentEquals(other.bytes))

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = "SealKey(redacted)"
}

/**
 * SHA-256 of an envelope's content: what the hop signature covers, and for a file OFFER the
 * seal's id. Derived, never on the wire except where a packet names another packet's content
 * (a seal-level COMPLETE names its seal). Hashing happens in protection; this only carries it.
 *
 * Public only because the generated SQLDelight row classes are.
 */
public class ContentId internal constructor(internal val bytes: ByteArray) {
    init {
        require(bytes.size == CONTENT_ID_BYTES) {
            "ContentId must be $CONTENT_ID_BYTES bytes but was ${bytes.size}"
        }
    }

    override fun equals(other: Any?): Boolean =
        this === other || (other is ContentId && bytes.contentEquals(other.bytes))

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = bytes.toHexString()
}

internal const val SEAL_KEY_BYTES: Int = 32
internal const val CONTENT_ID_BYTES: Int = 32
