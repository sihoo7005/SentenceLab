package com.sentencelab.app.data

import android.content.Context
import com.sentencelab.app.model.Sentence
import com.sentencelab.app.model.StudySet
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Local data stays in app-private storage until explicitly synchronized by the cloud client.
 * A temporary file is written first to avoid truncating the previous save.
 */
class StudyStorage(context: Context, fileName: String = "sets.json") {
    private val file = File(context.filesDir, fileName)

    fun load(): List<StudySet> {
        if (!file.exists()) return emptyList()
        return try {
            val all = JSONArray(file.readText(Charsets.UTF_8))
            (0 until all.length()).map { index ->
                val set = all.getJSONObject(index)
                val cards = set.optJSONArray("cards") ?: JSONArray()
                StudySet(
                    id = set.getString("id"),
                    title = set.getString("title"),
                    cards = (0 until cards.length()).map { item ->
                        val card = cards.getJSONObject(item)
                        val segments = card.optJSONArray("chunks") ?: JSONArray()
                        Sentence(
                            id = card.getString("id"),
                            english = card.getString("english"),
                            korean = card.getString("korean"),
                            chunks = (0 until segments.length()).map(segments::getString),
                            attempts = card.optInt("attempts"),
                            correct = card.optInt("correct"),
                            needsReview = card.optBoolean("needsReview")
                        )
                    }
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun save(sets: List<StudySet>) {
        val array = JSONArray()
        sets.forEach { set ->
            val cards = JSONArray()
            set.cards.forEach { card ->
                cards.put(
                    JSONObject()
                        .put("id", card.id)
                        .put("english", card.english)
                        .put("korean", card.korean)
                        .put("chunks", JSONArray(card.chunks))
                        .put("attempts", card.attempts)
                        .put("correct", card.correct)
                        .put("needsReview", card.needsReview)
                )
            }
            array.put(JSONObject().put("id", set.id).put("title", set.title).put("cards", cards))
        }
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(array.toString(), Charsets.UTF_8)
        if (!temp.renameTo(file)) {
            file.writeText(array.toString(), Charsets.UTF_8)
            temp.delete()
        }
    }
}
