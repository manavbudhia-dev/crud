package com.papervault.scanner.ui.home

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.papervault.scanner.data.Document
import com.papervault.scanner.data.DocumentRepository
import com.papervault.scanner.data.DocumentSource
import com.papervault.scanner.data.PasswordRequiredException
import com.papervault.scanner.pdf.WrongPasswordException
import com.papervault.scanner.ui.container
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface HomeEvent {
    data class Opened(val id: Long) : HomeEvent
    data class Error(val message: String) : HomeEvent
}

class HomeViewModel(private val repo: DocumentRepository) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    val documents: StateFlow<List<Document>?> = combine(repo.observeLibrary(), _query) { docs, q ->
        if (q.isBlank()) docs else docs.filter { it.title.contains(q.trim(), ignoreCase = true) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Set when an imported PDF is password-protected; UI asks for the password. */
    private val _locked = MutableStateFlow<PasswordRequiredException?>(null)
    val locked: StateFlow<PasswordRequiredException?> = _locked.asStateFlow()

    fun unlockImport(password: String) {
        val l = _locked.value ?: return
        _locked.value = null
        viewModelScope.launch {
            _busy.value = true
            try {
                _events.send(HomeEvent.Opened(repo.importLocked(l.lockedFile, password, l.title)))
            } catch (e: WrongPasswordException) {
                _locked.value = l
                _events.send(HomeEvent.Error("Incorrect password"))
            } catch (e: Exception) {
                l.lockedFile.delete()
                _events.send(HomeEvent.Error(e.message ?: "Could not unlock PDF"))
            } finally {
                _busy.value = false
            }
        }
    }

    fun cancelUnlock() {
        _locked.value?.lockedFile?.delete()
        _locked.value = null
    }

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _events = Channel<HomeEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    fun setQuery(q: String) { _query.value = q }

    fun onScanned(pdf: Uri) = work { repo.importPdf(pdf, DocumentSource.SCAN) }
    fun onPhotosPicked(uris: List<Uri>) { if (uris.isNotEmpty()) work { repo.createFromImages(uris) } }
    fun onPdfPicked(uri: Uri, name: String?) = work {
        repo.importPdf(uri, DocumentSource.IMPORT, name?.removeSuffix(".pdf")?.removeSuffix(".PDF"))
    }

    fun rename(doc: Document, title: String) = viewModelScope.launch { repo.rename(doc, title) }
    fun delete(doc: Document) = viewModelScope.launch { repo.delete(doc) }

    private fun work(block: suspend () -> Long) {
        viewModelScope.launch {
            _busy.value = true
            try {
                _events.send(HomeEvent.Opened(block()))
            } catch (e: PasswordRequiredException) {
                _locked.value = e
            } catch (e: Exception) {
                _events.send(HomeEvent.Error(e.message ?: "Something went wrong"))
            } finally {
                _busy.value = false
            }
        }
    }

    companion object {
        val Factory = viewModelFactory { initializer { HomeViewModel(container.documents) } }
    }
}
