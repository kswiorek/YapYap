package org.yapyap.persistence

import kotlinx.coroutines.test.runTest
import org.yapyap.crypto.identity.AccountId
import org.yapyap.persistence.db.*
import org.yapyap.persistence.key.DefaultIdentityKeyRepository
import org.yapyap.persistence.messaging.DefaultMessageRepository
import org.yapyap.persistence.messaging.DefaultRoomRepository
import org.yapyap.persistence.messaging.RoomMemberRecord
import org.yapyap.protocol.DeviceType
import org.yapyap.protocol.RoomId
import org.yapyap.protocol.RoomType
import org.yapyap.protocol.envelopes.MessagePayload
import org.yapyap.testfixtures.epochSeconds
import kotlin.test.*
import kotlin.uuid.Uuid

/**
 * SQL contract for the room fold's storage boundary (docs/room events.md §4–§5):
 * the foldable filter is exactly `VERIFIED` ∧ stored flag, the chat-room sweep
 * excludes GLOBAL, the genesis merge is a targeted update, and the member-table
 * FKs are enforced — which is what makes projection deferral load-bearing.
 */
class RoomFoldQueriesJvmTest {

    private var connection: DatabaseConnection? = null

    @AfterTest
    fun closeDb() {
        connection?.driver?.close()
        connection = null
    }

    private fun textIn(roomId: RoomId, tick: Long): MessagePayload.Text =
        MessagePayload.Text(
            messageId = Uuid.random(),
            roomId = roomId,
            senderAccountId = FixtureAccountId,
            authorDeviceId = FixtureDevicePeerId,
            prevIds = emptyList(),
            createdAt = epochSeconds(tick),
            text = "hi",
        )

    @Test
    fun findFoldableInRoom_returns_verified_complete_only() = runTest {
        connection = openMemoryDatabase()
        val db = connection!!.database
        val messages = DefaultMessageRepository(db)
        val rooms = DefaultRoomRepository(db)
        val room = RoomId(Uuid.random())
        rooms.ensureRoomExists(room, RoomType.TEXT_CHANNEL, "foldable")

        val good = textIn(room, 1L)
        messages.insert(
            good,
            isOrphaned = false,
            ancestryComplete = true,
            verificationState = VerificationState.VERIFIED
        )
        val incomplete = textIn(room, 2L)
        messages.insert(
            incomplete,
            isOrphaned = false,
            ancestryComplete = false,
            verificationState = VerificationState.VERIFIED
        )
        val pending = textIn(room, 3L)
        messages.insert(
            pending,
            isOrphaned = false,
            ancestryComplete = true,
            verificationState = VerificationState.PENDING
        )
        val rejected = textIn(room, 4L)
        messages.insert(
            rejected,
            isOrphaned = false,
            ancestryComplete = true,
            verificationState = VerificationState.REJECTED
        )
        val otherRoom = textIn(RoomId(Uuid.random()), 5L)
        // Note: otherRoom's room row must exist for the messages FK.
        rooms.ensureRoomExists(otherRoom.roomId, RoomType.TEXT_CHANNEL, "other")
        messages.insert(
            otherRoom,
            isOrphaned = false,
            ancestryComplete = true,
            verificationState = VerificationState.VERIFIED
        )

        val foldable = messages.findFoldableInRoom(room)
        assertEquals(listOf(good.messageId), foldable.map { it.payload.messageId })
    }

    @Test
    fun allChatRoomIds_excludes_global() = runTest {
        connection = openMemoryDatabase()
        val db = connection!!.database
        val rooms = DefaultRoomRepository(db)
        rooms.ensureRoomExists(RoomId.GLOBAL, RoomType.GLOBAL_CONTROL, "global")
        val chatA = RoomId(Uuid.random())
        val chatB = RoomId(Uuid.random())
        rooms.ensureRoomExists(chatA, RoomType.TEXT_CHANNEL, "a")
        rooms.ensureRoomExists(chatB, RoomType.TEXT_CHANNEL, "b")

        assertEquals(setOf(chatA, chatB), rooms.allChatRoomIds().toSet())
    }

    @Test
    fun mergeRoomFromGenesis_overwrites_provisional_row() = runTest {
        connection = openMemoryDatabase()
        val db = connection!!.database
        val rooms = DefaultRoomRepository(db)
        val room = RoomId(Uuid.random())
        rooms.ensureRoomExists(room, RoomType.UNKNOWN, "")

        rooms.mergeRoomFromGenesis(room, "real name", RoomType.TEXT_CHANNEL, spaceId = null)

        val row = db.roomQueries.selectRoomById(room).executeAsOne()
        assertEquals("real name", row.name)
        assertEquals(RoomType.TEXT_CHANNEL, row.type)
        assertNull(row.space_id)
        // Re-merge is idempotent; a null space preserves the local value.
        rooms.mergeRoomFromGenesis(room, "real name", RoomType.TEXT_CHANNEL, spaceId = null)
        assertEquals("real name", db.roomQueries.selectRoomById(room).executeAsOne().name)
    }

