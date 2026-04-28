package com.example.team_arthsetu.utils

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

/**
 * Loads profile photos with correct display orientation (EXIF) and saves normalized JPEGs
 * so other screens can use [BitmapFactory.decodeFile] without extra handling.
 */
object ProfilePhotoUtils {

    private const val MAX_DIMENSION = 2048
    private const val JPEG_QUALITY = 92

    fun loadOrientedBitmap(path: String): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val sample = calculateInSampleSize(bounds.outWidth, bounds.outHeight, MAX_DIMENSION)
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val raw = BitmapFactory.decodeFile(path, opts) ?: return null
        val degrees = readExifRotationDegrees(path)
        if (degrees == 0) return raw
        val rotated = rotateBitmap(raw, degrees.toFloat())
        if (rotated != raw) raw.recycle()
        return rotated
    }

    private fun calculateInSampleSize(w: Int, h: Int, maxDim: Int): Int {
        var inSampleSize = 1
        val longSide = max(w, h)
        while (longSide / inSampleSize > maxDim) inSampleSize *= 2
        return max(1, inSampleSize)
    }

    private fun readExifRotationDegrees(path: String): Int =
        try {
            val exif = ExifInterface(path)
            when (
                exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            ) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } catch (_: Exception) {
            0
        }

    fun rotateBitmap(bitmap: Bitmap, degrees: Float): Bitmap {
        val d = ((degrees % 360f) + 360f) % 360f
        if (d == 0f) return bitmap
        val m = Matrix().apply { postRotate(d) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
    }

    fun saveAsNormalizedJpeg(bitmap: Bitmap, file: File) {
        FileOutputStream(file).use { fos ->
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, fos)) {
                throw java.io.IOException("JPEG compress failed")
            }
        }
        try {
            ExifInterface(file.absolutePath).apply {
                setAttribute(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL.toString()
                )
                saveAttributes()
            }
        } catch (_: Exception) { }
    }
}
