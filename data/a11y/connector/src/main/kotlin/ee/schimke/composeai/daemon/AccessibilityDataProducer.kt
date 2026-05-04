package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.DataFetchResult
import ee.schimke.composeai.daemon.protocol.DataProductAttachment
import ee.schimke.composeai.daemon.protocol.DataProductCapability
import ee.schimke.composeai.daemon.protocol.DataProductExtra
import ee.schimke.composeai.daemon.protocol.DataProductFacet
import ee.schimke.composeai.daemon.protocol.DataProductTransport
import ee.schimke.composeai.data.render.pipeline.SamplingPolicy
import ee.schimke.composeai.renderer.AccessibilityDataProducts
import ee.schimke.composeai.renderer.AccessibilityFinding
import ee.schimke.composeai.renderer.AccessibilityNode
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * D2 — writes the per-render a11y artefacts the data-product registry surfaces.
 *
 * One pair of files per render under `<rootDir>/<previewId>/`:
 *
 * - `a11y-atf.json` — `{ "findings": AccessibilityFinding[] }`. Inline-transport kind
 *   (`a11y/atf`) parses this into `payload`. Always written, even when `findings` is empty,
 *   so the registry can distinguish "no findings on this render" from "a11y didn't run".
 * - `a11y-hierarchy.json` — `{ "nodes": AccessibilityNode[] }`. Path-transport kind
 *   (`a11y/hierarchy`) returns this file's absolute path; VS Code's webview reads it.
 * - `a11y-touchTargets.json` — `{ "targets": TouchTarget[] }`. Inline-transport kind
 *   (`a11y/touchTargets`) derives clickable target sizes + overlaps from the hierarchy.
 *
 * On-disk layout pinned by [docs/daemon/DATA-PRODUCTS.md](../../../../../../../docs/daemon/DATA-PRODUCTS.md)
 * § "On-disk layout".
 */
object AccessibilityDataProducer {

  private val json = Json {
    encodeDefaults = false
    prettyPrint = false
  }

  /** Schema version pinned alongside the on-disk shape. Bumped when the shape changes. */
  const val SCHEMA_VERSION: Int = AccessibilityDataProducts.SCHEMA_VERSION

  /** `a11y/atf` — findings array. */
  const val KIND_ATF: String = AccessibilityDataProducts.KIND_ATF

  /** `a11y/hierarchy` — accessibility-relevant nodes with bounds + label + states. */
  const val KIND_HIERARCHY: String = AccessibilityDataProducts.KIND_HIERARCHY

  /** `a11y/touchTargets` — clickable node sizes and overlap findings derived from hierarchy. */
  const val KIND_TOUCH_TARGETS: String = AccessibilityDataProducts.KIND_TOUCH_TARGETS

  /**
   * D2.1 — `a11y/overlay`. Path-transport kind whose only content is the Paparazzi-style
   * annotated PNG produced by [AccessibilityImageProcessor]. Lets clients fetch the picture
   * directly without first asking for the JSON kinds. Also surfaces as an `overlay` extra on
   * the JSON kinds, so a panel that subscribed to `a11y/atf` still has the PNG path handy.
   */
  const val KIND_OVERLAY: String = AccessibilityDataProducts.KIND_OVERLAY

  /** File names under `<rootDir>/<previewId>/`. */
  const val FILE_ATF: String = "a11y-atf.json"
  const val FILE_HIERARCHY: String = "a11y-hierarchy.json"
  const val FILE_TOUCH_TARGETS: String = "a11y-touchTargets.json"
  const val FILE_OVERLAY: String = "a11y-overlay.png"

  @Serializable internal data class AtfPayload(val findings: List<AccessibilityFinding>)

  @Serializable internal data class HierarchyPayload(val nodes: List<AccessibilityNode>)

  @Serializable internal data class TouchTargetsPayload(val targets: List<TouchTarget>)

