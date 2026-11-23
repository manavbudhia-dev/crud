package com.papervault.scanner.pdf

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.StaticLayout
import android.text.TextPaint
import android.util.LruCache
import java.io.File
import kotlin.math.roundToInt

/** Annotation items in normalised page coordinates (0..1, origin top-left of displayed page). */
sealed interface AnnItem {
    data class Stroke(
        val points: List<Pair<Float, Float>>,
        val color: Int,
        /** Stroke width as a fraction of page width. */
        val width: Float,
        val highlighter: Boolean,
    ) : AnnItem

    data class Text(val x: Float, val y: Float, val text: String, val color: Int, /** fraction of page width */ val size: Float) : AnnItem

    /** A placed image such as a signature; (x, y) is the top-left; [width] is a fraction of page width. */
    data class Stamp(val x: Float, val y: Float, val path: String, val width: Float, val aspect: Float) : AnnItem
}

object AnnotationRenderer {

    private val images = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun loadStamp(path: String): Bitmap? =
        images.get(path) ?: BitmapFactory.decodeFile(path)?.also { images.put(path, it) }

    /** Draw items onto [canvas] covering a [w]x[h] page. Used for both on-screen preview and export. */
    fun draw(canvas: Canvas, items: List<AnnItem>, w: Float, h: Float) {
        for (item in items) when (item) {
            is AnnItem.Stroke -> drawStroke(canvas, item, w, h)
            is AnnItem.Text -> {
                val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = item.color
                    textSize = item.size * w
                }
                val maxWidth = ((1f - item.x) * w).roundToInt().coerceAtLeast(1)
                val layout = StaticLayout.Builder.obtain(item.text, 0, item.text.length, paint, maxWidth).build()
                canvas.save()
                canvas.translate(item.x * w, item.y * h)
                layout.draw(canvas)
                canvas.restore()
            }
            is AnnItem.Stamp -> {
                val bmp = loadStamp(item.path) ?: continue
                val dw = item.width * w
                val dh = dw / item.aspect
                canvas.drawBitmap(bmp, null, RectF(item.x * w, item.y * h, item.x * w + dw, item.y * h + dh), Paint(Paint.FILTER_BITMAP_FLAG))
            }
        }
    }

    private fun drawStroke(canvas: Canvas, s: AnnItem.Stroke, w: Float, h: Float) {
        if (s.points.isEmpty()) return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = if (s.highlighter) Paint.Cap.SQUARE else Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = s.width * w
            color = s.color
            if (s.highlighter) alpha = 90
        }
        val path = Path()
        s.points.forEachIndexed { i, (x, y) -> if (i == 0) path.moveTo(x * w, y * h) else path.lineTo(x * w, y * h) }
        if (s.points.size == 1) path.lineTo(s.points[0].first * w + 0.1f, s.points[0].second * h)
        canvas.drawPath(path, paint)
    }

    /** Bounds of an item in normalised coordinates, for hit-testing. */
    fun bounds(item: AnnItem, pageAspect: Float): RectF = when (item) {
        is AnnItem.Stroke -> {
            val xs = item.points.map { it.first }
            val ys = item.points.map { it.second }
            val pad = item.width
            RectF(xs.min() - pad, ys.min() - pad * pageAspect, xs.max() + pad, ys.max() + pad * pageAspect)
        }
        is AnnItem.Text -> {
            val lines = item.text.lines()
            val longest = lines.maxOf { it.length }.coerceAtLeast(1)
            // Rough estimate: average glyph ≈ 0.55em; line height ≈ 1.2em. Height is in page-height units.
            RectF(item.x, item.y, item.x + longest * item.size * 0.55f, item.y + lines.size * item.size * 1.2f * pageAspect)
        }
        is AnnItem.Stamp -> RectF(item.x, item.y, item.x + item.width, item.y + item.width / item.aspect * pageAspect)
    }

    /** Render a transparent overlay bitmap for export. */
    fun renderOverlay(items: List<AnnItem>, widthPx: Int, heightPx: Int): Bitmap {
        val bmp = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        draw(Canvas(bmp), items, widthPx.toFloat(), heightPx.toFloat())
        return bmp
    }

    /** Write annotations into [file] as overlays, preserving underlying text. */
    fun export(file: File, perPage: Map<Int, List<AnnItem>>, out: File) {
        val overlays = HashMap<Int, Bitmap>()
        try {
            perPage.filterValues { it.isNotEmpty() }.forEach { (page, items) ->
                val (wPt, hPt) = PdfUtils.pageSizePoints(file, page)
                val scale = (2000f / wPt).coerceAtMost(3f)
                overlays[page] = renderOverlay(items, (wPt * scale).roundToInt(), (hPt * scale).roundToInt())
            }
            PdfOps.overlayImages(file, overlays, out)
        } finally {
            overlays.values.forEach { it.recycle() }
        }
    }
}
