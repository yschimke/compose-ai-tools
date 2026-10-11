package ee.schimke.composeai.discovery

import kotlin.reflect.KProperty1
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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
   * One of a closed set of wire values, each naming a Kotlin member ([members]: wire value to the
   * member's qualified name). Decodes to the wire value; the renderer maps it to the real member.
   */
  fun enumOf(typeFqn: String, members: Map<String, String>): AdapterValueCodec<String> =
    object : AdapterValueCodec<String> {
      override val jsonType = "string"
      override val kotlinType = typeFqn
      override val stateType: String? = null
      override val exportCodec = ExportProjection.Codec.Enum(typeFqn, members)

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

      override fun encode(value: String): JsonElement = JsonPrimitive(value)

      override fun decode(value: JsonElement): String {
        require(value is JsonPrimitive && value.isString) { "expected a token name" }
        require(export.tokens == null || value.content in export.tokens) {
          "expected one of ${export.tokens?.sorted()}"
        }
        return value.content
      }
    }
}

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
  private var declaredProjection: ExportProjection? = null
  private val projectionRecords = mutableListOf<ComponentRecord>()
  private val projectionFactories = mutableListOf<FactorySignature>()

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
    require(codec.decode(codec.encode(default)) == default) {
      "$id.$name: default does not round trip"
    }
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
    require(codec.decode(codec.encode(default)) == default) {
      "$id.$name: default does not round trip"
    }
    return AdapterProperty(this, reference, name, codec, default, bindable, wireOnly = true)
      .also(declaredProperties::add)
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
   * parameters, and what it writes besides when placed in a structural role ([contexts]).
   */
  protected fun target(
    record: ComponentRecord,
    parameters: Map<String, ExportProjection.Value>,
    variants: Set<String>? = null,
    slots: Map<String, String> = emptyMap(),
    events: Map<String, String> = emptyMap(),
    contexts: Map<String, ExportProjection.Context> = emptyMap(),
  ): ProjectionTarget =
    ProjectionTarget(
      record,
      ExportProjection.Target(record.canonicalId, parameters, variants, slots, events, contexts),
    )

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
        if (property.name !in read) add("$id: property ${property.name} is not exported")
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
        // Resolved as Kotlin resolves named arguments: some overload must take every argument the
        // target writes and default the rest. A record without overloads stands for its own one.
        val candidates: List<ComponentOverload?> = targetRecord.overloads.ifEmpty { listOf(null) }
        val mismatches = candidates.map { overload ->
          targetMismatches(
            target,
            written,
            overload?.parameters ?: targetRecord.parameters,
            targetRecord.code?.call,
            where,
          )
        }
        addAll(resolution(where, candidates, mismatches))
        // In a role the context's arguments are written too, so the call must still resolve.
        for ((role, context) in target.contexts) {
          val inRole = "$where in $role"
          val alsoWritten =
            context.parameters.mapValues { (name, value) ->
              writtenType(value, properties, "$inRole.$name", this)
            } + (if (context.modifier.isEmpty()) emptyMap() else mapOf("modifier" to MODIFIER_TYPE))
          val roleMismatches = candidates.map { overload ->
            targetMismatches(
              target,
              written + alsoWritten,
              overload?.parameters ?: targetRecord.parameters,
              targetRecord.code?.call,
              inRole,
            )
          }
          // Only what the role adds: the target's own mismatches are reported once, above.
          if (mismatches.any { it.isEmpty() })
            addAll(resolution(inRole, candidates, roleMismatches))
        }
      }
    }

  /** Nothing when some overload takes the call, else why none does. */
  private fun resolution(
    where: String,
    candidates: List<ComponentOverload?>,
    mismatches: List<List<String>>,
  ): List<String> =
    when {
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
    overloadParameters: List<TargetParameter>,
    placeholders: String?,
    where: String,
  ): List<String> = buildList {
    val parameters = overloadParameters.associateBy { it.name }
    for ((name, type) in written) {
      val parameter = parameters[name]
      if (parameter == null) {
        add("$where: no parameter $name")
        continue
      }
      if (type != null && type != parameter.typeFqn)
        add("$where.$name: writes $type, parameter takes ${parameter.typeFqn}")
    }
    for (slot in declaredSlots) {
      val name = target.slots[slot.name] ?: slot.name
      if (parameters[name]?.composableSlot != true)
        add("$where: slot ${slot.name} -> $name is not a composable slot")
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
        declaredSlots.map { target.slots[it.name] ?: it.name } +
        target.events.values +
        declaredChanges.map { it.name }
    // The record's call site is printed with every export, as named arguments, so it fills a
    // required parameter of any overload that has one by that name and breaks one that has not.
    val placed = placeholders?.let(::namedArguments).orEmpty()
    for (name in placed) {
      if (name !in parameters)
        add("$where: the call site writes $name, which it has no parameter for")
    }
    for (parameter in overloadParameters) {
      if (parameter.hasDefault || parameter.name in covered || parameter.name in placed) continue
      add("$where: required parameter ${parameter.name} is not written")
    }
  }

  /** The named arguments of a call snippet's outermost call: `Button(onClick = {})` → `onClick`. */
  private fun namedArguments(call: String): Set<String> {
    val open = call.indexOf('(')
    if (open < 0) return emptySet()
    val names = mutableSetOf<String>()
    var depth = 0
    var start = open + 1
    var quoted = false
    var i = open
    while (i < call.length) {
      val c = call[i]
      when {
        quoted && c == '\\' -> i++
        c == '"' -> quoted = !quoted
        quoted -> Unit
        c == '(' || c == '{' || c == '[' -> depth++
        c == ')' || c == '}' || c == ']' -> depth--
        c == ',' && depth == 1 -> start = i + 1
        c == '=' &&
          depth == 1 &&
          call.getOrNull(i + 1) != '=' &&
          call.getOrNull(i - 1) !in OPERATOR_CHARS ->
          call.substring(start, i).trim().takeIf { it.isNotEmpty() }?.let(names::add)
      }
      if (depth == 0 && i > open) break
      i++
    }
    return names
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
          if (overloads.none { it.returnTypeFqn == value.typeFqn })
            problems +=
              "$where: ${value.callableFqn} returns ${overloads.first().returnTypeFqn}, not ${value.typeFqn}"
          // A property-backed argument (or a nested call of them) drops out when its property is
          // unset; only a fixed one is written on every node, so only it may fill a required slot.
          val always = value.arguments.filterValues { it is ExportProjection.Value.Fixed }.keys
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
            is ExportProjection.Codec.Unit ->
              if (codec.unit == "sp") "androidx.compose.ui.unit.TextUnit"
              else "androidx.compose.ui.unit.Dp"
            is ExportProjection.Codec.Token -> codec.typeFqn
          }
      }
    }

  /**
   * Why [overload] cannot take [written] (argument name to the type written, null when unknown), or
   * null when it can: Kotlin resolves named arguments against the overload whose parameters they
   * name and whose other parameters are all defaulted. A required parameter must be among [always],
   * the arguments written on every node, since lowering omits an unset property's.
   */
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
          type != null && parameter.typeFqn != null && type != parameter.typeFqn ->
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
        // How a node becomes the real call, for the builder's export; see [exportAs].
        b.exportProjection = projection?.let(ExportProjectionJson::encode)
      }
      .build()
  }

  private fun <T> defaultValue(property: AdapterProperty<P, T>): JsonElement =
    property.codec.encode(property.default)
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

/**
 * The characters that make a following `=` part of an operator (`<=`, `!=`, `==`), not an argument.
 */
private val OPERATOR_CHARS = setOf('=', '!', '<', '>')
