package com.example.agent

import com.example.runtime.VirtualWorkspaceManager
import com.example.security.NetworkAuditLogger
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

data class BulkSkillImportResult(
    val importedCount: Int,
    val skippedCount: Int = 0,
    val modeLabel: String,
    val sampleTitles: List<String> = emptyList(),
    val errors: List<String> = emptyList(),
    val firstSkill: ImportedSkillEntity? = null
)

data class SkillMatchResult(
    val skill: ImportedSkillEntity,
    val score: Int,
    val matchedTerms: List<String>
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
        suspend fun upsertImportedSkills(skills: List<ImportedSkillEntity>) {
            skills.forEach { upsertImportedSkill(it) }
        }
        suspend fun deleteImportedSkill(slug: String)
        suspend fun clearAllImportedSkills() {}
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
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
        val bulk = importAutoBulk(source, titleHint).getOrElse { return@withContext Result.failure(it) }
        val first = bulk.firstSkill
            ?: repository.getAllImportedSkillsSnapshot().firstOrNull()
            ?: return@withContext Result.failure(IllegalStateException("No importable skill found."))
        Result.success(first)
    }

    suspend fun importAutoBulk(
        source: String,
        titleHint: String = "",
        onProgress: ((String) -> Unit)? = null
    ): Result<BulkSkillImportResult> = withContext(Dispatchers.IO) {
        runCatching {
            val trimmed = source.trim()
            require(trimmed.isNotBlank()) { "Skill source cannot be empty." }

            // Multi-URL batch paste detection (e.g. 5-100+ GitHub URLs pasted line by line)
            val urlLines = trimmed.lines()
                .map { it.trim() }
                .filter { it.startsWith("http://") || it.startsWith("https://") }
            if (urlLines.size >= 2 && trimmed.lines().count { it.isNotBlank() } == urlLines.size) {
                var totalImported = 0
                var totalSkipped = 0
                val samples = mutableListOf<String>()
                val errors = mutableListOf<String>()
                var firstImported: ImportedSkillEntity? = null

                urlLines.distinct().forEachIndexed { idx, lineUrl ->
                    onProgress?.invoke("ব্যাচ ইম্পোর্ট (${idx + 1}/${urlLines.size}): ${lineUrl.substringAfterLast('/').take(28)}...")
                    importFromUrlBulk(lineUrl, "", onProgress).fold(
                        onSuccess = { res ->
                            totalImported += res.importedCount
                            totalSkipped += res.skippedCount
                            if (firstImported == null) firstImported = res.firstSkill
                            if (samples.size < 8) samples.addAll(res.sampleTitles.take(8 - samples.size))
                        },
                        onFailure = { err ->
                            errors.add("${lineUrl.take(45)}: ${err.message}")
                        }
                    )
                }
                require(totalImported > 0) {
                    "Batch URL import failed: ${errors.firstOrNull() ?: "No valid skills found"}"
                }
                return@runCatching BulkSkillImportResult(
                    importedCount = totalImported,
                    skippedCount = totalSkipped,
                    modeLabel = "Multi-URL Batch (${urlLines.size} links)",
                    sampleTitles = samples,
                    errors = errors,
                    firstSkill = firstImported
                )
            }

            when {
                trimmed.startsWith("http://") || trimmed.startsWith("https://") ->
                    importFromUrlBulk(trimmed, titleHint, onProgress).getOrThrow()
                trimmed.length < 300 && !trimmed.contains('\n') -> {
                    val candidate = runCatching { workspace.resolveSafe(trimmed) }.getOrNull()
                    if (candidate != null && candidate.exists()) {
                        if (candidate.isDirectory) {
                            importFromDirectoryBulk(candidate, titleHint, trimmed, onProgress).getOrThrow()
                        } else {
                            importFromLocalFileBulk(candidate, candidate.name, titleHint, trimmed, onProgress).getOrThrow()
                        }
                    } else {
                        val single = importFromRawText(trimmed, titleHint, "Direct Text").getOrThrow()
                        BulkSkillImportResult(1, 0, "Direct Text", listOf(single.title), firstSkill = single)
                    }
                }
                else -> {
                    val single = importFromRawText(trimmed, titleHint, "Direct Text / Prompt").getOrThrow()
                    BulkSkillImportResult(1, 0, "Direct Skill Text", listOf(single.title), firstSkill = single)
                }
            }
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
            val bulk = importFromLocalFileBulk(file, displayName.ifBlank { file.name }, titleHint, relativePath, null).getOrThrow()
            bulk.firstSkill ?: error("No skill parsed from $relativePath")
        }
    }

    suspend fun importFromUploadedPathBulk(
        relativePath: String,
        displayName: String = "",
        titleHint: String = "",
        onProgress: ((String) -> Unit)? = null
    ): Result<BulkSkillImportResult> = withContext(Dispatchers.IO) {
        runCatching {
            val file = workspace.resolveSafe(relativePath)
            require(file.exists() && file.isFile) { "Uploaded skill file not found: $relativePath" }
            importFromLocalFileBulk(file, displayName.ifBlank { file.name }, titleHint, relativePath, onProgress).getOrThrow()
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

            val entity = buildSkillEntity(slug, skillDir, entryFile, parsed, sourceOrigin)
            repository.upsertImportedSkill(entity)
            entity
        }
    }

    suspend fun importFromUrl(
        rawUrl: String,
        titleHint: String = ""
    ): Result<ImportedSkillEntity> = withContext(Dispatchers.IO) {
        runCatching {
            val bulk = importFromUrlBulk(rawUrl, titleHint, null).getOrThrow()
            bulk.firstSkill ?: error("No skill imported from $rawUrl")
        }
    }

    suspend fun importFromUrlBulk(
        rawUrl: String,
        titleHint: String = "",
        onProgress: ((String) -> Unit)? = null
    ): Result<BulkSkillImportResult> = withContext(Dispatchers.IO) {
        runCatching {
            val url = rawUrl.trim()
            // 1. Direct ZIP archive URL
            if (url.lowercase().substringBefore('?').endsWith(".zip")) {
                onProgress?.invoke("ZIP ডাউনলোড হচ্ছে...")
                val bytes = downloadBytes(url)
                val zipName = url.substringAfterLast('/').substringBefore('?').ifBlank { "skill.zip" }
                return@runCatching importFromZipBytesBulk(bytes, zipName, titleHint, url, onProgress = onProgress)
            }

            // 2. GitHub repo, folder, or file URL
            val ghParsed = parseGitHubUrl(url)
            if (ghParsed != null) {
                val (owner, repo, branchHint, subPath, isBlob) = ghParsed
                if (isBlob) {
                    onProgress?.invoke("ডাউনলোড হচ্ছে: ${subPath.substringAfterLast('/')}")
                    val rawGhUrl = "https://raw.githubusercontent.com/$owner/$repo/${branchHint.ifBlank { "main" }}/$subPath"
                    val content = downloadText(rawGhUrl)
                    val fileName = subPath.substringAfterLast('/').ifBlank { "SKILL.md" }
                    val single = importSingleTextSkill(content, fileName, titleHint, url)
                    return@runCatching BulkSkillImportResult(1, 0, "GitHub File", listOf(single.title), firstSkill = single)
                }

                // FAST ZERO-RATE-LIMIT STRATEGY: Download repo archive ZIP in 1 HTTP call!
                val candidateBranches = buildList {
                    if (branchHint.isNotBlank()) add(branchHint)
                    add("main")
                    add("master")
                }.distinct().toMutableList()

                var downloadedZip: ByteArray? = null
                var resolvedBranch = branchHint.ifBlank { "main" }
                for (branch in candidateBranches) {
                    onProgress?.invoke("GitHub রেপো ZIP ডাউনলোড হচ্ছে ($owner/$repo@$branch)...")
                    val archiveUrl = "https://github.com/$owner/$repo/archive/refs/heads/$branch.zip"
                    val attempt = runCatching { downloadBytes(archiveUrl) }
                    if (attempt.isSuccess && (attempt.getOrNull()?.size ?: 0) > 100) {
                        downloadedZip = attempt.getOrNull()
                        resolvedBranch = branch
                        break
                    }
                }

                // If default branch is neither main nor master, inspect repo metadata once
                if (downloadedZip == null) {
                    val defaultBranch = runCatching {
                        val metaJson = JSONObject(downloadText("https://api.github.com/repos/$owner/$repo"))
                        metaJson.optString("default_branch").trim()
                    }.getOrNull().orEmpty()
                    if (defaultBranch.isNotBlank() && defaultBranch !in candidateBranches) {
                        onProgress?.invoke("GitHub রেপো ZIP ডাউনলোড হচ্ছে ($owner/$repo@$defaultBranch)...")
                        val archiveUrl = "https://github.com/$owner/$repo/archive/refs/heads/$defaultBranch.zip"
                        val attempt = runCatching { downloadBytes(archiveUrl) }
                        if (attempt.isSuccess && (attempt.getOrNull()?.size ?: 0) > 100) {
                            downloadedZip = attempt.getOrNull()
                            resolvedBranch = defaultBranch
                        }
                    }
                }

                if (downloadedZip != null) {
                    val repoBaseUrl = "https://github.com/$owner/$repo/tree/$resolvedBranch"
                    return@runCatching importFromZipBytesBulk(
                        zipBytes = downloadedZip,
                        archiveName = "$repo.zip",
                        titleHint = titleHint,
                        origin = url,
                        subPathFilter = subPath,
                        repoBaseTreeUrl = repoBaseUrl,
                        onProgress = onProgress
                    )
                }

                // Fallback to GitHub Contents API for single subfolder
                for (branch in candidateBranches) {
                    val folderResult = runCatching {
                        downloadGitHubFolderIntoSkill(owner, repo, branch, subPath, titleHint, url)
                    }
                    if (folderResult.isSuccess) {
                        val sk = folderResult.getOrThrow()
                        return@runCatching BulkSkillImportResult(1, 0, "GitHub Folder", listOf(sk.title), firstSkill = sk)
                    }
                }
            }

            // 3. Direct raw URL (.md, .json, .yaml, .py, .js, .txt)
            val directRaw = if (url.contains("github.com/") && url.contains("/blob/")) {
                url.replace("https://github.com/", "https://raw.githubusercontent.com/")
                    .replace("/blob/", "/")
            } else url

            onProgress?.invoke("স্কিল ফাইল ডাউনলোড হচ্ছে...")
            val text = downloadText(directRaw)
            val fileName = directRaw.substringAfterLast('/').substringBefore('?').ifBlank { "SKILL.md" }
            val single = importSingleTextSkill(text, fileName, titleHint, url)
            BulkSkillImportResult(1, 0, "Direct URL", listOf(single.title), firstSkill = single)
        }
    }

    private suspend fun importFromLocalFileBulk(
        file: File,
        displayName: String,
        titleHint: String,
        origin: String,
        onProgress: ((String) -> Unit)?
    ): Result<BulkSkillImportResult> = runCatching {
        val lower = displayName.lowercase()
        if (lower.endsWith(".zip") || lower.endsWith(".skill")) {
            val bytes = file.readBytes()
            val isZip = bytes.size >= 2 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()
            if (isZip) {
                return@runCatching importFromZipBytesBulk(bytes, displayName, titleHint, origin, onProgress = onProgress)
            }
        }
        val text = file.readText()
        val single = importSingleTextSkill(text, displayName, titleHint, origin)
        BulkSkillImportResult(1, 0, "Local File", listOf(single.title), firstSkill = single)
    }

    private suspend fun importFromDirectoryBulk(
        dir: File,
        titleHint: String,
        origin: String,
        onProgress: ((String) -> Unit)?
    ): Result<BulkSkillImportResult> = runCatching {
        val skillEntries = dir.walkTopDown()
            .filter { it.isFile && isExplicitSkillManifestName(it.name) }
            .toList()
        if (skillEntries.size >= 2) {
            onProgress?.invoke("${skillEntries.size}টি স্কিল ফোল্ডার যুক্ত হচ্ছে...")
            val existingSlugs = repository.getAllImportedSkillsSnapshot().map { it.slug }.toMutableSet()
            val entities = mutableListOf<ImportedSkillEntity>()
            val samples = mutableListOf<String>()
            for (entry in skillEntries.take(4500)) {
                val folder = entry.parentFile ?: continue
                val content = runCatching { entry.readText() }.getOrNull().orEmpty()
                if (content.isBlank()) continue
                val fallback = folder.name.replace('-', ' ').replace('_', ' ').trim().ifBlank { "Skill" }
                val parsed = analyzeSkillContent(content, fallback, entry.name)
                val slug = allocateFastUniqueSlug(parsed.title.ifBlank { fallback }, existingSlugs)
                val targetDir = workspace.resolveSafe("skills/$slug")
                if (folder.canonicalFile != targetDir.canonicalFile) {
                    folder.copyRecursively(targetDir, overwrite = true)
                }
                val targetEntry = File(targetDir, entry.name)
                val entity = buildSkillEntity(slug, targetDir, targetEntry, parsed, origin)
                entities.add(entity)
                if (samples.size < 8) samples.add(entity.title)
            }
            batchPersistSkills(entities)
            return@runCatching BulkSkillImportResult(
                importedCount = entities.size,
                modeLabel = "Multi-Skill Directory",
                sampleTitles = samples,
                firstSkill = entities.firstOrNull()
            )
        }

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
        val entity = buildSkillEntity(slug, targetDir, targetEntry, parsed, origin)
        repository.upsertImportedSkill(entity)
        BulkSkillImportResult(1, 0, "Directory Skill", listOf(entity.title), firstSkill = entity)
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
        val entity = buildSkillEntity(slug, skillDir, entryFile, parsed, origin)
        repository.upsertImportedSkill(entity)
        return entity
    }

    private data class ZipMemFile(
        val relPath: String,
        val fileName: String,
        val bytes: ByteArray
    )

    /**
     * Universal Bulk ZIP Processor:
     * - Handles single-skill ZIP archives cleanly (backward compatible).
     * - Automatically splits multi-skill repositories (e.g. anthropics/skills, obra/superpowers, sickn33/agentic-awesome-skills)
     *   into separate ImportedSkillEntity records.
     * - Automatically extracts curated awesome-skills README/catalog entries (e.g. VoltAgent/awesome-agent-skills 1,000+ skills)
     *   as lazy-hydrated local index stubs in 1 pass with ZERO GitHub rate-limit errors.
     */
    suspend fun importFromZipBytesBulk(
        zipBytes: ByteArray,
        archiveName: String,
        titleHint: String = "",
        origin: String = "ZIP Archive",
        subPathFilter: String = "",
        repoBaseTreeUrl: String = "",
        onProgress: ((String) -> Unit)? = null
    ): BulkSkillImportResult = withContext(Dispatchers.IO) {
        onProgress?.invoke("ZIP ফাইল আনপ্যাক ও স্ক্যান হচ্ছে...")

        val rawEntries = mutableListOf<ZipMemFile>()
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null && rawEntries.size < 12_000) {
                val name = entry.name.replace('\\', '/').trimStart('/')
                if (!entry.isDirectory &&
                    !name.contains("..") &&
                    !name.contains("__MACOSX/") &&
                    !name.contains("/.git/") &&
                    !name.startsWith(".git/") &&
                    !name.contains("/node_modules/")
                ) {
                    val fileBytes = readBoundedEntryBytes(zis, maxBytes = 1_500_000)
                    if (fileBytes != null) {
                        rawEntries.add(ZipMemFile(name, name.substringAfterLast('/'), fileBytes))
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        require(rawEntries.isNotEmpty()) { "Skill archive contained no readable files." }

        // Detect and strip common GitHub archive top-level root folder (e.g., "skills-main/")
        val firstSegments = rawEntries.map { it.relPath.substringBefore('/') }.distinct()
        val hasSingleRootDir = firstSegments.size == 1 && rawEntries.all { it.relPath.contains('/') }
        val normalized = if (hasSingleRootDir) {
            val prefix = "${firstSegments.first()}/"
            rawEntries.map { it.copy(relPath = it.relPath.removePrefix(prefix)) }
        } else {
            rawEntries
        }

        val cleanSub = subPathFilter.trim('/').lowercase()
        val scopedFiles = if (cleanSub.isBlank()) normalized else {
            normalized.filter {
                val p = it.relPath.lowercase()
                p == cleanSub || p.startsWith("$cleanSub/")
            }
        }
        require(scopedFiles.isNotEmpty()) { "No files found under '$subPathFilter' inside archive." }

        // 1. Identify explicit skill manifests (SKILL.md, openclaw.json, skill.json, manifest.json)
        val manifestFiles = scopedFiles.filter { isExplicitSkillManifestName(it.fileName) }
        val folderGroups = linkedMapOf<String, MutableList<ZipMemFile>>()
        for (mf in manifestFiles) {
            val parentDir = if (mf.relPath.contains('/')) mf.relPath.substringBeforeLast('/') else ""
            folderGroups.getOrPut(parentDir) { mutableListOf() }
        }

        // Attach companion files inside each detected skill folder
        if (folderGroups.isNotEmpty()) {
            for (f in scopedFiles) {
                val parent = if (f.relPath.contains('/')) f.relPath.substringBeforeLast('/') else ""
                var cursor = parent
                while (true) {
                    if (folderGroups.containsKey(cursor)) {
                        folderGroups[cursor]?.add(f)
                        break
                    }
                    if (!cursor.contains('/')) {
                        if (folderGroups.containsKey("") && f.relPath.count { it == '/' } <= 1) {
                            folderGroups[""]?.add(f)
                        }
                        break
                    }
                    cursor = cursor.substringBeforeLast('/')
                }
            }
        }

        // 2. Scan markdown catalogs (README.md, CATALOG.md, etc.) for awesome-agent-skills link directories
        val markdownDocs = scopedFiles.filter {
            val lower = it.fileName.lowercase()
            (lower.endsWith(".md") || lower.endsWith(".mdx")) &&
                !isExplicitSkillManifestName(it.fileName) &&
                it.bytes.size <= 1_200_000
        }
        val catalogItems = extractCatalogSkillsFromMarkdownDocs(markdownDocs, repoBaseTreeUrl, origin)

        // Single-skill archive (classic 1-skill ZIP with scripts/references) -> preserve exact single-skill behavior
        if (folderGroups.size == 1 && catalogItems.size < 8) {
            val (folderPath, filesInSkill) = folderGroups.entries.first()
            val entryMem = filesInSkill.firstOrNull { isExplicitSkillManifestName(it.fileName) }
                ?: filesInSkill.first()
            val entryText = entryMem.bytes.toString(Charsets.UTF_8)
            val fallback = titleHint.ifBlank {
                folderPath.substringAfterLast('/').ifBlank { archiveName.substringBeforeLast('.') }
            }
            val parsed = analyzeSkillContent(entryText, fallback, entryMem.fileName)
            val slug = allocateUniqueSlug(titleHint.ifBlank { parsed.title })
            val skillDir = workspace.resolveSafe("skills/$slug")
            if (!skillDir.exists()) skillDir.mkdirs()

            val stripPrefix = if (folderPath.isBlank()) "" else "$folderPath/"
            for (zf in filesInSkill.take(250)) {
                val relInside = zf.relPath.removePrefix(stripPrefix).ifBlank { zf.fileName }
                val out = File(skillDir, relInside).canonicalFile
                if (!out.path.startsWith("${skillDir.canonicalPath}${File.separator}")) continue
                out.parentFile?.let { if (!it.exists()) it.mkdirs() }
                out.writeBytes(zf.bytes)
            }
            val entryRel = entryMem.relPath.removePrefix(stripPrefix).ifBlank { entryMem.fileName }
            val entity = buildSkillEntity(slug, skillDir, File(skillDir, entryRel), parsed, origin)
            repository.upsertImportedSkill(entity)
            return@withContext BulkSkillImportResult(1, 0, "ZIP Skill", listOf(entity.title), firstSkill = entity)
        }

        val existingSkills = repository.getAllImportedSkillsSnapshot()
        val existingSlugs = existingSkills.map { it.slug }.toMutableSet()
        val entitiesToSave = mutableListOf<ImportedSkillEntity>()
        val sampleTitles = mutableListOf<String>()
        val seenSourceKeys = HashSet<String>(4096)
        var skipped = 0

        // A. Split & write all multi-folder SKILL.md skills
        if (folderGroups.isNotEmpty()) {
            onProgress?.invoke("${folderGroups.size}টি SKILL.md ফোল্ডার কনফিগার হচ্ছে...")
            for ((folderPath, filesInSkill) in folderGroups) {
                if (entitiesToSave.size >= 4500) break
                val entryMem = filesInSkill
                    .sortedBy { priorityRankForManifest(it.fileName) }
                    .firstOrNull { isExplicitSkillManifestName(it.fileName) }
                    ?: continue
                val content = entryMem.bytes.toString(Charsets.UTF_8)
                if (content.isBlank()) {
                    skipped++
                    continue
                }
                val fallback = folderPath.substringAfterLast('/').ifBlank {
                    archiveName.substringBeforeLast('.')
                }.replace('-', ' ').replace('_', ' ').trim()
                val parsed = analyzeSkillContent(content, fallback, entryMem.fileName)
                val slug = allocateFastUniqueSlug(parsed.title.ifBlank { fallback }, existingSlugs)
                val skillDir = workspace.resolveSafe("skills/$slug")
                if (!skillDir.exists()) skillDir.mkdirs()

                val stripPrefix = if (folderPath.isBlank()) "" else "$folderPath/"
                for (zf in filesInSkill.take(35)) {
                    val relInside = zf.relPath.removePrefix(stripPrefix).ifBlank { zf.fileName }
                    val out = File(skillDir, relInside).canonicalFile
                    if (!out.path.startsWith("${skillDir.canonicalPath}${File.separator}")) continue
                    out.parentFile?.let { if (!it.exists()) it.mkdirs() }
                    out.writeBytes(zf.bytes)
                }
                val entryRel = entryMem.relPath.removePrefix(stripPrefix).ifBlank { entryMem.fileName }
                val sourceUrl = if (repoBaseTreeUrl.isNotBlank() && folderPath.isNotBlank()) {
                    "$repoBaseTreeUrl/$folderPath"
                } else origin
                val entity = buildSkillEntity(slug, skillDir, File(skillDir, entryRel), parsed, sourceUrl)
                entitiesToSave.add(entity)
                seenSourceKeys.add(parsed.title.lowercase())
                if (sampleTitles.size < 10) sampleTitles.add(entity.title)
            }
        }

        // B. Add curated awesome-skills catalog links (e.g., 1,000+ community & official skills)
        if (catalogItems.size >= 5 || entitiesToSave.isEmpty()) {
            onProgress?.invoke("ক্যাটালগ থেকে ${catalogItems.size}টি স্কিল ইনডেক্স হচ্ছে...")
            for (cat in catalogItems) {
                if (entitiesToSave.size >= 4500) break
                val normTitleKey = cat.title.lowercase()
                if (!seenSourceKeys.add(normTitleKey) && !seenSourceKeys.add(cat.url.lowercase())) {
                    skipped++
                    continue
                }
                val slug = allocateFastUniqueSlug(cat.title, existingSlugs)
                val skillDir = workspace.resolveSafe("skills/$slug")
                if (!skillDir.exists()) skillDir.mkdirs()

                val stubContent = buildCatalogStubMarkdown(cat)
                val entryFile = File(skillDir, "SKILL.md")
                entryFile.writeText(stubContent)

                val parsed = analyzeSkillContent(stubContent, cat.title, "SKILL.md")
                val entity = buildSkillEntity(
                    slug = slug,
                    skillDir = skillDir,
                    entryFile = entryFile,
                    parsed = parsed.copy(
                        description = cat.description.ifBlank { parsed.description },
                        ecosystem = cat.ecosystem
                    ),
                    sourceOrigin = cat.url
                )
                entitiesToSave.add(entity)
                if (sampleTitles.size < 10) sampleTitles.add(entity.title)
            }
        }

        // C. Fallback: single README/doc skill if neither multi-folders nor catalog matched
        if (entitiesToSave.isEmpty()) {
            val tempSlug = allocateUniqueSlug(titleHint.ifBlank { archiveName.substringBeforeLast('.') })
            val skillDir = workspace.resolveSafe("skills/$tempSlug")
            if (!skillDir.exists()) skillDir.mkdirs()
            val rootCanonical = skillDir.canonicalPath
            for (zf in scopedFiles.take(250)) {
                val out = File(skillDir, zf.relPath).canonicalFile
                if (!out.path.startsWith("$rootCanonical${File.separator}")) continue
                out.parentFile?.let { if (!it.exists()) it.mkdirs() }
                out.writeBytes(zf.bytes)
            }
            val entryFile = findBestEntryFile(skillDir)
                ?: skillDir.walkTopDown().firstOrNull { it.isFile }
                ?: error("No readable skill entry found inside $archiveName")
            val parsed = analyzeSkillContent(
                entryFile.readText(),
                titleHint.ifBlank { archiveName.substringBeforeLast('.') },
                entryFile.name
            )
            val entity = buildSkillEntity(tempSlug, skillDir, entryFile, parsed, origin)
            repository.upsertImportedSkill(entity)
            return@withContext BulkSkillImportResult(1, skipped, "ZIP Archive", listOf(entity.title), firstSkill = entity)
        }

        onProgress?.invoke("${entitiesToSave.size}টি স্কিল ডাটাবেসে সেভ হচ্ছে...")
        batchPersistSkills(entitiesToSave)

        val mode = when {
            folderGroups.size >= 2 && catalogItems.size >= 5 -> "Hybrid Multi-Skill + Catalog (${entitiesToSave.size})"
            folderGroups.size >= 2 -> "Multi-Skill Archive (${entitiesToSave.size})"
            else -> "Awesome Catalog Index (${entitiesToSave.size})"
        }
        BulkSkillImportResult(
            importedCount = entitiesToSave.size,
            skippedCount = skipped,
            modeLabel = mode,
            sampleTitles = sampleTitles,
            firstSkill = entitiesToSave.firstOrNull()
        )
    }

    private suspend fun batchPersistSkills(entities: List<ImportedSkillEntity>) {
        if (entities.isEmpty()) return
        entities.chunked(400).forEach { chunk ->
            repository.upsertImportedSkills(chunk)
        }
    }

    private data class CatalogSkillStub(
        val title: String,
        val url: String,
        val category: String,
        val description: String,
        val ecosystem: String
    )

    private fun extractCatalogSkillsFromMarkdownDocs(
        docs: List<ZipMemFile>,
        repoBaseTreeUrl: String,
        originUrl: String
    ): List<CatalogSkillStub> {
        val out = mutableListOf<CatalogSkillStub>()
        val seenUrls = HashSet<String>(2048)
        val linkRegex = Regex("""\[([^\[\]\n]{2,90})]\(([^()\s\n]+)\)""")
        val headingRegex = Regex("""^#{1,4}\s+(.+)$""")

        // Prioritize README / CATALOG / INDEX files first
        val orderedDocs = docs.sortedBy { doc ->
            val n = doc.fileName.lowercase()
            when {
                n == "readme.md" -> 0
                n.contains("catalog") || n.contains("index") || n.contains("skills") -> 1
                else -> 2
            }
        }

        for (doc in orderedDocs) {
            if (out.size >= 4500) break
            val text = runCatching { doc.bytes.toString(Charsets.UTF_8) }.getOrNull().orEmpty()
            if (text.isBlank()) continue

            var currentCategory = doc.fileName.substringBeforeLast('.')
                .replace('-', ' ').replace('_', ' ').trim()
                .ifBlank { "Community Skill" }

            for (rawLine in text.lineSequence()) {
                if (out.size >= 4500) break
                val line = rawLine.trim()
                if (line.isBlank()) continue

                val hMatch = headingRegex.find(line)
                if (hMatch != null) {
                    val headingClean = hMatch.groupValues[1]
                        .replace(Regex("""\[.*?]\(.*?\)"""), "")
                        .replace(Regex("""[^\p{L}\p{N}\s/&+-]"""), " ")
                        .trim()
                    if (headingClean.length in 2..60 &&
                        !headingClean.equals("table of contents", true) &&
                        !headingClean.equals("contributing", true)
                    ) {
                        currentCategory = headingClean
                    }
                    continue
                }

                // Skip badge lines
                if (line.contains("img.shields.io") || line.contains("badge.svg") || line.startsWith("[![")) continue

                val matches = linkRegex.findAll(line).toList()
                if (matches.isEmpty()) continue

                // Prefer the first skill-looking link on the line
                for (m in matches) {
                    val rawLabel = m.groupValues[1]
                        .replace("*", "")
                        .replace("`", "")
                        .trim()
                    val rawHref = m.groupValues[2].trim()
                    if (rawLabel.length < 2 || rawHref.startsWith("#") || rawHref.startsWith("mailto:")) continue
                    if (isNonSkillCatalogHref(rawLabel, rawHref)) continue

                    val resolvedUrl = resolveCatalogHref(rawHref, repoBaseTreeUrl, originUrl)
                    if (!resolvedUrl.startsWith("http://") && !resolvedUrl.startsWith("https://")) continue
                    if (!seenUrls.add(resolvedUrl.lowercase())) continue

                    val descClean = extractCatalogDescriptionFromLine(line, m.value, currentCategory)
                    val eco = inferEcosystemFromUrlAndText(resolvedUrl, "$rawLabel $currentCategory $descClean")
                    out.add(
                        CatalogSkillStub(
                            title = rawLabel.take(75),
                            url = resolvedUrl,
                            category = currentCategory,
                            description = descClean.take(260),
                            ecosystem = eco
                        )
                    )
                    break
                }
            }
        }
        return out
    }

    private fun isNonSkillCatalogHref(label: String, href: String): Boolean {
        val h = href.lowercase()
        val l = label.lowercase()
        val badExtensions = listOf(".png", ".jpg", ".jpeg", ".gif", ".svg", ".webp", ".ico", ".mp4")
        if (badExtensions.any { h.substringBefore('?').endsWith(it) }) return true
        val badFiles = listOf("license", "contributing", "code_of_conduct", "security.md", "changelog")
        if (badFiles.any { h.endsWith(it) || l == it }) return true
        val badDomains = listOf(
            "shields.io", "twitter.com", "x.com", "discord.gg", "discord.com",
            "youtube.com", "youtu.be", "linkedin.com", "buymeacoffee.com", "ko-fi.com", "npmjs.com"
        )
        if (badDomains.any { h.contains(it) }) return true
        val badLabels = listOf("back to top", "license", "mit", "apache", "star", "fork", "issues", "pull requests", "website", "docs", "home")
        if (l in badLabels) return true
        return false
    }

    private fun resolveCatalogHref(href: String, repoBaseTreeUrl: String, originUrl: String): String {
        if (href.startsWith("http://") || href.startsWith("https://")) return href
        val base = repoBaseTreeUrl.ifBlank { originUrl.trimEnd('/') }.trimEnd('/')
        if (!base.startsWith("http")) return href
        val cleanRel = href.removePrefix("./").trimStart('/')
        return if (base.contains("/tree/")) {
            "$base/$cleanRel"
        } else {
            "$base/tree/main/$cleanRel"
        }
    }

    private fun extractCatalogDescriptionFromLine(line: String, matchedLink: String, category: String): String {
        val after = line.substringAfter(matchedLink, "")
            .replace("|", " ")
            .replace(Regex("""\[([^]]+)]\([^)]+\)"""), "$1")
            .trimStart(' ', '-', '–', '—', ':', '*', '|')
            .trim()
        return if (after.length >= 6) {
            "[$category] $after"
        } else {
            "$category skill from curated repository"
        }
    }

    private fun inferEcosystemFromUrlAndText(url: String, text: String): String {
        val lower = "$url $text".lowercase()
        return when {
            lower.contains("openclaw") || lower.contains("clawhub") -> "OpenClaw"
            lower.contains("anthropics/") || lower.contains("claude") -> "Anthropic / Claude"
            lower.contains("gemini") || lower.contains("google") -> "Google Gemini"
            lower.contains("mcp") -> "MCP / Agent"
            else -> "Catalog Index"
        }
    }

    private fun buildCatalogStubMarkdown(cat: CatalogSkillStub): String = buildString {
        append("---\n")
        append("name: \"${cat.title.replace("\"", "'")}\"\n")
        append("description: \"${cat.description.replace("\"", "'")}\"\n")
        append("category: \"${cat.category.replace("\"", "'")}\"\n")
        append("source_url: \"${cat.url}\"\n")
        append("lazy_fetch: true\n")
        append("---\n\n")
        append("# ${cat.title}\n\n")
        append("Category: ${cat.category}\n\n")
        append("${cat.description}\n\n")
        append("## Source Reference\n")
        append("Remote Skill URL: ${cat.url}\n")
        append("Note: When loaded via `skill_load`, full SKILL.md instructions are automatically fetched & cached on demand.\n")
    }

    /**
     * Zero-Token On-Device Smart Skill Auto-Matcher (BM25-style weighted matching + Bangla semantic expansion).
     * Runs locally in < 10ms even across 3,000+ imported skills without calling any LLM API.
     */
    fun matchRelevantSkills(
        query: String,
        allSkills: List<ImportedSkillEntity>,
        limit: Int = 5
    ): List<SkillMatchResult> {
        if (allSkills.isEmpty()) return emptyList()
        val enabled = allSkills.filter { it.enabled }
        if (enabled.isEmpty()) return emptyList()

        val expandedTerms = expandQueryTerms(query)
        if (expandedTerms.isEmpty()) {
            return enabled.take(limit).map { SkillMatchResult(it, 1, emptyList()) }
        }

        val normalizedQuery = query.trim().lowercase()
        val scored = ArrayList<SkillMatchResult>(enabled.size)

        for (sk in enabled) {
            val titleLower = sk.title.lowercase()
            val slugLower = sk.slug.lowercase().replace('_', ' ')
            val descLower = sk.description.lowercase()
            val ecoLower = sk.ecosystem.lowercase()

            var score = 0
            val matched = mutableListOf<String>()

            if (normalizedQuery.length >= 3 && (titleLower.contains(normalizedQuery) || slugLower.contains(normalizedQuery))) {
                score += 24
            }

            for ((term, weight) in expandedTerms) {
                var hit = false
                if (titleLower.contains(term)) {
                    score += 7 * weight
                    hit = true
                }
                if (slugLower.contains(term)) {
                    score += 6 * weight
                    hit = true
                }
                if (descLower.contains(term)) {
                    score += 3 * weight
                    hit = true
                }
                if (ecoLower.contains(term)) {
                    score += 1 * weight
                    hit = true
                }
                if (hit && matched.size < 5) {
                    matched.add(term)
                }
            }

            if (score > 0) {
                // Slight tie-breaker bonus for already-hydrated full skills over lazy stubs
                val hydratedBonus = if (sk.wordCount >= 120) 2 else 0
                scored.add(SkillMatchResult(sk, score + hydratedBonus, matched))
            }
        }

        if (scored.isEmpty()) return emptyList()

        scored.sortWith(
            compareByDescending<SkillMatchResult> { it.score }
                .thenByDescending { it.skill.wordCount }
                .thenBy { it.skill.title }
        )
        return scored.take(limit)
    }

    private fun expandQueryTerms(rawQuery: String): Map<String, Int> {
        val lower = rawQuery.lowercase()
        val terms = linkedMapOf<String, Int>()
        val stopWords = setOf(
            "the", "and", "for", "with", "this", "that", "from", "into", "your", "use", "using",
            "please", "make", "create", "open", "do", "run", "করো", "করে", "দিয়ে", "দিয়ে", "থেকে",
            "আমার", "একটা", "একটি", "জন্য", "এবং", "হবে", "দাও", "নাও", "তুমি", "এটা", "কাজ"
        )

        lower.split(Regex("""[^\p{L}\p{N}_-]+"""))
            .map { it.trim() }
            .filter { it.length >= 2 && it !in stopWords }
            .forEach { token ->
                terms[token] = 2
            }

        val semanticMap = listOf(
            listOf("pdf", "পিডিএফ") to listOf("pdf", "document", "extract", "report"),
            listOf("excel", "xlsx", "csv", "sheet", "spreadsheet", "এক্সেল", "শিট", "হিসাব") to listOf("xlsx", "excel", "csv", "spreadsheet", "data"),
            listOf("word", "docx", "doc", "ওয়ার্ড", "ডকুমেন্ট", "ডক") to listOf("docx", "word", "document"),
            listOf("ppt", "pptx", "slides", "presentation", "পাওয়ারপয়েন্ট", "স্লাইড") to listOf("pptx", "presentation", "slides"),
            listOf("browser", "web", "chrome", "site", "website", "url", "search", "google", "internet", "ব্রাউজার", "ওয়েব", "ক্রোম", "সার্চ", "ইন্টারনেট") to listOf("browser", "playwright", "web", "scraping", "automation", "webapp", "testing", "search"),
            listOf("form", "fill", "apply", "signup", "register", "ফর্ম", "ফিলাপ", "পূরণ", "আবেদন", "সাবমিট") to listOf("form", "testing", "automation", "ops", "playwright", "webapp", "browser"),
            listOf("email", "gmail", "mail", "outreach", "ইমেইল", "জিমেইল", "মেইল") to listOf("email", "cold-email", "gmail", "outreach", "copywriting"),
            listOf("account", "login", "signup", "অ্যাকাউন্ট", "লগইন", "সাইন") to listOf("browser", "automation", "auth", "playwright", "testing"),
            listOf("facebook", "tiktok", "youtube", "instagram", "social", "monetization", "ফেসবুক", "টিকটক", "ইউটিউব", "সোশ্যাল", "মনিটাইজেশন") to listOf("social", "marketing", "content", "copywriting", "video", "media"),
            listOf("whatsapp", "হোয়াটসঅ্যাপ", "হোয়াটসঅ্যাপ", "গ্রুপ") to listOf("whatsapp", "chat", "messaging"),
            listOf("android", "app", "mobile", "phone", "অ্যান্ড্রয়েড", "অ্যাপ", "ফোন") to listOf("android", "mobile", "kotlin", "compose", "automation"),
            listOf("design", "ui", "html", "css", "frontend", "ডিজাইন", "ওয়েবপেজ") to listOf("frontend", "design", "ui", "tailwind", "html"),
            listOf("python", "script", "code", "api", "mcp", "কোড", "পাইথন", "স্ক্রিপ্ট") to listOf("python", "mcp", "api", "script", "code", "dev"),
            listOf("plan", "research", "brainstorm", "পরিকল্পনা", "রিসার্চ") to listOf("planning", "brainstorming", "research", "workflow")
        )

        for ((triggers, expansions) in semanticMap) {
            if (triggers.any { lower.contains(it) }) {
                expansions.forEach { exp ->
                    terms.putIfAbsent(exp, 1)
                }
            }
        }
        return terms
    }

    /**
     * Hydrates (if lazy remote stub) and returns a compact actionable snippet for Zero-Token Bot pre-warming.
     */
    suspend fun loadCompactPlaybookForBot(
        slug: String,
        query: String,
        maxChars: Int = 1800
    ): Result<String> = loadSkillForAgent(slugOrTitle = slug, query = query, charOffset = 0, maxChars = maxChars)

    suspend fun loadSkillForAgent(
        slugOrTitle: String,
        query: String = "",
        charOffset: Int = 0,
        maxChars: Int = 6500
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val all = repository.getAllImportedSkillsSnapshot()
            val q = slugOrTitle.trim().lowercase()
            var skill = all.firstOrNull {
                it.slug.lowercase() == q ||
                    it.title.lowercase().contains(q) ||
                    it.slug.lowercase().contains(q)
            } ?: error("Imported skill '$slugOrTitle' not found.")

            val file = workspace.resolveSafe(skill.entryPath)
            require(file.exists() && file.isFile) { "Skill entry missing on disk: ${skill.entryPath}" }
            var fullText = file.readText()

            // On-Demand Lazy Hydration for catalog-indexed skills
            if (charOffset == 0 && fullText.contains("lazy_fetch: true")) {
                val remoteUrl = Regex("(?m)^source_url:\\s*\"?([^\"\\n]+)\"?").find(fullText)?.groupValues?.getOrNull(1)?.trim()
                if (!remoteUrl.isNullOrBlank()) {
                    val hydrated = runCatching { fetchRemoteSkillMarkdown(remoteUrl) }.getOrNull()
                    if (!hydrated.isNullOrBlank() && hydrated.length > 120) {
                        file.writeText(hydrated)
                        fullText = hydrated
                        val reparsed = analyzeSkillContent(hydrated, skill.title, "SKILL.md")
                        skill = skill.copy(
                            description = reparsed.description.take(280),
                            toolMappingSummary = reparsed.toolMappingSummary.take(700),
                            tocSummary = reparsed.tocSummary.take(900),
                            wordCount = reparsed.wordCount,
                            lineCount = reparsed.lineCount
                        )
                        repository.upsertImportedSkill(skill)
                    }
                }
            }

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

    private fun fetchRemoteSkillMarkdown(sourceUrl: String): String? {
        val gh = parseGitHubUrl(sourceUrl)
        if (gh != null) {
            val (owner, repo, branchHint, subPath, isBlob) = gh
            val branches = listOfNotNull(branchHint.takeIf { it.isNotBlank() }, "main", "master").distinct()
            if (isBlob) {
                for (br in branches) {
                    val raw = "https://raw.githubusercontent.com/$owner/$repo/$br/$subPath"
                    val res = runCatching { downloadText(raw) }.getOrNull()
                    if (!res.isNullOrBlank()) return res
                }
            } else {
                val cleanSub = subPath.trim('/')
                val candidateFiles = if (cleanSub.isBlank()) {
                    listOf("SKILL.md", "skill.md", "README.md")
                } else {
                    listOf("$cleanSub/SKILL.md", "$cleanSub/skill.md", "$cleanSub/README.md")
                }
                for (br in branches) {
                    for (cf in candidateFiles) {
                        val raw = "https://raw.githubusercontent.com/$owner/$repo/$br/$cf"
                        val res = runCatching { downloadText(raw) }.getOrNull()
                        if (!res.isNullOrBlank()) return res
                    }
                }
            }
            return null
        }
        return runCatching { downloadText(sourceUrl) }.getOrNull()
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

    suspend fun clearAllSkills(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val skillsRoot = workspace.resolveSafe("skills")
            if (skillsRoot.exists()) {
                skillsRoot.deleteRecursively()
            }
            skillsRoot.mkdirs()
            repository.clearAllImportedSkills()
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

        if (raw.trimStart().startsWith("---")) {
            val fmMatch = Regex("^---\\s*\\n([\\s\\S]*?)\\n---").find(raw.trimStart())
            val fmBlock = fmMatch?.groupValues?.getOrNull(1).orEmpty()
            fmName = Regex("(?m)^name:\\s*[\"']?(.+?)[\"']?\\s*$").find(fmBlock)?.groupValues?.getOrNull(1).orEmpty().trim()
            fmDesc = Regex("(?m)^description:\\s*[\"']?(.+?)[\"']?\\s*$").find(fmBlock)?.groupValues?.getOrNull(1).orEmpty().trim()
            fmTools = Regex("(?m)^allowed-tools:\\s*(.+)$").find(fmBlock)?.groupValues?.getOrNull(1).orEmpty().trim()
        }

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
        val entity = buildSkillEntity(slug, skillDir, entryFile, parsed, originUrl)
        repository.upsertImportedSkill(entity)
        return entity
    }

    private fun buildSkillEntity(
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
        val bundledSummary = allFiles.take(15).joinToString(", ")

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

        return ImportedSkillEntity(
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

    private fun isExplicitSkillManifestName(name: String): Boolean {
        val lower = name.lowercase()
        return lower == "skill.md" || lower == "openclaw.json" || lower == "skill.json" || lower == "manifest.json"
    }

    private fun priorityRankForManifest(name: String): Int = when (name.lowercase()) {
        "skill.md" -> 0
        "openclaw.json" -> 1
        "skill.json" -> 2
        else -> 3
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

    private fun allocateFastUniqueSlug(title: String, usedSlugs: MutableSet<String>): String {
        val base = title.lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .take(38)
            .ifBlank { "skill" }
        var candidate = base
        var idx = 2
        while (candidate in usedSlugs || workspace.resolveSafe("skills/$candidate").exists()) {
            candidate = "${base.take(32)}_$idx"
            idx++
        }
        usedSlugs.add(candidate)
        return candidate
    }

    private fun readBoundedEntryBytes(zis: ZipInputStream, maxBytes: Int): ByteArray? {
        val buffer = ByteArray(8192)
        val out = java.io.ByteArrayOutputStream()
        var total = 0
        while (true) {
            val read = zis.read(buffer)
            if (read <= 0) break
            total += read
            if (total > maxBytes) return null
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
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
