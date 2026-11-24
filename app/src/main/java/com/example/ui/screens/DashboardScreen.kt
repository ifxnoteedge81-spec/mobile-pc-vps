package com.example.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.WorkOutline
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.agent.AgentRuntimeState
import com.example.resources.DeviceResourceSnapshot
import com.example.storage.AgentTaskEntity
import com.example.storage.AppSettings
import com.example.storage.AutomationRuleEntity
import com.example.storage.ExecutionLogEntity
import com.example.storage.TaskStatus
import com.example.ui.components.GlassOsCard
import com.example.ui.components.SectionHeader
import com.example.ui.components.StatusBadge
import com.example.ui.components.TelemetryMeter
import com.example.ui.navigation.AppDestination
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.EmeraldPulse
import com.example.ui.theme.NeuralCyan

private data class QuickSkillPreset(
    val label: String,
    val prompt: String,
    val icon: ImageVector
)

@Composable
fun DashboardScreen(
    settings: AppSettings,
    resources: DeviceResourceSnapshot,
    agentState: AgentRuntimeState,
    activeModelLabel: String,
    isOnline: Boolean,
    accessibilityConnected: Boolean,
    tasks: List<AgentTaskEntity>,
    automations: List<AutomationRuleEntity>,
    recentLogs: List<ExecutionLogEntity>,
    onRefreshResources: () -> Unit,
    onNavigate: (AppDestination) -> Unit,
    onLaunchSkillPrompt: (String) -> Unit
) {
    LaunchedEffect(Unit) { onRefreshResources() }

    val activeTasksCount = tasks.count {
        it.status == TaskStatus.RUNNING || it.status == TaskStatus.PLANNING || it.status == TaskStatus.WAITING_PERMISSION
    }
    val completedTasksCount = tasks.count { it.status == TaskStatus.COMPLETED }
    val activeAutomationsCount = automations.count { it.enabled }

    val presets = listOf(
        QuickSkillPreset(
            "Job Search Agent",
            "Find 10 remote Kotlin/Python jobs using the browser, compare requirements, and save an organized report in workspace/jobs_report.md.",
            Icons.Filled.WorkOutline
        ),
        QuickSkillPreset(
            "Research AI Video Tools",
            "Open the browser and research the best free AI video generation tools, compare limits, and save a summary in workspace/ai_video_tools.md.",
            Icons.Filled.Language
        ),
        QuickSkillPreset(
            "YouTube Package",
            "Prepare a YouTube video production package about Personal AI Operating Systems using youtube_prepare_package.",
            Icons.Filled.VideoLibrary
        ),
        QuickSkillPreset(
            "Run Python System Audit",
            "Create and run a Python script in scripts/health_check.py that analyzes workspace files and prints a JSON summary.",
            Icons.Filled.Code
        )
    )

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("dashboard_screen"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Hero OS Banner
        item {
            GlassOsCard(borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                        .clip(MaterialTheme.shapes.small)
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.img_ai_core_banner),
                        contentDescription = "Personal AI Computer neural core banner",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color.Transparent, Color(0xE6090D16))
                                )
                            )
                    )
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.img_agent_avatar),
                            contentDescription = "Autonomous AI Agent Emblem",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "PERSONAL AI COMPUTER",
                                style = MaterialTheme.typography.titleLarge,
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Local-First Autonomous Mobile OS • ${if (isOnline) "Online" else "Offline Mode"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFCBD5E1)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StatusBadge(
                        label = if (agentState.isBusy) "Agent: ${agentState.taskStatus}" else "Agent Ready",
                        color = if (agentState.isBusy) AmberWarning else EmeraldPulse
                    )
                    StatusBadge(
                        label = activeModelLabel.take(28),
                        color = NeuralCyan
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { onNavigate(AppDestination.CommandCenter) },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("hero_open_command_center")
                    ) {
                        Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Command Center")
                    }
                    FilledTonalButton(
                        onClick = { onNavigate(AppDestination.VirtualDesktop) },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("hero_open_virtual_desktop")
                    ) {
                        Icon(Icons.Filled.TouchApp, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Virtual Desktop")
                    }
                }
            }
        }

        // System Telemetry Grid
        item {
            SectionHeader(
                title = "Hardware & Runtime Telemetry",
                subtitle = "Real Android memory, CPU, and local Linux workspace metrics",
                icon = Icons.Filled.Memory
            )
            Spacer(Modifier.height(8.dp))
            GlassOsCard(onClick = { onNavigate(AppDestination.ResourceManager) }) {
                TelemetryMeter(
                    label = "Physical RAM Used (${resources.totalPhysicalRamMb - resources.availableRamMb} / ${resources.totalPhysicalRamMb} MB)",
                    valueText = "${resources.memoryPressureLabel} • Budget ${settings.ramBudgetMb} MB",
                    progress = resources.usedRamPercent,
                    accentColor = if (resources.lowMemoryFlag) AmberWarning else NeuralCyan
                )
                Spacer(Modifier.height(12.dp))
                val storageUsedGb = (resources.storageTotalGb - resources.storageFreeGb).coerceAtLeast(0.0)
                val storageProgress = if (resources.storageTotalGb > 0) (storageUsedGb / resources.storageTotalGb).toFloat() else 0f
                TelemetryMeter(
                    label = "Internal Storage (${String.format("%.1f", resources.storageFreeGb)} GB free)",
                    valueText = "${resources.cpuCores} Cores • ${resources.primaryAbi}",
                    progress = storageProgress,
                    accentColor = EmeraldPulse
                )
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Tasks: $activeTasksCount active / $completedTasksCount done",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Automations: $activeAutomationsCount active",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Core Environment Shortcuts
        item {
            SectionHeader(
                title = "Core Operating Modules",
                subtitle = "Direct access to Linux shell, WebView automation, files & security",
                icon = Icons.Filled.Terminal
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ModuleShortcutCard(
                    title = "Linux Shell",
                    subtitle = "sh / Python / V8",
                    icon = Icons.Filled.Terminal,
                    modifier = Modifier.weight(1f).testTag("shortcut_terminal"),
                    onClick = { onNavigate(AppDestination.Terminal) }
                )
                ModuleShortcutCard(
                    title = "Browser",
                    subtitle = "WebView + DOM",
                    icon = Icons.Filled.Language,
                    modifier = Modifier.weight(1f).testTag("shortcut_browser"),
                    onClick = { onNavigate(AppDestination.Browser) }
                )
                ModuleShortcutCard(
                    title = "Files & IDE",
                    subtitle = "Code & Edit",
                    icon = Icons.Filled.FolderOpen,
                    modifier = Modifier.weight(1f).testTag("shortcut_files"),
                    onClick = { onNavigate(AppDestination.FileManager) }
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ModuleShortcutCard(
                    title = "Virtual Hands",
                    subtitle = if (accessibilityConnected) "Connected" else "Setup Needed",
                    icon = Icons.Filled.TouchApp,
                    modifier = Modifier.weight(1f).testTag("shortcut_security"),
                    onClick = { onNavigate(AppDestination.PermissionsSecurity) }
                )
                ModuleShortcutCard(
                    title = "Automations",
                    subtitle = "WorkManager",
                    icon = Icons.Filled.Schedule,
                    modifier = Modifier.weight(1f).testTag("shortcut_automations"),
                    onClick = { onNavigate(AppDestination.AutomationManager) }
                )
                ModuleShortcutCard(
                    title = "AI Keys",
                    subtitle = "Gemini / OR",
                    icon = Icons.Filled.Security,
                    modifier = Modifier.weight(1f).testTag("shortcut_models"),
                    onClick = { onNavigate(AppDestination.AiModelSettings) }
                )
            }
        }

        // Autonomous Agent Skill Launchers
        item {
            SectionHeader(
                title = "Autonomous Agent Skills",
                subtitle = "Tap to launch multi-step tool workflows",
                icon = Icons.Filled.PlayArrow
            )
            Spacer(Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(presets) { preset ->
                    AssistChip(
                        onClick = {
                            onLaunchSkillPrompt(preset.prompt)
                            onNavigate(AppDestination.CommandCenter)
                        },
                        label = { Text(preset.label) },
                        leadingIcon = {
                            Icon(preset.icon, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                    )
                }
            }
        }

        // Recent Activity Feed
        item {
            SectionHeader(
                title = "Recent Verified Activity",
                subtitle = "Persisted locally in Room SQLite",
                icon = Icons.Filled.Schedule
            )
        }

        if (recentLogs.isEmpty()) {
            item {
                GlassOsCard {
                    Text(
                        text = "No agent tool executions yet. Open AI Command Center or Terminal to run your first autonomous workflow.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            items(recentLogs.take(6)) { log ->
                GlassOsCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = log.title,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        StatusBadge(
                            label = log.entryType,
                            color = if (log.verified) EmeraldPulse else NeuralCyan
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = log.detail.take(160),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun ModuleShortcutCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    GlassOsCard(modifier = modifier, onClick = onClick) {
        Icon(
            imageVector = icon,
            contentDescription = title,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.height(8.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
