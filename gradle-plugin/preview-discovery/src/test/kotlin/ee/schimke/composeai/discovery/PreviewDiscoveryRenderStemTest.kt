package ee.schimke.composeai.discovery

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PreviewDiscoveryRenderStemTest {

  /**
   * Headline invariant: every stem is composed only of `[A-Za-z0-9._]` (no spaces, dashes, parens,
   * quotes, or anything else a shell would have to quote). Spaces in `@Preview(name = "Devices -
   * Large Round")` make it into the `preview.id`, but the rewrite strips them at the per-segment
   * sanitisation step.
   */
  @Test
  fun `every resolved stem is shell-safe - only letters, digits, underscores, and dots`() {
    val previews =
      listOf(
        preview("com.example.PreviewsKt.ActivityListPreview_Devices - Large Round"),
        preview("com.example.PreviewsKt.ActivityListPreview_Devices - Small Round"),
        preview("com.example.PreviewsKt.ButtonPreview_Devices - Large Round"),
        preview("com.example.PreviewsKt.ButtonPreview_Devices - Small Round"),
        preview("com.example.PreviewsKt.BadButtonPreview_Bad Button (light)"),
        preview("com.example.PreviewsKt.Pixel8SystemUiPreview_Pixel 8"),
        preview("com.example.PreviewsKt.Pixel8SystemUiPreview_Pixel 8 - Night"),
      )

    val stems = PreviewDiscovery.resolveRenderStems(previews)

    for (stem in stems) {
      assertThat(stem).matches("[A-Za-z0-9._]+")
    }
    assertThat(stems.toSet().size).isEqualTo(stems.size)
  }

  @Test
  fun `function-and-variant alone is the stem when nothing else collides`() {
    // Headline change from the previous fix: no package, no class — the last sanitised segment
    // is enough to disambiguate everyone in the module, so we use just that. Matches the user
    // spec: `ActivityListPreview_Devices_Large_Round.png`.
    val previews =
      listOf(
        preview("com.example.app.PreviewsKt.ActivityListPreview_Devices - Large Round"),
        preview("com.example.app.PreviewsKt.ButtonPreview_Devices - Large Round"),
        preview("com.example.app.PreviewsKt.BadButtonPreview_Devices - Small Round"),
      )

    val stems = PreviewDiscovery.resolveRenderStems(previews)

    assertThat(stems)
      .containsExactly(
        "ActivityListPreview_Devices_Large_Round",
        "ButtonPreview_Devices_Large_Round",
        "BadButtonPreview_Devices_Small_Round",
      )
      .inOrder()
  }

  @Test
  fun `collapses runs of separator-like characters into a single underscore`() {
    // ` - ` (space-dash-space) is a run of three "separator" characters in the source name;
    // every old stem ended up with `Devices_-_Large_Round`. New stems collapse the run.
    val previews = listOf(preview("com.example.PreviewsKt.Foo_Devices - Large Round"))
    val stems = PreviewDiscovery.resolveRenderStems(previews)
    assertThat(stems).containsExactly("Foo_Devices_Large_Round")
  }

  @Test
  fun `parens, quotes, and other shell-awkward characters all collapse with adjacent runs`() {
    val previews = listOf(preview("com.example.PreviewsKt.Foo_tile light (light)"))
    val stems = PreviewDiscovery.resolveRenderStems(previews)
    assertThat(stems).containsExactly("Foo_tile_light_light")
  }

  @Test
  fun `prepends the class segment only when the function-and-variant alone collides`() {
    // Two `ActivityListPreview` functions in different `Kt` classes (and same variant suffix)
    // need the class prefix to disambiguate. Every other preview keeps its bare function-level
    // stem — the per-preview shortest-unique-suffix calc is local, not collective.
    val previews =
      listOf(
        preview("com.example.AmbientPreviewsKt.ActivityListPreview_Devices - Large Round"),
        preview("com.example.PreviewsKt.ActivityListPreview_Devices - Large Round"),
        preview("com.example.PreviewsKt.ButtonPreview_Devices - Large Round"),
      )

    val stems = PreviewDiscovery.resolveRenderStems(previews)

    assertThat(stems)
      .containsExactly(
        "AmbientPreviewsKt.ActivityListPreview_Devices_Large_Round",
        "PreviewsKt.ActivityListPreview_Devices_Large_Round",
        // No collision on this function name → stays at depth 1.
        "ButtonPreview_Devices_Large_Round",
      )
      .inOrder()
  }

  @Test
  fun `prepends the package segment only when the class prefix doesn't disambiguate`() {
    // Two `PreviewsKt.ActivityListPreview_*` in different packages — at depth 2 they collide,
    // at depth 3 (`<lastPkg>.PreviewsKt.ActivityListPreview_*`) they don't. Other previews
    // sharing a unique function name stay at depth 1.
    val previews =
      listOf(
        preview("com.example.first.PreviewsKt.ActivityListPreview_Same Variant"),
        preview("com.example.second.PreviewsKt.ActivityListPreview_Same Variant"),
        preview("com.example.first.PreviewsKt.UniqueOnePreview_Some Variant"),
      )

    val stems = PreviewDiscovery.resolveRenderStems(previews)

    assertThat(stems)
      .containsExactly(
        "first.PreviewsKt.ActivityListPreview_Same_Variant",
        "second.PreviewsKt.ActivityListPreview_Same_Variant",
        "UniqueOnePreview_Some_Variant",
      )
      .inOrder()
  }

  @Test
  fun `appends a numeric disambiguator when sanitisation maps two ids to the same form`() {
    // Pathological case: two distinct ids that differ ONLY in characters the sanitiser collapses
    // (underscore vs dash here). After sanitisation they're byte-identical; the per-suffix-depth
    // search never finds a unique form. The renders directory still has to stay collision-free,
    // so the second occurrence gets a `_<idx>` suffix.
    val previews =
      listOf(
        preview("com.example.PreviewsKt.Foo_Pixel 8 (light)"),
        preview("com.example.PreviewsKt.Foo_Pixel_8_light"),
      )

    val stems = PreviewDiscovery.resolveRenderStems(previews)

    assertThat(stems.toSet().size).isEqualTo(stems.size)
    for (stem in stems) {
      assertThat(stem).matches("[A-Za-z0-9._]+")
    }
    // First occurrence keeps the clean form; later occurrence carries its manifest index.
    assertThat(stems[0]).isEqualTo("Foo_Pixel_8_light")
    assertThat(stems[1]).isEqualTo("Foo_Pixel_8_light_1")
  }

  @Test
  fun `single-preview module - bare function-and-variant segment, no prefixes`() {
    val previews = listOf(preview("com.example.PreviewsKt.OnlyOne_Variant - With Space"))

    val stems = PreviewDiscovery.resolveRenderStems(previews)

    assertThat(stems).containsExactly("OnlyOne_Variant_With_Space")
  }

  @Test
  fun `a dot in the preview name does not truncate the stem to a trailing fragment`() {
    // `@Preview(name = "Font scale 1.5x")` puts a dot in the variant suffix. The dot is NOT a
    // structural id separator, so the stem must keep the full function-and-variant name rather than
    // collapsing to the unique fractional tail (`5x`). A sibling at 1.0x guards against the suffix
    // fold silently dropping segments.
    val previews =
      listOf(
        PreviewInfo(
          id = "com.example.PreviewsKt.FontScale150Preview_Font scale 1.5x",
          functionName = "FontScale150Preview",
          className = "com.example.PreviewsKt",
        ),
        PreviewInfo(
          id = "com.example.PreviewsKt.FontScale100Preview_Font scale 1.0x",
          functionName = "FontScale100Preview",
          className = "com.example.PreviewsKt",
        ),
      )

    val stems = PreviewDiscovery.resolveRenderStems(previews)

    assertThat(stems)
      .containsExactly("FontScale150Preview_Font_scale_1_5x", "FontScale100Preview_Font_scale_1_0x")
      .inOrder()
  }

  @Test
  fun `distinct ids differing only by dot-vs-underscore in the name still get distinct stems`() {
    // Two variants on the same function whose names differ only by `.` vs `_` keep distinct ids
    // (the manifest dedups by id, so collapsing them would silently drop one). They sanitise to the
    // same stem form, so the numeric disambiguator must split them on disk.
    val previews =
      listOf(
        PreviewInfo(
          id = "com.example.PreviewsKt.Foo_State 1.5",
          functionName = "Foo",
          className = "com.example.PreviewsKt",
        ),
        PreviewInfo(
          id = "com.example.PreviewsKt.Foo_State 1_5",
          functionName = "Foo",
          className = "com.example.PreviewsKt",
        ),
      )

    val stems = PreviewDiscovery.resolveRenderStems(previews)

    assertThat(stems.toSet()).hasSize(2)
    assertThat(stems[0]).isEqualTo("Foo_State_1_5")
    assertThat(stems[1]).isEqualTo("Foo_State_1_5_1")
  }

  @Test
  fun `empty input returns empty stems list`() {
    assertThat(PreviewDiscovery.resolveRenderStems(emptyList())).isEmpty()
  }

  private fun preview(id: String): PreviewInfo =
    PreviewInfo(
      id = id,
      functionName = id.substringAfterLast('.').substringBefore('_'),
      className = id.substringBeforeLast('.'),
    )
}
