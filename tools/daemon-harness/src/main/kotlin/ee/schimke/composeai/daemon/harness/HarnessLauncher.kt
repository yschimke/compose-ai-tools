package ee.schimke.composeai.daemon.harness

import java.io.File

/**
 * How the harness spawns the daemon JVM that [HarnessClient] talks to — see
 * [TEST-HARNESS § 8a + § 9](../../../docs/daemon/TEST-HARNESS.md#8a-the-fakehost-test-fixture).
 *
 * Two implementations ship with the harness: [FakeHarnessLauncher] (default; spawns
 * [FakeDaemonMain]) and [RealDesktopHarnessLauncher] (`-Pharness.host=real`; spawns the real
 * [`DaemonMain`][ee.schimke.composeai.daemon.DaemonMain] from `:renderer-desktop-daemon`).
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
 * Spawns the real desktop daemon — `ee.schimke.composeai.daemon.DaemonMain` from
 * `:renderer-desktop-daemon` — for D-harness.v1.5a's `-Pharness.host=real` mode.
 *
 * **Classpath resolution (Option A from the v1.5a task brief).** The harness module deliberately
 * does not depend on `:renderer-desktop-daemon` in production code — that would tie the
 * renderer-agnostic harness production classpath to a specific renderer. We add the dep as
 * `testImplementation` only, so the harness's *test* `java.class.path` includes the desktop
 * daemon's main classes (`DaemonMain`, `DesktopHost`, `RenderEngine`, `RenderSpec`) and Compose
 * Desktop / Skiko's `compose.desktop.currentOs` native bundle. The production classpath is
 * unaffected — the renderer-agnostic invariant from
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
    val cpString = classpath.joinToString(File.pathSeparator) { it.absolutePath }
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