  @Serializable
  internal data class TouchTarget(
    val nodeId: String,
    val boundsInScreen: String,
    val widthDp: Float,
    val heightDp: Float,
    val findings: List<String>,
    val overlappingNodeIds: List<String>? = null,
  )

  /**
   * Writes both JSON files to `<rootDir>/<previewId>/` (creating the directory tree if needed).
   * Idempotent — overwrites prior files. Called from [RenderEngine.render]'s a11y branch with
   * the result of [ee.schimke.composeai.renderer.AccessibilityChecker.analyze].
   *
   * When [pngFile] is supplied (the captured screenshot), the configured [imageProcessors]
   * also run — for the daemon's default wiring that means [AccessibilityImageProcessor]
   * generates `a11y-overlay.png`. Each processor failure is logged + skipped so a single
   * bad processor never strands the JSON the consumer already cares about.
   */
  fun writeArtifacts(
    rootDir: File,
    previewId: String,
    findings: List<AccessibilityFinding>,
    nodes: List<AccessibilityNode>,
    density: Float = 1f,
    pngFile: File? = null,
    isRound: Boolean = false,
    imageProcessors: List<ImageProcessor> = emptyList(),
  ) {
    val previewDir = rootDir.resolve(previewId)
    previewDir.mkdirs()
    previewDir
      .resolve(FILE_ATF)
      .writeText(json.encodeToString(AtfPayload.serializer(), AtfPayload(findings)))
    previewDir
      .resolve(FILE_HIERARCHY)
      .writeText(json.encodeToString(HierarchyPayload.serializer(), HierarchyPayload(nodes)))
    previewDir
      .resolve(FILE_TOUCH_TARGETS)
      .writeText(
        json.encodeToString(
          TouchTargetsPayload.serializer(),
          TouchTargetsPayload(buildTouchTargets(nodes, density)),
        )
      )
    if (pngFile == null || imageProcessors.isEmpty()) return
    val input =
      ImageProcessorInput(
        previewId = previewId,
        pngFile = pngFile,
        dataDir = rootDir,
        isRound = isRound,
        context = AccessibilityImageContext(findings = findings, nodes = nodes),
      )
    for (processor in imageProcessors) {
      try {
        processor.process(input)
      } catch (t: Throwable) {
        System.err.println(
          "AccessibilityDataProducer: image processor '${processor.name}' failed " +
            "for $previewId: ${t.javaClass.simpleName}: ${t.message}"
        )
      }
    }
  }

  private fun buildTouchTargets(nodes: List<AccessibilityNode>, density: Float): List<TouchTarget> {
    val scale = density.takeIf { it > 0f } ?: 1f
    val candidates =
      nodes.mapIndexedNotNull { index, node ->
        if (!node.states.any { it == "clickable" || it == "long-clickable" }) return@mapIndexedNotNull null
        val rect = parseBounds(node.boundsInScreen) ?: return@mapIndexedNotNull null
        Candidate(
          nodeId = "node-$index",
          boundsInScreen = node.boundsInScreen,
          rect = rect,
          widthDp = rect.widthPx / scale,
          heightDp = rect.heightPx / scale,
        )
      }

    for (i in candidates.indices) {
      for (j in i + 1 until candidates.size) {
        val a = candidates[i]
        val b = candidates[j]
        if (a.rect.overlaps(b.rect) && !a.rect.contains(b.rect) && !b.rect.contains(a.rect)) {
          a.overlappingNodeIds += b.nodeId
          b.overlappingNodeIds += a.nodeId
        }
      }
    }

    return candidates.map { candidate ->
      val findings = mutableListOf<String>()
      if (candidate.widthDp < MIN_TOUCH_TARGET_DP || candidate.heightDp < MIN_TOUCH_TARGET_DP) {
        findings += FINDING_BELOW_MINIMUM
      }
      if (candidate.overlappingNodeIds.isNotEmpty()) findings += FINDING_OVERLAPPING
      TouchTarget(
        nodeId = candidate.nodeId,
        boundsInScreen = candidate.boundsInScreen,
        widthDp = candidate.widthDp,
        heightDp = candidate.heightDp,
        findings = findings,
        overlappingNodeIds = candidate.overlappingNodeIds.takeIf { it.isNotEmpty() },
      )
    }
  }

