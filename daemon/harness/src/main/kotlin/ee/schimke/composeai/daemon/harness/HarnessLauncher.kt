package ee.schimke.composeai.daemon.harness

import java.io.File

/**
 * How the harness spawns the daemon JVM that [HarnessClient] talks to — see
 * [TEST-HARNESS § 8a + § 9](../../../docs/daemon/TEST-HARNESS.md#8a-the-fakehost-test-fixture).
 *
 * Two implementations ship with the harness: [FakeHarnessLauncher] (default; spawns
 * [FakeDaemonMain]) and [RealDesktopHarnessLauncher] (`-Pharness.host=real`; spawns the real
 * [`DaemonMain`][ee.schimke.composeai.daemon.DaemonMain] from `:daemon:desktop`).
 *
 * The [name] surfaces in diagnostic logs (e.g. `S1LifecycleRealModeTest` skip messages and stderr
 * dumps) so a failing run makes obvious *which* host configuration produced the failure — the
 * v1.5a-era "did this run against fake or real?" question is far the most common debugging
 * question.
 */
interface HarnessLauncher {
  /** Short tag — `"fake"` or `"real"`. Surfaces in diagnostic logs. */
  val name: String

  /** Spawns the daemon subprocess. The returned [Process] is owned by [HarnessClient]. */
  fun spawn(): Process
}

/**
 * Spawns [FakeDaemonMain] against [fixtureDir]. Pre-D-harness.v1.5 behaviour — what
 * `HarnessClient.start(fixtureDir, classpath)` did before the launcher abstraction landed.
 *
 * `composeai.harness.fixtureDir` is set on the spawned JVM so [FakeDaemonMain] can locate the
 * `previews.json` + per-preview PNG fixtures.
 */
class FakeHarnessLauncher(
  private val fixtureDir: File,
  private val classpath: List<File>,
  private val mainClass: String = "ee.schimke.composeai.daemon.harness.FakeDaemonMain",
  private val extraJvmArgs: List<String> = emptyList(),
  /**
   * H1+H2 — when non-null, sets `-Dcomposeai.daemon.historyDir=<path>` on the spawned JVM so
   * [FakeDaemonMain] wires a [HistoryManager]. Default null = no history, pre-H1 behaviour.
   */
  private val historyDir: File? = null,
  /**
   * H1+H2 — workspace root for git-provenance resolution. Optional; defaults to the spawned JVM's
   * cwd (the harness module's project dir under Gradle test execution).
   */
  private val workspaceRoot: File? = null,
  /**
   * H10-read — comma-separated list of full git ref names (e.g. `refs/heads/preview/main`) for
   * [GitRefHistorySource] wiring. When non-empty, sets `-Dcomposeai.daemon.gitRefHistory=…` on the
   * spawned JVM.
   */
  private val gitRefHistory: List<String> = emptyList(),
  /** H4 — when non-null, sets `-Dcomposeai.daemon.history.maxEntriesPerPreview=…` on the JVM. */
  private val pruneMaxEntriesPerPreview: Int? = null,
  /** H4 — when non-null, sets `-Dcomposeai.daemon.history.maxAgeDays=…` on the JVM. */
  private val pruneMaxAgeDays: Int? = null,
  /** H4 — when non-null, sets `-Dcomposeai.daemon.history.maxTotalSizeBytes=…` on the JVM. */
  private val pruneMaxTotalSizeBytes: Long? = null,
  /** H4 — when non-null, sets `-Dcomposeai.daemon.history.autoPruneIntervalMs=…` on the JVM. */
  private val pruneAutoIntervalMs: Long? = null,
) : HarnessLauncher {

  override val name: String = "fake"

  override fun spawn(): Process {
    require(fixtureDir.isDirectory) {
      "FakeHarnessLauncher.spawn: fixtureDir '${fixtureDir.absolutePath}' is not a directory"
    }
    val javaBin = File(System.getProperty("java.home"), "bin/java")
    val cpString = classpath.joinToString(File.pathSeparator) { it.absolutePath }
    val command =
      buildList<String> {
        add(javaBin.absolutePath)
        add("-Dcomposeai.harness.fixtureDir=${fixtureDir.absolutePath}")
        // Match the in-process integration test's idle timeout — keeps harness scenarios snappy
        // when a misbehaving test forgets to send `exit`.
        add("-Dcomposeai.daemon.idleTimeoutMs=2000")
        if (historyDir != null) add("-Dcomposeai.daemon.historyDir=${historyDir.absolutePath}")
        if (workspaceRoot != null)
          add("-Dcomposeai.daemon.workspaceRoot=${workspaceRoot.absolutePath}")
        if (gitRefHistory.isNotEmpty())
          add("-Dcomposeai.daemon.gitRefHistory=${gitRefHistory.joinToString(",")}")
        if (pruneMaxEntriesPerPreview != null)
          add("-Dcomposeai.daemon.history.maxEntriesPerPreview=$pruneMaxEntriesPerPreview")
        if (pruneMaxAgeDays != null)
          add("-Dcomposeai.daemon.history.maxAgeDays=$pruneMaxAgeDays")
        if (pruneMaxTotalSizeBytes != null)
          add("-Dcomposeai.daemon.history.maxTotalSizeBytes=$pruneMaxTotalSizeBytes")
        if (pruneAutoIntervalMs != null)
          add("-Dcomposeai.daemon.history.autoPruneIntervalMs=$pruneAutoIntervalMs")
        addAll(extraJvmArgs)
        add("-cp")
        add(cpString)
        add(mainClass)
      }
    return ProcessBuilder(command)
      .redirectErrorStream(false)
      .redirectInput(ProcessBuilder.Redirect.PIPE)
      .redirectOutput(ProcessBuilder.Redirect.PIPE)
      .redirectError(ProcessBuilder.Redirect.PIPE)
      .start()
  }
}

