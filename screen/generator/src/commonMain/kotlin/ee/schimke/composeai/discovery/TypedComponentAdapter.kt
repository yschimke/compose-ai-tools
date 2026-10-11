package ee.schimke.composeai.discovery

import kotlin.reflect.KProperty1
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** A bidirectional wire conversion. Invariant in T so a changed property cannot widen to Any. */
interface AdapterValueCodec<T> {
  val jsonType: String
  val kotlinType: String

  /** Document wrapper spelling, distinct from the JSON Schema type used by capabilities. */
  val literalType: String
    get() =
      when (jsonType) {
        "boolean" -> "bool"
        "integer" -> "int"
        else -> jsonType
      }

  /** Null when the host state schema cannot express this codec's accepted values. */
  val stateType: String?
    get() = jsonType.takeIf { it in STATE_TYPES }

  /**
   * How a value of this codec is written into exported source, when it can be: the same wire value
   * lowered by an [ExportProjection]. Null for a codec the projection vocabulary cannot print.
   */
  val exportCodec: ExportProjection.Codec?
    get() = null

  fun encode(value: T): JsonElement

  fun decode(value: JsonElement): T

  /**
   * The closed set of wire values this codec accepts, when it has one; published in a nested
   * [shape] so an editor can offer a picker for an enum inside a list or an object.
   */
  val allowedValues: Set<String>?
    get() = null

  /**
   * The recursive editor hint for a composite codec ([AdapterValueCodecs.list],
   * [AdapterValueCodecs.obj]); null for a scalar, which an editor renders from its `jsonType`.
   *
   * ```json
   * { "control": "list",   "element": <shape> }
   * { "control": "object", "objectType": "<fqn>",
   *   "fields": [ { "name": "…", "required": true|false, "default"?: <wrapper>, …<shape> } ] }
   * ```
   *
   * See [shape] for `<shape>`; it recurses through nested lists and objects.
   */
  val editor: JsonObject?
    get() = null

  /**
   * What an editor needs to author one value of this codec, published inside a composite's
   * [editor]:
   * ```json
   * { "jsonType": "string|boolean|integer|number|array|object",
   *   "literalType": "<the wrapper type, as [literalType]>",
   *   "allowedValues"?: [ "…" ],
   *   "editor"?: { …[editor] } }
   * ```
   */
  fun shape(): JsonObject = buildJsonObject {
    put("jsonType", jsonType)
    put("literalType", literalType)
    allowedValues?.let { put("allowedValues", JsonArray(it.sorted().map(::JsonPrimitive))) }
    editor?.let { put("editor", it) }
  }

  /**
   * [value] as the builder document stores it: a scalar is the literal wrapper `{"type":
   * literalType, "value": encode(value)}`; a list is `{"type": "list", "values": [<wrapper>…]}` and
   * an object `{"type": "object", "fields": {"<name>": <wrapper>}}`, nesting wrappers, never raw
   * JSON.
   */
  fun wrap(value: T): JsonObject = buildJsonObject {
    put("type", literalType)
    put("value", encode(value))
  }

