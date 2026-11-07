package org.yapyap.wiring

import kotlinx.coroutines.CoroutineScope
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import org.yapyap.config.BootConfig
import org.yapyap.logging.AppLog
import org.yapyap.orchestrator.DefaultOrchestrator
import org.yapyap.orchestrator.boot.LocalStoreReset
import org.yapyap.orchestrator.boot.NodeAssembler
import org.yapyap.orchestrator.boot.Vault
import org.yapyap.crypto.store.DefaultKeyStore

/** The composition root: builds a node's [VaultGraph] and, through it, its [NodeGraph]. */
internal class DefaultNodeAssembler(
    dataDirectory: Path,
    private val bootConfig: BootConfig,
    private val platform: Platform,
) : NodeAssembler {
    private val layout = DataLayout(dataDirectory)

    override fun prepareDataDirectory() {
        SystemFileSystem.createDirectories(layout.dataDirectory)
        SystemFileSystem.createDirectories(layout.torStateRoot)
        SystemFileSystem.createDirectories(layout.logDirectory)
        AppLog.init(platform.logger(layout.logDirectory))
    }

    override suspend fun openVault(scope: CoroutineScope): Vault =
        VaultGraph.open(layout, bootConfig, platform, scope)

    override fun storeReset(vault: Vault?): LocalStoreReset {
        val graph = vault as VaultGraph?
        return LocalStoreReset(
            dataDirectory = layout.dataDirectory,
            // Without an open vault, a fresh store on the same keyring is equivalent for the
            // wipe (service-scoped well-known slots).
            keyStore = graph?.keyStore ?: DefaultKeyStore(platform.keyringSessionFactory),
            closeDatabase = { graph?.close() },
            collectKeySlots = { graph?.prekeySlots().orEmpty() },
        )
    }
}

/** Builds an orchestrator for [dataDirectory] on [platform]. */
internal fun createOrchestrator(dataDirectory: Path, bootConfig: BootConfig, platform: Platform): DefaultOrchestrator =
    DefaultOrchestrator(dataDirectory, bootConfig, DefaultNodeAssembler(dataDirectory, bootConfig, platform))