/**
 * Spawns the real desktop daemon — `ee.schimke.composeai.daemon.DaemonMain` from `:daemon:desktop`
 * — for D-harness.v1.5a's `-Pharness.host=real` mode.
 *
 * **Classpath resolution (Option A from the v1.5a task brief).** The harness module deliberately
 * does not depend on `:daemon:desktop` in production code — that would tie the renderer-agnostic
 * harness production classpath to a specific renderer. We add the dep as `testImplementation` only,
 * so the harness's *test* `java.class.path` includes the desktop daemon's main classes
 * (`DaemonMain`, `DesktopHost`, `RenderEngine`, `RenderSpec`) and Compose Desktop / Skiko's
 * `compose.desktop.currentOs` native bundle. The production classpath is unaffected — the
 * renderer-agnostic invariant from
 * [DESIGN § 4](../../../../docs/daemon/DESIGN.md#renderer-agnostic-surface) holds where it matters.
 *
 * **System properties on the spawned JVM:**
 * - `composeai.render.outputDir = rendersDir` — where [RenderEngine] writes PNGs.
 * - `composeai.harness.previewsManifest = previewsManifest` — JSON file mapping previewId →
 *   `RenderSpec` shape. Read by `DaemonMain` (when set) to wrap [DesktopHost] with a routing shim,
 *   since `JsonRpcServer.handleRenderNow` only forwards `previewId=<id>` in the payload — not the
 *   className/functionName the engine needs. Without this manifest the real daemon would fall
 *   through to `renderStubFallback` and produce no PNG.
 * - `composeai.daemon.idleTimeoutMs = 2000` — same scenario-friendly idle timeout the fake launcher
 *   uses.
 *
 * No `composeai.harness.fixtureDir` — the real daemon doesn't read FakeHost fixtures.
 */
class RealDesktopHarnessLauncher(
  private val rendersDir: File,
  private val previewsManifest: File,
  private val classpath: List<File>,
  private val extraJvmArgs: List<String> = emptyList(),
  /**
   * Additional `-cp` entries appended after [classpath]. Used by S6 (B2.1) so the daemon's
   * `java.class.path` includes the cheap-signal marker file's parent directory — which lets editing
   * the marker drift both the cheap-signal hash AND the authoritative classpath hash (the dir's
   * `lastModified` shifts when contents change). Empty by default; existing scenarios are
   * unaffected.
   */
  private val extraClasspath: List<File> = emptyList(),
) : HarnessLauncher {

  override val name: String = "real"

  override fun spawn(): Process {
    require(rendersDir.isDirectory) {
      "RealDesktopHarnessLauncher.spawn: rendersDir '${rendersDir.absolutePath}' is not a directory"
    }
    require(previewsManifest.isFile) {
      "RealDesktopHarnessLauncher.spawn: previewsManifest '${previewsManifest.absolutePath}' " +
        "must exist before spawning (write the JSON before calling HarnessClient.start)"
    }
    val javaBin = File(System.getProperty("java.home"), "bin/java")
    val fullClasspath = classpath + extraClasspath
    val cpString = fullClasspath.joinToString(File.pathSeparator) { it.absolutePath }
    val command =
      buildList<String> {
        add(javaBin.absolutePath)
        add("-Dcomposeai.render.outputDir=${rendersDir.absolutePath}")
        add("-Dcomposeai.harness.previewsManifest=${previewsManifest.absolutePath}")
        add("-Dcomposeai.daemon.idleTimeoutMs=2000")
        addAll(extraJvmArgs)
        add("-cp")
        add(cpString)
        add("ee.schimke.composeai.daemon.DaemonMain")
      }
    return ProcessBuilder(command)
      .redirectErrorStream(false)
      .redirectInput(ProcessBuilder.Redirect.PIPE)
      .redirectOutput(ProcessBuilder.Redirect.PIPE)
      .redirectError(ProcessBuilder.Redirect.PIPE)
      .start()
  }
}

