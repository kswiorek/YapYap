package org.yapyap.gui.mentions

import org.yapyap.protocol.AccountId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MentionMatcherTest {

    private val alice = AccountId("a".repeat(64))
    private val bob = AccountId("b".repeat(64))

    @Test
    fun findsHexMention() {
        val tokens = MentionMatcher.find("hello @${"a".repeat(64)} bye")
        assertEquals(1, tokens.size)
        val t = tokens.first() as MentionMatcher.Token.Mention
        assertEquals(alice, t.accountId)
    }

    @Test
    fun freeformNameIsNotAMention() {
        assertTrue(MentionMatcher.find("hello @alice, how are you").isEmpty())
    }

    @Test
    fun everyoneMatches() {
        val tokens = MentionMatcher.find("@everyone listen up")
        assertEquals(1, tokens.size)
        assertTrue(tokens.first() is MentionMatcher.Token.Everyone)
    }

    @Test
    fun mentionsMeById() {
        assertTrue(MentionMatcher.mentionsMe("ping @${"a".repeat(64)}", alice))
        assertFalse(MentionMatcher.mentionsMe("ping @${"b".repeat(64)}", alice))
    }

    @Test
    fun mentionsMeViaEveryone() {
        assertTrue(MentionMatcher.mentionsMe("@everyone hi", bob))
    }

    @Test
    fun displayNamesWithSpacesNeverBreakMatching() {
        // Matching is on the rigid 64-hex token, so names are irrelevant to detection.
        val text = "hi @${"a".repeat(64)} and @${"b".repeat(64)}!"
        assertEquals(2, MentionMatcher.find(text).size)
    }

    @Test
    fun previewSubstitutesNames() {
        val out = MentionMatcher.toPreviewText(
            "hi @${"a".repeat(64)}",
            mapOf(alice to "Alice Liddell"),
        )
        assertEquals("hi @Alice Liddell", out)
    }

    @Test
    fun neverThrowsOnGarbage() {
        MentionMatcher.find("@ @@@ ### #nope <t:bad> \uE000\uE001")
        MentionMatcher.mentionsMe("", alice)
        MentionMatcher.toPreviewText("", emptyMap())
    }
}
