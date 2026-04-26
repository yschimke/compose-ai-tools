package ee.schimke.composeai.renderer

import kotlinx.serialization.Serializable

/**
 * Renderer-side mirror of the plugin's `ResourceManifest` / `ResourcePreview` / `ResourceCapture` /
 * `ResourceVariant` / `ManifestReference` types. Same split as [RenderManifest] vs the plugin's
 * `PreviewManifest` — keeps the renderer free of a hard dependency on `gradle-plugin/`.
 *
 * The renderer reads `resources.json` (path passed via the `composeai.resources.manifest` system
 * property) and writes one PNG / GIF per [RenderResourceCapture] into the directory pointed to by
 * `composeai.resources.outputDir`.
 */
@Serializable
enum class RenderResourceType {
  VECTOR,
  ANIMATED_VECTOR,
  ADAPTIVE_ICON,
}

@Serializable
enum class RenderAdaptiveShape {
  CIRCLE,
  ROUNDED_SQUARE,
  SQUARE,
  LEGACY,
}

@Serializable
data class RenderResourceVariant(
  val qualifiers: String? = null,
  val shape: RenderAdaptiveShape? = null,
)

@Serializable
data class RenderResourceCapture(
  val variant: RenderResourceVariant? = null,
  val renderOutput: String = "",
  val cost: Float = 1.0f,
)

@Serializable
data class RenderResourcePreview(
  val id: String,
  val type: RenderResourceType,
  val sourceFiles: Map<String, String> = emptyMap(),
  val captures: List<RenderResourceCapture> = emptyList(),
)

@Serializable
data class RenderManifestReference(
  val source: String,
  val componentKind: String,
  val componentName: String? = null,
  val attributeName: String,
  val resourceType: String,
  val resourceName: String,
)

@Serializable
data class RenderResourceManifest(
  val module: String,
  val variant: String,
  val resources: List<RenderResourcePreview> = emptyList(),
  val manifestReferences: List<RenderManifestReference> = emptyList(),
)
