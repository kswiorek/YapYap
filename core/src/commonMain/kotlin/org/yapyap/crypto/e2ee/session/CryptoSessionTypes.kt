package org.yapyap.crypto.e2ee.session

import org.yapyap.crypto.e2ee.policy.OpkOfferBinding
import org.yapyap.protocol.PeerId

/**
 * Protocol-invariant wire-format constraints for crypto session payloads. These are fixed by the
 * cryptographic algorithms and wire format, not by transport capacity — they never change at runtime.
 *
 * Capacity limits (max frame/body/plaintext/control bytes) that depend on transport limits live in
 * [CryptoLimits] and are enforced by [CryptoWireCodec].
 */
object CryptoWireLimits {
    /** X25519 keys are stored in DER form in this stack (~44 bytes); cap prevents hostile oversize. */
    const val MAX_DH_PUBLIC_KEY_BYTES: Int = 64
    const val MAX_X3DH_EPHEMERAL_KEY_BYTES: Int = 64
    const val MAX_STRING_ID_BYTES: Int = 256
    const val MAX_OPK_PUBLIC_KEY_BYTES: Int = 64
    const val MAX_SESSION_BINDING_BYTES: Int = OpkOfferBinding.BINDING_LENGTH
    const val MAX_SKIPPED_KEYS_COUNT: Int = 256

    fun requireDhPublicKeySize(size: Int) {
        require(size in 1..MAX_DH_PUBLIC_KEY_BYTES) {
            "DH public key size $size exceeds max $MAX_DH_PUBLIC_KEY_BYTES"
        }
    }

    fun requireX3dhEphemeralKeySize(size: Int) {
        require(size in 1..MAX_X3DH_EPHEMERAL_KEY_BYTES) {
            "X3DH ephemeral key size $size exceeds max $MAX_X3DH_EPHEMERAL_KEY_BYTES"
        }
    }

    fun requireStringIdSize(size: Int) {
        require(size in 0..MAX_STRING_ID_BYTES) {
            "string id size $size exceeds max $MAX_STRING_ID_BYTES"
        }
    }

    fun requireOpkPublicKeySize(size: Int) {
        require(size in 1..MAX_OPK_PUBLIC_KEY_BYTES) {
            "OPK public key size $size exceeds max $MAX_OPK_PUBLIC_KEY_BYTES"
        }
    }

    fun requireSessionBindingSize(size: Int) {
        require(size == MAX_SESSION_BINDING_BYTES) {
            "session binding size $size must be $MAX_SESSION_BINDING_BYTES"
        }
    }
}

data class SessionWireFrame(
    val sessionEpoch: Int,
    val sessionGeneration: Int = 1,
    val outerHandshake: X3dhWireInfo?,   // epoch-1 initiator first message only
    val ratchet: RatchetCiphertext,
) {
    companion object {
        /**
         * Max header bytes added by the wire codec around the ratchet ciphertext (worst case: an
         * epoch-1 first message with the X3DH handshake attached).
         * MAGIC(4) + VERSION(1) + epoch(4) + generation(4) + hasOuter(1)
         * + outer length prefix(4) + outer content + ratchet length prefix(4).
         * Outer content worst case (using protocol caps): ephKey(4+64) + spkId(4+256) + epoch(4)
         * + generation(4) + mode(1) + opkFlag(1) + opkId(4+256) = 598, giving 14 + 4 + 598 + 4 = 620.
         */
        const val MAX_HEADER_BYTES: Int = 620
    }
}

sealed interface RatchetInnerPlaintext {
    val bytes: ByteArray
    data class Payload(override val bytes: ByteArray) : RatchetInnerPlaintext

    data class WithControl(
        override val bytes: ByteArray,
        val control: InnerSessionControl?,
    ) : RatchetInnerPlaintext

    companion object {
        /** Bytes added by the wire codec around the application plaintext: kind(1) + length prefix(4). */
        const val ENCODED_OVERHEAD: Int = 5
    }
}

sealed interface InnerSessionControl {
    data class OpkOffer(
        val sessionEpoch: Int,
        val sessionGeneration: Int,
        val opkId: String,
        val opkPublicKey: ByteArray,
        val sessionBinding: ByteArray,
    ) : InnerSessionControl
}


data class CryptoSessionRecord(
    val peerDeviceId: PeerId,
    val sessionEpoch: Int,
    val ratchetState: RatchetSessionState,
    val meta: CryptoSessionMeta,
    val canonical: Boolean,
)

data class CryptoSessionMeta(
    val role: SessionRole,
    val x3dhMode: X3dhMode,
    val handshakeSpkId: String,
    val handshakeOpkId: String? = null,
    val initiatorEphemeralPrivateKey: ByteArray? = null,
    val initiatorEphemeralPublicKey: ByteArray? = null,
    val offeredOpkId: String? = null,
    val status: SessionStatus = SessionStatus.ACTIVE,
    val sessionGeneration: Int = 1,
    val createdAtEpochSeconds: Long,
    val updatedAtEpochSeconds: Long,
)

enum class SessionRole { INITIATOR, RESPONDER }
enum class SessionStatus { ACTIVE, PENDING, SUPERSEDED }
