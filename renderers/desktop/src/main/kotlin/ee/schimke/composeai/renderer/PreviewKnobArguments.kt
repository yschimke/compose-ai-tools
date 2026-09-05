package ee.schimke.composeai.renderer

/**
 * Binds seeded knob values to a preview's **value parameters** — the secondary override format.
 *
 * ### Why an argument array rather than a controller
 *
 * The original override surface (`previewOverride*`) declares a knob by *executing a lookup inside
 * the composable body*, so a renderer seeds it by writing into a process-static controller before
 * composing. A parameter knob is declared by the function signature instead, so seeding it is
 * ordinary argument passing: the value goes to the composable the same way a caller would pass it,
 * and the body contains no harness call at all.
 *
 * ### How a subset is seeded
 *
 * `ComposableMethod.invoke` treats a **null or absent** argument as "use this parameter's default":
 * it sets that parameter's bit in Kotlin's synthetic `$default` mask and passes a zero value, so
 * the compiled default expression runs. That is exactly the behaviour a partial seed needs, and it
 * is already what makes an all-defaults preview render from a zero-length argument list today.
 *
 * So [bind] returns an array as long as the preview's parameter list, holding `null` everywhere
 * except the positions a seed named. Nothing else about the render seam changes.
 *
 * ### What it deliberately does not do
 *
 * * **No coercion across kinds.** A seed whose text is not a valid value for the knob's declared
 *   type is *dropped*, not guessed at — that position stays null and the author default renders.
 *   Coercing `"yes"` to `true`, or truncating `"1.5"` to an `Int`, would publish a capture that
 *   silently disagrees with the value the client asked for, which is worse than visibly ignoring
 *   it.
 * * **No null seeding.** Null is the channel's "use the default" signal, so it cannot also mean
 *   "set this to null". Nullable parameters are therefore not knobs (see `ComposableSignature`),
 *   and a seed naming one has nothing to bind to.
 * * **No unknown keys.** A seed whose name matches no declared knob is ignored here; it may still
 *   be a `previewOverride*` key, which the controller-backed extension seeds separately. One seed
 *   map serves both formats, so each side takes only what it recognises.
 */
object PreviewKnobArguments {

  /**
   * One editable value parameter of a preview, as discovery recorded it.
   *
   * [options] is non-empty only for [Type.ENUM], where it is the parameter's constants — both the
   * closed set a viewer offers and the guard that keeps a stale seed from naming a constant the
   * enum no longer has.
   */
  data class Knob(
    val name: String,
    val index: Int,
    val type: Type,
    val options: List<String> = emptyList(),
  )

  /** The value kinds a [Knob] can carry — mirrors discovery's `PreviewKnobType`. */
  enum class Type {
    STRING,
    BOOLEAN,
    INT,
    LONG,
    FLOAT,
    DOUBLE,

    /**
     * A Kotlin `enum class` parameter, seeded by constant **name**.
     *
     * It binds as that name — a `String` — and is turned into the constant itself by
     * [coerceToParameterTypes] at the invoke seam, which is the first place the parameter's own
     * `Class` is in hand. Nothing before that point can build one.
     */
    ENUM,
  }

  /**
   * The argument array to invoke a preview declaring [knobs] with, given the raw [seeds] a client
   * sent (knob name → verbatim text).
   *
   * Returns an empty list when nothing can be bound — no knobs, no seeds, or no seed that names a
   * knob and parses — so a caller can keep its existing zero-argument invoke rather than
   * constructing an all-null array that means the same thing.
   *
   * The array is sized by the highest knob index plus one rather than by [knobs].size: a knob's
   * index is its position in the *full* parameter list, which may include parameters that are
   * defaulted but not seedable (`modifier: Modifier = Modifier`). Those positions stay null and
   * take their defaults.
   */
  fun bind(knobs: List<Knob>, seeds: Map<String, String>): List<Any?> {
    if (knobs.isEmpty() || seeds.isEmpty()) return emptyList()
    val byName = knobs.associateBy { it.name }
    val bound = seeds.mapNotNull { (name, raw) ->
      val knob = byName[name] ?: return@mapNotNull null
      parse(knob, raw)?.let { knob.index to it }
    }
    if (bound.isEmpty()) return emptyList()
    val size = knobs.maxOf { it.index } + 1
    val args = arrayOfNulls<Any?>(size)
    bound.forEach { (index, value) -> if (index in 0 until size) args[index] = value }
    return args.toList()
  }

  /**
   * The typed value for [raw] under [type], or null when it is not one — an unparseable seed is
   * dropped rather than coerced.
   *
   * `toBooleanStrictOrNull` rather than `toBoolean`: the lenient form maps every non-`"true"`
   * string to `false`, so a malformed seed would silently render the opposite of a `true` default
   * instead of the default itself.
   */
  private fun parse(knob: Knob, raw: String): Any? =
    when (knob.type) {
      Type.STRING -> raw
      Type.BOOLEAN -> raw.toBooleanStrictOrNull()
      Type.INT -> raw.toIntOrNull()
      Type.LONG -> raw.toLongOrNull()
      Type.FLOAT -> raw.toFloatOrNull()
      Type.DOUBLE -> raw.toDoubleOrNull()
      Type.ENUM -> raw.takeIf { it in knob.options }
    }

