/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ee.schimke.composeai.rcembedded.jvm

import androidx.compose.remote.core.RemoteComposeState
import androidx.compose.remote.core.SystemClock
import androidx.compose.remote.player.compose.embedded.SnapshotRemoteComposeState
import androidx.compose.remote.player.compose.embedded.StoreBackedRemoteContext
import androidx.compose.remote.player.compose.embedded.TextPaintSpec
import androidx.compose.remote.player.compose.embedded.drawTextAtOriginPlatform
import androidx.compose.remote.player.compose.embedded.drawTextOnPathPlatform
import androidx.compose.remote.player.compose.embedded.measureTextInkBounds
import androidx.compose.remote.player.compose.embedded.measureTextWidth
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test

/**
 * Exercises the **jvm half of the canvas text seam** — `RcPlayerTextPlatformJvm.kt`'s four
 * functions — on a plain desktop JVM, rasterizing through skiko with no Android and no Robolectric.
 *
 * ## What is asserted, and what deliberately is not
 *
 * Not asserted: any particular number. Android's font stack and the host's are different, so
 * metrics are not comparable across the seam and pinning a width here would pin *this container's
 * fonts*. `PROVENANCE.md` records that as a parity limit; the seam's value is that both halves
 * answer the same four questions, not that they answer them identically.
 *
 * Asserted instead: the **relationships the callers in `RcPlayerDrawing.kt` depend on**. Chiefly
 * [inkBoundsPredictWhereTheGlyphsLand] — `DrawTextAnchored` measures ink bounds, does arithmetic on
 * `left`/`top`, then draws at the result, so measurement and drawing agreeing about the origin *is*
 * the contract. A jvm half that measured with one face and drew with another would pass every other
 * test in this file and fail that one.
 *
 * Face *selection* is not asserted either — that a family id resolves to a specific typeface
 * depends on what fonts the host has, and an assertion about that would be testing fontconfig. What
 * is covered is that every family id, including an unresolvable one, produces a usable face rather
 * than throwing or measuring zero.
 */
class DesktopTextPlatformTest {

  /**
   * Skips the class when skiko's native library cannot be loaded at all, rather than reporting a
   * cryptic `NoClassDefFoundError` against every method.
   *
   * `libskiko-<host>.so` has a link-time dependency on the platform's GL library even for the
   * purely raster drawing this test does, so a host without one — or, as in the container this was
   * written in, a JVM whose dynamic loader cannot see the one that is installed — cannot run *any*
   * of it. That is an environment fact, not a fault in the code under test, and reporting it as
   * sixteen failures buries the one line that says so.
   *
   * The skip is deliberately noisy: a quiet one would let this whole file evaporate on CI and still
   * report green, which is worse than no test. If it prints, fix the environment — on Linux that
   * means `libgl1`, and `LD_LIBRARY_PATH` covering it for the JVM that runs the tests. Note
   * Gradle's test workers inherit the *daemon's* environment, so a daemon started before that was
   * exported keeps the old one; `./gradlew --stop` first.
   */
  @Before
  fun requireSkikoNatives() {
    if (!SKIKO_LOADED) {
      System.err.println(
        "DesktopTextPlatformTest skipped entirely: skiko's native library did not load, so nothing " +
          "here exercised the jvm text seam. Cause: $skikoLoadFailure"
      )
    }
    Assume.assumeTrue("skiko natives unavailable: $skikoLoadFailure", SKIKO_LOADED)
  }

  private class TestContext(state: RemoteComposeState) : StoreBackedRemoteContext(SystemClock()) {
    init {
      mRemoteComposeState = state
    }
  }

  private fun newContext(): TestContext = TestContext(SnapshotRemoteComposeState())

  /** Default spec: unset typeface, so the platform default face — the common case. */
  private fun spec(
    textSize: Float = 24f,
    fontFamily: Int = 0,
    isTypefaceSet: Boolean = false,
    fontWeight: Int = 400,
    italic: Boolean = false,
  ) =
    TextPaintSpec(
      textSize = textSize,
      fontFamily = fontFamily,
      isTypefaceSet = isTypefaceSet,
      fontWeight = fontWeight,
      italic = italic,
      argbColor = OPAQUE_BLACK,
    )