    @Test
    fun mergeRoomFromGenesis_defers_unknown_space_without_throwing() = runTest {
        connection = openMemoryDatabase()
        val db = connection!!.database
        val rooms = DefaultRoomRepository(db)
        val room = RoomId(Uuid.random())
        rooms.ensureRoomExists(room, RoomType.UNKNOWN, "")

        // No spaces row exists: the space write defers (FK), the name/type merge lands.
        rooms.mergeRoomFromGenesis(room, "spaced", RoomType.TEXT_CHANNEL, spaceId = "no-such-space")

        val row = db.roomQueries.selectRoomById(room).executeAsOne()
        assertEquals("spaced", row.name)
        assertNull(row.space_id)
    }

    @Test
    fun removeRoomMembersNotIn_converges_to_keep_set() = runTest {
        connection = openMemoryDatabase()
        val db = connection!!.database
        seedLocalAccountAndDevice(db, FixtureAccountId, FixtureDevicePeerId)
        val rooms = DefaultRoomRepository(db)
        val room = RoomId(Uuid.random())
        rooms.ensureRoomExists(room, RoomType.TEXT_CHANNEL, "members")
        val gone = AccountId("gone-account")
        DefaultIdentityKeyRepository(db, DeviceType.DESKTOP).upsertChainAccount(
            gone, null, AccountRole.MEMBER, IdentityStatus.ACTIVE, "gone",
        )
        rooms.upsertMember(room, FixtureAccountId, RoomMemberRole.MEMBER)
        rooms.upsertMember(room, gone, RoomMemberRole.MEMBER)

        rooms.removeRoomMembersNotIn(room, listOf(FixtureAccountId))
        assertEquals(listOf(FixtureAccountId), rooms.membersOfRoom(room))

        // Empty keep set takes the delete-all path (NOT IN () would be invalid SQL).
        rooms.removeRoomMembersNotIn(room, emptyList())
        assertEquals(emptyList(), rooms.membersOfRoom(room))
    }

    @Test
    fun member_insert_for_unknown_account_fails_fk() = runTest {
        connection = openMemoryDatabase()
        val db = connection!!.database
        val rooms = DefaultRoomRepository(db)
        val room = RoomId(Uuid.random())
        rooms.ensureRoomExists(room, RoomType.TEXT_CHANNEL, "fk")

        // Negative test (d3): the account_id FK is enforced, so the projector must
        // defer rows for accounts not yet in `accounts` — inserting blind would crash.
        val failure = assertFailsWith<Exception> {
            rooms.upsertMember(room, AccountId("never-synced"), RoomMemberRole.MEMBER, RoomMemberStatus.ACTIVE)
        }
        assertTrue(
            failure.message?.contains("FOREIGN", ignoreCase = true) == true,
            "expected an FK violation, got: ${failure.message}",
        )
        assertNull(
            db.roomQueries.selectAllMembersForRoom(room).executeAsList()
                .firstOrNull { it.account_id == AccountId("never-synced") },
        )
    }

    @Test
    fun membersOfRoom_returns_active_only() = runTest {
        connection = openMemoryDatabase()
        val db = connection!!.database
        seedLocalAccountAndDevice(db, FixtureAccountId, FixtureDevicePeerId)
        val rooms = DefaultRoomRepository(db)
        val room = RoomId(Uuid.random())
        rooms.ensureRoomExists(room, RoomType.TEXT_CHANNEL, "active-only")
        val removed = AccountId("removed-account")
        DefaultIdentityKeyRepository(db, DeviceType.DESKTOP).upsertChainAccount(
            removed, null, AccountRole.MEMBER, IdentityStatus.ACTIVE, "removed",
        )
        rooms.upsertMember(room, FixtureAccountId, RoomMemberRole.MEMBER, RoomMemberStatus.ACTIVE)
        rooms.upsertMember(room, removed, RoomMemberRole.ADMIN, RoomMemberStatus.REMOVED)

        // Access read: REMOVED rows never grant access (docs/room events.md §5).
        assertEquals(listOf(FixtureAccountId), rooms.membersOfRoom(room))
    }

    @Test
    fun memberStatusesOfRoom_returns_all_rows_with_status() = runTest {
        connection = openMemoryDatabase()
        val db = connection!!.database
        seedLocalAccountAndDevice(db, FixtureAccountId, FixtureDevicePeerId)
        val rooms = DefaultRoomRepository(db)
        val room = RoomId(Uuid.random())
        rooms.ensureRoomExists(room, RoomType.TEXT_CHANNEL, "statuses")
        val removed = AccountId("removed-account")
        DefaultIdentityKeyRepository(db, DeviceType.DESKTOP).upsertChainAccount(
            removed, null, AccountRole.MEMBER, IdentityStatus.ACTIVE, "removed",
        )
        rooms.upsertMember(room, FixtureAccountId, RoomMemberRole.MEMBER, RoomMemberStatus.ACTIVE)
        rooms.upsertMember(room, removed, RoomMemberRole.ADMIN, RoomMemberStatus.REMOVED)

        // GUI read: every committed row, with the status the GUI reads for the
        // member list and removal banner (docs/room events.md §3) — including REMOVED rows.
        val rows = rooms.memberStatusesOfRoom(room).associateBy { it.accountId }
        assertEquals(
            RoomMemberRecord(FixtureAccountId, RoomMemberRole.MEMBER, RoomMemberStatus.ACTIVE),
            rows[FixtureAccountId],
        )
        assertEquals(
            RoomMemberRecord(removed, RoomMemberRole.ADMIN, RoomMemberStatus.REMOVED),
            rows[removed],
        )
    }
}
