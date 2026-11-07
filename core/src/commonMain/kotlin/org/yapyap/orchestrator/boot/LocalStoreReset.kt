package org.yapyap.orchestrator.boot

import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.crypto.keys.KeySlot
import org.yapyap.crypto.store.KeyStore

/**
 * Terminal offline wipe: enumerates prekey key slots (`spk-*`/`opk-*` IDs from
 * the DB, which the OS keyring cannot list), closes the DB, clears the keyring
 * ([KeyStore.deleteAll] for well-known slots + each enumerated slot), then deletes
 * everything under [dataDirectory] except the lock file (PoC: `vault.db*`, `tor/`,
 * `state.toml`, `userSettings.toml`, `logs/` — the user copies out anything worth
 * keeping beforehand). Next `start()` recreates the directories and reopens the
 * database, landing in `SetupRequired` via the boot diagnoser: wiped and
 * fresh are indistinguishable by design. The orchestrator therefore lands in
 * `Stopped` (never `SetupRequired` directly) — its in-memory DB handle is
 * dead after the wipe and only `start()` can rebuild it.
 *
 * Entries orphaned from their DB rows (crash between `putKey` and `insert`, or a
 * previous partial wipe) are unfindable and linger — harmless, as fresh
 * provisioning mints new IDs and the master-key wipe cryptographically retires
 * the old DB.
 *
 * Caller (orchestrator) owns transport shutdown + scope cancellation + state
 * transitions; this only touches persistence.
 */
internal class LocalStoreReset(
    private val dataDirectory: Path,
    private val keyStore: KeyStore,
    private val closeDatabase: suspend () -> Unit = {},
    private val collectKeySlots: suspend () -> List<KeySlot> = { emptyList() },
    /**
     * Top-level entries left in place. The data-directory lock file is held open by the
     * caller during the wipe, and Windows refuses to delete an open file; it holds only a PID.
     */
    private val keepFileNames: Set<String> = setOf(DATA_DIR_LOCK_FILE),
) {
    suspend fun wipe() {
        // DB must stay open for enumeration; tolerate corrupt/missing DB (fall back
        // to well-known slots + file deletion, which still fully resets the node).
        val dynamicSlots = runCatching { collectKeySlots() }.getOrDefault(emptyList())
        runCatching { closeDatabase() }
        for (slot in dynamicSlots) {
            runCatching { keyStore.deleteKey(slot) }
        }
        runCatching { keyStore.deleteAll() }
        // Every entry gets its attempt even when one fails, and the failures are named.
        val failed = mutableListOf<Path>()
        if (SystemFileSystem.exists(dataDirectory)) {
            for (child in SystemFileSystem.list(dataDirectory)) {
                if (child.name in keepFileNames) continue
                deleteRecursively(child, failed)
            }
        }
        SystemFileSystem.createDirectories(dataDirectory)
        if (failed.isNotEmpty()) {
            throw IOException("Could not delete ${failed.joinToString()} (still open by another program?)")
        }
        // Logging may already be torn down alongside the wiped logs dir; keep this
        // best-effort so reset itself never fails on a dead sink.
        runCatching {
            AppLog.info(
                component = LogComponent.ORCHESTRATOR,
                event = LogEvent.STOPPED,
                message = "Local state wiped",
            )
        }
    }

    private fun deleteRecursively(path: Path, failed: MutableList<Path>) {
        if (SystemFileSystem.metadataOrNull(path)?.isDirectory == true) {
            for (child in SystemFileSystem.list(path)) {
                deleteRecursively(child, failed)
            }
        }
        try {
            SystemFileSystem.delete(path, mustExist = false)
        } catch (e: IOException) {
            failed += path
        }
    }
}
