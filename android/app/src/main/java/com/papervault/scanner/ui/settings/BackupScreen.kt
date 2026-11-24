package com.papervault.scanner.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.papervault.scanner.PaperVaultApp
import com.papervault.scanner.backup.WrongBackupPasswordException
import com.papervault.scanner.ui.components.SoftCard
import com.papervault.scanner.ui.components.ToolTile
import com.papervault.scanner.ui.components.enterUp
import com.papervault.scanner.ui.components.relativeTime
import com.papervault.scanner.ui.theme.Brand
import com.papervault.scanner.ui.theme.extra
import com.papervault.scanner.util.formatSize
import com.papervault.scanner.vault.VaultState
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private sealed interface BackupState {
    data object Idle : BackupState
    data class Working(val message: String, val progress: Float) : BackupState
    data class Done(val message: String) : BackupState
    data class Failed(val message: String) : BackupState
}

@Composable
fun BackupScreen(onBack: () -> Unit, onOpenVault: () -> Unit) {
    val container = (LocalContext.current.applicationContext as PaperVaultApp).container
    val manager = container.backup
    val vaultState by container.vault.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<BackupState>(BackupState.Idle) }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var includeVault by remember { mutableStateOf(true) }
    var restoreUri by remember { mutableStateOf<Uri?>(null) }
    var lastBackup by remember { mutableStateOf(manager.lastBackupAt) }
    val vaultUnlocked = vaultState == VaultState.UNLOCKED

    val createFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val pw = password.toCharArray()
        scope.launch {
            state = BackupState.Working("Preparing…", 0f)
            state = try {
                val r = manager.backup(uri, pw, includeVault && vaultUnlocked) { m, p -> state = BackupState.Working(m, p) }
                lastBackup = manager.lastBackupAt
                password = ""; confirm = ""
                BackupState.Done(
                    "Backed up ${r.documents} document${if (r.documents == 1) "" else "s"}" +
                        (if (r.vaultDocuments > 0) ", ${r.vaultDocuments} from the vault" else "") +
                        " · ${formatSize(r.bytes)}",
                )
            } catch (e: Exception) {
                BackupState.Failed(e.message ?: "Backup failed")
            }
        }
    }
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> restoreUri = uri }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.extra.background).statusBarsPadding().verticalScroll(rememberScrollState()),
    ) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text("Backup & restore", style = MaterialTheme.typography.titleLarge)
        }
        Text(
            "Your backup is one file encrypted with a password you choose (AES-256). In the save screen, pick Google Drive to keep it in the cloud — or any folder, USB drive or email.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.extra.inkSoft,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
        )

        AnimatedContent(state, label = "backupState") { s ->
            when (s) {
                is BackupState.Working -> SoftCard(Modifier.padding(18.dp).fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(s.message, style = MaterialTheme.typography.titleSmall)
                        LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
                    }
                }
                is BackupState.Done -> StatusCard(s.message, isError = false) { state = BackupState.Idle }
                is BackupState.Failed -> StatusCard(s.message, isError = true) { state = BackupState.Idle }
                BackupState.Idle -> Spacer(Modifier.height(4.dp))
            }
        }

        // ---- Backup
        SoftCard(Modifier.padding(horizontal = 18.dp, vertical = 8.dp).fillMaxWidth().enterUp(0)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ToolTile(Icons.Default.CloudUpload, Brand.Blue, Brand.BlueSoft)
                    Column(Modifier.padding(start = 14.dp)) {
                        Text("Create backup", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (lastBackup > 0) "Last backup ${relativeTime(lastBackup)}" else "No backup yet",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.extra.inkSoft,
                        )
                    }
                }
                PasswordInput(password, { password = it }, "Backup password (min. 8 characters)")
                PasswordInput(confirm, { confirm = it }, "Confirm password", isError = confirm.isNotEmpty() && confirm != password)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Include Docs Vault", style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (vaultUnlocked) "Vault documents are added to the encrypted backup" else "Unlock the vault to include it",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.extra.inkSoft,
                        )
                    }
                    if (vaultUnlocked) Switch(checked = includeVault, onCheckedChange = { includeVault = it })
                    else if (vaultState == VaultState.LOCKED) TextButton(onClick = onOpenVault) { Text("Unlock") }
                }
                Text(
                    "There's no way to recover this password. Without it the backup can't be opened.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                )
                Button(
                    onClick = {
                        container.vault.keepUnlockedForExternal()
                        createFile.launch("PaperVault-backup-${SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())}.pvbackup")
                    },
                    enabled = password.length >= 8 && password == confirm && state !is BackupState.Working,
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(50),
                ) { Text("Back up now") }
            }
        }

        // ---- Restore
        SoftCard(Modifier.padding(horizontal = 18.dp, vertical = 8.dp).fillMaxWidth().enterUp(80)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ToolTile(Icons.Default.Restore, Brand.Green, Brand.GreenSoft)
                    Column(Modifier.padding(start = 14.dp)) {
                        Text("Restore from backup", style = MaterialTheme.typography.titleMedium)
                        Text("Adds the backup's documents; nothing here is deleted", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.extra.inkSoft)
                    }
                }
                if (vaultState != VaultState.UNLOCKED) Text(
                    "To restore vault documents, set up or unlock your vault first. Otherwise they're skipped.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.extra.inkSoft,
                )
                OutlinedButton(
                    onClick = { container.vault.keepUnlockedForExternal(); openFile.launch(arrayOf("*/*")) },
                    enabled = state !is BackupState.Working, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(50),
                ) { Text("Choose backup file") }
            }
        }
        Spacer(Modifier.height(32.dp))
    }

    restoreUri?.let { uri ->
        var pw by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { restoreUri = null },
            title = { Text("Backup password") },
            text = { PasswordInput(pw, { pw = it }, "Password") },
            confirmButton = {
                TextButton(enabled = pw.isNotEmpty(), onClick = {
                    restoreUri = null
                    val chars = pw.toCharArray()
                    scope.launch {
                        state = BackupState.Working("Decrypting…", 0f)
                        state = try {
                            val r = manager.restore(uri, chars) { m, p -> state = BackupState.Working(m, p) }
                            BackupState.Done(buildString {
                                append("Restored ${r.restored} document${if (r.restored == 1) "" else "s"}")
                                if (r.vaultRestored > 0) append(" and ${r.vaultRestored} vault document${if (r.vaultRestored == 1) "" else "s"}")
                                if (r.skippedDuplicates > 0) append(". ${r.skippedDuplicates} already here")
                                if (r.vaultSkipped > 0) append(". ${r.vaultSkipped} vault document${if (r.vaultSkipped == 1) "" else "s"} skipped (vault locked)")
                            })
                        } catch (e: WrongBackupPasswordException) {
                            BackupState.Failed(e.message!!)
                        } catch (e: Exception) {
                            BackupState.Failed(e.message ?: "Restore failed")
                        }
                    }
                }) { Text("Restore") }
            },
            dismissButton = { TextButton(onClick = { restoreUri = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun StatusCard(message: String, isError: Boolean, onDismiss: () -> Unit) {
    SoftCard(Modifier.padding(18.dp).fillMaxWidth()) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(message, Modifier.weight(1f), color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.extra.ink)
            TextButton(onClick = onDismiss) { Text("OK") }
        }
    }
}

@Composable
private fun PasswordInput(value: String, onChange: (String) -> Unit, label: String, isError: Boolean = false) =
    OutlinedTextField(
        value, onChange, label = { Text(label) }, singleLine = true, isError = isError,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
