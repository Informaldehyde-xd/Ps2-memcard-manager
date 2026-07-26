package com.ps2mc.manager.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ps2mc.manager.data.McCardRepository
import com.ps2mc.manager.mc.McCardImage
import com.ps2mc.manager.mc.McDirEntry
import kotlinx.coroutines.launch

enum class Screen { HOME, CARD_BROWSER }
enum class ViewMode { GRID, LIST }

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = McCardRepository(application)

    var screen by mutableStateOf(Screen.HOME)
        private set
    var viewMode by mutableStateOf(ViewMode.GRID)
        private set
    var loadedCard by mutableStateOf<McCardImage?>(null)
        private set
    var rootEntries by mutableStateOf<List<McDirEntry>>(emptyList())
        private set
    var isLoading by mutableStateOf(false)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set
    var cardFileName by mutableStateOf<String?>(null)
        private set

    fun openCard(uri: Uri, displayName: String?) {
        viewModelScope.launch {
            isLoading = true
            errorMessage = null
            val (image, error) = repo.openCard(uri)
            isLoading = false
            if (image != null) {
                loadedCard = image
                rootEntries = try {
                    image.listRoot()
                } catch (e: Exception) {
                    errorMessage = "Could not read directory: ${e.message}"
                    emptyList()
                }
                cardFileName = displayName ?: "Memory Card"
                screen = Screen.CARD_BROWSER
            } else {
                errorMessage = error
            }
        }
    }

    fun toggleViewMode() {
        viewMode = if (viewMode == ViewMode.GRID) ViewMode.LIST else ViewMode.GRID
    }

    fun goHome() {
        screen = Screen.HOME
        loadedCard = null
        rootEntries = emptyList()
        errorMessage = null
    }

    fun dismissError() {
        errorMessage = null
    }
}
