package ee.schimke.composeai.discovery

/**
 * Generates a compilable `@Composable` screen from a [ScreenDocument] and the components a build
 * discovered.
 *
 * ## What this adds over [ComponentSnippets]
 *
 * [ComponentSnippets] prints one component's call site with **placeholders** — `Text(text = "")` —
 * which proves a component is reachable but renders nothing anyone designed. This binds the values
 * a builder actually set, and nests components into each other's slots, so the output is the screen
 * rather than a specimen of its parts.
 *
 * ## What it inherits, and why that matters
 *
 * A node is generated only when its record carries an emitted [ComponentCode]. That single check
 * carries every protection the call-site generator learned the hard way: the component is public,
 * has no uninferable type parameters, did not collide with an overload, is a top-level function
 * with an importable callable, and has a signature that was actually read rather than defaulted
 * away. None of that is re-derived here — a second implementation of those rules is how two halves
 * of a contract start disagreeing.
 *
 * What is *not* inherited is the argument list: `code.call` fills required parameters with
 * placeholders, and this replaces them with the document's values. So the emitted call is built
 * here from [ComponentRecord.parameters], with `code.call` used as the licence to call at all.
 *
 * ## Where that licence is wider than it should be
 *
 * `code.call != null` is treated as proof that nothing stops a caller writing this call. That is
 * not quite true, and the gap is one problem rather than a list of them: **the record cannot
 * express every source-level restriction on calling a declaration**, so each one found has to be
 * recorded as its own field, and one not yet found is silently admitted. Two are known and open:
 *
 * - `@Deprecated(level = DeprecationLevel.ERROR)`. A preview can call such a component under
 *   `@Suppress("DEPRECATION_ERROR")`, which persists a call site the generated file — carrying no
 *   such suppression — cannot compile.
 * - A context *parameter* (Kotlin 2.2 onwards). `ComposableSignature.hasContextRequirement` reads
 *   the older `contextReceiverTypes` only, and says there why it cannot read the other.
 *
 * Both are admitted today. Closing them properly means either an explicit closed list of what
 * `code.call` promises, or compiling a candidate call in the producer — not a boolean per
 * restriction, which is how this list would keep growing.
 *
 * A third restriction belongs to [ScreenValue.Chain] rather than to `code.call`: an imported
 * extension **loses to a member of the same simple name on the receiver**. A link naming
 * `com.example.pad` is emitted as `.pad()`, and a receiver that declares its own `pad` gets the
 * call instead — a different expression from the one the document asked for, emitted as though it
 * were the right one. The only mechanism that forces the link's own callable is a *renaming* import
 * alias (`import com.example.pad as generatedPad`, called as `.generatedPad()`); an alias to the
 * same name does not help, since resolution keys off the name at the call site. That is declined
 * for now, deliberately: it renames every link in every generated file — `Modifier
 * .generatedFillMaxWidth()` — to close a case that needs a link named after a member of `Any`,
 * `Number` or `Modifier.Companion`, and a probe over material3 1.11 found no such collision. The
 * trade is readability against a narrow hazard, and it is recorded here so it is a decision rather
 * than an oversight.
 *
 * ## The one thing a caller must decide
 *
 * `expressionPackages` is not a convenience. A [ScreenDocument] is wire data, and
 * [ScreenValue.Construct] emits a qualified call with document-supplied arguments — so without a
 * declared vocabulary this object would happily generate
 * `java.nio.file.Files.readString(java.nio.file.Path.of("/etc/passwd"))` for a `String` parameter,
 * and a host that compiles and renders what it generated would run it. The set is empty by default,
 * so a caller that has not thought about it gets refusals rather than arbitrary code.
 *
 * A third gap arrived with the widened value vocabulary and is a different animal:
 * [ScreenValue.Reference], [ScreenValue.Construct] and [ScreenValue.Chain] carry a **claimed**
 * type. It is checked against the parameter, so a colour handed to a `String` is still refused, but
 * the claim itself is taken on trust. [ScreenValue] says why that trade was worth making and where
 * the failure lands when a projection gets it wrong.
 *
 * ## Refusing, again
 *
 * The discipline is the same one that makes the call-site generator worth anything: emit only what
 * can be proven, and say why otherwise. A builder pinned to a catalog it no longer has, a property
 * the component never declared, a string handed to a `Boolean` — each is a refusal naming the node,
 * because a screen that compiles and is not the one designed is worse than an error message.
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
   * Opt-in, and deliberately so. `@Preview` lives in `androidx.compose.ui.tooling.preview`, which
   * is an Android tooling dependency a consumer of this generator need not have on its compile
   * classpath — the Gradle plugin generates screens into builds that do not. Emitting it always
   * would trade "the file compiles" for "the file previews", which is the wrong way round for the
   * caller that only wanted source.
   *
   * The values are the design's own environment rather than `@Preview` defaults, because a design
   * authored at 411x914 in dark at a 1.3 font scale and previewed at Android Studio's defaults is a
   * different picture from the one its author approved — and the whole point of pasting the export
   * into an IDE is to see that picture.
   *
   * [locale] is wire data. It reaches the emitted file inside a string literal, so it is validated
   * against a language-tag shape rather than escaped: a value that is not one is a projection bug
   * worth a refusal, not something to quietly pass through into source this generator signs.
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
     * Whether the design is dark, emitted as `uiMode = …UI_MODE_NIGHT_YES`.
     *
     * Fully qualified through `android.content.res.Configuration` rather than imported: the
     * constant is Android-only and this keeps it out of the import list, where it would collide
     * with nothing today but would still be a name the file spends for one integer.
     */
    val darkMode: Boolean = false,
    /**
     * Paints the preview's background rather than compositing on transparency.
     *
     * True by default because a transparent preview of a screen designed against a surface reads as
     * a rendering fault to the person who pasted it.
     */
    val showBackground: Boolean = true,
  )

  private const val INDENT = "    "

  fun generate(
    document: ScreenDocument,
    components: ComponentRecordFile,
    packageName: String = "generated.screen",
    expressionPackages: Set<String> = emptySet(),
    preview: Preview? = null,
  ): Result {
    if (components.schemaVersion > COMPONENT_RECORD_SCHEMA_VERSION) {
      // A record from a newer producer may mean things by fields this build has never seen.
      // Reading it as the current schema is exactly the guess the version exists to prevent.
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
    // **Schema 1 is refused outright**, rather than read with a growing list of exceptions.
    //
    // The first attempt kept reading it and refused only the one thing it could name — markers
    // whose opt-in mechanism it could not classify. That was too clever twice over: it scanned the
    // whole catalog, so one gated component nobody placed refused a screen built entirely from
    // stable ones; and it protected only the field it happened to be about, while a schema-1 record
    // also cannot say whether a component needs a context receiver, so those still slipped through
    // with a persisted `code.call` and produced a call the compiler rejects.
    //
    // Both are the same shape: this generator's guarantee rests on fields schema 1 does not have,
    // and every one of them would need its own exception here. One rule instead of a table of them
    // — a producer emits schema 2, and a catalog older than that is regenerated rather than
    // squinted at.
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
    // Two components can share a simple name (`com.a.Badge`, `com.b.Badge`), and a screen can share
    // one with a component it calls — `fun HomeScreen()` calling a `HomeScreen` component would
    // shadow the import and recurse into itself. Neither is exotic once a catalog spans libraries.
    // A simple name is only used when exactly one component wants it and the screen does not; the
    // rest are called fully qualified, which is always unambiguous and needs no import.
    //
    // Nesting is deliberately *not* a third reason. This once also qualified any component sitting
    // in a slot with a receiver — `Column(content = ColumnScope.() -> Unit)` and everything under
    // it — on the premise that an import would not reach inside one. That premise is false, and
    // the whole Compose ecosystem is the counterexample: `import …material3.Text` then `Column {
    // Text("hi") }` is what every hand-written file does, and an implicit receiver adds names to
    // the scope rather than removing the imported one from it. Because the flag was sticky, one
    // scoped slot near the root qualified every descendant, so a realistic screen was fully
    // qualified throughout — the shape a builder's code pane shows its user.
    //
    // What the premise was groping for is real but much narrower: a *member* of the receiver with
    // the same simple name does win over an import. That is the same hazard a hand-written file
    // carries, this generator has no view of a receiver's members to reason about it, and being
    // more paranoid than the language bought unreadable output rather than safety.
    // `ScreenGeneratorCompileFunctionalTest` compiles a screen nested through `Card` and `Button`
    // against real Material 3, which is what says the imports resolve.
    val claimants = components.components.groupBy { it.symbol.name }
    val simplyImportable =
      components.components
        .filter {
          claimants.getValue(it.symbol.name).size == 1 &&
            it.symbol.name != document.name &&
            it.symbol.name !in RESERVED_BY_THE_WRAPPER &&
            // Both only when a preview is emitted, because only then does the file spend these
            // names; reserving them always would needlessly qualify a component in every other
            // screen. `Preview` is the annotation's own simple name, and the wrapper is a
            // top-level declaration that would *win* over an import of the same name — so a
            // component called `HomeScreenPreview` in a `HomeScreen` would silently become a call
            // to the wrapper, which calls the screen, which renders it: a stack overflow standing
            // in for the component somebody placed.
            (preview == null ||
              (it.symbol.name != PREVIEW_SIMPLE_NAME &&
                it.symbol.name != previewFunctionName(document.name)))
        }
        .map { it.canonicalId }
        .toSet()
    // Declarations are checked before anything reads them, so a misspelled variable is reported
    // once against the declaration rather than once per node that names it.
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
    // The same shadowing one level up. A state name is a local `val` in the composable body, so it
    // also shadows any package *root* the generated source writes out in full: the preamble emits
    // `androidx.compose.runtime.remember` for every declaration, a component that cannot claim a
    // simple name is called by its qualified callable, an allowed expression is written qualified,
    // and each declared type is interpolated into `mutableStateOf<…>`. A state named `androidx`
    // compiles on its own line — a local is not in scope in its own initializer — and breaks the
    // next one, which is the worst place for this to surface.
    val qualifiedRoots = buildSet {
      add("androidx")
      // Every component, not only the ones that cannot claim a simple name: which of the two a
      // node gets is decided per record, and a state name may not shadow the root of either.
      components.components.mapTo(this) { it.symbol.callable.substringBefore('.') }
      expressionPackages.mapTo(this) { it.substringBefore('.') }
      document.state.mapTo(this) { it.typeFqn.substringBefore('.') }
    }
    val shadowedRoots =
      document.state.map(ScreenState::name).filter { it in qualifiedRoots }.distinct()
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
      )
    // Everything a hoisted binding must not shadow: the declarations, the components this file
    // calls by simple name, and the screen's own function. A `val FooInitial` sitting above a
    // `FooInitial(...)` call captures it exactly the way a state name would.
    val bindingNamesTaken =
      document.state.map(ScreenState::name).toSet() +
        components.components.filter { it.canonicalId in simplyImportable }.map { it.symbol.name } +
        document.name +
        // And the package roots, for the same reason a state name may not be one: a component that
        // cannot claim a simple name is called fully qualified, and `val tintInitial = …` above a
        // `tintInitial.widgets.Text(...)` captures that root exactly as a declaration would.
        qualifiedRoots
    val declaredSoFar = mutableSetOf<String>()
    val preamble =
      document.state.map { declared ->
        // Each initializer sees only what precedes it, and the name itself is added after the
        // initializer is rendered rather than before, because a local is not in scope in its own.
        context.initializerScope = declaredSoFar.toSet()
        // The declared type is interpolated into `mutableStateOf<…>`, so it is source, and
        // `ScreenDocument` is wire data. Every other name this file writes goes through a shape
        // check first; this one did not, so a malformed type produced source that does not compile
        // and a crafted one could close the call and splice statements into the composable.
        if (!isQualifiedName(declared.typeFqn)) {
          context.reasons +=
            "state `${declared.name}` is declared as `${declared.typeFqn}`, which is not a " +
              "qualified Kotlin name"
          return@map null
        }
        // Rendered against the declared type, the same way an assignment to this variable is.
        // The untyped path emits a literal on its own terms, so `kotlin.Float` seeded with `0.5`
        // produced `mutableStateOf<kotlin.Float>(0.5)` — a Double literal — and a literal of the
        // wrong kind entirely was emitted rather than refused. Nullability is stripped for the
        // comparison because every literal this vocabulary has is non-null.
        val initial =
          context.argument(
            declared.initial,
            TargetParameter(
              declared.name,
              declared.typeFqn.removeSuffix("?"),
              typeFqn = declared.typeFqn.removeSuffix("?"),
            ),
            "state",
          ) ?: return@map null
        declaredSoFar += declared.name
        val name = ComponentSnippets.escapeIfKeyword(declared.name)
        // Nullability is syntax, not part of any segment's name. Escaping the whole spelling
        // turned the documented `kotlin.String?` into `kotlin.`String?`` — a backticked classifier
        // rather than a nullable String — so every nullable state stopped compiling.
        val nullableType = declared.typeFqn.endsWith("?")
        val type =
          ComponentSnippets.escapeCallableIfKeyword(declared.typeFqn.removeSuffix("?")) +
            if (nullableType) "?" else ""
        // `remember`'s calculation is `@DisallowComposableCalls`, and this vocabulary can name a
        // composable read — `MaterialTheme.colorScheme.primary` is the documented example. Kotlin
        // rejects that inside the lambda even though the same expression is legal one line up, so
        // anything naming an API is bound first and the lambda closes over the binding.
        //
        // A literal and a state read stay where they are: neither can be a composable call — one
        // is a constant, the other reads a local `MutableState` — and hoisting every `""` would
        // double an ordinary preamble to guard against nothing.
        val hoisted =
          declared.initial is ScreenValue.Reference ||
            declared.initial is ScreenValue.Construct ||
            declared.initial is ScreenValue.Chain
        // `remember` so the value survives recomposition — without it the screen resets on every
        // frame that touches it, which looks like the state never changing at all.
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
    if (context.reasons.isNotEmpty()) return Result.Refused(context.reasons.toList())
    val declarations = preamble.filterNotNull().flatten()

    // An AndroidX-mechanism marker is reported by both scans, so it is subtracted here rather than
    // written twice under two annotations that would each reject the other's markers.
    val androidxOptIns = context.androidxOptIns.distinct().sorted()
    val optIns = (context.optIns - context.androidxOptIns).distinct().sorted()
    val imports =
      (context.imports +
          context.extensionImports +
          "androidx.compose.runtime.Composable" +
          listOfNotNull(PREVIEW_ANNOTATION.takeIf { preview != null }))
        .distinct()
        .sorted()
    // Kotlin calls two imports of one simple name a conflicting import and compiles neither. The
    // component half of this can't collide — `simplyImportable` already withholds a simple name two
    // records want — but an extension link is imported from wherever a projection said, so
    // `foundation.layout.padding` and `some.other.padding` in one screen have to be caught here.
    val conflicts =
      imports
        .groupBy { it.substringAfterLast('.') }
        .filterValues { it.size > 1 }
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
    val source = buildString {
      appendLine("package $packageName")
      appendLine()
      imports.forEach { appendLine("import $it") }
      appendLine()
      if (optIns.isNotEmpty()) {
        appendLine(
          // Both halves qualified. The markers because two can share a simple name from different
          // packages, and `@OptIn(ExperimentalApi::class, ExperimentalApi::class)` is ambiguous
          // rather than merely ugly; the annotation itself because the generated file sits in a
          // package the caller chose, and a package declaring its own `OptIn` would capture the
          // bare name — the AndroidX branch below was already written qualified.
          optIns.joinToString(", ", "@kotlin.OptIn(", ")") { "${markerReference(it)}::class" }
        )
      }
      if (androidxOptIns.isNotEmpty()) {
        // A different annotation, not a stylistic variant: `kotlin.OptIn` rejects a marker declared
        // with `androidx.annotation.RequiresOptIn` ("this class is not an opt-in requirement
        // marker"), and the AndroidX one takes an array under a named `markerClass`.
        appendLine(
          androidxOptIns.joinToString(
            ", ",
            "@androidx.annotation.OptIn(markerClass = [",
            "])",
          ) {
            "${markerReference(it)}::class"
          }
        )
      }
      appendLine("@Composable")
      appendLine("fun ${document.name}() {")
      declarations.forEach { appendLine("    $it") }
      appendLine(body)
      appendLine("}")
      if (preview != null) {
        appendLine()
        append(previewFunction(document.name, preview))
      }
    }
    return Result.Emitted(source = source, requiredOptIns = optIns + androidxOptIns)
  }

  /**
   * Resolves a document's component id, by canonical key or by catalog alias.
   *
   * Two indexes rather than one flat map, because they are not equally authoritative:
   * [ComponentRecord.canonicalId] is the file's key and an alias is a label several records may
   * carry. Merging them would let an alias on one record mask another record's key — a silent
   * substitution, which is the one outcome worth more care than either lookup.
   */
  private class ComponentIndex(records: List<ComponentRecord>) {
    private val byCanonical = records.groupBy { it.canonicalId }
    // `distinct()` inside the record, never across records. A record listing one alias twice says
    // nothing twice; two *records* claiming one alias is the ambiguity this refuses, and they can
    // share a canonical id — so collapsing by canonical id here would let an alias resolve to
    // whichever of the two came first in the file, while `resolve` refuses the same pair when
    // asked by canonical id. Catalog-order-dependent, and silently so.
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
    /** Declared state by name, so a read can be checked against something rather than trusted. */
    val state: Map<String, ScreenState> = emptyMap(),
  ) {
    val imports = mutableSetOf<String>()
    /**
     * Kept apart from [imports] only so the conflict message can say an *extension* collided. They
     * are unioned before anything is written.
     */
    val extensionImports = mutableSetOf<String>()
    val optIns = mutableSetOf<String>()
    val androidxOptIns = mutableSetOf<String>()
    val reasons = mutableListOf<String>()

    /**
     * The state names in scope while one declaration's own initializer is rendered, or null in the
     * body, where every declaration is.
     *
     * The preamble emits one `val` per declaration in document order and a local is not in scope in
     * its own initializer, so an initializer may read only what came before it. Without this a
     * document could put `first`'s initializer on `second.value` and generate a file that names a
     * variable two lines before declaring it.
     */
    var initializerScope: Set<String>? = null

    fun node(node: ScreenNode, depth: Int): String {
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
        // Keep walking its children: a catalog that dropped a whole subtree should name every node
        // it can no longer place, not just the outermost one. `reasons` is what a caller acts on,
        // and the text returned here is discarded the moment anything has failed.
        node.slots.values.flatten().forEach { node(it, depth + 1) }
        return "$pad// unresolved: ${node.componentId}"
      }
      // The licence to call at all. Everything a refusal protects against — private, generic,
      // collided, unreadable, not importable — is already decided here, once, by the producer.
      val code = record.code
      if (code?.call == null) {
        reasons +=
          "`${node.componentId}` has no call site: ${code?.refusedReason ?: "no code was recorded"}"
        node.slots.values.flatten().forEach { node(it, depth + 1) }
        return "$pad// unusable: ${node.componentId}"
      }
      val qualified = ComponentSnippets.escapeCallableIfKeyword(record.symbol.callable)
      if (record.canonicalId in simplyImportable) imports += qualified
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
            !parameter.composableSlot ->
              "`${record.symbol.name}`.`$slot` is a parameter, not a @Composable slot"
            else -> null
          }
        if (rejected != null) {
          reasons += rejected
          // The loop below walks `record.parameters`, so a slot the component never declared is
          // never reached and its subtree would go unreported — the same gap as an unresolved
          // node's children, one level in. A renamed slot is exactly when a document is most
          // likely to be stale further down, so those children are the ones worth naming.
          children.forEach { node(it, depth + 1) }
        }
      }

      val arguments = mutableListOf<String>()
      // A handler naming a parameter the component does not declare is refused here rather than
      // silently dropped, exactly as an unknown argument is: a screen whose button does nothing is
      // not the screen that was designed, and it compiles perfectly.
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
            // A conflicted document can set both an argument and a slot for one parameter. The
            // scalar loses (a literal cannot be a function type, so `argument` refuses it), but the
            // slot's children would never be visited otherwise — the fourth branch that rejects a
            // node and would drop its subtree.
            //
            // Only when the slot loop above did not already walk them. It walks the children of
            // any slot it rejected, so for a parameter that is not a composable slot both paths
            // fire, every reason below is duplicated, and a document conflicted at each level
            // doubles the work per level.
            if (children != null) {
              reasons +=
                "`${record.symbol.name}`.`${parameter.name}` is set as both a value and a slot"
              if (parameter.composableSlot) {
                children.forEach { node(it, depth + 1) }
              }
            }
          }
          children != null &&
            parameter.composableSlot &&
            !ComponentSnippets.acceptsBareLambda(parameter.type) -> {
            // `code.call` may have been emittable only because this slot was defaulted away. A
            // `(Int, Int) -> Unit` or `() -> String` slot cannot be satisfied by `{ children }`.
            reasons +=
              "`${record.symbol.name}`.`${parameter.name}` is `${parameter.type}`, which children " +
                "in a bare lambda cannot satisfy"
            // The third branch that rejects a node and would otherwise drop its subtree, after an
            // unresolved id and a slot the component never declared. All three now walk on.
            children.forEach { node(it, depth + 1) }
          }
          children != null && parameter.composableSlot -> {
            val nested = children.joinToString("\n") { node(it, depth + 1) }
            arguments += "${ComponentSnippets.escapeIfKeyword(parameter.name)} = {\n$nested\n$pad}"
          }
          // Untouched by the document. A default may be omitted; anything else still has to be
          // filled, and the placeholder table is the same one the call-site generator uses.
          parameter.hasDefault -> Unit
          else -> {
            val placeholder = ComponentSnippets.placeholderFor(parameter)
            if (placeholder == null) {
              reasons +=
                "`${record.symbol.name}` needs `${parameter.name}: ${parameter.type}` and the " +
                  "document does not set it"
            } else {
              // A constructed placeholder (`TextFieldState()`, issue #5067) is the one the table
              // writes that does not resolve on its own, so its import travels with it — through
              // the same conflict check every other import here goes through, which is what keeps
              // two same-named types from silently producing a file Kotlin refuses.
              ComponentSnippets.constructedTypeOf(parameter)?.let {
                imports += ComponentSnippets.escapeCallableIfKeyword(it)
              }
              arguments += "${ComponentSnippets.escapeIfKeyword(parameter.name)} = $placeholder"
            }
          }
        }
      }
      val name =
        if (record.canonicalId in simplyImportable)
          ComponentSnippets.escapeIfKeyword(record.symbol.name)
        else qualified
      return "$pad$name(${arguments.joinToString(", ")})"
    }

    /**
     * The Kotlin expression for [value] as an argument to [parameter], or null having recorded why
     * it does not fit.
     *
     * Two rules, because [ScreenValue] has two halves. A literal is checked by *rendering it
     * against the parameter's type*, so `Whole(1)` is an `Int` for an `Int` parameter and a `Long`
     * for a `Long` one — the parameter decides, which is what lets one document value serve either.
     * A [ScreenValue.Reference], [ScreenValue.Construct] or [ScreenValue.Chain] is checked the
     * other way round: it renders once, on its own terms, and its claimed type must equal the
     * parameter's.
     *
     * Both compare the **qualified** type, so a `com.example.String` property is rejected rather
     * than handed a string literal — the trap the call-site generator was caught by twice.
     */
    /**
     * A handler, as a lambda assigning declared state.
     *
     * The parameter must be a zero-argument function type. A handler on `onValueChange: (String) ->
     * Unit` would need a parameter list this generator has no name for, and emitting `{ … }` there
     * compiles only by accident of the argument being ignored.
     */
    fun lambda(actions: List<ScreenAction>, parameter: TargetParameter, owner: String): String? {
      val where = "`$owner`.`${parameter.name}`"
      // A composable slot is not an event callback, however much its type looks like one. The
      // `@Composable` lives in `composableSlot` rather than in `type`, so `content: @Composable ()
      // -> Unit` reads as `() -> Unit` and satisfies every shape check below. Compose then runs the
      // body while composing rather than when anything happens, so a `Toggle` bound here flips its
      // state on every composition and invalidates the scope that just wrote it — a screen that
      // recomposes forever, from a document the generator called valid.
      if (parameter.composableSlot) {
        reasons += "$where is a composable slot rather than an event callback"
        return null
      }
      // Zero arguments, not merely "a bare lambda fits". `acceptsBareLambda` is the slot question
      // and answers true for `(String) -> Unit`, because children placed in a slot may ignore its
      // receiver. A handler may not: `onValueChange` exists to deliver the new value, and a
      // generated body that ignores it compiles and silently drops what the control reported.
      if (!ComponentSnippets.acceptsZeroArgLambda(parameter.type)) {
        reasons += "$where is `${parameter.type}`, which a generated handler cannot satisfy"
        return null
      }
      if (actions.isEmpty()) {
        // An empty handler is a button that looks live and is not. The document meant something by
        // binding it, and an empty lambda is the one reading that hides the mistake.
        reasons += "$where binds a handler with no actions"
        return null
      }
      val statements = actions.map { action ->
        val declared = state[action.variable]
        if (declared == null) {
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
            // An event callback is not a composable scope. A reference, a construct or a chain
            // can name a composable read — `MaterialTheme.colorScheme.primary` is the documented
            // example — and Kotlin rejects one inside an `onClick`. The preamble hoists such an
            // expression to a binding because it has a composable scope to hoist into; a handler
            // is emitted inside the tree and has nowhere to put one, so this refuses instead of
            // returning `Emitted` for source that does not compile.
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
            // Literals carry no type of their own, so they are checked the way an argument is —
            // by rendering against the declared type rather than by comparing a claim.
            val rendered =
              argument(
                action.value,
                TargetParameter(action.variable, declared.typeFqn, typeFqn = declared.typeFqn),
                owner,
              ) ?: return null
            "$target.value = $rendered"
          }
        }
      }
      return "{ ${statements.joinToString("; ")} }"
    }

    /**
     * A state read, as the `.value` of the declared property.
     *
     * `.value` rather than a `by` delegate, because `by` needs `getValue` and `setValue` imported
     * and this generator's rule is that nothing it emits can be shadowed by the package it lands
     * in. Two more imports are two more chances of the conflict the import check already refuses,
     * bought for a spelling nobody reads twice in generated code.
     */
    fun stateRead(value: ScreenValue.StateRead, where: String): String? {
      val declared = state[value.variable]
      if (declared == null) {
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

    fun argument(value: ScreenValue, parameter: TargetParameter, owner: String): String? {
      val type = ComponentSnippets.qualifiedTypeOf(parameter)
      val where = "`$owner`.`${parameter.name}`"
      if (value.typeFqn != null) {
        val rendered = expression(value, where, depth = 0) ?: return null
        if (value.typeFqn != type) {
          reasons += "$where is $type, and this value is a ${value.typeFqn}"
          return null
        }
        return rendered
      }
      // `string` refuses an over-long value by *recording* the reason, so the count is read either
      // side of the render: a null literal that already explained itself must not draw the generic
      // "is not a String" on top of it, and comparing message text instead would silence the
      // second of two identical parameters on two nodes.
      val before = reasons.size
      val literal =
        when (value) {
          is ScreenValue.Text -> if (type != "kotlin.String") null else string(value.value, where)
          is ScreenValue.Bool -> if (type == "kotlin.Boolean") value.value.toString() else null
          is ScreenValue.Whole ->
            when (type) {
              "kotlin.Int" ->
                // `toInt()` wraps silently: 2147483648 would be emitted as -2147483648, which
                // compiles and is not the number anyone entered.
                if (value.value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())
                  value.value.toString()
                else null
              "kotlin.Long" -> long(value.value)
              else -> null
            }
          is ScreenValue.Fractional ->
            when {
              // Neither `NaN` nor `Infinity` is a Kotlin literal, so both would emit source the
              // compiler rejects.
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
          // Every case with a claimed type left through the branch above.
          else -> null
        }
      if (literal == null && reasons.size == before) {
        reasons += "$where is $type, which ${value::class.simpleName} is not"
      }
      return literal
    }

    /**
     * [value] rendered on its own terms — no parameter to lean on — or null having said why.
     *
     * This is the rule for every **nested** position (a constructor argument, a chain receiver),
     * where there is no declared type to render against. A literal therefore gets one fixed
     * spelling: a whole number is an `Int` when it fits and a `Long` otherwise, and a fraction is
     * always a `Double`. That is a real restriction — `Dp` takes a `Float`, so `Dp(16.0)` does not
     * compile and a projection wanting `16.dp` writes the idiomatic [ScreenValue.Chain] instead.
     * Documented rather than papered over: a rule a projection can read beats a coercion it cannot
     * predict.
     */
    fun expression(value: ScreenValue, where: String, depth: Int): String? {
      // A document arrives over the wire and nothing on that path bounds how deeply a value nests.
      // Recursion here is depth-first over attacker-shaped data, so the cap is a refusal rather
      // than a `StackOverflowError` thrown out of a generator that promised a `Result`.
      if (depth > MAX_VALUE_DEPTH) {
        reasons += "$where nests values more than $MAX_VALUE_DEPTH deep"
        return null
      }
      // Collected for every nesting level, not just the outermost, because a construct's argument
      // is as capable of naming a gated API as the construct itself. Unioned into the same two
      // sets a component's markers go into, so the wrapper carries one `@OptIn` of each mechanism
      // however many places asked for it.
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
        is ScreenValue.StateRead -> stateRead(value, where)
        is ScreenValue.Reference -> {
          val root = qualifiedName(value.rootFqn, where) ?: return null
          val members = value.members.map { name(it, where) ?: return null }
          (listOf(root) + members).joinToString(".")
        }
        is ScreenValue.Construct -> {
          val callable = qualifiedName(value.callableFqn, where) ?: return null
          val arguments = arguments(value.positional, value.named, where, depth) ?: return null
          "$callable($arguments)"
        }
        is ScreenValue.Chain -> {
          if (value.links.isEmpty()) {
            reasons += "$where is a chain with no links, which is a plain reference"
            return null
          }
          val rendered = expression(value.receiver, where, depth + 1) ?: return null
          // `-1.dp` is `-(1.dp)`, not `(-1).dp`: Kotlin binds the selector tighter than unary
          // minus. Verified with the compiler — `-1.toString()` is rejected outright, because
          // there is no `unaryMinus` on `String`. For a receiver whose result *does* have one the
          // failure is worse than a compile error: it silently applies the extension to the
          // positive value and negates afterwards.
          val receiver = if (rendered.startsWith("-")) "($rendered)" else rendered
          buildString {
            append(receiver)
            for (link in value.links) {
              // The **whole** callable, not just the simple name it ends in. Validating only the
              // last segment let `foo..padding` through: `padding` is a fine name, so the link
              // was accepted and imported as `foo.``.padding` — an empty backticked segment, in a
              // file this generator had already called compilable.
              val imported = qualifiedName(link.callableFqn, where) ?: return null
              val simple = link.callableFqn.substringAfterLast('.')
              // A chain link is *imported* and called by its simple name, so it has to be a
              // top-level declaration. `RowScope.weight` is not: it is a member extension of the
              // scope, supplied by an implicit receiver, and neither
              // `import …layout.RowScope.weight` nor a package-level `…layout.weight` resolves.
              // Emitted anyway it produces a file that fails on the import line.
              //
              // The qualifier's case is the evidence available here. Kotlin packages are lower
              // case and classifiers are capitalised by universal convention, so a capitalised
              // penultimate segment names a classifier and therefore a member. That is a
              // convention rather than a rule — a top-level callable in a package with a
              // capitalised segment is legal and would be refused — and refusing the legal
              // oddity beats emitting the common one broken.
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
              if (!link.property && simple == screenName) {
                // An extension imported under the screen's own name is shadowed by the function
                // being generated, so the chain would call the screen — or fail to resolve.
                reasons += "$where imports `$simple`, which is the screen's own name"
                return null
              }
              extensionImports += imported
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
        }
      }
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
     * A marker reaches the file as annotation source through `markerReference`, which only
     * keyword-escapes. That was safe while every marker came from `ComponentRecord` — discovery
     * read those off a class file — and stopped being safe the moment a [ScreenValue] could carry
     * its own, because a `ScreenDocument` is wire data. A marker holding a backtick and a newline
     * closes the generated `@OptIn(…)` and opens a top-level declaration: arbitrary code in the
     * file **without naming anything `expressionPackages` would have checked**.
     *
     * The shape check alone, deliberately. A marker is an inert type reference inside an annotation
     * and executes nothing, so restricting *which* markers may be named would refuse a project's
     * own experimental annotation for no safety gained. What has to hold is that it cannot stop
     * being a name.
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

    /** A dotted path, each segment escaped, or null having said why. */
    private fun qualifiedName(fqn: String, where: String): String? {
      val segments = fqn.split('.')
      // A **qualifier is required**, not just a writable name. Every path this validates is either
      // emitted fully qualified with no import (a reference, a construct) or imported by its full
      // name (a chain link), and a single segment can be neither: it names a declaration in the
      // default package, which a file in a named package can neither import nor refer to. Left
      // unchecked, `Construct("Color", …)` emitted a bare `Color(…)` into `package
      // generated.screen`
      // and this returned `Emitted` for it.
      //
      // Shape before trust, deliberately: a malformed name is malformed whatever vocabulary is
      // declared, and "this is not a qualified name" is the more actionable of the two answers.
      if (!isQualifiedName(fqn)) {
        reasons += "$where refers to `$fqn`, which is not a qualified Kotlin name"
        return null
      }
      // **The trust boundary.** Everything else in this file asks whether a name can be *written*;
      // this asks whether it may be *called*, and the two are not the same question once a
      // document arrives over the wire.
      //
      // `Construct` emits a fully-qualified call with the arguments the document supplies, so a
      // spelling-only check admits any accessible JVM method:
      // `Files.readString(Path.of("/etc/passwd"))` is a well-formed qualified call whose claimed
      // type is `kotlin.String`, matches a `String` parameter, and generates without complaint. A
      // host that then compiles and renders the screen — which is the point of generating it —
      // has executed it.
      //
      // So a caller declares the vocabulary its projection is allowed to name, and the default is
      // **empty**: a caller who never thought about this gets refusals rather than arbitrary code.
      // A prefix matches the package itself or a name under it, never a longer sibling package,
      // which is why the `.` is required rather than a bare `startsWith`.
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
      // A JVM constant-pool string is length-prefixed with an unsigned short, so a value over 65535
      // modified-UTF-8 bytes cannot be a literal at all — the backend fails late, on a file this
      // generator has already called compilable. Nothing bounds a pasted document value, so it is
      // measured and refused rather than assumed small.
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
   * A `Long` literal.
   *
   * `-9223372036854775808L` does not compile: Kotlin reads the positive token first and rejects it
   * as out of range, then applies unary minus. Verified with the compiler, which is also why
   * `Int.MIN_VALUE` is left as a plain literal — the same spelling one type down *is* accepted.
   *
   * Qualified for the same reason `@kotlin.OptIn` is: the generated file sits in a package the
   * caller chose, and a same-package declaration named `Long` shadows the default import.
   */
  private fun long(value: Long): String =
    if (value == Long.MIN_VALUE) "kotlin.Long.MIN_VALUE" else "${value}L"

  /**
   * A Kotlin string literal for [value].
   *
   * `$` needs escaping as much as `"` does: a user typing `$name` into a label would otherwise
   * generate a template referring to a variable that does not exist, which is a compile error
   * produced by ordinary text.
   */
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
   * Whether [name] can be written into generated source as a bare declaration name.
   *
   * Three ways it cannot, and all three produce source the compiler rejects rather than a warning:
   * it is not an identifier at all (`my screen`), it is a hard keyword (`when`), or it is
   * all-underscore. The last is the least obvious — `_`, `__` and friends match every identifier
   * regex ever written and Kotlin reserves them, so `fun _()` fails with "Names _, __, ___, … are
   * reserved in Kotlin".
   */
  private fun isUsableIdentifier(name: String): Boolean =
    ComponentSnippets.isIdentifier(name) &&
      !ComponentSnippets.isHardKeyword(name) &&
      name.any { it != '_' }

  /**
   * Whether [name] can be written as a Kotlin name at all — bare or in backticks.
   *
   * Wider than [isUsableIdentifier] on purpose, and for a different question. That one asks whether
   * a *declaration* can be named this; this one asks whether a name a projection handed us can be
   * *referred to*, and Kotlin's backticks admit far more than its identifier rule does — a real
   * marker in this repo's own fixtures is spelled `` `Api${'$'}Experimental` ``. So the rule is the
   * escape's own limit rather than the identifier's: a backticked name may not be empty and may not
   * contain the characters that would close the quoting or reparse as structure.
   */
  /**
   * Whether [fqn] is a dotted path of writable names carrying a qualifier.
   *
   * Shared by the callable check and the marker check so the two cannot drift. Both put a
   * projection-supplied string into generated source, and a string that is not a name is how one
   * stops being a reference and starts being syntax.
   */
  /**
   * The local a hoisted initializer is bound to.
   *
   * Derived from the state's own name so the generated line reads as belonging to it, and bumped
   * until it collides with nothing this file already writes — a screen may legitimately declare
   * both `caption` and `captionInitial`, or call a component named `CaptionInitial`, and the
   * binding must shadow neither.
   */
  private fun initialBinding(name: String, taken: Set<String>): String {
    var candidate = "${name}Initial"
    while (candidate in taken) candidate += "_"
    return ComponentSnippets.escapeIfKeyword(candidate)
  }

  private fun isQualifiedName(fqn: String): Boolean {
    val segments = fqn.split('.')
    return fqn.isNotEmpty() && segments.size >= 2 && segments.all(::isWritableName)
  }

  private fun isWritableName(name: String): Boolean =
    name.isNotEmpty() &&
      // Reserved, and reserved past backticks. `_`, `__` and friends match every identifier rule
      // ever written, so nothing else here rejects them, and `receiver._` would be returned as
      // successfully generated source that does not compile. The same rule `isUsableIdentifier`
      // applies to a declaration's name applies to a name we merely refer to.
      name.any { it != '_' } &&
      name.none { it in FORBIDDEN_IN_A_NAME }

  private val FORBIDDEN_IN_A_NAME = ".;:\\/[]<>`\n\r".toSet()

  /**
   * An opt-in marker, spelled for source.
   *
   * The name arrives already in source notation — the producer rebuilds a nested marker's name from
   * its nesting chain, because `$` is a nesting separator in a binary name and an ordinary
   * character inside a backticked one, and only the chain tells them apart. All that is left here
   * is keyword escaping, since a marker under `com.`when`` is spelled without backticks anywhere it
   * is recorded.
   */
  private fun markerReference(marker: String): String =
    ComponentSnippets.escapeCallableIfKeyword(marker)

  /**
   * The bytes [value] occupies as a JVM constant-pool string.
   *
   * Modified UTF-8, not UTF-8: `NUL` is two bytes rather than one, and a supplementary character is
   * six (both halves of the surrogate pair encoded separately) rather than four.
   */
  private fun modifiedUtf8Length(value: String): Int = value.sumOf { c ->
    when {
      c.code in 1..0x7F -> 1
      c.code <= 0x7FF -> 2
      else -> 3
    }
  }

  private const val MAX_CONSTANT_POOL_STRING = 65535

  /**
   * How deeply one argument's value may nest.
   *
   * Sixteen is far past anything a builder produces — `Modifier.padding(PaddingValues(16.dp))` is
   * three — and far short of what overflows a stack. The number is not the point; having one is.
   */
  private const val MAX_VALUE_DEPTH = 16

  /**
   * Simple names the generated file has already spent on its own scaffolding.
   *
   * The wrapper always imports `androidx.compose.runtime.Composable`, so a catalog component that
   * happens to be called `Composable` would be imported alongside it and `Composable()` would be
   * ambiguous between the two. Such a component is called fully qualified instead — the same answer
   * the screen's own name and a two-package collision already get.
   */
  private val RESERVED_BY_THE_WRAPPER = setOf("Composable")

  /** The tooling annotation a [Preview] emits, imported only when one is asked for. */
  private const val PREVIEW_ANNOTATION = "androidx.compose.ui.tooling.preview.Preview"

  private const val PREVIEW_SIMPLE_NAME = "Preview"

  /** The wrapper's name, needed before it is emitted so a component cannot be shadowed by it. */
  private fun previewFunctionName(screenName: String) = "$screenName$PREVIEW_SIMPLE_NAME"

  /**
   * A language tag this generator is willing to put inside a string literal.
   *
   * Letters, digits and separators only. `@Preview(locale = …)` takes a tag such as `en-US`, and
   * anything outside this shape is either not a tag or is trying to be something other than a tag —
   * a quote or a newline would close the literal and continue in code. Escaping it would also work
   * and is what [ScreenValue.Text] does for values a designer typed; a locale is not typed prose,
   * so a wrong one is worth naming rather than smuggling through as an escaped string that no
   * Android runtime will resolve anyway.
   */
  private val LANGUAGE_TAG = Regex("[A-Za-z0-9]+(?:[-_][A-Za-z0-9]+)*")

  /** Every problem with [preview], so a caller fixes them in one pass rather than one per run. */
  private fun previewRefusals(preview: Preview): List<String> = buildList {
    preview.widthDp?.let { if (it <= 0) add("preview widthDp must be positive, not $it") }
    preview.heightDp?.let { if (it <= 0) add("preview heightDp must be positive, not $it") }
    preview.fontScale?.let {
      // `!(it > 0)` rather than `it <= 0` so a NaN — which loses every comparison — is caught here
      // instead of reaching the file as `fontScale = NaNf`, which does not compile.
      if (!(it > 0.0) || it.isInfinite())
        add("preview fontScale must be finite and positive, not $it")
    }
    preview.locale?.let {
      if (!LANGUAGE_TAG.matches(it)) add("preview locale `$it` is not a language tag")
    }
  }

  /**
   * The `@Preview` wrapper: a private, zero-argument composable that calls the screen.
   *
   * A wrapper rather than the annotation on the screen itself. The screen is the artifact a caller
   * pastes and then *calls* from their own code; annotating it would make every call site carry a
   * preview, and a `@Preview` function is expected to take no arguments — a constraint that belongs
   * to the preview, not to the screen it previews.
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
        // One argument per line, always. A design carrying a locale and a uiMode runs well past
        // 100 columns on one line, and ktfmt is not run over what this emits.
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
}
