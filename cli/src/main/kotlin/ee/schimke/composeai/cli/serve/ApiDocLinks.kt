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
 * exactly what that code touches. So the snippet's imports **are** the API surface of the render,
 * with no join table to maintain and nothing for a catalog to declare.
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
 * this produces resolves (220 distinct pages, zero 404s). `ApiDocLinksTest` pins the shapes that
 * got it there, so a "simplification" that drops one of them fails rather than silently starts
 * publishing dead links.
 */
internal object ApiDocLinks {

  /** Where the reference pages live. A literal, so nothing snippet-derived reaches an `href`. */
  private const val BASE = "https://developer.android.com/reference/kotlin/"

  /** Most links one snippet may contribute, so a screen-sized panel stays screen-sized. */
  private const val MAX_LINKS = 24

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
    val imports = mutableListOf<Pair<String, String>>()
    for (line in snippet.lineSequence()) {
      val match = IMPORT.matchEntire(line)
      if (match != null) {
        val fqn = match.groupValues[1]
        // A star import names no symbol, and has no leaf to choose a page shape for.
        if (!fqn.endsWith(".*")) {
          imports += match.groupValues[2].ifEmpty { fqn.substringAfterLast('.') } to fqn
        }
      }
      // Import and `package` lines are blanked rather than dropped so the offsets that order the
      // links stay comparable with the source a reader is looking at.
      val keep = match == null && !line.trimStart().startsWith("package ")
      body.append(if (keep) line else "").append('\n')
    }
    val code = blankCommentsAndStrings(body.toString())
    return imports
      .mapNotNull { (name, fqn) -> linkFor(name, fqn, code) }
      // Two imports can reach the same page (the same symbol under an alias as well as its own
      // name); the page is what the reader opens, so it is what de-duplicates.
      .distinctBy { it.link.url }
      .sortedWith(compareBy({ if (it.link.composable) 0 else 1 }, { it.firstUse }))
      .take(MAX_LINKS)
      .map { it.link }
  }

  /** A resolved link plus the offset that orders it; the offset never leaves this file. */
  private class Ranked(val link: Link, val firstUse: Int)

  private fun linkFor(name: String, fqn: String, code: String): Ranked? {
    if (!fqn.startsWith("androidx.") && !fqn.startsWith("android.")) return null
    val leaf = fqn.substringAfterLast('.')
    if (leaf.firstOrNull()?.isUpperCase() != true) return null
    if (fqn.contains(".compose.material.icons.") || fqn.contains(".compose.material3.icons.")) {
      return null
    }
    if (Regex("""^Local[A-Z]""").containsMatchIn(leaf)) return null
    val quoted = Regex.escape(name)
    // The name written on its own — not the tail of `Icons.Filled.Add`, not part of a longer
    // identifier. An import the snippet never spells this way is not a symbol its code uses.
    val firstUse =
      Regex("""(?<![A-Za-z0-9_.])$quoted(?![A-Za-z0-9_])""").find(code)?.range?.first ?: return null
    val composable =
      when {
        usedAsDeclaration(quoted, code) -> false
        VALUE_PACKAGES.any { fqn.startsWith("$it.") } -> false
        calledInStatementPosition(name, code) -> true
        // Mentioned, but neither a type nor a call: a property, which has no page of its own.
        else -> return null
      }
    val url = BASE + fqn.replace('.', '/') + if (composable) ".composable" else ""
    return Ranked(Link(name = name, fqn = fqn, composable = composable, url = url), firstUse)
  }

  /** Qualifier, annotation, or type position — three uses a composable function never has. */
  private fun usedAsDeclaration(quoted: String, code: String): Boolean =
    Regex("""(?<![A-Za-z0-9_.])$quoted\s*\.""").containsMatchIn(code) ||
      Regex("""@$quoted(?![A-Za-z0-9_])""").containsMatchIn(code) ||
      Regex(""":\s*$quoted(?![A-Za-z0-9_])""").containsMatchIn(code) ||
      Regex("""[<,]\s*$quoted\s*[>,]""").containsMatchIn(code)

  /**
   * Whether [name] is called somewhere a *statement* may start: at the beginning of the code, after
   * `{`, `}`, `)`, `;` or `->`, or as the `fun … () = Name(…)` expression body of a composable.
   *
   * The `{` case excludes the value-producing lambdas ([VALUE_LAMBDAS]) and the trailing lambda of
   * an already-parenthesised call, since neither opens a composition.
   */
  private fun calledInStatementPosition(name: String, code: String): Boolean {
    val call = Regex("""(?<![A-Za-z0-9_.])${Regex.escape(name)}\s*[({]""")
    for (match in call.findAll(code)) {
      var j = match.range.first - 1
      while (j >= 0 && code[j].isWhitespace()) j--
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
   */
  private fun blankCommentsAndStrings(source: String): String {
    val out = StringBuilder(source.length)
    var i = 0
    var inString = false
    while (i < source.length) {
      val c = source[i]
      if (inString) {
        if (c == '\\') {
          out.append(' ')
          if (i + 1 < source.length) out.append(if (source[i + 1] == '\n') '\n' else ' ')
          i += 2
          continue
        }
        if (c == '"') inString = false
        out.append(if (c == '\n') '\n' else ' ')
        i++
        continue
      }
      when {
        c == '"' -> {
          inString = true
          out.append(' ')
          i++
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
