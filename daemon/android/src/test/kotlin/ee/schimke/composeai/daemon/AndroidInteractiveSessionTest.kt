package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.InteractiveInputKind
import ee.schimke.composeai.daemon.protocol.InteractiveInputParams
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Integration coverage for the v3 Android-interactive held-rule loop (INTERACTIVE-ANDROID.md § 9).
 * This is the substantive PR B test — it boots two Robolectric sandboxes (`sandboxCount = 2`),
 * pins slot 1 to a held interactive session against the [ClickToggleSquare] testFixture, drives
 * the click into the held composition via [AndroidInteractiveSession.dispatch], and verifies the
 * second [AndroidInteractiveSession.render] reflects the flipped state.
 *
 * The empirical probe at `RobolectricInteractiveProbeTest` already confirmed the underlying
 * recipe (held rule + paused mainClock + dispatchTouchEvent + recapture) works under Robolectric.
 * This test adds the host plumbing on top: cross-classloader command marshalling through the
 * bridge, host-side `acquireInteractiveSession` precondition checks, the slot-pinning policy,
 * and the streamId / requestId correlation paths.
 *
 * **Sandbox cold-boot cost.** With `sandboxCount = 2` the test pays ~2× the cold-boot wall-clock
 * of single-sandbox tests (each sandbox downloads + instruments `android-all` independently on a
 * cold cache). On a warm cache it's ≤ 5 s extra. Acceptable for a single integration test; we
 * don't multiply this across the harness — the broader S1-S8 coverage stays on the v1 stateless
 * path.
 *
 * **No `assumeTrue` gate.** Unlike the standalone probe (`-Dcomposeai.probe.interactive=true`),
 * this test runs on every `:daemon:android:test` invocation. The probe stays gated because it's
 * an empirical experiment about Robolectric internals; this is the wired-up production path and
 * must not regress silently.
 *
 * Pixel-match helper inlined for the same reason `RenderEngineTest` and the probe inline theirs
 * — pulling `:daemon:harness`'s `PixelDiff` would invert the dependency graph.
 */
class AndroidInteractiveSessionTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun heldClickToggleSurvivesAcrossInputs() {
    val outputDir = tempFolder.newFolder("interactive-renders")
    System.setProperty(RenderEngine.OUTPUT_DIR_PROP, outputDir.absolutePath)
    System.setProperty("roborazzi.test.record", "true")

