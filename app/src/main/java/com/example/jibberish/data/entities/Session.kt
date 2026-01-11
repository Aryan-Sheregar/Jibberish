package com.example.jibberish.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Sessions Table: Stores high-level session data.
 * 
 * A Session represents a complete conversation/translation capture period
 * from when the user toggles "On" to when they toggle "Off".
 */
@Entity(tableName = "sessions")
data class Session(
    @PrimaryKey
    @ColumnInfo(name = "session_id")
    val sessionId: String = UUID.randomUUID().toString(),
    
    @ColumnInfo(name = "start_timestamp")
    val startTimestamp: Long = System.currentTimeMillis(),
    
    @ColumnInfo(name = "end_timestamp")
    val endTimestamp: Long? = null,
    
    @ColumnInfo(name = "generated_summary")
    val generatedSummary: String? = null,
    
    @ColumnInfo(name = "is_active")
    val isActive: Boolean = true,
    
    @ColumnInfo(name = "translation_count")
    val translationCount: Int = 0
)
