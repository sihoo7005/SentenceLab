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

    /** Split without changing the words. PDF line wrapping is collapsed into spaces. */
    fun splitImportedSentences(raw: String, pdf: Boolean = false): List<String> {
        val normalized = raw.replace("\r\n", "\n").replace('\r', '\n')
        val paragraphs = if (pdf) {
            normalized.split(Regex("\\n\\s*\\n")).map { block ->
                block.lines().map(String::trim).filter(String::isNotBlank).joinToString(" ")
            }
        } else {
            normalized.lines().map(String::trim)
        }
        val results = ArrayList<String>()
        val breaker = BreakIterator.getSentenceInstance(Locale.US)
        for (paragraph in paragraphs) {
            if (paragraph.isBlank()) continue
            breaker.setText(paragraph)
            var start = breaker.first()
            while (true) {
                val end = breaker.next()
                if (end == BreakIterator.DONE) break
                val sentence = paragraph.substring(start, end).trim()
                if (sentence.any { it.isLetter() }) {
                    if (sentence.length > MAX_SENTENCE_CHARS) {
                        error("2,000자보다 긴 문장이 있어 가져올 수 없습니다. 원문을 나누어 주세요.")
                    }
                    results.add(sentence)
                    if (results.size > MAX_SENTENCES) {
                        error("문장이 2,500개를 넘습니다. 문서를 나누어 가져오세요.")
                    }
                }
                start = end
            }
        }
        return results
    }
}
