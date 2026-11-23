package com.papervault.scanner.ui.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.automirrored.filled.TextSnippet
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MergeType
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.papervault.scanner.convert.OutputFormat
import com.papervault.scanner.ui.Routes

private sealed interface Entry
private data class Header(val title: String) : Entry
private data class Item(val label: String, val icon: ImageVector, val needsDoc: Boolean, val route: (Long) -> String) : Entry

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AllToolsScreen(onBack: () -> Unit, onNavigate: (String) -> Unit, vm: ToolViewModel = viewModel(key = "alltools", factory = ToolViewModel.Factory)) {
    val library by vm.library.collectAsStateWithLifecycle()
    var picking by remember { mutableStateOf<Item?>(null) }

    val entries = listOf(
        Header("Edit"),
        Item("Mask content", Icons.Default.VisibilityOff, true) { Routes.mask(it) },
        Item("Sign & annotate", Icons.Default.Draw, true) { Routes.annotate(it) },
        Item("Page numbers", Icons.Default.FormatListNumbered, true) { Routes.tool(PdfTool.PAGE_NUMBERS, it) },
        Item("Watermark", Icons.Default.WaterDrop, true) { Routes.tool(PdfTool.WATERMARK, it) },
        Header("Organise"),
        Item("Merge PDFs", Icons.Default.MergeType, false) { Routes.tool(PdfTool.MERGE) },
        Item("Split PDF", Icons.AutoMirrored.Filled.CallSplit, true) { Routes.tool(PdfTool.SPLIT, it) },
        Item("Compress", Icons.Default.Compress, true) { Routes.tool(PdfTool.COMPRESS, it) },
        Header("Convert from PDF"),
        Item("PDF to Word", Icons.Default.Description, true) { Routes.convert(it, OutputFormat.WORD) },
        Item("PDF to images", Icons.Default.Collections, true) { Routes.convert(it, OutputFormat.JPG) },
        Item("PDF to text", Icons.AutoMirrored.Filled.TextSnippet, true) { Routes.convert(it, OutputFormat.TEXT) },
        Item("PDF to Excel", Icons.Default.GridOn, true) { Routes.convert(it, OutputFormat.EXCEL) },
        Item("PDF to PowerPoint", Icons.Default.Slideshow, true) { Routes.convert(it, OutputFormat.POWERPOINT) },
        Header("Convert to PDF / text"),
        Item("Word to PDF", Icons.Default.PictureAsPdf, false) { Routes.importConvert(ImportKind.WORD_TO_PDF) },
        Item("Text to PDF", Icons.Default.TextFields, false) { Routes.importConvert(ImportKind.TEXT_TO_PDF) },
        Item("Image to text", Icons.Default.Badge, false) { Routes.importConvert(ImportKind.IMAGE_TO_TEXT) },
        Header("Security"),
        Item("Password protect", Icons.Default.Password, true) { Routes.tool(PdfTool.PROTECT, it) },
        Item("Docs Vault", Icons.Default.Lock, false) { Routes.VAULT },
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("All tools") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(100.dp),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            items(entries, span = { if (it is Header) GridItemSpan(maxLineSpan) else GridItemSpan(1) }) { e ->
                when (e) {
                    is Header -> Text(e.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                    is Item -> Card(onClick = { if (e.needsDoc) picking = e else onNavigate(e.route(-1)) }) {
                        Column(
                            Modifier.padding(12.dp).fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(e.icon, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
                            Text(e.label, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
                        }
                    }
                }
            }
        }
    }
    picking?.let { item ->
        DocumentPickerDialog("Choose a PDF", library, onDismiss = { picking = null }) {
            picking = null
            onNavigate(item.route(it.id))
        }
    }
}
