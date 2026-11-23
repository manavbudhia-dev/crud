package com.papervault.scanner.ui.home

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.papervault.scanner.ui.Routes
import com.papervault.scanner.ui.components.AppHeader
import com.papervault.scanner.ui.components.BlobBackground
import com.papervault.scanner.ui.components.HeroIllustration
import com.papervault.scanner.ui.components.PasswordDialog
import com.papervault.scanner.ui.components.SoftCard
import com.papervault.scanner.ui.tools.displayName
import com.papervault.scanner.ui.theme.extra

/** Home tab: hero, primary Scan button, quick actions, and the latest documents. */
@Composable
fun HomeScreen(
    onOpenDocument: (Long) -> Unit,
    onNavigate: (String) -> Unit,
    vm: HomeViewModel = viewModel(factory = HomeViewModel.Factory),
) {
    val context = LocalContext.current
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val docs by vm.documents.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val locked by vm.locked.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        vm.events.collect { e ->
            when (e) {
                is HomeEvent.Opened -> onOpenDocument(e.id)
                is HomeEvent.Error -> snackbar.showSnackbar(e.message)
            }
        }
    }
    val pickPhotos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(100)) { vm.onPhotosPicked(it) }
    val pickPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { vm.onPdfPicked(it, displayName(context, it)) }
    }

    BlobBackground(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState())) {
            AppHeader(prefs.initial, onSearch = { onNavigate(Routes.SEARCH) }, onProfile = { onNavigate(Routes.SETTINGS) })
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 24.dp))

            Column(Modifier.padding(horizontal = 28.dp).padding(top = 28.dp)) {
                Text("Your documents,\nin one place", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.extra.ink)
                Spacer(Modifier.height(12.dp))
                Text("Scan, manage and access\nyour PDFs easily.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.extra.inkSoft)
            }

            HeroIllustration(Modifier.fillMaxWidth().height(200.dp).padding(top = 8.dp))

            Button(
                onClick = { onNavigate(Routes.SCAN) },
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 30.dp, vertical = 16.dp),
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .shadow(14.dp, RoundedCornerShape(50), ambientColor = MaterialTheme.colorScheme.primary, spotColor = MaterialTheme.colorScheme.primary),
            ) {
                Icon(Icons.Default.DocumentScanner, null, Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Text("Scan Document", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.width(10.dp))
                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(20.dp))
            }

            SoftCard(Modifier.padding(horizontal = 18.dp, vertical = 24.dp).fillMaxWidth(), radius = 26.dp) {
                Row(Modifier.height(IntrinsicSize.Min).padding(vertical = 18.dp)) {
                    QuickAction(Icons.Default.Image, "Photo\nto PDF", Modifier.weight(1f)) {
                        pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }
                    VDivider()
                    QuickAction(Icons.Default.NoteAdd, "Import\nPDF", Modifier.weight(1f)) { pickPdf.launch(arrayOf("application/pdf")) }
                    VDivider()
                    QuickAction(Icons.Default.Lock, "Vault", Modifier.weight(1f)) { onNavigate(Routes.vault()) }
                }
            }

            val recent = docs.orEmpty().take(3)
            if (recent.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Recent", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.extra.ink, modifier = Modifier.weight(1f))
                    TextButton(onClick = { onNavigate(com.papervault.scanner.ui.main.Tab.DOCUMENTS.route) }) { Text("See all") }
                }
                Column(Modifier.padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    recent.forEach { d -> DocumentListItem(d, onClick = { onOpenDocument(d.id) }, onStar = { vm.toggleStar(d) }, onMore = null) }
                }
            }
            Spacer(Modifier.height(120.dp))
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp))
    }

    locked?.let { l ->
        PasswordDialog(title = "\"${l.title}\" is password-protected", onDismiss = vm::cancelUnlock, onConfirm = vm::unlockImport)
    }
}

@Composable
private fun QuickAction(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clickable(onClick = onClick).padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(34.dp))
        Spacer(Modifier.height(10.dp))
        Text(label, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.extra.ink, textAlign = TextAlign.Center)
    }
}

@Composable
private fun VDivider() = Box(Modifier.width(1.dp).fillMaxHeight().padding(vertical = 8.dp).background(MaterialTheme.colorScheme.outlineVariant))
