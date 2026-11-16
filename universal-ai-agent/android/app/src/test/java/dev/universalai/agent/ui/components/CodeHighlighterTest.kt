package dev.universalai.agent.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class CodeHighlighterTest {

    @Test
    fun `highlighting never drops characters`() {
        val code = "fun main() {\n    val x = 42 // answer\n    println(\"hi\")\n}"
        val highlighted = CodeHighlighter.highlight(code, "kotlin")
        assertEquals(code, highlighted.text)
    }

    @Test
    fun `python code round-trips`() {
        val code = "def add(a, b):\n    \"\"\"sum\"\"\"\n    return a + b  # maths"
        val highlighted = CodeHighlighter.highlight(code, "python")
        assertEquals(code, highlighted.text)
    }

    @Test
    fun `unknown language is still rendered`() {
        val code = "<html><body>hello</body></html>"
        assertEquals(code, CodeHighlighter.highlight(code, null).text)
        assertEquals(code, CodeHighlighter.highlight(code, "weird-lang").text)
    }

    @Test
    fun `numbers and keywords are annotated`() {
        val highlighted = CodeHighlighter.highlight("return 42", "kotlin")
        assertEquals("return 42", highlighted.text)
        assertEquals(true, highlighted.spanStyles.isNotEmpty())
    }

    @Test
    fun `inline markdown keeps visible text`() {
        val annotated = MarkdownParser.inline("Use `code`, **bold**, *italic* and [docs](https://example.com).")
        assertEquals("Use code, bold, italic and docs.", annotated.text)
        assertEquals("https://example.com", annotated.getStringAnnotations("url", 0, annotated.length).first().item)
    }
}
