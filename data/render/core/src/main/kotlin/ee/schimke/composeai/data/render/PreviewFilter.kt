package ee.schimke.composeai.data.render

/**
 * Renderer-side `@Preview` selector shared by the Robolectric render entries that render actual
 * `@Preview` composables — the image render ([ee.schimke.composeai.renderer.PreviewManifestLoader])
 * and the XR subspace render — so `--preview` / `--preview-id` / `--exclude-preview-id` (and their
 * `composePreview.filter` / `.idFilter` / `.idExclude` property conventions) narrow an Android
 * render the same way they already narrow the desktop one (issues #2066, #2966, #2977). The XML
 * resource render is a separate manifest of assets with no `@Preview` function and is deliberately
 * left unfiltered — see `ResourcePreviewRenderTest`.
 *
 * The matching rules ([matches] / [matchesId] / [globToRegex]) are a **deliberate mirror** of the
 * plugin's `ee.schimke.composeai.discovery.PreviewNameFilter`, which drives the desktop
 * `RenderPreviewsTask`. The two can't share one class across the plugin↔renderer classpath boundary
 * (the same split that makes `RenderManifest` a mirror of the plugin's `PreviewManifest`), so the
 * logic is duplicated and must be kept in sync — the two implementations are line-for-line the same
 * matcher and are each covered by their own tests (`PreviewFilterTest` here,
 * `PreviewNameFilterTest` in `:preview-discovery`). Change one, change the other.
 *
 * A preview matches a pattern against two candidate names: its **simple** function name and its
 * **package-qualified** name (`<package>.<functionName>`, derived from the owning class's package).
 * A pattern containing `*`/`?` is anchored-glob-matched; a plain pattern matches on equality or
 * substring. Matching is case-sensitive, any pattern keeps the preview (OR across the list), and an
 * empty pattern list matches everything ("render every preview").
 */
object PreviewFilter {

  /**
   * Prefix marking a pattern as an **exact** match rather than a substring one — the renderer-side
   * mirror of `PreviewNameFilter.ANCHOR`, which documents why a generated id list needs it (a base
   * id is always a substring of its own `_VARIANT_` / row fan-out, so substring exclusion deletes
   * work a sharder meant to keep).
   */
  const val ANCHOR: String = "="

  /** System property carrying the comma-separated `--preview` name patterns. */
  const val NAME_FILTER_PROPERTY: String = "composeai.preview.filter"

  /** System property carrying the comma-separated `--preview-id` id patterns. */
  const val ID_FILTER_PROPERTY: String = "composeai.preview.idFilter"

  /** System property carrying the comma-separated `--exclude-preview-id` id patterns. */
  const val ID_EXCLUDE_PROPERTY: String = "composeai.preview.idExclude"

  /** System property carrying the comma-separated `--exclude-preview-row` label patterns. */
  const val ROW_EXCLUDE_PROPERTY: String = "composeai.preview.rowExclude"

  /**
   * Reads one of the comma-separated pattern system properties into a cleaned list: split on `,`,
   * trimmed, blanks dropped. Absent / blank → an empty list ("no filter"). The same comma-separated
   * shape the plugin's property resolvers produce, so a single `-PcomposePreview.filter=A,B` or
   * `ORG_GRADLE_PROJECT_composePreview.filter=A,B` reaches both backends identically.
   */
  fun patternsFrom(
    property: String,
    read: (String) -> String? = System::getProperty,
  ): List<String> =
    read(property)?.split(",")?.map(String::trim)?.filter(String::isNotEmpty) ?: emptyList()

  /**
   * True when [functionName] (owned by [className]) matches at least one of [patterns], or when
   * [patterns] is empty.
   */
  fun matches(patterns: Collection<String>, functionName: String, className: String): Boolean {
    val cleaned = patterns.map(String::trim).filter(String::isNotEmpty)
    if (cleaned.isEmpty()) return true
    val fqName = fqName(className, functionName)
    return cleaned.any { matchOne(it, functionName, fqName) }
  }

  /** True when a preview **id** matches at least one of [patterns], or when [patterns] is empty. */
  fun matchesId(patterns: Collection<String>, id: String): Boolean {
    val cleaned = patterns.map(String::trim).filter(String::isNotEmpty)
    if (cleaned.isEmpty()) return true
    return cleaned.any { matchOne(it, id, id) }
  }

