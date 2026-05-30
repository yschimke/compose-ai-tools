@file:JvmName("DaemonMain")

package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.history.GitProvenance
import ee.schimke.composeai.daemon.history.GitRefHistorySource
import ee.schimke.composeai.daemon.history.HistoryManager
import ee.schimke.composeai.daemon.history.HistoryPruneConfig
import ee.schimke.composeai.data.render.RenderPreviewExtension
import ee.schimke.composeai.data.render.extensions.DataExtensionDescriptor
import ee.schimke.composeai.data.render.extensions.RecordingScriptDataExtensions
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path

/**
 * Entry point for the desktop preview daemon JVM — see docs/daemon/DESIGN.md § 4
 * ("Renderer-agnostic surface"). Mirrors `:daemon:android`'s [DaemonMain][
 * ee.schimke.composeai.daemon.DaemonMain] (B1.5) so a future `composePreviewDaemonStart` task that
 * picks the right `mainClass` per target doesn't have to special-case anything.
 *
 * Lifecycle (B-desktop.1.5):
 * 1. **Stdout reroute.** Stdout is the JSON-RPC channel — every byte is a framed message. Some
 *    libraries we don't fully control (kotlinx-coroutines bootstrap, Skiko native init, occasional
 *    `println` left over in third-party code) will write to `System.out` by default and corrupt the
 *    wire. Capture the real stdout into a local before swapping `System.out` to `System.err`, then
 *    hand the captured stream to [JsonRpcServer]. After this point any `System.out.println` lands
 *    on stderr (free-form log per [PROTOCOL.md § 1](../../../../../../docs/daemon/PROTOCOL.md)).
 * 2. **Print a hello banner to stderr** so `runDaemonMain` debugging ("did the JVM boot?") is
 *    obvious without sending bytes down the wire.
 * 3. Build a [DesktopHost] (B-desktop.1.3 + B-desktop.1.4 — holds the warm render thread + Compose
 *    runtime open across renders). Implements the renderer-agnostic [RenderHost] from
 *    `:daemon:core`.
 * 4. Build a [JsonRpcServer] (B1.5 — JSON-RPC 2.0 over stdio with LSP-style Content-Length
 *    framing). Lives in `:daemon:core`; binds to any [RenderHost] implementation.
 * 5. **Install a SIGTERM shutdown hook** (B-desktop.1.6) that closes stdin to nudge the read loop
 *    out of `read()` and calls `host.shutdown(timeoutMs)` so the in-flight render drains before the
 *    JVM exits. Mirrors the no-mid-render-cancellation enforcement listed in
 *    [DESIGN.md § 9](../../../../../../docs/daemon/DESIGN.md#no-mid-render-cancellation--invariant--enforcement).
 * 6. [JsonRpcServer.run] blocks until the client sends `shutdown` + `exit` or stdin closes; it
 *    calls `System.exit` itself.
 * 7. Defensive `host.shutdown(...)` in `finally` — `JsonRpcServer.run` already calls
 *    `host.shutdown()` on its `cleanShutdown` path, but if `run()` itself throws (e.g. an
 *    unrecoverable IO error) before reaching that, the host's render thread is still alive and a
 *    bare `System.exit` would skip its `try/finally` discipline. Calling `shutdown(timeoutMs =
 *    30_000)` here is idempotent and matches the renderer-android side.
 *
 * `args` is currently unused; future flags (e.g. `--detect-leaks=heavy`, `--foreground`) will be
 * parsed here.
 */
fun main(args: Array<String>) {
  // Capture the real stdout *before* swapping. Whatever uses `System.out` after this line lands on
  // stderr; the JSON-RPC channel is the captured `realOut`. Embedded-mode callers (see
  // `:render-session-embedded-desktop`) skip this swap and the SIGTERM hook entirely by invoking
  // [runDaemon] directly with their own piped streams — `main(...)` is the subprocess entry
  // point only.
  val realOut = System.out
  System.setOut(System.err)

  System.err.println("compose-ai-tools desktop daemon: hello (args=${args.toList()})")

  runDaemon(
    input = System.`in`,
    output = realOut,
    installSigtermHook = true,
    onExit = { code -> System.exit(code) },
  )
}

/**
 * Renderer / extension / host / JSON-RPC-server setup, parameterised over the transport streams and
 * the SIGTERM-hook policy so embedded mode can drive the same daemon body in-process.
 *
 * **Subprocess mode** (the canonical path) is `fun main()` above: real stdio is swapped, the
 * SIGTERM hook is installed, [runDaemon] is invoked with `System.in` and the captured `realOut`,
 * and the JSON-RPC server blocks the calling thread until `shutdown` + `exit`.
 *
 * **Embedded mode** runs this function on a background thread with [input] and [output] connected
 * to in-memory piped streams; the calling thread holds the other ends of those pipes and drives the
 * daemon via a `DaemonClient`. SIGTERM is the caller's concern in that shape — they own the thread
 * and can cancel it via `client.shutdownAndExit()` which causes the JSON-RPC server's read loop to
 * return cleanly. Setting [installSigtermHook] to false on the embedded path avoids leaking a
 * per-session hook onto the JVM's shutdown machinery.
 *
 * All other configuration (preview index path, history dir, classpath fingerprint sources) is read
 * from system properties exactly as before — the embedded session is responsible for setting these
 * from the daemon launch descriptor before invoking. This is the only constraint on multi-session
 * reuse: sysprops are JVM-global, so two embedded sessions in the same JVM cannot point at
 * different `previews.json` files simultaneously without external synchronisation.
 */
