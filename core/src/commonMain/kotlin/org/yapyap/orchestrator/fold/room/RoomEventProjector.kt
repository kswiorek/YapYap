package org.yapyap.orchestrator.fold.room

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.yapyap.crypto.identity.AccountId
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.orchestrator.dag.DagEngine
import org.yapyap.orchestrator.dag.MessageDraft
import org.yapyap.orchestrator.dag.RoomCreatedDraft
import org.yapyap.orchestrator.dag.RoomId
import org.yapyap.orchestrator.fold.global.GlobalEventProjector
import org.yapyap.orchestrator.fold.graph.ancestorClosures
import org.yapyap.orchestrator.fold.graph.canonicalOrder
import org.yapyap.orchestrator.fold.graph.childAdjacency
import org.yapyap.orchestrator.pipeline.InboundMessagePipeline
import org.yapyap.persistence.db.RoomMemberRole
import org.yapyap.persistence.db.RoomMemberStatus
import org.yapyap.persistence.db.RoomType
import org.yapyap.persistence.key.IdentityKeyRepository
import org.yapyap.persistence.messaging.MessageRepository
import org.yapyap.persistence.messaging.MessageRow
import org.yapyap.persistence.messaging.RoomRepository
import org.yapyap.protocol.envelopes.MessagePayload
import org.yapyap.protocol.envelopes.RoomEventPayload
import org.yapyap.routing.router.Router
import kotlin.uuid.Uuid

/**
 * Committed fold diff of one chat room. Consumers: the GUI (member list + the §3
 * badge/hide flags, which re-query the projection on these changes), future fan-out.
 */
sealed interface RoomStateChange {
    val roomId: RoomId

    /** Genesis commit: the room row is real (the GUI stops filtering it as UNKNOWN). */
    data class RoomCommitted(
        override val roomId: RoomId,
        val name: String,
        val roomType: RoomType,
    ) : RoomStateChange

    data class MemberAdded(override val roomId: RoomId, val accountId: AccountId) : RoomStateChange
    data class MemberRemoved(override val roomId: RoomId, val accountId: AccountId) : RoomStateChange

    /** Role transitions, including handover (successor → OWNER) — never on removed rows. */
    data class MemberRoleChanged(
        override val roomId: RoomId,
        val accountId: AccountId,
        val role: RoomMemberRole,
    ) : RoomStateChange
}

/**
 * Sole writer of chain-derived chat-room membership (`rooms` merge + `room_members`
 * recompute). Folds each room over the engine-filtered order (`VERIFIED` ∧ stored
 * flag — docs/room events.md §4) on ingest, reverify, GLOBAL-commit and boot triggers.
 *
 * Writes zero verdicts and zero flags: the engine owns both columns in rooms (single
 * writer — a fold-written flag would ping-pong against the reverify hooks, §3).
 * Deferral lives here: rows for accounts not yet in `accounts` wait for a later
 * fold (the `account_id` FK stays).
 */
interface RoomEventProjector {
    val stateChanges: Flow<RoomStateChange>

    fun start(scope: CoroutineScope)
    suspend fun stop()

    /**
     * Local publish path (mirrors `DefaultGlobalEventProjector.publish`): engine
     * append → synchronous fold + commit → broadcast to the folded ACTIVE
     * members. Authorization is never checked here — `RoomService` owns the
     * refusals; the fold ignores invalid events regardless.
     */
    suspend fun publishRoomCreated(draft: RoomCreatedDraft): RoomId
    suspend fun publishMemberAdd(roomId: RoomId, targetAccountId: AccountId)
    suspend fun publishMemberRemove(
        roomId: RoomId,
        targetAccountId: AccountId,
        successorAccountId: AccountId? = null,
    )

    suspend fun publishAddAdmin(roomId: RoomId, targetAccountId: AccountId)
    suspend fun publishRemoveAdmin(roomId: RoomId, targetAccountId: AccountId)
}

