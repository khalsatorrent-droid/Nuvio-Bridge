package com.example.ui.screens

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.ServerLogEntity
import com.example.engine.AppLogger
import com.example.engine.LogEntry
import com.example.engine.StepLogLevel
import com.example.ui.theme.HdBackground
import com.example.ui.theme.HdBorder
import com.example.ui.theme.HdErrorText
import com.example.ui.theme.HdPrimary
import com.example.ui.theme.HdPrimaryContainer
import com.example.ui.theme.HdSurface
import com.example.ui.theme.HdTextMuted
import com.example.ui.theme.HdTextPrimary
import com.example.ui.theme.HdTextSecondary
import kotlinx.coroutines.launch

private val ColorOk = Color(0xFF4ADE80)
private val ColorErr = Color(0xFFF87171)
private val ColorWarn = Color(0xFFFBBF24)
private val ColorInfo = Color(0xFF93C5FD)
private val ColorDbg = Color(0xFF94A3B8)
private val ColorTag = Color(0xFF38BDF8)
private val ColorTerminalBg = Color(0xFF0F172A)

@Composable
fun LogsScreen(
    serverLogs: List<ServerLogEntity> = emptyList(),
    executionLogs: List<LogEntry>,
    onClearLogs: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf("ALL") }
    var autoScrollEnabled by remember { mutableStateOf(true) }

    // Filter in-memory with negligible CPU
    val filteredLogs = remember(executionLogs, selectedFilter, searchQuery) {
        executionLogs.filter { entry ->
            val matchesCategory = when (selectedFilter) {
                "ALL" -> true
                "ERRORS" -> entry.level == StepLogLevel.ERROR || !entry.reason.isNullOrEmpty()
                "RESOLVER" -> entry.tag.contains("RESOLVER", ignoreCase = true)
                "SCRAPERS" -> !listOf("RESOLVER", "NETWORK", "SERVER", "JS-RUNTIME", "JS-CONSOLE").any { entry.tag.contains(it, ignoreCase = true) }
                "SERVER" -> entry.tag.contains("SERVER", ignoreCase = true)
                "NETWORK" -> entry.tag.contains("NETWORK", ignoreCase = true)
                else -> true
            }

            val matchesSearch = if (searchQuery.isBlank()) {
                true
            } else {
                entry.message.contains(searchQuery, ignoreCase = true) ||
                        entry.tag.contains(searchQuery, ignoreCase = true) ||
                        (entry.reason != null && entry.reason.contains(searchQuery, ignoreCase = true))
            }

            matchesCategory && matchesSearch
        }
    }

    // Auto-scroll when new entries arrive if auto-scroll is active
    LaunchedEffect(filteredLogs.size, autoScrollEnabled) {
        if (autoScrollEnabled && filteredLogs.isNotEmpty()) {
            listState.scrollToItem(filteredLogs.size - 1)
        }
    }

    val isScrolledToBottom by remember {
        derivedStateOf {
            val totalItems = listState.layoutInfo.totalItemsCount
            if (totalItems == 0) true
            else {
                val lastVisibleItemIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                lastVisibleItemIndex >= totalItems - 2
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            Spacer(modifier = Modifier.height(4.dp))

            // Console Header & Actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (executionLogs.isNotEmpty()) ColorOk else ColorDbg)
                    )
                    Text(
                        text = "Continuous Live Log",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = HdTextPrimary
                        )
                    )
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = HdSurface
                    ) {
                        Text(
                            text = "${executionLogs.size} lines",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = HdTextSecondary,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp
                            ),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    // Copy Full Continuous Log
                    OutlinedButton(
                        onClick = {
                            val fullText = AppLogger.getAllText()
                            if (fullText.isNotEmpty()) {
                                clipboardManager.setText(AnnotatedString(fullText))
                                Toast.makeText(context, "Full log copied to clipboard", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "No logs to copy", Toast.LENGTH_SHORT).show()
                            }
                        },
                        border = BorderStroke(1.dp, HdBorder),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = HdTextPrimary),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                        Text("Copy", fontSize = 11.sp, modifier = Modifier.padding(start = 4.dp))
                    }

                    // Clear
                    OutlinedButton(
                        onClick = onClearLogs,
                        border = BorderStroke(1.dp, HdBorder),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = HdErrorText),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(14.dp))
                        Text("Clear", fontSize = 11.sp, modifier = Modifier.padding(start = 4.dp))
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Search Bar & Filter Chips
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Filter logs...", fontSize = 12.sp, color = HdTextMuted) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = HdTextMuted, modifier = Modifier.size(16.dp)) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }, modifier = Modifier.size(24.dp)) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear search", tint = HdTextMuted, modifier = Modifier.size(14.dp))
                            }
                        }
                    },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = HdPrimary,
                        unfocusedBorderColor = HdBorder,
                        focusedContainerColor = HdSurface,
                        unfocusedContainerColor = HdSurface,
                        focusedTextColor = HdTextPrimary,
                        unfocusedTextColor = HdTextPrimary
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Filter Chips Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf("ALL", "ERRORS", "RESOLVER", "SCRAPERS", "SERVER", "NETWORK").forEach { filter ->
                    val isSelected = selectedFilter == filter
                    FilterChip(
                        selected = isSelected,
                        onClick = { selectedFilter = filter },
                        label = { Text(filter, fontSize = 10.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = HdPrimaryContainer,
                            selectedLabelColor = HdPrimary,
                            containerColor = HdSurface,
                            labelColor = HdTextSecondary
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = isSelected,
                            borderColor = if (isSelected) HdPrimary else HdBorder,
                            selectedBorderColor = HdPrimary
                        ),
                        modifier = Modifier.height(28.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Main Continuous Terminal Window
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = ColorTerminalBg,
                border = BorderStroke(1.dp, Color(0xFF1E293B)),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                if (filteredLogs.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Terminal,
                                contentDescription = null,
                                tint = ColorDbg,
                                modifier = Modifier.size(36.dp)
                            )
                            Text(
                                text = if (executionLogs.isEmpty()) "Waiting for activity..." else "No matching log entries",
                                style = MaterialTheme.typography.bodySmall.copy(color = HdTextSecondary, fontWeight = FontWeight.SemiBold)
                            )
                            Text(
                                text = "Run a test query in the Dashboard or trigger a request from Stremio.",
                                style = MaterialTheme.typography.labelSmall.copy(color = HdTextMuted),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                } else {
                    SelectionContainer {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(
                                items = filteredLogs,
                                key = { it.id }
                            ) { entry ->
                                LogRow(entry = entry)
                            }
                            item {
                                Spacer(modifier = Modifier.height(30.dp))
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
        }

        // Floating Jump-To-Bottom Button
        if (!isScrolledToBottom && filteredLogs.isNotEmpty()) {
            FloatingActionButton(
                onClick = {
                    autoScrollEnabled = true
                    coroutineScope.launch {
                        listState.animateScrollToItem(filteredLogs.size - 1)
                    }
                },
                shape = CircleShape,
                containerColor = HdPrimary,
                contentColor = Color.White,
                elevation = FloatingActionButtonDefaults.elevation(4.dp),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = 16.dp, end = 8.dp)
                    .size(40.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = "Scroll to bottom",
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun LogRow(entry: LogEntry) {
    val levelColor = when (entry.level) {
        StepLogLevel.SUCCESS -> ColorOk
        StepLogLevel.ERROR -> ColorErr
        StepLogLevel.WARNING -> ColorWarn
        StepLogLevel.DEBUG -> ColorDbg
        StepLogLevel.INFO -> ColorInfo
    }

    val levelTag = when (entry.level) {
        StepLogLevel.SUCCESS -> "OK"
        StepLogLevel.ERROR -> "ERR"
        StepLogLevel.WARNING -> "WRN"
        StepLogLevel.DEBUG -> "DBG"
        StepLogLevel.INFO -> "INF"
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Top
    ) {
        // Timestamp
        Text(
            text = entry.timeFormatted,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = ColorDbg
            )
        )

        // Level
        Text(
            text = levelTag,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = levelColor
            )
        )

        // Category Tag
        Text(
            text = "[${entry.tag}]",
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = ColorTag
            )
        )

        // Message & optional Failure Reason
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.message,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = if (entry.level == StepLogLevel.ERROR) ColorErr else HdTextPrimary
                )
            )
            if (!entry.reason.isNullOrEmpty()) {
                Text(
                    text = "↳ Reason: ${entry.reason}",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = ColorErr
                    )
                )
            }
        }
    }
}
