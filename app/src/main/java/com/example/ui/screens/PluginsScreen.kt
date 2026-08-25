package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.LibraryRepoItem
import com.example.data.model.PluginEntity
import com.example.data.model.RepoEntity
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
import com.example.ui.theme.HdTextMuted
import com.example.ui.theme.HdTextPrimary
import com.example.ui.theme.HdTextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun PluginsScreen(
    plugins: List<PluginEntity>,
    repos: List<RepoEntity>,
    libraryRepos: List<LibraryRepoItem>,
    isLibraryLoading: Boolean,
    isSyncing: Boolean,
    syncMessage: String?,
    onTogglePlugin: (String, Boolean) -> Unit,
    onSavePlugin: (PluginEntity) -> Unit,
    onDeletePlugin: (String) -> Unit,
    onRestoreDefaults: () -> Unit,
    onAddRepo: (String, String, String) -> Unit,
    onSyncRepo: (RepoEntity) -> Unit,
    onDeleteRepo: (String) -> Unit,
    onRefreshLibrary: () -> Unit,
    onInstallLibraryRepo: (LibraryRepoItem) -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var showAddPluginDialog by remember { mutableStateOf(false) }
    var showAddRepoDialog by remember { mutableStateOf(false) }
    var viewingPluginForEdit by remember { mutableStateOf<PluginEntity?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategoryFilter by remember { mutableStateOf("All") }

    val context = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(6.dp))

        // 3 Navigation Tabs: Library Explorer, Installed Plugins, Connected Repos
        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = HdSurface,
            contentColor = HdPrimary,
            modifier = Modifier.clip(RoundedCornerShape(14.dp)),
            indicator = { tabPositions ->
                TabRowDefaults.SecondaryIndicator(
                    modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                    color = HdPrimary,
                    height = 3.dp
                )
            }
        ) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(Icons.Default.Explore, contentDescription = null, modifier = Modifier.size(15.dp))
                        Text(
                            "Plugin Library",
                            fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Medium,
                            color = if (selectedTab == 0) HdPrimary else HdTextSecondary,
                            fontSize = 12.sp
                        )
                    }
                }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(Icons.Default.Extension, contentDescription = null, modifier = Modifier.size(15.dp))
                        Text(
                            "Plugins (${plugins.size})",
                            fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Medium,
                            color = if (selectedTab == 1) HdPrimary else HdTextSecondary,
                            fontSize = 12.sp
                        )
                    }
                }
            )
            Tab(
                selected = selectedTab == 2,
                onClick = { selectedTab = 2 },
                text = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(15.dp))
                        Text(
                            "Repos (${repos.size})",
                            fontWeight = if (selectedTab == 2) FontWeight.Bold else FontWeight.Medium,
                            color = if (selectedTab == 2) HdPrimary else HdTextSecondary,
                            fontSize = 12.sp
                        )
                    }
                }
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Syncing Global Progress
        if (isSyncing) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = HdPrimaryContainer,
                border = BorderStroke(1.dp, HdPrimary.copy(alpha = 0.3f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(color = HdPrimary, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        syncMessage ?: "Syncing provider repository...",
                        style = MaterialTheme.typography.bodySmall.copy(color = HdOnPrimaryContainer, fontWeight = FontWeight.SemiBold)
                    )
                }
            }
        }

        when (selectedTab) {
            0 -> {
                // ==================== TAB 0: ONLINE PLUGIN LIBRARY (VERCEL) ====================
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        // Header Banner for nuvio-plugin-library.vercel.app
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(containerColor = HdSurface),
                            border = BorderStroke(1.dp, HdPrimary.copy(alpha = 0.25f))
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
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
                                            shape = RoundedCornerShape(8.dp),
                                            color = HdPrimaryContainer,
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(
                                                    Icons.Default.AutoAwesome,
                                                    contentDescription = null,
                                                    tint = HdPrimary,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }
                                        Column {
                                            Text(
                                                "Nuvio Plugin Library",
                                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, color = HdTextPrimary)
                                            )
                                            Text(
                                                "nuvio-plugin-library.vercel.app",
                                                style = MaterialTheme.typography.labelSmall.copy(color = HdPrimary, fontWeight = FontWeight.SemiBold)
                                            )
                                        }
                                    }

                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        OutlinedButton(
                                            onClick = onRefreshLibrary,
                                            border = BorderStroke(1.dp, HdBorder),
                                            shape = RoundedCornerShape(8.dp),
                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = HdTextSecondary),
                                            modifier = Modifier.height(34.dp)
                                        ) {
                                            if (isLibraryLoading) {
                                                CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp, color = HdPrimary)
                                            } else {
                                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text("Refresh", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }

                                        Button(
                                            onClick = {
                                                try {
                                                    val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://nuvio-plugin-library.vercel.app/"))
                                                    context.startActivity(browserIntent)
                                                } catch (e: Exception) {
                                                    Toast.makeText(context, "Could not open browser", Toast.LENGTH_SHORT).show()
                                                }
                                            },
                                            shape = RoundedCornerShape(8.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = HdPrimary, contentColor = Color.White),
                                            modifier = Modifier.height(34.dp)
                                        ) {
                                            Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(14.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Web Page", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }

                                Text(
                                    "Browse verified community provider repositories from the official Nuvio Plugin Library and install scrapers with 1 click.",
                                    style = MaterialTheme.typography.bodySmall.copy(color = HdTextSecondary, fontSize = 12.sp)
                                )
                            }
                        }
                    }

                    // Search & Filter
                    item {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Search repositories or scrapers...", fontSize = 12.sp, color = HdTextMuted) },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = HdTextSecondary, modifier = Modifier.size(18.dp)) },
                            modifier = Modifier.fillMaxWidth(),
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

                    item {
                        val categories = listOf("All", "4K UHD", "Movies", "Anime", "All-in-One", "Multi-Audio")
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            items(categories) { cat ->
                                val isSelected = selectedCategoryFilter == cat
                                FilterChip(
                                    selected = isSelected,
                                    onClick = { selectedCategoryFilter = cat },
                                    label = { Text(cat, fontSize = 11.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = HdPrimaryContainer,
                                        selectedLabelColor = HdOnPrimaryContainer,
                                        containerColor = HdSurface,
                                        labelColor = HdTextSecondary
                                    ),
                                    border = BorderStroke(1.dp, if (isSelected) HdPrimary else HdBorder),
                                    shape = RoundedCornerShape(20.dp)
                                )
                            }
                        }
                    }

                    // Filtered Library Items
                    val filteredList = libraryRepos.filter { item ->
                        val matchesQuery = searchQuery.isBlank() ||
                                item.name.contains(searchQuery, ignoreCase = true) ||
                                item.description.contains(searchQuery, ignoreCase = true) ||
                                item.author.contains(searchQuery, ignoreCase = true) ||
                                item.tags.any { it.contains(searchQuery, ignoreCase = true) }

                        val matchesCat = selectedCategoryFilter == "All" ||
                                item.tags.any { it.contains(selectedCategoryFilter, ignoreCase = true) } ||
                                item.name.contains(selectedCategoryFilter, ignoreCase = true)

                        matchesQuery && matchesCat
                    }

                    items(filteredList, key = { it.id }) { libraryItem ->
                        val isInstalled = repos.any { it.url.equals(libraryItem.manifestUrl, ignoreCase = true) }
                        val installedRepo = repos.find { it.url.equals(libraryItem.manifestUrl, ignoreCase = true) }

                        LibraryRepoCard(
                            item = libraryItem,
                            isInstalled = isInstalled,
                            installedPluginCount = installedRepo?.pluginCount ?: 0,
                            onInstall = { onInstallLibraryRepo(libraryItem) }
                        )
                    }

                    item {
                        Spacer(modifier = Modifier.height(24.dp))
                    }
                }
            }

            1 -> {
                // ==================== TAB 1: INSTALLED PLUGINS ====================
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = onRestoreDefaults,
                        border = BorderStroke(1.dp, HdBorder),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = HdTextSecondary)
                    ) {
                        Icon(Icons.Default.RestartAlt, contentDescription = null, tint = HdTextSecondary, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Restore Core", color = HdTextSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }

                    Button(
                        onClick = { showAddPluginDialog = true },
                        colors = ButtonDefaults.buttonColors(containerColor = HdPrimary, contentColor = Color.White),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.testTag("add_plugin_btn")
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Add Custom JS", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(plugins, key = { it.id }) { plugin ->
                        PluginItemCard(
                            plugin = plugin,
                            onToggle = { isEnabled -> onTogglePlugin(plugin.id, isEnabled) },
                            onEdit = { viewingPluginForEdit = plugin },
                            onDelete = { onDeletePlugin(plugin.id) }
                        )
                    }
                    item {
                        Spacer(modifier = Modifier.height(24.dp))
                    }
                }
            }

            2 -> {
                // ==================== TAB 2: CONNECTED REPOSITORIES ====================
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Active Repositories (${repos.size})",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = HdTextPrimary)
                    )

                    Button(
                        onClick = { showAddRepoDialog = true },
                        colors = ButtonDefaults.buttonColors(containerColor = HdPrimary, contentColor = Color.White),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Add Custom URL", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(repos, key = { it.id }) { repo ->
                        RepoItemCard(
                            repo = repo,
                            onSync = { onSyncRepo(repo) },
                            onDelete = { onDeleteRepo(repo.id) }
                        )
                    }
                    item {
                        Spacer(modifier = Modifier.height(24.dp))
                    }
                }
            }
        }
    }

    // Add / Edit Plugin Dialog
    if (showAddPluginDialog || viewingPluginForEdit != null) {
        val editingPlugin = viewingPluginForEdit
        PluginEditorDialog(
            initialPlugin = editingPlugin,
            onDismiss = {
                showAddPluginDialog = false
                viewingPluginForEdit = null
            },
            onSave = { saved ->
                onSavePlugin(saved)
                showAddPluginDialog = false
                viewingPluginForEdit = null
            }
        )
    }

    // Add Repo Dialog
    if (showAddRepoDialog) {
        AddRepoDialog(
            onDismiss = { showAddRepoDialog = false },
            onAdd = { name, url, desc ->
                onAddRepo(name, url, desc)
                showAddRepoDialog = false
            }
        )
    }
}

