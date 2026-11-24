package com.papervault.scanner.ui.edit

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.papervault.scanner.data.Document
import com.papervault.scanner.data.DocumentRepository
import com.papervault.scanner.data.DocumentSource
import com.papervault.scanner.data.SettingsRepository
import com.papervault.scanner.pdf.FieldType
import com.papervault.scanner.pdf.FormField
import com.papervault.scanner.pdf.PdfForms
import com.papervault.scanner.ui.components.SoftCard
import com.papervault.scanner.ui.components.enterUp
import com.papervault.scanner.ui.container
import com.papervault.scanner.ui.theme.extra
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

class FormFillViewModel(private val repo: DocumentRepository, private val settings: SettingsRepository, docId: Long) : ViewModel() {
    val doc = MutableStateFlow<Document?>(null)
    val fields = MutableStateFlow<List<FormField>?>(null)
    val values = MutableStateFlow<Map<String, String>>(emptyMap())
    val flatten = MutableStateFlow(false)
    val busy = MutableStateFlow(false)
    private val _messages = Channel<String>(Channel.BUFFERED); val messages = _messages.receiveAsFlow()
    private val _saved = Channel<Pair<Long, Boolean>>(Channel.BUFFERED); val saved = _saved.receiveAsFlow()

    init {
        viewModelScope.launch {
            val d = repo.get(docId) ?: return@launch
            doc.value = d
            val f = withContext(Dispatchers.IO) { runCatching { PdfForms.read(File(d.filePath)) }.getOrDefault(emptyList()) }
            fields.value = f
            values.value = f.associate { it.name to it.value }
        }
    }

    fun set(name: String, v: String) = values.update { it + (name to v) }

    fun autofill() {
        val s = settings.settings.value
        if (s.profileName.isBlank() && s.profileEmail.isBlank()) {
            _messages.trySend("Add your name and email in Settings → Profile to use autofill"); return
        }
        val current = fields.value.orEmpty().map { it.copy(value = values.value[it.name].orEmpty()) }
        val suggested = PdfForms.autofill(current, s.profileName, s.profileEmail, DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date()))
        values.update { it + suggested }
        _messages.trySend(if (suggested.isEmpty()) "Nothing to autofill" else "Filled ${suggested.size} field${if (suggested.size == 1) "" else "s"}")
    }

    fun save(thenSign: Boolean) {
        val d = doc.value ?: return
        viewModelScope.launch {
            busy.value = true
            try {
                val out = repo.newWorkFile()
                val changed = values.value.filter { (k, v) -> fields.value?.firstOrNull { it.name == k }?.value != v }
                val r = withContext(Dispatchers.IO) { PdfForms.fill(File(d.filePath), changed, flatten.value, out) }
                val id = repo.addGenerated(out, DocumentSource.EDIT, "${d.title} (filled)", d.folder)
                r.warnings.firstOrNull()?.let { _messages.send(it) }
                _saved.send(id to thenSign)
            } catch (e: Exception) {
                _messages.send(e.message ?: "Could not save the form")
            } finally {
                busy.value = false
            }
        }
    }

    companion object {
        fun factory(id: Long) = viewModelFactory { initializer { FormFillViewModel(container.documents, container.settings, id) } }
    }
}

@Composable
fun FormFillScreen(
    docId: Long,
    onBack: () -> Unit,
    onSaved: (id: Long, sign: Boolean) -> Unit,
    onAnnotate: (Long) -> Unit,
    vm: FormFillViewModel = viewModel(key = "form$docId", factory = FormFillViewModel.factory(docId)),
) {
    val doc by vm.doc.collectAsStateWithLifecycle()
    val fields by vm.fields.collectAsStateWithLifecycle()
    val values by vm.values.collectAsStateWithLifecycle()
    val flatten by vm.flatten.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(Unit) { vm.saved.collect { (id, sign) -> onSaved(id, sign) } }

    Box(Modifier.fillMaxSize().background(MaterialTheme.extra.background)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                Text("Fill form", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (!fields.isNullOrEmpty()) TextButton(onClick = vm::autofill) {
                    Icon(Icons.Default.AutoAwesome, null); Text(" Autofill")
                }
            }
            val list = fields
            when {
                list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                list.isEmpty() -> Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No fillable fields", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "This PDF isn't an interactive form. You can still type on it and add your signature.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.extra.inkSoft,
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { doc?.let { onAnnotate(it.id) } }) { Text("Type & sign instead") }
                }
                else -> LazyColumn(
                    Modifier.weight(1f),
                    contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item {
                        Text("${list.size} fields in ${doc?.title.orEmpty()}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.extra.inkSoft)
                    }
                    itemsIndexed(list, key = { _, f -> f.name }) { i, f ->
                        SoftCard(Modifier.fillMaxWidth().enterUp((i * 30).coerceAtMost(300)), radius = 16.dp) {
                            Column(Modifier.padding(14.dp)) { FieldEditor(f, values[f.name].orEmpty()) { vm.set(f.name, it) } }
                        }
                    }
                }
            }
            AnimatedVisibility(!list.isNullOrEmpty()) {
                Column(Modifier.fillMaxWidth().background(MaterialTheme.extra.card).navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Lock answers", style = MaterialTheme.typography.titleSmall)
                            Text("Flatten so the answers can't be changed", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.extra.inkSoft)
                        }
                        Switch(checked = flatten, onCheckedChange = { vm.flatten.value = it })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = { vm.save(false) }, enabled = !busy, modifier = Modifier.weight(1f), shape = RoundedCornerShape(50)) { Text("Save copy") }
                        Button(onClick = { vm.save(true) }, enabled = !busy, modifier = Modifier.weight(1f), shape = RoundedCornerShape(50)) { Text("Save & sign") }
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 140.dp))
    }
}

@Composable
private fun FieldEditor(f: FormField, value: String, onChange: (String) -> Unit) {
    when (f.type) {
        FieldType.TEXT -> OutlinedTextField(
            value, onChange, label = { Text(f.label) }, enabled = !f.readOnly,
            singleLine = !f.multiline, minLines = if (f.multiline) 3 else 1, modifier = Modifier.fillMaxWidth(),
        )
        FieldType.CHECKBOX -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(f.label, Modifier.weight(1f))
            Switch(checked = value == PdfForms.CHECKED, enabled = !f.readOnly, onCheckedChange = { onChange(if (it) PdfForms.CHECKED else "") })
        }
        FieldType.CHOICE, FieldType.RADIO -> {
            var open by remember { mutableStateOf(false) }
            Text(f.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.extra.inkSoft)
            Box {
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = !f.readOnly) { open = true }.padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(value.ifBlank { "Choose…" }, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Icon(Icons.Default.ArrowDropDown, null)
                }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    f.options.forEach { o -> DropdownMenuItem(text = { Text(o) }, onClick = { onChange(o); open = false }) }
                }
            }
        }
    }
}
