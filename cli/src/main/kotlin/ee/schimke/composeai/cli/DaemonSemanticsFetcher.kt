package ee.schimke.composeai.cli

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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * Drives a short-lived [RenderSession] for one module, renders the requested previews so the
 * daemon's always-on `compose/semantics` extension writes each one's `compose-semantics.json`
 * sidecar, then reads those sidecars back off disk. Used by `compose-preview bundle pack
 * --with-semantics` to carry the per-preview semantics blob (the [ComposeSemanticsProduct] tree
 * with resolved foreground/background colours) inside the bundle as `previews/<id>.semantics.json`
 * — the shape the design-parity static bundle reader consumes (issue #1843).
 *
 * The standalone `composePreviewRender` Gradle task is "normal render only" and never produces
 * semantics — the daemon is the single producer (see `docs/AGENTS.md`). So unlike the rest of
 * `pack` (which is pure Gradle), the semantics blob is obtained by spinning up the same daemon the
 * VS Code extension / MCP server / `compose-preview a11y` use, fetching, and shutting it down.
 *
 * @param factory pluggable render-session factory; defaults to the subprocess backend. Test
 *   scaffolding can inject a fake by constructing a custom [RenderSessionFactory].
 */
internal class DaemonSemanticsFetcher(
  private val factory: RenderSessionFactory = SubprocessRenderSessions,
  private val onLog: (String) -> Unit = {},
  private val fileSystem: FileSystem = SystemFileSystem,
) {
  /**
   * Render [previewIds] through a temporary daemon and return each preview's
   * `compose-semantics.json` bytes, keyed by preview id. Previews whose sidecar never materialised
   * (a `@PreviewParameter` fan-out whose data dir is keyed by a per-value base name, a render that
   * failed, an unsupported backend) are simply absent from the returned map — the caller carries
   * what it got and reports the rest.
   *
   * [projectDir] is the module's project directory (`PreviewModule.projectDir`);
   * `daemon-launch.json` and the daemon's `build/compose-previews/data/<id>/` output both sit under
   * it regardless of the module's gradle path.
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
      )

    val session: RenderSession =
      try {
        factory.open(config)
      } catch (e: RenderSessionException) {
        return Outcome.OpenFailed(reason = e.message ?: e.javaClass.simpleName)
      }

    return session.use { live ->
      // `renderNow` only *queues* the renders and acks immediately (`RenderNowResult.queued`) — the
      // daemon's always-on ComposeSemanticsExtension writes each sidecar later, signalled by a
      // `renderFinished` notification per preview. Reading the files right after the ack would race
      // the render and inject nothing (or a stale sidecar from a prior run), so we wait for the
      // notifications before reading. Mirrors `render-session/cli`'s RenderCli wait loop.
      //
      // Clear any stale sidecars first so a render that doesn't fire (rejected / failed) can't
      // leave
      // us reading cross-run data. Safe because `bundle pack` spawns a fresh, cold daemon that
      // always renders (no warm cache to serve "unchanged").
      for (previewId in previewIds) {
        sidecarFile(projectDir, previewId).delete()
        layoutSidecarFile(projectDir, previewId).delete()
        fontsSidecarFile(projectDir, previewId).delete()
        figmaSvgSidecarFile(projectDir, previewId).delete()
      }

      val pending = ConcurrentHashMap.newKeySet<String>().apply { addAll(previewIds) }
      val latch = CountDownLatch(previewIds.size)
      live
        .onNotification { method, params ->
          if (method != "renderFinished" || params == null) return@onNotification
          val id = params["id"]?.jsonPrimitive?.contentOrNull ?: return@onNotification
          // Count down on any finish for a pending id, regardless of pngPath — a failed render
          // emits renderFinished without one, and we must not block forever waiting on it. Whether
          // a sidecar actually materialised is decided by the disk read below.
          if (pending.remove(id)) latch.countDown()
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
            if (pending.remove(rejected.id)) latch.countDown()
          }
          val finished = latch.await(SEMANTICS_RENDER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
          if (!finished) {
            onLog(
              "timed out after ${SEMANTICS_RENDER_TIMEOUT_SECONDS}s waiting for renders: " +
                pending.joinToString(",")
            )
          }
        }

      val byId = LinkedHashMap<String, ByteArray>()
      val layoutById = LinkedHashMap<String, ByteArray>()
      val fontsById = LinkedHashMap<String, ByteArray>()
      val figmaSvgById = LinkedHashMap<String, ByteArray>()
      for (previewId in previewIds) {
        val file = sidecarFile(projectDir, previewId)
        if (file.isFile && file.length() > 0) {
          byId[previewId] = fileSystem.read(file.path.toPath()) { readByteArray() }
        } else {
          onLog("no ${ComposeSemanticsProduct.FILE} for '$previewId'")
        }
        // The layout-inspector tree (full LayoutNode walk with per-node bounds + resolved tokens)
        // is baked by the same always-on daemon render; carry it too so consumers can build
        // slot-level redlines/wireframes the a11y semantics tree can't express (issue: layout
        // wireframes). Best-effort — absent for a render that produced no tree.
        val layout = layoutSidecarFile(projectDir, previewId)
        if (layout.isFile && layout.length() > 0) {
          layoutById[previewId] = fileSystem.read(layout.path.toPath()) { readByteArray() }
        }
        // The `fonts/used` record (requested vs resolved family, weight, style per font
        // resolution) is baked by the same always-on daemon render (FontsRecorderExtension); carry
        // it so the design-catalog export can generate the in-browser tier's fonts.json from what
        // the previews actually resolved. Best-effort — absent on backends without the recorder.
        val fonts = fontsSidecarFile(projectDir, previewId)
        if (fonts.isFile && fonts.length() > 0) {
          fontsById[previewId] = fileSystem.read(fonts.path.toPath()) { readByteArray() }
        }
        // The layered `compose/figma-svg` export (real fills/strokes/corner radii + editable text)
        // is baked by the same always-on daemon render; carry it so the design-catalog export can
        // ship an editable vector per sticker alongside the raster PNG. Best-effort — absent for a
        // render that produced no drawing layers.
        val figmaSvg = figmaSvgSidecarFile(projectDir, previewId)
        if (figmaSvg.isFile && figmaSvg.length() > 0) {
          figmaSvgById[previewId] = fileSystem.read(figmaSvg.path.toPath()) { readByteArray() }
        }
      }
      Outcome.Ok(
        semanticsById = byId,
        layoutById = layoutById,
        fontsById = fontsById,
        figmaSvgById = figmaSvgById,
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

  sealed interface Outcome {
    /**
     * Session opened and renders attempted. [semanticsById] holds one entry per preview whose
     * `compose-semantics.json` materialised — possibly empty if every render failed. [layoutById]
     * holds the matching `layout-inspector.json` (the full LayoutNode tree) for each preview that
     * produced one; a preview may have semantics but no layout tree, or vice versa. [fontsById]
     * holds the matching `fonts-used.json` (`fonts/used` — requested vs resolved font families) for
     * each preview whose backend runs the fonts recorder. [figmaSvgById] holds the matching
     * `compose-figma.svg` (the layered editable `compose/figma-svg` export) for each preview that
     * produced drawing layers.
     */
    data class Ok(
      val semanticsById: Map<String, ByteArray>,
      val layoutById: Map<String, ByteArray> = emptyMap(),
      val fontsById: Map<String, ByteArray> = emptyMap(),
      val figmaSvgById: Map<String, ByteArray> = emptyMap(),
    ) : Outcome

    data class DescriptorMissing(val expected: File) : Outcome

    data class OpenFailed(val reason: String) : Outcome
  }

  private companion object {
    /** RPC ack budget for the (fast, queue-only) `renderNow` call itself. */
    val RENDER_ACK_TIMEOUT = 60.seconds

    /**
     * Generous budget for every queued render to emit `renderFinished` — the daemon renders the
     * selected previews in sequence and the first also pays the sandbox cold-start.
     */
    const val SEMANTICS_RENDER_TIMEOUT_SECONDS = 180L
  }
}
