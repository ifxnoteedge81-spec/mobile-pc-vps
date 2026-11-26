package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("AI Computer", appName)
  }

  @Test
  fun `device automation disables screenshot and validates share_file paths`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val controller = com.example.accessibility.DeviceAutomationController(context)
    val ss = controller.globalNav("screenshot")
    assert(ss.isFailure)

    val badShare = controller.sendShareOrIntent("share_file", "../../etc/passwd", "TikTok")
    assert(badShare.isFailure)
  }

  @Test
  fun `zero latency bot engine respects autonomous flag and returns safely`() = kotlinx.coroutines.runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val workspace = com.example.runtime.VirtualWorkspaceManager(context)
    val logger = com.example.security.NetworkAuditLogger()
    val browser = com.example.browser.BrowserController(workspace, logger)
    val device = com.example.accessibility.DeviceAutomationController(context)
    val bots = com.example.agent.ZeroLatencyBotEngine(device, browser)

    val disabled = bots.tryInstantOrPrewarm("home", emptyList(), fullAutonomousMode = false)
    assertEquals(null, disabled)

    // When Accessibility Service is disconnected in Robolectric, VirtualHandsActionBot cleanly falls back (null)
    val disconnected = bots.tryInstantOrPrewarm("home", emptyList(), fullAutonomousMode = true)
    assertEquals(null, disconnected)
  }

  @Test
  fun `autoPopupReflexBot dismisses safe dialogs but protects uploads, purchases, and protected apps`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val workspace = com.example.runtime.VirtualWorkspaceManager(context)
    val logger = com.example.security.NetworkAuditLogger()
    val browser = com.example.browser.BrowserController(workspace, logger)
    val device = com.example.accessibility.DeviceAutomationController(context)
    val bots = com.example.agent.ZeroLatencyBotEngine(device, browser)

    fun node(text: String, clickable: Boolean = true, x: Float = 100f) =
      com.example.accessibility.ObservedUiNode(
        text = text,
        contentDesc = "",
        viewId = "",
        className = "Button",
        bounds = "[0,0][200,500]",
        centerX = x,
        centerY = 500f,
        clickable = clickable,
        editable = false
      )

    // 1. Permission dialog -> picks While using the app
    val permHit = bots.findSafePopupDismissTarget(
      "com.google.android.permissioncontroller",
      listOf(
        node("Allow App to access photos?", clickable = false),
        node("While using the app"),
        node("Don't allow")
      )
    )
    assertEquals("While using the app", permHit?.text)

    // 2. Skip Ad / Later nag popup -> picks Skip Ad
    val skipHit = bots.findSafePopupDismissTarget(
      "com.zhiliaoapp.musically",
      listOf(node("Special Promo", clickable = false), node("Skip Ad"), node("Buy Pro"))
    )
    assertEquals("Skip Ad", skipHit?.text)

    // 3. Contextual Cancel in Update dialog -> picks Cancel
    val updateCancelHit = bots.findSafePopupDismissTarget(
      "com.example.movieapp",
      listOf(node("New version update available", clickable = false), node("Update"), node("Cancel"))
    )
    assertEquals("Cancel", updateCancelHit?.text)

    // 4. Upload progress Cancel (NO popup context) -> returns null (never cancels user upload!)
    val uploadCancelHit = bots.findSafePopupDismissTarget(
      "com.google.android.youtube",
      listOf(node("Uploading video 45%", clickable = false), node("Cancel"))
    )
    assertEquals(null, uploadCancelHit)

    // 5. Protected wallet/banking app -> returns null
    val walletHit = bots.findSafePopupDismissTarget(
      "com.bKash.customerapp",
      listOf(node("Not now"), node("Skip"))
    )
    assertEquals(null, walletHit)
  }

  @Test
  fun `multiMediaTargetBot stages thumbnail into MediaStore and validates share_multiple`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val workspace = com.example.runtime.VirtualWorkspaceManager(context)
    val savedImg = workspace.saveUploadedFile("thumb_test.jpg", byteArrayOf(1, 2, 3, 4)).getOrThrow()
    val rel = workspace.relativePathOf(savedImg)
    val controller = com.example.accessibility.DeviceAutomationController(context)

    val staged = controller.stageMediaToPublicGallery(rel)
    assert(staged.isSuccess)
    assert(staged.getOrThrow().displayName.contains("thumb_test.jpg"))

    val badMulti = controller.sendShareOrIntent("share_multiple", "../../secret.txt", "YouTube")
    assert(badMulti.isFailure)
  }

  @Test
  fun `gemini and openrouter request builders and response parsers handle free-tier edge cases`() {
    val logger = com.example.security.NetworkAuditLogger()
    val gemini = com.example.ai.GeminiProvider(logger)
    val orProvider = com.example.ai.OpenRouterProvider(logger)

    val bodyNoThinking = gemini.buildRequestBody(
      model = "gemini-2.5-flash",
      systemPrompt = "sys",
      messages = listOf(com.example.ai.AiMessage(role = "user", content = "hello")),
      tools = emptyList(),
      fastMode = true,
      includeThinkingConfig = false
    )
    assert(!bodyNoThinking.has("generationConfig"))

    val geminiResp = gemini.parseSingleResponse(
      """{"candidates":[{"finishReason":"STOP","content":{"parts":[{"text":"Ready"},{"functionCall":{"name":"file_read","args":{"path":"uploads/cv.pdf"}}}]}}]}""",
      "gemini-2.5-flash"
    )
    assertEquals("Ready", geminiResp.text)
    assertEquals(1, geminiResp.toolCalls.size)

    val orBody = orProvider.buildRequestBody(
      model = "qwen/qwen-2.5-72b-instruct:free",
      systemPrompt = "sys",
      messages = listOf(
        com.example.ai.AiMessage(
          role = "assistant",
          content = "",
          toolCalls = listOf(
            com.example.ai.ToolCallRequest(
              id = "call_1",
              name = "file_list",
              arguments = org.json.JSONObject().put("path", "uploads")
            )
          )
        )
      ),
      tools = emptyList(),
      stream = false
    )
    assertEquals("", orBody.getJSONArray("messages").getJSONObject(1).getString("content"))

    val orResp = orProvider.parseSingleResponse(
      """{"model":"qwen/qwen-2.5-72b-instruct:free","choices":[{"message":{"content":null,"tool_calls":[{"id":"c1","function":{"name":"file_read","arguments":"{\"path\":\"uploads/a.pdf\"}"}}]}}]}""",
      "qwen/qwen-2.5-72b-instruct:free"
    )
    assertEquals("uploads/a.pdf", orResp.toolCalls.first().arguments.getString("path"))
  }

  @Test
  fun `appUpdateChecker parses version json and Room migration 1 to 2 is registered`() {
    val info = com.example.runtime.AppUpdateChecker.parseVersionJson(
      rawJson = """
        {
          "version": "main-9",
          "versionCode": 9570500,
          "releaseDownloadUrl": "https://github.com/ifxnoteedge81-spec/mobile-pc-vps/releases/download/latest/personal-ai-computer.apk",
          "sha256": "abc123",
          "sizeBytes": 22000000,
          "changelog": ["Seamless In-Place Updates"]
        }
      """.trimIndent(),
      installedVersionCode = 9570000L,
      installedVersionName = "1.0.8"
    )
    assert(info.isUpdateAvailable)
    assertEquals(9570500L, info.latestVersionCode)
    assertEquals(1, com.example.storage.AppDatabase.MIGRATION_1_2.startVersion)
    assertEquals(2, com.example.storage.AppDatabase.MIGRATION_1_2.endVersion)
  }
}
