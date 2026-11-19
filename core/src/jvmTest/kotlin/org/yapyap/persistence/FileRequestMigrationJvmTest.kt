package org.yapyap.persistence

import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * Upgrade path for 12.sqm + 13.sqm: the file request asking state (routing) and the
 * pending-file-request trigger state (orchestrator) land. Runs with FK off, checked
 * with FK check.
 */
class FileRequestMigrationJvmTest {

    private fun factory(path: String) = DatabaseFactory(object : DriverFactory {
        override fun createDriver() = SingleConnectionJdbcDriver("jdbc:sqlite:file:$path?foreign_keys=on")
    })

    @Test
    fun versionEleven_migratesToFileRequestTables() = runTest {
        val path = Files.createTempDirectory("yapyap-file-request-migration").resolve("v.db").toString().replace('\\', '/')

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
            val deviceId = org.yapyap.protocol.PeerId("a".repeat(64))

            db.fileRequestAttemptQueries.insertFileRequestAttempt(fileId, kotlin.time.Instant.fromEpochSeconds(10_000L))
            db.fileRequestAttemptQueries.insertDeclinedFileRequestDevice(fileId, deviceId)
            db.fileRequestAttemptQueries.setFileRequestTransfer(Uuid.random().toHexString(), fileId)
            db.fileRequestAttemptQueries.setFileRequestWaiting(
                deviceId,
                kotlin.time.Instant.fromEpochSeconds(20_000L),
                Uuid.random().toHexString(),
                fileId,
            )
            val attempt = db.fileRequestAttemptQueries.selectFileRequestById(fileId).executeAsOneOrNull()
            assertNotNull(attempt, "attempt row must be readable by file id")
            assertEquals(deviceId, attempt.waiting_device)
            db.fileRequestAttemptQueries.clearFileRequestWaiting(fileId)
            val cleared = db.fileRequestAttemptQueries.selectFileRequestById(fileId).executeAsOne()
            assertTrue(cleared.waiting_device == null && cleared.request_transfer_id == null)

            db.fileTransferQueries.insertPendingFileRequest(
                fileId,
                org.yapyap.orchestrator.file.PendingFileRequestStatus.PENDING,
                kotlin.time.Instant.fromEpochSeconds(10_000L),
                kotlin.time.Instant.fromEpochSeconds(10_000L),
            )
            val pending = db.fileTransferQueries.selectPendingFileRequestById(fileId).executeAsOneOrNull()
            assertNotNull(pending, "pending row must be readable by file id")
            db.fileTransferQueries.updatePendingFileRequestStatus(
                org.yapyap.orchestrator.file.PendingFileRequestStatus.UNAVAILABLE,
                kotlin.time.Instant.fromEpochSeconds(11_000L),
                fileId,
            )
            db.fileTransferQueries.deletePendingFileRequest(fileId)
            assertTrue(db.fileTransferQueries.selectPendingFileRequestById(fileId).executeAsOneOrNull() == null)
        } finally {
            connection.driver.close()
        }
    }
}
