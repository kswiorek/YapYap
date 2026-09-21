package org.yapyap.orchestrator.boot

import org.yapyap.crypto.primitives.CryptoProvider
import org.yapyap.persistence.db.IdentityStatus
import org.yapyap.persistence.key.*

/**
 * Pure boot check: probes DB rows + keystore presence + self [IdentityStatus]
 * and returns a [BootDiagnosis]. No side effects (no re-insert, no init).
 *
 * Decision table (peer count ignored — a genesis single-node network is Healthy):
 * - no rows + no keys → [BootDiagnosis.SetupRequired] (fresh and wiped are identical)
 * - rows + keys + key→ID binding + active SPK + self not BANNED → [BootDiagnosis.Healthy]
 *   (`null` status means "not yet known", never removed)
 * - anything partial, a key→ID mismatch, or a missing active SPK →
 *   `ResetRequired(INCONSISTENT_STORAGE)`
 * - self device/account BANNED → `ResetRequired(SELF_BANNED)`
 */
class BootDiagnoser(
    private val identityRepository: IdentityKeyRepository,
    private val keyStore: KeyStore,
    private val cryptoProvider: CryptoProvider,
) {
    suspend fun diagnose(): BootDiagnosis {
        val deviceRow = identityRepository.getLocalDeviceRecord()
        val accountRow = identityRepository.getLocalAccountRecord()
        val deviceSigningPriv = keyStore.getKey(deviceSigningPrivateRef())
        val deviceEncryptionPriv = keyStore.getKey(deviceEncryptionPrivateRef())
        val accountSigningPriv = keyStore.getKey(accountSigningPrivateRef())

        if (deviceRow == null && accountRow == null &&
            deviceSigningPriv == null && deviceEncryptionPriv == null && accountSigningPriv == null
        ) {
            return BootDiagnosis.SetupRequired
        }

        val missing = buildList {
            if (deviceRow == null) add("device row")
            if (accountRow == null) add("account row")
            if (deviceSigningPriv == null) add("device signing key")
            if (deviceEncryptionPriv == null) add("device encryption key")
            if (accountSigningPriv == null) add("account signing key")
        }
        if (missing.isNotEmpty()) {
            return BootDiagnosis.ResetRequired(
                reason = ResetReason.INCONSISTENT_STORAGE,
                details = "Partial local state, missing: ${missing.joinToString()}",
            )
        }

        // Key→ID binding: the rows must belong to the keys we hold.
        val device = checkNotNull(deviceRow)
        val account = checkNotNull(accountRow)
        val derivedDeviceId = cryptoProvider.peerIdFromPublicKey(
            cryptoProvider.privateSigningKeyToPublicKey(checkNotNull(deviceSigningPriv)),
        )
        val derivedAccountId = cryptoProvider.accountIdFromPublicKey(
            cryptoProvider.privateSigningKeyToPublicKey(checkNotNull(accountSigningPriv)),
        )
        if (derivedDeviceId != device.deviceId || derivedAccountId != account.accountId) {
            return BootDiagnosis.ResetRequired(
                reason = ResetReason.INCONSISTENT_STORAGE,
                details = "Key/row ID mismatch (keys do not derive the stored device/account IDs)",
            )
        }

        val deviceStatus = identityRepository.getDeviceStatus(device.deviceId)
        val accountStatus = identityRepository.getAccountStatus(account.accountId)
        if (deviceStatus == IdentityStatus.BANNED || accountStatus == IdentityStatus.BANNED) {
            return BootDiagnosis.ResetRequired(
                reason = ResetReason.SELF_BANNED,
                details = "Local device/account is BANNED (device=$deviceStatus, account=$accountStatus)",
            )
        }

        // Rotation keeps exactly one active SPK; its absence means crypto would fail
        // later in init() — flag it here for the early ResetRequired screen instead.
        val activeSpk = identityRepository.getActiveSignedPreKeyForDevice(device.deviceId)
        val activeSpkPriv = activeSpk?.let { keyStore.getKey(signedPreKeyPrivateRef(it.keyId)) }
        if (activeSpk == null || activeSpkPriv == null) {
            return BootDiagnosis.ResetRequired(
                reason = ResetReason.INCONSISTENT_STORAGE,
                details = "Active signed prekey missing (row=${activeSpk != null}, key=${activeSpkPriv != null})",
            )
        }
        return BootDiagnosis.Healthy
    }
}
