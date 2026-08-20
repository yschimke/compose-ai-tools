package ee.schimke.composeai.cli.serve

/**
 * The **API reference links** behind a usage snippet: every `androidx.` / `android.` symbol the
 * cleaned Compose code actually uses, resolved to its KDoc page on `developer.android.com`
 * (issue #4331).
 *
 * ### Why the snippet, and not the preview
 *
 * A catalog preview is a `@Preview` function in the catalog's own package — `ImageBackgroundButton`
 * in `ee.schimke.wearm3catalog` — and nothing about that name says the component on screen is
 * `androidx.wear.compose.material3.Button`. The *usage snippet* does: [PlaygroundSourceCleaner] has
 * already reduced the sticker to the plain Compose a reader would write, and pruned its imports to
 * exactly what that code touches. So the snippet's imports — plus the APIs the cleaner chose to
 * write out in full ([QUALIFIED]) — **are** the API surface of the render, with no join table to
 * maintain and nothing for a catalog to declare.
 *
 * ### The two page shapes, and why the kind has to be inferred
 *
 * `developer.android.com` publishes a top-level `@Composable` function at `<pkg>/<Name>.composable`
 * and a class / interface / object / annotation at `<pkg>/<Name>` — and the *other* one 404s. An
 * import carries no signature to tell them apart, so [linkFor] reads how the snippet uses the name:
 *
 * - used as a **qualifier** (`ButtonDefaults.buttonColors()`), an **annotation** (`@Composable`),
 *   or a **type** (`: Modifier`, `<Dp>`) ⇒ the declaration page. A composable is never any of
 *   those.
 * - outside a Compose namespace ([composableNamespace]) ⇒ the declaration page, without consulting
 *   the call site at all. `Button(onClick = { Intent(ctx, T::class.java) })` puts a constructor
 *   exactly where a composable call sits, and nothing in the braces alone tells a callback lambda
 *   from a slot one — but `android.content.Intent` has no `.composable` page to be wrong about.
 * - otherwise, **called in statement position** ⇒ the composable page. "Statement position" is the
 *   discriminator that matters, and it is decided by the character before the call rather than by
 *   the start of a line: a value called for its constructor is always part of a larger expression
 *   (`color = Color(0xFF…)`, or that same argument wrapped onto a line of its own), so the
 *   character before it is `=` or `,` or `(`, while a composable call follows a statement — `{`,
 *   `}`, `)`, `;`, `->`, or the start of the code. Line starts alone got this wrong on every
 *   wrapped argument.
 * - a name that is neither ⇒ no link. A bare `shape = CircleShape` names a **property**, and dokka
 *   files properties under their package summary rather than giving each a page.
 *
 * Comments and string literals are blanked before any of that runs ([blankCommentsAndStrings]).
 * Both produced wrong answers on real catalog source: a `Slider.kt` mention in a KDoc line read as
 * a qualifier, and `contentDescription = "Add"` made an `Icons.Filled.Add` import look like a
 * symbol the code used by name.
 *
 * ### What is deliberately dropped
 *
 * - Non-`androidx`/`android` packages — a catalog's own helpers have no published reference.
 * - Lower-case leaves (`fillMaxSize`, `dp`, `remember`) and `Local…` composition locals: extension
 *   functions, properties and vals, all filed under a package summary with no page of their own.
 * - The icon packs (`androidx.compose.material.icons.**`), whose members are extension properties
 *   on `Icons.Filled` and friends, and are written `Icons.Filled.Add` rather than by their imported
 *   name anyway.
 *
 * Measured against 244 live snippets spanning every catalog on the public preview host, every URL
 * this produces resolves (227 distinct pages, zero 404s). `ApiDocLinksTest` pins the shapes that
 * got it there, so a "simplification" that drops one of them fails rather than silently starts
 * publishing dead links.
 */
internal object ApiDocLinks {

  /** Where the reference pages live. A literal, so nothing snippet-derived reaches an `href`. */
  private const val BASE = "https://developer.android.com/reference/kotlin/"

  /** Most links one snippet may contribute, so a screen-sized panel stays screen-sized. */
  private const val MAX_LINKS = 24

  /** What a string literal's characters become — see [blankCommentsAndStrings]. */
  private const val STRING_FILL = '0'

  /**
   * Packages that publish **value types only** — no top-level composable has ever lived in them.
   *
   * Named because the statement-position rule cannot see through an if/else expression or a `map {
   * a -> Offset(…) }` lambda: both put a constructor call exactly where a composable call would
   * sit. Rather than teach the scanner Kotlin's expression grammar for two cases, the four packages
   * whose whole contents are `Color` / `Offset` / `Dp` / `TextStyle`-shaped values say so.
   */
  private val VALUE_PACKAGES =
    listOf(
      "androidx.compose.ui.graphics",
      "androidx.compose.ui.geometry",
      "androidx.compose.ui.unit",
      "androidx.compose.ui.text",
    )

