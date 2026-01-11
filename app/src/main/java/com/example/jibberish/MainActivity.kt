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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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

@Composable
fun MainAppStructure(
    speechManager: SpeechManager, 
    jargonManager: JargonManager,
    sessionManager: SessionManager,
    dataRetentionManager: DataRetentionManager
) {
    var currentScreen by remember { mutableStateOf("home") }

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
                    icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = "History") },
                    label = { Text("History") },
                    selected = currentScreen == "history",
                    onClick = { currentScreen = "history" }
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
                "home" -> HomeScreen(speechManager, jargonManager, sessionManager)
                "history" -> HistoryScreen(sessionManager)
                "settings" -> SettingsScreen(dataRetentionManager)
            }
        }
    }
}

@Composable
fun HomeScreen(
    speechManager: SpeechManager, 
    jargonManager: JargonManager,
    sessionManager: SessionManager
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
    
    // Animate session toggle color
    val sessionToggleColor by animateColorAsState(
        targetValue = if (isSessionActive) Color(0xFF4CAF50) else MaterialTheme.colorScheme.surfaceVariant,
        label = "session_toggle_color"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(16.dp))
        
        // Session Control Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = sessionToggleColor
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Session",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (isSessionActive) Color.White else MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = when (sessionStatus) {
                                is SessionManager.SessionStatus.Active -> "Recording translations..."
                                is SessionManager.SessionStatus.GeneratingSummary -> "Generating summary..."
                                is SessionManager.SessionStatus.Starting -> "Starting..."
                                else -> "Toggle to start"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isSessionActive) Color.White.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    
                    Switch(
                        checked = isSessionActive,
                        onCheckedChange = { shouldActivate ->
                            scope.launch {
                                if (shouldActivate) {
                                    sessionManager.startSession()
                                } else {
                                    // Stop listening first if active
                                    if (isListening) {
                                        speechManager.stopListening()
                                    }
                                    sessionManager.endSession()
                                }
                            }
                        },
                        enabled = sessionStatus !is SessionManager.SessionStatus.GeneratingSummary
                    )
                }
                
                if (isSessionActive) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "${currentTranslations.size} translations captured",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.9f)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Transcription Display
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Transcription",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = textState,
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Jargon Analysis Display
        jargonAnalysis?.let { analysis ->
            JargonAnalysisCard(analysis)
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Transcribe Button - saves to session when pressed
        Button(
            onClick = {
                if (!hasPermission) {
                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                } else {
                    if (isListening) {
                        speechManager.stopListening()
                        // Save the current transcription to the session
                        jargonAnalysis?.let { analysis ->
                            if (analysis is JargonManager.JargonResult.Success && isSessionActive) {
                                scope.launch {
                                    sessionManager.addTranslation(
                                        originalText = analysis.sentence,
                                        jsonOutput = "", // Could serialize the full response if needed
                                        containsJargon = analysis.containsJargon,
                                        jargonTerms = analysis.jargons,
                                        simplifiedMeaning = analysis.simplifiedMeaning
                                    )
                                }
                            }
                        }
                    } else {
                        speechManager.startListening()
                    }
                }
            },
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isListening) Color.Red else MaterialTheme.colorScheme.primary
            ),
            modifier = Modifier.size(width = 200.dp, height = 60.dp),
            enabled = modelStatus is JargonManager.ModelStatus.Ready && 
                     (isSessionActive || !isListening) // Can only start if session is active
        ) {
            Text(
                text = when {
                    isListening -> "STOP & SAVE"
                    !isSessionActive -> "START SESSION"
                    else -> "TRANSCRIBE"
                }
            )
        }
        
        if (!isSessionActive && !isListening) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Toggle session ON to start transcribing",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun JargonAnalysisCard(analysis: JargonManager.JargonResult) {
    when (analysis) {
        is JargonManager.JargonResult.Success -> {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (analysis.containsJargon) 
                        MaterialTheme.colorScheme.errorContainer 
                    else 
                        MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = if (analysis.containsJargon) "⚠️ Jargon Detected" else "✓ No Jargon",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (analysis.containsJargon) 
                                MaterialTheme.colorScheme.error 
                            else 
                                MaterialTheme.colorScheme.primary
                        )
                    }
                    
                    if (analysis.containsJargon && analysis.jargons.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Jargon Terms:",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        analysis.jargons.forEach { jargon ->
                            Text(
                                text = "• $jargon",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(start = 8.dp, top = 4.dp)
                            )
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Simplified:",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = analysis.simplifiedMeaning,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
        is JargonManager.JargonResult.Error -> {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Text(
                    text = analysis.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
    }
}