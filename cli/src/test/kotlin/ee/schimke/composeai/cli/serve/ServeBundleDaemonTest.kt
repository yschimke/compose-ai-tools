package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.RenderTier
import ee.schimke.composeai.mcp.DaemonLaunchDescriptor
import ee.schimke.composeai.render.session.RenderSessionConfig
import ee.schimke.composeai.render.session.subprocess.SubprocessRenderSessions
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Exercises [ServeBundleDaemon.materialize] against a real **packed desktop bundle** — the same
 * shape `serve --catalogs --allow-render-trusted` fetches for a catalog's `liveBundle`. Needs a
 * bundle on disk (produced by `compose-preview bundle pack --module :samples:design-catalog-m3 -o
 * <path>`, e.g. via `NonGradleContractTest`'s pattern) plus the CLI's own `:cli:installDist`
 * sidecars (`lib-daemon-desktop` / `lib-renderer`) to resolve a real daemon classpath — neither is
 * produced by a normal `:cli:test` run, so this self-skips (same convention as
 * `NonGradleContractTest` in `:render-session-subprocess`) rather than failing when they're
 * missing.
 *
 * Point `-Dcomposeai.test.bundlePath=<file>` at a pre-packed bundle (defaults to
 * `/tmp/m3-bundle.png`, the path this feature's own verification pass packs to). The
 * `lib-daemon-desktop`/`lib-renderer` sidecars are auto-discovered from this checkout's
 * `cli/build/install/compose-preview/` when present (i.e. after `./gradlew :cli:installDist`);
 * override via `-Dcomposeai.cli.appHome=<install-root>` to point elsewhere.
 */
class ServeBundleDaemonTest {

  @Test
  fun `materialize produces a valid descriptor plus previews from a packed desktop bundle`() {
    val state = materializeOrSkip("descriptor-shape") ?: return

    val descriptorFile = state.descriptor
    assertTrue(descriptorFile.isFile, "daemon-launch.json should exist at ${descriptorFile.path}")
    val parsed =
      descriptorJson.decodeFromString(
        DaemonLaunchDescriptor.serializer(),
        descriptorFile.readText(),
      )

    assertEquals("ee.schimke.composeai.daemon.DaemonMain", parsed.mainClass)
    assertEquals("desktop", parsed.variant)
    assertEquals(":catalog", parsed.modulePath)
    assertTrue(parsed.enabled)
    assertTrue(parsed.classpath.isNotEmpty(), "daemon classpath should not be empty")
    assertTrue(
      parsed.classpath.all { File(it).isFile },
      "every daemon classpath entry should exist on disk: ${parsed.classpath}",
    )
    assertEquals(listOf("--enable-native-access=ALL-UNNAMED"), parsed.jvmArgs)

    val userClassDirs = parsed.systemProperties["composeai.daemon.userClassDirs"]
    assertTrue(!userClassDirs.isNullOrBlank(), "userClassDirs sysprop should be set")
    assertTrue(
      userClassDirs.split(File.pathSeparator).all { File(it).exists() },
      "every userClassDirs entry should exist on disk: $userClassDirs",
    )
    val previewsJsonPath = parsed.systemProperties["composeai.daemon.previewsJsonPath"]
    assertTrue(!previewsJsonPath.isNullOrBlank())
    assertTrue(File(previewsJsonPath).isFile)
    assertEquals(previewsJsonPath, parsed.manifestPath)
    assertEquals(state.workspaceRoot.absolutePath, parsed.workingDirectory)

    assertTrue(state.previews.isNotEmpty(), "materialize should discover at least one preview")
    assertEquals("compose-m3", state.label)

    // The author-declared knob sidecars (`previews/<id>.overrides.json`) must be folded into the
    // ServePreview set so the daemon-backed session (and, via ServeCatalogLiveHost, the baked
    // browse
    // surface) can advertise the editable knobs. The M3 catalog's FilledButton declares a `label`
    // string knob; assert it round-trips from the packed bundle.
    val filled = state.previews.firstOrNull { it.id.endsWith("FilledButton_Light") }
    if (filled != null) {
      assertTrue(
        filled.overrides.any { it.key == "label" },
        "FilledButton should carry its declared `label` knob, got ${filled.overrides}",
      )
    }
  }

  @Test
  fun `materialized bundle renders one preview through a real daemon`() {
    val state = materializeOrSkip("live-render") ?: return
    val targetId = state.previews.first().id

    val session =
      try {
        SubprocessRenderSessions.open(
          RenderSessionConfig(
            descriptorPath = state.descriptor,
            workspaceRoot = state.workspaceRoot,
            workspaceName = state.workspaceName,
            logSink = { line -> System.err.println("[daemon] $line") },
          )
        )
      } catch (e: Exception) {
        System.err.println(
          "[ServeBundleDaemonTest] skipping live render — daemon failed to open (${e.message}). " +
            "Needs a display (run under xvfb-run + LIBGL_ALWAYS_SOFTWARE=1) and the CLI's " +
            "installDist sidecars."
        )
        return
      }

    session.use {
      val finished = AtomicReference<String?>(null)
      val latch = CountDownLatch(1)
      session
        .onNotification { method, params ->
          if (method == "renderFinished" && params != null) {
            val id = params["id"]?.jsonPrimitive?.contentOrNull
            if (id == targetId) {
              finished.set(params["pngPath"]?.jsonPrimitive?.contentOrNull)
              latch.countDown()
            }
          }
        }
        .use {
          val ack = session.renderNow(previewIds = listOf(targetId), tier = RenderTier.FULL)
          assertTrue(
            ack.rejected.none { it.id == targetId },
            "renderNow should queue $targetId, got rejected=${ack.rejected}",
          )
          assertTrue(
            latch.await(90, TimeUnit.SECONDS),
            "daemon should emit renderFinished for $targetId within 90s",
          )
        }

      val pngPath = finished.get()
      assertTrue(!pngPath.isNullOrBlank(), "renderFinished should carry a pngPath")
      val png = File(pngPath)
      assertTrue(png.isFile, "rendered PNG must exist on disk: $pngPath")
      assertTrue(png.length() > 0L)
    }
  }

  /**
   * Locates the bundle + sidecars and calls [ServeBundleDaemon.materialize] into a fresh temp dir
   * under [label], or logs why and returns `null` so the caller self-skips.
   */
  private fun materializeOrSkip(label: String): ServeSessionState? {
    val bundlePath = System.getProperty(BUNDLE_PATH_PROPERTY) ?: DEFAULT_BUNDLE_PATH
    val bundleFile = File(bundlePath)
    if (!bundleFile.isFile) {
      System.err.println(
        "[ServeBundleDaemonTest] skipping ($label) — no bundle at $bundlePath. Pack one via " +
          "`compose-preview bundle pack --module :samples:design-catalog-m3 -o $bundlePath`."
      )
      return null
    }

    ensureAppHomeConfigured()

    val destDir = Files.createTempDirectory("serve-bundle-daemon-test-$label").toFile()
    val state = ServeBundleDaemon.materialize(bundleFile, destDir, "compose-m3")
    if (state == null) {
      System.err.println(
        "[ServeBundleDaemonTest] skipping ($label) — materialize returned null (see log above); " +
          "likely missing lib-daemon-desktop/lib-renderer sidecars. Run `:cli:installDist` and/or " +
          "pass -Dcomposeai.cli.appHome=<install-root>."
      )
      return null
    }
    return state
  }

  /**
   * If no explicit `-Dcomposeai.cli.appHome` override is set, point it at this checkout's own
   * `cli/build/install/compose-preview/` when that `:cli:installDist` output exists — lets the test
   * run end to end in a normal dev/CI checkout without extra flags, while still respecting an
   * explicit override.
   */
  private fun ensureAppHomeConfigured() {
    if (System.getProperty(APP_HOME_PROPERTY) != null) return
    val installDir = File(locateRepoRoot(), "cli/build/install/compose-preview")
    if (installDir.isDirectory) {
      System.setProperty(APP_HOME_PROPERTY, installDir.absolutePath)
    }
  }

  /** Walk up from the test JVM's working dir to find the repo root (has `settings.gradle.kts`). */
  private fun locateRepoRoot(): File {
    var dir: File? = File(".").canonicalFile
    while (dir != null) {
      if (File(dir, "settings.gradle.kts").isFile) return dir
      dir = dir.parentFile
    }
    error("Could not locate repo root above ${File(".").canonicalFile}")
  }

  private companion object {
    const val BUNDLE_PATH_PROPERTY = "composeai.test.bundlePath"
    const val APP_HOME_PROPERTY = "composeai.cli.appHome"
    const val DEFAULT_BUNDLE_PATH = "/tmp/m3-bundle.png"

    val descriptorJson = Json { ignoreUnknownKeys = true }
  }
}
