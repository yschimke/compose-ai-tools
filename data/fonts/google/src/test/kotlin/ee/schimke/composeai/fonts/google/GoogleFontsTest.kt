package ee.schimke.composeai.fonts.google

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The Google Fonts resolution surface: CSS-API query shapes, TTF extraction, and cache behaviour.
 *
 * These moved here with the code when it was extracted from `:renderers-android`, because two lanes
 * now resolve through it — the Robolectric downloadable-font shadow and the Remote Compose typeface
 * resolver — and a regression in the query shape or the cache would silently give one of them the
 * wrong typeface.
 */
class GoogleFontsTest {

  @get:Rule val tempDir = TemporaryFolder()

  @Test
  fun `slugify lowercases and replaces non-alphanumerics with hyphens`() {
    assertEquals("roboto-mono", GoogleFontKey.slugify("Roboto Mono"))
    assertEquals("ibm-plex-sans", GoogleFontKey.slugify("IBM Plex Sans"))
    assertEquals("noto-serif", GoogleFontKey.slugify("  Noto--Serif  "))
    assertEquals("font", GoogleFontKey.slugify("!!!"))
  }

  @Test
  fun `fileName encodes weight and italic axis`() {
    assertEquals(
      "roboto-mono-400.ttf",
      GoogleFontKey("Roboto Mono", 400, italic = false).fileName(),
    )
    assertEquals(
      "roboto-mono-700-italic.ttf",
      GoogleFontKey("Roboto Mono", 700, italic = true).fileName(),
    )
  }

  @Test
  fun `buildCssUrl encodes family name with spaces and weight axis`() {
    val url = buildCssUrl(GoogleFontKey("Roboto Mono", 500, italic = false))
    assertEquals(
      "https://fonts.googleapis.com/css2?family=Roboto%20Mono:wght@500&display=swap",
      url,
    )
  }

  @Test
  fun `buildCssUrl encodes italic axis`() {
    val url = buildCssUrl(GoogleFontKey("Inter", 400, italic = true))
    assertEquals("https://fonts.googleapis.com/css2?family=Inter:ital,wght@1,400&display=swap", url)
  }

  @Test
  fun `extractFirstTruetypeUrl picks the truetype-formatted entry only`() {
    val css =
      """
      @font-face {
        font-family: 'Inter';
        src: url(https://fonts.gstatic.com/foo.woff2) format('woff2');
      }
      @font-face {
        font-family: 'Inter';
        src: url(https://fonts.gstatic.com/foo.ttf) format('truetype');
      }
      """
        .trimIndent()
    assertEquals("https://fonts.gstatic.com/foo.ttf", extractFirstTruetypeUrl(css))
  }

  @Test
  fun `extractFirstTruetypeUrl returns null when no truetype source is present`() {
    val css =
      """
      @font-face {
        src: url(https://fonts.gstatic.com/foo.woff2) format('woff2');
      }
      """
        .trimIndent()
    assertNull(extractFirstTruetypeUrl(css))
  }

  @Test
  fun `buildRangeCssUrl spans the conventional 100-1000 variable-axis range`() {
    val url = buildRangeCssUrl(GoogleFontKey("Roboto Flex", 100, italic = false))
    assertEquals(
      "https://fonts.googleapis.com/css2?family=Roboto%20Flex:wght@100..1000&display=swap",
      url,
    )
  }

  @Test
  fun `pickClosestTruetypeUrl returns the single URL for a variable-font response`() {
    // Range query on a purely-variable family like Roboto Flex returns
    // one @font-face block whose font-weight says "400" but whose TTF is
    // the axis-range-covering variable font.
    val css =
      """
      @font-face {
        font-family: 'Roboto Flex';
        font-weight: 400;
        src: url(https://fonts.gstatic.com/flex.ttf) format('truetype');
      }
      """
        .trimIndent()
    assertEquals(
      "https://fonts.gstatic.com/flex.ttf",
      pickClosestTruetypeUrl(css, requestedWeight = 100),
    )
  }

  @Test
  fun `pickClosestTruetypeUrl picks the nearest declared weight from a static family`() {
    val css =
      """
      @font-face { font-family: 'X'; font-weight: 300;
        src: url(https://fonts.gstatic.com/x-300.ttf) format('truetype'); }
      @font-face { font-family: 'X'; font-weight: 500;
        src: url(https://fonts.gstatic.com/x-500.ttf) format('truetype'); }
      @font-face { font-family: 'X'; font-weight: 700;
        src: url(https://fonts.gstatic.com/x-700.ttf) format('truetype'); }
      """
        .trimIndent()
    // 550 is strictly closer to 500 than 300 or 700.
    assertEquals(
      "https://fonts.gstatic.com/x-500.ttf",
      pickClosestTruetypeUrl(css, requestedWeight = 550),
    )
    // 900 pulls to 700, the heaviest declared static sub-font.
    assertEquals(
      "https://fonts.gstatic.com/x-700.ttf",
      pickClosestTruetypeUrl(css, requestedWeight = 900),
    )
  }

