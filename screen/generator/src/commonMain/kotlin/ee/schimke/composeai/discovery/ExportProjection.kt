package ee.schimke.composeai.discovery

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * How a catalog component's wire properties become a call to the REAL library composable.
 *
 * A builder design stores what a designer chose (`variant = filled-tonal`, `containerColor =
 * primary`); the library takes something else (`FilledTonalButton(colors =
 * ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.primary))`).
 * Today that translation is hand-written per catalog in the builder's exporters. A projection
 * states it as data, owned by the catalog beside its typed adapter, and [lower] turns one saved
 * node into the [ScreenNode] arguments [ScreenGenerator] already knows how to print. Nothing here
 * is a template or an emitter: every value is a codec over one property, or a nested call of such
 * values, so a projection can be validated against discovered records before it is published.
 *
 * @property variantProperty the wire property choosing among [targets]; null when there is one.
 * @property targets the records a node may become, each with its own parameter bindings — so a
 *   variant may pick both the composable (`FilledTonalButton`) and the factory its colours use.
 */
data class ExportProjection(
  val targets: List<Target>,
  val variantProperty: String? = null,
) {
  init {
    require(targets.isNotEmpty()) { "a projection names at least one target record" }
    require(targets.count { it.variants == null } <= 1) { "at most one default target" }
    if (variantProperty == null) require(targets.size == 1) { "several targets need a variant" }
  }

  /**
   * One record a node can lower to.
   *
   * @property variants the [variantProperty] values selecting this record; null for the default.
   * @property slots the record's composable parameter for each wire slot whose name differs (Wear's
   *   `content` is `Button`'s `label`).
   * @property events the record's callback parameter for each wire event (`click` is `onClick`).
   */
  data class Target(
    val record: String,
    val parameters: Map<String, Value>,
    val variants: Set<String>? = null,
    val slots: Map<String, String> = emptyMap(),
    val events: Map<String, String> = emptyMap(),
  )

  /** What one parameter, or one argument of a nested call, is written from. */
  sealed interface Value {
    /** A wire property through a codec; absent, empty or equal to [omitWhen] writes nothing. */
    data class Property(val name: String, val codec: Codec, val omitWhen: JsonPrimitive? = null) :
      Value

    /** A fixed value the call always needs, such as `onClick = {}` for a design without one. */
    data class Fixed(val value: ScreenValue) : Value

    /**
     * A nested factory call (`ButtonDefaults.buttonColors(containerColor = …)`), written only when
     * at least one of its [arguments] is, so an unstyled button keeps the library's own colours.
     */
    data class Call(
      val callableFqn: String,
      val typeFqn: String,
      val arguments: Map<String, Value>,
    ) : Value
  }

  /** How a wire value is read and written as a Kotlin expression. */
  sealed interface Codec {
    data object Text : Codec

    data object Bool : Codec

    data object Whole : Codec

    data object Fractional32 : Codec

    /**
     * A colour: `#RRGGBB` / `#AARRGGBB` as `Color(0x…)`, `transparent` as `Color.Transparent`, any
     * other token as a member of the theme's colour scheme, renamed through [roles] where the
     * catalog's token is not the scheme's own name (Wear's `surface` is `surfaceContainer`).
     */
    data class Color(
      val schemeRootFqn: String,
      val schemeMembers: List<String> = listOf("colorScheme"),
      val roles: Map<String, String> = emptyMap(),
      val tokens: Set<String>? = null,
    ) : Codec

    /** A closed set of wire values, each written as the qualified member it names. */
    data class Enum(val typeFqn: String, val members: Map<String, String>) : Codec
  }

  /**
   * Every wire property some target reads, [variantProperty] included. A host refuses a node
   * property outside this set rather than export a design that silently drops it.
   */
  val properties: Set<String>
    get() = buildSet {
      variantProperty?.let(::add)
      fun visit(value: Value) {
        when (value) {
          is Value.Property -> add(value.name)
          is Value.Call -> value.arguments.values.forEach(::visit)
          is Value.Fixed -> Unit
        }
      }
      targets.forEach { it.parameters.values.forEach(::visit) }
    }

  /**
   * The parameter of [record] that [property] is written to as itself, through no factory call —
   * the one place a host may pass a state-bound value through unchanged. Null when none is.
   */
  fun directParameter(record: String, property: String): String? =
    targets
      .firstOrNull { it.record == record }
      ?.parameters
      ?.entries
      ?.firstOrNull { (_, value) -> value is Value.Property && value.name == property }
      ?.key

  /**
   * The qualified owners a lowered node may print — factory owners, enum owners, colour-scheme
   * roots and the colour type — so a host's expression allowlist can admit exactly these.
   */
  val qualifiedOwners: Set<String>
    get() = buildSet {
      fun codec(codec: Codec) {
        when (codec) {
          is Codec.Color -> {
            add(codec.schemeRootFqn)
            add(COLOR)
          }
          is Codec.Enum -> codec.members.values.mapTo(this) { it.substringBeforeLast('.') }
          else -> Unit
        }
      }
      fun visit(value: Value) {
        when (value) {
          is Value.Property -> codec(value.codec)
          is Value.Call -> {
            add(value.callableFqn.substringBeforeLast('.'))
            value.arguments.values.forEach(::visit)
          }
          is Value.Fixed -> Unit
        }
      }
      targets.forEach { it.parameters.values.forEach(::visit) }
    }

  /** A node lowered onto one record, or every reason it could not be. */
  sealed interface Lowered {
    /**
     * The chosen [record] and its [arguments]; [slots] and [events] rename the node's wire slots
     * and events onto that record's parameters, for the caller that owns child nodes and actions.
     */
    data class Node(
      val record: String,
      val arguments: Map<String, ScreenValue>,
      val slots: Map<String, String> = emptyMap(),
      val events: Map<String, String> = emptyMap(),
    ) : Lowered

    data class Refused(val reasons: List<String>) : Lowered
  }

  /** Lower one saved node's [properties] (the builder's `{type, value}` wrappers). */
  fun lower(properties: Map<String, JsonElement>): Lowered {
    val reasons = mutableListOf<String>()
    val variant = variantProperty?.let { wireValue(properties[it])?.contentOrNull }
    val target =
      targets.firstOrNull { variant != null && it.variants?.contains(variant) == true }
        ?: targets.firstOrNull { it.variants == null }
    if (target == null) return Lowered.Refused(listOf("no target for $variantProperty=$variant"))
    val arguments = buildMap {
      for ((parameter, value) in target.parameters) {
        write(value, properties, "${target.record}.$parameter", reasons)?.let { put(parameter, it) }
      }
    }
    return if (reasons.isEmpty())
      Lowered.Node(target.record, arguments, target.slots, target.events)
    else Lowered.Refused(reasons)
  }

  private fun write(
    value: Value,
    properties: Map<String, JsonElement>,
    where: String,
    reasons: MutableList<String>,
  ): ScreenValue? =
    when (value) {
      is Value.Fixed -> value.value
      is Value.Property -> {
        val raw = wireValue(properties[value.name])
        if (raw == null || raw is JsonNull || (raw.isString && raw.content.isEmpty())) null
        else if (value.omitWhen != null && sameScalar(raw, value.omitWhen)) null
        else encode(value.codec, raw, "$where <- ${value.name}", reasons)
      }
      is Value.Call -> {
        val arguments = buildMap {
          for ((name, argument) in value.arguments) {
            write(argument, properties, "$where.$name", reasons)?.let { put(name, it) }
          }
        }
        if (arguments.isEmpty()) null
        else ScreenValue.Construct(value.callableFqn, named = arguments, typeFqn = value.typeFqn)
      }
    }

  private fun encode(
    codec: Codec,
    raw: JsonPrimitive,
    where: String,
    reasons: MutableList<String>,
  ): ScreenValue? {
    fun refuse(message: String): ScreenValue? {
      reasons += "$where: $message"
      return null
    }
    return when (codec) {
      Codec.Text -> if (raw.isString) ScreenValue.Text(raw.content) else refuse("expected text")
      Codec.Bool -> raw.booleanOrNull?.let { ScreenValue.Bool(it) } ?: refuse("expected a boolean")
      Codec.Whole ->
        raw.doubleOrNull
          ?.takeIf { !raw.isString && it % 1.0 == 0.0 }
          ?.let { ScreenValue.Whole(it.toLong()) } ?: refuse("expected a whole number")
      Codec.Fractional32 ->
        raw.doubleOrNull
          ?.takeIf { !raw.isString && it.isFinite() }
          ?.let { ScreenValue.Fractional32(it.toFloat()) } ?: refuse("expected a number")
      is Codec.Enum ->
        codec.members[raw.content]?.let { member ->
          ScreenValue.Reference(
            member.substringBeforeLast('.'),
            listOf(member.substringAfterLast('.')),
            typeFqn = codec.typeFqn,
          )
        } ?: refuse("`${raw.content}` is not one of ${codec.members.keys.sorted()}")
      is Codec.Color -> color(codec, raw.content) ?: refuse("`${raw.content}` is not a colour")
    }
  }

  private fun color(codec: Codec.Color, value: String): ScreenValue? {
    if (value.startsWith("#")) {
      val hex = value.removePrefix("#")
      val argb =
        when (hex.length) {
          6 -> "FF$hex".toLongOrNull(16)
          8 -> hex.toLongOrNull(16)
          else -> null
        } ?: return null
      return ScreenValue.Construct(
        COLOR,
        positional = listOf(ScreenValue.Whole(argb)),
        typeFqn = COLOR,
      )
    }
    if (value == "transparent") {
      return ScreenValue.Reference(COLOR, listOf("Transparent"), typeFqn = COLOR)
    }
    if (codec.tokens != null && value !in codec.tokens) return null
    val role = codec.roles[value] ?: value
    if (!role.all { it.isLetterOrDigit() }) return null
    return ScreenValue.Reference(codec.schemeRootFqn, codec.schemeMembers + role, typeFqn = COLOR)
  }

  private fun wireValue(element: JsonElement?): JsonPrimitive? =
    when (element) {
      null -> null
      is JsonObject -> element["value"] as? JsonPrimitive
      is JsonPrimitive -> element
      else -> null
    }

  private fun sameScalar(a: JsonPrimitive, b: JsonPrimitive): Boolean =
    a.content == b.content && a.isString == b.isString

  private companion object {
    const val COLOR = "androidx.compose.ui.graphics.Color"
  }
}
