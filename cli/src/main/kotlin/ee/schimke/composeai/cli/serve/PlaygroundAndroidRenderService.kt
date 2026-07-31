package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.render.session.RenderSession
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Opens a bundle-less render session over a compiled playground snippet — the injected daemon seam
 * shared by [PlaygroundAndroidRenderService] and [PlaygroundRcCaptureService], both of which stand
 * one Android/Robolectric daemon over a snippet's own classes. Production binds this to
 * [SubprocessRenderSessions.openBundleDaemon] with the Android backend; tests supply a fake
 * session.
 */
fun interface PlaygroundAndroidSessionOpener {
  fun open(
    classesDir: File,
    previewsJson: File,
    workspaceRoot: File,
    userClasspath: List<String>,
  ): RenderSession
}

/**
 * The production [PlaygroundCompileService] `renderFirstFrame`: render a freshly-compiled Compose
 * snippet on a daemon and return the PNG the daemon drew — the still frame the Stage-1 response
 * surfaces as its `image` (`docs/design/PLAYGROUND.md` §7 Phase 2, epic #3015).
 *
 * **Backend-agnostic:** the flow (open → render → read the `pngPath`) is identical for CMP (desktop
 * Skiko daemon) and Android (Robolectric); the injected [openSession] selects the backend, so the
 * same service wires both modes' first frame — only the daemon sidecar differs.
 *
 * The flow is the render half of [PlaygroundRcCaptureService]'s open → render → await → fetch
 * shape, over a bundle-less daemon standing on the snippet's own compiled classes:
 * 1. synthesize a one-preview `previews.json` from the snippet's discovered `@Preview` id
 *    ([PlaygroundPreviews]);
 * 2. [openSession] over the snippet's `classesDir` + full compile classpath (the production opener
 *    is [SubprocessRenderSessions.openBundleDaemon] with the Android backend —
 *    `lib-daemon-android` + `android.jar` on the daemon classpath, the Robolectric
 *    jvmArgs/sysprops, and the snippet classpath as `userClassDirs`);
 * 3. `renderNow` the preview and await its terminal `renderFinished` / `renderFailed` notification;
 * 4. read the PNG the daemon wrote to the `renderFinished` `pngPath` — no data-product fetch and no
 *    extension enable, unlike the RC capture: a plain first frame is the base render product.
 *
 * [openSession] is injected so the orchestration is unit-testable against a fake `RenderSession`
 * without a real daemon subprocess (the same seam split [PlaygroundRcCaptureService] uses). Returns
 * null — a clean "no frame" — on any miss: the render couldn't queue, it failed or timed out, or it
 * produced no PNG. A null first frame is never fatal to the run: the Stage-1 response simply
 * carries no still image while the preview token is still minted.
 */
class PlaygroundAndroidRenderService(
  private val openSession: PlaygroundAndroidSessionOpener,
  /** Mints a fresh scratch dir per render (holds the synthesized manifest + render outputs). */
  private val newWorkDir: () -> File,
  private val renderBudget: Duration = DEFAULT_RENDER_BUDGET,
  private val ackTimeout: Duration = DEFAULT_ACK_TIMEOUT,
) {

  /**
   * The [PlaygroundCompileService] `renderFirstFrame` seam: snippet → first-frame PNG bytes, or
   * null.
   */
  fun render(snippet: PlaygroundTokenStore.PlaygroundSnippet): ByteArray? {
    val workDir = newWorkDir().apply { mkdirs() }
    return try {
      val previewsJson =
        File(workDir, "previews.json").apply {
          writeText(PlaygroundPreviews.singlePreviewManifestJson(snippet))
        }
      // The playground's classpath entries are already absolute okio paths; File(toString()) is the
      // safe bridge to the java.io.File the render-session API takes.
      val classesDir = File(snippet.classesDir.toString())
      val userClasspath = snippet.classpath.map { File(it.toString()).absolutePath }
      val session = openSession.open(classesDir, previewsJson, workDir, userClasspath)
      try {
        renderFirstFrame(session, snippet.previewId)
      } finally {
        runCatching { session.close() }
      }
    } catch (_: Exception) {
      // A render failure is a clean "no frame" to the caller; it must never escape as a throwable
      // out of the render seam.
      null
    } finally {
      runCatching { workDir.deleteRecursively() }
    }
  }

  private fun renderFirstFrame(session: RenderSession, previewId: String): ByteArray? {
    val latch = CountDownLatch(1)
    val pngPath = AtomicReference<String?>()
    val failure = AtomicReference<String?>()
    val handle = session.onNotification { method, params ->
      if (params == null) return@onNotification
      val id = params["id"]?.jsonPrimitive?.contentOrNull ?: return@onNotification
      if (id != previewId) return@onNotification
      when (method) {
        "renderFinished" -> {
          params["pngPath"]?.jsonPrimitive?.contentOrNull?.let { pngPath.set(it) }
          latch.countDown()
        }
        "renderFailed" -> {
          failure.set("daemon reported renderFailed")
          latch.countDown()
        }
      }
    }
    return handle.use {
      val ack =
        session.renderNow(
          listOf(previewId),
          reason = "playground-first-frame",
          timeout = ackTimeout,
        )
      if (ack.rejected.isNotEmpty()) return@use null
      if (!latch.await(renderBudget.inWholeMilliseconds, TimeUnit.MILLISECONDS)) return@use null
      if (failure.get() != null) return@use null
      val path = pngPath.get() ?: return@use null
      val file = File(path)
      if (!file.isFile) return@use null
      runCatching { file.readBytes() }.getOrNull()
    }
  }

  companion object {
    /** Cold Android/Robolectric renders take tens of seconds; budget generously. */
    val DEFAULT_RENDER_BUDGET: Duration = 180.seconds
    val DEFAULT_ACK_TIMEOUT: Duration = 30.seconds
  }
}
