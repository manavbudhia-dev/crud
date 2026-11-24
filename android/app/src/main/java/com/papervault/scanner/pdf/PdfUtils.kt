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
import com.papervault.scanner.data.PageSize
import com.papervault.scanner.pdf.compress.CompressionEngine
import com.papervault.scanner.pdf.compress.CompressionLevel
import com.papervault.scanner.pdf.compress.PageEncoding
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
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

    /** Displayed page size in points (rotation applied). */
    fun pageSizePoints(file: File, index: Int): Pair<Float, Float> =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { r -> r.openPage(index).use { it.width.toFloat() to it.height.toFloat() } }
        }

    /** Render every page at [targetWidth] px, calling [block] for each; bitmaps are recycled after. */
    fun forEachPage(file: File, targetWidth: Int, block: (index: Int, bmp: Bitmap) -> Unit) {
        val count = pageCount(file)
        for (i in 0 until count) {
            val bmp = renderPage(file, i, targetWidth) ?: continue
            try { block(i, bmp) } finally { bmp.recycle() }
        }
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
    /**
     * Build a PDF from images, one per page, through the compression engine: each page is
     * downscaled to the level's dpi and stored as colour JPEG, grey JPEG or 1-bit CCITT,
     * whichever suits its content. [hintFor] lets callers force an encoding (e.g. the B&W filter).
     */
    fun imagesToPdf(
        context: Context,
        uris: List<Uri>,
        out: File,
        pageSize: PageSize = PageSize.A4,
        sizeFor: ((Int) -> PageSize)? = null,
        level: CompressionLevel = CompressionLevel.BALANCED,
        hintFor: ((Int) -> PageEncoding?)? = null,
    ) {
        // High quality needs ~2500 px across A4; other levels need less, so decode smaller and save memory.
        val maxSide = if (level == CompressionLevel.BEST || level == CompressionLevel.OFF) 3600 else 2600
        PDDocument().use { doc ->
            uris.forEachIndexed { i, uri ->
                val bmp = decodeScaled(context, uri, maxSide = maxSide) ?: return@forEachIndexed
                try {
                    val landscape = bmp.width > bmp.height
                    val ps = sizeFor?.invoke(i) ?: pageSize
                    val (baseW, baseH) = when (ps) {
                        PageSize.A4 -> A4_W.toFloat() to A4_H.toFloat()
                        PageSize.LETTER -> 612f to 792f
                        PageSize.FIT -> A4_W.toFloat() to max(1f, A4_W.toFloat() * bmp.height / bmp.width)
                    }
                    val fit = ps == PageSize.FIT
                    val pw = if (landscape && !fit) baseH else baseW
                    val ph = if (landscape && !fit) baseW else baseH
                    val margin = if (fit) 0f else MARGIN
                    val scale = min((pw - 2 * margin) / bmp.width, (ph - 2 * margin) / bmp.height)
                    val w = bmp.width * scale
                    val h = bmp.height * scale
                    val page = PDPage(PDRectangle(pw, ph))
                    doc.addPage(page)
                    // The image only covers w points, so size pixels for that width, not the whole page.
                    val encoded = CompressionEngine.encodePage(doc, bmp, w, level, hintFor?.invoke(i))
                    PDPageContentStream(doc, page).use { cs -> cs.drawImage(encoded.image, (pw - w) / 2, (ph - h) / 2, w, h) }
                } finally {
                    bmp.recycle()
                }
            }
            val tmp = File(out.parentFile, out.name + ".tmp")
            doc.save(tmp)
            if (!tmp.renameTo(out)) { tmp.copyTo(out, true); tmp.delete() }
        }
    }

    fun decodeScaled(context: Context, uri: Uri, maxSide: Int): Bitmap? {
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
