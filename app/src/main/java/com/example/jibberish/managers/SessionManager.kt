package com.example.jibberish.managers

import android.content.Context
import com.example.jibberish.data.JibberishDatabase
import com.example.jibberish.data.entities.Session
import com.example.jibberish.data.entities.SessionWithTranslations
import com.example.jibberish.data.entities.Translation
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
 * 1. User toggles session ON → startSession()
 * 2. User presses "Transcribe" button → addTranslation() stores chunk
 * 3. User toggles session OFF → endSession() generates summary
 */
class SessionManager(private val context: Context) {
    
    private val database = JibberishDatabase.getDatabase(context)
    private val sessionDao = database.sessionDao()
    private val translationDao = database.translationDao()
    
    private val generativeModel: GenerativeModel = Generation.getClient()
    
    private val _currentSession = MutableStateFlow<Session?>(null)
    val currentSession: StateFlow<Session?> = _currentSession.asStateFlow()
    
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
        CoroutineScope(Dispatchers.IO).launch {
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
     * Called when user toggles session ON.
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
     * Called when user presses "Transcribe" button.
     */
    suspend fun addTranslation(
        originalText: String,
        jsonOutput: String,
        containsJargon: Boolean,
        jargonTerms: List<String>,
        simplifiedMeaning: String?
    ): Translation? {
        val session = _currentSession.value ?: return null
        
        val translation = Translation(
            sessionId = session.sessionId,
            originalText = originalText,
            jsonOutput = jsonOutput,
            containsJargon = containsJargon,
            jargonTerms = jargonTerms.joinToString(","),
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
     * Called when user toggles session OFF.
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
     * Generates a holistic summary using Gemini Nano.
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
            
            val response = generativeModel.generateContent(fullPrompt)
            response.candidates.firstOrNull()?.text ?: "Unable to generate summary."
            
        } catch (e: Exception) {
            "Summary generation failed: ${e.localizedMessage ?: e.message}"
        }
    }
    
    /**
     * Cancels the current session without generating a summary.
     */
    suspend fun cancelSession() {
        val session = _currentSession.value ?: return
        
        // Delete the session (translations will cascade delete)
        sessionDao.deleteSession(session)
        
        _currentSession.value = null
        _isSessionActive.value = false
        _currentTranslations.value = emptyList()
        _sessionStatus.value = SessionStatus.Idle
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
    
    private suspend fun loadCurrentSessionTranslations(sessionId: String) {
        val translations = translationDao.getTranslationsForSessionSync(sessionId)
        _currentTranslations.value = translations
    }
    
    sealed interface SessionStatus {
        data object Idle : SessionStatus
        data object Starting : SessionStatus
        data object Active : SessionStatus
        data object GeneratingSummary : SessionStatus
        data class Error(val message: String) : SessionStatus
    }
}
