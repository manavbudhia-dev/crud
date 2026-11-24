package com.papervault.scanner.pdf.compress

import kotlin.math.abs
import kotlin.math.max

/** How a page image should be stored. */
enum class PageEncoding {
    /** Photos, colour documents: JPEG. */
    COLOR,
    /** Grey documents (pencil, newsprint, grey-scale scans): desaturated JPEG, no chroma noise. */
    GRAY,
    /** Black text on white (typed pages, B&W filter): 1-bit image — typically several times smaller than JPEG. */
    BILEVEL,
}

/** Compression presets. dpi is relative to the PDF page size, so A4 and Letter get the same sharpness. */
enum class CompressionLevel(val label: String, val description: String, val dpi: Int, val jpegQuality: Float, val allowBilevel: Boolean) {
    BEST("High quality", "300 dpi · large files, best for printing", 300, 0.88f, false),
    BALANCED("Balanced", "200 dpi · sharp on screen and in print, much smaller", 200, 0.72f, true),
    SMALLEST("Smallest", "150 dpi · ideal for email and WhatsApp", 150, 0.55f, true),
    OFF("Off", "Keep images as captured", 0, 0.92f, false),
}

/**
 * Decides [PageEncoding] from pixel statistics on a sparse sample of the image.
 * Pure Kotlin on ARGB ints so it is unit-tested on the JVM.
 */
object ContentAnalyzer {

    data class Stats(val colorfulness: Float, val extremeFraction: Float, val midtoneFraction: Float)

    fun stats(px: IntArray, w: Int, h: Int): Stats {
        val step = max(1, max(w, h) / 160)
        var n = 0; var chroma = 0.0; var extremes = 0; var mids = 0
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val c = px[y * w + x]
                val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
                chroma += (abs(r - g) + abs(g - b) + abs(b - r)) / 3.0
                val l = (r * 77 + g * 150 + b * 29) shr 8
                if (l < 70 || l > 200) extremes++
                if (l in 100..170) mids++
                n++
                x += step
            }
            y += step
        }
        return Stats((chroma / n).toFloat(), extremes.toFloat() / n, mids.toFloat() / n)
    }

    /**
     * @param hint what the user asked for (e.g. the B&W or Grayscale scan filter); wins when given.
     */
    fun decide(px: IntArray, w: Int, h: Int, level: CompressionLevel, hint: PageEncoding? = null): PageEncoding {
        if (hint == PageEncoding.BILEVEL) return if (level.allowBilevel) PageEncoding.BILEVEL else PageEncoding.GRAY
        if (hint != null) return hint
        val s = stats(px, w, h)
        val gray = s.colorfulness < 6f
        return when {
            // Text page: almost every pixel is paper or ink, very few mid-tones.
            gray && level.allowBilevel && s.extremeFraction > 0.97f && s.midtoneFraction < 0.015f -> PageEncoding.BILEVEL
            gray -> PageEncoding.GRAY
            else -> PageEncoding.COLOR
        }
    }

    /** Pixel width a page image needs at [dpi] for a page [pageWidthPt] points wide. */
    fun targetWidth(pageWidthPt: Float, dpi: Int): Int = (pageWidthPt / 72f * dpi).toInt()
}
