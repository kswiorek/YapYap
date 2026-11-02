package org.yapyap

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.github.weisj.jsvg.parser.LoaderContext
import com.github.weisj.jsvg.parser.SVGLoader
import com.github.weisj.jsvg.parser.impl.InputStreamXMLInput
import com.github.weisj.jsvg.view.ViewBox
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Caption glyphs loaded from the OS icon theme at runtime, so a theme switch
 * changes the window buttons instead of them being hardcoded to Breeze.
 *
 * Lookup follows the freedesktop Icon Theme spec in the relevant parts: active
 * theme detection (KDE: `kdeglobals [Icons]`; GNOME: gsettings), per-theme
 * directory search (`actions/24 → 22 → 16`, the sizes a title bar needs),
 * `index.theme` inheritance, ending at `hicolor`. Files are rendered with jsvg
 * (lightweight, MIT, used by JetBrains/NetBeans/Eclipse) after normalizing
 * their paint to our runtime tint — symbolic icons use `currentColor` scheme
 * classes (Breeze) or hardcoded dark fills (Adwaita), neither of which is
 * visible on our dark bar as-is.
 *
 * Any failure (missing files, unparsable SVG) returns null and the caller
 * falls back to the bundled SVGs.
 */
fun themeIconName(kind: CaptionKind, isMaximized: Boolean): String = when (kind) {
    CaptionKind.Minimize -> "window-minimize"
    CaptionKind.MaximizeRestore -> if (isMaximized) "window-restore" else "window-maximize"
    CaptionKind.Close -> "window-close"
}

/** Rasterizes a theme icon to [px]×[px], or null when anything goes wrong. */
fun themeCaptionImage(iconName: String, tint: Color, px: Int): ImageBitmap? {
    if (px <= 0) return null
    val file = findIconFile(activeIconTheme(), iconName) ?: return null
    return runCatching {
        val prepared = prepareThemeSvg(file.readText(), tint.toHex())
        val factory = javax.xml.stream.XMLInputFactory.newFactory()
        factory.setProperty(javax.xml.stream.XMLInputFactory.SUPPORT_DTD, false)
        val document = SVGLoader().load(
            InputStreamXMLInput(factory, ByteArrayInputStream(prepared.toByteArray(Charsets.UTF_8))),
            null,
            LoaderContext.builder().build(),
        ) ?: return null
        val image = BufferedImage(px, px, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
            document.render(null, g, ViewBox(0f, 0f, px.toFloat(), px.toFloat()))
        } finally {
            g.dispose()
        }
        image.toComposeImageBitmap()
    }.getOrNull()
}

/**
 * Normalizes a theme SVG's paint to [tintHex] (`#rrggbb`): `currentColor`
 * references (Breeze scheme classes) plus the hardcoded dark fills Adwaita
 * uses. These four caption icons are monochrome, so no other paint survives —
 * anything else falls back to bundled icons anyway.
 */
internal fun prepareThemeSvg(svg: String, tintHex: String): String {
    var out = svg.replace("currentColor", tintHex)
    // Breeze scheme classes (`.ColorScheme-Text{color:#232629}`): take the tint too.
    out = colorPropertyRegex.replace(out, "color:$tintHex")
    for (dark in listOf("#2e3436", "#2e3434")) {
        out = out.replace(dark, tintHex, ignoreCase = true)
    }
    return out
}

private val colorPropertyRegex = Regex("""(?<!-)color\s*:\s*#[0-9a-fA-F]{6}""")

internal fun Color.toHex(): String {
    fun c(v: Float) = (v * 255f).toInt().coerceIn(0, 255)
    return "#%02x%02x%02x".format(c(red), c(green), c(blue))
}

private fun activeIconTheme(): String {
    // KDE Plasma: kdeglobals [Icons] Theme=...
    runCatching {
        val kdeglobals = File(System.getProperty("user.home"), ".config/kdeglobals")
        if (kdeglobals.isFile) {
            var inIcons = false
            for (line in kdeglobals.readLines()) {
                val t = line.trim()
                if (t.startsWith("[")) {
                    inIcons = t.equals("[Icons]", ignoreCase = true)
                } else if (inIcons && t.startsWith("Theme=")) {
                    return t.substringAfter("=").trim().ifBlank { "breeze" }
                }
            }
        }
    }
    // GNOME: gsettings org.gnome.desktop.interface icon-theme.
    runCatching {
        val p = ProcessBuilder("gsettings", "get", "org.gnome.desktop.interface", "icon-theme")
            .redirectErrorStream(true).start()
        if (p.waitFor(2, TimeUnit.SECONDS) && p.exitValue() == 0) {
            val v = p.inputReader().readText().trim().trim('\'')
            if (v.isNotBlank() && v != "@as []") return v
        }
    }
    return "breeze"
}

private val iconSearchDirs: List<File> by lazy {
    val home = System.getProperty("user.home")
    listOf(
        File(home, ".icons"),
        File(home, ".local/share/icons"),
        File("/usr/local/share/icons"),
        File("/usr/share/icons"),
    ).filter { it.isDirectory }
}

private val iconSubdirs = listOf("actions/24", "actions/22", "actions/16", "actions/symbolic", "symbolic/ui")

private fun findIconFile(theme: String, iconName: String): File? {
    val visited = mutableSetOf<String>()
    fun search(current: String): File? {
        if (!visited.add(current)) return null
        for (dir in iconSearchDirs) {
            val themeDir = File(dir, current)
            if (!themeDir.isDirectory) continue
            for (sub in iconSubdirs) {
                for (file in listOf(
                    File(themeDir, "$sub/$iconName.svg"),
                    File(themeDir, "$sub/$iconName-symbolic.svg")
                )) {
                    if (file.isFile) return file
                }
            }
            // Inherited themes (index.theme Inherits=...), then hicolor.
            val inherits = readInherits(File(themeDir, "index.theme")) + "hicolor"
            for (parent in inherits) {
                search(parent)?.let { return it }
            }
        }
        return null
    }
    return search(theme)
}

private fun readInherits(indexTheme: File): List<String> {
    if (!indexTheme.isFile) return emptyList()
    for (line in indexTheme.readLines()) {
        val t = line.trim()
        if (t.startsWith("Inherits=")) {
            return t.substringAfter("=").split(",").map { it.trim() }.filter { it.isNotEmpty() }
        }
    }
    return emptyList()
}
