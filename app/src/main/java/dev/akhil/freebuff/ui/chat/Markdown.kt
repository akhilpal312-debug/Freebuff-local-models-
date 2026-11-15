package dev.akhil.freebuff.ui.chat

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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.akhil.freebuff.design.AppIcons
import dev.akhil.freebuff.design.LocalExtendedColors

/** Minimal block model: enough Markdown for LLM output, without a heavy parser. */
private sealed interface Block {
    data class Paragraph(val text: String) : Block
    data class Heading(val level: Int, val text: String) : Block
    data class Code(val language: String, val code: String) : Block
    data class Bullet(val items: List<String>, val ordered: Boolean) : Block
    data class Quote(val text: String) : Block
    data class Rule(val unused: Unit = Unit) : Block
}

private fun parseBlocks(markdown: String): List<Block> {
    val blocks = mutableListOf<Block>()
    val lines = markdown.lines()
    var index = 0
    val paragraph = StringBuilder()

    fun flushParagraph() {
        if (paragraph.isNotBlank()) blocks += Block.Paragraph(paragraph.toString().trim())
        paragraph.clear()
    }

    while (index < lines.size) {
        val line = lines[index]
        when {
            line.trimStart().startsWith("```") -> {
                flushParagraph()
                val language = line.trim().removePrefix("```").trim()
                val code = StringBuilder()
                index++
                while (index < lines.size && !lines[index].trimStart().startsWith("```")) {
                    code.appendLine(lines[index])
                    index++
                }
                blocks += Block.Code(language, code.toString().trimEnd())
            }

            line.startsWith("#") -> {
                flushParagraph()
                val level = line.takeWhile { it == '#' }.length.coerceAtMost(4)
                blocks += Block.Heading(level, line.dropWhile { it == '#' }.trim())
            }

            line.trimStart().startsWith("> ") -> {
                flushParagraph()
                val quote = StringBuilder()
                while (index < lines.size && lines[index].trimStart().startsWith("> ")) {
                    quote.appendLine(lines[index].trimStart().removePrefix("> ").trim())
                    index++
                }
                blocks += Block.Quote(quote.toString().trim())
                continue
            }

            line.trim() == "---" || line.trim() == "***" -> {
                flushParagraph()
                blocks += Block.Rule()
            }

            line.matches(Regex("^\\s*[-*+]\\s+.*")) || line.matches(Regex("^\\s*\\d+[.)]\\s+.*")) -> {
                flushParagraph()
                val ordered = line.trim().first().isDigit()
                val items = mutableListOf<String>()
                while (index < lines.size && (lines[index].matches(Regex("^\\s*[-*+]\\s+.*")) ||
                        lines[index].matches(Regex("^\\s*\\d+[.)]\\s+.*")))
                ) {
                    items += lines[index].trim().replaceFirst(Regex("^([-*+]|\\d+[.)])\\s+"), "")
                    index++
                }
                blocks += Block.Bullet(items, ordered)
                continue
            }

            line.isBlank() -> flushParagraph()

            else -> {
                if (paragraph.isNotEmpty()) paragraph.append(' ')
                paragraph.append(line.trim())
            }
        }
        index++
    }
    flushParagraph()
    return blocks
}

/** Renders inline emphasis, code spans and links as styled text. */
@Composable
private fun inline(text: String): AnnotatedString {
    val codeColor = MaterialTheme.colorScheme.primary
    return remember(text, codeColor) {
        buildAnnotatedString {
            var index = 0
            while (index < text.length) {
                when {
                    text.startsWith("**", index) -> {
                        val end = text.indexOf("**", index + 2)
                        if (end > 0) {
                            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                                append(text.substring(index + 2, end))
                            }
                            index = end + 2
                        } else {
                            append(text[index]); index++
                        }
                    }

                    text.startsWith("`", index) -> {
                        val end = text.indexOf('`', index + 1)
                        if (end > 0) {
                            withStyle(
                                SpanStyle(
                                    fontFamily = FontFamily.Monospace,
                                    color = codeColor,
                                    fontSize = 13.sp,
                                ),
                            ) {
                                append(text.substring(index + 1, end))
                            }
                            index = end + 1
                        } else {
                            append(text[index]); index++
                        }
                    }

                    text.startsWith("*", index) || text.startsWith("_", index) -> {
                        val marker = text[index]
                        val end = text.indexOf(marker, index + 1)
                        if (end > index + 1) {
                            withStyle(SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)) {
                                append(text.substring(index + 1, end))
                            }
                            index = end + 1
                        } else {
                            append(text[index]); index++
                        }
                    }

                    else -> {
                        append(text[index]); index++
                    }
                }
            }
        }
    }
}

@Composable
fun CodeBlock(language: String, code: String, modifier: Modifier = Modifier) {
    val clipboard = LocalClipboardManager.current
    val extended = LocalExtendedColors.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(extended.codeBackground, RoundedCornerShape(14.dp))
            .border(1.dp, extended.codeBorder, RoundedCornerShape(14.dp)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = language.ifBlank { "code" }.lowercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { clipboard.setText(AnnotatedString(code)) }) {
                Text("Copy", style = MaterialTheme.typography.labelMedium)
            }
        }
        SelectionContainer {
            Text(
                text = code,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.5.sp,
                lineHeight = 19.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(start = 14.dp, end = 14.dp, bottom = 14.dp, top = 0.dp),
            )
        }
    }
}

/** Markdown renderer used for assistant messages. */
@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    val blocks = remember(text) { parseBlocks(text) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block ->
            when (block) {
                is Block.Paragraph -> Text(
                    text = inline(block.text),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                is Block.Heading -> Text(
                    text = inline(block.text),
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.headlineSmall
                        2 -> MaterialTheme.typography.titleLarge
                        else -> MaterialTheme.typography.titleMedium
                    },
                    color = MaterialTheme.colorScheme.onSurface,
                )

                is Block.Code -> CodeBlock(block.language, block.code)

                is Block.Bullet -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    block.items.forEachIndexed { itemIndex, item ->
                        Row {
                            Text(
                                text = if (block.ordered) "${itemIndex + 1}." else "•",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(22.dp),
                            )
                            Text(
                                text = inline(item),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }

                is Block.Quote -> Row(Modifier.fillMaxWidth()) {
                    androidx.compose.foundation.layout.Box(
                        Modifier
                            .width(3.dp)
                            .height(18.dp)
                            .background(MaterialTheme.colorScheme.outline, RoundedCornerShape(2.dp)),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = inline(block.text),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                is Block.Rule -> androidx.compose.material3.HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            }
        }
    }
}

@Composable
fun CopyButton(text: String, label: String = "Copy") {
    val clipboard = LocalClipboardManager.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { clipboard.setText(AnnotatedString(text)) }) {
            Icon(AppIcons.Copy, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}
