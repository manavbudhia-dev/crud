package com.papervault.scanner.ui.scan

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import java.io.File
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

enum class ScanFilter(val label: String) { MAGIC("Magic colour"), ORIGINAL("Original"), ENHANCE("Enhance"), GRAYSCALE("Grayscale"), BW("B&W") }

/** Document = one page per photo; ID card = front + back on one A4 page; Book = split two facing pages. */
enum class ScanMode(val label: String) { DOCUMENT("Document"), ID_CARD("ID card"), BOOK("Book") }

/** Corner points in normalised image coordinates, clockwise from top-left. */
data class Quad(val tl: Pair<Float, Float>, val tr: Pair<Float, Float>, val br: Pair<Float, Float>, val bl: Pair<Float, Float>) {
    val points get() = listOf(tl, tr, br, bl)
    fun with(index: Int, p: Pair<Float, Float>) = when (index) {
        0 -> copy(tl = p); 1 -> copy(tr = p); 2 -> copy(br = p); else -> copy(bl = p)
    }

    companion object {
        fun inset(f: Float = 0.06f) = Quad(f to f, 1 - f to f, 1 - f to 1 - f, f to 1 - f)
        val FULL = inset(0f)
    }
}

object ScanProcessing {

    fun decode(file: File, maxSide: Int = 2400): Bitmap? {
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, b)
        var sample = 1
        while (max(b.outWidth, b.outHeight) / (sample * 2) >= maxSide) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** Perspective-correct the [quad] region into a flat rectangle. */
    fun warp(src: Bitmap, quad: Quad): Bitmap {
        val w = src.width.toFloat(); val h = src.height.toFloat()
        val pts = quad.points.map { (x, y) -> x * w to y * h }
        fun d(a: Pair<Float, Float>, b: Pair<Float, Float>) = hypot(a.first - b.first, a.second - b.second)
        val outW = max(d(pts[0], pts[1]), d(pts[3], pts[2])).roundToInt().coerceIn(1, 4000)
        val outH = max(d(pts[0], pts[3]), d(pts[1], pts[2])).roundToInt().coerceIn(1, 4000)
        val srcArr = floatArrayOf(pts[0].first, pts[0].second, pts[1].first, pts[1].second, pts[2].first, pts[2].second, pts[3].first, pts[3].second)
        val dstArr = floatArrayOf(0f, 0f, outW.toFloat(), 0f, outW.toFloat(), outH.toFloat(), 0f, outH.toFloat())
        val m = Matrix().apply { setPolyToPoly(srcArr, 0, dstArr, 0, 4) }
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        Canvas(out).apply { drawColor(android.graphics.Color.WHITE); drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)) }
        return out
    }

    /** Full clean-up pipeline for one page: optional shadow removal, then the colour filter. */
    fun process(src: Bitmap, f: ScanFilter, removeShadows: Boolean): Bitmap {
        val flat = if (removeShadows || f == ScanFilter.MAGIC || f == ScanFilter.BW) removeShadows(src) else src
        return filter(flat, f).also { if (it != flat && flat != src) flat.recycle() }
    }

    fun removeShadows(src: Bitmap): Bitmap {
        val w = src.width; val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val out = Illumination.normalize(px, w, h)
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    fun filter(src: Bitmap, f: ScanFilter): Bitmap {
        if (f == ScanFilter.ORIGINAL) return src
        val cm = when (f) {
            ScanFilter.MAGIC -> contrast(1.18f, -6f).apply { postConcat(ColorMatrix().apply { setSaturation(1.3f) }) }
            ScanFilter.ENHANCE -> contrast(1.35f, 18f).apply { postConcat(ColorMatrix().apply { setSaturation(1.15f) }) }
            ScanFilter.GRAYSCALE -> ColorMatrix().apply { setSaturation(0f) }.apply { postConcat(contrast(1.2f, 10f)) }
            ScanFilter.BW -> ColorMatrix().apply { setSaturation(0f) }.apply { postConcat(contrast(2.6f, -40f)) }
            ScanFilter.ORIGINAL -> ColorMatrix()
            else -> ColorMatrix()
        }
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(cm) })
        return out
    }

    private fun contrast(c: Float, brightness: Float): ColorMatrix {
        val t = (1 - c) * 128 + brightness
        return ColorMatrix(floatArrayOf(c, 0f, 0f, 0f, t, 0f, c, 0f, 0f, t, 0f, 0f, c, 0f, t, 0f, 0f, 0f, 1f, 0f))
    }

    fun rotate(src: Bitmap, degrees: Int): Bitmap =
        if (degrees % 360 == 0) src else Bitmap.createBitmap(src, 0, 0, src.width, src.height, Matrix().apply { postRotate(degrees.toFloat()) }, true)

    fun saveJpeg(bmp: Bitmap, file: File, quality: Int = 90): File {
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, quality, it) }
        return file
    }
}

/** Layout helpers for the scan modes. */
object ScanLayouts {
    /** Book mode: split a landscape spread into left and right pages. Portrait images are kept whole. */
    fun splitSpread(src: Bitmap): List<Bitmap> {
        if (src.width <= src.height) return listOf(src)
        val half = src.width / 2
        return listOf(
            Bitmap.createBitmap(src, 0, 0, half, src.height),
            Bitmap.createBitmap(src, half, 0, src.width - half, src.height),
        )
    }

    /**
     * ID card mode: place front and (optional) back at true card size (85.6 × 54 mm) on an
     * A4 page at 150 dpi, so printing at 100% gives a life-size copy.
     */
    fun idCardPage(front: Bitmap, back: Bitmap?): Bitmap {
        val dpi = 150f
        fun mm(v: Float) = v / 25.4f * dpi
        val pageW = mm(210f).toInt(); val pageH = mm(297f).toInt()
        val page = Bitmap.createBitmap(pageW, pageH, Bitmap.Config.ARGB_8888)
        val c = Canvas(page)
        c.drawColor(android.graphics.Color.WHITE)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        val cardW = mm(85.6f); val cardH = mm(54f)
        fun place(b: Bitmap, top: Float) {
            // Cards photographed in portrait are rotated to landscape.
            val img = if (b.height > b.width) rotate(b) else b
            val scale = min(cardW / img.width, cardH / img.height)
            val w = img.width * scale; val h = img.height * scale
            val left = (pageW - w) / 2
            c.drawBitmap(img, null, android.graphics.RectF(left, top, left + w, top + h), paint)
            if (img != b) img.recycle()
        }
        val top = mm(40f)
        place(front, top)
        back?.let { place(it, top + cardH + mm(20f)) }
        return page
    }

    private fun rotate(b: Bitmap) = Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(90f) }, true)
    private fun min(a: Float, b: Float) = if (a < b) a else b
}
