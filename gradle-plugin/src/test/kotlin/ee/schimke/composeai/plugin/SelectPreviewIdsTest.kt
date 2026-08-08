package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import ee.schimke.composeai.discovery.PreviewInfo
import org.gradle.api.GradleException
import org.junit.Test

/**
 * Unit tests for the preview-**id** filter (issue #2966), the per-fan-out-member counterpart of
 * [selectNamedPreviews].
 *
 * The fixture is the shape that motivates the filter: one `@Preview` function fanned out over
 * several themes. All three ids share `functionName`, so a name filter can only keep or drop the
 * whole set — which is why a design catalog deferring every palette but the primary could thin its
 * published bundle but not its render.
 */
class SelectPreviewIdsTest {

  private fun preview(id: String, functionName: String, pkg: String = "com.example.preview") =
    PreviewInfo(id = id, functionName = functionName, className = "$pkg.PreviewsKt")

  private val all =
    listOf(
      preview("FilledButton_Light", "FilledButton"),
      preview("FilledButton_Dark", "FilledButton"),
      preview("FilledButton_HighContrast", "FilledButton"),
      preview("OutlinedButton_Light", "OutlinedButton"),
      preview("OutlinedButton_Dark", "OutlinedButton"),
    )

  @Test
  fun `empty filter returns every preview`() {
    assertThat(selectPreviewIds(all, emptyList())).isEqualTo(all)
  }

  @Test
  fun `blank-only filter returns every preview`() {
    assertThat(selectPreviewIds(all, listOf("  ", ""))).isEqualTo(all)
  }

  @Test
  fun `glob selects one member per function across the whole module`() {
    // The catalog case: bake the light palette, leave the rest to the live preview server.
    val selected = selectPreviewIds(all, listOf("*_Light"))
    assertThat(selected.map { it.id }).containsExactly("FilledButton_Light", "OutlinedButton_Light")
  }

  @Test
  fun `id filter splits a single function's fan-out, which a name filter cannot`() {
    val byId = selectPreviewIds(all, listOf("FilledButton_Dark"))
    assertThat(byId.map { it.id }).containsExactly("FilledButton_Dark")
    // Contrast: the name filter keeps every member of the function, all three palettes.
    val byName = selectNamedPreviews(all, listOf("FilledButton"))
    assertThat(byName.map { it.id })
      .containsExactly("FilledButton_Light", "FilledButton_Dark", "FilledButton_HighContrast")
  }

  @Test
  fun `plain pattern matches as a substring`() {
    val selected = selectPreviewIds(all, listOf("Outlined"))
    assertThat(selected.map { it.id })
      .containsExactly("OutlinedButton_Light", "OutlinedButton_Dark")
  }

  @Test
  fun `several patterns are OR-ed`() {
    val selected = selectPreviewIds(all, listOf("FilledButton_Light", "OutlinedButton_Dark"))
    assertThat(selected.map { it.id }).containsExactly("FilledButton_Light", "OutlinedButton_Dark")
  }

  @Test
  fun `composes with the name filter, most-specific last`() {
    // What `--preview FilledButton --preview-id *_Light` does: the name filter runs first.
    val selected =
      selectPreviewIds(selectNamedPreviews(all, listOf("FilledButton")), listOf("*_Light"))
    assertThat(selected.map { it.id }).containsExactly("FilledButton_Light")
  }

  @Test
  fun `no match fails fast and lists available ids`() {
    // Same select-or-fail policy as the name filter: a stale spec or a typo'd glob must be loud,
    // not
    // a silent zero-preview render that surfaces later as a bundle of missing stickers.
    val thrown =
      try {
        selectPreviewIds(all, listOf("*_Sepia"))
        null
      } catch (e: GradleException) {
        e
      }
    assertThat(thrown).isNotNull()
    val message = thrown!!.message!!
    assertThat(message).contains("--preview-id matched no previews")
    assertThat(message).contains("'*_Sepia'")
    assertThat(message).contains("Available preview ids:")
    assertThat(message).contains("FilledButton_Light")
  }

