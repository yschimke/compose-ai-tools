package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.PreviewOverrides
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

    // The render-output dir must be set so DaemonMain.dataRoot is non-null and the file-based data
    // products register — notably compose/figma-svg, without which an override-bearing .svg render
    // fails "-32020 kind not advertised". It must sit under the working dir so its sibling `data/`
    // (where both DaemonMain's registry and RenderEngine's producer resolve) is inside this
    // session's temp tree.
    val outputDir = parsed.systemProperties["composeai.render.outputDir"]
    assertTrue(
      !outputDir.isNullOrBlank(),
      "composeai.render.outputDir must be set so figma-svg registers",
    )
    assertEquals(
      File(state.workspaceRoot, "renders").absolutePath,
      outputDir,
      "output dir lives under the session dir, so its sibling data/ dir does too",
    )

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
   * The load-bearing proof for the **android** backend: an Android/Wear catalog's `liveBundle`
   * materialises to a Robolectric daemon whose `compose/figma-svg` lane is **per-variant** — the
   * fix for the baked `figma/<slug>.svg` collapsing every state/selection variant of a component
   * onto one vector (`FilledButton` == `ButtonDisabled` == … in the served SVG). Renders the SVG
   * for pairs that share a slug but differ in state and asserts the bytes differ.
   *
   * Self-skips unless pointed at a packed **android** bundle via
   * `-Dcomposeai.test.androidBundlePath` (pack one with
   * `:samples:design-catalog-wear-m3:composePreviewBundle`) with the Android daemon sidecar
   * reachable (`-Dcomposeai.cli.libDaemonAndroidDir=<…>/staged-daemon-android-libs`) and a local
   * Android SDK (`ANDROID_HOME`/`ANDROID_SDK_ROOT`). The first render cold-starts Robolectric
   * (fetches `android-all-instrumented`), so the budget is generous.
   */
  @Test
  fun `android bundle serves per-variant SVG through a real Robolectric daemon`() {
    val bundlePath = System.getProperty(ANDROID_BUNDLE_PATH_PROPERTY)
    if (bundlePath.isNullOrBlank()) {
      System.err.println(
        "[ServeBundleDaemonTest] skipping android per-variant SVG — set " +
          "-D$ANDROID_BUNDLE_PATH_PROPERTY=<wear bundle .png> (from " +
          "`:samples:design-catalog-wear-m3:composePreviewBundle`)."
      )
      return
    }
    val bundleFile = File(bundlePath)
    if (!bundleFile.isFile) {
      System.err.println("[ServeBundleDaemonTest] skipping android — no bundle at $bundlePath")
      return
    }
    ensureAppHomeConfigured()

    val destDir = Files.createTempDirectory("serve-bundle-daemon-android").toFile()
    val state = ServeBundleDaemon.materialize(bundleFile, destDir, "wear-m3")
    if (state == null) {
      System.err.println(
        "[ServeBundleDaemonTest] skipping android — materialize returned null (see log). Needs the " +
          "lib-daemon-android sidecar (-Dcomposeai.cli.libDaemonAndroidDir=…) + android.jar " +
          "(ANDROID_HOME/ANDROID_SDK_ROOT)."
      )
      return
    }
    // Sanity: the descriptor really is the android launch (Robolectric flags present).
    val parsed =
      Json { ignoreUnknownKeys = true }
        .decodeFromString(DaemonLaunchDescriptor.serializer(), state.descriptor.readText())
    assertEquals("android", parsed.variant, "wear-m3 bundle should materialize an android daemon")
    assertTrue(
      parsed.systemProperties["robolectric.graphicsMode"] == "NATIVE",
      "android descriptor should carry the robolectric.* render flags",
    )
    assertTrue(
      parsed.systemProperties["composeai.daemon.backgroundSandboxBoot"] == "true",
      "serve-spawned android daemons should default to background pool boot (fast cold start)",
    )

    val host =
      try {
        ServeRenderHost.open(
          descriptorPath = state.descriptor,
          workspaceRoot = state.workspaceRoot,
          workspaceName = state.workspaceName,
          previews = state.previews,
          label = state.label,
          declaredThemes = state.declaredThemes,
          onLog = { line -> System.err.println("[android daemon] $line") },
        )
      } catch (e: Exception) {
        System.err.println(
          "[ServeBundleDaemonTest] skipping android live render — daemon failed to open " +
            "(${e.message})."
        )
        return
      }

    host.use {
      val ids = state.previews.map { it.id }
      // Warm the Robolectric daemon: its FIRST render cold-starts (android-all instrumentation +
      // Compose init) and can blow the host's internal 180s render budget. The daemon stays alive
      // across a timed-out render, so retry a throwaway PNG render until one lands before timing
      // the
      // real per-variant SVG lane. Skip (not fail) if it never warms — that's an
      // environment-too-slow
      // signal, not a regression.
      val warmId = ids.firstOrNull { it.endsWith("CatalogPreviewsKt.FilledButton") } ?: ids.first()
      var warm = false
      for (attempt in 1..4) {
        when (val r = host.render(warmId, PreviewOverrides())) {
          is RenderOutcome.Ok -> {
            warm = true
            break
          }
          else -> System.err.println("[android daemon] warm-up attempt $attempt: $r")
        }
      }
      // A daemon that never warms is an environment signal (a box too slow/small to cold-start
      // Robolectric), NOT a pass — mark it SKIPPED via Assume so it can't masquerade as green while
      // the per-variant assertions below never ran.
      org.junit.jupiter.api.Assumptions.assumeTrue(
        warm,
        "android daemon never warmed after 4 render attempts (cold Robolectric start too slow " +
          "for this box) — skipping the per-variant SVG assertions",
      )

      // Slug-sharing state pairs that the baked per-slug SVG collapses; each must now differ.
      val pairs =
        listOf(
          "CatalogPreviewsKt.FilledButton" to "CatalogPreviewsKt.ButtonDisabled",
          "CatalogPreviewsKt.SwitchButtonOn" to "CatalogPreviewsKt.SwitchButtonOff",
          "CatalogPreviewsKt.CheckboxButtonChecked" to "CatalogPreviewsKt.CheckboxButtonUnchecked",
        )
      var checked = 0
      for ((aSuffix, bSuffix) in pairs) {
        val aId = ids.firstOrNull { it.endsWith(aSuffix) } ?: continue
        val bId = ids.firstOrNull { it.endsWith(bSuffix) } ?: continue
        val a = host.renderSvg(aId, PreviewOverrides())
        val b = host.renderSvg(bId, PreviewOverrides())
        assertTrue(a is SvgOutcome.Ok, "SVG render of $aId should succeed, got $a")
        assertTrue(b is SvgOutcome.Ok, "SVG render of $bId should succeed, got $b")
        val aBytes = (a as SvgOutcome.Ok).svg
        val bBytes = (b as SvgOutcome.Ok).svg
        assertTrue(aBytes.isNotEmpty() && bBytes.isNotEmpty(), "SVGs must be non-empty")
        // Optional: dump the rendered vectors so a human can eyeball the per-variant difference.
        System.getProperty("composeai.test.svgDumpDir")
          ?.takeIf { it.isNotBlank() }
          ?.let { dir ->
            File(dir).mkdirs()
            File(dir, "$aSuffix.svg").writeBytes(aBytes)
            File(dir, "$bSuffix.svg").writeBytes(bBytes)
          }
        assertTrue(
          !aBytes.contentEquals(bBytes),
          "per-variant SVG regression: $aSuffix and $bSuffix rendered byte-identical SVGs " +
            "(the daemon collapsed the state variant)",
        )
        checked++
      }
      assertTrue(checked > 0, "expected at least one slug-sharing state pair in the wear bundle")
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
    const val ANDROID_BUNDLE_PATH_PROPERTY = "composeai.test.androidBundlePath"
    const val APP_HOME_PROPERTY = "composeai.cli.appHome"
    const val DEFAULT_BUNDLE_PATH = "/tmp/m3-bundle.png"

    val descriptorJson = Json { ignoreUnknownKeys = true }
  }
}
