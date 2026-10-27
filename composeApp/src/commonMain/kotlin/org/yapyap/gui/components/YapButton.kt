package org.yapyap.gui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.yapyap.gui.theme.YapRadius
import org.yapyap.gui.theme.yapColors
import org.yapyap.gui.theme.yapType

enum class YapButtonVariant { Primary, Secondary, Danger, Ghost }

@Composable
fun YapButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: YapButtonVariant = YapButtonVariant.Primary,
    enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
) {
    val colors = yapColors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val bg = when (variant) {
        YapButtonVariant.Primary -> when {
            pressed -> colors.brandActive
            hovered -> colors.brandHover
            else -> colors.brand
        }

        YapButtonVariant.Secondary -> when {
            pressed -> colors.modActive
            hovered -> colors.modHover
            else -> colors.modSubtle
        }

        YapButtonVariant.Danger -> when {
            pressed -> colors.dangerHover
            hovered -> colors.danger
            else -> colors.danger
        }

        YapButtonVariant.Ghost -> when {
            pressed -> colors.modActive
            hovered -> colors.modHover
            else -> Color.Transparent
        }
    }
    val fg = when (variant) {
        YapButtonVariant.Ghost -> colors.textSubtle
        else -> colors.textStrong
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(YapRadius.sm))
            .background(bg.copy(alpha = if (enabled) 1f else 0.5f))
            .border(1.dp, Color(0x14FFFFFF), RoundedCornerShape(YapRadius.sm))
            .clickable(interactionSource = interaction, indication = null, enabled = enabled) { onClick() }
            .padding(contentPadding)
            .defaultMinSize(minHeight = 38.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(text, style = yapType.bodyMdMedium.copy(color = fg))
    }
}

@Composable
fun SoonChip(modifier: Modifier = Modifier) {
    val colors = yapColors
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(colors.modSubtle)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        BasicText(
            "SOON",
            style = yapType.bodyXsMedium.copy(
                color = colors.textMuted,
                letterSpacing = 0.4.sp,
            ),
        )
    }
}
