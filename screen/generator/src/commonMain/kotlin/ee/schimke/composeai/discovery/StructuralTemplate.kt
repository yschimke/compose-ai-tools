package ee.schimke.composeai.discovery

/**
 * The hole-filler a catalog's structural code templates are rendered by.
 *
 * ## What it exists for
 *
 * A record can print a *call site*: `Text(text = "Hello")`. It cannot print the structure a screen
 * needs around those call sites, because that structure is not in any signature — `ScreenScaffold`
 * takes a scroll state that has to be the same object the `TransformingLazyColumn` inside it holds,
 * `SurfaceTransformation(spec)` is declared on `TransformingLazyColumnItemScope` and can only be
 * written inside `item { }`, a dialog is a sibling of the scaffold rather than a child, and a
 * `CheckboxButton` without a hoisted `remember` is a picture of a checkbox.
 *
 * The preview server writes that structure today in 1,406 lines of Kotlin, per catalog, for
 * components it has never compiled. The catalog contract moves it into the catalog repository as
 * **templates**: Kotlin fragments with named holes, published in `ui-builder.policy.json`, one per
 * structural role. This is what fills the holes.
 *
 * ## A hole-filler, not a language
 *
 * Two hole kinds and no third:
 *
 * - `${name}` — substitute the value the caller resolved for `name`.
 * - `${call(modifier = "Modifier.padding(8.dp)")}` — a record call site with named argument
 *   overrides, so a template can reach a parameter the surrounding structure requires without
 *   knowing which component sits in the hole.
 *
 * **No conditionals, no loops, no expressions.** A template that would need one is two roles, and
 * adding a role is a change to the engine with a test rather than a capability every catalog then
 * ships a compiler for. That restriction is the entire argument for templates-as-data over an
 * emitter published as a jar: the builder can *validate* what a catalog asks for, and the compile
 * and render round trip is the validation.
 *
 * ## Indentation is the whole reason this is not `String.replace`
 *
 * A hole is nearly always filled with several lines, and the output is Kotlin somebody reads:
 * ```
 * AppScaffold {
 *   ScreenScaffold(scrollState = ${listState}) {
 *     ${content}
 *   }
 * }
 * ```
 *
 * `${content}` sits at four spaces, and a naive replace would indent the substituted block's first
 * line and leave every other line at column zero. So a hole that begins a line (whitespace only
 * before it) re-indents every continuation line of its value to that hole's column. A hole in the
 * middle of a line does not: there is no single column to align to, and guessing one produces
 * output that is worse than unindented.
 */
object StructuralTemplate {

  /** One hole in a template. */
  sealed interface Hole {
    /** `${name}` — a named value the caller resolves. */
    data class Named(val name: String) : Hole

    /**
     * `${call(...)}` — a record call site, with named argument overrides the structure requires.
     *
     * The overrides are carried as **source text**, unparsed: `transformation =
     * "SurfaceTransformation(spec)"` is a Kotlin expression the catalog wrote, and this engine is
     * not the thing that decides whether it is a valid one. The compile round trip is.
     */
    data class Call(val overrides: Map<String, String>) : Hole
  }

  /** A rendered template, or every reason it could not be rendered. */
  sealed interface Result {
    data class Emitted(val source: String) : Result

    /** Every problem, not just the first — an author wants the whole list to act on. */
    data class Refused(val reasons: List<String>) : Result
  }

  /**
   * The holes [template] declares, in order, or the reasons it cannot be read.
   *
   * Split out from [render] because a catalog's templates are validated when the catalog is
   * *published*, long before any design is exported through them. A policy naming a hole the
   * builder will never resolve should be reported to the person editing the policy, not to the
   * person who later drew a screen.
   */
  fun holes(template: String): Result2<List<Hole>> {
    val holes = mutableListOf<Hole>()
    val reasons = mutableListOf<String>()
    scan(template) { hole, reason ->
      if (hole != null) holes += hole
      if (reason != null) reasons += reason
    }
    return if (reasons.isEmpty()) Result2.Ok(holes) else Result2.Failed(reasons)
  }

