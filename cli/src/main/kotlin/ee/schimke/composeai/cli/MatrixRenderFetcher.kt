package ee.schimke.composeai.cli

import ee.schimke.composeai.io.SystemFileSystem
import ee.schimke.composeai.mcp.MatrixCell
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
import kotlinx.serialization.json.jsonPrimitive
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * Drives a short-lived [ee.schimke.composeai.render.session.RenderSession] for one module and
 * renders a single preview across every cell of a display-axis matrix (issue #1788), returning the
 * rendered PNG bytes per cell. The CLI's `render-matrix` command turns those into per-cell hashes
 * and an optional contact sheet.
 *
 * Cells render **serially** through one session: `renderNow` only queues, and each cell carries its
 * own overrides on the same preview id, so we wait for each cell's `renderFinished` before queueing
 * the next — mirroring how the daemon MCP server serialises different-override renders of the same
 * preview. Lives in the CLI (not the render-session library) because the per-cell aggregation is a
 * CLI / agent contract; third-party tooling uses [RenderSessionFactory] / `renderNow` directly.
 *
 * @param factory pluggable render-session factory; defaults to the subprocess backend. Tests inject
 *   a fake by constructing a custom [RenderSessionFactory].
 */
internal class MatrixRenderFetcher(
  private val factory: RenderSessionFactory = SubprocessRenderSessions,
  private val onLog: (String) -> Unit = {},
  private val fileSystem: FileSystem = SystemFileSystem,
) {
  /**
   * Render [previewId] across [cells] in [projectDir]'s module. [projectDir] is the module's
   * project directory (`daemon-launch.json` sits under `<projectDir>/build/compose-previews/`).
   * [workspaceRoot] is the repository root the daemon reports through the initialize handshake;
   * defaults to [projectDir] for single-module projects.
   */
  fun fetch(
    projectDir: File,
    moduleName: String,
    previewId: String,
    cells: List<MatrixCell>,
    workspaceRoot: File = projectDir,
  ): Outcome {
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
      // A single notification listener feeds whichever cell is currently in flight. Because cells
      // render serially (we await each finish before queueing the next), there is exactly one
      // pending latch at a time.
      val pending = AtomicReference<CountDownLatch?>(null)
      val pngPath = AtomicReference<String?>(null)
      live
        .onNotification { method, params ->
          if (method != "renderFinished" || params == null) return@onNotification
          val id = params["id"]?.jsonPrimitive?.contentOrNull ?: return@onNotification
          if (id != previewId) return@onNotification
          // `unchanged` renders still carry a (re-used) pngPath, so this captures bytes either way.
          params["pngPath"]?.jsonPrimitive?.contentOrNull?.let { pngPath.set(it) }
          pending.get()?.countDown()
        }
        .use {
          val results = mutableListOf<CellResult>()
          for (cell in cells) {
            val latch = CountDownLatch(1)
            pending.set(latch)
            pngPath.set(null)

            val ack =
              try {
                live.renderNow(
                  previewIds = listOf(previewId),
                  reason = "render-matrix ${cell.label}",
                  overrides = cell.toOverrides(),
                  timeout = RENDER_ACK_TIMEOUT,
                )
              } catch (e: RenderSessionException) {
                onLog("renderNow failed for cell '${cell.label}': ${e.message}")
                null
              }

            // renderNow threw: nothing was queued, so no renderFinished will arrive — don't burn
            // the full render timeout waiting on a render that never started.
            if (ack == null) {
              results += CellResult(cell, png = null)
              continue
            }

            val rejected = ack.rejected.firstOrNull { it.id == previewId }
            if (rejected != null) {
              onLog("render rejected for cell '${cell.label}': ${rejected.reason}")
              results += CellResult(cell, png = null)
              continue
            }

            if (!latch.await(RENDER_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
              onLog("timed out waiting for render of cell '${cell.label}'")
              results += CellResult(cell, png = null)
              continue
            }

            val path = pngPath.get()
            val bytes =
              path
                ?.toPath()
                ?.takeIf { fileSystem.exists(it) }
                ?.let { p -> fileSystem.read(p) { readByteArray() } }
            if (bytes == null) onLog("no PNG produced for cell '${cell.label}'")
            results += CellResult(cell, png = bytes)
          }
          Outcome.Ok(cells = results)
        }
    }
  }

  /** One rendered cell: its [cell] coordinates and the rendered [png] bytes (null on failure). */
  class CellResult(val cell: MatrixCell, val png: ByteArray?)

  sealed interface Outcome {
    /**
     * Session opened and every cell attempted. [cells] is in input order; failed cells have null
     * PNG.
     */
    data class Ok(val cells: List<CellResult>) : Outcome

    data class DescriptorMissing(val expected: File) : Outcome

    data class OpenFailed(val reason: String) : Outcome
  }

  private companion object {
    /** RPC ack budget for the (fast, queue-only) `renderNow` call itself. */
    val RENDER_ACK_TIMEOUT = 60.seconds

    /** Per-cell budget for the queued render to emit `renderFinished` (first pays cold start). */
    const val RENDER_TIMEOUT_SECONDS = 180L
  }
}
