package org.yapyap.gui.screens.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.yapyap.gui.components.*
import org.yapyap.gui.copy.RefusalText
import org.yapyap.gui.session.ToastKind
import org.yapyap.gui.session.YapSession
import org.yapyap.gui.theme.YapRadius
import org.yapyap.gui.theme.yapColors
import org.yapyap.gui.theme.yapType
import org.yapyap.orchestrator.onboarding.OnboardingState
import org.yapyap.orchestrator.runtime.OrchestratorRuntime
import org.yapyap.orchestrator.runtime.onboarding.SponsorOutcome
import yapyap.composeapp.generated.resources.* // Res + generated string accessors

/**
 * S3 scan-mode, paste variant (camera deferred to the mobile milestone per the
 * implementation plan §1): the sponsor pastes the newcomer's base64 invite
 * code, optionally grants admin, and calls `sponsorNewcomer`.
 */
@Composable
fun SponsorDialog(
    runtime: OrchestratorRuntime,
    session: YapSession,
    onDismiss: () -> Unit,
) {
    val colors = yapColors
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var grantAdmin by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var shapeError by remember { mutableStateOf(false) }
    val addedMsg = stringResource(Res.string.toast_sponsor_added)
    val addedNoAdminMsg = stringResource(Res.string.toast_sponsor_added_no_admin)
    val failedMsg = stringResource(Res.string.toast_sponsor_failed)

    fun submit() {
        val bytes = Base64Encoder.decode(code)
        if (bytes == null) {
            // Local shape failure (not base64 at all): distinct from the core
            // MalformedInvite copy below, so a report tells us which layer fired.
            error = null
            shapeError = true
            return
        }
        working = true
        error = null
        shapeError = false
        scope.launch {
            try {
                when (val outcome = runtime.onboarding.sponsorNewcomer(bytes, grantAdmin)) {
                    is SponsorOutcome.Sponsored -> {
                        if (grantAdmin && !outcome.adminGranted) {
                            session.showToast(addedNoAdminMsg, ToastKind.Success)
                        } else {
                            session.showToast(addedMsg, ToastKind.Success)
                        }
                        onDismiss()
                    }

                    is SponsorOutcome.Refused ->
                        error = RefusalText.sponsor(outcome.reason)
                }
            } catch (e: Exception) {
                // Infra failure: input preserved so the user can retry.
                error = e.message ?: failedMsg
            } finally {
                working = false
            }
        }
    }

    ScrimOverlay(
        onDismiss = if (working) null else onDismiss,
        maxContentWidth = 480.dp,
        onConfirm = ::submit,
        confirmEnabled = code.isNotBlank() && !working,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(YapRadius.md))
                .background(colors.bgSurface)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { }
                .padding(20.dp),
        ) {
            BasicText(
                stringResource(Res.string.sponsor_title),
                style = yapType.headingXl.copy(color = colors.textStrong),
            )
            BasicText(
                stringResource(Res.string.sponsor_body),
                style = yapType.bodySm.copy(color = colors.textSubtle),
                modifier = Modifier.padding(top = 8.dp),
            )
            YapTextField(
                value = code,
                onValueChange = { code = it; error = null; shapeError = false },
                placeholder = stringResource(Res.string.sponsor_hint),
                singleLine = false,
                modifier = Modifier.padding(top = 12.dp),
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                YapSwitch(checked = grantAdmin, onCheckedChange = { grantAdmin = it })
                BasicText(
                    stringResource(Res.string.sponsor_grant_admin),
                    style = yapType.bodySm.copy(color = colors.textDefault),
                    modifier = Modifier.padding(start = 10.dp),
                )
            }
            BasicText(
                stringResource(Res.string.sponsor_grant_note),
                style = yapType.bodyXs.copy(color = colors.textMuted),
                modifier = Modifier.padding(top = 4.dp),
            )
            if (shapeError) InlineError(stringResource(Res.string.sponsor_bad_shape))
            error?.let { InlineError(it) }
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.End) {
                YapButton(
                    stringResource(Res.string.common_cancel),
                    onClick = onDismiss,
                    variant = YapButtonVariant.Secondary,
                    enabled = !working,
                    modifier = Modifier.padding(end = 8.dp),
                )
                YapButton(
                    if (working) stringResource(Res.string.sponsor_working) else stringResource(Res.string.sponsor_button),
                    onClick = ::submit,
                    enabled = code.isNotBlank() && !working,
                )
            }
        }
    }
}

/**
 * Newcomer-side progress banner (S1 step 2/3, live in the shell): the
 * orchestrator is already Running while the newcomer waits for the sponsor's
 * intro, so this renders from `runtime.onboarding.newcomerState`, not from
 * setup. Null (no banner) for IDLE/COMPLETE.
 *
 * The banner is clickable while [onShowCode] is non-null: it reopens the
 * invite overlay (code + future QR), so dismissing the overlay never strands
 * the join. The Cancel button stays independent — pressing it does not open
 * the overlay.
 */
@Composable
fun NewcomerStatusBanner(
    state: OnboardingState,
    onShowCode: (() -> Unit)?,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = yapColors
    val line = when (state) {
        OnboardingState.AWAITING_INTRO -> stringResource(Res.string.newcomer_waiting_sponsor)
        OnboardingState.REQUEST_SENT -> stringResource(Res.string.newcomer_sending)
        OnboardingState.REQUEST_DELIVERED -> stringResource(Res.string.newcomer_delivered)
        OnboardingState.SYNCING -> stringResource(Res.string.newcomer_syncing)
        OnboardingState.IDLE, OnboardingState.COMPLETE, OnboardingState.TIMED_OUT -> return
    }
    val showsInvite = state == OnboardingState.AWAITING_INTRO && onShowCode != null
    Row(
        modifier.fillMaxWidth().background(colors.bgSurfaceHigh)
            .then(if (showsInvite) Modifier.clickable { onShowCode?.invoke() } else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            BasicText(
                line,
                style = yapType.bodySm.copy(color = colors.textDefault),
            )
            when {
                showsInvite -> BasicText(
                    stringResource(Res.string.newcomer_tap_to_view),
                    style = yapType.bodyXs.copy(color = colors.textMuted),
                    modifier = Modifier.padding(top = 2.dp),
                )
                // Recovery path: there is no invite code at all, so never point at one.
                // Delivery can take minutes while Tor connects; the request keeps retrying.
                state == OnboardingState.REQUEST_SENT -> BasicText(
                    stringResource(Res.string.newcomer_sending_note),
                    style = yapType.bodyXs.copy(color = colors.textMuted),
                    modifier = Modifier.padding(top = 2.dp),
                )

                state == OnboardingState.AWAITING_INTRO -> {
                    // Invite path with no staged bytes: setup codes live only in
                    // process memory, so a restart (or an old Done) loses them while
                    // core's session persists. Never a dead end — say so and point
                    // at the way out (cancel → reset → fresh setup).
                    BasicText(
                        stringResource(Res.string.newcomer_no_code_saved),
                        style = yapType.bodyXs.copy(color = colors.warning),
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
        YapButton(
            stringResource(Res.string.common_cancel),
            onClick = onCancel,
            variant = YapButtonVariant.Secondary,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}
