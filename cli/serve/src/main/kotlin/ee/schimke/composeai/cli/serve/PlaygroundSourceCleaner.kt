package ee.schimke.composeai.cli.serve

/**
 * Turns a catalog sticker's source into the **usage code** a developer would write — the thing the
 * playground handoff (and, next, the viewer's Source tab) should open on.
 *
 * ### The problem
 *
 * `PlaygroundSeedResolver` already narrows a preview file to the one declaration behind the card
 * that was clicked, verbatim. Verbatim is honest but it is not usable: a sticker carries the
 * machinery that lets a single declaration serve a baked PNG, a live clickable session, six themes
 * and a variant matrix at once. Opening `Button/Filled` in the playground today hands over three
 * catalog annotations, a `Sticker { }` frame, a click tally, a size knob, a shape knob, an enabled
 * knob, a private layout wrapper and a string-resource lookup — around thirty lines, of which two
 * are about `Button`. Worse, half of those names live in the catalog's *own* module, and the
 * playground compiles against the published bundle, so a fair number of them do not even resolve:
 * the seed note has to warn that unresolved references are expected and should be deleted.
 *
 * So the noise is not only cosmetic. Cleaning is what makes the seed **runnable**.
 *
 * ### The approach: subtract declared scaffolding, then close over what is left
 *
 * Everything here is either catalog-agnostic or driven by [UsageRules], which the catalog declares
 * once for its handful of helpers rather than per component. In order:
 *
 * 1. **Slice** to the declaration containing the anchor line (as the seed already did).
 * 2. **Strip catalog annotations**, resolved through the file's own imports so a rule can only ever
 *    strike an annotation it actually names.
 * 3. **Inline string resources**, so the snippet renders the label the sticker renders.
 * 4. **Apply the scaffold rules** — unwrap, substitute, inline, drop, rename (see
 *    [UsageRules.Kind]).
 * 5. **Close over same-file references.** Whatever the cleaned body still calls that is declared in
 *    the same file (`FigmaButtonContent`, `SizedLabel`) is pulled in and cleaned too, recursively.
 *    This is the step that turns "expect unresolved references" into a buffer that compiles.
 * 6. **Prune imports** to what survived, add what the rewrites need, stamp a real `@Preview`.
 *
 * ### Part parse, part text scan
 *
 * This began as pure text, because the Kotlin frontend is deliberately kept off the CLI's classpath
 * (`cli/build.gradle.kts`). The snippet corpus then showed what that cost: named-argument binding,
 * a receiver chain mistaken for a package qualifier, a trailing-lambda call with no parentheses, a
 * qualified call no pass could see — five defects, all of them structure being guessed at.
 *
 * So the structural questions now go to a real parse. [UsageSourceParser] loads `:usage-source-psi`
 * into the *same kind* of isolated classloader the playground compiler already uses, so the
 * frontend still never reaches the CLI's own classpath; [applySubstituteParsed] and the residue
 * scan read [UsageSourceFacts] rather than regex. The remaining passes are still text, and the
 * whole parse is optional: a host with no staged sidecar keeps the text path, which is what shipped
 * before.
 *
 * Either way it is built to **fail in the safe direction**: every text pass is masked against
 * string and comment content so it cannot rewrite inside a literal, anything it does not understand
 * it leaves alone, and [Result.residue] reports declared scaffolding that survived — so a seed that
 * came out half-cleaned says so rather than pretending. The caller falls back to the verbatim slice
 * when [clean] returns null.
 *
 * The formatting assumptions are ktfmt's (Google style), which every catalog in these repos is
 * formatted with: one argument per line in a wrapped call, a blank line between top-level
 * declarations, no blank line inside an annotation stack.
 */
object PlaygroundSourceCleaner {

  /**
   * @property text the cleaned Kotlin: imports, the entry declaration, and any same-file helpers it
   *   still needs.
   * @property entryFunction the name of the declaration the anchor fell in, for the editor's note.
   * @property residue declared scaffolding that survived every pass — empty is the good case. A
   *   non-empty residue is a *reportable* outcome, not a failure: the seed is still better than
   *   verbatim, and the names here are the ones whose rules need writing.
   */
  data class Result(val text: String, val entryFunction: String?, val residue: List<String>)

  /**
   * Clean [source] around [bodyLine]. Returns null when there is nothing safe to do — no anchor, an
   * anchor that does not land in a declaration (the file moved under a branch `ref` since discovery
   * ran), or a file with no import header to reason about — in which case the caller seeds the
   * verbatim slice as before.
   *
   * [strings] maps string-resource keys to the English literal, empty when the catalog declares no
   * [UsageRules.stringsPath].
   */
  fun clean(
    source: String,
    bodyLine: Int?,
    rules: UsageRules,
    strings: Map<String, String> = emptyMap(),
    parser: UsageSourceParser? = UsageSourceParser.of(),
    helperSources: List<String> = emptyList(),
  ): Result? {
    if (bodyLine == null) return null
    val lines = source.lines()
    if (bodyLine < 1 || bodyLine > lines.size) return null
    if (lines[bodyLine - 1].isBlank()) return null

    val helpers = helperIndex(helperSources)
    val extraImports = LinkedHashSet<Import>()
    val imports = importMap(lines)
    val blocks = topLevelBlocks(lines)
    val entryIndex =
      blocks.indexOfFirst { bodyLine - 1 in it.range }.takeIf { it >= 0 } ?: return null
    val declaredAt = blocks.withIndex().mapNotNull { (i, b) -> b.name?.let { it to i } }.toMap()

    val residue = LinkedHashSet<String>()
    val addedImports = LinkedHashSet<String>()
    val cleanedByIndex = LinkedHashMap<Int, String>()

    // Close over same-file references breadth-first: clean a block, see what it still calls that
    // this file declares, queue those. `seen` bounds it — mutual recursion between two helpers
    // would otherwise loop.
    val queue = ArrayDeque(listOf(entryIndex))
    val seen = mutableSetOf<Int>()
    while (queue.isNotEmpty()) {
      val index = queue.removeFirst()
      if (!seen.add(index)) continue
      val block = blocks[index]
      val cleaned =
        cleanBlock(
          text = block.text,
          rules = rules,
          imports = imports,
          strings = strings,
          isEntry = index == entryIndex,
          residue = residue,
          addedImports = addedImports,
          parser = parser,
          helpers = helpers,
          extraImports = extraImports,
        )
      cleanedByIndex[index] = cleaned
      for ((name, at) in declaredAt) {
        if (at != index && at !in seen && mentionsWord(cleaned, name)) queue.addLast(at)
      }
    }

    // A cross-file helper whose name equals the ENTRY's is not satisfied by the entry declaration,
    // and treating it as satisfied produces a preview that calls itself. Renamed before the helper
    // closure runs, so the reference below resolves to a declaration that will actually be emitted.
    val collision =
      resolveEntryNameCollision(
        blocks[entryIndex].name,
        cleanedByIndex[entryIndex],
        helpers,
        declaredAt.keys + rules.scaffolds.keys,
      )
    val closureHelpers = collision?.helpers ?: helpers
    collision?.let { cleanedByIndex[entryIndex] = it.entryBody }

    // Then the same closure across the declared scaffold sources. A catalog whose component
    // bodies live in a shared module leaves the cleaned text calling `StatefulCheckbox` or
    // `CardContentSlot` — names as unresolvable to a reader as the sticker frame was, and which
    // the same-file pass structurally cannot reach. Bounded, unlike the same-file loop: these
    // files are somebody else's whole module, and a snippet dragging a hundred declarations
    // behind it has stopped being an example of anything.
    val cleanedHelpers =
      closeOverHelpers(
        seeds = cleanedByIndex.values,
        helpers = closureHelpers,
        skip = declaredAt.keys + rules.scaffolds.keys,
        rules = rules,
        strings = strings,
        residue = residue,
        addedImports = addedImports,
        parser = parser,
        extraImports = extraImports,
      )

    // Entry first, then its helpers in file order — a reader wants the composable they clicked at
    // the top, not after two private helpers they did not ask about.
    val bodies = buildList {
      add(cleanedByIndex.getValue(entryIndex))
      cleanedByIndex.keys
        .sorted()
        .filter { it != entryIndex }
        .forEach { add(cleanedByIndex.getValue(it)) }
      addAll(cleanedHelpers)
    }
    val body = bodies.joinToString("\n\n").trimEnd()
    if (body.isBlank()) return null

    val header = headerFor(lines, imports, body, addedImports, rules, residue, extraImports)
    val text = if (header.isEmpty()) body else "$header\n\n$body"
    return Result(text, blocks[entryIndex].name, residue.toList())
  }

  // ---------------------------------------------------------------------------------------------
  // Block splitting — the same "column 0 after a blank line" rule PlaygroundSeed.sliceDeclaration
  // uses, applied to every declaration in the file rather than just the anchored one. See that
  // function's KDoc for why this rule and not brace counting.
  // ---------------------------------------------------------------------------------------------

  private data class Block(val range: IntRange, val text: String, val name: String?)

  private fun topLevelBlocks(lines: List<String>): List<Block> {
    val headerEnd = headerEndExclusive(lines)
    val starts = (headerEnd..lines.lastIndex).filter { startsTopLevelDeclaration(lines, it) }
    return starts.mapIndexed { i, start ->
      val nextStart = starts.getOrNull(i + 1) ?: (lines.size)
      var end = nextStart - 1
      while (end > start && lines[end].isBlank()) end--
      val text = lines.subList(start, end + 1).joinToString("\n")
      Block(start..end, text, declaredName(text))
    }
  }

  private fun startsTopLevelDeclaration(lines: List<String>, i: Int): Boolean {
    val line = lines[i]
    if (line.isBlank()) return false
    if (line.first().isWhitespace()) return false
    return i == 0 || lines[i - 1].isBlank()
  }

  private fun headerEndExclusive(lines: List<String>): Int {
    val lastImport = lines.indexOfLast { it.trimStart().startsWith("import ") }
    if (lastImport >= 0) return lastImport + 1
    val packageLine = lines.indexOfLast { it.trimStart().startsWith("package ") }
    return if (packageLine >= 0) packageLine + 1 else 0
  }

