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
 * D-harness.v2 Android counterpart of [S6ClasspathDirtyRealModeTest] — same scenario shape, same
 * Tier-1 cascade assertion, only the launcher (and therefore the spawned daemon's classpath +
 * `--add-opens`) differs. Skipped under fake mode and `-Ptarget=desktop`.
 *
 * **Why android needs its own test class.** The Android variant takes ~3-10s extra for Robolectric
 * sandbox bootstrap on the first render; the desktop test's 60s `pollRenderFinishedFor` timeout
 * isn't generous enough for a contended Android run. We bump the first-render timeout to 180s on
 * Android (matching `S1LifecycleAndroidRealModeTest`) and keep the post-`fileChanged` exit budget
 * at 5s — Tier-1 dirty detection itself is identical between targets (the cascade lives in
 * `:daemon:core` which both targets consume).
 */
class S6ClasspathDirtyAndroidRealModeTest {

  @Test
  fun s6_classpath_dirty_real_mode_android() {
    Assume.assumeTrue(
      "Skipping S6ClasspathDirtyAndroidRealModeTest — set -Pharness.host=real to enable.",
      HarnessTestSupport.harnessHost() == "real",
    )
    Assume.assumeTrue(
      "Skipping S6ClasspathDirtyAndroidRealModeTest — android variant; set -Ptarget=android.",
      HarnessTestSupport.harnessTarget() == "android",
    )

    val previewId = "red-square"
    val paths =
      realAndroidModeScenario(
        name = "s6-android",
        previews =
          listOf(
            RealModePreview(
              id = previewId,
              className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
              functionName = "RedSquare",
            )
          ),
      )

    // See the desktop test KDoc for the authoritative-vs-cheap drift story.
    val classpathDriftDir =
      File(paths.rendersDir.parentFile, "${paths.rendersDir.name}-cp-drift").apply { mkdirs() }
    val cheapMarker =
      File(classpathDriftDir, "cheap-marker.toml").apply { writeText("cheap = 1\n") }

    val launcher =
      RealAndroidHarnessLauncher(
        rendersDir = paths.rendersDir,
        previewsManifest = paths.manifestFile,
        classpath = paths.classpath,
        extraClasspath = listOf(classpathDriftDir),
        extraJvmArgs =
          listOf(
            "-D${ee.schimke.composeai.daemon.ClasspathFingerprint.CHEAP_SIGNAL_FILES_PROP}=${cheapMarker.absolutePath}",
            "-Dcomposeai.daemon.classpathDirtyGraceMs=300",
          ),
      )

    val client = HarnessClient.start(launcher)
    val s6Start = System.currentTimeMillis()
    try {
      val init = client.initialize()
      assertEquals(1, init.protocolVersion)
      assertEquals(
        ee.schimke.composeai.daemon.ClasspathFingerprint.SHA_256_HEX_LENGTH,
        init.classpathFingerprint.length,
      )
      client.sendInitialized()

      // 1. First render — confirm sandbox came up.
      val rn = client.renderNow(previews = listOf(previewId), tier = RenderTier.FAST)
      assertEquals(listOf(previewId), rn.queued)
      val finished = client.pollRenderFinishedFor(previewId, timeout = 180.seconds)
      assertNotNull("renderFinished for first render", finished)

      // 2. Mutate cheap-signal file.
      Thread.sleep(20)
      cheapMarker.writeText("cheap = 2 (edited at ${System.currentTimeMillis()})\n")
      cheapMarker.setLastModified(System.currentTimeMillis())
      classpathDriftDir.setLastModified(System.currentTimeMillis())

      // 3. fileChanged → daemon detects drift.
      client.fileChanged(
        path = cheapMarker.absolutePath,
        kind = FileKind.CLASSPATH,
        changeType = ChangeType.MODIFIED,
      )

      // 4. classpathDirty notification.
      val classpathDirty = client.pollNotification("classpathDirty", 5.seconds)
      val cdParams = classpathDirty["params"]?.jsonObject
      assertNotNull(cdParams)
      assertEquals("fingerprintMismatch", cdParams!!["reason"]?.jsonPrimitive?.contentOrNull)
      val detail = cdParams["detail"]?.jsonPrimitive?.contentOrNull ?: ""
      assertTrue(
        "classpathDirty.detail must reference both hash kinds; got '$detail'",
        detail.contains("cheapHash") && detail.contains("classpathHash"),
      )

      // 5. Daemon exits cleanly. The Android sandbox's teardown can take a few seconds —
      //    grace=300ms plus 10s slack covers the worst-case Robolectric class-cleanup pass.
      val exitCode = client.waitForExit(timeoutMs = 15_000)
      assertNotNull("daemon must exit within grace+slack — stderr=${client.dumpStderr()}", exitCode)
      assertEquals(
        "daemon must exit cleanly (code 0). Stderr=\n${client.dumpStderr()}",
        Integer.valueOf(0),
        exitCode,
      )
      val totalMs = System.currentTimeMillis() - s6Start
      System.err.println(
        "S6ClasspathDirtyAndroidRealModeTest: render → fileChanged → exit in ${totalMs}ms"
      )
    } catch (t: Throwable) {
      System.err.println(
        "S6ClasspathDirtyAndroidRealModeTest failed; daemon stderr:\n${client.dumpStderr()}"
      )
      throw t
    } finally {
      try {
        client.close()
      } catch (_: Throwable) {}
    }
  }
}
