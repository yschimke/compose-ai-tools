package ee.schimke.composeai.cli

import ee.schimke.composeai.data.fonts.FigmaSvgFontWarningsSidecar
import ee.schimke.composeai.data.fonts.FontsUsedDataProducer
import ee.schimke.composeai.data.layoutinspector.ComposeFigmaSvgProduct
import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsProduct
import ee.schimke.composeai.data.layoutinspector.LayoutInspectorProduct
import ee.schimke.composeai.io.SystemFileSystem
import ee.schimke.composeai.render.session.RenderSession
import ee.schimke.composeai.render.session.RenderSessionConfig
import ee.schimke.composeai.render.session.RenderSessionException
import ee.schimke.composeai.render.session.RenderSessionFactory
import ee.schimke.composeai.render.session.subprocess.SubprocessRenderSessions
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * Drives a short-lived [RenderSession] for one module, renders the requested previews so the
 * daemon's `compose/semantics` extension writes each `compose-semantics.json`, and reads them back,
 * for `bundle pack --with-semantics` (`previews/<id>.semantics.json`). The standalone
 * `composePreviewRender` task never produces semantics; the daemon is the only producer.
 *
 * @param factory render-session factory; defaults to the subprocess backend, tests inject a fake.
 * @param renderTimeout an inactivity budget: how long to wait after the last completed render
 *   before concluding the daemon stalled. Not a batch deadline, so wide catalogs still finish.
 */
