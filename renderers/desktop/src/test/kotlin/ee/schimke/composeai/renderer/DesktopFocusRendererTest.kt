package ee.schimke.composeai.renderer

import ee.schimke.composeai.daemon.protocol.FocusDirection
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Desktop counterpart of `:samples:android-alpha`'s `FocusedPreviewPixelTest`: proves that
 * `@FocusedPreview` on a **Compose Multiplatform Desktop** module drives real focus (and, with
 * `pressed = true`, a real pointer press) rather than rendering the resting frame N times.
 *
 * Before this path existed the desktop renderer ignored the per-capture focus state discovery had
 * already emitted, which is why the CMP catalog forged its focused / pressed stickers with a held
 * `MutableInteractionSource` (issue #3672). Each test here fails if that regression returns: the
 * fixture is plain `Button`s with no interaction source of their own, so a capture can only differ
 * from the undriven one when the component actually received the input.
 */
class DesktopFocusRendererTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  private val fixtureClass = "ee.schimke.composeai.renderer.FocusRenderTestFixturesKt"

  private fun render(
    name: String,
    focus: DesktopFocusIntent?,
    functionName: String = "FocusableButtonRow",
  ): Pair<File, Boolean> {
    val out = File(tempFolder.newFolder(name), "$name.png")
    val drove =
      if (focus == null) {
        renderPreview(
          className = fixtureClass,
          functionName = functionName,
          widthPx = 800,
          heightPx = 400,
          density = 2.0f,
          showBackground = true,
          backgroundColor = 0L,
          outputFile = out,
          wrapperClassName = null,
          wrapWidth = true,
          wrapHeight = true,
          previewArgs = emptyList(),
          localeTag = null,
        )
        true
      } else {
        renderFocusPreview(
          className = fixtureClass,
          functionName = functionName,
          widthPx = 800,
          heightPx = 400,
          density = 2.0f,
          showBackground = true,
          backgroundColor = 0L,
          outputFile = out,
          wrapperClassName = null,
          wrapWidth = true,
          wrapHeight = true,
          previewArgs = emptyList(),
          localeTag = null,
          focus = focus,
        )
      }
    // Keep the captures for the PR's visual evidence (build dir, not committed).
    if (out.exists()) {
      File("build/focus-evidence").apply { mkdirs() }.let { out.copyTo(File(it, out.name), true) }
    }
    return out to drove
  }

  private fun decode(file: File): BufferedImage {
    assertTrue("rendered PNG must exist: ${file.absolutePath}", file.exists() && file.length() > 0)
    return ByteArrayInputStream(file.readBytes()).use { ImageIO.read(it) } ?: error("no decode")
  }

  private fun bytes(file: File): Int = file.readBytes().contentHashCode()

  /**
   * The headline property: focusing the first button changes pixels. If `LocalInputModeManager`
   * fell back to Touch — `Modifier.clickable` registers its focusable as `Focusability
   * .SystemDefined`, which refuses focus in touch mode — or the walk never ran, the capture would
   * be byte-identical to the undriven one, exactly the state the CMP catalog was stuck in.
   */
  @Test
  fun focusedCaptureDiffersFromUndrivenCapture() {
    val (plain, _) = render("desktop-focus-none", null)
    val (focused, drove) = render("desktop-focus-0", DesktopFocusIntent(tabIndex = 0))
    assertTrue("focus walk must land on a focusable", drove)
    assertNotEquals(
      "focused capture must differ from the undriven one",
      bytes(plain),
      bytes(focused),
    )
    // Same framing — a focused capture is the resting capture plus a state, never a reflow.
    assertEquals(decode(plain).width, decode(focused).width)
    assertEquals(decode(plain).height, decode(focused).height)
  }

  /**
   * Indexed fan-out lands on a *different* button per index. A broken walk (missing `Enter`, wrong
   * `+1` compensation, or a controller that never re-fires) collapses these into equal captures.
   */
  @Test
  fun indexedCapturesLandOnDifferentButtons() {
    val hashes =
      (0..2).map { index ->
        bytes(render("desktop-focus-idx$index", DesktopFocusIntent(tabIndex = index)).first)
      }
    assertEquals("each index must produce a distinct capture", 3, hashes.toSet().size)
  }

  /**
   * Traversal mode walks `Next, Next, Previous` to buttons 0, 1, 0 — the desktop twin of the
   * Android pixel test's `Next, Next, Previous, Next → 0, 1, 0, 1`.
   *
   * Each capture is its own scene here (one process per capture on this backend), so every step
   * replays the whole prefix; the assertion below is what catches a regression to "apply only this
   * step's direction", which would render all three steps as the same first-focusable frame. Step 1
   * must also equal the `indices = [0]` capture: `Enter` parks focus before the first focusable, so
   * one `Next` and index 0 are the same place.
   */
  @Test
  fun traversalStepsWalkForwardAndBack() {
    val indexed = bytes(render("desktop-focus-tr-idx0", DesktopFocusIntent(tabIndex = 0)).first)
    val steps =
      listOf(
          listOf(FocusDirection.Next),
          listOf(FocusDirection.Next, FocusDirection.Next),
          listOf(FocusDirection.Next, FocusDirection.Next, FocusDirection.Previous),
        )
        .mapIndexed { i, directions ->
          bytes(
            render(
                "desktop-focus-tr${i + 1}",
                DesktopFocusIntent(directions = directions, step = i + 1),
              )
              .first
          )
        }
    assertEquals("step 1 (Enter + Next) lands where index 0 does", indexed, steps[0])
    assertNotEquals("step 2 must move forward off button 0", steps[0], steps[1])
    assertEquals("step 3 (Previous) must come back to button 0", steps[0], steps[2])
  }

  /**
   * `pressed = true` dispatches a real pointer down onto the focused button, so the pressed capture
   * must differ from the focus-only one. The fixture's buttons carry no interaction source of their
   * own — the press has to be routed through hit testing into `Modifier.clickable` for any pixel to
   * change, which is the "the real component received it" proof #3672 asks for.
   */
  @Test
  fun pressedCaptureDiffersFromFocusedCapture() {
    val focused = bytes(render("desktop-focus-nopress", DesktopFocusIntent(tabIndex = 0)).first)
    val pressed =
      bytes(render("desktop-focus-press", DesktopFocusIntent(tabIndex = 0, pressed = true)).first)
    assertNotEquals("pressed capture must differ from the focused one", focused, pressed)
  }

  /**
   * `overlay = true` paints the review marker over the focused element and preserves the unmarked
   * capture as `<basename>.raw.png` — same contract the Android overlay has.
   */
  @Test
  fun overlayMarksCaptureAndKeepsRawCompanion() {
    val (marked, _) =
      render("desktop-focus-overlay", DesktopFocusIntent(tabIndex = 0, overlay = true))
    val raw = File(marked.parentFile, marked.nameWithoutExtension + ".raw.png")
    assertTrue("raw companion must be preserved", raw.exists())
    assertNotEquals(bytes(raw), bytes(marked))
    val image = decode(marked)
    var overlayRed = 0
    for (y in 0 until image.height) {
      for (x in 0 until image.width) {
        val rgb = image.getRGB(x, y)
        val r = (rgb shr 16) and 0xFF
        val g = (rgb shr 8) and 0xFF
        val b = rgb and 0xFF
        if (r > 0xE0 && g in 0x20..0x60 && b in 0x20..0x60) overlayRed++
      }
    }
    assertTrue("overlay stroke must be painted (found $overlayRed marker pixels)", overlayRed > 0)
  }

  /**
   * Nothing focusable → decline, so the caller falls back to the undriven capture rather than
   * publishing a PNG under a focus label that no component could have taken.
   */
  @Test
  fun declinesWhenNothingCanTakeFocus() {
    val (_, drove) =
      render("desktop-focus-decline", DesktopFocusIntent(tabIndex = 0), "NoFocusableRow")
    assertFalse("a preview with no focusable must decline the focus drive", drove)
  }
}
