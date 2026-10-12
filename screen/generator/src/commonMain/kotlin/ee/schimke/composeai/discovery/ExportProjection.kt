package ee.schimke.composeai.discovery

import kotlinx.serialization.json.JsonArray
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
   * @property synthesize composable parameters written from properties by synthesizing a child of
   *   another catalog component, which then exports through its own projection: `ListHeader`'s
   *   `content` is a `wear-m3/text` holding the header's `text`.
   * @property items for a parameter declared through its receiver's DSL rather than composed
   *   (`TransformingLazyColumn`'s `content: TransformingLazyColumnScope.() -> Unit`), the member
   *   each child is wrapped in (`item`).
   * @property itemRoles for an [items] parameter, the structural role its member opens. The child
   *   is then the direct child of that role: its target's context for the role applies, and the
   *   wrapper's body composes under the scope the catalog's `code.scopes[role]` declares. A
   *   parameter with no role wraps its children without one, so a list target whose rows take no
   *   context (Wear's `transformation = none`) names none.
   * @property modifier links written from properties onto the node's modifier, wherever it is
   *   placed: Wear's `wear-m3/icon` writes its `sizeDp` as `Modifier.size(…)`. Each link is written
   *   when every argument is, skipped when none is, and refused when only some are.
   */
  data class Target(
    val record: String,
    val parameters: Map<String, Value>,
    val variants: Set<String>? = null,
    val slots: Map<String, String> = emptyMap(),
    val events: Map<String, String> = emptyMap(),
    val contexts: Map<String, Context> = emptyMap(),
    val slotPatterns: Map<String, SlotPatterns> = emptyMap(),
    val synthesize: Map<String, Synthesis> = emptyMap(),
    val items: Map<String, String> = emptyMap(),
    val itemRoles: Map<String, String> = emptyMap(),
    val modifier: List<ModifierLink> = emptyList(),
  ) {
    init {
      for ((parameter, member) in items) {
        require(ComponentSnippets.isIdentifier(member)) {
          "`$parameter`'s item member `$member` is not a Kotlin identifier"
        }
      }
      for ((parameter, role) in itemRoles) {
        require(parameter in items) { "`$parameter` has an item role but no item member" }
        require(role in UI_BUILDER_STRUCTURAL_ROLES) {
          "`$parameter`'s item role `$role` is not one of $UI_BUILDER_STRUCTURAL_ROLES"
        }
      }
      val itemsWritten = items.keys intersect parameters.keys
      require(itemsWritten.isEmpty()) { "${itemsWritten.sorted()} are both a DSL slot and written" }
      val itemsSynthesized = items.keys intersect synthesize.keys
      require(itemsSynthesized.isEmpty()) {
        "${itemsSynthesized.sorted()} are both a DSL slot and synthesized"
      }
      val synthesizedWritten = synthesize.keys intersect parameters.keys
      require(synthesizedWritten.isEmpty()) {
        "${synthesizedWritten.sorted()} are both written and synthesized"
      }
      // Every parameter a wire slot can fill: its rename, or each pattern's destinations and
      // `otherwise`. A synthesized child there would be a second child for one parameter.
      val slotFilled =
        slots.values +
          slotPatterns.keys +
          slotPatterns.values.flatMap { patterns ->
            patterns.patterns.flatMap { it.parameters.keys } + listOfNotNull(patterns.otherwise)
          }
      val synthesizedSlots = synthesize.keys intersect slotFilled.toSet()
      require(synthesizedSlots.isEmpty()) {
        "${synthesizedSlots.sorted()} are both filled from a wire slot and synthesized"
      }
      // One patterned slot per target, so a pattern alone decides which overload the call is.
      require(slotPatterns.size <= 1) { "at most one slot is recognised by patterns" }
      for (slot in slotPatterns.keys) {
        require(slot !in slots) { "slot `$slot` is renamed and recognised by patterns" }
      }
      for (patterns in slotPatterns.values) {
        val clash =
          (patterns.patterns.flatMap { it.parameters.keys } + listOfNotNull(patterns.otherwise))
            .toSet() intersect parameters.keys
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
   * A modifier link whose [arguments] are projection values: `Modifier.size(<sizeDp>.dp)` is
   * `ModifierLink("androidx.compose.foundation.layout.size", listOf(Property("sizeDp",
   * Unit("dp"))))`. [callableFqn] is a top-level extension, imported as a [ChainLink] is.
   */
  data class ModifierLink(val callableFqn: String, val arguments: List<Value>) {
    init {
      require(arguments.isNotEmpty()) { "`$callableFqn`: a modifier link reads at least one value" }
    }
  }

  /**
   * A child node of [component] synthesized for a composable parameter, its properties copied from
   * the parent's: [properties] maps the child's property to the parent property it is read from.
   * The child inherits nothing else. It is synthesized on every node, with whichever of those
   * properties the parent has set, so a required parameter such as `ListHeader`'s `content` is
   * always written; an unset property is left to the child component's own default.
   */
  data class Synthesis(val component: String, val properties: Map<String, String>) {
    init {
      require(properties.isNotEmpty()) { "a synthesized child reads at least one property" }
    }
  }

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
        // Each line, and the overflow, is its own child; a shared destination would drop one.
        require(to.size == to.toSet().size) { "lines go to distinct parameters: $to" }
        require(rest == null || rest !in to) { "`rest` $rest is also a line's parameter" }
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

      // `rest` is filled whenever more lines than [to] names are needed to match at all.
      override val parameters: Map<String, Boolean>
        get() =
          to.mapIndexed { index, name -> name to (index < min) }.toMap() +
            listOfNotNull(rest).associateWith { min > to.size }
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
    /**
     * A wire property through a codec; absent, empty or equal to [omitWhen] writes nothing. A list
     * or object codec reads the whole stored wrapper and takes no [omitWhen].
     */
    data class Property(val name: String, val codec: Codec, val omitWhen: JsonPrimitive? = null) :
      Value {
      init {
        require(omitWhen == null || !codec.composite) { "`$name`: a $codec has no omitWhen" }
      }
    }

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
    /** Whether this codec reads a `list` / `object` wrapper rather than a scalar's `value`. */
    val composite: Boolean
      get() = this is ListOf || this is Object

    /**
     * The wrapper `type` a value of this codec is stored under by default, as the typed adapters'
     * own codecs spell it (`AdapterValueCodec.literalType`): what a list element or object field
     * must state, unless its [ListOf.elementLiteralType] / [Field.literalType] says otherwise.
     */
    val literalType: String
      get() =
        when (this) {
          Text,
          is Color,
          is Enum,
          is Token,
          MaterialIcon -> "string"
          Bool -> "bool"
          Whole -> "int"
          Fractional32,
          is Unit -> "number"
          is ListOf -> "list"
          is Object -> "object"
        }

    data object Text : Codec

    /**
     * A Material icon key as the builder stores it (`filled/newReleases`), written as the host's
     * own icon table writes it: `Icons.Filled.NewReleases`, its extension property imported. The
     * table is the host's (compose-ui-builder ships one generated from the icon jars), so a
     * projection names the codec rather than copying thousands of members; lowered without one, the
     * property is refused.
     */
    data object MaterialIcon : Codec

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

    /**
     * A `{"type": "list", "values": [<wrapper>…]}`, each value through [element], written
     * `listOf(a, b)`. An empty list is `listOf()`, the parameter's type inferring the element. Each
     * value must be exactly the wrapper the element's codec reads back: a scalar's `{"type":
     * [elementLiteralType], "value": …}`, a nested list's or object's own wrapper.
     */
    data class ListOf(
      val element: Codec,
      val elementLiteralType: String = element.literalType,
    ) : Codec {
      init {
        require(!element.composite || elementLiteralType == element.literalType) {
          "a nested ${element.literalType} is stored as `${element.literalType}`"
        }
      }
    }

    /**
     * A `{"type": "object", "fields": {"<name>": <wrapper>}}` of the class [typeFqn], written as a
     * call to [callableFqn] (its constructor, by default) with every field as a named argument
     * through its codec, in [fields] order. A field left out writes its [Field.default], or is
     * refused when it has none; a field the object does not declare is refused. Every field is
     * written because the callable need not default the parameters the adapter does.
     */
    data class Object(
      val typeFqn: String,
      val fields: Map<String, Field>,
      val callableFqn: String = typeFqn,
    ) : Codec {
      init {
        require(fields.isNotEmpty()) { "an object codec writes at least one field" }
      }
    }

    /**
     * One field of an [Object]: its [codec], the wrapper [literalType] it is stored under, and the
     * stored wrapper of its [default], written when an object leaves the field out. Null makes the
     * field required.
     */
    data class Field(
      val codec: Codec,
      val literalType: String = codec.literalType,
      val default: JsonObject? = null,
    ) {
      init {
        require(!codec.composite || literalType == codec.literalType) {
          "a nested ${codec.literalType} is stored as `${codec.literalType}`"
        }
      }

      val required: Boolean
        get() = default == null
    }
  }

  /**
   * Every wire property some target reads, [variantProperty] included. A host refuses a node
   * property outside this set rather than export a design that silently drops it.
   */
  val properties: Set<String>
    get() = buildSet {
      variantProperty?.let(::add)
      targets.forEach { addAll(read(it)) }
    }

  /** The wire properties [target] writes, in any role, or copies to a synthesized child. */
  private fun read(target: Target): Set<String> = buildSet {
    fun visit(value: Value) {
      when (value) {
        is Value.Property -> add(value.name)
        is Value.Call -> value.arguments.values.forEach(::visit)
        is Value.Fixed -> Unit
      }
    }
    target.parameters.values.forEach(::visit)
    target.contexts.values.forEach { it.parameters.values.forEach(::visit) }
    target.modifier.forEach { it.arguments.forEach(::visit) }
    target.synthesize.values.forEach { addAll(it.properties.values) }
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
      fun codec(owned: Codec) {
        when (owned) {
          is Codec.Color -> {
            add(owned.schemeRootFqn)
            add(COLOR)
          }
          is Codec.Enum -> owned.members.values.mapTo(this) { it.substringBeforeLast('.') }
          is Codec.Token -> add(owned.rootFqn)
          // A top-level extension: the allowlist admits the callable itself.
          is Codec.Unit -> add("androidx.compose.ui.unit.${owned.unit}")
          is Codec.ListOf -> {
            add(LIST_OF.substringBeforeLast('.'))
            codec(owned.element)
          }
          is Codec.Object -> {
            add(owned.callableFqn.substringBeforeLast('.'))
            owned.fields.values.forEach { codec(it.codec) }
          }
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
        // A scope member is supplied by the receiver and never imported, but the generator still
        // holds its qualified name to the allowlist, so its scope is the owner a host must admit.
        add(link.callableFqn.substringBeforeLast('.'))
        link.positional.forEach(::screen)
        link.named.values.forEach(::screen)
      }
      for (target in targets) {
        target.parameters.values.forEach(::visit)
        for (link in target.modifier) {
          // A top-level extension: the allowlist admits the callable itself.
          add(link.callableFqn)
          link.arguments.forEach(::visit)
        }
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
      /**
       * The target's own links written from properties, then the role context's, for the caller to
       * append after the node's authored modifiers.
       */
      val modifier: List<ChainLink> = emptyList(),
      /**
       * Children to synthesize, by parameter: each a component and its properties as saved (the
       * builder's `{type, value}` wrappers), for the caller to export through that component's own
       * projection.
       */
      val synthesized: Map<String, Synthesized> = emptyMap(),
      /** The target's [Target.items]: the member each child of a DSL parameter is wrapped in. */
      val items: Map<String, String> = emptyMap(),
      /** The target's [Target.itemRoles]: the structural role an [items] member opens. */
      val itemRoles: Map<String, String> = emptyMap(),
    ) : Lowered

    data class Refused(val reasons: List<String>) : Lowered

    /** One synthesized child: its catalog [component] and the [properties] copied to it. */
    data class Synthesized(val component: String, val properties: Map<String, JsonElement>)
  }

  /**
   * Lower one saved node's [properties] (the builder's `{type, value}` wrappers). [role] is the
   * structural role the node is the direct child of, if any; its context's arguments are written
   * too, and a role with no context on the chosen target writes nothing extra.
   */
  fun lower(properties: Map<String, JsonElement>, role: String? = null): Lowered =
    lower(properties, role, null)

  /**
   * [lower], with the host's [icons] table: a [Codec.MaterialIcon] property is written as whatever
   * the host writes that icon key as (the builder's `Icons.Filled.X`), and refused without one.
   */
  fun lower(
    properties: Map<String, JsonElement>,
    role: String?,
    icons: ((String) -> ScreenValue?)?,
  ): Lowered {
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
    // A property another target writes but this one does not (an outlined button's
    // `containerColor`) would be dropped, so stating one is refused, as a host refuses a property
    // no target writes.
    val read = read(target)
    for (name in
      (properties.keys intersect this.properties) - read - setOfNotNull(variantProperty)) {
      val raw = wireValue(properties[name]) ?: continue
      if (raw is JsonNull || (raw.isString && raw.content.isEmpty())) continue
      reasons += "$name: not written by ${target.record}"
    }
    val context = role?.let { target.contexts[it] }
    val arguments = buildMap {
      for ((parameter, value) in target.parameters + context?.parameters.orEmpty()) {
        write(value, properties, "${target.record}.$parameter", reasons, icons)?.let {
          put(parameter, it)
        }
      }
    }
    val ownLinks = buildList {
      for (link in target.modifier) {
        val written =
          link.arguments.mapIndexed { index, value ->
            write(
              value,
              properties,
              "${target.record}.modifier ${link.callableFqn}[$index]",
              reasons,
              icons,
            )
          }
        when {
          written.all { it == null } -> Unit
          written.any { it == null } ->
            reasons +=
              "${target.record}.modifier: `${link.callableFqn}` is written from values only some " +
                "of which are set"
          else -> add(ChainLink(link.callableFqn, positional = written.filterNotNull()))
        }
      }
    }
    val synthesized = buildMap {
      for ((parameter, synthesis) in target.synthesize) {
        val copied =
          synthesis.properties
            .mapNotNull { (child, parent) ->
              properties[parent]?.takeUnless { it is JsonNull }?.let { child to it }
            }
            .toMap()
        put(parameter, Lowered.Synthesized(synthesis.component, copied))
      }
    }
    return if (reasons.isEmpty())
      Lowered.Node(
        target.record,
        arguments,
        target.slots,
        target.events,
        ownLinks + context?.modifier.orEmpty(),
        synthesized,
        target.items,
        target.itemRoles,
      )
    else Lowered.Refused(reasons)
  }

  private fun write(
    value: Value,
    properties: Map<String, JsonElement>,
    where: String,
    reasons: MutableList<String>,
    icons: ((String) -> ScreenValue?)?,
  ): ScreenValue? =
    when (value) {
      is Value.Fixed -> value.value
      is Value.Property -> {
        val stated = properties[value.name]
        val raw = if (value.codec.composite) null else wireValue(stated)
        if (value.codec.composite) {
          if (stated == null || stated is JsonNull) null
          else
            nested(
              value.codec,
              value.codec.literalType,
              stated,
              "$where <- ${value.name}",
              reasons,
              icons,
            )
        } else if (stated != null && stated !is JsonNull && raw == null) {
          reasons += "$where <- ${value.name}: not a scalar value"
          null
        } else if (raw == null || raw is JsonNull || (raw.isString && raw.content.isEmpty())) null
        else if (value.omitWhen != null && sameScalar(raw, value.omitWhen)) null
        else encode(value.codec, raw, "$where <- ${value.name}", reasons, icons)
      }
      is Value.Call -> {
        val arguments = buildMap {
          for ((name, argument) in value.arguments) {
            write(argument, properties, "$where.$name", reasons, icons)?.let { put(name, it) }
          }
        }
        if (arguments.isEmpty()) null
        else ScreenValue.Construct(value.callableFqn, named = arguments, typeFqn = value.typeFqn)
      }
    }

  /**
   * One stored wrapper inside a list or object (or a list or object property itself), written
   * through [codec]. Unlike a top-level scalar, a nested value cannot be "unset": an element or
   * field that would write nothing is refused, since dropping it would change the list or object.
   */
  private fun nested(
    codec: Codec,
    literalType: String,
    stored: JsonElement,
    where: String,
    reasons: MutableList<String>,
    icons: ((String) -> ScreenValue?)?,
  ): ScreenValue? {
    fun refuse(message: String): ScreenValue? {
      reasons += "$where: $message"
      return null
    }
    return when (codec) {
      is Codec.ListOf -> {
        val values =
          composite(stored, "list", "values") as? JsonArray
            ?: return refuse("expected a {\"type\": \"list\", \"values\": […]} wrapper")
        val before = reasons.size
        val written = values.mapIndexed { index, item ->
          nested(codec.element, codec.elementLiteralType, item, "$where[$index]", reasons, icons)
        }
        if (reasons.size != before) null
        else
          ScreenValue.Construct(
            LIST_OF,
            positional = written.filterNotNull(),
            typeFqn = LIST,
          )
      }
      is Codec.Object -> {
        val fields =
          composite(stored, "object", "fields") as? JsonObject
            ?: return refuse("expected a {\"type\": \"object\", \"fields\": {…}} wrapper")
        val unknown = fields.keys - codec.fields.keys
        if (unknown.isNotEmpty()) return refuse("${codec.typeFqn} has no field ${unknown.sorted()}")
        val before = reasons.size
        val named = buildMap {
          for ((name, field) in codec.fields) {
            // An omitted field writes the default the adapter declared, which the class's own
            // constructor need not share; one without a default cannot be written at all.
            val value =
              fields[name]?.takeUnless { it is JsonNull }
                ?: field.default
                ?: run {
                  reasons += "$where.$name: required field is missing"
                  null
                }
                ?: continue
            nested(field.codec, field.literalType, value, "$where.$name", reasons, icons)?.let {
              put(name, it)
            }
          }
        }
        if (reasons.size != before) null
        else ScreenValue.Construct(codec.callableFqn, named = named, typeFqn = codec.typeFqn)
      }
      else -> {
        // Exactly what the adapter's codec unwraps: its own literal wrapper, never a bare value or
        // a wrapper of another type (a top-level property stays as lenient as it always was).
        val wrapper = stored as? JsonObject
        val stated = (wrapper?.get("type") as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (wrapper == null || stated != literalType)
          return refuse(
            "expected a `$literalType` wrapper, found ${stated?.let { "`$it`" } ?: stored}"
          )
        val raw =
          wrapper["value"] as? JsonPrimitive
            ?: return refuse("a `$literalType` wrapper has no value")
        if (raw is JsonNull) return refuse("has no value")
        // An empty string is the top-level "unset"; nested, only text can mean it literally.
        if (raw.isString && raw.content.isEmpty() && codec != Codec.Text)
          return refuse("an empty value cannot be written here")
        val before = reasons.size
        encode(codec, raw, where, reasons, icons)
          ?: if (reasons.size == before) refuse("writes nothing, which a list or object cannot")
          else null
      }
    }
  }

  /** The [content] of a `{"type": [type], [content]: …}` wrapper, or null when it is not one. */
  private fun composite(stored: JsonElement, type: String, content: String): JsonElement? {
    if (stored !is JsonObject) return null
    val stated = (stored["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    if (stated != type || stored.keys != setOf("type", content)) return null
    return stored[content]
  }

  private fun encode(
    codec: Codec,
    raw: JsonPrimitive,
    where: String,
    reasons: MutableList<String>,
    icons: ((String) -> ScreenValue?)?,
  ): ScreenValue? {
    fun refuse(message: String): ScreenValue? {
      reasons += "$where: $message"
      return null
    }
    return when (codec) {
      Codec.Text -> if (raw.isString) ScreenValue.Text(raw.content) else refuse("expected text")
      Codec.MaterialIcon ->
        when {
          !raw.isString -> refuse("expected a Material icon key")
          icons == null ->
            refuse("`${raw.content}` is a Material icon key, and this host writes none")
          else -> icons(raw.content) ?: refuse("`${raw.content}` is not a Material icon key")
        }
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
      is Codec.ListOf,
      is Codec.Object -> refuse("expected a list or object wrapper, not a scalar")
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
    const val LIST = "kotlin.collections.List"
    const val LIST_OF = "kotlin.collections.listOf"
    val UNITS = setOf("dp", "sp")
    val UNIT_TYPES =
      mapOf("dp" to "androidx.compose.ui.unit.Dp", "sp" to "androidx.compose.ui.unit.TextUnit")

    fun String.isIdentifier() = isNotEmpty() && first().isLetter() && all { it.isLetterOrDigit() }
  }
}