  /**
   * Anchored at **column 0**, which is what makes it a *top-level* declaration matcher: the same
   * pattern unanchored would match the `val c = counted(…)` inside a body and report the block as
   * declaring `c`.
   */
  /**
   * Modifiers are matched as "any run of lowercase words" rather than as a closed list. Kotlin has
   * many, and a closed list missed exactly the ones that change what a declaration *is* — `data
   * class`, `enum class`, `sealed class`, `value class`. A preview referencing a same-file `data
   * class Model` then had that block left out of the closure while the seed still claimed to be
   * clean, so `Model(...)` came back unresolved with no residue to warn about it. Over-matching is
   * harmless here: the pattern is still anchored at column 0 and still has to reach a real
   * declaration keyword.
   */
  /**
   * The leading annotations a one-line declaration carries — `@Composable fun Sticker(id: String) =
   * …`, which is what ktfmt emits whenever the whole thing fits. Without this the declaration has
   * no *name* as far as [declaredName] is concerned, so it is invisible to both closure passes: a
   * one-line helper simply never came along, and the snippet called something it never brought.
   */
  private const val ANNOTATION_RUN = """(?:@[A-Za-z_][A-Za-z0-9_.]*(?:\([^)\n]*\))?\s+)*"""

  private val DECLARATION =
    Regex(
      """^$ANNOTATION_RUN(?:[a-z]+\s+)*(?:fun|val|var|class|object|interface|typealias)\s+(?:<[^>]*>\s+)?([A-Za-z_][A-Za-z0-9_]*)"""
    )

  /**
   * The name a declaration block introduces. Scans for the first column-0 declaration line rather
   * than examining one candidate: a block opens with KDoc and an annotation stack, and a multi-line
   * annotation's continuation lines (` id = "Button/Filled",`) look like neither an annotation nor
   * a declaration.
   */
  /**
   * An EXTENSION declaration, captured by its callable name rather than its receiver.
   *
   * [DECLARATION] takes the first identifier after `fun`, which for `private fun
   * Morph.toComposePath(...)` is `Morph` — the receiver type. Indexing the helper under that name
   * meant a body calling `morph.toComposePath(progress)` never matched it: the checked-in
   * `shape-morph` component does exactly that, so its cleaned snippet brought `ShapeMorphViewer`
   * along and left `toComposePath` behind — an unresolved call, and not in residue either, because
   * the residue check reads the same index.
   *
   * Tried before [DECLARATION] and only for `fun`, since only a function can be an extension here;
   * a declaration with no receiver does not match (there is no `.`) and falls through unchanged.
   */
  private val EXTENSION_DECLARATION =
    Regex(
      """^$ANNOTATION_RUN(?:[a-z]+\s+)*fun\s+(?:<[^>]*>\s+)?[A-Za-z_][A-Za-z0-9_]*(?:<[^>]*>)?\??\.([A-Za-z_][A-Za-z0-9_]*)\s*\("""
    )

  private fun declaredName(text: String): String? =
    text.lines().firstNotNullOfOrNull { line ->
      EXTENSION_DECLARATION.find(line)?.groupValues?.get(1)
        ?: DECLARATION.find(line)?.groupValues?.get(1)
    }

  // ---------------------------------------------------------------------------------------------
  // Header
  // ---------------------------------------------------------------------------------------------

  /**
   * One `import` line: what the body refers to it by ([name] — the alias when there is one), where
   * it points, and how to write it back out.
   *
   * Aliases are carried rather than collapsed to the FQN's last segment. Deriving the name from the
   * FQN would look up `Bar` for `import foo.Bar as Baz` — so a body that says `Baz` would prune the
   * import it needs, and an import that survived would be re-emitted without its `as Baz`.
   */
  private data class Import(val name: String, val fqn: String, val alias: String?) {
    fun render(): String = if (alias == null) "import $fqn" else "import $fqn as $alias"
  }

  private fun importsOf(lines: List<String>): List<Import> = lines.mapNotNull { line ->
    val t = line.trim()
    if (!t.startsWith("import ")) return@mapNotNull null
    val spec = t.removePrefix("import ").trim()
    val alias = spec.substringAfter(" as ", "").trim().ifEmpty { null }
    val fqn = spec.substringBefore(" as ").trim()
    val name = alias ?: fqn.substringAfterLast('.')
    if (name.isEmpty()) null else Import(name, fqn, alias)
  }

  /** Name → FQN, for resolving an annotation's simple name against the file's own imports. */
  private fun importMap(lines: List<String>): Map<String, String> =
    importsOf(lines).associate { it.name to it.fqn }

  /**
   * The cleaned file header: the imports [body] still uses, plus the ones the rewrites introduced,
   * sorted.
   *
   * The `package` line is dropped deliberately. The snippet is no longer the catalog's code — it is
   * plain Compose that happens to have been derived from it — and compiling it into the catalog's
   * package would let it reach `internal` members that a real consumer could not, which would make
   * a snippet that builds here and not for the person who copies it.
   *
   * A file-level annotation is kept only when it is not catalog machinery (`@file:OptIn` stays,
   * `@file:CatalogGroup` goes) — but only if something in [body] still needs it, which is why the
   * import prune runs over the annotations too.
   */
  private fun headerFor(
    lines: List<String>,
    imports: Map<String, String>,
    body: String,
    addedImports: Set<String>,
    rules: UsageRules,
    residue: MutableSet<String>,
    extraImports: Set<Import> = emptySet(),
  ): String {
    // Kept whole, by paren balance rather than by line. A ktfmt-wrapped
    // `@file:OptIn(\n  A::class,\n  B::class,\n)` is one annotation across five lines, and a
    // line-at-a-time filter would emit its opening line alone — an unterminated annotation, and a
    // header that then prunes the imports only its discarded arguments referenced.
    val fileAnnotations =
      annotationBlocks(lines.takeWhile { !it.trimStart().startsWith("package ") })
        .filterNot { isScaffoldAnnotation(it.name, imports, rules) }
        .map { it.text }
    // The preview file's own imports, plus the ones the scaffold sources contributed for whatever
    // was expanded or closed over out of them. An extra whose simple name the preview file already
    // binds to something else is dropped rather than emitted alongside it: two imports of the same
    // name do not compile, and the file being cleaned is the one whose meaning must win.
    val ownNames = importsOf(lines).map { it.name }.toSet()
    val candidates =
      importsOf(lines) + extraImports.filter { it.name !in ownNames }.distinctBy { it.name }
    val kept = candidates.filter { import ->
      if (isScaffoldPackage(import.fqn, rules)) {
        // A scaffold import that is still referenced means a rule is missing, not that the import
        // should be kept — record it and drop it, so the residue names the gap.
        if (mentionsIdentifier(body, import.name)) residue.add(import.name)
        false
      } else if (import.fqn in DELEGATION_IMPORTS) {
        // `var checked by remember { mutableStateOf(…) }` needs `getValue`/`setValue` and names
        // neither, so the mention test prunes exactly the two imports that make the delegation
        // compile. Keep them whenever the body delegates — an unused import is a warning, a
        // missing one is a snippet advertised as runnable that does not build.
        usesPropertyDelegation(body)
      } else {
        mentionsIdentifier(body, import.name) ||
          fileAnnotations.any { mentionsIdentifier(it, import.name) }
      }
    }
    val all = (kept.map { it.render() } + addedImports.map { "import $it" }).distinct().sorted()
    return (fileAnnotations + (if (fileAnnotations.isEmpty()) emptyList() else listOf("")) + all)
      .joinToString("\n")
      .trim()
  }

  /**
   * The two imports a `by` property delegation needs and never mentions. Compose's `MutableState`
   * delegation is the reason: `import androidx.compose.runtime.getValue` is what makes `var x by
   * remember { mutableStateOf(0) }` resolve, and nothing in that line says `getValue`.
   */
  private val DELEGATION_IMPORTS =
    setOf("androidx.compose.runtime.getValue", "androidx.compose.runtime.setValue")

  private fun usesPropertyDelegation(body: String): Boolean {
    val mask = codeMask(body)
    return Regex("""\b(?:val|var)\s+[A-Za-z_][A-Za-z0-9_]*\s+by\s""").findAll(body).any {
      mask[it.range.first]
    }
  }

  private data class AnnotationBlock(val name: String, val text: String)

  /**
   * The file-level annotations in [lines], each as one block however many lines it spans. Also used
   * by [stripScaffoldAnnotations], so the two agree about where an annotation ends.
   */
  private fun annotationBlocks(lines: List<String>): List<AnnotationBlock> {
    val out = mutableListOf<AnnotationBlock>()
    var i = 0
    while (i < lines.size) {
      if (!lines[i].trimStart().startsWith("@file:")) {
        i++
        continue
      }
      val end = annotationEnd(lines, i)
      out.add(
        AnnotationBlock(
          name = annotationName(lines[i]),
          text = lines.subList(i, end + 1).joinToString("\n"),
        )
      )
      i = end + 1
    }
    return out
  }

  // ---------------------------------------------------------------------------------------------
  // Block cleaning
  // ---------------------------------------------------------------------------------------------

  private fun cleanBlock(
    text: String,
    rules: UsageRules,
    imports: Map<String, String>,
    strings: Map<String, String>,
    isEntry: Boolean,
    residue: MutableSet<String>,
    addedImports: MutableSet<String>,
    parser: UsageSourceParser?,
    helpers: Map<String, Helper> = emptyMap(),
    extraImports: MutableSet<Import> = mutableSetOf(),
  ): String {
    var out = stripScaffoldAnnotations(text, imports, rules)
    // Before every other pass: a delegating sticker has no component in it *to* clean until what it
    // delegates to has been spliced in, and everything below — the string inliner, the knob
    // substitutions, the import prune — then runs over the real body rather than over a one-line
    // wrapper. See [UsageRules.Kind.EXPAND].
    out = expandDelegates(out, rules, helpers, residue, extraImports)
    out = inlineStringResources(out, strings)
    // Before anything matches on a helper name: a call written fully qualified is the same call.
    out = unqualifyScaffoldCalls(out, rules)
    // Order matters. UNWRAP first, so a wrapper takes its own arguments away with it before DROP
    // starts reasoning about which arguments mention a dropped binding. INLINE next, so a member
    // substitution lands before DROP inspects the argument it sits in. DROP, then semantic theme
    // wrappers and RENAME last — rewriting early would hide a name the other passes match on.
    out = applyUnwrap(out, rules)
    // The parse settles argument binding, trailing-lambda calls and qualifiers; the text pass is
    // the fallback for a host with no staged sidecar (see [UsageSourceParser]).
    out =
      if (parser != null) applySubstituteParsed(out, rules, addedImports, parser)
      else applySubstitute(out, rules, addedImports)
    out = applyInline(out, rules, addedImports)
    out = applyDrop(out, rules, residue)
    out = applyMaterial3SystemTheme(out, rules)
    out = applyRename(out, rules, addedImports)
    if (isEntry) out = stampPreview(out, rules, addedImports)
    // Residue: declared scaffolding that survived. With a parse, every *call* is visible however it
    // is qualified — which is what the text scan structurally could not do, since it rejects a name
    // after a `.` by design. The word scan stays alongside it for non-call references (a binding, a
    // resource key) that no call node would report.
    val calledNames =
      parser?.facts(out)?.calls?.map { it.callee }?.toSet()
        ?: rules.scaffolds.keys.filter { mentionsQualifiedCall(out, it) }.toSet()
    for (name in rules.scaffolds.keys) {
      if (name in calledNames || mentionsWord(out, name)) residue.add(name)
    }
    return out.trimEnd()
  }