  /**
   * Trailing lambdas whose body is a **value**, not a composition. The constructor call in
   * `remember { MutableInteractionSource() }` sits exactly where the `{` would otherwise mark a
   * composable one.
   */
  private val VALUE_LAMBDAS =
    setOf(
      "remember",
      "rememberSaveable",
      "mutableStateOf",
      "mutableStateListOf",
      "derivedStateOf",
      "lazy",
      "runCatching",
    )

  /**
   * One resolved symbol: the [name] the snippet writes (an `as` alias, where it renamed one), the
   * [fqn] it imports, whether it resolved as a composable (which picks the page shape), and the
   * [url] to open.
   */
  data class Link(val name: String, val fqn: String, val composable: Boolean, val url: String)

  private val IMPORT =
    Regex("""^\s*import\s+([A-Za-z_][A-Za-z0-9_.]*)\s*(?:as\s+([A-Za-z_][A-Za-z0-9_]*))?\s*$""")

  /**
   * A platform API written out in full in the body — lower-case package segments and **one**
   * capitalised type.
   *
   * One, not a chain: `androidx.compose.ui.graphics.Color.Transparent` is a member read off
   * `Color`, and taking both capitalised segments asks the site for a nested type that does not
   * exist. An import is the opposite case and keeps its whole chain, because an import line names
   * the type exactly — including a genuinely nested one like `LayoutElementBuilders.Box`.
   */
  private val QUALIFIED =
    Regex(
      """(?<![A-Za-z0-9_.])((?:androidx|android)(?:\.[a-z][A-Za-z0-9_]*)+\.[A-Z][A-Za-z0-9_]*)"""
    )

  /**
   * The reference links for [snippet] — composables first, then declarations, each group in the
   * order the code first names them.
   *
   * That ordering is what puts the component the preview is *about* at the head of the list: the
   * outermost composable call is the first one written, and the annotations decorating it
   * (`@Preview`, `@Composable`) sort behind every component they annotate.
   *
   * Empty for a snippet with no documented imports, which is the whole answer for a catalog built
   * out of its own helpers.
   */
  fun of(snippet: String): List<Link> {
    val body = StringBuilder()
    val candidates = mutableListOf<Pair<String, String>>()
    for (line in snippet.lineSequence()) {
      val match = IMPORT.matchEntire(line)
      if (match != null) {
        val fqn = match.groupValues[1]
        // A star import names no symbol, and has no leaf to choose a page shape for.
        if (!fqn.endsWith(".*")) {
          candidates += match.groupValues[2].ifEmpty { fqn.substringAfterLast('.') } to fqn
        }
      }
      // Import and `package` lines are blanked rather than dropped so the offsets that order the
      // links stay comparable with the source a reader is looking at.
      val keep = match == null && !line.trimStart().startsWith("package ")
      body.append(if (keep) line else "").append('\n')
    }
    val code = blankCommentsAndStrings(body.toString())
    // Fully-qualified uses, which carry no import at all. Not an edge case: the cleaner's
    // `MATERIAL3_SYSTEM_THEME` rewrite deliberately emits
    // `androidx.compose.material3.MaterialTheme(...)` and prunes the import, so a catalog whose
    // theme wrapper goes through it would otherwise be missing the most prominent API on the card.
    // Here the spelling the code uses IS the qualified name, which every rule below matches on
    // just as it matches a simple one.
    for (match in QUALIFIED.findAll(code)) {
      candidates += match.groupValues[1] to match.groupValues[1]
    }
    return candidates
      .mapNotNull { (spelling, fqn) -> linkFor(spelling, fqn, code) }
      // Two candidates can reach the same page — a symbol under an alias as well as its own name,
      // or an import and a qualified use of the same thing. The page is what the reader opens, so
      // it is what de-duplicates.
      .distinctBy { it.link.url }
      .sortedWith(compareBy({ if (it.link.composable) 0 else 1 }, { it.firstUse }))
      .take(MAX_LINKS)
      .map { it.link }
  }

  /** A resolved link plus the offset that orders it; the offset never leaves this file. */
  private class Ranked(val link: Link, val firstUse: Int)

