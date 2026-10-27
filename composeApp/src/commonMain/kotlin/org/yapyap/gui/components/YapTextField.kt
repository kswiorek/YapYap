package org.yapyap.gui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import org.yapyap.gui.theme.YapRadius
import org.yapyap.gui.theme.yapColors
import org.yapyap.gui.theme.yapType

@Composable
fun YapTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    singleLine: Boolean = true,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    isError: Boolean = false,
) {
    val colors = yapColors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val border = when {
        focused -> colors.brand
        isError -> colors.danger
        else -> colors.borderNormal
    }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .clip(RoundedCornerShape(YapRadius.sm))
            .background(colors.bgApp.copy(alpha = 0.6f))
            .border(1.dp, border, RoundedCornerShape(YapRadius.sm))
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .fillMaxWidth(),
        textStyle = yapType.bodyMd.copy(color = colors.textDefault),
        cursorBrush = SolidColor(colors.textDefault),
        singleLine = singleLine,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        interactionSource = interaction,
        decorationBox = { inner ->
            if (value.isEmpty()) {
                androidx.compose.foundation.text.BasicText(
                    placeholder,
                    style = yapType.bodyMd.copy(color = colors.textPlaceholder),
                )
            }
            inner()
        },
    )
}

@Composable
fun InlineError(text: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.padding(top = 4.dp)) {
        androidx.compose.foundation.text.BasicText(
            text,
            style = yapType.bodyXs.copy(color = yapColors.danger),
        )
    }
}
