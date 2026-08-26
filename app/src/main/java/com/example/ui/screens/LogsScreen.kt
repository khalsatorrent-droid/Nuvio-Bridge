package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.ListAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.ServerLogEntity
import com.example.engine.ExecutionStepLog
import com.example.engine.StepLogLevel
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
import com.example.ui.theme.HdTextMuted
import com.example.ui.theme.HdTextPrimary
import com.example.ui.theme.HdTextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LogsScreen(
    serverLogs: List<ServerLogEntity>,
    executionLogs: List<ExecutionStepLog>,
    onClearLogs: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var selectedCategoryFilter by remember { mutableStateOf("ALL") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(6.dp))

        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Engine & Server Logs",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = HdTextPrimary
                    )
                )
                Text(
                    text = "${executionLogs.size} step logs • ${serverLogs.size} HTTP requests",
                    style = MaterialTheme.typography.labelSmall.copy(color = HdTextSecondary)
                )
            }

            if (serverLogs.isNotEmpty() || executionLogs.isNotEmpty()) {
                OutlinedButton(
                    onClick = onClearLogs,
                    border = BorderStroke(1.dp, HdBorder),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = HdErrorText),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text("Clear All", fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp))
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Tab Selector
        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = HdSurface,
            contentColor = HdPrimary,
            indicator = { tabPositions ->
                TabRowDefaults.SecondaryIndicator(
                    Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                    color = HdPrimary
                )
            },
            divider = {},
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(HdSurface)
        ) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = {
                    Text(
                        "Execution Steps (${executionLogs.size})",
                        fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Normal,
                        fontSize = 13.sp
                    )
                }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = {
                    Text(
                        "HTTP Requests (${serverLogs.size})",
                        fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Normal,
                        fontSize = 13.sp
                    )
                }
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (selectedTab == 0) {
            // Execution Steps View
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                listOf("ALL", "ERRORS", "RESOLVER", "NETWORK", "SCRAPERS", "SERVER").forEach { filter ->
                    val isSelected = selectedCategoryFilter == filter
                    FilterChip(
                        selected = isSelected,
                        onClick = { selectedCategoryFilter = filter },
                        label = { Text(filter, fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = HdPrimaryContainer,
                            selectedLabelColor = HdOnPrimaryContainer,
                            containerColor = HdSurface,
                            labelColor = HdTextSecondary
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = isSelected,
                            borderColor = if (isSelected) HdPrimary else HdBorder,
                            selectedBorderColor = HdPrimary
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            val filteredLogs = executionLogs.filter { log ->
                when (selectedCategoryFilter) {
                    "ALL" -> true
                    "ERRORS" -> log.level == StepLogLevel.ERROR || log.failureReason != null
                    "RESOLVER" -> log.category.contains("RESOLVER", ignoreCase = true) || log.category.contains("ARM", ignoreCase = true) || log.category.contains("TMDB", ignoreCase = true)
                    "NETWORK" -> log.category.contains("NETWORK", ignoreCase = true) || log.category.contains("FETCH", ignoreCase = true)
                    "SCRAPERS" -> log.category.contains("SCRAPER", ignoreCase = true) || !listOf("RESOLVER", "NETWORK", "SERVER", "JS-RUNTIME", "JS-CONSOLE", "FORMATTER").any { log.category.contains(it, ignoreCase = true) }
                    "SERVER" -> log.category.contains("SERVER", ignoreCase = true) || log.category.contains("FORMATTER", ignoreCase = true)
                    else -> true
                }
            }

            if (filteredLogs.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ListAlt,
                            contentDescription = null,
                            tint = HdTextMuted,
                            modifier = Modifier.size(48.dp)
                        )
                        Text(
                            text = "No execution steps logged yet",
                            style = MaterialTheme.typography.bodyMedium.copy(color = HdTextSecondary, fontWeight = FontWeight.SemiBold)
                        )
                        Text(
                            text = "Run a test query in the Dashboard or trigger a request from Stremio to see real-time steps and error reasons.",
                            style = MaterialTheme.typography.bodySmall.copy(color = HdTextMuted),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredLogs, key = { it.id }) { stepLog ->
                        ExecutionStepCard(stepLog = stepLog)
                    }
                    item {
                        Spacer(modifier = Modifier.height(24.dp))
                    }
                }
            }
        } else {
            // Server Logs View
            if (serverLogs.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ListAlt,
                            contentDescription = null,
                            tint = HdTextMuted,
                            modifier = Modifier.size(48.dp)
                        )
                        Text(
                            text = "No server requests yet",
                            style = MaterialTheme.typography.bodyMedium.copy(color = HdTextSecondary, fontWeight = FontWeight.SemiBold)
                        )
                        Text(
                            text = "Start the server and query a stream from Stremio to see real-time HTTP logs here.",
                            style = MaterialTheme.typography.bodySmall.copy(color = HdTextMuted),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(serverLogs, key = { it.id }) { log ->
                        LogItemCard(log = log)
                    }
                    item {
                        Spacer(modifier = Modifier.height(24.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun ExecutionStepCard(stepLog: ExecutionStepLog) {
    val dateStr = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(stepLog.timestamp))

    val (badgeBg, badgeTextColor, levelIcon) = when (stepLog.level) {
        StepLogLevel.SUCCESS -> Triple(HdSuccessBg, HdSuccessText, Icons.Default.CheckCircle)
        StepLogLevel.ERROR -> Triple(HdErrorBg, HdErrorText, Icons.Default.Error)
        StepLogLevel.WARNING -> Triple(Color(0xFF3E2723), Color(0xFFFFB74D), Icons.Default.Warning)
        StepLogLevel.DEBUG -> Triple(Color(0xFF263238), Color(0xFF90A4AE), Icons.Default.Info)
        StepLogLevel.INFO -> Triple(HdPrimaryContainer, HdOnPrimaryContainer, Icons.Default.PlayArrow)
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = HdSurface,
        border = BorderStroke(1.dp, if (stepLog.level == StepLogLevel.ERROR) HdErrorText.copy(alpha = 0.5f) else HdBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Category badge
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color(0xFF1E293B)
                    ) {
                        Text(
                            text = stepLog.category,
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = Color(0xFF93C5FD),
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp
                            ),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }

                    // Level Badge
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = badgeBg
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Icon(
                                imageVector = levelIcon,
                                contentDescription = null,
                                tint = badgeTextColor,
                                modifier = Modifier.size(11.dp)
                            )
                            Text(
                                text = stepLog.level.name,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = badgeTextColor,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 10.sp
                                )
                            )
                        }
                    }

                    if (stepLog.durationMs != null) {
                        Text(
                            text = "${stepLog.durationMs}ms",
                            style = MaterialTheme.typography.labelSmall.copy(color = HdTextSecondary, fontSize = 10.sp)
                        )
                    }
                }

                Text(
                    text = dateStr,
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = HdTextMuted,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp
                    )
                )
            }

            // Step Name
            Text(
                text = stepLog.step,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = HdTextPrimary,
                    fontSize = 13.sp
                )
            )

            // Step Details
            Text(
                text = stepLog.details,
                style = MaterialTheme.typography.bodySmall.copy(
                    color = HdTextSecondary,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
            )

            // Explicit Failure Reason if present
            if (!stepLog.failureReason.isNullOrEmpty()) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = HdErrorBg.copy(alpha = 0.7f),
                    border = BorderStroke(1.dp, HdErrorText.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Failure Reason",
                            tint = HdErrorText,
                            modifier = Modifier.size(16.dp)
                        )
                        Column {
                            Text(
                                text = "Failure Reason:",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = HdErrorText,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 10.sp
                                )
                            )
                            Text(
                                text = stepLog.failureReason,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = HdErrorText,
                                    fontSize = 11.sp
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LogItemCard(log: ServerLogEntity) {
    val dateStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(log.timestamp))
    val isOk = log.status in 200..299
    val statusBg = if (isOk) HdSuccessBg else HdErrorBg
    val statusText = if (isOk) HdSuccessText else HdErrorText

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = HdSurface,
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
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = HdPrimaryContainer
                    ) {
                        Text(
                            text = log.method,
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = HdOnPrimaryContainer,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp
                            ),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = statusBg
                    ) {
                        Text(
                            text = "${log.status}",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = statusText,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp
                            ),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }

                    Text(
                        text = "${log.durationMs} ms",
                        style = MaterialTheme.typography.labelSmall.copy(color = HdTextSecondary, fontSize = 11.sp)
                    )
                }

                Text(
                    text = dateStr,
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = HdTextMuted,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                )
            }

            Text(
                text = log.path,
                style = MaterialTheme.typography.bodySmall.copy(
                    color = HdTextPrimary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp
                ),
                maxLines = 2
            )

            if (log.streamsFound > 0) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "⚡ Delivered ${log.streamsFound} stream sources",
                        style = MaterialTheme.typography.labelSmall.copy(color = HdPrimary, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
                    )
                    Text(
                        text = "From: ${log.clientIp}",
                        style = MaterialTheme.typography.labelSmall.copy(color = HdTextMuted, fontSize = 10.sp)
                    )
                }
            }
        }
    }
}
