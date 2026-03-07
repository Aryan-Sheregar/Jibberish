package com.example.jibberish.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.jibberish.data.dao.SessionDao
import com.example.jibberish.data.dao.TranslationDao
import com.example.jibberish.data.entities.Session
import com.example.jibberish.data.entities.Translation

@Database(
    entities = [Session::class, Translation::class],
    version = 1,
    exportSchema = true
)
abstract class JibberishDatabase : RoomDatabase() {
    
    abstract fun sessionDao(): SessionDao
    abstract fun translationDao(): TranslationDao
    
    companion object {
        @Volatile
        private var INSTANCE: JibberishDatabase? = null
        
        fun getDatabase(context: Context): JibberishDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    JibberishDatabase::class.java,
                    "jibberish_database"
                )
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
