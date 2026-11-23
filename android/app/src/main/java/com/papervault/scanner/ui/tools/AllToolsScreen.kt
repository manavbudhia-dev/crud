package com.papervault.scanner.ui.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.TextSnippet
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.papervault.scanner.PaperVaultApp
import com.papervault.scanner.convert.OutputFormat
import com.papervault.scanner.ui.Routes
import com.papervault.scanner.ui.components.AppHeader
import com.papervault.scanner.ui.components.SectionTitle
import com.papervault.scanner.ui.components.SoftCard
import com.papervault.scanner.ui.components.enterUp
import com.papervault.scanner.ui.components.ToolTile
import com.papervault.scanner.ui.theme.Brand
import com.papervault.scanner.ui.theme.extra

private data class ToolEntry(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val fg: Color,
    val bg: Color,
    val needsDoc: Boolean,
    val route: (Long) -> String,
)

private val entries = listOf(
    ToolEntry("PDF Editor", "Edit, annotate and more", Icons.Default.EditNote, Brand.Red, Brand.RedSoft, true) { Routes.annotate(it) },
    ToolEntry("Compress PDF", "Reduce file size", Icons.Default.Compress, Brand.Green, Brand.GreenSoft, true) { Routes.tool(PdfTool.COMPRESS, it) },
    ToolEntry("Merge PDF", "Combine multiple files", Icons.Default.Layers, Brand.Purple, Brand.PurpleSoft, false) { Routes.tool(PdfTool.MERGE) },
    ToolEntry("Split PDF", "Divide your PDF", Icons.Default.ContentCut, Brand.Orange, Brand.OrangeSoft, true) { Routes.tool(PdfTool.SPLIT, it) },
    ToolEntry("Convert to Word", "PDF to DOCX", Icons.Default.Description, Brand.Blue, Brand.BlueSoft, true) { Routes.convert(it, OutputFormat.WORD) },
    ToolEntry("Mask Content", "Hide private details before sharing", Icons.Default.VisibilityOff, Brand.Pink, Brand.PinkSoft, true) { Routes.mask(it) },
    ToolEntry("Sign PDF", "Draw and place your signature", Icons.Default.Draw, Brand.Teal, Brand.TealSoft, true) { Routes.annotate(it) },
    ToolEntry("Organize Pages", "Reorder, rotate, delete pages", Icons.Default.ViewAgenda, Brand.Purple, Brand.PurpleSoft, true) { Routes.editor(it) },
    ToolEntry("Protect PDF", "Add a password", Icons.Default.Password, Brand.Red, Brand.RedSoft, true) { Routes.tool(PdfTool.PROTECT, it) },
    ToolEntry("PDF to Images", "Save pages as JPG or PNG", Icons.Default.Collections, Brand.Orange, Brand.OrangeSoft, true) { Routes.convert(it, OutputFormat.JPG) },
    ToolEntry("PDF to Excel", "Extract tables to XLSX", Icons.Default.GridOn, Brand.Green, Brand.GreenSoft, true) { Routes.convert(it, OutputFormat.EXCEL) },
    ToolEntry("PDF to PowerPoint", "One slide per page", Icons.Default.Slideshow, Brand.Orange, Brand.OrangeSoft, true) { Routes.convert(it, OutputFormat.POWERPOINT) },
    ToolEntry("PDF to Text", "Extract text with OCR", Icons.AutoMirrored.Filled.TextSnippet, Brand.Blue, Brand.BlueSoft, true) { Routes.convert(it, OutputFormat.TEXT) },
    ToolEntry("Word to PDF", "DOCX to PDF", Icons.Default.PictureAsPdf, Brand.Red, Brand.RedSoft, false) { Routes.importConvert(ImportKind.WORD_TO_PDF) },
    ToolEntry("Text to PDF", "TXT to PDF", Icons.Default.TextFields, Brand.Teal, Brand.TealSoft, false) { Routes.importConvert(ImportKind.TEXT_TO_PDF) },
    ToolEntry("Image to Text", "Recognise text in a photo", Icons.Default.Badge, Brand.Purple, Brand.PurpleSoft, false) { Routes.importConvert(ImportKind.IMAGE_TO_TEXT) },
    ToolEntry("Page Numbers", "Number every page", Icons.Default.FormatListNumbered, Brand.Blue, Brand.BlueSoft, true) { Routes.tool(PdfTool.PAGE_NUMBERS, it) },
    ToolEntry("Watermark", "Stamp text across pages", Icons.Default.WaterDrop, Brand.Teal, Brand.TealSoft, true) { Routes.tool(PdfTool.WATERMARK, it) },
    ToolEntry("Scan Document", "Capture with the camera", Icons.Default.DocumentScanner, Brand.Blue, Brand.BlueSoft, false) { Routes.SCAN },
    ToolEntry("Docs Vault", "Encrypted, fingerprint-locked storage", Icons.Default.Lock, Brand.Blue, Brand.BlueSoft, false) { Routes.vault() },
)

/** Tools tab: "Tools & Features" list of coloured tool cards. */
@Composable
fun AllToolsScreen(onNavigate: (String) -> Unit, vm: ToolViewModel = viewModel(key = "alltools", factory = ToolViewModel.Factory)) {
    val library by vm.library.collectAsStateWithLifecycle()
    val settings by (vm.app as PaperVaultApp).container.settings.settings.collectAsStateWithLifecycle()
    var picking by remember { mutableStateOf<ToolEntry?>(null) }

    Column(Modifier.fillMaxSize().background(MaterialTheme.extra.background).statusBarsPadding()) {
        AppHeader(settings.initial, onSearch = { onNavigate(Routes.SEARCH) }, onProfile = { onNavigate(Routes.SETTINGS) })
        LazyColumn(contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 110.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { SectionTitle("Tools & Features", "Boost your productivity", Modifier.padding(start = 6.dp, bottom = 6.dp)) }
            itemsIndexed(entries, key = { _, e -> e.title }) { i, e ->
                SoftCard(Modifier.fillMaxWidth().enterUp((i * 35).coerceAtMost(350)), radius = 18.dp, onClick = { if (e.needsDoc) picking = e else onNavigate(e.route(-1)) }) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        ToolTile(e.icon, e.fg, e.bg)
                        Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                            Text(e.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.extra.ink)
                            Text(e.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.extra.inkSoft)
                        }
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.extra.ink)
                    }
                }
            }
        }
    }
    picking?.let { item ->
        DocumentPickerDialog("Choose a PDF", library, onDismiss = { picking = null }) { picking = null; onNavigate(item.route(it.id)) }
    }
}
