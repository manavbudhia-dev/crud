package com.papervault.scanner.ui.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.papervault.scanner.data.Document
import com.papervault.scanner.data.DocumentSource
import com.papervault.scanner.pdf.NumberPosition
import com.papervault.scanner.pdf.PageRanges
import com.papervault.scanner.pdf.PdfOps
import com.papervault.scanner.util.formatSize
import java.io.File

enum class PdfTool(val title: String) {
    MERGE("Merge PDFs"),
    SPLIT("Split PDF"),
    COMPRESS("Compress PDF"),
    PROTECT("Password protect"),
    PAGE_NUMBERS("Add page numbers"),
    WATERMARK("Add watermark"),
}

@Composable
fun PdfToolScreen(
    tool: PdfTool,
    docId: Long?,
    onBack: () -> Unit,
    onOpenDocument: (Long) -> Unit,
    vm: ToolViewModel = viewModel(key = "tool_${tool}_$docId", factory = ToolViewModel.Factory),
) {
    val status by vm.status.collectAsStateWithLifecycle()
    val library by vm.library.collectAsStateWithLifecycle()
    val doc = library.firstOrNull { it.id == docId }

    ToolScaffold(tool.title, status, onBack, onOpenDocument, vm::reset) {
        when (tool) {
            PdfTool.MERGE -> MergeContent(vm, library, docId)
            else -> {
                if (doc == null) {
                    Text("Document not found.")
                    return@ToolScaffold
                }
                DocumentHeader(doc)
                when (tool) {
                    PdfTool.SPLIT -> SplitContent(vm, doc)
                    PdfTool.COMPRESS -> CompressContent(vm, doc)
                    PdfTool.PROTECT -> ProtectContent(vm, doc)
                    PdfTool.PAGE_NUMBERS -> PageNumbersContent(vm, doc)
                    PdfTool.WATERMARK -> WatermarkContent(vm, doc)
                    PdfTool.MERGE -> Unit
                }
            }
        }
    }
}

// ---------------- Merge ----------------

