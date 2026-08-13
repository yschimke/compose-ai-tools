package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.cli.PreviewInfo
import ee.schimke.composeai.io.SystemFileSystem
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
 * **Why not just glob `<stem>_*`.** Preview ids are `_`-joined, so a *different* preview can own a
 * filename that looks like a row of this one — `Foo` and `Foo_Dark` are both real previews when a
 * multi-preview annotation is in play, and `renders/Foo_Dark.png` belongs to the latter. Any file
 * another preview in the manifest claims as its own capture output is therefore excluded, which is
 * the same "longest base wins" hazard `PreviewRowAddress` handles on the daemon side, resolved here
 * with the stronger evidence available: the manifest says who owns what.
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
    val leaf = rel.substringAfterLast('/')
    val dot = leaf.lastIndexOf('.')
    if (dot <= 0) return emptyList()
    val stem = leaf.substring(0, dot)
    val ext = leaf.substring(dot)

    val root = moduleDir / "build" / "compose-previews"
    val dir = if (dirPart.isEmpty()) root else dirPart.split('/').fold(root) { acc, p -> acc / p }
    val entries =
      runCatching { fileSystem.list(dir) }
        .getOrElse {
          return emptyList()
        }

    val prefix = "${stem}_"
    return entries
      .mapNotNull { path ->
        val name = path.name
        if (!name.startsWith(prefix) || !name.endsWith(ext)) return@mapNotNull null
        // Another preview's own render, not a row of this one.
        val asOutput = if (dirPart.isEmpty()) name else "$dirPart/$name"
        if (asOutput in siblingOutputs) return@mapNotNull null
        val token = name.substring(prefix.length, name.length - ext.length)
        token.takeIf { it.isNotEmpty() }
      }
      .distinct()
      .sortedWith(rowOrder())
      .map { Row(id = "${preview.id}_$it", label = it) }
  }

  /**
   * Every capture output claimed by [previews], as `renderOutput`-relative paths — the exclusion
   * set [rowsFor] needs so one preview's render can't be read as another's row.
   */
  fun claimedOutputs(previews: List<PreviewInfo>): Set<String> =
    previews.flatMapTo(mutableSetOf()) { p ->
      p.captures.map { it.renderOutput }.filter { it.isNotBlank() }
    }

  /**
   * Numeric `PARAM_<idx>` rows first, ordered by index so `PARAM_10` follows `PARAM_2` rather than
   * preceding it as lexicographic order would; labelled rows after, alphabetically. Provider order
   * isn't recoverable from a filename, so alphabetical is the stable, readable choice — the same
   * rule `PreviewResultBuilder` applies to the static fan-out.
   */
  private fun rowOrder(): Comparator<String> = Comparator { a, b ->
    val ia = a.removePrefix(INDEX_PREFIX).toIntOrNull()?.takeIf { a.startsWith(INDEX_PREFIX) }
    val ib = b.removePrefix(INDEX_PREFIX).toIntOrNull()?.takeIf { b.startsWith(INDEX_PREFIX) }
    when {
      ia != null && ib != null -> ia.compareTo(ib)
      ia != null -> -1
      ib != null -> 1
      else -> a.compareTo(b)
    }
  }

  private const val INDEX_PREFIX = "PARAM_"

  /** Convenience for callers holding a `java.io.File` project dir (the Tooling API's shape). */
  fun rowsFor(
    preview: PreviewInfo,
    moduleDir: java.io.File,
    siblingOutputs: Set<String>,
    fileSystem: FileSystem = SystemFileSystem,
  ): List<Row> = rowsFor(preview, moduleDir.path.toPath(), siblingOutputs, fileSystem)
}
