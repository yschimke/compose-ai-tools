package ee.schimke.composeai.discovery

/**
 * Generates a compilable `@Composable` screen from a [ScreenDocument] and the components a build
 * discovered.
 *
 * Unlike [ComponentSnippets], which prints one call site with placeholders, this binds the values a
 * builder set and nests components into each other's slots.
 *
 * A node is generated only when its record carries an emitted [ComponentCode]; `code.call` is the
 * licence to call (public, inferable, importable, signature actually read), while the argument list
 * is rebuilt here from [ComponentRecord.parameters].
 *
 * Known gaps in that licence: `@Deprecated(level = ERROR)` components and Kotlin context
 * *parameters* are still admitted. A [ScreenValue.Chain] link is emitted as an imported extension,
 * which loses to a same-named member on the receiver; a renaming import alias would fix that but
 * hurts readability for a case not seen in practice, so it is declined deliberately.
 *
 * `expressionPackages` is a security boundary: [ScreenValue.Construct] emits qualified calls with
 * document-supplied arguments, so it is empty by default and refuses anything outside it.
 * Reference, Construct and Chain values carry a claimed type that is checked against the parameter
 * but otherwise trusted.
 *
 * Anything that cannot be proven is refused with a reason naming the node: a screen that compiles
 * but is not the one designed is worse than an error.
 */
object ScreenGenerator {

  /** The generated file, or the reasons it could not be generated. */
  sealed interface Result {
    data class Emitted(
      /** A complete Kotlin file: package, imports, opt-ins and the screen composable. */
      val source: String,
      /**
       * Every `@RequiresOptIn` marker the screen's components need, already applied to [source].
       */
      val requiredOptIns: List<String>,
    ) : Result

    /** Every problem found, not just the first — a builder wants the whole list to act on. */
    data class Refused(val reasons: List<String>) : Result
  }

  /**
   * The design environment a generated `@Preview` should reproduce, or null for no preview.
   *
   * Opt-in because `@Preview` is an Android tooling dependency consumers need not have on the
   * compile classpath. Values are the design's own environment, not `@Preview` defaults. [locale]
   * is validated against a language-tag shape rather than escaped, since it lands in a string
   * literal.
   */
  data class Preview(
    /** Design width in dp. Omitted from the annotation when null, so `@Preview` decides. */
    val widthDp: Int? = null,
    /** Design height in dp. Omitted when null. */
    val heightDp: Int? = null,
    /** Font scale, emitted as a `Float` literal. Omitted when null. */
    val fontScale: Double? = null,
    /** BCP-47-ish language tag, e.g. `en-US`. Omitted when null. */
    val locale: String? = null,
    /**
     * Whether the design is dark, emitted as `uiMode = …UI_MODE_NIGHT_YES`; fully qualified so the
     * Android-only constant stays out of the imports.
     */
    val darkMode: Boolean = false,
    /** Paints the preview's background; a transparent screen preview reads as a rendering fault. */
    val showBackground: Boolean = true,
    /**
     * Also emit a `@PreviewScreenSizes` wrapper so the screen is drawn at every reference size.
     * Uses the platform multipreview rather than a hand-written device list that would go stale.
     * Off by default.
     */
    val screenSizes: Boolean = false,
    /**
     * Device ids (e.g. `id:pixel_6`) to draw the screen at, one `@Preview(device = …)` each, beside
     * the design's own frame. Unlike [screenSizes], these are the devices the design claims to
     * support. Ids rather than geometry so they resolve against the tooling's catalog. Order kept,
     * repeats dropped.
     */
    val devices: List<String> = emptyList(),
  )

  private const val INDENT = "    "

  /** How many identical siblings it takes before a `repeat` reads better than the calls. */
  private const val MINIMUM_FOLDED_RUN = 3

  /**
   * Where a spliced value may begin and end — see [varyingRun]. A value can only follow an opening
   * delimiter and be followed by a closing one, so a fold never splits a token.
   */
  private val FOLD_OPENING_DELIMITERS = setOf('(', ',', '=', '[', ' ')

  private val FOLD_CLOSING_DELIMITERS = setOf(')', ',', ']', ' ')

  /** A string literal with nothing in it this generator would have to reason about. */
  private val FOLD_STRING = Regex("""^"([^"\\$\n]|\\[\\"nrt$])*"$""")

  /** A number a `listOf` can hold without the elements disagreeing about their type. */
  private val FOLD_HEX_NUMBER = Regex("""^-?(0[xX][0-9a-fA-F]+)([Ll]?)$""")

  private val FOLD_DECIMAL_NUMBER = Regex("""^-?(\d+)(\.\d+)?([fFdDLl]?)$""")

  /**
   * How many shorter windows are tried after the grown one fails — see [varyingRun]. Bounds what
   * would otherwise be a quadratic scan.
   */
  private const val FOLD_SHRINK_ATTEMPTS = 3

  /**
   * The longest run one fold may cover, bounding the growth scan. Longer runs are folded in windows
   * of this size.
   */
  private const val FOLD_MAXIMUM_WINDOW = 256

  /**
   * Loop parameter names in preference order; the first one the body doesn't contain is used so it
   * cannot shadow anything. A body containing all of them is not folded.
   */
  private val FOLD_PARAMETER_NAMES = listOf("value", "entry", "element", "each", "item")