  private data class Candidate(
    val nodeId: String,
    val boundsInScreen: String,
    val rect: BoundsRect,
    val widthDp: Float,
    val heightDp: Float,
    val overlappingNodeIds: MutableList<String> = mutableListOf(),
  )

  private data class BoundsRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val widthPx: Int = (right - left).coerceAtLeast(0)
    val heightPx: Int = (bottom - top).coerceAtLeast(0)

    fun overlaps(other: BoundsRect): Boolean =
      left < other.right && right > other.left && top < other.bottom && bottom > other.top

    fun contains(other: BoundsRect): Boolean =
      left <= other.left && top <= other.top && right >= other.right && bottom >= other.bottom
  }

  private fun parseBounds(bounds: String): BoundsRect? {
    val parts = bounds.split(',')
    if (parts.size != 4) return null
    val values = parts.map { it.trim().toIntOrNull() ?: return null }
    return BoundsRect(values[0], values[1], values[2], values[3])
  }

  private const val MIN_TOUCH_TARGET_DP = 48f
  private const val FINDING_BELOW_MINIMUM = "belowMinimum"
  private const val FINDING_OVERLAPPING = "overlapping"
}

/**
 * D2 — [DataProductRegistry] implementation that surfaces `a11y/atf` (inline) and
 * `a11y/hierarchy` (path) by reading the JSON files [AccessibilityDataProducer] writes during
 * each render. The renderer-side producer always writes when daemon-mode a11y is active; this
 * registry decides whether the data ends up on the wire based on the dispatcher's subscription
 * bookkeeping.
 *
 * `attachable: true` for both kinds — they ride `renderFinished.dataProducts` when the client
 * has subscribed. `fetchable: true` for both — pull-on-demand reads from the same files. Neither
 * kind triggers a re-render: the producer always runs in daemon a11y mode, so the JSON is on
 * disk for any preview that has rendered at least once.
 *
 * `rootDir` mirrors `RenderEngine`'s `dataDir` (defaults to `<outputDir.parent>/data`). Wired by
 * [DaemonMain].
 */
class AccessibilityDataProductRegistry(private val rootDir: File) : DataProductRegistry {

  private val json = Json { ignoreUnknownKeys = true }

  override val capabilities: List<DataProductCapability> =
    listOf(
      DataProductCapability(
        kind = AccessibilityDataProducer.KIND_ATF,
        schemaVersion = AccessibilityDataProducer.SCHEMA_VERSION,
        transport = DataProductTransport.INLINE,
        attachable = true,
        fetchable = true,
        requiresRerender = false,
        displayName = "Accessibility findings",
        facets = listOf(DataProductFacet.STRUCTURED, DataProductFacet.CHECK, DataProductFacet.DIAGNOSTIC),
        sampling = SamplingPolicy.End,
      ),
      DataProductCapability(
        kind = AccessibilityDataProducer.KIND_HIERARCHY,
        schemaVersion = AccessibilityDataProducer.SCHEMA_VERSION,
        transport = DataProductTransport.PATH,
        attachable = true,
        fetchable = true,
        requiresRerender = false,
        displayName = "Accessibility hierarchy",
        facets = listOf(DataProductFacet.STRUCTURED),
        mediaTypes = listOf("application/json"),
        sampling = SamplingPolicy.End,
      ),
      DataProductCapability(
        kind = AccessibilityDataProducer.KIND_TOUCH_TARGETS,
        schemaVersion = AccessibilityDataProducer.SCHEMA_VERSION,
        transport = DataProductTransport.INLINE,
        attachable = true,
        fetchable = true,
        requiresRerender = false,
        displayName = "Touch target findings",
        facets = listOf(DataProductFacet.STRUCTURED, DataProductFacet.CHECK, DataProductFacet.DIAGNOSTIC),
        sampling = SamplingPolicy.End,
      ),
      DataProductCapability(
        kind = AccessibilityDataProducer.KIND_OVERLAY,
        schemaVersion = AccessibilityDataProducer.SCHEMA_VERSION,
        transport = DataProductTransport.PATH,
        attachable = true,
        fetchable = true,
        requiresRerender = false,
        displayName = "Accessibility overlay",
        facets = listOf(DataProductFacet.ARTIFACT, DataProductFacet.IMAGE, DataProductFacet.OVERLAY),
        mediaTypes = listOf("image/png"),
        sampling = SamplingPolicy.End,
      ),
    )

