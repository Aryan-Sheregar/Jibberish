package com.example.jibberish.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.example.jibberish.data.entities.Session
import com.example.jibberish.data.entities.SessionWithTranslations
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: Session)
    
    @Update
    suspend fun updateSession(session: Session)
    
    @Delete
    suspend fun deleteSession(session: Session)
    
    @Query("SELECT * FROM sessions WHERE session_id = :sessionId")
    suspend fun getSessionById(sessionId: String): Session?
    
    @Query("SELECT * FROM sessions WHERE is_active = 1 LIMIT 1")
    suspend fun getActiveSession(): Session?
    
    @Query("SELECT * FROM sessions WHERE is_active = 0 ORDER BY start_timestamp DESC")
    fun getCompletedSessions(): Flow<List<Session>>
    
    @Transaction
    @Query("SELECT * FROM sessions WHERE session_id = :sessionId")
    suspend fun getSessionWithTranslations(sessionId: String): SessionWithTranslations?
    
    // End a session: set end timestamp and mark as inactive
    @Query("UPDATE sessions SET end_timestamp = :endTime, is_active = 0 WHERE session_id = :sessionId")
    suspend fun endSession(sessionId: String, endTime: Long = System.currentTimeMillis())
    
    // Update session summary after AI generates it
    @Query("UPDATE sessions SET generated_summary = :summary WHERE session_id = :sessionId")
    suspend fun updateSessionSummary(sessionId: String, summary: String)
    
    // Update translation count
    @Query("UPDATE sessions SET translation_count = translation_count + 1 WHERE session_id = :sessionId")
    suspend fun incrementTranslationCount(sessionId: String)
    
    // Data retention: delete sessions older than specified timestamp
    @Query("DELETE FROM sessions WHERE end_timestamp IS NOT NULL AND end_timestamp < :beforeTimestamp")
    suspend fun deleteSessionsOlderThan(beforeTimestamp: Long): Int
    
    // Get session count as observable Flow
    @Query("SELECT COUNT(*) FROM sessions WHERE is_active = 0")
    fun getCompletedSessionCountFlow(): Flow<Int>
    
    // Delete all completed sessions
    @Query("DELETE FROM sessions WHERE is_active = 0")
    suspend fun deleteAllCompletedSessions(): Int
}
