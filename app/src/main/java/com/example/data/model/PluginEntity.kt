package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "plugins")
data class PluginEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val version: String,
    val author: String,
    val repoUrl: String,
    val jsCode: String,
    val isEnabled: Boolean = true,
    val supportedTypes: String = "movie,series,anime",
    val orderPriority: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)