@Composable
fun LibraryRepoCard(
    item: LibraryRepoItem,
    isInstalled: Boolean,
    installedPluginCount: Int,
    onInstall: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = HdSurface),
        border = BorderStroke(1.dp, if (isInstalled) HdPrimary.copy(alpha = 0.4f) else HdBorder)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (isInstalled) HdPrimaryContainer else Color(0xFFF0F0F0),
                        modifier = Modifier.size(38.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = item.name.take(2).uppercase(),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = if (isInstalled) HdPrimary else HdTextSecondary,
                                    fontSize = 13.sp
                                )
                            )
                        }
                    }

                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = item.name,
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, color = HdTextPrimary)
                            )
                            if (item.isVerified) {
                                Icon(Icons.Default.Verified, contentDescription = "Verified", tint = HdPrimary, modifier = Modifier.size(14.dp))
                            }
                        }
                        Text(
                            text = "by ${item.author} • ~${item.estimatedProviders} providers",
                            style = MaterialTheme.typography.labelSmall.copy(color = HdTextSecondary, fontSize = 11.sp)
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = if (item.badge == "Featured" || item.badge == "Top Rated") HdPrimaryContainer else Color(0xFFF3F4F6),
                    border = BorderStroke(1.dp, if (item.badge == "Featured") HdPrimary.copy(alpha = 0.3f) else HdBorder)
                ) {
                    Text(
                        text = item.badge,
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = if (item.badge == "Featured" || item.badge == "Top Rated") HdOnPrimaryContainer else HdTextSecondary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }

            Text(
                text = item.description,
                style = MaterialTheme.typography.bodySmall.copy(color = HdTextSecondary, fontSize = 12.sp)
            )

            // Tags
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                item.tags.forEach { tag ->
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color.White,
                        border = BorderStroke(1.dp, HdBorder)
                    ) {
                        Text(
                            text = tag,
                            style = MaterialTheme.typography.labelSmall.copy(color = HdTextSecondary, fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            // Manifest Link URL Snippet
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFFF8FAFC),
                border = BorderStroke(1.dp, HdBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = item.manifestUrl,
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = HdPrimary,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    ),
                    modifier = Modifier.padding(6.dp),
                    maxLines = 1
                )
            }

            // Install / Sync Action Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isInstalled) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = HdSuccessText, modifier = Modifier.size(16.dp))
                        Text(
                            text = "Installed ($installedPluginCount scrapers)",
                            style = MaterialTheme.typography.labelSmall.copy(color = HdSuccessText, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        )
                    }
                } else {
                    Text(
                        text = "Ready to Install",
                        style = MaterialTheme.typography.labelSmall.copy(color = HdTextSecondary, fontSize = 11.sp)
                    )
                }

                Button(
                    onClick = onInstall,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isInstalled) HdSurfaceContainer else HdPrimary,
                        contentColor = if (isInstalled) HdPrimary else Color.White
                    ),
                    border = if (isInstalled) BorderStroke(1.dp, HdPrimary.copy(alpha = 0.3f)) else null,
                    modifier = Modifier.height(34.dp)
                ) {
                    Icon(
                        imageVector = if (isInstalled) Icons.Default.Sync else Icons.Default.Download,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (isInstalled) "Re-sync Repo" else "Install Repo",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
fun PluginItemCard(
    plugin: PluginEntity,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = HdSurface),
        border = BorderStroke(1.dp, if (plugin.isEnabled) HdPrimary.copy(alpha = 0.3f) else HdBorder)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (plugin.isEnabled) HdPrimaryContainer else Color(0xFFE0E0E0),
                        modifier = Modifier.size(36.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = plugin.name.take(2).uppercase(),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = if (plugin.isEnabled) HdPrimary else HdTextSecondary,
                                    fontSize = 12.sp
                                )
                            )
                        }
                    }

                    Column {
                        Text(
                            text = plugin.name,
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = if (plugin.isEnabled) HdTextPrimary else HdTextSecondary
                            )
                        )
                        Text(
                            text = "v${plugin.version} • by ${plugin.author}",
                            style = MaterialTheme.typography.labelSmall.copy(color = HdTextSecondary, fontSize = 11.sp)
                        )
                    }
                }

                Switch(
                    checked = plugin.isEnabled,
                    onCheckedChange = onToggle,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = HdPrimary,
                        uncheckedThumbColor = HdTextSecondary,
                        uncheckedTrackColor = HdBorder
                    )
                )
            }

            Text(
                text = plugin.description,
                style = MaterialTheme.typography.bodySmall.copy(
                    color = if (plugin.isEnabled) HdTextPrimary else HdTextMuted,
                    fontSize = 12.sp
                )
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = HdPrimaryContainer,
                    border = BorderStroke(1.dp, HdPrimary.copy(alpha = 0.2f))
                ) {
                    Text(
                        text = "Types: ${plugin.supportedTypes}",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = HdOnPrimaryContainer,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold
                        ),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Code, contentDescription = "View Code", tint = HdPrimary, modifier = Modifier.size(18.dp))
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = HdErrorText, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun RepoItemCard(
    repo: RepoEntity,
    onSync: () -> Unit,
    onDelete: () -> Unit
) {
    val dateStr = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(repo.lastSynced))

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = HdSurface),
        border = BorderStroke(1.dp, HdBorder)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Language, contentDescription = null, tint = HdPrimary, modifier = Modifier.size(20.dp))
                    Text(
                        text = repo.name,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, color = HdTextPrimary)
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(onClick = onSync, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Sync, contentDescription = "Sync", tint = HdPrimary, modifier = Modifier.size(18.dp))
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = HdErrorText, modifier = Modifier.size(18.dp))
                    }
                }
            }

            Text(
                text = repo.description,
                style = MaterialTheme.typography.bodySmall.copy(color = HdTextSecondary, fontSize = 12.sp)
            )

            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color.White,
                border = BorderStroke(1.dp, HdBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = repo.url,
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = HdPrimary,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    ),
                    modifier = Modifier.padding(8.dp),
                    maxLines = 1
                )
            }

            Text(
                text = "Last synced: $dateStr • ${repo.pluginCount} scrapers installed",
                style = MaterialTheme.typography.labelSmall.copy(color = HdTextSecondary, fontSize = 10.sp)
            )
        }
    }
}

