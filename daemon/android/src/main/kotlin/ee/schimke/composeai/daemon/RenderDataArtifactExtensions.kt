package ee.schimke.composeai.daemon

import android.content.Context
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
   * The held [`androidx.activity.ComponentActivity`] the rule launched for this render. Threaded
   * to extensions that read activity-scoped state — `getIntent()` (deep-link routing audits),
   * `onBackPressedDispatcher.hasEnabledCallbacks()` (registered back callbacks). Robolectric's
   * `ActivityScenario` boots the activity with a default `MAIN`/`LAUNCHER` Intent and no extras,
   * so production renders typically see an empty intent — extensions handle that gracefully.
   */
  val HeldActivity: ExtensionContextKey<androidx.activity.ComponentActivity> =
    ExtensionContextKey(
      name = "render-data-artifact.heldActivity",
      type = androidx.activity.ComponentActivity::class.java,
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
}
