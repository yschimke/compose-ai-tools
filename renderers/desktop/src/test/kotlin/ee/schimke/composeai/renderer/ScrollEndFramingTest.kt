package ee.schimke.composeai.renderer

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.overrides.previewOverrideColor
import java.io.File
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A scrollable whose rows read `isSystemInDarkTheme()`: green under dark, red under light. Long
 * enough that END has something to drive, so the theme question is asked of the *driven* capture
 * rather than of a frame that never scrolled.
 */
@Composable
fun DarkAwareScrollFixture() {
  val rowColor = if (isSystemInDarkTheme()) Color.Green else Color.Red
  LazyColumn(modifier = Modifier.fillMaxSize()) {
    items(20) { Box(modifier = Modifier.fillMaxWidth().height(40.dp).background(rowColor)) }
  }
}

/**
 * As [DarkAwareScrollFixture], but its rows occupy only half the width so the resolved background
 * stays visible beside them.
 *
 * A full-bleed fixture cannot see this bug at all: driven to the end, its rows cover every pixel of
 * the viewport, so the ground behind them is white or dark with equally no evidence either way.
 */
@Composable
fun NarrowRowScrollFixture() {
  val rowColor = if (isSystemInDarkTheme()) Color.Green else Color.Red
  LazyColumn(modifier = Modifier.fillMaxSize()) {
    items(20) { Box(modifier = Modifier.fillMaxWidth(0.5f).height(40.dp).background(rowColor)) }
  }
}

/** A scrollable declaring one `previewOverride*` knob, so the END capture owes a sidecar. */
@Composable
fun KnobbedScrollFixture() {
  val rowColor = previewOverrideColor(key = END_KNOB_KEY, default = Color(0xFF1B5E20))
  LazyColumn(modifier = Modifier.fillMaxSize()) {
    items(20) { Box(modifier = Modifier.fillMaxWidth().height(40.dp).background(rowColor)) }
  }
}

const val END_KNOB_KEY: String = "endRowColor"

/**
 * `@ScrollingPreview(END)` writes an ordinary primary preview PNG — it is the one scroll mode whose
 * product is the sticker itself. So every `@Preview` option that shapes an ordinary capture still
 * applies to it: the night flip, the resolved background, `showSystemUi` chrome, the round-device
 * clip, and the `.overrides.json` sidecar.
 *
 * None of them did. A successful END drive reports `didCapture = true`, which makes the caller skip
 * the fall-through to `renderPreview` — the very path that applies all of the above. So the options
 * were honoured precisely when the drive DECLINED, and dropped whenever it worked.
 *
 * Each test here was confirmed to fail before the fix, with the symptom named in its comment.
 */
class ScrollEndFramingTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  private val fixtureClass = "ee.schimke.composeai.renderer.ScrollEndFramingTestKt"

  /**
   * The renderer's positional argv. Padded out to the framing arguments (21–23), which the older
   * scroll tests stop short of — being unable to *express* `uiMode` or `device` is a large part of
   * why this went unnoticed.
   */
  private fun rendererArgs(
    function: String,
    outputFile: File,
    showBackground: Boolean = true,
    showSystemUi: Boolean = false,
    uiMode: Int = 0,
    device: String = "",
  ): Array<String> =
    arrayOf(
      fixtureClass,
      function,
      "100", // widthPx
      "160", // heightPx
      "1.0", // density
      showBackground.toString(),
      "0", // backgroundColor
      outputFile.absolutePath,
      "", // wrapperClassName
      "false", // wrapWidth
      "false", // wrapHeight
      "", // previewParameterProviderFqn
      "0", // previewParameterLimit
      "", // localeTag
      "END", // scrollMode
      "VERTICAL", // scrollAxis
      "0", // maxScrollPx
      "0", // scrollFrameIntervalMs
      "COMPOSE", // previewKind
      "", // assetPath
      "1.0", // fontScale
      showSystemUi.toString(),
      uiMode.toString(),
      device,
    )

  private fun renderToFile(
    label: String,
    function: String,
    showBackground: Boolean = true,
    showSystemUi: Boolean = false,
    uiMode: Int = 0,
    device: String = "",
  ): File {
    val out = tempFolder.newFolder(label).resolve("capture.png")
    main(rendererArgs(function, out, showBackground, showSystemUi, uiMode, device))
    assertTrue("a capture must be written to $out", out.exists())
    return out
  }

  private fun render(
    label: String,
    function: String,
    showBackground: Boolean = true,
    showSystemUi: Boolean = false,
    uiMode: Int = 0,
    device: String = "",
  ): java.awt.image.BufferedImage {
    val out = renderToFile(label, function, showBackground, showSystemUi, uiMode, device)
    return ImageIO.read(out) ?: error("capture was not decodable: $out")
  }

  private fun java.awt.image.BufferedImage.countWhere(
    predicate: (r: Int, g: Int, b: Int, a: Int) -> Boolean
  ): Int {
    var n = 0
    for (y in 0 until height) {
      for (x in 0 until width) {
        val argb = getRGB(x, y)
        val a = (argb ushr 24) and 0xFF
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        if (predicate(r, g, b, a)) n++
      }
    }
    return n
  }

  private fun java.awt.image.BufferedImage.greenPixels() = countWhere { r, g, b, _ ->
    g > 180 && r < 80 && b < 80
  }

  private fun java.awt.image.BufferedImage.redPixels() = countWhere { r, g, b, _ ->
    r > 180 && g < 80 && b < 80
  }

  /**
   * `@Preview(uiMode = 32)`. Before the fix the driven capture never provided `LocalSystemTheme`,
   * so `isSystemInDarkTheme()` reported light and the fixture painted RED under a night preview —
   * the opposite of what the same preview's ordinary capture shows.
   */
  @Test
  fun `an END capture honours the night uiMode`() {
    val night = render("night", "DarkAwareScrollFixture", uiMode = 32)
    assertTrue("a night END capture must render its dark colours", night.greenPixels() > 0)
    assertEquals("no light-theme rows may survive in a night capture", 0, night.redPixels())
  }

  /** The control: the same fixture with no uiMode still renders light, so the flip is the cause. */
  @Test
  fun `an END capture with no uiMode stays light`() {
    val day = render("day", "DarkAwareScrollFixture")
    assertTrue("a day END capture must render its light colours", day.redPixels() > 0)
    assertEquals("no dark-theme rows may appear without the night bit", 0, day.greenPixels())
  }

  /**
   * `showBackground = true` on a night preview is not white — the resolution is shared with the
   * single-frame path via `PreviewBackground`. The driven path used a local `Color.White`, so a
   * night END capture was dark content on a white ground.
   */
  @Test
  fun `an END capture resolves the night background rather than white`() {
    val night = render("night-bg", "NarrowRowScrollFixture", showBackground = true, uiMode = 32)
    val whitePixels = night.countWhere { r, g, b, _ -> r > 240 && g > 240 && b > 240 }
    // Guard the guard: "no white" is trivially true of a capture whose rows cover every pixel, so
    // check the ground is actually on screen — dark, opaque, and not one of the fixture's rows.
    val rowPixels = night.greenPixels()
    val darkGround = night.countWhere { r, g, b, a -> a > 0 && r < 60 && g < 60 && b < 60 }
    assertTrue("the fixture must still draw its rows", rowPixels > 0)
    assertTrue("the fixture must leave resolved background visible beside its rows", darkGround > 0)
    assertEquals("a night capture must not be backed by white", 0, whitePixels)
  }

  /**
   * A round device clips to the circle, so its corners carry no pixels. The driven path wrote the
   * raw square surface, shipping an unclipped capture beside clipped siblings from the same
   * catalog.
   */
  @Test
  fun `an END capture on a round device is clipped to the circle`() {
    val round = render("round", "DarkAwareScrollFixture", device = "id:wearos_small_round")
    val corners =
      listOf(
        round.getRGB(0, 0),
        round.getRGB(round.width - 1, 0),
        round.getRGB(0, round.height - 1),
        round.getRGB(round.width - 1, round.height - 1),
      )
    corners.forEach { assertEquals("a round capture's corners must be transparent", 0, it ushr 24) }
    // …and the middle still has content, so this isn't passing because the whole frame is empty.
    assertTrue(
      "the clipped capture must still carry its rows",
      (round.getRGB(round.width / 2, round.height / 2) ushr 24) > 0,
    )
  }

  /**
   * `@Preview(showSystemUi = true)` wraps the composition in the synthetic status/nav chrome. The
   * driven path skipped it, so a phone END capture came back the right size but chrome-less.
   */
  @Test
  fun `an END capture honours showSystemUi`() {
    val bare = render("bare", "DarkAwareScrollFixture")
    val framed = render("framed", "DarkAwareScrollFixture", showSystemUi = true)
    // The frame reserves bands top and bottom, so strictly fewer rows fit than in the bare capture.
    assertTrue(
      "system bars must displace content (bare=${bare.redPixels()}, " +
        "framed=${framed.redPixels()})",
      framed.redPixels() < bare.redPixels(),
    )
  }

  /**
   * The knobs a preview declares are drained beside the PNG as `<stem>.overrides.json`. A driven
   * END capture wrote none — and because it reports success, the fall-through that would have
   * written one never ran, so the bundled interactive overrides did not describe the capture.
   */
  @Test
  fun `an END capture writes the overrides sidecar it declared`() {
    val png = renderToFile("knobs", "KnobbedScrollFixture")
    val sidecar = File(png.parentFile, png.name.removeSuffix(".png") + ".overrides.json")
    assertTrue("a declared knob must be drained beside the capture at $sidecar", sidecar.exists())
    assertTrue(
      "the sidecar must name the knob the fixture declared",
      sidecar.readText().contains(END_KNOB_KEY),
    )
  }
}
