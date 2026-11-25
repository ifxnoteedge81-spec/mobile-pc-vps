package com.example.agent

import com.example.ai.AiMessage
import com.example.ai.AiModelRouter
import com.example.ai.TaskComplexity
import com.example.resources.DeviceResourceManager
import com.example.runtime.LocalLinuxEnvironment
import com.example.security.PermissionLevel
import com.example.storage.AgentRepository
import com.example.storage.AppSettings
import com.example.storage.MemoryCategory
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
import java.util.concurrent.CopyOnWriteArrayList

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
    val timestamp: Long = System.currentTimeMillis(),
    val actionUrl: String? = null,
    val shareRelativePath: String? = null,
    val shareSecondaryPath: String? = null
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
    private val skillManager: UniversalSkillManager,
    private val settingsFlow: kotlinx.coroutines.flow.Flow<AppSettings>,
    private val onInterventionAlert: (HumanInterventionRequest) -> Unit = {}
) {
    private val _state = MutableStateFlow(AgentRuntimeState())
    val state: StateFlow<AgentRuntimeState> = _state.asStateFlow()

    private var activeJob: Job? = null
    private var permissionDeferred: CompletableDeferred<Boolean>? = null
    private var interventionDeferred: CompletableDeferred<String>? = null

    // Keeps active session conversation turns so user never has to repeat context
    private val sessionTurns = CopyOnWriteArrayList<AiMessage>()

    fun submitCommand(
        userPrompt: String,
        attachments: List<AgentUploadedAttachment> = emptyList()
    ) {
        launchCommandJob(userPrompt, attachments)
    }

    suspend fun executeCommandAndWait(
        userPrompt: String,
        attachments: List<AgentUploadedAttachment> = emptyList()
    ): Boolean {
        val job = launchCommandJob(userPrompt, attachments) ?: return false
        job.join()
        return _state.value.taskStatus == TaskStatus.COMPLETED
    }

    private fun launchCommandJob(
        userPrompt: String,
        attachments: List<AgentUploadedAttachment> = emptyList()
    ): Job? {
        val trimmed = userPrompt.trim()
        if (trimmed.isEmpty() && attachments.isEmpty()) return null

        if (attachments.isEmpty() && (trimmed.startsWith("/sh ") || trimmed.startsWith("/py "))) {
            return runDirectLocalShortcut(trimmed)
        }

        activeJob?.cancel()
        permissionDeferred?.complete(false)
        permissionDeferred = null
        interventionDeferred?.complete("CANCELLED")
        interventionDeferred = null

        val job = scope.launch(Dispatchers.IO) {
            runAgentCycle(trimmed, attachments)
        }
        activeJob = job
        return job
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
        sessionTurns.clear()
        _state.update { it.copy(feed = emptyList(), streamingBuffer = "") }
    }

    private fun runDirectLocalShortcut(cmd: String): Job {
        return scope.launch(Dispatchers.IO) {
            appendFeed(FeedEntryKind.USER_COMMAND, "Local Direct Command", cmd)
            val isPy = cmd.startsWith("/py ")
            val tool = if (isPy) "python_exec" else "shell_exec"
            val args = if (isPy) JSONObject().put("code", cmd.removePrefix("/py ").trim())
            else JSONObject().put("command", cmd.removePrefix("/sh ").trim())

            val settings = settingsFlow.first()
            val perm = toolRegistry.evaluatePermission(tool, args, settings.fullAutonomousMode)
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
            appendFeed(
                kind = FeedEntryKind.VERIFIED_TOOL_RESULT,
                title = "Verified: $tool",
                body = out.output,
                actionUrl = out.actionUrl,
                shareRelativePath = out.shareRelativePath,
                shareSecondaryPath = out.shareSecondaryPath
            )
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
        val isFastChat = isFastConversationalTurn(trimmedPrompt = userPrompt.trim(), attachments = attachments)

        val promptImages = attachments.flatMap { it.imageAttachments }.take(6)

        val enrichedPrompt = buildString {
            append(userPrompt.ifBlank { "Please inspect and analyze the uploaded files below." })
            if (attachments.isNotEmpty()) {
                append("\n\n=== USER UPLOADED FILES IN virtual_env/ ===")
                attachments.forEach { att ->
                    append("\n- Name: ${att.displayName} | Path: ${att.relativePath} | Type: ${att.mimeType} | Size: ${att.sizeBytes}B")
                    if (!att.textExcerpt.isNullOrBlank()) {
                        append("\n  Extracted Content:\n  ${att.textExcerpt.take(5500)}")
                    } else if (att.imageAttachments.isNotEmpty()) {
                        append("\n  Visual Vision Pages/Images Attached: ${att.imageAttachments.size} image(s) sent inline directly to your vision input — read and analyze them directly!")
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
                currentPhaseLabel = if (isFastChat) "Fast Reply..." else "Step 1: Planning with memory & live API...",
                streamingBuffer = ""
            )
        }

        if (resources.warningMessage != null) {
            repository.appendLog(taskId, 0, "RESOURCE", "Resource Guard", resources.warningMessage)
        }

        // Sub-5-second fast path for simple greetings & short conversational questions
        if (isFastChat) {
            val fastPrompt = "You are Personal AI Computer, a helpful, ultra-fast autonomous AI computer assistant. Reply naturally, concisely, and warmly in the user's language (Bangla or English)."
            val conversation = mutableListOf<AiMessage>().apply {
                addAll(sessionTurns.takeLast(4))
                add(AiMessage(role = "user", content = enrichedPrompt))
            }
            val fastRes = router.complete(
                settings = settings,
                complexity = TaskComplexity.SIMPLE,
                systemPrompt = fastPrompt,
                messages = conversation,
                tools = emptyList(),
                fastMode = true,
                onStreamChunk = { chunk ->
                    _state.update { cur -> cur.copy(streamingBuffer = cur.streamingBuffer + chunk) }
                }
            )
            if (fastRes.isSuccess) {
                val completion = fastRes.getOrThrow()
                val tokens = completion.promptTokens + completion.completionTokens
                val reply = completion.text.ifBlank { "হ্যালো! বলো, আজ কীভাবে সাহায্য করতে পারি?" }
                val slotTag = completion.keySlotLabel?.let { "$it • " }.orEmpty()
                repository.appendLog(taskId, 1, "AI_RESPONSE", "Fast Reply", reply, verified = true)
                appendFeed(FeedEntryKind.AI_REASONING, "AI (${completion.provider} • $slotTag${completion.model})", reply)
                sessionTurns.add(AiMessage(role = "user", content = displayUserText))
                sessionTurns.add(AiMessage(role = "assistant", content = reply.take(1500)))
                while (sessionTurns.size > 12) sessionTurns.removeAt(0)
                repository.getTask(taskId)?.let {
                    repository.updateTask(
                        it.copy(
                            status = TaskStatus.COMPLETED,
                            finalReport = reply,
                            modelUsed = "${completion.provider} ($slotTag${completion.model})",
                            tokensUsed = tokens
                        )
                    )
                }
                _state.update {
                    it.copy(
                        isBusy = false,
                        taskStatus = TaskStatus.COMPLETED,
                        activeModelLabel = "${completion.provider} ($slotTag${completion.model})",
                        currentPhaseLabel = "Completed ($tokens tokens)",
                        streamingBuffer = ""
                    )
                }
                return
            }
            // If fast path fails, fall through to error handler cleanly via standard result handling
            val errMsg = fastRes.exceptionOrNull()?.message ?: "AI Provider Error"
            repository.appendLog(taskId, 1, "ERROR", "Provider Error", errMsg)
            repository.getTask(taskId)?.let {
                repository.updateTask(it.copy(status = TaskStatus.FAILED, finalReport = errMsg))
            }
            appendFeed(FeedEntryKind.ERROR_NOTICE, "AI Gateway Notice", errMsg)
            _state.update {
                it.copy(isBusy = false, taskStatus = TaskStatus.FAILED, currentPhaseLabel = "Failed", streamingBuffer = "")
            }
            return
        }

        val memories = repository.getMemorySnapshot()
        val memoryContext = if (memories.isEmpty()) "None saved yet."
        else memories.take(25).joinToString("\n") { "- [${it.category}] ${it.memoryKey}: ${it.content.take(260)}" }

        val recentTasks = repository.getRecentTasksSnapshot(8)
            .filter { it.id != taskId && it.finalReport.isNotBlank() }
            .take(6)
        val continuityContext = if (recentTasks.isEmpty()) "None yet."
        else recentTasks.reversed().joinToString("\n") { t ->
            "- User: ${t.prompt.take(180)} -> Result: ${t.finalReport.take(240)}"
        }

        val importedSkills = repository.getEnabledImportedSkillsSnapshot()
        val importedSkillsIndex = if (importedSkills.isEmpty()) {
            "None imported yet."
        } else {
            importedSkills.take(30).joinToString("\n") { sk ->
                "- [${sk.slug}] ${sk.title} (${sk.ecosystem}, ${sk.wordCount} words/${sk.lineCount} lines): ${sk.description.take(160)}"
            }
        }

        val attachmentHint = if (attachments.any { skillManager.looksLikeSkillFile(it.displayName, it.textExcerpt) }) {
            "\nNOTE: User attached file(s) that look like an external AI skill. If user wants to add/install/use them as skills, immediately call `skill_import_auto(source=\"uploads/...\")` on the file's relative path so all bundled instructions (even 10,000+ lines) are permanently absorbed!"
        } else ""

        val systemPrompt = """
            You are PERSONAL AI COMPUTER, a next-generation autonomous Android & Online Engineering AI OS designed to surpass standard agents in reliability, hardware adaptability, and real execution depth.
            You control real on-device tools:
            • Code & Terminal: Linux shell (`shell_exec`), Python 3 (`python_exec`), V8 JS/TS (`js_exec`), multi-file search (`workspace_grep`), file I/O (`file_list`, `file_read`, `file_write`, `file_delete`), local HTTP server (`local_server_control`).
            • CAD & Engineering Design: `cad_generate_drawing` creates real AutoCAD ASCII DXF (`.dxf`), dimensioned SVG Blueprints (`.svg`), interactive pan/zoom HTML viewers (`_viewer.html`), AND 3D CAD `.stl` meshes for ANY architectural floor plan, mechanical bracket, gear, PCB, civil drawing, or custom geometry!
            • Web & Research: Fast HTTPS (`http_request`) + Live Chromium Browser (`browser_open_and_read`, `browser_click`, `browser_fill`, `browser_scroll`, `browser_eval_js`, `browser_download`).
            • Full Phone Virtual Hands: `app_list`, `app_launch`, `app_install_store`, `device_screen_inspect`, `device_virtual_hand`, `device_clipboard`, `device_intent_action`.
            • Universal Auto-Skill Adapter: `skill_import_auto`, `skill_list`, `skill_load`, `skill_delete` (absorbs any Anthropic/OpenClaw/MCP/Gemini/ZIP/10,000+ line skill automatically).
            • Hardware & Memory: `system_hardware_status`, `memory_read`, `memory_save`, `schedule_automation`.

            CORE EXECUTION RULES (NO-EXCUSES AUTONOMY + SELF-HEALING):
            1. Reply in the user's language (Bangla or English). Execute real tool calls directly until the goal is completely finished and verified.
            2. DOCUMENT & PDF READING:
               - For ANY `.pdf`, `.docx`, `.txt`, `.csv`, or `.json` file (such as a CV/Resume in `uploads/`), call `file_read(path="uploads/...")` directly — `file_read` has a built-in PDF/DOCX text extractor! Or in Python use `extract_pdf_text("uploads/...")` / `PdfReader(...)`. Never run raw binary decoding scripts first.
            3. CV-TO-JOB SEARCH & APPLICATION WORKFLOW:
               - Extract candidate skills, roles, location, contact info, and experience from the CV -> search real active job boards via `browser_open_and_read` (e.g., query or direct public APIs like `https://remotive.com/api/remote-jobs?search=...`, `https://www.arbeitnow.com/api/job-board-api`, `bdjobs.com`, `linkedin.com/jobs`) -> pick matching openings -> open application page in Live Browser -> fill fields using `browser_fill` / `browser_click` -> pause with `request_human_intervention` whenever CAPTCHA, login/OTP, or final application review/submission requires the user.
            4. NEVER say "I cannot draw/design CAD" or give fake placeholders:
               - For ANY AutoCAD / floor plan / architectural / mechanical / 2D or 3D engineering drawing task, calculate exact real-world coordinates in Python (`python_exec`) if complex, then call `cad_generate_drawing` with detailed `entities_json` (`rect`, `line`, `circle`, `arc`, `polyline`, `dimension`, `text`, proper CAD layers) and optionally `extrude_height_3d`. Open the generated viewer URL in `browser_open_and_read` so the user sees the live blueprint immediately, and offer sharing the `.dxf`/`.stl` via `device_intent_action`!
            5. SELF-HEALING ON ERRORS:
               - If ANY tool fails, never give up or repeat the identical broken call: diagnose root cause, fix scripts/arguments, switch to an alternative strategy (e.g. `http_request` <-> `browser_open_and_read`, `shell_exec` <-> `python_exec` <-> `file_read`, UI element not found -> `scroll_to_text` + `wait_and_inspect`), and verify success.
            6. HARDWARE-ADAPTIVE EXECUTION:
               - Respect this Android phone's real hardware capacity below (${resources.deviceTierLabel} | Max Steps: ${resources.recommendedMaxAgentSteps}). For huge data, stream/chunk cleanly using files & Python scripts instead of bloating RAM.
            7. APP & ONLINE AUTONOMY:
               - Use `app_list` -> `app_launch` (or `app_install_store` if missing) -> `device_screen_inspect` + `device_virtual_hand`.
            8. UNIVERSAL AUTO-SKILL ADAPTER:
               - Whenever the user attaches a skill file/ZIP, gives a skill GitHub/URL, or pastes skill docs, call `skill_import_auto` immediately. When a task matches an imported skill below, call `skill_load(slug=..., query=...)` first and adapt its steps onto our Android tools.
            9. OTP / CAPTCHA / 2FA LIVE SCREEN HANDOFF:
               - Immediately call `request_human_intervention` (`target_mode` = 'BROWSER' or 'VIRTUAL_HANDS') when human verification appears, then continue seamlessly once resumed.
            10. MEMORY CONTINUITY:
               - Call `memory_save` proactively for key user workflows, CV profile highlights, CAD preferences, and project facts.$attachmentHint

            Device Tier: ${resources.deviceTierLabel} | RAM: ${resources.availableRamMb}/${resources.totalPhysicalRamMb} MB (Budget: ${settings.ramBudgetMb} MB) | Workspace ROM: ${"%.1f".format(resources.virtualEnvUsedMb)}/${settings.storageQuotaMb} MB | Virtual Hands: ${if (com.example.accessibility.PersonalAiAccessibilityService.isConnected.value) "CONNECTED" else "NOT ENABLED"}
            Imported Auto-Skills Index:
            $importedSkillsIndex

            Long-Term Agent Memory:
            $memoryContext

            Recent Conversation & Task Continuity (Previous Chats):
            $continuityContext
        """.trimIndent()

        // Include recent session conversation turns (clean user/assistant context) + current user command (with inline vision images if any)
        val conversation = mutableListOf<AiMessage>().apply {
            addAll(sessionTurns.takeLast(8))
            add(AiMessage(role = "user", content = enrichedPrompt, imageAttachments = promptImages))
        }

        val complexity = classifyComplexity(enrichedPrompt)
        val declarations = toolRegistry.getDeclarationsForPrompt(
            userPrompt = enrichedPrompt,
            hasAttachments = attachments.isNotEmpty(),
            hasImportedSkills = importedSkills.isNotEmpty()
        )

        var step = 1
        var totalTokens = 0
        var finalText = ""
        val consecutiveToolFailures = mutableMapOf<String, Int>()

        try {
            val maxIterations = resources.recommendedMaxAgentSteps.coerceIn(10, 36)
            while (step <= maxIterations) {
                waitWhilePaused()

                _state.update {
                    it.copy(
                        taskStatus = TaskStatus.RUNNING,
                        currentPhaseLabel = "Step $step: Thinking & executing...",
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
                val slotTag = completion.keySlotLabel?.let { "$it • " }.orEmpty()
                _state.update { it.copy(activeModelLabel = "${completion.provider} ($slotTag${completion.model})") }

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
                        toolCalls = completion.toolCalls,
                        rawAssistantPartsJson = completion.rawAssistantPartsJson
                    )
                )

                for (call in completion.toolCalls) {
                    waitWhilePaused()
                    _state.update {
                        it.copy(currentPhaseLabel = "Running: ${call.name}")
                    }
                    repository.appendLog(taskId, step, "TOOL_CALL", call.name, call.arguments.toString())

                    val permReq = toolRegistry.evaluatePermission(call.name, call.arguments, settings.fullAutonomousMode)
                    val outcome = if (permReq != null && permReq.level == PermissionLevel.RESTRICTED) {
                        ToolExecutionOutcome(call.name, false, permReq.summary, verified = true)
                    } else if (permReq != null) {
                        _state.update {
                            it.copy(
                                taskStatus = TaskStatus.WAITING_PERMISSION,
                                currentPhaseLabel = "Waiting for user approval..."
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
                        toolRegistry.execute(call.name, call.arguments, approvedLevel2 = settings.fullAutonomousMode)
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
                        title = "Tool: ${call.name}",
                        body = outcome.output.take(1200),
                        actionUrl = outcome.actionUrl,
                        shareRelativePath = outcome.shareRelativePath,
                        shareSecondaryPath = outcome.shareSecondaryPath
                    )

                    var finalToolOutput = outcome.output
                    if (!outcome.success) {
                        val failCount = (consecutiveToolFailures[call.name] ?: 0) + 1
                        consecutiveToolFailures[call.name] = failCount
                        if (failCount >= 2) {
                            finalToolOutput += "\n\n[SELF-HEALING RECOVERY DIRECTIVE]: '${call.name}' failed $failCount times consecutively. Diagnose root cause and switch strategy/arguments or use an alternate tool rather than repeating the exact same call."
                        }
                    } else {
                        consecutiveToolFailures.remove(call.name)
                    }
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

                    val maxToolResultChars = if (call.name == "skill_load" || call.name == "file_read") 7600 else 3200
                    conversation.add(
                        AiMessage(
                            role = "tool",
                            content = finalToolOutput.take(maxToolResultChars),
                            toolCallId = call.id,
                            toolName = call.name
                        )
                    )
                }
                step++
            }

            if (finalText.isBlank()) {
                finalText = "Completed $step autonomous steps. Check verified tool outputs above."
                appendFeed(FeedEntryKind.AI_REASONING, "Execution Summary", finalText)
            }

            // Persist conversational turn into session memory & Room task summary
            sessionTurns.add(AiMessage(role = "user", content = displayUserText.take(1200)))
            sessionTurns.add(AiMessage(role = "assistant", content = finalText.take(1500)))
            while (sessionTurns.size > 12) {
                sessionTurns.removeAt(0)
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
            // Also mirror notable task outcome into permanent Agent Memory
            if (displayUserText.length > 10) {
                repository.saveMemory(
                    category = MemoryCategory.TASK_HISTORY,
                    key = "Task #$taskId: ${displayUserText.take(45)}",
                    content = "Command: ${displayUserText.take(180)} | Outcome: ${finalText.take(260)}"
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

    private fun appendFeed(
        kind: FeedEntryKind,
        title: String,
        body: String,
        actionUrl: String? = null,
        shareRelativePath: String? = null,
        shareSecondaryPath: String? = null
    ) {
        _state.update { cur ->
            cur.copy(
                feed = (
                    cur.feed + CommandFeedItem(
                        kind = kind,
                        title = title,
                        body = body,
                        actionUrl = actionUrl,
                        shareRelativePath = shareRelativePath,
                        shareSecondaryPath = shareSecondaryPath
                    )
                ).takeLast(80)
            )
        }
    }

    private fun isFastConversationalTurn(
        trimmedPrompt: String,
        attachments: List<AgentUploadedAttachment>
    ): Boolean {
        if (attachments.isNotEmpty()) return false
        if (trimmedPrompt.length > 140) return false
        val lower = trimmedPrompt.lowercase()
        val exactGreetings = setOf(
            "hi", "hii", "hiii", "hello", "hey", "helo", "yo", "sup",
            "good morning", "good afternoon", "good evening", "good night",
            "assalamu alaikum", "salam", "walaikum assalam", "thanks", "thank you", "ok", "okay",
            "হাই", "হ্যালো", "হেলো", "সালাম", "আসসালামু আলাইকুম", "কেমন আছো", "কেমন আছেন",
            "কি অবস্থা", "কী অবস্থা", "ধন্যবাদ", "ওকে", "ঠিক আছে", "তুমি কে", "কে তুমি"
        )
        if (lower in exactGreetings) return true

        val actionKeywords = listOf(
            "file", "upload", "pdf", "python", "script", "shell", "terminal", "bash",
            "code", "html", "autocad", "cad", "dxf", "stl", "3d", "blueprint", "floor plan",
            "browser", "open", "click", "search", "google", "http", "url", "website",
            "job", "apply", "cv", "resume", "youtube", "video", "app", "install", "launch",
            "virtual hand", "screen", "tap", "swipe", "skill", "import", "github", "memory",
            "schedule", "server", "ram", "rom", "hardware", "download", "write", "create", "build",
            "run", "execute", "delete", "list", "check", "find",
            "ফাইল", "সিভি", "জব", "চাকরি", "এপ্লাই", "স্ক্রিপ্ট", "ব্রাউজার", "ড্রয়িং", "ডিজাইন",
            "অ্যাপ", "ইনস্টল", "ডাউনলোড", "স্কিল", "সার্চ", "তৈরি", "বানাও", "খোলো", "করো", "দাও", "চেক"
        )
        if (actionKeywords.any { lower.contains(it) }) return false
        return trimmedPrompt.split(Regex("\\s+")).size <= 10
    }

    private fun classifyComplexity(prompt: String): TaskComplexity {
        val lower = prompt.lowercase()
        return when {
            lower.contains("script") || lower.contains("python") || lower.contains("code") || lower.contains("html") ||
                lower.contains("autocad") || lower.contains("dxf") || lower.contains("cad") || lower.contains("stl") -> TaskComplexity.CODING
            lower.contains("job") || lower.contains("youtube") || lower.contains("research") || lower.contains("automate") ||
                lower.contains("virtual hand") || lower.contains("app") || lower.contains("design") -> TaskComplexity.MULTISTEP_AGENT
            lower.length > 180 -> TaskComplexity.REASONING
            else -> TaskComplexity.SIMPLE
        }
    }
}
