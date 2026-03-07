package com.example.jibberish

import com.example.jibberish.ui.theme.JibberishTheme
import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import kotlinx.coroutines.launch

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
                if (transcription.isNotBlank() && !isWhisperHallucination(transcription)) {
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
                e.printStackTrace()
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
 * Filters known Whisper hallucination phrases that appear on silent/near-silent audio.
 */
private fun isWhisperHallucination(text: String): Boolean {
    val normalized = text.trim().lowercase().removeSuffix(".").removeSuffix("!").trim()
    val hallucinations = setOf(
        "thanks for watching",
        "thanks for listening",
        "please subscribe",
        "like and subscribe",
        "the end",
    )
    return normalized in hallucinations
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
    var currentScreen by remember { mutableStateOf("home") }

    // Track if there's a new summary notification for History tab
    var hasNewSummary by remember { mutableStateOf(false) }

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
            NavigationBar {
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
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            when (currentScreen) {
                "home" -> HomeScreen(
                    audioRecorderManager = audioRecorderManager,
                    jargonManager = jargonManager,
                    sessionManager = sessionManager,
                    sarvamService = sarvamService,
                    onSummaryGenerated = { hasNewSummary = true }
                )
                "history" -> HistoryScreen(sessionManager)
                "settings" -> SettingsScreen(
                    dataRetentionManager = dataRetentionManager,
                    modelManager = modelManager,
                    modelDownloadManager = modelDownloadManager,
                    sarvamService = sarvamService
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
    onSummaryGenerated: () -> Unit = {}
) {
    val context = LocalContext.current
    val textState by audioRecorderManager.transcribedText.collectAsState()
    val isRecording by audioRecorderManager.isRecording.collectAsState()
    val modelStatus by jargonManager.modelStatus.collectAsState()
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
    var lastSeenJargonCount by remember { mutableIntStateOf(0) }
    val unreadJargonCount = (jargonItems.size - lastSeenJargonCount).coerceAtLeast(0)
    val jargonListState = rememberLazyListState()

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

    var hasPermission: Boolean by remember {
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
                        val session = sessionManager.endSession()
                        if (session?.generatedSummary != null) {
                            onSummaryGenerated()
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

        // Error card — auto-hides when no errors
        ErrorCard(
            sttError = (sttStatus as? SarvamSTTService.SttStatus.Error)?.message,
            modelError = (modelStatus as? JargonManager.ModelStatus.Error)?.message
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Transcription Area
        TranscriptionCard(
            text = textState,
            isRecording = isRecording,
            isTranscribing = sttStatus is SarvamSTTService.SttStatus.Transcribing,
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.3f)
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Jargon Cards Section
        JargonSection(
            jargonItems = jargonItems,
            unreadCount = unreadJargonCount,
            listState = jargonListState,
            onClearUnread = { lastSeenJargonCount = jargonItems.size },
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.7f)
        )
    }
}

@Composable
private fun ErrorCard(
    sttError: String?,
    modelError: String?
) {
    if (sttError == null && modelError == null) return

    Card(
        modifier = Modifier.fillMaxWidth(),
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

    val backgroundColor by animateColorAsState(
        targetValue = if (isSessionActive) Color(0xFF4CAF50) else MaterialTheme.colorScheme.surfaceVariant,
        label = "bg_color"
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = backgroundColor),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = when {
                        sessionStatus is SessionManager.SessionStatus.GeneratingSummary -> "Generating Summary..."
                        sessionStatus is SessionManager.SessionStatus.Starting -> "Starting Session..."
                        sttStatus is SarvamSTTService.SttStatus.Transcribing -> "Transcribing..."
                        isSessionActive && isRecording -> "Listening"
                        isSessionActive -> "Session Active"
                        sttStatus is SarvamSTTService.SttStatus.NotConfigured -> "Add Sarvam API key in Settings"
                        sttStatus is SarvamSTTService.SttStatus.Error -> "STT error — see Settings"
                        !modelReady -> "Loading AI model..."
                        else -> "Toggle to start"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (isSessionActive || isLoading) Color.White else MaterialTheme.colorScheme.onSurface
                )
                if (isSessionActive) {
                    Text(
                        text = "$translationCount chunks captured",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.8f)
                    )
                }
            }

            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(32.dp),
                    color = Color.White,
                    strokeWidth = 3.dp
                )
            } else {
                Switch(
                    checked = isSessionActive,
                    onCheckedChange = { shouldActivate ->
                        if (!hasPermission && shouldActivate) {
                            onRequestPermission()
                        } else {
                            onToggle(shouldActivate)
                        }
                    },
                    enabled = isSessionActive || (modelReady && sttStatus is SarvamSTTService.SttStatus.Ready),
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Color.White.copy(alpha = 0.3f),
                        uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                )
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
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
    }
}

@Composable
private fun TranscriptionCard(
    text: String,
    isRecording: Boolean,
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

@Composable
private fun JargonSection(
    jargonItems: List<JargonItem>,
    unreadCount: Int,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onClearUnread: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        // Section Header with badge
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Jargons",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.width(8.dp))
            if (unreadCount > 0) {
                Badge(
                    containerColor = MaterialTheme.colorScheme.primary
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Star,
                            contentDescription = null,
                            modifier = Modifier.size(10.dp)
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        Text(
                            text = unreadCount.toString(),
                            fontSize = 10.sp
                        )
                    }
                }
            }
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
                    Text(
                        text = "No jargon detected yet",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Start speaking to detect corporate jargon",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
        } else {
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(jargonItems, key = { it.id }) { item ->
                    JargonNotificationCard(
                        jargon = item.jargon,
                        meaning = item.meaning
                    )
                }
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }

    // Clear unread count when user scrolls
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) {
            onClearUnread()
        }
    }
}

@Composable
private fun JargonNotificationCard(
    jargon: String,
    meaning: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
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
                    .background(MaterialTheme.colorScheme.tertiaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "J",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
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
            }
        }
    }
}
