package org.yapyap.persistence

import kotlinx.coroutines.test.runTest
import org.yapyap.persistence.db.DatabaseFactory
import org.yapyap.persistence.db.DriverFactory
import org.yapyap.persistence.db.readPragmaUserVersion
import org.yapyap.persistence.packet.DefaultPacketOutbox
import org.yapyap.persistence.packet.RelayBackup
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
