package ee.schimke.composeai.daemon

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.RecordingFormat
import ee.schimke.composeai.daemon.protocol.RecordingScriptEvent
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageTypeSpecifier
import javax.imageio.metadata.IIOMetadata
import javax.imageio.metadata.IIOMetadataNode
import javax.imageio.stream.FileImageOutputStream
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * End-to-end integration test for the `TouchOverlayExtension` `AroundComposable` data extension
 * plus the new multi-pointer dispatch on [DesktopRecordingSession]. The test:
 *
 * 1. Allocates a recording session against [PinchableSquare] (a `Modifier.transformable`-driven
 *    centred blue square that scales with zoom gestures).
 * 2. Activates the touch overlay via `overrides.touchOverlay = true` so the captured frames carry
 *    the visualization rings + pulses on top of the preview.
 * 3. Scripts a 5-step outward pinch: two fingers start near the centre, walk symmetrically toward
 *    opposite corners over 500ms. Each frame dispatches a multi-pointer event with both
 *    `ComposeScenePointer`s present so Compose's gesture pipeline fires the `transformable` zoom
 *    callback.
 * 4. Stops + encodes APNG; also stitches the frames into a `pinch-to-zoom.gif` artifact that the PR
 *    comment links to.
 * 5. Asserts:
 *     - At least one frame has the cyan overlay ring pixels (overlay actually painted).
 *     - The post-pinch frame is dominated by the zoomed blue square (the multi-pointer dispatch
 *       reached the gesture detector — without multi-pointer this test would fail).
 *
 * The fixture lives in this file rather than the shared `RedFixturePreviews` testFixtures source
 * set so the cmp sample stays the canonical demo and the test fixture stays self-contained.
 */
class TouchOverlayPinchRecordingTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  private var savedRecordingsDir: String? = null

  @After
  fun tearDown() {
    val saved = savedRecordingsDir
    if (saved == null) System.clearProperty(DesktopHost.RECORDINGS_DIR_PROP)
    else System.setProperty(DesktopHost.RECORDINGS_DIR_PROP, saved)
  }

  @Test
  fun pinch_with_overlay_visibly_zooms_and_shows_touch_indicators() {
    val outputDir = tempFolder.newFolder("touch-overlay-renders")
    val recordingsRoot = tempFolder.newFolder("touch-overlay-recordings")
    savedRecordingsDir = System.getProperty(DesktopHost.RECORDINGS_DIR_PROP)
    System.setProperty(DesktopHost.RECORDINGS_DIR_PROP, recordingsRoot.absolutePath)

    val engine =
      RenderEngine(
        outputDir = outputDir,
        previewOverrideExtensions =
          PreviewOverrideExtensions(listOf(TouchOverlayPreviewOverrideExtension())),
      )
    val host =
      DesktopHost(
        engine = engine,
        previewSpecResolver = { previewId ->
          if (previewId == PINCH_PREVIEW_ID) {
            RenderSpec(
              className = "ee.schimke.composeai.daemon.TouchOverlayPinchRecordingTestKt",
              functionName = "PinchableSquare",
              widthPx = CANVAS_PX,
              heightPx = CANVAS_PX,
              density = 1.0f,
              outputBaseName = "pinch-to-zoom",
            )
          } else null
        },
      )
    host.start()
    try {
      val session =
        host.acquireRecordingSession(
          previewId = PINCH_PREVIEW_ID,
          recordingId = "test-rec-pinch",
          classLoader =
            TouchOverlayPinchRecordingTest::class.java.classLoader
              ?: ClassLoader.getSystemClassLoader(),
          fps = FPS,
          scale = 1.0f,
          overrides = PreviewOverrides(touchOverlay = true),
        )
      try {
        // Two-finger outward pinch script. Both fingers stay pressed across the same `tMs` so the
        // multi-pointer dispatch in DesktopRecordingSession sees both in one `sendPointerEvent`
        // call — that's the gating signal Compose's `Modifier.transformable` zoom detector needs.
        // The fingers walk from ~50% spread (start) to ~95% spread (end) over 500ms, producing a
        // ~1.9x zoom on the blue square.
        val script = buildPinchScript(durationMs = PINCH_DURATION_MS)
        session.postScript(script)

        val result = session.stop()
        assertTrue(
          "expected ≥ 8 frames over $PINCH_DURATION_MS ms at $FPS fps; got ${result.frameCount}",
          result.frameCount >= 8,
        )

        // Encode the APNG (always available) — this is the primary artifact callers consume.
        val apng = session.encode(RecordingFormat.APNG)
        val apngFile = File(apng.videoPath)
        assertTrue(
          "encoded APNG must be non-empty: ${apngFile.absolutePath}",
          apngFile.isFile && apng.sizeBytes > 0,
        )

        // Stitch the PNG frames into a GIF artifact for PR-comment embedding. APNG → many PR
        // viewers (including GitHub's web comment renderer) don't autoplay APNG inline but always
        // do GIF. Output path is `<repo>/build/touch-overlay-pinch.gif` — picked up by the
        // post-test step that uploads PR artifacts.
        val gifTarget = ARTIFACT_DIR.also { it.mkdirs() }.resolve("touch-overlay-pinch.gif")
        encodeFramesAsGif(File(result.framesDir), result.frameCount, gifTarget, fps = FPS)
        assertTrue(
          "GIF artifact must be written for the PR: ${gifTarget.absolutePath}",
          gifTarget.isFile && gifTarget.length() > 0,
        )

        // Touch overlay assertion — at least one captured frame must contain the cyan ring
        // (active-pointer overlay). We sample a frame mid-pinch where both fingers are pressed.
        val midFrame =
          readPng(File(result.framesDir, "frame-${"%05d".format(result.frameCount / 2)}.png"))
        val cyanMatch =
          pixelMatchPctApprox(midFrame, expectedRgb = 0x00BCD4, perChannelTolerance = 60)
        assertTrue(
          "mid-pinch frame must contain cyan overlay-ring pixels (touch viz painted); " +
            "got ${"%.4f".format(cyanMatch * 100)}% — if 0, the TouchOverlayExtension didn't fire",
          cyanMatch > 0.0005,
        )

        // Zoom assertion — final frame's blue-pixel coverage must be > the initial frame's,
        // proving the multi-pointer dispatch reached `Modifier.transformable`'s zoom callback.
        // Without real multi-pointer dispatch, the two `pointerDown`s would arrive as two
        // independent single-pointer gestures and `transformable` would never fire.
        val firstFrame = readPng(File(result.framesDir, "frame-00000.png"))
        val lastFrame =
          readPng(File(result.framesDir, "frame-${"%05d".format(result.frameCount - 1)}.png"))
        val initialBlue =
          pixelMatchPctApprox(firstFrame, expectedRgb = 0x1976D2, perChannelTolerance = 32)
        val finalBlue =
          pixelMatchPctApprox(lastFrame, expectedRgb = 0x1976D2, perChannelTolerance = 32)
        assertTrue(
          "final blue coverage ($finalBlue) must exceed initial ($initialBlue) — zoom didn't " +
            "register, multi-pointer dispatch likely broken",
          finalBlue > initialBlue * 1.3,
        )
      } finally {
        session.close()
      }
    } finally {
      host.shutdown()
    }
  }

  /**
   * Build a multi-pointer pinch timeline: two fingers walk symmetrically from near-centre outward
   * to opposite corners. Both fingers send `pointerDown` at `tMs = 0`, an interpolated
   * `pointerMove` every [STEP_MS] (frame-aligned at 30 fps), and `pointerUp` at the final tMs.
   */
  private fun buildPinchScript(durationMs: Long): List<RecordingScriptEvent> {
    val centre = CANVAS_PX / 2
    val startSpread = (CANVAS_PX * 0.15f).toInt() // ~15% diagonal — fingers near centre
    val endSpread = (CANVAS_PX * 0.40f).toInt() // ~40% diagonal — fingers near corners
    val steps = (durationMs / STEP_MS).toInt().coerceAtLeast(1)
    return buildList {
      // pointerDown for both fingers at tMs = 0.
      add(
        RecordingScriptEvent(
          tMs = 0L,
          kind = "input.pointerDown",
          pixelX = centre - startSpread,
          pixelY = centre - startSpread,
          pointerId = POINTER_A,
        )
      )
      add(
        RecordingScriptEvent(
          tMs = 0L,
          kind = "input.pointerDown",
          pixelX = centre + startSpread,
          pixelY = centre + startSpread,
          pointerId = POINTER_B,
        )
      )
      // Interpolated moves — fingers walk symmetrically outward along the main diagonal.
      for (i in 1..steps) {
        val tMs = i.toLong() * STEP_MS
        val fraction = i.toFloat() / steps.toFloat()
        val spread = (startSpread + (endSpread - startSpread) * fraction).toInt()
        add(
          RecordingScriptEvent(
            tMs = tMs,
            kind = "input.pointerMove",
            pixelX = centre - spread,
            pixelY = centre - spread,
            pointerId = POINTER_A,
          )
        )
        add(
          RecordingScriptEvent(
            tMs = tMs,
            kind = "input.pointerMove",
            pixelX = centre + spread,
            pixelY = centre + spread,
            pointerId = POINTER_B,
          )
        )
      }
      // Release both fingers at the final tMs.
      add(
        RecordingScriptEvent(
          tMs = durationMs,
          kind = "input.pointerUp",
          pixelX = centre - endSpread,
          pixelY = centre - endSpread,
          pointerId = POINTER_A,
        )
      )
      add(
        RecordingScriptEvent(
          tMs = durationMs,
          kind = "input.pointerUp",
          pixelX = centre + endSpread,
          pixelY = centre + endSpread,
          pointerId = POINTER_B,
        )
      )
    }
  }

  /** Read a PNG file into a [BufferedImage] for pixel assertions. */
  private fun readPng(f: File): BufferedImage =
    requireNotNull(ImageIO.read(f)) { "ImageIO.read returned null for ${f.absolutePath}" }

  /**
   * Pixel-coverage helper: fraction of pixels in [image] within [perChannelTolerance] of
   * [expectedRgb] on every channel. Same shape as the helper in `DesktopRecordingSessionTest` —
   * inlined here to avoid a circular dep with the test class.
   */
  private fun pixelMatchPctApprox(
    image: BufferedImage,
    expectedRgb: Int,
    perChannelTolerance: Int,
  ): Double {
    val expR = (expectedRgb shr 16) and 0xFF
    val expG = (expectedRgb shr 8) and 0xFF
    val expB = expectedRgb and 0xFF
    var matched = 0L
    var total = 0L
    for (y in 0 until image.height) {
      for (x in 0 until image.width) {
        val rgb = image.getRGB(x, y)
        val r = (rgb shr 16) and 0xFF
        val g = (rgb shr 8) and 0xFF
        val b = rgb and 0xFF
        if (
          kotlin.math.abs(r - expR) <= perChannelTolerance &&
            kotlin.math.abs(g - expG) <= perChannelTolerance &&
            kotlin.math.abs(b - expB) <= perChannelTolerance
        ) {
          matched++
        }
        total++
      }
    }
    return if (total == 0L) 0.0 else matched.toDouble() / total.toDouble()
  }

  /**
   * Minimal animated-GIF encoder for the artifact upload — uses Java's bundled `javax.imageio` GIF
   * writer plugin. Mirrors `ScrollGifEncoder` but inlined here so the test doesn't pull in
   * `:data:scroll:core` (which would muddy the daemon-desktop dependency graph).
   *
   * Reads `frame-NNNNN.png` files from [framesDir], writes [outputFile] looping forever at `1000 /
   * fps` ms per frame.
   */
  private fun encodeFramesAsGif(framesDir: File, frameCount: Int, outputFile: File, fps: Int) {
    require(frameCount > 0) { "frameCount must be > 0; got $frameCount" }
    val writer =
      ImageIO.getImageWritersByFormatName("gif").asSequence().firstOrNull()
        ?: error("No GIF writer plugin registered")
    outputFile.parentFile?.mkdirs()
    val frameDelayCs = ((1000 / fps) / 10).coerceAtLeast(2)
    FileImageOutputStream(outputFile).use { stream ->
      writer.output = stream
      val firstFrame = ImageIO.read(File(framesDir, "frame-00000.png"))
      val imageType = ImageTypeSpecifier.createFromRenderedImage(firstFrame)
      val param = writer.defaultWriteParam
      val meta = writer.getDefaultImageMetadata(imageType, param)
      configureGifFrameMetadata(meta, frameDelayCs, loopForever = true)
      writer.prepareWriteSequence(null)
      writer.writeToSequence(IIOImage(firstFrame, null, meta), param)
      for (i in 1 until frameCount) {
        val frame = ImageIO.read(File(framesDir, "frame-${"%05d".format(i)}.png"))
        val frameMeta = writer.getDefaultImageMetadata(imageType, param)
        configureGifFrameMetadata(frameMeta, frameDelayCs, loopForever = false)
        writer.writeToSequence(IIOImage(frame, null, frameMeta), param)
      }
      writer.endWriteSequence()
    }
    writer.dispose()
  }

  private fun configureGifFrameMetadata(meta: IIOMetadata, delayCs: Int, loopForever: Boolean) {
    val formatName = meta.nativeMetadataFormatName
    val root = meta.getAsTree(formatName) as IIOMetadataNode
    val gce = root.getOrCreateChild("GraphicControlExtension")
    gce.setAttribute("disposalMethod", "none")
    gce.setAttribute("userInputFlag", "FALSE")
    gce.setAttribute("transparentColorFlag", "FALSE")
    gce.setAttribute("delayTime", delayCs.toString())
    gce.setAttribute("transparentColorIndex", "0")
    if (loopForever) {
      val appExts = root.getOrCreateChild("ApplicationExtensions")
      val appExt = IIOMetadataNode("ApplicationExtension")
      appExt.setAttribute("applicationID", "NETSCAPE")
      appExt.setAttribute("authenticationCode", "2.0")
      appExt.userObject = byteArrayOf(0x1, 0x0, 0x0)
      appExts.appendChild(appExt)
    }
    meta.setFromTree(formatName, root)
  }

  private fun IIOMetadataNode.getOrCreateChild(name: String): IIOMetadataNode {
    var child = firstChild
    while (child != null) {
      if (child.nodeName.equals(name, ignoreCase = true)) return child as IIOMetadataNode
      child = child.nextSibling
    }
    val created = IIOMetadataNode(name)
    appendChild(created)
    return created
  }

  companion object {
    private const val PINCH_PREVIEW_ID = "pinch-to-zoom-square"
    private const val CANVAS_PX = 240
    private const val FPS = 30
    private const val STEP_MS = 1000L / 30L // one move per virtual frame
    private const val PINCH_DURATION_MS = 500L
    private const val POINTER_A = 1
    private const val POINTER_B = 2

    /**
     * Artifact directory for the GIF the test publishes. Lives under the repo's top-level `build/`
     * so the PR-upload step can find it without test-runner-specific paths. `repository.dir` is the
     * project's working dir — `System.getProperty("user.dir")` resolves to the module's dir when
     * run from gradle, so we walk up to the repo root.
     */
    private val ARTIFACT_DIR: File =
      File(System.getProperty("user.dir"))
        .parentFile
        .parentFile
        .resolve("build/touch-overlay-artifacts")
  }
}

/**
 * Recording-test fixture for the pinch-to-zoom + touch-overlay integration test. Same shape as the
 * `PinchToZoomPreview` in `:samples:cmp` (a centred blue square that scales via
 * `Modifier.transformable`) but lives in this module's test source set so the test doesn't need a
 * cross-module classpath. The reflective resolver in [DesktopRecordingSession] finds it via the
 * `ee.schimke.composeai.daemon.TouchOverlayPinchRecordingTestKt` class name (the Kotlin file's
 * synthetic `Kt` companion).
 */
@Composable
fun PinchableSquare() {
  var scale by remember { mutableStateOf(1f) }
  val transformableState = rememberTransformableState { zoomChange, _, _ ->
    scale = (scale * zoomChange).coerceIn(0.5f, 3.0f)
  }
  Box(
    modifier =
      Modifier.fillMaxSize()
        .background(Color(0xFFECEFF1))
        .clipToBounds()
        .transformable(state = transformableState),
    contentAlignment = Alignment.Center,
  ) {
    Box(
      modifier =
        Modifier.size(80.dp)
          .graphicsLayer(scaleX = scale, scaleY = scale)
          .background(Color(0xFF1976D2))
    )
  }
}
