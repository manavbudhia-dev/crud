package com.papervault.scanner.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Compress
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.papervault.scanner.BuildConfig
import com.papervault.scanner.PaperVaultApp
import com.papervault.scanner.data.PageSize
import com.papervault.scanner.data.ThemeMode
import com.papervault.scanner.ui.components.Avatar
import com.papervault.scanner.ui.components.CrownIcon
import com.papervault.scanner.ui.components.SoftCard
import com.papervault.scanner.ui.theme.extra

private enum class SettingsDialog { PROFILE, THEME, LANGUAGE, PAGE_SIZE, PREMIUM, ABOUT, OCR, COMPRESSION }

@Composable
fun SettingsScreen(onBack: () -> Unit, onOpenVault: () -> Unit, onOpenBackup: () -> Unit = {}) {
    val repo = (LocalContext.current.applicationContext as PaperVaultApp).container.settings
    val s by repo.settings.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<SettingsDialog?>(null) }

    Column(Modifier.fillMaxSize().background(MaterialTheme.extra.background).statusBarsPadding().verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = MaterialTheme.extra.ink) }
            Text("Settings", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.extra.ink, modifier = Modifier.weight(1f).padding(start = 8.dp))
            IconButton(onClick = { repo.update { it.copy(notifications = !it.notifications) } }) {
                Icon(Icons.Outlined.Notifications, "Notifications", tint = MaterialTheme.extra.ink)
            }
        }

        // Profile
        Row(
            Modifier.fillMaxWidth().clickable { dialog = SettingsDialog.PROFILE }.padding(horizontal = 24.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(s.initial, size = 62.dp)
            Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                Text(s.profileName.ifBlank { "Set up your profile" }, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.extra.ink)
                Text(s.profileEmail.ifBlank { "Add your name and email" }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.extra.inkSoft)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.extra.ink)
        }

        // Premium
        Row(
            Modifier.padding(horizontal = 18.dp, vertical = 8.dp).fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(MaterialTheme.extra.premium)
                .padding(horizontal = 16.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(46.dp).clip(RoundedCornerShape(50)).background(Color(0xFFFFE6C7)), contentAlignment = Alignment.Center) {
                CrownIcon(Modifier.size(30.dp))
            }
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text("Go Premium", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.extra.ink)
                Text("Unlock advanced features", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.extra.ink)
            }
            Button(onClick = { dialog = SettingsDialog.PREMIUM }, shape = RoundedCornerShape(50), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)) {
                Text("Upgrade")
            }
        }

        GroupTitle("General")
        SoftCard(Modifier.padding(horizontal = 18.dp).fillMaxWidth(), radius = 18.dp) {
            SettingRow(Icons.Outlined.Palette, "Appearance", s.theme.short()) { dialog = SettingsDialog.THEME }
            RowDivider()
            SettingRow(Icons.Outlined.Language, "Language", "English") { dialog = SettingsDialog.LANGUAGE }
            RowDivider()
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Notifications, null, tint = MaterialTheme.extra.ink)
                Text("Notifications", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.extra.ink, modifier = Modifier.weight(1f).padding(start = 16.dp))
                Switch(
                    checked = s.notifications, onCheckedChange = { v -> repo.update { it.copy(notifications = v) } },
                    colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary),
                )
            }
            RowDivider()
            SettingRow(Icons.Outlined.Description, "Photo to PDF page size", s.pageSize.label) { dialog = SettingsDialog.PAGE_SIZE }
            RowDivider()
            SettingRow(Icons.Outlined.Compress, "PDF compression", s.compression.label) { dialog = SettingsDialog.COMPRESSION }
            RowDivider()
            SettingRow(Icons.Outlined.Translate, "Text recognition language", s.ocrScript.label) { dialog = SettingsDialog.OCR }
        }

        GroupTitle("Security")
        SoftCard(Modifier.padding(horizontal = 18.dp).fillMaxWidth(), radius = 18.dp) {
            SettingRow(Icons.Outlined.Lock, "Docs Vault", "Password & fingerprint", onOpenVault)
            RowDivider()
            SettingRow(Icons.Outlined.CloudUpload, "Backup & restore", "Encrypted", onOpenBackup)
        }

        GroupTitle("About")
        SoftCard(Modifier.padding(horizontal = 18.dp).fillMaxWidth(), radius = 18.dp) {
            SettingRow(Icons.Outlined.Info, "About PaperVault", null) { dialog = SettingsDialog.ABOUT }
            RowDivider()
            SettingRow(Icons.Outlined.Shield, "Privacy", "On-device") { dialog = SettingsDialog.ABOUT }
        }
        Spacer(Modifier.height(32.dp))
    }

    when (dialog) {
        SettingsDialog.PROFILE -> ProfileDialog(s.profileName, s.profileEmail, onDismiss = { dialog = null }) { n, e ->
            repo.update { it.copy(profileName = n.trim(), profileEmail = e.trim()) }; dialog = null
        }
        SettingsDialog.THEME -> ChoiceDialog("Appearance", ThemeMode.entries, s.theme, { it.label }, { dialog = null }) { t ->
            repo.update { it.copy(theme = t) }; dialog = null
        }
        SettingsDialog.PAGE_SIZE -> ChoiceDialog("Photo to PDF page size", PageSize.entries, s.pageSize, { it.label }, { dialog = null }) { p ->
            repo.update { it.copy(pageSize = p) }; dialog = null
        }
        SettingsDialog.COMPRESSION -> ChoiceDialog(
            "Compress new PDFs", com.papervault.scanner.pdf.compress.CompressionLevel.entries, s.compression,
            { "${it.label} — ${it.description}" }, { dialog = null },
        ) { c -> repo.update { it.copy(compression = c) }; dialog = null }
        SettingsDialog.OCR -> ChoiceDialog("Text recognition language", com.papervault.scanner.convert.OcrScript.entries, s.ocrScript, { it.languages }, { dialog = null }) { o ->
            repo.update { it.copy(ocrScript = o) }; dialog = null
        }
        SettingsDialog.LANGUAGE -> ChoiceDialog("Language", listOf("English"), "English", { it }, { dialog = null }) { dialog = null }
        SettingsDialog.PREMIUM -> InfoDialog("PaperVault Premium", "All features are currently free on this device. Premium plans aren't available yet.") { dialog = null }
        SettingsDialog.ABOUT -> InfoDialog(
            "About PaperVault",
            "Version ${BuildConfig.VERSION_NAME}\n\nScanning, OCR, conversion and encryption all run on your device. Documents stay in the app's private storage and only leave it when you share them.",
        ) { dialog = null }
        null -> Unit
    }
}

