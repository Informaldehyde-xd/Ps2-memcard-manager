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
import com.ps2mc.manager.mc.McCardWriter
import com.ps2mc.manager.mc.McDirEntry
import kotlinx.coroutines.launch

enum class Screen { HOME, CARD_BROWSER }
enum class ViewMode { GRID, LIST }

private data class DirLevel(val relCluster: Int, val name: String)

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = McCardRepository(application)
    private val app = application

    var screen by mutableStateOf(Screen.HOME)
        private set
    var viewMode by mutableStateOf(ViewMode.GRID)
        private set
    var isLoading by mutableStateOf(false)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set
    var statusMessage by mutableStateOf<String?>(null)
        private set
    var cardFileName by mutableStateOf<String?>(null)
        private set
    var currentEntries by mutableStateOf<List<McDirEntry>>(emptyList())
        private set
    var currentPath by mutableStateOf("Root")
        private set
    var canGoUp by mutableStateOf(false)
        private set
    var clipboardEntry by mutableStateOf<McDirEntry?>(null)
        private set

    private var workingBytes: ByteArray? = null
    private var loadedCard: McCardImage? = null
    private var dirStack = mutableListOf<DirLevel>()

    /** relCluster of the directory that is the CURRENT dir's own parent, or null if current dir is root. */
    private fun parentOfCurrentDir(): Int? =
        if (dirStack.size >= 2) dirStack[dirStack.size - 2].relCluster else null

    fun openCard(uri: Uri, displayName: String?) {
        viewModelScope.launch {
            isLoading = true
            errorMessage = null
            val (image, error) = repo.openCard(uri)
            isLoading = false
            if (image != null) {
                workingBytes = image.rawBytesCopy()
                loadedCard = image
                cardFileName = displayName ?: "Memory Card"
                dirStack = mutableListOf(DirLevel(image.superblock.rootDirCluster, "Root"))
                refreshCurrentDir()
                screen = Screen.CARD_BROWSER
            } else {
                errorMessage = error
            }
        }
    }

    private fun refreshCurrentDir() {
        val image = loadedCard ?: return
        val level = dirStack.last()
        currentEntries = try {
            image.listDirectory(level.relCluster).filter { it.name != "." && it.name != ".." }
        } catch (e: Exception) {
            errorMessage = "Could not read directory: ${e.message}"
            emptyList()
        }
        currentPath = dirStack.joinToString(" / ") { it.name }
        canGoUp = dirStack.size > 1
    }

    fun toggleViewMode() {
        viewMode = if (viewMode == ViewMode.GRID) ViewMode.LIST else ViewMode.GRID
    }

    fun onEntryTapped(entry: McDirEntry) {
        if (!entry.isDirectory) return
        dirStack.add(DirLevel(entry.cluster, entry.name))
        refreshCurrentDir()
    }

    fun onBackPressed() {
        if (dirStack.size > 1) {
            dirStack.removeAt(dirStack.lastIndex)
            refreshCurrentDir()
        } else {
            goHome()
        }
    }

    fun goHome() {
        screen = Screen.HOME
        workingBytes = null
        loadedCard = null
        currentEntries = emptyList()
        dirStack = mutableListOf()
        errorMessage = null
        clipboardEntry = null
    }

    fun copyEntry(entry: McDirEntry) {
        clipboardEntry = entry
        statusMessage = "Copied \"${entry.name}\""
    }

    fun pasteHere() {
        val entry = clipboardEntry
        val image = loadedCard
        val bytes = workingBytes
        if (entry == null || image == null || bytes == null) return
        viewModelScope.launch {
            isLoading = true
            try {
                val writer = McCardWriter.from(bytes, image)
                val destRel = dirStack.last().relCluster
                val parentOfDest = parentOfCurrentDir()
                if (entry.isDirectory) {
                    writer.copyFolderInto(image, entry, destRel, parentOfDest)
                } else {
                    writer.copyFileEntry(image, entry, destRel, parentOfDest)
                }
                applyWrittenBytes(writer.exportBytes())
                statusMessage = "Pasted \"${entry.name}\""
            } catch (e: Exception) {
                errorMessage = "Paste failed: ${e.message}"
            }
            isLoading = false
        }
    }

    fun createFolder(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            errorMessage = "Folder name can't be empty."
            return
        }
        val image = loadedCard
        val bytes = workingBytes
        if (image == null || bytes == null) return
        viewModelScope.launch {
            isLoading = true
            try {
                val writer = McCardWriter.from(bytes, image)
                writer.createFolder(dirStack.last().relCluster, parentOfCurrentDir(), trimmed)
                applyWrittenBytes(writer.exportBytes())
                statusMessage = "Created \"$trimmed\""
            } catch (e: Exception) {
                errorMessage = "Create folder failed: ${e.message}"
            }
            isLoading = false
        }
    }

    private fun applyWrittenBytes(newBytes: ByteArray) {
        val newImage = McCardImage.open(newBytes)
        workingBytes = newBytes
        loadedCard = newImage
        refreshCurrentDir()
    }

    fun exportTo(uri: Uri) {
        val bytes = workingBytes ?: return
        viewModelScope.launch {
            isLoading = true
            try {
                app.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                statusMessage = "Saved. Original card was not modified."
            } catch (e: Exception) {
                errorMessage = "Save failed: ${e.message}"
            }
            isLoading = false
        }
    }

    fun dismissError() {
        errorMessage = null
    }

    fun dismissStatus() {
        statusMessage = null
    }
}
