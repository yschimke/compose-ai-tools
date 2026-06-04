package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.DataProductCapability
import ee.schimke.composeai.daemon.protocol.DataProductExtra
import ee.schimke.composeai.daemon.protocol.DataProductFacet
import ee.schimke.composeai.daemon.protocol.DataProductTransport
import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsPayload
import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsWireframeProduct
import ee.schimke.composeai.data.layoutinspector.SemanticsWireframeSvg
import ee.schimke.composeai.data.render.pipeline.SamplingPolicy
import ee.schimke.composeai.io.SystemFileSystem
import java.io.File
import kotlinx.serialization.json.JsonElement
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * Producer for the **SVG half** of `compose/semantics-wireframe`. The SVG is backend-agnostic (pure
 * string from [SemanticsWireframeSvg]) so it's written here regardless of backend. The **PNG half**
 * is raster and backend-specific, so each backend's baker (`AndroidSemanticsWireframe` /
 * `DesktopSemanticsWireframe`) writes [ComposeSemanticsWireframeProduct.FILE_PNG] alongside it; the
 * registry attaches whichever PNG it finds.
 */
object ComposeSemanticsWireframeDataProducer {
  const val KIND: String = ComposeSemanticsWireframeProduct.KIND
  const val SCHEMA_VERSION: Int = ComposeSemanticsWireframeProduct.SCHEMA_VERSION
  const val FILE_SVG: String = ComposeSemanticsWireframeProduct.FILE_SVG
  const val FILE_PNG: String = ComposeSemanticsWireframeProduct.FILE_PNG

  /** Writes `compose-semantics-wireframe.svg` under `<rootDir>/<previewId>/`. */
  fun writeSvg(
    rootDir: File,
    previewId: String,
    payload: ComposeSemanticsPayload,
    fileSystem: FileSystem = SystemFileSystem,
  ) {
    val previewDir = rootDir.resolve(previewId).also { it.mkdirs() }
    val svg = SemanticsWireframeSvg.render(payload)
    fileSystem.write(previewDir.resolve(FILE_SVG).path.toPath()) { writeUtf8(svg) }
  }
}

/**
 * Registry for `compose/semantics-wireframe`. The SVG is the primary path-transported artifact; the
 * baked PNG rides as a `png` [DataProductExtra] (mirroring how the a11y registry attaches its
 * overlay PNG), so a consumer that fetches the kind gets both the vector source and a raster
 * fallback.
 */
class ComposeSemanticsWireframeDataProductRegistry(
  private val rootDir: File,
  private val fileSystem: FileSystem = SystemFileSystem,
) :
  FileBackedDataProductRegistry(
    capabilities =
      listOf(
        DataProductCapability(
          kind = ComposeSemanticsWireframeDataProducer.KIND,
          schemaVersion = ComposeSemanticsWireframeDataProducer.SCHEMA_VERSION,
          transport = DataProductTransport.PATH,
          attachable = true,
          fetchable = true,
          requiresRerender = false,
          displayName = "Compose semantics wireframe",
          facets =
            listOf(DataProductFacet.ARTIFACT, DataProductFacet.IMAGE, DataProductFacet.OVERLAY),
          mediaTypes =
            listOf(
              ComposeSemanticsWireframeProduct.MEDIA_TYPE_SVG,
              ComposeSemanticsWireframeProduct.MEDIA_TYPE_PNG,
            ),
          sampling = SamplingPolicy.End,
        )
      ),
    fileSystem = fileSystem,
  ) {
  override fun fileFor(previewId: String, kind: String): File? =
    if (kind == ComposeSemanticsWireframeDataProducer.KIND)
      rootDir.resolve(previewId).resolve(ComposeSemanticsWireframeDataProducer.FILE_SVG)
    else null

  /** The SVG is not JSON — an `inline = true` fetch must still return the path, not parse it. */
  override fun allowInlineUpgrade(kind: String): Boolean = false

  /** Attach the baked PNG when present, so raster-only consumers get it without a second fetch. */
  override fun extras(
    previewId: String,
    kind: String,
    payload: JsonElement?,
  ): List<DataProductExtra>? {
    val png = rootDir.resolve(previewId).resolve(ComposeSemanticsWireframeDataProducer.FILE_PNG)
    if (!png.exists()) return null
    return listOf(
      DataProductExtra(
        name = ComposeSemanticsWireframeProduct.PNG_EXTRA_NAME,
        path = png.absolutePath,
        mediaType = ComposeSemanticsWireframeProduct.MEDIA_TYPE_PNG,
        sizeBytes = png.length().takeIf { it > 0 },
      )
    )
  }
}