private fun ThemeMode.short() = when (this) { ThemeMode.SYSTEM -> "System"; ThemeMode.LIGHT -> "Light"; ThemeMode.DARK -> "Dark" }

@Composable
private fun GroupTitle(text: String) =
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.extra.ink, modifier = Modifier.padding(start = 24.dp, top = 22.dp, bottom = 10.dp))

@Composable
private fun RowDivider() = HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)

@Composable
private fun SettingRow(icon: ImageVector, title: String, value: String?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.extra.ink)
        Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.extra.ink, modifier = Modifier.weight(1f).padding(start = 16.dp))
        if (value != null) Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.extra.inkSoft)
        Spacer(Modifier.width(6.dp))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.extra.inkSoft)
    }
}

@Composable
private fun <T> ChoiceDialog(title: String, options: List<T>, selected: T, label: (T) -> String, onDismiss: () -> Unit, onPick: (T) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { o ->
                    Row(Modifier.fillMaxWidth().clickable { onPick(o) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = o == selected, onClick = null)
                        Text(label(o), Modifier.padding(start = 12.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun InfoDialog(title: String, text: String, onDismiss: () -> Unit) = AlertDialog(
    onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(text) },
    confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
)

@Composable
private fun ProfileDialog(name: String, email: String, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var n by remember { mutableStateOf(name) }
    var e by remember { mutableStateOf(email) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Profile") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(n, { n = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(e, { e = it }, label = { Text("Email") }, singleLine = true)
                Text("Stored only on this device.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.extra.inkSoft)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(n, e) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
