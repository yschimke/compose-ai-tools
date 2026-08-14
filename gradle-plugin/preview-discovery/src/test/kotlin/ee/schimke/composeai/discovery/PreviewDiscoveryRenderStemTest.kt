package ee.schimke.composeai.discovery

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Contract for render stems: `<readable>-<digest>`, where `<readable>` is the sanitised
 * function-plus-variant segment and `<digest>` is 8 hex chars of `sha256(preview.id)`.
 *
 * See `PreviewDiscovery.normalizeRenderOutputs` for the rationale. The invariants these tests pin
 * are: the charset never escapes `[A-Za-z0-9._-]`, a stem depends on nothing but its own preview,
 * and no two previews in a module ever land on the same file — including on case-insensitive
 * filesystems.
 */
class PreviewDiscoveryRenderStemTest {

  /** `<readable>-<8 hex>` with the digest delimited by the one character sanitisation can't emit. */
  private val stemShape = Regex("[A-Za-z0-9_]+-[0-9a-f]{8}")

  /**
   * Headline invariant: every stem is composed only of `[A-Za-z0-9_-]` (no spaces, parens, quotes,
   * or anything else a shell would have to quote, and nothing a URL would have to percent-encode).
   * Spaces in `@Preview(name = "Devices - Large Round")` make it into the `preview.id`; the rewrite
   * strips them at the sanitisation step.
   */
  @Test
  fun `every resolved stem is shell-safe and url-safe`() {
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
      assertThat(stem).matches(stemShape.pattern)
    }
    assertThat(stems.toSet()).hasSize(stems.size)
  }

  @Test
  fun `readable part is the function-and-variant segment - never the package or class`() {
    // The class and package never appear, however many previews share a function name: uniqueness
    // is the digest's job, so the readable part stays as short as a human would write it.
    val previews =
      listOf(
        preview("com.example.app.PreviewsKt.ActivityListPreview_Devices - Large Round"),
        preview("com.example.app.PreviewsKt.ButtonPreview_Devices - Large Round"),
      )

    val stems = PreviewDiscovery.resolveRenderStems(previews)

    assertThat(stems.map { it.substringBeforeLast('-') })
      .containsExactly("ActivityListPreview_Devices_Large_Round", "ButtonPreview_Devices_Large_Round")
      .inOrder()
  }

  @Test
  fun `collapses runs of separator-like characters into a single underscore`() {
    // ` - ` (space-dash-space) is a run of three "separator" characters in the source name.
    val stems = listOf(preview("com.example.PreviewsKt.Foo_Devices - Large Round")).let(
      PreviewDiscovery::resolveRenderStems
    )
    assertThat(stems.single().substringBeforeLast('-')).isEqualTo("Foo_Devices_Large_Round")
  }

  @Test
  fun `parens, quotes, and other shell-awkward characters all collapse with adjacent runs`() {
    val stems =
      PreviewDiscovery.resolveRenderStems(listOf(preview("com.example.PreviewsKt.Foo_tile light (light)")))
    assertThat(stems.single().substringBeforeLast('-')).isEqualTo("Foo_tile_light_light")
  }

  /**
   * The property the old shortest-unique-suffix walk lacked: a stem is a pure function of its own
   * preview. Adding an unrelated preview that happens to share a function name used to rename the
   * existing preview's PNG (`Chip_Light.png` → `PreviewsKt.Chip_Light.png`), which breaks
   * commit-pinned render URLs and makes base-vs-head diffing see a rename as delete + add.
   */
  @Test
  fun `a stem does not change when an unrelated preview is added to the module`() {
    val existing = preview("com.example.a.PreviewsKt.Chip_Light")
    val before = PreviewDiscovery.resolveRenderStems(listOf(existing))
    val after =
      PreviewDiscovery.resolveRenderStems(
        listOf(existing, preview("com.example.b.OtherKt.Chip_Light"))
      )

    assertThat(after[0]).isEqualTo(before.single())
    assertThat(after[1]).isNotEqualTo(after[0])
  }

  /** Reordering the manifest must not renumber anything — nothing is positional any more. */
  @Test
  fun `stems are stable under manifest reordering`() {
    val a = preview("com.example.PreviewsKt.Foo_Light")
    val b = preview("com.example.PreviewsKt.Bar_Dark")

    val forward = PreviewDiscovery.resolveRenderStems(listOf(a, b))
    val reversed = PreviewDiscovery.resolveRenderStems(listOf(b, a))

    assertThat(reversed).containsExactly(forward[1], forward[0]).inOrder()
  }

  /**
   * Two ids differing ONLY in characters the sanitiser collapses (underscore vs dash, dot vs
   * underscore) sanitise to a byte-identical readable part. The digest splits them.
   */
  @Test
  fun `distinct ids that sanitise identically still get distinct stems`() {
    val previews =
      listOf(
        preview("com.example.PreviewsKt.Foo_Pixel 8 (light)"),
        preview("com.example.PreviewsKt.Foo_Pixel_8_light"),
      )

    val stems = PreviewDiscovery.resolveRenderStems(previews)

    assertThat(stems.toSet()).hasSize(2)
    assertThat(stems.map { it.substringBeforeLast('-') }.toSet()).containsExactly("Foo_Pixel_8_light")
    for (stem in stems) assertThat(stem).matches(stemShape.pattern)
  }

  /**
   * Regression: the old positional tiebreaker minted `<stem>_<idx>` without checking it against the
   * real stems, so a preview genuinely named `Foo_bar_1` was silently overwritten by the
   * disambiguated form of a colliding pair. Two of three previews shared one PNG.
   */
  @Test
  fun `a disambiguated stem never collides with a preview genuinely named like the disambiguator`() {
    val previews =
      listOf(
        preview("com.example.PreviewsKt.Foo_bar"),
        preview("com.example.PreviewsKt.Foo-bar"),
        preview("com.example.PreviewsKt.Foo_bar_1"),
      )

    val stems = PreviewDiscovery.resolveRenderStems(previews)

    assertThat(stems.toSet()).hasSize(3)
  }

  /**
   * APFS and NTFS are case-insensitive: `Foo_Dark.png` and `Foo_dark.png` are one file there. The
   * ids differ, so the digests differ, so the filenames differ in a case-independent way.
   */
  @Test
  fun `previews differing only by case get stems that differ outside the case-folded part`() {
    val previews =
      listOf(preview("com.example.PreviewsKt.Foo_Dark"), preview("com.example.PreviewsKt.Foo_dark"))

    val stems = PreviewDiscovery.resolveRenderStems(previews)

    assertThat(stems.map { it.lowercase() }.toSet()).hasSize(2)
  }

  /**
   * Structural suffixes (`_animated`, `_curves`, `_PARAM_<n>`, `_SCROLL_<mode>`) are appended after
   * the whole stem. Because the digest sits between the readable part and the suffix, a preview
   * genuinely named `animated` can no longer collide with a sibling's Lottie sidecar.
   */
  @Test
  fun `a preview named like a structural suffix does not collide with a sibling's sidecar`() {
    val previews =
      listOf(preview("com.example.PreviewsKt.Logo_animated"), preview("com.example.PreviewsKt.Logo"))

    val stems = PreviewDiscovery.resolveRenderStems(previews)

    val realPreview = stems[0]
    val siblingSidecar = "${stems[1]}_animated"
    assertThat(realPreview).isNotEqualTo(siblingSidecar)
  }

  /**
   * `NAME_MAX` is 255 bytes on ext4/APFS/NTFS. An unbounded stem blew straight past it; the readable
   * part is now capped and the digest keeps the truncated form unique.
   */
  @Test
  fun `an absurdly long preview name is truncated but stays unique`() {
    val previews =
      listOf(
        preview("com.example.PreviewsKt." + "VeryLongPreviewName".repeat(20) + "A"),
        preview("com.example.PreviewsKt." + "VeryLongPreviewName".repeat(20) + "B"),
      )

    val stems = PreviewDiscovery.resolveRenderStems(previews)

    for (stem in stems) {
      assertThat(stem.length)
        .isAtMost(PreviewDiscovery.MAX_READABLE_STEM + 1 + PreviewDiscovery.RENDER_STEM_DIGEST_CHARS)
      assertThat(stem).matches(stemShape.pattern)
    }
    assertThat(stems.toSet()).hasSize(2)
  }

  /** Truncation must not leave a dangling `_` where the cut landed mid-run. */
  @Test
  fun `truncation does not leave a trailing separator on the readable part`() {
    val name = "A".repeat(PreviewDiscovery.MAX_READABLE_STEM) + " tail"
    val stems = PreviewDiscovery.resolveRenderStems(listOf(preview("com.example.PreviewsKt.$name")))

    assertThat(stems.single().substringBeforeLast('-')).doesNotMatch(".*_$")
  }

  /**
   * `CON.png` is unopenable on Windows — reserved device names apply even with an extension. The
   * unconditional digest suffix means no stem is ever a bare reserved name.
   */
  @Test
  fun `a preview named after a windows reserved device is not a bare reserved name`() {
    val reserved = listOf("CON", "NUL", "PRN", "AUX", "COM1", "LPT1")
    val previews = reserved.map { preview("com.example.PreviewsKt.$it") }

    val stems = PreviewDiscovery.resolveRenderStems(previews)

    for (stem in stems) {
      assertThat(reserved).doesNotContain(stem.substringBefore('.').uppercase())
    }
  }

  @Test
  fun `a dot in the preview name does not truncate the stem to a trailing fragment`() {
    // `@Preview(name = "Font scale 1.5x")` puts a dot in the variant suffix. The dot is NOT a
    // structural id separator, so the readable part must keep the full function-and-variant name
    // rather than collapsing to the fractional tail (`5x`) — `renderStem` takes the LAST segment.
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

    assertThat(stems.map { it.substringBeforeLast('-') })
      .containsExactly("FontScale150Preview_Font_scale_1_5x", "FontScale100Preview_Font_scale_1_0x")
      .inOrder()
  }

  @Test
  fun `distinct ids differing only by dot-vs-underscore in the name still get distinct stems`() {
    // Two variants on the same function whose names differ only by `.` vs `_` keep distinct ids
    // (the manifest dedups by id, so collapsing them would silently drop one).
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
    assertThat(stems.map { it.substringBeforeLast('-') }.toSet()).containsExactly("Foo_State_1_5")
  }

  /** An id whose every character sanitises away still needs a filename. */
  @Test
  fun `an id with no alphanumeric characters falls back to a named stem`() {
    val previews =
      listOf(PreviewInfo(id = "***", functionName = "***", className = ""))

    val stems = PreviewDiscovery.resolveRenderStems(previews)

    assertThat(stems.single()).matches(stemShape.pattern)
    assertThat(stems.single()).startsWith("preview-")
  }

  /**
   * The backstop expands both sides of a genuine digest tie. Exercised at a 2-char digest because
   * constructing an 8-char tie needs ~2^32 work; the logic under test is width-independent.
   */
  @Test
  fun `a genuine digest tie expands both tied stems`() {
    // These two ids collide on the first 2 hex chars of sha256 (verified pair).
    val previews =
      listOf(
        preview("com.example.PreviewsKt.Foo_Dark50"),
        preview("com.example.PreviewsKt.Foo_dark50"),
      )

    val stems = PreviewDiscovery.resolveRenderStems(previews, digestChars = 2)

    assertThat(stems.toSet()).hasSize(2)
    // Both sides carry the full-length digest, not just the second one.
    for (stem in stems) {
      assertThat(stem.substringAfterLast('-')).hasLength(PreviewDiscovery.FULL_DIGEST_CHARS)
    }
  }

  /**
   * Regression: the tie test groups case-folded. Two stems differing only by case address the same
   * file on APFS/NTFS, so a case-only pair that *also* ties on the digest must still be expanded —
   * a case-sensitive grouping would call them distinct and let one overwrite the other.
   */
  @Test
  fun `a case-only pair that also ties on the digest is still expanded`() {
    val previews =
      listOf(
        preview("com.example.PreviewsKt.Foo_Dark50"),
        preview("com.example.PreviewsKt.Foo_dark50"),
      )

    val stems = PreviewDiscovery.resolveRenderStems(previews, digestChars = 2)

    // The point: distinct *case-insensitively*, which is what the filesystem compares.
    assertThat(stems.map { it.lowercase() }.toSet()).hasSize(2)
  }

  /** A tie must not drag unrelated previews into the long form. */
  @Test
  fun `a digest tie leaves untied stems untouched`() {
    val bystander = preview("com.example.PreviewsKt.Unrelated_Preview")
    val alone = PreviewDiscovery.resolveRenderStems(listOf(bystander), digestChars = 2).single()

    val withTie =
      PreviewDiscovery.resolveRenderStems(
        listOf(
          preview("com.example.PreviewsKt.Foo_Dark50"),
          preview("com.example.PreviewsKt.Foo_dark50"),
          bystander,
        ),
        digestChars = 2,
      )

    assertThat(withTie[2]).isEqualTo(alone)
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
