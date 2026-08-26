package com.example.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.model.RepoEntity
import com.example.data.model.ServerLogEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RepoDao {
    @Query("SELECT * FROM repositories ORDER BY name ASC")
    fun getAllReposFlow(): Flow<List<RepoEntity>>

    @Query("SELECT * FROM repositories ORDER BY name ASC")
    suspend fun getAllRepos(): List<RepoEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRepo(repo: RepoEntity)

    @Update
    suspend fun updateRepo(repo: RepoEntity)

    @Delete
    suspend fun deleteRepo(repo: RepoEntity)

    @Query("DELETE FROM repositories WHERE id = :id")
    suspend fun deleteRepoById(id: String)
}

@Dao
interface ServerLogDao {
    @Query("SELECT * FROM server_logs ORDER BY timestamp DESC LIMIT 100")
    fun getRecentLogsFlow(): Flow<List<ServerLogEntity>>

    @Insert
    suspend fun insertLog(log: ServerLogEntity)

    @Query("DELETE FROM server_logs WHERE id NOT IN (SELECT id FROM server_logs ORDER BY timestamp DESC LIMIT 300)")
    suspend fun pruneOldLogs()

    @Query("DELETE FROM server_logs")
    suspend fun clearAllLogs()

    @Query("SELECT COUNT(*) FROM server_logs")
    fun getTotalRequestCountFlow(): Flow<Int>

    @Query("SELECT SUM(streamsFound) FROM server_logs")
    fun getTotalStreamsServedFlow(): Flow<Int?>
}