  @Test
  fun `GoogleFontCache returns cached file when it already exists`() {
    val dir = tempDir.newFolder("fonts")
    val key = GoogleFontKey("Roboto Mono", 400, italic = false)
    val existing = File(dir, key.fileName())
    existing.writeBytes(FAKE_TTF_BYTES)
    var downloaderCalls = 0
    val cache =
      GoogleFontCache(
        dir,
        offline = false,
        downloader = { _, _ ->
          downloaderCalls++
          true
        },
      )
    val file = cache.load(key)
    assertEquals(existing, file)
    assertEquals(0, downloaderCalls)
  }

  @Test
  fun `GoogleFontCache invokes downloader on miss and caches the result`() {
    val dir = tempDir.newFolder("fonts")
    val key = GoogleFontKey("Inter", 400, italic = false)
    var downloaderCalls = 0
    val cache =
      GoogleFontCache(
        dir,
        offline = false,
        downloader = { _, dest ->
          downloaderCalls++
          dest.writeBytes(FAKE_TTF_BYTES)
          true
        },
      )
    val file1 = cache.load(key)
    assertNotNull(file1)
    assertTrue(FAKE_TTF_BYTES.contentEquals(file1!!.readBytes()))
    assertEquals(1, downloaderCalls)

    // Second call hits cache — downloader not invoked again.
    val file2 = cache.load(key)
    assertEquals(file1, file2)
    assertEquals(1, downloaderCalls)
  }

  @Test
  fun `GoogleFontCache returns null on miss when offline is true`() {
    val dir = tempDir.newFolder("fonts")
    val key = GoogleFontKey("Inter", 400, italic = false)
    val cache =
      GoogleFontCache(
        dir,
        offline = true,
        downloader = { _, _ -> error("offline mode must not invoke the downloader") },
      )
    assertNull(cache.load(key))
  }

  @Test
  fun `GoogleFontCache returns null and leaves no file when downloader fails`() {
    val dir = tempDir.newFolder("fonts")
    val key = GoogleFontKey("Ghost", 400, italic = false)
    val cache = GoogleFontCache(dir, offline = false, downloader = { _, _ -> false })
    assertNull(cache.load(key))
    assertFalse(File(dir, key.fileName()).exists())
  }

  @Test
  fun `googleFontsRepoSlug strips separators rather than hyphenating them`() {
    // Not the same rule as `GoogleFontKey.slugify` (which produces `roboto-flex` for readable cache
    // filenames) — the repository's directories carry no separators at all, and a hyphen here is a
    // 404 rather than a wrong file.
    assertEquals("robotoflex", googleFontsRepoSlug("Roboto Flex"))
    assertEquals("jetbrainsmono", googleFontsRepoSlug("JetBrains Mono"))
    assertEquals("robotoflex", GoogleFontKey.slugify("Roboto Flex").replace("-", ""))
  }

  @Test
  fun `googleFontsRepoFileUrl percent-encodes the axis brackets`() {
    assertEquals(
      "https://raw.githubusercontent.com/google/fonts/main/ofl/robotoflex/" +
        "RobotoFlex%5Bwdth,wght%5D.ttf",
      googleFontsRepoFileUrl("ofl", "robotoflex", "RobotoFlex[wdth,wght].ttf"),
    )
  }

  @Test
  fun `pickVariableFileName takes the bracketed filename and honours the italic split`() {
    val metadata =
      """
      fonts {
        filename: "Family-Regular.ttf"
      }
      fonts {
        filename: "Family[wdth,wght].ttf"
      }
      fonts {
        filename: "Family-Italic[wdth,wght].ttf"
      }
      """
        .trimIndent()
    assertEquals("Family[wdth,wght].ttf", pickVariableFileName(metadata, italic = false))
    assertEquals("Family-Italic[wdth,wght].ttf", pickVariableFileName(metadata, italic = true))
  }

  @Test
  fun `pickVariableFileName returns null for a family that ships only static files`() {
    // Lobster Two is the real case: catalogued, resolvable at a weight, no variable file anywhere.
    val metadata =
      """
      fonts {
        filename: "LobsterTwo-Regular.ttf"
      }
      fonts {
        filename: "LobsterTwo-Bold.ttf"
      }
      """
        .trimIndent()
    assertNull(pickVariableFileName(metadata, italic = false))
    assertNull(pickVariableFileName(metadata, italic = true))
  }

