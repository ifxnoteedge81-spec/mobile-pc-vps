package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Launch
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.accessibility.InstalledAppSummary
import com.example.storage.AgentMemoryEntity
import com.example.storage.AgentTaskEntity
import com.example.storage.AutomationRuleEntity
import com.example.storage.ExecutionLogEntity
import com.example.storage.MemoryCategory
import com.example.storage.TaskStatus
import com.example.ui.components.GlassOsCard
import com.example.ui.components.SectionHeader
import com.example.ui.components.StatusBadge
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CoralAlert
import com.example.ui.theme.EmeraldPulse
import com.example.ui.theme.NeuralCyan
import kotlinx.coroutines.flow.Flow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun AppManagerScreen(
    apps: List<InstalledAppSummary>,
    onLoadApps: () -> Unit,
    onLaunchApp: (String) -> Unit,
    onOpenAppSettings: (String) -> Unit
) {
    var search by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { onLoadApps() }

    val filtered = remember(apps, search) {
        if (search.isBlank()) apps
        else apps.filter { it.appName.contains(search, true) || it.packageName.contains(search, true) }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("app_manager_screen"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            SectionHeader(
                title = "Installed Applications (${apps.size})",
                subtitle = "Queried via compliant Android <queries> launcher intents",
                icon = Icons.Filled.Apps
            )
            Spacer(Modifier.height(8.dp))
            GlassOsCard {
                Text(
                    "Note: Installing APKs requires explicit user approval via Android's system PackageInstaller. Google Play Store & Play Services are not bundled inside the local Linux workspace.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                placeholder = { Text("Filter launchable apps...") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }

        items(filtered, key = { it.packageName }) { app ->
            GlassOsCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(app.appName, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${app.packageName} • v${app.versionName}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Row {
                        IconButton(onClick = { onOpenAppSettings(app.packageName) }) {
                            Icon(Icons.Filled.Settings, contentDescription = "App info")
                        }
                        IconButton(onClick = { onLaunchApp(app.packageName) }) {
                            Icon(Icons.Filled.Launch, contentDescription = "Launch app", tint = NeuralCyan)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AutomationManagerScreen(
    rules: List<AutomationRuleEntity>,
    onCreateRule: (title: String, prompt: String, type: String, hour: Int, minute: Int, cond: String) -> Unit,
    onToggleRule: (AutomationRuleEntity) -> Unit,
    onRunNow: (AutomationRuleEntity) -> Unit,
    onDeleteRule: (Long) -> Unit
) {
    var showCreate by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    var scheduleType by remember { mutableStateOf("DAILY") }
    var hourInput by remember { mutableStateOf("9") }
    var condInput by remember { mutableStateOf("") }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showCreate = true },
                modifier = Modifier.testTag("add_automation_fab")
            ) {
                Icon(Icons.Filled.Add, contentDescription = "New automation rule")
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .testTag("automation_manager_screen"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                SectionHeader(
                    title = "Automation & Scheduling Engine",
                    subtitle = "Backed by Android WorkManager (respects Doze & battery optimization)",
                    icon = Icons.Filled.Schedule
                )
            }

            if (rules.isEmpty()) {
                item {
                    GlassOsCard {
                        Text(
                            "No scheduled automations yet. Tap + to create a One-time, Daily, Weekly, Monthly, or Conditional task (e.g., 9:00 AM job search or evening downloads organizer).",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                items(rules, key = { it.id }) { rule ->
                    GlassOsCard {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(rule.title, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "${rule.scheduleType} at ${String.format("%02d:%02d", rule.hourOfDay, rule.minuteOfHour)} • ${rule.lastStatus}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = NeuralCyan
                                )
                            }
                            Switch(checked = rule.enabled, onCheckedChange = { onToggleRule(rule) })
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            rule.prompt,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { onRunNow(rule) }) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Run Now")
                            }
                            TextButton(onClick = { onDeleteRule(rule.id) }) {
                                Text("Delete", color = CoralAlert)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreate) {
        val types = listOf("ONE_TIME", "DAILY", "WEEKLY", "MONTHLY", "CONDITIONAL")
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text("Create Scheduled Automation") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("Title") },
                        placeholder = { Text("Morning Job Search") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = prompt,
                        onValueChange = { prompt = it },
                        label = { Text("Agent Instruction") },
                        placeholder = { Text("Search new Kotlin jobs and save report") },
                        maxLines = 3
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(types) { t ->
                            FilterChip(
                                selected = scheduleType == t,
                                onClick = { scheduleType = t },
                                label = { Text(t) }
                            )
                        }
                    }
                    OutlinedTextField(
                        value = hourInput,
                        onValueChange = { hourInput = it.filter { ch -> ch.isDigit() } },
                        label = { Text("Hour (0-23)") },
                        singleLine = true
                    )
                    if (scheduleType == "CONDITIONAL") {
                        OutlinedTextField(
                            value = condInput,
                            onValueChange = { condInput = it },
                            label = { Text("Condition Note") },
                            placeholder = { Text("Only when Wi-Fi connected") },
                            singleLine = true
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (title.isNotBlank() && prompt.isNotBlank()) {
                        onCreateRule(title, prompt, scheduleType, hourInput.toIntOrNull() ?: 9, 0, condInput)
                        title = ""; prompt = ""; condInput = ""
                        showCreate = false
                    }
                }) { Text("Schedule") }
            },
            dismissButton = { TextButton(onClick = { showCreate = false }) { Text("Cancel") } }
        )
    }
}

@Composable
fun AgentMemoryScreen(
    memories: List<AgentMemoryEntity>,
    onSaveMemory: (category: String, key: String, content: String, id: Long) -> Unit,
    onDeleteMemory: (Long) -> Unit
) {
    var selectedCategory by remember { mutableStateOf("All") }
    var showAdd by remember { mutableStateOf(false) }
    var cat by remember { mutableStateOf(MemoryCategory.USER_PROFILE) }
    var keyInput by remember { mutableStateOf("") }
    var contentInput by remember { mutableStateOf("") }

    val filtered = remember(memories, selectedCategory) {
        if (selectedCategory == "All") memories else memories.filter { it.category == selectedCategory }
    }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAdd = true },
                modifier = Modifier.testTag("add_memory_fab")
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Add memory")
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .testTag("agent_memory_screen"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                SectionHeader(
                    title = "Persistent Agent Memory",
                    subtitle = "Editable local memory. Passwords, OTPs & API keys are blocked.",
                    icon = Icons.Filled.Psychology
                )
                Spacer(Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item {
                        FilterChip(
                            selected = selectedCategory == "All",
                            onClick = { selectedCategory = "All" },
                            label = { Text("All") }
                        )
                    }
                    items(MemoryCategory.all) { c ->
                        FilterChip(
                            selected = selectedCategory == c,
                            onClick = { selectedCategory = c },
                            label = { Text(c) }
                        )
                    }
                }
            }

            if (filtered.isEmpty()) {
                item {
                    GlassOsCard {
                        Text(
                            "No memories stored in this category yet. Add your CV summary, preferred language, or project rules so the autonomous agent personalizes its actions.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                items(filtered, key = { it.id }) { item ->
                    GlassOsCard {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                StatusBadge(label = item.category, color = NeuralCyan)
                                Spacer(Modifier.height(4.dp))
                                Text(item.memoryKey, style = MaterialTheme.typography.titleSmall)
                            }
                            IconButton(onClick = { onDeleteMemory(item.id) }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Delete memory", tint = CoralAlert)
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(item.content, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }

    if (showAdd) {
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("Add Agent Memory") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(MemoryCategory.all) { c ->
                            FilterChip(selected = cat == c, onClick = { cat = c }, label = { Text(c) })
                        }
                    }
                    OutlinedTextField(
                        value = keyInput,
                        onValueChange = { keyInput = it },
                        label = { Text("Memory Title") },
                        placeholder = { Text("e.g. Developer CV Summary") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = contentInput,
                        onValueChange = { contentInput = it },
                        label = { Text("Content") },
                        maxLines = 4
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (keyInput.isNotBlank() && contentInput.isNotBlank()) {
                        onSaveMemory(cat, keyInput, contentInput, 0)
                        keyInput = ""; contentInput = ""
                        showAdd = false
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text("Cancel") } }
        )
    }
}

@Composable
fun TaskHistoryScreen(
    tasks: List<AgentTaskEntity>,
    observeLogs: (Long) -> Flow<List<ExecutionLogEntity>>,
    onDeleteTask: (Long) -> Unit,
    onRerunPrompt: (String) -> Unit
) {
    var inspectedTask by remember { mutableStateOf<AgentTaskEntity?>(null) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("task_history_screen"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            SectionHeader(
                title = "Task History (${tasks.size})",
                subtitle = "Tap any task to inspect verified step-by-step tool logs",
                icon = Icons.Filled.History
            )
        }

        if (tasks.isEmpty()) {
            item {
                GlassOsCard {
                    Text(
                        "No recorded tasks yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            items(tasks, key = { it.id }) { task ->
                val badgeColor = when (task.status) {
                    TaskStatus.COMPLETED -> EmeraldPulse
                    TaskStatus.FAILED, TaskStatus.CANCELLED -> CoralAlert
                    TaskStatus.PAUSED, TaskStatus.WAITING_PERMISSION -> AmberWarning
                    else -> NeuralCyan
                }
                GlassOsCard(onClick = { inspectedTask = task }) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            task.title,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        StatusBadge(label = task.status, color = badgeColor)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "${SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()).format(Date(task.updatedAt))} • ${task.modelUsed} • ${task.tokensUsed} tokens",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (task.finalReport.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            task.finalReport.take(150),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }

    inspectedTask?.let { task ->
        val logs by observeLogs(task.id).collectAsStateWithLifecycle(initialValue = emptyList())
        AlertDialog(
            onDismissRequest = { inspectedTask = null },
            title = { Text(task.title) },
            text = {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(logs) { log ->
                        GlassOsCard {
                            Text(
                                "#${log.stepNumber} [${log.entryType}] ${log.title}",
                                style = MaterialTheme.typography.labelMedium,
                                color = NeuralCyan
                            )
                            Text(log.detail.take(400), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    onRerunPrompt(task.prompt)
                    inspectedTask = null
                }) { Text("Re-run") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        onDeleteTask(task.id)
                        inspectedTask = null
                    }) { Text("Delete", color = CoralAlert) }
                    TextButton(onClick = { inspectedTask = null }) { Text("Close") }
                }
            }
        )
    }
}
