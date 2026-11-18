package org.yapyap.persistence

import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * Upgrade path for 8.sqm: the routing-owned `file_incoming_transfers` table lands.
 * Runs with FK off, checked with FK check.
 */
class FileIncomingMigrationJvmTest {

    private fun factory(path: String) = DatabaseFactory(object : DriverFactory {
        override fun createDriver() = SingleConnectionJdbcDriver("jdbc:sqlite:file:$path?foreign_keys=on")
    })

    @Test
    fun versionSeven_migratesToIncomingTransfers() = runTest {
        val path = Files.createTempDirectory("yapyap-file-incoming-migration").resolve("v.db").toString().replace('\\', '/')

        factory(path).createConnection().let { connection ->
            val driver = connection.driver
            revertToVersionFive(driver)
            // revertToVersionFive sets user_version = 5; migrate 5 -> latest.
            driver.close()
        }

        val connection = factory(path).createConnection()
        try {
            assertEquals(YapYapDatabase.Schema.version, readPragmaUserVersion(connection.driver))
            val transferId = Uuid.random().toHexString()
            val peerId = "a".repeat(64)
            val db = connection.database
            db.fileIncomingQueries.insertFileIncomingTransfer(
                transfer_id = transferId,
                file_id = org.yapyap.protocol.FileId("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"),
                author_device = org.yapyap.protocol.PeerId(peerId),
                key = ByteArray(32),
                chunk_size_bytes = 220L * 1024,
                total_bytes = 1000L,
                start_index = 0L,
                status = "ACTIVE",
                created_at = kotlin.time.Instant.fromEpochSeconds(10_000L),
                expires_at = kotlin.time.Instant.fromEpochSeconds(20_000L),
            )
            val rows = db.fileIncomingQueries.selectFileIncomingTransfersByFileId(
                org.yapyap.protocol.FileId("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"),
            ).executeAsList()
            assertEquals(1, rows.size)
            assertEquals(transferId, rows.single().transfer_id)
            val byId = db.fileIncomingQueries.selectFileIncomingTransferById(transferId).executeAsOneOrNull()
            assertTrue(byId != null, "row must be readable by transferId")
            db.fileIncomingQueries.deleteFileIncomingTransferById(transferId)
            assertTrue(
                db.fileIncomingQueries.selectFileIncomingTransferById(transferId).executeAsOneOrNull() == null,
                "row must be deletable",
            )
        } finally {
            connection.driver.close()
        }
    }
}
