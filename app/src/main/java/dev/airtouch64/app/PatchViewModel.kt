package dev.airtouch64.app

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.airtouch64.engine.BundleManifest
import dev.airtouch64.engine.PatchEvent
import dev.airtouch64.engine.PatchResult
import dev.airtouch64.engine.SourceInspection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class SourceSelection(
    val file: File,
    val name: String,
    val inspection: SourceInspection,
)

sealed interface UiState {
    data object Loading : UiState
    data class Home(
        val manifest: BundleManifest,
        val source: SourceSelection? = null,
        val importing: Boolean = false,
        val error: String? = null,
    ) : UiState

    data class Running(
        val manifest: BundleManifest,
        val currentStep: String?,
        val completed: Set<String>,
        val log: List<String>,
    ) : UiState

    data class Done(val result: PatchResult) : UiState

    data class Failed(val message: String, val log: List<String>) : UiState
}

class PatchViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = PatchRepository(application)
    var state by mutableStateOf<UiState>(UiState.Loading)
        private set

    init {
        viewModelScope.launch {
            state = try {
                UiState.Home(manifest = withContext(Dispatchers.IO) { repository.manifest() })
            } catch (error: Exception) {
                UiState.Failed("Bundle inválido: ${error.message}", emptyList())
            }
        }
    }

    fun importSource(uri: android.net.Uri) {
        val home = state as? UiState.Home ?: return
        state = home.copy(importing = true, error = null)
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val name = repository.displayName(uri)
                    val file = repository.importSource(uri, name)
                    val inspection = repository.inspect(file, home.manifest)
                    SourceSelection(file, name, inspection)
                }
            }
            state = result.fold(
                onSuccess = { selection -> home.copy(source = selection, importing = false) },
                onFailure = { error -> home.copy(importing = false, error = error.message ?: "Falha ao importar") },
            )
        }
    }

    fun start() {
        val home = state as? UiState.Home ?: return
        val source = home.source ?: return
        val manifest = home.manifest
        state = UiState.Running(manifest, currentStep = null, completed = emptySet(), log = emptyList())
        viewModelScope.launch {
            try {
                val identity = withContext(Dispatchers.IO) { repository.identity() }
                var log = mutableListOf<String>()
                var completed = mutableSetOf<String>()
                var current: String? = null
                val result = withContext(Dispatchers.IO) {
                    repository.patch(source.file, manifest, identity) { event ->
                        when (event) {
                            is PatchEvent.Step -> when (event.state) {
                                PatchEvent.Step.State.RUNNING -> {
                                    current = event.id
                                    log += manifest.steps.firstOrNull { it.id == event.id }?.name ?: event.id
                                }
                                PatchEvent.Step.State.DONE -> {
                                    completed += event.id
                                    current = null
                                }
                                PatchEvent.Step.State.FAILED -> current = null
                            }
                            is PatchEvent.Message -> log += event.text
                            is PatchEvent.Progress -> Unit
                        }
                        val snapshotLog = log.toList()
                        val snapshotCompleted = completed.toSet()
                        val snapshotCurrent = current
                        viewModelScope.launch {
                            state = UiState.Running(manifest, snapshotCurrent, snapshotCompleted, snapshotLog)
                        }
                    }
                }
                state = UiState.Done(result)
            } catch (error: Exception) {
                val running = state as? UiState.Running
                state = UiState.Failed(error.message ?: "Falha desconhecida", running?.log.orEmpty())
            }
        }
    }

    fun reset() {
        viewModelScope.launch {
            val manifest = withContext(Dispatchers.IO) { runCatching { repository.manifest() }.getOrNull() }
            state = if (manifest == null) {
                UiState.Failed("Bundle inválido", emptyList())
            } else {
                UiState.Home(manifest = manifest)
            }
        }
    }
}
