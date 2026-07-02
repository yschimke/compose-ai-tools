package ee.schimke.composeai.cli

import ee.schimke.composeai.io.SystemFileSystem
import java.io.File
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * Pure-function builder that turns a list of `(PreviewModule, PreviewManifest)` pairs into
 * [PreviewResult]s with PNG paths and sha256s populated, expanding `@PreviewParameter` fan-outs and
 * data-product artefact captures along the way.
 *
 * This is the manifest-to-result step shared by the CLI (`Command.buildResults`) and by external
 * driver consumers ([GradlePreviewDriver.render]). The CLI layers state-file change detection,
 * image-size override for hosting agents, and per-extension annotation
 * (`A11yReportRenderer.annotate`) on top of the base results returned here; the driver returns them
 * as-is.
 *
 * Results are returned with `changed = null` on every capture — the driver doesn't track diff
 * state. Consumers that want change detection track `sha256` against their own prior run.
 */
object PreviewResultBuilder {

  /**
   * Read a module's `build/compose-previews/previews.json` and decode it into a [PreviewManifest].
   * Returns `null` when the file doesn't exist — `composePreviewRenderAll` is allowed to skip a
   * module that has no previews discovered yet.
   */
  fun readManifest(
    module: PreviewModule,
    fileSystem: FileSystem = SystemFileSystem,
  ): PreviewManifest? {
    // `projectDir` stays a `File` (it crosses the Gradle Tooling API serialization boundary and
    // feeds `forProjectDirectory`); the manifest read itself goes through Okio.
    val manifestPath = module.projectDir.path.toPath() / "build/compose-previews/previews.json"
    if (!fileSystem.exists(manifestPath)) return null
    val text = fileSystem.read(manifestPath) { readUtf8() }
    return manifestJson.decodeFromString(text)
  }

  /**
   * Convenience over [readManifest] — drops modules whose manifest file isn't on disk yet.
   * Order-preserving.
   */
  fun readAllManifests(
    modules: List<PreviewModule>,
    fileSystem: FileSystem = SystemFileSystem,
  ): List<Pair<PreviewModule, PreviewManifest>> {
    return modules.mapNotNull { module -> readManifest(module, fileSystem)?.let { module to it } }
  }

  /**
   * Build the base [PreviewResult] list for [manifests]. Per-manifest:
   * - Each `PreviewInfo` becomes one `PreviewResult` with its captures expanded for
   *   `@PreviewParameter` fan-out and merged with data-product artefact captures (PNG / GIF).
   * - Each `CaptureResult` carries the absolute PNG path (when the file exists), its sha256, and
   *   `changed = null` (diff state is the caller's concern).
   * - The result's top-level `pngPath` / `sha256` / `changed` mirror the first capture, for
   *   back-compat with consumers that haven't migrated to the `captures` list.
   *
   * No side effects — pure function over the manifest data + on-disk PNG files.
   */
  fun build(
    manifests: List<Pair<PreviewModule, PreviewManifest>>,
    fileSystem: FileSystem = SystemFileSystem,
  ): List<PreviewResult> {
    val results = mutableListOf<PreviewResult>()
    for ((module, manifest) in manifests) {
      // Files owned by non-parameterised siblings — exclude them from the `<stem>_*` glob so a
      // `Foo_header.png` that belongs to a different preview never gets attributed to `Foo`'s
      // fan-out.
      val siblingRenderOutputs =
        manifest.previews
          .filter { it.params.previewParameterProviderClassName == null }
          .flatMap { it.captures.map { c -> c.renderOutput } }
          .filter { it.isNotEmpty() }
          .toSet()

      for (p in manifest.previews) {
        // `@PreviewParameter`-driven previews render at `<stem>_<suffix>.<ext>`, one file per
        // provider value. The manifest carries a single template capture; here we glob the actual
        // fan-out and synthesize a `CaptureResult` per file on disk.
        val captures =
          if (p.params.previewParameterProviderClassName != null) {
            p.captures.flatMap { capture ->
              expandParamCaptures(module, capture, siblingRenderOutputs, fileSystem)
            }
          } else {
            p.captures
          }
        val productCaptures = p.dataProducts.mapNotNull { it.asPreviewArtifactCapture(module) }
        val resultCaptures =
          if (captures.isSingleStaticCapture() && productCaptures.isNotEmpty()) {
            productCaptures
          } else {
            captures + productCaptures
          }
        val captureResults = resultCaptures.map { capture ->
          val pngFile =
            capture.renderOutput
              .takeIf { it.isNotEmpty() }
              ?.let { module.projectDir.resolve("build/compose-previews/$it").canonicalFile }
              ?.takeIf { it.exists() }
          val sha = pngFile?.let { previewSha256(it) }
          CaptureResult(
            advanceTimeMillis = capture.advanceTimeMillis,
            scroll = capture.scroll,
            pngPath = pngFile?.absolutePath,
            sha256 = sha,
            changed = null,
            optional = capture.optional,
          )
        }
        val first = captureResults.firstOrNull()
        results +=
          PreviewResult(
            id = p.id,
            module = module.gradlePath,
            functionName = p.functionName,
            className = p.className,
            sourceFile = p.sourceFile,
            params = p.params,
            captures = captureResults,
            pngPath = first?.pngPath,
            sha256 = first?.sha256,
            changed = null,
          )
      }
    }
    return results
  }

