package com.papervault.scanner.ui.components

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
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

    fun key(path: String, modified: Long, page: Int, width: Int) = "$path:$modified:$page:$width"

    fun peek(key: String): Bitmap? = cache.get(key)

    suspend fun load(file: File, page: Int, width: Int): Bitmap? {
        val k = key(file.absolutePath, file.lastModified(), page, width)
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
    val file = remember(path) { File(path) }
    var bitmap by remember(path, page, renderWidth) {
        mutableStateOf(PageCache.peek(PageCache.key(path, file.lastModified(), page, renderWidth)))
    }
    LaunchedEffect(path, page, renderWidth) {
        if (bitmap == null) bitmap = PageCache.load(file, page, renderWidth)
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        val bmp = bitmap
        if (bmp != null) {
            Image(bmp.asImageBitmap(), contentDescription = "Page ${page + 1}", contentScale = contentScale)
        } else {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
    }
}