  /**
   * A **package-qualified** call to a declared helper —
   * `ee.schimke.composeai.overrides.previewOverrideString("k", "v")` — reduced to the bare name, so
   * every pass below sees the call it already knows how to rewrite.
   *
   * Without this, such a call is invisible in both directions: [wordOccurrences] rejects an
   * occurrence preceded by `.` (correctly — `foo.counted` is not the scaffold `counted`), so no
   * rule fires, *and* the call needs no import, so the residue pass has nothing to report. The seed
   * comes out marked cleaned with a repository-internal call still in it, which is the one outcome
   * this whole design is built to avoid.
   *
   * The prefix must be a package the rules **name** ([UsageRules.scaffoldPackages]), not merely
   * something package-*shaped*. `state.metrics.counted { }` is two lowercase segments followed by a
   * declared scaffold name, and it is somebody's ordinary receiver chain; stripping it on that
   * resemblance would hand the call to the scaffold passes and rewrite it.
   *
   * An allow-list therefore *misses* a qualified call whose package nobody declared — and a miss is
   * only safe because [mentionsQualifiedCall] reports it as residue. [mentionsWord] alone cannot:
   * it rejects a name preceded by `.` by design, which is what made a package-qualified call
   * invisible in the first place.
   */
  private fun unqualifyScaffoldCalls(text: String, rules: UsageRules): String {
    // Only helpers a pass will actually rewrite. Unqualifying is a *setup* step — it exists so the
    // kind passes, which match a bare name, can see a package-qualified call. Doing it for a
    // [UsageRules.Kind.UNKNOWN] helper strips the qualifier and then no pass rewrites the call and
    // no pass adds an import, turning `ee.schimke.m3catalog.toggleable(true)` — which resolved —
    // into a bare `toggleable(true)` that does not. Left qualified, the call keeps working and
    // still lands in residue via `mentionsQualifiedCall`.
    val rewritable = rules.scaffolds.filterValues { it.kind != UsageRules.Kind.UNKNOWN }
    if (rewritable.isEmpty() || rules.scaffoldPackages.isEmpty()) return text
    val names = rewritable.keys.joinToString("|") { Regex.escape(it) }
    val packages = rules.scaffoldPackages.joinToString("|") { Regex.escape(it) }
    // `[({]` and not just `(`: a trailing-lambda call — `counted { }` — has no parentheses at all,
    // and that is the shape most scaffolding wrappers are written in.
    val qualified = Regex("""(?<![A-Za-z0-9_.])(?:$packages)\.($names)(?=\s*[({])""")
    val mask = codeMask(text)
    val out = StringBuilder(text.length)
    var at = 0
    for (m in qualified.findAll(text)) {
      if (!mask[m.range.first]) continue
      out.append(text, at, m.range.first).append(m.groupValues[1])
      at = m.range.last + 1
    }
    return if (at == 0) text else out.append(text, at, text.length).toString()
  }

  private fun isScaffoldPackage(fqn: String, rules: UsageRules): Boolean =
    rules.scaffoldAnnotationPackages.any { fqn == it || fqn.startsWith("$it.") }

  private fun isScaffoldAnnotation(
    simpleName: String,
    imports: Map<String, String>,
    rules: UsageRules,
  ): Boolean {
    // The bare-name list first: an annotation the catalog declares in the previews' own package
    // (`@CatalogModes`) is written with no import at all, so there is nothing for the package rule
    // below to resolve. See [UsageRules.scaffoldAnnotationNames].
    if (simpleName in rules.scaffoldAnnotationNames) return true
    val fqn = imports[simpleName] ?: return false
    return isScaffoldPackage(fqn, rules)
  }

  /**
   * Remove annotation lines whose simple name resolves, through the file's imports, into one of the
   * catalog's annotation packages — including the multi-line form (`@CatalogComponent(\n id =
   * …,\n)`), consumed by parenthesis balance rather than by line count.
   */
  private fun stripScaffoldAnnotations(
    text: String,
    imports: Map<String, String>,
    rules: UsageRules,
  ): String {
    val lines = text.lines()
    val out = mutableListOf<String>()
    var i = 0
    while (i < lines.size) {
      val line = lines[i]
      val trimmed = line.trimStart()
      val isAnnotation = trimmed.startsWith("@")
      if (!isAnnotation) {
        out.add(line)
        i++
        continue
      }
      val end = annotationEnd(lines, i)
      if (!isScaffoldAnnotation(annotationName(line), imports, rules)) {
        for (j in i..end) out.add(lines[j])
      } else if (end == i) {
        // A scaffold annotation can SHARE its line with the rest of the declaration — legal Kotlin,
        // and checked in: `CatalogText.kt:42` is
        // `@CatalogModes @Composable fun TextBrandedSpecimen() = Sticker("text-branded")`.
        // Dropping the whole line took `@Composable` and the function with it, leaving the cleaner
        // nothing to emit and falling back to the verbatim wrapper — the exact snippet this class
        // exists to replace. Remove only the matched annotation's own span; whatever the line
        // carries after it survives. Single-line only: a wrapped annotation owns every line of its
        // argument list, so the old whole-span drop is right there.
        val remainder = lineWithoutLeadingAnnotation(line)
        if (remainder.isNotBlank()) out.add(remainder)
      }
      i = end + 1
    }
    return out.joinToString("\n")
  }

  /**
   * [line] with its leading `@Annotation(...)` removed, indentation preserved.
   *
   * Returns blank when the annotation was the whole line, which is the ordinary case — the caller
   * then drops it as before.
   */
  private fun lineWithoutLeadingAnnotation(line: String): String {
    val indent = line.takeWhile { it.isWhitespace() }
    val body = line.substring(indent.length)
    if (!body.startsWith("@")) return line
    var index = 1
    if (body.startsWith("@file:")) index = "@file:".length
    while (index < body.length && (body[index].isLetterOrDigit() || body[index] == '_')) index++
    // Skip a balanced argument list, if any. Nesting and string literals both matter: an argument
    // can itself be an annotation (`@OptIn(A::class, B::class)`) and a string can hold a bracket.
    if (index < body.length && body[index] == '(') {
      var depth = 0
      var inString = false
      while (index < body.length) {
        val char = body[index]
        when {
          inString && char == '\\' -> index++
          char == '"' -> inString = !inString
          !inString && char == '(' -> depth++
          !inString && char == ')' -> {
            depth--
            if (depth == 0) {
              index++
              break
            }
          }
        }
        index++
      }
      // Unbalanced — not something to guess at. Leave the line to the caller's whole-span drop.
      if (depth != 0) return ""
    }
    val rest = body.substring(index).trimStart()
    return if (rest.isEmpty()) "" else indent + rest
  }

  /** `@file:OptIn(...)` / `@CatalogComponent(...)` → `OptIn` / `CatalogComponent`. */
  private fun annotationName(line: String): String =
    line.trimStart().removePrefix("@").removePrefix("file:").takeWhile {
      it.isLetterOrDigit() || it == '_'
    }

  /**
   * The index of the last line of the annotation starting at [start] — the same line when it takes
   * no arguments or fits on one, and the line closing its argument list when ktfmt has wrapped it.
   */
  private fun annotationEnd(lines: List<String>, start: Int): Int {
    var depth = 0
    var end = start
    while (end < lines.size) {
      depth += parenBalance(lines[end])
      if (depth <= 0) break
      end++
    }
    return minOf(end, lines.lastIndex)
  }

  private fun parenBalance(line: String): Int {
    val mask = codeMask(line)
    var n = 0
    for (k in line.indices) {
      if (!mask[k]) continue
      if (line[k] == '(') n++
      if (line[k] == ')') n--
    }
    return n
  }

