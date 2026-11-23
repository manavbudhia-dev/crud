package com.papervault.scanner.ui.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.graphics.vector.ImageVector
import com.papervault.scanner.ui.Routes
import com.papervault.scanner.ui.tools.PdfTool
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.papervault.scanner.data.Document
import com.papervault.scanner.data.DocumentRepository
import com.papervault.scanner.ui.components.ConfirmDeleteDialog
import com.papervault.scanner.ui.components.PdfPageImage
import com.papervault.scanner.ui.components.RenameDialog
import com.papervault.scanner.ui.container
import com.papervault.scanner.util.ShareUtils
import com.papervault.scanner.util.formatDate
import com.papervault.scanner.util.formatSize
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import com.papervault.scanner.ui.vault.SecureWindow
import com.papervault.scanner.vault.VaultManager
import com.papervault.scanner.vault.VaultState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class DocumentDetailViewModel(private val repo: DocumentRepository, private val vault: VaultManager, id: Long) : ViewModel() {
    val doc = repo.observe(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** True when this is a vault document and the vault is locked: the screen should close. */
    val locked = combine(doc, vault.state) { d, s -> d?.inVault == true && s != VaultState.UNLOCKED }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Readable PDF path: the file itself, or a decrypted temporary copy for vault documents. */
    val pdfPath: StateFlow<String?> = combine(doc, vault.state) { d, s -> d to s }
        .mapLatest { (d, s) ->
            when {
                d == null -> null
                !d.inVault -> d.filePath
                s != VaultState.UNLOCKED -> null
                else -> runCatching { vault.openForViewing(d).absolutePath }.getOrNull()
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun rename(d: Document, t: String) = viewModelScope.launch { repo.rename(d, t) }
    suspend fun delete(d: Document) = repo.delete(d)
    fun moveOutOfVault(d: Document) = viewModelScope.launch { runCatching { vault.moveOut(d) } }

    fun share(context: android.content.Context, d: Document) = viewModelScope.launch {
        if (d.inVault) {
            val f = runCatching { vault.exportForSharing(d) }.getOrNull() ?: return@launch
            vault.keepUnlockedForExternal()
            ShareUtils.shareFiles(context, listOf(f), "application/pdf")
        } else {
            ShareUtils.shareDocument(context, d)
        }
    }

    companion object {
        fun factory(id: Long) = viewModelFactory { initializer { DocumentDetailViewModel(container.documents, container.vault, id) } }
    }
}

/** Page-wise breakdown: every page of the PDF as a numbered thumbnail. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentDetailScreen(
    docId: Long,
    onBack: () -> Unit,
    onOpenPage: (Long, Int) -> Unit,
    onNavigate: (String) -> Unit,
    vm: DocumentDetailViewModel = viewModel(key = "doc$docId", factory = DocumentDetailViewModel.factory(docId)),
) {
    val context = LocalContext.current
    val doc by vm.doc.collectAsStateWithLifecycle()
    val path by vm.pdfPath.collectAsStateWithLifecycle()
    val locked by vm.locked.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var toolsMenu by remember { mutableStateOf(false) }
    var overflow by remember { mutableStateOf(false) }
    val inVault = doc?.inVault == true

    SecureWindow(enabled = inVault)
    LaunchedEffect(locked) { if (locked) onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(doc?.title ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    doc?.let { d ->
                        IconButton(onClick = { renaming = true }) { Icon(Icons.Default.Edit, "Rename") }
                        Box {
                            IconButton(onClick = { overflow = true }) { Icon(Icons.Default.MoreVert, "More") }
                            DropdownMenu(expanded = overflow, onDismissRequest = { overflow = false }) {
                                if (d.inVault) {
                                    DropdownMenuItem(text = { Text("Move to library") }, leadingIcon = { Icon(Icons.Default.LockOpen, null) },
                                        onClick = { overflow = false; vm.moveOutOfVault(d) })
                                } else {
                                    DropdownMenuItem(text = { Text("Move to Docs Vault") }, leadingIcon = { Icon(Icons.Default.Lock, null) },
                                        onClick = { overflow = false; onBack(); onNavigate(Routes.vault(moveDocId = d.id)) })
                                }
                                DropdownMenuItem(text = { Text("Delete") }, leadingIcon = { Icon(Icons.Default.Delete, null) },
                                    onClick = { overflow = false; deleting = true })
                            }
                        }
                    }
                },
            )
        },
        bottomBar = {
            val d = doc ?: return@Scaffold
            if (d.inVault) {
                BottomAppBar {
                    DetailAction(Icons.Default.Share, "Share copy", Modifier.weight(1f)) { vm.share(context, d) }
                    DetailAction(Icons.Default.LockOpen, "Move to library", Modifier.weight(1f)) { vm.moveOutOfVault(d) }
                }
                return@Scaffold
            }
            BottomAppBar {
                DetailAction(Icons.Default.EditNote, "Pages", Modifier.weight(1f)) { onNavigate(Routes.editor(d.id)) }
                DetailAction(Icons.Default.Draw, "Sign", Modifier.weight(1f)) { onNavigate(Routes.annotate(d.id)) }
                DetailAction(Icons.Default.VisibilityOff, "Mask", Modifier.weight(1f)) { onNavigate(Routes.mask(d.id)) }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    DetailAction(Icons.Default.Build, "Tools", Modifier.fillMaxWidth()) { toolsMenu = true }
                    DropdownMenu(expanded = toolsMenu, onDismissRequest = { toolsMenu = false }) {
                        listOf(PdfTool.MERGE, PdfTool.SPLIT, PdfTool.COMPRESS, PdfTool.PROTECT, PdfTool.PAGE_NUMBERS, PdfTool.WATERMARK)
                            .forEach { t ->
                                DropdownMenuItem(text = { Text(t.title) }, onClick = {
                                    toolsMenu = false
                                    onNavigate(Routes.tool(t, d.id))
                                })
                            }
                    }
                }
                DetailAction(Icons.Default.SwapHoriz, "Convert", Modifier.weight(1f)) { onNavigate(Routes.convert(d.id)) }
                DetailAction(Icons.Default.Share, "Share", Modifier.weight(1f)) { vm.share(context, d) }
            }
        },
    ) { padding ->
        val d = doc ?: return@Scaffold
        val readable = path ?: return@Scaffold
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 150.dp),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    "${d.pageCount} pages · ${formatSize(d.sizeBytes)} · ${formatDate(d.createdAt)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items((0 until d.pageCount).toList(), key = { it }) { page ->
                Card(onClick = { onOpenPage(d.id, page) }) {
                    Box {
                        PdfPageImage(
                            path = readable,
                            page = page,
                            renderWidth = 400,
                            modifier = Modifier.fillMaxWidth().aspectRatio(0.707f),
                        )
                        Surface(
                            color = MaterialTheme.colorScheme.primary,
                            shape = RoundedCornerShape(topEnd = 8.dp),
                            modifier = Modifier.align(Alignment.BottomStart),
                        ) {
                            Text(
                                "${page + 1}",
                                color = MaterialTheme.colorScheme.onPrimary,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
            }
        }

        if (renaming) {
            RenameDialog(d.title, onDismiss = { renaming = false }) { vm.rename(d, it); renaming = false }
        }
        if (deleting) {
            ConfirmDeleteDialog(d.title, onDismiss = { deleting = false }) {
                deleting = false
                scope.launch { vm.delete(d); onBack() }
            }
        }
    }
}

@Composable
private fun DetailAction(icon: ImageVector, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 6.dp),
    ) {
        Icon(icon, null)
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}
