package com.pocketsteward.app.similarity

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

object ImageDHash {
    fun fromPath(path: String): Long? {
        val file = File(path)
        if (!file.isFile) return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / sample > 256 || bounds.outHeight / sample > 256) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = BitmapFactory.decodeFile(path, options) ?: return null
        return try {
            val scaled = Bitmap.createScaledBitmap(bitmap, 9, 8, true)
            try {
                var hash = 0L
                var bit = 0
                for (y in 0 until 8) {
                    for (x in 0 until 8) {
                        val left = luminance(scaled.getPixel(x, y))
                        val right = luminance(scaled.getPixel(x + 1, y))
                        if (left > right) hash = hash or (1L shl bit)
                        bit++
                    }
                }
                hash
            } finally {
                if (scaled !== bitmap) scaled.recycle()
            }
        } finally {
            bitmap.recycle()
        }
    }

    fun likelyScreenshot(displayName: String): Boolean {
        val lower = displayName.lowercase()
        return "screenshot" in lower || lower.startsWith("screen_") || lower.startsWith("screen-")
    }

    private fun luminance(pixel: Int): Int {
        val r = (pixel shr 16) and 0xff
        val g = (pixel shr 8) and 0xff
        val b = pixel and 0xff
        return (299 * r + 587 * g + 114 * b) / 1000
    }
}
