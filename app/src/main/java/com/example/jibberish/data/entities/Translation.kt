package com.example.jibberish.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Translations Table: Stores individual translation chunks.
 * 
 * Each Translation represents a single "Transcribe" button press result,
 * containing the Gemini Nano JSON output linked to a parent Session.
 */
@Entity(
    tableName = "translations",
    foreignKeys = [
        ForeignKey(
            entity = Session::class,
            parentColumns = ["session_id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["session_id"])]
)
data class Translation(
    @PrimaryKey
    @ColumnInfo(name = "translation_id")
    val translationId: String = UUID.randomUUID().toString(),
    
    @ColumnInfo(name = "session_id")
    val sessionId: String,
    
    @ColumnInfo(name = "timestamp")
    val timestamp: Long = System.currentTimeMillis(),
    
    @ColumnInfo(name = "original_text")
    val originalText: String,
    
    @ColumnInfo(name = "json_output")
    val jsonOutput: String,
    
    @ColumnInfo(name = "contains_jargon")
    val containsJargon: Boolean = false,
    
    @ColumnInfo(name = "jargon_terms")
    val jargonTerms: String? = null, // Comma-separated list
    
    @ColumnInfo(name = "simplified_meaning")
    val simplifiedMeaning: String? = null
)
