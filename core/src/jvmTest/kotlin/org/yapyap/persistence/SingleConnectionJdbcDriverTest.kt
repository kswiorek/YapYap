package org.yapyap.persistence

import app.cash.sqldelight.Query
import app.cash.sqldelight.db.SqlDriver
import org.yapyap.persistence.db.DatabaseFactory
import org.yapyap.persistence.db.DriverFactory
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.AccountRole
import org.yapyap.protocol.IdentityStatus
import java.nio.file.Files
import kotlin.test.*

/**
 * The persistent-connection driver: one physical connection per driver, transactions
 * and listener notification behave like upstream, close ends it.
 */
class SingleConnectionJdbcDriverTest {

    private fun tempFile(): String =
        Files.createTempDirectory("yapyap-singledriver").resolve("t.db").toString()
            .replace('\\', '/')

    private fun open(path: String? = null): SingleConnectionJdbcDriver =
        SingleConnectionJdbcDriver("jdbc:sqlite:file:${path ?: tempFile()}?foreign_keys=on")

    @Test
    fun reuses_single_connection_and_round_trips_rows() {
        val driver = open()
        try {
            assertSame(driver.getConnection(), driver.getConnection())
            driver.execute(null, "CREATE TABLE t(id TEXT PRIMARY KEY, v BLOB)", 0)
            val payload = byteArrayOf(1, 2, 3)
            driver.execute(null, "INSERT INTO t VALUES('a', ?)", 1) {
                bindBytes(0, payload)
            }
            repeat(5) {
                val got = driver.executeQuery(null, "SELECT v FROM t WHERE id='a'", { cursor ->
                    cursor.next().value
                    app.cash.sqldelight.db.QueryResult.Value(cursor.getBytes(0))
                }, 0).value
                assertContentEquals(payload, got)
            }
        } finally {
            driver.close()
        }
    }

    @Test
    fun transaction_commit_and_rollback() {
        val driver = open()
        try {
            val factory = object : DriverFactory {
                override fun createDriver(): SqlDriver = driver
            }
            val db = DatabaseFactory(factory).createConnection().database
            db.transaction {
                db.identityQueries.putAccount(
                    AccountId("a"), null, false, AccountRole.MEMBER, IdentityStatus.ACTIVE, "n", false,
                )
            }
            try {
                db.transaction {
                    db.identityQueries.putAccount(
                        AccountId("b"), null, false, AccountRole.MEMBER, IdentityStatus.ACTIVE, "n", false,
                    )
                    throw IllegalStateException("boom")
                }
            } catch (_: IllegalStateException) {
                // Expected: the body aborts the transaction.
            }
            assertNotNull(db.identityQueries.selectAccountById(AccountId("a")).executeAsOneOrNull())
            assertNull(db.identityQueries.selectAccountById(AccountId("b")).executeAsOneOrNull())
        } finally {
            driver.close()
        }
    }

    @Test
    fun listeners_fire_on_notify_and_close_ends_driver() {
        val driver = open()
        try {
            var fired = 0
            val listener = object : Query.Listener {
                override fun queryResultsChanged() {
                    fired++
                }
            }
            driver.addListener("t", listener = listener)
            driver.notifyListeners("t", "other")
            assertEquals(1, fired)
            driver.removeListener("t", listener = listener)
            driver.notifyListeners("t")
            assertEquals(1, fired)
        } finally {
            driver.close()
        }
        assertFailsWith<Exception> {
            driver.execute(null, "SELECT 1", 0)
        }
        assertTrue(driver.getConnection().isClosed)
    }
}
