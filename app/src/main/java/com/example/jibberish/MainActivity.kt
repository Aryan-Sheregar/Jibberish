package com.example.jibberish

import com.example.jibberish.ui.theme.JibberishTheme
import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.RecordVoiceOver
import com.example.jibberish.ui.theme.StatusDownloading
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.jibberish.managers.AudioRecorderManager
import com.example.jibberish.managers.DataRetentionManager
import com.example.jibberish.managers.JargonManager
import com.example.jibberish.managers.ModelDownloadManager
import com.example.jibberish.managers.ModelManager
import com.example.jibberish.managers.SessionManager
import com.example.jibberish.managers.SarvamSTTService
import org.json.JSONObject
import com.example.jibberish.ui.screens.HistoryScreen
import com.example.jibberish.ui.screens.SettingsScreen
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private lateinit var modelManager: ModelManager
    private lateinit var jargonManager: JargonManager
    private lateinit var audioRecorderManager: AudioRecorderManager
    private lateinit var sessionManager: SessionManager
    private lateinit var dataRetentionManager: DataRetentionManager
    private lateinit var sarvamService: SarvamSTTService
    private lateinit var modelDownloadManager: ModelDownloadManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        sarvamService = SarvamSTTService(applicationContext)
        modelDownloadManager = ModelDownloadManager(applicationContext)
        modelManager = ModelManager(applicationContext)
        dataRetentionManager = DataRetentionManager(applicationContext)
        jargonManager = JargonManager(modelManager)
        sessionManager = SessionManager(applicationContext, modelManager)

        // Create AudioRecorderManager with callback that transcribes audio chunks via Sarvam API
        audioRecorderManager = AudioRecorderManager(applicationContext) { audioFile ->
            val result = sarvamService.transcribeAudio(audioFile)
            result.onSuccess { transcription ->
                if (transcription.isNotBlank()) {
                    audioRecorderManager.updateTranscription(transcription)
                    val jargonResult = jargonManager.analyzeJargon(transcription)
                    // Always persist transcription; add jargon metadata when available
                    when (jargonResult) {
                        is JargonManager.JargonResult.Success -> {
                            sessionManager.addTranslation(
                                originalText = jargonResult.sentence,
                                jsonOutput = "",
                                containsJargon = jargonResult.containsJargon,
                                jargonTerms = jargonResult.jargonMeanings,
                                simplifiedMeaning = jargonResult.simplifiedMeaning
                            )
                        }
                        is JargonManager.JargonResult.Error -> {
                            sessionManager.addTranslation(
                                originalText = transcription,
                                jsonOutput = "",
                                containsJargon = false,
                                jargonTerms = emptyMap(),
                                simplifiedMeaning = null
                            )
                        }
                    }
                }
            }
            result.onFailure { e ->
                android.util.Log.e("MainActivity", "STT transcription failed", e)
            }
        }

        // Initialize LLM model (MediaPipe path from download manager, null triggers AICore fallback)
        lifecycleScope.launch(Dispatchers.IO) {
            val mediaPipeModelPath = modelDownloadManager.getModelPath()
            modelManager.initializeModel(mediaPipeModelPath)
        }

        // Load saved Sarvam API key (status becomes Ready if key exists)
        lifecycleScope.launch(Dispatchers.IO) {
            sarvamService.initialize()
        }

        setContent {
            JibberishTheme {
                MainAppStructure(
                    audioRecorderManager = audioRecorderManager,
                    jargonManager = jargonManager,
                    sessionManager = sessionManager,
                    dataRetentionManager = dataRetentionManager,
                    modelManager = modelManager,
                    modelDownloadManager = modelDownloadManager,
                    sarvamService = sarvamService
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            // Full cleanup only when truly finishing (not config change)
            audioRecorderManager.destroy()
            jargonManager.close()
            sessionManager.close()
            dataRetentionManager.close()
            modelManager.cleanup()
            modelDownloadManager.close()
            sarvamService.close()
        } else {
            // Config change — release recorder but let in-flight work complete
            audioRecorderManager.release()
        }
    }
}

/**
 * Parses jargon terms from stored string.
 * Supports JSON object format {"term":"meaning"} and legacy comma-separated format.
 */