  /**
   * Render [template], asking [resolve] for each hole's value.
   *
   * [resolve] returns null for a hole it cannot fill, which becomes a refusal naming the hole
   * rather than an empty string: a screen root whose `content` silently vanished compiles, renders
   * an empty box, and is the worst possible outcome for an export somebody is about to paste into
   * an IDE.
   */
  fun render(template: String, resolve: (Hole) -> String?): Result {
    val out = StringBuilder()
    val reasons = mutableListOf<String>()
    var cursor = 0

    scanWithSpans(template) { span, hole, reason ->
      if (reason != null) {
        reasons += reason
        return@scanWithSpans
      }
      out.append(template, cursor, span.first)
      cursor = span.last + 1
      val resolved = resolve(hole!!)
      if (resolved == null) {
        reasons += "no value for ${describe(hole)}"
        return@scanWithSpans
      }
      out.append(indentContinuations(resolved, holeIndent(template, span.first)))
    }

    if (reasons.isNotEmpty()) return Result.Refused(reasons)
    out.append(template, cursor, template.length)
    return Result.Emitted(out.toString())
  }

  /** [holes]'s result type. Not [Result], which carries rendered source rather than a value. */
  sealed interface Result2<out T> {
    data class Ok<T>(val value: T) : Result2<T>

    data class Failed(val reasons: List<String>) : Result2<Nothing>
  }

  private fun describe(hole: Hole): String =
    when (hole) {
      is Hole.Named -> "\${${hole.name}}"
      is Hole.Call -> "\${call(…)}"
    }

  /**
   * The column a hole starting a line sits at, or null when it does not start one.
   *
   * "Starts a line" means only whitespace between the previous newline and the hole. A hole
   * anywhere else has no column its value's continuation lines could be aligned to — the text
   * before it on that line is not indentation — so nothing is added and the value is emitted as
   * written.
   */
  private fun holeIndent(template: String, start: Int): String? {
    var index = start - 1
    while (index >= 0 && (template[index] == ' ' || template[index] == '\t')) index--
    if (index >= 0 && template[index] != '\n') return null
    return template.substring(index + 1, start)
  }

  /** Every line of [value] after the first, prefixed with [indent]. Blank lines stay blank. */
  private fun indentContinuations(value: String, indent: String?): String {
    if (indent.isNullOrEmpty() || '\n' !in value) return value
    return value
      .lineSequence()
      .mapIndexed { index, line ->
        when {
          index == 0 -> line
          line.isBlank() -> line
          else -> indent + line
        }
      }
      .joinToString("\n")
  }

  private inline fun scan(template: String, report: (Hole?, String?) -> Unit) {
    scanWithSpans(template) { _, hole, reason -> report(hole, reason) }
  }

  /**
   * Walk `${…}` occurrences, reporting each as a hole plus the span it occupies.
   *
   * A `$` not followed by `{` is ordinary text — Kotlin templates are full of `$` — so only `${`
   * opens a hole. Braces nest and string literals are respected, because a call override's value
   * routinely contains both: `${call(modifier = "Modifier.padding(if (x) 8.dp else 0.dp)")}`.
   */
  private inline fun scanWithSpans(
    template: String,
    report: (IntRange, Hole?, String?) -> Unit,
  ) {
    var index = 0
    while (index < template.length - 1) {
      if (template[index] != '$' || template[index + 1] != '{') {
        index++
        continue
      }
      val end = closingBrace(template, index + 1)
      if (end < 0) {
        report(index..index, null, "unterminated \${ at offset $index")
        return
      }
      val body = template.substring(index + 2, end).trim()
      val span = index..end
      when {
        body.isEmpty() -> report(span, null, "empty \${} at offset $index")
        body.startsWith("call(") && body.endsWith(")") ->
          when (val overrides = parseOverrides(body.substring(5, body.length - 1))) {
            null -> report(span, null, "malformed \${call(...)} at offset $index: $body")
            else -> report(span, Hole.Call(overrides), null)
          }
        body.startsWith("call") -> report(span, null, "malformed \${call} at offset $index: $body")
        isName(body) -> report(span, Hole.Named(body), null)
        else -> report(span, null, "\${$body} is not a name or a call at offset $index")
      }
      index = end + 1
    }
  }

