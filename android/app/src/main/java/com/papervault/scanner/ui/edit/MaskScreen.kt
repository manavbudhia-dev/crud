package com.papervault.scanner.ui.edit

import android.app.Application
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.papervault.scanner.data.Document
import com.papervault.scanner.data.DocumentRepository
import com.papervault.scanner.data.DocumentSource
import com.papervault.scanner.pdf.MaskRect
import com.papervault.scanner.pdf.MaskStyle
import com.papervault.scanner.pdf.PdfUtils
import com.papervault.scanner.pdf.Redactor
import com.papervault.scanner.ui.components.PdfPageImage
import com.papervault.scanner.ui.container
import com.papervault.scanner.ui.tools.safeName
import com.papervault.scanner.util.ShareUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MaskViewModel(private val app: Application, private val repo: DocumentRepository, docId: Long) : ViewModel() {
    val doc = MutableStateFlow<Document?>(null)
    val page = MutableStateFlow(0)
    val masks = MutableStateFlow<Map<Int, List<MaskRect>>>(emptyMap())
    val style = MutableStateFlow(MaskStyle.BLACK)
    val busy = MutableStateFlow(false)
    val events = Channel<String>(Channel.BUFFERED)
    val opened = Channel<Long>(Channel.BUFFERED)

    /** Page aspect ratios (w/h) for layout. */
    val aspects = MutableStateFlow<List<Float>>(emptyList())

    init {
        viewModelScope.launch {
            val d = repo.get(docId) ?: return@launch
            doc.value = d
            aspects.value = withContext(Dispatchers.IO) {
                val f = File(d.filePath)
                (0 until d.pageCount).map { PdfUtils.pageSizePoints(f, it).let { (w, h) -> w / h } }
            }
        }
    }

    fun add(r: MaskRect) {
        val n = r.normalized()
        if (!n.isUsable) return
        masks.update { it + (page.value to (it[page.value].orEmpty() + n)) }
    }

    fun undo() = masks.update { m ->
        val list = m[page.value].orEmpty()
        if (list.isEmpty()) m else m + (page.value to list.dropLast(1))
    }

    fun removeAt(x: Float, y: Float) = masks.update { m ->
        val list = m[page.value].orEmpty()
        val hit = list.lastOrNull { x in it.left..it.right && y in it.top..it.bottom } ?: return@update m
        m + (page.value to list - hit)
    }

    fun clearPage() = masks.update { it - page.value }

    val total get() = masks.value.values.sumOf { it.size }

    private suspend fun render(): File {
        val d = doc.value!!
        val out = File(File(app.cacheDir, "share").apply { mkdirs() }, "${safeName(d.title)} (masked).pdf")
        Redactor.redact(File(d.filePath), masks.value, out)
        return out
    }

    fun saveCopy() = work {
        val d = doc.value!!
        val f = render()
        val tmp = repo.newWorkFile().also { f.copyTo(it, overwrite = true) }
        val id = repo.addGenerated(tmp, DocumentSource.EDIT, "${d.title} (masked)", d.folder)
        opened.send(id)
    }

    fun share() = work {
        val f = render()
        withContext(Dispatchers.Main) { ShareUtils.shareFiles(app, listOf(f), "application/pdf") }
    }

    private fun work(block: suspend () -> Unit) {
        if (total == 0) { events.trySend("Draw at least one mask first"); return }
        viewModelScope.launch {
            busy.value = true
            try { withContext(Dispatchers.IO) { block() } } catch (e: Exception) {
                events.send(e.message ?: "Masking failed")
            } finally { busy.value = false }
        }
    }

    companion object {
        fun factory(id: Long) = viewModelFactory { initializer { MaskViewModel(this[APPLICATION_KEY]!!, container.documents, id) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MaskScreen(
    docId: Long,
    onBack: () -> Unit,
    onOpenDocument: (Long) -> Unit,
    vm: MaskViewModel = viewModel(key = "mask$docId", factory = MaskViewModel.factory(docId)),
) {
    val doc by vm.doc.collectAsStateWithLifecycle()
    val page by vm.page.collectAsStateWithLifecycle()
    val masks by vm.masks.collectAsStateWithLifecycle()
    val style by vm.style.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val aspects by vm.aspects.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var eraseMode by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.events.receiveAsFlow().collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(Unit) { vm.opened.receiveAsFlow().collect(onOpenDocument) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Mask content") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = vm::undo) { Icon(Icons.AutoMirrored.Filled.Undo, "Undo") }
                    IconButton(onClick = vm::share, enabled = !busy) { Icon(Icons.Default.Share, "Share masked copy") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val d = doc ?: return@Scaffold
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(
                "Drag over ID numbers, addresses or anything private. Masked pages are flattened so the hidden content is removed from the shared file, not just covered.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MaskStyle.entries.forEach { s ->
                    FilterChip(selected = !eraseMode && s == style, onClick = { eraseMode = false; vm.style.value = s }, label = { Text(s.label) })
                }
                FilterChip(selected = eraseMode, onClick = { eraseMode = !eraseMode }, label = { Text("Remove") },
                    leadingIcon = { Icon(Icons.Default.Delete, null) })
            }
            PageNavigator(page, d.pageCount, { vm.page.value = it }, trailing = masks[page]?.size?.let { " · $it masks" } ?: "")

            val aspect = aspects.getOrNull(page) ?: 0.707f
            var size by remember { mutableStateOf(IntSize.Zero) }
            var drag by remember { mutableStateOf<MaskRect?>(null) }
            Box(
                Modifier
                    .padding(horizontal = 12.dp)
                    .fillMaxWidth()
                    .aspectRatio(aspect)
                    .background(Color.White)
                    .onSizeChanged { size = it },
            ) {
                PdfPageImage(d.filePath, page, 1400, Modifier.matchParentSize())
                Canvas(
                    Modifier
                        .matchParentSize()
                        .pointerInput(page, eraseMode, style) {
                            if (eraseMode) {
                                detectTapGestures { o -> val (x, y) = norm(o.x, o.y, size); vm.removeAt(x, y) }
                            } else {
                                detectDragGestures(
                                    onDragStart = { o ->
                                        val (x, y) = norm(o.x, o.y, size)
                                        drag = MaskRect(x, y, x, y, style)
                                    },
                                    onDrag = { change, _ ->
                                        val (x, y) = norm(change.position.x, change.position.y, size)
                                        drag = drag?.copy(right = x, bottom = y)
                                    },
                                    onDragEnd = { drag?.let(vm::add); drag = null },
                                    onDragCancel = { drag = null },
                                )
                            }
                        },
                ) {
                    (masks[page].orEmpty() + listOfNotNull(drag?.normalized())).forEach { r ->
                        val tl = Offset(r.left * this.size.width, r.top * this.size.height)
                        val sz = Size((r.right - r.left) * this.size.width, (r.bottom - r.top) * this.size.height)
                        when (r.style) {
                            MaskStyle.BLACK -> drawRect(Color.Black, tl, sz)
                            MaskStyle.WHITE -> {
                                drawRect(Color.White, tl, sz)
                                drawRect(Color.Gray, tl, sz, style = Stroke(1.dp.toPx()))
                            }
                            // Preview for pixelate/blur: translucent hatch; the real effect is applied on export.
                            MaskStyle.PIXELATE, MaskStyle.BLUR -> {
                                drawRect(Color(0xCC607D8B), tl, sz)
                                drawRect(Color.White, tl, sz, style = Stroke(1.dp.toPx()))
                            }
                        }
                    }
                }
            }
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = vm::clearPage, modifier = Modifier.weight(1f)) { Text("Clear page") }
                Button(onClick = vm::saveCopy, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Save masked copy") }
            }
            Text(
                "Your original document is not changed. Black is the safest style for sensitive numbers.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            )
        }
    }
}
