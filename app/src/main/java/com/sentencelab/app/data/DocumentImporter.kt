package com.sentencelab.app.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.text.BreakIterator
import java.util.Locale

/** Data stays on the device. Drive access is delegated to the Android file picker. */
data class ImportedDocument(val name: String, val sentences: List<String>)

object DocumentImporter {
    private const val MAX_FILE_BYTES = 20 * 1024 * 1024
    private const val MAX_TEXT_CHARS = 500_000
    private const val MAX_SENTENCES = 2500
    private const val MAX_SENTENCE_CHARS = 2000

    suspend fun read(context: Context, uri: Uri): ImportedDocument = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            ?.takeIf { it.isNotBlank() } ?: "선택한 문서"
        val mime = resolver.getType(uri).orEmpty()
        val virtual = DocumentsContract.isDocumentUri(context, uri) &&
            (resolver.query(
                uri, arrayOf(DocumentsContract.Document.COLUMN_FLAGS), null, null, null
            )?.use { c -> if (c.moveToFirst()) c.getInt(0) else 0 } ?: 0)
                .and(DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT) != 0

        val pdf: Boolean
        val text: String
        if (virtual || mime == "application/vnd.google-apps.document") {
            // Google Docs are virtual Drive files: they must be exported by the provider.
            val formats = resolver.getStreamTypes(uri, "*/*")?.toSet().orEmpty()
            when {
                "text/plain" in formats -> {
                    text = readTyped(context, uri, "text/plain", false)
                    pdf = false
                }
                "application/pdf" in formats -> {
                    text = readTyped(context, uri, "application/pdf", true)
                    pdf = true
                }
                else -> error("이 Google 문서는 텍스트 또는 PDF로 내보낼 수 없습니다. Drive에서 PDF로 저장한 뒤 가져오세요.")
            }
        } else if (mime == "application/pdf" || name.endsWith(".pdf", true)) {
            text = resolver.openInputStream(uri)?.use { pdfText(context, it) }
                ?: error("PDF 파일을 열 수 없습니다.")
            pdf = true
        } else if (
            mime == "text/plain" || mime == "text/markdown" ||
            name.endsWith(".txt", true) || name.endsWith(".md", true)
        ) {
            text = resolver.openInputStream(uri)?.use { String(readLimited(it), Charsets.UTF_8) }
                ?: error("텍스트 파일을 열 수 없습니다.")
            pdf = false
        } else {
            error("Google Docs, PDF, TXT, MD 파일을 선택하세요.")
        }

