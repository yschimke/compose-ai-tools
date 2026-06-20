package ee.schimke.composeai.clients

/**
 * A pointer/key event a client forwards into the held composition, in **image-natural pixel**
 * coordinates — the same space the daemon's renderer thinks in and the same contract the VS Code
 * panel uses (`docs/daemon/INTERACTIVE.md` § 6/7). The app captures a touch in view-local pixels
 * and scales it with [scalePointer] before sending.
 *
 * [kind] wire-spellings match the daemon's `InteractiveInputKind` `@SerialName`s exactly (`click`,
 * `pointerDown`, `pointerMove`, `pointerUp`, `rotaryScroll`, `keyDown`, `keyUp`) so a serve lane
 * that grows input support routes the message straight to `interactive/input` with no translation.
 */
data class InputEvent(
  val kind: Kind,
  val pixelX: Int? = null,
  val pixelY: Int? = null,
  /**
   * Per-pointer id for multi-touch (pinch/rotate). Defaults to 0; ignored for non-pointer kinds.
   */
  val pointerId: Int = 0,
  /** Wheel/rotary delta for [Kind.ROTARY_SCROLL]; positive means scroll-down. */
  val scrollDeltaY: Float? = null,
  /** Key identifier for [Kind.KEY_DOWN] / [Kind.KEY_UP] (e.g. "Enter", "Backspace", "A"). */
  val keyCode: String? = null,
) {
  enum class Kind(val wire: String) {
    CLICK("click"),
    POINTER_DOWN("pointerDown"),
    POINTER_MOVE("pointerMove"),
    POINTER_UP("pointerUp"),
    ROTARY_SCROLL("rotaryScroll"),
    KEY_DOWN("keyDown"),
    KEY_UP("keyUp"),
  }

  companion object {
    /** A single tap at an image-natural pixel position. */
    fun click(pixelX: Int, pixelY: Int): InputEvent =
      InputEvent(Kind.CLICK, pixelX = pixelX, pixelY = pixelY)

    /** A rotary-bezel / wheel scroll (Wear crown, mouse wheel). */
    fun rotaryScroll(deltaY: Float): InputEvent =
      InputEvent(Kind.ROTARY_SCROLL, scrollDeltaY = deltaY)

    /** A key press+release pair is two events; these mint each half. */
    fun keyDown(keyCode: String): InputEvent = InputEvent(Kind.KEY_DOWN, keyCode = keyCode)

    fun keyUp(keyCode: String): InputEvent = InputEvent(Kind.KEY_UP, keyCode = keyCode)

    /**
     * Map a touch in the on-screen image's view-local pixel space onto the frame's image-natural
     * pixel space. The app draws a [StreamFrame] of [frameWidthPx]×[frameHeightPx] into a box of
     * [viewWidthPx]×[viewHeightPx]; this converts the tap back, so the coordinate the daemon
     * receives lands on the same element the user touched regardless of display scaling.
     *
     * Assumes the frame is letterboxed-fit (uniform scale, centred) — the default
     * `ContentScale.Fit` the canvas uses. Returns coordinates clamped into `[0, frame)`.
     */
    fun scalePointer(
      viewX: Float,
      viewY: Float,
      viewWidthPx: Float,
      viewHeightPx: Float,
      frameWidthPx: Int,
      frameHeightPx: Int,
    ): Pair<Int, Int> {
      if (frameWidthPx <= 0 || frameHeightPx <= 0 || viewWidthPx <= 0f || viewHeightPx <= 0f) {
        return 0 to 0
      }
      val scale = minOf(viewWidthPx / frameWidthPx, viewHeightPx / frameHeightPx)
      val drawnW = frameWidthPx * scale
      val drawnH = frameHeightPx * scale
      val offsetX = (viewWidthPx - drawnW) / 2f
      val offsetY = (viewHeightPx - drawnH) / 2f
      val fx = ((viewX - offsetX) / scale).toInt().coerceIn(0, frameWidthPx - 1)
      val fy = ((viewY - offsetY) / scale).toInt().coerceIn(0, frameHeightPx - 1)
      return fx to fy
    }
  }
}