internal fun parseJargonTerms(stored: String): List<Pair<String, String>> {
    return try {
        val json = JSONObject(stored)
        json.keys().asSequence().map { key -> key to json.optString(key, "") }.toList()
    } catch (_: Exception) {
        // Fallback: old comma-separated format (no individual meanings)
        stored.split(",").map { it.trim() }.filter { it.isNotBlank() }.map { it to "" }
    }
}

// Data class for jargon items in the list
data class JargonItem(
    val id: String = java.util.UUID.randomUUID().toString(),
    val jargon: String,
    val meaning: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Composable
fun MainAppStructure(
    audioRecorderManager: AudioRecorderManager,
    jargonManager: JargonManager,
    sessionManager: SessionManager,
    dataRetentionManager: DataRetentionManager,
    modelManager: ModelManager,
    modelDownloadManager: ModelDownloadManager,
    sarvamService: SarvamSTTService
) {
    var currentScreen by rememberSaveable { mutableStateOf("home") }

    // Track if there's a new summary notification for History tab
    var hasNewSummary by rememberSaveable { mutableStateOf(false) }

    // Auto-reinit LLM after Gemma download completes
    val gemmaDownloadState by modelDownloadManager.downloadState.collectAsState()
    LaunchedEffect(gemmaDownloadState) {
        if (gemmaDownloadState is ModelDownloadManager.DownloadState.Completed) {
            modelManager.initializeModel(
                (gemmaDownloadState as ModelDownloadManager.DownloadState.Completed).modelPath
            )
        }
    }

    Scaffold(
        bottomBar = {
            Surface(
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shadowElevation = 8.dp
            ) {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                tonalElevation = 0.dp
            ) {
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Home, contentDescription = "Home") },
                    label = { Text("Listen") },
                    selected = currentScreen == "home",
                    onClick = { currentScreen = "home" }
                )
                NavigationBarItem(
                    icon = {
                        BadgedBox(
                            badge = {
                                if (hasNewSummary) {
                                    Badge {
                                        Icon(
                                            imageVector = Icons.Default.Star,
                                            contentDescription = "New summary",
                                            modifier = Modifier.size(8.dp)
                                        )
                                    }
                                }
                            }
                        ) {
                            Icon(Icons.AutoMirrored.Filled.List, contentDescription = "History")
                        }
                    },
                    label = { Text("History") },
                    selected = currentScreen == "history",
                    onClick = {
                        currentScreen = "history"
                        hasNewSummary = false
                    }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                    label = { Text("Settings") },
                    selected = currentScreen == "settings",
                    onClick = { currentScreen = "settings" }
                )
            }
            } // Surface
        }
    ) { innerPadding ->
        Crossfade(
            targetState = currentScreen,
            modifier = Modifier.padding(innerPadding),
            label = "screen_transition"
        ) { screen ->
            when (screen) {
                "home" -> HomeScreen(
                    audioRecorderManager = audioRecorderManager,
                    jargonManager = jargonManager,
                    sessionManager = sessionManager,
                    sarvamService = sarvamService,
                    modelManager = modelManager,
                    onSummaryGenerated = { hasNewSummary = true }
                )
                "history" -> HistoryScreen(sessionManager)
                "settings" -> SettingsScreen(
                    dataRetentionManager = dataRetentionManager,
                    modelManager = modelManager,
                    modelDownloadManager = modelDownloadManager
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    audioRecorderManager: AudioRecorderManager,
    jargonManager: JargonManager,
    sessionManager: SessionManager,
    sarvamService: SarvamSTTService,
    modelManager: ModelManager,
    onSummaryGenerated: () -> Unit = {}
) {
    val context = LocalContext.current
    val textState by audioRecorderManager.transcribedText.collectAsState()
    val isRecording by audioRecorderManager.isRecording.collectAsState()
    val modelStatus by jargonManager.modelStatus.collectAsState()
    val rawModelStatus by modelManager.modelStatus.collectAsState()
    val isSessionActive by sessionManager.isSessionActive.collectAsState()
    val sessionStatus by sessionManager.sessionStatus.collectAsState()
    val currentTranslations by sessionManager.currentTranslations.collectAsState()
    val sttStatus by sarvamService.status.collectAsState()
    val scope = rememberCoroutineScope()

    // Derive jargon items from source-of-truth (persisted translations) — survives navigation
    val jargonItems = currentTranslations
        .filter { it.containsJargon && !it.jargonTerms.isNullOrBlank() }
        .flatMap { translation ->
            parseJargonTerms(translation.jargonTerms!!).map { (term, meaning) ->
                JargonItem(
                    id = "${translation.translationId}_$term",
                    jargon = term,
                    meaning = meaning.ifBlank { translation.simplifiedMeaning ?: "" },
                    timestamp = translation.timestamp
                )
            }
        }
    var lastSeenJargonCount by rememberSaveable { mutableIntStateOf(0) }
    val unreadJargonCount = (jargonItems.size - lastSeenJargonCount).coerceAtLeast(0)
    val jargonListState = rememberLazyListState()

    // Tab state: 0 = Live Feed, 1 = Jargon
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    // Reset unread counter when session ends
    LaunchedEffect(isSessionActive) {
        if (!isSessionActive) {
            lastSeenJargonCount = 0
        }
    }

    // Auto-scroll when new jargon items appear
    LaunchedEffect(jargonItems.size) {
        if (jargonItems.isNotEmpty() && jargonItems.size > lastSeenJargonCount) {
            jargonListState.animateScrollToItem(jargonItems.size - 1)
        }
    }

    var hasPermission: Boolean by rememberSaveable {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted -> hasPermission = isGranted }
    )

    // Auto-start recording when permission is granted and session starts (after manual toggle)
    LaunchedEffect(hasPermission, isSessionActive, modelStatus, sttStatus) {
        if (hasPermission && isSessionActive &&
            modelStatus is JargonManager.ModelStatus.Ready &&
            sttStatus is SarvamSTTService.SttStatus.Ready && !isRecording
        ) {
            audioRecorderManager.startRecording()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.Top
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        // Top Bar - Session Control
        SessionControlBar(
            isSessionActive = isSessionActive,
            sessionStatus = sessionStatus,
            isRecording = isRecording,
            translationCount = currentTranslations.size,
            onToggle = { shouldActivate ->
                scope.launch {
                    if (shouldActivate) {
                        sessionManager.startSession()
                        if (hasPermission && modelStatus is JargonManager.ModelStatus.Ready) {
                            audioRecorderManager.startRecording()
                        }
                    } else {
                        audioRecorderManager.stopRecording()
                        withContext(NonCancellable) {
                            val session = sessionManager.endSession()
                            if (session?.generatedSummary != null) {
                                onSummaryGenerated()
                            }
                        }
                    }
                }
            },
            onRequestPermission = {
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            },
            hasPermission = hasPermission,
            modelReady = modelStatus is JargonManager.ModelStatus.Ready,
            sttStatus = sttStatus
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Model status card — only visible when model is not yet ready
        ModelStatusCard(status = rawModelStatus, modifier = Modifier.fillMaxWidth())

        // Error card — auto-hides when no errors
        ErrorCard(
            sttError = (sttStatus as? SarvamSTTService.SttStatus.Error)?.message,
            modelError = (modelStatus as? JargonManager.ModelStatus.Error)?.message
        )

        // TabRow for Live Feed / Jargon
        PrimaryTabRow(
            selectedTabIndex = selectedTab,
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.primary
        ) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text("Live Feed") }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = {
                    selectedTab = 1
                    lastSeenJargonCount = jargonItems.size
                },
                text = {
                    if (unreadJargonCount > 0 && selectedTab != 1) {
                        BadgedBox(
                            badge = {
                                Badge { Text(unreadJargonCount.toString()) }
                            }
                        ) {
                            Text("Jargon")
                        }
                    } else {
                        Text("Jargon")
                    }
                }
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Tab content fills remaining space
        when (selectedTab) {
            0 -> TranscriptionCard(
                text = textState,
                isRecording = isRecording,
                isSessionActive = isSessionActive,
                isTranscribing = sttStatus is SarvamSTTService.SttStatus.Transcribing,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
            1 -> JargonSection(
                jargonItems = jargonItems,
                isSessionActive = isSessionActive,
                listState = jargonListState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
        }
    }
}

/**
 * Compact status card shown on HomeScreen when the AI model is not yet ready.
 * Hides itself entirely once ModelStatus.Ready — takes zero space when hidden.
 */
@Composable
private fun ModelStatusCard(
    status: ModelManager.ModelStatus,
    modifier: Modifier = Modifier
) {
    // Only render when model is not ready
    if (status is ModelManager.ModelStatus.Ready) return

    val (iconTint, containerColor, headline, subtext, showProgress, isIndeterminate, progress) =
        when (status) {
            is ModelManager.ModelStatus.Checking -> ModelStatusCardData(
                iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                headline = "Checking AI model...",
                subtext = null,
                showProgress = true,
                isIndeterminate = true,
                progress = 0f
            )
            is ModelManager.ModelStatus.Downloading -> {
                val modelName = when (status.modelType) {
                    is ModelManager.ModelType.AICore -> "Gemini Nano"
                    is ModelManager.ModelType.MediaPipe -> "Gemma 2B"
                }
                val sub = when (status.modelType) {
                    is ModelManager.ModelType.AICore -> "System is downloading the model in the background."
                    is ModelManager.ModelType.MediaPipe ->
                        if (status.progressPercent >= 0) "${status.progressPercent}% downloaded"
                        else "Download in progress..."
                }
                val prog = if (status.progressPercent >= 0) status.progressPercent / 100f else 0f
                ModelStatusCardData(
                    iconTint = StatusDownloading,
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    headline = "Downloading $modelName",
                    subtext = sub,
                    showProgress = true,
                    isIndeterminate = status.progressPercent < 0,
                    progress = prog
                )
            }
            is ModelManager.ModelStatus.Error -> ModelStatusCardData(
                iconTint = MaterialTheme.colorScheme.error,
                containerColor = MaterialTheme.colorScheme.errorContainer,
                headline = status.message,
                subtext = "Go to Settings → AI Model to switch to Gemma 2B.",
                showProgress = false,
                isIndeterminate = false,
                progress = 0f
            )
            is ModelManager.ModelStatus.Ready -> return  // unreachable, silences exhaustive warning
        }

    Card(
        modifier = modifier.padding(bottom = 8.dp),
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = containerColor),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (showProgress && isIndeterminate) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = iconTint
                    )
                } else if (status is ModelManager.ModelStatus.Error) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = iconTint
                    )
                }
                Text(
                    text = headline,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    color = if (status is ModelManager.ModelStatus.Error)
                        MaterialTheme.colorScheme.onErrorContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            subtext?.let {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (status is ModelManager.ModelStatus.Error)
                        MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                )
            }
            if (showProgress && !isIndeterminate) {
                Spacer(modifier = Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                    color = StatusDownloading,
                    trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                )
            }
        }
    }
}

