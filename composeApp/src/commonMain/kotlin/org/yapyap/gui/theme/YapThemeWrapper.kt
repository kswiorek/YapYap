package org.yapyap.gui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable

/** Composition root for YapYap theming. */
@Composable
fun YapTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    // Accent ramp is theme-independent (YapAccent); only surfaces/text differ per
    // palette, so a new theme is a new YapColors instance reusing YapAccent.
    val colors = if (darkTheme) YapDarkColors else YapLightColors
    CompositionLocalProvider(
        LocalYapColors provides colors,
        LocalYapType provides YapTypeScale,
    ) {
        content()
    }
}

val yapColors: YapColors
    @Composable
    @ReadOnlyComposable
    get() = LocalYapColors.current

/**
 * Resolves [UiPreferences] `appearance.theme` ("dark" / "light" / "system")
 * against the OS setting. Unknown values fall back to dark.
 */
@Composable
fun resolveDarkTheme(themePref: String): Boolean = when (themePref) {
    "light" -> false
    "system" -> isSystemInDarkTheme()
    else -> true
}

val yapType: YapType
    @Composable
    @ReadOnlyComposable
    get() = LocalYapType.current
