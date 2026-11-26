package com.sentencelab.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentImporterTest {
    @Test
    fun textDocumentPreservesOriginalSentenceWording() {
        val text = "First sentence is here.\nSecond sentence stays here!"
        val cards = DocumentImporter.splitImportedSentences(text)
        assertEquals(listOf("First sentence is here.", "Second sentence stays here!"), cards)
    }

    @Test
    fun pdfLineWrapsAreJoinedBeforeSplitting() {
        val text = "The subscription economy offers a\nwide variety of services.\nConsumers enjoy it."
        val cards = DocumentImporter.splitImportedSentences(text, pdf = true)
        assertEquals(
            listOf("The subscription economy offers a wide variety of services.", "Consumers enjoy it."),
            cards
        )
    }

    @Test
    fun academicTitlesAreNotMergedWithTheFirstSentence() {
        val doc = "The Subscription Economy: From Ownership to Access\n\n" +
            "Jiyun starts her day by listening to music.\n" +
            "She learns through online videos."
        assertEquals(
            listOf("Jiyun starts her day by listening to music.", "She learns through online videos."),
            DocumentImporter.splitImportedSentences(doc)
        )
    }

    @Test
    fun pdfHeadingsDoNotAttachToBodyText() {
        val pdf = "Online platforms drive the subscription economy\n" +
            "With the development of digital devices,\n" +
            "consumers can do many things easily."
        assertEquals(
            listOf("With the development of digital devices, consumers can do many things easily."),
            DocumentImporter.splitImportedSentences(pdf, pdf = true)
        )
    }

    @Test
    fun doctorAbbreviationDoesNotSplitTheSentence() {
        val text = "According to Dr. Carlos Duarte, the ocean stores carbon."
        assertEquals(listOf(text), DocumentImporter.splitImportedSentences(text))
    }

    @Test
    fun quotedQuestionBeforeLowerCaseReportingClauseStaysTogether() {
        val text = "When she asked, \"Is it safe?\" he replied, \"Yes.\""
        assertEquals(listOf(text), DocumentImporter.splitImportedSentences(text))
    }

    @Test
    fun linesWithoutTerminalPunctuationStillSplitInTextDocuments() {
        val text = "I like music\nYou enjoy reading"
        assertEquals(
            listOf("I like music", "You enjoy reading"),
            DocumentImporter.splitImportedSentences(text)
        )
    }

    @Test
    fun blankLinesAreIgnored() {
        assertTrue(DocumentImporter.splitImportedSentences("  \n\n").isEmpty())
    }
}
