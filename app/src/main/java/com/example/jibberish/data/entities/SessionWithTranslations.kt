package com.example.jibberish.data.entities

import androidx.room.Embedded
import androidx.room.Relation

/**
 * One-to-many relationship: Session with all its Translations.
 * Used for drill-down view when user clicks on a session in History.
 */
data class SessionWithTranslations(
    @Embedded
    val session: Session,
    
    @Relation(
        parentColumn = "session_id",
        entityColumn = "session_id"
    )
    val translations: List<Translation>
)
