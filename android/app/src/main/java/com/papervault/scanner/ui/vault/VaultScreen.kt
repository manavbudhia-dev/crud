package com.papervault.scanner.ui.vault

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.papervault.scanner.data.Document
import com.papervault.scanner.ui.components.ConfirmDeleteDialog
import com.papervault.scanner.ui.findActivity
import com.papervault.scanner.ui.home.rememberDocumentScanner
import com.papervault.scanner.ui.tools.DocumentPickerDialog
import com.papervault.scanner.ui.tools.displayName
import com.papervault.scanner.util.ShareUtils
import com.papervault.scanner.util.formatDate
import com.papervault.scanner.util.formatSize
import com.papervault.scanner.vault.VaultState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultScreen(
    moveDocId: Long?,
    onBack: () -> Unit,
    onOpenDocument: (Long) -> Unit,
    vm: VaultViewModel = viewModel(factory = VaultViewModel.Factory),
) {
    SecureWindow()
    val state by vm.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    var settingsOpen by remember { mutableStateOf(false) }
    var moved by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(Unit) {
        vm.shareFile.collect {
            vm.vault.keepUnlockedForExternal()
            ShareUtils.shareFiles(context, listOf(it), "application/pdf")
        }
    }
    // "Move to vault" from a document: do it as soon as the vault is unlocked.
    LaunchedEffect(state, moveDocId) {
        if (state == VaultState.UNLOCKED && moveDocId != null && !moved) {
            moved = true
            vm.moveInById(moveDocId)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Docs Vault") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    if (state == VaultState.UNLOCKED) {
                        IconButton(onClick = { settingsOpen = true }) { Icon(Icons.Default.Settings, "Vault settings") }
                        IconButton(onClick = { vm.vault.lock() }) { Icon(Icons.Default.Lock, "Lock now") }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            when (state) {
                VaultState.NOT_SET_UP -> SetupView(vm)
                VaultState.LOCKED -> UnlockView(vm)
                VaultState.UNLOCKED -> VaultHome(vm, onOpenDocument)
            }
        }
    }
    if (settingsOpen) VaultSettingsDialog(vm) { settingsOpen = false }
}

// ---------------- setup ----------------

@Composable
private fun SetupView(vm: VaultViewModel) {
    val activity = LocalContext.current.findActivity() as FragmentActivity
    var pw by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    val canBio = remember { vm.vault.biometricAvailable() }
    var useBio by remember { mutableStateOf(canBio) }
    val valid = pw.length >= 6 && pw == pw2

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Default.Shield, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
        Text("Set up your vault", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Documents in the vault are encrypted on this device (AES-256). They are hidden from your library, can't be screenshotted, and need your password or fingerprint to open.",
            textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium,
        )
        PasswordField(pw, { pw = it }, "Vault password (min. 6 characters)")
        PasswordField(pw2, { pw2 = it }, "Confirm password", isError = pw2.isNotEmpty() && pw != pw2)
        if (canBio) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Fingerprint, null)
                Text("Unlock with fingerprint / face", Modifier.weight(1f).padding(start = 12.dp))
                Switch(checked = useBio, onCheckedChange = { useBio = it })
            }
        }
        Text(
            "There is no way to recover a forgotten password. If you forget it, the vault can only be reset, which deletes its documents.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center,
        )
        Button(onClick = {
            vm.setUp(pw) {
                if (useBio) enrollBiometric(activity, vm)
            }
        }, enabled = valid, modifier = Modifier.fillMaxWidth()) { Text("Create vault") }
    }
}

private fun enrollBiometric(activity: FragmentActivity, vm: VaultViewModel) {
    val cipher = runCatching { vm.vault.biometricEnrollCipher() }.getOrNull() ?: return
    showBiometricPrompt(activity, "Enable fingerprint unlock", "Confirm to use biometrics for the vault", cipher,
        onSuccess = vm::onBiometricEnrolled, onError = {})
}

