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
  ): Result? {
    if (bodyLine == null) return null
    val lines = source.lines()
    if (bodyLine < 1 || bodyLine > lines.size) return null
    if (lines[bodyLine - 1].isBlank()) return null

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
        )
      cleanedByIndex[index] = cleaned
      for ((name, at) in declaredAt) {
        if (at != index && at !in seen && mentionsWord(cleaned, name)) queue.addLast(at)
      }
    }

    // Entry first, then its helpers in file order — a reader wants the composable they clicked at
    // the top, not after two private helpers they did not ask about.
    val bodies = buildList {
      add(cleanedByIndex.getValue(entryIndex))
      cleanedByIndex.keys
        .sorted()
        .filter { it != entryIndex }
        .forEach { add(cleanedByIndex.getValue(it)) }
    }
    val body = bodies.joinToString("\n\n").trimEnd()
    if (body.isBlank()) return null

    val header = headerFor(lines, imports, body, addedImports, rules, residue)
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
  private val DECLARATION =
    Regex(
      """^(?:[a-z]+\s+)*(?:fun|val|var|class|object|interface|typealias)\s+(?:<[^>]*>\s+)?([A-Za-z_][A-Za-z0-9_]*)"""
    )

  /**
   * The name a declaration block introduces. Scans for the first column-0 declaration line rather
   * than examining one candidate: a block opens with KDoc and an annotation stack, and a multi-line
   * annotation's continuation lines (` id = "Button/Filled",`) look like neither an annotation nor
   * a declaration.
   */
  private fun declaredName(text: String): String? =
    text.lines().firstNotNullOfOrNull { DECLARATION.find(it)?.groupValues?.get(1) }

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
  ): String {
    // Kept whole, by paren balance rather than by line. A ktfmt-wrapped
    // `@file:OptIn(\n  A::class,\n  B::class,\n)` is one annotation across five lines, and a
    // line-at-a-time filter would emit its opening line alone — an unterminated annotation, and a
    // header that then prunes the imports only its discarded arguments referenced.
    val fileAnnotations =
      annotationBlocks(lines.takeWhile { !it.trimStart().startsWith("package ") })
        .filterNot { isScaffoldAnnotation(it.name, imports, rules) }
        .map { it.text }
    val kept =
      importsOf(lines).filter { import ->
        if (isScaffoldPackage(import.fqn, rules)) {
          // A scaffold import that is still referenced means a rule is missing, not that the import
          // should be kept — record it and drop it, so the residue names the gap.
          if (mentionsIdentifier(body, import.name)) residue.add(import.name)
          false
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
  ): String {
    var out = stripScaffoldAnnotations(text, imports, rules)
    out = inlineStringResources(out, strings)
    // Before anything matches on a helper name: a call written fully qualified is the same call.
    out = unqualifyScaffoldCalls(out, rules)
    // Order matters. UNWRAP first, so a wrapper takes its own arguments away with it before DROP
    // starts reasoning about which arguments mention a dropped binding. INLINE next, so a member
    // substitution lands before DROP inspects the argument it sits in. DROP, then RENAME last —
    // renaming early would hide a name the other passes match on.
    out = applyUnwrap(out, rules)
    // The parse settles argument binding, trailing-lambda calls and qualifiers; the text pass is
    // the fallback for a host with no staged sidecar (see [UsageSourceParser]).
    out =
      if (parser != null) applySubstituteParsed(out, rules, addedImports, parser)
      else applySubstitute(out, rules, addedImports)
    // After SUBSTITUTE, so a knob in the initializer (`toggleable(catalogEnabled())`) is already
    // plain by the time the state declaration quotes it. Parsed-only by design — see
    // [UsageRules.Kind.DESTRUCTURE].
    if (parser != null) out = applyDestructureParsed(out, rules, addedImports, parser)
    out = applyInline(out, rules)
    out = applyDrop(out, rules, residue)
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
    if (rules.scaffolds.isEmpty() || rules.scaffoldPackages.isEmpty()) return text
    val names = rules.scaffolds.keys.joinToString("|") { Regex.escape(it) }
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
      }
      i = end + 1
    }
    return out.joinToString("\n")
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
      if (scaffold.kind != UsageRules.Kind.RENAME) continue
      val to = scaffold.renameTo ?: continue
      if (!mentionsWord(out, name)) continue
      out = replaceWord(out, name, to)
      scaffold.addImport?.let { addedImports.add(it) }
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
        val call = findCall(out, name) ?: break
        val lambdaOpen = out.indexOf('{', call.argsEnd).takeIf { it >= 0 } ?: break
        val lambdaClose = matchBrace(out, lambdaOpen) ?: break
        val inner = out.substring(lambdaOpen + 1, lambdaClose)
        val callIndent = indentOf(out, call.start)
        val lineStart = out.lastIndexOf('\n', call.start - 1) + 1
        val prefix = out.substring(lineStart, call.start)
        val body = dedent(inner, callIndent)
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
          else out.substring(0, call.start) + body.trimStart() + out.substring(lambdaClose + 1)
      }
    }
    return out
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
   * `val (checked, onCheckedChange) = toggleable(true)` → `var checked by remember {
   * mutableStateOf(true) }`, with every use of `onCheckedChange` becoming `{ checked = it }`.
   *
   * ### Why this one could not be a text pass
   *
   * The other kinds rewrite an expression or delete a binding. This replaces a **declaration** and
   * rebinds a *second* name that the declaration introduced — so it has to know the destructuring
   * exists, which names it binds, in what order, and where its initializer starts and ends. A regex
   * that thought it knew those things is how this file earned most of its review findings, which is
   * why the kind is gated on the parser being staged rather than degrading to a guess.
   *
   * ### All or nothing, per declaration
   *
   * The same discipline [applyDrop] uses. If the template cites an argument the call does not have,
   * or the declaration binds a shape the rule does not describe (one name, or three), the whole
   * rewrite is abandoned for that declaration and the helper is left exactly as the catalog wrote
   * it — reported as residue. A half-applied state rewrite compiles to something subtly wrong,
   * which is worse than a snippet that visibly still calls a helper.
   */
  private fun applyDestructureParsed(
    text: String,
    rules: UsageRules,
    addedImports: MutableSet<String>,
    parser: UsageSourceParser,
  ): String {
    if (rules.scaffolds.none { it.value.kind == UsageRules.Kind.DESTRUCTURE }) return text
    var out = text
    var guard = 0
    while (guard++ < MAX_REWRITES) {
      val facts = parser.facts(out) ?: return out
      val rewrite =
        facts.destructurings
          .asSequence()
          .mapNotNull { declaration ->
            // The initializer, as a call: the facts report the two separately, and the call is what
            // carries the callee, its arguments and its receiver.
            val call =
              facts.calls.firstOrNull {
                it.replaceStart == declaration.initializerStart ||
                  it.start == declaration.initializerStart
              } ?: return@mapNotNull null
            val scaffold = rules.scaffolds[call.callee] ?: return@mapNotNull null
            if (scaffold.kind != UsageRules.Kind.DESTRUCTURE) return@mapNotNull null
            // Same guard the substitution pass carries: a matching name on somebody else's receiver
            // is not this scaffold.
            if (call.receiver != null && call.receiver !in rules.scaffoldPackages) {
              return@mapNotNull null
            }
            val plain = scaffold.plain ?: return@mapNotNull null
            // Exactly the value/setter pair the kind describes. Anything else is a shape the rule
            // does not speak for.
            if (declaration.names.size != 2) return@mapNotNull null
            val (value, setterName) = declaration.names
            if (value.isEmpty() || setterName.isEmpty()) return@mapNotNull null
            val args = facts.bind(call, scaffold.params) ?: return@mapNotNull null
            val render = { template: String ->
              Regex("""\$(\d+)|\${'$'}value|\${'$'}setter""").replace(template) { m ->
                when (m.value) {
                  "\$value" -> value
                  "\$setter" -> setterName
                  else -> args.getOrNull(m.groupValues[1].toInt()) ?: m.value
                }
              }
            }
            val declarationText = render(plain)
            val setterText = scaffold.setter?.let(render)
            if (declarationText.contains(Regex("""\${'$'}\d"""))) return@mapNotNull null
            if (setterText?.contains(Regex("""\${'$'}\d""")) == true) return@mapNotNull null
            Triple(declaration, declarationText to setterText, scaffold)
          }
          .firstOrNull() ?: return out

      val (declaration, replacements, scaffold) = rewrite
      val (declarationText, setterText) = replacements
      if (declaration.start < 0 || declaration.end > out.length) return out

      // The declaration and every reference to the setter name, from **one** parse, applied
      // right-to-left so earlier offsets stay valid. Not `replaceWord`: that also rewrites the
      // argument *label* in `Switch(onCheckedChange = onCheckedChange)`, which produced
      // `{ checked = it } = { checked = it }` — the parse separates a label from a reference and a
      // word scan cannot.
      val edits = buildList {
        add(declaration.start to declaration.end to declarationText)
        if (setterText != null) {
          facts.references
            .filter { it.name == declaration.names[1] && it.start >= declaration.end }
            .forEach { add(it.start to it.end to setterText) }
        }
      }
      for ((range, replacement) in edits.sortedByDescending { it.first.first }) {
        val (start, end) = range
        if (start < 0 || end > out.length || start >= end) return out
        out = out.substring(0, start) + replacement + out.substring(end)
      }
      scaffold.addImport?.let { addedImports.add(it) }
      addedImports.addAll(scaffold.addImports)
    }
    return out
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
      replacement.second.addImport?.let { addedImports.add(it) }
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
        scaffold.addImport?.let { addedImports.add(it) }
      }
    }
    return out
  }

  /**
   * `val c = counted("Filled")` + `c.onClick` + `c.label` → `{}` + `"Filled"`, with the binding
   * line deleted.
   */
  private fun applyInline(text: String, rules: UsageRules): String {
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
  // Text mechanics. Every one of these is masked against string/comment content, so no pass can
  // rewrite inside a literal — the failure that makes naive source rewriting untrustworthy.
  // ---------------------------------------------------------------------------------------------

  private const val MAX_REWRITES = 64

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
          val end = (text.indexOf("*/", i + 2).takeIf { it >= 0 }?.plus(2)) ?: text.length
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

  private fun isIdentifierChar(c: Char) = c.isLetterOrDigit() || c == '_'

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

  /** Re-indents a lambda body lifted out of its wrapper, to the column the wrapper sat at. */
  private fun dedent(inner: String, toColumn: Int): String {
    val lines = inner.lines().filter { it.isNotBlank() }
    if (lines.isEmpty()) return ""
    val common = lines.minOf { line -> line.takeWhile { it == ' ' }.length }
    val shift = common - toColumn
    return lines.joinToString("\n") { line ->
      if (shift > 0) line.drop(minOf(shift, line.takeWhile { it == ' ' }.length)) else line
    }
  }

  private fun removeLines(text: String, range: IntRange): String {
    val lines = text.lines()
    return lines.filterIndexed { i, _ -> i !in range }.joinToString("\n")
  }
}
