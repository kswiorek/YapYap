package org.yapyap.orchestrator.fold.room

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.yapyap.crypto.identity.AccountId
import org.yapyap.crypto.identity.AccountIdentityRecord
import org.yapyap.crypto.identity.DeviceIdentityRecord
import org.yapyap.orchestrator.dag.*
import org.yapyap.orchestrator.fold.global.GlobalEventProjector
import org.yapyap.orchestrator.fold.global.IdentityStateChange
import org.yapyap.persistence.db.IdentityStatus
import org.yapyap.persistence.db.RoomMemberRole
import org.yapyap.persistence.db.RoomMemberStatus
import org.yapyap.persistence.db.VerificationState
import org.yapyap.persistence.key.InMemoryIdentityKeyRepository
import org.yapyap.persistence.messaging.RoomMemberRecord
import org.yapyap.persistence.messaging.RoomRecord
import org.yapyap.persistence.messaging.RoomRepository
import org.yapyap.protocol.*
import org.yapyap.protocol.envelopes.*
import org.yapyap.routing.router.*
import org.yapyap.sync.FakeInboundMessagePipeline
import org.yapyap.testfixtures.FakeMessageRepository
import org.yapyap.testfixtures.epochSeconds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.uuid.Uuid

/**
 * Projector tests (§9, projector level): the adapter + commit around the fuzz-pinned
 * core — genesis merge, member recompute (REMOVED retained, re-add reactivates),
 * deferral for unknown accounts, re-fold triggers, the no-genesis skip, and the
 * zero verdict/flag-write regression. Authorship is precomputed: rows are seeded
 * directly with the engine's verdicts/flags, no crypto involved.
 */
private val ROwner = AccountId("rp-owner")
private val RAdmin = AccountId("rp-admin")
private val RMember = AccountId("rp-member")
private val ROutsider = AccountId("rp-outsider")
private val RDevice = PeerId("rp-device")

private fun roomCreated(name: String = "room", members: List<AccountId> = emptyList()) =
    RoomEventPayload.RoomCreated(members, name, RoomType.TEXT_CHANNEL, null)

private data class MemberCell(
    var role: RoomMemberRole,
    var status: RoomMemberStatus,
    var removalNodeId: Uuid? = null,
)

/** Precise in-memory [RoomRepository]: rooms + full member rows for assertions. */
private class RecordingRoomRepository : RoomRepository {
    data class RoomCell(var name: String, var type: RoomType, var spaceId: String?)

    val rooms = mutableMapOf<RoomId, RoomCell>()
    val members = mutableMapOf<Pair<RoomId, AccountId>, MemberCell>()

    override suspend fun membersOfRoom(roomId: RoomId): List<AccountId> =
        // Mirror the ACTIVE-only access read.
        members.filter { (key, cell) -> key.first == roomId && cell.status == RoomMemberStatus.ACTIVE }
            .map { it.key.second }

    override suspend fun memberStatusesOfRoom(roomId: RoomId): List<RoomMemberRecord> =
        members.filter { (key, _) -> key.first == roomId }
            .map { (key, cell) -> RoomMemberRecord(key.second, cell.role, cell.status, cell.removalNodeId) }

    override suspend fun memberRowOf(roomId: RoomId, accountId: AccountId): RoomMemberRecord? =
        members[roomId to accountId]
            ?.let { RoomMemberRecord(accountId, it.role, it.status, it.removalNodeId) }

    override suspend fun roomsOfPeer(peerId: PeerId): List<RoomId> = error("not used")

    override suspend fun ensureRoomExists(roomId: RoomId, type: RoomType, name: String) {
        rooms.getOrPut(roomId) { RoomCell(name, type, null) }
    }

    override suspend fun upsertMember(
        roomId: RoomId,
        accountId: AccountId,
        role: RoomMemberRole,
        status: RoomMemberStatus,
        removalNodeId: Uuid?,
    ) {
        members[roomId to accountId] = MemberCell(role, status, removalNodeId)
    }

