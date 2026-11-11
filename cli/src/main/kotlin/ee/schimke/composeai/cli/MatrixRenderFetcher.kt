package ee.schimke.composeai.cli

import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.io.SystemFileSystem
import ee.schimke.composeai.render.matrix.MatrixCell
import ee.schimke.composeai.render.session.RenderSessionConfig
import ee.schimke.composeai.render.session.RenderSessionException
import ee.schimke.composeai.render.session.RenderSessionFactory
import ee.schimke.composeai.render.session.subprocess.SubprocessRenderSessions
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * Drives a short-lived [ee.schimke.composeai.render.session.RenderSession] for one module,
 * rendering one preview once per cell and returning PNG bytes per cell. A cell is anything with a
 * label and [PreviewOverrides]: a display-axis [MatrixCell] for `render-matrix` ([fetch]), or a
 * carried Remote Compose document for `render-cases` ([fetchCells]). Cells render serially, each
 * waiting for its terminal event, since they share a preview id with different overrides.
 *
 * @param factory render-session factory; defaults to the subprocess backend, tests inject a fake.
 */
internal class MatrixRenderFetcher(
  private val factory: RenderSessionFactory = SubprocessRenderSessions,
  private val onLog: (String) -> Unit = {},
  private val fileSystem: FileSystem = SystemFileSystem,
) {
  /**
   * Render [previewId] across [cells] in [projectDir]'s module (where `daemon-launch.json` lives).
   * [workspaceRoot] is reported to the daemon; defaults to [projectDir].
   */
  fun fetch(
    projectDir: File,
    moduleName: String,
    previewId: String,
    cells: List<MatrixCell>,
    workspaceRoot: File = projectDir,
  ): Outcome<MatrixCell> =
    fetchCells(
      projectDir,
      moduleName,
      previewId,
      cells,
      { it.label },
      { it.toOverrides() },
      workspaceRoot,
    )

  /**
   * Render [previewId] once per cell of [cells], each with the overrides [overridesOf] gives it,
   * named by [labelOf] in logs and the render reason.
   */
  fun <T> fetchCells(
    projectDir: File,
    moduleName: String,
    previewId: String,
    cells: List<T>,
    labelOf: (T) -> String,
    overridesOf: (T) -> PreviewOverrides,
    workspaceRoot: File = projectDir,
  ): Outcome<T> {
    val descriptorFile = File(projectDir, "build/compose-previews/daemon-launch.json")
    if (!descriptorFile.isFile) return Outcome.DescriptorMissing(descriptorFile)

    val config =
      RenderSessionConfig(
        descriptorPath = descriptorFile,
        workspaceRoot = workspaceRoot.absoluteFile,
        workspaceName = workspaceRoot.name.ifBlank { moduleName },
        logSink = onLog,
      )

    val session =
      try {
        factory.open(config)
      } catch (e: RenderSessionException) {
        return Outcome.OpenFailed(reason = e.message ?: e.javaClass.simpleName)
      }

    return session.use { live ->
      // One listener feeds the single in-flight cell's latch.
      val pending = AtomicReference<CountDownLatch?>(null)
      val pngPath = AtomicReference<String?>(null)
      live
        .onNotification { method, params ->
          val failedEvent = method == "renderFailed"
          if ((method != "renderFinished" && !failedEvent) || params == null) return@onNotification
          val id = params["id"]?.jsonPrimitive?.contentOrNull ?: return@onNotification
          if (id != previewId) return@onNotification
          // Either terminal event releases the cell, so a broken preview doesn't cost each cell the
          // full timeout. `pngPath` stays null on failure.
          if (failedEvent) {
            onLog(
              "render failed for '$id': " +
                (params["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
                  ?: "daemon reported renderFailed"),
            )
          } else {
            // `unchanged` renders still carry a reused pngPath.
            params["pngPath"]?.jsonPrimitive?.contentOrNull?.let { pngPath.set(it) }
          }
          pending.get()?.countDown()
        }
        .use {
          val results = mutableListOf<CellResult<T>>()
          for (cell in cells) {
            val label = labelOf(cell)
            // The daemon clears its override-in-flight flag just after `renderFinished`, so the
            // next cell can be rejected as coalesced; retry with the same bounded backoff as
            // ServeRenderHost.
            var attempt = 0
            var queued = false
            var failed: String? = null
            var latch = CountDownLatch(1)
            while (true) {
              latch = CountDownLatch(1)
              pending.set(latch)
              pngPath.set(null)

              val ack =
                try {
                  live.renderNow(
                    previewIds = listOf(previewId),
                    reason = "render $label",
                    overrides = overridesOf(cell),
                    timeout = RENDER_ACK_TIMEOUT,
                  )
                } catch (e: RenderSessionException) {
                  // Nothing was queued, so don't wait for a terminal event.
                  failed = "renderNow failed for cell '$label': ${e.message}"
                  break
                }

              val rejected = ack.rejected.firstOrNull { it.id == previewId }
              if (rejected != null) {
                if (rejected.reason.startsWith("coalesced") && attempt++ < MAX_COALESCED_RETRIES) {
                  Thread.sleep(COALESCED_RETRY_BACKOFF_MS)
                  continue
                }
                failed = "render rejected for cell '$label': ${rejected.reason}"
                break
              }
              queued = true
              break
            }

            if (!queued) {
              failed?.let(onLog)
              results += CellResult(cell, png = null)
              continue
            }

            if (!latch.await(RENDER_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
              onLog("timed out waiting for render of cell '$label'")
              results += CellResult(cell, png = null)
              continue
            }

            val path = pngPath.get()
            val bytes =
              path
                ?.toPath()
                ?.takeIf { fileSystem.exists(it) }
                ?.let { p -> fileSystem.read(p) { readByteArray() } }
            if (bytes == null) onLog("no PNG produced for cell '$label'")
            results += CellResult(cell, png = bytes)
          }
          Outcome.Ok(cells = results)
        }
    }
  }

  /**
   * One rendered cell: the [cell] it was asked for and the rendered [png] bytes (null on failure).
   */
  class CellResult<out T>(val cell: T, val png: ByteArray?)

  sealed interface Outcome<out T> {
    /** Session opened and every cell attempted, in input order; failed cells have a null PNG. */
    data class Ok<out T>(val cells: List<CellResult<T>>) : Outcome<T>

    data class DescriptorMissing(val expected: File) : Outcome<Nothing>

    data class OpenFailed(val reason: String) : Outcome<Nothing>
  }

  private companion object {
    /** RPC ack budget for the (fast, queue-only) `renderNow` call itself. */
    val RENDER_ACK_TIMEOUT = 60.seconds

    /** Per-cell budget for the queued render to emit `renderFinished` (first pays cold start). */
    const val RENDER_TIMEOUT_SECONDS = 180L

    /**
     * Bounded retries when the daemon coalesces an in-flight override render, as in
     * ServeRenderHost.
     */
    const val MAX_COALESCED_RETRIES = 50
    const val COALESCED_RETRY_BACKOFF_MS = 100L
  }
}
