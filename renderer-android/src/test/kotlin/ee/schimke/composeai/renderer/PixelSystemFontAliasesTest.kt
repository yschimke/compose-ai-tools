package ee.schimke.composeai.renderer

import android.graphics.Typeface
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Unit tests for [PixelSystemFontAliases] — the mapping table that lets
 * `Font(DeviceFontFamilyName("roboto-flex"), …)` transparently resolve via
 * the downloadable Google Fonts cache.
 *
 * The end-to-end seeding path is exercised by `:sample-android:renderAllPreviews`
 * (see `DeviceFontFamilyShowcasePreview`); these tests cover the pure-JVM
 * surface — the mapping table and the lookup/builder/map plumbing. The real
 * `Typeface.sSystemFontMap` only exists when the test runs inside Robolectric
 * (the JVM android.jar stub doesn't carry it), so tests pass their own
 * `mutableMapOf()` and a synthetic typeface builder.
 */
class PixelSystemFontAliasesTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    @Test
    fun `resolve returns canonical Google Fonts display names for seeded slugs`() {
        assertEquals("Roboto Flex", PixelSystemFontAliases.resolve("roboto-flex"))
        assertEquals("Google Sans Flex", PixelSystemFontAliases.resolve("google-sans-flex"))
        assertEquals("Noto Serif", PixelSystemFontAliases.resolve("noto-serif"))
        assertEquals("Dancing Script", PixelSystemFontAliases.resolve("dancing-script"))
    }

    @Test
    fun `resolve is case-insensitive to tolerate DeviceFontFamilyName casings`() {
        // `DeviceFontFamilyName` preserves whatever the consumer typed; Pixel's
        // own fonts.xml uses all-lowercase but a caller might easily write
        // "Roboto-Flex". Normalise before lookup so either shape resolves.
        assertEquals("Roboto Flex", PixelSystemFontAliases.resolve("Roboto-Flex"))
        assertEquals("Roboto Flex", PixelSystemFontAliases.resolve("ROBOTO-FLEX"))
    }

    @Test
    fun `resolve returns null for unknown slugs`() {
        // Intentional: a naive reverse-slugify ("sans-serif" → "Sans Serif")
        // almost always produces a 404 on the CSS2 endpoint, so we never fall
        // through — the slug either has an explicit entry in ALIASES or the
        // caller sees null and lets Robolectric's real lookup run.
        assertNull(PixelSystemFontAliases.resolve("sans-serif"))
        assertNull(PixelSystemFontAliases.resolve("monospace"))
        assertNull(PixelSystemFontAliases.resolve("google-sans")) // proprietary, not on public catalog
        assertNull(PixelSystemFontAliases.resolve(""))
    }

    @Test
    fun `aliases table round-trips through slugify for every entry`() {
        // Invariant: slugify(displayName) must equal the slug key. That's what
        // lets the downloadable cache key match the on-device system name, so
        // a TTF seeded for "Roboto Flex" lives at roboto-flex-400.ttf on disk
        // and serves both the `Font(GoogleFont("Roboto Flex"))` and the
        // `Font(DeviceFontFamilyName("roboto-flex"))` entry points. Any new
        // alias whose display name slugifies differently would split the
        // cache; this test catches that early.
        for ((slug, displayName) in PixelSystemFontAliases.ALIASES) {
            assertEquals(
                "alias \"$slug\" → \"$displayName\" must round-trip through slugify",
                slug,
                GoogleFontKey.slugify(displayName),
            )
        }
    }

    @Test
    fun `seedSystemFontMap invokes the lookup with display name and weight 400`() {
        val calls = mutableListOf<Triple<String, Int, Boolean>>()
        val lookup: (String, Int, Boolean) -> File? = { name, weight, italic ->
            calls += Triple(name, weight, italic)
            null // no TTF produced — we only care about which calls fire
        }
        val seeded = PixelSystemFontAliases.seedSystemFontMap(
            lookup = lookup,
            systemFontMap = mutableMapOf(),
        )
        // No entries seeded because lookup never returned a file.
        assertTrue(seeded.isEmpty())
        // Every alias asked for its display name + regular weight + upright.
        assertEquals(PixelSystemFontAliases.ALIASES.size, calls.size)
        assertTrue(calls.any { it.first == "Roboto Flex" && it.second == 400 && !it.third })
        assertTrue(calls.any { it.first == "Google Sans Flex" })
        assertTrue(calls.any { it.first == "Dancing Script" })
    }

    @Test
    fun `seedSystemFontMap returns empty list when system map is unavailable`() {
        // Mirrors what happens under a plain JVM test (no Robolectric) where
        // reflective access to `Typeface.sSystemFontMap` returns null — we
        // quietly skip rather than throwing, so consumer tests don't have to
        // special-case the missing-Robolectric case.
        val seeded = PixelSystemFontAliases.seedSystemFontMap(
            lookup = { _, _, _ -> error("should not be called when map is null") },
            systemFontMap = null,
        )
        assertTrue(seeded.isEmpty())
    }

    @Test
    fun `seedSystemFontMap injects typefaces into the supplied map for each successful lookup`() {
        // The typefaceBuilder param lets us substitute a sentinel Typeface
        // without needing a real TTF file. The seeding path should route
        // every lookup-hit through the builder and stash the result in the
        // supplied map keyed by slug.
        val sentinelTypeface = SentinelTypeface.NORMAL
        val seededFile = tempDir.newFile("fake.ttf").apply { writeBytes(FAKE_TTF_BYTES) }
        val map = mutableMapOf<String, Typeface>()
        val seeded = PixelSystemFontAliases.seedSystemFontMap(
            lookup = { _, _, _ -> seededFile },
            systemFontMap = map,
            typefaceBuilder = { _, _, _ -> sentinelTypeface },
        )
        assertEquals(PixelSystemFontAliases.ALIASES.size, seeded.size)
        assertEquals(PixelSystemFontAliases.ALIASES.keys.toSet(), map.keys)
        for (slug in PixelSystemFontAliases.ALIASES.keys) {
            assertSame("slug $slug must point at the built typeface", sentinelTypeface, map[slug])
        }
    }

    @Test
    fun `seedSystemFontMap is idempotent — preseeded slugs aren't overwritten`() {
        val sentinelOld = SentinelTypeface.NORMAL
        val sentinelNew = SentinelTypeface.BOLD
        val map = mutableMapOf<String, Typeface>("roboto-flex" to sentinelOld)
        var builderCalls = 0
        val seeded = PixelSystemFontAliases.seedSystemFontMap(
            lookup = { _, _, _ -> tempDir.newFile() },
            systemFontMap = map,
            typefaceBuilder = { _, _, _ ->
                builderCalls++
                sentinelNew
            },
        )
        // Returned list still includes the preseeded slug so callers can count
        // the effective coverage at a glance.
        assertTrue("roboto-flex" in seeded)
        // But the existing entry is preserved — no rebuild, no overwrite.
        assertSame(sentinelOld, map["roboto-flex"])
        assertEquals(
            "builder should run once per NOT-yet-seeded alias",
            PixelSystemFontAliases.ALIASES.size - 1,
            builderCalls,
        )
    }

    @Test
    fun `GoogleFontKey cache file lands at slug-derived path so system alias shares the cache`() {
        // Same invariant as the round-trip test, phrased through the actual
        // cache filename. A DeviceFontFamilyName("roboto-flex") seeded from
        // display name "Roboto Flex" must cache to roboto-flex-400.ttf,
        // which is identical to the GoogleFont("Roboto Flex") cache entry —
        // so the two entry points share bytes instead of double-downloading.
        val systemAliasFile = GoogleFontKey(
            name = "Roboto Flex",
            weight = FontWeight(400),
            italic = false,
        ).fileName()
        assertEquals("roboto-flex-400.ttf", systemAliasFile)
    }

    @Test
    fun `resolve does not trim whitespace or collapse hyphen runs`() {
        // Deliberate non-goal: we don't trim whitespace or collapse runs of
        // hyphens. DeviceFontFamilyName values rarely carry whitespace, and
        // normalising beyond lowercase risks matching unintended slugs.
        assertNull(PixelSystemFontAliases.resolve(" roboto-flex "))
        assertNull(PixelSystemFontAliases.resolve("roboto--flex"))
        assertNotNull(PixelSystemFontAliases.resolve("roboto-flex"))
    }

    companion object {
        // Minimum byte sequence so the temp file has non-zero length; not a
        // parseable TTF. The seeding-path tests substitute the typefaceBuilder
        // with a sentinel so these bytes are never actually handed to
        // `Typeface.Builder`.
        private val FAKE_TTF_BYTES = byteArrayOf(0, 1, 0, 0, 0, 0)
    }

    /**
     * Distinct, reference-equal-by-design stand-ins for [Typeface]. We can't
     * use `Typeface.DEFAULT` / `Typeface.DEFAULT_BOLD` here — those fields are
     * populated by the real platform at boot and are null against the JVM
     * android.jar stub these unit tests run on.
     *
     * The `NORMAL` and `BOLD` entries exist purely as two non-equal instances
     * so `assertSame` can tell them apart; nothing ever calls into their API.
     */
    private object SentinelTypeface {
        val NORMAL: Typeface = newStubTypeface()
        val BOLD: Typeface = newStubTypeface()

        private fun newStubTypeface(): Typeface {
            // `Typeface` has only package-private constructors; Unsafe.allocateInstance
            // is the standard no-arg bypass on JVM. These instances are never
            // unpacked into native code — the filter-logic tests only do
            // reference-equality checks on what the builder returns.
            val unsafeField = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe")
            unsafeField.isAccessible = true
            val unsafe = unsafeField.get(null) as sun.misc.Unsafe
            return unsafe.allocateInstance(Typeface::class.java) as Typeface
        }
    }
}
