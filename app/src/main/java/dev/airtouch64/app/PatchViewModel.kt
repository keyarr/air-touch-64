package dev.airtouch64.app

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.airtouch64.engine.BundleHeader
import dev.airtouch64.engine.PatchEvent
import dev.airtouch64.engine.PatchResult
import dev.airtouch64.engine.SourceInspection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class BundleSelection(val file: File, val name: String, val header: BundleHeader)
data class SourceSelection(val file: File, val name: String, val inspection: SourceInspection)

sealed interface UiState {
    data object Loading : UiState
    data class Home(
        val bundle: BundleSelection? = null,
        val source: SourceSelection? = null,
        val importingBundle: Boolean = false,
        val importingSource: Boolean = false,
        val error: String? = null,
    ) : UiState
    data class Running(val header: BundleHeader, val currentStep: String?, val completed: Set<String>, val log: List<String>) : UiState
    data class Done(val result: PatchResult) : UiState
    data class Failed(val message: String, val log: List<String>) : UiState
}

class PatchViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = PatchRepository(application)
    var state by mutableStateOf<UiState>(UiState.Loading)
        private set

    init { reset() }

    fun importBundle(uri: Uri) {
        val home = state as? UiState.Home ?: return
        state = home.copy(importingBundle = true, error = null)
        viewModelScope.launch {
            val result = runCatching { withContext(Dispatchers.IO) {
                val name = repository.displayName(uri)
                val bundle = repository.importBundle(uri, name)
                val source = home.source?.let { old ->
                    old.copy(inspection = repository.inspect(old.file, bundle.header))
                }
                bundle to source
            } }
            state = result.fold(
                { (bundle, source) -> home.copy(bundle = bundle, source = source, importingBundle = false) },
                { error -> home.copy(importingBundle = false, error = error.message ?: getApplication<Application>().getString(R.string.import_failed)) },
            )
        }
    }

    fun importSource(uri: Uri) {
        val home = state as? UiState.Home ?: return
        state = home.copy(importingSource = true, error = null)
        viewModelScope.launch {
            val result = runCatching { withContext(Dispatchers.IO) {
                val name = repository.displayName(uri)
                val file = repository.importSource(uri, name)
                val inspection = home.bundle?.let { repository.inspect(file, it.header) }
                file to (name to inspection)
            } }
            state = result.fold(
                { (file, details) ->
                    val (name, inspection) = details
                    if (inspection == null) home.copy(importingSource = false, error = getApplication<Application>().getString(R.string.import_bundle_first))
                    else home.copy(source = SourceSelection(file, name, inspection), importingSource = false)
                },
                { error -> home.copy(importingSource = false, error = error.message ?: getApplication<Application>().getString(R.string.import_failed)) },
            )
        }
    }

    fun start() {
        val home = state as? UiState.Home ?: return
        val source = home.source ?: return
        val bundle = home.bundle ?: return
        val header = bundle.header
        state = UiState.Running(header, null, emptySet(), emptyList())
        viewModelScope.launch {
            try {
                val identity = withContext(Dispatchers.IO) { repository.identity() }
                val log = mutableListOf<String>(); val completed = mutableSetOf<String>(); var current: String? = null
                val result = withContext(Dispatchers.IO) {
                    repository.patch(source.file, bundle.file, identity) { event ->
                        when (event) {
                            is PatchEvent.Step -> when (event.state) {
                                PatchEvent.Step.State.RUNNING -> { current = event.id; log += header.steps.firstOrNull { it.id == event.id }?.name ?: event.id }
                                PatchEvent.Step.State.DONE -> { completed += event.id; current = null }
                                PatchEvent.Step.State.FAILED -> current = null
                            }
                            is PatchEvent.Message -> log += event.text
                            is PatchEvent.Progress -> Unit
                        }
                        val lines = log.toList(); val done = completed.toSet(); val active = current
                        viewModelScope.launch { state = UiState.Running(header, active, done, lines) }
                    }
                }
                state = UiState.Done(result)
            } catch (error: Exception) {
                state = UiState.Failed(error.message ?: getApplication<Application>().getString(R.string.unknown_failure), (state as? UiState.Running)?.log.orEmpty())
            }
        }
    }

    fun reset() {
        state = UiState.Loading
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) { repository.loadSavedBundle() }
            val result = withContext(Dispatchers.IO) { runCatching { repository.downloadLatestBundle() } }
            state = result.fold(
                { UiState.Home(bundle = it) },
                { error -> UiState.Home(bundle = saved, error = error.message ?: getApplication<Application>().getString(R.string.bundle_download_failed)) },
            )
        }
    }
}
