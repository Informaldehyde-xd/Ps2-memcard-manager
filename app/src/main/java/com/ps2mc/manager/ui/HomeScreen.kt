package com.ps2mc.manager.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ps2mc.manager.ui.theme.AccentBlueDark
import com.ps2mc.manager.ui.theme.AccentBlueLight
import com.ps2mc.manager.ui.theme.ButtonBlueBg
import com.ps2mc.manager.ui.theme.ButtonBlueText
import com.ps2mc.manager.ui.theme.NavyGradientBottom
import com.ps2mc.manager.ui.theme.NavyGradientTop
import com.ps2mc.manager.ui.theme.TextFooter

/**
 * Home / launcher screen — matches the "OPL Menu" mockup: navy gradient
 * background, centered logo badge, title, and a 2x2 grid of action buttons.
 */
@Composable
fun HomeScreen(
    isLoading: Boolean,
    onOpenCard: () -> Unit,
    onRecentCards: () -> Unit,
    onImportSave: () -> Unit,
    onSettings: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(NavyGradientTop, NavyGradientBottom)))
            .padding(horizontal = 24.dp, vertical = 32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Logo badge
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Brush.linearGradient(listOf(AccentBlueLight, AccentBlueDark))),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Bolt, contentDescription = null, tint = Color.White, modifier = Modifier.size(32.dp))
            }

            Spacer(Modifier.height(16.dp))
            Text("PS2 MC Manager", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Select an option to continue",
                color = AccentBlueLight.copy(alpha = 0.7f),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )

            if (isLoading) {
                Spacer(Modifier.height(20.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = AccentBlueLight)
                    Spacer(Modifier.width(8.dp))
                    Text("Opening card…", color = AccentBlueLight, fontSize = 13.sp)
                }
            }

            Spacer(Modifier.height(40.dp))

            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    MenuButton(Icons.Filled.FolderOpen, "Open Card", onOpenCard, Modifier.weight(1f))
                    MenuButton(Icons.Filled.History, "Recent Cards", onRecentCards, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    MenuButton(Icons.Filled.Download, "Import Save", onImportSave, Modifier.weight(1f))
                    MenuButton(Icons.Filled.Settings, "Settings", onSettings, Modifier.weight(1f))
                }
            }
        }

        Text(
            "v0.1.0 • PS2 MC Manager",
            color = TextFooter,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 12.dp)
        )
    }
}

@Composable
private fun MenuButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        modifier = modifier.aspectRatio(1.3f),
        shape = RoundedCornerShape(20.dp),
        color = ButtonBlueBg
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(icon, contentDescription = label, tint = ButtonBlueText, modifier = Modifier.size(28.dp))
            Spacer(Modifier.height(10.dp))
            Text(label, color = ButtonBlueText, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
    }
}
