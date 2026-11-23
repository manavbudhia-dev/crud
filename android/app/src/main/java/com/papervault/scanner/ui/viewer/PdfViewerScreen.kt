package com.papervault.scanner.ui.viewer

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.papervault.scanner.ui.components.PdfPageImage
import com.papervault.scanner.ui.detail.DocumentDetailViewModel
import com.papervault.scanner.ui.vault.SecureWindow
import androidx.compose.runtime.LaunchedEffect

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfViewerScreen(
    docId: Long,
    initialPage: Int,
    onBack: () -> Unit,
    vm: DocumentDetailViewModel = viewModel(key = "doc$docId", factory = DocumentDetailViewModel.factory(docId)),
) {
    val context = LocalContext.current
    val doc by vm.doc.collectAsStateWithLifecycle()
    val path by vm.pdfPath.collectAsStateWithLifecycle()
    val locked by vm.locked.collectAsStateWithLifecycle()
    val d = doc
    SecureWindow(enabled = d?.inVault == true)
    LaunchedEffect(locked) { if (locked) onBack() }
    val pager = rememberPagerState(initialPage = initialPage) { d?.pageCount ?: 0 }
    // Render at screen width (capped) so text stays sharp at 1x; zoom scales the bitmap.
    val screenPx = with(LocalDensity.current) { LocalConfiguration.current.screenWidthDp.dp.roundToPx() }
    val renderWidth = (screenPx * 1.5f).toInt().coerceAtMost(2000)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(d?.title ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    d?.let { IconButton(onClick = { vm.share(context, it) }) { Icon(Icons.Default.Share, "Share") } }
                },
            )
        },
        containerColor = Color(0xFF2B2B2B),
    ) { padding ->
        if (d == null) return@Scaffold
        val readable = path ?: return@Scaffold
        Box(Modifier.padding(padding).fillMaxSize()) {
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize(), beyondViewportPageCount = 1) { page ->
                ZoomablePage { PdfPageImage(readable, page, renderWidth, Modifier.fillMaxWidth().background(Color.White)) }
            }
            Surface(
                color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.8f),
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
            ) {
                Text(
                    "${pager.currentPage + 1} / ${d.pageCount}",
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
/** Pinch to zoom (1x–5x), pan when zoomed, double-tap to toggle 2.5x. */
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
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = {
                    if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = 2.5f
                })
            }
            // Only consume gestures when zoomed, so the pager can still swipe at 1x.
            .transformable(state, canPan = { scale > 1f })
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            },
    ) { content() }
}
