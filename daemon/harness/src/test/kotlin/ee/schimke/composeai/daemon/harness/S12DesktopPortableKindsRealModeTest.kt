package ee.schimke.composeai.daemon.harness

import ee.schimke.composeai.daemon.protocol.RenderTier
import java.io.File
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test

/**
 * #1201 acceptance criterion (third bullet): asserts that `data/subscribe` against every kind the
 * desktop daemon advertises as portable succeeds — i.e. the daemon does **not** error out with
 * `-32020 DataProductUnknown`. NotAvailable on subsequent `data/fetch` is fine; what we're pinning
 * is "`extensions/list` and `data/subscribe` agree on what's advertised", so the CMP-shared panel
 * stops surfacing the "kind not advertised" diagnostic that issue #1201 was filed against.
 *
 * **What this test pins.** Each kind in [PORTABLE_DESKTOP_KINDS] is registered on the desktop
 * daemon by [`buildDesktopExtensions`] (see `BuildDesktopExtensionsTest` for the unit-level
 * counterpart). This scenario is the wire-level proof: spawns a real `:daemon:desktop` JVM via
 * [RealDesktopHarnessLauncher], renders a fixture preview so the daemon's `dataRoot` is populated,
 * then issues `extensions/enable` for each kind's owning extension and a `data/subscribe` call per
 * kind. [HarnessClient.dataSubscribe] throws if the daemon responds with an error (including
 * `-32020`), so a clean run is the assertion.
 *
 * **Why not just trust the unit test?** The unit test verifies the list of `Extension` instances
 * `buildDesktopExtensions` emits; this test verifies that the same list survives the full JSON-RPC
 * round trip — extensions are activated, the dispatcher routes `data/subscribe` to the registered
 * `DataProductRegistry`, and the registry reports a known kind. Issue #1201's panel symptom was
 * "kind not advertised over the wire", which is exactly this round trip.
 *
 * **Skipped under fake mode** — the FakeDaemonMain doesn't go through `buildDesktopExtensions`. Run
 * with `./gradlew :daemon:harness:test -Pharness.host=real --tests "*S12*"`.
 */
class S12DesktopPortableKindsRealModeTest {

  @Test
  fun s12_data_subscribe_does_not_return_kind_unknown_for_portable_desktop_kinds() {
    Assume.assumeTrue(
      "Skipping — set -Pharness.host=real to enable.",
      HarnessTestSupport.harnessHost() == "real",
    )
    Assume.assumeTrue(
      "Skipping — desktop variant; set -Ptarget=desktop (default).",
      HarnessTestSupport.harnessTarget() == "desktop",
    )

    val moduleBuildDir = File("build")
    val rendersDir =
      File(moduleBuildDir, "daemon-harness/renders/s12-portable-kinds").apply {
        deleteRecursively()
        mkdirs()
      }
    val manifestFile =
      File(moduleBuildDir, "daemon-harness/manifests/s12-portable-kinds-previews.json").apply {
        parentFile.mkdirs()
      }
    manifestFile.writeText(
      """
      {
        "previews": [
          {
            "id": "red-square",
            "className": "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
            "functionName": "RedSquare",
            "widthPx": 64,
            "heightPx": 64,
            "density": 1.0,
            "showBackground": true,
            "outputBaseName": "red-square"
          }
        ]
      }
      """
        .trimIndent()
    )

    val classpath =
      System.getProperty("java.class.path")
        .split(File.pathSeparator)
        .filter { it.isNotBlank() }
        .map(::File)

    val launcher =
      RealDesktopHarnessLauncher(
        rendersDir = rendersDir,
        previewsManifest = manifestFile,
        classpath = classpath,
      )

    val client = HarnessClient.start(launcher)
    try {
      val initResult = client.initialize()
      assertEquals(2, initResult.protocolVersion)
      client.sendInitialized()

      // Enable every portable extension. The dispatcher rejects `data/subscribe` for inactive
      // extensions with `DataProductUnknown` — which is precisely the failure mode we're guarding
      // against. Pulled-in dependencies don't surface to the client and the unknown set is
      // empty by construction on the desktop daemon (every id below is registered in
      // buildDesktopExtensions).
      val enableResult = client.extensionsEnable(PORTABLE_DESKTOP_KINDS.map { it.extensionId })
      assertTrue(
        "extensions/enable reported unknown ids — desktop daemon out of sync with this test: " +
          "${enableResult.unknown}",
        enableResult.unknown.isEmpty(),
      )

      // Render once so the daemon's `dataRoot` exists. The registries return `NotAvailable` from
      // `data/fetch` because no producer ports on desktop yet — that's the steady state #1201
      // accepts. `data/subscribe` succeeds regardless: it just registers interest.
      val renderResult = client.renderNow(previews = listOf("red-square"), tier = RenderTier.FAST)
      assertEquals(listOf("red-square"), renderResult.queued)
      assertNotNull(
        "renderFinished must arrive before subscribe assertions",
        client.pollNotification("renderFinished", 60.seconds),
      )

      // The actual assertion: each portable kind must `data/subscribe` without erroring.
      // HarnessClient.dataSubscribe throws on any error response, so a clean call IS the proof.
      for (entry in PORTABLE_DESKTOP_KINDS) {
        try {
          client.dataSubscribe(previewId = "red-square", kind = entry.kind)
        } catch (t: Throwable) {
          throw AssertionError(
            "data/subscribe for kind='${entry.kind}' (extension='${entry.extensionId}') " +
              "must succeed on desktop — issue #1201 acceptance. Cause: ${t.message}",
            t,
          )
        }
      }

      val exitCode = client.shutdownAndExit(timeout = 30.seconds)
      assertEquals("Daemon must exit cleanly. Stderr=\n${client.dumpStderr()}", 0, exitCode)
    } catch (t: Throwable) {
      System.err.println(
        "S12DesktopPortableKindsRealModeTest failed; stderr from daemon:\n" + client.dumpStderr()
      )
      throw t
    } finally {
      try {
        client.close()
      } catch (_: Throwable) {
        // best-effort
      }
    }
  }

  private data class PortableKind(val extensionId: String, val kind: String)

  private companion object {
    /**
     * Kinds the desktop daemon advertises whenever `composeai.render.outputDir` is set — the steady
     * state for any real harness run. Mirrors the positive assertions in
     * `BuildDesktopExtensionsTest`; keep this list in sync when [`buildDesktopExtensions`] grows or
     * shrinks. `displayfilter` is omitted because its registration is gated on
     * `composeai.displayfilter.filters` being non-empty, which would require additional sysprop
     * plumbing on the spawned daemon to exercise; the gate itself is covered by the unit test.
     */
    private val PORTABLE_DESKTOP_KINDS =
      listOf(
        PortableKind(extensionId = "fonts/used", kind = "fonts/used"),
        PortableKind(extensionId = "compose/semantics", kind = "compose/semantics"),
        PortableKind(extensionId = "layout/inspector", kind = "layout/inspector"),
        PortableKind(extensionId = "text/strings", kind = "text/strings"),
        PortableKind(extensionId = "i18n/translations", kind = "i18n/translations"),
        PortableKind(extensionId = "data/navigation", kind = "data/navigation"),
      )
  }
}