  /**
   * The bounding box of every pixel a [block] actually painted, or null if it painted nothing.
   *
   * An [ImageBitmap] starts fully transparent, so a non-zero alpha channel is exactly "this pixel
   * was marked" — no tolerance needed, and it catches anti-aliased edges too, which is why the box
   * comparisons below allow a pixel or two of slack.
   */
  private fun paintedBounds(
    width: Int = 240,
    height: Int = 160,
    block: DrawScope.() -> Unit,
  ): Rect? {
    val bitmap = ImageBitmap(width, height)
    CanvasDrawScope()
      .draw(
        Density(1f),
        LayoutDirection.Ltr,
        Canvas(bitmap),
        Size(width.toFloat(), height.toFloat()),
        block,
      )
    val pixels = IntArray(width * height)
    bitmap.readPixels(pixels)

    var minX = Int.MAX_VALUE
    var minY = Int.MAX_VALUE
    var maxX = Int.MIN_VALUE
    var maxY = Int.MIN_VALUE
    for (y in 0 until height) {
      for (x in 0 until width) {
        if ((pixels[y * width + x] ushr 24) != 0) {
          if (x < minX) minX = x
          if (y < minY) minY = y
          if (x > maxX) maxX = x
          if (y > maxY) maxY = y
        }
      }
    }
    if (minX == Int.MAX_VALUE) return null
    // Inclusive pixel indices to an exclusive-right box, so width/height read as pixel counts.
    return Rect(minX.toFloat(), minY.toFloat(), maxX + 1f, maxY + 1f)
  }

  @Test
  fun measuresAPositiveAdvanceThatGrowsWithTheString() {
    val context = newContext()
    val short = measureTextWidth("Hi", spec(), context)
    val long = measureTextWidth("Hi there, world", spec(), context)
    assertTrue("a measured advance of $short is not a usable measurement", short > 0f)
    assertTrue("$long should exceed $short", long > short)
  }

  @Test
  fun measurementsScaleWithTextSize() {
    val context = newContext()
    val small = measureTextWidth("Measured", spec(textSize = 20f), context)
    val large = measureTextWidth("Measured", spec(textSize = 40f), context)
    // Advances are linear in text size up to hinting, so the ratio should be 2 within a few
    // percent.
    assertEquals("doubling textSize should double the advance", 2f, large / small, 0.1f)
  }

  @Test
  fun inkBoundsAreTightAroundTheGlyphsRatherThanALayoutBox() {
    val context = newContext()
    // "HELLO" has no descenders, so its ink sits between the baseline and the cap height. Layout
    // bounds would include the descent and line spacing below y=0; ink bounds must not.
    val bounds = measureTextInkBounds("HELLO", spec(), context)
    assertTrue("ink should extend above the baseline, got top=${bounds.top}", bounds.top < 0f)
    assertTrue(
      "ink of a descender-free string should not reach far below the baseline, " +
        "got bottom=${bounds.bottom}",
      bounds.bottom <= 1f,
    )
    assertTrue("ink width should be positive, got ${bounds.width}", bounds.width > 0f)

    val advance = measureTextWidth("HELLO", spec(), context)
    assertTrue(
      "ink width ${bounds.width} should not exceed the advance $advance by more than a side bearing",
      bounds.width <= advance + 2f,
    )
  }

  /**
   * The seam's central invariant: what [measureTextInkBounds] reports is where
   * [drawTextAtOriginPlatform] puts the glyphs, both relative to the same origin.
   *
   * `DrawTextAnchored` computes its position from `bounds.left`/`bounds.top` and then draws at it,
   * so if these two disagreed — a different resolved face, a different text size, a different
   * origin convention — anchored text would be placed by a measurement of something else. On
   * Android that is guaranteed by both sides building the same framework `Paint`; here it is
   * guaranteed by both going through `toSkiaFont`, and this is the test that would catch it
   * regressing.
   */
  @Test
  fun inkBoundsPredictWhereTheGlyphsLand() {
    val context = newContext()
    val text = "Anchored"
    val originX = 40f
    val originY = 100f
    val bounds = measureTextInkBounds(text, spec(), context)

    val painted =
      paintedBounds { drawTextAtOriginPlatform(text, originX, originY, spec(), context) }
        ?: error("drawing text at an origin inside the canvas painted nothing")

    // Anti-aliasing spreads coverage about a pixel past the geometric outline, and ink bounds are
    // exact, so allow a small slack in each direction rather than demanding equality.
    val slack = 2f
    assertEquals("left edge", originX + bounds.left, painted.left, slack)
    assertEquals("top edge", originY + bounds.top, painted.top, slack)
    assertEquals("right edge", originX + bounds.right, painted.right, slack)
    assertEquals("bottom edge", originY + bounds.bottom, painted.bottom, slack)
  }

  @Test
  fun drawingAtAnOriginTreatsItAsThePenAndBaseline() {
    val context = newContext()
    val painted =
      paintedBounds { drawTextAtOriginPlatform("HELLO", 40f, 100f, spec(), context) }
        ?: error("painted nothing")
    // Pen start: glyphs begin at x, not centred on it. Baseline: a descender-free string sits
    // above.
    assertTrue(
      "glyphs should start at or after the pen x, got ${painted.left}",
      painted.left >= 38f,
    )
    assertTrue(
      "a descender-free string should stay above the baseline, got bottom=${painted.bottom}",
      painted.bottom <= 102f,
    )
    assertTrue("glyphs should rise above the baseline, got top=${painted.top}", painted.top < 100f)
  }

