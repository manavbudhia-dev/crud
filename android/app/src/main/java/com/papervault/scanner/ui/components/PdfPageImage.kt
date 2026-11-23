package com.papervault.scanner.ui.components

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.papervault.scanner.pdf.PdfUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Process-wide cache of rendered pages, sized at 1/8 of the app heap. */
object PageCache {
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    // PdfRenderer is not thread-safe per file; serialise renders to keep memory predictable.
    private val renderLock = Mutex()

    fun key(file: File, page: Int, width: Int) = "${file.absolutePath}:${file.lastModified()}:${file.length()}:$page:$width"

    fun peek(key: String): Bitmap? = cache.get(key)

    fun clear() = cache.evictAll()

    suspend fun load(file: File, page: Int, width: Int): Bitmap? {
        val k = key(file, page, width)
        cache.get(k)?.let { return it }
        return renderLock.withLock {
            cache.get(k) ?: withContext(Dispatchers.IO) { PdfUtils.renderPage(file, page, width) }
                ?.also { cache.put(k, it) }
        }
    }
}

@Composable
fun PdfPageImage(
    path: String,
    page: Int,
    renderWidth: Int,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
) {
    val file = File(path)
    val version = file.lastModified() xor file.length()
    // Show a cheap low-res render immediately (or one already cached), then swap in the sharp one.
    val previewWidth = (renderWidth / 4).coerceAtLeast(120)
    var bitmap by remember(path, version, page, renderWidth) {
        mutableStateOf(
            PageCache.peek(PageCache.key(file, page, renderWidth))
                ?: PageCache.peek(PageCache.key(file, page, previewWidth)),
        )
    }
    LaunchedEffect(path, version, page, renderWidth) {
        if (bitmap == null && renderWidth > previewWidth * 2) bitmap = PageCache.load(file, page, previewWidth)
        if (bitmap?.width != renderWidth) PageCache.load(file, page, renderWidth)?.let { bitmap = it }
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        androidx.compose.animation.Crossfade(
            targetState = bitmap,
            animationSpec = androidx.compose.animation.core.tween(220),
            label = "page",
        ) { bmp ->
            if (bmp != null) {
                Image(
                    bmp.asImageBitmap(), contentDescription = "Page ${page + 1}", contentScale = contentScale,
                    filterQuality = androidx.compose.ui.graphics.FilterQuality.Medium,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }
        }
    }
}
