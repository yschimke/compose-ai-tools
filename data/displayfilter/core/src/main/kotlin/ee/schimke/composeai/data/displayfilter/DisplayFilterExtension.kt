package ee.schimke.composeai.data.displayfilter

import ee.schimke.composeai.data.render.extensions.CommonDataProducts
import ee.schimke.composeai.data.render.extensions.DataExtensionConstraints
import ee.schimke.composeai.data.render.extensions.DataExtensionHookKind
import ee.schimke.composeai.data.render.extensions.DataExtensionId
import ee.schimke.composeai.data.render.extensions.DataExtensionPhase
import ee.schimke.composeai.data.render.extensions.DataExtensionTarget
import ee.schimke.composeai.data.render.extensions.DataProductKey
import ee.schimke.composeai.data.render.extensions.ExtensionContextKey
import ee.schimke.composeai.data.render.extensions.ExtensionPostCaptureContext
import ee.schimke.composeai.data.render.extensions.PostCaptureProcessor
import java.io.File

/**
 * Reads the captured PNG, applies every filter listed in [DisplayFilterContextKeys.Filters] (or an
 * empty result if none are enabled), and writes one PNG per filter into
 * [DisplayFilterContextKeys.OutputDirectory] using the filename pattern
 * `<basename>_displayfilter_<filterId>.png`.
 *
 * Mirrors `OverlayExtension` in shape — host-controlled values (output directory, filter set) flow
 * through context keys; the typed product graph carries inputs/outputs only.
 */
class DisplayFilterExtension : PostCaptureProcessor {
  override val id: DataExtensionId = DataExtensionId(EXTENSION_ID)
  override val hooks: Set<DataExtensionHookKind> = setOf(DataExtensionHookKind.AfterRender)
  override val constraints: DataExtensionConstraints =
    DataExtensionConstraints(phase = DataExtensionPhase.PostProcess)
  override val inputs: Set<DataProductKey<*>> = setOf(CommonDataProducts.ImageArtifact)
  override val outputs: Set<DataProductKey<*>> = setOf(DisplayFilterDataProducts.Variants)
  override val targets: Set<DataExtensionTarget> =
    setOf(DataExtensionTarget.Android, DataExtensionTarget.Desktop)

  override fun process(context: ExtensionPostCaptureContext) {
    val filters = context.get(DisplayFilterContextKeys.Filters).orEmpty()
    if (filters.isEmpty()) {
      context.products.put(DisplayFilterDataProducts.Variants, DisplayFilterArtifacts(emptyList()))
      return
    }
    val image = context.products.require(CommonDataProducts.ImageArtifact)
    val outputDir = context.require(DisplayFilterContextKeys.OutputDirectory)
    val sourcePng = File(image.path)
    val basename = sourcePng.nameWithoutExtension
    val artifacts = filters.map { filter ->
      val dest = outputDir.resolve("${basename}_displayfilter_${filter.id}.png")
      PngColorMatrix.apply(sourcePng, dest, filter.matrix)
      DisplayFilterArtifact(filter = filter, path = dest.absolutePath)
    }
    context.products.put(DisplayFilterDataProducts.Variants, DisplayFilterArtifacts(artifacts))
  }

  companion object {
    const val EXTENSION_ID: String = "displayfilter"
  }
}

/**
 * Host-controlled inputs to [DisplayFilterExtension]. Output directory is per-preview and the
 * filter set is per-render-config, neither of which fits as a typed product upstream.
 */
object DisplayFilterContextKeys {
  val OutputDirectory: ExtensionContextKey<File> =
    ExtensionContextKey(name = "displayfilter.outputDirectory", type = File::class.java)
  val Filters: ExtensionContextKey<List<DisplayFilter>> =
    @Suppress("UNCHECKED_CAST")
    ExtensionContextKey(
      name = "displayfilter.filters",
      type = List::class.java as Class<List<DisplayFilter>>,
    )
}
