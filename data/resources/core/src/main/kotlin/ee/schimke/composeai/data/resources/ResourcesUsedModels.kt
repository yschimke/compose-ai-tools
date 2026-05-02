package ee.schimke.composeai.data.resources

import kotlinx.serialization.Serializable

object ResourcesUsedProduct {
  const val KIND: String = "resources/used"
  const val SCHEMA_VERSION: Int = 1
  const val FILE: String = "resources-used.json"
}

@Serializable data class ResourcesUsedPayload(val references: List<ResourceUsedReference>)

@Serializable
data class ResourceUsedReference(
  val resourceType: String,
  val resourceName: String,
  val packageName: String,
  val resolvedValue: String? = null,
  val resolvedFile: String? = null,
  val consumers: List<ResourceUsedConsumer>,
)

@Serializable data class ResourceUsedConsumer(val nodeId: String)
