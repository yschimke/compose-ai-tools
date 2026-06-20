package ee.schimke.composeai.renderer

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
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
}
