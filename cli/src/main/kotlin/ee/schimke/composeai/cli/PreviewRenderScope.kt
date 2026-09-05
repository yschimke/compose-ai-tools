package ee.schimke.composeai.cli

import ee.schimke.composeai.previewdata.PreviewManifest
import ee.schimke.composeai.previewdata.PreviewModule

/**
 * Turns a `--id` / `--filter` / `--preview` request into the Gradle property that actually narrows
 * the render (issue #3730; `--preview` joined the selectors in #3744).
 *
 * Before this, `--id` / `--filter` were applied **client-side**: the CLI drove
 * `:module:composePreviewRenderAll` at full width and then dropped the non-matching rows from its
 * own output. Asking for one preview therefore rendered every preview in the module — measured at
 * 317s instead of 3s on `samples/cmp` (64 previews), which also overran the CLI's own timeout and
 * made single-preview renders *fail*. The plugin has honoured `composePreview.idFilter` on both
 * backends since #2977; the CLI simply wasn't passing it.
 *
 * **Why an explicit id list rather than forwarding the pattern.** `--filter` is a
 * *case-insensitive* substring match on the id ([previewIdMatchesRequest]);
 * `composePreview.idFilter` is case-**sensitive** (`PreviewNameFilter.matchesId`). Forwarding the
 * raw pattern would silently render a *narrower* set than the CLI then expects to print, so a
 * `--filter homescreen` that used to match `HomeScreenPreview` would come back with no PNG. Instead
 * the CLI resolves the request against the discovery manifest it has already read and forwards the
 * exact ids, each anchored with [ANCHOR] so a base id can't drag in its own `_VARIANT_` / row
 * fan-out siblings. The two matchers never have to agree, because only one of them ever runs.
 *
 * **Why only when it genuinely narrows.** A filtered `composePreviewRender` is not build-cacheable
 * (`RenderPreviewsTask`'s `outputs.cacheIf` — a filtered run leaves every other module PNG at
 * whatever the last run wrote, so its output dir isn't the module's complete render set).
 * Forwarding a filter that selects *everything* would therefore buy nothing and cost the build
 * cache, so [forRequest] returns no arguments in that case.
 */
internal object PreviewRenderScope {

  /** The Gradle property `composePreviewRender` reads id patterns from, on both backends. */
  const val GRADLE_PROPERTY: String = "composePreview.idFilter"

  /**
   * The delimiter- and encoding-safe form of [GRADLE_PROPERTY]: a **path** to a newline-delimited
   * UTF-8 list of the same patterns (issue #5172), the positive twin of
   * `composePreview.idExcludeFile`. Used only when the comma-joined property cannot carry the
   * selection — see [forRequest].
   */
  const val FILE_GRADLE_PROPERTY: String = "composePreview.idFilterFile"

  /**
   * Mirror of `PreviewNameFilter.ANCHOR` — the prefix that makes a pattern an exact match rather
   * than a substring one. Load-bearing here: ids are hierarchical, so an unanchored `Button` would
   * also select `Button_Dark` and every `@PreviewParameter` row under it, quietly re-widening the
   * render this class exists to narrow.
   */
  const val ANCHOR: String = "="

  /**
   * The narrowing decision for one render invocation.
   *
   * [gradleArgs] are appended to the render's Gradle argument list. [renderedIds] is the set of
   * preview ids this run will actually (re-)render, **after** permutation expansion, or `null` when
   * the run renders everything — the CLI uses it to leave the `.cli-state.json` entries of
   * deliberately-skipped previews alone instead of forgetting their sha. [note] is a human-readable
   * reason the narrowing was declined, for `--verbose`; `null` when there is nothing to say.
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
   * Resolve [exactId] / [filter] / [previewRef] against the discovery [manifests] of the modules
   * about to render. The three selectors intersect — see [previewIdMatchesRequest].
   *
   * [permutations] is the active `--permutations` list: the CLI matches a request against the
   * *expanded* ids a user sees in `show` output (`Foo_dark`), but forwards the *unexpanded* id
   * (`Foo`) because the render applies its id filter before expanding — see
   * `RenderPreviewsTask.render`.
   *
   * [filesDir] is where the `$FILE_GRADLE_PROPERTY` fallback file is written when the comma-joined
   * property can't carry the selection; `null` means the JVM temp dir. Tests pass their own.
   *
   * Returns [FULL] when there is no request, when nothing matched (the caller renders no module at
   * all in that case), or when the request selects every discovered preview.
   */
  fun forRequest(
    manifests: List<Pair<PreviewModule, PreviewManifest>>,
    exactId: String?,
    filter: String?,
    previewRef: String? = null,
    permutations: List<String> = emptyList(),
    rowAware: Boolean = true,
    filesDir: java.io.File? = null,
  ): Scope {
    if (exactId == null && filter == null && previewRef == null) return FULL
    if (manifests.isEmpty()) return FULL

    val selected = linkedSetOf<String>()
    val renderedIds = linkedSetOf<String>()
    var discovered = 0
    // Issue #3786 — a selector naming a `@PreviewParameter` row (`Foo_PARAM_1`, or a bare label
    // like `Crimson`) picks out an id that only exists once the fan-out is on disk, so it can never
    // match a manifest entry here. Selecting the parameterized previews it *might* name keeps the
    // #3730 narrowing working for row requests instead of falling through to
    // `selected.isEmpty() -> FULL` and rendering the whole module. Gated on `--id` not naming a
    // preview that really exists, so an exact request still narrows to exactly that one.
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

    // `previewIdFilterProperty` splits the property on `,`, so an id carrying a comma would be torn
    // into two patterns that each match nothing — and a filter matching nothing *fails* the render.
    // No discovered id should contain one (ids derive from Kotlin identifiers and are
    // path-sanitised), so this is a guard rather than a code path.
    val joined = patterns.joinToString(",")
    val commaSafe = patterns.none { it.contains(',') }
    // Issue #5172 — process arguments are encoded with the JVM's `sun.jnu.encoding`, so on a
    // C/POSIX-locale JVM (`ANSI_X3.4-1968`: containers, CI runners, cloud agent sandboxes) every
    // non-ASCII character in this argument is replaced by `?` before Gradle ever sees it. A preview
    // named `Cadence — Sync ready` then resolves to an id the render worker can never match, so the
    // narrowed path failed for exactly the previews it was meant to speed up.
    val argSafe = platformArgEncodable(joined)
    if (commaSafe && argSafe) {
      return Scope(gradleArgs = listOf("-P$GRADLE_PROPERTY=$joined"), renderedIds = renderedIds)
    }

    // Both problems are transport problems, and a UTF-8 file behind an ASCII path solves both: the
    // path is what crosses the argument boundary, and newlines delimit ids a comma cannot. Mirrors
    // `composePreview.idExcludeFile`, which exists for the comma half of the same story.
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
   * The charset the JVM encodes process arguments (and environment) with — `sun.jnu.encoding`.
   * Unrelated to `file.encoding`: it comes from the process locale, so a container with no locale
   * configured runs at `ANSI_X3.4-1968` (US-ASCII) even on a JDK whose default charset is UTF-8.
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
   * Write [patterns] as a newline-delimited UTF-8 file for `-P$FILE_GRADLE_PROPERTY`, or `null`
   * when the file can't be created (a read-only temp dir — the caller then renders wide rather than
   * failing the run).
   *
   * Written under the JVM temp dir rather than the project's `build/`, because the project path is
   * exactly as likely to be un-encodable as the ids are, and the path is what has to reach Gradle
   * intact. Deleted on exit: the Gradle invocation reads it during configuration of the same run.
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
