package org.yapyap.gui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import org.yapyap.gui.session.ToastKind
import org.yapyap.gui.session.ToastMessage
import org.yapyap.gui.theme.YapRadius
import org.yapyap.gui.theme.yapColors
import org.yapyap.gui.theme.yapType

@Composable
fun ToastHost(message: ToastMessage?, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    if (message == null) return
    val colors = yapColors
    val accent = when (message.kind) {
        ToastKind.Info -> colors.info
        ToastKind.Success -> colors.positive
        ToastKind.Error -> colors.danger
    }
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
        Box(
            Modifier
                .padding(bottom = 24.dp)
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
    Dialog(onDismissRequest = onCancel) {
        Column(
            Modifier
                .clip(RoundedCornerShape(YapRadius.md))
                .background(colors.bgSurface)
                .padding(20.dp)
                .fillMaxWidth(),
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
            androidx.compose.foundation.layout.Row(
                Modifier.fillMaxWidth().padding(top = 16.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End,
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
    Box(
        modifier.fillMaxSize().background(yapColors.bgApp).padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.fillMaxWidth(0.9f),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) { content() }
    }
}
