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

import androidx.compose.ui.text.font.FontVariation
import ee.schimke.composeai.fonts.google.GoogleFontKey
import ee.schimke.composeai.fonts.google.GoogleFontSource
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * A variable font's axes reach the family the jvm player draws with.
 *
 * The resolver is asked for the same face twice at different `wdth` instances: those are two
 * different faces of one file, so they must be two different `FontFamily` objects. Handing the
 * second request the first's family — which is what a cache keyed on `(family, weight, italic)`
 * alone does — is exactly how a `wght` ramp ends up drawing every line at the first weight it saw.
 *
 * Hermetic: the [GoogleFontSource] is faked over the repo's vendored Roboto Flex, so nothing is
 * fetched. (`RcFontAxisRenderTest` in `:rc-player-compose` measures the *rendered* ink for the same
 * claim; this one pins the resolver's own contract.)
 */
class RcJvmFontAxisTest {

  private class FakeFonts(private val file: File) : GoogleFontSource {
    var loads = 0
    val requested = mutableListOf<GoogleFontKey>()

    override fun load(key: GoogleFontKey): File? {
      loads++
      requested += key
      return file
    }
  }

  @Test
  fun `each axis instance is its own family, and the file is fetched once`() {
    val face = File(VARIABLE_FACE_PATH)
    assumeTrue("vendored Roboto Flex not found at $VARIABLE_FACE_PATH", face.isFile)
    val fonts = FakeFonts(face)
    val resolver = GoogleFontTypefaceResolver(fonts)

    val narrow = resolver.composeFontFamily("google:Roboto Flex", 400, false, width(25f))
    val wide = resolver.composeFontFamily("google:Roboto Flex", 400, false, width(151f))
    val narrowAgain = resolver.composeFontFamily("google:Roboto Flex", 400, false, width(25f))

    assertNotNull("a served file must produce a family", narrow)
    assertNotEquals("wdth 25 and wdth 151 are different faces of one file", narrow, wide)
    assertSame("a repeated axis instance is served from the cache", narrow, narrowAgain)
    // One file, however many instances: the (possibly network-backed) source is asked once.
    assertNotNull(wide)
    assert(fonts.loads == 1) {
      "expected one fetch for three instance requests, got ${fonts.loads}"
    }
  }

  @Test
  fun `no axes resolves to the plain family`() {
    val face = File(VARIABLE_FACE_PATH)
    assumeTrue("vendored Roboto Flex not found at $VARIABLE_FACE_PATH", face.isFile)
    val resolver = GoogleFontTypefaceResolver(FakeFonts(face))

    val plain = resolver.composeFontFamily("google:Roboto Flex", 400, false)
    val alsoPlain =
      resolver.composeFontFamily("google:Roboto Flex", 400, false, FontVariation.Settings())

    assertNotNull(plain)
    assertSame("an empty axis set is the same request as none", plain, alsoPlain)
    assertNotEquals(plain, resolver.composeFontFamily("google:Roboto Flex", 400, false, width(25f)))
  }

  @Test
  fun `a wght axis selects which instance is fetched`() {
    // Google Fonts serves a named family as a static instance *per weight*, so an axis request has
    // to reach the fetch as well as the face: asking for the 400 file and then applying `wght 700`
    // to it varies nothing, because that file has no axes. The document's own `fontWeight` stays at
    // its default while the axis carries the intent, which is why the axis has to win here.
    val face = File(VARIABLE_FACE_PATH)
    assumeTrue("vendored Roboto Flex not found at $VARIABLE_FACE_PATH", face.isFile)
    val fonts = FakeFonts(face)
    val resolver = GoogleFontTypefaceResolver(fonts)

    resolver.composeFontFamily(
      "google:Orbitron",
      weight = 400,
      italic = false,
      settings = FontVariation.Settings(FontVariation.weight(700)),
    )

    assertEquals(listOf(GoogleFontKey("Orbitron", 700, false)), fonts.requested)
  }

  private fun width(value: Float) = FontVariation.Settings(FontVariation.width(value))

  private companion object {
    /**
     * The wasm catalog's vendored variable face, read from the repo rather than copied — the same
     * file the browser lane serves, so every lane's axis behaviour is measured against one font.
     * Relative to this module's directory, which is a Gradle `Test` task's working directory.
     */
    const val VARIABLE_FACE_PATH =
      "../../samples/cmp-wasm-catalog/src/wasmJsMain/resources/fonts/RobotoFlex.ttf"
  }
}
