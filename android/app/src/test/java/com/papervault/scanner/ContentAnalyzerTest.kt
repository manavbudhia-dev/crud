package com.papervault.scanner

import com.papervault.scanner.pdf.compress.CompressionLevel
import com.papervault.scanner.pdf.compress.ContentAnalyzer
import com.papervault.scanner.pdf.compress.PageEncoding
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

class ContentAnalyzerTest {
    private fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    private val w = 400; private val h = 560

    private fun textPage() = IntArray(w * h) { i -> val x = i % w; val y = i / w; if (y % 28 in 8..12 && x % 40 in 4..34) argb(15, 15, 15) else argb(250, 250, 250) }
    private fun grayPhoto() = Random(1).let { r -> IntArray(w * h) { val v = 60 + (it % w) * 140 / w + r.nextInt(-10, 10); argb(v, v, v) } }
    private fun colorPhoto() = IntArray(w * h) { i -> argb((i % w) * 255 / w, (i / w) * 255 / h, 120) }

    @Test fun textBecomesBilevel() = assertEquals(PageEncoding.BILEVEL, ContentAnalyzer.decide(textPage(), w, h, CompressionLevel.BALANCED))
    @Test fun textStaysGrayAtHighQuality() = assertEquals(PageEncoding.GRAY, ContentAnalyzer.decide(textPage(), w, h, CompressionLevel.BEST))
    @Test fun grayPhotoIsGray() = assertEquals(PageEncoding.GRAY, ContentAnalyzer.decide(grayPhoto(), w, h, CompressionLevel.BALANCED))
    @Test fun colorIsColor() = assertEquals(PageEncoding.COLOR, ContentAnalyzer.decide(colorPhoto(), w, h, CompressionLevel.SMALLEST))
    @Test fun hintWins() = assertEquals(PageEncoding.BILEVEL, ContentAnalyzer.decide(colorPhoto(), w, h, CompressionLevel.BALANCED, PageEncoding.BILEVEL))
    @Test fun targetWidth() = assertEquals(1652, ContentAnalyzer.targetWidth(595f, 200))
}
