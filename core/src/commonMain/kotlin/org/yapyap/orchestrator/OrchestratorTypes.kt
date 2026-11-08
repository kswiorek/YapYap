package org.yapyap.orchestrator

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
 * Local transport status: whether this device can talk to the network, and
 * whether our own onion address is dialable by others yet. Device-local (no
 * peer involved), so it is safe to surface on the [Orchestrator] itself —
 * including to headless relays, which have no runtime services.
 *
 * Messages sent in any state but [Online] are not lost: they wait in the
 * outbox and the peers' pull path, and go out once connected.
 */
public sealed interface LocalTransportStatus {
    /** The device has no usable network connection. */
    public data object NoNetwork : LocalTransportStatus

    /**
     * Tor is connecting to the network ([progress] 0–99 %). [problem] is set
     * while Tor reports trouble reaching relays; it often clears on its own, so
     * it is detail, not a verdict.
     */
    public data class Connecting(
        val progress: Int,
        val problem: TransportProblem?,
    ) : LocalTransportStatus

    /** Tor was connected but lost the network; it reconnects on its own. */
    public data object Reconnecting : LocalTransportStatus

    /**
     * Tor got stuck while the network is fine and is being fixed automatically
     * ([action] is the current step). [progress] is Tor's bootstrap percentage
     * while it reconnects, null once it is up again and only our address is
     * being re-proven.
     */
    public data class Recovering(
        val action: RecoveryAction,
        val progress: Int?,
        val problem: TransportProblem?,
    ) : LocalTransportStatus

    /**
     * Tor is connected, but our address isn't proven dialable yet: a fresh
     * descriptor (after every start or network change) takes a while to
     * propagate. Outbound bootstrap dials ride the slow retry path meanwhile.
     */
    public data object WarmingUp : LocalTransportStatus

    /** Connected and our address is dialable. */
    public data object Online : LocalTransportStatus

    /**
     * Connected, but our address could not be proven dialable within the
     * budget. Still retrying underneath, so this can resolve to [Online].
     */
    public data object Degraded : LocalTransportStatus
}

/** Why Tor is having trouble connecting. [detail] is diagnostic text (reason, relay address), not user copy. */
public data class TransportProblem(
    val kind: TransportProblemKind,
    val detail: String,
)

/** What automatic recovery is doing, cheapest first. None of it changes our address. */
public enum class RecoveryAction {
    /** Dropping and re-making Tor's network connections. */
    RECONNECTING,

    /** Restarting Tor. */
    RESTARTING,

    /** Restarting Tor with a fresh copy of the network directory. */
    REFRESHING_DIRECTORY,

    /** Restarting Tor with newly chosen entry relays (rare; at most once a day). */
    RESETTING_ENTRY_RELAYS,
}

public enum class TransportProblemKind {
    /** Tor can't reach the relays it tries (refused, no route, timeouts): a blocked or broken network. */
    RELAYS_UNREACHABLE,

    /** Anything else Tor reported. */
    OTHER,
}

/** Why boot stopped and the GUI must offer a reset. */
public enum class ResetReason {
    INCONSISTENT_STORAGE,
    SELF_BANNED,
    ONBOARDING_EXPIRED,
}

/** Onboarding lifecycle state, surfaced to the GUI. */
public enum class OnboardingState {
    /** No onboarding in progress (or already complete — the one-time secret is burned). */
    IDLE,

    /** This node is onboarding as a newcomer and waiting for the sponsor's intro. */
    AWAITING_INTRO,

    /**
     * Recovery path only: our recovery request is queued to the device we asked and
     * not yet confirmed. (On the invite path the sponsor contacts us instead, so
     * there is no request — that path stays in [AWAITING_INTRO].)
     */
    REQUEST_SENT,

    /** Recovery path only: the device we asked confirmed our request; waiting for its intro. */
    REQUEST_DELIVERED,

    /** The bootstrap intro was received and the global room is syncing. */
    SYNCING,

    /** The first fold containing the local device's own Add event landed; the secret is burned. */
    COMPLETE,

    /**
     * Deadline passed before completion; secret burned. Terminal: no in-place retry exists
     * (setup requires SetupRequired state; the sponsor already published our AddDevice, so
     * same-key retry would be an invalid duplicate; the burned secret can never open the
     * intro). Lands in `OrchestratorState.ResetRequired(ONBOARDING_EXPIRED)` — recovery is
     * `resetApp()` plus a fresh setup.
     */
    TIMED_OUT,
}
