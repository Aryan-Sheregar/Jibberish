package com.example.jibberish.managers

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

class JargonManager(
    private val modelManager: ModelManager
) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val _modelStatus = MutableStateFlow<ModelStatus>(ModelStatus.Checking)
    val modelStatus: StateFlow<ModelStatus> = _modelStatus.asStateFlow()

    // System prompt for jargon identification
    private val systemPrompt = """
You are a jargon detection assistant. Analyze the given text and identify any business jargon, corporate speak, or technical terms. Provide the meaning of each jargon term individually.

Return your response as JSON in this EXACT format:
{
  "contains_jargon": true,
  "sentence": "original sentence here",
  "jargons": {"term1": "plain meaning of term1", "term2": "plain meaning of term2"},
  "simplified_meaning": "plain language version of the full sentence"
}

If there is NO jargon, return:
{
  "contains_jargon": false,
  "sentence": "original sentence here",
  "jargons": {},
  "simplified_meaning": "The sentence is already clear and straightforward."
}

Always respond with valid JSON only, no additional text or markdown.
""".trimIndent()

    init {
        scope.launch {
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
            parseJsonResult(resultText, speechInput)

        } catch (e: Exception) {
            JargonResult.Error("Error: ${e.localizedMessage ?: e.message}")
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

            val jargonMeanings = mutableMapOf<String, String>()
            val jargonsValue = json.opt("jargons")
            when (jargonsValue) {
                is JSONObject -> {
                    jargonsValue.keys().forEach { key ->
                        jargonMeanings[key] = jargonsValue.optString(key, "")
                    }
                }
                is org.json.JSONArray -> {
                    // Fallback: LLM returned old array format — use simplified_meaning for all
                    val fallbackMeaning = json.optString("simplified_meaning", "")
                    for (i in 0 until jargonsValue.length()) {
                        jargonMeanings[jargonsValue.getString(i)] = fallbackMeaning
                    }
                }
            }

            val simplifiedMeaning = json.optString("simplified_meaning", "")

            JargonResult.Success(
                containsJargon = containsJargon,
                sentence = sentence,
                jargonMeanings = jargonMeanings,
                simplifiedMeaning = simplifiedMeaning
            )

        } catch (e: Exception) {
            JargonResult.Error("Failed to parse response: ${e.message}")
        }
    }

    fun close() {
        scope.cancel()
    }

    sealed interface ModelStatus {
        data object Checking : ModelStatus
        data object Ready : ModelStatus
        data class Error(val message: String) : ModelStatus
    }

    sealed interface JargonResult {
        data class Success(
            val containsJargon: Boolean,
            val sentence: String,
            val jargonMeanings: Map<String, String>,
            val simplifiedMeaning: String
        ) : JargonResult

        data class Error(val message: String) : JargonResult
    }
}