  /**
   * `stringResource(Res.string.label_filled)` → `"Filled"`. The sticker renders a translated string
   * because a catalog must; a usage snippet that showed the lookup instead of the label would be
   * teaching the reader about the catalog's resource module rather than about the component.
   *
   * Only exact, single-argument `Res.string.<key>` lookups are inlined — anything with a formatting
   * argument or a computed key is left alone.
   */
  private fun inlineStringResources(text: String, strings: Map<String, String>): String {
    if (strings.isEmpty()) return text
    // Masked like every other pass. A doc comment or a literal may quote a lookup verbatim
    // (`Text("Use stringResource(Res.string.label)")`), and substituting there would splice a
    // quoted string into the middle of a quoted string.
    val mask = codeMask(text)
    val sb = StringBuilder()
    var last = 0
    for (m in Regex("""stringResource\(\s*Res\.string\.([A-Za-z0-9_]+)\s*\)""").findAll(text)) {
      val value = strings[m.groupValues[1]] ?: continue
      if (!mask[m.range.first]) continue
      sb.append(text, last, m.range.first)
      sb.append("\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
      last = m.range.last + 1
    }
    if (last == 0) return text
    sb.append(text, last, text.length)
    return sb.toString()
  }

  private fun applyRename(
    text: String,
    rules: UsageRules,
    addedImports: MutableSet<String>,
  ): String {
    var out = text
    for ((name, scaffold) in rules.scaffolds) {
      if (scaffold.kind != UsageRules.Kind.RENAME || scaffold.special != null) continue
      val to = scaffold.renameTo ?: continue
      if (!mentionsWord(out, name)) continue
      out = replaceWord(out, name, to)
      addedImports.addAll(scaffold.imports)
    }
    return out
  }

  /**
   * `Sticker { … }` → a stock Material 3 theme which actually consumes the preview's `uiMode`.
   *
   * Only the helper name is replaced, so the trailing lambda — the component usage this cleaner is
   * trying to preserve — stays byte-for-byte intact. The rewrite deliberately accepts only an
   * argument-free trailing-lambda call. `StickerFrame(tokens) { … }` may encode more than the
   * reusable stock light/dark policy; leaving it as residue is the safe and honest outcome.
   */
  private fun applyMaterial3SystemTheme(text: String, rules: UsageRules): String {
    var out = text
    for ((name, scaffold) in rules.scaffolds) {
      if (
        scaffold.kind != UsageRules.Kind.RENAME ||
          scaffold.special != UsageRules.MATERIAL3_SYSTEM_THEME
      )
        continue
      // The expansion is fully qualified on purpose. A preserved component body may already use a
      // different MaterialTheme (or colour-scheme helper) by simple name; injecting AndroidX
      // imports would make that otherwise valid source ambiguous.
      for (at in wordOccurrences(out, name).asReversed()) {
        var next = at + name.length
        while (next < out.length && out[next].isWhitespace()) next++
        var replaceEnd = at + name.length
        if (out.getOrNull(next) == '(') {
          val close = matchParen(out, next) ?: continue
          if (containsCode(out.substring(next + 1, close))) continue
          replaceEnd = close + 1
          next = close + 1
          while (next < out.length && out[next].isWhitespace()) next++
        }
        if (out.getOrNull(next) != '{') continue
        out = out.replaceRange(at, replaceEnd, MATERIAL3_SYSTEM_THEME_CALL)
      }
    }
    return out
  }

  /** `ButtonFrame(size) { <body> }` → `<body>`, de-indented to the call's own column. */
  private fun applyUnwrap(text: String, rules: UsageRules): String {
    var out = text
    for ((name, scaffold) in rules.scaffolds) {
      if (scaffold.kind != UsageRules.Kind.UNWRAP) continue
      var guard = 0
      while (guard++ < MAX_REWRITES) {
        val (callStart, lambdaOpen) = findWrapperCall(out, name) ?: break
        val lambdaClose = matchBrace(out, lambdaOpen) ?: break
        val inner = out.substring(lambdaOpen + 1, lambdaClose)
        val callIndent = indentOf(out, callStart)
        val lineStart = out.lastIndexOf('\n', callStart - 1) + 1
        val prefix = out.substring(lineStart, callStart)
        val body = reindent(inner, callIndent)
        // Where the splice starts depends on what precedes the call on its own line.
        //
        // When the line is only indentation, splice from the line start: that indent is already the
        // column the lifted body is being re-indented to, and keeping it would indent it twice.
        //
        // When something else precedes it, that something belongs to the declaration — the wrapper
        // is the expression body of the preview (`fun Card() = ButtonFrame(size) { … }`). Splicing
        // from the line start there deleted `fun Card() =` along with the wrapper, left the lifted
        // body at top level, and still called the result cleaned. So keep the prefix, and drop the
        // body's own first-line indent, which the prefix now supplies.
        out =
          if (prefix.isBlank()) out.substring(0, lineStart) + body + out.substring(lambdaClose + 1)
          else out.substring(0, callStart) + body.trimStart() + out.substring(lambdaClose + 1)
      }
    }
    return out
  }

  /**
   * A wrapper call and the `{` opening its trailing lambda — `Frame(size) { … }` **or** `Frame { …
   * }`.
   *
   * The paren-less form is not an edge case, it is how most Compose wrappers are written, and
   * [findCall] cannot see it: it requires a `(` after the name. So a catalog declaring its
   * argument-free sticker frame as UNWRAP got no rewrite at all and the frame in its residue —
   * which reads as "this rule needs writing" for a rule that was written correctly.
   */
  private fun findWrapperCall(text: String, name: String): Pair<Int, Int>? {
    for (at in wordOccurrences(text, name)) {
      var k = at + name.length
      while (k < text.length && text[k].isWhitespace()) k++
      if (k < text.length && text[k] == '(') {
        val close = matchParen(text, k) ?: continue
        k = close + 1
        while (k < text.length && text[k].isWhitespace()) k++
      }
      if (k < text.length && text[k] == '{') return at to k
    }
    return null
  }

  /** A `name = value` argument, as distinct from a positional one. */
  private val NAMED_ARG =
    Regex("""^([A-Za-z_][A-Za-z0-9_]*)\s*=(?!=)\s*(.+)$""", RegexOption.DOT_MATCHES_ALL)

  /**
   * Resolve a call's arguments to the positions a `$0`/`$1` template cites, so a **named** argument
   * substitutes as its value rather than as `default = "Shopping"`.
   *
   * [params] names the callee's parameters in declaration order. With it, positional arguments fill
   * parameters left to right and named ones fill by name, which is exactly Kotlin's own rule, so
   * `previewOverrideString(key = "title", default = "Shopping")` and
   * `previewOverrideString("title", "Shopping")` both put `"Shopping"` at `$1`.
   *
   * Without it — a rule declared before this existed — the old positional reading stands, except
   * that a call using named arguments returns null so the caller leaves the call alone and reports
   * it as residue. Guessing would be the one outcome worse than not rewriting: it emits Kotlin that
   * looks right and does not compile.
   *
   * An argument naming a parameter [params] does not list (a second overload's, say) is ignored
   * rather than fatal, and does not consume a positional slot — matching how Kotlin resolves it.
   */
  private fun bindArguments(args: List<String>, params: List<String>): List<String?>? {
    val named = args.map { NAMED_ARG.find(it) }
    if (params.isEmpty()) return if (named.any { it != null }) null else args
    val bound = arrayOfNulls<String>(params.size)
    var next = 0
    for ((i, arg) in args.withIndex()) {
      val match = named[i]
      if (match == null) {
        // Positional: the next parameter no named argument has already claimed.
        while (next < params.size && bound[next] != null) next++
        if (next >= params.size) continue // beyond the declared list; nothing cites it
        bound[next++] = arg
      } else {
        val at = params.indexOf(match.groupValues[1])
        if (at >= 0) bound[at] = match.groupValues[2].trim()
      }
    }
    return bound.toList()
  }

  /**
   * [applySubstitute] over a real parse: the same rules, with the guesswork removed.
   *
   * What changes against the text pass, all of it something the snippet corpus caught:
   * - arguments bind the way Kotlin binds them, named or positional, in any order;
   * - a trailing-lambda call (`counted { }`) is a call, though it has no parentheses;
   * - a qualified call replaces as a whole — no separate unqualifying step, and no regex deciding
   *   from shape whether `state.metrics` was a package.
   *
   * Re-parses after each rewrite rather than batching edits, so a knob nested inside another call
   * (`counted(catalogChoice(…))`) is plain by the time the outer call's argument text is read.
   * Innermost-first — descending start offset — for the same reason. Blocks are a declaration each
   * and parsing one costs well under a millisecond, so the loop is cheaper than the bookkeeping
   * that would replace it.
   */
  private fun applySubstituteParsed(
    text: String,
    rules: UsageRules,
    addedImports: MutableSet<String>,
    parser: UsageSourceParser,
  ): String {
    var out = text
    var guard = 0
    while (guard++ < MAX_REWRITES) {
      val facts = parser.facts(out) ?: return out
      val edit =
        facts.calls
          .asSequence()
          .filter { rules.scaffolds[it.callee]?.kind == UsageRules.Kind.SUBSTITUTE }
          // A matching *name* is not a matching call. `state.previewOverrideString(…)` is
          // somebody's
          // member function, and the replacement range covers the whole qualified expression — so
          // substituting on the name alone would delete their receiver and their call. Only a bare
          // call, or one qualified by a package the rules name, is this scaffold.
          .filter { it.receiver == null || it.receiver in rules.scaffoldPackages }
          .sortedByDescending { it.start }
          .mapNotNull { call ->
            val scaffold = rules.scaffolds.getValue(call.callee)
            val plain = scaffold.plain ?: return@mapNotNull null
            val args = facts.bind(call, scaffold.params) ?: return@mapNotNull null
            val rendered =
              Regex("""\$(\d+)""").replace(plain) { m ->
                args.getOrNull(m.groupValues[1].toInt()) ?: m.value
              }
            // A template citing an argument the call does not have would emit a literal `$1`. Leave
            // the call alone; the residue scan then reports it as an unwritten rule.
            if (rendered.contains(Regex("""\$\d"""))) null
            else Triple(call.replaceStart, call.replaceEnd, rendered to scaffold)
          }
          .firstOrNull() ?: return out
      val (start, end, replacement) = edit
      if (start < 0 || end > out.length || start >= end) return out
      out = out.substring(0, start) + replacement.first + out.substring(end)
      addedImports.addAll(replacement.second.imports)
    }
    return out
  }

  /**
   * `catalogChoice("style", "outlined", "outlined", "elevated")` → `"outlined"` — the whole call
   * expression replaced by what it evaluates to on the lane the render was baked on.
   *
   * Runs before [applyInline] so a knob nested inside a tally (`counted(catalogChoice(…))`) is
   * already plain by the time the tally's argument is captured as text.
   */
  private fun applySubstitute(
    text: String,
    rules: UsageRules,
    addedImports: MutableSet<String>,
  ): String {
    var out = text
    for ((name, scaffold) in rules.scaffolds) {
      if (scaffold.kind != UsageRules.Kind.SUBSTITUTE) continue
      val plain = scaffold.plain ?: continue
      var guard = 0
      while (guard++ < MAX_REWRITES) {
        val call = findCall(out, name) ?: break
        val args =
          bindArguments(
            splitTopLevel(out.substring(call.argsStart + 1, call.argsEnd)).map { it.trim() },
            scaffold.params,
          ) ?: break
        val rendered =
          Regex("""\$(\d+)""").replace(plain) { m ->
            args.getOrNull(m.groupValues[1].toInt()) ?: m.value
          }
        // A template citing an argument the call does not have would silently emit `$1`. Leave the
        // call alone instead; the residue check below then reports it as an unwritten rule.
        if (rendered.contains(Regex("""\$\d"""))) break
        out = out.substring(0, call.start) + rendered + out.substring(call.argsEnd + 1)
        addedImports.addAll(scaffold.imports)
      }
    }
    return out
  }

  /**
   * `val c = counted("Filled")` + `c.onClick` + `c.label` → `{}` + `"Filled"`, with the binding
   * line deleted.
   */
  private fun applyInline(
    text: String,
    rules: UsageRules,
    addedImports: MutableSet<String>,
  ): String {
    var out = text
    for ((name, scaffold) in rules.scaffolds) {
      if (scaffold.kind != UsageRules.Kind.INLINE) continue
      var guard = 0
      while (guard++ < MAX_REWRITES) {
        val binding = findValBinding(out, name) ?: break
        val replacements =
          scaffold.members.mapValues { (_, template) ->
            Regex("""\$(\d+)""").replace(template) { m ->
              binding.arguments.getOrNull(m.groupValues[1].toInt())?.trim() ?: m.value
            }
          }
        // The same guard applySubstitute carries, and for the same reason — plus a worse failure
        // if it is missing. A template citing an argument the call does not supply emits a literal
        // `$1` into the editor, and this path would already have deleted the binding that made the
        // code work. Leave the declaration alone; the residue check then reports the unwritten
        // rule.
        if (replacements.values.any { it.contains(Regex("""\$\d""")) }) break
        out = removeLines(out, binding.lineRange)
        for ((member, replacement) in replacements) {
          out = replaceWord(out, "${binding.name}.$member", replacement)
        }
        addedImports.addAll(scaffold.imports)
      }
    }
    return out
  }

  /**
   * Delete a knob and everything downstream of it: the `val` that binds it, and — the part that
   * does the real work — every **named** argument whose value mentions either.
   *
   * ### All or nothing, per declaration
   *
   * Only named arguments are eligible. A positional one carries no label to reason about, and
   * `Spacer(Modifier.width(size.iconSpacing))` would become `Spacer()`, which does not compile — a
   * text pass that guesses here produces code that looks clean and is broken, which is strictly
   * worse than code that looks noisy and runs.
   *
   * So the pass verifies itself: if any reference to a dropped binding survives the argument
   * filter, the whole DROP is **abandoned** for this declaration and the original text returned,
   * with the helper recorded in [residue]. The knob either disappears completely or is left exactly
   * as the catalog wrote it. There is no half-rewritten state, and the residue names precisely
   * which helper needs a better rule.
   */
  private fun applyDrop(text: String, rules: UsageRules, residue: MutableSet<String>): String {
    val dropped = mutableSetOf<String>()
    val helpers = mutableSetOf<String>()
    var out = text
    for ((name, scaffold) in rules.scaffolds) {
      if (scaffold.kind != UsageRules.Kind.DROP) continue
      if (!mentionsWord(out, name)) continue
      helpers.add(name)
      dropped.add(name)
      var guard = 0
      while (guard++ < MAX_REWRITES) {
        val binding = findValBinding(out, name) ?: break
        dropped.add(binding.name)
        out = removeLines(out, binding.lineRange)
      }
    }
    if (dropped.isEmpty()) return out
    out =
      filterCallArguments(out) { arg ->
        isNamedArgument(arg) && dropped.any { mentionsWord(arg, it) }
      }
    val survivor = dropped.firstOrNull { mentionsWord(out, it) }
    if (survivor != null) {
      residue.addAll(helpers)
      return text
    }
    return out
  }

  private fun isNamedArgument(arg: String): Boolean =
    Regex("""^\s*[A-Za-z_][A-Za-z0-9_]*\s*=[^=]""").containsMatchIn(arg)

  /** Puts a real `@Preview` back on the entry point, since the catalog's own was just stripped. */
  private fun stampPreview(
    text: String,
    rules: UsageRules,
    addedImports: MutableSet<String>,
  ): String {
    val simple = rules.previewAnnotation.substringAfterLast('.')
    if (mentionsWord(text, "@$simple")) return text
    val lines = text.lines().toMutableList()
    val at = lines.indexOfFirst { it.trimStart().startsWith("@Composable") }
    val insertAt = if (at >= 0) at else lines.indexOfFirst { DECLARATION.containsMatchIn(it) }
    if (insertAt < 0) return text
    lines.add(insertAt, "@$simple")
    addedImports.add(rules.previewAnnotation)
    return lines.joinToString("\n")
  }

  // ---------------------------------------------------------------------------------------------
  // Cross-file scaffolding: the declared scaffold sources, what may be expanded out of them, and
  // what may be closed over from them. See [UsageRules.scaffoldSources] and
  // [UsageRules.Kind.EXPAND].
  // ---------------------------------------------------------------------------------------------

  /** One top-level declaration read out of a scaffold source, carrying its file's imports. */
  private data class Helper(
    val text: String,
    val imports: List<Import>,
    val importMap: Map<String, String>,
  )

  /**
   * At most this many declarations are pulled in from the scaffold sources. The same-file closure
   * is deliberately unbounded — a preview file is the catalog author's own unit of code and all of
   * it is about the preview — but these files are a whole shared module, and a snippet that drags a
   * hundred declarations behind it has stopped being an example of the component.
   */
  private const val MAX_HELPER_CLOSURES = 8

  /** And no single one larger than this: past it the helper *is* the snippet. */
  private const val MAX_HELPER_BYTES = 4_000

  /**
   * Name → declaration across every scaffold source.
   *
   * A name declared more than once — in two of the files, or twice in one as an overload set — is
   * removed rather than resolved by order. A repo that publishes several catalogs lists all of
   * their scaffolding here, and quietly splicing one catalog's `counted` into another's snippet
   * would produce code that never ran anywhere.
   */
  private fun helperIndex(sources: List<String>): Map<String, Helper> {
    if (sources.isEmpty()) return emptyMap()
    val out = LinkedHashMap<String, Helper>()
    val ambiguous = mutableSetOf<String>()
    for (source in sources) {
      val lines = source.lines()
      val imports = importsOf(lines)
      val importMap = imports.associate { it.name to it.fqn }
      for (block in topLevelBlocks(lines)) {
        val name = block.name ?: continue
        if (out.put(name, Helper(block.text, imports, importMap)) != null) ambiguous.add(name)
      }
    }
    ambiguous.forEach { out.remove(it) }
    return out
  }

  /**
   * Clean whatever the seeds still call that a scaffold source declares, breadth-first, and return
   * those bodies in the order they were pulled in.
   *
   * [skip] carries the names that are somebody else's business: what the preview's own file already
   * declares (the same-file closure has it), and what the rules describe (a declared scaffold is
   * rewritten, never copied in — copying it would defeat the rule and re-introduce the machinery).
   */
  /**
   * The rename applied when a cross-file helper shares the entry preview's name.
   *
   * [helpers] is re-keyed so the closure emits the helper under [renamedTo]; [entryBody] is the
   * entry with its *calls* rewritten to match, its own declaration left alone.
   */
  private class EntryNameCollision(
    val helpers: Map<String, Helper>,
    val entryBody: String,
    val renamedTo: String,
  )

  /**
   * Rename a scaffold-source helper that collides with the entry preview's own name, or null when
   * there is nothing to rename.
   *
   * `skip` in [closeOverHelpers] carries every name the preview file declares, on the sound
   * reasoning that a local declaration already satisfies the reference. The entry is the one name
   * that reasoning fails for. After EXPAND runs, the entry's body is the *sticker's* body, and the
   * sticker's body calls the component — which in a delegating catalog can be the same name as the
   * preview that shows it. The checked-in m3 catalog is exactly this: `CatalogStates.kt` declares
   * `fun SegmentedToggle() = Sticker("segmentedbutton")`, and that slug's branch in
   * `CatalogComponents.kt` calls `SegmentedToggle()`. Treating the entry declaration as satisfying
   * the call emitted a preview whose body calls itself — unbounded recursion, reported as clean
   * with no residue.
   *
   * Renaming rather than inlining: the helper keeps its own shape, so a reader sees the component
   * as its author wrote it, and only the name moves. Inlining would flatten a component body into
   * the preview and lose that.
   */
  private fun resolveEntryNameCollision(
    entryName: String?,
    entryBody: String?,
    helpers: Map<String, Helper>,
    taken: Set<String>,
  ): EntryNameCollision? {
    if (entryName == null || entryBody == null) return null
    val helper = helpers[entryName] ?: return null
    // Declaring the name is not the problem; CALLING it is. A preview that merely shares a name
    // with something in the shared module, and never references it, needs nothing done.
    val callSites = callOccurrences(entryBody, entryName)
    if (callSites.isEmpty()) return null

    val renamed = freshName(entryName, taken + helpers.keys)
    // Every word occurrence in the helper — its declaration, and any recursion inside it.
    val renamedText =
      replaceAt(helper.text, wordOccurrences(helper.text, entryName), entryName, renamed)
    return EntryNameCollision(
      helpers = helpers - entryName + (renamed to helper.copy(text = renamedText)),
      entryBody = replaceAt(entryBody, callSites, entryName, renamed),
      renamedTo = renamed,
    )
  }

  /**
   * Offsets in [text] where [name] is *called* — followed by `(`, and not itself the `fun` header.
   *
   * The distinction is the whole point: the entry's own `fun SegmentedToggle()` must keep its name
   * (it is the preview the reader clicked), while the call that EXPAND put in its body must move to
   * the renamed helper.
   */
  private fun callOccurrences(text: String, name: String): List<Int> =
    wordOccurrences(text, name).filter { at ->
      val after = text.drop(at + name.length).takeWhile { it.isWhitespace() || it == '(' }
      if (!after.contains('(')) return@filter false
      val before = text.take(at).trimEnd()
      !before.endsWith("fun")
    }

  /** [text] with each offset in [at] (which must all start [from]) replaced by [to]. */
  private fun replaceAt(text: String, at: List<Int>, from: String, to: String): String {
    if (at.isEmpty()) return text
    val builder = StringBuilder(text)
    for (offset in at.sortedDescending()) builder.replace(offset, offset + from.length, to)
    return builder.toString()
  }

  /** `Name`, `NameComponent`, `NameComponent2`, … — the first not already spoken for. */
  private fun freshName(base: String, taken: Set<String>): String {
    val preferred = base + "Component"
    if (preferred !in taken) return preferred
    var suffix = 2
    while ("$preferred$suffix" in taken) suffix++
    return "$preferred$suffix"
  }

  private fun closeOverHelpers(
    seeds: Collection<String>,
    helpers: Map<String, Helper>,
    skip: Set<String>,
    rules: UsageRules,
    strings: Map<String, String>,
    residue: MutableSet<String>,
    addedImports: MutableSet<String>,
    parser: UsageSourceParser?,
    extraImports: MutableSet<Import>,
  ): List<String> {
    if (helpers.isEmpty()) return emptyList()
    val cleaned = LinkedHashMap<String, String>()
    val queue = ArrayDeque<String>()
    val queued = mutableSetOf<String>()
    fun enqueue(text: String) {
      for (name in helpers.keys) {
        if (name in skip || name in queued) continue
        // `mentionsWord` rejects a name preceded by `.`, which is right for a plain call — a
        // receiver chain must not be mistaken for a reference — and exactly wrong for an EXTENSION,
        // whose only call shape is `receiver.name(...)`. Both are checked, so an extension helper
        // is pulled in by the call that actually appears.
        if (mentionsWord(text, name) || mentionsExtensionCall(text, name)) {
          queued.add(name)
          queue.addLast(name)
        }
      }
    }
    seeds.forEach(::enqueue)
    while (queue.isNotEmpty() && cleaned.size < MAX_HELPER_CLOSURES) {
      val name = queue.removeFirst()
      val helper = helpers.getValue(name)
      // Too big to be an example. Left uncopied and named in the residue, so the note says the
      // snippet still refers to something it did not bring along.
      if (helper.text.length > MAX_HELPER_BYTES) {
        residue.add(name)
        continue
      }
      val body =
        cleanBlock(
          text = helper.text,
          rules = rules,
          imports = helper.importMap,
          strings = strings,
          isEntry = false,
          residue = residue,
          addedImports = addedImports,
          parser = parser,
          helpers = helpers,
          extraImports = extraImports,
        )
      cleaned[name] = body
      extraImports.addAll(helper.imports)
      enqueue(body)
    }
    // Whatever the cap left in the queue is still referenced and still not here; say so rather than
    // let the note claim a snippet that closes over everything it uses.
    queue.forEach { residue.add(it) }
    return cleaned.values.toList()
  }

  /**
   * Replace every call to a declared [UsageRules.Kind.EXPAND] helper with the helper's own body,
   * its parameters bound to the call's arguments.
   *
   * Iterative rather than recursive: an expansion may itself call another delegating helper
   * (`Sticker(id)` → `CatalogSticker { CatalogComponent(id) }` → the component), and each pass over
   * the text picks the next one up. A helper whose expansion still calls itself is declined instead
   * — that is a recursion this cannot terminate, and the guard alone would only bound how large the
   * damage got.
   */
  private fun expandDelegates(
    text: String,
    rules: UsageRules,
    helpers: Map<String, Helper>,
    residue: MutableSet<String>,
    extraImports: MutableSet<Import>,
  ): String {
    val expandable =
      rules.scaffolds
        .filterValues { it.kind == UsageRules.Kind.EXPAND }
        .keys
        .filter { helpers.containsKey(it) }
    if (expandable.isEmpty()) return text
    var out = text
    if (expandable.any { findCall(out, it) != null }) out = blockBodyForm(out)
    val declined = mutableSetOf<String>()
    var guard = 0
    while (guard++ < MAX_REWRITES) {
      val name = expandable.firstOrNull { it !in declined && findCall(out, it) != null } ?: break
      val helper = helpers.getValue(name)
      val call = findCall(out, name) ?: break
      val expansion = expandCall(helper, out.substring(call.argsStart + 1, call.argsEnd))
      if (expansion == null || mentionsWord(expansion, name)) {
        declined.add(name)
        residue.add(name)
        continue
      }
      out = spliceExpansion(out, call, expansion)
      extraImports.addAll(helper.imports)
    }
    return out
  }

  /**
   * `@Composable fun X() = Sticker("id")` → `@Composable fun X() { Sticker("id") }`.
   *
   * An expression body is a fine shape for a one-line delegation and an impossible one for what the
   * delegation expands *to*: a component body is several statements, and splicing them after an `=`
   * produces Kotlin that does not parse. Converting first is safe only where the declaration
   * returns `Unit`, so this requires a `@Composable` with **no declared return type** and leaves
   * every other expression body exactly as written.
   */
  private fun blockBodyForm(text: String): String {
    if (!mentionsWord(text, "@Composable")) return text
    val mask = codeMask(text)
    val head = findFunctionHead(text, mask) ?: return text
    val open = head.range.last
    val close = matchParen(text, open) ?: return text
    var i = close + 1
    while (i < text.length && text[i].isWhitespace()) i++
    // A `:` here is a declared return type, and `{` is already a block body. Only a bare `=` is the
    // Unit-returning expression body this may rewrite.
    if (i >= text.length || text[i] != '=' || text.getOrNull(i + 1) == '=') return text
    val body = text.substring(i + 1).trim()
    if (body.isEmpty()) return text
    val indent = head.range.first - (text.lastIndexOf('\n', head.range.first - 1) + 1)
    return text.substring(0, i) +
      "{\n" +
      reindent(body, indent + 2) +
      "\n" +
      " ".repeat(indent) +
      "}"
  }

  /**
   * Anchored at a line start ([RegexOption.MULTILINE]), which is not cosmetic: `fun` is three
   * lowercase letters, so the unanchored pattern's optional modifier run happily consumes it and
   * matches from the middle of the *previous* line — `@Composable\nfun X(` matched at `omposable`.
   * [Regex.findAll] returns non-overlapping matches, so that phantom swallowed the real declaration
   * and every helper came back unparseable.
   */
  private val FUNCTION_HEAD =
    Regex(
      """^$ANNOTATION_RUN(?:[a-z]+\s+)*fun\s+(?:<[^>]*>\s+)?[A-Za-z_][A-Za-z0-9_]*\s*\(""",
      RegexOption.MULTILINE,
    )

  /**
   * The declaration's own `fun Name(` — at a **code** position and at the start of its own line, so
   * a `fun` written in the KDoc above it is never mistaken for the thing that KDoc documents.
   */
  private fun findFunctionHead(text: String, mask: BooleanArray): MatchResult? =
    FUNCTION_HEAD.findAll(text).firstOrNull { match ->
      val at = match.range.first
      mask[at] && text.lastIndexOf('\n', at - 1) + 1 == at
    }

  /** A declared parameter: the name a body refers to it by, and its default if it has one. */
  private data class Param(val name: String, val default: String?)

  private data class FunctionDecl(val params: List<Param>, val body: String)

  /**
   * The body of the helper [helper] declares, with its parameters bound to [argsText].
   *
   * Null whenever the answer would be a guess — a declaration this cannot parse, an argument list
   * that does not bind, a parameter left with neither an argument nor a default, or a `when`
   * dispatch whose subject is not a literal. The caller then leaves the call alone and reports it.
   */
  private fun expandCall(helper: Helper, argsText: String): String? {
    val decl = parseFunction(helper.text) ?: return null
    val args =
      bindArguments(splitTopLevel(argsText).map { it.trim() }, decl.params.map { it.name })
        ?: return null
    val bound =
      decl.params.mapIndexed { i, p -> p.name to (args.getOrNull(i) ?: p.default) }.toMap()
    if (bound.values.any { it == null }) return null
    // A dispatch is all-or-nothing: either the one branch the call selects, or no expansion. Left
    // to fall through, a key this cannot pin to a literal would splice the *entire* component set
    // in — every branch of it — which is the one outcome worse than not expanding.
    val dispatch = loneWhen(decl.body)
    var body = if (dispatch == null) decl.body else dispatchBranch(dispatch, bound) ?: return null
    for ((name, value) in bound) body = replaceWord(body, name, value!!)
    // Normalised to column 0 as a block, never `trim`ped: trimming would strip the *first* line's
    // indent and leave every continuation line carrying the helper file's original column, so the
    // spliced body came out with its second line hanging eight spaces off its first.
    return reindent(body, 0).ifBlank { null }
  }

  private val WHEN_SUBJECT = Regex("""^when\s*\(\s*([A-Za-z_][A-Za-z0-9_]*)\s*\)\s*\{""")

  private val STRING_LITERAL = Regex("""^"(?:[^"\\]|\\.)*"$""")

  /** A body that is nothing but `when (<subject>) { … }` — a shared component set's dispatch. */
  private data class LoneWhen(val subject: String, val entries: List<Pair<String, String>>)

  /**
   * [body] read as a dispatch, or null when it is not one.
   *
   * *Lone* is the requirement: anything after the `when`'s closing brace means the helper does more
   * than dispatch, and keeping one branch of it would silently drop the rest.
   */
  private fun loneWhen(body: String): LoneWhen? {
    val trimmed = body.trim()
    val head = WHEN_SUBJECT.find(trimmed) ?: return null
    val open = head.range.last
    val close = matchBrace(trimmed, open) ?: return null
    if (trimmed.substring(close + 1).isNotBlank()) return null
    return LoneWhen(head.groupValues[1], whenEntries(trimmed.substring(open + 1, close)))
  }

  /**
   * The one branch of a shared component set's `when (id)` that [bound] selects, or null when it
   * selects none — a subject the call did not bind to a string literal, or a key with no branch and
   * no `else`.
   *
   * A catalog's component set is one such dispatch over several hundred branches, so this is the
   * difference between the snippet being the component and the snippet being the catalog.
   */
  private fun dispatchBranch(dispatch: LoneWhen, bound: Map<String, String?>): String? {
    val key = bound[dispatch.subject]?.trim() ?: return null
    if (!STRING_LITERAL.matches(key)) return null
    val match =
      dispatch.entries.firstOrNull { (conditions, _) ->
        splitTopLevel(conditions).any { it.trim() == key }
      } ?: dispatch.entries.firstOrNull { it.first.trim() == "else" } ?: return null
    // Normalised, not trimmed: `trim` would take the *first* line's indentation with it and leave
    // the rest of the branch carrying the component set's original column, which is the difference
    // between a branch that reads as code and one whose second line hangs eight spaces to the
    // right of its first.
    val branch = reindent(match.second, 0)
    // A braced branch body contributes its statements, not its braces.
    return if (branch.startsWith("{") && matchBrace(branch, 0) == branch.length - 1)
      branch.substring(1, branch.length - 1)
    else branch
  }

  /**
   * The `<conditions> -> <body>` entries of a `when` body, as text.
   *
   * A braced branch ends at its matching brace. An unbraced one ends at the next line indented no
   * further than the entry itself — ktfmt's own rule for continuing an expression, and the only
   * signal available without a parse. Comments between entries are skipped, which the m3 component
   * set has plenty of.
   */
  private fun whenEntries(body: String): List<Pair<String, String>> {
    val mask = codeMask(body)
    val out = mutableListOf<Pair<String, String>>()
    var i = 0
    while (i < body.length) {
      i = skipTrivia(body, i)
      if (i >= body.length) break
      val conditionStart = i
      val entryIndent = conditionStart - (body.lastIndexOf('\n', conditionStart - 1) + 1)
      val arrow = topLevelArrow(body, mask, i) ?: break
      val conditions = body.substring(conditionStart, arrow)
      var j = arrow + 2
      while (j < body.length && body[j].isWhitespace()) j++
      if (j >= body.length) break
      // A branch written *below* its `->` keeps its own line's indentation: the entry text starts
      // at the beginning of that line, not at its first token, so the dedent downstream can still
      // see what column the branch was written in.
      if (body[j] != '{') {
        val lineStart = body.lastIndexOf('\n', j - 1)
        if (lineStart > arrow) j = lineStart + 1
      }
      val end =
        if (body[j] == '{') (matchBrace(body, j) ?: break) + 1
        else expressionEnd(body, mask, j, entryIndent)
      out.add(conditions to body.substring(j, end))
      i = end
    }
    return out
  }

  /** Advances past whitespace and comments. */
  private fun skipTrivia(text: String, from: Int): Int {
    var i = from
    while (i < text.length) {
      when {
        text[i].isWhitespace() -> i++
        text.startsWith("//", i) -> i = text.indexOf('\n', i).takeIf { it >= 0 } ?: text.length
        text.startsWith("/*", i) -> i = blockCommentEnd(text, i)
        else -> return i
      }
    }
    return i
  }

  /** The `->` separating an entry's conditions from its body — at depth 0, so no lambda's. */
  private fun topLevelArrow(text: String, mask: BooleanArray, from: Int): Int? {
    var depth = 0
    var i = from
    while (i < text.length - 1) {
      if (mask[i]) {
        when (text[i]) {
          '(',
          '[',
          '{' -> depth++
          ')',
          ']',
          '}' -> depth--
          '-' -> if (depth == 0 && text[i + 1] == '>') return i
        }
      }
      i++
    }
    return null
  }

  private fun expressionEnd(
    text: String,
    mask: BooleanArray,
    from: Int,
    entryIndent: Int,
  ): Int {
    var depth = 0
    var i = from
    while (i < text.length) {
      if (mask[i]) {
        when (text[i]) {
          '(',
          '[',
          '{' -> depth++
          ')',
          ']',
          '}' -> {
            depth--
            if (depth < 0) return i
          }
        }
      }
      if (text[i] == '\n' && depth == 0) {
        val next = nextNonBlankIndent(text, i + 1)
        if (next == null || next <= entryIndent) return i
      }
      i++
    }
    return text.length
  }

  private fun nextNonBlankIndent(text: String, from: Int): Int? {
    var j = from
    while (j < text.length) {
      val end = text.indexOf('\n', j).takeIf { it >= 0 } ?: text.length
      val line = text.substring(j, end)
      if (line.isNotBlank()) return line.takeWhile { it == ' ' }.length
      j = end + 1
    }
    return null
  }

  /**
   * `fun Name(<params>) = <expr>` / `fun Name(<params>) { <body> }`, as parameters and body text.
   *
   * The declaration is found at a **code** position and at the start of its own line, so a `fun`
   * written inside the KDoc above it is not mistaken for the declaration it documents.
   */
  private fun parseFunction(text: String): FunctionDecl? {
    val mask = codeMask(text)
    val head = findFunctionHead(text, mask) ?: return null
    val open = head.range.last
    val close = matchParen(text, open) ?: return null
    val params =
      splitTopLevel(text.substring(open + 1, close))
        .mapNotNull { parseParam(it) }
        .ifEmpty { if (text.substring(open + 1, close).isBlank()) emptyList() else return null }
    var i = close + 1
    while (i < text.length && text[i].isWhitespace()) i++
    // Skip a declared return type: it may itself contain `->` and `<…>`, neither of which is the
    // body, so scan to the first `=` or `{` that is not inside one.
    if (i < text.length && text[i] == ':') {
      var depth = 0
      i++
      while (i < text.length) {
        if (mask[i]) {
          when (text[i]) {
            '(',
            '[',
            '<' -> depth++
            ')',
            ']',
            '>' -> depth--
            '{' -> if (depth <= 0) break
            '=' -> if (depth <= 0 && text.getOrNull(i + 1) != '=') break
          }
        }
        i++
      }
    }
    if (i >= text.length) return null
    return when (text[i]) {
      '=' -> FunctionDecl(params, text.substring(i + 1).trim())
      '{' -> {
        val end = matchBrace(text, i) ?: return null
        FunctionDecl(params, text.substring(i + 1, end))
      }
      else -> null
    }
  }

  /** `id: String`, `index: Int? = null`, `content: @Composable () -> Unit`. */
  private fun parseParam(text: String): Param? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return null
    val mask = codeMask(trimmed)
    val colon =
      trimmed.indices.firstOrNull { trimmed[it] == ':' && mask[it] && !inBrackets(trimmed, it) }
        ?: return null
    val name =
      Regex("""([A-Za-z_][A-Za-z0-9_]*)\s*$""")
        .find(trimmed.substring(0, colon))
        ?.groupValues
        ?.get(1) ?: return null
    val rest = trimmed.substring(colon + 1)
    val restMask = codeMask(rest)
    val eq =
      rest.indices.firstOrNull {
        rest[it] == '=' &&
          restMask[it] &&
          rest.getOrNull(it + 1) != '=' &&
          rest.getOrNull(it - 1) !in listOf('=', '!', '<', '>', '-') &&
          !inBrackets(rest, it)
      }
    return Param(name, eq?.let { rest.substring(it + 1).trim() })
  }

