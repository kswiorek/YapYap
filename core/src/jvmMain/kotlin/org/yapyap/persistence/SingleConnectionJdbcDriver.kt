package org.yapyap.persistence

import app.cash.sqldelight.Query
import app.cash.sqldelight.driver.jdbc.ConnectionManager
import app.cash.sqldelight.driver.jdbc.JdbcDriver
import java.sql.Connection
import java.sql.DriverManager
import java.util.*

/**
 * SQLDelight JDBC driver holding ONE persistent connection for the driver's lifetime.
 *
 * Why: [JdbcSqliteDriver] (used for file DBs) pairs every query outside an explicit
 * transaction with a fresh physical connection open + close. Against SQLCipher each
 * open re-runs the full key derivation (~90ms measured) — so a boot sweep of a few
 * hundred queries burns a minute in KDF alone, and every chat-window load pays
 * ~0.5s for a handful of rows. One connection pays the derivation once per process.
 *
 * This mirrors upstream's own `StaticConnectionManager` (single shared connection,
 * used for memory DBs), extended to file DBs. Soundness rests on the same
 * discipline [org.yapyap.persistence.db.databaseDispatcher] already documents:
 * every repository funnels through that single thread, schema init runs strictly
 * before any use, and close happens strictly after the last use — so the shared
 * connection is never touched concurrently (and sqlite-jdbc additionally
 * serializes statements on the connection monitor).
 */
internal class SingleConnectionJdbcDriver(
    url: String,
    properties: Properties = Properties(),
) : JdbcDriver(),
    ConnectionManager {
    private val listeners = linkedMapOf<String, MutableSet<Query.Listener>>()
    private val connection: Connection = DriverManager.getConnection(url, properties)

    override var transaction: ConnectionManager.Transaction? = null

    override fun getConnection(): Connection = connection

    override fun closeConnection(connection: Connection) = Unit

    override fun close() = connection.close()

    override fun addListener(vararg queryKeys: String, listener: Query.Listener) {
        synchronized(listeners) {
            queryKeys.forEach { listeners.getOrPut(it, { linkedSetOf() }).add(listener) }
        }
    }

    override fun removeListener(vararg queryKeys: String, listener: Query.Listener) {
        synchronized(listeners) {
            queryKeys.forEach { listeners[it]?.remove(listener) }
        }
    }

    override fun notifyListeners(vararg queryKeys: String) {
        val listenersToNotify = linkedSetOf<Query.Listener>()
        synchronized(listeners) {
            queryKeys.forEach { listeners[it]?.let(listenersToNotify::addAll) }
        }
        listenersToNotify.forEach(Query.Listener::queryResultsChanged)
    }
}