fun runDaemon(
  input: InputStream,
  output: OutputStream,
  installSigtermHook: Boolean,
  onExit: (Int) -> Unit = { code -> System.exit(code) },
) {

  // D-harness.v1.5a — when the harness drives real-mode runs it sets
  // `composeai.harness.previewsManifest=<json>` so the daemon can resolve the protocol-level
  // previewId (forwarded by JsonRpcServer as `payload="previewId=<id>"`) into a parseable
  // RenderSpec via `PreviewManifestRouter`. Production launches don't set this sysprop, so the
  // plain DesktopHost path is unchanged.
  // B2.0 — build the disposable user-class holder from `composeai.daemon.userClassDirs` (set by
  // the gradle plugin's daemon launch descriptor). When the sysprop is unset (legacy harness paths
  // that don't yet emit it), the holder is null and the host falls back to the JVM app
  // classloader — the pre-B2.0 behaviour. Per CLASSLOADER.md: parent classloader is the JVM app
  // classloader; URLs come from the sysprop.
  val userClassUrls = UserClassLoaderHolder.urlsFromSysprop()
  val userClassloaderHolder: UserClassLoaderHolder? =
    if (userClassUrls.isNotEmpty()) {
      System.err.println(
        "compose-ai-tools desktop daemon: UserClassLoaderHolder active " +
          "(urls=${userClassUrls.size}, dirs=${userClassUrls.map { it.path }})"
      )
      UserClassLoaderHolder(urls = userClassUrls)
    } else null

  // B2.2 phase 1 — load the in-memory preview index from `previews.json`. The gradle plugin's
  // `composePreviewDaemonStart` task emits the absolute path as a sysprop on the daemon JVM (see
  // `composeai.daemon.previewsJsonPath` in AndroidPreviewSupport.kt). When unset (in-process tests,
  // ad-hoc launches) we come up with the empty index — same shape as the pre-B2.2 stub.
  // Loaded BEFORE host construction (was: after) so the host's v2 interactive resolver can consult
  // the index. Index loading is read-only and fast; the reorder is a no-op for everything else.
  val previewsJsonPath = System.getProperty(PreviewIndex.PREVIEWS_JSON_PATH_PROP)
  val previewIndex: PreviewIndex =
    if (!previewsJsonPath.isNullOrBlank()) {
      val loaded = PreviewIndex.loadFromFile(Path.of(previewsJsonPath))
      System.err.println(
        "compose-ai-tools desktop daemon: PreviewIndex loaded " +
          "(path=${loaded.path}, previewCount=${loaded.size})"
      )
      loaded
    } else {
      PreviewIndex.empty()
    }

  // Producer-side registries. Constructed unconditionally so they can be referenced from
  // capture/listener lambdas; their per-render side effects are gated by the [ExtensionRegistry]
  // built below — when an extension is inactive, no callbacks fire.
  val recompositionRegistry = RecompositionDataProductRegistry()
  val themeRegistry = ThemeDataProductRegistry()
  val wallpaperRegistry = WallpaperDataProductRegistry()
  val launcherWidgetRegistry = LauncherWidgetDataProductRegistry()

  // B2.1 — wire Tier-1 classpath fingerprinting (DESIGN § 8). Cheap-signal file set comes from
  // `composeai.daemon.cheapSignalFiles` (set by the gradle plugin's composePreviewDaemonStart).
  // Authoritative classpath comes from this JVM's own `java.class.path`. When the cheap-signal
  // sysprop is unset (in-process tests, ad-hoc launches), the fingerprint is null and the
  // pre-B2.1 no-op behaviour holds.
  val classpathFingerprint: ClasspathFingerprint? =
    ClasspathFingerprint.parseCheapSignalFilesSysprop()
      .takeIf { it.isNotEmpty() }
      ?.let { cheap ->
        val classpath =
          (System.getProperty("java.class.path") ?: "")
            .split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .map { File(it) }
        System.err.println(
          "compose-ai-tools desktop daemon: ClasspathFingerprint active " +
            "(cheap=${cheap.size}, classpath=${classpath.size})"
        )
        ClasspathFingerprint(cheapSignalFiles = cheap, classpathEntries = classpath)
      }

  // B2.2 phase 2 — wire the incremental rescan path. ClassGraph scans are scoped to the smallest
  // classpath element overlapping the saved `.kt` file (see [IncrementalDiscovery]); the diff
  // against [previewIndex] is emitted as `discoveryUpdated`. Skip wiring when the index is empty —
  // there's no baseline to diff against, and a scan-on-every-save with no anchor would only burn
  // CPU on saves the daemon can't reach a useful conclusion about.
  val incrementalDiscovery: IncrementalDiscovery? =
    if (previewIndex.size > 0) {
      val classpath =
        (System.getProperty("java.class.path") ?: "")
          .split(File.pathSeparator)
          .filter { it.isNotBlank() }
          .map { Path.of(it) }
      System.err.println(
        "compose-ai-tools desktop daemon: IncrementalDiscovery active " +
          "(classpath=${classpath.size}, previewCount=${previewIndex.size})"
      )
      IncrementalDiscovery(classpath = classpath)
    } else null

  // History feature gated to 1.1 (see [HistoryFeature]). Until then `historyManager` is null on
  // every launch — `composeai.daemon.historyDir`, the git-ref read sources, prune budgets, and the
  // `historyManager?.setPruneListener` wire in `JsonRpcServer` all elide because the const-folded
  // branch goes dead. HISTORY.md describes the original H1+H2 wiring this block preserves
  // verbatim for the 1.1 re-enable.
  val historyManager: HistoryManager? =
    if (HistoryFeature.ENABLED) {
      val historyDirProp = System.getProperty(HISTORY_DIR_PROP)
      val workspaceRootProp = System.getProperty(WORKSPACE_ROOT_PROP)
      val gitProvenance =
        if (historyDirProp != null) {
          GitProvenance(workspaceRoot = workspaceRootProp?.let(Path::of))
        } else null
      val gitRefHistoryRefs = GitRefHistorySource.parseRefsSysprop()
      val pruneConfig = HistoryPruneConfig.fromSysprops()
      historyDirProp?.let { dir ->
        System.err.println(
          "compose-ai-tools desktop daemon: HistoryManager active (dir=$dir, " +
            "gitRefs=${gitRefHistoryRefs}, pruneConfig=$pruneConfig)"
        )
        HistoryManager.forLocalFsAndGitRefs(
          historyDir = Path.of(dir),
          module = System.getProperty(MODULE_ID_PROP) ?: "",
          gitProvenance = gitProvenance,
          gitRefs = gitRefHistoryRefs,
          repoRoot = workspaceRootProp?.let(Path::of) ?: Path.of(dir).parent,
          pruneConfig = pruneConfig,
        )
      }
    } else null

  // `dataRoot` follows the same layout as `:daemon:android` — `<renderOutputDir>/../data` when
  // `composeai.render.outputDir` is set, else null. Producers that write to disk land their
  // per-render JSON / PNG artefacts under `dataRoot/<previewId>/...`; the matching registries read
  // back from the same location. Computed unconditionally so file-based registries (`fonts/used`,
  // `displayfilter`, ...) can be advertised even when no producer has written yet — `data/fetch`
  // returns `NotAvailable` instead of `-32020 kind not advertised`. See issue #1201.
  val dataRoot: File? =
    System.getProperty(RenderEngine.OUTPUT_DIR_PROP)?.let { renderOutputDir ->
      File(renderOutputDir).parentFile?.resolve("data") ?: File(renderOutputDir)
    }
  val composeTraceEnabled = dataRoot != null && PerfettoTraceDataProducer.enabled()

  // ExtensionRegistry — every contribution the daemon can expose is registered here, all inactive
  // by default. Clients call `extensions/enable` to opt in (typically the MCP supervisor enables a
  // configured allowlist on connect). See docs/daemon/PROTOCOL.md § 3a.
  val extensions =
    ExtensionRegistry(
      buildDesktopExtensions(
        previewIndex = previewIndex,
        recompositionRegistry = recompositionRegistry,
        themeRegistry = themeRegistry,
        wallpaperRegistry = wallpaperRegistry,
        launcherWidgetRegistry = launcherWidgetRegistry,
        historyManager = historyManager,
        dataRoot = dataRoot,
        composeTraceEnabled = composeTraceEnabled,
        displayFilterEnabled = DisplayFilterConfig.fromSystemProperties().isNotEmpty(),
      )
    )

  // Render engine consumes the registry's live override aggregator so `extensions/enable` mid-
  // session takes effect on the next render. PreviewContextCapture gates on `data/theme`'s
  // active state; while theme is inactive the renderer skips the per-render Compose-context
  // capture.
  val renderEngine =
    RenderEngine(
      previewContextCapture =
        object : RenderEngine.PreviewContextCapture {
          override fun shouldCapture(previewId: String?, renderMode: String?): Boolean =
            extensions.isActive("data/theme") && themeRegistry.shouldCapture(previewId, renderMode)
        },
      previewOverrideExtensions = extensions.activeOverrideExtensions(),
    )

  val manifestPath = System.getProperty("composeai.harness.previewsManifest")
  val host: RenderHost =
    if (manifestPath != null && manifestPath.isNotBlank()) {
      val manifest = PreviewManifestRouter.loadManifest(File(manifestPath))
      System.err.println(
        "compose-ai-tools desktop daemon: PreviewManifestRouter active " +
          "(manifest=$manifestPath, previews=${manifest.previews.map { it.id }})"
      )
      PreviewManifestRouter(
        manifest = manifest,
        engine = renderEngine,
        userClassloaderHolder = userClassloaderHolder,
      )
    } else {
      DesktopHost(
        engine = renderEngine,
        userClassloaderHolder = userClassloaderHolder,
        previewSpecResolver =
          previewIndexBackedSpecResolver(previewIndex)?.takeIf { previewIndex.size > 0 },
        // Recomposition session listener gates on the extension's active state — when inactive,
        // the producer doesn't install its CompositionObserver.
        interactiveSessionListener =
          DesktopHost.InteractiveSessionListener { previewId, scene ->
            if (extensions.isActive("data/recomposition")) {
              recompositionRegistry.onSessionLifecycle(previewId, scene)
            }
          },
      )
    }

  // Stage-2 in-process compile service. Reads its config from the
  // `composeai.daemon.bta.*` sysprops the gradle plugin's `DaemonBootstrapTask`
  // populates unconditionally whenever the variant wiring resolved the BTA classpath.
  // Returns null when the sysprops are absent — in that case
  // `JsonRpcServer.compileSources` returns `result=fallback` for every call and
  // the editor falls back to stage 1 (`gradle --continuous`) or stage 0. The editor
  // only dispatches `compileSources` when the VS Code workspace setting
  // `composePreview.daemon.compileInProcess` is on, so a non-null service still costs
  // nothing until that switch is flipped. See docs/daemon/COMPILE-IN-PROCESS.md.
  val btaCompileService = ee.schimke.composeai.daemon.bta.DefaultBtaCompileService.fromSysprops()
  if (btaCompileService != null) {
    System.err.println(
      "compose-ai-tools desktop daemon: BtaCompileService active — `compileSources` JSON-RPC " +
        "will dispatch through the in-process Kotlin Build Tools compiler"
    )
  }

  val server =
    JsonRpcServer(
      input = input,
      output = output,
      host = host,
      daemonVersion = DaemonVersion.value,
      classpathFingerprint = classpathFingerprint,
      previewIndex = previewIndex,
      incrementalDiscovery = incrementalDiscovery,
      historyManager = historyManager,
      extensions = extensions,
      btaCompileService = btaCompileService,
      onExit = onExit,
    )

  if (installSigtermHook) {
    installSigtermShutdownHook(host, originalStdin = input)
  }

  try {
    server.run() // blocks until the client closes the wire
  } finally {
    // Idempotent — JsonRpcServer.cleanShutdown already calls this on the happy path.
    try {
      host.shutdown(timeoutMs = 30_000)
    } catch (t: Throwable) {
      System.err.println("compose-ai-tools desktop daemon: host.shutdown failed: ${t.message}")
    }
  }
}

