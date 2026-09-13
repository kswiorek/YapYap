package org.yapyap.orchestrator.globalevent

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.yapyap.crypto.identity.AccountId
import org.yapyap.crypto.identity.AccountIdentityRecord
import org.yapyap.crypto.identity.DeviceIdentityRecord
import org.yapyap.crypto.identity.IdentityResolver
import org.yapyap.crypto.primitives.CryptoProvider
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.orchestrator.dag.DagEngine
import org.yapyap.orchestrator.dag.MessageDraft
import org.yapyap.orchestrator.dag.RoomId
import org.yapyap.orchestrator.pipeline.InboundMessagePipeline
import org.yapyap.persistence.db.*
import org.yapyap.persistence.key.IdentityKeyRepository
import org.yapyap.persistence.messaging.MessageRepository
import org.yapyap.persistence.messaging.MessageRow
import org.yapyap.persistence.messaging.RoomRepository
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.TorEndpoint
import org.yapyap.protocol.envelopes.GlobalEventPayload
import org.yapyap.protocol.envelopes.Invite
import org.yapyap.protocol.envelopes.MessagePayload
import org.yapyap.protocol.envelopes.RecoveryRequest
import org.yapyap.routing.router.Router
import kotlin.uuid.Uuid

/**
 * Committed fold diff of the global control room. Consumers: the onboarding provider
 * (own-device anchoring → COMPLETE), the pending-reverify hook, the sprint-4d firewall, UI.
 */
sealed interface IdentityStateChange {
    data class AccountAdded(val accountId: AccountId) : IdentityStateChange
    data class AccountRemoved(val accountId: AccountId) : IdentityStateChange
    data class AdminGranted(val accountId: AccountId) : IdentityStateChange
    data class AdminRevoked(val accountId: AccountId) : IdentityStateChange
    data class DeviceAdded(val accountId: AccountId, val deviceId: PeerId) : IdentityStateChange
    data class DeviceRemoved(val deviceId: PeerId) : IdentityStateChange
}

/**
 * Sole writer of chain-derived identity columns (`accounts` / `devices`).
 *
 * Two roles in one class:
 *  - **control-plane writer**: the `publish*` functions are the only way identity events enter
 *    the DAG. Each call appends to the global room (signed by the local device via
 *    [DagEngine.append]), folds + commits synchronously, then broadcasts to the room members.
 *    The sponsor service and the recovery responder are thin callers over these.
 *    The [DagException.FrontierUnavailable] refusal propagates: when the global room holds
 *    messages but its chainable frontier is empty (every tip parked on an open gap, or no
 *    tip VERIFIED yet), nothing is appended and nothing is broadcast — callers map it to a
 *    domain failure (the future AccountService, the sponsor flow's `SponsorRefusal`).
 *  - **projector**: re-folds the global room on every ingest trigger and on boot, verifies each
 *    event against shadow-state keys, flips PENDING → VERIFIED/REJECTED, and commits the merge.
 *
 * The projector never touches private keys — consent signatures ([Invite.accountKeySignature],
 * [RecoveryRequest.accountSignature], genesis) are computed by the callers that hold the
 * account key; the fold only verifies them against fold-state pub keys via [CryptoProvider]
 * primitives (never the live-table-backed `SignatureProvider`).
 */
interface GlobalEventProjector {
    val stateChanges: Flow<IdentityStateChange>

    fun start(scope: CoroutineScope)
    suspend fun stop()

    /**
     * Genesis (create-new-network): AddAccount (DAG root, `prevId == null`, admin by definition)
     * + AddDevice self-introduction (branch 3, authorized by [accountKeySignature]).
     * The global room must be empty — the DAG structure itself enforces the "only once".
     */
    suspend fun publishGenesisAccount(
        account: AccountIdentityRecord,
        device: DeviceIdentityRecord,
        deviceType: DeviceType,
        torEndpoint: TorEndpoint,
        accountKeySignature: ByteArray,
    )

    /**
     * New-account sponsorship (§8.1): AddAccount + AddDevice back-to-back with the same signer
     * (branch 2, authorized by the invite's `accountKeySignature`), plus GrantAdmin when
     * [grantAdmin] — fail-fast on the local `is_admin` BEFORE appending anything.
     */
    suspend fun publishSponsoredNewAccount(invite: Invite, grantAdmin: Boolean)

    /** Add-device to the sponsor's own account (branch 1 — the invite carries no account). */
    suspend fun publishOwnAccountDevice(invite: Invite)

    /** Recovery relay (§8.2 phase 2): AddDevice carrying the request's `accountSignature` as the
     *  `key_signature` (branch 3). */
    suspend fun publishRelayedDevice(request: RecoveryRequest)

    suspend fun publishGrantAdmin(targetAccountId: AccountId)
    suspend fun publishRemoveAdmin(targetAccountId: AccountId)
    suspend fun publishRemoveAccount(targetAccountId: AccountId)
    suspend fun publishRemoveDevice(targetDeviceId: PeerId)

    /**
     * Still-active devices whose branch-1 `AddDevice` was authored by [authorDeviceId], per the
     * last committed fold — the cascade-ban source set for the ban UI (ban-first-then-query:
     * the set is frozen once the ban lands, docs/global events.md §3). Empty until the first
     * fold completes.
     */
    suspend fun activeDevicesAddedBy(authorDeviceId: PeerId): List<PeerId>
}