    override suspend fun removeMember(roomId: RoomId, accountId: AccountId) {
        members[roomId to accountId]?.status = RoomMemberStatus.REMOVED
    }

    override suspend fun allChatRoomIds(): List<RoomId> =
        rooms.keys.filter { it != RoomId.GLOBAL }

    override suspend fun roomOf(roomId: RoomId): RoomRecord? =
        rooms[roomId]?.let { RoomRecord(roomId, it.spaceId, it.type, it.name) }

    override suspend fun mergeRoomFromGenesis(roomId: RoomId, name: String, type: RoomType, spaceId: String?) {
        val cell = rooms.getOrPut(roomId) { RoomCell(name, type, null) }
        cell.name = name
        cell.type = type
        if (spaceId != null) cell.spaceId = spaceId
    }

    override suspend fun removeRoomMembersNotIn(roomId: RoomId, keep: Collection<AccountId>) {
        members.keys.removeAll { (r, a) -> r == roomId && a !in keep }
    }
}

private class FakeDagEngine : DagEngine {
    private val _changes = MutableSharedFlow<VerificationStateChange>(extraBufferCapacity = 64)
    override val verificationStateChanges: Flow<VerificationStateChange> = _changes.asSharedFlow()

    fun emit(change: VerificationStateChange) {
        _changes.tryEmit(change)
    }

    override suspend fun append(roomId: RoomId, draft: MessageDraft): MessagePayload = error("not used")

    override suspend fun createRoom(draft: RoomCreatedDraft): MessagePayload.RoomEvent = error("not used")
    override suspend fun ingest(payload: MessagePayload): IngestResult? = error("not used")

    override suspend fun reverifyPendingFor(deviceId: PeerId): List<VerificationStateChange> = error("not used")
    override suspend fun reverifyAllPending(): List<VerificationStateChange> = error("not used")
}

private class FakeGlobalEventProjector : GlobalEventProjector {
    private val _changes = MutableSharedFlow<IdentityStateChange>(extraBufferCapacity = 64)
    override val stateChanges: Flow<IdentityStateChange> = _changes.asSharedFlow()

    fun emit(change: IdentityStateChange) {
        _changes.tryEmit(change)
    }

    override fun start(scope: CoroutineScope) = Unit
    override suspend fun stop() = Unit
    override suspend fun publishGenesisAccount(
        account: AccountIdentityRecord,
        device: DeviceIdentityRecord,
        deviceType: DeviceType,
        torEndpoint: TorEndpoint,
        accountKeySignature: ByteArray,
    ) = error("not used")

    override suspend fun publishSponsoredNewAccount(invite: Invite, grantAdmin: Boolean) = error("not used")
    override suspend fun publishOwnAccountDevice(invite: Invite) = error("not used")
    override suspend fun publishRelayedDevice(request: RecoveryRequest) = error("not used")
    override suspend fun publishGrantAdmin(targetAccountId: AccountId) = error("not used")
    override suspend fun publishRemoveAdmin(targetAccountId: AccountId) = error("not used")
    override suspend fun publishRemoveAccount(targetAccountId: AccountId) = error("not used")
    override suspend fun publishRemoveDevice(targetDeviceId: PeerId) = error("not used")
    override suspend fun activeDevicesAddedBy(authorDeviceId: PeerId): List<PeerId> = error("not used")
}

private class FakeRouter : Router {
    val sent = mutableListOf<Pair<AccountId, MessagePayload>>()
    override val incomingMessages: Flow<MessagePayload> = MutableSharedFlow()
    override val typingIndicators: Flow<TypingIndicatorEvent> = MutableSharedFlow()
    override val bootstrapPackets: Flow<BootstrapPacketEvent> = MutableSharedFlow()
    override val pingPayloads: Flow<PingFrontiers> = MutableSharedFlow()

    override suspend fun start() = Unit
    override suspend fun stop() = Unit
    override fun isRunning(): Boolean = true
    override suspend fun announceOnline() = Unit
    override suspend fun sendMessage(
        target: AccountId,
        payload: MessagePayload,
    ): SendMessageResult {
        sent.add(target to payload)
        return SendMessageResult(SendMessageStatus.SUCCESS, peersTotal = 1, peersQueued = 1, failureKind = null)
    }

