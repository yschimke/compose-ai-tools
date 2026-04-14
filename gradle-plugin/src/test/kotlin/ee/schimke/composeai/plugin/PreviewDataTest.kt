package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Test

class PreviewDataTest {

    private val json = Json { prettyPrint = true; encodeDefaults = true }

    @Test
    fun `manifest serialization round-trips`() {
        val manifest = PreviewManifest(
            module = "app",
            variant = "debug",
            previews = listOf(
                PreviewInfo(
                    id = "com.example.PreviewsKt.RedBoxPreview_Red Box",
                    functionName = "RedBoxPreview",
                    className = "com.example.PreviewsKt",
                    sourceFile = "Previews.kt",
                    params = PreviewParams(
                        name = "Red Box",
                        showBackground = true,
                        backgroundColor = 0xFFFF0000,
                    ),
                    renderOutput = "renders/com.example.PreviewsKt.RedBoxPreview_Red Box.png",
                ),
            ),
        )

        val serialized = json.encodeToString(manifest)
        val deserialized = json.decodeFromString<PreviewManifest>(serialized)

        assertThat(deserialized).isEqualTo(manifest)
        assertThat(deserialized.previews).hasSize(1)
        assertThat(deserialized.previews[0].params.backgroundColor).isEqualTo(0xFFFF0000)
    }

    @Test
    fun `default params have sensible values`() {
        val params = PreviewParams()
        assertThat(params.widthDp).isNull()
        assertThat(params.heightDp).isNull()
        assertThat(params.fontScale).isEqualTo(1.0f)
        assertThat(params.showBackground).isFalse()
        assertThat(params.backgroundColor).isEqualTo(0L)
    }
}
