package org.yapyap.orchestrator.runtime.identity

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.yapyap.orchestrator.OrchestratorConfig
import org.yapyap.orchestrator.fold.global.GlobalEventProjector
import org.yapyap.orchestrator.identity.Account
import org.yapyap.orchestrator.identity.IdentityReader
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.IdentityStatus
import org.yapyap.routing.router.AccountReach
import org.yapyap.routing.router.Router

/**
 * Read-only GUI roster over the chain-derived identity tables: accounts with
 * their devices, account-level presence, and the local account. No mutations,
 * no pre-checks, no outcomes — pure reads assembled from the identity rows,
 * refreshed off the projector's stateChanges (roster), the router's
 * onlineAccounts (presence flips), and a slow ticker (silent lastSeen aging —
 * markSeen emits nothing).
 *
 * Provisional rows are included unflagged (the established-node invariant says
 * they don't exist outside onboarding); BANNED rows stay visible with their
 * status — the admin UI and message rendering both need them.
 */
internal class DefaultIdentityService(
    private val identityReader: IdentityReader,
    private val router: Router,
    private val projector: GlobalEventProjector,
    private val orchestratorConfig: StateFlow<OrchestratorConfig>,
) : IdentityService {

    private val _accounts = MutableStateFlow<List<AccountView>>(emptyList())
    override val accounts: StateFlow<List<AccountView>> = _accounts.asStateFlow()

    private val _localAccount = MutableStateFlow<AccountView?>(null)
    override val localAccount: StateFlow<AccountView?> = _localAccount.asStateFlow()

    private val _onlineAccounts = MutableStateFlow<Set<AccountId>>(emptySet())

    private var collectJob: Job? = null

    fun start(scope: CoroutineScope) {
        if (collectJob?.isActive == true) return
        collectJob = scope.launch {
            refresh()
            launch {
                router.onlineAccounts.collect { online ->
                    _onlineAccounts.value = online
                    refresh()
                }
            }
            launch { projector.stateChanges.collect { refresh() } }
            // Silence otherwise: lastSeen ages without emitting, so the
            // "last seen X ago" labels would drift without a periodic re-read.
            // The interval is re-read so hot config reloads take effect.
            launch {
                while (true) {
                    delay(orchestratorConfig.value.identityRefreshInterval)
                    refresh()
                }
            }
        }
    }

    fun stop() {
        collectJob?.cancel()
        collectJob = null
    }

    override suspend fun account(accountId: AccountId): AccountView? {
        val row = identityReader.getAllAccounts().firstOrNull { it.accountId == accountId } ?: return null
        val reach = router.accountReach(listOf(accountId))[accountId]
        return viewOf(row, _onlineAccounts.value, reach)
    }

    private suspend fun refresh() {
        val online = _onlineAccounts.value
        val rows = identityReader.getAllAccounts()
        val reach = router.accountReach(rows.map { it.accountId })
        val views = rows
            .map { row -> viewOf(row, online, reach[row.accountId]) }
            .sortedWith(compareBy({ it.displayName }, { it.accountId.id }))
        _accounts.value = views
        _localAccount.value = views.firstOrNull { it.isLocal }
    }

    private fun viewOf(
        row: Account,
        online: Set<AccountId>,
        reach: AccountReach?,
    ): AccountView {
        val deviceViews = row.devices
            .map {
                DeviceView(
                    deviceId = it.deviceId,
                    torEndpoint = when {
                        it.status == IdentityStatus.BANNED -> null
                        // This device's own endpoint is what Tor published, not the declared one.
                        it.isLocal -> runCatching { router.localTorEndpoint() }.getOrDefault(it.declaredEndpoint)
                        else -> it.declaredEndpoint
                    },
                    deviceType = it.deviceType,
                    status = it.status,
                    isLocal = it.isLocal,
                    provisional = it.provisional,
                )
            }
            .sortedBy { it.deviceId.id }
        val seen = reach?.lastSeen
        val label = when {
            // The running app is its own presence proof — self-presence is not
            // peer presence, so the router never reports the local account.
            row.isLocal || row.accountId in online -> AvailabilityLabel.ONLINE
            seen != null -> AvailabilityLabel.OFFLINE
            else -> AvailabilityLabel.UNKNOWN
        }
        return AccountView(
            accountId = row.accountId,
            displayName = row.displayName,
            role = row.role,
            status = row.status,
            isLocal = row.isLocal,
            availability = AccountAvailability(label, seen),
            devices = deviceViews,
        )
    }
}
