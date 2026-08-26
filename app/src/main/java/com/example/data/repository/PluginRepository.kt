package com.example.data.repository

import com.example.data.AppDatabase
import com.example.data.DefaultPlugins
import com.example.data.model.PluginEntity
import com.example.data.model.RepoEntity
import com.example.data.model.ServerLogEntity
import com.example.engine.NuvioRepoFetcher
import kotlinx.coroutines.flow.Flow

class PluginRepository(private val db: AppDatabase) {

    private val pluginDao = db.pluginDao()
    private val repoDao = db.repoDao()
    private val serverLogDao = db.serverLogDao()
    private val repoFetcher = NuvioRepoFetcher()

    val allPluginsFlow: Flow<List<PluginEntity>> = pluginDao.getAllPluginsFlow()
    val allReposFlow: Flow<List<RepoEntity>> = repoDao.getAllReposFlow()
    val recentLogsFlow: Flow<List<ServerLogEntity>> = serverLogDao.getRecentLogsFlow()
    val totalRequestsFlow: Flow<Int> = serverLogDao.getTotalRequestCountFlow()
    val totalStreamsServedFlow: Flow<Int?> = serverLogDao.getTotalStreamsServedFlow()

    suspend fun getEnabledPlugins(): List<PluginEntity> {
        return pluginDao.getEnabledPlugins()
    }

    suspend fun initializeDefaultsIfNeeded() {
        val existing = pluginDao.getAllPlugins()
        if (existing.isEmpty() || existing.any { it.version < "2.5.0" }) {
            pluginDao.insertPlugins(DefaultPlugins.getDefaultPlugins())
        }
        for (repo in DefaultPlugins.DEFAULT_REPOS) {
            repoDao.insertRepo(repo)
        }
    }

    suspend fun restoreDefaultPlugins() {
        pluginDao.insertPlugins(DefaultPlugins.getDefaultPlugins())
        for (repo in DefaultPlugins.DEFAULT_REPOS) {
            repoDao.insertRepo(repo)
        }
    }

    suspend fun setPluginEnabled(id: String, isEnabled: Boolean) {
        pluginDao.setPluginEnabled(id, isEnabled)
    }

    suspend fun insertPlugin(plugin: PluginEntity) {
        pluginDao.insertPlugin(plugin)
    }

    suspend fun deletePlugin(plugin: PluginEntity) {
        pluginDao.deletePlugin(plugin)
    }

    suspend fun deletePluginById(id: String) {
        pluginDao.deletePluginById(id)
    }

    suspend fun addRepo(repo: RepoEntity) {
        repoDao.insertRepo(repo)
    }

    suspend fun deleteRepo(repo: RepoEntity) {
        repoDao.deleteRepo(repo)
    }

    suspend fun deleteRepoById(id: String) {
        repoDao.deleteRepoById(id)
    }

    suspend fun syncRepo(repo: RepoEntity): Int {
        val fetchedPlugins = repoFetcher.fetchRepoPlugins(repo)
        if (fetchedPlugins.isNotEmpty()) {
            pluginDao.insertPlugins(fetchedPlugins)
            val updated = repo.copy(
                lastSynced = System.currentTimeMillis(),
                pluginCount = fetchedPlugins.size
            )
            repoDao.updateRepo(updated)
        }
        return fetchedPlugins.size
    }

    suspend fun logServerRequest(log: ServerLogEntity) {
        serverLogDao.insertLog(log)
        try {
            serverLogDao.pruneOldLogs()
        } catch (_: Exception) {}
    }

    suspend fun clearLogs() {
        serverLogDao.clearAllLogs()
    }
}