/**
 * Installs the SIGTERM shutdown hook that enforces the no-mid-render-cancellation invariant from
 * [DESIGN.md § 9](../../../../../../docs/daemon/DESIGN.md#no-mid-render-cancellation--invariant--enforcement)
 * (B-desktop.1.6).
 *
 * **What the hook does, in order**, when SIGTERM arrives (or any other JVM-shutdown trigger fires:
 * `System.exit`, last non-daemon thread exiting, `Ctrl-C` on a foreground process, etc.):
 *
 * 1. Closes [originalStdin]. The [JsonRpcServer.run] read loop is blocked in
 *    `InputStream.read(...)`; closing the stream surfaces an `IOException` / EOF and the loop
 *    breaks. This is option (a) from the B-desktop.1.6 task brief — "close `System.in` from the
 *    shutdown hook so the loop sees EOF" — chosen over adding a `requestStop()` API to
 *    `:daemon:core` because it doesn't widen the core surface. The trade-off is the read loop still
 *    walks through its own EOF→idle-timeout path before reaching `cleanShutdown`; the drain we care
 *    about (host render thread) is handled by step 2 below, independently.
 * 2. Calls [RenderHost.shutdown] with the timeout from `composeai.daemon.idleTimeoutMs` (capped at
 *    the JVM's default 30s shutdown-hook grace window — JVMs kill non-daemon hooks that exceed
 *    this). [DesktopHost.shutdown] enqueues a poison pill on the render queue and joins the worker
 *    thread, so an in-flight `RenderEngine.render` finishes (including its `try/finally`
 *    `scene.close()` from B-desktop.1.4) before the JVM proceeds with exit.
 *
 * **The crucial difference from the JSON-RPC `shutdown` request path.** `JsonRpcServer.shutdown`
 * already drains the in-flight queue before resolving (PROTOCOL.md § 3). That handler runs on the
 * read thread. The SIGTERM hook runs on a JVM-owned shutdown thread *concurrently with* the read
 * thread — so the host gets two `shutdown()` calls (one from the hook, one from `cleanShutdown` on
 * the read thread's EOF path). [DesktopHost.shutdown] is idempotent, so this is fine; the second
 * call observes the worker already gone and returns immediately.
 *
 * **What we cannot defend against.** SIGKILL (`kill -9`) bypasses shutdown hooks entirely — the
 * kernel kills the JVM mid-syscall. An in-flight `ImageComposeScene` will leak its Skia native
 * `Surface` (and the JVM's classloader graph), but the daemon process is also gone, so the leak
 * doesn't span renders. There is nothing we can do about this in user code; the only mitigation is
 * the gradle plugin / VS Code client preferring SIGTERM over SIGKILL for routine daemon disposal.
 *
 * **Manual smoke test.** Run `./gradlew :daemon:desktop:runDaemonMain` in one terminal, note the
 * PID printed in the hello banner, and `kill -TERM <pid>` from another terminal. The hook's
 * "draining…" line lands on stderr, [DesktopHost.shutdown] returns once the worker is gone, and the
 * JVM exits within ~1s for an idle daemon (the time taken by `host.shutdown()` plus the read loop's
 * EOF→idleTimeout-sleep walk; the latter is bounded by the `composeai.daemon.idleTimeoutMs` system
 * property, default 5s).
 */
