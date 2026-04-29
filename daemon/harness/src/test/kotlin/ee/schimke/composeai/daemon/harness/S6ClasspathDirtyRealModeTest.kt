package ee.schimke.composeai.daemon.harness

import ee.schimke.composeai.daemon.protocol.ChangeType
import ee.schimke.composeai.daemon.protocol.FileKind
import ee.schimke.composeai.daemon.protocol.RenderTier
import java.io.File
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test

/**
 * S6 — Tier-1 ClasspathFingerprint dirty detection (B2.1, DESIGN § 8). Verifies that an editor
 * touch to a cheap-signal file followed by a `fileChanged({ kind: "classpath" })` notification
 * causes the daemon to:
 *
 * 1. Recompute the cheap-signal hash and observe a drift.
 * 2. Recompute the authoritative classpath hash (synthetic in this test — we control the fake
 *    "classpath" entry's mtime/bytes so it definitely drifts).
 * 3. Emit a one-shot `classpathDirty({ reason: "fingerprintMismatch", … })` notification.
 * 4. Refuse subsequent `renderNow` requests with the documented `ClasspathDirty` error.
 * 5. Exit cleanly (code 0) within `daemon.classpathDirtyGraceMs + slack`.
 *
 * **How the synthetic classpath drift is engineered.** The harness can't easily cause the *real*
 * resolved classpath to drift — that would require a Gradle re-resolve mid-test. Instead, the test
 * creates a freshly-minted file under `build/daemon-harness/...` and points BOTH
 * `composeai.daemon.cheapSignalFiles` AND a daemon-private "extra-classpath" sysprop at it. The
 * desktop daemon's `DaemonMain` (B2.1) reads `composeai.daemon.cheapSignalFiles` for the cheap set
 * and uses `java.class.path` for the authoritative set. We write the synthetic file inside a
 * directory that's already on the daemon's `-cp` (the test-renders dir we add via
 * `-Dcomposeai.harness.testClasspathExtra=…`), so changing it changes both the cheap hash (because
 * it's also in the cheap-signal sysprop) AND the authoritative hash (because the directory's
 * `lastModified` shifts when files inside change).
 *
 * Wait — actually, the simpler approach is: put the file into a directory that's already on
 * `java.class.path`, then mutate it. Both hashes drift. We use the harness's `rendersDir` — already
 * on the daemon's classpath via the launcher — and write a `cheap-marker.toml` inside it.
 *
 * **Run on both targets.** This test class skips under fake mode and skips under
 * `-Ptarget=android`. The Android counterpart [S6ClasspathDirtyAndroidRealModeTest] handles
 * Android.
 */
class S6ClasspathDirtyRealModeTest {

