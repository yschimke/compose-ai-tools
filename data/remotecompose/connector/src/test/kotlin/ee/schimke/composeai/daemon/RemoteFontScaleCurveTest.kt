package ee.schimke.composeai.daemon

import androidx.compose.remote.creation.compose.capture.RemoteDensity
import androidx.compose.remote.creation.compose.state.rf
import androidx.compose.remote.creation.compose.state.rsp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `sp -> px` at capture is **non-linear**, and this pins the curve that makes it so.
 *
 * ## Why this is load-bearing for `composeai.render.rcDensity=host`
 *
 * Android does not scale text by multiplying: `FontScaleConverter` damps large text hard, so a
 * headline barely moves while body text tracks the scale exactly. `RemoteTextUnit.toPx` reproduces
 * that through `RemoteFontScaleConverter.NonLinear`, and — this is the part that matters — it does
 * so on **both** of its branches:
 *
 * * every input constant ([RemoteCaptureDensity.FIXED]) folds the curve to a literal;
 * * any input a variable ([RemoteCaptureDensity.HOST]) emits the same converter as a
 *   `RemoteFloatExpression` over the player's own `FONT_SIZE`, evaluated at paint time.
 *
 * So a `Host` document is not "linear scaling deferred to the player" — it carries Android's curve
 * with it. That is what makes the opt-in safe, and it is not obvious from the property name.
 *
 * ## What it means for a player
 *
 * The pixels a `Host` document resolves to **already carry the host's font scale**, damped. A
 * player must draw them as they are; one that multiplies by `fontScale` a second time turns the
 * 44sp row below from 50.4px into 100.8px — a 2x error on exactly the text the curve exists to
 * protect. `rc-players`' `RcFontScaleRenderTest` pins a player doing that on one of its two text
 * paths.
 *
 * Measured against the real converter rather than transcribed from AOSP's tables, so a version bump
 * that re-cuts the curve fails here instead of silently changing every headline.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RemoteFontScaleCurveTest {

  /**
   * Body text tracks the scale; headlines do not move at all until well past it.
   *
   * The two ends are the whole point: 12sp doubles exactly at 2.0, while 44sp is untouched through
   * 1.5 and grows 15% at 2.0. Anything that "fixes" font scaling by multiplying would make both
   * rows 2.0x and be wrong on one of them.
   */
  @Test
  fun theCaptureCurveDampsLargeTextAndNotSmall() {
    for ((size, expected) in EXPECTED) {
      for ((fontScale, px) in expected) {
        assertEquals(
          "${size}sp at fontScale $fontScale",
          px,
          toPx(size, fontScale),
          TOLERANCE,
        )
      }
    }
  }

  /** A literal font size is unchanged at `fontScale = 1`, on every size the curve knots at. */
  @Test
  fun theCurveIsIdentityAtScaleOne() {
    for (size in EXPECTED.keys) {
      assertEquals("${size}sp at fontScale 1", size.toFloat(), toPx(size, 1f), TOLERANCE)
    }
  }

  /** Density is a separate multiplier, so the curve is the same shape at any of them. */
  @Test
  fun densityMultipliesTheCurveRatherThanBendingIt() {
    for (size in EXPECTED.keys) {
      val atOne = toPx(size, FONT_SCALE_2X, density = 1f)
      val atTwo = toPx(size, FONT_SCALE_2X, density = 2f)
      assertEquals("${size}sp at density 2", atOne * 2f, atTwo, TOLERANCE)
    }
  }

  /**
   * The `Host` branch defers rather than folding — which is the branch `rcDensity=host` rides on.
   *
   * Every other case here supplies two literals, so `RemoteTextUnit.toPx` takes the
   * constant-folding path and the assertions above never reach the variable one. That is the branch
   * this file's header calls load-bearing: a regression that quietly folded `RemoteDensity.Host` to
   * a literal would bake the capture host's own font scale into the document and ship it to every
   * player, and every test above would still pass.
   *
   * What is checked is the difference in kind — the fixed branch answers a constant and the host
   * branch answers an expression with no constant value, on the same sizes the curve knots at.
   * Evaluating that expression needs a `RemoteContext` with the player's `FONT_SIZE` bound, which
   * is a player's job rather than a capture's; `rc-players`' `RcFontScaleRenderTest` is where the
   * resolved pixels are pinned, and the header says so.
   */
  @Test
  fun theHostBranchEmitsAnExpressionInsteadOfFoldingTheCurve() {
    for (size in EXPECTED.keys) {
      assertNull(
        "${size}sp against RemoteDensity.Host should stay an expression",
        size.rsp.toPx(RemoteDensity.Host).constantValueOrNull,
      )
      assertNotNull(
        "${size}sp against fixed inputs should fold",
        size.rsp.toPx(RemoteDensity(1f.rf, 1f.rf)).constantValueOrNull,
      )
    }
  }

  /** The capture's own conversion, with both density and font scale constant. */
  private fun toPx(sp: Int, fontScale: Float, density: Float = 1f): Float =
    checkNotNull(sp.rsp.toPx(RemoteDensity(density.rf, fontScale.rf)).constantValueOrNull) {
      "constant inputs should fold to a constant"
    }

  private companion object {
    const val TOLERANCE = 0.05f
    const val FONT_SCALE_2X = 2f

    /** Measured off `RemoteFontScaleConverter.NonLinear` at density 1.0. */
    val EXPECTED =
      linkedMapOf(
        12 to mapOf(1.15f to 13.8f, 1.3f to 15.6f, 1.5f to 18f, 2f to 24f),
        16 to mapOf(1.15f to 18.1f, 1.3f to 20.2f, 1.5f to 23f, 2f to 28f),
        24 to mapOf(1.15f to 25.2f, 1.3f to 26.4f, 1.5f to 28f, 2f to 36f),
        30 to mapOf(1.15f to 30f, 1.3f to 30f, 1.5f to 30f, 2f to 38f),
        44 to mapOf(1.15f to 44f, 1.3f to 44f, 1.5f to 44f, 2f to 50.4f),
        57 to mapOf(1.15f to 57f, 1.3f to 57f, 1.5f to 57f, 2f to 61.914f),
      )
  }
}
