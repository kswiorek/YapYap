package org.yapyap.orchestrator.fold.room

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.yapyap.crypto.identity.AccountId
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.orchestrator.dag.DagEngine
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
}

internal class DefaultRoomEventProjector(
    private val pipeline: InboundMessagePipeline,
    private val dagEngine: DagEngine,
    private val globalEventProjector: GlobalEventProjector,
    private val messageRepository: MessageRepository,
    private val roomRepository: RoomRepository,
    private val identityKeyRepository: IdentityKeyRepository,
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
        // Boot fold: sweep all chat rooms (idempotent full re-fold per room).
        scope.launch {
            for (roomId in roomRepository.allChatRoomIds()) foldAndCommit(roomId, "boot")
        }
        if (collectJob?.isActive == true) return
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
        // TODO: bulk account-existence check once rooms outgrow the 10–20-user profile.
        val committed = output.members.filterKeys { accountId ->
            identityKeyRepository.getAccountRecord(accountId) != null
        }
        for ((accountId, member) in committed) {
            roomRepository.addMember(roomId, accountId, member.role, member.status)
        }
        roomRepository.removeRoomMembersNotIn(roomId, committed.keys)
        // Diff the committed projection (deferred rows excluded, so a landing row
        // always reads as new) against the last commit.
        val prev = lastCommits[roomId]?.members
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
        lastCommits[roomId] = output.copy(members = committed)
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
