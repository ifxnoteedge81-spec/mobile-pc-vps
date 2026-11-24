package com.example.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Launch
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Monitor
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.accessibility.ObservedUiNode
import com.example.agent.AgentRuntimeState
import com.example.agent.AgentSkillRegistry
import com.example.agent.AgentUploadedAttachment
import com.example.agent.CommandFeedItem
import com.example.agent.FeedEntryKind
import com.example.agent.SkillCategory
import com.example.browser.BrowserController
import com.example.browser.BrowserTab
import com.example.ui.components.GlassOsCard
import com.example.ui.components.StatusBadge
import com.example.ui.components.formatBytes
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CoralAlert
import com.example.ui.theme.EmeraldPulse
import com.example.ui.theme.NeuralCyan
import com.example.ui.theme.VioletAccent

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommandCenterScreen(
    agentState: AgentRuntimeState,
    activeModelLabel: String,
    isOnline: Boolean,
    hasAnyApiKey: Boolean,
    pendingAttachments: List<AgentUploadedAttachment>,
    browserController: BrowserController,
    browserTabs: List<BrowserTab>,
    activeTabId: String,
    accessibilityConnected: Boolean,
    activeWindowPackage: String,
    liveNodes: List<ObservedUiNode>,
    onAttachFiles: (List<Uri>) -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onSendCommand: (String) -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onResolvePermission: (Boolean) -> Unit,
    onResolveIntervention: (String) -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenExternalApp: (String?) -> Unit,
    onInspectDeviceScreen: () -> Unit,
    onQuickVirtualHand: (String) -> Unit,
    onClearFeed: () -> Unit,
    onOpenModelSettings: () -> Unit
) {
    val context = LocalContext.current
    var inputText by remember { mutableStateOf("") }
    var isListening by remember { mutableStateOf(false) }
    var voiceStatus by remember { mutableStateOf<String?>(null) }
    var showLiveScreen by remember { mutableStateOf(false) }
    var liveScreenMode by remember { mutableStateOf("BROWSER") } // "BROWSER" or "VIRTUAL_HANDS"
    var showSkillsSheet by remember { mutableStateOf(false) }
    var selectedSkillCategory by remember { mutableStateOf<SkillCategory?>(null) }
    var interventionNote by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    val intervention = agentState.pendingIntervention
    LaunchedEffect(intervention?.id) {
        if (intervention != null) {
            showLiveScreen = true
            liveScreenMode = intervention.targetMode
            interventionNote = ""
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            onAttachFiles(uris)
        }
    }

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
        "আমার আপলোড করা ফাইলগুলো পড়ে সারসংক্ষেপ দাও।",
        "Virtual Hands দিয়ে ফোনের অ্যাপ ওপেন করে স্ক্রিন দেখে কাজ করো।",
        "ব্রাউজারে কাজটি শুরু করো এবং OTP বা CAPTCHA এলে আমাকে লাইভ স্ক্রিন দেখাও।"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag("command_center_screen")
    ) {
        // Execution Status Header + Live Screen & Skills Toggles
        GlassOsCard(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            borderColor = when {
                intervention != null -> AmberWarning
                agentState.isBusy -> NeuralCyan
                else -> MaterialTheme.colorScheme.outline
            }
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
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = activeModelLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                StatusBadge(
                    label = agentState.taskStatus,
                    color = when {
                        intervention != null || agentState.isPaused -> AmberWarning
                        agentState.isBusy -> NeuralCyan
                        agentState.taskStatus == "COMPLETED" -> EmeraldPulse
                        agentState.taskStatus == "FAILED" -> CoralAlert
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }

            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(
                    selected = showLiveScreen,
                    onClick = { showLiveScreen = !showLiveScreen },
                    leadingIcon = { Icon(Icons.Filled.Monitor, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    label = { Text(if (showLiveScreen) "লাইভ স্ক্রিন চালু" else "লাইভ স্ক্রিন") },
                    modifier = Modifier.testTag("toggle_live_screen_chip")
                )
                AssistChip(
                    onClick = { showSkillsSheet = true },
                    leadingIcon = { Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    label = { Text("Skills & Virtual Hands") },
                    modifier = Modifier.testTag("open_skills_sheet_chip")
                )
            }

            if (agentState.isBusy && intervention == null) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
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
                        Text("Stop")
                    }
                }
            }
        }

        // Embedded Live Screen Inside Agent Box (Auto-pops on OTP / CAPTCHA / Intervention)
        AnimatedVisibility(visible = showLiveScreen || intervention != null) {
            LiveScreenAgentPanel(
                intervention = intervention,
                liveScreenMode = liveScreenMode,
                onSelectMode = { liveScreenMode = it },
                onClosePanel = { showLiveScreen = false },
                browserController = browserController,
                browserTabs = browserTabs,
                activeTabId = activeTabId,
                accessibilityConnected = accessibilityConnected,
                activeWindowPackage = activeWindowPackage,
                liveNodes = liveNodes,
                interventionNote = interventionNote,
                onInterventionNoteChange = { interventionNote = it },
                onResolveIntervention = { onResolveIntervention(interventionNote) },
                onStopAgent = onStop,
                onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                onOpenExternalApp = onOpenExternalApp,
                onInspectScreen = onInspectDeviceScreen,
                onQuickVirtualHand = onQuickVirtualHand
            )
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
                                "Add a Gemini or OpenRouter API key to unlock natural language planning (Auto model selection)."
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

        // Chat / Execution Feed List
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
                            text = "Autonomous Agent Box (Bangla & English)",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "ফাইল আপলোড করুন, Skills বেছে নিন অথবা নির্দেশ দিন। Virtual Hands, লাইভ ব্রাউজার, Python/Shell ব্যবহার করে এজেন্ট কাজ করবে — এবং কোথাও OTP বা CAPTCHA এলে লাইভ স্ক্রিন সামনে চলে আসবে।",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(10.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(quickPrompts) { sample ->
                                AssistChip(
                                    onClick = { inputText = sample },
                                    label = { Text(sample.take(40)) }
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

        // Attached Uploaded Files Preview Row above Composer
        if (pendingAttachments.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(pendingAttachments, key = { it.id }) { att ->
                    InputChip(
                        selected = true,
                        onClick = {},
                        label = { Text("${att.displayName} (${formatBytes(att.sizeBytes)})", maxLines = 1) },
                        leadingIcon = { Icon(Icons.Filled.AttachFile, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        trailingIcon = {
                            IconButton(
                                onClick = { onRemoveAttachment(att.id) },
                                modifier = Modifier.size(18.dp)
                            ) {
                                Icon(Icons.Filled.Close, contentDescription = "Remove file", modifier = Modifier.size(14.dp))
                            }
                        }
                    )
                }
            }
        }

        // Input Composer Bar with File Upload + Voice + Skills + Send
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            IconButton(
                onClick = { filePickerLauncher.launch(arrayOf("*/*")) },
                modifier = Modifier.testTag("agent_attach_file_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.AttachFile,
                    contentDescription = "Upload files to Agent Box",
                    tint = NeuralCyan
                )
            }

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
                placeholder = { Text("বাংলা বা English কমান্ড লিখুন...") },
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
                    if (inputText.isNotBlank() || pendingAttachments.isNotEmpty()) {
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

    if (showSkillsSheet) {
        ModalBottomSheet(onDismissRequest = { showSkillsSheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    "Agent Skills & Virtual Hands",
                    style = MaterialTheme.typography.titleMedium,
                    color = NeuralCyan
                )
                Text(
                    "যেকোনো স্কিল সিলেক্ট করলে এজেন্ট বক্সে কমান্ড তৈরি হয়ে যাবে (আপলোড করা ফাইলসহ ব্যবহার করা যাবে)।",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        FilterChip(
                            selected = selectedSkillCategory == null,
                            onClick = { selectedSkillCategory = null },
                            label = { Text("All") }
                        )
                    }
                    items(SkillCategory.entries) { cat ->
                        FilterChip(
                            selected = selectedSkillCategory == cat,
                            onClick = { selectedSkillCategory = cat },
                            label = { Text(cat.title) }
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                val filteredSkills = AgentSkillRegistry.skills.filter {
                    selectedSkillCategory == null || it.category == selectedSkillCategory
                }
                LazyColumn(
                    modifier = Modifier.height(340.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredSkills, key = { it.id }) { skill ->
                        GlassOsCard(
                            borderColor = if (skill.category == SkillCategory.VIRTUAL_HANDS) EmeraldPulse.copy(alpha = 0.6f)
                            else MaterialTheme.colorScheme.outline,
                            modifier = Modifier.testTag("skill_card_${skill.id}")
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(skill.title, style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        skill.subtitle,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                Button(
                                    onClick = {
                                        inputText = skill.promptTemplate
                                        showSkillsSheet = false
                                    }
                                ) {
                                    Text("Use")
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun LiveScreenAgentPanel(
    intervention: com.example.agent.HumanInterventionRequest?,
    liveScreenMode: String,
    onSelectMode: (String) -> Unit,
    onClosePanel: () -> Unit,
    browserController: BrowserController,
    browserTabs: List<BrowserTab>,
    activeTabId: String,
    accessibilityConnected: Boolean,
    activeWindowPackage: String,
    liveNodes: List<ObservedUiNode>,
    interventionNote: String,
    onInterventionNoteChange: (String) -> Unit,
    onResolveIntervention: () -> Unit,
    onStopAgent: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenExternalApp: (String?) -> Unit,
    onInspectScreen: () -> Unit,
    onQuickVirtualHand: (String) -> Unit
) {
    val activeTab = browserTabs.firstOrNull { it.id == activeTabId } ?: browserTabs.first()
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }

    GlassOsCard(
        modifier = Modifier
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .testTag("live_screen_panel"),
        borderColor = if (intervention != null) AmberWarning else NeuralCyan
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Monitor, contentDescription = null, tint = if (intervention != null) AmberWarning else NeuralCyan, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (intervention != null) "লাইভ স্ক্রিন: ${intervention.challengeType}" else "লাইভ স্ক্রিন মনিটর",
                    style = MaterialTheme.typography.titleSmall,
                    color = if (intervention != null) AmberWarning else NeuralCyan
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(
                    selected = liveScreenMode == "BROWSER",
                    onClick = { onSelectMode("BROWSER") },
                    label = { Text("Browser") }
                )
                FilterChip(
                    selected = liveScreenMode == "VIRTUAL_HANDS",
                    onClick = { onSelectMode("VIRTUAL_HANDS") },
                    label = { Text("Hands") }
                )
                if (intervention == null) {
                    IconButton(onClick = onClosePanel, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Filled.Close, contentDescription = "Hide live screen", modifier = Modifier.size(18.dp))
                    }
                }
            }
        }

        if (intervention != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "${intervention.reason}\n${intervention.instructionForUser}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = interventionNote,
                    onValueChange = onInterventionNoteChange,
                    placeholder = { Text("ঐচ্ছিক নোট/OTP হিন্ট লিখুন...") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = onResolveIntervention,
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldPulse),
                    modifier = Modifier.testTag("resume_after_solve_button")
                ) {
                    Text("সল্ভ সম্পন্ন")
                }
                OutlinedButton(onClick = onStopAgent) {
                    Text("Stop")
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        if (liveScreenMode == "BROWSER") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = activeTab.url,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Row {
                    IconButton(onClick = { webViewInstance?.goBack() }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", modifier = Modifier.size(16.dp))
                    }
                    IconButton(onClick = { webViewInstance?.reload() }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Reload", modifier = Modifier.size(16.dp))
                    }
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(230.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surface)
            ) {
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.builtInZoomControls = true
                            settings.displayZoomControls = false
                            webViewClient = object : WebViewClient() {
                                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                    if (url != null) browserController.updateActiveTabMeta(url = url, isLoading = true)
                                }
                                override fun onPageFinished(view: WebView?, url: String?) {
                                    browserController.updateActiveTabMeta(title = view?.title, url = url, isLoading = false, progress = 100)
                                }
                            }
                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                    browserController.updateActiveTabMeta(progress = newProgress)
                                }
                            }
                            browserController.attachWebView(this)
                            webViewInstance = this
                            loadUrl(activeTab.url)
                        }
                    },
                    update = { wv ->
                        browserController.attachWebView(wv)
                        webViewInstance = wv
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        } else {
            // Live Virtual Hands Monitor & External App Control
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (accessibilityConnected) "Virtual Hands Active • App: $activeWindowPackage" else "Virtual Hands Disabled",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (accessibilityConnected) EmeraldPulse else CoralAlert
                    )
                }
                if (!accessibilityConnected) {
                    OutlinedButton(onClick = onOpenAccessibilitySettings) {
                        Text("Enable")
                    }
                } else {
                    OutlinedButton(onClick = { onOpenExternalApp(intervention?.urlOrPackage ?: activeWindowPackage) }) {
                        Icon(Icons.AutoMirrored.Filled.Launch, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("অ্যাপে যান")
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item {
                    AssistChip(onClick = onInspectScreen, leadingIcon = { Icon(Icons.Filled.TouchApp, null, Modifier.size(14.dp)) }, label = { Text("Inspect") })
                }
                item {
                    AssistChip(onClick = { onQuickVirtualHand("scroll_down") }, leadingIcon = { Icon(Icons.Filled.PanTool, null, Modifier.size(14.dp)) }, label = { Text("Scroll Down") })
                }
                item {
                    AssistChip(onClick = { onQuickVirtualHand("back") }, label = { Text("Back") })
                }
                item {
                    AssistChip(onClick = { onQuickVirtualHand("home") }, label = { Text("Home") })
                }
            }
            Spacer(Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                    .padding(8.dp)
            ) {
                if (liveNodes.isEmpty()) {
                    Text(
                        "অন্য অ্যাপের লাইভ এলিমেন্ট এখানে দেখা যাবে। স্ক্রিন পড়তে 'Inspect' চাপুন অথবা Virtual Hands কমান্ড চালান।",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(liveNodes.take(20)) { node ->
                            val label = node.text.ifBlank { node.contentDesc }.ifBlank { node.viewId }.ifBlank { node.className }
                            Text(
                                text = "• [${node.className}] $label (${node.centerX.toInt()},${node.centerY.toInt()})",
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
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
        FeedEntryKind.HUMAN_INTERVENTION -> Triple("Live Screen Handoff", AmberWarning, Icons.Filled.Monitor)
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
