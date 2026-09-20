package com.pocketsteward.app.content

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.annotation.RequiresApi
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.tasks.await

interface PdfContentExtractor {
    suspend fun extract(input: InputStream): ContentExtraction
}

/**
 * Android-native PDF extraction. Embedded PDF text is preferred. A page with
 * no text layer is rendered and passed through bundled ML Kit OCR.
 *
 * M10B's shipped implementation requires Android 15+ for page text access.
 * Older OS versions fail explicitly rather than pretending OCR alone is full
 * PDF support. Pocket Steward currently targets Android 16 hardware.
 */
class AndroidPdfContentExtractor(
    private val context: Context,
) : PdfContentExtractor {
    override suspend fun extract(input: InputStream): ContentExtraction {
        if (Build.VERSION.SDK_INT < 35) {
            return ContentExtraction.Unsupported(
                "PDF content inspection currently requires Android 15 or newer.",
            )
        }
        return extractApi35(input)
    }

    @RequiresApi(35)
    private suspend fun extractApi35(input: InputStream): ContentExtraction {
        val temp = File.createTempFile("pocket-steward-", ".pdf", context.cacheDir)
        return try {
            temp.outputStream().use { output -> input.copyTo(output, bufferSize = 64 * 1024) }
            val pfd = ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY)
            pfd.use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    val maxPages = minOf(renderer.pageCount, MAX_PAGES)
                    val segments = mutableListOf<PageTextSegment>()
                    var totalChars = 0
                    var usedOcr = false
                    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                    try {
                        for (pageIndex in 0 until maxPages) {
                            if (totalChars >= MAX_PDF_CHARS) break
                            renderer.openPage(pageIndex).use { page ->
                                val embedded = page.textContents
                                    .joinToString(" ") { it.text }
                                    .replace(Regex("""\s+"""), " ")
                                    .trim()

                                val pageText: String
                                val ocr: Boolean
                                if (embedded.isNotBlank()) {
                                    pageText = embedded
                                    ocr = false
                                } else {
                                    pageText = recognizePage(page, recognizer)
                                    ocr = pageText.isNotBlank()
                                    usedOcr = usedOcr || ocr
                                }

                                if (pageText.isNotBlank()) {
                                    val remaining = MAX_PDF_CHARS - totalChars
                                    val bounded = pageText.take(remaining)
                                    segments += PageTextSegment(pageIndex + 1, bounded, ocr)
                                    totalChars += bounded.length
                                }
                            }
                        }
                    } finally {
                        recognizer.close()
                    }

                    val content = segments.joinToString("\n\n") { segment ->
                        "[Page ${segment.pageNumber}]\n${segment.text}"
                    }
                    val truncated = renderer.pageCount > maxPages || totalChars >= MAX_PDF_CHARS
                    ContentExtraction.Text(
                        content = content,
                        truncated = truncated,
                        kind = if (usedOcr) ContentKind.PDF_OCR else ContentKind.PDF_TEXT,
                        pages = segments,
                    )
                }
            }
        } catch (security: SecurityException) {
            ContentExtraction.Failed("PDF is encrypted, password-protected, or otherwise unavailable.")
        } catch (t: Throwable) {
            ContentExtraction.Failed(t.message ?: "Could not read PDF.")
        } finally {
            temp.delete()
        }
    }

    @RequiresApi(35)
    private suspend fun recognizePage(
        page: PdfRenderer.Page,
        recognizer: com.google.mlkit.vision.text.TextRecognizer,
    ): String {
        val scale = minOf(
            MAX_OCR_DIMENSION.toFloat() / page.width.coerceAtLeast(1),
            MAX_OCR_DIMENSION.toFloat() / page.height.coerceAtLeast(1),
            2.0f,
        )
        val width = (page.width * scale).toInt().coerceAtLeast(1)
        val height = (page.height * scale).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return try {
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            recognizer.process(InputImage.fromBitmap(bitmap, 0)).await().text
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        const val MAX_PAGES = 250
        const val MAX_PDF_CHARS = 1_000_000
        const val MAX_OCR_DIMENSION = 1800
    }
}
