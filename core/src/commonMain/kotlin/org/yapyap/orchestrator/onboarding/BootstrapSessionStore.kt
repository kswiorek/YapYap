package org.yapyap.orchestrator.onboarding

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.yapyap.protection.envelope.BootstrapKeySource

/**
 * Holds the active one-time bootstrap secret: the newcomer's generated secret while on-boarding,
 * or the sponsor's scanned secret while sponsoring. Implements [BootstrapKeySource] for the
 * [org.yapyap.protection.envelope.BootstrapIntroProtection]; a null key is the "not on-boarding" gate.
 *
 * TODO(sprint 4 onboarding): persist the secret in the keyring-backed [org.yapyap.persistence.key.KeyStore]
 * so an onboarding interrupted by an app restart survives; burn ([burn]) is called at onboarding COMPLETE.
 */
class BootstrapSessionStore : BootstrapKeySource {
    private val mutex = Mutex()
    private var secret: ByteArray? = null

    override suspend fun introKey(): ByteArray? = mutex.withLock { secret }

    /** Set the active secret (newcomer: generated at provisioning; sponsor: parsed from the scanned QR). */
    suspend fun setActiveSecret(value: ByteArray) {
        mutex.withLock { secret = value }
    }

    /** One-time secrets must be burned once onboarding completes (or the intro is acknowledged). */
    suspend fun burn() {
        mutex.withLock {
            secret?.fill(0)
            secret = null
        }
    }
}