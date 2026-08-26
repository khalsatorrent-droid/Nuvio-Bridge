package com.example.ui.screens

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.MainUiState
import com.example.ui.theme.HdBorder
import com.example.ui.theme.HdOnPrimaryContainer
import com.example.ui.theme.HdPrimary
import com.example.ui.theme.HdPrimaryContainer
import com.example.ui.theme.HdSuccessText
import com.example.ui.theme.HdSurface
import com.example.ui.theme.HdSurfaceContainer
import com.example.ui.theme.HdTextMuted
import com.example.ui.theme.HdTextPrimary
import com.example.ui.theme.HdTextSecondary

@Composable
fun SettingsScreen(
    state: MainUiState,
    onPortChange: (Int) -> Unit,
    onUpdateSettings: (Boolean, Boolean, Boolean, Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    var portInput by remember(state.serverPort) { mutableStateOf("${state.serverPort}") }
    var sortByQuality by remember(state.sortByQuality) { mutableStateOf(state.sortByQuality) }
    var groupByQuality by remember(state.groupByQuality) { mutableStateOf(state.groupByQuality) }
    var filterOutLowQuality by remember(state.filterOutLowQuality) { mutableStateOf(state.filterOutLowQuality) }
    var timeoutInput by remember(state.requestTimeoutSec) { mutableStateOf("${state.requestTimeoutSec}") }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Server & Quality Configuration",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = HdTextPrimary
                )
            )
        }

        // Port & Network Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = HdSurface),
                border = BorderStroke(1.dp, HdBorder)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Lan, contentDescription = null, tint = HdPrimary, modifier = Modifier.size(20.dp))
                        Text("HTTP Server Port", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, color = HdTextPrimary))
                    }

                    Text(
                        text = "Standard Stremio addon port is 8585. Changing this will restart the server.",
                        style = MaterialTheme.typography.bodySmall.copy(color = HdTextSecondary)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = portInput,
                            onValueChange = { portInput = it.filter { char -> char.isDigit() } },
                            label = { Text("Port") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = HdPrimary,
                                unfocusedBorderColor = HdBorder,
                                focusedTextColor = HdTextPrimary,
                                unfocusedTextColor = HdTextPrimary,
                                focusedContainerColor = Color.White,
                                unfocusedContainerColor = Color.White
                            ),
                            shape = RoundedCornerShape(12.dp)
                        )

                        Button(
                            onClick = {
                                val p = portInput.toIntOrNull()
                                if (p != null && p in 1024..65535) {
                                    onPortChange(p)
                                    Toast.makeText(context, "Port set to $p", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "Invalid port (1024-65535)", Toast.LENGTH_SHORT).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = HdPrimary, contentColor = Color.White),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Apply", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // Quality Engine Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = HdSurface),
                border = BorderStroke(1.dp, HdBorder)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.HighQuality, contentDescription = null, tint = Color(0xFFF57F17), modifier = Modifier.size(20.dp))
                        Text("Stream Quality Formatting", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, color = HdTextPrimary))
                    }

                    // Toggle: Sort by Quality
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Sort Streams by Quality", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold, color = HdTextPrimary))
                            Text("Sorts 4K UHD > 1080p FHD > 720p HD > 480p", style = MaterialTheme.typography.bodySmall.copy(color = HdTextSecondary))
                        }
                        Switch(
                            checked = sortByQuality,
                            onCheckedChange = {
                                sortByQuality = it
                                onUpdateSettings(sortByQuality, groupByQuality, filterOutLowQuality, timeoutInput.toIntOrNull() ?: 12)
                            },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = HdPrimary)
                        )
                    }

                    // Toggle: Filter low quality
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Filter Low Quality Streams", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold, color = HdTextPrimary))
                            Text("Hides streams below 720p", style = MaterialTheme.typography.bodySmall.copy(color = HdTextSecondary))
                        }
                        Switch(
                            checked = filterOutLowQuality,
                            onCheckedChange = {
                                filterOutLowQuality = it
                                onUpdateSettings(sortByQuality, groupByQuality, filterOutLowQuality, timeoutInput.toIntOrNull() ?: 0)
                            },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = HdPrimary)
                        )
                    }
                }
            }
        }

        // Provider Execution & Timeout Card
        item {
            val isUnlimited = (timeoutInput.toIntOrNull() ?: 0) <= 0
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = HdSurface),
                border = BorderStroke(1.dp, HdBorder)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Tune, contentDescription = null, tint = HdPrimary, modifier = Modifier.size(20.dp))
                        Text("Provider Execution & Timeout", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, color = HdTextPrimary))
                    }

                    Text(
                        text = "Controls how long the engine waits for JavaScript scrapers and decryption routines to finish executing.",
                        style = MaterialTheme.typography.bodySmall.copy(color = HdTextSecondary)
                    )

                    // Unlimited Execution Switch
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Run Until Complete (No Timeout)", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold, color = HdTextPrimary))
                            Text("Never abort scrapers early; wait for full stream resolution", style = MaterialTheme.typography.bodySmall.copy(color = HdTextSecondary))
                        }
                        Switch(
                            checked = isUnlimited,
                            onCheckedChange = { unlimited ->
                                val newTimeout = if (unlimited) 0 else 60
                                timeoutInput = "$newTimeout"
                                onUpdateSettings(sortByQuality, groupByQuality, filterOutLowQuality, newTimeout)
                                Toast.makeText(context, if (unlimited) "Execution set to Unlimited (No Timeouts)" else "Timeout set to 60s", Toast.LENGTH_SHORT).show()
                            },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = HdPrimary)
                        )
                    }

                    if (isUnlimited) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = HdSuccessText.copy(alpha = 0.08f),
                            border = BorderStroke(1.dp, HdSuccessText.copy(alpha = 0.3f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("⚡", fontSize = 16.sp)
                                Text(
                                    text = "All scrapers will run in parallel until complete execution without any premature timeout interrupts.",
                                    style = MaterialTheme.typography.labelSmall.copy(color = HdSuccessText, fontWeight = FontWeight.Medium)
                                )
                            }
                        }
                    } else {
                        // Quick Presets for Custom Timeout
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Preset Limits (Seconds):", style = MaterialTheme.typography.labelSmall.copy(color = HdTextSecondary))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                listOf(30, 60, 120, 180).forEach { sec ->
                                    val isSelected = timeoutInput == "$sec"
                                    Button(
                                        onClick = {
                                            timeoutInput = "$sec"
                                            onUpdateSettings(sortByQuality, groupByQuality, filterOutLowQuality, sec)
                                            Toast.makeText(context, "Timeout set to ${sec}s", Toast.LENGTH_SHORT).show()
                                        },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (isSelected) HdPrimary else HdSurfaceContainer,
                                            contentColor = if (isSelected) Color.White else HdTextPrimary
                                        ),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("${sec}s", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // GitHub Actions APK Build Guide Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = HdSurface),
                border = BorderStroke(1.dp, HdBorder)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Android, contentDescription = null, tint = HdSuccessText, modifier = Modifier.size(20.dp))
                        Text("How to Get APK from GitHub", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, color = HdTextPrimary))
                    }

                    Text(
                        text = "This repository includes a pre-configured `.github/workflows/build-apk.yml` workflow:",
                        style = MaterialTheme.typography.bodySmall.copy(color = HdTextSecondary)
                    )

                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color.White,
                        border = BorderStroke(1.dp, HdBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text("1. Push project to your GitHub repository.", style = MaterialTheme.typography.labelSmall.copy(color = HdPrimary, fontWeight = FontWeight.SemiBold))
                            Text("2. GitHub Actions will build the APK automatically.", style = MaterialTheme.typography.labelSmall.copy(color = HdPrimary, fontWeight = FontWeight.SemiBold))
                            Text("3. Go to Actions tab > Click latest run > Download APK Artifact!", style = MaterialTheme.typography.labelSmall.copy(color = HdPrimary, fontWeight = FontWeight.SemiBold))
                        }
                    }
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

