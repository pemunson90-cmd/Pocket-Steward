package com.pocketsteward.app.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.LruCache
import android.util.Size
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketsteward.app.ui.theme.LocalThumbnailsEnabled
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The picture at the start of a file row: a real preview for images, videos
 * and PDFs when previews are on, otherwise a colored badge carrying the
 * file's extension.
 *
 * Read-only by construction. Previews are decoded from the file with the
 * platform's own thumbnail APIs, held in memory only, and never written to
 * disk, so turning the setting off (or closing the app) leaves no trace.
 * The visual is decorative for screen readers: the row it sits in already
 * announces the file name, and repeating it would double every row.
 *
 * @param location an absolute path or a `content://` URI. Null draws the
 *   badge only, which is right for files whose bytes are not where the name
 *   says (Trash rows, history entries).
 */
@Composable
fun FileVisual(
    name: String,
    location: String?,
    mimeType: String? = null,
    isDirectory: Boolean = false,
    size: Dp = 40.dp,
    modifier: Modifier = Modifier,
) {
    val kind = FileKind.of(name, mimeType, isDirectory)
    val previewsOn = LocalThumbnailsEnabled.current
    val context = LocalContext.current
    val px = with(LocalDensity.current) { size.roundToPx() }.coerceAtLeast(1)
    val key = location?.let { "$it@$px" }

    val preview by produceState(
        initialValue = key?.let(ThumbnailCache::get),
        key1 = key,
        key2 = previewsOn,
    ) {
        if (!previewsOn || key == null || !kind.canPreview || value != null) return@produceState
        if (ThumbnailCache.knownToFail(key)) return@produceState
        value = ThumbnailCache.load(context.applicationContext, key, location, kind, px)
    }

    val shape = RoundedCornerShape(size / 4)
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(targetState = preview.takeIf { previewsOn }, label = "fileVisual") { bitmap ->
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(size),
                )
            } else {
                FileBadge(name = name, kind = kind, size = size)
            }
        }
    }
}

