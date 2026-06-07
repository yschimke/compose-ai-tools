package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.DataProductCapability
import ee.schimke.composeai.daemon.protocol.DataProductFacet
import ee.schimke.composeai.daemon.protocol.DataProductTransport
import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsPayload
import ee.schimke.composeai.data.render.pipeline.SamplingPolicy
import ee.schimke.composeai.io.SystemFileSystem
import ee.schimke.composeai.xr.SPATIAL_SEMANTICS_TREE_VERSION
import ee.schimke.composeai.xr.Size3dDp
import ee.schimke.composeai.xr.SpatialSemanticsTree
import ee.schimke.composeai.xr.SpatialSemanticsTrees
import java.io.File
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * Producer for **`compose/spatial-semantics`** — the unified 3D-over-2D [SpatialSemanticsTree] (the
 * subspace layout with each panel carrying its 2D [ComposeSemanticsPayload] tree). It is the
 * accessibility-and-structure view the `XR_A11Y.md` / `SPATIAL_SEMANTICS_TREE.md` designs converge
 * on: an agent walks the 3D level to pick a panel by spatial position, then reads the 2D level
 * within it.
 *
 * Two production sites write the **same** `compose-spatial-semantics.json`, both read back by
 * [SpatialSemanticsDataProductRegistry]:
 * - **XR previews** — the `:renderer-xr` batch render task harvests the real multi-panel tree
 *   (`SubspaceSceneRecorder.recordTree`) and calls [writeTree].
 * - **Ordinary previews** — the daemon render engines wrap the captured 2D root in the degenerate
 *   single-panel tree via [writeSinglePanel], so every preview exposes the kind (the per-panel 2D
 *   wireframe is the leaf renderer for all of them).
 */
object SpatialSemanticsDataProducer {
  const val KIND: String = "compose/spatial-semantics"
  const val SCHEMA_VERSION: Int = SPATIAL_SEMANTICS_TREE_VERSION
  const val FILE: String = "compose-spatial-semantics.json"

  // `encodeDefaults = true` so `version` + `units` are always present (the consumer's
  // `isSpatialSemanticsTree` guard rejects a payload missing either); `explicitNulls = false` keeps
  // the optional 2D-node fields off the wire when absent, matching `compose/semantics`'
  // compactness.
  private val json = Json {
    encodeDefaults = true
    explicitNulls = false
    prettyPrint = false
  }

  /** Writes [tree] as `compose-spatial-semantics.json` under `<rootDir>/<previewId>/`. */
  fun writeTree(
    rootDir: File,
    previewId: String,
    tree: SpatialSemanticsTree,
    fileSystem: FileSystem = SystemFileSystem,
  ) {
    val previewDir = rootDir.resolve(previewId).also { it.mkdirs() }
    fileSystem.write(previewDir.resolve(FILE).path.toPath()) {
      writeUtf8(json.encodeToString(SpatialSemanticsTree.serializer(), tree))
    }
  }

  /**
   * Writes the **degenerate single-panel** tree for an ordinary (non-XR) preview: one `panel` node
   * at identity pose whose `panelContent` is [payload]'s 2D tree. The panel's `sizeDp` is recovered
   * from the root node's `boundsInRoot` so the viewer can lay the face out at its true extent.
   */
  fun writeSinglePanel(
    rootDir: File,
    previewId: String,
    payload: ComposeSemanticsPayload,
    fileSystem: FileSystem = SystemFileSystem,
  ) {
    val tree =
      SpatialSemanticsTrees.singlePanel(
        content = payload.root,
        sizeDp = payload.root.boundsInRoot.toSizeDp(),
        previewId = previewId,
        label = payload.root.label,
      )
    writeTree(rootDir, previewId, tree, fileSystem)
  }

  /** Parses a `"left,top,right,bottom"` bounds string into a flat (`depth = 0`) [Size3dDp]. */
  private fun String.toSizeDp(): Size3dDp {
    val parts = split(',').mapNotNull { it.trim().toIntOrNull() }
    if (parts.size != 4) return Size3dDp(width = 0, height = 0)
    val (left, top, right, bottom) = parts
    return Size3dDp(
      width = (right - left).coerceAtLeast(0),
      height = (bottom - top).coerceAtLeast(0),
    )
  }
}

/**
 * Registry for `compose/spatial-semantics`. Path-transport JSON by default; the inline-fallback
 * read and missing-file → NotAvailable plumbing come from [FileBackedDataProductRegistry].
 */
class SpatialSemanticsDataProductRegistry(private val rootDir: File) :
  FileBackedDataProductRegistry(
    capabilities =
      listOf(
        DataProductCapability(
          kind = SpatialSemanticsDataProducer.KIND,
          schemaVersion = SpatialSemanticsDataProducer.SCHEMA_VERSION,
          transport = DataProductTransport.PATH,
          attachable = true,
          fetchable = true,
          requiresRerender = false,
          displayName = "Compose spatial semantics",
          facets = listOf(DataProductFacet.STRUCTURED),
          mediaTypes = listOf("application/json"),
          sampling = SamplingPolicy.End,
        )
      )
  ) {
  override fun fileFor(previewId: String, kind: String): File? =
    if (kind == SpatialSemanticsDataProducer.KIND)
      rootDir.resolve(previewId).resolve(SpatialSemanticsDataProducer.FILE)
    else null
}