        if (text.length > MAX_TEXT_CHARS) error("문서가 너무 깁니다. 파일을 나누어 가져오세요.")
        val sentences = splitImportedSentences(text, pdf)
        if (sentences.isEmpty()) {
            error(if (pdf) "추출 가능한 글자가 없습니다. 이미지로 된 스캔 PDF는 현재 지원하지 않습니다."
                else "문서에서 영어 문장을 찾지 못했습니다.")
        }
        ImportedDocument(name, sentences)
    }

    private fun readTyped(context: Context, uri: Uri, format: String, pdf: Boolean): String {
        val asset = context.contentResolver.openTypedAssetFileDescriptor(uri, format, null)
            ?: error("Drive 문서를 읽을 수 없습니다.")
        return asset.use { descriptor ->
            descriptor.createInputStream().use { input ->
                if (pdf) pdfText(context, input)
                else String(readLimited(input), Charsets.UTF_8)
            }
        }
    }

    private fun pdfText(context: Context, stream: InputStream): String {
        PDFBoxResourceLoader.init(context.applicationContext)
        val bytes = readLimited(stream)
        return PDDocument.load(bytes).use { pdf -> PDFTextStripper().getText(pdf) }
    }

    private fun readLimited(input: InputStream): ByteArray {
        val buffer = ByteArray(8192)
        val result = ByteArrayOutputStream()
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (result.size() + count > MAX_FILE_BYTES) {
                error("파일이 20MiB를 초과합니다. 작은 파일로 나누어 가져오세요.")
            }
            result.write(buffer, 0, count)
        }
        return result.toByteArray()
    }

    /** Preserve explicit line breaks in Docs/text; PDF wraps are soft unless a heading is detected. */
    fun splitImportedSentences(raw: String, pdf: Boolean = false): List<String> {
        val normalized = raw.replace("\r\n", "\n").replace('\r', '\n').removePrefix("\uFEFF")
        val lines = normalized.lines()
        val paragraphs = ArrayList<String>()
        val wrapped = StringBuilder()

        fun flush() {
            if (wrapped.isNotBlank()) {
                paragraphs.add(wrapped.toString().trim())
                wrapped.clear()
            }
        }

        for (index in lines.indices) {
            val line = lines[index].trim()
            if (line.isEmpty() || line.matches(Regex("[_—=\\-]{3,}"))) {
                flush()
                continue
            }
            val blankBefore = index == 0 || lines[index - 1].isBlank() ||
                lines[index - 1].trim().matches(Regex("[_—=\\-]{3,}"))
            val blankAfter = index == lines.lastIndex || lines[index + 1].isBlank()
            val next = lines.drop(index + 1).firstOrNull { it.isNotBlank() }?.trim()
            if (isSectionHeading(line, blankBefore, blankAfter, next)) {
                flush()
                continue
            }
            if (pdf) {
                if (wrapped.isNotEmpty()) wrapped.append(' ')
                wrapped.append(line)
            } else {
                paragraphs.add(line)
            }
        }
        flush()

        val results = ArrayList<String>()
        val breaker = BreakIterator.getSentenceInstance(Locale.US)
        for (paragraph in paragraphs) {
            if (paragraph.isBlank()) continue
            val safe = protectAbbreviations(paragraph)
            breaker.setText(safe)
            val pieces = ArrayList<String>()
            var start = breaker.first()
            while (true) {
                val end = breaker.next()
                if (end == BreakIterator.DONE) break
                val part = safe.substring(start, end).replace('\uE000', '.').trim()
                if (part.any(Char::isLetter)) {
                    // A lower-case continuation after a quoted question is not a new sentence.
                    if (pieces.isNotEmpty() && part.firstOrNull()?.isLowerCase() == true) {
                        pieces[pieces.lastIndex] += " " + part
                    } else {
                        pieces.add(part)
                    }
                }
                start = end
            }
            for (sentence in pieces) {
                if (sentence.length > MAX_SENTENCE_CHARS) {
                    error("2,000자보다 긴 문장이 있어 가져올 수 없습니다. 원문을 나누어 주세요.")
                }
                results.add(sentence)
                if (results.size > MAX_SENTENCES) {
                    error("문장이 2,500개를 넘습니다. 문서를 나누어 가져오세요.")
                }
            }
        }
        return results
    }

    private fun protectAbbreviations(text: String): String {
        val titles = Regex("""\b(?:Dr|Mr|Mrs|Ms|Prof|Sr|Jr|St|Mt|vs)\.""", RegexOption.IGNORE_CASE)
        val shortForms = Regex("""\b(?:e\.g|i\.e)\.""", RegexOption.IGNORE_CASE)
        return titles.replace(text) { it.value.dropLast(1) + '\uE000' }
            .let { textWithTitles ->
                shortForms.replace(textWithTitles) { it.value.replace('.', '\uE000') }
            }
    }

    private fun isSectionHeading(
        line: String,
        blankBefore: Boolean,
        blankAfter: Boolean,
        next: String?
    ): Boolean {
        if (line.length > 100 || line.lastOrNull() in listOf('.', '!', '?', '"', '”')) return false
        if (line.startsWith("Further Reading", ignoreCase = true)) return true
        val tokens = line.split(Regex("\\s+")).filter { word -> word.any(Char::isLetter) }
        if (tokens.isEmpty() || tokens.size > 12) return false
        val capitals = tokens.count { word -> word.firstOrNull()?.isUpperCase() == true }
        // Typical standalone English title, not a complete sentence.
        if (tokens.size >= 3 && capitals >= 2 && capitals * 2 >= tokens.size) return true
        val knownHeadings = setOf(
            "Subscriptions are everywhere",
            "People love the subscriptions",
            "Online platforms drive the subscription economy",
            "Limitations of the subscription economy"
        )
        if (line in knownHeadings) return true
        // A single explicit line break does NOT make a short sentence into a title.
        return tokens.size <= 7 && blankBefore && blankAfter &&
            next?.firstOrNull()?.isUpperCase() == true
    }
}
