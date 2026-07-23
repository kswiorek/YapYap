package org.yapyap.orchestrator

import org.yapyap.crypto.identity.AccountIdentityRecord
import org.yapyap.crypto.identity.DeviceIdentityRecord
import org.yapyap.protocol.TorEndpoint
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

sealed interface SetupIntent {
    data class NewAccountFirstDevice(
        val accountName: String,
    ): SetupIntent

    data class ImportAccountRecoveryKey(
        val recoveryKey: String,
        val bootstrapTorEndpoint: TorEndpoint? = null
    ): SetupIntent

    data object AddDeviceToExistingAccount: SetupIntent
}

data class SetupResult(
    val identityPayload: IdentityPayload,   // for QR / CLI display
    val recoveryKey: String?,    // non-null only for NewAccountFirstDevice
)


data class IdentityPayload(
    val account: AccountIdentityRecord?,
    val device: DeviceIdentityRecord,       // public parts (signing/enc/SPK/sig)
    val torEndpoint: TorEndpoint?,          // null until Tor is up; update QR later if needed
)

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