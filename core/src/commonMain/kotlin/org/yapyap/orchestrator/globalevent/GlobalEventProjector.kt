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
import org.yapyap.persistence.db.DeviceType
import org.yapyap.persistence.db.IdentityStatus
import org.yapyap.persistence.db.RoomMemberRole
import org.yapyap.persistence.db.RoomType
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
 *
 * The fold itself lives in [GlobalFold]: this class owns scaffolding (canonical order,
 * genesis, reachability), the storage-backed verdict write and the merge commit; the pure
 * replay/restart core ([replayFold]/[foldToFixpoint]) is shared with the dynamics fuzzer.
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
     * Canonical fold + commit (§2–§7): verdicts are outputs, never inputs.
     * REJECTED = proven forgery; auth-invalid / cut / sealed / duplicate = ignored
     * VERIFIED; unresolvable / unreachable / poisoned = PENDING.
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
            val nodes = HashMap<Uuid, FoldNode>(byId.size)
            for ((id, row) in byId) nodes[id] = foldNodeOf(row)
            val crypto = object : FoldCrypto {
                override suspend fun verifyAuthor(key: ByteArray, msg: ByteArray, sig: ByteArray): Boolean =
                    cryptoProvider.verifyDetached(key, msg, sig)

                override suspend fun verifyBinding(key: ByteArray, binding: ByteArray, sig: ByteArray): Boolean =
                    cryptoProvider.verifyDetached(key, binding, sig)
            }

            // Restart loop over carried revocations: a backdated event sorting before its
            // revocation is caught on the restart, where the revocation is known from the
            // start. See [foldToFixpoint] for the oscillation doctrine.
            val current = foldToFixpoint(
                order, nodes, ancestors, foldSet, genesis, genesisKey, crypto,
                onOscillation = {
                    AppLog.warn(
                        component = LogComponent.ORCHESTRATOR,
                        event = LogEvent.GLOBAL_FOLD_BAN_DIVERGED,
                        message = "Fold restart loop oscillated; kept the maximal-revocation fixpoint",
                        fields = mapOf("trigger" to trigger),
                    )
                },
            )
            for ((messageId, verdict) in current.verdicts) {
                if (byId.getValue(messageId).verificationState != verdict) {
                    messageRepository.updateVerificationState(messageId, verdict)
                }
            }
            commit(current, nodes, trigger)
            lastFoldDevices = current.output.devices
        }
    }

    /**
     * Storage→core adapter: decodes one row; null event = proven forgery (the core
     * assigns REJECTED). Id-derivation is precomputed here.
     */
    private suspend fun foldNodeOf(row: MessageRow): FoldNode {
        val payload = row.payload
        val event = runCatching {
            (payload as? MessagePayload.GlobalEvent)?.decodeEvent()
        }.getOrNull()
        // Id-derivation assertion (self-certifying ids), precomputed here so the core
        // stays crypto-free apart from the [FoldCrypto] oracles.
        val derivationOk = when (event) {
            is GlobalEventPayload.AddAccount ->
                cryptoProvider.accountIdFromPublicKey(event.accountSigningPublicKey) == event.accountId

            is GlobalEventPayload.AddDevice ->
                cryptoProvider.peerIdFromPublicKey(event.signingPublicKey) == event.deviceId

            else -> true
        }
        return FoldNode(
            id = payload.messageId,
            authorDeviceId = payload.authorDeviceId,
            senderAccountId = payload.senderAccountId,
            prevIds = payload.prevIds,
            authorSignature = payload.authorSignature,
            signingBytes = (payload as? MessagePayload.GlobalEvent)?.encodeForAuthorSigning()
                ?: ByteArray(0),
            event = event,
            derivationOk = derivationOk,
        )
    }

    /** Stored-graph child adjacency, for the topo sort and genesis descent. */
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
     * Canonical order (§2): topological over stored `prevIds` (`createdAt, messageId`
     * tiebreak). Unorderable cycles replay last, deterministically.
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
     * Genesis resolution (§3): the empty-`prevIds` `AddAccount` with the most transitive
     * descendants. Forged roots have (almost) none; the pre-forgery margin is permanent.
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

    /** Memoized transitive `prevIds` closures (the seals' vouching sets). */
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
     * Fold input: ancestry-complete nodes reachable from the winning root. Everything
     * else stays PENDING — a lost event parks only its own branch.
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
     * Genesis self-introduction key: the first `AddDevice` authored by the added
     * device itself for the genesis account. Self-authenticating; resolves
     * authorship only, never authorization.
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
     * Commit as a merge: chain-derived fields from the fold output, tombstones for the
     * invalidated set, local-only columns preserved. Absent-but-untombstoned rows are
     * unverifiable (gaps), never removals.
     */
    private suspend fun commit(
        result: ReplayResult,
        nodes: Map<Uuid, FoldNode>,
        trigger: String,
    ) {
        val output = result.output
        for ((_, acc) in output.accounts) {
            // Commit material is resolved from the defining node: the core outputs
            // decisions (ids + admin/status), never display data.
            val add = nodes[acc.nodeId]?.event as? GlobalEventPayload.AddAccount
                ?: error("fold invariant violated: account ${acc.accountId} without a defining AddAccount")
            identityKeyRepository.upsertChainAccount(
                accountId = acc.accountId,
                accountSigningPublicKey = acc.accountSigningPublicKey,
                isAdmin = acc.isAdmin,
                status = IdentityStatus.ACTIVE,
                displayName = add.displayName,
            )
        }
        for ((_, dev) in output.devices) {
            val add = nodes[dev.nodeId]?.event as? GlobalEventPayload.AddDevice
                ?: error("fold invariant violated: device ${dev.deviceId} without a defining AddDevice")
            identityKeyRepository.upsertChainDevice(
                deviceId = dev.deviceId,
                accountId = dev.accountId,
                deviceType = add.deviceType,
                torEndpoint = add.torEndpoint,
                signingPublicKey = add.signingPublicKey,
                encryptionPublicKey = add.encryptionPublicKey,
                keySignature = add.keySignature,
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
