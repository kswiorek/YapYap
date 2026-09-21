package org.yapyap.orchestrator.boot

import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.persistence.key.KeyReference
import org.yapyap.persistence.key.KeyStore

/**
 * Terminal offline wipe: enumerates dynamic key refs (`spk-*`/`opk-*` IDs from
 * the DB, which the OS keyring cannot list), closes the DB, clears the keyring
 * ([KeyStore.deleteAll] for well-known refs + each enumerated ref), then deletes
 * everything under [dataDirectory] (PoC: `vault.db*`, `tor/`, `state.toml`,
 * `userSettings.toml`, `logs/` — the user copies out anything worth keeping
 * beforehand). Next `start()` recreates the directories, landing in
 * `SetupRequired`: wiped and fresh are indistinguishable by design.
 *
 * Entries orphaned from their DB rows (crash between `putKey` and `insert`, or a
 * previous partial wipe) are unfindable and linger — harmless, as fresh
 * provisioning mints new IDs and the master-key wipe cryptographically retires
 * the old DB.
 *
 * Caller (orchestrator) owns transport shutdown + scope cancellation + state
 * transitions; this only touches persistence.
 */
class LocalStoreReset(
    private val dataDirectory: Path,
    private val keyStore: KeyStore,
    private val closeDatabase: suspend () -> Unit = {},
    private val collectKeyRefs: suspend () -> List<KeyReference> = { emptyList() },
) {
    suspend fun wipe() {
        // DB must stay open for enumeration; tolerate corrupt/missing DB (fall back
        // to well-known refs + file deletion, which still fully resets the node).
        val dynamicRefs = runCatching { collectKeyRefs() }.getOrDefault(emptyList())
        runCatching { closeDatabase() }
        for (ref in dynamicRefs) {
            runCatching { keyStore.deleteKey(ref) }
        }
        runCatching { keyStore.deleteAll() }
        if (SystemFileSystem.exists(dataDirectory)) {
            for (child in SystemFileSystem.list(dataDirectory)) {
                deleteRecursively(child)
            }
        }
        SystemFileSystem.createDirectories(dataDirectory)
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

    private fun deleteRecursively(path: Path) {
        if (SystemFileSystem.metadataOrNull(path)?.isDirectory == true) {
            for (child in SystemFileSystem.list(path)) {
                deleteRecursively(child)
            }
        }
        SystemFileSystem.delete(path, mustExist = false)
    }
}
