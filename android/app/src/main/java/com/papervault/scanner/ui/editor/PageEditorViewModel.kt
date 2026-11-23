package com.papervault.scanner.ui.editor

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import com.papervault.scanner.data.Document
import com.papervault.scanner.data.DocumentRepository
import com.papervault.scanner.data.DocumentSource
import com.papervault.scanner.pdf.PageSpec
import com.papervault.scanner.pdf.PdfOps
import com.papervault.scanner.pdf.PdfUtils
import com.papervault.scanner.ui.container
import com.papervault.scanner.util.ShareUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicLong

data class EditPage(val key: Long, val spec: PageSpec)

data class EditorState(
    val doc: Document? = null,
    val pages: List<EditPage> = emptyList(),
    val selected: Set<Long> = emptySet(),
    val dirty: Boolean = false,
    val busy: Boolean = false,
)

sealed interface EditorEvent {
    data object Saved : EditorEvent
    data class Created(val id: Long) : EditorEvent
    data class Message(val text: String) : EditorEvent
}

class PageEditorViewModel(
    private val app: Application,
    private val repo: DocumentRepository,
    private val docId: Long,
) : ViewModel() {

    private val keys = AtomicLong()
    private val _state = MutableStateFlow(EditorState())
    val state: StateFlow<EditorState> = _state.asStateFlow()
    private val _events = Channel<EditorEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        viewModelScope.launch {
            val doc = repo.get(docId) ?: return@launch
            val file = File(doc.filePath)
            _state.value = EditorState(
                doc = doc,
                pages = (0 until doc.pageCount).map { EditPage(keys.incrementAndGet(), PageSpec(file, it)) },
            )
        }
    }

    fun toggle(key: Long) = _state.update {
        it.copy(selected = if (key in it.selected) it.selected - key else it.selected + key)
    }

    fun selectAll() = _state.update { it.copy(selected = it.pages.map { p -> p.key }.toSet()) }
    fun clearSelection() = _state.update { it.copy(selected = emptySet()) }

    fun rotate(delta: Int) = edit { s ->
        s.pages.map { if (it.key in s.selected) it.copy(spec = it.spec.copy(rotation = it.spec.rotation + delta)) else it }
    }

    fun deleteSelected() {
        if (_state.value.pages.size == _state.value.selected.size) {
            send(EditorEvent.Message("A document needs at least one page"))
            return
        }
        edit { s -> s.pages.filterNot { it.key in s.selected } }
        clearSelection()
    }

    fun duplicateSelected() = edit { s ->
        s.pages.flatMap { if (it.key in s.selected) listOf(it, it.copy(key = keys.incrementAndGet())) else listOf(it) }
    }

    /** Move every selected page one slot left (-1) or right (+1). */
    fun move(direction: Int) = edit { s ->
        val list = s.pages.toMutableList()
        val indices = list.indices.filter { list[it].key in s.selected }
        val ordered = if (direction < 0) indices else indices.reversed()
        for (i in ordered) {
            val j = i + direction
            if (j !in list.indices || list[j].key in s.selected) continue
            list[i] = list[j].also { list[j] = list[i] }
        }
        list
    }

    fun movePage(from: Int, to: Int) = edit { s ->
        s.pages.toMutableList().apply { add(to.coerceIn(0, size - 1), removeAt(from)) }
    }

    fun appendPdf(uri: Uri) = work {
        val tmp = repo.newWorkFile()
        app.contentResolver.openInputStream(uri)?.use { i -> tmp.outputStream().use { i.copyTo(it) } }
        appendFile(tmp)
    }

    fun appendImages(uris: List<Uri>) {
        if (uris.isEmpty()) return
        work {
            val tmp = repo.newWorkFile()
            PdfUtils.imagesToPdf(app, uris, tmp)
            appendFile(tmp)
        }
    }

    private fun appendFile(file: File) {
        val count = PdfUtils.pageCount(file)
        val added = (0 until count).map { EditPage(keys.incrementAndGet(), PageSpec(file, it)) }
        _state.update { it.copy(pages = it.pages + added, dirty = true) }
        send(EditorEvent.Message("Added $count page${if (count == 1) "" else "s"}"))
    }

    fun save() = work {
        val s = _state.value
        val doc = s.doc ?: return@work
        val out = repo.newWorkFile()
        PdfOps.assemble(s.pages.map { it.spec }, out)
        repo.replaceContent(doc, out)
        val fresh = repo.get(docId)!!
        val file = File(fresh.filePath)
        _state.update {
            it.copy(
                doc = fresh,
                pages = (0 until fresh.pageCount).map { i -> EditPage(keys.incrementAndGet(), PageSpec(file, i)) },
                selected = emptySet(),
                dirty = false,
            )
        }
        send(EditorEvent.Saved)
    }

    fun extractSelected() = work {
        val s = _state.value
        val specs = s.pages.filter { it.key in s.selected }.map { it.spec }
        if (specs.isEmpty()) return@work
        val out = repo.newWorkFile()
        PdfOps.assemble(specs, out)
        val id = repo.addGenerated(out, DocumentSource.SPLIT, "${s.doc?.title} (pages)", s.doc?.folder)
        send(EditorEvent.Created(id))
    }

    fun shareSelectedAsImages() = work {
        val s = _state.value
        val shareDir = File(app.cacheDir, "share").apply { mkdirs() }
        val files = s.pages.withIndex().filter { it.value.key in s.selected }.mapNotNull { (i, p) ->
            val bmp = PdfUtils.renderPage(p.spec.source, p.spec.pageIndex, 1600) ?: return@mapNotNull null
            val rotated = rotate(bmp, p.spec.rotation)
            File(shareDir, "${s.doc?.title?.replace(Regex("[^A-Za-z0-9 _-]"), "_")}_p${i + 1}.jpg").also { f ->
                f.outputStream().use { rotated.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                rotated.recycle()
            }
        }
        withContext(Dispatchers.Main) { ShareUtils.shareFiles(app, files, "image/jpeg") }
    }

    private fun rotate(bmp: Bitmap, degrees: Int): Bitmap {
        val d = ((degrees % 360) + 360) % 360
        if (d == 0) return bmp
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(d.toFloat()) }, true)
            .also { if (it != bmp) bmp.recycle() }
    }

    private fun edit(transform: (EditorState) -> List<EditPage>) =
        _state.update { it.copy(pages = transform(it), dirty = true) }

    private fun send(e: EditorEvent) = viewModelScope.launch { _events.send(e) }

    private fun work(block: suspend () -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            try {
                withContext(Dispatchers.IO) { block() }
            } catch (e: Exception) {
                _events.send(EditorEvent.Message(e.message ?: "Operation failed"))
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    companion object {
        fun factory(id: Long) = viewModelFactory {
            initializer { PageEditorViewModel(this[APPLICATION_KEY]!!, container.documents, id) }
        }
    }
}