  /**
   * The value a literal [wrapper] (as [wrap] writes it) holds. Its `type` must be this codec's
   * [literalType], as renderers require today; a `state` wrapper is the host's to resolve first.
   */
  fun unwrap(wrapper: JsonObject): T {
    val type = (wrapper["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    require(type == literalType) { "expected a `$literalType` wrapper, found `$type`" }
    return decode(requireNotNull(wrapper["value"]) { "a `$literalType` wrapper has no value" })
  }
}

/**
 * Strict scalar codecs; malformed authored values are errors, never silently replaced by defaults.
 */
object AdapterValueCodecs {
  val String: AdapterValueCodec<String> =
    object : AdapterValueCodec<String> {
      override val jsonType = "string"
      override val kotlinType = "kotlin.String"
      override val exportCodec = ExportProjection.Codec.Text

      override fun encode(value: String): JsonElement = JsonPrimitive(value)

      override fun decode(value: JsonElement): String {
        require(value is JsonPrimitive && value.isString) { "expected a JSON string" }
        return value.content
      }
    }

  val Boolean: AdapterValueCodec<Boolean> =
    object : AdapterValueCodec<Boolean> {
      override val jsonType = "boolean"
      override val kotlinType = "kotlin.Boolean"
      override val exportCodec = ExportProjection.Codec.Bool

      override fun encode(value: Boolean): JsonElement = JsonPrimitive(value)

      override fun decode(value: JsonElement): Boolean {
        require(value is JsonPrimitive && !value.isString) { "expected a JSON boolean" }
        return requireNotNull(value.booleanOrNull) { "expected a JSON boolean" }
      }
    }

  val Int: AdapterValueCodec<Int> =
    object : AdapterValueCodec<Int> {
      override val jsonType = "integer"
      override val kotlinType = "kotlin.Int"
      override val exportCodec = ExportProjection.Codec.Whole

      override fun encode(value: Int): JsonElement = JsonPrimitive(value)

      override fun decode(value: JsonElement): Int {
        require(value is JsonPrimitive && !value.isString) { "expected a JSON integer" }
        return requireNotNull(value.intOrNull) { "expected a JSON integer" }
      }
    }

  /** A finite float; binds to a `number` state variable. */
  val Float: AdapterValueCodec<Float> =
    object : AdapterValueCodec<Float> {
      override val jsonType = "number"
      override val kotlinType = "kotlin.Float"
      override val stateType = "number"
      override val exportCodec = ExportProjection.Codec.Fractional32

      override fun encode(value: Float): JsonElement {
        require(value.isFinite()) { "expected a finite number" }
        return JsonPrimitive(value)
      }

      override fun decode(value: JsonElement): Float {
        require(value is JsonPrimitive && !value.isString) { "expected a JSON number" }
        return requireNotNull(value.floatOrNull?.takeIf { it.isFinite() }) {
          "expected a finite number"
        }
      }
    }

  /**
   * A colour as the builder stores it, `#RRGGBB`/`#AARRGGBB`, `transparent` or a theme role,
   * decoded to a [WireColor] the renderer resolves against its own theme. Empty is
   * [WireColor.Unset]: the component's own colour.
   */
  fun color(export: ExportProjection.Codec.Color): AdapterValueCodec<WireColor> =
    object : AdapterValueCodec<WireColor> {
      override val jsonType = "string"
      override val kotlinType = "androidx.compose.ui.graphics.Color"
      override val stateType: String? = null
      override val exportCodec = export

      override fun encode(value: WireColor): JsonElement = JsonPrimitive(value.wire)

      override fun decode(value: JsonElement): WireColor {
        require(value is JsonPrimitive && value.isString) { "expected a colour string" }
        return WireColor.parse(value.content, export.tokens)
          ?: throw IllegalArgumentException("`${value.content}` is not a colour")
      }
    }

  /**
   * A Material icon key (`filled/newReleases`), exported as the host's icon table writes it; see
   * [ExportProjection.Codec.MaterialIcon]. [allowed] closes the set the shelf offers, when given.
   */
  fun materialIcon(allowed: Set<String>? = null): AdapterValueCodec<String> =
    object : AdapterValueCodec<String> {
      override val jsonType = "string"
      override val kotlinType = IMAGE_VECTOR_TYPE
      override val stateType: String? = null
      override val exportCodec = ExportProjection.Codec.MaterialIcon
      override val allowedValues = allowed

      override fun encode(value: String): JsonElement = JsonPrimitive(value)

      override fun decode(value: JsonElement): String {
        require(value is JsonPrimitive && value.isString) { "expected an icon key" }
        return value.content
      }
    }

  /**
   * One of a closed set of wire values, each naming a Kotlin member ([members]: wire value to the
   * member's qualified name). Decodes to the wire value; the renderer maps it to the real member.
   */
  fun enumOf(typeFqn: String, members: Map<String, String>): AdapterValueCodec<String> =
    object : AdapterValueCodec<String> {
      override val jsonType = "string"
      override val kotlinType = typeFqn
      override val stateType: String? = null
      override val exportCodec = ExportProjection.Codec.Enum(typeFqn, members)
      override val allowedValues = members.keys

      override fun encode(value: String): JsonElement = JsonPrimitive(value)

      override fun decode(value: JsonElement): String {
        require(value is JsonPrimitive && value.isString && value.content in members) {
          "expected one of ${members.keys.sorted()}"
        }
        return value.content
      }
    }

  /**
   * A number of [unit] (`dp` or `sp`) as the builder stores it, a bare float; the renderer applies
   * the unit. With [positive], zero or less is the component's own default and exports nothing.
   */
  fun unit(unit: String, positive: Boolean = false): AdapterValueCodec<Float> =
    object : AdapterValueCodec<Float> by Float {
      override val exportCodec = ExportProjection.Codec.Unit(unit, positive)
      // A bound number would reach a `Dp`/`TextUnit` parameter without its `.dp`/`.sp`.
      override val stateType: String? = null
    }

  /**
   * A theme token named by the builder (`titleLarge`), decoded to its name; the renderer reads the
   * same member from its own theme, and the export writes [export]'s path to it.
   */
  fun token(export: ExportProjection.Codec.Token): AdapterValueCodec<String> =
    object : AdapterValueCodec<String> {
      override val jsonType = "string"
      override val kotlinType = export.typeFqn
      override val stateType: String? = null
      override val exportCodec = export
      override val allowedValues = export.tokens

      override fun encode(value: String): JsonElement = JsonPrimitive(value)

      override fun decode(value: JsonElement): String {
        require(value is JsonPrimitive && value.isString) { "expected a token name" }
        require(export.tokens == null || value.content in export.tokens) {
          "expected one of ${export.tokens?.sorted()}"
        }
        return value.content
      }
    }

  /**
   * An ordered list of [element] values, stored as `{"type": "list", "values":
   * [<element wrapper>…]}` and exported as `listOf(…)`. Literal-only: the state schema has no list
   * type. [encode] is the `values` array, so [decode] and [encode] stay each other's inverse.
   */
  fun <E> list(element: AdapterValueCodec<E>): AdapterValueCodec<List<E>> =
    object : AdapterValueCodec<List<E>> {
      override val jsonType = "array"
      override val kotlinType = LIST_TYPE
      override val literalType = "list"
      override val stateType: String? = null
      override val exportCodec =
        element.exportCodec?.let { ExportProjection.Codec.ListOf(it, element.literalType) }
      override val editor: JsonObject = buildJsonObject {
        put("control", "list")
        put("element", element.shape())
      }

      override fun encode(value: List<E>): JsonElement = JsonArray(value.map(element::wrap))

      override fun decode(value: JsonElement): List<E> {
        require(value is JsonArray) { "expected a list of `${element.literalType}` wrappers" }
        return value.mapIndexed { index, item ->
          require(item is JsonObject) { "[$index]: expected a `${element.literalType}` wrapper" }
          try {
            element.unwrap(item)
          } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("[$index]: ${e.message}", e)
          }
        }
      }

      override fun wrap(value: List<E>): JsonObject = buildJsonObject {
        put("type", literalType)
        put("values", encode(value))
      }

      override fun unwrap(wrapper: JsonObject): List<E> {
        requireWrapper(wrapper, literalType, "values")
        return decode(wrapper.getValue("values"))
      }
    }

  /**
   * A value of the class [typeFqn], stored as `{"type": "object", "fields": {"<name>": <wrapper>}}`
   * and exported as a constructor call with named arguments (`Contact(name = "Ada", unread = 2)`),
   * [callableFqn] being the callable it writes (the class itself by default). [declare] adds each
   * field, typed by its property reference and codec, and then supplies
   * [AdapterObjectCodecBuilder.construct], which receives the decoded fields by their handles:
   * ```kotlin
   * val ContactCodec: AdapterObjectCodec<Contact> =
   *   AdapterValueCodecs.obj("com.example.Contact") {
   *     val name = field(Contact::name, AdapterValueCodecs.String)
   *     val unread = field(Contact::unread, AdapterValueCodecs.Int, default = 0)
   *     construct { Contact(name = it[name], unread = it[unread]) }
   *   }
   * ```
   *
   * A field may use any codec, a list or another object included. A field with a default may be
   * absent from a stored wrapper; one without is required. Literal-only, like [list].
   */
  fun <T> obj(
    typeFqn: String,
    callableFqn: String = typeFqn,
    declare: AdapterObjectCodecBuilder<T>.() -> Unit,
  ): AdapterObjectCodec<T> = AdapterObjectCodecBuilder<T>(typeFqn).apply(declare).build(callableFqn)
}

/** One field of an [AdapterObjectCodec]: its wire [name], the property it reads and its codec. */
class AdapterObjectField<T, F>
internal constructor(
  val reference: KProperty1<T, F>,
  val codec: AdapterValueCodec<F>,
  private val fallback: (() -> F)?,
) {
  /** The wire name, the Kotlin property's own. */
  val name: String = reference.name

  /** Whether a stored object may leave this field out and take its default. */
  val hasDefault: Boolean
    get() = fallback != null

  internal fun wrapOf(value: T): JsonObject = codec.wrap(reference.get(value))

  internal fun read(fields: JsonObject): F {
    val stored = fields[name]
    if (stored == null || stored is JsonNull) {
      val default = fallback ?: throw IllegalArgumentException("missing field `$name`")
      return default()
    }
    require(stored is JsonObject) { "`$name`: expected a `${codec.literalType}` wrapper" }
    return try {
      codec.unwrap(stored)
    } catch (e: IllegalArgumentException) {
      throw IllegalArgumentException("`$name`: ${e.message}", e)
    }
  }

  /** How an export writes this field: its codec, stored type, and default for an omitted one. */
  internal fun exported(): ExportProjection.Codec.Field? =
    codec.exportCodec?.let { export ->
      ExportProjection.Codec.Field(export, codec.literalType, fallback?.let { codec.wrap(it()) })
    }

  internal fun shape(): JsonObject = buildJsonObject {
    put("name", name)
    put("required", fallback == null)
    fallback?.let { put("default", codec.wrap(it())) }
    codec.shape().forEach { (key, value) -> put(key, value) }
  }
}

/** The decoded fields of one stored object, read by their typed handles. */
class AdapterObjectValues
internal constructor(private val values: Map<AdapterObjectField<*, *>, Any?>) {
  operator fun <F> get(field: AdapterObjectField<*, F>): F {
    require(field in values) { "field `${field.name}` belongs to another object codec" }
    // The codec decoded this entry through the field's own codec, so it is an F.
    @Suppress("UNCHECKED_CAST")
    return values[field] as F
  }
}

/** Declares an [AdapterObjectCodec]'s fields; see [AdapterValueCodecs.obj]. */
class AdapterObjectCodecBuilder<T> internal constructor(private val typeFqn: String) {
  private val fields = mutableListOf<AdapterObjectField<T, *>>()
  private var builds: ((AdapterObjectValues) -> T)? = null
  private var built = false

