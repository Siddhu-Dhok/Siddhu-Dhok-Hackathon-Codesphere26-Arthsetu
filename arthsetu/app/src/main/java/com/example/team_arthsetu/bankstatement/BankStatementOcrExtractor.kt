package com.example.team_arthsetu.bankstatement

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.TimeUnit

object BankStatementOcrExtractor {

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    /** Blocking OCR for use from [Dispatchers.IO] only. */
    fun extractTextFromBitmapBlocking(bitmap: Bitmap): String {
        val image = InputImage.fromBitmap(bitmap, 0)
        return try {
            Tasks.await(recognizer.process(image), 120, TimeUnit.SECONDS).text.orEmpty()
        } catch (_: Exception) {
            ""
        }
    }
}