  @Test
  fun everyFamilyIdProducesAUsableFace() {
    val context = newContext()
    // 0..3 are the core generics; each must resolve to *something* that measures.
    for (family in 0..3) {
      val width =
        measureTextWidth("Family", spec(fontFamily = family, isTypefaceSet = true), context)
      assertTrue("generic family $family measured $width", width > 0f)
    }
  }

  @Test
  fun namedAndDownloadableFamiliesFallBackRatherThanFailing() {
    val context = newContext()
    // A `google:` name is a download request on Android and has no JVM equivalent; a nonsense name
    // matches nothing anywhere. Both must substitute a face, not throw or measure zero — a document
    // naming a font it can't have still has to render.
    context.loadText(700, "google:Roboto")
    context.loadText(701, "NoSuchFontFamilyAnywhere")
    context.loadText(702, "device:DejaVu Sans")
    for (id in 700..702) {
      val text = context.getText(id)
      val width = measureTextWidth("Fallback", spec(fontFamily = id, isTypefaceSet = true), context)
      assertTrue("family '$text' measured $width", width > 0f)
    }
  }

  @Test
  fun anUnresolvableFamilyIdFallsBackToTheDefaultFace() {
    val context = newContext()
    // No text loaded at this id, so `getText` answers null — the same branch `toNativeTextPaint`
    // handles by falling to generic 0.
    val orphan = measureTextWidth("Orphan", spec(fontFamily = 9999, isTypefaceSet = true), context)
    val default = measureTextWidth("Orphan", spec(), context)
    assertEquals("an unresolvable id should measure as the default face", default, orphan, 0.01f)
  }

  @Test
  fun textOnAHorizontalPathReadsLikeOrdinaryText() {
    val context = newContext()
    val path =
      Path().apply {
        moveTo(20f, 100f)
        lineTo(220f, 100f)
      }
    val painted =
      paintedBounds { drawTextOnPathPlatform("ALONG", path, 0f, 0f, spec(), context) }
        ?: error("painted nothing")
    // A straight horizontal path is the degenerate case: glyphs should sit in a short, wide band
    // straddling the path's y, upright and in order.
    assertTrue("should be wider than tall, got $painted", painted.width > painted.height)
    assertTrue("should start near the path start, got ${painted.left}", painted.left in 15f..60f)
  }

  @Test
  fun textOnAVerticalPathIsRotatedToFollowIt() {
    val context = newContext()
    val path =
      Path().apply {
        moveTo(120f, 20f)
        lineTo(120f, 150f)
      }
    val painted =
      paintedBounds { drawTextOnPathPlatform("DOWNWARD", path, 0f, 0f, spec(), context) }
        ?: error("painted nothing")
    // The point of the RSXform placement: glyphs are *rotated*, not merely moved. Upright glyphs
    // stacked along a vertical path would still each be wider than tall and the run would be no
    // taller than one glyph; a rotated run is taller than it is wide.
    assertTrue(
      "a run along a vertical path should be taller than wide, got $painted",
      painted.height > painted.width,
    )
    assertTrue(
      "the run should span most of the path, got height=${painted.height}",
      painted.height > 60f,
    )
  }

  @Test
  fun textOnACircleBendsAroundIt() {
    val context = newContext()
    // The arc `DrawTextOnCircle` builds, via the same Compose `Path.addArc` the op uses.
    val path = Path().apply { addArc(Rect(40f, 20f, 200f, 140f), 180f, 180f) }
    val painted =
      paintedBounds { drawTextOnPathPlatform("CURVED TEXT", path, 0f, 0f, spec(), context) }
        ?: error("painted nothing")
    // Along the top half of a circle the run must occupy two dimensions — a chord-laid string would
    // be a flat band a glyph high.
    assertTrue("a curved run should be tall, got $painted", painted.height > 25f)
    assertTrue("a curved run should be wide, got $painted", painted.width > 80f)
  }

  @Test
  fun vOffsetShiftsPerpendicularToThePath() {
    val context = newContext()
    fun path() =
      Path().apply {
        moveTo(20f, 80f)
        lineTo(220f, 80f)
      }
    val level =
      paintedBounds { drawTextOnPathPlatform("OFFSET", path(), 0f, 0f, spec(), context) }
        ?: error("painted nothing")
    val shifted =
      paintedBounds { drawTextOnPathPlatform("OFFSET", path(), 0f, 30f, spec(), context) }
        ?: error("painted nothing")
    // +vOffset is along the path's perpendicular, which for a left-to-right path points down.
    assertEquals("vOffset should move the run by its own amount", 30f, shifted.top - level.top, 3f)
    assertEquals("and should not move it horizontally", level.left, shifted.left, 2f)
  }

