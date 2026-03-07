package com.example.jibberish.managers

import android.content.Context
import com.example.jibberish.data.JibberishDatabase
import com.example.jibberish.data.entities.Session
import com.example.jibberish.data.entities.SessionWithTranslations
import com.example.jibberish.data.entities.Translation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * SessionManager handles the session-based architecture for batch translation processing.
 *
 * Workflow:
 * 1. User toggles session ON -> startSession()
 * 2. Audio chunks are transcribed and analyzed -> addTranslation() stores chunk
 * 3. User toggles session OFF -> endSession() generates summary
 */
class SessionManager(
    context: Context,
    private val modelManager: ModelManager
) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val database = JibberishDatabase.getDatabase(context)
    private val sessionDao = database.sessionDao()
    private val translationDao = database.translationDao()

    private val _currentSession = MutableStateFlow<Session?>(null)

    private val _isSessionActive = MutableStateFlow(false)
    val isSessionActive: StateFlow<Boolean> = _isSessionActive.asStateFlow()

    private val _currentTranslations = MutableStateFlow<List<Translation>>(emptyList())
    val currentTranslations: StateFlow<List<Translation>> = _currentTranslations.asStateFlow()

    private val _sessionStatus = MutableStateFlow<SessionStatus>(SessionStatus.Idle)
    val sessionStatus: StateFlow<SessionStatus> = _sessionStatus.asStateFlow()

    // System prompt for summary generation
    private val summaryPrompt = """
You are a conversation summarizer. Given a series of conversation snippets with their jargon analysis,
create a concise summary that captures:
1. The main topics discussed
2. Key jargon terms that were used and their meanings
3. Overall context of the conversation

Return your response as a plain text summary (2-4 sentences). Be concise and informative.
""".trimIndent()

    init {
        scope.launch {
            // Check for any active session from previous app launch
            val activeSession = sessionDao.getActiveSession()
            if (activeSession != null) {
                _currentSession.value = activeSession
                _isSessionActive.value = true
                loadCurrentSessionTranslations(activeSession.sessionId)
            }
        }
    }

    /**
     * Starts a new translation session.
     */
    suspend fun startSession(): Session {
        _sessionStatus.value = SessionStatus.Starting

        // End any existing active session first
        _currentSession.value?.let { existingSession ->
            if (existingSession.isActive) {
                endSession()
            }
        }

        val newSession = Session()
        sessionDao.insertSession(newSession)

        _currentSession.value = newSession
        _isSessionActive.value = true
        _currentTranslations.value = emptyList()
        _sessionStatus.value = SessionStatus.Active

        return newSession
    }

    /**
     * Adds a translation chunk to the current session.
     */
    suspend fun addTranslation(
        originalText: String,
        jsonOutput: String,
        containsJargon: Boolean,
        jargonTerms: Map<String, String>,
        simplifiedMeaning: String?
    ): Translation? {
        val session = _currentSession.value ?: return null

        val translation = Translation(
            sessionId = session.sessionId,
            originalText = originalText,
            jsonOutput = jsonOutput,
            containsJargon = containsJargon,
            jargonTerms = if (jargonTerms.isNotEmpty()) JSONObject(jargonTerms).toString() else null,
            simplifiedMeaning = simplifiedMeaning
        )

        translationDao.insertTranslation(translation)
        sessionDao.incrementTranslationCount(session.sessionId)

        // Update local state
        _currentTranslations.value = _currentTranslations.value + translation
        _currentSession.value = sessionDao.getSessionById(session.sessionId)

        return translation
    }

    /**
     * Ends the current session, generates a summary, and saves it.
     */
    suspend fun endSession(): Session? {
        val session = _currentSession.value ?: return null

        _sessionStatus.value = SessionStatus.GeneratingSummary

        // End the session in database
        val endTime = System.currentTimeMillis()
        sessionDao.endSession(session.sessionId, endTime)

        // Get all translations for this session
        val translations = translationDao.getAllTranslationsForSummary(session.sessionId)

        // Generate summary if there are translations
        val summary = if (translations.isNotEmpty()) {
            generateSummary(translations)
        } else {
            "No translations recorded in this session."
        }

        // Update session with summary
        sessionDao.updateSessionSummary(session.sessionId, summary)

        // Clear current session state
        val completedSession = sessionDao.getSessionById(session.sessionId)
        _currentSession.value = null
        _isSessionActive.value = false
        _currentTranslations.value = emptyList()
        _sessionStatus.value = SessionStatus.Idle

        return completedSession
    }

    /**
     * Generates a holistic summary using the active model (AICore or MediaPipe).
     */
    private suspend fun generateSummary(translations: List<Translation>): String {
        return try {
            val conversationContent = translations.mapIndexed { index, translation ->
                buildString {
                    append("Snippet ${index + 1}:\n")
                    append("Original: \"${translation.originalText}\"\n")
                    if (translation.containsJargon) {
                        append("Jargon found: ${translation.jargonTerms}\n")
                        append("Simplified: ${translation.simplifiedMeaning}\n")
                    }
                }
            }.joinToString("\n---\n")

            val fullPrompt = "$summaryPrompt\n\nConversation snippets:\n$conversationContent"

            modelManager.generateText(fullPrompt)

        } catch (e: Exception) {
            "Summary generation failed: ${e.localizedMessage ?: e.message}"
        }
    }

    /**
     * Gets all completed sessions for history display.
     */
    fun getCompletedSessions(): Flow<List<Session>> {
        return sessionDao.getCompletedSessions()
    }

    /**
     * Gets a session with all its translations for detail view.
     */
    suspend fun getSessionWithTranslations(sessionId: String): SessionWithTranslations? {
        return sessionDao.getSessionWithTranslations(sessionId)
    }

    /**
     * Deletes a specific session.
     */
    suspend fun deleteSession(session: Session) {
        sessionDao.deleteSession(session)
    }

    fun close() {
        scope.cancel()
    }

    private suspend fun loadCurrentSessionTranslations(sessionId: String) {
        val translations = translationDao.getTranslationsForSessionSync(sessionId)
        _currentTranslations.value = translations
    }

    sealed interface SessionStatus {
        data object Idle : SessionStatus
        data object Starting : SessionStatus
        data object Active : SessionStatus
        data object GeneratingSummary : SessionStatus
    }
}
