package com.sentencelab.app.cloud

import com.sentencelab.app.model.StudySet

/**
 * One authenticated session, kept only in memory. A server revision is required
 * before writes. Concurrent edits never trigger an automatic snapshot overwrite.
 */
data class ServerStudyState(
    val acknowledged: CloudSnapshot? = null,
    val sets: List<StudySet> = emptyList(),
    val attempted: CloudSnapshot? = null,
    val conflict: Boolean = false
) {
    val ready: Boolean get() = acknowledged != null
    val dirty: Boolean get() = acknowledged != null && sets != acknowledged.sets

    fun edit(updated: List<StudySet>): ServerStudyState {
        check(ready) { "Load server data first" }
        return copy(sets = updated)
    }

    fun nextUpload(): CloudSnapshot? =
        if (dirty && !conflict && attempted == null)
            CloudSnapshot(acknowledged!!.revision, sets) else null

    fun beginUpload(upload: CloudSnapshot): ServerStudyState {
        check(upload == nextUpload())
        return copy(attempted = upload)
    }

    fun uploaded(upload: CloudSnapshot, revision: Int): ServerStudyState {
        check(attempted == upload)
        return copy(acknowledged = CloudSnapshot(revision, upload.sets), attempted = null)
    }

    fun observe(remote: CloudSnapshot): ServerStudyState {
        val previous = acknowledged
        if (previous == null) return ServerStudyState(acknowledged = remote, sets = remote.sets)
        val pending = attempted
        if (pending != null) {
            // A request may have committed even when its response was lost.
            if (remote.sets == pending.sets) {
                return copy(acknowledged = remote, attempted = null, conflict = false)
            }
            if (remote.revision == previous.revision && remote.sets == previous.sets) {
                return copy(attempted = null, conflict = false)
            }
            return copy(conflict = true)
        }
        if (!dirty) return ServerStudyState(acknowledged = remote, sets = remote.sets)
        if (remote.sets == sets) return ServerStudyState(acknowledged = remote, sets = remote.sets)
        if (remote.revision != previous.revision || remote.sets != previous.sets) {
            return copy(conflict = true)
        }
        return this
    }
}