  private fun linkFor(spelling: String, fqn: String, code: String): Ranked? {
    if (!fqn.startsWith("androidx.") && !fqn.startsWith("android.")) return null
    val leaf = fqn.substringAfterLast('.')
    if (leaf.firstOrNull()?.isUpperCase() != true) return null
    // `android.permission.BLUETOOTH_CONNECT` is a String constant, not a type. Reached only
    // through the qualified scan, which cannot lean on an import line to tell it otherwise.
    if (leaf.length > 1 && leaf == leaf.uppercase()) return null
    if (fqn.contains(".compose.material.icons.") || fqn.contains(".compose.material3.icons.")) {
      return null
    }
    if (Regex("""^Local[A-Z]""").containsMatchIn(leaf)) return null
    val quoted = Regex.escape(spelling)
    // The name written on its own — not the tail of `Icons.Filled.Add`, not part of a longer
    // identifier. An import the snippet never spells this way is not a symbol its code uses.
    val firstUse =
      Regex("""(?<![A-Za-z0-9_.])$quoted(?![A-Za-z0-9_])""").find(code)?.range?.first ?: return null
    val composable =
      when {
        usedAsDeclaration(quoted, code) -> false
        // Outside a Compose namespace there is no `.composable` page to be wrong about, so the
        // call-site reading is not consulted at all: `Button(onClick = { Intent(ctx, T::class.java)
        // })`
        // puts a constructor exactly where a composable call sits, and no reading of the braces
        // alone tells a callback lambda from a slot one.
        !composableNamespace(fqn) -> false
        calledInStatementPosition(spelling, code) -> true
        // Mentioned, but neither a type nor a call: a property, which has no page of its own.
        else -> return null
      }
    val url = BASE + referencePath(fqn) + if (composable) ".composable" else ""
    // The name the panel shows is what the code writes — an `as` alias included, since that is
    // the identifier the reader just met. A qualified use writes the whole path, which is not a
    // label, so it shows its leaf instead.
    val label = if (spelling.contains('.')) leaf else spelling
    return Ranked(Link(name = label, fqn = fqn, composable = composable, url = url), firstUse)
  }

  /**
   * Whether a `.composable` page could exist for [fqn] at all — the namespaces that publish
   * composable functions, minus the [VALUE_PACKAGES] inside them.
   *
   * `androidx.compose.*` and `androidx.wear.compose.*` are both covered by the `.compose.` segment;
   * Glance and TV Material are the two composable homes that do not carry it.
   */
  private fun composableNamespace(fqn: String): Boolean =
    (fqn.contains(".compose.") ||
      fqn.startsWith("androidx.glance.") ||
      fqn.startsWith("androidx.tv.material3.")) && VALUE_PACKAGES.none { fqn.startsWith("$it.") }

  /**
   * The reference site's path for [fqn]: package segments separated by `/`, and the class chain
   * kept dotted — `androidx/wear/protolayout/LayoutElementBuilders.Box` for a nested type, which is
   * how the site spells one. Replacing every dot would ask for a directory that does not exist.
   *
   * Package segments are the leading lower-case ones, which is the naming convention every
   * `androidx` and `android` package follows.
   */
  private fun referencePath(fqn: String): String {
    val segments = fqn.split('.')
    val firstType = segments.indexOfFirst { it.firstOrNull()?.isUpperCase() == true }
    if (firstType < 0) return segments.joinToString("/")
    return (segments.subList(0, firstType) +
        segments.subList(firstType, segments.size).joinToString("."))
      .joinToString("/")
  }

  /** Qualifier, annotation, or type position — three uses a composable function never has. */
  private fun usedAsDeclaration(quoted: String, code: String): Boolean =
    Regex("""(?<![A-Za-z0-9_.])$quoted\s*\.""").containsMatchIn(code) ||
      Regex("""@$quoted(?![A-Za-z0-9_])""").containsMatchIn(code) ||
      Regex(""":\s*$quoted(?![A-Za-z0-9_])""").containsMatchIn(code) ||
      Regex("""[<,]\s*$quoted\s*[>,]""").containsMatchIn(code)

