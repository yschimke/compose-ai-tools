package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Test

class PreviewDataTest {

  private val json = Json {
    prettyPrint = true
    encodeDefaults = true
  }

  @Test
  fun `manifest serialization round-trips`() {
    val manifest =
      PreviewManifest(
        module = "app",
        variant = "debug",
        previews =
          listOf(
            PreviewInfo(
              id = "com.example.PreviewsKt.RedBoxPreview_Red Box",
              functionName = "RedBoxPreview",
              className = "com.example.PreviewsKt",
              sourceFile = "Previews.kt",
              params =
                PreviewParams(
                  name = "Red Box",
                  showBackground = true,
                  backgroundColor = 0xFFFF0000,
                ),
              captures =
                listOf(
                  Capture(renderOutput = "renders/com.example.PreviewsKt.RedBoxPreview_Red Box.png")
                ),
            )
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

  @Test
  fun `resource manifest serialization round-trips`() {
    val manifest =
      ResourceManifest(
        module = "app",
        variant = "debug",
        resources =
          listOf(
            ResourcePreview(
              id = "drawable/ic_compose_logo",
              type = ResourceType.VECTOR,
              sourceFiles =
                mapOf(
                  null to "src/main/res/drawable/ic_compose_logo.xml",
                  "night" to "src/main/res/drawable-night/ic_compose_logo.xml",
                ),
              captures =
                listOf(
                  ResourceCapture(
                    variant = ResourceVariant(qualifiers = "xhdpi"),
                    renderOutput = "renders/resources/drawable/ic_compose_logo_xhdpi.png",
                  ),
                  ResourceCapture(
                    variant = ResourceVariant(qualifiers = "night-xhdpi"),
                    renderOutput = "renders/resources/drawable/ic_compose_logo_night-xhdpi.png",
                  ),
                ),
            ),
            ResourcePreview(
              id = "mipmap/ic_launcher",
              type = ResourceType.ADAPTIVE_ICON,
              sourceFiles = mapOf("anydpi-v26" to "src/main/res/mipmap-anydpi-v26/ic_launcher.xml"),
              captures =
                listOf(
                  ResourceCapture(
                    variant = ResourceVariant(qualifiers = "xhdpi", shape = AdaptiveShape.CIRCLE),
                    renderOutput = "renders/resources/mipmap/ic_launcher_xhdpi_SHAPE_circle.png",
                    cost = RESOURCE_ADAPTIVE_COST,
                  )
                ),
            ),
          ),
        manifestReferences =
          listOf(
            ManifestReference(
              source = "src/main/AndroidManifest.xml",
              componentKind = "application",
              componentName = null,
              attributeName = "android:icon",
              resourceType = "mipmap",
              resourceName = "ic_launcher",
            ),
            ManifestReference(
              source = "src/main/AndroidManifest.xml",
              componentKind = "activity",
              componentName = "com.example.sampleandroid.MainActivity",
              attributeName = "android:icon",
              resourceType = "drawable",
              resourceName = "ic_settings",
            ),
          ),
      )

    val serialized = json.encodeToString(manifest)
    val deserialized = json.decodeFromString<ResourceManifest>(serialized)

    assertThat(deserialized).isEqualTo(manifest)
    assertThat(deserialized.resources).hasSize(2)
    assertThat(deserialized.manifestReferences).hasSize(2)
    assertThat(deserialized.resources[0].sourceFiles.keys).containsExactly(null, "night")
  }
}
