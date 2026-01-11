package com.example.jibberish.managers

import android.content.Context
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

class JargonManager(private val context: Context) {

    private val _modelStatus = MutableStateFlow<ModelStatus>(ModelStatus.Checking)
    val modelStatus: StateFlow<ModelStatus> = _modelStatus.asStateFlow()

    private val _lastAnalysis = MutableStateFlow<JargonResult?>(null)
    val lastAnalysis: StateFlow<JargonResult?> = _lastAnalysis.asStateFlow()

    // System prompt for jargon identification (this will be cached via prefix caching)
    private val systemPrompt = """
You are a jargon detection assistant. Analyze the given text and identify any business jargon, corporate speak, or technical terms.

Return your response as JSON in this EXACT format:
{
  "contains_jargon": true,
  "sentence": "original sentence here",
  "jargons": ["term1", "term2"],
  "simplified_meaning": "plain language explanation"
}

If there is NO jargon, return:
{
  "contains_jargon": false,
  "sentence": "original sentence here",
  "jargons": [],
  "simplified_meaning": "The sentence is already clear and straightforward."
}

Always respond with valid JSON only, no additional text or markdown.
""".trimIndent()

    private val generativeModel: GenerativeModel = Generation.getClient()

    init {
        CoroutineScope(Dispatchers.IO).launch {
            checkModelStatus()
        }
    }

    private suspend fun checkModelStatus() {
        try {
            // For now, assume model is ready when client is obtained
            // TODO: Add FeatureStatus and download handling later
            _modelStatus.value = ModelStatus.Ready
        } catch (e: Exception) {
            _modelStatus.value = ModelStatus.Error(e.message ?: "Unknown error")
        }
    }

    /**
     * Analyzes speech input for jargon and returns structured result.
     * Uses prefix caching by keeping the system prompt consistent across all requests.
     */
    suspend fun analyzeJargon(speechInput: String): JargonResult {
        return try {
            // Build the full prompt with system instruction (prefix caching)
            val fullPrompt = "$systemPrompt\n\nAnalyze this text: \"$speechInput\""

            // Use generateContent from ML Kit GenAI Prompt API
            val response = generativeModel.generateContent(fullPrompt)

            if (_modelStatus.value != ModelStatus.Ready) {
                _modelStatus.value = ModelStatus.Ready
            }

            val resultText = response.candidates.firstOrNull()?.text ?: "{}"
            val result = parseJsonResult(resultText, speechInput)

            // Update the flow so UI can observe
            _lastAnalysis.value = result

            result

        } catch (e: Exception) {
            val errorResult = JargonResult.Error("Error: ${e.localizedMessage ?: e.message}")
            _lastAnalysis.value = errorResult
            errorResult
        }
    }

    private fun parseJsonResult(jsonText: String, originalText: String): JargonResult {
        return try {
            // Clean up potential markdown code blocks
            val cleanJson = jsonText
                .replace("```json", "")
                .replace("```", "")
                .trim()

            val json = JSONObject(cleanJson)

            val containsJargon = json.optBoolean("contains_jargon", false)
            val sentence = json.optString("sentence", originalText)

            val jargonList = mutableListOf<String>()
            val jargonArray = json.optJSONArray("jargons")
            if (jargonArray != null) {
                for (i in 0 until jargonArray.length()) {
                    jargonList.add(jargonArray.getString(i))
                }
            }

            val simplifiedMeaning = json.optString("simplified_meaning", "")

            JargonResult.Success(
                containsJargon = containsJargon,
                sentence = sentence,
                jargons = jargonList,
                simplifiedMeaning = simplifiedMeaning
            )

        } catch (e: Exception) {
            JargonResult.Error("Failed to parse response: ${e.message}")
        }
    }

    fun close() {
        // ML Kit GenAI Prompt doesn't require explicit close in alpha version
    }

    sealed interface ModelStatus {
        data object Checking : ModelStatus
        data object Downloading : ModelStatus
        data object Ready : ModelStatus
        data class Error(val message: String) : ModelStatus
    }

    sealed interface JargonResult {
        data class Success(
            val containsJargon: Boolean,
            val sentence: String,
            val jargons: List<String>,
            val simplifiedMeaning: String
        ) : JargonResult

        data class Error(val message: String) : JargonResult
    }
}