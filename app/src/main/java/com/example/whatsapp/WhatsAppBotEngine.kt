package com.example.whatsapp

import android.content.Context
import com.example.ai.AiMessage
import com.example.ai.AiModelRouter
import com.example.ai.TaskComplexity
import com.example.storage.AgentRepository
import com.example.storage.AppSettings
import com.example.storage.SettingsDataStore
import com.example.storage.WhatsAppChatEntity
import com.example.storage.WhatsAppMessageEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * WhatsApp AI Brain & Zero-Latency Management Bot Engine.
 * - Cross-channel sliding deduplication + per-chat Mutex -> ZERO double replies.
 * - Strict Owner-Only & Whitelist Access Control -> unauthorized contacts cannot run bot/AI commands or get unwanted replies.
 * - Human-like Bangla/Banglish/English replies via sanitized conversational prompts.
 * - Single-Coordinator Bot <-> AI sync + Scheduled Multi-Day Message Dispatch.
 */
class WhatsAppBotEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    private val repository: AgentRepository,
    private val settingsStore: SettingsDataStore,
    private val aiRouter: AiModelRouter,
    val webBridge: WhatsAppWebBridge,
    private val miniAiBrain: com.example.ai.LocalMiniAiBrain? = null
) : WhatsAppWebBridge.BridgeListener {

    private val _state = MutableStateFlow(
        WaRuntimeState(
            notificationAccessGranted = WhatsAppNotificationService.isPermissionEnabled(context)
        )
    )
    val state: StateFlow<WaRuntimeState> = _state.asStateFlow()

    // Sliding deduplication caches (replaces fixed 8s bucket to prevent boundary races between WA_WEB & NOTIFICATION)
    private val recentInboundByChatAndBody = ConcurrentHashMap<String, Long>()
    private val recentInboundByBody = ConcurrentHashMap<String, Long>()
    // Prevents bot from ever echoing its own outgoing replies / command outputs / scheduled messages
    private val recentBotOutgoingFingerprints = ConcurrentHashMap<String, Long>()
    // Prevents hammering unread chats repeatedly in tight loops
    private val lastUnreadScanByChat = ConcurrentHashMap<String, Long>()
    // Per-chat mutex guarantees strict single-threaded inbound handling per conversation
    private val chatMutexes = ConcurrentHashMap<String, Mutex>()
    private val dispatchMutex = Mutex()

    @Volatile
    private var ownerComputerTaskBridge: (suspend (String) -> String)? = null

    init {
        webBridge.setListener(this)
    }

    fun attachComputerTaskBridge(bridge: suspend (String) -> String) {
        ownerComputerTaskBridge = bridge
    }

    fun refreshNotificationStatus() {
        val granted = WhatsAppNotificationService.isPermissionEnabled(context)
        _state.update { it.copy(notificationAccessGranted = granted) }
    }

    override fun onOwnerPhoneDetected(phoneDigits: String) {
        _state.update { it.copy(detectedOwnerPhone = phoneDigits) }
        scope.launch(Dispatchers.IO) {
            runCatching {
                val current = settingsStore.settings.first()
                if (current.waOwnerPhone.isBlank() && phoneDigits.isNotBlank()) {
                    settingsStore.updateWhatsAppBotConfig(ownerPhone = "+$phoneDigits")
                }
            }
        }
    }

    override fun onStatusChanged(status: WaLinkStatus, detail: String) {
        _state.update {
            it.copy(
                linkStatus = status,
                pairingCode = webBridge.pairingCode.value,
                detectedOwnerPhone = webBridge.detectedOwnerPhone.value,
                statusDetail = detail,
                activeChatName = webBridge.activeChatName.value
            )
        }
    }

    override fun onChatsSynced(chats: List<WaSyncedChat>) {
        if (chats.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            runCatching {
                val settings = settingsStore.settings.first()
                chats.forEach { synced ->
                    val existing = repository.getWhatsAppChatByKey(synced.chatKey)
                    val updated = if (existing == null) {
                        val whitelistOnly = settings.waWhitelistOnlyMode
                        WhatsAppChatEntity(
                            chatKey = synced.chatKey,
                            displayName = synced.displayName,
                            isGroup = synced.isGroup,
                            autoReplyEnabled = if (whitelistOnly) false else if (synced.isGroup) settings.waDefaultAutoReplyGroup else settings.waDefaultAutoReplyDm,
                            antiLinkEnabled = if (whitelistOnly) false else synced.isGroup && settings.waGlobalAntiLink,
                            antiStickerEnabled = if (whitelistOnly) false else synced.isGroup && settings.waGlobalAntiSticker,
                            antiForwardEnabled = if (whitelistOnly) false else synced.isGroup && settings.waGlobalAntiForward,
                            commandsEnabled = !settings.waOwnerOnlyCommands,
                            lastMessagePreview = synced.lastPreview,
                            lastMessageAt = System.currentTimeMillis(),
                            unreadCount = synced.unreadCount
                        )
                    } else {
                        existing.copy(
                            displayName = synced.displayName,
                            lastMessagePreview = synced.lastPreview.ifBlank { existing.lastMessagePreview },
                            unreadCount = synced.unreadCount
                        )
                    }
                    repository.upsertWhatsAppChat(updated)
                }
            }
        }
    }

    override fun onUnreadChatDetected(chatName: String, isGroup: Boolean) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                val settings = settingsStore.settings.first()
                if (!settings.waBotEnabled) return@runCatching
                val chatKey = WaChatKeyHelper.normalize(chatName, isGroup)
                val chat = repository.getWhatsAppChatByKey(chatKey)
                val isOwnerChat = !isGroup && WaAccessGuard.phoneMatches(
                    chatName,
                    settings.waOwnerPhone.ifBlank { webBridge.detectedOwnerPhone.value }
                )
                val wantsHandling = if (settings.waWhitelistOnlyMode) {
                    isOwnerChat || (chat != null && (chat.autoReplyEnabled || chat.antiLinkEnabled || chat.antiStickerEnabled || chat.antiForwardEnabled))
                } else {
                    chat?.let {
                        it.autoReplyEnabled || it.antiLinkEnabled || it.antiStickerEnabled
                    } ?: (if (isGroup) settings.waDefaultAutoReplyGroup || settings.waGlobalAntiLink || settings.waGlobalAntiSticker else settings.waDefaultAutoReplyDm)
                }
                if (!wantsHandling) return@runCatching

                val now = System.currentTimeMillis()
                val prev = lastUnreadScanByChat[chatKey] ?: 0L
                if (now - prev < 5_000L) return@runCatching
                lastUnreadScanByChat[chatKey] = now

                if (!webBridge.activeChatName.value.equals(chatName, ignoreCase = true)) {
                    webBridge.openChatAndReadMessages(chatName)
                }
            }
        }
    }

    override fun onIncomingMessage(message: WaIncomingMessage) {
        scope.launch(Dispatchers.IO) {
            runCatching { processIncomingMessage(message) }
        }
    }

    fun onIncomingNotification(message: WaIncomingMessage) {
        scope.launch(Dispatchers.IO) {
            runCatching { processIncomingMessage(message) }
        }
    }

    suspend fun processIncomingMessage(message: WaIncomingMessage) {
        val bodyClean = message.body.trim()
        if (bodyClean.isEmpty()) return

        val chatKey = WaChatKeyHelper.normalize(message.chatName, message.isGroup)
        val mutex = chatMutexes.computeIfAbsent(chatKey) { Mutex() }

        mutex.withLock {
            val startedAt = System.currentTimeMillis()
            val normChat = WaAccessGuard.normalizeChatNameForDedup(message.chatName)
            val normBody = WaAccessGuard.normalizeTextForDedup(bodyClean)
            if (normBody.isEmpty()) return

            // 1. Anti-self-loop check against bot's own recent outgoing replies (60s sliding window)
            val outKeyExact = "$normChat::$normBody"
            val lastOutChatAt = recentBotOutgoingFingerprints[outKeyExact]
            val lastOutBodyAt = recentBotOutgoingFingerprints["body::$normBody"]
            if ((lastOutChatAt != null && startedAt - lastOutChatAt < 60_000L) ||
                (lastOutBodyAt != null && startedAt - lastOutBodyAt < 25_000L)
            ) {
                return
            }

            // 2. Sliding cross-channel deduplication (WA_WEB DOM <-> Android NOTIFICATION)
            val chatBodyKey = "$normChat::$normBody"
            val lastSeenSameChat = recentInboundByChatAndBody[chatBodyKey]
            if (lastSeenSameChat != null && startedAt - lastSeenSameChat < 25_000L) {
                return
            }
            val lastSeenGlobalBody = recentInboundByBody[normBody]
            if (lastSeenGlobalBody != null && startedAt - lastSeenGlobalBody < 6_000L) {
                // Same exact text received across both channels within 6s (e.g. phone vs contact alias)
                return
            }
            recentInboundByChatAndBody[chatBodyKey] = startedAt
            recentInboundByBody[normBody] = startedAt
            pruneOldFingerprints(startedAt)

            val settings = settingsStore.settings.first()
            val effectiveOwnerPhone = settings.waOwnerPhone.ifBlank { webBridge.detectedOwnerPhone.value }
            val isOwner = WaAccessGuard.isOwnerMessage(
                message = message,
                configuredOwnerPhone = effectiveOwnerPhone,
                configuredOwnerName = settings.waOwnerName
            )

            // Parse potential command BEFORE checking isFromMe so Owner commands typed on WA work!
            val parsedCmd = WaCommandParser.parse(bodyClean)
            if (message.isFromMe && parsedCmd == null) {
                // Normal outgoing message sent manually by user — never auto-reply to self
                return
            }

            val chat = ensureChatRecord(message.chatName, message.isGroup, bodyClean, settings)

            val msgType = when {
                parsedCmd != null -> "COMMAND"
                message.isSticker -> "STICKER"
                message.hasLink -> "LINK"
                else -> "TEXT"
            }
            repository.insertWhatsAppMessage(
                WhatsAppMessageEntity(
                    chatKey = chatKey,
                    chatName = message.chatName,
                    senderName = if (isOwner && message.isFromMe) "Owner" else message.senderName,
                    isFromMe = message.isFromMe,
                    isGroup = message.isGroup,
                    body = bodyClean,
                    messageType = msgType,
                    sourceChannel = message.sourceChannel,
                    timestamp = startedAt
                )
            )

            if (message.recentContextLines.isNotEmpty() && !message.isFromMe) {
                val existingCount = repository.getRecentWhatsAppMessages(chatKey, limit = 3).size
                if (existingCount <= 1) {
                    message.recentContextLines.dropLast(1).takeLast(6).forEach { line ->
                        val isMe = line.startsWith("Me:", ignoreCase = true)
                        val sender = if (isMe) "Me" else line.substringBefore(":").trim().ifBlank { message.senderName }
                        val content = line.substringAfter(":", line).trim()
                        if (content.isNotBlank() && content != bodyClean) {
                            repository.insertWhatsAppMessage(
                                WhatsAppMessageEntity(
                                    chatKey = chatKey,
                                    chatName = message.chatName,
                                    senderName = sender,
                                    isFromMe = isMe,
                                    isGroup = message.isGroup,
                                    body = content,
                                    messageType = "TEXT",
                                    sourceChannel = message.sourceChannel,
                                    timestamp = startedAt - 1000L
                                )
                            )
                        }
                    }
                }
            }

            if (!settings.waBotEnabled) return

            // 3. STRICT OWNER-ONLY COMMAND & AI SERVER ACCESS GATE
            if (parsedCmd != null) {
                val allowedToRunCmd = if (settings.waOwnerOnlyCommands) {
                    isOwner
                } else {
                    isOwner || chat.commandsEnabled
                }
                if (!allowedToRunCmd) {
                    _state.update { it.copy(blockedUnauthorizedCount = it.blockedUnauthorizedCount + 1) }
                    appendEvent(
                        kind = "BLOCKED",
                        chatName = message.chatName,
                        summary = "Unauthorized '${message.senderName}' tried command '${bodyClean.take(40)}' — silently blocked.",
                        latencyMs = (System.currentTimeMillis() - startedAt).coerceAtLeast(1L)
                    )
                    return
                }
                handleInChatCommand(parsedCmd, chat, message, settings, startedAt)
                return
            }

            // Never run moderation or auto-reply on Owner's own messages unless chatting in Self-Note
            if (message.isFromMe) return

            // 4. ZERO-LATENCY MODERATION (Only on approved/configured chats when Whitelist-Only is active)
            val allowGroupGlobals = !settings.waWhitelistOnlyMode && message.isGroup
            val antiLinkActive = chat.antiLinkEnabled || (allowGroupGlobals && settings.waGlobalAntiLink)
            val antiStickerActive = chat.antiStickerEnabled || (allowGroupGlobals && settings.waGlobalAntiSticker)
            val antiForwardActive = chat.antiForwardEnabled || (allowGroupGlobals && settings.waGlobalAntiForward)

            if (antiLinkActive && (message.hasLink || WaModerationDetector.containsLink(bodyClean))) {
                executeModerationDelete(
                    chat = chat,
                    message = message,
                    reason = "🚫 লিংক শেয়ার করা নিষেধ, তাই মেসেজটি সরানো হয়েছে।",
                    startedAt = startedAt
                )
                return
            }

            if (antiStickerActive && (message.isSticker || WaModerationDetector.looksLikeStickerText(bodyClean))) {
                executeModerationDelete(
                    chat = chat,
                    message = message,
                    reason = "🚫 এই গ্রুপে স্টিকার বন্ধ আছে।",
                    startedAt = startedAt
                )
                return
            }

            if (antiForwardActive && message.isForwarded) {
                executeModerationDelete(
                    chat = chat,
                    message = message,
                    reason = "🚫 ফরওয়ার্ডেড স্প্যাম মেসেজ সরানো হয়েছে।",
                    startedAt = startedAt
                )
                return
            }

            // 5. STRICT WHITELIST AUTO-REPLY GATE (Never reply to unselected/unknown numbers!)
            val shouldAutoReply = if (settings.waWhitelistOnlyMode) {
                chat.autoReplyEnabled
            } else {
                chat.autoReplyEnabled
            }
            if (!shouldAutoReply) return

            generateAndSendAiReply(
                chat = chat,
                incomingBody = bodyClean,
                senderName = message.senderName,
                sourceChannel = message.sourceChannel,
                startedAt = startedAt
            )
        }
    }

    private suspend fun handleInChatCommand(
        cmd: WaCommandAction,
        chat: WhatsAppChatEntity,
        message: WaIncomingMessage,
        settings: AppSettings,
        startedAt: Long
    ) {
        val replyText: String = when (cmd) {
            is WaCommandAction.ToggleAntiLink -> {
                repository.upsertWhatsAppChat(chat.copy(antiLinkEnabled = cmd.enable))
                if (cmd.enable) "✅ Anti-Link চালু হয়েছে — এই চ্যাটে লিংক দিলে অটো-ডিলিট হবে।"
                else "⚪ Anti-Link বন্ধ করা হয়েছে।"
            }
            is WaCommandAction.ToggleAntiSticker -> {
                repository.upsertWhatsAppChat(chat.copy(antiStickerEnabled = cmd.enable))
                if (cmd.enable) "✅ Anti-Sticker চালু হয়েছে।"
                else "⚪ Anti-Sticker বন্ধ করা হয়েছে।"
            }
            is WaCommandAction.ToggleAntiForward -> {
                repository.upsertWhatsAppChat(chat.copy(antiForwardEnabled = cmd.enable))
                if (cmd.enable) "✅ Anti-Forward চালু হয়েছে।"
                else "⚪ Anti-Forward বন্ধ করা হয়েছে।"
            }
            is WaCommandAction.ToggleAutoReply -> {
                repository.upsertWhatsAppChat(chat.copy(autoReplyEnabled = cmd.enable))
                if (cmd.enable) "✅ '${chat.displayName}'-এ AI অটো-রিপ্লাই চালু হয়েছে।"
                else "⏸️ '${chat.displayName}'-এ অটো-রিপ্লাই বন্ধ করা হয়েছে।"
            }
            is WaCommandAction.ToggleCommands -> {
                repository.upsertWhatsAppChat(chat.copy(commandsEnabled = cmd.enable))
                if (cmd.enable) "✅ Bot Commands ON" else "⚪ Bot Commands OFF"
            }
            is WaCommandAction.Ping -> {
                val ms = (System.currentTimeMillis() - startedAt).coerceAtLeast(1L)
                "⚡ Online (${ms}ms • ${aiRouter.getActiveLabel(settings)} • Owner Lock Active)"
            }
            is WaCommandAction.ShowStatus -> {
                val antiLink = if (chat.antiLinkEnabled) "ON" else "OFF"
                val antiSticker = if (chat.antiStickerEnabled) "ON" else "OFF"
                val antiFwd = if (chat.antiForwardEnabled) "ON" else "OFF"
                val autoReply = if (chat.autoReplyEnabled) "ON" else "OFF"
                """
                📊 *${chat.displayName}* Status:
                • Auto-Reply: $autoReply | Whitelist Mode: ${if (settings.waWhitelistOnlyMode) "STRICT" else "OPEN"}
                • Anti-Link: $antiLink | Anti-Sticker: $antiSticker | Anti-Forward: $antiFwd
                • Owner-Only Access: ${if (settings.waOwnerOnlyCommands) "ON" else "OFF"}
                • AI Brain: ${aiRouter.getActiveLabel(settings)}
                """.trimIndent()
            }
            is WaCommandAction.ShowHelp -> WaCommandParser.helpText()
            is WaCommandAction.BroadcastSay -> cmd.text
            is WaCommandAction.AskAi -> {
                generateAndSendAiReply(
                    chat = chat,
                    incomingBody = cmd.query,
                    senderName = message.senderName,
                    sourceChannel = message.sourceChannel,
                    startedAt = startedAt
                )
                return
            }
            is WaCommandAction.RunComputerTask -> {
                val bridge = ownerComputerTaskBridge
                if (bridge == null) {
                    "⚠️ AI Computer Task Bridge সংযুক্ত নেই।"
                } else {
                    _state.update { it.copy(isGeneratingReply = true) }
                    try {
                        bridge(cmd.taskPrompt)
                    } finally {
                        _state.update { it.copy(isGeneratingReply = false) }
                    }
                }
            }
        }

        val sent = dispatchReply(chat.chatKey, chat.displayName, replyText, message.sourceChannel)
        val latency = (System.currentTimeMillis() - startedAt).coerceAtLeast(1L)
        if (sent.isSuccess) {
            recordOutgoingBotMessage(chat, replyText, "COMMAND", latency)
            _state.update {
                it.copy(
                    commandsExecutedCount = it.commandsExecutedCount + 1,
                    lastLatencyMs = latency
                )
            }
            appendEvent("COMMAND", chat.displayName, "${message.body.take(30)} -> OK", latency)
        } else {
            appendEvent("ERROR", chat.displayName, sent.exceptionOrNull()?.message ?: "Command reply failed", latency)
        }
    }

    private suspend fun executeModerationDelete(
        chat: WhatsAppChatEntity,
        message: WaIncomingMessage,
        reason: String,
        startedAt: Long
    ) {
        val delRes = webBridge.deleteOffendingMessage(chat.displayName, message.domMessageId, message.body)
        val latency = (System.currentTimeMillis() - startedAt).coerceAtLeast(1L)
        val statusMsg = if (delRes.isSuccess) reason else "$reason (⚠️ সতর্কবার্তা)"

        dispatchReply(chat.chatKey, chat.displayName, statusMsg, message.sourceChannel)
        repository.insertWhatsAppMessage(
            WhatsAppMessageEntity(
                chatKey = chat.chatKey,
                chatName = chat.displayName,
                senderName = "Moderation Bot",
                isFromMe = true,
                isGroup = chat.isGroup,
                body = statusMsg,
                messageType = "MODERATION",
                sourceChannel = message.sourceChannel,
                deletedByModeration = delRes.isSuccess,
                latencyMs = latency
            )
        )
        _state.update {
            it.copy(
                moderatedCount = it.moderatedCount + 1,
                lastLatencyMs = latency
            )
        }
        appendEvent("MODERATION", chat.displayName, statusMsg, latency)
    }

    private suspend fun generateAndSendAiReply(
        chat: WhatsAppChatEntity,
        incomingBody: String,
        senderName: String,
        sourceChannel: String,
        startedAt: Long
    ) {
        _state.update { it.copy(isGeneratingReply = true) }
        try {
            val settings = settingsStore.settings.first()
            if (settings.miniAiEnabled && miniAiBrain != null && chat.customTopicPrompt.isBlank()) {
                val miniHit = miniAiBrain.inferReply(
                    query = incomingBody,
                    minConfidence = (settings.miniAiTokenSaverConfidence + 0.04f).coerceAtMost(0.90f),
                    channel = "WHATSAPP"
                )
                if (miniHit != null) {
                    val sendRes = dispatchReply(chat.chatKey, chat.displayName, miniHit.reply, sourceChannel)
                    val latency = (System.currentTimeMillis() - startedAt).coerceAtLeast(1L)
                    if (sendRes.isSuccess) {
                        recordOutgoingBotMessage(chat, miniHit.reply, "AI_REPLY", latency)
                        _state.update {
                            it.copy(
                                repliesSentCount = it.repliesSentCount + 1,
                                lastLatencyMs = latency
                            )
                        }
                        appendEvent(
                            "AI_REPLY",
                            chat.displayName,
                            "Mini AI (0 Tokens): ${miniHit.reply.take(90)}",
                            latency
                        )
                    } else {
                        appendEvent("ERROR", chat.displayName, sendRes.exceptionOrNull()?.message ?: "Send failed", latency)
                    }
                    return
                }
            }

            val recentHistory = repository.getRecentWhatsAppMessages(chat.chatKey, limit = 14)
            val agentMemories = repository.getMemorySnapshot().take(5)

            val systemPrompt = buildString {
                appendLine(settings.waBotPersona)
                appendLine("Chat: ${chat.displayName} (${if (chat.isGroup) "Group" else "Private 1-on-1"})")
                if (chat.customTopicPrompt.isNotBlank()) {
                    appendLine("Special instructions for this chat: ${chat.customTopicPrompt}")
                }
                if (agentMemories.isNotEmpty()) {
                    appendLine("Owner Context:")
                    agentMemories.forEach { m -> appendLine("- ${m.memoryKey}: ${m.content}") }
                }
                appendLine("Conversation Style Guidelines:")
                appendLine("- Sound completely natural, warm, and human (like a real person texting on WhatsApp).")
                appendLine("- Match the exact language and tone of the sender (natural Bangla, Banglish, or English).")
                appendLine("- Do NOT use robotic templates, numbered lists for casual greetings, or 'As an AI...' disclaimers.")
                appendLine("- Give ONE direct, helpful reply (usually 1-3 short sentences). Output only the message text.")
            }

            val conversation = mutableListOf<AiMessage>()
            recentHistory.dropLast(1).forEach { msg ->
                if (msg.messageType == "MODERATION" || msg.messageType == "COMMAND") return@forEach
                conversation.add(
                    AiMessage(
                        role = if (msg.isFromMe) "assistant" else "user",
                        content = if (msg.isFromMe) msg.body else if (chat.isGroup) "${msg.senderName}: ${msg.body}" else msg.body
                    )
                )
            }
            conversation.add(
                AiMessage(
                    role = "user",
                    content = if (chat.isGroup) "$senderName: $incomingBody" else incomingBody
                )
            )

            val aiResult = aiRouter.complete(
                settings = settings,
                complexity = TaskComplexity.SIMPLE,
                systemPrompt = systemPrompt,
                messages = conversation,
                tools = emptyList(),
                fastMode = true
            )

            val rawReply = aiResult.getOrNull()?.text.orEmpty()
            val replyText = WaAccessGuard.sanitizeHumanReply(rawReply)
            if (replyText.isBlank()) {
                val err = aiResult.exceptionOrNull()?.message ?: "AI returned empty reply"
                appendEvent("ERROR", chat.displayName, err, System.currentTimeMillis() - startedAt)
                return
            }

            if (settings.miniAiEnabled) {
                miniAiBrain?.learnFromCloudTurn(incomingBody, replyText, channel = "WHATSAPP")
            }

            val sendRes = dispatchReply(chat.chatKey, chat.displayName, replyText, sourceChannel)
            val latency = (System.currentTimeMillis() - startedAt).coerceAtLeast(1L)
            if (sendRes.isSuccess) {
                recordOutgoingBotMessage(chat, replyText, "AI_REPLY", latency)
                _state.update {
                    it.copy(
                        repliesSentCount = it.repliesSentCount + 1,
                        lastLatencyMs = latency
                    )
                }
                appendEvent("AI_REPLY", chat.displayName, "$senderName: ${replyText.take(90)}", latency)
            } else {
                appendEvent("ERROR", chat.displayName, sendRes.exceptionOrNull()?.message ?: "Send failed", latency)
            }
        } finally {
            _state.update { it.copy(isGeneratingReply = false) }
        }
    }

    suspend fun sendManualOrAiMessage(
        targetChatName: String,
        isGroup: Boolean,
        messageOrPrompt: String,
        composeWithAi: Boolean
    ): Result<String> {
        val startedAt = System.currentTimeMillis()
        val cleanName = targetChatName.trim()
        if (cleanName.isBlank()) return Result.failure(IllegalArgumentException("Target contact, group name, or phone number required"))
        val settings = settingsStore.settings.first()
        val chat = ensureChatRecord(cleanName, isGroup, messageOrPrompt, settings)

        val finalBody = if (composeWithAi) {
            val history = repository.getRecentWhatsAppMessages(chat.chatKey, limit = 10)
            val convo = history.map {
                AiMessage(if (it.isFromMe) "assistant" else "user", "${it.senderName}: ${it.body}")
            } + AiMessage("user", "Write a warm, natural human WhatsApp message for this goal (reply ONLY with the message text): $messageOrPrompt")
            val aiRes = aiRouter.complete(
                settings = settings,
                complexity = TaskComplexity.SIMPLE,
                systemPrompt = settings.waBotPersona,
                messages = convo,
                tools = emptyList(),
                fastMode = true
            )
            WaAccessGuard.sanitizeHumanReply(aiRes.getOrElse { return Result.failure(it) }.text)
        } else {
            messageOrPrompt.trim()
        }

        if (finalBody.isBlank()) return Result.failure(IllegalStateException("Message body cannot be empty"))

        val sendRes = dispatchReply(chat.chatKey, cleanName, finalBody, preferredChannel = "WA_WEB")
        val latency = (System.currentTimeMillis() - startedAt).coerceAtLeast(1L)
        if (sendRes.isSuccess) {
            recordOutgoingBotMessage(chat, finalBody, if (composeWithAi) "AI_REPLY" else "SENT", latency)
            _state.update {
                it.copy(repliesSentCount = it.repliesSentCount + 1, lastLatencyMs = latency)
            }
            appendEvent("SENT", cleanName, finalBody.take(100), latency)
            return Result.success("Sent to $cleanName (${latency}ms): $finalBody")
        }
        return sendRes
    }

    /**
     * Executes a scheduled WhatsApp message/campaign item on its scheduled date & time.
     */
    suspend fun executeScheduledDispatch(scheduleId: Long): Result<String> {
        val item = repository.getWhatsAppScheduleById(scheduleId)
            ?: return Result.failure(IllegalStateException("Schedule #$scheduleId not found"))
        if (item.status != "PENDING") {
            return Result.success("Skipped (#$scheduleId status=${item.status})")
        }

        val resolvedPrompt = item.messageTemplate
            .replace("{day}", item.dayIndex.toString())
            .replace("{total}", item.totalDays.toString())

        val res = sendManualOrAiMessage(
            targetChatName = item.targetDisplay,
            isGroup = item.isGroup,
            messageOrPrompt = resolvedPrompt,
            composeWithAi = item.useAiCompose
        )
        res.fold(
            onSuccess = { summary ->
                repository.updateWhatsAppSchedule(
                    item.copy(status = "SENT", lastResult = summary.take(180))
                )
                _state.update { it.copy(scheduledSentCount = it.scheduledSentCount + 1) }
                appendEvent("SCHEDULED", item.targetDisplay, "Day ${item.dayIndex}/${item.totalDays}: ${summary.take(80)}", 0L)
            },
            onFailure = { err ->
                repository.updateWhatsAppSchedule(
                    item.copy(status = "FAILED", lastResult = (err.message ?: "Dispatch failed").take(180))
                )
                appendEvent("ERROR", item.targetDisplay, "Schedule #${item.id} failed: ${err.message}", 0L)
            }
        )
        return res
    }

    suspend fun sweepDueScheduledDispatches(): Int {
        val due = repository.getDueWhatsAppSchedules(System.currentTimeMillis())
        var count = 0
        for (item in due) {
            if (executeScheduledDispatch(item.id).isSuccess) count++
        }
        return count
    }

    suspend fun configureChatRules(
        chatName: String,
        isGroup: Boolean,
        autoReply: Boolean? = null,
        antiLink: Boolean? = null,
        antiSticker: Boolean? = null,
        antiForward: Boolean? = null,
        commandsEnabled: Boolean? = null,
        customTopicPrompt: String? = null
    ): WhatsAppChatEntity {
        val settings = settingsStore.settings.first()
        val base = ensureChatRecord(chatName, isGroup, "", settings)
        val updated = base.copy(
            autoReplyEnabled = autoReply ?: base.autoReplyEnabled,
            antiLinkEnabled = antiLink ?: base.antiLinkEnabled,
            antiStickerEnabled = antiSticker ?: base.antiStickerEnabled,
            antiForwardEnabled = antiForward ?: base.antiForwardEnabled,
            commandsEnabled = commandsEnabled ?: base.commandsEnabled,
            customTopicPrompt = customTopicPrompt ?: base.customTopicPrompt
        )
        repository.upsertWhatsAppChat(updated)
        return updated
    }

    fun observeMessagesForChat(chatKey: String): Flow<List<WhatsAppMessageEntity>> =
        repository.observeWhatsAppMessages(chatKey)

    private suspend fun dispatchReply(
        chatKey: String,
        chatName: String,
        replyText: String,
        preferredChannel: String
    ): Result<String> = dispatchMutex.withLock {
        val now = System.currentTimeMillis()
        val normChat = WaAccessGuard.normalizeChatNameForDedup(chatName)
        val normBody = WaAccessGuard.normalizeTextForDedup(replyText)
        recentBotOutgoingFingerprints["$normChat::$normBody"] = now
        recentBotOutgoingFingerprints["body::$normBody"] = now

        if (webBridge.linkStatus.value == WaLinkStatus.LINKED) {
            val webRes = webBridge.sendMessageToChat(chatName, replyText)
            if (webRes.isSuccess) return@withLock webRes
        }

        if (!WaAccessGuard.looksLikePhoneTarget(chatName) &&
            WhatsAppNotificationService.sendQuickReply(context, chatKey, chatName, replyText)
        ) {
            return@withLock Result.success("Sent via Background Notification Quick-Reply")
        }

        return@withLock if (preferredChannel == "NOTIFICATION") {
            Result.failure(IllegalStateException("Link WhatsApp Web OR enable Notification Access for quick-reply."))
        } else {
            Result.failure(IllegalStateException("WhatsApp Web not linked yet and no active quick-reply notification for '$chatName'."))
        }
    }

    private suspend fun ensureChatRecord(
        chatName: String,
        isGroup: Boolean,
        preview: String,
        settings: AppSettings
    ): WhatsAppChatEntity {
        val cleanName = WaChatKeyHelper.fallbackDisplayName(chatName)
        val chatKey = WaChatKeyHelper.normalize(cleanName, isGroup)
        val existing = repository.getWhatsAppChatByKey(chatKey)
        val record = if (existing == null) {
            val whitelistOnly = settings.waWhitelistOnlyMode
            WhatsAppChatEntity(
                chatKey = chatKey,
                displayName = cleanName,
                isGroup = isGroup,
                autoReplyEnabled = if (whitelistOnly) false else if (isGroup) settings.waDefaultAutoReplyGroup else settings.waDefaultAutoReplyDm,
                antiLinkEnabled = if (whitelistOnly) false else isGroup && settings.waGlobalAntiLink,
                antiStickerEnabled = if (whitelistOnly) false else isGroup && settings.waGlobalAntiSticker,
                antiForwardEnabled = if (whitelistOnly) false else isGroup && settings.waGlobalAntiForward,
                commandsEnabled = !settings.waOwnerOnlyCommands,
                lastMessagePreview = preview.take(100),
                lastMessageAt = System.currentTimeMillis()
            )
        } else {
            existing.copy(
                displayName = cleanName,
                lastMessagePreview = preview.ifBlank { existing.lastMessagePreview }.take(100),
                lastMessageAt = System.currentTimeMillis()
            )
        }
        repository.upsertWhatsAppChat(record)
        return record
    }

    private suspend fun recordOutgoingBotMessage(
        chat: WhatsAppChatEntity,
        text: String,
        type: String,
        latencyMs: Long
    ) {
        repository.insertWhatsAppMessage(
            WhatsAppMessageEntity(
                chatKey = chat.chatKey,
                chatName = chat.displayName,
                senderName = "AI Bot",
                isFromMe = true,
                isGroup = chat.isGroup,
                body = text,
                messageType = type,
                sourceChannel = if (webBridge.linkStatus.value == WaLinkStatus.LINKED) "WA_WEB" else "NOTIFICATION",
                latencyMs = latencyMs
            )
        )
        repository.upsertWhatsAppChat(
            chat.copy(
                lastMessagePreview = "Bot: ${text.take(90)}",
                lastMessageAt = System.currentTimeMillis(),
                unreadCount = 0
            )
        )
    }

    private fun appendEvent(kind: String, chatName: String, summary: String, latencyMs: Long) {
        val ev = WaBotEvent(kind = kind, chatName = chatName, summary = summary, latencyMs = latencyMs)
        _state.update { st ->
            st.copy(recentEvents = (listOf(ev) + st.recentEvents).take(40))
        }
    }

    private fun pruneOldFingerprints(now: Long) {
        if (recentInboundByChatAndBody.size > 200) {
            recentInboundByChatAndBody.entries.removeIf { now - it.value > 35_000L }
        }
        if (recentInboundByBody.size > 150) {
            recentInboundByBody.entries.removeIf { now - it.value > 15_000L }
        }
        if (recentBotOutgoingFingerprints.size > 150) {
            recentBotOutgoingFingerprints.entries.removeIf { now - it.value > 65_000L }
        }
    }
}
