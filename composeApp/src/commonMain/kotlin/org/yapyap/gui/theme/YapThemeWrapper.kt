package org.yapyap.gui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable

/** Composition root for YapYap theming. Dark-only for v1 (light = stub). */
@Composable
fun YapTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    // Only the dark palette exists today; the parameter keeps the seam for light theme.
    val colors = if (darkTheme) YapDarkColors else YapDarkColors
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

val yapType: YapType
    @Composable
    @ReadOnlyComposable
    get() = LocalYapType.current
