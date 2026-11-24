package com.example.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.example.runtime.TerminalLine
import com.example.runtime.WebServerState
import com.example.ui.components.GlassOsCard
import com.example.ui.components.StatusBadge
import com.example.ui.theme.CoralAlert
import com.example.ui.theme.EmeraldPulse
import com.example.ui.theme.NeuralCyan

@Composable
fun TerminalScreen(
    lines: List<TerminalLine>,
    currentDir: String,
    webServerState: WebServerState,
    pendingConfirmationCmd: String?,
    onRunCommand: (String, Boolean) -> Unit,
    onDismissConfirmation: () -> Unit,
    onToggleWebServer: () -> Unit,
    onOpenLocalServerInBrowser: (String) -> Unit
) {
    var cmdInput by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) {
            listState.animateScrollToItem(lines.lastIndex)
        }
    }

    val quickChips = listOf(
        "help",
        "sysinfo",
        "ls -la ..",
        "python3 ../scripts/hello_agent.py",
        "node ../scripts/analyze.js",
        "uname -a",
        "clear"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag("terminal_screen")
    ) {
        // Header & Local Dev Server Controls
        GlassOsCard(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Terminal, contentDescription = null, tint = EmeraldPulse)
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text("Sandboxed Linux Shell", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "PWD: ~/virtual_env/$currentDir",
                            style = MaterialTheme.typography.labelSmall,
                            color = NeuralCyan
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AssistChip(
                        onClick = onToggleWebServer,
                        label = { Text(if (webServerState.running) "Stop :8080" else "Serve :8080") },
                        leadingIcon = {
                            Icon(Icons.Filled.Dns, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                    )
                    if (webServerState.running) {
                        AssistChip(
                            onClick = { onOpenLocalServerInBrowser(webServerState.url) },
                            label = { Text("Open") },
                            leadingIcon = {
                                Icon(Icons.Filled.Language, contentDescription = null, modifier = Modifier.size(16.dp))
                            }
                        )
                    }
                }
            }
            if (webServerState.running) {
                Spacer(Modifier.height(6.dp))
                StatusBadge(label = "HTTP Server Live -> ${webServerState.url}", color = EmeraldPulse)
            }
        }

        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(quickChips) { chip ->
                AssistChip(
                    onClick = { onRunCommand(chip, false) },
                    label = { Text(chip, style = MaterialTheme.typography.labelSmall) }
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // Terminal Buffer
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .background(Color(0xFF06090F), MaterialTheme.shapes.medium)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(lines, key = { it.id }) { line ->
                Text(
                    text = line.text,
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                    color = when {
                        line.isError -> CoralAlert
                        line.isCommand -> NeuralCyan
                        else -> Color(0xFFE2E8F0)
                    }
                )
            }
        }

        // Input Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = cmdInput,
                onValueChange = { cmdInput = it },
                placeholder = { Text("Enter sh, python3, node, or sysinfo...") },
                singleLine = true,
                modifier = Modifier
                    .weight(1f)
                    .testTag("terminal_command_input")
            )
            FilledIconButton(
                onClick = {
                    if (cmdInput.isNotBlank()) {
                        onRunCommand(cmdInput, false)
                        cmdInput = ""
                    }
                },
                modifier = Modifier.testTag("terminal_run_button")
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Run command")
            }
        }
    }

    if (pendingConfirmationCmd != null) {
        AlertDialog(
            onDismissRequest = onDismissConfirmation,
            title = { Text("Level 2 Command Confirmation") },
            text = {
                Text("This command modifies files or performs a network operation:\n\n$pendingConfirmationCmd\n\nAllow execution inside sandboxed virtual_env?")
            },
            confirmButton = {
                Button(onClick = { onRunCommand(pendingConfirmationCmd, true) }) {
                    Text("Execute")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissConfirmation) { Text("Cancel") }
            }
        )
    }
}