  fun generate(
    document: ScreenDocument,
    components: ComponentRecordFile,
    packageName: String = "generated.screen",
    expressionPackages: Set<String> = emptySet(),
    preview: Preview? = null,
  ): Result {
    if (components.schemaVersion > COMPONENT_RECORD_SCHEMA_VERSION) {
      // A record from a newer producer may mean things by fields this build has never seen.
      return Result.Refused(
        listOf(
          "components.json is schema ${components.schemaVersion}, newer than the " +
            "$COMPONENT_RECORD_SCHEMA_VERSION this generator understands"
        )
      )
    }
    val badSegment = packageName.split('.').firstOrNull { !isUsableIdentifier(it) }
    if (badSegment != null) {
      return Result.Refused(
        listOf("package segment `$badSegment` is not a usable Kotlin identifier")
      )
    }
    if (!isUsableIdentifier(document.name)) {
      return Result.Refused(
        listOf("screen name `${document.name}` is not a usable Kotlin function name")
      )
    }
    if (preview != null) {
      val bad = previewRefusals(preview)
      if (bad.isNotEmpty()) return Result.Refused(bad)
    }
    // Schema 1 is refused outright: this generator's guarantees rest on fields schema 1 lacks
    // (opt-in mechanism, context receivers), and patching each one is a growing table of
    // exceptions. Regenerate the catalog instead.
    if (components.schemaVersion < COMPONENT_RECORD_OPT_IN_MECHANISM_SCHEMA) {
      return Result.Refused(
        listOf(
          "components.json is schema ${components.schemaVersion}; this generator needs at least " +
            "$COMPONENT_RECORD_OPT_IN_MECHANISM_SCHEMA, which is the first to record whether a " +
            "component needs a context receiver and which opt-in mechanism each marker uses. " +
            "Re-run discovery to regenerate the catalog."
        )
      )
    }
    // Two components can share a simple name, and a screen can share one with a component it calls
    // (which would recurse). A simple name is used only when exactly one component wants it and the
    // screen does not; the rest are called fully qualified. Nesting inside receiver-scoped slots
    // does not require qualification — imports resolve there as in hand-written code.
    val functionIssues = validateFunctions(document, preview)
    if (functionIssues.isNotEmpty()) return Result.Refused(functionIssues)
    val functionNames = document.functions.map { it.name }.toSet()
    val parameterNames = document.functions.flatMap { it.parameters }.map { it.name }.toSet()
    val claimants = components.components.groupBy { it.symbol.name }
    val simplyImportable =
      components.components
        .filter {
          claimants.getValue(it.symbol.name).size == 1 &&
            it.symbol.name != document.name &&
            it.symbol.name !in functionNames &&
            it.symbol.name !in parameterNames &&
            it.symbol.name !in RESERVED_BY_THE_WRAPPER &&
            // Reserved only when a preview is emitted. The wrapper is a top-level declaration that
            // would win over an import of the same name, turning a component call into infinite
            // recursion.
            (preview == null ||
              (it.symbol.name != PREVIEW_SIMPLE_NAME &&
                it.symbol.name != previewFunctionName(document.name))) &&
            (preview?.screenSizes != true ||
              (it.symbol.name != PREVIEW_SCREEN_SIZES_SIMPLE_NAME &&
                it.symbol.name != screenSizesPreviewFunctionName(document.name))) &&
            // The device fan-out reuses the `Preview` annotation reserved above; only the wrapper
            // is new.
            (preview?.devices.isNullOrEmpty() ||
              it.symbol.name != devicesPreviewFunctionName(document.name))
        }
        .map { it.canonicalId }
        .toSet()
    // Declarations are checked first so a misspelled variable is reported once, not per node.
    val duplicates =
      document.state.groupingBy(ScreenState::name).eachCount().filterValues { it > 1 }.keys
    if (duplicates.isNotEmpty()) {
      return Result.Refused(duplicates.sorted().map { "state `$it` is declared more than once" })
    }
    val unusableState = document.state.map(ScreenState::name).filterNot(::isUsableIdentifier)
    if (unusableState.isNotEmpty()) {
      return Result.Refused(unusableState.map { "state `$it` is not a usable Kotlin identifier" })
    }
    // A state name that collides with a component this file imports by simple name would shadow it
    // inside the function body, so the component's call site would resolve to the property.
    val shadowed =
      document.state.map(ScreenState::name).filter { name ->
        components.components.any { it.canonicalId in simplyImportable && it.symbol.name == name }
      }
    if (shadowed.isNotEmpty()) {
      return Result.Refused(
        shadowed.sorted().map {
          "state `$it` has the same name as a component this screen calls, and would shadow it"
        }
      )
    }
    // A state name is a local `val`, so it also shadows any package root the generated source
    // writes qualified (`androidx.compose.runtime.remember`, qualified callables, declared types).
    // It compiles on its own line and breaks the next, so refuse it up front.
    val qualifiedRoots = buildSet {
      add("androidx")
      // Every component: a state name may not shadow the root of either a simple or a qualified
      // call.
      components.components.mapTo(this) { it.symbol.callable.substringBefore('.') }
      expressionPackages.mapTo(this) { it.substringBefore('.') }
      document.state.mapTo(this) { it.typeFqn.substringBefore('.') }
      document.functions
        .flatMap { it.parameters }
        .filterIsInstance<ScreenParameter.Value>()
        .mapTo(this) { it.typeFqn.substringBefore('.') }
    }
    val shadowedRoots =
      document.state.map(ScreenState::name).filter { it in qualifiedRoots }.distinct()
    val capturedParameters = parameterNames.filter {
      it in qualifiedRoots ||
        it in functionNames ||
        it == document.name ||
        it in RESERVED_BY_THE_WRAPPER
    }
    if (capturedParameters.isNotEmpty())
      return Result.Refused(
        capturedParameters.map {
          "function parameter `$it` would shadow a generated function or qualified package"
        }
      )
    if (shadowedRoots.isNotEmpty()) {
      return Result.Refused(
        shadowedRoots.sorted().map {
          "state `$it` is the root of a package this screen writes in full, and would shadow it"
        }
      )
    }
    val context =
      Emission(
        ComponentIndex(components.components),
        simplyImportable,
        document.name,
        expressionPackages,
        document.state.associateBy(ScreenState::name),
        // A state named `kotlin` captures the qualifier a folded run writes, so such a document is
        // simply not folded.
        foldsRepeatedSiblings = document.state.none { it.name == "kotlin" },
        allocatedNames =
          (document.state.map(ScreenState::name) +
              components.components.map { it.symbol.name } +
              qualifiedRoots +
              document.name +
              RESERVED_BY_THE_WRAPPER +
              functionNames +
              parameterNames)
            .toMutableSet(),
        functions = document.functions.associateBy { it.name },
      )
    // Everything a hoisted binding must not shadow: declarations, simply-named components, the
    // screen.
    val bindingNamesTaken =
      document.state.map(ScreenState::name).toSet() +
        components.components.filter { it.canonicalId in simplyImportable }.map { it.symbol.name } +
        document.name +
        // And package roots, for the same reason as state names.
        qualifiedRoots
    val declaredSoFar = mutableSetOf<String>()
    val preamble =
      document.state.map { declared ->
        // The name is added after its initializer renders: a local is not in scope in its own
        // initializer.
        context.initializerScope = declaredSoFar.toSet()
        // The declared type is interpolated into `mutableStateOf<…>` and is wire data, so
        // shape-check it to prevent malformed or injected source.
        if (!isQualifiedName(declared.typeFqn)) {
          context.reasons +=
            "state `${declared.name}` is declared as `${declared.typeFqn}`, which is not a " +
              "qualified Kotlin name"
          return@map null
        }
        // Rendered against the declared type, as an assignment would be, so a wrong-kind literal is
        // refused. Nullability is stripped because every literal in this vocabulary is non-null.
        val initial =
          context.argument(
            declared.initial,
            TargetParameter.Builder(name = declared.name, type = declared.typeFqn.removeSuffix("?"))
              .also { b -> b.typeFqn = declared.typeFqn.removeSuffix("?") }
              .build(),
            "state",
          ) ?: return@map null
        declaredSoFar += declared.name
        val name = ComponentSnippets.escapeIfKeyword(declared.name)
        // Nullability is syntax, not part of a name: escape only the segments, not the trailing
        // `?`.
        val nullableType = declared.typeFqn.endsWith("?")
        val type =
          ComponentSnippets.escapeCallableIfKeyword(declared.typeFqn.removeSuffix("?")) +
            if (nullableType) "?" else ""
        // `remember`'s calculation is `@DisallowComposableCalls`, and a value may name a composable
        // read (`MaterialTheme.colorScheme.primary`), so anything naming an API is bound first.
        // Literals and state reads can't be composable calls and stay inline.
        val hoisted =
          declared.initial is ScreenValue.Reference ||
            declared.initial is ScreenValue.Construct ||
            declared.initial is ScreenValue.Chain
        // `remember` so the value survives recomposition.
        if (!hoisted) {
          listOf(
            "val $name = androidx.compose.runtime.remember { " +
              "androidx.compose.runtime.mutableStateOf<$type>($initial) }"
          )
        } else {
          val bound = initialBinding(declared.name, bindingNamesTaken)
          listOf(
            "val $bound = $initial",
            "val $name = androidx.compose.runtime.remember { " +
              "androidx.compose.runtime.mutableStateOf<$type>($bound) }",
          )
        }
      }
    // The body is not an initializer: every declaration is in scope there.
    context.initializerScope = null
    val body = context.node(document.root, depth = 1)
    val functionBodies = context.functionBodies()
    if (context.reasons.isNotEmpty()) return Result.Refused(context.reasons.toList())
    val declarations = preamble.filterNotNull().flatten()

    // An AndroidX-mechanism marker is reported by both scans; subtract it so it is written only
    // once.
    val androidxOptIns = context.androidxOptIns.distinct().sorted()
    val optIns = (context.optIns - context.androidxOptIns).distinct().sorted()
    val imports =
      (context.imports +
          context.extensionImports +
          "androidx.compose.runtime.Composable" +
          listOfNotNull(
            PREVIEW_ANNOTATION.takeIf { preview != null },
            PREVIEW_SCREEN_SIZES_ANNOTATION.takeIf { preview?.screenSizes == true },
          ))
        .distinct()
        .sorted()
    // Two imports of one simple name don't compile. Components are already deduplicated, but
    // extension links, references and construct callables are imported from wherever a projection
    // said, so check for conflicts here.
    val conflicts =
      imports
        .groupBy { it.substringAfterLast('.') }
        .filterValues {
          it.size > 1 && !resolvedByReceiver(it, context.extensionReceivers, context.imports)
        }
        .toList()
        .sortedBy { it.first }
    if (conflicts.isNotEmpty()) {
      return Result.Refused(
        conflicts.map { (name, fqns) ->
          "`$name` would be imported from ${fqns.sorted().joinToString(" and ")}, which Kotlin " +
            "rejects as a conflicting import"
        }
      )
    }
    // A local allocated mid-body could shadow an import added later for a subsequent node. Refused.
    val shadowedImports =
      context.hoistedNames.filter { local -> imports.any { it.substringAfterLast('.') == local } }
    if (shadowedImports.isNotEmpty()) {
      return Result.Refused(
        shadowedImports.sorted().map {
          "the local `$it` a member read is typed through would shadow an import of the same name"
        }
      )
    }
    // A marker is imported only when its simple name is free, decided after the conflict check so
    // an opt-in can never make a screen refuse.
    val markerNames = (optIns + androidxOptIns).groupBy { it.substringAfterLast('.') }
    val importedMarkers =
      (optIns + androidxOptIns)
        .filter { marker ->
          val simple = marker.substringAfterLast('.')
          markerNames.getValue(simple).size == 1 &&
            imports.none { it.substringAfterLast('.') == simple } &&
            simple !in context.allocatedNames &&
            simple !in RESERVED_BY_THE_WRAPPER
        }
        .toSet()
    fun marker(name: String) =
      if (name in importedMarkers) markerReference(name).substringAfterLast('.')
      else markerReference(name)
    val source = buildString {
      appendLine("package $packageName")
      appendLine()
      (imports + importedMarkers.map(::markerReference)).distinct().sorted().forEach {
        appendLine("import $it")
      }
      appendLine()
      if (optIns.isNotEmpty()) {
        appendLine(
          // Both qualified: markers can share a simple name across packages, and the caller's
          // package might declare its own `OptIn`.
          optIns.joinToString(", ", "@kotlin.OptIn(", ")") { "${marker(it)}::class" }
        )
      }
      if (androidxOptIns.isNotEmpty()) {
        // Not a stylistic variant: `kotlin.OptIn` rejects markers declared with
        // `androidx.annotation.RequiresOptIn`, and the AndroidX one takes a named `markerClass`
        // array.
        appendLine(
          androidxOptIns.joinToString(
            ", ",
            "@androidx.annotation.OptIn(markerClass = [",
            "])",
          ) {
            "${marker(it)}::class"
          }
        )
      }
      appendLine("@Composable")
      appendLine("fun ${document.name}() {")
      declarations.forEach { appendLine("    $it") }
      appendLine(body)
      appendLine("}")
      functionBodies.forEach { function ->
        appendLine()
        if (optIns.isNotEmpty())
          appendLine(optIns.joinToString(", ", "@kotlin.OptIn(", ")") { "${marker(it)}::class" })
        if (androidxOptIns.isNotEmpty())
          appendLine(
            androidxOptIns.joinToString(", ", "@androidx.annotation.OptIn(markerClass = [", "])") {
              "${marker(it)}::class"
            }
          )
        appendLine("@Composable")
        appendLine(function)
      }
      if (preview != null) {
        appendLine()
        append(previewFunction(document.name, preview))
        if (preview.screenSizes) {
          appendLine()
          append(screenSizesPreviewFunction(document.name))
        }
        val devices = preview.devices.distinct()
        if (devices.isNotEmpty()) {
          appendLine()
          append(devicesPreviewFunction(document.name, devices))
        }
      }
    }
    return Result.Emitted(source = source, requiredOptIns = optIns + androidxOptIns)
  }

  /**
   * Whether a group of same-named imports is extensions that no single call could mean two of: each
   * called only on stated receiver types, none shared. Anything else is a real conflict.
   */
  private fun resolvedByReceiver(
    group: List<String>,
    receivers: Map<String, Set<String?>>,
    ordinary: Set<String>,
  ): Boolean {
    // An import also used as an ordinary call or reference is not only an extension.
    if (group.any { it in ordinary }) return false
    val types = group.map { receivers[it] ?: return false }
    if (types.any { null in it }) return false
    return types.flatten().size == types.flatten().toSet().size
  }

  private fun validateFunctions(document: ScreenDocument, preview: Preview?): List<String> =
    buildList {
      val names = document.functions.map { it.name }
      if (names.toSet().size != names.size) add("function names must be unique")
      val reserved = buildSet {
        addAll(document.state.map { it.name })
        add(document.name)
        addAll(RESERVED_BY_THE_WRAPPER)
        if (preview != null) {
          add(PREVIEW_SIMPLE_NAME)
          add(previewFunctionName(document.name))
          if (preview.screenSizes) {
            add(PREVIEW_SCREEN_SIZES_SIMPLE_NAME)
            add(screenSizesPreviewFunctionName(document.name))
          }
          if (preview.devices.isNotEmpty()) add(devicesPreviewFunctionName(document.name))
        }
      }
      document.functions.forEach { function ->
        if (!isUsableIdentifier(function.name) || function.name in reserved) {
          add("function `${function.name}` is not an available Kotlin function name")
        }
        val parameters = function.parameters.map { it.name }
        if (parameters.toSet().size != parameters.size)
          add("function `${function.name}` has duplicate parameters")
        function.parameters.forEach { parameter ->
          if (!isUsableIdentifier(parameter.name))
            add("function `${function.name}` has an unusable parameter `${parameter.name}`")
          if (
            parameter is ScreenParameter.Value &&
              (!isQualifiedName(parameter.typeFqn) ||
                parameter.typeFqn.split('.').any { !isUsableIdentifier(it) } ||
                parameter.typeFqn.startsWith("kotlin.Function"))
          ) {
            add(
              "function `${function.name}` parameter `${parameter.name}` needs a concrete qualified value type; callbacks use Callback"
            )
          }
        }
      }
      val definitions = document.functions.associateBy { it.name }
      val visited = mutableSetOf<String>()
      val active = mutableSetOf<String>()
      fun references(node: ScreenNode, depth: Int = 0): Set<String> {
        if (depth > 128) {
          add("function tree exceeds 128 levels")
          return emptySet()
        }
        return listOfNotNull(node.function).toSet() +
          node.slots.values.flatten().flatMap { references(it, depth + 1) }
      }
      fun visit(name: String) {
        if (name in visited) return
        if (name in active || active.size >= 128) {
          add("recursive or excessively nested function call `$name`")
          return
        }
        val function = definitions[name] ?: return
        active += name
        references(function.root).forEach(::visit)
        active -= name
        visited += name
      }
      names.forEach(::visit)
    }

  /**
   * Resolves a document's component id, by canonical key or by catalog alias. Two indexes because
   * an alias is a label several records may carry; merging would let one record's alias mask
   * another's canonical key.
   */
  private class ComponentIndex(records: List<ComponentRecord>) {
    private val byCanonical = records.groupBy { it.canonicalId }
    // `distinct()` within a record only: two records claiming one alias is ambiguous even when they
    // share a canonical id, and collapsing would make resolution catalog-order-dependent.
    private val byAlias =
      records
        .flatMap { record -> record.componentIds.distinct().map { it to record } }
        .groupBy(
          { it.first },
          { it.second },
        )

