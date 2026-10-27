package org.yapyap.gui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.yapyap.gui.theme.yapColors
import org.yapyap.protocol.AccountId

/**
 * Deterministic gradient avatar with initials (08-feature-readiness: temporary
 * pixel source, final geometry). 40 dp default; status dot drawn by caller.
 */
@Composable
fun Avatar(
    accountId: AccountId,
    displayName: String,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
) {
    val (c1, c2) = remember(accountId) { gradientFor(accountId.id) }
    val initials = remember(displayName) { initialsOf(displayName) }
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(Brush.linearGradient(listOf(c1, c2))),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            initials,
            style = androidx.compose.ui.text.TextStyle(
                color = Color.White,
                fontSize = (size.value * 0.38f).toSp(),
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            ),
        )
    }
}

@Composable
fun StatusDot(
    online: Boolean?,
    modifier: Modifier = Modifier,
    size: Dp = 12.dp,
) {
    val colors = yapColors
    val fill = when (online) {
        true -> colors.statusOnline
        false -> colors.statusOffline
        null -> Color.Transparent
    }
    val ring = when (online) {
        null -> colors.statusOffline
        else -> null
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(fill)
            .then(if (ring != null) Modifier.background(circleRingBrush(ring)) else Modifier),
    )
}

private fun circleRingBrush(color: Color): Brush = Brush.radialGradient(
    0.0f to color.copy(alpha = 0f),
    0.55f to color.copy(alpha = 0f),
    0.6f to color,
    1.0f to color,
)

private fun gradientFor(seed: String): Pair<Color, Color> {
    val palette = listOf(
        0xFF5865F2 to 0xFF9B5CF6,
        0xFF3BA55D to 0xFF2D7D46,
        0xFFED4245 to 0xFF9B2C2C,
        0xFFFEA02A to 0xFFC26E0A,
        0xFF57F287 to 0xFF1F8A4C,
        0xFF1ABC9C to 0xFF0E7461,
        0xFF3498DB to 0xFF1D5F8A,
        0xFFE91E63 to 0xFF8E1240,
    )
    val idx = (seed.fold(0) { acc, c -> acc * 31 + c.code } and 0x7FFFFFFF) % palette.size
    val (a, b) = palette[idx]
    return Color(a) to Color(b)
}

private fun initialsOf(name: String): String {
    val parts = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (parts.isEmpty()) return "?"
    val first = parts.first().first().uppercaseChar()
    val second = if (parts.size > 1) parts[1].first().uppercaseChar().toString() else ""
    return "$first$second"
}

private fun Float.toSp(): androidx.compose.ui.unit.TextUnit =
    androidx.compose.ui.unit.TextUnit(value = this, type = androidx.compose.ui.unit.TextUnitType.Sp)
