package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.DataProductCapability
import ee.schimke.composeai.daemon.protocol.DataProductFacet
import ee.schimke.composeai.daemon.protocol.DataProductTransport
import ee.schimke.composeai.data.layoutinspector.ComposeFigmaSvgProduct
import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsPayload
import ee.schimke.composeai.data.layoutinspector.FigmaLayeredSvg
import ee.schimke.composeai.data.layoutinspector.FigmaSvgModel
import ee.schimke.composeai.data.layoutinspector.LayoutInspectorPayload
import ee.schimke.composeai.data.render.pipeline.SamplingPolicy
import ee.schimke.composeai.io.SystemFileSystem
import java.io.File
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * Producer for `compose/figma-svg` — the **layered, editable SVG** export. Backend-agnostic (pure
 * string from [FigmaLayeredSvg]) so it's written here regardless of backend, next to the
 * `compose/semantics-wireframe` SVG and from the same captured trees. Where the wireframe bakes the
 * *semantics* tree into a schematic, this bakes the *layout* tree — for the composable names and
 * container tokens — plus the semantics tree's text, into a design-fidelity artifact a designer
 * imports and edits in Figma.
 */
object ComposeFigmaSvgDataProducer {
  const val KIND: String = ComposeFigmaSvgProduct.KIND
  const val SCHEMA_VERSION: Int = ComposeFigmaSvgProduct.SCHEMA_VERSION
  const val FILE_SVG: String = ComposeFigmaSvgProduct.FILE_SVG

  /**
   * Writes `compose-figma.svg` under `<rootDir>/<previewId>/`.
   *
   * @param layout the layout-inspector tree (composable names + container tokens + nesting).
   * @param semantics optional semantics tree whose text nodes enrich matching layers with editable
   *   text + typography.
   * @param colorNames optional `#AARRGGBB` → theme-role-name map so named fills carry their
   *   variable.
   * @param density px-per-dp of the captured frame (dp/sp tokens are converted to px against it).
   */
  fun writeSvg(
    rootDir: File,
    previewId: String,
    layout: LayoutInspectorPayload,
    semantics: ComposeSemanticsPayload? = null,
    colorNames: Map<String, String> = emptyMap(),
    density: Float = 1f,
    fileSystem: FileSystem = SystemFileSystem,
  ) {
    val previewDir = rootDir.resolve(previewId).also { it.mkdirs() }
    val model =
      FigmaSvgModel.from(
        layout = layout,
        semantics = semantics,
        colorNames = colorNames,
        density = density,
      )
    val svg = FigmaLayeredSvg.render(model)
    fileSystem.write(previewDir.resolve(FILE_SVG).path.toPath()) { writeUtf8(svg) }
  }
}

/**
 * Registry for `compose/figma-svg`. A single path-transported SVG artifact — no baked PNG extra
 * (the vector *is* the deliverable here; raster capture for PNG-sticker components is a separate,
 * future mode). Mirrors [ComposeSemanticsWireframeDataProductRegistry] otherwise.
 */
class ComposeFigmaSvgDataProductRegistry(
  private val rootDir: File,
  private val fileSystem: FileSystem = SystemFileSystem,
) :
  FileBackedDataProductRegistry(
    capabilities =
      listOf(
        DataProductCapability(
          kind = ComposeFigmaSvgDataProducer.KIND,
          schemaVersion = ComposeFigmaSvgDataProducer.SCHEMA_VERSION,
          transport = DataProductTransport.PATH,
          attachable = true,
          fetchable = true,
          requiresRerender = false,
          displayName = "Figma layered SVG",
          facets = listOf(DataProductFacet.ARTIFACT, DataProductFacet.IMAGE),
          mediaTypes = listOf(ComposeFigmaSvgProduct.MEDIA_TYPE_SVG),
          sampling = SamplingPolicy.End,
        )
      ),
    fileSystem = fileSystem,
  ) {
  override fun fileFor(previewId: String, kind: String): File? =
    if (kind == ComposeFigmaSvgDataProducer.KIND)
      rootDir.resolve(previewId).resolve(ComposeFigmaSvgDataProducer.FILE_SVG)
    else null

  /** The SVG is not JSON — an `inline = true` fetch must still return the path, not parse it. */
  override fun allowInlineUpgrade(kind: String): Boolean = false
}
