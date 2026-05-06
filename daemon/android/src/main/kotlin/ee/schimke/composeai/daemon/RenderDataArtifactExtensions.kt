package ee.schimke.composeai.daemon

import android.content.Context
import android.view.View
import androidx.compose.ui.semantics.SemanticsNode
import ee.schimke.composeai.data.render.PreviewContext
import ee.schimke.composeai.data.render.extensions.ExtensionContextKey
import ee.schimke.composeai.data.render.extensions.PlannedDataExtension
import java.io.File

/**
 * Per-render builder for "always-on" data extensions (fonts, resources, i18n).
 *
 * Distinct from [PreviewOverrideExtensions] in two ways:
 * - Extensions returned here always run, regardless of `renderNow.overrides`.
 * - The factory is invoked per render with the render-time platform [Context], so the extension
 *   instance can stand up its recorder + `CompositionLocal` install before composition starts.
 *
 * The render engine threads the resulting list through the Compose data-extension pipeline (for
 * any [ee.schimke.composeai.data.render.extensions.compose.AroundComposableHook] members) and then
 * iterates the same list for [ee.schimke.composeai.data.render.extensions.PostCaptureProcessor]
 * members during the post-capture pass — so one extension class can both install a recording
 * `CompositionLocal` during composition and write its typed artifact after capture.
 */
fun interface RenderDataArtifactExtensionFactory {
  fun create(context: Context): PlannedDataExtension
}

class RenderDataArtifactExtensions(val factories: List<RenderDataArtifactExtensionFactory>) {
  fun build(context: Context): List<PlannedDataExtension> = factories.map { it.create(context) }

  companion object {
    val Empty: RenderDataArtifactExtensions = RenderDataArtifactExtensions(emptyList())
  }
}

/**
 * Typed keys the render engine populates on [ee.schimke.composeai.data.render.extensions
 * .ExtensionPostCaptureContext.data] before invoking each always-on data extension. Lets the
 * extensions reach the per-preview output directory, the file-system base name, the requested
 * locale tag, and the captured semantics root through the same typed-key pattern other
 * post-capture extensions already use.
 */
object RenderDataArtifactContextKeys {
  /** Per-preview data-product output root (`<dataDir>/<previewId>/<file>`). */
  val RootDir: ExtensionContextKey<File> =
    ExtensionContextKey(name = "render-data-artifact.rootDir", type = File::class.java)

  /**
   * File-system base name (`spec.outputBaseName`) — used by extensions that key their per-preview
   * directory off the renderer-generated output name rather than the protocol-level previewId.
   */
  val OutputBaseName: ExtensionContextKey<String> =
    ExtensionContextKey(name = "render-data-artifact.outputBaseName", type = String::class.java)

  /**
   * Protocol-level preview identifier (`spec.previewId`), if the caller supplied one. Distinct
   * from [OutputBaseName]; some extensions (fonts) historically prefer this when present and fall
   * back to the base name otherwise.
   */
  val PreviewId: ExtensionContextKey<String> =
    ExtensionContextKey(name = "render-data-artifact.previewId", type = String::class.java)

  /** BCP-47 locale tag the render was performed with, when the request specified one. */
  val RenderedLocale: ExtensionContextKey<String> =
    ExtensionContextKey(name = "render-data-artifact.renderedLocale", type = String::class.java)

  /** Captured root semantics node for the rendered preview. */
  val SemanticsRoot: ExtensionContextKey<SemanticsNode> =
    ExtensionContextKey(
      name = "render-data-artifact.semanticsRoot",
      type = SemanticsNode::class.java,
    )

  /**
   * Pre-built [PreviewContext] for the layout-inspector data product. Carries the
   * captured slot tables, semantics root, device dimensions, and render-mode metadata that
   * `LayoutInspectorDataProducer.writeArtifacts` consumes — assembled once by the render engine
   * and shared with any extension that needs the same view of the rendered preview.
   */
  val LayoutInspectorPreviewContext: ExtensionContextKey<PreviewContext> =
    ExtensionContextKey(
      name = "render-data-artifact.layoutInspectorPreviewContext",
      type = PreviewContext::class.java,
    )

  /**
   * The Android `View` backing `ViewRootForTest` after capture. Populated only when the render
   * ran in a11y mode; extensions that consume this key should treat its absence as "accessibility
   * data not collected for this render" rather than fail. This key's presence doubles as the
   * gate that replaces the old `if (runAccessibility) { ... }` wrapper around the inline
   * accessibility sidecar block.
   */
  val AccessibilityViewRoot: ExtensionContextKey<View> =
    ExtensionContextKey(
      name = "render-data-artifact.accessibilityViewRoot",
      type = View::class.java,
    )

  /** Render-time density in dp/px (`spec.density`). */
  val RenderDensity: ExtensionContextKey<Float> =
    ExtensionContextKey(
      name = "render-data-artifact.renderDensity",
      type = Float::class.javaObjectType,
    )

  /** Path to the just-captured PNG. Used by sidecars that overlay onto the preview image. */
  val OutputPngFile: ExtensionContextKey<File> =
    ExtensionContextKey(name = "render-data-artifact.outputPngFile", type = File::class.java)

  /** True when the render qualifier set "round" was applied (Wear OS round screens). */
  val IsRoundScreen: ExtensionContextKey<Boolean> =
    ExtensionContextKey(
      name = "render-data-artifact.isRoundScreen",
      type = Boolean::class.javaObjectType,
    )

  /**
   * Legacy [ImageProcessor] list configured on the render engine. Only consulted by extensions
   * that interop with the pre-extension `ImageProcessor` surface; defaults to empty when not
   * populated.
   */
  val ImageProcessors: ExtensionContextKey<List<ImageProcessor>> =
    @Suppress("UNCHECKED_CAST")
    ExtensionContextKey(
      name = "render-data-artifact.imageProcessors",
      type = List::class.java as Class<List<ImageProcessor>>,
    )
}
