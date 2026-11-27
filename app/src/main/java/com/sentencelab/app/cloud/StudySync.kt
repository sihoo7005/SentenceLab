package com.sentencelab.app.cloud

import com.sentencelab.app.model.Sentence
import com.sentencelab.app.model.StudySet

/**
 * Three-way merge against the last acknowledged server snapshot.
 * Never replace local edits with a remote snapshot without comparing both to their common base.
 */
object StudyMerge {
    fun merge(base: List<StudySet>?, local: List<StudySet>, remote: List<StudySet>): List<StudySet> {
        val old = base.orEmpty().associateBy { it.id }
        val ours = local.associateBy { it.id }
        val theirs = remote.associateBy { it.id }
        val order = (local.map { it.id } + remote.map { it.id }).distinct()
        return order.mapNotNull { id ->
            mergeSet(old[id], ours[id], theirs[id])
        }
    }

    private fun mergeSet(base: StudySet?, local: StudySet?, remote: StudySet?): StudySet? {
        if (base == null) {
            if (local == null) return remote
            if (remote == null) return local
            return local.copy(cards = mergeCards(null, local.cards, remote.cards))
        }
        if (local == null && remote == null) return null
        if (local == null) return if (remote == base) null else remote
        if (remote == null) return if (local == base) null else local
        return StudySet(
            id = local.id,
            title = if (local.title != base.title) local.title else remote.title,
            cards = mergeCards(base.cards, local.cards, remote.cards)
        )
    }

    private fun mergeCards(
        base: List<Sentence>?, local: List<Sentence>, remote: List<Sentence>
    ): List<Sentence> {
        val old = base.orEmpty().associateBy { it.id }
        val ours = local.associateBy { it.id }
        val theirs = remote.associateBy { it.id }
        return (local.map { it.id } + remote.map { it.id }).distinct().mapNotNull { id ->
            mergeCard(old[id], ours[id], theirs[id])
        }
    }

    private fun mergeCard(base: Sentence?, local: Sentence?, remote: Sentence?): Sentence? {
        if (base == null) return when {
            local == null -> remote
            remote == null -> local
            else -> local.copy(completed = local.completed || remote.completed, modeMask = local.modeMask or remote.modeMask)
        }
        if (local == null && remote == null) return null
        if (local == null) return if (remote == base) null else remote
        if (remote == null) return if (local == base) null else local
        if (local == remote) return local
        // A deliberate reset must beat stale completed flags and counters from other devices.
        val wasReset = hasBeenReset(base, local) || hasBeenReset(base, remote)
        val newAttempts = base.attempts +
            (local.attempts - base.attempts).coerceAtLeast(0) +
            (remote.attempts - base.attempts).coerceAtLeast(0)
        val newCorrect = base.correct +
            (local.correct - base.correct).coerceAtLeast(0) +
            (remote.correct - base.correct).coerceAtLeast(0)
        return Sentence(
            id = local.id,
            english = if (local.english != base.english) local.english else remote.english,
            korean = if (local.korean != base.korean) local.korean else remote.korean,
            chunks = if (local.chunks != base.chunks) local.chunks else remote.chunks,
            attempts = if (wasReset) 0 else newAttempts,
            correct = if (wasReset) 0 else newCorrect.coerceAtMost(newAttempts),
            needsReview = if (wasReset) false else
                if (local.needsReview != base.needsReview) local.needsReview else remote.needsReview,
            completed = if (wasReset) false else local.completed || remote.completed,
            modeMask = if (wasReset) 0 else local.modeMask or remote.modeMask
        )
    }

    private fun hasBeenReset(base: Sentence, updated: Sentence): Boolean {
        val previouslyStudied = base.attempts > 0 || base.correct > 0 ||
            base.completed || base.needsReview || base.modeMask != 0
        return previouslyStudied && updated.attempts == 0 && updated.correct == 0 &&
            !updated.completed && !updated.needsReview && updated.modeMask == 0
    }
}

