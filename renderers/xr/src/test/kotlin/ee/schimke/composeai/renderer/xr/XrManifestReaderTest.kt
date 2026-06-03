package ee.schimke.composeai.renderer.xr

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** [XrManifestReader] keeps only `XR_SUBSPACE` previews and pulls out class/function/id. */
class XrManifestReaderTest {

  @get:Rule val tempDir = TemporaryFolder()

  private fun manifest(json: String): File =
    File(tempDir.root, "previews.json").apply { writeText(json) }

  @Test
  fun filtersToXrSubspacePreviews() {
    val file =
      manifest(
        """
        {
          "module": "demo",
          "variant": "debug",
          "previews": [
            { "id": "a", "functionName": "ComposeOne", "className": "test.AKt",
              "params": { "kind": "COMPOSE" } },
            { "id": "spatial-1", "functionName": "MySpatial", "className": "test.XrKt",
              "params": { "kind": "XR_SUBSPACE" }, "captures": [ { "renderOutput": "x.png" } ] },
            { "id": "tile", "functionName": "ATile", "className": "test.TileKt",
              "params": { "kind": "TILE" } }
          ]
        }
        """
          .trimIndent()
      )

    val xr = XrManifestReader.xrPreviews(file)

    assertThat(xr).hasSize(1)
    assertThat(xr.single())
      .isEqualTo(XrManifestReader.XrPreview("spatial-1", "test.XrKt", "MySpatial"))
  }

  @Test
  fun returnsEmptyWhenNoPreviews() {
    val file = manifest("""{ "module": "demo", "variant": "debug", "previews": [] }""")
    assertThat(XrManifestReader.xrPreviews(file)).isEmpty()
  }
}
