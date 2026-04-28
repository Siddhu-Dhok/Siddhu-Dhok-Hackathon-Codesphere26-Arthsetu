package com.example.team_arthsetu.bankstatement

import android.graphics.Bitmap
import android.util.Log
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.rendering.PDFRenderer
import java.io.File

object BankStatementPdfPageRenderer {

    private const val TAG = "BankStmtPdfRender"

    /** 150 DPI → PDFBox scale (72 DPI baseline). */
    private val renderScale: Float = 150f / 72f

    fun renderPageForOcr(file: File, pageIndex: Int): Bitmap? {
        return try {
            PDDocument.load(file).use { doc ->
                if (pageIndex < 0 || pageIndex >= doc.numberOfPages) return null
                val renderer = PDFRenderer(doc)
                // Use renderImage(int, float) so the scale is clearly Float (avoids DPI overload mismatch on some Kotlin/Java interop builds).
                renderer.renderImage(pageIndex, renderScale)
            }
        } catch (e: Exception) {
            Log.w(TAG, "render page $pageIndex failed", e)
            null
        }
    }
}
