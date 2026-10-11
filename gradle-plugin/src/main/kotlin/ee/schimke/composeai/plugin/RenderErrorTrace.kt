package ee.schimke.composeai.plugin

/**
 * Extracts the `Caused by:` chain and the first user-package frame from a render-error sidecar's
 * stack trace. Needed because the headline fields are often uninformative (#3741): the reflective
 * invoke makes `exception` an `InvocationTargetException`, and `topAppFrame` lands on the tooling
 * frame that invoked the preview.
 *
 * Duplicated in `:cli` (`MissingRenderReport.kt`) since this included build shares no module with
 * it; keep in step (drift only degrades the message).
 */
internal object RenderErrorTrace {

  private const val CAUSED_BY_PREFIX = "Caused by:"
  private const val SUPPRESSED_PREFIX = "Suppressed:"

  /**
   * `at [<module>/]<class>.<method>(<file>:<line>)`; the optional group swallows JPMS qualifiers
   * (`app//…`, `java.base@17/…`).
   */
  private val FRAME_REGEX = Regex("^\\s*at\\s+(?:[\\w.@\$]*/{1,2})?([\\w\$.<>-]+)\\(([^()]*)\\)")

  /** One `Caused by:` entry. */
  data class Cause(val exception: String, val message: String)

  /**
   * Every `Caused by:` of the **primary** chain, outermost first. `Suppressed:` branches are
   * excluded: a suppressed throwable's own cause prints as an indented `Caused by:` after the
   * primary chain and would otherwise win as root cause. See [primaryLines].
   */
  fun causeChain(stackTrace: String): List<Cause> =
    primaryLines(stackTrace)
      .map { it.trim() }
      .filter { it.startsWith(CAUSED_BY_PREFIX) }
      .map { header ->
        val body = header.removePrefix(CAUSED_BY_PREFIX).trim()
        val split = body.indexOf(": ")
        if (split < 0) Cause(body, "")
        else Cause(body.substring(0, split), body.substring(split + 2).trim())
      }
      .toList()

  /** The deepest cause — the failure worth leading with. `null` when there is no chain. */
  fun rootCause(stackTrace: String): Cause? = causeChain(stackTrace).lastOrNull()

  /**
   * The first frame in the preview's own package, deepest `Caused by:` section first. Package
   * prefixes go longest-first down to two segments (so siblings count, `com.` never does). `null`
   * falls back to the sidecar's `topAppFrame`.
   */
  fun preferredAppFrame(
    stackTrace: String,
    previewClassName: String,
  ): ComposePreviewTasks.ErrorSidecar.TopAppFrame? {
    val prefixes = packagePrefixes(previewClassName)
    if (prefixes.isEmpty() || stackTrace.isBlank()) return null
    for (section in sections(stackTrace).asReversed()) {
      for (prefix in prefixes) {
        val frame = section.firstNotNullOfOrNull { line ->
          parseFrame(line)?.takeIf { it.className.startsWith("$prefix.") }
        }
        if (frame != null) {
          return ComposePreviewTasks.ErrorSidecar.TopAppFrame(
            file = frame.file,
            line = frame.line,
            function = frame.function,
          )
        }
      }
    }
    return null
  }

  /**
   * [stackTrace] with `Suppressed:` branches removed. `printStackTrace()` nests suppressed blocks
   * one tab deeper (causes keep the prefix), so a block at indent *n* runs until a non-blank line
   * indented less. Mirrors `:cli`'s `MissingRenderReport.primaryTraceLines`.
   */
  private fun primaryLines(stackTrace: String): List<String> {
    val out = mutableListOf<String>()
    var suppressedIndent: Int? = null
    for (line in stackTrace.lineSequence()) {
      if (line.isBlank()) {
        if (suppressedIndent == null) out += line
        continue
      }
      val indent = line.takeWhile { it == ' ' || it == '\t' }.length
      suppressedIndent?.let { if (indent < it) suppressedIndent = null }
      if (line.trimStart().startsWith(SUPPRESSED_PREFIX)) {
        // An outer block's bound wins: a suppressed-of-a-suppressed stays inside the outer one.
        suppressedIndent = minOf(suppressedIndent ?: indent, indent)
        continue
      }
      if (suppressedIndent != null) continue
      out += line
    }
    return out
  }

  /**
   * Throwable sections of a trace (outermost, then each `Caused by:`), without `Suppressed:`
   * branches.
   */
  private fun sections(stackTrace: String): List<List<String>> {
    val out = mutableListOf<MutableList<String>>(mutableListOf())
    for (line in primaryLines(stackTrace)) {
      if (line.trim().startsWith(CAUSED_BY_PREFIX)) out += mutableListOf<String>()
      out.last() += line
    }
    return out
  }

  private data class ParsedFrame(
    val className: String,
    val function: String,
    val file: String,
    val line: Int,
  )

  private fun parseFrame(line: String): ParsedFrame? {
    val match = FRAME_REGEX.find(line) ?: return null
    val (qualified, location) = match.destructured
    val className = qualified.substringBeforeLast('.', "")
    if (className.isEmpty()) return null
    val colon = location.lastIndexOf(':')
    val lineNumber = if (colon > 0) location.substring(colon + 1).toIntOrNull() ?: 0 else 0
    // `(Unknown Source)` / `(Native Method)` carry no file — useless as an "open this" pointer.
    if (lineNumber <= 0) return null
    return ParsedFrame(
      className = className,
      function = qualified.substringAfterLast('.'),
      file = location.substring(0, colon),
      line = lineNumber,
    )
  }

  private fun packagePrefixes(className: String): List<String> {
    val segments = className.substringBeforeLast('.', "").split('.').filter { it.isNotEmpty() }
    if (segments.size < 2) return emptyList()
    return (segments.size downTo 2).map { segments.take(it).joinToString(".") }
  }
}
