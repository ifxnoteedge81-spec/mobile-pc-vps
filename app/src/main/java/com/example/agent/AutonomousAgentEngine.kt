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
    private val botEngine: ZeroLatencyBotEngine? = null,
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

        // Zero-Second Multi-Bot Dispatch (t = 0ms): executes direct gestures/app opens instantly,
        // or pre-warms target app / share composer / signup page while Cloud AI prepares next steps.
        val botResult = botEngine?.tryInstantOrPrewarm(userPrompt.trim(), attachments, settings.fullAutonomousMode)
        if (botResult != null) {
            repository.appendLog(taskId, 0, "TOOL_RESULT", "${botResult.botName} (0ms)", botResult.summary, verified = true)
            appendFeed(
                kind = FeedEntryKind.VERIFIED_TOOL_RESULT,
                title = "⚡ ${botResult.botName}",
                body = botResult.summary
            )
            if (botResult.handledCompletely) {
                sessionTurns.add(AiMessage(role = "user", content = displayUserText.take(800)))
                sessionTurns.add(AiMessage(role = "assistant", content = botResult.summary))
                while (sessionTurns.size > 12) sessionTurns.removeAt(0)
                repository.getTask(taskId)?.let {
                    repository.updateTask(
                        it.copy(
                            status = TaskStatus.COMPLETED,
                            finalReport = botResult.summary,
                            modelUsed = "Zero-Second Local Bot (${botResult.botName})"
                        )
                    )
                }
                _state.update {
                    it.copy(
                        isBusy = false,
                        taskStatus = TaskStatus.COMPLETED,
                        activeModelLabel = "⚡ ${botResult.botName}",
                        currentPhaseLabel = "Completed in 0s (Local Bot)",
                        streamingBuffer = ""
                    )
                }
                return
            }
        }

        val finalUserTurnPrompt = if (botResult != null && botResult.preExecutedContext.isNotBlank()) {
            "$enrichedPrompt\n\n=== ZERO-SECOND BOT PRE-EXECUTION ===\n${botResult.preExecutedContext}"
        } else {
            enrichedPrompt
        }

        // Sub-5-second fast path for simple greetings & short conversational questions
        if (isFastChat && botResult == null) {
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
        else memories.take(12).joinToString("\n") { "- [${it.category}] ${it.memoryKey}: ${it.content.take(200)}" }

        val recentTasks = repository.getRecentTasksSnapshot(6)
            .filter { it.id != taskId && it.finalReport.isNotBlank() }
            .take(4)
        val continuityContext = if (recentTasks.isEmpty()) "None yet."
        else recentTasks.reversed().joinToString("\n") { t ->
            "- User: ${t.prompt.take(160)} -> Result: ${t.finalReport.take(200)}"
        }

        val importedSkills = repository.getEnabledImportedSkillsSnapshot()
        val matchedSkills = if (importedSkills.isEmpty()) {
            emptyList()
        } else {
            skillManager.matchRelevantSkills(userPrompt, importedSkills, limit = 6)
        }
        val importedSkillsIndex = if (importedSkills.isEmpty()) {
            "None imported yet."
        } else if (matchedSkills.isEmpty()) {
            "Total Imported: ${importedSkills.size} skills (use `skill_search(query=...)` if a specialized skill is needed)."
        } else {
            "Total Imported: ${importedSkills.size} | Auto-Matched Top ${matchedSkills.size}:\n" +
                matchedSkills.joinToString("\n") { m ->
                    val sk = m.skill
                    "- [${sk.slug}] ${sk.title} (${sk.ecosystem}, ${sk.wordCount}w): ${sk.description.take(140)}"
                }
        }

        val attachmentHint = if (attachments.any { skillManager.looksLikeSkillFile(it.displayName, it.textExcerpt) }) {
            "\nNOTE: User attached skill file(s). If user wants to add/use them, call `skill_import_auto(source=\"uploads/...\")`."
        } else ""

        val complexity = classifyComplexity(finalUserTurnPrompt)
        val isUiFastTurn = complexity == TaskComplexity.MULTISTEP_AGENT && !isCodingOrCadPrompt(userPrompt)
        val vhStatus = if (com.example.accessibility.PersonalAiAccessibilityService.isConnected.value) "CONNECTED" else "NOT ENABLED"

        val systemPrompt = if (isUiFastTurn) {
            """
            You are PERSONAL AI COMPUTER — The AI Brain orchestrating Zero-Latency Local Bots across ALL Android Apps, Internet Websites, and Virtual World tasks. Reply concisely in the user's language (Bangla or English).
            Execute tools immediately until finished. Do NOT narrate unnecessary steps.
            RULES:
            1. BRAIN + ZERO-LATENCY BOTS SYNERGY:
               - AutoPopupReflexBot already clears Allow/Skip/Later/NotNow/Cookie overlays automatically. Never waste turns manually dismissing simple nag popups unless one remains.
               - NO SCREENSHOTS: Never call `screenshot`. Read live UI hierarchy directly (`device_screen_inspect` or `[LIVE SCREEN UPDATE]`) and live browser DOM (`browser_open_and_read`, `browser_batch`).
            2. UNIVERSAL APP + INTERNET FALLBACK:
               - If searching/downloading in any app (e.g., MovieBox, YouTube, etc.) fails or returns no result, IMMEDIATELY switch to Live Browser (`browser_open_and_read`, `browser_download`) or phone Chrome (`app_launch("chrome")` / `device_intent_action("url", ...)`).
            3. MULTI-ACTION BATCHING (1-2 MIN MAX):
               - App UI: use `device_virtual_hand(action="batch", steps="[...]")` to bundle 2-10 taps/inputs/scrolls in ONE turn!
               - Browser forms: use `browser_batch(steps="[...]")` to fill multiple inputs + click Next/Submit in ONE turn!
            4. MULTI-MEDIA & THUMBNAIL TARGETING (MultiMediaTargetBot):
               - MultiMediaTargetBot pre-shares the primary video/photo AND stages secondary/thumbnail images at the top of Android Gallery (`Pictures/AI Computer`). Use `device_intent_action(action_type="stage_gallery", target="uploads/...")` if needed later, and when picking a Thumbnail or secondary photo, select that staged filename or tap the FIRST newest gallery tile!
            5. AUTONOMOUS ACCOUNT CREATION & LOGIN (GMAIL/WEB/APPS):
               - Fill Name, Birthday, Gender, Username, Password directly (`browser_batch`/`browser_fill` or `device_virtual_hand`).
               - Whenever Phone Number, SMS OTP, CAPTCHA, or human verification is needed, IMMEDIATELY call `request_human_intervention` (`target_mode="BROWSER"` or `"VIRTUAL_HANDS"`) telling the user what to enter in the Live Screen box or solve on screen and tap 'জমা দিন'. Use `[HUMAN_INTERVENTION_RESOLVED]` immediately on resume.
            6. SELF-HEALING: If a selector fails, use text/hint/scroll or `browser_eval_js`.
            7. WHATSAPP AI MANAGEMENT BOT (NO EXTERNAL APP SWITCH NEEDED): Use `wa_list_chats`, `wa_read_chat`, `wa_send_message`, and `wa_configure_chat` to inspect previous conversations, send direct or AI-composed messages to any contact or group, and toggle Auto-Reply / Anti-Link / Anti-Sticker per group or contact directly inside the app.$attachmentHint

            Virtual Hands: $vhStatus | Tier: ${resources.deviceTierLabel}
            Skills: $importedSkillsIndex
            Memory: $memoryContext
            Recent: $continuityContext
            """.trimIndent()
        } else {
            """
            You are PERSONAL AI COMPUTER, a next-generation autonomous Android & Online Engineering AI OS designed to surpass standard agents in reliability, hardware adaptability, and real execution depth.
            You control real on-device tools:
            • Code & Terminal: Linux shell (`shell_exec`), Python 3 (`python_exec`), V8 JS/TS (`js_exec`), multi-file search (`workspace_grep`), file I/O (`file_list`, `file_read`, `file_write`, `file_delete`), local HTTP server (`local_server_control`).
            • CAD & Engineering Design: `cad_generate_drawing` creates real AutoCAD ASCII DXF (`.dxf`), dimensioned SVG Blueprints (`.svg`), interactive pan/zoom HTML viewers (`_viewer.html`), AND 3D CAD `.stl` meshes for ANY architectural floor plan, mechanical bracket, gear, PCB, civil drawing, or custom geometry!
            • Web & Research: Fast HTTPS (`http_request`) + Live Chromium Browser (`browser_open_and_read`, `browser_click`, `browser_fill`, `browser_batch`, `browser_scroll`, `browser_eval_js`, `browser_download`).
            • Full Phone Virtual Hands + Integrated Bots (NO Screenshots): `app_list`, `app_launch`, `app_install_store`, `device_screen_inspect`, `device_virtual_hand` (`batch` for 2-10 actions in 1 turn + `auto_dismiss_popups` — AutoPopupReflexBot also auto-clears Allow/Skip/Later popups automatically!), `device_clipboard`, `device_intent_action` (`share_file`, `share_multiple`, `stage_gallery` for Thumbnail/Photo Picker targeting!).
            • Universal Auto-Skill Adapter: `skill_import_auto`, `skill_list`, `skill_load`, `skill_delete` (absorbs any Anthropic/OpenClaw/MCP/Gemini/ZIP/10,000+ line skill automatically).
            • Hardware & Memory: `system_hardware_status`, `memory_read`, `memory_save`, `schedule_automation`.

            CORE EXECUTION RULES (BRAIN + ZERO-LATENCY BOTS + DIRECT SCREEN READING):
            1. Reply in the user's language (Bangla or English). Execute real tool calls directly until the goal is completely finished and verified.
            2. DOCUMENT & PDF READING:
               - For ANY `.pdf`, `.docx`, `.txt`, `.csv`, or `.json` file (such as a CV/Resume in `uploads/`), call `file_read(path="uploads/...")` directly — `file_read` has a built-in PDF/DOCX text extractor! Or in Python use `extract_pdf_text("uploads/...")`.
            3. ULTRA-FAST VIRTUAL HANDS & UNIVERSAL WEB FALLBACK:
               - NEVER take screenshots (`screenshot` is disabled). Read the live screen UI hierarchy directly (`device_screen_inspect`, or read `[LIVE SCREEN UPDATE]` returned automatically after every Virtual Hand / `app_launch` action!).
               - AutoPopupReflexBot clears Allow/Skip/Later/Cookie popups automatically. Always combine multi-step UI or form sequences via `device_virtual_hand(action="batch", steps="[...]")` or `browser_batch(steps="[...]")`.
               - For multi-file uploads (e.g. Video + Thumbnail on YouTube/Social): MultiMediaTargetBot shares primary media and stages secondary/thumbnail images in Android Gallery (`Pictures/AI Computer`). Use `stage_gallery` if needed so Photo Pickers show the thumbnail first!
               - For ANY search/download across apps & internet: if not found in an app, immediately fall back to Live Browser (`browser_open_and_read` / `browser_download`) or phone Chrome!
            4. AUTONOMOUS ACCOUNT CREATION & LOGIN (GMAIL / GOOGLE / WEB / APPS):
               - If asked to create an account (e.g., Gmail/Google account -> `browser_open_and_read("https://accounts.google.com/signup")` OR Settings/Gmail app via Virtual Hands) or log in anywhere:
               - Inspect fields and fill Name, Birthday, Gender, Username, Password directly (`browser_fill`, `browser_batch`, or `device_virtual_hand` `type` / `batch`, including password fields when requested by user).
               - Whenever Phone Number, SMS OTP, CAPTCHA, or personal verification info is needed from the user, IMMEDIATELY call `request_human_intervention` (`target_mode="BROWSER"` or `"VIRTUAL_HANDS"`) with a clear question in `instruction_for_user` telling the user what to type in the Live Screen input box (e.g., phone number or OTP) or solve on screen and tap 'জমা দিন'. Once resumed, read `[HUMAN_INTERVENTION_RESOLVED]: ...`, enter that value immediately, and finish creating the account!
            5. CV-TO-JOB SEARCH & APPLICATION WORKFLOW:
               - Extract candidate skills/experience from CV -> search live jobs via `browser_open_and_read` -> fill applications -> call `request_human_intervention` for CAPTCHA/OTP/final submission.
            6. NEVER say "I cannot draw/design CAD":
               - For ANY AutoCAD / floor plan / mechanical / 2D or 3D engineering drawing task, call `cad_generate_drawing` with exact coordinates and open the live blueprint viewer URL.
            7. SELF-HEALING ON ERRORS:
               - If any tool fails, immediately diagnose and switch strategy (`scroll_to_text`, coordinate `tap`, `browser_eval_js`, `http_request` <-> `browser_open_and_read`).
            8. HARDWARE-ADAPTIVE EXECUTION:
               - Respect this Android phone's real hardware capacity below (${resources.deviceTierLabel} | Max Steps: ${resources.recommendedMaxAgentSteps}).
            9. UNIVERSAL AUTO-SKILL ADAPTER:
               - Absorb skill files/URLs with `skill_import_auto` and load matching imported skills via `skill_load`.
            10. MEMORY CONTINUITY:
               - Call `memory_save` for user preferences & project facts (never save passwords/OTPs).$attachmentHint

            Device Tier: ${resources.deviceTierLabel} | RAM: ${resources.availableRamMb}/${resources.totalPhysicalRamMb} MB (Budget: ${settings.ramBudgetMb} MB) | Workspace ROM: ${"%.1f".format(resources.virtualEnvUsedMb)}/${settings.storageQuotaMb} MB | Virtual Hands: $vhStatus
            Imported Auto-Skills Index:
            $importedSkillsIndex

            Long-Term Agent Memory:
            $memoryContext

            Recent Conversation & Task Continuity (Previous Chats):
            $continuityContext
            """.trimIndent()
        }

        // Include recent session conversation turns (clean user/assistant context) + current user command (with inline vision images if any)
        val conversation = mutableListOf<AiMessage>().apply {
            addAll(sessionTurns.takeLast(8))
            add(AiMessage(role = "user", content = finalUserTurnPrompt, imageAttachments = promptImages))
        }

        val declarations = toolRegistry.getDeclarationsForPrompt(
            userPrompt = finalUserTurnPrompt,
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

                val useFastTurn = complexity == TaskComplexity.SIMPLE || complexity == TaskComplexity.MULTISTEP_AGENT
                val completionResult = router.complete(
                    settings = settings,
                    complexity = complexity,
                    systemPrompt = systemPrompt,
                    messages = conversation,
                    tools = declarations,
                    fastMode = useFastTurn,
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
            "browser", "chrome", "open", "click", "search", "google", "http", "url", "website", "internet",
            "job", "apply", "cv", "resume", "youtube", "video", "thumbnail", "movie", "cinema", "stream",
            "app", "install", "launch", "virtual", "screen", "tap", "swipe", "skill", "import", "github", "memory",
            "schedule", "server", "ram", "rom", "hardware", "download", "write", "create", "build", "share",
            "run", "execute", "delete", "list", "check", "find",
            "ফাইল", "সিভি", "জব", "চাকরি", "এপ্লাই", "স্ক্রিপ্ট", "ব্রাউজার", "ক্রোম", "ড্রয়িং", "ডিজাইন",
            "অ্যাপ", "ইনস্টল", "ডাউনলোড", "স্কিল", "সার্চ", "তৈরি", "বানাও", "খোলো", "করো", "দাও", "চেক",
            "মুভি", "সিনেমা", "থাম্বনেল", "শেয়ার", "ইন্টারনেট", "ভার্চুয়াল", "ওয়েব"
        )
        if (actionKeywords.any { lower.contains(it) }) return false
        return trimmedPrompt.split(Regex("\\s+")).size <= 10
    }

    private fun isCodingOrCadPrompt(prompt: String): Boolean {
        val lower = prompt.lowercase()
        return lower.contains("script") || lower.contains("python") || lower.contains("code") ||
            lower.contains("html") || lower.contains("shell") || lower.contains("bash") ||
            lower.contains("autocad") || lower.contains("dxf") || lower.contains("cad") || lower.contains("stl")
    }

    private fun classifyComplexity(prompt: String): TaskComplexity {
        val lower = prompt.lowercase()
        return when {
            isCodingOrCadPrompt(prompt) -> TaskComplexity.CODING
            lower.contains("job") || lower.contains("youtube") || lower.contains("research") || lower.contains("automate") ||
                lower.contains("virtual") || lower.contains("app") || lower.contains("design") ||
                lower.contains("gmail") || lower.contains("account") || lower.contains("tiktok") || lower.contains("upload") ||
                lower.contains("chrome") || lower.contains("browser") || lower.contains("website") || lower.contains("internet") ||
                lower.contains("movie") || lower.contains("cinema") || lower.contains("download") || lower.contains("thumbnail") ||
                lower.contains("জিমেইল") || lower.contains("অ্যাকাউন্ট") || lower.contains("আপলোড") ||
                lower.contains("মুভি") || lower.contains("সিনেমা") || lower.contains("ক্রোম") || lower.contains("থাম্বনেল") -> TaskComplexity.MULTISTEP_AGENT
            lower.length > 180 -> TaskComplexity.REASONING
            else -> TaskComplexity.SIMPLE
        }
    }
}
