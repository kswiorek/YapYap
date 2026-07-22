package org.yapyap.orchestrator

import org.yapyap.routing.router.RouterConfig
import org.yapyap.transport.tor.backend.TorBackendConfig

enum class OrchestratorState {
    Created,
    Unlocking,
    SetupRequired,    // app: show onboarding; relay: should not linger here
    BootRecovering,
    Starting,
    Running,
    Stopping,
    Stopped,
    Failed,
}

enum class NodeMode {
    FULL_CLIENT,   // GUI app
    HEADLESS_RELAY // Pi relay; no UI, possibly slimmer surface
}

data class OrchestratorConfig(
    val mode: NodeMode,
    /** Absolute path to the node data root (DB, Tor state, logs). */
    val dataDirectory: String,
    val routerConfig: RouterConfig = RouterConfig(),
    val torBackendConfig: TorBackendConfig = TorBackendConfig(),
    val keyringServiceName: String = "org.yapyap",
) {
    init {
        require(dataDirectory.isNotBlank()) { "dataDirectory must not be blank" }
        require(keyringServiceName.isNotBlank()) { "keyringServiceName must not be blank" }
    }

    val databasePath: String get() = "$dataDirectory/vault.db"
    val torStateRootPath: String get() = "$dataDirectory/tor"
    val logDirectory: String get() = "$dataDirectory/logs"
}