package com.example.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.agent.AgentRuntimeState
import com.example.agent.CommandFeedItem
import com.example.agent.FeedEntryKind
import com.example.ui.components.GlassOsCard
import com.example.ui.components.StatusBadge
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CoralAlert
import com.example.ui.theme.EmeraldPulse
import com.example.ui.theme.NeuralCyan
import com.example.ui.theme.VioletAccent

@Composable
fun CommandCenterScreen(
    agentState: AgentRuntimeState,
    activeModelLabel: String,
    isOnline: Boolean,
    hasAnyApiKey: Boolean,
    onSendCommand: (String) -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onResolvePermission: (Boolean) -> Unit,
    onClearFeed: () -> Unit,
    onOpenModelSettings: () -> Unit
) {
    val context = LocalContext.current
    var inputText by remember { mutableStateOf("") }
    var isListening by remember { mutableStateOf(false) }
    var voiceStatus by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()

    val speechRecognizer = remember {
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            SpeechRecognizer.createSpeechRecognizer(context)
        } else null
    }

    DisposableEffect(Unit) {
        onDispose { speechRecognizer?.destroy() }
    }

    fun startVoiceCapture() {
        val sr = speechRecognizer ?: run {
            voiceStatus = "Speech recognition service unavailable on this device."
            return
        }
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { isListening = true; voiceStatus = "Listening (Bangla / English)..." }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { isListening = false }
            override fun onError(error: Int) { isListening = false; voiceStatus = "Voice input stopped (code $error)" }
            override fun onResults(results: Bundle?) {
                isListening = false
                voiceStatus = null
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val spoken = matches?.firstOrNull().orEmpty()
                if (spoken.isNotBlank()) inputText = spoken
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "bn-BD")
            putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf("en-US", "bn-BD"))
        }
        sr.startListening(intent)
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startVoiceCapture()
        else voiceStatus = "Microphone permission denied. Type your command below."
    }

    LaunchedEffect(agentState.feed.size) {
        if (agentState.feed.isNotEmpty()) {
            listState.animateScrollToItem(agentState.feed.lastIndex)
        }
    }

    val quickPrompts = listOf(
        "/py print('Hello from on-device Python!')",
        "/sh ls -la",
        "আমার workspace এ কি কি ফাইল আছে তা চেক করে একটি summary দাও।",
        "Open the browser and research the best free AI video generation tools.",
        "Create a Python script scripts/fibonacci.py and execute it."
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag("command_center_screen")
    ) {
        // Execution Status Header
        GlassOsCard(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            borderColor = if (agentState.isBusy) NeuralCyan else MaterialTheme.colorScheme.outline
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = agentState.currentPhaseLabel,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = activeModelLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                StatusBadge(
                    label = agentState.taskStatus,
                    color = when {
                        agentState.isPaused -> AmberWarning
                        agentState.isBusy -> NeuralCyan
                        agentState.taskStatus == "COMPLETED" -> EmeraldPulse
                        agentState.taskStatus == "FAILED" -> CoralAlert
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }

            if (agentState.isBusy) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (agentState.isPaused) {
                        FilledIconButton(
                            onClick = onResume,
                            modifier = Modifier.testTag("agent_resume_button")
                        ) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = "Resume agent")
                        }
                    } else {
                        FilledIconButton(
                            onClick = onPause,
                            modifier = Modifier.testTag("agent_pause_button")
                        ) {
                            Icon(Icons.Filled.Pause, contentDescription = "Pause agent")
                        }
                    }
                    OutlinedButton(
                        onClick = onStop,
                        modifier = Modifier.testTag("agent_stop_button")
                    ) {
                        Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Stop Task")
                    }
                }
            }
        }

        // Offline or Missing API Key banner
        if (!isOnline || !hasAnyApiKey) {
            GlassOsCard(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                borderColor = AmberWarning
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.Warning, contentDescription = null, tint = AmberWarning)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = if (!isOnline) {
                                "Offline: Cloud AI paused. Local /sh and /py commands still run on-device!"
                            } else {
                                "Add a Gemini or OpenRouter API key to unlock natural language planning (or use /sh & /py now)."
                            },
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (isOnline && !hasAnyApiKey) {
                        TextButton(onClick = onOpenModelSettings) {
                            Icon(Icons.Filled.Tune, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Keys")
                        }
                    }
                }
            }
        }

        // Level 2 Permission Request Card
        agentState.pendingPermission?.let { req ->
            GlassOsCard(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                borderColor = AmberWarning
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Shield, contentDescription = null, tint = AmberWarning)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "${req.level.title}: ${req.summary}",
                        style = MaterialTheme.typography.titleSmall,
                        color = AmberWarning
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = req.argumentsPreview,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { onResolvePermission(true) },
                        colors = ButtonDefaults.buttonColors(containerColor = EmeraldPulse),
                        modifier = Modifier.testTag("permission_approve_button")
                    ) {
                        Text("Approve & Execute")
                    }
                    OutlinedButton(
                        onClick = { onResolvePermission(false) },
                        modifier = Modifier.testTag("permission_deny_button")
                    ) {
                        Text("Deny")
                    }
                }
            }
        }

        // Feed List
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (agentState.feed.isEmpty()) {
                item {
                    GlassOsCard {
                        Text(
                            text = "Autonomous Agent Ready (Bangla & English)",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Give any multi-step instruction. The agent plans, calls real tools (Linux shell, Python, JS V8, WebView Browser, Files, Virtual Hands), verifies outputs, and reports completion.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(10.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(quickPrompts) { sample ->
                                AssistChip(
                                    onClick = { inputText = sample },
                                    label = { Text(sample.take(42)) }
                                )
                            }
                        }
                    }
                }
            } else {
                items(agentState.feed, key = { it.id }) { item ->
                    FeedItemCard(item)
                }
            }

            if (agentState.streamingBuffer.isNotBlank()) {
                item {
                    GlassOsCard(borderColor = VioletAccent.copy(alpha = 0.6f)) {
                        StatusBadge(label = "Streaming AI Thought", color = VioletAccent)
                        Spacer(Modifier.height(6.dp))
                        Text(agentState.streamingBuffer, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }

        voiceStatus?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = NeuralCyan,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
            )
        }

        // Input Composer Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            IconButton(
                onClick = {
                    val granted = ContextCompat.checkSelfPermission(
                        context, Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                    if (granted) startVoiceCapture()
                    else micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                },
                modifier = Modifier.testTag("voice_input_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.Mic,
                    contentDescription = "Voice command input",
                    tint = if (isListening) CoralAlert else MaterialTheme.colorScheme.primary
                )
            }

            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                placeholder = { Text("Command in English/বাংলা or /sh /py...") },
                maxLines = 3,
                modifier = Modifier
                    .weight(1f)
                    .testTag("command_input_field")
            )

            if (agentState.feed.isNotEmpty()) {
                IconButton(onClick = onClearFeed) {
                    Icon(Icons.Filled.ClearAll, contentDescription = "Clear feed")
                }
            }

            FilledIconButton(
                onClick = {
                    if (inputText.isNotBlank()) {
                        onSendCommand(inputText)
                        inputText = ""
                    }
                },
                modifier = Modifier.testTag("send_command_button")
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Execute command")
            }
        }
    }
}

