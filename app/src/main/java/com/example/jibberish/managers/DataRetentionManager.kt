package com.example.jibberish.managers

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.jibberish.data.JibberishDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "jibberish_settings")

/**
 * DataRetentionManager handles automatic cleanup of old session data
 * based on user preferences.
 * 
 * Retention Options:
 * - 1 day
 * - 7 days (default)
 * - 30 days
 * - 90 days
 * - Forever (no auto-delete)
 */
class DataRetentionManager(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val database = JibberishDatabase.getDatabase(context)
    private val sessionDao = database.sessionDao()
    
    companion object {
        private val RETENTION_DAYS_KEY = intPreferencesKey("retention_days")
        private val LAST_CLEANUP_KEY = longPreferencesKey("last_cleanup_timestamp")
        
        // Retention period options in days
        const val RETENTION_1_DAY = 1
        const val RETENTION_7_DAYS = 7
        const val RETENTION_30_DAYS = 30
        const val RETENTION_90_DAYS = 90
        const val RETENTION_FOREVER = -1 // Special value for "never delete"
        
        val RETENTION_OPTIONS = listOf(
            RETENTION_1_DAY to "1 Day",
            RETENTION_7_DAYS to "7 Days",
            RETENTION_30_DAYS to "30 Days",
            RETENTION_90_DAYS to "90 Days",
            RETENTION_FOREVER to "Forever"
        )
        
        const val DEFAULT_RETENTION_DAYS = RETENTION_7_DAYS
    }
    
    init {
        // Run cleanup on initialization
        scope.launch {
            performCleanupIfNeeded()
        }
    }
    
    /**
     * Gets the current retention period setting.
     */
    fun getRetentionDays(): Flow<Int> {
        return context.dataStore.data.map { preferences ->
            preferences[RETENTION_DAYS_KEY] ?: DEFAULT_RETENTION_DAYS
        }
    }
    
    /**
     * Sets the retention period.
     */
    suspend fun setRetentionDays(days: Int) {
        context.dataStore.edit { preferences ->
            preferences[RETENTION_DAYS_KEY] = days
        }
        // Perform cleanup immediately when setting changes
        performCleanup()
    }
    
    /**
     * Gets human-readable retention period label.
     */
    fun getRetentionLabel(days: Int): String {
        return RETENTION_OPTIONS.find { it.first == days }?.second ?: "Unknown"
    }
    
    /**
     * Performs cleanup of old sessions based on retention settings.
     * Returns the number of sessions deleted.
     */
    suspend fun performCleanup(): Int {
        val retentionDays = context.dataStore.data.first()[RETENTION_DAYS_KEY] ?: DEFAULT_RETENTION_DAYS
        
        // If retention is set to "forever", don't delete anything
        if (retentionDays == RETENTION_FOREVER) {
            return 0
        }
        
        val cutoffTimestamp = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(retentionDays.toLong())
        val deletedCount = sessionDao.deleteSessionsOlderThan(cutoffTimestamp)
        
        // Update last cleanup timestamp
        context.dataStore.edit { preferences ->
            preferences[LAST_CLEANUP_KEY] = System.currentTimeMillis()
        }
        
        return deletedCount
    }
    
    /**
     * Performs cleanup only if enough time has passed since last cleanup.
     * Called on app startup to avoid running cleanup too frequently.
     */
    private suspend fun performCleanupIfNeeded() {
        val lastCleanup = context.dataStore.data.first()[LAST_CLEANUP_KEY] ?: 0L
        val hoursSinceLastCleanup = TimeUnit.MILLISECONDS.toHours(System.currentTimeMillis() - lastCleanup)
        
        // Only run cleanup if at least 6 hours have passed
        if (hoursSinceLastCleanup >= 6) {
            performCleanup()
        }
    }
    
    /**
     * Manually clears all session data (for "Clear All Data" button).
     * Returns the number of sessions deleted.
     */
    suspend fun clearAllData(): Int {
        return sessionDao.deleteAllCompletedSessions()
    }
    
    fun getCompletedSessionCountFlow(): Flow<Int> {
        return sessionDao.getCompletedSessionCountFlow()
    }

    fun close() {
        scope.cancel()
    }
}