/** HISTORY.md § "What this PR lands § H1" — null disables history. */
private const val HISTORY_DIR_PROP = "composeai.daemon.historyDir"

/** Optional override for git-provenance resolution; defaults to JVM CWD. */
private const val WORKSPACE_ROOT_PROP = "composeai.daemon.workspaceRoot"

/** Module project path stamped into every history entry's `module` field. */
private const val MODULE_ID_PROP = "composeai.daemon.moduleId"

/**
 * Adapts a [PreviewIndex] into the `(previewId) -> RenderSpec?` lambda [DesktopHost] consumes for
 * v2 interactive sessions. PreviewIndex carries className + methodName plus the optional `params`
 * block widened in issue #420; this resolver threads each present field into the corresponding
 * [RenderSpec] knob and falls back to the [RenderSpec] defaults (320x320 sandbox, density 2.0,
 * white background, no locale/font-scale/uiMode/orientation override) for absent ones.
 *
 * **Per-field fallback, not all-or-nothing.** A `@Preview(widthDp = 200)` with no `heightDp` set
 * lands on the resolver as `widthDp = 200, heightDp = null` and produces `widthPx = 200 * density`
 * + `heightPx = 320` (the default). This mirrors how `PreviewOverrides` merges over the
 *   discovery-time spec on the `renderNow` path — see PROTOCOL.md § 5.
 *
 * **Density precedence.** When `params.density` is set, it drives both the `widthDp → widthPx`
 * conversion and the `RenderSpec.density` field. When `density` is null but `widthDp` is set, the
 * conversion uses the default density (2.0) — same arithmetic the production discovery emitter uses
 * for "no device, no system UI" previews (see `DeviceDimensions.DEFAULT_DENSITY`).
 *
 * **`uiMode` decode.** The plugin's `PreviewParams.uiMode` is a raw Android `Configuration.uiMode`
 * bitmask (0 = unset). [uiModeIsNight] checks the night bit (0x20); when set, the resolver maps it
 * onto `RenderSpec.SpecUiMode.DARK`, otherwise leaves the spec's `uiMode` null so Compose Desktop's
 * `LocalSystemTheme.Unknown` fallback fires.
 *
 * **Orientation is plugin-not-emitted-today.** The plugin's `PreviewParams` doesn't carry an
 * orientation field — `@Preview` annotations don't have an `orientation =` parameter. Left unwired
 * here; if a future plugin pass derives portrait/landscape from `widthDp > heightDp`, the params
 * DTO already has the slot and the resolver picks it up.
 *
 * Returns `null` when [previewId] isn't in the index — the host translates that into
 * `UnsupportedOperationException`, JsonRpcServer falls back to v1 dispatch, the panel keeps working
 * without held-state semantics.
 */
