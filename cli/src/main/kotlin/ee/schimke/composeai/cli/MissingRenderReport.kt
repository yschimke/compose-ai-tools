package ee.schimke.composeai.cli

import ee.schimke.composeai.cli.serve.RenderFailureFrame
import ee.schimke.composeai.io.SystemFileSystem
import ee.schimke.composeai.previewdata.PreviewResult
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path.Companion.toPath

/*
 * Reading the renderer's error sidecar
 * (`<module>/build/compose-previews/renders/<Stem>.png.error.json`) and its stack trace. When a
 * preview threw, the sidecar holds the real cause, which beats guessing at build wiring. What may
 * be said about it is decided in [PreviewDiagnosis] and worded in `MissingRenderMessage.kt`; this
 * file only reads evidence.
 */

/**
 * The renderer's per-preview `compose-preview-error/v1` sidecar. Mirrors the writers
 * (`RenderErrorSidecar.kt`, `DesktopRendererMain.kt`) and the plugin's `PreviewRenderError.kt`
 * schema, which the CLI can't depend on. Only consumed fields are modelled; frames reuse
 * [RenderFailureFrame].
 */
@Serializable
data class RenderErrorSidecar(
  val schema: String = "",
  val exception: String = "",
  val message: String = "",
  val topAppFrame: RenderFailureFrame? = null,
  /** The renderer's explanation when the failure was a native-library load; empty otherwise. */
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
 * Read the `<output>.error.json` beside [expectedOutput] (where the output would have been
 * written). Null when absent, unreadable, or not a `compose-preview-error` schema — "learned
 * nothing", never "succeeded".
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
 * Every `Caused by:` of [stackTrace]'s primary chain, outermost first; empty when there is none.
 * `Suppressed:` branches are excluded: their own (indented) `Caused by:` lines would otherwise be
 * mistaken for the root cause.
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

/** The deepest `Caused by:` — the failure to lead with — or null when there is no chain. */
fun rootCauseOf(stackTrace: String): RenderErrorCause? = causeChainOf(stackTrace).lastOrNull()

/**
 * The first frame in the user's own package, searching the deepest `Caused by:` section first. The
 * renderer's `topAppFrame` often lands on a tooling frame; anchoring on the preview class's package
 * points at a file the user can open. Prefixes go from the exact package down to two segments. Null
 * when nothing matches (the caller falls back to `topAppFrame`).
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
 * Split a stack trace into throwable sections (outermost, then each `Caused by:`), dropping
 * `Suppressed:` branches ([primaryTraceLines]) so frames always belong to the failure named.
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
 * `at [<module>/]<class>.<method>(<file>:<line>)`; the optional group swallows JPMS qualifiers
 * (`app//…`, `java.base@17/…`), unambiguous since `/` never appears in class names.
 */
private val FRAME_REGEX = Regex("""^\s*at\s+(?:[\w.@$]*/{1,2})?([\w$.<>-]+)\(([^()]*)\)""")

/**
 * Package prefixes counted as user code, longest first, down to two segments (one would match every
 * `com.`/`org.` library).
 */
private fun packagePrefixesOf(className: String): List<String> {
  val pkg = className.substringBeforeLast('.', "")
  if (pkg.isEmpty()) return emptyList()
  val segments = pkg.split('.')
  if (segments.size < 2) return emptyList()
  return (segments.size downTo 2).map { segments.take(it).joinToString(".") }
}
