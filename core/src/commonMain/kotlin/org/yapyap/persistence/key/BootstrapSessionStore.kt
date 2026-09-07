package org.yapyap.persistence.key

import org.yapyap.crypto.identity.IdentityKeyPurpose

/**
 * Source of the active one-time bootstrap secret. Returns null when no onboarding session is active —
 * both the newcomer-side gate (a packet aimed at a node that isn't on-boarding) and the sponsor-side
 * guard (no scanned QR in flight) fall out of that.
 */
fun interface BootstrapKeySource {
    suspend fun introKey(): ByteArray?
}

/**
 * Holds the active one-time bootstrap secret: the newcomer's generated secret while on-boarding,
 * or the sponsor's scanned secret while sponsoring. Implements [BootstrapKeySource] for the
 * [org.yapyap.protection.envelope.BootstrapProtection]; a null key is the "not on-boarding" gate.
 *
 * The secret lives directly in the keyring-backed [KeyStore] — no in-memory cache. This matches the
 * signing-key path (which does uncached keystore roundtrips per packet on a far hotter path), and
 * `introKey()` only fires for SECRET_AEAD bootstrap packets anyway, so there is nothing to gain
 * from caching. Consequences of direct reads:
 *  - a missing key and an unreadable keyring both read as null (gate closed); the resulting
 *    protection failure dispositions back to the peer — see the ACK/NACK seam handoff item for the
 *    NACK-vs-retry follow-up;
 *  - there is no in-memory copy to zeroize: [burn] is just [KeyStore.deleteKey].
 */
class BootstrapSessionStore(
    private val keyStore: KeyStore,
) : BootstrapKeySource {
    override suspend fun introKey(): ByteArray? = keyStore.getKey(SECRET_REF)

    /**
     * Set the active secret (newcomer: generated at provisioning; sponsor: parsed from the
     * scanned QR). Persists first: if the keystore rejects the write the exception propagates
     * and no secret is held that we couldn't durably keep.
     */
    suspend fun setActiveSecret(value: ByteArray) {
        keyStore.putKey(SECRET_REF, value)
    }

    /** One-time secrets must be burned once onboarding completes (or is cancelled). */
    suspend fun burn() {
        keyStore.deleteKey(SECRET_REF)
    }

    companion object {
        private val SECRET_REF = KeyReference(
            keyId = "yapyap:bootstrap:secret",
            purpose = IdentityKeyPurpose.BOOTSTRAP_SECRET,
            type = KeyType.PRIVATE,
        )
    }
}
