package com.locallink.pro.ui.screens.chat

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.locallink.pro.ui.theme.GlassFillFaint
import com.locallink.pro.ui.theme.OmniAccentBright
import com.locallink.pro.ui.theme.OmniText
import com.locallink.pro.ui.theme.glass

/**
 * Lightweight Markdown renderer for assistant replies — no third-party dependency.
 * Block level: fenced ``` code blocks, ATX headers (#..###), unordered (-,*,•) and
 * ordered (1.) lists, blank-line-separated paragraphs. Inline: **bold**, *italic*,
 * `code`, [text](url) (rendered as the visible text, styled).
 *
 * Pass [trailingCursor] = true while streaming to append a blinking-style "▌".
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    color: androidx.compose.ui.graphics.Color = OmniText,
    trailingCursor: Boolean = false,
) {
    val blocks = remember(text) { parseBlocks(text) }
    Column(modifier) {
        blocks.forEachIndexed { i, block ->
            val isLast = i == blocks.lastIndex
            val cursor = trailingCursor && isLast
            when (block) {
                is MdBlock.Code -> CodeBlock(block.code)
                is MdBlock.Heading -> Text(
                    text = inline(block.text, color).let { if (cursor) it + cursorSpan() else it },
                    color = color,
                    fontWeight = FontWeight.Bold,
                    fontSize = when (block.level) { 1 -> 21.sp; 2 -> 18.sp; else -> 16.sp },
                    lineHeight = 26.sp,
                    modifier = Modifier.padding(top = if (i == 0) 0.dp else 8.dp, bottom = 3.dp),
                )
                is MdBlock.ListItem -> Row(Modifier.padding(vertical = 1.5.dp, horizontal = 2.dp)) {
                    Text(block.marker, color = OmniAccentBright, style = LocalTextStyle.current)
                    Text(
                        text = inline(block.text, color).let { if (cursor) it + cursorSpan() else it },
                        color = color,
                        modifier = Modifier.padding(start = 6.dp),
                        style = LocalTextStyle.current,
                    )
                }
                is MdBlock.Paragraph -> Text(
                    text = inline(block.text, color).let { if (cursor) it + cursorSpan() else it },
                    color = color,
                    style = LocalTextStyle.current,
                    modifier = Modifier.padding(vertical = 1.dp),
                )
            }
        }
    }
}

@Composable
private fun CodeBlock(code: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .glass(shape = RoundedCornerShape(12.dp), fill = GlassFillFaint, highlight = false)
            .horizontalScroll(rememberScrollState()),
    ) {
        Text(
            text = code,
            color = OmniText,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            lineHeight = 19.sp,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }
}

private fun cursorSpan(): AnnotatedString = AnnotatedString(" ▌")

// ─── Block model ─────────────────────────────────────────────────────────

private sealed interface MdBlock {
    data class Paragraph(val text: String) : MdBlock
    data class Heading(val level: Int, val text: String) : MdBlock
    data class ListItem(val marker: String, val text: String) : MdBlock
    data class Code(val code: String) : MdBlock
}

private fun parseBlocks(src: String): List<MdBlock> {
    val out = ArrayList<MdBlock>()
    val lines = src.replace("\r\n", "\n").split("\n")
    var i = 0
    val para = StringBuilder()

    fun flushPara() {
        if (para.isNotBlank()) out.add(MdBlock.Paragraph(para.toString().trim()))
        para.setLength(0)
    }

    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()

        // fenced code block
        if (trimmed.startsWith("```")) {
            flushPara()
            val code = StringBuilder()
            i++
            while (i < lines.size && !lines[i].trim().startsWith("```")) {
                code.appendLine(lines[i]); i++
            }
            i++ // consume closing fence (or EOF)
            out.add(MdBlock.Code(code.toString().trimEnd('\n')))
            continue
        }

        // heading
        val h = Regex("^(#{1,6})\\s+(.*)$").find(trimmed)
        if (h != null) {
            flushPara()
            out.add(MdBlock.Heading(h.groupValues[1].length, h.groupValues[2].trim()))
            i++; continue
        }

        // unordered list
        val ul = Regex("^[-*•]\\s+(.*)$").find(trimmed)
        if (ul != null) {
            flushPara()
            out.add(MdBlock.ListItem("•", ul.groupValues[1].trim()))
            i++; continue
        }

        // ordered list
        val ol = Regex("^(\\d+)[.)]\\s+(.*)$").find(trimmed)
        if (ol != null) {
            flushPara()
            out.add(MdBlock.ListItem("${ol.groupValues[1]}.", ol.groupValues[2].trim()))
            i++; continue
        }

        // blank line → paragraph break
        if (trimmed.isEmpty()) { flushPara(); i++; continue }

        // accumulate paragraph (soft-wrap multi-line into one)
        if (para.isNotEmpty()) para.append(' ')
        para.append(trimmed)
        i++
    }
    flushPara()
    if (out.isEmpty()) out.add(MdBlock.Paragraph(src.trim()))
    return out
}

// ─── Inline spans ────────────────────────────────────────────────────────

private fun inline(text: String, base: androidx.compose.ui.graphics.Color): AnnotatedString = buildAnnotatedString {
    // First strip links [text](url) → text, remembering nothing fancy (just show text).
    val linkClean = Regex("\\[([^\\]]+)]\\(([^)]+)\\)").replace(text) { it.groupValues[1] }
    // Tokenize on **bold**, *italic*/_italic_, `code`.
    val token = Regex("(\\*\\*([^*]+)\\*\\*)|(`([^`]+)`)|((?<![*\\w])[*_]([^*_]+)[*_](?![*\\w]))")
    var last = 0
    for (m in token.findAll(linkClean)) {
        if (m.range.first > last) append(linkClean.substring(last, m.range.first))
        when {
            m.groupValues[1].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(m.groupValues[2]) }
            m.groupValues[3].isNotEmpty() -> withStyle(
                SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 13.5.sp, color = OmniAccentBright),
            ) { append(m.groupValues[4]) }
            m.groupValues[5].isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(m.groupValues[6]) }
        }
        last = m.range.last + 1
    }
    if (last < linkClean.length) append(linkClean.substring(last))
}
