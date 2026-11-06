package org.yapyap.gui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.painterResource
import org.yapyap.gui.theme.YapAccent
import org.yapyap.gui.theme.yapColors
import yapyap.composeapp.generated.resources.Res
import yapyap.composeapp.generated.resources.yap_mark
import yapyap.composeapp.generated.resources.yap_mark_mono

/**
 * Full-color brand mark: white yap on Yap pink. For brand moments (splash,
 * empty states). The glyph vector carries no disc — the pink comes from here,
 * so the asset stays two files, not N recolored copies.
 */
@Composable
fun YapLogo(
    size: Dp = 64.dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier.size(size).clip(CircleShape).background(YapAccent.pink),
        contentAlignment = Alignment.Center,
    ) {
        Image(painterResource(Res.drawable.yap_mark), null, Modifier.fillMaxSize())
    }
}

/**
 * Monochrome mark: yap glyph on a theme surface, never pure black.
 *
 * The source icon is white-on-black / black-on-white; on our grey ladder the
 * dark side becomes a theme grey ([background], default [YapColors.bgRaised])
 * and the light side becomes [foreground] (default [YapColors.textStrong]),
 * per the "no pure black backgrounds" rule. For small, quiet placements
 * (window title, headers).
 */
@Composable
fun YapMark(
    size: Dp = 20.dp,
    modifier: Modifier = Modifier,
    background: Color = yapColors.bgRaised,
    foreground: Color = yapColors.textStrong,
) {
    Box(
        modifier.size(size).clip(CircleShape).background(background),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(Res.drawable.yap_mark_mono),
            contentDescription = null,
            tint = foreground,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
