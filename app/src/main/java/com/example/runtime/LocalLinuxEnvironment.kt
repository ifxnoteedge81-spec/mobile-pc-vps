package com.example.runtime

import android.os.Build
import com.example.security.CommandSecurityGuard
import com.example.security.CommandValidationResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

data class TerminalLine(
    val id: Long = MonotonicIdGenerator.nextId(),
    val text: String,
    val isCommand: Boolean = false,
    val isError: Boolean = false
)

data class ShellExecutionResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val workingDirRelative: String,
    val blockedReason: String? = null,
    val requiresConfirmationReason: String? = null
)

class LocalLinuxEnvironment(
    private val workspace: VirtualWorkspaceManager,
    private val pythonEngine: LocalPythonEngine,
    private val jsEngine: LocalJsEngine,
    private val webServer: LocalWebServer
) {
    private var currentDir: File = workspace.rootDir
    private val envVars = mutableMapOf(
        "HOME" to workspace.homeDir.absolutePath,
        "TMPDIR" to workspace.tmpDir.absolutePath,
        "VIRTUAL_ROOT" to workspace.rootDir.absolutePath,
        "TERM" to "xterm-256color",
        "LANG" to "en_US.UTF-8"
    )

    private val _terminalLines = MutableStateFlow(
        listOf(
            TerminalLine(text = "Personal AI Computer • Sandboxed Linux Userspace"),
            TerminalLine(text = "Kernel: ${System.getProperty("os.name")} ${System.getProperty("os.version")} (${Build.SUPPORTED_ABIS.firstOrNull()})"),
            TerminalLine(text = "Sandbox Root: virtual_env/ | Shell: /system/bin/sh + PyLite + V8 JS"),
            TerminalLine(text = "Type 'help' for commands, 'ls', 'python3 scripts/hello_agent.py', or 'serve start'.")
        )
    )
    val terminalLines: StateFlow<List<TerminalLine>> = _terminalLines.asStateFlow()

    private val _currentRelativeDir = MutableStateFlow(".")
    val currentRelativeDir: StateFlow<String> = _currentRelativeDir.asStateFlow()

    suspend fun runInteractiveCommand(rawCommand: String, confirmedLevel2: Boolean = false): ShellExecutionResult {
        val cmd = rawCommand.trim()
        if (cmd.isEmpty()) return ShellExecutionResult(0, "", "", currentRelativePath())

        appendLine("ai@local:~/${currentRelativePath()}$ $cmd", isCommand = true)

        val result = executeCommand(cmd, confirmedLevel2)
        if (result.blockedReason != null) {
            appendLine("[BLOCKED] ${result.blockedReason}", isError = true)
        } else if (result.requiresConfirmationReason != null) {
            appendLine("[CONFIRMATION REQUIRED] ${result.requiresConfirmationReason}", isError = true)
        } else {
            if (result.stdout.isNotBlank()) appendLine(result.stdout)
            if (result.stderr.isNotBlank()) appendLine(result.stderr, isError = true)
        }
        return result
    }

    suspend fun executeCommand(rawCommand: String, confirmedLevel2: Boolean = false): ShellExecutionResult =
        withContext(Dispatchers.IO) {
            val cmd = rawCommand.trim()
            val validation = CommandSecurityGuard.validateShellCommand(cmd)
            if (validation is CommandValidationResult.Blocked) {
                return@withContext ShellExecutionResult(126, "", validation.reason, currentRelativePath(), blockedReason = validation.reason)
            }
            if (validation is CommandValidationResult.RequiresConfirmation && !confirmedLevel2) {
                return@withContext ShellExecutionResult(
                    125,
                    "",
                    validation.reason,
                    currentRelativePath(),
                    requiresConfirmationReason = validation.reason
                )
            }

            // Built-in interactive commands
            when {
                cmd == "clear" -> {
                    _terminalLines.value = emptyList()
                    return@withContext ShellExecutionResult(0, "", "", currentRelativePath())
                }
                cmd == "help" -> {
                    val helpText = """
                        Built-in Workspace Commands:
                          pwd, cd <dir>, ls, cat <file>, mkdir <dir>, touch <file>, grep, find, uname -a
                          python3 <script.py> | python -c "<code>"  (On-device Python runtime)
                          node <script.js>    | js -e "<code>"      (Android V8 JS runtime)
                          serve start | serve stop                  (Local HTTP dev server :8080)
                          sysinfo                                   (Kernel, ABI, Toolchain status)
                          export KEY=VAL                            (Set session environment var)
                          clear                                     (Clear terminal output)
                    """.trimIndent()
                    return@withContext ShellExecutionResult(0, helpText, "", currentRelativePath())
                }
                cmd == "pwd" -> {
                    return@withContext ShellExecutionResult(0, "/virtual_env/${currentRelativePath()}", "", currentRelativePath())
                }
                cmd == "sysinfo" -> {
                    val nativePy = pythonEngine.findNativePythonBinary() ?: "Not installed (using PyLite Python 3 engine)"
                    val info = """
                        OS: Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})
                        Kernel: ${System.getProperty("os.version")} | Arch: ${Build.SUPPORTED_ABIS.joinToString()}
                        Shell: /system/bin/sh (POSIX Toybox/mksh)
                        Python: $nativePy
                        JS Engine: Android WebView V8
                        C/C++ Compiler: ${if (File("/system/bin/clang").exists()) "clang" else "Unavailable on stock unrooted Android (requires NDK/PRoot sysroot)"}
                        Web Server: ${if (webServer.state.value.running) webServer.state.value.url else "Stopped"}
                    """.trimIndent()
                    return@withContext ShellExecutionResult(0, info, "", currentRelativePath())
                }
                cmd == "serve start" -> {
                    val state = webServer.start(8080)
                    return@withContext ShellExecutionResult(0, "Local server listening at ${state.url}", "", currentRelativePath())
                }
                cmd == "serve stop" -> {
                    webServer.stop()
                    return@withContext ShellExecutionResult(0, "Local server stopped.", "", currentRelativePath())
                }
                cmd == "cd" || cmd.startsWith("cd ") -> {
                    val arg = cmd.removePrefix("cd").trim()
                    val target = when {
                        arg.isEmpty() || arg == "~" -> workspace.homeDir
                        arg.startsWith("~/") -> workspace.resolveSafe(arg.removePrefix("~/"), workspace.homeDir)
                        else -> workspace.resolveSafe(arg, currentDir)
                    }
                    return@withContext if (target.exists() && target.isDirectory) {
                        currentDir = target
                        _currentRelativeDir.value = currentRelativePath()
                        ShellExecutionResult(0, "", "", currentRelativePath())
                    } else {
                        ShellExecutionResult(1, "", "cd: $arg: No such directory in virtual_env", currentRelativePath())
                    }
                }
                cmd.startsWith("export ") -> {
                    val assign = cmd.removePrefix("export ").trim()
                    val k = assign.substringBefore("=").trim()
                    val v = assign.substringAfter("=", "").trim()
                    if (k.isNotEmpty()) envVars[k] = v
                    return@withContext ShellExecutionResult(0, "", "", currentRelativePath())
                }
                cmd.startsWith("python3 ") || cmd.startsWith("python ") -> {
                    val arg = cmd.substringAfter(" ").trim()
                    val code = if (arg.startsWith("-c ")) {
                        stripOuterQuotes(arg.removePrefix("-c ").trim())
                    } else {
                        val file = resolveWithWorkspaceFallback(arg)
                        if (file == null || !file.exists() || !file.isFile) {
                            return@withContext ShellExecutionResult(2, "", "python3: can't open file '$arg'", currentRelativePath())
                        }
                        file.readText()
                    }
                    val res = pythonEngine.executeScript(code, currentDir)
                    return@withContext ShellExecutionResult(
                        if (res.success) 0 else 1,
                        res.stdout,
                        res.stderr,
                        currentRelativePath()
                    )
                }
                cmd.startsWith("node ") || cmd.startsWith("js ") -> {
                    val arg = cmd.substringAfter(" ").trim()
                    val isTs = arg.endsWith(".ts")
                    val code = if (arg.startsWith("-e ")) {
                        stripOuterQuotes(arg.removePrefix("-e ").trim())
                    } else {
                        val file = resolveWithWorkspaceFallback(arg)
                        if (file == null || !file.exists() || !file.isFile) {
                            return@withContext ShellExecutionResult(2, "", "node: module not found '$arg'", currentRelativePath())
                        }
                        file.readText()
                    }
                    val res = jsEngine.executeJs(code, isTypeScript = isTs)
                    return@withContext ShellExecutionResult(
                        if (res.success) 0 else 1,
                        res.stdout,
                        res.stderr,
                        currentRelativePath()
                    )
                }
            }

            // Real POSIX /system/bin/sh execution confined to currentDir
            return@withContext runCatching {
                val pb = ProcessBuilder("/system/bin/sh", "-c", cmd)
                    .directory(currentDir)
                pb.environment().putAll(envVars)
                pb.environment()["PWD"] = currentDir.absolutePath

                val process = pb.start()
                val finished = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    process.waitFor(12, TimeUnit.SECONDS)
                } else {
                    val waiter = Thread { runCatching { process.waitFor() } }.apply { start() }
                    waiter.join(12_000L)
                    !waiter.isAlive
                }
                if (!finished) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        process.destroyForcibly()
                    } else {
                        process.destroy()
                    }
                    return@runCatching ShellExecutionResult(
                        124,
                        "",
                        "Command timed out after 12s limit.",
                        currentRelativePath()
                    )
                }
                val stdout = process.inputStream.bufferedReader().readText().take(32_000).trimEnd()
                val stderr = process.errorStream.bufferedReader().readText().take(16_000).trimEnd()
                ShellExecutionResult(process.exitValue(), stdout, stderr, currentRelativePath())
            }.getOrElse { err ->
                ShellExecutionResult(1, "", "Shell error: ${err.message}", currentRelativePath())
            }
        }

    fun appendLine(text: String, isCommand: Boolean = false, isError: Boolean = false) {
        _terminalLines.update { current ->
            (current + TerminalLine(text = text, isCommand = isCommand, isError = isError)).takeLast(300)
        }
    }

    private fun stripOuterQuotes(s: String): String {
        val t = s.trim()
        return if (t.length >= 2 && ((t.startsWith("\"") && t.endsWith("\"")) || (t.startsWith("'") && t.endsWith("'")))) {
            t.substring(1, t.length - 1)
        } else t
    }

    private fun resolveWithWorkspaceFallback(arg: String): File? {
        val primary = runCatching { workspace.resolveSafe(arg, currentDir) }.getOrNull()
        if (primary != null && primary.exists()) return primary
        return runCatching { workspace.resolveSafe(arg, workspace.rootDir) }.getOrNull()
    }

    private fun currentRelativePath(): String {
        val rel = workspace.relativePathOf(currentDir)
        return rel.ifEmpty { "." }
    }
}