private fun previewIndexBackedSpecResolver(previewIndex: PreviewIndex): ((String) -> RenderSpec?)? {
  return { previewId -> previewIndex.byId(previewId)?.let { renderSpecFromInfo(it) } }
}

/**
 * Builds a [RenderSpec] from a [PreviewInfoDto], honouring the optional `params` block widened in
 * issue #420 and falling back to the [RenderSpec] defaults for every absent field. Pulled into a
 * top-level helper (rather than inlined into [previewIndexBackedSpecResolver]) so unit tests can
 * exercise the conversion without standing up a [PreviewIndex] + lambda.
 */
internal fun renderSpecFromInfo(info: PreviewInfoDto): RenderSpec {
  val defaults =
    RenderSpec(previewId = info.id, className = info.className, functionName = info.methodName)
  val params = info.params ?: return defaults
  val density = params.density ?: defaults.density
  val widthPx = params.widthDp?.let { (it * density).toInt() } ?: defaults.widthPx
  val heightPx = params.heightDp?.let { (it * density).toInt() } ?: defaults.heightPx
  val uiMode = if (uiModeIsNight(params.uiMode)) RenderSpec.SpecUiMode.DARK else defaults.uiMode
  return RenderSpec(
    previewId = info.id,
    className = info.className,
    functionName = info.methodName,
    widthPx = widthPx,
    heightPx = heightPx,
    density = density,
    showBackground = params.showBackground ?: defaults.showBackground,
    backgroundColor = params.backgroundColor ?: defaults.backgroundColor,
    device = params.device ?: defaults.device,
    outputBaseName = defaults.outputBaseName,
    localeTag = params.locale ?: defaults.localeTag,
    fontScale = params.fontScale ?: defaults.fontScale,
    uiMode = uiMode,
    orientation = defaults.orientation,
    wrapperClassName = params.wrapperClassName ?: defaults.wrapperClassName,
  )
}

private fun installSigtermShutdownHook(host: RenderHost, originalStdin: java.io.InputStream) {
  // Same property the JsonRpcServer reads, so a single sysprop tunes both timeouts coherently.
  // Default 30s — matches the existing `finally`-block defensive shutdown above and most JVMs'
  // shutdown-hook grace window.
  val timeoutMs =
    System.getProperty(JsonRpcServer.IDLE_TIMEOUT_PROP)?.toLongOrNull()?.coerceAtMost(30_000L)
      ?: 30_000L

  Runtime.getRuntime()
    .addShutdownHook(
      Thread(
        {
          System.err.println(
            "compose-ai-tools desktop daemon: SIGTERM received, draining in-flight renders " +
              "(timeoutMs=$timeoutMs)"
          )
          // Close stdin to break the read loop out of its blocking read() — same effect as the
          // client closing the wire, which JsonRpcServer.readLoop already handles. Best-effort:
          // if the stream is already closed (or we're being called twice), swallow.
          try {
            originalStdin.close()
          } catch (_: Throwable) {
            // ignore — we just want the read loop to stop reading new requests.
          }
          try {
            host.shutdown(timeoutMs = timeoutMs)
          } catch (t: Throwable) {
            System.err.println(
              "compose-ai-tools desktop daemon: SIGTERM hook host.shutdown failed: ${t.message}"
            )
          }
          System.err.println("compose-ai-tools desktop daemon: drain complete, JVM exiting")
        },
        "compose-ai-daemon-sigterm-hook",
      )
    )
}