  @Test
  fun `no match with no discovered previews explains the empty module`() {
    val thrown =
      try {
        selectPreviewIds(emptyList(), listOf("Anything"))
        null
      } catch (e: GradleException) {
        e
      }
    assertThat(thrown!!.message).contains("no discovered previews")
  }

  @Test
  fun `exclusion drops the named members and keeps everything else`() {
    // The deferral case: bake every primary, skip the dark palettes.
    val kept = excludePreviewIds(all, listOf("*_Dark"))
    assertThat(kept.map { it.id })
      .containsExactly("FilledButton_Light", "FilledButton_HighContrast", "OutlinedButton_Light")
  }

  @Test
  fun `exclusion is the only polarity that can express a deferred palette`() {
    // A component's untagged primary sticker carries no theme suffix, so a positive `*_Light`
    // filter
    // would drop it along with the deferred palettes — the failure exclusion exists to avoid.
    val withUntagged = all + preview("PlainCard", "PlainCard")
    assertThat(selectPreviewIds(withUntagged, listOf("*_Light")).map { it.id })
      .doesNotContain("PlainCard")
    assertThat(excludePreviewIds(withUntagged, listOf("*_Dark")).map { it.id })
      .contains("PlainCard")
  }

  @Test
  fun `an empty or blank exclusion list is a no-op`() {
    assertThat(excludePreviewIds(all, emptyList())).isEqualTo(all)
    assertThat(excludePreviewIds(all, listOf(" ", ""))).isEqualTo(all)
  }

  @Test
  fun `an exclusion matching nothing is a no-op, not a failure`() {
    // Fails safe: a stale pattern renders more than intended (caught by the publish) rather than
    // silently rendering none.
    assertThat(excludePreviewIds(all, listOf("*_Sepia"))).isEqualTo(all)
  }

  @Test
  fun `excluding every preview fails loudly`() {
    val thrown =
      try {
        excludePreviewIds(all, listOf("*Button*"))
        null
      } catch (e: GradleException) {
        e
      }
    assertThat(thrown).isNotNull()
    assertThat(thrown!!.message).contains("excluded every one of the 5 preview(s)")
    assertThat(thrown.message).contains("nothing would render")
  }

  @Test
  fun `exclusion applies after the id filter`() {
    val kept =
      excludePreviewIds(selectPreviewIds(all, listOf("FilledButton*")), listOf("*_HighContrast"))
    assertThat(kept.map { it.id }).containsExactly("FilledButton_Light", "FilledButton_Dark")
  }

  @Test
  fun `an anchored exclusion does not take the variants of the id it names`() {
    // The sharding bug (issue #3559): ids are hierarchical, so a base id is a substring of its own
    // fan-out members. Unanchored, excluding one shard's `FilledButton_Light` also deleted
    // `FilledButton_Light_VARIANT_off` — work the excluding shard was itself assigned.
    val previews =
      listOf(
        preview("FilledButton_Light", "FilledButton"),
        preview("FilledButton_Light_VARIANT_off", "FilledButton"),
        preview("FilledButton_Dark", "FilledButton"),
      )

    val unanchored = excludePreviewIds(previews, listOf("FilledButton_Light"))
    assertThat(unanchored.map { it.id }).containsExactly("FilledButton_Dark")

    val anchored = excludePreviewIds(previews, listOf("=FilledButton_Light"))
    assertThat(anchored.map { it.id })
      .containsExactly("FilledButton_Light_VARIANT_off", "FilledButton_Dark")
      .inOrder()
  }

  @Test
  fun `an anchored selection matches only the id it names`() {
    val previews =
      listOf(
        preview("FilledButton_Light", "FilledButton"),
        preview("FilledButton_Light_VARIANT_off", "FilledButton"),
      )
    assertThat(selectPreviewIds(previews, listOf("=FilledButton_Light")).map { it.id })
      .containsExactly("FilledButton_Light")
  }
}
