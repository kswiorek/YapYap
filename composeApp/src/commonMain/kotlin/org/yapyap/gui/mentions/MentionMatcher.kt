package org.yapyap.gui.mentions

import org.yapyap.protocol.AccountId
import org.yapyap.protocol.RoomId

/**
 * Mention token grammar (§7): `@<64-hex AccountId>`, `@everyone`, `#<RoomId UUID>`.
 * Pure functions over plain message text — the backend never interprets these.
 *
 * AccountId.id is SHA-256 hex (exactly 64 lowercase hex chars); RoomId wraps a UUID
 * (canonical dashed form). Matching is rigid-token based, so display names may
 * contain anything (spaces, unicode, @, #, emoji) without breaking mentions.
 */
object MentionMatcher {
    private val HEX64 = Regex("@[0-9a-fA-F]{64}\\b")
    private val EVERYONE = Regex("@everyone\\b")
    private val ROOM_REF = Regex("#[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b")

    sealed interface Token {
        data class Mention(val accountId: AccountId, val range: IntRange) : Token
        data class Everyone(val range: IntRange) : Token
        data class RoomRef(val roomId: RoomId, val range: IntRange) : Token
    }

    /** All mention/room tokens in [text], in order. Never throws on weird input. */
    fun find(text: String): List<Token> {
        if (text.isEmpty()) return emptyList()
        val out = mutableListOf<Token>()
        HEX64.findAll(text).forEach { m ->
            runCatching {
                out += Token.Mention(
                    AccountId(m.value.substring(1).lowercase()),
                    m.range,
                )
            }
        }
        EVERYONE.findAll(text).forEach { m -> out += Token.Everyone(m.range) }
        ROOM_REF.findAll(text).forEach { m ->
            runCatching {
                out += Token.RoomRef(
                    RoomId(kotlin.uuid.Uuid.parse(m.value.substring(1).lowercase())),
                    m.range,
                )
            }
        }
        return out.sortedBy { rangeOf(it).first }
    }

    private fun rangeOf(t: Token): IntRange = when (t) {
        is Token.Mention -> t.range
        is Token.Everyone -> t.range
        is Token.RoomRef -> t.range
    }

    /** True when [text] mentions [localId] (or contains @everyone). Self-authorship is
     *  decided by the caller (it knows the message author). */
    fun mentionsMe(text: String, localId: AccountId): Boolean =
        EVERYONE.containsMatchIn(text) ||
                find(text).any { it is Token.Mention && it.accountId == localId }

    /** Plain-text preview: substitute id tokens with display names so the room list
     *  never shows raw hex. Unknown ids → "@unknown". */
    fun toPreviewText(text: String, names: Map<AccountId, String>): String {
        var out = text
        find(out).filterIsInstance<Token.Mention>().reversed().forEach { tok ->
            val name = names[tok.accountId] ?: "unknown"
            out = out.replaceRange(tok.range, "@$name")
        }
        return out
    }
}
