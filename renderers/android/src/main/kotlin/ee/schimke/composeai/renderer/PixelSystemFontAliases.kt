package ee.schimke.composeai.renderer

import android.graphics.Typeface
import ee.schimke.composeai.data.fonts.SystemFontFamilies
import ee.schimke.composeai.fonts.google.GoogleFontCache
import ee.schimke.composeai.fonts.google.GoogleFontKey
import ee.schimke.composeai.fonts.google.GoogleFontSource

/**
 * Maps Android system-font family slugs (the names consumers pass to
 * `DeviceFontFamilyName("roboto-flex")`) to canonical Google Fonts display names (`"Roboto Flex"`)
 * so the renderer can transparently download the matching TTF via [GoogleFontCacheAccess] and serve
 * it as the system font under Robolectric.
 *
 * ## Why this exists
 *
 * On a real Pixel 8 / 9 the `/system/etc/fonts.xml` file lists families like `roboto-flex`,
 * `google-sans-flex`, `noto-serif` etc. so `Typeface.create("roboto-flex", …)` — which is what
 * Compose's `DeviceFontFamilyName` path ultimately calls — returns the system-provided variable
 * TTF. Under Robolectric the sandboxed `/system/fonts` comes from the `android-all` artifact, which
 * ships only a small AOSP subset (Roboto static, Noto Emoji, CutiveMono, …). Every other family
 * silently resolves to `Typeface.DEFAULT`, so consumer code that renders fine on-device renders as
 * Roboto in the preview.
 *
 * ## What it does
 *
 * [seedSystemFontMap] injects TypeFaces into `Typeface.sSystemFontMap` keyed by the slug. Once
 * seeded, the real `Typeface.create(familyName, weight, italic)` native path finds our entry and
 * wraps it with the requested weight/italic via `nativeCreateFromTypefaceWithExactStyle`. For
 * variable families (Roboto Flex, Google Sans Flex) the seeded TTF is the wght-axis range-covering
 * variable TTF, so weight selection propagates to the native renderer to whatever extent
 * Robolectric's Skia supports it (variable-axis propagation is limited under Robolectric native
 * graphics — see issue #119 and upstream
 * android-review.googlesource.com/c/platform/frameworks/support/+/3945083).
 *
 * ## Mapping surface
 *
 * The table targets the publicly-downloadable overlap — a Pixel slug only earns an entry if the
 * same family exists on fonts.google.com under the mapped display name. Slugs for proprietary
 * Google-branded families (e.g. `google-sans`, `google-sans-text`) that aren't on the public
 * catalog are deliberately omitted: a mapping would trigger a download that 404s on every test run.
 * Extend [ALIASES] when a new public family lands.
 *
 * Unknown slugs pass through untouched — `Font(DeviceFontFamilyName("weird"))` falls through to
 * Robolectric's real lookup and stays as `Typeface.DEFAULT`.
 *
 * ## Both renderers must seed
 *
 * Seeding is per-process state, so EVERY process that rasterises previews has to do it or the two
 * tiers disagree. The batch/snapshot renderer seeds in [RobolectricRenderTest.renderDefault]; the
 * **daemon** (`RenderEngine`, which drives `serve`'s live lane) seeds via [seedSystemFonts]. When
 * only one of them did, a `DeviceFontFamilyName` family rendered as Roboto in the live stream and
 * as the real face in the baked PNG — a silent typeface change between the two views of the same
 * preview, with no warning on either side (unlike the downloadable-`GoogleFont` path, which fails
 * the preview outright). That's why [seedSystemFonts] is public: the daemon lives in another
 * module.
 */
object PixelSystemFontAliases {

  /**
   * Ordered pairs of (system-font slug, Google Fonts display name). Seeded from Pixel 8/9
   * `/system/etc/fonts.xml` snapshots — every entry has been verified against the public
   * fonts.google.com catalog, so the CSS2 download path resolves a real TTF.
   *
   * The table itself lives in [SystemFontFamilies] because the `compose/semantics` producer needs
   * the identical mapping to *report* the face a `DeviceFontFamilyName` node resolved. Two copies
   * would let the renderer draw Roboto Flex while the inspector called the same node `roboto-flex`
   * — the design comparison then reports a typeface change that never happened. Add new families
   * there.
   *
   * `roboto-flex` and `google-sans-flex` are variable families on both sides (the CSS2 range query
   * returns a single axis-covering TTF). The remainder are static families whose closest-weight
   * sub-font is picked by [pickClosestTruetypeUrl].
   */
  internal val ALIASES: Map<String, String>
    get() = SystemFontFamilies.DISPLAY_NAMES

