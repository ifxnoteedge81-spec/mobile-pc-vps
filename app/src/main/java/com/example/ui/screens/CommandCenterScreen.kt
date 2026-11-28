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
import android.webkit.ValueCallback
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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.isImeVisible
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Monitor
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import com.example.storage.ImportedSkillEntity
import com.example.voice.VoiceRuntimeState
import com.example.ui.components.GlassOsCard
import com.example.ui.components.StatusBadge
import com.example.ui.components.WebViewUploadChooserDialog
import com.example.ui.components.configureAgentWebView
import com.example.ui.components.flushWebViewCookies
import com.example.ui.components.formatBytes
import com.example.ui.components.resolveWorkspaceShareUri
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CoralAlert
import com.example.ui.theme.EmeraldPulse
import com.example.ui.theme.NeuralCyan
import com.example.ui.theme.VioletAccent

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CommandCenterScreen(
    agentState: AgentRuntimeState,
    activeModelLabel: String,
    isOnline: Boolean,
    hasAnyApiKey: Boolean,
    pendingAttachments: List<AgentUploadedAttachment>,
    importedSkills: List<ImportedSkillEntity>,
    skillImportProgress: String? = null,
    browserController: BrowserController,
    browserTabs: List<BrowserTab>,
    activeTabId: String,
    browserActivitySignal: Long,
    accessibilityConnected: Boolean,
    activeWindowPackage: String,
    liveNodes: List<ObservedUiNode>,
    voiceState: VoiceRuntimeState = VoiceRuntimeState(),
    voiceLockEnabled: Boolean = true,
    voiceMatchThreshold: Float = 0.78f,
    voiceSpokenFeedback: Boolean = true,
    onAttachFiles: (List<Uri>) -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onAbsorbAttachmentsAsSkills: () -> Unit,
    onImportSkillFiles: (List<Uri>) -> Unit,
    onImportSkillSource: (String) -> Unit,
    onToggleImportedSkill: (String, Boolean) -> Unit,
    onDeleteImportedSkill: (String) -> Unit,
    onClearAllImportedSkills: () -> Unit = {},
    onSearchSkillsLocal: (String) -> List<ImportedSkillEntity> = { importedSkills.take(80) },
    onEnrollVoice: () -> Unit = {},
    onTestVoiceMatch: () -> Unit = {},
    onClearVoice: () -> Unit = {},
    onToggleHandsFree: (Boolean) -> Unit = {},
    onUpdateVoicePrefs: (Boolean?, Float?, Boolean?) -> Unit = { _, _, _ -> },
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
    onOpenModelSettings: () -> Unit,
    miniAiStats: com.example.ai.MiniAiBrainStats = com.example.ai.MiniAiBrainStats()
) {
    val context = LocalContext.current
    var inputText by remember { mutableStateOf("") }
    var isListening by remember { mutableStateOf(false) }
    var voiceStatus by remember { mutableStateOf<String?>(null) }
    var showLiveScreen by remember { mutableStateOf(false) }
    var liveScreenMode by remember { mutableStateOf("BROWSER") } // "BROWSER" or "VIRTUAL_HANDS"
    var showSkillsSheet by remember { mutableStateOf(false) }
    var showVoiceSheet by remember { mutableStateOf(false) }
    var showImportedOnly by remember { mutableStateOf(false) }
    var selectedSkillCategory by remember { mutableStateOf<SkillCategory?>(null) }
    var skillImportInput by remember { mutableStateOf("") }
    var skillSearchQuery by remember { mutableStateOf("") }
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
    LaunchedEffect(browserActivitySignal) {
        if (browserActivitySignal > 0L) {
            showLiveScreen = true
            liveScreenMode = "BROWSER"
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            onAttachFiles(uris)
        }
    }

    val skillFilePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            onImportSkillFiles(uris)
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

    var pendingVoiceGuardAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val voiceGuardMicLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            pendingVoiceGuardAction?.invoke()
        } else {
            voiceStatus = "ভয়েস ট্রেইনিং ও হ্যান্ডস-ফ্রি চালানোর জন্য মাইক্রোফোন পারমিশন দিন।"
        }
        pendingVoiceGuardAction = null
    }

    fun withVoiceGuardMicPermission(action: () -> Unit) {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) action()
        else {
            pendingVoiceGuardAction = action
            voiceGuardMicLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    val isImeVisible = WindowInsets.isImeVisible
    LaunchedEffect(agentState.feed.size, isImeVisible) {
        if (agentState.feed.isNotEmpty()) {
            listState.animateScrollToItem(agentState.feed.lastIndex)
        }
    }

    val quickPrompts = listOf(
        "ইউনিভার্সাল বাংলা প্রম্পট (সব কাজের জন্য)" to com.example.ai.LocalMiniAiBrain.buildUniversalBanglaPrompt(),
        "/py print('Hello from on-device Python!')" to "/py print('Hello from on-device Python!')",
        "/sh ls -la" to "/sh ls -la",
        "আপলোড করা ফাইলগুলোর সারসংক্ষেপ" to "আমার আপলোড করা ফাইলগুলো পড়ে সারসংক্ষেপ দাও।",
        "Virtual Hands দিয়ে অ্যাপ নিয়ন্ত্রণ" to "Virtual Hands দিয়ে ফোনের অ্যাপ ওপেন করে স্ক্রিন দেখে কাজ করো।",
        "ব্রাউজার + OTP/CAPTCHA লাইভ স্ক্রিন" to "ব্রাউজারে কাজটি শুরু করো এবং OTP বা CAPTCHA এলে আমাকে লাইভ স্ক্রিন দেখাও।"
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
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                item {
                    FilterChip(
                        selected = showLiveScreen,
                        onClick = { showLiveScreen = !showLiveScreen },
                        leadingIcon = { Icon(Icons.Filled.Monitor, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        label = { Text(if (showLiveScreen) "লাইভ স্ক্রিন চালু" else "লাইভ স্ক্রিন") },
                        modifier = Modifier.testTag("toggle_live_screen_chip")
                    )
                }
                item {
                    AssistChip(
                        onClick = { showSkillsSheet = true },
                        leadingIcon = { Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        label = {
                            Text(
                                if (importedSkills.isEmpty()) "Skills & Auto-Import"
                                else "Skills (${importedSkills.size} Auto)"
                            )
                        },
                        modifier = Modifier.testTag("open_skills_sheet_chip")
                    )
                }
                item {
                    FilterChip(
                        selected = voiceState.isHandsFreeActive || voiceState.enrolledSampleCount > 0,
                        onClick = { showVoiceSheet = true },
                        leadingIcon = {
                            Icon(
                                Icons.Filled.Mic,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = if (voiceState.isHandsFreeActive) EmeraldPulse else MaterialTheme.colorScheme.primary
                            )
                        },
                        label = {
                            Text(
                                when {
                                    voiceState.isHandsFreeActive -> "Hands-Free ON"
                                    voiceState.enrolledSampleCount > 0 -> "Voice Guard (${voiceState.enrolledSampleCount})"
                                    else -> "Voice Guard"
                                }
                            )
                        },
                        modifier = Modifier.testTag("open_voice_guard_chip")
                    )
                }
                if (!accessibilityConnected) {
                    item {
                        AssistChip(
                            onClick = onOpenAccessibilitySettings,
                            leadingIcon = { Icon(Icons.Filled.TouchApp, contentDescription = null, modifier = Modifier.size(16.dp), tint = AmberWarning) },
                            label = { Text("Virtual Hands চালু করুন") },
                            modifier = Modifier.testTag("enable_virtual_hands_chip")
                        )
                    }
                }
                if (agentState.feed.isNotEmpty() && !agentState.isBusy) {
                    item {
                        AssistChip(
                            onClick = onClearFeed,
                            leadingIcon = { Icon(Icons.Filled.ClearAll, contentDescription = null, modifier = Modifier.size(16.dp)) },
                            label = { Text("Clear") },
                            modifier = Modifier.testTag("clear_feed_chip")
                        )
                    }
                }
                item {
                    AssistChip(
                        onClick = onOpenModelSettings,
                        leadingIcon = { Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp), tint = EmeraldPulse) },
                        label = { Text("Mini AI (${miniAiStats.learnedSamples} • ${miniAiStats.estimatedTokensSaved}t saved)") },
                        modifier = Modifier.testTag("mini_ai_status_chip")
                    )
                }
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

        // Embedded Live Screen Inside Agent Box (Auto-pops on OTP / CAPTCHA / Account Input)
        AnimatedVisibility(visible = (showLiveScreen && (!isImeVisible || intervention != null)) || intervention != null) {
            LiveScreenAgentPanel(
                intervention = intervention,
                isCompactForKeyboard = isImeVisible,
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
                            items(quickPrompts) { (labelText, promptBody) ->
                                AssistChip(
                                    onClick = { inputText = promptBody },
                                    label = { Text(labelText.take(42)) }
                                )
                            }
                        }
                    }
                }
            } else {
                items(agentState.feed, key = { it.id }) { item ->
                    FeedItemCard(
                        item = item,
                        onOpenPreviewUrl = { url ->
                            browserController.openUrlInActiveOrNewTab(url)
                            showLiveScreen = true
                            liveScreenMode = "BROWSER"
                        },
                        onShareWorkspaceFile = { relPath ->
                            val shareUri = resolveWorkspaceShareUri(context, relPath)
                            if (shareUri != null) {
                                val fileName = relPath.substringAfterLast('/')
                                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "*/*"
                                    putExtra(Intent.EXTRA_STREAM, shareUri)
                                    putExtra(Intent.EXTRA_SUBJECT, fileName)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(
                                    Intent.createChooser(sendIntent, "Share $fileName")
                                )
                            }
                        }
                    )
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

        // Attached Uploaded Files Preview Row above Composer (+ 1-tap Auto-Skill Absorb)
        if (pendingAttachments.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                item(key = "absorb_skill_chip") {
                    AssistChip(
                        onClick = onAbsorbAttachmentsAsSkills,
                        leadingIcon = { Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp), tint = EmeraldPulse) },
                        label = { Text("অটো-স্কিল যুক্ত করুন") },
                        modifier = Modifier.testTag("absorb_attachments_skill_chip")
                    )
                }
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
                placeholder = { Text("বাংলা / English কমান্ড লিখুন...", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                maxLines = 4,
                modifier = Modifier
                    .weight(1f)
                    .testTag("command_input_field")
            )

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
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            ) {
                Text(
                    "Universal Auto-Skill Adapter & Virtual Hands",
                    style = MaterialTheme.typography.titleMedium,
                    color = NeuralCyan
                )
                Text(
                    "পুরো GitHub Repo লিংক (যেমন VoltAgent 1000+), রেপোর ডাউনলোড করা .zip ফাইল, বা একাধিক লিংক একবারে দিন — সব স্কিল অটো আলাদা হয়ে পুশ হবে এবং কাজের সময় AI ও বট সেরা স্কিল অটো খুঁজে ব্যবহার করবে (০ টোকেন সার্চ)।",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))

                // Universal Bulk Auto-Importer Card
                GlassOsCard(borderColor = NeuralCyan.copy(alpha = 0.55f)) {
                    OutlinedTextField(
                        value = skillImportInput,
                        onValueChange = { skillImportInput = it },
                        placeholder = { Text("GitHub Repo/Awesome URL, একাধিক লিংক (লাইন বাই লাইন), বা ZIP URL...") },
                        maxLines = 3,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("skill_import_source_input")
                    )
                    if (!skillImportProgress.isNullOrBlank()) {
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = skillImportProgress,
                            style = MaterialTheme.typography.labelSmall,
                            color = NeuralCyan
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = { skillFilePickerLauncher.launch(arrayOf("*/*")) },
                            enabled = skillImportProgress == null,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("import_skill_file_button")
                        ) {
                            Icon(Icons.Filled.AttachFile, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("File / ZIP পিক")
                        }
                        Button(
                            onClick = {
                                if (skillImportInput.isNotBlank()) {
                                    onImportSkillSource(skillImportInput)
                                    skillImportInput = ""
                                }
                            },
                            enabled = skillImportInput.isNotBlank() && skillImportProgress == null,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("import_skill_source_button")
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("অটো ইম্পোর্ট")
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        item {
                            AssistChip(
                                onClick = {
                                    if (skillImportProgress == null) {
                                        onImportSkillSource("https://github.com/VoltAgent/awesome-agent-skills")
                                    }
                                },
                                label = { Text("1-Tap: VoltAgent 1000+ Pack") }
                            )
                        }
                        item {
                            AssistChip(
                                onClick = {
                                    if (skillImportProgress == null) {
                                        onImportSkillSource("https://github.com/anthropics/skills")
                                    }
                                },
                                label = { Text("1-Tap: Anthropic Official Pack") }
                            )
                        }
                    }
                }

                if (importedSkills.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = skillSearchQuery,
                            onValueChange = { skillSearchQuery = it },
                            placeholder = { Text("স্কিল খুঁজুন (${importedSkills.size}টির মধ্যে): pdf, browser, email, form...") },
                            singleLine = true,
                            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = "Search skills") },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("skill_search_query_input")
                        )
                        TextButton(
                            onClick = onClearAllImportedSkills,
                            modifier = Modifier.testTag("clear_all_imported_skills_button")
                        ) {
                            Text("সব মুছুন", color = CoralAlert)
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        FilterChip(
                            selected = !showImportedOnly && selectedSkillCategory == null,
                            onClick = {
                                showImportedOnly = false
                                selectedSkillCategory = null
                            },
                            label = { Text("All") }
                        )
                    }
                    item {
                        FilterChip(
                            selected = showImportedOnly,
                            onClick = {
                                showImportedOnly = true
                                selectedSkillCategory = null
                            },
                            label = { Text("Auto-Imported (${importedSkills.size})") },
                            modifier = Modifier.testTag("filter_imported_skills_chip")
                        )
                    }
                    items(SkillCategory.entries) { cat ->
                        FilterChip(
                            selected = !showImportedOnly && selectedSkillCategory == cat,
                            onClick = {
                                showImportedOnly = false
                                selectedSkillCategory = cat
                            },
                            label = { Text(cat.title) }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))

                val filteredBuiltIn = if (showImportedOnly) emptyList()
                else AgentSkillRegistry.skills.filter {
                    val catMatch = selectedSkillCategory == null || it.category == selectedSkillCategory
                    val q = skillSearchQuery.trim()
                    val qMatch = q.isBlank() || it.title.contains(q, true) || it.subtitle.contains(q, true)
                    catMatch && qMatch
                }
                val visibleImported = remember(importedSkills, skillSearchQuery, showImportedOnly, selectedSkillCategory) {
                    if (showImportedOnly || selectedSkillCategory == null) {
                        onSearchSkillsLocal(skillSearchQuery)
                    } else {
                        emptyList()
                    }
                }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 160.dp, max = 310.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (visibleImported.isNotEmpty()) {
                        items(visibleImported, key = { "imp_${it.slug}" }) { imp ->
                            GlassOsCard(
                                borderColor = if (imp.enabled) NeuralCyan.copy(alpha = 0.65f)
                                else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                                modifier = Modifier.testTag("imported_skill_card_${imp.slug}")
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Text(
                                                text = imp.title,
                                                style = MaterialTheme.typography.titleSmall,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f, fill = false)
                                            )
                                            StatusBadge(label = imp.ecosystem, color = VioletAccent)
                                        }
                                        Text(
                                            text = "${imp.wordCount} words • ${imp.lineCount} lines • ${imp.description}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Spacer(Modifier.width(6.dp))
                                    Switch(
                                        checked = imp.enabled,
                                        onCheckedChange = { onToggleImportedSkill(imp.slug, it) }
                                    )
                                }
                                Spacer(Modifier.height(6.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    IconButton(
                                        onClick = { onDeleteImportedSkill(imp.slug) },
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(
                                            Icons.Filled.Delete,
                                            contentDescription = "Delete imported skill",
                                            tint = CoralAlert
                                        )
                                    }
                                    Spacer(Modifier.width(6.dp))
                                    Button(
                                        onClick = {
                                            inputText = "Use imported skill '${imp.slug}' (${imp.title}): "
                                            showSkillsSheet = false
                                        }
                                    ) {
                                        Text("Use")
                                    }
                                }
                            }
                        }
                    }

                    if (showImportedOnly && visibleImported.isEmpty()) {
                        item {
                            GlassOsCard {
                                Text(
                                    "এখনো কোনো এক্সটার্নাল স্কিল ইম্পোর্ট করা হয়নি। উপরে File/ZIP পিক করুন অথবা GitHub/Skill টেক্সট দিন — যেকোনো এজেন্টের স্কিল অটোমেটিক কনফিগার হয়ে যাবে।",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    items(filteredBuiltIn, key = { it.id }) { skill ->
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
                Spacer(Modifier.height(14.dp))
            }
        }
    }

    if (showVoiceSheet) {
        ModalBottomSheet(onDismissRequest = { showVoiceSheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Owner Voice Training & Hands-Free Guard",
                            style = MaterialTheme.typography.titleMedium,
                            color = NeuralCyan
                        )
                        Text(
                            text = "আপনার কণ্ঠস্বর ট্রেইন করুন — লক চালু থাকলে অন্য কেউ বললেও AI কম্পিউটার কাজ করবে না।",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    StatusBadge(
                        label = "${voiceState.enrolledSampleCount}/3 Samples",
                        color = if (voiceState.enrolledSampleCount > 0) EmeraldPulse else AmberWarning
                    )
                }

                GlassOsCard(
                    borderColor = when (voiceState.lastMatchAccepted) {
                        true -> EmeraldPulse.copy(alpha = 0.6f)
                        false -> CoralAlert.copy(alpha = 0.6f)
                        null -> NeuralCyan.copy(alpha = 0.45f)
                    }
                ) {
                    Text(
                        text = voiceState.statusMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (voiceState.isEnrolling || voiceState.isTestingMatch || voiceState.isCapturingSpeech) {
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    voiceState.lastMatchScorePercent?.let { pct ->
                        Spacer(Modifier.height(6.dp))
                        StatusBadge(
                            label = "Last Match Score: $pct% (${if (voiceState.lastMatchAccepted == true) "OWNER VERIFIED" else "REJECTED"})",
                            color = if (voiceState.lastMatchAccepted == true) EmeraldPulse else CoralAlert
                        )
                    }
                    if (voiceState.lastTranscript.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Last Command: \"${voiceState.lastTranscript}\"",
                            style = MaterialTheme.typography.labelSmall,
                            color = NeuralCyan
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { withVoiceGuardMicPermission { onEnrollVoice() } },
                        enabled = !voiceState.isEnrolling && !voiceState.isTestingMatch,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("voice_enroll_button")
                    ) {
                        Icon(Icons.Filled.Mic, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (voiceState.isEnrolling) "Recording..." else "Train Voice")
                    }
                    OutlinedButton(
                        onClick = { withVoiceGuardMicPermission { onTestVoiceMatch() } },
                        enabled = voiceState.enrolledSampleCount > 0 && !voiceState.isEnrolling && !voiceState.isTestingMatch,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("voice_test_match_button")
                    ) {
                        Icon(Icons.Filled.Verified, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (voiceState.isTestingMatch) "Testing..." else "Test Match %")
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Hands-Free Continuous Voice Mode", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Switch(
                        checked = voiceState.isHandsFreeActive,
                        onCheckedChange = { enable ->
                            if (enable) withVoiceGuardMicPermission { onToggleHandsFree(true) }
                            else onToggleHandsFree(false)
                        },
                        modifier = Modifier.testTag("voice_hands_free_switch")
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Owner Voiceprint Lock (শুধু আপনার কণ্ঠ)", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Switch(
                        checked = voiceLockEnabled,
                        onCheckedChange = { onUpdateVoicePrefs(it, null, null) },
                        modifier = Modifier.testTag("voice_lock_switch")
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Speak Reply Back (Bangla/English TTS)", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Switch(
                        checked = voiceSpokenFeedback,
                        onCheckedChange = { onUpdateVoicePrefs(null, null, it) },
                        modifier = Modifier.testTag("voice_tts_switch")
                    )
                }

                Column {
                    Text(
                        text = "Voice Match Sensitivity Threshold: ${(voiceMatchThreshold * 100).toInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Slider(
                        value = voiceMatchThreshold,
                        onValueChange = { onUpdateVoicePrefs(null, it, null) },
                        valueRange = 0.65f..0.90f
                    )
                }

                if (voiceState.enrolledSampleCount > 0) {
                    TextButton(
                        onClick = onClearVoice,
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(16.dp), tint = CoralAlert)
                        Spacer(Modifier.width(4.dp))
                        Text("Reset Voiceprint", color = CoralAlert)
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun LiveScreenAgentPanel(
    intervention: com.example.agent.HumanInterventionRequest?,
    isCompactForKeyboard: Boolean = false,
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
    var lastSyncedTabId by remember { mutableStateOf(activeTab.id) }
    var lastSyncedSeq by remember { mutableStateOf(activeTab.navigationSeq) }
    var pendingFileCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    var pendingAcceptTypes by remember { mutableStateOf(arrayOf("*/*")) }

    val liveUploadLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        pendingFileCallback?.onReceiveValue(if (uris.isNotEmpty()) uris.toTypedArray() else null)
        pendingFileCallback = null
    }

    DisposableEffect(webViewInstance) {
        onDispose {
            flushWebViewCookies()
            webViewInstance?.let { browserController.detachWebView(it) }
            pendingFileCallback?.onReceiveValue(null)
            pendingFileCallback = null
        }
    }

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
                    placeholder = { Text("OTP, ফোন নম্বর, পাসওয়ার্ড বা নোট লিখুন...") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = onResolveIntervention,
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldPulse),
                    modifier = Modifier.testTag("resume_after_solve_button")
                ) {
                    Text("জমা দিন")
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
                    .height(if (isCompactForKeyboard) 125.dp else 230.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surface)
            ) {
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            configureAgentWebView(this)
                            webViewClient = object : WebViewClient() {
                                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                    if (url != null) browserController.updateActiveTabMeta(url = url, isLoading = true)
                                }
                                override fun onPageFinished(view: WebView?, url: String?) {
                                    flushWebViewCookies()
                                    browserController.updateActiveTabMeta(title = view?.title, url = url, isLoading = false, progress = 100)
                                }
                            }
                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                    browserController.updateActiveTabMeta(progress = newProgress)
                                }
                                override fun onShowFileChooser(
                                    webView: WebView?,
                                    filePathCallback: ValueCallback<Array<Uri>>?,
                                    fileChooserParams: FileChooserParams?
                                ): Boolean {
                                    pendingFileCallback?.onReceiveValue(null)
                                    pendingFileCallback = filePathCallback
                                    val accepts = fileChooserParams?.acceptTypes
                                        ?.map { it.trim() }
                                        ?.filter { it.isNotBlank() && it.contains("/") }
                                        ?.toTypedArray()
                                    pendingAcceptTypes = if (accepts != null && accepts.isNotEmpty()) accepts else arrayOf("*/*")
                                    return true
                                }
                            }
                            browserController.attachWebView(this)
                            webViewInstance = this
                            lastSyncedTabId = activeTab.id
                            lastSyncedSeq = activeTab.navigationSeq
                            loadUrl(activeTab.url)
                        }
                    },
                    update = { wv ->
                        browserController.attachWebView(wv)
                        webViewInstance = wv
                        if (lastSyncedTabId != activeTab.id || lastSyncedSeq != activeTab.navigationSeq) {
                            lastSyncedTabId = activeTab.id
                            lastSyncedSeq = activeTab.navigationSeq
                            if (wv.url != activeTab.url) {
                                wv.loadUrl(activeTab.url)
                            }
                        }
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
                    .height(if (isCompactForKeyboard) 85.dp else 140.dp)
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

    WebViewUploadChooserDialog(
        pendingCallback = pendingFileCallback,
        onPickFromPhone = { liveUploadLauncher.launch(pendingAcceptTypes) },
        onDeliverWorkspaceUris = { uris ->
            pendingFileCallback?.onReceiveValue(uris)
            pendingFileCallback = null
        }
    )
}

@Composable
private fun FeedItemCard(
    item: CommandFeedItem,
    onOpenPreviewUrl: (String) -> Unit = {},
    onShareWorkspaceFile: (String) -> Unit = {}
) {
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
        if (item.actionUrl != null || item.shareRelativePath != null) {
            Spacer(Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item.actionUrl?.let { url ->
                    item(key = "preview_url") {
                        AssistChip(
                            onClick = { onOpenPreviewUrl(url) },
                            leadingIcon = { Icon(Icons.Filled.Monitor, contentDescription = null, modifier = Modifier.size(16.dp), tint = NeuralCyan) },
                            label = { Text("Open Blueprint Viewer") },
                            modifier = Modifier.testTag("cad_preview_button")
                        )
                    }
                }
                item.shareRelativePath?.let { rel ->
                    item(key = "share_primary") {
                        AssistChip(
                            onClick = { onShareWorkspaceFile(rel) },
                            leadingIcon = { Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(16.dp), tint = EmeraldPulse) },
                            label = { Text("Share ${rel.substringAfterLast('.').uppercase()}") },
                            modifier = Modifier.testTag("cad_share_primary_button")
                        )
                    }
                }
                item.shareSecondaryPath?.let { rel2 ->
                    item(key = "share_secondary") {
                        AssistChip(
                            onClick = { onShareWorkspaceFile(rel2) },
                            leadingIcon = { Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(16.dp), tint = VioletAccent) },
                            label = { Text("Share ${rel2.substringAfterLast('.').uppercase()}") },
                            modifier = Modifier.testTag("cad_share_secondary_button")
                        )
                    }
                }
            }
        }
    }
}
