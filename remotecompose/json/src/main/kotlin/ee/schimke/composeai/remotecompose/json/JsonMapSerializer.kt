package ee.schimke.composeai.remotecompose.json

import androidx.compose.remote.core.Operation
import androidx.compose.remote.core.operations.Utils
import androidx.compose.remote.core.serialize.MapSerializer
import androidx.compose.remote.core.serialize.Serializable as RcSerializable
import androidx.compose.remote.core.serialize.SerializeTags
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Collects one Remote Compose operation into a [JsonObject] as the [MapSerializer] AndroidX hands
 * it. One instance per object; reuse would merge fields.
 *
 * Ids are NaN payloads (`Utils.asNan(id)`) and JSON has no NaN, so non-finite floats become
 * strings: an id as `"@<id>"` (the authoring reference sigil), else `Infinity` / `-Infinity` /
 * `NaN`.
 */
internal class JsonMapSerializer : MapSerializer {

  private val fields = LinkedHashMap<String, JsonElement>()

  fun result(): JsonObject = JsonObject(fields)

  override fun addType(type: String): MapSerializer = put("type", JsonPrimitive(type))

  // Non-null array parameters: `MapSerializer` marks them `@NonNull`, so a nullable one wouldn't
  // override.
  override fun addFloatExpressionSrc(key: String, value: FloatArray): MapSerializer =
    put(key, floats(value))

  /**
   * An integer expression plus its mask (which entries are literals vs. variable references) as a
   * sibling `<key>Mask`; without it, different expressions with identical arrays diff as equal.
   */
  override fun addIntExpressionSrc(key: String, value: IntArray, mask: Int): MapSerializer {
    put(key, JsonArray(value.map { JsonPrimitive(it) }))
    return put("${key}Mask", JsonPrimitive(mask))
  }

  override fun addPath(key: String, value: FloatArray): MapSerializer = put(key, floats(value))

  /**
   * The operation's role (`COMPONENT`, `MODIFIER`, …) under [TAGS], so a consumer can filter a dump
   * without a hardcoded list of operation names.
   */
  override fun addTags(vararg tags: SerializeTags): MapSerializer =
    put(TAGS, JsonArray(tags.map { JsonPrimitive(it.name) }))

  // Null and empty stay distinct, so absent → empty still diffs.
  override fun <T : Any?> add(key: String, value: List<T>?): MapSerializer =
    put(key, value?.let { list -> JsonArray(list.map { convert(it) }) } ?: JsonNull)

  override fun <T : Any?> add(key: String, value: Map<String, T>?): MapSerializer =
    put(key, value?.let { map -> JsonObject(map.mapValues { (_, v) -> convert(v) }) } ?: JsonNull)

  override fun add(key: String, value: RcSerializable?): MapSerializer = put(key, convert(value))

  override fun add(key: String, value: String?): MapSerializer = put(key, primitive(value))

  override fun add(key: String, a: Float, b: Float, c: Float, d: Float): MapSerializer =
    put(key, JsonArray(listOf(float(a), float(b), float(c), float(d))))

  override fun add(key: String, a: Float, b: Float): MapSerializer =
    put(key, JsonArray(listOf(float(a), float(b))))

  override fun add(key: String, value: Byte?): MapSerializer = put(key, primitive(value))

  override fun add(key: String, value: Short?): MapSerializer = put(key, primitive(value))

  override fun add(key: String, value: Int?): MapSerializer = put(key, primitive(value))

  override fun add(key: String, value: Long?): MapSerializer = put(key, primitive(value))

  override fun add(key: String, value: Float?): MapSerializer =
    put(key, if (value == null) JsonNull else float(value))

  override fun add(key: String, value: Double?): MapSerializer =
    put(key, if (value == null) JsonNull else double(value))

  override fun add(key: String, value: Boolean?): MapSerializer = put(key, primitive(value))

  override fun <T : Enum<T>> add(key: String, value: Enum<T>?): MapSerializer =
    put(key, primitive(value?.name))

  private fun put(key: String, value: JsonElement): MapSerializer = apply { fields[key] = value }

  private fun floats(value: FloatArray) = JsonArray(value.map { float(it) })

  private fun primitive(value: String?) = value?.let { JsonPrimitive(it) } ?: JsonNull

  private fun primitive(value: Number?) = value?.let { JsonPrimitive(it) } ?: JsonNull

  private fun primitive(value: Boolean?) = value?.let { JsonPrimitive(it) } ?: JsonNull

  companion object {
    /** Key carrying [SerializeTags]; prefixed so no operation field can collide with it. */
    const val TAGS: String = "\$tags"

    /**
     * Marks an operation that doesn't implement
     * [androidx.compose.remote.core.serialize.Serializable]: `{"$UNSERIALIZED": "<class>", "text":
     * "<deepToString>"}`, a visible hole rather than a plausible-looking value.
     */
    const val UNSERIALIZED: String = "\$unserialized"

    /**
     * Project any value a [MapSerializer] can be handed into JSON: a nested serializable becomes an
     * object, a null becomes null, anything else becomes its string form.
     */
    fun convert(value: Any?): JsonElement =
      when (value) {
        null -> JsonNull
        is RcSerializable -> JsonMapSerializer().also { value.serialize(it) }.result()
        // `Header` always lands here (it predates the hook) and is decoded separately into the
        // dump's `header`; any other operation here is a genuine upstream gap.
        is Operation ->
          JsonObject(
            mapOf(
              UNSERIALIZED to JsonPrimitive(value.javaClass.simpleName),
              "text" to JsonPrimitive(value.deepToString("")),
            )
          )
        is Boolean -> JsonPrimitive(value)
        is Float -> float(value)
        is Double -> double(value)
        is Number -> JsonPrimitive(value)
        is String -> JsonPrimitive(value)
        is Enum<*> -> JsonPrimitive(value.name)
        else -> JsonPrimitive(value.toString())
      }

    /** A float as JSON: finite as a number, non-finite as described on [JsonMapSerializer]. */
    fun float(value: Float): JsonElement =
      when {
        value.isFinite() -> JsonPrimitive(value)
        value.isInfinite() -> JsonPrimitive(if (value > 0) "Infinity" else "-Infinity")
        // A zero payload is a real NaN (e.g. `fillMaxWidth`'s "no fraction" marker).
        else ->
          Utils.idFromNan(value).let {
            if (it == 0) JsonPrimitive("NaN") else JsonPrimitive("@$it")
          }
      }

    fun double(value: Double): JsonElement =
      when {
        value.isFinite() -> JsonPrimitive(value)
        value.isInfinite() -> JsonPrimitive(if (value > 0) "Infinity" else "-Infinity")
        else -> JsonPrimitive("NaN")
      }
  }
}
