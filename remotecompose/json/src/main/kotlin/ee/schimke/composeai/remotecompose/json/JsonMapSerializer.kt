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
 * Collects one Remote Compose operation into a [JsonObject], by being the [MapSerializer] AndroidX
 * already hands every operation.
 *
 * One instance serializes exactly one object; nested [RcSerializable] values get their own. That
 * mirrors how upstream uses the interface and is why there is no reset — reuse would silently merge
 * two operations' fields.
 *
 * ## Non-finite floats are the whole problem
 *
 * JSON has no `NaN`, and Remote Compose leans on NaN harder than any other format worth naming: an
 * id does not travel as a number, it travels as a *NaN payload* (`Utils.asNan(id)` sets the
 * `0xFF800000` bits and packs the id into the mantissa). `"width": [NaN, NaN]` in a naive dump is
 * not a missing value — it is two encoded references, and printing them as `NaN` destroys exactly
 * the information a reader opened the dump for.
 *
 * So every non-finite float is emitted as a **string**, and one that decodes to an id is emitted as
 * `"@<id>"` — the same `@name` sigil the authoring dialect uses for a reference, so the two
 * dialects at least agree on what a reference looks like. `Infinity` / `-Infinity` / a bare `NaN`
 * (a real one, carrying no payload) keep their names. Finite floats stay numbers.
 *
 * This is lossy in one direction and deliberately so: `"@42"` does not say whether 42 is a colour,
 * a text or a float variable. The `NamedVariable` operations in the same dump do, and pairing them
 * is the reader's job — encoding the type here would mean inventing a type system upstream does not
 * have at this layer.
 */
internal class JsonMapSerializer : MapSerializer {

  private val fields = LinkedHashMap<String, JsonElement>()

  fun result(): JsonObject = JsonObject(fields)

  override fun addType(type: String): MapSerializer = put("type", JsonPrimitive(type))

  // The three array overloads take non-null arrays: `MapSerializer` marks them `@NonNull` through
  // JSpecify, and Kotlin honours that, so a nullable parameter here overrides nothing at all rather
  // than merely being lenient.
  override fun addFloatExpressionSrc(key: String, value: FloatArray): MapSerializer =
    put(key, floats(value))

  /**
   * An integer expression, with its **mask**.
   *
   * The mask is not decoration: it says which entries of the array are literals and which are
   * variable references, so two documents with byte-identical arrays and different masks mean
   * different things. Dropping it made them project to identical JSON — which is a direct hit on
   * the one job this projection has, since a diff that cannot separate them reports no change where
   * there is one.
   *
   * Emitted as a sibling `<key>Mask` rather than folded into the array, because the array is the
   * expression and a reader walking it should not have to skip an element that is not part of it.
   */
  override fun addIntExpressionSrc(key: String, value: IntArray, mask: Int): MapSerializer {
    put(key, JsonArray(value.map { JsonPrimitive(it) }))
    return put("${key}Mask", JsonPrimitive(mask))
  }

  override fun addPath(key: String, value: FloatArray): MapSerializer = put(key, floats(value))

  /**
   * The operation's role — `COMPONENT`, `MODIFIER`, `DRAW_OPERATION`, … — under [TAGS].
   *
   * Kept because it is the only machine-readable answer to "what kind of thing is this", and a
   * consumer filtering a dump down to the layout tree (drop everything that is not a component)
   * would otherwise have to keep a hardcoded list of operation type names in step with upstream.
   */
  override fun addTags(vararg tags: SerializeTags): MapSerializer =
    put(TAGS, JsonArray(tags.map { JsonPrimitive(it.name) }))

  // `null` and empty are DIFFERENT and both are kept, matching every other nullable overload here.
  // `orEmpty()` collapsed them, so an operation whose optional list went from absent to present-
  // and-empty produced no diff at all — which is a direct hit on the one job this projection has.
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
    /**
     * Key carrying [SerializeTags]. Prefixed so it cannot collide with an operation field — an
     * operation that one day names a field `tags` would otherwise overwrite its own role.
     */
    const val TAGS: String = "\$tags"

    /**
     * Key under which an operation that does **not** implement
     * [androidx.compose.remote.core.serialize.Serializable] records what it is.
     *
     * Such an operation is dumped as `{"$UNSERIALIZED": "<class>", "text": "<deepToString>"}`,
     * which is a visible hole rather than an invisible one. This matters more than it looks: the
     * failure mode of a dumper is not throwing, it is producing something plausible, and an
     * operation silently rendered as its `toString()` reads like a value.
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
        // An operation upstream has not taught to serialize. `Header` is the one that always lands
        // here — it predates the hook — and `RemoteComposeJson.dumpToJsonObject` decodes it
        // properly into the dump's `header`, so seeing it in `operations` too is expected. Anything
        // *else* appearing here is a genuine gap, and the point of the marker is that it reads as
        // one instead of as a value.
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

    /**
     * A float as JSON: finite ones as numbers, non-finite ones as the strings described on
     * [JsonMapSerializer].
     */
    fun float(value: Float): JsonElement =
      when {
        value.isFinite() -> JsonPrimitive(value)
        value.isInfinite() -> JsonPrimitive(if (value > 0) "Infinity" else "-Infinity")
        // A NaN in a Remote Compose document is an id far more often than it is an absent value.
        // `Utils.idFromNan` reads the mantissa back out; a zero payload means it really is just a
        // NaN, which `fillMaxWidth` uses as its "no explicit fraction" marker.
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