    override suspend fun sendTypingIndicator(
        targets: Collection<AccountId>,
        roomId: RoomId,
        interval: Duration,
    ) = Unit

    override suspend fun sendBootstrap(
        payload: BootstrapPayload,
        target: PeerId,
        targetEndpoint: TorEndpoint?,
        sharedSecret: ByteArray?,
    ) = Unit
}

private class Harness {
    val messageRepo = FakeMessageRepository()
    val roomRepo = RecordingRoomRepository()
    val identityRepo = InMemoryIdentityKeyRepository()
    val pipeline = FakeInboundMessagePipeline()
    val dagEngine = FakeDagEngine()
    val globalProjector = FakeGlobalEventProjector()
    val router = FakeRouter()
    val seen = mutableListOf<RoomStateChange>()
    val projector = DefaultRoomEventProjector(
        pipeline = pipeline,
        dagEngine = dagEngine,
        globalEventProjector = globalProjector,
        messageRepository = messageRepo,
        roomRepository = roomRepo,
        identityKeyRepository = identityRepo,
        router = router,
    )
    private var tick = 1000L

    fun startIn(scope: CoroutineScope) {
        // Subscribe first: a replay=0 flow drops emissions with zero collectors,
        // and the boot sweep commits (unlike the global tier's silent baseline).
        scope.launch { projector.stateChanges.collect { seen.add(it) } }
        projector.start(scope)
    }

    fun roomEvent(
        roomId: RoomId,
        author: AccountId,
        prevIds: List<Uuid>,
        event: RoomEventPayload,
    ): MessagePayload.RoomEvent = MessagePayload.RoomEvent(
        messageId = Uuid.random(),
        roomId = roomId,
        senderAccountId = author,
        authorDeviceId = RDevice,
        prevIds = prevIds,
        createdAt = epochSeconds(tick++),
        eventBytes = event.encode(),
        authorSignature = byteArrayOf(0x01),
    )

    fun text(roomId: RoomId, author: AccountId, prevIds: List<Uuid>): MessagePayload.Text =
        MessagePayload.Text(
            messageId = Uuid.random(),
            roomId = roomId,
            senderAccountId = author,
            authorDeviceId = RDevice,
            prevIds = prevIds,
            createdAt = epochSeconds(tick++),
            text = "hello",
            authorSignature = byteArrayOf(0x01),
        )

    suspend fun store(
        payload: MessagePayload,
        complete: Boolean = true,
        state: VerificationState = VerificationState.VERIFIED,
        orphaned: Boolean = false,
    ) {
        messageRepo.insert(payload, orphaned, complete, state)
        for (parent in payload.prevIds) messageRepo.insertParent(payload.messageId, parent)
    }

    /** Simulates a GLOBAL commit landing identity (the deferral counterpart). */
    suspend fun known(vararg accounts: AccountId) {
        for (account in accounts) {
            identityRepo.upsertChainAccount(account, null, false, IdentityStatus.ACTIVE, account.id)
        }
    }

    fun triggerIngest(payload: MessagePayload) {
        pipeline.emit(IngestResult.Inserted(payload))
    }

    fun triggerReverify(messageId: Uuid, roomId: RoomId) {
        dagEngine.emit(
            VerificationStateChange(messageId, roomId, VerificationState.PENDING, VerificationState.VERIFIED),
        )
    }

    fun triggerGlobal(account: AccountId) {
        globalProjector.emit(IdentityStateChange.AccountAdded(account))
    }

