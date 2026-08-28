package ee.schimke.composeai.cli.serve

import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Builds the prefilled GitHub **new-issue** link the viewer offers beside its "source" link, so
 * someone looking at a preview that renders wrongly can file it against the repo that owns the code
 * — carrying the facts a triager would otherwise have to ask for (which system, which preview,
 * which catalog build, the deep link, and the PNG at the settings on screen).
 *
 * **Why a prefilled link rather than the server filing the issue itself.** [ServeGithubAuth] keeps
 * only a signed cookie holding the visitor's login and their repo-access verdict — the OAuth token
 * is deliberately discarded after the check. Filing server-side "as the visitor" would mean asking
 * for issue-write scope and holding user tokens on a public box, a real escalation for no gain:
 * their browser is already signed in to GitHub, so handing it a prefilled `issues/new` URL files
 * the issue under their own identity with nothing to custody. Sign-in still shows up here — when
 * the server knows the visitor's login it names it in the affordance's tooltip — but the flow works
 * signed out too, because GitHub prompts for login on the issue form anyway.
 *
 * **The screenshot is pasted, not linked.** The body links the `/render` PNG at the current
 * settings (handy, but it re-renders against whatever the catalog is when someone reads the issue),
 * and asks for a paste — the viewer's "Copy PNG" puts real `image/png` bytes on the clipboard, so
 * one keystroke in the issue box uploads the exact pixels to GitHub's own CDN, where they stay put.
 */
internal object ServeIssueReport {

  /**
   * Stand-in for the render URL inside [body], so the viewer JS can keep the report in sync with
   * the on-screen overrides without re-assembling the whole body client-side: the form's hidden
   * `body` input carries the text with this placeholder in it and swaps in the live `/render` URL
   * on each refresh.
   */
  const val RENDER_PLACEHOLDER: String = "{{render}}"

  /** Filled by the focused comparison once its browser-side scorer has completed. */
  const val RAW_SCORES_PLACEHOLDER: String = "{{rawScores}}"

  /**
   * Stand-in for the selection's `element:` / `bounds:` lines inside the locator block.
   *
   * Occupies a **whole line** and is substituted with its newline, so a body with nothing selected
   * reproduces byte for byte the block this writer emits on its own. The selection is the one part
   * of a locator the server cannot know: it is made by clicking, after the page is served. Only the
   * template form carries it — see [body]'s `renderPlaceholder`, which the same reasoning produced.
   */
  const val SELECTION_PLACEHOLDER: String = "{{selection}}"

  const val LOCATOR_FENCE: String = "compose-parity-locator/v1"

  /** The only plane `v1` accepts for [Bounds]; see that type and D1. */
  const val RENDER_PIXELS: String = "render-pixels"

  /** Repo bugs fall back to when a session names no source of its own — the renderer is ours. */
  const val FALLBACK_REPO: String = "yschimke/compose-ai-tools"

