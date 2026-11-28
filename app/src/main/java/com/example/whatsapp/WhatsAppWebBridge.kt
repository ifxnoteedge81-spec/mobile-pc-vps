package com.example.whatsapp

import android.annotation.SuppressLint
import android.content.Context
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import com.example.security.NetworkAuditLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * Persistent Desktop-Mode WhatsApp Web Bridge (`https://web.whatsapp.com`).
 * Extracts connected Owner phone automatically from WA Web storage, handles direct phone
 * deep-links (`send?phone=...`) for scheduled/manual sending, and observes both incoming
 * messages and Owner-typed bot commands cleanly without double firing.
 */
class WhatsAppWebBridge(
    private val appContext: Context,
    private val scope: CoroutineScope,
    private val auditLogger: NetworkAuditLogger
) {
    private val _linkStatus = MutableStateFlow(WaLinkStatus.DISCONNECTED)
    val linkStatus: StateFlow<WaLinkStatus> = _linkStatus.asStateFlow()

    private val _pairingCode = MutableStateFlow("")
    val pairingCode: StateFlow<String> = _pairingCode.asStateFlow()

    private val _detectedOwnerPhone = MutableStateFlow("")
    val detectedOwnerPhone: StateFlow<String> = _detectedOwnerPhone.asStateFlow()

    private val _statusDetail = MutableStateFlow("Tap 'Open Link Portal' to pair WhatsApp via QR or 8-digit phone link code.")
    val statusDetail: StateFlow<String> = _statusDetail.asStateFlow()

    private val _activeChatName = MutableStateFlow("")
    val activeChatName: StateFlow<String> = _activeChatName.asStateFlow()

    private var webView: WebView? = null
    private var attachedContainer: FrameLayout? = null
    private var observerBootstrapped = false

    @Volatile
    private var listener: BridgeListener? = null

    interface BridgeListener {
        fun onChatsSynced(chats: List<WaSyncedChat>)
        fun onIncomingMessage(message: WaIncomingMessage)
        fun onUnreadChatDetected(chatName: String, isGroup: Boolean)
        fun onStatusChanged(status: WaLinkStatus, detail: String)
        fun onOwnerPhoneDetected(phoneDigits: String) {}
    }

    fun setListener(l: BridgeListener) {
        listener = l
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun ensureInitialized(loadIfNeeded: Boolean = true): WebView {
        val existing = webView
        if (existing != null) {
            if (loadIfNeeded && existing.url.isNullOrBlank()) {
                _linkStatus.value = WaLinkStatus.LOADING
                existing.loadUrl(WA_WEB_URL)
            }
            return existing
        }

        val wv = WebView(appContext.applicationContext).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            runCatching {
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            }

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                useWideViewPort = true
                loadWithOverviewMode = true
                setSupportZoom(true)
                builtInZoomControls = true
                displayZoomControls = false
                mediaPlaybackRequiresUserGesture = false
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                userAgentString = DESKTOP_CHROME_UA
            }

            addJavascriptInterface(JsWaReceiver(), "AndroidWaBridge")

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    if (newProgress >= 90 && !observerBootstrapped) {
                        injectObserverScript(view)
                    }
                }
            }

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val url = request?.url?.toString().orEmpty()
                    return !(url.startsWith("https://web.whatsapp.com") || url.startsWith("https://www.whatsapp.com"))
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    observerBootstrapped = false
                    injectObserverScript(view)
                    auditLogger.log("web.whatsapp.com", "WA Web Page Loaded", "Ready", 0L)
                }
            }
        }

        webView = wv
        if (loadIfNeeded) {
            _linkStatus.value = WaLinkStatus.LOADING
            _statusDetail.value = "Connecting to WhatsApp Web Linked Devices..."
            wv.loadUrl(WA_WEB_URL)
        }
        return wv
    }

    fun attachToContainer(container: FrameLayout) {
        val wv = ensureInitialized(loadIfNeeded = true)
        wv.onResume()
        val oldParent = wv.parent as? ViewGroup
        if (oldParent === container) return
        oldParent?.removeView(wv)
        container.removeAllViews()
        container.addView(wv)
        attachedContainer = container
    }

    fun detachFromContainer(container: FrameLayout) {
        if (attachedContainer === container) {
            container.removeAllViews()
            attachedContainer = null
        }
    }

    fun reloadSession() {
        scope.launch(Dispatchers.Main) {
            observerBootstrapped = false
            val wv = ensureInitialized(loadIfNeeded = false)
            _linkStatus.value = WaLinkStatus.LOADING
            _statusDetail.value = "Reloading WhatsApp Web session..."
            wv.loadUrl(WA_WEB_URL)
        }
    }

    fun clearSessionAndRelink() {
        scope.launch(Dispatchers.Main) {
            observerBootstrapped = false
            _pairingCode.value = ""
            _detectedOwnerPhone.value = ""
            runCatching {
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
            }
            val wv = ensureInitialized(loadIfNeeded = false)
            wv.clearCache(true)
            _linkStatus.value = WaLinkStatus.WAITING_PAIRING
            _statusDetail.value = "Session reset. Scan QR or enter phone number to get a fresh Link Code."
            wv.loadUrl(WA_WEB_URL)
        }
    }

    suspend fun triggerPhoneLinkCode(phoneWithCountryCode: String): Result<String> = withContext(Dispatchers.Main) {
        val wv = ensureInitialized(loadIfNeeded = true)
        val cleanDigits = phoneWithCountryCode.replace(Regex("[^0-9+]"), "").trim()
        if (cleanDigits.length < 7) {
            return@withContext Result.failure(IllegalArgumentException("Enter full phone number with country code, e.g. +88017XXXXXXXX"))
        }

        val clickLinkBtnJs = """
            (function() {
                const btns = Array.from(document.querySelectorAll('button, [role=button], span, a'));
                const target = btns.find(el => {
                    const t = (el.innerText || el.textContent || '').toLowerCase();
                    return t.includes('link with phone number') || t.includes('ফোন নম্বর দিয়ে লিংক');
                });
                if (target) { target.click(); return 'CLICKED_LINK_BTN'; }
                const input = document.querySelector('input[type="text"], input[aria-label*="phone" i], input[aria-label*="Type your phone" i]');
                return input ? 'ALREADY_ON_PHONE_INPUT' : 'NOT_FOUND';
            })();
        """.trimIndent()
        evalJs(wv, clickLinkBtnJs)
        delay(850)

        val phoneQuoted = JSONObject.quote(cleanDigits)
        val submitPhoneJs = """
            (function() {
                const inp = document.querySelector('input[aria-label*="Type your phone" i], input[aria-label*="phone" i], form input[type="text"], input[type="tel"]');
                if (!inp) return 'NO_PHONE_INPUT';
                inp.focus();
                const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value')?.set;
                if (setter) setter.call(inp, $phoneQuoted);
                else inp.value = $phoneQuoted;
                inp.dispatchEvent(new Event('input', {bubbles: true}));
                inp.dispatchEvent(new Event('change', {bubbles: true}));
                setTimeout(() => {
                    const nextBtn = Array.from(document.querySelectorAll('button, [role=button]'))
                        .find(b => ((b.innerText || '').trim().toLowerCase() === 'next' || (b.innerText || '').toLowerCase().includes('পরবর্তী')));
                    if (nextBtn) nextBtn.click();
                }, 250);
                return 'SUBMITTED';
            })();
        """.trimIndent()
        val submitState = evalJs(wv, submitPhoneJs)?.trim('"').orEmpty()
        if (submitState == "NO_PHONE_INPUT") {
            return@withContext Result.failure(IllegalStateException("Phone link form not visible yet. Wait a second on QR screen and retry."))
        }

        repeat(10) {
            delay(450)
            val readCodeJs = """
                (function() {
                    const codeHolder = document.querySelector('[data-link-code]');
                    if (codeHolder) {
                        const attr = codeHolder.getAttribute('data-link-code');
                        if (attr) return attr;
                    }
                    const spans = Array.from(document.querySelectorAll('[aria-details] span, [data-testid*="link"] span, div[aria-label*="code" i] span'));
                    const chars = spans.map(s => (s.innerText || '').trim()).filter(t => t.length === 1 && /^[A-Z0-9-]$/i.test(t));
                    if (chars.length >= 8) return chars.slice(0, 8).join('');
                    const bodyTxt = document.body ? document.body.innerText : '';
                    const m = bodyTxt.match(/\b([A-Z0-9]{4}[-\s]?[A-Z0-9]{4})\b/);
                    return m ? m[1] : '';
                })();
            """.trimIndent()
            val code = evalJs(wv, readCodeJs)?.trim()?.trim('"').orEmpty()
            if (code.length >= 8 && !code.equals("null", ignoreCase = true)) {
                val formatted = code.replace(" ", "").let {
                    if (!it.contains("-") && it.length == 8) "${it.substring(0, 4)}-${it.substring(4)}" else it
                }
                _pairingCode.value = formatted
                _statusDetail.value = "WhatsApp Link Code: $formatted (Enter in WhatsApp > Linked Devices > Link with phone number)"
                return@withContext Result.success(formatted)
            }
        }
        Result.success("Requested — check the Link Portal screen for your 8-character WhatsApp code.")
    }

    suspend fun syncChatListNow(): Result<Int> = withContext(Dispatchers.Main) {
        val wv = webView ?: return@withContext Result.failure(IllegalStateException("WhatsApp Web not initialized"))
        val res = evalJs(wv, "window.__waSyncChats ? window.__waSyncChats() : 0")
        val count = res?.toIntOrNull() ?: 0
        Result.success(count)
    }

    suspend fun openChatAndReadMessages(chatName: String): Result<List<String>> = withContext(Dispatchers.Main) {
        val wv = webView ?: return@withContext Result.failure(IllegalStateException("WhatsApp Web not initialized"))
        if (_linkStatus.value != WaLinkStatus.LINKED) {
            return@withContext Result.failure(IllegalStateException("WhatsApp Web is not linked yet."))
        }

        val opened = openChatInternal(wv, chatName)
        if (!opened) {
            return@withContext Result.failure(IllegalStateException("Chat '$chatName' not found in WhatsApp Web."))
        }
        delay(260)
        val lines = readActiveMessagesInternal(wv)
        Result.success(lines)
    }

    suspend fun sendMessageToChat(chatName: String, message: String): Result<String> = withContext(Dispatchers.Main) {
        val wv = webView ?: return@withContext Result.failure(IllegalStateException("WhatsApp Web is not linked yet."))
        if (_linkStatus.value != WaLinkStatus.LINKED) {
            return@withContext Result.failure(IllegalStateException("WhatsApp Web is not linked yet."))
        }

        val currentActive = _activeChatName.value.trim()
        val samePhone = WaAccessGuard.looksLikePhoneTarget(chatName) &&
            WaAccessGuard.phoneMatches(currentActive, chatName)
        if (!currentActive.equals(chatName.trim(), ignoreCase = true) && !samePhone) {
            val opened = openChatInternal(wv, chatName)
            if (!opened) {
                return@withContext Result.failure(IllegalStateException("Could not open chat '$chatName' in WhatsApp Web."))
            }
            delay(320)
        }

        val msgEsc = JSONObject.quote(message)
        val sendJs = """
            (function() {
                const box = document.querySelector('#main footer [contenteditable="true"], [data-testid="conversation-compose-box-input"]');
                if (!box) return JSON.stringify({ok:false, err:'Compose box not found'});
                box.focus();
                try {
                    const sel = window.getSelection();
                    const range = document.createRange();
                    range.selectNodeContents(box);
                    sel.removeAllRanges();
                    sel.addRange(range);
                    document.execCommand('insertText', false, $msgEsc);
                } catch(e) {
                    box.textContent = $msgEsc;
                    box.dispatchEvent(new InputEvent('input', {bubbles:true, data:$msgEsc, inputType:'insertText'}));
                }
                setTimeout(() => {
                    const sendBtn = document.querySelector('[data-testid="send"], button[aria-label="Send"], span[data-icon="send"], span[data-icon="wds-ic-send-filled"]');
                    if (sendBtn) {
                        const clickable = sendBtn.closest('button') || sendBtn;
                        clickable.click();
                    } else {
                        box.dispatchEvent(new KeyboardEvent('keydown', {key:'Enter', code:'Enter', keyCode:13, which:13, bubbles:true}));
                    }
                }, 90);
                return JSON.stringify({ok:true});
            })();
        """.trimIndent()

        val raw = evalJs(wv, sendJs).orEmpty()
        return@withContext if (raw.contains("\"ok\":true")) {
            Result.success("Sent via Linked WhatsApp Web to $chatName")
        } else {
            Result.failure(IllegalStateException("WhatsApp Web send failed: $raw"))
        }
    }

    suspend fun deleteOffendingMessage(chatName: String, domMessageId: String, bodyPreview: String): Result<String> =
        withContext(Dispatchers.Main) {
            val wv = webView ?: return@withContext Result.failure(IllegalStateException("WhatsApp Web unavailable"))
            if (!_activeChatName.value.equals(chatName, ignoreCase = true)) {
                openChatInternal(wv, chatName)
                delay(240)
            }
            val idEsc = JSONObject.quote(domMessageId)
            val prevEsc = JSONObject.quote(bodyPreview.take(40))
            val js = """
                (function() {
                    let target = null;
                    if ($idEsc) {
                        target = document.querySelector('[data-id="' + $idEsc.replace(/"/g, '') + '"]');
                    }
                    if (!target) {
                        const rows = Array.from(document.querySelectorAll('#main [data-id], #main .message-in'));
                        for (let i = rows.length - 1; i >= Math.max(0, rows.length - 12); i--) {
                            const txt = (rows[i].innerText || '').trim();
                            if ($prevEsc && txt.includes($prevEsc)) { target = rows[i]; break; }
                        }
                        if (!target && rows.length > 0) target = rows[rows.length - 1];
                    }
                    if (!target) return 'TARGET_NOT_FOUND';

                    target.dispatchEvent(new MouseEvent('mouseover', {bubbles:true}));
                    target.dispatchEvent(new MouseEvent('contextmenu', {bubbles:true, cancelable:true, view:window}));
                    setTimeout(() => {
                        const chev = target.querySelector('[data-icon="down-context"], [aria-label*="Context menu" i], [data-testid="icon-down-context"]');
                        if (chev) (chev.closest('button,[role=button]') || chev).click();
                    }, 90);
                    setTimeout(() => {
                        const menuItems = Array.from(document.querySelectorAll('[role=button], li[role=menuitem], div[role=menuitem], span'));
                        const delItem = menuItems.find(el => {
                            const t = (el.innerText || '').trim().toLowerCase();
                            return t === 'delete' || t === 'delete message' || t === 'মুছুন';
                        });
                        if (delItem) delItem.click();
                    }, 240);
                    setTimeout(() => {
                        const btns = Array.from(document.querySelectorAll('button, [role=button]'));
                        const delEveryone = btns.find(b => (b.innerText || '').trim().toLowerCase().includes('delete for everyone'));
                        const delMe = btns.find(b => (b.innerText || '').trim().toLowerCase().includes('delete for me'));
                        const finalBtn = delEveryone || delMe;
                        if (finalBtn) finalBtn.click();
                    }, 430);
                    return 'DELETE_DISPATCHED';
                })();
            """.trimIndent()
            val out = evalJs(wv, js)?.trim('"').orEmpty()
            if (out == "DELETE_DISPATCHED") Result.success("Auto-deleted offending message")
            else Result.failure(IllegalStateException(out.ifBlank { "Delete failed" }))
        }

    private suspend fun openChatInternal(wv: WebView, chatName: String): Boolean {
        val nameEsc = JSONObject.quote(chatName.trim())
        val directClickJs = """
            (function() {
                const target = $nameEsc.toLowerCase();
                const items = Array.from(document.querySelectorAll('#pane-side [role=listitem], #pane-side [data-testid="cell-frame-container"]'));
                const hit = items.find(el => (el.innerText || '').toLowerCase().includes(target));
                if (hit) {
                    hit.click();
                    return 'OPENED_VISIBLE';
                }
                const search = document.querySelector('#side [contenteditable="true"], [data-testid="chat-list-search"]');
                if (!search) return 'NOT_FOUND';
                search.focus();
                document.execCommand('selectAll', false, null);
                document.execCommand('insertText', false, $nameEsc);
                return 'SEARCHING';
            })();
        """.trimIndent()
        val first = evalJs(wv, directClickJs)?.trim('"').orEmpty()
        if (first == "OPENED_VISIBLE") return true
        if (first == "SEARCHING") {
            delay(480)
            val pickSearchJs = """
                (function() {
                    const target = $nameEsc.toLowerCase();
                    const items = Array.from(document.querySelectorAll('#pane-side [role=listitem], #pane-side [data-testid="cell-frame-container"]'));
                    const hit = items.find(el => (el.innerText || '').toLowerCase().includes(target));
                    if (hit) { hit.click(); return 'OPENED_SEARCH'; }
                    return 'NOT_FOUND';
                })();
            """.trimIndent()
            if (evalJs(wv, pickSearchJs)?.trim('"') == "OPENED_SEARCH") return true
        }

        // Direct phone number fallback via https://web.whatsapp.com/send?phone=<digits>
        val phoneForUrl = WaAccessGuard.formatPhoneForWaUrl(chatName)
        if (phoneForUrl != null) {
            observerBootstrapped = false
            wv.loadUrl("$WA_WEB_URL/send?phone=$phoneForUrl")
            repeat(18) {
                delay(400)
                val ready = evalJs(
                    wv,
                    "!!document.querySelector('#main footer [contenteditable=\"true\"], [data-testid=\"conversation-compose-box-input\"]')"
                )?.trim()
                if (ready == "true") {
                    injectObserverScript(wv)
                    return true
                }
            }
        }
        return false
    }

    private suspend fun readActiveMessagesInternal(wv: WebView): List<String> {
        val js = """
            (function() {
                const rows = Array.from(document.querySelectorAll('#main .message-in, #main .message-out')).slice(-15);
                const list = rows.map(r => {
                    const isOut = r.classList.contains('message-out');
                    const copyable = r.querySelector('.copyable-text');
                    const pre = copyable ? (copyable.getAttribute('data-pre-plain-text') || '') : '';
                    const txt = (r.querySelector('.selectable-text')?.innerText || r.innerText || '').replace(/\s+/g, ' ').trim();
                    return (isOut ? '[Me] ' : pre + ' ') + txt;
                });
                return JSON.stringify(list);
            })();
        """.trimIndent()
        val raw = evalJs(wv, js) ?: return emptyList()
        return runCatching {
            val unescaped = JSONObject("{\"v\":$raw}").getString("v")
            val arr = JSONArray(unescaped)
            List(arr.length()) { idx -> arr.optString(idx) }
        }.getOrDefault(emptyList())
    }

    private fun injectObserverScript(wv: WebView?) {
        if (wv == null || observerBootstrapped) return
        observerBootstrapped = true
        val script = """
            (function() {
                if (window.__waAndroidBotInstalled) return;
                window.__waAndroidBotInstalled = true;
                const seenMsgKeys = new Set();
                let lastOwnerPhoneSent = '';

                function detectSelfPhone() {
                    try {
                        const raw = localStorage.getItem('last-wid-md') || localStorage.getItem('last-wid') || '';
                        const clean = raw.replace(/"/g, '').split(':')[0].split('@')[0].replace(/[^0-9]/g, '');
                        if (clean.length >= 7 && clean !== lastOwnerPhoneSent) {
                            lastOwnerPhoneSent = clean;
                            window.AndroidWaBridge.onOwnerPhoneDetected(clean);
                        }
                    } catch(e) {}
                }

                function checkLoginState() {
                    const pane = document.querySelector('#pane-side');
                    if (pane) {
                        detectSelfPhone();
                        window.AndroidWaBridge.onLinkStatus('LINKED', 'WhatsApp Web Linked & Monitoring Live');
                        window.__waSyncChats();
                        return true;
                    }
                    const qr = document.querySelector('canvas[aria-label*="Scan" i], [data-testid="qrcode"]');
                    const linkPhone = Array.from(document.querySelectorAll('button, span')).some(e => (e.innerText || '').toLowerCase().includes('link with phone'));
                    if (qr || linkPhone) {
                        window.AndroidWaBridge.onLinkStatus('WAITING_PAIRING', 'Scan QR code or tap Link w/ Phone Code');
                    }
                    return false;
                }

                window.__waSyncChats = function() {
                    const rows = Array.from(document.querySelectorAll('#pane-side [role=listitem], #pane-side [data-testid="cell-frame-container"]')).slice(0, 35);
                    const chats = [];
                    rows.forEach(row => {
                        const titleEl = row.querySelector('span[title], [data-testid="cell-frame-title"] span');
                        const name = (titleEl?.getAttribute('title') || titleEl?.innerText || '').trim();
                        if (!name) return;
                        const unreadBadge = row.querySelector('[aria-label*="unread" i], [data-testid="icon-unread-count"]');
                        const unreadTxt = unreadBadge ? (unreadBadge.innerText || unreadBadge.getAttribute('aria-label') || '1') : '0';
                        const unread = parseInt((unreadTxt.match(/\d+/) || ['0'])[0], 10) || 0;
                        const hasGroupIcon = !!row.querySelector('[data-icon="default-group"], [data-icon="group"]');
                        const previewEl = row.querySelector('[data-testid="last-msg-status"]')?.parentElement || row.querySelectorAll('span[dir="ltr"], span[dir="auto"]')[1];
                        const preview = (previewEl?.innerText || '').replace(/\s+/g, ' ').trim().slice(0, 100);
                        const colonInPreview = preview.includes(': ') && !preview.startsWith('http');
                        const isGroup = hasGroupIcon || colonInPreview;
                        chats.push({ name, unread, isGroup, preview });
                        if (unread > 0) {
                            window.AndroidWaBridge.onUnreadDetected(name, isGroup);
                        }
                    });
                    window.AndroidWaBridge.onChatsSynced(JSON.stringify(chats));
                    return chats.length;
                };

                function extractPhoneFromDomId(domId) {
                    if (!domId) return '';
                    const m = domId.match(/_(\d{7,15})@(?:c\.us|s\.whatsapp\.net|lid)/i);
                    return m ? m[1] : '';
                }

                function scanActiveMessages() {
                    const headerTitle = (document.querySelector('#main header span[title]')?.getAttribute('title') || document.querySelector('#main header span[dir="auto"]')?.innerText || '').trim();
                    if (!headerTitle) return;
                    const isGroup = !!document.querySelector('#main header [data-icon="default-group"]') || !!document.querySelector('#main .message-in [data-testid="author"]');
                    const inMsgs = Array.from(document.querySelectorAll('#main .message-in')).slice(-4);
                    const outCmdMsgs = Array.from(document.querySelectorAll('#main .message-out')).slice(-2);
                    const msgs = [...inMsgs, ...outCmdMsgs];

                    const contextRows = Array.from(document.querySelectorAll('#main .message-in, #main .message-out')).slice(-10).map(r => {
                        const out = r.classList.contains('message-out');
                        const t = (r.querySelector('.selectable-text')?.innerText || '').replace(/\s+/g, ' ').trim();
                        return (out ? 'Me: ' : 'Them: ') + t;
                    }).filter(x => x.length > 5);

                    msgs.forEach(m => {
                        const isOut = m.classList.contains('message-out');
                        const rowWrap = m.closest('[data-id]') || m;
                        const domId = rowWrap.getAttribute('data-id') || '';
                        const copyable = m.querySelector('.copyable-text');
                        const prePlain = copyable ? (copyable.getAttribute('data-pre-plain-text') || '') : '';
                        let sender = isOut ? 'You' : headerTitle;
                        if (!isOut) {
                            const authorEl = m.querySelector('[data-testid="author"], span[aria-label$=":"]');
                            if (authorEl) sender = (authorEl.innerText || '').trim();
                            else if (prePlain.includes('] ')) sender = prePlain.split('] ')[1].replace(/:$/, '').trim() || headerTitle;
                        }

                        const isSticker = !!m.querySelector('[data-testid="sticker"], img[alt*="sticker" i], canvas[aria-label*="sticker" i]');
                        const isForwarded = (m.innerText || '').toLowerCase().includes('forwarded');
                        const linkEl = m.querySelector('a[href]');
                        const textNode = m.querySelector('.selectable-text');
                        let body = (textNode ? textNode.innerText : (linkEl ? linkEl.href : '')).trim();
                        if (!body && isSticker && !isOut) body = '[Sticker]';
                        if (!body) return;

                        // Outgoing messages are ONLY forwarded when Owner explicitly types a bot command (.ai, .cmd, .antilink, etc.)
                        if (isOut && !/^[.!/][a-zA-Z\u0980-\u09FF]/.test(body)) return;

                        const key = headerTitle + '|' + (domId || (isOut ? 'out' : 'in')) + '|' + body.slice(0, 60);
                        if (seenMsgKeys.has(key)) return;
                        seenMsgKeys.add(key);
                        if (seenMsgKeys.size > 400) {
                            const first = seenMsgKeys.values().next().value;
                            seenMsgKeys.delete(first);
                        }

                        if (!window.__waActiveChatPrimed[headerTitle]) return;

                        window.AndroidWaBridge.onIncomingDomMessage(JSON.stringify({
                            chatName: headerTitle,
                            senderName: sender,
                            senderPhone: extractPhoneFromDomId(domId),
                            isGroup: isGroup,
                            isFromMe: isOut,
                            body: body,
                            hasLink: !!linkEl,
                            isSticker: isSticker,
                            isForwarded: isForwarded,
                            domId: domId,
                            context: contextRows
                        }));
                    });
                    window.__waActiveChatPrimed[headerTitle] = true;
                }

                window.__waActiveChatPrimed = {};
                const obs = new MutationObserver(() => {
                    checkLoginState();
                    scanActiveMessages();
                });
                obs.observe(document.body, { childList: true, subtree: true });
                setInterval(() => {
                    checkLoginState();
                    scanActiveMessages();
                }, 1500);
                checkLoginState();
            })();
        """.trimIndent()
        wv.evaluateJavascript(script, null)
    }

    private suspend fun evalJs(wv: WebView, js: String): String? {
        return withTimeoutOrNull(3_000L) {
            suspendCancellableCoroutine { cont ->
                wv.evaluateJavascript(js) { res ->
                    if (cont.isActive) cont.resume(res)
                }
            }
        }
    }

    private inner class JsWaReceiver {
        @JavascriptInterface
        fun onOwnerPhoneDetected(phoneDigits: String) {
            val clean = WaAccessGuard.extractPhoneDigits(phoneDigits)
            if (clean.length >= 7) {
                _detectedOwnerPhone.value = clean
                listener?.onOwnerPhoneDetected(clean)
            }
        }

        @JavascriptInterface
        fun onLinkStatus(statusStr: String, detail: String) {
            val st = runCatching { WaLinkStatus.valueOf(statusStr) }.getOrDefault(WaLinkStatus.LOADING)
            _linkStatus.value = st
            _statusDetail.value = detail
            listener?.onStatusChanged(st, detail)
        }

        @JavascriptInterface
        fun onChatsSynced(jsonArrStr: String) {
            runCatching {
                val arr = JSONArray(jsonArrStr)
                val list = buildList {
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        val name = obj.optString("name").trim()
                        if (name.isBlank()) continue
                        val isGroup = obj.optBoolean("isGroup", false)
                        add(
                            WaSyncedChat(
                                chatKey = WaChatKeyHelper.normalize(name, isGroup),
                                displayName = name,
                                isGroup = isGroup,
                                unreadCount = obj.optInt("unread", 0),
                                lastPreview = obj.optString("preview")
                            )
                        )
                    }
                }
                listener?.onChatsSynced(list)
            }
        }

        @JavascriptInterface
        fun onUnreadDetected(chatName: String, isGroup: Boolean) {
            listener?.onUnreadChatDetected(chatName, isGroup)
        }

        @JavascriptInterface
        fun onIncomingDomMessage(jsonStr: String) {
            runCatching {
                val obj = JSONObject(jsonStr)
                val chatName = obj.optString("chatName").trim()
                val body = obj.optString("body").trim()
                if (chatName.isBlank() || body.isBlank()) return
                _activeChatName.value = chatName
                val ctxArr = obj.optJSONArray("context")
                val ctx = if (ctxArr != null) List(ctxArr.length()) { ctxArr.optString(it) } else emptyList()
                listener?.onIncomingMessage(
                    WaIncomingMessage(
                        chatName = chatName,
                        senderName = obj.optString("senderName", chatName),
                        isGroup = obj.optBoolean("isGroup", false),
                        body = body,
                        hasLink = obj.optBoolean("hasLink", false) || WaModerationDetector.containsLink(body),
                        isSticker = obj.optBoolean("isSticker", false) || WaModerationDetector.looksLikeStickerText(body),
                        isForwarded = obj.optBoolean("isForwarded", false),
                        isFromMe = obj.optBoolean("isFromMe", false),
                        senderPhoneHint = obj.optString("senderPhone", ""),
                        domMessageId = obj.optString("domId", ""),
                        sourceChannel = "WA_WEB",
                        recentContextLines = ctx
                    )
                )
            }
        }
    }

    companion object {
        const val WA_WEB_URL = "https://web.whatsapp.com"
        private const val DESKTOP_CHROME_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36"
    }
}
