package ee.schimke.composeai.daemon

import androidx.compose.ui.semantics.SemanticsNode
import ee.schimke.composeai.data.render.extensions.ExtensionContextKey
import java.io.File

/**
 * Portable subset of the post-capture context keys a render engine populates on
 * [ee.schimke.composeai.data.render.extensions.ExtensionPostCaptureContext.data] before invoking a
 * [ee.schimke.composeai.data.render.extensions.PostCaptureProcessor].
 *
 * These live in this Compose-Multiplatform-JVM module (not the Android daemon) so both the Android
 * and Desktop render engines — and the shared `PostCaptureProcessor` adapters over the producers in
 * this module — read the *same* key instances. The Android-only keys (`HeldActivity`,
 * `LayoutInspectorPreviewContext`, …) stay in the Android daemon's `RenderDataArtifactContextKeys`,
 * which delegates its portable keys here so there is a single source of truth. Key equality is
 * by-value (name + type), so populating one and reading the other resolves regardless.
 */
object RenderArtifactContextKeys {
  /** Per-preview data-product output root (`<rootDir>/<previewId>/<file>`). */
  val RootDir: ExtensionContextKey<File> =
    ExtensionContextKey(name = "render-data-artifact.rootDir", type = File::class.java)

  /**
   * File-system base name the producer keys its per-preview directory off — the renderer-generated
   * output name rather than the protocol-level previewId.
   */
  val OutputBaseName: ExtensionContextKey<String> =
    ExtensionContextKey(name = "render-data-artifact.outputBaseName", type = String::class.java)

  /** Captured root semantics node for the rendered preview. */
  val SemanticsRoot: ExtensionContextKey<SemanticsNode> =
    ExtensionContextKey(
      name = "render-data-artifact.semanticsRoot",
      type = SemanticsNode::class.java,
    )

  /**
   * Render density (`spec.density`, dp = px / density). Threaded so producers that resolve a
   * percent-based corner radius (`CircleShape`) against a node's measured px size can express it in
   * dp (issue #1908).
   */
  val Density: ExtensionContextKey<Float> =
    ExtensionContextKey(name = "render-data-artifact.density", type = Float::class.javaObjectType)
}