    /** Stored verdict/flag snapshot: the projector must never write either column. */
    fun flagSnapshot(): Map<Uuid, Pair<VerificationState, Boolean>> =
        messageRepo.byId.mapValues { (_, row) -> row.verificationState to row.ancestryComplete }
}

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultRoomEventProjectorTest {

    @Test
    fun genesis_commit_writes_room_and_members() = runTest {
        val h = Harness()
        val room = RoomId(Uuid.random())
        h.roomRepo.ensureRoomExists(room, RoomType.UNKNOWN, "")
        h.known(ROwner, RAdmin, RMember)
        val g = h.roomEvent(room, ROwner, emptyList(), roomCreated(members = listOf(RAdmin, RMember)))
        h.store(g)
        val grant = h.roomEvent(room, ROwner, listOf(g.messageId), RoomEventPayload.AddAdmin(RAdmin))
        h.store(grant)
        h.store(h.text(room, RMember, listOf(g.messageId, grant.messageId)))
        val before = h.flagSnapshot()
        h.startIn(backgroundScope)
        runCurrent()

        // Rooms merge: provisional UNKNOWN overwritten from the defining genesis.
        assertEquals("room", h.roomRepo.rooms[room]?.name)
        assertEquals(RoomType.TEXT_CHANNEL, h.roomRepo.rooms[room]?.type)
        // Member recompute: owner slot maps to OWNER; grant applied.
        assertEquals(MemberCell(RoomMemberRole.OWNER, RoomMemberStatus.ACTIVE), h.roomRepo.members[room to ROwner])
        assertEquals(MemberCell(RoomMemberRole.ADMIN, RoomMemberStatus.ACTIVE), h.roomRepo.members[room to RAdmin])
        assertEquals(MemberCell(RoomMemberRole.MEMBER, RoomMemberStatus.ACTIVE), h.roomRepo.members[room to RMember])
        // Baseline commit is silent except the room itself.
        assertEquals(1, h.seen.size)
        assertEquals(RoomStateChange.RoomCommitted(room, "room", RoomType.TEXT_CHANNEL), h.seen[0])
        // Regression: the projector writes zero verdicts and zero flags.
        assertEquals(before, h.flagSnapshot())
    }

    @Test
    fun no_genesis_skips_commit() = runTest {
        val h = Harness()
        val room = RoomId(Uuid.random())
        h.roomRepo.ensureRoomExists(room, RoomType.UNKNOWN, "")
        h.known(ROwner)
        // Foldable content but no genesis (pre-genesis orphans fold once it lands)…
        h.store(h.text(room, ROwner, emptyList()))
        // …and a PENDING genesis is not foldable either.
        val pending = h.roomEvent(room, ROwner, emptyList(), roomCreated())
        h.store(pending, state = VerificationState.PENDING)
        h.startIn(backgroundScope)
        runCurrent()

        assertEquals(RoomType.UNKNOWN, h.roomRepo.rooms[room]?.type)
        assertTrue(h.roomRepo.members.isEmpty())
        assertTrue(h.seen.isEmpty())
    }

    @Test
    fun pending_and_incomplete_excluded_until_reverify() = runTest {
        val h = Harness()
        val room = RoomId(Uuid.random())
        h.roomRepo.ensureRoomExists(room, RoomType.UNKNOWN, "")
        h.known(ROwner, RAdmin, ROutsider)
        val g = h.roomEvent(room, ROwner, emptyList(), roomCreated(members = listOf(RAdmin)))
        h.store(g)
        // VERIFIED on its own merits but born incomplete (child of a PENDING node):
        // never chainable, excluded from the fold set.
        val add = h.roomEvent(room, ROwner, listOf(g.messageId), RoomEventPayload.MemberAdd(ROutsider))
        h.store(add, complete = false)
        h.startIn(backgroundScope)
        runCurrent()

        assertNull(h.roomRepo.members[room to ROutsider])
        assertEquals(1, h.seen.size)
        assertEquals(RoomStateChange.RoomCommitted(room, "room", RoomType.TEXT_CHANNEL), h.seen[0])
        val before = h.flagSnapshot()

        // Engine reverify up-cascade promotes the flag; the change re-triggers the fold.
        h.messageRepo.updateAncestryComplete(add.messageId, true)
        h.triggerReverify(add.messageId, room)
        runCurrent()

        assertEquals(
            MemberCell(RoomMemberRole.MEMBER, RoomMemberStatus.ACTIVE),
            h.roomRepo.members[room to ROutsider],
        )
        assertEquals(2, h.seen.size)
        assertEquals(RoomStateChange.RoomCommitted(room, "room", RoomType.TEXT_CHANNEL), h.seen[0])
        assertEquals(RoomStateChange.MemberAdded(room, ROutsider), h.seen[1])
        // Only the engine's own promotion changed; the projector wrote nothing.
        val after = h.flagSnapshot()
        assertEquals(before + (add.messageId to (VerificationState.VERIFIED to true)), after)
    }

    @Test
    fun removal_flips_status_and_emits() = runTest {
        val h = Harness()
        val room = RoomId(Uuid.random())
        h.roomRepo.ensureRoomExists(room, RoomType.UNKNOWN, "")
        h.known(ROwner, RAdmin, RMember)
        val g = h.roomEvent(room, ROwner, emptyList(), roomCreated(members = listOf(RAdmin, RMember)))
        h.store(g)
        val grant = h.roomEvent(room, ROwner, listOf(g.messageId), RoomEventPayload.AddAdmin(RAdmin))
        h.store(grant)
        h.startIn(backgroundScope)
        runCurrent()
        h.seen.clear()

        val remove = h.roomEvent(
            room, ROwner, listOf(g.messageId, grant.messageId),
            RoomEventPayload.MemberRemove(RMember, null),
        )
        h.store(remove)
        h.triggerIngest(remove)
        runCurrent()

        // Rows retained on removal: the REMOVED row carries the defining removal
        // node (the removal boundary for sync serve and display).
        assertEquals(
            MemberCell(RoomMemberRole.MEMBER, RoomMemberStatus.REMOVED, remove.messageId),
            h.roomRepo.members[room to RMember],
        )
        assertEquals(1, h.seen.size)
        assertEquals(RoomStateChange.MemberRemoved(room, RMember), h.seen[0])
    }

    @Test
    fun readd_reactivates_and_emits() = runTest {
        val h = Harness()
        val room = RoomId(Uuid.random())
        h.roomRepo.ensureRoomExists(room, RoomType.UNKNOWN, "")
        h.known(ROwner, RMember)
        val g = h.roomEvent(room, ROwner, emptyList(), roomCreated(members = listOf(RMember)))
        h.store(g)
        val remove = h.roomEvent(room, ROwner, listOf(g.messageId), RoomEventPayload.MemberRemove(RMember, null))
        h.store(remove)
        h.startIn(backgroundScope)
        runCurrent()
        assertEquals(
            MemberCell(RoomMemberRole.MEMBER, RoomMemberStatus.REMOVED, remove.messageId),
            h.roomRepo.members[room to RMember],
        )
        h.seen.clear()

        val readd = h.roomEvent(
            room, ROwner, listOf(g.messageId, remove.messageId),
            RoomEventPayload.MemberAdd(RMember),
        )
        h.store(readd)
        h.triggerIngest(readd)
        runCurrent()

        assertEquals(
            MemberCell(RoomMemberRole.MEMBER, RoomMemberStatus.ACTIVE),
            h.roomRepo.members[room to RMember],
        )
        assertEquals(1, h.seen.size)
        assertEquals(RoomStateChange.MemberAdded(room, RMember), h.seen[0])
    }

    @Test
    fun handover_emits_removal_and_owner_role() = runTest {
        val h = Harness()
        val room = RoomId(Uuid.random())
        h.roomRepo.ensureRoomExists(room, RoomType.UNKNOWN, "")
        h.known(ROwner, RAdmin)
        val g = h.roomEvent(room, ROwner, emptyList(), roomCreated(members = listOf(RAdmin)))
        h.store(g)
        h.startIn(backgroundScope)
        runCurrent()
        h.seen.clear()

        val handover = h.roomEvent(
            room, ROwner, listOf(g.messageId),
            RoomEventPayload.MemberRemove(ROwner, RAdmin),
        )
        h.store(handover)
        h.triggerIngest(handover)
        runCurrent()

        // Never OWNER on a removed row; the successor is admin + owner atomically.
        // The leaver's REMOVED row carries the handover node as its boundary.
        assertEquals(
            MemberCell(RoomMemberRole.MEMBER, RoomMemberStatus.REMOVED, handover.messageId),
            h.roomRepo.members[room to ROwner],
        )
        assertEquals(
            MemberCell(RoomMemberRole.OWNER, RoomMemberStatus.ACTIVE),
            h.roomRepo.members[room to RAdmin],
        )
        assertEquals(2, h.seen.size)
        assertEquals(RoomStateChange.MemberRemoved(room, ROwner), h.seen[0])
        assertEquals(RoomStateChange.MemberRoleChanged(room, RAdmin, RoomMemberRole.OWNER), h.seen[1])
    }

    @Test
    fun deferred_rows_land_on_global_commit() = runTest {
        val h = Harness()
        val room = RoomId(Uuid.random())
        h.roomRepo.ensureRoomExists(room, RoomType.UNKNOWN, "")
        // RMember unknown: the fold counts them, the commit defers the row (FK stays).
        h.known(ROwner, RAdmin)
        val g = h.roomEvent(room, ROwner, emptyList(), roomCreated(members = listOf(RAdmin, RMember)))
        h.store(g)
        h.startIn(backgroundScope)
        runCurrent()

        assertNull(h.roomRepo.members[room to RMember])
        assertEquals(1, h.seen.size)
        assertEquals(RoomStateChange.RoomCommitted(room, "room", RoomType.TEXT_CHANNEL), h.seen[0])

        // GLOBAL lands the identity; the commit trigger re-folds and the row lands.
        h.known(RMember)
        h.triggerGlobal(RMember)
        runCurrent()

        assertEquals(
            MemberCell(RoomMemberRole.MEMBER, RoomMemberStatus.ACTIVE),
            h.roomRepo.members[room to RMember],
        )
        assertEquals(2, h.seen.size)
        assertEquals(RoomStateChange.RoomCommitted(room, "room", RoomType.TEXT_CHANNEL), h.seen[0])
        assertEquals(RoomStateChange.MemberAdded(room, RMember), h.seen[1])
    }

    @Test
    fun ingest_trigger_folds_only_that_room() = runTest {
        val h = Harness()
        val roomA = RoomId(Uuid.random())
        val roomB = RoomId(Uuid.random())
        for (room in listOf(roomA, roomB)) {
            h.roomRepo.ensureRoomExists(room, RoomType.UNKNOWN, "")
            h.known(ROwner, RMember)
            h.store(h.roomEvent(room, ROwner, emptyList(), roomCreated(members = listOf(RMember))))
        }
        h.startIn(backgroundScope)
        runCurrent()
        h.seen.clear()

        h.known(ROutsider)
        // Chain the new event onto room A's genesis.
        val genesisA = h.messageRepo.findFoldableInRoom(roomA).single { it.payload.prevIds.isEmpty() }
        val addA = h.roomEvent(
            roomA, ROwner, listOf(genesisA.payload.messageId),
            RoomEventPayload.MemberAdd(ROutsider),
        )
        h.store(addA)
        h.triggerIngest(addA)
        runCurrent()

        assertEquals(1, h.seen.size)
        assertEquals(RoomStateChange.MemberAdded(roomA, ROutsider), h.seen[0])
        assertNull(h.roomRepo.members[roomB to ROutsider])
    }

    @Test
    fun global_room_triggers_are_ignored() = runTest {
        val h = Harness()
        h.startIn(backgroundScope)
        runCurrent()
        h.seen.clear()

        val global = h.roomEvent(RoomId.GLOBAL, ROwner, emptyList(), roomCreated())
        h.triggerIngest(global)
        h.triggerReverify(global.messageId, RoomId.GLOBAL)
        runCurrent()

        assertTrue(h.seen.isEmpty())
    }
}
