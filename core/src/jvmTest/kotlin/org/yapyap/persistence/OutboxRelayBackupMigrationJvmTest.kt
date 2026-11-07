package org.yapyap.persistence

import kotlinx.coroutines.test.runTest
import org.yapyap.persistence.db.DatabaseFactory
import org.yapyap.persistence.db.DriverFactory
import org.yapyap.persistence.db.readPragmaUserVersion
import org.yapyap.routing.store.DefaultPacketOutbox
import org.yapyap.routing.store.RelayBackup
import org.yapyap.protocol.PacketType
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.testfixtures.epochSeconds
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * Turns a store created at today's schema back into a version-3 store: undoes 3.sqm (outbox
 * tags, the sync split, message deliveries) by recreating the version-3 shapes.
 */
internal fun revertToVersionThree(driver: app.cash.sqldelight.db.SqlDriver) {
    revertToVersionFive(driver)
    listOf(
        "DROP INDEX outbox_tag_idx",
        "ALTER TABLE outbox DROP COLUMN tag_kind",
        "ALTER TABLE outbox DROP COLUMN tag_id",
        "DROP TABLE message_deliveries",
        "DROP TABLE sync_declined_devices",
        "DROP TABLE sync_attempts",
        "DROP TABLE pending_sync_candidate_accounts",
        "DROP TABLE pending_syncs",
        """CREATE TABLE pending_syncs(
            sync_id TEXT NOT NULL PRIMARY KEY,
            room_id TEXT NOT NULL,
            target_message_id TEXT NOT NULL,
            attempts INTEGER NOT NULL DEFAULT 0,
            next_attempt_at INTEGER NOT NULL,
            FOREIGN KEY (room_id) REFERENCES rooms(room_id),
            UNIQUE (room_id, target_message_id)
        )""",
        """CREATE TABLE pending_sync_candidate_accounts(
            sync_id TEXT NOT NULL,
            account_id TEXT NOT NULL,
            PRIMARY KEY (sync_id, account_id),
            FOREIGN KEY (sync_id) REFERENCES pending_syncs(sync_id) ON DELETE CASCADE
        )""",
        """CREATE TABLE pending_sync_attempted_peers(
            sync_id TEXT NOT NULL,
            device_id TEXT NOT NULL,
            PRIMARY KEY (sync_id, device_id),
            FOREIGN KEY (sync_id) REFERENCES pending_syncs(sync_id) ON DELETE CASCADE
        )""",
        "CREATE INDEX pending_syncs_room_idx ON pending_syncs(room_id)",
        "CREATE INDEX pending_syncs_due_idx ON pending_syncs(next_attempt_at)",
        "CREATE INDEX pending_sync_attempted_peers_device_idx ON pending_sync_attempted_peers(device_id)",
        "PRAGMA user_version = 3",
    ).forEach { driver.execute(null, it, 0) }
}

/**
 * Upgrade path for the outbox's `relay_backup` column (2.sqm): a store at schema version 2
 * (the outbox without the column, holding a queued row) opens at the current version, keeps
 * its row with no backup owed, and takes new rows with one.
 */
class OutboxRelayBackupMigrationJvmTest {

    private fun factory(path: String) = DatabaseFactory(object : DriverFactory {
        override fun createDriver() = SingleConnectionJdbcDriver("jdbc:sqlite:file:$path?foreign_keys=on")
    })

    private fun envelope() = BinaryEnvelope(
        packetId = Uuid.random(),
        packetType = PacketType.MESSAGE,
        dispositionRequested = true,
        createdAt = epochSeconds(1_000L),
        expiresAt = epochSeconds(100_000L),
        source = PeerId("a".repeat(64)),
        target = PeerId("b".repeat(64)),
        payload = byteArrayOf(1),
    )

    @Test
    fun versionTwoStore_migrates_andKeepsItsQueuedRows() = runTest {
        val path = Files.createTempDirectory("yapyap-migration").resolve("v.db").toString().replace('\\', '/')

        // A version-2 store: today's schema minus the column, with one queued row.
        val queuedBefore = envelope()
        factory(path).createConnection().let { connection ->
            val driver = connection.driver
            revertToVersionThree(driver)
            driver.execute(null, "ALTER TABLE outbox DROP COLUMN relay_backup", 0)
            driver.execute(null, "PRAGMA user_version = 2", 0)
            driver.execute(
                null,
                "INSERT INTO outbox(packet_id, envelope_blob, blob_size, target_device_id, is_relay, " +
                        "retry_count, expires_at, last_attempt_at, next_retry_at) VALUES (?, ?, ?, ?, 0, 0, ?, ?, ?)",
                7,
            ) {
                val blob = queuedBefore.encode()
                bindString(0, queuedBefore.packetId.toHexString())
                bindBytes(1, blob)
                bindLong(2, blob.size.toLong())
                bindString(3, queuedBefore.target.id)
                bindLong(4, queuedBefore.expiresAt.epochSeconds)
                bindLong(5, queuedBefore.createdAt.epochSeconds)
                bindLong(6, queuedBefore.createdAt.epochSeconds)
            }
            driver.close()
        }

        val connection = factory(path).createConnection()
        try {
            assertEquals(YapYapDatabase.Schema.version, readPragmaUserVersion(connection.driver))
            val outbox = DefaultPacketOutbox(connection.database)

            assertEquals(listOf(queuedBefore.packetId), outbox.listDue(epochSeconds(2_000L)).map { it.packetId })
            assertFalse(outbox.claimRelayBackup(queuedBefore.packetId), "rows from before the upgrade owe no backup")

            val queuedAfter = envelope()
            outbox.enqueue(queuedAfter, epochSeconds(1_000L), relayBackup = RelayBackup.PENDING)
            assertTrue(outbox.claimRelayBackup(queuedAfter.packetId))
            assertFalse(outbox.claimRelayBackup(queuedAfter.packetId), "claimed once")
        } finally {
            connection.driver.close()
        }
    }
}