@Composable
fun PluginEditorDialog(
    initialPlugin: PluginEntity?,
    onDismiss: () -> Unit,
    onSave: (PluginEntity) -> Unit
) {
    var name by remember { mutableStateOf(initialPlugin?.name ?: "Custom Scraper") }
    var desc by remember { mutableStateOf(initialPlugin?.description ?: "Custom JS scraper plugin for Nuvio") }
    var author by remember { mutableStateOf(initialPlugin?.author ?: "User") }
    var version by remember { mutableStateOf(initialPlugin?.version ?: "1.0.0") }
    var types by remember { mutableStateOf(initialPlugin?.supportedTypes ?: "movie,series") }
    var jsCode by remember {
        mutableStateOf(
            initialPlugin?.jsCode ?: """
                async function getStreams(params) {
                    const streams = [];
                    const { type, id, season, episode, imdbId } = params;
                    const targetId = imdbId || id;
                    
                    const embedUrl = "https://vidsrc.xyz/embed/" + (type === "movie" ? "movie/" : "tv/") + targetId;
                    
                    streams.push({
                        name: "[Nuvio] Custom Scraper",
                        title: "1080p FHD • Fast CDN",
                        url: embedUrl,
                        quality: "1080p",
                        provider: "Custom"
                    });
                    
                    return streams;
                }
            """.trimIndent()
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (initialPlugin != null) "Edit Scraper: ${initialPlugin.name}" else "Add Custom Nuvio Plugin",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = HdTextPrimary)
            )
        },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Plugin Name") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = HdPrimary,
                            unfocusedBorderColor = HdBorder,
                            focusedTextColor = HdTextPrimary,
                            unfocusedTextColor = HdTextPrimary
                        ),
                        shape = RoundedCornerShape(10.dp)
                    )
                }
                item {
                    OutlinedTextField(
                        value = desc,
                        onValueChange = { desc = it },
                        label = { Text("Description") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = HdPrimary,
                            unfocusedBorderColor = HdBorder,
                            focusedTextColor = HdTextPrimary,
                            unfocusedTextColor = HdTextPrimary
                        ),
                        shape = RoundedCornerShape(10.dp)
                    )
                }
                item {
                    OutlinedTextField(
                        value = types,
                        onValueChange = { types = it },
                        label = { Text("Supported Types (comma-separated)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = HdPrimary,
                            unfocusedBorderColor = HdBorder,
                            focusedTextColor = HdTextPrimary,
                            unfocusedTextColor = HdTextPrimary
                        ),
                        shape = RoundedCornerShape(10.dp)
                    )
                }
                item {
                    Text(
                        text = "JavaScript Scraper Code:",
                        style = MaterialTheme.typography.labelMedium.copy(color = HdPrimary, fontWeight = FontWeight.Bold)
                    )
                    OutlinedTextField(
                        value = jsCode,
                        onValueChange = { jsCode = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = HdPrimary,
                            unfocusedBorderColor = HdBorder,
                            focusedTextColor = HdTextPrimary,
                            unfocusedTextColor = HdTextPrimary,
                            focusedContainerColor = Color.White,
                            unfocusedContainerColor = Color.White
                        ),
                        shape = RoundedCornerShape(10.dp)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val entity = PluginEntity(
                        id = initialPlugin?.id ?: "plugin_custom_${System.currentTimeMillis()}",
                        name = name.ifBlank { "Custom Scraper" },
                        description = desc,
                        version = version,
                        author = author,
                        repoUrl = initialPlugin?.repoUrl ?: "custom",
                        jsCode = jsCode,
                        isEnabled = initialPlugin?.isEnabled ?: true,
                        supportedTypes = types,
                        orderPriority = initialPlugin?.orderPriority ?: 99
                    )
                    onSave(entity)
                },
                colors = ButtonDefaults.buttonColors(containerColor = HdPrimary, contentColor = Color.White),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("Save Scraper", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = HdTextSecondary)
            }
        },
        containerColor = HdSurface
    )
}

