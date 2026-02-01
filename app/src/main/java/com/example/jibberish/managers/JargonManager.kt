package com.example.jibberish.managers

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

class JargonManager(
    private val context: Context,
    private val modelManager: ModelManager
) {

    private val _modelStatus = MutableStateFlow<ModelStatus>(ModelStatus.Checking)
    val modelStatus: StateFlow<ModelStatus> = _modelStatus.asStateFlow()

    private val _lastAnalysis = MutableStateFlow<JargonResult?>(null)
    val lastAnalysis: StateFlow<JargonResult?> = _lastAnalysis.asStateFlow()

    // System prompt for jargon identification
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

    init {
        CoroutineScope(Dispatchers.IO).launch {
            observeModelStatus()
        }
    }

    private suspend fun observeModelStatus() {
        modelManager.modelStatus.collect { status ->
            _modelStatus.value = when (status) {
                is ModelManager.ModelStatus.Checking -> ModelStatus.Checking
                is ModelManager.ModelStatus.Ready -> ModelStatus.Ready
                is ModelManager.ModelStatus.Error -> ModelStatus.Error(status.message)
            }
        }
    }

    /**
     * Analyzes speech input for jargon and returns structured result.
     */
    suspend fun analyzeJargon(speechInput: String): JargonResult {
        return try {
            val fullPrompt = "$systemPrompt\n\nAnalyze this text: \"$speechInput\""

            val resultText = modelManager.generateText(fullPrompt)
            val result = parseJsonResult(resultText, speechInput)

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
        // No-op
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