  /**
   * The package-qualified name a user reads off the source: `<package>.<functionName>`. [className]
   * is the owning class FQN (a synthetic `…Kt` holder for top-level functions), so only its package
   * segment is meaningful. Falls back to the bare function name in the default package.
   */
  fun fqName(className: String, functionName: String): String {
    val pkg = className.substringBeforeLast('.', "")
    return if (pkg.isEmpty()) functionName else "$pkg.$functionName"
  }

  /**
   * Applies the three filters to [items] with the same compose-and-fail-fast policy the desktop
   * path uses (see `RenderPreviewsTask.selectNamedPreviews` / `selectPreviewIds` /
   * `excludePreviewIds`):
   * 1. the **name** filter runs first (function/FQN),
   * 2. then the **id** filter over what the name filter kept,
   * 3. then the **id exclusions** drop members from that.
   *
   * A positive filter (name or id) that matches nothing, or an exclusion that removes everything,
   * throws [IllegalStateException] listing the available names/ids — a filtered render that would
   * produce zero output is a typo or stale spec, not a silent no-op (the failure the desktop path
   * guards against too). Empty filter lists pass [items] through unchanged.
   *
   * [failOnNoMatch] gates that throw. `true` (the default) is for the authoritative render that
   * sees every discovered preview — the Android image render, whose manifest carries all kinds
   * (COMPOSE / XR / LOTTIE / SVG / catalog), so a global no-match really is a typo. `false` is for
   * a **kind-restricted sibling** view — the XR-only render — where a filter naming a preview of a
   * different kind legitimately matches nothing *here* while the image render matches it; failing
   * would sink `composePreviewRenderAll`. There, a no-match yields an empty list and the sibling
   * simply renders nothing.
   *
   * Type-agnostic via the accessor lambdas so the image-render [ee.schimke.composeai.renderer]
   * entry and the XR entry can each pass their own row type.
   */
  fun <T> select(
    items: List<T>,
    nameFilters: List<String>,
    idFilters: List<String>,
    idExcludes: List<String>,
    functionName: (T) -> String,
    className: (T) -> String,
    id: (T) -> String,
    failOnNoMatch: Boolean = true,
  ): List<T> {
    val nameFiltered = selectByName(items, nameFilters, functionName, className, failOnNoMatch)
    val idFiltered = selectById(nameFiltered, idFilters, id, failOnNoMatch)
    return excludeById(idFiltered, idExcludes, id, failOnNoMatch)
  }

  /**
   * Narrows [items] to those whose function/FQN matches [patterns]. On no match, throws when
   * [failOnNoMatch] (the default), else returns an empty list. See [select] for why a sibling view
   * passes `false`.
   */
  fun <T> selectByName(
    items: List<T>,
    patterns: List<String>,
    functionName: (T) -> String,
    className: (T) -> String,
    failOnNoMatch: Boolean = true,
  ): List<T> {
    val cleaned = patterns.map(String::trim).filter(String::isNotEmpty)
    if (cleaned.isEmpty()) return items
    val matched = items.filter { matches(cleaned, functionName(it), className(it)) }
    if (matched.isNotEmpty() || !failOnNoMatch) return matched
    throw noMatch(
      flag = "--preview",
      patterns = cleaned,
      available = items.map { fqName(className(it), functionName(it)) }.distinct().sorted(),
      what = "previews",
    )
  }

  /**
   * Narrows [items] to those whose id matches [patterns]. No-match behaviour: see [selectByName].
   */
  fun <T> selectById(
    items: List<T>,
    patterns: List<String>,
    id: (T) -> String,
    failOnNoMatch: Boolean = true,
  ): List<T> {
    val cleaned = patterns.map(String::trim).filter(String::isNotEmpty)
    if (cleaned.isEmpty()) return items
    val matched = items.filter { matchesId(cleaned, id(it)) }
    if (matched.isNotEmpty() || !failOnNoMatch) return matched
    throw noMatch(
      flag = "--preview-id",
      patterns = cleaned,
      available = items.map { id(it) }.distinct().sorted(),
      what = "preview ids",
    )
  }

  /**
   * Drops [items] whose id matches [excludes], keeping the rest. When an exclusion removes
   * everything, throws if [failOnNoMatch] (the default) else returns the empty list — a
   * kind-restricted sibling may legitimately exclude its whole subset.
   */
  fun <T> excludeById(
    items: List<T>,
    excludes: List<String>,
    id: (T) -> String,
    failOnNoMatch: Boolean = true,
  ): List<T> {
    val cleaned = excludes.map(String::trim).filter(String::isNotEmpty)
    if (cleaned.isEmpty() || items.isEmpty()) return items
    val kept = items.filterNot { matchesId(cleaned, id(it)) }
    if (kept.isNotEmpty() || !failOnNoMatch) return kept
    throw IllegalStateException(
      "composePreviewRender --exclude-preview-id excluded every one of the ${items.size} " +
        "preview(s) for ${cleaned.joinToString(", ") { "'$it'" }} — nothing would render."
    )
  }

