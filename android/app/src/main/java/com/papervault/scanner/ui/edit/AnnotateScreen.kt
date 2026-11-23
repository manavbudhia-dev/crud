package com.papervault.scanner.ui.edit

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Highlight
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.AutoFixNormal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.papervault.scanner.data.Document
import com.papervault.scanner.data.DocumentRepository
import com.papervault.scanner.pdf.AnnItem
import com.papervault.scanner.pdf.AnnotationRenderer
import com.papervault.scanner.pdf.PdfUtils
import com.papervault.scanner.ui.components.PdfPageImage
import com.papervault.scanner.ui.container
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class AnnTool { PEN, HIGHLIGHT, TEXT, SIGN, MOVE, ERASE }

class AnnotateViewModel(private val repo: DocumentRepository, docId: Long, startPage: Int) : ViewModel() {
    val doc = MutableStateFlow<Document?>(null)
    val page = MutableStateFlow(startPage)
    val items = MutableStateFlow<Map<Int, List<AnnItem>>>(emptyMap())
    val aspects = MutableStateFlow<List<Float>>(emptyList())
    val busy = MutableStateFlow(false)
    val messages = Channel<String>(Channel.BUFFERED)
    val saved = Channel<Unit>(Channel.BUFFERED)

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

    val dirty get() = items.value.values.any { it.isNotEmpty() }
    fun current() = items.value[page.value].orEmpty()

    fun add(item: AnnItem) = items.update { it + (page.value to (it[page.value].orEmpty() + item)) }
    fun replace(index: Int, item: AnnItem) = items.update {
        val l = it[page.value].orEmpty().toMutableList()
        if (index in l.indices) l[index] = item
        it + (page.value to l)
    }

    fun undo() = items.update { m -> m + (page.value to m[page.value].orEmpty().dropLast(1)) }

    fun hit(x: Float, y: Float): Int {
        val aspect = aspects.value.getOrNull(page.value) ?: 0.707f
        return current().indexOfLast { AnnotationRenderer.bounds(it, aspect).contains(x, y) }
    }

    fun eraseAt(x: Float, y: Float) {
        val i = hit(x, y)
        if (i >= 0) items.update { it + (page.value to it[page.value].orEmpty().filterIndexed { j, _ -> j != i }) }
    }

    fun save() {
        val d = doc.value ?: return
        viewModelScope.launch {
            busy.value = true
            try {
                withContext(Dispatchers.IO) {
                    val out = repo.newWorkFile()
                    AnnotationRenderer.export(File(d.filePath), items.value, out)
                    repo.replaceContent(d, out)
                }
                items.value = emptyMap()
                saved.send(Unit)
            } catch (e: Exception) {
                messages.send(e.message ?: "Could not save")
            } finally {
                busy.value = false
            }
        }
    }

    companion object {
        fun factory(id: Long, page: Int) = viewModelFactory { initializer { AnnotateViewModel(container.documents, id, page) } }
    }
}

