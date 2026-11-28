package com.example.whatsapp

enum class WaLinkStatus(val label: String) {
    DISCONNECTED("Not Linked"),
    LOADING("Loading WhatsApp Web..."),
    WAITING_PAIRING("Scan QR or Enter Link Code"),
    LINKED("WhatsApp Linked (Live)"),
    ERROR("Connection Issue")
}

data class WaBotEvent(
    val id: Long = com.example.runtime.MonotonicIdGenerator.nextId(),
    val kind: String, // COMMAND, MODERATION, AI_REPLY, SENT, SCHEDULED, BLOCKED, LINK, ERROR
    val chatName: String,
    val summary: String,
    val latencyMs: Long = 0L,
    val timestamp: Long = System.currentTimeMillis()
)

data class WaIncomingMessage(
    val chatName: String,
    val senderName: String,
    val isGroup: Boolean,
    val body: String,
    val hasLink: Boolean,
    val isSticker: Boolean,
    val isForwarded: Boolean = false,
    val isFromMe: Boolean = false,
    val senderPhoneHint: String = "",
    val domMessageId: String = "",
    val sourceChannel: String = "WA_WEB", // WA_WEB or NOTIFICATION
    val recentContextLines: List<String> = emptyList(),
    val timestamp: Long = System.currentTimeMillis()
)

data class WaSyncedChat(
    val chatKey: String,
    val displayName: String,
    val isGroup: Boolean,
    val unreadCount: Int,
    val lastPreview: String
)

data class WaRuntimeState(
    val linkStatus: WaLinkStatus = WaLinkStatus.DISCONNECTED,
    val pairingCode: String = "",
    val detectedOwnerPhone: String = "",
    val statusDetail: String = "Open WhatsApp Link Portal to connect via QR or 8-digit phone link code.",
    val notificationAccessGranted: Boolean = false,
    val activeChatName: String = "",
    val repliesSentCount: Int = 0,
    val moderatedCount: Int = 0,
    val commandsExecutedCount: Int = 0,
    val blockedUnauthorizedCount: Int = 0,
    val scheduledSentCount: Int = 0,
    val lastLatencyMs: Long = 0L,
    val isGeneratingReply: Boolean = false,
    val recentEvents: List<WaBotEvent> = emptyList()
)

sealed class WaCommandAction {
    data class ToggleAntiLink(val enable: Boolean) : WaCommandAction()
    data class ToggleAntiSticker(val enable: Boolean) : WaCommandAction()
    data class ToggleAntiForward(val enable: Boolean) : WaCommandAction()
    data class ToggleAutoReply(val enable: Boolean) : WaCommandAction()
    data class ToggleCommands(val enable: Boolean) : WaCommandAction()
    data class AskAi(val query: String) : WaCommandAction()
    data class RunComputerTask(val taskPrompt: String) : WaCommandAction()
    data class BroadcastSay(val text: String) : WaCommandAction()
    data object ShowStatus : WaCommandAction()
    data object ShowHelp : WaCommandAction()
    data object Ping : WaCommandAction()
}

object WaChatKeyHelper {
    fun normalize(displayName: String, isGroup: Boolean): String {
        val clean = displayName.trim().lowercase().replace(Regex("\\s+"), " ")
        val prefix = if (isGroup) "grp::" else "dm::"
        return prefix + clean
    }

    fun fallbackDisplayName(raw: String): String = raw.trim().ifBlank { "WhatsApp Chat" }
}

object WaModerationDetector {
    private val urlRegex = Regex(
        """(https?://\S+|www\.\S+|wa\.me/\S+|chat\.whatsapp\.com/\S+|t\.me/\S+|bit\.ly/\S+|[a-z0-9-]+\.(com|net|org|io|xyz|bd|me|co|info|online|shop)/\S*)""",
        RegexOption.IGNORE_CASE
    )

    fun containsLink(text: String): Boolean {
        if (text.isBlank()) return false
        return urlRegex.containsMatchIn(text)
    }

    fun extractFirstLink(text: String): String? = urlRegex.find(text)?.value

