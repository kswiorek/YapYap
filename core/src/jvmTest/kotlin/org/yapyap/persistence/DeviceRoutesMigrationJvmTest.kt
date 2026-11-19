package org.yapyap.persistence

import kotlinx.coroutines.test.runTest
import org.yapyap.orchestrator.identity.DefaultIdentityStore
import org.yapyap.protocol.DeviceType
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.TorEndpoint
import org.yapyap.routing.store.DefaultDeviceRouteStore
import org.yapyap.testfixtures.epochSeconds
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Turns a store created at today's schema back into a version-5 store: undoes 5.sqm (the
 * router's device_routes split out of devices) by recreating the version-5 devices shape,
 * and undoes 6.sqm (file placeholders -> attachment index) by restoring the placeholders.
 */
internal fun revertToVersionFive(driver: app.cash.sqldelight.db.SqlDriver) {
    listOf(
        "DROP TABLE device_routes",
        "DROP INDEX devices_account_idx",
        "DROP TABLE devices",
        """CREATE TABLE devices (
            device_id TEXT NOT NULL PRIMARY KEY,
            is_local_device INTEGER NOT NULL,
            account_id TEXT NOT NULL,
            device_type TEXT NOT NULL,
            onion_address TEXT NOT NULL,
            onion_port INTEGER NOT NULL,
            signing_pub_key BLOB NOT NULL,
            encryption_pub_key BLOB NOT NULL,
            status TEXT NOT NULL,
            current_signed_prekey_id TEXT,
            push_token TEXT,
            reliability_score REAL NOT NULL,
            last_seen_timestamp INTEGER NOT NULL,
            provisional INTEGER NOT NULL DEFAULT 0,
            FOREIGN KEY (account_id) REFERENCES accounts(account_id)
        )""",
        "CREATE INDEX devices_account_idx ON devices(account_id)",
        // 13.sqm undone: the pending file requests go (fresh schema creates them).
        "DROP TABLE IF EXISTS pending_file_requests",
        // 12.sqm undone: the file request attempts go (fresh schema creates them).
        "DROP TABLE IF EXISTS file_request_declined_devices",
        "DROP INDEX IF EXISTS file_request_attempts_due_idx",
        "DROP TABLE IF EXISTS file_request_attempts",
        // 11.sqm undone: the send/serve rows go (fresh schema creates them).
        "DROP TABLE IF EXISTS file_transfers",
        // 10.sqm undone: the push rows go (fresh schema creates them).
        "DROP INDEX IF EXISTS file_pushes_file_target_idx",
        "DROP TABLE IF EXISTS file_pushes",
        // 9.sqm undone: the receive state and staging go (fresh schema creates them).
        "DROP INDEX IF EXISTS file_receive_chunks_file_idx",
        "DROP TABLE IF EXISTS file_receive_chunks",
        "DROP TABLE IF EXISTS file_receives",
        // 8.sqm undone: the incoming-transfer rows go (fresh schema creates them).
        "DROP INDEX IF EXISTS file_incoming_transfers_file_idx",
        "DROP TABLE IF EXISTS file_incoming_transfers",
        // 7.sqm undone: the permanent store index goes (fresh schema creates it).
        "DROP TABLE IF EXISTS file_descriptors",
        // 6.sqm undone: attachment index goes, old placeholders come back (empty).
        "DROP INDEX IF EXISTS message_attachments_file_idx",
        "DROP TABLE IF EXISTS message_attachments",
        """CREATE TABLE file_transfers (
            transfer_id TEXT NOT NULL PRIMARY KEY,
            message_id TEXT NOT NULL,
            status TEXT NOT NULL,
            file_name_hint TEXT,
            mime_type TEXT,
            total_bytes INTEGER NOT NULL,
            chunk_size_bytes INTEGER NOT NULL,
            chunk_count INTEGER NOT NULL,
            object_hash BLOB NOT NULL,
            highest_contiguous_chunk INTEGER NOT NULL DEFAULT -1,
            local_file_path TEXT NOT NULL,
            FOREIGN KEY (message_id) REFERENCES messages(message_id)
        )""",
        """CREATE TABLE file_chunk_tracker (
            transfer_id TEXT NOT NULL,
            chunk_index INTEGER NOT NULL,
            status TEXT NOT NULL,
            PRIMARY KEY (transfer_id, chunk_index),
            FOREIGN KEY (transfer_id) REFERENCES file_transfers(transfer_id)
        )""",
        "CREATE INDEX file_transfers_message_idx ON file_transfers(message_id)",
        "CREATE INDEX file_chunk_tracker_status_idx ON file_chunk_tracker(status)",
        "PRAGMA user_version = 5",
    ).forEach { driver.execute(null, it, 0) }
}

/**
 * Upgrade path for 5.sqm: a version-5 store's per-device router state (endpoint, reliability,
 * last-seen) moves to device_routes, and devices keeps its identity columns and its prekeys.
 */
class DeviceRoutesMigrationJvmTest {

    private fun factory(path: String) = DatabaseFactory(object : DriverFactory {
        override fun createDriver() = SingleConnectionJdbcDriver("jdbc:sqlite:file:$path?foreign_keys=on")
    })

    @Test
    fun versionFiveStore_movesRouterStateToDeviceRoutes() = runTest {
        val path = Files.createTempDirectory("yapyap-migration").resolve("v.db").toString().replace('\\', '/')
        val seen = PeerId("seen-device")
        val neverSeen = PeerId("never-seen-device")

        factory(path).createConnection().let { connection ->
            val driver = connection.driver
            revertToVersionFive(driver)
            fun exec(sql: String) = driver.execute(null, sql, 0)
            exec("INSERT INTO accounts(account_id, is_local_account, role, status, display_name) VALUES ('acc', 0, 'MEMBER', 'ACTIVE', 'a')")
            exec(
                "INSERT INTO devices VALUES ('${seen.id}', 0, 'acc', 'DESKTOP', 'healed.onion', 81, X'01', X'02', " +
                        "'ACTIVE', 'spk-1', NULL, 0.75, 7000, 0)",
            )
            // Never seen: the old Instant.DISTANT_PAST sentinel.
            exec(
                "INSERT INTO devices VALUES ('${neverSeen.id}', 0, 'acc', 'DESKTOP', 'n.onion', 80, X'03', X'04', " +
                        "'ACTIVE', NULL, NULL, 0.5, -3217862419201, 1)",
            )
            // A child row: the rebuild must leave it pointing at its device.
            exec(
                "INSERT INTO signed_prekeys(spk_id, device_id, public_key, signature, is_active, created_at_epoch_seconds) " +
                        "VALUES ('spk-1', '${seen.id}', X'05', X'06', 1, 0)",
            )
            driver.close()
        }

        val connection = factory(path).createConnection()
        try {
            assertEquals(YapYapDatabase.Schema.version, readPragmaUserVersion(connection.driver))
            val identities = DefaultIdentityStore(connection.database, DeviceType.DESKTOP)
            val routes = DefaultDeviceRouteStore(connection.database, identities)

            assertEquals(TorEndpoint("healed.onion", 81), routes.resolveTorEndpointForDevice(seen))
            val availability = assertNotNull(routes.availability(seen))
            assertEquals(0.75, availability.reliabilityScore)
            assertEquals(epochSeconds(7_000L), availability.lastSeen)
            assertNull(connection.database.routesQueries.selectRoute(neverSeen).executeAsOne().last_seen_timestamp)

            assertEquals(true, identities.isDeviceProvisional(neverSeen))
            assertNotNull(identities.getActiveSignedPreKeyForDevice(seen))
        } finally {
            connection.driver.close()
        }
    }
}
