package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.StremioStreamItem
import com.example.ui.MainUiState
import com.example.ui.theme.HdAccentPurpleDark
import com.example.ui.theme.HdAccentPurpleLight
import com.example.ui.theme.HdBackground
import com.example.ui.theme.HdBorder
import com.example.ui.theme.HdErrorBg
import com.example.ui.theme.HdErrorText
import com.example.ui.theme.HdOnPrimaryContainer
import com.example.ui.theme.HdPrimary
import com.example.ui.theme.HdPrimaryContainer
import com.example.ui.theme.HdSuccessBg
import com.example.ui.theme.HdSuccessText
import com.example.ui.theme.HdSurface
import com.example.ui.theme.HdSurfaceContainer
import com.example.ui.theme.HdSurfaceVariant
import com.example.ui.theme.HdTextMuted
import com.example.ui.theme.HdTextPrimary
import com.example.ui.theme.HdTextSecondary

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DashboardScreen(
    state: MainUiState,
    activePluginCount: Int,
    totalRequests: Int,
    totalStreams: Int,
    onToggleServer: () -> Unit,
    onRefreshNetwork: () -> Unit,
    onQueryChange: (String) -> Unit,
    onTypeChange: (String) -> Unit,
    onSeasonChange: (String) -> Unit,
    onEpisodeChange: (String) -> Unit,
    onTestStream: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(6.dp))
            ServerStatusHeroCard(
                isRunning = state.isServerRunning,
                port = state.serverPort,
                localIp = state.localIp,
                activePluginCount = activePluginCount,
                onToggle = onToggleServer,
                onRefreshNetwork = onRefreshNetwork
            )
        }

        if (state.isServerRunning) {
            item {
                QuickStremioActionsCard(
                    localIp = state.localIp,
                    port = state.serverPort,
                    context = context
                )
            }
        }

        item {
            ServerStatsGrid(
                isRunning = state.isServerRunning,
                activePlugins = activePluginCount,
                totalRequests = totalRequests,
                totalStreams = totalStreams,
                wifiConnected = state.wifiConnected
            )
        }

        item {
            StreamTesterCard(
                state = state,
                onQueryChange = onQueryChange,
                onTypeChange = onTypeChange,
                onSeasonChange = onSeasonChange,
                onEpisodeChange = onEpisodeChange,
                onTestStream = onTestStream,
                context = context
            )
        }

        item {
            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

@Composable
fun ServerStatusHeroCard(
    isRunning: Boolean,
    port: Int,
    localIp: String,
    activePluginCount: Int,
    onToggle: () -> Unit,
    onRefreshNetwork: () -> Unit
) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    val heroBg = if (isRunning) HdPrimaryContainer else Color(0xFFF0EBF5)
    val endpointUrl = if (isRunning) "http://$localIp:$port" else "http://127.0.0.1:$port"

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("server_status_card"),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = heroBg),
        border = BorderStroke(1.dp, if (isRunning) HdPrimary.copy(alpha = 0.2f) else HdBorder)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Local Endpoint",
                        style = MaterialTheme.typography.labelMedium.copy(
                            color = HdOnPrimaryContainer.copy(alpha = 0.8f),
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp
                        )
                    )
                    Text(
                        text = endpointUrl,
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            color = HdOnPrimaryContainer,
                            fontSize = 20.sp,
                            letterSpacing = (-0.5).sp,
                            fontFamily = FontFamily.Monospace
                        ),
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }

                IconButton(
                    onClick = onToggle,
                    modifier = Modifier
                        .size(48.dp)
                        .background(
                            if (isRunning) HdPrimary else Color(0xFF6750A4).copy(alpha = 0.15f),
                            RoundedCornerShape(16.dp)
                        )
                        .testTag("server_toggle_btn")
                ) {
                    Icon(
                        imageVector = if (isRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = if (isRunning) "Stop Server" else "Start Server",
                        tint = if (isRunning) Color.White else HdPrimary,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            HorizontalDivider(
                color = HdOnPrimaryContainer.copy(alpha = 0.12f),
                thickness = 1.dp
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "ACTIVE REPO",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = HdOnPrimaryContainer.copy(alpha = 0.65f),
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 10.sp,
                            letterSpacing = 0.5.sp
                        )
                    )
                    Text(
                        text = "nuvioplugins.com/official",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = HdOnPrimaryContainer,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp
                        ),
                        maxLines = 1
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "ACTIVE PLUGINS",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = HdOnPrimaryContainer.copy(alpha = 0.65f),
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 10.sp,
                            letterSpacing = 0.5.sp
                        )
                    )
                    Text(
                        text = if (isRunning) "$activePluginCount Enabled • Port :$port" else "Standby (Tap Start)",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = HdOnPrimaryContainer,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp
                        ),
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
fun QuickStremioActionsCard(
    localIp: String,
    port: Int,
    context: Context
) {
    val manifestUrl = "http://$localIp:$port/manifest.json"
    val stremioDeeplink = "stremio://$localIp:$port/manifest.json"
    val webDashboardUrl = "http://127.0.0.1:$port"

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = HdSurface),
        border = BorderStroke(1.dp, HdBorder)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = HdPrimaryContainer,
                    modifier = Modifier.size(28.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Tv,
                            contentDescription = null,
                            tint = HdPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                Text(
                    text = "Install in Stremio",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = HdTextPrimary,
                        fontSize = 16.sp
                    )
                )
            }

            Text(
                text = "Addon URL ready. Tap to install directly into Stremio or copy link to share on Android TV:",
                style = MaterialTheme.typography.bodySmall.copy(color = HdTextSecondary, fontSize = 12.sp)
            )

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color.White,
                border = BorderStroke(1.dp, HdBorder),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        copyToClipboard(context, manifestUrl, "LAN Manifest URL copied!")
                    }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = manifestUrl,
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = HdPrimary,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "Copy URL",
                        tint = HdTextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = {
                        try {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(stremioDeeplink))
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            copyToClipboard(context, manifestUrl, "Copied! Paste into Stremio Addons search.")
                            Toast.makeText(context, "Stremio app not found. Link copied!", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = HdPrimary,
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Tv, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Open Stremio", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }

                OutlinedButton(
                    onClick = {
                        try {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(webDashboardUrl))
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            Toast.makeText(context, "Cannot open browser", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.weight(1f),
                    border = BorderStroke(1.dp, HdPrimary),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = HdPrimary),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.OpenInBrowser, contentDescription = null, tint = HdPrimary, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Web Portal", color = HdPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
fun ServerStatsGrid(
    isRunning: Boolean,
    activePlugins: Int,
    totalRequests: Int,
    totalStreams: Int,
    wifiConnected: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        StatCard(
            label = "Plugins Active",
            value = "$activePlugins",
            icon = Icons.Default.Sensors,
            accentColor = HdPrimary,
            modifier = Modifier.weight(1f)
        )
        StatCard(
            label = "HTTP Requests",
            value = "$totalRequests",
            icon = Icons.Default.Language,
            accentColor = Color(0xFF00838F),
            modifier = Modifier.weight(1f)
        )
        StatCard(
            label = "Streams Served",
            value = "$totalStreams",
            icon = Icons.Default.Tv,
            accentColor = HdSuccessText,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
fun StatCard(
    label: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = HdSurface),
        border = BorderStroke(1.dp, HdBorder)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = accentColor.copy(alpha = 0.12f),
                modifier = Modifier.size(28.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(imageVector = icon, contentDescription = null, tint = accentColor, modifier = Modifier.size(16.dp))
                }
            }
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold,
                    color = HdTextPrimary,
                    fontSize = 20.sp
                )
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(
                    color = HdTextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StreamTesterCard(
    state: MainUiState,
    onQueryChange: (String) -> Unit,
    onTypeChange: (String) -> Unit,
    onSeasonChange: (String) -> Unit,
    onEpisodeChange: (String) -> Unit,
    onTestStream: () -> Unit,
    context: Context
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("stream_tester_card"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = HdSurface),
        border = BorderStroke(1.dp, HdBorder)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = HdPrimaryContainer,
                    modifier = Modifier.size(28.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            tint = HdPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                Text(
                    text = "Live Stream Scraper Tester",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = HdTextPrimary,
                        fontSize = 16.sp
                    )
                )
            }

            Text(
                text = "Test an IMDB, TMDB, or Kitsu ID to verify your enabled Nuvio plugins and quality sorting output:",
                style = MaterialTheme.typography.bodySmall.copy(color = HdTextSecondary, fontSize = 12.sp)
            )

            // Content Type Chips
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf("movie" to "Movie (IMDB)", "series" to "TV Series", "anime" to "Anime (Kitsu)").forEach { (typeKey, label) ->
                    val selected = state.testType == typeKey
                    FilterChip(
                        selected = selected,
                        onClick = { onTypeChange(typeKey) },
                        label = { Text(label, fontSize = 12.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = HdPrimaryContainer,
                            selectedLabelColor = HdOnPrimaryContainer,
                            containerColor = Color.White,
                            labelColor = HdTextSecondary
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = selected,
                            borderColor = HdBorder,
                            selectedBorderColor = HdPrimary
                        )
                    )
                }
            }

            // Query Input
            OutlinedTextField(
                value = state.testQuery,
                onValueChange = onQueryChange,
                label = { Text(if (state.testType == "anime") "Kitsu ID (e.g. kitsu:1234 or 1234)" else "IMDB / TMDB ID (e.g. tt0111161)") },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("test_query_input"),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = HdPrimary,
                    unfocusedBorderColor = HdBorder,
                    focusedTextColor = HdTextPrimary,
                    unfocusedTextColor = HdTextPrimary,
                    focusedLabelColor = HdPrimary,
                    unfocusedLabelColor = HdTextSecondary,
                    focusedContainerColor = Color.White,
                    unfocusedContainerColor = Color.White
                ),
                shape = RoundedCornerShape(12.dp)
            )

            if (state.testType == "series" || state.testType == "anime") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = state.testSeason,
                        onValueChange = onSeasonChange,
                        label = { Text("Season") },
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
                    OutlinedTextField(
                        value = state.testEpisode,
                        onValueChange = onEpisodeChange,
                        label = { Text("Episode") },
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
                }
            }

            Button(
                onClick = onTestStream,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("test_submit_btn"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = HdPrimary,
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(12.dp),
                enabled = !state.isTesting
            ) {
                if (state.isTesting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Scraping with Nuvio JS plugins...", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                } else {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Run Scraper Query", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }

            // Error notice
            if (state.testError != null) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = HdErrorBg,
                    border = BorderStroke(1.dp, HdErrorText.copy(alpha = 0.3f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = state.testError,
                        style = MaterialTheme.typography.bodySmall.copy(color = HdErrorText),
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            // Results list
            if (state.testStreams.isNotEmpty()) {
                Text(
                    text = "Streams Found (${state.testStreams.size}):",
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = HdSuccessText
                    )
                )

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.testStreams.forEach { stream ->
                        StreamItemCard(stream = stream, context = context)
                    }
                }
            }
        }
    }
}

@Composable
fun StreamItemCard(
    stream: StremioStreamItem,
    context: Context
) {
    val is4K = stream.title.contains("4K", ignoreCase = true) || stream.name.contains("4K", ignoreCase = true)
    val is1080p = stream.title.contains("1080p", ignoreCase = true) || stream.name.contains("1080p", ignoreCase = true)
    val badgeColor = when {
        is4K -> Color(0xFFF57F17)
        is1080p -> HdPrimary
        else -> Color(0xFF00838F)
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color.White,
        border = BorderStroke(1.dp, HdBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    text = stream.name,
                    style = MaterialTheme.typography.labelMedium.copy(
                        color = badgeColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    ),
                    modifier = Modifier.weight(1f)
                )

                val copyTarget = stream.url ?: stream.infoHash
                if (copyTarget != null) {
                    IconButton(
                        onClick = {
                            copyToClipboard(context, copyTarget, if (stream.url != null) "Stream URL copied!" else "InfoHash copied!")
                        },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Copy Stream Identifier",
                            tint = HdTextSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            Text(
                text = stream.title,
                style = MaterialTheme.typography.bodySmall.copy(color = HdTextPrimary),
                lineHeight = 16.sp
            )

            if (stream.url != null) {
                Text(
                    text = stream.url,
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = HdTextMuted,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp
                    ),
                    maxLines = 1
                )
            } else if (stream.infoHash != null) {
                Text(
                    text = "magnet:?xt=urn:btih:${stream.infoHash}",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = HdTextMuted,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp
                    ),
                    maxLines = 1
                )
            }
        }
    }
}

fun copyToClipboard(context: Context, text: String, toastMessage: String = "Copied to clipboard") {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText("Nuvio Server", text)
    clipboard.setPrimaryClip(clip)
    Toast.makeText(context, toastMessage, Toast.LENGTH_SHORT).show()
}

