package dev.universalai.agent.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.universalai.agent.ui.theme.Palette

sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class Bullets(val items: List<String>, val ordered: Boolean) : MdBlock
    data class Code(val language: String?, val code: String) : MdBlock
    data class Quote(val text: String) : MdBlock
    data object Divider : MdBlock
}

/**
 * Deliberately small Markdown parser covering what models actually emit:
 * headings, paragraphs, bullet/numbered lists, fenced code, quotes and rules.
 * Kept pure so it is unit tested on the JVM.
 */
object MarkdownParser {

    fun parse(markdown: String): List<MdBlock> {
        val blocks = mutableListOf<MdBlock>()
        val lines = markdown.replace("\r\n", "\n").split("\n")
        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            when {
                line.startsWith("```") -> {
                    val language = line.removePrefix("```").trim().ifBlank { null }
                    val code = StringBuilder()
                    index++
                    while (index < lines.size && !lines[index].startsWith("```")) {
                        code.appendLine(lines[index])
                        index++
                    }
                    index++ // closing fence
                    blocks += MdBlock.Code(language, code.toString().trimEnd('\n'))
                    continue
                }
                line.matches(Regex("^#{1,6}\\s+.*")) -> {
                    val level = line.takeWhile { it == '#' }.length
                    blocks += MdBlock.Heading(level, line.drop(level).trim())
                }
                line.trim() == "---" || line.trim() == "***" -> blocks += MdBlock.Divider
                line.startsWith("> ") -> blocks += MdBlock.Quote(line.removePrefix("> ").trim())
                line.matches(Regex("^\\s*[-*+]\\s+.*")) -> {
                    val items = mutableListOf<String>()
                    while (index < lines.size && lines[index].matches(Regex("^\\s*[-*+]\\s+.*"))) {
                        items += lines[index].replace(Regex("^\\s*[-*+]\\s+"), "")
                        index++
                    }
                    blocks += MdBlock.Bullets(items, ordered = false)
                    continue
                }
                line.matches(Regex("^\\s*\\d+[.)]\\s+.*")) -> {
                    val items = mutableListOf<String>()
                    while (index < lines.size && lines[index].matches(Regex("^\\s*\\d+[.)]\\s+.*"))) {
                        items += lines[index].replace(Regex("^\\s*\\d+[.)]\\s+"), "")
                        index++
                    }
                    blocks += MdBlock.Bullets(items, ordered = true)
                    continue
                }
                line.isBlank() -> Unit
                else -> {
                    val paragraph = StringBuilder(line)
                    index++
                    while (index < lines.size && lines[index].isNotBlank() &&
                        !lines[index].startsWith("```") &&
                        !lines[index].matches(Regex("^#{1,6}\\s+.*")) &&
                        !lines[index].matches(Regex("^\\s*[-*+]\\s+.*")) &&
                        !lines[index].matches(Regex("^\\s*\\d+[.)]\\s+.*"))
                    ) {
                        paragraph.append(' ').append(lines[index].trim())
                        index++
                    }
                    blocks += MdBlock.Paragraph(paragraph.toString())
                    continue
                }
            }
            index++
        }
        return blocks
    }

    private val inlinePattern = Regex(
        "(`[^`]+`)|(\\*\\*[^*]+\\*\\*)|(\\*[^*]+\\*)|(_[^_]+_)|(\\[[^\\]]+]\\([^)]+\\))|(https?://\\S+)",
    )

    fun inline(text: String): AnnotatedString = buildAnnotatedString {
        var cursor = 0
        inlinePattern.findAll(text).forEach { match ->
            if (match.range.first > cursor) {
                append(text.substring(cursor, match.range.first))
            }
            val token = match.value
            when {
                token.startsWith("`") -> withStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = Palette.CodeBackground,
                        color = Palette.Graphite,
                        fontSize = 13.sp,
                    ),
                ) { append(token.trim('`')) }
                token.startsWith("**") -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                    append(token.trim('*'))
                }
                token.startsWith("*") || token.startsWith("_") -> withStyle(
                    SpanStyle(fontStyle = FontStyle.Italic),
                ) { append(token.trim('*', '_')) }
                token.startsWith("[") -> {
                    val label = token.substringAfter('[').substringBefore(']')
                    val target = token.substringAfter('(').substringBefore(')')
                    pushStringAnnotation(tag = "url", annotation = target)
                    withStyle(
                        SpanStyle(
                            color = Palette.Clay,
                            textDecoration = TextDecoration.Underline,
                        ),
                    ) { append(label) }
                    pop()
                }
                else -> withStyle(SpanStyle(color = Palette.Clay)) { append(token) }
            }
            cursor = match.range.last + 1
        }
        if (cursor < text.length) append(text.substring(cursor))
    }
}

