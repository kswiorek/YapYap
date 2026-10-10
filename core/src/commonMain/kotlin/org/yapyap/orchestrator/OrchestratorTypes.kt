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