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
  NINE_PATCH,
}

@Serializable
enum class RenderAdaptiveShape {
  CIRCLE,
  SQUIRCLE,
  ROUNDED_SQUARE,
  SQUARE,
}

@Serializable
enum class RenderAdaptiveStyle {
  FULL_COLOR,
  THEMED_LIGHT,
  THEMED_DARK,
  LEGACY,
}

/**
 * Renderer-side mirror of `NinePatchStretch`. Non-null on [RenderResourceType.NINE_PATCH] captures
 * — drives the target `(width, height)` the renderer passes to `NinePatchDrawable.setBounds`.
 */
@Serializable
enum class RenderNinePatchStretch {
  INTRINSIC,
  HORIZONTAL,
  VERTICAL,
  BOTH,
}

@Serializable
data class RenderResourceVariant(
  val qualifiers: String? = null,
  val shape: RenderAdaptiveShape? = null,
  val style: RenderAdaptiveStyle? = null,
  val stretch: RenderNinePatchStretch? = null,
  /**
   * `true` for an [RenderResourceType.ANIMATED_VECTOR] keyframe filmstrip capture (horizontal PNG
   * compositing one cell per fraction in [RenderResourceCapture.filmstripFractions]). `false` for
   * the per-frame GIF capture and for every non-AVD capture.
   */
  val filmstrip: Boolean = false,
)

@Serializable
data class RenderResourceCapture(
  val variant: RenderResourceVariant? = null,
  val renderOutput: String = "",
  val cost: Float = 1.0f,
  /**
   * Animation keyframe fractions for filmstrip captures (`variant.filmstrip == true`); each value
   * is a fraction of the resolved animation duration in `[0, 1]`. Empty on every other capture.
   */
  val filmstripFractions: List<Float> = emptyList(),
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