  private val manifestJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
  }

  private fun List<Capture>.isSingleStaticCapture(): Boolean =
    size == 1 && single().advanceTimeMillis == null && single().scroll == null

  private fun PreviewDataProduct.asPreviewArtifactCapture(module: PreviewModule): Capture? {
    if (output.isBlank()) return null
    val isImageOrAnimation =
      mediaTypes.any { it.startsWith("image/") } ||
        output.endsWith(".png") ||
        output.endsWith(".gif")
    if (!isImageOrAnimation) return null
    if (!module.projectDir.resolve("build/compose-previews/$output").exists()) return null
    return Capture(advanceTimeMillis = advanceTimeMillis, scroll = scroll, renderOutput = output)
  }

  /**
   * Globs the on-disk `<stem>_<suffix>.<ext>` fan-out for a parameterised preview capture. The
   * manifest carries one template capture (e.g. `renders/foo.png`); the renderer writes one file
   * per provider value, keying each by a derived label (`renders/foo_on.png`) or by numeric index
   * (`renders/foo_PARAM_0.png`) when the label can't be derived. Returns synthetic `Capture` rows
   * pointing at each file, or an empty list when nothing matched — the plugin's
   * `composePreviewRenderAll` verification already fails loudly when a parameterised preview
   * rendered no files at all, so we don't duplicate the error surface here.
   */
  private fun expandParamCaptures(
    module: PreviewModule,
    template: Capture,
    siblingRenderOutputs: Set<String>,
    fileSystem: FileSystem = SystemFileSystem,
  ): List<Capture> {
    val rel =
      template.renderOutput.ifEmpty {
        return listOf(template)
      }
    val file = module.projectDir.resolve("build/compose-previews/$rel").canonicalFile
    val dir = file.parentFile ?: return listOf(template)
    val prefix = file.nameWithoutExtension + "_"
    val ext = ".${file.extension}"
    val templateDir = rel.substringBeforeLast('/', "")
    val dirPrefix = if (templateDir.isEmpty()) "" else "$templateDir/"
    val matches =
      (fileSystem.listOrNull(dir.path.toPath())?.map { it.toFile() } ?: emptyList())
        .filter { f ->
          f.name.startsWith(prefix) &&
            f.name.endsWith(ext) &&
            (dirPrefix + f.name) !in siblingRenderOutputs
        }
        .sortedWith(paramFanoutOrder(prefix, ext))
    if (matches.isEmpty()) return emptyList()
    // Preserve the template's time/scroll coordinates — a parameterised preview is orthogonal to
    // those dimensions. Each fan-out file points back at the same conceptual capture, just at a
    // different provider value.
    return matches.map { f ->
      Capture(
        advanceTimeMillis = template.advanceTimeMillis,
        scroll = template.scroll,
        renderOutput = dirPrefix + f.name,
      )
    }
  }

  /**
   * Stable ordering for a fan-out's on-disk files. Numeric `_PARAM_<idx>` entries come first,
   * sorted by index (so `PARAM_10` lands after `PARAM_2` rather than before it as lexicographic
   * ordering would produce). Label-based entries sort alphabetically by their suffix — provider
   * order isn't recoverable from the filename alone, but alphabetical is stable and readable.
   */
  private fun paramFanoutOrder(prefix: String, ext: String): Comparator<File> = Comparator { a, b ->
    val sa = a.name.removePrefix(prefix).removeSuffix(ext)
    val sb = b.name.removePrefix(prefix).removeSuffix(ext)
    val ia = sa.removePrefix("PARAM_").toIntOrNull()?.takeIf { sa.startsWith("PARAM_") }
    val ib = sb.removePrefix("PARAM_").toIntOrNull()?.takeIf { sb.startsWith("PARAM_") }
    when {
      ia != null && ib != null -> ia.compareTo(ib)
      ia != null -> -1
      ib != null -> 1
      else -> sa.compareTo(sb)
    }
  }
}