private val palette = listOf(Color(0xFF111111), Color(0xFF1A47C8), Color(0xFFD32F2F), Color(0xFF2E7D32), Color(0xFFFFD600))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnnotateScreen(
    docId: Long,
    startPage: Int,
    onBack: () -> Unit,
    vm: AnnotateViewModel = viewModel(key = "ann$docId", factory = AnnotateViewModel.factory(docId, startPage)),
) {
    val doc by vm.doc.collectAsStateWithLifecycle()
    val page by vm.page.collectAsStateWithLifecycle()
    val items by vm.items.collectAsStateWithLifecycle()
    val aspects by vm.aspects.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    var tool by remember { mutableStateOf(AnnTool.PEN) }
    var color by remember { mutableStateOf(palette[1]) }
    var width by remember { mutableFloatStateOf(0.006f) }
    var textSize by remember { mutableFloatStateOf(0.03f) }
    var pendingText by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    var pendingSign by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    var confirmExit by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.messages.receiveAsFlow().collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(Unit) { vm.saved.receiveAsFlow().collect { onBack() } }

    val exit = { if (vm.dirty) confirmExit = true else onBack() }
    BackHandler(onBack = exit)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Annotate & sign") },
                navigationIcon = { IconButton(onClick = exit) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = vm::undo) { Icon(Icons.AutoMirrored.Filled.Undo, "Undo") }
                    TextButton(onClick = vm::save, enabled = !busy && items.values.any { it.isNotEmpty() }) { Text("Save") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val d = doc ?: return@Scaffold
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ToolChip("Pen", Icons.Default.Brush, tool == AnnTool.PEN) { tool = AnnTool.PEN; if (color == palette[4]) color = palette[1] }
                ToolChip("Highlight", Icons.Default.Highlight, tool == AnnTool.HIGHLIGHT) { tool = AnnTool.HIGHLIGHT; color = palette[4] }
                ToolChip("Text", Icons.Default.TextFields, tool == AnnTool.TEXT) { tool = AnnTool.TEXT }
                ToolChip("Sign", Icons.Default.Draw, tool == AnnTool.SIGN) { tool = AnnTool.SIGN }
                ToolChip("Move", Icons.Default.OpenWith, tool == AnnTool.MOVE) { tool = AnnTool.MOVE }
                ToolChip("Erase", Icons.Default.AutoFixNormal, tool == AnnTool.ERASE) { tool = AnnTool.ERASE }
            }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                palette.forEach { c ->
                    Box(
                        Modifier.size(28.dp).background(c, CircleShape)
                            .border(if (c == color) 3.dp else 1.dp, if (c == color) MaterialTheme.colorScheme.primary else Color.LightGray, CircleShape)
                            .clickable { color = c },
                    )
                }
            }
            when (tool) {
                AnnTool.PEN, AnnTool.HIGHLIGHT -> LabeledSlider("Thickness", width, 0.002f..0.03f) { width = it }
                AnnTool.TEXT -> LabeledSlider("Text size", textSize, 0.015f..0.08f) { textSize = it }
                AnnTool.SIGN -> HelpText("Tap where the signature should go. Use Move to drag it after placing.")
                AnnTool.MOVE -> HelpText("Drag a text box or signature to move it.")
                AnnTool.ERASE -> HelpText("Tap an annotation to remove it.")
            }
            PageNavigator(page, d.pageCount, { vm.page.value = it })

            val aspect = aspects.getOrNull(page) ?: 0.707f
            var size by remember { mutableStateOf(IntSize.Zero) }
            var stroke by remember { mutableStateOf<List<Pair<Float, Float>>>(emptyList()) }
            var moving by remember { mutableStateOf(-1) }
            val pageItems = items[page].orEmpty()
            Box(
                Modifier.padding(horizontal = 12.dp).fillMaxWidth().aspectRatio(aspect).background(Color.White).onSizeChanged { size = it },
            ) {
                PdfPageImage(d.filePath, page, 1400, Modifier.matchParentSize())
                Canvas(
                    Modifier.matchParentSize().pointerInput(page, tool, color, width, textSize) {
                        when (tool) {
                            AnnTool.PEN, AnnTool.HIGHLIGHT -> detectDragGestures(
                                onDragStart = { stroke = listOf(norm(it.x, it.y, size)) },
                                onDrag = { ch, _ -> stroke = stroke + norm(ch.position.x, ch.position.y, size) },
                                onDragEnd = {
                                    vm.add(AnnItem.Stroke(stroke, color.toArgb(), if (tool == AnnTool.HIGHLIGHT) width * 3 else width, tool == AnnTool.HIGHLIGHT))
                                    stroke = emptyList()
                                },
                                onDragCancel = { stroke = emptyList() },
                            )
                            AnnTool.TEXT -> detectTapGestures { pendingText = norm(it.x, it.y, size) }
                            AnnTool.SIGN -> detectTapGestures { pendingSign = norm(it.x, it.y, size) }
                            AnnTool.ERASE -> detectTapGestures { val (x, y) = norm(it.x, it.y, size); vm.eraseAt(x, y) }
                            AnnTool.MOVE -> detectDragGestures(
                                onDragStart = { val (x, y) = norm(it.x, it.y, size); moving = vm.hit(x, y) },
                                onDrag = { ch, delta ->
                                    val i = moving
                                    val item = vm.current().getOrNull(i)
                                    if (item != null) {
                                        ch.consume()
                                        val dx = delta.x / size.width
                                        val dy = delta.y / size.height
                                        vm.replace(i, when (item) {
                                            is AnnItem.Text -> item.copy(x = item.x + dx, y = item.y + dy)
                                            is AnnItem.Stamp -> item.copy(x = item.x + dx, y = item.y + dy)
                                            is AnnItem.Stroke -> item.copy(points = item.points.map { (px, py) -> px + dx to py + dy })
                                        })
                                    }
                                },
                                onDragEnd = { moving = -1 },
                            )
                        }
                    },
                ) {
                    val live = if (stroke.isEmpty()) emptyList() else listOf(
                        AnnItem.Stroke(stroke, color.toArgb(), if (tool == AnnTool.HIGHLIGHT) width * 3 else width, tool == AnnTool.HIGHLIGHT)
                    )
                    drawIntoCanvas { AnnotationRenderer.draw(it.nativeCanvas, pageItems + live, this.size.width, this.size.height) }
                }
            }
            HelpText("Annotations are drawn on top of the page; existing text stays selectable. Save applies them to this document.")
        }
    }

    pendingText?.let { (x, y) ->
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { pendingText = null },
            title = { Text("Add text") },
            text = { OutlinedTextField(text, { text = it }, minLines = 2) },
            confirmButton = {
                TextButton(onClick = {
                    if (text.isNotBlank()) vm.add(AnnItem.Text(x, y, text, color.toArgb(), textSize))
                    pendingText = null
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { pendingText = null }) { Text("Cancel") } },
        )
    }
    pendingSign?.let { (x, y) ->
        SignatureDialog(onDismiss = { pendingSign = null }) { file ->
            val bmp = AnnotationRenderer.loadStamp(file.path)
            if (bmp != null) {
                val w = 0.3f
                vm.add(AnnItem.Stamp((x - w / 2).coerceIn(0f, 1f - w), y, file.path, w, bmp.width.toFloat() / bmp.height))
            }
            pendingSign = null
        }
    }
    if (confirmExit) {
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text("Discard annotations?") },
            confirmButton = { TextButton(onClick = { confirmExit = false; onBack() }) { Text("Discard") } },
            dismissButton = { TextButton(onClick = { confirmExit = false; vm.save() }) { Text("Save") } },
        )
    }
}

@Composable
private fun ToolChip(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, onClick: () -> Unit) =
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) }, leadingIcon = { Icon(icon, null, Modifier.size(18.dp)) })

@Composable
private fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Slider(value, onChange, valueRange = range, modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun HelpText(text: String) = Text(
    text,
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
)
