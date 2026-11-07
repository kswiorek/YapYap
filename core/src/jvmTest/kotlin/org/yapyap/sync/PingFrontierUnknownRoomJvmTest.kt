package org.yapyap.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.yapyap.orchestrator.OrchestratorConfig
import org.yapyap.orchestrator.sync.DefaultSyncCoordinator
import org.yapyap.persistence.db.DatabaseConnection
import org.yapyap.persistence.db.openMemoryDatabase
import org.yapyap.persistence.messaging.DefaultMessageRepository
import org.yapyap.persistence.messaging.DefaultRoomRepository
import org.yapyap.persistence.sync.DefaultPendingSyncRepository
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.RoomId
import org.yapyap.protocol.RoomType
import org.yapyap.testfixtures.FakeClock
import org.yapyap.testfixtures.epochSeconds
import kotlin.test.*
import kotlin.uuid.Uuid

/**
 * A device added to an account that already belongs to rooms knows those rooms only from
 * the frontiers its own account's other devices ping: the sync row for such a room must be
 * mintable against real SQLite, whose `pending_syncs.room_id` references `rooms`.
 */
class PingFrontierUnknownRoomJvmTest {

    private var connection: DatabaseConnection? = null

    @AfterTest
    fun closeDb() {
        connection?.driver?.close()
        connection = null
    }

    private val ownAccount = AccountId("own-account")

    private fun coordinator(): Pair<DefaultSyncCoordinator, Pair<DefaultRoomRepository, DefaultPendingSyncRepository>> {
        connection = openMemoryDatabase()
        val db = connection!!.database
        val rooms = DefaultRoomRepository(db)
        val syncs = DefaultPendingSyncRepository(db)
        val coordinator = DefaultSyncCoordinator(
            pipeline = FakeInboundMessagePipeline(),
            roomRepository = rooms,
            messageRepository = DefaultMessageRepository(db),
            pendingSyncRepository = syncs,
            router = RecordingSyncRouter(),
            clock = FakeClock(epochSeconds(1_000L)),
            orchestratorConfig = MutableStateFlow(OrchestratorConfig()),
        )
        return coordinator to (rooms to syncs)
    }

    @Test
    fun pingedFrontierOfAnUnknownRoom_mintsAPlaceholderRoomAndASyncRow() = runTest {
        val (coordinator, repos) = coordinator()
        val (rooms, syncs) = repos
        val room = RoomId(Uuid.random())
        val tip = Uuid.random()

        coordinator.requestFrontierSync(room, listOf(tip), senderAccount = ownAccount)

        assertEquals(RoomType.UNKNOWN, assertNotNull(rooms.roomOf(room)).type)
        assertNotNull(syncs.findSyncByTarget(room, tip), "the sync row exists, chasing the tip")
    }

    @Test
    fun unknownRoomWithoutSender_stillMintsNothing() = runTest {
        val (coordinator, repos) = coordinator()
        val (rooms, _) = repos
        val room = RoomId(Uuid.random())

        coordinator.requestFrontierSync(room, listOf(Uuid.random()), senderAccount = null)

        assertNull(rooms.roomOf(room), "a row that could never be sent leaves no placeholder either")
    }
}