  /** A required field: a stored object without it is refused. */
  fun <F> field(
    reference: KProperty1<T, F>,
    codec: AdapterValueCodec<F>,
  ): AdapterObjectField<T, F> = add(AdapterObjectField(reference, codec, null))

  /** A field a stored object may leave out, decoding to [default]. */
  fun <F> field(
    reference: KProperty1<T, F>,
    codec: AdapterValueCodec<F>,
    default: F,
  ): AdapterObjectField<T, F> {
    val wrapped = codec.wrap(default)
    require(codec.wrap(codec.unwrap(wrapped)) == wrapped) {
      "$typeFqn.${reference.name}: default does not round trip"
    }
    return add(AdapterObjectField(reference, codec) { default })
  }

  /** How a decoded object is built from its fields; called once, after every [field]. */
  fun construct(build: (AdapterObjectValues) -> T) {
    check(!built && builds == null) { "$typeFqn: construct is declared once" }
    builds = build
  }

  private fun <F> add(field: AdapterObjectField<T, F>): AdapterObjectField<T, F> {
    check(!built && builds == null) { "$typeFqn: declare every field before construct" }
    require(fields.none { it.name == field.name }) { "$typeFqn: duplicate field ${field.name}" }
    fields += field
    return field
  }

  internal fun build(callable: String): AdapterObjectCodec<T> {
    check(!built) { "$typeFqn: already built" }
    require(fields.isNotEmpty()) { "$typeFqn: an object codec declares at least one field" }
    val construct = checkNotNull(builds) { "$typeFqn: declare construct { … }" }
    built = true
    return AdapterObjectCodec(typeFqn, callable, fields.toList(), construct)
  }
}

/**
 * A class-valued codec built by [AdapterValueCodecs.obj]. [encode] is the `fields` object of
 * wrappers, so [decode] and [encode] stay each other's inverse; [wrap] adds `{"type": "object"}`.
 * Unknown stored fields are refused rather than dropped.
 */
class AdapterObjectCodec<T>
internal constructor(
  val typeFqn: String,
  /** The callable an export writes: the class's constructor, or a factory returning it. */
  val callableFqn: String,
  val fields: List<AdapterObjectField<T, *>>,
  private val construct: (AdapterObjectValues) -> T,
) : AdapterValueCodec<T> {
  init {
    require(typeFqn.contains('.') && callableFqn.contains('.')) {
      "an object codec names qualified types: $typeFqn, $callableFqn"
    }
  }

  override val jsonType = "object"
  override val kotlinType = typeFqn
  override val literalType = "object"
  override val stateType: String? = null

  /** Null unless every field's codec can be exported. */
  override val exportCodec: ExportProjection.Codec? = run {
    val exported = fields.map { field -> field.name to (field.exported() ?: return@run null) }
    ExportProjection.Codec.Object(typeFqn, exported.toMap(), callableFqn)
  }

  override val editor: JsonObject = buildJsonObject {
    put("control", "object")
    put("objectType", typeFqn)
    put("fields", JsonArray(fields.map { it.shape() }))
  }

  override fun encode(value: T): JsonElement =
    JsonObject(fields.associate { it.name to it.wrapOf(value) })

  override fun decode(value: JsonElement): T {
    require(value is JsonObject) { "expected the fields of a $typeFqn" }
    val unknown = value.keys - fields.map { it.name }.toSet()
    require(unknown.isEmpty()) { "$typeFqn has no field ${unknown.sorted()}" }
    return construct(
      AdapterObjectValues(fields.associateWith<AdapterObjectField<*, *>, Any?> { it.read(value) })
    )
  }

  override fun wrap(value: T): JsonObject = buildJsonObject {
    put("type", literalType)
    put("fields", encode(value))
  }

  override fun unwrap(wrapper: JsonObject): T {
    requireWrapper(wrapper, literalType, "fields")
    return decode(wrapper.getValue("fields"))
  }
}

/** A composite wrapper is exactly `{"type": [type], [content]: …}`. */
private fun requireWrapper(wrapper: JsonObject, type: String, content: String) {
  val stated = (wrapper["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
  require(stated == type) { "expected a `$type` wrapper, found `$stated`" }
  require(wrapper.keys == setOf("type", content)) {
    "a `$type` wrapper is {\"type\", \"$content\"}, found ${wrapper.keys.sorted()}"
  }
}

private const val LIST_TYPE = "kotlin.collections.List"

/** A colour as a design stores it, before a theme resolves a role. */
sealed interface WireColor {
  val wire: String

  /** No colour authored: the component keeps its own. */
  data object Unset : WireColor {
    override val wire = ""
  }

  data class Argb(val argb: Long) : WireColor {
    override val wire: String
      get() = "#" + argb.toString(16).uppercase().padStart(8, '0')
  }

  data object Transparent : WireColor {
    override val wire = "transparent"
  }

  /** A colour-scheme role by the catalog's token name. */
  data class Role(val token: String) : WireColor {
    override val wire: String
      get() = token
  }

  companion object {
    fun parse(value: String, tokens: Set<String>? = null): WireColor? =
      when {
        value.isEmpty() -> Unset
        value == "transparent" -> Transparent
        value.startsWith("#") -> {
          val hex = value.removePrefix("#")
          when (hex.length) {
            6 -> "FF$hex".toLongOrNull(16)
            8 -> hex.toLongOrNull(16)
            else -> null
          }?.let(::Argb)
        }
        value.all { it.isLetterOrDigit() } && (tokens == null || value in tokens) -> Role(value)
        else -> null
      }
  }
}

/** A property reference and its wire identity, owned by exactly one adapter definition. */
class AdapterProperty<P, T>
internal constructor(
  internal val owner: TypedComponentAdapter<P>,
  val reference: KProperty1<P, T>,
  val name: String,
  val codec: AdapterValueCodec<T>,
  val default: T,
  val bindable: Boolean,
  /** A builder property with no parameter of its own: lowered by the adapter's projection. */
  val wireOnly: Boolean = false,
) {
  fun decode(value: JsonElement?): T = if (value == null) default else codec.decode(value)

  /**
   * The value a stored literal [wrapper] holds (see [AdapterValueCodec.unwrap]), else [default].
   */
  fun unwrap(wrapper: JsonObject?): T = if (wrapper == null) default else codec.unwrap(wrapper)

  /**
   * `{"name", "jsonType", "required": false}`, `jsonType` being `[type, "object"]` when a state
   * wrapper may stand in for the literal. A list or object property is never bindable: it publishes
   * `"array"` / `"object"` as a plain string and adds the codec's recursive
   * [`editor`][AdapterValueCodec.editor].
   */
  internal fun capability(): JsonElement = buildJsonObject {
    put("name", name)
    put(
      "jsonType",
      if (bindable) JsonArray(listOf(JsonPrimitive(codec.jsonType), JsonPrimitive("object")))
      else JsonPrimitive(codec.jsonType),
    )
    put("required", false)
    codec.editor?.let { put("editor", it) }
  }
}

/** A slot handle keeps runtime dispatch and published slot metadata on the same name. */
class AdapterSlot<P>
internal constructor(
  internal val owner: TypedComponentAdapter<P>,
  val name: String,
  val required: Boolean,
) {
  internal fun capability(): JsonElement = buildJsonObject {
    put("name", name)
    put("ordered", true)
    put("cardinality", buildJsonObject { put("min", if (required) 1 else 0) })
    put("acceptedRoles", JsonArray(listOf("Scaffold", "Container", "Leaf").map(::JsonPrimitive)))
  }
}

/** A zero-argument callback referenced from the adapter model. */
class AdapterEvent<P>
internal constructor(
  internal val owner: TypedComponentAdapter<P>,
  val name: String,
  val eventName: String,
)

/** A checked link between a callback's payload type and a property it updates. */
class AdapterStateChange<P, T>
internal constructor(
  internal val owner: TypedComponentAdapter<P>,
  val name: String,
  val property: AdapterProperty<P, T>,
)

/**
 * Subclass beside the real component adapter: property references are compile-checked, while the
 * discovered record stays the source of callable names, types, defaults and imports. Construct all
 * handles in the subclass initializer; publishing or registering freezes the definition.
 */
open class TypedComponentAdapter<P>(val id: String, val component: ComponentRecord) {
  private var frozen = false
  private val declaredNames = mutableSetOf<String>()
  private val declaredEventNames = mutableSetOf<String>()
  private val declaredProperties = mutableListOf<AdapterProperty<P, *>>()
  private val declaredSlots = mutableListOf<AdapterSlot<P>>()
  private val declaredEvents = mutableListOf<AdapterEvent<P>>()
  private val declaredChanges = mutableListOf<AdapterStateChange<P, *>>()
  private var declaredProjection: ExportProjection? = null
  private val projectionRecords = mutableListOf<ComponentRecord>()
  private val projectionFactories = mutableListOf<FactorySignature>()
  private val refusedOnExport = mutableSetOf<String>()

  /** How a design using this component is exported onto real records, when declared. */
  val projection: ExportProjection?
    get() = declaredProjection

  /** Every record the [projection] may lower onto besides [component]: its variant targets. */
  val variantRecords: List<ComponentRecord>
    get() = projectionRecords.filter { it.canonicalId != component.canonicalId }

  init {
    require(Regex("[a-z0-9-]+/[a-z0-9/-]+").matches(id)) { "invalid adapter component id: $id" }
  }

  val properties: List<AdapterProperty<P, *>>
    get() = declaredProperties.toList()

  val slots: List<AdapterSlot<P>>
    get() = declaredSlots.toList()

  fun owns(event: AdapterEvent<P>): Boolean = event.owner === this

  fun owns(property: AdapterProperty<P, *>): Boolean = property.owner === this

  fun owns(slot: AdapterSlot<P>): Boolean = slot.owner === this

  fun owns(change: AdapterStateChange<P, *>): Boolean = change.owner === this

  protected fun <T> property(
    reference: KProperty1<P, T>,
    codec: AdapterValueCodec<T>,
    default: T,
    name: String = reference.name,
    bindable: Boolean = codec.stateType != null,
  ): AdapterProperty<P, T> {
    require(!bindable || codec.stateType in STATE_TYPES) {
      "$id.$name: codec ${codec.jsonType} has no supported state type; use a literal property"
    }
    declare(name)
    // Fail early for custom codecs that cannot represent their own default.
    require(roundTrips(codec, default)) { "$id.$name: default does not round trip" }
    return AdapterProperty(this, reference, name, codec, default, bindable)
      .also(declaredProperties::add)
  }

  /**
   * A builder property that is not a parameter of [component] — `variant`, `containerColor` — and
   * reaches the real call only through this adapter's [exportAs] projection (and its renderer).
   */
  protected fun <T> wireProperty(
    reference: KProperty1<P, T>,
    codec: AdapterValueCodec<T>,
    default: T,
    name: String = reference.name,
    bindable: Boolean = codec.stateType != null,
  ): AdapterProperty<P, T> {
    require(!bindable || codec.stateType in STATE_TYPES) {
      "$id.$name: codec ${codec.jsonType} has no supported state type; use a literal property"
    }
    declare(name)
    require(roundTrips(codec, default)) { "$id.$name: default does not round trip" }
    return AdapterProperty(this, reference, name, codec, default, bindable, wireOnly = true)
      .also(declaredProperties::add)
  }

  /**
   * A scalar's default decodes back to itself, as it always has; a list or object's (whose class
   * need not be a data class) stores the same wrapper once read back.
   */
  private fun <T> roundTrips(codec: AdapterValueCodec<T>, default: T): Boolean {
    if (codec.editor == null) return codec.decode(codec.encode(default)) == default
    val wrapped = codec.wrap(default)
    return codec.wrap(codec.unwrap(wrapped)) == wrapped
  }

  /** One record this adapter's export may become, with how its parameters are written. */
  class ProjectionTarget
  internal constructor(
    internal val record: ComponentRecord,
    internal val target: ExportProjection.Target,
  )

  /**
   * A target for [exportAs]: the real [record], selected by [variants] (null for the default), its
   * [parameters] written from declared properties, the wire [slots] / [events] renamed onto its
   * parameters, what it writes besides when placed in a structural role ([contexts]), and how a
   * slot's children choose the parameters they fill ([slotPatterns]); and the member each child of
   * a DSL parameter is wrapped in ([items]) and the structural role that member opens
   * ([itemRoles]).
   */
  protected fun target(
    record: ComponentRecord,
    parameters: Map<String, ExportProjection.Value>,
    variants: Set<String>? = null,
    slots: Map<String, String> = emptyMap(),
    events: Map<String, String> = emptyMap(),
    contexts: Map<String, ExportProjection.Context> = emptyMap(),
    slotPatterns: Map<String, ExportProjection.SlotPatterns> = emptyMap(),
    synthesize: Map<String, ExportProjection.Synthesis> = emptyMap(),
    items: Map<String, String> = emptyMap(),
    itemRoles: Map<String, String> = emptyMap(),
  ): ProjectionTarget =
    ProjectionTarget(
      record,
      ExportProjection.Target(
        record.canonicalId,
        typedAs(record, parameters),
        variants,
        slots,
        events,
        contexts.mapValues { (_, context) ->
          context.copy(parameters = typedAs(record, context.parameters))
        },
        slotPatterns,
        synthesize,
        items,
        itemRoles,
      ),
    )

  /**
   * [values] with each factory call typed as the parameter it is passed to, where that parameter's
   * type is one of the call's recorded return supertypes: `Arrangement.spacedBy(…)` returns a
   * `HorizontalOrVertical` and is written to `verticalArrangement` as an `Arrangement.Vertical`.
   * The generator matches an argument's type to a parameter's exactly when it picks the overload,
   * so a call typed as its return type would make the record's own overload look inapplicable.
   */
  private fun typedAs(
    record: ComponentRecord,
    values: Map<String, ExportProjection.Value>,
  ): Map<String, ExportProjection.Value> = values.mapValues { (name, value) ->
    if (value !is ExportProjection.Value.Call) return@mapValues value
    fun passedAs(type: String) = projectionFactories.any {
      it.callableFqn == value.callableFqn &&
        it.returnTypeFqn == value.typeFqn &&
        type in it.returnSupertypes
    }
    // The record's own overload decides, as the generator calls it whenever it applies; only a
    // parameter the own overload lacks is typed from the others, and then only when they agree.
    val declared =
      record.parameters.firstOrNull { it.name == name }?.typeFqn?.let(::listOf)
        ?: record.overloads
          .flatMap { it.parameters }
          .filter { it.name == name }
          .mapNotNull { it.typeFqn }
          .distinct()
    val type = declared.singleOrNull()
    if (type != null && type != value.typeFqn && passedAs(type)) value.copy(typeFqn = type)
    else value
  }

  /** A declared property written through its codec, skipped when it equals [omitWhen]. */
  protected fun from(property: AdapterProperty<P, *>, omitWhen: JsonPrimitive? = null) =
    ExportProjection.Value.Property(
      property.name,
      requireNotNull(property.codec.exportCodec) {
        "$id.${property.name}: codec ${property.codec.jsonType} cannot be exported"
      },
      omitWhen,
    )

  /**
   * A nested factory call of declared properties, written when any argument is. [overloads] are the
   * factory's discovered signatures (`TypedAdapterDiscovery.discoverFactories`, narrowed with
   * [named]), so validation checks every argument by name and type against a real overload and the
   * call writes the factory's declared return type.
   */
  protected fun call(
    overloads: Collection<FactorySignature>,
    vararg arguments: Pair<String, ExportProjection.Value>,
  ): ExportProjection.Value.Call {
    require(overloads.isNotEmpty()) { "$id: a factory call needs its discovered signatures" }
    val callable =
      requireNotNull(overloads.map { it.callableFqn }.distinct().singleOrNull()) {
        "$id: overloads of more than one factory: ${overloads.map { it.callableFqn }.distinct()}"
      }
    val returns =
      requireNotNull(overloads.map { it.returnTypeFqn }.distinct().singleOrNull()) {
        "$id: $callable overloads return different types"
      }
    projectionFactories += overloads.filterNot { it in projectionFactories }
    return ExportProjection.Value.Call(callable, returns, arguments.toMap())
  }

  /** Every discovered factory this adapter's projection calls. */
  val factories: List<FactorySignature>
    get() = projectionFactories.toList()

  /**
   * Export this component onto real records: [variant] (a declared property) picks among [targets];
   * exactly one target may be the default. Replaces hand-written export for this id.
   */
  protected fun exportAs(vararg targets: ProjectionTarget, variant: AdapterProperty<P, *>? = null) {
    check(!frozen) { "$id: adapter definition is already frozen" }
    check(declaredProjection == null) { "$id: export projection already declared" }
    require(variant == null || variant.owner === this) { "variant belongs to another adapter" }
    require(targets.any { it.record.canonicalId == component.canonicalId }) {
      "$id: the adapter's own record must be one of its export targets"
    }
    declaredProjection = ExportProjection(targets.map { it.target }, variant?.name)
    projectionRecords += targets.map { it.record }
  }

  /**
   * Declared [properties] the [exportAs] projection deliberately does not write, because no
   * parameter of any target can carry them: Wear `Text`'s `fontVariationSettings` reaches the call
   * only through a `TextStyle` copy. They stay on the shelf; a host refuses a node that sets one
   * (it is outside [ExportProjection.properties]), and validation stops requiring them exported.
   */
  protected fun exportRefuses(vararg properties: AdapterProperty<P, *>) {
    check(!frozen) { "$id: adapter definition is already frozen" }
    for (property in properties) {
      require(property.owner === this) { "${property.name} belongs to another adapter" }
      require(refusedOnExport.add(property.name)) { "$id.${property.name}: already refused" }
    }
  }

  protected fun slot(name: String, required: Boolean = false): AdapterSlot<P> {
    declare(name)
    return AdapterSlot(this, name, required).also(declaredSlots::add)
  }

  protected fun event(reference: KProperty1<P, () -> Unit>): AdapterEvent<P> {
    val eventName = reference.name.removePrefix("on").replaceFirstChar { it.lowercase() }
    require(eventName.isNotBlank() && eventName !in declaredEventNames) {
      "$id: duplicate or blank event name $eventName"
    }
    declare(reference.name)
    declaredEventNames.add(eventName)
    return AdapterEvent(this, reference.name, eventName).also(declaredEvents::add)
  }

  protected fun <T> stateChange(
    reference: KProperty1<P, (T) -> Unit>,
    property: AdapterProperty<P, T>,
  ): AdapterStateChange<P, T> {
    require(property.owner === this) { "state property belongs to another adapter" }
    require(property.bindable && property.codec.stateType in STATE_TYPES) {
      "$id.${property.name}: state callback requires a bindable property with a supported state type"
    }
    declare(reference.name)
    return AdapterStateChange(this, reference.name, property).also(declaredChanges::add)
  }

  private fun declare(name: String) {
    check(!frozen) { "$id: adapter definition is already frozen" }
    require(name.isNotBlank() && declaredNames.add(name)) {
      "$id: duplicate or blank parameter $name"
    }
  }

  /**
   * Additional validation against the freshly discovered signature, suitable for an app's tests.
   */
  fun validateAgainst(record: ComponentRecord): List<String> = buildList {
    if (record.canonicalId != component.canonicalId) add("$id: component record identity changed")
    if (!record.signatureKnown) add("$id: component signature is unknown")
    val parameters = record.parameters.associateBy { it.name }
    for (property in declaredProperties) {
      if (property.wireOnly) {
        if (declaredProjection == null) add("$id.${property.name}: a wire property needs exportAs")
        continue
      }
      val parameter = parameters[property.name]
      if (parameter == null) add("$id: unknown property ${property.name}")
      else if (parameter.typeFqn != property.codec.kotlinType || parameter.nullable) {
        add(
          "$id.${property.name}: expected ${property.codec.kotlinType}, discovered ${parameter.typeFqn}${if (parameter.nullable) "?" else ""}"
        )
      }
    }
    // A projection's targets rename slots and events onto whichever overload they resolve to;
    // [validateProjection] checks them there, the record's own target included.
    if (declaredProjection == null) {
      for (slot in declaredSlots) {
        if (parameters[slot.name]?.composableSlot != true)
          add("$id: ${slot.name} is not a composable slot")
      }
      for (event in declaredEvents) {
        if (!matchesCallback(parameters[event.name], null))
          add("$id: ${event.name} is not a () -> Unit callback")
      }
    }
    for (change in declaredChanges) {
      val type = change.property.codec.kotlinType.substringAfterLast('.')
      if (!matchesCallback(parameters[change.name], change.property.codec.kotlinType))
        add("$id: ${change.name} is not a ($type) -> Unit callback")
    }
    if (declaredProjection == null) {
      for (parameter in record.parameters) {
        if (!parameter.hasDefault && parameter.name !in declaredNames)
          add("$id: required parameter ${parameter.name} is not adapted")
      }
    }
    declaredProjection?.let { projection -> addAll(validateProjection(projection, record)) }
  }

  /**
   * Every target of [projection] against its discovered record ([record] stands in for the
   * adapter's own): parameters exist and take what is written, slots and events name real slots and
   * callbacks, and every required parameter is written, renamed onto, or supplied by the record's
   * own call site.
   */
  private fun validateProjection(projection: ExportProjection, record: ComponentRecord) =
    buildList {
      val properties = declaredProperties.associateBy { it.name }
      projection.variantProperty?.let { name ->
        if (name !in properties) add("$id: variant property $name is not declared")
      }
      // A declared property no projection reads would be refused by every host on every node.
      val read = projection.properties
      for (property in declaredProperties) {
        val refused = property.name in refusedOnExport
        if (property.name !in read && !refused) {
          add("$id: property ${property.name} is not exported")
        }
        if (property.name in read && refused) {
          add("$id: property ${property.name} is refused on export but the projection writes it")
        }
      }
      val records =
        projectionRecords.associateBy { it.canonicalId } + (record.canonicalId to record)
      for (target in projection.targets) {
        val targetRecord = records[target.record]
        if (targetRecord == null) {
          add("$id: export target ${target.record} has no discovered record")
          continue
        }
        val where = "$id -> ${targetRecord.symbol.name}"
        if (!targetRecord.signatureKnown) add("$where: signature is unknown")
        val written =
          target.parameters.mapValues { (name, value) ->
            writtenType(value, properties, "$where.$name", this)
          }
        val candidates: List<ComponentOverload?> = targetRecord.overloads.ifEmpty { listOf(null) }
        // The overload the record speaks for, which the generator calls whenever it applies.
        val own = candidates.indexOfFirst {
          it == null ||
            it.descriptor == targetRecord.symbol.descriptor ||
            it.parameters == targetRecord.parameters
        }
        for ((parameter, synthesis) in target.synthesize) {
          for (property in synthesis.properties.values) {
            if (property !in properties)
              add("$where.$parameter: synthesizes from property $property, which is not declared")
          }
        }
        for ((slot, _) in target.slotPatterns) {
          if (declaredSlots.none { it.name == slot })
            add("$where: slot pattern for undeclared slot $slot")
        }
        // Against every way the target fills its slots: a pattern may route only one shape to the
        // DSL parameter, and the others leave it unfilled.
        val everyFill = fillsOf(target).flatMap { (_, fills) -> fills }.map { it.parameter }.toSet()
        for (parameter in target.items.keys) {
          if (parameter !in everyFill) add("$where: items for $parameter, which no slot fills")
        }
        // Resolved as Kotlin resolves named arguments: some overload must take every argument the
        // target writes and default the rest. A record without overloads stands for its own one.
        // A patterned slot fills different parameters per pattern, and each fill is its own call.
        for ((shape, fills) in fillsOf(target)) {
          val at = if (shape == null) where else "$where [$shape]"
          // A pattern is how the overload is chosen, so it must choose exactly one.
          val exactlyOne = shape != null
          val mismatches = candidates.map { overload ->
            targetMismatches(
              target,
              written,
              fills,
              overload?.parameters ?: targetRecord.parameters,
              at,
            )
          }
          addAll(resolution(at, candidates, mismatches, own, exactlyOne))
          // In a role the context's arguments are written too, so the call must still resolve.
          for ((role, context) in target.contexts) {
            val inRole = "$at in $role"
            val alsoWritten =
              context.parameters.mapValues { (name, value) ->
                writtenType(value, properties, "$inRole.$name", this)
              } +
                (if (context.modifier.isEmpty()) emptyMap() else mapOf("modifier" to MODIFIER_TYPE))
            val roleMismatches = candidates.map { overload ->
              targetMismatches(
                target,
                written + alsoWritten,
                fills,
                overload?.parameters ?: targetRecord.parameters,
                inRole,
              )
            }
            // Only what the role adds: the target's own mismatches are reported once, above.
            if (mismatches.any { it.isEmpty() })
              addAll(resolution(inRole, candidates, roleMismatches, own, exactlyOne))
          }
        }
      }
    }

  /** One composable parameter a wire slot fills: from which slot, and whether on every node. */
  private data class SlotFill(val slot: String, val parameter: String, val always: Boolean)

  /**
   * The ways [target] fills its parameters from wire slots, each labelled for messages: one,
   * unlabelled, when no slot is patterned; otherwise one per pattern of the patterned slot, and its
   * `otherwise`, each beside the plain renames of the other slots.
   */
  private fun fillsOf(target: ExportProjection.Target): List<Pair<String?, List<SlotFill>>> {
    val plain =
      declaredSlots
        .filter { it.name !in target.slotPatterns }
        .map { SlotFill(it.name, target.slots[it.name] ?: it.name, always = true) } +
        target.synthesize.map { (parameter, synthesis) ->
          SlotFill("synthesized ${synthesis.component}", parameter, always = true)
        }
    val (slot, patterns) =
      target.slotPatterns.entries.singleOrNull() ?: return listOf(null to plain)
    return patterns.patterns.mapIndexed { index, pattern ->
      "$slot pattern ${index + 1}" to
        plain + pattern.parameters.map { (parameter, always) -> SlotFill(slot, parameter, always) }
    } +
      listOfNotNull(
        patterns.otherwise?.let { "$slot otherwise" to plain + SlotFill(slot, it, always = true) }
      )
  }

  /**
   * Nothing when the call resolves as the generator resolves it, else why not: the record's [own]
   * overload when it takes the call, otherwise exactly one other. Kotlin refuses a call two
   * overloads take equally, and so does the generator, so two alternates taking it choose nothing.
   * With [exactlyOne] (a slot pattern, which is how the overload is chosen) even the record's own
   * overload must be the only one.
   */
  private fun resolution(
    where: String,
    candidates: List<ComponentOverload?>,
    mismatches: List<List<String>>,
    own: Int,
    exactlyOne: Boolean = false,
  ): List<String> =
    when {
      !exactlyOne && own >= 0 && mismatches[own].isEmpty() -> emptyList()
      mismatches.count { it.isEmpty() } > 1 ->
        listOf(
          "$where: overloads " +
            candidates
              .zip(mismatches)
              .filter { it.second.isEmpty() }
              .joinToString(" and ") { "${it.first?.descriptor}" } +
            " both take it, so it chooses neither"
        )
      mismatches.any { it.isEmpty() } -> emptyList()
      mismatches.size == 1 -> mismatches.single()
      else ->
        listOf(
          "$where: no overload takes this target: " +
            candidates.zip(mismatches).joinToString("; ") { (overload, reasons) ->
              "${overload?.descriptor}: " +
                reasons.joinToString(", ") { it.removePrefix(where).removePrefix(": ") }
            }
        )
    }

  /** Why [target] cannot call the overload taking [overloadParameters]; empty when it can. */
  private fun targetMismatches(
    target: ExportProjection.Target,
    written: Map<String, String?>,
    fills: List<SlotFill>,
    overloadParameters: List<TargetParameter>,
    where: String,
  ): List<String> = buildList {
    val parameters = overloadParameters.associateBy { it.name }
    for ((name, type) in written) {
      val parameter = parameters[name]
      if (parameter == null) {
        add("$where: no parameter $name")
        continue
      }
      if (type != null && !assignable(type, parameter.typeFqn))
        add("$where.$name: writes $type, parameter takes ${parameter.typeFqn}")
    }
    for (fill in fills) {
      val parameter = parameters[fill.parameter]
      // A DSL parameter is filled through its item member, so it is no composable slot itself.
      if (fill.parameter in target.items) {
        // Children declared through items return nothing, so only a `Scope.() -> Unit` takes them.
        if (parameter?.scopeDslReceiver == null || parameter.lambdaReturnTypeFqn != "kotlin.Unit")
          add(
            "$where: slot ${fill.slot} -> ${fill.parameter} wraps its children in " +
              "`${target.items.getValue(fill.parameter)}`, but is not a `Scope.() -> Unit` DSL"
          )
      } else if (parameter?.composableSlot != true)
        add("$where: slot ${fill.slot} -> ${fill.parameter} is not a composable slot")
    }
    for ((event, name) in target.events) {
      if (declaredEvents.none { it.eventName == event }) add("$where: event $event is not declared")
      if (!matchesCallback(parameters[name], null))
        add("$where: $name is not a () -> Unit callback")
    }
    // Only what the projection actually writes: a declared property it leaves out is not passed by
    // name, whatever the record calls its parameters.
    val covered =
      written.keys +
        fills.filter { it.always }.map { it.parameter } +
        target.events.values +
        declaredChanges.map { it.name }
    // The generator writes the chosen overload's own call: a required parameter nothing writes gets
    // that parameter's placeholder (`onClick = {}`). The record's recorded call site belongs to its
    // own overload and is not printed for another. A required composable slot left to its `{}`
    // placeholder would draw nothing where the component expects content, so it is reported.
    for (parameter in overloadParameters) {
      if (parameter.hasDefault || parameter.name in covered) continue
      if (!parameter.composableSlot && ComponentSnippets.placeholderFor(parameter) != null) continue
      add("$where: required parameter ${parameter.name} is not written")
    }
  }

  /** The Kotlin type [value] writes, after checking the properties it reads exist and export. */
  private fun writtenType(
    value: ExportProjection.Value,
    properties: Map<String, AdapterProperty<P, *>>,
    where: String,
    problems: MutableList<String>,
  ): String? =
    when (value) {
      // A scalar's Kotlin type is ambiguous (`Whole` is an Int or a Long); a typed value says it.
      is ExportProjection.Value.Fixed ->
        when (val fixed = value.value) {
          is ScreenValue.Text -> "kotlin.String"
          is ScreenValue.Bool -> "kotlin.Boolean"
          else -> fixed.typeFqn
        }
      is ExportProjection.Value.Call -> {
        val written =
          value.arguments.mapValues { (name, argument) ->
            writtenType(argument, properties, "$where.$name", problems)
          }
        val overloads = projectionFactories.filter { it.callableFqn == value.callableFqn }
        if (overloads.isEmpty()) {
          problems +=
            "$where: factory ${value.callableFqn} was not discovered; call it through its FactorySignature"
        } else {
          // Typed as its return type, or as a supertype it is passed as (see [typedAs]).
          if (
            overloads.none {
              it.returnTypeFqn == value.typeFqn || value.typeFqn in it.returnSupertypes
            }
          )
            problems +=
              "$where: ${value.callableFqn} returns ${overloads.first().returnTypeFqn}, not ${value.typeFqn}"
          // A property-backed argument (or a nested call of them) drops out when its property is
          // unset; only a fixed one is written on every node, so only it may fill a required slot.
          // Except the call's one argument when nothing is fixed: the call itself is written only
          // when some argument is, so whenever `spacedBy(space = …)` is written, `space` is.
          val fixed = value.arguments.filterValues { it is ExportProjection.Value.Fixed }.keys
          val always =
            if (fixed.isEmpty() && value.arguments.size == 1) value.arguments.keys else fixed
          val mismatches = overloads.map { overloadMismatch(it, written, always) }
          if (mismatches.none { it == null }) {
            problems +=
              "$where: no overload of ${value.callableFqn} takes " +
                written.keys.sorted().joinToString(", ", "(", ")") +
                ": " +
                mismatches.filterNotNull().joinToString("; ")
          }
        }
        value.typeFqn
      }
      is ExportProjection.Value.Property -> {
        val property = properties[value.name]
        if (property == null) {
          problems += "$where: property ${value.name} is not declared"
          null
        } else
          when (val codec = value.codec) {
            ExportProjection.Codec.Text -> "kotlin.String"
            ExportProjection.Codec.Bool -> "kotlin.Boolean"
            ExportProjection.Codec.Whole -> "kotlin.Int"
            ExportProjection.Codec.Fractional32 -> "kotlin.Float"
            is ExportProjection.Codec.Color -> COLOR_TYPE
            is ExportProjection.Codec.Enum -> codec.typeFqn
            ExportProjection.Codec.MaterialIcon -> IMAGE_VECTOR_TYPE
            is ExportProjection.Codec.Unit ->
              if (codec.unit == "sp") "androidx.compose.ui.unit.TextUnit"
              else "androidx.compose.ui.unit.Dp"
            is ExportProjection.Codec.Token -> codec.typeFqn
            // The erased classifier, as discovery records a parameter's; the element type is held
            // by the property reference and the element codec at compile time.
            is ExportProjection.Codec.ListOf -> LIST_TYPE
            is ExportProjection.Codec.Object -> codec.typeFqn
          }
      }
    }

  /**
   * Why [overload] cannot take [written] (argument name to the type written, null when unknown), or
   * null when it can: Kotlin resolves named arguments against the overload whose parameters they
   * name and whose other parameters are all defaulted. A required parameter must be among [always],
   * the arguments written on every node, since lowering omits an unset property's.
   */
  /**
   * Whether a value of [type] may be passed where [to] is taken: the same type, or a discovered
   * factory's return type whose recorded supertypes include it (`Arrangement.spacedBy` returns a
   * `HorizontalOrVertical`, which is a `Vertical`).
   */
  private fun assignable(type: String, to: String?): Boolean =
    type == to ||
      (to != null &&
        projectionFactories.any { it.returnTypeFqn == type && to in it.returnSupertypes })

  private fun overloadMismatch(
    overload: FactorySignature,
    written: Map<String, String?>,
    always: Set<String>,
  ): String? {
    val parameters = overload.parameters.associateBy { it.name }
    val reasons = buildList {
      for ((name, type) in written) {
        val parameter = parameters[name]
        when {
          parameter == null -> add("no parameter $name")
          type != null && parameter.typeFqn != null && !assignable(type, parameter.typeFqn) ->
            add("$name takes ${parameter.typeFqn}, written $type")
        }
      }
      for (parameter in overload.parameters) {
        if (parameter.hasDefault) continue
        if (parameter.name !in written) add("required ${parameter.name} is not written")
        else if (parameter.name !in always)
          add("required ${parameter.name} is written only when its property is set")
      }
    }
    return reasons.takeIf { it.isNotEmpty() }?.joinToString(", ")
  }

  // Callback input classifiers are not qualified in the current discovery contract. Check exact
  // tokens (never erase arbitrary package substrings), structural function/receiver flags and the
  // qualified return classifier. The real compiled component call remains the input-type proof.
  private fun matchesCallback(parameter: TargetParameter?, input: String?): Boolean {
    if (
      parameter == null ||
        parameter.nullable ||
        parameter.composableSlot ||
        parameter.scopeDslReceiver != null ||
        parameter.lambdaReturnTypeFqn != "kotlin.Unit"
    )
      return false
    val arity = if (input == null) 0 else 1
    if (parameter.typeFqn != "kotlin.Function$arity") return false
    val inputs = if (input == null) listOf("") else listOf(input, input.substringAfterLast('.'))
    val spelling = parameter.type.filterNot { it.isWhitespace() }
    return inputs.any { spelling == "($it)->Unit" || spelling == "($it)->kotlin.Unit" }
  }

  /** Freeze and validate before either catalog generation or runtime registration. */
  fun freeze() {
    val issues = validateAgainst(component)
    require(issues.isEmpty()) { issues.joinToString("\n") }
    frozen = true
  }

  internal fun recordWithPolicy(): ComponentRecord {
    freeze()
    val builder = component.builder?.newBuilder() ?: BuilderPolicy.Builder()
    builder.id = id
    builder.canvas = id
    builder.stateCallbacks = declaredChanges.map { change ->
      BuilderPair.Builder(
          change.name,
          "${change.property.name}:${requireNotNull(change.property.codec.stateType)}",
        )
        .build()
    }
    return component.newBuilder().also { it.builder = builder.build() }.build()
  }

  internal fun policy(): UiBuilderAuthoredComponent {
    freeze()
    return UiBuilderAuthoredComponent.Builder()
      .also { b ->
        b.record = component.canonicalId
        b.canvas = id
        b.propertyCapabilities = declaredProperties.map { it.capability() }
        b.slotCapabilities = declaredSlots.map { it.capability() }
        // A refused property's default is not seeded: a new node setting it would be refused.
        b.insertContent = buildJsonObject {
          put(
            "properties",
            JsonObject(
              declaredProperties
                .filter { it.name !in refusedOnExport }
                .associate { property -> property.name to defaultValue(property) }
            ),
          )
        }
        // How a node becomes the real call, for the builder's export; see [exportAs].
        b.exportProjection = projection?.let(ExportProjectionJson::encode)
      }
      .build()
  }

  /** The stored wrapper of [property]'s default: `{type, value}` for a scalar, as ever. */
  private fun <T> defaultValue(property: AdapterProperty<P, T>): JsonObject =
    property.codec.wrap(property.default)
}

/** The overloads of [callableFqn] among these discovered factories. */
fun Collection<FactorySignature>.named(callableFqn: String): List<FactorySignature> = filter {
  it.callableFqn == callableFqn
}

/** A portable pair, encoded using the existing serializers and consumed by existing builders. */
class GeneratedAdapterCatalog
internal constructor(val record: ComponentRecordFile, val catalog: UiBuilderCatalogFile)

/**
 * Generates an explicitly selected inventory. Undeclared app components never leak into the shelf.
 * No app class is loaded by discovery: call this from a separate, opt-in catalog export entry
 * point.
 */
object TypedAdapterCatalog {
  private fun variantId(adapter: TypedComponentAdapter<*>, variant: ComponentRecord) =
    "${adapter.id}-${variant.symbol.name.lowercase()}"

  fun generate(
    discovered: ComponentRecordFile,
    cover: UiBuilderCatalogs.CoverSheet,
    policy: UiBuilderPolicyFile,
    adapters: List<TypedComponentAdapter<*>>,
    /**
     * Every factory the adapters' projections call, freshly discovered
     * (`TypedAdapterDiscovery.discoverFactories`); an adapter built against other signatures is
     * stale, exactly as a stale component record is.
     */
    factories: List<FactorySignature> = emptyList(),
  ): GeneratedAdapterCatalog {
    require(adapters.isNotEmpty()) { "declare at least one adapter" }
    require(adapters.map { it.id }.distinct().size == adapters.size) {
      "duplicate adapter component ids"
    }
    require(adapters.map { it.component.canonicalId }.distinct().size == adapters.size) {
      "duplicate adapter component records"
    }
    require(
      discovered.components.map { it.canonicalId }.distinct().size == discovered.components.size
    ) {
      "duplicate discovered component records"
    }
    require(policy.components.isEmpty()) {
      "typed catalog owns its component inventory; do not merge another component inventory"
    }
    val byId = discovered.components.associateBy { it.canonicalId }
    adapters.forEach { adapter ->
      for (expected in listOf(adapter.component) + adapter.variantRecords) {
        val current =
          requireNotNull(byId[expected.canonicalId]) {
            "${adapter.id}: record ${expected.canonicalId} is absent from discovery"
          }
        require(current == expected) {
          "${adapter.id}: adapter was built against a stale component record"
        }
      }
      for (factory in adapter.factories) {
        require(factory in factories) {
          "${adapter.id}: factory ${factory.callableFqn} is absent from discovery, or stale"
        }
      }
      adapter.freeze()
    }
    // A projection's variant targets (`FilledTonalButton` for a `variant` of `Button`) ship in the
    // record so export can print them, kept off the shelf: the adapter's id is their one identity.
    val variants =
      adapters
        .flatMap { adapter -> adapter.variantRecords.map { adapter to it } }
        .distinctBy { it.second.canonicalId }
        .filter { (_, record) -> adapters.none { it.component.canonicalId == record.canonicalId } }
    val record =
      discovered
        .newBuilder()
        .also { b ->
          b.components = adapters.map { it.recordWithPolicy() } + variants.map { it.second }
          b.builderOrphans = emptyList()
        }
        .build()
    val authored =
      policy
        .newBuilder()
        .also { b ->
          b.components =
            adapters.associate { it.id to it.policy() } +
              variants.associate { (adapter, variant) ->
                variantId(adapter, variant) to
                  UiBuilderAuthoredComponent.Builder()
                    .also {
                      it.record = variant.canonicalId
                      it.excluded = "an export target of ${adapter.id}, selected by its variant"
                    }
                    .build()
              }
        }
        .build()
    val catalog = requireNotNull(UiBuilderCatalogs.generate(record, cover, authored))
    // The one finding generation expects is the variant targets it kept off the shelf itself.
    val variantIds = variants.map { (adapter, variant) -> variantId(adapter, variant) }.toSet()
    val unexpected =
      catalog.diagnostics.filterNot {
        it.code == UiBuilderCatalogs.Diagnostics.COMPONENT_EXCLUDED && it.subject in variantIds
      }
    require(unexpected.isEmpty()) { unexpected.joinToString("\n") { "${it.code}: ${it.message}" } }
    return GeneratedAdapterCatalog(record, catalog)
  }
}

private const val COLOR_TYPE = "androidx.compose.ui.graphics.Color"

private const val MODIFIER_TYPE = "androidx.compose.ui.Modifier"

private const val IMAGE_VECTOR_TYPE = "androidx.compose.ui.graphics.vector.ImageVector"
