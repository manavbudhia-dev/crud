package com.papervault.scanner

import com.papervault.scanner.ui.scan.Illumination
import org.junit.Assert.assertTrue
import org.junit.Test

class IlluminationTest {
    private fun gray(c: Int) = ((c shr 16 and 0xFF) + (c shr 8 and 0xFF) + (c and 0xFF)) / 3

    /** White page with a heavy left-to-right shadow and black text lines. */
    private fun shadedPage(w: Int, h: Int): IntArray = IntArray(w * h) { i ->
        val x = i % w; val y = i / w
        val light = 0.35f + 0.65f * x / w                    // strong shadow on the left
        val warm = intArrayOf(255, 236, 200)                  // yellow lamp cast
        val ink = (y % 40 in 10..14) && (x % 60 in 5..50)    // text lines
        val base = if (ink) intArrayOf(25, 25, 25) else warm
        val r = (base[0] * light).toInt(); val g = (base[1] * light).toInt(); val b = (base[2] * light).toInt()
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    @Test fun flattensShadowAndKeepsInk() {
        val w = 600; val h = 400
        val src = shadedPage(w, h)
        val out = Illumination.normalize(src, w, h)
        // Background samples (not ink) across the shadow gradient
        val bgSrc = listOf(20, 300, 580).map { gray(src[20 * w + it]) }
        val bgOut = listOf(20, 300, 580).map { gray(out[20 * w + it]) }
        assertTrue("source should be uneven: $bgSrc", bgSrc.max() - bgSrc.min() > 100)
        assertTrue("background should be even and bright: $bgOut", bgOut.max() - bgOut.min() < 25 && bgOut.min() > 225)
        // Ink in the dark (shadowed) region must stay dark
        val inkOut = gray(out[12 * w + 20])
        assertTrue("ink should stay dark: $inkOut", inkOut < 90)
        // Colour cast removed: background roughly neutral
        val c = out[20 * w + 300]
        val spread = listOf(c shr 16 and 0xFF, c shr 8 and 0xFF, c and 0xFF).let { it.max() - it.min() }
        assertTrue("cast should be removed, channel spread $spread", spread < 20)
    }

    @Test fun fastEnough() {
        val w = 1600; val h = 1200
        val src = shadedPage(w, h)
        Illumination.normalize(src, w, h)
        val t = System.nanoTime()
        Illumination.normalize(src, w, h)
        val ms = (System.nanoTime() - t) / 1e6
        assertTrue("took ${ms}ms", ms < 1500)
    }
}
