package org.yapyap.protection

import org.yapyap.crypto.CryptoException
import org.yapyap.crypto.e2ee.CryptoSessionException

enum class ProtectionDisposition {
    /** Transient — the same logical message may succeed later. */
    RETRYABLE,

    /** Corrupt, authenticated-as-bad, or replay — resending the same bytes will not help. */
    PERMANENT,

    /** Session not ready for this message yet — wait for prerequisites. */
    DEFER,
}

enum class ProtectionReason {
    WIRE_DECODE,
    AUTH,
    IDENTITY,
    SESSION,
    SESSION_GAP,
    SESSION_VIOLATION,
}

enum class AuthenticationReason {
    MISSING_SIGNATURE,
    INVALID_SIGNATURE,
    DECRYPT_AUTH_FAILED,
}

sealed class ProtectionException(
    message: String,
    val disposition: ProtectionDisposition,
    val reason: ProtectionReason,
    cause: Throwable? = null,
) : Exception(message, cause) {

    class InvalidEnvelope(cause: Throwable? = null) :
        ProtectionException(
            message = "Invalid envelope",
            disposition = ProtectionDisposition.PERMANENT,
            reason = ProtectionReason.WIRE_DECODE,
            cause = cause,
        )

    class AuthenticationFailed(
        val authReason: AuthenticationReason,
        cause: Throwable? = null,
    ) : ProtectionException(
        message = when (authReason) {
            AuthenticationReason.MISSING_SIGNATURE -> "Signature missing"
            AuthenticationReason.INVALID_SIGNATURE -> "Signature verification failed"
            AuthenticationReason.DECRYPT_AUTH_FAILED -> "Decryption authentication failed"
        },
        disposition = ProtectionDisposition.PERMANENT,
        reason = ProtectionReason.AUTH,
        cause = cause,
    )

    class IdentityNotReady(cause: CryptoException) :
        ProtectionException(
            message = "Identity not ready",
            disposition = ProtectionDisposition.RETRYABLE,
            reason = ProtectionReason.IDENTITY,
            cause = cause,
        )

    class SessionNotReady(cause: CryptoSessionException) :
        ProtectionException(
            message = "Session not ready",
            disposition = ProtectionDisposition.RETRYABLE,
            reason = ProtectionReason.SESSION,
            cause = cause,
        )

    class SessionGap(cause: CryptoSessionException) :
        ProtectionException(
            message = "Session gap",
            disposition = ProtectionDisposition.DEFER,
            reason = ProtectionReason.SESSION_GAP,
            cause = cause,
        )

    class SessionViolation(cause: CryptoSessionException) :
        ProtectionException(
            message = "Session violation",
            disposition = ProtectionDisposition.PERMANENT,
            reason = ProtectionReason.SESSION_VIOLATION,
            cause = cause,
        )

    companion object {
        fun mapCryptoSessionException(error: CryptoSessionException): ProtectionException =
            when (error) {
                is CryptoSessionException.Replay,
                is CryptoSessionException.MessageSkipExceeded,
                -> SessionViolation(error)
                is CryptoSessionException.SupersededDhChain -> SessionGap(error)
                is CryptoSessionException.NoSession,
                is CryptoSessionException.HandshakeRequired,
                is CryptoSessionException.HandshakeMismatch,
                is CryptoSessionException.MissingOfferedOpk,
                is CryptoSessionException.OpkConsumeFailed,
                is CryptoSessionException.MissingInitiatorEphemeral,
                -> SessionNotReady(error)
            }

        fun mapDecryptFailure(error: Exception): ProtectionException =
            when (error) {
                is ProtectionException -> error
                is CryptoSessionException -> mapCryptoSessionException(error)
                is CryptoException -> IdentityNotReady(error)
                else -> AuthenticationFailed(AuthenticationReason.DECRYPT_AUTH_FAILED, error)
            }

        fun map(error: Exception): ProtectionException =
            when (error) {
                is ProtectionException -> error
                is CryptoSessionException -> mapCryptoSessionException(error)
                is CryptoException -> IdentityNotReady(error)
                else -> InvalidEnvelope(error)
            }
    }
}
