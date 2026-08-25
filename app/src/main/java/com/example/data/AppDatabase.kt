package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.data.dao.PluginDao
import com.example.data.dao.RepoDao
import com.example.data.dao.ServerLogDao
import com.example.data.model.PluginEntity
import com.example.data.model.RepoEntity
import com.example.data.model.ServerLogEntity

@Database(
    entities = [
        PluginEntity::class,
        RepoEntity::class,
        ServerLogEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun pluginDao(): PluginDao
    abstract fun repoDao(): RepoDao
    abstract fun serverLogDao(): ServerLogDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "nuvio_server_db"
                ).fallbackToDestructiveMigration().build()
                INSTANCE = instance
                instance
            }
        }
    }
}
