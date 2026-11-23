package com.papervault.scanner.ui.vault

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.papervault.scanner.data.Document
import com.papervault.scanner.data.DocumentRepository
import com.papervault.scanner.data.DocumentSource
import com.papervault.scanner.data.PasswordRequiredException
import com.papervault.scanner.ui.container
import com.papervault.scanner.vault.LockedOutException
import com.papervault.scanner.vault.VaultManager
import com.papervault.scanner.vault.WrongVaultPasswordException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.crypto.Cipher

class VaultViewModel(val vault: VaultManager, private val repo: DocumentRepository) : ViewModel() {

    val state = vault.state
    val documents: StateFlow<List<Document>> = vault.documents.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val library: StateFlow<List<Document>> = repo.observeLibrary().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val busy = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()
    private val _shareFile = Channel<File>(Channel.BUFFERED)
    val shareFile = _shareFile.receiveAsFlow()

    fun setUp(password: String, onDone: () -> Unit) = launch {
        vault.setUp(password.toCharArray())
        onDone()
    }

    fun unlock(password: String) = launch {
        try {
            vault.unlockWithPassword(password.toCharArray())
            error.value = null
        } catch (e: WrongVaultPasswordException) {
            val left = 5 - vault.failedAttempts()
            error.value = if (left > 0) "Incorrect password. $left attempt${if (left == 1) "" else "s"} before a timeout." else "Incorrect password."
        } catch (e: LockedOutException) {
            error.value = e.message
        }
    }

    fun onBiometricUnlocked(cipher: Cipher) = launch { vault.finishBiometricUnlock(cipher) }
    fun onBiometricEnrolled(cipher: Cipher) = launch {
        vault.finishBiometricEnroll(cipher)
        _messages.send("Fingerprint unlock enabled")
    }

    fun changePassword(old: String, new: String, onDone: () -> Unit) = launch {
        try {
            vault.changePassword(old.toCharArray(), new.toCharArray())
            _messages.send("Password changed")
            onDone()
        } catch (e: WrongVaultPasswordException) {
            _messages.send("Current password is incorrect")
        }
    }

    fun moveIn(doc: Document) = launch { vault.moveIn(doc); _messages.send("\"${doc.title}\" moved to the vault") }
    fun moveInById(id: Long) = launch { repo.get(id)?.takeIf { !it.inVault }?.let { vault.moveIn(it); _messages.send("\"${it.title}\" moved to the vault") } }
    fun moveOut(doc: Document) = launch { vault.moveOut(doc); _messages.send("\"${doc.title}\" moved to your library") }
    fun delete(doc: Document) = launch { repo.delete(doc) }
    fun share(doc: Document) = launch { _shareFile.send(vault.exportForSharing(doc)) }

    /** Import straight into the vault: create the PDF, then encrypt it (plaintext is deleted). */
    fun importScan(uri: Uri) = launch { vault.moveIn(repo.get(repo.importPdf(uri, DocumentSource.SCAN))!!) }
    fun importPdf(uri: Uri, name: String?) = launch { vault.moveIn(repo.get(repo.importPdf(uri, DocumentSource.IMPORT, name))!!) }
    fun importPhotos(uris: List<Uri>) { if (uris.isNotEmpty()) launch { vault.moveIn(repo.get(repo.createFromImages(uris))!!) } }

    fun reset() = launch { vault.reset(documents.value) }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch {
            busy.value = true
            try {
                block()
            } catch (e: PasswordRequiredException) {
                e.lockedFile.delete()
                _messages.send("Remove the PDF's password first (import it to the library), then move it to the vault")
            } catch (e: Exception) {
                _messages.send(e.message ?: "Something went wrong")
            } finally {
                busy.value = false
            }
        }
    }

    companion object {
        val Factory = viewModelFactory { initializer { VaultViewModel(container.vault, container.documents) } }
    }
}
