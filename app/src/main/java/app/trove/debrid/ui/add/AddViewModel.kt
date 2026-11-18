package app.trove.debrid.ui.add

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.trove.debrid.R
import app.trove.debrid.core.CachedInfo
import app.trove.debrid.core.ClassifiedShare
import app.trove.debrid.core.DebridError
import app.trove.debrid.core.DebridService
import app.trove.debrid.core.ServiceCacheResult
import app.trove.debrid.core.selectAddService
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
    val errorResource: Int? = null,
    val cacheResults: List<ServiceCacheResult> = emptyList(),
    val selectedService: DebridService? = null,
) {
    val showServicePicker: Boolean get() = cacheResults.size >= 2

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
            error = null, errorResource = null, added = false, cached = null,
            cacheResults = emptyList(), selectedService = null) }
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
            checking = false, error = null, errorResource = null, added = false,
            cacheResults = emptyList(), selectedService = null) }
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
            _state.update { it.copy(checking = true) }
            try {
                val active = repository.loadConnection().provider?.service
                if (version != inputVersion) return
                val results = repository.checkCachedEverywhere(hash)
                if (version != inputVersion) return
                val selected = if (results.size >= 2) selectAddService(results, active) else null
                val cached = if (results.size == 1) results.single().info
                    else results.firstOrNull { it.service == (selected ?: active) }?.info
                _state.update { it.copy(cacheResults = results, selectedService = selected, cached = cached) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The cache lookup is optional. Keep the classified input available for Add.
            } finally {
                if (version == inputVersion) _state.update { it.copy(checking = false) }
            }
        }
    }

    fun selectService(service: DebridService) {
        _state.update {
            if (it.adding || it.cacheResults.none { result -> result.service == service }) it
            else it.copy(selectedService = service)
        }
    }

    fun add() {
        if (_state.value.adding || _state.value.added) return
        val classified = _state.value.classified ?: return
        val selectedService = _state.value.selectedService
        _state.update { it.copy(adding = true, error = null, errorResource = null) }
        viewModelScope.launch {
            var service: DebridService? = null
            val result = try {
                val provider = selectedService?.let { repository.loadProviderFor(it) }
                    ?: repository.loadConnection().provider
                if (provider == null) {
                    _state.update { it.copy(adding = false, error = "Add your TorBox API key in Settings first") }
                    return@launch
                }
                service = provider.service
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
                    if (e is DebridError.AuthInvalid && service == DebridService.ALLDEBRID) {
                        _state.update { it.copy(adding = false, errorResource = R.string.ad_add_auth_invalid) }
                        return@fold
                    }
                    if (e is DebridError.AuthInvalid && service == DebridService.PREMIUMIZE) {
                        _state.update { it.copy(adding = false, errorResource = R.string.pm_add_auth_invalid) }
                        return@fold
                    }
                    if (e is DebridError.AuthInvalid && service == DebridService.OFFCLOUD) {
                        _state.update { it.copy(adding = false, errorResource = R.string.oc_add_auth_invalid) }
                        return@fold
                    }
                    if (e is DebridError.AuthInvalid && service == DebridService.DEBRID_LINK) {
                        _state.update { it.copy(adding = false, errorResource = R.string.dl_add_auth_invalid) }
                        return@fold
                    }
                    _state.update {
                        it.copy(
                            adding = false,
                            error = when (e) {
                                is DebridError.AuthInvalid -> if (service == DebridService.REAL_DEBRID) {
                                    "Real-Debrid rejected your API token. Open Settings and re-enter it."
                                } else {
                                    "TorBox rejected your API key. Open Settings and re-enter it."
                                }
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
        _state.update { it.copy(error = null, errorResource = null) }
    }

    fun inputError(message: String) {
        _state.update { it.copy(error = message, errorResource = null) }
    }

    fun reset() {
        if (_state.value.adding) return
        inputJob?.cancel()
        inputVersion++
        _state.value = AddUiState()
    }
}