  private const val MAX_SUGGESTED = 20

  private fun noMatch(
    flag: String,
    patterns: List<String>,
    available: List<String>,
    what: String,
  ): IllegalStateException =
    IllegalStateException(
      buildString {
        append("composePreviewRender $flag matched no previews for ")
        append(patterns.joinToString(", ") { "'$it'" })
        append(".")
        if (available.isEmpty()) {
          append(" This module has no discovered previews on this backend.")
        } else {
          append(" Available $what:")
          available.take(MAX_SUGGESTED).forEach { append("\n  ").append(it) }
          val more = available.size - MAX_SUGGESTED
          if (more > 0) append("\n  … and ").also { append(more) }.also { append(" more.") }
        }
      }
    )

  private fun matchOne(pattern: String, simpleName: String, fqName: String): Boolean =
    when {
      pattern.startsWith(ANCHOR) -> {
        val exact = pattern.substring(ANCHOR.length)
        simpleName == exact || fqName == exact
      }
      pattern.any { it == '*' || it == '?' } -> {
        val regex = globToRegex(pattern)
        regex.matches(simpleName) || regex.matches(fqName)
      }
      else ->
        simpleName == pattern ||
          fqName == pattern ||
          simpleName.contains(pattern) ||
          fqName.contains(pattern)
    }

  private fun globToRegex(glob: String): Regex {
    val out = StringBuilder()
    val literal = StringBuilder()
    fun flushLiteral() {
      if (literal.isNotEmpty()) {
        out.append(Regex.escape(literal.toString()))
        literal.clear()
      }
    }
    for (c in glob) {
      when (c) {
        '*' -> {
          flushLiteral()
          out.append(".*")
        }
        '?' -> {
          flushLiteral()
          out.append(".")
        }
        else -> literal.append(c)
      }
    }
    flushLiteral()
    return Regex(out.toString())
  }

  /**
   * The indices of [suffixes] whose `@PreviewParameter` row should render, in order.
   *
   * The row axis is separate from the id filters above because the ids they match are the
   * **discovered** ones: a parameterized function is one entry in `previews.json` (discovery reads
   * bytecode and can't instantiate a provider), and its rows only exist once the render JVM has
   * enumerated the values and labelled them. So a design system whose palettes come from a provider
   * — the shape behind #2966's measurement — can only be thinned here, by label.
   *
   * [suffixes] are the per-row suffixes the renderer computed (`"_Dark"`, or `"_PARAM_3"` for a
   * value that couldn't be labelled); the leading `_` is stripped before matching, so a caller
   * writes `--exclude-preview-row Dark`, matching the filename they see. Matching is
   * **case-insensitive** — deliberately unlike [matchesId], since a label comes from user data
   * (`"Dark"`) while the pattern is usually a design spec's own spelling (`"dark"`), and widening
   * an exclusion is safe where widening a positive filter would not be.
   *
   * Two rules keep it from ever costing coverage silently, mirroring the desktop renderer's
   * `PreviewRowFilter`:
   * - a preview with no fan-out (a single empty suffix) is never filtered — it has no rows, so a
   *   row pattern must not be able to delete its only render;
   * - if every row matches, none is skipped: a preview that rendered nothing would publish as a
   *   component with no pixels, which is a misconfigured exclusion rather than a deferral.
   */
  fun keptRowIndices(suffixes: List<String>, patterns: List<String>): List<Int> {
    val all = suffixes.indices.toList()
    val cleaned = patterns.map(String::trim).filter(String::isNotEmpty)
    if (cleaned.isEmpty()) return all
    if (suffixes.size == 1 && suffixes[0].isEmpty()) return all
    val kept = all.filterNot { matchesRowLabel(cleaned, suffixes[it].removePrefix("_")) }
    return if (kept.isEmpty()) all else kept
  }

  /**
   * True when a row [label] matches one of [patterns] — glob when it has `*`/`?`, else equality.
   */
  fun matchesRowLabel(patterns: Collection<String>, label: String): Boolean =
    patterns.map(String::trim).filter(String::isNotEmpty).any { pattern ->
      if (pattern.any { it == '*' || it == '?' })
        Regex(globToRegex(pattern).pattern, RegexOption.IGNORE_CASE).matches(label)
      else label.equals(pattern, ignoreCase = true)
    }
}