/**
 * Builds the extension list registered on the desktop daemon. Extracted from [runDaemon] so unit
 * tests can assert the registered ids without spinning up a full JSON-RPC server.
 *
 * **Per-backend parity with `:daemon:android`'s `DaemonMain`** (issue #1201). The two backends
 * register different subsets because some producers are Android-API-bound (`uiautomator`,
 * Robolectric ATF a11y, `Resources.getValue` interception). The registries below are file-based and
 * portable: when no producer has written, `data/fetch` returns `NotAvailable` rather than the wire
 * `-32020 kind not advertised`, which is what the panel needs to gate its chips correctly.
 *
 * Kinds whose producer is genuinely Android-bound (`resources/used`, `uiautomator`) are NOT
 * registered here — see issue #1201 for the per-kind portability triage. They stay unadvertised on
 * desktop; the panel should honour `ServerCapabilities.backend == "desktop"` to grey out the
 * corresponding chips.
 *
 * `a11y` IS registered (overlay-only): ATF itself is Android-only, but the "what a screen reader
 * sees" overlay + legend is portable — the desktop producer extracts Compose semantics from the
 * scene and draws the overlay with AWT, shipping empty findings. See
 * [DesktopAccessibilityDataProductRegistry].
 */
internal fun buildDesktopExtensions(
  previewIndex: PreviewIndex,
  recompositionRegistry: RecompositionDataProductRegistry,
  themeRegistry: ThemeDataProductRegistry,
  wallpaperRegistry: WallpaperDataProductRegistry,
  launcherWidgetRegistry: LauncherWidgetDataProductRegistry,
  historyManager: HistoryManager?,
  dataRoot: File?,
  composeTraceEnabled: Boolean,
  displayFilterEnabled: Boolean,
): List<Extension> = buildList {
  tryAdd("device/clip") {
    Extension(
      id = "device/clip",
      displayName = "Device clip",
      dataProductRegistry = DeviceClipDataProductRegistry(previewIndex = previewIndex),
      previewExtensionDescriptors = listOf(RenderPreviewExtension.deviceClipDescriptor),
    )
  }
  tryAdd("device/background") {
    Extension(
      id = "device/background",
      displayName = "Device background",
      dataProductRegistry = DeviceBackgroundDataProductRegistry(previewIndex = previewIndex),
      previewExtensionDescriptors = listOf(RenderPreviewExtension.deviceBackgroundDescriptor),
    )
  }
  tryAdd("render/trace") {
    Extension(
      id = "render/trace",
      displayName = "Render trace",
      dataProductRegistry = RenderTraceDataProductRegistry(),
      previewExtensionDescriptors = listOf(RenderPreviewExtension.renderTraceDescriptor),
    )
  }
  tryAdd("render/test-failure") {
    Extension(
      id = "render/test-failure",
      displayName = "Test failure",
      dataProductRegistry = TestFailureDataProductRegistry(),
    )
  }
  tryAdd("render/overlay-legend") {
    Extension(
      id = "render/overlay-legend",
      displayName = "Render overlay legend",
      previewExtensionDescriptors = listOf(RenderPreviewExtension.overlayLegendDescriptor),
    )
  }
  tryAdd("data/theme") {
    Extension(
      id = "data/theme",
      displayName = "Material 3 theme override",
      dataProductRegistry = themeRegistry,
      previewOverrideExtensions = listOf(Material3ThemePreviewOverrideExtension()),
    )
  }
  tryAdd("data/wallpaper") {
    Extension(
      id = "data/wallpaper",
      displayName = "Wallpaper override",
      dataProductRegistry = wallpaperRegistry,
      previewOverrideExtensions = listOf(WallpaperPreviewOverrideExtension()),
    )
  }
  tryAdd("data/focus") {
    // Issue #1205 — focus / keyboard-traversal override. The planner reads
    // `renderNow.overrides.focus` and emits the around-composable that flips
    // `LocalInputModeManager` to keyboard mode and drives `FocusManager.moveFocus(...)`.
    // Android wires the same planner from `:data-focus-connector` directly into the
    // RenderEngine's `previewOverrideExtensions` list (see RobolectricHost); the desktop
    // backend instead routes through the extension registry so `extensions/list` reports
    // `data/focus` and the override only fires when the extension is active.
    Extension(
      id = "data/focus",
      displayName = "Focus override",
      previewOverrideExtensions = listOf(FocusPreviewOverrideExtension()),
    )
  }
  tryAdd("data/keyboard") {
    // Soft-keyboard (IME) overlay. Planner always emits the extension so the shadow
    // `LocalSoftwareKeyboardController` is in place for every render and app-side
    // `keyboardController.show()` / focused `BasicTextField` raises the band naturally;
    // `renderNow.overrides.keyboard` and `interactive/input` `KEY_*` dispatches also feed the
    // same `KeyboardController`.
    //
    // [dataExtensionDescriptors] advertises the connector's `KeyboardOverrideExtension.ID` so the
    // panel / MCP can discover it via `initialize.capabilities.dataExtensions` and gate the
    // per-card "force soft-keyboard band" toggle on the daemon actually shipping the extension —
    // rather than the current broad "any interactive backend" gate.
    Extension(
      id = "data/keyboard",
      displayName = "Soft keyboard overlay",
      previewOverrideExtensions = listOf(KeyboardPreviewOverrideExtension()),
      dataExtensionDescriptors =
        listOf(
          DataExtensionDescriptor(
            id = KeyboardOverrideExtension.ID,
            displayName = "Soft keyboard overlay",
          )
        ),
    )
  }
  tryAdd("data/pseudolocale") {
    Extension(
      id = "data/pseudolocale",
      displayName = "Pseudolocale (desktop)",
      previewOverrideExtensions = listOf(PseudolocalePreviewOverrideExtensionDesktop()),
    )
  }
  tryAdd("data/touch-overlay") {
    // Touch-event visualization overlay (`AroundComposableHook`) — paints a translucent ring at
    // every active pointer plus short-lived expanding pulses on down / up, same shape as Android's
    // "Show touches" developer-mode toggle. Activated when `renderNow.overrides.touchOverlay =
    // true`
    // OR (automatically) when a live recording session starts — see
    // `DesktopHost.acquireRecordingSession`. The around-composable observes pointer events via
    // `Modifier.pointerInput` on the Initial pass without consuming them, so the inner preview's
    // gesture detectors (`Modifier.transformable`, `Modifier.clickable`, …) keep working
    // unchanged. The planner lives in the shared `:data-touch-overlay-connector` module so both
    // backends register the same `TouchOverlayPreviewOverrideExtension` — Android wires it from
    // `RobolectricHost.previewOverrideExtensions` and advertises the extension descriptor from
    // `:daemon:android`'s `DaemonMain`.
    //
    // [dataExtensionDescriptors] advertises `TouchOverlayExtension.ID` so the panel / MCP can
    // discover the extension via `initialize.capabilities.dataExtensions` and gate the per-card
    // "touch overlay" toggle on the daemon actually shipping it.
    Extension(
      id = "data/touch-overlay",
      displayName = "Touch event overlay",
      previewOverrideExtensions = listOf(TouchOverlayPreviewOverrideExtension()),
      dataExtensionDescriptors =
        listOf(
          DataExtensionDescriptor(
            id = TouchOverlayExtension.ID,
            displayName = "Touch event overlay",
          )
        ),
    )
  }
  tryAdd("data/launcher-widget") {
    // Launcher-widget container-size override. The around-composable wraps the preview body in a
    // sized `Box` matching the clamped whole-cell footprint on a launcher grid. Activated by
    // `renderNow.overrides.launcherWidget = LauncherWidgetOverride(cells = ...)`. Snap-only — a
    // future daemon-side resize-loop orchestrator (issue: launcher-widget resize loop) will walk
    // intermediate stops via `launcherWidgetStops(...)` and emit one render per stop.
    Extension(
      id = "data/launcher-widget",
      displayName = "Launcher widget container size",
      dataProductRegistry = launcherWidgetRegistry,
      previewOverrideExtensions = listOf(LauncherWidgetPreviewOverrideExtension()),
      dataExtensionDescriptors =
        listOf(
          DataExtensionDescriptor(
            id = LauncherWidgetExtension.ID,
            displayName = "Launcher widget container size",
          )
        ),
    )
  }
  tryAdd("data/recomposition") {
    Extension(
      id = "data/recomposition",
      displayName = "Recomposition counters",
      dataProductRegistry = recompositionRegistry,
    )
  }
  if (dataRoot != null) {
    // Issue #1201 — file-based registries that are portable to desktop. The producer side is
    // currently Android-only for `fonts/used` (the GoogleFontInterceptor / Typeface accounting
    // path); the registry returns NotAvailable on desktop until a Skia-side font producer lands.
    // `displayfilter` is fully portable (BufferedImage post-capture) and gated on the same sysprop
    // the Android side reads.
    tryAdd("fonts/used") {
      Extension(
        id = "fonts/used",
        displayName = "Fonts used",
        dataProductRegistry = FontsUsedDataProductRegistry(rootDir = dataRoot),
      )
    }
    // Phase 2 (#1201): layoutinspector + strings registries. The connector modules were migrated
    // to Compose Multiplatform JVM so desktop can depend on them; the producers stay Robolectric-
    // bound for now so these return `NotAvailable` until a CMP-portable producer ports. The point
    // of advertising them is to stop the panel's chips logging `-32020 kind not advertised` on
    // CMP-desktop sessions.
    tryAdd("compose/semantics") {
      Extension(
        id = "compose/semantics",
        displayName = "Compose semantics snapshot",
        dataProductRegistry = ComposeSemanticsDataProductRegistry(rootDir = dataRoot),
      )
    }
    tryAdd("layout/inspector") {
      Extension(
        id = "layout/inspector",
        displayName = "Layout inspector",
        dataProductRegistry = LayoutInspectorDataProductRegistry(rootDir = dataRoot),
      )
    }
    tryAdd("text/strings") {
      Extension(
        id = "text/strings",
        displayName = "Text strings",
        dataProductRegistry =
          TextStringsDataProductRegistry(rootDir = dataRoot, previewIndex = previewIndex),
      )
    }
    tryAdd("i18n/translations") {
      Extension(
        id = "i18n/translations",
        displayName = "i18n translations",
        dataProductRegistry = I18nTranslationsDataProductRegistry(rootDir = dataRoot),
      )
    }
    // Phase 4 (#1201): navigation registry. Producer side stays in `:daemon:android`
    // (`Intent` reflection); the registry returns `NotAvailable` on desktop until a CMP-portable
    // producer driving `NavController` lands. Advertising it is enough to stop the panel's
    // navigation chip tripping `-32020 kind not advertised`.
    tryAdd("data/navigation") {
      Extension(
        id = "data/navigation",
        displayName = "Navigation snapshot",
        dataProductRegistry = NavigationDataProductRegistry(rootDir = dataRoot),
      )
    }
    // Issue #1604 — daemon-side scroll artefact production on CMP-desktop. Unlike the registries
    // above (whose producers are still Android-bound), scroll is fully portable: the registry
    // lives in the pure-JVM `:data-scroll-connector`, and `:renderer-desktop` already carries the
    // `runComposeUiTest`-driven capture. The registry advertises `render/scroll/long` /
    // `render/scroll/gif` as `requiresRerender = true`, so a missing scroll artefact returns
    // `Outcome.RequiresRerender("scroll-long"|"scroll-gif")` and the dispatcher queues a
    // per-preview re-render that `RenderEngine.runScrollScenario` routes into
    // `renderScrollPreview`, writing to the same
    // `<dataRoot>/render-scroll-{long,gif}/<id>.{png,gif}`
    // paths Gradle does so the host's `gradleService.readPreviewImage` reads the same file either
    // way. Descriptors are advertised too so MCP / `previewExtensions/list` clients see the scroll
    // surface — exactly mirroring `:daemon:android`'s `DaemonMain`.
    tryAdd("scroll") {
      Extension(
        id = "scroll",
        displayName = "Scrolling preview artifacts",
        dataProductRegistry = ScrollDataProductRegistry(rootDir = dataRoot),
        previewExtensionDescriptors =
          listOf(
            ee.schimke.composeai.scroll.ScrollPreviewExtension.longScrollDescriptor,
            ee.schimke.composeai.scroll.ScrollPreviewExtension.gifScrollDescriptor,
          ),
      )
    }
    // Accessibility (desktop, overlay-only). Unlike Android — where the producer runs ATF over the
    // Robolectric View tree — the desktop path extracts Compose semantics from the scene's
    // `semanticsOwners` and draws the Paparazzi-style overlay + legend with AWT (see
    // `DesktopAccessibility*`). ATF is Android-only, so findings are always empty; `a11y/atf` ships
    // an empty `findings` array so the CLI's per-preview fetch parses and the module report stays
    // `status=null` (no global "ATF data unavailable" banner). No `previewExtensionDescriptors` —
    // the overlay is produced post-capture by the RenderEngine, not by an around-composable.
    tryAdd("a11y") {
      Extension(
        id = "a11y",
        displayName = "Accessibility (desktop, overlay-only)",
        dataProductRegistry = DesktopAccessibilityDataProductRegistry(rootDir = dataRoot),
      )
    }
    if (displayFilterEnabled) {
      tryAdd("displayfilter") {
        Extension(
          id = "displayfilter",
          displayName = "Display filter variants",
          dataProductRegistry = DisplayFilterDataProductRegistry(rootDir = dataRoot),
        )
      }
    }
    if (composeTraceEnabled) {
      tryAdd("compose/trace") {
        Extension(
          id = "compose/trace",
          displayName = "Compose Perfetto trace",
          dataProductRegistry = PerfettoTraceDataProductRegistry(rootDir = dataRoot),
          previewExtensionDescriptors = listOf(RenderPreviewExtension.composeTraceDescriptor),
        )
      }
    }
  }
  if (historyManager != null) {
    tryAdd("history/diff-regions") {
      Extension(
        id = "history/diff-regions",
        displayName = "History diff regions",
        dataProductRegistry = HistoryDiffRegionsDataProductRegistry(historyManager = historyManager),
      )
    }
  }
  // Recording-script extensions are descriptor-only on the daemon side — the host's session
  // registry decides what's actually dispatchable. The roadmap descriptors are advertised so
  // panels can grey out unimplemented actions.
  tryAdd("recording/script") {
    Extension(
      id = "recording/script",
      displayName = "Recording-script extensions",
      dataExtensionDescriptors = RecordingScriptDataExtensions.roadmapDescriptors,
    )
  }
}

/**
 * Adds [build]'s result to the list, catching `LinkageError` (`NoClassDefFoundError` /
 * `ClassNotFoundException`-shaped failures) so one missing connector module's class does not crash
 * the entire daemon process. Mirrors the helper in `:daemon:android`'s DaemonMain — see the
 * rationale comment there.
 */
private inline fun MutableList<Extension>.tryAdd(label: String, build: () -> Extension) {
  try {
    add(build())
  } catch (e: LinkageError) {
    System.err.println(
      "compose-ai-tools daemon: extension '$label' unavailable on this classpath — " +
        "${e.javaClass.simpleName}: ${e.message}"
    )
  }
}