  private fun inBrackets(text: String, at: Int): Boolean {
    val mask = codeMask(text)
    var depth = 0
    for (i in 0 until at) {
      if (!mask[i]) continue
      when (text[i]) {
        '(',
        '[',
        '<',
        '{' -> depth++
        ')',
        ']',
        '>',
        '}' -> depth--
      }
    }
    return depth > 0
  }

  /**
   * Put [expansion] where the call was, indented to where the call sat.
   *
   * A one-line expansion replaces the call expression in place, as any substitution does. A
   * multi-line one cannot: the call may share its line with a lambda brace that opened before it
   * and closes after it (`CatalogSticker { CatalogComponent("x") }`), and simply pasting statements
   * into the middle of that line produces something that parses only by accident. So the line is
   * broken around it — what preceded the call, the expansion indented one level in, then what
   * followed.
   */
  private fun spliceExpansion(text: String, call: Call, expansion: String): String {
    val after = call.argsEnd + 1
    if (!expansion.contains('\n')) {
      return text.substring(0, call.start) + expansion + text.substring(after)
    }
    val lineStart = text.lastIndexOf('\n', call.start - 1) + 1
    val lineEnd = text.indexOf('\n', after).takeIf { it >= 0 } ?: text.length
    val prefix = text.substring(lineStart, call.start)
    val suffix = text.substring(after, lineEnd)
    val indent = prefix.takeWhile { it == ' ' }.length
    val head = if (prefix.isBlank()) "" else prefix.trimEnd() + "\n"
    val bodyIndent = if (prefix.isBlank()) indent else indent + 2
    val tail = if (suffix.isBlank()) "" else "\n" + " ".repeat(indent) + suffix.trimStart()
    return text.substring(0, lineStart) +
      head +
      reindent(expansion, bodyIndent) +
      tail +
      text.substring(lineEnd)
  }

