package com.example.agent

import com.example.ai.AiMessage
import com.example.ai.AiModelRouter
import com.example.ai.TaskComplexity
import com.example.resources.DeviceResourceManager
import com.example.runtime.LocalLinuxEnvironment
import com.example.security.PermissionLevel
import com.example.storage.AgentRepository
import com.example.storage.AppSettings
import com.example.storage.TaskStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject

enum class FeedEntryKind {
    USER_COMMAND,
    AI_REASONING,
    VERIFIED_TOOL_RESULT,
    PERMISSION_REQUEST,
    HUMAN_INTERVENTION,
    ERROR_NOTICE
}

data class CommandFeedItem(
    val id: Long = System.nanoTime(),
    val kind: FeedEntryKind,
    val title: String,
    val body: String,
    val timestamp: Long = System.currentTimeMillis()
)

data class AgentRuntimeState(
    val activeTaskId: Long? = null,
    val taskStatus: String = TaskStatus.PENDING,
    val isBusy: Boolean = false,
    val isPaused: Boolean = false,
    val currentPhaseLabel: String = "Idle • Ready for command",
    val activeModelLabel: String = "",
    val feed: List<CommandFeedItem> = emptyList(),
    val pendingPermission: PendingPermissionRequest? = null,
    val pendingIntervention: HumanInterventionRequest? = null,
    val streamingBuffer: String = ""
)

