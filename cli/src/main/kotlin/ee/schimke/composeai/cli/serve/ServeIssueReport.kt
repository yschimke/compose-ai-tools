package ee.schimke.composeai.cli.serve

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
   * Stand-in for the render URL inside [issueUrl]'s body, so the viewer JS can keep the link in
   * sync with the on-screen overrides without re-assembling the whole body client-side: the anchor
   * carries the encoded URL with this placeholder in it and swaps in the live `/render` URL on each
   * refresh. Chosen to be URL-encoded identically by [WebEscaping.urlEncodeSegment] and JS's
   * `encodeURIComponent` (`%7B%7Brender%7D%7D`), so the JS substitution is a plain string replace.
   */
  const val RENDER_PLACEHOLDER: String = "{{render}}"

  /** Repo bugs fall back to when a session names no source of its own — the renderer is ours. */
  const val FALLBACK_REPO: String = "yschimke/compose-ai-tools"

  /**
   * The facts a report carries. Everything but [repo] and [previewId] is optional: a plain local
   * session knows neither its catalog nor a source file, and simply drops those rows rather than
   * filing a half-empty template.
   */
  data class Context(
    /** `owner/name` the issue is filed against — see [repoFor]. */
    val repo: String,
    /** The preview's flattened id (`Button__filled__dark`), the one unambiguous handle. */
    val previewId: String,
    /** Human label, when the manifest recorded one; the title falls back to [previewId]. */
    val previewLabel: String? = null,
    /** The served design system (`wear-m3`), when this session is a catalog. */
    val system: String? = null,
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
    /** Absolute `/render/<id>.png` URL at the overrides in force when the page was served. */
    val renderUrl: String? = null,
  )

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

  /** Issue title — identifies the preview; the reporter writes the actual complaint. */
  fun title(ctx: Context): String {
    val what = ctx.previewLabel?.trim()?.takeIf { it.isNotEmpty() } ?: ctx.previewId
    val where = ctx.system?.trim()?.takeIf { it.isNotEmpty() }?.let { " ($it)" } ?: ""
    return "Preview issue: $what$where"
  }

  /**
   * Issue body, in markdown. [renderPlaceholder] swaps the render link for [RENDER_PLACEHOLDER] so
   * the viewer JS can substitute the live URL; the server-rendered `href` uses the real one, which
   * is what a visitor with JS off gets.
   */
  fun body(ctx: Context, renderPlaceholder: Boolean = false): String {
    val rows = buildList {
      ctx.system?.trim()?.takeIf { it.isNotEmpty() }?.let { add("| Design system | `$it` |") }
      add("| Preview | `${ctx.previewId}` |")
      ctx.sourceUrl?.takeIf { it.isNotBlank() }?.let { add("| Source | $it |") }
      ctx.catalog?.takeIf { it.isNotBlank() }?.let { add("| Catalog | `$it` |") }
      ctx.toolVersion
        ?.takeIf { it.isNotBlank() }
        ?.let { add("| Rendered by | compose-ai-tools $it |") }
    }
    val render =
      if (renderPlaceholder) RENDER_PLACEHOLDER else ctx.renderUrl?.takeIf { it.isNotBlank() }
    val links = buildList {
      ctx.viewerUrl?.takeIf { it.isNotBlank() }?.let { add("[Open this preview]($it)") }
      render?.let { add("[PNG at these settings]($it)") }
    }
    return buildString {
      append("### What's wrong\n\n")
      append("<!-- What did you expect to see, and what did you get? -->\n\n\n")
      append("### Screenshot\n\n")
      append(
        "<!-- Paste it here. The viewer's \"Export & direct links\" panel has a Copy PNG button " +
          "that puts the image itself on your clipboard, so Ctrl-V / Cmd-V lands the exact render " +
          "in this issue. -->\n\n\n"
      )
      append("### Which preview\n\n")
      append("| | |\n| --- | --- |\n")
      append(rows.joinToString("\n"))
      if (links.isNotEmpty()) append("\n\n").append(links.joinToString(" · "))
      append("\n")
    }
  }

  /**
   * The full `https://github.com/<repo>/issues/new?title=…&body=…` URL. With [renderPlaceholder]
   * the body's render link is [RENDER_PLACEHOLDER] (already percent-encoded), giving the viewer JS
   * a template it can retarget at the current overrides.
   */
  fun issueUrl(ctx: Context, renderPlaceholder: Boolean = false): String =
    "https://github.com/${ctx.repo}/issues/new" +
      "?title=${WebEscaping.urlEncodeSegment(title(ctx))}" +
      "&body=${WebEscaping.urlEncodeSegment(body(ctx, renderPlaceholder))}"

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