internal class DefaultRoomEventProjector(
    private val pipeline: InboundMessagePipeline,
    private val dagEngine: DagEngine,
    private val globalEventProjector: GlobalEventProjector,
    private val messageRepository: MessageRepository,
    private val roomRepository: RoomRepository,
    private val identityKeyRepository: IdentityKeyRepository,
    private val router: Router,
) : RoomEventProjector {

    private val _stateChanges = MutableSharedFlow<RoomStateChange>(extraBufferCapacity = 64)
    override val stateChanges: Flow<RoomStateChange> = _stateChanges.asSharedFlow()

    /** Per-room fold serialization (§5): one room's fold never blocks another's. */
    private val mapMutex = Mutex()
    private val roomMutexes = HashMap<RoomId, Mutex>()

    /** Last committed projection per room (committed rows only — deferred rows excluded). */
    private val lastCommits = HashMap<RoomId, RoomFoldOutput>()

    private var collectJob: Job? = null
    private var scope: CoroutineScope? = null

    override fun start(scope: CoroutineScope) {
        this.scope = scope
        if (collectJob?.isActive == true) return
        // Boot fold: sweep all chat rooms (idempotent full re-fold per room).
        scope.launch {
            for (roomId in roomRepository.allChatRoomIds()) foldAndCommit(roomId, "boot")
        }
        collectJob = scope.launch {
            launch {
                // Room insert (orphan creation included) and gap closure: the closing
                // message's ingest is the trigger; a full re-fold reads fresh flags.
                pipeline.ingestResults.collect { result ->
                    val roomId = result.payload.roomId
                    if (roomId != RoomId.GLOBAL) foldAndCommit(roomId, "ingest")
                }
            }
            launch {
                // Reverify flips PENDING first (engine mutex, before emit): a fold
                // reading a stale PENDING just gives no shadow effect until this
                // trigger recomputes.
                dagEngine.verificationStateChanges.collect { change ->
                    if (change.roomId != RoomId.GLOBAL) foldAndCommit(change.roomId, "reverify")
                }
            }
            launch {
                // GLOBAL commits land deferred member rows: sweep every chat room.
                globalEventProjector.stateChanges.collect {
                    for (roomId in roomRepository.allChatRoomIds()) foldAndCommit(roomId, "global-commit")
                }
            }
        }
    }

    override suspend fun stop() {
        collectJob?.cancel()
        collectJob = null
        scope = null
    }

    override suspend fun publishRoomCreated(draft: RoomCreatedDraft): RoomId {
        val node = dagEngine.createRoom(draft)
        foldAndCommit(node.roomId, "publish")
        // The folded ACTIVE set is the genesis member list — the common path
        // reaches everyone who must discover the room. No courtesy leg needed.
        broadcast(node.roomId, listOf(node))
        AppLog.info(
            component = LogComponent.ORCHESTRATOR,
            event = LogEvent.MESSAGE_APPENDED,
            message = "Room genesis published",
            fields = mapOf("roomId" to node.roomId),
        )
        return node.roomId
    }

    override suspend fun publishMemberAdd(roomId: RoomId, targetAccountId: AccountId) {
        val node = dagEngine.append(roomId, MessageDraft.RoomEvent(RoomEventPayload.MemberAdd(targetAccountId)))
        foldAndCommit(roomId, "publish")
        // The target was never in the genesis list and holds nothing of the room:
        // push the stored genesis head-start alongside the broadcast (dedup-safe
        // for re-adds), then normal frontier sync takes over the middle history.
        broadcast(roomId, listOf(node), courtesy = genesisPayloadOf(roomId)?.let { targetAccountId to it })
        AppLog.info(
            component = LogComponent.ORCHESTRATOR,
            event = LogEvent.MESSAGE_APPENDED,
            message = "Room MemberAdd published",
            fields = mapOf("roomId" to roomId, "target" to targetAccountId),
        )
    }

    override suspend fun publishMemberRemove(
        roomId: RoomId,
        targetAccountId: AccountId,
        successorAccountId: AccountId?,
    ) {
        val node = dagEngine.append(
            roomId,
            MessageDraft.RoomEvent(RoomEventPayload.MemberRemove(targetAccountId, successorAccountId)),
        )
        foldAndCommit(roomId, "publish")
        // The fold just excluded the target from the ACTIVE fan-out — push the
        // removal node to them directly so their client can flip its own row
        // instead of going silently stale (docs/room events.md §6).
        broadcast(roomId, listOf(node), courtesy = targetAccountId to node)
        AppLog.info(
            component = LogComponent.ORCHESTRATOR,
            event = LogEvent.MESSAGE_APPENDED,
            message = "Room MemberRemove published",
            fields = mapOf("roomId" to roomId, "target" to targetAccountId),
        )
    }

    override suspend fun publishAddAdmin(roomId: RoomId, targetAccountId: AccountId) {
        val node = dagEngine.append(roomId, MessageDraft.RoomEvent(RoomEventPayload.AddAdmin(targetAccountId)))
        foldAndCommit(roomId, "publish")
        broadcast(roomId, listOf(node))
        AppLog.info(
            component = LogComponent.ORCHESTRATOR,
            event = LogEvent.MESSAGE_APPENDED,
            message = "Room AddAdmin published",
            fields = mapOf("roomId" to roomId, "target" to targetAccountId),
        )
    }

    override suspend fun publishRemoveAdmin(roomId: RoomId, targetAccountId: AccountId) {
        val node = dagEngine.append(roomId, MessageDraft.RoomEvent(RoomEventPayload.RemoveAdmin(targetAccountId)))
        foldAndCommit(roomId, "publish")
        broadcast(roomId, listOf(node))
        AppLog.info(
            component = LogComponent.ORCHESTRATOR,
            event = LogEvent.MESSAGE_APPENDED,
            message = "Room RemoveAdmin published",
            fields = mapOf("roomId" to roomId, "target" to targetAccountId),
        )
    }

    /**
     * Common fan-out: [nodes] to every ACTIVE member (the just-committed fold
     * output — a newly added member is already in it), plus one targeted
     * courtesy payload for the account that needs a head start. Unconditional
     * and dedup-safe: a target that already holds the courtesy node no-ops it.
     */
    private suspend fun broadcast(
        roomId: RoomId,
        nodes: List<MessagePayload>,
        courtesy: Pair<AccountId, MessagePayload>? = null,
    ) {
        val members = roomRepository.membersOfRoom(roomId)
        coroutineScope {
            val sends = ArrayList<Deferred<*>>(nodes.size * members.size + 1)
            for (node in nodes) {
                for (member in members) {
                    sends.add(async { router.sendMessage(member, node) })
                }
            }
            if (courtesy != null) {
                sends.add(async { router.sendMessage(courtesy.first, courtesy.second) })
            }
            sends.awaitAll()
        }
        AppLog.debug(
            component = LogComponent.ORCHESTRATOR,
            event = LogEvent.OUTBOX_MESSAGE_QUEUED,
            message = "Room events broadcast to room members",
            fields = mapOf("roomId" to roomId, "nodeCount" to nodes.size, "memberCount" to members.size),
        )
    }

    /** The stored genesis node, for the MemberAdd courtesy leg (null pre-genesis). */
    private suspend fun genesisPayloadOf(roomId: RoomId): MessagePayload.RoomEvent? {
        // The self-certifying derivation admits exactly one genesis per room; the
        // engine rejects forged shapes, so this is a lookup, not a competition.
        for (row in messageRepository.findFoldableInRoom(roomId)) {
            val payload = row.payload as? MessagePayload.RoomEvent ?: continue
            if (payload.prevIds.isNotEmpty()) continue
            val event = runCatching { payload.decodeEvent() }.getOrNull()
            if (event is RoomEventPayload.RoomCreated) return payload
        }
        return null
    }

    private suspend fun mutexFor(roomId: RoomId): Mutex = mapMutex.withLock {
        roomMutexes.getOrPut(roomId) { Mutex() }
    }

    /** Fold + commit; reads the filtered order only (the order doubles as the fold set). */
    private suspend fun foldAndCommit(roomId: RoomId, trigger: String) {
        mutexFor(roomId).withLock {
            val rows = messageRepository.findFoldableInRoom(roomId)
            if (rows.isEmpty()) return
            val byId = rows.associateBy { it.payload.messageId }
            val children = childAdjacency(byId)
            val order = canonicalOrder(byId, children)
            val ancestors = ancestorClosures(byId)
            // No genesis in store → skip commit (pre-genesis orphans fold once it lands).
            val genesis = resolveGenesis(order, byId) ?: return
            val nodes = HashMap<Uuid, RoomFoldNode>(byId.size)
            for ((id, row) in byId) nodes[id] = foldNodeOf(row)

            val current = roomFoldToFixpoint(
                order, nodes, ancestors, genesis,
                onOscillation = {
                    AppLog.warn(
                        component = LogComponent.ORCHESTRATOR,
                        event = LogEvent.ROOM_FOLD_OSCILLATED,
                        message = "Room fold restart loop oscillated; kept the maximal-seal fixpoint",
                        fields = mapOf("trigger" to trigger, "roomId" to roomId),
                    )
                },
            )
            commit(roomId, current, nodes, genesis, trigger)
        }
    }

    /**
     * Storage→core adapter: decodes one row; null event = content message (rides the
     * order as a no-op) or undecodable bytes (unreachable for VERIFIED rows — the
     * engine REJECTs those at ingest — kept as the defensive backstop).
     */
    private fun foldNodeOf(row: MessageRow): RoomFoldNode {
        val payload = row.payload
        val event = runCatching {
            (payload as? MessagePayload.RoomEvent)?.decodeEvent()
        }.getOrNull()
        return RoomFoldNode(
            id = payload.messageId,
            // Trusted via §2: the engine binding-checks device↔account before VERIFIED.
            authorAccountId = payload.senderAccountId,
            event = event,
        )
    }

    /**
     * Genesis: the unique `VERIFIED` empty-`prevIds` `RoomCreated` node. Self-certifying
     * `roomId`s admit exactly one per room (the engine REJECTs forged shapes), so this
     * is a lookup, not a competition — first in canonical order wins defensively.
     */
    private fun resolveGenesis(order: List<Uuid>, byId: Map<Uuid, MessageRow>): RoomGenesisInfo? {
        for (id in order) {
            val row = byId.getValue(id)
            if (row.payload.prevIds.isNotEmpty()) continue
            val event = runCatching {
                (row.payload as? MessagePayload.RoomEvent)?.decodeEvent()
            }.getOrNull()
            if (event is RoomEventPayload.RoomCreated) {
                return RoomGenesisInfo(id, row.payload.senderAccountId)
            }
        }
        return null
    }

    /**
     * Commit as a merge for the `rooms` row and a recompute for `room_members`.
     * Commit material (name/type/space) is resolved from the defining genesis node:
     * the core outputs decisions (ids + roles/status), never display data.
     */
    private suspend fun commit(
        roomId: RoomId,
        result: RoomReplayResult,
        nodes: Map<Uuid, RoomFoldNode>,
        genesis: RoomGenesisInfo,
        trigger: String,
    ) {
        val output = result.output
        val created = nodes[genesis.nodeId]?.event as? RoomEventPayload.RoomCreated
            ?: error("fold invariant violated: room $roomId without a defining RoomCreated")
        roomRepository.mergeRoomFromGenesis(
            roomId = roomId,
            name = created.roomName,
            type = created.roomType,
            spaceId = created.spaceId?.toString(),
        )
        // Projection deferral (§5): the `room_members.account_id` FK stays — rows for
        // accounts not yet in `accounts` wait for a later fold. Devices imply the
        // account row, so deferred rows are unobservable in the interim.
        // TODO: [Finishing touches] bulk account-existence check once rooms outgrow the 10–20-user profile.
        val committed = output.members.filterKeys { accountId ->
            identityKeyRepository.getAccountRecord(accountId) != null
        }
        for ((accountId, member) in committed) {
            // The removal boundary rides the recompute: non-null exactly on
            // REMOVED rows (every honored removal has a defining node).
            if (member.status == RoomMemberStatus.REMOVED) {
                check(member.removalNodeId != null) {
                    "fold invariant violated: removed member $accountId in room $roomId without a removal node"
                }
            } else {
                check(member.removalNodeId == null) {
                    "fold invariant violated: active member $accountId in room $roomId carries a removal node"
                }
            }
            roomRepository.upsertMember(roomId, accountId, member.role, member.status, member.removalNodeId)
        }
        roomRepository.removeRoomMembersNotIn(roomId, committed.keys)
        // Diff the committed projection (deferred rows excluded, so a landing row
        // always reads as new) against the last commit.
        val prev = mapMutex.withLock { lastCommits[roomId]?.members }
        val changes = ArrayList<RoomStateChange>()
        if (prev == null) {
            // Baseline: silent except the room itself (the GUI filters UNKNOWN rooms
            // until the genesis commit lands).
            changes.add(RoomStateChange.RoomCommitted(roomId, created.roomName, created.roomType))
        } else {
            for ((accountId, member) in committed) {
                val was = prev[accountId]
                if (was == null) {
                    if (member.status == RoomMemberStatus.ACTIVE) {
                        changes.add(RoomStateChange.MemberAdded(roomId, accountId))
                        if (member.role != RoomMemberRole.MEMBER) {
                            changes.add(RoomStateChange.MemberRoleChanged(roomId, accountId, member.role))
                        }
                    } else {
                        // Landed already removed (removed-before-identity): the
                        // added-then-removed pair keeps the ordering invariant.
                        changes.add(RoomStateChange.MemberAdded(roomId, accountId))
                        changes.add(RoomStateChange.MemberRemoved(roomId, accountId))
                    }
                } else {
                    if (was.status == RoomMemberStatus.ACTIVE && member.status == RoomMemberStatus.REMOVED) {
                        changes.add(RoomStateChange.MemberRemoved(roomId, accountId))
                    } else if (was.status == RoomMemberStatus.REMOVED && member.status == RoomMemberStatus.ACTIVE) {
                        changes.add(RoomStateChange.MemberAdded(roomId, accountId))
                        if (member.role != RoomMemberRole.MEMBER) {
                            changes.add(RoomStateChange.MemberRoleChanged(roomId, accountId, member.role))
                        }
                    } else if (was.role != member.role && member.status == RoomMemberStatus.ACTIVE) {
                        changes.add(RoomStateChange.MemberRoleChanged(roomId, accountId, member.role))
                    }
                }
            }
        }
        mapMutex.withLock { lastCommits[roomId] = output.copy(members = committed) }
        for (change in changes) _stateChanges.emit(change)
        AppLog.info(
            component = LogComponent.ORCHESTRATOR,
            event = LogEvent.ROOM_FOLD_COMMITTED,
            message = "Room fold committed",
            fields = mapOf(
                "trigger" to trigger,
                "roomId" to roomId,
                "members" to committed.size,
                "changes" to changes.size,
            ),
        )
    }
}
