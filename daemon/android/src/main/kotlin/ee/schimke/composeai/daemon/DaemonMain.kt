@file:JvmName("DaemonMain")

package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.bridge.DaemonHostBridge
import ee.schimke.composeai.daemon.history.GitProvenance
import ee.schimke.composeai.daemon.history.GitRefHistorySource
import ee.schimke.composeai.daemon.history.HistoryManager
import ee.schimke.composeai.daemon.history.HistoryPruneConfig
import java.io.File
import java.nio.file.Path

/**
 * Entry point for the preview daemon JVM — see docs/daemon/DESIGN.md § 4.
 *
 * The Gradle plugin's `composePreviewDaemonStart` task points its launch
 * descriptor at `ee.schimke.composeai.daemon.DaemonMain` (see
 * [`AndroidPreviewSupport.kt:974`](../../../../../../gradle-plugin/src/main/kotlin/ee/schimke/composeai/plugin/AndroidPreviewSupport.kt#L974)),
 * which the JVM resolves via the file-level [JvmName] annotation above.
 *
 * Lifecycle:
 *
 * 1. Print a hello banner to stderr (free-form log per PROTOCOL.md § 1).
 * 2. Build a [RobolectricHost] (B1.3 — holds the Robolectric sandbox open
 *    across renders). Implements the renderer-agnostic [RenderHost] from
 *    `:daemon:core`. **D-harness.v2:** when
 *    `composeai.harness.previewsManifest=<path>` is set, wrap with
 *    [PreviewManifestRouter] so the harness's `previewId=<id>` payload
 *    resolves to a parseable [RenderSpec]. Mirrors
 *    `:daemon:desktop`'s wireup. Production launches don't pass the
 *    sysprop, so production behaviour is unchanged.
 * 3. Build a [JsonRpcServer] (B1.5 — JSON-RPC 2.0 over stdio with LSP-style
 *    Content-Length framing). Lives in `:daemon:core`; binds to any
 *    [RenderHost] implementation.
 * 4. [JsonRpcServer.run] blocks until the client sends `shutdown` + `exit`
 *    or stdin closes; it calls `System.exit` itself.
 *
 * `args` is currently unused; future flags (e.g. `--detect-leaks=heavy`,
 * `--foreground`) will be parsed here.
 */