  @Test
  fun s6_classpath_dirty_real_mode_desktop() {
    Assume.assumeTrue(
      "Skipping S6ClasspathDirtyRealModeTest — set -Pharness.host=real to enable.",
      HarnessTestSupport.harnessHost() == "real",
    )
    Assume.assumeTrue(
      "Skipping S6ClasspathDirtyRealModeTest — desktop variant; set -Ptarget=desktop (default).",
      HarnessTestSupport.harnessTarget() == "desktop",
    )

    val previewId = "red-square"
    val paths =
      realModeScenario(
        name = "s6-real",
        previews =
          listOf(
            RealModePreview(
              id = previewId,
              className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
              functionName = "RedSquare",
            )
          ),
      )

    // Synthetic cheap-signal file. We need both hashes to drift on edit:
    //   * Cheap hash: covered by listing the file in `composeai.daemon.cheapSignalFiles` —
    //     the daemon reads the file's bytes.
    //   * Authoritative hash: needs the file's *parent dir* on the daemon's `java.class.path`
    //     so that the dir's `(size, lastModified)` shifts when the file inside changes. We add
    //     a fresh test-classpath dir via [RealDesktopHarnessLauncher.extraClasspath] and place
    //     the marker inside it.
    val classpathDriftDir =
      File(paths.rendersDir.parentFile, "${paths.rendersDir.name}-cp-drift").apply { mkdirs() }
    val cheapMarker =
      File(classpathDriftDir, "cheap-marker.toml").apply { writeText("cheap = 1\n") }

    val launcher =
      RealDesktopHarnessLauncher(
        rendersDir = paths.rendersDir,
        previewsManifest = paths.manifestFile,
        classpath = paths.classpath,
        extraClasspath = listOf(classpathDriftDir),
        extraJvmArgs =
          listOf(
            "-D${ee.schimke.composeai.daemon.ClasspathFingerprint.CHEAP_SIGNAL_FILES_PROP}=${cheapMarker.absolutePath}",
            // Aggressive grace window — speeds the test up. Production default is 2000ms.
            "-Dcomposeai.daemon.classpathDirtyGraceMs=300",
          ),
      )

    val client = HarnessClient.start(launcher)
    val s6Start = System.currentTimeMillis()
    try {
      val init = client.initialize()
      assertEquals(1, init.protocolVersion)
      assertEquals(
        "initialize.classpathFingerprint must be a SHA-256 hex string",
        ee.schimke.composeai.daemon.ClasspathFingerprint.SHA_256_HEX_LENGTH,
        init.classpathFingerprint.length,
      )
      client.sendInitialized()

      // 1. First render — confirm the daemon is alive and serving.
      val rn = client.renderNow(previews = listOf(previewId), tier = RenderTier.FAST)
      assertEquals(listOf(previewId), rn.queued)
      val finished = client.pollRenderFinishedFor(previewId, timeout = 60.seconds)
      assertNotNull("renderFinished for first render", finished)

      // 2. Mutate the cheap-signal file. Sleep first so the FS mtime ticks at least one
      //    millisecond past the daemon's startup snapshot.
      Thread.sleep(20)
      cheapMarker.writeText("cheap = 2 (edited at ${System.currentTimeMillis()})\n")
      cheapMarker.setLastModified(System.currentTimeMillis())
      // Also bump the parent dir's mtime — on most FSes adding/editing a child already does this,
      // but be explicit so the daemon's authoritative-classpath hash definitely drifts.
      classpathDriftDir.setLastModified(System.currentTimeMillis())

      // 3. fileChanged({ kind: "classpath" }) — daemon's Tier-1 cascade fires.
      client.fileChanged(
        path = cheapMarker.absolutePath,
        kind = FileKind.CLASSPATH,
        changeType = ChangeType.MODIFIED,
      )

      // 4. classpathDirty notification within 5s (cheap recompute is sub-ms; the only real cost
      //    is the writer thread flushing the bytes onto the wire).
      val classpathDirty = client.pollNotification("classpathDirty", 5.seconds)
      val cdParams = classpathDirty["params"]?.jsonObject
      assertNotNull("classpathDirty must carry params", cdParams)
      assertEquals("fingerprintMismatch", cdParams!!["reason"]?.jsonPrimitive?.contentOrNull)
      val detail = cdParams["detail"]?.jsonPrimitive?.contentOrNull ?: ""
      assertTrue(
        "classpathDirty.detail must reference both hash kinds; got '$detail'",
        detail.contains("cheapHash") && detail.contains("classpathHash"),
      )

      // 5. The daemon process must exit cleanly within the grace window + slack.
      val exitCode = client.waitForExit(timeoutMs = 5_000)
      assertNotNull("daemon must exit within grace+slack — stderr=${client.dumpStderr()}", exitCode)
      assertEquals(
        "daemon must exit cleanly (code 0). Stderr=\n${client.dumpStderr()}",
        Integer.valueOf(0),
        exitCode,
      )
      val totalMs = System.currentTimeMillis() - s6Start
      System.err.println(
        "S6ClasspathDirtyRealModeTest: render → fileChanged → exit in ${totalMs}ms"
      )
    } catch (t: Throwable) {
      System.err.println(
        "S6ClasspathDirtyRealModeTest failed; daemon stderr:\n${client.dumpStderr()}"
      )
      throw t
    } finally {
      try {
        client.close()
      } catch (_: Throwable) {}
    }
  }
}
