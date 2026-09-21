package com.pocketsteward.app.content

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.graphics.pdf.PdfRendererPreV
import android.graphics.pdf.RenderParams
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.ext.SdkExtensions
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresExtension
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.tasks.await

interface PdfContentExtractor {
    suspend fun extract(input: InputStream): ContentExtraction
}

/**
 * Android-native PDF extraction. Embedded text wins; a page with no text
 * layer is rendered and passed through bundled ML Kit OCR.
 *
 * Android 15+ uses PdfRenderer directly. Android 11–14 uses PdfRendererPreV
 * when S Extensions 13+ is available. Older PDF module revisions fail
 * explicitly rather than silently returning incomplete "support".
 */
class AndroidPdfContentExtractor(
    private val context: Context,
) : PdfContentExtractor {
    override suspend fun extract(input: InputStream): ContentExtraction {
        val temp = File.createTempFile("pocket-steward-", ".pdf", context.cacheDir)
        return try {
            temp.outputStream().use { output -> input.copyTo(output, bufferSize = 64 * 1024) }
            when {
                Build.VERSION.SDK_INT >= 35 -> extractApi35(temp)
                SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 13 -> extractPreV(temp)
                else -> ContentExtraction.Unsupported(
                    "PDF text inspection needs Android 15+ or the Android PDF system extension version 13+.",
                )
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
    private suspend fun extractApi35(file: File): ContentExtraction {
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        pfd.use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                return try {
                    extractPages(
                        pageCount = renderer.pageCount,
                        open = { index ->
                            renderer.openPage(index).let { page ->
                                ModernPage(
                                    text = {
                                        page.textContents.joinToString(" ") { it.text }
                                    },
                                    width = page.width,
                                    height = page.height,
                                    render = { bitmap ->
                                        page.render(
                                            bitmap,
                                            null,
                                            null,
                                            PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY,
                                        )
                                    },
                                    close = page::close,
                                )
                            }
                        },
                        recognizer = recognizer,
                    )
                } finally {
                    recognizer.close()
                }
            }
        }
    }

    /**
     * Guarded by the S-extension check before invocation. PdfRendererPreV
     * exposes the Android V PDF APIs on Android R through U.
     */
    @RequiresApi(30)
    @RequiresExtension(extension = Build.VERSION_CODES.S, version = 13)
    private suspend fun extractPreV(file: File): ContentExtraction {
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        pfd.use { descriptor ->
            PdfRendererPreV(descriptor).use { renderer ->
                val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                return try {
                    val params = RenderParams.Builder(RenderParams.RENDER_MODE_FOR_DISPLAY).build()
                    extractPages(
                        pageCount = renderer.pageCount,
                        open = { index ->
                            renderer.openPage(index).let { page ->
                                ModernPage(
                                    text = {
                                        page.textContents.joinToString(" ") { it.text }
                                    },
                                    width = page.width,
                                    height = page.height,
                                    render = { bitmap ->
                                        page.render(bitmap, null, null, params)
                                    },
                                    close = page::close,
                                )
                            }
                        },
                        recognizer = recognizer,
                    )
                } finally {
                    recognizer.close()
                }
            }
        }
    }

    private suspend fun extractPages(
        pageCount: Int,
        open: (Int) -> ModernPage,
        recognizer: TextRecognizer,
    ): ContentExtraction {
        val maxPages = minOf(pageCount, MAX_PAGES)
        val segments = mutableListOf<PageTextSegment>()
        var totalChars = 0
        var usedOcr = false

        for (pageIndex in 0 until maxPages) {
            if (totalChars >= MAX_PDF_CHARS) break
            val page = open(pageIndex)
            try {
                val embedded = page.text()
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
            } finally {
                page.close()
            }
        }

        val content = segments.joinToString("\n\n") { segment ->
            "[Page ${segment.pageNumber}]\n${segment.text}"
        }
        return ContentExtraction.Text(
            content = content,
            truncated = pageCount > maxPages || totalChars >= MAX_PDF_CHARS,
            kind = if (usedOcr) ContentKind.PDF_OCR else ContentKind.PDF_TEXT,
            pages = segments,
        )
    }

    private suspend fun recognizePage(page: ModernPage, recognizer: TextRecognizer): String {
        val scale = minOf(
            MAX_OCR_DIMENSION.toFloat() / page.width.coerceAtLeast(1),
            MAX_OCR_DIMENSION.toFloat() / page.height.coerceAtLeast(1),
            2.0f,
        )
        val width = (page.width * scale).toInt().coerceAtLeast(1)
        val height = (page.height * scale).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return try {
            page.render(bitmap)
            recognizer.process(InputImage.fromBitmap(bitmap, 0)).await().text
        } finally {
            bitmap.recycle()
        }
    }

    private class ModernPage(
        val text: () -> String,
        val width: Int,
        val height: Int,
        val render: (Bitmap) -> Unit,
        val close: () -> Unit,
    )

    private companion object {
        const val MAX_PAGES = 250
        const val MAX_PDF_CHARS = 1_000_000
        const val MAX_OCR_DIMENSION = 1800
    }
}