    fun looksLikeStickerText(text: String): Boolean {
        val lower = text.trim().lowercase()
        return lower == "[sticker]" ||
            lower == "sticker" ||
            lower.startsWith("💟 sticker") ||
            lower == "🖼️ sticker"
    }
}

object WaAccessGuard {
    private val invisibleChars = Regex("[\u200B-\u200F\uFEFF\u202A-\u202E]")

    fun extractPhoneDigits(raw: String): String =
        raw.replace(Regex("[^0-9]"), "")

    fun looksLikePhoneTarget(raw: String): Boolean {
        val digits = extractPhoneDigits(raw)
        val nonPhoneLetters = raw.replace(Regex("[0-9+\\-()\\s]"), "")
        return digits.length in 7..15 && nonPhoneLetters.isEmpty()
    }

    fun formatPhoneForWaUrl(raw: String): String? {
        val digits = extractPhoneDigits(raw)
        if (digits.length !in 7..15) return null
        // Convert local Bangladesh 01XXXXXXXXX -> 8801XXXXXXXXX when missing country code
        return if (digits.length == 11 && digits.startsWith("01")) "88$digits" else digits
    }

    fun phoneMatches(candidateRaw: String, ownerRaw: String): Boolean {
        val candDigits = extractPhoneDigits(candidateRaw)
        val ownerDigits = extractPhoneDigits(ownerRaw)
        if (candDigits.length < 7 || ownerDigits.length < 7) return false
        if (candDigits == ownerDigits) return true
        val tailLen = minOf(10, minOf(candDigits.length, ownerDigits.length))
        if (tailLen < 8) return false
        return candDigits.takeLast(tailLen) == ownerDigits.takeLast(tailLen)
    }

    fun isOwnerMessage(
        message: WaIncomingMessage,
        configuredOwnerPhone: String,
        configuredOwnerName: String
    ): Boolean {
        // Explicit outgoing command typed by the Owner directly in WhatsApp / WA Web
        if (message.isFromMe) return true

        val ownerPhone = configuredOwnerPhone.trim()
        if (ownerPhone.isNotBlank()) {
            if (phoneMatches(message.senderPhoneHint, ownerPhone)) return true
            if (phoneMatches(message.senderName, ownerPhone)) return true
            if (!message.isGroup && phoneMatches(message.chatName, ownerPhone)) return true
        }

        val ownerName = configuredOwnerName.trim()
        if (ownerName.isNotBlank()) {
            val senderLower = message.senderName.trim().lowercase()
            if (senderLower == ownerName.lowercase()) return true
            if (!message.isGroup && message.chatName.trim().lowercase() == ownerName.lowercase()) return true
        }

        val senderNorm = message.senderName.trim().lowercase()
        if (senderNorm == "you" || senderNorm == "me" || senderNorm.endsWith("(you)")) return true

        return false
    }

    fun normalizeChatNameForDedup(chatName: String): String {
        val cleaned = invisibleChars.replace(chatName, "")
            .replace(Regex("\\s*\\(\\+?\\d[\\d\\s\\-]{6,}\\)\\s*$"), "")
            .replace(Regex("[^\\p{L}\\p{N}]"), "")
            .lowercase()
        return cleaned.ifBlank { chatName.trim().lowercase() }
    }

    fun normalizeTextForDedup(text: String): String =
        invisibleChars.replace(text, "")
            .trim()
            .replace(Regex("\\s+"), " ")
            .lowercase()
            .take(120)

    fun sanitizeHumanReply(rawReply: String): String {
        var out = invisibleChars.replace(rawReply, "").trim()
        // Strip leading AI meta labels or robotic disclaimers
        out = out.replace(Regex("^(assistant|bot|ai|reply)\\s*:\\s*", RegexOption.IGNORE_CASE), "")
        out = out.replace(Regex("^(as an ai( language model)?[,:.\\s]*)", RegexOption.IGNORE_CASE), "")
        if ((out.startsWith("\"") && out.endsWith("\"") && out.length > 2) ||
            (out.startsWith("'") && out.endsWith("'") && out.length > 2)
        ) {
            out = out.substring(1, out.length - 1).trim()
        }
        return out
    }
}