@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    baseColor: Color = Palette.Ink,
) {
    val blocks = remember(markdown) { MarkdownParser.parse(markdown) }
    Column(modifier = modifier.fillMaxWidth()) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Heading -> {
                    val size = when (block.level) {
                        1 -> 22.sp
                        2 -> 19.sp
                        3 -> 17.sp
                        else -> 15.sp
                    }
                    Spacer(Modifier.height(if (block.level <= 2) 14.dp else 8.dp))
                    Text(
                        text = block.text,
                        style = MaterialTheme.typography.titleLarge.copy(fontSize = size),
                        color = baseColor,
                    )
                    Spacer(Modifier.height(4.dp))
                }
                is MdBlock.Paragraph -> {
                    Text(
                        text = MarkdownParser.inline(block.text),
                        style = MaterialTheme.typography.bodyLarge,
                        color = baseColor,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                is MdBlock.Bullets -> {
                    Column {
                        block.items.forEachIndexed { index, item ->
                            Row(modifier = Modifier.padding(bottom = 4.dp)) {
                                Text(
                                    text = if (block.ordered) "${index + 1}." else "•",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = Palette.Muted,
                                    modifier = Modifier.width(20.dp),
                                )
                                Text(
                                    text = MarkdownParser.inline(item),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = baseColor,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
                is MdBlock.Quote -> {
                    Row(modifier = Modifier.padding(vertical = 4.dp)) {
                        Column(
                            modifier = Modifier
                                .width(3.dp)
                                .height(28.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(Palette.HairlineStrong),
                        ) {}
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = MarkdownParser.inline(block.text),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Palette.Muted,
                        )
                    }
                }
                is MdBlock.Code -> CodeBlock(language = block.language, code = block.code)
                MdBlock.Divider -> {
                    Spacer(Modifier.height(10.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(Palette.Hairline),
                    ) {}
                    Spacer(Modifier.height(10.dp))
                }
            }
        }
    }
}

@Composable
fun CodeBlock(language: String?, code: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Palette.CodeBackground)
            .border(1.dp, Palette.CodeBorder, RoundedCornerShape(14.dp)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = (language ?: "code").lowercase(),
                style = MaterialTheme.typography.labelSmall,
                color = Palette.Faint,
            )
        }
        Column(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            SelectionContainer {
                Text(
                    text = CodeHighlighter.highlight(code, language),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.5.sp,
                    lineHeight = 19.sp,
                    color = Palette.Graphite,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
    }
}

/** Lightweight, warm-toned token colouring for the languages the agents emit. */
object CodeHighlighter {

    private val keywords = setOf(
        "fun", "val", "var", "class", "object", "interface", "if", "else", "when", "return",
        "import", "package", "suspend", "override", "private", "public", "internal", "data",
        "def", "async", "await", "for", "while", "try", "except", "raise", "from", "as",
        "const", "let", "function", "export", "default", "new", "this", "self", "true",
        "false", "null", "None", "True", "False", "elif", "with", "yield", "struct", "enum",
        "select", "from", "where", "insert", "update", "delete", "create", "table", "into",
        "public", "static", "void", "int", "string", "boolean", "long", "double", "final",
    )

    private val tokenPattern = Regex(
        "(\"\"\"[\\s\\S]*?\"\"\")|(\"([^\"\\\\]|\\\\.)*\")|('([^'\\\\]|\\\\.)*')|(//[^\\n]*)|(#.*)|(/\\*[\\s\\S]*?\\*/)|(\\b\\d+(\\.\\d+)?\\b)|(\\b[A-Za-z_][A-Za-z0-9_]*\\b)",
    )

    fun highlight(code: String, language: String?): AnnotatedString = buildAnnotatedString {
        var cursor = 0
        tokenPattern.findAll(code).forEach { match ->
            if (match.range.first > cursor) append(code.substring(cursor, match.range.first))
            val token = match.value
            val style = when {
                token.startsWith("\"\"\"") || token.startsWith("\"") || token.startsWith("'") ->
                    SpanStyle(color = Palette.Sage)
                token.startsWith("//") || token.startsWith("#") -> SpanStyle(
                    color = Palette.Faint,
                    fontStyle = FontStyle.Italic,
                )
                token.startsWith("/*") -> SpanStyle(color = Palette.Faint, fontStyle = FontStyle.Italic)
                token.firstOrNull()?.isDigit() == true -> SpanStyle(color = Palette.Amber)
                token in keywords -> SpanStyle(color = Palette.Clay, fontWeight = FontWeight.Medium)
                token.firstOrNull()?.isUpperCase() == true -> SpanStyle(color = Palette.Graphite)
                else -> SpanStyle(color = Palette.Graphite)
            }
            withStyle(style) { append(token) }
            cursor = match.range.last + 1
        }
        if (cursor < code.length) append(code.substring(cursor))
    }
}
