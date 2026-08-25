package com.example.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.NuvioApp
import com.example.data.model.PluginEntity
import com.example.data.model.RepoEntity
import com.example.data.model.ServerLogEntity
import com.example.data.model.StremioStreamItem
import com.example.engine.IdResolver
import com.example.engine.StreamFormatter
import com.example.server.ServerService
import com.example.util.NetworkUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class MainUiState(
    val isServerRunning: Boolean = false,
    val serverPort: Int = 8585,
    val localIp: String = "127.0.0.1",
    val wifiConnected: Boolean = false,
    val testQuery: String = "tt0111161", // Default: Shawshank Redemption (or TMDB 278)
    val testType: String = "movie",
    val testSeason: String = "1",
    val testEpisode: String = "1",
    val testStreams: List<StremioStreamItem> = emptyList(),
    val isTesting: Boolean = false,
    val testError: String? = null,
    val isSyncing: Boolean = false,
    val syncMessage: String? = null,
    val sortByQuality: Boolean = true,
    val groupByQuality: Boolean = true,
    val filterOutLowQuality: Boolean = false,
    val requestTimeoutSec: Int = 12
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as NuvioApp
    private val repository = app.repository
    private val pluginRunner = app.pluginRunner
    private val libraryFetcher = com.example.engine.NuvioLibraryFetcher()

    private val _libraryRepos = MutableStateFlow<List<com.example.data.model.LibraryRepoItem>>(
        com.example.engine.NuvioLibraryFetcher.CURATED_LIBRARY_REPOS
    )
    val libraryRepos: StateFlow<List<com.example.data.model.LibraryRepoItem>> = _libraryRepos.asStateFlow()

    private val _isLibraryLoading = MutableStateFlow(false)
    val isLibraryLoading: StateFlow<Boolean> = _isLibraryLoading.asStateFlow()

    private val _uiState = MutableStateFlow(
        MainUiState(
            localIp = NetworkUtils.getLocalIpAddress(application),
            wifiConnected = NetworkUtils.isWifiConnected(application)
        )
    )
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    val plugins: StateFlow<List<PluginEntity>> = repository.allPluginsFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val repos: StateFlow<List<RepoEntity>> = repository.allReposFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val logs: StateFlow<List<ServerLogEntity>> = repository.recentLogsFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val totalRequests: StateFlow<Int> = repository.totalRequestsFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = 0
    )

    val totalStreams: StateFlow<Int?> = repository.totalStreamsServedFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = 0
    )

    init {
        viewModelScope.launch {
            ServerService.isServerRunningFlow.collect { running ->
                _uiState.value = _uiState.value.copy(
                    isServerRunning = running,
                    localIp = NetworkUtils.getLocalIpAddress(getApplication()),
                    wifiConnected = NetworkUtils.isWifiConnected(getApplication())
                )
            }
        }
        viewModelScope.launch {
            ServerService.serverPortFlow.collect { port ->
                _uiState.value = _uiState.value.copy(serverPort = port)
            }
        }
    }

    fun refreshNetworkInfo() {
        _uiState.value = _uiState.value.copy(
            localIp = NetworkUtils.getLocalIpAddress(getApplication()),
            wifiConnected = NetworkUtils.isWifiConnected(getApplication())
        )
    }

    fun toggleServer() {
        val currentRunning = _uiState.value.isServerRunning
        val context = getApplication<Application>()
        val intent = Intent(context, ServerService::class.java).apply {
            action = if (currentRunning) ServerService.ACTION_STOP else ServerService.ACTION_START
            putExtra(ServerService.EXTRA_PORT, _uiState.value.serverPort)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !currentRunning) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun setPort(port: Int) {
        if (port in 1024..65535) {
            _uiState.value = _uiState.value.copy(serverPort = port)
            if (_uiState.value.isServerRunning) {
                toggleServer()
                viewModelScope.launch {
                    kotlinx.coroutines.delay(500)
                    toggleServer()
                }
            }
        }
    }

    fun togglePlugin(id: String, isEnabled: Boolean) {
        viewModelScope.launch {
            repository.setPluginEnabled(id, isEnabled)
        }
    }

    fun savePlugin(plugin: PluginEntity) {
        viewModelScope.launch {
            repository.insertPlugin(plugin)
            Toast.makeText(getApplication(), "Plugin '${plugin.name}' saved!", Toast.LENGTH_SHORT).show()
        }
    }

    fun deletePlugin(id: String) {
        viewModelScope.launch {
            repository.deletePluginById(id)
        }
    }

    fun restoreDefaults() {
        viewModelScope.launch {
            repository.restoreDefaultPlugins()
            Toast.makeText(getApplication(), "Default Nuvio scrapers restored!", Toast.LENGTH_SHORT).show()
        }
    }

    fun refreshLibrary() {
        viewModelScope.launch {
            _isLibraryLoading.value = true
            try {
                val fetched = libraryFetcher.fetchActiveLibraryRepos()
                _libraryRepos.value = fetched
                Toast.makeText(getApplication(), "Loaded ${fetched.size} repos from Nuvio Library", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(getApplication(), "Failed to refresh library: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                _isLibraryLoading.value = false
            }
        }
    }

    fun installRepoFromLibrary(item: com.example.data.model.LibraryRepoItem) {
        viewModelScope.launch {
            val existing = repos.value.find { it.url.equals(item.manifestUrl, ignoreCase = true) }
            val repoToSync = if (existing != null) {
                existing
            } else {
                val newRepo = RepoEntity(
                    id = "repo_${System.currentTimeMillis()}",
                    name = item.name,
                    url = item.manifestUrl,
                    description = item.description
                )
                repository.addRepo(newRepo)
                newRepo
            }
            syncRepo(repoToSync)
        }
    }

    fun addRepo(name: String, url: String, description: String) {
        viewModelScope.launch {
            val id = "repo_${System.currentTimeMillis()}"
            val repo = RepoEntity(
                id = id,
                name = name.ifBlank { "Custom Repo" },
                url = url.trim(),
                description = description.ifBlank { "Nuvio Scraper Repository" }
            )
            repository.addRepo(repo)
            syncRepo(repo)
        }
    }

    fun syncRepo(repo: RepoEntity) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSyncing = true, syncMessage = "Syncing ${repo.name}...")
            val count = repository.syncRepo(repo)
            _uiState.value = _uiState.value.copy(
                isSyncing = false,
                syncMessage = if (count > 0) "Installed $count plugins from ${repo.name}" else "No plugins found in ${repo.name}"
            )
            Toast.makeText(getApplication(), "Synced ${repo.name}: $count plugins", Toast.LENGTH_SHORT).show()
        }
    }

    fun deleteRepo(id: String) {
        viewModelScope.launch {
            repository.deleteRepoById(id)
        }
    }

    fun setTestQuery(query: String) {
        _uiState.value = _uiState.value.copy(testQuery = query)
    }

    fun setTestType(type: String) {
        _uiState.value = _uiState.value.copy(testType = type)
    }

    fun setTestSeason(season: String) {
        _uiState.value = _uiState.value.copy(testSeason = season)
    }

    fun setTestEpisode(episode: String) {
        _uiState.value = _uiState.value.copy(testEpisode = episode)
    }

    fun testStream() {
        val query = _uiState.value.testQuery.trim()
        if (query.isEmpty()) return

        val type = _uiState.value.testType
        val season = _uiState.value.testSeason.toIntOrNull()
        val episode = _uiState.value.testEpisode.toIntOrNull()

        _uiState.value = _uiState.value.copy(
            isTesting = true,
            testError = null,
            testStreams = emptyList()
        )

        viewModelScope.launch {
            try {
                val enabledPlugins = repository.getEnabledPlugins()
                if (enabledPlugins.isEmpty()) {
                    _uiState.value = _uiState.value.copy(
                        isTesting = false,
                        testError = "No plugins enabled. Please enable at least one Nuvio plugin."
                    )
                    return@launch
                }

                // Resolve IDs (TMDB / IMDB)
                val resolved = IdResolver.resolve(
                    rawId = query,
                    type = type,
                    existingImdbId = if (query.startsWith("tt")) query else null,
                    existingTmdbId = if (!query.startsWith("tt") && query.all { it.isDigit() }) query else null
                )

                val rawList = mutableListOf<com.example.data.model.RawPluginStream>()
                for (plugin in enabledPlugins) {
                    val res = pluginRunner.runPlugin(
                        plugin = plugin,
                        type = type,
                        id = query,
                        season = season,
                        episode = episode,
                        tmdbId = resolved.tmdbId,
                        imdbId = resolved.imdbId,
                        kitsuId = if (query.startsWith("kitsu")) query.removePrefix("kitsu:") else null,
                        timeoutMs = 10000
                    )
                    rawList.addAll(res)
                }

                val formatted = StreamFormatter.formatAndSortStreams(
                    rawStreams = rawList,
                    sortByQuality = _uiState.value.sortByQuality,
                    groupByQuality = _uiState.value.groupByQuality,
                    filterOutLowQuality = _uiState.value.filterOutLowQuality
                )

                _uiState.value = _uiState.value.copy(
                    isTesting = false,
                    testStreams = formatted,
                    testError = if (formatted.isEmpty()) "No verified streams returned by active plugins for '$query'" else null
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isTesting = false,
                    testError = "Test failed: ${e.message}"
                )
            }
        }
    }

    fun clearLogs() {
        viewModelScope.launch {
            repository.clearLogs()
        }
    }

    fun updateSettings(
        sortByQuality: Boolean,
        groupByQuality: Boolean,
        filterOutLowQuality: Boolean,
        timeoutSec: Int
    ) {
        _uiState.value = _uiState.value.copy(
            sortByQuality = sortByQuality,
            groupByQuality = groupByQuality,
            filterOutLowQuality = filterOutLowQuality,
            requestTimeoutSec = timeoutSec
        )
    }

    fun getManifestUrl(): String {
        val ip = _uiState.value.localIp
        val port = _uiState.value.serverPort
        return "http://$ip:$port/manifest.json"
    }

    fun getLocalManifestUrl(): String {
        val port = _uiState.value.serverPort
        return "http://127.0.0.1:$port/manifest.json"
    }

    fun getStremioDeeplink(): String {
        val ip = _uiState.value.localIp
        val port = _uiState.value.serverPort
        return "stremio://$ip:$port/manifest.json"
    }
}
