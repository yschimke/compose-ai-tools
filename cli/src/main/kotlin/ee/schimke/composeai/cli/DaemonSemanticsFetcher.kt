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
import java.util.concurrent.ConcurrentLinkedQueue
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
 * Drives short-lived [RenderSession]s for one module, renders the requested previews so the
 * daemon's `compose/semantics` extension writes each `compose-semantics.json`, and reads them back,
 * for `bundle pack --with-semantics` (`previews/<id>.semantics.json`). The standalone
 * `composePreviewRender` task never produces semantics; the daemon is the only producer.
 *
 * **Windowed submission.** The daemon starts each render's `host.submit` deadline when the render
 * is queued, so handing it a whole catalog in one `renderNow` made every preview share one deadline
 * and the tail of a wide catalog failed together once it expired. Renders are instead submitted in
 * a bounded [window] per daemon, refilled as each one finishes, so a render waits behind at most
 * `window - 1` others and the daemon's per-render deadline is a real hang guard again. Older
 * daemons need nothing new for this: it is the same `renderNow`, called more often.
 *
 * **Parallel daemons.** With [workers] > 1, that many daemons pull ids from one shared queue (work
 * stealing, so a slow module section doesn't idle the others). Sidecars are per preview id and each
 * id is rendered by exactly one daemon, so outputs never collide, and the result maps are built in
 * [fetch]'s input order once every daemon is done, so the output is independent of scheduling.
 *
 * @param factory render-session factory; defaults to the subprocess backend, tests inject a fake.
 * @param renderTimeout an inactivity budget: how long a daemon may go without completing a render
 *   before it is presumed stalled. Not a batch deadline, so wide catalogs still finish.
 * @param workers how many daemons to drive in parallel; each loads the module's full classpath and
 *   previews.json, so this multiplies memory. See [SemanticsWorkers].
 * @param window renders in flight per daemon.
 * @param requestedKinds data kinds sent as `initialize.options.attachDataProducts`: what the pack
 *   reads back. Daemons that gate on-demand work by requested kind skip everything else; older ones
 *   ignore kinds they don't know. Null sends nothing (the daemon default).
 */
internal class DaemonSemanticsFetcher(
  private val factory: RenderSessionFactory = SubprocessRenderSessions,
  private val onLog: (String) -> Unit = {},
  private val fileSystem: FileSystem = SystemFileSystem,
  private val renderTimeout: Duration = DEFAULT_RENDER_TIMEOUT,
  private val workers: Int = 1,
  private val window: Int = DEFAULT_WINDOW,
  private val requestedKinds: List<String>? = kindsFor(figmaSvg = true),
) {
  /**
   * Render [previewIds] through temporary daemons and return each `compose-semantics.json`, keyed
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

    // Sidecars are written later, signalled by a per-preview terminal notification. Clear stale
    // ones
    // first so a render that never fires can't yield cross-run data (each daemon is fresh and cold,
    // so it always renders).
    for (previewId in previewIds) {
      sidecarFile(projectDir, previewId).delete()
      layoutSidecarFile(projectDir, previewId).delete()
      fontsSidecarFile(projectDir, previewId).delete()
      figmaSvgSidecarFile(projectDir, previewId).delete()
      figmaRasterDir(projectDir, previewId).deleteRecursively()
      // Written only for a degraded export, so a stale one would fail the lost-font check on its
      // own.
      figmaFontWarningsSidecarFile(projectDir, previewId).delete()
    }
    // Without the export requested, nothing this run produces should carry figma artefacts: a
    // daemon
    // that doesn't gate the kind may still write them, but the caller asked to go without.
    val readFigma = requestedKinds == null || ComposeFigmaSvgProduct.KIND in requestedKinds

    val workerCount = workers.coerceIn(1, previewIds.size)
    val baseName = workspaceRoot.name.ifBlank { moduleName }
    val queue = ConcurrentLinkedQueue(previewIds)
    val timings = RenderTimings()
    val startedAt = System.nanoTime()
    val openFailures =
      runWorkers(workerCount) { index ->
        val config =
          RenderSessionConfig(
            descriptorPath = descriptorFile,
            workspaceRoot = workspaceRoot.absoluteFile,
            // Distinct names keep each daemon's forwarded stderr attributable.
            workspaceName = if (workerCount == 1) baseName else "$baseName-semantics-${index + 1}",
            // Several daemons on one module would otherwise share (and prune) one history dir; a
            // one-shot capture never reads its history anyway.
            systemPropertyOverrides =
              if (workerCount == 1) emptyMap() else mapOf(HISTORY_ENABLED_PROP to "false"),
            logSink = if (workerCount == 1) onLog else { line -> onLog("[${index + 1}] $line") },
            // The daemon starts this deadline at submission; with windowed submission that is at
            // most `window - 1` renders ahead, so it guards a single hung render. A long command
            // timeout may extend it, but never below the daemon's five-minute default.
            maxRenderTime = maxOf(renderTimeout, DEFAULT_RENDER_TIMEOUT),
            // A stuck render shouldn't add the normal 15s shutdown RPC plus exit grace after the
            // inactivity timeout; shut down quickly and let the owner kill it.
            shutdownTimeout = SEMANTICS_SHUTDOWN_TIMEOUT,
            attachDataProducts = requestedKinds,
          )
        val session: RenderSession =
          try {
            factory.open(config)
          } catch (e: RenderSessionException) {
            return@runWorkers e.message ?: e.javaClass.simpleName
          }
        session.use { live ->
          drive(live, queue, timings, if (workerCount == 1) "" else "[${index + 1}] ")
        }
        null
      }
    if (openFailures.size == workerCount) {
      return Outcome.OpenFailed(reason = openFailures.distinct().joinToString("; "))
    }
    for (reason in openFailures) {
      onLog("a semantics daemon failed to open ($reason); the others took its previews")
    }
    // Ids no daemon got to: every daemon that opened stalled or broke before the queue drained.
    val unsubmitted = generateSequence { queue.poll() }.toList()
    if (unsubmitted.isNotEmpty()) {
      onLog(
        "${unsubmitted.size} preview(s) never submitted after the daemon(s) stopped; first: " +
          unsubmitted.take(UNSUBMITTED_LOG_LIMIT).joinToString(",")
      )
    }
    val wallMs = (System.nanoTime() - startedAt) / 1_000_000
    timings
      .summaryLines(
        wallMs = wallMs,
        daemons = workerCount - openFailures.size,
        window = window,
        unsubmitted = unsubmitted.size,
      )
      .forEach(onLog)

    val byId = LinkedHashMap<String, ByteArray>()
    val layoutById = LinkedHashMap<String, ByteArray>()
    val fontsById = LinkedHashMap<String, ByteArray>()
    val figmaSvgById = LinkedHashMap<String, ByteArray>()
    val figmaRasterById = LinkedHashMap<String, Map<String, ByteArray>>()
    val figmaFontWarningsById = LinkedHashMap<String, ByteArray>()
    // Read back in input order, whichever daemon rendered each id: the maps' order is independent
    // of
    // scheduling.
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
      // `fonts/used` (requested vs resolved fonts) from the same render, for the in-browser tier's
      // fonts.json. Best-effort; absent on backends without the recorder.
      val fonts = fontsSidecarFile(projectDir, previewId)
      if (fonts.isFile && fonts.length() > 0) {
        fontsById[previewId] = fileSystem.read(fonts.path.toPath()) { readByteArray() }
      }
      if (!readFigma) continue
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
      // The font-warning sidecar, written only when text exported as missing-glyph boxes. Collected
      // regardless of the SVG; the caller decides whether a degraded catalog may publish.
      val fontWarnings = figmaFontWarningsSidecarFile(projectDir, previewId)
      if (fontWarnings.isFile && fontWarnings.length() > 0) {
        figmaFontWarningsById[previewId] =
          fileSystem.read(fontWarnings.path.toPath()) { readByteArray() }
      }
    }
    return Outcome.Ok(
      semanticsById = byId,
      layoutById = layoutById,
      fontsById = fontsById,
      figmaSvgById = figmaSvgById,
      figmaRasterById = figmaRasterById,
      figmaFontWarningsById = figmaFontWarningsById,
    )
  }

  /**
   * Runs [body] for worker indices `0 until count` — inline for one, on threads otherwise, so the
   * daemons open and render concurrently — and returns the non-null results (open failures).
   */
  private fun runWorkers(count: Int, body: (Int) -> String?): List<String> {
    if (count == 1) return listOfNotNull(body(0))
    val results = arrayOfNulls<String>(count)
    val threads =
      (0 until count).map { index ->
        Thread(
            {
              results[index] =
                try {
                  body(index)
                } catch (e: Exception) {
                  // A worker that dies mid-run loses only its in-flight renders; the rest of the
                  // queue stays for the others.
                  onLog(
                    "semantics daemon ${index + 1} failed: ${e.message ?: e.javaClass.simpleName}"
                  )
                  null
                }
            },
            "bundle-semantics-${index + 1}",
          )
          .apply { start() }
      }
    threads.forEach { it.join() }
    return results.filterNotNull()
  }

  /**
   * Feeds [live] from [queue] with at most [window] renders in flight until the queue drains, the
   * daemon makes no progress for [renderTimeout], or `renderNow` itself fails.
   */
  private fun drive(
    live: RenderSession,
    queue: ConcurrentLinkedQueue<String>,
    timings: RenderTimings,
    logPrefix: String,
  ) {
    val inFlight = ConcurrentHashMap.newKeySet<String>()
    // One token per terminal event, so the wait wakes on every completion and the deadline can be
    // an inactivity budget.
    val progress = LinkedBlockingQueue<Unit>()
    live
      .onNotification { method, params ->
        val failed = method == "renderFailed"
        if ((method != "renderFinished" && !failed) || params == null) return@onNotification
        val id = params["id"]?.jsonPrimitive?.contentOrNull ?: return@onNotification
        // Either terminal event (`renderFinished` or `renderFailed`) releases the slot; otherwise
        // one broken preview burns the whole budget. Whether a sidecar exists is decided by the
        // disk
        // read afterwards.
        if (failed) {
          val reason =
            params["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
              ?: "daemon reported renderFailed"
          onLog("${logPrefix}render failed for '$id': $reason")
          timings.recordFailed()
        } else {
          timings.recordFinished(id, params)
        }
        if (inFlight.remove(id)) progress.offer(Unit)
      }
      .use {
        val timeoutMs = renderTimeout.inWholeMilliseconds
        while (true) {
          val batch = ArrayList<String>()
          while (inFlight.size + batch.size < window) batch += queue.poll() ?: break
          if (batch.isNotEmpty()) {
            // Before the call: a fast daemon may report a render before `renderNow` returns.
            inFlight.addAll(batch)
            val ack =
              try {
                live.renderNow(
                  previewIds = batch,
                  reason = "bundle pack semantics",
                  timeout = RENDER_ACK_TIMEOUT,
                )
              } catch (e: RenderSessionException) {
                // Not requeued: the daemon may still have queued them, and a second daemon
                // rendering the same id would race on its sidecar.
                onLog("${logPrefix}renderNow for semantics failed: ${e.message}")
                timings.recordUnacknowledged(inFlight.size)
                return
              }
            // Rejected ids will never emit a terminal event; free their slots.
            for (rejected in ack.rejected) {
              onLog("${logPrefix}render rejected for '${rejected.id}': ${rejected.reason}")
              timings.recordFailed()
              inFlight.remove(rejected.id)
            }
          }
          if (inFlight.isEmpty()) {
            if (queue.isEmpty()) return
            continue
          }
          // The timeout resets on each completion, so only a genuine stall trips it, not a large
          // catalog's total render time.
          if (progress.poll(timeoutMs, TimeUnit.MILLISECONDS) == null) {
            onLog(
              "${logPrefix}timed out after $renderTimeout with no render progress; still waiting " +
                "on: " +
                inFlight.joinToString(",")
            )
            timings.recordStalled(inFlight.size)
            return
          }
        }
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

  companion object {
    /**
     * The data kinds `bundle pack --with-semantics` reads back, for
     * `initialize.options.attachDataProducts`. `compose/figma-svg` is the expensive one (seconds
     * per render) and the only kind daemons gate on today; leave it out when the catalog defers its
     * vectors to a live daemon and nothing needs the pack's copy.
     */
    fun kindsFor(figmaSvg: Boolean): List<String> = buildList {
      add(ComposeSemanticsProduct.KIND)
      add(LayoutInspectorProduct.KIND)
      add(FontsUsedDataProducer.KIND)
      if (figmaSvg) add(ComposeFigmaSvgProduct.KIND)
    }

    /** Renders in flight per daemon: enough to keep a pooled Android host busy. */
    const val DEFAULT_WINDOW = 8

    /**
     * The daemon's history switch (`HistoryFeature.PROP`), off when several daemons share a dir.
     */
    private const val HISTORY_ENABLED_PROP = "composeai.history.enabled"

    private const val UNSUBMITTED_LOG_LIMIT = 20

    /** RPC ack budget for the (fast, queue-only) `renderNow` call itself. */
    private val RENDER_ACK_TIMEOUT = 60.seconds

    /**
     * Default inactivity window for callers without a command timeout: how long a single render may
     * take before the daemon is presumed stalled.
     */
    private val DEFAULT_RENDER_TIMEOUT = 300.seconds

    /** Graceful close budget before a stalled one-shot semantics daemon is terminated. */
    private val SEMANTICS_SHUTDOWN_TIMEOUT = 2.seconds
  }
}
