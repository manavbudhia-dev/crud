package com.papervault.scanner.ui.viewer

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.papervault.scanner.pdf.PdfOps
import com.papervault.scanner.ui.Routes
import com.papervault.scanner.ui.components.ConfirmDeleteDialog
import com.papervault.scanner.ui.components.PageBadge
import com.papervault.scanner.ui.components.PdfPageImage
import com.papervault.scanner.ui.components.RenameDialog
import com.papervault.scanner.ui.detail.DocumentDetailViewModel
import com.papervault.scanner.ui.theme.extra
import com.papervault.scanner.ui.vault.SecureWindow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Main document screen: page card with "1 / 6" badge and a floating Share / Download / Edit / More bar. */
@Composable
fun PdfViewerScreen(
    docId: Long,
    initialPage: Int,
    onBack: () -> Unit,
    onNavigate: (String) -> Unit,
    vm: DocumentDetailViewModel = viewModel(key = "doc$docId", factory = DocumentDetailViewModel.factory(docId)),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val doc by vm.doc.collectAsStateWithLifecycle()
    val path by vm.pdfPath.collectAsStateWithLifecycle()
    val locked by vm.locked.collectAsStateWithLifecycle()
    val d = doc
    SecureWindow(enabled = d?.inVault == true)
    LaunchedEffect(locked) { if (locked) onBack() }

    val pager = rememberPagerState(initialPage = initialPage) { d?.pageCount ?: 0 }
    val screenPx = with(LocalDensity.current) { LocalConfiguration.current.screenWidthDp.dp.roundToPx() }
    val renderWidth = (screenPx * 1.5f).toInt().coerceAtMost(2000)

    var topMenu by remember { mutableStateOf(false) }
    var editMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

    val download = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri: Uri? ->
        val p = path ?: return@rememberLauncherForActivityResult
        if (uri != null) scope.launch(Dispatchers.IO) {
            context.contentResolver.openOutputStream(uri)?.use { out -> File(p).inputStream().use { it.copyTo(out) } }
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.extra.background)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = MaterialTheme.extra.ink) }
                Text(
                    d?.let { "${it.title}.pdf" } ?: "", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.extra.ink,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 6.dp),
                )
                IconButton(onClick = { searching = true }) { Icon(Icons.Outlined.Search, "Search in document", tint = MaterialTheme.extra.ink) }
                Box {
                    IconButton(onClick = { topMenu = true }) { Icon(Icons.Default.MoreVert, "More", tint = MaterialTheme.extra.ink) }
                    DropdownMenu(expanded = topMenu, onDismissRequest = { topMenu = false }) {
                        MenuItem(Icons.Outlined.GridView, "All pages") { topMenu = false; d?.let { onNavigate(Routes.detail(it.id)) } }
                        MenuItem(Icons.Outlined.DriveFileRenameOutline, "Rename") { topMenu = false; renaming = true }
                        MenuItem(Icons.Outlined.Delete, "Delete") { topMenu = false; deleting = true }
                    }
                }
            }

            val readable = path
            if (d == null || readable == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                return@Column
            }
            HorizontalPager(
                state = pager,
                contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 110.dp),
                pageSpacing = 14.dp,
                beyondViewportPageCount = 1,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                Box(
                    Modifier.fillMaxSize().shadow(12.dp, RoundedCornerShape(24.dp), ambientColor = Color(0x221E3A8A), spotColor = Color(0x221E3A8A))
                        .clip(RoundedCornerShape(24.dp)).background(Color.White),
                ) {
                    ZoomablePage { PdfPageImage(readable, page, renderWidth, Modifier.fillMaxWidth().background(Color.White)) }
                    PageBadge("${page + 1} / ${d.pageCount}", Modifier.align(Alignment.TopEnd).padding(14.dp).clickable { onNavigate(Routes.detail(d.id)) })
                }
            }
        }

        // Floating action bar
        val dd = d
        if (dd != null) Row(
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(horizontal = 18.dp, vertical = 14.dp).fillMaxWidth()
                .shadow(16.dp, RoundedCornerShape(26.dp), ambientColor = Color(0x261E3A8A), spotColor = Color(0x261E3A8A))
                .clip(RoundedCornerShape(26.dp)).background(MaterialTheme.extra.card).padding(vertical = 12.dp),
        ) {
            BarAction(Icons.Outlined.Share, "Share", Modifier.weight(1f)) { vm.share(context, dd) }
            BarAction(Icons.Outlined.Download, "Download", Modifier.weight(1f)) {
                if (dd.inVault) vm.keepUnlocked()
                download.launch("${dd.title}.pdf")
            }
            if (!dd.inVault) Box(Modifier.weight(1f)) {
                BarAction(Icons.Outlined.Edit, "Edit", Modifier.fillMaxWidth()) { editMenu = true }
                DropdownMenu(expanded = editMenu, onDismissRequest = { editMenu = false }) {
                    MenuItem(Icons.Outlined.Draw, "Annotate & sign") { editMenu = false; onNavigate(Routes.annotate(dd.id, pager.currentPage)) }
                    MenuItem(Icons.Outlined.ViewAgenda, "Organize pages") { editMenu = false; onNavigate(Routes.editor(dd.id)) }
                    MenuItem(Icons.Outlined.VisibilityOff, "Mask content") { editMenu = false; onNavigate(Routes.mask(dd.id)) }
                }
            }
            Box(Modifier.weight(1f)) {
                BarAction(Icons.Default.MoreHoriz, "More", Modifier.fillMaxWidth()) { moreMenu = true }
                DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                    MenuItem(Icons.Outlined.GridView, "All pages") { moreMenu = false; onNavigate(Routes.detail(dd.id)) }
                    if (dd.inVault) {
                        MenuItem(Icons.Outlined.LockOpen, "Move to library") { moreMenu = false; vm.moveOutOfVault(dd) }
                    } else {
                        MenuItem(Icons.Outlined.SwapHoriz, "Convert") { moreMenu = false; onNavigate(Routes.convert(dd.id)) }
                        MenuItem(Icons.Outlined.Build, "PDF tools") { moreMenu = false; onNavigate(Routes.detail(dd.id)) }
                        MenuItem(Icons.Outlined.Lock, "Move to Docs Vault") { moreMenu = false; onBack(); onNavigate(Routes.vault(moveDocId = dd.id)) }
                    }
                    MenuItem(Icons.Outlined.Delete, "Delete") { moreMenu = false; deleting = true }
                }
            }
        }
    }

    if (searching && path != null) {
        SearchDialog(path!!, onDismiss = { searching = false }) { page ->
            searching = false
            scope.launch { pager.animateScrollToPage(page) }
        }
    }
    if (renaming && d != null) RenameDialog(d.title, onDismiss = { renaming = false }) { vm.rename(d, it); renaming = false }
    if (deleting && d != null) ConfirmDeleteDialog(d.title, onDismiss = { deleting = false }) {
        deleting = false
        scope.launch { vm.delete(d); onBack() }
    }
}