@Composable
fun AddRepoDialog(
    onDismiss: () -> Unit,
    onAdd: (String, String, String) -> Unit
) {
    var name by remember { mutableStateOf("Yoruix Nuvio Providers") }
    var url by remember { mutableStateOf("https://raw.githubusercontent.com/yoruix/nuvio-providers/refs/heads/main/manifest.json") }
    var desc by remember { mutableStateOf("Official Nuvio providers repository by Yoruix with 4K/1080p stream resolvers.") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Add Provider Repository",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = HdTextPrimary)
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Quick Presets from Nuvio Library:",
                    style = MaterialTheme.typography.labelSmall.copy(color = HdTextSecondary, fontWeight = FontWeight.Bold)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            name = "Yoruix Providers"
                            url = "https://raw.githubusercontent.com/yoruix/nuvio-providers/refs/heads/main/manifest.json"
                            desc = "Nuvio providers repository by Yoruix with 4K/1080p resolvers"
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, HdBorder),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = HdPrimary)
                    ) {
                        Text("Yoruix", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }

                    OutlinedButton(
                        onClick = {
                            name = "All-in-One Nuvio"
                            url = "https://raw.githubusercontent.com/D3adlyRocket/All-in-One-Nuvio/refs/heads/main/manifest.json"
                            desc = "All-in-One community provider collection for movies, TV series, and anime"
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, HdBorder),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = HdPrimary)
                    ) {
                        Text("All-in-One", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Repository Name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = HdPrimary,
                        unfocusedBorderColor = HdBorder,
                        focusedTextColor = HdTextPrimary,
                        unfocusedTextColor = HdTextPrimary
                    ),
                    shape = RoundedCornerShape(10.dp)
                )

                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Repository Manifest URL / JSON") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = false,
                    maxLines = 3,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = HdPrimary,
                        unfocusedBorderColor = HdBorder,
                        focusedTextColor = HdTextPrimary,
                        unfocusedTextColor = HdTextPrimary
                    ),
                    shape = RoundedCornerShape(10.dp)
                )

                OutlinedTextField(
                    value = desc,
                    onValueChange = { desc = it },
                    label = { Text("Description") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = HdPrimary,
                        unfocusedBorderColor = HdBorder,
                        focusedTextColor = HdTextPrimary,
                        unfocusedTextColor = HdTextPrimary
                    ),
                    shape = RoundedCornerShape(10.dp)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onAdd(name, url, desc) },
                colors = ButtonDefaults.buttonColors(containerColor = HdPrimary, contentColor = Color.White),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("Add & Sync", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = HdTextSecondary)
            }
        },
        containerColor = HdSurface
    )
}
