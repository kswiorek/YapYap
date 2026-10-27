package org.yapyap.gui.screens.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.ClickableText
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import org.yapyap.gui.markdown.DiscordMarkdownTransformer
import org.yapyap.gui.theme.yapColors
import org.yapyap.gui.theme.yapType
import org.yapyap.orchestrator.runtime.message.MessageDisplayItem
import org.yapyap.protocol.AccountId

/**
 * M2 markdown-lite renderer: Discord dialect core (bold/italic/underline/strike,
 * inline code, links, spoilers, mentions, room refs) as one AnnotatedString per
 * block + block structure (code fences, headers, quotes, lists). The mikepenz
 * library replaces the internals later; the API (MessageBody) stays.
 */
@Composable
fun MessageBody(
    item: MessageDisplayItem,
    names: Map<AccountId, String>,
    onRoomRef: (org.yapyap.protocol.RoomId) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (item) {
        is MessageDisplayItem.Text -> TextBody(item.text, names, onRoomRef, modifier)
        is MessageDisplayItem.File -> FileCard(item, modifier)
        is MessageDisplayItem.Gap -> GapDivider(modifier)
    }
}

@Composable
private fun TextBody(
    raw: String,
    names: Map<AccountId, String>,
    onRoomRef: (org.yapyap.protocol.RoomId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = yapColors
    val transformed = remember(raw) { DiscordMarkdownTransformer.transform(raw) }
    var spoilerRevealed by remember(raw) { mutableStateOf(false) }
    Column(modifier) {
        splitBlocks(transformed).forEach { block ->
            when (block) {
                is Block.Code -> Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF242429))
                        .padding(10.dp),
                ) {
                    BasicText(
                        block.text,
                        style = yapType.bodyMd.copy(
                            color = colors.textDefault,
                            fontFamily = FontFamily.Monospace,
                        ),
                    )
                }

                is Block.Header -> BasicText(
                    block.text,
                    style = when (block.level) {
                        1 -> yapType.headingXl
                        2 -> yapType.headingLg
                        else -> yapType.bodyMdMedium
                    }.copy(color = colors.textStrong),
                    modifier = Modifier.padding(vertical = 2.dp),
                )

                is Block.Quote -> Row(
                    Modifier.fillMaxWidth().padding(vertical = 2.dp)
                        .height(androidx.compose.foundation.layout.IntrinsicSize.Min),
                ) {
                    Box(
                        Modifier
                            .padding(end = 8.dp)
                            .width(2.dp)
                            .fillMaxHeight()
                            .background(colors.borderStrong),
                    )
                    InlineText(block.text, names, onRoomRef, spoilerRevealed) { spoilerRevealed = true }
                }

                is Block.ListItem -> Row(Modifier.fillMaxWidth()) {
                    BasicText(
                        if (block.ordered) "${block.number}. " else "• ",
                        style = yapType.bodyMd.copy(color = colors.textDefault),
                    )
                    InlineText(block.text, names, onRoomRef, spoilerRevealed) { spoilerRevealed = true }
                }

                is Block.Para -> InlineText(block.text, names, onRoomRef, spoilerRevealed) { spoilerRevealed = true }
            }
        }
    }
}

private sealed interface Block {
    data class Code(val text: String) : Block
    data class Header(val level: Int, val text: String) : Block
    data class Quote(val text: String) : Block
    data class ListItem(val text: String, val ordered: Boolean, val number: Int) : Block
    data class Para(val text: String) : Block
}

