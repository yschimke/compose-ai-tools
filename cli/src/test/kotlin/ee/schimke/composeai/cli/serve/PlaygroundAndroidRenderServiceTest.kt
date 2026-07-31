package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.cli.PreviewManifest
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.Json
import okio.Path.Companion.toPath

/**
 * The Android first-frame render orchestrator: previews.json synthesis, render→await, and reading
 * the daemon's `renderFinished` PNG — driven against a fake render session (no real daemon
 * subprocess).
 */
class PlaygroundAndroidRenderServiceTest {

  private val json = Json { ignoreUnknownKeys = true }
  private val tmpDirs = mutableListOf<File>()

  private fun tmp(): File =
    java.nio.file.Files.createTempDirectory("android-render-test").toFile().also { tmpDirs += it }

  @AfterTest
  fun cleanup() {
    tmpDirs.forEach { it.deleteRecursively() }
  }

  private fun snippet(previewId: String = "com.example.SnippetKt.AndroidPreview") =
    PlaygroundTokenStore.PlaygroundSnippet(
      mode = PlaygroundMode.ANDROID,
      workDir = "/work".toPath(),
      classesDir = "/work/classes".toPath(),
      classpath = listOf("/catalog/app.jar".toPath(), "/work/classes".toPath()),
      moduleName = "playground-android",
      previewId = previewId,
    )

  @Test
  fun `a rendered snippet's first frame is returned, and previews_json is synthesized from its id`() {
    val fake = FakeRenderSession(renderRoot = tmp())
    var seenManifest: PreviewManifest? = null
    val svc =
      PlaygroundAndroidRenderService(
        openSession = { classesDir, previewsJson, _, userClasspath ->
          assertTrue(previewsJson.exists(), "the manifest is written before the session opens")
          seenManifest =
            json.decodeFromString(PreviewManifest.serializer(), previewsJson.readText())
          assertEquals("/work/classes", classesDir.path)
          assertTrue(
            userClasspath.any { it.endsWith("app.jar") },
            "catalog jars ride userClasspath",
          )
          fake
        },
        newWorkDir = { tmp() },
      )

    val png = svc.render(snippet())

    // The fake writes "png:<uiMode>:<locale>:<device>" as the frame; a no-overrides first frame is
    // all-null. That the bytes come back at all proves we read the `renderFinished` pngPath file.
    assertEquals("png:null:null:null", png?.decodeToString())
    val preview = seenManifest!!.previews.single()
    assertEquals("com.example.SnippetKt.AndroidPreview", preview.id)
    assertEquals("com.example.SnippetKt", preview.className)
    assertEquals("AndroidPreview", preview.functionName)
  }

  @Test
  fun `a render that never finishes times out to null`() {
    // A renderHook that emits nothing models a render whose terminal event never arrives.
    val fake = FakeRenderSession(renderRoot = tmp(), renderHook = { _, _ -> })
    val svc =
      PlaygroundAndroidRenderService(
        openSession = { _, _, _, _ -> fake },
        newWorkDir = { tmp() },
        renderBudget = 200.milliseconds,
        ackTimeout = 1.seconds,
      )

    assertNull(svc.render(snippet()))
  }

  @Test
  fun `a rejected render yields null`() {
    val fake = FakeRenderSession(renderRoot = tmp(), rejectAll = true)
    val svc =
      PlaygroundAndroidRenderService(openSession = { _, _, _, _ -> fake }, newWorkDir = { tmp() })

    assertNull(svc.render(snippet()))
  }

  @Test
  fun `a render the daemon reports failed yields null`() {
    val previewId = "com.example.SnippetKt.AndroidPreview"
    lateinit var fake: FakeRenderSession
    fake =
      FakeRenderSession(
        renderRoot = tmp(),
        // The daemon reports the render body threw instead of emitting a frame.
        renderHook = { _, _ -> fake.emitFailed(previewId, "boom") },
      )
    val svc =
      PlaygroundAndroidRenderService(
        openSession = { _, _, _, _ -> fake },
        newWorkDir = { tmp() },
        renderBudget = 2.seconds,
      )

    assertNull(svc.render(snippet(previewId)))
  }
}
