package com.papervault.scanner.ui.home

import android.net.Uri
import android.provider.OpenableColumns
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MergeType
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import com.papervault.scanner.data.Document
import com.papervault.scanner.ui.Routes
import com.papervault.scanner.convert.OutputFormat
import com.papervault.scanner.ui.components.ConfirmDeleteDialog
import com.papervault.scanner.ui.components.PasswordDialog
import com.papervault.scanner.ui.tools.DocumentPickerDialog
import com.papervault.scanner.ui.tools.PdfTool
import com.papervault.scanner.ui.components.PdfPageImage
import com.papervault.scanner.ui.components.RenameDialog
import com.papervault.scanner.util.ShareUtils
import com.papervault.scanner.util.formatDate
import com.papervault.scanner.util.formatSize
import kotlinx.coroutines.launch

private data class Tool(val label: String, val icon: ImageVector, val phase: Int? = null, val action: (() -> Unit)? = null)

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
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var searching by rememberSaveable { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Document?>(null) }
    var deleting by remember { mutableStateOf<Document?>(null) }
    var docTool by remember { mutableStateOf<DocTool?>(null) }
    val locked by vm.locked.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        vm.events.collect { e ->
            when (e) {
                is HomeEvent.Opened -> onOpenDocument(e.id)
                is HomeEvent.Error -> snackbar.showSnackbar(e.message)
            }
        }
    }

    val scan = rememberDocumentScanner(
        onResult = vm::onScanned,
        onError = { scope.launch { snackbar.showSnackbar(it) } },
    )
    val pickPhotos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(100)) {
        vm.onPhotosPicked(it)
    }
    val pickPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { vm.onPdfPicked(it, displayName(context, it)) }
    }

    val tools = listOf(
        Tool("Scan", Icons.Default.DocumentScanner, action = scan),
        Tool("Photo to PDF", Icons.Default.Image, action = {
            pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }),
        Tool("Import PDF", Icons.Default.FileOpen, action = { pickPdf.launch(arrayOf("application/pdf")) }),
        Tool("Merge", Icons.Default.MergeType, action = { onNavigate(Routes.tool(PdfTool.MERGE)) }),
        Tool("Split", Icons.AutoMirrored.Filled.CallSplit, action = {
            docTool = DocTool("Split which PDF?") { Routes.tool(PdfTool.SPLIT, it) }
        }),
        Tool("Compress", Icons.Default.Compress, action = {
            docTool = DocTool("Compress which PDF?") { Routes.tool(PdfTool.COMPRESS, it) }
        }),
        Tool("Protect", Icons.Default.Password, action = {
            docTool = DocTool("Protect which PDF?") { Routes.tool(PdfTool.PROTECT, it) }
        }),
        Tool("Mask", Icons.Default.VisibilityOff, action = {
            docTool = DocTool("Mask which PDF?") { Routes.mask(it) }
        }),
        Tool("Sign", Icons.Default.Draw, action = {
            docTool = DocTool("Sign which PDF?") { Routes.annotate(it) }
        }),
        Tool("PDF to Word", Icons.Default.Description, action = {
            docTool = DocTool("Convert which PDF?") { Routes.convert(it, OutputFormat.WORD) }
        }),
        Tool("Docs Vault", Icons.Default.Lock, phase = 6),
        Tool("All tools", Icons.Default.Apps, action = { onNavigate(Routes.ALL_TOOLS) }),
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
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Text("PaperVault", fontWeight = FontWeight.SemiBold)
                    }
                },
                actions = {
                    IconButton(onClick = {
                        if (searching) vm.setQuery("")
                        searching = !searching
                    }) {
                        Icon(if (searching) Icons.Default.Close else Icons.Default.Search, "Search")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = scan,
                icon = { Icon(Icons.Default.DocumentScanner, null) },
                text = { Text("Scan") },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())

            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(tools) { tool ->
                    ToolButton(tool) {
                        tool.action?.invoke()
                            ?: scope.launch { snackbar.showSnackbar("${tool.label} arrives in phase ${tool.phase}") }
                    }
                }
            }

            Text(
                "Recent documents",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
            )

            val list = docs
            when {
                list == null -> Unit
                list.isEmpty() -> EmptyState(searching = query.isNotEmpty())
                else -> LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(list, key = { it.id }) { doc ->
                        DocumentRow(
                            doc = doc,
                            onClick = { onOpenDocument(doc.id) },
                            onShare = { ShareUtils.shareDocument(context, doc) },
                            onRename = { renaming = doc },
                            onDelete = { deleting = doc },
                        )
                    }
                }
            }
        }
    }

    renaming?.let { doc ->
        RenameDialog(doc.title, onDismiss = { renaming = null }) { vm.rename(doc, it); renaming = null }
    }
    docTool?.let { t ->
        DocumentPickerDialog(t.title, docs.orEmpty(), onDismiss = { docTool = null }) {
            docTool = null
            onNavigate(t.route(it.id))
        }
    }
    locked?.let { l ->
        PasswordDialog(
            title = "\"${l.title}\" is password-protected",
            onDismiss = vm::cancelUnlock,
            onConfirm = vm::unlockImport,
        )
    }
    deleting?.let { doc ->
        ConfirmDeleteDialog(doc.title, onDismiss = { deleting = null }) { vm.delete(doc); deleting = null }
    }
}

@Composable
private fun ToolButton(tool: Tool, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(72.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
    ) {
        Surface(
            shape = CircleShape,
            color = if (tool.action != null) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.size(48.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(tool.icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            tool.label,
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
    }
}

@Composable
private fun DocumentRow(
    doc: Document,
    onClick: () -> Unit,
    onShare: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            PdfPageImage(
                path = doc.filePath,
                page = 0,
                renderWidth = 180,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(width = 56.dp, height = 72.dp).clip(RoundedCornerShape(6.dp)),
            )
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(doc.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Text(
                    "${doc.pageCount} page${if (doc.pageCount == 1) "" else "s"} · ${formatSize(doc.sizeBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    formatDate(doc.updatedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onShare) { Icon(Icons.Default.Share, "Share") }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        leadingIcon = { Icon(Icons.Default.Edit, null) },
                        onClick = { menu = false; onRename() },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        leadingIcon = { Icon(Icons.Default.Delete, null) },
                        onClick = { menu = false; onDelete() },
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyState(searching: Boolean) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.DocumentScanner,
                null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                if (searching) "No documents match your search" else "No documents yet",
                style = MaterialTheme.typography.titleMedium,
            )
            if (!searching) {
                Text(
                    "Tap Scan to capture a document, or turn photos into a PDF.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun displayName(context: android.content.Context, uri: Uri): String? =
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
    }
