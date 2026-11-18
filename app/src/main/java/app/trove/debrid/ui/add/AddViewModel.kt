package app.trove.debrid.ui.add

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.trove.debrid.core.CachedInfo
import app.trove.debrid.core.ClassifiedShare
import app.trove.debrid.core.DebridError
import app.trove.debrid.data.TroveRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException

data class AddUiState(
    val inputText: String = "",
    val classified: ClassifiedShare? = null,
    val cached: CachedInfo? = null,
    val checking: Boolean = false,
    val adding: Boolean = false,
    val added: Boolean = false,
    val addedKind: app.trove.debrid.core.DownloadKind? = null,
    val error: String? = null,
) {
    override fun toString(): String = "AddUiState([redacted])"
}

class AddViewModel(
    private val repository: TroveRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(AddUiState())
    val state: StateFlow<AddUiState> = _state.asStateFlow()

    private var inputJob: Job? = null
    private var inputVersion = 0

    fun setText(text: String) {
        if (_state.value.adding) return
        inputJob?.cancel()
        val version = ++inputVersion
        _state.update { it.copy(inputText = text, classified = null, checking = false,
            error = null, added = false, cached = null) }
        inputJob = viewModelScope.launch {
            delay(250) // Don't fetch the hoster list/cache for every keystroke.
            classifyText(text, version)
        }
    }

    fun setFile(bytes: ByteArray, fileName: String, mimeType: String?) {
        if (_state.value.adding) return
        inputJob?.cancel()
        val version = ++inputVersion
        _state.update { it.copy(inputText = fileName, classified = null, cached = null,
            checking = false, error = null, added = false) }
        inputJob = viewModelScope.launch {
            val classified = classify(null, bytes, fileName, mimeType, version) ?: return@launch
            if (version == inputVersion) _state.update { it.copy(classified = classified) }
        }
    }

    private suspend fun classify(text: String?, bytes: ByteArray?, name: String?, mime: String?, version: Int): ClassifiedShare? =
        try { repository.classifyShared(text, bytes, name, mime) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) {
            if (version == inputVersion) {
                _state.update { it.copy(checking = false, error = "Couldn't read this input. Try again.") }
            }
            null
        }

    private suspend fun classifyText(text: String, version: Int) {
        val classified = classify(text, null, null, null, version) ?: return
        if (version != inputVersion) return
        _state.update { it.copy(classified = classified) }
        if (classified is ClassifiedShare.Magnet) {
            val hash = classified.infoHashHex ?: return
            try {
                val provider = repository.loadConnection().provider ?: return
                if (version != inputVersion) return
                _state.update { it.copy(checking = true) }
                val cached = repository.checkCached(provider, hash).getOrThrow()?.get(hash)
                if (version == inputVersion) _state.update { it.copy(cached = cached) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The cache lookup is optional. Keep the classified input available for Add.
            } finally {
                if (version == inputVersion) _state.update { it.copy(checking = false) }
            }
        }
    }

    fun add() {
        if (_state.value.adding || _state.value.added) return
        val classified = _state.value.classified ?: return
        _state.update { it.copy(adding = true, error = null) }
        viewModelScope.launch {
            val result = try {
                val provider = repository.loadConnection().provider
                if (provider == null) {
                    _state.update { it.copy(adding = false, error = "Add your TorBox API key in Settings first") }
                    return@launch
                }
                repository.addClassified(provider, classified)
            }
            catch (e: CancellationException) {
                _state.update { it.copy(adding = false) }
                throw e
            }
            catch (e: Exception) { Result.failure(e) }
            result.fold(
                onSuccess = { download -> _state.update { s -> s.copy(adding = false, added = true, addedKind = download.kind) } },
                onFailure = { e ->
                    if (e is CancellationException) throw e
                    _state.update {
                        it.copy(
                            adding = false,
                            error = when (e) {
                                is DebridError.AuthInvalid -> "TorBox rejected your API key. Open Settings and re-enter it."
                                is DebridError.Provider -> e.detail
                                is DebridError -> e.message
                                else -> "Couldn't add download"
                            },
                        )
                    }
                },
            )
        }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    fun inputError(message: String) {
        _state.update { it.copy(error = message) }
    }

    fun reset() {
        if (_state.value.adding) return
        inputJob?.cancel()
        inputVersion++
        _state.value = AddUiState()
    }
}