  @Test
  fun `pickVariableFileName falls back to the upright file when a family has no italic variable`() {
    val metadata = """fonts { filename: "Family[wght].ttf" }"""
    assertEquals("Family[wght].ttf", pickVariableFileName(metadata, italic = true))
  }

  @Test
  fun `hasFvarTable reads the table directory`() {
    assertTrue(hasFvarTable(sfntWithTables("glyf", "fvar", "head")))
    // The shape the CSS API actually serves for a variable family: a baked static instance. Caching
    // that as "the variable file" is the failure this guard exists to prevent.
    assertFalse(hasFvarTable(sfntWithTables("glyf", "head", "STAT")))
    assertFalse(hasFvarTable(byteArrayOf(0, 1, 0)))
    // A header claiming more tables than the buffer holds is a truncated download, not a font.
    assertFalse(hasFvarTable(byteArrayOf(0, 1, 0, 0, 0, 9, 0, 0, 0, 0, 0, 0)))
  }

  @Test
  fun `loadVariable caches under a weight-free name and asks the source once`() {
    val dir = tempDir.newFolder("fonts")
    var calls = 0
    val cache =
      GoogleFontCache(
        dir,
        false,
        { _, _ -> error("the static downloader must not serve a variable request") },
        { _, _, dest ->
          calls++
          dest.writeBytes(FAKE_TTF_BYTES)
          true
        },
      )
    val file = cache.loadVariable("Roboto Flex")
    assertNotNull(file)
    assertEquals("roboto-flex-variable.ttf", file!!.name)
    // One file serves every weight — a second ask must not re-download 1.7 MB.
    assertEquals(file, cache.loadVariable("Roboto Flex"))
    assertEquals(1, calls)
  }

  @Test
  fun `loadVariable keeps upright and italic as separate files`() {
    val dir = tempDir.newFolder("fonts")
    val cache =
      GoogleFontCache(
        dir,
        false,
        { _, _ -> error("the static downloader must not serve a variable request") },
        { _, _, dest ->
          dest.writeBytes(FAKE_TTF_BYTES)
          true
        },
      )
    assertEquals(
      "roboto-flex-variable.ttf",
      cache.loadVariable("Roboto Flex", italic = false)?.name,
    )
    assertEquals(
      "roboto-flex-variable-italic.ttf",
      cache.loadVariable("Roboto Flex", italic = true)?.name,
    )
  }

  @Test
  fun `loadVariable leaves no file behind when the family has no variable font`() {
    val dir = tempDir.newFolder("fonts")
    val cache = GoogleFontCache(dir, false, { _, _ -> error("static path") }, { _, _, _ -> false })
    assertNull(cache.loadVariable("Lobster Two"))
    assertFalse(File(dir, variableFileName("Lobster Two", italic = false)).exists())
  }

  @Test
  fun `loadVariable returns null on miss when offline`() {
    val dir = tempDir.newFolder("fonts")
    val cache =
      GoogleFontCache(
        dir,
        true,
        { _, _ -> error("offline mode must not invoke the downloader") },
        { _, _, _ -> error("offline mode must not invoke the downloader") },
      )
    assertNull(cache.loadVariable("Roboto Flex"))
  }

  @Test
  fun `a source that serves only static faces reports no variable file`() {
    // The interface default: every existing fake keeps compiling, and says "no axes here".
    val staticOnly =
      object : GoogleFontSource {
        override fun load(key: GoogleFontKey): File? = null
      }
    assertNull(staticOnly.loadVariable("Roboto Flex"))
  }

  companion object {
    // Minimum byte sequence the cache round-trips. No need to be a valid
    // TTF — the unit tests never parse the file, only the
    // shadow/end-to-end path hands it to `Typeface.createFromFile`.
    private val FAKE_TTF_BYTES = byteArrayOf(0, 1, 0, 0, 0, 0)

    /** An sfnt header + table directory naming [tags]; the tables themselves are not written. */
    private fun sfntWithTables(vararg tags: String): ByteArray {
      val out = ByteArray(12 + tags.size * 16)
      // sfnt version 1.0, then numTables.
      out[1] = 1
      out[4] = (tags.size shr 8).toByte()
      out[5] = tags.size.toByte()
      tags.forEachIndexed { i, tag ->
        tag.forEachIndexed { c, ch -> out[12 + i * 16 + c] = ch.code.toByte() }
      }
      return out
    }
  }
}