class AutonomousAgentEngine(
    private val scope: CoroutineScope,
    private val router: AiModelRouter,
    private val toolRegistry: ToolRegistry,
    private val repository: AgentRepository,
    private val linuxEnv: LocalLinuxEnvironment,
    private val resourceManager: DeviceResourceManager,
    private val settingsFlow: kotlinx.coroutines.flow.Flow<AppSettings>,
    private val onInterventionAlert: (HumanInterventionRequest) -> Unit = {}
) {
    private val _state = MutableStateFlow(AgentRuntimeState())
    val state: StateFlow<AgentRuntimeState> = _state.asStateFlow()

    private var activeJob: Job? = null
    private var permissionDeferred: CompletableDeferred<Boolean>? = null
    private var interventionDeferred: CompletableDeferred<String>? = null

    fun submitCommand(
        userPrompt: String,
        attachments: List<AgentUploadedAttachment> = emptyList()
    ) {
        val trimmed = userPrompt.trim()
        if (trimmed.isEmpty() && attachments.isEmpty()) return

        // Support instant local slash commands even when completely offline
        if (attachments.isEmpty() && (trimmed.startsWith("/sh ") || trimmed.startsWith("/py "))) {
            runDirectLocalShortcut(trimmed)
            return
        }

        activeJob?.cancel()
        permissionDeferred?.complete(false)
        permissionDeferred = null
        interventionDeferred?.complete("CANCELLED")
        interventionDeferred = null

        activeJob = scope.launch(Dispatchers.IO) {
            runAgentCycle(trimmed, attachments)
        }
    }

    fun pauseTask() {
        _state.update { it.copy(isPaused = true, taskStatus = TaskStatus.PAUSED, currentPhaseLabel = "Paused by user") }
    }

    fun resumeTask() {
        _state.update { it.copy(isPaused = false, taskStatus = TaskStatus.RUNNING, currentPhaseLabel = "Resuming execution...") }
    }

    fun stopTask() {
        activeJob?.cancel()
        permissionDeferred?.complete(false)
        permissionDeferred = null
        interventionDeferred?.complete("CANCELLED")
        interventionDeferred = null
        val id = _state.value.activeTaskId
        if (id != null) {
            scope.launch(Dispatchers.IO) {
                repository.getTask(id)?.let {
                    repository.updateTask(it.copy(status = TaskStatus.CANCELLED, finalReport = "Cancelled by user"))
                    repository.appendLog(id, 99, "STATUS", "Cancelled", "Task cancelled by user")
                }
            }
        }
        _state.update {
            it.copy(
                isBusy = false,
                isPaused = false,
                taskStatus = TaskStatus.CANCELLED,
                pendingPermission = null,
                pendingIntervention = null,
                currentPhaseLabel = "Cancelled",
                streamingBuffer = ""
            )
        }
    }

    fun resolvePermission(approved: Boolean) {
        permissionDeferred?.complete(approved)
        permissionDeferred = null
        _state.update { it.copy(pendingPermission = null) }
    }

    fun resolveHumanIntervention(userNote: String = "") {
        val note = userNote.trim().ifBlank {
            "User solved the CAPTCHA / OTP / verification challenge on the Live Screen. Inspect updated page/screen and continue from this step."
        }
        interventionDeferred?.complete(note)
        interventionDeferred = null
        _state.update {
            it.copy(
                pendingIntervention = null,
                taskStatus = TaskStatus.RUNNING,
                currentPhaseLabel = "Resuming from human-solved step..."
            )
        }
    }

    fun clearFeed() {
        _state.update { it.copy(feed = emptyList(), streamingBuffer = "") }
    }

    private fun runDirectLocalShortcut(cmd: String) {
        scope.launch(Dispatchers.IO) {
            appendFeed(FeedEntryKind.USER_COMMAND, "Local Direct Command", cmd)
            val isPy = cmd.startsWith("/py ")
            val tool = if (isPy) "python_exec" else "shell_exec"
            val args = if (isPy) JSONObject().put("code", cmd.removePrefix("/py ").trim())
            else JSONObject().put("command", cmd.removePrefix("/sh ").trim())

            val perm = toolRegistry.evaluatePermission(tool, args)
            if (perm != null && perm.level == PermissionLevel.RESTRICTED) {
                appendFeed(FeedEntryKind.ERROR_NOTICE, "Blocked (Level 3)", perm.summary)
                return@launch
            }
            val approved = if (perm != null) awaitUserPermission(perm) else true
            if (!approved) {
                appendFeed(FeedEntryKind.ERROR_NOTICE, "Denied by User", perm?.summary ?: "Cancelled")
                return@launch
            }
            val out = toolRegistry.execute(tool, args, approvedLevel2 = true)
            appendFeed(FeedEntryKind.VERIFIED_TOOL_RESULT, "Verified: $tool", out.output)
        }
    }

    private suspend fun runAgentCycle(
        userPrompt: String,
        attachments: List<AgentUploadedAttachment>
    ) {
        val settings = settingsFlow.first()
        val resources = resourceManager.refresh(settings)
        val modelLabel = router.getActiveLabel(settings)

        val displayUserText = buildString {
            append(userPrompt.ifBlank { "Analyze attached files." })
            if (attachments.isNotEmpty()) {
                append("\n\n[Attached Files: ${attachments.joinToString(", ") { it.displayName }}]")
            }
        }
        val enrichedPrompt = buildString {
            append(userPrompt.ifBlank { "Please inspect and analyze the uploaded files below." })
            if (attachments.isNotEmpty()) {
                append("\n\n=== USER UPLOADED FILES IN virtual_env/ ===")
                attachments.forEach { att ->
                    append("\n- Name: ${att.displayName} | Path: ${att.relativePath} | Type: ${att.mimeType} | Size: ${att.sizeBytes}B")
                    if (!att.textExcerpt.isNullOrBlank()) {
                        append("\n  Content Preview:\n  ${att.textExcerpt.take(2500)}")
                    }
                }
            }
        }

        appendFeed(FeedEntryKind.USER_COMMAND, "User Command", displayUserText)
        val taskId = repository.createTask(
            title = displayUserText.take(70),
            prompt = displayUserText,
            modelUsed = modelLabel
        )

        _state.update {
            it.copy(
                activeTaskId = taskId,
                taskStatus = TaskStatus.PLANNING,
                isBusy = true,
                isPaused = false,
                pendingIntervention = null,
                activeModelLabel = modelLabel,
                currentPhaseLabel = "Step 1: Analyzing objective, uploads & live API...",
                streamingBuffer = ""
            )
        }

        if (resources.warningMessage != null) {
            repository.appendLog(taskId, 0, "RESOURCE", "Resource Guard", resources.warningMessage)
        }

        val memories = repository.getMemorySnapshot()
        val memoryContext = if (memories.isEmpty()) "None saved yet."
        else memories.take(15).joinToString("\n") { "- [${it.category}] ${it.memoryKey}: ${it.content}" }

        val systemPrompt = """
            You are PERSONAL AI COMPUTER, an autonomous local-first Android AI Agent Engine inspired by OpenClaw.
            You control real on-device tools inside a sandboxed Linux workspace (virtual_env), an on-device Python & JS V8 runtime, an integrated Chromium WebView browser, and full Android Accessibility Virtual Hands (app_list, app_launch, device_screen_inspect, device_virtual_hand).

            CRITICAL RULES:
            1. Support both Bangla and English seamlessly. Reply in the same language the user used (Bangla or English).
            2. NEVER fabricate tool outputs, file creation, web searches, or command results. ALWAYS call the real tools and observe their actual outputs before reporting completion.
            3. Follow the 3-Tier Permission Policy:
               - Level 1 (Safe): workspace read/write (including uploaded files in uploads/), safe shell/python/js, public web browsing/reading, inspecting screen.
               - Level 2 (Confirmation): deleting files, filling forms, downloading files, Virtual Hand device gestures, launching apps, scheduling automations.
               - Level 3 (Restricted): NEVER bypass login, OTP, CAPTCHA, biometrics, banking, or execute destructive OS commands.
            4. HUMAN-IN-THE-LOOP PAUSE & RESUME:
               Whenever you encounter a CAPTCHA, OTP, 2FA, login gate, or any interactive check that you cannot or must not bypass yourself, IMMEDIATELY call `request_human_intervention` so the Live Screen pops up inside the user's Agent Chat Box. When the user resolves it and clicks Resume, inspect the updated browser/screen state and continue seamlessly from that exact step!
            5. Keep responses concise, structured, and actionable.

            Current Device State:
            - Available RAM: ${resources.availableRamMb} MB / ${resources.totalPhysicalRamMb} MB (Pressure: ${resources.memoryPressureLabel})
            - RAM Budget: ${settings.ramBudgetMb} MB | Max Concurrency: ${resources.effectiveConcurrency}
            - Accessibility Virtual Hands: ${if (com.example.accessibility.PersonalAiAccessibilityService.isConnected.value) "CONNECTED" else "NOT ENABLED"}

            Persistent Agent Memory:
            $memoryContext
        """.trimIndent()

        val conversation = mutableListOf(
            AiMessage(role = "user", content = enrichedPrompt)
        )
        val complexity = classifyComplexity(enrichedPrompt)
        val declarations = toolRegistry.getDeclarations()

        var step = 1
        var totalTokens = 0
        var finalText = ""

        try {
            val maxIterations = if (settings.lowResourceMode) 5 else 10
            while (step <= maxIterations) {
                waitWhilePaused()

                _state.update {
                    it.copy(
                        taskStatus = TaskStatus.RUNNING,
                        currentPhaseLabel = "Iteration $step: Selecting API model & planning...",
                        streamingBuffer = ""
                    )
                }

                val completionResult = router.complete(
                    settings = settings,
                    complexity = complexity,
                    systemPrompt = systemPrompt,
                    messages = conversation,
                    tools = declarations,
                    onStreamChunk = { chunk ->
                        _state.update { cur -> cur.copy(streamingBuffer = cur.streamingBuffer + chunk) }
                    }
                )

                if (completionResult.isFailure) {
                    val msg = completionResult.exceptionOrNull()?.message ?: "AI Provider Error"
                    repository.appendLog(taskId, step, "ERROR", "Provider Error", msg)
                    repository.getTask(taskId)?.let {
                        repository.updateTask(it.copy(status = TaskStatus.FAILED, finalReport = msg))
                    }
                    appendFeed(FeedEntryKind.ERROR_NOTICE, "AI Gateway Notice", msg)
                    _state.update {
                        it.copy(isBusy = false, taskStatus = TaskStatus.FAILED, currentPhaseLabel = "Failed", streamingBuffer = "")
                    }
                    return
                }

                val completion = completionResult.getOrThrow()
                totalTokens += completion.promptTokens + completion.completionTokens
                _state.update { it.copy(activeModelLabel = "${completion.provider} • ${completion.model}") }

                if (completion.text.isNotBlank() && completion.toolCalls.isNotEmpty()) {
                    repository.appendLog(taskId, step, "PLAN", "Agent Plan", completion.text)
                    appendFeed(FeedEntryKind.AI_REASONING, "Agent Plan (${completion.model})", completion.text)
                }

                if (completion.toolCalls.isEmpty()) {
                    finalText = completion.text.ifBlank { "Task completed." }
                    repository.appendLog(taskId, step, "AI_RESPONSE", "Final Report", finalText, verified = true)
                    appendFeed(FeedEntryKind.AI_REASONING, "Final Report (${completion.provider} • ${completion.model})", finalText)
                    break
                }

                conversation.add(
                    AiMessage(
                        role = "assistant",
                        content = completion.text,
                        toolCalls = completion.toolCalls
                    )
                )

                for (call in completion.toolCalls) {
                    waitWhilePaused()
                    _state.update {
                        it.copy(currentPhaseLabel = "Executing tool: ${call.name}")
                    }
                    repository.appendLog(taskId, step, "TOOL_CALL", call.name, call.arguments.toString())

                    val permReq = toolRegistry.evaluatePermission(call.name, call.arguments)
                    val outcome = if (permReq != null && permReq.level == PermissionLevel.RESTRICTED) {
                        ToolExecutionOutcome(call.name, false, permReq.summary, verified = true)
                    } else if (permReq != null) {
                        _state.update {
                            it.copy(
                                taskStatus = TaskStatus.WAITING_PERMISSION,
                                currentPhaseLabel = "Waiting for user permission..."
                            )
                        }
                        repository.appendLog(taskId, step, "PERMISSION", "Approval Requested", permReq.summary)
                        val approved = awaitUserPermission(permReq)
                        _state.update { it.copy(taskStatus = TaskStatus.RUNNING) }
                        if (approved) {
                            toolRegistry.execute(call.name, call.arguments, approvedLevel2 = true)
                        } else {
                            ToolExecutionOutcome(call.name, false, "User denied permission for ${call.name}.", verified = true)
                        }
                    } else {
                        toolRegistry.execute(call.name, call.arguments, approvedLevel2 = false)
                    }

                    repository.appendLog(
                        taskId,
                        step,
                        "TOOL_RESULT",
                        "${call.name} (${if (outcome.success) "OK" else "ERR"})",
                        outcome.output,
                        verified = outcome.verified
                    )
                    appendFeed(
                        kind = if (outcome.success) FeedEntryKind.VERIFIED_TOOL_RESULT else FeedEntryKind.ERROR_NOTICE,
                        title = "Verified Tool: ${call.name}",
                        body = outcome.output.take(1200)
                    )

                    var finalToolOutput = outcome.output
                    outcome.interventionRequest?.let { req ->
                        appendFeed(
                            kind = FeedEntryKind.HUMAN_INTERVENTION,
                            title = "লাইভ স্ক্রিন প্রয়োজন: ${req.challengeType}",
                            body = "${req.reason}\n${req.instructionForUser}"
                        )
                        repository.appendLog(taskId, step, "INTERVENTION", req.challengeType, req.reason)
                        val userResolution = awaitHumanIntervention(req)
                        appendFeed(
                            kind = FeedEntryKind.USER_COMMAND,
                            title = "মানব-সমাধান সম্পন্ন (Resumed)",
                            body = userResolution
                        )
                        finalToolOutput = "${outcome.output}\n\n[HUMAN_INTERVENTION_RESOLVED]: $userResolution"
                    }

                    conversation.add(
                        AiMessage(
                            role = "tool",
                            content = finalToolOutput.take(4000),
                            toolCallId = call.id,
                            toolName = call.name
                        )
                    )
                }
                step++
            }

            if (finalText.isBlank()) {
                finalText = "Completed $step agent execution steps. Inspect verified tool logs for details."
                appendFeed(FeedEntryKind.AI_REASONING, "Execution Summary", finalText)
            }

            repository.getTask(taskId)?.let {
                repository.updateTask(
                    it.copy(
                        status = TaskStatus.COMPLETED,
                        finalReport = finalText,
                        tokensUsed = totalTokens
                    )
                )
            }
            _state.update {
                it.copy(
                    isBusy = false,
                    taskStatus = TaskStatus.COMPLETED,
                    pendingIntervention = null,
                    currentPhaseLabel = "Completed ($totalTokens tokens)",
                    streamingBuffer = ""
                )
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            val msg = e.message ?: "Unexpected agent error"
            repository.appendLog(taskId, step, "ERROR", "Runtime Error", msg)
            repository.getTask(taskId)?.let {
                repository.updateTask(it.copy(status = TaskStatus.FAILED, finalReport = msg))
            }
            appendFeed(FeedEntryKind.ERROR_NOTICE, "Agent Error", msg)
            _state.update {
                it.copy(
                    isBusy = false,
                    taskStatus = TaskStatus.FAILED,
                    pendingIntervention = null,
                    currentPhaseLabel = "Error",
                    streamingBuffer = ""
                )
            }
        }
    }

    private suspend fun awaitUserPermission(request: PendingPermissionRequest): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        permissionDeferred = deferred
        _state.update { it.copy(pendingPermission = request) }
        return deferred.await()
    }

    private suspend fun awaitHumanIntervention(request: HumanInterventionRequest): String {
        val deferred = CompletableDeferred<String>()
        interventionDeferred = deferred
        _state.update {
            it.copy(
                pendingIntervention = request,
                taskStatus = TaskStatus.PAUSED,
                currentPhaseLabel = "⏸️ সল্ভ করুন: ${request.challengeType} (Live Screen)"
            )
        }
        onInterventionAlert(request)
        return deferred.await()
    }

    private suspend fun waitWhilePaused() {
        while (_state.value.isPaused) {
            delay(250)
        }
    }

    private fun appendFeed(kind: FeedEntryKind, title: String, body: String) {
        _state.update { cur ->
            cur.copy(feed = (cur.feed + CommandFeedItem(kind = kind, title = title, body = body)).takeLast(80))
        }
    }

    private fun classifyComplexity(prompt: String): TaskComplexity {
        val lower = prompt.lowercase()
        return when {
            lower.contains("script") || lower.contains("python") || lower.contains("code") || lower.contains("html") -> TaskComplexity.CODING
            lower.contains("job") || lower.contains("youtube") || lower.contains("research") || lower.contains("automate") || lower.contains("virtual hand") -> TaskComplexity.MULTISTEP_AGENT
            lower.length > 160 -> TaskComplexity.REASONING
            else -> TaskComplexity.SIMPLE
        }
    }
}
