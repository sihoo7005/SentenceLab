package com.sentencelab.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StudyModelsTest {
    @Test fun bulkSupportsTabsAndPipes() {
        val imported = parseBulk("Hello world\t안녕 세상\nHow are you?|||잘 지내?")
        assertEquals(2, imported.size)
        assertEquals("안녕 세상", imported.first().korean)
    }

    @Test fun bulkSkipsMalformedLines() {
        assertEquals(1, parseBulk("broken\nhello\t안녕\n\t뜻").size)
    }

    @Test fun answersIgnorePunctuationAndCase() {
        assertEquals(normalized("Hello, World!"), normalized("hello world"))
    }

    @Test fun customChunksMustMatchOriginal() {
        assertTrue(validChunks("I like music.", listOf("I", "like music.")))
        assertFalse(validChunks("I like music.", listOf("I", "love music.")))
    }

    @Test fun shortSentenceStaysInOneGroup() {
        assertEquals(1, orderWordGroups("One two three four five six seven").size)
    }

    @Test fun eightWordsStayTogether() {
        val groups = orderWordGroups("one two three four five six seven eight")
        assertEquals(listOf(8), groups.map { it.size })
    }

    @Test fun nineWordsSplitEightPlusOne() {
        val groups = orderWordGroups("one two three four five six seven eight nine")
        assertEquals(listOf(8, 1), groups.map { it.size })
    }

    @Test fun longerSentencesUseConsecutiveEightWordChunks() {
        val words = (1..18).joinToString(" ") { "word" + it }
        val groups = orderWordGroups(words)
        assertEquals(listOf(8, 8, 2), groups.map { it.size })
        assertEquals(words, groups.flatten().joinToString(" "))
        assertTrue(groups.all { it.size <= 8 })
    }

    @Test fun wordCheckHandlesPunctuationAndRepeats() {
        assertTrue(matchesNextWord("Hello,", "hello"))
        assertTrue(matchesNextWord("the", "the"))
        assertFalse(matchesNextWord("world", "word"))
    }
}
