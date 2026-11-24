package com.papervault.scanner.pdf.compress

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import com.papervault.scanner.ui.scan.DocDetector
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceGray
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

data class EncodedPage(val image: PDImageXObject, val encoding: PageEncoding)
data class OptimizeReport(val imagesSeen: Int, val imagesRecompressed: Int, val bytesBefore: Long, val bytesAfter: Long)

/**
 * The compression engine: every image page the app writes goes through [encodePage], so PDFs are
 * small when they're created rather than shrunk afterwards.
 *
 *  1. Downscale to the level's dpi for the page's physical size (no point storing 4000 px for A4 at 200 dpi).
 *  2. Analyse content → colour JPEG, desaturated JPEG, or 1-bit Flate for black-and-white text.
 *  3. Encode at the level's JPEG quality.
 */
object CompressionEngine {

    /** Encode [src] for a page of [pageWpt] x [pageHpt] points. Does not recycle [src]. */
    fun encodePage(doc: PDDocument, src: Bitmap, pageWpt: Float, level: CompressionLevel, hint: PageEncoding? = null): EncodedPage {
        if (level == CompressionLevel.OFF) {
            return EncodedPage(JPEGFactory.createFromImage(doc, src, level.jpegQuality), PageEncoding.COLOR)
        }
        val px = pixels(src)
        val encoding = ContentAnalyzer.decide(px, src.width, src.height, level, hint)
        // 1-bit images need more pixels to keep text edges smooth; they're still far smaller.
        val dpi = if (encoding == PageEncoding.BILEVEL) max(level.dpi, 300) else level.dpi
        val target = ContentAnalyzer.targetWidth(pageWpt, dpi)
        val scaled = downscale(src, target)
        try {
            val image = when (encoding) {
                PageEncoding.COLOR -> JPEGFactory.createFromImage(doc, scaled, level.jpegQuality)
                PageEncoding.GRAY -> grayscale(scaled).let { g -> JPEGFactory.createFromImage(doc, g, level.jpegQuality).also { if (g != scaled) g.recycle() } }
                PageEncoding.BILEVEL -> bilevel(doc, scaled)
            }
            return EncodedPage(image, encoding)
        } finally {
            if (scaled != src) scaled.recycle()
        }
    }

    /**
     * "Keep text" optimisation for existing PDFs: re-encode only embedded images that are larger
     * than needed, keeping text, vectors, links and form fields untouched. A re-encoded image is
     * only used when it's at least 10% smaller.
     */
    fun optimizeImages(file: File, level: CompressionLevel, out: File, onProgress: (Int, Int) -> Unit = { _, _ -> }): OptimizeReport {
        var seen = 0; var changed = 0
        PDDocument.load(file).use { doc ->
            val visited = HashSet<Any>()
            val total = doc.numberOfPages
            doc.pages.forEachIndexed { i, page ->
                val res = page.resources ?: return@forEachIndexed
                val box = page.cropBox
                val (s, c) = optimizeResources(doc, res, box.width, level, visited, depth = 0)
                seen += s; changed += c
                onProgress(i + 1, total)
            }
            doc.save(out)
        }
        return OptimizeReport(seen, changed, file.length(), out.length())
    }

    private fun optimizeResources(doc: PDDocument, res: PDResources, pageWpt: Float, level: CompressionLevel, visited: HashSet<Any>, depth: Int): Pair<Int, Int> {
        var seen = 0; var changed = 0
        for (name in res.xObjectNames.toList()) {
            val xo = runCatching { res.getXObject(name) }.getOrNull() ?: continue
            if (!visited.add(xo.cosObject)) continue
            when (xo) {
                is PDImageXObject -> {
                    seen++
                    if (tryRecompress(doc, res, name, xo, pageWpt, level)) changed++
                }
                is PDFormXObject -> if (depth < 3) xo.resources?.let {
                    val (s, c) = optimizeResources(doc, it, pageWpt, level, visited, depth + 1)
                    seen += s; changed += c
                }
            }
        }
        return seen to changed
    }

    private fun tryRecompress(doc: PDDocument, res: PDResources, name: COSName, img: PDImageXObject, pageWpt: Float, level: CompressionLevel): Boolean {
        if (level == CompressionLevel.OFF) return false
        val before = img.cosObject.length
        if (before < 40_000 || img.width < 300 || img.height < 300) return false   // already small
        if (img.isStencil || img.bitsPerComponent == 1) return false // masks / already 1-bit
        val bmp = runCatching { img.image }.getOrNull() ?: return false
        try {
            // Many exporters attach an all-opaque alpha mask; only real transparency blocks JPEG.
            if (img.cosObject.containsKey(COSName.SMASK) && hasTransparency(img)) return false
            val encoded = encodePage(doc, bmp, pageWpt, level)
            val after = encoded.image.cosObject.length
            if (after < before * 0.9) {
                res.put(name, encoded.image)
                return true
            }
            return false
        } finally {
            bmp.recycle()
        }
    }

    private fun hasTransparency(img: PDImageXObject): Boolean {
        val mask = runCatching { img.softMask?.image }.getOrNull() ?: return false
        try {
            val px = pixels(mask)
            // Soft masks decode as grey images: anything not fully white is (partly) transparent.
            return px.any { c -> (c and 0xFF) < 250 || (c ushr 24) < 250 && (c ushr 24) != 0 }
        } finally {
            mask.recycle()
        }
    }

    // ------------------------------------------------------------------ pixel helpers

    private fun pixels(b: Bitmap): IntArray = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    private fun downscale(b: Bitmap, targetWidth: Int): Bitmap {
        if (targetWidth <= 0 || b.width <= targetWidth * 1.05f) return b
        val h = max(1, (b.height * targetWidth.toFloat() / b.width).roundToInt())
        return Bitmap.createScaledBitmap(b, targetWidth, h, true)
    }

    private fun grayscale(b: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(b, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) }) })
        return out
    }

    /**
     * 1-bit DeviceGray image (1 = white paper, 0 = black ink), rows packed MSB-first and
     * Flate-compressed. Long runs of white paper compress extremely well, so a text page at
     * 300 dpi is typically tens of KB.
     */
    internal fun bilevel(doc: PDDocument, b: Bitmap): PDImageXObject {
        val w = b.width; val h = b.height
        val packed = packBits(pixels(b), w, h)
        val deflated = java.io.ByteArrayOutputStream(packed.size / 8)
        java.util.zip.DeflaterOutputStream(deflated, java.util.zip.Deflater(java.util.zip.Deflater.BEST_COMPRESSION)).use { it.write(packed) }
        return PDImageXObject(doc, deflated.toByteArray().inputStream(), COSName.FLATE_DECODE, w, h, 1, PDDeviceGray.INSTANCE)
    }

    /** Otsu threshold, then pack each row into bytes (bit = 1 for paper). Pure Kotlin; unit-tested. */
    internal fun packBits(px: IntArray, w: Int, h: Int): ByteArray {
        val luma = IntArray(px.size) { val c = px[it]; (((c shr 16) and 0xFF) * 77 + ((c shr 8) and 0xFF) * 150 + (c and 0xFF) * 29) shr 8 }
        val t = DocDetector.otsu(luma)
        val rowBytes = (w + 7) / 8
        val out = ByteArray(rowBytes * h)
        for (y in 0 until h) {
            val src = y * w; val dst = y * rowBytes
            for (x in 0 until w) if (luma[src + x] > t) {
                val i = dst + (x shr 3)
                out[i] = (out[i].toInt() or (0x80 ushr (x and 7))).toByte()
            }
        }
        return out
    }


}
