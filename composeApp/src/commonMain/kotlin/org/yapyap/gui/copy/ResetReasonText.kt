package org.yapyap.gui.copy

import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.stringResource
import org.yapyap.orchestrator.ResetReason
import yapyap.composeapp.generated.resources.Res
import yapyap.composeapp.generated.resources.reset_expired_body
import yapyap.composeapp.generated.resources.reset_expired_next
import yapyap.composeapp.generated.resources.reset_expired_title
import yapyap.composeapp.generated.resources.reset_inconsistent_body
import yapyap.composeapp.generated.resources.reset_inconsistent_next
import yapyap.composeapp.generated.resources.reset_inconsistent_title
import yapyap.composeapp.generated.resources.reset_removed_body
import yapyap.composeapp.generated.resources.reset_removed_next
import yapyap.composeapp.generated.resources.reset_removed_title

/**
 * ResetReason → user-facing copy. The backend returns typed values, never copy;
 * user copy lives here and only here (same rule as [RefusalText]). Composable:
 * copy comes from string resources so it localizes with the rest of the UI.
 */
object ResetReasonText {
    @Composable
    fun title(reason: ResetReason): String = when (reason) {
        ResetReason.SELF_BANNED -> stringResource(Res.string.reset_removed_title)
        ResetReason.INCONSISTENT_STORAGE -> stringResource(Res.string.reset_inconsistent_title)
        ResetReason.ONBOARDING_EXPIRED -> stringResource(Res.string.reset_expired_title)
    }

    @Composable
    fun body(reason: ResetReason): String = when (reason) {
        ResetReason.SELF_BANNED -> stringResource(Res.string.reset_removed_body)
        ResetReason.INCONSISTENT_STORAGE -> stringResource(Res.string.reset_inconsistent_body)
        ResetReason.ONBOARDING_EXPIRED -> stringResource(Res.string.reset_expired_body)
    }

    @Composable
    fun nextStep(reason: ResetReason): String = when (reason) {
        ResetReason.SELF_BANNED -> stringResource(Res.string.reset_removed_next)
        ResetReason.INCONSISTENT_STORAGE -> stringResource(Res.string.reset_inconsistent_next)
        ResetReason.ONBOARDING_EXPIRED -> stringResource(Res.string.reset_expired_next)
    }
}