    /** The record, or the reason there isn't exactly one. */
    fun resolve(id: String): Outcome {
      byCanonical[id]?.let { matches ->
        return if (matches.size == 1) Outcome.Found(matches.single())
        else
          Outcome.Ambiguous(
            "canonical id `$id` is claimed by ${matches.size} components in this catalog, so it " +
              "identifies none of them"
          )
      }
      val aliased = byAlias[id] ?: return Outcome.Missing
      return if (aliased.size == 1) Outcome.Found(aliased.single())
      else
        Outcome.Ambiguous(
          "catalog id `$id` maps to ${aliased.size} components " +
            "(${aliased.joinToString(", ") { "`${it.canonicalId}`" }}), so it identifies none of " +
            "them"
        )
    }

    sealed interface Outcome {
      data class Found(val record: ComponentRecord) : Outcome

      data class Ambiguous(val reason: String) : Outcome

      data object Missing : Outcome
    }
  }

  /**
   * Accumulates one generation pass: the text, the imports it needs, and every reason it failed.
   */
  private class Emission(
    val index: ComponentIndex,
    val simplyImportable: Set<String>,
    val screenName: String,
    val expressionPackages: Set<String>,
    /** Declared state by name, so a read can be checked rather than trusted. */
    val state: Map<String, ScreenState> = emptyMap(),
    /** Whether [foldRepeats] may fold here — see the call that computes it. */
    val foldsRepeatedSiblings: Boolean = true,
    val allocatedNames: MutableSet<String> = mutableSetOf(),
    val functions: Map<String, ScreenFunction> = emptyMap(),
  ) {
    val imports = mutableSetOf<String>()
    /** Kept apart from [imports] only so a conflict message can say an *extension* collided. */
    val extensionImports = mutableSetOf<String>()

    /**
     * For each extension import, the receiver types it was called on (null where unknown — any link
     * after the first). Same-named extensions only conflict when a call could resolve to both, so
     * the check in [generate] is on receivers.
     */
    val extensionReceivers = mutableMapOf<String, MutableSet<String?>>()
    val optIns = mutableSetOf<String>()
    val androidxOptIns = mutableSetOf<String>()
    val reasons = mutableListOf<String>()

    /**
     * State names in scope while one declaration's initializer renders, or null in the body. A
     * local is not in scope in its own initializer, so an initializer may read only earlier
     * declarations.
     */
    var initializerScope: Set<String>? = null

    /**
     * The receiver scope of the slot the current node sits in, or null at the root. Needed for
     * [ChainLink.receiverScopeFqn]: whether `Modifier.weight` compiles depends on where the node
     * was placed. Depth-first and single-threaded, so save/restore suffices.
     */
    private var slotScope: String? = null

    private data class RowScope(val variable: String, val fields: Map<String, String>)

    /** A slot lambda's parameter bound by [ScreenNode.slotParameters]: its local name and type. */
    private data class SlotParameter(val variable: String, val type: String)

    /** Every slot parameter visible at this point, by document key; innermost binding wins. */
    private var slotParameterScope: Map<String, SlotParameter> = emptyMap()

    private var rowScope: RowScope? = null
    private var parameterScope: Map<String, ScreenParameter>? = null
    private val visibleState: Map<String, ScreenState>
      get() = if (parameterScope == null) state else emptyMap()

    private val localNameCounters = mutableMapOf<String, Int>()

    /**
     * The block a node is being emitted into, and the typed locals its member links asked to
     * declare there — see [memberLink]. A local lands immediately before the call that reads it, in
     * the same composable scope and composition locals, so hoisting is exact.
     *
     * [lambdaDepth] counts value lambdas entered since this block opened; inside one a receiver is
     * evaluated when the lambda runs, so nothing is hoisted.
     */
    private class Block {
      val locals = mutableListOf<String>()
      /** Receiver text to the local already holding it, keyed by the classifier it is typed as. */
      val receivers = mutableMapOf<Pair<String, String>, String>()
      var lambdaDepth = 0
    }

    private val blocks = ArrayDeque<Block>()

    /**
     * Every name a hoisted local took, so [generate] can refuse one a later import collides with.
     */
    val hoistedNames = mutableSetOf<String>()

    private inline fun <T> insideLambda(render: () -> T): T {
      val block = blocks.lastOrNull()
      block?.let { it.lambdaDepth++ }
      try {
        return render()
      } finally {
        block?.let { it.lambdaDepth-- }
      }
    }

    /**
     * The name of a `val name: Owner = receiver` declared above the current node, or null where
     * there is no block to declare it in (a state initializer or value lambda; the caller writes
     * the `let<Owner, _>` form instead). Reused within a block unless it calls a `remember…`, since
     * each such call site is its own composition slot.
     */
    private fun hoistedReceiver(owner: String, ownerFqn: String, receiver: String): String? {
      val block = blocks.lastOrNull()?.takeIf { it.lambdaDepth == 0 } ?: return null
      val shareable = "remember" !in receiver
      if (shareable)
        block.receivers[ownerFqn to receiver]?.let {
          return it
        }
      val simple = ownerFqn.substringAfterLast('.')
      val decapitalised = simple.replaceFirstChar { it.lowercaseChar() }
      val name =
        allocateLocal(
          if (isUsableIdentifier(decapitalised)) decapitalised else "${decapitalised}Value"
        )
      hoistedNames += name
      block.locals += "val $name: $owner = $receiver"
      if (shareable) block.receivers[ownerFqn to receiver] = name
      return name
    }

    /**
     * An argument whose whole text is a receiver this block already holds, written as that local.
     */
    private fun sharedArgument(argument: String): String {
      val block = blocks.lastOrNull() ?: return argument
      val equals = argument.indexOf(" = ")
      if (equals < 0) return argument
      val value = argument.substring(equals + 3)
      val local =
        block.receivers.entries.singleOrNull { it.key.second == value }?.value ?: return argument
      return argument.substring(0, equals + 3) + local
    }

    private fun allocateLocal(base: String): String {
      var counter = localNameCounters[base] ?: 0
      var candidate: String
      do {
        candidate = if (counter == 0) base else "${base}_$counter"
        counter++
      } while (
        candidate in allocatedNames || imports.any { it.substringAfterLast('.') == candidate }
      )
      localNameCounters[base] = counter
      allocatedNames += candidate
      return candidate
    }

    private fun parameterTarget(parameter: ScreenParameter): TargetParameter =
      when (parameter) {
        is ScreenParameter.Value ->
          TargetParameter.Builder(name = parameter.name, type = parameter.typeFqn)
            .also { b -> b.typeFqn = parameter.typeFqn }
            .build()
        is ScreenParameter.Callback ->
          TargetParameter.Builder(name = parameter.name, type = "() -> Unit")
            .also { b ->
              b.typeFqn = "kotlin.Function0"
              b.lambdaReturnTypeFqn = "kotlin.Unit"
            }
            .build()
      }

    fun functionBodies(): List<String> =
      functions.values.map { function ->
        parameterScope = function.parameters.associateBy { it.name }
        rowScope = null
        slotScope = null
        try {
          val parameters =
            function.parameters.joinToString(", ") {
              val type =
                when (it) {
                  is ScreenParameter.Value -> it.typeFqn
                  is ScreenParameter.Callback -> "() -> kotlin.Unit"
                }
              "${it.name}: $type"
            }
          "private fun ${function.name}($parameters) {\n${node(function.root, 1)}\n}"
        } finally {
          parameterScope = null
          rowScope = null
          slotScope = null
        }
      }

    private fun parameterRead(value: ScreenValue.ParameterRead, where: String): String? {
      val declaration = parameterScope?.get(value.parameter)
      if (declaration == null || parameterTarget(declaration).typeFqn != value.typeFqn) {
        reasons +=
          "$where reads parameter `${value.parameter}` as ${value.typeFqn}, but this function does not declare that type"
        return null
      }
      return declaration.name
    }

    private fun functionCall(node: ScreenNode, depth: Int): String {
      val function = functions[node.function]
      if (function == null) {
        reasons += "no generated function `${node.function}`"
        return ""
      }
      if (node.componentId.isNotEmpty() || node.slots.isNotEmpty() || node.slotItems.isNotEmpty()) {
        reasons +=
          "function `${function.name}` cannot also call a component or carry slots or slot items"
      }
      val declared = function.parameters.map { it.name }.toSet()
      (node.arguments.keys + node.handlers.keys - declared).forEach {
        reasons += "function `${function.name}` has no parameter `$it`"
      }
      val arguments =
        function.parameters.map { parameter ->
          val target = parameterTarget(parameter)
          val value = node.arguments[parameter.name]
          val handler = node.handlers[parameter.name]
          if (value != null && handler != null)
            reasons += "function `${function.name}` supplies `${parameter.name}` twice"
          val rendered =
            when {
              value != null -> argument(value, target, function.name)
              handler != null -> lambda(handler, target, function.name)
              else -> {
                reasons += "function `${function.name}` is missing parameter `${parameter.name}`"
                null
              }
            }
          "${parameter.name} = $rendered"
        }
      return INDENT.repeat(depth) + function.name + "(" + arguments.joinToString(", ") + ")"
    }

    fun node(node: ScreenNode, depth: Int): String {
      val block = Block()
      blocks.addLast(block)
      val rendered =
        try {
          nodeCall(node, depth)
        } finally {
          blocks.removeLast()
        }
      if (block.locals.isEmpty()) return rendered
      val pad = INDENT.repeat(depth)
      return block.locals.joinToString("") { "$pad$it\n" } + rendered
    }

    private fun nodeCall(node: ScreenNode, depth: Int): String {
      if (depth > 128) {
        reasons += "screen nesting exceeds 128 levels"
        return ""
      }
      // Asked before the structural paths return: none has a slot lambda a binding could name.
      if (
        (node.repetition != null || node.selection != null || node.function != null) &&
          node.slotParameters.isNotEmpty()
      ) {
        reasons +=
          "a repetition, selection or function call has no slot lambda to name a parameter of"
      }
      if (node.repetition != null) return repetition(node, depth)
      if (node.selection != null) return selection(node, depth)
      if (node.function != null) return functionCall(node, depth)
      val pad = INDENT.repeat(depth)
      val record =
        when (val outcome = index.resolve(node.componentId)) {
          is ComponentIndex.Outcome.Found -> outcome.record
          is ComponentIndex.Outcome.Ambiguous -> {
            reasons += outcome.reason
            null
          }
          ComponentIndex.Outcome.Missing -> {
            reasons += "no component `${node.componentId}` in this catalog"
            null
          }
        }
      if (record == null) {
        // Keep walking children so every node a stale catalog can no longer place is reported.
        node.slots.values.flatten().forEach { node(it, depth + 1) }
        return "$pad// unresolved: ${node.componentId}"
      }
      // The licence to call at all, decided once by the producer. A record refused only for a
      // placeholder this node supplies is re-checked with the node's own arguments counted as
      // present, but only when the record's own fields reproduce the stored refusal — a producer's
      // refusal is never overridden.
      val recorded = record.code
      val code =
        if (recorded?.call != null || recorded == null) recorded
        else {
          val supplied = node.arguments.keys + node.slots.keys + node.handlers.keys
          val rederived = ComponentSnippets.refusalWith(record, emptySet())
          if (
            rederived != null &&
              rederived == recorded.refusedReason &&
              ComponentSnippets.refusalWith(record, supplied) == null
          )
            ComponentCode.Builder()
              .also { b ->
                b.call = "${ComponentSnippets.escapeIfKeyword(record.symbol.name)}(…)"
                b.imports = listOf(record.symbol.callable)
                b.requiredOptIns = record.requiredOptIns
                b.androidxOptIns = record.androidxOptIns
              }
              .build()
          else recorded
        }
      if (code?.call == null) {
        reasons +=
          "`${node.componentId}` has no call site: ${code?.refusedReason ?: "no code was recorded"}"
        node.slots.values.flatten().forEach { node(it, depth + 1) }
        return "$pad// unusable: ${node.componentId}"
      }
      val qualified = ComponentSnippets.escapeCallableIfKeyword(record.symbol.callable)
      // An object member (`SearchBarDefaults.InputField`) is written through its object, which
      // imports.
      val owner = record.symbol.callable.substringBeforeLast('.', "")
      val objectMember = owner.substringAfterLast('.').firstOrNull()?.isUpperCase() == true
      if (record.canonicalId in simplyImportable)
        imports += if (objectMember) ComponentSnippets.escapeCallableIfKeyword(owner) else qualified
      markers(code.requiredOptIns, optIns, "`${record.symbol.name}`")
      markers(code.androidxOptIns, androidxOptIns, "`${record.symbol.name}`")

      val byName = record.parameters.associateBy { it.name }
      node.arguments.keys.filterNot(byName::containsKey).forEach {
        reasons += "`${record.symbol.name}` has no parameter `$it`"
      }
      node.slots.forEach { (slot, children) ->
        val parameter = byName[slot]
        val rejected =
          when {
            parameter == null -> "`${record.symbol.name}` has no slot `$slot`"
            !fillable(parameter, node.slotItems[slot]) ->
              "`${record.symbol.name}`.`$slot` is a parameter, not a @Composable slot"
            else -> null
          }
        if (rejected != null) {
          reasons += rejected
          // Walk the children of an undeclared slot too, so a stale subtree is fully reported.
          children.forEach { node(it, depth + 1) }
        }
      }
      // A slot parameter named for a slot with no children binds a name nothing can read: stale
      // document.
      node.slotParameters.keys
        .filterNot { it in node.slots }
        .sorted()
        .forEach {
          reasons += "`${record.symbol.name}`.`$it` names its lambda parameter and has no children"
        }
      // A wrapper for a slot the node does not fill would otherwise be dropped silently.
      node.slotItems.keys
        .filterNot { it in node.slots }
        .sorted()
        .forEach {
          reasons +=
            "`${record.symbol.name}`.`$it` wraps its children in a slot item and has no children"
        }

      val arguments = mutableListOf<String>()
      var trailing: String? = null
      // An unknown handler is refused, not dropped: a button that does nothing still compiles.
      node.handlers.keys
        .filterNot { key -> record.parameters.any { it.name == key } }
        .sorted()
        .forEach { reasons += "`${record.symbol.name}` has no `$it` to bind a handler to" }
      for (parameter in record.parameters) {
        val supplied = node.arguments[parameter.name]
        val children = node.slots[parameter.name]
        val handler = node.handlers[parameter.name]
        if (handler != null) {
          lambda(handler, parameter, record.symbol.name)?.let {
            arguments += "${ComponentSnippets.escapeIfKeyword(parameter.name)} = $it"
          }
          continue
        }
        when {
          supplied != null -> {
            argument(supplied, parameter, record.symbol.name)?.let {
              arguments += "${ComponentSnippets.escapeIfKeyword(parameter.name)} = $it"
            }
            // A conflicted document can set both an argument and a slot for one parameter; the
            // scalar is refused, but walk the slot's children unless the slot loop above already
            // did.
            if (children != null) {
              reasons +=
                "`${record.symbol.name}`.`${parameter.name}` is set as both a value and a slot"
              if (fillable(parameter, node.slotItems[parameter.name])) {
                children.forEach { node(it, depth + 1) }
              }
            }
          }
          children != null &&
            fillable(parameter, node.slotItems[parameter.name]) &&
            !ComponentSnippets.acceptsBareLambda(parameter.type) -> {
            // `code.call` may have been emittable only because this slot was defaulted away; a
            // non-`Unit` or parameterised lambda can't be satisfied by `{ children }`.
            reasons +=
              "`${record.symbol.name}`.`${parameter.name}` is `${parameter.type}`, which children " +
                "in a bare lambda cannot satisfy"
            children.forEach { node(it, depth + 1) }
          }
          children != null && fillable(parameter, node.slotItems[parameter.name]) -> {
            // A DSL slot declares children through its receiver (`item { … }` in `LazyListScope`),
            // so the wrapper is resolved and checked first. See [SlotItem].
            val declared = node.slotItems[parameter.name]
            val wrapper = declared?.let { slotItem(it, parameter, record.symbol.name) }
            if (declared != null && wrapper == null) {
              children.forEach { node(it, depth + 1) }
              continue
            }
            val outer = slotScope
            // Inside a wrapper the receiver is the wrapper's own, which nothing attests, so scoped
            // links refuse.
            slotScope = if (wrapper == null) parameter.composableSlotReceiver else null
            // Name the lambda's parameter when the document reads it (`{ padding -> … }`). Only
            // single-parameter lambdas composed into directly can be named.
            val key = node.slotParameters[parameter.name]
            val bound = key?.let { k ->
              val type = ComponentSnippets.singleLambdaParameterType(parameter.type)
              if (type == null || wrapper != null) {
                reasons +=
                  "`${record.symbol.name}`.`${parameter.name}` is `${parameter.type}`, whose " +
                    "lambda has no single parameter to name"
                null
              } else k to SlotParameter(allocateLocal(localNameFor(k)), type)
            }
            val outerParameters = slotParameterScope
            if (bound != null) slotParameterScope = slotParameterScope + bound
            val head = bound?.let { "{ ${it.second.variable} ->" } ?: "{"
            val inner = INDENT.repeat(depth + 1)
            val nested =
              try {
                if (wrapper == null) {
                  val rendered = children.map { node(it, depth + 1) }
                  if (foldsRepeatedSiblings) foldRepeats(rendered, inner)
                  else rendered.joinToString("\n")
                } else
                  children.joinToString("\n") { child ->
                    "$inner$wrapper {\n${node(child, depth + 2)}\n$inner}"
                  }
              } finally {
                slotScope = outer
                slotParameterScope = outerParameters
              }
            // `content` trails the parentheses, as Compose is written. Keyed on the name rather
            // than position, since a record may list only some parameters; the API guidelines put
            // `content` last.
            if (parameter.name == "content" && parameter === record.parameters.last())
              trailing = "$head\n$nested\n$pad}"
            else
              arguments +=
                "${ComponentSnippets.escapeIfKeyword(parameter.name)} = $head\n$nested\n$pad}"
          }
          // Untouched by the document: a default may be omitted; anything else gets the call-site
          // placeholder.
          parameter.hasDefault -> Unit
          else -> {
            val placeholder = ComponentSnippets.placeholderFor(parameter)
            if (placeholder == null) {
              reasons +=
                "`${record.symbol.name}` needs `${parameter.name}: ${parameter.type}` and the " +
                  "document does not set it"
            } else {
              // A constructed placeholder (`TextFieldState()`) or `rememberT()` factory needs an
              // import, which goes through the same conflict check — and may not be named `kotlin`,
              // which folds would capture.
              (ComponentSnippets.constructedTypeOf(parameter)
                  ?: ComponentSnippets.factoryCallableOf(parameter))
                ?.let {
                  val simple = it.substringAfterLast('.')
                  if (simple in RESERVED_BY_THE_WRAPPER) {
                    reasons +=
                      "`${record.symbol.name}`.`${parameter.name}` imports `$simple`, which the " +
                        "generated file spends on its own scaffolding"
                  } else {
                    imports += ComponentSnippets.escapeCallableIfKeyword(it)
                  }
                }
              arguments += "${ComponentSnippets.escapeIfKeyword(parameter.name)} = $placeholder"
            }
          }
        }
      }
      val name =
        when {
          record.canonicalId !in simplyImportable -> qualified
          objectMember ->
            ComponentSnippets.escapeIfKeyword(owner.substringAfterLast('.')) +
              "." +
              ComponentSnippets.escapeIfKeyword(record.symbol.name)
          else -> ComponentSnippets.escapeIfKeyword(record.symbol.name)
        }
      val rendered = arguments.joinToString(", ") { sharedArgument(it) }
      return when {
        trailing == null -> "$pad$name($rendered)"
        rendered.isEmpty() -> "$pad$name $trailing"
        else -> "$pad$name($rendered) $trailing"
      }
    }

    /**
     * Whether this parameter can take children. A `@Composable` slot always can; a scope DSL
     * (`LazyListScope.() -> Unit`) only with a [SlotItem] saying how to declare them.
     */
    private fun fillable(parameter: TargetParameter, item: SlotItem?): Boolean =
      parameter.composableSlot || (item != null && parameter.scopeDslReceiver != null)

    /**
     * The call each DSL-slot child is wrapped in (`item`, `item(key = "a")`), or null having
     * recorded why not. `item` is a receiver member, never imported, so it is valid only in a slot
     * whose receiver is exactly that scope type.
     */
    private fun slotItem(
      item: SlotItem,
      parameter: TargetParameter,
      component: String,
    ): String? {
      val where = "`$component`.`${parameter.name}`"
      val scope = parameter.scopeDslReceiver ?: parameter.composableSlotReceiver
      if (scope != item.receiverScopeFqn) {
        reasons +=
          "$where wraps its children in `${item.callable}`, which is declared on " +
            "`${item.receiverScopeFqn}`, and this slot composes under " +
            (scope?.let { "`$it`" } ?: "no receiver")
        return null
      }
      val callable = name(item.callable, where) ?: return null
      if (item.positional.isEmpty() && item.named.isEmpty()) return callable
      val arguments = arguments(item.positional, item.named, where, 0) ?: return null
      return "$callable($arguments)"
    }

    /**
     * A handler, as a lambda assigning declared state. The parameter must be a zero-argument
     * function type; anything else would need parameters this generator has no names for.
     */
    fun lambda(actions: List<ScreenAction>, parameter: TargetParameter, owner: String): String? =
      insideLambda {
        actionLambda(actions, parameter, owner)
      }

    private fun actionLambda(
      actions: List<ScreenAction>,
      parameter: TargetParameter,
      owner: String,
    ): String? {
      val where = "`$owner`.`${parameter.name}`"
      // A composable slot is not an event callback even though `content: @Composable () -> Unit`
      // reads as `() -> Unit`; a `Toggle` bound there would flip on every composition and recompose
      // forever.
      if (parameter.composableSlot) {
        reasons += "$where is a composable slot rather than an event callback"
        return null
      }
      // Zero arguments, not merely "a bare lambda fits": a handler that ignores `onValueChange`'s
      // value compiles and silently drops what the control reported.
      if (!ComponentSnippets.acceptsZeroArgLambda(parameter.type)) {
        reasons += "$where is `${parameter.type}`, which a generated handler cannot satisfy"
        return null
      }
      if (actions.isEmpty()) {
        // An empty handler is a button that looks live and is not.
        reasons += "$where binds a handler with no actions"
        return null
      }
      val statements = actions.map { action ->
        val declared = visibleState[action.variable]
        if (declared == null) {
          if (parameterScope != null) {
            reasons +=
              "$where cannot capture screen state `${action.variable}` inside a function; pass a callback parameter"
            return null
          }
          reasons +=
            "$where writes `${action.variable}`, which this screen does not declare" +
              if (state.isEmpty()) ""
              else " (it declares ${state.keys.sorted().joinToString(", ")})"
          return null
        }
        val target = name(action.variable, where) ?: return null
        when (action) {
          is ScreenAction.Toggle -> {
            if (declared.typeFqn != "kotlin.Boolean") {
              reasons +=
                "$where toggles `${action.variable}`, which is a ${declared.typeFqn} rather " +
                  "than a kotlin.Boolean"
              return null
            }
            "$target.value = !$target.value"
          }
          is ScreenAction.Set -> {
            if (action.value.typeFqn != null && action.value.typeFqn != declared.typeFqn) {
              reasons +=
                "$where sets `${action.variable}` to a ${action.value.typeFqn}, and it is " +
                  "declared as a ${declared.typeFqn}"
              return null
            }
            // Handlers are emitted inside the tree with no composable scope to hoist into, so a
            // reference, construct or chain (which may be a composable read) is refused here.
            if (
              action.value is ScreenValue.Reference ||
                action.value is ScreenValue.Construct ||
                action.value is ScreenValue.Chain
            ) {
              reasons +=
                "$where sets `${action.variable}` from an expression that names an API, which a " +
                  "handler cannot evaluate — an event callback is not a composable scope"
              return null
            }
            // Literals carry no type, so they are checked by rendering against the declared type.
            val rendered =
              argument(
                action.value,
                TargetParameter.Builder(name = action.variable, type = declared.typeFqn)
                  .also { b -> b.typeFqn = declared.typeFqn }
                  .build(),
                owner,
              ) ?: return null
            "$target.value = $rendered"
          }
        }
      }
      return "{ ${statements.joinToString("; ")} }"
    }

    private fun repetition(node: ScreenNode, depth: Int): String {
      val repetition = requireNotNull(node.repetition)
      val pad = INDENT.repeat(depth)
      if (
        node.componentId.isNotEmpty() ||
          node.arguments.isNotEmpty() ||
          node.handlers.isNotEmpty() ||
          node.slotItems.isNotEmpty() ||
          node.selection != null ||
          node.function != null
      ) {
        reasons +=
          "repetition cannot also call a component or carry arguments, handlers, slot items or selection"
      }
      if (node.slots.keys != setOf(repetition.templateSlot)) {
        reasons += "repetition must have exactly its template slot `${repetition.templateSlot}`"
      }
      if (repetition.rows.size > 10_000) {
        reasons += "repetition exceeds 10000 authored rows"
        return ""
      }
      if (
        repetition.fields.any { (key, type) ->
          key.isEmpty() ||
            !isQualifiedName(type) ||
            type.split('.').any { !isUsableIdentifier(it) } ||
            type.startsWith("kotlin.Function")
        }
      ) {
        reasons += "repetition fields require nonempty keys and qualified non-function value types"
        return ""
      }
      if ("kotlin" in state) {
        reasons += "state `kotlin` shadows the package required by repetition"
      }
      repetition.fields.values
        .map { it.substringBefore('.') }
        .filter { it in state }
        .forEach { reasons += "state `$it` shadows a repetition field type's package" }
      allocatedNames += repetition.fields.values.map { it.substringBefore('.') }
      val className = allocateLocal("ScreenRow")
      val variable = allocateLocal("screenRow")
      val fields = repetition.fields.entries.toList()
      val parameters = fields.mapIndexed { index, (_, type) -> "val field$index: $type" }
      // Evaluate initializers before entering the template's scope, so nested loops read the
      // enclosing row.
      val rows =
        repetition.rows.mapIndexed { rowIndex, row ->
          if (row.keys != repetition.fields.keys) {
            reasons += "repetition row $rowIndex must supply exactly ${repetition.fields.keys}"
          }
          val arguments = fields.map { (key, type) ->
            row[key]?.let {
              argument(
                it,
                TargetParameter.Builder(name = key, type = type)
                  .also { b -> b.typeFqn = type }
                  .build(),
                "row $rowIndex",
              )
            }
          }
          "$className(${arguments.joinToString(", ")})"
        }
      val outer = rowScope
      rowScope = RowScope(variable, repetition.fields)
      val body =
        try {
          node.slots.values.flatten().joinToString("\n") { node(it, depth + 1) }
        } finally {
          rowScope = outer
        }
      val declaration =
        if (parameters.isEmpty()) "class $className"
        else "data class $className(${parameters.joinToString(", ")})"
      return "$pad$declaration\n${pad}kotlin.collections.listOf<$className>(${rows.joinToString(", ")}).forEach { $variable ->\n$body\n$pad}"
    }

    /**
     * A read of a slot lambda's parameter, checked against the record's declared type. Qualified or
     * generic types must match exactly; an unqualified one matches by simple name only against a
     * plain class-name claim.
     */
    private fun slotParameterRead(value: ScreenValue.SlotParameterRead, where: String): String? {
      val binding = slotParameterScope[value.key]
      fun plain(type: String) = type.none { it == '<' || it == '(' || it == '?' || it == ' ' }
      val matches =
        binding != null &&
          when {
            binding.type == value.typeFqn -> true
            '.' in binding.type || !plain(binding.type) -> false
            else -> plain(value.typeFqn) && value.typeFqn.substringAfterLast('.') == binding.type
          }
      if (binding == null || !matches) {
        reasons +=
          "$where reads slot parameter `${value.key}` as ${value.typeFqn}, but " +
            (binding?.let { "that slot's lambda takes ${it.type}" } ?: "no enclosing slot binds it")
        return null
      }
      return binding.variable
    }

    /** A document key as a Kotlin local: its identifier characters, never a keyword or empty. */
    private fun localNameFor(key: String): String {
      val cleaned = key.filter { it.isLetterOrDigit() || it == '_' }
      val base = if (cleaned.isEmpty() || cleaned.first().isDigit()) "slot$cleaned" else cleaned
      return if (ComponentSnippets.escapeIfKeyword(base) != base) "${base}Value" else base
    }

    private fun rowRead(value: ScreenValue.RowRead, where: String): String? {
      val scope = rowScope
      val type = scope?.fields?.get(value.field)
      if (type == null || type != value.typeFqn) {
        reasons +=
          "$where reads row field `${value.field}` as ${value.typeFqn}, but its scope declares ${type ?: "no such field"}"
        return null
      }
      return "${scope.variable}.field${scope.fields.keys.indexOf(value.field)}"
    }

    private fun selection(node: ScreenNode, depth: Int): String {
      val selection = requireNotNull(node.selection)
      val pad = INDENT.repeat(depth)
      val inner = INDENT.repeat(depth + 1)
      // Walk every branch so diagnostics don't hide broken components behind the selected state.
      val branches =
        node.slots.mapValues { (_, children) ->
          children.joinToString("\n") { node(it, depth + 2) }
        }
      if (
        node.componentId.isNotEmpty() ||
          node.arguments.isNotEmpty() ||
          node.handlers.isNotEmpty() ||
          node.slotItems.isNotEmpty() ||
          node.function != null
      ) {
        reasons +=
          "selection cannot also call a component or carry arguments, handlers or slot items"
      }
      if (selection.cases.isEmpty()) reasons += "selection must declare at least one case"
      val referenced = selection.cases.keys + listOfNotNull(selection.elseSlot)
      if (selection.elseSlot in selection.cases) {
        reasons += "selection fallback `${selection.elseSlot}` is also a case"
      }
      (referenced - node.slots.keys).forEach { reasons += "selection has no slot `$it`" }
      (node.slots.keys - referenced).forEach {
        reasons += "selection slot `$it` has no case or fallback"
      }
      val subjectType =
        selection.subject.typeFqn
          ?: when (val subject = selection.subject) {
            is ScreenValue.Text -> "kotlin.String"
            is ScreenValue.Bool -> "kotlin.Boolean"
            is ScreenValue.Whole ->
              if (subject.value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) "kotlin.Int"
              else "kotlin.Long"
            is ScreenValue.Fractional32 -> "kotlin.Float"
            is ScreenValue.Fractional -> "kotlin.Double"
            else -> null
          }
      if (
        subjectType?.removeSuffix("?") !in
          setOf(
            "kotlin.String",
            "kotlin.Boolean",
            "kotlin.Int",
            "kotlin.Long",
            "kotlin.Float",
            "kotlin.Double",
          )
      ) {
        reasons += "selection subject must be a scalar value, was $subjectType"
        return "$pad// invalid selection"
      }
      val type = requireNotNull(subjectType)
      val subject =
        argument(
          selection.subject,
          TargetParameter.Builder(name = "subject", type = type)
            .also { b -> b.typeFqn = type }
            .build(),
          "selection",
        )
      val parameter =
        TargetParameter.Builder(name = "case", type = type.removeSuffix("?"))
          .also { b -> b.typeFqn = type.removeSuffix("?") }
          .build()
      val seen = mutableSetOf<String>()
      val rendered =
        selection.cases
          .map { (slot, value) ->
            if (
              value !is ScreenValue.Text &&
                value !is ScreenValue.Bool &&
                value !is ScreenValue.Whole &&
                value !is ScreenValue.Fractional &&
                value !is ScreenValue.Fractional32
            ) {
              reasons += "selection case `$slot` must be a literal"
              return@map "$inner// invalid case"
            }
            val match = argument(value, parameter, "selection `$slot`")
            // Primitive equality treats signed zeros as equal and float narrowing can merge
            // decimals, so compare the emitted spellings.
            val key =
              when (match) {
                "-0.0",
                "0.0" -> "0.0"
                "-0.0f",
                "0.0f" -> "0.0f"
                else -> match
              }
            if (key != null && !seen.add(key)) reasons += "selection case `$slot` duplicates $match"
            "$inner$match -> {\n${branches[slot].orEmpty()}\n$inner}"
          }
          .toMutableList()
      rendered +=
        selection.elseSlot?.let { "${inner}else -> {\n${branches[it].orEmpty()}\n$inner}" }
          ?: "${inner}else -> {}"
      return "${pad}when ($subject) {\n${rendered.joinToString("\n")}\n$pad}"
    }

    /**
     * A state read, as `.value` of the declared property — not a `by` delegate, which would need
     * `getValue`/`setValue` imports that the package could shadow.
     */
    fun stateRead(value: ScreenValue.StateRead, where: String): String? {
      val declared = visibleState[value.variable]
      if (declared == null) {
        if (parameterScope != null) {
          reasons +=
            "$where cannot capture screen state `${value.variable}` inside a function; pass a value parameter"
          return null
        }
        reasons +=
          "$where reads `${value.variable}`, which this screen does not declare" +
            if (state.isEmpty()) "" else " (it declares ${state.keys.sorted().joinToString(", ")})"
        return null
      }
      if (declared.typeFqn != value.typeFqn) {
        reasons +=
          "$where reads `${value.variable}` as a ${value.typeFqn}, and it is declared as a " +
            declared.typeFqn
        return null
      }
      initializerScope?.let { inScope ->
        if (value.variable !in inScope) {
          reasons +=
            "$where reads `${value.variable}`, which is not declared before it" +
              if (value.variable in state) " (a later declaration, or itself)" else ""
          return null
        }
      }
      val escaped = name(value.variable, where) ?: return null
      return "$escaped.value"
    }

    /**
     * `{ 0.4f }` for a parameter that takes a zero-argument lambda, or null having said why not.
     *
     * Checked on the classifier: only `kotlin.Function0` qualifies (`(Int) -> Float` and `Float.()
     * -> Float` are both `Function1`). Not `acceptsZeroArgLambda`, which requires `-> Unit`. The
     * body is checked by [argument] against the return type, so literal rules aren't restated.
     */
    private fun lambda(
      value: ScreenValue.Lambda,
      parameter: TargetParameter,
      owner: String,
    ): String? = insideLambda { valueLambda(value, parameter, owner) }

    private fun valueLambda(
      value: ScreenValue.Lambda,
      parameter: TargetParameter,
      owner: String,
    ): String? {
      val where = "`$owner`.`${parameter.name}`"
      val returns = parameter.lambdaReturnTypeFqn
      if (returns == null || parameter.typeFqn != "kotlin.Function0") {
        reasons +=
          "$where is `${parameter.type}`, which a `{ … }` returning a value does not satisfy"
        return null
      }
      // A parameter standing for the lambda's result, so the body is checked as an argument to it.
      val body =
        argument(
          value.result,
          TargetParameter.Builder(name = parameter.name, type = returns.substringAfterLast('.'))
            .also { b -> b.typeFqn = returns }
            .build(),
          owner,
        ) ?: return null
      return "{ $body }"
    }

    /**
     * The Kotlin expression for [value] as an argument to [parameter], or null having recorded why.
     *
     * A literal is rendered against the parameter's type (`Whole(1)` is an `Int` or a `Long` as the
     * parameter decides). A [ScreenValue.Reference], [ScreenValue.Construct] or [ScreenValue.Chain]
     * renders on its own terms and its claimed type must equal the parameter's. Both compare the
     * qualified type, so `com.example.String` is not handed a string literal.
     */
    fun argument(value: ScreenValue, parameter: TargetParameter, owner: String): String? {
      val type = ComponentSnippets.qualifiedTypeOf(parameter)
      val where = "`$owner`.`${parameter.name}`"
      if (
        value is ScreenValue.ParameterRead &&
          parameterScope?.get(value.parameter) is ScreenParameter.Callback &&
          (parameter.composableSlot ||
            !ComponentSnippets.acceptsZeroArgLambda(parameter.type) ||
            parameter.lambdaReturnTypeFqn?.let { it != "kotlin.Unit" } == true)
      ) {
        reasons += "$where cannot take a zero-argument Unit callback parameter"
        return null
      }
      if (value.typeFqn != null) {
        val rendered = expression(value, where, depth = 0) ?: return null
        if (value.typeFqn != type) {
          reasons += "$where is $type, and this value is a ${value.typeFqn}"
          return null
        }
        return rendered
      }
      // `string` records its own reason for an over-long value, so compare counts to avoid a
      // second, generic "is not a String" on top of it.
      val before = reasons.size
      val literal =
        when (value) {
          is ScreenValue.Text -> if (type != "kotlin.String") null else string(value.value, where)
          is ScreenValue.Bool -> if (type == "kotlin.Boolean") value.value.toString() else null
          is ScreenValue.Whole ->
            when (type) {
              "kotlin.Int" ->
                // `toInt()` wraps silently.
                if (value.value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())
                  value.value.toString()
                else null
              "kotlin.Long" -> long(value.value)
              else -> null
            }
          is ScreenValue.Fractional32 ->
            // Its own spelling, so it fits `Float` only; widening into `Double` is the coercion to
            // avoid.
            if (type == "kotlin.Float" && value.value.isFinite()) floatLiteral(value.value)
            else null
          is ScreenValue.Fractional ->
            when {
              // Neither `NaN` nor `Infinity` is a Kotlin literal.
              !value.value.isFinite() -> null
              type == "kotlin.Float" -> {
                // The same narrowing rule as `Int`, which the first pass missed one type down: a
                // `Double` past `Float`'s range becomes `Infinity`, and one below it collapses to
                // zero. The float's own rendering is emitted, so the literal is exactly the value
                // the parameter will hold rather than a `Double` spelling with an `f` stapled on.
                val narrowed = value.value.toFloat()
                val lost = !narrowed.isFinite() || (narrowed == 0.0f && value.value != 0.0)
                if (lost) null else "${narrowed}f"
              }
              type == "kotlin.Double" -> value.value.toString()
              else -> null
            }
          // Held to the parameter's *return* type; every zero-argument function type is
          // `kotlin.Function0`.
          is ScreenValue.Lambda -> lambda(value, parameter, owner)
          is ScreenValue.ActionLambda -> lambda(value.actions, parameter, owner)
          else -> null
        }
      if (literal == null && reasons.size == before) {
        reasons += "$where is $type, which ${value::class.simpleName} is not"
      }
      return literal
    }

    /**
     * [value] rendered on its own terms, for nested positions with no declared type (a constructor
     * argument, a chain receiver), or null having said why. A whole number is an `Int` when it fits
     * and a `Long` otherwise, and a fraction is always a `Double` — so `Dp(16.0)` doesn't compile
     * and a projection writes `16.dp` as a [ScreenValue.Chain] instead.
     */
    fun expression(value: ScreenValue, where: String, depth: Int): String? {
      // Documents are wire data with unbounded nesting; cap depth with a refusal, not a stack
      // overflow.
      if (depth > MAX_VALUE_DEPTH) {
        reasons += "$where nests values more than $MAX_VALUE_DEPTH deep"
        return null
      }
      // Collected at every nesting level: a construct's argument can name a gated API too.
      markers(value.requiredOptIns, optIns, where)
      markers(value.androidxOptIns, androidxOptIns, where)
      return when (value) {
        is ScreenValue.Text -> string(value.value, where)
        is ScreenValue.Bool -> value.value.toString()
        is ScreenValue.Whole ->
          if (value.value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) value.value.toString()
          else long(value.value)
        is ScreenValue.Fractional ->
          if (value.value.isFinite()) value.value.toString()
          else {
            reasons += "$where is ${value.value}, which is not a Kotlin literal"
            null
          }
        // The one nested fraction that is not a `Double` — see `ScreenValue.Fractional32`.
        is ScreenValue.Fractional32 ->
          if (value.value.isFinite()) floatLiteral(value.value)
          else {
            reasons += "$where is ${value.value}, which is not a Kotlin literal"
            null
          }
        // Nested, the body takes its kind's fixed spelling (`rememberCarouselState { 5 }` is an
        // `Int`).
        is ScreenValue.Lambda ->
          insideLambda { expression(value.result, where, depth + 1) }?.let { "{ $it }" }
        // No discovered signature here: validate the actions and let the compiler check the
        // enclosing call.
        is ScreenValue.ActionLambda ->
          lambda(
            value.actions,
            TargetParameter.Builder(name = "callback", type = "() -> Unit")
              .also { b -> b.typeFqn = "kotlin.Function0" }
              .build(),
            where,
          )
        is ScreenValue.StateRead -> stateRead(value, where)
        is ScreenValue.RowRead -> rowRead(value, where)
        is ScreenValue.SlotParameterRead -> slotParameterRead(value, where)
        is ScreenValue.ParameterRead -> parameterRead(value, where)
        is ScreenValue.Reference -> {
          val root = importedName(value.rootFqn, where) ?: return null
          val members = value.members.map { name(it, where) ?: return null }
          (listOf(root) + members).joinToString(".")
        }
        is ScreenValue.Construct -> {
          val callable = importedName(value.callableFqn, where) ?: return null
          val argb = (value.positional.singleOrNull() as? ScreenValue.Whole)?.value
          // `Color(0xFF1A73E8)`, the ARGB spelling colours are read in.
          if (
            value.callableFqn == COLOR &&
              value.named.isEmpty() &&
              argb != null &&
              argb in 0..0xFFFFFFFFL
          ) {
            return "$callable(0x${argb.toString(16).uppercase().padStart(8, '0')})"
          }
          val arguments = arguments(value.positional, value.named, where, depth) ?: return null
          "$callable($arguments)"
        }
        is ScreenValue.Chain -> {
          if (value.links.isEmpty()) {
            reasons += "$where is a chain with no links, which is a plain reference"
            return null
          }
          val rendered = expression(value.receiver, where, depth + 1) ?: return null
          // `-1.dp` is `-(1.dp)`: the selector binds tighter than unary minus, which would silently
          // negate after applying the extension.
          val receiver = if (rendered.startsWith("-")) "($rendered)" else rendered
          // A builder, because a member link may replace everything written so far with its hoisted
          // local.
          val chain = StringBuilder(receiver)
          with(chain) {
            for ((index, link) in value.links.withIndex()) {
              // Validate the whole callable, not just its last segment (`foo..padding` must be
              // refused).
              val imported = qualifiedName(link.callableFqn, where) ?: return null
              val simple = link.callableFqn.substringAfterLast('.')
              // A member of the receiver's own type (`directive.copy(…)`); `memberLink` makes the
              // compiler hold the receiver to the guarded classifier. See `ChainLink.member`.
              if (link.member) {
                val written =
                  memberLink(
                    link,
                    value.receiver.takeIf { index == 0 },
                    toString(),
                    simple,
                    where,
                    depth,
                  ) ?: return null
                setLength(0)
                append(written)
                continue
              }
              // A member extension of the slot's receiver: not imported, and legal only where that
              // receiver is in scope (`Modifier.weight`). See `ChainLink.receiverScopeFqn`.
              val scope = link.receiverScopeFqn
              if (scope != null) {
                if (slotScope != scope) {
                  reasons +=
                    "$where links `$simple`, which is declared on `$scope` and is in scope only " +
                      "inside a slot with that receiver; this node sits " +
                      (slotScope?.let { "in a `$it` slot" } ?: "at the root, which has no receiver")
                  return null
                }
                append(".")
                append(ComponentSnippets.escapeIfKeyword(simple))
                if (link.property) {
                  if (link.positional.isNotEmpty() || link.named.isNotEmpty()) {
                    reasons += "$where reads `$simple` as a property and also passes it arguments"
                    return null
                  }
                } else {
                  val arguments =
                    arguments(link.positional, link.named, where, depth) ?: return null
                  append("(").append(arguments).append(")")
                }
                continue
              }
              // A chain link is imported by simple name, so it must be top-level; `RowScope.weight`
              // is a member extension and won't import. A capitalised penultimate segment is taken
              // as a classifier — a convention, so a legal oddity is refused rather than the common
              // case emitted broken.
              if (
                link.callableFqn
                  .substringBeforeLast('.')
                  .substringAfterLast('.')
                  .firstOrNull()
                  ?.isUpperCase() == true
              ) {
                reasons +=
                  "$where links `${link.callableFqn}`, whose qualifier names a classifier rather " +
                    "than a package — a member extension comes from an implicit receiver and " +
                    "cannot be imported"
                return null
              }
              if (simple in RESERVED_BY_THE_WRAPPER) {
                // A `kotlin` import would capture the qualifier `foldRepeats` writes.
                reasons +=
                  "$where imports `$simple`, which the generated file spends on its own scaffolding"
                return null
              }
              if (!link.property && simple == screenName) {
                // The generated function would shadow an import of the screen's own name.
                reasons += "$where imports `$simple`, which is the screen's own name"
                return null
              }
              extensionImports += imported
              extensionReceivers
                .getOrPut(imported) { mutableSetOf() }
                .add(value.receiver.typeFqn.takeIf { index == 0 })
              append(".")
              append(ComponentSnippets.escapeIfKeyword(simple))
              if (link.property) {
                if (link.positional.isNotEmpty() || link.named.isNotEmpty()) {
                  reasons += "$where reads `$simple` as a property and also passes it arguments"
                  return null
                }
              } else {
                val arguments = arguments(link.positional, link.named, where, depth) ?: return null
                append("(").append(arguments).append(")")
              }
            }
          }
          chain.toString()
        }
      }
    }

    /**
     * The chain so far, [written], with a [ChainLink.member] link applied, or null having said why.
     *
     * This generator cannot type the receiver, so a plain `written.name` would let a claimed owner
     * pass the package guard while resolving to some other type's member. Instead the receiver is
     * written where the compiler checks its type:
     * ```
     * val paneScaffoldDirective: PaneScaffoldDirective = calculatePaneScaffoldDirective(…)
     * SupportingPaneScaffold(…, value = …(paneScaffoldDirective.maxHorizontalPartitions))
     * ```
     * declared above the node (see [Block]), or inline as `written.let<Owner, _> { it.name }` where
     * no block exists. A generic `Owner` fails to compile, which is the safe direction.
     *
     * Also checked: the qualifier names a classifier, the link doesn't also claim a slot scope, and
     * a first link has an explicit value [receiver] (a bare `Modifier` is a companion, not an
     * instance).
     */
    private fun memberLink(
      link: ChainLink,
      receiver: ScreenValue?,
      written: String,
      simple: String,
      where: String,
      depth: Int,
    ): String? {
      val declaring = link.callableFqn.substringBeforeLast('.', missingDelimiterValue = "")
      if (declaring.substringAfterLast('.').firstOrNull()?.isUpperCase() != true) {
        reasons +=
          "$where links member `$simple`, whose qualifier `$declaring` does not name the " +
            "classifier that declares it"
        return null
      }
      if (link.receiverScopeFqn != null) {
        reasons +=
          "$where links `$simple` as both a member of `$declaring` and a member extension of " +
            "`${link.receiverScopeFqn}`"
        return null
      }
      if (
        receiver is ScreenValue.Reference &&
          receiver.members.isEmpty() &&
          receiver.rootFqn.substringAfterLast('.').firstOrNull()?.isUpperCase() == true
      ) {
        reasons +=
          "$where links member `$simple` of `$declaring` on `${receiver.rootFqn}`, which names a " +
            "type rather than a value; a member needs an explicit receiver expression"
        return null
      }
      val name = ComponentSnippets.escapeIfKeyword(simple)
      val owner = importedName(declaring, where) ?: return null
      if (link.property && (link.positional.isNotEmpty() || link.named.isNotEmpty())) {
        reasons += "$where reads `$simple` as a property and also passes it arguments"
        return null
      }
      val local = hoistedReceiver(owner, declaring, written)
      val member =
        if (link.property) name
        else "$name(${arguments(link.positional, link.named, where, depth) ?: return null})"
      return if (local != null) "$local.$member" else "$written.let<$owner, _> { it.$member }"
    }

    /** `a, b, name = c` for a call, or null having said why one of them could not be written. */
    private fun arguments(
      positional: List<ScreenValue>,
      named: Map<String, ScreenValue>,
      where: String,
      depth: Int,
    ): String? {
      val rendered = mutableListOf<String>()
      for (argument in positional) rendered += expression(argument, where, depth + 1) ?: return null
      for ((parameter, argument) in named) {
        val escaped = name(parameter, where) ?: return null
        rendered += "$escaped = ${expression(argument, where, depth + 1) ?: return null}"
      }
      return rendered.joinToString(", ")
    }

    /**
     * Accepts each `@RequiresOptIn` marker that can be written as a qualified name, refusing the
     * rest.
     *
     * Markers can now come from wire data via [ScreenValue], and `markerReference` only
     * keyword-escapes, so a backtick or newline could inject code. Only the shape is checked: a
     * marker is an inert type reference, so restricting which ones may be named would gain nothing.
     */
    private fun markers(names: List<String>, into: MutableSet<String>, where: String) {
      for (name in names) {
        if (isQualifiedName(name)) into += name
        else reasons += "$where needs opt-in marker `$name`, which is not a qualified Kotlin name"
      }
    }

    /**
     * A single name, backticked when it has to be, or null having said why it cannot be written.
     */
    private fun name(name: String, where: String): String? {
      if (!isWritableName(name)) {
        reasons += "$where names `$name`, which cannot be written as a Kotlin identifier"
        return null
      }
      return ComponentSnippets.escapeIfKeyword(name)
    }

    /**
     * A name imported and written the way a person writes it, or null having said why not.
     *
     * Importing risks shadowing by a declaration in the caller's package, but conflicting imports
     * are refused file-wide and a shadowed import fails loudly at the import line, so readability
     * wins.
     *
     * A top-level declaration imports itself (`Color`, `Modifier`); an object member imports its
     * owner, so `CardDefaults.cardColors(…)` keeps its qualifier. The two are told apart by the
     * qualifier's case, as for chain links.
     */
    private fun importedName(fqn: String, where: String): String? {
      qualifiedName(fqn, where) ?: return null
      val owner = fqn.substringBeforeLast('.')
      // A capitalised penultimate segment names a classifier, which is what imports.
      val memberOfClassifier = owner.substringAfterLast('.').firstOrNull()?.isUpperCase() == true
      val imported = if (memberOfClassifier) owner else fqn
      val simple = imported.substringAfterLast('.')
      if (simple in RESERVED_BY_THE_WRAPPER) {
        // Written qualified instead (not refused), so a folded run's `kotlin.` still means the
        // package.
        return qualifiedName(fqn, where)
      }
      if (simple == screenName || simple in allocatedNames) {
        // The generated function would shadow an import of its own name.
        reasons += "$where imports `$simple`, which is the screen's own name"
        return null
      }
      imports += ComponentSnippets.escapeCallableIfKeyword(imported)
      val written = ComponentSnippets.escapeIfKeyword(simple)
      return if (memberOfClassifier)
        "$written.${name(fqn.substringAfterLast('.'), where) ?: return null}"
      else written
    }

    /** A dotted path, each segment escaped, or null having said why. */
    private fun qualifiedName(fqn: String, where: String): String? {
      val segments = fqn.split('.')
      // A qualifier is required: a single segment names the default package, which a file in a
      // named package can't reference. Shape is checked before trust since it's the more actionable
      // error.
      if (!isQualifiedName(fqn)) {
        reasons += "$where refers to `$fqn`, which is not a qualified Kotlin name"
        return null
      }
      // SECURITY: the trust boundary. `Construct` emits a qualified call with document-supplied
      // arguments, so a spelling-only check would admit e.g.
      // `Files.readString(Path.of("/etc/passwd"))`, which a host rendering the screen would
      // execute. Callers declare allowed packages; the default is empty. The `.` keeps a prefix
      // from matching a longer sibling package.
      if (expressionPackages.none { fqn == it || fqn.startsWith("$it.") }) {
        reasons +=
          "$where names `$fqn`, which is outside the packages this screen may call " +
            "(${expressionPackages.sorted().joinToString(", ").ifEmpty { "none are allowed" }})"
        return null
      }
      return segments.joinToString(".") { ComponentSnippets.escapeIfKeyword(it) }
    }

    /** A string literal, or null when it is too long to be one. */
    private fun string(value: String, where: String): String? {
      // A JVM constant-pool string is limited to 65535 modified-UTF-8 bytes; larger values would
      // fail late in the backend.
      val length = modifiedUtf8Length(value)
      if (length > MAX_CONSTANT_POOL_STRING) {
        reasons +=
          "$where is $length bytes, past the $MAX_CONSTANT_POOL_STRING a JVM string constant " +
            "can hold"
        return null
      }
      return quote(value)
    }
  }

