@file:JvmName("DaemonMain")

package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.history.GitProvenance
import ee.schimke.composeai.daemon.history.GitRefHistorySource
import ee.schimke.composeai.daemon.history.HistoryManager
import ee.schimke.composeai.daemon.history.HistoryPruneConfig
import java.io.File
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
  // stderr; the JSON-RPC channel is the captured `realOut`.
  val realOut = System.out
  System.setOut(System.err)

  System.err.println("compose-ai-tools desktop daemon: hello (args=${args.toList()})")

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

  val manifestPath = System.getProperty("composeai.harness.previewsManifest")
  val host: RenderHost =
    if (manifestPath != null && manifestPath.isNotBlank()) {
      val manifest = PreviewManifestRouter.loadManifest(File(manifestPath))
      System.err.println(
        "compose-ai-tools desktop daemon: PreviewManifestRouter active " +
          "(manifest=$manifestPath, previews=${manifest.previews.map { it.id }})"
      )
      PreviewManifestRouter(manifest = manifest, userClassloaderHolder = userClassloaderHolder)
    } else {
      DesktopHost(userClassloaderHolder = userClassloaderHolder)
    }
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

  // B2.2 phase 1 — load the in-memory preview index from `previews.json`. The gradle plugin's
  // `composePreviewDaemonStart` task emits the absolute path as a sysprop on the daemon JVM (see
  // `composeai.daemon.previewsJsonPath` in AndroidPreviewSupport.kt). When unset (in-process tests,
  // ad-hoc launches) we come up with the empty index — same shape as the pre-B2.2 stub.
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

  // H1+H2 — wire the per-render history archive. Path comes from `composeai.daemon.historyDir`
  // (the gradle plugin's daemon launch descriptor will emit this in a future task; for now agents
  // and ad-hoc launches set it manually). The default is null = no history written. Workspace
  // root for git-provenance resolution comes from `composeai.daemon.workspaceRoot`, with the JVM
  // CWD as the fallback. See HISTORY.md § "What this PR lands § H1".
  val historyDirProp = System.getProperty(HISTORY_DIR_PROP)
  val workspaceRootProp = System.getProperty(WORKSPACE_ROOT_PROP)
  val gitProvenance =
    if (historyDirProp != null) {
      GitProvenance(workspaceRoot = workspaceRootProp?.let(Path::of))
    } else null
  // H10-read — when `composeai.daemon.gitRefHistory` is set (comma-separated list of full ref
  // names like `refs/heads/preview/main`), each ref produces a read-only [GitRefHistorySource]
  // alongside the writable [LocalFsHistorySource]. Refs that don't exist locally trigger a
  // one-time warn-level `log` notification with a hint for the human and degrade gracefully
  // (no entries in `history/list`). See HISTORY.md § "GitRefHistorySource".
  val gitRefHistoryRefs = GitRefHistorySource.parseRefsSysprop()
  // H4 — prune config from sysprops (defaults: 50 entries / 14 days / 500 MB / 1h auto interval).
  val pruneConfig = HistoryPruneConfig.fromSysprops()
  val historyManager: HistoryManager? = historyDirProp?.let { dir ->
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

  val server =
    JsonRpcServer(
      input = System.`in`,
      output = realOut,
      host = host,
      classpathFingerprint = classpathFingerprint,
      previewIndex = previewIndex,
      incrementalDiscovery = incrementalDiscovery,
      historyManager = historyManager,
    )

  installSigtermShutdownHook(host, originalStdin = System.`in`)

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
