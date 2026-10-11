package ee.schimke.composeai.discovery

import kotlin.reflect.KProperty1
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
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

  fun encode(value: T): JsonElement

  fun decode(value: JsonElement): T
}

/**
 * Strict scalar codecs; malformed authored values are errors, never silently replaced by defaults.
 */
object AdapterValueCodecs {
  val String: AdapterValueCodec<String> =
    object : AdapterValueCodec<String> {
      override val jsonType = "string"
      override val kotlinType = "kotlin.String"

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

      override fun encode(value: Int): JsonElement = JsonPrimitive(value)

      override fun decode(value: JsonElement): Int {
        require(value is JsonPrimitive && !value.isString) { "expected a JSON integer" }
        return requireNotNull(value.intOrNull) { "expected a JSON integer" }
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
) {
  fun decode(value: JsonElement?): T = if (value == null) default else codec.decode(value)

  internal fun capability(): JsonElement = buildJsonObject {
    put("name", name)
    put(
      "jsonType",
      if (bindable) JsonArray(listOf(JsonPrimitive(codec.jsonType), JsonPrimitive("object")))
      else JsonPrimitive(codec.jsonType),
    )
    put("required", false)
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
    require(codec.decode(codec.encode(default)) == default) {
      "$id.$name: default does not round trip"
    }
    return AdapterProperty(this, reference, name, codec, default, bindable)
      .also(declaredProperties::add)
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
      val parameter = parameters[property.name]
      if (parameter == null) add("$id: unknown property ${property.name}")
      else if (parameter.typeFqn != property.codec.kotlinType || parameter.nullable) {
        add(
          "$id.${property.name}: expected ${property.codec.kotlinType}, discovered ${parameter.typeFqn}${if (parameter.nullable) "?" else ""}"
        )
      }
    }
    for (slot in declaredSlots) {
      if (parameters[slot.name]?.composableSlot != true)
        add("$id: ${slot.name} is not a composable slot")
    }
    for (event in declaredEvents) {
      if (!matchesCallback(parameters[event.name], null))
        add("$id: ${event.name} is not a () -> Unit callback")
    }
    for (change in declaredChanges) {
      val type = change.property.codec.kotlinType.substringAfterLast('.')
      if (!matchesCallback(parameters[change.name], change.property.codec.kotlinType))
        add("$id: ${change.name} is not a ($type) -> Unit callback")
    }
    for (parameter in record.parameters) {
      if (!parameter.hasDefault && parameter.name !in declaredNames)
        add("$id: required parameter ${parameter.name} is not adapted")
    }
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
        b.insertContent = buildJsonObject {
          put(
            "properties",
            JsonObject(
              declaredProperties.associate { property ->
                property.name to
                  buildJsonObject {
                    put("type", property.codec.literalType)
                    put("value", defaultValue(property))
                  }
              }
            ),
          )
        }
      }
      .build()
  }

  private fun <T> defaultValue(property: AdapterProperty<P, T>): JsonElement =
    property.codec.encode(property.default)
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
  fun generate(
    discovered: ComponentRecordFile,
    cover: UiBuilderCatalogs.CoverSheet,
    policy: UiBuilderPolicyFile,
    adapters: List<TypedComponentAdapter<*>>,
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
      val current =
        requireNotNull(byId[adapter.component.canonicalId]) {
          "${adapter.id}: record is absent from discovery"
        }
      require(current == adapter.component) {
        "${adapter.id}: adapter was built against a stale component record"
      }
      adapter.freeze()
    }
    val record =
      discovered
        .newBuilder()
        .also { b ->
          b.components = adapters.map { it.recordWithPolicy() }
          b.builderOrphans = emptyList()
        }
        .build()
    val authored =
      policy
        .newBuilder()
        .also { b -> b.components = adapters.associate { it.id to it.policy() } }
        .build()
    val catalog = requireNotNull(UiBuilderCatalogs.generate(record, cover, authored))
    require(catalog.diagnostics.isEmpty()) {
      catalog.diagnostics.joinToString("\n") { "${it.code}: ${it.message}" }
    }
    return GeneratedAdapterCatalog(record, catalog)
  }
}