/**
 * Spawns the real Android daemon — `ee.schimke.composeai.daemon.DaemonMain` from `:daemon:android`
 * — for D-harness.v2's `-Ptarget=android` mode.
 *
 * **The JVM entry point is identical to the desktop launcher.** The package is
 * `ee.schimke.composeai.daemon` in both modules, just different runtime classpaths. The Android
 * module's `DaemonMain` constructs a
 * [`RobolectricHost`][ee.schimke.composeai.daemon.RobolectricHost] (mirroring the desktop module's
 * `DesktopHost`) and wraps it in `PreviewManifestRouter` when `composeai.harness.previewsManifest`
 * is set — exactly the same shape as the desktop launcher.
 *
 * **Classpath resolution.** Same Option A pattern as [RealDesktopHarnessLauncher]: the harness adds
 * `testImplementation(project(":daemon:android"))` +
 * `testImplementation(testFixtures(project(":daemon:android")))` so the harness's *test*
 * `java.class.path` includes:
 * - The android daemon's main classes (`DaemonMain`, `RobolectricHost`, `RenderEngine`,
 *   `RenderSpec`, `PreviewManifestRouter`).
 * - Robolectric + JUnit (declared `implementation(...)` on the daemon module per B1.3 — the
 *   sandbox-holder pattern requires them on the *main* classpath, not just test).
 * - The android testFixtures composables (`RedSquare`, `BlueSquare`, `GreenSquare`, `SlowSquare`,
 *   `BoomComposable`) at FQN `ee.schimke.composeai.daemon.RedFixturePreviewsKt`.
 * - androidx.compose runtime/foundation/ui (transitive via the daemon module's runtime classpath).
 *
 * Production classpath is unaffected — the renderer-agnostic invariant from
 * [DESIGN § 4](../../../../docs/daemon/DESIGN.md#renderer-agnostic-surface) holds where it matters.
 *
 * **System properties on the spawned JVM.** Mirror [RealDesktopHarnessLauncher]'s plus the
 * Robolectric-specific ones from `AndroidPreviewClasspath.RobolectricSystemProps`
 * (gradle-plugin/.../AndroidPreviewClasspath.kt). The harness duplicates the *subset* that the
 * daemon's render path actually relies on — graphics mode, looper mode, conscrypt, pixel-copy,
 * roborazzi record. The `composeai.fonts.cacheDir` / `composeai.fonts.offline` props are not passed
 * because the fixture composables don't use Google Fonts; if a future fixture does, surface those
 * here too.
 *
 * **JVM args.** Robolectric on JDK 17+ requires `--add-opens` for `java.lang`, `java.lang.reflect`,
 * and `java.nio` — same set [`AndroidPreviewClasspath.buildJvmArgs`][
 * ee.schimke.composeai.plugin.AndroidPreviewClasspath] returns for the standalone Robolectric test
 * path. Without these the daemon JVM aborts in `ShadowVMRuntime` static init before reaching the
 * JSON-RPC read loop.
 *
 * **Heavy spawn cost.** Robolectric sandbox bootstrap (the dummy-`@Test` runner trick from DESIGN §
 * 9 + B1.3) costs roughly 3-10s on a typical dev machine — at least an order of magnitude higher
 * than the desktop launcher's ~600ms cold. Use 60s `renderStarted` and 120s `renderFinished`
 * timeouts in tests; the previous `:samples:android` `renderPreviews` task already proves the
 * JVM-args / classpath shape works at this scale, so the cost is well-understood, just slow.
 *
 * No `composeai.harness.fixtureDir` — the real daemon doesn't read FakeHost fixtures.
 */
