package org.yapyap.orchestrator.runtime.admin

import kotlinx.coroutines.flow.StateFlow
import org.yapyap.crypto.identity.AccountId
import org.yapyap.orchestrator.runtime.globalevent.GlobalEventOutcome
import org.yapyap.protocol.PeerId

/**
 * Admin-gated control plane: thin fail-fast wrappers over the
 * GlobalEventProjector publish calls. The GUI gates admin UI on [localIsAdmin]
 * (live off the projector's stateChanges; false until the service is started).
 * Self-service (own devices/account) lives in
 * [org.yapyap.orchestrator.runtime.account.AccountService].
 *
 * Every mutator refuses BEFORE any write — the fold silently ignores
 * authorization-invalid events (stored VERIFIED, no shadow effect), so these
 * pre-checks are the GUI's only refusal feedback; generic throws are reserved
 * for infrastructure failures. Owner targets are irrevocable
 * (`OwnerIrrevocable`): the owner is the network's repair path and acts on
 * their own account exclusively through the self-service paths. Admin ops
 * targeting the local account's own rows are refused (`SelfServiceRequired`) —
 * the self-service paths carry gates the admin path cannot mirror. Provisional
 * (not-yet-folded) targets are refused `NotReady` — the fold evaluates them
 * against shadow state that doesn't contain them yet.
 */
interface AdminService {
    /** Local account's admin flag, live (OWNER implies admin). */
    val localIsAdmin: StateFlow<Boolean>

    /** Grant admin to an ACTIVE MEMBER account (`TargetNotEligible` otherwise). */
    suspend fun grantAdmin(target: AccountId): GlobalEventOutcome

    /** Revoke admin from an ACTIVE ADMIN account; self step-down is legal.
     *  The owner is irrevocable (`OwnerIrrevocable`). */
    suspend fun revokeAdmin(target: AccountId): GlobalEventOutcome

    /** Remove (ban) another account, cascade-banning its devices. Never the
     *  local account (`SelfServiceRequired`) or the owner (`OwnerIrrevocable`). */
    suspend fun removeAccount(target: AccountId): GlobalEventOutcome

    /** Remove (ban) another account's device. Never the local account's
     *  devices (`SelfServiceRequired`) or the owner's (`OwnerIrrevocable`). */
    suspend fun removeDevice(target: PeerId): GlobalEventOutcome
}
