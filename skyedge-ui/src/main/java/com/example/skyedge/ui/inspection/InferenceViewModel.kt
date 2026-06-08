package com.example.skyedge.ui.inspection

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.skyedge.core.api.InspectionFacade
import com.example.skyedge.core.api.InspectionUiState
import com.example.skyedge.core.api.ModelChoice
import com.example.skyedge.core.impl.InspectionFacadeImpl
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class InferenceViewModel @JvmOverloads constructor(
    application: Application,
    injectedFacade: InspectionFacade? = null,
) : AndroidViewModel(application) {

    private val facade: InspectionFacade =
        injectedFacade ?: InspectionFacadeImpl(application, viewModelScope)

    val uiState: StateFlow<InspectionUiState> = facade.state.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = InspectionUiState(),
    )

    val modelChoices: List<ModelChoice> = facade.modelChoices

    fun loadModel(modelKey: String = uiState.value.selectedModelKey) {
        facade.loadModel(modelKey)
    }

    fun switchModel(modelKey: String) {
        facade.switchModel(modelKey)
    }

    fun updateStatus(message: String) {
        facade.updateStatus(message)
    }

    fun infer(uri: Uri) {
        viewModelScope.launch {
            facade.infer(uri)
        }
    }

    fun loadGeoTiff(uri: Uri) {
        viewModelScope.launch {
            facade.loadGeoTiff(uri)
        }
    }

    fun inferMapSession() {
        viewModelScope.launch {
            facade.inferMapSession()
        }
    }

    fun setMapLayerVisibility(showOrtho: Boolean, showMask: Boolean) {
        facade.setMapLayerVisibility(showOrtho, showMask)
    }

    fun setMaskAlpha(alpha: Float) {
        facade.setMaskAlpha(alpha)
    }

    fun clearMapSession() {
        facade.clearMapSession()
    }

    fun refreshHistory() {
        facade.refreshHistory()
    }

    fun benchmarkCurrentImage(uri: Uri, runs: Int = 10) {
        viewModelScope.launch {
            facade.benchmark(uri, runs)
        }
    }

    override fun onCleared() {
        facade.close()
        super.onCleared()
    }
}