// ---------------- unlock ----------------

@Composable
private fun UnlockView(vm: VaultViewModel) {
    val activity = LocalContext.current.findActivity() as FragmentActivity
    val error by vm.error.collectAsStateWithLifecycle()
    var pw by remember { mutableStateOf("") }
    var confirmReset by remember { mutableStateOf(false) }
    var lockout by remember { mutableLongStateOf(vm.vault.lockoutRemaining()) }
    val bio = vm.vault.biometricEnabled

    val promptBio = {
        val cipher = vm.vault.biometricUnlockCipher()
        if (cipher != null) {
            showBiometricPrompt(activity, "Unlock Docs Vault", "Use your fingerprint or face", cipher,
                onSuccess = vm::onBiometricUnlocked, onError = { vm.error.value = it })
        } else {
            vm.error.value = "Biometric unlock was turned off because your enrolled fingerprints changed. Use your password."
        }
    }
    LaunchedEffect(Unit) { if (bio) promptBio() }
    LaunchedEffect(error) {
        lockout = vm.vault.lockoutRemaining()
        while (lockout > 0) { delay(1000); lockout = vm.vault.lockoutRemaining() }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(24.dp))
        Icon(Icons.Default.Lock, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
        Text("Vault locked", style = MaterialTheme.typography.headlineSmall)
        PasswordField(pw, { pw = it; vm.error.value = null }, "Password", onDone = { if (pw.isNotEmpty() && lockout == 0L) vm.unlock(pw) })
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }
        if (lockout > 0) Text("Try again in ${lockout}s", color = MaterialTheme.colorScheme.error)
        Button(onClick = { vm.unlock(pw); pw = "" }, enabled = pw.isNotEmpty() && lockout == 0L, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.LockOpen, null); Text("Unlock", Modifier.padding(start = 8.dp))
        }
        if (bio) {
            OutlinedButton(onClick = promptBio, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Fingerprint, null); Text("Use fingerprint", Modifier.padding(start = 8.dp))
            }
        }
        TextButton(onClick = { confirmReset = true }) { Text("Forgot password?") }
    }
    if (confirmReset) {
        var typed by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset vault?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Your password can't be recovered. Resetting permanently deletes every document in the vault. Type DELETE to confirm.")
                    OutlinedTextField(typed, { typed = it }, singleLine = true)
                }
            },
            confirmButton = { TextButton(onClick = { confirmReset = false; vm.reset() }, enabled = typed == "DELETE") { Text("Reset vault") } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        )
    }
}

// ---------------- unlocked ----------------

@Composable
private fun VaultHome(vm: VaultViewModel, onOpenDocument: (Long) -> Unit) {
    val docs by vm.documents.collectAsStateWithLifecycle()
    val library by vm.library.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var addMenu by remember { mutableStateOf(false) }
    var pickLibrary by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Document?>(null) }

    val scan = rememberDocumentScanner(onResult = vm::importScan, onError = { scope.launch { vm.error.value = it } })
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(100)) { vm.importPhotos(it) }
    val pdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.importPdf(it, displayName(context, it)?.substringBeforeLast('.')) }
    }

    Box(Modifier.fillMaxSize()) {
        if (docs.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Shield, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(8.dp))
                Text("Your vault is empty", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Add ID cards, passports, bank statements, certificates — anything private.",
                    textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(docs, key = { it.id }) { d ->
                    VaultRow(d, onClick = { onOpenDocument(d.id) }, onShare = { vm.share(d) }, onMoveOut = { vm.moveOut(d) }, onDelete = { deleting = d })
                }
            }
        }
        Box(Modifier.align(Alignment.BottomEnd).padding(20.dp)) {
            FloatingActionButton(onClick = { addMenu = true }) { Icon(Icons.Default.Add, "Add to vault") }
            DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                DropdownMenuItem(text = { Text("Scan into vault") }, leadingIcon = { Icon(Icons.Default.DocumentScanner, null) }, onClick = {
                    addMenu = false; vm.vault.keepUnlockedForExternal(); scan()
                })
                DropdownMenuItem(text = { Text("Photos into vault") }, leadingIcon = { Icon(Icons.Default.Image, null) }, onClick = {
                    addMenu = false; vm.vault.keepUnlockedForExternal()
                    photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                })
                DropdownMenuItem(text = { Text("Import PDF into vault") }, leadingIcon = { Icon(Icons.Default.FileOpen, null) }, onClick = {
                    addMenu = false; vm.vault.keepUnlockedForExternal(); pdf.launch(arrayOf("application/pdf"))
                })
                DropdownMenuItem(text = { Text("Move from library") }, leadingIcon = { Icon(Icons.Default.SwapHoriz, null) }, onClick = {
                    addMenu = false; pickLibrary = true
                })
            }
        }
    }
    if (pickLibrary) {
        DocumentPickerDialog("Move to vault", library, onDismiss = { pickLibrary = false }) { pickLibrary = false; vm.moveIn(it) }
    }
    deleting?.let { d -> ConfirmDeleteDialog(d.title, onDismiss = { deleting = null }) { vm.delete(d); deleting = null } }
}

