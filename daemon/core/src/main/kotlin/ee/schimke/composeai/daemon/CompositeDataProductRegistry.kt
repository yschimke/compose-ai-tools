package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.DataProductAttachment
import kotlinx.serialization.json.JsonElement

/**
 * Small fan-out registry for daemon builds that wire multiple independent data-product producers.
 * Capabilities are concatenated in constructor order; kind routing uses the first registry that
 * advertises the kind.
 */
class CompositeDataProductRegistry(private val registries: List<DataProductRegistry>) :
  DataProductRegistry {

  override val capabilities = registries.flatMap { it.capabilities }

  override fun isKnown(kind: String): Boolean = registries.any { it.isKnown(kind) }

  override fun fetch(
    previewId: String,
    kind: String,
    params: JsonElement?,
    inline: Boolean,
  ): DataProductRegistry.Outcome {
    val registry =
      registries.firstOrNull { it.isKnown(kind) } ?: return DataProductRegistry.Outcome.Unknown
    return registry.fetch(previewId, kind, params, inline)
  }

  override fun attachmentsFor(previewId: String, kinds: Set<String>): List<DataProductAttachment> =
    registries.flatMap { registry ->
      val supportedKinds = kinds.filterTo(mutableSetOf()) { registry.isKnown(it) }
      if (supportedKinds.isEmpty()) emptyList()
      else registry.attachmentsFor(previewId, supportedKinds)
    }

  override fun onSubscribe(previewId: String, kind: String, params: JsonElement?) {
    registries.firstOrNull { it.isKnown(kind) }?.onSubscribe(previewId, kind, params)
  }

  override fun onUnsubscribe(previewId: String, kind: String) {
    registries.firstOrNull { it.isKnown(kind) }?.onUnsubscribe(previewId, kind)
  }
}
