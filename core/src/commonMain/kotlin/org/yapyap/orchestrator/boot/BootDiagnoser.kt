package org.yapyap.orchestrator.boot

import org.yapyap.crypto.identity.LocalIdentityStore
import org.yapyap.crypto.keys.KeySlot
import org.yapyap.crypto.keys.SigningPrivateKey
import org.yapyap.crypto.primitives.CryptoProvider
import org.yapyap.crypto.store.BootstrapSessionStore
import org.yapyap.crypto.store.KeyStore
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.orchestrator.ResetReason
import org.yapyap.orchestrator.identity.IdentityReader
import org.yapyap.persistence.db.DatabaseConnection
import org.yapyap.protocol.IdentityStatus
import kotlin.time.Clock

/**
 * Pure boot check: probes DB rows + keystore presence + self [IdentityStatus]
 * and returns a [BootDiagnosis]. Read-only except for one benign repair (see below).
 *
 * Decision table (peer count ignored — a genesis single-node network is Healthy):
 * - a store created on this boot holds nothing the keyring's identity keys could belong to
 *   (the data directory was deleted while the OS keyring kept them): they are leftovers,
 *   deleted before the checks below (the third benign repair), which then see a fresh node
 * - no rows + no keys → [BootDiagnosis.SetupRequired] (fresh and wiped are identical)
 * - rows + keys + key→ID binding + active SPK + self not BANNED → [BootDiagnosis.Healthy]
 *   (`null` status means "not yet known", never removed)
 * - the account key is held only by the account's first device: a device added to an
 *   existing account has neither half, and instead its device row must belong to the local
 *   account (a confirmed device under another account is rebound — the second benign repair)
 * - anything partial, a key→ID mismatch, or a missing active SPK →
 *   `ResetRequired(INCONSISTENT_STORAGE)`
 * - self device/account BANNED → `ResetRequired(SELF_BANNED)`
 * - local device row still provisional (no fold ever anchored us) with no live
 *   bootstrap session → `ResetRequired(ONBOARDING_EXPIRED)` (dead onboarding:
 *   the intro can never arrive and same-key retry is structurally dead, so only
 *   wipe + fresh setup recovers; checked last so consistency reasons win)
 */
internal class BootDiagnoser(
    private val identityReader: IdentityReader,
    private val localIdentityStore: LocalIdentityStore,
    private val keyStore: KeyStore,
    private val cryptoProvider: CryptoProvider,
    private val sessionStore: BootstrapSessionStore,
    private val clock: Clock,
) {
    /** [storeIsNew]: the database was created on this boot (see [DatabaseConnection.created]). */
    suspend fun diagnose(storeIsNew: Boolean = false): BootDiagnosis {
        if (storeIsNew) clearLeftoverIdentityKeys()
        val deviceRow = identityReader.getLocalDeviceIdentity()
        val accountRow = identityReader.getLocalAccountIdentity()
        val deviceSigningPriv = keyStore.getKey(KeySlot.DeviceSigningPrivate)
        val deviceEncryptionPriv = keyStore.getKey(KeySlot.DeviceEncryptionPrivate)
        val accountSigningPriv = keyStore.getKey(KeySlot.AccountSigningPrivate)
        val accountSigningPub = keyStore.getKey(KeySlot.AccountSigningPublic)

        if (deviceRow == null && accountRow == null && deviceSigningPriv == null &&
            deviceEncryptionPriv == null && accountSigningPriv == null && accountSigningPub == null
        ) {
            return BootDiagnosis.SetupRequired
        }

        val missing = buildList {
            if (deviceRow == null) add("device row")
            if (accountRow == null) add("account row")
            if (deviceSigningPriv == null) add("device signing key")
            if (deviceEncryptionPriv == null) add("device encryption key")
            // Both halves absent is a key-less device (added to an existing account); one
            // half alone is a partial keystore.
            if (accountSigningPriv == null && accountSigningPub != null) add("account signing key")
        }
        if (missing.isNotEmpty()) {
            return BootDiagnosis.ResetRequired(
                reason = ResetReason.INCONSISTENT_STORAGE,
                details = "Partial local state, missing: ${missing.joinToString()}",
            )
        }

        // Key→ID binding: the rows must belong to the keys we hold.
        val device = checkNotNull(deviceRow)
        var account = checkNotNull(accountRow)
        val derivedDeviceId = cryptoProvider.peerIdFromPublicKey(
            cryptoProvider.privateSigningKeyToPublicKey(SigningPrivateKey(checkNotNull(deviceSigningPriv))),
        )
        val derivedAccountId = accountSigningPriv?.let {
            cryptoProvider.accountIdFromPublicKey(cryptoProvider.privateSigningKeyToPublicKey(SigningPrivateKey(it)))
        }
        if (derivedDeviceId != device.deviceId || (derivedAccountId != null && derivedAccountId != account.accountId)) {
            return BootDiagnosis.ResetRequired(
                reason = ResetReason.INCONSISTENT_STORAGE,
                details = "Key/row ID mismatch (keys do not derive the stored device/account IDs)",
            )
        }
        if (derivedAccountId == null) {
            // Key-less device: no key ties the account row to us, the device row's account does.
            // Stores from before the projector rebound the local account on confirmation still
            // point at the pre-confirmation stand-in; the chain's answer is repaired in.
            if (identityReader.getAccountIdForDevice(device.deviceId) != account.accountId &&
                localIdentityStore.rebindLocalAccountToLocalDevice() != null
            ) {
                account = identityReader.getLocalAccountIdentity() ?: account
            }
            val deviceAccount = identityReader.getAccountIdForDevice(device.deviceId)
            if (deviceAccount != account.accountId) {
                return BootDiagnosis.ResetRequired(
                    reason = ResetReason.INCONSISTENT_STORAGE,
                    details = "Local device belongs to account $deviceAccount, local account is ${account.accountId}",
                )
            }
        }

        val deviceStatus = identityReader.getDeviceStatus(device.deviceId)
        val accountStatus = identityReader.getAccountStatus(account.accountId)
        if (deviceStatus == IdentityStatus.BANNED || accountStatus == IdentityStatus.BANNED) {
            return BootDiagnosis.ResetRequired(
                reason = ResetReason.SELF_BANNED,
                details = "Local device/account is BANNED (device=$deviceStatus, account=$accountStatus)",
            )
        }

        // Rotation keeps exactly one active SPK; its absence means crypto would fail
        // later in init() — flag it here for the early ResetRequired screen instead.
        val activeSpk = identityReader.getActiveSignedPreKeyForDevice(device.deviceId)
        val activeSpkPriv = activeSpk?.let { keyStore.getKey(KeySlot.SignedPreKeyPrivate(it.id)) }
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
        if (identityReader.isDeviceProvisional(device.deviceId)) {
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

    private suspend fun clearLeftoverIdentityKeys() {
        val leftovers = KeySlot.localIdentity.filter { keyStore.getKey(it) != null }
        if (leftovers.isEmpty()) return
        AppLog.warn(
            component = LogComponent.ORCHESTRATOR,
            event = LogEvent.LEFTOVER_KEYS_CLEARED,
            message = "New database but identity keys left in the keyring; deleting them",
            fields = mapOf("slots" to leftovers.joinToString { it.keyringName }),
        )
        for (slot in leftovers) keyStore.deleteKey(slot)
    }
}
