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

package ee.schimke.composeai.rcembedded

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.view.View.MeasureSpec
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Minimal standard-Compose reduction of the missing tinted vector in the embedded render lane.
 *
 * This deliberately contains no Remote Compose code. It demonstrates that Robolectric renders a
 * SrcIn-tinted path correctly when its source is opaque, and correctly renders nothing when its
 * source is transparent. The latter was the input supplied by the embedded player's old default
 * paint (`ComposeLocalPaint.color == 0`); framework `android.graphics.Paint`, used by the View
 * player, defaults to opaque black instead.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "xhdpi")
class ComposePathColorFilterRobolectricReproTest {

  @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun srcInTintRequiresAnOpaqueSourceColor() {
    composeRule.setContent {
      CompositionLocalProvider(LocalDensity provides Density(1f)) {
        Canvas(Modifier.size(WIDTH.dp, HEIGHT.dp)) {
          drawRect(BACKGROUND)

          // Control: the same path painted directly with the desired color.
          translate(left = LEFT_X, top = TOP_Y) { drawPath(star(), color = TINT) }

          // Control: SrcIn tint works under Robolectric when there is opaque source content.
          translate(left = RIGHT_X, top = TOP_Y) {
            drawPath(
              path = star(),
              color = Color.Black,
              colorFilter = ColorFilter.tint(TINT, BlendMode.SrcIn),
            )
          }

          // Exact reduction of the former embedded-player input. ComposeLocalPaint started at
          // ARGB 0, making the source fully transparent; SrcIn therefore had no pixels to tint.
          translate(left = TRANSPARENT_X, top = TOP_Y) {
            drawPath(
              path = star(),
              color = Color.Transparent,
              colorFilter = ColorFilter.tint(TINT, BlendMode.SrcIn),
            )
          }
        }
      }
    }
    composeRule.waitForIdle()

    val bitmap = drawActivityToBitmap(WIDTH, HEIGHT)
    val directPixels = bitmap.countPixelsNear(TINT, 0, WIDTH / 3)
    val tintedPixels = bitmap.countPixelsNear(TINT, WIDTH / 3, 2 * WIDTH / 3)
    val transparentPixels = bitmap.countPixelsNear(TINT, 2 * WIDTH / 3, WIDTH)

    assertTrue("control path did not render: direct=$directPixels", directPixels > 100)
    assertTrue(
      "opaque ColorFilter.tint(..., SrcIn) path did not render: tinted=$tintedPixels",
      tintedPixels > 100,
    )
    assertEquals(
      "transparent SrcIn source unexpectedly produced tinted pixels",
      0,
      transparentPixels,
    )

    // This is the semantic mismatch between the two RC renderers, independent of Compose drawing.
    assertEquals(0xff000000.toInt(), android.graphics.Paint().color)
  }

  private fun drawActivityToBitmap(width: Int, height: Int): Bitmap {
    val root = composeRule.activity.findViewById<ViewGroup>(android.R.id.content)
    root.measure(
      MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
      MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
    )
    root.layout(0, 0, width, height)
    return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
      root.draw(AndroidCanvas(it))
    }
  }

  private fun Bitmap.countPixelsNear(color: Color, minX: Int, maxX: Int): Int {
    val expected = color.toArgb()
    return (0 until height).sumOf { y ->
      (minX until maxX).count { x ->
        val actual = getPixel(x, y)
        channelDistance(actual, expected) <= 12
      }
    }
  }

  private fun channelDistance(a: Int, b: Int): Int =
    kotlin.math.abs(android.graphics.Color.red(a) - android.graphics.Color.red(b)) +
      kotlin.math.abs(android.graphics.Color.green(a) - android.graphics.Color.green(b)) +
      kotlin.math.abs(android.graphics.Color.blue(a) - android.graphics.Color.blue(b))

  private fun star() =
    Path().apply {
      moveTo(12f, 2f)
      lineTo(15.09f, 8.26f)
      lineTo(22f, 9.27f)
      lineTo(17f, 14.14f)
      lineTo(18.18f, 21.02f)
      lineTo(12f, 17.77f)
      lineTo(5.82f, 21.02f)
      lineTo(7f, 14.14f)
      lineTo(2f, 9.27f)
      lineTo(8.91f, 8.26f)
      close()
    }

  private companion object {
    const val WIDTH = 192
    const val HEIGHT = 48
    const val LEFT_X = 20f
    const val RIGHT_X = 84f
    const val TRANSPARENT_X = 148f
    const val TOP_Y = 12f
    val BACKGROUND = Color(0xff332e3c)
    val TINT = Color(0xffcac4d0)
  }
}
