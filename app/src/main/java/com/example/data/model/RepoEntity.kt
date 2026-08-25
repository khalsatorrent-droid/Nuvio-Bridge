package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "repositories")
data class RepoEntity(
    @PrimaryKey val id: String,
    val name: String,
    val url: String,
    val description: String,
    val lastSynced: Long = System.currentTimeMillis(),
    val pluginCount: Int = 0
)

@Entity(tableName = "server_logs")
data class ServerLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val method: String,
    val path: String,
    val status: Int,
    val durationMs: Long,
    val streamsFound: Int = 0,
    val clientIp: String = "127.0.0.1"
)
