package com.example.globaltranslation.core.util

import com.example.globaltranslation.core.model.*
import org.junit.Assert.*
import org.junit.Test

class DocumentLayoutTest {
    @Test fun wideHeadingAndTwoColumnsKeepReadingOrderWithoutMixing() {
        val words = mutableListOf(OcrWord("HEADING", TextBounds(30f, 20f, 550f, 45f)))
        for (column in 0..1) for (row in 0..19) for (word in 0..3) {
            val x = 30f + column * 300 + word * 55
            val y = 100f + row * 24
            words += OcrWord("${if (column == 0) "LEFT" else "RIGHT"}$row-$word", TextBounds(x, y, x + 48, y + 16))
        }
        val regions = DocumentLayout.columns(words, 620, 650)
        assertTrue(regions.size >= 3)
        val blocks = DocumentLayout.blocks(words, regions)
        assertEquals("HEADING", blocks.first().text)
        assertFalse(blocks.any { "LEFT" in it.text && "RIGHT" in it.text })
        assertEquals(words.size, blocks.sumOf { it.text.split(Regex("\\s+")).size })
        assertTrue(blocks.indexOfFirst { "RIGHT" in it.text } > blocks.indexOfLast { "LEFT" in it.text })
    }

    @Test fun tileSeamsHaveOverlapAndDuplicateWordsAreRemovedOnlyAtSamePosition() {
        val tiles = DocumentLayout.tiles(TextBounds(0f, 0f, 600f, 1500f), 600f, 800f, 80f)
        assertEquals(2, tiles.size)
        assertEquals(80f, tiles[0].bottom - tiles[1].top, .001f)
        val words = listOf(
            OcrWord("animal", TextBounds(20f, 720f, 80f, 736f), tile = 1, edgeDistance = 1f),
            OcrWord("animals", TextBounds(20f, 720f, 88f, 736f), tile = 2, edgeDistance = 40f),
            OcrWord("animals", TextBounds(20f, 750f, 88f, 766f), tile = 2, edgeDistance = 40f))
        val kept = DocumentLayout.deduplicate(words)
        assertEquals(2, kept.size)
        assertTrue(kept.all { it.text == "animals" })
    }
}