@Composable
private fun FeedItemCard(item: CommandFeedItem) {
    val (badgeLabel, badgeColor, icon) = when (item.kind) {
        FeedEntryKind.USER_COMMAND -> Triple("User Command", NeuralCyan, Icons.Filled.CheckCircle)
        FeedEntryKind.AI_REASONING -> Triple("AI Reasoning / Report", VioletAccent, Icons.Filled.CheckCircle)
        FeedEntryKind.VERIFIED_TOOL_RESULT -> Triple("Verified Operation", EmeraldPulse, Icons.Filled.Verified)
        FeedEntryKind.PERMISSION_REQUEST -> Triple("Permission", AmberWarning, Icons.Filled.Shield)
        FeedEntryKind.ERROR_NOTICE -> Triple("Notice", CoralAlert, Icons.Filled.Warning)
    }

    GlassOsCard(borderColor = badgeColor.copy(alpha = 0.45f)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Icon(icon, contentDescription = null, tint = badgeColor, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(item.title, style = MaterialTheme.typography.titleSmall)
            }
            StatusBadge(label = badgeLabel, color = badgeColor)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = item.body,
            style = if (item.kind == FeedEntryKind.VERIFIED_TOOL_RESULT) {
                MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
            } else {
                MaterialTheme.typography.bodyMedium
            },
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
