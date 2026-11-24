package com.example.runtime

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * Executes real ES2023 JavaScript (and lightweight TypeScript after type-stripping)
 * using Android's built-in V8 WebView JavaScript engine on-device without DCL.
 */
class LocalJsEngine(private val context: Context) {

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun executeJs(code: String, isTypeScript: Boolean = false): ScriptExecutionResult =
        withContext(Dispatchers.Main) {
            val start = System.currentTimeMillis()
            val preparedCode = if (isTypeScript) stripBasicTypeScript(code) else code
            val wrapped = """
                (function() {
                    const __logs = [];
                    const console = {
                        log: (...args) => __logs.push(args.map(a => typeof a === 'object' ? JSON.stringify(a) : String(a)).join(' ')),
                        warn: (...args) => __logs.push('[WARN] ' + args.join(' ')),
                        error: (...args) => __logs.push('[ERR] ' + args.join(' '))
                    };
                    try {
                        const __res = (function() {
                            $preparedCode
                        })();
                        return JSON.stringify({
                            ok: true,
                            logs: __logs.join('\n'),
                            result: __res === undefined ? '' : (typeof __res === 'object' ? JSON.stringify(__res) : String(__res))
                        });
                    } catch (e) {
                        return JSON.stringify({
                            ok: false,
                            logs: __logs.join('\n'),
                            error: e && e.stack ? e.stack : String(e)
                        });
                    }
                })();
            """.trimIndent()

            val webView = WebView(context).apply {
                settings.javaScriptEnabled = true
            }
            val rawResult = withTimeoutOrNull(8_000L) {
                suspendCancellableCoroutine<String?> { cont ->
                    webView.evaluateJavascript(wrapped) { res ->
                        if (cont.isActive) cont.resume(res)
                    }
                }
            }
            webView.destroy()

            val duration = System.currentTimeMillis() - start
            if (rawResult == null || rawResult == "null") {
                return@withContext ScriptExecutionResult(
                    success = false,
                    stdout = "",
                    stderr = "JavaScript execution timed out or returned null.",
                    engineLabel = "Android V8 WebView Engine",
                    durationMs = duration
                )
            }

            return@withContext runCatching {
                val unescaped = JSONObject("{\"v\":$rawResult}").getString("v")
                val json = JSONObject(unescaped)
                val ok = json.optBoolean("ok", false)
                val logs = json.optString("logs", "")
                val res = json.optString("result", "")
                val err = json.optString("error", "")
                val combinedOut = listOf(logs, res).filter { it.isNotBlank() }.joinToString("\n")
                ScriptExecutionResult(
                    success = ok,
                    stdout = combinedOut,
                    stderr = err,
                    engineLabel = if (isTypeScript) "Android V8 + TS Transpiler" else "Android V8 WebView Engine",
                    durationMs = duration
                )
            }.getOrElse { ex ->
                ScriptExecutionResult(
                    success = false,
                    stdout = "",
                    stderr = "Parse error: ${ex.message}",
                    engineLabel = "Android V8 WebView Engine",
                    durationMs = duration
                )
            }
        }

    private fun stripBasicTypeScript(ts: String): String {
        return ts
            .replace(Regex("""interface\s+\w+\s*\{[^}]*\}""", RegexOption.MULTILINE), "")
            .replace(Regex("""type\s+\w+\s*=\s*[^;\n]+[;\n]"""), "")
            .replace(Regex(""":\s*(string|number|boolean|any|void|unknown|never)(\[\])?"""), "")
            .replace(Regex("""\s+as\s+(string|number|boolean|any|unknown)"""), "")
    }
}
