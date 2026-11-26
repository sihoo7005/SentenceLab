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
    @Test fun completedCardsDisappearOnlyFromCompletedModes() {
        val finished = Sentence(id = "done", english = "Done.", korean = "끝", completed = true, modeMask = 15)
        val fresh = Sentence(id = "new", english = "New.", korean = "새로운", completed = false)
        for (mode in StudyMode.entries) {
            val list = eligibleForStudy(listOf(finished, fresh), mode, reviewOnly = false)
            assertEquals(listOf("new"), list.map { it.id })
        }
    }

    @Test fun explicitlyRequestedReviewCanIncludeCompletedMistakes() {
        val mistake = Sentence(
            id = "mistake", english = "Hello.", korean = "안녕",
            completed = true, needsReview = true, modeMask = StudyMode.ORDER.bit
        )
        assertEquals(
            listOf("mistake"),
            eligibleForStudy(listOf(mistake), StudyMode.ORDER, reviewOnly = true).map { it.id }
        )
        assertTrue(eligibleForStudy(listOf(mistake), StudyMode.ORDER, reviewOnly = false).isEmpty())
    }

    @Test fun normalSpellModeSkipsCardsWithoutKoreanTranslation() {
        val missing = Sentence(id = "no-meaning", english = "Hello.", korean = "")
        val meaningful = Sentence(id = "meaning", english = "World.", korean = "세상")
        assertEquals(
            listOf("meaning"),
            eligibleForStudy(listOf(missing, meaningful), StudyMode.SPELL, false).map { it.id }
        )
    }


    @Test fun blankUsesImportantWordsAndPreservesCompounds() {
        val words = blankWords("The well-known researcher can't overlook evidence.", BlankDifficulty.HARD, kotlin.random.Random(1))
        assertTrue(words.all { it.answer.lowercase() != "the" })
        assertTrue(words.all { it.answer in listOf("well-known", "researcher", "can't", "overlook", "evidence") })
        assertEquals(words.size, Regex("_____").findAll(blankPrompt("The well-known researcher can't overlook evidence.", words)).count())
        assertTrue(matchesBlank("can’t", "CAN'T"))
        assertFalse(matchesBlank("well-known", "wellknown"))
    }
    @Test fun difficultyRaisesBlankCountAndModesAreIndependent() {
        val sentence = "Researchers carefully examine complex evidence before developing reliable conclusions."
        assertTrue(blankWords(sentence, BlankDifficulty.HARD).size > blankWords(sentence, BlankDifficulty.EASY).size)
        val card = Sentence(english = sentence, korean = "연구", completed = true, modeMask = StudyMode.BLANK.bit)
        assertTrue(eligibleForStudy(listOf(card), StudyMode.BLANK, false).isEmpty())
        assertEquals(1, eligibleForStudy(listOf(card), StudyMode.ORDER, false).size)
        assertEquals(0, resetStudyProgress(listOf(card)).single().modeMask)
    }
    @Test fun legacyProgressIsPreservedButDoesNotClaimSpecificMode() {
        val card = Sentence(english = "Legacy sentence.", korean = "이전 문장", attempts = 1, correct = 1, completed = true)
        assertEquals(1, eligibleForStudy(listOf(card), StudyMode.BLANK, false).size)
        assertEquals(1, card.correct)
    }
}
