package ee.schimke.composeai.cli

import ee.schimke.composeai.cli.serve.RenderFailureFrame
import ee.schimke.composeai.io.SystemFileSystem
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path.Companion.toPath

/*
 * The renderer's error sidecar, as the CLI reads it: the file format, and the stack-trace reading
 * that turns one into something worth printing (issue #3741).
 *
 * When a preview renders nothing, `show` / `render` used to print one fixed paragraph blaming the
 * *build wiring* ("`composePreviewRender` reported NO-SOURCE, the renderer test class wasn't on
 * testClassesDirs"). That guess is wrong whenever the render task actually ran and the preview
 * threw — and in that case the renderer has already written the precise cause next to where the
 * PNG would have gone:
 *
 *   <module>/build/compose-previews/renders/<Stem>.png.error.json
 *
 * Which of those two a given preview is, and what may therefore be said about it, is decided in
 * [PreviewDiagnosis] and said in `MissingRenderMessage.kt` (issue #3796). This file only knows how
 * to read the evidence, never how to describe it.
 */

/**
 * The renderer's per-preview `compose-preview-error/v1` sidecar, as the CLI reads it.
 *
 * Mirrors the writer (`renderer-android/.../RenderErrorSidecar.kt`, the desktop equivalent in
 * `DesktopRendererMain.kt`) and the schema owned by the gradle plugin
 * (`gradle-plugin/.../PreviewRenderError.kt`) — `:gradle-plugin` is a separate included build, so
 * the CLI cannot depend on that type. Only the fields this report consumes are modelled;
 * `ignoreUnknownKeys` keeps a newer renderer's extra fields harmless. The frame type is the
 * serve-side [RenderFailureFrame] rather than a second `file`/`line`/`function` triple.
 */
@Serializable
data class RenderErrorSidecar(
  val schema: String = "",
  val exception: String = "",
  val message: String = "",
  val topAppFrame: RenderFailureFrame? = null,
  /**
   * The renderer's one-sentence explanation when the failure was a native-library load rather than
   * the preview's own code. Empty for an ordinary preview throw and for older sidecars.
   */
  val diagnosis: String = "",
  /** Full `Throwable.printStackTrace()` text, including any `Caused by:` chain. */
  val stackTrace: String = "",
)

/** One `Caused by:` entry of a stack trace. */
data class RenderErrorCause(val exception: String, val message: String)

/** Sidecar file name suffix: the would-be output path with `.error.json` appended. */
const val RENDER_ERROR_SIDECAR_SUFFIX: String = ".error.json"

private const val RENDER_ERROR_SCHEMA_PREFIX = "compose-preview-error/"

private val sidecarJson = Json { ignoreUnknownKeys = true }

/**
 * Read the `<output>.error.json` sidecar beside [expectedOutput] (the absolute path the PNG / data
 * product *would* have been written to). Returns `null` when there is no sidecar, when it is
 * unreadable, or when its schema isn't a `compose-preview-error` version — all of which mean "we
 * learned nothing here", never "the render succeeded".
 */
fun readRenderErrorSidecar(
  expectedOutput: File,
  fileSystem: FileSystem = SystemFileSystem,
): RenderErrorSidecar? {
  val path = (expectedOutput.path + RENDER_ERROR_SIDECAR_SUFFIX).toPath()
  if (!fileSystem.exists(path)) return null
  val text = runCatching { fileSystem.read(path) { readUtf8() } }.getOrNull() ?: return null
  val decoded =
    runCatching { sidecarJson.decodeFromString(RenderErrorSidecar.serializer(), text) }.getOrNull()
      ?: return null
  return decoded.takeIf { it.schema.startsWith(RENDER_ERROR_SCHEMA_PREFIX) }
}

/** The capture coordinates of [result] that came back without a PNG, for the offender list. */
internal fun missingCaptureCoords(result: PreviewResult): String =
  result.captures
    .filter { it.pngPath == null && !it.optional }
    .joinToString(", ") { captureCoordLabel(it) }
    .ifEmpty { "default" }

/**
 * Every `Caused by:` entry of [stackTrace]'s **primary** chain, outermost cause first. Empty when
 * the trace carries no cause chain — the outermost throwable is then the whole story and the
 * sidecar's own `exception` / `message` already describe it.
 *
 * `Suppressed:` branches are excluded: a suppressed throwable with a cause of its own (the ordinary
 * shape for a `use {}` / try-with-resources body that threw and then failed to close) is printed by
 * `printStackTrace()` as an *indented* `Caused by:`, so trimming every line first made it
 * indistinguishable from the real chain — and, being printed last, it won the `lastOrNull()` that
 * picks the root cause. The close failure would then be reported as the render's root cause.
 */
fun causeChainOf(stackTrace: String): List<RenderErrorCause> =
  primaryTraceLines(stackTrace)
    .map { it.trim() }
    .filter { it.startsWith(CAUSED_BY_PREFIX) }
    .map { header ->
      val body = header.removePrefix(CAUSED_BY_PREFIX).trim()
      val split = body.indexOf(": ")
      if (split < 0) RenderErrorCause(body, "")
      else RenderErrorCause(body.substring(0, split), body.substring(split + 2).trim())
    }
    .toList()

/**
 * The deepest `Caused by:` entry of [stackTrace] — the failure worth leading with, since the outer
 * throwable is routinely a reflective wrapper. `null` when the trace has no cause chain.
 */
