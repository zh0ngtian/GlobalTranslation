package com.example.globaltranslation.core.util

import com.example.globaltranslation.core.model.PhotoTextBlock
import com.example.globaltranslation.core.model.TextBounds

/** Line bounds are in the text's reading coordinates, including for rotated photographs. */
data class ParagraphLine(val text: String, val bounds: TextBounds)

object ParagraphText {
    private val listStart = Regex("^(?:[-−–—*](?:\\s|$)|[•·▪◦●○◆◇①-⑳]|(?:\\d+[.)、．]|[（(]\\d+[)）]|[A-Za-z][.)]|[IVXLCDM]+[.)]|[一二三四五六七八九十]+[、.)])(?:\\s|$|(?=[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}])))")

    fun isListItem(text: String): Boolean = listStart.containsMatchIn(text.trimStart())

    /** Preserve visible paragraph gaps, heading size changes and first-line indentation. */
    fun paragraphStarts(lines: List<ParagraphLine>): Set<Int> = lines.indices.drop(1).filter { index ->
        val previous = lines[index - 1]
        val current = lines[index]
        val height = current.bounds.height.coerceAtLeast(1f)
        val previousHeight = previous.bounds.height.coerceAtLeast(1f)
        current.bounds.top - previous.bounds.bottom > height * .9f ||
            height > previousHeight * 1.5f || height < previousHeight * .65f ||
            (current.bounds.left - previous.bounds.left > height && !isListItem(previous.text))
    }.toSet()

    /** Build a request-only copy. Source text, block IDs and photo geometry stay intact. */
    fun prepare(block: PhotoTextBlock): String {
        val lines = block.text.lines()
        return buildString {
            lines.forEachIndexed { index, source ->
                val line = source.trim()
                if (index > 0) {
                    val previous = lines[index - 1].trim()
                    val hardBreak = line.isEmpty() || previous.isEmpty() ||
                        index in block.paragraphStartLines || isListItem(line) ||
                        previous.endsWith(":") || previous.endsWith("：")
                    append(if (hardBreak) "\n" else separator(previous.last(), line.first()))
                }
                append(line)
            }
        }
    }

    private fun separator(left: Char, right: Char): String =
        if (isCjk(left) && isCjk(right)) "" else " "

    private fun isCjk(char: Char): Boolean = Character.UnicodeScript.of(char.code) in setOf(
        Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA
    ) || char in "，。！？：；、（）「」『』【】《》〈〉…"
}
