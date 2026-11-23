package com.papervault.scanner.ui.scan

import kotlin.math.abs
import kotlin.math.max

/**
 * Fast document-edge detector for small grayscale frames (≈160 px wide).
 *
 * Paper is usually brighter than what it lies on, so: blur → Otsu threshold → largest bright
 * connected region → convex hull → best-area quadrilateral on that hull. Runs in a few
 * milliseconds, which is enough to track the page live from the camera preview.
 * Pure Kotlin (no Android types) so it is unit-tested on the JVM.
 */
object DocDetector {

    data class Result(val quad: Quad, val areaFraction: Float)

    /**
     * @param luma grayscale values 0..255, row-major, [w] x [h]
     * @return the page corners in normalised coordinates, or null when no page-like region is found
     */
    fun detect(luma: IntArray, w: Int, h: Int): Result? {
        if (w < 16 || h < 16) return null
        val blurred = boxBlur(luma, w, h)
        val t = otsu(blurred)
        val mask = BooleanArray(w * h) { blurred[it] > t }

        val region = largestRegion(mask, w, h) ?: return null
        val total = w * h
        val fraction = region.size.toFloat() / total
        if (fraction < 0.12f || fraction > 0.97f) return null

        // Boundary points only: a pixel of the region with a non-region 4-neighbour.
        val inRegion = BooleanArray(total).also { a -> region.forEach { a[it] = true } }
        val boundary = ArrayList<IntArray>()
        for (i in region) {
            val x = i % w; val y = i / w
            if (x == 0 || y == 0 || x == w - 1 || y == h - 1 ||
                !inRegion[i - 1] || !inRegion[i + 1] || !inRegion[i - w] || !inRegion[i + w]
            ) boundary.add(intArrayOf(x, y))
        }
        val hull = convexHull(boundary)
        if (hull.size < 4) return null
        val quadPts = bestQuad(hull)
        val quadArea = polygonArea(quadPts)
        // Reject blobs that are far from quadrilateral (e.g. a lamp, a hand).
        if (quadArea < 0.85f * region.size || quadArea < 0.12f * total) return null

        fun n(p: IntArray) = (p[0] + 0.5f) / w to (p[1] + 0.5f) / h
        val ordered = orderCorners(quadPts)
        return Result(Quad(n(ordered[0]), n(ordered[1]), n(ordered[2]), n(ordered[3])), quadArea / total)
    }

    // ---------------------------------------------------------------- steps

    private fun boxBlur(src: IntArray, w: Int, h: Int): IntArray {
        val out = IntArray(src.size)
        for (y in 0 until h) for (x in 0 until w) {
            var sum = 0; var n = 0
            for (dy in -1..1) {
                val yy = y + dy
                if (yy < 0 || yy >= h) continue
                for (dx in -1..1) {
                    val xx = x + dx
                    if (xx < 0 || xx >= w) continue
                    sum += src[yy * w + xx]; n++
                }
            }
            out[y * w + x] = sum / n
        }
        return out
    }

    fun otsu(px: IntArray): Int {
        val hist = IntArray(256)
        px.forEach { hist[it.coerceIn(0, 255)]++ }
        val total = px.size
        var sum = 0.0
        for (i in 0..255) sum += i.toDouble() * hist[i]
        var sumB = 0.0; var wB = 0; var best = 0.0; var threshold = 127
        for (t in 0..255) {
            wB += hist[t]
            if (wB == 0) continue
            val wF = total - wB
            if (wF == 0) break
            sumB += t.toDouble() * hist[t]
            val mB = sumB / wB
            val mF = (sum - sumB) / wF
            val between = wB.toDouble() * wF * (mB - mF) * (mB - mF)
            if (between > best) { best = between; threshold = t }
        }
        return threshold
    }

    /** Largest 4-connected true region, as pixel indexes. */
    private fun largestRegion(mask: BooleanArray, w: Int, h: Int): IntArray? {
        val label = IntArray(mask.size)
        val stack = IntArray(mask.size)
        var bestLabel = 0; var bestSize = 0; var next = 1
        for (start in mask.indices) {
            if (!mask[start] || label[start] != 0) continue
            var sp = 0; var size = 0
            stack[sp++] = start; label[start] = next
            while (sp > 0) {
                val i = stack[--sp]; size++
                val x = i % w; val y = i / w
                if (x > 0 && mask[i - 1] && label[i - 1] == 0) { label[i - 1] = next; stack[sp++] = i - 1 }
                if (x < w - 1 && mask[i + 1] && label[i + 1] == 0) { label[i + 1] = next; stack[sp++] = i + 1 }
                if (y > 0 && mask[i - w] && label[i - w] == 0) { label[i - w] = next; stack[sp++] = i - w }
                if (y < h - 1 && mask[i + w] && label[i + w] == 0) { label[i + w] = next; stack[sp++] = i + w }
            }
            if (size > bestSize) { bestSize = size; bestLabel = next }
            next++
        }
        if (bestLabel == 0) return null
        val out = IntArray(bestSize)
        var k = 0
        for (i in label.indices) if (label[i] == bestLabel) out[k++] = i
        return out
    }

