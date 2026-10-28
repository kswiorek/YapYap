package org.yapyap.gui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.yapyap.gui.session.ToastKind
import org.yapyap.gui.session.ToastMessage
import org.yapyap.gui.theme.YapRadius
import org.yapyap.gui.theme.yapColors
import org.yapyap.gui.theme.yapType

/**
 * In-window modal scrim (replaces `Dialog` everywhere). Desktop `Dialog` opens a
 * separate, application-modal OS window: the main window can't resize while one is
 * open and the panel sizes itself once. An overlay Box lives in the main window, so
 * window-relative sizing stays live and resizing keeps working.
 *
 * `onDismiss == null` means explicit-dismiss only (W4 recovery key): no scrim-click
 * or Esc dismissal. Key handling uses the tunnel phase, so Esc reaches the scrim
 * from any focused child — no focus bookkeeping needed.
 *
 * Sizing: cards get an EXPLICIT width (`min(window − 32, maxContentWidth)`).
 * `fillMaxWidth() + widthIn(max)` does NOT cap — the fill's min-width wins and the
 * card goes full-bleed. Small cards always pass maxContentWidth; only settings
 * (its own overlay) spans near-full-window.
 */
@Composable
fun ScrimOverlay(
    onDismiss: (() -> Unit)?,
    modifier: Modifier = Modifier,
    maxContentWidth: Dp = Dp.Unspecified,
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(
        modifier.fillMaxSize()
            .background(yapColors.scrim)
            .then(if (onDismiss != null) Modifier.clickable { onDismiss() } else Modifier)
            .onPreviewKeyEvent { ev ->
                if (onDismiss != null && ev.key == Key.Escape) {
                    onDismiss()
                    true
                } else false
            },
        contentAlignment = contentAlignment,
    ) {
        if (maxContentWidth != Dp.Unspecified) {
            Box(Modifier.width((maxWidth - 32.dp).coerceAtMost(maxContentWidth))) {
                content()
            }
        } else {
            content()
        }
    }
}

@Composable
fun ToastHost(message: ToastMessage?, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    if (message == null) return
    val colors = yapColors
    val accent = when (message.kind) {
        ToastKind.Info -> colors.info
        ToastKind.Success -> colors.positive
        ToastKind.Error -> colors.danger
    }
    // Overlay slot: caller aligns BottomCenter inside the shell Box, so the toast
    // floats 24 dp above the bottom edge (W16) instead of taking layout space.
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
        Box(
            Modifier
                .padding(bottom = 24.dp)
                .widthIn(max = 280.dp)
                .clip(RoundedCornerShape(YapRadius.sm))
                .background(colors.bgSurfaceHigh)
                .clickable { onDismiss() }
                .padding(start = 12.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
        ) {
            BasicText(message.text, style = yapType.bodySm.copy(color = colors.textDefault))
        }
        @Suppress("UNUSED_EXPRESSION")
        accent
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    dangerous: Boolean = true,
    requireTypedText: String? = null,
    typedText: String = "",
    onTypedTextChange: (String) -> Unit = {},
) {
    val colors = yapColors
    // Same semantics as the old Dialog version: scrim-click and Esc cancel (W15).
    ScrimOverlay(onDismiss = onCancel, maxContentWidth = 442.dp) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(YapRadius.md))
                .background(colors.bgSurface)
                // Swallow clicks: without a consumer, presses on non-clickable
                // card areas bubble to the scrim and would cancel.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { }
                .padding(20.dp),
        ) {
            BasicText(title, style = yapType.headingLg.copy(color = colors.textStrong))
            BasicText(
                body,
                style = yapType.bodySm.copy(color = colors.textSubtle),
                modifier = Modifier.padding(top = 8.dp),
            )
            if (requireTypedText != null) {
                YapTextField(
                    value = typedText,
                    onValueChange = onTypedTextChange,
                    placeholder = "Type \"$requireTypedText\" to confirm",
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 16.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                YapButton(
                    "Cancel",
                    onClick = onCancel,
                    variant = YapButtonVariant.Secondary,
                    modifier = Modifier.padding(end = 8.dp),
                )
                YapButton(
                    confirmLabel,
                    onClick = onConfirm,
                    variant = if (dangerous) YapButtonVariant.Danger else YapButtonVariant.Primary,
                    enabled = requireTypedText == null || typedText == requireTypedText,
                )
            }
        }
    }
}

@Composable
fun CenterColumn(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    // Full-screen centred `max-width: 480px` column with scroll (W3/W17):
    // short windows scroll instead of clipping, wide windows don't stretch.
    Box(
        modifier.fillMaxSize().background(yapColors.bgApp).padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.fillMaxWidth().widthIn(max = 480.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) { content() }
    }
}
