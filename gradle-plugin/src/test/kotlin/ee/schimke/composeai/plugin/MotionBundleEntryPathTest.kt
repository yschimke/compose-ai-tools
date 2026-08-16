package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pins [motionBundleEntryPath] — the name a rendered `@InteractionPreview` / `@AnimatedPreview`
 * capture is packed under inside a bundle.
 *
 * The rule that matters is that a capture shares the **preview id** space with the still it
 * documents, so `previews/<id>.apng` sits beside `previews/<id>.png`. Everything downstream joins
 * those two by name: `catalog-motion.mjs` reads a capture's theme off the still sharing its stem,
 * and `catalog-motion-publish.mjs` names the published file after that still.
 *
 * Bundle pack originally keyed captures by the render's own leaf, which is `<readable>-<digest>`
 * (docs/RENDER_FILENAMES.md) and deliberately NOT the id — so both joins declined silently. Every
 * capture published with no theme (which pins it to *every* card of its component, so the light
 * card played the dark recording) at a path derived from the render digest rather than the sticker.
 * The bytes were in the bundle and the run was green; the catalog just never showed them.
 */
class MotionBundleEntryPathTest {

  private val id = "com.example.designcatalogm3.CatalogSelectionKt.SwitchOn_Dark"

  @Test
  fun `names a capture after its preview id, not the render leaf`() {
    assertThat(motionBundleEntryPath(id, "SwitchOn_Dark-d0f22b72.apng"))
      .isEqualTo("previews/$id.apng")
  }

  @Test
  fun `pairs with the still it documents on every character but the extension`() {
    val motion = motionBundleEntryPath(id, "SwitchOn_Dark-d0f22b72.apng")
    assertThat(motion?.removeSuffix(".apng")).isEqualTo("$BUNDLE_PREVIEWS_DIR/$id")
  }

  @Test
  fun `carries the interaction suffix when the function owns two motion outputs`() {
    assertThat(motionBundleEntryPath(id, "SwitchOn_Dark-d0f22b72_interaction.apng"))
      .isEqualTo("previews/${id}_interaction.apng")
  }

  @Test
  fun `carries the anim suffix`() {
    assertThat(motionBundleEntryPath(id, "SwitchOn_Dark-d0f22b72_anim.gif"))
      .isEqualTo("previews/${id}_anim.gif")
  }

  @Test
  fun `keeps a function's two captures apart`() {
    val interaction = motionBundleEntryPath(id, "Foo-1234_interaction.apng")
    val animation = motionBundleEntryPath(id, "Foo-1234_anim.gif")
    assertThat(interaction).isNotEqualTo(animation)
  }

  @Test
  fun `accepts gif as well as apng`() {
    assertThat(motionBundleEntryPath(id, "SwitchOn_Dark-d0f22b72.gif"))
      .isEqualTo("previews/$id.gif")
  }

  @Test
  fun `normalises an upper-case extension`() {
    assertThat(motionBundleEntryPath(id, "SwitchOn_Dark-d0f22b72.APNG"))
      .isEqualTo("previews/$id.apng")
  }

  @Test
  fun `declines a still — a PNG is not a motion capture`() {
    assertThat(motionBundleEntryPath(id, "SwitchOn_Dark-d0f22b72.png")).isNull()
  }

  @Test
  fun `declines a leaf with no extension at all`() {
    assertThat(motionBundleEntryPath(id, "SwitchOn_Dark-d0f22b72")).isNull()
  }

  @Test
  fun `a preview genuinely ending in the suffix word is not double-suffixed`() {
    // `Logo_animated` sanitises to a stem ending in neither `_interaction` nor `_anim`, so the
    // structural suffix is read off the leaf and nothing is invented for the id.
    assertThat(motionBundleEntryPath("com.example.Kt.Logo_animated", "Logo_animated-ab12.apng"))
      .isEqualTo("previews/com.example.Kt.Logo_animated.apng")
  }
}
