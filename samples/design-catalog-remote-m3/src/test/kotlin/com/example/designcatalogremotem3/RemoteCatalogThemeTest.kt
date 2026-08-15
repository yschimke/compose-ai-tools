@file:Suppress("RestrictedApiAndroidX")

package com.example.designcatalogremotem3

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pins what a declared theme *is* here: data applied to an already-recorded document, never
 * something installed while recording it.
 *
 * Every sticker is captured under the default theme, so the document carries the stock scheme as
 * named state (`USER:WearM3.<role>`). A theme is then a map of overrides onto those names, which
 * the player's `setNamedColorOverride` applies with no recomposition — which is what lets a
 * *published* catalog, whose bytecode was dropped at pack time, still be re-themed.
 *
 * The keys are therefore load-bearing in a way ordinary constants are not: they have to match the
 * names the document actually emits, or the override silently no-ops and the render comes back
 * unthemed under a successful status.
 */
class RemoteCatalogThemeTest {

  /**
   * The declared set mirrors `:samples:design-catalog-wear-m3`'s, name for name and in order — that
   * pairing is what lets the cross-system compare read the two catalogs as one theme set, so a name
   * added on one side and not the other is a regression rather than a detail.
   */
  @Test
  fun `the declared themes mirror the wear sibling's set`() {
    assertThat(REMOTE_THEME_NAMES)
      .containsExactly("M3", "Coral", "Teal", "Google Sans Flex", "KotlinConf")
      .inOrder()
  }

  /**
   * Every key addresses a `RemoteMaterialTheme` role under the `WearM3.` prefix the document names
   * its state with. A typo here costs nothing at build time and everything at render time: the
   * player skips an unknown name, so the sticker comes back stock rather than failing.
   */
  @Test
  fun `every override key is a WearM3-prefixed role`() {
    for (name in REMOTE_THEME_NAMES) {
      for (key in remoteCatalogThemeColors(name).keys) {
        assertThat(key).startsWith("WearM3.")
        assertThat(key.removePrefix("WearM3.")).isNotEmpty()
      }
    }
  }

  /**
   * M3 is the stock scheme, and Google Sans Flex is palette-identical to it on purpose so the pair
   * isolates the typeface. Both must therefore override no colour at all — a stray entry would make
   * the Google Sans Flex / M3 comparison a type *and* colour change.
   */
  @Test
  fun `the baseline themes override no colour`() {
    assertThat(remoteCatalogThemeColors("M3")).isEmpty()
    assertThat(remoteCatalogThemeColors("Google Sans Flex")).isEmpty()
  }

  /**
   * Coral and Teal are the single-role edits the sibling makes: primary + secondary, nothing else.
   */
  @Test
  fun `the single-role palettes move only primary and secondary`() {
    for (name in listOf("Coral", "Teal")) {
      assertThat(remoteCatalogThemeColors(name).keys)
        .containsExactly("WearM3.primary", "WearM3.secondary")
    }
  }

  /**
   * KotlinConf carries the fuller seed ramp — both families, containers and `on*` roles included.
   */
  @Test
  fun `KotlinConf carries the full primary and secondary ramp`() {
    val keys = remoteCatalogThemeColors("KotlinConf").keys

    assertThat(keys)
      .containsAtLeast(
        "WearM3.primary",
        "WearM3.primaryDim",
        "WearM3.primaryContainer",
        "WearM3.onPrimary",
        "WearM3.onPrimaryContainer",
        "WearM3.secondary",
        "WearM3.secondaryDim",
        "WearM3.secondaryContainer",
        "WearM3.onSecondary",
        "WearM3.onSecondaryContainer",
      )
  }

  /**
   * Coral and Teal must not move the face: a palette that also changed the type would make a
   * side-by-side against M3 a type *and* colour comparison. KotlinConf is the deliberate exception
   * — its identity is a palette *and* a type pairing, matching the Wear sibling.
   */
  @Test
  fun `only the type-moving themes name their own body face`() {
    assertThat(remoteCatalogFont("Google Sans Flex")).isEqualTo("Google Sans Flex")
    assertThat(remoteCatalogFont("KotlinConf")).isEqualTo("Inter")
    for (palette in listOf("M3", "Coral", "Teal")) {
      assertThat(remoteCatalogFont(palette)).isEqualTo("Roboto Flex")
    }
  }

  /** Only KotlinConf pairs a second face; everything else draws one family throughout. */
  @Test
  fun `only KotlinConf pairs a display face`() {
    assertThat(remoteCatalogDisplayFont("KotlinConf")).isEqualTo("JetBrains Mono")
    for (name in listOf("M3", "Coral", "Teal", "Google Sans Flex")) {
      assertThat(remoteCatalogDisplayFont(name)).isNull()
    }
  }
}
