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
import com.papervault.scanner.data.SettingsRepository
import com.papervault.scanner.data.SortOrder
import com.papervault.scanner.pdf.WrongPasswordException
import com.papervault.scanner.ui.container
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface HomeEvent {
    data class Opened(val id: Long) : HomeEvent
    data class Error(val message: String) : HomeEvent
}

/** Library filter: everything, starred only, or one folder. */
sealed interface LibraryFilter {
    data object All : LibraryFilter
    data object Starred : LibraryFilter
    data object Recent : LibraryFilter
    data class Folder(val name: String) : LibraryFilter
}

class HomeViewModel(private val repo: DocumentRepository, private val settings: SettingsRepository) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _filter = MutableStateFlow<LibraryFilter>(LibraryFilter.All)
    val filter: StateFlow<LibraryFilter> = _filter.asStateFlow()

    val sort: StateFlow<SortOrder> = settings.settings.map { it.sort }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), settings.settings.value.sort)

    val folders: StateFlow<List<String>> = repo.observeFolders()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val documents: StateFlow<List<Document>?> = combine(repo.observeLibrary(), _query, _filter, settings.settings) { docs, q, f, s ->
        docs.asSequence()
            .filter { q.isBlank() || it.title.contains(q.trim(), ignoreCase = true) }
            .filter {
                when (f) {
                    LibraryFilter.All -> true
                    LibraryFilter.Starred -> it.starred
                    LibraryFilter.Recent -> System.currentTimeMillis() - it.updatedAt < 7L * 86_400_000
                    is LibraryFilter.Folder -> it.folder == f.name
                }
            }
            .let { seq ->
                if (f == LibraryFilter.Recent) seq.sortedByDescending { it.updatedAt } else when (s.sort) {
                    SortOrder.RECENT -> seq.sortedByDescending { it.updatedAt }
                    SortOrder.CREATED -> seq.sortedByDescending { it.createdAt }
                    SortOrder.NAME -> seq.sortedBy { it.title.lowercase() }
                    SortOrder.SIZE -> seq.sortedByDescending { it.sizeBytes }
                }
            }
            .toList()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _events = Channel<HomeEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    /** Set when an imported PDF is password-protected; UI asks for the password. */
    private val _locked = MutableStateFlow<PasswordRequiredException?>(null)
    val locked: StateFlow<PasswordRequiredException?> = _locked.asStateFlow()

    fun setQuery(q: String) { _query.value = q }
    fun setFilter(f: LibraryFilter) { _filter.value = f }
    fun setSort(s: SortOrder) = settings.update { it.copy(sort = s) }
    fun setGrid(grid: Boolean) = settings.update { it.copy(documentsGrid = grid) }
    val prefs = settings.settings

    fun onScanned(pdf: Uri) = work { repo.importPdf(pdf, DocumentSource.SCAN).also { inCurrentFolder(it) } }
    fun onPhotosPicked(uris: List<Uri>) {
        if (uris.isNotEmpty()) work {
            repo.createFromImages(uris, pageSize = settings.settings.value.pageSize).also { inCurrentFolder(it) }
        }
    }
    fun onPdfPicked(uri: Uri, name: String?) = work {
        repo.importPdf(uri, DocumentSource.IMPORT, name?.removeSuffix(".pdf")?.removeSuffix(".PDF")).also { inCurrentFolder(it) }
    }

    /** New documents land in the folder currently being viewed. */
    private suspend fun inCurrentFolder(id: Long) {
        val f = _filter.value
        if (f is LibraryFilter.Folder) repo.get(id)?.let { repo.setFolder(it, f.name) }
    }

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

    fun rename(doc: Document, title: String) = viewModelScope.launch { repo.rename(doc, title) }
    fun delete(doc: Document) = viewModelScope.launch { repo.delete(doc) }
    fun toggleStar(doc: Document) = viewModelScope.launch { repo.toggleStar(doc) }
    fun moveToFolder(doc: Document, folder: String?) = viewModelScope.launch { repo.setFolder(doc, folder) }

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
        val Factory = viewModelFactory { initializer { HomeViewModel(container.documents, container.settings) } }
    }
}
