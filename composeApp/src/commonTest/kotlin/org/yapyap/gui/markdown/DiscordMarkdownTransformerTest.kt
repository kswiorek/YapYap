package org.yapyap.gui.markdown

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiscordMarkdownTransformerTest {

    @Test
    fun underlineMarkedWithSentinels() {
        val out = DiscordMarkdownTransformer.transform("hello __world__ bye")
        assertTrue(out.contains("${DiscordMarkdownTransformer.UL_OPEN}world${DiscordMarkdownTransformer.UL_CLOSE}"))
    }

    @Test
    fun spoilerMarkedWithSentinels() {
        val out = DiscordMarkdownTransformer.transform("a ||secret|| b")
        assertTrue(out.contains("${DiscordMarkdownTransformer.SP_OPEN}secret${DiscordMarkdownTransformer.SP_CLOSE}"))
    }

    @Test
    fun codeNegatesFormatting() {
        val out = DiscordMarkdownTransformer.transform("`__not underline__` and `||not spoiler||`")
        assertFalse(out.contains(DiscordMarkdownTransformer.UL_OPEN.toString()))
        assertFalse(out.contains(DiscordMarkdownTransformer.SP_OPEN.toString()))
        assertTrue(out.contains("`__not underline__`"))
    }

    @Test
    fun fencedCodeNegatesFormatting() {
        val src = "```\n__no__ ||no||\n```\nafter __yes__"
        val out = DiscordMarkdownTransformer.transform(src)
        assertTrue(out.contains("```\n__no__ ||no||\n```"))
        assertTrue(out.contains("${DiscordMarkdownTransformer.UL_OPEN}yes${DiscordMarkdownTransformer.UL_CLOSE}"))
    }

    @Test
    fun forgedPrivateUseIsStripped() {
        val out = DiscordMarkdownTransformer.transform("x \uE000fake\uE001 y")
        assertFalse(out.contains("\uE000fake\uE001"))
    }

    @Test
    fun quoteToEndExpands() {
        val out = DiscordMarkdownTransformer.transform(">>> line one\nline two")
        assertEquals("> line one\n> line two", out)
    }

    @Test
    fun subtextBecomesH6() {
        val out = DiscordMarkdownTransformer.transform("-# small text")
        assertEquals("###### small text", out)
    }

    @Test
    fun stripToPlainRemovesFormatting() {
        assertEquals("bold and italic", DiscordMarkdownTransformer.stripToPlain("**bold** and *italic*"))
        assertEquals("link", DiscordMarkdownTransformer.stripToPlain("[link](https://example.com)"))
        assertEquals("secret", DiscordMarkdownTransformer.stripToPlain("||secret||"))
        assertEquals("code", DiscordMarkdownTransformer.stripToPlain("```anything```"))
    }
}
