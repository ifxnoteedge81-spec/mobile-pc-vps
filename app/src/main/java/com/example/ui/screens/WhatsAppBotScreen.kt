package com.example.ui.screens

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.storage.AppSettings
import com.example.storage.WhatsAppChatEntity
import com.example.storage.WhatsAppMessageEntity
import com.example.storage.WhatsAppScheduledMessageEntity
import com.example.ui.components.GlassOsCard
import com.example.ui.components.SectionHeader
import com.example.ui.components.StatusBadge
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CoralAlert
import com.example.ui.theme.EmeraldPulse
import com.example.ui.theme.NeuralCyan
import com.example.whatsapp.WaCommandParser
import com.example.whatsapp.WaLinkStatus
import com.example.whatsapp.WaRuntimeState
import com.example.whatsapp.WhatsAppWebBridge
import kotlinx.coroutines.flow.Flow
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WhatsAppBotScreen(
    settings: AppSettings,
    waState: WaRuntimeState,
    linkStatus: WaLinkStatus,
    pairingCode: String,
    activeModelLabel: String,
    hasAnyApiKey: Boolean,
    chats: List<WhatsAppChatEntity>,
    activityLogs: List<WhatsAppMessageEntity>,
    schedules: List<WhatsAppScheduledMessageEntity> = emptyList(),
    webBridge: WhatsAppWebBridge,
    observeChatMessages: (String) -> Flow<List<WhatsAppMessageEntity>>,
    onRefreshStatus: () -> Unit,
    onToggleMaster: (Boolean) -> Unit,
    onSaveGlobalRules: (Boolean, Boolean, Boolean, Boolean, Boolean, String, String?, String?, Boolean?, Boolean?) -> Unit,
    onRequestPhoneLinkCode: (String) -> Unit,
    onSyncChats: () -> Unit,
    onReloadWeb: (Boolean) -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onSendMessage: (String, Boolean, String, Boolean) -> Unit,
    onUpdateChatRules: (String, Boolean, Boolean?, Boolean?, Boolean?, Boolean?, Boolean?, String?) -> Unit,
    onDeleteChat: (String) -> Unit,
    onScheduleCampaign: (String, Boolean, String, Boolean, Int, Int, Int, Int, Int, Int, String) -> Unit = { _, _, _, _, _, _, _, _, _, _, _ -> },
    onRunScheduleNow: (Long) -> Unit = {},
    onCancelSchedule: (Long) -> Unit = {},
    onDeleteSchedule: (Long) -> Unit = {},
    onOpenModelSettings: () -> Unit,
    waNotificationBound: Boolean = waState.notificationAccessGranted,
    batteryUnrestricted: Boolean = false,
    onOpenAppInfoSettings: () -> Unit = {},
    onOpenBatterySettings: () -> Unit = {}
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    LaunchedEffect(Unit) { onRefreshStatus() }

    var showLinkPortal by rememberSaveable { mutableStateOf(linkStatus != WaLinkStatus.LINKED) }
    var phoneInput by rememberSaveable { mutableStateOf("") }
    var targetChatInput by rememberSaveable { mutableStateOf("") }
    var targetIsGroup by rememberSaveable { mutableStateOf(false) }
    var messageInput by rememberSaveable { mutableStateOf("") }

    // Owner Lock & Security state
    var ownerPhoneDraft by rememberSaveable(settings.waOwnerPhone) {
        mutableStateOf(settings.waOwnerPhone.ifBlank {
            waState.detectedOwnerPhone.takeIf { it.isNotBlank() }?.let { "+$it" }.orEmpty()
        })
    }
    var ownerNameDraft by rememberSaveable(settings.waOwnerName) { mutableStateOf(settings.waOwnerName) }

    // Multi-Day WhatsApp Scheduler state
    val calInit = remember { Calendar.getInstance().apply { add(Calendar.MINUTE, 10) } }
    var schedYear by rememberSaveable { mutableStateOf(calInit.get(Calendar.YEAR)) }
    var schedMonth by rememberSaveable { mutableStateOf(calInit.get(Calendar.MONTH) + 1) }
    var schedDay by rememberSaveable { mutableStateOf(calInit.get(Calendar.DAY_OF_MONTH)) }
    var schedHour by rememberSaveable { mutableStateOf(calInit.get(Calendar.HOUR_OF_DAY)) }
    var schedMinute by rememberSaveable { mutableStateOf(calInit.get(Calendar.MINUTE)) }
    var schedDaysCountText by rememberSaveable { mutableStateOf("1") }
    var schedTargetInput by rememberSaveable { mutableStateOf("") }
    var schedIsGroup by rememberSaveable { mutableStateOf(false) }
    var schedTemplateInput by rememberSaveable { mutableStateOf("") }
    var schedDailyListInput by rememberSaveable { mutableStateOf("") }
    var schedUseDailyList by rememberSaveable { mutableStateOf(false) }
    var schedUseAiCompose by rememberSaveable { mutableStateOf(false) }
    var showSchedulerCard by rememberSaveable { mutableStateOf(false) }

    var chatFilter by rememberSaveable { mutableStateOf("ALL") } // ALL, GROUPS, DM
    var chatSearch by rememberSaveable { mutableStateOf("") }

    var personaDraft by rememberSaveable(settings.waBotPersona) { mutableStateOf(settings.waBotPersona) }
    var showGlobalSettingsCard by rememberSaveable { mutableStateOf(false) }
    var showAddChatDialog by remember { mutableStateOf(false) }
    var historyDialogChat by remember { mutableStateOf<WhatsAppChatEntity?>(null) }
    var topicDialogChat by remember { mutableStateOf<WhatsAppChatEntity?>(null) }

    val filteredChats = remember(chats, chatFilter, chatSearch) {
        val q = chatSearch.trim().lowercase()
        chats.filter { c ->
            val typeMatch = when (chatFilter) {
                "GROUPS" -> c.isGroup
                "DM" -> !c.isGroup
                else -> true
            }
            val queryMatch = q.isEmpty() || c.displayName.lowercase().contains(q)
            typeMatch && queryMatch
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("whatsapp_bot_screen"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Master Control & Zero-Latency Brain Header
        item {
            GlassOsCard(
                borderColor = if (settings.waBotEnabled) EmeraldPulse.copy(alpha = 0.5f)
                else MaterialTheme.colorScheme.outlineVariant
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.SmartToy,
                            contentDescription = "WhatsApp AI Bot",
                            tint = if (settings.waBotEnabled) EmeraldPulse else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "WhatsApp AI Management Bot",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (settings.waBotEnabled) "Active • Sub-second AI & Moderation"
                                else "Paused • Switch ON to auto-manage",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Switch(
                        checked = settings.waBotEnabled,
                        onCheckedChange = onToggleMaster,
                        modifier = Modifier.testTag("wa_master_switch")
                    )
                }

                Spacer(Modifier.height(10.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val linkColor = when (linkStatus) {
                        WaLinkStatus.LINKED -> EmeraldPulse
                        WaLinkStatus.WAITING_PAIRING, WaLinkStatus.LOADING -> AmberWarning
                        else -> CoralAlert
                    }
                    StatusBadge(label = linkStatus.label, color = linkColor)
                    StatusBadge(
                        label = when {
                            waNotificationBound -> "BG Listener: Active"
                            waState.notificationAccessGranted -> "BG Listener: Rebinding"
                            else -> "BG Listener: Off"
                        },
                        color = when {
                            waNotificationBound -> EmeraldPulse
                            waState.notificationAccessGranted -> AmberWarning
                            else -> CoralAlert
                        }
                    )
                    StatusBadge(
                        label = if (batteryUnrestricted) "Battery: Unrestricted" else "Battery: Optimize Risk",
                        color = if (batteryUnrestricted) EmeraldPulse else AmberWarning
                    )
                    StatusBadge(
                        label = if (hasAnyApiKey) activeModelLabel.take(30) else "Add API Key",
                        color = if (hasAnyApiKey) NeuralCyan else CoralAlert
                    )
                }

                if (!hasAnyApiKey) {
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(onClick = onOpenModelSettings, modifier = Modifier.fillMaxWidth()) {
                        Text("AI Brain চালাতে Gemini বা OpenRouter API Key সেট করুন")
                    }
                }

                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    MetricPill("Replies", "${waState.repliesSentCount}")
                    MetricPill("Blocked", "${waState.blockedUnauthorizedCount}")
                    MetricPill("Scheduled", "${waState.scheduledSentCount}")
                    MetricPill("Speed", if (waState.lastLatencyMs > 0) "${waState.lastLatencyMs}ms" else "<1s")
                }
            }
        }

        // 2. WhatsApp Link Code / QR Web Portal (No separate WhatsApp opening required)
        item {
            GlassOsCard(borderColor = NeuralCyan.copy(alpha = 0.4f)) {
                SectionHeader(
                    title = "WhatsApp Link & Pairing Portal",
                    subtitle = "হোয়াটসঅ্যাপে আলাদা করে না ঢুকেই এখানে QR বা ৮-সংখ্যার Link Code দিয়ে কানেক্ট করুন",
                    icon = Icons.Filled.QrCodeScanner
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = waState.statusDetail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (pairingCode.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    GlassOsCard(borderColor = EmeraldPulse.copy(alpha = 0.6f)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "WhatsApp Link Code",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = EmeraldPulse
                                )
                                Text(
                                    text = pairingCode,
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            IconButton(onClick = { clipboard.setText(AnnotatedString(pairingCode)) }) {
                                Icon(Icons.Filled.ContentCopy, contentDescription = "Copy Link Code")
                            }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = phoneInput,
                        onValueChange = { phoneInput = it },
                        label = { Text("Phone (+88017...)") },
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("wa_phone_input")
                    )
                    Button(
                        onClick = {
                            showLinkPortal = true
                            onRequestPhoneLinkCode(phoneInput)
                        },
                        modifier = Modifier.testTag("wa_get_link_code_button")
                    ) {
                        Icon(Icons.Filled.Link, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Link Code")
                    }
                }

                Spacer(Modifier.height(10.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilledTonalButton(
                        onClick = { showLinkPortal = !showLinkPortal },
                        modifier = Modifier.testTag("wa_toggle_portal_button")
                    ) {
                        Icon(Icons.Filled.PhoneAndroid, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (showLinkPortal) "Hide Web Portal" else "Open Link Portal")
                    }
                    OutlinedButton(onClick = onSyncChats) {
                        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Sync Groups")
                    }
                    OutlinedButton(onClick = { onReloadWeb(false) }) {
                        Text("Reload")
                    }
                    OutlinedButton(onClick = { onReloadWeb(true) }) {
                        Text("New QR")
                    }
                    if (!waNotificationBound) {
                        Button(
                            onClick = onOpenNotificationSettings,
                            colors = ButtonDefaults.buttonColors(containerColor = EmeraldPulse)
                        ) {
                            Icon(Icons.Filled.NotificationsActive, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(if (waState.notificationAccessGranted) "Re-toggle BG Listener" else "Enable BG Auto-Reply")
                        }
                        OutlinedButton(onClick = onOpenAppInfoSettings) {
                            Text("Restricted Settings (⋮)")
                        }
                    }
                    if (!batteryUnrestricted) {
                        OutlinedButton(onClick = onOpenBatterySettings) {
                            Text("Allow 24/7 Battery")
                        }
                    }
                }

                AnimatedVisibility(visible = showLinkPortal) {
                    Column {
                        Spacer(Modifier.height(10.dp))
                        PersistentWhatsAppPortal(
                            webBridge = webBridge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(390.dp)
                        )
                    }
                }
            }
        }

        // 3. Direct & AI Message Sender (Contacts & Groups)
        item {
            GlassOsCard {
                SectionHeader(
                    title = "Send / AI Compose to Contact or Group",
                    subtitle = "আগের কনভারসেশন বুঝে AI দিয়ে বা সরাসরি যেকোনো চ্যাটে/গ্রুপে মেসেজ পাঠান",
                    icon = Icons.AutoMirrored.Filled.Send
                )
                Spacer(Modifier.height(8.dp))

                if (chats.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        chats.take(8).forEach { c ->
                            FilterChip(
                                selected = targetChatInput.equals(c.displayName, ignoreCase = true),
                                onClick = {
                                    targetChatInput = c.displayName
                                    targetIsGroup = c.isGroup
                                },
                                label = { Text(c.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                leadingIcon = {
                                    Icon(
                                        if (c.isGroup) Icons.Filled.Group else Icons.Filled.Person,
                                        contentDescription = null,
                                        modifier = Modifier.size(15.dp)
                                    )
                                }
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = targetChatInput,
                        onValueChange = { targetChatInput = it },
                        label = { Text("Contact, Group, or +8801...") },
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("wa_target_chat_input")
                    )
                    FilterChip(
                        selected = targetIsGroup,
                        onClick = { targetIsGroup = !targetIsGroup },
                        label = { Text(if (targetIsGroup) "Group" else "DM") }
                    )
                }

                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = messageInput,
                    onValueChange = { messageInput = it },
                    label = { Text("Message text or AI topic/instruction...") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("wa_message_input")
                )

                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            onSendMessage(targetChatInput, targetIsGroup, messageInput, false)
                            messageInput = ""
                        },
                        enabled = targetChatInput.isNotBlank() && messageInput.isNotBlank(),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("wa_send_direct_button")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Send Direct")
                    }
                    Button(
                        onClick = {
                            onSendMessage(targetChatInput, targetIsGroup, messageInput, true)
                            messageInput = ""
                        },
                        enabled = targetChatInput.isNotBlank() && messageInput.isNotBlank(),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("wa_send_ai_button")
                    ) {
                        Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("AI Send")
                    }
                }
            }
        }

        // 3b. Scheduled WhatsApp Messages & Multi-Day Campaign (1-30 Days)
        item {
            val pendingCount = schedules.count { it.status == "PENDING" }
            GlassOsCard(borderColor = if (pendingCount > 0) EmeraldPulse.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outlineVariant) {
                SectionHeader(
                    title = "Scheduled & Multi-Day Auto Sender ($pendingCount)",
                    subtitle = "তারিখ ও সময় অনুযায়ী ১ দিন বা একটানা ১০–৩০ দিনের আলাদা কাজ/মেসেজ শিডিউল করুন",
                    icon = Icons.Filled.Schedule,
                    trailing = {
                        TextButton(
                            onClick = { showSchedulerCard = !showSchedulerCard },
                            modifier = Modifier.testTag("wa_toggle_scheduler_button")
                        ) {
                            Text(if (showSchedulerCard) "Hide" else "Schedule")
                        }
                    }
                )

                AnimatedVisibility(visible = showSchedulerCard || schedules.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (showSchedulerCard) {
                            Spacer(Modifier.height(4.dp))
                            if (chats.isNotEmpty()) {
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    chats.take(6).forEach { c ->
                                        FilterChip(
                                            selected = schedTargetInput.equals(c.displayName, ignoreCase = true),
                                            onClick = {
                                                schedTargetInput = c.displayName
                                                schedIsGroup = c.isGroup
                                            },
                                            label = { Text(c.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                        )
                                    }
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = schedTargetInput,
                                    onValueChange = { schedTargetInput = it },
                                    label = { Text("Target Contact, Group, or +8801...") },
                                    singleLine = true,
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("wa_sched_target_input")
                                )
                                FilterChip(
                                    selected = schedIsGroup,
                                    onClick = { schedIsGroup = !schedIsGroup },
                                    label = { Text(if (schedIsGroup) "Group" else "DM") }
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        runCatching {
                                            DatePickerDialog(
                                                context,
                                                { _, y, m0, d ->
                                                    schedYear = y
                                                    schedMonth = m0 + 1
                                                    schedDay = d
                                                },
                                                schedYear,
                                                (schedMonth - 1).coerceIn(0, 11),
                                                schedDay
                                            ).show()
                                        }
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("wa_sched_date_button")
                                ) {
                                    Icon(Icons.Filled.CalendarMonth, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("$schedYear-${"%02d".format(schedMonth)}-${"%02d".format(schedDay)}")
                                }
                                OutlinedButton(
                                    onClick = {
                                        runCatching {
                                            TimePickerDialog(
                                                context,
                                                { _, h, min ->
                                                    schedHour = h
                                                    schedMinute = min
                                                },
                                                schedHour,
                                                schedMinute,
                                                true
                                            ).show()
                                        }
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("wa_sched_time_button")
                                ) {
                                    Icon(Icons.Filled.AccessTime, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("${"%02d".format(schedHour)}:${"%02d".format(schedMinute)}")
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                listOf("1" to "1 Day", "7" to "7 Days", "10" to "10 Days").forEach { (dVal, label) ->
                                    FilterChip(
                                        selected = schedDaysCountText == dVal,
                                        onClick = { schedDaysCountText = dVal },
                                        label = { Text(label) }
                                    )
                                }
                                OutlinedTextField(
                                    value = schedDaysCountText,
                                    onValueChange = { v -> schedDaysCountText = v.filter { it.isDigit() }.take(2) },
                                    label = { Text("Days") },
                                    singleLine = true,
                                    modifier = Modifier
                                        .width(84.dp)
                                        .testTag("wa_sched_days_input")
                                )
                            }

                            SettingSwitchRow("প্রতিদিন আলাদা আলাদা মেসেজ/টাস্ক (লাইন বাই লাইন)", schedUseDailyList) {
                                schedUseDailyList = it
                            }

                            if (schedUseDailyList) {
                                OutlinedTextField(
                                    value = schedDailyListInput,
                                    onValueChange = { schedDailyListInput = it },
                                    label = { Text("Day 1 (Line 1), Day 2 (Line 2) ... Day 10") },
                                    minLines = 3,
                                    maxLines = 6,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("wa_sched_daily_list_input")
                                )
                            } else {
                                OutlinedTextField(
                                    value = schedTemplateInput,
                                    onValueChange = { schedTemplateInput = it },
                                    label = { Text("Message or AI Prompt (Day {day}/{total})") },
                                    minLines = 2,
                                    maxLines = 4,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("wa_sched_template_input")
                                )
                            }

                            SettingSwitchRow("AI দিয়ে প্রতিদিন ন্যাচারাল মেসেজ লিখবে", schedUseAiCompose) {
                                schedUseAiCompose = it
                            }

                            val parsedDays = (schedDaysCountText.toIntOrNull() ?: 1).coerceIn(1, 30)
                            Button(
                                onClick = {
                                    onScheduleCampaign(
                                        schedTargetInput,
                                        schedIsGroup,
                                        schedTemplateInput,
                                        schedUseAiCompose,
                                        schedYear,
                                        schedMonth,
                                        schedDay,
                                        schedHour,
                                        schedMinute,
                                        parsedDays,
                                        if (schedUseDailyList) schedDailyListInput else ""
                                    )
                                    schedTemplateInput = ""
                                    schedDailyListInput = ""
                                },
                                enabled = schedTargetInput.isNotBlank() && (schedTemplateInput.isNotBlank() || schedDailyListInput.isNotBlank()),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("wa_sched_submit_button")
                            ) {
                                Icon(Icons.Filled.Schedule, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Save ${parsedDays}-Day Schedule")
                            }
                        }

                        if (schedules.isNotEmpty()) {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                            val dateFmt = remember { SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()) }
                            schedules.take(15).forEach { item ->
                                GlassOsCard {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "${item.targetDisplay} • Day ${item.dayIndex}/${item.totalDays}",
                                                style = MaterialTheme.typography.labelLarge,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = "${dateFmt.format(Date(item.scheduledAtMillis))} • ${item.messageTemplate.take(70)}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            if (item.lastResult.isNotBlank()) {
                                                Text(
                                                    text = item.lastResult,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                        StatusBadge(
                                            label = item.status,
                                            color = when (item.status) {
                                                "SENT" -> EmeraldPulse
                                                "FAILED" -> CoralAlert
                                                "CANCELLED" -> MaterialTheme.colorScheme.onSurfaceVariant
                                                else -> NeuralCyan
                                            }
                                        )
                                    }
                                    Spacer(Modifier.height(6.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.End,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        if (item.status == "PENDING") {
                                            TextButton(onClick = { onRunScheduleNow(item.id) }) {
                                                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(15.dp))
                                                Spacer(Modifier.width(2.dp))
                                                Text("Send Now")
                                            }
                                            TextButton(onClick = { onCancelSchedule(item.id) }) {
                                                Text("Cancel")
                                            }
                                        }
                                        IconButton(
                                            onClick = { onDeleteSchedule(item.id) },
                                            modifier = Modifier.size(34.dp)
                                        ) {
                                            Icon(Icons.Filled.Delete, contentDescription = "Delete schedule", tint = CoralAlert)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 4. Owner Security Shield, Global Defaults & AI Bot Persona
        item {
            GlassOsCard(borderColor = if (settings.waOwnerOnlyCommands) NeuralCyan.copy(alpha = 0.45f) else AmberWarning.copy(alpha = 0.5f)) {
                SectionHeader(
                    title = "Owner Lock, Whitelist & AI Persona",
                    subtitle = "অপরিচিত নাম্বার ব্লক, শুধু Owner-এর .ai/.cmd অনুমোদন ও মানবিক রিপ্লাই স্টাইল",
                    icon = Icons.Filled.Lock,
                    trailing = {
                        TextButton(onClick = { showGlobalSettingsCard = !showGlobalSettingsCard }) {
                            Text(if (showGlobalSettingsCard) "Hide" else "Configure")
                        }
                    }
                )

                Spacer(Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    StatusBadge(
                        label = if (settings.waWhitelistOnlyMode) "Whitelist-Only: ON" else "Open Auto-Reply",
                        color = if (settings.waWhitelistOnlyMode) EmeraldPulse else AmberWarning
                    )
                    StatusBadge(
                        label = if (settings.waOwnerOnlyCommands) "Owner Cmd Lock: ON" else "Shared Cmds",
                        color = if (settings.waOwnerOnlyCommands) EmeraldPulse else AmberWarning
                    )
                    val activeOwner = settings.waOwnerPhone.ifBlank {
                        waState.detectedOwnerPhone.takeIf { it.isNotBlank() }?.let { "+$it" }.orEmpty()
                    }
                    if (activeOwner.isNotBlank()) {
                        StatusBadge(label = "Owner: $activeOwner", color = NeuralCyan)
                    }
                }

                AnimatedVisibility(visible = showGlobalSettingsCard) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Spacer(Modifier.height(6.dp))
                        OutlinedTextField(
                            value = ownerPhoneDraft,
                            onValueChange = { ownerPhoneDraft = it },
                            label = { Text("Owner WhatsApp Phone (+8801...)") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("wa_owner_phone_input")
                        )
                        OutlinedTextField(
                            value = ownerNameDraft,
                            onValueChange = { ownerNameDraft = it },
                            label = { Text("Owner Display Name (Optional)") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("wa_owner_name_input")
                        )
                        SettingSwitchRow(
                            title = "Strict Whitelist-Only (সিলেক্ট ছাড়া অন্য কোনো নাম্বারে অটো-রিপ্লাই যাবে না)",
                            checked = settings.waWhitelistOnlyMode,
                            onCheckedChange = {
                                onSaveGlobalRules(
                                    settings.waDefaultAutoReplyDm,
                                    settings.waDefaultAutoReplyGroup,
                                    settings.waGlobalAntiLink,
                                    settings.waGlobalAntiSticker,
                                    settings.waGlobalAntiForward,
                                    personaDraft,
                                    ownerPhoneDraft,
                                    ownerNameDraft,
                                    it,
                                    settings.waOwnerOnlyCommands
                                )
                            }
                        )
                        SettingSwitchRow(
                            title = "Owner-Only Bot & AI Commands (.ai / .cmd শুধু আপনার নাম্বার থেকেই চলবে)",
                            checked = settings.waOwnerOnlyCommands,
                            onCheckedChange = {
                                onSaveGlobalRules(
                                    settings.waDefaultAutoReplyDm,
                                    settings.waDefaultAutoReplyGroup,
                                    settings.waGlobalAntiLink,
                                    settings.waGlobalAntiSticker,
                                    settings.waGlobalAntiForward,
                                    personaDraft,
                                    ownerPhoneDraft,
                                    ownerNameDraft,
                                    settings.waWhitelistOnlyMode,
                                    it
                                )
                            }
                        )
                        HorizontalDivider()
                        SettingSwitchRow(
                            title = "Auto-Reply Private Chats (DMs) by Default",
                            checked = settings.waDefaultAutoReplyDm,
                            onCheckedChange = {
                                onSaveGlobalRules(
                                    it,
                                    settings.waDefaultAutoReplyGroup,
                                    settings.waGlobalAntiLink,
                                    settings.waGlobalAntiSticker,
                                    settings.waGlobalAntiForward,
                                    personaDraft,
                                    ownerPhoneDraft,
                                    ownerNameDraft,
                                    settings.waWhitelistOnlyMode,
                                    settings.waOwnerOnlyCommands
                                )
                            }
                        )
                        SettingSwitchRow(
                            title = "Auto-Reply All Groups by Default",
                            checked = settings.waDefaultAutoReplyGroup,
                            onCheckedChange = {
                                onSaveGlobalRules(
                                    settings.waDefaultAutoReplyDm,
                                    it,
                                    settings.waGlobalAntiLink,
                                    settings.waGlobalAntiSticker,
                                    settings.waGlobalAntiForward,
                                    personaDraft,
                                    ownerPhoneDraft,
                                    ownerNameDraft,
                                    settings.waWhitelistOnlyMode,
                                    settings.waOwnerOnlyCommands
                                )
                            }
                        )
                        SettingSwitchRow(
                            title = "Global Group Anti-Link (Auto-Delete URLs)",
                            checked = settings.waGlobalAntiLink,
                            onCheckedChange = {
                                onSaveGlobalRules(
                                    settings.waDefaultAutoReplyDm,
                                    settings.waDefaultAutoReplyGroup,
                                    it,
                                    settings.waGlobalAntiSticker,
                                    settings.waGlobalAntiForward,
                                    personaDraft,
                                    ownerPhoneDraft,
                                    ownerNameDraft,
                                    settings.waWhitelistOnlyMode,
                                    settings.waOwnerOnlyCommands
                                )
                            }
                        )
                        SettingSwitchRow(
                            title = "Global Group Anti-Sticker (Auto-Delete Stickers)",
                            checked = settings.waGlobalAntiSticker,
                            onCheckedChange = {
                                onSaveGlobalRules(
                                    settings.waDefaultAutoReplyDm,
                                    settings.waDefaultAutoReplyGroup,
                                    settings.waGlobalAntiLink,
                                    it,
                                    settings.waGlobalAntiForward,
                                    personaDraft,
                                    ownerPhoneDraft,
                                    ownerNameDraft,
                                    settings.waWhitelistOnlyMode,
                                    settings.waOwnerOnlyCommands
                                )
                            }
                        )
                        SettingSwitchRow(
                            title = "Global Anti-Forward Spam",
                            checked = settings.waGlobalAntiForward,
                            onCheckedChange = {
                                onSaveGlobalRules(
                                    settings.waDefaultAutoReplyDm,
                                    settings.waDefaultAutoReplyGroup,
                                    settings.waGlobalAntiLink,
                                    settings.waGlobalAntiSticker,
                                    it,
                                    personaDraft,
                                    ownerPhoneDraft,
                                    ownerNameDraft,
                                    settings.waWhitelistOnlyMode,
                                    settings.waOwnerOnlyCommands
                                )
                            }
                        )
                        OutlinedTextField(
                            value = personaDraft,
                            onValueChange = { personaDraft = it },
                            label = { Text("WhatsApp AI Brain Instructions / Persona") },
                            minLines = 3,
                            maxLines = 5,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(
                            onClick = {
                                onSaveGlobalRules(
                                    settings.waDefaultAutoReplyDm,
                                    settings.waDefaultAutoReplyGroup,
                                    settings.waGlobalAntiLink,
                                    settings.waGlobalAntiSticker,
                                    settings.waGlobalAntiForward,
                                    personaDraft,
                                    ownerPhoneDraft,
                                    ownerNameDraft,
                                    settings.waWhitelistOnlyMode,
                                    settings.waOwnerOnlyCommands
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("wa_save_global_rules_button")
                        ) {
                            Text("Save Owner Lock & Bot Persona")
                        }
                    }
                }
            }
        }

        // 5. Selective Per-Group & Per-Contact Manager
        item {
            GlassOsCard {
                SectionHeader(
                    title = "Groups & Contacts (${chats.size})",
                    subtitle = "আলাদাভাবে গ্রুপ বা কন্ট্যাক্ট সিলেক্ট করে Auto-Reply, Anti-Link ও Anti-Sticker সেট করুন",
                    icon = Icons.Filled.Group,
                    trailing = {
                        FilledTonalButton(
                            onClick = { showAddChatDialog = true },
                            modifier = Modifier.testTag("wa_add_chat_button")
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Add")
                        }
                    }
                )

                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("ALL" to "All", "GROUPS" to "Groups", "DM" to "Contacts").forEach { (key, label) ->
                        FilterChip(
                            selected = chatFilter == key,
                            onClick = { chatFilter = key },
                            label = { Text(label) }
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = chatSearch,
                    onValueChange = { chatSearch = it },
                    label = { Text("Search groups or contacts...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        if (filteredChats.isEmpty()) {
            item {
                GlassOsCard {
                    Text(
                        text = "কোনো গ্রুপ বা চ্যাট এখনো লিস্টে আসেনি। উপরে WhatsApp Link Portal দিয়ে QR/Code স্ক্যান করে Sync Groups চাপুন অথবা '+ Add' বাটনে গ্রুপের নাম যোগ করুন।",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            items(filteredChats, key = { it.chatKey }) { chat ->
                WhatsAppChatRuleCard(
                    chat = chat,
                    onToggleAutoReply = {
                        onUpdateChatRules(chat.displayName, chat.isGroup, it, null, null, null, null, null)
                    },
                    onToggleAntiLink = {
                        onUpdateChatRules(chat.displayName, chat.isGroup, null, it, null, null, null, null)
                    },
                    onToggleAntiSticker = {
                        onUpdateChatRules(chat.displayName, chat.isGroup, null, null, it, null, null, null)
                    },
                    onToggleAntiForward = {
                        onUpdateChatRules(chat.displayName, chat.isGroup, null, null, null, it, null, null)
                    },
                    onToggleCommands = {
                        onUpdateChatRules(chat.displayName, chat.isGroup, null, null, null, null, it, null)
                    },
                    onOpenTopic = { topicDialogChat = chat },
                    onOpenHistory = { historyDialogChat = chat },
                    onDelete = { onDeleteChat(chat.chatKey) }
                )
            }
        }

        // 6. In-Chat WhatsApp Commands Reference & Live Bot Feed
        item {
            GlassOsCard {
                SectionHeader(
                    title = "In-Chat Bot Commands (হোয়াটসঅ্যাপের ভেতর থেকে)",
                    subtitle = "যেকোনো গ্রুপ বা চ্যাটে নিচের কমান্ড লিখে সরাসরি বট কন্ট্রোল করুন",
                    icon = Icons.Filled.Terminal
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = WaCommandParser.helpText(),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { clipboard.setText(AnnotatedString(WaCommandParser.helpText())) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Copy Command Menu")
                }
            }
        }

        if (waState.recentEvents.isNotEmpty() || activityLogs.isNotEmpty()) {
            item {
                SectionHeader(
                    title = "Live Bot Activity & Moderation Log",
                    subtitle = "Zero-latency reflexes, deleted links/stickers & AI replies",
                    icon = Icons.Filled.History
                )
            }
            items(waState.recentEvents.take(12), key = { it.id }) { ev ->
                GlassOsCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${ev.kind} • ${ev.chatName}",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        StatusBadge(
                            label = "${ev.latencyMs}ms",
                            color = when (ev.kind) {
                                "MODERATION" -> AmberWarning
                                "ERROR" -> CoralAlert
                                else -> EmeraldPulse
                            }
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = ev.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    if (showAddChatDialog) {
        AddWhatsAppChatDialog(
            onDismiss = { showAddChatDialog = false },
            onAdd = { name, isGroup, autoReply, antiLink, antiSticker ->
                onUpdateChatRules(name, isGroup, autoReply, antiLink, antiSticker, false, true, "")
                showAddChatDialog = false
            }
        )
    }

    topicDialogChat?.let { chat ->
        var topicText by remember(chat.chatKey) { mutableStateOf(chat.customTopicPrompt) }
        AlertDialog(
            onDismissRequest = { topicDialogChat = null },
            title = { Text("Topic Rule: ${chat.displayName}") },
            text = {
                OutlinedTextField(
                    value = topicText,
                    onValueChange = { topicText = it },
                    label = { Text("Custom AI topic/tone for this chat") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    onUpdateChatRules(chat.displayName, chat.isGroup, null, null, null, null, null, topicText)
                    topicDialogChat = null
                }) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { topicDialogChat = null }) { Text("Cancel") }
            }
        )
    }

    historyDialogChat?.let { chat ->
        val chatMessages by observeChatMessages(chat.chatKey).collectAsStateWithLifecycle(initialValue = emptyList())
        AlertDialog(
            onDismissRequest = { historyDialogChat = null },
            title = { Text("${chat.displayName} • Context (${chatMessages.size})") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                ) {
                    if (chatMessages.isEmpty()) {
                        Text(
                            "No conversation history recorded yet.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(chatMessages, key = { it.id }) { m ->
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        text = "${if (m.isFromMe) "🤖 Bot/Me" else "👤 ${m.senderName}"} [${m.messageType}]",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (m.isFromMe) EmeraldPulse else NeuralCyan
                                    )
                                    Text(m.body, style = MaterialTheme.typography.bodySmall)
                                    HorizontalDivider(modifier = Modifier.padding(top = 4.dp))
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { historyDialogChat = null }) { Text("Close") }
            }
        )
    }
}

@Composable
private fun PersistentWhatsAppPortal(
    webBridge: WhatsAppWebBridge,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val container = remember { FrameLayout(context) }

    DisposableEffect(container, webBridge) {
        webBridge.attachToContainer(container)
        onDispose {
            webBridge.detachFromContainer(container)
        }
    }

    AndroidView(
        factory = { container },
        modifier = modifier
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WhatsAppChatRuleCard(
    chat: WhatsAppChatEntity,
    onToggleAutoReply: (Boolean) -> Unit,
    onToggleAntiLink: (Boolean) -> Unit,
    onToggleAntiSticker: (Boolean) -> Unit,
    onToggleAntiForward: (Boolean) -> Unit,
    onToggleCommands: (Boolean) -> Unit,
    onOpenTopic: () -> Unit,
    onOpenHistory: () -> Unit,
    onDelete: () -> Unit
) {
    GlassOsCard(
        borderColor = if (chat.autoReplyEnabled || chat.antiLinkEnabled || chat.antiStickerEnabled)
            EmeraldPulse.copy(alpha = 0.45f)
        else MaterialTheme.colorScheme.outlineVariant
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = if (chat.isGroup) Icons.Filled.Group else Icons.Filled.Chat,
                    contentDescription = null,
                    tint = if (chat.isGroup) NeuralCyan else EmeraldPulse,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(
                        text = chat.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${if (chat.isGroup) "Group" else "Contact"} • ${chat.lastMessagePreview.ifBlank { "Ready" }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            IconButton(onClick = onOpenHistory) {
                Icon(Icons.Filled.History, contentDescription = "Conversation History")
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Remove from list")
            }
        }

        Spacer(Modifier.height(8.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            FilterChip(
                selected = chat.autoReplyEnabled,
                onClick = { onToggleAutoReply(!chat.autoReplyEnabled) },
                label = { Text("AI Auto-Reply") }
            )
            FilterChip(
                selected = chat.antiLinkEnabled,
                onClick = { onToggleAntiLink(!chat.antiLinkEnabled) },
                label = { Text("Anti-Link") }
            )
            FilterChip(
                selected = chat.antiStickerEnabled,
                onClick = { onToggleAntiSticker(!chat.antiStickerEnabled) },
                label = { Text("Anti-Sticker") }
            )
            FilterChip(
                selected = chat.antiForwardEnabled,
                onClick = { onToggleAntiForward(!chat.antiForwardEnabled) },
                label = { Text("Anti-Forward") }
            )
            FilterChip(
                selected = chat.commandsEnabled,
                onClick = { onToggleCommands(!chat.commandsEnabled) },
                label = { Text("Cmds") }
            )
            OutlinedButton(onClick = onOpenTopic, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)) {
                Text(if (chat.customTopicPrompt.isBlank()) "+ Topic Rule" else "Edit Topic")
            }
        }
    }
}

@Composable
private fun AddWhatsAppChatDialog(
    onDismiss: () -> Unit,
    onAdd: (String, Boolean, Boolean, Boolean, Boolean) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var isGroup by remember { mutableStateOf(true) }
    var autoReply by remember { mutableStateOf(true) }
    var antiLink by remember { mutableStateOf(true) }
    var antiSticker by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select / Add WhatsApp Group or Chat") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Exact WhatsApp Group or Contact Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                SettingSwitchRow("This is a WhatsApp Group", isGroup) { isGroup = it }
                SettingSwitchRow("Enable AI Auto-Reply", autoReply) { autoReply = it }
                SettingSwitchRow("Enable Anti-Link Auto-Delete", antiLink) { antiLink = it }
                SettingSwitchRow("Enable Anti-Sticker Auto-Delete", antiSticker) { antiSticker = it }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isNotBlank()) onAdd(name.trim(), isGroup, autoReply, antiLink, antiSticker)
                },
                enabled = name.isNotBlank()
            ) {
                Text("Save Rule")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun SettingSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun MetricPill(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = NeuralCyan)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
