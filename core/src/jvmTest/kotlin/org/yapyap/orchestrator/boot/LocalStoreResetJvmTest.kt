package org.yapyap.orchestrator.boot

import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlinx.io.files.Path
import org.yapyap.persistence.key.InMemoryKeyStore
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The wipe leaves the held lock file (Windows refuses to delete an open file), tries every
 * other entry even when one fails, and names what it could not delete.
 */
class LocalStoreResetJvmTest {

    private fun dataDir(): File = Files.createTempDirectory("yapyap-reset").toFile().apply {
        resolve("vault.db").writeText("db")
        resolve("logs").mkdirs()
        resolve("logs/yapyap.log").writeText("log")
        resolve("tor").mkdirs()
        resolve("tor/state").writeText("tor")
        resolve("userSettings.toml").writeText("")
    }

    @Test
    fun wipe_keepsTheLockFile_andDeletesTheRest() = runTest {
        val dir = dataDir()
        val lock = checkNotNull(acquireDataDirLock(Path(dir.absolutePath)))
        try {
            LocalStoreReset(Path(dir.absolutePath), InMemoryKeyStore()).wipe()
            assertEquals(listOf(DATA_DIR_LOCK_FILE), dir.list()!!.toList())
        } finally {
            lock.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun undeletableEntry_isNamed_andTheOthersStillGo() = runTest {
        val dir = dataDir()
        val tor = dir.resolve("tor")
        // A read-only directory refuses to give up its children, like an open file on Windows.
        tor.setWritable(false)
        try {
            val failure = assertFailsWith<IOException> {
                LocalStoreReset(Path(dir.absolutePath), InMemoryKeyStore()).wipe()
            }
            assertTrue("tor" in failure.message.orEmpty(), "names the file: ${failure.message}")
            assertEquals(listOf("tor"), dir.list()!!.toList(), "everything else was deleted")
        } finally {
            tor.setWritable(true)
            dir.deleteRecursively()
        }
    }
}
