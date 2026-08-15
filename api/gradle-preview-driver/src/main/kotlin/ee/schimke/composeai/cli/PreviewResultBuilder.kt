package ee.schimke.composeai.cli

import ee.schimke.composeai.io.SystemFileSystem
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
      for (p in manifest.previews) {
        // Files owned by sibling previews — exclude them from the `<stem>_*` glob so a
        // `Foo_header.png` or permutation template such as `Foo_dark.png` never gets attributed to
        // `Foo`'s parameter fan-out. Parameterized siblings still declare their template capture,
        // and that template is enough to isolate their own rendered rows (`Foo_dark_row.png`).
        val siblingRenderOutputs =
          manifest.previews
            .asSequence()
            .filter { it !== p }
            .flatMap { it.captures.asSequence().map { c -> c.renderOutput } }
            .filter { it.isNotEmpty() }
            .toSet()

        // `@PreviewParameter`-driven previews render at `<stem>_<suffix>.<ext>`, one file per
        // provider value. The manifest carries a single template capture; here we glob the actual
        // fan-out and synthesize a `CaptureResult` per file on disk.
        val captures: List<ExpandedCapture> =
          if (p.params.previewParameterProviderClassName != null) {
            p.captures.flatMap { capture ->
              expandParamCaptures(module, p.id, capture, siblingRenderOutputs, fileSystem)
            }
          } else {
            p.captures.map(::ExpandedCapture)
          }
        val productCaptures =
          p.dataProducts.mapNotNull { it.asPreviewArtifactCapture(module) }.map(::ExpandedCapture)
        val resultCaptures =
          if (captures.map { it.capture }.isSingleStaticCapture() && productCaptures.isNotEmpty()) {
            productCaptures
          } else {
            captures + productCaptures
          }
        val captureResults = resultCaptures.map { expanded ->
          val capture = expanded.capture
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
            parameterLabel = expanded.parameterLabel,
            parameterRowId = expanded.parameterRowId,
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
   * Reads the on-disk `<stem>_<suffix>.<ext>` fan-out for a parameterised preview capture. The
   * manifest carries one template capture (e.g. `renders/foo.png`); the renderer writes one file
   * per provider value, keying each by a derived label (`renders/foo_on.png`) or by numeric index
   * (`renders/foo_PARAM_0.png`) when the label can't be derived. Returns synthetic `Capture` rows
   * pointing at each file, or an empty list when nothing matched — the plugin's
   * `composePreviewRenderAll` verification already fails loudly when a parameterised preview
   * rendered no files at all, so we don't duplicate the error surface here.
   *
   * Which files count, what each row is called, and in what order they come back are all
   * [PreviewParameterFanout]'s to decide — the same rule `serve` addresses its row cards with, so
   * the id printed here is the id `--id` accepts (issue #3819). Only the directory listing is done
   * here.
   */
  private fun expandParamCaptures(
    module: PreviewModule,
    baseId: String,
    template: Capture,
    siblingRenderOutputs: Set<String>,
    fileSystem: FileSystem = SystemFileSystem,
  ): List<ExpandedCapture> {
    val rel =
      template.renderOutput.ifEmpty {
        return listOf(ExpandedCapture(template))
      }
    val file = module.projectDir.resolve("build/compose-previews/$rel").canonicalFile
    val dir = file.parentFile ?: return listOf(ExpandedCapture(template))
    val fileNames = fileSystem.listOrNull(dir.path.toPath())?.map { it.name } ?: return emptyList()
    val rows =
      PreviewParameterFanout.rowsOf(
        baseId = baseId,
        templateOutput = rel,
        fileNames = fileNames,
        siblingOutputs = siblingRenderOutputs,
      )
    if (rows.isEmpty()) return emptyList()
    // Preserve the template's time/scroll coordinates — a parameterised preview is orthogonal to
    // those dimensions. Each fan-out file points back at the same conceptual capture, just at a
    // different provider value.
    return rows.map { row ->
      ExpandedCapture(
        capture =
          Capture(
            advanceTimeMillis = template.advanceTimeMillis,
            scroll = template.scroll,
            renderOutput = row.output,
          ),
        parameterLabel = PreviewParameterFanout.label(row.token),
        parameterRowId = row.id,
      )
    }
  }

  private data class ExpandedCapture(
    val capture: Capture,
    val parameterLabel: String? = null,
    val parameterRowId: String? = null,
  )
}

/**
 * Whether a `@PreviewParameter` fan-out candidate belongs to a **more specific sibling** template
 * rather than to [templateOutput].
 *
 * Fan-out files are found by globbing `<stem>_*` beside the template, because only the provider
 * knows its values — and that glob is ambiguous whenever one preview's template is a prefix of
 * another's. With templates `Foo.png` and `Foo_Dark.png` in one directory, `Foo_Dark_Alice.png` is
 * `Foo_Dark`'s row, not `Foo`'s, even though it matches `Foo_`. Attributing it to `Foo` would show
 * one preview's exception under another preview's name.
 *
 * The rule: a sibling owns the candidate when it lives in the same directory, shares its extension,
 * has a *longer* stem than the template (so it is the more specific match), and the candidate's
 * leaf begins with that sibling's stem plus `_`.
 *
 * Shared rather than duplicated: [PreviewResultBuilder] applies it when expanding fan-out captures
 * from files that exist, and the CLI's missing-render resolver applies it when attributing fan-out
 * `.error.json` sidecars. Two copies of this rule would drift, and a drift means one preview's
 * failure reported under another's name.
 */
fun parameterFanoutOwnedBySibling(
  templateOutput: String,
  siblingOutput: String,
  candidateOutput: String,
): Boolean {
  if (siblingOutput.isEmpty()) return false
  val siblingDir = siblingOutput.substringBeforeLast('/', "")
  val candidateDir = candidateOutput.substringBeforeLast('/', "")
  if (siblingDir != candidateDir) return false
  val templateStem = templateOutput.substringAfterLast('/').substringBeforeLast('.', "")
  val siblingLeaf = siblingOutput.substringAfterLast('/')
  val candidateLeaf = candidateOutput.substringAfterLast('/')
  val siblingDot = siblingLeaf.lastIndexOf('.')
  val candidateDot = candidateLeaf.lastIndexOf('.')
  if (siblingDot <= 0 || candidateDot <= 0) return false
  val siblingStem = siblingLeaf.substring(0, siblingDot)
  if (siblingStem.length <= templateStem.length) return false
  val siblingExt = siblingLeaf.substring(siblingDot)
  if (candidateLeaf.substring(candidateDot) != siblingExt) return false
  return candidateLeaf.startsWith(siblingStem + "_")
}
