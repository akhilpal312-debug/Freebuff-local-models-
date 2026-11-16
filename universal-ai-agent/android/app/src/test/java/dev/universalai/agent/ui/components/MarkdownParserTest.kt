package dev.universalai.agent.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownParserTest {

    @Test
    fun `headings carry their level`() {
        val blocks = MarkdownParser.parse("# Title\n\n### Detail")
        assertEquals(2, blocks.size)
        assertEquals(MdBlock.Heading(1, "Title"), blocks[0])
        assertEquals(MdBlock.Heading(3, "Detail"), blocks[1])
    }

    @Test
    fun `fenced code keeps language and body`() {
        val markdown = "Intro\n\n```kotlin\nval x = 1\nprintln(x)\n```\n\nDone"
        val blocks = MarkdownParser.parse(markdown)
        val code = blocks.filterIsInstance<MdBlock.Code>().single()
        assertEquals("kotlin", code.language)
        assertEquals("val x = 1\nprintln(x)", code.code)
        assertTrue(blocks.first() is MdBlock.Paragraph)
        assertTrue(blocks.last() is MdBlock.Paragraph)
    }

    @Test
    fun `unordered list groups consecutive items`() {
        val blocks = MarkdownParser.parse("- one\n- two\n* three")
        val list = blocks.single() as MdBlock.Bullets
        assertEquals(listOf("one", "two", "three"), list.items)
        assertEquals(false, list.ordered)
    }

    @Test
    fun `ordered list stays ordered`() {
        val blocks = MarkdownParser.parse("1. first\n2. second")
        val list = blocks.single() as MdBlock.Bullets
        assertEquals(listOf("first", "second"), list.items)
        assertTrue(list.ordered)
    }

    @Test
    fun `consecutive lines join into one paragraph`() {
        val blocks = MarkdownParser.parse("first line\nsecond line")
        assertEquals(1, blocks.size)
        assertEquals("first line second line", (blocks[0] as MdBlock.Paragraph).text)
    }

    @Test
    fun `quotes and dividers are recognised`() {
        val blocks = MarkdownParser.parse("> quoted\n\n---")
        assertEquals(MdBlock.Quote("quoted"), blocks[0])
        assertEquals(MdBlock.Divider, blocks[1])
    }

    @Test
    fun `unclosed code fence still yields a code block`() {
        val blocks = MarkdownParser.parse("```python\nprint('hi')")
        val code = blocks.single() as MdBlock.Code
        assertEquals("python", code.language)
        assertEquals("print('hi')", code.code)
    }
}
