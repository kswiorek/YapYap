package org.yapyap.orchestrator.globalevent

import org.yapyap.crypto.identity.AccountId
import org.yapyap.persistence.db.IdentityStatus
import org.yapyap.persistence.db.VerificationState
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.envelopes.GlobalEventPayload
import kotlin.uuid.Uuid

/**
 * Pure fold core of the global control room (§2–§3, see docs/fold diagram.mmd).
 *
 * Inputs carry only what the gates decide on (ids, authorship, signatures,
 * bindings, verification keys); display data stays out of the shadow state —
 * the projector resolves commit material via the defining node ids the core
 * outputs. Decoupled from storage and crypto ([FoldCrypto] oracles) so the
 * dynamics fuzzer drives the exact production logic with synthetic nodes.
 */

/**
 * One fold input node. A null [event] is a proven forgery (undecodable bytes or
 * wrong payload type) — REJECTED, poisoning structural descendants.
 */
data class FoldNode(
    val id: Uuid,
    val authorDeviceId: PeerId,
    val senderAccountId: AccountId,
    val prevIds: List<Uuid>,
    val authorSignature: ByteArray?,
    val signingBytes: ByteArray,
    val event: GlobalEventPayload?,
    /** Self-certifying id-derivation assertion, precomputed by the adapter. */
    val derivationOk: Boolean,
)

/** Signature oracles the fold needs — the only crypto surface of the core. */
interface FoldCrypto {
    suspend fun verifyAuthor(key: ByteArray, msg: ByteArray, sig: ByteArray): Boolean
    suspend fun verifyBinding(key: ByteArray, binding: ByteArray, sig: ByteArray): Boolean
}

/** Chain-derived account projection (tombstones tracked separately). */
internal data class FoldAccount(
    val accountId: AccountId,
    val accountSigningPublicKey: ByteArray,
    val isAdmin: Boolean,
    val status: IdentityStatus,
    /** Defining `AddAccount` node — the material source for the projector's commit. */
    val nodeId: Uuid,
)

/** Chain-derived device projection (tombstones tracked separately). */
internal data class FoldDevice(
    val deviceId: PeerId,
    val accountId: AccountId,
    val status: IdentityStatus,
    /** Author device of the validating `AddDevice` (cascade-ban source set, §3). */
    val addedBy: PeerId,
    /** Branch-1 own-device adds only — the cascade-ban source set. */
    val branch1: Boolean,
    /** Defining `AddDevice` node (commit material source). */
    val nodeId: Uuid,
)

internal data class FoldOutput(
    val accounts: Map<AccountId, FoldAccount>,
    val devices: Map<PeerId, FoldDevice>,
)

/** Genesis root: the empty-`prevIds` `AddAccount` the DAG grew from. */
internal data class GenesisInfo(val nodeId: Uuid, val accountId: AccountId)

/** Genesis device's signing key — resolves genesis authorship before any device exists. */
internal data class GenesisKey(val deviceId: PeerId, val signingPublicKey: ByteArray)

internal data class ReplayResult(
    val output: FoldOutput,
    val verdicts: Map<Uuid, VerificationState>,
    val tombstonedAccounts: Set<AccountId>,
    val tombstonedDevices: Set<PeerId>,
    /** Explicitly banned device → defining ban node (first in canonical order). */
    val bans: Map<PeerId, Uuid>,
    /** Explicitly demoted account → defining demotion node (first in canonical order). */
    val demotions: Map<AccountId, Uuid>,
)

/**
 * The revocation footprint of one walk — the restart loop's fixpoint key. Defining
 * nodes included: seals need the revocation's ancestry.
 */
internal data class RevocationState(
    val bans: Map<PeerId, Uuid>,
    val demotions: Map<AccountId, Uuid>,
    val tombstonedAccounts: Set<AccountId>,
)

/**
 * Oscillation selection: most revocations in effect wins (contested principal
 * loses, §1); ties break by canonical node order. Deterministic, hence universal.
 */
private val revocationRank = compareBy<ReplayResult>(
    { it.bans.size + it.demotions.size + it.tombstonedAccounts.size },
    { (it.bans.values + it.demotions.values).map { node -> node.toString() }.sorted().joinToString() },
)

/**
 * Restart loop over carried revocations. A repeated revocation-state means the
 * walks cycle: keep the maximal-revocation fixpoint. Pure function of the inputs.
 */
