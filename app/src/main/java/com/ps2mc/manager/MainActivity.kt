package com.ps2mc.manager

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ps2mc.manager.ui.AppViewModel
import com.ps2mc.manager.ui.CardBrowserScreen
import com.ps2mc.manager.ui.HomeScreen
import com.ps2mc.manager.ui.Screen
import com.ps2mc.manager.ui.theme.PS2MCManagerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PS2MCManagerTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppRoot()
                }
            }
        }
    }
}

@Composable
fun AppRoot(viewModel: AppViewModel = viewModel()) {
    val context = LocalContext.current

    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.openCard(it, it.lastPathSegment?.substringAfterLast('/')) }
    }
    val saveAsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        uri?.let { viewModel.exportTo(it) }
    }

    viewModel.errorMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissError() },
            confirmButton = { TextButton(onClick = { viewModel.dismissError() }) { Text("OK") } },
            title = { Text("Error") },
            text = { Text(msg) }
        )
    }

    LaunchedEffect(viewModel.statusMessage) {
        viewModel.statusMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.dismissStatus()
        }
    }

    when (viewModel.screen) {
        Screen.HOME -> HomeScreen(
            isLoading = viewModel.isLoading,
            onOpenCard = { openLauncher.launch(arrayOf("*/*")) },
            onRecentCards = { Toast.makeText(context, "Recent cards — coming soon", Toast.LENGTH_SHORT).show() },
            onImportSave = { Toast.makeText(context, "Import save — coming soon", Toast.LENGTH_SHORT).show() },
            onSettings = { Toast.makeText(context, "Settings — coming soon", Toast.LENGTH_SHORT).show() }
        )
        Screen.CARD_BROWSER -> CardBrowserScreen(
            path = viewModel.currentPath,
            entries = viewModel.currentEntries,
            viewMode = viewModel.viewMode,
            clipboardName = viewModel.clipboardEntry?.name,
            onToggleViewMode = { viewModel.toggleViewMode() },
            onBack = { viewModel.onBackPressed() },
            onEntryTapped = { viewModel.onEntryTapped(it) },
            onCopyEntry = { viewModel.copyEntry(it) },
            onPasteHere = { viewModel.pasteHere() },
            onCreateFolder = { viewModel.createFolder(it) },
            onSaveAs = { saveAsLauncher.launch("${viewModel.cardFileName ?: "card"}_edited.ps2") },
            onExportPsu = { viewModel.exportPsu(it) },
            onImportPsu = { openLauncher.launch(arrayOf("*/*")) }
        )
    }
}
