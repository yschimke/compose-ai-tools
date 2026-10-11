package ee.schimke.composeai.discovery

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

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
   * @property contexts what this record writes besides [parameters] when its node is placed in a
   *   structural role, keyed by role (`list-item`): the arguments its parent decides.
   * @property slotPatterns for the one wire slot whose children decide which parameters they fill
   *   (Wear's `Button` content: an icon, then one or two lines of text), how they are recognised.
   *   Replaces that slot's [slots] rename.
   */
  data class Target(
    val record: String,
    val parameters: Map<String, Value>,
    val variants: Set<String>? = null,
    val slots: Map<String, String> = emptyMap(),
    val events: Map<String, String> = emptyMap(),
    val contexts: Map<String, Context> = emptyMap(),
    val slotPatterns: Map<String, SlotPatterns> = emptyMap(),
  ) {
    init {
      // One patterned slot per target, so a pattern alone decides which overload the call is.
      require(slotPatterns.size <= 1) { "at most one slot is recognised by patterns" }
      for (slot in slotPatterns.keys) {
        require(slot !in slots) { "slot `$slot` is renamed and recognised by patterns" }
      }
      for (patterns in slotPatterns.values) {
        val clash =
          patterns.patterns.flatMap { it.parameters.keys }.toSet() intersect parameters.keys
        require(clash.isEmpty()) { "slot patterns fill ${clash.sorted()}, already written" }
      }
      for ((role, context) in contexts) {
        require(role in UI_BUILDER_STRUCTURAL_ROLES) {
          "context role `$role` is not one of $UI_BUILDER_STRUCTURAL_ROLES"
        }
        val both = context.parameters.keys intersect parameters.keys
        require(both.isEmpty()) { "`$role` context rewrites ${both.sorted()}, already written" }
        require(MODIFIER !in context.parameters || context.modifier.isEmpty()) {
          "`$role` context both writes `$MODIFIER` and appends to it"
        }
      }
    }
  }

  /**
   * What a target writes only when its node is the **direct** child of a structural role: Wear's
   * `transformation = SurfaceTransformation(spec)` and `Modifier.transformedHeight(this, spec)` for
   * an item of the screen's list. One level deep, as the hand-written exporters do.
   *
   * @property parameters further arguments, checked against the record exactly as [Target]'s.
   * @property modifier links appended, in order, after the node's own modifier chain — never in
   *   place of it.
   */
  data class Context(
    val parameters: Map<String, Value> = emptyMap(),
    val modifier: List<ChainLink> = emptyList(),
  )

  /**
   * How one wire slot's children are recognised: the first of [patterns] that matches assigns them
   * to parameters, and those parameters pick the overload. [otherwise] is the parameter every child
   * goes to when none matches, or null to refuse the node with the reason.
   */
  data class SlotPatterns(val patterns: List<SlotPattern>, val otherwise: String? = null) {
    init {
      require(patterns.isNotEmpty() || otherwise != null) { "slot patterns recognise something" }
    }
  }

  /** An ordered list of [match]ers over a slot's children, every child consumed by one of them. */
  data class SlotPattern(val match: List<Matcher>) {
    init {
      require(match.isNotEmpty()) { "a slot pattern has at least one matcher" }
      val all = match.flatMap { it.parameters.keys }
      require(all.size == all.toSet().size) { "a slot pattern fills each parameter once: $all" }
      require(
        match.count { it is Matcher.Rest } <= 1 &&
          match.lastOrNull().let { m -> match.none { it is Matcher.Rest } || m is Matcher.Rest }
      ) {
        "`any` takes the remaining children, so it is the last matcher"
      }
    }

    /** Every parameter this pattern may fill, and whether it fills it whenever it matches. */
    val parameters: Map<String, Boolean>
      get() = match.fold(emptyMap()) { acc, matcher -> acc + matcher.parameters }
  }

  /** The closed matcher vocabulary: data that can be validated, never code. */
  sealed interface Matcher {
    /** The parameters filled, each with whether it is filled whenever the matcher matches. */
    val parameters: Map<String, Boolean>

    /** The next child, a [componentId] node, goes to [to]; with [optional], it may be absent. */
    data class Component(val componentId: String, val to: String, val optional: Boolean = false) :
      Matcher {
      override val parameters: Map<String, Boolean>
        get() = mapOf(to to !optional)
    }

    /**
     * The next child is lines of text: one [textComponent] node, or a column the host judges plain
     * (no modifiers, default arrangement) holding only [textComponent] nodes, flattened. Line `k`
     * goes to `to[k]`; [min]..[max] lines match, and lines past [to] go to [rest] when it is set.
     */
    data class Lines(
      val textComponent: String,
      val to: List<String>,
      val min: Int = 1,
      val max: Int? = null,
      val rest: String? = null,
    ) : Matcher {
      init {
        require(to.isNotEmpty()) { "lines go to at least one parameter" }
        require(min >= 1) { "lines match at least one line" }
        val upper = max ?: if (rest == null) to.size else Int.MAX_VALUE
        require(upper >= min) { "lines match $min..$upper lines" }
        require(rest != null || upper <= to.size) {
          "lines past ${to.size} need a `rest` parameter to go to"
        }
      }

      /** The most lines this matches. */
      val upper: Int
        get() = max ?: if (rest == null) to.size else Int.MAX_VALUE

      override val parameters: Map<String, Boolean>
        get() =
          to.mapIndexed { index, name -> name to (index < min) }.toMap() +
            listOfNotNull(rest).associateWith { false }
    }

    /** Every remaining child, at least one, goes to [to]. */
    data class Rest(val to: String) : Matcher {
      override val parameters: Map<String, Boolean>
        get() = mapOf(to to true)
    }
  }

  /** Which children of a patterned slot go to which parameter, or why none can. */
  sealed interface SlotAssignment<out C> {
    data class Assigned<C>(val parameters: Map<String, List<C>>) : SlotAssignment<C>

    data class Refused(val reason: String) : SlotAssignment<Nothing>
  }

  /**
   * [record]'s parameters for the children of its wire [slot], by its [Target.slotPatterns], or the
   * plain rename when the slot has none. [componentId] names a child's catalog component;
   * [plainColumn] is the host's judgement of a column of lines: its children when the child is a
   * column with no modifiers and default arrangement, else null.
   */
  fun <C> assignSlot(
    record: String,
    slot: String,
    children: List<C>,
    componentId: (C) -> String,
    plainColumn: (C) -> List<C>?,
  ): SlotAssignment<C> {
    val target =
      targets.firstOrNull { it.record == record }
        ?: return SlotAssignment.Refused("$record is not a target of this projection")
    val patterns =
      target.slotPatterns[slot]
        ?: return SlotAssignment.Assigned(mapOf((target.slots[slot] ?: slot) to children))
    for (pattern in patterns.patterns) {
      match(pattern, children, componentId, plainColumn)?.let {
        return SlotAssignment.Assigned(it)
      }
    }
    return patterns.otherwise?.let { SlotAssignment.Assigned(mapOf(it to children)) }
      ?: SlotAssignment.Refused(
        "no pattern of `$slot` matches its children " +
          children.joinToString(", ", "[", "]") { componentId(it) }
      )
  }

  private fun <C> match(
    pattern: SlotPattern,
    children: List<C>,
    componentId: (C) -> String,
    plainColumn: (C) -> List<C>?,
  ): Map<String, List<C>>? {
    val assigned = linkedMapOf<String, List<C>>()
    var next = 0
    for (matcher in pattern.match) {
      val child = children.getOrNull(next)
      when (matcher) {
        is Matcher.Component ->
          if (child != null && componentId(child) == matcher.componentId) {
            assigned[matcher.to] = listOf(child)
            next++
          } else if (!matcher.optional) return null
        is Matcher.Lines -> {
          child ?: return null
          val lines =
            if (componentId(child) == matcher.textComponent) listOf(child)
            else
              plainColumn(child)?.takeIf { column ->
                column.isNotEmpty() && column.all { componentId(it) == matcher.textComponent }
              } ?: return null
          if (lines.size < matcher.min || lines.size > matcher.upper) return null
          lines.take(matcher.to.size).forEachIndexed { k, line ->
            assigned[matcher.to[k]] = listOf(line)
          }
          if (lines.size > matcher.to.size) assigned[matcher.rest!!] = lines.drop(matcher.to.size)
          next++
        }
        is Matcher.Rest -> {
          if (next >= children.size) return null
          assigned[matcher.to] = children.drop(next)
          next = children.size
        }
      }
    }
    return assigned.takeIf { next == children.size }
  }

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

    /**
     * A number in [unit] (`dp` or `sp`), written `16.dp`. With [positive], zero or less writes
     * nothing — the "unset" a Wear text's `fontSizeSp = 0` means.
     */
    data class Unit(val unit: String, val positive: Boolean = false) : Codec {
      init {
        require(unit in UNITS) { "a unit codec writes one of $UNITS" }
      }
    }

    /**
     * A theme token written as a member path from [rootFqn]: `titleLarge` through
     * `MaterialTheme.typography` is `MaterialTheme.typography.titleLarge`. [tokens] closes the set
     * when the catalog publishes one.
     */
    data class Token(
      val rootFqn: String,
      val members: List<String>,
      val typeFqn: String,
      val tokens: Set<String>? = null,
    ) : Codec
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
      targets.forEach { target ->
        target.parameters.values.forEach(::visit)
        target.contexts.values.forEach { it.parameters.values.forEach(::visit) }
      }
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
          is Codec.Token -> add(codec.rootFqn)
          // A top-level extension: the allowlist admits the callable itself.
          is Codec.Unit -> add("androidx.compose.ui.unit.${codec.unit}")
          else -> Unit
        }
      }
      fun screen(value: ScreenValue) {
        when (value) {
          is ScreenValue.Reference -> add(value.rootFqn)
          is ScreenValue.Construct -> {
            add(value.callableFqn.substringBeforeLast('.'))
            value.positional.forEach(::screen)
            value.named.values.forEach(::screen)
          }
          is ScreenValue.Chain -> {
            screen(value.receiver)
            value.links.forEach { link ->
              add(link.callableFqn.substringBeforeLast('.'))
              link.positional.forEach(::screen)
              link.named.values.forEach(::screen)
            }
          }
          is ScreenValue.Lambda -> screen(value.result)
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
          is Value.Fixed -> screen(value.value)
        }
      }
      fun link(link: ChainLink) {
        // A scope member is supplied by the receiver and never imported, so it names no owner.
        if (link.receiverScopeFqn == null) add(link.callableFqn.substringBeforeLast('.'))
        link.positional.forEach(::screen)
        link.named.values.forEach(::screen)
      }
      for (target in targets) {
        target.parameters.values.forEach(::visit)
        for (context in target.contexts.values) {
          context.parameters.values.forEach(::visit)
          context.modifier.forEach(::link)
        }
      }
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
      /** The role context's links, for the caller to append after the node's own modifiers. */
      val modifier: List<ChainLink> = emptyList(),
    ) : Lowered

    data class Refused(val reasons: List<String>) : Lowered
  }

  /**
   * Lower one saved node's [properties] (the builder's `{type, value}` wrappers). [role] is the
   * structural role the node is the direct child of, if any; its context's arguments are written
   * too, and a role with no context on the chosen target writes nothing extra.
   */
  fun lower(properties: Map<String, JsonElement>, role: String? = null): Lowered {
    val reasons = mutableListOf<String>()
    val statedVariant = variantProperty?.let { properties[it] }?.takeUnless { it is JsonNull }
    if (statedVariant != null && wireValue(statedVariant) == null) {
      return Lowered.Refused(listOf("$variantProperty: not a scalar value"))
    }
    val variant =
      variantProperty
        ?.let { wireValue(properties[it]) }
        ?.takeUnless { it is JsonNull }
        ?.contentOrNull
        ?.takeIf { it.isNotEmpty() }
    // An absent variant is the default target; a stated one must be listed, or a stale or misspelt
    // value would silently export a different composable.
    val target =
      if (variant == null) targets.firstOrNull { it.variants == null }
      else targets.firstOrNull { it.variants?.contains(variant) == true }
    if (target == null) return Lowered.Refused(listOf("no target for $variantProperty=$variant"))
    val context = role?.let { target.contexts[it] }
    val arguments = buildMap {
      for ((parameter, value) in target.parameters + context?.parameters.orEmpty()) {
        write(value, properties, "${target.record}.$parameter", reasons)?.let { put(parameter, it) }
      }
    }
    return if (reasons.isEmpty())
      Lowered.Node(
        target.record,
        arguments,
        target.slots,
        target.events,
        context?.modifier.orEmpty(),
      )
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
        val stated = properties[value.name]
        val raw = wireValue(stated)
        if (stated != null && stated !is JsonNull && raw == null) {
          reasons += "$where <- ${value.name}: not a scalar value"
          null
        } else if (raw == null || raw is JsonNull || (raw.isString && raw.content.isEmpty())) null
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
      // Read as a Long token: through a Double, 9007199254740993 would export as …992.
      Codec.Whole ->
        raw.longOrNull?.takeIf { !raw.isString }?.let { ScreenValue.Whole(it) }
          ?: refuse("expected a whole number")
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
      is Codec.Unit -> {
        val number = raw.doubleOrNull?.takeIf { !raw.isString && it.isFinite() }
        when {
          number == null -> refuse("expected a number of ${codec.unit}")
          codec.positive && number <= 0.0 -> null
          else -> {
            val whole = number.toLong()
            ScreenValue.Chain(
              receiver =
                if (number == whole.toDouble() && whole in Int.MIN_VALUE..Int.MAX_VALUE)
                  ScreenValue.Whole(whole)
                else ScreenValue.Fractional(number),
              links = listOf(ChainLink("androidx.compose.ui.unit.${codec.unit}", property = true)),
              typeFqn = UNIT_TYPES.getValue(codec.unit),
            )
          }
        }
      }
      is Codec.Token ->
        raw.content
          .takeIf { (codec.tokens == null || it in codec.tokens) && it.isIdentifier() }
          ?.let {
            ScreenValue.Reference(codec.rootFqn, codec.members + it, typeFqn = codec.typeFqn)
          } ?: refuse("`${raw.content}` is not a token of ${codec.members.joinToString(".")}")
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
    const val MODIFIER = "modifier"
    val UNITS = setOf("dp", "sp")
    val UNIT_TYPES =
      mapOf("dp" to "androidx.compose.ui.unit.Dp", "sp" to "androidx.compose.ui.unit.TextUnit")

    fun String.isIdentifier() = isNotEmpty() && first().isLetter() && all { it.isLetterOrDigit() }
  }
}
