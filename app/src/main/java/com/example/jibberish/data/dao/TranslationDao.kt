package com.example.jibberish.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.jibberish.data.entities.Translation

@Dao
interface TranslationDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTranslation(translation: Translation)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTranslations(translations: List<Translation>)

    @Query("SELECT * FROM translations WHERE session_id = :sessionId ORDER BY timestamp ASC")
    suspend fun getTranslationsForSessionSync(sessionId: String): List<Translation>

    @Query("SELECT * FROM translations WHERE session_id = :sessionId ORDER BY timestamp ASC")
    suspend fun getAllTranslationsForSummary(sessionId: String): List<Translation>
}
