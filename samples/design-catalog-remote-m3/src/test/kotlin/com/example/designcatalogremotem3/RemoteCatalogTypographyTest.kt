@file:Suppress("RestrictedApiAndroidX")

package com.example.designcatalogremotem3

import androidx.compose.remote.creation.compose.text.RemoteFontFamily
import androidx.wear.compose.remote.material3.RemoteTypography
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pins what the declared typeface themes move — and, just as importantly, what they leave alone.
 *
 * A Remote Compose document reaches a typeface either through a **built-in family id** (`0 =
 * default`, `1 = sans-serif`, `2 = serif`, `3 = monospace`) or by **naming a family**. These themes
 * are only allowed to move the first: the named-family stickers exist to keep the `Named(…)` and
 * font-variation-axis paths rendered and diffed across the five player lanes, so a theme that
 * reached them would quietly delete that coverage.
 */
class RemoteCatalogTypographyTest {

  @Test
  fun `a theme re-points the built-in default family at a google-namespaced family`() {
    for (family in REMOTE_THEME_FONTS) {
      val typography = remoteCatalogTypography(family)
      val expected = RemoteFontFamily.Named("google:$family")

      assertThat(typography.bodyLarge.fontFamily).isEqualTo(expected)
      assertThat(typography.titleLarge.fontFamily).isEqualTo(expected)
      assertThat(typography.labelSmall.fontFamily).isEqualTo(expected)
    }
  }

  @Test
  fun `the stock typography leaves the built-in default family in place`() {
    // The un-themed render must stay on built-in id 0 — installing no theme is what keeps
    // `composePreviewRenderAll` byte-for-byte what it was before the themes existed.
    val stock = RemoteTypography()

    assertThat(stock.bodyLarge.fontFamily).isAnyOf(null, RemoteFontFamily.Default)
  }

  @Test
  fun `themes are declared for exactly the two catalog faces`() {
    assertThat(REMOTE_THEME_FONTS).containsExactly("Roboto Flex", "Google Sans Flex").inOrder()
  }
}