  /**
   * A slot's generated children, with runs of identical siblings written as one `repeat`.
   *
   * Builder documents have no loops, so a twelve-cell row is twelve nodes. Folding joins children
   * that generated byte-identical text, so it changes only the spelling; `repeat` is inline, so
   * composition is unchanged. Not applied to [SlotItem] slots, where child identity matters.
   *
   * Written `kotlin.repeat(n) { _ -> … }` so nothing in the body (state, imports accumulating
   * during emission) can capture `repeat` or an implicit `it`. Only the `kotlin` root needs
   * protecting. Runs shorter than [MINIMUM_FOLDED_RUN] aren't worth folding.
   */
  private fun foldRepeats(children: List<String>, indent: String): String {
    val out = StringBuilder()
    var index = 0
    while (index < children.size) {
      var end = index + 1
      while (end < children.size && children[end] == children[index]) end++
      if (out.isNotEmpty()) out.append("\n")
      // Whichever fold covers more siblings, the `repeat` on a tie: when values differ, a list
      // shows them.
      val varying = varyingRun(children, index)?.takeIf { it.end > end }
      if (varying == null && end - index >= MINIMUM_FOLDED_RUN) {
        val run = end - index
        out
          .append(indent)
          .append("kotlin.repeat(")
          .append(run)
          .append(") { _ ->\n")
          .append(children[index].prependIndent(INDENT))
          .append("\n")
          .append(indent)
          .append("}")
      } else {
        if (varying == null) {
          out.append(children[index])
          end = index + 1
        } else {
          out
            .append(indent)
            .append("for (")
            .append(varying.parameter)
            .append(" in kotlin.collections.listOf(")
            .append(varying.values.joinToString(", "))
            .append(")) {\n")
            .append((varying.prefix + varying.parameter + varying.suffix).prependIndent(INDENT))
            .append("\n")
            .append(indent)
            .append("}")
          end = varying.end
        }
      }
      index = end
    }
    return out.toString()
  }