  override fun fetch(
    previewId: String,
    kind: String,
    params: JsonElement?,
    inline: Boolean,
  ): DataProductRegistry.Outcome {
    val (file, transport) =
      when (kind) {
        AccessibilityDataProducer.KIND_ATF ->
          fileFor(previewId, AccessibilityDataProducer.FILE_ATF) to DataProductTransport.INLINE
        AccessibilityDataProducer.KIND_HIERARCHY ->
          fileFor(previewId, AccessibilityDataProducer.FILE_HIERARCHY) to
            DataProductTransport.PATH
        AccessibilityDataProducer.KIND_TOUCH_TARGETS ->
          fileFor(previewId, AccessibilityDataProducer.FILE_TOUCH_TARGETS) to
            DataProductTransport.INLINE
        AccessibilityDataProducer.KIND_OVERLAY ->
          fileFor(previewId, AccessibilityDataProducer.FILE_OVERLAY) to DataProductTransport.PATH
        else -> return DataProductRegistry.Outcome.Unknown
      }
    if (!file.exists()) return DataProductRegistry.Outcome.NotAvailable
    val extras = extrasFor(previewId, kind)
    // The overlay kind is binary (PNG); never parse it as JSON. Path-only.
    if (kind == AccessibilityDataProducer.KIND_OVERLAY) {
      return DataProductRegistry.Outcome.Ok(
        DataFetchResult(
          kind = kind,
          schemaVersion = AccessibilityDataProducer.SCHEMA_VERSION,
          path = file.absolutePath,
          extras = extras.takeIf { it.isNotEmpty() },
        )
      )
    }
    val payloadElement: JsonElement? =
      try {
        json.parseToJsonElement(file.readText())
      } catch (t: Throwable) {
        return DataProductRegistry.Outcome.FetchFailed(
          message = "could not parse $kind for $previewId: ${t.message}"
        )
      }
    val result =
      when {
        inline ->
          DataFetchResult(
            kind = kind,
            schemaVersion = AccessibilityDataProducer.SCHEMA_VERSION,
            payload = payloadElement,
            extras = extras.takeIf { it.isNotEmpty() },
          )
        transport == DataProductTransport.INLINE ->
          // Even when the caller didn't ask for inline, the `inline` transport kind has no
          // separate `path` representation. Returning the parsed payload keeps the API
          // self-consistent: `transport='inline'` ⇒ payload always set.
          DataFetchResult(
            kind = kind,
            schemaVersion = AccessibilityDataProducer.SCHEMA_VERSION,
            payload = payloadElement,
            extras = extras.takeIf { it.isNotEmpty() },
          )
        else ->
          DataFetchResult(
            kind = kind,
            schemaVersion = AccessibilityDataProducer.SCHEMA_VERSION,
            path = file.absolutePath,
            extras = extras.takeIf { it.isNotEmpty() },
          )
      }
    return DataProductRegistry.Outcome.Ok(result)
  }