class RealAndroidHarnessLauncher(
  private val rendersDir: File,
  private val previewsManifest: File,
  private val classpath: List<File>,
  private val extraJvmArgs: List<String> = emptyList(),
  /** See [RealDesktopHarnessLauncher.extraClasspath]. */
  private val extraClasspath: List<File> = emptyList(),
) : HarnessLauncher {

  companion object {
    /**
     * Returns the spawned-daemon classpath the harness build wired up via
     * `composeai.harness.androidDaemonClasspath` (a file listing one absolute JAR path per line).
     * D-harness.v2's harness build resolves `:daemon:android`'s runtime classpath + testFixtures
     * into that file at task execution time so the harness's plain JVM test runtime (which can't
     * natively consume Android library variants) doesn't have to handle AGP variant resolution.
     * Returns null if the property is unset — tests that need the Android daemon gate themselves
     * with `Assume.assumeTrue(target == "android")` and the harness build only sets the property
     * when `-Ptarget=android`-compatible test runs are configured.
     */
    fun classpathFromProperty(): List<File>? {
      val path = System.getProperty("composeai.harness.androidDaemonClasspath") ?: return null
      val file = File(path)
      if (!file.isFile) return null
      return file.readLines().filter { it.isNotBlank() }.map { File(it.trim()) }
    }
  }

  override val name: String = "real-android"

  override fun spawn(): Process {
    require(rendersDir.isDirectory) {
      "RealAndroidHarnessLauncher.spawn: rendersDir '${rendersDir.absolutePath}' is not a directory"
    }
    require(previewsManifest.isFile) {
      "RealAndroidHarnessLauncher.spawn: previewsManifest '${previewsManifest.absolutePath}' " +
        "must exist before spawning (write the JSON before calling HarnessClient.start)"
    }
    val javaBin = File(System.getProperty("java.home"), "bin/java")
    val fullClasspath = classpath + extraClasspath
    val cpString = fullClasspath.joinToString(File.pathSeparator) { it.absolutePath }
    val command =
      buildList<String> {
        add(javaBin.absolutePath)
        // --add-opens for Robolectric on JDK 17+ — see KDoc for the rationale. Mirrors
        // gradle-plugin's `AndroidPreviewClasspath.buildJvmArgs()`.
        add("--add-opens=java.base/java.lang=ALL-UNNAMED")
        add("--add-opens=java.base/java.lang.reflect=ALL-UNNAMED")
        add("--add-opens=java.base/java.nio=ALL-UNNAMED")
        // Render output directory.
        add("-Dcomposeai.render.outputDir=${rendersDir.absolutePath}")
        // Wire the manifest router (same as desktop).
        add("-Dcomposeai.harness.previewsManifest=${previewsManifest.absolutePath}")
        // Robolectric system properties — the daemon's @Config / @GraphicsMode annotations on
        // `SandboxHoldingRunner` already pin most of these, but belt-and-braces keeps a future
        // annotation-stripping refactor from breaking renders. Order matches
        // `AndroidPreviewClasspath.buildSystemProperties` for diff-friendly comparison.
        add("-Drobolectric.graphicsMode=NATIVE")
        add("-Drobolectric.looperMode=PAUSED")
        add("-Drobolectric.conscryptMode=OFF")
        add("-Drobolectric.pixelCopyRenderMode=hardware")
        add("-Droborazzi.test.record=true")
        // Pin Robolectric to SDK 35 — `SandboxHoldingRunner`'s @Config(sdk=[35]) already does this,
        // but JDK 17 + SDK 36 emits the "won't be run" warning early in sandbox bootstrap and
        // some Robolectric paths fall back to defaults if the annotation is misread. Belt-and-
        // braces; doesn't hurt when the annotation already pins.
        add("-Drobolectric.config.sdk=35")
        // Idle timeout — keep parity with the desktop launcher. Note: Robolectric sandbox bootstrap
        // can dominate the first render's wall-clock; tests should use a large *poll* timeout
        // (60-120s) rather than relying on this idle timeout to back-stop a hung daemon.
        add("-Dcomposeai.daemon.idleTimeoutMs=2000")
        addAll(extraJvmArgs)
        add("-cp")
        add(cpString)
        add("ee.schimke.composeai.daemon.DaemonMain")
      }
    return ProcessBuilder(command)
      .redirectErrorStream(false)
      .redirectInput(ProcessBuilder.Redirect.PIPE)
      .redirectOutput(ProcessBuilder.Redirect.PIPE)
      .redirectError(ProcessBuilder.Redirect.PIPE)
      .start()
  }
}
