package com.example.agent

import com.example.accessibility.DeviceAutomationController
import com.example.ai.ToolDeclaration
import com.example.browser.BrowserController
import com.example.runtime.LocalJsEngine
import com.example.runtime.LocalLinuxEnvironment
import com.example.runtime.LocalPythonEngine
import com.example.runtime.LocalWebServer
import com.example.runtime.VirtualWorkspaceManager
import com.example.security.CommandSecurityGuard
import com.example.security.CommandValidationResult
import com.example.security.PermissionLevel
import com.example.storage.AgentRepository
import com.example.storage.AutomationRuleEntity
import com.example.storage.MemoryCategory
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

data class HumanInterventionRequest(
    val id: Long = System.nanoTime(),
    val challengeType: String,
    val reason: String,
    val targetMode: String, // "BROWSER" or "VIRTUAL_HANDS"
    val urlOrPackage: String,
    val instructionForUser: String
)

data class ToolExecutionOutcome(
    val toolName: String,
    val success: Boolean,
    val output: String,
    val verified: Boolean = true,
    val interventionRequest: HumanInterventionRequest? = null
)

data class PendingPermissionRequest(
    val toolName: String,
    val level: PermissionLevel,
    val summary: String,
    val argumentsPreview: String
)

class ToolRegistry(
    private val workspace: VirtualWorkspaceManager,
    private val linuxEnv: LocalLinuxEnvironment,
    private val pythonEngine: LocalPythonEngine,
    private val jsEngine: LocalJsEngine,
    private val webServer: LocalWebServer,
    private val browser: BrowserController,
    private val deviceController: DeviceAutomationController,
    private val repository: AgentRepository
) {

    fun getDeclarations(): List<ToolDeclaration> = listOf(
        ToolDeclaration(
            name = "shell_exec",
            description = "Execute a POSIX shell command inside the sandboxed Linux virtual_env workspace.",
            parametersSchema = schema(
                props = mapOf("command" to prop("string", "Shell command to run, e.g. 'ls -la' or 'grep -rn foo workspace'")),
                required = listOf("command")
            )
        ),
        ToolDeclaration(
            name = "python_exec",
            description = "Execute Python 3 code on-device inside the virtual workspace and capture stdout/stderr.",
            parametersSchema = schema(
                props = mapOf("code" to prop("string", "Valid Python 3 code to run")),
                required = listOf("code")
            )
        ),
        ToolDeclaration(
            name = "js_exec",
            description = "Execute JavaScript (or TypeScript) on the Android V8 engine and return console logs.",
            parametersSchema = schema(
                props = mapOf(
                    "code" to prop("string", "JavaScript or TypeScript code"),
                    "typescript" to prop("boolean", "Set true if code uses basic TypeScript types")
                ),
                required = listOf("code")
            )
        ),
        ToolDeclaration(
            name = "file_list",
            description = "List files and directories inside virtual_env (e.g., '', 'workspace', 'uploads', 'scripts', 'projects', 'downloads').",
            parametersSchema = schema(
                props = mapOf("path" to prop("string", "Relative path inside virtual_env")),
                required = emptyList()
            )
        ),
        ToolDeclaration(
            name = "file_read",
            description = "Read a text file from virtual_env (including user uploaded files in uploads/).",
            parametersSchema = schema(
                props = mapOf("path" to prop("string", "Relative path inside virtual_env")),
                required = listOf("path")
            )
        ),
        ToolDeclaration(
            name = "file_write",
            description = "Write or update a text file (report, script, CV draft, HTML, JSON) inside virtual_env.",
            parametersSchema = schema(
                props = mapOf(
                    "path" to prop("string", "Relative file path inside virtual_env"),
                    "content" to prop("string", "Full file contents")
                ),
                required = listOf("path", "content")
            )
        ),
        ToolDeclaration(
            name = "file_delete",
            description = "Delete a file in virtual_env. Requires user Level 2 confirmation.",
            parametersSchema = schema(
                props = mapOf("path" to prop("string", "Relative path to delete")),
                required = listOf("path")
            )
        ),
        ToolDeclaration(
            name = "browser_open_and_read",
            description = "Open a URL or web search query in the integrated browser and extract page title, text, links, and forms.",
            parametersSchema = schema(
                props = mapOf("url_or_query" to prop("string", "Full https:// URL or search query")),
                required = listOf("url_or_query")
            )
        ),
        ToolDeclaration(
            name = "browser_click",
            description = "Click a button or link in the integrated browser by CSS selector or visible text.",
            parametersSchema = schema(
                props = mapOf("target" to prop("string", "CSS selector or visible button/link text")),
                required = listOf("target")
            )
        ),
        ToolDeclaration(
            name = "browser_fill",
            description = "Fill a form input field in the integrated browser. Requires Level 2 confirmation.",
            parametersSchema = schema(
                props = mapOf(
                    "field" to prop("string", "CSS selector, input name, id, or placeholder"),
                    "value" to prop("string", "Text value to type")
                ),
                required = listOf("field", "value")
            )
        ),
        ToolDeclaration(
            name = "browser_download",
            description = "Download a public file URL into virtual_env/downloads/. Requires Level 2 confirmation.",
            parametersSchema = schema(
                props = mapOf(
                    "url" to prop("string", "HTTPS URL to download"),
                    "filename" to prop("string", "Optional target filename")
                ),
                required = listOf("url")
            )
        ),
        ToolDeclaration(
            name = "app_list",
            description = "List launchable Android apps installed on the device so Virtual Hands can open and automate them.",
            parametersSchema = schema(
                props = mapOf("filter" to prop("string", "Optional keyword filter by app name or package")),
                required = emptyList()
            )
        ),
        ToolDeclaration(
            name = "app_launch",
            description = "Launch an installed Android app by display name (e.g., 'Chrome', 'WhatsApp', 'YouTube') or package name using Virtual Hands.",
            parametersSchema = schema(
                props = mapOf("app_name_or_package" to prop("string", "App name or Android package ID")),
                required = listOf("app_name_or_package")
            )
        ),
        ToolDeclaration(
            name = "device_screen_inspect",
            description = "Inspect the current Android screen UI hierarchy (text, descriptions, clickable/editable buttons, center coordinates) via Accessibility Virtual Hands.",
            parametersSchema = schema(props = emptyMap(), required = emptyList())
        ),
        ToolDeclaration(
            name = "device_virtual_hand",
            description = "Perform any Android Accessibility Virtual Hand action on the device screen (tap, double_tap, long_press, swipe, scroll_up, scroll_down, scroll_left, scroll_right, click_text, type, type_focused, launch_app, open_url, back, home, recents, notifications, quick_settings, screenshot, wait_ms). Requires Level 2 confirmation.",
            parametersSchema = schema(
                props = mapOf(
                    "action" to prop("string", "Action: tap, double_tap, long_press, swipe, scroll_up, scroll_down, scroll_left, scroll_right, click_text, type, type_focused, launch_app, open_url, back, home, recents, notifications, quick_settings, screenshot, wait_ms"),
                    "arg1" to prop("string", "Primary arg: X coordinate, text/viewId query, app name, URL, wait duration ms, or swipe coords"),
                    "arg2" to prop("string", "Secondary arg: Y coordinate, text to type, or swipe target")
                ),
                required = listOf("action")
            )
        ),
        ToolDeclaration(
            name = "request_human_intervention",
            description = "CRITICAL: Call this whenever the agent encounters CAPTCHA, OTP, 2FA, login wall, biometric check, or any step it cannot complete autonomously. Pauses the agent cleanly, pops the Live Screen up inside the Agent Chat Box for the user to solve, and resumes from this exact step once the user taps Resume.",
            parametersSchema = schema(
                props = mapOf(
                    "challenge_type" to prop("string", "e.g., CAPTCHA, OTP, Login Verification, Manual Check"),
                    "reason" to prop("string", "Short description of why agent paused"),
                    "target_mode" to prop("string", "'BROWSER' if inside integrated WebView or 'VIRTUAL_HANDS' if inside another Android app"),
                    "instruction_for_user" to prop("string", "Clear Bangla/English instruction telling the user what to solve before tapping Resume")
                ),
                required = listOf("challenge_type", "reason", "target_mode", "instruction_for_user")
            )
        ),
        ToolDeclaration(
            name = "memory_read",
            description = "Read saved Agent Memory entries (User Profile, Preferences, Instructions, Project Info).",
            parametersSchema = schema(props = emptyMap(), required = emptyList())
        ),
        ToolDeclaration(
            name = "memory_save",
            description = "Save non-sensitive context or preferences to Agent Memory.",
            parametersSchema = schema(
                props = mapOf(
                    "category" to prop("string", "Category: User Profile, User Preferences, Working Context, Saved Instructions, Project Information"),
                    "key" to prop("string", "Short title/key"),
                    "content" to prop("string", "Memory content (no passwords, OTPs, or API keys)")
                ),
                required = listOf("category", "key", "content")
            )
        ),
        ToolDeclaration(
            name = "local_server_control",
            description = "Start or stop the local HTTP web server serving virtual_env/projects on 127.0.0.1:8080.",
            parametersSchema = schema(
                props = mapOf("action" to prop("string", "'start' or 'stop'")),
                required = listOf("action")
            )
        ),
        ToolDeclaration(
            name = "schedule_automation",
            description = "Register a recurring or one-time local automation task in Automation Manager. Requires Level 2 confirmation.",
            parametersSchema = schema(
                props = mapOf(
                    "title" to prop("string", "Automation title"),
                    "prompt" to prop("string", "Task instruction to run"),
                    "schedule_type" to prop("string", "ONE_TIME, DAILY, WEEKLY, MONTHLY"),
                    "hour" to prop("integer", "Hour 0-23"),
                    "minute" to prop("integer", "Minute 0-59")
                ),
                required = listOf("title", "prompt", "schedule_type")
            )
        ),
        ToolDeclaration(
            name = "youtube_prepare_package",
            description = "Generate and save a complete YouTube production package (script, outline, title, description, tags, metadata JSON) into projects/youtube/.",
            parametersSchema = schema(
                props = mapOf(
                    "slug" to prop("string", "Short folder-safe slug"),
                    "title" to prop("string", "Video title"),
                    "description" to prop("string", "Video description"),
                    "tags" to prop("string", "Comma-separated tags"),
                    "script" to prop("string", "Full video script and scene outline")
                ),
                required = listOf("slug", "title", "description", "script")
            )
        )
    )

    fun evaluatePermission(toolName: String, args: JSONObject): PendingPermissionRequest? {
        return when (toolName) {
            "shell_exec" -> {
                val cmd = args.optString("command")
                when (val check = CommandSecurityGuard.validateShellCommand(cmd)) {
                    is CommandValidationResult.Blocked -> PendingPermissionRequest(
                        toolName, PermissionLevel.RESTRICTED, check.reason, cmd
                    )
                    is CommandValidationResult.RequiresConfirmation -> PendingPermissionRequest(
                        toolName, PermissionLevel.CONFIRMATION_REQUIRED, check.reason, cmd
                    )
                    CommandValidationResult.Allowed -> null
                }
            }
            "file_delete" -> PendingPermissionRequest(
                toolName,
                PermissionLevel.CONFIRMATION_REQUIRED,
                "Confirm deleting workspace file '${args.optString("path")}'",
                args.toString(2)
            )
            "browser_fill" -> PendingPermissionRequest(
                toolName,
                PermissionLevel.CONFIRMATION_REQUIRED,
                "Confirm entering text into webpage field '${args.optString("field")}'",
                args.toString(2)
            )
            "browser_download" -> PendingPermissionRequest(
                toolName,
                PermissionLevel.CONFIRMATION_REQUIRED,
                "Confirm downloading file from ${args.optString("url")}",
                args.toString(2)
            )
            "app_launch" -> PendingPermissionRequest(
                toolName,
                PermissionLevel.CONFIRMATION_REQUIRED,
                "Confirm opening Android app '${args.optString("app_name_or_package")}' via Virtual Hands",
                args.toString(2)
            )
            "device_virtual_hand" -> PendingPermissionRequest(
                toolName,
                PermissionLevel.CONFIRMATION_REQUIRED,
                "Confirm Virtual Hand action '${args.optString("action")}' on Android screen",
                args.toString(2)
            )
            "schedule_automation" -> PendingPermissionRequest(
                toolName,
                PermissionLevel.CONFIRMATION_REQUIRED,
                "Confirm creating ${args.optString("schedule_type")} automation '${args.optString("title")}'",
                args.toString(2)
            )
            else -> null
        }
    }

    suspend fun execute(toolName: String, args: JSONObject, approvedLevel2: Boolean): ToolExecutionOutcome {
        return when (toolName) {
            "shell_exec" -> {
                val cmd = args.optString("command")
                val res = linuxEnv.executeCommand(cmd, confirmedLevel2 = approvedLevel2)
                if (res.blockedReason != null) {
                    ToolExecutionOutcome(toolName, false, res.blockedReason, verified = true)
                } else {
                    val body = buildString {
                        append("exit=${res.exitCode} pwd=${res.workingDirRelative}")
                        if (res.stdout.isNotBlank()) append("\nSTDOUT:\n${res.stdout}")
                        if (res.stderr.isNotBlank()) append("\nSTDERR:\n${res.stderr}")
                    }
                    ToolExecutionOutcome(toolName, res.exitCode == 0, body, verified = true)
                }
            }
            "python_exec" -> {
                val code = args.optString("code")
                val res = pythonEngine.executeScript(code)
                val out = buildString {
                    append("Engine: ${res.engineLabel} (${res.durationMs}ms)")
                    if (res.stdout.isNotBlank()) append("\nSTDOUT:\n${res.stdout}")
                    if (res.stderr.isNotBlank()) append("\nSTDERR:\n${res.stderr}")
                }
                ToolExecutionOutcome(toolName, res.success, out, verified = true)
            }
            "js_exec" -> {
                val code = args.optString("code")
                val isTs = args.optBoolean("typescript", false)
                val res = jsEngine.executeJs(code, isTypeScript = isTs)
                val out = buildString {
                    append("Engine: ${res.engineLabel} (${res.durationMs}ms)")
                    if (res.stdout.isNotBlank()) append("\nSTDOUT:\n${res.stdout}")
                    if (res.stderr.isNotBlank()) append("\nSTDERR:\n${res.stderr}")
                }
                ToolExecutionOutcome(toolName, res.success, out, verified = true)
            }
            "file_list" -> {
                val path = args.optString("path", "")
                val items = workspace.listDirectory(path)
                val listing = if (items.isEmpty()) "(empty directory)"
                else items.joinToString("\n") {
                    val kind = if (it.isDirectory) "[DIR]" else "[FILE ${it.sizeBytes}B]"
                    "$kind ${it.relativePath}"
                }
                ToolExecutionOutcome(toolName, true, listing, verified = true)
            }
            "file_read" -> {
                val path = args.optString("path")
                val res = workspace.readTextFile(path)
                ToolExecutionOutcome(
                    toolName,
                    res.isSuccess,
                    res.getOrElse { "Error: ${it.message}" },
                    verified = true
                )
            }
            "file_write" -> {
                val path = args.optString("path")
                val content = args.optString("content")
                val res = workspace.writeTextFile(path, content)
                ToolExecutionOutcome(
                    toolName,
                    res.isSuccess,
                    res.fold(
                        onSuccess = { "Saved ${content.length} chars to ${workspace.relativePathOf(it)}" },
                        onFailure = { "Error: ${it.message}" }
                    ),
                    verified = true
                )
            }
            "file_delete" -> {
                val path = args.optString("path")
                val res = workspace.deleteFile(path)
                ToolExecutionOutcome(
                    toolName,
                    res.isSuccess,
                    res.fold(onSuccess = { "Deleted $path" }, onFailure = { "Error: ${it.message}" }),
                    verified = true
                )
            }
            "browser_open_and_read" -> {
                val query = args.optString("url_or_query")
                val res = browser.navigateAndWait(query)
                res.fold(
                    onSuccess = { snap ->
                        val summary = """
                            Title: ${snap.title}
                            URL: ${snap.url}
                            Page Text:
                            ${snap.textContent.take(3200)}
                            Links:
                            ${snap.linksSummary.take(1000)}
                            Forms: ${snap.formsSummary}
                        """.trimIndent()
                        val challenge = CommandSecurityGuard.detectHumanChallenge(snap.textContent, snap.url)
                        val intervention = challenge?.let { (cType, cReason) ->
                            HumanInterventionRequest(
                                challengeType = cType,
                                reason = cReason,
                                targetMode = "BROWSER",
                                urlOrPackage = snap.url,
                                instructionForUser = "উপরের লাইভ ব্রাউজার স্ক্রিনে $cType সল্ভ করে 'সল্ভ সম্পন্ন — কাজ শুরু করো' বাটনে ট্যাপ করুন।"
                            )
                        }
                        ToolExecutionOutcome(toolName, true, summary, verified = true, interventionRequest = intervention)
                    },
                    onFailure = { err ->
                        ToolExecutionOutcome(toolName, false, "Browser error: ${err.message}", verified = true)
                    }
                )
            }
            "browser_click" -> {
                val target = args.optString("target")
                val res = browser.clickElement(target)
                ToolExecutionOutcome(toolName, res.isSuccess, res.getOrElse { it.message ?: "Click failed" })
            }
            "browser_fill" -> {
                val field = args.optString("field")
                val value = args.optString("value")
                val res = browser.fillInput(field, value)
                ToolExecutionOutcome(toolName, res.isSuccess, res.getOrElse { it.message ?: "Fill failed" })
            }
            "browser_download" -> {
                val url = args.optString("url")
                val filename = args.optString("filename").takeIf { it.isNotBlank() }
                val res = browser.downloadFileToWorkspace(url, filename)
                ToolExecutionOutcome(toolName, res.isSuccess, res.getOrElse { it.message ?: "Download failed" })
            }
            "app_list" -> {
                val filter = args.optString("filter").trim()
                val apps = deviceController.listLaunchableApps()
                    .filter { filter.isBlank() || it.appName.contains(filter, true) || it.packageName.contains(filter, true) }
                    .take(60)
                val out = if (apps.isEmpty()) "No matching launchable apps found."
                else apps.joinToString("\n") { "${it.appName} -> ${it.packageName} (v${it.versionName})" }
                ToolExecutionOutcome(toolName, true, out, verified = true)
            }
            "app_launch" -> {
                val query = args.optString("app_name_or_package")
                val res = deviceController.launchApp(query)
                ToolExecutionOutcome(toolName, res.isSuccess, res.getOrElse { it.message ?: "Launch failed" })
            }
            "device_screen_inspect" -> {
                val res = deviceController.inspectScreen()
                res.fold(
                    onSuccess = { nodes ->
                        val pkg = deviceController.activePackage.value
                        val text = if (nodes.isEmpty()) "Active package: $pkg\nNo accessible nodes detected"
                        else "Active package: $pkg\n" + nodes.joinToString("\n") { n ->
                            "${n.className} text='${n.text}' desc='${n.contentDesc}' id='${n.viewId}' center=(${n.centerX.toInt()},${n.centerY.toInt()}) clickable=${n.clickable} editable=${n.editable}"
                        }
                        val challenge = CommandSecurityGuard.detectHumanChallenge(text, pkg)
                        val intervention = challenge?.let { (cType, cReason) ->
                            HumanInterventionRequest(
                                challengeType = cType,
                                reason = cReason,
                                targetMode = "VIRTUAL_HANDS",
                                urlOrPackage = pkg,
                                instructionForUser = "$pkg অ্যাপে $cType দেখা গেছে। অ্যাপটি ওপেন করে সল্ভ করুন, তারপর চ্যাট বক্সে Resume দিন।"
                            )
                        }
                        ToolExecutionOutcome(toolName, true, text, verified = true, interventionRequest = intervention)
                    },
                    onFailure = { ToolExecutionOutcome(toolName, false, it.message ?: "Accessibility unavailable") }
                )
            }
            "device_virtual_hand" -> {
                val action = args.optString("action").lowercase()
                val arg1 = args.optString("arg1")
                val arg2 = args.optString("arg2")
                if (action == "wait_ms") {
                    val ms = (arg1.toLongOrNull() ?: 1200L).coerceIn(200L, 10_000L)
                    delay(ms)
                    return ToolExecutionOutcome(toolName, true, "Waited ${ms}ms for UI state settlement", verified = true)
                }
                val res = when (action) {
                    "tap" -> deviceController.tap(arg1.toFloatOrNull() ?: 500f, arg2.toFloatOrNull() ?: 500f)
                    "double_tap" -> deviceController.doubleTap(arg1.toFloatOrNull() ?: 500f, arg2.toFloatOrNull() ?: 500f)
                    "long_press" -> deviceController.longPress(arg1.toFloatOrNull() ?: 500f, arg2.toFloatOrNull() ?: 500f)
                    "swipe" -> {
                        val parts1 = arg1.split(",")
                        val parts2 = arg2.split(",")
                        val x1 = parts1.getOrNull(0)?.trim()?.toFloatOrNull() ?: 500f
                        val y1 = parts1.getOrNull(1)?.trim()?.toFloatOrNull() ?: 1400f
                        val x2 = parts2.getOrNull(0)?.trim()?.toFloatOrNull() ?: 500f
                        val y2 = parts2.getOrNull(1)?.trim()?.toFloatOrNull() ?: 400f
                        deviceController.swipe(x1, y1, x2, y2)
                    }
                    "scroll_down" -> deviceController.scroll("down")
                    "scroll_up" -> deviceController.scroll("up")
                    "scroll_left" -> deviceController.scroll("left")
                    "scroll_right" -> deviceController.scroll("right")
                    "click_text" -> deviceController.clickText(arg1)
                    "type" -> deviceController.typeIntoField(arg1, arg2)
                    "type_focused" -> deviceController.typeIntoField("", arg1.ifBlank { arg2 })
                    "launch_app" -> deviceController.launchApp(arg1)
                    "open_url" -> deviceController.openExternalUrl(arg1)
                    "back", "home", "recents", "notifications", "quick_settings", "screenshot" -> deviceController.globalNav(action)
                    else -> Result.failure(IllegalArgumentException("Unsupported action: $action"))
                }
                ToolExecutionOutcome(toolName, res.isSuccess, res.getOrElse { it.message ?: "Gesture failed" })
            }
            "request_human_intervention" -> {
                val cType = args.optString("challenge_type", "Manual Verification")
                val reason = args.optString("reason", "AI encountered a step requiring user interaction.")
                val mode = args.optString("target_mode", "BROWSER").uppercase()
                val instruction = args.optString("instruction_for_user", "লাইভ স্ক্রিনে ধাপটি সম্পন্ন করে Resume দিন।")
                val targetRef = if (mode == "BROWSER") browser.getActiveTab().url else deviceController.activePackage.value
                ToolExecutionOutcome(
                    toolName = toolName,
                    success = true,
                    output = "Paused for Human Intervention ($cType): $reason",
                    verified = true,
                    interventionRequest = HumanInterventionRequest(
                        challengeType = cType,
                        reason = reason,
                        targetMode = if (mode == "VIRTUAL_HANDS") "VIRTUAL_HANDS" else "BROWSER",
                        urlOrPackage = targetRef,
                        instructionForUser = instruction
                    )
                )
            }
            "memory_read" -> {
                val memories = repository.getMemorySnapshot()
                val text = if (memories.isEmpty()) "Agent Memory is currently empty."
                else memories.joinToString("\n") { "[${it.category}] ${it.memoryKey}: ${it.content}" }
                ToolExecutionOutcome(toolName, true, text, verified = true)
            }
            "memory_save" -> {
                val cat = args.optString("category", MemoryCategory.WORKING_CONTEXT)
                val key = args.optString("key")
                val content = args.optString("content")
                val res = repository.saveMemory(cat, key, content)
                ToolExecutionOutcome(
                    toolName,
                    res.isSuccess,
                    res.fold(onSuccess = { "Saved memory '$key' under $cat" }, onFailure = { it.message ?: "Blocked" })
                )
            }
            "local_server_control" -> {
                val action = args.optString("action").lowercase()
                if (action == "start") {
                    val st = webServer.start(8080)
                    ToolExecutionOutcome(toolName, st.running, "Local dev server at ${st.url}")
                } else {
                    webServer.stop()
                    ToolExecutionOutcome(toolName, true, "Local dev server stopped.")
                }
            }
            "schedule_automation" -> {
                val rule = AutomationRuleEntity(
                    title = args.optString("title"),
                    prompt = args.optString("prompt"),
                    scheduleType = args.optString("schedule_type", "DAILY"),
                    hourOfDay = args.optInt("hour", 9),
                    minuteOfHour = args.optInt("minute", 0)
                )
                val id = repository.saveAutomationRule(rule)
                ToolExecutionOutcome(toolName, true, "Saved automation rule #$id (${rule.title} • ${rule.scheduleType})")
            }
            "youtube_prepare_package" -> {
                val slug = args.optString("slug", "video_project").replace(Regex("[^a-zA-Z0-9_-]"), "_")
                val title = args.optString("title")
                val desc = args.optString("description")
                val tags = args.optString("tags")
                val script = args.optString("script")
                val meta = JSONObject()
                    .put("title", title)
                    .put("description", desc)
                    .put("tags", tags.split(",").map { it.trim() }.filter { it.isNotEmpty() })
                    .put("upload_status", "Prepared locally. Connect YouTube OAuth in AI Settings & confirm Level 2 upload before publishing.")
                    .toString(2)
                workspace.writeTextFile("projects/youtube/$slug/metadata.json", meta)
                workspace.writeTextFile("projects/youtube/$slug/script.md", "# $title\n\n$script")
                ToolExecutionOutcome(
                    toolName,
                    true,
                    "Created YouTube production package at projects/youtube/$slug/ (metadata.json & script.md).",
                    verified = true
                )
            }
            else -> ToolExecutionOutcome(toolName, false, "Unknown tool: $toolName")
        }
    }

    private fun schema(props: Map<String, JSONObject>, required: List<String>): JSONObject {
        val properties = JSONObject()
        props.forEach { (k, v) -> properties.put(k, v) }
        val reqArr = JSONArray()
        required.forEach { reqArr.put(it) }
        return JSONObject()
            .put("type", "object")
            .put("properties", properties)
            .put("required", reqArr)
    }

    private fun prop(type: String, description: String): JSONObject =
        JSONObject().put("type", type).put("description", description)
}