private fun splitBlocks(text: String): List<Block> {
    val out = mutableListOf<Block>()
    var inCode = false
    val codeBuf = StringBuilder()
    val paraBuf = mutableListOf<String>()
    fun flushPara() {
        if (paraBuf.isNotEmpty()) {
            out += Block.Para(paraBuf.joinToString("\n"))
            paraBuf.clear()
        }
    }
    text.lines().forEach { line ->
        if (line.trimStart().startsWith("```")) {
            if (inCode) {
                out += Block.Code(codeBuf.toString().trimEnd('\n'))
                codeBuf.clear()
                inCode = false
            } else {
                flushPara()
                inCode = true
            }
            return@forEach
        }
        if (inCode) {
            codeBuf.appendLine(line)
            return@forEach
        }
        val trimmed = line.trimStart()
        when {
            trimmed.startsWith("###### ") -> {
                flushPara()
                out += Block.Header(4, trimmed.removePrefix("###### "))
            }

            trimmed.startsWith("### ") -> {
                flushPara()
                out += Block.Header(3, trimmed.removePrefix("### "))
            }

            trimmed.startsWith("## ") -> {
                flushPara()
                out += Block.Header(2, trimmed.removePrefix("## "))
            }

            trimmed.startsWith("# ") -> {
                flushPara()
                out += Block.Header(1, trimmed.removePrefix("# "))
            }

            trimmed.startsWith("> ") || trimmed == ">" -> {
                flushPara()
                out += Block.Quote(trimmed.removePrefix(">").trimStart())
            }

            Regex("^(\\d+)\\.\\s+").containsMatchIn(trimmed) -> {
                flushPara()
                val m = Regex("^(\\d+)\\.\\s+").find(trimmed)!!
                out += Block.ListItem(trimmed.substring(m.range.last + 1), true, m.groupValues[1].toInt())
            }

            trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                flushPara()
                out += Block.ListItem(trimmed.drop(2), false, 0)
            }

            else -> paraBuf += line
        }
    }
    if (inCode) out += Block.Code(codeBuf.toString())
    flushPara()
    return out
}

@Composable
private fun InlineText(
    text: String,
    names: Map<AccountId, String>,
    onRoomRef: (org.yapyap.protocol.RoomId) -> Unit,
    spoilerRevealed: Boolean,
    onRevealSpoiler: () -> Unit,
) {
    val colors = yapColors
    val annotated = remember(text, spoilerRevealed, names) {
        buildInline(text, names, spoilerRevealed)
    }
    // TODO(M3): migrate to Text + LinkAnnotation (ClickableText is deprecated in CMP 1.10).
    ClickableText(
        annotated,
        style = yapType.bodyMd.copy(color = colors.textDefault),
        onClick = { offset ->
            annotated.getStringAnnotations("url", offset, offset).firstOrNull()?.let {
                openUrl(it.item)
                return@ClickableText
            }
            annotated.getStringAnnotations("room", offset, offset).firstOrNull()?.let {
                runCatching {
                    onRoomRef(org.yapyap.protocol.RoomId(kotlin.uuid.Uuid.parse(it.item)))
                }
                return@ClickableText
            }
            annotated.getStringAnnotations("spoiler", offset, offset).firstOrNull()?.let {
                onRevealSpoiler()
            }
        },
    )
}

private fun buildInline(text: String, names: Map<AccountId, String>, spoilerRevealed: Boolean): AnnotatedString {
    // Code spans masked first (negation).
    val codeSpans = mutableListOf<String>()
    val t = Regex("`([^`\n]+)`").replace(text) { m ->
        codeSpans += m.groupValues[1]
        "\uE00E${codeSpans.size - 1}\uE00E"
    }
    return buildAnnotatedString {
        parseInline(t, names, spoilerRevealed, codeSpans)
    }
}