  /**
   * The facts a report carries. Everything but [repo] is optional: a plain local session knows
   * neither its catalog nor a source file, and simply drops those rows rather than filing a
   * half-empty template.
   */
  data class Context(
    /** `owner/name` the issue is filed against — see [repoFor]. */
    val repo: String,
    /**
     * The preview's flattened id (`Button__filled__dark`), the one unambiguous handle.
     *
     * Null on a **page-scoped** report — the comparison wall shows every component in the catalog
     * and singles out none, so a report filed from it names the page and the lane rather than
     * inventing a preview the visitor never picked. The body then drops its `| Preview |` row and
     * [locator] returns null, exactly as it already does for the optional rows below.
     */
    val previewId: String? = null,
    /** Human label, when the manifest recorded one; the title falls back to [previewId]. */
    val previewLabel: String? = null,
    /** The served design system (`wear-m3`), when this session is a catalog. */
    val system: String? = null,
    /** Stable catalog component identity. */
    val componentId: String? = null,
    /** Design reference compared with this exact preview. */
    val referenceId: String? = null,
    /** Preview-id axes only; live controls belong exclusively to [overrides]. */
    val variant: String = "",
    /**
     * The selected element, when the reporter picked one — see [Locator.element].
     *
     * Server-side this is normally null even on the focused comparison: a selection is made by
     * clicking, after the page has been served, so the page's JS writes it into the body template's
     * [SELECTION_PLACEHOLDER] instead. The field is here for a caller that already knows — and
     * because [locator] has to be able to state a complete record either way.
     */
    val element: String? = null,
    /** The selected region, when the reporter dragged one. See [Bounds] and [element]. */
    val bounds: Bounds? = null,
    /** The complete, normalised query map consumed by the render lane. */
    val overrides: Map<String, String> = emptyMap(),
    /** GitHub blob URL of the preview's source file (from [ServeUrls.githubBlobUrl]). */
    val sourceUrl: String? = null,
    /**
     * Delivery provenance as `owner/repo@branch` — which catalog build the visitor was looking at.
     */
    val catalog: String? = null,
    /** compose-ai-tools version that rendered the catalog, from its `catalog.json`. */
    val toolVersion: String? = null,
    /** Absolute viewer URL for this preview. Token-bearing URLs are stripped by [withoutToken]. */
    val viewerUrl: String? = null,
    /** Absolute focused comparison URL for this preview/reference pair. */
    val comparisonUrl: String? = null,
    /**
     * Absolute URL of the **page** the report was filed from, for a report that names no preview.
     * Carries the page's own query — which lane the comparison wall was showing, for instance —
     * because that is the whole of what a page-scoped report can point a triager at.
     */
    val pageUrl: String? = null,
    /** Absolute `/render/<id>.png` URL at the overrides in force when the page was served. */
    val renderUrl: String? = null,
    /**
     * Whether the render lane answers **without a session token** — i.e. the server is `--public`.
     *
     * [withoutToken] strips the token from every URL that reaches an issue body, because the token
     * is the capability to drive the server. On a token-gated box that makes [renderUrl] a URL the
     * lane itself 404s ([ServeHttpServer]'s render handler rejects a tokenless request), so an
     * embedded image would be broken in every filed issue no matter how reachable the host is.
     * Defaults to false so a caller that doesn't know keeps the link form.
     */
    val publicRender: Boolean = false,
    /** Browser-computed parity measurements; absent until the focused comparison finishes. */
    val rawScores: RawScores? = null,
  )

  data class RawScores(
    val structuralMatch: Double,
    val pixelsChanged: Double,
    val proportionDifference: Double? = null,
  )

  data class Locator(
    val repository: String,
    val system: String,
    val componentId: String,
    val previewId: String,
    val referenceId: String,
    val variant: String,
    val overrides: Map<String, String>,
    /**
     * The element a selection named, and the region it covered.
     *
     * Reserved ahead of a selector existing, because both parsers ignore unknown keys: adding the
     * selection to `v1` after the format froze would have produced reports that indexed cleanly
     * with the selection silently dropped — no strict-parser rejection to notice, no error
     * anywhere. The focused comparison fills them now (`<cp-element-selection>`), through
     * [SELECTION_PLACEHOLDER] rather than through this writer, since the choice is made after the
     * page is served.
     *
     * [element] is the `testTag` **verbatim** and is only a usable identity while exactly one node
     * carries it; [bounds] may be absent for a tag whose every carrying node had a zero-area box,
     * and is absent for every selection made before a region was dragged.
     */
    val element: String? = null,
    val bounds: Bounds? = null,
    val revision: String? = null,
  )

