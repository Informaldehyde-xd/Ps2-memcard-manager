package com.ps2mc.manager.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import com.ps2mc.manager.mc.McDirEntry
import com.ps2mc.manager.ui.theme.IconAudio
import com.ps2mc.manager.ui.theme.IconFile
import com.ps2mc.manager.ui.theme.IconFolder
import com.ps2mc.manager.ui.theme.IconImage
import com.ps2mc.manager.ui.theme.SlateBackground
import com.ps2mc.manager.ui.theme.SlateCard
import com.ps2mc.manager.ui.theme.TextDim
import com.ps2mc.manager.ui.theme.TextMuted
import com.ps2mc.manager.ui.theme.TextPrimary
import com.ps2mc.manager.ui.theme.TextSecondary
import kotlin.math.ln
import kotlin.math.pow

/**
 * Card browser screen — matches the "File Manager" mockup: header with
 * title, grid/list toggle, overflow menu, then a grid or list of entries.
 * Currently shows the root directory of a loaded card (real save folders).
 */
@Composable
fun CardBrowserScreen(
    cardName: String,
    entries: List<McDirEntry>,
    viewMode: ViewMode,
    onToggleViewMode: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SlateBackground)
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = TextSecondary)
            }
            Text(
                cardName,
                color = TextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 4.dp)
            )
            IconButton(onClick = onToggleViewMode) {
                Icon(
                    if (viewMode == ViewMode.GRID) Icons.Filled.GridView else Icons.Filled.ViewList,
                    contentDescription = "Toggle view",
                    tint = TextSecondary
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "More options", tint = TextSecondary)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    listOf("Export Save", "Delete", "Select", "Sort").forEach { label ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                menuOpen = false
                                Toast.makeText(context, "$label — coming soon", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                }
            }
        }

        Text(
            "${entries.size} save${if (entries.size == 1) "" else "s"}",
            color = TextMuted,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )

        Spacer(Modifier.height(8.dp))

        if (entries.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No saves found on this card", color = TextDim, fontSize = 14.sp)
            }
        } else when (viewMode) {
            ViewMode.GRID -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 100.dp),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(entries) { entry -> EntryGridCard(entry) }
            }
            ViewMode.LIST -> LazyColumn(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(entries) { entry -> EntryListRow(entry) }
            }
        }
    }
}

private fun iconFor(entry: McDirEntry): Pair<ImageVector, androidx.compose.ui.graphics.Color> {
    if (entry.isDirectory) return Icons.Filled.Folder to IconFolder
    val n = entry.name.lowercase()
    return when {
        n.endsWith(".txt") || n.endsWith(".sys") -> Icons.Filled.Description to IconFile
        n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg") -> Icons.Filled.Image to IconImage
        n.endsWith(".mp3") || n.endsWith(".wav") -> Icons.Filled.MusicNote to IconAudio
        else -> Icons.Filled.InsertDriveFile to IconFile
    }
}

@Composable
private fun EntryGridCard(entry: McDirEntry) {
    val (icon, tint) = iconFor(entry)
    Surface(shape = RoundedCornerShape(16.dp), color = SlateCard) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(40.dp))
            Spacer(Modifier.height(8.dp))
            Text(
                entry.name,
                color = TextSecondary,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun EntryListRow(entry: McDirEntry) {
    val (icon, tint) = iconFor(entry)
    Surface(shape = RoundedCornerShape(12.dp), color = SlateCard) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text(
                entry.name,
                color = TextSecondary,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                if (entry.isDirectory) "Folder" else formatBytes(entry.length),
                color = TextDim,
                fontSize = 12.sp
            )
        }
    }
}

private fun formatBytes(bytes: Int): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB")
    val exp = (ln(bytes.toDouble()) / ln(1024.0)).toInt().coerceIn(1, units.size)
    val value = bytes / 1024.0.pow(exp.toDouble())
    return "%.1f %s".format(value, units[exp - 1])
}
