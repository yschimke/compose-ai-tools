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

import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import ee.schimke.composeai.fonts.google.GoogleFontKey
import ee.schimke.composeai.fonts.google.GoogleFontSource
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The seam that instances a `google:` family at a document's font-variation axes.
 *
 * Hermetic: the [GoogleFontSource] is faked over the repo's vendored Roboto Flex — a genuine
 * variable file (1.7 MB, `fvar` present), which is the whole point, since the CSS API's answer for
 * the same family is a baked static instance with no axes left to apply.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "xhdpi")
class GoogleVariableFontFamiliesTest {

  private class FakeSource(private val file: File?) : GoogleFontSource {
    var variableCalls = 0

    override fun load(key: GoogleFontKey): File? =
      error("the static path must not be reached for an axis request")

    override fun loadVariable(family: String, italic: Boolean): File? {
      variableCalls++
      return file
    }
  }

  @Test
  fun `two axis sets on one file are two different families`() {
    val source = FakeSource(robotoFlex())
    val resolver = GoogleVariableFontFamilies(source)

    val narrow = resolver.composeFontFamily(GOOGLE_ROBOTO_FLEX, WEIGHT, UPRIGHT, listOf(WDTH to 25f))
    val wide = resolver.composeFontFamily(GOOGLE_ROBOTO_FLEX, WEIGHT, UPRIGHT, listOf(WDTH to 151f))

    assertNotNull(narrow)
    assertNotNull(wide)
    // The failure this guards: a cache keyed on the family alone hands the second line the first
    // line's instance, and a `wdth` ramp draws three identical rows.
    assertNotEquals(narrow, wide)
    // One file serves both instances — the source is asked once, not once per axis set.
    assertEquals(1, source.variableCalls)
  }

  @Test
  fun `a repeated request is served from the cache`() {
    val source = FakeSource(robotoFlex())
    val resolver = GoogleVariableFontFamilies(source)
    val axes = listOf(WGHT to 700f)

    val first = resolver.composeFontFamily(GOOGLE_ROBOTO_FLEX, WEIGHT, UPRIGHT, axes)
    val second = resolver.composeFontFamily(GOOGLE_ROBOTO_FLEX, WEIGHT, UPRIGHT, axes)

    assertNotNull(first)
    assertSame(first, second)
  }

  @Test
  fun `an unvaried request is left to the downloadable-font path`() {
    val source = FakeSource(robotoFlex())
    val resolver = GoogleVariableFontFamilies(source)

    // Null, not a family: without axes the existing `Font(GoogleFont(...))` path is the right one,
    // and it resolves a smaller static instance rather than downloading 1.7 MB.
    assertNull(resolver.composeFontFamily(GOOGLE_ROBOTO_FLEX, WEIGHT, UPRIGHT, emptyList()))
    assertEquals(0, source.variableCalls)
  }

  @Test
  fun `only a google-namespaced family opts in`() {
    val source = FakeSource(robotoFlex())
    val resolver = GoogleVariableFontFamilies(source)
    val axes = listOf(WGHT to 700f)

    assertNull(resolver.composeFontFamily("Roboto Flex", WEIGHT, UPRIGHT, axes))
    assertNull(resolver.composeFontFamily("device:roboto-flex", WEIGHT, UPRIGHT, axes))
    assertNull(resolver.composeFontFamily("google:", WEIGHT, UPRIGHT, axes))
    assertNull(resolver.composeFontFamily(null, WEIGHT, UPRIGHT, axes))
    assertEquals(0, source.variableCalls)
  }

  @Test
  fun `a family with no variable file is remembered as a miss`() {
    // Lobster Two is the real case: catalogued and resolvable at a weight, no variable file
    // anywhere. Asking again per text op would re-probe the network for every line.
    val source = FakeSource(null)
    val resolver = GoogleVariableFontFamilies(source)

    assertNull(resolver.composeFontFamily("google:Lobster Two", WEIGHT, UPRIGHT, listOf(WGHT to 700f)))
    assertNull(resolver.composeFontFamily("google:Lobster Two", WEIGHT, UPRIGHT, listOf(WGHT to 400f)))
    assertEquals(1, source.variableCalls)
  }

  @Test
  fun `no configured font source resolves nothing`() {
    val resolver = GoogleVariableFontFamilies(null)
    assertNull(
      resolver.composeFontFamily(GOOGLE_ROBOTO_FLEX, WEIGHT, UPRIGHT, listOf(WGHT to 700f))
    )
  }

  private fun robotoFlex(): File {
    val file = File(VENDORED_ROBOTO_FLEX)
    assumeTrue("vendored Roboto Flex not found at $VENDORED_ROBOTO_FLEX", file.isFile)
    return file
  }

  private companion object {
    const val GOOGLE_ROBOTO_FLEX = "google:Roboto Flex"
    const val WGHT = "wght"
    const val WDTH = "wdth"
    val WEIGHT = FontWeight(400)
    val UPRIGHT = FontStyle.Normal

    /**
     * The catalog's own variable face, vendored for the browser lane's font manifest. Reused here
     * rather than downloaded so the test is hermetic — and it is the same file
     * `loadVariable("Roboto Flex")` fetches, byte for byte.
     */
    const val VENDORED_ROBOTO_FLEX =
      "../../samples/cmp-wasm-catalog/src/wasmJsMain/resources/fonts/RobotoFlex.ttf"
  }
}
