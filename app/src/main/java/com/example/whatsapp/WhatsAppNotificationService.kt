package com.example.whatsapp

import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import com.example.PersonalAiApplication
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Companion NotificationListenerService for com.whatsapp & com.whatsapp.w4b.
 * Filters aggregate summary notifications & self-echoes cleanly to prevent double replies,
 * while providing background RemoteInput quick-reply handles.
 */
class WhatsAppNotificationService : NotificationListenerService() {

    data class QuickReplyHandle(
        val pendingIntent: PendingIntent,
        val remoteInputs: Array<RemoteInput>,
        val capturedAtMs: Long = System.currentTimeMillis()
    )

    override fun onListenerConnected() {
        runCatching {
            super.onListenerConnected()
            _isConnected.value = true
            val app = application as? PersonalAiApplication
            app?.container?.whatsAppBotEngine?.refreshNotificationStatus()
        }
    }

    override fun onListenerDisconnected() {
        runCatching {
            super.onListenerDisconnected()
            _isConnected.value = false
            val app = application as? PersonalAiApplication
            app?.container?.whatsAppBotEngine?.refreshNotificationStatus()
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        runCatching {
            val notif = sbn?.notification ?: return
            val pkg = sbn.packageName?.lowercase().orEmpty()
            if (pkg != WHATSAPP_PKG && pkg != WHATSAPP_BUSINESS_PKG) return
            if ((notif.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return

            val extras = notif.extras ?: return
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
            val convTitle = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()?.trim().orEmpty()
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
            if (title.isBlank() || text.isBlank()) return

            if (shouldIgnoreSystemOrSummaryNotification(title, text)) return

            val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notif)
            val selfName = style?.user?.name?.toString()?.trim().orEmpty()
            val latestMsg = style?.messages?.lastOrNull()
            val latestPersonName = latestMsg?.person?.name?.toString()?.trim().orEmpty()
            if (latestPersonName.isNotBlank() &&
                (latestPersonName.equals("You", ignoreCase = true) ||
                    (selfName.isNotBlank() && latestPersonName.equals(selfName, ignoreCase = true)))
            ) {
                val latestText = latestMsg?.text?.toString()?.trim().orEmpty()
                if (WaCommandParser.parse(latestText) == null) return
            }

            val isGroup = convTitle.isNotBlank() || title.contains(": ")
            val chatName = when {
                convTitle.isNotBlank() -> convTitle
                title.contains(": ") -> title.substringBefore(": ").trim()
                else -> title
            }
            val senderName = when {
                latestPersonName.isNotBlank() -> latestPersonName
                convTitle.isNotBlank() && title.contains(": ") -> title.substringAfter(": ").trim()
                convTitle.isNotBlank() -> title
                title.contains(": ") -> title.substringAfter(": ").trim()
                else -> title
            }
            val phoneHint = WaAccessGuard.extractPhoneDigits(senderName)
                .ifBlank { WaAccessGuard.extractPhoneDigits(chatName) }
                .takeIf { it.length in 7..15 }
                .orEmpty()

            val contextLines = extractMessagingStyleLines(style)
            val replyAction = findReplyAction(notif)
            val chatKey = WaChatKeyHelper.normalize(chatName, isGroup)
            val dedupChatKey = WaAccessGuard.normalizeChatNameForDedup(chatName)
            if (replyAction != null) {
                quickReplyHandles[chatKey] = replyAction
                quickReplyHandles[chatName.lowercase()] = replyAction
                quickReplyHandles[dedupChatKey] = replyAction
            }

            val bodyLower = text.lowercase()
            val isSticker = WaModerationDetector.looksLikeStickerText(bodyLower) ||
                (bodyLower.contains("sticker") && bodyLower.length <= 24)

            val incoming = WaIncomingMessage(
                chatName = chatName,
                senderName = senderName,
                isGroup = isGroup,
                body = text,
                hasLink = WaModerationDetector.containsLink(text),
                isSticker = isSticker,
                isForwarded = bodyLower.contains("forwarded"),
                isFromMe = false,
                senderPhoneHint = phoneHint,
                sourceChannel = "NOTIFICATION",
                recentContextLines = contextLines
            )

            val app = application as? PersonalAiApplication ?: return
            app.container.whatsAppBotEngine.onIncomingNotification(incoming)
        }
    }

    private fun extractMessagingStyleLines(style: NotificationCompat.MessagingStyle?): List<String> {
        if (style == null) return emptyList()
        return runCatching {
            style.messages.takeLast(10).mapNotNull { m ->
                val msgText = m.text?.toString()?.trim().orEmpty()
                if (msgText.isBlank()) null
                else {
                    val who = m.person?.name?.toString()?.trim().orEmpty().ifBlank { "Me" }
                    "$who: $msgText"
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun findReplyAction(notif: Notification): QuickReplyHandle? {
        return runCatching {
            val count = NotificationCompat.getActionCount(notif)
            for (i in 0 until count) {
                val action = NotificationCompat.getAction(notif, i) ?: continue
                val inputs = action.remoteInputs
                val pi = action.actionIntent
                if (!inputs.isNullOrEmpty() && pi != null) {
                    return@runCatching QuickReplyHandle(pendingIntent = pi, remoteInputs = inputs)
                }
            }
            null
        }.getOrNull()
    }

    companion object {
        const val WHATSAPP_PKG = "com.whatsapp"
        const val WHATSAPP_BUSINESS_PKG = "com.whatsapp.w4b"

        private val _isConnected = MutableStateFlow(false)
        val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

        private val quickReplyHandles = ConcurrentHashMap<String, QuickReplyHandle>()
        private val summaryCountRegex = Regex("""^\d+\s+(new\s+)?messages?(\s+from\s+\d+\s+chats?)?$""", RegexOption.IGNORE_CASE)

        fun shouldIgnoreSystemOrSummaryNotification(title: String, text: String): Boolean {
            if (title.equals("WhatsApp", ignoreCase = true) || title.equals("WhatsApp Business", ignoreCase = true)) {
                return true
            }
            val lower = text.trim().lowercase()
            if (summaryCountRegex.matches(lower)) return true
            val ignoredFragments = listOf(
                "checking for new messages",
                "whatsapp web is currently active",
                "backup in progress",
                "restoring media",
                "missed voice call",
                "missed video call",
                "ongoing voice call",
                "ongoing video call",
                "this message was deleted"
            )
            return ignoredFragments.any { lower.contains(it) }
        }

        fun isPermissionEnabled(context: Context): Boolean {
            if (_isConnected.value) return true
            return runCatching {
                val enabled = Settings.Secure.getString(
                    context.contentResolver,
                    "enabled_notification_listeners"
                ).orEmpty()
                val flat = ComponentName(context, WhatsAppNotificationService::class.java).flattenToString()
                enabled.contains(flat) || enabled.contains(WhatsAppNotificationService::class.java.simpleName)
            }.getOrDefault(false)
        }

        fun openNotificationListenerSettings(context: Context): Result<String> {
            return runCatching {
                val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                "Opened Notification Access settings — enable Personal AI Computer for background WhatsApp replies."
            }
        }

        fun sendQuickReply(context: Context, chatKey: String, chatName: String, replyText: String): Boolean {
            val handle = quickReplyHandles[chatKey]
                ?: quickReplyHandles[chatName.trim().lowercase()]
                ?: quickReplyHandles[WaAccessGuard.normalizeChatNameForDedup(chatName)]
                ?: return false
            if (System.currentTimeMillis() - handle.capturedAtMs > 15 * 60_000L) {
                return false
            }
            return runCatching {
                val intent = Intent()
                val results = Bundle()
                for (ri in handle.remoteInputs) {
                    results.putCharSequence(ri.resultKey, replyText)
                }
                RemoteInput.addResultsToIntent(handle.remoteInputs, intent, results)
                handle.pendingIntent.send(context, 0, intent)
                true
            }.getOrDefault(false)
        }
    }
}
