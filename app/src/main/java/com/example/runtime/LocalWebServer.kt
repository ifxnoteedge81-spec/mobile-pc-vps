package com.example.runtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

data class WebServerState(
    val running: Boolean = false,
    val port: Int = 8080,
    val url: String = "http://127.0.0.1:8080",
    val requestLog: List<String> = emptyList()
)

class LocalWebServer(
    private val workspace: VirtualWorkspaceManager,
    private val scope: CoroutineScope
) {
    private val _state = MutableStateFlow(WebServerState())
    val state: StateFlow<WebServerState> = _state.asStateFlow()

    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null

    fun urlFor(relativePath: String): String {
        val base = _state.value.url.trimEnd('/')
        val rel = relativePath.trimStart('/')
        return if (rel.isEmpty()) base else "$base/$rel"
    }

    fun start(port: Int = 8080): WebServerState {
        if (_state.value.running) return _state.value
        return runCatching {
            val socket = ServerSocket(port, 16, InetAddress.getByName("127.0.0.1"))
            serverSocket = socket
            _state.value = WebServerState(
                running = true,
                port = port,
                url = "http://127.0.0.1:$port",
                requestLog = listOf("Started HTTP server serving ${workspace.projectsDir.absolutePath}")
            )
            acceptJob = scope.launch(Dispatchers.IO) {
                while (isActive && !socket.isClosed) {
                    val client = runCatching { socket.accept() }.getOrNull() ?: break
                    launch(Dispatchers.IO) { handleClient(client) }
                }
            }
            _state.value
        }.getOrElse { err ->
            _state.value = _state.value.copy(
                running = false,
                requestLog = listOf("Failed to bind port $port: ${err.message}") + _state.value.requestLog
            )
            _state.value
        }
    }

    fun stop() {
        acceptJob?.cancel()
        runCatching { serverSocket?.close() }
        serverSocket = null
        _state.value = _state.value.copy(
            running = false,
            requestLog = (listOf("Stopped local web server") + _state.value.requestLog).take(30)
        )
    }

    private fun handleClient(client: Socket) {
        client.use { sock ->
            val reader = sock.getInputStream().bufferedReader()
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            val rawEncoded = parts.getOrNull(1)?.substringBefore("?") ?: "/"
            val rawPath = runCatching {
                java.net.URLDecoder.decode(rawEncoded, "UTF-8")
            }.getOrDefault(rawEncoded)
            val relPath = if (rawPath == "/" || rawPath.isBlank()) "index.html" else rawPath.trimStart('/')

            val target = runCatching {
                val candidate = File(workspace.projectsDir, relPath).canonicalFile
                val base = workspace.projectsDir.canonicalFile
                if (candidate.path == base.path || candidate.path.startsWith("${base.path}${File.separator}")) candidate else null
            }.getOrNull()

            val out = sock.getOutputStream()
            if (target != null && target.exists() && target.isFile) {
                val bytes = target.readBytes()
                val mime = mimeTypeForExtension(target.extension)
                val header = "HTTP/1.1 200 OK\r\nContent-Type: $mime\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                out.write(header.toByteArray())
                out.write(bytes)
                logRequest("200 GET /$relPath (${bytes.size}B)")
            } else {
                val body = "<h1>404 Not Found</h1><p>/$relPath not found in virtual_env/projects</p>".toByteArray()
                val header = "HTTP/1.1 404 Not Found\r\nContent-Type: text/html\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                out.write(header.toByteArray())
                out.write(body)
                logRequest("404 GET /$relPath")
            }
            out.flush()
        }
    }

    private fun logRequest(entry: String) {
        _state.value = _state.value.copy(requestLog = (listOf(entry) + _state.value.requestLog).take(30))
    }

    companion object {
        fun mimeTypeForExtension(ext: String): String = when (ext.lowercase()) {
            "html", "htm" -> "text/html; charset=utf-8"
            "css" -> "text/css; charset=utf-8"
            "js", "mjs" -> "application/javascript; charset=utf-8"
            "json" -> "application/json; charset=utf-8"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "svg" -> "image/svg+xml"
            "ico" -> "image/x-icon"
            "pdf" -> "application/pdf"
            "dxf" -> "application/dxf"
            "stl" -> "model/stl"
            "wasm" -> "application/wasm"
            "csv" -> "text/csv; charset=utf-8"
            "md" -> "text/markdown; charset=utf-8"
            "xml" -> "application/xml; charset=utf-8"
            else -> "text/plain; charset=utf-8"
        }
    }
}