private fun AnnotatedString.Builder.parseInline(
    text: String,
    names: Map<AccountId, String>,
    spoilerRevealed: Boolean,
    codeSpans: List<String>,
) {
    // Combined token regex, ordered by specificity.
    val token = Regex(
        "\uE00E(\\d+)\uE00E" + // 1: code placeholder
                "|${DiscordMarkdownTransformer.UL_OPEN}(.+?)${DiscordMarkdownTransformer.UL_CLOSE}" + // 2,3
                "|${DiscordMarkdownTransformer.SP_OPEN}(.+?)${DiscordMarkdownTransformer.SP_CLOSE}" + // 4,5
                "|\\*\\*\\*(.+?)\\*\\*\\*" + // 6
                "|\\*\\*(.+?)\\*\\*" + // 7
                "|~~(.+?)~~" + // 8
                "|\\[(.+?)]\\(([^)]+)\\)" + // 9,10 masked link
                "|(https?://[^\\s<>]+)" + // 11 bare url
                "|(@[0-9a-fA-F]{64}\\b)" + // 12 mention
                "|(@everyone\\b)" + // 13
                "|(#[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b)" + // 14 room
                "|(?<!\\w)\\*([^*\n]+)\\*" + // 15 single-* italic
                "|(?<!\\w)_([^_\n]+)_", // 16 single-_ italic
    )
    var pos = 0
    for (m in token.findAll(text)) {
        if (m.range.first > pos) append(text.substring(pos, m.range.first))
        val g = m.groupValues
        when {
            g[1].isNotEmpty() -> {
                val code = codeSpans.getOrNull(g[1].toInt()) ?: ""
                pushStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0xFF242429)))
                append(code)
                pop()
            }

            g[3].isNotEmpty() -> {
                pushStyle(SpanStyle(textDecoration = TextDecoration.Underline))
                append(g[3])
                pop()
            }

            g[5].isNotEmpty() -> {
                if (spoilerRevealed) {
                    append(g[5])
                } else {
                    pushStringAnnotation("spoiler", "1")
                    pushStyle(SpanStyle(color = Color.Transparent, background = Color(0xFF202024)))
                    append("▓".repeat(g[5].length.coerceAtMost(32)))
                    pop()
                    pop()
                }
            }

            g[6].isNotEmpty() -> {
                pushStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic))
                append(g[6]); pop()
            }

            g[7].isNotEmpty() -> {
                pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                append(g[7]); pop()
            }

            g[8].isNotEmpty() -> {
                pushStyle(SpanStyle(textDecoration = TextDecoration.LineThrough))
                append(g[8]); pop()
            }

            g[9].isNotEmpty() -> {
                pushStringAnnotation("url", g[10])
                pushStyle(SpanStyle(color = Color(0xFF76AFF6)))
                append(g[9]); pop(); pop()
            }

            g[11].isNotEmpty() -> {
                val url = g[11]
                pushStringAnnotation("url", url)
                pushStyle(SpanStyle(color = Color(0xFF76AFF6)))
                append(url); pop(); pop()
            }

            g[12].isNotEmpty() -> {
                val id = runCatching { AccountId(g[12].substring(1).lowercase()) }.getOrNull()
                val name = id?.let { names[it] } ?: "unknown"
                pushStyle(SpanStyle(color = Color(0xFFCDD7FF), background = Color(0x3D5865F2)))
                append("@$name"); pop()
            }

            g[13].isNotEmpty() -> {
                pushStyle(SpanStyle(color = Color(0xFFCDD7FF), background = Color(0x3D5865F2)))
                append("@everyone"); pop()
            }

            g[14].isNotEmpty() -> {
                pushStringAnnotation("room", g[14].substring(1))
                pushStyle(SpanStyle(color = Color(0xFF76AFF6)))
                append(g[14]); pop(); pop()
            }

            g[15].isNotEmpty() || g[16].isNotEmpty() -> {
                pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                append(g[15].ifEmpty { g[16] }); pop()
            }
        }
        pos = m.range.last + 1
    }
    if (pos < text.length) append(text.substring(pos))
}

@Composable
fun FileCard(item: MessageDisplayItem.File, modifier: Modifier = Modifier) {
    val colors = yapColors
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(colors.bgRaised)
            .padding(12.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(colors.bgAccent)
                .padding(10.dp),
        ) {
            BasicText("🗎", style = yapType.bodyLg.copy(color = colors.textDefault))
        }
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            BasicText(item.fileName, style = yapType.bodySmMedium.copy(color = colors.textDefault))
            BasicText(
                formatBytes(item.fileSize),
                style = yapType.bodyXs.copy(color = colors.textMuted),
            )
        }
        BasicText(
            "Soon",
            style = yapType.bodyXs.copy(color = colors.textMuted),
            modifier = Modifier.clickable { },
        )
    }
}

private fun formatBytes(n: Long): String = when {
    n < 1024 -> "$n B"
    n < 1024 * 1024 -> "${n / 1024} KB"
    else -> "${n / (1024 * 1024)} MB"
}

@Composable
fun GapDivider(modifier: Modifier = Modifier) {
    val colors = yapColors
    Row(
        modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).background(colors.borderNormal).padding(vertical = 0.5.dp))
        BasicText(
            "messages not loaded",
            style = yapType.bodyXs.copy(color = colors.textMuted),
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        Box(Modifier.weight(1f).background(colors.borderNormal).padding(vertical = 0.5.dp))
    }
}