  /**
   * A selected region **and the plane it is measured in**, which is the half a bare rectangle
   * leaves out.
   *
   * Three spaces are in play and they disagree: a tag selection comes from the index in render
   * pixels, a drag selection is in display pixels, and an acceptance wants the canonical plane. D1
   * settles that both tag-index producers publish `render-pixels` and that the canonical-plane
   * transform is a step of the *comparison* — a plane is a property of a comparison, the index is a
   * property of a render — so `v1` carries only that space, and an element that never moved cannot
   * report as moved because two ends of the wire assumed different planes.
   */
  data class Bounds(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val space: String = RENDER_PIXELS,
  ) {
    /**
     * The invariants both parsers enforce, checked where the rectangle is *made* rather than where
     * it is serialised.
     *
     * A writer that emitted a rectangle its own producer refuses would hand the reporter a
     * prefilled body that looks right and takes the **whole issue** out of the index when the
     * workflow next runs — a failure with no symptom until someone wonders why the report never
     * appeared. The reachable version of that is batch 03's drag selection, which starts in display
     * pixels: a missed conversion is a mistake at the point of construction, and this is where it
     * should stop.
     */
    init {
      require(space == RENDER_PIXELS) { "bounds space must be $RENDER_PIXELS, was $space" }
      // The origin may be **negative**, deliberately: a uniquely tagged node can extend above or
      // left of the render root, and both tag-index producers emit signed coordinates for that case
      // — `ServeSemanticsTags` asks only for `right > left` / `bottom > top`, `tag-index.mjs`
      // parses
      // `-?\d+`, and `ServeTagIndex` validates only the extent. Requiring a non-negative origin
      // here would mean batch 03 could not copy the bounds the index handed it. Clipping is the
      // comparison's plane transform's business, not this constructor's.
      require(width >= 1 && height >= 1) {
        "bounds must have a positive extent, was ${width}x$height"
      }
    }
  }

  /**
   * Which repo a preview's bug belongs to: the catalog's **source** repo (the Kotlin the preview is
   * declared in) when known, else its **delivery** repo (the `design-artifacts/<system>` branch's
   * repo — better than nothing, and usually the same project), else [FALLBACK_REPO].
   *
   * Note that the source repo can be a fork (Android's samples are rendered from preview branches
   * in `yschimke/compose-samples`); that is deliberately where the report goes, because it is where
   * the preview code that misrendered actually lives.
   */
  fun repoFor(source: ServeWeb.CatalogSource?, provenance: ServeWeb.CatalogProvenance?): String =
    source?.repo?.trim()?.takeIf { it.isNotEmpty() }
      ?: provenance?.repo?.trim()?.takeIf { it.isNotEmpty() }
      ?: FALLBACK_REPO

