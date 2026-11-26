package com.sentencelab.app.cloud

import com.sentencelab.app.model.Sentence
import com.sentencelab.app.model.StudySet
import com.sentencelab.app.model.resetStudyProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StudyMergeTest {
    private fun card(id: String, attempts: Int = 0, correct: Int = 0) =
        Sentence(id = id, english = "Hello world.", korean = "안녕.", attempts = attempts, correct = correct)
    private fun group(vararg cards: Sentence) = StudySet(id = "study", title = "학습", cards = cards.toList())

    @Test fun firstSyncRetainsBothSides() {
        val merged = StudyMerge.merge(null, listOf(group(card("a"))), listOf(group(card("b"))))
        assertEquals(listOf("a", "b"), merged.single().cards.map { it.id })
    }

    @Test fun localDeletionIsPropagatedWhenRemoteUnchanged() {
        val base = group(card("a"), card("b"))
        val merged = StudyMerge.merge(listOf(base), listOf(group(card("b"))), listOf(base))
        assertEquals(listOf("b"), merged.single().cards.map { it.id })
    }

    @Test fun remoteDeletionIsPropagatedWhenLocalUnchanged() {
        val base = group(card("a"), card("b"))
        val merged = StudyMerge.merge(listOf(base), listOf(base), listOf(group(card("a"))))
        assertEquals(listOf("a"), merged.single().cards.map { it.id })
    }

    @Test fun differentDevicesProgressSumsWithoutDroppingEither() {
        val base = group(card("a", 2, 1))
        val local = group(card("a", 3, 2))
        val remote = group(card("a", 4, 1))
        val merged = StudyMerge.merge(listOf(base), listOf(local), listOf(remote))
        assertEquals(5, merged.single().cards.single().attempts)
        assertEquals(2, merged.single().cards.single().correct)
    }

    @Test fun identicalUpdatesAreNotCountedTwice() {
        val base = group(card("a", 2, 1))
        val updated = group(card("a", 3, 2))
        val merged = StudyMerge.merge(listOf(base), listOf(updated), listOf(updated))
        assertEquals(3, merged.single().cards.single().attempts)
        assertEquals(2, merged.single().cards.single().correct)
    }

    @Test fun changedCardIsNotDeletedByConflictingDelete() {
        val base = group(card("a"))
        val modified = group(card("a", 1, 1))
        val merged = StudyMerge.merge(listOf(base), listOf(group()), listOf(modified))
        assertEquals(1, merged.single().cards.size)
    }

    @Test fun localAndRemoteNewSetsBothSurvive() {
        val merged = StudyMerge.merge(null, listOf(StudySet(id="local", title="A")),
            listOf(StudySet(id="remote", title="B")))
        assertEquals(2, merged.size)
        assertTrue(merged.any { it.id=="local" })
        assertFalse(merged.none { it.id=="remote" })
    }
    @Test fun completedStateSurvivesOtherDeviceWithUnfinishedCopy() {
        val base = group(card("a"))
        val finished = group(card("a").copy(attempts = 1, correct = 1, completed = true))
        val merged = StudyMerge.merge(listOf(base), listOf(base), listOf(finished))
        assertTrue(merged.single().cards.single().completed)
    }

    @Test fun completedStateIsNeverLostWhenLocalAndRemoteProgressDiffer() {
        val base = group(card("a"))
        val finished = group(card("a").copy(attempts = 1, completed = true))
        val otherDevice = group(card("a").copy(attempts = 1, correct = 1, completed = false))
        val merged = StudyMerge.merge(listOf(base), listOf(finished), listOf(otherDevice))
        assertTrue(merged.single().cards.single().completed)
        assertEquals(2, merged.single().cards.single().attempts)
    }

    @Test fun resetLeavesSentenceAndTranslationUntouched() {
        val studied = card("a", 3, 2).copy(
            english = "Test sentence.", korean = "시험 문장.",
            chunks = listOf("Test", "sentence."),
            completed = true, needsReview = true
        )
        val reset = resetStudyProgress(listOf(studied)).single()
        assertEquals(studied.id, reset.id)
        assertEquals(studied.english, reset.english)
        assertEquals(studied.korean, reset.korean)
        assertEquals(studied.chunks, reset.chunks)
        assertEquals(0, reset.attempts)
        assertEquals(0, reset.correct)
        assertFalse(reset.completed)
        assertFalse(reset.needsReview)
    }

    @Test fun resetOnOneSetDoesNotChangeOtherSet() {
        val first = group(card("a", 2, 1).copy(completed = true))
        val other = StudySet(id = "other", title = "다른 세트",
            cards = listOf(card("b", 4, 3).copy(completed = true)))
        val updated = listOf(first.copy(cards = resetStudyProgress(first.cards)), other)
        assertFalse(updated.first().cards.single().completed)
        assertEquals(4, updated.last().cards.single().attempts)
        assertTrue(updated.last().cards.single().completed)
    }

    @Test fun localResetWinsAgainstOldRemoteCompletion() {
        val baseCard = card("a", 3, 2).copy(completed = true, needsReview = true)
        val base = group(baseCard)
        val local = group(resetStudyProgress(listOf(baseCard)).single())
        val remote = group(baseCard.copy(attempts = 4, correct = 3))
        val result = StudyMerge.merge(listOf(base), listOf(local), listOf(remote))
            .single().cards.single()
        assertEquals(0, result.attempts)
        assertEquals(0, result.correct)
        assertFalse(result.completed)
        assertFalse(result.needsReview)
    }

    @Test fun remoteResetWinsAndRetainsNewTranslation() {
        val baseCard = card("a", 2, 1).copy(completed = true)
        val base = group(baseCard)
        val local = group(baseCard.copy(attempts = 3, correct = 2, korean = "새 해석"))
        val remote = group(resetStudyProgress(listOf(baseCard)).single())
        val result = StudyMerge.merge(listOf(base), listOf(local), listOf(remote))
            .single().cards.single()
        assertEquals(0, result.attempts)
        assertFalse(result.completed)
        assertEquals("새 해석", result.korean)
    }

    @Test fun studyingAgainAfterResetWorksNormally() {
        val base = group(card("a"))
        val local = group(card("a", 1, 1).copy(completed = true))
        val result = StudyMerge.merge(listOf(base), listOf(local), listOf(base))
            .single().cards.single()
        assertEquals(1, result.attempts)
        assertTrue(result.completed)
    }

    @Test fun firstSyncWithSameCardIdKeepsCompletedFlag() {
        val unfinished = group(card("a"))
        val finished = group(card("a").copy(completed = true))
        val merged = StudyMerge.merge(null, listOf(unfinished), listOf(finished))
        assertTrue(merged.single().cards.single().completed)
    }


    @Test fun concurrentModesAreCombinedAndResetClearsModes() {
        val base = group(card("a"))
        val local = group(card("a", 1, 1).copy(completed = true, modeMask = 2))
        val remote = group(card("a", 1, 1).copy(completed = true, modeMask = 8))
        val merged = StudyMerge.merge(listOf(base), listOf(local), listOf(remote)).single().cards.single()
        assertEquals(10, merged.modeMask)
        val reset = group(resetStudyProgress(listOf(merged)).single())
        val stale = group(merged.copy(attempts = 3))
        assertEquals(0, StudyMerge.merge(listOf(group(merged)), listOf(reset), listOf(stale)).single().cards.single().modeMask)
    }
}
