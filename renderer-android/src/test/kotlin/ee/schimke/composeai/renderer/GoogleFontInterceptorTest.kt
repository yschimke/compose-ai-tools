package ee.schimke.composeai.renderer

import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Unit tests for [GoogleFontCache], [GoogleFontKey], the CSS-API helpers,
 * and the `FontRequest.query` parser that underpins
 * [ShadowFontsContractCompat].
 *
 * No network access: the download path is stubbed with a canned byte array
 * so the test is deterministic. No Robolectric runner either — these are
 * pure JVM helpers, and the shadow is exercised end-to-end via
 * `:sample-android:renderAllPreviews`.
 */
class GoogleFontInterceptorTest {

    @get:Rule
    val tempDir = TemporaryFolder()

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
            GoogleFontKey("Roboto Mono", FontWeight(400), italic = false).fileName(),
        )
        assertEquals(
            "roboto-mono-700-italic.ttf",
            GoogleFontKey("Roboto Mono", FontWeight(700), italic = true).fileName(),
        )
    }

    @Test
    fun `buildCssUrl encodes family name with spaces and weight axis`() {
        val url = buildCssUrl(GoogleFontKey("Roboto Mono", FontWeight(500), italic = false))
        assertEquals(
            "https://fonts.googleapis.com/css2?family=Roboto%20Mono:wght@500&display=swap",
            url,
        )
    }

    @Test
    fun `buildCssUrl encodes italic axis`() {
        val url = buildCssUrl(GoogleFontKey("Inter", FontWeight(400), italic = true))
        assertEquals(
            "https://fonts.googleapis.com/css2?family=Inter:ital,wght@1,400&display=swap",
            url,
        )
    }

    @Test
    fun `extractFirstTruetypeUrl picks the truetype-formatted entry only`() {
        val css = """
            @font-face {
              font-family: 'Inter';
              src: url(https://fonts.gstatic.com/foo.woff2) format('woff2');
            }
            @font-face {
              font-family: 'Inter';
              src: url(https://fonts.gstatic.com/foo.ttf) format('truetype');
            }
        """.trimIndent()
        assertEquals("https://fonts.gstatic.com/foo.ttf", extractFirstTruetypeUrl(css))
    }

    @Test
    fun `extractFirstTruetypeUrl returns null when no truetype source is present`() {
        val css = """
            @font-face {
              src: url(https://fonts.gstatic.com/foo.woff2) format('woff2');
            }
        """.trimIndent()
        assertNull(extractFirstTruetypeUrl(css))
    }

    @Test
    fun `buildRangeCssUrl spans the conventional 100-1000 variable-axis range`() {
        val url = buildRangeCssUrl(GoogleFontKey("Roboto Flex", FontWeight(100), italic = false))
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
        val css = """
            @font-face {
              font-family: 'Roboto Flex';
              font-weight: 400;
              src: url(https://fonts.gstatic.com/flex.ttf) format('truetype');
            }
        """.trimIndent()
        assertEquals(
            "https://fonts.gstatic.com/flex.ttf",
            pickClosestTruetypeUrl(css, requestedWeight = 100),
        )
    }

    @Test
    fun `pickClosestTruetypeUrl picks the nearest declared weight from a static family`() {
        val css = """
            @font-face { font-family: 'X'; font-weight: 300;
              src: url(https://fonts.gstatic.com/x-300.ttf) format('truetype'); }
            @font-face { font-family: 'X'; font-weight: 500;
              src: url(https://fonts.gstatic.com/x-500.ttf) format('truetype'); }
            @font-face { font-family: 'X'; font-weight: 700;
              src: url(https://fonts.gstatic.com/x-700.ttf) format('truetype'); }
        """.trimIndent()
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
    fun `parseFontRequestQuery reads the Compose GoogleFont wire format`() {
        val query = "name=Roboto%20Mono&weight=500&width=100.0&italic=0.0&besteffort=true"
        val key = parseFontRequestQuery(query)
        assertNotNull(key)
        assertEquals("Roboto Mono", key!!.name)
        assertEquals(500, key.weight.weight)
        assertFalse(key.italic)
    }

    @Test
    fun `parseFontRequestQuery treats italic floats above half as italic`() {
        val key = parseFontRequestQuery("name=Inter&weight=700&italic=1.0&besteffort=true")
        assertNotNull(key)
        assertTrue(key!!.italic)
        assertEquals(700, key.weight.weight)
    }

    @Test
    fun `parseFontRequestQuery defaults missing weight to 400 and italic to false`() {
        val key = parseFontRequestQuery("name=Inter&besteffort=true")
        assertNotNull(key)
        assertEquals(400, key!!.weight.weight)
        assertFalse(key.italic)
    }

    @Test
    fun `parseFontRequestQuery returns null when name is missing or blank`() {
        assertNull(parseFontRequestQuery(null))
        assertNull(parseFontRequestQuery("weight=400&italic=0.0"))
        assertNull(parseFontRequestQuery("name=&weight=400"))
    }

    @Test
    fun `GoogleFontCache returns cached file when it already exists`() {
        val dir = tempDir.newFolder("fonts")
        val key = GoogleFontKey("Roboto Mono", FontWeight(400), italic = false)
        val existing = File(dir, key.fileName())
        existing.writeBytes(FAKE_TTF_BYTES)
        var downloaderCalls = 0
        val cache = GoogleFontCache(dir, offline = false, downloader = { _, _ ->
            downloaderCalls++
            true
        })
        val file = cache.load(key)
        assertEquals(existing, file)
        assertEquals(0, downloaderCalls)
    }

    @Test
    fun `GoogleFontCache invokes downloader on miss and caches the result`() {
        val dir = tempDir.newFolder("fonts")
        val key = GoogleFontKey("Inter", FontWeight(400), italic = false)
        var downloaderCalls = 0
        val cache = GoogleFontCache(dir, offline = false, downloader = { _, dest ->
            downloaderCalls++
            dest.writeBytes(FAKE_TTF_BYTES)
            true
        })
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
        val key = GoogleFontKey("Inter", FontWeight(400), italic = false)
        val cache = GoogleFontCache(dir, offline = true, downloader = { _, _ ->
            error("offline mode must not invoke the downloader")
        })
        assertNull(cache.load(key))
    }

    @Test
    fun `GoogleFontCache returns null and leaves no file when downloader fails`() {
        val dir = tempDir.newFolder("fonts")
        val key = GoogleFontKey("Ghost", FontWeight(400), italic = false)
        val cache = GoogleFontCache(dir, offline = false, downloader = { _, _ -> false })
        assertNull(cache.load(key))
        assertFalse(File(dir, key.fileName()).exists())
    }

    companion object {
        // Minimum byte sequence the cache round-trips. No need to be a valid
        // TTF — the unit tests never parse the file, only the
        // shadow/end-to-end path hands it to `Typeface.createFromFile`.
        private val FAKE_TTF_BYTES = byteArrayOf(0, 1, 0, 0, 0, 0)
    }
}
