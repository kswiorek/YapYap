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
 */
interface AdminService {
    /** Local account's admin flag, live. */
    val localIsAdmin: StateFlow<Boolean>

    suspend fun grantAdmin(target: AccountId): GlobalEventOutcome
    suspend fun revokeAdmin(target: AccountId): GlobalEventOutcome
    suspend fun removeAccount(target: AccountId): GlobalEventOutcome
    suspend fun removeDevice(target: PeerId): GlobalEventOutcome
}
