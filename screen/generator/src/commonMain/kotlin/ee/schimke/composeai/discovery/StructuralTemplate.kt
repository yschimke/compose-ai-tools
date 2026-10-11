package ee.schimke.composeai.discovery

/**
 * The hole-filler a catalog's structural code templates are rendered by.
 *
 * A record can print a call site but not the structure a screen needs around it (a scroll state
 * shared between `ScreenScaffold` and its `TransformingLazyColumn`, `SurfaceTransformation` only
 * inside `item { }`, a hoisted `remember` for a `CheckboxButton`). Catalogs publish that structure
 * as templates — Kotlin fragments with named holes, one per structural role, in
 * `ui-builder.policy.json` — and this fills the holes.
 *
 * Two hole kinds and no third:
 * - `${name}` — substitute the value the caller resolved for `name`.
 * - `${call(modifier = "Modifier.padding(8.dp)")}` — a record call site with named argument
 *   overrides, so a template can set a parameter without knowing which component fills the hole.
 *
 * No conditionals, loops or expressions: a template needing one is two roles. That keeps templates
 * validatable data rather than an emitter jar.
 *
 * Indentation is why this isn't `String.replace`:
 * ```
 * AppScaffold {
 *   ScreenScaffold(scrollState = ${listState}) {
 *     ${content}
 *   }
 * }
 * ```
 * A hole that begins a line re-indents every continuation line of its value to the hole's column.
 * A mid-line hole does not, since there is no single column to align to.
 */
object StructuralTemplate {

  /** One hole in a template. */
  sealed interface Hole {
    /** `${name}` — a named value the caller resolves. */
    data class Named(val name: String) : Hole

    /**
     * `${call(...)}` — a record call site with named argument overrides, carried as unparsed source
     * text; the compile round trip decides whether they are valid.
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
   * The holes [template] declares, in order, or the reasons it cannot be read. Separate from
   * [render] so templates are validated when the catalog is published, not when a screen is
   * exported.
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
   * Render [template], asking [resolve] for each hole's value. A null from [resolve] becomes a
   * refusal naming the hole, never an empty string that would silently drop content.
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
   * The column a hole starting a line sits at (only whitespace before it on its line), or null when
   * it does not start one.
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
   * Walk `${…}` occurrences, reporting each as a hole plus its span. Only `${` opens a hole (Kotlin
   * templates are full of `$`); braces nest and string literals are respected, since overrides
   * contain both.
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
   * Where a Kotlin comment starting at [index] ends, or -1 when none starts there. Shared by every
   * scanner here so commented braces/commas are never read as syntax. Block comments nest in
   * Kotlin, so this counts depth; an unterminated comment runs to the end.
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

  /**
   * How many characters of quoting start at [index]: 3 for `\"\"\"`, 1 for `"`, 0 otherwise. Shared
   * so [closingBrace], [splitTopLevel] and [topLevelEquals] agree; triple quotes are tested first.
   */
  private fun quoteAt(text: String, index: Int): Int =
    if (text.startsWith("\"\"\"", index)) 3 else if (text[index] == '"') 1 else 0

  /**
   * Index of the `}` closing the `{` at [open], respecting nesting, string and character literals
   * (e.g. `${'$'}{call(separator = '}')}`) and comments.
   */
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
        // A raw string has no escapes, so a backslash inside one is an ordinary character.
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
   * `a = "x", b = y` as a map, or null when it is not that. Values are kept verbatim, quotes
   * included, since each is a Kotlin expression.
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
      // A parameter named twice can't be honoured by any call site; report it against the policy.
      if (overrides.put(name, value) != null) return null
    }
    return overrides
  }

  /**
   * The spans of [text] that are generic argument lists, so their commas are not separators
   * (`emptyMap<String, Int>()`).
   *
   * Angle brackets can't simply be counted (`a < b`, `->`, nested generics), so a `<` is an opener
   * only when it directly follows an identifier character and a matching `>` is found, skipping
   * `->` and abandoning on anything a type argument can't contain. A rejected `<` is an ordinary
   * character, so this can only ever un-split.
   */
  private fun genericSpans(text: String): List<IntRange> {
    val spans = mutableListOf<IntRange>()
    var index = 0
    while (index < text.length) {
      // Skip comments, as every scanner here does.
      val skipped = commentEnd(text, index)
      if (skipped >= 0) {
        index = skipped
        continue
      }
      if (text[index] != '<' || index == 0 || !isTypeChar(text[index - 1])) {
        index++
        continue
      }
      var depth = 0
      var parens = 0
      var scan = index
      var end = -1
      while (scan < text.length) {
        // Everything a type argument list may hold, and nothing else; anything else means the `<`
        // was a comparison. Parentheses are allowed (function types like `(Int, Int) -> Unit`) with
        // depth tracked so `>` only closes at paren depth zero; `@` is allowed for type-use
        // annotations like `@Composable () -> Unit`.
        val commented = commentEnd(text, scan)
        if (commented >= 0) {
          scan = commented
          continue
        }
        val ch = text[scan]
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
    // Character literals too: `separator = ','` must not split.
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
        // Comments are kept verbatim but skipped as syntax.
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
   * Index of the `=` separating name from value, ignoring `==`, `>=` and anything in a string or
   * character literal (`'='`).
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
