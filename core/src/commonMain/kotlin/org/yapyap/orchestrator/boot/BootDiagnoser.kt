package org.yapyap.orchestrator.boot

import org.yapyap.crypto.primitives.CryptoProvider
import org.yapyap.persistence.db.IdentityStatus
import org.yapyap.persistence.key.*
import kotlin.time.Clock

/**
 * Pure boot check: probes DB rows + keystore presence + self [IdentityStatus]
 * and returns a [BootDiagnosis]. Read-only except for one benign repair (see below).
 *
 * Decision table (peer count ignored — a genesis single-node network is Healthy):
 * - no rows + no keys → [BootDiagnosis.SetupRequired] (fresh and wiped are identical)
 * - rows + keys + key→ID binding + active SPK + self not BANNED → [BootDiagnosis.Healthy]
 *   (`null` status means "not yet known", never removed)
 * - anything partial, a key→ID mismatch, or a missing active SPK →
 *   `ResetRequired(INCONSISTENT_STORAGE)`
 * - self device/account BANNED → `ResetRequired(SELF_BANNED)`
 * - local device row still provisional (no fold ever anchored us) with no live
 *   bootstrap session → `ResetRequired(ONBOARDING_EXPIRED)` (dead onboarding:
 *   the intro can never arrive and same-key retry is structurally dead, so only
 *   wipe + fresh setup recovers; checked last so consistency reasons win)
 */
class BootDiagnoser(
    private val identityRepository: IdentityKeyRepository,
    private val keyStore: KeyStore,
    private val cryptoProvider: CryptoProvider,
    private val sessionStore: BootstrapSessionStore,
    private val clock: Clock,
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

        // Dead onboarding: the local device was provisioned but no fold ever cleared its
        // provisional bit, and no bootstrap session can still complete it. A live session
        // (deadline in the future) means onboarding was merely interrupted by the restart
        // and resumes — every other case is terminal: the burned/expired secret can never
        // open the intro, and the sponsor already published our AddDevice, so same-key
        // retry would be an invalid duplicate. Checked last so consistency reasons win.
        // (A device-anchored row implies an anchored account row — the fold commits
        // AddAccount and AddDevice together — so one predicate covers both.)
        // Note: session() repairs corrupt keyring entries (deletes them); benign and
        // idempotent — the only "write" in this otherwise read-only check.
        if (identityRepository.isDeviceProvisional(device.deviceId)) {
            val session = sessionStore.session()
            if (session == null || clock.now() >= session.deadline) {
                return BootDiagnosis.ResetRequired(
                    reason = ResetReason.ONBOARDING_EXPIRED,
                    details = "Onboarding never completed; no live session remains " +
                        "(session=${if (session == null) "burned" else "expired"})",
                )
            }
        }
        return BootDiagnosis.Healthy
    }
}
