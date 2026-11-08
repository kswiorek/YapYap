package org.yapyap.wiring

import kotlinx.io.files.Path
import org.yapyap.orchestrator.NodeMode
import org.yapyap.orchestrator.Orchestrator

public expect class OrchestratorFactory(
    dataDirectory: Path,
    mode: NodeMode,
    loopback: LoopbackTransport = LoopbackTransport.NONE,
    keyringNamespace: String? = null,
) {
    public fun create(): Orchestrator
}

/**
 * Selects loopback stand-ins for the real network backends.
 *
 * Test/dev only: a loopback transport keeps the full encode/decode, routing, and E2EE
 * path intact but provides no anonymity and no transport-layer authenticity. Anything
 * above the transport (signatures, E2EE) still verifies exactly as in production.
 *
 * The GUI may pass this to [OrchestratorFactory] for local testing; production defaults
 * to [NONE].
 */
public enum class LoopbackTransport {
    /** Real backends (Tor + WebRTC). */
    NONE,

    /** Loopback Tor backend (localhost TCP); real WebRTC backend. */
    TOR,

    /** Real Tor backend; loopback WebRTC — not yet implemented. */
    WEBRTC,

    /** Loopback for both transports — WebRTC side not yet implemented. */
    ALL,
}
