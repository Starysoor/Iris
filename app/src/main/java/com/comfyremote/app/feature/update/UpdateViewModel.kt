package com.comfyremote.app.feature.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.comfyremote.app.AppContainer
import com.comfyremote.app.GhRelease
import com.comfyremote.app.R
import com.comfyremote.app.ui.AppStrings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UpdateUiState(
    val currentVersion: String = "",
    val checking: Boolean = false,
    val release: GhRelease? = null,
    val status: String? = null,
    val isError: Boolean = false,
    val downloading: Boolean = false,
    val progress: Float? = null,
)

class UpdateViewModel(private val container: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(
        UpdateUiState(
            currentVersion = container.updateRepository.currentVersion(),
        )
    )
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    fun check() {
        if (_state.value.checking || _state.value.downloading) return
        _state.update { it.copy(checking = true, status = AppStrings.get(R.string.update_status_checking), isError = false) }
        viewModelScope.launch {
            runCatching { container.updateRepository.latest() }
                .onSuccess { release ->
                    container.updateSettingsStore.markUpdateChecked()
                    if (container.updateRepository.isNewer(release)) {
                        _state.update { it.copy(checking = false, release = release, status = null) }
                    } else {
                        _state.update {
                            it.copy(
                                checking = false,
                                release = null,
                                status = AppStrings.get(R.string.update_up_to_date),
                            )
                        }
                    }
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(
                            checking = false,
                            status = AppStrings.get(R.string.update_check_failed, (e.message ?: "error").take(200)),
                            isError = true,
                        )
                    }
                }
        }
    }

    fun downloadAndInstall() {
        val release = _state.value.release ?: return
        if (_state.value.downloading) return
        _state.update { it.copy(downloading = true, progress = 0f, isError = false) }
        viewModelScope.launch {
            runCatching {
                container.updateRepository.download(release) { p ->
                    _state.update { it.copy(progress = p) }
                }
            }.onSuccess { apk ->
                _state.update { it.copy(downloading = false, progress = 1f) }
                container.updateRepository.install(apk)
            }.onFailure { e ->
                _state.update {
                    it.copy(
                        downloading = false,
                        status = AppStrings.get(R.string.update_download_failed, (e.message ?: "error").take(200)),
                        isError = true,
                    )
                }
            }
        }
    }
}