fun rootCauseOf(stackTrace: String): RenderErrorCause? = causeChainOf(stackTrace).lastOrNull()

/**
 * The first stack frame belonging to the *user's own* package, searching the deepest `Caused by:`
 * section first.
 *
 * The renderer's `topAppFrame` is computed from the outermost throwable's frames with a
 * skip-the-framework-prefixes heuristic, which lands on whichever tooling frame invoked the
 * composable — in issue #3741 that was `KeyboardDataProduct.kt:148`, a data-product frame in *this*
 * project, while the frame worth showing was the consumer's `AmbientAwareActivity.kt:76`. Anchoring
 * on the preview class's own package instead makes the one-line summary point at a file the user
 * can open. Package prefixes are tried longest-first (exact package, then parents down to two
 * segments) so a sibling package of the preview still counts, but `com.` never does.
 *
 * Returns `null` when nothing matches, leaving the sidecar's `topAppFrame` as the fallback.
 */
fun preferredAppFrame(stackTrace: String, previewClassName: String): RenderFailureFrame? {
  val prefixes = packagePrefixesOf(previewClassName)
  if (prefixes.isEmpty() || stackTrace.isBlank()) return null
  val sections = traceSections(stackTrace)
  for (section in sections.asReversed()) {
    for (prefix in prefixes) {
      val frame = section.firstNotNullOfOrNull { line ->
        parseFrame(line)?.takeIf { it.className.startsWith("$prefix.") }
      }
      if (frame != null) {
        return RenderFailureFrame(file = frame.file, line = frame.line, function = frame.function)
      }
    }
  }
  return null
}

private const val CAUSED_BY_PREFIX = "Caused by:"
private const val SUPPRESSED_PREFIX = "Suppressed:"

/**
 * [stackTrace]'s lines with every `Suppressed:` branch removed, indentation preserved.
 *
 * `Throwable.printStackTrace()` nests by indentation and nothing else: a suppressed throwable's
 * caption, frames, **and its own `Caused by:` chain** are printed one tab deeper than the throwable
 * that suppressed it (`printEnclosedStackTrace` passes `prefix + "\t"` for suppressed and the
 * unchanged `prefix` for causes). So a block that starts at indent *n* runs until the first
 * non-blank line indented less than *n* — everything in between belongs to the suppressed branch,
 * not to the chain the report walks. Concretely:
 * ```
 * Caused by: java.io.IOException: disk gone      <- primary chain, indent 0
 * 	at App.write(App.kt:3)
 * 	Suppressed: java.lang.RuntimeException: close failed
 * 		at App.close(App.kt:4)
 * 	Caused by: java.net.SocketException: reset    <- the *suppressed* one's cause, indent 1
 * ```
 */
private fun primaryTraceLines(stackTrace: String): List<String> {
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
 * Split a printed stack trace into its throwable sections: the outermost throwable first, then one
 * per `Caused by:`. `Suppressed:` branches are dropped entirely (see [primaryTraceLines]) so
 * neither the cause chain nor the frame search can wander into one — the frame the report prints
 * has to belong to the failure it names.
 */
private fun traceSections(stackTrace: String): List<List<String>> {
  val sections = mutableListOf<MutableList<String>>(mutableListOf())
  for (line in primaryTraceLines(stackTrace)) {
    if (line.trim().startsWith(CAUSED_BY_PREFIX)) sections += mutableListOf<String>()
    sections.last() += line
  }
  return sections
}

private data class ParsedFrame(
  val className: String,
  val function: String,
  val file: String,
  val line: Int,
)

/** `\tat com.example.Foo$bar.invoke(Foo.kt:42)` → its parts; `null` for any other line. */
private fun parseFrame(line: String): ParsedFrame? {
  val match = FRAME_REGEX.find(line) ?: return null
  val (qualified, location) = match.destructured
  val className = qualified.substringBeforeLast('.', "")
  val function = qualified.substringAfterLast('.')
  if (className.isEmpty()) return null
  val colon = location.lastIndexOf(':')
  val file = if (colon > 0) location.substring(0, colon) else location
  val lineNumber = if (colon > 0) location.substring(colon + 1).toIntOrNull() ?: 0 else 0
  // `(Unknown Source)` / `(Native Method)` carry no file — useless as a "open this file" pointer.
  if (lineNumber <= 0) return null
  return ParsedFrame(className, function, file, lineNumber)
}

/**
 * `at [<module>/]<class>.<method>(<file>:<line>)`. The optional leading group swallows the
 * classloader / module qualifier a JPMS-aware JVM prints (`app//com.example.Foo.bar(...)`,
 * `java.base@17/java.lang.reflect.Method.invoke(...)`) — `/` never appears in a class name, so it
 * is unambiguous.
 */
private val FRAME_REGEX = Regex("""^\s*at\s+(?:[\w.@$]*/{1,2})?([\w$.<>-]+)\(([^()]*)\)""")

/**
 * Package prefixes to accept as "the user's own code", longest first: the preview class's package,
 * then each parent down to two segments. Two is the floor because a one-segment prefix (`com`,
 * `org`) would match every library on the classpath.
 */
private fun packagePrefixesOf(className: String): List<String> {
  val pkg = className.substringBeforeLast('.', "")
  if (pkg.isEmpty()) return emptyList()
  val segments = pkg.split('.')
  if (segments.size < 2) return emptyList()
  return (segments.size downTo 2).map { segments.take(it).joinToString(".") }
}
