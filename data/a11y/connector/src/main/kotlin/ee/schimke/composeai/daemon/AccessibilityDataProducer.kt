package ee.schimke.composeai.daemon

import ee.schimke.composeai.io.SystemFileSystem
import okio.Path.Companion.toPath
import ee.schimke.composeai.daemon.protocol.DataProductCapability
import ee.schimke.composeai.daemon.protocol.DataProductExtra
import ee.schimke.composeai.daemon.protocol.DataProductFacet
import ee.schimke.composeai.daemon.protocol.DataProductTransport
import ee.schimke.composeai.data.render.extensions.RenderImageArtifact
import ee.schimke.composeai.data.render.pipeline.SamplingPolicy
import ee.schimke.composeai.renderer.AccessibilityDataProducts
import ee.schimke.composeai.renderer.AccessibilityFinding
import ee.schimke.composeai.renderer.AccessibilityFindingsPayload
import ee.schimke.composeai.renderer.AccessibilityHierarchyPayload
import ee.schimke.composeai.renderer.AccessibilityNode
import ee.schimke.composeai.renderer.AccessibilityTouchTargetsPayload
import java.io.File
import java.util.concurrent.ConcurrentHashMap
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
   * `a11y/overlay`. Path-transport kind whose only content is the Paparazzi-style annotated PNG
   * produced by [OverlayExtension] inside [runAccessibilityPostCapturePipeline]. Lets clients
   * fetch the picture directly without first asking for the JSON kinds. Also surfaces as an
   * `overlay` extra on the JSON kinds, so a panel that subscribed to `a11y/atf` still has the
   * PNG path handy.
   */
  const val KIND_OVERLAY: String = AccessibilityDataProducts.KIND_OVERLAY

  /** File names under `<rootDir>/<previewId>/`. */
  const val FILE_ATF: String = "a11y-atf.json"
  const val FILE_HIERARCHY: String = "a11y-hierarchy.json"
  const val FILE_TOUCH_TARGETS: String = "a11y-touchTargets.json"
  const val FILE_OVERLAY: String = "a11y-overlay.png"

  /** [DataProductExtra.name] used for the rendered overlay PNG attached to a11y JSON kinds. */
  const val OVERLAY_EXTRA_NAME: String = "overlay"

  @Serializable internal data class AtfPayload(val findings: List<AccessibilityFinding>)

  @Serializable internal data class HierarchyPayload(val nodes: List<AccessibilityNode>)

  /**
   * Writes ATF + hierarchy JSON to `<rootDir>/<previewId>/` and runs the typed post-capture
   * pipeline ([runAccessibilityPostCapturePipeline]) so [TouchTargetsExtension] writes the
   * touch-targets JSON and [OverlayExtension] writes the overlay PNG. Idempotent — overwrites
   * prior files.
   */
  fun writeArtifacts(
    rootDir: File,
    previewId: String,
    findings: List<AccessibilityFinding>,
    nodes: List<AccessibilityNode>,
    density: Float = 1f,
    pngFile: File? = null,
    isRound: Boolean = false,
  ) {
    val previewDir = rootDir.resolve(previewId)
    previewDir.mkdirs()
    SystemFileSystem.write(previewDir.resolve(FILE_ATF).path.toPath()) {
      writeUtf8(json.encodeToString(AtfPayload.serializer(), AtfPayload(findings)))
    }
    SystemFileSystem.write(previewDir.resolve(FILE_HIERARCHY).path.toPath()) {
      writeUtf8(json.encodeToString(HierarchyPayload.serializer(), HierarchyPayload(nodes)))
    }

    // Typed post-capture pipeline: TouchTargetsExtension always runs; OverlayExtension runs when
    // pngFile is supplied (it requires CommonDataProducts.ImageArtifact). Both write their
    // outputs through the typed product store and we serialize touchTargets here. Overlay
    // writes its own PNG side-effectfully — the store carries the resulting AccessibilityOverlayArtifact
    // for any future consumer that wants the path inline.
    val store =
      runAccessibilityPostCapturePipeline(
        previewId = previewId,
        hierarchy = AccessibilityHierarchyPayload(nodes),
        findings = AccessibilityFindingsPayload(findings),
        density = density,
        imageArtifact = pngFile?.let { RenderImageArtifact(path = it.absolutePath) },
        outputDirectory = previewDir,
        isRound = isRound,
      )
    store.get(AccessibilityDataProducts.TouchTargets)?.let { touchTargets ->
      SystemFileSystem.write(previewDir.resolve(FILE_TOUCH_TARGETS).path.toPath()) {
        writeUtf8(
          json.encodeToString(AccessibilityTouchTargetsPayload.serializer(), touchTargets)
        )
      }
    }
  }
}