private data class ModelStatusCardData(
    val iconTint: androidx.compose.ui.graphics.Color,
    val containerColor: androidx.compose.ui.graphics.Color,
    val headline: String,
    val subtext: String?,
    val showProgress: Boolean,
    val isIndeterminate: Boolean,
    val progress: Float
)

@Composable
private fun ErrorCard(
    sttError: String?,
    modelError: String?
) {
    if (sttError == null && modelError == null) return

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            sttError?.let {
                Text(
                    text = "Speech-to-text: $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (sttError != null && modelError != null) {
                Spacer(modifier = Modifier.height(2.dp))
            }
            modelError?.let {
                Text(
                    text = "AI model: $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun SessionControlBar(
    isSessionActive: Boolean,
    sessionStatus: SessionManager.SessionStatus,
    isRecording: Boolean,
    translationCount: Int,
    onToggle: (Boolean) -> Unit,
    onRequestPermission: () -> Unit,
    hasPermission: Boolean,
    modelReady: Boolean,
    sttStatus: SarvamSTTService.SttStatus
) {
    val isLoading = sessionStatus is SessionManager.SessionStatus.Starting ||
            sessionStatus is SessionManager.SessionStatus.GeneratingSummary ||
            (isSessionActive && sttStatus is SarvamSTTService.SttStatus.Transcribing)

    val canStart = modelReady && sttStatus is SarvamSTTService.SttStatus.Ready

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = when {
                        sessionStatus is SessionManager.SessionStatus.GeneratingSummary -> "Generating Summary..."
                        sessionStatus is SessionManager.SessionStatus.Starting -> "Starting Session..."
                        sttStatus is SarvamSTTService.SttStatus.Transcribing -> "Transcribing..."
                        isSessionActive && isRecording -> "Listening"
                        isSessionActive -> "Session Active"
                        else -> "Ready"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (isSessionActive) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.onSurface
                )
                if (isSessionActive) {
                    Text(
                        text = "$translationCount chunks captured",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else if (!canStart) {
                    // Prerequisite hints
                    val hint = when {
                        sttStatus is SarvamSTTService.SttStatus.Error -> "STT error — check Settings"
                        !modelReady -> "Loading AI model..."
                        else -> null
                    }
                    hint?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(32.dp),
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 3.dp
                )
            } else if (isSessionActive) {
                Button(
                    onClick = { onToggle(false) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Stop")
                }
            } else {
                Button(
                    onClick = {
                        if (!hasPermission) {
                            onRequestPermission()
                        } else {
                            onToggle(true)
                        }
                    },
                    enabled = canStart,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Start Listening")
                }
            }
        }
    }
}

@Composable
private fun AudioWaveIndicator(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "audio_wave")
    val barCount = 5
    val barHeights = (0 until barCount).map { index ->
        infiniteTransition.animateFloat(
            initialValue = 0.3f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 400 + index * 80),
                repeatMode = RepeatMode.Reverse
            ),
            label = "bar_$index"
        )
    }

    Row(
        modifier = modifier.height(16.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        barHeights.forEach { animatedHeight ->
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .fillMaxHeight(animatedHeight.value)
                    .clip(RoundedCornerShape(1.5.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.7f))
            )
        }
    }
}

