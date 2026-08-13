package ee.schimke.composeai.cli

import ee.schimke.composeai.cli.serve.RenderFailureFrame
import ee.schimke.composeai.io.SystemFileSystem
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path.Companion.toPath

/*
 * Why this file exists (issue #3741).
 *
 * When a preview renders nothing, `show` / `render` used to print one fixed paragraph blaming the
 * *build wiring* ("`composePreviewRender` reported NO-SOURCE, the renderer test class wasn't on
 * testClassesDirs"). That guess is wrong whenever the render task actually ran and the preview
 * threw — and in that case the renderer has already written the precise cause next to where the
 * PNG would have gone:
 *
 *   <module>/build/compose-previews/renders/<Stem>.png.error.json
 *
 * The two cases want opposite remedies (fix the classpath vs. fix the composable), so the report
 * distinguishes them explicitly: a sidecar means "rendered and threw — build wiring is fine", no
 * sidecar means "the task really was skipped" and keeps the historical NO-SOURCE guidance.
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

/**
 * One preview that produced no PNG, paired with whatever the renderer left beside its would-be
 * output. [sidecar] is `null` when no `.error.json` was found — the "render was skipped" case.
 */
data class MissingRender(
  val id: String,
  val module: String,
  /** Human-readable capture coordinates that came back empty, e.g. `default`, `500ms`. */
  val coords: String,
  /** The preview's own class FQN — used to pick a stack frame in the *user's* package. */
  val className: String = "",
  val sidecar: RenderErrorSidecar? = null,
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

/**
 * Pair each missing preview with its sidecar, looking beside every output the manifest says that
 * preview should have produced (each capture's `renderOutput`, then its data products). The first
 * sidecar found wins — one broken composable fails all of its outputs with the same throwable.
 *
 * Uses the manifest rather than the [PreviewResult] because a missing capture carries no path:
 * `pngPath` is null precisely because the file isn't there, so the *expected* location has to come
 * from `renderOutput` resolved against the module's `build/compose-previews` directory — the same
 * resolution [PreviewResultBuilder.build] does for outputs that exist.
 */
fun collectMissingRenders(
  missing: List<PreviewResult>,
  manifests: List<Pair<PreviewModule, PreviewManifest>>,
  fileSystem: FileSystem = SystemFileSystem,
): List<MissingRender> {
  val moduleByPath = manifests.associate { (module, _) -> module.gradlePath to module }
  val previewsByModule = manifests.associate { (module, manifest) ->
    module.gradlePath to manifest.previews.associateBy { it.id }
  }
  return missing.map { result ->
    val module = moduleByPath[result.module]
    val preview = previewsByModule[result.module]?.get(result.id)
    val relativeOutputs =
      buildList {
          preview?.captures?.forEach { if (it.renderOutput.isNotEmpty()) add(it.renderOutput) }
          preview?.dataProducts?.forEach { if (it.output.isNotEmpty()) add(it.output) }
          // Manifests written before `renderOutput` was mandatory, and previews whose captures were
          // globbed away entirely, still render to the default stem.
          add("renders/${result.id}.png")
        }
        .distinct()
    val sidecar = module?.let { m ->
      relativeOutputs.firstNotNullOfOrNull { rel ->
        readRenderErrorSidecar(m.projectDir.resolve("build/compose-previews/$rel"), fileSystem)
      }
    }
    MissingRender(
      id = result.id,
      module = result.module,
      coords = missingCaptureCoords(result),
      className = result.className,
      sidecar = sidecar,
    )
  }
}

/** The capture coordinates of [result] that came back without a PNG, for the offender list. */
internal fun missingCaptureCoords(result: PreviewResult): String =
  result.captures
    .filter { it.pngPath == null && !it.optional }
    .joinToString(", ") { captureCoordLabel(it) }
    .ifEmpty { "default" }

/**
 * The stderr report for a run that produced no PNG for [missing] of [total] previews. Pure function
 * over already-read sidecars — the disk read lives in [readRenderErrorSidecar] — so the wording of
 * both branches is unit-testable without standing up a Gradle render.
 *
 * [prefix] carries the `missing-renders policy=…` tag when the policy opts the exit code down.
 */
fun formatMissingRenderReport(
  missing: List<MissingRender>,
  total: Int,
  prefix: String = "",
): String {
  val sb = StringBuilder()
  sb
    .append(prefix)
    .append("Render task completed but produced no PNG for ")
    .append(missing.size)
    .append(" of ")
    .append(total)
    .append(" preview(s):")
  for (entry in missing) {
    val moduleTag = if (entry.module.isNotBlank()) " (${entry.module})" else ""
    sb.append("\n  - ").append(entry.id).append(moduleTag).append(" — no PNG for: ")
    sb.append(entry.coords)
    entry.sidecar?.let { sb.appendSidecarDetail(it, entry.className) }
  }
  val threw = missing.filter { it.sidecar != null }
  val skipped = missing.filter { it.sidecar == null }
  if (threw.isNotEmpty()) {
    // The whole point of issue #3741: an error sidecar proves the render task ran, so the
    // NO-SOURCE / testClassesDirs guidance below must NOT be printed for these.
    sb
      .append("\n")
      .append(threw.size)
      .append(" preview(s) rendered and then threw — the build wiring is fine. Full stack traces ")
      .append("are in the `<render>.png")
      .append(RENDER_ERROR_SIDECAR_SUFFIX)
      .append("` sidecar beside each preview's would-be output.")
  }
  if (skipped.isNotEmpty()) {
    if (threw.isNotEmpty()) {
      sb
        .append("\nNo error sidecar for ")
        .append(skipped.size)
        .append(" preview(s) (")
        .append(skipped.take(5).joinToString(", ") { it.id })
        .append(if (skipped.size > 5) ", …): " else "): ")
    } else {
      sb.append("\n")
    }
    sb.append(
      "Check the Gradle output above — a common cause is the `composePreviewRender` task " +
        "reporting NO-SOURCE, which means the renderer test class wasn't found on " +
        "testClassesDirs. Per-preview stack traces are in the `composePreviewRender-reports` " +
        "artifact attached to the run."
    )
  }
  return sb.toString()
}

/**
 * The `threw X: msg (at File.kt:42 in fn)` detail lines for one failing preview.
 *
 * Leads with the **root** cause rather than the outermost throwable: the renderer invokes the
 * preview reflectively, so the outer exception is routinely a `InvocationTargetException` that says
 * nothing at all, while the last `Caused by:` in the trace is the real failure (issue #3741's case:
 * `NoClassDefFoundError: com/google/wear/services/ambient/AmbientComponentState`).
 */
private fun StringBuilder.appendSidecarDetail(sidecar: RenderErrorSidecar, className: String) {
  val chain = causeChainOf(sidecar.stackTrace)
  val root = chain.lastOrNull()
  val exception = root?.exception?.takeIf { it.isNotBlank() } ?: sidecar.exception
  val message = if (root != null) root.message else sidecar.message
  val frame = preferredAppFrame(sidecar.stackTrace, className) ?: sidecar.topAppFrame
  append("\n      threw ").append(exception.substringAfterLast('.'))
  if (message.isNotBlank()) append(": ").append(message)
  if (frame != null && frame.file.isNotBlank()) {
    append(" (at ").append(frame.file)
    if (frame.line > 0) append(':').append(frame.line)
    if (frame.function.isNotBlank()) append(" in ").append(frame.function)
    append(')')
  }
  if (chain.isNotEmpty()) {
    // The whole `Caused by:` chain, outermost first — the wrapper says *how* the renderer reached
    // the failure (reflective invoke, class initialisation), which the root cause alone hides.
    val names =
      (listOf(sidecar.exception) + chain.map { it.exception })
        .filter { it.isNotBlank() }
        .map { it.substringAfterLast('.') }
    append("\n        chain: ").append(names.joinToString(" → "))
  }
  if (sidecar.diagnosis.isNotBlank()) append("\n        ").append(sidecar.diagnosis)
}

/**
 * Every `Caused by:` entry of [stackTrace], outermost cause first. Empty when the trace carries no
 * cause chain — the outermost throwable is then the whole story and the sidecar's own `exception` /
 * `message` already describe it.
 */
fun causeChainOf(stackTrace: String): List<RenderErrorCause> =
  stackTrace
    .lineSequence()
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

/**
 * Split a printed stack trace into its throwable sections: the outermost throwable first, then one
 * per `Caused by:`. `Suppressed:` blocks stay attached to the section they were printed in — they
 * are not part of the cause chain the report walks.
 */
private fun traceSections(stackTrace: String): List<List<String>> {
  val sections = mutableListOf<MutableList<String>>(mutableListOf())
  for (line in stackTrace.lineSequence()) {
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
