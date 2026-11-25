package com.example.ui.screens

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.browser.BrowserController
import com.example.browser.BrowserTab
import com.example.browser.PageSnapshot
import com.example.storage.BookmarkEntity
import com.example.ui.components.GlassOsCard
import com.example.ui.components.WebViewUploadChooserDialog
import com.example.ui.components.configureAgentWebView
import com.example.ui.components.flushWebViewCookies

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    browserController: BrowserController,
    tabs: List<BrowserTab>,
    activeTabId: String,
    snapshot: PageSnapshot?,
    bookmarks: List<BookmarkEntity>,
    onExtractDom: () -> Unit,
    onCaptureSnapshot: () -> Unit,
    onAddBookmark: (String, String) -> Unit
) {
    val activeTab = tabs.firstOrNull { it.id == activeTabId } ?: tabs.first()
    var addressInput by remember(activeTabId, activeTab.url) { mutableStateOf(activeTab.url) }
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    var lastSyncedSeq by remember { mutableStateOf(activeTab.navigationSeq) }
    var lastSyncedTabId by remember { mutableStateOf(activeTab.id) }
    var showDomDialog by remember { mutableStateOf(false) }
    var pendingFileCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    var pendingAcceptTypes by remember { mutableStateOf(arrayOf("*/*")) }

    val webUploadLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        pendingFileCallback?.onReceiveValue(if (uris.isNotEmpty()) uris.toTypedArray() else null)
        pendingFileCallback = null
    }

    DisposableEffect(webViewInstance) {
        onDispose {
            flushWebViewCookies()
            webViewInstance?.let { browserController.detachWebView(it) }
            pendingFileCallback?.onReceiveValue(null)
            pendingFileCallback = null
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag("browser_screen")
    ) {
        // Tabs Row
        LazyRow(
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items(tabs, key = { it.id }) { tab ->
                FilterChip(
                    selected = tab.id == activeTab.id,
                    onClick = { browserController.switchTab(tab.id) },
                    label = {
                        Text(
                            tab.title.take(16),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    trailingIcon = if (tabs.size > 1) {
                        {
                            IconButton(
                                onClick = { browserController.closeTab(tab.id) },
                                modifier = Modifier.size(18.dp)
                            ) {
                                Icon(Icons.Filled.Close, contentDescription = "Close tab", modifier = Modifier.size(14.dp))
                            }
                        }
                    } else null
                )
            }
            item {
                IconButton(
                    onClick = { browserController.newTab() },
                    modifier = Modifier.testTag("browser_new_tab_button")
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "New browser tab")
                }
            }
        }

        // URL & Navigation Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { webViewInstance?.goBack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            IconButton(onClick = { webViewInstance?.goForward() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Forward")
            }
            OutlinedTextField(
                value = addressInput,
                onValueChange = { addressInput = it },
                singleLine = true,
                placeholder = { Text("Enter URL or search...") },
                modifier = Modifier
                    .weight(1f)
                    .testTag("browser_url_input")
            )
            IconButton(
                onClick = {
                    val normalized = browserController.normalizeUrlOrSearch(addressInput)
                    browserController.updateActiveTabMeta(url = normalized, isLoading = true)
                    webViewInstance?.loadUrl(normalized)
                },
                modifier = Modifier.testTag("browser_go_button")
            ) {
                Icon(Icons.Filled.Refresh, contentDescription = "Navigate or reload")
            }
        }

        // Action Bar (Bookmarks, DOM Extract, Snapshot)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                onClick = {
                    onExtractDom()
                    showDomDialog = true
                },
                modifier = Modifier.testTag("browser_extract_dom_button")
            ) {
                Icon(Icons.Filled.Code, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Inspect DOM")
            }
            TextButton(onClick = onCaptureSnapshot) {
                Icon(Icons.Filled.CameraAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Snapshot")
            }
            TextButton(onClick = { onAddBookmark(activeTab.title, activeTab.url) }) {
                Icon(Icons.Filled.BookmarkAdd, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Save")
            }
        }

        if (bookmarks.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(bookmarks.take(8)) { bm ->
                    FilterChip(
                        selected = false,
                        onClick = {
                            addressInput = bm.url
                            webViewInstance?.loadUrl(bm.url)
                        },
                        label = { Text(bm.title.take(18), maxLines = 1) }
                    )
                }
            }
        }

        if (activeTab.isLoading) {
            LinearProgressIndicator(
                progress = { (activeTab.progress / 100f).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Real Android WebView
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    configureAgentWebView(this)
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            if (url != null) {
                                addressInput = url
                                browserController.updateActiveTabMeta(url = url, isLoading = true)
                            }
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            flushWebViewCookies()
                            browserController.updateActiveTabMeta(
                                title = view?.title,
                                url = url,
                                isLoading = false,
                                progress = 100
                            )
                        }
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            browserController.updateActiveTabMeta(progress = newProgress)
                        }

                        override fun onShowFileChooser(
                            webView: WebView?,
                            filePathCallback: ValueCallback<Array<Uri>>?,
                            fileChooserParams: FileChooserParams?
                        ): Boolean {
                            pendingFileCallback?.onReceiveValue(null)
                            pendingFileCallback = filePathCallback
                            val accepts = fileChooserParams?.acceptTypes
                                ?.map { it.trim() }
                                ?.filter { it.isNotBlank() && it.contains("/") }
                                ?.toTypedArray()
                            pendingAcceptTypes = if (accepts != null && accepts.isNotEmpty()) accepts else arrayOf("*/*")
                            return true
                        }
                    }
                    browserController.attachWebView(this)
                    webViewInstance = this
                    lastSyncedTabId = activeTab.id
                    lastSyncedSeq = activeTab.navigationSeq
                    loadUrl(activeTab.url)
                }
            },
            update = { wv ->
                browserController.attachWebView(wv)
                webViewInstance = wv
                if (lastSyncedTabId != activeTab.id || lastSyncedSeq != activeTab.navigationSeq) {
                    lastSyncedTabId = activeTab.id
                    lastSyncedSeq = activeTab.navigationSeq
                    if (wv.url != activeTab.url) {
                        wv.loadUrl(activeTab.url)
                    }
                }
            },
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        )
    }

    WebViewUploadChooserDialog(
        pendingCallback = pendingFileCallback,
        onPickFromPhone = { webUploadLauncher.launch(pendingAcceptTypes) },
        onDeliverWorkspaceUris = { uris ->
            pendingFileCallback?.onReceiveValue(uris)
            pendingFileCallback = null
        }
    )

    if (showDomDialog) {
        AlertDialog(
            onDismissRequest = { showDomDialog = false },
            title = { Text("Extracted Page State") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    if (snapshot == null) {
                        Text("Extracting DOM from active WebView...")
                    } else {
                        GlassOsCard {
                            Text(snapshot.title, style = MaterialTheme.typography.titleSmall)
                            Text(snapshot.url, style = MaterialTheme.typography.labelSmall)
                            Spacer(Modifier.height(8.dp))
                            Text(snapshot.textContent.take(1500), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDomDialog = false }) { Text("Close") }
            }
        )
    }
}