  /**
   * Builds the [DataProductExtra] list for [kind] by scanning [previewId]'s data dir for
   * known sibling outputs. Today the only extra is the a11y overlay PNG, attached to all
   * three a11y kinds. Returns an empty list when no extras are available — the caller wraps
   * the result in `takeIf { it.isNotEmpty() }` so the wire field stays absent in that case.
   */
  private fun extrasFor(previewId: String, kind: String): List<DataProductExtra> {
    val attaches =
      when (kind) {
        AccessibilityDataProducer.KIND_ATF,
        AccessibilityDataProducer.KIND_HIERARCHY,
        AccessibilityDataProducer.KIND_TOUCH_TARGETS,
        AccessibilityDataProducer.KIND_OVERLAY -> true
        else -> false
      }
    if (!attaches) return emptyList()
    val overlay = fileFor(previewId, AccessibilityDataProducer.FILE_OVERLAY)
    if (!overlay.exists()) return emptyList()
    return listOf(
      DataProductExtra(
        name = AccessibilityImageProcessor.OVERLAY_NAME,
        path = overlay.absolutePath,
        mediaType = "image/png",
        sizeBytes = overlay.length().takeIf { it > 0 },
      )
    )
  }

  override fun attachmentsFor(
    previewId: String,
    kinds: Set<String>,
  ): List<DataProductAttachment> {
    val out = mutableListOf<DataProductAttachment>()
    for (kind in kinds) {
      val extras = extrasFor(previewId, kind).takeIf { it.isNotEmpty() }
      when (kind) {
        AccessibilityDataProducer.KIND_ATF -> {
          val file = fileFor(previewId, AccessibilityDataProducer.FILE_ATF)
          if (!file.exists()) continue
          val parsed =
            try {
              json.parseToJsonElement(file.readText())
            } catch (t: Throwable) {
              System.err.println(
                "AccessibilityDataProductRegistry: parse $kind failed for $previewId: ${t.message}"
              )
              continue
            }
          out.add(
            DataProductAttachment(
              kind = kind,
              schemaVersion = AccessibilityDataProducer.SCHEMA_VERSION,
              payload = parsed,
              extras = extras,
            )
          )
        }
        AccessibilityDataProducer.KIND_HIERARCHY -> {
          val file = fileFor(previewId, AccessibilityDataProducer.FILE_HIERARCHY)
          if (!file.exists()) continue
          out.add(
            DataProductAttachment(
              kind = kind,
              schemaVersion = AccessibilityDataProducer.SCHEMA_VERSION,
              path = file.absolutePath,
              extras = extras,
            )
          )
        }
        AccessibilityDataProducer.KIND_TOUCH_TARGETS -> {
          val file = fileFor(previewId, AccessibilityDataProducer.FILE_TOUCH_TARGETS)
          if (!file.exists()) continue
          val parsed =
            try {
              json.parseToJsonElement(file.readText())
            } catch (t: Throwable) {
              System.err.println(
                "AccessibilityDataProductRegistry: parse $kind failed for $previewId: ${t.message}"
              )
              continue
            }
          out.add(
            DataProductAttachment(
              kind = kind,
              schemaVersion = AccessibilityDataProducer.SCHEMA_VERSION,
              payload = parsed,
              extras = extras,
            )
          )
        }
        AccessibilityDataProducer.KIND_OVERLAY -> {
          val file = fileFor(previewId, AccessibilityDataProducer.FILE_OVERLAY)
          if (!file.exists()) continue
          out.add(
            DataProductAttachment(
              kind = kind,
              schemaVersion = AccessibilityDataProducer.SCHEMA_VERSION,
              path = file.absolutePath,
              extras = extras,
            )
          )
        }
      // Unknown kinds drop out silently — the dispatcher already filtered against
      // `capabilities` before calling this; an unrecognised kind here means the
      // dispatcher's filtering drifted and we'd rather skip than emit garbage.
      }
    }
    return out
  }

  private fun fileFor(previewId: String, fileName: String): File =
    rootDir.resolve(previewId).resolve(fileName)
}
