package com.example.ui.components

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.example.ui.theme.EmeraldPulse
import com.example.ui.theme.NeuralCyan
import java.io.File

@SuppressLint("SetJavaScriptEnabled")
fun configureAgentWebView(webView: WebView) {
    webView.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        builtInZoomControls = true
        displayZoomControls = false
        useWideViewPort = true
        loadWithOverviewMode = true
        allowFileAccess = false
        mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
    }
    val cookieManager = CookieManager.getInstance()
    cookieManager.setAcceptCookie(true)
    cookieManager.setAcceptThirdPartyCookies(webView, true)
}

fun flushWebViewCookies() {
    runCatching { CookieManager.getInstance().flush() }
}

fun resolveWorkspaceShareUri(context: Context, relativePath: String): Uri? {
    val cleanRel = relativePath.trimStart('/')
    val target = File(File(context.filesDir, "virtual_env"), cleanRel)
    if (!target.exists() || !target.isFile) return null
    return runCatching {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", target)
    }.getOrNull()
}

@Composable
fun WebViewUploadChooserDialog(
    pendingCallback: ValueCallback<Array<Uri>>?,
    onPickFromPhone: () -> Unit,
    onDeliverWorkspaceUris: (Array<Uri>?) -> Unit
) {
    if (pendingCallback == null) return
    val context = LocalContext.current
    val workspaceFiles = remember(pendingCallback) {
        val root = File(context.filesDir, "virtual_env")
        listOf("uploads", "projects", "workspace", "downloads")
            .flatMap { sub ->
                val dir = File(root, sub)
                if (!dir.exists()) emptyList()
                else dir.walkTopDown()
                    .filter { it.isFile && it.length() > 0L }
                    .take(20)
                    .map { f ->
                        val rel = f.relativeTo(root).path.replace(File.separatorChar, '/')
                        Triple(f, rel, f.length())
                    }
                    .toList()
            }
            .take(30)
    }

    AlertDialog(
        onDismissRequest = { onDeliverWorkspaceUris(null) },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.AttachFile, contentDescription = null, tint = NeuralCyan)
                Spacer(Modifier.width(8.dp))
                Text("ওয়েবপেজে ফাইল আপলোড")
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "ফোন স্টোরেজ থেকে নতুন ফাইল পিক করুন, অথবা এজেন্ট ওয়ার্কস্পেসে আগে থেকে আপলোড করা ফাইল (যেমন CV / DXF) সরাসরি সিলেক্ট করুন:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = onPickFromPhone,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("ফোন থেকে ফাইল বাছাই করুন")
                }

                if (workspaceFiles.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "এজেন্ট ওয়ার্কস্পেস ফাইল:",
                        style = MaterialTheme.typography.labelMedium,
                        color = EmeraldPulse
                    )
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 220.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(workspaceFiles, key = { it.second }) { (file, rel, size) ->
                            GlassOsCard(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        val uri = runCatching {
                                            FileProvider.getUriForFile(
                                                context,
                                                "${context.packageName}.fileprovider",
                                                file
                                            )
                                        }.getOrNull()
                                        onDeliverWorkspaceUris(if (uri != null) arrayOf(uri) else null)
                                    }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Filled.Description,
                                        contentDescription = null,
                                        tint = NeuralCyan,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = file.name,
                                            style = MaterialTheme.typography.labelLarge,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "$rel • ${formatBytes(size)}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            OutlinedButton(onClick = onPickFromPhone) {
                Text("Browse Phone")
            }
        },
        dismissButton = {
            TextButton(onClick = { onDeliverWorkspaceUris(null) }) {
                Text("Cancel")
            }
        }
    )
}
