package com.papervault.scanner.ui.main

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.papervault.scanner.Launch
import com.papervault.scanner.data.DocumentRepository
import com.papervault.scanner.data.DocumentSource
import com.papervault.scanner.data.PasswordRequiredException
import com.papervault.scanner.pdf.WrongPasswordException
import com.papervault.scanner.ui.components.PasswordDialog
import com.papervault.scanner.ui.container
import com.papervault.scanner.ui.tools.displayName
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** Imports files that were shared to / opened with PaperVault. PDFs one by one; images become one PDF. */
class IncomingViewModel(private val app: android.app.Application, private val repo: DocumentRepository) : ViewModel() {
    val busy = MutableStateFlow(false)
    val locked = MutableStateFlow<PasswordRequiredException?>(null)
    private val _opened = Channel<Long>(Channel.BUFFERED)
    val opened = _opened.receiveAsFlow()
    private val _errors = Channel<String>(Channel.BUFFERED)
    val errors = _errors.receiveAsFlow()

    fun import(launch: Launch.Import) = viewModelScope.launch {
        busy.value = true
        try {
            val pdfs = launch.uris.filterIndexed { i, u -> isPdf(launch.mimes[i], u) }
            val images = launch.uris.filterIndexed { i, u -> launch.mimes[i]?.startsWith("image/") == true && !isPdf(launch.mimes[i], u) }
            var last: Long? = null
            for (u in pdfs) {
                try {
                    last = repo.importPdf(u, DocumentSource.IMPORT, displayName(app, u)?.substringBeforeLast('.'))
                } catch (e: PasswordRequiredException) {
                    locked.value = e
                }
            }
            if (images.isNotEmpty()) last = repo.createFromImages(images)
            if (pdfs.isEmpty() && images.isEmpty()) _errors.send("PaperVault can open PDFs and images")
            last?.let { _opened.send(it) }
        } catch (e: Exception) {
            _errors.send(e.message ?: "Could not open the file")
        } finally {
            busy.value = false
        }
    }

    fun unlock(password: String) = viewModelScope.launch {
        val l = locked.value ?: return@launch
        locked.value = null
        busy.value = true
        try {
            _opened.send(repo.importLocked(l.lockedFile, password, l.title))
        } catch (e: WrongPasswordException) {
            locked.value = l
            _errors.send("Incorrect password")
        } catch (e: Exception) {
            _errors.send(e.message ?: "Could not unlock PDF")
        } finally {
            busy.value = false
        }
    }

    fun cancel() { locked.value?.lockedFile?.delete(); locked.value = null }

    private fun isPdf(mime: String?, uri: Uri) =
        mime == "application/pdf" || (mime == null || mime == "application/octet-stream") && uri.toString().endsWith(".pdf", true)

    companion object {
        val Factory = viewModelFactory { initializer { IncomingViewModel(this[APPLICATION_KEY]!!, container.documents) } }
    }
}

/** Consumes pending launches: navigates for routes, imports for shared files. */
@Composable
fun LaunchHandler(onRoute: (String) -> Unit, onOpenDocument: (Long) -> Unit, onError: (String) -> Unit, vm: IncomingViewModel = viewModel(factory = IncomingViewModel.Factory)) {
    val router = (androidx.compose.ui.platform.LocalContext.current.applicationContext as com.papervault.scanner.PaperVaultApp).container.launches
    val pending by router.pending.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val locked by vm.locked.collectAsStateWithLifecycle()

    LaunchedEffect(pending) {
        when (val l = router.consume()) {
            is Launch.Route -> onRoute(l.route)
            is Launch.Import -> vm.import(l)
            null -> Unit
        }
    }
    LaunchedEffect(Unit) { vm.opened.collect(onOpenDocument) }
    LaunchedEffect(Unit) { vm.errors.collect(onError) }

    if (busy) {
        Box(Modifier.fillMaxSize().background(Color(0x66000000)), contentAlignment = Alignment.Center) {
            Column(
                Modifier.background(MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.shapes.large).padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                CircularProgressIndicator()
                Text("Importing…")
            }
        }
    }
    locked?.let { l -> PasswordDialog(title = "\"${l.title}\" is password-protected", onDismiss = vm::cancel, onConfirm = vm::unlock) }
}

/** Opens the system file picker straight away (used by the widget's Import button). */
@Composable
fun ImportPickerRoute(onDone: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val router = (context.applicationContext as com.papervault.scanner.PaperVaultApp).container.launches
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        onDone()
        if (uris.isNotEmpty()) router.submit(Launch.Import(uris, uris.map { context.contentResolver.getType(it) }))
    }
    LaunchedEffect(Unit) { picker.launch(arrayOf("application/pdf", "image/*")) }
}
