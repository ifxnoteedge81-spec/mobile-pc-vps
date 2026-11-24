package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.example.runtime.ScriptExecutionResult
import com.example.runtime.WorkspaceFileItem
import com.example.ui.components.GlassOsCard
import com.example.ui.components.StatusBadge
import com.example.ui.components.formatBytes
import com.example.ui.theme.CoralAlert
import com.example.ui.theme.EmeraldPulse
import com.example.ui.theme.NeuralCyan

@Composable
fun FileManagerScreen(
    currentPath: String,
    files: List<WorkspaceFileItem>,
    editorPath: String?,
    editorContent: String,
    editorRunOutput: ScriptExecutionResult?,
    onBrowsePath: (String) -> Unit,
    onSearch: (String) -> Unit,
    onOpenFile: (String) -> Unit,
    onEditorChange: (String) -> Unit,
    onSaveEditor: () -> Unit,
    onRunEditorFile: () -> Unit,
    onCloseEditor: () -> Unit,
    onCreateItem: (String, Boolean) -> Unit,
    onRenameItem: (String, String) -> Unit,
    onDeleteItem: (String) -> Unit,
    onImportUri: (Uri) -> Unit
) {
    if (editorPath != null) {
        BackHandler { onCloseEditor() }
        CodeEditorView(
            filePath = editorPath,
            content = editorContent,
            runOutput = editorRunOutput,
            onContentChange = onEditorChange,
            onSave = onSaveEditor,
            onRun = onRunEditorFile,
            onClose = onCloseEditor
        )
        return
    }

    var searchQuery by remember { mutableStateOf("") }
    var showCreateDialog by remember { mutableStateOf<Boolean?>(null) } // false=file, true=folder
    var newNameInput by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<WorkspaceFileItem?>(null) }
    var pendingRename by remember { mutableStateOf<WorkspaceFileItem?>(null) }

    val safImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) onImportUri(uri)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag("file_manager_screen")
    ) {
        GlassOsCard(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    if (currentPath.isNotEmpty()) {
                        IconButton(onClick = {
                            val parent = currentPath.substringBeforeLast("/", "")
                            onBrowsePath(parent)
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Up directory")
                        }
                    }
                    Text(
                        text = "/virtual_env/${currentPath}",
                        style = MaterialTheme.typography.titleSmall,
                        color = NeuralCyan
                    )
                }
                Row {
                    IconButton(onClick = { newNameInput = ""; showCreateDialog = false }) {
                        Icon(Icons.Filled.Add, contentDescription = "New File")
                    }
                    IconButton(onClick = { newNameInput = ""; showCreateDialog = true }) {
                        Icon(Icons.Filled.CreateNewFolder, contentDescription = "New Folder")
                    }
                    IconButton(onClick = { safImportLauncher.launch(arrayOf("text/*", "application/json")) }) {
                        Icon(Icons.Filled.FileOpen, contentDescription = "Import document via SAF")
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = searchQuery,
                onValueChange = {
                    searchQuery = it
                    onSearch(it)
                },
                placeholder = { Text("Search files in virtual_env...") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (files.isEmpty()) {
                item {
                    GlassOsCard {
                        Text(
                            "This folder is empty. Tap + to create a Python, JS, HTML, Bash, or Markdown file.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                items(files, key = { it.relativePath }) { item ->
                    GlassOsCard(
                        onClick = {
                            if (item.isDirectory) onBrowsePath(item.relativePath)
                            else onOpenFile(item.relativePath)
                        }
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Icon(
                                    imageVector = if (item.isDirectory) Icons.Filled.Folder else Icons.Filled.InsertDriveFile,
                                    contentDescription = null,
                                    tint = if (item.isDirectory) NeuralCyan else EmeraldPulse
                                )
                                Spacer(Modifier.width(10.dp))
                                Column {
                                    Text(item.name, style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        text = if (item.isDirectory) "Directory" else "${formatBytes(item.sizeBytes)} • .${item.extension.ifBlank { "txt" }}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Row {
                                IconButton(onClick = { newNameInput = item.name; pendingRename = item }) {
                                    Icon(Icons.Filled.DriveFileRenameOutline, contentDescription = "Rename")
                                }
                                IconButton(onClick = { pendingDelete = item }) {
                                    Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = CoralAlert)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    showCreateDialog?.let { isFolder ->
        AlertDialog(
            onDismissRequest = { showCreateDialog = null },
            title = { Text(if (isFolder) "New Folder" else "New File") },
            text = {
                OutlinedTextField(
                    value = newNameInput,
                    onValueChange = { newNameInput = it },
                    placeholder = { Text(if (isFolder) "folder_name" else "script.py / app.js / note.md") },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (newNameInput.isNotBlank()) onCreateItem(newNameInput, isFolder)
                    showCreateDialog = null
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { showCreateDialog = null }) { Text("Cancel") } }
        )
    }

    pendingRename?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingRename = null },
            title = { Text("Rename ${item.name}") },
            text = {
                OutlinedTextField(value = newNameInput, onValueChange = { newNameInput = it }, singleLine = true)
            },
            confirmButton = {
                Button(onClick = {
                    if (newNameInput.isNotBlank()) onRenameItem(item.relativePath, newNameInput)
                    pendingRename = null
                }) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { pendingRename = null }) { Text("Cancel") } }
        )
    }

    pendingDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Confirm Deletion") },
            text = { Text("Permanently delete '${item.relativePath}' from virtual_env?") },
            confirmButton = {
                Button(onClick = {
                    onDeleteItem(item.relativePath)
                    pendingDelete = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun CodeEditorView(
    filePath: String,
    content: String,
    runOutput: ScriptExecutionResult?,
    onContentChange: (String) -> Unit,
    onSave: () -> Unit,
    onRun: () -> Unit,
    onClose: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp)
            .testTag("code_editor_view")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(filePath.substringAfterLast("/"), style = MaterialTheme.typography.titleMedium)
                Text(filePath, style = MaterialTheme.typography.labelSmall, color = NeuralCyan)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilledTonalButton(onClick = onSave) {
                    Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Save")
                }
                Button(onClick = onRun) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Run")
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = "Close editor")
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = content,
            onValueChange = onContentChange,
            textStyle = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        )

        if (runOutput != null) {
            Spacer(Modifier.height(8.dp))
            GlassOsCard(borderColor = if (runOutput.success) EmeraldPulse else CoralAlert) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(runOutput.engineLabel, style = MaterialTheme.typography.titleSmall)
                    StatusBadge(
                        label = if (runOutput.success) "OK ${runOutput.durationMs}ms" else "ERROR",
                        color = if (runOutput.success) EmeraldPulse else CoralAlert
                    )
                }
                Spacer(Modifier.height(6.dp))
                Column(modifier = Modifier.verticalScroll(rememberScrollState()).height(110.dp)) {
                    if (runOutput.stdout.isNotBlank()) {
                        Text(
                            runOutput.stdout,
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace)
                        )
                    }
                    if (runOutput.stderr.isNotBlank()) {
                        Text(
                            runOutput.stderr,
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            color = CoralAlert
                        )
                    }
                }
            }
        }
    }
}
