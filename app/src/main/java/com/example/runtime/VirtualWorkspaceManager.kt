package com.example.runtime

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class WorkspaceFileItem(
    val name: String,
    val relativePath: String,
    val absolutePath: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val lastModified: Long,
    val extension: String
)

class VirtualWorkspaceManager(baseFilesDir: File) {

    constructor(context: Context) : this(context.filesDir)

    val rootDir: File = File(baseFilesDir, "virtual_env")
    val homeDir: File = File(rootDir, "home")
    val workspaceDir: File = File(rootDir, "workspace")
    val projectsDir: File = File(rootDir, "projects")
    val scriptsDir: File = File(rootDir, "scripts")
    val downloadsDir: File = File(rootDir, "downloads")
    val uploadsDir: File = File(rootDir, "uploads")
    val skillsDir: File = File(rootDir, "skills")
    val tmpDir: File = File(rootDir, "tmp")
    val memoryDir: File = File(rootDir, "memory")

    init {
        ensureStructure()
    }

    fun ensureStructure() {
        listOf(rootDir, homeDir, workspaceDir, projectsDir, scriptsDir, downloadsDir, uploadsDir, skillsDir, tmpDir, memoryDir)
            .forEach { if (!it.exists()) it.mkdirs() }
        seedStarterFilesIfNeeded()
    }

    private fun seedStarterFilesIfNeeded() {
        val helloPy = File(scriptsDir, "hello_agent.py")
        if (!helloPy.exists()) {
            helloPy.writeText(
                """
                import math
                import json
                from datetime import datetime

                print("Personal AI Computer - Local Python Runtime")
                cores = 8
                score = round(math.sqrt(2026) * 3.14159, 2)
                report = {
                    "status": "ready",
                    "engine": "on-device",
                    "score": score,
                    "timestamp": str(datetime.now())
                }
                print(json.dumps(report, indent=2))
                """.trimIndent()
            )
        }

        val sysAuditSh = File(scriptsDir, "sys_audit.sh")
        if (!sysAuditSh.exists()) {
            sysAuditSh.writeText(
                """
                #!/system/bin/sh
                echo "=== Virtual Linux Environment Audit ==="
                uname -a
                echo "PWD: $(pwd)"
                echo "Files in workspace:"
                ls -la ../workspace
                """.trimIndent()
            )
        }

        val nodeDemo = File(scriptsDir, "analyze.js")
        if (!nodeDemo.exists()) {
            nodeDemo.writeText(
                """
                const tasks = ["Research AI tools", "Parse workspace", "Verify outputs"];
                console.log("JS V8 Runtime Active - Tasks:", tasks.length);
                const summary = tasks.map((t, i) => `${'$'}{i + 1}. ${'$'}{t}`).join("\n");
                console.log(summary);
                """.trimIndent()
            )
        }

        val indexHtml = File(projectsDir, "index.html")
        if (!indexHtml.exists()) {
            indexHtml.writeText(
                """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                  <meta charset="UTF-8" />
                  <meta name="viewport" content="width=device-width, initial-scale=1.0" />
                  <title>Personal AI Computer — Local Dev Server</title>
                  <style>
                    body { font-family: system-ui, sans-serif; background: #090d16; color: #f1f5f9; padding: 24px; }
                    .card { background: #101624; border: 1px solid #28354d; border-radius: 16px; padding: 20px; }
                    h1 { color: #22d3ee; margin-top: 0; }
                    code { color: #10b981; }
                  </style>
                </head>
                <body>
                  <div class="card">
                    <h1>Local Dev Server Online</h1>
                    <p>Served directly from <code>virtual_env/projects/index.html</code> on this Android device.</p>
                  </div>
                </body>
                </html>
                """.trimIndent()
            )
        }

        val readmeTxt = File(workspaceDir, "README.txt")
        if (!readmeTxt.exists()) {
            readmeTxt.writeText(
                "Personal AI Computer Local Workspace\n" +
                    "All autonomous agent scripts, reports, downloads, and projects live safely inside this sandboxed FHS root.\n"
            )
        }
    }

