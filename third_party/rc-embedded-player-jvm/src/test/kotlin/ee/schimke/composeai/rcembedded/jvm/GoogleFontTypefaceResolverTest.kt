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

import ee.schimke.composeai.fonts.google.GoogleFontKey
import ee.schimke.composeai.fonts.google.GoogleFontSource
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assume
import org.junit.Test

/**
 * The decision half of the jvm player's downloadable-font resolver: which names opt in, what key
 * they resolve to, and that everything it cannot serve degrades to null (the caller's local-match →
 * default-face fallback) rather than throwing.
 *
 * Deliberately network-free — the [GoogleFontSource] is faked, which is the whole reason the
 * resolver takes one. That the *real* source downloads is `GoogleFontCache`'s own contract, tested
 * in `:data-fonts-google`.
 */
class GoogleFontTypefaceResolverTest {

  private class FakeFonts(private val file: File?) : GoogleFontSource {
    val requests = mutableListOf<GoogleFontKey>()

    override fun load(key: GoogleFontKey): File? {
      requests += key
      return file
    }
  }

  @Test
  fun `only a google-prefixed family opts in`() {
    assertNull(GoogleFontTypefaceResolver.googleFontKey(null, 400, false))
    assertNull(GoogleFontTypefaceResolver.googleFontKey("Orbitron", 400, false))
    assertNull(GoogleFontTypefaceResolver.googleFontKey("device:Roboto", 400, false))
    assertNull(GoogleFontTypefaceResolver.googleFontKey("sans-serif", 400, false))
    // A bare prefix names nothing to fetch.
    assertNull(GoogleFontTypefaceResolver.googleFontKey("google:", 400, false))
    assertNull(GoogleFontTypefaceResolver.googleFontKey("google:   ", 400, false))
  }

  @Test
  fun `the prefix is stripped and the request carries weight and slant`() {
    assertEquals(
      GoogleFontKey("Orbitron", 500, true),
      GoogleFontTypefaceResolver.googleFontKey("google:Orbitron", 500, true),
    )
    // Multi-word families reach the cache spaced, the spelling Google Fonts is keyed on.
    assertEquals(
      GoogleFontKey("Space Grotesk", 400, false),
      GoogleFontTypefaceResolver.googleFontKey("  google:Space Grotesk  ", 400, false),
    )
    // Documents are written by hand as often as by a builder, so the prefix match is lenient.
    assertEquals(
      GoogleFontKey("Lobster Two", 700, false),
      GoogleFontTypefaceResolver.googleFontKey("GOOGLE:Lobster Two", 700, false),
    )
  }

  @Test
  fun `a render with no font cache resolves nothing and asks nothing`() {
    val resolver = GoogleFontTypefaceResolver(fonts = null)

    assertNull(resolver.composeFontFamily("google:Orbitron", 400, false))
    assertNull(resolver.skiaTypeface("google:Orbitron", 400, false))
  }

  @Test
  fun `a non-google family never reaches the font source`() {
    val fonts = FakeFonts(file = null)
    val resolver = GoogleFontTypefaceResolver(fonts)

    assertNull(resolver.composeFontFamily("Orbitron", 400, false))
    assertNull(resolver.composeFontFamily("device:Roboto", 400, false))
    assertNull(resolver.skiaTypeface("monospace", 400, false))

    assertEquals(emptyList<GoogleFontKey>(), fonts.requests)
  }

  @Test
  fun `an unresolvable family is asked for once, not once per op`() {
    // The seams call the resolver per paint change, so a family the source can't serve — offline,
    // a name Google has no family for — must not re-attempt the (network-backed) fetch on every
    // text op in the document.
    val fonts = FakeFonts(file = null)
    val resolver = GoogleFontTypefaceResolver(fonts)

    repeat(5) { assertNull(resolver.composeFontFamily("google:Nonesuch", 400, false)) }

    assertEquals(listOf(GoogleFontKey("Nonesuch", 400, false)), fonts.requests)
  }

  @Test
  fun `a resolved family is fetched once and reused`() {
    val file = File.createTempFile("resolver-face-", ".ttf").apply { deleteOnExit() }
    val fonts = FakeFonts(file)
    val resolver = GoogleFontTypefaceResolver(fonts)

    val first = resolver.composeFontFamily("google:Orbitron", 400, false)
    val second = resolver.composeFontFamily("google:Orbitron", 400, false)

    assertNotNull("a served file must produce a FontFamily", first)
    assertEquals("the cached family is reused verbatim", first, second)
    assertEquals(listOf(GoogleFontKey("Orbitron", 400, false)), fonts.requests)
  }

  @Test
  fun `weight and slant are separate requests`() {
    val file = File.createTempFile("resolver-face-", ".ttf").apply { deleteOnExit() }
    val fonts = FakeFonts(file)
    val resolver = GoogleFontTypefaceResolver(fonts)

    resolver.composeFontFamily("google:Orbitron", 400, false)
    resolver.composeFontFamily("google:Orbitron", 700, false)
    resolver.composeFontFamily("google:Orbitron", 400, true)

    assertEquals(
      listOf(
        GoogleFontKey("Orbitron", 400, false),
        GoogleFontKey("Orbitron", 700, false),
        GoogleFontKey("Orbitron", 400, true),
      ),
      fonts.requests,
    )
  }

  @Test
  fun `a file skia cannot parse falls back rather than throwing`() {
    Assume.assumeTrue("skiko natives unavailable", skikoLoaded)
    val notAFont =
      File.createTempFile("resolver-garbage-", ".ttf").apply {
        writeBytes(byteArrayOf(1, 2, 3, 4))
        deleteOnExit()
      }
    val resolver = GoogleFontTypefaceResolver(FakeFonts(notAFont))

    assertNull(resolver.skiaTypeface("google:Orbitron", 400, false))
  }

  private companion object {
    /** Same gate the other skiko-touching tests here use: skip loudly rather than fail. */
    val skikoLoaded: Boolean =
      try {
        org.jetbrains.skia.FontMgr.default.familiesCount
        true
      } catch (t: Throwable) {
        System.err.println(
          "GoogleFontTypefaceResolverTest: skiko natives unavailable, skipping the skia case " +
            "(${t::class.java.simpleName}: ${t.message})"
        )
        false
      }
  }
}