  /**
   * Issue body, in markdown. [renderPlaceholder] swaps the render link for [RENDER_PLACEHOLDER] so
   * the viewer JS can substitute the live URL; the server-rendered `href` uses the real one, which
   * is what a visitor with JS off gets.
   */
  fun body(
    ctx: Context,
    renderPlaceholder: Boolean = false,
    selectionPlaceholder: Boolean = false,
  ): String {
    val rows = buildList {
      ctx.system?.trim()?.takeIf { it.isNotEmpty() }?.let { add("| Design system | `$it` |") }
      ctx.previewId?.trim()?.takeIf { it.isNotEmpty() }?.let { add("| Preview | `$it` |") }
      withoutToken(ctx.sourceUrl)?.takeIf { it.isNotBlank() }?.let { add("| Source | $it |") }
      ctx.catalog?.takeIf { it.isNotBlank() }?.let { add("| Catalog | `$it` |") }
      ctx.toolVersion
        ?.takeIf { it.isNotBlank() }
        ?.let { add("| Rendered by | compose-ai-tools $it |") }
      val scores =
        if (renderPlaceholder && !ctx.referenceId.isNullOrBlank()) RAW_SCORES_PLACEHOLDER
        else ctx.rawScores?.let(::formatRawScores)
      scores?.let { add("| Raw comparison | `$it` |") }
    }
    // The placeholder stands in for a render URL this body HAS; a report that names no render (a
    // page-scoped one) must not grow a `{{render}}` nothing will ever substitute — the wall runs no
    // script over its report body, so the placeholder would be filed verbatim.
    val hasRender = !withoutToken(ctx.renderUrl).isNullOrBlank()
    val render =
      if (renderPlaceholder) RENDER_PLACEHOLDER.takeIf { hasRender }
      else withoutToken(ctx.renderUrl)?.takeIf { it.isNotBlank() }
    // Whether the render can be *embedded* is decided by the real URL even when the body is the
    // JS template, so both forms of the body have the same shape and the placeholder swap can't
    // turn a working image into a broken one. Two independent conditions have to hold: GitHub's
    // proxy must be able to *reach* the URL, and the lane must *answer* it without the token this
    // body strips.
    val embed = render != null && ctx.publicRender && isEmbeddable(ctx.renderUrl)
    val links = buildList {
      withoutToken(ctx.pageUrl)?.takeIf { it.isNotBlank() }?.let { add("[Open this page]($it)") }
      withoutToken(ctx.viewerUrl)
        ?.takeIf { it.isNotBlank() }
        ?.let { add("[Open this preview]($it)") }
      withoutToken(ctx.comparisonUrl)
        ?.takeIf { it.isNotBlank() }
        ?.let { add("[Open comparison]($it)") }
      // Only worth its own line when the image isn't already showing it.
      if (!embed) render?.let { add("[PNG at these settings]($it)") }
    }
    return buildString {
      append("### What's wrong\n\n")
      append("<!-- What did you expect to see, and what did you get? -->\n\n\n")
      append("### Screenshot\n\n")
      if (embed) {
        append("![${altText(ctx)}]($render)\n\n")
        append(
          "<!-- That image is a LIVE render: it re-renders if the catalog changes, so it may " +
            "stop showing what you saw. GitHub displays it through Camo, but Camo proxies the " +
            "source URL; it does not make a versioned snapshot. For a copy that stays put, use " +
            "Copy PNG in the viewer's \"Export & direct links\" panel and paste it here — " +
            "GitHub then hosts the pixels itself. -->\n\n\n"
        )
      } else if (ctx.previewId.isNullOrBlank()) {
        // No preview means no "Export & direct links" panel to point at: what a page-scoped report
        // wants attached is the page, and the capture control in the report launcher is the tool
        // that grabs it.
        append(
          "<!-- Paste it here. The \"Report a problem\" launcher on the page has a capture " +
            "control that copies the whole view, a region, or one element to your clipboard, so " +
            "Ctrl-V / Cmd-V lands it in this issue. -->\n\n\n"
        )
      } else {
        append(
          "<!-- Paste it here. The viewer's \"Export & direct links\" panel has a Copy PNG " +
            "button that puts the image itself on your clipboard, so Ctrl-V / Cmd-V lands the " +
            "exact render in this issue. -->\n\n\n"
        )
      }
      append(if (ctx.previewId.isNullOrBlank()) "### Which page\n\n" else "### Which preview\n\n")
      append("| | |\n| --- | --- |\n")
      append(rows.joinToString("\n"))
      if (links.isNotEmpty()) append("\n\n").append(links.joinToString(" · "))
      append("\n")
      locator(ctx)?.let { append("\n").append(locatorBlock(it, selectionPlaceholder)) }
    }
  }

  private fun formatRawScores(scores: RawScores): String = buildString {
    append(
      "%.1f%% structural match; %.2f%% pixels changed"
        .format(Locale.ROOT, scores.structuralMatch, scores.pixelsChanged)
    )
    scores.proportionDifference?.let {
      append("; %.1f%% proportion difference".format(Locale.ROOT, it))
    }
  }

  fun locator(ctx: Context): Locator? {
    val preview = ctx.previewId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val system = ctx.system?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val component = ctx.componentId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val reference = ctx.referenceId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return Locator(
      repository = ctx.repo,
      system = system,
      componentId = component,
      previewId = preview,
      referenceId = reference,
      variant = ctx.variant,
      overrides = ctx.overrides,
      // Verbatim, deliberately: the tag is JSON-quoted on the wire, so edge whitespace survives
      // both parsers and the selected tag keeps its identity. A tag index treats `" glyph "` and
      // `"glyph"` as different keys, and normalising here would point the acceptance at the wrong
      // one — or at none. Only an *empty* tag is dropped, which both parsers refuse anyway.
      element = ctx.element?.takeIf { it.isNotEmpty() },
      bounds = ctx.bounds,
      revision = ctx.catalog?.trim()?.takeIf { it.isNotEmpty() },
    )
  }