/**
 * D2 — [DataProductRegistry] implementation that surfaces `a11y/atf` (inline) and
 * `a11y/hierarchy` (path) by reading the JSON files [AccessibilityDataProducer] writes during
 * each render. The renderer-side producer always writes when daemon-mode a11y is active; this
 * registry decides whether the data ends up on the wire based on the dispatcher's subscription
 * bookkeeping.
 *
 * `attachable: true` — they ride `renderFinished.dataProducts` when the client has subscribed.
 * `fetchable: true` — pull-on-demand reads from the same files. `requiresRerender: true` (D2.2)
 * — when the latest render didn't run in a11y mode the artefact is absent, so [fetch] returns
 * [DataProductRegistry.Outcome.RequiresRerender] and the dispatcher queues a `mode=a11y` re-render
 * before re-invoking. The cost of a11y mode is paid only when a panel actually subscribes, not on
 * every render.
 *
 * `rootDir` mirrors `RenderEngine`'s `dataDir` (defaults to `<outputDir.parent>/data`). Wired by
 * [DaemonMain].
 */
class AccessibilityDataProductRegistry(private val rootDir: File) :
  FileBackedDataProductRegistry(
    capabilities =
      listOf(
        DataProductCapability(
          kind = AccessibilityDataProducer.KIND_ATF,
          schemaVersion = AccessibilityDataProducer.SCHEMA_VERSION,
          transport = DataProductTransport.INLINE,
          attachable = true,
          fetchable = true,
          requiresRerender = true,
          displayName = "Accessibility findings",
          facets =
            listOf(DataProductFacet.STRUCTURED, DataProductFacet.CHECK, DataProductFacet.DIAGNOSTIC),
          sampling = SamplingPolicy.End,
        ),
        DataProductCapability(
          kind = AccessibilityDataProducer.KIND_HIERARCHY,
          schemaVersion = AccessibilityDataProducer.SCHEMA_VERSION,
          transport = DataProductTransport.PATH,
          attachable = true,
          fetchable = true,
          requiresRerender = true,
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
          requiresRerender = true,
          displayName = "Touch target findings",
          facets =
            listOf(DataProductFacet.STRUCTURED, DataProductFacet.CHECK, DataProductFacet.DIAGNOSTIC),
          sampling = SamplingPolicy.End,
        ),
        DataProductCapability(
          kind = AccessibilityDataProducer.KIND_OVERLAY,
          schemaVersion = AccessibilityDataProducer.SCHEMA_VERSION,
          transport = DataProductTransport.PATH,
          attachable = true,
          fetchable = true,
          requiresRerender = true,
          displayName = "Accessibility overlay",
          facets =
            listOf(DataProductFacet.ARTIFACT, DataProductFacet.IMAGE, DataProductFacet.OVERLAY),
          mediaTypes = listOf("image/png"),
          sampling = SamplingPolicy.End,
        ),
      )
  ) {

  /**
   * D2.2 — set of `(previewId, kind)` pairs the dispatcher has reported subscribed via
   * [onSubscribe] and not yet torn down via [onUnsubscribe] (or `setVisible` pruning, which fans
   * out as `onUnsubscribe` calls per dropped pair). Tracked here so the host's render dispatcher
   * can ask [isPreviewSubscribed] before queueing the next render and stamp `mode=a11y` into the
   * payload — turning a `data/subscribe` for a focus-mode card into "all subsequent renders for
   * this preview run with `LocalInspectionMode = false` and write the ATF/hierarchy artefacts,
   * until focus moves on." Today the predicate has no readers wired yet (PR 1d will land that
   * hook); the bookkeeping is structural and idempotent so adding the read site is the only
   * remaining step.
   */
  private val subscribedPairs: MutableSet<Pair<String, String>> = ConcurrentHashMap.newKeySet()

  override fun fileFor(previewId: String, kind: String): File? {
    val fileName =
      when (kind) {
        AccessibilityDataProducer.KIND_ATF -> AccessibilityDataProducer.FILE_ATF
        AccessibilityDataProducer.KIND_HIERARCHY -> AccessibilityDataProducer.FILE_HIERARCHY
        AccessibilityDataProducer.KIND_TOUCH_TARGETS -> AccessibilityDataProducer.FILE_TOUCH_TARGETS
        AccessibilityDataProducer.KIND_OVERLAY -> AccessibilityDataProducer.FILE_OVERLAY
        else -> return null
      }
    return rootDir.resolve(previewId).resolve(fileName)
  }

  /**
   * D2.2 — every a11y kind is `requiresRerender = true`. A missing artefact means the latest
   * render didn't run in a11y mode (the producer's writeArtifacts is gated on
   * `effectiveRunAccessibility`); the dispatcher reacts by queueing a re-render with `mode=a11y`
   * and re-invoking fetch, which then finds the freshly-written file.
   */
  override fun missingOutcome(previewId: String, kind: String): DataProductRegistry.Outcome =
    DataProductRegistry.Outcome.RequiresRerender("a11y")

  /**
   * Every advertised kind here is gated on the renderer's a11y mode (`writeArtifacts` only fires
   * when `effectiveRunAccessibility` is true) — so a sticky subscription for any of them implies
   * the next render must run with mode=a11y. Returning the mode from the producer puts the
   * mapping next to the artefacts it produces; [JsonRpcServer.subscriptionDrivenRenderMode]
   * routes through the registry instead of pattern-matching on the kind string.
   */
  override fun renderModeFor(kind: String): String? = if (isKnown(kind)) "a11y" else null

  /** The overlay kind is a PNG — never parse it as JSON, even on `inline = true`. */
  override fun allowInlineUpgrade(kind: String): Boolean =
    kind != AccessibilityDataProducer.KIND_OVERLAY

  /**
   * The overlay PNG rides as an `extras` entry on every a11y kind so a panel that subscribed to
   * any of them gets the picture handy without a follow-up `data/fetch`. Skips when the overlay
   * file isn't on disk for [previewId].
   */
  override fun extras(
    previewId: String,
    kind: String,
    payload: JsonElement?,
  ): List<DataProductExtra>? {
    val overlay = rootDir.resolve(previewId).resolve(AccessibilityDataProducer.FILE_OVERLAY)
    if (!overlay.exists()) return null
    return listOf(
      DataProductExtra(
        name = AccessibilityDataProducer.OVERLAY_EXTRA_NAME,
        path = overlay.absolutePath,
        mediaType = "image/png",
        sizeBytes = overlay.length().takeIf { it > 0 },
      )
    )
  }

  /**
   * D2.2 — record `(previewId, kind)` so the next render of [previewId] can run in a11y mode.
   * Idempotent across re-subscribes (the [Set] de-duplicates). Only the four advertised a11y
   * kinds are tracked; an unrelated kind (the dispatcher routes here only after capability
   * matching, but this is defensive) is ignored.
   */
  override fun onSubscribe(previewId: String, kind: String, params: JsonElement?) {
    if (!isKnown(kind)) return
    subscribedPairs.add(previewId to kind)
  }

  /**
   * D2.2 — drop the `(previewId, kind)` bookkeeping. Fires on explicit `data/unsubscribe`, on
   * `setVisible`-driven pruning when the preview leaves view, and on daemon shutdown. After this
   * runs, [isPreviewSubscribed] reflects the new state synchronously.
   */
  override fun onUnsubscribe(previewId: String, kind: String) {
    if (!isKnown(kind)) return
    subscribedPairs.remove(previewId to kind)
  }

  /**
   * D2.2 — `true` iff at least one a11y kind has a live subscription for [previewId]. Designed for
   * the host's render dispatcher: when this returns `true` the next `host.submit(...)` payload for
   * [previewId] should carry `mode=a11y` so the renderer flips `LocalInspectionMode` and writes
   * the ATF/hierarchy artefacts the subscribed kinds will read off disk on the next
   * `attachmentsFor` pass.
   */
  fun isPreviewSubscribed(previewId: String): Boolean =
    subscribedPairs.any { (id, _) -> id == previewId }
}
