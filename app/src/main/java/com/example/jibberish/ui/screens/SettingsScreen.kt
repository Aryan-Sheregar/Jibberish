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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import com.example.jibberish.managers.DataRetentionManager
import com.example.jibberish.managers.ModelDownloadManager
import com.example.jibberish.managers.ModelManager
import com.example.jibberish.managers.SarvamSTTService
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    dataRetentionManager: DataRetentionManager,
    modelManager: ModelManager,
    modelDownloadManager: ModelDownloadManager,
    sarvamService: SarvamSTTService
) {
    val currentRetentionDays by dataRetentionManager.getRetentionDays().collectAsState(initial = DataRetentionManager.DEFAULT_RETENTION_DAYS)
    val modelStatus by modelManager.modelStatus.collectAsState()
    val currentModelType by modelManager.currentModelType.collectAsState()
    val downloadState by modelDownloadManager.downloadState.collectAsState()
    val sttStatus by sarvamService.status.collectAsState()
    var showRetentionDialog by remember { mutableStateOf(false) }
    var showClearDataDialog by remember { mutableStateOf(false) }
    var showModelDialog by remember { mutableStateOf(false) }
    val sessionCount by dataRetentionManager.getCompletedSessionCountFlow().collectAsState(initial = 0)
    val scope = rememberCoroutineScope()

    val gemmaModelPath = remember { modelDownloadManager.getModelPath() }

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
                Text("This will permanently delete all $sessionCount completed sessions and their translations. This action cannot be undone.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            dataRetentionManager.clearAllData()
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
                    val resolvedPath = modelDownloadManager.getModelPath()
                    modelManager.initializeModel(resolvedPath)
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
            // Speech Recognition Section
            Text(
                text = "Speech Recognition",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            SarvamApiKeyCard(
                sarvamService = sarvamService,
                sttStatus = sttStatus,
                scope = scope
            )

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

            // Gemma 2B download card — always shown so it can always be downloaded
            GemmaDownloadCard(
                downloadState = downloadState,
                existingModelPath = gemmaModelPath,
                onDownload = { modelDownloadManager.startDownload() },
                onCancel = { modelDownloadManager.cancelDownload() }
            )

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
                                text = "$sessionCount completed sessions stored",
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

        }
    }
}

@Composable
private fun SarvamApiKeyCard(
    sarvamService: SarvamSTTService,
    sttStatus: SarvamSTTService.SttStatus,
    scope: kotlinx.coroutines.CoroutineScope
) {
    var apiKeyInput by remember { mutableStateOf(sarvamService.getApiKey()) }
    val isConfigured = sttStatus is SarvamSTTService.SttStatus.Ready ||
            sttStatus is SarvamSTTService.SttStatus.Transcribing

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Sarvam Saaras-V3",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = if (isConfigured) "API key configured" else "API key required for speech-to-text",
                style = MaterialTheme.typography.bodySmall,
                color = if (isConfigured) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = apiKeyInput,
                onValueChange = { apiKeyInput = it },
                label = { Text("API Key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = {
                    scope.launch { sarvamService.setApiKey(apiKeyInput) }
                },
                enabled = apiKeyInput.isNotBlank()
            ) {
                Text("Save")
            }
        }
    }
}

@Composable
private fun GemmaDownloadCard(
    downloadState: ModelDownloadManager.DownloadState,
    existingModelPath: String?,
    onDownload: () -> Unit,
    onCancel: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Gemma 2B Model",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )

            when {
                downloadState is ModelDownloadManager.DownloadState.Completed ||
                existingModelPath != null && downloadState is ModelDownloadManager.DownloadState.Idle -> {
                    val path = (downloadState as? ModelDownloadManager.DownloadState.Completed)?.modelPath
                        ?: existingModelPath ?: ""
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Ready",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = path,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                downloadState is ModelDownloadManager.DownloadState.Downloading -> {
                    val state = downloadState
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "${state.progressPercent}%  —  ${"%.1f".format(state.downloadedMb)} / ${"%.1f".format(state.totalMb)} MB",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { state.progressPercent / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(onClick = onCancel) {
                        Text("Cancel")
                    }
                }

                downloadState is ModelDownloadManager.DownloadState.Failed -> {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = downloadState.error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = onDownload) {
                        Text("Retry Download")
                    }
                }

                else -> {
                    // Idle, model not on disk
                    Text(
                        text = "Required for MediaPipe (Gemma 2B) inference. ~1.5 GB download.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = onDownload) {
                        Text("Download Model")
                    }
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
