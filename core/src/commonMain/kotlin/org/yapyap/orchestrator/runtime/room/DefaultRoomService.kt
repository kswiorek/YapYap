package org.yapyap.orchestrator.runtime.room

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.yapyap.crypto.identity.AccountId
import org.yapyap.crypto.identity.IdentityResolver
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.orchestrator.dag.DagException
import org.yapyap.orchestrator.dag.RoomCreatedDraft
import org.yapyap.orchestrator.fold.room.RoomEventProjector
import org.yapyap.persistence.db.RoomMemberRole
import org.yapyap.persistence.db.RoomMemberStatus
import org.yapyap.persistence.key.IdentityKeyRepository
import org.yapyap.persistence.messaging.RoomMemberRecord
import org.yapyap.persistence.messaging.RoomRepository
import org.yapyap.protocol.RoomId
import org.yapyap.protocol.RoomType

/**
 * GUI-facing room management over the room-event projector's publish path.
 * Creation publishes the `RoomCreated` genesis; membership ops publish the
 * admin events. Every op pre-checks against our own projection (refusals are
 * values); the fold ignores invalid events regardless, so a revocation race
 * degrades to a no-op publish, never a fork. Infra failures throw.
 */
internal class DefaultRoomService(
    private val projector: RoomEventProjector,
    private val roomRepository: RoomRepository,
    private val identityKeyRepository: IdentityKeyRepository,
    private val identityResolver: IdentityResolver,
) : RoomService {

    private val _rooms = MutableStateFlow<List<RoomDetails>>(emptyList())
    override val rooms: StateFlow<List<RoomDetails>> = _rooms.asStateFlow()

    private var collectJob: Job? = null

    fun start(scope: CoroutineScope) {
        if (collectJob?.isActive == true) return
        collectJob = scope.launch {
            refreshRooms()
            launch { projector.stateChanges.collect { refreshRooms() } }
        }
    }

    fun stop() {
        collectJob?.cancel()
        collectJob = null
    }

    private suspend fun refreshRooms() {
        _rooms.value = roomRepository.allChatRoomIds().mapNotNull { roomId ->
            detailsOf(roomId)?.takeIf { it.type != RoomType.UNKNOWN }
        }
    }

    override suspend fun room(roomId: RoomId): RoomDetails? = detailsOf(roomId)

    private suspend fun detailsOf(roomId: RoomId): RoomDetails? {
        val record = roomRepository.roomOf(roomId) ?: return null
        val members = roomRepository.memberStatusesOfRoom(roomId)
            .map { RoomMemberView(it.accountId, it.role, it.status) }
        return RoomDetails(roomId, record.name, record.type, members)
    }

    override suspend fun createRoom(name: String?, members: Set<AccountId>): CreateRoomResult {
        if (members.isEmpty()) return CreateRoomResult.Refused(CreateRoomRefusal.EMPTY_MEMBERS)
        val local = identityResolver.getLocalAccountId()
        val full = members + local
        for (member in full) {
            if (identityKeyRepository.getAccountRecord(member) == null) {
                return CreateRoomResult.Refused(CreateRoomRefusal.UNKNOWN_MEMBER)
            }
        }
        val roomName = name?.trim().orEmpty()
        val roomId = projector.publishRoomCreated(RoomCreatedDraft(full, roomName, RoomType.TEXT_CHANNEL))
        refreshRooms()
        return CreateRoomResult.Created(roomId)
    }

    override suspend fun addMember(roomId: RoomId, target: AccountId): RoomEventOutcome {
        if (roomRepository.roomOf(roomId) == null) {
            return RoomEventOutcome.Refused(RoomEventRefusal.RoomNotFound)
        }
        checkAdmin(roomId)?.let { return it }
        if (identityKeyRepository.getAccountRecord(target) == null) {
            return RoomEventOutcome.Refused(RoomEventRefusal.UnknownMember)
        }
        return publish { projector.publishMemberAdd(roomId, target) }
    }

    private suspend fun verifyAccount(roomId: RoomId, target: AccountId): RoomEventOutcome? {
        val targetRow = roomRepository.memberRowOf(roomId, target)
        if (targetRow?.status != RoomMemberStatus.ACTIVE) {
            return RoomEventOutcome.Refused(RoomEventRefusal.NotMember)
        }
        if (targetRow.role == RoomMemberRole.OWNER) {
            return RoomEventOutcome.Refused(RoomEventRefusal.OwnerIrrevocable)
        }
        return null
    }

    override suspend fun removeMember(roomId: RoomId, target: AccountId): RoomEventOutcome {
        if (roomRepository.roomOf(roomId) == null) {
            return RoomEventOutcome.Refused(RoomEventRefusal.RoomNotFound)
        }
        checkAdmin(roomId)?.let { return it }
        verifyAccount(roomId, target)?.let { return it }
        return publish { projector.publishMemberRemove(roomId, target) }
    }

    override suspend fun grantAdmin(roomId: RoomId, target: AccountId): RoomEventOutcome {
        if (roomRepository.roomOf(roomId) == null) {
            return RoomEventOutcome.Refused(RoomEventRefusal.RoomNotFound)
        }
        checkAdmin(roomId)?.let { return it }
        verifyAccount(roomId, target)?.let { return it }
        return publish { projector.publishAddAdmin(roomId, target) }
    }

    override suspend fun revokeAdmin(roomId: RoomId, target: AccountId): RoomEventOutcome {
        if (roomRepository.roomOf(roomId) == null) {
            return RoomEventOutcome.Refused(RoomEventRefusal.RoomNotFound)
        }
        checkAdmin(roomId)?.let { return it }
        verifyAccount(roomId, target)?.let { return it }
        return publish { projector.publishRemoveAdmin(roomId, target) }
    }

    override suspend fun leaveRoom(roomId: RoomId, successorAccountId: AccountId?): RoomEventOutcome {
        if (roomRepository.roomOf(roomId) == null) {
            return RoomEventOutcome.Refused(RoomEventRefusal.RoomNotFound)
        }
        val local = identityResolver.getLocalAccountId()
        val self = roomRepository.memberRowOf(roomId, local)
            ?.takeIf { it.status == RoomMemberStatus.ACTIVE }
            ?: return RoomEventOutcome.Refused(RoomEventRefusal.NotMember)
        if (self.role == RoomMemberRole.OWNER) {
            // Owner self-leave is the handover: the fold honors it only with a
            // valid successor, so refuse every bad shape up front.
            val successor = successorAccountId
                ?: return RoomEventOutcome.Refused(RoomEventRefusal.InvalidSuccessor)
            if (successor == local) {
                return RoomEventOutcome.Refused(RoomEventRefusal.InvalidSuccessor)
            }
            val successorRow = roomRepository.memberRowOf(roomId, successor)
            if (successorRow?.status != RoomMemberStatus.ACTIVE) {
                return RoomEventOutcome.Refused(RoomEventRefusal.InvalidSuccessor)
            }
            return publish { projector.publishMemberRemove(roomId, local, successor) }
        }
        // The successor field is owner-only — a non-owner smuggling one would be
        // ignored whole by the fold; refuse instead.
        if (successorAccountId != null) {
            return RoomEventOutcome.Refused(RoomEventRefusal.InvalidSuccessor)
        }
        return publish { projector.publishMemberRemove(roomId, local) }
    }

    /** Admin-gated pre-check: room known, local ACTIVE, local admin-or-owner. */
    private suspend fun checkAdmin(roomId: RoomId): RoomEventOutcome? {
        val local = localActiveRow(roomId)
            ?: return RoomEventOutcome.Refused(RoomEventRefusal.NotMember)
        if (local.role != RoomMemberRole.ADMIN && local.role != RoomMemberRole.OWNER) {
            return RoomEventOutcome.Refused(RoomEventRefusal.NotAdmin)
        }
        return null
    }

    private suspend fun localActiveRow(roomId: RoomId): RoomMemberRecord? {
        val local = identityResolver.getLocalAccountId()
        return roomRepository.memberRowOf(roomId, local)
            ?.takeIf { it.status == RoomMemberStatus.ACTIVE }
    }

    private suspend fun publish(call: suspend () -> Unit): RoomEventOutcome {
        return try {
            call()
            RoomEventOutcome.Published
        } catch (e: DagException.FrontierUnavailable) {
            AppLog.warn(
                component = LogComponent.ORCHESTRATOR,
                event = LogEvent.APPEND_REFUSED,
                message = "Room publish refused — frontier unchainable while syncing",
                fields = mapOf("roomId" to e.roomId),
            )
            RoomEventOutcome.Refused(RoomEventRefusal.NotReady)
        }
    }
}
