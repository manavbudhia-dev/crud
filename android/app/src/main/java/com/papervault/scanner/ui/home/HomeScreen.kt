package com.papervault.scanner.ui.home

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MergeType
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.papervault.scanner.convert.OutputFormat
import com.papervault.scanner.data.Document
import com.papervault.scanner.data.SortOrder
import com.papervault.scanner.ui.Routes
import com.papervault.scanner.ui.components.ConfirmDeleteDialog
import com.papervault.scanner.ui.components.PasswordDialog
import com.papervault.scanner.ui.components.PdfPageImage
import com.papervault.scanner.ui.components.RenameDialog
import com.papervault.scanner.ui.tools.DocumentPickerDialog
import com.papervault.scanner.ui.tools.PdfTool
import com.papervault.scanner.ui.tools.displayName
import com.papervault.scanner.util.ShareUtils
import com.papervault.scanner.util.formatDate
import com.papervault.scanner.util.formatSize
import kotlinx.coroutines.launch

private data class Tool(val label: String, val icon: ImageVector, val action: () -> Unit)

/** Tools that operate on one existing document: pick it first, then open the tool. */
private data class DocTool(val title: String, val route: (Long) -> String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenDocument: (Long) -> Unit,
    onNavigate: (String) -> Unit,
    vm: HomeViewModel = viewModel(factory = HomeViewModel.Factory),
) {
    val context = LocalContext.current
    val docs by vm.documents.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val folders by vm.folders.collectAsStateWithLifecycle()
    val sort by vm.sort.collectAsStateWithLifecycle()
    val locked by vm.locked.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var searching by rememberSaveable { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Document?>(null) }
    var deleting by remember { mutableStateOf<Document?>(null) }
    var foldering by remember { mutableStateOf<Document?>(null) }
    var docTool by remember { mutableStateOf<DocTool?>(null) }

    LaunchedEffect(Unit) {
        vm.events.collect { e ->
            when (e) {
                is HomeEvent.Opened -> onOpenDocument(e.id)
                is HomeEvent.Error -> snackbar.showSnackbar(e.message)
            }
        }
    }

    val scan = rememberDocumentScanner(onResult = vm::onScanned, onError = { scope.launch { snackbar.showSnackbar(it) } })
    val pickPhotos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(100)) { vm.onPhotosPicked(it) }
    val pickPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { vm.onPdfPicked(it, displayName(context, it)) }
    }

    val tools = listOf(
        Tool("Scan", Icons.Default.DocumentScanner, scan),
        Tool("Photo to PDF", Icons.Default.Image) {
            pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        },
        Tool("Import PDF", Icons.Default.FileOpen) { pickPdf.launch(arrayOf("application/pdf")) },
        Tool("Docs Vault", Icons.Default.Lock) { onNavigate(Routes.vault()) },
        Tool("PDF to Word", Icons.Default.Description) { docTool = DocTool("Convert which PDF?") { Routes.convert(it, OutputFormat.WORD) } },
        Tool("Mask", Icons.Default.VisibilityOff) { docTool = DocTool("Mask which PDF?") { Routes.mask(it) } },
        Tool("Sign", Icons.Default.Draw) { docTool = DocTool("Sign which PDF?") { Routes.annotate(it) } },
        Tool("Merge", Icons.Default.MergeType) { onNavigate(Routes.tool(PdfTool.MERGE)) },
        Tool("Split", Icons.AutoMirrored.Filled.CallSplit) { docTool = DocTool("Split which PDF?") { Routes.tool(PdfTool.SPLIT, it) } },
        Tool("All tools", Icons.Default.Apps) { onNavigate(Routes.ALL_TOOLS) },
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (searching) {
                        TextField(
                            value = query,
                            onValueChange = vm::setQuery,
                            placeholder = { Text("Search documents") },
                            singleLine = true,
                            colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Text("PaperVault", fontWeight = FontWeight.SemiBold)
                    }
                },
                actions = {
                    IconButton(onClick = { if (searching) vm.setQuery(""); searching = !searching }) {
                        Icon(if (searching) Icons.Default.Close else Icons.Default.Search, "Search")
                    }
                    Box {
                        IconButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Filled.Sort, "Sort") }
                        DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                            SortOrder.entries.forEach { s ->
                                DropdownMenuItem(
                                    text = { Text(s.label, fontWeight = if (s == sort) FontWeight.Bold else FontWeight.Normal) },
                                    onClick = { sortMenu = false; vm.setSort(s) },
                                )
                            }
                        }
                    }
                    IconButton(onClick = { onNavigate(Routes.SETTINGS) }) { Icon(Icons.Default.Settings, "Settings") }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = scan, icon = { Icon(Icons.Default.DocumentScanner, null) }, text = { Text("Scan") })
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())

            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(tools) { ToolButton(it) }
            }

            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { FilterChip(selected = filter == LibraryFilter.All, onClick = { vm.setFilter(LibraryFilter.All) }, label = { Text("All") }) }
                item {
                    FilterChip(
                        selected = filter == LibraryFilter.Starred, onClick = { vm.setFilter(LibraryFilter.Starred) },
                        label = { Text("Starred") }, leadingIcon = { Icon(Icons.Default.Star, null, Modifier.size(16.dp)) },
                    )
                }
                items(folders) { f ->
                    FilterChip(
                        selected = filter == LibraryFilter.Folder(f), onClick = { vm.setFilter(LibraryFilter.Folder(f)) },
                        label = { Text(f) }, leadingIcon = { Icon(Icons.Default.Folder, null, Modifier.size(16.dp)) },
                    )
                }
            }

            val list = docs
            when {
                list == null -> Unit
                list.isEmpty() -> EmptyState(filtered = query.isNotEmpty() || filter != LibraryFilter.All)
                else -> LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(list, key = { it.id }) { doc ->
                        DocumentRow(
                            doc = doc,
                            onClick = { onOpenDocument(doc.id) },
                            onStar = { vm.toggleStar(doc) },
                            onShare = { ShareUtils.shareDocument(context, doc) },
                            onRename = { renaming = doc },
                            onFolder = { foldering = doc },
                            onVault = { onNavigate(Routes.vault(moveDocId = doc.id)) },
                            onDelete = { deleting = doc },
                        )
                    }
                }
            }
        }
    }

    docTool?.let { t ->
        DocumentPickerDialog(t.title, docs.orEmpty(), onDismiss = { docTool = null }) { docTool = null; onNavigate(t.route(it.id)) }
    }
    locked?.let { l ->
        PasswordDialog(title = "\"${l.title}\" is password-protected", onDismiss = vm::cancelUnlock, onConfirm = vm::unlockImport)
    }
    renaming?.let { doc -> RenameDialog(doc.title, onDismiss = { renaming = null }) { vm.rename(doc, it); renaming = null } }
    deleting?.let { doc -> ConfirmDeleteDialog(doc.title, onDismiss = { deleting = null }) { vm.delete(doc); deleting = null } }
    foldering?.let { doc ->
        FolderDialog(current = doc.folder, existing = folders, onDismiss = { foldering = null }) {
            vm.moveToFolder(doc, it); foldering = null
        }
    }
}

