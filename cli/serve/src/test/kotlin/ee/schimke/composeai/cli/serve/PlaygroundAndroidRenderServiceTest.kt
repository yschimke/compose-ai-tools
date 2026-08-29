package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.DataFetchResult
import ee.schimke.composeai.daemon.protocol.PreviewOverrideValue
import ee.schimke.composeai.data.overrides.PreviewOverrideDeclaration
import ee.schimke.composeai.data.overrides.PreviewOverridesPayload
import ee.schimke.composeai.previewdata.PreviewManifest
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

  private fun snippet(
    previewId: String = "com.example.SnippetKt.AndroidPreview",
    workDir: String = "/work",
  ) =
    PlaygroundTokenStore.PlaygroundSnippet(
      mode = PlaygroundMode.ANDROID,
      workDir = workDir.toPath(),
      classesDir = "$workDir/classes".toPath(),
      classpath = listOf("/catalog/app.jar".toPath(), "$workDir/classes".toPath()),
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
  fun `the knobs the preview declared are persisted as the snippet's override sidecar`() {
    val work = tmp()
    val fake =
      FakeRenderSession(
        renderRoot = tmp(),
        fetchDataHook = { previewId, kind ->
          if (kind != PlaygroundAndroidRenderService.OVERRIDES_KIND) null
          else
            DataFetchResult(
              kind = kind,
              schemaVersion = 1,
              payload =
                json.encodeToJsonElement(
                  PreviewOverridesPayload.serializer(),
                  PreviewOverridesPayload(
                    declarations =
                      listOf(
                        PreviewOverrideDeclaration(
                          key = "label",
                          type = "string",
                          default = PreviewOverrideValue.StringValue(previewId),
                        )
                      )
                  ),
                ),
            )
        },
      )
    val svc =
      PlaygroundAndroidRenderService(openSession = { _, _, _, _ -> fake }, newWorkDir = { tmp() })

    val id = "com.example.SnippetKt.AndroidPreview"
    assertTrue(svc.render(snippet(id, workDir = work.absolutePath)) != null)

    // The connector has to be armed before the render — the declarations are collected during it.
    assertTrue(
      PlaygroundAndroidRenderService.OVERRIDES_EXTENSION_ID in fake.enabledExtensionIds,
      "the named-override connector is enabled before rendering",
    )
    // The sidecar lands in the SNIPPET's work dir (not this service's throwaway one), under the
    // exact name ServeBundleDaemon.readPreviews folds into ServePreview.overrides — which is what
    // gives a redeemed /pg/ session the viewer's live knob drawer.
    val sidecar = File(work, "previews/$id.overrides.json")
    assertTrue(sidecar.isFile, "the declarations are persisted for the redeemed live session")
    val payload = json.decodeFromString(PreviewOverridesPayload.serializer(), sidecar.readText())
    assertEquals(listOf("label"), payload.declarations.map { it.key })
  }

  @Test
  fun `a preview that declared no knobs writes no sidecar`() {
    val work = tmp()
    val fake =
      FakeRenderSession(
        renderRoot = tmp(),
        fetchDataHook = { _, kind ->
          if (kind != PlaygroundAndroidRenderService.OVERRIDES_KIND) null
          else
            DataFetchResult(
              kind = kind,
              schemaVersion = 1,
              payload =
                json.encodeToJsonElement(
                  PreviewOverridesPayload.serializer(),
                  PreviewOverridesPayload(),
                ),
            )
        },
      )
    val svc =
      PlaygroundAndroidRenderService(openSession = { _, _, _, _ -> fake }, newWorkDir = { tmp() })

    assertTrue(svc.render(snippet(workDir = work.absolutePath)) != null)
    // An empty payload is the common case; writing it would leave a file that parses to nothing.
    assertTrue(File(work, "previews").listFiles().isNullOrEmpty(), "no empty sidecar is written")
  }

  @Test
  fun `a backend without the override connector still renders its first frame`() {
    val work = tmp()
    val fake =
      FakeRenderSession(
        renderRoot = tmp(),
        unknownExtensionIds = setOf(PlaygroundAndroidRenderService.OVERRIDES_EXTENSION_ID),
      )
    val svc =
      PlaygroundAndroidRenderService(openSession = { _, _, _, _ -> fake }, newWorkDir = { tmp() })

    // The knob drain is strictly additive: a daemon that doesn't carry `data/overrides` must still
    // produce the still image the Stage-1 response shows.
    assertEquals(
      "png:null:null:null",
      svc.render(snippet(workDir = work.absolutePath))?.decodeToString(),
    )
    assertTrue(File(work, "previews").listFiles().isNullOrEmpty())
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