  /**
   * [args] with every enum knob's constant **name** replaced by the constant itself, read off
   * [parameterTypes] — the preview's own value-parameter types, in order.
   *
   * This is the seam an enum knob cannot be bound without: a name is all a seed carries and all
   * [bind] can produce, because neither this object nor the daemon that sends the seed holds the
   * enum `Class`. The invoke path does, so the conversion happens here, once, for every lane.
   *
   * A position that is not an enum, is not a `String`, or names no constant of its type is left
   * exactly as it was — a seed that cannot become a constant must fall back to the author default,
   * which is what a `null` at that position already means, rather than fail the render.
   */
  fun coerceToParameterTypes(args: List<Any?>, parameterTypes: Array<Class<*>>): List<Any?> {
    if (args.isEmpty()) return args
    if (args.none { it is String }) return args
    val coerced = args.mapIndexed { index, value ->
      val type = parameterTypes.getOrNull(index) ?: return@mapIndexed value
      if (value !is String || !type.isEnum) return@mapIndexed value
      type.enumConstants?.firstOrNull { seedTextOf(type, it) == value } ?: value
    }
    // The SAME list back when nothing moved, so a caller can tell "no enum here" from "an enum was
    // converted" by identity — which is what lets the invoke seam keep its fast path for every
    // preview this does not touch.
    return if (coerced == args) args else coerced
  }

  /**
   * Whether [seed] names a constant of the enum [type] — by the text that constant answers to,
   * which is its `@KnobValue` when it declares one.
   *
   * Method *resolution* needs this as much as the invoke does. A seed is still text at that point,
   * and an overload is chosen by matching each argument's runtime type against the parameter's, so
   * without this a `String` never matches an enum parameter and the preview resolves to nothing.
   * Matching on the same text the binder will bind is what keeps the two from disagreeing.
   */
  fun matchesEnumConstant(type: Class<*>, seed: String): Boolean =
    type.enumConstants?.any { seedTextOf(type, it) == seed } == true

  /**
   * The seed text [constant] answers to: the value its `@KnobValue` declares, or its own name.
   *
   * The annotation is resolved **by name**, not by type, so no renderer artefact takes a dependency
   * on `preview-annotations` to read it — a consumer that never uses the annotation simply has no
   * class by that name on the classpath, and every constant answers to its own name.
   *
   * Any reflective failure degrades to the constant name for the same reason: a seed that cannot be
   * matched leaves its position alone, and the author default renders.
   */
  private fun seedTextOf(enumType: Class<*>, constant: Any): String? {
    val name = (constant as? Enum<*>)?.name ?: return null
    return runCatching {
      val field = enumType.getDeclaredField(name)
      field.annotations
        .firstOrNull { it.annotationClass.java.name == KNOB_VALUE_ANNOTATION }
        ?.let { annotation ->
          annotation.annotationClass.java.getDeclaredMethod("value").invoke(annotation) as? String
        }
        ?.takeIf { it.isNotEmpty() }
    }
      .getOrNull() ?: name
  }

  /** FQN of the alias annotation, resolved reflectively — see [seedTextOf]. */
  private const val KNOB_VALUE_ANNOTATION = "ee.schimke.composeai.preview.KnobValue"

  /**
   * Parses the `knobs=<name>:<index>:<TYPE>,…` render-payload token discovery threads through
   * `previews.json`. An entry that is malformed — wrong arity, a non-numeric index, a type name
   * this renderer does not know — is skipped rather than failing the render: a newer plugin may
   * name a knob kind an older renderer cannot bind, and dropping just that knob degrades to the
   * author default while the rest of the preview still seeds.
   */
  fun parseToken(token: String?): List<Knob> {
    if (token.isNullOrBlank()) return emptyList()
    return token.split(',').mapNotNull { entry ->
      // Three fields, or four when a fourth carries an enum's constants (`|`-separated, since the
      // token's own separators are already spoken for). A three-field ENUM entry is dropped: an
      // enum knob with no options would offer an empty picker and drop every seed.
      val parts = entry.split(':')
      if (parts.size !in 3..4) return@mapNotNull null
      val index = parts[1].toIntOrNull() ?: return@mapNotNull null
      if (index < 0) return@mapNotNull null
      val type = Type.entries.firstOrNull { it.name == parts[2] } ?: return@mapNotNull null
      val options = parts.getOrNull(3)?.split('|')?.filter { it.isNotBlank() }.orEmpty()
      if (type == Type.ENUM && options.isEmpty()) return@mapNotNull null
      parts[0].takeIf { it.isNotBlank() }?.let { Knob(it, index, type, options) }
    }
  }
}
