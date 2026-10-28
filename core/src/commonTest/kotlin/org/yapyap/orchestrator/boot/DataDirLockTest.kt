package org.yapyap.orchestrator.boot

import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class DataDirLockTest {

    @Test
    fun lock_is_exclusive_and_releasable() = runTest {
        val dir = Path(SystemTemporaryDirectory.toString(), "yapyap-locktest-${Uuid.random()}")
        SystemFileSystem.createDirectories(dir)
        try {
            val first = acquireDataDirLock(dir)
            assertNotNull(first, "first acquire must succeed")
            assertNull(acquireDataDirLock(dir), "second acquire while held must fail")
            first.close()
            assertNotNull(
                acquireDataDirLock(dir)?.also { it.close() },
                "acquire after release must succeed",
            )
        } finally {
            SystemFileSystem.delete(Path(dir.toString() + "/app.lock"), mustExist = false)
            SystemFileSystem.delete(dir, mustExist = false)
        }
    }
}
