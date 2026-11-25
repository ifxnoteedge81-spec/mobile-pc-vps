package com.example.runtime

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Base64
import com.example.ai.AiImageAttachment
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Zero-dependency on-device vision helper for encoding uploaded images and
 * rendering scanned PDF pages (via built-in PdfRenderer) into compact JPEG base64 attachments.
 */
object MediaVisionHelper {

    fun isImageAttachment(fileName: String, mimeType: String): Boolean {
        val lowerName = fileName.lowercase()
        val lowerMime = mimeType.lowercase()
        return lowerMime.startsWith("image/") ||
            lowerName.endsWith(".jpg") ||
            lowerName.endsWith(".jpeg") ||
            lowerName.endsWith(".png") ||
            lowerName.endsWith(".webp")
    }

    fun encodeImageBytesToBase64(
        bytes: ByteArray,
        displayName: String,
        maxDimensionPx: Int = 1280,
        jpegQuality: Int = 82
    ): AiImageAttachment? {
        if (bytes.isEmpty()) return null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val srcW = bounds.outWidth
            val srcH = bounds.outHeight
            if (srcW <= 0 || srcH <= 0) return@runCatching null

            var sampleSize = 1
            while (max(srcW / sampleSize, srcH / sampleSize) > maxDimensionPx * 2) {
                sampleSize *= 2
            }
            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts) ?: return@runCatching null
            val scaled = scaleBitmapIfNeeded(decoded, maxDimensionPx)
            if (scaled !== decoded) decoded.recycle()

            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, jpegQuality, out)
            scaled.recycle()

            val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            AiImageAttachment(
                displayName = displayName,
                mimeType = "image/jpeg",
                base64Data = b64
            )
        }.getOrNull()
    }

    fun renderPdfPagesToImages(
        pdfFile: File,
        displayName: String,
        maxPages: Int = 3,
        maxDimensionPx: Int = 1280,
        jpegQuality: Int = 82
    ): List<AiImageAttachment> {
        if (!pdfFile.exists() || pdfFile.length() <= 0L) return emptyList()
        return runCatching {
            val results = mutableListOf<AiImageAttachment>()
            ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    val count = minOf(renderer.pageCount, maxPages)
                    for (pageIdx in 0 until count) {
                        renderer.openPage(pageIdx).use { page ->
                            val rawW = max(page.width, 1)
                            val rawH = max(page.height, 1)
                            val scale = minOf(
                                maxDimensionPx.toFloat() / max(rawW, rawH).toFloat(),
                                2.0f
                            ).coerceAtLeast(1.0f)
                            val targetW = (rawW * scale).roundToInt().coerceIn(200, maxDimensionPx)
                            val targetH = (rawH * scale).roundToInt().coerceIn(200, maxDimensionPx)
                            val bitmap = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                            try {
                                val canvas = Canvas(bitmap)
                                canvas.drawColor(Color.WHITE)
                                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                                val out = ByteArrayOutputStream()
                                bitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality, out)
                                val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                                results.add(
                                    AiImageAttachment(
                                        displayName = "$displayName (Page ${pageIdx + 1})",
                                        mimeType = "image/jpeg",
                                        base64Data = b64
                                    )
                                )
                            } finally {
                                bitmap.recycle()
                            }
                        }
                    }
                }
            }
            results
        }.getOrElse { emptyList() }
    }

    private fun scaleBitmapIfNeeded(bitmap: Bitmap, maxDim: Int): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val longest = max(w, h)
        if (longest <= maxDim || w <= 0 || h <= 0) return bitmap
        val ratio = maxDim.toFloat() / longest.toFloat()
        val newW = (w * ratio).roundToInt().coerceAtLeast(1)
        val newH = (h * ratio).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, newW, newH, true)
    }
}
