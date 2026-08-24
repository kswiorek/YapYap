package org.yapyap.config

import kotlinx.serialization.Serializable
import org.yapyap.crypto.e2ee.CryptoSessionConfig
import org.yapyap.orchestrator.NodeMode
import org.yapyap.orchestrator.OrchestratorConfig
import org.yapyap.orchestrator.sync.SyncConfig
import org.yapyap.persistence.db.DeviceType
import org.yapyap.routing.router.RouterConfig
import org.yapyap.transport.tor.backend.TorBackendConfig
import org.yapyap.transport.webrtc.backend.WebRtcBackendConfig

// Frozen at boot, read from config file + platform factory
data class BootConfig(
    val mode: NodeMode = NodeMode.FULL_CLIENT,
    val localDeviceType: DeviceType = DeviceType.DESKTOP, // from platform factory, NOT a default
)

// Hot-reloadable, exposed as StateFlow<RuntimeConfig>
@Serializable
data class RuntimeConfig(
    val tor: TorBackendConfig = TorBackendConfig(),
    val webRtc: WebRtcBackendConfig = WebRtcBackendConfig(),
    val router: RouterConfig = RouterConfig(),
    val sync: SyncConfig = SyncConfig(),
    val crypto: CryptoSessionConfig = CryptoSessionConfig(),
    val orchestrator: OrchestratorConfig = OrchestratorConfig(),
)

// What the user is allowed to set. Mirrors userSettings.conf structure.
@Serializable
data class UserPreferences(
    val router: RouterUserPrefs = RouterUserPrefs(),
    val sync: SyncUserPrefs = SyncUserPrefs(),
    val pushToken: String? = null,
)

@Serializable
data class RouterUserPrefs(
    val outboxMaxSizeBytes: Long? = null,
    val ackLifetimeSeconds: Long? = null,
    // messageLifetimeSeconds ABSENT → network-controlled
)

@Serializable
data class SyncUserPrefs(
    val gracePeriodSeconds: Long? = null,
)

@Serializable
data class NetworkPolicy(
    val router: RouterNetworkPolicy = RouterNetworkPolicy(),
)

@Serializable
data class RouterNetworkPolicy(
    val messageLifetimeSeconds: Long? = null,
    val dedupRetentionSeconds: Long? = null,
)