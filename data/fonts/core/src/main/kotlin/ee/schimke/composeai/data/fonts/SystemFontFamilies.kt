package ee.schimke.composeai.data.fonts

/**
 * Android **system-font family slugs** — what a consumer passes to
 * `Font(DeviceFontFamilyName("roboto-flex"))` — and the canonical display name each one names.
 *
 * The slugs come from a Pixel 8/9 `/system/etc/fonts.xml`; every display name below exists on
 * fonts.google.com under exactly that spelling, which is what lets the renderer download the real
 * face and seed it as the system font under Robolectric.
 *
 * ## Why this table is shared
 *
 * Two lanes need the same mapping and must not disagree about it:
 * - the **renderer** seeds `Typeface.sSystemFontMap` from it, so
 *   `DeviceFontFamilyName("roboto-flex")` draws Roboto Flex rather than falling back to Roboto
 *   (`PixelSystemFontAliases`);
 * - the **`compose/semantics` producer** reports the typeface a text node resolved, and a node
 *   drawn in a device family has no other handle on its face — Compose's `DeviceFontFamilyNameFont`
 *   exposes neither a resource id nor a font-file identity.
 *
 * If only the first knew the table, the inspector would report the render's typeface as
 * `roboto-flex` while the design spec beside it says `Roboto Flex`, and the typography comparison
 * would call one face two — a difference reported where there is none.
 *
 * A slug with no entry is **not** guessed at: reverse-slugifying almost never round-trips the
 * public catalog's casing, so [displayName] answers null and [label] passes the slug through
 * unchanged. That is the honest report — the render did fall back to whatever the platform had.
 */
object SystemFontFamilies {

  /**
   * Ordered slug → display name. Restrict additions to families that ship in Pixel's bundled
   * `fonts.xml` **and** exist on fonts.google.com under the mapped name; a mapping that fails that
   * second test makes the renderer's seeding download 404 on every run.
   */
  val DISPLAY_NAMES: Map<String, String> =
    linkedMapOf(
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

  /** The canonical display name for [slug], or null when the table doesn't cover it. */
  fun displayName(slug: String): String? = DISPLAY_NAMES[slug.trim().lowercase()]

  /**
   * Slugs this process **tried and failed** to seed, so nothing drew the mapped face.
   *
   * Process-global because the two halves are a lane apart by construction: the renderer seeds (and
   * is the only thing that knows whether a download resolved), the semantics producer reports.
   * Empty on any lane that never seeds — the desktop renderer resolves families a different way, so
   * an absent entry means "not this mechanism", not "failed".
   */
  private val unseeded = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

  /**
   * Record the outcome of a seeding pass: [attempted] slugs of which [seeded] now resolve.
   *
   * Idempotent and re-entrant — a later pass that succeeds clears the earlier failure, so a warm
   * cache on the second render stops reporting the fallback.
   */
  fun recordSeeding(attempted: Collection<String>, seeded: Collection<String>) {
    val ok = seeded.map { it.trim().lowercase() }.toSet()
    for (slug in attempted.map { it.trim().lowercase() }) {
      if (slug in ok) unseeded.remove(slug) else unseeded.add(slug)
    }
  }

  /** Whether [slug] was seeded, or was never a slug this process tried to seed. */
  fun isDrawable(slug: String): Boolean = slug.trim().lowercase() !in unseeded

  /**
   * Notified when [label] is asked about a slug this process failed to seed — i.e. a preview
   * genuinely *used* a device family that nothing drew.
   *
   * The use site is the only honest place to raise this. Seeding attempts every entry in
   * [DISPLAY_NAMES] on every render process, so a cold cache fails all ten at once; treating that
   * as a per-render fault would fail every Android preview in every project, including the nine
   * families the preview never mentions. Asking about a slug is the point where the failure stops
   * being hypothetical: something is about to report a typeface, and the face it names is not the
   * one that drew.
   *
   * A listener rather than a direct call because the reporting sink lives in `:renderer-android`
   * ([FontResolutionDiagnostics]) and this module sits below it — the renderer installs itself.
   * Null on any lane with no sink (the desktop renderer, plain unit tests), where the slug simply
   * passes through as before.
   */
  @Volatile private var unseededUseListener: ((String) -> Unit)? = null

  /** Install the [unseededUseListener]. Idempotent; pass null to remove. */
  fun onUnseededUse(listener: ((String) -> Unit)?) {
    unseededUseListener = listener
  }

  /**
   * How [slug] should be **reported**: the display name of the face that was drawn, or the raw
   * slug.
   *
   * The raw slug is not a lesser answer, it is a different fact. When seeding failed — a cold font
   * cache, no egress, a family the CSS endpoint stopped serving — `Font(DeviceFontFamilyName(…))`
   * falls back to whatever the platform had, which is Roboto rather than the mapped face. Reporting
   * `Roboto Flex` there would name a typeface nothing drew, and hide the exact fallback drift the
   * design comparison exists to expose. The slug says what the code asked for and stops short of
   * claiming it was honoured.
   */
  fun label(slug: String): String {
    val trimmed = slug.trim()
    if (isDrawable(trimmed)) return displayName(trimmed) ?: trimmed
    // Asked about a family nothing drew — tell the sink before answering, so the render can treat
    // it the way it already treats an unresolved downloadable font.
    unseededUseListener?.invoke(trimmed)
    return trimmed
  }
}