    /** Andrew's monotone chain. */
    private fun convexHull(pts: List<IntArray>): List<IntArray> {
        if (pts.size < 3) return pts
        val s = pts.sortedWith(compareBy({ it[0] }, { it[1] }))
        fun cross(o: IntArray, a: IntArray, b: IntArray) = (a[0] - o[0]).toLong() * (b[1] - o[1]) - (a[1] - o[1]).toLong() * (b[0] - o[0])
        val lower = ArrayList<IntArray>()
        for (p in s) { while (lower.size >= 2 && cross(lower[lower.size - 2], lower.last(), p) <= 0) lower.removeAt(lower.lastIndex); lower.add(p) }
        val upper = ArrayList<IntArray>()
        for (p in s.asReversed()) { while (upper.size >= 2 && cross(upper[upper.size - 2], upper.last(), p) <= 0) upper.removeAt(upper.lastIndex); upper.add(p) }
        lower.removeAt(lower.lastIndex); upper.removeAt(upper.lastIndex)
        return lower + upper
    }

    /** Four hull points with (near-)maximal enclosed area, via coordinate-ascent from extreme points. */
    private fun bestQuad(hull: List<IntArray>): List<IntArray> {
        val n = hull.size
        val idx = intArrayOf(
            hull.indices.minBy { hull[it][0] + hull[it][1] },
            hull.indices.maxBy { hull[it][0] - hull[it][1] },
            hull.indices.maxBy { hull[it][0] + hull[it][1] },
            hull.indices.minBy { hull[it][0] - hull[it][1] },
        )
        fun area(ix: IntArray) = polygonArea(ix.sorted().map { hull[it] })
        var best = area(idx)
        repeat(4) {
            var improved = false
            for (k in 0..3) for (c in 0 until n) {
                if (c in idx) continue
                val old = idx[k]; idx[k] = c
                val a = area(idx)
                if (a > best + 1e-3f) { best = a; improved = true } else idx[k] = old
            }
            if (!improved) return idx.sorted().map { hull[it] }
        }
        return idx.sorted().map { hull[it] }
    }

    private fun polygonArea(p: List<IntArray>): Float {
        var a = 0L
        for (i in p.indices) { val j = (i + 1) % p.size; a += p[i][0].toLong() * p[j][1] - p[j][0].toLong() * p[i][1] }
        return abs(a) / 2f
    }

    /** Order as top-left, top-right, bottom-right, bottom-left. */
    private fun orderCorners(p: List<IntArray>): List<IntArray> {
        val cx = p.sumOf { it[0] } / 4.0; val cy = p.sumOf { it[1] } / 4.0
        val sorted = p.sortedBy { kotlin.math.atan2(it[1] - cy, it[0] - cx) } // clockwise in screen coords from left
        val start = sorted.indices.minBy { sorted[it][0] + sorted[it][1] }
        return List(4) { sorted[(start + it) % 4] }
    }

    /** Re-order arbitrary corners as TL, TR, BR, BL (needed after rotating coordinates). */
    fun reorder(q: Quad): Quad {
        val pts = q.points
        val cx = pts.sumOf { it.first.toDouble() } / 4; val cy = pts.sumOf { it.second.toDouble() } / 4
        val sorted = pts.sortedBy { kotlin.math.atan2(it.second - cy, it.first - cx) }
        val start = sorted.indices.minBy { sorted[it].first + sorted[it].second }
        val o = List(4) { sorted[(start + it) % 4] }
        return Quad(o[0], o[1], o[2], o[3])
    }

    /** Rotate normalised corners clockwise by [degrees] (sensor → upright). */
    fun rotate(q: Quad, degrees: Int): Quad {
        fun r(p: Pair<Float, Float>) = when ((degrees % 360 + 360) % 360) {
            90 -> (1 - p.second) to p.first
            180 -> (1 - p.first) to (1 - p.second)
            270 -> p.second to (1 - p.first)
            else -> p
        }
        return reorder(Quad(r(q.tl), r(q.tr), r(q.br), r(q.bl)))
    }

    /** Largest corner movement between two quads (normalised units). */
    fun distance(a: Quad, b: Quad): Float = a.points.zip(b.points).maxOf { (p, q) -> max(abs(p.first - q.first), abs(p.second - q.second)) }
}
