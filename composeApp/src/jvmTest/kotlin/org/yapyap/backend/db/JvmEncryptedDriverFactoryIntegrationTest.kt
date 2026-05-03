package org.yapyap.backend.db

import java.nio.file.Files
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.absolutePathString
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Opens a real on-disk SQLCipher database via [JvmEncryptedDriverFactory].
 * Requires JDBC SQLite + SQLCipher support from [io.github.willena:sqlite-jdbc] (already on jvmMain classpath).
 */
@OptIn(ExperimentalPathApi::class)
class JvmEncryptedDriverFactoryIntegrationTest {

    private var tempDir: java.nio.file.Path? = null
    private var connection: DatabaseConnection? = null

    @AfterTest
    fun tearDown() {
        try {
            connection?.driver?.close()
        } finally {
            connection = null
        }
        tempDir?.let { dir ->
            runCatching { dir.deleteRecursively() }
            tempDir = null
        }
    }

    @Test
    fun databaseFactory_createConnection_initializesEncryptedSchema() {
        val dir = Files.createTempDirectory("yapyap-sqlcipher-test")
        tempDir = dir
        val dbFile = dir.resolve("vault.db")
        val pathForUrl = dbFile.absolutePathString().replace('\\', '/')
        val masterKey = ByteArray(32) { (it + 1).toByte() }

        val driverFactory = JvmEncryptedDriverFactory(databasePath = pathForUrl, masterKey = masterKey)
        connection = DatabaseFactory(driverFactory).createConnection()

        val version = readPragmaUserVersion(connection!!.driver)
        assertTrue(version > 0L, "schema user_version should be set after init (got $version)")
        assertTrue(readPragmaForeignKeys(connection!!.driver), "foreign_keys expected ON")
    }
}