object WaCommandParser {
    private val supportedPrefixes = setOf('.', '!', '/')

    fun parse(rawText: String): WaCommandAction? {
        val trimmed = rawText.trim()
        if (trimmed.length < 2 || trimmed.first() !in supportedPrefixes) return null

        val withoutPrefix = trimmed.drop(1).trim()
        if (withoutPrefix.isEmpty()) return null

        val parts = withoutPrefix.split(Regex("\\s+"), limit = 2)
        val cmd = parts[0].lowercase()
        val arg = parts.getOrNull(1)?.trim().orEmpty()
        val argLower = arg.lowercase()

        fun parseOnOff(defaultValue: Boolean = true): Boolean {
            return when (argLower) {
                "on", "enable", "enabled", "true", "1", "start", "চালু", "অন" -> true
                "off", "disable", "disabled", "false", "0", "stop", "বন্ধ", "অফ" -> false
                else -> defaultValue
            }
        }

        return when (cmd) {
            "antilink", "anti-link", "linkblock", "এন্টিলিংক" ->
                WaCommandAction.ToggleAntiLink(parseOnOff(true))

            "antisticker", "anti-sticker", "stickerblock", "এন্টিস্টিকার" ->
                WaCommandAction.ToggleAntiSticker(parseOnOff(true))

            "antiforward", "anti-forward", "antispam", "এন্টিস্প্যাম" ->
                WaCommandAction.ToggleAntiForward(parseOnOff(true))

            "autoreply", "auto-reply", "automsg", "ai-reply", "bot", "অটোরিপ্লাই", "অটোমেসেজ" ->
                WaCommandAction.ToggleAutoReply(parseOnOff(true))

            "mute" -> WaCommandAction.ToggleAutoReply(false)
            "unmute" -> WaCommandAction.ToggleAutoReply(true)

            "commands", "cmd" -> {
                if (arg.isNotBlank() && cmd == "cmd" && argLower !in setOf("on", "off", "enable", "disable")) {
                    WaCommandAction.RunComputerTask(arg)
                } else {
                    WaCommandAction.ToggleCommands(parseOnOff(true))
                }
            }

            "task", "run", "do", "server", "এজেন্ট" -> {
                if (arg.isNotBlank()) WaCommandAction.RunComputerTask(arg) else WaCommandAction.ShowHelp
            }

            "ai", "ask", "gpt", "gemini", "বট" -> {
                if (arg.isNotBlank()) WaCommandAction.AskAi(arg) else WaCommandAction.ShowHelp
            }

            "say", "echo" -> {
                if (arg.isNotBlank()) WaCommandAction.BroadcastSay(arg) else null
            }

            "status", "rules", "info", "groupinfo", "স্ট্যাটাস" -> WaCommandAction.ShowStatus
            "help", "menu", "commandslist", "হেল্প" -> WaCommandAction.ShowHelp
            "ping", "test" -> WaCommandAction.Ping
            else -> null
        }
    }

    fun helpText(): String = """
        🤖 *WhatsApp AI Bot Commands* (Owner-Only Secured • Prefix: `.` / `!` / `/`)
        • `.autoreply on` / `off` — এই চ্যাট/গ্রুপে AI অটো-রিপ্লাই চালু বা বন্ধ
        • `.antilink on` / `off` — গ্রুপে কেউ লিংক দিলে অটো-ডিলিট চালু/বন্ধ
        • `.antisticker on` / `off` — গ্রুপে স্টিকার দিলে অটো-ডিলিট চালু/বন্ধ
        • `.antiforward on` / `off` — ফরওয়ার্ডেড স্প্যাম ডিলিট চালু/বন্ধ
        • `.ai <প্রশ্ন>` — শুধুই Owner: সরাসরি AI ব্রেইনে প্রশ্ন ও উত্তর
        • `.cmd <কাজ>` — শুধুই Owner: AI Computer-এ সরাসরি টাস্ক চালানো
        • `.status` — বর্তমান গ্রুপ/চ্যাটের সব রুলস দেখা
        • `.ping` — স্পিড ও লিংক স্ট্যাটাস চেক
    """.trimIndent()
}
