package org.yapyap.persistence

import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * Upgrade path for 10.sqm: the pusher's per-device transfer state lands. Runs with FK
 * off, checked with FK check.
 */
class FilePushMigrationJvmTest {

    private fun factory(path: String) = DatabaseFactory(object : DriverFactory {
        override fun createDriver() = SingleConnectionJdbcDriver("jdbc:sqlite:file:$path?foreign_keys=on")
    })

    @Test
    fun versionNine_migratesToFilePushes() = runTest {
        val path = Files.createTempDirectory("yapyap-file-push-migration").resolve("v.db").toString().replace('\\', '/')

        factory(path).createConnection().let { connection ->
            val driver = connection.driver
            revertToVersionFive(driver)
            driver.close()
        }

        val connection = factory(path).createConnection()
        try {
            assertEquals(YapYapDatabase.Schema.version, readPragmaUserVersion(connection.driver))
            val db = connection.database
            val transferId = Uuid.random().toHexString()
            val deviceId = "a".repeat(64)
            db.filePushQueries.insertFilePush(
                transfer_id = transferId,
                file_id = org.yapyap.protocol.FileId("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"),
                target_device = org.yapyap.protocol.PeerId(deviceId),
                kind = "DIRECT",
                total_bytes = 1000L,
                key = ByteArray(32),
                chunk_size_bytes = 220L * 1024,
                start_index = 0L,
                next_chunk_index = 0L,
                contiguous_acked = -1L,
                marker_packet_id = null,
                status = "CREATED",
                created_at = kotlin.time.Instant.fromEpochSeconds(10_000L),
                expires_at = kotlin.time.Instant.fromEpochSeconds(20_000L),
            )
            val row = db.filePushQueries.selectFilePushById(transferId).executeAsOneOrNull()
            assertTrue(row != null, "row must be readable by transfer id")
            assertEquals("DIRECT", row.kind)
            db.filePushQueries.updateFilePushMarker(Uuid.random().toHexString(), transferId)
            db.filePushQueries.updateFilePushProgress(3L, 1L, transferId)
            val updated = db.filePushQueries.selectFilePushById(transferId).executeAsOne()!!
            assertEquals(3L, updated.next_chunk_index)
            assertEquals(1L, updated.contiguous_acked)
            assertTrue(updated.marker_packet_id != null)
            // Push rows are history: no delete path, TTL expiry just stops the transfer.
        } finally {
            connection.driver.close()
        }
    }
}