  /** Catalog-authored component id, with the parity dashboard's stable route-id fallback. */
  fun componentIdFor(preview: ServePreview): String =
    preview.componentId?.takeIf { it.isNotBlank() }
      ?: run {
        val ideal = preview.id.indexOf("__ideal")
        if (ideal > 0) preview.id.substring(0, ideal)
        else {
          val parts = preview.id.split("__")
          val theme = parts.indices.lastOrNull { it >= 1 && parts[it] in setOf("light", "dark") }
          if (theme == null) preview.id
          else parts.filterIndexed { index, _ -> index != theme }.joinToString("__")
        }
      }

  /** Axis segments already encoded by the served preview id, never live overrides. */
  fun variantFor(preview: ServePreview): String =
    preview.id.substringAfter("__", missingDelimiterValue = "").replace("__", "/")

  /**
   * The block, as markdown. [selectionPlaceholder] swaps the selection's two lines for
   * [SELECTION_PLACEHOLDER] so the page's JS can write in what the reporter picked; the
   * server-rendered body uses the real (usually absent) values, which is what a visitor with JS off
   * files.
   */
  fun locatorBlock(locator: Locator, selectionPlaceholder: Boolean = false): String = buildString {
    append("```$LOCATOR_FENCE\n")
    append("repository: ${locator.repository}\n")
    append("system: ${locator.system}\n")
    append("component: ${locator.componentId}\n")
    append("preview: ${locator.previewId}\n")
    append("reference: ${locator.referenceId}\n")
    append("variant: ${locator.variant}\n")
    append("overrides: ${canonicalOverrides(locator.overrides)}\n")
    if (selectionPlaceholder) append("$SELECTION_PLACEHOLDER\n")
    else {
      locator.element?.let { append("element: ${canonicalElement(it)}\n") }
      locator.bounds?.let { append("bounds: ${canonicalBounds(it)}\n") }
    }
    locator.revision?.let { append("revision: $it\n") }
    append("```\n")
  }

  /**
   * Every locator a body carries, in order.
   *
   * One issue may name several components — an umbrella report like m3-catalog#42's Elevated shadow
   * level covers three — and one block can only say one of them, so the body carries one block each
   * and the index emits a row per block. Returns empty when the body has none; a body whose blocks
   * contradict each other (two repositories, two systems, or one component twice) is the producer's
   * to reject, since it is the side that turns them into rows.
   */
  fun locatorsFromBody(body: String): List<Locator> =
    body.split("```$LOCATOR_FENCE\n").drop(1).mapNotNull { rest ->
      val content = rest.substringBefore("\n```", missingDelimiterValue = "")
      content.takeIf { it.isNotEmpty() }?.let { locatorFromContent(it) }
    }

  fun locatorFromBody(body: String): Locator? {
    val fenced = body.substringAfter("```$LOCATOR_FENCE\n", missingDelimiterValue = "")
    if (fenced.isEmpty()) return null
    val content = fenced.substringBefore("\n```", missingDelimiterValue = "")
    if (content.isEmpty()) return null
    return locatorFromContent(content)
  }

  private fun locatorFromContent(content: String): Locator? {
    val fields =
      content
        .lineSequence()
        .mapNotNull { line ->
          val separator = line.indexOf(':')
          if (separator <= 0) null
          // Trim **both** ends, as the producer does. This side trimmed only the start, so a value
          // carrying a trailing space read one way here and another there — two engines disagreeing
          // about what a block says, which is precisely what the shared fixture exists to stop.
          else line.substring(0, separator).trim() to line.substring(separator + 1).trim()
        }
        .toMap()
    val overrides =
      runCatching { parseOverrides(fields["overrides"] ?: return null) }.getOrNull() ?: return null
    return Locator(
      repository = fields["repository"]?.takeIf { it.isNotBlank() } ?: return null,
      system = fields["system"]?.takeIf { it.isNotBlank() } ?: return null,
      componentId = fields["component"]?.takeIf { it.isNotBlank() } ?: return null,
      previewId = fields["preview"]?.takeIf { it.isNotBlank() } ?: return null,
      referenceId = fields["reference"]?.takeIf { it.isNotBlank() } ?: return null,
      variant = fields["variant"] ?: return null,
      overrides = overrides,
      element =
        fields["element"]?.let { runCatching { parseElement(it) }.getOrNull() ?: return null },
      bounds = fields["bounds"]?.let { runCatching { parseBounds(it) }.getOrNull() ?: return null },
      revision = fields["revision"]?.takeIf { it.isNotBlank() },
    )
  }

