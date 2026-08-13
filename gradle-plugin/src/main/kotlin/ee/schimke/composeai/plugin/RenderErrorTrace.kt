package ee.schimke.composeai.plugin

/**
 * Reads the useful parts out of a render-error sidecar's printed stack trace: the `Caused by:`
 * chain, and the first stack frame belonging to the *user's own* package.
 *
 * Both exist because the sidecar's headline fields are routinely uninformative (issue #3741). The
 * renderer invokes each preview reflectively, so `exception` is often
 * `java.lang.reflect.InvocationTargetException` — the actual failure lives at the end of the cause
 * chain. And `topAppFrame` is computed with a skip-the-framework-prefixes heuristic over the
 * *outermost* throwable's frames, which lands on whichever tooling frame did the invoking
 * (`KeyboardDataProduct.kt:148` in the reported case) rather than on the consumer file the reader
 * can actually open (`AmbientAwareActivity.kt:76`).
 *
 * Deliberately duplicated in `:cli` (`MissingRenderReport.kt`): `:gradle-plugin` is a separate
 * included build, so there is no module both can depend on — the same reason `ErrorSidecar` mirrors
 * the renderer's schema here. Keep the two in step; a drift degrades the message, never the build.
 */
internal object RenderErrorTrace {

  private const val CAUSED_BY_PREFIX = "Caused by:"
  private const val SUPPRESSED_PREFIX = "Suppressed:"

  /**
   * `at [<module>/]<class>.<method>(<file>:<line>)`. The optional leading group swallows the module
   * / classloader qualifier a JPMS-aware JVM prints (`app//com.example.Foo.bar(…)`,
   * `java.base@17/java.lang.reflect.Method.invoke(…)`); `/` never appears in a class name.
   */
  private val FRAME_REGEX = Regex("^\\s*at\\s+(?:[\\w.@\$]*/{1,2})?([\\w\$.<>-]+)\\(([^()]*)\\)")

  /** One `Caused by:` entry. */
  data class Cause(val exception: String, val message: String)

  /**
   * Every `Caused by:` entry of [stackTrace]'s **primary** chain, outermost cause first.
   *
   * `Suppressed:` branches are excluded. A suppressed throwable that has a cause of its own — the
   * ordinary shape for a `use {}` body that threw and then failed to close — is printed by
   * `printStackTrace()` as an *indented* `Caused by:`, so trimming every line first made it
   * indistinguishable from the real chain and, being printed last, it won the `lastOrNull()` that
   * picks the root cause. See [primaryLines].
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
   * The first frame in the preview's own package, searching the deepest `Caused by:` section first.
   * Package prefixes are tried longest-first (exact package, then parents down to two segments), so
   * a sibling package of the preview counts but `com.` never does. `null` when nothing matches,
   * leaving the sidecar's own `topAppFrame` as the fallback.
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
   * [stackTrace]'s lines with every `Suppressed:` branch removed, indentation preserved.
   *
   * `printStackTrace()` nests by indentation and nothing else: a suppressed throwable's caption,
   * frames, **and its own `Caused by:` chain** are printed one tab deeper than the throwable that
   * suppressed it (`printEnclosedStackTrace` passes `prefix + "\t"` for suppressed and the
   * unchanged `prefix` for causes), so a block that starts at indent *n* runs until the first
   * non-blank line indented less than *n*. Mirrors `MissingRenderReport.primaryTraceLines` in
   * `:cli`.
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
   * The throwable sections of a printed trace: outermost first, then one per `Caused by:`.
   * `Suppressed:` branches are dropped (see [primaryLines]) so the frame this picks belongs to the
   * failure the message names.
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
