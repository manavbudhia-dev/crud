package com.papervault.scanner.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.papervault.scanner.data.Document
import com.papervault.scanner.data.SortOrder
import com.papervault.scanner.ui.Routes
import com.papervault.scanner.ui.components.AppHeader
import com.papervault.scanner.ui.components.ConfirmDeleteDialog
import com.papervault.scanner.ui.components.FileTypeTile
import com.papervault.scanner.ui.components.PdfPageImage
import com.papervault.scanner.ui.components.RenameDialog
import com.papervault.scanner.ui.components.SoftCard
import com.papervault.scanner.ui.components.StarButton
import com.papervault.scanner.ui.components.bouncyClick
import com.papervault.scanner.ui.components.enterUp
import com.papervault.scanner.ui.components.pressScale
import com.papervault.scanner.ui.components.fileKind
import com.papervault.scanner.ui.components.relativeTime
import com.papervault.scanner.ui.theme.Brand
import com.papervault.scanner.ui.theme.extra
import com.papervault.scanner.util.ShareUtils
import com.papervault.scanner.util.formatSize

enum class DocumentsMode { ALL, STARRED, SEARCH }

/** Documents tab (chips All / Starred / Recent), Starred tab, and search — one screen, three modes. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentsScreen(
    mode: DocumentsMode,
    onOpenDocument: (Long) -> Unit,
    onNavigate: (String) -> Unit,
    onBack: () -> Unit = {},
    vm: HomeViewModel = viewModel(key = "docs_$mode", factory = HomeViewModel.Factory),
) {
    val context = LocalContext.current
    val docs by vm.documents.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val folders by vm.folders.collectAsStateWithLifecycle()
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var sheet by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Document?>(null) }
    var deleting by remember { mutableStateOf<Document?>(null) }
    var foldering by remember { mutableStateOf<Document?>(null) }

    LaunchedEffect(mode) { if (mode == DocumentsMode.STARRED) vm.setFilter(LibraryFilter.Starred) }
    LaunchedEffect(Unit) {
        vm.events.collect { e ->
            when (e) {
                is HomeEvent.Opened -> onOpenDocument(e.id)
                is HomeEvent.Error -> snackbar.showSnackbar(e.message)
            }
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.extra.background)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            if (mode == DocumentsMode.SEARCH) {
                val focus = remember { FocusRequester() }
                LaunchedEffect(Unit) { focus.requestFocus() }
                Row(Modifier.padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    TextField(
                        value = query, onValueChange = vm::setQuery, singleLine = true,
                        placeholder = { Text("Search documents") },
                        shape = RoundedCornerShape(50),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.extra.card, unfocusedContainerColor = MaterialTheme.extra.card,
                            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                        ),
                        trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { vm.setQuery("") }) { Icon(Icons.Default.Close, "Clear") } },
                        modifier = Modifier.weight(1f).focusRequester(focus),
                    )
                }
            } else {
                AppHeader(prefs.initial, onSearch = { onNavigate(Routes.SEARCH) }, onProfile = { onNavigate(Routes.SETTINGS) })
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())

            if (mode == DocumentsMode.ALL) {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        item { Pill("All", filter == LibraryFilter.All) { vm.setFilter(LibraryFilter.All) } }
                        item { Pill("Starred", filter == LibraryFilter.Starred) { vm.setFilter(LibraryFilter.Starred) } }
                        item { Pill("Recent", filter == LibraryFilter.Recent) { vm.setFilter(LibraryFilter.Recent) } }
                        items(folders) { f -> Pill(f, filter == LibraryFilter.Folder(f)) { vm.setFilter(LibraryFilter.Folder(f)) } }
                    }
                    Spacer(Modifier.width(10.dp))
                    Box(
                        Modifier.size(46.dp).shadow(6.dp, CircleShape).clip(CircleShape).background(MaterialTheme.extra.card).clickable { sheet = true },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Default.Tune, "Sort and filter", tint = MaterialTheme.extra.ink) }
                }
            } else if (mode == DocumentsMode.STARRED) {
                Text("Starred", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.extra.ink, modifier = Modifier.padding(start = 24.dp, top = 8.dp))
            }

            if (mode != DocumentsMode.SEARCH) {
                Row(Modifier.fillMaxWidth().padding(end = 16.dp, top = 10.dp), horizontalArrangement = Arrangement.End) {
                    Row(Modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.extra.chip.copy(alpha = 0.6f)).padding(4.dp)) {
                        ViewToggle(Icons.Default.GridView, prefs.documentsGrid) { vm.setGrid(true) }
                        ViewToggle(Icons.AutoMirrored.Filled.ViewList, !prefs.documentsGrid) { vm.setGrid(false) }
                    }
                }
            }

            val list = docs
            when {
                list == null -> Unit
                list.isEmpty() -> EmptyDocs(mode, query.isNotEmpty() || filter != LibraryFilter.All)
                prefs.documentsGrid && mode != DocumentsMode.SEARCH -> LazyVerticalGrid(
                    GridCells.Adaptive(150.dp),
                    contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 110.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(list, key = { it.id }) { d ->
                        DocumentGridItem(d, Modifier.animateItem(), onClick = { onOpenDocument(d.id) }, onStar = { vm.toggleStar(d) })
                    }
                }
                else -> LazyColumn(
                    contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 110.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(list, key = { it.id }) { d ->
                        DocumentListItem(
                            d, modifier = Modifier.animateItem(), onClick = { onOpenDocument(d.id) }, onStar = { vm.toggleStar(d) },
                            onMore = DocMenuActions(
                                share = { ShareUtils.shareDocument(context, d) },
                                rename = { renaming = d },
                                folder = { foldering = d },
                                vault = { onNavigate(Routes.vault(moveDocId = d.id)) },
                                delete = { deleting = d },
                            ),
                        )
                    }
                }
            }
        }

        if (mode != DocumentsMode.SEARCH) {
            val fabInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            FloatingActionButton(
                onClick = { onNavigate(Routes.SCAN) },
                interactionSource = fabInteraction,
                shape = CircleShape,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = Color.White,
                modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = 20.dp, bottom = 104.dp).size(66.dp)
                    .enterUp(250, 120f).pressScale(fabInteraction, 0.9f),
            ) { Icon(Icons.Default.DocumentScanner, "Scan", Modifier.size(30.dp)) }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }

    if (sheet) {
        ModalBottomSheet(onDismissRequest = { sheet = false }, containerColor = MaterialTheme.extra.card) {
            Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
                Text("Sort by", style = MaterialTheme.typography.titleMedium)
                SortOrder.entries.forEach { s ->
                    Row(Modifier.fillMaxWidth().clickable { vm.setSort(s); sheet = false }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = prefs.sort == s, onClick = null)
                        Text(s.label, Modifier.padding(start = 12.dp))
                    }
                }
            }
        }
    }
    renaming?.let { d -> RenameDialog(d.title, onDismiss = { renaming = null }) { vm.rename(d, it); renaming = null } }
    deleting?.let { d -> ConfirmDeleteDialog(d.title, onDismiss = { deleting = null }) { vm.delete(d); deleting = null } }
    foldering?.let { d ->
        FolderDialog(d.folder, folders, onDismiss = { foldering = null }) { vm.moveToFolder(d, it); foldering = null }
    }
}

@Composable
private fun Pill(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    val bg by androidx.compose.animation.animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.extra.chip, label = "pillBg",
    )
    val fg by androidx.compose.animation.animateColorAsState(if (selected) Color.White else MaterialTheme.extra.ink, label = "pillFg")
    val elevation by androidx.compose.animation.core.animateDpAsState(if (selected) 8.dp else 0.dp, label = "pillElevation")
    Box(
        Modifier
            .shadow(elevation, shape, spotColor = MaterialTheme.colorScheme.primary)
            .clip(shape)
            .background(bg)
            .bouncyClick(0.94f, onClick)
            .padding(horizontal = 22.dp, vertical = 11.dp),
    ) {
        Text(label, style = MaterialTheme.typography.titleSmall, color = fg)
    }
}

@Composable
private fun ViewToggle(icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)).background(if (selected) MaterialTheme.extra.card else Color.Transparent).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, Modifier.size(20.dp), tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.extra.ink) }
}

class DocMenuActions(val share: () -> Unit, val rename: () -> Unit, val folder: () -> Unit, val vault: () -> Unit, val delete: () -> Unit)

/** Row card: file-type tile, "Title.pdf", "⏱ 2h ago • 2.4 MB", star, ⋮. */
@Composable
fun DocumentListItem(d: Document, onClick: () -> Unit, onStar: () -> Unit, onMore: DocMenuActions?, modifier: Modifier = Modifier) {
    var menu by remember { mutableStateOf(false) }
    SoftCard(modifier.fillMaxWidth(), onClick = onClick) {
        Row(Modifier.padding(start = 14.dp, top = 14.dp, bottom = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            FileTypeTile(d.fileKind())
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text("${d.title}.pdf", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.extra.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.AccessTime, null, Modifier.size(15.dp), tint = MaterialTheme.extra.inkSoft)
                    Text(
                        " ${relativeTime(d.updatedAt)} • ${formatSize(d.sizeBytes)}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.extra.inkSoft, maxLines = 1,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StarButton(d.starred, onStar)
                    if (onMore != null) Box {
                        IconButton(onClick = { menu = true }, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.MoreVert, "More", tint = MaterialTheme.extra.ink) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Share") }, leadingIcon = { Icon(Icons.Default.Share, null) }, onClick = { menu = false; onMore.share() })
                            DropdownMenuItem(text = { Text("Rename") }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = { menu = false; onMore.rename() })
                            DropdownMenuItem(text = { Text("Move to folder") }, leadingIcon = { Icon(Icons.Default.Folder, null) }, onClick = { menu = false; onMore.folder() })
                            DropdownMenuItem(text = { Text("Move to Docs Vault") }, leadingIcon = { Icon(Icons.Default.Lock, null) }, onClick = { menu = false; onMore.vault() })
                            DropdownMenuItem(text = { Text("Delete") }, leadingIcon = { Icon(Icons.Default.Delete, null) }, onClick = { menu = false; onMore.delete() })
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

@Composable
private fun DocumentGridItem(d: Document, modifier: Modifier, onClick: () -> Unit, onStar: () -> Unit) {
    SoftCard(modifier.fillMaxWidth(), onClick = onClick) {
        Box {
            PdfPageImage(d.filePath, 0, 360, Modifier.fillMaxWidth().aspectRatio(0.8f), contentScale = ContentScale.Crop)
            StarButton(d.starred, onStar, Modifier.align(Alignment.TopEnd).padding(4.dp))
        }
        Column(Modifier.padding(12.dp)) {
            Text("${d.title}.pdf", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${relativeTime(d.updatedAt)} • ${formatSize(d.sizeBytes)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.extra.inkSoft)
        }
    }
}

@Composable
private fun EmptyDocs(mode: DocumentsMode, filtered: Boolean) {
    Column(Modifier.fillMaxWidth().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        FileTypeTile(com.papervault.scanner.ui.components.FileKind.PDF, 80.dp)
        Spacer(Modifier.height(16.dp))
        Text(
            when {
                mode == DocumentsMode.STARRED -> "No starred documents"
                filtered -> "No matching documents"
                else -> "No documents yet"
            },
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            if (mode == DocumentsMode.STARRED) "Tap the star on a document to keep it here." else "Tap the scan button to capture your first document.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.extra.inkSoft, textAlign = TextAlign.Center,
        )
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
                if (existing.isNotEmpty()) LazyColumn(Modifier.heightIn(max = 220.dp)) {
                    items(existing) { f ->
                        Row(Modifier.fillMaxWidth().clickable { name = f }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.primary)
                            Text(f, Modifier.padding(start = 10.dp))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(name.ifBlank { null }) }) { Text(if (name.isBlank()) "Remove from folder" else "Move") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
