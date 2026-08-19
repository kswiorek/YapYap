package org.yapyap.orchestrator.config

import kotlinx.coroutines.flow.StateFlow
import org.yapyap.orchestrator.NodeMode
import org.yapyap.orchestrator.sync.SyncConfig
import org.yapyap.persistence.db.DeviceType
import org.yapyap.routing.router.RouterConfig
import org.yapyap.transport.tor.backend.TorBackendConfig
import org.yapyap.transport.webrtc.backend.WebRtcBackendConfig

data class AppConfig(
    val boot: BootConfig,
    val runtime: StateFlow<RuntimeConfig>,
    val userPreferences: StateFlow<UserPreferences>,
    val networkPolicy: StateFlow<NetworkPolicy>,
)
// Frozen at boot, read from config file + platform factory
data class BootConfig(
    val mode: NodeMode,
    val localDeviceType: DeviceType, // from platform factory, NOT a default
)

// Hot-reloadable, exposed as StateFlow<RuntimeConfig>
data class RuntimeConfig(
    val tor: TorBackendConfig,
    val webRtc: WebRtcBackendConfig,
    val router: RouterConfig,
    val sync: SyncConfig,
    val maintenanceIntervalSeconds: Long,
    val dedupRetentionSeconds: Long,
)

// Per-user, stored in DB/prefs, surfaced to GUI, mutable at any time
data class UserPreferences(
    val pushToken: String?,
//    val notificationSettings: ...,
//    // etc
)

// Network-wide, received via global control room system events
data class NetworkPolicy(
    val maxMessageSizeBytes: Long,
    val retentionSeconds: Long,
)