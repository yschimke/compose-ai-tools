package ee.schimke.composeai.daemon

import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsPayload
import ee.schimke.composeai.data.render.extensions.DataExtensionConstraints
import ee.schimke.composeai.data.render.extensions.DataExtensionHookKind
import ee.schimke.composeai.data.render.extensions.DataExtensionId
import ee.schimke.composeai.data.render.extensions.DataExtensionPhase
import ee.schimke.composeai.data.render.extensions.DataExtensionTarget
import ee.schimke.composeai.data.render.extensions.ExtensionPostCaptureContext
import ee.schimke.composeai.data.render.extensions.PostCaptureProcessor

/**
 * Always-on post-capture extension that writes the `compose/figma-svg` layered SVG for the rendered
 * preview. Combines the two trees the engine already captures: the layout tree (via
 * [RenderDataArtifactContextKeys.LayoutInspectorPreviewContext] — the composable names + container
 * tokens) and the semantics tree (via [RenderDataArtifactContextKeys.SemanticsRoot] — the editable
 * text). Mirrors [LayoutInspectorExtension] + [ComposeSemanticsWireframeExtension]; all three read
 * the same captured frame, so the export stays in lock-step with the JSON snapshots and the
 * wireframe.
 */
class ComposeFigmaSvgExtension : PostCaptureProcessor {
  override val id: DataExtensionId = ID
  override val hooks: Set<DataExtensionHookKind> = setOf(DataExtensionHookKind.AfterCapture)
  override val constraints: DataExtensionConstraints =
    DataExtensionConstraints(phase = DataExtensionPhase.Capture)
  override val targets: Set<DataExtensionTarget> = setOf(DataExtensionTarget.Android)

  override fun process(context: ExtensionPostCaptureContext) {
    val rootDir = context.require(RenderDataArtifactContextKeys.RootDir)
    val outputBaseName = context.require(RenderDataArtifactContextKeys.OutputBaseName)
    // Key off the protocol previewId when present, matching the file-backed registry lookup and the
    // sibling wireframe extension so `data/fetch` finds the SVG.
    val previewId = context.get(RenderDataArtifactContextKeys.PreviewId) ?: outputBaseName
    val previewContext =
      context.require(RenderDataArtifactContextKeys.LayoutInspectorPreviewContext)
    val density = context.get(RenderDataArtifactContextKeys.Density) ?: 1f
    val fontScale = context.get(RenderDataArtifactContextKeys.FontScale) ?: 1f
    val layout = LayoutInspectorDataProducer.buildPayload(previewContext, density) ?: return
    val semantics: ComposeSemanticsPayload? =
      context.get(RenderDataArtifactContextKeys.SemanticsRoot)?.let {
        ComposeSemanticsDataProducer.buildPayload(it, density)
      }
    // The captured frame PNG, when the engine threaded it, turns on hybrid raster export: opaque
    // components become `<image>` layers backed by a background-free crop of the frame.
    val frameImage = context.get(RenderDataArtifactContextKeys.OutputPng)
    ComposeFigmaSvgDataProducer.writeSvg(
      rootDir = rootDir,
      previewId = previewId,
      layout = layout,
      semantics = semantics,
      density = density,
      // Size sp text at the render's font scale (setFontScale) so the vector matches the render.
      fontScale = fontScale,
      frameImage = frameImage,
      // Embed the real (Google-downloadable) face so `<text>` renders faithfully — parity with the
      // desktop export. On Android the render itself is Roboto, so the embedded face is the exact
      // match. Opt-in; reuses the renderer's own font cache dir / offline switch.
      fontResolver = figmaFontResolver(),
      // A round Wear device screen is rendered through Roborazzi's `applyDeviceCrop` (the same
      // `isRound` condition), which masks the frame to a circle — so the export must mask to that
      // same circle, else its full-frame background paints the corners the render clips away.
      roundClip = previewContext.device.isRound,
    )
  }

  /**
   * The Google-Fonts WOFF2 resolver for the export, or null when embedding is off. On when
   * `composeai.figma.embedFonts=true` — the plugin forwards that flag into the daemon JVM (see
   * `AndroidPreviewClasspath.buildSystemProperties`). Unlike desktop this doesn't also honour the
   * fidelity flag: the fidelity harness is desktop-only, so there's nothing to imply embedding for
   * here. Reuses the renderer's font cache dir / offline switch so a face is downloaded at most
   * once per environment.
   */
  private fun figmaFontResolver(): FigmaFontResolver? {
    fun on(prop: String) = System.getProperty(prop)?.lowercase() == "true"
    if (!on("composeai.figma.embedFonts")) return null
    return GoogleFontsWoff2Resolver(
      cacheDir = System.getProperty("composeai.fonts.cacheDir")?.let { java.io.File(it) },
      offline = on("composeai.fonts.offline"),
    )
  }

  companion object {
    val ID: DataExtensionId = DataExtensionId(ComposeFigmaSvgDataProducer.KIND)

    val factory: RenderDataArtifactExtensionFactory = RenderDataArtifactExtensionFactory { _ ->
      ComposeFigmaSvgExtension()
    }
  }
}
