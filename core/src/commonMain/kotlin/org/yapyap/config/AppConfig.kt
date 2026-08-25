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