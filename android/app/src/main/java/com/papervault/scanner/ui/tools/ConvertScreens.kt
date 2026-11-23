package com.papervault.scanner.ui.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.papervault.scanner.PaperVaultApp
import com.papervault.scanner.convert.OutputFormat
import com.papervault.scanner.convert.WordMode
import com.papervault.scanner.data.DocumentSource
import com.papervault.scanner.util.ShareUtils
import java.io.File

/** PDF → Word / images / text / Excel / PowerPoint. */
@Composable
fun ConvertScreen(
    docId: Long,
    initial: OutputFormat?,
    onBack: () -> Unit,
    onOpenDocument: (Long) -> Unit,
    vm: ToolViewModel = viewModel(key = "convert$docId", factory = ToolViewModel.Factory),
) {
    val status by vm.status.collectAsStateWithLifecycle()
    val library by vm.library.collectAsStateWithLifecycle()
    val doc = library.firstOrNull { it.id == docId }
    var format by rememberSaveable { mutableStateOf(initial ?: OutputFormat.WORD) }
    var ocr by rememberSaveable { mutableStateOf(true) }
    var wordMode by rememberSaveable { mutableStateOf(WordMode.TEXT) }

    ToolScaffold("Convert PDF", status, onBack, onOpenDocument, vm::reset) {
        if (doc == null) { Text("Document not found."); return@ToolScaffold }
        DocumentHeader(doc)
        OptionGroup("Convert to", OutputFormat.entries, format, { it.label }) { format = it }
        if (format == OutputFormat.WORD) {
            OptionGroup("Word layout", WordMode.entries, wordMode, { it.label }) { wordMode = it }
        }
        val usesText = format == OutputFormat.TEXT || format == OutputFormat.EXCEL ||
            (format == OutputFormat.WORD && wordMode == WordMode.TEXT)
        if (usesText) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Recognise text in scanned pages (OCR)", Modifier.weight(1f))
                Switch(checked = ocr, onCheckedChange = { ocr = it })
            }
            Text(
                "Text is extracted on your device. Complex layouts (columns, tables) come out as plain lines; Excel splits columns on wide gaps.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Button(onClick = {
            val converter = (vm.app as PaperVaultApp).container.converter
            vm.run("Converting…") {
                val files = converter.convert(
                    File(doc.filePath), format, exportDir(), safeName(doc.title), ocr, wordMode,
                ) { msg, f -> progress(msg, f) }
                if (files.isEmpty()) throw IllegalStateException("Nothing was produced")
                ToolStatus.Done("Converted to ${format.label}", files = files, mime = format.mime)
            }
        }, modifier = Modifier.fillMaxWidth()) { Text("Convert") }
    }
}

enum class ImportKind(val title: String) {
    IMAGE_TO_TEXT("Image to text (OCR)"),
    WORD_TO_PDF("Word to PDF"),
    TEXT_TO_PDF("Text to PDF"),
}

/** Conversions whose input comes from outside the library. */
@Composable
fun ImportConvertScreen(
    kind: ImportKind,
    onBack: () -> Unit,
    onOpenDocument: (Long) -> Unit,
    vm: ToolViewModel = viewModel(key = "import$kind", factory = ToolViewModel.Factory),
) {
    val context = LocalContext.current
    val status by vm.status.collectAsStateWithLifecycle()
    val converter = (vm.app as PaperVaultApp).container.converter

    val handle: (Uri) -> Unit = { uri ->
        val name = displayName(context, uri)?.substringBeforeLast('.') ?: "Document"
        when (kind) {
            ImportKind.IMAGE_TO_TEXT -> vm.run("Recognising text…") {
                val text = converter.ocrUri(uri)
                val f = File(exportDir(), "${safeName(name)}.txt").apply { writeText(text) }
                ToolStatus.Done(
                    if (text.isBlank()) "No text found" else "Text recognised",
                    files = listOf(f), mime = "text/plain", text = text.ifBlank { null },
                )
            }
            ImportKind.WORD_TO_PDF -> vm.run("Converting…") {
                val out = repo.newWorkFile()
                converter.docxToPdf(uri, out)
                ToolStatus.Done("Converted to PDF", listOf(repo.addGenerated(out, DocumentSource.CONVERT, name)))
            }
            ImportKind.TEXT_TO_PDF -> vm.run("Converting…") {
                val text = context.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
                    ?: throw IllegalArgumentException("Cannot open file")
                val out = repo.newWorkFile()
                converter.textToPdf(text, out)
                ToolStatus.Done("Converted to PDF", listOf(repo.addGenerated(out, DocumentSource.CONVERT, name)))
            }
        }
    }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { it?.let(handle) }
    val pickDoc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(handle) }

    ToolScaffold(kind.title, status, onBack, onOpenDocument, vm::reset) {
        Text(
            when (kind) {
                ImportKind.IMAGE_TO_TEXT -> "Pick a photo or screenshot. Text is recognised on your device (Latin-script languages)."
                ImportKind.WORD_TO_PDF -> "Pick a .docx file. Text, headings and line breaks are kept; images and complex formatting are not."
                ImportKind.TEXT_TO_PDF -> "Pick a .txt file to turn into an A4 PDF."
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(onClick = {
            when (kind) {
                ImportKind.IMAGE_TO_TEXT -> pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                ImportKind.WORD_TO_PDF -> pickDoc.launch(arrayOf("application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                ImportKind.TEXT_TO_PDF -> pickDoc.launch(arrayOf("text/plain"))
            }
        }, modifier = Modifier.fillMaxWidth()) { Text("Choose file") }
    }
}

fun displayName(context: Context, uri: Uri): String? =
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
    }
