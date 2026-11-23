package com.papervault.scanner.ui.scan

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.papervault.scanner.data.DocumentRepository
import com.papervault.scanner.data.DocumentSource
import com.papervault.scanner.data.SettingsRepository
import com.papervault.scanner.pdf.PdfUtils
import com.papervault.scanner.ui.container
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

data class ScanPage(val raw: File, val quad: Quad = Quad.inset(), val filter: ScanFilter = ScanFilter.ENHANCE, val rotation: Int = 0)

class ScanViewModel(private val app: Application, private val repo: DocumentRepository, private val settings: SettingsRepository) : ViewModel() {
    private val dir = File(app.cacheDir, "scan_session").apply { deleteRecursively(); mkdirs() }

    val pages = MutableStateFlow<List<ScanPage>>(emptyList())
    /** Index of the page being adjusted, or null while the camera is showing. */
    val editing = MutableStateFlow<Int?>(null)
    val busy = MutableStateFlow(false)
    val messages = Channel<String>(Channel.BUFFERED)
    val done = Channel<Long>(Channel.BUFFERED)
    private val _done get() = done
    val doneFlow = done.receiveAsFlow()

    fun onCaptured(bmp: Bitmap) = viewModelScope.launch {
        val f = withContext(Dispatchers.IO) {
            val scaled = downscale(bmp)
            ScanProcessing.saveJpeg(scaled, File(dir, "${UUID.randomUUID()}.jpg"), 92).also { if (scaled != bmp) scaled.recycle(); bmp.recycle() }
        }
        pages.update { it + ScanPage(f) }
        editing.value = pages.value.lastIndex
    }

    fun onImported(uris: List<Uri>) = viewModelScope.launch {
        busy.value = true
        val files = withContext(Dispatchers.IO) {
            uris.mapNotNull { u ->
                PdfUtils.decodeScaled(app, u, 2400)?.let { b -> ScanProcessing.saveJpeg(b, File(dir, "${UUID.randomUUID()}.jpg")).also { b.recycle() } }
            }
        }
        busy.value = false
        if (files.isEmpty()) return@launch
        pages.update { it + files.map { f -> ScanPage(f, quad = Quad.FULL) } }
        editing.value = pages.value.size - files.size
    }

    fun update(index: Int, transform: (ScanPage) -> ScanPage) = pages.update { l -> l.mapIndexed { i, p -> if (i == index) transform(p) else p } }

    fun retakeLast() {
        val l = pages.value
        if (l.isEmpty()) return
        l.last().raw.delete()
        pages.value = l.dropLast(1)
        editing.value = null
    }

    fun delete(index: Int) {
        pages.update { it.filterIndexed { i, _ -> i != index } }
        editing.value = null
    }

    fun backToCamera() { editing.value = null }

    /** Warp + filter every page and assemble the PDF. */
    fun finish() {
        val list = pages.value
        if (list.isEmpty()) return
        viewModelScope.launch {
            busy.value = true
            try {
                val outs = withContext(Dispatchers.IO) {
                    list.mapIndexed { i, p ->
                        val src = ScanProcessing.decode(p.raw) ?: error("Could not read page ${i + 1}")
                        val warped = ScanProcessing.warp(src, p.quad).also { src.recycle() }
                        val rotated = ScanProcessing.rotate(warped, p.rotation).also { if (it != warped) warped.recycle() }
                        val filtered = ScanProcessing.filter(rotated, p.filter).also { if (it != rotated) rotated.recycle() }
                        ScanProcessing.saveJpeg(filtered, File(dir, "out_$i.jpg"), 88).also { filtered.recycle() }.toUri()
                    }
                }
                val id = repo.createFromImages(outs, pageSize = settings.settings.value.pageSize, source = DocumentSource.SCAN)
                dir.deleteRecursively()
                _done.send(id)
            } catch (e: Exception) {
                messages.send(e.message ?: "Could not create PDF")
            } finally {
                busy.value = false
            }
        }
    }

    private fun downscale(b: Bitmap, maxSide: Int = 2400): Bitmap {
        val m = maxOf(b.width, b.height)
        if (m <= maxSide) return b
        val s = maxSide.toFloat() / m
        return Bitmap.createScaledBitmap(b, (b.width * s).toInt(), (b.height * s).toInt(), true)
    }

    override fun onCleared() { dir.deleteRecursively() }

    companion object {
        val Factory = viewModelFactory { initializer { ScanViewModel(this[APPLICATION_KEY]!!, container.documents, container.settings) } }
    }
}
