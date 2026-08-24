package org.yapyap.orchestrator

import org.yapyap.crypto.identity.AccountIdentityRecord
import org.yapyap.crypto.identity.DeviceIdentityRecord
import org.yapyap.orchestrator.sync.SyncConfig
import org.yapyap.protocol.TorEndpoint
import org.yapyap.routing.router.RouterConfig
import org.yapyap.transport.tor.backend.TorBackendConfig
import org.yapyap.transport.webrtc.backend.WebRtcBackendConfig

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