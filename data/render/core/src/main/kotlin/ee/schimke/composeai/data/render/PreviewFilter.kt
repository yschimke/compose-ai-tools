package ee.schimke.composeai.data.render

/**
 * Renderer-side `@Preview` selector shared by every Robolectric render entry — the image render
 * ([ee.schimke.composeai.renderer.PreviewManifestLoader]), the XR subspace render, and the XML
 * resource render — so `--preview` / `--preview-id` / `--exclude-preview-id` (and their
 * `composePreview.filter` / `.idFilter` / `.idExclude` property conventions) narrow an Android
 * render the same way they already narrow the desktop one (issues #2066, #2966, #2977).
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

  /** System property carrying the comma-separated `--preview` name patterns. */
  const val NAME_FILTER_PROPERTY: String = "composeai.preview.filter"

  /** System property carrying the comma-separated `--preview-id` id patterns. */
  const val ID_FILTER_PROPERTY: String = "composeai.preview.idFilter"

  /** System property carrying the comma-separated `--exclude-preview-id` id patterns. */
  const val ID_EXCLUDE_PROPERTY: String = "composeai.preview.idExclude"

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
   * Type-agnostic via the accessor lambdas so the image-render [ee.schimke.composeai.renderer]
   * entry, the XR entry, and the resource entry can each pass their own row type.
   */
  fun <T> select(
    items: List<T>,
    nameFilters: List<String>,
    idFilters: List<String>,
    idExcludes: List<String>,
    functionName: (T) -> String,
    className: (T) -> String,
    id: (T) -> String,
  ): List<T> {
    val nameFiltered = selectByName(items, nameFilters, functionName, className)
    val idFiltered = selectById(nameFiltered, idFilters, id)
    return excludeById(idFiltered, idExcludes, id)
  }

  /** Narrows [items] to those whose function/FQN matches [patterns]; fails fast on no match. */
  fun <T> selectByName(
    items: List<T>,
    patterns: List<String>,
    functionName: (T) -> String,
    className: (T) -> String,
  ): List<T> {
    val cleaned = patterns.map(String::trim).filter(String::isNotEmpty)
    if (cleaned.isEmpty()) return items
    val matched = items.filter { matches(cleaned, functionName(it), className(it)) }
    if (matched.isNotEmpty()) return matched
    throw noMatch(
      flag = "--preview",
      patterns = cleaned,
      available = items.map { fqName(className(it), functionName(it)) }.distinct().sorted(),
      what = "previews",
    )
  }

  /** Narrows [items] to those whose id matches [patterns]; fails fast on no match. */
  fun <T> selectById(items: List<T>, patterns: List<String>, id: (T) -> String): List<T> {
    val cleaned = patterns.map(String::trim).filter(String::isNotEmpty)
    if (cleaned.isEmpty()) return items
    val matched = items.filter { matchesId(cleaned, id(it)) }
    if (matched.isNotEmpty()) return matched
    throw noMatch(
      flag = "--preview-id",
      patterns = cleaned,
      available = items.map { id(it) }.distinct().sorted(),
      what = "preview ids",
    )
  }

  /** Drops [items] whose id matches [excludes], keeping the rest; fails fast if it drops all. */
  fun <T> excludeById(items: List<T>, excludes: List<String>, id: (T) -> String): List<T> {
    val cleaned = excludes.map(String::trim).filter(String::isNotEmpty)
    if (cleaned.isEmpty() || items.isEmpty()) return items
    val kept = items.filterNot { matchesId(cleaned, id(it)) }
    if (kept.isNotEmpty()) return kept
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
    if (pattern.any { it == '*' || it == '?' }) {
      val regex = globToRegex(pattern)
      regex.matches(simpleName) || regex.matches(fqName)
    } else {
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
}