@Composable
private fun BarAction(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = MaterialTheme.extra.ink, modifier = Modifier.size(26.dp))
        Spacer(Modifier.size(4.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.extra.ink)
    }
}

@Composable
private fun MenuItem(icon: ImageVector, text: String, onClick: () -> Unit) =
    DropdownMenuItem(text = { Text(text) }, leadingIcon = { Icon(icon, null) }, onClick = onClick)

/** Find text in the PDF (embedded text layer) and jump to a matching page. */
@Composable
private fun SearchDialog(path: String, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    var query by remember { mutableStateOf("") }
    var pages by remember { mutableStateOf<List<String>?>(null) }
    LaunchedEffect(path) { pages = withContext(Dispatchers.IO) { runCatching { PdfOps.extractText(File(path)) }.getOrDefault(emptyList()) } }
    val results = remember(query, pages) {
        val q = query.trim()
        if (q.length < 2) emptyList() else pages.orEmpty().mapIndexedNotNull { i, t ->
            val idx = t.indexOf(q, ignoreCase = true)
            if (idx < 0) null else i to t.substring((idx - 30).coerceAtLeast(0), (idx + q.length + 40).coerceAtMost(t.length)).replace('\n', ' ')
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Search in document") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(query, { query = it }, singleLine = true, placeholder = { Text("Type at least 2 characters") }, modifier = Modifier.fillMaxWidth())
                when {
                    pages == null -> CircularProgressIndicator(Modifier.size(24.dp))
                    pages!!.all { it.isBlank() } -> Text("This document has no text layer (e.g. a photo scan). Use PDF to Text with OCR first.", style = MaterialTheme.typography.bodySmall)
                    query.trim().length >= 2 && results.isEmpty() -> Text("No matches", style = MaterialTheme.typography.bodySmall)
                    else -> LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        items(results) { (page, snippet) ->
                            Column(Modifier.fillMaxWidth().clickable { onPick(page) }.padding(vertical = 8.dp)) {
                                Text("Page ${page + 1}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                                Text("…$snippet…", style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** Pinch to zoom (1x–5x), pan when zoomed, double-tap to toggle 2.5x. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ZoomablePage(content: @Composable () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val state = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 5f)
        offset = if (scale == 1f) Offset.Zero else offset + pan
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = 2.5f }) }
            .transformable(state, canPan = { scale > 1f })
            .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y },
    ) { content() }
}
