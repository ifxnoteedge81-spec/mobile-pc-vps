package com.example.agent

import com.example.runtime.VirtualWorkspaceManager
import com.example.security.NetworkAuditLogger
import com.example.storage.AgentRepository
import com.example.storage.ImportedSkillEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

data class SkillMetadataParsed(
    val title: String,
    val description: String,
    val ecosystem: String,
    val toolMappingSummary: String,
    val tocSummary: String,
    val wordCount: Int,
    val lineCount: Int
)

class UniversalSkillManager(
    private val workspace: VirtualWorkspaceManager,
    private val repository: SkillStore,
    private val auditLogger: NetworkAuditLogger
) {
    interface SkillStore {
        suspend fun getAllImportedSkillsSnapshot(): List<ImportedSkillEntity>
        suspend fun getImportedSkillBySlug(slug: String): ImportedSkillEntity?
        suspend fun upsertImportedSkill(skill: ImportedSkillEntity)
        suspend fun deleteImportedSkill(slug: String)
    }
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun looksLikeSkillFile(filename: String, textPreview: String?): Boolean {
        val lowerName = filename.lowercase()
        if (lowerName == "skill.md" ||
            lowerName.endsWith(".skill") ||
            lowerName.contains("skill") ||
            lowerName == "openclaw.json" ||
            lowerName == "tools.md" ||
            lowerName == "agents.md" ||
            lowerName == "soul.md" ||
            lowerName.endsWith(".zip")
        ) return true

        val lowerBody = textPreview?.lowercase().orEmpty()
        return (lowerBody.startsWith("---") && (lowerBody.contains("name:") || lowerBody.contains("description:"))) ||
            lowerBody.contains("allowed-tools:") ||
            lowerBody.contains("openclaw") ||
            lowerBody.contains("anthropic") ||
            lowerBody.contains("mcpservers") ||
            lowerBody.contains("## instructions") ||
            lowerBody.contains("## workflow")
    }

    suspend fun importAuto(
        source: String,
        titleHint: String = ""
    ): Result<ImportedSkillEntity> = withContext(Dispatchers.IO) {
        val trimmed = source.trim()
        require(trimmed.isNotBlank()) { "Skill source cannot be empty." }

        when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") ->
                importFromUrl(trimmed, titleHint)
            trimmed.length < 300 && !trimmed.contains('\n') -> {
                val candidate = runCatching { workspace.resolveSafe(trimmed) }.getOrNull()
                if (candidate != null && candidate.exists()) {
                    if (candidate.isDirectory) {
                        importFromDirectory(candidate, titleHint, trimmed)
                    } else {
                        importFromLocalFile(candidate, candidate.name, titleHint, trimmed)
                    }
                } else {
                    importFromRawText(trimmed, titleHint, "Direct Text")
                }
            }
            else -> importFromRawText(trimmed, titleHint, "Direct Text / Prompt")
        }
    }

    suspend fun importFromUploadedPath(
        relativePath: String,
        displayName: String = "",
        titleHint: String = ""
    ): Result<ImportedSkillEntity> = withContext(Dispatchers.IO) {
        runCatching {
            val file = workspace.resolveSafe(relativePath)
            require(file.exists() && file.isFile) { "Uploaded skill file not found: $relativePath" }
            importFromLocalFile(file, displayName.ifBlank { file.name }, titleHint, relativePath).getOrThrow()
        }
    }

    suspend fun importFromRawText(
        rawText: String,
        titleHint: String = "",
        sourceOrigin: String = "Direct Input"
    ): Result<ImportedSkillEntity> = withContext(Dispatchers.IO) {
        runCatching {
            val clean = rawText.trim()
            require(clean.isNotBlank()) { "Skill content is empty." }
            val parsed = analyzeSkillContent(clean, titleHint.ifBlank { "Custom Skill" }, "SKILL.md")
            val slug = allocateUniqueSlug(titleHint.ifBlank { parsed.title })
            val skillDir = workspace.resolveSafe("skills/$slug")
            if (!skillDir.exists()) skillDir.mkdirs()

            val ext = guessExtension(clean)
            val entryFile = File(skillDir, if (ext == "md") "SKILL.md" else "skill.$ext")
            entryFile.writeText(clean)

            finalizeAndPersistSkill(
                slug = slug,
                skillDir = skillDir,
                entryFile = entryFile,
                parsed = parsed,
                sourceOrigin = sourceOrigin
            )
        }
    }

    suspend fun importFromUrl(
        rawUrl: String,
        titleHint: String = ""
    ): Result<ImportedSkillEntity> = withContext(Dispatchers.IO) {
        runCatching {
            val url = rawUrl.trim()
            // 1. Direct ZIP archive URL
            if (url.lowercase().substringBefore('?').endsWith(".zip")) {
                val bytes = downloadBytes(url)
                val zipName = url.substringAfterLast('/').substringBefore('?').ifBlank { "skill.zip" }
                return@runCatching importFromZipBytes(bytes, zipName, titleHint, url)
            }

            // 2. GitHub repo or folder URL -> fetch full skill folder via GitHub Contents API or raw fallbacks
            val ghParsed = parseGitHubUrl(url)
            if (ghParsed != null) {
                val (owner, repo, branchHint, subPath, isBlob) = ghParsed
                if (isBlob) {
                    val rawGhUrl = "https://raw.githubusercontent.com/$owner/$repo/${branchHint.ifBlank { "main" }}/$subPath"
                    val content = downloadText(rawGhUrl)
                    val fileName = subPath.substringAfterLast('/').ifBlank { "SKILL.md" }
                    return@runCatching importSingleTextSkill(content, fileName, titleHint, url)
                }

                val branches = listOfNotNull(branchHint.takeIf { it.isNotBlank() }, "main", "master").distinct()
                for (branch in branches) {
                    val folderResult = runCatching {
                        downloadGitHubFolderIntoSkill(owner, repo, branch, subPath, titleHint, url)
                    }
                    if (folderResult.isSuccess) return@runCatching folderResult.getOrThrow()
                }
            }

            // 3. Direct raw URL (.md, .json, .yaml, .py, .js, .txt)
            val directRaw = if (url.contains("github.com/") && url.contains("/blob/")) {
                url.replace("https://github.com/", "https://raw.githubusercontent.com/")
                    .replace("/blob/", "/")
            } else url

            val text = downloadText(directRaw)
            val fileName = directRaw.substringAfterLast('/').substringBefore('?').ifBlank { "SKILL.md" }
            importSingleTextSkill(text, fileName, titleHint, url)
        }
    }

    private suspend fun importFromLocalFile(
        file: File,
        displayName: String,
        titleHint: String,
        origin: String
    ): Result<ImportedSkillEntity> = runCatching {
        val lower = displayName.lowercase()
        if (lower.endsWith(".zip") || lower.endsWith(".skill")) {
            val bytes = file.readBytes()
            val isZip = bytes.size >= 2 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()
            if (isZip) {
                return@runCatching importFromZipBytes(bytes, displayName, titleHint, origin)
            }
        }
        val text = file.readText()
        importSingleTextSkill(text, displayName, titleHint, origin)
    }

    private suspend fun importFromDirectory(
        dir: File,
        titleHint: String,
        origin: String
    ): Result<ImportedSkillEntity> = runCatching {
        val entry = findBestEntryFile(dir)
            ?: error("No SKILL.md, README.md, JSON, or script entry file found in ${dir.name}")
        val rawContent = entry.readText()
        val parsed = analyzeSkillContent(rawContent, titleHint.ifBlank { dir.name }, entry.name)
        val slug = allocateUniqueSlug(titleHint.ifBlank { parsed.title })
        val targetDir = workspace.resolveSafe("skills/$slug")
        if (dir.canonicalFile != targetDir.canonicalFile) {
            dir.copyRecursively(targetDir, overwrite = true)
        }
        val relEntry = entry.relativeTo(dir).path
        val targetEntry = File(targetDir, relEntry)
        finalizeAndPersistSkill(slug, targetDir, targetEntry, parsed, origin)
    }

    private suspend fun importSingleTextSkill(
        content: String,
        fileName: String,
        titleHint: String,
        origin: String
    ): ImportedSkillEntity {
        require(content.isNotBlank()) { "Skill file is empty." }
        val fallbackTitle = titleHint.ifBlank {
            fileName.substringBeforeLast('.').replace('_', ' ').replace('-', ' ').trim()
                .ifBlank { "Imported Skill" }
        }
        val parsed = analyzeSkillContent(content, fallbackTitle, fileName)
        val slug = allocateUniqueSlug(titleHint.ifBlank { parsed.title })
        val skillDir = workspace.resolveSafe("skills/$slug")
        if (!skillDir.exists()) skillDir.mkdirs()
        val safeName = fileName.replace(Regex("[^a-zA-Z0-9._-]"), "_").ifBlank { "SKILL.md" }
        val entryFile = File(skillDir, safeName)
        entryFile.writeText(content)
        return finalizeAndPersistSkill(slug, skillDir, entryFile, parsed, origin)
    }

    private suspend fun importFromZipBytes(
        zipBytes: ByteArray,
        archiveName: String,
        titleHint: String,
        origin: String
    ): ImportedSkillEntity {
        val tempSlug = allocateUniqueSlug(titleHint.ifBlank { archiveName.substringBeforeLast('.') })
        val skillDir = workspace.resolveSafe("skills/$tempSlug")
        if (!skillDir.exists()) skillDir.mkdirs()

        val rootCanonical = skillDir.canonicalPath
        var extractedCount = 0
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null && extractedCount < 250) {
                if (!entry.isDirectory) {
                    val outFile = File(skillDir, entry.name).canonicalFile
                    require(outFile.path.startsWith("$rootCanonical${File.separator}")) {
                        "Security violation: Zip entry escapes skill directory (${entry.name})"
                    }
                    outFile.parentFile?.let { if (!it.exists()) it.mkdirs() }
                    outFile.outputStream().use { zis.copyTo(it) }
                    extractedCount++
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        require(extractedCount > 0) { "Skill archive contained no extractable files." }

        val entryFile = findBestEntryFile(skillDir)
            ?: skillDir.walkTopDown().firstOrNull { it.isFile }
            ?: error("No readable skill entry found inside $archiveName")
        val content = entryFile.readText()
        val parsed = analyzeSkillContent(
            content,
            titleHint.ifBlank { archiveName.substringBeforeLast('.') },
            entryFile.name
        )
        return finalizeAndPersistSkill(tempSlug, skillDir, entryFile, parsed, origin)
    }

    private suspend fun downloadGitHubFolderIntoSkill(
        owner: String,
        repo: String,
        branch: String,
        subPath: String,
        titleHint: String,
        originUrl: String
    ): ImportedSkillEntity {
        val cleanSub = subPath.trim('/')
        val apiUrl = buildString {
            append("https://api.github.com/repos/$owner/$repo/contents")
            if (cleanSub.isNotBlank()) append("/$cleanSub")
            append("?ref=$branch")
        }
        val slug = allocateUniqueSlug(
            titleHint.ifBlank { cleanSub.substringAfterLast('/').ifBlank { repo } }
        )
        val skillDir = workspace.resolveSafe("skills/$slug")
        if (!skillDir.exists()) skillDir.mkdirs()

        var downloaded = 0
        fun fetchDirRecursive(dirApiUrl: String, targetLocalDir: File, depth: Int) {
            if (depth > 3 || downloaded >= 30) return
            val jsonRaw = downloadText(dirApiUrl)
            val arr = JSONArray(jsonRaw)
            for (i in 0 until arr.length()) {
                if (downloaded >= 30) break
                val item = arr.optJSONObject(i) ?: continue
                val type = item.optString("type")
                val name = item.optString("name")
                if (name.startsWith(".") || name == "node_modules") continue
                if (type == "file") {
                    val dlUrl = item.optString("download_url")
                    val size = item.optLong("size", 0L)
                    if (dlUrl.isNotBlank() && size in 0..1_500_000L) {
                        val bytes = downloadBytes(dlUrl)
                        val out = File(targetLocalDir, name.replace(Regex("[^a-zA-Z0-9._-]"), "_"))
                        out.parentFile?.let { if (!it.exists()) it.mkdirs() }
                        out.writeBytes(bytes)
                        downloaded++
                    }
                } else if (type == "dir") {
                    val subApi = item.optString("url")
                    if (subApi.isNotBlank()) {
                        fetchDirRecursive(subApi, File(targetLocalDir, name), depth + 1)
                    }
                }
            }
        }

        fetchDirRecursive(apiUrl, skillDir, 0)
        require(downloaded > 0) { "No files found at GitHub path." }

        val entryFile = findBestEntryFile(skillDir)
            ?: skillDir.walkTopDown().firstOrNull { it.isFile }
            ?: error("No entry file found in GitHub skill.")
        val parsed = analyzeSkillContent(
            entryFile.readText(),
            titleHint.ifBlank { cleanSub.substringAfterLast('/').ifBlank { repo } },
            entryFile.name
        )
        return finalizeAndPersistSkill(slug, skillDir, entryFile, parsed, originUrl)
    }

    private suspend fun finalizeAndPersistSkill(
        slug: String,
        skillDir: File,
        entryFile: File,
        parsed: SkillMetadataParsed,
        sourceOrigin: String
    ): ImportedSkillEntity {
        val allFiles = skillDir.walkTopDown()
            .filter { it.isFile && it.name != "ADAPTER_MANIFEST.json" }
            .map { workspace.relativePathOf(it) }
            .sorted()
            .toList()
        val bundledSummary = allFiles.take(30).joinToString(", ")

        val manifest = runCatching {
            JSONObject()
                .put("slug", slug)
                .put("title", parsed.title)
                .put("ecosystem", parsed.ecosystem)
                .put("description", parsed.description)
                .put("entryPath", workspace.relativePathOf(entryFile))
                .put("bundledFiles", JSONArray(allFiles))
                .put("toolMapping", parsed.toolMappingSummary)
                .put("wordCount", parsed.wordCount)
                .put("lineCount", parsed.lineCount)
                .put("sourceOrigin", sourceOrigin)
                .toString(2)
        }.getOrNull() ?: "{\"slug\":\"$slug\",\"title\":\"${parsed.title}\",\"ecosystem\":\"${parsed.ecosystem}\"}"
        File(skillDir, "ADAPTER_MANIFEST.json").writeText(manifest)

        val entity = ImportedSkillEntity(
            slug = slug,
            title = parsed.title.take(80),
            ecosystem = parsed.ecosystem,
            description = parsed.description.take(280),
            entryPath = workspace.relativePathOf(entryFile),
            bundledFilesSummary = bundledSummary.take(600),
            toolMappingSummary = parsed.toolMappingSummary.take(700),
            tocSummary = parsed.tocSummary.take(900),
            wordCount = parsed.wordCount,
            lineCount = parsed.lineCount,
            sourceOrigin = sourceOrigin.take(220),
            enabled = true
        )
        repository.upsertImportedSkill(entity)
        return entity
    }

    suspend fun loadSkillForAgent(
        slugOrTitle: String,
        query: String = "",
        charOffset: Int = 0,
        maxChars: Int = 6500
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val all = repository.getAllImportedSkillsSnapshot()
            val q = slugOrTitle.trim().lowercase()
            val skill = all.firstOrNull {
                it.slug.lowercase() == q ||
                    it.title.lowercase().contains(q) ||
                    it.slug.lowercase().contains(q)
            } ?: error("Imported skill '$slugOrTitle' not found. Available: ${all.joinToString { it.slug }}")

            val file = workspace.resolveSafe(skill.entryPath)
            require(file.exists() && file.isFile) { "Skill entry missing on disk: ${skill.entryPath}" }
            val fullText = file.readText()
            val cleanQuery = query.trim().lowercase()

            val bodySlice = if (fullText.length <= maxChars && charOffset == 0) {
                fullText
            } else if (cleanQuery.isNotBlank()) {
                extractRelevantSections(fullText, cleanQuery, maxChars)
            } else {
                val safeStart = charOffset.coerceIn(0, (fullText.length - 1).coerceAtLeast(0))
                val safeEnd = (safeStart + maxChars).coerceAtMost(fullText.length)
                val nextHint = if (safeEnd < fullText.length) {
                    "\n\n[... More content available: ${fullText.length - safeEnd} chars remaining. Call skill_load(slug='${skill.slug}', offset=$safeEnd) or pass a section query ...]"
                } else ""
                fullText.substring(safeStart, safeEnd) + nextHint
            }

            buildString {
                append("=== IMPORTED SKILL: ${skill.title} (${skill.slug}) ===\n")
                append("Origin Ecosystem: ${skill.ecosystem} | Size: ${skill.wordCount} words / ${skill.lineCount} lines\n")
                append("Entry Path: ${skill.entryPath}\n")
                if (skill.bundledFilesSummary.isNotBlank()) {
                    append("Bundled Files in virtual_env/: ${skill.bundledFilesSummary}\n")
                }
                append("AUTO-ADAPTED TOOL MAPPING (Use our Android tools directly):\n${skill.toolMappingSummary}\n")
                if (skill.tocSummary.isNotBlank() && fullText.length > maxChars) {
                    append("Document Sections:\n${skill.tocSummary}\n")
                }
                append("\n--- SKILL INSTRUCTIONS ---\n")
                append(bodySlice)
            }
        }
    }

    suspend fun deleteSkill(slug: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val skill = repository.getImportedSkillBySlug(slug)
            if (skill != null) {
                val dir = workspace.resolveSafe("skills/$slug")
                if (dir.exists()) dir.deleteRecursively()
                repository.deleteImportedSkill(slug)
            }
        }
    }

    suspend fun setSkillEnabled(slug: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        repository.getImportedSkillBySlug(slug)?.let {
            repository.upsertImportedSkill(it.copy(enabled = enabled))
        }
    }

    fun analyzeSkillContent(raw: String, fallbackTitle: String, fileName: String): SkillMetadataParsed {
        val lines = raw.lines()
        val lineCount = lines.size
        val wordCount = raw.trim().split(Regex("\\s+")).count { it.isNotBlank() }

        var fmName = ""
        var fmDesc = ""
        var fmTools = ""

        // Parse YAML frontmatter if present (Anthropic SKILL.md / OpenClaw / Universal)
        if (raw.trimStart().startsWith("---")) {
            val fmMatch = Regex("^---\\s*\\n([\\s\\S]*?)\\n---").find(raw.trimStart())
            val fmBlock = fmMatch?.groupValues?.getOrNull(1).orEmpty()
            fmName = Regex("(?m)^name:\\s*[\"']?(.+?)[\"']?\\s*$").find(fmBlock)?.groupValues?.getOrNull(1).orEmpty().trim()
            fmDesc = Regex("(?m)^description:\\s*[\"']?(.+?)[\"']?\\s*$").find(fmBlock)?.groupValues?.getOrNull(1).orEmpty().trim()
            fmTools = Regex("(?m)^allowed-tools:\\s*(.+)$").find(fmBlock)?.groupValues?.getOrNull(1).orEmpty().trim()
        }

        // Parse JSON manifest if JSON file
        if (fileName.lowercase().endsWith(".json") && raw.trimStart().startsWith("{")) {
            runCatching {
                val obj = JSONObject(raw)
                if (fmName.isBlank()) fmName = obj.optString("name", obj.optString("title", ""))
                if (fmDesc.isBlank()) fmDesc = obj.optString("description", "")
            }
        }

        val firstHeading = lines.firstOrNull { it.trimStart().startsWith("# ") }
            ?.removePrefix("#")?.trim().orEmpty()
        val title = fmName.ifBlank { firstHeading }.ifBlank { fallbackTitle }

        val firstPara = lines
            .dropWhile { it.trim().isEmpty() || it.trim() == "---" || it.contains(":") && !it.startsWith("#") }
            .firstOrNull { it.isNotBlank() && !it.trimStart().startsWith("#") && it.trim() != "---" }
            ?.trim().orEmpty()
        val description = fmDesc.ifBlank { firstPara }.ifBlank { "Auto-adapted ${fileName.substringAfterLast('.').uppercase()} skill ($wordCount words)" }

        val lower = raw.lowercase()
        val ecosystem = when {
            lower.contains("openclaw") || lower.contains("clawhub") || lower.contains("sessions_spawn") || fileName.equals("openclaw.json", true) -> "OpenClaw"
            lower.contains("anthropic") || lower.contains("claude") || fmTools.isNotBlank() || lower.contains("allowed-tools") || fileName.equals("SKILL.md", true) -> "Anthropic / Claude"
            lower.contains("gemini") || lower.contains("google-genai") || lower.contains("adk") -> "Google Gemini"
            lower.contains("mcpservers") || lower.contains("inputschema") || lower.contains("openai") || lower.contains("langchain") || lower.contains("crewai") -> "MCP / OpenAI / Agent"
            fileName.endsWith(".py") || fileName.endsWith(".js") || fileName.endsWith(".ts") || fileName.endsWith(".sh") -> "Executable Script"
            else -> "Universal Skill"
        }

        val mappings = linkedSetOf<String>()
        if (lower.contains("bash") || lower.contains("terminal") || lower.contains("shell") || lower.contains("subprocess") || lower.contains("exec")) {
            mappings.add("Bash/Terminal -> shell_exec (or python_exec)")
        }
        if (lower.contains("read") || lower.contains("view_file") || lower.contains("cat ")) {
            mappings.add("Read/View -> file_read (bundled files live in virtual_env/skills/)")
        }
        if (lower.contains("write") || lower.contains("edit") || lower.contains("multiedit")) {
            mappings.add("Write/Edit -> file_write")
        }
        if (lower.contains("webfetch") || lower.contains("curl") || lower.contains("fetch") || lower.contains("requests")) {
            mappings.add("WebFetch/HTTP -> http_request")
        }
        if (lower.contains("websearch") || lower.contains("browser") || lower.contains("playwright") || lower.contains("puppeteer") || lower.contains("selenium")) {
            mappings.add("Browser/Playwright -> browser_open_and_read, browser_click, browser_fill, browser_eval_js")
        }
        if (lower.contains("computer") || lower.contains("click") || lower.contains("tap") || lower.contains("adb") || lower.contains("swipe")) {
            mappings.add("Computer/UI Control -> device_screen_inspect, device_virtual_hand, app_launch")
        }
        if (lower.contains("python") || fileName.endsWith(".py")) {
            mappings.add("Python -> python_exec (on-device runtime)")
        }
        if (lower.contains("node") || fileName.endsWith(".js") || fileName.endsWith(".ts")) {
            mappings.add("JavaScript/TS -> js_exec (Android V8)")
        }
        if (mappings.isEmpty()) {
            mappings.add("General Steps -> shell_exec, python_exec, file_read/write, browser_open_and_read, device_virtual_hand")
        }

        val headings = lines.mapIndexedNotNull { idx, line ->
            val t = line.trimStart()
            if (t.startsWith("# ") || t.startsWith("## ") || t.startsWith("### ")) {
                "L${idx + 1}: ${t.take(80)}"
            } else null
        }.take(28)

        return SkillMetadataParsed(
            title = title,
            description = description,
            ecosystem = ecosystem,
            toolMappingSummary = mappings.joinToString("\n• ", prefix = "• "),
            tocSummary = headings.joinToString("\n"),
            wordCount = wordCount,
            lineCount = lineCount
        )
    }

    private fun extractRelevantSections(fullText: String, queryLower: String, maxChars: Int): String {
        val keywords = queryLower.split(Regex("\\s+")).filter { it.length >= 2 }.distinct()
        val sections = fullText.split(Regex("(?m)(?=^#{1,3}\\s)"))
        val scored = sections.mapIndexed { idx, sec ->
            val secLower = sec.lowercase()
            val hits = keywords.count { secLower.contains(it) }
            Triple(idx, hits, sec)
        }.filter { it.second > 0 }
            .sortedWith(compareByDescending<Triple<Int, Int, String>> { it.second }.thenBy { it.first })

        if (scored.isEmpty()) return fullText.take(maxChars)
        val sb = StringBuilder()
        for ((_, _, sec) in scored) {
            if (sb.length + sec.length + 4 > maxChars) {
                if (sb.isEmpty()) {
                    val secLower = sec.lowercase()
                    val firstHitPos = keywords
                        .mapNotNull { kw -> secLower.indexOf(kw).takeIf { it >= 0 } }
                        .minOrNull()
                    val window = if (firstHitPos != null && firstHitPos > maxChars / 2) {
                        val start = (firstHitPos - maxChars / 3).coerceAtLeast(0)
                        val end = (start + maxChars).coerceAtMost(sec.length)
                        val headerLine = sec.lineSequence().firstOrNull().orEmpty()
                        val bodySlice = sec.substring(start, end)
                        if (headerLine.startsWith("#") && start > headerLine.length) {
                            "$headerLine\n...\n$bodySlice"
                        } else {
                            "...\n$bodySlice"
                        }
                    } else {
                        sec.take(maxChars)
                    }
                    sb.append(window)
                }
                break
            }
            sb.append(sec).append("\n")
        }
        return sb.toString().trim()
    }

    private fun findBestEntryFile(dir: File): File? {
        val priorityNames = listOf("SKILL.md", "skill.md", "README.md", "readme.md", "openclaw.json", "skill.json", "manifest.json", "AGENTS.md", "TOOLS.md")
        for (name in priorityNames) {
            val found = dir.walkTopDown().firstOrNull { it.isFile && it.name.equals(name, ignoreCase = true) }
            if (found != null) return found
        }
        return dir.walkTopDown().firstOrNull {
            it.isFile && it.extension.lowercase() in setOf("md", "txt", "json", "yaml", "yml", "py", "js", "ts", "sh")
        }
    }

    private fun allocateUniqueSlug(title: String): String {
        val base = title.lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .take(36)
            .ifBlank { "skill_${System.currentTimeMillis() % 100000}" }
        val existing = workspace.resolveSafe("skills/$base")
        return if (!existing.exists()) base else "${base}_${System.currentTimeMillis() % 10000}"
    }

    private fun guessExtension(text: String): String {
        val t = text.trimStart()
        return when {
            t.startsWith("{") || t.startsWith("[") -> "json"
            t.startsWith("import ") || t.startsWith("def ") || t.contains("if __name__ ==") -> "py"
            t.startsWith("#!/") -> "sh"
            else -> "md"
        }
    }

    private data class GitHubUrlParts(
        val owner: String,
        val repo: String,
        val branch: String,
        val subPath: String,
        val isBlob: Boolean
    )

    private fun parseGitHubUrl(url: String): GitHubUrlParts? {
        val regex = Regex("""^https?://github\.com/([^/]+)/([^/#?]+?)(?:\.git)?(?:/(tree|blob)/([^/]+)(?:/(.*))?)?/?$""")
        val match = regex.find(url.substringBefore('?')) ?: return null
        val owner = match.groupValues[1]
        val repo = match.groupValues[2]
        val kind = match.groupValues[3]
        val branch = match.groupValues[4]
        val sub = match.groupValues[5]
        return GitHubUrlParts(owner, repo, branch, sub, isBlob = kind == "blob")
    }

    private fun downloadText(url: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "PersonalAIComputer-SkillAdapter/1.0")
            .build()
        httpClient.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            check(resp.isSuccessful) { "HTTP ${resp.code} fetching $url" }
            auditLogger.log(url.substringAfter("://").substringBefore("/"), "Skill Download", "200 OK", raw.length.toLong())
            return raw
        }
    }

    private fun downloadBytes(url: String): ByteArray {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "PersonalAIComputer-SkillAdapter/1.0")
            .build()
        httpClient.newCall(req).execute().use { resp ->
            val bytes = resp.body?.bytes() ?: ByteArray(0)
            check(resp.isSuccessful) { "HTTP ${resp.code} downloading $url" }
            auditLogger.log(url.substringAfter("://").substringBefore("/"), "Skill Archive", "200 OK", bytes.size.toLong())
            return bytes
        }
    }
}
