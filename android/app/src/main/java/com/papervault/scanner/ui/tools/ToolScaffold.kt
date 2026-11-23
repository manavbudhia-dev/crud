package com.papervault.scanner.ui.tools

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.papervault.scanner.data.Document
import com.papervault.scanner.util.ShareUtils
import com.papervault.scanner.util.formatSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolScaffold(
    title: String,
    status: ToolStatus,
    onBack: () -> Unit,
    onOpenDocument: (Long) -> Unit,
    onReset: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (status) {
                is ToolStatus.Idle -> content()
                is ToolStatus.Running -> RunningCard(status)
                is ToolStatus.Done -> DoneCard(status, onOpenDocument, onReset)
                is ToolStatus.Failed -> {
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.ErrorOutline, null, tint = MaterialTheme.colorScheme.error)
                            Text(status.message, Modifier.padding(start = 12.dp).weight(1f))
                        }
                    }
                    OutlinedButton(onClick = onReset) { Text("Try again") }
                }
            }
        }
    }
}

@Composable
private fun RunningCard(s: ToolStatus.Running) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (s.progress == null) CircularProgressIndicator()
            else LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.size(12.dp))
            Text(s.message)
        }
    }
}

@Composable
private fun DoneCard(s: ToolStatus.Done, onOpenDocument: (Long) -> Unit, onReset: () -> Unit) {
    val context = LocalContext.current
    val saver = rememberSaveFilesLauncher()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                Text(s.message, Modifier.padding(start = 12.dp), style = MaterialTheme.typography.titleSmall)
            }
            s.text?.let { text ->
                Card(Modifier.fillMaxWidth()) {
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Text(text, Modifier.padding(12.dp).heightIn(max = 360.dp).verticalScroll(rememberScrollState()))
                    }
                }
                OutlinedButton(onClick = {
                    val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("Recognised text", text))
                }, Modifier.fillMaxWidth()) { Text("Copy text") }
            }
            s.files.forEach { Text("${it.name} · ${formatSize(it.length())}", style = MaterialTheme.typography.bodySmall) }
            if (s.docIds.size == 1) {
                Button(onClick = { onOpenDocument(s.docIds.first()) }, Modifier.fillMaxWidth()) { Text("Open document") }
            } else if (s.docIds.size > 1) {
                Text("${s.docIds.size} documents added to your library.")
            }
            if (s.files.isNotEmpty()) {
                Button(onClick = { ShareUtils.shareFiles(context, s.files, s.mime) }, Modifier.fillMaxWidth()) { Text("Share") }
                OutlinedButton(onClick = { saver(s.files, s.mime) }, Modifier.fillMaxWidth()) {
                    Text(if (s.files.size == 1) "Save to device" else "Save to device (.zip)")
                }
            }
            TextButton(onClick = onReset) { Text("Done") }
        }
    }
}

/**
 * Save exported files to a user-chosen location via the system file picker.
 * Several files are bundled into one zip.
 */
@Composable
fun rememberSaveFilesLauncher(): (List<File>, String) -> Unit {
    val context = LocalContext.current
    val pending = remember { arrayOfNulls<List<File>>(1) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri: Uri? ->
        val files = pending[0] ?: return@rememberLauncherForActivityResult
        if (uri != null) CoroutineScope(Dispatchers.IO).launch { writeTo(context, uri, files) }
        pending[0] = null
    }
    return { files, _ ->
        pending[0] = files
        launcher.launch(if (files.size == 1) files.first().name else "export.zip")
    }
}

private fun writeTo(context: Context, uri: Uri, files: List<File>) {
    context.contentResolver.openOutputStream(uri)?.use { out ->
        if (files.size == 1) files.first().inputStream().use { it.copyTo(out) }
        else java.util.zip.ZipOutputStream(out).use { zip ->
            files.forEach { f ->
                zip.putNextEntry(java.util.zip.ZipEntry(f.name))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }
}

@Composable
fun <T> OptionGroup(label: String, options: List<T>, selected: T, text: (T) -> String, onSelect: (T) -> Unit) {
    Text(label, style = MaterialTheme.typography.titleSmall)
    Column {
        options.forEach { opt ->
            Row(
                Modifier.fillMaxWidth().selectable(opt == selected, onClick = { onSelect(opt) }).padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = opt == selected, onClick = null)
                Text(text(opt), Modifier.padding(start = 8.dp))
            }
        }
    }
}

@Composable
fun DocumentHeader(doc: Document?) {
    if (doc == null) return
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(doc.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${doc.pageCount} pages · ${formatSize(doc.sizeBytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Pick one library document (used when a tool is started from the home screen). */
@Composable
fun DocumentPickerDialog(title: String, docs: List<Document>, onDismiss: () -> Unit, onPick: (Document) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            if (docs.isEmpty()) Text("Your library is empty. Scan or import a PDF first.")
            else LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(docs, key = { it.id }) { d ->
                    Column(Modifier.fillMaxWidth().clickable { onPick(d) }.padding(vertical = 10.dp)) {
                        Text(d.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${d.pageCount} pages · ${formatSize(d.sizeBytes)}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