  /**
   * A run of siblings that generated the same text but for one literal, or null.
   *
   * Folds e.g. differently-coloured cells into:
   * ```
   * kotlin.collections.listOf(0xFFEBEDF0, 0xFF9BE9A8, 0xFF40C463).forEach { value ->
   *     Surface(color = Color(value), …)
   * }
   * ```
   * Sound because substituting the parameter rebuilds each child's text exactly. Requirements: the
   * varying piece sits between delimiters (never inside a token); all pieces are literals of one
   * kind and, for numbers, the same textual length so `listOf` infers the same type; and they
   * differ (else it's an identical run). The parameter name is one the body doesn't already
   * contain.
   */
  private fun varyingRun(children: List<String>, start: Int): VaryingRun? {
    // Grown once, then shrunk a little — never searched. The window extends while children share
    // some prefix/suffix with the first (a running minimum, one walk per child); only the final
    // window and [FOLD_SHRINK_ATTEMPTS] shorter ones are validated. A full search would be
    // quadratic per start.
    val first = children[start]
    var prefix = first.length
    var suffix = first.length
    // From the second child: the first shares all of itself with itself.
    var end = start + 1
    val limit = minOf(children.size, start + FOLD_MAXIMUM_WINDOW)
    while (end < limit) {
      val text = children[end]
      // Prefix and suffix are independent running minima; their overlap is settled by
      // [varyingFold].
      prefix = commonPrefixLength(first, text, prefix)
      suffix = commonSuffixLength(first, text, suffix)
      if (prefix == 0 || suffix == 0) break
      end++
    }
    var attempts = FOLD_SHRINK_ATTEMPTS
    while (end - start >= MINIMUM_FOLDED_RUN && attempts-- > 0) {
      varyingFold(children.subList(start, end), end)?.let {
        return it
      }
      end--
    }
    return null
  }