  @Test
  fun hOffsetShiftsAlongThePath() {
    val context = newContext()
    fun path() =
      Path().apply {
        moveTo(20f, 80f)
        lineTo(220f, 80f)
      }
    val level =
      paintedBounds { drawTextOnPathPlatform("OFFSET", path(), 0f, 0f, spec(), context) }
        ?: error("painted nothing")
    val shifted =
      paintedBounds { drawTextOnPathPlatform("OFFSET", path(), 25f, 0f, spec(), context) }
        ?: error("painted nothing")
    assertEquals("hOffset should move the run along the path", 25f, shifted.left - level.left, 3f)
    assertEquals("and should not move it perpendicular", level.top, shifted.top, 2f)
  }

  @Test
  fun aRunTooLongForThePathIsClipped() {
    val context = newContext()
    val shortPath =
      Path().apply {
        moveTo(20f, 80f)
        lineTo(70f, 80f)
      }
    val longPath =
      Path().apply {
        moveTo(20f, 80f)
        lineTo(220f, 80f)
      }
    val text = "FAR TOO LONG FOR FIFTY PIXELS"
    val onShort =
      paintedBounds { drawTextOnPathPlatform(text, shortPath, 0f, 0f, spec(), context) }
        ?: error("painted nothing")
    val onLong =
      paintedBounds { drawTextOnPathPlatform(text, longPath, 0f, 0f, spec(), context) }
        ?: error("painted nothing")
    // Glyphs past the end of the path are dropped rather than piling up at the end or wrapping.
    assertTrue(
      "the clipped run ($onShort) should be narrower than the full one ($onLong)",
      onShort.width < onLong.width,
    )
    assertTrue(
      "the clipped run should not overrun the path, got right=${onShort.right}",
      onShort.right <= 80f,
    )
  }

  @Test
  fun aRunContinuesOntoTheNextContour() {
    val context = newContext()
    // Two short, well-separated contours. A run too long for the first must resume on the second
    // rather than stopping — that is the `nextContour` branch.
    val twoContours =
      Path().apply {
        moveTo(20f, 40f)
        lineTo(90f, 40f)
        moveTo(20f, 130f)
        lineTo(90f, 130f)
      }
    val painted =
      paintedBounds {
        drawTextOnPathPlatform("SPANNING TWO CONTOURS", twoContours, 0f, 0f, spec(), context)
      } ?: error("painted nothing")
    // Both contours drew, so the painted box spans the gap between y=40 and y=130.
    assertTrue("expected ink near both contours, got $painted", painted.top < 45f)
    assertTrue("expected ink near both contours, got $painted", painted.bottom > 110f)
  }

  @Test
  fun degenerateInputsDrawNothingAndDoNotThrow() {
    val context = newContext()
    val path =
      Path().apply {
        moveTo(20f, 80f)
        lineTo(220f, 80f)
      }
    assertEquals(
      "empty text should paint nothing",
      null,
      paintedBounds { drawTextOnPathPlatform("", path, 0f, 0f, spec(), context) },
    )
    assertEquals(
      "an empty path has nowhere to place a glyph",
      null,
      paintedBounds { drawTextOnPathPlatform("TEXT", Path(), 0f, 0f, spec(), context) },
    )
    assertEquals(
      "a zero-length path has nowhere to place a glyph",
      null,
      paintedBounds {
        drawTextOnPathPlatform("TEXT", Path().apply { moveTo(50f, 50f) }, 0f, 0f, spec(), context)
      },
    )
    assertEquals(
      "empty text at an origin should paint nothing",
      null,
      paintedBounds { drawTextAtOriginPlatform("", 40f, 100f, spec(), context) },
    )
    // And measuring nothing is zero-width rather than an error.
    assertEquals(0f, measureTextWidth("", spec(), context), 0f)
  }

  private companion object {
    const val OPAQUE_BLACK = 0xFF000000.toInt()

    /** Why skiko's natives failed to load, or null if they loaded. See [requireSkikoNatives]. */
    var skikoLoadFailure: String? = null

    /**
     * Whether Skia is callable at all, decided once by touching a class whose initializer loads the
     * native library. [org.jetbrains.skia.FontMgr] is the one every test here reaches first.
     */
    val SKIKO_LOADED: Boolean =
      try {
        org.jetbrains.skia.FontMgr.default.familiesCount
        true
      } catch (t: Throwable) {
        // An initializer failure surfaces as ExceptionInInitializerError / NoClassDefFoundError /
        // UnsatisfiedLinkError depending on which test touches Skia first, so this catches
        // Throwable
        // on purpose rather than guessing which.
        skikoLoadFailure = "${t::class.java.simpleName}: ${t.message}"
        false
      }
  }
}
