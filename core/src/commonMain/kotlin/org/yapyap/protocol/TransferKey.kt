package org.yapyap.protocol

/**
 * Per-transfer symmetric key (design §4): one key never outlives one transfer.
 * Wraps the bytes, never prints them. Exactly [TRANSFER_KEY_BYTES] bytes.
 *
 * Public only because the generated SQLDelight row classes are: the constructor and the bytes are
 * internal, so nothing outside `core` can make a key or read one.
 */
public class TransferKey internal constructor(internal val bytes: ByteArray) {
    init {
        require(bytes.size == TRANSFER_KEY_BYTES) {
            "TransferKey must be $TRANSFER_KEY_BYTES bytes but was ${bytes.size}"
        }
    }

    override fun equals(other: Any?): Boolean =
        this === other || (other is TransferKey && bytes.contentEquals(other.bytes))

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = "TransferKey(redacted)"
}
