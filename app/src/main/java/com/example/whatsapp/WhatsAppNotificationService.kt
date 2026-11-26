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
 * Gives sub-second background auto-replies via Android RemoteInput (even when the WhatsApp
 * WebView portal is not foregrounded) and shares full MessagingStyle conversation context.
 */
class WhatsAppNotificationService : NotificationListenerService() {

    data class QuickReplyHandle(
        val pendingIntent: PendingIntent,
        val remoteInputs: Array<RemoteInput>,
        val capturedAtMs: Long = System.currentTimeMillis()
    )

    override fun onListenerConnected() {
        super.onListenerConnected()
        _isConnected.value = true
        runCatching {
            val app = application as? PersonalAiApplication
            app?.container?.whatsAppBotEngine?.refreshNotificationStatus()
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        _isConnected.value = false
        runCatching {
            val app = application as? PersonalAiApplication
            app?.container?.whatsAppBotEngine?.refreshNotificationStatus()
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notif = sbn?.notification ?: return
        val pkg = sbn.packageName?.lowercase().orEmpty()
        if (pkg != WHATSAPP_PKG && pkg != WHATSAPP_BUSINESS_PKG) return
        if ((notif.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return

        val extras = notif.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val convTitle = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()?.trim().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        if (title.isBlank() || text.isBlank()) return

        // Ignore system status notifications like "Checking for new messages"
        if (text.contains("Checking for new messages", ignoreCase = true) ||
            text.contains("WhatsApp Web is currently active", ignoreCase = true) ||
            title.equals("WhatsApp", ignoreCase = true)
        ) {
            return
        }

        val isGroup = convTitle.isNotBlank() || title.contains(":")
        val chatName = when {
            convTitle.isNotBlank() -> convTitle
            title.contains(": ") -> title.substringBefore(": ").trim()
            else -> title
        }
        val senderName = when {
            convTitle.isNotBlank() && title.contains(": ") -> title.substringAfter(": ").trim()
            !convTitle.isNullOrBlank() -> title
            title.contains(": ") -> title.substringAfter(": ").trim()
            else -> title
        }

        val contextLines = extractMessagingStyleLines(notif)
        val replyAction = findReplyAction(notif)
        val chatKey = WaChatKeyHelper.normalize(chatName, isGroup)
        if (replyAction != null) {
            quickReplyHandles[chatKey] = replyAction
            quickReplyHandles[chatName.lowercase()] = replyAction
        }

        val bodyLower = text.lowercase()
        val isSticker = WaModerationDetector.looksLikeStickerText(bodyLower) ||
            bodyLower.contains("sticker") && bodyLower.length <= 24

        val incoming = WaIncomingMessage(
            chatName = chatName,
            senderName = senderName,
            isGroup = isGroup,
            body = text,
            hasLink = WaModerationDetector.containsLink(text),
            isSticker = isSticker,
            isForwarded = bodyLower.contains("forwarded"),
            isFromMe = false,
            sourceChannel = "NOTIFICATION",
            recentContextLines = contextLines
        )

        val app = application as? PersonalAiApplication ?: return
        app.container.whatsAppBotEngine.onIncomingNotification(incoming)
    }

    private fun extractMessagingStyleLines(notif: Notification): List<String> {
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notif)
            ?: return emptyList()
        return style.messages.takeLast(10).mapNotNull { m ->
            val msgText = m.text?.toString()?.trim().orEmpty()
            if (msgText.isBlank()) null
            else {
                val who = m.person?.name?.toString()?.trim().orEmpty().ifBlank { "Me" }
                "$who: $msgText"
            }
        }
    }

    private fun findReplyAction(notif: Notification): QuickReplyHandle? {
        val count = NotificationCompat.getActionCount(notif)
        for (i in 0 until count) {
            val action = NotificationCompat.getAction(notif, i) ?: continue
            val inputs = action.remoteInputs
            val pi = action.actionIntent
            if (!inputs.isNullOrEmpty() && pi != null) {
                return QuickReplyHandle(pendingIntent = pi, remoteInputs = inputs)
            }
        }
        return null
    }

    companion object {
        const val WHATSAPP_PKG = "com.whatsapp"
        const val WHATSAPP_BUSINESS_PKG = "com.whatsapp.w4b"

        private val _isConnected = MutableStateFlow(false)
        val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

        private val quickReplyHandles = ConcurrentHashMap<String, QuickReplyHandle>()

        fun isPermissionEnabled(context: Context): Boolean {
            if (_isConnected.value) return true
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ).orEmpty()
            val flat = ComponentName(context, WhatsAppNotificationService::class.java).flattenToString()
            return enabled.contains(flat) || enabled.contains(WhatsAppNotificationService::class.java.simpleName)
        }

        fun openNotificationListenerSettings(context: Context): Result<String> {
            return runCatching {
                val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                "Opened Notification Access settings — enable Personal AI Computer for zero-latency background WhatsApp replies."
            }
        }

        fun sendQuickReply(context: Context, chatKey: String, chatName: String, replyText: String): Boolean {
            val handle = quickReplyHandles[chatKey]
                ?: quickReplyHandles[chatName.trim().lowercase()]
                ?: return false
            // Expired notification handles > 15 min old may fail; let caller fall back cleanly
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
