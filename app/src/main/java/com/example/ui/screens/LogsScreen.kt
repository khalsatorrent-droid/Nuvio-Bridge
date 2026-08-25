package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ListAlt
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.ServerLogEntity
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

@Composable
fun LogsScreen(
    logs: List<ServerLogEntity>,
    onClearLogs: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Live Server Requests",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = HdTextPrimary
                    )
                )
                Text(
                    text = "${logs.size} recent requests logged",
                    style = MaterialTheme.typography.labelSmall.copy(color = HdTextSecondary)
                )
            }

            if (logs.isNotEmpty()) {
                OutlinedButton(
                    onClick = onClearLogs,
                    border = BorderStroke(1.dp, HdBorder),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = HdErrorText),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text("Clear", fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp))
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (logs.isEmpty()) {
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
                items(logs, key = { it.id }) { log ->
                    LogItemCard(log = log)
                }
                item {
                    Spacer(modifier = Modifier.height(24.dp))
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

