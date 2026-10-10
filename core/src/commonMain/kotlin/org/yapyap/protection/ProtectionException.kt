package org.yapyap.protection

import org.yapyap.crypto.CryptoException
import org.yapyap.crypto.e2ee.CryptoSessionException

internal enum class ProtectionDisposition {
    /** Corrupt, authenticated-as-bad, or replay — resending the same bytes will not help. */
    PERMANENT,

    /**
     * Prerequisites missing (session/identity not ready, unknown account) — the packet is
     * neither ACKed nor NACKed; dedup is cleared so a later resend is reprocessed fresh
     * once the prerequisite lands. See inboundResultForProtectionFailure.
     */
    DEFER,
}

internal enum class ProtectionReason {
    WIRE_DECODE,
    AUTH,
    IDENTITY,
    SESSION,
    SESSION_GAP,
    SESSION_VIOLATION,
}

internal enum class AuthenticationReason {
    MISSING_SIGNATURE,
    INVALID_SIGNATURE,
    DECRYPT_AUTH_FAILED,
}

internal sealed class ProtectionException(
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
        authReason: AuthenticationReason,
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
            disposition = ProtectionDisposition.DEFER,
            reason = ProtectionReason.IDENTITY,
            cause = cause,
        )

    class SessionNotReady(cause: CryptoSessionException) :
        ProtectionException(
            message = "Session not ready",
            disposition = ProtectionDisposition.DEFER,
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

    /**
     * Encrypt failed for a reason that is not a known crypto condition (DB, IO, locks).
     * DEFER — the failure is environmental until proven otherwise; the staged message is
     * kept for re-protection rather than dropped as permanently dead.
     */
    class EncryptNotReady(cause: Exception) :
        ProtectionException(
            message = "Message encryption not possible right now",
            disposition = ProtectionDisposition.DEFER,
            reason = ProtectionReason.SESSION,
            cause = cause,
        )

    /** No active onboarding session — the bootstrap gate. Never succeeds until a new onboarding starts. */
    class BootstrapSessionInactive :
        ProtectionException(
            message = "No active bootstrap onboarding session",
            disposition = ProtectionDisposition.PERMANENT,
            reason = ProtectionReason.IDENTITY,
        )

    companion object {
        fun mapCryptoSessionException(error: CryptoSessionException): ProtectionException =
            when (error) {
                is CryptoSessionException.Replay,
                is CryptoSessionException.MessageSkipExceeded,
                is CryptoSessionException.OversizedFrame,
                    -> SessionViolation(error)

                is CryptoSessionException.SupersededDhChain -> SessionGap(error)
                is CryptoSessionException.DecryptionFailed ->
                    AuthenticationFailed(AuthenticationReason.DECRYPT_AUTH_FAILED, error)

                is CryptoSessionException.NoSession,
                is CryptoSessionException.HandshakeRequired,
                is CryptoSessionException.HandshakeMismatch,
                is CryptoSessionException.MissingOfferedOpk,
                is CryptoSessionException.OpkConsumeFailed,
                is CryptoSessionException.MissingInitiatorEphemeral,
                    -> SessionNotReady(error)
            }

        fun mapEncryptDecryptFailure(error: Exception): ProtectionException =
            when (error) {
                is ProtectionException -> error
                is CryptoSessionException -> mapCryptoSessionException(error)
                is CryptoException -> IdentityNotReady(error)
                else -> AuthenticationFailed(AuthenticationReason.DECRYPT_AUTH_FAILED, error)
            }

        /**
         * Encrypt-side mapping: unknown failures are environmental (DB, IO) until proven
         * otherwise — keep the message (DEFER) rather than dropping it as permanently dead.
         * The decrypt side keeps [mapEncryptDecryptFailure]'s PERMANENT default (bad input).
         */
        fun mapEncryptFailure(error: Exception): ProtectionException =
            when (error) {
                is ProtectionException -> error
                is CryptoSessionException -> mapCryptoSessionException(error)
                is CryptoException -> IdentityNotReady(error)
                else -> EncryptNotReady(error)
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
