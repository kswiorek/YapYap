package org.yapyap.persistence

import kotlinx.coroutines.test.runTest
import org.yapyap.crypto.identity.AccountId
import org.yapyap.persistence.db.*
import org.yapyap.persistence.key.DefaultIdentityKeyRepository
import org.yapyap.persistence.messaging.DefaultMessageRepository
import org.yapyap.persistence.messaging.DefaultRoomRepository
import org.yapyap.protocol.DeviceType
import org.yapyap.protocol.RoomId
import org.yapyap.protocol.RoomType
import org.yapyap.protocol.envelopes.MessagePayload
import org.yapyap.testfixtures.epochSeconds
import kotlin.test.*
import kotlin.uuid.Uuid

/**
 * SQL contract for the removal-boundary display policy (docs/room events.md §3):
 * the renderable page query and the single-message render check share one
 * recursive CTE over `message_parents` joined against `room_members` — ACTIVE
 * authors render, REMOVED authors render only inside their removal node's
 * ancestor closure, never-members never render. The fakes mirror this predicate;
 * these tests pin the real SQL (CTE shape, the `IS NOT NULL` guard, the verdict
 * filter, hole-free pagination).
 */
class RenderableQueriesJvmTest {

    private var connection: DatabaseConnection? = null

    @AfterTest
    fun closeDb() {
        connection?.driver?.close()
        connection = null
    }

    private fun textIn(
        roomId: RoomId,
        sender: AccountId,
        tick: Long,
        parents: List<Uuid> = emptyList(),
    ): MessagePayload.Text =
        MessagePayload.Text(
            messageId = Uuid.random(),
            roomId = roomId,
            senderAccountId = sender,
            authorDeviceId = FixtureDevicePeerId,
            prevIds = parents,
            createdAt = epochSeconds(tick),
            text = "hi",
        )

    private suspend fun DefaultMessageRepository.seedLinked(msg: MessagePayload.Text) {
        insert(
            msg,
            isOrphaned = false,
            ancestryComplete = true,
            verificationState = VerificationState.VERIFIED,
        )
        for (parent in msg.prevIds) insertParent(msg.messageId, parent)
    }

    /** Room with an ACTIVE fixture member and a REMOVED member; returns the removal node id. */
    private suspend fun seedRemovalBoundary(
        messages: DefaultMessageRepository,
        rooms: DefaultRoomRepository,
        room: RoomId,
        removed: AccountId,
        eraParents: List<Uuid>,
    ): Uuid {
        seedLocalAccountAndDevice(connection!!.database, FixtureAccountId, FixtureDevicePeerId)
        DefaultIdentityKeyRepository(connection!!.database, DeviceType.DESKTOP).upsertChainAccount(
            removed, null, false, IdentityStatus.ACTIVE, "removed",
        )
        rooms.ensureRoomExists(room, RoomType.TEXT_CHANNEL, "boundary")
        rooms.upsertMember(room, FixtureAccountId, RoomMemberRole.MEMBER, RoomMemberStatus.ACTIVE)
        // The removal node is a plain Text stand-in here: the query only reads ids
        // and edges (in production it is a RoomEvent, filtered later by display type).
        val removal = textIn(room, removed, 2L, eraParents)
        messages.seedLinked(removal)
        rooms.upsertMember(room, removed, RoomMemberRole.MEMBER, RoomMemberStatus.REMOVED, removal.messageId)
        return removal.messageId
    }

    @Test
    fun renderablePage_activeAuthor_renders_newestFirst() = runTest {
        connection = openMemoryDatabase()
        val db = connection!!.database
        val messages = DefaultMessageRepository(db)
        val rooms = DefaultRoomRepository(db)
        val room = RoomId(Uuid.random())
        seedLocalAccountAndDevice(db, FixtureAccountId, FixtureDevicePeerId)
        rooms.ensureRoomExists(room, RoomType.TEXT_CHANNEL, "active")
        rooms.upsertMember(room, FixtureAccountId, RoomMemberRole.MEMBER, RoomMemberStatus.ACTIVE)

        val old = textIn(room, FixtureAccountId, 1L)
        val new = textIn(room, FixtureAccountId, 2L, listOf(old.messageId))
        messages.seedLinked(old)
        messages.seedLinked(new)

        val page = messages.renderableMessagesInRoom(room, 10)
        assertEquals(listOf(new.messageId, old.messageId), page.map { it.payload.messageId })
    }