  fun canonicalOverrides(overrides: Map<String, String>): String {
    val sorted = overrides.entries.sortedWith { a, b -> compareCodePoints(a.key, b.key) }
    return Json.encodeToString(
      JsonObject.serializer(),
      JsonObject(sorted.associate { it.key to JsonPrimitive(it.value) }),
    )
  }

  /**
   * `element` is written as a **JSON string**, which is what keeps a tag from becoming syntax.
   *
   * The block is line-oriented `key: value`, and a `testTag` is an arbitrary string: one containing
   * a newline would not stay one field — `row\nrevision: injected` reads back as an element plus a
   * revision nobody wrote — and one carrying a fence delimiter could end the block early and drop
   * the whole issue from the index. Quoting also makes a tag with leading or trailing whitespace
   * expressible, which a format whose readers trim otherwise cannot carry at all.
   */
  fun canonicalElement(element: String): String =
    Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(element))

  /**
   * Canonical bounds JSON: the same code-point key order the overrides carry, so a block is
   * comparable byte for byte without parsing it back.
   */
  fun canonicalBounds(bounds: Bounds): String =
    Json.encodeToString(
      JsonObject.serializer(),
      JsonObject(
        // Code point order: height < space < width < x < y.
        linkedMapOf(
          "height" to JsonPrimitive(bounds.height),
          "space" to JsonPrimitive(bounds.space),
          "width" to JsonPrimitive(bounds.width),
          "x" to JsonPrimitive(bounds.x),
          "y" to JsonPrimitive(bounds.y),
        )
      ),
    )

  private fun parseElement(value: String): String {
    val element =
      Json.parseToJsonElement(value).jsonPrimitive.takeIf { it.isString }?.contentOrNull
        ?: error("element must be a JSON string")
    require(element.isNotEmpty()) { "element must not be empty" }
    require(canonicalElement(element) == value) { "element is not canonical JSON" }
    return element
  }

  private fun parseBounds(value: String): Bounds {
    val json = Json.parseToJsonElement(value).jsonObject
    val space = json["space"]?.jsonPrimitive?.contentOrNull ?: error("bounds names no space")
    // `v1` accepts only the plane both tag-index producers publish; see [Bounds] and D1.
    require(space == RENDER_PIXELS) { "bounds space must be $RENDER_PIXELS" }
    fun extent(key: String): Int =
      json[key]?.jsonPrimitive?.content?.toIntOrNull() ?: error("bounds $key must be an integer")
    // [Bounds] enforces the origin, extent and space invariants itself, so a rectangle that fails
    // them throws here and the caller's `runCatching` turns it into a refused locator.
    val bounds =
      Bounds(x = extent("x"), y = extent("y"), width = extent("width"), height = extent("height"))
    require(json.keys.size == 5) { "bounds carries unknown keys" }
    require(canonicalBounds(bounds) == value) { "bounds are not canonical JSON" }
    return bounds
  }

  private fun parseOverrides(value: String): Map<String, String> =
    Json.parseToJsonElement(value).jsonObject.mapValues { (_, element) ->
      element.jsonPrimitive.takeIf { it.isString }?.contentOrNull
        ?: error("override values must be strings")
    }

  private fun compareCodePoints(a: String, b: String): Int {
    var ai = 0
    var bi = 0
    while (ai < a.length && bi < b.length) {
      val ac = a.codePointAt(ai)
      val bc = b.codePointAt(bi)
      if (ac != bc) return ac.compareTo(bc)
      ai += Character.charCount(ac)
      bi += Character.charCount(bc)
    }
    return (a.length - ai).compareTo(b.length - bi)
  }

  /** Markdown-safe alt text: the preview's label or id, with `]` and `[` stripped. */
  private fun altText(ctx: Context): String {
    val what =
      ctx.previewLabel?.trim()?.takeIf { it.isNotEmpty() }
        ?: ctx.previewId?.trim()?.takeIf { it.isNotEmpty() }
        ?: "render"
    return what.replace('[', ' ').replace(']', ' ').trim()
  }

  /**
   * Whether [url] is one GitHub can actually render inline.
   *
   * An embedded image is fetched **by GitHub's camo proxy, not by the reader's browser**, so it has
   * to be reachable from the public internet over HTTPS. A developer's `compose-preview serve` on
   * `http://127.0.0.1:8080` (or a box on a private LAN, or a plain-HTTP host) fails that, and an
   * embed would put a broken-image icon in their issue where a working link belongs — so those
   * bodies keep the link form instead. Deliberately conservative: anything not clearly public is
   * treated as not embeddable.
   *
   * This is **reachability only**. Whether the lane will actually serve the request is a separate
   * question — see [Context.publicRender], which [body] requires as well.
   */
  fun isEmbeddable(url: String?): Boolean {
    val u = url?.trim() ?: return false
    if (!u.startsWith("https://")) return false
    val host = u.removePrefix("https://").substringBefore('/').substringBefore('?').lowercase()
    val name = host.substringBeforeLast(':').trim('[', ']')
    if (name.isEmpty()) return false
    // A public host is a dotted name. Single-label intranet names, `.local`, and raw loopback /
    // private addresses are all unreachable from camo.
    if (!name.contains('.') || name.endsWith(".local") || name.endsWith(".internal")) return false
    if (name == "localhost" || name.endsWith(".localhost")) return false
    return !isPrivateIpv4(name)
  }

  /** Loopback and RFC 1918 literals, which are dotted but still unreachable from outside. */
  private fun isPrivateIpv4(host: String): Boolean {
    val parts = host.split('.')
    if (parts.size != 4 || parts.any { it.toIntOrNull() == null }) return false
    val (a, b) = parts[0].toInt() to parts[1].toInt()
    return a == 127 || a == 10 || a == 0 || (a == 192 && b == 168) || (a == 172 && b in 16..31)
  }

  /**
   * The GitHub new-issue form for [repo], used as a `<form action>` rather than a link the JS
   * rewrites.
   *
   * The viewer surfaces this as a **GET form**: the reporter's `title` is a typed-in input and
   * `body` is a hidden one. That is not a styling preference: keeping the prefilled report current
   * as the knobs change means writing page state into it, and writing a page-derived string into an
   * anchor's `href` is a navigation sink (a `javascript:` URL there would execute) — CodeQL flags
   * it, correctly, no matter how the value is guarded afterwards. A form has no such sink: the
   * action is a server-rendered literal the JS never touches, the live render URL only ever lands
   * in an input value, and the browser does the query encoding on submit.
   */
  fun action(repo: String): String = "https://github.com/$repo/issues/new"

  /**
   * [url] with any `token=` query parameter dropped. A token-gated session bakes its session token
   * into every link on the page; that token **is** the capability to drive the server, so it must
   * never be carried into an issue body that gets posted publicly. The rest of the query (the
   * overrides that shape the render) is kept, since it is what makes the link reproduce what the
   * reporter saw.
   */
  fun withoutToken(url: String?): String? {
    val u = url?.takeIf { it.isNotBlank() } ?: return null
    val cut = u.indexOf('?')
    if (cut < 0) return u
    val kept =
      u.substring(cut + 1).split('&').filter { it.isNotEmpty() && !it.startsWith("token=") }
    return if (kept.isEmpty()) u.substring(0, cut)
    else u.substring(0, cut) + "?" + kept.joinToString("&")
  }
}
