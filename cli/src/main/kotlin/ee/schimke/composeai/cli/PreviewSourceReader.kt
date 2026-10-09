package ee.schimke.composeai.cli

import java.io.File

/**
 * A @Preview function's source, as the guidelines check shows it to a model: from the line
 * `previews.json` gives as `bodyLine` (the `fun` line) to the end of the function, so rules about
 * code — a fixed size, a hard-coded colour, a missing content description — are judged on the code
 * rather than guessed from pixels.
 *
 * Braces are counted outside string and character literals and comments, which is enough for
 * preview functions; a function written as an expression (`= Sticker { … }`) ends where its
 * brackets close. Capped at [maxLines] and [maxChars]: a preview is a few dozen lines, and the cap
 * only bites on a file the counter misreads.
 */
internal object PreviewSourceReader {
  fun read(file: File, bodyLine: Int, maxLines: Int = 200, maxChars: Int = 8_000): String? {
    if (!file.isFile || bodyLine < 1) return null
    val lines = runCatching { file.readLines() }.getOrNull() ?: return null
    if (bodyLine > lines.size) return null
    return extract(lines, bodyLine - 1, maxLines, maxChars)
  }

  /**
   * [read], followed by the bodies of the functions the preview **directly calls** that its own
   * module defines — one level deep, at most [maxCallees] of them within [maxCalleeChars].
   *
   * A preview is often a single call into the catalog's frame (`WearList() = WearScreen { … }`),
   * and the frame is where the time text, the scaffold or the theme come from. Shown only the
   * preview's body, a model reported "ScreenScaffold's timeText is not set" for a screen whose
   * frame supplies it. Library functions (`ScreenScaffold`, `Text`) are not in the module's source
   * tree, so they are never pulled in.
   */
  fun readWithCallees(
    file: File,
    bodyLine: Int,
    maxCallees: Int = 3,
    maxCalleeChars: Int = 4_000,
    index: SourceIndex? = SourceIndex.forSourceFile(file),
  ): String? {
    val body = read(file, bodyLine) ?: return null
    index ?: return body
    val own = DECLARATION.find(body)?.groupValues?.get(1)
    val callees =
      CALL.findAll(body)
        .map { it.groupValues[1] }
        .filter { it != own }
        .distinct()
        .mapNotNull { name -> index.find(name)?.let { name to it } }
        .filterNot { (_, at) -> at.file.canonicalPath == file.canonicalPath && at.line == bodyLine }
        .take(maxCallees)
        .toList()
    if (callees.isEmpty()) return body
    val out = StringBuilder(body)
    var budget = maxCalleeChars
    for ((name, at) in callees) {
      val lines = runCatching { at.file.readLines() }.getOrNull() ?: continue
      val callee = extract(lines, at.line - 1, maxChars = budget.coerceAtLeast(0))
      if (callee.isBlank() || budget <= 0) break
      out.append("\n\n// ").append(name).append(", which the preview calls (")
      out.append(at.file.name).append(':').append(at.line).append("):\n").append(callee)
      budget -= callee.length
    }
    return out.toString()
  }

  /** `fun Name(` or `fun <T> Receiver.Name(`, capturing the name. */
  private val DECLARATION = Regex("""\bfun\s+(?:<[^>]*>\s*)?(?:[\w.]+\.)?([A-Za-z_]\w*)\s*\(""")

  /** A call to a capitalised function: composables, as Compose names them. */
  private val CALL = Regex("""(?<![\w.])([A-Z]\w*)\s*[({]""")

  fun extract(lines: List<String>, start: Int, maxLines: Int = 200, maxChars: Int = 8_000): String {
    val out = StringBuilder()
    var depth = 0
    var opened = false
    var bodyIsExpression = false
    var inBlockComment = false
    var taken = 0
    for (index in start until lines.size) {
      val line = lines[index]
      if (taken >= maxLines || out.length + line.length > maxChars) break
      out.append(line).append('\n')
      taken++
      var i = 0
      var inString = false
      var quote = ' '
      while (i < line.length) {
        val c = line[i]
        val next = line.getOrNull(i + 1)
        when {
          inBlockComment ->
            if (c == '*' && next == '/') {
              inBlockComment = false
              i++
            }
          inString ->
            when {
              c == '\\' -> i++
              c == quote -> inString = false
            }
          c == '/' && next == '/' -> break
          c == '/' && next == '*' -> {
            inBlockComment = true
            i++
          }
          c == '"' || c == '\'' -> {
            inString = true
            quote = c
          }
          // The body starts at the first `{`, or at a bracket after a top-level `=` (an expression
          // body); the parameter list's brackets close before either and do not count as the end.
          c == '=' && depth == 0 && next != '=' -> bodyIsExpression = true
          c == '{' -> {
            depth++
            opened = true
          }
          c == '(' -> {
            depth++
            if (bodyIsExpression) opened = true
          }
          c == '}' || c == ')' -> depth--
        }
        i++
      }
      if (opened && depth <= 0) break
    }
    return out.toString().trimEnd()
  }
}

/**
 * Where a module's functions are declared: every `fun Name(` under the `src` directory that holds a
 * preview's source file, read once and kept for the run.
 */
internal class SourceIndex private constructor(private val root: File) {
  data class At(val file: File, val line: Int)

  private val byName: Map<String, At> by lazy {
    val found = mutableMapOf<String, At>()
    root
      .walkTopDown()
      .onEnter { it.name != "build" && !it.name.startsWith(".") }
      .filter { it.isFile && it.extension == "kt" }
      .forEach { file ->
        runCatching { file.readLines() }
          .getOrNull()
          ?.forEachIndexed { index, line ->
            DECLARATION.find(line)?.let { match ->
              // The first declaration wins: an overload elsewhere is not the one being guessed at.
              found.putIfAbsent(match.groupValues[1], At(file, index + 1))
            }
          }
      }
    found
  }

  fun find(name: String): At? = byName[name]

  companion object {
    private val DECLARATION =
      Regex(
        """^\s*(?:@[\w.]+(?:\([^)]*\))?\s+)*(?:(?:private|internal|public|inline)\s+)*fun\s+(?:<[^>]*>\s*)?([A-Z]\w*)\s*\("""
      )
    private val cache = mutableMapOf<String, SourceIndex>()

    /**
     * The index for the module whose `src` directory holds [file], or null outside one. With
     * [within], a `src` directory not under it gives none: a handoff's staged tree comes from the
     * pull request, and the walk must not reach past it.
     */
    fun forSourceFile(file: File, within: File? = null): SourceIndex? {
      var dir: File? = file.absoluteFile.parentFile
      while (dir != null && dir.name != "src") dir = dir.parentFile
      val src = dir ?: return null
      if (within != null) {
        val root = within.canonicalFile
        if (!src.canonicalFile.startsWith(root)) return null
      }
      return synchronized(cache) { cache.getOrPut(src.canonicalPath) { SourceIndex(src) } }
    }
  }
}
