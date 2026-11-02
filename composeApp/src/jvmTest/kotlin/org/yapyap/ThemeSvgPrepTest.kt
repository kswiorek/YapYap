package org.yapyap

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ThemeSvgPrepTest {

    @Test
    fun currentColor_replacedWithTint() {
        val svg = """<svg viewBox="0 0 16 16"><style>.ColorScheme-Text{color:#232629;}</style>""" +
                """<path d="m4 4 8 8" fill="currentColor"/></svg>"""
        val out = prepareThemeSvg(svg, "#aabbcc")
        assertEquals(-1, out.indexOf("currentColor"))
        assertEquals(-1, out.indexOf("#232629"))
    }

    @Test
    fun adwaitaHardcodedFill_replacedWithTint() {
        val svg = """<svg viewBox="0 0 16 16"><path d="m4 4h8" fill="#2e3436"/></svg>"""
        assertEquals(-1, prepareThemeSvg(svg, "#aabbcc").indexOf("#2e3436", ignoreCase = true))
    }

    @Test
    fun otherPaint_leftAlone() {
        val svg = """<svg viewBox="0 0 16 16"><path d="m4 4h8" fill="#ff0000"/></svg>"""
        assertFalse("currentColor" in prepareThemeSvg(svg, "#aabbcc"))
        assertEquals(svg, prepareThemeSvg(svg, "#aabbcc"))
    }

    @Test
    fun colorToHex_opaque() {
        assertEquals("#ff0000", Color(1f, 0f, 0f, 1f).toHex())
        assertEquals("#00ff00", Color(0f, 1f, 0f, 1f).toHex())
    }
}
