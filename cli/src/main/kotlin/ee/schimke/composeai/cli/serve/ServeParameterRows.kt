package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.io.SystemFileSystem
import ee.schimke.composeai.previewdata.PreviewInfo
import ee.schimke.composeai.previewdata.PreviewParameterFanout
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/**
 * Expands a parameterized `@PreviewParameter` preview into the **row ids** the daemon can address,
 * by reading the fan-out the render pass already wrote to disk (issue #3749 follow-up).
 *
 * **Why disk and not discovery.** `previews.json` carries one entry per parameterized function —
 * discovery reads bytecode and can't instantiate a `PreviewParameterProvider`, so it has no idea
 * how many values there are. The *renderer* does: it writes `<stem>_<label>.png` /
 * `<stem>_PARAM_<idx>.png` per value (docs/RENDER_FILENAMES.md). `serve`'s Gradle path runs a full
 * render before it starts the server, so those files are sitting there — and the daemon now accepts
 * exactly those `<baseId>_<row>` ids. Reading them back is what turns "the daemon *can* render row
 * 3" into "the viewer lists row 3", with no new protocol surface.
 *
 * **Which files, and what each row is called, is not decided here** — [PreviewParameterFanout] owns
 * that rule, and `PreviewResultBuilder` reads the same fan-out through it for `show` / `list` /
 * `render` (issue #3819). This class contributes the manifest evidence (who claims which output)
 * and the directory listing; sharing the rest is what stops `serve` and the result-shaped commands
 * from disagreeing about what row `Foo_Dark_Alice` is — a disagreement that doesn't merely
 * misreport, it hands you a different row than the one you selected.
 *
 * A preview with no provider, or one whose fan-out isn't on disk (a `serve` run that didn't render
 * — a bundle-backed session, or a render that failed), expands to nothing and keeps its bare id, so
 * this only ever adds rows that genuinely exist.
 */
object ServeParameterRows {

  /** One row of a parameterized preview: the addressable id plus the token that names it. */
  data class Row(val id: String, val label: String)

  /**
   * The rows of [preview] found under [moduleDir]`/build/compose-previews/`, in the fan-out's own
   * order (numeric `PARAM_<idx>` by index first, then labels alphabetically — matching how the CLI
   * orders a fan-out elsewhere). Empty when [preview] declares no provider or nothing matched.
   *
   * [siblingOutputs] must be every *other* preview's capture outputs, used to reject files this
   * preview doesn't own.
   */
  fun rowsFor(
    preview: PreviewInfo,
    moduleDir: Path,
    siblingOutputs: Set<String>,
    fileSystem: FileSystem = SystemFileSystem,
  ): List<Row> {
    if (preview.params.previewParameterProviderClassName.isNullOrBlank()) return emptyList()
    val template =
      preview.captures.firstOrNull { it.renderOutput.isNotBlank() } ?: return emptyList()
    val rel = template.renderOutput
    val dirPart = rel.substringBeforeLast('/', "")

    val root = moduleDir / "build" / "compose-previews"
    val dir = if (dirPart.isEmpty()) root else dirPart.split('/').fold(root) { acc, p -> acc / p }
    val entries = runCatching {
      fileSystem.list(dir)
    }
      .getOrElse {
        return emptyList()
      }

    return PreviewParameterFanout.rowsOf(
        baseId = preview.id,
        templateOutput = rel,
        fileNames = entries.map { it.name },
        siblingOutputs = siblingOutputs,
      )
      .map { Row(id = it.id, label = it.token) }
  }

  /**
   * Every capture output claimed by [previews], as `renderOutput`-relative paths — the exclusion
   * set [rowsFor] needs so one preview's render can't be read as another's row.
   */
  fun claimedOutputs(previews: List<PreviewInfo>): Set<String> =
    previews.flatMapTo(mutableSetOf()) { p ->
      p.captures.map { it.renderOutput }.filter { it.isNotBlank() }
    }

  /** Convenience for callers holding a `java.io.File` project dir (the Tooling API's shape). */
  fun rowsFor(
    preview: PreviewInfo,
    moduleDir: java.io.File,
    siblingOutputs: Set<String>,
    fileSystem: FileSystem = SystemFileSystem,
  ): List<Row> = rowsFor(preview, moduleDir.path.toPath(), siblingOutputs, fileSystem)
}
