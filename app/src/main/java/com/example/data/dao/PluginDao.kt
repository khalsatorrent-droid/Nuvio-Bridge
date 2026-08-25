package com.example.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.model.PluginEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PluginDao {
    @Query("SELECT * FROM plugins ORDER BY orderPriority ASC, name ASC")
    fun getAllPluginsFlow(): Flow<List<PluginEntity>>

    @Query("SELECT * FROM plugins ORDER BY orderPriority ASC, name ASC")
    suspend fun getAllPlugins(): List<PluginEntity>

    @Query("SELECT * FROM plugins WHERE isEnabled = 1 ORDER BY orderPriority ASC, name ASC")
    suspend fun getEnabledPlugins(): List<PluginEntity>

    @Query("SELECT * FROM plugins WHERE id = :id")
    suspend fun getPluginById(id: String): PluginEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlugin(plugin: PluginEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlugins(plugins: List<PluginEntity>)

    @Update
    suspend fun updatePlugin(plugin: PluginEntity)

    @Query("UPDATE plugins SET isEnabled = :isEnabled WHERE id = :id")
    suspend fun setPluginEnabled(id: String, isEnabled: Boolean)

    @Delete
    suspend fun deletePlugin(plugin: PluginEntity)

    @Query("DELETE FROM plugins WHERE id = :id")
    suspend fun deletePluginById(id: String)

    @Query("DELETE FROM plugins")
    suspend fun deleteAllPlugins()
}
