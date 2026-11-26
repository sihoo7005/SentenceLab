package com.sentencelab.app.cloud

import android.content.Context
import com.sentencelab.app.data.StudyStorage
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
            else -> local.copy(completed = local.completed || remote.completed)
        }
        if (local == null && remote == null) return null
        if (local == null) return if (remote == base) null else remote
        if (remote == null) return if (local == base) null else local
        if (local == remote) return local
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
            attempts = newAttempts,
            correct = newCorrect.coerceAtMost(newAttempts),
            needsReview = if (local.needsReview != base.needsReview) local.needsReview else remote.needsReview,
            completed = local.completed || remote.completed
        )
    }
}

data class SyncResult(val sets: List<StudySet>, val revision: Int)

/**
 * Persisted common ancestor supports safe edits and deletions across devices.
 * Without a common ancestor (first upgrade), both sides are kept rather than overwritten.
 */
class StudySync(context: Context) {
    private val app = context.applicationContext
    private val api = CloudApi()
    private val baselineStore = StudyStorage(app, "sync-baseline.json")
    private val prefs = app.getSharedPreferences("study_sync", Context.MODE_PRIVATE)

    suspend fun reconcile(token: String, local: List<StudySet>): SyncResult {
        val accountId = api.accountId(token)
        val previousAccount = prefs.getString("account_id", null)
        if (previousAccount != null && previousAccount != accountId && local.isNotEmpty()) {
            error("다른 Google 계정이 감지되어 동기화를 중단했습니다. 기존 기기 데이터를 보호하기 위한 조치입니다.")
        }
        val knownBase = if (previousAccount == accountId && prefs.getInt("revision", -1) >= 0) {
            baselineStore.load()
        } else null
        repeat(4) { attempt ->
            val remote = api.getSnapshot(token)
            val merged = StudyMerge.merge(knownBase, local, remote.sets)
            try {
                val revision = if (merged == remote.sets) remote.revision
                    else api.upload(token, remote.revision, merged)
                // Persist the acknowledged server snapshot before the revision marker.
                baselineStore.save(merged)
                prefs.edit().putString("account_id", accountId)
                    .putInt("revision", revision).commit()
                return SyncResult(merged, revision)
            } catch (e: CloudConflictException) {
                if (attempt == 3) throw IllegalStateException(
                    "다른 기기에서 데이터가 계속 변경되고 있습니다. 잠시 후 다시 동기화합니다.", e
                )
            }
        }
        error("동기화에 실패했습니다.")
    }
}