@Composable
private fun FileBadge(name: String, kind: FileKind, size: Dp) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val (container, content) = badgeColors(kind, dark)
    val text = FileKind.badgeText(name, kind)
    Box(
        modifier = Modifier.size(size).background(container),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = content,
            fontWeight = FontWeight.Bold,
            // Four letters have to fit a 40dp square without wrapping.
            fontSize = (size.value * if (text.length >= 4) 0.24f else 0.28f).sp,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/**
 * One hue per kind, chosen to stay distinguishable from each other and
 * legible in both light and dark themes (container tone ~90 / ~30, content
 * tone ~10 / ~90, the same tones Material uses for its containers). Fixed
 * rather than taken from the theme, so a PDF is the same red whether or not
 * wallpaper colors are on.
 */
private fun badgeColors(kind: FileKind, dark: Boolean): Pair<Color, Color> {
    val (light, darkPair) = when (kind) {
        FileKind.IMAGE -> (0xFFD6E3FF to 0xFF001B3E) to (0xFF284777 to 0xFFD6E3FF)
        FileKind.VIDEO -> (0xFFEBDDFF to 0xFF250059) to (0xFF533C83 to 0xFFEBDDFF)
        FileKind.PDF -> (0xFFFFDAD6 to 0xFF410002) to (0xFF8C1D18 to 0xFFFFDAD6)
        FileKind.AUDIO -> (0xFFFFD8EC to 0xFF3A0729) to (0xFF73325D to 0xFFFFD8EC)
        FileKind.DOCUMENT -> (0xFFD3E4FF to 0xFF001C38) to (0xFF1F4876 to 0xFFD3E4FF)
        FileKind.SPREADSHEET -> (0xFFC1E8BE to 0xFF002106) to (0xFF265027 to 0xFFC1E8BE)
        FileKind.SLIDES -> (0xFFFFDBC9 to 0xFF331200) to (0xFF773300 to 0xFFFFDBC9)
        FileKind.TEXT -> (0xFFE2E3DD to 0xFF1A1C19) to (0xFF454843 to 0xFFE2E3DD)
        FileKind.CODE -> (0xFFCCE8E6 to 0xFF00201F) to (0xFF1E4E4B to 0xFFCCE8E6)
        FileKind.ARCHIVE -> (0xFFF6E388 to 0xFF211B00) to (0xFF534600 to 0xFFF6E388)
        FileKind.APP -> (0xFFB8F0D0 to 0xFF002113) to (0xFF005235 to 0xFFB8F0D0)
        FileKind.EBOOK -> (0xFFF3DFC7 to 0xFF261909) to (0xFF574432 to 0xFFF3DFC7)
        FileKind.FOLDER -> (0xFFC1E8BE to 0xFF002106) to (0xFF265027 to 0xFFC1E8BE)
        FileKind.OTHER -> (0xFFE0E4D9 to 0xFF191D17) to (0xFF434840 to 0xFFE0E4D9)
    }
    val pick = if (dark) darkPair else light
    return Color(pick.first) to Color(pick.second)
}

/**
 * Process-wide, memory-only preview cache. Sized to 1/16 of the app's heap,
 * capped at 24 MiB, which holds several hundred 40dp previews: enough that
 * scrolling back up never re-decodes. Failures are remembered so a corrupt
 * or password-protected file is tried once, not on every scroll past it.
 */
internal object ThumbnailCache {
    private val maxBytes = (Runtime.getRuntime().maxMemory() / 16).coerceAtMost(24L * 1024 * 1024).toInt()

    private val cache = object : LruCache<String, Bitmap>(maxBytes) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }
    private val failures = LruCache<String, Boolean>(2_000)

    // Decoding a large HEIC or the first frame of a 4K video is expensive;
    // three at a time keeps a fast fling from queueing a hundred decodes.
    private val gate = Semaphore(3)

    fun get(key: String): Bitmap? = cache.get(key)

    fun knownToFail(key: String): Boolean = failures.get(key) == true

    fun clear() {
        cache.evictAll()
        failures.evictAll()
    }

    suspend fun load(context: Context, key: String, location: String, kind: FileKind, px: Int): Bitmap? =
        gate.withPermit {
            cache.get(key)?.let { return@withPermit it }
            val bitmap = withContext(Dispatchers.IO) {
                try {
                    decode(context, location, kind, px)
                } catch (_: Exception) {
                    null
                } catch (_: OutOfMemoryError) {
                    null
                }
            }
            if (bitmap != null) cache.put(key, bitmap) else failures.put(key, true)
            bitmap
        }

    private fun decode(context: Context, location: String, kind: FileKind, px: Int): Bitmap? {
        val target = Size(px, px)
        if (location.startsWith("content://")) {
            val uri = Uri.parse(location)
            return if (kind == FileKind.PDF) {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { renderPdf(it, px) }
            } else {
                context.contentResolver.loadThumbnail(uri, target, null)
            }
        }
        val file = File(location)
        if (!file.isFile) return null
        return when (kind) {
            FileKind.IMAGE -> ThumbnailUtils.createImageThumbnail(file, target, null)
            FileKind.VIDEO -> ThumbnailUtils.createVideoThumbnail(file, target, null)
            FileKind.PDF -> ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { renderPdf(it, px) }
            else -> null
        }
    }

    /** Page one only, scaled to fit, on white, because PDF pages assume a paper background. */
    private fun renderPdf(fd: ParcelFileDescriptor, px: Int): Bitmap? =
        PdfRenderer(fd).use { renderer ->
            if (renderer.pageCount == 0) return null
            renderer.openPage(0).use { page ->
                val scale = px.toFloat() / maxOf(page.width, page.height)
                val w = (page.width * scale).toInt().coerceAtLeast(1)
                val h = (page.height * scale).toInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(AndroidColor.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap
            }
        }
}