internal class DaemonSemanticsFetcher(
  private val factory: RenderSessionFactory = SubprocessRenderSessions,
  private val onLog: (String) -> Unit = {},
  private val fileSystem: FileSystem = SystemFileSystem,
  private val renderTimeout: Duration = DEFAULT_RENDER_TIMEOUT,
) {
  /**
   * Render [previewIds] through a temporary daemon and return each `compose-semantics.json`, keyed
   * by preview id. Previews whose sidecar never materialised are absent; the caller reports them.
   *
   * `@PreviewParameter` previews read from the bare id too: the daemon renders the provider's first
   * value under `<id>`, so the sidecar lands in `build/compose-previews/data/<id>/` like any other.
   *
   * [projectDir] is the module directory (`PreviewModule.projectDir`), which holds both
   * `daemon-launch.json` and the daemon's output.
   */
  fun fetch(
    projectDir: File,
    moduleName: String,
    previewIds: List<String>,
    workspaceRoot: File = projectDir,
  ): Outcome {
    if (previewIds.isEmpty()) return Outcome.Ok(emptyMap())

    val descriptorFile = File(projectDir, "build/compose-previews/daemon-launch.json")
    if (!descriptorFile.isFile) return Outcome.DescriptorMissing(descriptorFile)

    val config =
      RenderSessionConfig(
        descriptorPath = descriptorFile,
        workspaceRoot = workspaceRoot.absoluteFile,
        workspaceName = workspaceRoot.name.ifBlank { moduleName },
        logSink = onLog,
        // Let a long command timeout extend the daemon's per-render deadline (which starts while
        // queued), but never shorten its five-minute default.
        maxRenderTime = maxOf(renderTimeout, DEFAULT_RENDER_TIMEOUT),
        // A stuck render shouldn't add the normal 15s shutdown RPC plus exit grace after the
        // inactivity timeout; shut down quickly and let the owner kill it.
        shutdownTimeout = SEMANTICS_SHUTDOWN_TIMEOUT,
      )

    val session: RenderSession =
      try {
        factory.open(config)
      } catch (e: RenderSessionException) {
        return Outcome.OpenFailed(reason = e.message ?: e.javaClass.simpleName)
      }

    return session.use { live ->
      // `renderNow` only queues and acks; sidecars are written later, signalled by a per-preview
      // terminal notification, so wait for those before reading (as `render-session/cli`'s
      // RenderCli does). Clear stale sidecars first so a render that never fires can't yield
      // cross-run data (the daemon is fresh and cold, so it always renders).
      for (previewId in previewIds) {
        sidecarFile(projectDir, previewId).delete()
        layoutSidecarFile(projectDir, previewId).delete()
        fontsSidecarFile(projectDir, previewId).delete()
        figmaSvgSidecarFile(projectDir, previewId).delete()
        figmaRasterDir(projectDir, previewId).deleteRecursively()
      }

      val pending = ConcurrentHashMap.newKeySet<String>().apply { addAll(previewIds) }
      // One token per terminal event, so the wait wakes on every completion and the deadline can be
      // an inactivity budget.
      val progress = LinkedBlockingQueue<Unit>()
      live
        .onNotification { method, params ->
          val failed = method == "renderFailed"
          if ((method != "renderFinished" && !failed) || params == null) return@onNotification
          val id = params["id"]?.jsonPrimitive?.contentOrNull ?: return@onNotification
          // Either terminal event (`renderFinished` or `renderFailed`) releases the wait; otherwise
          // one broken preview burns the whole budget. Whether a sidecar exists is decided by the
          // disk read below.
          if (failed) {
            val reason =
              params["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
                ?: "daemon reported renderFailed"
            onLog("render failed for '$id': $reason")
          }
          if (pending.remove(id)) progress.offer(Unit)
        }
        .use {
          val ack =
            try {
              live.renderNow(
                previewIds = previewIds,
                reason = "bundle pack semantics",
                timeout = RENDER_ACK_TIMEOUT,
              )
            } catch (e: RenderSessionException) {
              onLog("renderNow for semantics failed: ${e.message}")
              null
            }
          // Rejected ids will never emit renderFinished — stop waiting on them.
          ack?.rejected?.forEach { rejected ->
            onLog("render rejected for '${rejected.id}': ${rejected.reason}")
            if (pending.remove(rejected.id)) progress.offer(Unit)
          }
          // Wait for every queued render to reach a terminal event. The timeout resets on each
          // completion, so only a genuine stall trips it, not a large catalog's total render time.
          val timeoutMs = renderTimeout.inWholeMilliseconds
          while (pending.isNotEmpty()) {
            val signalled = progress.poll(timeoutMs, TimeUnit.MILLISECONDS)
            if (signalled == null) {
              onLog(
                "timed out after $renderTimeout with no render progress; still waiting on: " +
                  pending.joinToString(",")
              )
              break
            }
          }
        }

      val byId = LinkedHashMap<String, ByteArray>()
      val layoutById = LinkedHashMap<String, ByteArray>()
      val fontsById = LinkedHashMap<String, ByteArray>()
      val figmaSvgById = LinkedHashMap<String, ByteArray>()
      val figmaRasterById = LinkedHashMap<String, Map<String, ByteArray>>()
      val figmaFontWarningsById = LinkedHashMap<String, ByteArray>()
      for (previewId in previewIds) {
        val file = sidecarFile(projectDir, previewId)
        if (file.isFile && file.length() > 0) {
          byId[previewId] = fileSystem.read(file.path.toPath()) { readByteArray() }
        } else {
          onLog("no ${ComposeSemanticsProduct.FILE} for '$previewId'")
        }
        // The layout-inspector tree from the same render, for slot-level redlines. Best-effort.
        val layout = layoutSidecarFile(projectDir, previewId)
        if (layout.isFile && layout.length() > 0) {
          layoutById[previewId] = fileSystem.read(layout.path.toPath()) { readByteArray() }
        }
        // `fonts/used` (requested vs resolved fonts) from the same render, for the in-browser
        // tier's fonts.json. Best-effort; absent on backends without the recorder.
        val fonts = fontsSidecarFile(projectDir, previewId)
        if (fonts.isFile && fonts.length() > 0) {
          fontsById[previewId] = fileSystem.read(fonts.path.toPath()) { readByteArray() }
        }
        // The layered `compose/figma-svg` export from the same render. Best-effort.
        val figmaSvg = figmaSvgSidecarFile(projectDir, previewId)
        if (figmaSvg.isFile && figmaSvg.length() > 0) {
          figmaSvgById[previewId] = fileSystem.read(figmaSvg.path.toPath()) { readByteArray() }
          // A hybrid figma-svg's `figma-raster/<node>.png` crops, so its `<image>` layers resolve
          // once published. Usually empty.
          val crops =
            figmaRasterDir(projectDir, previewId)
              .listFiles { f -> f.isFile && f.name.endsWith(".png") }
              ?.sortedBy { it.name }
              ?.associate { it.name to fileSystem.read(it.path.toPath()) { readByteArray() } }
              .orEmpty()
          if (crops.isNotEmpty()) figmaRasterById[previewId] = crops
        }
        // The font-warning sidecar, written only when text exported as missing-glyph boxes.
        // Collected regardless of the SVG; the caller decides whether a degraded catalog may
        // publish.
        val fontWarnings = figmaFontWarningsSidecarFile(projectDir, previewId)
        if (fontWarnings.isFile && fontWarnings.length() > 0) {
          figmaFontWarningsById[previewId] =
            fileSystem.read(fontWarnings.path.toPath()) { readByteArray() }
        }
      }
      Outcome.Ok(
        semanticsById = byId,
        layoutById = layoutById,
        fontsById = fontsById,
        figmaSvgById = figmaSvgById,
        figmaRasterById = figmaRasterById,
        figmaFontWarningsById = figmaFontWarningsById,
      )
    }
  }

  private fun sidecarFile(projectDir: File, previewId: String): File =
    File(projectDir, "build/compose-previews/data/$previewId/${ComposeSemanticsProduct.FILE}")

  private fun layoutSidecarFile(projectDir: File, previewId: String): File =
    File(projectDir, "build/compose-previews/data/$previewId/${LayoutInspectorProduct.FILE}")

  private fun fontsSidecarFile(projectDir: File, previewId: String): File =
    File(projectDir, "build/compose-previews/data/$previewId/${FontsUsedDataProducer.FILE}")

  private fun figmaSvgSidecarFile(projectDir: File, previewId: String): File =
    File(projectDir, "build/compose-previews/data/$previewId/${ComposeFigmaSvgProduct.FILE_SVG}")

  private fun figmaRasterDir(projectDir: File, previewId: String): File =
    File(projectDir, "build/compose-previews/data/$previewId/${ComposeFigmaSvgProduct.RASTER_DIR}")

  private fun figmaFontWarningsSidecarFile(projectDir: File, previewId: String): File =
    File(projectDir, "build/compose-previews/data/$previewId/${FigmaSvgFontWarningsSidecar.FILE}")

  sealed interface Outcome {
    /**
     * Session opened and renders attempted. Each map holds entries only for previews that produced
     * that artifact: [semanticsById] (`compose-semantics.json`), [layoutById]
     * (`layout-inspector.json`), [fontsById] (`fonts/used`), [figmaSvgById] (`compose-figma.svg`),
     * [figmaRasterById] (hybrid export crops, filename → bytes), and [figmaFontWarningsById] —
     * normally empty; an entry means that preview's text is wrong.
     */
    data class Ok(
      val semanticsById: Map<String, ByteArray>,
      val layoutById: Map<String, ByteArray> = emptyMap(),
      val fontsById: Map<String, ByteArray> = emptyMap(),
      val figmaSvgById: Map<String, ByteArray> = emptyMap(),
      val figmaRasterById: Map<String, Map<String, ByteArray>> = emptyMap(),
      val figmaFontWarningsById: Map<String, ByteArray> = emptyMap(),
    ) : Outcome

    data class DescriptorMissing(val expected: File) : Outcome

    data class OpenFailed(val reason: String) : Outcome
  }

  private companion object {
    /** RPC ack budget for the (fast, queue-only) `renderNow` call itself. */
    val RENDER_ACK_TIMEOUT = 60.seconds

    /**
     * Default inactivity window for callers without a command timeout: how long a single render may
     * take before the daemon is presumed stalled.
     */
    val DEFAULT_RENDER_TIMEOUT = 300.seconds

    /** Graceful close budget before a stalled one-shot semantics daemon is terminated. */
    val SEMANTICS_SHUTDOWN_TIMEOUT = 2.seconds
  }
}
