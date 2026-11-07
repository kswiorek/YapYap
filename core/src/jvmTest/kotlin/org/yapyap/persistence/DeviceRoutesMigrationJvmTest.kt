package org.yapyap.persistence

import kotlinx.coroutines.test.runTest
import org.yapyap.persistence.db.DatabaseFactory
import org.yapyap.persistence.db.DriverFactory
import org.yapyap.persistence.db.readPragmaUserVersion
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
 * router's device_routes split out of devices) by recreating the version-5 devices shape.
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
