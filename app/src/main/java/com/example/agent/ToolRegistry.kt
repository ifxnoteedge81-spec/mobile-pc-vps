package com.example.agent

import com.example.accessibility.DeviceAutomationController
import com.example.ai.ToolDeclaration
import com.example.browser.BrowserController
import com.example.resources.DeviceResourceManager
import com.example.runtime.CadDrawingEngine
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
    val interventionRequest: HumanInterventionRequest? = null,
    val actionUrl: String? = null,
    val shareRelativePath: String? = null,
    val shareSecondaryPath: String? = null
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
    private val cadEngine: CadDrawingEngine,
    private val resourceManager: DeviceResourceManager,
    private val browser: BrowserController,
    private val deviceController: DeviceAutomationController,
    private val repository: AgentRepository,
    private val skillManager: UniversalSkillManager
) {

    fun getDeclarations(): List<ToolDeclaration> = listOf(
        ToolDeclaration(
            name = "shell_exec",
            description = "Execute a POSIX shell command inside sandboxed virtual_env workspace.",
            parametersSchema = schema(
                props = mapOf("command" to prop("string", "Shell command, e.g. 'ls -la'")),
                required = listOf("command")
            )
        ),
        ToolDeclaration(
            name = "python_exec",
            description = "Execute Python 3 code on-device and capture stdout/stderr.",
            parametersSchema = schema(
                props = mapOf("code" to prop("string", "Python 3 code to run")),
                required = listOf("code")
            )
        ),
        ToolDeclaration(
            name = "js_exec",
            description = "Execute JavaScript or TypeScript on Android V8 engine.",
            parametersSchema = schema(
                props = mapOf(
                    "code" to prop("string", "JS or TS code"),
                    "typescript" to prop("boolean", "True if TypeScript")
                ),
                required = listOf("code")
            )
        ),
        ToolDeclaration(
            name = "file_list",
            description = "List files in virtual_env ('', 'workspace', 'uploads', 'scripts', 'projects', 'downloads').",
            parametersSchema = schema(
                props = mapOf("path" to prop("string", "Relative directory path")),
                required = emptyList()
            )
        ),
        ToolDeclaration(
            name = "file_read",
            description = "Read and extract readable text from any file in virtual_env (supports .pdf, .docx, .txt, .md, .csv, .json, and source files, including uploads/).",
            parametersSchema = schema(
                props = mapOf("path" to prop("string", "Relative file path")),
                required = listOf("path")
            )
        ),
        ToolDeclaration(
            name = "file_write",
            description = "Write or update a text file inside virtual_env.",
            parametersSchema = schema(
                props = mapOf(
                    "path" to prop("string", "Relative file path"),
                    "content" to prop("string", "File text content")
                ),
                required = listOf("path", "content")
            )
        ),
        ToolDeclaration(
            name = "file_delete",
            description = "Delete a file in virtual_env.",
            parametersSchema = schema(
                props = mapOf("path" to prop("string", "Relative file path")),
                required = listOf("path")
            )
        ),
        ToolDeclaration(
            name = "http_request",
            description = "Fast direct HTTPS GET/POST/PUT/PATCH/DELETE request for APIs, GitHub raw files, JSON, or web pages with optional custom headers.",
            parametersSchema = schema(
                props = mapOf(
                    "url" to prop("string", "Full https:// URL"),
                    "method" to prop("string", "GET, POST, PUT, PATCH, DELETE, or HEAD"),
                    "body" to prop("string", "Optional JSON/text request body"),
                    "headers" to prop("string", "Optional JSON object string of custom headers, e.g. '{\"Authorization\":\"Bearer ...\",\"Accept\":\"application/json\"}'"),
                    "content_type" to prop("string", "Optional Content-Type header, default application/json")
                ),
                required = listOf("url")
            )
        ),
        ToolDeclaration(
            name = "browser_open_and_read",
            description = "Open URL or search query in integrated Live WebView Browser and extract text, links, forms.",
            parametersSchema = schema(
                props = mapOf("url_or_query" to prop("string", "Full https:// URL or search query")),
                required = listOf("url_or_query")
            )
        ),
        ToolDeclaration(
            name = "browser_click",
            description = "Click a button or link in integrated Live Browser by CSS selector or text.",
            parametersSchema = schema(
                props = mapOf("target" to prop("string", "CSS selector or visible button/link text")),
                required = listOf("target")
            )
        ),
        ToolDeclaration(
            name = "browser_fill",
            description = "Fill an input field in integrated Live Browser.",
            parametersSchema = schema(
                props = mapOf(
                    "field" to prop("string", "CSS selector, name, id, or placeholder"),
                    "value" to prop("string", "Text to type")
                ),
                required = listOf("field", "value")
            )
        ),
        ToolDeclaration(
            name = "browser_scroll",
            description = "Scroll or navigate back in the integrated Live Browser.",
            parametersSchema = schema(
                props = mapOf("direction" to prop("string", "'down', 'up', or 'back'")),
                required = listOf("direction")
            )
        ),
        ToolDeclaration(
            name = "browser_eval_js",
            description = "Run JavaScript inside the integrated Live Browser DOM to inspect or automate complex pages.",
            parametersSchema = schema(
                props = mapOf("script" to prop("string", "JavaScript snippet to evaluate")),
                required = listOf("script")
            )
        ),
        ToolDeclaration(
            name = "browser_download",
            description = "Download a public file URL into virtual_env/downloads/.",
            parametersSchema = schema(
                props = mapOf(
                    "url" to prop("string", "HTTPS URL"),
                    "filename" to prop("string", "Optional target filename")
                ),
                required = listOf("url")
            )
        ),
        ToolDeclaration(
            name = "app_list",
            description = "List launchable Android apps installed on the device.",
            parametersSchema = schema(
                props = mapOf("filter" to prop("string", "Optional filter keyword")),
                required = emptyList()
            )
        ),
        ToolDeclaration(
            name = "app_launch",
            description = "Launch an installed Android app by display name or package ID.",
            parametersSchema = schema(
                props = mapOf("app_name_or_package" to prop("string", "App name or package ID")),
                required = listOf("app_name_or_package")
            )
        ),
        ToolDeclaration(
            name = "app_install_store",
            description = "Open Google Play Store to install a required Android app when it is not yet installed.",
            parametersSchema = schema(
                props = mapOf("app_name_or_package" to prop("string", "App name or Play Store package ID")),
                required = listOf("app_name_or_package")
            )
        ),
        ToolDeclaration(
            name = "device_screen_inspect",
            description = "Inspect current Android screen UI elements, coordinates, and screen size via Accessibility Virtual Hands.",
            parametersSchema = schema(
                props = mapOf("reason" to prop("string", "Optional brief reason for inspection")),
                required = emptyList()
            )
        ),
        ToolDeclaration(
            name = "device_virtual_hand",
            description = "Control any Android screen via Virtual Hands: tap, double_tap, long_press, swipe, scroll_up, scroll_down, scroll_left, scroll_right, scroll_to_text, click_text, type, type_focused, press_enter, launch_app, open_url, back, home, recents, notifications, quick_settings, screenshot, wait_ms, wait_and_inspect.",
            parametersSchema = schema(
                props = mapOf(
                    "action" to prop("string", "Action name"),
                    "arg1" to prop("string", "X coord, text/viewId query, app name, URL, wait ms, or 'x1,y1'"),
                    "arg2" to prop("string", "Y coord, text to type, or 'x2,y2'")
                ),
                required = listOf("action")
            )
        ),
        ToolDeclaration(
            name = "device_clipboard",
            description = "Read or copy text to the Android clipboard for cross-app pasting.",
            parametersSchema = schema(
                props = mapOf(
                    "mode" to prop("string", "'get' or 'set'"),
                    "text" to prop("string", "Text to copy when mode='set'")
                ),
                required = listOf("mode")
            )
        ),
        ToolDeclaration(
            name = "device_intent_action",
            description = "Open Android Share sheet, Dialer, Email compose, or external URL intent.",
            parametersSchema = schema(
                props = mapOf(
                    "action_type" to prop("string", "'share', 'dial', 'email', or 'url'"),
                    "target" to prop("string", "Phone number, email, URL, or share subject"),
                    "body" to prop("string", "Optional text body")
                ),
                required = listOf("action_type", "target")
            )
        ),
        ToolDeclaration(
            name = "request_human_intervention",
            description = "Pause immediately and open Live Screen inside the Agent Chat Box when CAPTCHA, OTP, 2FA, login wall, or manual verification appears. Resumes from this step when user solves it.",
            parametersSchema = schema(
                props = mapOf(
                    "challenge_type" to prop("string", "e.g. CAPTCHA, OTP, Login Verification"),
                    "reason" to prop("string", "Short description"),
                    "target_mode" to prop("string", "'BROWSER' or 'VIRTUAL_HANDS'"),
                    "instruction_for_user" to prop("string", "Instruction for user before tapping Resume")
                ),
                required = listOf("challenge_type", "reason", "target_mode", "instruction_for_user")
            )
        ),
        ToolDeclaration(
            name = "memory_read",
            description = "Read saved persistent Agent Memory entries.",
            parametersSchema = schema(
                props = mapOf("category" to prop("string", "Optional category filter")),
                required = emptyList()
            )
        ),
        ToolDeclaration(
            name = "memory_save",
            description = "Save important user preferences, workflow context, or facts to permanent Agent Memory.",
            parametersSchema = schema(
                props = mapOf(
                    "category" to prop("string", "User Profile, User Preferences, Working Context, Saved Instructions, Project Information, Task History"),
                    "key" to prop("string", "Short key/title"),
                    "content" to prop("string", "Memory content (never save passwords/OTPs/API keys)")
                ),
                required = listOf("category", "key", "content")
            )
        ),
        ToolDeclaration(
            name = "local_server_control",
            description = "Start or stop local HTTP dev server for virtual_env/projects on 127.0.0.1:8080.",
            parametersSchema = schema(
                props = mapOf("action" to prop("string", "'start' or 'stop'")),
                required = listOf("action")
            )
        ),
        ToolDeclaration(
            name = "schedule_automation",
            description = "Create a recurring or one-time background automation rule.",
            parametersSchema = schema(
                props = mapOf(
                    "title" to prop("string", "Rule title"),
                    "prompt" to prop("string", "Task prompt"),
                    "schedule_type" to prop("string", "ONE_TIME, DAILY, WEEKLY, MONTHLY"),
                    "hour" to prop("integer", "Hour 0-23"),
                    "minute" to prop("integer", "Minute 0-59")
                ),
                required = listOf("title", "prompt", "schedule_type")
            )
        ),
        ToolDeclaration(
            name = "youtube_prepare_package",
            description = "Generate and save a YouTube video production package into projects/youtube/.",
            parametersSchema = schema(
                props = mapOf(
                    "slug" to prop("string", "Folder-safe slug"),
                    "title" to prop("string", "Video title"),
                    "description" to prop("string", "Video description"),
                    "tags" to prop("string", "Comma-separated tags"),
                    "script" to prop("string", "Video script & scenes")
                ),
                required = listOf("slug", "title", "description", "script")
            )
        ),
        ToolDeclaration(
            name = "skill_import_auto",
            description = "Auto-import and adapt ANY external skill (Anthropic SKILL.md, OpenClaw, MCP, Gemini, scripts, .zip bundles, GitHub URLs, or huge multi-thousand-word skill docs) into this agent permanently.",
            parametersSchema = schema(
                props = mapOf(
                    "source" to prop("string", "Relative virtual_env path (e.g. uploads/...), GitHub/HTTPS URL, or raw skill text"),
                    "title_hint" to prop("string", "Optional title for the skill")
                ),
                required = listOf("source")
            )
        ),
        ToolDeclaration(
            name = "skill_list",
            description = "List all installed built-in skills and auto-imported external skills (with slug, ecosystem, word count, and status).",
            parametersSchema = schema(
                props = mapOf("filter" to prop("string", "Optional keyword filter")),
                required = emptyList()
            )
        ),
        ToolDeclaration(
            name = "skill_load",
            description = "Load instructions, auto-adapted tool mappings, bundled files, and relevant sections or paginated chunks of an imported skill (handles massive 10,000+ line skills).",
            parametersSchema = schema(
                props = mapOf(
                    "slug" to prop("string", "Imported skill slug or title"),
                    "query" to prop("string", "Optional section/topic keyword to jump directly to matching sections"),
                    "offset" to prop("integer", "Optional character offset for paginated reading of huge skills")
                ),
                required = listOf("slug")
            )
        ),
        ToolDeclaration(
            name = "skill_delete",
            description = "Remove an imported custom skill by its slug.",
            parametersSchema = schema(
                props = mapOf("slug" to prop("string", "Skill slug to delete")),
                required = listOf("slug")
            )
        ),
        ToolDeclaration(
            name = "cad_generate_drawing",
            description = "Generate real AutoCAD ASCII DXF (.dxf), dimensioned SVG Blueprint (.svg), interactive HTML pan/zoom viewer, and optional 3D STL mesh (.stl) from custom geometry JSON entities (line, rect, circle, arc, polyline, text, dimension) or parametric templates (floor_plan_2bhk, mechanical_plate, spur_gear, l_bracket).",
            parametersSchema = schema(
                props = mapOf(
                    "slug" to prop("string", "Folder-safe project slug, e.g. 'house_plan_1'"),
                    "title" to prop("string", "Engineering drawing title"),
                    "units" to prop("string", "'mm', 'cm', 'm', 'in', or 'ft'"),
                    "entities_json" to prop("string", "JSON array of entities: [{type:'rect',x:0,y:0,w:100,h:80,layer:'WALLS'}, {type:'circle',cx:50,cy:40,r:10,layer:'HOLES'}, {type:'dimension',x1:0,y1:0,x2:100,y2:0,offset:-15,label:'100 mm'}, {type:'text',x:10,y:20,text:'ROOM',height:6}, {type:'line',...}, {type:'arc',...}, {type:'polyline',points:[[0,0],[10,10]],closed:true}]"),
                    "template" to prop("string", "Optional starter template: 'floor_plan', 'mechanical_plate', 'gear', or 'bracket'"),
                    "extrude_height_3d" to prop("number", "Optional >0 extrusion height to also export 3D .stl mesh")
                ),
                required = listOf("slug", "title")
            )
        ),
        ToolDeclaration(
            name = "workspace_grep",
            description = "Search text or patterns across all files inside virtual_env/ (projects, scripts, uploads, skills).",
            parametersSchema = schema(
                props = mapOf(
                    "query" to prop("string", "Case-insensitive search keyword or substring"),
                    "subdir" to prop("string", "Optional relative folder like 'projects' or 'skills'")
                ),
                required = listOf("query")
            )
        ),
        ToolDeclaration(
            name = "system_hardware_status",
            description = "Inspect live Android device capabilities: physical/free RAM, custom RAM budget, device/workspace ROM storage & quota, CPU cores/ABI, device performance tier, and active API pool.",
            parametersSchema = schema(
                props = mapOf("detail" to prop("string", "Optional 'summary' or 'full'")),
                required = emptyList()
            )
        )
    )

    fun getDeclarationsForPrompt(
        userPrompt: String,
        hasAttachments: Boolean = false,
        hasImportedSkills: Boolean = false
    ): List<ToolDeclaration> {
        val all = getDeclarations()
        val lower = userPrompt.lowercase()

        // If user gives an open-ended "do anything / super autonomous" request, expose every tool
        if (lower.contains("super autonomous") || lower.contains("সবকিছু") || lower.contains("all tools")) {
            return all
        }

        val coreNames = linkedSetOf(
            "file_list", "file_read", "file_write", "file_delete", "workspace_grep",
            "http_request", "python_exec", "shell_exec",
            "memory_read", "memory_save", "system_hardware_status"
        )

        var matchedDomainCount = 0

        val wantsWeb = listOf(
            "browser", "web", "http", "url", "search", "google", "site", "portal",
            "job", "apply", "remotive", "linkedin", "bdjobs", "html", "download", "server",
            "ব্রাউজার", "ওয়েব", "সার্চ", "চাকরি", "জব", "এপ্লাই", "ডাউনলোড", "লিংক", "ওয়েবসাইট"
        ).any { lower.contains(it) }
        if (wantsWeb) {
            matchedDomainCount++
            coreNames.addAll(
                listOf(
                    "browser_open_and_read", "browser_click", "browser_fill",
                    "browser_scroll", "browser_eval_js", "browser_download",
                    "request_human_intervention", "local_server_control"
                )
            )
        }

        val wantsVirtualHands = listOf(
            "virtual hand", "app", "screen", "tap", "swipe", "scroll", "otp", "captcha",
            "play store", "install", "launch", "share", "email", "dial", "call", "clipboard",
            "অ্যাপ", "স্ক্রিন", "ইনস্টল", "শেয়ার", "ইমেইল", "ওটিপি", "ক্যাপচা", "কল"
        ).any { lower.contains(it) }
        if (wantsVirtualHands) {
            matchedDomainCount++
            coreNames.addAll(
                listOf(
                    "app_list", "app_launch", "app_install_store",
                    "device_screen_inspect", "device_virtual_hand",
                    "device_clipboard", "device_intent_action", "request_human_intervention"
                )
            )
        }

        val wantsCad = listOf(
            "cad", "autocad", "dxf", "stl", "svg", "blueprint", "floor plan", "drawing",
            "3d", "bracket", "gear", "architect", "mechanical", "civil", "engineering design",
            "ড্রয়িং", "ফ্লোর প্ল্যান", "ব্লুপ্রিন্ট", "নকশা", "ডিজাইন"
        ).any { lower.contains(it) }
        if (wantsCad) {
            matchedDomainCount++
            coreNames.addAll(
                listOf(
                    "cad_generate_drawing", "local_server_control",
                    "browser_open_and_read", "device_intent_action"
                )
            )
        }

        val wantsJs = listOf("javascript", "typescript", "node", "js", "ts", "v8", "react").any { lower.contains(it) }
        if (wantsJs) {
            matchedDomainCount++
            coreNames.add("js_exec")
        }

        val wantsSkills = hasImportedSkills || hasAttachments || listOf(
            "skill", "import", "anthropic", "claude", "openclaw", "mcp", "github", "স্কিল", "ইমপোর্ট"
        ).any { lower.contains(it) }
        if (wantsSkills) {
            if (!hasImportedSkills) matchedDomainCount++
            coreNames.addAll(listOf("skill_import_auto", "skill_list", "skill_load", "skill_delete"))
        }

        val wantsScheduleOrMedia = listOf(
            "schedule", "daily", "weekly", "monthly", "cron", "remind", "youtube", "video",
            "শিডিউল", "প্রতিদিন", "ইউটিউব", "ভিডিও"
        ).any { lower.contains(it) }
        if (wantsScheduleOrMedia) {
            matchedDomainCount++
            coreNames.addAll(listOf("schedule_automation", "youtube_prepare_package"))
        }

        // If prompt is ambiguous or doesn't explicitly narrow to 1-2 domains, keep full toolset
        return if (matchedDomainCount == 0) all else all.filter { it.name in coreNames }
    }

    fun evaluatePermission(
        toolName: String,
        args: JSONObject,
        fullAutonomousMode: Boolean = true
    ): PendingPermissionRequest? {
        if (toolName == "shell_exec") {
            val cmd = args.optString("command")
            return when (val check = CommandSecurityGuard.validateShellCommand(cmd)) {
                is CommandValidationResult.Blocked -> PendingPermissionRequest(
                    toolName, PermissionLevel.RESTRICTED, check.reason, cmd
                )
                is CommandValidationResult.RequiresConfirmation -> {
                    if (fullAutonomousMode) null
                    else PendingPermissionRequest(toolName, PermissionLevel.CONFIRMATION_REQUIRED, check.reason, cmd)
                }
                CommandValidationResult.Allowed -> null
            }
        }

        // In Full Autonomous Mode, execute authorized tools directly without blocking popups
        if (fullAutonomousMode) return null

        return when (toolName) {
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
            "app_launch", "app_install_store" -> PendingPermissionRequest(
                toolName,
                PermissionLevel.CONFIRMATION_REQUIRED,
                "Confirm opening '${args.optString("app_name_or_package")}'",
                args.toString(2)
            )
            "device_virtual_hand" -> PendingPermissionRequest(
                toolName,
                PermissionLevel.CONFIRMATION_REQUIRED,
                "Confirm Virtual Hand action '${args.optString("action")}' on screen",
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
            "http_request" -> {
                val url = args.optString("url")
                val method = args.optString("method", "GET")
                val body = args.optString("body", "")
                val contentType = args.optString("content_type", "application/json")
                val parsedHeaders = parseHeadersArg(args.opt("headers"))
                val res = browser.fastHttpRequest(method, url, body, contentType, parsedHeaders)
                ToolExecutionOutcome(toolName, res.isSuccess, res.getOrElse { it.message ?: "HTTP failed" }, verified = true)
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
                            ${snap.textContent.take(3000)}
                            Links:
                            ${snap.linksSummary.take(900)}
                            Forms: ${snap.formsSummary}
                        """.trimIndent()
                        val challenge = CommandSecurityGuard.detectHumanChallenge(snap.textContent, snap.url)
                        val intervention = challenge?.let { (cType, cReason) ->
                            HumanInterventionRequest(
                                challengeType = cType,
                                reason = cReason,
                                targetMode = "BROWSER",
                                urlOrPackage = snap.url,
                                instructionForUser = "উপরের লাইভ ব্রাউজার স্ক্রিনে $cType সল্ভ করে 'সল্ভ সম্পন্ন' বাটনে ট্যাপ করুন।"
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
                delay(350)
                ToolExecutionOutcome(toolName, res.isSuccess, res.getOrElse { it.message ?: "Click failed" })
            }
            "browser_fill" -> {
                val field = args.optString("field")
                val value = args.optString("value")
                val res = browser.fillInput(field, value)
                ToolExecutionOutcome(toolName, res.isSuccess, res.getOrElse { it.message ?: "Fill failed" })
            }
            "browser_scroll" -> {
                val dir = args.optString("direction", "down").lowercase()
                val res = if (dir == "back") browser.goBack() else browser.scrollPage(dir)
                delay(300)
                ToolExecutionOutcome(toolName, res.isSuccess, res.getOrElse { it.message ?: "Browser scroll failed" })
            }
            "browser_eval_js" -> {
                val script = args.optString("script")
                val res = browser.evalCustomJs(script)
                ToolExecutionOutcome(toolName, res.isSuccess, res.getOrElse { it.message ?: "JS eval failed" })
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
                if (res.isSuccess) delay(650)
                ToolExecutionOutcome(toolName, res.isSuccess, res.getOrElse { it.message ?: "Launch failed" })
            }
            "app_install_store" -> {
                val query = args.optString("app_name_or_package")
                val res = deviceController.openPlayStoreForApp(query)
                if (res.isSuccess) delay(850)
                ToolExecutionOutcome(toolName, res.isSuccess, res.getOrElse { it.message ?: "Play Store launch failed" })
            }
            "device_screen_inspect" -> formatScreenInspectResult(toolName)
            "device_virtual_hand" -> {
                val action = args.optString("action").lowercase()
                val arg1 = args.optString("arg1")
                val arg2 = args.optString("arg2")
                if (action == "wait_ms") {
                    val ms = (arg1.toLongOrNull() ?: 1000L).coerceIn(150L, 10_000L)
                    delay(ms)
                    return ToolExecutionOutcome(toolName, true, "Waited ${ms}ms", verified = true)
                }
                if (action == "wait_and_inspect") {
                    val ms = (arg1.toLongOrNull() ?: 900L).coerceIn(200L, 8_000L)
                    delay(ms)
                    return formatScreenInspectResult(toolName)
                }
                if (action == "scroll_to_text") {
                    val res = deviceController.scrollToText(arg1)
                    return ToolExecutionOutcome(toolName, res.isSuccess, res.getOrElse { it.message ?: "Not found" })
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
                    "press_enter" -> deviceController.pressEnter()
                    "launch_app" -> deviceController.launchApp(arg1)
                    "open_url" -> deviceController.openExternalUrl(arg1)
                    "back", "home", "recents", "notifications", "quick_settings", "screenshot" -> deviceController.globalNav(action)
                    else -> Result.failure(IllegalArgumentException("Unsupported action: $action"))
                }
                if (res.isSuccess) delay(280)
                ToolExecutionOutcome(toolName, res.isSuccess, res.getOrElse { it.message ?: "Gesture failed" })
            }
            "device_clipboard" -> {
                val mode = args.optString("mode", "get").lowercase()
                val res = if (mode == "set") deviceController.setClipboardText(args.optString("text"))
                else deviceController.getClipboardText()
                ToolExecutionOutcome(toolName, res.isSuccess, res.getOrElse { it.message ?: "Clipboard error" })
            }
            "device_intent_action" -> {
                val res = deviceController.sendShareOrIntent(
                    args.optString("action_type"),
                    args.optString("target"),
                    args.optString("body")
                )
                ToolExecutionOutcome(toolName, res.isSuccess, res.getOrElse { it.message ?: "Intent failed" })
            }
            "request_human_intervention" -> {
                val cType = args.optString("challenge_type", "Manual Verification")
                val reason = args.optString("reason", "AI encountered a step requiring user interaction.")
                val mode = args.optString("target_mode", "BROWSER").uppercase()
                val instruction = args.optString("instruction_for_user", "লাইভ স্ক্রিনে ধাপটি সম্পন্ন করে 'সল্ভ সম্পন্ন' চাপুন।")
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
                val cat = args.optString("category", "").trim()
                val memories = repository.getMemorySnapshot()
                    .filter { cat.isBlank() || it.category.equals(cat, ignoreCase = true) }
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
                    .put("upload_status", "Prepared locally in projects/youtube/$slug/.")
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
            "skill_import_auto" -> {
                val source = args.optString("source")
                val hint = args.optString("title_hint", "")
                val res = skillManager.importAuto(source, hint)
                res.fold(
                    onSuccess = { sk ->
                        val summary = buildString {
                            append("Auto-imported & configured skill '${sk.title}' (slug: ${sk.slug})\n")
                            append("Detected Ecosystem: ${sk.ecosystem} | Size: ${sk.wordCount} words (${sk.lineCount} lines)\n")
                            append("Entry Path: ${sk.entryPath}\n")
                            if (sk.bundledFilesSummary.isNotBlank()) append("Bundled Files: ${sk.bundledFilesSummary}\n")
                            append("Adapted Tools:\n${sk.toolMappingSummary}")
                        }
                        ToolExecutionOutcome(toolName, true, summary, verified = true)
                    },
                    onFailure = { err ->
                        ToolExecutionOutcome(toolName, false, "Skill auto-import failed: ${err.message}", verified = true)
                    }
                )
            }
            "skill_list" -> {
                val filter = args.optString("filter", "").trim()
                val imported = repository.getAllImportedSkillsSnapshot()
                    .filter { filter.isBlank() || it.title.contains(filter, true) || it.slug.contains(filter, true) || it.ecosystem.contains(filter, true) }
                val builtIn = AgentSkillRegistry.skills
                    .filter { filter.isBlank() || it.title.contains(filter, true) || it.subtitle.contains(filter, true) }
                val out = buildString {
                    append("=== IMPORTED UNIVERSAL SKILLS (${imported.size}) ===\n")
                    if (imported.isEmpty()) {
                        append("(No external skills imported yet. Use skill_import_auto to absorb any skill file, ZIP, or GitHub URL.)\n")
                    } else {
                        imported.forEach { sk ->
                            append("- [${sk.slug}] ${sk.title} (${sk.ecosystem}, ${sk.wordCount}w/${sk.lineCount}L, enabled=${sk.enabled}): ${sk.description}\n")
                        }
                    }
                    append("\n=== BUILT-IN SKILLS (${builtIn.size}) ===\n")
                    builtIn.forEach { b ->
                        append("- ${b.title} [${b.category.title}]: ${b.subtitle}\n")
                    }
                }
                ToolExecutionOutcome(toolName, true, out, verified = true)
            }
            "skill_load" -> {
                val slug = args.optString("slug")
                val query = args.optString("query", "")
                val offset = args.optInt("offset", 0)
                val res = skillManager.loadSkillForAgent(slug, query, offset, maxChars = 5500)
                ToolExecutionOutcome(
                    toolName,
                    res.isSuccess,
                    res.getOrElse { it.message ?: "Failed to load skill '$slug'" },
                    verified = true
                )
            }
            "skill_delete" -> {
                val slug = args.optString("slug")
                val res = skillManager.deleteSkill(slug)
                ToolExecutionOutcome(
                    toolName,
                    res.isSuccess,
                    res.fold(onSuccess = { "Deleted imported skill '$slug'." }, onFailure = { it.message ?: "Delete failed" }),
                    verified = true
                )
            }
            "cad_generate_drawing" -> {
                val slug = args.optString("slug", "cad_project")
                val title = args.optString("title", "CAD Blueprint")
                val units = args.optString("units", "mm")
                val entitiesJson = args.optString("entities_json", "")
                val template = args.optString("template", "")
                val extrudeH = args.optDouble("extrude_height_3d", 0.0).let { if (it.isNaN()) 0.0 else it }
                val res = cadEngine.generateCadProject(
                    slug = slug,
                    title = title,
                    units = units,
                    entitiesJson = entitiesJson,
                    template = template,
                    extrudeHeight3d = extrudeH
                )
                res.fold(
                    onSuccess = { cad ->
                        if (!webServer.state.value.running) webServer.start(8080)
                        val viewerUrl = webServer.urlFor("cad/${cad.slug}/${cad.slug}_viewer.html")
                        val summary = buildString {
                            append("Generated Real CAD Package '${cad.title}' (${cad.entityCount} entities, ${"%.1f".format(cad.width)} x ${"%.1f".format(cad.height)} ${cad.units})\n")
                            append("• AutoCAD DXF: ${cad.dxfPath}\n")
                            append("• Dimensioned SVG Blueprint: ${cad.svgPath}\n")
                            append("• Interactive CAD Viewer: ${cad.viewerHtmlPath} (Live URL: $viewerUrl)\n")
                            cad.stlPath?.let { append("• 3D CAD Mesh STL: $it\n") }
                            append("Tip: Open $viewerUrl in browser_open_and_read to preview visually, or tap Share/Open Blueprint below.")
                        }
                        ToolExecutionOutcome(
                            toolName = toolName,
                            success = true,
                            output = summary,
                            verified = true,
                            actionUrl = viewerUrl,
                            shareRelativePath = cad.dxfPath,
                            shareSecondaryPath = cad.stlPath
                        )
                    },
                    onFailure = { err ->
                        ToolExecutionOutcome(toolName, false, "CAD generation error: ${err.message}", verified = true)
                    }
                )
            }
            "workspace_grep" -> {
                val query = args.optString("query").trim()
                val subdir = args.optString("subdir", "").trim()
                if (query.isBlank()) {
                    ToolExecutionOutcome(toolName, false, "Search query cannot be blank.")
                } else {
                    val base = workspace.resolveSafe(subdir)
                    val hits = mutableListOf<String>()
                    if (base.exists()) {
                        base.walkTopDown().filter { it.isFile && it.length() < 1_500_000L }.forEach { f ->
                            if (hits.size >= 40) return@forEach
                            val rel = workspace.relativePathOf(f)
                            runCatching {
                                f.bufferedReader().useLines { lines ->
                                    lines.forEachIndexed { idx, line ->
                                        if (hits.size < 40 && line.contains(query, ignoreCase = true)) {
                                            hits.add("$rel:${idx + 1}: ${line.trim().take(120)}")
                                        }
                                    }
                                }
                            }
                        }
                    }
                    val out = if (hits.isEmpty()) "No matches for '$query' in '${subdir.ifBlank { "virtual_env/" }}'"
                    else hits.joinToString("\n")
                    ToolExecutionOutcome(toolName, true, out, verified = true)
                }
            }
            "system_hardware_status" -> {
                val snap = resourceManager.snapshot.value
                val out = buildString {
                    append("Device Tier: ${snap.deviceTierLabel}\n")
                    append("Physical RAM: ${snap.availableRamMb} MB free / ${snap.totalPhysicalRamMb} MB total (${snap.memoryPressureLabel})\n")
                    append("App Heap: ${snap.appHeapUsedMb}/${snap.appHeapMaxMb} MB | CPU: ${snap.cpuCores} cores (${snap.primaryAbi})\n")
                    append("Device Free ROM: ${"%.2f".format(snap.storageFreeGb)} GB / ${"%.2f".format(snap.storageTotalGb)} GB\n")
                    append("Virtual Env Usage: ${"%.2f".format(snap.virtualEnvUsedMb)} MB / Custom ROM Quota: ${snap.storageQuotaMb} MB\n")
                    append("Effective Concurrency: ${snap.effectiveConcurrency} | Max Steps Budget: ${snap.recommendedMaxAgentSteps}\n")
                    append("Accessibility Virtual Hands: ${if (com.example.accessibility.PersonalAiAccessibilityService.isConnected.value) "CONNECTED" else "NOT CONNECTED"}")
                }
                ToolExecutionOutcome(toolName, true, out, verified = true)
            }
            else -> ToolExecutionOutcome(toolName, false, "Unknown tool: $toolName")
        }
    }

    private fun formatScreenInspectResult(toolName: String): ToolExecutionOutcome {
        val res = deviceController.inspectScreen()
        return res.fold(
            onSuccess = { nodes ->
                val pkg = deviceController.activePackage.value
                val size = deviceController.getScreenSizeLabel()
                val text = if (nodes.isEmpty()) "Screen: $size | Active package: $pkg\nNo accessible nodes detected"
                else "Screen: $size | Active package: $pkg\n" + nodes.joinToString("\n") { n ->
                    "${n.className} text='${n.text}' desc='${n.contentDesc}' id='${n.viewId}' center=(${n.centerX.toInt()},${n.centerY.toInt()}) clickable=${n.clickable} editable=${n.editable}"
                }
                val challenge = CommandSecurityGuard.detectHumanChallenge(text, pkg)
                val intervention = challenge?.let { (cType, cReason) ->
                    HumanInterventionRequest(
                        challengeType = cType,
                        reason = cReason,
                        targetMode = "VIRTUAL_HANDS",
                        urlOrPackage = pkg,
                        instructionForUser = "$pkg অ্যাপে $cType দেখা গেছে। 'অ্যাপে যান' চেপে সল্ভ করুন, তারপর 'সল্ভ সম্পন্ন' চাপুন।"
                    )
                }
                ToolExecutionOutcome(toolName, true, text, verified = true, interventionRequest = intervention)
            },
            onFailure = { ToolExecutionOutcome(toolName, false, it.message ?: "Accessibility unavailable") }
        )
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

    private fun parseHeadersArg(raw: Any?): Map<String, String> {
        val obj = when (raw) {
            is JSONObject -> raw
            is String -> raw.trim().takeIf { it.startsWith("{") }?.let { runCatching { JSONObject(it) }.getOrNull() }
            else -> null
        } ?: return emptyMap()
        val map = linkedMapOf<String, String>()
        obj.keys().forEach { key ->
            val v = obj.opt(key)?.toString()
            if (!key.isNullOrBlank() && v != null) map[key] = v
        }
        return map
    }

    private fun prop(type: String, description: String): JSONObject =
        JSONObject().put("type", type).put("description", description)
}
