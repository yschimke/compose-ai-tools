package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pins [sanitizeBundleEntryId] — the mapping from a preview `id` to the form used for its file
 * names inside a bundle (`previews/<id>.png`, `ir/<id>.rc`, and the ids in the bundle's own
 * `bundle.json` / `previews.json`). A `@Preview(name = "Image Widget Squircle")` yields an id with
 * spaces; the pack step used to copy it verbatim, so the bundle entries alone carried spaces while
 * the on-disk renders were already collapsed. This makes the bundle match the renderer's own
 * `[^A-Za-z0-9._-]` → `_` per-character substitution so entry names, the ids readers reconstruct
 * them from, and both manifests all agree — and nothing a shell or URL would have to quote
 * survives.
 */
class SanitizeBundleEntryIdTest {

  @Test
  fun `collapses spaces to underscores`() {
    assertThat(sanitizeBundleEntryId("ImageWidgetSquirclePreview_Image Widget Squircle"))
      .isEqualTo("ImageWidgetSquirclePreview_Image_Widget_Squircle")
  }

  @Test
  fun `preserves dots and dashes`() {
    // Dots never need quoting and keep dot-vs-underscore-distinct ids distinct (the manifest dedups
    // by id); dashes are shell- and URL-safe too. Both must survive untouched.
    assertThat(sanitizeBundleEntryId("com.example.FooKt.Bar_Font scale 1.5x"))
      .isEqualTo("com.example.FooKt.Bar_Font_scale_1.5x")
    assertThat(sanitizeBundleEntryId("Foo_wearos-small-round")).isEqualTo("Foo_wearos-small-round")
  }

  @Test
  fun `replaces every shell- or URL-awkward character`() {
    assertThat(sanitizeBundleEntryId("Foo_tile (light) \"a/b\""))
      .isEqualTo("Foo_tile__light___a_b_")
  }

  @Test
  fun `is idempotent on an already-clean id`() {
    val clean = "pkg.Foo_Devices_Large_Round"
    assertThat(sanitizeBundleEntryId(clean)).isEqualTo(clean)
    assertThat(sanitizeBundleEntryId(sanitizeBundleEntryId("Foo_A B")))
      .isEqualTo(sanitizeBundleEntryId("Foo_A B"))
  }
}
