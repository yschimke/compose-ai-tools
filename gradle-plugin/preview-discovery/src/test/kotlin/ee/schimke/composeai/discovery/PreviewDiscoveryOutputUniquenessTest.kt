package ee.schimke.composeai.discovery

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * `enforceOutputUniqueness` is the manifest-wide backstop for render output paths.
 *
 * Stem resolution only covers the annotation-derived previews; Lottie/SVG assets, the token
 * catalogs, activities and app tours are all appended afterwards with literal stems, and several
 * land in the same `renders/` directory. These tests pin that the assembled manifest never contains
 * two entries claiming one file — including case-insensitively, which is what APFS and NTFS compare.
 */
class PreviewDiscoveryOutputUniquenessTest {

  private fun preview(
    id: String,
    vararg renderOutputs: String,
    dataProducts: List<String> = emptyList(),
  ): PreviewInfo =
    PreviewInfo(
      id = id,
      functionName = id.substringAfterLast('.'),
      className = id.substringBeforeLast('.', missingDelimiterValue = ""),
      captures = renderOutputs.map { Capture(renderOutput = it) },
      dataProducts = dataProducts.map { PreviewDataProduct(kind = "render/test", output = it) },
    )

  private fun outputs(previews: List<PreviewInfo>): List<String> =
    previews.flatMap { p -> p.captures.map { it.renderOutput } + p.dataProducts.map { it.output } }

  /** The reported defect: an annotation stem colliding with an appended Lottie asset's stem. */
  @Test
  fun `an annotation preview colliding with a lottie asset is split apart`() {
    val previews =
      listOf(
        preview("com.example.PreviewsKt.lottie__Foo", "renders/lottie__Foo-abcd1234.png"),
        preview("lottie__Foo-abcd1234", "renders/lottie__Foo-abcd1234.png"),
      )

    val result = PreviewDiscovery.enforceOutputUniqueness(previews)

    assertThat(outputs(result).toSet()).hasSize(2)
    // Ids are identity and must survive untouched — only the on-disk path moves.
    assertThat(result.map { it.id }).isEqualTo(previews.map { it.id })
  }

  /** APFS/NTFS fold case, so paths differing only by case are one file and must be split. */
  @Test
  fun `paths differing only by case are treated as colliding`() {
    val previews =
      listOf(
        preview("com.example.PreviewsKt.Foo", "renders/Widget_Dark.png"),
        preview("svg__widget_dark", "renders/widget_dark.png"),
      )

    val result = PreviewDiscovery.enforceOutputUniqueness(previews)

    assertThat(outputs(result).map { it.lowercase() }.toSet()).hasSize(2)
  }

  /** A collision must not drag unrelated entries onto new filenames. */
  @Test
  fun `previews that collide with nothing keep their exact paths`() {
    val bystander = preview("com.example.PreviewsKt.Untouched", "renders/Untouched-11112222.png")
    val previews =
      listOf(
        preview("com.example.PreviewsKt.Dup", "renders/Clash.png"),
        preview("lottie__Clash", "renders/Clash.png"),
        bystander,
      )

    val result = PreviewDiscovery.enforceOutputUniqueness(previews)

    assertThat(result[2].captures.single().renderOutput)
      .isEqualTo("renders/Untouched-11112222.png")
  }

  /** Order must not decide who gets renamed — the outcome is a function of the ids. */
  @Test
  fun `the result is stable under manifest reordering`() {
    val a = preview("com.example.PreviewsKt.Alpha", "renders/Clash.png")
    val b = preview("lottie__Clash", "renders/Clash.png")

    val forward = PreviewDiscovery.enforceOutputUniqueness(listOf(a, b))
    val reversed = PreviewDiscovery.enforceOutputUniqueness(listOf(b, a))

    assertThat(reversed.map { it.id to outputs(listOf(it)) }.reversed())
      .isEqualTo(forward.map { it.id to outputs(listOf(it)) })
  }