  /** [varyingRun]'s question asked of one window, or null when this window cannot be folded. */
  private fun varyingFold(texts: List<String>, end: Int): VaryingRun? {
    val first = texts.first()
    var prefix =
      texts.fold(first.length) { length, text -> commonPrefixLength(first, text, length) }
    var suffix =
      texts.fold(first.length) { length, text ->
        commonSuffixLength(first, text, minOf(length, text.length - prefix))
      }
    if (prefix + suffix >= first.length) return null
    while (prefix > 0 && first[prefix - 1] !in FOLD_OPENING_DELIMITERS) prefix--
    if (prefix == 0) return null
    while (suffix > 0 && first[first.length - suffix] !in FOLD_CLOSING_DELIMITERS) suffix--
    if (suffix == 0) return null

    val values = texts.map { it.substring(prefix, it.length - suffix) }
    if (values.distinct().size < 2) return null
    if (values.any { it.isEmpty() || '\n' in it }) return null
    if (!values.all(::isFoldableStringLiteral) && !values.all(::isFoldableBooleanLiteral)) {
      // Same kind and same length: length alone admits `1000` and `1.0f` together; kind alone
      // admits whole numbers either side of `Int.MAX_VALUE`.
      val kinds = values.map(::foldableNumberKind)
      if (kinds.any { it == null } || kinds.distinct().size != 1) return null
      if (values.any { it.length != values.first().length }) return null
    }

    val body = first.substring(0, prefix) + first.substring(first.length - suffix)
    val parameter =
      FOLD_PARAMETER_NAMES.firstOrNull { candidate ->
        !Regex("\\b${Regex.escape(candidate)}\\b").containsMatchIn(body) &&
          values.none { Regex("\\b${Regex.escape(candidate)}\\b").containsMatchIn(it) }
      } ?: return null
    return VaryingRun(
      end = end,
      values = values,
      prefix = first.substring(0, prefix),
      suffix = first.substring(first.length - suffix),
      parameter = parameter,
    )
  }

