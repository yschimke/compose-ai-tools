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

  /** [displayName] for reporting, falling back to the slug itself rather than a guess. */
  fun label(slug: String): String = displayName(slug) ?: slug.trim()
}
