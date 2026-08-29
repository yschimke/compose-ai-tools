package ee.schimke.composeai.data.render

/**
 * Resolution of the backing colour a preview is drawn on, shared by both renderer backends and both
 * daemons so `@Preview(showBackground = …, backgroundColor = …, uiMode = …)` resolves identically
 * everywhere. Pure ARGB `Int` math — no Compose types — so it can live in the protocol module the
 * Android and Desktop trees both see.
 *
 * ### Why `showBackground = true` is not simply white
 *
 * Android Studio does not paint white for `showBackground = true`; it paints the *theme's*
 * `windowBackground`, and under `uiMode = UI_MODE_NIGHT_YES` a DayNight theme resolves that to a
 * dark neutral. The renderers used to hardcode `Color.White`, so every dark-mode preview came out
 * on a white sheet. That is not a cosmetic difference: a composable that does not paint its own
 * `Surface` inherits nothing but the backing colour, so its dark-mode `onSurface` text landed on
 * white — light-on-light, around 1.3:1 — while the same preview looked fine in Android Studio.
 *
 * We approximate rather than reproduce AS exactly. Resolving the real `windowBackground` needs an
 * Android `Context` and a themed activity, which the Desktop backend has no equivalent of, so the
 * two backends would disagree — and disagreeing is worse than being uniformly close. [NIGHT_ARGB]
 * is Material 3's dark surface, which is what a DayNight `windowBackground` resolves to for the
 * apps this tooling renders. An explicit `@Preview(backgroundColor = …)` always wins, so a preview
 * that needs an exact value can still state one.
 */
public object PreviewBackground {

  /** Fully transparent — `showBackground = false` and no explicit `backgroundColor`. */
  public const val TRANSPARENT_ARGB: Int = 0x00000000

  /** The `showBackground = true` backing for a non-night (or unspecified) `uiMode`. */
  public const val DAY_ARGB: Int = 0xFFFFFFFF.toInt()

  /**
   * The `showBackground = true` backing for `uiMode and UI_MODE_NIGHT_MASK == UI_MODE_NIGHT_YES`.
   * Material 3's dark surface (`#1C1B1F`) — see the class comment for why this is a constant rather
   * than a theme lookup.
   */
  public const val NIGHT_ARGB: Int = 0xFF1C1B1F.toInt()

  /**
   * `android.content.res.Configuration.UI_MODE_NIGHT_MASK`, inlined to keep this module JVM-only.
   */
  private const val UI_MODE_NIGHT_MASK = 0x30

  /** `android.content.res.Configuration.UI_MODE_NIGHT_YES`. */
  private const val UI_MODE_NIGHT_YES = 0x20

  /** Whether [uiMode] carries the night bit. `0` (unspecified) is not night. */
  public fun isNight(uiMode: Int): Boolean = (uiMode and UI_MODE_NIGHT_MASK) == UI_MODE_NIGHT_YES

  /**
   * The ARGB backing colour for a preview.
   *
   * Precedence, highest first:
   * 1. [clearBackground] — a caller that needs a transparent capture whatever the preview declared
   *    (e.g. the Figma/SVG export path).
   * 2. [backgroundColor] — an explicit `@Preview(backgroundColor = …)`. `0` means "unset"; that is
   *    the annotation's own default, and a genuinely transparent backing is expressed by leaving
   *    [showBackground] false rather than by passing `0`.
   * 3. [showBackground] — [NIGHT_ARGB] when [night], [DAY_ARGB] otherwise.
   * 4. [TRANSPARENT_ARGB].
   *
   * [night] rather than a raw `uiMode` because the daemons carry the night axis as a resolved enum
   * (`RenderSpec.SpecUiMode`) that already folds in the inbound override, while the renderers hold
   * the raw `@Preview(uiMode = …)` bits — see the [resolveArgb] overload for those.
   */
  public fun resolveArgb(
    showBackground: Boolean,
    backgroundColor: Long,
    night: Boolean,
    clearBackground: Boolean = false,
  ): Int =
    when {
      clearBackground -> TRANSPARENT_ARGB
      backgroundColor != 0L -> backgroundColor.toInt()
      showBackground -> if (night) NIGHT_ARGB else DAY_ARGB
      else -> TRANSPARENT_ARGB
    }

  /** [resolveArgb] for callers holding the raw `@Preview(uiMode = …)` Configuration bits. */
  public fun resolveArgbForUiMode(
    showBackground: Boolean,
    backgroundColor: Long,
    uiMode: Int,
    clearBackground: Boolean = false,
  ): Int = resolveArgb(showBackground, backgroundColor, isNight(uiMode), clearBackground)
}
