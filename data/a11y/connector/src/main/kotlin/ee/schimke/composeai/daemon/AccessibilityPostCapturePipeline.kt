package ee.schimke.composeai.daemon

import ee.schimke.composeai.data.render.extensions.CommonDataProducts
import ee.schimke.composeai.data.render.extensions.DataExtensionPlanner
import ee.schimke.composeai.data.render.extensions.DataExtensionTarget
import ee.schimke.composeai.data.render.extensions.DataProductKey
import ee.schimke.composeai.data.render.extensions.DataProductStore
import ee.schimke.composeai.data.render.extensions.ExtensionPostCaptureContext
import ee.schimke.composeai.data.render.extensions.PlannedDataExtension
import ee.schimke.composeai.data.render.extensions.PostCaptureProcessor
import ee.schimke.composeai.data.render.extensions.RecordingDataProductStore
import ee.schimke.composeai.data.render.extensions.RenderDensity
import ee.schimke.composeai.data.render.extensions.RenderImageArtifact
import ee.schimke.composeai.renderer.AccessibilityDataProducts
import ee.schimke.composeai.renderer.AccessibilityFindingsPayload
import ee.schimke.composeai.renderer.AccessibilityHierarchyPayload
import ee.schimke.composeai.renderer.TouchTargetsExtension

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
  attributes: Map<String, Any?> = emptyMap(),
  extensions: List<PlannedDataExtension> = AccessibilityPostCaptureExtensions.defaults,
  target: DataExtensionTarget? = DataExtensionTarget.Android,
): DataProductStore {
  val store = RecordingDataProductStore()
  store.put(AccessibilityDataProducts.Hierarchy, hierarchy)
  store.put(AccessibilityDataProducts.Atf, findings)
  store.put(CommonDataProducts.Density, RenderDensity(density))
  imageArtifact?.let { store.put(CommonDataProducts.ImageArtifact, it) }

  val initialProducts =
    buildSet<DataProductKey<*>> {
      add(AccessibilityDataProducts.Hierarchy)
      add(AccessibilityDataProducts.Atf)
      add(CommonDataProducts.Density)
      if (imageArtifact != null) add(CommonDataProducts.ImageArtifact)
    }

  val requestedOutputs =
    extensions
      .filter { ext -> imageArtifact != null || CommonDataProducts.ImageArtifact !in ext.inputs }
      .flatMap { it.outputs }
      .toSet()

  if (requestedOutputs.isEmpty()) return store

  val plan =
    DataExtensionPlanner.planOutputs(
      extensions = extensions,
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
          attributes = attributes,
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
 * Default consumer set for [runAccessibilityPostCapturePipeline]. Currently just
 * [TouchTargetsExtension] — overlay generation still goes through the legacy
 * [AccessibilityImageProcessor] hook on [AccessibilityDataProducer.writeArtifacts] until that path
 * gets folded into the typed graph in a follow-up.
 */
object AccessibilityPostCaptureExtensions {
  val defaults: List<PlannedDataExtension> = listOf(TouchTargetsExtension())
}
