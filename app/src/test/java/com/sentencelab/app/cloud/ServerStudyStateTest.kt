package com.sentencelab.app.cloud

import com.sentencelab.app.model.StudySet
import org.junit.Assert.*
import org.junit.Test

class ServerStudyStateTest {
    private fun sets(vararg ids: String) = ids.map { StudySet(id = it, title = it) }
    private fun load(vararg ids: String) = ServerStudyState().observe(CloudSnapshot(2, sets(*ids)))

    @Test fun loginRequiresServerSnapshotAndNeverUploadsAnEmptyDefault() {
        val fresh = ServerStudyState()
        assertFalse(fresh.ready)
        assertNull(fresh.nextUpload())
        val loaded = fresh.observe(CloudSnapshot(2, sets("remote")))
        assertEquals(sets("remote"), loaded.sets)
        assertFalse(loaded.dirty)
        assertNull(loaded.nextUpload())
    }
    @Test fun newAccountDoesNotInheritPreviousSets() {
        val old = load("old").edit(sets("old", "unsaved"))
        val next = ServerStudyState().observe(CloudSnapshot(8, sets("new")))
        assertTrue(old.dirty)
        assertEquals(sets("new"), next.sets)
        assertNull(next.nextUpload())
    }
    @Test fun newEditWhileUploadIsRunningRemainsPending() {
        val changed = load("old").edit(sets("one"))
        val upload = changed.nextUpload()!!
        val saved = changed.beginUpload(upload).edit(sets("two")).uploaded(upload, 3)
        assertEquals(sets("one"), saved.acknowledged!!.sets)
        assertEquals(sets("two"), saved.sets)
        assertEquals(CloudSnapshot(3, sets("two")), saved.nextUpload())
    }
    @Test fun lostResponseIsRecognizedWithoutDuplicateWrite() {
        val changed = load("old").edit(sets("new"))
        val upload = changed.nextUpload()!!
        val verified = changed.beginUpload(upload).observe(CloudSnapshot(3, sets("new")))
        assertFalse(verified.dirty)
        assertNull(verified.nextUpload())
    }
    @Test fun failedUploadRetriesOnlyIfServerUnchanged() {
        val changed = load("old").edit(sets("new"))
        val upload = changed.nextUpload()!!
        val retry = changed.beginUpload(upload).observe(CloudSnapshot(2, sets("old")))
        assertEquals(upload, retry.nextUpload())
        val conflict = changed.beginUpload(upload).observe(CloudSnapshot(3, sets("other")))
        assertTrue(conflict.conflict)
        assertTrue(conflict.dirty)
        assertNull(conflict.nextUpload())
        assertEquals(sets("new"), conflict.sets)
    }
    @Test fun remoteRefreshCannotOverwriteEditsMadeDuringFetch() {
        val changed = load("old").edit(sets("new"))
        val conflict = changed.observe(CloudSnapshot(3, sets("other")))
        assertTrue(conflict.conflict)
        assertEquals(sets("new"), conflict.sets)
        assertNull(conflict.nextUpload())
    }
    @Test fun cleanRefreshUsesServerAndDiscardDoesNotUpload() {
        val refreshed = load("old").observe(CloudSnapshot(3, sets("new")))
        assertEquals(sets("new"), refreshed.sets)
        assertFalse(refreshed.dirty)
        assertNull(ServerStudyState().nextUpload())
    }
}
