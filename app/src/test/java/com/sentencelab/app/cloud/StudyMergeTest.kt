package com.sentencelab.app.cloud

import com.sentencelab.app.model.Sentence
import com.sentencelab.app.model.StudySet
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

    @Test fun firstSyncWithSameCardIdKeepsCompletedFlag() {
        val unfinished = group(card("a"))
        val finished = group(card("a").copy(completed = true))
        val merged = StudyMerge.merge(null, listOf(unfinished), listOf(finished))
        assertTrue(merged.single().cards.single().completed)
    }

}
