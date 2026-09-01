package ee.schimke.composeai.renderer

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins that renaming the Kotlin type `RenderPreviewDataProduct` -> [RenderPreviewArtifact] is
 * wire-neutral: an existing manifest whose JSON still uses the `dataProducts` field name
 * deserializes into the renamed type unchanged. The class name isn't serialized; only the field
 * name is, and that stays `dataProducts` for back-compat.
 */
class RenderManifestWireTest {
  private val json = Json { ignoreUnknownKeys = true }

  @Test
  fun `dataProducts field still decodes into RenderPreviewArtifact`() {
    val wire =
      """
      {
        "id": "com.example.Foo",
        "functionName": "Foo",
        "className": "com.example.PreviewsKt",
        "dataProducts": [
          { "kind": "render/scroll/long", "output": "data/foo.png", "cost": 4.0 }
        ]
      }
      """
        .trimIndent()

    val entry = json.decodeFromString(RenderPreviewEntry.serializer(), wire)

    assertEquals(1, entry.dataProducts.size)
    val artifact: RenderPreviewArtifact = entry.dataProducts.single()
    assertEquals("render/scroll/long", artifact.kind)
    assertEquals("data/foo.png", artifact.output)
    assertEquals(4.0f, artifact.cost)
  }

  @Test
  fun `Dragged interaction decodes without losing named seeds`() {
    val wire =
      """
      {
        "module": ":app",
        "variant": "debug",
        "previews": [{
          "id": "com.example.Dragged",
          "functionName": "Dragged",
          "className": "com.example.PreviewsKt",
          "overrides": {
            "name": "dragged-enabled",
            "seeds": [
              { "key": "enabled", "kind": "BOOLEAN", "raw": "true" }
            ],
            "interaction": "Dragged"
          },
          "captures": [{ "drag": { "targetIndex": 2 } }]
        }]
      }
      """
        .trimIndent()

    val entry = json.decodeFromString(RenderManifest.serializer(), wire).previews.single()

    val overrides = requireNotNull(entry.overrides)
    assertEquals("dragged-enabled", overrides.name)
    assertEquals(1, overrides.seeds.size)
    assertEquals("enabled", overrides.seeds.single().key)
    assertEquals("true", overrides.seeds.single().raw)
    assertNull(overrides.interaction)
    assertEquals(2, entry.captures.single().drag?.targetIndex)
  }
}
