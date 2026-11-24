package com.papervault.scanner.ui.scan

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Shadow / uneven-light removal for document photos.
 *
 * Estimates the paper's background colour across the page (per-channel local maximum on a
 * coarse grid, dilated and blurred so text doesn't leak in), then divides each pixel by it.
 * Shadows, gradients, yellow lamp casts and soft glare halos flatten to white while ink stays
 * dark. Works on ARGB ints so it runs (and is tested) without Android.
 */
object Illumination {

    fun normalize(px: IntArray, w: Int, h: Int, strength: Float = 1f): IntArray {
        val cell = max(8, max(w, h) / 48)
        val gw = ceil(w / cell.toFloat()).toInt()
        val gh = ceil(h / cell.toFloat()).toInt()
        val bg = Array(3) { FloatArray(gw * gh) }

        // 1. Per-cell 90th-percentile-ish: use max of a sparse sample, robust enough & fast.
        for (gy in 0 until gh) for (gx in 0 until gw) {
            var r = 0; var g = 0; var b = 0
            val y0 = gy * cell; val y1 = min(h, y0 + cell)
            val x0 = gx * cell; val x1 = min(w, x0 + cell)
            var y = y0
            while (y < y1) {
                var x = x0
                while (x < x1) {
                    val c = px[y * w + x]
                    r = max(r, (c shr 16) and 0xFF); g = max(g, (c shr 8) and 0xFF); b = max(b, c and 0xFF)
                    x += 2
                }
                y += 2
            }
            val i = gy * gw + gx
            bg[0][i] = r.toFloat(); bg[1][i] = g.toFloat(); bg[2][i] = b.toFloat()
        }
        // 2. Dilate (spread paper colour over text-heavy cells), then blur twice.
        for (ch in 0..2) {
            bg[ch] = dilate(bg[ch], gw, gh)
            bg[ch] = blur(blur(bg[ch], gw, gh), gw, gh)
        }
        // 3. Divide by the bilinearly-interpolated background.
        val out = IntArray(px.size)
        for (y in 0 until h) {
            val fy = ((y + 0.5f) / cell - 0.5f).coerceIn(0f, (gh - 1).toFloat())
            val y0 = fy.toInt(); val y1 = min(gh - 1, y0 + 1); val ty = fy - y0
            for (x in 0 until w) {
                val fx = ((x + 0.5f) / cell - 0.5f).coerceIn(0f, (gw - 1).toFloat())
                val x0 = fx.toInt(); val x1 = min(gw - 1, x0 + 1); val tx = fx - x0
                val c = px[y * w + x]
                var res = c and 0xFF000000.toInt()
                for (ch in 0..2) {
                    val a = bg[ch]
                    val top = a[y0 * gw + x0] * (1 - tx) + a[y0 * gw + x1] * tx
                    val bot = a[y1 * gw + x0] * (1 - tx) + a[y1 * gw + x1] * tx
                    val back = max(24f, top * (1 - ty) + bot * ty)
                    val v = (c shr (16 - ch * 8)) and 0xFF
                    val corrected = (v * 255f / back).coerceAtMost(255f)
                    val mixed = v + (corrected - v) * strength
                    res = res or (mixed.toInt().coerceIn(0, 255) shl (16 - ch * 8))
                }
                out[y * w + x] = res
            }
        }
        return out
    }

    private fun dilate(a: FloatArray, w: Int, h: Int): FloatArray = FloatArray(a.size) { i ->
        val x = i % w; val y = i / w
        var m = 0f
        for (dy in -1..1) for (dx in -1..1) {
            val xx = x + dx; val yy = y + dy
            if (xx in 0 until w && yy in 0 until h) m = max(m, a[yy * w + xx])
        }
        m
    }

    private fun blur(a: FloatArray, w: Int, h: Int): FloatArray = FloatArray(a.size) { i ->
        val x = i % w; val y = i / w
        var s = 0f; var n = 0
        for (dy in -1..1) for (dx in -1..1) {
            val xx = x + dx; val yy = y + dy
            if (xx in 0 until w && yy in 0 until h) { s += a[yy * w + xx]; n++ }
        }
        s / n
    }
}