    val resolver = previewSpecResolver()
    val host = RobolectricHost(sandboxCount = 2, previewSpecResolver = resolver)
    host.start()
    try {
      assertTrue(
        "supportsInteractive must be true with sandboxCount=2 + previewSpecResolver wired",
        host.supportsInteractive,
      )

      val session =
        host.acquireInteractiveSession(
          previewId = INTERACTIVE_PREVIEW_ID,
          classLoader = AndroidInteractiveSessionTest::class.java.classLoader!!,
        )
      try {
        assertEquals(INTERACTIVE_PREVIEW_ID, session.previewId)

        // First frame: held composition starts on red (initial state of `ClickToggleSquare`).
        val firstResult = session.render(requestId = RenderHost.nextRequestId())
        assertNotNull("first render must produce a PNG path", firstResult.pngPath)
        val firstImg = decode(File(firstResult.pngPath!!))
        val redBefore = pixelMatchPct(firstImg, RED_RGB, perChannelTolerance = 8)
        assertTrue(
          "expected initial held capture to be ≥95% red (got ${"%.2f".format(redBefore * 100)}%)",
          redBefore >= 0.95,
        )

        // Dispatch a click in the centre of the box. ClickToggleSquare's pointerInput awaits the
        // first ACTION_DOWN and flips its remember'd state to green; held composition keeps the
        // mutated state across captures.
        session.dispatch(
          InteractiveInputParams(
            frameStreamId = "irrelevant-on-host-side",
            kind = InteractiveInputKind.CLICK,
            pixelX = INTERACTIVE_WIDTH_PX / 2,
            pixelY = INTERACTIVE_HEIGHT_PX / 2,
          )
        )

        // Second frame: state should now be green.
        val secondResult = session.render(requestId = RenderHost.nextRequestId())
        val secondImg = decode(File(secondResult.pngPath!!))
        val greenAfter = pixelMatchPct(secondImg, GREEN_RGB, perChannelTolerance = 8)
        val redAfter = pixelMatchPct(secondImg, RED_RGB, perChannelTolerance = 8)
        assertTrue(
          "expected post-click held capture to be ≥95% green (got " +
            "${"%.2f".format(greenAfter * 100)}%, redAfter=${"%.2f".format(redAfter * 100)}%) — " +
            "either dispatchTouchEvent didn't reach pointerInput or recomposition didn't land " +
            "before the second capture",
          greenAfter >= 0.95,
        )
      } finally {
        session.close()
      }

      // After close, normal renders must still work — slot 0 should have stayed live throughout
      // the held session, and slot 1 should be back to draining its requests queue. Submit a
      // standard render to confirm.
      val followupId = RenderHost.nextRequestId()
      val followupResult =
        host.submit(
          RenderRequest.Render(
            id = followupId,
            payload =
              "previewId=red-square;" +
                "className=ee.schimke.composeai.daemon.RedFixturePreviewsKt;" +
                "functionName=RedSquare;" +
                "widthPx=64;heightPx=64;density=1.0;" +
                "showBackground=true;" +
                "outputBaseName=post-interactive-red",
          ),
          timeoutMs = 60_000,
        )
      assertEquals(followupId, followupResult.id)
      assertNotNull(
        "post-interactive normal render must still produce a PNG (slot 0 stayed alive)",
        followupResult.pngPath,
      )
      val followupImg = decode(File(followupResult.pngPath!!))
      val followupRed = pixelMatchPct(followupImg, RED_RGB, perChannelTolerance = 8)
      assertTrue(
        "post-interactive RedSquare render should still be ≥95% red (got " +
          "${"%.2f".format(followupRed * 100)}%)",
        followupRed >= 0.95,
      )
    } finally {
      host.shutdown()
    }
  }

  @Test
  fun acquireWithoutResolverThrowsUnsupported() {
    val host = RobolectricHost(sandboxCount = 2)
    assertFalse(
      "supportsInteractive must be false without a previewSpecResolver, even with sandboxCount=2",
      host.supportsInteractive,
    )
    host.start()
    try {
      try {
        host.acquireInteractiveSession(
          previewId = INTERACTIVE_PREVIEW_ID,
          classLoader = javaClass.classLoader!!,
        )
        fail("expected UnsupportedOperationException for missing previewSpecResolver")
      } catch (expected: UnsupportedOperationException) {
        assertNotNull(expected.message)
      }
    } finally {
      host.shutdown()
    }
  }

  @Test
  fun acquireWithSandboxCountOneThrowsUnsupported() {
    // sandboxCount = 1 should refuse interactive even when a resolver is wired — the single slot
    // can't be sacrificed without taking normal renders down.
    val host = RobolectricHost(sandboxCount = 1, previewSpecResolver = previewSpecResolver())
    assertFalse(
      "supportsInteractive must be false with sandboxCount=1 even when resolver is wired",
      host.supportsInteractive,
    )
    host.start()
    try {
      try {
        host.acquireInteractiveSession(
          previewId = INTERACTIVE_PREVIEW_ID,
          classLoader = javaClass.classLoader!!,
        )
        fail("expected UnsupportedOperationException for sandboxCount=1")
      } catch (expected: UnsupportedOperationException) {
        assertNotNull(expected.message)
        assertTrue(
          "error message should mention the sandboxCount constraint",
          expected.message!!.contains("sandboxCount"),
        )
      }
    } finally {
      host.shutdown()
    }
  }

  @Test
  fun idleLeaseAutoClosesAbandonedSession() {
    // Drive the watchdog with a 500ms lease so we don't burn a minute of CI per run. The host's
    // interactiveIdleLeaseMs forwards to every session it acquires.
    System.setProperty(
      RenderEngine.OUTPUT_DIR_PROP,
      tempFolder.newFolder("zombie").absolutePath,
    )
    System.setProperty("roborazzi.test.record", "true")
    val host =
      RobolectricHost(
        sandboxCount = 2,
        previewSpecResolver = previewSpecResolver(),
        interactiveIdleLeaseMs = 500L,
      )
    host.start()
    try {
      val session =
        host.acquireInteractiveSession(
          previewId = INTERACTIVE_PREVIEW_ID,
          classLoader = javaClass.classLoader!!,
        )
      val typed = session as AndroidInteractiveSession
      assertFalse("session must be open immediately after acquire", typed.isClosed)
      assertNull("autoClosedReason should be null on a fresh session", typed.autoClosedReason())

      // Wait long enough for the watchdog to fire and complete its close round-trip. Lease is
      // 500ms; watchdog ticks at 500ms/4 = 125ms; close round-trip is < 1s. Polling once a tick
      // for up to 10s is generous against a slow CI but fast in normal cases.
      val deadline = System.currentTimeMillis() + 10_000L
      while (!typed.isClosed && System.currentTimeMillis() < deadline) {
        Thread.sleep(100L)
      }
      assertTrue(
        "watchdog should have auto-closed the idle session within 10s of acquire " +
          "(idleLeaseMs=500)",
        typed.isClosed,
      )
      val reason = typed.autoClosedReason()
      assertNotNull("autoClosedReason should be populated by the watchdog path", reason)
      assertTrue(
        "autoClosedReason should mention the idle gap and the lease for diagnostics: '$reason'",
        reason!!.contains("idle for") && reason.contains("lease"),
      )

      // After auto-close, a fresh acquire must succeed — the host's activeInteractiveStreamId
      // should have been cleared by the watchdog's close() call.
      val followup =
        host.acquireInteractiveSession(
          previewId = INTERACTIVE_PREVIEW_ID,
          classLoader = javaClass.classLoader!!,
        )
      followup.close()
    } finally {
      host.shutdown()
    }
  }

  @Test
  fun nestedAcquireRefusesUntilFirstSessionCloses() {
    System.setProperty(
      RenderEngine.OUTPUT_DIR_PROP,
      tempFolder.newFolder("nested").absolutePath,
    )
    System.setProperty("roborazzi.test.record", "true")
    val host = RobolectricHost(sandboxCount = 2, previewSpecResolver = previewSpecResolver())
    host.start()
    try {
      val first =
        host.acquireInteractiveSession(
          previewId = INTERACTIVE_PREVIEW_ID,
          classLoader = javaClass.classLoader!!,
        )
      try {
        try {
          host.acquireInteractiveSession(
            previewId = INTERACTIVE_PREVIEW_ID,
            classLoader = javaClass.classLoader!!,
          )
          fail("nested acquire should have thrown")
        } catch (expected: UnsupportedOperationException) {
          assertTrue(
            "nested-acquire error should mention 'already held'",
            expected.message!!.contains("already held"),
          )
        }
      } finally {
        first.close()
      }
      // After close, a fresh acquire must succeed — the host should have cleared its
      // activeInteractiveStreamId ref.
      val second =
        host.acquireInteractiveSession(
          previewId = INTERACTIVE_PREVIEW_ID,
          classLoader = javaClass.classLoader!!,
        )
      second.close()
    } finally {
      host.shutdown()
    }
  }

  private fun previewSpecResolver(): (String) -> RenderSpec? = { previewId ->
    if (previewId == INTERACTIVE_PREVIEW_ID) {
      RenderSpec(
        className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
        functionName = "ClickToggleSquare",
        widthPx = INTERACTIVE_WIDTH_PX,
        heightPx = INTERACTIVE_HEIGHT_PX,
        density = 1.0f,
        showBackground = true,
        outputBaseName = "interactive-clicktoggle",
      )
    } else null
  }

  private fun decode(file: File): java.awt.image.BufferedImage {
    require(file.exists()) { "expected capture at ${file.absolutePath}" }
    val bytes = file.readBytes()
    require(bytes.isNotEmpty()) { "capture is empty: ${file.absolutePath}" }
    return ByteArrayInputStream(bytes).use { ImageIO.read(it) }
      ?: error("ImageIO refused to decode capture: ${file.absolutePath}")
  }

  private fun pixelMatchPct(
    img: java.awt.image.BufferedImage,
    expectedRgb: Int,
    perChannelTolerance: Int,
  ): Double {
    val expR = (expectedRgb shr 16) and 0xFF
    val expG = (expectedRgb shr 8) and 0xFF
    val expB = expectedRgb and 0xFF
    var matches = 0L
    for (y in 0 until img.height) {
      for (x in 0 until img.width) {
        val rgb = img.getRGB(x, y)
        val r = (rgb shr 16) and 0xFF
        val g = (rgb shr 8) and 0xFF
        val b = rgb and 0xFF
        if (
          abs(r - expR) <= perChannelTolerance &&
            abs(g - expG) <= perChannelTolerance &&
            abs(b - expB) <= perChannelTolerance
        ) {
          matches++
        }
      }
    }
    return matches.toDouble() / (img.width.toLong() * img.height.toLong()).toDouble()
  }

  companion object {
    private const val INTERACTIVE_PREVIEW_ID = "interactive-clicktoggle"
    private const val INTERACTIVE_WIDTH_PX = 96
    private const val INTERACTIVE_HEIGHT_PX = 96
    private const val RED_RGB = 0xEF5350
    private const val GREEN_RGB = 0x66BB6A
  }
}
