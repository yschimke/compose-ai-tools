package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.data.remotecompose.RemoteComposeDocumentPayload
import ee.schimke.composeai.data.remotecompose.RemoteComposeDocumentProduct
import ee.schimke.composeai.render.session.RenderSession
import java.io.File
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The production [PlaygroundCompileService] `captureRemoteDocument`: render a freshly-compiled
 * **remote-compose** snippet on an Android/Robolectric daemon and return the serialized Remote
 * Compose document (`.rc`) it drew — the bytes the playground then publishes as a `/d/<id>`
 * permalink (`docs/design/PLAYGROUND.md` §3, epic #3014).
 *
 * The flow mirrors `ServeRenderHost.render`'s open → render → await → fetch shape, over a
 * bundle-less daemon standing on the snippet's own compiled classes:
 *
 * 1. synthesize a `previews.json` from the snippet's discovered `@Preview` ids;
 * 2. [openSession] over the snippet's `classesDir` + full compile classpath (the production opener
 *    is [SubprocessRenderSessions.openBundleDaemon] with the Android backend — `lib-daemon-android`
 *     + `android.jar` on the daemon classpath, the Robolectric jvmArgs/sysprops, and the snippet
 *       classpath as `userClassDirs`);
 * 3. `renderNow` the preview and await its terminal `renderFinished` / `renderFailed` notification
 *    — the render is what drives `RemoteOverridablePreview` to capture the `.rc` into the daemon's
 *    `compose/remotecompose-doc` product;
 * 4. `fetchData(compose/remotecompose-doc)` and Base64-decode the document bytes.
 *
 * [openSession] is injected so the orchestration is unit-testable against a fake `RenderSession`
 * without a real daemon subprocess (the same seam split `PlaygroundCompileService` uses for its
 * other collaborators). Returns null — a clean "no document" — on any miss: the mode couldn't
 * queue, the render failed or timed out, or the preview drew no Remote Compose document.
 */
class PlaygroundRcCaptureService(
  private val openSession: PlaygroundAndroidSessionOpener,
  /** Mints a fresh scratch dir per capture (holds the synthesized manifest + render outputs). */
  private val newWorkDir: () -> File,
  private val renderBudget: Duration = DEFAULT_RENDER_BUDGET,
  private val ackTimeout: Duration = DEFAULT_ACK_TIMEOUT,
) {

  /**
   * The [PlaygroundCompileService] `captureRemoteDocument` seam: snippet → `.rc` bytes, or null.
   */
  fun capture(snippet: PlaygroundTokenStore.PlaygroundSnippet): ByteArray? {
    val workDir = newWorkDir().apply { mkdirs() }
    return try {
      val previewsJson =
        File(workDir, "previews.json").apply {
          writeText(PlaygroundPreviews.previewManifestJson(snippet))
        }
      // The playground's classpath entries are already absolute okio paths; File(toString()) is the
      // safe bridge to the java.io.File the render-session API takes.
      val classesDir = File(snippet.classesDir.toString())
      val userClasspath = snippet.classpath.map { File(it.toString()).absolutePath }
      val session = openSession.open(classesDir, previewsJson, workDir, userClasspath)
      try {
        renderAndFetch(session, snippet.previewId)
      } finally {
        runCatching { session.close() }
      }
    } catch (_: Exception) {
      // A capture failure is a clean "no document" to the caller (which reports the RC failure);
      // it must never escape as a throwable out of the render seam.
      null
    } finally {
      runCatching { workDir.deleteRecursively() }
    }
  }

  private fun renderAndFetch(session: RenderSession, previewId: String): ByteArray? {
    // The daemon registers `data/remotecompose` **inactive** (like every extension): its `onRender`
    // hook — the one that drains the `.rc` off `IrSidecarChannel` into `compose/remotecompose-doc`
    // —
    // runs only while the extension is active (`JsonRpcServer.handleRenderFinished` feeds
    // `activeDataProducts().onRender(...)`), and `fetchData` rejects the kind as "not advertised"
    // until it's enabled. So enable it before rendering, or the capture silently draws nothing.
    // An unknown/rejected id (or an enable RPC failure) means the backend carries no Remote Compose
    // runtime — a clean "no document", handled like every other miss below.
    val enabled =
      runCatching { session.enableExtensions(listOf(REMOTE_COMPOSE_EXTENSION_ID)) }.getOrNull()
        ?: return null
    if (REMOTE_COMPOSE_EXTENSION_ID in enabled.unknown) return null

    val latch = CountDownLatch(1)
    val failure = AtomicReference<String?>()
    val handle = session.onNotification { method, params ->
      if (params == null) return@onNotification
      val id = params["id"]?.jsonPrimitive?.contentOrNull ?: return@onNotification
      if (id != previewId) return@onNotification
      when (method) {
        "renderFinished" -> latch.countDown()
        "renderFailed" -> {
          failure.set(
            params["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
              ?: "daemon reported renderFailed"
          )
          latch.countDown()
        }
      }
    }
    return handle.use {
      val ack =
        session.renderNow(listOf(previewId), reason = "playground-rc-capture", timeout = ackTimeout)
      if (ack.rejected.isNotEmpty()) return@use null
      if (!latch.await(renderBudget.inWholeMilliseconds, TimeUnit.MILLISECONDS)) return@use null
      if (failure.get() != null) return@use null
      val result = session.fetchData(previewId, RemoteComposeDocumentProduct.KIND)
      val payloadJson = result.payload ?: return@use null
      val payload =
        json.decodeFromJsonElement(RemoteComposeDocumentPayload.serializer(), payloadJson)
      runCatching { Base64.getDecoder().decode(payload.documentBase64) }.getOrNull()
    }
  }

  companion object {
    /**
     * The daemon's Remote Compose extension **id** (`DaemonMain`'s `tryAdd("data/remotecompose")`)
     * — distinct from the data-product **kind** `compose/remotecompose(-doc)`. `extensions/enable`
     * resolves by extension id, so this is what activates the document-capture `onRender` hook.
     */
    const val REMOTE_COMPOSE_EXTENSION_ID: String = "data/remotecompose"

    /** Cold Android/Robolectric renders take tens of seconds; budget generously. */
    val DEFAULT_RENDER_BUDGET: Duration = 180.seconds
    val DEFAULT_ACK_TIMEOUT: Duration = 30.seconds

    private val json = Json {
      encodeDefaults = true
      ignoreUnknownKeys = true
    }
  }
}
