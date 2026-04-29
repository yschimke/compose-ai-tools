@file:JvmName("DaemonMain")

package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.bridge.DaemonHostBridge
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
  val userClassloaderHolder: UserClassLoaderHolder? =
    if (userClassUrls.isNotEmpty()) {
      System.err.println(
        "compose-ai-tools daemon: UserClassLoaderHolder active " +
          "(urls=${userClassUrls.size}, dirs=${userClassUrls.map { it.path }})"
      )
      // B2.0-followup — the child URLClassLoader's parent must be the **sandbox** classloader
      // (not the host thread's app loader). DaemonHostBridge.sandboxClassLoaderRef is set inside
      // SandboxHoldingRunner.holdSandboxOpen once the sandbox boots; the supplier here is
      // evaluated lazily on first allocation, by which time the bridge ref is non-null because
      // RobolectricHost.publishChildLoader awaits sandbox-ready before invoking the supplier.
      // Forensics-confirmed root cause for the previous Android save-loop failure.
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
    } else null

  val manifestPath = System.getProperty("composeai.harness.previewsManifest")
  val host: RenderHost =
    if (manifestPath != null && manifestPath.isNotBlank()) {
      val manifest = PreviewManifestRouter.loadManifest(File(manifestPath))
      System.err.println(
        "compose-ai-tools daemon: PreviewManifestRouter active " +
          "(manifest=$manifestPath, previews=${manifest.previews.map { it.id }})"
      )
      PreviewManifestRouter(manifest = manifest, userClassloaderHolder = userClassloaderHolder)
    } else {
      RobolectricHost(userClassloaderHolder = userClassloaderHolder)
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

  val server =
    JsonRpcServer(
      input = System.`in`,
      output = realOut,
      host = host,
      classpathFingerprint = classpathFingerprint,
      previewIndex = previewIndex,
      incrementalDiscovery = incrementalDiscovery,
    )
  server.run()
}
