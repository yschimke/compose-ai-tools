package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.DataFetchResult
import ee.schimke.composeai.data.remotecompose.RemoteComposeDocumentPayload
import ee.schimke.composeai.previewdata.PreviewManifest
import java.io.File
import java.util.Base64
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
 * The Android/Remote-Compose capture orchestrator: previews.json synthesis, render→await→fetch, and
 * document decode — driven against a fake render session (no real daemon subprocess).
 */
class PlaygroundRcCaptureServiceTest {

  private val json = Json { ignoreUnknownKeys = true }
  private val tmpDirs = mutableListOf<File>()

  private fun tmp(): File =
    java.nio.file.Files.createTempDirectory("rc-capture-test").toFile().also { tmpDirs += it }

  @AfterTest
  fun cleanup() {
    tmpDirs.forEach { it.deleteRecursively() }
  }

  private fun snippet(previewId: String = "com.example.SnippetKt.RemotePreview") =
    PlaygroundTokenStore.PlaygroundSnippet(
      mode = PlaygroundMode.REMOTE_COMPOSE,
      workDir = "/work".toPath(),
      classesDir = "/work/classes".toPath(),
      classpath = listOf("/catalog/app.jar".toPath(), "/work/classes".toPath()),
      moduleName = "playground-android",
      previewId = previewId,
    )

  private fun docResult(bytes: ByteArray) =
    DataFetchResult(
      kind = "compose/remotecompose-doc",
      schemaVersion = 1,
      payload =
        json.encodeToJsonElement(
          RemoteComposeDocumentPayload.serializer(),
          RemoteComposeDocumentPayload(Base64.getEncoder().encodeToString(bytes)),
        ),
    )

  @Test
  fun `a rendered snippet's document is captured, and previews_json is synthesized from its id`() {
    val rc = byteArrayOf(1, 2, 3, 4, 5)
    val fake =
      FakeRenderSession(
        renderRoot = tmp(),
        fetchDataHook = { _, kind ->
          if (kind == "compose/remotecompose-doc") docResult(rc) else null
        },
      )
    var seenManifest: PreviewManifest? = null
    val svc =
      PlaygroundRcCaptureService(
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

    val bytes = svc.capture(snippet())

    assertEquals(rc.toList(), bytes?.toList())
    // The `data/remotecompose` extension must be enabled (it registers inactive) or the daemon's
    // document-capture `onRender` hook never runs and the fetch comes back empty.
    assertTrue(
      "data/remotecompose" in fake.enabledExtensionIds,
      "the RC extension must be enabled before the render captures the document",
    )
    val preview = seenManifest!!.previews.single()
    assertEquals("com.example.SnippetKt.RemotePreview", preview.id)
    assertEquals("com.example.SnippetKt", preview.className)
    assertEquals("RemotePreview", preview.functionName)
  }

  @Test
  fun `a backend without the remote-compose extension yields null`() {
    // The daemon reports `data/remotecompose` unknown (no Remote Compose runtime). Even though the
    // fetch hook would hand back a document, capture must fail soft — the extension never
    // activated.
    val rc = byteArrayOf(9, 8, 7)
    val fake =
      FakeRenderSession(
        renderRoot = tmp(),
        unknownExtensionIds = setOf("data/remotecompose"),
        fetchDataHook = { _, kind ->
          if (kind == "compose/remotecompose-doc") docResult(rc) else null
        },
      )
    val svc =
      PlaygroundRcCaptureService(openSession = { _, _, _, _ -> fake }, newWorkDir = { tmp() })

    assertNull(svc.capture(snippet()))
  }

  @Test
  fun `a render that produces no document yields null`() {
    val fake =
      FakeRenderSession(
        renderRoot = tmp(),
        // The product reports nothing captured (null payload) — a non-RC @Preview.
        fetchDataHook = { _, kind ->
          if (kind == "compose/remotecompose-doc")
            DataFetchResult(kind = kind, schemaVersion = 1, payload = null)
          else null
        },
      )
    val svc =
      PlaygroundRcCaptureService(openSession = { _, _, _, _ -> fake }, newWorkDir = { tmp() })

    assertNull(svc.capture(snippet()))
  }

  @Test
  fun `a render that never finishes times out to null`() {
    // renderHook that emits nothing models a render whose terminal event never arrives.
    val fake = FakeRenderSession(renderRoot = tmp(), renderHook = { _, _ -> })
    val svc =
      PlaygroundRcCaptureService(
        openSession = { _, _, _, _ -> fake },
        newWorkDir = { tmp() },
        renderBudget = 200.milliseconds,
        ackTimeout = 1.seconds,
      )

    assertNull(svc.capture(snippet()))
  }

  @Test
  fun `a rejected render yields null`() {
    val fake = FakeRenderSession(renderRoot = tmp(), rejectAll = true)
    val svc =
      PlaygroundRcCaptureService(openSession = { _, _, _, _ -> fake }, newWorkDir = { tmp() })

    assertNull(svc.capture(snippet()))
  }
}
