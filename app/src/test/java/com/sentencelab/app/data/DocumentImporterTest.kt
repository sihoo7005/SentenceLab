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
    fun blankLinesAreIgnored() {
        assertTrue(DocumentImporter.splitImportedSentences("  \n\n").isEmpty())
    }
}
