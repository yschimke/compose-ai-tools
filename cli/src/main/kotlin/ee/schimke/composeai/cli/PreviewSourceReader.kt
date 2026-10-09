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