@Composable
private fun VaultRow(d: Document, onClick: () -> Unit, onShare: () -> Unit, onMoveOut: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Lock, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(d.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${d.pageCount} pages · ${formatSize(d.sizeBytes)} · ${formatDate(d.updatedAt)}", style = MaterialTheme.typography.bodySmall)
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Share (unencrypted copy)") }, onClick = { menu = false; onShare() })
                    DropdownMenuItem(text = { Text("Move to library") }, onClick = { menu = false; onMoveOut() })
                    DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

// ---------------- settings ----------------

@Composable
private fun VaultSettingsDialog(vm: VaultViewModel, onDismiss: () -> Unit) {
    val activity = LocalContext.current.findActivity() as FragmentActivity
    var bio by remember { mutableStateOf(vm.vault.biometricEnabled) }
    var autoLock by remember { mutableStateOf(vm.vault.autoLockSeconds) }
    var changing by remember { mutableStateOf(false) }
    val options = listOf(0 to "Immediately", 30 to "After 30 seconds", 60 to "After 1 minute", 300 to "After 5 minutes")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Vault settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (vm.vault.biometricAvailable()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Fingerprint / face unlock", Modifier.weight(1f))
                        Switch(checked = bio, onCheckedChange = {
                            if (it) { enrollBiometric(activity, vm); bio = true } else { vm.vault.disableBiometric(); bio = false }
                        })
                    }
                }
                Text("Lock when the app is in the background", style = MaterialTheme.typography.labelLarge)
                options.forEach { (secs, label) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.RadioButton(selected = autoLock == secs, onClick = { autoLock = secs; vm.vault.autoLockSeconds = secs })
                        Text(label)
                    }
                }
                OutlinedButton(onClick = { changing = true }, Modifier.fillMaxWidth()) { Text("Change password") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
    if (changing) {
        var old by remember { mutableStateOf("") }
        var new by remember { mutableStateOf("") }
        var new2 by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { changing = false },
            title = { Text("Change password") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PasswordField(old, { old = it }, "Current password")
                    PasswordField(new, { new = it }, "New password (min. 6)")
                    PasswordField(new2, { new2 = it }, "Confirm new password", isError = new2.isNotEmpty() && new != new2)
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.changePassword(old, new) { changing = false } }, enabled = old.isNotEmpty() && new.length >= 6 && new == new2) { Text("Change") }
            },
            dismissButton = { TextButton(onClick = { changing = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PasswordField(value: String, onChange: (String) -> Unit, label: String, isError: Boolean = false, onDone: (() -> Unit)? = null) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        isError = isError,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = if (onDone != null) ImeAction.Done else ImeAction.Next),
        keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
        modifier = Modifier.fillMaxWidth(),
    )
}
