package com.ps2mc.manager

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ps2mc.manager.ui.AppViewModel
import com.ps2mc.manager.ui.CardBrowserScreen
import com.ps2mc.manager.ui.HomeScreen
import com.ps2mc.manager.ui.Screen
import com.ps2mc.manager.ui.theme.PS2MCManagerTheme
import androidx.compose.foundation.layout.fillMaxSize

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
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.openCard(it, it.lastPathSegment?.substringAfterLast('/')) }
    }

    viewModel.errorMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissError() },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissError() }) { Text("OK") }
            },
            title = { Text("Couldn't open card") },
            text = { Text(msg) }
        )
    }

    when (viewModel.screen) {
        Screen.HOME -> HomeScreen(
            isLoading = viewModel.isLoading,
            onOpenCard = { launcher.launch(arrayOf("*/*")) },
            onRecentCards = { Toast.makeText(context, "Recent cards — coming soon", Toast.LENGTH_SHORT).show() },
            onImportSave = { Toast.makeText(context, "Import save — coming soon", Toast.LENGTH_SHORT).show() },
            onSettings = { Toast.makeText(context, "Settings — coming soon", Toast.LENGTH_SHORT).show() }
        )
        Screen.CARD_BROWSER -> CardBrowserScreen(
            cardName = viewModel.cardFileName ?: "Memory Card",
            entries = viewModel.rootEntries,
            viewMode = viewModel.viewMode,
            onToggleViewMode = { viewModel.toggleViewMode() },
            onBack = { viewModel.goHome() }
        )
    }
}
