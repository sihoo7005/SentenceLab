package com.sentencelab.app.model

import java.util.Locale
import java.util.UUID

data class Sentence(
    val id: String = UUID.randomUUID().toString(),
    val english: String,
    val korean: String,
    val chunks: List<String> = english.trim().split(Regex("\\s+")),
    val attempts: Int = 0,
    val correct: Int = 0,
    val needsReview: Boolean = false,
    val completed: Boolean = false
)

data class StudySet(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val cards: List<Sentence> = emptyList()
)

enum class StudyMode(val label: String) {
    FLASHCARD("암기"),
    ORDER("어순 배열"),
    SPELL("영작"),
    BLANK("빈칸")
}

data class BulkEntry(val english: String, val korean: String)

fun parseBulk(text: String): List<BulkEntry> = text.lineSequence().mapNotNull { line ->
    val delimiter = when {
        '\t' in line -> "\t"
        "|||" in line -> "|||"
        else -> return@mapNotNull null
    }
    val parts = line.split(delimiter, limit = 2)
    val english = parts[0].trim().removePrefix("\uFEFF")
    val korean = parts.getOrElse(1) { "" }.trim()
    if (english.isBlank() || korean.isBlank()) null else BulkEntry(english, korean)
}.toList()

fun normalized(text: String): String =
    text.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), "")

fun parseChunks(english: String, raw: String): List<String> =
    if (raw.isBlank()) english.trim().split(Regex("\\s+"))
    else raw.split("|").map(String::trim).filter(String::isNotEmpty)

fun validChunks(english: String, parts: List<String>): Boolean =
    parts.isNotEmpty() && normalized(english) == normalized(parts.joinToString(" "))


/** Split long sentences into consecutive eight-word chunks, keeping the final remainder. */
fun orderWordGroups(english: String): List<List<String>> {
    val words = english.trim().split(Regex("\\s+")).filter(String::isNotEmpty)
    return words.chunked(8)
}

/** Duplicate words are valid choices for the next position, regardless of their source index. */
fun matchesNextWord(expected: String, choice: String): Boolean =
    normalized(expected) == normalized(choice)


/** Regular study skips completed cards; explicit review can still revisit mistakes. */
fun eligibleForStudy(cards: List<Sentence>, mode: StudyMode, reviewOnly: Boolean): List<Sentence> =
    cards.filter { card ->
        (if (reviewOnly) card.needsReview else !card.completed) &&
            (mode != StudyMode.SPELL || card.korean.isNotBlank())
    }

/** Reset only study progress; keep card IDs, sentences, translations and chunks. */
fun resetStudyProgress(cards: List<Sentence>): List<Sentence> =
    cards.map { it.copy(attempts = 0, correct = 0, needsReview = false, completed = false) }