  /**
   * Whether [name] is called somewhere a *statement* may start: at the beginning of the code, after
   * `{`, `}`, `)`, `;` or `->`, after a line break that closed the previous statement, or as the
   * `fun … () = Name(…)` expression body of a composable.
   *
   * The `{` case excludes the value-producing lambdas ([VALUE_LAMBDAS]) and the trailing lambda of
   * an already-parenthesised call, since neither opens a composition.
   *
   * The **line break** case is what makes a call after an ordinary local declaration reachable —
   * `val enabled = true` then `Button(enabled = enabled)`, where the character before the call is
   * the `e` of `true`. Kotlin has no statement terminator, so the line break is the only thing
   * separating them. It counts only when the previous line *ended* an expression (an identifier, a
   * literal, a `]`); a line ending in `=` or `,` or `(` is one argument wrapped across two lines,
   * and treating that as a statement is exactly what put a `.composable` page on a value class.
   */
  private fun calledInStatementPosition(name: String, code: String): Boolean {
    val call = Regex("""(?<![A-Za-z0-9_.])${Regex.escape(name)}\s*[({]""")
    for (match in call.findAll(code)) {
      var j = match.range.first - 1
      var crossedLineBreak = false
      while (j >= 0 && code[j].isWhitespace()) {
        if (code[j] == '\n') crossedLineBreak = true
        j--
      }
      if (j < 0) return true
      when (code[j]) {
        '}',
        ';',
        ')' -> return true
        '>' -> if (j > 0 && code[j - 1] == '-') return true
        '=' -> {
          // `fun kitGlyph() = Icon(…)`: an expression body, whose `=` follows the parameter list.
          // An ordinary `argument = Value(…)` has an identifier there instead, and is not one.
          var k = j - 1
          while (k >= 0 && code[k].isWhitespace()) k--
          if (k >= 0 && code[k] == ')') return true
        }
        '{' -> if (ownerOfBrace(code, j) !in VALUE_LAMBDAS) return true
        else ->
          if (crossedLineBreak && (code[j].isLetterOrDigit() || code[j] == '_' || code[j] == ']')) {
            return true
          }
      }
    }
    return false
  }

  /**
   * The identifier a `{` at [brace] belongs to — `remember` in `remember { … }`, and equally
   * `remember` in `remember(key) { … }`, since an argument list between the two changes nothing
   * about whose lambda it is. Empty for a brace that follows no call at all (`fun demo() {`, an
   * `if` body, a bare block), which is exactly the case that must NOT be mistaken for one.
   */
  private fun ownerOfBrace(code: String, brace: Int): String {
    var k = brace - 1
    while (k >= 0 && code[k].isWhitespace()) k--
    if (k >= 0 && code[k] == ')') {
      var depth = 0
      while (k >= 0) {
        if (code[k] == ')') depth++
        if (code[k] == '(') {
          depth--
          if (depth == 0) break
        }
        k--
      }
      k--
      while (k >= 0 && code[k].isWhitespace()) k--
    }
    val end = k
    while (k >= 0 && (code[k].isLetterOrDigit() || code[k] == '_')) k--
    return if (end > k) code.substring(k + 1, end + 1) else ""
  }

  /**
   * Replace every comment, and the contents of every string literal, with spaces — keeping newlines
   * so line structure and offsets survive.
   *
   * Deliberately a scanner rather than a regex: `"a // b"` is a string containing what looks like a
   * comment and `// "a` is a comment containing what looks like an unterminated string. A pattern
   * that handles one gets the other wrong, and both appear in ordinary catalog source.
   *
   * A **raw** `"""…"""` string is consumed whole, before the single-quote case can see it. Toggling
   * per quote instead would treat a raw string's own `"` characters as delimiters and hand back
   * alternating slices of its contents as though they were code.
   *
   * A string blanks to **digits**, not spaces, while a comment blanks to spaces. The difference is
   * that a string literal *is* an expression: `val json = "…"` ends a statement, and the next line
   * may open a new one. Blanked to whitespace it would read as though the `=` were still hanging
   * open, and the composable called on the following line would be lost.
   */
  private fun blankCommentsAndStrings(source: String): String {
    val out = StringBuilder(source.length)
    var i = 0
    while (i < source.length) {
      val c = source[i]
      when {
        c == '"' && source.startsWith("\"\"\"", i) -> {
          val end = source.indexOf("\"\"\"", i + 3)
          val stop = if (end < 0) source.length else end + 3
          while (i < stop) {
            out.append(if (source[i] == '\n') '\n' else STRING_FILL)
            i++
          }
        }
        c == '"' -> {
          out.append(STRING_FILL)
          i++
          while (i < source.length) {
            if (source[i] == '\\') {
              out.append(STRING_FILL)
              if (i + 1 < source.length) {
                out.append(if (source[i + 1] == '\n') '\n' else STRING_FILL)
              }
              i += 2
              continue
            }
            val ch = source[i]
            out.append(if (ch == '\n') '\n' else STRING_FILL)
            i++
            if (ch == '"') break
          }
        }
        c == '/' && i + 1 < source.length && source[i + 1] == '/' -> {
          while (i < source.length && source[i] != '\n') {
            out.append(' ')
            i++
          }
        }
        c == '/' && i + 1 < source.length && source[i + 1] == '*' -> {
          val end = source.indexOf("*/", i + 2)
          val stop = if (end < 0) source.length else end + 2
          while (i < stop) {
            out.append(if (source[i] == '\n') '\n' else ' ')
            i++
          }
        }
        else -> {
          out.append(c)
          i++
        }
      }
    }
    return out.toString()
  }
}
