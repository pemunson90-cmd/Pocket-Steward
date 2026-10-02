package com.pocketsteward.app.content

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.Charset
import java.util.zip.ZipInputStream

/**
 * Pure, bounded content extraction. It knows nothing about Android storage or
 * mutation. Callers own permission and open the stream through StorageGateway.
 */
object ContentExtractor {
    const val MAX_TEXT_BYTES: Int = 1_048_576
    const val MAX_OOXML_TEXT_CHARS: Int = 1_000_000

    private val plainTextExtensions = setOf(
        "txt", "md", "markdown", "log", "csv", "json", "xml", "yaml", "yml",
        "kt", "kts", "java", "js", "mjs", "cjs", "ts", "tsx", "jsx", "py",
        "rb", "go", "rs", "c", "cc", "cpp", "h", "hpp", "cs", "swift",
        "sh", "bash", "zsh", "fish", "sql", "html", "htm", "css", "scss",
        "ini", "cfg", "conf", "toml", "properties", "gradle",
    )

    private val ooxmlExtensions = setOf("docx", "xlsx", "pptx")

    val supportedExtensions: Set<String> = plainTextExtensions + ooxmlExtensions + "pdf"

    fun supports(extension: String): Boolean {
        val ext = extension.lowercase()
        return ext in plainTextExtensions || ext in ooxmlExtensions || ext == "pdf"
    }

    fun extract(extension: String, input: InputStream, budget: ContentInspectionBudget = ContentInspectionBudget.FULL): ContentExtraction {
        val ext = extension.lowercase()
        return try {
            when {
                ext in plainTextExtensions -> extractPlainText(input, budget)
                ext in ooxmlExtensions -> extractOoxml(ext, input, budget)
                ext == "pdf" -> ContentExtraction.Unsupported(
                    "PDF requires the Android PDF extractor.",
                )
                else -> ContentExtraction.Unsupported("This file type is not text-readable.")
            }
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (t: Throwable) {
            ContentExtraction.Failed(t.message ?: t.javaClass.simpleName)
        }
    }

    private fun extractPlainText(input: InputStream, budget: ContentInspectionBudget): ContentExtraction.Text {
        val bytes = readBounded(input, budget.maxTextBytes + 1)
        val truncated = bytes.size > budget.maxTextBytes
        val payload = if (truncated) bytes.copyOf(budget.maxTextBytes) else bytes

        // UTF-8 is the project default. Replacement characters are preferable
        // to throwing away an otherwise searchable file because of one bad byte.
        val text = payload.toString(Charsets.UTF_8)
        return ContentExtraction.Text(text.take(budget.maxTextChars), truncated || text.length > budget.maxTextChars, ContentKind.PLAIN_TEXT)
    }

    private fun extractOoxml(extension: String, input: InputStream, budget: ContentInspectionBudget): ContentExtraction {
        val accepted = when (extension) {
            "docx" -> { name: String -> name == "word/document.xml" || name.startsWith("word/header") || name.startsWith("word/footer") }
            "xlsx" -> { name: String -> name == "xl/sharedStrings.xml" || name.startsWith("xl/worksheets/") }
            "pptx" -> { name: String -> name.startsWith("ppt/slides/slide") && name.endsWith(".xml") }
            else -> return ContentExtraction.Unsupported("Unsupported Office document type.")
        }

        val text = StringBuilder()
        var truncated = false
        var decodedBytes = 0
        var entries = 0
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (++entries > budget.maxArchiveEntries) { truncated = true; break }
                val remainingBytes = budget.maxDecodedArchiveBytes - decodedBytes
                if (remainingBytes <= 0) { truncated = true; break }
                if (!entry.isDirectory && accepted(entry.name)) {
                    val remaining = budget.maxTextChars - text.length
                    if (remaining <= 0) {
                        truncated = true
                        break
                    }
                    val xmlBytes = readBounded(zip, minOf(budget.maxTextBytes, remaining * 4, remainingBytes) + 1)
                    decodedBytes += xmlBytes.size
                    val xml = xmlBytes.toString(Charsets.UTF_8)
                    val extracted = xmlToText(xml)
                    if (extracted.length > remaining) {
                        text.append(extracted, 0, remaining)
                        truncated = true
                        break
                    } else {
                        if (text.isNotEmpty() && extracted.isNotBlank()) text.append('\n')
                        text.append(extracted)
                    }
                    if (xmlBytes.size > minOf(budget.maxTextBytes, remaining * 4, remainingBytes)) { truncated = true; break }
                }
                // Explicitly drain within the decoded-byte budget. closeEntry alone can decompress a bomb.
                val drained = readBounded(zip, budget.maxDecodedArchiveBytes - decodedBytes + 1)
                decodedBytes += drained.size
                if (decodedBytes > budget.maxDecodedArchiveBytes) { truncated = true; break }
                zip.closeEntry()
            }
        }
        return ContentExtraction.Text(text.toString(), truncated, ContentKind.OOXML)
    }

    /**
     * OOXML text nodes are ordinary XML text. We do not need formatting for
     * search, only readable text. This keeps M10A dependency-free and bounded.
     */
    private fun xmlToText(xml: String): String =
        xml
            .replace(Regex("""<[^>]+>"""), " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&amp;", "&")
            .replace(Regex("""\s+"""), " ")
            .trim()

    private fun readBounded(input: InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream(minOf(limit, 64 * 1024))
        val buffer = ByteArray(16 * 1024)
        var remaining = limit
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size, remaining))
            if (read < 0) break
            out.write(buffer, 0, read)
            remaining -= read
        }
        return out.toByteArray()
    }
}
