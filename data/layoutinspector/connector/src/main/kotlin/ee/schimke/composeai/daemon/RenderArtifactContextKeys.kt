package ee.schimke.composeai.daemon

import androidx.compose.runtime.tooling.CompositionData
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

  /**
   * Protocol-level preview identifier (`spec.previewId`), when the caller supplied one. Distinct
   * from [OutputBaseName]; extensions that key their per-preview directory off the protocol id (so
   * `data/fetch` finds the artefact) prefer this and fall back to [OutputBaseName].
   */
  val PreviewId: ExtensionContextKey<String> =
    ExtensionContextKey(name = "render-data-artifact.previewId", type = String::class.java)

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

  /**
   * Composition slot tables captured for the render, the layout-inspector walk's second input
   * alongside [SemanticsRoot]. Desktop holds these directly after `scene.render()`; Android
   * snapshots them from its slot-table capture. Threading them here lets the shared
   * `layout/inspector` + `figma-svg` producers reach the same tree both backends' inline paths
   * used.
   */
  @Suppress("UNCHECKED_CAST")
  val SlotTables: ExtensionContextKey<List<CompositionData>> =
    ExtensionContextKey(
      name = "render-data-artifact.slotTables",
      type = List::class.java as Class<List<CompositionData>>,
    )

  /**
   * The captured frame PNG (`<outputDir>/<outputBaseName>.png`). Threaded so the
   * `compose/figma-svg` hybrid export can crop opaque-component `<image>` layers out of the frame
   * without re-rendering.
   */
  val OutputPng: ExtensionContextKey<File> =
    ExtensionContextKey(name = "render-data-artifact.outputPng", type = File::class.java)

  /**
   * Render font scale (`spec.fontScale`). Threaded so the `compose/figma-svg` export can size `sp`
   * text as `sp × density × fontScale`, matching the render whose geometry was measured with the
   * scaled text. Absent ⇒ 1.0.
   */
  val FontScale: ExtensionContextKey<Float> =
    ExtensionContextKey(name = "render-data-artifact.fontScale", type = Float::class.javaObjectType)

  /**
   * Whether the render's device masks its frame to a circle (round Wear). The `compose/figma-svg`
   * export must clip to the same circle so its full-frame background doesn't paint the corners the
   * render clipped away. Android derives it from the preview device; desktop leaves it `false`.
   */
  val RoundClip: ExtensionContextKey<Boolean> =
    ExtensionContextKey(
      name = "render-data-artifact.roundClip",
      type = Boolean::class.javaObjectType,
    )

  /**
   * The flat background colour the render actually painted behind the composable, as `#AARRGGBB` —
   * the resolution of `@Preview(showBackground = …, backgroundColor = …)` (and the per-render
   * "crisp outline" clear-background override) that the backend already computes for the PNG.
   * Absent when the render drew on transparency, which is the common component-preview case.
   *
   * The `compose/figma-svg` export paints it as the bottom layer so the vector matches the raster
   * (issue #2884): a Wear device preview declaring `showBackground = true, backgroundColor =
   * 0xFF000000` previously exported with its round clip intact but a transparent canvas, losing the
   * black watch face the PNG shows.
   */
  val PreviewBackground: ExtensionContextKey<String> =
    ExtensionContextKey(name = "render-data-artifact.previewBackground", type = String::class.java)
}