internal class DefaultGlobalEventProjector(
    private val dagEngine: DagEngine,
    private val pipeline: InboundMessagePipeline,
    private val messageRepository: MessageRepository,
    private val identityKeyRepository: IdentityKeyRepository,
    private val identityResolver: IdentityResolver,
    private val roomRepository: RoomRepository,
    private val router: Router,
    private val cryptoProvider: CryptoProvider,
) : GlobalEventProjector {

    private val _stateChanges = MutableSharedFlow<IdentityStateChange>(extraBufferCapacity = 64)
    override val stateChanges: Flow<IdentityStateChange> = _stateChanges.asSharedFlow()

    /** Serializes all fold triggers (publish path, ingest collector, boot). */
    private val foldMutex = Mutex()

    /** Last committed projection (null until the first fold — the first fold sets the baseline silently). */
    private var lastCommit: FoldOutput? = null

    /** Devices of the last committed fold, backing [activeDevicesAddedBy]. */
    private var lastFoldDevices: Map<PeerId, FoldDevice> = emptyMap()

    private var collectJob: Job? = null
    private var scope: CoroutineScope? = null

    override fun start(scope: CoroutineScope) {
        this.scope = scope
        // Boot fold: commits whatever global history is already stored (idempotent full re-fold).
        scope.launch {
            // Step 0 (docs/global events.md §9): the GLOBAL room must exist as a real room
            // (messages FK target, sync candidates, ping frontiers, broadcast recipients).
            roomRepository.ensureRoomExists(RoomId.GLOBAL, RoomType.GLOBAL_CONTROL, "global-control")
            foldAndCommit("boot")
        }
        if (collectJob?.isActive == true) return
        // Local appends bypass the pipeline, so every trigger here is a remote insert.
        // At 10–20 users the global log is tiny: a full re-fold on every trigger is
        // correct and cheap (optimize with watermarks only if ever needed).
        collectJob = scope.launch {
            pipeline.ingestResults.collect { result ->
                if (result.payload.roomId == RoomId.GLOBAL) foldAndCommit("ingest")
            }
        }
    }

    override suspend fun stop() {
        collectJob?.cancel()
        collectJob = null
        scope = null
    }

    override suspend fun publishGenesisAccount(
        account: AccountIdentityRecord,
        device: DeviceIdentityRecord,
        deviceType: DeviceType,
        torEndpoint: TorEndpoint,
        accountKeySignature: ByteArray,
    ) {
        require(!messageRepository.hasMessages(RoomId.GLOBAL)) {
            "genesis requires an empty global room"
        }
        val accountKey = requireNotNull(account.key) { "genesis requires the account public key" }
        require(accountKeySignature.isNotEmpty()) { "genesis requires the account signature over the device binding" }
        publish(
            listOf(
                GlobalEventPayload.AddAccount(
                    accountId = account.accountId,
                    accountSigningPublicKey = accountKey.publicKey,
                    displayName = account.displayName,
                ),
                GlobalEventPayload.AddDevice(
                    accountId = account.accountId,
                    deviceId = device.deviceId,
                    signingPublicKey = device.signing.publicKey,
                    encryptionPublicKey = device.encryption.publicKey,
                    torEndpoint = torEndpoint,
                    deviceType = deviceType,
                    keySignature = accountKeySignature,
                ),
            ),
        )
    }

    override suspend fun publishSponsoredNewAccount(invite: Invite, grantAdmin: Boolean) {
        val account = requireNotNull(invite.account) {
            "sponsored new-account publish requires invite.account"
        }
        val accountKey = requireNotNull(account.key) {
            "sponsored new-account publish requires the account public key"
        }
        val keySignature = requireNotNull(invite.accountKeySignature) {
            "sponsored new-account publish requires invite.accountKeySignature"
        }
        // Fail fast: GrantAdmin is valid only if the sponsor is admin at this fold position.
        check(!grantAdmin || identityResolver.isLocalAccountAdmin()) {
            "local account is not an admin"
        }
        val events = mutableListOf(
            GlobalEventPayload.AddAccount(
                accountId = account.accountId,
                accountSigningPublicKey = accountKey.publicKey,
                displayName = account.displayName,
            ),
            GlobalEventPayload.AddDevice(
                accountId = account.accountId,
                deviceId = invite.device.deviceId,
                signingPublicKey = invite.device.signing.publicKey,
                encryptionPublicKey = invite.device.encryption.publicKey,
                torEndpoint = invite.torEndpoint,
                deviceType = invite.deviceType,
                keySignature = keySignature,
            ),
        )
        // GrantAdmin is a separate event, never a field of AddAccount (admin status is
        // derived from the log).
        if (grantAdmin) events += GlobalEventPayload.GrantAdmin(account.accountId)
        publish(events)
    }

    override suspend fun publishOwnAccountDevice(invite: Invite) {
        require(invite.account == null) { "own-account device publish takes an account-less invite" }
        val localAccount = identityResolver.getLocalAccountIdentityRecord()
        publish(
            listOf(
                GlobalEventPayload.AddDevice(
                    accountId = localAccount.accountId,
                    deviceId = invite.device.deviceId,
                    signingPublicKey = invite.device.signing.publicKey,
                    encryptionPublicKey = invite.device.encryption.publicKey,
                    torEndpoint = invite.torEndpoint,
                    deviceType = invite.deviceType,
                    // Branch 1: the sponsor's own authorship is the authorization — a sibling
                    // device does not hold the account private key, so no keySignature exists.
                    keySignature = null,
                ),
            ),
        )
    }

    override suspend fun publishRelayedDevice(request: RecoveryRequest) {
        requireNotNull(request.account.key) { "relayed device publish requires the account public key" }
        require(request.accountSignature.isNotEmpty()) { "relayed device publish requires request.accountSignature" }
        publish(
            listOf(
                GlobalEventPayload.AddDevice(
                    accountId = request.account.accountId,
                    deviceId = request.device.deviceId,
                    signingPublicKey = request.device.signing.publicKey,
                    encryptionPublicKey = request.device.encryption.publicKey,
                    torEndpoint = request.torEndpoint,
                    deviceType = request.deviceType,
                    keySignature = request.accountSignature,
                ),
            ),
        )
    }

    override suspend fun publishGrantAdmin(targetAccountId: AccountId) {
        publish(listOf(GlobalEventPayload.GrantAdmin(targetAccountId)))
    }

    override suspend fun publishRemoveAdmin(targetAccountId: AccountId) {
        publish(listOf(GlobalEventPayload.RemoveAdmin(targetAccountId)))
    }

    override suspend fun publishRemoveAccount(targetAccountId: AccountId) {
        publish(listOf(GlobalEventPayload.RemoveAccount(targetAccountId)))
    }

    override suspend fun publishRemoveDevice(targetDeviceId: PeerId) {
        publish(listOf(GlobalEventPayload.RemoveDevice(targetDeviceId)))
    }

    override suspend fun activeDevicesAddedBy(authorDeviceId: PeerId): List<PeerId> =
        lastFoldDevices.values
            .filter { it.branch1 && it.addedBy == authorDeviceId && it.status == IdentityStatus.ACTIVE }
            .map { it.deviceId }
            .sortedBy { it.id }

    /**
     * Shared writer path: append (DagEngine signs with the local device key and chains off the
     * room tail) → fold + commit synchronously (the intro's dagHead must already include the new
     * nodes) → broadcast via the standard message path (sync stays the fallback).
     */
    private suspend fun publish(events: List<GlobalEventPayload>) {
        val nodes = events.map { dagEngine.append(RoomId.GLOBAL, MessageDraft.GlobalEvent(it)) }
        foldAndCommit("publish")
        broadcast(nodes)
        AppLog.info(
            component = LogComponent.ORCHESTRATOR,
            event = LogEvent.MESSAGE_APPENDED,
            message = "Global events published to the control DAG",
            fields = mapOf(
                "eventKinds" to events.map { it.kind },
                "nodeCount" to nodes.size,
            ),
        )
    }

    private suspend fun broadcast(nodes: List<MessagePayload>) {
        val members = roomRepository.membersOfRoom(RoomId.GLOBAL)
        coroutineScope {
            nodes.flatMap { node ->
                members.map { member -> async { router.sendMessage(member, node) } }
            }.awaitAll()
        }
        AppLog.debug(
            component = LogComponent.ORCHESTRATOR,
            event = LogEvent.OUTBOX_MESSAGE_QUEUED,
            message = "Global events broadcast to room members",
            fields = mapOf("nodeCount" to nodes.size, "memberCount" to members.size),
        )
    }

    /**
     * Canonical fold + commit (docs/global events.md §2–§7). Serializes all triggers (publish
     * path, ingest collector, boot). The fold is a pure function of the stored set: verdicts are
     * outputs, never inputs — every node holding the same set computes the identical fixpoint.
     *
     * Shape: fold input (chainable graph) → genesis election → reachability → restart loop
     * of single replays with carried revocations → maximal-revocation selection → verdict
     * write → merge commit. See docs/fold diagram.mmd for the design-level flow.
     *
     * Verdicts are authenticity-only (§6 storage criterion): REJECTED = proven forgery
     * (undecodable / wrong payload type / bad signature / id-derivation mismatch), permanent,
     * poisoning structural descendants into PENDING. Everything else authentic is VERIFIED —
     * including authorization-invalid, cut, duplicate and demoted-author events, which are
     * *ignored* (no shadow effect). Unresolvable author / unreachable / incomplete ancestry /
     * poisoned → PENDING (not folded, not rejected).
     */
    private suspend fun foldAndCommit(trigger: String) {
        foldMutex.withLock {
            val rows = messageRepository.findAllInRoom(RoomId.GLOBAL)
            if (rows.isEmpty()) {
                if (lastCommit == null) lastCommit = FoldOutput(emptyMap(), emptyMap())
                return
            }
            val byId = rows.associateBy { it.payload.messageId }
            val children = childAdjacency(byId)
            val order = canonicalOrder(byId, children)
            val ancestors = ancestorClosures(byId)
            val genesis = resolveGenesis(byId, children)
            val foldSet = foldInputSet(byId, children, genesis)
            val genesisKey = genesisSelfIntroKey(order, byId, genesis)

            // Restart loop over carried revocations: a backdated event sorting before its
            // revocation is caught on the restart, where the revocation is known from the
            // start. The loop is a deterministic pure function of the stored set, so every
            // node traces the identical walk sequence to the identical fixpoint (§2).
            //
            // Non-monotone corner (§3/§6.2): any dependency cycle through fold state (ban
            // validity ← banner adminship ← grant validity ← granter liveness ← ban of
            // granter…) can oscillate — walk N validates a revocation whose cut kills the
            // chain another revocation stands on, walk N+1 voids both, walk N+2 re-discovers
            // them. A repeated revocation-state means the loop is cycling: stop and keep the
            // visited state with the most revocations in effect (bans + demotions + account
            // tombstones; ties broken by canonical node order — arbitrary but universal).
            // That is the contested-principal-loses doctrine (§1) as the single implementable
            // rule, covering direct opposing pairs and deeper cycles alike with no syntactic
            // pair scan. Revocations act forwards-only: earlier valid grants stay valid
            // ("don't cut off the branch"); revocations void positionally-later events plus
            // events outside the revocation's ancestry (backdated/concurrent forgeries).
            // An explicit re-revocation by a living admin converges operationally past any residue.
            val history = LinkedHashMap<RevocationState, ReplayResult>()
            var carried = RevocationState(emptyMap(), emptyMap(), emptySet())
            var current = replay(
                order, byId, ancestors, foldSet, genesis, genesisKey,
                carried.bans, carried.demotions, carried.tombstonedAccounts,
            )
            while (true) {
                val found = RevocationState(current.bans, current.demotions, current.tombstonedAccounts)
                if (found == carried) break
                if (found in history) {
                    current = (history.values + current).maxWithOrNull(revocationRank) ?: current
                    AppLog.warn(
                        component = LogComponent.ORCHESTRATOR,
                        event = LogEvent.GLOBAL_FOLD_BAN_DIVERGED,
                        message = "Fold restart loop oscillated; kept the maximal-revocation fixpoint",
                        fields = mapOf("trigger" to trigger),
                    )
                    break
                }
                history[found] = current
                carried = found
                current = replay(
                    order, byId, ancestors, foldSet, genesis, genesisKey,
                    carried.bans, carried.demotions, carried.tombstonedAccounts,
                )
            }
            for ((messageId, verdict) in current.verdicts) {
                if (byId.getValue(messageId).verificationState != verdict) {
                    messageRepository.updateVerificationState(messageId, verdict)
                }
            }
            commit(current, trigger)
            lastFoldDevices = current.output.devices
        }
    }

    /** Chain-derived account projection of one fold (tombstoned accounts tracked separately). */
    private data class FoldAccount(
        val accountId: AccountId,
        val accountSigningPublicKey: ByteArray,
        val displayName: String,
        val isAdmin: Boolean,
        val status: IdentityStatus,
    )

    /** Chain-derived device projection of one fold (tombstoned devices tracked separately). */
    private data class FoldDevice(
        val deviceId: PeerId,
        val accountId: AccountId,
        val deviceType: DeviceType,
        val torEndpoint: TorEndpoint,
        val signingPublicKey: ByteArray,
        val encryptionPublicKey: ByteArray,
        val keySignature: ByteArray?,
        val status: IdentityStatus,
        /** Author device of the validating `AddDevice` (cascade-ban source set, §3). */
        val addedBy: PeerId,
        /** True for branch-1 own-device adds (no `key_signature`) — the only adds the
         *  cascade-ban source set ([activeDevicesAddedBy]) covers. */
        val branch1: Boolean,
    )

    private data class FoldOutput(
        val accounts: Map<AccountId, FoldAccount>,
        val devices: Map<PeerId, FoldDevice>,
    )

    /** Genesis root: the empty-`prevIds` `AddAccount` the DAG grew from (forged roots lose, §3). */
    private data class GenesisInfo(val nodeId: Uuid, val accountId: AccountId)

    /** The single self-introduction key: the genesis device's signing key, resolving the genesis
     *  device's authorship (the root `AddAccount` and its own `AddDevice`) before any device of
     *  the network exists in shadow state. */
    private data class GenesisKey(val deviceId: PeerId, val signingPublicKey: ByteArray)

    private data class ReplayResult(
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
     * The revocation footprint of one replay walk — the restart loop's fixpoint key and its
     * carried input (defining nodes included: the seal needs the revocation's ancestry).
     */
    private data class RevocationState(
        val bans: Map<PeerId, Uuid>,
        val demotions: Map<AccountId, Uuid>,
        val tombstonedAccounts: Set<AccountId>,
    )

    /**
     * Oscillation selection rule (§1 doctrine, implementable form): prefer the fixpoint with the
     * most revocations in effect; ties fall back to canonical node order. Deterministic over
     * the stored set, hence universal across nodes. This is the single rule resolving all
     * validity paradoxes (direct opposing pairs and deeper grant/ban cycles alike).
     */
    private val revocationRank = compareBy<ReplayResult>(
        { it.bans.size + it.demotions.size + it.tombstonedAccounts.size },
        { (it.bans.values + it.demotions.values).map { node -> node.toString() }.sorted().joinToString() },
    )

    /** Stored-graph child adjacency (parent → children), for the topo sort and genesis descent. */
    private fun childAdjacency(byId: Map<Uuid, MessageRow>): Map<Uuid, List<Uuid>> {
        val children = HashMap<Uuid, MutableList<Uuid>>()
        for ((id, row) in byId) {
            for (parent in row.payload.prevIds) {
                if (byId.containsKey(parent)) {
                    children.getOrPut(parent) { mutableListOf() }.add(id)
                }
            }
        }
        return children
    }

    /**
     * Canonical order (§2): topological over the stored `prevIds` edges (Kahn's algorithm,
     * `(createdAt, messageId)` tiebreak). Nodes that never become ready (a forged causality
     * cycle) replay last, deterministically.
     */
    private fun canonicalOrder(byId: Map<Uuid, MessageRow>, children: Map<Uuid, List<Uuid>>): List<Uuid> {
        val orderOf = Comparator<Uuid> { a, b ->
            val ra = byId.getValue(a).payload
            val rb = byId.getValue(b).payload
            val c = ra.createdAt.compareTo(rb.createdAt)
            if (c != 0) c else a.compareTo(b)
        }
        val indegree = HashMap<Uuid, Int>()
        for ((id, row) in byId) {
            indegree[id] = row.payload.prevIds.count { byId.containsKey(it) }
        }
        // Plain-list priority scan (common-safe; the global log is tiny — swap in a real
        // priority structure only if the room ever outgrows the 10–20-user profile).
        val ready = ArrayList<Uuid>()
        for ((id, degree) in indegree) {
            if (degree == 0) ready.add(id)
        }
        val order = ArrayList<Uuid>(byId.size)
        while (ready.isNotEmpty()) {
            val id = ready.minWith(orderOf)
            ready.remove(id)
            order.add(id)
            for (child in children[id].orEmpty()) {
                val remaining = indegree.getValue(child) - 1
                indegree[child] = remaining
                if (remaining == 0) ready.add(child)
            }
        }
        if (order.size < byId.size) {
            val replayed = order.toSet()
            order.addAll(byId.keys.filter { it !in replayed }.sortedWith(orderOf))
        }
        return order
    }

    /**
     * Genesis resolution (§3): among empty-`prevIds` `AddAccount` nodes, the true root is the
     * ancestor of (almost) the whole DAG — a forged root has (almost) no descendants, and the
     * pre-forgery margin is permanent (honest appends after the forgery reference both roots
     * equally). Tiebreak earliest `(createdAt, messageId)`; unreachable by an attacker (no
     * author key exists on a ~single-event network).
     */
    private fun resolveGenesis(byId: Map<Uuid, MessageRow>, children: Map<Uuid, List<Uuid>>): GenesisInfo? {
        val candidates = byId.values.mapNotNull { row ->
            val event = runCatching { (row.payload as? MessagePayload.GlobalEvent)?.decodeEvent() }.getOrNull()
            if (row.payload.prevIds.isEmpty() && event is GlobalEventPayload.AddAccount) {
                row.payload.messageId to event.accountId
            } else null
        }
        if (candidates.isEmpty()) return null
        fun descendantsOf(root: Uuid): Int {
            val seen = HashSet<Uuid>()
            val stack = ArrayDeque<Uuid>()
            seen.add(root)
            stack.add(root)
            while (stack.isNotEmpty()) {
                for (child in children[stack.removeLast()].orEmpty()) {
                    if (seen.add(child)) stack.add(child)
                }
            }
            return seen.size - 1
        }
        return candidates
            .map { (nodeId, accountId) -> Triple(nodeId, accountId, descendantsOf(nodeId)) }
            .sortedWith(
                compareByDescending<Triple<Uuid, AccountId, Int>> { it.third }
                    .thenBy { byId.getValue(it.first).payload.createdAt }
                    .thenBy { it.first },
            )
            .firstOrNull()
            ?.let { GenesisInfo(it.first, it.second) }
    }

    /**
     * Memoized transitive `prevIds` closures over the stored graph (pure function of the stored
     * set — the banner's vouching set for the cut and the demotion seal read from here).
     */
    private fun ancestorClosures(byId: Map<Uuid, MessageRow>): Map<Uuid, Set<Uuid>> {
        val memo = HashMap<Uuid, Set<Uuid>>()
        fun ancestorsOf(node: Uuid): Set<Uuid> = memo.getOrPut(node) {
            val seen = HashSet<Uuid>()
            val stack = ArrayDeque<Uuid>()
            for (parent in byId.getValue(node).payload.prevIds) {
                if (seen.add(parent)) stack.add(parent)
            }
            while (stack.isNotEmpty()) {
                for (parent in byId[stack.removeLast()]?.payload?.prevIds.orEmpty()) {
                    if (seen.add(parent)) stack.add(parent)
                }
            }
            seen
        }
        for (id in byId.keys) ancestorsOf(id)
        return memo
    }

    /**
     * Fold input: the chainable graph — ancestry-complete nodes reachable from the winning
     * genesis root over present edges (§2). Everything else (incomplete ancestry, detached
     * sub-DAGs, the losing genesis root and its private branch) stays PENDING: not folded,
     * not rejected, just not considered. The same graph the append path builds on
     * (`selectRoomFrontier` refuses to chain off unverifiable ancestry).
     */
    private fun foldInputSet(
        byId: Map<Uuid, MessageRow>,
        children: Map<Uuid, List<Uuid>>,
        genesis: GenesisInfo?,
    ): Set<Uuid> {
        if (genesis == null) return emptySet()
        val reachable = HashSet<Uuid>()
        val stack = ArrayDeque<Uuid>()
        reachable.add(genesis.nodeId)
        stack.add(genesis.nodeId)
        while (stack.isNotEmpty()) {
            for (child in children[stack.removeLast()].orEmpty()) {
                if (reachable.add(child)) stack.add(child)
            }
        }
        return reachable.filterTo(HashSet()) { byId.getValue(it).ancestryComplete }
    }

    /**
     * The single genesis-scoped self-introduction key: the first (canonical order)
     * `AddDevice` whose author IS the added device and whose target is the genesis account.
     * In every implemented flow the responder (not the device) authors relayed adds
     * ([publishRelayedDevice]), so the genesis self-introduction is the only legitimate
     * author==device node. Anything else with author==device resolves no author: if the
     * device was never added the event is PENDING (unverifiable, not provably forged); if it
     * was, the author resolves through shadow state and the node is a duplicate (ignored).
     *
     * The key is self-authenticating (the node signature verifies against the carried key
     * with no prior trust) and resolves authorship only — never authorization (a fresh
     * author cannot sponsor an `AddAccount`; unilateral onboarding stays closed, §3).
     */
    private fun genesisSelfIntroKey(
        order: List<Uuid>,
        byId: Map<Uuid, MessageRow>,
        genesis: GenesisInfo?,
    ): GenesisKey? {
        if (genesis == null) return null
        for (id in order) {
            val payload = byId.getValue(id).payload as? MessagePayload.GlobalEvent ?: continue
            val event = runCatching { payload.decodeEvent() }.getOrNull() as? GlobalEventPayload.AddDevice
                ?: continue
            if (payload.authorDeviceId == event.deviceId && event.accountId == genesis.accountId) {
                return GenesisKey(event.deviceId, event.signingPublicKey)
            }
        }
        return null
    }

    /**
     * One replay walk over canonical order into shadow state (§2–§3). Pure function of the
     * stored set plus the carried revocations ([carriedBans], [carriedDemotions],
     * [carriedTombstonedAccounts] — empty on the first walk, accumulated by the restart loop
     * in [foldAndCommit]).
     *
     * Verdict discipline is authenticity-only (§6 storage criterion):
     * - REJECTED = proven forgery (undecodable / wrong payload type / bad signature /
     *   id-derivation mismatch). Permanent, and poisoning structural descendants into PENDING.
     * - authorization-invalid / cut / sealed / duplicate / banned-or-demoted author = ignored,
     *   stored VERIFIED (authentic, no shadow effect — bans and policy failures never fork
     *   the chainable graph).
     * - unresolvable author / outside the fold input / poisoned = PENDING (not folded).
     */
    private suspend fun replay(
        order: List<Uuid>,
        byId: Map<Uuid, MessageRow>,
        ancestors: Map<Uuid, Set<Uuid>>,
        foldSet: Set<Uuid>,
        genesis: GenesisInfo?,
        genesisKey: GenesisKey?,
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
            val row = byId.getValue(id)
            val payload = row.payload as? MessagePayload.GlobalEvent
            if (payload == null) {
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
            val event = try {
                payload.decodeEvent()
            } catch (e: Exception) {
                verdicts[id] = VerificationState.REJECTED
                poisoned.add(id)
                continue
            }
            // Id-derivation assertion first (self-certifying ids): a forged add carrying
            // attacker keys for a victim id cannot match — sibling impersonation is
            // structurally impossible. Disproven authenticity outranks every policy
            // disposition below, including the poison rule (a poisoned node with a bad
            // signature is still proven forged).
            val derivationOk = when (event) {
                is GlobalEventPayload.AddAccount ->
                    cryptoProvider.accountIdFromPublicKey(event.accountSigningPublicKey) == event.accountId

                is GlobalEventPayload.AddDevice ->
                    cryptoProvider.peerIdFromPublicKey(event.signingPublicKey) == event.deviceId

                else -> true
            }
            if (!derivationOk) {
                verdicts[id] = VerificationState.REJECTED
                poisoned.add(id)
                continue
            }
            val authorId = payload.authorDeviceId
            val shadowAuthor = devices[authorId]
            val authorKey = shadowAuthor?.signingPublicKey
                ?: if (genesisKey != null && authorId == genesisKey.deviceId) {
                    genesisKey.signingPublicKey
                } else {
                    null
                }
            if (authorKey == null) {
                // Unresolvable author (credentials in a gap, or never existed) → PENDING,
                // never REJECTED — the global-room UNKNOWN_AUTHOR analogue (§2).
                verdicts[id] = VerificationState.PENDING
                continue
            }
            val sig = payload.authorSignature
            if (sig == null || sig.isEmpty() ||
                !cryptoProvider.verifyDetached(authorKey, payload.encodeForAuthorSigning(), sig)
            ) {
                verdicts[id] = VerificationState.REJECTED
                poisoned.add(id)
                continue
            }
            if (payload.prevIds.any { it in poisoned }) {
                // Provenance poison: a structural descendant of a proven forgery is
                // excluded from the fold — but stays PENDING, not REJECTED (its own
                // authenticity was not disproven; the crypto checks above already ran).
                verdicts[id] = VerificationState.PENDING
                poisoned.add(id)
                continue
            }
            // Device-seal enforcement lives in the author-cut gate below (carried bans
            // seal their full ancestry-exterior; walk bans kill inline only when
            // sequential). Reaching here means the author is live in this walk.
            val authorIsShadow = shadowAuthor != null
            val authorAccountId = shadowAuthor?.accountId

            // Direct-opponent exemption (narrow, type-matched): a revocation never seals
            // the principal that concurrently counter-revokes it. Genuinely concurrent
            // (neither in the other's ancestry — every backdated forgery included)
            // opposing revocations are a validity paradox decided by the restart loop
            // (mutual destruction), not by the seal. Third-party revocations (not aimed
            // at the sealer) and sequential retaliations (inside the seal's ancestry)
            // are unaffected.
            fun opposesSeal(seal: Uuid): Boolean {
                if (id in ancestors.getValue(seal) || seal in ancestors.getValue(id)) return false
                val sealPayload = byId[seal]?.payload as? MessagePayload.GlobalEvent ?: return false
                val sealEvent = runCatching { sealPayload.decodeEvent() }.getOrNull() ?: return false
                return when (event) {
                    is GlobalEventPayload.RemoveDevice ->
                        sealEvent is GlobalEventPayload.RemoveDevice &&
                                event.targetDeviceId == sealPayload.authorDeviceId &&
                                authorId == sealEvent.targetDeviceId

                    is GlobalEventPayload.RemoveAdmin ->
                        sealEvent is GlobalEventPayload.RemoveAdmin &&
                                event.targetAccountId == sealPayload.senderAccountId &&
                                authorAccountId == sealEvent.targetAccountId

                    is GlobalEventPayload.RemoveAccount ->
                        sealEvent is GlobalEventPayload.RemoveAccount &&
                                event.targetAccountId == sealPayload.senderAccountId &&
                                authorAccountId == sealEvent.targetAccountId

                    else -> false
                }
            }

            fun effectiveAdmin(account: AccountId): Boolean {
                val acc = accounts[account] ?: return false
                if (acc.status != IdentityStatus.ACTIVE) return false
                if (acc.isAdmin) return true
                // The account was demoted earlier in canonical order — but by a
                // concurrent direct opponent (walk or carried): mutual destruction via
                // the restart loop, not a settled demotion. Settled (sequential or
                // third-party) demotions count.
                val walkSeal = walkDemotions[account]
                if (walkSeal != null && opposesSeal(walkSeal)) return true
                val carriedSeal = carriedDemotions[account]
                if (carriedSeal != null && opposesSeal(carriedSeal)) return true
                return false
            }

            // Demotion seal (§3, admin level): a demotion seals the frontier it was appended
            // at. An admin-gated event positioned at-or-before the demotion but outside the
            // seal is void — this kills backdated counter-demotions and backdated grants
            // alike. Events positioned after the demotion are decided positionally (a
            // re-grant restores validity going forward; without one the author is not admin
            // anyway), so the seal never fires for them: demotion is reversible, and the
            // seal is interval-scoped (it only voids what its demoter never vouched for).
            fun sealed(account: AccountId): Boolean {
                val seal = demotionOf(account) ?: return false
                if (seal == id) return false
                if (opposesSeal(seal)) return false
                if (positionOf.getValue(id) > positionOf.getValue(seal)) return false
                return id !in ancestors.getValue(seal)
            }
            // Author cut (device-ban liveness): a ban discovered earlier in THIS walk
            // kills the author only if sequential (ban is an ancestor of this node —
            // first mover wins inline). Carried bans seal their ancestry-exterior
            // (backdated/concurrent forgeries die on the restart), except their own
            // defining node (a revocation never seals itself) and direct opponents
            // (counter-revocation paradox → restart loop). Poisoned/unresolvable nodes
            // never reach here (PENDING above), so provenance-poison always wins over
            // the seal.
            val walkBanOfAuthor = walkBans[authorId]
            val authorCutInline = walkBanOfAuthor != null && walkBanOfAuthor != id &&
                    walkBanOfAuthor in ancestors.getValue(id)
            val carriedBanSeal = carriedBans[authorId]
            val authorCutCarried = carriedBanSeal != null && carriedBanSeal != id &&
                    id !in ancestors.getValue(carriedBanSeal) && !opposesSeal(carriedBanSeal)
            if (authorCutInline || authorCutCarried) {
                // The ban seals the frontier it was appended at (§3, full scope): every event
                // by the banned device outside the ban's ancestry is void — backdated or
                // concurrent, adds or admin acts. A retaliatory event can never be inside its
                // own ban's ancestry (prevIds frozen before it existed), so retaliatory
                // cycles self-defeat once the honest ban is carried (§6.2).
                if (event is GlobalEventPayload.AddDevice && event.deviceId !in devices) {
                    ignoredAddDevices.add(event.deviceId)
                }
                verdicts[id] = VerificationState.VERIFIED
                continue
            }
            val authorIsAdmin = authorAccountId?.let { effectiveAdmin(it) } == true

            when (event) {
                is GlobalEventPayload.AddAccount -> {
                    if (id == genesis?.nodeId) {
                        // Genesis (admin by definition). Preempts same-account duplicates:
                        // a forged root sorting first cannot displace the true root (§3).
                        accounts[event.accountId] = FoldAccount(
                            accountId = event.accountId,
                            accountSigningPublicKey = event.accountSigningPublicKey,
                            displayName = event.displayName,
                            isAdmin = true,
                            status = IdentityStatus.ACTIVE,
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
                    // Sponsored (member-level): the author must be a vouched device — a fresh
                    // (self-introduction) author cannot sponsor; unilateral onboarding is closed.
                    if (!authorIsShadow) {
                        verdicts[id] = VerificationState.VERIFIED
                        continue
                    }
                    accounts[event.accountId] = FoldAccount(
                        accountId = event.accountId,
                        accountSigningPublicKey = event.accountSigningPublicKey,
                        displayName = event.displayName,
                        isAdmin = false,
                        status = IdentityStatus.ACTIVE,
                    )
                    sponsorKeys[authorId to event.accountId] = event.accountSigningPublicKey
                    verdicts[id] = VerificationState.VERIFIED
                }

                is GlobalEventPayload.AddDevice -> {
                    // Duplicate (first valid add wins; tombstoned ids stay dead — re-adding
                    // after removal requires a fresh key set → new device_id): ignored.
                    // (Id derivation was already asserted up front, before policy checks.)
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
                        // Branch 1: own-device add — the signer's account IS the target, and the
                        // signer must be a vouched device.
                        if (!authorIsShadow || target == null || authorAccountId != event.accountId) {
                            ignoreAdd()
                            continue
                        }
                    } else {
                        val binding = event.bindingBytes()
                        if (target == null) {
                            // Branch 2: new-account onboarding — same-signer AddAccount earlier in
                            // canonical order, key verifies under that AddAccount's pub key.
                            // Tombstoned accounts kill these absolutely, at every position
                            // (rule 2, §3 — a leaked recovery key of a banned account re-enters
                            // nothing; positional validity alone was evadable by backdating).
                            val sponsorKey = sponsorKeys[authorId to event.accountId]
                            if (event.accountId in carriedTombstonedAccounts || sponsorKey == null ||
                                !cryptoProvider.verifyDetached(sponsorKey, binding, event.keySignature)
                            ) {
                                ignoreAdd()
                                continue
                            }
                        } else {
                            // Branch 3: recovery relay (or the genesis self-introduction) —
                            // account exists and ACTIVE, key verifies under its pub key.
                            if (target.status != IdentityStatus.ACTIVE ||
                                event.accountId in carriedTombstonedAccounts ||
                                !cryptoProvider.verifyDetached(
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
                        deviceType = event.deviceType,
                        torEndpoint = event.torEndpoint,
                        signingPublicKey = event.signingPublicKey,
                        encryptionPublicKey = event.encryptionPublicKey,
                        keySignature = event.keySignature,
                        status = IdentityStatus.ACTIVE,
                        addedBy = authorId,
                        branch1 = event.keySignature == null,
                    )
                    verdicts[id] = VerificationState.VERIFIED
                }

                is GlobalEventPayload.GrantAdmin -> {
                    val targetAcc = accounts[event.targetAccountId]
                    val authAccount = authorAccountId
                    if (!authorIsShadow || authAccount == null || !effectiveAdmin(authAccount) ||
                        sealed(authAccount) ||
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
                    val authAccount = authorAccountId
                    // Genesis account is irrevocable (admin by definition): demoting it would
                    // fold the network to zero admins with no recovery path. Ignored VERIFIED.
                    if (!authorIsShadow || authAccount == null ||
                        targetAcc == null || targetAcc.status != IdentityStatus.ACTIVE ||
                        event.targetAccountId == genesis?.accountId ||
                        !effectiveAdmin(authAccount) ||
                        sealed(authAccount)
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
                    val authAccount = authorAccountId
                    val adminPath = authorIsShadow && authAccount != null &&
                            effectiveAdmin(authAccount) && !sealed(authAccount)
                    println(
                        "FOLD-DEBUG node ${
                            id.toString().takeLast(4)
                        } RemoveDevice shadow=$authorIsShadow adminPath=$adminPath own=$ownAccount targetNull=${target == null} targetStatus=${target?.status}"
                    )
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
                    val authAccount = authorAccountId
                    val adminPath = authorIsShadow && authAccount != null &&
                            effectiveAdmin(authAccount) && !sealed(authAccount)
                    // Genesis account is irrevocable (admin by definition + existence):
                    // removing it would fold the network to zero admins. Ignored VERIFIED.
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
        // Invalidated adds project as implied removals at the revocation's position (a
        // tombstone, never a "never-existed" retraction — pre-ban messages stay verifiable,
        // and the firewall can distinguish banned from never-seen).
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

    /**
     * Commit as a merge (§7): chain-derived columns from the fold output, tombstones for the
     * invalidated set, local-only columns preserved by the repository merge. Absence from the
     * output that is NOT tombstoned (unverifiable — credentials in a gap, provisional seeds)
     * is left untouched; a gap must never read as a removal. GLOBAL membership follows the
     * diff.
     *
     * First-fold discipline: silence is a property of the boot trigger (initial-sync
     * assimilation — a fresh sync must not burst-emit pre-existing state), not of a null
     * baseline. A first fold triggered by publish/ingest carries genuinely new data and emits
     * the diff against empty. This makes startup ordering race-free: whether the boot fold or
     * a data fold runs first, emissions are exactly the data-driven diff.
     */
    private suspend fun commit(result: ReplayResult, trigger: String) {
        val output = result.output
        for ((_, acc) in output.accounts) {
            identityKeyRepository.upsertChainAccount(
                accountId = acc.accountId,
                accountSigningPublicKey = acc.accountSigningPublicKey,
                isAdmin = acc.isAdmin,
                status = IdentityStatus.ACTIVE,
                displayName = acc.displayName,
            )
        }
        for ((_, dev) in output.devices) {
            identityKeyRepository.upsertChainDevice(
                deviceId = dev.deviceId,
                accountId = dev.accountId,
                deviceType = dev.deviceType,
                torEndpoint = dev.torEndpoint,
                signingPublicKey = dev.signingPublicKey,
                encryptionPublicKey = dev.encryptionPublicKey,
                keySignature = dev.keySignature,
                status = IdentityStatus.ACTIVE,
            )
        }
        for (accountId in result.tombstonedAccounts) {
            identityKeyRepository.tombstoneAccount(accountId)
            roomRepository.removeMember(RoomId.GLOBAL, accountId)
        }
        for (deviceId in result.tombstonedDevices) {
            identityKeyRepository.tombstoneDevice(deviceId)
        }
        val prev = lastCommit
        if (prev == null && trigger == "boot") {
            for (accountId in output.accounts.keys) {
                roomRepository.addMember(RoomId.GLOBAL, accountId, RoomMemberRole.MEMBER)
            }
            lastCommit = output
            AppLog.info(
                component = LogComponent.ORCHESTRATOR,
                event = LogEvent.GLOBAL_FOLD_COMMITTED,
                message = "Global events fold committed (baseline)",
                fields = mapOf(
                    "trigger" to trigger,
                    "accounts" to output.accounts.size,
                    "devices" to output.devices.size,
                ),
            )
            return
        }
        val baseline = prev ?: FoldOutput(emptyMap(), emptyMap())
        val changes = ArrayList<IdentityStateChange>()
        for ((accountId, acc) in output.accounts) {
            val before = baseline.accounts[accountId]
            if (before == null) {
                changes.add(IdentityStateChange.AccountAdded(accountId))
                if (acc.isAdmin) changes.add(IdentityStateChange.AdminGranted(accountId))
                roomRepository.addMember(RoomId.GLOBAL, accountId, RoomMemberRole.MEMBER)
            } else if (!before.isAdmin && acc.isAdmin) {
                changes.add(IdentityStateChange.AdminGranted(accountId))
            } else if (before.isAdmin && !acc.isAdmin) {
                changes.add(IdentityStateChange.AdminRevoked(accountId))
            }
        }
        for (accountId in baseline.accounts.keys) {
            if (accountId !in output.accounts && accountId in result.tombstonedAccounts) {
                changes.add(IdentityStateChange.AccountRemoved(accountId))
            }
        }
        for ((deviceId, dev) in output.devices) {
            if (deviceId !in baseline.devices) {
                changes.add(IdentityStateChange.DeviceAdded(dev.accountId, deviceId))
            }
        }
        for (deviceId in baseline.devices.keys) {
            if (deviceId !in output.devices && deviceId in result.tombstonedDevices) {
                changes.add(IdentityStateChange.DeviceRemoved(deviceId))
            }
        }
        lastCommit = output
        for (change in changes) _stateChanges.emit(change)
        AppLog.info(
            component = LogComponent.ORCHESTRATOR,
            event = LogEvent.GLOBAL_FOLD_COMMITTED,
            message = "Global events fold committed",
            fields = mapOf(
                "trigger" to trigger,
                "accounts" to output.accounts.size,
                "devices" to output.devices.size,
                "changes" to changes.size,
            ),
        )
    }
}
