package com.example

import com.example.runtime.LocalPythonEngine
import com.example.runtime.VirtualWorkspaceManager
import com.example.security.CommandSecurityGuard
import com.example.security.CommandValidationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentCoreUnitTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun commandSecurityGuard_blocksDestructiveAndAllowsSafe() {
        assertTrue(CommandSecurityGuard.validateShellCommand("ls -la") is CommandValidationResult.Allowed)
        assertTrue(CommandSecurityGuard.validateShellCommand("rm -rf /") is CommandValidationResult.Blocked)
        assertTrue(CommandSecurityGuard.validateShellCommand("rm old_file.txt") is CommandValidationResult.RequiresConfirmation)
        assertTrue(CommandSecurityGuard.containsSensitiveCredential("my otp code is 1234"))
    }

    @Test
    fun virtualWorkspace_preventsPathTraversalAndCreatesFiles() {
        val workspace = VirtualWorkspaceManager(tempFolder.newFolder("files"))
        workspace.writeTextFile("workspace/test_note.txt", "hello agent").getOrThrow()
        val readBack = workspace.readTextFile("workspace/test_note.txt").getOrThrow()
        assertEquals("hello agent", readBack)

        var threw = false
        try {
            workspace.resolveSafe("../../etc/passwd")
        } catch (_: IllegalArgumentException) {
            threw = true
        }
        assertTrue("Expected path traversal rejection", threw)
    }

    @Test
    fun localPythonEngine_executesRealPythonCode() {
        val workspace = VirtualWorkspaceManager(tempFolder.newFolder("py_files"))
        val python = LocalPythonEngine(workspace)
        val code = """
            def fib(n):
                if n <= 1:
                    return n
                return fib(n - 1) + fib(n - 2)
            print("fib6=", fib(6))
        """.trimIndent()
        val res = python.executeScript(code)
        assertTrue("Python execution failed: ${res.stderr}", res.success)
        assertTrue("Expected fib6= 8 in stdout but got: ${res.stdout}", res.stdout.contains("fib6= 8"))
    }

    @Test
    fun humanChallengeAndUploads_workSafely() {
        val captcha = CommandSecurityGuard.detectHumanChallenge("Please complete reCAPTCHA: I'm not a robot", "https://example.com")
        assertEquals("CAPTCHA", captcha?.first)

        val otp = CommandSecurityGuard.detectHumanChallenge("Enter OTP verification code sent to your phone")
        assertEquals("OTP / 2FA", otp?.first)

        val workspace = VirtualWorkspaceManager(tempFolder.newFolder("upload_root"))
        val saved = workspace.saveUploadedFile("my cv (final).txt", "Kotlin Android Engineer".toByteArray()).getOrThrow()
        assertTrue(saved.exists())
        assertTrue(workspace.relativePathOf(saved).startsWith("uploads/"))

        assertTrue(com.example.agent.AgentSkillRegistry.skills.any {
            it.category == com.example.agent.SkillCategory.VIRTUAL_HANDS
        })
    }

    @Test
    fun keyPoolAndSkills_areConfiguredCorrectly() {
        assertEquals(5, com.example.security.SecureKeyStore.geminiSlots.size)
        assertEquals("enc_gemini_api_key", com.example.security.SecureKeyStore.SecretSlot.GEMINI_API_KEY.prefKey)
        assertEquals("enc_gemini_api_key_5", com.example.security.SecureKeyStore.SecretSlot.GEMINI_API_KEY_5.prefKey)
        assertTrue(com.example.agent.AgentSkillRegistry.skills.any { it.id == "vh_app_install" })
        assertTrue(com.example.agent.AgentSkillRegistry.skills.any { it.id == "github_api_skill" })
        assertTrue(com.example.agent.AgentSkillRegistry.skills.any { it.id == "auto_account_creator" })
        assertTrue(com.example.agent.AgentSkillRegistry.skills.any { it.id == "vh_fast_upload" })
    }

    @Test
    fun universalSkillAdapter_parsesAnthropicOpenClawZipAndMassiveDocs() = kotlinx.coroutines.runBlocking {
        val workspace = VirtualWorkspaceManager(tempFolder.newFolder("skill_ws"))
        val inMemoryStore = object : com.example.agent.UniversalSkillManager.SkillStore {
            val map = linkedMapOf<String, com.example.storage.ImportedSkillEntity>()
            override suspend fun getAllImportedSkillsSnapshot() = map.values.toList()
            override suspend fun getImportedSkillBySlug(slug: String) = map[slug]
            override suspend fun upsertImportedSkill(skill: com.example.storage.ImportedSkillEntity) {
                map[skill.slug] = skill
            }
            override suspend fun deleteImportedSkill(slug: String) {
                map.remove(slug)
            }
        }
        val manager = com.example.agent.UniversalSkillManager(
            workspace = workspace,
            repository = inMemoryStore,
            auditLogger = com.example.security.NetworkAuditLogger()
        )

        val anthropicSkill = """
            ---
            name: pdf-research-pro
            description: Extract tables and summarize massive reports
            allowed-tools: Bash, Read, WebFetch, Playwright
            ---
            # PDF Research Pro
            ## Workflow
            Use Playwright to open portal and Bash to transform CSV files.
        """.trimIndent()

        val importedAnthropic = manager.importAuto(anthropicSkill).getOrThrow()
        assertEquals("Anthropic / Claude", importedAnthropic.ecosystem)
        assertTrue(importedAnthropic.toolMappingSummary.contains("browser_open_and_read"))
        assertTrue(importedAnthropic.toolMappingSummary.contains("shell_exec"))

        val massiveSkill = buildString {
            append("---\nname: massive-auto-skill\ndescription: Huge multi-section workflow\n---\n# Massive Skill\n")
            repeat(1300) { idx ->
                if (idx % 150 == 0) append("\n## Section $idx Deep Guide\n")
                append("OpenClaw automation token_marker_$idx step alpha beta gamma delta epsilon zeta eta ")
            }
        }
        val importedMassive = manager.importAuto(massiveSkill).getOrThrow()
        assertEquals("OpenClaw", importedMassive.ecosystem)
        assertTrue("Expected >12,000 words, got ${importedMassive.wordCount}", importedMassive.wordCount > 12_000)

        val sectionHit = manager.loadSkillForAgent(importedMassive.slug, query = "token_marker_950", maxChars = 3500).getOrThrow()
        assertTrue(sectionHit.contains("token_marker_950"))
        assertTrue(sectionHit.contains("AUTO-ADAPTED TOOL MAPPING"))

        val pageOne = manager.loadSkillForAgent(importedMassive.slug, charOffset = 0, maxChars = 3500).getOrThrow()
        assertTrue(pageOne.contains("More content available"))

        // ZIP skill bundle extraction test
        val zipBytes = java.io.ByteArrayOutputStream().apply {
            java.util.zip.ZipOutputStream(this).use { zos ->
                zos.putNextEntry(java.util.zip.ZipEntry("bundle/SKILL.md"))
                zos.write("---\nname: zip-claude-bundle\ndescription: Packaged skill\n---\n# Bundled\nUse scripts/run.py".toByteArray())
                zos.closeEntry()
                zos.putNextEntry(java.util.zip.ZipEntry("bundle/scripts/run.py"))
                zos.write("print('ok')".toByteArray())
                zos.closeEntry()
            }
        }.toByteArray()
        val uploadedZip = workspace.saveUploadedFile("custom_skill.zip", zipBytes).getOrThrow()
        val importedZip = manager.importAuto(workspace.relativePathOf(uploadedZip)).getOrThrow()
        assertEquals("Anthropic / Claude", importedZip.ecosystem)
        assertTrue(importedZip.bundledFilesSummary.contains("scripts/run.py"))

        manager.deleteSkill(importedZip.slug).getOrThrow()
        assertEquals(2, inMemoryStore.getAllImportedSkillsSnapshot().size)
    }

    @Test
    fun cadEngine_generatesValidDxfSvgHtmlAnd3dStl() {
        val workspace = VirtualWorkspaceManager(tempFolder.newFolder("cad_ws"))
        val cadEngine = com.example.runtime.CadDrawingEngine(workspace)

        val floorPlanSummary = cadEngine.generateCadProject(
            slug = "duplex_plan",
            title = "Duplex Floor Plan",
            units = "cm",
            template = "floor_plan"
        ).getOrThrow()

        val dxfText = workspace.readTextFile(floorPlanSummary.dxfPath).getOrThrow()
        assertTrue(dxfText.contains("SECTION"))
        assertTrue(dxfText.contains("ENTITIES"))
        assertTrue(dxfText.contains("MASTER BEDROOM"))
        assertTrue(dxfText.contains("EOF"))

        val svgText = workspace.readTextFile(floorPlanSummary.svgPath).getOrThrow()
        assertTrue(svgText.contains("<svg"))
        assertTrue(svgText.contains("Duplex Floor Plan"))

        val htmlText = workspace.readTextFile(floorPlanSummary.viewerHtmlPath).getOrThrow()
        assertTrue(htmlText.contains("Zoom +"))

        val mech3dSummary = cadEngine.generateCadProject(
            slug = "custom_flange_3d",
            title = "Mounting Flange",
            units = "mm",
            entitiesJson = """
                [
                  {"type":"rect","x":0,"y":0,"w":120,"h":80,"layer":"OUTLINE"},
                  {"type":"circle","cx":60,"cy":40,"r":18,"layer":"HOLES"},
                  {"type":"dimension","x1":0,"y1":0,"x2":120,"y2":0,"offset":-12,"label":"120.0 mm"}
                ]
            """.trimIndent(),
            extrudeHeight3d = 12.0
        ).getOrThrow()

        val stlPath = mech3dSummary.stlPath
        assertTrue("Expected 3D STL path to be generated", stlPath != null)
        val stlText = workspace.readTextFile(stlPath!!).getOrThrow()
        assertTrue(stlText.startsWith("solid custom_flange_3d"))
        assertTrue(stlText.contains("endsolid custom_flange_3d"))
    }

    @Test
    fun securitySanitize_stripsInvisibleDirectionalCharsAndBearer() {
        val dirty = "\u200E\uFEFF  Bearer sk-or-v1-abc123\u200B\n  "
        val clean = com.example.security.SecureKeyStore.sanitizeSecret(dirty)
        assertEquals("sk-or-v1-abc123", clean)
    }

    @Test
    fun pdfExtractorAndPyLite_readCompressedPdfWithOpenAndExceptAsE() {
        val workspace = VirtualWorkspaceManager(tempFolder.newFolder("pdf_py_ws"))
        // Build a genuine compressed FlateDecode PDF byte stream
        val contentOp = "BT\n/F1 12 Tf\n72 720 Td\n(Curriculum Vitae - Rableu Mallik) Tj\nT*\n(Senior Android & AI Engineer) Tj\nET"
        val deflater = java.util.zip.Deflater()
        deflater.setInput(contentOp.toByteArray(Charsets.ISO_8859_1))
        deflater.finish()
        val deflated = java.io.ByteArrayOutputStream().also { bos ->
            val buf = ByteArray(256)
            while (!deflater.finished()) {
                val n = deflater.deflate(buf)
                bos.write(buf, 0, n)
            }
            deflater.end()
        }.toByteArray()

        val pdfOut = java.io.ByteArrayOutputStream()
        pdfOut.write("%PDF-1.4\n1 0 obj << /Length ${deflated.size} /Filter /FlateDecode >>\nstream\n".toByteArray(Charsets.ISO_8859_1))
        pdfOut.write(deflated)
        pdfOut.write("\nendstream\nendobj\n%%EOF".toByteArray(Charsets.ISO_8859_1))

        val savedPdf = workspace.saveUploadedFile("cv_test.pdf", pdfOut.toByteArray()).getOrThrow()
        val relPdf = workspace.relativePathOf(savedPdf)

        val readViaWorkspace = workspace.readTextFile(relPdf).getOrThrow()
        assertTrue("Expected CV name in extracted PDF text, got: $readViaWorkspace", readViaWorkspace.contains("Rableu Mallik"))
        assertTrue(readViaWorkspace.contains("Senior Android"))

        // Verify PyLite handles `with open(..., "rb") as f:`, `except Exception as e:`, and `os.path.exists`
        val python = LocalPythonEngine(workspace)
        val pyRes = python.executeScript(
            """
            import os
            try:
                with open("$relPdf", "rb") as f:
                    txt = f.read()
                print("EXISTS=", os.path.exists("$relPdf"))
                print("LEN=", len(txt))
                print("FOUND=", "Rableu Mallik" in txt)
                x = '"'
                print("QUOTE=", x)
                raise_err = undefined_var_trigger
            except Exception as e:
                print("CAUGHT=", e)
            """.trimIndent()
        )
        assertTrue("PyLite script failed: ${pyRes.stderr}", pyRes.success)
        assertTrue(pyRes.stdout.contains("EXISTS= True"))
        assertTrue(pyRes.stdout.contains("FOUND= True"))
        assertTrue(pyRes.stdout.contains("CAUGHT="))
    }

    @Test
    fun pyLiteUpgrades_supportBreakContinueClassListCompCsvAndBuiltins() {
        val workspace = VirtualWorkspaceManager(tempFolder.newFolder("py_up_ws"))
        workspace.writeTextFile("workspace/jobs.csv", "role,salary\nAndroid Dev,120000\n\"ML, AI Engineer\",150000\n").getOrThrow()
        val python = LocalPythonEngine(workspace)

        val script = """
            import csv

            class Candidate:
                def __init__(self, name, exp):
                    self.name = name
                    self.exp = exp

                def summary(self):
                    return f"{self.name}:{self.exp}"

            c = Candidate("Rableu", 5)
            c.exp += 1
            print("CAND=", c.summary())

            nums = [1, 2, 3, 4, 5, 6]
            picked = []
            for n in nums:
                if n == 2:
                    continue
                if n == 5:
                    break
                picked.append(n)
            print("LOOP=", picked)

            evens_sq = [x * x for x in nums if x % 2 == 0]
            print("COMP=", evens_sq)

            with open("workspace/jobs.csv", "r") as f:
                rows = csv.DictReader(f)
            print("CSV_ROLE1=", rows[1]["role"])
            print("ZIP=", zip([1, 2], ["a", "b"]))
            print("ANY_ALL=", any([False, True]), all([True, True]))
        """.trimIndent()

        val res = python.executeScript(script)
        assertTrue("PyLite upgrade test failed: ${res.stderr}", res.success)
        assertTrue("Got: ${res.stdout}", res.stdout.contains("CAND= Rableu:6"))
        assertTrue("Got: ${res.stdout}", res.stdout.contains("LOOP= [1, 3, 4]"))
        assertTrue("Got: ${res.stdout}", res.stdout.contains("COMP= [4, 16, 36]"))
        assertTrue("Got: ${res.stdout}", res.stdout.contains("CSV_ROLE1= ML, AI Engineer"))
        assertTrue("Got: ${res.stdout}", res.stdout.contains("ZIP= [[1, a], [2, b]]"))
        assertTrue("Got: ${res.stdout}", res.stdout.contains("ANY_ALL= True True"))
    }

    @Test
    fun webServer_normalizesUrlsAndSupportsCadAndDocumentMimeTypes() {
        val workspace = VirtualWorkspaceManager(tempFolder.newFolder("web_ws"))
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO)
        val server = com.example.runtime.LocalWebServer(workspace, scope)

        assertEquals("http://127.0.0.1:8080/cad/house/house_viewer.html", server.urlFor("/cad/house/house_viewer.html"))
        assertEquals("image/svg+xml", com.example.runtime.LocalWebServer.mimeTypeForExtension("svg"))
        assertEquals("application/dxf", com.example.runtime.LocalWebServer.mimeTypeForExtension("dxf"))
        assertEquals("model/stl", com.example.runtime.LocalWebServer.mimeTypeForExtension("stl"))
        assertEquals("application/pdf", com.example.runtime.LocalWebServer.mimeTypeForExtension("pdf"))
        assertEquals("image/webp", com.example.runtime.LocalWebServer.mimeTypeForExtension("webp"))
        assertEquals("application/wasm", com.example.runtime.LocalWebServer.mimeTypeForExtension("wasm"))
    }

    @Test
    fun aiMessageAndAttachment_preserveMultimodalImages() {
        val img = com.example.ai.AiImageAttachment(
            displayName = "scanned_cv.pdf (Page 1)",
            mimeType = "image/jpeg",
            base64Data = "AQID"
        )
        val msg = com.example.ai.AiMessage(
            role = "user",
            content = "Read this scanned CV",
            imageAttachments = listOf(img)
        )
        assertEquals(1, msg.imageAttachments.size)
        assertEquals("image/jpeg", msg.imageAttachments.first().mimeType)
    }

    @Test
    fun autoPopupReflexBot_safeDismissalAndProtectionRules() {
        fun node(text: String, clickable: Boolean = true) = com.example.accessibility.ObservedUiNode(
            text = text,
            contentDesc = "",
            viewId = "",
            className = "Button",
            bounds = "[0,0][200,400]",
            centerX = 100f,
            centerY = 200f,
            clickable = clickable,
            editable = false
        )

        val perm = com.example.agent.ZeroLatencyBotEngine.findSafePopupDismissTarget(
            "com.google.android.permissioncontroller",
            listOf(node("Allow App to access photos?", clickable = false), node("While using the app"), node("Don't allow"))
        )
        assertEquals("While using the app", perm?.text)

        val skip = com.example.agent.ZeroLatencyBotEngine.findSafePopupDismissTarget(
            "com.zhiliaoapp.musically",
            listOf(node("Promo", clickable = false), node("Skip Ad"), node("Buy Pro"))
        )
        assertEquals("Skip Ad", skip?.text)

        val contextualCancel = com.example.agent.ZeroLatencyBotEngine.findSafePopupDismissTarget(
            "com.example.movieapp",
            listOf(node("Update available later?", clickable = false), node("Update"), node("Cancel"))
        )
        assertEquals("Cancel", contextualCancel?.text)

        val uploadCancel = com.example.agent.ZeroLatencyBotEngine.findSafePopupDismissTarget(
            "com.google.android.youtube",
            listOf(node("Uploading video 60%", clickable = false), node("Cancel"))
        )
        assertEquals(null, uploadCancel)

        val protectedWallet = com.example.agent.ZeroLatencyBotEngine.findSafePopupDismissTarget(
            "com.bKash.customerapp",
            listOf(node("Skip"), node("Not now"))
        )
        assertEquals(null, protectedWallet)
    }

    @Test
    fun geminiProvider_prioritizesStableFreeFlashAndClearCache() {
        val logger = com.example.security.NetworkAuditLogger()
        val gemini = com.example.ai.GeminiProvider(logger)

        val flash25 = gemini.scoreGeminiModel("gemini-2.5-flash", com.example.ai.TaskComplexity.REASONING, preferFast = true)
        val flash25Lite = gemini.scoreGeminiModel("gemini-2.5-flash-lite", com.example.ai.TaskComplexity.SIMPLE, preferFast = true)
        val flash20 = gemini.scoreGeminiModel("gemini-2.0-flash", com.example.ai.TaskComplexity.MULTISTEP_AGENT, preferFast = true)
        val preview3 = gemini.scoreGeminiModel("gemini-3-flash-preview", com.example.ai.TaskComplexity.REASONING, preferFast = true)
        val proExp = gemini.scoreGeminiModel("gemini-2.5-pro-exp", com.example.ai.TaskComplexity.MULTISTEP_AGENT, preferFast = true)

        assertTrue("Expected 2.5-flash ($flash25) > 3-flash-preview ($preview3)", flash25 > preview3)
        assertTrue("Expected 2.5-flash-lite ($flash25Lite) > 3-flash-preview ($preview3)", flash25Lite > preview3)
        assertTrue("Expected 2.0-flash ($flash20) > pro-exp ($proExp)", flash20 > proExp)
        gemini.clearCache()
    }

    @Test
    fun openRouterProvider_enforcesFreeModelsCooldownAndToolPriority() {
        val logger = com.example.security.NetworkAuditLogger()
        val orProvider = com.example.ai.OpenRouterProvider(logger)

        val catalog = listOf(
            com.example.ai.OpenRouterModelInfo("openai/gpt-4o", "GPT-4o", 128000, isFree = false, supportsTools = true),
            com.example.ai.OpenRouterModelInfo("openrouter/auto", "Auto", 128000, isFree = false, supportsTools = true),
            com.example.ai.OpenRouterModelInfo("qwen/qwen-2.5-72b-instruct:free", "Qwen Free", 32768, isFree = true, supportsTools = true),
            com.example.ai.OpenRouterModelInfo("google/gemma-3-27b-it:free", "Gemma 3 Free", 32768, isFree = true, supportsTools = false),
            com.example.ai.OpenRouterModelInfo("openrouter/free", "Free Router", 65536, isFree = true, supportsTools = false),
            com.example.ai.OpenRouterModelInfo("openai/text-embedding-3-small", "Embed", 8192, isFree = true, supportsTools = false)
        )

        val freeToolCandidates = orProvider.filterAndRankModels(
            models = catalog,
            freeOnly = true,
            complexity = com.example.ai.TaskComplexity.MULTISTEP_AGENT,
            requiresTools = true,
            preferFreeOnSimple = true
        )
        assertTrue(
            "Must NEVER pick paid models when freeOnly=true: $freeToolCandidates",
            !freeToolCandidates.contains("openai/gpt-4o") && !freeToolCandidates.contains("openrouter/auto")
        )
        assertTrue("Must exclude embedding models: $freeToolCandidates", !freeToolCandidates.contains("openai/text-embedding-3-small"))
        assertEquals(
            "Tool-capable free model must rank #1 ahead of openrouter/free",
            "qwen/qwen-2.5-72b-instruct:free",
            freeToolCandidates.first()
        )
        assertTrue("openrouter/free should remain in fallback candidates", freeToolCandidates.contains("openrouter/free"))

        // Verify markModelFailure demotes a broken free model in subsequent resolution
        orProvider.markModelFailure("qwen/qwen-2.5-72b-instruct:free", cooldownMs = 60_000L)
        val afterCooldown = orProvider.filterAndRankModels(
            models = catalog,
            freeOnly = true,
            complexity = com.example.ai.TaskComplexity.MULTISTEP_AGENT,
            requiresTools = true,
            preferFreeOnSimple = true
        )
        assertTrue("Failed model should be skipped while cooling down: $afterCooldown", afterCooldown.first() != "qwen/qwen-2.5-72b-instruct:free")
        orProvider.clearCache()
    }

    @Test
    fun pyLite_iteratesFileHandleLinesAndStringMethods() {
        val workspace = VirtualWorkspaceManager(tempFolder.newFolder("py_iter_ws"))
        workspace.writeTextFile("workspace/lines.txt", "alpha\nbeta\n123").getOrThrow()
        val python = LocalPythonEngine(workspace)
        val res = python.executeScript(
            """
            with open("workspace/lines.txt", "r") as f:
                collected = []
                for line in f:
                    collected.append(line.strip())
            print("LINES=", collected)
            print("DIGITS=", collected[2].isdigit())
            print("FIND=", "hello world".find("world"))
            print("COUNT=", "banana".count("na"))
            """.trimIndent()
        )
        assertTrue("PyLite line iteration failed: ${res.stderr}", res.success)
        assertTrue(res.stdout.contains("LINES= [alpha, beta, 123]"))
        assertTrue(res.stdout.contains("DIGITS= True"))
        assertTrue(res.stdout.contains("FIND= 6"))
        assertTrue(res.stdout.contains("COUNT= 2"))
    }

    @Test
    fun appUpdateChecker_detectsNewerVersionWithoutUninstall() {
        assertEquals(42L, com.example.runtime.AppUpdateChecker.extractRunNumber("main-42"))
        assertEquals(18L, com.example.runtime.AppUpdateChecker.extractRunNumber("1.0.18"))
        assertTrue(
            com.example.runtime.AppUpdateChecker.isRemoteNewer(
                installedVersionCode = 9_570_000L,
                installedVersionName = "1.0.6",
                remoteVersionCode = 9_570_120L,
                remoteVersionLabel = "main-7"
            )
        )
        assertTrue(
            !com.example.runtime.AppUpdateChecker.isRemoteNewer(
                installedVersionCode = 9_570_120L,
                installedVersionName = "1.0.7",
                remoteVersionCode = 9_570_120L,
                remoteVersionLabel = "main-7"
            )
        )
    }

    @Test
    fun whatsAppBot_parsesInChatCommandsAndDetectsLinksAndStickers() {
        val antiLinkOn = com.example.whatsapp.WaCommandParser.parse(".antilink on")
        assertEquals(com.example.whatsapp.WaCommandAction.ToggleAntiLink(true), antiLinkOn)

        val antiLinkOff = com.example.whatsapp.WaCommandParser.parse("!antilink off")
        assertEquals(com.example.whatsapp.WaCommandAction.ToggleAntiLink(false), antiLinkOff)

        val antiStickerOn = com.example.whatsapp.WaCommandParser.parse("/antisticker on")
        assertEquals(com.example.whatsapp.WaCommandAction.ToggleAntiSticker(true), antiStickerOn)

        val autoReplyBn = com.example.whatsapp.WaCommandParser.parse(".অটোরিপ্লাই অন")
        assertEquals(com.example.whatsapp.WaCommandAction.ToggleAutoReply(true), autoReplyBn)

        val askAi = com.example.whatsapp.WaCommandParser.parse(".ai আজকের আবহাওয়া কেমন?")
        assertEquals(com.example.whatsapp.WaCommandAction.AskAi("আজকের আবহাওয়া কেমন?"), askAi)

        assertTrue(com.example.whatsapp.WaModerationDetector.containsLink("Join here https://chat.whatsapp.com/ABC123"))
        assertTrue(com.example.whatsapp.WaModerationDetector.containsLink("Visit wa.me/8801700000000"))
        assertTrue(!com.example.whatsapp.WaModerationDetector.containsLink("Hello vai kemon acho"))

        assertTrue(com.example.whatsapp.WaModerationDetector.looksLikeStickerText("[Sticker]"))
        assertEquals("grp::tech squad", com.example.whatsapp.WaChatKeyHelper.normalize("  Tech   Squad ", true))
        assertEquals("dm::rahim", com.example.whatsapp.WaChatKeyHelper.normalize("Rahim", false))
    }

    @Test
    fun universalSkillManager_splitsMultiSkillZipAndIndexesCatalog1000PlusSkillsAndMatchesBangla() = kotlinx.coroutines.runBlocking {
        val workspace = VirtualWorkspaceManager(tempFolder.newFolder("bulk_skill_ws"))
        val dbMap = linkedMapOf<String, com.example.storage.ImportedSkillEntity>()
        val inMemoryStore = object : com.example.agent.UniversalSkillManager.SkillStore {
            override suspend fun getAllImportedSkillsSnapshot() = dbMap.values.toList()
            override suspend fun getImportedSkillBySlug(slug: String) = dbMap[slug]
            override suspend fun upsertImportedSkill(skill: com.example.storage.ImportedSkillEntity) {
                dbMap[skill.slug] = skill
            }
            override suspend fun upsertImportedSkills(skills: List<com.example.storage.ImportedSkillEntity>) {
                skills.forEach { dbMap[it.slug] = it }
            }
            override suspend fun deleteImportedSkill(slug: String) {
                dbMap.remove(slug)
            }
            override suspend fun clearAllImportedSkills() {
                dbMap.clear()
            }
        }
        val manager = com.example.agent.UniversalSkillManager(
            workspace = workspace,
            repository = inMemoryStore,
            auditLogger = com.example.security.NetworkAuditLogger()
        )

        // 1. Test multi-skill GitHub-style archive ZIP with 6 separate SKILL.md folders
        val multiBytes = java.io.ByteArrayOutputStream().use { baos ->
            java.util.zip.ZipOutputStream(baos).use { zos ->
                val folders = listOf(
                    "pdf" to "Extract, merge, and generate PDF documents.",
                    "docx" to "Create and edit formatted Word documents.",
                    "webapp-testing" to "Playwright browser form fill and UI testing.",
                    "cold-email" to "Write personalized outreach and Gmail emails.",
                    "xlsx" to "Spreadsheet formulas and Excel data tables.",
                    "frontend-design" to "Modern responsive web UI and HTML design."
                )
                for ((name, desc) in folders) {
                    zos.putNextEntry(java.util.zip.ZipEntry("skills-main/skills/$name/SKILL.md"))
                    zos.write("---\nname: \"$name\"\ndescription: \"$desc\"\n---\n# $name\n$desc\n".toByteArray())
                    zos.closeEntry()
                }
            }
            baos.toByteArray()
        }
        val multiRes = manager.importFromZipBytesBulk(multiBytes, "skills.zip", repoBaseTreeUrl = "https://github.com/anthropics/skills/tree/main")
        assertEquals(6, multiRes.importedCount)
        assertEquals(6, dbMap.size)

        // 2. Test 1,050-entry awesome-agent-skills catalog ZIP -> batch-indexed cleanly
        val catalogMd = buildString {
            append("# Awesome Agent Skills\n\n## Browser & Web\n")
            append("- [playwright-form-filler](https://github.com/example/playwright-form-filler) - Automated browser form filling and web clicking\n")
            append("## Productivity & Docs\n")
            for (i in 1..1050) {
                append("- [community-skill-$i](https://github.com/voltagent-demo/skill-$i) - Automated workflow helper #$i for tasks\n")
            }
        }
        val catalogZipBytes = java.io.ByteArrayOutputStream().use { baos ->
            java.util.zip.ZipOutputStream(baos).use { zos ->
                zos.putNextEntry(java.util.zip.ZipEntry("awesome-agent-skills-main/README.md"))
                zos.write(catalogMd.toByteArray())
                zos.closeEntry()
            }
            baos.toByteArray()
        }
        val catRes = manager.importFromZipBytesBulk(
            catalogZipBytes,
            "awesome-agent-skills.zip",
            repoBaseTreeUrl = "https://github.com/VoltAgent/awesome-agent-skills/tree/main"
        )
        assertTrue("Expected >= 1050 skills indexed, got ${catRes.importedCount}", catRes.importedCount >= 1050)
        assertTrue("Expected total db >= 1056, got ${dbMap.size}", dbMap.size >= 1056)

        // 3. Test Zero-Token Smart Matching with Bangla + English queries across 1,056 skills
        val allSkills = dbMap.values.toList()
        val pdfHits = manager.matchRelevantSkills("আমার একটা পিডিএফ ফাইল তৈরি করো", allSkills, limit = 5)
        assertTrue("Expected pdf skill in top hits: ${pdfHits.map { it.skill.slug }}", pdfHits.any { it.skill.slug.contains("pdf") })

        val emailHits = manager.matchRelevantSkills("প্রফেশনাল ইমেইল পাঠাও", allSkills, limit = 5)
        assertTrue("Expected email skill in top hits: ${emailHits.map { it.skill.slug }}", emailHits.any { it.skill.slug.contains("email") })

        val formHits = manager.matchRelevantSkills("ব্রাউজারে ওয়েব ফর্ম ফিলাপ করো", allSkills, limit = 5)
        assertTrue("Expected browser/form skill in top hits: ${formHits.map { it.skill.slug }}", formHits.any { it.skill.slug.contains("form") || it.skill.slug.contains("testing") })
    }
}
