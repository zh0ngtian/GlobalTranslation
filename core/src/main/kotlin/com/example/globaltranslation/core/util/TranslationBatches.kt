package com.example.globaltranslation.core.util

import com.example.globaltranslation.core.model.PhotoTextBlock

data class TranslationPart(val id: String, val blockId: String, val text: String)

/** Limits request size without losing text, including oversized individual OCR blocks. */
object TranslationBatches {
    fun parts(blocks: List<PhotoTextBlock>, maxCharacters: Int = 6000): List<TranslationPart> {
        require(maxCharacters >= 2)
        require(blocks.map { it.id }.distinct().size == blocks.size)
        return blocks.flatMapIndexed { blockIndex, block ->
            val result = mutableListOf<TranslationPart>()
            var start = 0
            while (start < block.text.length) {
                var end = minOf(start + maxCharacters, block.text.length)
                if (end < block.text.length) {
                    // Keep surrogate pairs intact, then prefer a nearby sentence/word boundary.
                    if (block.text[end - 1].isHighSurrogate() && block.text[end].isLowSurrogate()) end--
                    val boundary = (end - 1 downTo start + (end - start) / 2)
                        .firstOrNull { block.text[it].isWhitespace() }
                    if (boundary != null) end = boundary + 1
                }
                result += TranslationPart("b${blockIndex}p${result.size}", block.id, block.text.substring(start, end))
                start = end
            }
            result
        }
    }

    fun batches(parts: List<TranslationPart>, maxCharacters: Int = 6000): List<List<TranslationPart>> {
        val batches = mutableListOf<List<TranslationPart>>()
        var batch = mutableListOf<TranslationPart>()
        var size = 0
        for (part in parts) {
            if (batch.isNotEmpty() && (size + part.text.length > maxCharacters || batch.size >= 40)) {
                batches += batch
                batch = mutableListOf()
                size = 0
            }
            batch += part
            size += part.text.length
        }
        if (batch.isNotEmpty()) batches += batch
        return batches
    }

    fun assemble(parts: List<TranslationPart>, translated: Map<String, String>): Map<String, String> =
        parts.groupBy { it.blockId }.mapNotNull { (blockId, pieces) ->
            if (pieces.all { translated.containsKey(it.id) })
                blockId to pieces.joinToString("\n") { translated.getValue(it.id) }
            else null
        }.toMap()
}
