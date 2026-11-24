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
import com.papervault.scanner.data.PageSize
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

data class ScanPage(
    val raw: File,
    val quad: Quad = Quad.inset(),
    val filter: ScanFilter = ScanFilter.MAGIC,
    val rotation: Int = 0,
    val removeShadows: Boolean = true,
    val mode: ScanMode = ScanMode.DOCUMENT,
)

class ScanViewModel(private val app: Application, private val repo: DocumentRepository, private val settings: SettingsRepository) : ViewModel() {
    private val dir = File(app.cacheDir, "scan_session").apply { deleteRecursively(); mkdirs() }

    val pages = MutableStateFlow<List<ScanPage>>(emptyList())
    val mode = MutableStateFlow(ScanMode.DOCUMENT)
    /** Index of the page being adjusted, or null while the camera is showing. */
    val editing = MutableStateFlow<Int?>(null)
    val busy = MutableStateFlow(false)
    val messages = Channel<String>(Channel.BUFFERED)
    private val _done = Channel<Long>(Channel.BUFFERED)
    val doneFlow = _done.receiveAsFlow()

    /**
     * [liveQuad] is the page outline tracked in the preview at the moment of capture; if absent,
     * detection runs once more on the full photo; failing that, a default inset frame is used.
     */
    fun onCaptured(bmp: Bitmap, liveQuad: Quad?, openEditor: Boolean = true) = viewModelScope.launch {
        val (f, quad) = withContext(Dispatchers.Default) {
            val scaled = downscale(bmp)
            val q = liveQuad ?: DocDetectorAndroid.detect(scaled) ?: Quad.inset()
            ScanProcessing.saveJpeg(scaled, File(dir, "${UUID.randomUUID()}.jpg"), 92).also { if (scaled != bmp) scaled.recycle(); bmp.recycle() } to q
        }
        pages.update { it + ScanPage(f, quad = quad, mode = mode.value) }
        if (openEditor) editing.value = pages.value.lastIndex
    }

    fun onImported(uris: List<Uri>) = viewModelScope.launch {
        busy.value = true
        val imported = withContext(Dispatchers.IO) {
            uris.mapNotNull { u ->
                PdfUtils.decodeScaled(app, u, 2400)?.let { b ->
                    val q = DocDetectorAndroid.detect(b) ?: Quad.FULL
                    ScanPage(ScanProcessing.saveJpeg(b, File(dir, "${UUID.randomUUID()}.jpg")).also { b.recycle() }, quad = q, mode = mode.value)
                }
            }
        }
        busy.value = false
        if (imported.isEmpty()) return@launch
        pages.update { it + imported }
        editing.value = pages.value.size - imported.size
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
                val outs = withContext(Dispatchers.Default) {
                    var n = 0
                    fun save(b: Bitmap) = ScanProcessing.saveJpeg(b, File(dir, "out_${n++}.jpg"), 88).also { b.recycle() }.toUri()
                    val result = ArrayList<Uri>()
                    val exactA4 = HashSet<Int>()
                    var pendingFront: Bitmap? = null
                    list.forEachIndexed { i, p ->
                        val src = ScanProcessing.decode(p.raw) ?: error("Could not read page ${i + 1}")
                        val warped = ScanProcessing.warp(src, p.quad).also { src.recycle() }
                        val rotated = ScanProcessing.rotate(warped, p.rotation).also { if (it != warped) warped.recycle() }
                        val clean = ScanProcessing.process(rotated, p.filter, p.removeShadows).also { if (it != rotated) rotated.recycle() }
                        when (p.mode) {
                            ScanMode.DOCUMENT -> result += save(clean)
                            ScanMode.BOOK -> ScanLayouts.splitSpread(clean).forEach { half -> result += save(half) }.also { if (!clean.isRecycled) clean.recycle() }
                            ScanMode.ID_CARD -> {
                                val front = pendingFront
                                if (front == null) pendingFront = clean
                                else { exactA4 += result.size; result += save(ScanLayouts.idCardPage(front, clean)); front.recycle(); clean.recycle(); pendingFront = null }
                            }
                        }
                        // An unpaired ID side followed by a different mode is placed alone.
                        val nextMode = list.getOrNull(i + 1)?.mode
                        if (pendingFront != null && nextMode != ScanMode.ID_CARD) {
                            exactA4 += result.size; result += save(ScanLayouts.idCardPage(pendingFront!!, null)); pendingFront!!.recycle(); pendingFront = null
                        }
                    }
                    result to exactA4
                }
                val (uris, a4) = outs
                val userSize = settings.settings.value.pageSize
                // ID-card pages are already A4-shaped at true card size: FIT makes the PDF page exactly A4.
                val id = repo.createFromImages(uris, pageSize = userSize, source = DocumentSource.SCAN) { i -> if (i in a4) PageSize.FIT else userSize }
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
