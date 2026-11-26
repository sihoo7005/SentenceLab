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
    val completed: Boolean = false,
    val modeMask: Int = 0
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
    BLANK("빈칸");

    val bit: Int get() = 1 shl ordinal
}

fun Sentence.completedIn(mode: StudyMode): Boolean = modeMask and mode.bit != 0
fun Sentence.allModesCompleted(): Boolean = StudyMode.entries.all {
    (it == StudyMode.SPELL && korean.isBlank()) || completedIn(it)
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
        (if (reviewOnly) card.needsReview else !card.completedIn(mode)) &&
            (mode != StudyMode.SPELL || card.korean.isNotBlank())
    }

/** Reset only study progress; keep card IDs, sentences, translations and chunks. */
fun resetStudyProgress(cards: List<Sentence>): List<Sentence> =
    cards.map { it.copy(attempts = 0, correct = 0, needsReview = false, completed = false, modeMask = 0) }

/** Word-level blanks preserve apostrophes and internal hyphens. */
enum class BlankDifficulty(val label: String, val fraction: Double) {
    EASY("쉬움", 0.15), NORMAL("보통", 0.25), HARD("어려움", 0.40)
}

data class BlankWord(val range: IntRange, val answer: String)

private val blankWordPattern = Regex("[\\p{L}]+(?:['’\\-][\\p{L}]+)*")
private val blankStopWords = ("a an the i you he she it we they me him her us them my your his its our their " +
    "this that these those which who whom whose what where when why how and or but if as than " +
    "of in on at to for from by with about into through over under is am are was were be been being " +
    "do does did have has had can could may might must shall should will would not no so then " +
    "there here very also only just all some any each both more most such").split(" ").toSet()

fun blankWords(english: String, difficulty: BlankDifficulty, random: kotlin.random.Random = kotlin.random.Random.Default): List<BlankWord> {
    val matches = blankWordPattern.findAll(english).toList()
    val candidates = matches.filter { it.value.length >= 2 }
    if (candidates.isEmpty()) return emptyList()
    val important = candidates.filter { it.value.lowercase(Locale.ROOT) !in blankStopWords }
    val pool = if (important.isNotEmpty()) important else candidates
    val count = kotlin.math.round(matches.size * difficulty.fraction).toInt().coerceIn(1, pool.size)
    return pool.shuffled(random).take(count).sortedBy { it.range.first }.map { BlankWord(it.range, it.value) }
}

fun blankPrompt(english: String, blanks: List<BlankWord>): String {
    val result = StringBuilder(english)
    blanks.withIndex().reversed().forEach { (index, word) ->
        result.replace(word.range.first, word.range.last + 1, "(${index + 1}) _____")
    }
    return result.toString()
}

fun matchesBlank(expected: String, answer: String): Boolean {
    fun canonical(value: String) = value.trim().lowercase(Locale.ROOT).replace('’', '\'')
    return canonical(expected) == canonical(answer)
}
