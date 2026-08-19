package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.InteractiveInputKind
import ee.schimke.composeai.daemon.protocol.InteractiveInputParams
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A live click has to show the component's own press feedback — wear-m3-catalog#32.
 *
 * The Android live lane used to dispatch a pixel click by invoking the smallest containing node's
 * `SemanticsActions.OnClick` lambda. That runs the handler and nothing else: no `PressInteraction`
 * ever reaches the component's interaction source, so it never rippled and never drew its pressed
 * state. Anything a live session appeared to do on click was whatever the handler happened to write
 * — which is why a catalog that answered a click by growing its label looked fine while the
 * component underneath was inert.
 *
 * [RippleOnlySquare] makes that testable without a pixel oracle. Its handler is inert and it
 * remembers no state, so **the press feedback is the only thing that can move a pixel**: identical
 * frames prove the press never landed, and a frame that differs proves it did.
 *
 * Both halves of the fix are load-bearing here and the test would fail without either. The click is
 * injected as a real down/up gesture (so there is a ripple at all), and the dispatch no longer
 * advances the held clock 100ms past the release (so the ~150ms fade-out has not already run out
 * before the first frame the client could be sent).
 */
class LivePressRippleTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun aLiveClickPaintsPressFeedback() {
    System.setProperty(
      RenderEngine.OUTPUT_DIR_PROP,
      tempFolder.newFolder("interactive-ripple").absolutePath,
    )
    System.setProperty("roborazzi.test.record", "true")
    val host = RobolectricHost(sandboxCount = 2, previewSpecResolver = previewSpecResolver())
    host.start()
    try {
      val session =
        host.acquireInteractiveSession(
          previewId = RIPPLE_PREVIEW_ID,
          classLoader = javaClass.classLoader!!,
        )
      try {
        val restingPng = renderTo(session)
        val resting = decode(restingPng)
        System.getenv(EVIDENCE_DIR_ENV)
          ?.takeIf { it.isNotBlank() }
          ?.let { dir ->
            restingPng.copyTo(File(File(dir).apply { mkdirs() }, "resting.png"), overwrite = true)
          }

        session.dispatch(
          InteractiveInputParams(
            frameStreamId = "irrelevant-on-host-side",
            kind = InteractiveInputKind.CLICK,
            pixelX = RIPPLE_WIDTH_PX / 2,
            pixelY = RIPPLE_HEIGHT_PX / 2,
          )
        )

        // The frames a burst would put on the wire. Rendering them back to back here is what the
        // daemon's post-input burst does for a real client: without it the next frame is a quarter
        // of a second out and the ripple is long gone.
        val diffs =
          (0 until BURST_FRAMES).map { frame ->
            val png = renderTo(session)
            val diff = meanChannelDiff(resting, decode(png))
            // Kept for the evidence run — `COMPOSEAI_EVIDENCE_DIR=… ./gradlew …` collects the
            // filmstrip that `docs/design/evidence/live-press/` is built from. An env var rather
            // than a sysprop because the build forwards the environment to the test JVM and does
            // not forward arbitrary `-D`s.
            System.getenv(EVIDENCE_DIR_ENV)
              ?.takeIf { it.isNotBlank() }
              ?.let { dir ->
                val out = File(dir).apply { mkdirs() }
                png.copyTo(File(out, "press-frame-$frame.png"), overwrite = true)
              }
            diff
          }

        val peak = diffs.max()
        assertTrue(
          "a live click must paint press feedback; every frame after it was identical to the " +
            "resting frame (mean per-channel diffs: ${diffs.joinToString { "%.2f".format(it) }})",
          peak >= MIN_PRESS_DIFF,
        )
      } finally {
        session.close()
      }
    } finally {
      host.shutdown()
    }
  }

  private fun renderTo(session: InteractiveSession): File {
    val result = session.render(requestId = RenderHost.nextRequestId())
    assertNotNull("render must produce a PNG path", result.pngPath)
    return File(result.pngPath!!)
  }

  private fun previewSpecResolver(): (String) -> RenderSpec? = { previewId ->
    if (previewId == RIPPLE_PREVIEW_ID) {
      RenderSpec(
        className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
        functionName = "RippleOnlySquare",
        widthPx = RIPPLE_WIDTH_PX,
        heightPx = RIPPLE_HEIGHT_PX,
        density = 1.0f,
        showBackground = true,
        outputBaseName = RIPPLE_PREVIEW_ID,
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

  /**
   * Mean absolute per-channel difference between two frames, 0..255. A ripple is a translucent wash
   * over the whole square rather than a hard-edged shape, so an "N% of pixels match" test would
   * report nothing until the wash crossed the tolerance; a mean moves with it.
   */
  private fun meanChannelDiff(
    a: java.awt.image.BufferedImage,
    b: java.awt.image.BufferedImage,
  ): Double {
    require(a.width == b.width && a.height == b.height) {
      "frames differ in size: ${a.width}×${a.height} vs ${b.width}×${b.height}"
    }
    var total = 0L
    for (y in 0 until a.height) {
      for (x in 0 until a.width) {
        val p = a.getRGB(x, y)
        val q = b.getRGB(x, y)
        total += abs(((p shr 16) and 0xFF) - ((q shr 16) and 0xFF)).toLong()
        total += abs(((p shr 8) and 0xFF) - ((q shr 8) and 0xFF)).toLong()
        total += abs((p and 0xFF) - (q and 0xFF)).toLong()
      }
    }
    return total.toDouble() / (a.width.toDouble() * a.height * 3)
  }

  private companion object {
    const val RIPPLE_PREVIEW_ID = "interactive-ripple"
    const val RIPPLE_WIDTH_PX = 96
    const val RIPPLE_HEIGHT_PX = 96

    /** How many frames after the click to sample — the burst window, in renders. */
    const val BURST_FRAMES = 6

    /**
     * Mean per-channel difference that counts as "the component drew something". Material's pressed
     * ripple is ~10% alpha, and blue over this fixture's red is ~100 per channel at full strength,
     * so a real ripple clears this by a wide margin while encoder noise does not.
     */
    const val MIN_PRESS_DIFF = 1.0

    const val EVIDENCE_DIR_ENV = "COMPOSEAI_EVIDENCE_DIR"
  }
}
