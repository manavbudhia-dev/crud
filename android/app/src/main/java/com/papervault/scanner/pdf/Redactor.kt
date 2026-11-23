package com.papervault.scanner.pdf

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

enum class MaskStyle(val label: String) { BLACK("Black"), WHITE("White-out"), PIXELATE("Pixelate"), BLUR("Blur") }

/** A mask rectangle in normalised page coordinates (0..1, origin top-left of the displayed page). */
data class MaskRect(val left: Float, val top: Float, val right: Float, val bottom: Float, val style: MaskStyle) {
    fun normalized() = MaskRect(minOf(left, right), minOf(top, bottom), maxOf(left, right), maxOf(top, bottom), style)
    val isUsable get() = (right - left) > 0.005f && (bottom - top) > 0.005f
}

object Redactor {

    /** Output resolution for flattened pages. */
    private const val DPI = 200
    private const val MAX_WIDTH = 2400

    /**
     * Produce a copy of [file] where every page with masks is flattened to an image with the
     * masked regions destroyed. Pages without masks keep their original (selectable) content.
     */
    fun redact(file: File, masks: Map<Int, List<MaskRect>>, out: File) {
        val replacements = HashMap<Int, Bitmap>()
        try {
            masks.filterValues { it.isNotEmpty() }.forEach { (page, rects) ->
                val (wPt, _) = PdfUtils.pageSizePoints(file, page)
                val width = (wPt / 72f * DPI).roundToInt().coerceIn(600, MAX_WIDTH)
                val bmp = PdfUtils.renderPage(file, page, width) ?: return@forEach
                apply(bmp, rects)
                replacements[page] = bmp
            }
            PdfOps.replaceWithImages(file, replacements, out)
        } finally {
            replacements.values.forEach { it.recycle() }
        }
    }

    /** Paint masks onto a mutable bitmap in place. Also used for the live preview. */
    fun apply(bmp: Bitmap, rects: List<MaskRect>) {
        val canvas = Canvas(bmp)
        val w = bmp.width
        val h = bmp.height
        for (r in rects.map { it.normalized() }) {
            val px = Rect(
                (r.left * w).toInt().coerceIn(0, w - 1),
                (r.top * h).toInt().coerceIn(0, h - 1),
                (r.right * w).roundToInt().coerceIn(1, w),
                (r.bottom * h).roundToInt().coerceIn(1, h),
            )
            if (px.width() <= 0 || px.height() <= 0) continue
            when (r.style) {
                MaskStyle.BLACK -> canvas.drawRect(px, Paint().apply { color = Color.BLACK })
                MaskStyle.WHITE -> canvas.drawRect(px, Paint().apply { color = Color.WHITE })
                MaskStyle.PIXELATE -> resample(bmp, canvas, px, blockPx = max(12, minOf(px.width(), px.height()) / 3), smooth = false)
                MaskStyle.BLUR -> {
                    // Repeated heavy down/up-sampling: approximates a wide blur and discards detail.
                    repeat(3) { resample(bmp, canvas, px, blockPx = max(10, minOf(px.width(), px.height()) / 2), smooth = true) }
                }
            }
        }
    }

    private fun resample(bmp: Bitmap, canvas: Canvas, px: Rect, blockPx: Int, smooth: Boolean) {
        val region = Bitmap.createBitmap(bmp, px.left, px.top, px.width(), px.height())
        val sw = max(1, px.width() / blockPx)
        val sh = max(1, px.height() / blockPx)
        val small = Bitmap.createScaledBitmap(region, sw, sh, true)
        region.recycle()
        val paint = Paint().apply { isFilterBitmap = smooth }
        canvas.drawBitmap(small, null, RectF(px), paint)
        small.recycle()
    }
}