  /**
   * Index of the `}` closing the `{` at [open], respecting nesting, string literals AND character
   * literals.
   *
   * The third scanner in this file that has to know what a quote is, and the one that runs FIRST —
   * so `${'$'}{call(separator = '}')}` ended here, at the brace inside the char literal, and was
   * rejected as malformed before the argument splitter's own handling could ever see it. Fixing the
   * two later scanners without this one left the feature exactly as broken for the input that
   * motivated the fix.
   */
  /**
   * How many characters of quoting start at [index]: 3 for `\"\"\"`, 1 for `"`, 0 otherwise.
   *
   * One place, because there are THREE scanners in this file that have to agree about what a quote
   * is — [closingBrace], [splitTopLevel] and [topLevelEquals] — and they have twice been found
   * disagreeing, each having been taught separately. A triple quote must be tested before a single
   * one, or `\"\"\"` reads as an empty string followed by a stray quote and every state after it
   * inverts.
   */
  /**
   * Where a Kotlin comment starting at [index] ends, or -1 when none starts there.
   *
   * The same "one place, three scanners" argument as [quoteAt], and the same bug: [closingBrace],
   * [splitTopLevel] and [topLevelEquals] each tracked quotes and none tracked comments, so
   * `${'$'}{call(content = { /* } */ Text("x") })}` read the commented brace as syntax, took the
   * lambda's closing brace for the end of the hole, and rejected valid Kotlin. An override's value
   * is documented as arbitrary Kotlin source, so a comment in it is ordinary rather than exotic.
   *
   * Block comments NEST in Kotlin, unlike C — `/* /* */ */` is one comment — so this counts rather
   * than searching for the first `*` + `/`. An unterminated comment runs to the end of the text,
   * which is what a compiler would say about it too.
   */
  private fun commentEnd(text: String, index: Int): Int {
    if (index + 1 >= text.length || text[index] != '/') return -1
    if (text[index + 1] == '/') {
      val newline = text.indexOf('\n', index + 2)
      return if (newline < 0) text.length else newline
    }
    if (text[index + 1] != '*') return -1
    var depth = 0
    var scan = index
    while (scan + 1 < text.length) {
      if (text[scan] == '/' && text[scan + 1] == '*') {
        depth++
        scan += 2
      } else if (text[scan] == '*' && text[scan + 1] == '/') {
        depth--
        scan += 2
        if (depth == 0) return scan
      } else {
        scan++
      }
    }
    return text.length
  }

  private fun quoteAt(text: String, index: Int): Int =
    if (text.startsWith("\"\"\"", index)) 3 else if (text[index] == '"') 1 else 0

  private fun closingBrace(template: String, open: Int): Int {
    var depth = 0
    var index = open
    var inString = false
    var inRaw = false
    var inChar = false
    while (index < template.length) {
      val ch = template[index]
      val quote = if (inChar) 0 else quoteAt(template, index)
      when {
        // A raw string has no escapes at all, so a backslash inside one is an ordinary character.
        (inString || inChar) && ch == '\\' -> index++
        inRaw && quote == 3 -> {
          inRaw = false
          index += 2
        }
        inRaw -> Unit
        quote == 3 && !inString -> {
          inRaw = true
          index += 2
        }
        quote == 1 && !inChar -> inString = !inString
        ch == '\'' && !inString -> inChar = !inChar
        inString || inChar -> Unit
        commentEnd(template, index) >= 0 -> {
          index = commentEnd(template, index)
          continue
        }
        ch == '{' -> depth++
        ch == '}' -> {
          depth--
          if (depth == 0) return index
        }
      }
      index++
    }
    return -1
  }

  /**
   * `a = "x", b = y` as a map, or null when it is not that.
   *
   * Values are kept verbatim, quotes and all where the catalog wrote them: a Kotlin expression is
   * the unit here, and stripping quotes would turn `label = "Checkbox"` into an identifier.
   */
  private fun parseOverrides(text: String): Map<String, String>? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return emptyMap()
    val overrides = LinkedHashMap<String, String>()
    for (part in splitTopLevel(trimmed)) {
      val equals = topLevelEquals(part)
      if (equals < 0) return null
      val name = part.substring(0, equals).trim()
      val value = part.substring(equals + 1).trim()
      if (!isName(name) || value.isEmpty()) return null
      // A repeated argument is a template that names one parameter twice, which no call site can
      // honour; failing here is a message about the policy rather than a compile error later.
      if (overrides.put(name, value) != null) return null
    }
    return overrides
  }

  /**
   * The spans of [text] that are GENERIC ARGUMENT LISTS, so their commas are not separators.
   *
   * `${'$'}{call(items = emptyMap<String, Int>())}` was rejected as malformed: the splitter tracked
   * `()`, `[]` and `{}` and nothing else, so the comma between `String` and `Int` sat at depth zero
   * and cut one named argument into two. An override's value is documented as arbitrary Kotlin, so
   * rejecting valid Kotlin is a defect in the reader, not in the template.
   *
   * Angle brackets cannot simply be counted like the other three. `a < b` is a comparison, `->`
   * ends in a `>` that closes nothing, and `List<Pair<String, Int>>` nests. So this is a pre-scan
   * that PROVES a span before the splitter trusts it: a `<` is a generic opener only when it
   * follows an identifier character directly, and only when a matching `>` is found while skipping
   * `->` and refusing anything a type argument cannot contain. A `<` that fails any of those is
   * left to the splitter as an ordinary character, which is exactly the old behaviour — so a
   * comparison expression is no worse off than before, and this can only ever un-split.
   */
  private fun genericSpans(text: String): List<IntRange> {
    val spans = mutableListOf<IntRange>()
    var index = 0
    while (index < text.length) {
      if (text[index] != '<' || index == 0 || !isTypeChar(text[index - 1])) {
        index++
        continue
      }
      var depth = 0
      var parens = 0
      var scan = index
      var end = -1
      while (scan < text.length) {
        val ch = text[scan]
        // Everything a type argument list may hold, and nothing else. A quote or any other
        // character means the `<` was a comparison, and the attempt is abandoned rather than
        // guessed at.
        //
        // Parentheses are IN the set because a function type is an ordinary type argument —
        // `emptyMap<String, (Int, Int) -> Unit>()` is valid Kotlin, and excluding `(` made the scan
        // give up there, record no span, and let the comma after `String` split one override into
        // two. Their depth is tracked so a `>` only closes the list at paren depth zero, which is
        // what keeps `(Int, Int) -> Unit` from ending it early.
        //
        // `@` is in for the same reason, one shape further on: a type-use annotation is part of the
        // type, and `@Composable () -> Unit` is the function type this codebase actually writes, so
        // excluding `@` failed the identical way for the commoner input. It needs no depth of its
        // own — an annotation is a prefix, not a bracket — and admitting the character cannot widen
        // what the scan accepts as a list, because a `>` still has to close it at paren depth zero.
        if (!isTypeChar(ch) && ch !in "<>,?* -()@") break
        when {
          // `->` inside a function type argument: its `>` closes nothing.
          ch == '-' && scan + 1 < text.length && text[scan + 1] == '>' -> scan++
          ch == '(' -> parens++
          ch == ')' -> {
            parens--
            // More `)` than `(` means the `<` was never an opener: `n<m(1)` is arithmetic.
            if (parens < 0) break
          }
          ch == '<' && parens == 0 -> depth++
          ch == '>' && parens == 0 -> {
            depth--
            if (depth == 0) end = scan
          }
        }
        if (end >= 0) break
        scan++
      }
      if (end > index) {
        spans += index..end
        index = end + 1
      } else {
        index++
      }
    }
    return spans
  }

  private fun isTypeChar(ch: Char): Boolean = ch.isLetterOrDigit() || ch == '_' || ch == '.'

  /** Split on commas that are not inside parentheses, brackets, braces or a string literal. */
  private fun splitTopLevel(text: String): List<String> {
    val parts = mutableListOf<String>()
    val current = StringBuilder()
    val generics = genericSpans(text)
    var depth = 0
    var inString = false
    // Kotlin CHARACTER literals, tracked alongside strings. An override's value is arbitrary Kotlin
    // kept verbatim, and `separator = ','` is an ordinary thing to write — with only double quotes
    // recognised, the comma inside it read as a top-level argument separator and split one argument
    // into two, so a valid template was rejected as malformed.
    var inChar = false
    var inRaw = false
    var index = 0
    while (index < text.length) {
      val ch = text[index]
      val quote = if (inChar) 0 else quoteAt(text, index)
      when {
        (inString || inChar) && ch == '\\' -> {
          current.append(ch)
          if (index + 1 < text.length) current.append(text[index + 1])
          index += 2
          continue
        }
        inRaw && quote == 3 -> {
          inRaw = false
          current.append("\"\"\"")
          index += 3
          continue
        }
        inRaw -> current.append(ch)
        quote == 3 && !inString -> {
          inRaw = true
          current.append("\"\"\"")
          index += 3
          continue
        }
        quote == 1 && !inChar -> {
          inString = !inString
          current.append(ch)
        }
        ch == '\'' && !inString -> {
          inChar = !inChar
          current.append(ch)
        }
        inString || inChar -> current.append(ch)
        // Kept verbatim, like everything else in an override's value: the text is Kotlin source and
        // a comment is part of it. Skipped only as SYNTAX, so a brace or a comma inside one stops
        // being read as structure.
        commentEnd(text, index) >= 0 -> {
          val end = commentEnd(text, index)
          current.append(text, index, end)
          index = end
          continue
        }
        ch == '(' || ch == '[' || ch == '{' -> {
          depth++
          current.append(ch)
        }
        ch == ')' || ch == ']' || ch == '}' -> {
          depth--
          current.append(ch)
        }
        ch == ',' && depth == 0 && generics.none { index in it } -> {
          parts += current.toString()
          current.clear()
        }
        else -> current.append(ch)
      }
      index++
    }
    if (current.isNotBlank()) parts += current.toString()
    return parts.map { it.trim() }.filter { it.isNotEmpty() }
  }

  /**
   * Index of the `=` separating name from value, ignoring `==`, `>=` and anything in a string or a
   * character literal — `'='` is a legal value, and reading the `=` inside it as the separator
   * would split the argument in the wrong place.
   */
  private fun topLevelEquals(part: String): Int {
    var inString = false
    var inChar = false
    var inRaw = false
    var index = 0
    while (index < part.length) {
      val ch = part[index]
      val quote = if (inChar) 0 else quoteAt(part, index)
      when {
        (inString || inChar) && ch == '\\' -> index++
        inRaw && quote == 3 -> {
          inRaw = false
          index += 2
        }
        inRaw -> Unit
        quote == 3 && !inString -> {
          inRaw = true
          index += 2
        }
        quote == 1 && !inChar -> inString = !inString
        ch == '\'' && !inString -> inChar = !inChar
        inString || inChar -> Unit
        commentEnd(part, index) >= 0 -> {
          index = commentEnd(part, index)
          continue
        }
        ch == '=' -> {
          val next = part.getOrNull(index + 1)
          val previous = part.getOrNull(index - 1)
          if (
            next != '=' && previous != '=' && previous != '!' && previous != '<' && previous != '>'
          ) {
            return index
          }
        }
      }
      index++
    }
    return -1
  }

  private fun isName(text: String): Boolean =
    text.isNotEmpty() &&
      (text[0].isLetter() || text[0] == '_') &&
      text.all { it.isLetterOrDigit() || it == '_' }
}
