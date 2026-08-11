package ee.schimke.composeai.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Removes per-process identities from the inspection sidecars carried by a published bundle.
 *
 * The daemon's live data products deliberately expose Compose's runtime `SemanticsNode.id` values:
 * other products captured in the same session use that id space to join nodes. Those ids are not a
 * publication identity, however. A fresh renderer assigns a different sequence, and reflected
 * modifier values can include JVM object hashes and generated-lambda addresses. Carrying those
 * bytes into every per-preview bundle made an otherwise identical catalog publication replace every
 * bundle blob.
 *
 * Canonicalization happens at the bundle boundary so live-session joins keep their native ids. A
 * semantics node uses its already-stable `ref` when present, with a structural path fallback. The
 * matching layout node is rewritten through the old-id mapping; layout-only nodes use their own
 * structural path. Malformed or future-schema payloads pass through unchanged rather than making
 * the best-effort `--with-semantics` lane fail.
 */
internal object InspectionSidecarCanonicalizer {
  private val json = Json

  internal data class Result(
    val semanticsById: Map<String, ByteArray>,
    val layoutById: Map<String, ByteArray>,
  )

  fun canonicalize(
    semanticsById: Map<String, ByteArray>,
    layoutById: Map<String, ByteArray>,
  ): Result {
    val canonicalSemantics = LinkedHashMap<String, ByteArray>(semanticsById.size)
    val semanticsIds = HashMap<String, Map<String, String>>(semanticsById.size)
    semanticsById.forEach { (previewId, bytes) ->
      val canonical = canonicalizeSemantics(bytes)
      canonicalSemantics[previewId] = canonical?.first ?: bytes
      semanticsIds[previewId] = canonical?.second.orEmpty()
    }

    val canonicalLayout = LinkedHashMap<String, ByteArray>(layoutById.size)
    layoutById.forEach { (previewId, bytes) ->
      canonicalLayout[previewId] =
        canonicalizeLayout(bytes, semanticsIds[previewId].orEmpty()) ?: bytes
    }
    return Result(canonicalSemantics, canonicalLayout)
  }

  private fun canonicalizeSemantics(bytes: ByteArray): Pair<ByteArray, Map<String, String>>? =
    runCatching {
        val payload =
          json.parseToJsonElement(bytes.decodeToString()) as? JsonObject ?: return@runCatching null
        val root = payload["root"] as? JsonObject ?: return@runCatching null
        val idMapping = LinkedHashMap<String, String>()
        val canonicalRoot = canonicalizeSemanticsNode(root, "r", idMapping)
        rewrite(payload, "root", canonicalRoot).toString().encodeToByteArray() to idMapping
      }
      .getOrNull()

  private fun canonicalizeSemanticsNode(
    node: JsonObject,
    path: String,
    idMapping: MutableMap<String, String>,
  ): JsonObject {
    val oldId = node.string("nodeId")
    val stableId = node.string("ref") ?: "semantics:$path"
    if (oldId != null) idMapping[oldId] = stableId
    return rewrite(
      source = node,
      replacements =
        mapOf(
          "nodeId" to JsonPrimitive(stableId),
          "children" to
            canonicalChildren(node["children"], path, ::canonicalizeSemanticsNode, idMapping),
        ),
    )
  }

  private fun canonicalizeLayout(bytes: ByteArray, semanticsIds: Map<String, String>): ByteArray? =
    runCatching {
        val payload =
          json.parseToJsonElement(bytes.decodeToString()) as? JsonObject ?: return@runCatching null
        val root = payload["root"] as? JsonObject ?: return@runCatching null
        val canonicalRoot = canonicalizeLayoutNode(root, "r", semanticsIds)
        rewrite(payload, "root", canonicalRoot)
          .canonicalizeRuntimeStrings()
          .toString()
          .encodeToByteArray()
      }
      .getOrNull()

  private fun canonicalizeLayoutNode(
    node: JsonObject,
    path: String,
    semanticsIds: Map<String, String>,
  ): JsonObject {
    val stableId = node.string("nodeId")?.let(semanticsIds::get) ?: "layout:$path"
    return rewrite(
      source = node,
      replacements =
        mapOf(
          "nodeId" to JsonPrimitive(stableId),
          "children" to
            canonicalChildren(node["children"], path) { child, childPath ->
              canonicalizeLayoutNode(child, childPath, semanticsIds)
            },
        ),
    )
  }

  private fun canonicalChildren(
    children: JsonElement?,
    path: String,
    transform: (JsonObject, String) -> JsonObject,
  ): JsonElement? =
    when (children) {
      null -> null
      is JsonArray ->
        JsonArray(
          children.mapIndexed { index, child ->
            (child as? JsonObject)?.let { transform(it, "$path/$index") } ?: child
          }
        )
      else -> error("children must be an array")
    }

  private fun canonicalChildren(
    children: JsonElement?,
    path: String,
    transform: (JsonObject, String, MutableMap<String, String>) -> JsonObject,
    idMapping: MutableMap<String, String>,
  ): JsonElement? =
    canonicalChildren(children, path) { child, childPath -> transform(child, childPath, idMapping) }

  private fun rewrite(source: JsonObject, key: String, value: JsonElement): JsonObject =
    rewrite(source, mapOf(key to value))

  private fun rewrite(source: JsonObject, replacements: Map<String, JsonElement?>): JsonObject =
    JsonObject(
      buildMap {
        source.forEach { (key, value) ->
          if (key !in replacements) put(key, value) else replacements[key]?.let { put(key, it) }
        }
        replacements.forEach { (key, value) ->
          if (key !in source && value != null) put(key, value)
        }
      }
    )

  private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

  private fun JsonElement.canonicalizeRuntimeStrings(): JsonElement =
    when (this) {
      is JsonObject -> JsonObject(mapValues { (_, value) -> value.canonicalizeRuntimeStrings() })
      is JsonArray -> JsonArray(map { it.canonicalizeRuntimeStrings() })
      is JsonPrimitive ->
        if (isString) JsonPrimitive(content.canonicalizeJvmRuntimeIdentity()) else this
    }
}

private val JVM_OBJECT_IDENTITY =
  Regex("""^([A-Za-z_][\w$]*(?:\.[A-Za-z_][\w$]*)+)@[0-9a-fA-F]{1,16}$""")
private val JVM_LAMBDA_IDENTITY =
  Regex("""^(.+\${'$'}\${'$'}Lambda)/0x[0-9a-fA-F]+@[0-9a-fA-F]{1,16}$""")
private val JVM_LAMBDA_ADDRESS = Regex("""^(.+\${'$'}\${'$'}Lambda)/0x[0-9a-fA-F]+$""")

internal fun String.canonicalizeJvmRuntimeIdentity(): String =
  replace(JVM_LAMBDA_IDENTITY, "${'$'}1/<address>@<identity>")
    .replace(JVM_LAMBDA_ADDRESS, "${'$'}1/<address>")
    .replace(JVM_OBJECT_IDENTITY, "${'$'}1@<identity>")
