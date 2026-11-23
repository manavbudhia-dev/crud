package com.papervault.scanner.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.exifinterface.media.ExifInterface
import java.io.File
import kotlin.math.max
import kotlin.math.min

object PdfUtils {

    // A4 in PostScript points (1/72 inch)
    private const val A4_W = 595
    private const val A4_H = 842
    private const val MARGIN = 18f

    fun pageCount(file: File): Int =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { it.pageCount }
        }

    /**
     * Render one page to a bitmap whose width is [targetWidth] px (height keeps aspect).
     * PdfRenderer allows only one open page at a time per renderer, so each call opens its own.
     */
    fun renderPage(file: File, index: Int, targetWidth: Int): Bitmap? = runCatching {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                renderer.openPage(index).use { page ->
                    val scale = targetWidth.toFloat() / page.width
                    val bmp = Bitmap.createBitmap(
                        targetWidth,
                        max(1, (page.height * scale).toInt()),
                        Bitmap.Config.ARGB_8888,
                    )
                    bmp.eraseColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bmp
                }
            }
        }
    }.getOrNull()

    /**
     * Build an A4 PDF from images, one image per page, scaled to fit and centred.
     * Landscape images get a landscape page. EXIF orientation is honoured.
     */
    fun imagesToPdf(context: Context, uris: List<Uri>, out: File) {
        val doc = PdfDocument()
        try {
            uris.forEachIndexed { i, uri ->
                val bmp = decodeScaled(context, uri, maxSide = 2400) ?: return@forEachIndexed
                val landscape = bmp.width > bmp.height
                val pw = if (landscape) A4_H else A4_W
                val ph = if (landscape) A4_W else A4_H
                val page = doc.startPage(PdfDocument.PageInfo.Builder(pw, ph, i + 1).create())
                val canvas: Canvas = page.canvas
                canvas.drawColor(Color.WHITE)
                val availW = pw - 2 * MARGIN
                val availH = ph - 2 * MARGIN
                val scale = min(availW / bmp.width, availH / bmp.height)
                val w = bmp.width * scale
                val h = bmp.height * scale
                val left = (pw - w) / 2
                val top = (ph - h) / 2
                canvas.drawBitmap(bmp, null, RectF(left, top, left + w, top + h), Paint(Paint.FILTER_BITMAP_FLAG))
                doc.finishPage(page)
                bmp.recycle()
            }
            out.outputStream().use { doc.writeTo(it) }
        } finally {
            doc.close()
        }
    }

    private fun decodeScaled(context: Context, uri: Uri, maxSide: Int): Bitmap? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null

        val rotation = resolver.openInputStream(uri)?.use {
            when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f
        if (rotation == 0f) return bmp
        val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rotation) }, true)
        if (rotated != bmp) bmp.recycle()
        return rotated
    }
}
