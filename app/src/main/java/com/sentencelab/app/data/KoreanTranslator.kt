package com.sentencelab.app.data

import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.sentencelab.app.model.Sentence
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await

/** The English/Korean translation model runs locally after its initial download. */
object KoreanTranslator {
    private val englishLetters = Regex("[A-Za-z]")

    fun missing(cards: List<Sentence>): List<Sentence> =
        cards.filter { it.korean.isBlank() && englishLetters.containsMatchIn(it.english) }

    /**
     * Translate only missing Korean definitions. Successfully translated batches are committed
     * independently, so a model/network failure or cancellation cannot erase imported originals.
     */
    suspend fun fillMissing(
        cards: List<Sentence>,
        onProgress: (Int, Int) -> Unit,
        onBatch: (Map<String, String>) -> Unit
    ): Pair<Int, Int> {
        val pending = missing(cards)
        if (pending.isEmpty()) return 0 to 0

        val options = TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.KOREAN)
            .build()
        val translator = Translation.getClient(options)
        var translatedCount = 0
        var failures = 0
        val batch = LinkedHashMap<String, String>()

        try {
            // Download once if necessary. The cached model can subsequently run without a network.
            translator.downloadModelIfNeeded().await()
            for ((index, card) in pending.withIndex()) {
                try {
                    val translated = translator.translate(card.english).await().trim()
                    if (translated.isNotBlank()) {
                        batch[card.id] = translated
                        translatedCount++
                    } else failures++
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    failures++
                }
                if (batch.size >= 10) {
                    onBatch(batch.toMap())
                    batch.clear()
                }
                onProgress(index + 1, pending.size)
            }
            return translatedCount to failures
        } finally {
            // Commit any successful results even if a later item failed or the user cancelled.
            if (batch.isNotEmpty()) onBatch(batch.toMap())
            translator.close()
        }
    }
}
