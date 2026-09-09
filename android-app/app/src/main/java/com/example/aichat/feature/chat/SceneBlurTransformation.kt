package com.example.aichat.feature.chat

import android.graphics.Bitmap
import coil.size.Size
import coil.transform.Transformation
import kotlin.math.max
import kotlin.math.roundToInt

/** Cached software blur also works on Android 9–11, where Compose blur is a no-op. */
class SceneBlurTransformation : Transformation {
    override val cacheKey = "anime-scene-blur-v1"
    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val scale = 320f / max(input.width, input.height)
        val w = max(1, (input.width * scale).roundToInt())
        val h = max(1, (input.height * scale).roundToInt())
        val bitmap = Bitmap.createScaledBitmap(input, w, h, true)
        var pixels = IntArray(w * h)
        var buffer = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val radius = 7
        fun pass(horizontal: Boolean) {
            val lines = if (horizontal) h else w
            val length = if (horizontal) w else h
            for (line in 0 until lines) {
                fun index(position: Int): Int = if (horizontal) line * w + position.coerceIn(0, w - 1) else position.coerceIn(0, h - 1) * w + line
                var red = 0; var green = 0; var blue = 0
                fun add(position: Int, direction: Int) {
                    val c = pixels[index(position)]
                    red += ((c ushr 16) and 255) * direction
                    green += ((c ushr 8) and 255) * direction
                    blue += (c and 255) * direction
                }
                for (p in -radius..radius) add(p, 1)
                val count = 2 * radius + 1
                for (p in 0 until length) {
                    buffer[index(p)] = (255 shl 24) or ((red / count) shl 16) or ((green / count) shl 8) or (blue / count)
                    add(p - radius, -1); add(p + radius + 1, 1)
                }
            }
            val swap = pixels; pixels = buffer; buffer = swap
        }
        repeat(2) { pass(true); pass(false) }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }
}
