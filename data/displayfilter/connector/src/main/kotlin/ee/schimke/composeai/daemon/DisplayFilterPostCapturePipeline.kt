package ee.schimke.composeai.daemon

import ee.schimke.composeai.data.displayfilter.DisplayFilter
import ee.schimke.composeai.data.displayfilter.DisplayFilterArtifacts
import ee.schimke.composeai.data.displayfilter.DisplayFilterContextKeys
import ee.schimke.composeai.data.displayfilter.DisplayFilterDataProducts
import ee.schimke.composeai.data.displayfilter.DisplayFilterExtension
import ee.schimke.composeai.data.render.extensions.CommonDataProducts
import ee.schimke.composeai.data.render.extensions.DataExtensionPlanner
import ee.schimke.composeai.data.render.extensions.DataExtensionTarget
import ee.schimke.composeai.data.render.extensions.DataProductKey
import ee.schimke.composeai.data.render.extensions.DataProductStore
import ee.schimke.composeai.data.render.extensions.ExtensionContextData
import ee.schimke.composeai.data.render.extensions.ExtensionPostCaptureContext
import ee.schimke.composeai.data.render.extensions.PlannedDataExtension
import ee.schimke.composeai.data.render.extensions.PostCaptureProcessor
import ee.schimke.composeai.data.render.extensions.RecordingDataProductStore
import ee.schimke.composeai.data.render.extensions.RenderImageArtifact
import ee.schimke.composeai.data.render.extensions.provides
import java.io.File

/**
 * Seeds a [DataProductStore] with the captured PNG, plans the registered post-capture extensions,
 * runs each [PostCaptureProcessor] in planner-determined order, and returns the populated store.
 *
 * Mirrors `runAccessibilityPostCapturePipeline` in shape so the two pipelines share a mental model.
 * Per-extension `scopedFor(it)` views fail loudly on undeclared `put`/`get`. Failures from
 * individual extensions are logged + skipped — one bad filter shouldn't strand the others.
 */
fun runDisplayFilterPostCapturePipeline(
  previewId: String,
  imageArtifact: RenderImageArtifact,
  outputDirectory: File,
  filters: List<DisplayFilter>,
  extensions: List<PlannedDataExtension> = DisplayFilterPostCaptureExtensions.defaults,
  target: DataExtensionTarget? = null,
): DataProductStore {
  val store = RecordingDataProductStore()
  store.put(CommonDataProducts.ImageArtifact, imageArtifact)

  val contextData =
    ExtensionContextData.of(
      DisplayFilterContextKeys.OutputDirectory provides outputDirectory,
      DisplayFilterContextKeys.Filters provides filters,
    )

  val initialProducts: Set<DataProductKey<*>> = setOf(CommonDataProducts.ImageArtifact)
  val requestedOutputs = extensions.flatMap { it.outputs }.toSet()
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
      "DisplayFilterPostCapturePipeline: planning failed for $previewId — ${plan.errors}"
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
        "DisplayFilterPostCapturePipeline: extension '${ext.id}' failed for " +
          "$previewId: ${t.javaClass.simpleName}: ${t.message}"
      )
    }
  }
  return store
}

/** Default consumer set — just the one extension today; left as a list so additions are trivial. */
object DisplayFilterPostCaptureExtensions {
  val defaults: List<PlannedDataExtension> = listOf(DisplayFilterExtension())
}

/** Convenience helper for callers that just want the artifact bag, not the full store. */
fun DataProductStore.displayFilterArtifacts(): DisplayFilterArtifacts? =
  get(DisplayFilterDataProducts.Variants)
