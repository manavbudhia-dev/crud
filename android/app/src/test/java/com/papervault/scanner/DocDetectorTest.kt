package com.papervault.scanner

import com.papervault.scanner.ui.scan.DocDetector
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class DocDetectorTest {

    /** Dark noisy background with a bright convex quad; returns luma array. */
    private fun frame(w: Int, h: Int, corners: List<Pair<Float, Float>>, seed: Int = 1): IntArray {
        val r = Random(seed)
        val px = corners.map { it.first * w to it.second * h }
        fun inside(x: Float, y: Float): Boolean {
            var sign = 0
            for (i in 0 until 4) {
                val (ax, ay) = px[i]; val (bx, by) = px[(i + 1) % 4]
                val c = (bx - ax) * (y - ay) - (by - ay) * (x - ax)
                val s = if (c > 0) 1 else -1
                if (sign == 0) sign = s else if (s != sign) return false
            }
            return true
        }
        return IntArray(w * h) { i ->
            val x = i % w + 0.5f; val y = i / w + 0.5f
            (if (inside(x, y)) 215 else 70) + r.nextInt(-18, 18)
        }
    }

    private fun close(a: Pair<Float, Float>, b: Pair<Float, Float>, tol: Float = 0.03f) =
        abs(a.first - b.first) < tol && abs(a.second - b.second) < tol

    @Test fun findsAxisAlignedPage() {
        val c = listOf(0.15f to 0.1f, 0.85f to 0.1f, 0.85f to 0.9f, 0.15f to 0.9f)
        val r = DocDetector.detect(frame(160, 120, c), 160, 120)
        assertNotNull(r)
        r!!.quad.points.zip(c).forEach { (got, want) -> assertTrue("$got vs $want", close(got, want)) }
    }

    @Test fun findsTiltedPerspectivePage() {
        val c = listOf(0.25f to 0.08f, 0.82f to 0.18f, 0.74f to 0.93f, 0.12f to 0.80f)
        val r = DocDetector.detect(frame(160, 120, c, seed = 7), 160, 120)
        assertNotNull(r)
        r!!.quad.points.zip(c).forEach { (got, want) -> assertTrue("$got vs $want", close(got, want, 0.04f)) }
    }

    @Test fun rejectsEmptyScene() {
        val r = Random(3)
        assertNull(DocDetector.detect(IntArray(160 * 120) { 90 + r.nextInt(-10, 10) }, 160, 120))
    }

    @Test fun isFast() {
        val c = listOf(0.2f to 0.1f, 0.8f to 0.15f, 0.85f to 0.9f, 0.1f to 0.85f)
        val f = frame(160, 120, c)
        repeat(20) { DocDetector.detect(f, 160, 120) } // warm-up
        val t = System.nanoTime()
        repeat(50) { DocDetector.detect(f, 160, 120) }
        val ms = (System.nanoTime() - t) / 50 / 1e6
        assertTrue("took ${ms}ms per frame", ms < 15)
    }
}
