package com.example.globaltranslation.core.util

import com.example.globaltranslation.core.model.*
import org.junit.Assert.*
import org.junit.Test

class ParagraphTextTest {
    private fun block(text: String, starts: Set<Int> = emptySet()) =
        PhotoTextBlock("source", text, TextBounds(0f, 0f, 300f, 100f), paragraphStartLines = starts)

    @Test fun visualWrapsBecomeCoherentProseWithoutJoiningLatinWords() {
        listOf(
            "Please keep this door\nclosed at all times." to "Please keep this door closed at all times.",
            "Veuillez garder cette porte\r\nfermée en permanence." to "Veuillez garder cette porte fermée en permanence.",
            "Si prega di tenere questa porta\rsempre chiusa." to "Si prega di tenere questa porta sempre chiusa.",
            "このドアは常に\n閉めてください。" to "このドアは常に閉めてください。",
            "请始终保持\n此门关闭。" to "请始终保持此门关闭。",
            "전원을 끈 후\n작업하십시오." to "전원을 끈 후 작업하십시오."
        ).forEach { (source, expected) -> assertEquals(expected, ParagraphText.prepare(block(source))) }
    }

    @Test fun paragraphsHeadingsAndListItemsKeepTheirBoundariesButItemsMayWrap() {
        val source = "INSTRUCTIONS\nPlease keep this door\nclosed.\n\nBefore leaving:\n1. Switch off the\npower.\n2. Close the door.\n•Keep your ticket.\n一、保留\n票据。"
        val expected = "INSTRUCTIONS\nPlease keep this door closed.\n\nBefore leaving:\n1. Switch off the power.\n2. Close the door.\n•Keep your ticket.\n一、保留票据。"
        assertEquals(expected, ParagraphText.prepare(block(source, setOf(1))))
        assertEquals("First paragraph.\nSecond paragraph.", ParagraphText.prepare(block("First paragraph.\nSecond paragraph.", setOf(1))))
    }

    @Test fun numbersHyphensIdentifiersAndOriginalBlockArePreserved() {
        val original = block("Use the inter-\nlocking mechanism at 12.5 bar.\nModel GT-\n250 supports 25 N·m.")
        val snapshot = original.copy()
        assertEquals("Use the inter- locking mechanism at 12.5 bar. Model GT- 250 supports 25 N·m.", ParagraphText.prepare(original))
        assertFalse(ParagraphText.isListItem("12.5 bar"))
        assertEquals(snapshot, original)
        assertEquals("", ParagraphText.prepare(block("")))
        assertEquals("\n\n", ParagraphText.prepare(block("\n\n")))
    }

    @Test fun ocrGeometrySeparatesHeadingParagraphGapAndIndentation() {
        val lines = listOf(
            ParagraphLine("HEADING", TextBounds(0f, 0f, 200f, 40f)),
            ParagraphLine("Wrapped paragraph", TextBounds(0f, 45f, 300f, 65f)),
            ParagraphLine("continues here.", TextBounds(0f, 70f, 220f, 90f)),
            ParagraphLine("New paragraph", TextBounds(0f, 115f, 280f, 135f)),
            ParagraphLine("Indented paragraph", TextBounds(30f, 140f, 280f, 160f))
        )
        assertEquals(setOf(1, 3, 4), ParagraphText.paragraphStarts(lines))
        assertEquals("HEADING\nWrapped paragraph continues here.\nNew paragraph\nIndented paragraph",
            ParagraphText.prepare(block(lines.joinToString("\n") { it.text }, ParagraphText.paragraphStarts(lines))))
    }

    @Test fun hangingIndentWithinNumberedItemRemainsAContinuation() {
        val lines = listOf(
            ParagraphLine("1. Keep the", TextBounds(0f, 0f, 250f, 20f)),
            ParagraphLine("door closed.", TextBounds(35f, 25f, 250f, 45f))
        )
        assertTrue(ParagraphText.paragraphStarts(lines).isEmpty())
        assertEquals("1. Keep the door closed.", ParagraphText.prepare(block(lines.joinToString("\n") { it.text })))
    }
}