@Composable
private fun MergeContent(vm: ToolViewModel, library: List<Document>, preselected: Long?) {
    val order = remember { mutableStateListOf<Long>().apply { preselected?.let { add(it) } } }
    var title by rememberSaveable { mutableStateOf("") }

    Text("Choose documents in the order they should appear.", style = MaterialTheme.typography.bodyMedium)
    library.forEach { d ->
        val pos = order.indexOf(d.id)
        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = pos >= 0, onCheckedChange = { if (it) order.add(d.id) else order.remove(d.id) })
                Column(Modifier.weight(1f)) {
                    Text(d.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${d.pageCount} pages", style = MaterialTheme.typography.bodySmall)
                }
                if (pos >= 0) {
                    Text("#${pos + 1}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    IconButton(onClick = { if (pos > 0) order.add(pos - 1, order.removeAt(pos)) }, enabled = pos > 0) {
                        Icon(Icons.Default.ArrowUpward, "Earlier")
                    }
                    IconButton(onClick = { if (pos < order.lastIndex) order.add(pos + 1, order.removeAt(pos)) }, enabled = pos < order.lastIndex) {
                        Icon(Icons.Default.ArrowDownward, "Later")
                    }
                }
            }
        }
    }
    OutlinedTextField(title, { title = it }, label = { Text("New document name (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Button(
        onClick = {
            val ids = order.toList()
            vm.run("Merging ${ids.size} documents…") {
                val docs = repo.getAll(ids)
                val out = repo.newWorkFile()
                PdfOps.merge(docs.map { File(it.filePath) }, out)
                val id = repo.addGenerated(out, DocumentSource.MERGE, title.ifBlank { repo.defaultTitle(DocumentSource.MERGE) })
                ToolStatus.Done("Merged ${docs.sumOf { it.pageCount }} pages", listOf(id))
            }
        },
        enabled = order.size >= 2,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(if (order.size < 2) "Select at least 2 documents" else "Merge ${order.size} documents") }
}

// ---------------- Split ----------------

private enum class SplitMode(val label: String) { EVERY("Every page as its own PDF"), RANGES("By page ranges"), CHUNKS("Every N pages") }

@Composable
private fun SplitContent(vm: ToolViewModel, doc: Document) {
    var mode by rememberSaveable { mutableStateOf(SplitMode.RANGES) }
    var ranges by rememberSaveable { mutableStateOf("1-${(doc.pageCount / 2).coerceAtLeast(1)}, ${(doc.pageCount / 2 + 1).coerceAtMost(doc.pageCount)}-${doc.pageCount}") }
    var chunk by rememberSaveable { mutableStateOf("2") }
    var error by remember { mutableStateOf<String?>(null) }

    OptionGroup("Split mode", SplitMode.entries, mode, { it.label }) { mode = it; error = null }
    when (mode) {
        SplitMode.RANGES -> OutlinedTextField(
            ranges, { ranges = it; error = null },
            label = { Text("Ranges, e.g. 1-3, 4, 5-${doc.pageCount}") },
            supportingText = { Text("Each comma-separated range becomes one PDF") },
            isError = error != null, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        SplitMode.CHUNKS -> OutlinedTextField(
            chunk, { chunk = it.filter(Char::isDigit); error = null },
            label = { Text("Pages per file") }, isError = error != null, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
        )
        SplitMode.EVERY -> Text("Creates ${doc.pageCount} documents.", style = MaterialTheme.typography.bodySmall)
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Button(
        onClick = {
            val groups = try {
                when (mode) {
                    SplitMode.EVERY -> PageRanges.everyPage(doc.pageCount)
                    SplitMode.RANGES -> PageRanges.parseGroups(ranges, doc.pageCount)
                    SplitMode.CHUNKS -> PageRanges.chunks(doc.pageCount, chunk.toIntOrNull() ?: 0)
                }
            } catch (e: IllegalArgumentException) {
                error = e.message; return@Button
            }
            vm.run("Splitting…") {
                val ids = groups.mapIndexed { i, g ->
                    progress("Creating part ${i + 1} of ${groups.size}", (i + 1f) / groups.size)
                    val out = repo.newWorkFile()
                    PdfOps.extract(File(doc.filePath), g, out)
                    val label = if (g.size == 1) "p${g[0] + 1}" else "p${g.first() + 1}-${g.last() + 1}"
                    repo.addGenerated(out, DocumentSource.SPLIT, "${doc.title} ($label)", doc.folder)
                }
                ToolStatus.Done("Split into ${ids.size} documents", ids)
            }
        },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Split") }
}

// ---------------- Compress ----------------

private enum class Level(val label: String, val dpi: Int, val quality: Float) {
    LOW("Light — best quality (150 dpi)", 150, 0.8f),
    MEDIUM("Balanced (120 dpi)", 120, 0.65f),
    HIGH("Strong — smallest file (96 dpi)", 96, 0.5f),
}

@Composable
private fun CompressContent(vm: ToolViewModel, doc: Document) {
    var level by rememberSaveable { mutableStateOf(Level.MEDIUM) }
    OptionGroup("Compression", Level.entries, level, { it.label }) { level = it }
    Text(
        "Pages are re-encoded as images. Ideal for scans and photos; selectable text in digital PDFs becomes part of the image.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Button(onClick = {
        vm.run("Compressing…") {
            val out = repo.newWorkFile()
            PdfOps.compress(File(doc.filePath), level.dpi, level.quality, out) { done, total ->
                progress("Compressing page $done of $total", done.toFloat() / total)
            }
            val newSize = out.length()
            val id = repo.addGenerated(out, DocumentSource.EDIT, "${doc.title} (compressed)", doc.folder)
            val saved = 100 - (newSize * 100 / doc.sizeBytes.coerceAtLeast(1))
            ToolStatus.Done("${formatSize(doc.sizeBytes)} → ${formatSize(newSize)} (${if (saved >= 0) "-$saved" else "+${-saved}"}%)", listOf(id))
        }
    }, modifier = Modifier.fillMaxWidth()) { Text("Compress") }
}

// ---------------- Protect ----------------

@Composable
private fun ProtectContent(vm: ToolViewModel, doc: Document) {
    var pw by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    Text(
        "Creates a password-protected copy (AES-256) to share. Your library copy stays unlocked.",
        style = MaterialTheme.typography.bodyMedium,
    )
    OutlinedTextField(pw, { pw = it }, label = { Text("Password") }, singleLine = true,
        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
    OutlinedTextField(pw2, { pw2 = it }, label = { Text("Confirm password") }, singleLine = true,
        isError = pw2.isNotEmpty() && pw != pw2,
        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
    Button(
        onClick = {
            vm.run("Encrypting…") {
                val out = File(exportDir(), "${safeName(doc.title)} (protected).pdf")
                PdfOps.protect(File(doc.filePath), pw, out)
                ToolStatus.Done("Protected copy ready", files = listOf(out))
            }
        },
        enabled = pw.length >= 4 && pw == pw2,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(if (pw.isNotEmpty() && pw.length < 4) "Use at least 4 characters" else "Create protected copy") }
}

// ---------------- Page numbers ----------------

@Composable
private fun PageNumbersContent(vm: ToolViewModel, doc: Document) {
    var position by rememberSaveable { mutableStateOf(NumberPosition.BOTTOM_CENTER) }
    val formats = listOf("{n}", "Page {n}", "{n} / {total}", "Page {n} of {total}")
    var format by rememberSaveable { mutableStateOf(formats[2]) }
    OptionGroup("Position", NumberPosition.entries, position, {
        when (it) {
            NumberPosition.BOTTOM_CENTER -> "Bottom centre"
            NumberPosition.BOTTOM_RIGHT -> "Bottom right"
            NumberPosition.TOP_RIGHT -> "Top right"
        }
    }) { position = it }
    OptionGroup("Format", formats, format, { it.replace("{n}", "3").replace("{total}", "${doc.pageCount}") }) { format = it }
    Button(onClick = {
        vm.run("Adding page numbers…") {
            val out = repo.newWorkFile()
            PdfOps.addPageNumbers(File(doc.filePath), position, format, out)
            val id = repo.addGenerated(out, DocumentSource.EDIT, "${doc.title} (numbered)", doc.folder)
            ToolStatus.Done("Page numbers added", listOf(id))
        }
    }, modifier = Modifier.fillMaxWidth()) { Text("Apply") }
}

// ---------------- Watermark ----------------

@Composable
private fun WatermarkContent(vm: ToolViewModel, doc: Document) {
    var text by rememberSaveable { mutableStateOf("CONFIDENTIAL") }
    var opacity by remember { mutableFloatStateOf(0.25f) }
    OutlinedTextField(text, { text = it }, label = { Text("Watermark text") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        supportingText = { Text("Latin characters only") })
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Opacity ${(opacity * 100).toInt()}%", style = MaterialTheme.typography.titleSmall)
        Slider(value = opacity, onValueChange = { opacity = it }, valueRange = 0.05f..0.8f)
    }
    Button(onClick = {
        vm.run("Adding watermark…") {
            val out = repo.newWorkFile()
            PdfOps.addWatermark(File(doc.filePath), text, opacity, out)
            val id = repo.addGenerated(out, DocumentSource.EDIT, "${doc.title} (watermarked)", doc.folder)
            ToolStatus.Done("Watermark added", listOf(id))
        }
    }, enabled = text.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Apply") }
}

fun ToolViewModel.exportDir(): File = File(app.cacheDir, "share").apply { mkdirs() }
fun safeName(s: String) = s.replace(Regex("[^A-Za-z0-9 ._()-]"), "_").ifBlank { "document" }