    fun resolveSafe(relativePath: String, base: File = rootDir): File {
        val cleaned = relativePath.trim().trimStart('/')
        val candidate = if (cleaned.isEmpty() || cleaned == ".") base else File(base, cleaned)
        val rootCanonical = rootDir.canonicalFile
        val targetCanonical = candidate.canonicalFile
        val rootPath = rootCanonical.path
        val targetPath = targetCanonical.path
        require(targetPath == rootPath || targetPath.startsWith("$rootPath${File.separator}")) {
            "Security violation: Path escapes sandboxed virtual_env ($relativePath)"
        }
        return targetCanonical
    }

    fun relativePathOf(file: File): String {
        val rootPath = rootDir.canonicalPath
        val filePath = file.canonicalPath
        return if (filePath == rootPath) "" else filePath.removePrefix("$rootPath${File.separator}")
    }

    fun listDirectory(relativePath: String = ""): List<WorkspaceFileItem> {
        val dir = resolveSafe(relativePath)
        if (!dir.exists() || !dir.isDirectory) return emptyList()
        return (dir.listFiles() ?: emptyArray())
            .sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
            .map { file ->
                WorkspaceFileItem(
                    name = file.name,
                    relativePath = relativePathOf(file),
                    absolutePath = file.absolutePath,
                    isDirectory = file.isDirectory,
                    sizeBytes = if (file.isFile) file.length() else 0L,
                    lastModified = file.lastModified(),
                    extension = file.extension.lowercase()
                )
            }
    }

    fun readTextFile(relativePath: String): Result<String> = runCatching {
        val file = resolveSafe(relativePath)
        require(file.exists() && file.isFile) { "File not found: $relativePath" }
        require(file.length() <= 2_000_000) { "File too large to open in editor (>2MB)" }
        file.readText()
    }

    fun writeTextFile(relativePath: String, content: String): Result<File> = runCatching {
        val file = resolveSafe(relativePath)
        file.parentFile?.let { if (!it.exists()) it.mkdirs() }
        file.writeText(content)
        file
    }

    fun createDirectory(relativePath: String): Result<File> = runCatching {
        val dir = resolveSafe(relativePath)
        if (!dir.exists()) dir.mkdirs()
        dir
    }

    fun renameFile(relativePath: String, newName: String): Result<File> = runCatching {
        require(!newName.contains("/") && !newName.contains("..")) { "Invalid file name" }
        val src = resolveSafe(relativePath)
        require(src.exists()) { "Source does not exist" }
        val dst = File(src.parentFile, newName.trim())
        resolveSafe(relativePathOf(dst))
        require(!dst.exists()) { "Target name already exists" }
        check(src.renameTo(dst)) { "Rename failed" }
        dst
    }

    fun deleteFile(relativePath: String): Result<Unit> = runCatching {
        val target = resolveSafe(relativePath)
        require(target != rootDir.canonicalFile) { "Cannot delete root virtual_env" }
        if (target.isDirectory) {
            check(target.deleteRecursively()) { "Failed to delete directory" }
        } else {
            check(target.delete()) { "Failed to delete file" }
        }
    }

    fun searchFiles(query: String, maxResults: Int = 50): List<WorkspaceFileItem> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        return rootDir.walkTopDown()
            .filter { it != rootDir && it.name.lowercase().contains(q) }
            .take(maxResults)
            .map { file ->
                WorkspaceFileItem(
                    name = file.name,
                    relativePath = relativePathOf(file),
                    absolutePath = file.absolutePath,
                    isDirectory = file.isDirectory,
                    sizeBytes = if (file.isFile) file.length() else 0L,
                    lastModified = file.lastModified(),
                    extension = file.extension.lowercase()
                )
            }
            .toList()
    }

    fun saveUploadedFile(originalName: String, bytes: ByteArray): Result<File> = runCatching {
        val safeName = originalName.trim()
            .replace(Regex("[^a-zA-Z0-9._-]"), "_")
            .ifBlank { "upload_${System.currentTimeMillis()}.bin" }
        val target = resolveSafe("uploads/${System.currentTimeMillis() % 100000}_$safeName")
        target.parentFile?.let { if (!it.exists()) it.mkdirs() }
        target.writeBytes(bytes)
        target
    }

    fun formatTimestamp(millis: Long): String {
        return SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()).format(Date(millis))
    }
}
