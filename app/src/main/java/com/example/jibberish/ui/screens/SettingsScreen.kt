package com.example.jibberish.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.jibberish.managers.ApiKeyManager
import com.example.jibberish.managers.DataRetentionManager
import com.example.jibberish.managers.ModelManager
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    dataRetentionManager: DataRetentionManager,
    apiKeyManager: ApiKeyManager,
    modelManager: ModelManager
) {
    val currentRetentionDays by dataRetentionManager.getRetentionDays().collectAsState(initial = DataRetentionManager.DEFAULT_RETENTION_DAYS)
    val currentApiKey by apiKeyManager.getApiKey().collectAsState(initial = "")
    val modelStatus by modelManager.modelStatus.collectAsState()
    val currentModelType by modelManager.currentModelType.collectAsState()
    var showRetentionDialog by remember { mutableStateOf(false) }
    var showClearDataDialog by remember { mutableStateOf(false) }
    var showModelDialog by remember { mutableStateOf(false) }
    var sessionCount by remember { mutableIntStateOf(0) }
    var apiKeyInput by remember { mutableStateOf("") }
    var apiKeySaved by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Initialize API key input field
    LaunchedEffect(currentApiKey) {
        if (apiKeyInput.isEmpty() && currentApiKey.isNotEmpty()) {
            apiKeyInput = currentApiKey
        }
    }

    LaunchedEffect(Unit) {
        val stats = dataRetentionManager.getDataStatistics()
        sessionCount = stats.sessionCount
    }

    if (showRetentionDialog) {
        RetentionPeriodDialog(
            currentDays = currentRetentionDays,
            onDismiss = { showRetentionDialog = false },
            onConfirm = { days ->
                scope.launch {
                    dataRetentionManager.setRetentionDays(days)
                }
                showRetentionDialog = false
            }
        )
    }

    if (showClearDataDialog) {
        AlertDialog(
            onDismissRequest = { showClearDataDialog = false },
            icon = { Icon(Icons.Default.Delete, contentDescription = null) },
            title = { Text("Clear All Data?") },
            text = {
                Text("This will permanently delete all $sessionCount sessions and their translations. This action cannot be undone.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            dataRetentionManager.clearAllData()
                            sessionCount = 0
                        }
                        showClearDataDialog = false
                    }
                ) {
                    Text("Delete All", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDataDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showModelDialog) {
        ModelSelectionDialog(
            modelManager = modelManager,
            currentModelType = currentModelType,
            onDismiss = { showModelDialog = false },
            onConfirm = { modelType ->
                scope.launch {
                    modelManager.setModelPreference(modelType)
                    modelManager.initializeModel()
                }
                showModelDialog = false
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // API Configuration Section
            Text(
                text = "API Configuration",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Groq API Key",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "Required for speech-to-text transcription (Whisper)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = apiKeyInput,
                        onValueChange = {
                            apiKeyInput = it
                            apiKeySaved = false
                        },
                        label = { Text("API Key") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                scope.launch {
                                    apiKeyManager.setApiKey(apiKeyInput)
                                    apiKeySaved = true
                                }
                            },
                            enabled = apiKeyInput.isNotBlank() && apiKeyInput != currentApiKey
                        ) {
                            Text("Save")
                        }
                        if (apiKeySaved) {
                            Text(
                                text = "Saved",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }

            // AI Model Section
            Text(
                text = "AI Model",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showModelDialog = true }
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Jargon Analysis Model",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = when (modelStatus) {
                                is ModelManager.ModelStatus.Checking -> "Checking availability..."
                                is ModelManager.ModelStatus.Ready -> {
                                    when (currentModelType) {
                                        is ModelManager.ModelType.AICore -> "AICore (Gemini Nano)"
                                        is ModelManager.ModelType.MediaPipe -> "MediaPipe (Gemma 2B)"
                                        null -> "Ready"
                                    }
                                }
                                is ModelManager.ModelStatus.Error -> "Error: ${(modelStatus as ModelManager.ModelStatus.Error).message}"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = when (modelStatus) {
                                is ModelManager.ModelStatus.Ready -> MaterialTheme.colorScheme.primary
                                is ModelManager.ModelStatus.Error -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                    if (modelStatus is ModelManager.ModelStatus.Checking) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Data Management Section
            Text(
                text = "Data Management",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    // Data Retention Setting
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showRetentionDialog = true }
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Data Retention Period",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "Automatically delete sessions older than this",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(
                            text = dataRetentionManager.getRetentionLabel(currentRetentionDays),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    HorizontalDivider()

                    // Clear All Data
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showClearDataDialog = true }
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Clear All Data",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.error
                            )
                            Text(
                                text = "$sessionCount sessions stored",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Clear data",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // About Section
            Text(
                text = "About",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Jibberish",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Version 1.0",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "Jibberish uses on-device AI to detect and simplify corporate jargon in real-time. Audio is transcribed via Groq's Whisper API, while jargon analysis runs locally on your device.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            // Privacy Note
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                )
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Text(
                        text = "Privacy Note",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(end = 12.dp)
                    )
                    Text(
                        text = "Audio chunks are sent to Groq for transcription. Jargon analysis and all data storage remain on-device. Your Groq API key is stored locally.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelSelectionDialog(
    modelManager: ModelManager,
    currentModelType: ModelManager.ModelType?,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    val aiCoreSupported = remember { modelManager.isAICoreSupported() }
    var selectedModel by remember {
        mutableStateOf(
            when (currentModelType) {
                is ModelManager.ModelType.AICore -> ModelManager.MODEL_AICORE
                is ModelManager.ModelType.MediaPipe -> ModelManager.MODEL_MEDIAPIPE
                null -> if (aiCoreSupported) ModelManager.MODEL_AICORE else ModelManager.MODEL_MEDIAPIPE
            }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select AI Model") },
        text = {
            Column {
                Text(
                    text = "Choose which model to use for jargon analysis and summaries:",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                // AICore option
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = aiCoreSupported) { selectedModel = ModelManager.MODEL_AICORE }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = selectedModel == ModelManager.MODEL_AICORE,
                        onClick = { selectedModel = ModelManager.MODEL_AICORE },
                        enabled = aiCoreSupported
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "AICore (Gemini Nano)",
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (aiCoreSupported) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        )
                        if (!aiCoreSupported) {
                            Text(
                                text = "Not available on this device",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    if (selectedModel == ModelManager.MODEL_AICORE && aiCoreSupported) {
                        Spacer(modifier = Modifier.weight(1f))
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Selected",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                // MediaPipe option
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { selectedModel = ModelManager.MODEL_MEDIAPIPE }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = selectedModel == ModelManager.MODEL_MEDIAPIPE,
                        onClick = { selectedModel = ModelManager.MODEL_MEDIAPIPE }
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "MediaPipe (Gemma 2B)",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            text = "Local on-device model",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (selectedModel == ModelManager.MODEL_MEDIAPIPE) {
                        Spacer(modifier = Modifier.weight(1f))
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Selected",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selectedModel) }) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun RetentionPeriodDialog(
    currentDays: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    var selectedDays by remember { mutableIntStateOf(currentDays) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Data Retention Period") },
        text = {
            Column {
                Text(
                    text = "Choose how long to keep your session history:",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                DataRetentionManager.RETENTION_OPTIONS.forEach { (days, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedDays = days }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedDays == days,
                            onClick = { selectedDays = days }
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyLarge
                        )
                        if (selectedDays == days) {
                            Spacer(modifier = Modifier.weight(1f))
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Selected",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selectedDays) }) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