@Composable
private fun ToolButton(tool: Tool) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(76.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = tool.action).padding(vertical = 6.dp),
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(48.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(tool.icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer) }
        }
        Spacer(Modifier.height(6.dp))
        Text(tool.label, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, maxLines = 2)
    }
}

@Composable
private fun DocumentRow(
    doc: Document,
    onClick: () -> Unit,
    onStar: () -> Unit,
    onShare: () -> Unit,
    onRename: () -> Unit,
    onFolder: () -> Unit,
    onVault: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            PdfPageImage(
                path = doc.filePath, page = 0, renderWidth = 180, contentScale = ContentScale.Crop,
                modifier = Modifier.size(width = 56.dp, height = 72.dp).clip(RoundedCornerShape(6.dp)),
            )
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(doc.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Text(
                    "${doc.pageCount} page${if (doc.pageCount == 1) "" else "s"} · ${formatSize(doc.sizeBytes)}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    listOfNotNull(doc.folder, formatDate(doc.updatedAt)).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onStar) {
                Icon(
                    if (doc.starred) Icons.Default.Star else Icons.Default.StarBorder,
                    if (doc.starred) "Unstar" else "Star",
                    tint = if (doc.starred) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Share") }, leadingIcon = { Icon(Icons.Default.Share, null) }, onClick = { menu = false; onShare() })
                    DropdownMenuItem(text = { Text("Rename") }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = { menu = false; onRename() })
                    DropdownMenuItem(text = { Text("Move to folder") }, leadingIcon = { Icon(Icons.Default.Folder, null) }, onClick = { menu = false; onFolder() })
                    DropdownMenuItem(text = { Text("Move to Docs Vault") }, leadingIcon = { Icon(Icons.Default.Lock, null) }, onClick = { menu = false; onVault() })
                    DropdownMenuItem(text = { Text("Delete") }, leadingIcon = { Icon(Icons.Default.Delete, null) }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

@Composable
private fun FolderDialog(current: String?, existing: List<String>, onDismiss: () -> Unit, onConfirm: (String?) -> Unit) {
    var name by rememberSaveable { mutableStateOf(current.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move to folder") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Folder name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (existing.isNotEmpty()) {
                    LazyColumn(Modifier.heightIn(max = 220.dp)) {
                        items(existing) { f ->
                            Row(
                                Modifier.fillMaxWidth().clickable { name = f }.padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.primary)
                                Text(f, Modifier.padding(start = 10.dp))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(name.ifBlank { null }) }) { Text(if (name.isBlank()) "Remove from folder" else "Move") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun EmptyState(filtered: Boolean) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.DocumentScanner, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(12.dp))
            Text(if (filtered) "No matching documents" else "No documents yet", style = MaterialTheme.typography.titleMedium)
            if (!filtered) {
                Text(
                    "Tap Scan to capture a document, or turn photos into a PDF.",
                    style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