@Composable
private fun TranscriptionCard(
    text: String,
    isRecording: Boolean,
    isSessionActive: Boolean,
    isTranscribing: Boolean,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()

    // Auto-scroll to the bottom whenever new transcription text arrives
    LaunchedEffect(text) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Transcription",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                when {
                    isTranscribing -> Text(
                        text = "Processing...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    isRecording -> AudioWaveIndicator()
                }
            }
            Spacer(modifier = Modifier.height(8.dp))

            if (text.isBlank()) {
                // Empty state
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Outlined.RecordVoiceOver,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = if (isSessionActive) "Listening... speech will appear here"
                                   else "Start a session to see transcription",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                ) {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
private fun JargonSection(
    jargonItems: List<JargonItem>,
    isSessionActive: Boolean,
    listState: androidx.compose.foundation.lazy.LazyListState,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()

    Column(modifier = modifier) {
        // Section Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "${jargonItems.size} total",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Jargon Cards List
        if (jargonItems.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Outlined.RecordVoiceOver,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = if (isSessionActive) "Listening... jargon will appear here"
                               else "Start a session to detect jargon",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
            }
        } else {
            // Smart auto-scroll: track if user is near bottom
            val isNearBottom by remember {
                derivedStateOf {
                    val layoutInfo = listState.layoutInfo
                    val lastVisibleItem = layoutInfo.visibleItemsInfo.lastOrNull()
                    lastVisibleItem != null &&
                        lastVisibleItem.index >= layoutInfo.totalItemsCount - 2
                }
            }

            // Auto-scroll only when near bottom
            LaunchedEffect(jargonItems.size, isNearBottom) {
                if (jargonItems.isNotEmpty() && isNearBottom) {
                    listState.animateScrollToItem(jargonItems.size - 1)
                }
            }

            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(jargonItems, key = { it.id }) { item ->
                        JargonNotificationCard(
                            jargon = item.jargon,
                            meaning = item.meaning,
                            timestamp = item.timestamp,
                            modifier = Modifier.animateItem()
                        )
                    }
                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }

                // "Jump to live" FAB when scrolled away
                if (!isNearBottom && jargonItems.size > 3) {
                    SmallFloatingActionButton(
                        onClick = {
                            scope.launch {
                                listState.animateScrollToItem(jargonItems.size - 1)
                            }
                        },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(16.dp),
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = "Jump to live"
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun JargonNotificationCard(
    jargon: String,
    meaning: String,
    timestamp: Long,
    modifier: Modifier = Modifier
) {
    val relativeTime = remember(timestamp) {
        val diff = System.currentTimeMillis() - timestamp
        val minutes = diff / 60_000
        val hours = diff / 3_600_000
        when {
            minutes < 1 -> "just now"
            minutes < 60 -> "${minutes}m ago"
            hours < 24 -> "${hours}h ago"
            else -> "${hours / 24}d ago"
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = jargon.firstOrNull()?.uppercase() ?: "J",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = jargon,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = meaning,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 18.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = relativeTime,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
        }
    }
}
