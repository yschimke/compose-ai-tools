package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SpatialBundleEntriesTest {

  @get:Rule val tmp = TemporaryFolder()

  @Test
  fun `scene and image textures use the portable spatial bundle layout`() {
    val renders = tmp.newFolder("renders")
    val rawId = "com.example/Xr Preview"
    val scene = File(renders, sanitizeBundleEntryId(rawId)).apply { mkdirs() }
    File(scene, "scene.json")
      .writeText("""{"version":1,"panels":[{"texture":"panel.png"}],"orbiters":[]}""")
    File(scene, "panel.png").writeBytes(byteArrayOf(1, 2, 3))
    File(scene, "composite.png").writeBytes(byteArrayOf(4, 5, 6))
    File(scene, "debug.log").writeText("not public")
    File(scene, "script.js").writeText("not executable")

    val entries = spatialBundleEntries(renders, rawId, "xr-preview")

    assertThat(entries.keys)
      .containsExactly(
        "previews/xr-preview.spatial/scene.json",
        "previews/xr-preview.spatial/panel.png",
      )
    assertThat(entries.getValue("previews/xr-preview.spatial/panel.png"))
      .isEqualTo(byteArrayOf(1, 2, 3))
  }

  @Test
  fun `a preview without a scene publishes no spatial directory`() {
    val renders = tmp.newFolder("renders-empty")
    File(renders, sanitizeBundleEntryId("flat")).apply {
      mkdirs()
      resolve("panel.png").writeBytes(byteArrayOf(1))
    }

    assertThat(spatialBundleEntries(renders, "flat", "flat")).isEmpty()
  }
}
