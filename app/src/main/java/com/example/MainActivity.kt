package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.ListAlt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.ListAlt
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.MainViewModel
import com.example.ui.screens.DashboardScreen
import com.example.ui.screens.LogsScreen
import com.example.ui.screens.PluginsScreen
import com.example.ui.screens.SettingsScreen
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
import com.example.ui.theme.HdTextPrimary
import com.example.ui.theme.HdTextSecondary
import com.example.ui.theme.MyApplicationTheme

enum class NavTab(
    val title: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
) {
    DASHBOARD("Home", Icons.Filled.Home, Icons.Outlined.Home, "nav_dashboard"),
    PLUGINS("Plugins", Icons.Filled.Extension, Icons.Outlined.Extension, "nav_plugins"),
    LOGS("Logs", Icons.Filled.ListAlt, Icons.Outlined.ListAlt, "nav_logs"),
    SETTINGS("Settings", Icons.Filled.Settings, Icons.Outlined.Settings, "nav_settings")
}

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        setContent {
            MyApplicationTheme {
                MainAppScreen(viewModel = viewModel)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppScreen(viewModel: MainViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val plugins by viewModel.plugins.collectAsStateWithLifecycle()
    val repos by viewModel.repos.collectAsStateWithLifecycle()
    val libraryRepos by viewModel.libraryRepos.collectAsStateWithLifecycle()
    val isLibraryLoading by viewModel.isLibraryLoading.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val totalRequests by viewModel.totalRequests.collectAsStateWithLifecycle()
    val totalStreams by viewModel.totalStreams.collectAsStateWithLifecycle()

    var currentTab by remember { mutableIntStateOf(0) }
    val tabs = NavTab.values()

    val activePluginCount = plugins.count { it.isEnabled }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = HdBackground,
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(HdBackground)
                    .statusBarsPadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = HdPrimaryContainer,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Filled.Dns,
                                    contentDescription = "Server Icon",
                                    tint = HdPrimary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Column {
                            Text(
                                text = "Nuvio Bridge",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = HdTextPrimary,
                                    fontSize = 18.sp,
                                    letterSpacing = (-0.3).sp
                                )
                            )
                            Text(
                                text = if (uiState.isServerRunning) "Port :${uiState.serverPort} • Bridge Active" else "Bridge Offline",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = HdTextSecondary,
                                    fontSize = 11.sp
                                )
                            )
                        }
                    }

                    // High Density Status Pill Badge
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = if (uiState.isServerRunning) HdSuccessBg else HdErrorBg,
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (uiState.isServerRunning) HdSuccessText.copy(alpha = 0.3f) else HdErrorText.copy(alpha = 0.3f)
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(if (uiState.isServerRunning) HdSuccessText else HdErrorText)
                            )
                            Text(
                                text = if (uiState.isServerRunning) "ACTIVE" else "STOPPED",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = if (uiState.isServerRunning) HdSuccessText else HdErrorText,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 10.sp,
                                    letterSpacing = 0.5.sp
                                )
                            )
                        }
                    }
                }
                HorizontalDivider(color = HdBorder, thickness = 1.dp)
            }
        },
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(HdSurface)
                    .navigationBarsPadding()
            ) {
                HorizontalDivider(color = HdBorder, thickness = 1.dp)
                NavigationBar(
                    containerColor = HdSurface,
                    contentColor = HdPrimary,
                    tonalElevation = 0.dp,
                    modifier = Modifier.height(64.dp)
                ) {
                    tabs.forEachIndexed { index, tab ->
                        val selected = currentTab == index
                        NavigationBarItem(
                            selected = selected,
                            onClick = { currentTab = index },
                            icon = {
                                Icon(
                                    imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                    contentDescription = tab.title,
                                    modifier = Modifier.size(22.dp)
                                )
                            },
                            label = {
                                Text(
                                    text = tab.title,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                        fontSize = 11.sp
                                    )
                                )
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = HdOnPrimaryContainer,
                                selectedTextColor = HdOnPrimaryContainer,
                                indicatorColor = HdPrimaryContainer,
                                unselectedIconColor = HdTextSecondary,
                                unselectedTextColor = HdTextSecondary
                            ),
                            modifier = Modifier.testTag(tab.testTag)
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        AnimatedContent(
            targetState = currentTab,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "tab_transition",
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) { targetTab ->
            when (tabs[targetTab]) {
                NavTab.DASHBOARD -> {
                    DashboardScreen(
                        state = uiState,
                        activePluginCount = activePluginCount,
                        totalRequests = totalRequests,
                        totalStreams = totalStreams ?: 0,
                        onToggleServer = { viewModel.toggleServer() },
                        onRefreshNetwork = { viewModel.refreshNetworkInfo() },
                        onQueryChange = { viewModel.setTestQuery(it) },
                        onTypeChange = { viewModel.setTestType(it) },
                        onSeasonChange = { viewModel.setTestSeason(it) },
                        onEpisodeChange = { viewModel.setTestEpisode(it) },
                        onTestStream = { viewModel.testStream() }
                    )
                }
                NavTab.PLUGINS -> {
                    PluginsScreen(
                        plugins = plugins,
                        repos = repos,
                        libraryRepos = libraryRepos,
                        isLibraryLoading = isLibraryLoading,
                        isSyncing = uiState.isSyncing,
                        syncMessage = uiState.syncMessage,
                        onTogglePlugin = { id, enabled -> viewModel.togglePlugin(id, enabled) },
                        onSavePlugin = { viewModel.savePlugin(it) },
                        onDeletePlugin = { viewModel.deletePlugin(it) },
                        onRestoreDefaults = { viewModel.restoreDefaults() },
                        onAddRepo = { name, url, desc -> viewModel.addRepo(name, url, desc) },
                        onSyncRepo = { viewModel.syncRepo(it) },
                        onDeleteRepo = { viewModel.deleteRepo(it) },
                        onRefreshLibrary = { viewModel.refreshLibrary() },
                        onInstallLibraryRepo = { viewModel.installRepoFromLibrary(it) }
                    )
                }
                NavTab.LOGS -> {
                    LogsScreen(
                        logs = logs,
                        onClearLogs = { viewModel.clearLogs() }
                    )
                }
                NavTab.SETTINGS -> {
                    SettingsScreen(
                        state = uiState,
                        onPortChange = { viewModel.setPort(it) },
                        onUpdateSettings = { sort, group, filter, timeout ->
                            viewModel.updateSettings(sort, group, filter, timeout)
                        }
                    )
                }
            }
        }
    }
}

