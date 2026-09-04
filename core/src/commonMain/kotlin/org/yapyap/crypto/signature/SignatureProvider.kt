package org.yapyap.crypto.signature

import org.yapyap.crypto.identity.AccountId
import org.yapyap.protocol.PeerId

/** Result of classifying a message's claimed authorship into a three-valued outcome. */
enum class AuthorshipOutcome {
    /** Signature is cryptographically valid against a known author key and the author binds to the claimed account. */
    VALID,

    /** The payload is demonstrably not by the claimed author (bad/missing signature or author/account mismatch). */
    INVALID,

    /** Did not verify because the author's key/roster is not yet known to us — defer until identity state catches up. */
    UNKNOWN_AUTHOR,
}

interface SignatureProvider {
    suspend fun sign(message: ByteArray): ByteArray

    suspend fun verify(deviceId: PeerId, message: ByteArray, signature: ByteArray): Boolean

    suspend fun verifyMessageAuthorship(
        accountId: AccountId,
        authorDeviceId: PeerId,
        signedBytes: ByteArray,
        signature: ByteArray,
    ): Boolean

    /**
     * Three-valued authorship classification used by the DAG engine to assign a message its
     * [org.yapyap.persistence.db.VerificationState].
     *
     * Default implementation collapses to the [Boolean] form and cannot distinguish `UNKNOWN_AUTHOR`
     * from `INVALID`; concrete providers that resolve keys from live state should override it.
     */
    suspend fun classifyMessageAuthorship(
        accountId: AccountId,
        authorDeviceId: PeerId,
        signedBytes: ByteArray,
        signature: ByteArray?,
    ): AuthorshipOutcome {
        if (signature == null) return AuthorshipOutcome.INVALID
        return if (verifyMessageAuthorship(accountId, authorDeviceId, signedBytes, signature)) {
            AuthorshipOutcome.VALID
        } else {
            AuthorshipOutcome.INVALID
        }
    }
}
