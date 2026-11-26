package com.example.agent

import com.example.accessibility.DeviceAutomationController
import com.example.accessibility.ObservedUiNode
import com.example.accessibility.PersonalAiAccessibilityService
import com.example.browser.BrowserController
import com.example.security.CommandSecurityGuard
import kotlinx.coroutines.delay

/**
 * Integrated Zero-Latency Functional Multi-Bot Engine (Hands & Reflexes for the AI Brain):
 * 1. AutoPopupReflexBot   - Automatically clears safe Allow/Skip/Later/NotNow/Cookie popups without wasting AI turns.
 * 2. MultiMediaTargetBot  - Ranks primary media vs secondary/thumbnail media, stages files into Android Gallery, and shares directly.
 * 3. VirtualHandsActionBot - Sub-50ms single UI navigation, scroll, inspect, and tap execution.
 * 4. AccountBootstrapBot  - Instant t=0ms Google/Gmail signup pre-warming in Live Browser.
 * 5. UniversalRouterBot   - Launches ANY installed app OR routes directly to Live Browser/Chrome across all Internet & Virtual World tasks.
 */
data class BotDispatchResult(
    val handledCompletely: Boolean,
    val botName: String,
    val summary: String,
    val preExecutedContext: String = ""
)

class ZeroLatencyBotEngine(
    private val deviceController: DeviceAutomationController,
    private val browserController: BrowserController,
    private val skillManager: UniversalSkillManager? = null,
    private val repository: com.example.storage.AgentRepository? = null,
    private val workspace: com.example.runtime.VirtualWorkspaceManager? = null
) {
    private val appAliases: Map<String, List<String>> = mapOf(
        "youtube" to listOf("youtube", "ইউটিউব", "yt studio", "youtube studio"),
        "tiktok" to listOf("tiktok", "টিকটক", "tik tok", "musically"),
        "facebook" to listOf("facebook", "ফেসবুক", "fb"),
        "instagram" to listOf("instagram", "ইনস্টাগ্রাম", "insta"),
        "whatsapp" to listOf("whatsapp", "হোয়াটসঅ্যাপ", "হোয়াটসঅ্যাপ"),
        "telegram" to listOf("telegram", "টেলিগ্রাম"),
        "moviebox" to listOf("moviebox", "movie box", "মুভি বক্স", "মুভিবক্স", "onstream", "loklok"),
        "chrome" to listOf("chrome", "ক্রোম", "google chrome"),
        "gmail" to listOf("gmail", "জিমেইল"),
        "settings" to listOf("settings", "সেটিংস"),
        "camera" to listOf("camera", "ক্যামেরা"),
        "photos" to listOf("photos", "gallery", "গ্যালারি", "ফটো"),
        "play store" to listOf("play store", "প্লে স্টোর", "google play"),
        "spotify" to listOf("spotify", "স্পটিফাই"),
        "capcut" to listOf("capcut", "ক্যাপকাট"),
        "canva" to listOf("canva", "ক্যানভা"),
        "phone" to listOf("dialer", "phone", "ফোন"),
        "messages" to listOf("messages", "sms", "মেসেজ"),
        "files" to listOf("files", "ফাইল")
    )

    private val videoFirstPlatforms = setOf("youtube", "tiktok", "instagram", "facebook")

    private val compoundTaskHints = listOf(
        "তারপর", "এবং", "দিয়ে", "দিয়ে", "করে", "থেকে", "আপলোড", "পোস্ট", "তৈরি", "বানাও",
        "লিখে", "পাঠাও", "ডাউনলোড", "ইনস্টল", "লগইন", "অ্যাকাউন্ট", "না পেলে", "না পাইলে",
        "ফিলাপ", "পূরণ", "আবেদন", "এপ্লাই", "সাবমিট", "রিপোর্ট", "বিশ্লেষণ", "ক্লিক",
        "then", "and", "upload", "post", "create", "make", "write", "send", "watch", "stream",
        "fill", "type", "login", "signup", "sign up", "account", "install", "download",
        "fallback", "if not", "apply", "submit", "report", "analyze", "form"
    )

    private val webOrInternetHints = listOf(
        "http://", "https://", ".com", ".org", ".net", ".io", ".bd", "website", "site", "browser",
        "chrome", "google search", "search online", "internet", "third party", "third-party", "download link",
        "ওয়েব", "ওয়েবসাইট", "ব্রাউজার", "ক্রোম", "ইন্টারনেট", "থার্ড পার্টি", "অনলাইন", "সার্চ", "গুগল", "খোঁজো"
    )

    suspend fun tryInstantOrPrewarm(
        prompt: String,
        attachments: List<AgentUploadedAttachment>,
        fullAutonomousMode: Boolean
    ): BotDispatchResult? {
        if (!fullAutonomousMode) return null
        val trimmed = prompt.trim()
        if (trimmed.isEmpty()) return null
        val lower = trimmed.lowercase()

        // 0. SkillSearchBot (0 AI Tokens): Pure skill count, listing, or local skill search
        if (attachments.isEmpty() && skillManager != null && repository != null) {
            val isPureSkillQuery = isPureSkillLookupQuery(lower)
            if (isPureSkillQuery) {
                val allImported = repository.getAllImportedSkillsSnapshot()
                val cleanedQuery = extractSkillSearchQuery(trimmed)
                val matches = if (cleanedQuery.isBlank()) {
                    allImported.take(15).map { SkillMatchResult(it, 1, emptyList()) }
                } else {
                    skillManager.matchRelevantSkills(cleanedQuery, allImported, limit = 12)
                }
                val report = buildString {
                    append("মোট ইমপোর্টেড স্কিল: ${allImported.size}টি (Active: ${allImported.count { it.enabled }}টি) • বিল্ট-ইন: ${AgentSkillRegistry.skills.size}টি।\n")
                    if (cleanedQuery.isNotBlank()) {
                        append("সার্চ: '$cleanedQuery' — ম্যাচ পাওয়া গেছে: ${matches.size}টি\n\n")
                    } else {
                        append("\nসাম্প্রতিক/শীর্ষ স্কিলসমূহ:\n")
                    }
                    if (matches.isEmpty()) {
                        append("কোনো ম্যাচিং ইমপোর্টেড স্কিল পাওয়া যায়নি।")
                    } else {
                        matches.forEachIndexed { idx, m ->
                            append("${idx + 1}. ${m.skill.title} [${m.skill.slug}] (${m.skill.ecosystem}) — ${m.skill.description.take(90)}\n")
                        }
                    }
                }
                return BotDispatchResult(
                    handledCompletely = true,
                    botName = "SkillSearchBot (0 Tokens)",
                    summary = report.trim()
                )
            }
        }

        // 0.5 LocalSystemBot (0 AI Tokens): Pure workspace file list request
        if (attachments.isEmpty() && workspace != null && isPureFileListQuery(lower)) {
            val items = workspace.listDirectory("")
            val out = if (items.isEmpty()) {
                "ওয়ার্কস্পেস (`virtual_env/`) বর্তমানে খালি।"
            } else {
                "ওয়ার্কস্পেস ফাইল তালিকা (${items.size}টি):\n" + items.take(40).joinToString("\n") {
                    val tag = if (it.isDirectory) "[DIR]" else "[${it.sizeBytes}B]"
                    "• $tag ${it.relativePath}"
                }
            }
            return BotDispatchResult(
                handledCompletely = true,
                botName = "LocalSystemBot (0 Tokens)",
                summary = out
            )
        }

        // 1. VirtualHandsActionBot: Instant single UI gestures & popup sweep (< 50ms, 0 Tokens)
        if (attachments.isEmpty() && lower.length <= 65) {
            val instantGesture = matchDirectGesture(lower, trimmed)
            if (instantGesture != null && instantGesture.second.isSuccess) {
                return BotDispatchResult(
                    handledCompletely = true,
                    botName = "VirtualHandsActionBot (0 Tokens)",
                    summary = "${instantGesture.second.getOrNull()} (0ms Fast Bot)"
                )
            }
        }

        // Helper to attach auto-matched skill playbook & profile memory with ZERO AI tokens
        suspend fun attachSkillAndProfileContext(baseContext: String): Pair<String, String?> {
            val matchedSkillNote = buildAutoMatchedSkillContext(trimmed)
            val profileNote = buildFormProfileContextIfNeeded(lower)
            val combined = buildString {
                if (matchedSkillNote != null) append(matchedSkillNote.second).append("\n\n")
                if (!profileNote.isNullOrBlank()) append(profileNote).append("\n\n")
                append(baseContext)
            }.trim()
            return combined to matchedSkillNote?.first
        }

        // 2. AccountBootstrapBot: Instant Gmail/Google Signup Live Browser pre-launch at t=0ms
        val wantsGoogleAccount = (lower.contains("gmail") || lower.contains("জিমেইল") || lower.contains("google account") || lower.contains("গুগল অ্যাকাউন্ট")) &&
            (lower.contains("create") || lower.contains("make") || lower.contains("তৈরি") || lower.contains("বানা") || lower.contains("খুল"))
        if (wantsGoogleAccount) {
            val snap = browserController.navigateAndWait("https://accounts.google.com/signup").getOrNull()
            val clearedOverlay = browserController.dismissCommonOverlays()
            val baseCtx = buildString {
                append("[AccountBootstrapBot pre-opened https://accounts.google.com/signup in Live Browser at 0ms]\n")
                if (!clearedOverlay.isNullOrBlank()) append("[AutoPopupReflexBot cleared web overlay: '$clearedOverlay']\n")
                if (snap != null) {
                    append("Title: ${snap.title}\nURL: ${snap.url}\nText: ${snap.textContent.take(1500)}\nForms: ${snap.formsSummary}")
                }
            }
            val (ctx, skillName) = attachSkillAndProfileContext(baseCtx)
            return BotDispatchResult(
                handledCompletely = false,
                botName = if (skillName != null) "AccountBootstrapBot + Skill:$skillName" else "AccountBootstrapBot",
                summary = "Opened Google Signup in Live Browser (0ms)" + if (skillName != null) " • Skill: $skillName" else "",
                preExecutedContext = ctx
            )
        }

        // 3. MultiMediaTargetBot: Primary + Secondary/Thumbnail Media Target Helper (YouTube/TikTok/Social/Any App)
        val mediaAtts = attachments.filter {
            it.mimeType.startsWith("image/") || it.mimeType.startsWith("video/")
        }
        val detectedApps = detectAllMentionedApps(lower)
        val primaryTargetApp = detectedApps.firstOrNull { it != "chrome" } ?: detectedApps.firstOrNull()
        val wantsUploadOrShare = mediaAtts.isNotEmpty() && (
            lower.contains("upload") || lower.contains("post") || lower.contains("share") || lower.contains("thumbnail") ||
                lower.contains("আপলোড") || lower.contains("পোস্ট") || lower.contains("পাঠা") || lower.contains("শেয়ার") ||
                lower.contains("থাম্বনেল") || primaryTargetApp != null
            )
        if (wantsUploadOrShare) {
            val botRes = runMultiMediaTargetBot(
                mediaAtts = mediaAtts,
                targetApp = primaryTargetApp,
                lowerPrompt = lower
            )
            if (botRes != null) {
                val (ctx, skillName) = attachSkillAndProfileContext(botRes.preExecutedContext)
                return botRes.copy(
                    botName = if (skillName != null) "${botRes.botName} + Skill:$skillName" else botRes.botName,
                    preExecutedContext = ctx
                )
            }
        }

        // 4. UniversalRouterBot: Any App + Any Internet Browser / Fallback Routing
        val wantsFallbackChromeOrWeb = webOrInternetHints.any { lower.contains(it) }
        val nonBrowserApp = detectedApps.firstOrNull { it != "chrome" }
        val isInAppWhatsAppBotRequest = nonBrowserApp == "whatsapp" && listOf(
            "bot", "auto", "antilink", "anti-link", "antisticker", "anti-sticker", "group",
            "link code", "pairing", "qr", "message", "reply", "বট", "অটো", "লিংক", "গ্রুপ",
            "মেসেজ", "রিপ্লাই", "স্টিকার", "এন্টি"
        ).any { lower.contains(it) }

        if (nonBrowserApp != null && !isInAppWhatsAppBotRequest) {
            val isPureOpen = !compoundTaskHints.any { lower.contains(it) } &&
                !wantsFallbackChromeOrWeb &&
                (lower.contains("open") || lower.contains("launch") || lower.contains("start") ||
                    lower.contains("খোল") || lower.contains("ওপেন") || lower.contains("চালা") || lower.contains("ঢোক"))

            val launchRes = deviceController.launchApp(nonBrowserApp)
            if (launchRes.isSuccess) {
                delay(280)
                val dismissed = autoDismissScreenPopups(maxRounds = 2)
                if (isPureOpen && attachments.isEmpty()) {
                    val note = if (dismissed.isNotEmpty()) " • Popups cleared: ${dismissed.joinToString()}" else ""
                    return BotDispatchResult(
                        handledCompletely = true,
                        botName = "UniversalRouterBot (0 Tokens)",
                        summary = "${launchRes.getOrNull()}$note (0ms Launch)"
                    )
                }
                val screenText = readQuickScreenSnapshot()
                val baseCtx = buildString {
                    append("[UniversalRouterBot launched '$nonBrowserApp' at 0ms]\n")
                    if (dismissed.isNotEmpty()) {
                        append("[AutoPopupReflexBot auto-dismissed: ${dismissed.joinToString(" | ")}]\n")
                    }
                    if (wantsFallbackChromeOrWeb) {
                        append("FALLBACK ROUTING READY: Primary target is '$nonBrowserApp'. If search returns no match or fails inside '$nonBrowserApp', IMMEDIATELY switch to Live Browser (`browser_open_and_read` / `browser_download`) or phone Chrome (`app_launch('chrome')`).\n")
                    }
                    append(screenText)
                }
                val (ctx, skillName) = attachSkillAndProfileContext(baseCtx)
                return BotDispatchResult(
                    handledCompletely = false,
                    botName = buildString {
                        append("UniversalRouterBot")
                        if (dismissed.isNotEmpty()) append(" + AutoPopupReflexBot")
                        if (skillName != null) append(" + Skill:$skillName")
                    },
                    summary = "Launched '$nonBrowserApp' at 0ms" +
                        (if (dismissed.isNotEmpty()) " (cleared ${dismissed.size} popup)" else "") +
                        (if (skillName != null) " • Auto-Skill: $skillName" else ""),
                    preExecutedContext = ctx
                )
            } else if (wantsFallbackChromeOrWeb || !isPureOpen) {
                val urlOrQuery = extractUrlOrCleanQuery(trimmed, nonBrowserApp)
                val snap = browserController.navigateAndWait(urlOrQuery).getOrNull()
                val clearedOverlay = browserController.dismissCommonOverlays()
                val baseCtx = buildString {
                    append("[UniversalRouterBot: App '$nonBrowserApp' not installed — auto-switched to Live Browser at 0ms]\n")
                    if (!clearedOverlay.isNullOrBlank()) append("[AutoPopupReflexBot cleared web overlay: '$clearedOverlay']\n")
                    if (snap != null) {
                        append("Title: ${snap.title}\nURL: ${snap.url}\nText: ${snap.textContent.take(1500)}\nLinks: ${snap.linksSummary.take(600)}\nForms: ${snap.formsSummary}")
                    }
                }
                val (ctx, skillName) = attachSkillAndProfileContext(baseCtx)
                return BotDispatchResult(
                    handledCompletely = false,
                    botName = if (skillName != null) "UniversalRouterBot (Web) + Skill:$skillName" else "UniversalRouterBot (Web Fallback)",
                    summary = "'$nonBrowserApp' not installed -> pre-opened Live Browser at 0ms",
                    preExecutedContext = ctx
                )
            }
        }

        // 5. Pure Internet / Chrome / URL Pre-Warming & Zero-Token Pure Search/Open
        val explicitUrl = Regex("""https?://\S+""", RegexOption.IGNORE_CASE).find(trimmed)?.value
        val asksPureChromeOpen = detectedApps.contains("chrome") &&
            !compoundTaskHints.any { lower.contains(it) } &&
            (lower.contains("open") || lower.contains("খোল") || lower.contains("ওপেন"))
        if (asksPureChromeOpen && explicitUrl == null && attachments.isEmpty()) {
            val res = deviceController.launchApp("chrome")
            if (res.isSuccess) {
                delay(240)
                val dismissed = autoDismissScreenPopups(maxRounds = 1)
                return BotDispatchResult(
                    handledCompletely = true,
                    botName = "UniversalRouterBot (0 Tokens)",
                    summary = "${res.getOrNull()} (0ms Launch)" +
                        if (dismissed.isNotEmpty()) " • Cleared ${dismissed.joinToString()}" else ""
                )
            }
        }

        if (explicitUrl != null || (attachments.isEmpty() && wantsFallbackChromeOrWeb && lower.length > 6)) {
            val targetQuery = explicitUrl ?: extractUrlOrCleanQuery(trimmed, null)
            val snap = browserController.navigateAndWait(targetQuery).getOrNull()
            if (snap != null) {
                val clearedOverlay = browserController.dismissCommonOverlays()
                val isPureSearchOrView = attachments.isEmpty() &&
                    !compoundTaskHints.any { lower.contains(it) } &&
                    (explicitUrl != null || lower.contains("search") || lower.contains("সার্চ") || lower.contains("খোঁজো") || lower.contains("খোল") || lower.contains("ওপেন") || lower.contains("দেখাও")) &&
                    !lower.contains("এপ্লাই") && !lower.contains("ফর্ম") && !lower.contains("অ্যাকাউন্ট")
                if (isPureSearchOrView) {
                    val quickReport = buildString {
                        append("🌐 লাইভ ব্রাউজার রেজাল্ট (0 Tokens):\n")
                        append("শিরোনাম: ${snap.title}\nলিংক: ${snap.url}\n")
                        if (!clearedOverlay.isNullOrBlank()) append("AutoPopupReflexBot: '$clearedOverlay' বন্ধ করা হয়েছে।\n")
                        if (snap.textContent.isNotBlank()) append("\nসারসংক্ষেপ:\n${snap.textContent.take(1000)}\n")
                        if (snap.linksSummary.isNotBlank()) append("\nপ্রধান লিংক:\n${snap.linksSummary.take(600)}")
                    }
                    return BotDispatchResult(
                        handledCompletely = true,
                        botName = "UniversalRouterBot (0 Tokens)",
                        summary = quickReport.trim()
                    )
                }

                val baseCtx = buildString {
                    append("[UniversalRouterBot pre-opened Live Browser for '$targetQuery' at 0ms]\n")
                    if (!clearedOverlay.isNullOrBlank()) append("[AutoPopupReflexBot cleared web overlay: '$clearedOverlay']\n")
                    append("Title: ${snap.title}\nURL: ${snap.url}\nText: ${snap.textContent.take(1500)}\nLinks: ${snap.linksSummary.take(700)}\nForms: ${snap.formsSummary}")
                }
                val (ctx, skillName) = attachSkillAndProfileContext(baseCtx)
                return BotDispatchResult(
                    handledCompletely = false,
                    botName = if (skillName != null) "UniversalRouterBot + Skill:$skillName" else "UniversalRouterBot",
                    summary = "Pre-loaded Live Browser at 0ms" + if (skillName != null) " • Skill: $skillName" else "",
                    preExecutedContext = ctx
                )
            }
        }

        // 6. SkillRouterBot (0ms, 0 Tokens): Even when no app/URL pre-warm ran, auto-match & pre-load best Skill for AI Brain!
        val matchedSkillNote = buildAutoMatchedSkillContext(trimmed)
        val profileNote = buildFormProfileContextIfNeeded(lower)
        if (matchedSkillNote != null || !profileNote.isNullOrBlank()) {
            val combinedCtx = buildString {
                if (matchedSkillNote != null) append(matchedSkillNote.second).append("\n\n")
                if (!profileNote.isNullOrBlank()) append(profileNote)
            }.trim()
            return BotDispatchResult(
                handledCompletely = false,
                botName = matchedSkillNote?.first?.let { "SkillRouterBot ($it)" } ?: "ProfileMemoryBot",
                summary = matchedSkillNote?.first?.let { "Auto-Matched Skill '$it' in <10ms (0 Tokens)" }
                    ?: "Attached non-sensitive profile facts for form/task (0 Tokens)",
                preExecutedContext = combinedCtx
            )
        }

        return null
    }

    /**
     * MultiMediaTargetBot:
     * - Ranks primary media (e.g. video for YouTube/TikTok) vs secondary assets (e.g. thumbnail image or extra photos).
     * - Stages media files into Android's public MediaStore Gallery ('Pictures/AI Computer' or 'Movies/AI Computer')
     *   so system Photo/Thumbnail Pickers display them at the very top immediately.
     * - Dispatches primary share_file or multi-photo share_multiple directly to the target app, then clears popups.
     */
    private suspend fun runMultiMediaTargetBot(
        mediaAtts: List<AgentUploadedAttachment>,
        targetApp: String?,
        lowerPrompt: String
    ): BotDispatchResult? {
        val prefersVideo = targetApp in videoFirstPlatforms ||
            lowerPrompt.contains("video") || lowerPrompt.contains("ভিডিও") || lowerPrompt.contains("thumbnail") || lowerPrompt.contains("থাম্বনেল")

        val primaryMedia = if (prefersVideo) {
            mediaAtts.firstOrNull { it.mimeType.startsWith("video/") } ?: mediaAtts.first()
        } else {
            mediaAtts.first()
        }
        val secondaryMedia = mediaAtts.filter { it.relativePath != primaryMedia.relativePath }

        // Stage secondary (thumbnail/extra photos) + primary into public Gallery so Pickers see them first
        val stagedSecondary = secondaryMedia.mapNotNull { att ->
            deviceController.stageMediaToPublicGallery(att.relativePath).getOrNull()?.let { att to it }
        }
        val stagedPrimary = deviceController.stageMediaToPublicGallery(primaryMedia.relativePath).getOrNull()

        val allImagesOnly = mediaAtts.size > 1 && mediaAtts.all { it.mimeType.startsWith("image/") }
        val shareRes = if (allImagesOnly && targetApp in setOf("whatsapp", "telegram", "facebook", "instagram")) {
            deviceController.sendShareOrIntent(
                actionType = "share_multiple",
                target = mediaAtts.joinToString(",") { it.relativePath },
                body = targetApp.orEmpty()
            )
        } else {
            deviceController.sendShareOrIntent(
                actionType = "share_file",
                target = primaryMedia.relativePath,
                body = targetApp.orEmpty()
            )
        }

        if (shareRes.isFailure) return null
        delay(340)
        val dismissed = autoDismissScreenPopups(maxRounds = 2)
        val screenText = readQuickScreenSnapshot()

        val ctx = buildString {
            append("[MultiMediaTargetBot dispatched '${primaryMedia.displayName}' (${primaryMedia.mimeType}) -> '${targetApp ?: "Share Target"}' at 0ms]\n")
            if (stagedPrimary != null) {
                append("• Primary staged in Android Gallery: ${stagedPrimary.albumPath}/${stagedPrimary.displayName}\n")
            }
            if (stagedSecondary.isNotEmpty()) {
                append("• Secondary / Thumbnail Media Staged in Gallery (ready at TOP of Photo/Thumbnail Picker):\n")
                stagedSecondary.forEachIndexed { idx, (att, staged) ->
                    append("  ${idx + 1}. File='${att.relativePath}' -> Gallery='${staged.albumPath}/${staged.displayName}' (${staged.mimeType})\n")
                }
                append("  TIP: When tapping Add Thumbnail / Gallery Picker, pick '${stagedSecondary.first().second.displayName}' or tap the FIRST newest gallery tile!\n")
            }
            if (dismissed.isNotEmpty()) {
                append("[AutoPopupReflexBot auto-dismissed: ${dismissed.joinToString(" | ")}]\n")
            }
            append(screenText)
        }

        val secondaryLabel = if (stagedSecondary.isNotEmpty()) " + ${stagedSecondary.size} thumbnail/gallery asset(s) staged" else ""
        return BotDispatchResult(
            handledCompletely = false,
            botName = "MultiMediaTargetBot",
            summary = "Sent '${primaryMedia.displayName}' to ${targetApp ?: "chooser"}$secondaryLabel (0ms)",
            preExecutedContext = ctx
        )
    }

    /**
     * AutoPopupReflexBot:
     * Automatically clears non-destructive Allow/Permission/Skip/Later/NotNow/Dismiss dialogs
     * so AI Brain does not waste round-trips on predictable popups.
     */
    suspend fun autoDismissScreenPopups(maxRounds: Int = 2): List<String> {
        if (!PersonalAiAccessibilityService.isConnected.value) return emptyList()
        val dismissed = mutableListOf<String>()
        repeat(maxRounds.coerceIn(1, 3)) {
            val pkg = deviceController.activePackage.value
            val nodes = deviceController.inspectScreen().getOrNull().orEmpty()
            val candidate = findSafePopupDismissTarget(pkg, nodes) ?: return dismissed
            val label = candidate.text.ifBlank { candidate.contentDesc }.trim()
            val tapOk = deviceController.tap(candidate.centerX, candidate.centerY).isSuccess
            if (!tapOk) return dismissed
            dismissed.add("$label ($pkg)")
            delay(220)
        }
        return dismissed
    }

    /**
     * Pure deterministic selector for AutoPopupReflexBot (unit-testable without live service).
     */
    fun findSafePopupDismissTarget(pkg: String, nodes: List<ObservedUiNode>): ObservedUiNode? =
        Companion.findSafePopupDismissTarget(pkg, nodes)

    companion object {
        fun findSafePopupDismissTarget(pkg: String, nodes: List<ObservedUiNode>): ObservedUiNode? {
            if (nodes.isEmpty() || CommandSecurityGuard.isProtectedPackage(pkg)) return null

            val lowerPkg = pkg.lowercase()
            val screenSummary = nodes.joinToString(" ") { "${it.text} ${it.contentDesc}" }.lowercase()
            val blockedWords = listOf(
                "buy", "pay", "purchase", "subscribe", "checkout", "order",
                "delete", "remove", "erase", "send money", "transfer", "uninstall", "install"
            )

            fun isSafeClickable(node: ObservedUiNode): Boolean {
                if (!node.clickable) return false
                val lbl = node.text.ifBlank { node.contentDesc }.trim().lowercase()
                if (lbl.isEmpty() || lbl.length > 28) return false
                return blockedWords.none { lbl.contains(it) }
            }

            val isPermissionScreen = lowerPkg.contains("permissioncontroller") ||
                lowerPkg.contains("packageinstaller") ||
                (screenSummary.contains("allow ") && (screenSummary.contains("access") || screenSummary.contains("record") || screenSummary.contains("notifications") || screenSummary.contains("photos") || screenSummary.contains("location") || screenSummary.contains("camera") || screenSummary.contains("microphone"))) ||
                screenSummary.contains("অনুমতি")

            if (isPermissionScreen) {
                val permissionPriority = listOf(
                    "while using the app",
                    "only this time",
                    "allow all",
                    "allow",
                    "অনুমতি দিন"
                )
                for (target in permissionPriority) {
                    val hit = nodes.firstOrNull { node ->
                        if (!isSafeClickable(node)) return@firstOrNull false
                        val lbl = node.text.ifBlank { node.contentDesc }.trim().lowercase()
                        lbl == target || lbl.startsWith("$target ")
                    }
                    if (hit != null) return hit
                }
            }

            val unconditionalNagButtons = listOf(
                "skip ad", "skip ads", "skip", "not now", "maybe later", "later",
                "no thanks", "no, thanks", "dismiss", "close ad", "got it",
                "বাদ দিন", "এখন না", "পরে"
            )
            for (target in unconditionalNagButtons) {
                val hit = nodes.firstOrNull { node ->
                    if (!isSafeClickable(node)) return@firstOrNull false
                    val lbl = node.text.ifBlank { node.contentDesc }.trim().lowercase()
                    lbl == target || (target.length >= 4 && lbl.startsWith("$target "))
                }
                if (hit != null) return hit
            }

            // Contextual 'Cancel' / 'Close' ONLY when clear popup dialog markers exist (never cancel an active upload)
            val hasPopupDialogContext = listOf(
                "later", "update", "new version", "rate us", "review", "turn on",
                "enable", "notification", "sign in", "save password", "sync", "permission"
            ).any { screenSummary.contains(it) }
            if (hasPopupDialogContext) {
                val hit = nodes.firstOrNull { node ->
                    if (!isSafeClickable(node)) return@firstOrNull false
                    val lbl = node.text.ifBlank { node.contentDesc }.trim().lowercase()
                    lbl == "cancel" || lbl == "close" || lbl == "বাতিল" || lbl == "বন্ধ করুন"
                }
                if (hit != null) return hit
            }

            return null
        }
    }

    private suspend fun matchDirectGesture(lower: String, raw: String): Pair<String, Result<String>>? {
        return when {
            lower in setOf("home", "go home", "হোম", "হোমে যাও") ->
                "home" to deviceController.globalNav("home")
            lower in setOf("back", "go back", "ব্যাক", "ব্যাক করো", "পিছনে যাও") ->
                "back" to deviceController.globalNav("back")
            lower in setOf("recents", "recent apps", "রিসেন্ট") ->
                "recents" to deviceController.globalNav("recents")
            lower in setOf("notifications", "নোটিফিকেশন") ->
                "notifications" to deviceController.globalNav("notifications")
            lower in setOf("scroll down", "নিচে স্ক্রল", "নিচে স্ক্রল করো", "স্ক্রল ডাউন") ->
                "scroll_down" to deviceController.scroll("down")
            lower in setOf("scroll up", "উপরে স্ক্রল", "উপরে স্ক্রল করো", "স্ক্রল আপ") ->
                "scroll_up" to deviceController.scroll("up")
            lower in setOf("dismiss popup", "close popup", "skip popup", "পপআপ সরাও", "পপআপ বন্ধ করো") -> {
                val cleared = autoDismissScreenPopups(maxRounds = 2)
                val res = if (cleared.isNotEmpty()) Result.success("Cleared popup(s): ${cleared.joinToString()}")
                else Result.failure(IllegalStateException("No dismissible popup found"))
                "popup" to res
            }
            lower in setOf("inspect screen", "read screen", "স্ক্রিন পড়ো", "স্ক্রিন দেখো") -> {
                val res = deviceController.inspectScreen().map { nodes ->
                    val pkg = deviceController.activePackage.value
                    "Live Screen ($pkg • ${nodes.size} nodes):\n" +
                        nodes.take(40).joinToString("\n") { n ->
                            "${n.className} '${n.text.ifBlank { n.contentDesc }}' @(${n.centerX.toInt()},${n.centerY.toInt()})"
                        }
                }
                "inspect" to res
            }
            (lower.startsWith("tap ") || lower.startsWith("click ")) && !lower.contains(" and ") -> {
                val target = raw.substringAfter(" ").trim()
                if (target.isNotBlank() && PersonalAiAccessibilityService.isConnected.value) {
                    "click_text" to deviceController.clickText(target)
                } else null
            }
            else -> null
        }
    }

    private fun detectAllMentionedApps(lower: String): List<String> {
        val found = linkedSetOf<String>()
        for ((canonical, aliases) in appAliases) {
            if (aliases.any { lower.contains(it) }) {
                found.add(canonical)
            }
        }
        val apps = runCatching { deviceController.listLaunchableApps() }.getOrElse { emptyList() }
        apps.forEach { app ->
            val nameLower = app.appName.lowercase()
            if (nameLower.length >= 4 && lower.contains(nameLower)) {
                found.add(app.packageName)
            }
        }
        return found.toList()
    }

    private fun extractUrlOrCleanQuery(prompt: String, missingAppName: String?): String {
        val urlMatch = Regex("""https?://\S+""", RegexOption.IGNORE_CASE).find(prompt)?.value
        if (urlMatch != null) return urlMatch
        var cleaned = prompt
            .replace(Regex("""(chrome|browser|ক্রোম|ব্রাউজার|ওপেন|খোলো|দাও|করো|থেকে|দিয়ে|দিয়ে|open|launch|please)""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
        if (!missingAppName.isNullOrBlank()) {
            cleaned = cleaned.replace(missingAppName, "", ignoreCase = true).trim()
        }
        return cleaned.ifBlank { prompt }
    }

    private fun readQuickScreenSnapshot(): String {
        val nodes = deviceController.inspectScreen().getOrNull() ?: return "(Accessibility Virtual Hands not connected)"
        val pkg = deviceController.activePackage.value
        val size = deviceController.getScreenSizeLabel()
        return "Screen: $size | App: $pkg\n" + nodes.take(70).joinToString("\n") { n ->
            buildString {
                append(n.className)
                if (n.text.isNotBlank()) append(" '${n.text}'")
                if (n.contentDesc.isNotBlank() && n.contentDesc != n.text) append(" desc='${n.contentDesc}'")
                if (n.viewId.isNotBlank()) append(" id=${n.viewId}")
                append(" @(${n.centerX.toInt()},${n.centerY.toInt()})")
                if (n.clickable) append(" [click]")
                if (n.editable) append(" [edit]")
            }
        }
    }

    private fun isPureSkillLookupQuery(lower: String): Boolean {
        val mentionsSkill = lower.contains("skill") || lower.contains("স্কিল")
        if (!mentionsSkill) return false
        val mutatingHints = listOf("import", "add", "push", "delete", "remove", "create", "build", "http://", "https://", "ইম্পোর্ট", "পুশ", "যোগ", "ডিলিট", "বানা", "তৈরি", "ইউজ", "use ")
        if (mutatingHints.any { lower.contains(it) }) return false
        val lookupHints = listOf("list", "show", "search", "find", "count", "how many", "কয়টা", "কতগুলো", "কতগুলা", "দেখাও", "খোঁজো", "সার্চ", "তালিকা")
        return lookupHints.any { lower.contains(it) }
    }

    private fun extractSkillSearchQuery(raw: String): String {
        return raw.replace(
            Regex("""(skill|skills|স্কিল|গুলো|গুলা|সব|তালিকা|লিস্ট|list|show|search|find|count|how many|কয়টা|কতগুলো|কতগুলা|দেখাও|খোঁজো|সার্চ|আমার|আছে|কিনা|গুলো|প্লিজ|please)""", RegexOption.IGNORE_CASE),
            " "
        ).replace(Regex("""\s+"""), " ").trim()
    }

    private fun isPureFileListQuery(lower: String): Boolean {
        val normalized = lower.trim()
        return normalized in setOf(
            "list files", "show files", "files", "workspace files",
            "ফাইল দেখাও", "ফাইল লিস্ট দেখাও", "ফাইল তালিকা দেখাও", "সব ফাইল দেখাও"
        )
    }

    private suspend fun buildAutoMatchedSkillContext(prompt: String): Pair<String, String>? {
        val mgr = skillManager ?: return null
        val repo = repository ?: return null
        val allSkills = runCatching { repo.getEnabledImportedSkillsSnapshot() }.getOrElse { return null }
        if (allSkills.isEmpty()) return null

        val matches = mgr.matchRelevantSkills(prompt, allSkills, limit = 4)
        val top = matches.firstOrNull() ?: return null
        if (top.score < 6) return null

        val compactPlaybook = mgr.loadCompactPlaybookForBot(top.skill.slug, prompt, maxChars = 1650).getOrNull()
        val ctx = buildString {
            append("[SkillRouterBot (0 Tokens) Auto-Matched Top ${matches.size} of ${allSkills.size} Skills]\n")
            matches.forEachIndexed { idx, m ->
                append("• #${idx + 1} [${m.skill.slug}] ${m.skill.title} (score=${m.score}): ${m.skill.description.take(100)}\n")
            }
            if (!compactPlaybook.isNullOrBlank()) {
                append("\n[Active Auto-Loaded Skill Playbook: ${top.skill.slug}]\n")
                append(compactPlaybook)
            }
        }.trim()
        return top.skill.title to ctx
    }

    private suspend fun buildFormProfileContextIfNeeded(lower: String): String? {
        val repo = repository ?: return null
        val needsProfile = listOf(
            "form", "fill", "apply", "signup", "sign up", "account", "gmail", "email", "cv", "resume",
            "ফর্ম", "ফিলাপ", "পূরণ", "আবেদন", "এপ্লাই", "অ্যাকাউন্ট", "জিমেইল", "ইমেইল"
        ).any { lower.contains(it) }
        if (!needsProfile) return null

        val memories = runCatching { repo.getMemorySnapshot() }.getOrElse { return null }
        val profileEntries = memories.filter {
            it.category == com.example.storage.MemoryCategory.USER_PROFILE ||
                it.category == com.example.storage.MemoryCategory.USER_PREFERENCES
        }.take(6)
        if (profileEntries.isEmpty()) return null

        return "[ProfileMemoryBot (0 Tokens) Saved User Profile for Form/Account]:\n" +
            profileEntries.joinToString("\n") { "- ${it.memoryKey}: ${it.content.take(140)}" }
    }
}
