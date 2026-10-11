package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.io.SystemFileSystem
import ee.schimke.composeai.previewdata.PreviewInfo
import ee.schimke.composeai.previewdata.PreviewParameterFanout
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/**
 * Expands a `@PreviewParameter` preview into the row ids the daemon can address, by reading the
 * fan-out the render already wrote (`<stem>_<label>.png` / `<stem>_PARAM_<idx>.png`,
 * docs/RENDER_FILENAMES.md). Discovery can't instantiate providers, so only the render knows the
 * rows.
 *
 * [PreviewParameterFanout] owns which files and row names count, shared with `PreviewResultBuilder`
 * so `serve` and `show` / `list` / `render` agree; this class supplies the manifest evidence and
 * the directory listing. With no provider or no fan-out on disk, the preview keeps its bare id.
 */
public object ServeParameterRows {

  /** One row of a parameterized preview: the addressable id plus the token that names it. */
  public data class Row(val id: String, val label: String)

  /**
   * The rows of [preview] under [moduleDir]`/build/compose-previews/`, in fan-out order
   * (`PARAM_<idx>` numerically, then labels alphabetically); empty when there are none.
   * [siblingOutputs] are the other previews' outputs, used to reject files this preview doesn't
   * own.
   */
  public fun rowsFor(
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
  public fun claimedOutputs(previews: List<PreviewInfo>): Set<String> =
    previews.flatMapTo(mutableSetOf()) { p ->
      p.captures.map { it.renderOutput }.filter { it.isNotBlank() }
    }

  /** Convenience for callers holding a `java.io.File` project dir (the Tooling API's shape). */
  public fun rowsFor(
    preview: PreviewInfo,
    moduleDir: java.io.File,
    siblingOutputs: Set<String>,
    fileSystem: FileSystem = SystemFileSystem,
  ): List<Row> = rowsFor(preview, moduleDir.path.toPath(), siblingOutputs, fileSystem)
}