  /**
   * Resolve [slug] to the canonical Google Fonts display name. Returns `null` when the slug isn't
   * in [ALIASES] — callers should NOT fall through to a naive reverse-slugify because that almost
   * always produces a 404 on the CSS endpoint (the public catalog's casing rules don't round-trip
   * through [GoogleFontKey.slugify] for most families).
   */
  fun resolve(slug: String): String? = ALIASES[slug.lowercase()]

  /**
   * Public entry point for [seedSystemFontMap] — same behaviour, no internal types in the
   * signature, so the daemon module (`RenderEngine`) can seed the same aliases the batch renderer
   * does. Returns the slugs now present in the system font map.
   *
   * A slug that can't be resolved (cold cache with no egress, offline mode, a family the CSS
   * endpoint no longer serves) is reported through [warn] **once per process** rather than passing
   * silently: an unseeded `roboto-flex` means every `DeviceFontFamilyName("roboto-flex")` in the
   * render falls back to Roboto, which is a visible typeface change and the exact drift this
   * function exists to prevent. It is deliberately NOT fatal — unlike a downloadable `GoogleFont`,
   * a device-family miss is what a real device without that family would do too, and failing the
   * render would take down previews that never asked for the family in the first place.
   */
  fun seedSystemFonts(warn: (String) -> Unit = { System.err.println(it) }): List<String> {
    val seeded = seedSystemFontMap()
    // Tell the reporting side what actually resolved. A slug that didn't renders as the platform's
    // fallback, and the `compose/semantics` producer must name *that* rather than the family the
    // code asked for — otherwise the typography inspector reports a face nothing drew, hiding the
    // very drift the warning below is about.
    SystemFontFamilies.recordSeeding(attempted = ALIASES.keys, seeded = seeded)
    val missing = ALIASES.keys - seeded.toSet()
    if (missing.isNotEmpty() && unseededWarned.compareAndSet(false, true)) {
      warn(
        "compose-preview: system font aliases not seeded: ${missing.joinToString(", ")}. " +
          "`Font(DeviceFontFamilyName(<slug>))` for these renders as Roboto instead of the real " +
          "face. Warm the font cache (composeai.fonts.cacheDir) or allow egress to " +
          "fonts.googleapis.com + fonts.gstatic.com."
      )
    }
    return seeded
  }

  /** Guards the one-shot [seedSystemFonts] warning so a per-render call doesn't spam the log. */
  private val unseededWarned = java.util.concurrent.atomic.AtomicBoolean(false)

  /**
   * Seed `Typeface.sSystemFontMap` with cached TTFs for every entry in [ALIASES] that [cache] can
   * resolve. Idempotent — repeated calls skip slugs already present.
   *
   * Returns the list of slugs successfully seeded. Empty list when [cache] is unavailable (e.g.
   * `composeai.fonts.cacheDir` unset) or when every downloadable font is missing in offline mode.
   *
   * Seeding always asks for weight 400, and takes whatever [GoogleFontCache] resolves that to. Note
   * that for a variable family this is the family's **static 400 instance**, not the axis-covering
   * variable TTF: `downloadFromGoogleFonts` only falls back to the `wght@100..1000` range query
   * when the exact-weight query carried no TTF url, and for Roboto Flex / Google Sans Flex the
   * exact-weight query *does* answer. `Typeface.create(tf, weight, italic)` then synthesises
   * off-400 weights, the same as it would for a static family. That's a fidelity limit worth
   * knowing about, but it is not a parity risk: both render tiers go through this identical path,
   * so the live daemon and the baked snapshot agree on the face either way.
   */
  internal fun seedSystemFontMap(
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
    val resolver: (String, Int, Boolean) -> java.io.File? =
      when {
        lookup != null -> lookup
        cache != null -> { n, w, i ->
          cache.load(GoogleFontKey(n, w, i))
        }
        else -> { n, w, i ->
          GoogleFontCacheAccess.load(n, w, i)
        }
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
   * Reflective handle to `android.graphics.Typeface.sSystemFontMap`. Null when the field doesn't
   * exist (pre-O or a future Android refactor) or isn't a mutable map — both cases we silently
   * skip, letting the system lookup fall through to Robolectric's own behaviour.
   */
  @Suppress("UNCHECKED_CAST")
  private fun systemFontMap(): MutableMap<String, Typeface>? = runCatching {
    val field = Typeface::class.java.getDeclaredField("sSystemFontMap")
    field.isAccessible = true
    field.get(null) as? MutableMap<String, Typeface>
  }
    .getOrNull()
}
