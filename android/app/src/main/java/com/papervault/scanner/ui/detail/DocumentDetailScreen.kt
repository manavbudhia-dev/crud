package com.papervault.scanner.ui.detail

import androidx.compose.foundation.layout.Arrangement
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DocumentDetailViewModel(private val repo: DocumentRepository, id: Long) : ViewModel() {
    val doc = repo.observe(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    fun rename(d: Document, t: String) = viewModelScope.launch { repo.rename(d, t) }
    suspend fun delete(d: Document) = repo.delete(d)

    companion object {
        fun factory(id: Long) = viewModelFactory { initializer { DocumentDetailViewModel(container.documents, id) } }
    }
}

/** Page-wise breakdown: every page of the PDF as a numbered thumbnail. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentDetailScreen(
    docId: Long,
    onBack: () -> Unit,
    onOpenPage: (Long, Int) -> Unit,
    vm: DocumentDetailViewModel = viewModel(key = "doc$docId", factory = DocumentDetailViewModel.factory(docId)),
) {
    val context = LocalContext.current
    val doc by vm.doc.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(doc?.title ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    doc?.let { d ->
                        IconButton(onClick = { renaming = true }) { Icon(Icons.Default.Edit, "Rename") }
                        IconButton(onClick = { ShareUtils.shareDocument(context, d) }) { Icon(Icons.Default.Share, "Share") }
                        IconButton(onClick = { deleting = true }) { Icon(Icons.Default.Delete, "Delete") }
                    }
                },
            )
        },
    ) { padding ->
        val d = doc ?: return@Scaffold
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
                            path = d.filePath,
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
