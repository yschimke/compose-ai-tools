package ee.schimke.composeai.daemon

import ee.schimke.composeai.data.render.extensions.CommonDataProducts
import ee.schimke.composeai.data.render.extensions.DataExtensionPlanner
import ee.schimke.composeai.data.render.extensions.DataExtensionTarget
import ee.schimke.composeai.data.render.extensions.DataProductKey
import ee.schimke.composeai.data.render.extensions.DataProductStore
import ee.schimke.composeai.data.render.extensions.ExtensionContextData
import ee.schimke.composeai.data.render.extensions.ExtensionContextValue
import ee.schimke.composeai.data.render.extensions.ExtensionPostCaptureContext
import ee.schimke.composeai.data.render.extensions.PlannedDataExtension
import ee.schimke.composeai.data.render.extensions.PostCaptureProcessor
import ee.schimke.composeai.data.render.extensions.RecordingDataProductStore
import ee.schimke.composeai.data.render.extensions.RenderDensity
import ee.schimke.composeai.data.render.extensions.RenderImageArtifact
import ee.schimke.composeai.data.render.extensions.provides
import ee.schimke.composeai.renderer.AccessibilityDataProducts
import ee.schimke.composeai.renderer.AccessibilityFindingsPayload
import ee.schimke.composeai.renderer.AccessibilityHierarchyPayload
import ee.schimke.composeai.renderer.OverlayContextKeys
import ee.schimke.composeai.renderer.OverlayExtension
import ee.schimke.composeai.renderer.TouchTargetsExtension
import java.io.File

/**
 * Seeds a [DataProductStore] with hierarchy + ATF + density (and image artifact when present),
 * plans the registered post-capture extensions, runs each [PostCaptureProcessor] in planner-
 * determined order, and returns the populated store. Each extension's view is `scopedFor(it)` so
 * undeclared `put`/`get` fail loudly.
 *
 * Failures from individual extensions are logged and skipped — one bad extension shouldn't strand
 * the typed products earlier extensions already produced. Mirrors the existing
 * [AccessibilityImageProcessor] error policy in [AccessibilityDataProducer.writeArtifacts].
 *
 * Caller-supplied [extensions] lets test code inject custom processors; production wiring passes
 * the default consumer set ([AccessibilityPostCaptureExtensions.defaults]).
 */
fun runAccessibilityPostCapturePipeline(
  previewId: String,
  hierarchy: AccessibilityHierarchyPayload,
  findings: AccessibilityFindingsPayload,
  density: Float,
  imageArtifact: RenderImageArtifact? = null,
  outputDirectory: File? = null,
  isRound: Boolean = false,
  extensions: List<PlannedDataExtension> = AccessibilityPostCaptureExtensions.defaults,
  target: DataExtensionTarget? = DataExtensionTarget.Android,
): DataProductStore {
  val store = RecordingDataProductStore()
  store.put(AccessibilityDataProducts.Hierarchy, hierarchy)
  store.put(AccessibilityDataProducts.Atf, findings)
  store.put(CommonDataProducts.Density, RenderDensity(density))
  imageArtifact?.let { store.put(CommonDataProducts.ImageArtifact, it) }

  val contextValues = buildList<ExtensionContextValue<*>> {
    if (outputDirectory != null) add(OverlayContextKeys.OutputDirectory provides outputDirectory)
    add(OverlayContextKeys.IsRound provides isRound)
  }
  val contextData = ExtensionContextData.of(*contextValues.toTypedArray())

  val initialProducts =
    buildSet<DataProductKey<*>> {
      add(AccessibilityDataProducts.Hierarchy)
      add(AccessibilityDataProducts.Atf)
      add(CommonDataProducts.Density)
      if (imageArtifact != null) add(CommonDataProducts.ImageArtifact)
    }

  // Only request outputs whose full dependency chain can be satisfied from initialProducts +
  // extensions on this render. Direct-input filtering isn't enough — an extension with no direct
  // image dependency may still transitively depend on one through another extension's output, in
  // which case planOutputs would error on the missing ImageArtifact and strand every output for
  // this render. Computing the runnable closure first lets unaffected outputs (e.g. touch-targets)
  // still run.
  val runnableExtensions = runnableExtensionsFor(extensions, initialProducts)
  val requestedOutputs = runnableExtensions.flatMap { it.outputs }.toSet()

  if (requestedOutputs.isEmpty()) return store

  val plan =
    DataExtensionPlanner.planOutputs(
      extensions = runnableExtensions,
      requestedOutputs = requestedOutputs,
      initialProducts = initialProducts,
      target = target,
    )

  if (!plan.isValid) {
    System.err.println(
      "AccessibilityPostCapturePipeline: planning failed for $previewId — ${plan.errors}"
    )
    return store
  }

  for (ext in plan.orderedExtensions) {
    if (ext !is PostCaptureProcessor) continue
    try {
      ext.process(
        ExtensionPostCaptureContext(
          extensionId = ext.id,
          previewId = previewId,
          renderMode = null,
          products = store.scopedFor(ext),
          data = contextData,
        )
      )
    } catch (t: Throwable) {
      System.err.println(
        "AccessibilityPostCapturePipeline: extension '${ext.id}' failed for " +
          "$previewId: ${t.javaClass.simpleName}: ${t.message}"
      )
    }
  }
  return store
}

/**
 * Default consumer set for [runAccessibilityPostCapturePipeline]. Both [TouchTargetsExtension] and
 * [OverlayExtension] now run through the typed graph; the legacy [AccessibilityImageProcessor]
 * hook is no longer installed by default and is kept only for embedders with custom processors.
 */
object AccessibilityPostCaptureExtensions {
  val defaults: List<PlannedDataExtension> = listOf(TouchTargetsExtension(), OverlayExtension())
}

/**
 * Walks the dependency closure forward from [initialProducts] and returns the subset of
 * [extensions] whose full input chain is ultimately satisfied. Used to drop extensions whose
 * declared inputs (or transitive inputs through other extensions in the list) can't be produced
 * on this render so their unsatisfiable outputs don't poison [DataExtensionPlanner.planOutputs]
 * for the rest.
 *
 * Fixpoint iteration over the producer map. O(n²) in the worst case for n extensions; n is small
 * (single-digit) for any realistic registered set, so the simple shape stays cheaper than a
 * topological sort with cycle handling.
 */
internal fun runnableExtensionsFor(
  extensions: List<PlannedDataExtension>,
  initialProducts: Set<DataProductKey<*>>,
): List<PlannedDataExtension> {
  val producible = initialProducts.toMutableSet()
  val runnable = mutableListOf<PlannedDataExtension>()
  val pending = extensions.toMutableList()
  do {
    val newlyRunnable = pending.filter { ext -> ext.inputs.all { it in producible } }
    if (newlyRunnable.isEmpty()) break
    runnable += newlyRunnable
    newlyRunnable.forEach { producible += it.outputs }
    pending.removeAll(newlyRunnable)
  } while (pending.isNotEmpty())
  return runnable
}