  private fun commonPrefixLength(first: String, other: String, limit: Int): Int {
    var length = minOf(limit, other.length)
    var index = 0
    while (index < length && first[index] == other[index]) index++
    return index
  }

  private fun commonSuffixLength(first: String, other: String, limit: Int): Int {
    val bound = minOf(limit, other.length)
    var index = 0
    while (index < bound && first[first.length - 1 - index] == other[other.length - 1 - index]) {
      index++
    }
    return index
  }

  private fun isFoldableStringLiteral(value: String): Boolean =
    value.length >= 2 && value.startsWith('"') && value.endsWith('"') && FOLD_STRING.matches(value)

  private fun isFoldableBooleanLiteral(value: String): Boolean = value == "true" || value == "false"

  /**
   * Which sort of number [value] is (radix, fractional, suffix — what decides Kotlin's inferred
   * type), or null for what is not one.
   */
  private fun foldableNumberKind(value: String): String? {
    FOLD_HEX_NUMBER.matchEntire(value)?.let {
      // A hex literal's type depends on magnitude, so it is foldable only with an explicit suffix.
      return if (it.groupValues[2].isEmpty()) null else "hex:${it.groupValues[2]}"
    }
    val decimal = FOLD_DECIMAL_NUMBER.matchEntire(value) ?: return null
    val fractional = decimal.groupValues[2].isNotEmpty()
    val suffix = decimal.groupValues[3]
    // An unsuffixed whole number may have been widened to `Long` at its call site; lifting it into
    // a list would change its type. Bare decimal fractions are `Double` everywhere.
    if (!fractional && suffix.isEmpty()) return null
    return "${if (fractional) "fractional" else "whole"}:$suffix"
  }

