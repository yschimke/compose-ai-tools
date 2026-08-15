package ee.schimke.composeai.cli

/**
 * The **one** rule for turning a `@PreviewParameter` fan-out on disk into addressable **row ids**
 * (`<baseId>_<row>`), shared by everything that has to agree about what a row is called.
 *
 * **Why one rule.** Discovery emits a single manifest entry per parameterized *function* — it reads
 * bytecode and cannot instantiate a `PreviewParameterProvider` — so the row set only exists as the
 * `<stem>_<label>.png` / `<stem>_PARAM_<idx>.png` files the renderer wrote
 * (docs/RENDER_FILENAMES.md). Two places used to read those files back with two hand-rolled globs:
 * [PreviewResultBuilder] (per-value captures for `show` / `list` / `render`) and
 * `ServeParameterRows` (per-row cards for `serve`). They had already drifted — only the builder
 * rejected a candidate owned by a *more specific* sibling template
 * ([parameterFanoutOwnedBySibling]), so with previews `Foo` and `Foo_Dark` in one directory
 * `Foo_Dark_Alice.png` was `Foo_Dark`'s row to the builder and *also* `Foo`'s row to `serve`. A
 * drift here doesn't merely disagree, it mis-addresses: you select one row and get another. So the
 * glob, the exclusions, the token, the ordering and the id shape all live here, and both callers
 * keep only their own IO.
 *
 * Callers pass leaf **file names** rather than paths so this stays a pure function over a directory
 * listing — testable without a render, and indifferent to whether the listing came from Okio, a
 * `java.io.File`, or a fake.
 */
object PreviewParameterFanout {

  /** One value of a provider: the file it rendered to, the token that names it, and its row id. */
  data class Row(val id: String, val token: String, val output: String)

  /** The `<stem>_PARAM_<idx>` spelling the renderer falls back to when no label can be derived. */
  const val INDEX_PREFIX: String = "PARAM_"

  /**
   * The rows of the preview whose id is [baseId] and whose manifest template capture renders to
   * [templateOutput] (a `build/compose-previews/`-relative path such as `renders/Foo.png`), given
   * the leaf [fileNames] of the directory that template lives in.
   *
   * [siblingOutputs] is every capture output claimed by *other* previews, in the same relative
   * form. Two exclusions come out of it, and both are load-bearing because preview ids are
   * `_`-joined so a different preview can own a file that looks like a row of this one:
   * 1. an exact claim — `renders/Foo_Dark.png` is `Foo_Dark`'s own render, not `Foo`'s row;
   * 2. a longer-stemmed sibling's fan-out — `renders/Foo_Dark_Alice.png` is a row of `Foo_Dark`,
   *    the more specific template ([parameterFanoutOwnedBySibling]).
   *
   * Ordered by [tokenOrder]. Empty when the template names no file, has no extension, or nothing on
   * disk matched — the caller decides what an empty fan-out means (the builder keeps no captures,
   * `serve` keeps the bare id).
   */
  fun rowsOf(
    baseId: String,
    templateOutput: String,
    fileNames: List<String>,
    siblingOutputs: Set<String>,
  ): List<Row> {
    if (templateOutput.isEmpty()) return emptyList()
    val dirPart = templateOutput.substringBeforeLast('/', "")
    val leaf = templateOutput.substringAfterLast('/')
    val dot = leaf.lastIndexOf('.')
    if (dot <= 0) return emptyList()
    val prefix = leaf.substring(0, dot) + "_"
    val ext = leaf.substring(dot)
    val dirPrefix = if (dirPart.isEmpty()) "" else "$dirPart/"

    return fileNames
      .asSequence()
      .filter {
        it.startsWith(prefix) && it.endsWith(ext) && it.length > prefix.length + ext.length
      }
      .map { name -> name.substring(prefix.length, name.length - ext.length) to dirPrefix + name }
      .filter { (_, output) ->
        output !in siblingOutputs &&
          siblingOutputs.none { sibling ->
            parameterFanoutOwnedBySibling(
              templateOutput = templateOutput,
              siblingOutput = sibling,
              candidateOutput = output,
            )
          }
      }
      .distinctBy { (token, _) -> token }
      .sortedWith(compareBy(tokenOrder) { (token, _) -> token })
      .map { (token, output) -> Row(id = rowId(baseId, token), token = token, output = output) }
      .toList()
  }

  /**
   * The addressable id of one row: `<baseId>_<token>`, exactly the shape the daemon accepts on
   * `renderNow` and `--id` accepts as a selector. The single place that spelling is written down.
   */
  fun rowId(baseId: String, token: String): String = "${baseId}_$token"

  /**
   * Human-readable coordinate for a row, for output that shows a row *under* its preview rather
   * than addressing it: `PARAM_3` reads as `parameter 3`, `Crimson_Red` as `Crimson Red`. Null when
   * the token carries nothing to say. Distinct from [rowId] on purpose — this one is lossy, and a
   * selector must never be derived from it.
   */
  fun label(token: String): String? {
    val parameterIndex =
      token.removePrefix(INDEX_PREFIX).toIntOrNull()?.takeIf { token.startsWith(INDEX_PREFIX) }
    return if (parameterIndex != null) "parameter $parameterIndex"
    else token.replace('_', ' ').trim().ifEmpty { null }
  }

  /**
   * Numeric `PARAM_<idx>` rows first, ordered by index so `PARAM_10` follows `PARAM_2` rather than
   * preceding it as lexicographic order would; labelled rows after, alphabetically. Provider order
   * isn't recoverable from a filename, so alphabetical is the stable, readable choice.
   */
  val tokenOrder: Comparator<String> = Comparator { a, b ->
    val ia = a.removePrefix(INDEX_PREFIX).toIntOrNull()?.takeIf { a.startsWith(INDEX_PREFIX) }
    val ib = b.removePrefix(INDEX_PREFIX).toIntOrNull()?.takeIf { b.startsWith(INDEX_PREFIX) }
    when {
      ia != null && ib != null -> ia.compareTo(ib)
      ia != null -> -1
      ib != null -> 1
      else -> a.compareTo(b)
    }
  }
}
