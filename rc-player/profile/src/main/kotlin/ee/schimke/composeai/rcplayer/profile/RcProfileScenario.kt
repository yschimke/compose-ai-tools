package ee.schimke.composeai.rcplayer.profile

import androidx.compose.ui.geometry.Offset
import ee.schimke.composeai.rcplayer.protocol.RcDocument
import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec

/**
 * One thing to profile: a document, how many times to load it, and how many frames to draw once
 * loaded.
 *
 * The load count and the frame count are separate knobs because they answer separate questions. A
 * document is decoded, linked and turned into a layout tree **once**; it is drawn every frame. So a
 * scenario that draws 600 frames from one load tells you nothing useful about `rc:decode`, and one
 * that loads 600 documents and draws one frame each tells you nothing about the steady state. Every
 * scenario here does both, and the report reads the two groups separately.
 */
public data class RcProfileScenario(
  public val id: String,
  public val description: String,
  public val document: RcDocument,
  /** Fresh player instances built, each re-running decode → link → layout tree. */
  public val loads: Int,
  /** Frames rendered per load, after the first. */
  public val framesPerLoad: Int,
  /**
   * Pointer gestures to replay, as `(frameIndex, position)` press/release pairs. Positions are
   * scene pixels; the profile renders at density 1 so they are also document units.
   */
  public val taps: List<RcProfileTap> = emptyList(),
) {
  /** The document as wire bytes, so the profile measures the real decode a hosted player runs. */
  public val bytes: ByteArray by lazy { RcDocumentCodec.encode(document) }
}

/** A press/release pair delivered before the frame at [frameIndex] is rendered. */
public data class RcProfileTap(public val frameIndex: Int, public val position: Offset)

/** The four reference scenarios, in the order the report lists them. */
public fun rcProfileScenarios(loads: Int = 12, framesPerLoad: Int = 30): List<RcProfileScenario> {
  val centre = Offset(RcProfileDocuments.WIDTH / 2f, RcProfileDocuments.HEIGHT / 2f)
  return listOf(
    RcProfileScenario(
      id = "static-button-text",
      description = "Rounded button with a centred text label — layout tree, no canvas operations",
      document = RcProfileDocuments.staticButtonWithText(),
      loads = loads,
      framesPerLoad = framesPerLoad,
    ),
    RcProfileScenario(
      id = "static-canvas",
      description = "Fill, plate, circle and two strokes drawn as canvas operations",
      document = RcProfileDocuments.staticCanvas(),
      loads = loads,
      framesPerLoad = framesPerLoad,
    ),
    RcProfileScenario(
      id = "animated-canvas",
      description = "The static canvas plus a document-load clock sweeping a highlight every frame",
      document = RcProfileDocuments.animatedCanvas(),
      loads = loads,
      framesPerLoad = framesPerLoad,
    ),
    RcProfileScenario(
      id = "interactive-button",
      description = "The button made clickable — ripple, host action, and a float the canvas reads",
      document = RcProfileDocuments.interactiveButton(),
      loads = loads,
      framesPerLoad = framesPerLoad,
      // Tap every fifth frame, leaving frames in between for the repaint the action triggers.
      taps = (1 until framesPerLoad step 5).map { RcProfileTap(it, centre) },
    ),
  )
}