internal suspend fun foldToFixpoint(
    order: List<Uuid>,
    nodes: Map<Uuid, FoldNode>,
    ancestors: Map<Uuid, Set<Uuid>>,
    foldSet: Set<Uuid>,
    genesis: GenesisInfo?,
    genesisKey: GenesisKey?,
    crypto: FoldCrypto,
    onOscillation: () -> Unit = {},
    onWalk: (walkIndex: Int, result: ReplayResult) -> Unit = { _, _ -> },
): ReplayResult {
    val history = LinkedHashMap<RevocationState, ReplayResult>()
    var carried = RevocationState(emptyMap(), emptyMap(), emptySet())
    var current = replayFold(
        order, nodes, ancestors, foldSet, genesis, genesisKey, crypto,
        carried.bans, carried.demotions, carried.tombstonedAccounts,
    )
    var walkIndex = 0
    onWalk(walkIndex, current)
    while (true) {
        val found = RevocationState(current.bans, current.demotions, current.tombstonedAccounts)
        if (found == carried) break
        if (found in history) {
            current = (history.values + current).maxWithOrNull(revocationRank) ?: current
            onOscillation()
            break
        }
        history[found] = current
        carried = found
        current = replayFold(
            order, nodes, ancestors, foldSet, genesis, genesisKey, crypto,
            carried.bans, carried.demotions, carried.tombstonedAccounts,
        )
        walkIndex++
        onWalk(walkIndex, current)
    }
    return current
}

/**
 * One replay walk over canonical order into shadow state (§2–§3).
 * Verdicts are authenticity-only: REJECTED = proven forgery (poisons structural
 * descendants into PENDING); auth-invalid / cut / sealed / duplicate = ignored
 * VERIFIED (no shadow effect); unresolvable author / outside fold input /
 * poisoned = PENDING.
 */