  /** One run of siblings differing in a single literal — see [varyingRun]. */
  private data class VaryingRun(
    val end: Int,
    val values: List<String>,
    val prefix: String,
    val suffix: String,
    val parameter: String,
  )

  /**
   * A `Long` literal. `-9223372036854775808L` doesn't compile (the positive token is range-checked
   * before unary minus). Qualified because the caller's package could declare its own `Long`.
   */
  private fun long(value: Long): String =
    if (value == Long.MIN_VALUE) "kotlin.Long.MIN_VALUE" else "${value}L"

  /** A Kotlin string literal for [value]; `$` is escaped so typed text never becomes a template. */
  private fun quote(value: String): String =
    value
      .replace("\\", "\\\\")
      .replace("\"", "\\\"")
      .replace("$", "\\$")
      .replace("\n", "\\n")
      .replace("\r", "\\r")
      .replace("\t", "\\t")
      .let { "\"$it\"" }

  /**
   * Whether [name] can be a bare declaration name: an identifier, not a hard keyword, and not
   * all-underscore (Kotlin reserves `_`, `__`, …).
   */
  private fun isUsableIdentifier(name: String): Boolean =
    ComponentSnippets.isIdentifier(name) &&
      !ComponentSnippets.isHardKeyword(name) &&
      name.any { it != '_' }

  /**
   * The local a hoisted initializer is bound to: derived from the state's name and bumped until it
   * collides with nothing the file already writes.
   */
  private fun initialBinding(name: String, taken: Set<String>): String {
    var candidate = "${name}Initial"
    while (candidate in taken) candidate += "_"
    return ComponentSnippets.escapeIfKeyword(candidate)
  }

  /**
   * Whether [fqn] is a dotted path of writable names with a qualifier. Shared by the callable and
   * marker checks so they cannot drift.
   */
  private fun isQualifiedName(fqn: String): Boolean {
    val segments = fqn.split('.')
    return fqn.isNotEmpty() && segments.size >= 2 && segments.all(::isWritableName)
  }

  /**
   * Whether [name] can be referred to, bare or backticked. Wider than [isUsableIdentifier]:
   * backticks admit almost anything except empty names and characters that close the quoting.
   */
  private fun isWritableName(name: String): Boolean =
    name.isNotEmpty() &&
      // Reserved even in backticks: `receiver._` doesn't compile.
      name.any { it != '_' } &&
      name.none { it in FORBIDDEN_IN_A_NAME }

  private val FORBIDDEN_IN_A_NAME = ".;:\\/[]<>`\n\r".toSet()

  /**
   * An opt-in marker, spelled for source. The producer already rebuilt nested names into source
   * notation, so only keyword escaping is left.
   */
  private fun markerReference(marker: String): String =
    ComponentSnippets.escapeCallableIfKeyword(marker)

  private const val COLOR = "androidx.compose.ui.graphics.Color"

  /** `1f` for a whole float and `0.5f` otherwise, the spelling `weight(1f)` is written in. */
  private fun floatLiteral(value: Float): String =
    if (
      value == value.toInt().toFloat() &&
        kotlin.math.abs(value) < 1_000_000f &&
        !value.isNegativeZero()
    )
      "${value.toInt()}f"
    else "${value}f"

  private fun Float.isNegativeZero(): Boolean = this == 0f && 1f / this < 0f

  /**
   * The bytes [value] occupies as a JVM constant-pool string: modified UTF-8, where `NUL` is two
   * bytes and a supplementary character six.
   */
  private fun modifiedUtf8Length(value: String): Int = value.sumOf { c ->
    when {
      c.code in 1..0x7F -> 1
      c.code <= 0x7FF -> 2
      else -> 3
    }
  }

  private const val MAX_CONSTANT_POOL_STRING = 65535

  /** How deeply one argument's value may nest; far past real use, far short of a stack overflow. */
  private const val MAX_VALUE_DEPTH = 16

  /**
   * Simple names the generated file already spends on its own scaffolding.
   *
   * `Composable` is always imported. `kotlin` is spent by [foldRepeats]'s `kotlin.repeat`, so
   * nothing is imported under it: components and value references are written qualified, while
   * chain links and constructed placeholders (which need their import) are refused. A state named
   * `kotlin` turns folding off instead.
   */
  private val RESERVED_BY_THE_WRAPPER = setOf("Composable", "kotlin")

  /** The tooling annotation a [Preview] emits, imported only when one is asked for. */
  private const val PREVIEW_ANNOTATION = "androidx.compose.ui.tooling.preview.Preview"

  private const val PREVIEW_SIMPLE_NAME = "Preview"

  /**
   * The platform's reference-size multipreview. Named rather than expanded so the device set stays
   * androidx's to maintain.
   */
  private const val PREVIEW_SCREEN_SIZES_ANNOTATION =
    "androidx.compose.ui.tooling.preview.PreviewScreenSizes"

  private const val PREVIEW_SCREEN_SIZES_SIMPLE_NAME = "PreviewScreenSizes"

  /** Names the device fan-out's wrapper, e.g. `HomeScreenDevicesPreview`. */
  private const val DEVICES_SIMPLE_NAME = "Devices"

  /** The wrapper's name, needed before it is emitted so a component cannot be shadowed by it. */
  private fun previewFunctionName(screenName: String) = "$screenName$PREVIEW_SIMPLE_NAME"

  private fun screenSizesPreviewFunctionName(screenName: String) =
    "$screenName$PREVIEW_SCREEN_SIZES_SIMPLE_NAME$PREVIEW_SIMPLE_NAME"

  private fun devicesPreviewFunctionName(screenName: String) =
    "$screenName$DEVICES_SIMPLE_NAME$PREVIEW_SIMPLE_NAME"

  /**
   * A language tag this generator will put inside a string literal: letters, digits and separators,
   * so a quote or newline can't close the literal. A wrong locale is refused rather than escaped.
   */
  private val LANGUAGE_TAG = Regex("[A-Za-z0-9]+(?:[-_][A-Za-z0-9]+)*")

  /**
   * A device specifier (`id:pixel_6` or `spec:…`) this generator will put inside a string literal.
   * A shape check, not a catalog — which ids exist is the tooling's question.
   */
  private val DEVICE_SPEC = Regex("[A-Za-z0-9_.:=,%/+-]+")

  /** Every problem with [preview], so a caller fixes them in one pass rather than one per run. */
  private fun previewRefusals(preview: Preview): List<String> = buildList {
    preview.widthDp?.let { if (it <= 0) add("preview widthDp must be positive, not $it") }
    preview.heightDp?.let { if (it <= 0) add("preview heightDp must be positive, not $it") }
    preview.fontScale?.let {
      // `!(it > 0)` also catches NaN, which would otherwise emit `fontScale = NaNf`.
      if (!(it > 0.0) || it.isInfinite())
        add("preview fontScale must be finite and positive, not $it")
    }
    preview.locale?.let {
      if (!LANGUAGE_TAG.matches(it)) add("preview locale `$it` is not a language tag")
    }
    preview.devices.forEach {
      if (!DEVICE_SPEC.matches(it)) add("preview device `$it` is not a device specifier")
    }
  }

  /**
   * The `@Preview` wrapper: a private, zero-argument composable that calls the screen, so the
   * screen itself stays a plain callable without preview constraints.
   */
  private fun previewFunction(screenName: String, preview: Preview): String {
    val arguments = buildList {
      preview.widthDp?.let { add("widthDp = $it") }
      preview.heightDp?.let { add("heightDp = $it") }
      // `Float` literal: `@Preview.fontScale` is a Float and an unsuffixed decimal is a Double.
      preview.fontScale?.let { add("fontScale = ${it}f") }
      preview.locale?.let { add("locale = \"$it\"") }
      if (preview.showBackground) add("showBackground = true")
      if (preview.darkMode) {
        add("uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES")
      }
    }
    return buildString {
      if (arguments.isEmpty()) {
        appendLine("@$PREVIEW_SIMPLE_NAME")
      } else {
        // One argument per line: ktfmt is not run over what this emits.
        appendLine("@$PREVIEW_SIMPLE_NAME(")
        arguments.forEach { appendLine("$INDENT$it,") }
        appendLine(")")
      }
      appendLine("@Composable")
      appendLine("private fun ${previewFunctionName(screenName)}() {")
      appendLine("$INDENT$screenName()")
      appendLine("}")
    }
  }

  /**
   * The device fan-out: one stacked `@Preview(device = …)` per id the design named, on one wrapper.
   * Carries none of the design's frame, which would override what is being varied; `name` is the
   * id.
   */
  private fun devicesPreviewFunction(screenName: String, devices: List<String>): String =
    buildString {
      devices.forEach {
        appendLine("@$PREVIEW_SIMPLE_NAME(")
        appendLine("${INDENT}name = \"$it\",")
        appendLine("${INDENT}device = \"$it\",")
        appendLine(")")
      }
      appendLine("@Composable")
      appendLine("private fun ${devicesPreviewFunctionName(screenName)}() {")
      appendLine("$INDENT$screenName()")
      appendLine("}")
    }

  /**
   * The `@PreviewScreenSizes` wrapper: the same screen at every reference size. Carries none of the
   * design's environment, which would override the varied dimensions or apply to every device.
   */
  private fun screenSizesPreviewFunction(screenName: String): String = buildString {
    appendLine("@$PREVIEW_SCREEN_SIZES_SIMPLE_NAME")
    appendLine("@Composable")
    appendLine("private fun ${screenSizesPreviewFunctionName(screenName)}() {")
    appendLine("$INDENT$screenName()")
    appendLine("}")
  }
}
