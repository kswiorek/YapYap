package org.yapyap.orchestrator.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.yapyap.orchestrator.OrchestratorConfig
import org.yapyap.orchestrator.dag.VerificationState
import org.yapyap.orchestrator.file.DefaultFileRequestSource
import org.yapyap.orchestrator.identity.DefaultIdentityStore
import org.yapyap.orchestrator.store.DefaultMessageRepository
import org.yapyap.orchestrator.store.DefaultRoomRepository
import org.yapyap.persistence.FixtureAccountId
import org.yapyap.persistence.FixtureDevicePeerId
import org.yapyap.persistence.openMemoryDatabase
import org.yapyap.persistence.seedLocalAccountAndDevice
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.AccountRole
import org.yapyap.protocol.DeviceType
import org.yapyap.protocol.FileId
import org.yapyap.protocol.IdentityStatus
import org.yapyap.protocol.MessageId
import org.yapyap.protocol.RoomId
import org.yapyap.protocol.RoomMemberRole
import org.yapyap.protocol.RoomMemberStatus
import org.yapyap.protocol.RoomType
import org.yapyap.protocol.SyncId
import org.yapyap.protocol.envelopes.DagPayload
import org.yapyap.protocol.envelopes.SyncPayload
import org.yapyap.routing.message.FakePendingSyncRepository
import org.yapyap.testfixtures.FakeCoverage
import org.yapyap.testfixtures.FakeDescriptors
import org.yapyap.testfixtures.FakeLocalIdentity
import org.yapyap.testfixtures.FakeReceives
import org.yapyap.testfixtures.epochSeconds
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class RoomVisibilityTest {

    private var connection: org.yapyap.persistence.DatabaseConnection? = null

    @AfterTest
    fun closeDb() {
        connection?.driver?.close()
        connection = null
    }

    private fun textMsg(
        roomId: RoomId,
        sender: AccountId,
        tick: Long,
        parents: List<MessageId> = emptyList(),
        attachments: List<DagPayload.Attachment> = emptyList(),
    ): DagPayload.Text = DagPayload.Text(
        messageId = MessageId(Uuid.random()),
        roomId = roomId,
        senderAccountId = sender,
        authorDeviceId = FixtureDevicePeerId,
        prevIds = parents,
        createdAt = epochSeconds(tick),
        text = "m",
        attachments = attachments,
    )

    private suspend fun DefaultMessageRepository.seedLinked(msg: DagPayload.Text) {
        insert(msg, isOrphaned = false, ancestryComplete = true, verificationState = VerificationState.VERIFIED)
        for (parent in msg.prevIds) insertParent(msg.messageId, parent)
    }

    private suspend fun ensureAccount(account: AccountId) {
        DefaultIdentityStore(connection!!.database, DeviceType.DESKTOP).upsertChainAccount(
            account, null, AccountRole.MEMBER, IdentityStatus.ACTIVE, account.id,
        )
    }

    @Test
    fun activeSeesAll_nonMemberSeesNothing_unknownFalse() = runTest {
        connection = openMemoryDatabase()
        val db = connection!!.database
        val messages = DefaultMessageRepository(db)
        val rooms = DefaultRoomRepository(db)
        val visibility = DefaultRoomVisibility(messages, rooms)
        val room = RoomId(Uuid.random())
        val active = AccountId("visibility-active")
        val stranger = AccountId("visibility-stranger")
        seedLocalAccountAndDevice(db, FixtureAccountId, FixtureDevicePeerId)
        ensureAccount(active)
        rooms.ensureRoomExists(room, RoomType.TEXT_CHANNEL, "v")
        rooms.upsertMember(room, active, RoomMemberRole.MEMBER, RoomMemberStatus.ACTIVE)

        val m0 = textMsg(room, active, 1L)
        messages.seedLinked(m0)

        assertTrue(visibility.isVisible(m0.messageId, active))
        assertFalse(visibility.isVisible(m0.messageId, stranger))
        assertFalse(visibility.isVisible(MessageId(Uuid.random()), active))
        assertFalse(visibility.isVisible(MessageId(Uuid.random()), stranger))
    }

    @Test
    fun removedSeesRemovalAndAncestors_notAfter() = runTest {
        connection = openMemoryDatabase()
        val db = connection!!.database
        val messages = DefaultMessageRepository(db)
        val rooms = DefaultRoomRepository(db)
        val visibility = DefaultRoomVisibility(messages, rooms)
        val room = RoomId(Uuid.random())
        val removed = AccountId("visibility-removed")
        seedLocalAccountAndDevice(db, FixtureAccountId, FixtureDevicePeerId)
        ensureAccount(removed)
        rooms.ensureRoomExists(room, RoomType.TEXT_CHANNEL, "v")

        val m0 = textMsg(room, removed, 1L)
        messages.seedLinked(m0)
        val m1 = textMsg(room, removed, 2L, listOf(m0.messageId))
        messages.seedLinked(m1)
        val removal = textMsg(room, removed, 3L, listOf(m1.messageId))
        messages.seedLinked(removal)
        rooms.upsertMember(room, removed, RoomMemberRole.MEMBER, RoomMemberStatus.REMOVED, removal.messageId)
        val after = textMsg(room, removed, 4L, listOf(removal.messageId))
        messages.seedLinked(after)

        assertTrue(visibility.isVisible(m0.messageId, removed))
        assertTrue(visibility.isVisible(m1.messageId, removed))
        assertTrue(visibility.isVisible(removal.messageId, removed))
        assertFalse(visibility.isVisible(after.messageId, removed))
    }

    @Test
    fun scope_decidesOncePerRoom() = runTest {
        connection = openMemoryDatabase()
        val db = connection!!.database
        val messages = DefaultMessageRepository(db)
        val rooms = DefaultRoomRepository(db)
        val visibility = DefaultRoomVisibility(messages, rooms)
        val room = RoomId(Uuid.random())
        val active = AccountId("scope-active")
        val removed = AccountId("scope-removed")
        val stranger = AccountId("scope-stranger")
        seedLocalAccountAndDevice(db, FixtureAccountId, FixtureDevicePeerId)
        ensureAccount(active)
        ensureAccount(removed)
        rooms.ensureRoomExists(room, RoomType.TEXT_CHANNEL, "v")
        rooms.upsertMember(room, active, RoomMemberRole.MEMBER, RoomMemberStatus.ACTIVE)
        val removal = textMsg(room, removed, 1L)
        messages.seedLinked(removal)
        rooms.upsertMember(room, removed, RoomMemberRole.MEMBER, RoomMemberStatus.REMOVED, removal.messageId)

        assertEquals(VisibilityScope.All, visibility.scope(room, active))
        assertEquals(VisibilityScope.UpTo(removal.messageId), visibility.scope(room, removed))
        assertEquals(VisibilityScope.None, visibility.scope(room, stranger))
    }

    @Test
    fun chain1000_removedSeesFirst() = runTest {
        connection = openMemoryDatabase()
        val db = connection!!.database
        val messages = DefaultMessageRepository(db)
        val rooms = DefaultRoomRepository(db)
        val visibility = DefaultRoomVisibility(messages, rooms)
        val room = RoomId(Uuid.random())
        val removed = AccountId("visibility-removed-1000")
        seedLocalAccountAndDevice(db, FixtureAccountId, FixtureDevicePeerId)
        ensureAccount(removed)
        rooms.ensureRoomExists(room, RoomType.TEXT_CHANNEL, "v")

        var prev: MessageId? = null
        var first: MessageId? = null
        repeat(1000) { i ->
            val msg = textMsg(room, removed, i.toLong(), prev?.let { listOf(it) } ?: emptyList())
            messages.seedLinked(msg)
            if (i == 0) first = msg.messageId
            prev = msg.messageId
        }
        val removal = textMsg(room, removed, 1000L, listOf(requireNotNull(prev)))
        messages.seedLinked(removal)
        rooms.upsertMember(room, removed, RoomMemberRole.MEMBER, RoomMemberStatus.REMOVED, removal.messageId)

        val firstId = requireNotNull(first)
        assertTrue(visibility.isVisible(firstId, removed))
        assertTrue(visibility.isVisible(removal.messageId, removed))
        assertTrue(messages.isAncestorOrSelf(firstId, removal.messageId))
    }

    @Test
    fun parity_syncServingAndServeGateAgree() = runTest {
        connection = openMemoryDatabase()
        val db = connection!!.database
        val messages = DefaultMessageRepository(db)
        val rooms = DefaultRoomRepository(db)
        val visibility = DefaultRoomVisibility(messages, rooms)
        val room = RoomId(Uuid.random())
        val active = AccountId("parity-active")
        val removed = AccountId("parity-removed")
        val stranger = AccountId("parity-stranger")
        seedLocalAccountAndDevice(db, FixtureAccountId, FixtureDevicePeerId)
        ensureAccount(active)
        ensureAccount(removed)
        rooms.ensureRoomExists(room, RoomType.TEXT_CHANNEL, "v")
        rooms.upsertMember(room, active, RoomMemberRole.MEMBER, RoomMemberStatus.ACTIVE)

        val m0 = textMsg(room, active, 1L)
        messages.seedLinked(m0)
        val m1 = textMsg(room, active, 2L, listOf(m0.messageId))
        messages.seedLinked(m1)
        val removal = textMsg(room, removed, 3L, listOf(m1.messageId))
        messages.seedLinked(removal)
        rooms.upsertMember(room, removed, RoomMemberRole.MEMBER, RoomMemberStatus.REMOVED, removal.messageId)
        val after = textMsg(room, removed, 4L, listOf(removal.messageId))
        messages.seedLinked(after)

        val provider = DefaultSyncPayloadProvider(
            messages,
            FakePendingSyncRepository(),
            MutableStateFlow(OrchestratorConfig()),
            visibility,
        )
        val descriptors = FakeDescriptors()
        val source = DefaultFileRequestSource(
            messages = messages,
            visibility = visibility,
            receives = FakeReceives(),
            descriptors = descriptors,
            localIdentity = FakeLocalIdentity(active),
            coverage = FakeCoverage(),
        )

        fun syncReq(missing: List<MessageId>): SyncPayload.Request = SyncPayload.Request(
            roomId = room,
            syncId = SyncId(Uuid.random()),
            missingIds = missing,
            knownIds = emptyList(),
        )

        val cases =
            listOf(m0.messageId to true, m1.messageId to true, removal.messageId to true, after.messageId to false)
        for ((msgId, removedVisible) in cases) {
            // Sync parity: served iff visible.
            val servedActive = provider.serveSync(syncReq(listOf(msgId)), active).any { it.messageId == msgId }
            assertTrue(servedActive, "active should be served $msgId")
            val servedRemoved = provider.serveSync(syncReq(listOf(msgId)), removed).any { it.messageId == msgId }
            assertTrue(
                servedRemoved == removedVisible,
                "removed visibility mismatch for $msgId",
            )
            val servedStranger = provider.serveSync(syncReq(listOf(msgId)), stranger)
            assertTrue(servedStranger.isEmpty(), "stranger sees nothing")

            // Serve-gate parity: one file per message, HELD, referenced only by that message.
            val fileId = FileId(Uuid.random().toHexString().padEnd(64, '0').take(64))
            db.messageQueries.insertMessageAttachment(msgId, fileId)
            descriptors.held += fileId
            val gateActive = source.canServe(fileId, active)
            assertTrue(gateActive, "serve gate active $msgId")
            val gateRemoved = source.canServe(fileId, removed)
            assertEquals(removedVisible, gateRemoved, "serve gate removed $msgId")
            assertFalse(source.canServe(fileId, stranger), "serve gate stranger $msgId")
        }
        // Author included: the author of a referencing message may serve it.
        val authorFile = FileId(Uuid.random().toHexString().padEnd(64, '0').take(64))
        db.messageQueries.insertMessageAttachment(m0.messageId, authorFile)
        descriptors.held += authorFile
        assertTrue(source.canServe(authorFile, active))
    }

}
