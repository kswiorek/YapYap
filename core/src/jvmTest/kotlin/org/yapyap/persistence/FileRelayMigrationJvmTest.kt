package org.yapyap.persistence

import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * Upgrade path for 14.sqm: the file relay store (routing-owned transient ciphertext
 * for foreign traffic) lands. Runs with FK off, checked with FK check.
 */
class FileRelayMigrationJvmTest {

    private fun factory(path: String) = DatabaseFactory(object : DriverFactory {
        override fun createDriver() = SingleConnectionJdbcDriver("jdbc:sqlite:file:$path?foreign_keys=on")
    })

    @Test
    fun versionThirteen_migratesToFileRelayTables() = runTest {
        val path =
            Files.createTempDirectory("yapyap-file-relay-migration").resolve("v.db").toString().replace('\\', '/')

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
            val now = kotlin.time.Instant.fromEpochSeconds(10_000L)

            db.fileRelayQueries.insertFileRelayTransfer(
                transfer_id = transferId,
                source_device = org.yapyap.protocol.PeerId("a".repeat(64)),
                target_device = org.yapyap.protocol.PeerId("b".repeat(64)),
                chunk_size_bytes = 220L * 1024,
                total_bytes = 1_000L,
                start_index = 0L,
                offer_payload = byteArrayOf(1, 2, 3),
                status = "RESERVED",
                reserved_bytes = 1_000L,
                marker_packet_id = null,
                created_at = now,
                last_chunk_at = now,
                expires_at = kotlin.time.Instant.fromEpochSeconds(20_000L),
            )
            db.fileRelayQueries.insertFileRelayChunk(
                transfer_id = transferId,
                chunk_index = 0L,
                packet_id = null,
                blob = byteArrayOf(4, 5)
            )
            // PK dedup: a re-deposited chunk is a no-op, never double-counted.
            db.fileRelayQueries.insertFileRelayChunk(
                transfer_id = transferId,
                chunk_index = 0L,
                packet_id = null,
                blob = byteArrayOf(4, 5)
            )
            assertEquals(1L, db.fileRelayQueries.countFileRelayChunksByTransfer(transferId).executeAsOne())

            val row = db.fileRelayQueries.selectFileRelayTransferById(transferId).executeAsOneOrNull()
            assertNotNull(row, "relay transfer must be readable by transfer id")
            assertEquals("RESERVED", row.status)
            assertEquals(1_000L, db.fileRelayQueries.sumFileRelayReservedBytes().executeAsOneOrNull() ?: -1L)

            val packetId = Uuid.random().toHexString()
            db.fileRelayQueries.updateFileRelayTransferMarker(packetId, "FORWARDING", transferId)
            db.fileRelayQueries.updateFileRelayChunkPacketId(packetId, transferId, 0L)
            val byPacket = db.fileRelayQueries.selectFileRelayChunkByPacketId(packetId).executeAsOneOrNull()
            assertNotNull(byPacket, "chunk must resolve through its forwarded packet id")
            db.fileRelayQueries.deleteFileRelayChunkByPacketId(packetId)
            assertEquals(0L, db.fileRelayQueries.countFileRelayChunksByTransfer(transferId).executeAsOne())

            db.fileRelayQueries.deleteFileRelayTransferById(transferId)
            assertTrue(db.fileRelayQueries.selectFileRelayTransferById(transferId).executeAsOneOrNull() == null)
        } finally {
            connection.driver.close()
        }
    }
}
