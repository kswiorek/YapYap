package org.yapyap.persistence

import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Upgrade path for 11.sqm: the orchestrator-owned send/serve rows land. Runs with FK
 * off, checked with FK check.
 */
class FileTransferMigrationJvmTest {

    private fun factory(path: String) = DatabaseFactory(object : DriverFactory {
        override fun createDriver() = SingleConnectionJdbcDriver("jdbc:sqlite:file:$path?foreign_keys=on")
    })

    @Test
    fun versionNine_migratesToFileTransfers() = runTest {
        val path =
            Files.createTempDirectory("yapyap-file-transfer-migration").resolve("v.db").toString().replace('\\', '/')

        factory(path).createConnection().let { connection ->
            val driver = connection.driver
            revertToVersionFive(driver)
            driver.close()
        }

        val connection = factory(path).createConnection()
        try {
            assertEquals(YapYapDatabase.Schema.version, readPragmaUserVersion(connection.driver))
            val db = connection.database
            val fileId = org.yapyap.protocol.FileId("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
            val account = org.yapyap.protocol.AccountId("file-transfer-acct")
            db.fileTransferQueries.insertFileTransfer(
                file_id = fileId,
                peer_account_id = account,
                direction = org.yapyap.orchestrator.file.FileTransferDirection.SEND,
                transfer_class = org.yapyap.orchestrator.file.FileTransferClass.SMALL,
                status = org.yapyap.orchestrator.file.FileTransferStatus.IN_FLIGHT,
                created_at = kotlin.time.Instant.fromEpochSeconds(10_000L),
                updated_at = kotlin.time.Instant.fromEpochSeconds(10_000L),
                expires_at = kotlin.time.Instant.fromEpochSeconds(20_000L),
            )
            val row = db.fileTransferQueries.selectFileTransfer(fileId, account).executeAsOneOrNull()
            assertTrue(row != null, "send row must be readable by (file, account)")
            assertEquals("IN_FLIGHT", row.status.name)
            db.fileTransferQueries.updateFileTransferStatus(
                org.yapyap.orchestrator.file.FileTransferStatus.COMPLETED,
                kotlin.time.Instant.fromEpochSeconds(11_000L),
                fileId,
                account,
            )
            assertEquals(
                "COMPLETED",
                db.fileTransferQueries.selectFileTransfer(fileId, account).executeAsOne().status.name,
            )
            assertEquals(1, db.fileTransferQueries.selectFileTransfersByFile(fileId).executeAsList().size)
        } finally {
            connection.driver.close()
        }
    }
}
