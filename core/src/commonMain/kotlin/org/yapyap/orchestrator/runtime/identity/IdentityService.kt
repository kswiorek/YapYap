package org.yapyap.orchestrator.runtime.identity

import kotlinx.coroutines.flow.StateFlow
import org.yapyap.protocol.AccountId

/**
 * Read-only GUI view over accounts and devices: the roster, per-account
 * presence labels, and the local account. Mutations live in
 * [org.yapyap.orchestrator.runtime.admin.AdminService] (admin-gated) and
 * [org.yapyap.orchestrator.runtime.account.AccountService] (self-service).
 */
public interface IdentityService {
    /** All known accounts (chain-derived + provisional), stable order, live. */
    public val accounts: StateFlow<List<AccountView>>

    /** The local account, or null pre-onboarding. */
    public val localAccount: StateFlow<AccountView?>

    /** Lookup for message rendering — GUI shows "unknown author" when null. */
    public suspend fun account(accountId: AccountId): AccountView?
}
