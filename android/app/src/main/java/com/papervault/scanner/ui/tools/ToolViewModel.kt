package com.papervault.scanner.ui.tools

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.papervault.scanner.data.Document
import com.papervault.scanner.data.DocumentRepository
import com.papervault.scanner.ui.container
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface ToolStatus {
    data object Idle : ToolStatus
    data class Running(val message: String, val progress: Float? = null) : ToolStatus
    /** Finished: [docIds] are new library documents; [files] are exported files to share/save. */
    data class Done(val message: String, val docIds: List<Long> = emptyList(), val files: List<java.io.File> = emptyList(), val mime: String = "application/pdf", val text: String? = null) : ToolStatus
    data class Failed(val message: String) : ToolStatus
}

/** Shared runner for one-shot tools: progress, errors and results in a single state. */
class ToolViewModel(val app: Application, val repo: DocumentRepository) : ViewModel() {

    val library: StateFlow<List<Document>> =
        repo.observeLibrary().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _status = MutableStateFlow<ToolStatus>(ToolStatus.Idle)
    val status: StateFlow<ToolStatus> = _status.asStateFlow()

    fun reset() { _status.value = ToolStatus.Idle }

    fun progress(message: String, fraction: Float? = null) {
        _status.value = ToolStatus.Running(message, fraction)
    }

    fun run(startMessage: String, block: suspend ToolViewModel.() -> ToolStatus.Done) {
        if (_status.value is ToolStatus.Running) return
        _status.value = ToolStatus.Running(startMessage)
        viewModelScope.launch {
            _status.value = try {
                withContext(Dispatchers.IO) { block() }
            } catch (e: Exception) {
                ToolStatus.Failed(e.message ?: e.javaClass.simpleName)
            } catch (e: OutOfMemoryError) {
                ToolStatus.Failed("Not enough memory for this document")
            }
        }
    }

    companion object {
        val Factory = viewModelFactory { initializer { ToolViewModel(this[APPLICATION_KEY]!!, container.documents) } }
    }
}
