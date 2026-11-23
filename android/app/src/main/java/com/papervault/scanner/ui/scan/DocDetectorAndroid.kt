package com.papervault.scanner.ui.scan

import android.graphics.Bitmap
import androidx.camera.core.ImageProxy
import kotlin.math.max

/** Android adapters for [DocDetector]: camera frames and bitmaps. */
object DocDetectorAndroid {

    private const val TARGET = 160

    /** Detect on a camera frame's Y (luminance) plane — no colour conversion needed. Returns upright coords. */
    fun detect(image: ImageProxy): Quad? {
        val plane = image.planes[0]
        val buf = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val step = max(1, max(image.width, image.height) / TARGET)
        val w = image.width / step
        val h = image.height / step
        val luma = IntArray(w * h)
        for (y in 0 until h) {
            val row = y * step * rowStride
            for (x in 0 until w) luma[y * w + x] = buf.get(row + x * step * pixelStride).toInt() and 0xFF
        }
        val r = DocDetector.detect(luma, w, h) ?: return null
        return DocDetector.rotate(r.quad, image.imageInfo.rotationDegrees)
    }

    fun detect(bitmap: Bitmap): Quad? {
        val scale = TARGET.toFloat() / max(bitmap.width, bitmap.height)
        val w = max(16, (bitmap.width * scale).toInt())
        val h = max(16, (bitmap.height * scale).toInt())
        val small = Bitmap.createScaledBitmap(bitmap, w, h, true)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        if (small != bitmap) small.recycle()
        val luma = IntArray(w * h) { val c = px[it]; (((c shr 16) and 0xFF) * 77 + ((c shr 8) and 0xFF) * 150 + (c and 0xFF) * 29) shr 8 }
        return DocDetector.detect(luma, w, h)?.quad
    }
}
