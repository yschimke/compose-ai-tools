package ee.schimke.composeai.cli

/**
 * Turns a `--id` / `--filter` request into the Gradle property that actually narrows the render
 * (issue #3730).
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
   * Resolve [exactId] / [filter] against the discovery [manifests] of the modules about to render.
   *
   * [permutations] is the active `--permutations` list: the CLI matches a request against the
   * *expanded* ids a user sees in `show` output (`Foo_dark`), but forwards the *unexpanded* id
   * (`Foo`) because the render applies its id filter before expanding — see
   * `RenderPreviewsTask.render`.
   *
   * Returns [FULL] when there is no request, when nothing matched (the caller renders no module at
   * all in that case), or when the request selects every discovered preview.
   */
  fun forRequest(
    manifests: List<Pair<PreviewModule, PreviewManifest>>,
    exactId: String?,
    filter: String?,
    permutations: List<String> = emptyList(),
  ): Scope {
    if (exactId == null && filter == null) return FULL
    if (manifests.isEmpty()) return FULL

    val selected = linkedSetOf<String>()
    val renderedIds = linkedSetOf<String>()
    var discovered = 0
    for ((_, manifest) in manifests) {
      for (preview in manifest.previews) {
        discovered++
        val expanded =
          matchedExpansion(preview, exactId = exactId, filter = filter, permutations = permutations)
            ?: continue
        selected += preview.id
        renderedIds += expanded
      }
    }

    if (selected.isEmpty()) return FULL
    if (selected.size == discovered) return FULL

    // `previewIdFilterProperty` splits the property on `,`, so an id carrying a comma would be torn
    // into two patterns that each match nothing — and a filter matching nothing *fails* the render.
    // No discovered id should contain one (ids derive from Kotlin identifiers and are
    // path-sanitised), so this is a guard rather than a code path: decline the narrowing and render
    // wide instead of breaking the run.
    val unsafe = selected.filter { it.contains(',') || it.isBlank() }
    if (unsafe.isNotEmpty()) {
      return Scope(
        note =
          "could not narrow the Gradle render: ${unsafe.size} matching preview id(s) contain a " +
            "comma, which $GRADLE_PROPERTY cannot express (e.g. '${unsafe.first()}')"
      )
    }

    val patterns = selected.joinToString(",") { ANCHOR + it }
    return Scope(gradleArgs = listOf("-P$GRADLE_PROPERTY=$patterns"), renderedIds = renderedIds)
  }

  /**
   * The *unexpanded* ids in [manifest] that an `--id` / `--filter` request selects — "which
   * previews did the user ask about", answered against a single module's discovery manifest.
   *
   * This is the question [Scope.renderedIds] cannot answer for a non-render consumer (issue #3742).
   * `renderedIds` is `null` whenever the render wasn't narrowed, which includes a request that
   * happened to select *every* preview, so a consumer that reads `null` as "no filter" and one that
   * reads it as "everything" are both right about the render and wrong about the request. Anything
   * that fans out per preview off the back of a request — the daemon a11y pass in `A11yCommand`,
   * and any future [ee.schimke.composeai.cli.ReportCommand] data-product hook — narrows through
   * here instead.
   *
   * Returns every id when there is no request. Ids come back in manifest order, unexpanded, because
   * that is the form the daemon addresses previews in; matching still happens against the
   * *expanded* ids for the same reason [forRequest] does it (a user's `--id Foo_dark` names a
   * permutation, not a declared preview).
   */
  fun selectedPreviewIds(
    manifest: PreviewManifest,
    exactId: String?,
    filter: String?,
    permutations: List<String> = emptyList(),
  ): List<String> {
    if (exactId == null && filter == null) return manifest.previews.map { it.id }
    return manifest.previews
      .filter {
        matchedExpansion(it, exactId = exactId, filter = filter, permutations = permutations) !=
          null
      }
      .map { it.id }
  }

  /**
   * [preview] expanded under [permutations], or `null` when none of those expansions match the
   * request. The single place the "does this declared preview answer the request" decision is made,
   * so [forRequest] (which narrows the render) and [selectedPreviewIds] (which narrows everything
   * else) cannot drift apart.
   */
  private fun matchedExpansion(
    preview: PreviewInfo,
    exactId: String?,
    filter: String?,
    permutations: List<String>,
  ): List<String>? {
    val expanded = PreviewPermutationsCli.expand(listOf(preview), permutations).map { it.id }
    return expanded.takeIf { ids ->
      ids.any { previewIdMatchesRequest(it, exactId = exactId, filter = filter) }
    }
  }
}