fun main(args: Array<String>) {
  // D-harness.v2 — capture the real stdout *before* swapping. Robolectric (and Roborazzi) write
  // diagnostic messages directly to `System.out` during sandbox bootstrap and HardwareRenderer
  // setup (e.g. "This workaround is used when an ActionBar is present and the SDK version is 35
  // or higher."). The JSON-RPC channel is the captured `realOut`; everything else lands on
  // stderr (free-form log per [PROTOCOL.md § 1]). Mirrors `:daemon:desktop`'s
  // [DaemonMain][ee.schimke.composeai.daemon.DaemonMain] (B-desktop.1.5).
  val realOut = System.out
  System.setOut(System.err)

  System.err.println("compose-ai-tools daemon: hello (args=${args.toList()})")

  // D-harness.v2 — when the harness drives real-mode runs it sets
  // `composeai.harness.previewsManifest=<json>` so the daemon can resolve the protocol-level
  // previewId (forwarded by JsonRpcServer as `payload="previewId=<id>"`) into a parseable
  // RenderSpec via `PreviewManifestRouter`. Production launches don't set this sysprop, so the
  // plain RobolectricHost path is unchanged.
  // B2.0 — build the disposable user-class holder from `composeai.daemon.userClassDirs` (set by
  // the gradle plugin's daemon launch descriptor). The holder's child URLClassLoader is mirrored
  // into `DaemonHostBridge.childLoaderRef` so the sandbox-side `RenderEngine.render` resolves
  // preview classes against the recompiled bytecode after every `fileChanged({ kind: "source" })`
  // swap. When the sysprop is unset (legacy/in-process tests), the holder is null and the legacy
  // sandbox-classpath path stays — pre-B2.0 behaviour.
  val userClassUrls = UserClassLoaderHolder.urlsFromSysprop()
  val hasUserClasses = userClassUrls.isNotEmpty()
  if (hasUserClasses) {
    System.err.println(
      "compose-ai-tools daemon: UserClassLoaderHolder active " +
        "(urls=${userClassUrls.size}, dirs=${userClassUrls.map { it.path }})"
    )
  }

  // SANDBOX-POOL.md (Layer 3) — read the supervisor-supplied sandbox-count knob. The
  // DaemonSupervisor passes `composeai.daemon.sandboxCount = 1 + replicasPerDaemon` via the
  // launch descriptor's systemProperties; sandbox pooling collapses what used to be N separate
  // daemon JVMs into one JVM with N sandbox slots. Default 1 preserves the pre-pool single-
  // sandbox behaviour bit-for-bit.
  val sandboxCount =
    (System.getProperty(SANDBOX_COUNT_PROP)?.toIntOrNull() ?: 1).coerceAtLeast(1)

  // SANDBOX-POOL-FOLLOWUPS.md (#1) — per-slot child loaders. The factory closes over the URL
  // list and constructs one holder per slot, parented to the slot's own sandbox classloader. The
  // host invokes the factory lazily on first dispatch to each slot, after the sandbox prologue
  // has registered its loader on `DaemonHostBridge.slot(i).sandboxClassLoaderRef`.
  val userClassloaderHolderFactory: ((sandboxClassLoader: ClassLoader) -> UserClassLoaderHolder)? =
    if (hasUserClasses) {
      { sandboxClassLoader ->
        UserClassLoaderHolder(urls = userClassUrls, parentSupplier = { sandboxClassLoader })
      }
    } else null

  val manifestPath = System.getProperty("composeai.harness.previewsManifest")
  val host: RenderHost =
    if (manifestPath != null && manifestPath.isNotBlank()) {
      val manifest = PreviewManifestRouter.loadManifest(File(manifestPath))
      System.err.println(
        "compose-ai-tools daemon: PreviewManifestRouter active " +
          "(manifest=$manifestPath, previews=${manifest.previews.map { it.id }})"
      )
      // The harness's PreviewManifestRouter wraps a single RobolectricHost; sandbox pooling is
      // an optimisation for the production daemon path, not the harness mode (which exists to
      // exercise the wire protocol against in-process fakes). Stay at sandboxCount=1 here and
      // use the legacy single-instance holder (PreviewManifestRouter still takes that form).
      val singletonHolder: UserClassLoaderHolder? =
        userClassloaderHolderFactory?.let { factory ->
          UserClassLoaderHolder(
            urls = userClassUrls,
            parentSupplier = {
              DaemonHostBridge.currentSandboxClassLoader()
                ?: error(
                  "DaemonHostBridge.sandboxClassLoaderRef is null — sandbox prologue didn't run. " +
                    "Did SandboxHoldingRunner.holdSandboxOpen execute setSandboxClassLoader before " +
                    "the host called publishChildLoader?"
                )
            },
          )
        }
      PreviewManifestRouter(manifest = manifest, userClassloaderHolder = singletonHolder)
    } else {
      if (sandboxCount > 1) {
        System.err.println(
          "compose-ai-tools daemon: sandbox pool active (sandboxCount=$sandboxCount)"
        )
      }
      RobolectricHost(
        userClassloaderHolderFactory = userClassloaderHolderFactory,
        sandboxCount = sandboxCount,
      )
    }

  // B2.1 — wire Tier-1 classpath fingerprinting (DESIGN § 8). Mirrors the desktop daemon's
  // construction shape — cheap-signal set from `composeai.daemon.cheapSignalFiles`, authoritative
  // hash from this JVM's `java.class.path`. Sysprop unset → null fingerprint → pre-B2.1 no-op.
  val classpathFingerprint: ClasspathFingerprint? =
    ClasspathFingerprint.parseCheapSignalFilesSysprop().takeIf { it.isNotEmpty() }?.let { cheap ->
      val classpath =
        (System.getProperty("java.class.path") ?: "")
          .split(File.pathSeparator)
          .filter { it.isNotBlank() }
          .map { File(it) }
      System.err.println(
        "compose-ai-tools daemon: ClasspathFingerprint active " +
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
        "compose-ai-tools daemon: PreviewIndex loaded " +
          "(path=${loaded.path}, previewCount=${loaded.size})"
      )
      loaded
    } else {
      PreviewIndex.empty()
    }

  // B2.2 phase 2 — wire the incremental rescan path. Mirrors `:daemon:desktop`'s wireup; the
  // ClassGraph scan happens against this JVM's `java.class.path` and is scoped to the smallest
  // classpath element overlapping the saved `.kt` file (see [IncrementalDiscovery]). Skip wiring
  // when the index is empty — no baseline → scan-on-save has nothing to diff against.
  val incrementalDiscovery: IncrementalDiscovery? =
    if (previewIndex.size > 0) {
      val classpath =
        (System.getProperty("java.class.path") ?: "")
          .split(File.pathSeparator)
          .filter { it.isNotBlank() }
          .map { Path.of(it) }
      System.err.println(
        "compose-ai-tools daemon: IncrementalDiscovery active " +
          "(classpath=${classpath.size}, previewCount=${previewIndex.size})"
      )
      IncrementalDiscovery(classpath = classpath)
    } else null

  // H1+H2 — wire the per-render history archive. Path comes from `composeai.daemon.historyDir`;
  // workspace root for git-provenance resolution comes from `composeai.daemon.workspaceRoot` (JVM
  // CWD when unset). Mirrors the desktop daemon's wireup. See HISTORY.md § "What this PR lands".
  val historyDirProp = System.getProperty(HISTORY_DIR_PROP)
  val workspaceRootProp = System.getProperty(WORKSPACE_ROOT_PROP)
  val gitProvenance =
    if (historyDirProp != null) {
      GitProvenance(workspaceRoot = workspaceRootProp?.let(Path::of))
    } else null
  // H10-read — see desktop DaemonMain for the design rationale.
  val gitRefHistoryRefs = GitRefHistorySource.parseRefsSysprop()
  // H4 — prune config from sysprops (defaults: 50 entries / 14 days / 500 MB / 1h auto interval).
  val pruneConfig = HistoryPruneConfig.fromSysprops()
  val historyManager: HistoryManager? =
    historyDirProp?.let { dir ->
      System.err.println(
        "compose-ai-tools daemon: HistoryManager active (dir=$dir, gitRefs=${gitRefHistoryRefs}, " +
          "pruneConfig=$pruneConfig)"
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

  // D2 — wire the accessibility data-product registry. Always-on when the renders dir is
  // resolvable: the registry advertises `a11y/atf` + `a11y/hierarchy` and reads the JSON files
  // RenderEngine writes under `<outputDir.parent>/data/<id>/`. The registry never blocks
  // rendering; missing files surface as "no attachment for this kind on this render". Setting
  // [RenderEngine.ATTACH_A11Y_PROP] flips the renderer into a11y mode so the JSON ever lands.
  val renderOutputDir = System.getProperty(RenderEngine.OUTPUT_DIR_PROP)
  val dataProducts: DataProductRegistry =
    if (renderOutputDir != null) {
      val dataRoot = File(renderOutputDir).parentFile?.resolve("data") ?: File(renderOutputDir)
      System.setProperty(RenderEngine.ATTACH_A11Y_PROP, "true")
      System.err.println(
        "compose-ai-tools daemon: AccessibilityDataProductRegistry active (dataRoot=$dataRoot)"
      )
      AccessibilityDataProductRegistry(rootDir = dataRoot)
    } else {
      DataProductRegistry.Empty
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
      dataProducts = dataProducts,
    )
  server.run()
}

/** HISTORY.md § "What this PR lands § H1" — null disables history. */
private const val HISTORY_DIR_PROP = "composeai.daemon.historyDir"

/** Optional override for git-provenance resolution; defaults to JVM CWD. */
private const val WORKSPACE_ROOT_PROP = "composeai.daemon.workspaceRoot"

/** Module project path stamped into every history entry's `module` field. */
private const val MODULE_ID_PROP = "composeai.daemon.moduleId"

/**
 * SANDBOX-POOL.md (Layer 3) — sandbox-pool size knob. Set by [DaemonSupervisor] from
 * `1 + replicasPerDaemon`. Default 1 preserves the pre-pool single-sandbox behaviour. Values < 1
 * are coerced to 1.
 */
private const val SANDBOX_COUNT_PROP = "composeai.daemon.sandboxCount"
