package com.example.jibberish.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.jibberish.data.entities.Translation
import kotlinx.coroutines.flow.Flow

@Dao
interface TranslationDao {
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTranslation(translation: Translation)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTranslations(translations: List<Translation>)
    
    @Delete
    suspend fun deleteTranslation(translation: Translation)
    
    @Query("SELECT * FROM translations WHERE translation_id = :translationId")
    suspend fun getTranslationById(translationId: String): Translation?
    
    @Query("SELECT * FROM translations WHERE session_id = :sessionId ORDER BY timestamp ASC")
    fun getTranslationsForSession(sessionId: String): Flow<List<Translation>>
    
    @Query("SELECT * FROM translations WHERE session_id = :sessionId ORDER BY timestamp ASC")
    suspend fun getTranslationsForSessionSync(sessionId: String): List<Translation>
    
    @Query("SELECT COUNT(*) FROM translations WHERE session_id = :sessionId")
    suspend fun getTranslationCountForSession(sessionId: String): Int
    
    // Get all translations for merging into summary (ordered by timestamp)
    @Query("SELECT * FROM translations WHERE session_id = :sessionId ORDER BY timestamp ASC")
    suspend fun getAllTranslationsForSummary(sessionId: String): List<Translation>
    
    // Get all jargon-containing translations for a session
    @Query("SELECT * FROM translations WHERE session_id = :sessionId AND contains_jargon = 1 ORDER BY timestamp ASC")
    suspend fun getJargonTranslationsForSession(sessionId: String): List<Translation>
    
    // Delete translations older than specified timestamp (cascades from session delete, but useful for direct cleanup)
    @Query("DELETE FROM translations WHERE timestamp < :beforeTimestamp")
    suspend fun deleteTranslationsOlderThan(beforeTimestamp: Long): Int
    
    // Get total translation count
    @Query("SELECT COUNT(*) FROM translations")
    suspend fun getTotalTranslationCount(): Int
}
