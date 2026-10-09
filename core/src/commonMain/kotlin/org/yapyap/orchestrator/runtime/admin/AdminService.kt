package org.yapyap.orchestrator.runtime.admin

import kotlinx.coroutines.flow.StateFlow
import org.yapyap.crypto.identity.AccountId
import org.yapyap.orchestrator.runtime.globalevent.GlobalEventOutcome
import org.yapyap.protocol.PeerId

/**
 * Admin-gated control plane: thin fail-fast wrappers over the
 * GlobalEventProjector publish calls. The GUI gates admin UI on [localIsAdmin].
 * Self-service (own devices/account) lives in
 * [org.yapyap.orchestrator.runtime.account.AccountService].
 *
 * TODO: [Sprint 4] DefaultAdminService must refuse owner removals BEFORE any write
 * (the fold ignores them silently — stored VERIFIED, no shadow effect — so the
 * refusal is the GUI's only feedback):
 * - `revokeAdmin` / `removeAccount` targeting the owner account
 *   (`identityKeyRepository.isAccountOwner(target)`) → `OwnerIrrevocable`;
 * - `removeDevice` targeting a device of the owner account
 *   (`getAccountIdForDevice(target) == getOwnerAccountId()`) → `OwnerIrrevocable`.
 * The owner acts on their own account exclusively through the self-service paths.
 */
interface AdminService {
    /** Local account's admin flag, live. */
    val localIsAdmin: StateFlow<Boolean>

    suspend fun grantAdmin(target: AccountId): GlobalEventOutcome
    suspend fun revokeAdmin(target: AccountId): GlobalEventOutcome
    suspend fun removeAccount(target: AccountId): GlobalEventOutcome
    suspend fun removeDevice(target: PeerId): GlobalEventOutcome
}
