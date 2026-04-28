package com.example.team_arthsetu.bankstatement

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint

/**
 * Bitmap pipeline for clearer ML Kit OCR: grayscale → contrast → threshold → light blur.
 * Order tuned for bank statements (dark text on light paper).
 */
object OcrImagePreprocessor {

    private const val MAX_LONG_EDGE = 3200
    private const val SCALE = 1.75f

    /**
     * Multiple tuned variants so OCR can recover more rows from difficult statements.
     */
    fun preprocessVariants(source: Bitmap): List<Bitmap> {
        val out = ArrayList<Bitmap>(3)
        out += source.copy(source.config ?: Bitmap.Config.ARGB_8888, true)
        out += preprocessPipeline(
            source.copy(source.config ?: Bitmap.Config.ARGB_8888, true),
            contrast = 1.40f,
            threshold = 132,
            applyBlur = true
        )
        out += preprocessPipeline(
            source.copy(source.config ?: Bitmap.Config.ARGB_8888, true),
            contrast = 1.25f,
            threshold = 155,
            applyBlur = false
        )
        return out
    }

    fun preprocessForOcr(source: Bitmap): Bitmap {
        return preprocessPipeline(source, contrast = 1.45f, threshold = 140, applyBlur = true)
    }

    private fun preprocessPipeline(
        source: Bitmap,
        contrast: Float,
        threshold: Int,
        applyBlur: Boolean
    ): Bitmap {
        var bmp = source
        try {
            bmp = scaleUp(bmp, SCALE)
            bmp = toGrayscale(bmp)
            bmp = boostContrast(bmp, contrast)
            bmp = thresholdBinary(bmp, threshold)
            if (applyBlur) bmp = boxBlur3(bmp)
            return bmp
        } catch (_: OutOfMemoryError) {
            if (bmp != source && !bmp.isRecycled) bmp.recycle()
            return source
        }
    }

    private fun scaleUp(src: Bitmap, factor: Float): Bitmap {
        val nw = (src.width * factor).toInt().coerceIn(1, MAX_LONG_EDGE)
        val nh = (src.height * factor).toInt().coerceIn(1, MAX_LONG_EDGE)
        if (nw == src.width && nh == src.height) return src
        val out = Bitmap.createScaledBitmap(src, nw, nh, true)
        if (out != src && !src.isRecycled) src.recycle()
        return out
    }

    private fun toGrayscale(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint().apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        }
        canvas.drawBitmap(src, 0f, 0f, paint)
        if (out != src && !src.isRecycled) src.recycle()
        return out
    }

    private fun boostContrast(src: Bitmap, contrast: Float): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val cm = ColorMatrix().apply {
            set(
                floatArrayOf(
                    contrast, 0f, 0f, 0f, 0f,
                    0f, contrast, 0f, 0f, 0f,
                    0f, 0f, contrast, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        }
        val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(cm) }
        canvas.drawBitmap(src, 0f, 0f, paint)
        if (out != src && !src.isRecycled) src.recycle()
        return out
    }

    /** Simple fixed threshold → black & white. */
    private fun thresholdBinary(src: Bitmap, threshold: Int): Bitmap {
        val w = src.width
        val h = src.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (c shr 16) and 0xff
            val g = (c shr 8) and 0xff
            val b = c and 0xff
            val gray = (r * 0.299 + g * 0.587 + b * 0.114).toInt()
            pixels[i] = if (gray > threshold) Color.WHITE else Color.BLACK
        }
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        if (out != src && !src.isRecycled) src.recycle()
        return out
    }

    /** Light 3×3 box blur (noise reduction). */
    private fun boxBlur3(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val p = IntArray(w * h)
        val p2 = IntArray(w * h)
        src.getPixels(p, 0, w, 0, 0, w, h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var sum = 0L
                var n = 0
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        val xx = x + dx
                        val yy = y + dy
                        if (xx in 0 until w && yy in 0 until h) {
                            val c = p[yy * w + xx]
                            sum += (c and 0xff)
                            n++
                        }
                    }
                }
                val g = (sum / n).toInt().coerceIn(0, 255)
                p2[y * w + x] = Color.argb(255, g, g, g)
            }
        }
        out.setPixels(p2, 0, w, 0, 0, w, h)
        if (out != src && !src.isRecycled) src.recycle()
        return out
    }
}
