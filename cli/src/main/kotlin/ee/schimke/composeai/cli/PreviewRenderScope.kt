package ee.schimke.composeai.cli

import ee.schimke.composeai.previewdata.PreviewManifest
import ee.schimke.composeai.previewdata.PreviewModule

/**
 * Turns a `--id` / `--filter` / `--preview` request into the Gradle property that narrows the
 * render itself, rather than rendering the whole module and filtering output.
 *
 * Forwards an explicit, [ANCHOR]ed id list rather than the pattern: `--filter` is case-insensitive
 * ([previewIdMatchesRequest]) while `composePreview.idFilter` is case-sensitive
 * (`PreviewNameFilter.matchesId`), so the CLI resolves against the discovery manifest and only one
 * matcher ever runs. Anchoring stops a base id pulling in its variant/row siblings.
 *
 * Only applied when it genuinely narrows: a filtered `composePreviewRender` isn't build-cacheable,
 * so a filter selecting everything returns no arguments ([forRequest]).
 */
internal object PreviewRenderScope {

  /** The Gradle property `composePreviewRender` reads id patterns from, on both backends. */
  const val GRADLE_PROPERTY: String = "composePreview.idFilter"

  /**
   * Delimiter- and encoding-safe form of [GRADLE_PROPERTY]: a path to a newline-delimited UTF-8
   * list (the positive twin of `composePreview.idExcludeFile`), used only when the comma-joined
   * property can't carry the selection ([forRequest]).
   */
  const val FILE_GRADLE_PROPERTY: String = "composePreview.idFilterFile"

  /**
   * Mirror of `PreviewNameFilter.ANCHOR`: makes a pattern exact, so `Button` doesn't also select
   * `Button_Dark` and its rows.
   */
  const val ANCHOR: String = "="

  /**
   * The narrowing decision for one render. [gradleArgs] are appended to the Gradle args.
   * [renderedIds] is the set of (permutation-expanded) ids this run re-renders, or null for all, so
   * skipped previews keep their `.cli-state.json` shas. [note] says why narrowing was declined
   * (`--verbose`).
   */
  data class Scope(
    val gradleArgs: List<String> = emptyList(),
    val renderedIds: Set<String>? = null,
    val note: String? = null,
  ) {
    val narrowed: Boolean
      get() = gradleArgs.isNotEmpty()
  }

  /** The unnarrowed scope — render everything, as the CLI did before #3730. */
  val FULL: Scope = Scope()

  /**
   * Resolve [exactId] / [filter] / [previewRef] / [ids] (`--id-file`) against the [manifests] about
   * to render; the selectors intersect ([previewIdMatchesRequest]).
   *
   * [permutations]: requests match the expanded ids users see (`Foo_dark`) but forward the
   * unexpanded id (`Foo`), since the render filters before expanding (`RenderPreviewsTask.render`).
   * [filesDir] is where the `$FILE_GRADLE_PROPERTY` fallback file goes (null = temp dir).
   *
   * Returns [FULL] when there is no request, nothing matched, or everything matched.
   */
  fun forRequest(
    manifests: List<Pair<PreviewModule, PreviewManifest>>,
    exactId: String?,
    filter: String?,
    previewRef: String? = null,
    permutations: List<String> = emptyList(),
    rowAware: Boolean = true,
    filesDir: java.io.File? = null,
    ids: Set<String>? = null,
  ): Scope {
    if (exactId == null && filter == null && previewRef == null && ids == null) return FULL
    if (manifests.isEmpty()) return FULL

    val selected = linkedSetOf<String>()
    val renderedIds = linkedSetOf<String>()
    var discovered = 0
    // A row selector (`Foo_PARAM_1`, or a label like `Crimson`) can't match a manifest entry, so
    // narrow to the parameterized previews it might name rather than render the whole module —
    // unless `--id` names a real preview.
    val exactIdExists = manifestsDeclareExactId(manifests, exactId)
    for ((_, manifest) in manifests) {
      for (preview in manifest.previews) {
        discovered++
        val expanded = PreviewPermutationsCli.expand(listOf(preview), permutations).map { it.id }
        val mayOwnRequestedRow =
          previewMatchesRequestIncludingRows(
            preview,
            exactId = exactId,
            filter = filter,
            previewRef = previewRef,
            exactIdExists = exactIdExists,
            rowAware = rowAware,
            ids = ids,
          )
        if (
          !mayOwnRequestedRow &&
            expanded.none {
              previewIdMatchesRequest(
                it,
                exactId = exactId,
                filter = filter,
                previewRef = previewRef,
                className = preview.className,
                functionName = preview.functionName,
                ids = ids,
              )
            }
        )
          continue
        selected += preview.id
        renderedIds += expanded
      }
    }

    if (selected.isEmpty()) return FULL
    if (selected.size == discovered) return FULL

    val patterns = selected.filter(String::isNotBlank).map { ANCHOR + it }
    if (patterns.isEmpty()) return FULL

    // The property is comma-split, so a comma in an id would break it; discovered ids shouldn't
    // contain one, so this is a guard.
    val joined = patterns.joinToString(",")
    val commaSafe = patterns.none { it.contains(',') }
    // Process arguments use `sun.jnu.encoding`; under a C/POSIX locale non-ASCII characters become
    // `?` and the ids never match.
    val argSafe = platformArgEncodable(joined)
    if (commaSafe && argSafe) {
      return Scope(gradleArgs = listOf("-P$GRADLE_PROPERTY=$joined"), renderedIds = renderedIds)
    }

    // A UTF-8 file behind an ASCII path fixes both the comma and the encoding problem.
    val file = writeIdFilterFile(patterns, filesDir)
    if (file == null || !platformArgEncodable(file.path)) {
      val why =
        if (!commaSafe) "contain a comma, which $GRADLE_PROPERTY cannot express"
        else "cannot be passed through this JVM's argument encoding ($PLATFORM_ARG_ENCODING)"
      return Scope(
        note =
          "could not narrow the Gradle render: ${patterns.size} matching preview id(s) $why, and " +
            "the $FILE_GRADLE_PROPERTY fallback was unusable (e.g. '${patterns.first()}')"
      )
    }
    return Scope(
      gradleArgs = listOf("-P$FILE_GRADLE_PROPERTY=${file.path}"),
      renderedIds = renderedIds,
    )
  }

  /**
   * The charset the JVM encodes process arguments and environment with (`sun.jnu.encoding`), from
   * the process locale and independent of `file.encoding`.
   */
  internal val PLATFORM_ARG_ENCODING: String
    get() = System.getProperty("sun.jnu.encoding") ?: System.getProperty("file.encoding") ?: "UTF-8"

  /** Whether [value] survives the trip through [PLATFORM_ARG_ENCODING] unchanged. */
  internal fun platformArgEncodable(value: String): Boolean =
    runCatching { java.nio.charset.Charset.forName(PLATFORM_ARG_ENCODING) }
      .getOrNull()
      ?.newEncoder()
      ?.canEncode(value) ?: true

  /**
   * Write [patterns] as a newline-delimited UTF-8 file for `-P$FILE_GRADLE_PROPERTY`, or null when
   * it can't be created (the caller then renders wide). In the temp dir, since the project path may
   * be unencodable too; deleted on exit.
   */
  private fun writeIdFilterFile(patterns: List<String>, dir: java.io.File?): java.io.File? =
    runCatching {
      val file =
        java.io.File.createTempFile("compose-preview-id-filter-", ".txt", dir).apply {
          deleteOnExit()
        }
      file.writeText(patterns.joinToString("\n"), Charsets.UTF_8)
      file
    }
    .getOrNull()
}
