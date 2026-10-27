package org.yapyap.gui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import org.yapyap.gui.theme.yapColors
import org.yapyap.gui.theme.yapType

@Composable
fun YapSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = yapColors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val track = when {
        checked && hovered -> colors.brandHover
        checked -> colors.brand
        else -> colors.bgAccent
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(track.copy(alpha = if (enabled) 1f else 0.5f))
            .border(1.dp, colors.borderNormal, RoundedCornerShape(12.dp))
            .clickable(interactionSource = interaction, indication = null, enabled = enabled) {
                onCheckedChange(!checked)
            }
            .size(width = 40.dp, height = 24.dp)
            .padding(4.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .size(16.dp)
                .clip(CircleShape)
                .background(colors.textStrong),
        )
    }
}

@Composable
fun SettingsRow(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    control: @Composable () -> Unit,
) {
    Row(
        modifier = modifier.padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
            BasicText(title, style = yapType.bodySmMedium.copy(color = yapColors.textDefault))
            if (description.isNotBlank()) {
                BasicText(
                    description,
                    style = yapType.bodyXs.copy(color = yapColors.textMuted),
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        control()
    }
}
