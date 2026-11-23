package com.papervault.scanner.ui.editor

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.RotateLeft
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.papervault.scanner.ui.components.PdfPageImage
import com.papervault.scanner.ui.home.rememberDocumentScanner
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageEditorScreen(
    docId: Long,
    onBack: () -> Unit,
    onOpenDocument: (Long) -> Unit,
    vm: PageEditorViewModel = viewModel(key = "edit$docId", factory = PageEditorViewModel.factory(docId)),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var addMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        vm.events.collect {
            when (it) {
                EditorEvent.Saved -> snackbar.showSnackbar("Saved")
                is EditorEvent.Created -> onOpenDocument(it.id)
                is EditorEvent.Message -> snackbar.showSnackbar(it.text)
            }
        }
    }

    val scan = rememberDocumentScanner(onResult = vm::appendPdf, onError = { scope.launch { snackbar.showSnackbar(it) } })
    val pickPhotos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(100)) { vm.appendImages(it) }
    val pickPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::appendPdf) }

    val exit = { if (state.dirty) confirmDiscard = true else onBack() }
    BackHandler(onBack = exit)
    val hasSelection = state.selected.isNotEmpty()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (hasSelection) "${state.selected.size} selected" else "Edit pages") },
                navigationIcon = { IconButton(onClick = exit) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = vm::selectAll) { Icon(Icons.Default.SelectAll, "Select all") }
                    Box {
                        IconButton(onClick = { addMenu = true }) { Icon(Icons.Default.Add, "Add pages") }
                        DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                            DropdownMenuItem(text = { Text("Scan pages") }, leadingIcon = { Icon(Icons.Default.DocumentScanner, null) },
                                onClick = { addMenu = false; scan() })
                            DropdownMenuItem(text = { Text("Add photos") }, leadingIcon = { Icon(Icons.Default.Image, null) },
                                onClick = {
                                    addMenu = false
                                    pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                })
                            DropdownMenuItem(text = { Text("Add PDF") }, leadingIcon = { Icon(Icons.Default.FileOpen, null) },
                                onClick = { addMenu = false; pickPdf.launch(arrayOf("application/pdf")) })
                        }
                    }
                    TextButton(onClick = vm::save, enabled = state.dirty && !state.busy) { Text("Save") }
                },
            )
        },
        bottomBar = {
            BottomAppBar {
                IconButton(onClick = { vm.rotate(-90) }, enabled = hasSelection) { Icon(Icons.AutoMirrored.Filled.RotateLeft, "Rotate left") }
                IconButton(onClick = { vm.rotate(90) }, enabled = hasSelection) { Icon(Icons.AutoMirrored.Filled.RotateRight, "Rotate right") }
                IconButton(onClick = { vm.move(-1) }, enabled = hasSelection) { Icon(Icons.Default.ChevronLeft, "Move earlier") }
                IconButton(onClick = { vm.move(1) }, enabled = hasSelection) { Icon(Icons.Default.ChevronRight, "Move later") }
                IconButton(onClick = vm::duplicateSelected, enabled = hasSelection) { Icon(Icons.Default.ContentCopy, "Duplicate") }
                IconButton(onClick = vm::deleteSelected, enabled = hasSelection) { Icon(Icons.Default.Delete, "Delete") }
                Box {
                    IconButton(onClick = { moreMenu = true }, enabled = hasSelection) { Icon(Icons.Default.MoreVert, "More") }
                    DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                        DropdownMenuItem(text = { Text("Extract to new PDF") }, onClick = { moreMenu = false; vm.extractSelected() })
                        DropdownMenuItem(text = { Text("Share as images") }, onClick = { moreMenu = false; vm.shareSelectedAsImages() })
                        DropdownMenuItem(text = { Text("Clear selection") }, onClick = { moreMenu = false; vm.clearSelection() })
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(
                "Tap pages to select, then rotate, reorder, duplicate or delete. Save writes the changes.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            LazyVerticalGrid(
                columns = GridCells.Adaptive(120.dp),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                itemsIndexed(state.pages, key = { _, p -> p.key }) { index, page ->
                    val selected = page.key in state.selected
                    Card(
                        onClick = { vm.toggle(page.key) },
                        border = if (selected) BorderStroke(3.dp, MaterialTheme.colorScheme.primary) else null,
                        colors = CardDefaults.cardColors(),
                        modifier = Modifier.animateItem(),
                    ) {
                        Box(Modifier.fillMaxWidth().aspectRatio(0.75f), contentAlignment = Alignment.Center) {
                            PdfPageImage(
                                path = page.spec.source.absolutePath,
                                page = page.spec.pageIndex,
                                renderWidth = 300,
                                modifier = Modifier.padding(8.dp).rotate(page.spec.rotation.toFloat()),
                            )
                            Icon(
                                if (selected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(22.dp),
                            )
                            Text(
                                "${index + 1}",
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.align(Alignment.BottomCenter).padding(4.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard changes?") },
            text = { Text("Your page edits haven't been saved.") },
            confirmButton = { TextButton(onClick = { confirmDiscard = false; onBack() }) { Text("Discard") } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false; vm.save() }) { Text("Save") } },
            shape = RoundedCornerShape(16.dp),
        )
    }
}
