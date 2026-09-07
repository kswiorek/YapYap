package org.yapyap.orchestrator

import org.yapyap.protocol.PeerId
import org.yapyap.protocol.TorEndpoint
import org.yapyap.protocol.envelopes.Invite

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
    /**
     * Genesis of a brand-new standalone network: provisions the local account + first device and,
     * once global events land, appends the root `AddAccount` (prevId == null — admin by definition,
     * §3 of the global-events doc). No sponsor invite is produced: the network sits in limbo until
     * another device joins.
     */
    data class Genesis(
        val accountName: String,
    ) : SetupIntent

    /**
     * Join an existing network with a brand-new account: provisions account + first device and
     * produces the sponsor invite (INVITE-flavor [org.yapyap.protocol.envelopes.Invite] carrying the account record).
     */
    data class NewAccountFirstDevice(
        val accountName: String,
    ): SetupIntent

    /**
     * Out-of-band bootstrap endpoint, supplied by the user for account recovery (and later for
     * composite-endpoint string encoding + the sponsor side): the mesh node's device id plus its
     * Tor endpoint. The peerId is required so the standard WRONG_TARGET check applies on the
     * responder; the endpoint is what the outbox dispatches to while the node has no local row
     * for the target. Enough — no full peer-row encoding needed.
     */
    data class BootstrapEndpoint(
        val peerId: PeerId,
        val torEndpoint: TorEndpoint,
    )

    /**
     * Recover an existing account on a fresh device using its recovery key: provisions account
     * (from the key) + a new device, then sends a RECOVERY_REQUEST to [bootstrapEndpoint] through
     * the outbox. A recovering device knows no peers, so the endpoint is mandatory.
     */
    data class ImportAccountRecoveryKey(
        val recoveryKey: String,
        val bootstrapEndpoint: BootstrapEndpoint,
    ): SetupIntent

    data object AddDeviceToExistingAccount: SetupIntent
}

data class SetupResult(
    /**
     * The newcomer's onboarding invite (INVITE-flavor [Invite]): encodes to the bytes the
     * GUI renders as a QR (or the CLI prints) and the sponsor decodes back. Non-null on the two join
     * paths ([SetupIntent.NewAccountFirstDevice] — new account, [SetupIntent.AddDeviceToExistingAccount] —
     * existing account); null for [SetupIntent.Genesis] (no one to scan it) and
     *   [SetupIntent.ImportAccountRecoveryKey] (direct-connect path via `bootstrapEndpoint`).
     * `invite.account == null` means the sponsor adds the device to its own account; otherwise the
     * sponsor also publishes the account via `AddAccount`.
     */
    val invite: Invite?,
    /** Non-null for the account-founding paths (Genesis, NewAccountFirstDevice) — the account signing key as a pasteable code. */
    val recoveryKey: String?,
)