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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SwipeUp
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.accessibility.ObservedUiNode
import com.example.agent.AgentRuntimeState
import com.example.browser.BrowserTab
import com.example.browser.PageSnapshot
import com.example.runtime.TerminalLine
import com.example.runtime.WorkspaceFileItem
import com.example.ui.components.GlassOsCard
import com.example.ui.components.SectionHeader
import com.example.ui.components.StatusBadge
import com.example.ui.navigation.AppDestination
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.EmeraldPulse
import com.example.ui.theme.NeuralCyan

@Composable
fun VirtualDesktopScreen(
    agentState: AgentRuntimeState,
    browserTabs: List<BrowserTab>,
    browserSnapshot: PageSnapshot?,
    terminalLines: List<TerminalLine>,
    workspaceFiles: List<WorkspaceFileItem>,
    accessibilityConnected: Boolean,
    accessibilityHealth: com.example.accessibility.AccessibilityHealthState =
        if (accessibilityConnected) com.example.accessibility.AccessibilityHealthState.CONNECTED
        else com.example.accessibility.AccessibilityHealthState.DISABLED,
    activeWindowPackage: String,
    observedNodes: List<ObservedUiNode>,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenAppInfoSettings: () -> Unit = {},
    onInspectScreen: () -> Unit,
    onTestGesture: (String) -> Unit,
    onNavigate: (AppDestination) -> Unit
) {
    val activeTab = browserTabs.firstOrNull()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("virtual_desktop_screen"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            SectionHeader(
                title = "Virtual Desktop Workspace",
                subtitle = "Live multi-environment view: Virtual Env Controller + Android Virtual Hands",
                icon = Icons.Filled.Computer
            )
        }

        // Window 1: Active Agent & Browser State
        item {
            GlassOsCard(borderColor = NeuralCyan.copy(alpha = 0.5f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Language, contentDescription = null, tint = NeuralCyan)
                        Spacer(Modifier.width(8.dp))
                        Text("Browser Window (${browserTabs.size} tab)", style = MaterialTheme.typography.titleSmall)
                    }
                    FilledTonalButton(onClick = { onNavigate(AppDestination.Browser) }) {
                        Icon(Icons.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Focus")
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = activeTab?.url ?: "https://duckduckgo.com",
                    style = MaterialTheme.typography.labelMedium,
                    color = NeuralCyan,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (browserSnapshot != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Extracted DOM: ${browserSnapshot.title}\n${browserSnapshot.textContent.take(180)}...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Window 2: Terminal Tail
        item {
            GlassOsCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Terminal, contentDescription = null, tint = EmeraldPulse)
                        Spacer(Modifier.width(8.dp))
                        Text("Linux Shell Session", style = MaterialTheme.typography.titleSmall)
                    }
                    FilledTonalButton(onClick = { onNavigate(AppDestination.Terminal) }) {
                        Text("Open Shell")
                    }
                }
                Spacer(Modifier.height(8.dp))
                Column(modifier = Modifier.fillMaxWidth()) {
                    terminalLines.takeLast(5).forEach { line ->
                        Text(
                            text = line.text,
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            color = if (line.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        // Window 3: Workspace Files
        item {
            GlassOsCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("Sandboxed Files (${workspaceFiles.size} items)", style = MaterialTheme.typography.titleSmall)
                    }
                    FilledTonalButton(onClick = { onNavigate(AppDestination.FileManager) }) {
                        Text("Explorer")
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = workspaceFiles.take(6).joinToString(" • ") { it.name }.ifBlank { "virtual_env/" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Window 4: Controller B — Virtual Hands & Screen Observation (Accessibility Service)
        item {
            GlassOsCard(borderColor = if (accessibilityConnected) EmeraldPulse else AmberWarning) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.TouchApp, contentDescription = null, tint = if (accessibilityConnected) EmeraldPulse else AmberWarning)
                        Spacer(Modifier.width(8.dp))
                        Text("Virtual Hands (Controller B)", style = MaterialTheme.typography.titleSmall)
                    }
                    StatusBadge(
                        label = accessibilityHealth.label,
                        color = if (accessibilityConnected) EmeraldPulse else AmberWarning
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = when (accessibilityHealth) {
                        com.example.accessibility.AccessibilityHealthState.CONNECTED ->
                            "Active Window: $activeWindowPackage\nProtected banking, OTP, password, and biometric screens are automatically blocked."
                        com.example.accessibility.AccessibilityHealthState.ENABLED_BUT_UNBOUND ->
                            "Accessibility সেটিংসে চালু থাকলেও Android/OEM সার্ভিসটি সাময়িক বন্ধ রেখেছে। Settings-এ গিয়ে 'AI Computer Virtual Hands' একবার OFF করে আবার ON করুন।"
                        com.example.accessibility.AccessibilityHealthState.DISABLED ->
                            "Virtual Hands বন্ধ আছে। Settings থেকে চালু করুন (অপশন লক/গ্রে থাকলে App Info → উপরের ডানে ⋮ → Allow restricted settings দিন)।"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                if (!accessibilityConnected) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = onOpenAccessibilitySettings,
                            modifier = Modifier.testTag("enable_accessibility_button")
                        ) {
                            Text(
                                if (accessibilityHealth == com.example.accessibility.AccessibilityHealthState.ENABLED_BUT_UNBOUND)
                                    "Re-toggle Virtual Hands"
                                else "Enable Virtual Hands"
                            )
                        }
                        OutlinedButton(onClick = onOpenAppInfoSettings) {
                            Text("Restricted Settings")
                        }
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = onInspectScreen,
                            modifier = Modifier.testTag("inspect_screen_nodes_button")
                        ) {
                            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Inspect UI Tree")
                        }
                        OutlinedButton(onClick = { onTestGesture("swipe") }) {
                            Icon(Icons.Filled.SwipeUp, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Test Scroll")
                        }
                        OutlinedButton(onClick = { onTestGesture("notifications") }) {
                            Text("Notifs")
                        }
                    }
                }
            }
        }

        if (observedNodes.isNotEmpty()) {
            item {
                Text(
                    "Observed Accessibility Nodes (${observedNodes.size})",
                    style = MaterialTheme.typography.titleSmall
                )
            }
            items(observedNodes.take(12)) { node ->
                GlassOsCard {
                    Text(
                        text = "${node.className} • (${node.centerX.toInt()}, ${node.centerY.toInt()})",
                        style = MaterialTheme.typography.labelMedium,
                        color = NeuralCyan
                    )
                    Text(
                        text = "text='${node.text}' desc='${node.contentDesc}' clickable=${node.clickable}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
