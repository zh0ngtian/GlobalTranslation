package com.example.globaltranslation.core

import com.example.globaltranslation.core.model.*
import com.example.globaltranslation.core.util.*
import org.junit.Assert.*
import org.junit.Test

class PhotoPipelineTest {
    private fun block(id: String, text: String) = PhotoTextBlock(id, text, TextBounds(0f, 0f, 100f, 40f))

    @Test fun splittingPreservesEveryCharacterAndUnicode() {
        val original = "Torque 25 N·m. 日本語 😀 END\n".repeat(600)
        val parts = TranslationBatches.parts(listOf(block("a", original)), 37)
        assertEquals(original, parts.joinToString("") { it.text })
        assertTrue(parts.all { it.text.length <= 37 && !it.text.last().isHighSurrogate() && !it.text.first().isLowSurrogate() })
        assertEquals(parts.size, parts.map { it.id }.distinct().size)
        assertTrue(TranslationBatches.batches(parts, 80).all { batch -> batch.sumOf { it.text.length } <= 80 })
    }

    @Test fun batchesLimitCountAndOnlyCompleteBlocksAreAssembled() {
        val parts = TranslationBatches.parts((0..81).map { block("$it", "Text") })
        assertEquals(listOf(40, 40, 2), TranslationBatches.batches(parts).map { it.size })
        val split = TranslationBatches.parts(listOf(block("long", "123456789"), block("short", "OK")), 3)
        val results = split.drop(1).associate { it.id to "译文" }
        assertEquals(mapOf("short" to "译文"), TranslationBatches.assemble(split, results))
    }

    @Test fun boundsAndLetterboxingMatchPhoto() {
        assertEquals(TextBounds(0f, 2f, 100f, 50f), TextBounds(-5f, 2f, 120f, 80f).clipped(100, 50))
        assertNull(TextBounds(Float.NaN, 2f, 4f, 5f).clipped(100, 100))
        assertNull(TextBounds(100f, 2f, 120f, 5f).clipped(100, 100))
        assertEquals(TextBounds(0f, 150f, 300f, 450f), fitPhoto(100, 100, 300, 600).map(TextBounds(0f, 0f, 100f, 100f)))
        assertEquals(TextBounds(150f, 0f, 450f, 300f), fitPhoto(100, 100, 600, 300).map(TextBounds(0f, 0f, 100f, 100f)))
    }

    @Test fun defaultsAndTemplateSelectionStayIndependent() {
        val settings = TranslationSettings()
        assertEquals(TextScript.LATIN, settings.script)
        assertEquals("zh-Hans", settings.options.target.code)
        assertEquals("", settings.options.additionalRequirements)
        val selected = settings.copy(templates = listOf(PromptTemplate("t", "机械", "Use torque")), selectedTemplateId = "t")
        assertEquals("Use torque", selected.options.additionalRequirements)
        assertEquals(settings.options.target, selected.options.target)
        assertEquals("", selected.copy(selectedTemplateId = "missing").options.additionalRequirements)
    }
}
