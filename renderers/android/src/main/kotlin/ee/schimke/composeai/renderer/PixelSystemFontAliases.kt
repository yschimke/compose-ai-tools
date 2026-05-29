package ee.schimke.composeai.renderer

import android.graphics.Typeface
import androidx.compose.ui.text.font.FontWeight

/**
 * Maps Android system-font family slugs (the names consumers pass to
 * `DeviceFontFamilyName("roboto-flex")`) to canonical Google Fonts display
 * names (`"Roboto Flex"`) so the renderer can transparently download the
 * matching TTF via [GoogleFontCacheAccess] and serve it as the system font
 * under Robolectric.
 *
 * ## Why this exists
 *
 * On a real Pixel 8 / 9 the `/system/etc/fonts.xml` file lists families like
 * `roboto-flex`, `google-sans-flex`, `noto-serif` etc. so
 * `Typeface.create("roboto-flex", …)` — which is what Compose's
 * `DeviceFontFamilyName` path ultimately calls — returns the system-provided
 * variable TTF. Under Robolectric the sandboxed `/system/fonts` comes from
 * the `android-all` artifact, which ships only a small AOSP subset (Roboto
 * static, Noto Emoji, CutiveMono, …). Every other family silently resolves
 * to `Typeface.DEFAULT`, so consumer code that renders fine on-device
 * renders as Roboto in the preview.
 *
 * ## What it does
 *
 * [seedSystemFontMap] injects TypeFaces into `Typeface.sSystemFontMap` keyed
 * by the slug. Once seeded, the real `Typeface.create(familyName, weight,
 * italic)` native path finds our entry and wraps it with the requested
 * weight/italic via `nativeCreateFromTypefaceWithExactStyle`. For variable
 * families (Roboto Flex, Google Sans Flex) the seeded TTF is the wght-axis
 * range-covering variable TTF, so weight selection propagates to the native
 * renderer to whatever extent Robolectric's Skia supports it (variable-axis
 * propagation is limited under Robolectric native graphics — see issue #119
 * and upstream android-review.googlesource.com/c/platform/frameworks/support/+/3945083).
 *
 * ## Mapping surface
 *
 * The table targets the publicly-downloadable overlap — a Pixel slug only
 * earns an entry if the same family exists on fonts.google.com under the
 * mapped display name. Slugs for proprietary Google-branded families (e.g.
 * `google-sans`, `google-sans-text`) that aren't on the public catalog are
 * deliberately omitted: a mapping would trigger a download that 404s on
 * every test run. Extend [ALIASES] when a new public family lands.
 *
 * Unknown slugs pass through untouched — `Font(DeviceFontFamilyName("weird"))`
 * falls through to Robolectric's real lookup and stays as `Typeface.DEFAULT`.
 */
internal object PixelSystemFontAliases {

    /**
     * Ordered pairs of (system-font slug, Google Fonts display name). Seeded
     * from Pixel 8/9 `/system/etc/fonts.xml` snapshots — every entry below
     * has been verified against the public fonts.google.com catalog, so the
     * CSS2 download path resolves a real TTF.
     *
     * Restrict to families that:
     *  - ship in Pixel's bundled fonts.xml, AND
     *  - exist on fonts.google.com under the mapped name.
     *
     * `roboto-flex` and `google-sans-flex` are variable families on both
     * sides (the CSS2 range query returns a single axis-covering TTF). The
     * remainder are static families whose closest-weight sub-font is picked
     * by [pickClosestTruetypeUrl].
     */
    internal val ALIASES: Map<String, String> = linkedMapOf(
        "roboto" to "Roboto",
        "roboto-flex" to "Roboto Flex",
        "google-sans-flex" to "Google Sans Flex",
        "noto-sans" to "Noto Sans",
        "noto-serif" to "Noto Serif",
        "noto-sans-mono" to "Noto Sans Mono",
        "cutive-mono" to "Cutive Mono",
        "coming-soon" to "Coming Soon",
        "dancing-script" to "Dancing Script",
        "carrois-gothic-sc" to "Carrois Gothic SC",
    )

    /**
     * Resolve [slug] to the canonical Google Fonts display name. Returns
     * `null` when the slug isn't in [ALIASES] — callers should NOT fall
     * through to a naive reverse-slugify because that almost always produces
     * a 404 on the CSS endpoint (the public catalog's casing rules don't
     * round-trip through [GoogleFontKey.slugify] for most families).
     */
    fun resolve(slug: String): String? = ALIASES[slug.lowercase()]

    /**
     * Seed `Typeface.sSystemFontMap` with cached TTFs for every entry in
     * [ALIASES] that [cache] can resolve. Idempotent — repeated calls skip
     * slugs already present.
     *
     * Returns the list of slugs successfully seeded. Empty list when
     * [cache] is unavailable (e.g. `composeai.fonts.cacheDir` unset) or when
     * every downloadable font is missing in offline mode.
     *
     * The seeding weight is picked to give Compose's
     * `nativeCreateFromTypefaceWithExactStyle` the broadest downstream
     * surface: for variable families we download the wght-range variant
     * (via the range CSS URL) so the resulting TTF carries the full
     * `wght 100..1000` axis. For static families we download the regular
     * weight — `Typeface.create(tf, weight, italic)` then applies synthesis
     * for off-400 weights the same way it would on-device.
     */
    fun seedSystemFontMap(
        cache: GoogleFontSource? = null,
        lookup: ((name: String, weight: Int, italic: Boolean) -> java.io.File?)? = null,
        systemFontMap: MutableMap<String, Typeface>? = systemFontMap(),
        typefaceBuilder: (java.io.File, Int, Boolean) -> Typeface? = ::buildTypefaceFromFile,
    ): List<String> {
        // Map access is ordered ahead of the resolver so unit tests can pass a
        // plain `mutableMapOf()` without relying on reflective access to the
        // real `Typeface.sSystemFontMap` — that field only exists when the
        // test runs inside Robolectric (the JVM android.jar stub lacks it).
        val map = systemFontMap ?: return emptyList()
        val resolver: (String, Int, Boolean) -> java.io.File? = when {
            lookup != null -> lookup
            cache != null -> { n, w, i -> cache.load(GoogleFontKey(n, FontWeight(w), i)) }
            else -> { n, w, i -> GoogleFontCacheAccess.load(n, w, i) }
        }
        val seeded = mutableListOf<String>()
        for ((slug, displayName) in ALIASES) {
            if (map.containsKey(slug)) {
                seeded += slug
                continue
            }
            val file = resolver(displayName, 400, false) ?: continue
            val typeface = runCatching { typefaceBuilder(file, 400, false) }.getOrNull() ?: continue
            map[slug] = typeface
            seeded += slug
        }
        return seeded
    }

    /**
     * Reflective handle to `android.graphics.Typeface.sSystemFontMap`. Null
     * when the field doesn't exist (pre-O or a future Android refactor) or
     * isn't a mutable map — both cases we silently skip, letting the system
     * lookup fall through to Robolectric's own behaviour.
     */
    @Suppress("UNCHECKED_CAST")
    private fun systemFontMap(): MutableMap<String, Typeface>? = runCatching {
        val field = Typeface::class.java.getDeclaredField("sSystemFontMap")
        field.isAccessible = true
        field.get(null) as? MutableMap<String, Typeface>
    }.getOrNull()
}
