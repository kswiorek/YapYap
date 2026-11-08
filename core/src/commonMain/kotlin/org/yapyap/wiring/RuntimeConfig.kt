package org.yapyap.wiring

import kotlinx.serialization.Serializable
import org.yapyap.crypto.e2ee.CryptoSessionConfig
import org.yapyap.orchestrator.OrchestratorConfig
import org.yapyap.routing.router.RouterConfig
import org.yapyap.transport.tor.backend.TorBackendConfig
import org.yapyap.transport.webrtc.backend.WebRtcBackendConfig

// Hot-reloadable, exposed as StateFlow<RuntimeConfig>
@Serializable
internal data class RuntimeConfig(
    val tor: TorBackendConfig = TorBackendConfig(),
    val webRtc: WebRtcBackendConfig = WebRtcBackendConfig(),
    val router: RouterConfig = RouterConfig(),
    val crypto: CryptoSessionConfig = CryptoSessionConfig(),
    val orchestrator: OrchestratorConfig = OrchestratorConfig(),
)
