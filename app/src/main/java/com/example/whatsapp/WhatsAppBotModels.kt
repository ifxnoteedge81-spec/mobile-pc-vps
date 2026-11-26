package com.example.whatsapp

enum class WaLinkStatus(val label: String) {
    DISCONNECTED("Not Linked"),
    LOADING("Loading WhatsApp Web..."),
    WAITING_PAIRING("Scan QR or Enter Link Code"),
    LINKED("WhatsApp Linked (Live)"),
    ERROR("Connection Issue")
}

data class WaBotEvent(
    val id: Long = System.nanoTime(),
    val kind: String, // COMMAND, MODERATION, AI_REPLY, SENT, LINK, ERROR
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
    val statusDetail: String = "Open WhatsApp Link Portal to connect via QR or 8-digit phone link code.",
    val notificationAccessGranted: Boolean = false,
    val activeChatName: String = "",
    val repliesSentCount: Int = 0,
    val moderatedCount: Int = 0,
    val commandsExecutedCount: Int = 0,
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

            "commands", "cmd" -> WaCommandAction.ToggleCommands(parseOnOff(true))

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
        🤖 *WhatsApp AI Bot Commands* (Prefix: `.` / `!` / `/`)
        • `.autoreply on` / `off` — এই চ্যাট/গ্রুপে AI অটো-রিপ্লাই চালু বা বন্ধ
        • `.antilink on` / `off` — গ্রুপে কেউ লিংক দিলে অটো-ডিলিট চালু/বন্ধ
        • `.antisticker on` / `off` — গ্রুপে স্টিকার দিলে অটো-ডিলিট চালু/বন্ধ
        • `.antiforward on` / `off` — ফরওয়ার্ডেড স্প্যাম ডিলিট চালু/বন্ধ
        • `.ai <প্রশ্ন>` — সরাসরি AI ব্রেইনকে কোনো প্রশ্ন করা
        • `.status` — বর্তমান গ্রুপ/চ্যাটের সব রুলস দেখা
        • `.ping` — জিরো-ল্যাটেন্সি স্পিড চেক
    """.trimIndent()
}
