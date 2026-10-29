package org.yapyap.orchestrator

import org.yapyap.orchestrator.boot.ResetReason
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.TorEndpoint

public sealed interface OrchestratorState {
    public data object Created : OrchestratorState
    public data object SetupRequired : OrchestratorState
    public data class ResetRequired(val reason: ResetReason, val details: String = "") : OrchestratorState
    public data object Starting : OrchestratorState
    public data object Running : OrchestratorState
    public data object Stopping : OrchestratorState
    public data object Stopped : OrchestratorState
    public data class Failed(val cause: Throwable) : OrchestratorState
}

public enum class NodeMode {
    FULL_CLIENT,   // GUI app
    HEADLESS_RELAY // Pi relay; no UI, possibly slimmer surface
}

public sealed interface SetupIntent {
    /**
     * Genesis of a brand-new standalone network: provisions the local account + first device and,
     * once global events land, appends the root `AddAccount` (prevId == null — admin by definition,
     * §3 of the global-events doc). No sponsor invite is produced: the network sits in limbo until
     * another device joins.
     */
    public data class Genesis(
        val accountName: String,
    ) : SetupIntent

    /**
     * Join an existing network with a brand-new account: provisions account + first device and
     * produces the sponsor invite bytes (carrying the account record) for the sponsor to scan.
     */
    public data class NewAccountFirstDevice(
        val accountName: String,
    ) : SetupIntent

    /**
     * Recover an existing account on a fresh device using its recovery key: provisions account
     * (from the key) + a new device, then sends a RECOVERY_REQUEST to [bootstrapEndpoint] through
     * the outbox. A recovering device knows no peers, so the endpoint is mandatory.
     */
    public data class ImportAccountRecoveryKey(
        val recoveryKey: String,
        val bootstrapEndpoint: BootstrapEndpoint,
    ) : SetupIntent

    public data object AddDeviceToExistingAccount : SetupIntent
}

/**
 * Out-of-band bootstrap endpoint, supplied by the user for account recovery (and later for
 * composite-endpoint string encoding + the sponsor side): the mesh node's device id plus its
 * Tor endpoint. The peerId is required so the standard WRONG_TARGET check applies on the
 * responder; the endpoint is what the outbox dispatches to while the node has no local row
 * for the target. Enough — no full peer-row encoding needed.
 */
public data class BootstrapEndpoint(
    val peerId: PeerId,
    val torEndpoint: TorEndpoint,
)

public data class SetupResult(
    /**
     * The newcomer's onboarding invite, encoded: the GUI renders these bytes as a QR
     * (or the CLI prints them) and the sponsor side decodes them back. Opaque — the
     * GUI never inspects the contents. Non-null on the two join paths
     * ([SetupIntent.NewAccountFirstDevice] — new account,
     * [SetupIntent.AddDeviceToExistingAccount] — existing account); null for
     * [SetupIntent.Genesis] (no one to scan it) and
     *   [SetupIntent.ImportAccountRecoveryKey] (direct-connect path via `bootstrapEndpoint`).
     */
    val inviteBytes: ByteArray?,
    /** Non-null for the account-founding paths (Genesis, NewAccountFirstDevice) — the account signing key as a pasteable code. */
    val recoveryKey: String?,
)

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

/**
 * Local Tor publish readiness: whether our own onion address is dialable by
 * the rest of the network yet. Device-local (no peer involved), so it is safe
 * to surface on the [Orchestrator] itself — including to headless relays, which
 * have no runtime services.
 *
 * A fresh onion (first boot, or any restart before its descriptor is
 * re-uploaded) is unresolvable for minutes: descriptor upload to an HSDir plus
 * propagation. During [WARMING_UP] outbound bootstrap dials fail with
 * host-unreachable and ride the slow retry path; the GUI should say so instead
 * of showing a silent spinner.
 */
public enum class LocalTransportState {
    /** Tor is up and our hidden service is added, but no descriptor upload observed yet. */
    WARMING_UP,

    /** An HSDir accepted our descriptor (or loopback transport came up) — dialable. */
    PUBLISHED,

    /**
     * Gave up waiting for a descriptor upload while Tor stayed up
     * (see `TorBackendConfig.publishTimeout`). Check connectivity; the node
     * keeps retrying underneath, so this can still resolve to [PUBLISHED].
     */
    DEGRADED,
}