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
}
