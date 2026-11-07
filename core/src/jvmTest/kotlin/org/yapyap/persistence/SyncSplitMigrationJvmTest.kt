package org.yapyap.persistence

import kotlinx.coroutines.test.runTest
import org.yapyap.persistence.db.DatabaseFactory
import org.yapyap.persistence.db.DriverFactory
import org.yapyap.persistence.db.readPragmaUserVersion
import org.yapyap.orchestrator.store.DefaultPendingSyncRepository
import org.yapyap.routing.store.DefaultSyncAttemptStore
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.RoomId
import org.yapyap.testfixtures.epochSeconds
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

/**
 * Upgrade path for the sync split (3.sqm): a version-3 store's pending syncs keep what is
 * missing and who may hold it, and their attempt state (count, next time, attempted devices)
 * moves to the router's sync_attempts.
 */
class SyncSplitMigrationJvmTest {

    private fun factory(path: String) = DatabaseFactory(object : DriverFactory {
        override fun createDriver() = SingleConnectionJdbcDriver("jdbc:sqlite:file:$path?foreign_keys=on")
    })

    @Test
    fun versionThreeStore_splitsSyncState() = runTest {
        val path = Files.createTempDirectory("yapyap-migration").resolve("v.db").toString().replace('\\', '/')
        val syncId = Uuid.random()
        val roomId = RoomId(Uuid.random())
        val target = Uuid.random()
        val candidate = AccountId("candidate-account")
        val declined = PeerId("declined-device")

        factory(path).createConnection().let { connection ->
            val driver = connection.driver
            revertToVersionThree(driver)
            fun exec(sql: String) = driver.execute(null, sql, 0)
            exec("INSERT INTO rooms(room_id, type, name) VALUES ('${roomId.value.toHexString()}', 'CHAT', 'r')")
            exec(
                "INSERT INTO pending_syncs(sync_id, room_id, target_message_id, attempts, next_attempt_at) " +
                        "VALUES ('${syncId.toHexString()}', '${roomId.value.toHexString()}', '${target.toHexString()}', 3, 5000)",
            )
            exec("INSERT INTO pending_sync_candidate_accounts(sync_id, account_id) VALUES ('${syncId.toHexString()}', '${candidate.id}')")
            exec("INSERT INTO pending_sync_attempted_peers(sync_id, device_id) VALUES ('${syncId.toHexString()}', '${declined.id}')")
            driver.close()
        }

        val connection = factory(path).createConnection()
        try {
            assertEquals(YapYapDatabase.Schema.version, readPragmaUserVersion(connection.driver))
            val pending = DefaultPendingSyncRepository(connection.database)
            val attempts = DefaultSyncAttemptStore(connection.database)

            assertEquals(listOf(candidate), pending.findSyncByTarget(roomId, target)?.candidateAccounts)
            val row = attempts.findDue(epochSeconds(5_000L), limit = 10).single()
            assertEquals(syncId, row.syncId)
            assertEquals(3, row.attempts)
            assertEquals(setOf(declined), row.declinedDevices)
        } finally {
            connection.driver.close()
        }
    }
}
