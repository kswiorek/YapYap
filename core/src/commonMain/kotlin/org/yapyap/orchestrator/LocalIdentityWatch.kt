package org.yapyap.orchestrator

import kotlinx.coroutines.flow.*
import org.yapyap.orchestrator.boot.ResetReason
import org.yapyap.orchestrator.boot.ResetRequest
import org.yapyap.orchestrator.fold.global.IdentityStateChange
import org.yapyap.orchestrator.onboarding.OnboardingState
import org.yapyap.orchestrator.identity.IdentityReader

/**
 * Watches for the local identity becoming unusable while the node runs, as [resetRequests]:
 *  - a fold that tombstones our own device or account bans us (boot diagnosis covers bans
 *    found at start; this covers bans arriving while running);
 *  - a live onboarding timeout is terminal with no in-place retry (setup requires
 *    SetupRequired, and the same keys would duplicate our AddDevice).
 */
internal class LocalIdentityWatch(
    identityChanges: Flow<IdentityStateChange>,
    onboardingState: Flow<OnboardingState>,
    private val identityReader: IdentityReader,
) {
    val resetRequests: Flow<ResetRequest> = merge(
        identityChanges.mapNotNull { change ->
            if (removesLocalIdentity(change)) {
                ResetRequest(ResetReason.SELF_BANNED, "Fold tombstoned local identity ($change)")
            } else {
                null
            }
        },
        onboardingState
            .filter { it == OnboardingState.TIMED_OUT }
            .map {
                ResetRequest(
                    ResetReason.ONBOARDING_EXPIRED,
                    "Onboarding timed out; secret burned (retry requires fresh keys)",
                )
            },
    )

    private suspend fun removesLocalIdentity(change: IdentityStateChange): Boolean = when (change) {
        is IdentityStateChange.DeviceRemoved ->
            change.deviceId == identityReader.getLocalDeviceIdentity()?.deviceId

        is IdentityStateChange.AccountRemoved ->
            change.accountId == identityReader.getLocalAccountIdentity()?.accountId

        else -> false
    }
}
