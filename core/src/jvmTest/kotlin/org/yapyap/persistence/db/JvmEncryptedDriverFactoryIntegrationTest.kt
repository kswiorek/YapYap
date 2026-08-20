package org.yapyap.persistence.db

import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import org.yapyap.persistence.JvmEncryptedDriverFactory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * Opens a real on-disk SQLCipher database via [JvmEncryptedDriverFactory].
 * Requires JDBC SQLite + SQLCipher support from [io.github.willena:sqlite-jdbc] (already on jvmMain classpath).
 */
class JvmEncryptedDriverFactoryIntegrationTest {

    private var tempDir: Path? = null
    private var connection: DatabaseConnection? = null

    @AfterTest
    fun tearDown() {
        try {
            connection?.driver?.close()
        } finally {
            connection = null
        }
        tempDir?.let { dir ->
            runCatching { deleteRecursively(dir) }
            tempDir = null
        }
    }

    @Test
    fun databaseFactory_createConnection_initializesEncryptedSchema() {
        val dir = Path(SystemTemporaryDirectory, "yapyap-sqlcipher-test-${Uuid.random()}")
        SystemFileSystem.createDirectories(dir)
        tempDir = dir
        val dbFile = Path(dir, "vault.db")
        val masterKey = ByteArray(32) { (it + 1).toByte() }

        val driverFactory = JvmEncryptedDriverFactory(databaseFile = dbFile, masterKey = masterKey)
        connection = DatabaseFactory(driverFactory).createConnection()

        val version = readPragmaUserVersion(connection!!.driver)
        assertTrue(version > 0L, "schema user_version should be set after init (got $version)")
        assertTrue(readPragmaForeignKeys(connection!!.driver), "foreign_keys expected ON")
    }

    private fun deleteRecursively(path: Path) {
        if (SystemFileSystem.metadataOrNull(path)?.isDirectory == true) {
            SystemFileSystem.list(path).forEach { deleteRecursively(it) }
        }
        SystemFileSystem.delete(path, mustExist = false)
    }
}