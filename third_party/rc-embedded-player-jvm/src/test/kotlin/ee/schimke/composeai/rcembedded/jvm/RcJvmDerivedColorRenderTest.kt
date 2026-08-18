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

import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Image
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test

/**
 * A colour *derived* inside a component's draw-content stream reaches the paint.
 *
 * The fixture is `remote-m3`'s `Button/Icon-Large` as the catalog captures it: a `RemoteIconButton`
 * whose container is painted from a `ColorExpression` — `tween(surfaceContainer, primaryContainer,
 * <toggle>)` — inside the `Modifier.drawWithContent` canvas block hanging off the button's box,
 * with the star drawn over it from a plain `ColorConstant` tint.
 *
 * That layout is what made the bug (compose-ai-tools#4165) so easy to miss: the star drew, so the
 * document looked *nearly* right, while the container behind it was fully transparent. The
 * expression publishes its colour under an out id that a later `PaintBundle.COLOR_ID` in the same
 * stream reads, but nothing ever ran it — `GraphContext`'s computed-op index walks the operation
 * *tree*, and draw-content ops hang off a component as a field rather than as a child, so
 * `getColor` fell through to a store no one had written and returned 0.
 *
 * Asserted: the container colour covers a real area of the render. Both halves matter — before the
 * fix the colour was absent entirely (so presence has teeth), and requiring an area rather than a
 * single pixel keeps a stray antialiased edge from passing for a filled circle.
 */
class RcJvmDerivedColorRenderTest {

  /** Same loud-skip contract as [RcJvmRendererTest]: no skiko natives, no raster at all. */
  @Before
  fun requireSkikoNatives() {
    if (!SKIKO_LOADED) {
      System.err.println(
        "RcJvmDerivedColorRenderTest skipped entirely: skiko's native library did not load, so " +
          "the derived-colour draw path was never exercised. Cause: $skikoLoadFailure"
      )
    }
    Assume.assumeTrue("skiko natives unavailable: $skikoLoadFailure", SKIKO_LOADED)
  }

  @Test
  fun paintsTheContainerColourAColorExpressionPublishesInTheDrawStream() {
    val bytes =
      checkNotNull(javaClass.getResourceAsStream("/$FIXTURE")) {
          "missing committed fixture $FIXTURE — is the Android module's test-resources srcDir " +
            "shared?"
        }
        .use { it.readBytes() }

    val png = renderRemoteDocumentToPng(bytes, SIZE, SIZE, DENSITY)

    // The button is untoggled, so the tween sits at its start colour: `surfaceContainer`, the
    // `ColorConstant` the expression interpolates *from*. It is the expression's output that is
    // painted either way — the id the paint reads is the expression's, not the constant's.
    val painted = countPixels(png, SURFACE_CONTAINER)
    assertTrue(
      "the container colour ${SURFACE_CONTAINER.toUInt().toString(16)} covers only $painted px — " +
        "the ColorExpression driving it never reached the paint",
      painted > 1_000,
    )
  }

  /** Pixels exactly equal to [argb] in the decoded PNG. */
  private fun countPixels(png: ByteArray, argb: Int): Int {
    val image = Image.makeFromEncoded(png)
    val bitmap = Bitmap()
    bitmap.allocN32Pixels(image.width, image.height)
    check(image.readPixels(bitmap)) { "could not read pixels back from the rendered image" }
    var count = 0
    for (y in 0 until bitmap.height) {
      for (x in 0 until bitmap.width) {
        if (bitmap.getColor(x, y) == argb) count++
      }
    }
    return count
  }

  private companion object {
    const val FIXTURE = "rc-fixtures/LargeRemoteIconButton-400x400.rc"

    /** The capture is 200dp square at dpi 320 — 400px at the xhdpi density the catalog bakes. */
    const val SIZE = 400
    const val DENSITY = 2f

    /** `WearM3.surfaceContainer`, the untoggled container colour, as the document declares it. */
    const val SURFACE_CONTAINER = 0xff332e3c.toInt()

    var skikoLoadFailure: String? = null

    /** Whether Skia is callable at all — decided once by touching a class that loads the native. */
    val SKIKO_LOADED: Boolean =
      try {
        org.jetbrains.skia.FontMgr.default.familiesCount
        true
      } catch (t: Throwable) {
        skikoLoadFailure = "${t::class.java.simpleName}: ${t.message}"
        false
      }
  }
}
