package org.yapyap

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.painterResource
import yapyap.composeapp.generated.resources.Res
import yapyap.composeapp.generated.resources.caption_breeze_close
import yapyap.composeapp.generated.resources.caption_breeze_maximize
import yapyap.composeapp.generated.resources.caption_breeze_minimize
import yapyap.composeapp.generated.resources.caption_breeze_restore
import yapyap.composeapp.generated.resources.caption_linux_close
import yapyap.composeapp.generated.resources.caption_linux_maximize
import yapyap.composeapp.generated.resources.caption_linux_minimize
import yapyap.composeapp.generated.resources.caption_linux_restore
import yapyap.composeapp.generated.resources.caption_mac_close
import yapyap.composeapp.generated.resources.caption_mac_minimize
import yapyap.composeapp.generated.resources.caption_mac_zoom
import yapyap.composeapp.generated.resources.caption_win_close
import yapyap.composeapp.generated.resources.caption_win_maximize
import yapyap.composeapp.generated.resources.caption_win_minimize
import yapyap.composeapp.generated.resources.caption_win_restore

/**
 * Window caption icons as SVG resources (`jvmMain/composeResources/drawable/`),
 * rendered by Compose itself — no hand-drawn canvas paths, no extra renderer
 * dependency. The set is picked for the running desktop at runtime:
 *
 * - Windows: Win11-style thin strokes,
 * - Plasma (KDE session): glyphs load from the active icon theme at runtime
 *   (`window-*-symbolic`, freedesktop lookup with inheritance) and redraw from
 *   the theme's path geometry on every theme switch — nothing is hardcoded to
 *   Breeze. The bundled Breeze-style SVGs are fallback only, for when the
 *   theme files are missing or use unsupported path commands.
 * - other Linux (GNOME etc.): Adwaita `window-*-symbolic` metrics,
 * - macOS: traffic lights.
 *
 * Monochrome glyphs are authored black and tinted at draw time, so hover
 * states stay dynamic. `--native-titlebar` remains the escape hatch for the
 * real OS frame.
 */
enum class CaptionPlatform { Mac, Windows, Other }

val captionPlatform: CaptionPlatform by lazy {
    val os = System.getProperty("os.name", "").lowercase()
    when {
        "mac" in os || "darwin" in os -> CaptionPlatform.Mac
        "win" in os -> CaptionPlatform.Windows
        else -> CaptionPlatform.Other
    }
}

enum class CaptionKind { Minimize, MaximizeRestore, Close }

/** True on a Plasma session (checked once): Breeze is the active icon theme there. */
private val isKdeSession: Boolean by lazy {
    (System.getenv("XDG_CURRENT_DESKTOP") ?: "").lowercase().contains("kde") ||
            (System.getenv("DESKTOP_SESSION") ?: "").lowercase().contains("plasma")
}

@Composable
fun CaptionGlyph(kind: CaptionKind, isMaximized: Boolean, tint: Color) {
    // Plasma: rasterize the theme file itself (updates with the user's theme
    // switch); bundled Breeze-style SVGs only as fallback.
    if (captionPlatform == CaptionPlatform.Other && isKdeSession) {
        val density = LocalDensity.current
        val px = with(density) { glyphSize.roundToPx() }
        val bitmap = remember(kind, isMaximized, tint, px) {
            runCatching { themeCaptionImage(themeIconName(kind, isMaximized), tint, px) }.getOrNull()
        }
        if (bitmap != null) {
            Image(bitmap, contentDescription = null, modifier = Modifier.size(glyphSize))
            return
        }
    }
    val res = when (captionPlatform) {
        CaptionPlatform.Windows -> when (kind) {
            CaptionKind.Minimize -> Res.drawable.caption_win_minimize
            CaptionKind.MaximizeRestore ->
                if (isMaximized) Res.drawable.caption_win_restore else Res.drawable.caption_win_maximize

            CaptionKind.Close -> Res.drawable.caption_win_close
        }

        CaptionPlatform.Other if isKdeSession -> when (kind) {
            CaptionKind.Minimize -> Res.drawable.caption_breeze_minimize
            CaptionKind.MaximizeRestore ->
                if (isMaximized) Res.drawable.caption_breeze_restore else Res.drawable.caption_breeze_maximize

            CaptionKind.Close -> Res.drawable.caption_breeze_close
        }

        else -> when (kind) {
            CaptionKind.Minimize -> Res.drawable.caption_linux_minimize
            CaptionKind.MaximizeRestore ->
                if (isMaximized) Res.drawable.caption_linux_restore else Res.drawable.caption_linux_maximize

            CaptionKind.Close -> Res.drawable.caption_linux_close
        }
    }
    Icon(
        painterResource(res),
        contentDescription = null,
        tint = tint,
        // Win11 glyphs stay 10 dp (native size); the Linux sets render at 16 dp.
        modifier = Modifier.size(if (captionPlatform == CaptionPlatform.Windows) 10.dp else glyphSize),
    )
}

/** Glyph size for the Linux sets: 16 dp matches IntelliJ and other native bars. */
private val glyphSize = 16.dp

/** macOS traffic lights: red close / yellow minimize / green zoom, 12 dp. */
@Composable
fun TrafficLights(
    onClose: () -> Unit,
    onMinimize: () -> Unit,
    onZoom: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        LightDot(Res.drawable.caption_mac_close, onClose)
        LightDot(Res.drawable.caption_mac_minimize, onMinimize)
        LightDot(Res.drawable.caption_mac_zoom, onZoom)
    }
}

@Composable
private fun LightDot(icon: org.jetbrains.compose.resources.DrawableResource, onClick: () -> Unit) {
    Box(
        Modifier.padding(end = 8.dp).size(12.dp)
            .clip(CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(icon),
            contentDescription = null,
            tint = Color.Unspecified,
            modifier = Modifier.size(12.dp),
        )
    }
}