  /** The digest goes before the extension, and multi-dot sidecars keep their full suffix. */
  @Test
  fun `the disambiguator lands before the extension including multi-dot sidecars`() {
    val previews =
      listOf(
        preview("com.example.PreviewsKt.A", "renders/Clash.raw.png"),
        preview("lottie__Clash", "renders/Clash.raw.png"),
      )

    val result = PreviewDiscovery.enforceOutputUniqueness(previews)

    for (path in outputs(result)) {
      assertThat(path).endsWith(".raw.png")
      assertThat(path).matches("renders/Clash-[0-9a-f]{8}\\.raw\\.png")
    }
    assertThat(outputs(result).toSet()).hasSize(2)
  }

  /** Data-product outputs share the namespace with captures and must be checked too. */
  @Test
  fun `a data product colliding with a capture is split apart`() {
    val previews =
      listOf(
        preview("com.example.PreviewsKt.A", "data/render-scroll-gif/Clash.gif"),
        preview("com.example.PreviewsKt.B", dataProducts = listOf("data/render-scroll-gif/Clash.gif")),
      )

    val result = PreviewDiscovery.enforceOutputUniqueness(previews)

    assertThat(outputs(result).toSet()).hasSize(2)
  }

  /** Every capture of a re-stemmed preview moves together, so its fan-out stays internally consistent. */
  @Test
  fun `all captures of a colliding preview are retagged consistently`() {
    val previews =
      listOf(
        preview("com.example.PreviewsKt.A", "renders/Clash.png", "renders/Clash_SCROLL_end.png"),
        preview("lottie__Clash", "renders/Clash.png"),
      )

    val result = PreviewDiscovery.enforceOutputUniqueness(previews)

    // Each leaf is tagged before its own extension, so a structural `_SCROLL_end` suffix stays
    // attached to the stem it belongs to and both captures carry the same digest.
    val stems = result[0].captures.map { it.renderOutput.substringAfterLast('/') }
    val digest = stems[0].removePrefix("Clash-").removeSuffix(".png")
    assertThat(digest).matches("[0-9a-f]{8}")
    assertThat(stems[1]).isEqualTo("Clash_SCROLL_end-$digest.png")
  }

  /**
   * The trap the old positional `_<idx>` tiebreaker fell into: the disambiguated name must not
   * itself land on a path some untouched preview already owns. Here the retag of `Clash` is
   * pre-empted by a third preview genuinely occupying that exact path, so the pass must escalate
   * rather than hand two previews the same file.
   */
  @Test
  fun `a retag that would collide with an untouched preview escalates instead`() {
    val clash = preview("com.example.PreviewsKt.A", "renders/Clash.png")
    val digest = PreviewDiscovery.renderStem(clash).substringAfterLast('-')
    val previews =
      listOf(
        clash,
        preview("lottie__Clash", "renders/Clash.png"),
        // Sits exactly where `clash`'s short-digest retag would land.
        preview("com.example.PreviewsKt.Squatter", "renders/Clash-$digest.png"),
      )

    val result = PreviewDiscovery.enforceOutputUniqueness(previews)

    assertThat(outputs(result).map { it.lowercase() }.toSet()).hasSize(3)
  }

  @Test
  fun `a manifest with no collisions is returned untouched`() {
    val previews =
      listOf(
        preview("com.example.PreviewsKt.A", "renders/A-11112222.png"),
        preview("lottie__B", "renders/lottie__B.png"),
      )

    assertThat(PreviewDiscovery.enforceOutputUniqueness(previews)).isEqualTo(previews)
  }

  @Test
  fun `a single preview and an empty manifest are no-ops`() {
    assertThat(PreviewDiscovery.enforceOutputUniqueness(emptyList())).isEmpty()
    val one = listOf(preview("com.example.PreviewsKt.A", "renders/A.png"))
    assertThat(PreviewDiscovery.enforceOutputUniqueness(one)).isEqualTo(one)
  }

  /** Previews carrying no output paths at all must not be mistaken for colliding on "". */
  @Test
  fun `previews with no outputs are ignored rather than colliding on the empty path`() {
    val previews =
      listOf(
        preview("com.example.PreviewsKt.A"),
        preview("com.example.PreviewsKt.B"),
        preview("com.example.PreviewsKt.C", "renders/C.png"),
      )

    assertThat(PreviewDiscovery.enforceOutputUniqueness(previews)).isEqualTo(previews)
  }
}
