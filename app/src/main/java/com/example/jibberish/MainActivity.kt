package com.example.jibberish

import com.example.jibberish.ui.theme.JibberishTheme
import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.jibberish.managers.DataRetentionManager
import com.example.jibberish.managers.JargonManager
import com.example.jibberish.managers.SessionManager
import com.example.jibberish.managers.SpeechManager
import com.example.jibberish.ui.screens.HistoryScreen
import com.example.jibberish.ui.screens.SettingsScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var jargonManager: JargonManager
    private lateinit var speechManager: SpeechManager
    private lateinit var sessionManager: SessionManager
    private lateinit var dataRetentionManager: DataRetentionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        jargonManager = JargonManager(this)
        sessionManager = SessionManager(this)
        dataRetentionManager = DataRetentionManager(this)
        
        // Create SpeechManager with callback that analyzes jargon
        speechManager = SpeechManager(this) { transcription: String ->
            jargonManager.analyzeJargon(transcription)
        }

        setContent {
            JibberishTheme {
                MainAppStructure(speechManager, jargonManager, sessionManager, dataRetentionManager)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        speechManager.release()
        jargonManager.close()
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
    speechManager: SpeechManager, 
    jargonManager: JargonManager,
    sessionManager: SessionManager,
    dataRetentionManager: DataRetentionManager
) {
    var currentScreen by remember { mutableStateOf("home") }
    
    // Track if there's a new summary notification for History tab
    var hasNewSummary by remember { mutableStateOf(false) }

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
                        hasNewSummary = false // Clear badge when visiting history
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
                    speechManager = speechManager, 
                    jargonManager = jargonManager, 
                    sessionManager = sessionManager,
                    onSummaryGenerated = { hasNewSummary = true }
                )
                "history" -> HistoryScreen(sessionManager)
                "settings" -> SettingsScreen(dataRetentionManager)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    speechManager: SpeechManager, 
    jargonManager: JargonManager,
    sessionManager: SessionManager,
    onSummaryGenerated: () -> Unit = {}
) {
    val context = LocalContext.current
    val textState by speechManager.transcribedText.collectAsState()
    val isListening by speechManager.isListening.collectAsState()
    val modelStatus by jargonManager.modelStatus.collectAsState()
    val jargonAnalysis by jargonManager.lastAnalysis.collectAsState()
    val isSessionActive by sessionManager.isSessionActive.collectAsState()
    val sessionStatus by sessionManager.sessionStatus.collectAsState()
    val currentTranslations by sessionManager.currentTranslations.collectAsState()
    val scope = rememberCoroutineScope()
    
    // List of jargon items detected during the session
    var jargonItems by remember { mutableStateOf<List<JargonItem>>(emptyList()) }
    var unreadJargonCount by remember { mutableIntStateOf(0) }
    val jargonListState = rememberLazyListState()

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
    
    // Auto-start session when app opens
    LaunchedEffect(Unit) {
        if (!isSessionActive && hasPermission) {
            sessionManager.startSession()
            if (modelStatus is JargonManager.ModelStatus.Ready) {
                speechManager.startListening()
            }
        }
    }
    
    // Auto-start listening when permission is granted and session starts
    LaunchedEffect(hasPermission, isSessionActive, modelStatus) {
        if (hasPermission && isSessionActive && modelStatus is JargonManager.ModelStatus.Ready && !isListening) {
            speechManager.startListening()
        }
    }
    
    // Track jargon analysis results, add to UI list, and save to database
    LaunchedEffect(jargonAnalysis) {
        jargonAnalysis?.let { analysis ->
            if (analysis is JargonManager.JargonResult.Success) {
                // Save to database if session is active
                if (isSessionActive) {
                    sessionManager.addTranslation(
                        originalText = analysis.sentence,
                        jsonOutput = "",
                        containsJargon = analysis.containsJargon,
                        jargonTerms = analysis.jargons,
                        simplifiedMeaning = analysis.simplifiedMeaning
                    )
                }
                
                // Add to UI list if contains jargon
                if (analysis.containsJargon) {
                    analysis.jargons.forEach { jargon ->
                        val newItem = JargonItem(
                            jargon = jargon,
                            meaning = analysis.simplifiedMeaning
                        )
                        jargonItems = jargonItems + newItem
                        unreadJargonCount++
                    }
                    // Auto-scroll to bottom when new items are added
                    if (jargonItems.isNotEmpty()) {
                        jargonListState.animateScrollToItem(jargonItems.size - 1)
                    }
                }
            }
        }
    }
    
    // Animate session toggle color
    val sessionToggleColor by animateColorAsState(
        targetValue = if (isSessionActive) Color(0xFF4CAF50) else MaterialTheme.colorScheme.surfaceVariant,
        label = "session_toggle_color"
    )

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
            isListening = isListening,
            translationCount = currentTranslations.size,
            onToggle = { shouldActivate ->
                scope.launch {
                    if (shouldActivate) {
                        sessionManager.startSession()
                        if (hasPermission && modelStatus is JargonManager.ModelStatus.Ready) {
                            speechManager.startListening()
                        }
                    } else {
                        speechManager.stopListening()
                        val session = sessionManager.endSession()
                        if (session?.generatedSummary != null) {
                            onSummaryGenerated()
                        }
                        // Clear jargon items when session ends
                        jargonItems = emptyList()
                        unreadJargonCount = 0
                    }
                }
            },
            onRequestPermission = {
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            },
            hasPermission = hasPermission,
            modelReady = modelStatus is JargonManager.ModelStatus.Ready
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Transcription Area
        TranscriptionCard(
            text = textState,
            isListening = isListening,
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
            onClearUnread = { unreadJargonCount = 0 },
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.7f)
        )
    }
}

@Composable
private fun SessionControlBar(
    isSessionActive: Boolean,
    sessionStatus: SessionManager.SessionStatus,
    isListening: Boolean,
    translationCount: Int,
    onToggle: (Boolean) -> Unit,
    onRequestPermission: () -> Unit,
    hasPermission: Boolean,
    modelReady: Boolean
) {
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
                        isSessionActive && isListening -> "🎙️ Listening"
                        isSessionActive -> "Session Active"
                        else -> "Toggle to start"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (isSessionActive) Color.White else MaterialTheme.colorScheme.onSurface
                )
                if (isSessionActive) {
                    Text(
                        text = "$translationCount chunks captured",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.8f)
                    )
                }
            }
            
            Switch(
                checked = isSessionActive,
                onCheckedChange = { shouldActivate ->
                    if (!hasPermission && shouldActivate) {
                        onRequestPermission()
                    } else {
                        onToggle(shouldActivate)
                    }
                },
                enabled = sessionStatus !is SessionManager.SessionStatus.GeneratingSummary && modelReady,
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

@Composable
private fun TranscriptionCard(
    text: String,
    isListening: Boolean,
    modifier: Modifier = Modifier
) {
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
                if (isListening) {
                    Spacer(modifier = Modifier.width(8.dp))
                    // Pulsing indicator
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(Color.Red)
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
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
                        text = "🔍",
                        fontSize = 48.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
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
                // Add some padding at the bottom
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
    // iOS-style notification card
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
            // Icon/Indicator
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                Color(0xFFFF6B6B),
                                Color(0xFFFF8E53)
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "⚠️",
                    fontSize = 18.sp
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