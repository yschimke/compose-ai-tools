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

  @Test
  fun `assignBundleEntryIds leaves non-colliding ids untouched, in order`() {
    val map = assignBundleEntryIds(listOf("pkg.Foo_A B", "pkg.Bar_C D"))
    assertThat(map.values.toList()).containsExactly("pkg.Foo_A_B", "pkg.Bar_C_D").inOrder()
  }

  @Test
  fun `assignBundleEntryIds disambiguates two ids that sanitise to the same form`() {
    // `"A B"` and `"A_B"` are distinct preview ids (the manifest dedups by raw id) that both
    // sanitise to `Foo_A_B`. Without disambiguation their `previews/<id>.png` / `ir/<id>.rc` keys
    // would collide and one capture would silently overwrite the other. First claimant keeps the
    // clean form; the collision gets a numeric suffix.
    val map = assignBundleEntryIds(listOf("Foo_A B", "Foo_A_B"))
    assertThat(map.getValue("Foo_A B")).isEqualTo("Foo_A_B")
    assertThat(map.getValue("Foo_A_B")).isEqualTo("Foo_A_B_1")
    assertThat(map.values.toSet()).hasSize(2)
  }

  @Test
  fun `assignBundleEntryIds maps a repeated raw id to a single bundle id`() {
    val map = assignBundleEntryIds(listOf("pkg.Foo_A B", "pkg.Foo_A B"))
    assertThat(map).hasSize(1)
    assertThat(map.getValue("pkg.Foo_A B")).isEqualTo("pkg.Foo_A_B")
  }
}