  /** Re-indents a lifted body to [column], preserving its own internal shape. */
  private fun reindent(text: String, column: Int): String {
    val lines = text.lines().dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() }
    if (lines.isEmpty()) return ""
    val common =
      lines.filter { it.isNotBlank() }.minOfOrNull { line -> line.takeWhile { it == ' ' }.length }
        ?: 0
    val pad = " ".repeat(column)
    return lines.joinToString("\n") { if (it.isBlank()) "" else (pad + it.drop(common)).trimEnd() }
  }

  // ---------------------------------------------------------------------------------------------
  // Text mechanics. Every one of these is masked against string/comment content, so no pass can
  // rewrite inside a literal — the failure that makes naive source rewriting untrustworthy.
  // ---------------------------------------------------------------------------------------------

  private const val MAX_REWRITES = 64

  private const val MATERIAL3_SYSTEM_THEME_CALL =
    "androidx.compose.material3.MaterialTheme(colorScheme = if (androidx.compose.foundation.isSystemInDarkTheme()) androidx.compose.material3.darkColorScheme() else androidx.compose.material3.lightColorScheme())"

  private data class Call(val start: Int, val argsStart: Int, val argsEnd: Int)

  private data class Binding(val name: String, val arguments: List<String>, val lineRange: IntRange)

  /**
   * Marks each character as *code* (true) or as string/char/comment content (false). Handles line
   * comments, block comments, `'c'`, `"…"` with escapes, and raw `"""…"""` strings.
   */
  internal fun codeMask(text: String): BooleanArray {
    val mask = BooleanArray(text.length) { true }
    var i = 0
    while (i < text.length) {
      when {
        text.startsWith("//", i) -> {
          val end = text.indexOf('\n', i).takeIf { it >= 0 } ?: text.length
          for (k in i until end) mask[k] = false
          i = end
        }
        text.startsWith("/*", i) -> {
          val end = blockCommentEnd(text, i)
          for (k in i until end) mask[k] = false
          i = end
        }
        text.startsWith("\"\"\"", i) -> {
          val end = (text.indexOf("\"\"\"", i + 3).takeIf { it >= 0 }?.plus(3)) ?: text.length
          for (k in i until end) mask[k] = false
          i = end
        }
        text[i] == '"' || text[i] == '\'' -> {
          val quote = text[i]
          var k = i + 1
          while (k < text.length && text[k] != quote) {
            if (text[k] == '\\') k++
            k++
          }
          val end = minOf(k + 1, text.length)
          for (j in i until end) mask[j] = false
          i = end
        }
        else -> i++
      }
    }
    return mask
  }

  /** Whether [text] contains a non-whitespace code character rather than only comments. */
  private fun containsCode(text: String): Boolean {
    var i = 0
    while (i < text.length) {
      when {
        text[i].isWhitespace() -> i++
        text.startsWith("//", i) -> i = text.indexOf('\n', i).takeIf { it >= 0 } ?: text.length
        text.startsWith("/*", i) -> i = blockCommentEnd(text, i)
        else -> return true
      }
    }
    return false
  }

  private fun isIdentifierChar(c: Char) = c.isLetterOrDigit() || c == '_'

  /** Kotlin block comments nest; return the terminator paired with the opener at [start]. */
  private fun blockCommentEnd(text: String, start: Int): Int {
    var depth = 1
    var i = start + 2
    while (i < text.length - 1) {
      when {
        text.startsWith("/*", i) -> {
          depth++
          i += 2
        }
        text.startsWith("*/", i) -> {
          depth--
          i += 2
          if (depth == 0) return i
        }
        else -> i++
      }
    }
    return text.length
  }

  /** Occurrences of [word] at code positions, bounded by non-identifier characters. */
  private fun wordOccurrences(text: String, word: String): List<Int> {
    if (word.isEmpty()) return emptyList()
    val mask = codeMask(text)
    val out = mutableListOf<Int>()
    var from = 0
    while (true) {
      val at = text.indexOf(word, from).takeIf { it >= 0 } ?: return out
      from = at + 1
      if (!mask[at]) continue
      val head = word.first()
      val before = text.getOrNull(at - 1)
      val after = text.getOrNull(at + word.length)
      val leftOk =
        if (isIdentifierChar(head)) before?.let { !isIdentifierChar(it) && it != '.' } ?: true
        else true
      val rightOk = after?.let { !isIdentifierChar(it) } ?: true
      if (leftOk && rightOk) out.add(at)
    }
  }

  internal fun mentionsWord(text: String, word: String): Boolean =
    wordOccurrences(text, word).isNotEmpty()

  /**
   * Whether [text] calls [name] as an extension — `receiver.name(`, at any depth of receiver chain.
   *
   * Deliberately narrow: it requires the call parentheses, so a plain property read on an unrelated
   * receiver (`state.morph`) does not drag a same-named function in. Over-matching here costs a
   * helper the snippet did not need; under-matching costs an unresolved call reported as clean,
   * which is the failure this class exists to prevent.
   */
  internal fun mentionsExtensionCall(text: String, name: String): Boolean {
    val mask = codeMask(text)
    var from = 0
    while (true) {
      val at = text.indexOf(name, from).takeIf { it >= 0 } ?: return false
      from = at + 1
      if (!mask[at]) continue
      if (text.getOrNull(at - 1) != '.') continue
      // The character before the `.` has to end an expression, or this is a package qualifier.
      val beforeDot = text.getOrNull(at - 2)
      if (
        beforeDot != null && !isIdentifierChar(beforeDot) && beforeDot != ')' && beforeDot != ']'
      ) {
        continue
      }
      val gap = text.drop(at + name.length).takeWhile { it.isWhitespace() }
      if (text.getOrNull(at + name.length + gap.length) == '(') return true
    }
  }

  /**
   * Whether [text] still calls [name] through **any** qualifier — `com.acme.counted(…)`.
   *
   * This is the residue half of [unqualifyScaffoldCalls]. That pass only rewrites a call whose
   * package the rules named, which is right (a receiver chain must not be stripped on a
   * resemblance) but leaves everything else unrewritten — and [mentionsWord] rejects a name
   * preceded by `.`, so nothing reported it either. A declared scaffold could therefore survive
   * into a seed marked *cleaned*, which is the failure this class exists to make impossible.
   *
   * Deliberately not trying to tell a package from a receiver: it cannot, without the resolution
   * this pass does not have. So it over-reports — `state.metrics.counted(…)` lands in residue too.
   * A false residue costs a note saying a helper may not have been rewritten; a false silence costs
   * a snippet advertised as runnable that does not compile. Only one of those is worth avoiding.
   */
  private fun mentionsQualifiedCall(text: String, name: String): Boolean {
    if (name.isEmpty()) return false
    val mask = codeMask(text)
    val qualified =
      Regex("""(?<![A-Za-z0-9_])(?:[A-Za-z_][A-Za-z0-9_]*\.)+${Regex.escape(name)}(?=\s*[({])""")
    return qualified.findAll(text).any { mask[it.range.first] }
  }

  /**
   * Whether [text] refers to [name] *at all*, including as the member half of a qualified
   * expression — which is what deciding an import's fate requires, and what [mentionsWord] must not
   * do.
   *
   * [mentionsWord] rejects an occurrence preceded by `.`, correctly: it drives the rewrites, and
   * `foo.counted` is not the `counted` a scaffold rule means. But Compose is built on imported
   * extensions used through receiver syntax — `Modifier.padding(16.dp)`, `16.dp`,
   * `Modifier.height(…)` — where every reference to the imported name follows a dot. Pruning on the
   * strict test therefore deleted `import androidx.compose.foundation.layout.padding` and `import
   * androidx.compose.ui.unit.dp` out from under a body that still used them, producing a seed
   * advertised as runnable that did not compile, with no residue to say so.
   *
   * Erring the other way costs an unused import — a warning, not a failure — which is the direction
   * this whole pass is supposed to fail in.
   */
  private fun mentionsIdentifier(text: String, name: String): Boolean {
    val mask = codeMask(text)
    var from = 0
    while (true) {
      val at = text.indexOf(name, from).takeIf { it >= 0 } ?: return false
      from = at + 1
      if (!mask[at]) continue
      val before = text.getOrNull(at - 1)
      val after = text.getOrNull(at + name.length)
      if (before?.let { isIdentifierChar(it) } == true) continue
      if (after?.let { isIdentifierChar(it) } == true) continue
      return true
    }
  }

  private fun replaceWord(text: String, word: String, replacement: String): String {
    val hits = wordOccurrences(text, word)
    if (hits.isEmpty()) return text
    val sb = StringBuilder()
    var last = 0
    for (at in hits) {
      sb.append(text, last, at).append(replacement)
      last = at + word.length
    }
    sb.append(text, last, text.length)
    return sb.toString()
  }

  private fun findCall(text: String, name: String): Call? = findCalls(text, name).firstOrNull()

  private fun findCalls(text: String, name: String): List<Call> =
    wordOccurrences(text, name).mapNotNull { at ->
      var k = at + name.length
      while (k < text.length && text[k].isWhitespace()) k++
      if (k < text.length && text[k] == '(') matchParen(text, k)?.let { Call(at, k, it) } else null
    }

  /**
   * `val <name> = <scaffold>(<args>)` — the binding, its arguments, and the lines it occupies, or
   * **null when the call is not bound to a `val` at all**.
   *
   * Reporting an unbound call as a nameless binding, as this first did, was a real bug and not a
   * tidy default. Both callers delete `lineRange`, and for a direct call that range is the whole
   * physical line the call sits on — so a ktfmt-legal one-liner `Button(onClick = {}, enabled =
   * catalogEnabled()) { … }` was deleted **whole** rather than merely losing its `enabled`
   * argument, and the cleaner then returned an empty themed preview with no residue to show for it.
   * It looked right on the fixture only because ktfmt had wrapped that call and put every knob on a
   * line of its own, where deleting the line happens to be the correct answer.
   *
   * An unbound call is not this function's business: [filterCallArguments] removes it where it sits
   * in a named argument, and the survivor check reports it where it does not.
   */
  private fun findValBinding(text: String, scaffold: String): Binding? {
    for (call in findCalls(text, scaffold)) {
      val lineStart = text.lastIndexOf('\n', call.start - 1) + 1
      val prefix = text.substring(lineStart, call.start)
      val name =
        Regex("""^\s*val\s+([A-Za-z_][A-Za-z0-9_]*)\s*=\s*$""").find(prefix)?.groupValues?.get(1)
          ?: continue
      val args =
        splitTopLevel(text.substring(call.argsStart + 1, call.argsEnd)).map {
          it.trim(',', ' ', '\n')
        }
      val firstLine = text.substring(0, lineStart).count { it == '\n' }
      val lastLine = text.substring(0, call.argsEnd).count { it == '\n' }
      return Binding(name, args, firstLine..lastLine)
    }
    return null
  }

  private fun matchParen(text: String, open: Int): Int? = matchDelimiter(text, open, '(', ')')

  private fun matchBrace(text: String, open: Int): Int? = matchDelimiter(text, open, '{', '}')

  private fun matchDelimiter(text: String, open: Int, o: Char, c: Char): Int? {
    val mask = codeMask(text)
    var depth = 0
    for (i in open until text.length) {
      if (!mask[i]) continue
      if (text[i] == o) depth++
      if (text[i] == c) {
        depth--
        if (depth == 0) return i
      }
    }
    return null
  }

  /** Splits an argument list on top-level commas, keeping each argument's own text intact. */
  private fun splitTopLevel(args: String): List<String> {
    val mask = codeMask(args)
    val out = mutableListOf<String>()
    var depth = 0
    var start = 0
    for (i in args.indices) {
      if (!mask[i]) continue
      when (args[i]) {
        '(',
        '[',
        '{' -> depth++
        ')',
        ']',
        '}' -> depth--
        ',' ->
          if (depth == 0) {
            out.add(args.substring(start, i))
            start = i + 1
          }
      }
    }
    if (start <= args.lastIndex) out.add(args.substring(start))
    return out.filter { it.isNotBlank() }
  }

  /**
   * Drops arguments matching [shouldDrop] from every call in [text], and collapses a call left with
   * a single short argument back onto one line — so a wrapped four-argument `Button(…)` whose knobs
   * were all catalog machinery comes out as `Button(onClick = {})` rather than as a two-line husk.
   */
  private fun filterCallArguments(text: String, shouldDrop: (String) -> Boolean): String {
    var out = text
    var searchFrom = 0
    var guard = 0
    while (guard++ < MAX_REWRITES * 4) {
      val mask = codeMask(out)
      val open = (searchFrom until out.length).firstOrNull { out[it] == '(' && mask[it] } ?: break
      val close = matchParen(out, open)
      if (close == null) {
        searchFrom = open + 1
        continue
      }
      val inner = out.substring(open + 1, close)
      val args = splitTopLevel(inner)
      val keep = args.filterNot { shouldDrop(it) }
      if (keep.size == args.size) {
        searchFrom = open + 1
        continue
      }
      val rendered =
        when {
          keep.isEmpty() -> ""
          // `.trim()` first: a surviving argument lifted out of a wrapped call still carries the
          // newline and indent that put it on its own line, and testing before trimming would keep
          // every collapsible call in its multi-line husk.
          keep.size == 1 && !keep[0].trim().contains('\n') -> keep[0].trim()
          else -> keep.joinToString(",") + ","
        }
      out = out.substring(0, open + 1) + rendered + out.substring(close)
      searchFrom = open + 1
    }
    return out
  }

  private fun indentOf(text: String, at: Int): Int {
    val lineStart = text.lastIndexOf('\n', at - 1) + 1
    return text.substring(lineStart, at).takeWhile { it == ' ' }.length
  }

  private fun removeLines(text: String, range: IntRange): String {
    val lines = text.lines()
    return lines.filterIndexed { i, _ -> i !in range }.joinToString("\n")
  }
}