    @Test
    fun renderablePage_removedAuthor_inClosure_renders_outOfClosure_hidden() = runTest {
        connection = openMemoryDatabase()
        val db = connection!!.database
        val messages = DefaultMessageRepository(db)
        val rooms = DefaultRoomRepository(db)
        val room = RoomId(Uuid.random())
        val removed = AccountId("removed-account")
        // The room row must exist before any message row (messages FK).
        rooms.ensureRoomExists(room, RoomType.TEXT_CHANNEL, "boundary")
        val era = textIn(room, removed, 1L)
        messages.seedLinked(era)
        val removal = seedRemovalBoundary(messages, rooms, room, removed, listOf(era.messageId))
        // Provably post-removal (descends from the removal node).
        val post = textIn(room, removed, 3L, listOf(removal))
        messages.seedLinked(post)
        // Backdated forgery (chained onto pre-removal history, concurrent with the removal).
        val backdated = textIn(room, removed, 4L, listOf(era.messageId))
        messages.seedLinked(backdated)

        val page = messages.renderableMessagesInRoom(room, 10).map { it.payload.messageId }

        // Member-era history renders (newest first); post-removal and backdated hide.
        assertEquals(listOf(removal, era.messageId), page)
        assertTrue(post.messageId !in page)
        assertTrue(backdated.messageId !in page)
    }

    @Test
    fun renderablePage_hiddenRows_doNotConsumePageBudget() = runTest {
        connection = openMemoryDatabase()
        val db = connection!!.database
        val messages = DefaultMessageRepository(db)
        val rooms = DefaultRoomRepository(db)
        val room = RoomId(Uuid.random())
        val removed = AccountId("removed-account")
        rooms.ensureRoomExists(room, RoomType.TEXT_CHANNEL, "holes")
        val era = textIn(room, removed, 1L)
        messages.seedLinked(era)
        val removal = seedRemovalBoundary(messages, rooms, room, removed, listOf(era.messageId))
        // Hidden rows interleaved newest-first between the two visible rows.
        messages.seedLinked(textIn(room, removed, 3L, listOf(removal)))
        messages.seedLinked(textIn(room, removed, 4L, listOf(era.messageId)))

        // Limit 2 still returns both visible rows — hidden rows are filtered by
        // WHERE, so pagination has no holes.
        val page = messages.renderableMessagesInRoom(room, 2).map { it.payload.messageId }
        assertEquals(listOf(removal, era.messageId), page)
    }

    @Test
    fun renderablePage_neverMember_rejected_nodelessRemoval_hidden() = runTest {
        connection = openMemoryDatabase()
        val db = connection!!.database
        val messages = DefaultMessageRepository(db)
        val rooms = DefaultRoomRepository(db)
        val room = RoomId(Uuid.random())
        val removed = AccountId("removed-account")
        rooms.ensureRoomExists(room, RoomType.TEXT_CHANNEL, "hidden")
        val era = textIn(room, removed, 1L)
        messages.seedLinked(era)
        seedRemovalBoundary(messages, rooms, room, removed, listOf(era.messageId))

        // Never-member (no row): hidden.
        val stranger = textIn(room, AccountId("stranger"), 5L)
        messages.seedLinked(stranger)
        // REJECTED verdict from an ACTIVE author: hidden by the verdict filter.
        val forged = textIn(room, FixtureAccountId, 6L)
        messages.insert(
            forged,
            isOrphaned = false,
            ancestryComplete = true,
            verificationState = VerificationState.REJECTED,
        )
        // REMOVED row without a defining node (should not exist; fail closed).
        val nodeless = AccountId("nodeless-account")
        DefaultIdentityKeyRepository(db, DeviceType.DESKTOP).upsertChainAccount(
            nodeless, null, false, IdentityStatus.ACTIVE, "nodeless",
        )
        rooms.upsertMember(room, nodeless, RoomMemberRole.MEMBER, RoomMemberStatus.REMOVED, null)
        val nodelessMsg = textIn(room, nodeless, 7L)
        messages.seedLinked(nodelessMsg)

        val page = messages.renderableMessagesInRoom(room, 10).map { it.payload.messageId }
        assertTrue(stranger.messageId !in page)
        assertTrue(forged.messageId !in page)
        assertTrue(nodelessMsg.messageId !in page)
        assertTrue(era.messageId in page)
    }

    @Test
    fun isRenderable_matches_page_predicate() = runTest {
        connection = openMemoryDatabase()
        val db = connection!!.database
        val messages = DefaultMessageRepository(db)
        val rooms = DefaultRoomRepository(db)
        val room = RoomId(Uuid.random())
        val removed = AccountId("removed-account")
        rooms.ensureRoomExists(room, RoomType.TEXT_CHANNEL, "renderable")
        val era = textIn(room, removed, 1L)
        messages.seedLinked(era)
        val removal = seedRemovalBoundary(messages, rooms, room, removed, listOf(era.messageId))
        val post = textIn(room, removed, 3L, listOf(removal))
        messages.seedLinked(post)
        val stranger = textIn(room, AccountId("stranger"), 4L)
        messages.seedLinked(stranger)

        assertTrue(messages.isRenderable(room, era.messageId))
        assertFalse(messages.isRenderable(room, post.messageId))
        assertFalse(messages.isRenderable(room, stranger.messageId))
        // Missing id and wrong room fail closed.
        assertFalse(messages.isRenderable(room, Uuid.random()))
        assertFalse(messages.isRenderable(RoomId(Uuid.random()), era.messageId))
    }
}
