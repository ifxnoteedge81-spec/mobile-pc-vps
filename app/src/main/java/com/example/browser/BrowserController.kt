package com.example.browser

import android.graphics.Bitmap
import android.graphics.Canvas
import android.webkit.WebView
import com.example.runtime.VirtualWorkspaceManager
import com.example.security.NetworkAuditLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.lang.ref.WeakReference
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

data class BrowserTab(
    val id: String = "tab_${System.nanoTime()}",
    val title: String = "New Tab",
    val url: String = "https://duckduckgo.com",
    val isLoading: Boolean = false,
    val progress: Int = 0
)

data class PageSnapshot(
    val url: String,
    val title: String,
    val textContent: String,
    val linksSummary: String,
    val formsSummary: String
)

/**
 * WebView-backed Browser Automation Adapter + fast direct HTTP client.
 */
class BrowserController(
    private val workspace: VirtualWorkspaceManager,
    private val auditLogger: NetworkAuditLogger
) {
    private val initialTab = BrowserTab(title = "DuckDuckGo Search", url = "https://duckduckgo.com")
    private val _tabs = MutableStateFlow(listOf(initialTab))
    val tabs: StateFlow<List<BrowserTab>> = _tabs.asStateFlow()

    private val _activeTabId = MutableStateFlow(initialTab.id)
    val activeTabId: StateFlow<String> = _activeTabId.asStateFlow()

    private val _lastExtractedSnapshot = MutableStateFlow<PageSnapshot?>(null)
    val lastExtractedSnapshot: StateFlow<PageSnapshot?> = _lastExtractedSnapshot.asStateFlow()

    private val _automationLog = MutableStateFlow<List<String>>(emptyList())
    val automationLog: StateFlow<List<String>> = _automationLog.asStateFlow()

    // Triggers Live Screen auto-expand in CommandCenterScreen when browser tools run
    private val _browserActivitySignal = MutableStateFlow(0L)
    val browserActivitySignal: StateFlow<Long> = _browserActivitySignal.asStateFlow()

    private var webViewRef: WeakReference<WebView>? = null
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    fun attachWebView(webView: WebView) {
        webViewRef = WeakReference(webView)
    }

    fun getActiveTab(): BrowserTab {
        val id = _activeTabId.value
        return _tabs.value.firstOrNull { it.id == id } ?: _tabs.value.first()
    }

    fun newTab(url: String = "https://duckduckgo.com"): BrowserTab {
        val tab = BrowserTab(title = "Loading...", url = normalizeUrlOrSearch(url))
        _tabs.update { it + tab }
        _activeTabId.value = tab.id
        log("Opened tab: ${tab.url}")
        return tab
    }

    fun switchTab(tabId: String) {
        if (_tabs.value.any { it.id == tabId }) {
            _activeTabId.value = tabId
            val targetUrl = getActiveTab().url
            webViewRef?.get()?.post { webViewRef?.get()?.loadUrl(targetUrl) }
        }
    }

    fun closeTab(tabId: String) {
        if (_tabs.value.size <= 1) return
        val remaining = _tabs.value.filterNot { it.id == tabId }
        _tabs.value = remaining
        if (_activeTabId.value == tabId) {
            _activeTabId.value = remaining.last().id
            webViewRef?.get()?.post { webViewRef?.get()?.loadUrl(remaining.last().url) }
        }
    }

    fun updateActiveTabMeta(title: String? = null, url: String? = null, isLoading: Boolean? = null, progress: Int? = null) {
        val activeId = _activeTabId.value
        _tabs.update { list ->
            list.map { tab ->
                if (tab.id != activeId) tab else tab.copy(
                    title = title?.takeIf { it.isNotBlank() } ?: tab.title,
                    url = url?.takeIf { it.isNotBlank() } ?: tab.url,
                    isLoading = isLoading ?: tab.isLoading,
                    progress = progress ?: tab.progress
                )
            }
        }
    }

    private suspend fun ensureWebViewReady(): WebView? {
        webViewRef?.get()?.let { return it }
        _browserActivitySignal.value = System.currentTimeMillis()
        repeat(8) {
            delay(180)
            webViewRef?.get()?.let { return it }
        }
        return null
    }

    suspend fun navigateAndWait(urlOrQuery: String): Result<PageSnapshot> = withContext(Dispatchers.Main) {
        val targetUrl = normalizeUrlOrSearch(urlOrQuery)
        updateActiveTabMeta(url = targetUrl, isLoading = true, progress = 10)
        _browserActivitySignal.value = System.currentTimeMillis()
        log("Navigate -> $targetUrl")

        val wv = ensureWebViewReady()
        if (wv != null) {
            wv.loadUrl(targetUrl)
            var attempts = 0
            while (attempts < 14) {
                delay(200)
                val tab = getActiveTab()
                if (!tab.isLoading && tab.progress >= 95 && attempts >= 2) break
                attempts++
            }
            val snap = extractPageContentInternal(wv)
            if (snap != null && snap.textContent.isNotBlank()) {
                _lastExtractedSnapshot.value = snap
                return@withContext Result.success(snap)
            }
        }

        // Fast headless HTTP fallback if WebView isn't mounted (e.g. background WorkManager)
        fetchLightweightHeadless(targetUrl)
    }

    suspend fun extractCurrentPage(): Result<PageSnapshot> = withContext(Dispatchers.Main) {
        val wv = ensureWebViewReady()
            ?: return@withContext fetchLightweightHeadless(getActiveTab().url)
        val snap = extractPageContentInternal(wv)
            ?: return@withContext Result.failure(IllegalStateException("Unable to inspect current WebView DOM"))
        _lastExtractedSnapshot.value = snap
        log("Extracted DOM (${snap.textContent.length} chars) from ${snap.url}")
        Result.success(snap)
    }

    suspend fun clickElement(selectorOrText: String): Result<String> = withContext(Dispatchers.Main) {
        val wv = ensureWebViewReady()
            ?: return@withContext Result.failure(IllegalStateException("Live Browser WebView is not currently visible. Ask user or open Command Center Live Screen."))
        val escaped = JSONObject.quote(selectorOrText)
        val js = """
            (function() {
                const q = $escaped;
                let el = null;
                try { el = document.querySelector(q); } catch(e) {}
                if (!el) {
                    const all = Array.from(document.querySelectorAll('a, button, input[type=submit], input[type=button], [role=button], span, div'));
                    el = all.find(n => (n.innerText || n.value || n.getAttribute('aria-label') || '').trim().toLowerCase().includes(q.toLowerCase()));
                }
                if (!el) return JSON.stringify({ok:false, msg:'Element not found: ' + q});
                el.scrollIntoView({behavior:'instant', block:'center'});
                el.click();
                return JSON.stringify({ok:true, msg:'Clicked: ' + (el.innerText || el.value || el.tagName)});
            })();
        """.trimIndent()
        val raw = evalJs(wv, js)
        log("Click '$selectorOrText' -> $raw")
        Result.success(raw ?: "Click dispatched")
    }

    suspend fun fillInput(selectorOrLabel: String, value: String): Result<String> = withContext(Dispatchers.Main) {
        val wv = ensureWebViewReady()
            ?: return@withContext Result.failure(IllegalStateException("Live Browser WebView is not currently visible."))
        val qEsc = JSONObject.quote(selectorOrLabel)
        val vEsc = JSONObject.quote(value)
        val js = """
            (function() {
                const q = $qEsc;
                const val = $vEsc;
                let el = null;
                try { el = document.querySelector(q); } catch(e) {}
                if (!el) {
                    const inputs = Array.from(document.querySelectorAll('input, textarea, select'));
                    el = inputs.find(i => ((i.name||'') + ' ' + (i.id||'') + ' ' + (i.placeholder||'') + ' ' + (i.getAttribute('aria-label')||'')).toLowerCase().includes(q.toLowerCase()));
                }
                if (!el) return 'Input field not found: ' + q;
                if (el.type && el.type.toLowerCase() === 'password') return 'Blocked: Cannot auto-fill password fields.';
                el.scrollIntoView({behavior:'instant', block:'center'});
                el.focus();
                el.value = val;
                el.dispatchEvent(new Event('input', {bubbles:true}));
                el.dispatchEvent(new Event('change', {bubbles:true}));
                return 'Filled field [' + (el.name || el.id || el.placeholder || el.tagName) + ']';
            })();
        """.trimIndent()
        val raw = evalJs(wv, js)
        log("Fill '$selectorOrLabel' -> $raw")
        Result.success(raw ?: "Input updated")
    }

    suspend fun scrollPage(direction: String): Result<String> = withContext(Dispatchers.Main) {
        val wv = ensureWebViewReady()
            ?: return@withContext Result.failure(IllegalStateException("Browser WebView not attached"))
        val dy = if (direction.lowercase() == "up") -750 else 750
        wv.evaluateJavascript("window.scrollBy({top:$dy, behavior:'smooth'});", null)
        log("Scrolled $direction")
        Result.success("Scrolled browser $direction")
    }

    suspend fun goBack(): Result<String> = withContext(Dispatchers.Main) {
        val wv = ensureWebViewReady()
            ?: return@withContext Result.failure(IllegalStateException("Browser WebView not attached"))
        if (wv.canGoBack()) {
            wv.goBack()
            Result.success("Navigated back in browser")
        } else {
            Result.failure(IllegalStateException("Browser has no previous page"))
        }
    }

    suspend fun evalCustomJs(script: String): Result<String> = withContext(Dispatchers.Main) {
        val wv = ensureWebViewReady()
            ?: return@withContext Result.failure(IllegalStateException("Browser WebView not attached"))
        val res = evalJs(wv, script) ?: "(null)"
        Result.success(res.take(4000))
    }

    suspend fun fastHttpRequest(
        method: String,
        url: String,
        body: String = "",
        contentType: String = "application/json"
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanMethod = method.uppercase().ifBlank { "GET" }
            val builder = Request.Builder()
                .url(url.trim())
                .header("User-Agent", "PersonalAIComputer/1.0 Android")
            when (cleanMethod) {
                "POST" -> builder.post(body.toRequestBody(contentType.toMediaType()))
                "PUT" -> builder.put(body.toRequestBody(contentType.toMediaType()))
                "DELETE" -> builder.delete()
                else -> builder.get()
            }
            httpClient.newCall(builder.build()).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                auditLogger.log(url.substringAfter("://").substringBefore("/"), "HTTP $cleanMethod", "HTTP ${resp.code}", raw.length.toLong())
                check(resp.isSuccessful) { "HTTP ${resp.code}: ${raw.take(400)}" }
                raw.take(5000)
            }
        }
    }

    suspend fun captureWebViewSnapshot(): Result<String> = withContext(Dispatchers.Main) {
        val wv = webViewRef?.get()
            ?: return@withContext Result.failure(IllegalStateException("Browser WebView not attached"))
        if (wv.width <= 0 || wv.height <= 0) {
            return@withContext Result.failure(IllegalStateException("Browser view has zero bounds"))
        }
        val bmp = Bitmap.createBitmap(wv.width, wv.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        wv.draw(canvas)
        val outFile = File(workspace.tmpDir, "browser_snapshot_${System.currentTimeMillis()}.png")
        withContext(Dispatchers.IO) {
            FileOutputStream(outFile).use { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) }
        }
        bmp.recycle()
        val rel = workspace.relativePathOf(outFile)
        log("Captured snapshot: $rel")
        Result.success(rel)
    }

    suspend fun downloadFileToWorkspace(url: String, filename: String? = null): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val safeName = (filename?.takeIf { it.isNotBlank() }
                    ?: url.substringAfterLast("/").substringBefore("?").ifBlank { "download_${System.currentTimeMillis()}.bin" })
                    .replace(Regex("[^a-zA-Z0-9._-]"), "_")
                val request = Request.Builder().url(url).build()
                httpClient.newCall(request).execute().use { resp ->
                    check(resp.isSuccessful) { "Download HTTP ${resp.code}" }
                    val bytes = resp.body?.bytes() ?: ByteArray(0)
                    val out = File(workspace.downloadsDir, safeName)
                    out.writeBytes(bytes)
                    auditLogger.log(url.substringAfter("://").substringBefore("/"), "File Download", "200 OK", bytes.size.toLong())
                    log("Downloaded ${bytes.size} bytes -> downloads/$safeName")
                    "downloads/$safeName (${bytes.size} bytes)"
                }
            }
        }

    private suspend fun fetchLightweightHeadless(url: String): Result<PageSnapshot> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36")
                .build()
            httpClient.newCall(req).execute().use { resp ->
                val html = resp.body?.string().orEmpty()
                auditLogger.log(url.substringAfter("://").substringBefore("/"), "Headless Fetch", "HTTP ${resp.code}", html.length.toLong())
                val title = Regex("<title>(.*?)</title>", RegexOption.IGNORE_CASE).find(html)?.groupValues?.getOrNull(1)?.trim() ?: url
                val cleanText = html
                    .replace(Regex("<script[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), " ")
                    .replace(Regex("<style[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), " ")
                    .replace(Regex("<[^>]+>"), " ")
                    .replace(Regex("\\s+"), " ")
                    .trim()
                    .take(5000)
                val links = Regex("<a[^>]+href=[\"']([^\"']+)[\"'][^>]*>(.*?)</a>", RegexOption.IGNORE_CASE)
                    .findAll(html)
                    .take(15)
                    .map { m -> "${m.groupValues[2].replace(Regex("<[^>]+>"), "").trim()} -> ${m.groupValues[1]}" }
                    .joinToString("\n")
                val snap = PageSnapshot(
                    url = url,
                    title = title,
                    textContent = cleanText,
                    linksSummary = links,
                    formsSummary = "(Headless HTTP fetch; open Live Screen Browser tab for interactive DOM forms)"
                )
                _lastExtractedSnapshot.value = snap
                snap
            }
        }
    }

    private suspend fun extractPageContentInternal(wv: WebView): PageSnapshot? {
        val js = """
            (function() {
                const title = document.title || '';
                const url = location.href || '';
                const text = (document.body ? document.body.innerText : '').replace(/\s+/g, ' ').trim().slice(0, 5000);
                const links = Array.from(document.querySelectorAll('a[href]')).slice(0, 20)
                    .map(a => (a.innerText || 'link').trim().slice(0, 50) + ' -> ' + a.href).join('\n');
                const forms = Array.from(document.querySelectorAll('input, textarea, select')).slice(0, 15)
                    .map(i => (i.tagName + '[name=' + (i.name||'') + ', id=' + (i.id||'') + ', type=' + (i.type||'') + ']')).join(', ');
                return JSON.stringify({title, url, text, links, forms});
            })();
        """.trimIndent()
        val raw = evalJs(wv, js) ?: return null
        return runCatching {
            val unescaped = JSONObject("{\"v\":$raw}").getString("v")
            val json = JSONObject(unescaped)
            PageSnapshot(
                url = json.optString("url"),
                title = json.optString("title"),
                textContent = json.optString("text"),
                linksSummary = json.optString("links"),
                formsSummary = json.optString("forms")
            )
        }.getOrNull()
    }

    private suspend fun evalJs(wv: WebView, js: String): String? {
        return withTimeoutOrNull(3_500L) {
            suspendCancellableCoroutine { cont ->
                wv.evaluateJavascript(js) { res ->
                    if (cont.isActive) cont.resume(res)
                }
            }
        }
    }

    fun normalizeUrlOrSearch(input: String): String {
        val t = input.trim()
        return when {
            t.startsWith("http://") || t.startsWith("https://") -> t
            t.contains(".") && !t.contains(" ") -> "https://$t"
            else -> "https://duckduckgo.com/?q=${URLEncoder.encode(t, "UTF-8")}"
        }
    }

    private fun log(msg: String) {
        _automationLog.update { (listOf(msg) + it).take(40) }
    }
}
