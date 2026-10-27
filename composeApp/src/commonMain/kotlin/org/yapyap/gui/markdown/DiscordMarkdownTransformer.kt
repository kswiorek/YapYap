package org.yapyap.gui.markdown

/**
 * Discord dialect pre-parser (§5): code-aware string pass producing sentinel-marked
 * text for constructs CommonMark lacks or mistranslates. Pure Kotlin, no dependencies.
 *
 * Pipeline: mask code spans/blocks → strip unknown private-use chars (anti-forgery) →
 * apply rewrites → restore code. Spoilers/mentions are negated inside code, per Discord.
 *
 * Sentinels live in the Unicode private-use area:
 * - U+E000..U+E001 wrap underlined runs
 * - U+E002..U+E003 wrap spoiler runs
 * - U+E010.. is reserved for timestamps (M3: `<t:unix:style>` → chip data)
 */
object DiscordMarkdownTransformer {
    const val UL_OPEN = '\uE000'
    const val UL_CLOSE = '\uE001'
    const val SP_OPEN = '\uE002'
    const val SP_CLOSE = '\uE003'

    private const val TRIPLE_TICK = "```"
    private val CODE_SPAN = Regex("$TRIPLE_TICK[\\s\\S]*?($TRIPLE_TICK|$)|`[^`\n]*`")

    /** Masked regions are restored verbatim after transformation. */
    fun transform(input: String): String {
        if (input.isEmpty()) return input
        // 1. Mask code.
        val masked = mutableListOf<String>()
        var text = CODE_SPAN.replace(input) { m ->
            masked += m.value
            "\uE00F${masked.size - 1}\uE00F"
        }
        // 2. Anti-forgery: drop any other private-use chars the user typed.
        text = text.filter { c -> c !in '\uE000'..'\uE0FF' || c == '\uE00F' }
        // 3. Rewrites (order matters: underline before emphasis handled by renderer).
        text = underline(text)
        text = spoilers(text)
        text = quoteToEnd(text)
        text = subtext(text)
        // 4. Restore code.
        masked.forEachIndexed { i, code ->
            text = text.replace("\uE00F${i}\uE00F", code)
        }
        return text
    }

    /** `__text__` → sentinel-wrapped (CommonMark would read `__` as bold). */
    internal fun underline(text: String): String {
        val re = Regex("__(.+?)__")
        return re.replace(text) { m -> "$UL_OPEN${m.groupValues[1]}$UL_CLOSE" }
    }

    /** `||text||` → sentinel-wrapped. */
    internal fun spoilers(text: String): String {
        val re = Regex("\\|\\|(.+?)\\|\\|")
        return re.replace(text) { m -> "$SP_OPEN${m.groupValues[1]}$SP_CLOSE" }
    }

    /** `>>> rest of message` → every line quoted. */
    internal fun quoteToEnd(text: String): String {
        val lines = text.lines()
        val idx = lines.indexOfFirst { it.startsWith(">>>") }
        if (idx < 0) return text
        val head = lines.subList(0, idx)
        val tail = lines.subList(idx, lines.size).map { line ->
            val stripped = if (line.startsWith(">>>")) line.removePrefix(">>>").trimStart() else line
            if (stripped.startsWith(">")) stripped else "> $stripped"
        }
        return (head + tail).joinToString("\n")
    }

    /** `-# text` → h6 marker the renderer maps to subtext style. */
    internal fun subtext(text: String): String =
        text.lines().joinToString("\n") { line ->
            val t = line.trimStart()
            if (t.startsWith("-# ") || t == "-#") "###### " + t.removePrefix("-#").trimStart()
            else line
        }

    /** Strip all Discord formatting to plain text (room-list previews, notifications). */
    fun stripToPlain(text: String): String {
        var out = text
        out = Regex("```[\\s\\S]*?```").replace(out, "code")
        out = Regex("`([^`]*)`").replace(out, "$1")
        out = Regex("\\*\\*\\*(.+?)\\*\\*\\*").replace(out, "$1")
        out = Regex("\\*\\*(.+?)\\*\\*").replace(out, "$1")
        out = Regex("__(.+?)__").replace(out, "$1")
        out = Regex("~~(.+?)~~").replace(out, "$1")
        out = Regex("(^|\\W)\\*(.+?)\\*").replace(out, "$1$2")
        out = Regex("(^|\\W)_([^_]+)_").replace(out, "$1$2")
        out = Regex("\\|\\|(.+?)\\|\\|").replace(out, "$1")
        out = Regex("\\[(.+?)]\\([^)]+\\)").replace(out, "$1")
        out = Regex("^#{1,6}\\s?", RegexOption.MULTILINE).replace(out, "")
        out = Regex("^-#\\s?", RegexOption.MULTILINE).replace(out, "")
        out = Regex("^>\\s?", RegexOption.MULTILINE).replace(out, "")
        return out.trim()
    }
}