internal suspend fun replayFold(
    order: List<Uuid>,
    nodes: Map<Uuid, FoldNode>,
    ancestors: Map<Uuid, Set<Uuid>>,
    foldSet: Set<Uuid>,
    genesis: GenesisInfo?,
    genesisKey: GenesisKey?,
    crypto: FoldCrypto,
    carriedBans: Map<PeerId, Uuid>,
    carriedDemotions: Map<AccountId, Uuid>,
    carriedTombstonedAccounts: Set<AccountId>,
): ReplayResult {
    val accounts = LinkedHashMap<AccountId, FoldAccount>()
    val devices = LinkedHashMap<PeerId, FoldDevice>()
    // Validated (signer, account) AddAccount links — the branch-2 onboarding proof.
    val sponsorKeys = HashMap<Pair<PeerId, AccountId>, ByteArray>()
    val verdicts = HashMap<Uuid, VerificationState>()
    val walkBans = LinkedHashMap<PeerId, Uuid>()
    val walkDemotions = LinkedHashMap<AccountId, Uuid>()
    val ignoredAddDevices = HashSet<PeerId>()
    // Structural descendants of crypto-REJECTED nodes: excluded from the fold (PENDING).
    val poisoned = HashSet<Uuid>()
    // Canonical positions, for the seal order gate below.
    val positionOf = HashMap<Uuid, Int>(order.size)
    for ((index, id) in order.withIndex()) positionOf[id] = index

    // Merged revocation views: this walk's discoveries shadow the carried ones (identical
    // in the common case — the replay is deterministic over a fixed canonical order).
    fun demotionOf(account: AccountId): Uuid? = walkDemotions[account] ?: carriedDemotions[account]

    for (id in order) {
        val node = nodes.getValue(id)
        val event = node.event
        if (event == null) {
            // Proven forgery (undecodable bytes / wrong payload type): REJECTED,
            // poisoning structural descendants.
            verdicts[id] = VerificationState.REJECTED
            poisoned.add(id)
            continue
        }
        if (id !in foldSet) {
            // Incomplete ancestry or unreachable from the winning root (detached
            // sub-DAG, losing genesis branch): not folded, not rejected (§2).
            verdicts[id] = VerificationState.PENDING
            continue
        }
        // Self-certifying ids first: disproven authenticity outranks everything below.
        if (!node.derivationOk) {
            verdicts[id] = VerificationState.REJECTED
            poisoned.add(id)
            continue
        }
        val authorId = node.authorDeviceId
        val shadowAuthor = devices[authorId]
        // Author keys resolve through the author's defining AddDevice node — the
        // shadow keeps decision fields only, never key material.
        val authorKey = shadowAuthor?.let { author ->
            (nodes[author.nodeId]?.event as? GlobalEventPayload.AddDevice)?.signingPublicKey
        } ?: if (genesisKey != null && authorId == genesisKey.deviceId) {
            genesisKey.signingPublicKey
        } else {
            null
        }
        if (authorKey == null) {
            // Unresolvable author → PENDING, never REJECTED.
            verdicts[id] = VerificationState.PENDING
            continue
        }
        val sig = node.authorSignature
        if (sig == null || sig.isEmpty() ||
            !crypto.verifyAuthor(authorKey, node.signingBytes, sig)
        ) {
            verdicts[id] = VerificationState.REJECTED
            poisoned.add(id)
            continue
        }
        if (node.prevIds.any { it in poisoned }) {
            // Descendant of a proven forgery: excluded, but PENDING, not REJECTED.
            verdicts[id] = VerificationState.PENDING
            poisoned.add(id)
            continue
        }
        // Author liveness is decided by the cut gate below; reaching it means live.
        val authorIsShadow = shadowAuthor != null
        val authorAccountId = shadowAuthor?.accountId

        // A revocation never counts against a concurrent mutual revoker: this node
        // and the seal concurrently revoke each other's principals (device or
        // account, any revocation types — ban, demotion, removal). That paradox
        // belongs to the restart loop, not the seal. Single directed edges
        // (third-party grief), sequential nodes and non-revocations are unaffected.
        fun revokesPrincipal(
            ev: GlobalEventPayload,
            targetDev: PeerId,
            targetAcct: AccountId?,
        ): Boolean = when (ev) {
            is GlobalEventPayload.RemoveDevice -> ev.targetDeviceId == targetDev
            is GlobalEventPayload.RemoveAdmin -> ev.targetAccountId == targetAcct
            is GlobalEventPayload.RemoveAccount ->
                ev.targetAccountId == targetAcct || devices[targetDev]?.accountId == ev.targetAccountId

            else -> false
        }

        fun mutuallyRevoked(seal: Uuid): Boolean {
            if (id in ancestors.getValue(seal) || seal in ancestors.getValue(id)) return false
            val sealNode = nodes[seal] ?: return false
            val sealEvent = sealNode.event ?: return false
            val sealAuthorAcct = devices[sealNode.authorDeviceId]?.accountId ?: return false
            return revokesPrincipal(event, sealNode.authorDeviceId, sealAuthorAcct) &&
                    revokesPrincipal(sealEvent, authorId, authorAccountId)
        }

        fun effectiveAdmin(account: AccountId): Boolean {
            val acc = accounts[account] ?: return false
            if (acc.status != IdentityStatus.ACTIVE) return false
            if (acc.isAdmin) return true
            // Demoted only by a concurrent mutual revoker → still admin here;
            // settled demotions count.
            val walkSeal = walkDemotions[account]
            if (walkSeal != null && mutuallyRevoked(walkSeal)) return true
            val carriedSeal = carriedDemotions[account]
            return carriedSeal != null && mutuallyRevoked(carriedSeal)
        }

        // Demotion seal: voids admin-gated events at-or-before the demotion that sit
        // outside its ancestry. Post-demotion events are positional (re-grants reopen).
        fun sealed(account: AccountId): Boolean {
            val seal = demotionOf(account) ?: return false
            if (seal == id) return false
            if (mutuallyRevoked(seal)) return false
            if (positionOf.getValue(id) > positionOf.getValue(seal)) return false
            return id !in ancestors.getValue(seal)
        }
        // Author cut: sequential walk bans kill inline (first mover wins); carried
        // bans seal their ancestry-exterior (own defining node and vouched ancestry
        // exempt). Deliberately no mutual-revoker exemption here: liveness outranks
        // authority — acts by a banned author are void, full stop. Concurrent
        // counter-bans still resolve (both void → oscillation → maximal pick keeps
        // mutual destruction); the mutual exemption lives only on the demotion
        // side (effectiveAdmin/sealed), where the position-gated seal can't
        // oscillate on its own. Poisoned/unresolvable nodes never reach here.
        val walkBanOfAuthor = walkBans[authorId]
        val authorCutInline = walkBanOfAuthor != null && walkBanOfAuthor != id &&
                walkBanOfAuthor in ancestors.getValue(id)
        val carriedBanSeal = carriedBans[authorId]
        val authorCutCarried = carriedBanSeal != null && carriedBanSeal != id &&
                id !in ancestors.getValue(carriedBanSeal)
        if (authorCutInline || authorCutCarried) {
            // Banned device acting outside the ban's ancestry: void, no shadow effect.
            if (event is GlobalEventPayload.AddDevice && event.deviceId !in devices) {
                ignoredAddDevices.add(event.deviceId)
            }
            verdicts[id] = VerificationState.VERIFIED
            continue
        }
        when (event) {
            is GlobalEventPayload.AddAccount -> {
                if (id == genesis?.nodeId) {
                    // Genesis: admin by definition.
                    accounts[event.accountId] = FoldAccount(
                        accountId = event.accountId,
                        accountSigningPublicKey = event.accountSigningPublicKey,
                        isAdmin = true,
                        status = IdentityStatus.ACTIVE,
                        nodeId = id,
                    )
                    sponsorKeys[authorId to event.accountId] = event.accountSigningPublicKey
                    verdicts[id] = VerificationState.VERIFIED
                    continue
                }
                if (accounts.containsKey(event.accountId)) {
                    verdicts[id] = VerificationState.VERIFIED
                    continue
                }
                if (genesis != null && genesis.accountId == event.accountId) {
                    verdicts[id] = VerificationState.VERIFIED
                    continue
                }
                // Sponsored (member-level): fresh authors can't sponsor.
                if (!authorIsShadow) {
                    verdicts[id] = VerificationState.VERIFIED
                    continue
                }
                accounts[event.accountId] = FoldAccount(
                    accountId = event.accountId,
                    accountSigningPublicKey = event.accountSigningPublicKey,
                    isAdmin = false,
                    status = IdentityStatus.ACTIVE,
                    nodeId = id,
                )
                sponsorKeys[authorId to event.accountId] = event.accountSigningPublicKey
                verdicts[id] = VerificationState.VERIFIED
            }

            is GlobalEventPayload.AddDevice -> {
                // First valid add wins; tombstoned ids stay dead.
                if (devices.containsKey(event.deviceId)) {
                    verdicts[id] = VerificationState.VERIFIED
                    continue
                }
                fun ignoreAdd() {
                    ignoredAddDevices.add(event.deviceId)
                    verdicts[id] = VerificationState.VERIFIED
                }

                val target = accounts[event.accountId]
                if (event.keySignature == null) {
                    // Branch 1: own-device add.
                    if (!authorIsShadow || target == null || authorAccountId != event.accountId) {
                        ignoreAdd()
                        continue
                    }
                } else {
                    val binding = event.bindingBytes()
                    if (target == null) {
                        // Branch 2: same-signer AddAccount earlier in canonical order.
                        // Tombstoned accounts kill these at every position.
                        val sponsorKey = sponsorKeys[authorId to event.accountId]
                        if (event.accountId in carriedTombstonedAccounts || sponsorKey == null ||
                            !crypto.verifyBinding(sponsorKey, binding, event.keySignature)
                        ) {
                            ignoreAdd()
                            continue
                        }
                    } else {
                        // Branch 3: account-key-authorized add (recovery relay, genesis intro).
                        if (target.status != IdentityStatus.ACTIVE ||
                            event.accountId in carriedTombstonedAccounts ||
                            !crypto.verifyBinding(
                                target.accountSigningPublicKey,
                                binding,
                                event.keySignature,
                            )
                        ) {
                            ignoreAdd()
                            continue
                        }
                    }
                }
                devices[event.deviceId] = FoldDevice(
                    deviceId = event.deviceId,
                    accountId = event.accountId,
                    status = IdentityStatus.ACTIVE,
                    addedBy = authorId,
                    branch1 = event.keySignature == null,
                    nodeId = id,
                )
                verdicts[id] = VerificationState.VERIFIED
            }

            is GlobalEventPayload.GrantAdmin -> {
                val targetAcc = accounts[event.targetAccountId]
                if (!authorIsShadow || authorAccountId == null || !effectiveAdmin(authorAccountId) ||
                    sealed(authorAccountId) ||
                    targetAcc == null || targetAcc.status != IdentityStatus.ACTIVE
                ) {
                    verdicts[id] = VerificationState.VERIFIED
                    continue
                }
                accounts[event.targetAccountId] = targetAcc.copy(isAdmin = true)
                verdicts[id] = VerificationState.VERIFIED
            }

            is GlobalEventPayload.RemoveAdmin -> {
                val targetAcc = accounts[event.targetAccountId]
                // Genesis account is irrevocable: no zero-admin network.
                if (!authorIsShadow || authorAccountId == null ||
                    targetAcc == null || targetAcc.status != IdentityStatus.ACTIVE ||
                    event.targetAccountId == genesis?.accountId ||
                    !effectiveAdmin(authorAccountId) ||
                    sealed(authorAccountId)
                ) {
                    verdicts[id] = VerificationState.VERIFIED
                    continue
                }
                accounts[event.targetAccountId] = targetAcc.copy(isAdmin = false)
                // First in canonical order defines the seal (§3).
                if (event.targetAccountId !in walkDemotions) {
                    walkDemotions[event.targetAccountId] = id
                }
                verdicts[id] = VerificationState.VERIFIED
            }

            is GlobalEventPayload.RemoveDevice -> {
                val target = devices[event.targetDeviceId]
                val ownAccount = authorAccountId != null && target != null &&
                        authorAccountId == target.accountId
                val adminPath = authorIsShadow && authorAccountId != null &&
                        effectiveAdmin(authorAccountId) && !sealed(authorAccountId)
                if (!authorIsShadow || (!adminPath && !ownAccount) ||
                    target == null || target.status != IdentityStatus.ACTIVE
                ) {
                    verdicts[id] = VerificationState.VERIFIED
                    continue
                }
                devices[event.targetDeviceId] = target.copy(status = IdentityStatus.BANNED)
                // First in canonical order defines the cut (§3).
                if (event.targetDeviceId !in walkBans) {
                    walkBans[event.targetDeviceId] = id
                }
                verdicts[id] = VerificationState.VERIFIED
            }

            is GlobalEventPayload.RemoveAccount -> {
                val targetAcc = accounts[event.targetAccountId]
                val ownAccount = authorAccountId != null && authorAccountId == event.targetAccountId
                val adminPath = authorIsShadow && authorAccountId != null &&
                        effectiveAdmin(authorAccountId) && !sealed(authorAccountId)
                // Genesis account is irrevocable: no zero-admin network.
                if (!authorIsShadow || (!adminPath && !ownAccount) ||
                    targetAcc == null || targetAcc.status != IdentityStatus.ACTIVE ||
                    event.targetAccountId == genesis?.accountId
                ) {
                    verdicts[id] = VerificationState.VERIFIED
                    continue
                }
                accounts[event.targetAccountId] =
                    targetAcc.copy(isAdmin = false, status = IdentityStatus.BANNED)
                for ((deviceId, dev) in devices) {
                    if (dev.accountId == event.targetAccountId && dev.status == IdentityStatus.ACTIVE) {
                        devices[deviceId] = dev.copy(status = IdentityStatus.BANNED)
                    }
                }
                verdicts[id] = VerificationState.VERIFIED
            }
        }
    }

    val tombstonedAccounts = accounts.values
        .filter { it.status != IdentityStatus.ACTIVE }
        .map { it.accountId }
        .toSet()
    val shadowBannedDevices = devices.values
        .filter { it.status != IdentityStatus.ACTIVE }
        .map { it.deviceId }
        .toSet()
    // Invalidated adds project as tombstones (never "never-existed" retractions,
    // so pre-ban messages stay verifiable).
    val tombstonedDevices = shadowBannedDevices +
            ignoredAddDevices.filter { it !in devices }
    return ReplayResult(
        output = FoldOutput(
            accounts = accounts.filterValues { it.status == IdentityStatus.ACTIVE },
            devices = devices.filterValues { it.status == IdentityStatus.ACTIVE },
        ),
        verdicts = verdicts,
        tombstonedAccounts = tombstonedAccounts,
        tombstonedDevices = tombstonedDevices,
        bans = walkBans,
        demotions = walkDemotions,
    )
}
