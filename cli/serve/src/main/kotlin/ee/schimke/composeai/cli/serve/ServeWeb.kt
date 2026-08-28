package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.agentgrants.AgentGrantCapability
import ee.schimke.composeai.agentgrants.AgentGrantProtocol
import ee.schimke.composeai.agentgrants.AgentGrantScope
import ee.schimke.composeai.bundle.BundleVerifier
import ee.schimke.composeai.data.overrides.PreviewOverrideOption
import ee.schimke.composeai.data.render.PreviewBackdrop
import ee.schimke.composeai.data.render.PreviewBackground
import ee.schimke.composeai.data.render.PreviewClip
import ee.schimke.composeai.designpages.DesignPage
import ee.schimke.composeai.designpages.PageNode
import ee.schimke.composeai.imagecrop.ContentCrop
import java.time.Instant
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Server-rendered HTML for the `compose-preview serve` web surface. Large static CSS/JS lives in
 * classpath assets served by [ServeWebAssets]; this object keeps the dynamic HTML and small
 * value-injected bootstraps that need token, session, or preview data.
 */
object ServeWeb {

  /**
   * Sign-in affordance for a GitHub-protected live stream lane.
   *
   * Deliberately carries no repository: the live stream gates on *being signed in*, nothing more
   * ([ServeHttpServer.rejectMissingGithubRepoAccess] is the playground's gate, not this one). The
   * chip used to name `--github-auth-repo` in its tooltip, which read as "you need access to that
   * repo for Live" — the opposite of the rule, and enough to make an outside contributor give up
   * before clicking (wear-m3-catalog#68).
   *
   * [restrictedToAllowedUsers] is the one thing that can narrow it: with `--github-auth-users` set,
   * [GitHubOAuthVerifier] refuses every login outside the list, so "any GitHub account works" would
   * walk those visitors through OAuth to a 403. Same distinction the front door's control already
   * draws — the allowlist restricts *sign-in itself*, which the repo check never did.
   */
  data class LiveAuthPrompt(
    val loginHref: String,
    val restrictedToAllowedUsers: Boolean = false,
  )

  /** Front-door GitHub auth state, shown when the public server protects code-running surfaces. */
  data class GitHubAuthStatus(
    val loginHref: String,
    val login: String? = null,
    val restrictedToAllowedUsers: Boolean = false,
    /**
     * What the sign-in unlocks on the page carrying this control, which is what its tooltip has to
     * describe. The two lanes have genuinely different gates — live streams open to any signed-in
     * visitor, the playground additionally wants access to [accessRepository] — so a control shown
     * on a catalog whose *only* gated lane is the playground must not promise Live.
     *
     * [LIVE] is the default because it is what the front door and `/status` carry: those pages
     * stand above any one catalog, so they describe the capability the sign-in most broadly unlocks
     * rather than answering for a particular catalog's lanes.
     */
    val lane: GatedLane = GatedLane.LIVE,
    /**
     * `--github-auth-repo`, named only when [lane] is [GatedLane.PLAYGROUND] — the one case where
     * repository access is genuinely part of what the visitor needs. Deliberately absent from the
     * Live wording: naming it there is the confusion this whole change exists to remove
     * (wear-m3-catalog#68).
     */
    val accessRepository: String? = null,
  )

  /** The capability a header sign-in control speaks for. See [GitHubAuthStatus.lane]. */
  enum class GatedLane {
    LIVE,
    PLAYGROUND,
  }

  /**
   * Absolute URLs advertised to link unfurlers for a browser-facing page. [imageUrl] is the thing
   * that page represents (a featured catalog hero, catalog component, or exact viewer render);
   * utility/error pages leave it null and get an honest text-only card. Kept explicit rather than
   * derived here because only the HTTP layer knows the externally visible scheme/host (notably when
   * Caddy terminates TLS).
   *
   * [imageWidth]/[imageHeight] are the image's real pixel dimensions, read from the PNG's IHDR by
   * the caller. Advertising them is not decoration: without them an unfurler has to download the
   * image and measure it before it can lay out a card, and both Slack and Google drop the image
   * rather than block on that when the fetch is slow or the measure fails. They also decide which
   * card the page gets — see [twitterCard], which stops claiming a large-image card for a thumbnail
   * that cannot fill one.
   */
  data class UnfurlMetadata(
    val pageUrl: String,
    val imageUrl: String? = null,
    val imageWidth: Int? = null,
    val imageHeight: Int? = null,
  )

  /**
   * The narrower edge a `summary_large_image` card needs before it is worth asking for.
   *
   * Slack and Twitter/X both fall back to the small card for an image below roughly this size, and
   * Google recommends 512² as the floor for a preview image — so a page that asks for the large
   * card with a 300×210 component render gets the small one anyway, having first told the fetcher
   * something untrue. A single component preview genuinely is a thumbnail; asking for `summary` and
   * getting a clean square beats asking for a banner and getting a broken one.
   */
  private const val LARGE_CARD_MIN_EDGE = 320

  /**
   * The narrowest and widest **aspect** (width ÷ height) worth claiming a `summary_large_image`
   * for.
   *
   * Size was never the whole story. Every consumer lays the large card out in a slot of roughly
   * 1.91:1 and fits the image to it by cropping, so what the card actually shows is a 1.91:1 window
   * onto the picture — and the further the picture's own aspect is from that, the less of it
   * survives. A catalog hero is the worst case in this codebase and it is not a near miss:
   * `compose-m3`'s is 1078×2399, an aspect of **0.45**, so the window keeps a horizontal band
   * through the middle of a phone screenshot and throws away 78% of the image. On that particular
   * render the surviving band was the empty half of an app scaffold — the front door unfurled as a
   * strip of blank dark pixels, at full card size, having passed the min-edge test comfortably.
   *
   * The band is the set of shapes whose crop still leaves roughly two thirds of the picture.
   * Cropping an image of aspect `a` into a 1.91 slot keeps `a / 1.91` of its height when it is
   * taller than the slot, and `1.91 / a` of its width when it is wider — so 1.25 and 2.4 are the
   * points either side where a third of the image starts to disappear. A 4:3 screenshot (1.33)
   * survives that comfortably; a square watch face (1.0, barely half kept) and a portrait phone
   * screenshot (0.45, a quarter kept) do not, and both are genuinely better served by `summary`,
   * which shows the whole image beside the text instead of a slice of it.
   *
   * This is a floor for *raw artwork*. The pages that matter most — the front door and each catalog
   * landing — don't rely on it, because they advertise a drawn [ServeSocialCard] at exactly
   * 1200×630 (1.90) rather than a render, and so are inside the band by construction.
   */
  private const val LARGE_CARD_MIN_ASPECT = 1.25

  private const val LARGE_CARD_MAX_ASPECT = 2.4

  /**
   * `twitter:card` for an unfurl — the large-image card only when there is an image *and* we know
   * it can fill one: big enough on both edges ([LARGE_CARD_MIN_EDGE]) and close enough in shape to
   * the slot it will be cropped into ([LARGE_CARD_MIN_ASPECT]..[LARGE_CARD_MAX_ASPECT]).
   *
   * An image whose dimensions we couldn't read keeps the large card: unknown size is not evidence
   * of a small or badly-shaped image, and the fetcher measures it itself in that case.
   */
  private fun twitterCard(unfurl: UnfurlMetadata): String {
    if (unfurl.imageUrl == null) return "summary"
    val w = unfurl.imageWidth
    val h = unfurl.imageHeight
    if (w == null || h == null) return "summary_large_image"
    if (w < LARGE_CARD_MIN_EDGE || h < LARGE_CARD_MIN_EDGE) return "summary"
    val aspect = w.toDouble() / h
    return if (aspect in LARGE_CARD_MIN_ASPECT..LARGE_CARD_MAX_ASPECT) "summary_large_image"
    else "summary"
  }

  /** Aggregate engagement metrics surfaced by the live server UI/API. */
  data class PreviewEngagement(val views: Long = 0)

  private fun assetHref(name: String): String = ServeWebAssets.href(name)

  /**
   * The verdict band a published match percentage falls in — the chip's colour, and nothing else.
   *
   * Restates `matchBand` in `scripts/design-artifacts/design-reference-score.mjs`, where the number
   * is minted. The thresholds come from the distribution a real catalog produces rather than from
   * round numbers, so they moved with the metric (issue #4290): the score is now measured over the
   * pixels the two frames actually drew on rather than over the whole canvas, and across
   * wear-m3-catalog's 186 published pairs that runs 4%..100% with a median of 91. 63 sit at or
   * above 95, and the 59 below 85 are the genuine divergences — a 4% scroll indicator, a 52%
   * picker, a 70% stepper that lost its button fills.
   *
   * A band never decides whether the number is SHOWN, only how it is coloured, so a drift between
   * the two copies costs a hue and can never hide a finding.
   */
  private fun specMatchBand(percent: Double): String =
    when {
      percent >= 95.0 -> "match"
      percent >= 85.0 -> "close"
      else -> "off"
    }

  private fun scriptTag(name: String): String = "<script src=\"${assetHref(name)}\"></script>"

  private fun viewCountHtml(views: Long): String =
    if (views <= 0) "" else "<div class=\"cp-engage\">${formatViews(views)}</div>"

  /**
   * The viewer's view tally. A `<span>`, not a block: it sits on the title row beside the id, where
   * it reads as one more fact about this preview rather than a paragraph of its own.
   */
  private fun viewerViewCountHtml(views: Long): String =
    if (views <= 0) "" else "<span class=\"cp-viewer-engage\">${formatViews(views)}</span>"

  private fun formatViews(views: Long): String =
    "${formatCount(views)} ${if (views == 1L) "view" else "views"}"

  private fun formatCount(n: Long): String =
    if (n < 1000) n.toString()
    else String.format(java.util.Locale.ROOT, "%.1f", n / 1000.0).removeSuffix(".0") + "k"

  /**
   * The starter snippet the [playgroundPage] editor opens with — a minimal Material 3 `@Preview`.
   */
  private val PLAYGROUND_SAMPLE =
    """
    import androidx.compose.material3.Button
    import androidx.compose.material3.Text
    import androidx.compose.runtime.Composable
    import androidx.compose.ui.tooling.preview.Preview

    @Preview
    @Composable
    fun Greeting() {
        Button(onClick = {}) {
            Text("Hello, Compose!")
        }
    }
    """
      .trimIndent()

  /**
   * Query string carrying the token and — only for a non-default tenant ([sessionId] non-null) —
   * the `session` id, so generated links stay on the same tenant. A null [sessionId] (the default
   * session) keeps URLs token-only.
   *
   * In [isPublic] mode every route is open (the token gates nothing), so the `token=` param is
   * **omitted** — a public link like `preview.coo.ee/compose-m3/` shouldn't drag a useless token
   * around. Non-public keeps the token as the only gate. May return an empty string (public + the
   * default session), so callers wrap it with [querySuffix] to avoid a dangling `?`.
   */
  private fun queryString(token: String, sessionId: String?, isPublic: Boolean): String {
    val parts = buildList {
      if (!isPublic) add("token=" + WebEscaping.urlEncodeSegment(token))
      if (sessionId != null) add("session=" + WebEscaping.urlEncodeSegment(sessionId))
    }
    return parts.joinToString("&")
  }

  /**
   * The query string for a same-session link, given the page's [basePath]. When the page is served
   * under a `/<system>` path ([basePath] non-empty) the session is carried by the path, so links
   * are **token-only** — no `&session=`. When it's the root-mounted default/legacy `?session=` form
   * ([basePath] empty) it falls back to [queryString]. In [isPublic] mode the token is dropped
   * either way (may return empty — wrap with [querySuffix]).
   *
   * A **top-level site** ([ServeSites]) is the third case and needs no code here: it is rooted like
   * the legacy form but carries its session in the ORIGIN, so its pages pass a null session id to
   * this function (see each page's `linkSessionId`) while keeping the real one for the per-catalog
   * storage keys and the dark-first lookup.
   */
  private fun linkQuery(
    token: String,
    sessionId: String?,
    basePath: String,
    isPublic: Boolean,
  ): String =
    if (basePath.isEmpty()) queryString(token, sessionId, isPublic)
    else if (isPublic) "" else "token=" + WebEscaping.urlEncodeSegment(token)

  /**
   * Prefix a query with `?` when non-empty, else the empty string (no dangling `?` on token-free
   * public links).
   */
  private fun querySuffix(query: String): String = if (query.isEmpty()) "" else "?$query"

  /**
   * The viewer's compact producer warning. Trusted catalogs carry no badge; an unverified one is
   * called out consistently with the home index.
   */
  private fun compactTrustBadge(trust: String?): String {
    if (trust != "unverified") return ""
    return " <span class=\"cp-badge cp-badge--unverified\" " +
      "title=\"producer trust: unverified\">⚠ untrusted</span>"
  }

  /**
   * The public front door only calls out a negative producer verdict: unverified catalogs are
   * orange and labelled `untrusted`, while trusted catalogs carry no badge. The full verdict and
   * its basis remain available on `/status` and on the catalog's own pages.
   */
  private fun homeTrustBadge(trust: String?): String {
    if (trust != "unverified") return ""
    return " <span class=\"cp-badge cp-badge--unverified\" " +
      "title=\"producer trust: unverified\">⚠ untrusted</span>"
  }

  /**
   * The session-level **"why snapshot-only" banner** — one amber `<section>` under the header
   * listing each [ServeDegradation]'s human [detail][ServeDegradation.detail] (e.g. "this catalog
   * publishes no live bundle"). Empty string when [degradations] is empty (a fully-live session or
   * a plain module), so no banner renders. This explains the *session-level* reason a live lane is
   * absent; the viewer's per-control `cp-note` still explains what each individual override needs.
   */
  private fun degradeBanner(degradations: List<ServeDegradation>): String {
    if (degradations.isEmpty()) return ""
    val items =
      degradations.joinToString("\n        ") {
        "<span class=\"cp-degrade-item\">${WebEscaping.htmlEscape(it.detail)}</span>"
      }
    return """
      <section class="cp-degrade" role="note" aria-label="Why this preview is snapshot-only">
        <span class="cp-degrade-icon" aria-hidden="true">ⓘ</span>
        $items
      </section>
      """
      .trimIndent() + "\n"
  }

  /**
   * What a page knows about the **published revisions** of the catalog it is showing: which one it
   * is pinned to (null ⇒ the current one), the branch's recent history, and the repo those commits
   * live in.
   *
   * Carried as data rather than as prebuilt HTML because each page addresses itself differently — a
   * viewer link is `/p/<id>`, a comparison's is `/compare/<id>?reference=…` — so the page that owns
   * the URL shape is the one that must build the destinations. See [revisionsHtml].
   */
  data class CatalogRevisions(
    val pinned: String? = null,
    val revisions: List<ServeCatalogRevision.Revision> = emptyList(),
    val repo: String? = null,
  ) {
    /** Nothing to say: no history to offer and no pin to announce. */
    val isEmpty: Boolean
      get() = pinned == null && revisions.isEmpty()

    companion object {
      val NONE = CatalogRevisions()
    }
  }

  /**
   * The revision control: the pin banner (when the page is showing an older publish) above the list
   * of publishes it can move between.
   *
   * This is the whole answer to "a published URL keeps changing under me" (issue #3723). The
   * delivery branch carries one commit per publish, so the versions already exist — what was
   * missing was a way to *name* one from the page and a way to *reach* the others. [hrefFor] builds
   * this same page at a given pin (null ⇒ the live one), which is what makes both halves one
   * control rather than a banner and an unrelated menu.
   *
   * A revision is shown by its publish date and the **source** commit it was rendered from where
   * the subject recorded one, falling back to the delivery sha. That ordering is deliberate: the
   * delivery sha is a publish marker, while the source sha is the change someone is actually
   * looking for when they go back a version.
   */
  private fun revisionBannerHtml(
    revisions: CatalogRevisions,
    hrefFor: (String?) -> String,
  ): String {
    val pinned = revisions.pinned ?: return ""
    val entry = revisions.revisions.firstOrNull { it.commit == pinned }
    val shaLink =
      ServeCatalogRevision.treeUrl(revisions.repo, pinned)?.let { url ->
        "<a href=\"${WebEscaping.htmlEscape(url)}\" target=\"_blank\" rel=\"noopener noreferrer\">" +
          "<code>${WebEscaping.htmlEscape(ServeCatalogRevision.short(pinned))}</code></a>"
      } ?: "<code>${WebEscaping.htmlEscape(ServeCatalogRevision.short(pinned))}</code>"
    val published =
      entry
        ?.date
        ?.takeIf { it.isNotBlank() }
        ?.let { ", published ${WebEscaping.htmlEscape(prettyDate(it))}" }
        .orEmpty()
    return """
      <section class="cp-pinned" role="note" aria-label="Pinned revision">
        <span class="cp-pinned-icon" aria-hidden="true">⚓</span>
        <span>Pinned to catalog revision $shaLink$published — these pixels cannot change.</span>
        <a class="cp-pinned-current" href="${WebEscaping.htmlEscape(hrefFor(null))}">view current</a>
      </section>
      """
      .trimIndent()
  }

  internal fun revisionsHtml(
    revisions: CatalogRevisions,
    includeBanner: Boolean = true,
    /**
     * Attributes for `<cp-revision-runs>`, or blank to leave the menu undecorated.
     *
     * Passed in rather than assembled here because the two URLs it carries — the runs lane and the
     * preview's render — belong to a *preview*, and this function draws the catalog-wide control on
     * pages that have no single preview behind them (a design reference, an unavailable-revision
     * page). Blank on those is the correct answer, not a missing feature.
     */
    runsAttrs: String = "",
    /**
     * A `/api/render-runs` payload inlined into the page, so the preview-harness captures the
     * markers offline. Blank on every served page — the element fetches there — and non-blank only
     * in the fixture, which is the only reason this parameter exists.
     */
    runsInlineJson: String = "",
    hrefFor: (String?) -> String,
  ): String {
    if (revisions.isEmpty) return ""
    val pinned = revisions.pinned
    val current = revisions.revisions.firstOrNull()?.commit
    val banner = if (includeBanner) revisionBannerHtml(revisions, hrefFor) else ""
    if (revisions.revisions.isEmpty()) return "$banner\n"
    val rows =
      revisions.revisions.joinToString("\n            ") { revision ->
        val isCurrent = revision.commit == current
        // A pin is what the page URL says; with no pin the page is showing the branch tip, so that
        // is the row marked. One row is marked either way, and never two.
        val selected = if (pinned == null) isCurrent else revision.commit == pinned
        val href = hrefFor(revision.commit.takeUnless { isCurrent })
        val date =
          revision.date.takeIf { it.isNotBlank() }?.let { prettyDate(it) } ?: revision.short
        val label = revision.sourceSha ?: revision.short
        val mark = if (selected) " aria-current=\"true\"" else ""
        val currentTag = if (isCurrent) "<span class=\"cp-revision-tag\">current</span>" else ""
        // `nofollow` because these are the same page over and over: a crawler that walked them
        // would index a dozen near-duplicates of every preview, and the version worth indexing is
        // the live one. The pages stay perfectly shareable — a link someone pastes is followed by
        // a person and unfurled by a fetcher, neither of which is a crawl.
        // The delivery sha on the row itself, which is the only thing that identifies it to
        // `<cp-revision-runs>`. Not derivable from the href: the current row deliberately carries
        // no `?at=` pin, so a client parsing hrefs would fail to mark the one row that is always a
        // run head.
        val stamp = " data-revision=\"${WebEscaping.htmlEscape(revision.commit)}\""
        "<a class=\"cp-revision\" rel=\"nofollow\" href=\"${WebEscaping.htmlEscape(href)}\"$mark" +
          "$stamp>" +
          "<span class=\"cp-revision-date\">${WebEscaping.htmlEscape(date)}</span>" +
          "<code class=\"cp-revision-sha\">${WebEscaping.htmlEscape(label)}</code>$currentTag</a>"
      }
    // The trigger names the revision the page is *on* — the pin when there is one, the tip
    // otherwise — so the closed menu already answers "which version am I looking at?", which was
    // the question the flat wall of chips answered only by making the reader hunt for the
    // highlighted one. Its accessible name is that visible text, deliberately: an `aria-label` here
    // would override the date, sha and current/pinned state and announce the control as bare
    // "Revision".
    //
    // It looks like a menu button and is a plain disclosure, which is what the ARIA says too. No
    // `role="menu"`/`menuitem`: those promise the menu keyboard model — arrow-key navigation, Esc
    // to dismiss, managed focus — and nothing here implements it, so the roles would describe
    // behaviour a keyboard user does not get. `<details>` + a list of links gives real disclosure
    // and ordinary Tab order for free; the `<nav>` is what names the list for a screen reader.
    val shown = revisions.revisions.firstOrNull { it.commit == (pinned ?: current) }
    val shownDate =
      shown?.date?.takeIf { it.isNotBlank() }?.let { prettyDate(it) }
        ?: pinned?.let { ServeCatalogRevision.short(it) }
        ?: shown?.short
        ?: ""
    val shownSha =
      shown?.sourceSha ?: shown?.short ?: pinned?.let { ServeCatalogRevision.short(it) }
    val shownTag =
      if (pinned == null) "<span class=\"cp-revision-tag\">current</span>"
      else "<span class=\"cp-revision-tag cp-revision-tag--pinned\">pinned</span>"
    val triggerContent =
      if (pinned == null) shownTag
      else
        """<span class="cp-revisions-key">Revision</span>
          <span class="cp-revision-date">${WebEscaping.htmlEscape(shownDate)}</span>
          ${shownSha?.let { "<code class=\"cp-revision-sha\">${WebEscaping.htmlEscape(it)}</code>" }.orEmpty()}
          $shownTag"""
    return banner +
      """
      <details class="cp-revisions">
        <summary class="cp-revisions-btn">
          $triggerContent
          <span class="cp-revisions-caret" aria-hidden="true">▾</span>
        </summary>
        <div class="cp-revisions-menu">
          ${if (runsAttrs.isBlank()) "" else "<cp-revision-runs$runsAttrs></cp-revision-runs>"}
          ${
        // `</script>` inside a JSON payload would end the element early, so the only sequence that
        // can break out is neutralised — the same treatment `cp-history-data` gets, and for the
        // same reason.
        if (runsInlineJson.isBlank()) ""
        else
          "<script type=\"application/json\" id=\"cp-revision-runs-data\">" +
            runsInlineJson.replace("</", "<\\/") +
            "</script>"
      }
          <nav class="cp-revision-list" aria-label="Published revisions">
            $rows
          </nav>
          <p class="cp-revision-note">Every publish of this design system is a commit on its
          delivery branch. Opening one pins this page — and the pixels on it — to that publish for
          good.</p>
        </div>
      </details>
      """
        .trimIndent() +
      "\n"
  }

  /**
   * Add `at=<sha>` to a link, or return it unchanged when the page carries no pin. One helper
   * because a pinned page has to pin *everything* it links — the render, the reference, its sibling
   * variants — and a single missed suffix is a panel quietly showing the present next to the past.
   *
   * Callers pass either a bare query suffix (empty, or already `?…`) or a whole URL that may or may
   * not carry a query, so the separator is chosen from what the string actually contains rather
   * than from whether it is empty. Getting that wrong is not a cosmetic slip: a public server
   * builds token-free links, so `/<system>/p/<id>` has no `?` at all, and appending `&at=<sha>`
   * folds the pin into the *path* — the URL 404s and every revision in the menu is a dead link.
   */
  private fun withPin(link: String, pinned: String?): String {
    val pin = pinned?.takeIf { it.isNotBlank() } ?: return link
    val param = "${ServeCatalogRevision.PARAM}=${WebEscaping.urlEncodeSegment(pin)}"
    return when {
      !link.contains('?') -> "$link?$param"
      link.endsWith('?') || link.endsWith('&') -> "$link$param"
      else -> "$link&$param"
    }
  }

  /** Canonical source repo, used for the "source" / branch / workflow links. */
  private const val SOURCE_REPO = "yschimke/compose-ai-tools"

  /**
   * How often an open catalog page tells the server a visitor is still there ([presenceScript]).
   *
   * Comfortably under the session reaper's ten-minute idle window, and by enough that a single
   * dropped ping — a sleeping laptop, a flaky connection, a tab briefly backgrounded — doesn't let
   * the session lapse. Cheap at this rate: one empty POST per open tab per four minutes.
   */
  internal const val PRESENCE_INTERVAL_SECONDS = 240

  /**
   * Where the Catalog / Dev switch remembers the visitor's choice: a host-wide cookie, read by the
   * server on every request (`ServeHttpServer.componentBrowserMode`).
   *
   * A cookie rather than `localStorage` because the choice decides what the *server* renders. Kept
   * on the client, the only way to act on it was to put it in the URL — so every page rewrote every
   * same-origin link to carry `?chrome=`, and a bare URL had to be bounced through a
   * `location.replace` before it could paint. This is one header the browser already sends, and the
   * URLs go back to being about the thing they address.
   */
  internal const val INTERFACE_MODE_COOKIE = "cp_chrome"

  /** How long the remembered Catalog / Dev choice sticks around. A preference, so: a year. */
  private const val INTERFACE_MODE_COOKIE_MAX_AGE = 31536000

  /**
   * Attributes the switch writes [INTERFACE_MODE_COOKIE] with: host-wide (the mode is the whole
   * site's, not one path's) and `SameSite=Lax`, which is all a presentation preference needs — it
   * carries no identity and gates nothing. `Secure` is appended by the script when the page is
   * itself https, so the same markup works on a `http://127.0.0.1` dev server, where a `Secure`
   * cookie would simply be dropped. Deliberately readable by script: the switch is client-side.
   */
  private const val INTERFACE_MODE_COOKIE_ATTRS =
    "; path=/; max-age=$INTERFACE_MODE_COOKIE_MAX_AGE; samesite=lax"

  /**
   * How many theme chips the viewer bar shows inline before folding. Lower than [AXIS_CHIPS_INLINE]
   * because the bar is capped at a single non-wrapping row: past a handful the chips ellipsise into
   * stubs and the group scrolls within itself, which is worse than a toggle that spells the current
   * theme out in full.
   */
  private const val THEME_CHIPS_INLINE = 4

  /**
   * Which of the design-spec lane's four views the page is served pressed, and therefore the one
   * `?specView=` leaves unsaid.
   *
   * Triptych since #4376 — the lane is entered to ask how the render and the imported reference
   * compare, and spec / diff / render side by side answers that on arrival where the plain
   * reference (the lane's original view, still one click away) only asked the eye to hold one frame
   * while looking at the other. The browser side keeps the same constant in
   * `serve-web/src/spec/views.ts`; they are the same decision rendered twice, so move both together
   * or the served page opens pressing a button the script immediately unpresses.
   */
  internal const val SPEC_DEFAULT_VIEW = "triptych"

  // android.content.res.Configuration values, kept local so the CLI has no Android dependency.
  private const val UI_MODE_NIGHT_MASK = 0x30
  private const val UI_MODE_NIGHT_NO = 0x10
  private const val UI_MODE_NIGHT_YES = 0x20

  /** Inline GitHub mark (Octicons, MIT). Rendered beside source and authentication links. */
  /** Feed glyph for the footer's changelog entry — the shape a reader recognises as a feed. */
  private const val RSS_ICON =
    "<svg class=\"cp-gh\" viewBox=\"0 0 16 16\" aria-hidden=\"true\" fill=\"currentColor\">" +
      "<circle cx=\"3\" cy=\"13\" r=\"2\"/>" +
      "<path d=\"M1 8.5a6.5 6.5 0 016.5 6.5h-2A4.5 4.5 0 001 10.5v-2z\"/>" +
      "<path d=\"M1 3a12 12 0 0112 12h-2A10 10 0 001 5V3z\"/></svg>"

  private const val GITHUB_ICON =
    "<svg class=\"cp-gh\" viewBox=\"0 0 16 16\" aria-hidden=\"true\" fill=\"currentColor\">" +
      "<path d=\"M8 0C3.58 0 0 3.58 0 8c0 3.54 2.29 6.53 5.47 7.59.4.07.55-.17.55-.38 " +
      "0-.19-.01-.82-.01-1.49-2.01.37-2.53-.49-2.69-.94-.09-.23-.48-.94-.82-1.13-.28-.15-.68-.52-.01-.53." +
      "63-.01 1.08.58 1.23.82.72 1.21 1.87.87 2.33.66.07-.52.28-.87.51-1.07-1.78-.2-3.64-.89-3.64-3.95 " +
      "0-.87.31-1.59.82-2.15-.08-.2-.36-1.02.08-2.12 0 0 .67-.21 2.2.82.64-.18 1.32-.27 2-.27.68 0 " +
      "1.36.09 2 .27 1.53-1.04 2.2-.82 2.2-.82.44 1.1.16 1.92.08 2.12.51.56.82 1.27.82 2.15 0 " +
      "3.07-1.87 3.75-3.65 3.95.29.25.54.73.54 1.48 0 1.07-.01 1.93-.01 2.2 0 .21.15.46.55.38A8.01 " +
      "8.01 0 0016 8c0-4.42-3.58-8-8-8z\"/></svg>"

  /**
   * Inline Figma mark, monochrome in `currentColor` so it sits in the same muted link row as
   * [GITHUB_ICON]. Its own class carries the 2:3 aspect (`.cp-gh` alone would squash the tall
   * viewBox into a square).
   */
  private const val FIGMA_ICON =
    "<svg class=\"cp-gh cp-figma-mark\" viewBox=\"0 0 38 57\" aria-hidden=\"true\" " +
      "fill=\"currentColor\">" +
      "<path d=\"M19 28.5a9.5 9.5 0 1 1 19 0 9.5 9.5 0 0 1-19 0z\"/>" +
      "<path d=\"M0 47.5A9.5 9.5 0 0 1 9.5 38H19v9.5a9.5 9.5 0 0 1-19 0z\"/>" +
      "<path d=\"M19 0v19h9.5a9.5 9.5 0 1 0 0-19H19z\"/>" +
      "<path d=\"M0 9.5A9.5 9.5 0 0 0 9.5 19H19V0H9.5A9.5 9.5 0 0 0 0 9.5z\"/>" +
      "<path d=\"M0 28.5A9.5 9.5 0 0 0 9.5 38H19V19H9.5A9.5 9.5 0 0 0 0 28.5z\"/></svg>"

  /**
   * The launcher's mark: a speech bubble with an exclamation in it — "say something is wrong".
   *
   * Deliberately not the GitHub mark the two reports otherwise wear. Those marks say *where a
   * report lands*, which is the right label on a control that files one; this button files nothing,
   * it opens the choice between two destinations, and stamping one of their logos on it would
   * pre-announce an answer the panel exists to ask.
   */
  private const val REPORT_ICON =
    "<svg class=\"cp-fab-mark\" viewBox=\"0 0 24 24\" aria-hidden=\"true\" fill=\"none\" " +
      "stroke=\"currentColor\" stroke-width=\"1.9\" stroke-linecap=\"round\" " +
      "stroke-linejoin=\"round\">" +
      "<path d=\"M20.5 12.4a7.7 7.7 0 0 1-8.3 7.6c-.7 0-1.4-.1-2-.3L4 21l1.4-3.9a7.3 7.3 0 0 1-1.9-4.9" +
      " 7.7 7.7 0 0 1 8.5-7.6 7.8 7.8 0 0 1 8.5 7.8z\"/>" +
      "<path d=\"M12 8.4v4\"/><path d=\"M12 15.4h.01\"/></svg>"

  /** GitHub session action shown in the home-page header when OAuth is configured. */
  private fun githubAuthControl(status: GitHubAuthStatus?): String {
    status ?: return ""
    // What this sign-in buys, in the visitor's terms. The allowlist narrows *who may sign in at
    // all*, so it reshapes either sentence; the repo is named only on the playground, whose gate
    // it actually is.
    val repo =
      status.accessRepository?.let { " with access to ${WebEscaping.htmlEscape(it)}" } ?: ""
    val tooltip =
      when {
        status.lane == GatedLane.PLAYGROUND && status.restrictedToAllowedUsers ->
          "Playground access is limited to configured GitHub users$repo"
        status.lane == GatedLane.PLAYGROUND -> "The playground requires a GitHub sign-in$repo"
        status.restrictedToAllowedUsers ->
          "Live preview access is limited to configured GitHub users"
        else -> "Live previews require a GitHub sign-in"
      }
    val tooltipAttr = " title=\"$tooltip\""
    val login = status.login?.takeIf { it.isNotBlank() }
    return if (login == null) {
      "<a class=\"cp-gh-auth\" href=\"${WebEscaping.htmlEscape(status.loginHref)}\"" +
        "$tooltipAttr>$GITHUB_ICON Sign in with GitHub</a>"
    } else {
      "<span class=\"cp-gh-auth cp-gh-auth--signed\"$tooltipAttr>$GITHUB_ICON " +
        "Signed in as ${WebEscaping.htmlEscape(login)}</span>"
    }
  }

  /**
   * The minimal site footer — GitHub, `/version`, "report a bug", and the running build — rendered
   * by [document] at the bottom of **every** browser-facing page, below the body. [version]
   * null/blank just drops the build span; the other entries stay, so the footer is never empty.
   *
   * The **GitHub** entry — the repo that ships this server — is the site's only link to it; the
   * header used to carry a second copy (see [siteHeader]). It reads "GitHub" rather than the
   * "source" it once did, because it opens the repo's front page, and the label "source" is already
   * spoken for by [sourceLinkHtml], the per-preview link that opens the *file* a preview is
   * declared in. Two links a click apart, both saying "source", went to different kinds of place.
   *
   * [note] is the page's own footer block, rendered *above* the links row: on a catalog landing
   * that's the provenance disclosure ([provenanceSection]), which belongs with the build/source
   * metadata rather than in the middle of the catalog's content. Empty on every other page.
   *
   * [bugReport] false drops the "report a bug" entry — passed by the report page itself, which is
   * where that entry leads.
   *
   * [changelogHref] adds the **Changelog** entry: the catalog's own `/feed.xml`, the published
   * history of what changed in the design system this page belongs to. It leads the row because it
   * is the only entry about the *content* — the rest are about the server. Empty wherever no such
   * history exists (the front door, `/status`, a plain module, a server started with the feed lane
   * off), so the link is never offered where it would 404.
   */
  private fun siteFooter(
    version: String?,
    note: String = "",
    bugReport: Boolean = true,
    changelogHref: String = "",
  ): String {
    val ver =
      version
        ?.takeIf { it.isNotBlank() }
        ?.let {
          " · <span class=\"cp-about-ver\" title=\"running preview-server build\">" +
            "server v${WebEscaping.htmlEscape(it)}</span>"
        } ?: ""
    val noteBlock =
      note.takeIf { it.isNotBlank() }?.let { "${it.trimEnd().prependIndent("        ")}\n" } ?: ""
    val report = if (bugReport) "\n${reportBugFormHtml().prependIndent("          ")} ·" else ""
    val changelog =
      changelogHref
        .takeIf { it.isNotBlank() }
        ?.let {
          "<a href=\"${WebEscaping.htmlEscape(it)}\" class=\"cp-changelog-link\"" +
            " title=\"What changed in this design system, newest first (RSS)\">" +
            "$RSS_ICON Changelog</a> ·\n          "
        } ?: ""
    return """
      <footer class="cp-site-footer">
$noteBlock        <div class="cp-site-footer-links">
          $changelog<a href="https://github.com/$SOURCE_REPO">$GITHUB_ICON GitHub</a> ·$report
          <a href="/version">/version</a>$ver
        </div>
      </footer>
      """
      .trimIndent()
  }

  /**
   * The footer's "report a bug" affordance: the entry point to [ServeBugReport.PATH], the page that
   * collects this server's diagnostics and hands the visitor a prefilled issue on the repo that
   * ships the server.
   *
   * It sits in the footer, on every page, **beside the build number** — a bug in the server is a
   * bug in that build, and the footer is the one piece of chrome every surface has, including the
   * ones with no preview to hang a report off (the front door, `/status`, a 404, a catalog that
   * failed to load). That is the whole difference from the per-preview affordance in
   * [previewLinksHtml], which reports a *preview* to the repo that declares it.
   *
   * A **GET form** rather than a link, for the reason written up on [ServeIssueReport.action]: the
   * two facts the report needs from the browser — which page the visitor is on, and the session
   * token that page carries — are page-derived strings, and writing those into an `href` is a
   * navigation sink. Here the action is a server-rendered literal, the script only ever fills input
   * *values*, and the browser does the encoding on submit.
   *
   * Both inputs start empty and are filled by `serve-chrome.js`. With JS off the form still submits
   * — on a public server that yields a report with no page section (the server's own diagnostics
   * are all still there), and on a token-gated one the report page 404s like every other gated
   * route reached without a token.
   */
  private fun reportBugFormHtml(): String =
    reportBugForm(
      "<button type=\"submit\" class=\"cp-report-bug-link\"" +
        " title=\"Report a bug in the preview server itself — not in a preview\">" +
        "$GITHUB_ICON report a server bug</button>"
    )

  /**
   * The `GET /report-bug` form, wrapped around whichever [submit] control the caller wants — the
   * footer's link-shaped one, or the launcher's two-line choice.
   *
   * Emitted **twice** on an ordinary page, once per entry point, and that is deliberate rather than
   * a duplication to factor out: they are two different affordances (a link in the document flow,
   * and a fixed launcher) that happen to need the same three page-derived values.
   * `fillBugReportLink` fills every copy on the page — it walks `querySelectorAll`, not
   * `querySelector` — precisely so a second entry point costs nothing to add.
   */
  private fun reportBugForm(submit: String): String =
    "<form class=\"cp-report-bug\" method=\"get\" action=\"${ServeBugReport.PATH}\">" +
      "<input type=\"hidden\" name=\"${ServeBugReport.FROM_PARAM}\" value=\"\">" +
      "<input type=\"hidden\" name=\"token\" value=\"\">" +
      // The scheme THIS page is painted in. Captured here because it cannot be recovered on the
      // report page: a catalog that pinned dark chrome and an OS set to light disagree, and the
      // report page has its own answer to that question rather than this page's.
      "<input type=\"hidden\" name=\"scheme\" value=\"\">" +
      submit +
      "</form>"

  /**
   * The **floating report launcher**: a small fixed button, bottom-right of every browser-facing
   * page, opening a panel that names both trackers and offers the screen capture.
   *
   * **Why it floats.** The footer entry is at the bottom of the document, and on the surfaces where
   * something most often looks wrong — a viewer with a tall stage, a catalog grid of two hundred
   * cards, a design page — that is several screens away from the thing being complained about. A
   * fixed launcher makes "something here is broken" a one-click gesture from wherever the visitor
   * noticed it, which is the only moment they still have the page in the state that produced the
   * bug. The footer entry stays: it is in the document flow, it prints, and it is what a page with
   * no JavaScript and no fixed positioning still has.
   *
   * **Why it offers two destinations rather than one.** This server has always had two reports and
   * they go to different repositories — a bug in the *server* to the repo that ships it, a bug in a
   * *preview* to the repo whose Kotlin declares it — but the only place that distinction was
   * written down was a sentence on the report page, which is after the choice has been made. A
   * report filed in the wrong tracker reaches people who cannot act on it, so the panel states the
   * split at the moment of choosing, and names the repo each half files against.
   *
   * The catalog half is server-rendered `hidden` and unhidden by `reportLauncher.ts` on pages that
   * actually carry the per-preview affordance (`#cp-report`), whose form already knows the derived
   * repo — see [reportIssueHtml]. Deriving it here instead would mean plumbing the catalog's repo
   * through every `document` caller for a link that is a scroll-and-focus into markup already on
   * the page.
   *
   * [captureSrc] is the hashed URL of `report-capture.js`, carried as an attribute rather than a
   * `<script>` tag: the capture machinery is several kilobytes that only matter once someone has
   * decided to file something, so it is fetched when the panel first opens and never on a page
   * whose visitor never reports anything.
   */
  private fun reportLauncherHtml(captureSrc: String): String =
    """
    <div class="cp-fab" data-cp-capture-src="${WebEscaping.htmlEscape(captureSrc)}">
      <details class="cp-fab-menu">
        <summary class="cp-fab-btn" title="Report a problem" aria-label="Report a problem"
          >$REPORT_ICON</summary>
        <div class="cp-fab-panel">
          <p class="cp-fab-head">Report a problem</p>
          <p class="cp-fab-sub">Two trackers &mdash; pick whichever owns the thing that is
            wrong.</p>
          <a class="cp-fab-choice cp-fab-catalog" href="#cp-report" hidden>
            <span class="cp-fab-what">Something is wrong with this <strong>preview</strong></span>
            <span class="cp-fab-who">wrong colours, wrong spec, a state that is missing</span>
          </a>
${reportBugForm(
      "<button type=\"submit\" class=\"cp-fab-choice\">" +
        "<span class=\"cp-fab-what\">Something is wrong with the <strong>preview " +
        "server</strong></span>" +
        "<span class=\"cp-fab-who\">the page, a control, a render that failed &mdash; goes to " +
        "<code>${WebEscaping.htmlEscape(ServeBugReport.REPO)}</code></span></button>"
    )
      .prependIndent("          ")}
${captureControlsHtml().prependIndent("          ")}
        </div>
      </details>
    </div>
    """
      .trimIndent()

  /**
   * The capture controls, shared by the launcher panel and [bugReportPage].
   *
   * Server-rendered and `hidden`, unhidden by `report-capture.js` once it has established that this
   * browser can actually grab a frame. That order matters: a control that offers a screenshot and
   * then reports "your browser cannot" is worse than no control, and the capability
   * (`getDisplayMedia`, `ClipboardItem`) is not knowable server-side.
   *
   * Three modes rather than one, because the three things a reporter wants to attach have nothing
   * in common. *Whole view* is the honest default for "the page is wrong". *Region* is for a corner
   * of a wide comparison, where a full-viewport shot buries the defect in a screenful of things
   * that are fine. *Element* is the one that needed a picker: on these pages the interesting thing
   * is very often a single node with an exact boundary — a render, a spec panel, a diagnostics
   * table, one cell of one — and asking someone to drag a box precisely around a table cell is a
   * worse tool than letting them point at it.
   */
  private fun captureControlsHtml(): String =
    """
    <div class="cp-shot" hidden>
      <p class="cp-shot-head">Capture what you can see</p>
      <p class="cp-fab-who">The report embeds the plain render. Anything the browser composes
        &mdash; the spec triptych, a wipe, an overlay, an error &mdash; only reaches the issue as a
        picture you take.</p>
      <div class="cp-shot-modes">
        <button type="button" class="cp-shot-mode" data-cp-capture="view"
          title="The whole browser viewport, as it is now">Whole view</button>
        <button type="button" class="cp-shot-mode" data-cp-capture="region"
          title="Drag a box around the part that is wrong">Region</button>
        <button type="button" class="cp-shot-mode" data-cp-capture="element"
          title="Point at one element — a render, a table, a single cell">Element</button>
      </div>
      <p class="cp-shot-note" role="status"></p>
      <ul class="cp-shot-list"></ul>
    </div>
    """
      .trimIndent()

  /**
   * Shared, intentionally compact navigation for every browser-facing page.
   *
   * The bar is a **fixed three-slot layout** — brand, live status, navigation — and every page
   * emits all three slots whether or not they have content, so nothing shifts position from one
   * page to the next. That matters because two of the slots are conditional: the render-server
   * badge only appears on pages that poll a daemon (and only once the first poll answers), and the
   * GitHub session control only on pages that were served with OAuth configured. Laid out as a
   * plain flex row those absences dragged the nav around — centred on a catalog page, hard right on
   * the home page. Here the brand is pinned left, the status badge centred, and the nav (including
   * [action], the GitHub session control) pinned right, so the same element sits in the same place
   * on every page regardless of which optional pieces are present.
   *
   * The status slot is server-rendered but starts empty and `hidden`; `presenceScript` fills and
   * unhides it when the daemon poll answers, so a page that never polls simply shows nothing there
   * rather than reserving a visible gap.
   *
   * [breadcrumb] rides in the brand slot, immediately after the mark: a page's "where am I / how do
   * I get back" (a [crumbHtml] trail, or a catalog landing's [backButton]) is *navigation*, and the
   * bar is where a visitor already looks for navigation. It used to be the first line of the page
   * BODY, which spent a whole row — plus its margin — restating the header's own job and pushed the
   * thing the page exists to show (the render) further below the fold on every viewer.
   *
   * The nav panel carries only what is *about this server's pages*: **Status**, the GitHub session
   * control ([action]), and **Settings**. Two entries used to sit alongside them and no longer do.
   * A "Catalogs" link, because it went to `/` — exactly where the brand beside it already goes, so
   * the bar offered the same destination twice. And a "GitHub" link to the repo that ships the
   * server, which is a fact *about the software*, not a way around the site: it belongs with the
   * build number and the bug report, so it lives in [siteFooter] instead.
   */
  private fun siteHeader(
    navSuffix: String,
    action: String = "",
    breadcrumb: String = "",
    /**
     * The catalog this page belongs to, named in the bar itself.
     *
     * The header used to say only "compose-preview" on every page of every system, so the one fact
     * a visitor most needs — *which design system am I looking at* — lived solely in the page's own
     * `<h1>` and scrolled away with it. The bar is pinned, so the name belongs here: it stays
     * legible while you are deep in a grid or a viewer, and it distinguishes two tabs open on two
     * catalogs, which the mark alone never could.
     *
     * Empty on the pages that belong to no catalog (the front door, `/status`, a shared document),
     * which keep the bare brand.
     */
    siteName: String = "",
    componentBrowser: Boolean = false,
    showInterfaceMode: Boolean = false,
    showPreviewThemeSetting: Boolean = false,
  ): String {
    val actionHtml = action.takeIf { it.isNotBlank() }?.let { "\n          $it" } ?: ""
    val crumb = breadcrumb.takeIf { it.isNotBlank() }?.let { "\n          $it" } ?: ""
    val name =
      siteName
        .takeIf { it.isNotBlank() }
        ?.let { "\n          <span class=\"cp-site-catalog\">${WebEscaping.htmlEscape(it)}</span>" }
        ?: ""
    val modeToggle =
      """
      <div class="cp-interface-mode" role="group" aria-label="Interface mode">
        <button type="button" data-cp-interface-mode="catalog" aria-pressed="${componentBrowser}">Catalog</button>
        <button type="button" data-cp-interface-mode="dev" aria-pressed="${!componentBrowser}">Dev</button>
      </div>
      """
        .trimIndent()
    val modeToggleHtml =
      if (showInterfaceMode) "\n" + modeToggle.prependIndent("          ") else ""
    if (componentBrowser) {
      return """
        <header class="cp-site-header">
          <div class="cp-site-lead">
            <a class="cp-site-brand" href="/$navSuffix" aria-label="compose-preview home">
              <span class="cp-site-mark" aria-hidden="true">◇</span>
              <span class="cp-site-wordmark">compose-preview</span>
            </a>$name$crumb
          </div>$modeToggleHtml
        </header>
        """
        .trimIndent()
    }
    return """
      <header class="cp-site-header">
        <div class="cp-site-lead">
          <a class="cp-site-brand" href="/$navSuffix" aria-label="compose-preview home">
            <span class="cp-site-mark" aria-hidden="true">◇</span>
            <span class="cp-site-wordmark">compose-preview</span>
          </a>$name$crumb
        </div>
        <nav class="cp-site-nav" aria-label="Primary navigation">$modeToggleHtml
          <details class="cp-site-menu" id="cp-site-menu">
            <summary class="cp-site-menu-btn" title="Menu" aria-label="Menu"
              aria-controls="cp-site-menu-panel"><span aria-hidden="true">⋮</span></summary>
          </details>
          <div class="cp-site-menu-panel" id="cp-site-menu-panel">
            <a class="cp-site-status-link" id="cp-status-link" href="/status$navSuffix">Status<span
              class="cp-daemon-status" id="cp-daemon-status" aria-hidden="true" hidden></span></a>$actionHtml
            ${settingsMenuHtml(showPreviewThemeSetting).prependIndent("            ").trimStart()}
          </div>
        </nav>
      </header>
      """
      .trimIndent()
  }

  /**
   * The header's **Settings** menu: standing per-visitor preferences, as opposed to the controls
   * that describe what is on screen (the Theme chips, Transparent, the override drawers). Two
   * settings live here: **Page theme**, whether the chrome follows the selected preview theme or
   * the visitor's operating system (see `cli/serve-web/src/chrome/pageTheme.ts`), and opt-in
   * **Power-user navigation** (see `keyboard-navigation.js`). They are settings rather than toolbar
   * controls because each is answered once and then applies to every catalog and page.
   *
   * A plain `<details>`, so it opens and the radios record a choice with **no JavaScript at all**;
   * the scripts only reflect stored values and enhance the menu. It sits in the nav so it is in the
   * same place on every page, and last so it never displaces the links.
   */
  private fun settingsMenuHtml(showPreviewThemeSetting: Boolean): String =
    """
    <details class="cp-settings">
      <summary class="cp-settings-btn" title="Settings" aria-label="Settings">
        <span aria-hidden="true">⚙</span><span class="cp-settings-btn-label">Settings</span>
      </summary>
      <div class="cp-settings-panel">${if (!showPreviewThemeSetting) "" else "\n" + """<fieldset class="cp-settings-group">
          <legend class="cp-settings-legend">Page theme</legend>
          <label class="cp-settings-option">
            <input type="radio" name="cp-page-theme" value="match" data-cp-page-theme checked>
            <span>Match the preview theme</span>
          </label>
          <label class="cp-settings-option">
            <input type="radio" name="cp-page-theme" value="system" data-cp-page-theme>
            <span>Follow my system</span>
          </label>
          <p class="cp-settings-hint">Selecting a Light or Dark preview theme paints this page to
            match. Choose Follow my system to keep the page on your operating system's setting.</p>
        </fieldset>""".trimIndent().prependIndent("        ")}
        <fieldset class="cp-settings-group cp-settings-keyboard">
          <legend class="cp-settings-legend">Keyboard</legend>
          <label class="cp-settings-option">
            <input type="checkbox" data-cp-keyboard-navigation>
            <span>Power-user navigation</span>
          </label>
          <p class="cp-settings-hint">Jump between components, variants, modes, and overrides with
            shortcuts and an on-screen command palette.</p>
          <button type="button" class="cp-settings-tour" data-cp-keyboard-tour>
            View keyboard tour
          </button>
        </fieldset>
      </div>
    </details>
    """
      .trimIndent()

  /**
   * A breadcrumb trail for the site header's brand slot: the [parent] page as a link, then — when
   * the page is a leaf rather than a plain "up one level" — the [current] page's name as inert
   * text.
   *
   * Emitted into [siteHeader]'s `breadcrumb` slot rather than as the body's first paragraph. Both
   * [parent] and [current] are escaped here, so callers pass raw text.
   */
  private fun crumbHtml(href: String, parent: String, current: String? = null): String {
    val tail =
      current
        ?.takeIf { it.isNotBlank() }
        ?.let {
          "<span class=\"cp-crumb-sep\" aria-hidden=\"true\">/</span>" +
            "<span class=\"cp-crumb-current\">${WebEscaping.htmlEscape(it)}</span>"
        } ?: ""
    return "<nav class=\"cp-breadcrumb\" aria-label=\"Breadcrumb\">" +
      "<a href=\"${WebEscaping.htmlEscape(href)}\">${WebEscaping.htmlEscape(parent)}</a>$tail</nav>"
  }

  /**
   * The per-preview "source" link shown under the viewer's title — an anchor to this preview's
   * source file on GitHub. [href] is the resolved blob URL (from [ServeUrls.githubBlobUrl]);
   * null/blank ⇒ nothing is rendered (a local session with no delivery provenance, or a preview
   * whose manifest recorded no source path). [path] is the module-relative source path, surfaced as
   * the link's tooltip so hovering names the file. Both the URL and the path are attribute-escaped.
   */
  private fun sourceLinkHtml(href: String?, path: String?): String {
    val url = href?.takeIf { it.isNotBlank() } ?: return ""
    val title =
      path?.takeIf { it.isNotBlank() }?.let { " title=\"${WebEscaping.htmlEscape(it)}\"" } ?: ""
    return "\n      <p class=\"cp-source\">" +
      "<a class=\"cp-source-link\" href=\"${WebEscaping.htmlEscape(url)}\"$title>" +
      "$GITHUB_ICON source</a></p>"
  }

  /**
   * The viewer's "report an issue" affordance: a prefilled GitHub new-issue **form** for the
   * preview on screen, assembled by [ServeIssueReport] (see [ServeIssueReport.action] for why a
   * form rather than a link). [action] is the issue form's URL and [body] is its hidden input —
   * filled for the settings the page was served at, so it works with JS off — while [bodyTemplate]
   * is the same body with the render link left as [ServeIssueReport.RENDER_PLACEHOLDER], which the
   * viewer JS re-substitutes as the overrides change. There is deliberately **no** title: the
   * reporter types it (see [reportIssueHtml]). [repo] names the target so nobody files against a
   * repo they didn't mean to, and [login] — present only when the visitor has a GitHub session on
   * this server — says whose account will author it.
   *
   * [subject] names what the report is *about*, in the affordance's own prose. The default suits
   * the per-preview case this started as; the comparison wall — which shows every component and
   * singles out none — files a page-scoped report and says so instead of claiming a preview the
   * visitor never picked.
   */
  data class ReportIssue(
    val action: String,
    val body: String,
    val bodyTemplate: String,
    val repo: String,
    val login: String? = null,
    val subject: String = "this preview",
  )

  /**
   * The Figma node a preview is specified by, as the viewer offers it: a ready-to-open deep [url]
   * (assembled by [ServeFigmaSpec] from a literal origin plus a validated file key and node id, so
   * a hostile catalog cannot put an arbitrary href on the page) and the reference's [label], which
   * names *which* spec the link opens when a producer publishes several.
   */
  data class FigmaSpec(val url: String, val label: String? = null)

  /**
   * A published design page as the catalog's **navigation** needs it: what to call it, and the id
   * its URL carries. Deliberately not the whole [DesignPage] — the landing lists these, it does not
   * draw them, and a page's node list is megabytes of manifest the tree has no use for.
   */
  data class PageLink(
    val id: String,
    val name: String,
    /** The page's own major sections, in the design file's order. Empty ⇒ a leaf row. */
    val sections: List<PageSection> = emptyList(),
  )

  /**
   * One **major section** of a design page — a Figma `COMPONENT_SET`, which on a specimen sheet is
   * what a reader means by a heading: the `Shape` page's grid of shapes, the `Buttons` page's row
   * of button families.
   *
   * Grouping nodes only, not every component. A definition sheet carries hundreds of nodes and
   * listing them in a sidebar would rebuild the wall of rows this navigation exists to avoid; the
   * sets are the handful of things the page is actually divided into.
   */
  data class PageSection(val nodeId: String, val name: String)

  /**
   * The `id` a page's node hotspot carries, so a link can land on it.
   *
   * A design-tool node id (`1:23`) is legal in an HTML `id` but not in a CSS selector or a URL
   * fragment without escaping, and it is free text from a third-party manifest either way — so the
   * anchor is *built* from the id rather than being it. Every character outside the safe set
   * becomes `-`, which can collide in principle; the collision is harmless here, because the worst
   * case is a fragment landing on the sibling above the one you asked for, and the alternative
   * (percent-encoding into a fragment) is unreadable in a URL a reader is meant to share.
   */
  fun nodeAnchorId(nodeId: String): String =
    "cp-node-" +
      nodeId
        .map { if (it.isLetterOrDigit() || it == '.' || it == '_') it else '-' }
        .joinToString("")

  /**
   * The row under the viewer's title holding the per-preview provenance links: "source" (where the
   * preview is declared), "report an issue" (a prefilled bug against the repo that owns it), and
   * "figma spec" (the node this preview is specified by, when the catalog names one). They share
   * one flex row so they read as one line of provenance actions; any can be absent, and when all
   * are the row itself is omitted rather than left as empty vertical space.
   */
  private fun previewLinksHtml(
    sourceHref: String?,
    sourcePath: String?,
    report: ReportIssue?,
    figmaSpec: FigmaSpec?,
    playgroundHref: String?,
    executableBundleHref: String?,
  ): String {
    val links =
      sourceLinkHtml(sourceHref, sourcePath) +
        playgroundLinkHtml(playgroundHref) +
        executableBundleLinkHtml(executableBundleHref) +
        reportIssueHtml(report) +
        figmaSpecHtml(figmaSpec)
    if (links.isBlank()) return ""
    return "\n      <div class=\"cp-preview-links\">$links\n      </div>"
  }

  private fun executableBundleLinkHtml(href: String?): String {
    val url = href?.takeIf { it.isNotBlank() } ?: return ""
    return "\n        <a href=\"${WebEscaping.htmlEscape(url)}\" download>download executable bundle</a>"
  }

  /**
   * "open in playground" — the same provenance row's action twin: where `source` sends you to read
   * this preview's Kotlin on GitHub, this opens it *in the editor* against the catalog it came
   * from, ready to press Run on.
   *
   * Deliberately sits in the provenance row rather than beside the render: it is a developer
   * affordance about where this preview comes from, not a control over what is on screen. Null —
   * the common case on a host with no playground lane, or a preview whose source path was never
   * recorded — renders nothing at all rather than a dead entry.
   */
  private fun playgroundLinkHtml(href: String?): String {
    val url = href?.takeIf { it.isNotBlank() } ?: return ""
    return "\n      <p class=\"cp-source\">" +
      "<a class=\"cp-source-link\" href=\"${WebEscaping.htmlEscape(url)}\" " +
      "title=\"Open this preview's source in the playground\">▶ playground</a></p>"
  }

  /**
   * Renders [spec] as a link opening the Figma node this preview is specified by. Null — the common
   * case, since only a catalog that publishes Figma-backed design references names one — renders
   * nothing at all rather than a dead or guessed link.
   */
  private fun figmaSpecHtml(spec: FigmaSpec?): String {
    val s = spec ?: return ""
    val label =
      s.label?.takeIf { it.isNotBlank() }?.let { " — ${WebEscaping.htmlEscape(it)}" } ?: ""
    val tip = "Open the Figma node this preview is specified by$label"
    return "\n      <p class=\"cp-figma\">" +
      "<a class=\"cp-figma-link\" href=\"${WebEscaping.htmlEscape(s.url)}\"" +
      " target=\"_blank\" rel=\"noopener noreferrer\" title=\"${WebEscaping.htmlEscape(tip)}\">" +
      "$FIGMA_ICON figma spec</a></p>"
  }

  /**
   * Renders [report] as the per-preview "report an issue" affordance beside the "source" link: a
   * disclosure styled as a link, opening a small panel whose one visible control is a **required**
   * Summary the reporter writes themselves.
   *
   * **Why the reporter types the title.** This used to be one click straight to a prefilled issue
   * whose title the server wrote (`Preview issue: <preview> (<system>)`) — which named the preview
   * and said nothing about what was wrong, so a repo collected a queue of issues distinguishable
   * only by opening them. The preview's identity was never the interesting part and is not lost: it
   * is the `| Preview |` row of the body's "Which preview" table, which every report still carries.
   * This is the same trade `/report-bug` already makes — see [bugReportPage]'s Summary input — so
   * the two reporting affordances now ask for the same thing.
   *
   * **Why a script-free `<details>`.** The form has to keep working with JS off, which is also what
   * enforces the title: `required` is the browser's own check, so a reporter cannot submit an
   * untitled report whether or not the page's script ran. Nothing here is scripted — the disclosure
   * is the element's own behaviour, the `action` stays a server-rendered literal, and the only
   * thing the viewer JS touches is the hidden `body` input it already refreshed.
   *
   * Null (a surface with no repo to file against) renders nothing.
   */
  private fun reportIssueHtml(report: ReportIssue?): String {
    val r = report ?: return ""
    val who =
      r.login?.takeIf { it.isNotBlank() }?.let { " as @${WebEscaping.htmlEscape(it)}" } ?: ""
    val repo = WebEscaping.htmlEscape(r.repo)
    val subject = WebEscaping.htmlEscape(r.subject)
    val tip = "Something wrong with $subject — files against $repo$who"
    // `data-cp-repo` is read by the floating launcher, which offers this affordance as its catalog
    // half and has to name the repo in the offer. Taken from an attribute rather than scraped out
    // of the note's prose below, so rewording the note cannot silently change where the launcher
    // says a report goes. `data-cp-subject` is there for the same reason and answers the other half
    // of the offer — what the report is about — so the wall's launcher says "these comparisons"
    // rather than claiming a preview on a page that shows every one of them.
    return "\n      <details class=\"cp-report\" id=\"cp-report\" data-cp-repo=\"$repo\"" +
      " data-cp-subject=\"$subject\">" +
      "<summary class=\"cp-report-link\" title=\"$tip\">" +
      "$GITHUB_ICON report a catalog issue</summary>" +
      "\n        <div class=\"cp-report-panel\">" +
      "<form class=\"cp-report-form\" method=\"get\" target=\"_blank\"" +
      " rel=\"noopener\" action=\"${WebEscaping.htmlEscape(r.action)}\">" +
      "<label class=\"cp-report-summary\">Summary" +
      "<input class=\"cp-report-summary-input\" type=\"text\" name=\"title\" required" +
      " autocomplete=\"off\" placeholder=\"Briefly describe what is wrong\"></label>" +
      "<input type=\"hidden\" name=\"body\" id=\"cp-report-body\"" +
      " value=\"${WebEscaping.htmlEscape(r.body)}\"" +
      " data-report-template=\"${WebEscaping.htmlEscape(r.bodyTemplate)}\">" +
      "<button type=\"submit\" class=\"cp-report-submit\">" +
      "$GITHUB_ICON Open a prefilled issue</button>" +
      "<span class=\"cp-report-note\">Files against <code>$repo</code>$who — the project whose " +
      "code declares $subject, <em>not</em> the preview server. The rest of the report — " +
      "what you were looking at, which build, the links — is filled in for you on GitHub.</span>" +
      "</form></div></details>"
  }

  /** Render catalog-published GitHub issues. Every href has already been rebuilt by the store. */
  private fun parityIssueRowsHtml(issues: List<ParityIssue>): String {
    if (issues.isEmpty()) return ""
    val rows =
      issues.joinToString("\n") { issue ->
        val state = if (issue.state == "closed") " closed" else ""
        val classification =
          listOfNotNull(issue.area?.let { "area:$it" }, issue.parity?.let { "parity:$it" })
            .joinToString(" · ")
        val meta = if (classification.isEmpty()) issue.state else "${issue.state} · $classification"
        "<li class=\"cp-parity-issue$state\"><a href=\"${WebEscaping.htmlEscape(issue.url)}\" " +
          "rel=\"noopener\">#${issue.number} ${WebEscaping.htmlEscape(issue.title)}</a>" +
          "<span>${WebEscaping.htmlEscape(meta)}</span></li>"
      }
    return "<aside class=\"cp-parity-issues\"><strong>Issues</strong><ul>$rows</ul></aside>"
  }

  /** Compact, non-link form safe to place inside a card whose whole body is already an anchor. */
  private fun parityIssueBadgeHtml(issues: List<ParityIssue>): String {
    if (issues.isEmpty()) return ""
    val open = issues.count { it.state == "open" }
    val closed = issues.size - open
    val label = buildList {
      if (open > 0) add("$open open")
      if (closed > 0) add("$closed closed")
    }
      .joinToString(" · ")
    val title = issues.joinToString("; ") { "#${it.number} ${it.title}" }
    return "<span class=\"cp-issue-badge\" title=\"${WebEscaping.htmlEscape(title)}\">${WebEscaping.htmlEscape(label)} issue${if (issues.size == 1) "" else "s"}</span>"
  }

  /**
   * The parity **verdict** panel: what a parity run concluded about this comparison, grouped the
   * way the run itself reports — accessibility and i18n first, then tokens, then layout, then the
   * pixels (docs/PRINCIPLES.md's order in `yschimke/design-parity`, and the order a reader can act
   * on).
   *
   * Server-rendered, deliberately. Everything else this page draws over its panels is built in the
   * browser from a payload, because it is geometry and geometry is useless without script. A
   * finding is a SENTENCE — "this label truncates in German", "padding is 24 where the spec says
   * 12" — and a sentence that appears only after a bundle has downloaded and upgraded cannot be
   * quoted into a bug, found with the browser's own search, or read at all by anything that does
   * not run script. So the prose is HTML and only the anchors travel as data.
   *
   * The anchor payload rides INSIDE the section, immediately after the rows it keys. Two reasons,
   * and the second is load-bearing: the ids are minted here and the payload is keyed by them, so
   * keeping them adjacent is the only arrangement in which they cannot be built out of step — and
   * `<cp-reference-compare>` installs the moment the parser reaches ITS tag, which is further down
   * the page. A payload emitted after that tag, as the acceptance context is, does not exist yet
   * when the element looks for it, and the highlights silently never wire up.
   */
  private fun parityVerdictHtml(sets: List<ParityFindingSet>): String {
    val findings = sets.flatMap { it.findings }
    if (findings.isEmpty()) return ""
    // Worst declared status wins. A run that declared none at all is read off its own findings,
    // which is the same rule the producing engine applies and keeps a hand-written manifest honest.
    val declared = sets.mapNotNull { it.status }
    val status =
      when {
        "fail" in declared -> "fail"
        "warn" in declared -> "warn"
        declared.isNotEmpty() -> "pass"
        findings.any { it.severity == ParityFindingSeverity.ERROR } -> "fail"
        findings.any { it.severity == ParityFindingSeverity.WARN } -> "warn"
        else -> "pass"
      }
    val tally =
      listOf(
          ParityFindingSeverity.ERROR to "error",
          ParityFindingSeverity.WARN to "warning",
          ParityFindingSeverity.INFO to "note",
        )
        .mapNotNull { (severity, noun) ->
          findings
            .count { it.severity == severity }
            .takeIf { it > 0 }
            ?.let { "$it $noun${if (it == 1) "" else "s"}" }
        }
        .joinToString(" · ")
    val anchors = LinkedHashMap<String, List<ParityAnchor>>()
    val groups =
      ParityFindingGroup.entries.mapNotNull { group ->
        val rows = findings.filter { ParityFindingGroup.of(it.kind) == group }
        if (rows.isEmpty()) return@mapNotNull null
        val items = rows.mapIndexed { index, finding ->
          val id = "${group.id}-$index"
          if (finding.anchors.isNotEmpty()) anchors[id] = finding.anchors
          parityFindingRowHtml(id, finding)
        }
        "    <section class=\"cp-parity-group\" data-cp-parity-group=\"${group.id}\">\n" +
          "      <h3>${WebEscaping.htmlEscape(group.title)}" +
          "<span class=\"cp-parity-count\">${rows.size}</span></h3>\n" +
          "      <ul class=\"cp-parity-list\">\n        " +
          items.joinToString("\n        ") +
          "\n      </ul>\n    </section>"
      }
    // Only claimed when something on the page can actually respond to a pointer. A catalog whose
    // producer publishes findings with no geometry gets the same panel without the instruction to
    // hover, rather than an invitation that does nothing.
    val hint =
      if (anchors.isEmpty()) ""
      else
        "\n  <p class=\"cp-parity-hint\">Hover a finding to light the region it describes on " +
          "both panels; click to keep it lit.</p>"
    val report =
      sets
        .firstNotNullOfOrNull { it.reportUrl }
        ?.let {
          "<a class=\"cp-parity-report\" href=\"${WebEscaping.htmlEscape(it)}\" " +
            "rel=\"noopener\">Full parity report</a>"
        }
        .orEmpty()
    // Carries its own leading newline and is written without `trimIndent()`, like every other
    // interpolated block on this page: trimming runs AFTER interpolation, so a nested block's own
    // indentation would drag the page's with it, and an empty one on its own template line would
    // leave a blank line on every catalog that publishes no verdict.
    val section =
      "\n<section class=\"cp-parity-verdict\" id=\"cp-parity-verdict\"" +
        " aria-labelledby=\"cp-parity-verdict-title\">\n" +
        "  <div class=\"cp-parity-verdict-head\">\n" +
        "    <h2 id=\"cp-parity-verdict-title\">Design parity</h2>\n" +
        "    <span class=\"cp-parity-status cp-parity-status--$status\">" +
        status.replaceFirstChar { it.uppercase() } +
        "</span>\n" +
        "    <span class=\"cp-parity-tally\">${WebEscaping.htmlEscape(tally)}</span>" +
        report +
        "\n  </div>" +
        hint +
        "\n  <div class=\"cp-parity-groups\">\n" +
        groups.joinToString("\n") +
        "\n  </div>"
    val payload =
      if (anchors.isEmpty()) ""
      else
        "\n  <script type=\"application/json\" id=\"cp-parity-anchors\">" +
          encodeParityAnchorPayload(ParityAnchorPayload(anchors)) +
          "</script>"
    return section + payload + "\n</section>"
  }

  /**
   * One finding row.
   *
   * A row carries its anchor id when it HAS somewhere to point, and nothing else — no `tabindex`,
   * no `role`, no `aria-pressed`. Those are added by `<cp-reference-compare>` once it has parsed
   * the payload and actually built the boxes, because only then is the row a control.
   *
   * The server cannot know that. Script may be disabled, blocked by a policy, or simply fail to
   * load, and on any of those the page still renders — that is the point of putting the prose in
   * the document. Announcing every anchored row as a pressed-state button up front would hand a
   * screen-reader or keyboard user a tab stop that does nothing when they reach it, on the one page
   * whose no-script behaviour was the reason to render it server-side at all. The same is true of a
   * payload keyed to a row the panels cannot place: the client drops the id, and a row that never
   * became a control never looked like one.
   */
  private fun parityFindingRowHtml(id: String, finding: ParityFinding): String {
    val expected = finding.detail["expected"]
    val actual = finding.detail["actual"]
    val token = finding.detail["token"] ?: finding.detail["property"]
    val delta =
      if (expected == null && actual == null) ""
      else
        "<span class=\"cp-parity-delta\">" +
          (token?.let { "<code>${WebEscaping.htmlEscape(it)}</code>" } ?: "") +
          (expected?.let {
            "<span class=\"cp-parity-expected\">expected ${WebEscaping.htmlEscape(it)}</span>"
          } ?: "") +
          (actual?.let {
            "<span class=\"cp-parity-actual\">actual ${WebEscaping.htmlEscape(it)}</span>"
          } ?: "") +
          "</span>"
    // Every remaining key, as the row's title. A producer's structured payload is transported in
    // full rather than narrowed to the two keys this page formats, so a check that reports a
    // contrast ratio or a measured touch target is still readable here.
    val rest =
      finding.detail
        .filterKeys { it != "expected" && it != "actual" && it != "token" && it != "property" }
        .entries
        .joinToString(" · ") { (key, value) -> "$key $value" }
    val title = if (rest.isEmpty()) "" else " title=\"${WebEscaping.htmlEscape(rest)}\""
    val anchored = finding.anchors.isNotEmpty()
    val interactive = if (!anchored) "" else " data-cp-parity-finding=\"$id\""
    val where =
      if (!anchored) ""
      else
        "<span class=\"cp-parity-where\">${finding.anchors.size} region" +
          "${if (finding.anchors.size == 1) "" else "s"}</span>"
    return "<li class=\"cp-parity-finding cp-parity-finding--${finding.severity}" +
      " cp-parity-finding--kind-${finding.kind}\"$interactive$title>" +
      "<span class=\"cp-parity-sev\">${WebEscaping.htmlEscape(finding.severity)}</span>" +
      "<span class=\"cp-parity-body\"><span class=\"cp-parity-msg\">" +
      WebEscaping.htmlEscape(finding.message) +
      "</span>$delta</span>$where</li>"
  }

  private fun issuesForPreview(
    issues: List<ParityIssue>,
    preview: ServePreview,
  ): List<ParityIssue> = issues.filter { issue ->
    preview.id in issue.previewIds ||
      (preview.componentId != null && issue.component == preview.componentId)
  }

  /**
   * The issues one **comparison row** carries — any naming one of its preview [ids], or its
   * component.
   *
   * Matched over the row's whole id set rather than over the one variant it is serving, because a
   * row IS the variants: an issue filed from the dark page is about the same two pictures the light
   * lane is showing, and joining on the served variant alone would hide it from the reader who
   * switched theme. The folded-out siblings ride along for the same reason they ride along in the
   * filter — they have no row of their own to carry their reports.
   *
   * Open before closed, then newest first: the column is read for "does someone already know?", and
   * a closed report answers that more weakly than an open one.
   */
  private fun issuesForRow(
    issues: List<ParityIssue>,
    ids: List<String>,
    componentId: String?,
  ): List<ParityIssue> {
    if (issues.isEmpty()) return emptyList()
    val wanted = ids.toSet()
    return issues
      .filter { issue ->
        issue.previewIds.any { it in wanted } ||
          (componentId != null && issue.component == componentId)
      }
      .sortedWith(compareBy({ it.state != "open" }, { -it.number }))
  }

  /**
   * The wall's **Bugs** cell: what is already filed against this row, and one link to file more.
   *
   * The numbers are links out to GitHub and nothing else — a title in the column would push the
   * pictures off a wall that already carries three of them, and it is on the tooltip where a reader
   * who wants it can get it without the table reflowing.
   *
   * "+ file" is always offered, including on a row with nothing filed, because that row is the
   * point: a bad score with no issue against it is the one a reader is scanning for. [detailHref]
   * is the focused comparison for the served pair — the report that names the exact preview AND
   * reference — and [fallbackHref] the viewer's own report, for a row with no reference to focus.
   */
  private fun compareBugsCellHtml(
    issues: List<ParityIssue>,
    detailHref: String?,
    fallbackHref: String,
  ): String {
    val links =
      issues.joinToString("") { issue ->
        val closed = if (issue.state == "closed") " cp-compare-bug--closed" else ""
        val tip = "${issue.state} · #${issue.number} ${issue.title}"
        "<a class=\"cp-compare-bug$closed\" href=\"${WebEscaping.htmlEscape(issue.url)}\" " +
          "rel=\"noopener\" title=\"${WebEscaping.htmlEscape(tip)}\">#${issue.number}</a>"
      }
    val file =
      "<a class=\"cp-compare-bug-new\" " +
        "href=\"${WebEscaping.htmlEscape(detailHref ?: fallbackHref)}\" " +
        "data-bug-fallback=\"${WebEscaping.htmlEscape(fallbackHref)}\" " +
        "title=\"Report what is wrong with this comparison\">+&#8202;file</a>"
    return "\n            <td class=\"cp-compare-bugs\">$links$file</td>"
  }

  /**
   * Provenance of a served design-system catalog: the trusted GitHub [repo]/[branch] it was fetched
   * from, when it was [generatedAt] (ISO-8601), and the [toolVersion]
   * (compose-ai-tools) + [designParityVersion] that produced it. Threaded from [ServeCatalogStore]
   * (which knows the repo/branch) + the catalog's own `catalog.json` metadata. Null fields are
   * simply omitted.
   */
  data class CatalogProvenance(
    val repo: String,
    val branch: String,
    /**
     * The delivery-branch commit this catalog was fetched at, when the store could resolve it — the
     * revision every permalink on this catalog's pages pins to ([ServeCatalogRevision]). Null for
     * an uploaded bundle, and for a catalog whose branch advertisement couldn't be read; the pages
     * then simply offer no permalink.
     */
    val commit: String? = null,
    val generatedAt: String? = null,
    val toolVersion: String? = null,
    val designParityVersion: String? = null,
  )

  /**
   * The **source** a catalog was built from — `catalog.json`'s `source = {repo, ref, module}` — as
   * opposed to the delivery [CatalogProvenance] (the `design-artifacts/<system>` branch that
   * carries the generated assets). This is the repo/ref/module of the actual Kotlin, so it's what a
   * per-preview "source" link must point at: `blob/<ref>/<module>/<sourceFile>`. Null for a plain
   * uploaded bundle or a catalog that declared no source.
   */
  data class CatalogSource(val repo: String, val ref: String, val module: String)

  /**
   * One thing the spec lane can put on the stage beside the render.
   *
   * The lane's four views — Spec, Diff, Triptych, Slider — are instruments over *a pair of images*,
   * and they do not care where the second image came from. So a second comparison is a second
   * SOURCE for the existing lane rather than a mode of its own: the views, the single normalisation
   * pass that keeps them in one pixel space, and the URL state all carry over untouched
   * (issue #4621).
   *
   * Two kinds exist today and they answer different questions, which is why the lane names the one
   * it is showing rather than implying they are interchangeable:
   *
   * * `kit` — the imported design reference. A SPECIFICATION: a static import, fixed at publish
   *   time. "Does this match what the design says?"
   * * `parallel` — the counterpart component in the `compareWith` sibling, served from this same
   *   origin. ANOTHER IMPLEMENTATION'S RENDER, not a spec. "Do our two renditions agree?"
   *
   * [provenance] is the whole reason the second kind is safe to offer. The sibling's render was
   * produced under its own theme, knobs and overrides — not the ones that produced the render it is
   * being compared against — so the lane says so. An implied symmetry here is the detail most
   * likely to make the feature quietly misleading.
   *
   * @property id the value the picker carries (`kit` / `parallel`); also the URL state's own token.
   * @property label what the picker button reads, e.g. `Figma` or `wear-m3-catalog`.
   * @property rasterUrl same-origin URL of the image to compare against; the viewer refuses any
   *   other origin ([specRasterSrc]).
   * @property provenance one line naming where this image came from, shown when the source is
   *   selected. Empty for a source that needs no caveat.
   */
  data class SpecSource(
    val id: String,
    val label: String,
    val rasterUrl: String,
    val provenance: String = "",
  )

  /**
   * "2026-07-17T12:34:56.789Z" → "2026-07-17 12:34 UTC"; anything unparseable is shown verbatim.
   */
  private fun prettyDate(iso: String): String {
    val m = Regex("""^(\d{4}-\d{2}-\d{2})T(\d{2}:\d{2})""").find(iso) ?: return iso
    return "${m.groupValues[1]} ${m.groupValues[2]} UTC"
  }

  /**
   * The catalog-provenance strip shown on a catalog landing: a link to the trusted delivery
   * [branch][CatalogProvenance.branch] on GitHub, the generation date, the compose-ai-tools +
   * design-parity versions it was rendered with, and a link to re-run the `design-artifacts`
   * workflow that regenerates it. Empty [prov] fields drop their item.
   */
  private fun provenanceSection(prov: CatalogProvenance, refreshUrl: String?): String {
    val repo = WebEscaping.htmlEscape(prov.repo)
    val branch = WebEscaping.htmlEscape(prov.branch)
    // Branch names carry a `/` (`design-artifacts/compose-m3`); it's a valid path in a tree URL.
    val branchUrl = "https://github.com/${prov.repo}/tree/${prov.branch}"
    val actionUrl = "https://github.com/${prov.repo}/actions/workflows/design-artifacts.yml"
    val items = buildList {
      add(
        "<span class=\"cp-prov-item\"><span class=\"cp-prov-key\">catalog</span> " +
          "<a href=\"$branchUrl\">$GITHUB_ICON $repo@$branch</a></span>"
      )
      // Which publish is on screen. The branch link above names a moving target by construction, so
      // without this the strip could say where a catalog came from but not *when* — and a visitor
      // reading a rendering they want to cite had nothing to cite it by.
      ServeCatalogRevision.treeUrl(prov.repo, prov.commit)?.let { url ->
        add(
          "<span class=\"cp-prov-item\"><span class=\"cp-prov-key\">revision</span> " +
            "<a href=\"${WebEscaping.htmlEscape(url)}\"><code>" +
            "${WebEscaping.htmlEscape(ServeCatalogRevision.short(prov.commit!!))}</code></a></span>"
        )
      }
      prov.generatedAt
        ?.takeIf { it.isNotBlank() }
        ?.let {
          add(
            "<span class=\"cp-prov-item\"><span class=\"cp-prov-key\">generated</span> " +
              "${WebEscaping.htmlEscape(prettyDate(it))}</span>"
          )
        }
      val tool = prov.toolVersion?.takeIf { it.isNotBlank() }
      val dp = prov.designParityVersion?.takeIf { it.isNotBlank() }
      if (tool != null || dp != null) {
        val parts = buildList {
          if (tool != null) add("compose-ai-tools <code>${WebEscaping.htmlEscape(tool)}</code>")
          if (dp != null) add("design-parity <code>${WebEscaping.htmlEscape(dp)}</code>")
        }
        add(
          "<span class=\"cp-prov-item\"><span class=\"cp-prov-key\">rendered by</span> " +
            "${parts.joinToString(" · ")}</span>"
        )
      }
      add("<span class=\"cp-prov-item\"><a href=\"$actionUrl\">regenerate ↗</a></span>")
      refreshUrl?.let {
        add(
          "<span class=\"cp-prov-item\"><button type=\"button\" class=\"cp-prov-refresh\" " +
            "data-refresh-url=\"${WebEscaping.htmlEscape(it)}\">refresh</button>" +
            "<span class=\"cp-prov-refresh-status\" role=\"status\" aria-live=\"polite\"></span></span>"
        )
      }
    }
    return """
      <details class="cp-prov cp-disclosure" open>
        <summary>
          <span class="cp-prov-title">Catalog details</span>
          <span class="cp-disclosure-hint">Source, generation time and tooling</span>
        </summary>
        <div class="cp-prov-body" aria-label="Catalog provenance">
          ${items.joinToString("\n          ")}
        </div>
      </details>
      ${if (refreshUrl == null) "" else provenanceRefreshScript()}
      """
      .trimIndent()
  }

  private fun provenanceRefreshScript(): String =
    """
    <script>
    (() => {
      const button = document.querySelector('.cp-prov-refresh');
      if (!button) return;
      const status = document.querySelector('.cp-prov-refresh-status');
      button.addEventListener('click', async () => {
        button.disabled = true;
        status.textContent = 'checking…';
        try {
          const response = await fetch(button.dataset.refreshUrl, { method: 'POST' });
          const result = await response.json();
          if (result.status === 'updated') {
            status.textContent = 'updated';
            window.location.reload();
            return;
          }
          status.textContent = result.status === 'current' ? 'up to date' :
            result.status === 'checking' ? 'check in progress' : 'check failed';
        } catch (_) {
          status.textContent = 'check failed';
        }
        button.disabled = false;
      });
    })();
    </script>
    """
      .trimIndent()

  /**
   * The theme axis (`light`/`dark`) baked into a flattened catalog id, or null if it carries none.
   */
  private fun cardTheme(id: String): String? =
    id.split("__").drop(1).lastOrNull { it == "light" || it == "dark" }

  /**
   * A dark-first design system draws its components for a dark surface (Wear OS is
   * black-watch-face-first), so a preview with no explicit light/dark token should sit on the DARK
   * stage — otherwise a light-on-transparent Wear render lands on the default white stage and its
   * light text is unreadable. Keyed off the served system name — the `/<system>` path mount
   * ([basePath]) or, for the legacy `?session=` form, the session id — and resolved through the
   * single per-system policy in [SystemDisplay] rather than an inline name check here.
   */
  private fun isDarkFirstSystem(
    basePath: String,
    sessionId: String?,
    declaredSurface: String? = null,
  ): Boolean {
    val system = basePath.trim('/').ifBlank { sessionId ?: "" }
    return SystemDisplay.resolveDarkFirst(system, declaredSurface)
  }

  /**
   * The stage / thumbnail **background** theme for a preview: its explicit `__light` / `__dark`
   * variant token when it has one, else the DARK default for a dark-first system
   * ([isDarkFirstSystem]), else none (the default light stage). Distinct from [cardTheme] — which
   * drives the light/dark *filter axis* and must stay explicit-only, so a dark-first catalog with
   * no light variants doesn't sprout a dead Light/Dark toggle.
   */
  private fun bgTheme(id: String, darkFirst: Boolean): String? =
    cardTheme(id) ?: if (darkFirst) "dark" else null

  /**
   * An `#AARRGGBB` data-product colour as a CSS one.
   *
   * CSS's 8-digit hex puts alpha **last** (`#RRGGBBAA`), so emitting the wire form unchanged would
   * read `#FF1C1B1F` as opaque-ish `#FF1C1B` — a red stage instead of a near-black one, which is
   * the kind of wrong that looks deliberate. An opaque colour drops the alpha entirely so the
   * common case stays the familiar six digits.
   */
  private fun String.asCssColor(): String {
    val hex = removePrefix("#")
    if (hex.length != 8) return this
    val alpha = hex.take(2)
    val rgb = hex.drop(2)
    return if (alpha.equals("FF", ignoreCase = true)) "#$rgb" else "#$rgb$alpha"
  }

  /**
   * The ground this preview should be shown on, resolved through the shared [PreviewBackdrop]
   * chain: what the preview states about itself first, the catalog's declared stage after.
   *
   * This is the serve host's end of the "per-preview with catalog defaults" contract. The two
   * halves are genuinely different claims and must not be collapsed: a `showBackground = false`
   * sticker says nothing about its ground on purpose (so it drops onto any Figma canvas) and
   * *wants* the catalog stage, while an explicit `@Preview(backgroundColor = 0xFFFFFFFF)` specimen
   * in the same dark-first catalog is stating a white ground and must keep it. Deriving both from
   * the catalog alone, which is what every compare surface used to do, gets the second one wrong.
   *
   * The [Backdrop][PreviewBackdrop.Backdrop] carries its own `source`, so a page can show *why* it
   * chose a stage instead of leaving a reader to guess — see the reference-compare page, which
   * names it in the panel's title text.
   */
  internal fun backdropFor(
    preview: ServePreview,
    darkFirst: Boolean,
    /**
     * The render lane's `uiMode` override (`"light"`/`"dark"`), when this page is showing one.
     *
     * An override is the *effective* render state, so it outranks the preview's discovery-time
     * `uiMode` for both rungs that read the night axis. Without it a `?uiMode=dark` comparison put
     * the overridden — genuinely dark — Actual panel on the preview's original light stage, and for
     * a `showBackground = true` preview it named white while the renderer painted the dark sheet.
     */
    uiModeOverride: String? = null,
  ): PreviewBackdrop.Backdrop {
    val overriddenSurface = PreviewBackdrop.CatalogSurface.parse(uiModeOverride)
    return PreviewBackdrop.withCatalogDefault(
      PreviewBackdrop.resolve(
        showBackground = preview.showBackground,
        backgroundColor = preview.backgroundColor,
        night =
          overriddenSurface?.let { it == PreviewBackdrop.CatalogSurface.DARK }
            ?: PreviewBackground.isNight(preview.uiMode),
        // The variant this render IS, which the catalog's stage cannot speak for: a *dark* variant
        // inside a light-first catalog needs a dark ground exactly as much as a dark-first
        // catalog's does. Omitting it opened a dark row's focused comparison on a light stage while
        // the wall and the viewer both showed it dark.
        variantSurface = overriddenSurface ?: variantSurfaceOf(preview),
      ),
      if (darkFirst) PreviewBackdrop.CatalogSurface.DARK else PreviewBackdrop.CatalogSurface.LIGHT,
    )
  }

  /**
   * The device-frame clip for a preview, as a CSS `clip-path`, or null when the whole capture is
   * screen.
   *
   * This is the shape half of the same "what is behind this preview?" question [backdropFor]
   * answers with a colour, and the two are only correct together. A round Wear capture is a circle
   * in a square PNG; painting its backdrop across the whole square draws the watch as a rectangle,
   * and because a Wear catalog declares black backgrounds against black screens, the device edge
   * did not merely look wrong — on this repo's own `PageIndicatorScaffoldTemplate` renders the
   * stage was pixel-identical to the screen and the boundary was invisible.
   *
   * Sized to the DEVICE box rather than to the panel: the compare panels size their `<img>` with
   * `width: auto; height: auto`, so the image element's box carries the render's own aspect and a
   * circle stated against the device is exactly the circle in the pixels. Clipping the panel
   * instead would clip the panel's rectangle, which is a different shape in a different place.
   */
  internal fun stageClipFor(
    preview: ServePreview,
    /**
     * The render lane's overrides, when this page is showing one. The clip has to describe the
     * frame that was actually RENDERED, not the one the preview was discovered with — the Actual
     * panel takes these through `assetQuery`, so a comparison opened at `?device=id:wearos_square`
     * shows a square render and a circle stated from the annotation would crop live screen off it.
     * The inverse is just as wrong: overriding a phone preview onto a watch leaves a round render
     * on a square stage. Same reason [backdropFor] takes `uiModeOverride`.
     */
    overrides: Map<String, String> = emptyMap(),
  ): String? {
    val frame = effectiveDeviceFrame(preview, overrides) ?: return null
    val shape = PreviewClip.resolve(frame.isRound, frame.widthDp, frame.heightDp) ?: return null
    return PreviewClip.cssClipPath(
      shape,
      frame.widthDp ?: return null,
      frame.heightDp ?: return null,
    )
  }

  /**
   * The device frame this comparison actually rendered at, or null when it cannot be stated.
   *
   * An explicit `device=` override replaces the frame outright and is resolved from the device
   * catalog exactly as discovery would have — including its shape, so switching a round preview to
   * one of the Wear picker's square choices drops the clip rather than keeping a stale circle.
   *
   * A SIZE override suppresses the clip instead of adjusting it. `widthPx`/`heightPx` are pixels
   * against a density this page does not carry, and `orientation` re-derives the frame through
   * rules that live in the resolver; a clip guessed from any of them would be a circle in the wrong
   * place, which is worse than the square stage this feature replaced — that at least never hid
   * real pixels. Answering null puts such a render back on the un-clipped stage, honestly.
   */
  private fun effectiveDeviceFrame(
    preview: ServePreview,
    overrides: Map<String, String>,
  ): ServeDeviceFrame? {
    if (SIZE_OVERRIDE_KEYS.any { !overrides[it].isNullOrBlank() }) return null
    val device = overrides["device"]?.takeIf { it.isNotBlank() } ?: return preview.deviceFrame
    return ServeDeviceFrame.from(device, widthDp = null, heightDp = null)
  }

  /**
   * Render overrides that change the frame's SHAPE by a route this page cannot re-derive. Kept as a
   * list rather than folded into the check above so a new sizing knob in
   * [ServeOverrides.SUPPORTED_KEYS] is one line to account for here.
   */
  private val SIZE_OVERRIDE_KEYS =
    listOf(
      "widthPx",
      "heightPx",
      "minWidthPx",
      "minHeightPx",
      "maxWidthPx",
      "maxHeightPx",
      "orientation",
    )

  /**
   * The light/dark variant a preview **is**, from the catalog's baked `theme` token, else its night
   * `uiMode`. Null when the render is unthemed and says nothing — the catalog's stage answers then.
   *
   * Same two signals, in the same order, that [previewTheme] reads; they are kept in step
   * deliberately so the stage a comparison uses and the theme the viewer reports cannot diverge.
   */
  private fun variantSurfaceOf(preview: ServePreview): PreviewBackdrop.CatalogSurface? =
    PreviewBackdrop.CatalogSurface.parse(preview.theme)
      ?: when (preview.uiMode and UI_MODE_NIGHT_MASK) {
        UI_MODE_NIGHT_YES -> PreviewBackdrop.CatalogSurface.DARK
        UI_MODE_NIGHT_NO -> PreviewBackdrop.CatalogSurface.LIGHT
        else -> null
      }

  /**
   * The preview's baked theme, preferring explicit catalog metadata, then its discovery-time
   * uiMode, over id heuristics.
   *
   * Falls through to [backdropFor] rather than to the catalog stage directly, so a preview that
   * declares its own ground (`showBackground` / `backgroundColor`) is placed on *that* rather than
   * on whichever stage its system happens to prefer.
   */
  private fun previewTheme(preview: ServePreview, darkFirst: Boolean): String? =
    preview.theme
      ?: when (preview.uiMode and UI_MODE_NIGHT_MASK) {
        UI_MODE_NIGHT_YES -> "dark"
        UI_MODE_NIGHT_NO -> "light"
        // A preview that states its own ground overrides the catalog stage; everything else keeps
        // [bgTheme]'s existing answer verbatim — including its `null` for a light-first catalog,
        // which means "emit no tag, take the page's default stage" rather than "light".
        else -> declaredBackdropTheme(preview) ?: bgTheme(preview.id, darkFirst)
      }

  /**
   * `"light"`/`"dark"` when the preview's own `@Preview` params name a ground, else null.
   *
   * Deliberately narrower than [backdropFor]: this is only the rungs where the preview speaks for
   * itself, because the catalog-default rung is [bgTheme]'s job and answering it here too would
   * turn its null — the signal that no tag should be emitted at all — into a `light` tag on every
   * preview in every light-first catalog.
   */
  private fun declaredBackdropTheme(preview: ServePreview): String? =
    PreviewBackdrop.resolve(
        showBackground = preview.showBackground,
        backgroundColor = preview.backgroundColor,
        night = PreviewBackground.isNight(preview.uiMode),
      )
      .takeIf { it.source != PreviewBackdrop.Source.NONE }
      ?.let { if (it.isDark) "dark" else "light" }

  /** Stable, catalog-specific persistence key shared by that catalog's landing and viewer pages. */
  private fun themeStorageKey(sessionId: String?, basePath: String): String {
    val catalog = basePath.trim('/').ifBlank { sessionId ?: "default" }
    return "cp-theme:${WebEscaping.urlEncodeSegment(catalog)}"
  }

  /** Stable, catalog-specific key for the last section selected on that catalog's landing page. */
  private fun tabStorageKey(sessionId: String?, basePath: String): String {
    val catalog = basePath.trim('/').ifBlank { sessionId ?: "default" }
    return "cp-tab:${WebEscaping.urlEncodeSegment(catalog)}"
  }

  /**
   * Stable, catalog-specific prefix for the viewer's remembered disclosures (see
   * `viewer-drawers.js`). `localStorage` is per-ORIGIN, and one host serves many catalogs under
   * different base paths — so an unscoped key would let "I folded this catalog's thirty-state axis"
   * also fold a normally-inline axis on every unrelated catalog beside it. Same scoping the theme
   * and section keys already carry, for the same reason.
   */
  private fun foldStorageScope(sessionId: String?, basePath: String): String =
    WebEscaping.urlEncodeSegment(basePath.trim('/').ifBlank { sessionId ?: "default" })

  /**
   * The flattened id with its theme token stripped — the key that pairs a component's light and
   * dark variants into ONE grid card. `button-filled__ideal__default__light` and `…__dark` both key
   * to `button-filled__ideal__default`, so the Light/Dark control can swap the card between the two
   * baked renders in place.
   *
   * Strips ONLY the segment [cardTheme] treats as the theme — the *last* standalone `light`/`dark`
   * segment after the component-id head — never every one. A flattened id can carry a non-theme
   * `light`/`dark` *state* segment earlier (e.g. `toggle__dark__default__light` is the dark-state
   * toggle rendered in the light theme); stripping all of them would collapse `toggle__dark__…` and
   * `toggle__light__…` onto one key and drop a state. A component slug like `theme-meshcore-light`
   * is a single segment and is never a theme token.
   */
  private fun baseKey(id: String): String {
    val parts = id.split("__")
    val themeIdx =
      parts.indices.lastOrNull { it >= 1 && (parts[it] == "light" || parts[it] == "dark") }
    return if (themeIdx == null) id
    else parts.filterIndexed { i, _ -> i != themeIdx }.joinToString("__")
  }

  /**
   * The component's **identity across every render axis** — its slug head, with the state / theme /
   * props / size axes all dropped. `button-filled__ideal__pressed__dark`,
   * `button-filled__ideal__default__light`, and `…__light__content-icon-label` all key to
   * `button-filled`. It's the part before the `__ideal` quality marker
   * ([ServeCatalogStore.previewIdFor] emits `<slug>__ideal__…`); a preview with no `__ideal` marker
   * (a plain uploaded bundle screen) falls back to its theme-stripped [baseKey], so such previews
   * still key apart from one another. Used to collapse the viewer's component nav to ONE entry per
   * component (mirroring the grid), independent of which variant is being viewed.
   */
  private fun componentKey(p: ServePreview): String {
    val idx = p.id.indexOf("__ideal")
    return if (idx > 0) p.id.substring(0, idx) else baseKey(p.id)
  }

  /**
   * Whether [p] is a **non-default** component state render (`unchecked`, `pressed`, `disabled`,
   * `unselected`, …) — a render the grid folds out so each component shows a single (default) card,
   * with its other states reachable via the viewer's [component subtree][componentSubtreeHtml].
   * Keyed off the catalog's `state` metadata (from `variants.json`), not the id: a stateless
   * preview / plain bundle screen has `state == null` and is treated as default (always shown).
   */
  private fun isNonDefaultState(p: ServePreview): Boolean = p.state != null && p.state != "default"

  /**
   * Whether [p] is a **non-default props variant** — an i18n / content / a11y axis render
   * (`{"locale":"ar-XB"}`, `{"direction":"rtl"}`, `{"fontScale":"2.0"}`,
   * `{"content":"icon+label"}`, …) the grid folds out so a component shows ONE card (its default
   * render) instead of a card per variant, with the folded variants reachable via the viewer's
   * [component subtree][componentSubtreeHtml]. Keyed off the catalog's `props` metadata (from
   * `variants.json`), not the id: a propless preview (a plain bundle screen, or a design-system
   * default) has empty props and is treated as default (always shown).
   */
  private fun hasNonDefaultProps(p: ServePreview): Boolean = !p.props.isNullOrEmpty()

  /**
   * Whether [p] is a render at a **non-primary breakpoint** — one of the component's other declared
   * sizes, which the grid folds onto its single card exactly as it folds a non-default
   * [state][isNonDefaultState] or [props variant][hasNonDefaultProps].
   *
   * A size is a different *rendering* of one component, not a different component: `AlertDialog` at
   * 204dp is the same dialog the 192dp card shows, drawn on a wider watch. Left unfolded, a catalog
   * that documents five breakpoints publishes five cards under one name — 14 components became 70
   * rows in wear-m3-catalog, all of them called things like "Alert Dialog"
   * ([wear-m3-catalog#41](https://github.com/yschimke/wear-m3-catalog/issues/41)).
   *
   * [primary] is the component's primary size from [primarySizeByComponent], looked up in [p]'s own
   * theme lane. A preview with no declared size, or whose component resolved none *in that lane*,
   * is never folded — an older catalog (or a plain bundle) whose size lives only in the id keeps a
   * card per size, because there is no metadata to build a switcher from and folding would make
   * those renders unreachable.
   */
  private fun isNonPrimarySize(
    p: ServePreview,
    primary: Map<Pair<String, String>, String>,
    darkFirst: Boolean,
  ): Boolean {
    val size = p.size?.takeIf { it.isNotBlank() } ?: return false
    val componentPrimary = primary[sizeFoldKey(p, darkFirst)] ?: return false
    return size != componentPrimary
  }

  /**
   * A component's identity *within one theme lane* — the key the size fold is resolved against.
   *
   * The lane comes from [ServePreview.theme] alone, NOT from [themeLane]: that one falls back to
   * scanning the flattened id for a `light`/`dark` segment, and `breakpoints[].size` is an
   * arbitrary catalog-chosen string, so a catalog that names a breakpoint `light` would have its
   * *size* token read as a baked theme. Two sizes would then resolve as two lanes, both survive the
   * fold, and [groupPreviews] would pair them as a light/dark swap — a Theme control that silently
   * changes device size. A catalog that bakes its themes into ids without declaring the metadata
   * simply resolves one lane here and folds as it did before the lane split, which is the safe
   * direction: this key only ever decides how much to KEEP.
   */
  private fun sizeFoldKey(p: ServePreview, darkFirst: Boolean): Pair<String, String> =
    componentKey(p) to (p.theme?.takeIf { it.isNotBlank() } ?: if (darkFirst) "dark" else "light")

  /**
   * Each component's **primary** breakpoint — the size its one card is drawn at — keyed by
   * [componentKey] **and theme lane**.
   *
   * The catalog's own order decides it: the first size a component publishes, read in authored
   * order ([ServePreview.catalogOrder], falling back to list order for a catalog that records
   * none). The export writes a component's images in the order the spec's `breakpoints` table
   * declares them, so this is the first *declared* breakpoint — the one a catalog leads with, and
   * for a design catalog the one its design references are mapped against.
   *
   * Per **lane**, because the size switcher a folded render is reached from is itself lane-scoped
   * ([componentRenderRows] holds `themeLane` fixed, so a light page never offers a dark size). A
   * catalog whose theme × size product is sparse — the primary size drawn only light while some
   * other breakpoint carries the component's only dark render — would otherwise have that dark
   * render folded away with nothing left to reach it from. Keying per lane keeps one representative
   * in each lane that has renders at all.
   *
   * The component-wide primary is resolved FIRST and wins in every lane that has it; a lane falls
   * back to its own first-declared size only when it genuinely lacks that size. Resolving each lane
   * independently would let two lanes pick different primaries whenever they enumerate their
   * breakpoints in a different order — light leading with `192dp` while dark leads with `240dp`,
   * which separate per-theme preview functions can easily produce. The two survivors then carry
   * different size tokens, so [baseKey] cannot pair them, and a FULL product would publish two
   * cards where the whole point is one. Lane-local primaries are the exception for sparse lanes,
   * not the rule.
   *
   * Only the component's DEFAULT renders are consulted: a component may publish a state or props
   * variant at some sizes and not others, and letting those vote could pick a primary that the
   * default render never rendered at, folding the whole component's card out of the grid.
   */
  private fun primarySizeByComponent(
    previews: List<ServePreview>,
    darkFirst: Boolean,
  ): Map<Pair<String, String>, String> {
    val defaults =
      previews
        .filter { it.size != null && !isNonDefaultState(it) && !hasNonDefaultProps(it) }
        .sortedBy { it.catalogOrder ?: Int.MAX_VALUE }
    // The component's primary across every lane — the size the one card is drawn at when it can be.
    val componentPrimary = LinkedHashMap<String, String>()
    defaults.forEach { componentPrimary.putIfAbsent(componentKey(it), it.size!!) }
    // Which sizes each lane actually published, so a lane can be asked whether it has that primary.
    val sizesByLane = LinkedHashMap<Pair<String, String>, MutableSet<String>>()
    defaults.forEach {
      sizesByLane.getOrPut(sizeFoldKey(it, darkFirst)) { LinkedHashSet() }.add(it.size!!)
    }
    val primary = LinkedHashMap<Pair<String, String>, String>()
    defaults.forEach {
      val key = sizeFoldKey(it, darkFirst)
      val shared = componentPrimary[componentKey(it)]
      primary.putIfAbsent(
        key,
        if (shared != null && shared in sizesByLane.getValue(key)) shared else it.size!!,
      )
    }
    return primary
  }

  /**
   * A preview id with only its **size** segment removed — the key that groups renders differing
   * *only* in breakpoint while holding every other axis fixed, so the viewer's size switcher offers
   * `AlertDialog` at 204dp from its 192dp render without dragging the reader off the state or props
   * variant they are looking at.
   *
   * The exporter names a sticker `<slug>__<variant>__<state>[__theme][__size][__props…]`
   * (`catalog-image-path.mjs`), so the size sits after the theme and before the props segments —
   * hence [propsCount] trailing segments are held out of the search rather than the token simply
   * being matched from the end, which a props value spelling the same word would otherwise win. The
   * token is the slug of the render's own declared [ServePreview.size] rather than anything from a
   * fixed vocabulary: a catalog is free to name its breakpoints `192dp`, `smallRound` or `wide`,
   * and only the catalog knows which.
   *
   * Returns [id] unchanged when the render declares no size or the token isn't in it — the props
   * axis it may already have been folded on is preserved either way, so a caller can compose the
   * two without a size-less preview quietly losing the other fold.
   */
  private fun sizeInvariantKey(id: String, size: String?, propsCount: Int): String {
    val token = size?.takeIf { it.isNotBlank() }?.let(::catalogSlug) ?: return id
    val parts = id.split("__")
    val limit = (parts.size - propsCount).coerceAtLeast(0)
    val idx = (1 until limit).lastOrNull { parts[it] == token } ?: return id
    return parts.filterIndexed { i, _ -> i != idx }.joinToString("__")
  }

  /** [sizeInvariantKey] over a render's own id, holding its state and props segments in place. */
  private fun sizeInvariantKey(p: ServePreview): String =
    sizeInvariantKey(p.id, p.size, p.props?.size ?: 0)

  /**
   * The size-switcher grouping key: [sizeInvariantKey] with the theme dropped too, for the same
   * reason [switcherStateKey] drops it — an untagged render has to group with its themed siblings,
   * and [themeLane] is what keeps the lanes apart.
   */
  private fun switcherSizeKey(p: ServePreview): String =
    themeStrippedKey(sizeInvariantKey(p), p.theme)

  /**
   * The exporter's slug for one id segment: non-`[a-zA-Z0-9._-]` runs collapse to `-`, trimmed and
   * lowercased. The Kotlin twin of `catalogSlug` in `catalog-image-path.mjs` (and of
   * [ServeBundleHost.heroSlug]) — a declared size of `Small Round` is `smallround` in the id it
   * named, so matching one against the other has to go through the same rule.
   */
  private fun catalogSlug(value: String): String =
    value.replace(Regex("[^a-zA-Z0-9._-]+"), "-").trim('-').lowercase()

  /**
   * Human label for a declared breakpoint: the catalog's own name for it ([ServePreview.size]),
   * else the token vocabulary [previewSizeVariantLabel] can recognise in the id. The catalog's name
   * leads because it is the one the spec's `breakpoints` table authored and the one the reader sees
   * everywhere else the axis is named.
   */
  private fun sizeLabel(p: ServePreview): String? =
    p.size?.takeIf { it.isNotBlank() } ?: previewSizeVariantLabel(p.id)

  /**
   * Human label for a component [state] token: the default render reads "Default"; a hyphenated
   * token like `keyboard-focus` becomes "Keyboard focus" (dashes → spaces, first letter
   * capitalised). Used for the viewer's state-switcher buttons.
   */
  private fun stateLabel(state: String?): String =
    if (state == null || state == "default") "Default"
    else state.replace('-', ' ').replaceFirstChar { it.uppercaseChar() }

  /**
   * A preview id with only its **state** segment removed — the key that groups renders differing
   * *only* in state (the state axis) while holding every other axis fixed (theme, and any `content`
   * / `size` / `k=v` props axes a component also varies on). The state segment is the one right
   * after the `ideal` marker in the flattened id (`<slug>__ideal__<state>[__theme][__props…]`, from
   * [ServeCatalogStore.previewIdFor]); it equals the preview's [ServePreview.state]. So
   * `button-filled__ideal__default__light` and `…__pressed__light` share the key
   * `button-filled__ideal__light`, but the `content=icon+label` render
   * `button-filled__ideal__default__light__content-icon-label` keeps its props segment and keys
   * apart — its state switcher won't drag the visitor back to the label-only button. Falls back to
   * the whole id when there's no state (a plain preview) or the state token isn't found, so such a
   * preview only ever groups with itself.
   */
  private fun stateInvariantKey(id: String, state: String?): String {
    state ?: return id
    val parts = id.split("__")
    val idealIdx = parts.indexOf("ideal")
    val stateIdx =
      if (idealIdx in 0 until parts.lastIndex && parts[idealIdx + 1] == state) idealIdx + 1
      else parts.indexOfFirst { it == state }.takeIf { it >= 1 } ?: return id
    return parts.filterIndexed { i, _ -> i != stateIdx }.joinToString("__")
  }

  private fun stateInvariantKey(p: ServePreview): String = stateInvariantKey(p.id, p.state)

  /**
   * The switcher's grouping key: [stateInvariantKey] with the theme segment dropped too
   * ([baseKey]), so a component's renders group by *what they are* and the theme is left to
   * [themeLane] alone.
   *
   * Dropping the theme from the key rather than relying on it is what makes an **untagged** render
   * group with its themed siblings. A catalog does not necessarily tag both modes: a component
   * whose non-default states come from `@OverrideVariant` publishes its dark cells as `…__xs__dark`
   * (the `uiMode` is a `@Preview` param the synthetic capture inherits) but its light cells as a
   * bare `…__xs`, while the default render still carries the full `…__default__light`. Keyed on the
   * id including the theme, those two never met: the light default keyed
   * `button-filled__ideal__light` and the light `xs` cell keyed `button-filled__ideal`, so the
   * viewer offered no state switcher at all on the light lane — the lane the grid links to — and
   * the whole size/shape matrix was reachable only by hand-typing an id. Both now key
   * `button-filled__ideal` and [themeLane] keeps light and dark apart.
   */
  private fun switcherStateKey(p: ServePreview): String =
    themeStrippedKey(stateInvariantKey(p), p.theme)

  /**
   * The props-family counterpart of [switcherStateKey], normalised the same way and for the same
   * reason: a themed default (`button__ideal__default__light`) and an untagged props sibling
   * (`button__ideal__default__content-icon-label`) resolve to one lane but would otherwise key
   * apart, and the family check runs first — so the lane agreeing would never get to matter and the
   * folded variant would stay unreachable.
   */
  private fun switcherPropsKey(p: ServePreview): String =
    themeStrippedKey(propsFamilyKey(p), p.theme)

  /**
   * [id] with its theme segment dropped ([baseKey]) — but **only when the render declares a
   * theme**.
   *
   * The guard is what keeps a state from being read as a theme. `baseKey` finds the last
   * `light`/`dark` token positionally, and a component may legitimately name a *state* `dark`
   * (`toggle__ideal__dark` with `state = "dark"`, no theme at all). Stripping that would key the
   * state apart from its own siblings; asking only renders that actually carry a theme to give it
   * up cannot.
   */
  private fun themeStrippedKey(id: String, theme: String?): String =
    if (theme == null) id else baseKey(id)

  /**
   * The light/dark **lane** a render belongs to for switcher grouping — its declared
   * [ServePreview.theme], else the `__light`/`__dark` token in its id ([cardTheme]), else the
   * system's primary lane (dark for a dark-first system, light otherwise).
   *
   * The fallback is the point: an untagged render is not theme-*less* in any way a visitor
   * experiences, it is simply the mode the catalog draws by default, and the switcher has to put it
   * in that lane or it strands there alone. Compared as a resolved string rather than a nullable so
   * the relation is symmetric — an untagged sibling reaches the primary-lane default and the
   * primary-lane default reaches it back.
   *
   * The id is read **state-stripped**, so the token scan cannot pick up a state named `light` or
   * `dark` and lane an unthemed render away from its own siblings.
   */
  private fun themeLane(p: ServePreview, darkFirst: Boolean): String =
    p.theme ?: cardTheme(stateInvariantKey(p)) ?: if (darkFirst) "dark" else "light"

  /**
   * Canonical JSON for a props value. Objects sort their keys recursively, while arrays preserve
   * their authored order. This keeps the variant identity stable even when two producers emit an
   * equivalent object with a different property order, and it keeps JSON types distinct (`true` is
   * not the same variant as `"true"`).
   */
  private fun canonicalPropsJson(value: JsonElement): String =
    when (value) {
      is JsonObject ->
        value.entries
          .sortedBy { it.key }
          .joinToString(prefix = "{", postfix = "}") { (key, child) ->
            "${JsonPrimitive(key)}:${canonicalPropsJson(child)}"
          }
      is JsonArray ->
        value.joinToString(prefix = "[", postfix = "]") { child -> canonicalPropsJson(child) }
      else -> value.toString()
    }

  /** Human-readable form of a props value: unquote scalars, retain compact JSON for structures. */
  private fun propsValueLabel(value: JsonElement): String =
    if (value is JsonPrimitive) value.content else canonicalPropsJson(value)

  /** A stable signature for a preview's props axis (sorted `k=value` pairs); `""` for default. */
  private fun propsSignature(props: JsonObject?): String =
    props
      ?.entries
      ?.sortedBy { it.key }
      ?.joinToString(",") { "${it.key}=${canonicalPropsJson(it.value)}" } ?: ""

  /**
   * Human label for a props-variant axis: "Default" for none, else a compact per-axis phrasing
   * ("RTL", "Locale ar-XB", "Font 2.0×", "Icon+label"), falling back to `key value` for an unknown
   * axis. Multiple axes join with " · ". Used for the viewer's variant-switcher buttons.
   */
  private fun propsLabel(props: JsonObject?): String {
    if (props.isNullOrEmpty()) return "Default"
    return props.entries
      .sortedBy { it.key }
      .joinToString(" · ") { (k, rawValue) ->
        val v = propsValueLabel(rawValue)
        when (k) {
          "direction" -> v.uppercase()
          "locale" -> "Locale $v"
          "fontScale" -> "Font ${v}×"
          "content" -> v.replaceFirstChar { it.uppercaseChar() }
          else -> "$k $v"
        }
      }
  }

  /**
   * The preview id with its trailing **props** segments removed — the key that groups a component's
   * default render with its props-axis variants (content / locale / direction / fontScale), holding
   * every other axis (slug, state, theme, size) fixed. The exporter appends one flattened segment
   * per props entry to the id (`…__light__content-icon-label`, `…__compact__locale-de`), so
   * dropping [ServePreview.props]`.size` trailing segments recovers the default render's id. The
   * default (no props) keys to its own full id, so a propless component only ever groups with
   * itself.
   */
  private fun propsFamilyKey(p: ServePreview): String {
    val n = p.props?.size ?: 0
    if (n == 0) return p.id
    val parts = p.id.split("__")
    return if (parts.size > n) parts.dropLast(n).joinToString("__") else p.id
  }

  /**
   * The comparison-table card family for [p]: fold state, props, size and the baked light/dark
   * pair. This mirrors the default-card grouping used by [groupPreviews] without broadening aliases
   * to every render of the same [componentKey].
   *
   * The size is folded here so a viewer deep-link naming a breakpoint the gallery left out still
   * selects that component's row rather than landing on an empty comparison — the same job the key
   * already does for a folded-out state. A component whose second size DOES carry a reference keeps
   * its own row (rows are keyed by [baseKey], not by this); the two rows then share one alias set,
   * exactly as two reference-bearing states of one component already do.
   */
  private fun comparisonCardKey(p: ServePreview): String =
    baseKey(stateInvariantKey(sizeInvariantKey(propsFamilyKey(p), p.size, propsCount = 0), p.state))

  /**
   * How a comparison row names the variant it shows — `Hovered`, `Xl square`, `RTL · Font 2.0×` —
   * or empty for the component's plain default render.
   *
   * The comparison page keeps every reference-bearing variant as a row of its own, so a component
   * with a reference per state contributes a dozen rows. [componentKey] alone cannot tell them
   * apart, which is what makes an otherwise correct page look mis-paired. Empty for the default so
   * the overwhelmingly common one-row-per-component case still reads as just the component name.
   */
  private fun compareVariantLabel(p: ServePreview): String =
    listOf(stateLabel(p.state), propsLabel(p.props)).filter { it != "Default" }.joinToString(" · ")

  /**
   * One grid card: a component that may carry a baked `light` and/or `dark` variant (a pair the
   * Light/Dark control [swaps][GridCard.swappable] in place) and/or a theme-neutral render. [order]
   * preserves first-seen position so the grid keeps catalog order.
   */
  private class GridCard(val order: Int) {
    var light: ServePreview? = null
    var dark: ServePreview? = null
    var neutral: ServePreview? = null

    /** True when both themes are baked, so the card can swap between them (rather than filter). */
    val swappable: Boolean
      get() = light != null && dark != null

    /** The variant shown by default (server-side): light, else dark, else the neutral render. */
    val default: ServePreview
      get() = light ?: dark ?: neutral!!

    /**
     * The render the grid actually paints, which on a **dark-first** system is the dark one —
     * [default] prefers light regardless, and `swapCard` has always opened on the system's own
     * lane. Anything describing the card to a visitor has to agree with the pixels beside it: a
     * tree built from [default] would label a dark-first catalog's cards from their light twins and
     * send every variant link into the light lane while the card next to it is showing dark.
     */
    fun rendered(darkFirst: Boolean): ServePreview =
      if (darkFirst) (dark ?: light ?: neutral!!) else default
  }

  /**
   * Collapse a catalog's per-theme previews into grid cards keyed by [baseKey], so a component's
   * `__light`/`__dark` variants become a SINGLE card the Light/Dark control swaps between — instead
   * of two separate cards a filter hides between. A component captured in only one theme (or a
   * theme-neutral app screen) stays a lone card the toggle leaves untouched. Order follows first
   * appearance.
   */
  private fun groupPreviews(previews: List<ServePreview>): List<GridCard> {
    val byKey = LinkedHashMap<String, GridCard>()
    previews.forEachIndexed { i, p ->
      val card = byKey.getOrPut(baseKey(p.id)) { GridCard(i) }
      when (cardTheme(p.id)) {
        "light" -> if (card.light == null) card.light = p
        "dark" -> if (card.dark == null) card.dark = p
        else -> if (card.neutral == null) card.neutral = p
      }
    }
    return byKey.values.sortedBy { it.order }
  }

  /** Fallback tab for section-bearing catalogs whose stray card carries no section of its own. */
  private const val OTHER_SECTION = "Other"

  /**
   * The **All** row's `data-tab`: the whole catalog, every section's panel showing at once, and
   * what a sectioned catalog lands on.
   *
   * A reserved slug rather than a section's own, so [buildSections] hands a catalog that really
   * does name a section "All" the slug `all-2` and the two never collide over `#cp-tab-all` /
   * `?tab=all`.
   */
  private const val ALL_TAB = "all"

  /**
   * The catalog section whose cards ARE theme specimens — a colour-role/type sheet that exists to
   * show one specific theme.
   */
  private const val THEMES_SECTION = "Themes"

  /**
   * Whether [p] is a theme **specimen**: a card that renders a named theme as its subject, so
   * re-rendering it under a `themeProvider` override destroys the very thing it documents.
   *
   * meshcore-mobile's `Theme/MeshCore-Light` is the case that surfaced this. Its caption reads
   * "MeshCore · Light · Orbitron / Space Grotesk / JetBrains Mono", and under a Dynamic Dark
   * override the card drew dark, in the default sans — pixels contradicting their own label. Every
   * card in a Themes tab has that property by construction.
   *
   * Two signals, either of which is enough:
   * * the catalog **section** — deliberately keyed on that rather than the id, because `theme-…` id
   *   prefixes are an authoring convention while `section` is the authored statement of what the
   *   tab IS (`catalog.spec.json`'s `section: "Themes"`). It speaks for a whole tab at once.
   * * the per-preview [ServePreview.fixedTheme] flag, from `@FixedTheme` on the function (or a
   *   `@ThemeCatalog`-synthesised sheet). This is what a specimen living OUTSIDE a Themes tab says
   *   for itself — an ungrouped bundle, a `Foundation` section that mixes swatches with components,
   *   a plain `compose-preview serve` of one module, none of which have a section to speak for
   *   them.
   *
   * This does NOT remove the theme chips: the rest of the catalog still re-renders, and a specimen
   * simply keeps its baked pixels — the same treatment a card with no daemon twin already gets.
   */
  private fun isThemeSpecimen(p: ServePreview): Boolean =
    p.fixedTheme || p.section?.equals(THEMES_SECTION, ignoreCase = true) == true

  /**
   * One sub-heading group inside a section tab: its [name] (null ⇒ ungrouped) and its cards.
   *
   * [slug] is the group's half of the `cp-group-<section>-<group>` anchor the navigation tree jumps
   * to, assigned by [buildSections] and unique within its section. Empty for a synthesized flat
   * group ([synthesizeGroups]), which has no tree above it to be jumped to from.
   */
  private class LandingGroup(val name: String?, var slug: String = "") {
    val cards = mutableListOf<GridCard>()
  }

  /**
   * One section (tab) of a tabbed landing: its display [name], a route-safe [slug] (the tab's
   * `#cp-panel-<slug>` anchor / id), and its ordered sub-[groups]. [count] totals its cards for the
   * tab's badge.
   */
  private class LandingSection(val name: String, var slug: String) {
    val groups = mutableListOf<LandingGroup>()

    val count: Int
      get() = groups.sumOf { it.cards.size }
  }

  /** Route-safe slug for a section name (`"Screens · Scanner"` → `"screens-scanner"`). */
  private fun sectionSlug(name: String): String {
    val s =
      name
        .lowercase()
        .map { if (it.isLetterOrDigit()) it else '-' }
        .joinToString("")
        .trim('-')
        .replace(Regex("-+"), "-")
    return s.ifEmpty { "section" }
  }

  /**
   * Bucket [cards] into ordered [LandingSection] tabs (keyed by each card's [ServePreview.section])
   * with ordered sub-[LandingGroup]s (keyed by [ServePreview.group]) inside — the tabbed-catalog
   * structure the landing renders as a tab bar over per-section panels.
   *
   * Sections, groups, and cards are all ordered by their authored [ServePreview.catalogOrder] (min
   * order for a section/group), because [ServeBundleHost] lists previews sorted by id — so without
   * this the tabs would read alphabetically rather than Themes → Components → Screens → … as
   * authored. A card missing a section falls into a trailing **"Other"** tab so nothing is dropped.
   * Slugs are de-duplicated so two same-slug section names still get distinct tab anchors. Returns
   * an empty list when NO card carries a section (a flat, untabbed catalog — the caller keeps the
   * plain grid).
   */
  private fun buildSections(cards: List<GridCard>): List<LandingSection> {
    if (cards.none { it.default.section != null }) return emptyList()
    fun ord(c: GridCard) = c.default.catalogOrder ?: Int.MAX_VALUE
    // section name -> (min order, group name -> cards), insertion-ordered as a stable fallback.
    class SectionAcc {
      var minOrder = Int.MAX_VALUE
      val groups = LinkedHashMap<String?, LandingGroup>()
    }
    val bySection = LinkedHashMap<String, SectionAcc>()
    for (card in cards) {
      val secName = card.default.section ?: OTHER_SECTION
      val acc = bySection.getOrPut(secName) { SectionAcc() }
      acc.minOrder = minOf(acc.minOrder, ord(card))
      acc.groups.getOrPut(card.default.group) { LandingGroup(card.default.group) }.cards.add(card)
    }
    // `all` belongs to the All row, so a section actually named "All" takes `all-2` — the same
    // de-duplication two same-slug section names already get.
    val usedSlugs = hashSetOf(ALL_TAB)
    return bySection.entries
      .sortedBy { it.value.minOrder }
      .map { (name, acc) ->
        var slug = sectionSlug(name)
        var n = 2
        while (!usedSlugs.add(slug)) {
          slug = "${sectionSlug(name)}-$n"
          n++
        }
        val section = LandingSection(name, slug)
        // Group slugs are scoped to their section, so the same group name reused across two
        // sections (meshcore-mobile's "Device" appears under both Components and Screens) still
        // yields two distinct anchors rather than one that swallows both.
        val usedGroupSlugs = HashSet<String>()
        acc.groups.values
          .sortedBy { g -> g.cards.minOf { ord(it) } }
          .forEach { g ->
            var gslug = g.name?.let { sectionSlug(it) } ?: "ungrouped"
            var gn = 2
            while (!usedGroupSlugs.add(gslug)) {
              gslug = "${g.name?.let { sectionSlug(it) } ?: "ungrouped"}-$gn"
              gn++
            }
            val ordered = LandingGroup(g.name, gslug)
            ordered.cards.addAll(g.cards.sortedBy { ord(it) })
            section.groups.add(ordered)
          }
        section
      }
  }

  /**
   * The catalog's **navigation tree**: one row per section, each expanding to its named sub-groups,
   * standing beside the grid rather than above it.
   *
   * This replaces the row of section tabs. The tabs showed only the top level of a structure that
   * is two deep — a catalog's groups (Foundation, Contacts, Scanner, …) existed solely as headings
   * you had to scroll a panel to find, so the only way to learn what a section *contained* was to
   * open it and read. The tree publishes both levels at once: every group in the selected section
   * is a destination you can see and click, and the selected one is marked as you scroll.
   *
   * The DOM contract the section rows carry is deliberately unchanged from the tab bar —
   * `.cp-tab[data-tab]`, `#cp-tab-<slug>`, `aria-controls`, `aria-selected`, and the
   * `href="#cp-panel-<slug>"` fallback — because that is what [catalogFilterScript]'s section
   * switching, the remembered-tab key, and the `?tab=` URL param all key off. What is new is the
   * nesting: a `role="group"` list of `.cp-tree-group` links, each pointing at its
   * `#cp-group-<section>-<group>` anchor on the sub-group divider the grid already emits.
   *
   * A section is **expanded exactly when it is selected**, which is the same statement its panel
   * makes — one section's contents at a time, rather than a second piece of state that can disagree
   * with which panel is showing. While a search is active the script spans every section (that is
   * the existing tab behaviour), so the tree expands every section that still holds a match. With
   * no JS nothing collapses at all: `html.cp-js` gates the collapse, so a no-JS client sees the
   * full outline over the full stack of panels, and every row is a working in-page anchor.
   *
   * Sections whose groups are all unnamed render as leaves — there is nothing to list under them.
   *
   * The tree leads with an **All** row ([ALL_TAB]) whenever there is more than one section, and it
   * is what the page lands on. A sectioned catalog used to open on its first section with the rest
   * of itself hidden, so the default view of a catalog was a fraction of it and the filter below
   * only searched the whole thing once you had typed into it. All is the browsing state the front
   * door should have: every panel showing, one scroll through the lot, and a filter that spans the
   * catalog because nothing is narrowing it. Picking a section still narrows to it; All is a row
   * you can come back to. Under All every section is expanded, since the tree beside a grid showing
   * everything is the outline of everything.
   */
  private fun catalogTreeHtml(
    sections: List<LandingSection>,
    components: (GridCard) -> TreeComponent,
    /** The design-pages branch ([pagesBranchHtml]), appended after the sections. Empty ⇒ none. */
    pagesBranch: String = "",
  ): String = buildString {
    // With more than one section there is a whole catalog to browse, so the tree leads with it.
    // One section IS the whole catalog, and a row saying so twice is not a choice.
    val hasAll = sections.size > 1
    append("<nav class=\"cp-tree\" id=\"cp-tabs\" aria-label=\"Catalog sections\">\n")
    append("<ul class=\"cp-tree-list\" role=\"tree\" aria-label=\"Catalog sections\">\n")
    if (hasAll) {
      // The whole grid, not a panel: `#cp-grid` is what the All row controls and what its no-JS
      // href jumps to — every section's panel is inside it, and it is the one id that is still
      // there when the sections themselves are collapsed away by a filter.
      append("<li class=\"cp-tree-node\" role=\"none\">\n")
      append("  <a class=\"cp-tab\" role=\"treeitem\" id=\"cp-tab-$ALL_TAB\"")
      append(" href=\"#cp-grid\" data-tab=\"$ALL_TAB\"")
      append(" aria-controls=\"cp-grid\" aria-selected=\"true\">")
      append("All<span class=\"cp-tab-count\">${sections.sumOf { it.count }}</span></a>\n")
      append("</li>\n")
    }
    sections.forEachIndexed { i, sec ->
      // Nothing is selected under All — it is the row above that is. A section is still EXPANDED,
      // because All shows every panel at once and the tree standing beside that has to be the
      // outline of what is actually on screen.
      val selected = if (!hasAll && i == 0) "true" else "false"
      val expanded = if (hasAll) "true" else selected
      val named = sec.groups.filter { it.name != null }
      append("<li class=\"cp-tree-node\" role=\"none\">\n")
      val childrenId = "cp-tree-children-${sec.slug}"
      append("  <a class=\"cp-tab\" role=\"treeitem\" id=\"cp-tab-${sec.slug}\"")
      append(" href=\"#cp-panel-${sec.slug}\" data-tab=\"${sec.slug}\"")
      append(" aria-controls=\"cp-panel-${sec.slug}\" aria-selected=\"$selected\"")
      // `aria-owns` because the markup cannot nest the group inside the treeitem: the row has to
      // be an <a> to stay a real link (the no-JS path), and the <li> that does contain both is
      // `role="none"`. Without this the `role="group"` would hang off the tree rather than off the
      // section whose `aria-expanded` governs it, so a screen reader could not report the group
      // rows as that section's children.
      if (named.isNotEmpty()) append(" aria-expanded=\"$expanded\" aria-owns=\"$childrenId\"")
      // No `tabindex` in the served markup, deliberately. The roving tab stop is a tree-widget
      // behaviour and the tree is only a widget once its script runs — baking `-1` into every row
      // but the first would leave a no-JS client (where the arrow keys never bind) unable to reach
      // any section past the first by keyboard, in the very mode where the rows are its only
      // navigation. `reflectTabs()` applies the indices on init instead.
      append(">")
      append(WebEscaping.htmlEscape(sec.name))
      append("<span class=\"cp-tab-count\">${sec.count}</span></a>\n")
      if (named.isNotEmpty()) {
        append("  <ul class=\"cp-tree-children\" id=\"$childrenId\" role=\"group\">\n")
        named.forEachIndexed { gi, g ->
          // The first group of the first section opens with the page, so a visitor lands on a tree
          // that is already showing components rather than one that has to be prised open before
          // it says anything a tab bar didn't.
          appendGroupRow(
            g,
            sec.slug,
            groupAnchorId(sec.slug, g.slug),
            i == 0 && gi == 0,
            components,
          )
        }
        append("  </ul>\n")
      }
      append("</li>\n")
    }
    append(pagesBranch)
    append("</ul>\n</nav>\n")
  }

  /**
   * The **outline** tree, for a catalog whose previews declare no `section` — the shape most
   * published design systems are in, m3-catalog included, where the inventory comes from
   * `@CatalogComponent(group = …)` and nothing ever names a section.
   *
   * Until now those catalogs got no tree at all: [buildSections] returned empty, the landing fell
   * back to a flat grid, and the two levels of structure the catalog *did* have (family group, then
   * component) stayed invisible. Here the groups ARE the top level. There are no panels to switch —
   * the flat grid shows everything at once — so every row is purely a jump, which is also why these
   * rows carry no `data-tab`.
   */
  private fun catalogOutlineTreeHtml(
    groups: List<LandingGroup>,
    components: (GridCard) -> TreeComponent,
    /** The design-pages branch ([pagesBranchHtml]), appended after the groups. Empty ⇒ none. */
    pagesBranch: String = "",
  ): String = buildString {
    append("<nav class=\"cp-tree\" id=\"cp-tabs\" aria-label=\"Catalog contents\">\n")
    append("<ul class=\"cp-tree-list\" role=\"tree\" aria-label=\"Catalog contents\">\n")
    groups.forEachIndexed { i, g ->
      // The group row IS the top-level node here, so it carries `cp-tree-node` itself rather than
      // being wrapped in one — the wrapper is what the filter hides, and a second <li> around an
      // <li> is not a list.
      appendGroupRow(g, null, flatGroupAnchorId(g.slug), i == 0, components, "cp-tree-node")
    }
    append(pagesBranch)
    append("</ul>\n</nav>\n")
  }

  /**
   * The tree's **Pages** branch: the design file's own pages, listed by name under one row that
   * leads to the index.
   *
   * This used to be an action chip in the header row, beside "compare SVG" and "download all". A
   * chip could only say *how many* pages there were — the names, which are the thing you actually
   * choose between, were a page away — and it sat in a row of one-off actions while being the one
   * entry there that is a place. The tree is where this catalog's places already live, so it goes
   * in the tree, at the foot: a page is a view of the *design file*, not part of the catalog's own
   * inventory, and it should not push that inventory down the column.
   *
   * Two things make it unlike every other branch, and both are deliberate:
   * - **It carries no `data-group`.** Every other row names an id on this page and is intercepted
   *   into a scroll; these rows are real navigations, so the click handler's `if (!id) return`
   *   leaves them to the browser. It is the same treatment a variant row already gets.
   * - **It is always open.** `aria-expanded="true"` is written once and never reflected — with a
   *   handful of pages there is nothing to gain by hiding their names behind a twisty, and the open
   *   state is what makes the branch worth having over the chip it replaces. [catalogTreeScript]
   *   skips reflecting a row that names no target, which is what keeps it open.
   */
  private fun pagesBranchHtml(pages: List<PageLink>, basePath: String, q: String): String {
    if (pages.isEmpty()) return ""
    return buildString {
      append("<li class=\"cp-tree-node cp-tree-pages\" role=\"none\">\n")
      append("  <a class=\"cp-tree-pages-row cp-tree-link\" role=\"treeitem\"")
      append(" href=\"${WebEscaping.htmlEscape("$basePath/pages$q")}\"")
      append(" aria-expanded=\"true\" aria-owns=\"cp-tree-pages-list\">")
      append("Pages<span class=\"cp-tree-count\">${pages.size}</span></a>\n")
      append("  <ul class=\"cp-tree-children cp-tree-components\" id=\"cp-tree-pages-list\"")
      append(" role=\"group\">\n")
      pages.forEach { page ->
        // The page id reaches the URL as one path segment, and the name is free text authored in
        // the design file — so one is encoded and the other escaped.
        val href = "$basePath/pages/${WebEscaping.urlEncodeSegment(page.id)}$q"
        append("    <li role=\"none\"><a class=\"cp-tree-page cp-tree-link\" role=\"treeitem\"")
        append(" href=\"${WebEscaping.htmlEscape(href)}\">")
        append("${WebEscaping.htmlEscape(page.name)}</a></li>\n")
      }
      append("  </ul>\n</li>\n")
    }
  }

  /**
   * The sidebar's two panes — **Components** and **Pages** — and the strip that switches them.
   *
   * The design file's pages used to be a branch at the FOOT of the component tree: below every
   * family, every component and every variant the catalog has. On m3-catalog that is past ~120
   * rows, so the pages were reachable only by scrolling the inventory you were not looking for, and
   * the two lists competed for the same column while answering different questions — *which
   * component* versus *which page of the design file*. They are peers, so they get peer treatment:
   * one strip at the top says which of the two the column is showing.
   *
   * **Only when there is something to switch between.** A catalog with no design pages keeps the
   * bare tree it has always had — a tab strip with one tab is a control that cannot be used, and
   * emitting one would move every committed golden for no reader benefit.
   *
   * Both panes are filtered by the one search box below the strip (see [catalogFilterScript]),
   * which is the other half of what makes them peers: the pages list was previously the only thing
   * in this column the filter could not reach.
   */
  private fun paneTabsHtml(componentCount: Int, pageCount: Int): String =
    """
    <div class="cp-panes" role="tablist" aria-label="Catalog navigation">
      <button type="button" class="cp-pane-tab" role="tab" id="cp-pane-tab-components"
        data-pane="components" aria-controls="cp-pane-components" aria-selected="true">
        Components<span class="cp-tree-count">$componentCount</span>
      </button>
      <button type="button" class="cp-pane-tab" role="tab" id="cp-pane-tab-pages"
        data-pane="pages" aria-controls="cp-pane-pages" aria-selected="false" tabindex="-1">
        Pages<span class="cp-tree-count">$pageCount</span>
      </button>
    </div>
    """
      .trimIndent()

  /**
   * The **Pages** pane: the design file's own pages, as a flat list.
   *
   * Flat rather than a tree, because it is one: a page has no children, and the branch shape it
   * used to wear implied a hierarchy that never existed. Each row carries `data-search` so the
   * shared filter can match it the way it matches a component row — the attribute rather than the
   * text, so what is matched is decided here and not by whatever the row happens to render.
   */
  private fun pagesPaneHtml(pages: List<PageLink>, basePath: String, q: String): String =
    buildString {
      append("<div class=\"cp-pane cp-pane-pages\" id=\"cp-pane-pages\" role=\"tabpanel\"")
      append(" aria-labelledby=\"cp-pane-tab-pages\" hidden>\n")
      append("<ul class=\"cp-page-list\">\n")
      pages.forEachIndexed { i, page ->
        // The page id reaches the URL as one path segment, and the name is free text authored in
        // the design file — so one is encoded and the other escaped.
        val pageUrl = "$basePath/pages/${WebEscaping.urlEncodeSegment(page.id)}$q"
        val href = WebEscaping.htmlEscape(pageUrl)
        val name = WebEscaping.htmlEscape(page.name)
        if (page.sections.isEmpty()) {
          append("  <li><a class=\"cp-tree-page cp-tree-link\" href=\"$href\"")
          append(" data-search=\"$name\">$name</a></li>\n")
        } else {
          // A page WITH sections is a branch: the row still leads to the whole sheet, and the
          // twisty beside it opens the sections that sheet is divided into. Only the first opens,
          // for the same reason the component tree opens one family — a column that opens
          // everything is the wall of rows this navigation exists to replace.
          val listId = "cp-page-sections-${WebEscaping.htmlEscape(page.id)}"
          val open = i == 0
          append("  <li class=\"cp-page-branch\">\n")
          append("    <a class=\"cp-tree-page cp-tree-link\" href=\"$href\"")
          append(" data-search=\"$name\" aria-expanded=\"${if (open) "true" else "false"}\"")
          append(" aria-controls=\"$listId\">$name")
          append("<span class=\"cp-tree-count\">${page.sections.size}</span></a>\n")
          append("    <ul class=\"cp-tree-children cp-page-sections\" id=\"$listId\">\n")
          page.sections.forEach { section ->
            val sectionName = WebEscaping.htmlEscape(section.name)
            // The row opens the SHEET, with no fragment — for now.
            //
            // A section is a COMPONENT_SET, and the page view draws nothing for one: it anchors
            // components, and `isComponent` excludes containers because nothing implements a set.
            // An earlier revision inferred a target by taking the first deeper node after the set,
            // which is precisely the inference `PageNode.container`'s own contract forbids — "a
            // manifest lists components and nothing else, so an unlisted frame between two of them
            // lets a shallower node be followed by a deeper one that is NOT inside it". On an empty
            // set that silently linked to an unrelated component, which is worse than not linking:
            // a wrong destination is indistinguishable from a right one until you read the sheet.
            //
            // Landing on the section itself needs the page to emit an anchor for the container,
            // which is the deep-link work this is a step toward. Until then the honest link is the
            // sheet, and the section names still do the job they were added for — making a page
            // findable by what is on it.
            val sectionHref = pageUrl
            append("      <li><a class=\"cp-tree-variant cp-tree-link\"")
            append(" href=\"${WebEscaping.htmlEscape(sectionHref)}\"")
            append(" data-search=\"$sectionName\">$sectionName</a></li>\n")
          }
          append("    </ul>\n  </li>\n")
        }
      }
      append("</ul>\n")
      append("<p class=\"cp-pane-empty\" id=\"cp-pages-empty\" hidden>No pages match.</p>\n")
      append("<a class=\"cp-pane-all\" href=\"${WebEscaping.htmlEscape("$basePath/pages$q")}\">")
      append("All pages</a>\n")
      append("</div>\n")
    }

  /**
   * The anchor on a synthesized flat sub-group divider — no section owns it, so it stands alone.
   */
  private fun flatGroupAnchorId(groupSlug: String) = "cp-group-$groupSlug"

  /**
   * A card's id line, split so it elides from the MIDDLE rather than the end.
   *
   * At a catalog column's width almost every id is clipped, and clipped at the END it conveys
   * nothing the label above it hasn't already said — `iconbutton-standard__ide…`. What
   * distinguishes one render from its siblings is the SUFFIX (the mode and the scheme), so the id
   * is cut at its last `__` and only the head half is allowed to shrink:
   * `iconbutton-standard__…__light`. CSS has no middle ellipsis, hence the two spans.
   *
   * Both spans are always emitted, even for an id with no `__` (empty tail): the grid's light/dark
   * swap re-fills them in place, and a card that arrived without a tail span would have nowhere to
   * put its variant's suffix.
   */
  private fun cardIdHtml(id: String): String {
    val cut = id.lastIndexOf("__")
    val head = if (cut > 0) id.substring(0, cut) else id
    val tail = if (cut > 0) id.substring(cut) else ""
    return "<div class=\"cp-id cp-id-elide\">" +
      "<span class=\"cp-id-head\">${WebEscaping.htmlEscape(head)}</span>" +
      "<span class=\"cp-id-tail\">${WebEscaping.htmlEscape(tail)}</span></div>"
  }

  /** A component row and the primary-axis variants beneath it. */
  private class TreeComponent(
    val label: String,
    val anchorId: String,
    val variants: List<TreeVariant>,
    val href: String,
  )

  /**
   * One group row plus its component rows (and each component's variants).
   *
   * Expansion follows the same discipline as a section: **a group is open exactly when it is the
   * current one**, and a component likewise. That is what keeps the tree a navigation aid rather
   * than a wall — compose-m3's 84 components across twenty families would otherwise all be rows at
   * once — and it matches what the grid beside it is doing, which shows one section at a time.
   */
  private fun StringBuilder.appendGroupRow(
    group: LandingGroup,
    tabSlug: String?,
    anchor: String,
    open: Boolean,
    components: (GridCard) -> TreeComponent,
    /** Extra class for the row's `<li>` — the outline tree's groups are its top-level nodes. */
    liClass: String = "",
  ) {
    val childrenId = "cp-tree-of-$anchor"
    val tabAttr = tabSlug?.let { " data-tab=\"$it\"" } ?: ""
    val expanded = if (open) "true" else "false"
    val li =
      if (liClass.isEmpty()) "<li role=\"none\">" else "<li class=\"$liClass\" role=\"none\">"
    append("    $li<a class=\"cp-tree-group cp-tree-link\" role=\"treeitem\"")
    append(" href=\"#$anchor\"$tabAttr data-group=\"$anchor\"")
    if (group.cards.isNotEmpty()) {
      append(" aria-expanded=\"$expanded\" aria-owns=\"$childrenId\"")
    }
    append(">")
    append(WebEscaping.htmlEscape(group.name ?: "Ungrouped"))
    append("<span class=\"cp-tree-count\">${group.cards.size}</span></a>\n")
    if (group.cards.isEmpty()) {
      append("</li>\n")
      return
    }
    append("      <ul class=\"cp-tree-children cp-tree-components\" id=\"$childrenId\"")
    append(" role=\"group\">\n")
    group.cards.forEach { card ->
      val c = components(card)
      appendComponentRow(
        label = c.label,
        // On the landing every row is an in-page jump: the component row and the synthetic Default
        // row both target the card the grid is already showing, which is what `data-group` drives.
        href = "#${c.anchorId}",
        rowAttrs = "$tabAttr data-group=\"${c.anchorId}\"",
        defaultHref = "#${c.anchorId}",
        defaultRowAttrs = "$tabAttr data-group=\"${c.anchorId}\"",
        variants = c.variants,
        variantsId = "cp-tree-of-${c.anchorId}",
        indent = "        ",
      )
    }
    append("      </ul>\n    </li>\n")
  }

  /**
   * One component row plus its variant children — the tree's leaf shape, shared by the landing's
   * whole-catalog tree and the viewer's single-component subtree so the two cannot drift into
   * looking like different things.
   *
   * What differs between the two callers is only where the rows *point* and whether they start
   * open. On the landing they are in-page jumps to a card in the grid beside them, and a component
   * ships collapsed because eighty-four of them are on screen at once. In the viewer each row is a
   * real navigation to that render's own page, the list is open (there is exactly one component),
   * and [currentHref] marks the render being viewed.
   */
  private fun StringBuilder.appendComponentRow(
    label: String,
    href: String,
    variants: List<TreeVariant>,
    variantsId: String,
    defaultHref: String,
    rowAttrs: String = "",
    defaultRowAttrs: String = "",
    /** Collapsed by default; the viewer's subtree opens, having only one component to show. */
    collapsed: Boolean = true,
    /**
     * Whether to lead the children with a synthetic **Default** row pointing at [defaultHref].
     *
     * The landing needs it: there the component row is an in-page jump to a card, not a render, so
     * without this row the default has no entry of its own. The viewer does not, because there the
     * component row IS the default render — a `Default` child beneath it would be a second row with
     * the same href and the same destination, which is the duplication this flag exists to avoid.
     */
    syntheticDefaultRow: Boolean = true,
    /** The row whose href matches is `aria-current="page"` — the render on screen. */
    currentHref: String? = null,
    indent: String = "        ",
  ) {
    fun current(target: String) = if (target == currentHref) " aria-current=\"page\"" else ""
    // The component row can itself be current — in the viewer it IS the default render, the rows
    // under it being the other ones. Nothing double-marks, because a caller that folds the default
    // into this row also drops it from [variants]; a caller that keeps a synthetic Default row
    // (the landing) passes no [currentHref] at all.
    append("$indent<li role=\"none\"><a class=\"cp-tree-component cp-tree-link\"")
    append(" role=\"treeitem\" href=\"${WebEscaping.htmlEscape(href)}\"$rowAttrs")
    if (variants.isNotEmpty()) {
      append(" aria-expanded=\"${!collapsed}\" aria-owns=\"$variantsId\"")
    }
    append(current(href))
    append(">")
    append(WebEscaping.htmlEscape(label))
    if (variants.isNotEmpty()) {
      // +1 for the default render either way: the landing lists it as the synthetic child row
      // below, the viewer folds it into this row.
      append("<span class=\"cp-tree-count\">${variants.size + 1}</span>")
    }
    append("</a>\n")
    if (variants.isNotEmpty()) {
      append("$indent  <ul class=\"cp-tree-children cp-tree-variants\" id=\"$variantsId\"")
      append(" role=\"group\">\n")
      // The default render leads, so the list reads as "the component, then how else it renders"
      // rather than starting at an exceptional state.
      if (syntheticDefaultRow) {
        append("$indent    <li role=\"none\"><a class=\"cp-tree-variant cp-tree-link\"")
        append(" role=\"treeitem\" href=\"${WebEscaping.htmlEscape(defaultHref)}\"$defaultRowAttrs")
        append("${current(defaultHref)}>Default</a></li>\n")
      }
      variants.forEach { v ->
        // A variant is folded out of the grid, so unlike the rows above it has nowhere on the
        // landing page to jump to — its href is a real navigation, left to the browser.
        append("$indent    <li role=\"none\"><a class=\"cp-tree-variant cp-tree-link\"")
        append(" role=\"treeitem\" href=\"${WebEscaping.htmlEscape(v.href)}\"${current(v.href)}>")
        append(WebEscaping.htmlEscape(v.label))
        append("</a></li>\n")
      }
      append("$indent  </ul>\n")
    }
    append("$indent</li>\n")
  }

  /** The id of the sub-group divider a tree row jumps to — its section's slug, then its own. */
  private fun groupAnchorId(sectionSlug: String, groupSlug: String) =
    "cp-group-$sectionSlug-$groupSlug"

  /**
   * The id of the grid card a component row jumps to. Preview ids are already slug-shaped
   * (`button-filled__ideal__default__light`), but they are catalog data rather than something this
   * page mints, so anything outside the HTML-id alphabet is folded to `-`.
   */
  private fun cardAnchorId(previewId: String) =
    "cp-card-" +
      previewId
        .map { if (it.isLetterOrDigit() || it == '_' || it == '-') it else '-' }
        .joinToString("")

  /**
   * One anchor per card, minted once for the whole page.
   *
   * Two things depend on this happening in a single place. The grid and the tree must not compute
   * the anchor differently — they name the same element. And the anchors have to be **injective**:
   * [cardAnchorId] folds everything outside the HTML-id alphabet to `-`, and a preview id may
   * legitimately contain `/`, `?`, `#` or a space, so `Foo/Bar` and `Foo?Bar` would otherwise mint
   * one id for two cards and `getElementById` would send both rows — and both fragment URLs — to
   * whichever card came first. A collision takes a numeric suffix, exactly as the group slugs do.
   */
  private fun mintCardAnchors(cards: List<GridCard>): Map<String, String> {
    val used = HashSet<String>()
    val out = LinkedHashMap<String, String>()
    cards.forEach { card ->
      val base = cardAnchorId(card.default.id)
      var candidate = base
      var n = 2
      while (!used.add(candidate)) {
        candidate = "$base-$n"
        n++
      }
      out[card.default.id] = candidate
    }
    return out
  }

  /** One **primary-axis** variant of a component: a distinct state or props render. */
  /** [axis] is `"state"` or `"props"` — which of the two primary axes this row varies. */
  private class TreeVariant(val label: String, val href: String, val axis: String = "state")

  /**
   * The viewer's **component subtree**: the same tree the catalog navigates by, filtered to the one
   * component on screen.
   *
   * This replaced two rows of chips — a `State` row and a `Variant` row — that were the viewer's
   * own second opinion about the component's axes. They keyed identically to [primaryVariants], so
   * they always listed the same renders the tree does; they simply said it in a different shape, in
   * a different place, with the two axes torn apart into rows that never named their relationship.
   * A subtree says it once, in the shape the reader already learned on the landing page: the
   * component, then every render under it, the current one marked.
   *
   * Returns "" when the component has no second render — the same silence the chip rows kept, so a
   * single-state component grows no navigation it cannot use.
   */
  /** The component's default render in [current]'s theme lane, or [current] when it has none. */
  private fun componentDefault(
    current: ServePreview,
    all: List<ServePreview>,
    darkFirst: Boolean,
  ): ServePreview {
    val key = componentKey(current)
    val lane = themeLane(current, darkFirst)
    return all
      .filter {
        componentKey(it) == key &&
          themeLane(it, darkFirst) == lane &&
          !isNonDefaultState(it) &&
          !hasNonDefaultProps(it)
      }
      // Authored order decides, not list order: the host lists previews sorted by id, so a
      // component documented at several breakpoints would otherwise root its subtree at whichever
      // size sorts first (`204dp` before `92dp`) rather than at the size its card is drawn at.
      .minByOrNull { it.catalogOrder ?: Int.MAX_VALUE } ?: current
  }

  /**
   * Every render of [current]'s component reachable in ONE hop from where the reader is standing.
   *
   * Two sets, unioned. [primaryVariants] from the component's default is the canonical set the
   * landing tree draws — one axis at a time, which is what keeps that tree navigable across a whole
   * catalog. But a component may bake state × props as a CROSS-PRODUCT, and that set holds one axis
   * at its default while walking the other: from `RTL` it offers no `pressed + RTL`, and since the
   * grid folds both axes out, the combination would be reachable from nowhere at all. So the rows
   * relative to [current] — its states holding its props fixed, its props holding its state fixed,
   * exactly how the chip switchers this replaced were keyed — are unioned in, and lead, because
   * they are the moves from *here*.
   *
   * Deduped by href, so a component with only one axis (nearly all of them) gets exactly the
   * canonical list and nothing doubles up.
   */
  private fun componentRenderRows(
    current: ServePreview,
    all: List<ServePreview>,
    darkFirst: Boolean,
    href: (ServePreview) -> String,
  ): List<TreeVariant> {
    val lane = themeLane(current, darkFirst)
    // Collected as previews, not as finished rows: whether a row can be labelled by ONE axis is a
    // property of the whole set (see [variantLabel]), so nothing can be named until both passes
    // have run.
    val rows = LinkedHashMap<String, Pair<ServePreview, String>>()
    // This render's own state axis, holding its props fixed.
    val stateKey = switcherStateKey(current)
    val byState = LinkedHashMap<String, ServePreview>()
    for (p in all) {
      if (switcherStateKey(p) != stateKey || themeLane(p, darkFirst) != lane) continue
      byState.putIfAbsent(p.state ?: "default", p)
    }
    // …and its props axis, holding its state fixed.
    val propsKey = switcherPropsKey(current)
    val curState = current.state ?: "default"
    val byProps = LinkedHashMap<String, ServePreview>()
    for (p in all) {
      if (switcherPropsKey(p) != propsKey || themeLane(p, darkFirst) != lane) continue
      if ((p.state ?: "default") != curState) continue
      byProps.putIfAbsent(propsSignature(p.props), p)
    }
    // …and its size axis, holding state and props fixed — [sizeInvariantKey] strips only the size,
    // so a reader on `no-buttons` is offered the other breakpoints OF `no-buttons`.
    val sizeKey = switcherSizeKey(current)
    val bySize = LinkedHashMap<String, ServePreview>()
    for (p in all) {
      if (p.size == null) continue
      if (switcherSizeKey(p) != sizeKey || themeLane(p, darkFirst) != lane) continue
      bySize.putIfAbsent(p.size, p)
    }
    if (byState.size > 1) {
      byState.entries
        .sortedBy { if (it.key == "default") 0 else 1 }
        .forEach { (_, p) -> rows.putIfAbsent(href(p), p to "state") }
    }
    if (byProps.size > 1) {
      byProps.entries
        .sortedBy { if (it.key == "") 0 else 1 }
        .forEach { (_, p) -> rows.putIfAbsent(href(p), p to "props") }
    }
    // Sizes last, and in the catalog's declared order rather than sorted: the export writes a
    // component's images in `breakpoints` order, so first-seen IS smallest-to-largest as the
    // catalog declares it, and re-sorting here would invent an ordering the spec did not ask for.
    if (bySize.size > 1) {
      bySize.forEach { (_, p) -> rows.putIfAbsent(href(p), p to "size") }
    }
    // Then the component's canonical set, for everything the two axes above did not already reach.
    primaryVariantPreviews(componentDefault(current, all, darkFirst), all, darkFirst).forEach {
      (p, axis) ->
      rows.putIfAbsent(href(p), p to axis)
    }
    // Both axes in play ⇒ every row names both coordinates. Otherwise a row that resets the state
    // and a row that resets the props are both "Default", and the render on screen is labelled by
    // whichever pass reached it first — `Pressed` for something that is Pressed AND RTL.
    val crossProduct = byState.size > 1 && byProps.size > 1
    return rows.values.map { (p, axis) ->
      TreeVariant(variantLabel(p, axis, crossProduct), href(p), axis)
    }
  }

  private fun componentSubtreeHtml(
    preview: ServePreview,
    siblings: List<ServePreview>,
    basePath: String,
    q: String,
    darkFirst: Boolean,
  ): String {
    fun href(p: ServePreview) = "$basePath/p/${WebEscaping.urlEncodeSegment(p.id)}$q"
    // The subtree hangs off the component's DEFAULT render, whichever of its renders is on screen:
    // arriving on `disabled` must not re-root the tree at `disabled` and hide the rest. Held to the
    // current theme lane so navigating within a dark catalog stays dark, exactly as the chip rows
    // and the component nav already do.
    val default = componentDefault(preview, siblings, darkFirst)
    val rows = componentRenderRows(preview, siblings, darkFirst, ::href)
    // The render ON SCREEN is always a row, even when neither axis set would have listed it. A
    // catalog can carry a variant whose axis lives only in its id — `…__default__light__
    // content-icon-label` with no `props` metadata — and such a render belongs to no axis, so a
    // subtree built from the axes alone would show the reader every render of this component
    // except the one they are looking at, with nothing marked current. A tree that says "this
    // component's renders" has to contain the page it is drawn on.
    val withCurrent =
      if (rows.any { it.href == href(preview) } || preview.id == default.id) rows
      else rows + TreeVariant(previewDisplayName(preview), href(preview), "props")
    // The DEFAULT render is the component row, not a child of it. Both pointed at the same href —
    // the same page, reached two ways, one line apart — and the child said "Default" directly under
    // a row already naming that render. Folding it up leaves the tree saying each render once: the
    // component, then the ways it differs.
    val variants = withCurrent.filterNot { it.href == href(default) }
    if (variants.isEmpty()) return ""
    return buildString {
      append("<nav class=\"cp-tree cp-axes-tree\" aria-label=\"Component renders\">\n")
      append("  <ul class=\"cp-tree-list\" role=\"tree\">\n")
      appendComponentRow(
        label = previewDisplayName(default),
        href = href(default),
        variants = variants,
        variantsId = "cp-axes-tree-variants",
        defaultHref = href(default),
        // One component, already chosen — a collapsed subtree would be a disclosure inside a
        // disclosure, and the outer one is the control that decides whether any of this shows.
        collapsed = false,
        syntheticDefaultRow = false,
        currentHref = href(preview),
        indent = "    ",
      )
      append("  </ul>\n</nav>")
    }
      .trimEnd()
  }

  /**
   * A component's primary-axis variants — the renders the grid folds out so a component shows one
   * card, listed here so the tree can offer them without a visit to the viewer to discover they
   * exist.
   *
   * **Primary** is `state` (disabled, pressed, checked) and `props` (with icon, RTL, large font):
   * axes where the variant is a different *thing to look at*. Theme, breakpoint, fontScale and
   * locale are **secondary** — a different rendering of the same thing — and stay out of the tree,
   * theme because the card already swaps it in place, the rest because they multiply every row by a
   * matrix nobody navigates by.
   *
   * The viewer's own subtree ([componentSubtreeHtml]) is built from this same function, so the two
   * cannot offer different sets: one definition of what a component's renders are, drawn twice.
   */
  private fun primaryVariants(
    default: ServePreview,
    all: List<ServePreview>,
    darkFirst: Boolean,
    href: (ServePreview) -> String,
  ): List<TreeVariant> =
    primaryVariantRows(default, all, darkFirst).map { (p, axis) ->
      TreeVariant(variantLabel(p, axis, crossProduct = false), href(p), axis)
    }

  private fun primaryVariantRows(
    default: ServePreview,
    all: List<ServePreview>,
    darkFirst: Boolean,
  ): List<Pair<ServePreview, String>> {
    val lane = themeLane(default, darkFirst)
    val defaultState = default.state ?: "default"
    val rows = mutableListOf<Pair<ServePreview, String>>()
    // States first: the axis a component varies on most, and the one a reviewer looks for.
    val stateKey = switcherStateKey(default)
    val seenStates = LinkedHashMap<String, ServePreview>()
    for (p in all) {
      if (switcherStateKey(p) != stateKey) continue
      if (themeLane(p, darkFirst) != lane) continue
      if (!hasNonDefaultProps(p) && isNonDefaultState(p)) {
        seenStates.putIfAbsent(p.state!!, p)
      }
    }
    seenStates.forEach { (_, p) -> rows.add(p to "state") }
    // Then the props axis, held at the component's default state so a row never crosses two axes.
    val propsKey = switcherPropsKey(default)
    val seenProps = LinkedHashMap<String, ServePreview>()
    for (p in all) {
      if (switcherPropsKey(p) != propsKey) continue
      if (themeLane(p, darkFirst) != lane) continue
      if ((p.state ?: "default") != defaultState) continue
      if (hasNonDefaultProps(p)) seenProps.putIfAbsent(propsSignature(p.props), p)
    }
    seenProps.forEach { (_, p) -> rows.add(p to "props") }
    return rows
  }

  /**
   * A row's label. Normally it names only the axis the row moves along — a component varies on one
   * axis and repeating the other's default on every row would be noise. But when BOTH axes are in
   * play the single label is ambiguous rather than terse: from `pressed + RTL`, the row resetting
   * the state (`default + RTL`) and the row resetting the props (`pressed + default`) are both
   * "Default", two different renders wearing one name. Naming both coordinates is what tells them
   * apart, and it also stops a cross-product row being labelled by whichever axis pass happened to
   * reach it first.
   */
  private fun variantLabel(p: ServePreview, axis: String, crossProduct: Boolean): String =
    // A size row names its breakpoint whatever else is in play: it moves along neither of the two
    // axes [crossProduct] disambiguates, and the size is the only thing that tells it from the row
    // the reader is standing on.
    if (axis == "size") sizeLabel(p) ?: stateLabel(p.state)
    else if (!crossProduct) if (axis == "state") stateLabel(p.state) else propsLabel(p.props)
    else "${stateLabel(p.state)} · ${propsLabel(p.props)}"

  /** [primaryVariants] as previews paired with the axis each varies, before they are labelled. */
  private fun primaryVariantPreviews(
    default: ServePreview,
    all: List<ServePreview>,
    darkFirst: Boolean,
  ): List<Pair<ServePreview, String>> = primaryVariantRows(default, all, darkFirst)

  /** Prettier display names for a few component families whose bare title-case reads badly. */
  private val FAMILY_DISPLAY_NAMES =
    mapOf(
      "fab" to "FAB",
      "textfield" to "Text fields",
      "radiobutton" to "Radio buttons",
      "segmentedbutton" to "Segmented buttons",
    )

  /**
   * The component **family** a card belongs to — the first token of its [componentKey] slug head
   * (`button-filled` → `button`, `textfield-outlined` → `textfield`, `badge` → `badge`). Used only
   * as a *fallback* grouping for a catalog that authored no [sections][ServePreview.section].
   */
  private fun cardFamily(card: GridCard): String =
    componentKey(card.default).substringBefore("__").substringBefore('-').ifBlank {
      componentKey(card.default)
    }

  /** A human family heading: a curated name, else the token title-cased (`switch` → `Switch`). */
  private fun familyDisplayName(family: String): String =
    FAMILY_DISPLAY_NAMES[family] ?: family.replace('-', ' ').replaceFirstChar { it.uppercaseChar() }

  /**
   * Prefer catalog-authored labels; turn generated ids into readable component names as fallback.
   */
  private fun previewDisplayName(preview: ServePreview): String {
    preview.componentId
      ?.takeIf { it.isNotBlank() }
      ?.let {
        return humanizeComponentId(it)
      }
    if (preview.label.isNotBlank() && preview.label != preview.id) return preview.label
    return componentKey(preview).substringBefore("__").replace('-', ' ').replaceFirstChar {
      it.uppercaseChar()
    }
  }

  /** Splits catalog identifiers without changing their stable route-safe preview ids. */
  private fun humanizeComponentId(componentId: String): String =
    componentId
      .replace(Regex("[/_-]+"), " ")
      .replace(Regex("(?<=[a-z0-9])(?=[A-Z])"), " ")
      .replace(Regex("(?<=[A-Z])(?=[A-Z][a-z])"), " ")
      .trim()
      .replace(Regex("\\s+"), " ")

  /** A compact human label for the size/breakpoint token carried in a flattened catalog id. */
  private fun previewSizeVariantLabel(id: String): String? =
    id.split("__").asReversed().firstNotNullOfOrNull { token ->
      when (token.lowercase()) {
        "compact" -> "Compact"
        "expanded" -> "Expanded"
        "smallround" -> "Small Round"
        "largeround" -> "Large Round"
        "xlround" -> "XL Round"
        else -> null
      }
    }

  /**
   * A **synthesized** sub-grouping for a section-less catalog: bucket [cards] by [cardFamily] so
   * the flat grid gains labelled dividers (Buttons, Cards, Text fields, …) like an authored catalog
   * — the fix for a large first-party catalog (compose-m3's 84 tiles) rendering as one undivided
   * wall. Purely a fallback: a catalog that authored its own sections goes through [buildSections]
   * and never reaches here.
   *
   * Returns null (⇒ keep the plain flat grid) unless the grouping is actually *useful*: it needs at
   * least two families AND at least one family with more than one card — otherwise every card would
   * get its own lone header, which is noisier than no grouping at all. Families keep first-seen
   * (catalog) order; cards keep their order within a family.
   */
  private fun synthesizeGroups(cards: List<GridCard>): List<LandingGroup>? {
    if (cards.size < 2) return null
    val byFamily = LinkedHashMap<String, LandingGroup>()
    for (card in cards) {
      byFamily
        .getOrPut(cardFamily(card)) { LandingGroup(familyDisplayName(cardFamily(card))) }
        .cards
        .add(card)
    }
    if (byFamily.size < 2 || byFamily.values.none { it.cards.size > 1 }) return null
    return byFamily.values.toList()
  }

  /**
   * The sticky **Theme** control for the catalog header — every theme the catalog configures, not
   * just the built-in light/dark axis (issue #2881).
   *
   * Two kinds of chip sit on the same axis, so a visitor picks *a theme* rather than juggling two
   * controls:
   * - the **baked** light/dark pair ([hasBaked]) — an instant, client-side swap between the two
   *   renders the catalog already published (`data-theme-choice="light"` / `"dark"`);
   * - each app-**declared** `@ThemeCatalog` / `@WearThemeCatalog` theme ([declared]) —
   *   `data-theme-choice="theme:<providerFqn>"`, which re-points every daemon-twinned card's
   *   thumbnail at `/render/<id>.png?themeProvider=<fqn>` so the grid redraws under that theme.
   *
   * A catalog with no baked pair still gets a leading `default` chip so the declared themes have
   * something to return to. Persists to the catalog-scoped localStorage key (shared with that
   * catalog's viewer Theme select, which ignores the `theme:` values it doesn't understand).
   * Progressive enhancement throughout — a no-JS client sees the full grid on its baked renders.
   */
  private fun themePickerHtml(hasBaked: Boolean, declared: List<ServeTheme>): String {
    val builtIns =
      if (hasBaked) listOf("light" to "Light", "dark" to "Dark") else listOf("default" to "Default")
    val chips = themeChipsHtml(builtIns, declared, indent = "            ")
    // THE VIEWER'S Theme control, not a second one that looks like it. The landing used to lay its
    // chips out as a wrapping row above 640px and fold them behind a pill only on a phone, so the
    // same catalog's two pages offered the same choice through two different affordances: a row of
    // a dozen chips here, one labelled dropdown on the component page. Below the fold that was also
    // the landing's tallest piece of chrome — a design system declaring a dozen themes spent three
    // or four rows of the toolbar naming them, on every viewport, whether or not anyone was
    // switching.
    //
    // So this is `.cp-theme-menu` + `.cp-theme-menu-panel`, the same two classes `viewerPage`
    // emits, with the same chips from [themeChipsHtml] inside: one pill that names the theme in
    // force, opening onto a column of full-width rows. All of the panel's styling is already in the
    // sheet for the viewer, and `<cp-catalog-toolbar>` closes it on a pick exactly as
    // `<cp-viewer-drawers>` does.
    //
    // The pill's value is seeded with the leading built-in and then mirrored from whichever chip is
    // pressed (`<cp-catalog-toolbar>`), because the choice in force is remembered in `localStorage`
    // and changes without a page load — a server-rendered label alone would be wrong on arrival for
    // anyone who has picked a theme here before.
    val seed = builtIns.first().second
    return """
    <div class="cp-toolbar">
      <details class="cp-theme-menu cp-catalog-theme">
        <summary class="cp-drawer-toggle cp-axis-toggle" aria-controls="cp-catalog-theme-bar">
          <span class="cp-toggle-label">Theme</span>
          <span class="cp-toggle-value" id="cp-catalog-theme-value">$seed</span>
          <span class="cp-theme-caret" aria-hidden="true">▾</span>
        </summary>
        <div class="cp-theme-menu-panel">
          <span class="cp-theme cp-theme-bar" id="cp-catalog-theme-bar" role="group" aria-label="Preview theme">
            $chips
          </span>
        </div>
      </details>
    </div>
    """
      .trimIndent()
  }

  /**
   * The theme chips themselves, shared by the landing picker ([themePickerHtml]) and the viewer bar
   * ([viewerThemePickerHtml]) so one control appears on both pages instead of two that drift.
   *
   * [builtIns] are the `(choice value, label)` pairs the page offers before any app-declared theme
   * — the landing's baked `light`/`dark` swap (or its lone `default`), the viewer's Light/Dark
   * uiMode pair (or Dark alone on a dark-first system). [declared] follows as one
   * `theme:<providerFqn>` chip each, qualified with its group when its bare name would collide with
   * a built-in label or with another declared theme.
   */
  private fun themeChipsHtml(
    builtIns: List<Pair<String, String>>,
    declared: List<ServeTheme>,
    /** Indentation for every chip after the first, so the emitted block reads as written HTML. */
    indent: String = "        ",
  ): String {
    val builtInLabels = builtIns.map { it.second.lowercase() }.toSet() + builtIns.map { it.first }
    val declaredNameCounts = declared.groupingBy { it.name.lowercase() }.eachCount()
    return buildString {
      builtIns.forEachIndexed { index, (value, label) ->
        if (index > 0) append("\n$indent")
        append("<button type=\"button\" class=\"cp-theme-btn\" data-theme-choice=\"$value\">")
        append("$label</button>")
      }
      declared.forEach { t ->
        val qualified =
          t.name.lowercase() in builtInLabels || declaredNameCounts.getValue(t.name.lowercase()) > 1
        val displayName =
          if (qualified) "${t.group?.takeIf { it.isNotBlank() } ?: "Custom"} · ${t.name}"
          else t.name
        val label = WebEscaping.htmlEscape(displayName)
        val title =
          t.group
            ?.takeIf { !qualified }
            ?.let { " title=\"${WebEscaping.htmlEscape(it)} · $label\"" } ?: ""
        append("\n$indent<button type=\"button\" class=\"cp-theme-btn\"")
        val modeAttr = t.mode?.let { " data-theme-mode=\"${WebEscaping.htmlEscape(it)}\"" } ?: ""
        append(
          " data-theme-choice=\"theme:${WebEscaping.htmlEscape(t.providerFqn)}\"$modeAttr$title>"
        )
        append("$label</button>")
      }
    }
  }

  /**
   * The **Transparent** toggle: flips the page between the solid stage the previews are normally
   * read on and the transparent checkerboard that shows a sticker's real alpha.
   *
   * One button rather than a Background / Transparent pair — a two-state axis with a default is
   * what `aria-pressed` on a single toggle says, and the pair spent twice the toolbar width to say
   * it while always showing one segment that did nothing when clicked.
   *
   * Emitted identically on the landing grid and on the single-preview viewer — the `<html>` class
   * it drives (`cp-bg-transparent`) already backs both `.cp-imgwrap` and `.cp-stage`, and the
   * pre-paint script in [document] already restores the choice on every page, so the viewer was
   * simply missing the control rather than the behaviour.
   *
   * The button itself is rendered by the `<cp-bg-toggle>` Lit element in `serve-components.js`
   * (source: `cli/serve-web/src/components/BgToggle.ts`), not here — one source of truth for markup
   * a JS-only control owns. `serve.css` gives the element `display: contents`, so the button stays
   * the toolbar's own flex item and lays out exactly as the bare button did.
   */
  private fun bgPickerHtml(title: String): String =
    "<cp-bg-toggle label=\"${WebEscaping.htmlEscape(title)}\"></cp-bg-toggle>"

  /**
   * The search box for the landing grid: a text input that filters cards to those whose label or id
   * contains the typed text. Progressive enhancement — the server emits every card and
   * [catalogFilterScript] does the hiding, so a no-JS client still sees the full grid. Shown
   * whenever the module has previews (independent of the theme toggle, which only appears for
   * per-theme catalogs).
   */
  private fun searchBoxHtml(usesFilter: Boolean = false): String {
    // The `uses:` operator's readout, and the only furniture it adds to the page. Empty and hidden
    // until the operator is typed, so a Dev-mode visitor who never uses it sees the same search bar
    // as before; `role="status"` because what it reports arrives asynchronously, after a keystroke
    // the reader has already finished making.
    //
    // It exists because the landing grid has no count line to borrow — `#cp-count` is emitted by
    // the comparison pages, not this one — and without a readout the operator would narrow the grid
    // with nothing on the page saying why, and an unindexable catalog would look exactly like a
    // catalog where nothing matched.
    val status =
      if (!usesFilter) ""
      else "\n      <p id=\"cp-uses-status\" class=\"cp-uses-status\" role=\"status\" hidden></p>"
    return """
    <div class="cp-searchbar">
      <input id="cp-search" class="cp-search" type="search" placeholder="Filter previews…"
        autocomplete="off" spellcheck="false" aria-label="Filter previews" aria-controls="cp-grid">$status
    </div>
    """
      .trimIndent()
  }

  /**
   * Landing-grid controls: the search box (matches a card's label + id, case-insensitive) and, when
   * the catalog carries light/dark pairs, the sticky Light/Dark **toggle** — which *swaps* each
   * swappable card between its baked light and dark render in place (image, viewer link, id, label,
   * and stage backing), rather than hiding cards. Single-theme / theme-neutral cards carry no swap
   * data and are left untouched. Theme state persists to a catalog-scoped localStorage key
   * (round-tripped with that catalog's viewer Theme select); the search text is ephemeral. Fully
   * client-side progressive enhancement — a no-JS client sees the full grid on its baked (default)
   * renders.
   *
   * When [hasTabs] (a sectioned catalog), the same script also drives the section **tabs**:
   * clicking a tab shows only that section's panel (others' cards hidden) while a search spans
   * every tab (tab selection ignored until the query clears), and empty sub-groups / sections
   * collapse. All tab handling is emitted as inline additions that are empty for a flat catalog, so
   * a section-less catalog's script is byte-for-byte unchanged.
   */
  private fun catalogFilterScript(
    hasThemes: Boolean,
    hasTabs: Boolean,
    hasGroups: Boolean,
    /** Whether a navigation tree is rendered at all — sectioned catalogs AND outline ones. */
    hasTree: Boolean,
    themeStorageKey: String,
    tabStorageKey: String,
    /**
     * Per-card render URL to re-request under a declared theme, in the grid's document order — a
     * **server-emitted** JS array literal (`["/render/a.png?…", "", …]`, `""` for a card the
     * session can't re-render). Emitted rather than read back off the card so no URL the browser
     * assigns to an `<img src>` ever originates as DOM text (CodeQL `js/xss-through-dom`). Empty
     * string ⇒ the catalog offers no declared themes and none of the theme-render machinery is
     * emitted at all.
     */
    themeBaseJs: String = "",
    themeLeaseUrl: String = "",
    /**
     * `POST` URL that tells the server a visitor is still on this page ([presenceScript]). Empty
     * omits the heartbeat entirely — the default, so a fixture golden or a plain-module landing
     * emits exactly the script it always did.
     */
    presenceUrl: String = "",
    /**
     * Whether the sidebar carries the Components/Pages pane strip ([paneTabsHtml]). False for every
     * catalog without design pages, which then emits this script byte-for-byte as before.
     */
    hasPanes: Boolean = false,
    /**
     * Whether the tree leads with the **All** row ([ALL_TAB]) — every sectioned catalog with more
     * than one section. False leaves out every mention of `all`, so a single-section catalog emits
     * the script it always did.
     */
    hasAllTab: Boolean = false,
    /**
     * Endpoint for the Dev-mode `uses:` operator ([usesFilterScript]). Empty — the default, and
     * every Catalog-mode render — leaves out every mention of it, so that presentation emits the
     * script byte-for-byte as before.
     */
    usesUrl: String = "",
  ): String {
    // Declared before anything that branches on it — the tab predicate and the pane split below
    // both ask whether the operator is wired.
    val usesFilter = usesUrl.isNotEmpty()
    val hasDeclaredThemes = themeBaseJs.isNotEmpty()
    val themeLeaseUrlJs = WebEscaping.jsString(themeLeaseUrl)
    // Spliced one level in, so a page with no presence URL emits the script byte-for-byte as
    // before.
    val presenceWiring =
      presenceScript(presenceUrl).let { script ->
        if (script.isEmpty()) ""
        else script.lines().joinToString("") { if (it.isEmpty()) "\n" else "\n      $it" }
      }
    // The stored choice is one of `light` / `dark` (a baked swap), `default` (the catalog's own
    // renders), or `theme:<providerFqn>` (an app-declared @ThemeCatalog theme, applied by
    // re-pointing each daemon-twinned card's thumbnail at a `?themeProvider=` render).
    val themeInit =
      if (hasThemes)
        """
        var stored = null;
        try { stored = localStorage.getItem("$themeStorageKey"); } catch (e) {}
        var themeBtns = document.querySelectorAll(".cp-theme-btn");
        function chipOffered(t) {
          var offered = false;
          themeBtns.forEach(function (b) { if (b.getAttribute("data-theme-choice") === t) offered = true; });
          return offered;
        }
        // The GRID always opens on published pixels. A stored app-declared theme
        // (`theme:<providerFqn>`) is deliberately NOT replayed here: restoring it would re-point
        // every card at a `?themeProvider=` render and put the whole grid through the daemon on
        // what is meant to be a default page view — the single most expensive thing an idle box
        // can be made to do, and it happened on every return visit. Only the baked chips, whose
        // pixels are already published, are restored. Stickiness for app-declared themes belongs
        // to the individual preview, which reads this same key for its own Theme select.
        function validTheme(t) {
          if (!t) return false;
          return t === "light" || t === "dark" || t === "default";
        }
        var theme = validTheme(stored) ? stored
          : (window.matchMedia && window.matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light");
        // A chip is only offered when the page rendered it, so a remembered choice this catalog no
        // longer configures (a theme the app dropped) falls back to the first chip.
        var known = false;
        themeBtns.forEach(function (b) { if (b.getAttribute("data-theme-choice") === theme) known = true; });
        if (!known && themeBtns.length) theme = themeBtns[0].getAttribute("data-theme-choice");
        // The URL wins over both. `?theme=` is on the address bar only because someone picked that
        // chip (or was handed the link), which makes it the one case where replaying an app-declared
        // theme IS what was asked for — the cost the stored-value rule above avoids is the cost of
        // an *unrequested* grid re-render, not of honouring an explicit link. An unknown value (a
        // theme this catalog no longer publishes) is ignored, exactly like a stale stored one.
        var urlTheme = urlParam("theme");
        if (urlTheme && chipOffered(urlTheme)) theme = urlTheme;
        // What the page falls back to when Back lands on an entry with no `?theme=` — the choice
        // this load resolved to, never the localStorage value a later click overwrote.
        var initialTheme = theme;
        var appliedTheme = null;
        """
          .trimIndent()
      else ""
    // The declared-theme lane is serial unless the server grants this page a short-lived claim on
    // its catalog's burst allocation. All users and tabs for that catalog share the same width, so
    // opening another page cannot multiply a five-worker burst into a JVM storm. Each worker
    // advances only after its image settles; failures get bounded delayed retries with
    // cache-busting URLs. Baked light/dark swaps never queue. themeGen abandons all workers the
    // moment a new theme is chosen and releases that generation's lease.
    val themeRenderInit =
      if (hasDeclaredThemes)
        """
        var themeBase = $themeBaseJs;
        var themeGen = 0;
        var themeLeaseUrl = $themeLeaseUrlJs;
        // Every claim this page currently holds. A list, not one slot: the visible batch and each
        // deferred batch take their own claim on the catalog's allocation, and abandoning a
        // generation (or leaving the page) has to hand back ALL of them — a single slot silently
        // dropped every claim but the last, leaving the catalog's burst width tied up until its
        // TTL ran out.
        var themeLeases = [];
        var themeRenderRetries = 3;
        function releaseThemeLease(lease, beacon) {
          if (!lease || !themeLeaseUrl) return;
          var held = themeLeases.indexOf(lease);
          if (held !== -1) themeLeases.splice(held, 1);
          var queryAt = themeLeaseUrl.indexOf("?");
          var url = queryAt === -1
            ? themeLeaseUrl + "/release"
            : themeLeaseUrl.slice(0, queryAt) + "/release" + themeLeaseUrl.slice(queryAt);
          url += (url.indexOf("?") === -1 ? "?" : "&") + "lease=" + encodeURIComponent(lease);
          if (beacon && navigator.sendBeacon) navigator.sendBeacon(url, "");
          else fetch(url, { method: "POST", credentials: "same-origin", keepalive: true }).catch(function () {});
        }
        function acquireThemeLease(gen, callback) {
          if (!themeLeaseUrl) { callback(null, 1); return; }
          fetch(themeLeaseUrl, { method: "POST", credentials: "same-origin" })
            .then(function (response) { return response.ok ? response.json() : null; })
            .then(function (grant) {
              var lease = grant && typeof grant.lease === "string" ? grant.lease : null;
              var concurrency = grant && Number.isFinite(grant.concurrency)
                ? Math.max(1, Math.min(5, grant.concurrency)) : 1;
              if (gen !== themeGen) { releaseThemeLease(lease, false); return; }
              if (lease) themeLeases.push(lease);
              callback(lease, concurrency);
            })
            .catch(function () { if (gen === themeGen) callback(null, 1); });
        }
        function releaseAllThemeLeases(beacon) {
          themeLeases.slice().forEach(function (lease) { releaseThemeLease(lease, beacon); });
        }
        // Point a batch's jobs at the claim that admits them. Any earlier token is dropped first:
        // a job re-queued under a fresh claim must not carry two.
        function stampThemeLease(jobs, lease) {
          jobs.forEach(function (job) {
            job.baseSrc = job.baseSrc.replace(/&_themeLease=[^&]*/, "") +
              "&_themeLease=" + encodeURIComponent(lease);
            job.src = job.baseSrc;
            job.retries = 0;
          });
        }
        function finishThemeJob(batch) {
          batch.remaining--;
          if (batch.remaining === 0) releaseThemeLease(batch.lease, false);
        }
        function clearThemeError(card) {
          card.classList.remove("cp-theme-render-error");
          var error = card.querySelector(".cp-theme-error");
          if (error) error.remove();
        }
        function showThemeError(card, terminal) {
          clearThemeError(card);
          card.classList.add("cp-theme-render-error");
          var error = document.createElement("span");
          error.className = "cp-theme-error";
          error.setAttribute("role", "status");
          // Two different facts, and conflating them is misleading: a retryable failure really may
          // resolve on the next attempt, while a terminal one (the server latched this preview as
          // unrenderable) never will. Saying "unavailable" for both left a permanently broken card
          // looking like it was still loading.
          error.textContent = terminal
            ? "This preview can't render live"
            : "Theme preview unavailable";
          var wrap = card.querySelector(".cp-imgwrap");
          if (wrap) wrap.appendChild(error);
        }
        function runThemeWorker(queue, gen, batch) {
          if (gen !== themeGen) return;
          var job = queue.shift();
          if (!job) return;
          // A deferred card is not loading yet: it deliberately has no request until its tab or
          // viewport reaches it. Mark it busy only when a worker actually starts the fetch. Doing
          // this while every job was being classified left hidden-tab cards aria-busy forever,
          // making a completed cold daemon burst look as though it had never woken the page.
          clearThemeError(job.card);
          job.card.classList.add("cp-reloading");
          job.card.setAttribute("aria-busy", "true");
          var img = job.img;
          var settled = false;
          function finish(ok, terminal) {
            if (settled || gen !== themeGen) return;
            settled = true;
            if (!ok && !terminal && job.retries < themeRenderRetries) {
              // Re-request rather than re-assign the identical (failed, uncached) URL. Exponential
              // backoff gives a busy daemon time to finish without stalling the other worker.
              job.retries++;
              job.src = job.baseSrc + "&_retry=" + job.retries;
              setTimeout(function () {
                if (gen !== themeGen) return;
                queue.push(job);
                runThemeWorker(queue, gen, batch);
              }, 1000 * Math.pow(2, job.retries));
              return;
            }
            job.card.classList.remove("cp-reloading");
            job.card.removeAttribute("aria-busy");
            if (!ok) showThemeError(job.card, terminal);
            finishThemeJob(batch);
            runThemeWorker(queue, gen, batch);
          }
          // Fetch the bytes FIRST and only then put them on the card.
          //
          // Assigning `src` on the live <img> dropped the pixels it was showing the instant the
          // request started, so the visitor watched the old theme's render vanish and sat looking
          // at a broken-image glyph under the spinner for the whole ~1s daemon round trip. Holding
          // the previous render until the new one is in hand means the card only ever shows real
          // pixels: the old theme's, then the new theme's, swapped in a single paint.
          //
          // A detached `new Image()` preload would do the same job, except a themed render is
          // `no-store` (it carries overrides), so handing its URL to the visible <img> afterwards
          // is not reliably a cache hit and can cost a second round trip. Fetching to a blob is one
          // request by construction.
          fetch(job.src, { credentials: "same-origin" })
            .then(function (response) {
              // 409 is the server saying it has permanently given up on this preview, not that it
              // is busy. Retrying it three times with backoff only occupies a worker that the rest
              // of the grid needs, so retire the job on the spot.
              if (response.status === 409) {
                var terminal = new Error("render 409");
                terminal.cpTerminal = true;
                throw terminal;
              }
              if (!response.ok) throw new Error("render " + response.status);
              return response.blob();
            })
            .then(function (blob) {
              if (gen !== themeGen) return;
              var url = URL.createObjectURL(blob);
              // Release the blob this card was holding, if any. Without this every theme switch
              // would strand one object URL per card for the life of the page.
              var previous = img.getAttribute("data-cp-blob");
              img.src = url;
              img.setAttribute("data-cp-blob", url);
              if (previous) URL.revokeObjectURL(previous);
              finish(true);
            })
            .catch(function (e) { finish(false, !!(e && e.cpTerminal)); });
        }
        function runThemeQueue(queue, gen, lease, concurrency) {
          var batch = { lease: lease, remaining: queue.length };
          if (!batch.remaining) { releaseThemeLease(lease, false); return; }
          var workers = Math.min(concurrency, queue.length);
          for (var i = 0; i < workers; i++) runThemeWorker(queue, gen, batch);
        }
        // Themed renders for cards that are off-screen (or hidden by search / another tab), held
        // until the viewport reaches them. `rootMargin` starts a card a screenful early so scrolling
        // meets finished pixels rather than a spinner.
        //
        // A deferred batch takes a claim of ITS OWN and hands it back when it drains. It wants none
        // of the burst — it runs one card at a time behind the visitor's scroll — but a render sent
        // without a claim queues on the server's single unleased semaphore, shared with every other
        // page. Riding the *visible* batch's token instead (what this used to do) was the bug: that
        // claim is released the moment the on-screen cards finish — immediately, when none were on
        // screen — and every later card then presented a token the server had already reaped and
        // was refused `429` until its retries ran out. Claims for one catalog join one allocation,
        // so asking again is a bookkeeping call, not extra width.
        var themeObserver = null;
        function stopDeferredTheme() {
          if (themeObserver) themeObserver.disconnect();
          themeObserver = null;
        }
        // Whether a card is close enough to the viewport to be worth rendering NOW. This is
        // geometry, deliberately, not `hidden`: on a flat catalog with no search nothing is hidden,
        // so partitioning on `hidden` alone would put all 80+ cards in the leased batch and defer
        // nothing — exactly the case this is here to fix. A zero-size rect (display:none, e.g. a
        // non-current tab panel) is never near the viewport.
        function nearViewport(c) {
          var r = c.getBoundingClientRect();
          if (!r.width && !r.height) return false;
          var h = window.innerHeight || document.documentElement.clientHeight || 0;
          return r.bottom > -400 && r.top < h + 400;
        }
        function runDeferredThemeBatch(jobs, gen) {
          acquireThemeLease(gen, function (lease) {
            if (gen !== themeGen) { releaseThemeLease(lease, false); return; }
            if (lease) stampThemeLease(jobs, lease);
            // One at a time whatever width was granted, and the batch releases the claim itself
            // once its last card settles.
            runThemeQueue(jobs, gen, lease, 1);
          });
        }
        function deferTheme(jobs, gen) {
          if (!jobs.length) return;
          // No IntersectionObserver (old browser): fall back to rendering them, serially, rather
          // than leaving those cards stuck on the wrong theme forever.
          if (!window.IntersectionObserver) { runDeferredThemeBatch(jobs, gen); return; }
          // Both the observer and its worklist are per-generation locals, never shared globals: a
          // callback already queued when the visitor picks another theme must retire ITSELF and
          // touch nothing else. Clearing the live observer or its pending list from a stale
          // callback would strand every not-yet-scrolled card on the previous theme's pixels.
          var pending = jobs.slice();
          var observer = new IntersectionObserver(function (entries) {
            if (gen !== themeGen) { observer.disconnect(); return; }
            var due = [];
            entries.forEach(function (e) {
              if (!e.isIntersecting) return;
              observer.unobserve(e.target);
              for (var i = 0; i < pending.length; i++) {
                if (pending[i].card === e.target) { due.push(pending.splice(i, 1)[0]); break; }
              }
            });
            if (due.length) runDeferredThemeBatch(due, gen);
          }, { rootMargin: "400px" });
          themeObserver = observer;
          jobs.forEach(function (job) { observer.observe(job.card); });
        }
        window.addEventListener("pagehide", function () { releaseAllThemeLeases(true); });
        """
          .trimIndent()
      else ""
    // Swap every swappable card to the chosen theme's baked render (src / viewer href / id / label
    // /
    // stage backing), and light up the pressed button. A card missing the chosen theme is skipped.
    // For a DECLARED theme the light/dark swap stays on the card's server-side default variant
    // (`data-def`) and the render URL grows a `themeProvider` param — applied only to cards the
    // session can actually re-render (`data-theme-live`), so an Android-only variant keeps its
    // baked
    // pixels rather than requesting a render that would ignore the theme.
    // Under a DECLARED theme a swap card keeps its server-side default variant's metadata (label /
    // id / viewer link / stage backing, from `data-def`) — only the pixels come from the themed
    // render — so picking a theme never silently flips the light/dark axis too.
    // On the **All** tab every section is on screen, so the card's own section is never the
    // current tab and this test rejected the whole grid — the visible batch came out empty, the
    // burst lease was released before a single render started, and every deferred card then
    // carried a token the server no longer knew (issue: themed grid stuck on the old pixels).
    // Geometry alone is the right answer there; the tab comparison is for the tabbed views.
    val themeSectionShowing =
      if (hasAllTab)
        "current === \"$ALL_TAB\" || themeSection.getAttribute(\"data-section\") === current"
      else "themeSection.getAttribute(\"data-section\") === current"
    val correctInitialThemeVisibility =
      if (hasTabs)
        "\n            var themeSection = c.closest(\".cp-section\");" +
          "\n            if (themeVisible && themeSection && (!input || input.value.trim() === \"\")) {" +
          "\n              themeVisible = $themeSectionShowing;" +
          "\n            }"
      else ""
    val applyDeclaredTheme =
      if (hasDeclaredThemes)
        """
        var provider = theme.indexOf("theme:") === 0 ? theme.slice(6) : "";
        releaseAllThemeLeases(false);
        themeGen++;
        stopDeferredTheme();
        var themeQueue = [];
        var themeDeferredQueue = [];
        var themeQueueGen = themeGen;
        cards.forEach(function (c) {
          c.classList.remove("cp-reloading");
          c.removeAttribute("aria-busy");
          clearThemeError(c);
        });
        if (provider) {
          cards.forEach(function (c, i) {
            if (c.getAttribute("data-swap") === "1") applyVariant(c, c.getAttribute("data-def") || "l", false);
            var img = c.querySelector("img");
            var base = themeBase[i];
            if (!img || !base) return;
            if (selectedThemeMode) c.setAttribute("data-bg-theme", selectedThemeMode);
            else {
              var bgDefault = c.getAttribute("data-bg-default") || "";
              if (bgDefault) c.setAttribute("data-bg-theme", bgDefault);
              else c.removeAttribute("data-bg-theme");
            }
            var themedSrc = base + (base.indexOf("?") === -1 ? "?" : "&") + "themeProvider=" + encodeURIComponent(provider);
            var job = {
              card: c,
              img: img,
              baseSrc: themedSrc,
              src: themedSrc,
              retries: 0,
            };
            // A tabbed catalog's hidden cards can take several daemon renders before the visitor
            // sees any response to their click. On initial load c.hidden is not assigned yet, so
            // also compare the card's section with the saved current tab. During a live search the
            // existing hidden state already spans tabs and remains authoritative.
            var themeVisible = !c.hidden && nearViewport(c);$correctInitialThemeVisibility
            if (themeVisible) {
              // Visible work is queued now even when the lease falls back to one worker. Mark the
              // whole on-screen batch busy immediately so cards waiting behind that worker cannot
              // pass their old-theme pixels off as finished.
              c.classList.add("cp-reloading");
              c.setAttribute("aria-busy", "true");
              themeQueue.push(job);
            } else {
              themeDeferredQueue.push(job);
            }
          });
          // Off-screen cards are NOT rendered up front. A catalog is commonly 80+ cards and the
          // shared daemon renders them one at a time (~1s each), so draining the whole grid costs a
          // minute of daemon time — most of it for pixels the visitor never scrolls to, while the
          // cards they ARE looking at wait behind them. They queue against the viewport instead and
          // render as they come into view, which is also what makes an emptied search or a newly
          // opened tab render just its own cards.
          acquireThemeLease(themeQueueGen, function (lease, concurrency) {
            // This claim belongs to the ON-SCREEN batch alone, and dies with it. Deferred cards ask
            // for their own when the viewport reaches them (runDeferredThemeBatch) — stamping them
            // here handed them a token that was already released by the time they ran.
            if (lease) stampThemeLease(themeQueue, lease);
            runThemeQueue(themeQueue, themeQueueGen, lease, concurrency);
            deferTheme(themeDeferredQueue, themeQueueGen);
          });
          return;
        }
        """
          .trimIndent()
      else ""
    // Spliced into applyThemeChoice's body (one level in), so a catalog with no declared themes
    // emits the plain baked swap exactly as before.
    val applyDeclaredThemeIndented =
      applyDeclaredTheme.lines().joinToString("") { if (it.isEmpty()) "\n" else "\n          $it" }
    // Leaving a declared theme has to put a NON-swap card back on its baked pixels (a swap card is
    // restored by applyVariant). Its baked URL is the same themeBase entry, minus the override.
    val restoreBakedSrc =
      if (hasDeclaredThemes)
        "\n            var img = c.querySelector(\"img\");" +
          "\n            var base = themeBase[i];" +
          "\n            if (img && base) setCardSrc(img, base);" +
          "\n            var bgDefault = c.getAttribute(\"data-bg-default\") || \"\";" +
          "\n            if (bgDefault) c.setAttribute(\"data-bg-theme\", bgDefault);" +
          "\n            else c.removeAttribute(\"data-bg-theme\");"
      else ""
    val applyTheme =
      if (hasThemes)
        """
        themeBtns.forEach(function (b) {
          b.setAttribute("aria-pressed", b.getAttribute("data-theme-choice") === theme ? "true" : "false");
        });
        var selectedThemeButton = null;
        themeBtns.forEach(function (b) {
          if (b.getAttribute("data-theme-choice") === theme) selectedThemeButton = b;
        });
        var selectedThemeMode = selectedThemeButton
          ? selectedThemeButton.getAttribute("data-theme-mode") || "" : "";
        // Point a swap card at one of its baked variants ("l"/"d"): pixels (unless the caller is
        // supplying themed ones), alt text, label, id, viewer link and stage backing.
        // Point a card's <img> at a plain URL, releasing whatever blob it was holding first.
        // A themed render is handed over as an object URL (see runThemeWorker); leaving a declared
        // theme for Light / Dark / Default replaces that source, and without this the blob behind
        // it would stay resident until the page unloaded — one full-resolution PNG per card, on
        // catalogs that routinely run to 80+ cards.
        function setCardSrc(img, url) {
          var previous = img.getAttribute("data-cp-blob");
          img.src = url;
          if (previous) {
            img.removeAttribute("data-cp-blob");
            URL.revokeObjectURL(previous);
          }
        }
        // The id line elides from the MIDDLE (`iconbutton-standard__…__light`), so it is two spans
        // and a swap re-fills both rather than overwriting the line with one string — which would
        // delete the spans and take the elision with them. Same split rule as the server's
        // `cardIdHtml`: cut at the last `__`, the tail keeps the mode and the scheme.
        function setCardId(idn, id) {
          var head = idn.querySelector(".cp-id-head");
          var tail = idn.querySelector(".cp-id-tail");
          if (!head || !tail) { idn.textContent = id; return; }
          var cut = id.lastIndexOf("__");
          head.textContent = cut > 0 ? id.slice(0, cut) : id;
          tail.textContent = cut > 0 ? id.slice(cut) : "";
        }
        function applyVariant(c, k, withSrc) {
          var src = c.getAttribute("data-" + k + "-src");
          if (!src) return;
          var img = c.querySelector("img");
          var lab = c.querySelector(".cp-label");
          var idn = c.querySelector(".cp-id");
          var lbl = c.getAttribute("data-" + k + "-label");
          if (img) { if (withSrc) setCardSrc(img, src); img.setAttribute("alt", lbl); }
          c.setAttribute("href", c.getAttribute("data-" + k + "-href"));
          c.setAttribute("aria-label", lbl);
          if (lab) { lab.textContent = lbl; lab.setAttribute("title", lbl); }
          if (idn) setCardId(idn, c.getAttribute("data-" + k + "-id"));
          c.setAttribute("data-bg-theme", k === "d" ? "dark" : "light");
        }
        cards.forEach(function (c) {
          c.setAttribute("data-bg-default", c.getAttribute("data-bg-theme") || "");
        });
        // apply() also runs on every search keystroke; re-point the cards only when the THEME
        // actually changed, so typing never restarts an in-flight themed-render queue.
        function applyThemeChoice() {
          if (theme === appliedTheme) return;
          appliedTheme = theme;
          // Turn the page over with the previews: with the Page theme setting on, the chrome
          // follows an explicit Light/Dark pick (the Page theme setting decides; a declared theme leaves it
          // alone). Guarded because that file is deferred to the end of the body.
          if (window.cpPageTheme) window.cpPageTheme.follow(theme);$applyDeclaredThemeIndented
          var k = theme === "dark" ? "d" : "l";
          cards.forEach(function (c, i) {
            if (c.getAttribute("data-swap") === "1") { applyVariant(c, k, true); return; }$restoreBakedSrc
          });
        }
        applyThemeChoice();
        """
          .trimIndent()
      else ""
    val themeWiring =
      if (hasThemes)
        """themeBtns.forEach(function (b) {
        b.addEventListener("click", function () {
          theme = b.getAttribute("data-theme-choice");
          try { localStorage.setItem("$themeStorageKey", theme); } catch (e) {}
          // A discrete pick gets its own history entry, so Back returns to the previous theme
          // rather than leaving the catalog. No navigation: the grid re-points its own images.
          pushUrl({ theme: theme });
          apply();
        });
      });"""
      else ""
    // Tab pieces — each empty for a flat (section-less) catalog and appended INLINE onto an
    // existing
    // line, so the emitted script for a plain catalog is byte-for-byte identical to the pre-tabs
    // one.
    // `cp-js` on <html> hides the redundant per-section <h2> (the tab bar labels the section).
    // A `.cp-subgroup` divider is present for BOTH an authored tabbed catalog and a synthesized
    // flat-grouped one, so its emptied-on-search collapse lives under [hasGroups], separate from
    // the tab-only machinery below.
    val groupDecls =
      if (hasGroups) "\n      var navGroups = document.querySelectorAll(\".cp-subgroup\");" else ""
    // Declared HERE rather than in the tree script, which is spliced in further down: `reflectTabs`
    // runs the moment it is defined and touches `treeGroups`, and a `var` assigned later would only
    // be hoisted, not set.
    // The Components/Pages switch. Declared inside the filter IIFE, like the section tabs, so the
    // pane in view and the query filtering it are one piece of state — a strip that lived in its
    // own script would have to re-enter `apply()` from outside and could disagree with it about
    // which list is on screen.
    val paneDecls =
      if (!hasPanes) ""
      else
        "\n      var paneTabs = document.querySelectorAll(\".cp-pane-tab\");" +
          "\n      var pageRows = document.querySelectorAll(\"#cp-pane-pages .cp-tree-page\");" +
          "\n      var sectionRows = document.querySelectorAll(\"#cp-pane-pages .cp-tree-variant\");" +
          "\n      var pagesEmpty = document.getElementById(\"cp-pages-empty\");" +
          "\n      var pane = urlParam(\"pane\") === \"pages\" ? \"pages\" : \"components\";" +
          // The filter serves whichever pane is showing, so it says which — the box is the same
          // control either way and the placeholder is the only thing that can tell you what it is
          // about to search.
          "\n      function reflectPanes() {" +
          "\n        paneTabs.forEach(function (t) {" +
          "\n          var on = t.getAttribute(\"data-pane\") === pane;" +
          "\n          t.setAttribute(\"aria-selected\", on ? \"true\" : \"false\");" +
          "\n          t.tabIndex = on ? 0 : -1;" +
          "\n          var panel = document.getElementById(t.getAttribute(\"aria-controls\"));" +
          "\n          if (panel) panel.hidden = !on;" +
          "\n        });" +
          "\n        if (input) {" +
          "\n          var what = pane === \"pages\" ? \"pages\" : \"previews\";" +
          "\n          input.placeholder = \"Filter \" + what + \"…\";" +
          "\n          input.setAttribute(\"aria-label\", \"Filter \" + what);" +
          "\n        }" +
          "\n      }"
    val treeDecls =
      if (!hasTree) ""
      else
        "\n      var treeGroups = document.querySelectorAll(\".cp-tree-group\");" +
          "\n      var treeComponents = document.querySelectorAll(\".cp-tree-component\");" +
          "\n      var treeLinks = document.querySelectorAll(\".cp-tree-link\");" +
          // The tree's own roving-tab-stop pass, published so `apply()` can call it from outside
          // the tree script's closure once the filter has changed which rows are on screen.
          "\n      var cpTreeStops = null;"
    // All shows every section's panel at once, and two things follow from that. The tree beside it
    // opens every branch, because it is now the outline of the whole grid rather than of one
    // panel. And the per-section <h2>s come back: `cp-js` hides them because the selected row
    // names the one section on screen, which stops being true the moment several are — the same
    // reason a live search, which also spans sections, brings them back.
    val allTabState =
      if (!hasAllTab) ""
      else
        "\n        var showingAll = current === \"$ALL_TAB\";" +
          "\n        document.documentElement.classList.toggle(" +
          "\n          \"cp-multi-section\"," +
          "\n          showingAll || searching" +
          "\n        );"
    val allExpandExpr = if (hasAllTab) " || showingAll" else ""
    // Under All a card is in the current tab whatever section it sits in — that is what All means.
    val allTabCardClause = if (hasAllTab) " || current === \"$ALL_TAB\"" else ""
    val tabDecls =
      if (hasTabs)
        "\n      var tabBtns = document.querySelectorAll(\".cp-tab\");" +
          "\n      var treeGroups = document.querySelectorAll(\".cp-tree-group\");" +
          "\n      var tabSections = document.querySelectorAll(\".cp-section\");" +
          "\n      var current = tabBtns.length ? tabBtns[0].getAttribute(\"data-tab\") : null;" +
          "\n      try {" +
          "\n        var storedTab = localStorage.getItem(\"$tabStorageKey\");" +
          "\n        tabBtns.forEach(function (t) {" +
          "\n          if (t.getAttribute(\"data-tab\") === storedTab) current = storedTab;" +
          "\n        });" +
          "\n      } catch (e) {}" +
          // `?tab=` outranks the remembered tab for the same reason `?theme=` outranks the
          // remembered chip: it is on the URL because it was chosen, here or by whoever shared it.
          "\n      var urlTab = urlParam(\"tab\");" +
          "\n      tabBtns.forEach(function (t) {" +
          "\n        if (t.getAttribute(\"data-tab\") === urlTab) current = urlTab;" +
          "\n      });" +
          "\n      var initialTab = current;" +
          // Selection, expansion and the tree's single tab stop are one statement: the selected
          // section is the open one and the one Tab lands on. The exception is a live search, which
          // spans every section — so every branch that still holds a match opens, because the rows
          // the matches sit under must not be the rows you cannot see.
          "\n      function reflectTabs() {" +
          "\n        var searching = !!(input && input.value.trim());" +
          allTabState +
          "\n        var stop = null;" +
          "\n        var firstShown = null;" +
          "\n        tabBtns.forEach(function (t) {" +
          "\n          var on = t.getAttribute(\"data-tab\") === current;" +
          "\n          t.setAttribute(\"aria-selected\", on ? \"true\" : \"false\");" +
          "\n          if (t.hasAttribute(\"aria-expanded\"))" +
          "\n            t.setAttribute(" +
          "\n              \"aria-expanded\"," +
          "\n              on || searching$allExpandExpr ? \"true\" : \"false\"" +
          "\n            );" +
          "\n          var node = t.closest(\".cp-tree-node\");" +
          "\n          var shown = !(node && node.hidden);" +
          // The stop belongs to the selected row only while that row is on screen, so a filtered-
          // out section does not keep a claim on it that the fallback below then duplicates.
          "\n          t.tabIndex = on && shown ? 0 : -1;" +
          "\n          if (shown && !firstShown) firstShown = t;" +
          "\n          if (on && shown) stop = t;" +
          "\n        });" +
          "\n        treeGroups.forEach(function (g) { g.tabIndex = -1; });" +
          // A filter can hide the selected section outright — search for something only another
          // section matches and `current` is off screen. Its row would still hold the tree's only
          // tab stop, so Tab would skip the whole navigation. Hand the stop to the first branch
          // still showing instead.
          "\n        if (!stop && firstShown) firstShown.tabIndex = 0;" +
          "\n      }" +
          "\n      reflectTabs();" +
          "\n      document.documentElement.classList.add(\"cp-js\");"
      else ""
    // A card is shown when it matches the search AND (while not searching) sits in the current tab.
    // A filter spans every section; tab selection is ignored until it clears. `uses:` is a filter,
    // so the same has to be true of it — and it is not enough to test `q`, because a query that is
    // ONLY `uses:Foo` leaves `q` empty. Reading that as "not searching" kept every other section
    // hidden, so matches outside the selected tab vanished and the readout counted only the cards
    // in it. `reflectTabs`'s own `searching` was already right about this — it reads the raw input,
    // where the operator is still present — which is why the branches opened while the cards under
    // them stayed hidden; this line is the half that disagreed.
    val searchingExpr = if (usesFilter) "(q !== \"\" || usesActive())" else "q !== \"\""
    val tabOkLine =
      if (hasTabs)
        "\n          var sec = c.closest(\".cp-section\");" +
          "\n          var tabOk = $searchingExpr || !sec" +
          allTabCardClause +
          " || sec.getAttribute(\"data-section\") === current;"
      else ""
    val hiddenExpr = if (hasTabs) "!(searchOk && tabOk)" else "!searchOk"
    val shownCond = if (hasTabs) "searchOk && tabOk" else "searchOk"
    // After the per-card pass, collapse any sub-group / section left with no visible card — and
    // re-size the sub-groups that survived. A cluster's width is its card count (`--cp-n`, see
    // `.cp-subgroup` in serve.css), and a filter changes that count: a family showing one of its
    // four cards would otherwise go on reserving four columns and painting three of them blank,
    // which is the whole thing this layout exists to stop happening.
    val groupPost =
      if (hasGroups)
        "\n        navGroups.forEach(function (g) {" +
          "\n          var on = g.querySelectorAll(\".cp-card:not([hidden])\").length;" +
          "\n          g.hidden = !on;" +
          "\n          if (on) g.style.setProperty(\"--cp-n\", on);" +
          "\n        });"
      else ""
    val sectionPost =
      if (hasTabs)
        "\n        tabSections.forEach(function (s) { s.hidden = !s.querySelector(\".cp-card:not([hidden])\"); });"
      else ""
    // The tree tracks what the grid just did: a group row disappears with the sub-group it points
    // at, so a filter never leaves a destination that scrolls to nothing. Section rows are hidden
    // ONLY while searching — outside a search every section but the current one is empty by
    // construction (its cards are filtered out by tab), and hiding those rows would delete the
    // navigation instead of filtering it. Re-reflecting last picks up the expansion a search opens.
    val treePost =
      if (!hasTree) ""
      else
      // Component rows first: each follows the card it points at, so a search never leaves a row
      // that scrolls to something hidden. Then the group rows follow their sub-group, which by
      // now has collapsed if the filter emptied it.
      "\n        treeComponents.forEach(function (c) {" +
          "\n          var card = document.getElementById(c.getAttribute(\"data-group\"));" +
          "\n          if (c.parentElement) c.parentElement.hidden = !!(card && card.hidden);" +
          "\n        });" +
          "\n        treeGroups.forEach(function (g) {" +
          "\n          var sub = document.getElementById(g.getAttribute(\"data-group\"));" +
          "\n          if (g.parentElement) g.parentElement.hidden = !!(sub && sub.hidden);" +
          "\n        });" +
          (if (hasTabs)
            "\n        tabBtns.forEach(function (t) {" +
              "\n          var node = t.closest(\".cp-tree-node\");" +
              "\n          var sec = document.getElementById(t.getAttribute(\"aria-controls\"));" +
              "\n          if (node) node.hidden = q !== \"\" && !!(sec && sec.hidden);" +
              "\n        });" +
              "\n        reflectTabs();"
          else "") +
          // Last word on the roving tab stop: the rows that just appeared or vanished change which
          // one should hold it, and `reflectTabs` only ever knew about sections and groups.
          "\n        if (cpTreeStops) cpTreeStops();"
    // The pages list is filtered by the SAME query as the grid and the tree — one box, one meaning,
    // whichever pane is showing. Matched against `data-search` rather than the row's text, so what
    // counts as the page's name is decided by the server that wrote it.
    val panePost =
      if (!hasPanes) ""
      else
        "\n        var pagesShown = 0;" +
          // Sections first, so a page row can ask whether any of its own survived. A page KEEPS on
          // its own name or on a section's — searching "circle" must find the Shape page even
          // though the word is not in its title, which is most of why the sections are here.
          "\n        sectionRows.forEach(function (sec) {" +
          "\n          var hay = (sec.getAttribute(\"data-search\") || \"\").toLowerCase();" +
          "\n          var keep = paneQ === \"\" || hay.indexOf(paneQ) !== -1;" +
          "\n          if (sec.parentElement) sec.parentElement.hidden = !keep;" +
          "\n        });" +
          "\n        pageRows.forEach(function (p) {" +
          "\n          var hay = (p.getAttribute(\"data-search\") || \"\").toLowerCase();" +
          "\n          var own = paneQ === \"\" || hay.indexOf(paneQ) !== -1;" +
          "\n          var list = p.nextElementSibling;" +
          "\n          var kept = list" +
          "\n            ? Array.prototype.some.call(list.children, function (li) { return !li.hidden; })" +
          "\n            : false;" +
          "\n          var keep = own || kept;" +
          // A page kept only because a section matched shows just those sections, and opens so they
          // are on screen — a match you have to expand a twisty to see is a match the filter did
          // not really surface. A page matching on its own name keeps its whole list.
          "\n          if (own && list) {" +
          "\n            Array.prototype.forEach.call(list.children, function (li) { li.hidden = false; });" +
          "\n          }" +
          "\n          if (p.parentElement) p.parentElement.hidden = !keep;" +
          "\n          if (p.hasAttribute(\"aria-expanded\") && paneQ !== \"\")" +
          "\n            p.setAttribute(\"aria-expanded\", keep ? \"true\" : \"false\");" +
          "\n          if (keep) pagesShown++;" +
          "\n        });" +
          "\n        if (pagesEmpty) pagesEmpty.hidden = pagesShown !== 0;"
    // ONE box, but it filters the list it is pointed at. On the Pages pane the query is a page
    // query: applying it to the grid as well would answer "shape" with "No previews match your
    // filter" under a sidebar that just found the Shape page, which is the box contradicting
    // itself. The grid belongs to the Components pane, so it is filtered when that pane is the one
    // showing and left whole otherwise.
    // The Pages pane lists design sheets, which have no composables to call — so `uses:` is a
    // question they cannot answer, and the pane filters on the RAW query rather than on the
    // operator-stripped remainder. Without that, a query of only `uses:Foo` reached the pane as an
    // empty string and showed every page while the readout above described the hidden component
    // grid. Filtering on the raw text instead lands the pane on its own empty state, which is the
    // truthful answer to "which of these sheets calls a Button".
    val paneQExpr = if (usesFilter) "usesRawQuery" else "q"
    val paneSplit =
      if (!hasPanes) ""
      else
        "\n        var paneQ = pane === \"pages\" ? $paneQExpr : \"\";" +
          "\n        if (pane === \"pages\") q = \"\";"
    val paneWiring =
      if (!hasPanes) ""
      else
      // The twisty on a page row, and the keys that do the same job.
      //
      // The row is a real LINK to the whole sheet as well as a fold, which is the whole difficulty:
      // the pointer distinguishes the two by where it lands (the arrow, or the label), and the
      // keyboard has no such position. So the two are split by input rather than shared: a pointer
      // click inside the arrow's 14px folds, and Right/Left fold from the keyboard — the same two
      // keys the component tree binds one pane over. Enter is left alone and follows the link.
      "\n      pageRows.forEach(function (p) {" +
          "\n        if (!p.hasAttribute(\"aria-expanded\")) return;" +
          "\n        function fold(open) {" +
          "\n          p.setAttribute(\"aria-expanded\", open ? \"true\" : \"false\");" +
          "\n        }" +
          "\n        p.addEventListener(\"keydown\", function (e) {" +
          "\n          if (e.key !== \"ArrowRight\" && e.key !== \"ArrowLeft\") return;" +
          "\n          var open = e.key === \"ArrowRight\";" +
          "\n          if ((p.getAttribute(\"aria-expanded\") === \"true\") === open) return;" +
          "\n          e.preventDefault();" +
          "\n          fold(open);" +
          "\n        });" +
          "\n        p.addEventListener(\"click\", function (e) {" +
          // A keyboard Enter synthesizes a click with `detail === 0` and no meaningful pointer
          // position, so `offsetX` reads 0 — inside the arrow — and the row would fold instead of
          // following its link. Hence pointer-only here, and the keydown above for the keyboard.
          "\n          if (!e.detail) return;" +
          "\n          if (e.offsetX > 14) return;" +
          "\n          e.preventDefault();" +
          "\n          fold(p.getAttribute(\"aria-expanded\") !== \"true\");" +
          "\n        });" +
          "\n      });" +
          "\n      paneTabs.forEach(function (t) {" +
          "\n        t.addEventListener(\"click\", function () {" +
          "\n          pane = t.getAttribute(\"data-pane\");" +
          "\n          reflectPanes();" +
          // Replaces rather than pushes, for the same reason typing does: switching a sidebar pane
          // is not a place you expect Back to undo one step at a time.
          "\n          replaceUrl({ pane: pane === \"pages\" ? \"pages\" : \"\" });" +
          // Re-filter: the query in the box now means something else. Switching to Pages with a
          // live query has to release the grid it was filtering and narrow the pages instead, and
          // switching back has to put both right again.
          "\n          apply();" +
          "\n        });" +
          "\n      });" +
          "\n      reflectPanes();"
    val tabWiring =
      if (hasTabs)
        "\n      tabBtns.forEach(function (t) {" +
          "\n        t.addEventListener(\"click\", function (e) {" +
          "\n          e.preventDefault();" +
          "\n          current = t.getAttribute(\"data-tab\");" +
          "\n          try { localStorage.setItem(\"$tabStorageKey\", current); } catch (e) {}" +
          "\n          reflectTabs();" +
          "\n          pushUrl({ tab: current });" +
          "\n          apply();" +
          "\n        });" +
          "\n      });"
      else ""
    // The tree's own behaviour: its group rows, its keyboard, and the scroll-spy that keeps the
    // row you are looking at marked. Spliced in at the same indent as the rest, and empty for a
    // flat catalog — which has no tree. Emitted AFTER [popWiring] on purpose: `onPop` is a plain
    // `popstate` listener, so the tree's fragment-precedence handler has to register second to get
    // the last word over the shared `?tab=` restore.
    val treeWiring =
      if (!hasTree) ""
      else
        catalogTreeScript(tabStorageKey, hasTabs, hasAllTab).lines().joinToString("") {
          if (it.isEmpty()) "\n" else "\n      $it"
        }
    // Back / Forward: re-read the whole selection off the URL and re-apply it in place — no
    // reload, so nothing is re-fetched that the page already has. A history entry that carries no
    // param for a control falls back to what THIS page load resolved to, never to the
    // localStorage value a later click wrote: otherwise Back out of a theme would land right back
    // on the theme the visitor was leaving.
    val themePop =
      if (hasThemes)
        "\n          var poppedTheme = urlParam(\"theme\") || initialTheme;" +
          "\n          if (chipOffered(poppedTheme)) theme = poppedTheme;"
      else ""
    val tabPop =
      if (hasTabs)
        "\n          var poppedTab = urlParam(\"tab\") || initialTab;" +
          "\n          tabBtns.forEach(function (t) {" +
          "\n            if (t.getAttribute(\"data-tab\") === poppedTab) current = poppedTab;" +
          "\n          });" +
          "\n          reflectTabs();"
      else ""
    // ---- The Dev-mode `uses:` operator
    //
    // Four small splices rather than a second script: the operator narrows the SAME card list the
    // text filter narrows, so it has to share `apply()` and the state around it. A parallel script
    // would need its own pass over the cards and could disagree with this one about which are
    // showing — the exact failure the pane strip's comment above describes.
    val usesDecls = if (usesFilter) usesFilterScript(usesUrl) else ""
    val queryExpr =
      if (usesFilter) "var q = usesSplit(input ? input.value.trim() : \"\");"
      else "var q = input ? input.value.trim().toLowerCase() : \"\";"
    // Parenthesised only when there is a second conjunct, so a Catalog-mode script keeps the exact
    // expression it had rather than gaining redundant brackets in every page fixture.
    val searchOkExpr =
      if (usesFilter) "(q === \"\" || hay.indexOf(q) !== -1) && usesOk(c)"
      else "q === \"\" || hay.indexOf(q) !== -1"
    // Written at the END of `apply()`, so the readout describes the grid as it now stands rather
    // than as it was before this pass narrowed it.
    val usesReport = if (usesFilter) "\n        usesReport(shown, total);" else ""
    val popWiring =
      "\n      if (urlState) {" +
        "\n        urlState.onPop(function () {$themePop$tabPop" +
        "\n          if (input) input.value = urlParam(\"q\");" +
        "\n          apply();" +
        "\n        });" +
        "\n      }"
    return """
    (function () {
      var cards = document.querySelectorAll(".cp-card");
      var input = document.getElementById("cp-search");
      var count = document.getElementById("cp-count");
      var empty = document.getElementById("cp-empty");
      var total = cards.length;
      // Address-bar state (`window.cpUrlState`). Every selection below is reflected into the URL so the
      // page someone is looking at is the page its URL describes — bookmarkable, shareable, and
      // reachable with Back — without ever reloading: the grid re-points its own images.
      var urlState = window.cpUrlState || null;
      function urlParam(n) { return urlState ? urlState.get(n) : ""; }
      function pushUrl(v) { if (urlState) urlState.push(v); }
      function replaceUrl(v) { if (urlState) urlState.replace(v); }
      if (input) { var urlQuery = urlParam("q"); if (urlQuery) input.value = urlQuery; }$usesDecls$groupDecls$treeDecls$tabDecls$paneDecls
      ${listOf(themeInit, themeRenderInit).filter { it.isNotEmpty() }.joinToString("\n")}
      function apply() {
        $applyTheme
        $queryExpr$paneSplit
        var shown = 0;
        cards.forEach(function (c) {
          var lab = c.querySelector(".cp-label");
          var idn = c.querySelector(".cp-id");
          var hay = ((lab ? lab.textContent : "") + " " + (idn ? idn.textContent : "")).toLowerCase();
          var searchOk = $searchOkExpr;$tabOkLine
          c.hidden = $hiddenExpr;
          if ($shownCond) shown++;
        });
        if (count) count.textContent = q === "" ? (total + " preview" + (total === 1 ? "" : "s")) : (shown + " of " + total);$usesReport
        if (empty) empty.hidden = shown !== 0;$groupPost$sectionPost$treePost$panePost
      }
      if (input) input.addEventListener("input", function () {
        // Typing REPLACES rather than pushes: a five-character filter must not bury the page the
        // visitor arrived from under five entries. The URL still carries the query, so the
        // filtered grid is bookmarkable.
        replaceUrl({ q: input.value.trim() });
        apply();
      });
      $themeWiring$tabWiring$paneWiring$popWiring$treeWiring
      apply();$presenceWiring
    })();
    """
      .trimIndent()
  }

  /**
   * The `uses:` operator's state and helpers, spliced into [catalogFilterScript]'s IIFE.
   *
   * ### What it is
   *
   * `uses:Button` in the landing filter box narrows the grid to previews whose declaration
   * **calls** something matching `Button` — resolved by the server
   * ([ServeHttpServer.handleUsesSearch]), which parses the catalog's own source. It composes with
   * the ordinary text filter, which keeps its meaning: `uses:Button tonal` is "calls a Button, and
   * is called something with `tonal` in it".
   *
   * ### Why an operator and not a control
   *
   * The alternative was a second input, or a chip beside the search box. Both would put a question
   * about *this repository's source* into the furniture of a page whose other controls are about
   * the design system — and it would sit there, empty, on every catalog and for every visitor. An
   * operator costs nothing until it is typed, rides the existing `?q=` (so a `uses:` search is as
   * bookmarkable and shareable as any other filter), and reads as what it is. It is not hidden: the
   * count line names the filter back to you the moment it is active.
   *
   * ### Unresolved is not empty
   *
   * A token whose answer has not arrived yet shows no cards and says so ("looking for calls to …"),
   * and a catalog that cannot be indexed at all says *that* ("call index unavailable") rather than
   * reporting an honest-looking zero. The distinction is the whole reason the endpoint answers with
   * an `available` flag instead of just a list.
   */
  private fun usesFilterScript(usesUrl: String): String {
    val script =
      """
      // ---- `uses:<composable>` — which previews CALL a thing (Dev mode only)
      var usesEndpoint = ${WebEscaping.jsString(usesUrl)};
      var usesToken = "";
      var usesRawQuery = "";
      var usesCache = {};
      var usesPending = {};
      // Whether a `uses:` filter is running right now. Asked wherever the page would otherwise
      // test `q !== ""` to mean "a filter is on": a query of only `uses:Foo` leaves `q` empty, and
      // every such test would then read as "resting".
      function usesActive() { return usesToken !== ""; }
      // Pulls `uses:<token>` out of the raw query and returns what is left for the text filter.
      // It also notices a CHANGE of token and starts the lookup, which is why it is one function
      // and not a pure split: `apply()` is the only place that reads the box, so it is the only
      // place that can see the token change, and a separate listener would have to re-parse the
      // same string and could disagree with this one about what was typed.
      function usesSplit(raw) {
        var found = "";
        var rest = raw.replace(/(^|\s)uses:(\S*)/i, function (m, lead, t) { found = t; return lead; });
        if (found !== usesToken) { usesToken = found; usesFetch(found); }
        // Kept for the Pages pane, which filters on what was TYPED rather than on the remainder —
        // see `paneQ` in the filter script.
        usesRawQuery = raw.trim().toLowerCase();
        return rest.trim().toLowerCase();
      }
      function usesEntry() { return usesToken ? usesCache[usesToken.toLowerCase()] : null; }
      // A card carries its default preview id; every variant folded onto it shares that declaration.
      function usesOk(c) {
        if (usesToken === "") return true;
        var entry = usesEntry();
        if (!entry || !entry.available) return false;
        var id = c.getAttribute("data-uses-id");
        return !!id && entry.ids.indexOf(id) !== -1;
      }
      // The readout under the search box. Hidden entirely while the operator is not in use, so the
      // page is unchanged for a visitor who never types it.
      function usesReport(shown, total) {
        var el = document.getElementById("cp-uses-status");
        if (!el) return;
        if (usesToken === "") { el.hidden = true; el.textContent = ""; return; }
        var entry = usesEntry();
        var note;
        if (!entry) note = "looking for calls to " + usesToken + "…";
        else if (!entry.available) note = "call index unavailable";
        else
          note =
            shown + " of " + total + " call " + usesToken +
            (entry.truncated ? " (partial index)" : "");
        el.textContent = note;
        el.hidden = false;
      }
      function usesFetch(t) {
        if (t === "") return;
        var key = t.toLowerCase();
        if (usesCache[key] || usesPending[key]) return;
        usesPending[key] = true;
        var sep = usesEndpoint.indexOf("?") === -1 ? "?" : "&";
        // Any failure — 404 in Catalog mode, an offline tab, a body that isn't JSON — lands as
        // "unavailable" rather than as an empty result, so the count line never reports a zero the
        // server did not actually compute.
        var unavailable = { available: false, truncated: false, ids: [] };
        fetch(usesEndpoint + sep + "q=" + encodeURIComponent(key))
          .then(function (r) { return r.ok ? r.json() : unavailable; })
          .catch(function () { return unavailable; })
          .then(function (data) {
            usesCache[key] = {
              available: !!data.available,
              truncated: !!data.truncated,
              ids: (data.ids || []).slice()
            };
            delete usesPending[key];
            apply();
          });
      }
      """
        .trimIndent()
    // Spliced one level in, the way [presenceScript] is, so the emitted script keeps the IIFE's
    // indentation instead of stitching a flush-left block into the middle of it.
    return script.lines().joinToString("") { if (it.isEmpty()) "\n" else "\n      $it" }
  }

  /**
   * The navigation tree's behaviour, spliced into [catalogFilterScript]'s IIFE so it closes over
   * the selection it shares with the grid (`current`, `reflectTabs`, `apply`, `pushUrl`) instead of
   * keeping a second copy that could disagree with which panel is showing.
   *
   * Three things the flat tab bar had no need of:
   * * **group rows** — a jump within the catalog: select the section, then scroll its sub-group
   *   divider into view. The bare `#cp-group-…` href stays as the no-JS fallback, because following
   *   it with JS present would land on a divider inside a panel the section switching still has
   *   hidden.
   * * **keyboard** — the tree pattern's roving focus (Down/Up walk the *visible* rows, Right opens
   *   a collapsed section, Left climbs from a group back to its section, Home/End jump the ends).
   *   The tab bar never implemented its own pattern's arrow keys; a tree that publishes two levels
   *   is where not having them starts to cost something.
   * * **scroll-spy** — the row for the sub-group on screen is marked `aria-current`, so the tree
   *   says where you *are* and not merely where you last clicked. Additive: with no
   *   `IntersectionObserver` the marking simply follows clicks.
   */
  private fun catalogTreeScript(
    tabStorageKey: String,
    hasTabs: Boolean,
    /** Whether the tree leads with the **All** row ([ALL_TAB]) — see [catalogFilterScript]. */
    hasAllTab: Boolean = false,
  ): String {
    // Section switching only exists for a catalog that HAS sections. An outline tree (a
    // section-less catalog) hides nothing and remembers nothing — every row is purely a jump — so
    // the pieces that talk to `current` / `selectTab` are spliced out rather than guarded at
    // runtime, and its script never mentions a tab.
    val selectOwningTab = if (hasTabs) "\n        selectOwningTab(row);" else ""
    val tabRows = if (hasTabs) ".cp-tab, " else ""
    // The rows a `#cp-panel-<slug>` fragment could name, and the three operations that only mean
    // something when sections exist. An outline tree gets inert stand-ins rather than `if
    // (hasTabs)`
    // scattered through the body.
    // A `#cp-group-…` fragment scrolls; it does not decide which slice of the catalog you are
    // looking at. Under All it would otherwise undo the very thing that made the link — the click
    // that wrote the fragment deliberately stayed in All, and a reload of the URL it wrote has to
    // land on the same page. Off All it still selects the fragment's own section, which is the only
    // way its target is on screen at all.
    val keepAll = if (hasAllTab) "\n          if (current === \"$ALL_TAB\") return;" else ""
    // Same rule on Back/Forward, but the marking still happens: the entry is a scroll position
    // within All, so the row it names is the one to mark.
    val keepAllPop =
      if (!hasAllTab) ""
      else
        "\n            if (current === \"$ALL_TAB\") {" +
          "\n              if (popped.row) markGroup(popped.row);" +
          "\n              return;" +
          "\n            }"
    val tabHelpers =
      if (hasTabs)
        """
        var tabBtnsForHash = tabBtns;
        function selectTab(slug) {
          if (!slug || slug === current) return;
          current = slug;
          try { localStorage.setItem("$tabStorageKey", current); } catch (e) {}
          reflectTabs();
          pushUrl({ tab: current });
          apply();
        }
        function selectCollapsedTab(row) { selectTab(row.getAttribute("data-tab")); }
        // Jumping to a group from ALL stays in All: the row you clicked says where to scroll, not
        // which slice of the catalog to throw away. From a section it still switches, because a
        // group row can name a section other than the one showing.
        function selectOwningTab(row) {$keepAll
          selectTab(row.getAttribute("data-tab"));
        }
        function applyLandingTab(landing) {
          if (!landing.tab || landing.tab === current) return;$keepAll
          current = landing.tab;
          initialTab = current;
          reflectTabs();
        }
        """
          .trimIndent()
          .lines()
          .joinToString("\n") { if (it.isEmpty()) "" else "      $it" }
          .trimStart()
      else
        """
        var tabBtnsForHash = [];
        function selectCollapsedTab() {}
        function applyLandingTab() {}
        """
          .trimIndent()
          .lines()
          .joinToString("\n") { if (it.isEmpty()) "" else "      $it" }
          .trimStart()
    val popPrecedence =
      if (!hasTabs) ""
      else
        """

        // Back / Forward has to resolve an entry the same way loading it fresh would. The shared pop
        // handler reads `?tab=` only, so returning to an entry whose fragment and query disagree —
        // `?tab=components#cp-group-themes-foundation`, which a fresh load resolves to Themes —
        // would land on Components with the fragment's target hidden. This runs after that handler
        // (registered later, and `onPop` is a plain listener) and re-applies the precedence.
        if (window.cpUrlState) {
          window.cpUrlState.onPop(function () {
            var popped = hashTarget();
            if (!popped || popped.tab === current) return;$keepAllPop
            current = popped.tab;
            reflectTabs();
            apply();
            if (popped.row) markGroup(popped.row);
          });
        }
        """
          .trimIndent()
          .lines()
          .joinToString("\n") { if (it.isEmpty()) "" else "      $it" }
          .trimStart()
    val sectionClicks =
      if (!hasTabs) ""
      else
        """

        // Choosing a whole section retires any group fragment: the row you clicked is the statement
        // of where you are, and a leftover `#cp-group-…` from another section would outrank it on
        // the next load. It also has to honour the promise its own `href="#cp-panel-…"` makes — the
        // shared handler prevents the default navigation and only swaps which panel is hidden, so
        // from halfway down a long section the scroll simply stayed where it was. Registered after
        // that handler, so the panel is already showing when this measures it, and it only scrolls
        // when the panel is actually behind the sticky toolbar.
        tabBtns.forEach(function (t) {
          t.addEventListener("click", function () {
            setFragment("");
            var panel = document.getElementById(t.getAttribute("aria-controls"));
            if (!panel) return;
            var clearance = tools ? tools.getBoundingClientRect().height : 0;
            if (panel.getBoundingClientRect().top < clearance) {
              panel.scrollIntoView({ block: "start" });
            }
          });
        });
        """
          .trimIndent()
          .lines()
          .joinToString("\n") { if (it.isEmpty()) "" else "      $it" }
          .trimStart()
    return """
    (function () {
      var tree = document.getElementById("cp-tabs");
      if (!tree) return;
      // The toolbar above pins itself at top:0 over everything, so publish its real height for the
      // sticky menu's offset and for every scroll target's `scroll-margin-top`. Measured rather
      // than assumed: it wraps on a narrow viewport and grows a row with the declared-theme chips,
      // and the static fallback in the stylesheet only covers the unwrapped case.
      // `cp-js` gates every collapse rule in the stylesheet. It used to be set by the section
      // machinery alone, which would have left an outline tree permanently expanded.
      document.documentElement.classList.add("cp-js");
      var tools = document.querySelector(".cp-catalog-tools");
      if (tools) {
        var syncTools = function () {
          var h = Math.round(tools.getBoundingClientRect().height);
          if (h > 0) document.documentElement.style.setProperty("--cp-sticky-tools", h + "px");
        };
        syncTools();
        if (window.ResizeObserver) new ResizeObserver(syncTools).observe(tools);
        else window.addEventListener("resize", syncTools);
      }
      $tabHelpers
      // The row pointing at an id, found by COMPARING attribute values rather than by building a
      // selector out of DOM text (CodeQL `js/xss-through-dom`, and the rule the backdrop viewer
      // already follows).
      function rowFor(id) {
        var found = null;
        treeGroups.forEach(function (g) { if (g.getAttribute("data-group") === id) found = g; });
        return found;
      }
      function markGroup(row) {
        treeGroups.forEach(function (g) {
          if (g === row) g.setAttribute("aria-current", "true");
          else g.removeAttribute("aria-current");
        });
      }
      // Which group and which component are open. One of each: a tree that opened every branch it
      // was ever asked about would end up listing every component in the catalog, which is the
      // wall the grid already is. The server marks the first group open, so the page arrives
      // showing components rather than needing to be prised open first.
      var openGroup = null;
      var openCard = null;
      treeLinks.forEach(function (r) {
        if (r.classList.contains("cp-tree-group") && r.getAttribute("aria-expanded") === "true") {
          openGroup = r.getAttribute("data-group");
        }
      });
      function parentRow(row) {
        var list = row.closest("ul.cp-tree-children");
        return list ? list.previousElementSibling : null;
      }
      function reflectTree() {
        treeLinks.forEach(function (r) {
          if (!r.hasAttribute("aria-expanded")) return;
          var id = r.getAttribute("data-group");
          // A branch that names no in-page target is not one of the two this tracks — the Pages
          // branch owns destinations that are elsewhere, and is written open once and left open.
          if (!id) return;
          var on = r.classList.contains("cp-tree-group") ? id === openGroup : id === openCard;
          r.setAttribute("aria-expanded", on ? "true" : "false");
        });
      }
      function openRow(row) {
        var id = row.getAttribute("data-group");
        if (row.classList.contains("cp-tree-group")) {
          openGroup = id;
          openCard = null;
        } else if (row.classList.contains("cp-tree-component")) {
          openCard = id;
          // And the group that HOLDS it. A click can only reach a component whose group is already
          // open, but a `#cp-card-…` fragment can name one in any group — and leaving `openGroup`
          // on whichever group the server expanded would scroll to the card while keeping its own
          // row, and every variant under it, collapsed out of the tree.
          var owner = parentRow(row);
          if (owner && owner.classList.contains("cp-tree-group")) {
            openGroup = owner.getAttribute("data-group");
          }
        }
        reflectTree();
        syncTabStops();
      }
      // The fragment is part of the address this page describes, and `cpUrlState` deliberately
      // preserves whatever hash is already there when it rewrites the query. So a click that moves
      // you somewhere else has to move the fragment too, or the URL keeps pointing at where you
      // WERE. `replaceState`, not push: a jump inside the page is a scroll, not a place to come
      // Back to.
      function setFragment(id) {
        var url = location.pathname + location.search + (id ? "#" + id : "");
        try { history.replaceState(history.state, "", url); } catch (e) {}
      }
      // Every row that names an in-page destination. A VARIANT row is the exception: the grid
      // folds those renders out, so it has nowhere here to jump to — it carries a plain `/p/<id>`
      // href and is left to the browser.
      treeLinks.forEach(function (row) {
        row.addEventListener("click", function (e) {
          var id = row.getAttribute("data-group");
          if (!id) return;
          var target = document.getElementById(id);
          if (!target) return;
          e.preventDefault();
          openRow(row);$selectOwningTab
          setFragment(id);
          target.scrollIntoView({ behavior: "smooth", block: "start" });
          if (row.classList.contains("cp-tree-group")) markGroup(row);
        });
      });
      // Keyboard: the tree pattern's roving focus. Visibility is read off layout rather than walked
      // by hand — a collapsed branch is `display: none`, so `offsetParent` already answers "can the
      // visitor reach this row", across all four levels and the filter's hiding at once.
      function visibleRows() {
        var rows = [];
        tree.querySelectorAll("$tabRows.cp-tree-link").forEach(function (r) {
          if (r.offsetParent !== null) rows.push(r);
        });
        return rows;
      }
      function focusRow(el) {
        if (!el) return;
        visibleRows().forEach(function (i) { i.tabIndex = i === el ? 0 : -1; });
        el.focus();
      }
      // A `role="tree"` is ONE tab stop: Tab enters it, the arrow keys move within it, Tab leaves.
      // Nothing established that until a first arrow press called `focusRow`, so every visible row
      // sat in the normal tab order until then — the whole point of the pattern, lost on the one
      // pass that matters, and worse the deeper the tree got. Keeps an existing stop if it is still
      // on screen (so a filter does not yank focus) and otherwise hands it to the first row.
      function syncTabStops() {
        var rows = visibleRows();
        if (!rows.length) return;
        var stop = null;
        rows.forEach(function (r) { if (!stop && r.tabIndex === 0) stop = r; });
        if (!stop) stop = rows[0];
        rows.forEach(function (r) { r.tabIndex = r === stop ? 0 : -1; });
      }
      cpTreeStops = syncTabStops;
      tree.addEventListener("keydown", function (e) {
        var items = visibleRows();
        var at = items.indexOf(document.activeElement);
        if (at === -1) return;
        var key = e.key;
        var next = null;
        if (key === "ArrowDown") next = items[Math.min(at + 1, items.length - 1)];
        else if (key === "ArrowUp") next = items[Math.max(at - 1, 0)];
        else if (key === "Home") next = items[0];
        else if (key === "End") next = items[items.length - 1];
        else if (key === "ArrowRight") {
          // Right opens a collapsed parent, steps into an expanded one's first child, and does
          // NOTHING on an end node. Falling through to "next visible row" on a leaf made Right a
          // second Arrow Down, walking the visitor across siblings when they asked to expand
          // something that cannot expand.
          var expanded = items[at].getAttribute("aria-expanded");
          if (expanded === "false") {
            e.preventDefault();
            if (items[at].classList.contains("cp-tree-link")) openRow(items[at]);
            else selectCollapsedTab(items[at]);
            return;
          }
          if (expanded !== "true") return;
          next = items[Math.min(at + 1, items.length - 1)];
        } else if (key === "ArrowLeft") {
          // Left closes an open branch, else climbs to the parent — the tree pattern's own rule,
          // and the only way back up once the levels are four deep. The `data-group` test excludes
          // the always-open Pages branch: closing it is not offered, and without the test Left on
          // that row would clear `openCard` and collapse whichever component IS open.
          if (
            items[at].getAttribute("aria-expanded") === "true" &&
            items[at].getAttribute("data-group")
          ) {
            e.preventDefault();
            if (items[at].classList.contains("cp-tree-group")) openGroup = null;
            else openCard = null;
            reflectTree();
            return;
          }
          next = parentRow(items[at]);
        } else return;
        if (!next) return;
        e.preventDefault();
        focusRow(next);
      });
      // What the URL's fragment names, or null when it names nothing this page has.
      //
      // Percent-DECODED before comparing: a section or group name keeps its non-ASCII letters
      // through `sectionSlug` (Kotlin's `isLetterOrDigit` is Unicode-aware), so the id in the DOM
      // is the raw text while browsers hand back `location.hash` encoded. Undecoded, a shared link
      // to an accented or CJK group would match no row at all and silently do nothing. A malformed
      // escape sequence throws, and is simply not a fragment this page knows.
      function hashTarget() {
        var id = location.hash ? location.hash.slice(1) : "";
        try { id = decodeURIComponent(id); } catch (e) { return null; }
        if (!id) return null;
        var tab = null;
        var row = null;
        treeLinks.forEach(function (g) {
          if (!row && g.getAttribute("data-group") === id) {
            tab = g.getAttribute("data-tab");
            row = g;
          }
        });
        if (!row) {
          tabBtnsForHash.forEach(function (t) {
            if (t.getAttribute("aria-controls") === id) tab = t.getAttribute("data-tab");
          });
        }
        return row || tab ? { tab: tab, row: row, id: id } : null;
      }
      syncTabStops();
      var landing = hashTarget();
      if (landing) {
        if (landing.row) openRow(landing.row);
        applyLandingTab(landing);
        setTimeout(function () {
          var el = document.getElementById(landing.id);
          if (el) el.scrollIntoView({ block: "start" });
          if (landing.row && landing.row.classList.contains("cp-tree-group")) markGroup(landing.row);
        }, 0);
      }$sectionClicks$popPrecedence
      // Scroll-spy: mark the group whose cards are on screen, so the tree says where you are rather
      // than only where you last clicked. Additive — with no `IntersectionObserver` the marking
      // simply follows clicks.
      if (window.IntersectionObserver) {
        var onScreen = [];
        var spy = new IntersectionObserver(function (entries) {
          entries.forEach(function (en) {
            var at = onScreen.indexOf(en.target);
            if (en.isIntersecting) { if (at === -1) onScreen.push(en.target); }
            else if (at !== -1) onScreen.splice(at, 1);
          });
          // The highest sub-group still in the band is the one being read.
          var top = null;
          onScreen.forEach(function (el) {
            if (el.hidden) return;
            if (!top || el.getBoundingClientRect().top < top.getBoundingClientRect().top) top = el;
          });
          if (top) markGroup(rowFor(top.id));
        // A band, not the whole viewport: the top inset clears the sticky header, and the bottom
        // one keeps the LAST sub-group from claiming the mark the moment its first row appears.
        // Deliberately generous at the bottom — a narrow strip near the top would leave nothing
        // marked at all on first paint, since a catalog's first sub-group starts a header, a
        // provenance strip and a toolbar down the page.
        }, { rootMargin: "-64px 0px -20% 0px" });
        document.querySelectorAll(".cp-subgroup[id]").forEach(function (g) { spy.observe(g); });
      }
    })();
    """
      .trimIndent()
  }

  /**
   * A heartbeat telling the server that a visitor is still on this catalog's pages.
   *
   * The server reaps an idle session — and the daemon behind it — after ten minutes, and measures
   * idleness in *requests*. Someone reading one catalog page makes none: the grid's thumbnails and
   * the front door's heroes are content-addressed and repaint from cache, which is the whole point
   * of prebaking them. So a tab that has been open a quarter of an hour is indistinguishable from
   * an abandoned one, and the visitor's next theme click pays a cold start. A ping every
   * [PRESENCE_INTERVAL_SECONDS] says otherwise; see `handlePresence` for what the server does with
   * it.
   *
   * Deliberately quiet about failure and about tabs nobody is looking at:
   * - **Only while visible.** A backgrounded tab is not a visitor, and keeping a daemon resident
   *   for one is exactly the waste the reaper exists to prevent. It resumes on `visibilitychange`,
   *   and pings immediately on becoming visible so a tab returned to after an hour doesn't wait out
   *   another interval before saying so.
   * - **Fires on arrival.** The page load itself is a request, but a *baked* one — it warms no
   *   daemon. Since catalogs are no longer warmed at boot, this first ping is what readies the one
   *   the visitor actually opened.
   * - **Errors ignored.** A heartbeat is not something a page can act on — offline, a catalog since
   *   removed, a server restarted. The next one tries again.
   */
  /**
   * [presenceScript] as a standalone `<script>` tag, for a page that has no script of its own to
   * splice it into (the viewer). Empty — not an empty tag — when there is no presence URL, so a
   * page without the heartbeat is byte-for-byte what it always was.
   */
  private fun presenceScriptTag(presenceUrl: String): String {
    val script = presenceScript(presenceUrl)
    if (script.isEmpty()) return ""
    // Emitted with the surrounding body's own indentation, including the leading newline: the tag
    // is interpolated *adjacent* to the previous one rather than on a line of its own, so the empty
    // case leaves no stray blank line, and every injected line stays at or past the template's
    // common indent (which `trimIndent` measures across the interpolated result, not the source).
    val indented = script.lines().joinToString("") { if (it.isEmpty()) "\n" else "\n        $it" }
    return "\n      <script>(function () {$indented\n      })();</script>"
  }

  private fun presenceScript(presenceUrl: String): String {
    if (presenceUrl.isEmpty()) return ""
    return """

      var presenceUrl = ${WebEscaping.jsString(presenceUrl)};
      function ping() {
        if (document.visibilityState !== "visible") return;
        fetch(presenceUrl, { method: "POST", credentials: "same-origin", keepalive: true })
          .catch(function () {});
      }
      setInterval(ping, ${PRESENCE_INTERVAL_SECONDS} * 1000);
      document.addEventListener("visibilitychange", ping);
      // Fired on arrival, not only every interval. Catalogs are no longer warmed at boot (see
      // ServeCatalogLiveHost.eagerWarmOnOpen), so this ping is what gets a daemon ready for the
      // catalog the visitor actually opened — while they read the grid, rather than when they
      // first click a theme and wait out a cold start.
      ping();

      // Render-server badge. Catalogs open their daemon on first real use, so whether one is up is
      // now a genuine question with a visible answer — a theme switch is instant against a warm
      // daemon and pays a cold start against none. Same URL family as the presence ping, and the
      // endpoint reads through `peekHost`, so polling it never wakes what it is reporting on.
      // The badge lives INSIDE the header's Status link (see ServeWeb.siteHeader) — the count and
      // that link answer the same question, so they are one control: the number is the summary and
      // the link is where the detail is. It is server-rendered and hidden, so filling it never
      // moves the brand or the nav.
      //
      // That slot is also the feature switch. Catalog mode's header carries no nav, so it has
      // neither the Status link nor the slot, and a catalog visitor has no `/status` page to read a
      // count against — a number with nothing to link to is not a summary of anything. This used to
      // create the span and append it to `<header>` when the slot was missing, which made it a third
      // item in that two-column grid: it landed in an implicit second row, stretched across the
      // `1fr` track, and painted the count as a full-width bar under the brand. So absence of the
      // slot now disables the badge outright rather than inventing somewhere to put it — which also
      // stops Catalog mode polling every 20s for an answer it does not display.
      var daemonUrl = presenceUrl.replace("/api/presence", "/api/daemons");
      // Read synchronously rather than on first paint: this script is emitted deep in the body, long
      // after the header, so the slot either exists now or is not on this page at all.
      var daemonBadge = document.getElementById("cp-daemon-status");
      function paintDaemonStatus(state) {
        var el = daemonBadge;
        if (!el) return;
        if (!state) { el.hidden = true; return; }
        el.hidden = false;
        // "not running" is a normal resting state, not a fault — a catalog nobody has rendered on
        // simply has no process yet. Word it so it doesn't read as an error.
        var count = state.instances || 0;
        var catalogDetail = state.running
          ? count + (count === 1 ? " instance" : " instances") +
            ", " + (state.activeStreams || 0) + " live"
          : "not running (starts on demand)";
        if (state.poolCapacity > 0)
          catalogDetail += ", pool " + state.pooled + "/" + state.poolCapacity;
        var overallDetail = (state.overallRunning || 0) + " catalogs running, " +
          (state.overallActiveStreams || 0) + " live streams";
        if (state.liveSeatsTotal > 0)
          overallDetail += ", " + (state.liveSeatsTotal - state.liveSeatsAvailable) + "/" +
            state.liveSeatsTotal + " seats used";
        el.innerHTML = '<span class="cp-daemon-dot" aria-hidden="true"></span>' + count;
        el.setAttribute("data-cp-daemon-running", state.running ? "1" : "0");
        // The badge itself is decoration on a link, so it says nothing to a screen reader: an
        // `aria-label` here would be appended to the LINK's accessible name, renaming "Status" to
        // a sentence about instance counts — and renaming it again on every poll. The name stays
        // "Status"; the detail rides on the link's own title (and behind it, the page it opens),
        // which is the same place a sighted visitor reads it from.
        var host = el.closest("a") || el;
        host.title = "Render servers — this catalog: " + catalogDetail +
          "\nOverall server: " + overallDetail;
      }
      function pollDaemons() {
        if (!daemonUrl || !daemonBadge || document.visibilityState !== "visible") return;
        fetch(daemonUrl, { credentials: "same-origin" })
          .then(function (r) { return r.ok ? r.json() : null; })
          .then(paintDaemonStatus)
          .catch(function () {});
      }
      setInterval(pollDaemons, 20000);
      document.addEventListener("visibilitychange", pollDaemons);
      pollDaemons();
      // A theme switch is exactly when the daemon comes up, so refresh the badge shortly after one.
      document.addEventListener("click", function (e) {
        if (e.target && e.target.closest && e.target.closest(".cp-theme-btn")) {
          setTimeout(pollDaemons, 1500);
        }
      });
    """
      .trimIndent()
  }

  /**
   * Viewer half of the catalog-scoped sticky Theme control. The landing page and viewer use the
   * same values: `light`, `dark`, or `theme:<provider FQN>`. A declared theme always wins over the
   * baked light/dark token in a preview id; plain day/night choices retain the old behaviour where
   * an explicit `__light` / `__dark` deep link opens on its baked pixels.
   */
  private fun viewerThemeStickyScript(themeStorageKey: String): String =
    """
    (function () {
      var el = document.getElementById("cp-theme");
      if (!el) return;
      // Runs before viewer.js' initial render. A clean explicit __light/__dark URL is reproducible:
      // remembered choices never override the path. An explicit query parameter still wins below.
      var root = document.querySelector(".cp-viewer");
      var pid = (root && root.getAttribute("data-preview-id")) || "";
      var themed = pid.split("__").some(function (s) { return s === "light" || s === "dark"; });
      // The page's own URL outranks the remembered choice: `?themeProvider=` / `?uiMode=` is there
      // because someone picked it (or was handed the link), so a bookmarked viewer opens on the
      // theme it was bookmarked in — including on an explicit __light/__dark preview.
      var params = new URLSearchParams(location.search);
      var provider = params.get("themeProvider");
      var uiMode = params.get("uiMode");
      var urlChoice = provider ? "theme:" + provider
        : (uiMode === "light" || uiMode === "dark" ? uiMode : "");
      var urlOption = null;
      Array.prototype.forEach.call(el.options, function (o) { if (urlChoice && o.value === urlChoice) urlOption = o; });
      // A choice that merely names the preview's baked theme is DISPLAYED but not marked active:
      // it asks for nothing, so it must not read as a pinned override. `?uiMode=light` on a
      // light-baked preview is the case that mattered — the light/dark toggle leaves the parameter
      // behind on the way back to light, and treating it as a pin suppressed the Figma comparison
      // for a visitor who had made no net choice. Mirrors `pinsTheme` in viewer/themeChoice.ts.
      var bakedTheme = el.getAttribute("data-default-theme") || "";
      function pinsTheme(choice) { return !!choice && choice !== bakedTheme; }
      if (urlOption) {
        el.value = urlChoice;
        if (pinsTheme(urlChoice)) el.setAttribute("data-theme-active", "1");
      }
      try {
        var stored = localStorage.getItem("$themeStorageKey");
        var declared = stored && stored.indexOf("theme:") === 0;
        var option = null;
        Array.prototype.forEach.call(el.options, function (o) { if (o.value === stored) option = o; });
        if (!urlOption && !themed && option && !option.disabled && (declared || stored === "light" || stored === "dark")) {
          el.value = stored;
          if (pinsTheme(stored)) el.setAttribute("data-theme-active", "1");
        }
      } catch (e) {}
      // Publish the design-score baseline before the component bundle upgrades the comparison
      // control. A chosen theme changes the rendered side of the comparison, so the
      // server-baked score is already stale on the first paint. viewer.js keeps this attribute in
      // sync after controls change; this early write makes the element's initial read authoritative.
      if (root) {
        var atSpecBaseline = el.disabled || el.getAttribute("data-theme-active") !== "1";
        root.setAttribute("data-spec-baseline", atSpecBaseline ? "1" : "0");
      }
      // Keep the stage backing colour in step with the CHOSEN theme, so a re-render in the opposite
      // uiMode never lands a transparent sticker on a clashing surface. The server seeds
      // data-bg-theme from the baked variant (or the dark-first default); a light/dark Theme choice
      // overrides it, and clearing it reverts to that default.
      var bgDefault = (root && root.getAttribute("data-bg-theme")) || "";
      function syncBg() {
        if (!root) return;
        // Only let the Theme choice drive the stage backing when the control can actually re-render
        // (daemon or Wasm). On a static bundle the select is disabled but the seeding above may still
        // have copied a remembered localStorage value into el.value — honoring it would tint the
        // stage while ServeBundleHost keeps returning the UNCHANGED baked PNG. Keep bgDefault there.
        var selectedOption = el.options[el.selectedIndex];
        var chosen = !el.disabled && selectedOption
          ? selectedOption.getAttribute("data-theme-mode") ||
            (el.value === "light" || el.value === "dark" ? el.value : "") : "";
        var m = chosen || bgDefault;
        if (m) root.setAttribute("data-bg-theme", m);
        else root.removeAttribute("data-bg-theme");
      }
      // Round-trip every unified choice, including `theme:<provider>`, to the catalog page.
      el.addEventListener("change", function () {
        el.setAttribute("data-theme-active", "1");
        try { localStorage.setItem("$themeStorageKey", el.value); } catch (e) {}
        syncBg();
        // …and the page around the stage, when the Page theme setting says to follow the choice.
        if (window.cpPageTheme) window.cpPageTheme.follow(el.value);
      });
      syncBg();
    })();
    """
      .trimIndent()

  /**
   * One design system's summary on the public [homeIndexPage]: its [system] id, human [title], an
   * optional one-line [subtitle] (the library coordinate), how many [previewCount] previews it
   * carries, its producer-[trust] verdict, and a [heroPreviewId] to render as the card's meaningful
   * preview (null ⇒ the system has no renderable preview, shown as a placeholder).
   */
  data class HomeSystem(
    val system: String,
    val title: String,
    val subtitle: String?,
    val previewCount: Int,
    val trust: String?,
    /** Repository that supplied this catalog; used for publisher attribution on the homepage. */
    val sourceRepo: String? = null,
    val heroPreviewId: String?,
    /** Content-crop for the hero thumbnail (frames a Wear sticker to its component); null ⇒ raw. */
    val heroCrop: ContentCrop? = null,
    /**
     * The **prebaked** thumbnail for this card, when the server has one ([ServeHeroImages]). This
     * is the fast path and the normal one: a small, already-cropped PNG on an immutable URL, so the
     * front door's imagery costs the server nothing to serve and nothing at all on a repeat visit.
     * Null falls back to [heroPreviewId] + [heroCrop] — the full-resolution `/render` lane with a
     * CSS clip window — which is what a card gets when the render can't be decoded (and what the
     * page-level unit tests exercise).
     */
    val heroImage: HeroImage? = null,
    /**
     * Whether this system's hero sits on a **dark** stage — a dark-first (Wear) system, per
     * [SystemDisplay.isDarkFirst]. The card carries `data-bg-theme="dark"` so its `.cp-imgwrap`
     * backs the thumbnail on dark rather than the default white (a light-on-transparent Wear
     * sticker on white reads wrong). Default false ⇒ the light stage, unchanged.
     */
    val darkStage: Boolean = false,
    /**
     * The front-page section this catalog was **published under** by the operator's config
     * ([ServeCatalogsConfig.Group]), or null when it declared none. A claim, not a fact: it only
     * takes effect when [sourceRepo] is one of [HomeGroup.repos] — see [homeSections].
     */
    val group: HomeGroup? = null,
    /** Aggregate visits to this catalog/app landing page. */
    val views: Long = 0,
    /**
     * This catalog publishes design references, so its `compare?format=reference` route has
     * something behind it and the card can offer the comparison. The **gate**, kept separate from
     * the vendor label below for the same reason the catalog landing keeps them separate: a
     * reference's `source.provider` may be `png`, `file`, `svg` or a token we do not map, which
     * names no design tool but is still a perfectly good thing to compare against. Conflating the
     * two dropped the action from every such catalog even though the route worked (#4349).
     *
     * False — the default — renders no compare action, which is every catalog that publishes no
     * design references, and every catalog on a server that has not had that catalog resident since
     * it started (the front door reads a suspended catalog's snapshot rather than resuming it).
     */
    val hasReferenceComparison: Boolean = false,
    /**
     * The design tool this catalog is specified by ("Figma", …), read off its references' provider
     * exactly as the catalog landing reads it ([designToolLabel]). Purely the **label**: null keeps
     * the neutral "compare to design references" wording rather than suppressing the action, and it
     * is only ever consulted when [hasReferenceComparison] already said there is one.
     */
    val designToolLabel: String? = null,
  )

  /**
   * One component offered by the home page's cross-catalog command palette. The server keeps this
   * compact projection beside the other suspended-catalog metadata, so global discovery neither
   * embeds every preview in the front door nor wakes an idle catalog daemon.
   */
  data class ComponentSearchEntry(val previewId: String, val label: String, val keywords: String)

  /**
   * Project a catalog's previews to the same component cards its landing page exposes. Theme,
   * state, props AND breakpoint renders collapse to their component's default card, so the palette
   * offers a component once rather than once per declared screen size — the same fold the grid and
   * the viewer's component drawer apply (#4279). A render whose size the export never tagged can't
   * be folded (there'd be no switcher to reach it from); those stay separate and keep the
   * disambiguating size suffix below.
   */
  fun componentSearchEntries(
    previews: List<ServePreview>,
    darkFirst: Boolean = false,
  ): List<ComponentSearchEntry> {
    val primarySizes = primarySizeByComponent(previews, darkFirst)
    val cards =
      groupPreviews(
        previews.filterNot {
          it.renderFailure == null &&
            (isNonDefaultState(it) ||
              hasNonDefaultProps(it) ||
              isNonPrimarySize(it, primarySizes, darkFirst))
        }
      )
    val duplicateLabels =
      cards
        .groupingBy { previewDisplayName(it.rendered(darkFirst)) }
        .eachCount()
        .filterValues { it > 1 }
        .keys
    return cards.map { card ->
      val preview = card.rendered(darkFirst)
      val baseLabel = previewDisplayName(preview)
      // The catalog's OWN size name first (`192dp`, `smallRound`, `wide`), then the flattened-id
      // token vocabulary. [previewSizeVariantLabel] only knows a fixed set of tokens, so a catalog
      // naming its breakpoints anything else — which is every Wear catalog, whose sizes are `192dp`
      // … `240dp` — hit the `?: baseLabel` fallback and published two palette rows spelled
      // identically. The declared `size` is exactly what tells them apart, and it is the same
      // string
      // the grid's own size rows are labelled with.
      val label =
        if (baseLabel !in duplicateLabels) baseLabel
        else
          (preview.size?.takeIf { it.isNotBlank() } ?: previewSizeVariantLabel(preview.id))?.let {
            "$baseLabel · $it"
          } ?: baseLabel
      ComponentSearchEntry(
        previewId = preview.id,
        label = label,
        keywords =
          listOfNotNull(preview.id, preview.label, preview.componentId, preview.section)
            .joinToString(" "),
      )
    }
  }

  /**
   * A front-page section a catalog may be published under: the [heading] shown, its count [noun],
   * the [repos] whose bytes are allowed to appear under it, and its section-order [priority]
   * ([ServeCatalogsConfig.Group.priority], highest first).
   */
  data class HomeGroup(
    val heading: String,
    val noun: String = ServeCatalogsConfig.DEFAULT_NOUN,
    val repos: Set<String> = emptySet(),
    val priority: Int = 0,
  )

  /**
   * A prebaked hero thumbnail on the front door: its immutable `/hero/<system>/<hash>.png` [path]
   * and the CSS-pixel size it lays out at. The crop is already in the pixels, so the card needs no
   * clip window; [width]/[height] are published as `<img>` attributes so the grid reserves the
   * right box before a single byte of image arrives (no reflow, no layout shift).
   */
  data class HeroImage(val path: String, val width: Int, val height: Int)

  /**
   * A thumbnail `<img>` for [src], optionally framed to its component content box ([crop]). With no
   * crop it's the plain image the card CSS scales to fit; with a crop it's wrapped in a fixed-size
   * `.cp-crop` clip window whose inline dimensions + negative offsets show only the component (a
   * Wear sticker's watch canvas is clipped away). [extraImgAttrs] carries per-call `<img>`
   * attributes (e.g. `loading="lazy"`). All numeric; [alt] is pre-escaped by the caller.
   */
  private fun thumbImg(
    src: String,
    alt: String,
    extraImgAttrs: String,
    crop: ContentCrop?,
  ): String {
    val img = "<img$extraImgAttrs alt=\"$alt\" src=\"$src\">"
    if (crop == null) return img
    // Geometry in PERCENTAGES of the box, not fixed px: the box sizes itself by aspect-ratio and
    // may
    // shrink under `max-width: 100%` on a narrow grid card, and the absolutely-positioned render
    // scales with it (a fixed-px window overflowed the card and clipped wide components). `height`
    // stays auto (the img keeps the render's aspect); `left` %s resolve against the box width,
    // `top`
    // against its aspect-ratio height.
    val w = cropPct(crop.imgW, crop.boxW)
    val l = cropPct(crop.left, crop.boxW)
    val t = cropPct(crop.top, crop.boxH)
    val cropped =
      "<img$extraImgAttrs alt=\"$alt\" src=\"$src\" style=\"width:${w}%;left:${l}%;top:${t}%\">"
    // A gutter window does not hide its overflow: the pixels outside the box are the component's
    // own shadow, and the window exists to line the box up with its neighbours, not to crop it.
    val cls = if (crop.clip) "cp-crop" else "cp-crop cp-crop--bleed"
    // The window's WIDTH is published as its relationship to the display cap, not as a frozen px
    // count: `--cp-crop-w-per-cap` is the box width per 1px of cap and `--cp-crop-max-w` the 1x
    // ceiling, so the stylesheet resolves `min(max-w, w-per-cap * --cp-thumb-cap)` and a narrow
    // viewport can lower the cap exactly as it lowers a plain `<img>`'s `max-height` (#4544 — a
    // cropped card drew 20% larger than its plain neighbour on a phone, because the 240px cap was
    // baked in here). Only the width is set, so `aspect-ratio` still derives the height and the box
    // scales rather than squashing. A hand-assembled crop carries no native size; it keeps the
    // fixed-px window.
    val sizing =
      if (crop.natBoxW > 0 && crop.natCapAxis > 0) {
        "--cp-crop-w-per-cap:${cropRatio(crop.natBoxW, crop.natCapAxis)};" +
          "--cp-crop-max-w:${crop.natBoxW}px"
      } else {
        "width:${crop.boxW}px"
      }
    return "<span class=\"$cls\" style=\"$sizing;aspect-ratio:${crop.boxW}/${crop.boxH}\">$cropped</span>"
  }

  /**
   * A crop dimension as a percentage of its box axis (e.g. `imgW/boxW`), formatted for a CSS
   * length: up to 4 decimals, locale-independent, trailing zeros trimmed (`0`, `119.5833`,
   * `-422.9167`). Kept exact enough that the framed component lands on the same pixels the old
   * fixed-px window did.
   */
  private fun cropPct(numerator: Int, denominator: Int): String {
    val v = numerator * 100.0 / denominator
    val s = String.format(java.util.Locale.ROOT, "%.4f", v)
    return if (s.contains('.')) s.trimEnd('0').trimEnd('.') else s
  }

  /**
   * A unitless CSS ratio (`numerator/denominator`), formatted like [cropPct] — up to 4 decimals,
   * locale-independent, trailing zeros trimmed. Used for the crop window's width-per-cap-pixel.
   */
  private fun cropRatio(numerator: Int, denominator: Int): String {
    val v = numerator.toDouble() / denominator
    val s = String.format(java.util.Locale.ROOT, "%.4f", v)
    return if (s.contains('.')) s.trimEnd('0').trimEnd('.') else s
  }

  /**
   * The public preview server's **front door**: an index of the systems it publishes, each a card
   * carrying a meaningful preview, the system's title + library, its trust badge, and a link to its
   * `/<system>/` catalog. This replaces showing an arbitrary default module's previews at `/` (the
   * point of `preview.coo.ee` is the catalogs, so the landing lists them rather than hiding them
   * behind a nav pill). Non-catalog `serve` (no `--catalogs`) keeps the plain [landingPage].
   *
   * Every card's imagery is **prebaked** ([HeroImage] / [ServeHeroImages]): a small,
   * already-cropped PNG on an immutable, content-hashed URL, loaded eagerly. Rendering the front
   * door therefore costs the server the HTML and nothing else — no render lane, no daemon, and on a
   * repeat visit no image requests at all.
   *
   * [systems] are the published catalogs (the `--catalogs` set), grouped into the Compose design
   * systems, Android's Compose samples, catalogs published by the `yschimke` GitHub organization,
   * and a final "Other" section for every remaining publisher (for example, Confetti from
   * `joreilly`). The sample catalogs are currently fetched from preview branches in the
   * `yschimke/compose-samples` fork, but they represent `android/compose-samples`; grouping by the
   * branch-trust origin would incorrectly present the fork as their publisher.
   * `--catalogs-unlisted` app catalogs are deliberately NOT indexed here — they're served at
   * `/<system>/` (shareable by direct link) but stay off the front door entirely, so an operator
   * can publish an app catalog without advertising it on the public landing.
   */
  fun homeIndexPage(
    systems: List<HomeSystem>,
    token: String,
    isPublic: Boolean = false,
    /**
     * Running server version (the CLI's `SERVE_VERSION`), surfaced in the minimal footer beside the
     * source/`/version` links so the live build is visible on the front door. Null omits it; the
     * fixture golden passes a fixed string so a release never churns the committed HTML.
     */
    version: String? = null,
    /** Absolute page + representative hero URLs for Open Graph/Twitter link previews. */
    unfurl: UnfurlMetadata? = null,
    githubAuth: GitHubAuthStatus? = null,
    componentBrowser: Boolean = false,
  ): String {
    val headerAction = if (componentBrowser) "" else githubAuthControl(githubAuth)
    // Public routes are open — no token param on the cards; a token-gated box keeps it.
    val tokenParam = if (isPublic) "" else "token=" + WebEscaping.urlEncodeSegment(token)
    val suffix = querySuffix(tokenParam)
    /**
     * The card's **compare to Figma** action: a chip in the card's own meta block, under the
     * preview count, deep-linking that catalog's comparison page straight to its `reference`
     * format.
     *
     * It is on the front door because the comparison is a destination people arrive *for*, and
     * until this it was reachable only from the chip row on a catalog's own landing page — so
     * "compare this system against its Figma" cost a visit to the catalog first, and was invisible
     * from `/` (compose-ai-tools#4324).
     *
     * The label names the design tool the catalog is actually specified by, for the same reason the
     * landing chip does: "compare to Figma" says what you get where "compare reference" would name
     * the format slug — and falls back to the landing's own neutral "compare to design references"
     * for a catalog whose references name no tool (a checked-in `png`, an `svg`, an unmapped
     * provider). Whether there is an action at all is [HomeSystem.hasReferenceComparison], never
     * the label: those are two questions, and answering the first with the second dropped the
     * action from every provider-neutral catalog (#4349).
     *
     * The accessible name carries the catalog's title ("Compose Material 3: compare to Figma")
     * while the visible text stays short. A front door lists many catalogs and several may name the
     * same tool, so half a dozen links otherwise announce identically as "compare to Figma" with
     * nothing in a screen-reader link list to tell them apart. The visible string is kept intact
     * inside the accessible name (WCAG 2.5.3 Label in Name), so "click compare to Figma" still
     * matches.
     *
     * It lives INSIDE the card, which is why the card is a `<div>` whose title carries the
     * `.cp-sys-open` link rather than being one big `<a>`: a link inside a link is not a thing HTML
     * has. `.cp-sys-open` stretches an overlay across the whole tile, so the tile is still one
     * click target, and the chip sits above that overlay as the one region that goes somewhere
     * else. The earlier shape hung the chip under the card in a wrapper cell, which meant a card
     * with an action was taller than one without unless an empty row was reserved for it — with the
     * chip inside, the grid's own stretch makes every card in a section the same size and the
     * reservation is gone.
     *
     * Suppressed in the component-browser ("Catalog") interface mode, which hides the format
     * comparisons on the catalog landing too — the mode is for browsing components, not for
     * auditing them against a design file.
     */
    fun compareAction(s: HomeSystem, sysSeg: String): String {
      if (componentBrowser || !s.hasReferenceComparison) return ""
      val query =
        listOf("format=reference", tokenParam).filter { it.isNotEmpty() }.joinToString("&")
      val href = WebEscaping.htmlEscape("/$sysSeg/compare?$query")
      val label =
        s.designToolLabel?.takeIf { it.isNotBlank() }?.let { "compare to $it" }
          ?: "compare to design references"
      val described = WebEscaping.htmlEscape("${s.title}: $label")
      return "\n            <p class=\"cp-sys-actions\">" +
        "<a class=\"cp-action-chip\" href=\"$href\" aria-label=\"$described\">" +
        "${WebEscaping.htmlEscape(label)}</a></p>"
    }
    fun card(s: HomeSystem): String {
      val sysSeg = WebEscaping.urlEncodeSegment(s.system)
      val title = WebEscaping.htmlEscape(s.title)
      val sysId = WebEscaping.htmlEscape(s.system)
      val hero = s.heroImage
      val img =
        if (hero != null) {
          // The fast path: a prebaked, already-cropped thumbnail on an immutable URL. `eager` (not
          // `lazy`) because these ARE the page — a dozen small PNGs the browser should start the
          // moment it sees them, rather than deferring past layout the way lazy-loading a
          // full-resolution render used to. The width/height attributes reserve the box up front.
          "<img loading=\"eager\" decoding=\"async\" width=\"${hero.width}\" height=\"${hero.height}\"" +
            " alt=\"$title preview\" src=\"${WebEscaping.htmlEscape(hero.path)}$suffix\">"
        } else if (s.heroPreviewId != null) {
          // Fallback: the live `/render` lane with a CSS clip window, for a catalog whose hero
          // couldn't be prebaked.
          val idSeg = WebEscaping.urlEncodeSegment(s.heroPreviewId)
          thumbImg(
            src = "/$sysSeg/render/$idSeg.png$suffix",
            alt = "$title preview",
            extraImgAttrs = " loading=\"lazy\"",
            crop = s.heroCrop,
          )
        } else {
          "<span class=\"cp-sys-noimg\">no preview</span>"
        }
      val desc =
        s.subtitle
          ?.takeIf { it.isNotBlank() }
          ?.let { "\n            <div class=\"cp-sys-desc\">${WebEscaping.htmlEscape(it)}</div>" }
          ?: ""
      val provenance =
        if (!componentBrowser) ""
        else
          s.sourceRepo
            ?.takeIf { it.isNotBlank() }
            ?.let {
              "\n            <div class=\"cp-browser-provenance\">${WebEscaping.htmlEscape(it)}</div>"
            } ?: ""
      val technicalId =
        if (componentBrowser) "" else "\n            <div class=\"cp-id\">$sysId</div>"
      val totals =
        if (componentBrowser) ""
        else
          "\n            <div class=\"cp-sys-foot\">${counted(s.previewCount, "preview(s)")}" +
            (if (s.views > 0) " · ${formatViews(s.views)}" else "") +
            "</div>"
      // A dark-first (Wear) system backs its hero on the dark stage — same `data-bg-theme` hook the
      // catalog grid and viewer use — so a light-on-transparent Wear sticker isn't washed out on
      // white.
      val bg = if (s.darkStage) " data-bg-theme=\"dark\"" else ""
      val searchAttr =
        " data-browser-search=\"${WebEscaping.htmlEscape("${s.title} ${s.system} ${s.subtitle.orEmpty()} ${s.sourceRepo.orEmpty()}").lowercase()}\""
      return """
      <div class="cp-card cp-sys"$bg$searchAttr>
        <div class="cp-imgwrap">$img</div>
        <div class="cp-meta">
          <div class="cp-sys-title"><a class="cp-sys-open" href="/$sysSeg/$suffix">$title</a>${homeTrustBadge(s.trust)}</div>$technicalId$desc$provenance${compareAction(s, sysSeg)}$totals
        </div>
      </div>
      """
        .trimIndent()
    }
    // Headings and nouns come from operator config (and, for the fallback sections, from a
    // catalog's own provenance), so they're escaped like any other data on the page.
    fun section(heading: String, list: List<HomeSystem>, noun: String, gridId: String): String {
      val head = WebEscaping.htmlEscape(heading)
      val count = WebEscaping.htmlEscape(counted(list.size, noun))
      return """
      <div class="cp-section-title">
        <h1 class="cp-head">$head</h1>
        ${if (componentBrowser) "" else "<span class=\"cp-section-count\">$count</span>"}
      </div>
      <div class="cp-grid cp-syslist" id="$gridId">
      ${list.joinToString("\n") { card(it) }}
      </div>
      """
        .trimIndent()
    }
    val sections = homeSections(systems)
    val catalogSearch =
      if (systems.isEmpty()) ""
      else
        """
        <div class="cp-browser-home-tools">
          <label class="cp-browser-search">
            <span class="cp-browser-search-icon" aria-hidden="true">⌕</span>
            <input id="cp-browser-catalog-search" class="cp-browser-search-input" type="search" autocomplete="off" spellcheck="false" placeholder="Search catalogs" aria-label="Search catalogs">
          </label>
        </div>
        <p id="cp-browser-catalog-empty" class="cp-empty" hidden>No catalogs match your search.</p>
        """
          .trimIndent() +
          """
          <script>(function(){var q=document.getElementById("cp-browser-catalog-search"),e=document.getElementById("cp-browser-catalog-empty");if(!q)return;q.addEventListener("input",function(){var n=q.value.trim().toLowerCase(),shown=0;document.querySelectorAll(".cp-sys").forEach(function(c){var hit=!n||(c.getAttribute("data-browser-search")||"").indexOf(n)>=0;c.hidden=!hit;if(hit)shown++;});document.querySelectorAll(".cp-section-title").forEach(function(h){var g=h.nextElementSibling;h.hidden=!!g&&!Array.prototype.some.call(g.children,function(c){return !c.hidden;});});if(e)e.hidden=shown!==0;});})();</script>
          """
            .trimIndent()
    val body =
      if (systems.isEmpty()) {
        "<h1 class=\"cp-head\">Design Systems</h1>\n" +
          "<p class=\"cp-sub\">No design systems are configured on this server.</p>"
      } else {
        catalogSearch +
          "\n" +
          sections
            .mapIndexed { index, s ->
              section(s.heading, s.systems, s.noun, if (index == 0) "cp-grid" else "cp-grid-$index")
            }
            .joinToString("\n")
      }
    val globalComponents =
      if (systems.isEmpty()) ""
      else "<span hidden data-cp-global-components=\"/api/components$suffix\"></span>\n"
    return document(
      title = "$HOME_TITLE — compose-preview",
      unfurlTitle = HOME_TITLE,
      unfurlDescription = homeUnfurlDescription(systems.size),
      unfurl = unfurl,
      navSuffix = suffix,
      headerAction = headerAction,
      version = version,
      body = body + if (globalComponents.isEmpty()) "" else "\n$globalComponents",
      componentBrowser = componentBrowser,
      interfaceModeControl = true,
    )
  }

  /**
   * What the front door calls itself, in its `<title>`, its `og:title` and the headline of its
   * unfurl card ([ServeSocialCard]).
   *
   * One constant because the three used to disagree: the tab said "Design systems" while the card
   * said "Compose previews", so a link's name changed depending on which of the two an unfurler
   * happened to prefer — and several fall back to `<title>` when they distrust the Open Graph
   * block. The product name is not lost by naming the *page* here: it is in `og:site_name`, in the
   * `<title>` suffix, and drawn on the card itself as the wordmark.
   */
  const val HOME_TITLE = "Design systems"

  /** The front door's `og:description`. */
  fun homeUnfurlDescription(systemCount: Int): String =
    "Browse $systemCount published Compose design system and app catalogs."

  /**
   * The line under the headline on the front door's unfurl card.
   *
   * Deliberately *not* [homeUnfurlDescription]: every client that shows the card also shows the
   * description beside it, so repeating the sentence in the picture wastes the only line the card
   * has. A stat line is the thing a reader can't get from the text around it.
   *
   * Both counts change only when a catalog is published or republished, which is what
   * [ServeSocialCard.Spec] requires of anything that reaches its cache key — a per-request value
   * here (a view tally, a timestamp) would mint an uncacheable card on every visit.
   */
  fun homeCardSubtitle(systems: List<HomeSystem>): String {
    val previews = systems.sumOf { it.previewCount }
    return "${systems.size} ${if (systems.size == 1) "catalog" else "catalogs"} · " +
      "$previews ${if (previews == 1) "preview" else "previews"}"
  }

  /**
   * The line under the headline on a catalog's unfurl card; the heading is already the headline.
   */
  fun catalogCardSubtitle(previewCount: Int): String =
    "$previewCount Compose ${if (previewCount == 1) "preview" else "previews"}"

  /**
   * A catalog's display name: what it calls itself, falling back to the module label. Shared by
   * [landingPage] and by the caller that builds that page's unfurl card, so the headline drawn on
   * the card cannot drift from the heading on the page it advertises.
   */
  fun catalogHeading(displayTitle: String?, moduleLabel: String): String =
    displayTitle?.takeIf { it.isNotBlank() } ?: moduleLabel

  /** A catalog page's `og:description`, and the text under its card's headline. */
  fun catalogUnfurlDescription(previewCount: Int, heading: String): String =
    "$previewCount Compose previews in $heading"

  /**
   * One publisher-grouped section of the front page: its heading, its cards, and its count noun.
   */
  data class HomeSection(val heading: String, val systems: List<HomeSystem>, val noun: String)

  /** Render legacy operator nouns such as `catalog(s)` as real singular/plural copy. */
  private fun counted(count: Int, noun: String): String {
    val singular = noun.replace("(s)", "")
    val plural = if (noun.contains("(s)")) singular + "s" else noun
    return "$count ${if (count == 1) singular else plural}"
  }

  /**
   * Group the published catalogs by **publisher**, for the front-page sections.
   *
   * The section a card lands in is **operator config, not code** ([ServeCatalogsConfig]): each
   * catalog entry names the group it's published under, and this reduces those declarations to
   * sections. Nothing here knows the id of any particular catalog — a server publishing catalogs
   * this build has never heard of gets the same grouping the first-party ones do.
   *
   * A declared group is a **claim, checked against provenance**. [HomeSystem.sourceRepo] — the
   * repository the catalog was generated from — must be one of the group's [HomeGroup.repos], which
   * are exactly the repos the operator named for that entry. Neither of the alternatives works on
   * its own:
   * * The **catalog id** is claimed by whoever publishes it. A third-party catalog served as
   *   `compose-m3` would otherwise be presented as an official design system purely for picking
   *   that name.
   * * The **trust verdict** names the branch the bytes were *fetched* from, which is a delivery
   *   detail: Android's samples are currently fetched from preview branches in the
   *   `yschimke/compose-samples` fork, and grouping on that would credit the fork owner for
   *   Android's work — which is what [ServeCatalogsConfig.Entry.attributionRepos] exists to
   *   express.
   *
   * A catalog whose claim doesn't hold — or that declares no group at all — falls back to its
   * source repo's **owner** section, and one with no provenance at all to "Other": unattributed,
   * never promoted.
   *
   * Sections come out by their group's [HomeGroup.priority] (highest first), then in
   * first-appearance (i.e. configured) order — so an operator orders the front page either by where
   * the catalogs sit in the list or, when that isn't enough, by saying so on the group
   * ([ServeCatalogsConfig.Group.priority]). A section whose group declares no priority, and one
   * derived from a repo owner, sit at 0; where two claims share a heading the section takes the
   * highest of them; "Other" is pinned last whatever it claims.
   */
  internal fun homeSections(systems: List<HomeSystem>): List<HomeSection> {
    val grouped = LinkedHashMap<String, MutableList<HomeSystem>>()
    val nouns = LinkedHashMap<String, String>()
    val priorities = LinkedHashMap<String, Int>()
    for (s in systems) {
      // The claim only holds when the bytes came from a repo the operator named for this entry.
      val claimed = s.group?.takeIf { g -> s.sourceRepo != null && s.sourceRepo in g.repos }
      val heading = claimed?.heading ?: ownerHeading(s.sourceRepo)
      grouped.getOrPut(heading) { mutableListOf() } += s
      nouns.putIfAbsent(heading, claimed?.noun ?: ServeCatalogsConfig.DEFAULT_NOUN)
      // Sections merge on the HEADING, which is operator text and neither unique nor validated as
      // such: two declared groups (or a group and an owner fallback) can spell the same one. So the
      // merged section takes the highest priority any of its claims declares — recording only the
      // first would leave a `priority: 100` group unlifted purely because a heading-mate with no
      // priority happened to register earlier.
      priorities.merge(heading, claimed?.priority ?: 0, ::maxOf)
    }
    val sections = grouped.map { (heading, list) ->
      HomeSection(heading, list, nouns.getValue(heading))
    }
    // sortedByDescending is stable, so equal priorities keep their first-appearance order.
    val ordered = sections.sortedByDescending { priorities.getValue(it.heading) }
    // "Other" is the unattributed bucket, so it reads last regardless of when it first appeared.
    return ordered.filterNot { it.heading == OTHER_HEADING } +
      ordered.filter { it.heading == OTHER_HEADING }
  }

  /** The heading an ungrouped catalog falls back to: its repo owner's, else the "Other" bucket. */
  private fun ownerHeading(sourceRepo: String?): String {
    val owner = sourceRepo?.substringBefore('/')?.takeIf { it.isNotBlank() && it != sourceRepo }
    return if (owner == null) OTHER_HEADING else "$owner repositories"
  }

  /** The catch-all section for catalogs carrying no usable provenance. */
  private const val OTHER_HEADING = "Other"

  /**
   * A styled **404** page for a browser that followed a dead link to a catalog or preview page
   * (`/nope-catalog/`, `/<system>/p/does-not-exist`) — so a broken navigation lands on the site's
   * own chrome with a way back home, rather than a bare `text/plain` "not found" dead-end. The
   * render / API lanes keep their plain-text 404; this is only for the HTML page routes. The back
   * link is built like [backButton] so it keeps the token on a gated ([isPublic] false) server.
   */
  fun notFoundPage(
    message: String,
    token: String,
    isPublic: Boolean,
    unfurl: UnfurlMetadata? = null,
    /**
     * Running server version (`SERVE_VERSION`), shown in the minimal footer. Null omits the build
     * span.
     */
    version: String? = null,
    /**
     * The catalog whose colours and name this page wears, when it is served on a **top-level site**
     * ([ServeSites]). A site hostname publishes one design system, so its `/status` and its 404 are
     * that system's pages too — carrying the palette and the theme key here is what makes the
     * *whole* hostname one skin rather than a themed catalog with unthemed chrome bolted beside it.
     * Empty (the default) on the main host, where these pages belong to no catalog and keep the
     * built-in chrome.
     */
    siteName: String = "",
    themeCss: String = "",
    themeStorageKey: String = "",
    componentBrowser: Boolean = false,
    githubAuth: GitHubAuthStatus? = null,
  ): String {
    val suffix = querySuffix(if (isPublic) "" else "token=" + WebEscaping.urlEncodeSegment(token))
    return document(
      title = "Not found — compose-preview",
      unfurlDescription = message,
      unfurl = unfurl,
      version = version,
      navSuffix = suffix,
      siteName = siteName,
      themeCss = themeCss,
      themeStorageKey = themeStorageKey,
      componentBrowser = componentBrowser,
      interfaceModeControl = true,
      headerAction = if (componentBrowser) "" else githubAuthControl(githubAuth),
      body =
        """
        <h1 class="cp-head">Not found</h1>
        <p class="cp-sub">${WebEscaping.htmlEscape(message)}</p>
        <a class="cp-back" href="/$suffix">${
          // On a site there is no index of systems to go back to — `/` is this catalog.
          if (siteName.isBlank()) "← All design systems" else "← Back"
        }</a>
        """
          .trimIndent(),
    )
  }

  /**
   * `GET /agent-access/{requestId}` — the page a human opens because an agent asked them to, and
   * the only place a grant is ever created. See
   * [docs/design/AGENT_ACCESS_GRANTS.md](../../../../../../../../docs/design/AGENT_ACCESS_GRANTS.md).
   *
   * The page has one job beyond collecting a click: **make the decision legible**. An operator
   * arrives here from a link they were handed, and everything they need in order to be suspicious
   * of it has to be on the screen — the verification code to compare against their terminal, the
   * label the agent supplied, where the request came from, and, in plain language, what each scope
   * lets the agent do to this machine.
   *
   * Everything the agent supplied ([label], [client]) is attacker-controlled text and is escaped
   * here without exception. [selectableScopes] has already been narrowed to what this approver may
   * actually give, so the form cannot offer a capability the POST would then refuse.
   */
  fun agentGrantApprovalPage(
    requestId: String,
    userCode: String,
    label: String,
    client: String,
    requestedScope: AgentGrantScope,
    requestedTtlSeconds: Long,
    expiresInSeconds: Long,
    approver: String,
    selectableScopes: List<AgentGrantScope>,
    maxTtlSeconds: Long,
    /**
     * Independent permissions this approver may tick, already narrowed like [selectableScopes].
     * Empty on almost every box — the whole feature is opt-in twice over.
     */
    selectableCapabilities: List<AgentGrantCapability> = emptyList(),
    approveCsrf: String,
    denyCsrf: String,
    /**
     * Form target — carries the access token on a token-gated box, so the POST stays authorized.
     */
    formAction: String,
    navSuffix: String = "",
    version: String? = null,
    siteName: String = "",
    themeCss: String = "",
    /**
     * Named only when the approver's own rights are what capped [selectableScopes] — so the page
     * says "you can't grant this" rather than silently omitting a row the agent asked for.
     */
    withheldScopes: List<AgentGrantScope> = emptyList(),
    /** Capabilities the agent asked for that this approver may not pass on. Same treatment. */
    withheldCapabilities: List<AgentGrantCapability> = emptyList(),
    withheldReason: String = "",
  ): String {
    val esc = WebEscaping::htmlEscape
    // **Radios, not checkboxes**, because the scopes are cumulative and independent boxes lie about
    // that. With `playground` offered, an approver could untick `live` while leaving `playground`
    // ticked — the page then said live access was withheld, and the grant included it anyway,
    // because `playground` implies `live` and the handler takes the highest ticked rung. On the one
    // page in this server whose entire job is to state accurately what is being agreed to, a
    // control that can misdescribe the outcome is the wrong control. One choice: the highest rung,
    // with everything it carries spelled out beneath it.
    // The **highest offered** rung is the default, not the requested one. Those differ exactly when
    // the approver's own rights capped the request — and there `requestedScope` matches no radio at
    // all, so the form opened with nothing selected and (being `required`) could not be submitted.
    // Defaulting to the top of what this approver may actually give is also the right answer on the
    // merits: it is the agent's ask, clamped to what the person in front of the page can grant.
    val defaultScope = selectableScopes.lastOrNull()
    val scopeRows =
      selectableScopes.joinToString("\n") { scope ->
        val checked = if (scope == defaultScope) " checked" else ""
        val includes =
          AgentGrantScope.upTo(scope).filter { it != scope }.joinToString(", ") { it.wire }
        val alsoIncludes =
          if (includes.isEmpty()) ""
          else "<span class=\"cp-grant-scope-implies\">also includes ${esc(includes)}</span>"
        """
        <label class="cp-grant-scope">
          <input type="radio" name="scope" value="${esc(scope.wire)}"$checked required>
          <span class="cp-grant-scope-name">${esc(scope.wire)}</span>
          <span class="cp-grant-scope-what">${esc(scope.humanDescription)}$alsoIncludes</span>
        </label>
        """
          .trimIndent()
      }
    // **Checkboxes here, radios above**, and the difference is not cosmetic. The scopes are a
    // ladder, so one control that picks a rung is the only honest way to draw them. A capability
    // implies nothing and is implied by nothing, so it is its own yes/no — and unticking one says
    // exactly what it looks like it says.
    //
    // Nothing is pre-ticked. An extra permission should be an act, not a default someone clicks
    // past: the agent asking for it is not the human agreeing to it, and this page exists to keep
    // those two separate.
    val capabilityRows =
      selectableCapabilities.joinToString("\n") { capability ->
        """
        <label class="cp-grant-scope">
          <input type="checkbox" name="capability" value="${esc(capability.wire)}">
          <span class="cp-grant-scope-name">${esc(capability.wire)}</span>
          <span class="cp-grant-scope-what">${esc(capability.humanDescription)}</span>
        </label>
        """
          .trimIndent()
      }
    val capabilityFieldset =
      if (selectableCapabilities.isEmpty()) ""
      else
        """
        <fieldset class="cp-grant-fieldset">
          <legend>Anything else the agent may do</legend>
          $capabilityRows
        </fieldset>
        """
          .trimIndent()
    val withheldCapabilityNote =
      if (withheldCapabilities.isEmpty()) ""
      else
        """
        <p class="cp-grant-withheld">Also asked for, not offered: ${
          esc(withheldCapabilities.joinToString(", ") { it.wire })
        } — ${esc(withheldReason)}</p>
        """
          .trimIndent()
    val withheld =
      if (withheldScopes.isEmpty()) ""
      else
        """
        <p class="cp-grant-withheld">Not offered: ${
          esc(withheldScopes.joinToString(", ") { it.wire })
        } — ${esc(withheldReason)}</p>
        """
          .trimIndent()
    val ttlOptions =
      ttlChoices(requestedTtlSeconds, maxTtlSeconds).joinToString("\n") { seconds ->
        val selected = if (seconds == requestedTtlSeconds) " selected" else ""
        "<option value=\"$seconds\"$selected>${esc(AgentGrantProtocol.formatDuration(seconds))}</option>"
      }
    return document(
      title = "Grant agent access — compose-preview",
      unfurlDescription = "An agent is asking for temporary access to this preview server.",
      version = version,
      navSuffix = navSuffix,
      siteName = siteName,
      themeCss = themeCss,
      body =
        """
        <h1 class="cp-head">Grant temporary access?</h1>
        <p class="cp-sub">An agent has asked for temporary access to this preview server. Approving mints a
        bearer token that expires on its own — nothing here changes this server's configuration, and you can
        revoke it at any time from <a href="/status$navSuffix">/status</a>.</p>

        <div class="cp-grant-code">
          <span class="cp-grant-code-label">Verification code</span>
          <code class="cp-grant-code-value">${esc(userCode)}</code>
          <span class="cp-grant-code-hint">This must match the code the agent printed. If it does not,
          you are looking at someone else's request — close this page.</span>
        </div>

        <dl class="cp-grant-facts">
          <dt>Purpose</dt><dd>${if (label.isBlank()) "<em>none given</em>" else esc(label)}</dd>
          <dt>Asked from</dt><dd>${esc(client)}</dd>
          <dt>Approving as</dt><dd>${esc(approver)}</dd>
          <dt>This request expires in</dt><dd>${esc(AgentGrantProtocol.formatDuration(expiresInSeconds))}</dd>
        </dl>

        <form class="cp-grant-form" method="post" action="${esc(formAction)}">
          <input type="hidden" name="csrf" value="${esc(approveCsrf)}">
          <fieldset class="cp-grant-fieldset">
            <legend>What the agent may do</legend>
            $scopeRows
          </fieldset>
          $capabilityFieldset
          $withheld
          $withheldCapabilityNote
          <label class="cp-grant-ttl">
            <span>Access expires after</span>
            <select name="ttl">
              $ttlOptions
            </select>
          </label>
          <div class="cp-grant-actions">
            <button class="cp-grant-approve" type="submit" name="action" value="approve">Approve</button>
            <button class="cp-grant-deny" type="submit" name="action" value="deny"
              formnovalidate>Deny</button>
          </div>
          <input type="hidden" name="denyCsrf" value="${esc(denyCsrf)}">
        </form>

        <p class="cp-grant-fineprint">The token is delivered to the agent that opened this request, not to
        whoever opens this link — so forwarding the link cannot leak it. Requested id
        <code>${esc(requestId.take(8))}…</code>.</p>
        """
          .trimIndent(),
    )
  }

  /**
   * The short page an approve/deny/expire lands on. Deliberately terminal — there is no link back
   * into the flow, because every path through it has already been decided and a "try again" button
   * would only ever re-submit a request that no longer exists.
   */
  fun agentGrantNoticePage(
    heading: String,
    message: String,
    navSuffix: String = "",
    version: String? = null,
    siteName: String = "",
    themeCss: String = "",
    /** Shown under the message when a grant was actually minted. */
    detail: String = "",
  ): String =
    document(
      title = "$heading — compose-preview",
      unfurlDescription = message,
      version = version,
      navSuffix = navSuffix,
      siteName = siteName,
      themeCss = themeCss,
      body =
        """
        <h1 class="cp-head">${WebEscaping.htmlEscape(heading)}</h1>
        <p class="cp-sub">${WebEscaping.htmlEscape(message)}</p>
        ${if (detail.isBlank()) "" else "<p class=\"cp-grant-detail\">${WebEscaping.htmlEscape(detail)}</p>"}
        <a class="cp-back" href="/status$navSuffix">← Server status</a>
        """
          .trimIndent(),
    )

  /**
   * The durations the approval page offers: a short ladder, plus whatever was actually requested,
   * clipped to the box's ceiling and de-duplicated. Ladder-only would drop the agent's own ask when
   * it happens to fall between rungs; request-only would make "give it ten minutes instead" a thing
   * you cannot do without the agent re-asking.
   */
  internal fun ttlChoices(requestedSeconds: Long, maxSeconds: Long): List<Long> =
    (LADDER + requestedSeconds)
      .filter { it in 1..maxSeconds }
      .distinct()
      .sorted()
      .ifEmpty { listOf(minOf(requestedSeconds.coerceAtLeast(1), maxSeconds)) }

  private val LADDER = listOf(15 * 60L, 60 * 60L, 4 * 60 * 60L, 8 * 60 * 60L, 24 * 60 * 60L)

  /**
   * The honest landing page for a preview URL pinned to a publish that did not contain that
   * preview.
   *
   * This remains a 404 — showing the current render under a historical URL would make the pin a lie
   * — but unlike the generic [notFoundPage] it keeps the catalog's revision navigator. A preview
   * can be added between publishes, so the catalog-wide revision menu can legitimately lead to a
   * commit where its id is absent. Dropping the menu at that point strands the visitor on the first
   * unavailable publish they try; keeping it lets them choose another historical publish or return
   * to current without pretending this one had pixels it never published.
   */
  fun unavailablePreviewRevisionPage(
    previewId: String,
    token: String,
    sessionId: String? = null,
    basePath: String = "",
    isPublic: Boolean = false,
    revisions: CatalogRevisions,
    unfurl: UnfurlMetadata? = null,
    version: String? = null,
    siteName: String = "",
    themeCss: String = "",
    themeStorageKey: String = "",
    sessionInOrigin: Boolean = false,
    /**
     * The catalog change feed the footer offers as **Changelog** and the head declares as this
     * page's RSS alternate. Empty when the server runs with the feed lane off. See [siteFooter].
     *
     * Offered here like on any other catalog page: this one is reached by pinning a preview to a
     * publication that predates it, which is exactly the moment a visitor wants the history.
     */
    changelogHref: String = "",
  ): String {
    val linkSessionId = if (sessionInOrigin) null else sessionId
    val idSeg = WebEscaping.urlEncodeSegment(previewId)
    val query = querySuffix(linkQuery(token, linkSessionId, basePath, isPublic))
    val hrefFor: (String?) -> String = { pin -> withPin("$basePath/p/$idSeg$query", pin) }
    val revisionMenu = revisionsHtml(revisions, includeBanner = false, hrefFor = hrefFor)
    val pin = revisions.pinned?.let(ServeCatalogRevision::short).orEmpty()
    val message =
      if (pin.isBlank()) "That preview does not exist in this catalog."
      else "This preview was not published in catalog revision $pin."
    val suffix = querySuffix(if (isPublic) "" else "token=" + WebEscaping.urlEncodeSegment(token))
    return document(
      title = "Preview unavailable — compose-preview",
      unfurlDescription = message,
      unfurl = unfurl,
      version = version,
      navSuffix = suffix,
      siteName = siteName,
      themeCss = themeCss,
      themeStorageKey = themeStorageKey,
      changelogHref = changelogHref,
      body =
        """
        <h1 class="cp-head">Preview unavailable</h1>
        <p class="cp-sub">${WebEscaping.htmlEscape(message)} Choose another revision or return to the current catalog.</p>
        $revisionMenu
        <a class="cp-back" href="${WebEscaping.htmlEscape(hrefFor(null))}">← View current preview</a>
        """
          .trimIndent(),
    )
  }

  /**
   * `GET /playground` — the **Stage-1 editor** for the Kotlin playground
   * (`docs/design/PLAYGROUND.md` §2). A code box + a mode selector + a Run button that POSTs to
   * `/api/{v}/compiler/run` and shows the compiler diagnostics, the first-frame render, and the
   * handoff link: **Open live preview →** (`/pg/<token>`, the CMP/Android live modes) or **Open
   * document →** (`/d/<id>`, Remote Compose).
   *
   * The lane compiles and runs user-supplied code on the server, so it is only ever mounted behind
   * a token (refused under `--public`); the page therefore always carries a `?token=…` suffix on
   * the links it builds. A plain `<textarea>` is the v1 editor — a stock `kotlin-playground` /
   * bespoke CodeMirror surface is a deferred, non-blocking decision (design §7 item 5).
   */
  fun playgroundPage(
    token: String,
    isPublic: Boolean,
    /**
     * What the catalog selector offers, first entry preselected: the host's pinned default (id
     * `""`, present only when a `--playground-bundle` resolved) followed by every served catalog a
     * snippet may be compiled against. Each entry carries its own mode list — a catalog's bundle
     * backend decides its renderer — so the Mode control is repopulated from the selected entry
     * rather than offering modes the host would then refuse.
     *
     * May be **empty** on a `--playground` host during startup: catalogs are fetched in the
     * background after the server is up. The page says so and refreshes itself from
     * `/api/1/compiler/catalogs` rather than making the visitor reload.
     */
    catalogs: List<PlaygroundCatalogInfo>,
    /**
     * True when `--playground` configured a runtime catalog selector on this host — independent of
     * whether any catalog has loaded into it yet.
     *
     * Kept separate from `catalogs.size` on purpose. A host running `--playground` *plus* a pinned
     * local bundle renders, during the startup window, a one-entry list holding only that pin — and
     * deciding on the count alone would omit the control from that page, which the script can then
     * never build, leaving the visitor pinned until they reload. What the control's presence tracks
     * is the host's configuration, which does not change under it.
     */
    catalogSelectorEnabled: Boolean = false,
    /**
     * A served preview's source, opened in place of the starter sample with its catalog preselected
     * — the `/playground?from=<system>/<previewId>` handoff from a viewer page. Null is the
     * ordinary "opened the playground directly" case.
     */
    seed: PlaygroundSeed? = null,
    /**
     * Preselect this catalog without seeding any source — the "try this design system" handoff from
     * a catalog landing page. Ignored when [seed] is present, which carries its own catalog.
     */
    preselectCatalog: String? = null,
    /**
     * The served-catalog system ids this host's **pinned** default compiles against
     * ([PlaygroundCompileService.pinnedCatalogSystems]). The selector reports a pin under the
     * anonymous id `""`, so without this a `?from=compose-m3/…` handoff on a host pinned to
     * `compose-m3` would look unrecognised — the one case where the buffer *is* opening against its
     * own catalog.
     */
    pinnedCatalogSystems: Set<String> = emptySet(),
    unfurl: UnfurlMetadata? = null,
    /**
     * Running server version (`SERVE_VERSION`), shown in the minimal footer. Null omits the build
     * span.
     */
    version: String? = null,
    /** Show the authenticated, server-enabled single stateful editing lease control. */
    editingLeaseEnabled: Boolean = false,
  ): String {
    val suffix = querySuffix(queryString(token, sessionId = null, isPublic = isPublic))
    val sample = WebEscaping.htmlEscape(seed?.text ?: PLAYGROUND_SAMPLE)
    val fileName = seed?.fileName ?: "Snippet.kt"
    // A seed names its own catalog; a catalog-page link names one without any source. Either way it
    // only wins if this host actually offers it — a link built before a catalog loaded (or against
    // one whose backend this host can't render) falls back to the first entry rather than
    // preselecting something the Run button would refuse.
    val handoffCatalog = seed?.catalog ?: preselectCatalog
    // Two ways this host can offer the named catalog: as the selector's own entry for it, or as the
    // pinned default (which the selector reports under the anonymous id `""`).
    val wantedIndex = handoffCatalog?.let { system ->
      catalogs
        .indexOfFirst {
          it.system == system &&
            (seed?.sourceModule.isNullOrBlank() || it.module == seed.sourceModule)
        }
        .takeIf { it >= 0 }
        ?: catalogs
          .indexOfFirst { it.id.isEmpty() }
          .takeIf { it >= 0 && system in pinnedCatalogSystems }
    }
    val selectedIndex = wantedIndex ?: 0
    // A host that pins its bundles and offers no runtime choice renders exactly the bar it always
    // did — one Mode select — rather than a one-entry "Catalog" control that decides nothing.
    // Everything else gets the control, including the two states where the list is momentarily
    // uninteresting: empty (nothing has loaded yet) and pin-only under [catalogSelectorEnabled].
    // Both fill in from the script's refresh, and the script can only fill in a control that
    // exists.
    val showCatalogs =
      catalogSelectorEnabled ||
        catalogs.isEmpty() ||
        catalogs.size > 1 ||
        catalogs.first().id.isNotEmpty()
    val catalogOptions =
      if (catalogs.isEmpty())
        """<option value="" disabled selected>No catalogs available yet…</option>"""
      else
        catalogs
          .mapIndexed { i, c ->
            val selected = if (i == selectedIndex) " selected" else ""
            """<option value="${WebEscaping.htmlEscape(c.id)}"$selected>${
              WebEscaping.htmlEscape(c.label)
            }</option>"""
          }
          .joinToString("\n              ")
    val options =
      catalogs
        .getOrNull(selectedIndex)
        ?.modes
        .orEmpty()
        .mapIndexed { i, mode ->
          val (value, label) = playgroundModeChoice(mode)
          val selected = if (i == 0) " selected" else ""
          """<option value="$value"$selected>$label</option>"""
        }
        .joinToString("\n              ")
    // Hand-indented to sit at the interpolation point's column (12) — a `trimIndent()`ed block
    // would
    // re-flush every line but the first back to column 0 in the emitted page.
    val catalogRow =
      if (!showCatalogs) ""
      else
        listOf(
            """<label class="cp-pg-modelabel" for="pg-catalog">Catalog</label>""",
            """<select id="pg-catalog" class="cp-pg-mode">""",
            "  $catalogOptions",
            "</select>",
            "",
          )
          .joinToString("\n            ")
    // "Nothing to compile against" has two causes that look identical from here — catalogs load in
    // the background (transient, self-healing) and a catalog must verify as trusted *and* carry a
    // liveBundle to back a compile (permanent, a config problem). Naming both beats leaving an
    // operator staring at an empty selector wondering which one they have.
    val emptyNote =
      if (catalogs.isNotEmpty()) ""
      else
        """

          <p id="pg-empty" class="cp-sub">No catalog can back a compile here yet. Catalogs are
            fetched in the background after the server starts, so this usually clears on its own —
            but a catalog also has to verify as <strong>trusted</strong> and publish a live bundle
            before the playground will compile against it.</p>"""
    // A handoff naming a catalog this host does not compile against. The link that built it is now
    // withheld at the source ([ServeHttpServer.playgroundLinkFor]), so reaching this means a
    // bookmark, a shared URL, or a hand-typed one — plus the genuinely transient case of a catalog
    // that has not finished loading. Either way the previous behaviour was the worst of the three
    // options: preselect the first entry, open the buffer, and let Run report a screen of
    // unresolved references against a design system nobody chose. Say it before the visitor spends
    // a compile finding out.
    //
    // Suppressed while the list is empty — [emptyNote] is already explaining that same state, and
    // better ("this usually clears on its own").
    val unavailableNote =
      if (handoffCatalog == null || wantedIndex != null || catalogs.isEmpty()) ""
      else {
        val target =
          catalogs.getOrNull(selectedIndex)?.let { entry ->
            val label = if (entry.id.isEmpty()) "this server's default catalog" else entry.id
            "<code>${WebEscaping.htmlEscape(label)}</code>"
          } ?: "the selected catalog"
        """

          <p id="pg-catalog-unavailable" class="cp-sub cp-pg-warn"><strong>This server cannot
            compile against <code>${WebEscaping.htmlEscape(handoffCatalog)}</code>.</strong> The
            playground compiles a snippet against one catalog's own classpath and renders it on that
            catalog's backend, and this host offers neither for that design system — most often
            because it serves Android and Wear catalogs for browsing while running only the desktop
            (Skiko) render backend, which is what an Android catalog's previews need. The editor is
            open on $target instead, so anything below that names
            <code>${WebEscaping.htmlEscape(handoffCatalog)}</code>'s own types will not resolve.
            Pick another catalog above, or start from the sample.</p>"""
      }
    // Says whose code is in the buffer, and is honest that it is a starting point: a preview file
    // is ordinary module code and may reference siblings the catalog's bundle never exported, so
    // "opened from" is the claim, not "this compiles".
    val seedNote =
      if (seed == null) ""
      else {
        val where =
          seed.blobUrl?.let {
            """<a class="cp-source-link" href="${WebEscaping.htmlEscape(it)}">${
                WebEscaping.htmlEscape(seed.fileName)
              }</a>"""
          } ?: WebEscaping.htmlEscape(seed.fileName)
        // Which of the three it is matters to a reader, and they promise different things. A
        // cleaned seed is usage code — the catalog's annotations, sticker frame, click tally and
        // knobs resolved away — so it may say "ready to Run"; the other two may not.
        if (seed.cleaned && !seed.scaffoldsDeclared) {
          // Cleaned, but by the generic rules alone: this catalog has not said what its own helpers
          // mean, so only the shared annotations came off and its `Sticker`/`counted`/knob calls
          // are
          // still in the buffer. Claiming "the sticker frame and knobs are gone, press Run" here
          // would be describing a different seed than the one on screen.
          """

          <p id="pg-seed" class="cp-sub">Opened from $where — <code>${
              WebEscaping.htmlEscape(seed.previewId)
            }</code> in <code>${WebEscaping.htmlEscape(seed.catalog)}</code>, with the catalog
            annotations removed. <code>${
              WebEscaping.htmlEscape(seed.catalog)
            }</code> has not declared what its own helpers mean in plain Compose, so the ones this
            preview uses are still here and will not resolve against the published catalog — delete
            them or replace them with your own values.</p>"""
        } else if (seed.cleaned) {
          val caveat =
            if (seed.residue.isEmpty()) ""
            else {
              val names =
                seed.residue.joinToString(", ") { "<code>${WebEscaping.htmlEscape(it)}</code>" }
              " Some of this catalog's own helpers ($names) had no plain-Compose form to rewrite " +
                "to, so they are still here and will not resolve — delete them or replace them " +
                "with your own values."
            }
          """

          <p id="pg-seed" class="cp-sub">Opened from $where — <code>${
              WebEscaping.htmlEscape(seed.previewId)
            }</code> in <code>${WebEscaping.htmlEscape(seed.catalog)}</code>, rewritten as the
            plain Compose that produces this render. The catalog's annotations, sticker frame and
            variant knobs are not code you need in order to use the component, so they are gone.
            Press Run.$caveat</p>"""
        } else if (seed.sliced)
          """

          <p id="pg-seed" class="cp-sub">Opened from $where — the declaration of
            <code>${WebEscaping.htmlEscape(seed.previewId)}</code>, plus that file's imports, from
            <code>${WebEscaping.htmlEscape(seed.catalog)}</code>. Just this one composable, not the
            whole file, but otherwise its source verbatim — so anything it pulls in from elsewhere
            in its own module shows up as an unresolved reference to delete.</p>"""
        else
          """

          <p id="pg-seed" class="cp-sub">Opened $where — the file
            <code>${WebEscaping.htmlEscape(seed.previewId)}</code> is declared in, from
            <code>${WebEscaping.htmlEscape(seed.catalog)}</code>. It is the whole file, not a
            trimmed snippet, and it compiles against that catalog's classpath: anything it pulls in
            from elsewhere in its own module shows up as an unresolved reference to delete.</p>"""
      }
    val catalogData =
      jsString(
        JSON_COMPACT.encodeToString(
          PlaygroundCatalogsResponse.serializer(),
          PlaygroundCatalogsResponse(catalogs),
        )
      )
    val editLeaseButton =
      if (!editingLeaseEnabled) ""
      else
        """
            <button id="pg-edit-lease" class="cp-doc-btn" type="button">Acquire editing lease</button>"""
    val editLeaseNote =
      if (!editingLeaseEnabled) ""
      else
        """
          <p id="pg-edit-lease-note" class="cp-pg-status" hidden></p>"""
    return document(
      title = "Playground — compose-preview",
      unfurlDescription = "Compile a Compose snippet against the live catalog and open a preview.",
      unfurl = unfurl,
      version = version,
      navSuffix = suffix,
      body =
        """
        <link rel="stylesheet" href="${assetHref("codemirror.css")}">
        <link rel="stylesheet" href="${assetHref("playground.css")}">
        <h1 class="cp-head">Playground</h1>
        <p class="cp-sub">Write a Compose snippet, compile it against the live catalog, and open a
          preview. This lane runs your code on the server, so it stays behind your token.</p>
        <div class="cp-pg">$emptyNote$unavailableNote$seedNote
          <div class="cp-pg-bar">
            $catalogRow<label class="cp-pg-modelabel" for="pg-mode">Mode</label>
            <select id="pg-mode" class="cp-pg-mode">
              $options
            </select>
            <button id="pg-run" class="cp-doc-btn cp-pg-run" type="button">Run</button>$editLeaseButton
          </div>$editLeaseNote
          <div id="pg-files" class="cp-pg-files" role="tablist" aria-label="Snippet files">
            <button class="cp-pg-file" type="button" role="tab" aria-current="true"
              data-pg-file="${WebEscaping.htmlEscape(fileName)}">${
                WebEscaping.htmlEscape(fileName)
              }</button>
            <button id="pg-add-file" class="cp-pg-filebtn" type="button">+ file</button>
            <button id="pg-remove-file" class="cp-pg-filebtn" type="button" hidden>Remove file</button>
          </div>
          <textarea id="pg-source" class="cp-pg-source" spellcheck="false"
            aria-label="Kotlin source">$sample</textarea>
          <div id="pg-status" class="cp-pg-status" role="status" hidden></div>
          <ul id="pg-diagnostics" class="cp-pg-diags" aria-live="polite" hidden></ul>
          <div id="pg-result" class="cp-doc-result cp-pg-result" hidden>
            <p id="pg-preview-note" class="cp-pg-status" hidden></p>
            <img id="pg-image" class="cp-pg-image" alt="Rendered first frame" hidden>
            <p id="pg-open-row" hidden>
              <a id="pg-open" class="cp-doc-btn" href="#" rel="noopener">Open preview →</a>
            </p>
            <ul id="pg-previews" class="cp-pg-diags" hidden
              aria-label="Previews declared by this snippet"></ul>
          </div>
        </div>
        ${scriptTag("codemirror.js")}
        <script>${playgroundScript(suffix, catalogData, fileName)}</script>
        """
          .trimIndent(),
    )
  }

  /**
   * Drives the playground editor: POST the snippet + mode, render the diagnostics/first-frame, and
   * surface the `/pg/<token>` (live) or `/d/<id>` (Remote Compose) handoff link. Kept
   * dependency-free (no bundle) so the page is one self-contained document.
   */
  private fun playgroundScript(
    querySuffix: String,
    catalogsJson: String,
    fileName: String,
  ): String =
    """
    (function () {
      var source = document.getElementById("pg-source");
      var mode = document.getElementById("pg-mode");
      var catalog = document.getElementById("pg-catalog");
      var run = document.getElementById("pg-run");
      var editLeaseButton = document.getElementById("pg-edit-lease");
      var editLeaseNote = document.getElementById("pg-edit-lease-note");
      var fileBar = document.getElementById("pg-files");
      var addFile = document.getElementById("pg-add-file");
      var removeFile = document.getElementById("pg-remove-file");
      var statusEl = document.getElementById("pg-status");
      var diags = document.getElementById("pg-diagnostics");
      var result = document.getElementById("pg-result");
      var image = document.getElementById("pg-image");
      var openRow = document.getElementById("pg-open-row");
      var openLink = document.getElementById("pg-open");
      var note = document.getElementById("pg-preview-note");
      var previewList = document.getElementById("pg-previews");
      var suffix = ${jsString(querySuffix)};
      var editLease = null;
      var editRevision = 0;
      // One holder per document/tab: closing one tab must not tear down another tab's shared
      // owner-wide incremental workspace.
      var editClient = (window.crypto && typeof window.crypto.randomUUID === "function")
        ? window.crypto.randomUUID()
        : (Date.now().toString(36) + "-" + Math.random().toString(36).slice(2));
      function releaseEditLeaseOnDiscard() {
        if (!editLease) return;
        var body = JSON.stringify({ lease: editLease, client: editClient });
        var url = "/api/1/compiler/edit-lease/release" + suffix;
        editLease = null;
        if (navigator.sendBeacon) {
          navigator.sendBeacon(url, new Blob([body], { type: "application/json" }));
        } else {
          fetch(url, {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: body,
            credentials: "same-origin",
            keepalive: true
          }).catch(function () {});
        }
      }
      window.addEventListener("pagehide", function (event) {
        // A bfcache page is still alive and may be restored; an actually discarded page should
        // surrender the one host-wide lease immediately. Delivery remains best-effort, with the
        // server TTL as the hard fallback.
        if (!event.persisted) releaseEditLeaseOnDiscard();
      });
      function setEditLease(value, message, expiresAt, revision) {
        editLease = value;
        editRevision = value && Number.isFinite(revision) ? revision : 0;
        if (!editLeaseButton) return;
        editLeaseButton.disabled = false;
        editLeaseButton.textContent = value ? "Release editing lease" : "Acquire editing lease";
        editLeaseButton.setAttribute("aria-pressed", value ? "true" : "false");
        editLeaseNote.hidden = !message;
        editLeaseNote.textContent = message || "";
        if (value && expiresAt) {
          editLeaseButton.title = "Lease expires " + new Date(expiresAt).toLocaleTimeString();
        } else {
          editLeaseButton.removeAttribute("title");
        }
      }
      if (editLeaseButton) {
        editLeaseButton.addEventListener("click", function () {
          editLeaseButton.disabled = true;
          if (!editLease) {
            fetch("/api/1/compiler/edit-lease" + suffix, {
              method: "POST",
              headers: { "Accept": "application/json", "Content-Type": "application/json" },
              body: JSON.stringify({ client: editClient })
            })
              .then(function (r) {
                return r.json().then(function (body) {
                  if (!r.ok) throw new Error(body.message || "The editing lease is busy.");
                  return body;
                });
              })
              .then(function (body) {
                setEditLease(body.lease, body.message, body.expiresAtEpochMs, body.revision);
              })
              .catch(function (e) {
                setEditLease(null, e.message || "Could not acquire editing lease.");
              });
          } else {
            var releasing = editLease;
            fetch("/api/1/compiler/edit-lease/release" + suffix, {
              method: "POST",
              headers: { "Content-Type": "application/json" },
              body: JSON.stringify({ lease: releasing, client: editClient })
            })
              .then(function (r) {
                if (!r.ok) throw new Error("The editing lease has already expired.");
                setEditLease(null, "Editing lease released.");
              })
              .catch(function (e) { setEditLease(null, e.message); });
          }
        });
      }
      // The catalog selector. Each entry carries its own mode list because a catalog's bundle
      // backend picks the renderer — selecting `compose-m3` (desktop) and selecting an Android
      // catalog are not the same choice with a different classpath, they are different modes.
      var catalogs = JSON.parse($catalogsJson).catalogs || [];
      var modeLabels = {${
      PlaygroundMode.entries.joinToString(", ") { m ->
        val (value, label) = playgroundModeChoice(m)
        "${jsString(value)}: ${jsString(label)}"
      }
    }};
      function selectedCatalog() {
        var id = catalog ? catalog.value : "";
        for (var i = 0; i < catalogs.length; i++) if (catalogs[i].id === id) return catalogs[i];
        return catalogs.length ? catalogs[0] : null;
      }
      // Repopulate Mode from the selected catalog, keeping the current mode when that catalog still
      // offers it — switching between two desktop catalogs must not silently reset the mode.
      function syncModes() {
        var entry = selectedCatalog();
        var wanted = mode.value;
        var offered = entry ? (entry.modes || []) : [];
        mode.innerHTML = "";
        offered.forEach(function (m) {
          var opt = document.createElement("option");
          opt.value = m;
          opt.textContent = modeLabels[m] || m;
          mode.appendChild(opt);
        });
        if (offered.indexOf(wanted) >= 0) mode.value = wanted;
        mode.disabled = offered.length === 0;
        run.disabled = offered.length === 0;
      }
      // Catalogs are fetched in the BACKGROUND after the server starts, so a page opened during
      // startup legitimately renders a short (or empty) list. Re-ask rather than making the visitor
      // guess that a reload would help.
      //
      // ONE fetch is not enough: on a host with nothing pinned the editor commonly loads before the
      // initial catalog loader has published anything, so the single reply is empty too and nothing
      // would ever ask again — a permanently disabled Run on a host that came up fine seconds later.
      // So poll while the answer is still empty, bounded (a host that genuinely serves no compilable
      // catalog must not poll forever), and stop the moment something is offered.
      var emptyPolls = 0;
      var MAX_EMPTY_POLLS = 12;
      var POLL_MS = 2500;
      function refreshCatalogs() {
        fetch("/api/1/compiler/catalogs" + suffix, { headers: { "Accept": "application/json" } })
          .then(function (r) { return r.ok ? r.json() : null; })
          .then(function (res) {
            if (!res || !res.catalogs) return;
            var previous = catalog ? catalog.value : "";
            catalogs = res.catalogs;
            if (catalog) {
              catalog.innerHTML = "";
              catalogs.forEach(function (c) {
                var opt = document.createElement("option");
                opt.value = c.id;
                opt.textContent = c.label;
                catalog.appendChild(opt);
              });
              if (!catalogs.length) {
                var none = document.createElement("option");
                none.value = ""; none.disabled = true; none.selected = true;
                none.textContent = "No catalogs available yet…";
                catalog.appendChild(none);
              } else {
                var keep = false;
                for (var i = 0; i < catalogs.length; i++) {
                  if (catalogs[i].id === previous) keep = true;
                }
                catalog.value = keep ? previous : catalogs[0].id;
              }
            }
            var empty = document.getElementById("pg-empty");
            if (empty) empty.hidden = catalogs.length > 0;
            syncModes();
            if (!catalogs.length && ++emptyPolls < MAX_EMPTY_POLLS) {
              window.setTimeout(refreshCatalogs, POLL_MS);
            }
          })
          .catch(function () { /* the baked-in list still stands */ });
      }
      if (catalog) {
        catalog.addEventListener("change", syncModes);
        // Opening the dropdown is the one moment a stale list actually costs the visitor something,
        // and it's a cheap place to catch catalogs that finished loading after the poll gave up.
        catalog.addEventListener("focus", refreshCatalogs);
      }
      syncModes();
      // Unconditional, not just when there is a selector: a page opened before the host's own pinned
      // bundle finished resolving renders with no modes at all, and the refresh is what recovers it
      // without asking the visitor to reload.
      refreshCatalogs();
      // CodeMirror over the textarea when the vendored bundle loaded, plain textarea when it
      // didn't. Every read/write of the buffer goes through readSource/writeSource, so a failed
      // asset fetch degrades to exactly the pre-editor behaviour instead of a dead page — the
      // editor is a convenience, and the compile lane is the feature.
      var editor = null;
      if (window.CodeMirror) {
        editor = window.CodeMirror.fromTextArea(source, {
          mode: "text/x-kotlin",
          lineNumbers: true,
          // `fromTextArea` hides the original textarea, which takes its aria-label out of the
          // accessibility tree with it. CodeMirror only names its own generated input through
          // this option, so without it a screen reader announces an unlabelled edit box.
          screenReaderLabel: "Kotlin source",
          indentUnit: 4,
          // Kotlin is space-indented; without this Tab inserts a literal tab that the compiler
          // accepts but nobody wants pasted back into a file.
          indentWithTabs: false,
          matchBrackets: true,
          viewportMargin: Infinity,
        });
        // Tab as INDENT, not focus-escape. A code box that swallows Tab is a keyboard trap, so
        // Esc first moves focus out — the standard escape hatch (WCAG 2.1.2).
        editor.setOption("extraKeys", {
          Tab: function (cm) { cm.execCommand("indentMore"); },
          "Shift-Tab": function (cm) { cm.execCommand("indentLess"); },
          Esc: function (cm) { cm.getInputField().blur(); },
          "Ctrl-Enter": function () { run.click(); },
          "Cmd-Enter": function () { run.click(); },
        });
      }
      function readSource() { return editor ? editor.getValue() : source.value; }
      function writeSource(text) {
        if (editor) editor.setValue(text); else source.value = text;
      }
      // CodeMirror diagnostics are deliberately drawn by this page instead of depending on its
      // optional lint addon: the compiler already returns exact 0-based locations, and keeping the
      // tiny renderer here preserves the editor's plain-textarea fallback. The list below the
      // editor remains the accessible summary; these widgets put each message beside the code that
      // caused it, which is where it is useful while fixing a failed compile.
      var editorDiags = [];
      var latestDiags = [];
      function clearEditorDiags() {
        if (!editor) return;
        editorDiags.forEach(function (entry) {
          if (entry.widget) entry.widget.clear();
          if (entry.lineHandle) editor.removeLineClass(entry.lineHandle, "background", entry.lineClass);
          if (entry.mark) entry.mark.clear();
        });
        editorDiags = [];
      }
      function renderEditorDiags() {
        clearEditorDiags();
        if (!editor) return;
        var file = files[active];
        latestDiags.forEach(function (d) {
          if (d.file !== file.name || d.line == null || d.line < 0 || d.line >= editor.lineCount()) return;
          var severity = d.severity || "info";
          var lineClass = "cp-pg-line-" + severity;
          // CodeMirror moves this handle with the line when edits are inserted above it. Keeping
          // the original numeric index would clear whichever line later occupied that position and
          // strand the actual diagnostic highlight after the next compile.
          var lineHandle = editor.addLineClass(d.line, "background", lineClass);
          var message = document.createElement("div");
          message.className = "cp-pg-inline-diag cp-pg-inline-" + severity;
          // The live summary below already announces the same diagnostic. Keep this visual copy
          // out of the accessibility tree so a compile failure is not read twice.
          message.setAttribute("aria-hidden", "true");
          message.textContent = d.message;
          var widget = editor.addLineWidget(d.line, message, { coverGutter: false, noHScroll: true });
          var mark = null;
          if (d.ch != null) {
            var lineText = editor.getLine(d.line) || "";
            var start = Math.max(0, Math.min(d.ch, lineText.length));
            var endLine = d.endLine == null ? d.line : Math.max(d.line, d.endLine);
            var endCh = d.endCh == null ? Math.min(lineText.length, start + 1) : d.endCh;
            if (endLine !== d.line || endCh > start) {
              mark = editor.markText(
                { line: d.line, ch: start },
                { line: endLine, ch: endCh },
                { className: "cp-pg-range-" + severity }
              );
            }
          }
          editorDiags.push({ widget: widget, lineHandle: lineHandle, lineClass: lineClass, mark: mark });
        });
      }
      // The snippet is a LIST of files compiled as one module, not one file: `files` holds every
      // buffer, `active` is the one the textarea is showing. A single-file snippet keeps exactly
      // the old shape, so nothing about the common case changes.
      var files = [{ name: ${jsString(fileName)}, text: readSource() }];
      var active = 0;
      function uniqueName(name) {
        var taken = {}; files.forEach(function (f) { taken[f.name.toLowerCase()] = true; });
        if (!taken[name.toLowerCase()]) return name;
        var stem = name.replace(/\.kt${'$'}/, "");
        for (var i = 1; ; i++) {
          var candidate = stem + "_" + i + ".kt";
          if (!taken[candidate.toLowerCase()]) return candidate;
        }
      }
      function renderFiles() {
        // Rebuild the tab strip from `files`; the +/- buttons are kept, not recreated.
        var tabs = fileBar.querySelectorAll("[data-pg-file]");
        for (var i = 0; i < tabs.length; i++) fileBar.removeChild(tabs[i]);
        files.forEach(function (f, i) {
          var tab = document.createElement("button");
          tab.type = "button";
          tab.className = "cp-pg-file";
          tab.setAttribute("role", "tab");
          tab.setAttribute("data-pg-file", f.name);
          tab.setAttribute("aria-current", i === active ? "true" : "false");
          tab.textContent = f.name;
          tab.addEventListener("click", function () { showFile(i); });
          fileBar.insertBefore(tab, addFile);
        });
        removeFile.hidden = files.length < 2;
      }
      addFile.addEventListener("click", function () {
        files[active].text = readSource();
        // Auto-named rather than prompted: Kotlin does not tie declarations to a file name, so the
        // name only ever shows up in diagnostics — not worth a modal dialog on every added file.
        var name = uniqueName("File" + (files.length + 1) + ".kt");
        files.push({ name: name, text: "" });
        active = files.length - 1;
        writeSource("");
        renderFiles();
        if (editor) editor.focus(); else source.focus();
      });
      removeFile.addEventListener("click", function () {
        if (files.length < 2) return;
        files.splice(active, 1);
        active = Math.min(active, files.length - 1);
        writeSource(files[active].text);
        renderFiles();
        // Removing the active file is also a tab transition. Repaint the retained diagnostics for
        // the buffer that just became active instead of leaving its messages hidden until a click
        // or another compile happens to refresh them.
        renderEditorDiags();
      });
      renderFiles();
      function setStatus(text, isError) {
        statusEl.hidden = false;
        statusEl.className = "cp-pg-status" + (isError ? " cp-doc-error" : "");
        statusEl.textContent = text;
      }
      function clearOut() {
        latestDiags = [];
        clearEditorDiags();
        diags.hidden = true; diags.innerHTML = "";
        result.hidden = true; image.hidden = true; image.removeAttribute("src"); openRow.hidden = true;
        note.hidden = true; note.textContent = "";
        previewList.hidden = true; previewList.innerHTML = "";
      }
      function indexOfFile(name) {
        for (var i = 0; i < files.length; i++) if (files[i].name === name) return i;
        return -1;
      }
      function showFile(i) {
        if (i < 0 || i === active) return;
        files[active].text = readSource();
        active = i;
        writeSource(files[active].text);
        renderFiles();
        renderEditorDiags();
      }
      function renderDiags(list) {
        latestDiags = list || [];
        renderEditorDiags();
        if (!latestDiags.length) return;
        diags.hidden = false;
        latestDiags.forEach(function (d) {
          var li = document.createElement("li");
          li.className = "cp-pg-diag cp-pg-" + (d.severity || "info");
          // With several buffers open, "unresolved reference at line 5" is useless without the
          // file — the server keys diagnostics by basename, so name it and, when that file is one
          // of ours, make the entry jump to its tab.
          var owner = indexOfFile(d.file || "");
          var where = (d.file ? d.file : "") + ((d.line != null) ? (":" + (d.line + 1)) : "");
          var loc = where ? (" (" + where + ")") : "";
          li.textContent = (d.severity || "info") + ": " + d.message + loc;
          if (owner >= 0) {
            li.style.cursor = "pointer";
            li.title = "Show " + d.file + (d.line != null ? ":" + (d.line + 1) : "");
            li.addEventListener("click", function () {
              showFile(owner);
              if (editor && d.line != null) {
                editor.setCursor({ line: d.line, ch: d.ch || 0 });
                editor.scrollIntoView({ line: d.line, ch: d.ch || 0 }, 80);
                editor.focus();
              }
            });
          }
          diags.appendChild(li);
        });
      }
      // A monotonic id fences stale runs: only the newest click updates the DOM, and Run is disabled
      // while a compile is in flight so a burst can't double-submit (each submit mints a token).
      var reqId = 0;
      run.addEventListener("click", function () {
        var myId = ++reqId;
        run.disabled = true;
        clearOut();
        setStatus("Compiling…", false);
        files[active].text = readSource();
        var body = JSON.stringify({
          confType: mode.value,
          catalog: catalog ? catalog.value : "",
          files: files,
          editLease: editLease || "",
          revision: editLease ? ++editRevision : 0
        });
        fetch("/api/1/compiler/run" + suffix, {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: body
        })
          .then(function (r) {
            return r.text().then(function (t) {
              if (!r.ok) throw new Error(t || ("run failed (" + r.status + ")"));
              return JSON.parse(t);
            });
          })
          .then(function (res) {
            if (myId !== reqId) return;
            run.disabled = false;
            if (editLeaseButton) editLeaseButton.disabled = false;
            renderDiags(res.diagnostics);
            var hasError = (res.diagnostics || []).some(function (d) { return d.severity === "error"; });
            if (res.exception) {
              if (res.exception.indexOf("live-edit lease") >= 0) setEditLease(null, res.exception);
              setStatus(res.exception, true); return;
            }
            if (hasError) {
              setStatus(
                res.revision != null
                  ? ("Compilation failed at revision " + res.revision +
                    (res.incremental ? " (incremental)." : " (full fallback)."))
                  : "Compilation failed.",
                true
              );
              return;
            }
            result.hidden = false;
            if (res.image) { image.hidden = false; image.src = res.image; }
            var link = res.documentUrl || res.previewUrl;
            if (link) {
              openRow.hidden = false;
              openLink.href = link + suffix;
              openLink.textContent = res.documentUrl ? "Open document →" : "Open live preview →";
            }
            // A snippet routinely declares more than one @Preview, and only the first drives the
            // still frame. The rest are compiled and live in the same session, so list every one as
            // its own link — `?preview=<id>` opens the session on it — rather than naming the drawn
            // one and leaving the others unreachable. Kept out of the status line, which stays the
            // terminal "Done." the e2e keys on.
            var all = res.previews || [];
            if (res.previewId && all.length > 1) {
              note.hidden = false;
              note.textContent =
                "Rendered " + res.previewId + " — " + all.length + " previews in this snippet.";
            }
            // Only the live-preview lane can open on a chosen preview; a documentUrl addresses a
            // rendered document, which `?preview=` means nothing to. So the per-preview links hang
            // off res.previewUrl specifically rather than the `link` that may be either.
            if (res.previewUrl && all.length > 1) {
              previewList.hidden = false;
              previewList.innerHTML = "";
              all.forEach(function (id) {
                var li = document.createElement("li");
                li.className = "cp-pg-diag cp-pg-info";
                var a = document.createElement("a");
                // Same `/pg/<token>` redemption the main link uses, plus the preview to open on.
                // The token rides in `suffix`, so `?`/`&` depends on whether it is already there.
                a.href = res.previewUrl + suffix + (suffix ? "&" : "?") +
                  "preview=" + encodeURIComponent(id);
                a.rel = "noopener";
                a.textContent = id === res.previewId ? (id + " (shown above)") : id;
                li.appendChild(a);
                previewList.appendChild(li);
              });
            }
            setStatus("Done.", false);
            if (res.revision != null && editLeaseNote) {
              editLeaseNote.hidden = false;
              editLeaseNote.textContent = "Revision " + res.revision +
                (res.incremental ? " compiled incrementally." : " used a full compile.");
            }
          })
          .catch(function (e) {
            if (myId !== reqId) return;
            run.disabled = false;
            if (editLeaseButton) editLeaseButton.disabled = false;
            setStatus(e.message || "run failed", true);
          });
      });
    })();
    """
      .trimIndent()

  fun playgroundDisabledPage(
    token: String,
    isPublic: Boolean,
    unfurl: UnfurlMetadata? = null,
    /**
     * Running server version (`SERVE_VERSION`), shown in the minimal footer. Null omits the build
     * span.
     */
    version: String? = null,
  ): String {
    val suffix = querySuffix(if (isPublic) "" else "token=" + WebEscaping.urlEncodeSegment(token))
    return document(
      title = "Playground unavailable — compose-preview",
      unfurlDescription = "The playground is not enabled on this server.",
      unfurl = unfurl,
      version = version,
      navSuffix = suffix,
      body =
        """
        <h1 class="cp-head">Playground unavailable</h1>
        <p class="cp-sub">
          This server was started without a playground bundle, so it can browse design systems and
          run live previews but cannot compile playground snippets.
        </p>
        <p class="cp-sub">
          Configure <code>--playground</code> to compile against any catalog this server already
          serves, or pin one with <code>--playground-bundle</code> /
          <code>--playground-android-bundle</code>. On public servers also configure
          <code>--playground-sandbox</code>.
        </p>
        <a class="cp-back" href="/$suffix">← All design systems</a>
        """
          .trimIndent(),
    )
  }

  /** The `<option>` value + label for a playground mode in the editor's selector. */
  private fun playgroundModeChoice(mode: PlaygroundMode): Pair<String, String> =
    when (mode) {
      PlaygroundMode.CMP -> "compose-cmp" to "Compose (Desktop)"
      PlaygroundMode.ANDROID -> "compose-android" to "Compose (Android)"
      PlaygroundMode.REMOTE_COMPOSE -> "remote-compose" to "Remote Compose"
    }

  /**
   * One ingested document as the permalink page shows it — the display facts only, so this page
   * never touches [ServeDocStore]'s bytes or clock (and the fixtures can build one by hand).
   */
  data class DocView(
    val id: String,
    /** Display label (the uploaded filename, sanitised by the store). */
    val name: String,
    /** [ServeDocFormat.id] — picks the player + the mount code. */
    val formatId: String,
    val formatLabel: String,
    /** Where the browser player bundle for this format is served. */
    val playerPath: String,
    /** Where the document bytes are served (`/d/<id>/raw`). */
    val rawPath: String,
    val facts: List<ServeDocFact>,
    val sizeText: String,
    /** Human "in 59m" form for the expiry pill. */
    val expiresInText: String,
    /** Absolute UTC instant the link dies, for the title attribute. */
    val expiresAtText: String,
    /** Declared document size, when the format announces one — sizes the canvas before load. */
    val width: Int? = null,
    val height: Int? = null,
  )

  /**
   * `GET /docs` — the **upload surface** for known document formats: drop a Remote Compose `.rc` or
   * a Lottie JSON (or paste a link to one, when the host allows URL fetches) and get back an
   * expiring permalink to hand to someone else.
   *
   * Progressive-ish: the drop zone is a real `<input type="file">` inside a `<form>`, and the
   * script turns the submit into a `fetch` so the resulting link can be shown (and copied) in
   * place. No upload happens without an explicit pick/drop.
   */
  fun docUploadPage(
    token: String,
    isPublic: Boolean,
    ttlSeconds: Long,
    /** Whether `?url=` fetches are permitted here (the SSRF allowlist is non-empty). */
    urlUploadAllowed: Boolean,
    unfurl: UnfurlMetadata? = null,
    /**
     * Running server version (`SERVE_VERSION`), shown in the minimal footer. Null omits the build
     * span.
     */
    version: String? = null,
  ): String {
    val query = queryString(token, sessionId = null, isPublic = isPublic)
    val suffix = querySuffix(query)
    val formats =
      ServeDocFormats.ALL.joinToString(", ") { "${it.label} (<code>${it.extension}</code>)" }
    val urlRow =
      if (!urlUploadAllowed) ""
      else
        """
        <form class="cp-doc-form" id="cp-doc-urlform">
          <input class="cp-doc-url" id="cp-doc-url" type="url" name="url" placeholder="…or paste a link to a document"
            aria-label="Document URL">
          <button class="cp-doc-btn" type="submit">Fetch</button>
        </form>
        """
          .trimIndent()
    return document(
      title = "Share a document — compose-preview",
      unfurlDescription = "Upload a Remote Compose or Lottie document and get an expiring link.",
      unfurl = unfurl,
      version = version,
      navSuffix = suffix,
      body =
        """
        <h1 class="cp-head">Share a document</h1>
        <p class="cp-sub">Upload a generated document and get a link that plays it in the browser and
          expires after ${humanDuration(ttlSeconds)}. Supported: $formats.</p>
        <form id="cp-doc-form" class="cp-drop" tabindex="0">
          <span class="cp-drop-title">Drop a document here, or choose a file</span>
          <span class="cp-drop-hint">Nothing is executed on the server — the document is played back
            by a player running in your own browser.</span>
          <input id="cp-doc-file" type="file" name="file" accept=".rc,.json,application/json">
        </form>
        $urlRow
        <div class="cp-doc-result" id="cp-doc-result" hidden></div>
        <script>${docUploadScript(suffix)}</script>
        """
          .trimIndent(),
    )
  }

  /** Drives the upload page: POST the picked/dropped/linked document, then show its permalink. */
  private fun docUploadScript(querySuffix: String): String =
    """
    (function () {
      var form = document.getElementById("cp-doc-form");
      var file = document.getElementById("cp-doc-file");
      var urlForm = document.getElementById("cp-doc-urlform");
      var out = document.getElementById("cp-doc-result");
      var suffix = ${jsString(querySuffix)};
      function show(html, isError) {
        out.hidden = false;
        out.className = "cp-doc-result" + (isError ? " cp-doc-error" : "");
        out.innerHTML = html;
      }
      function esc(s) { var d = document.createElement("span"); d.textContent = s; return d.innerHTML; }
      function post(url, body, label) {
        show("Uploading…", false);
        fetch(url, { method: "POST", body: body })
          .then(function (r) {
            return r.text().then(function (t) {
              if (!r.ok) throw new Error(t || ("upload failed (" + r.status + ")"));
              return JSON.parse(t);
            });
          })
          .then(function (doc) {
            // The API answers with the bare `/d/<id>` path. On a token-gated host that path 404s
            // without the token, so the browser-facing link carries this page's own query suffix
            // (empty in public mode, `?token=…` otherwise).
            var path = doc.url + suffix;
            var link = location.origin + path;
            show(
              "<p><strong>" + esc(label) + "</strong> — " + esc(doc.format) + ", link expires in " +
                esc(doc.expiresIn) + ".</p>" +
                "<p><a href=\"" + esc(path) + "\">" + esc(link) + "</a></p>" +
                "<button type=\"button\" class=\"cp-doc-btn\" id=\"cp-doc-copy\">Copy link</button>",
              false
            );
            var copy = document.getElementById("cp-doc-copy");
            if (copy) copy.addEventListener("click", function () {
              if (navigator.clipboard) navigator.clipboard.writeText(link);
              copy.textContent = "Copied";
            });
          })
          .catch(function (e) { show(esc(e.message || "upload failed"), true); });
      }
      function upload(f) {
        if (!f) return;
        var qs = suffix ? suffix + "&" : "?";
        post("/docs" + qs + "name=" + encodeURIComponent(f.name), f, f.name);
      }
      // The drop zone doubles as the file picker: clicking anywhere in it opens the chooser.
      form.addEventListener("click", function (e) { if (e.target !== file) file.click(); });
      form.addEventListener("submit", function (e) { e.preventDefault(); });
      file.addEventListener("change", function () { upload(file.files && file.files[0]); });
      ["dragenter", "dragover"].forEach(function (t) {
        form.addEventListener(t, function (e) { e.preventDefault(); form.classList.add("cp-drop-over"); });
      });
      ["dragleave", "drop"].forEach(function (t) {
        form.addEventListener(t, function (e) { e.preventDefault(); form.classList.remove("cp-drop-over"); });
      });
      form.addEventListener("drop", function (e) {
        if (e.dataTransfer && e.dataTransfer.files) upload(e.dataTransfer.files[0]);
      });
      if (urlForm) urlForm.addEventListener("submit", function (e) {
        e.preventDefault();
        var value = document.getElementById("cp-doc-url").value.trim();
        if (!value) return;
        var qs = suffix ? suffix + "&" : "?";
        post("/docs" + qs + "url=" + encodeURIComponent(value), null, value);
      });
    })();
    """
      .trimIndent()

  /**
   * `GET /d/<id>` — the **expiring permalink page** for one ingested document: the document itself,
   * played back client-side by its format's vendored player, plus what the server could read out of
   * it and how long the link has left.
   */
  fun docPage(
    doc: DocView,
    token: String,
    isPublic: Boolean,
    unfurl: UnfurlMetadata? = null,
    /**
     * Running server version (`SERVE_VERSION`), shown in the minimal footer. Null omits the build
     * span.
     */
    version: String? = null,
  ): String {
    val suffix = querySuffix(queryString(token, sessionId = null, isPublic = isPublic))
    val facts =
      doc.facts.joinToString("\n") { fact ->
        """
        <div class="cp-stat">
          <div class="cp-stat-key">${WebEscaping.htmlEscape(fact.key)}</div>
          <div class="cp-stat-val">${WebEscaping.htmlEscape(fact.value)}</div>
        </div>
        """
          .trimIndent()
      }
    val rawUrl = doc.rawPath + suffix
    val isRemoteComposeDoc = doc.formatId == ServeDocFormats.REMOTE_COMPOSE.id
    // Only the Remote Compose lane paints into a canvas the vendored faces matter for; the Lottie
    // player draws SVG and its page is byte-identical to before.
    // `window.cpRcFonts` ships in the component bundle now, so an `.rc` permalink loads the bundle
    // where it used to load `rc-fonts.js`. Emitted BEFORE the inline player script, which reads
    // the global as it starts the lane.
    val rcFontsScript =
      if (isRemoteComposeDoc) scriptTag("serve-components.js") + "\n        " else ""
    return document(
      title = "${doc.name} — compose-preview",
      unfurlDescription = "A shared ${doc.formatLabel} document, played back in your browser.",
      unfurl = unfurl,
      version = version,
      navSuffix = suffix,
      body =
        """
        <h1 class="cp-head">${WebEscaping.htmlEscape(doc.name)}</h1>
        <p class="cp-sub">${WebEscaping.htmlEscape(doc.formatLabel)} · ${WebEscaping.htmlEscape(doc.sizeText)}
          <span class="cp-doc-expiry" title="${WebEscaping.htmlEscape(doc.expiresAtText)}">expires in ${WebEscaping.htmlEscape(doc.expiresInText)}</span></p>
        <div class="cp-doc-stage" id="cp-doc-stage" data-format="${WebEscaping.htmlEscape(doc.formatId)}">
          ${docStageElement(doc)}
        </div>
        <p class="cp-doc-status" id="cp-doc-status">Loading the ${WebEscaping.htmlEscape(doc.formatLabel)} player…</p>
        <div class="cp-doc-facts">
        $facts
        </div>
        <p class="cp-sub" style="margin-top:18px">
          <a href="$rawUrl" download="${WebEscaping.htmlEscape(doc.name)}">Download the document</a> ·
          <a href="/docs$suffix">Share another</a>
        </p>
        $rcFontsScript<script>${docPlayerScript(doc, rawUrl)}</script>
        """
          .trimIndent(),
      // Same lane as the viewer's `js` chip, same reason: a shared `.rc` link must not render in
      // the
      // recipient's own generics.
      rcFonts = isRemoteComposeDoc,
    )
  }

  /** The element the format's player paints into — a canvas for RC, a container div for Lottie. */
  private fun docStageElement(doc: DocView): String =
    when (doc.formatId) {
      ServeDocFormats.LOTTIE.id -> "<div id=\"cp-doc-mount\"></div>"
      else ->
        "<canvas id=\"cp-doc-mount\" width=\"${doc.width ?: 512}\" height=\"${doc.height ?: 512}\"></canvas>"
    }

  /**
   * Load the format's player bundle, fetch the document, and mount it. The per-format mount is the
   * one place formats differ on this page; everything around it (load, error reporting, the stage)
   * is shared, and the bundle URL comes from the registry rather than being written in here.
   */
  private fun docPlayerScript(doc: DocView, rawUrl: String): String {
    val mount =
      when (doc.formatId) {
        ServeDocFormats.LOTTIE.id ->
          """
          fetch(raw).then(function (r) { return r.json(); }).then(function (data) {
            window.lottie.loadAnimation({
              container: mount, renderer: "svg", loop: true, autoplay: true, animationData: data
            });
            done();
          }).catch(fail);
          """
            .trimIndent()
        else ->
          // The vendored generic-family faces must be *loaded*, not merely declared, before the
          // player paints: canvas silently falls back for an unloaded face and never repaints
          // (see `cli/serve-web/src/rcFonts.ts`). `cpRcFonts` is absent only if the component
          // bundle failed to load, in which case the lane still renders — in the fallback face, as
          // it did before.
          """
          var fonts = window.cpRcFonts ? window.cpRcFonts.ready() : Promise.resolve();
          Promise.all([fonts, fetch(raw).then(function (r) { return r.arrayBuffer(); })]).then(function (r) {
            var buf = r[1];
            var player = new window.RC.RcdPlayer(mount);
            return Promise.resolve(player.loadFromArrayBuffer(buf)).then(function () {
              if (player.repaint) player.repaint();
              done();
            });
          }).catch(fail);
          """
            .trimIndent()
      }
    return """
      (function () {
        var raw = ${jsString(rawUrl)};
        var mount = document.getElementById("cp-doc-mount");
        var status = document.getElementById("cp-doc-status");
        function done() { status.textContent = ""; }
        function fail() { status.textContent = "This document could not be played back in your browser."; }
        var s = document.createElement("script");
        s.src = ${jsString(doc.playerPath)};
        s.onerror = function () { status.textContent = "The player failed to load."; };
        s.onload = function () {
      ${mount.prependIndent("      ")}
        };
        document.head.appendChild(s);
      })();
      """
      .trimIndent()
  }

  /** `3600` → `1h`; used for the upload page's TTL sentence and the permalink's expiry pill. */
  fun humanDuration(seconds: Long): String =
    when {
      // Days matter since a share can outlive one: the image lane's default link is a week, and
      // "168h" is a number a reader has to do arithmetic on to understand.
      seconds >= 86_400 ->
        "${seconds / 86_400}d" + ((seconds % 86_400) / 3600).let { if (it > 0) " ${it}h" else "" }
      seconds >= 3600 ->
        "${seconds / 3600}h" + ((seconds % 3600) / 60).let { if (it > 0) " ${it}m" else "" }
      seconds >= 60 -> "${seconds / 60}m"
      else -> "${seconds}s"
    }

  private fun humanBytes(bytes: Long): String =
    when {
      bytes >= 1024L * 1024 * 1024 -> "${bytes / (1024L * 1024 * 1024)} GiB"
      bytes >= 1024L * 1024 -> "${bytes / (1024L * 1024)} MiB"
      bytes >= 1024L -> "${bytes / 1024L} KiB"
      else -> "$bytes B"
    }

  /** A JS string literal for [value] — escaped via the JSON encoder, so quotes/slashes are safe. */
  private fun jsString(value: String): String =
    JsonPrimitive(value)
      .toString()
      // JSON quoting is not enough inside an inline `<script>`: the HTML parser ends the element at
      // the first literal `</script>` regardless of JS string context, so a value carrying one
      // would
      // close the script and let the rest render as markup. `<` is the same character to
      // `JSON.parse` and to a JS string literal, and can never form a tag.
      .replace("<", "\\u003c")
      .replace(">", "\\u003e")

  /**
   * Encoder for data baked into a page as a JS string literal (the playground's catalog list). Not
   * the HTTP wire encoder — this one is only ever read back by [jsString] + `JSON.parse`, so it
   * stays compact and omits defaults exactly like the API's.
   */
  private val JSON_COMPACT = Json { encodeDefaults = true }

  /** One coloured part of a [Meter]. */
  data class MeterSegment(val label: String, val value: Long, val tone: String)

  /** Part-of-whole data rendered underneath a status stat's human-readable value. */
  data class Meter(val total: Long, val segments: List<MeterSegment>)

  /** A labelled figure on the [statusPage], optionally backed by a capacity/progress meter. */
  data class Stat(val key: String, val value: String, val meter: Meter? = null)

  /** One published catalog's row on the [statusPage] — its trust, size, liveness, provenance. */
  data class StatusCatalog(
    val id: String,
    val title: String,
    val listed: Boolean,
    /** [BundleVerifier.summary] verdict string, or null for a non-catalog session. */
    val trust: String?,
    val previews: Int,
    /** Published render failures included in [previews]. */
    val failedRenders: Int = 0,
    /** Preview ids included in [previews] that have no published pixels yet. */
    val deferredPreviews: Int = 0,
    /** The catalog has a live daemon lane (server-side re-render), even if idle right now. */
    val live: Boolean,
    /** A live daemon for this catalog is up **right now**. */
    val running: Boolean,
    /**
     * Why the catalog is snapshot-only, when it is (a [ServeDegradation] detail); null otherwise.
     */
    val degradation: String?,
    /** Delivery branch and build identity for a fetched catalog; null for a plain bundle. */
    val provenance: CatalogProvenance?,
    /** `pending`, `loaded`, `failed`, or `stale` (last good copy + latest refresh error). */
    val loadState: String = "loaded",
    /** Latest catalog load/refresh error. */
    val loadError: String? = null,
    /** Server-side idle theme-cache fill progress for this catalog generation. */
    val themeOptimization: ThemeOptimizationSnapshot? = null,
    /** Bounded rendered-preview cache occupancy for this catalog generation. */
    val renderCache: CatalogRenderCacheSnapshot? = null,
    /**
     * The row's facts are a last-known snapshot of a catalog whose daemon is idle, not a live read
     * (`/status` never resumes one). Rendered as a "last known" qualifier next to the trust badge,
     * so an idle trusted catalog reads as trusted-and-idle instead of as a blank, untrusted-looking
     * row.
     */
    val stale: Boolean = false,
  )

  /** One currently-running render daemon's row on the [statusPage]. */
  data class StatusServer(
    val id: String,
    val label: String,
    /** `desktop` / `android` (derived from the live-seat weight), or `static` for a baked host. */
    val backend: String,
    val activeStreams: Int,
    /** Human "up for" duration, or "—" when unknown. */
    val upForText: String,
  )

  /**
   * One live **agent access grant** on the [statusPage] — see
   * [docs/design/AGENT_ACCESS_GRANTS.md](../../../../../../../../docs/design/AGENT_ACCESS_GRANTS.md).
   *
   * Carries a [fingerprint] and never a token. This table's whole reason to exist is that a human
   * can see what they have let in and end it; showing the credential would make the page itself a
   * place a credential leaks from.
   */
  data class StatusAgentGrant(
    val id: String,
    val fingerprint: String,
    val scopes: String,
    /**
     * Pre-formatted capability list, or empty. Its own column rather than appended to [scopes]: a
     * capability is not a rung, and a cell reading `preview, live, images` would say it was.
     */
    val capabilities: String = "",
    val label: String,
    val approvedBy: String,
    val expiresInText: String,
    /** The seal the revoke form must carry; empty when this viewer may not revoke. */
    val revokeCsrf: String = "",
  )

  /** One access request still waiting for a human, on the [statusPage]. */
  data class StatusAgentRequest(
    val id: String,
    val userCode: String,
    val label: String,
    val client: String,
    val requestedScope: String,
    val expiresInText: String,
  )

  /** One recent daemon startup failure's row on the [statusPage]. */
  data class StatusFailure(val whenText: String, val session: String, val reason: String)

  /** One recent live render failure (distinct from a daemon failing to start). */
  data class StatusRenderFailure(
    val whenText: String,
    val session: String,
    val durationText: String,
    val reason: String,
  )

  /**
   * The rendered model for the [statusPage] — pre-formatted so the page is a pure projection (and
   * the golden fixture is deterministic). [summary] are the headline stat tiles; [config] is the
   * effective-configuration grid; the three lists are the catalog / running-daemon / recent-failure
   * tables.
   */
  data class StatusView(
    val version: String,
    val public: Boolean,
    /** Wall-clock instant used to turn recent catalog generation times into relative labels. */
    val nowMillis: Long,
    /** No catalog-load, daemon-startup, or recent live-render failures. */
    val overallOk: Boolean,
    /** Human explanation for a degraded badge, with an in-page diagnostic target. */
    val healthReason: String? = null,
    val healthHref: String? = null,
    val summary: List<Stat>,
    val config: List<Stat>,
    val catalogs: List<StatusCatalog>,
    val servers: List<StatusServer>,
    val failures: List<StatusFailure>,
    val renderFailures: List<StatusRenderFailure> = emptyList(),
    /**
     * Live agent grants and the requests waiting on a human. Empty on a server with the lane off,
     * and the section is then omitted entirely rather than rendered empty — a table of nothing is
     * noise on a page an operator scans for trouble.
     */
    val agentGrants: List<StatusAgentGrant> = emptyList(),
    val agentGrantRequests: List<StatusAgentRequest> = emptyList(),
  )

  /**
   * The `/status` section for agent access grants: what is live, what is waiting, and a revoke
   * button per row.
   *
   * Omitted entirely when the lane is off and nothing is live or pending — an operator scanning
   * this page for trouble should not have to read two empty tables to learn that a feature they
   * never enabled is still off.
   */
  private fun agentGrantSectionHtml(
    view: StatusView,
    suffix: String,
    esc: (String) -> String,
  ): String {
    // Empty string, not an empty section: see the call site in [statusPage].
    if (view.agentGrants.isEmpty() && view.agentGrantRequests.isEmpty()) return ""
    val liveRows =
      if (view.agentGrants.isEmpty())
        "<tr><td colspan=\"7\" class=\"cp-empty\">No agent currently holds access.</td></tr>"
      else
        view.agentGrants.joinToString("\n") { grant ->
          val revoke =
            if (grant.revokeCsrf.isEmpty()) ""
            else
              "<form method=\"post\" action=\"/agent-access/${esc(grant.id)}/revoke$suffix\">" +
                "<input type=\"hidden\" name=\"csrf\" value=\"${esc(grant.revokeCsrf)}\">" +
                "<button class=\"cp-grant-revoke\" type=\"submit\">Revoke</button></form>"
          "<tr><td><code>${esc(grant.fingerprint)}</code></td>" +
            "<td>${esc(grant.scopes)}</td>" +
            "<td>${if (grant.capabilities.isBlank()) "—" else esc(grant.capabilities)}</td>" +
            "<td>${if (grant.label.isBlank()) "—" else esc(grant.label)}</td>" +
            "<td>${esc(grant.approvedBy)}</td>" +
            "<td>${esc(grant.expiresInText)}</td>" +
            "<td>$revoke</td></tr>"
        }
    val pending =
      if (view.agentGrantRequests.isEmpty()) ""
      else
        """
        <p class="cp-status-sec">Access requests waiting for you</p>
        <div class="cp-status-scroll"><table class="cp-grant-table">
          <thead><tr><th>Code</th><th>Purpose</th><th>From</th><th>Asking for</th><th>Expires in</th><th></th></tr></thead>
          <tbody>
          ${
            view.agentGrantRequests.joinToString("\n") { request ->
              "<tr><td><code>${esc(request.userCode)}</code></td>" +
                "<td>${if (request.label.isBlank()) "—" else esc(request.label)}</td>" +
                "<td>${esc(request.client)}</td>" +
                "<td>${esc(request.requestedScope)}</td>" +
                "<td>${esc(request.expiresInText)}</td>" +
                "<td><a href=\"/agent-access/${esc(request.id)}$suffix\">Review →</a></td></tr>"
            }
          }
          </tbody>
        </table></div>
        """
          .trimIndent()
    return "\n\n" +
      """
      <p class="cp-status-sec" id="agent-grants">Agent access</p>
      <div class="cp-status-scroll"><table class="cp-grant-table">
        <thead><tr><th>Grant</th><th>Scopes</th><th>Also</th><th>Purpose</th><th>Approved by</th><th>Expires in</th><th></th></tr></thead>
        <tbody>
        $liveRows
        </tbody>
      </table></div>
      $pending
      """
        .trimIndent()
  }

  /**
   * A styled **server status** page (`GET /status`): what this `serve` host publishes and its trust
   * / liveness, which render daemons are up right now, the effective configuration, and any recent
   * daemon startup failures. The same snapshot is available as JSON at `/status.json` (or
   * `/status?format=json`) for a monitor or a Home Assistant REST sensor — this is its human face.
   *
   * [token] threads through the generated links exactly as the landing/home renderers do: a
   * token-gated server ([StatusView.public] false) keeps `?token=` on the gated links
   * (`/status.json` and each catalog `/<system>/`) so clicking them doesn't hit the intentional
   * 404; a `--public` server drops it (the routes need none). The always-ungated `/version` /
   * `/healthz` links stay bare either way.
   */
  fun statusPage(
    view: StatusView,
    token: String,
    unfurl: UnfurlMetadata? = null,
    /** Running server version (`SERVE_VERSION`), shown in the minimal footer. */
    version: String? = null,
    /**
     * The catalog whose colours and name this page wears, when it is served on a **top-level site**
     * ([ServeSites]). A site hostname publishes one design system, so its `/status` and its 404 are
     * that system's pages too — carrying the palette and the theme key here is what makes the
     * *whole* hostname one skin rather than a themed catalog with unthemed chrome bolted beside it.
     * Empty (the default) on the main host, where these pages belong to no catalog and keep the
     * built-in chrome.
     */
    siteName: String = "",
    themeCss: String = "",
    themeStorageKey: String = "",
    componentBrowser: Boolean = false,
    githubAuth: GitHubAuthStatus? = null,
  ): String {
    fun esc(s: String) = WebEscaping.htmlEscape(s)
    // Gated-link suffix: token-gated ⇒ carry the token; public ⇒ nothing (routes are open).
    val suffix = if (view.public) "" else "?token=" + WebEscaping.urlEncodeSegment(token)
    fun stat(s: Stat): String {
      val meter =
        s.meter?.let { meter ->
          val total = meter.total.coerceAtLeast(0)
          val segments =
            meter.segments.joinToString("") { segment ->
              val width =
                if (total == 0L) 0.0
                else segment.value.coerceIn(0, total).toDouble() * 100.0 / total
              "<span class=\"cp-meter-segment cp-meter-segment--${esc(segment.tone)}\" " +
                "style=\"width:${"%.3f".format(Locale.ROOT, width)}%\" " +
                "title=\"${esc(segment.label)}: ${segment.value}\"></span>"
            }
          "<div class=\"cp-meter\" role=\"img\" aria-label=\"${esc(s.value)}\">$segments</div>"
        } ?: ""
      return "<div class=\"cp-stat\"><div class=\"cp-stat-key\">${esc(s.key)}</div>" +
        "<div class=\"cp-stat-val\">${esc(s.value)}</div>$meter</div>"
    }

    fun inlineMeter(label: String, value: Long, total: Long, tone: String): String {
      val percent = if (total <= 0L) 0.0 else value.coerceIn(0, total).toDouble() * 100.0 / total
      return "<span class=\"cp-inline-meter\" role=\"img\" aria-label=\"${esc(label)}\">" +
        "<span class=\"cp-inline-meter-fill cp-inline-meter-fill--${esc(tone)}\" " +
        "style=\"width:${"%.3f".format(Locale.ROOT, percent)}%\"></span></span>"
    }

    val healthBadge =
      if (view.overallOk) " <span class=\"cp-badge cp-badge--trusted\">✓ healthy</span>"
      else {
        val reason = view.healthReason?.takeIf { it.isNotBlank() }
        val title = reason?.let { " title=\"${esc(it)}\"" } ?: ""
        val label = "⚠ degraded" + reason?.let { ": ${esc(it)}" }.orEmpty()
        view.healthHref
          ?.takeIf { it.isNotBlank() }
          ?.let { href ->
            " <a class=\"cp-badge cp-badge--unverified\" href=\"${esc(href)}\"$title>$label</a>"
          } ?: " <span class=\"cp-badge cp-badge--unverified\"$title>$label</span>"
      }

    val summaryGrid = view.summary.joinToString("\n") { stat(it) }
    val configGrid =
      view.config.joinToString("\n") {
        "<div class=\"cp-status-config-row\"><dt>${esc(it.key)}</dt><dd>${esc(it.value)}</dd></div>"
      }

    val catalogRows =
      if (view.catalogs.isEmpty())
        "<tr><td colspan=\"4\" class=\"cp-muted\">No catalogs configured on this server.</td></tr>"
      else
        view.catalogs.joinToString("\n") { c ->
          val idSeg = WebEscaping.urlEncodeSegment(c.id)
          val listed = if (c.listed) "" else " <span class=\"cp-muted\">(unlisted)</span>"
          val prov =
            c.provenance?.let { provenance ->
              val repo = esc(provenance.repo)
              val branch = esc(provenance.branch)
              val branchUrl = esc("https://github.com/${provenance.repo}/tree/${provenance.branch}")
              val generated =
                provenance.generatedAt
                  ?.takeIf { it.isNotBlank() }
                  ?.let { iso ->
                    val label = friendlyGeneratedAt(iso, view.nowMillis)
                    " · <span title=\"${esc(iso)}\">${esc(label)}</span>"
                  } ?: ""
              val versions =
                buildList {
                    provenance.toolVersion
                      ?.takeIf { it.isNotBlank() }
                      ?.let { add("compose-ai-tools <code>${esc(it)}</code>") }
                    provenance.designParityVersion
                      ?.takeIf { it.isNotBlank() }
                      ?.let { add("design-parity <code>${esc(it)}</code>") }
                  }
                  .takeIf { it.isNotEmpty() }
                  ?.joinToString(" · ")
                  ?.let { "<div class=\"cp-muted\">$it</div>" } ?: ""
              "<div class=\"cp-muted\"><a href=\"$branchUrl\">$repo@$branch</a>" +
                "$generated</div>$versions"
            } ?: ""
          val stateCell =
            when {
              c.loadState == "failed" ->
                "<span class=\"cp-badge cp-badge--unverified\">failed to load</span>"
              c.loadState == "pending" -> "<span class=\"cp-muted\">loading</span>"
              c.loadState == "stale" ->
                "<span class=\"cp-badge cp-badge--unverified\">stale copy</span>"
              c.running -> "<span class=\"cp-ok\">live · running</span>"
              c.live -> "live · idle"
              else -> "<span class=\"cp-muted\">baked PNG</span>"
            }
          val degrade = c.degradation?.let { "<div class=\"cp-muted\">${esc(it)}</div>" } ?: ""
          val loadError = c.loadError?.let { "<div class=\"cp-muted\">${esc(it)}</div>" } ?: ""
          val themeOptimization =
            c.themeOptimization?.let { optimization ->
              // Dirty renders are the case this row used to report as finished. They are warm and
              // served, so `cached` counts them and `fullyOptimized` is true — but they were
              // written by a DIFFERENT build, and the pass is still working through re-rendering
              // them. A catalog that has adopted its predecessor's whole cache would otherwise
              // read "themes optimized 10440/10440" while every one of those pixels came from a
              // renderer that is no longer running, which is precisely the thing an operator
              // checking this page needs to be told.
              //
              // Worded for what the count means rather than for the case that motivated it. An
              // operator calling `regenerate` marks THIS build's renders dirty too, so "inherited"
              // would be a false claim about where those pixels came from, and "re-rendering"
              // asserts activity the pass may not have — the queue can be paused, or waiting on
              // admission. Saying only that they are queued is true of both, and telling them
              // apart would need the store to carry provenance per entry, which the timestamp
              // boundary deliberately does not.
              val queued =
                if (optimization.dirty > 0) " · ${optimization.dirty} awaiting re-render" else ""
              val failed = if (optimization.failed > 0) " · ${optimization.failed} failed" else ""
              val detail =
                if (optimization.converged) {
                  "themes optimized ${optimization.cached}/${optimization.total}"
                } else if (optimization.fullyOptimized) {
                  // `failed` belongs here too, and this is the branch that needs it most. A
                  // fully-warm catalog whose dirty re-renders keep failing is exactly the state the
                  // dirty failure count was added to make visible: every target is cached, so
                  // nothing else on the row moves, and without this the only signal was the meter's
                  // colour.
                  "themes optimized ${optimization.cached}/${optimization.total}$failed$queued"
                } else {
                  "theme optimization ${optimization.state} · " +
                    "${optimization.cached}/${optimization.total} cached" +
                    failed +
                    queued
                }
              "<div class=\"cp-muted\">${esc(detail)}</div>" +
                inlineMeter(
                  detail,
                  optimization.cached.toLong(),
                  optimization.total.toLong(),
                  // Queued renders are not a failure — they are serving — but they are not
                  // finished either, so the meter must not read the same as a converged catalog.
                  if (optimization.failed > 0) "warning"
                  else if (optimization.dirty > 0) "secondary" else "primary",
                )
            } ?: ""
          val renderCache =
            c.renderCache?.let { cache ->
              val detail =
                "preview cache ${cache.entries} entries · " +
                  "${humanBytes(cache.bytes)} / ${humanBytes(cache.maxBytes)}" +
                  if (cache.evictions > 0) " · ${cache.evictions} evicted" else ""
              "<div class=\"cp-muted\">${esc(detail)}</div>" +
                inlineMeter(detail, cache.bytes, cache.maxBytes, "secondary")
            } ?: ""
          // An idle catalog's facts are last-known, not live — say so next to the badge rather than
          // leaving the cell blank, which would read as untrusted.
          val staleNote = if (c.stale) "<div class=\"cp-muted\">last known</div>" else ""
          val trustCell =
            compactTrustBadge(c.trust).ifBlank { "<span class=\"cp-muted\">—</span>" } + staleNote
          val title =
            if (c.loadState == "failed" || c.loadState == "pending") esc(c.title)
            else "<a href=\"/$idSeg/$suffix\">${esc(c.title)}</a>"
          val previewCell =
            if (c.failedRenders > 0 || c.deferredPreviews > 0)
              "${c.previews} total<div class=\"cp-muted\">" +
                "${(c.previews - c.failedRenders - c.deferredPreviews).coerceAtLeast(0)} rendered · " +
                "${c.failedRenders} failed · ${c.deferredPreviews} deferred</div>"
            else "${c.previews}"
          "<tr>" +
            "<td>$title$listed" +
            "<div class=\"cp-muted\">${esc(c.id)}</div>$prov</td>" +
            "<td>$trustCell</td>" +
            "<td>$previewCell</td>" +
            "<td>$stateCell$themeOptimization$renderCache$loadError$degrade</td>" +
            "</tr>"
        }

    val serverRows =
      if (view.servers.isEmpty())
        "<tr><td colspan=\"4\" class=\"cp-muted\">No render daemons are running right now — they " +
          "start on demand and suspend when idle.</td></tr>"
      else
        view.servers.joinToString("\n") { s ->
          val id = if (s.id == s.label) "" else "<div class=\"cp-muted\">${esc(s.id)}</div>"
          "<tr>" +
            "<td>${esc(s.label)}$id</td>" +
            "<td><code>${esc(s.backend)}</code></td>" +
            "<td>${s.activeStreams}</td>" +
            "<td>${esc(s.upForText)}</td>" +
            "</tr>"
        }

    val failureSection =
      if (view.failures.isEmpty()) "<p class=\"cp-sub\">No recent daemon startup failures.</p>"
      else
        "<div class=\"cp-status-scroll\"><table class=\"cp-table\">" +
          "<thead><tr><th>When</th><th>Session</th><th>Reason</th></tr></thead><tbody>" +
          view.failures.joinToString("\n") { f ->
            "<tr><td>${esc(f.whenText)}</td><td>${esc(f.session)}</td>" +
              "<td>${esc(f.reason)}</td></tr>"
          } +
          "</tbody></table></div>"

    val renderFailureSection =
      if (view.renderFailures.isEmpty()) "<p class=\"cp-sub\">No recent render failures.</p>"
      else
        "<div class=\"cp-status-scroll\"><table class=\"cp-table\">" +
          "<thead><tr><th>When</th><th>Session</th><th>Duration</th><th>Reason</th></tr></thead><tbody>" +
          view.renderFailures.joinToString("\n") { f ->
            "<tr><td>${esc(f.whenText)}</td><td>${esc(f.session)}</td>" +
              "<td>${esc(f.durationText)}</td><td>${esc(f.reason)}</td></tr>"
          } +
          "</tbody></table></div>"

    val ver = " <span class=\"cp-about-ver\">v${esc(view.version)}</span>"
    val mode = if (view.public) "public (open)" else "token-gated"
    val agentGrantSection = agentGrantSectionHtml(view, suffix, ::esc)

    val body =
      """
      <h1 class="cp-head">Server status$healthBadge</h1>
      <p class="cp-sub">compose-preview serve · $mode$ver</p>
      <details class="cp-about cp-disclosure">
        <summary>
          <span class="cp-about-title">Status &amp; monitoring details</span>
          <span class="cp-disclosure-hint">JSON and health-check endpoints</span>
        </summary>
        <div class="cp-disclosure-body">
          <p class="cp-about-body">Catalog load results, render daemons, configuration and recent
            failures. The same data is available as JSON for monitors and Home Assistant.</p>
          <p class="cp-about-links">
            <a href="/status.json$suffix">/status.json</a> ·
            <a href="/version">/version</a> ·
            <a href="/healthz">/healthz</a>
          </p>
        </div>
      </details>

      <div class="cp-status-grid">
      $summaryGrid
      </div>

      <p class="cp-status-sec" id="catalogs">Catalogs</p>
      <div class="cp-status-scroll"><table class="cp-table">
        <thead><tr><th>Catalog</th><th>Trust</th><th>Previews</th><th>State</th></tr></thead>
        <tbody>
        $catalogRows
        </tbody>
      </table></div>

      <p class="cp-status-sec">Running servers</p>
      <div class="cp-status-scroll"><table class="cp-table">
        <thead><tr><th>Session</th><th>Backend</th><th>Streams</th><th>Up for</th></tr></thead>
        <tbody>
        $serverRows
        </tbody>
      </table></div>

      <p class="cp-status-sec">Configuration</p>
      <dl class="cp-status-config">
      $configGrid
      </dl>

      <p class="cp-status-sec" id="recent-daemon-failures">Recent daemon startup failures</p>
      $failureSection

      <p class="cp-status-sec" id="recent-render-failures">Recent live render failures</p>
      $renderFailureSection
      """
        .trimIndent() +
        // Appended rather than interpolated into the template: a lane that is off must leave this
        // page byte-for-byte what it was, and an empty interpolation inside the block still leaves
        // its own line behind — which the committed HTML fixture would then report as a diff on
        // every server that never enabled the feature.
        agentGrantSection

    return document(
      // A site's status is that app's status, so its tab says so rather than naming the box.
      title =
        if (siteName.isBlank()) "Server status — compose-preview"
        else "Status — ${WebEscaping.htmlEscape(siteName)}",
      body = body,
      unfurlDescription =
        "Live catalog, render-daemon, and deployment status for this compose-preview server.",
      unfurl = unfurl,
      version = version,
      navSuffix = suffix,
      siteName = siteName,
      themeCss = themeCss,
      themeStorageKey = themeStorageKey,
      componentBrowser = componentBrowser,
      interfaceModeControl = true,
      headerAction = if (componentBrowser) "" else githubAuthControl(githubAuth),
    )
  }

  /**
   * What [bugReportPage] draws: the assembled report, plus the pieces the page needs to show the
   * reporter what they are about to file.
   *
   * [body] is [ServeBugReport]'s output for the settings the page was served at, so the form works
   * with JS off — the title is the reporter's own, typed into the page's Summary field.
   * [bodyTemplate] is the same body with [ServeBugReport.CLIENT_PLACEHOLDER] where the browser
   * block goes, which the page script fills from `navigator` / `window`. [renderUrl], when present,
   * is the token-stripped `/render` PNG of whatever the reporter was looking at — shown on the page
   * as a thumbnail so "this is what I saw" is literal rather than described.
   */
  data class BugReport(
    val action: String,
    val body: String,
    val bodyTemplate: String,
    val repo: String,
    val renderUrl: String? = null,
    /** Present only when the visitor has a GitHub session on this server. */
    val login: String? = null,
    /**
     * The catalog the reported page belonged to, when it belonged to one. See [BugReportCatalog].
     */
    val catalog: BugReportCatalog? = null,
  )

  /**
   * The catalog the reporter was looking at, so [bugReportPage] can send a *catalog* bug to the
   * right tracker by name instead of telling the reporter to go and find the link themselves.
   *
   * The page's second paragraph has always said "wrong pixels are the catalog's bug, not this
   * server's — go back to the preview and use its report link". That is good advice from the front
   * door of a multi-catalog host, where the server genuinely cannot know which catalog is meant. It
   * is poor advice on a **top-level site** ([ServeSites]), which publishes exactly one catalog and
   * is the shape most visitors meet: `wear.preview.coo.ee` is the Wear catalog and nothing else,
   * the page they came from may be a design page or an index with no preview to go back to, and the
   * repository that owns the pixels is a lookup the server can do for them.
   */
  data class BugReportCatalog(
    /** Served system id, e.g. `wear-m3`. */
    val system: String,
    /** The catalog's own display title, e.g. "Wear Material 3" — [system] when it declares none. */
    val title: String,
    /**
     * `owner/repo` the catalog's pixels belong to, from its source else its delivery provenance.
     */
    val repo: String,
    /** The new-issue form for [repo]. */
    val issuesUrl: String,
    /** True when the reporter was on this catalog's own hostname, so the whole site is it. */
    val site: Boolean,
  )

  /**
   * `GET /report-bug` — the preview server's own bug-report page.
   *
   * **Why a page rather than a footer link straight to GitHub.** The per-preview report can be one
   * click, because its whole body is facts the visitor is already looking at: a preview id, the
   * overrides in the URL bar, a render on screen. A server report is not — it carries the JVM the
   * daemon runs on, which catalogs failed to load, and what the render lanes have been doing, none
   * of which is on any page. Shipping that to GitHub from a footer button would post a body the
   * reporter has never read, on a public tracker, in their name. So the page's main job is to
   * **show the report before it is filed**: the same markdown, rendered as the sections it will
   * become, with a plain-text copy underneath. Pressing the button then files exactly what is on
   * screen.
   *
   * The screenshot is deliberately two-sided. When the reporter came from a viewer the page embeds
   * that preview's `/render` PNG, and the body carries it too (embedded when this host is publicly
   * reachable, linked otherwise — the reachability rules live in [ServeIssueReport.isEmbeddable]).
   * That covers "the render is wrong". It does **not** cover "the page is wrong", which is most
   * server bugs, so the page also asks for a pasted screenshot of the whole window — the one thing
   * the server cannot produce for itself and the browser gives away for free.
   */
  fun bugReportPage(
    report: BugReport,
    /** The diagnostics as rows, shown verbatim; each entry is a section title to its rows. */
    sections: List<BugReportSection>,
    unfurl: UnfurlMetadata? = null,
    version: String? = null,
    siteName: String = "",
    themeCss: String = "",
    themeStorageKey: String = "",
    navSuffix: String = "",
  ): String {
    fun esc(s: String) = WebEscaping.htmlEscape(s)
    val who =
      report.login?.takeIf { it.isNotBlank() }?.let { " as @${esc(it)}" }
        ?: " — GitHub will ask you to sign in"
    val sectionHtml =
      sections
        .filter { it.rows.isNotEmpty() }
        .joinToString("\n") { section ->
          val rows =
            section.rows.joinToString("\n") { (key, value) ->
              // A blank key marks a LIST row (a failed catalog, a failure line) rather than a
              // key/value one. It spans both columns instead of drawing an empty header cell —
              // which reserved the key column's width and its rule, so a section that is really a
              // list read as a table whose left half had gone missing.
              if (key.isBlank()) "<tr><td colspan=\"2\">${esc(value)}</td></tr>"
              else "<tr><th scope=\"row\">${esc(key)}</th><td>${esc(value)}</td></tr>"
            }
          "<p class=\"cp-status-sec\">${esc(section.title)}</p>\n" +
            "<div class=\"cp-status-scroll\"><table class=\"cp-table cp-report-facts\">" +
            "<tbody>\n$rows\n</tbody></table></div>"
        }
    // Whatever the reporter captured on the page they came from, carried here in `sessionStorage`
    // and rendered by `report-capture.js` — see [captureControlsHtml]. Server-rendered as an
    // empty mount rather than left entirely to the script, so the section has a fixed place in the
    // page and the "nothing came across" wording is written here with the rest of the page's prose.
    val captures =
      """
      <div class="cp-shots" data-cp-capture-src="${esc(assetHref("report-capture.js"))}">
        <p class="cp-sub cp-shots-empty">No captures came across from the page you reported. Take
          one there with the &ldquo;Report a problem&rdquo; button, or paste an ordinary screenshot
          straight into the issue.</p>
        <ul class="cp-shot-list"></ul>
        <p class="cp-shot-note" role="status"></p>
      </div>
      """
        .trimIndent()
    val shot =
      report.renderUrl
        ?.takeIf { it.isNotBlank() }
        ?.let {
          "\n      <p class=\"cp-status-sec\">The base render of that preview</p>\n" +
            "      <p class=\"cp-sub\">Included in the report. It is the plain render at your " +
            "settings — not the spec triptych, the wipe, or any other view the browser " +
            "composes — and it is live, so it follows the catalog. Capture the page as well " +
            "if the exact pixels matter.</p>\n" +
            "      <img class=\"cp-report-shot\" src=\"${esc(it)}\" alt=\"the render this " +
            "report is about\" loading=\"lazy\">"
        } ?: ""
    // Where a *catalog* bug belongs. Named and linked when the server knows the catalog — always,
    // on a top-level site — and left as the generic "go back to the preview" advice when it does
    // not. See [BugReportCatalog] for why the generic wording is wrong on a one-catalog hostname.
    val catalog = report.catalog
    val elsewhere =
      (when {
          catalog == null ->
            """
          <p class="cp-sub cp-report-elsewhere">Wrong <em>pixels</em> rather than a wrong page? A button
            in the wrong colour, a state that is missing, a spec that does not match — that is the
            <strong>catalog&rsquo;s</strong> bug, not this server&rsquo;s. Go back to the preview and use
            its &ldquo;report a catalog issue&rdquo; link, which files against the repository whose
            Kotlin declares that preview. A catalog bug filed here reaches people who cannot fix it.</p>
          """
          catalog.site ->
            """
          <p class="cp-sub cp-report-elsewhere">This site is the
            <strong>${esc(catalog.title)}</strong> catalog and nothing else, so most of what you can
            see here is drawn from it rather than by this server. Wrong <em>pixels</em> — a button
            in the wrong colour, a state that is missing, a spec that does not match — are that
            catalog&rsquo;s bug, and belong in
            <a href="${esc(catalog.issuesUrl)}" rel="noopener">${esc(catalog.repo)}</a>. Filed here
            they reach people who cannot fix them. A preview&rsquo;s own &ldquo;report a catalog
            issue&rdquo; link is better still where you have one: it carries the preview, your
            overrides and the render with it.</p>
          """
          else ->
            """
          <p class="cp-sub cp-report-elsewhere">The page you came from belongs to the
            <strong>${esc(catalog.title)}</strong> catalog. Wrong <em>pixels</em> — a button in the
            wrong colour, a state that is missing, a spec that does not match — are that
            catalog&rsquo;s bug, not this server&rsquo;s, and belong in
            <a href="${esc(catalog.issuesUrl)}" rel="noopener">${esc(catalog.repo)}</a>; a
            preview&rsquo;s own &ldquo;report a catalog issue&rdquo; link files there too and
            carries the preview, your overrides and the render with it. A catalog bug filed here
            reaches people who cannot fix it.</p>
          """
        })
        // Re-indented to the template's own level: the outer `trimIndent()` runs on the string
        // AFTER substitution, so a block pasted in at column 0 would make the common indent 0 and
        // leave every other line of the page's markup indented.
        .trimIndent()
        .replace("\n", "\n      ")
    val body =
      """
      <h1 class="cp-head">Report a bug in the preview server</h1>
      <p class="cp-sub">This files against <a href="https://github.com/${esc(report.repo)}"
        >${esc(report.repo)}</a>, the repository that ships <code>compose-preview serve</code>$who
        — the page you were on, its controls, and the render lanes behind them.</p>
      $elsewhere

      <form class="cp-report-bug-form" method="get" target="_blank" rel="noopener"
        action="${esc(report.action)}">
        <label class="cp-bug-summary">Summary
          <input class="cp-bug-summary-input" type="text" name="title" required
            autocomplete="off" placeholder="Briefly describe the problem">
        </label>
        <input type="hidden" name="labels" value="${esc(ServeBugReport.LABELS)}">
        <input type="hidden" name="body" id="cp-bug-body" value="${esc(report.body)}"
          data-report-template="${esc(report.bodyTemplate)}">
        <button type="submit" class="cp-doc-btn cp-bug-submit">$GITHUB_ICON
          Open a prefilled issue</button>
        <span class="cp-muted">Opens GitHub&rsquo;s new-issue form. Nothing is sent from this
          server &mdash; you write the description and press Submit there.</span>
      </form>

      <p class="cp-status-sec">Add a screenshot</p>
      <p class="cp-sub">A picture cannot ride along with the report: GitHub&rsquo;s new-issue form
        prefills from a URL, and a URL carries a body, not an attachment. An image becomes one by
        being <strong>pasted</strong> into the issue, which is what uploads it to GitHub&rsquo;s own
        storage — and is why it keeps showing what you saw even after this server changes. So
        pressing the button above puts your newest capture back on the clipboard: the report opens
        with a Screenshot section waiting, and you paste into it.</p>
      $captures
      $shot

      <p class="cp-status-sec">What gets sent</p>
      <p class="cp-sub">Everything below travels with the report, and nothing else. Session tokens
        are stripped from every link.</p>
      $sectionHtml

      <details class="cp-about cp-disclosure">
        <summary>
          <span class="cp-about-title">The report, as markdown</span>
          <span class="cp-disclosure-hint">exactly what lands in the issue body</span>
        </summary>
        <div class="cp-disclosure-body">
          <pre class="cp-report-preview" id="cp-bug-preview">${esc(report.body)}</pre>
        </div>
      </details>
      """
        .trimIndent()
    return document(
      title = "Report a bug — compose-preview",
      body = body,
      unfurlDescription = "Report a bug in this compose-preview server.",
      unfurl = unfurl,
      version = version,
      navSuffix = navSuffix,
      siteName = siteName,
      themeCss = themeCss,
      themeStorageKey = themeStorageKey,
      // The footer entry leads here; offering it on this page would be a button back to itself.
      bugReport = false,
    )
  }

  /** One titled group of `key → value` diagnostics on [bugReportPage]. */
  data class BugReportSection(val title: String, val rows: List<Pair<String, String>>)

  /** Generation times use one relative format; the exact ISO instant remains in the title. */
  private fun friendlyGeneratedAt(iso: String, nowMillis: Long): String {
    val generated = runCatching { Instant.parse(iso) }.getOrNull() ?: return prettyDate(iso)
    val ageMillis = nowMillis - generated.toEpochMilli()
    if (ageMillis < 0) {
      val futureSeconds = (-ageMillis + 999) / 1000
      return when {
        futureSeconds < 60 -> "in less than a minute"
        futureSeconds < 3_600 -> relativeTime(futureSeconds / 60, "minute", future = true)
        futureSeconds < 86_400 -> relativeTime(futureSeconds / 3_600, "hour", future = true)
        futureSeconds < 2_592_000 -> relativeTime(futureSeconds / 86_400, "day", future = true)
        futureSeconds < 31_536_000 ->
          relativeTime(futureSeconds / 2_592_000, "month", future = true)
        else -> relativeTime(futureSeconds / 31_536_000, "year", future = true)
      }
    }
    val ageSeconds = ageMillis / 1000
    return when {
      ageSeconds < 60 -> "just now"
      ageSeconds < 3_600 -> relativeTime(ageSeconds / 60, "minute")
      ageSeconds < 86_400 -> relativeTime(ageSeconds / 3_600, "hour")
      ageSeconds < 2_592_000 -> relativeTime(ageSeconds / 86_400, "day")
      ageSeconds < 31_536_000 -> relativeTime(ageSeconds / 2_592_000, "month")
      else -> relativeTime(ageSeconds / 31_536_000, "year")
    }
  }

  private fun relativeTime(amount: Long, unit: String, future: Boolean = false): String {
    val duration = "$amount $unit${if (amount == 1L) "" else "s"}"
    return if (future) "in $duration" else "$duration ago"
  }

  /**
   * Per-system **display policy** — the single source of truth for what background surface each
   * published design system should use on the public server, so "does this system want a dark
   * stage?" is answered in ONE place instead of an ad-hoc `startsWith("wear")` check scattered
   * through the page renderers. Keyed by the served system id (the `/<system>` path mount, e.g.
   * `wear-m3`, `confetti-wear`).
   *
   * A system is **dark-first** when it targets a dark-first platform — Wear OS is
   * black-watch-face-first, so a light-on-transparent Wear sticker on the default white stage reads
   * with unreadable content.
   *
   * The authoritative signal is what the **catalog itself declares** (`catalog.json`'s
   * `display.surface`, from the spec) — pass it to [resolveDarkFirst]. Only when a catalog declares
   * nothing does this fall back to [isDarkFirst], a generic Wear/watch id heuristic (token match,
   * so `confetti-wear` hits as well as `wear-m3`) — a best-effort default, not a hardcoded per-app
   * list.
   */
  object SystemDisplay {
    /**
     * A Wear / watch system id, matched on a `-`/`_` token so `confetti-wear` and `wear-m3` hit.
     */
    private val wearIdPattern = Regex("(^|[-_])(wear|watch)([-_]|$)")

    /**
     * Whether [system] targets Wear OS, from the served id alone. Drives the platform-shaped bits
     * of the viewer that are true of a watch regardless of surface colour — the watch device
     * profiles in a screen's size picker, and the absence of an orientation control. Generic (any
     * Wear/watch system), never per-app.
     */
    fun isWearOs(system: String): Boolean {
      val s = system.trim('/').lowercase()
      if (s.isBlank()) return false
      return wearIdPattern.containsMatchIn(s)
    }

    /**
     * Fallback dark-first guess from the system id alone, for a catalog that declares no
     * `display.surface`: a Wear system is black-watch-face-first, so [isWearOs] *is* the guess.
     * Kept as its own name because a future non-Wear dark-first platform belongs here, not in
     * [isWearOs].
     */
    fun isDarkFirst(system: String): Boolean = isWearOs(system)

    /**
     * Wear/watch renders have no day mode; discard a generic UI's accidental light override.
     *
     * Applied to the RAW parameter map — before [ServeOverrides.parse] — so every lane (render,
     * storybook iframe, and both socket lanes) drops the override at one point, and a dropped
     * `uiMode` never reaches the daemon as a distinct cache key. There is deliberately no
     * post-parse twin of this: two normalizers at two layers is how one of them ends up dead.
     */
    fun normalizeOverrideParams(
      system: String,
      overrides: Map<String, String>,
    ): Map<String, String> = if (isDarkFirst(system)) overrides - "uiMode" else overrides

    /**
     * Resolve whether [system] draws on a DARK stage, preferring the catalog's declared
     * [surface][declaredSurface] (`"light"`/`"dark"`) and falling back to the [isDarkFirst] id
     * heuristic only when the catalog declared nothing.
     */
    fun resolveDarkFirst(system: String, declaredSurface: String?): Boolean =
      when (declaredSurface?.trim()?.lowercase()) {
        "dark" -> true
        "light" -> false
        else -> isDarkFirst(system)
      }
  }

  private fun isScreenPreview(preview: ServePreview): Boolean {
    preview.section?.lowercase()?.let {
      return it == "screens" || it == "screen"
    }
    return listOf(preview.id, preview.label).any { value ->
      val lower = value.lowercase()
      "screen" in lower || "conference" in lower
    }
  }

  /**
   * Pick a **meaningful** representative preview from a catalog's previews for the home index — the
   * most recognisable, default-state render rather than an arbitrary (often alphabetically first)
   * edge case. The primary rule is **prefer a real screen** (the most representative view of an
   * *app*): when the catalog carries any `Screens`-section preview, a screen always wins over a
   * single component — so an app like Confetti fronts a conference screen while a component library
   * (compose-m3, no screens) falls straight through to its component hero. Within that, scores
   * each: a non-default state (disabled/pressed/…) is pushed down; light beats dark; a canonical
   * button/filled hero is preferred. Ties break on the id so the choice is deterministic (stable
   * goldens). Null when there are no previews.
   */
  fun representativePreviewId(previews: List<ServePreview>): String? {
    val usable = previews.filter { it.renderFailure == null }
    if (usable.isEmpty()) return null
    val demote =
      listOf(
        "disabled",
        "error",
        "pressed",
        "focused",
        "hover",
        "dragged",
        "unchecked",
        "indeterminate",
        "empty",
        "loading",
      )
    // A preview is a "screen" when its catalog section says so (the reliable signal), else when its
    // id/label reads like one — so a screen wins the hero even before section metadata exists.
    val anyScreen = usable.any { isScreenPreview(it) }
    // A screen id that reads like the app's primary/landing view (its conference/home/schedule/…),
    // preferred among screens so an app fronts its main screen rather than an alphabetically-first
    // secondary one (e.g. Confetti leads with the conference screen, not bookmarks).
    val primaryScreen =
      listOf("conference", "home", "main", "schedule", "sessions", "overview", "start", "today")
    fun score(p: ServePreview): Int {
      val lower = p.id.lowercase()
      var s = 0
      // Prefer a real screen when the catalog has any; a screenless component library is unaffected
      // (every preview gets the same penalty, so the component heuristic below still decides).
      if (anyScreen && !isScreenPreview(p)) s += 100
      if (isScreenPreview(p) && primaryScreen.any { it in lower }) s -= 1
      // A non-default component state (unchecked / pressed / …) is never the hero — trust the
      // catalog's `state` metadata, falling back to the id-substring demote list below.
      if (p.state != null && p.state != "default") s += 8
      if ("dark" in lower) s += 4
      demote.forEach { if (it in lower) s += 8 }
      if ("button" in lower) s -= 3
      if ("filled" in lower) s -= 2
      return s
    }
    return usable.sortedWith(compareBy({ score(it) }, { it.id })).first().id
  }

  /**
   * How long a press has to be held on a catalog card before it means "start a live session here"
   * rather than "open this preview". Long enough not to fire on a tap or the start of a scroll,
   * short enough to feel like a press rather than a wait — the same ~half-second Android's own
   * long-press uses.
   */
  const val LONG_PRESS_HOLD_MS: Int = 500

  /**
   * The grid's **long-press live lane**: hold a card and its preview starts streaming from the
   * session's render daemon in place, inside the card, instead of navigating to the viewer.
   *
   * This emits the browser side's configuration and the `<cp-catalog-live>` element that reads it.
   * The per-card preview ids ride in a **server-emitted object literal**, in the grid's document
   * order, rather than being read back off `data-` attributes — the same rule the themed-render
   * URLs follow, so no id this page turns into a socket URL originates as DOM text. Each entry
   * carries the card's light and dark ids (identical for a single-variant card, empty for one the
   * session can't stream), so a card swapped to its dark render goes live on what is actually on
   * screen.
   *
   * The tag comes AFTER the config, so the element finds it the moment it upgrades; the components
   * bundle is already loaded by then (it precedes the filter script above). The element defers to
   * `DOMContentLoaded` if it upgrades early anyway, so the order is a nicety rather than a
   * dependency.
   *
   * Empty — no config, no tag — when no card can stream, which is every static bundle and every
   * baked-only catalog. Those pages are byte-for-byte what they always were.
   */
  private fun catalogLiveScript(
    basePath: String,
    query: String,
    cards: List<Pair<String, String>>,
    signInHref: String?,
  ): String {
    if (cards.none { (light, dark) -> light.isNotEmpty() || dark.isNotEmpty() }) return ""
    val entries =
      cards.joinToString(",") { (light, dark) ->
        "{l:${WebEscaping.jsString(light)},d:${WebEscaping.jsString(dark)}}"
      }
    val config =
      "window.cpCatalogLive = {base:${WebEscaping.jsString(basePath)}," +
        "query:${WebEscaping.jsString(query)}," +
        "signInHref:${WebEscaping.jsString(signInHref.orEmpty())}," +
        "holdMs:$LONG_PRESS_HOLD_MS,cards:[$entries]};"
    return "\n<script>$config</script>\n<cp-catalog-live></cp-catalog-live>"
  }

  /** Landing page: the module's preview list, each card linking to its viewer. */
  fun landingPage(
    moduleLabel: String,
    previews: List<ServePreview>,
    token: String,
    sessionId: String? = null,
    trust: String? = null,
    isPublic: Boolean = false,
    /**
     * Whether this server publishes a front-door home index (`/`) to link back to — true when it
     * serves ANY catalog, listed (`--catalogs`) OR unlisted app (`--catalogs-unlisted`). Gates the
     * "← All design systems" back button, so an app-only server's landings still link home. False
     * (default) for a plain single-module `serve` with no index, which shows no back button.
     */
    hasHomeIndex: Boolean = false,
    /**
     * URL prefix for this session's own links (`/<system>` when served under a path, empty for the
     * root-mounted default/legacy session). Card/render/zip links are prefixed with it and drop the
     * `&session=` param (the path carries the session). Empty ⇒ links are exactly as before.
     */
    basePath: String = "",
    /**
     * Whether this session can compare a render against its SVG export — gates the "compare SVG"
     * action, which deep-links the comparison page's `svg` format.
     */
    hasSvgComparison: Boolean = false,
    /**
     * Whether this session can compare a render against Remote Compose output — gates the "compare
     * RC players" action, which deep-links the comparison page's `rc` format.
     */
    hasRcComparison: Boolean = false,
    /**
     * Whether this session can compare a render against its design references — gates the "compare
     * to Figma" action, which deep-links the comparison page's `reference` format. Named after the
     * design tool ([designToolLabel]) for the same reason the other two are named after their
     * formats: the chip says what it puts side by side.
     */
    hasReferenceComparison: Boolean = false,
    /**
     * Whether this catalog has a design-parity view to link to — it maps at least one preview to a
     * design reference, or it publishes a `parity/activity.json` feed. False (the default) omits
     * the link entirely rather than offering a page of zeroes, so a plain module / an unmapped
     * catalog's landing is unchanged.
     */
    hasParityView: Boolean = false,
    /**
     * How many motion captures this catalog publishes, across every preview — the count behind the
     * "motion" action, and the gate on whether it appears at all. Zero (the default) omits it, so a
     * catalog that records nothing is unchanged and no visitor is offered an empty page.
     *
     * A count rather than a boolean because one recording and thirty are different offers, the same
     * reasoning the pages chip carries its count for.
     */
    motionCaptureCount: Int = 0,
    /**
     * The design pages this catalog publishes ([ServeDesignPages]), in publication order. Listed by
     * name in the navigation tree ([pagesBranchHtml]); a catalog with no tree to put them in falls
     * back to a header action chip. Empty (the default) offers neither, so a catalog that publishes
     * no pages is unchanged.
     */
    designPages: List<PageLink> = emptyList(),
    /**
     * The design tool this catalog is specified by ("Figma", …), from its references' provider or
     * its parity feed — names the reference comparison after the thing it compares against
     * ("compare to Figma") rather than after the internal format name. Null (no identifiable tool)
     * keeps the neutral "compare to design references" label. See [designToolLabel].
     */
    designToolLabel: String? = null,
    /**
     * Per-preview thumbnail content-crop lookup — frames a card's render to its component box (a
     * Wear sticker on a 454² watch canvas shows just the component). Returns null for a card that
     * should show the raw render (no figma-svg, or a render already tight to the component). The
     * default `{ null }` keeps every card uncropped — used by the plain-module landing and by
     * tests.
     */
    thumbCrop: (String) -> ContentCrop? = { null },
    /**
     * Per-preview **prebaked thumbnail** lookup ([ServeHeroImages.gridThumbFor]), returning the
     * baked bytes' content hash. When a card has one, every URL that points at that card's pixels
     * carries `?thumb=<hash>` and the render lane answers it from memory with a downscaled image,
     * instead of shipping the full-resolution render (a catalog page is ~2 MB of them). Returns
     * null for a card whose pixels aren't baked locally yet — it keeps the plain render URL and
     * picks a thumbnail up on a later page build.
     *
     * The default `{ null }` leaves every card on the full render — used by the plain-module
     * landing and by the fixture goldens, which must not churn with the bake.
     */
    thumbHash: (String) -> String? = { null },
    /**
     * `POST` URL that keeps this catalog's session (and its daemon) alive while a visitor has the
     * page open — see [presenceScript]. Empty (the default) omits the heartbeat.
     */
    presenceUrl: String = "",
    /**
     * Running server version (the CLI's `SERVE_VERSION`), surfaced in the minimal footer beside the
     * source/`/version` links. Null omits it; the fixture golden passes a fixed string so a release
     * never churns the committed HTML.
     */
    version: String? = null,
    /**
     * Provenance of a served design-system catalog (delivery branch, generation date, the
     * compose-ai-tools + design-parity versions it was rendered with). When present it renders a
     * provenance strip under the catalog header with a link to regenerate it. Null for a plain
     * uploaded bundle / non-catalog module (no such metadata).
     */
    provenance: CatalogProvenance? = null,
    /** POST URL that checks this catalog's delivery branch immediately. Null omits Refresh. */
    refreshUrl: String? = null,
    /**
     * `/playground?catalog=<system>` — opens the playground with this design system preselected, so
     * a snippet compiles against the catalog you were just browsing. Null on a host with no
     * playground lane; the summary line then reads exactly as it always did.
     */
    playgroundHref: String? = null,
    /**
     * The catalog's declared stage surface (`catalog.json`'s `display.surface`: `"light"`/`"dark"`)
     * — decides whether unthemed cards sit on the dark stage. Null ⇒ fall back to the system-name
     * dark-first heuristic ([isDarkFirstSystem]). So a system declares its own surface rather than
     * relying on its id.
     */
    declaredSurface: String? = null,
    /**
     * The served catalog's own palette as an inline `:root` override for the chrome's custom
     * properties, built by [ServeThemeCss] from the branch's `tokens.dtcg.json`. Empty ⇒ the page
     * keeps the built-in chrome (a plain module, or a catalog that publishes no tokens).
     */
    themeCss: String = "",
    /**
     * Why this catalog is snapshot-only, when it is (no live bundle, unverified, …). When
     * non-empty, a banner under the header explains it. Empty ⇒ no banner (a fully-live session, or
     * a plain module). See [ServeDegradation] / [degradeBanner].
     */
    degradations: List<ServeDegradation> = emptyList(),
    /**
     * The app's declared `@ThemeCatalog` / `@WearThemeCatalog` themes ([ServeHost.declaredThemes]).
     * They join the baked light/dark pair on the header's single Theme control (issue #2881), so
     * the grid can be redrawn under any theme the catalog configures — not just Light/Dark. Offered
     * only for cards the session can actually re-render ([canRenderThemeFor]) **by re-running their
     * composable** ([irReplayFor]); empty (default) keeps the plain light/dark axis.
     */
    declaredThemes: List<ServeTheme> = emptyList(),
    /**
     * Whether a given preview can be re-rendered under a `themeProvider` override — i.e. it has a
     * daemon twin ([ServeHost.canRenderOverridesFor]). A card that can't keeps its baked pixels
     * (which would ignore the theme) and the declared-theme chips only appear when at least one
     * card can. Defaults to `{ false }`: a plain static bundle offers baked light/dark only.
     */
    canRenderThemeFor: (String) -> Boolean = { false },
    /**
     * Whether a server render of a given preview **replays a captured document** rather than
     * re-running the composable — the grid's counterpart of the viewer's `irReplay` flag, read from
     * the same host question (`ServeHttpServer.isReplayedPreview`) that decides whether a
     * `themeProvider` render is refused.
     *
     * A declared theme installs a `PreviewWrapperProvider` **around a composition**, so a replayed
     * preview can never honour one: the server answers its render with a terminal 409
     * ([CatalogLiveRouting.irReplayDroppedOverrideNames]). Such a card is therefore not
     * theme-overridable however live its daemon twin is — without this the grid offered chips that
     * turned every card into "This preview can't render live" (a whole IR-backed catalog, e.g.
     * `remote-m3`, failing at once). The viewer already greys the same choice; this is the landing
     * page catching up. Defaults to `{ false }`: an ordinary class-backed session recomposes.
     */
    irReplayFor: (String) -> Boolean = { false },
    /**
     * Maximum themed-thumbnail burst supported by this host. Values above one enable the
     * server-issued page lease endpoint; actual concurrency is granted dynamically and clamped by
     * server render capacity. Monolithic daemons remain serial.
     */
    themeRenderBurstCapacity: Int = 1,
    /**
     * Per-preview engagement counts for this running server. The map is additive UI/API metadata:
     * missing or zero entries render no badge.
     */
    engagement: Map<String, PreviewEngagement> = emptyMap(),
    /**
     * Whether a given preview can be streamed live from the grid — the session offers the daemon
     * stream ([ServeHost.hasLiveStream]) **and** this preview has a daemon twin behind it
     * ([ServeHost.canRenderOverridesFor]). A card that passes gains the long-press live lane (see
     * [catalogLiveScript]); one that doesn't stays an ordinary link, because its socket would only
     * ever replay baked pixels. Defaults to `{ false }`: a static bundle offers no in-grid lane.
     */
    canStreamLiveFor: (String) -> Boolean = { false },
    /**
     * GitHub sign-in URL when the box gates its live lanes behind auth and this visitor isn't
     * signed in. Non-null keeps the long-press affordance (the lane exists, it just isn't theirs
     * yet) and answers the press with the reason instead of opening a socket that would close
     * 1008. Null (the default) ⇒ no auth in the way.
     */
    liveSignInHref: String? = null,
    /** Aggregate visits to this app/design-system landing page. */
    systemViews: Long = 0,
    /** Absolute page + representative preview URLs for Open Graph/Twitter link previews. */
    unfurl: UnfurlMetadata? = null,
    /** Human catalog title from catalog.json; [moduleLabel] remains the stable technical id. */
    displayTitle: String? = null,
    /**
     * Whether this page is served as a **top-level site** ([ServeSites]) — its catalog rooted on a
     * hostname of its own. The session is then implied by the ORIGIN, exactly as a `/<system>`
     * mount implies it by the path, so same-session links must not repeat it as `?session=`. False
     * (the default) leaves every existing caller's URLs byte-identical.
     */
    sessionInOrigin: Boolean = false,
    /** Validated catalog-published issues, matched onto each component card. */
    parityIssues: List<ParityIssue> = emptyList(),
    componentBrowser: Boolean = false,
    /**
     * GitHub session state, rendered as the header's sign-in control.
     *
     * A catalog landing is where a visitor arrives, and on a **top-level site** ([ServeSites]) it
     * is the whole front door — there is no home index above it carrying the control, so before
     * this the only sign-in affordance on a host like `wear.preview.coo.ee` was a press-and-hold on
     * a card (which follows the login) or a chip on a preview page. Someone who wanted a live
     * session had to be told to go and sign in on a *different hostname* first
     * (wear-m3-catalog#68).
     *
     * Null, and in Catalog mode, renders nothing — same as every other page. Catalog mode drops the
     * live lane entirely (hover-live included), so a sign-in offered there would unlock nothing.
     */
    githubAuth: GitHubAuthStatus? = null,
    /**
     * The catalog change feed the footer offers as **Changelog** and the head declares as this
     * page's RSS alternate. Empty when the server runs with the feed lane off. See [siteFooter].
     */
    changelogHref: String = "",
  ): String {
    @Suppress("NAME_SHADOWING") val designPages = if (componentBrowser) emptyList() else designPages
    @Suppress("NAME_SHADOWING")
    val parityIssues = if (componentBrowser) emptyList() else parityIssues
    @Suppress("NAME_SHADOWING") val hasSvgComparison = hasSvgComparison && !componentBrowser
    @Suppress("NAME_SHADOWING") val hasRcComparison = hasRcComparison && !componentBrowser
    @Suppress("NAME_SHADOWING")
    val hasReferenceComparison = hasReferenceComparison && !componentBrowser
    @Suppress("NAME_SHADOWING") val hasParityView = hasParityView && !componentBrowser
    // Suppressed in Catalog mode with the other destinations, and it is a close call rather than
    // an obvious one. The motion browser is browsing surface, not tooling — it is the collection
    // view of a control Catalog mode deliberately KEEPS per component — so the case for showing it
    // there is real. What settles it is the affordance: every other entry in the `⋯` menu is
    // stripped in that mode, so keeping this one would give Catalog mode a menu that exists to
    // hold a single item. Flip this line (and the checklist entry it is recorded under) if the
    // collection view turns out to be what streamlined visitors come for.
    @Suppress("NAME_SHADOWING")
    val motionCaptureCount = if (componentBrowser) 0 else motionCaptureCount
    @Suppress("NAME_SHADOWING") val playgroundHref = playgroundHref?.takeUnless { componentBrowser }
    @Suppress("NAME_SHADOWING")
    val degradations = if (componentBrowser) emptyList() else degradations
    // The session id links may carry. Null on a rooted site (and for the default session): the
    // URL already says which catalog this is. `sessionId` itself stays intact below — it keys the
    // per-catalog localStorage entries and the dark-first lookup, which a site still needs.
    val linkSessionId = if (sessionInOrigin) null else sessionId
    val q = querySuffix(linkQuery(token, linkSessionId, basePath, isPublic))
    // The Dev-mode `uses:` operator's endpoint (`ServeHttpServer.handleUsesSearch`). Empty in
    // Catalog mode, which is what keeps the operator out of that presentation entirely: no
    // `data-uses-id` on a card, no branch of it in the filter script, and the same bytes on the
    // wire a Catalog-mode visitor got before this existed. The route itself is gated the same way,
    // so an empty string here is a matching front end to a 404 rather than the only lock.
    val usesUrl = if (componentBrowser) "" else "$basePath/api/uses$q"
    val usesFilter = usesUrl.isNotEmpty()
    val themeLeaseUrl =
      if (themeRenderBurstCapacity > 1) "$basePath/api/theme-render-lease$q" else ""
    val navSuffix =
      querySuffix(if (isPublic) "" else "token=" + WebEscaping.urlEncodeSegment(token))
    val heading = catalogHeading(displayTitle, moduleLabel)
    val catalogId =
      if (componentBrowser || heading == moduleLabel) ""
      else "<p class=\"cp-catalog-id\">${WebEscaping.htmlEscape(moduleLabel)}</p>"
    // A dark-first system (Wear) puts every unthemed card on the dark stage; explicit light/dark
    // variants keep their own token. Only affects the background — the Light/Dark filter axis below
    // still keys off the explicit-only [cardTheme].
    val darkFirst = isDarkFirstSystem(basePath, sessionId, declaredSurface)
    // Collapse per-theme variants into one card each so the Light/Dark control swaps a card between
    // its baked light/dark render *in place*, rather than filtering two cards. A single-theme /
    // theme-neutral card carries no swap data and the toggle leaves it alone.
    // Fold non-default component states (unchecked/pressed/disabled/…), props-axis variants
    // (locale/direction-rtl/fontScale/content) AND non-primary breakpoints out of the grid first,
    // so a component shows ONE card (its default render at its first declared size) instead of a
    // card per state, per variant or per screen size; the folded renders stay reachable through the
    // viewer's state + variant + size switchers. Plain bundle screens (no state, no props, no
    // declared size) pass straight through.
    val primarySizes = primarySizeByComponent(previews, darkFirst)
    val groups =
      groupPreviews(
        previews.filterNot {
          (componentBrowser && it.renderFailure != null) ||
            (it.renderFailure == null &&
              (isNonDefaultState(it) ||
                hasNonDefaultProps(it) ||
                isNonPrimarySize(it, primarySizes, darkFirst)))
        }
      )
    val cardAnchors = mintCardAnchors(groups)
    val renderFailureSummary =
      if (componentBrowser) ""
      else
        previews
          .mapNotNull { it.renderFailure }
          .groupBy { it.errorClass to it.message }
          .takeIf { it.isNotEmpty() }
          ?.let { failures ->
            buildString {
              val total = failures.values.sumOf { it.size }
              append("<aside class=\"cp-render-failure-summary\"><strong>$total failed render")
              if (total != 1) append("s")
              append("</strong><ul>")
              failures.forEach { (signature, occurrences) ->
                append("<li><span>")
                append(WebEscaping.htmlEscape(signature.first.substringAfterLast('.')))
                if (signature.second.isNotBlank()) {
                  append(": ")
                  append(WebEscaping.htmlEscape(signature.second))
                }
                append("</span><strong>×${occurrences.size}</strong></li>")
              }
              append("</ul></aside>\n")
            }
          } ?: ""
    // A catalog whose breakpoints reach the server as metadata has just been folded to one card per
    // component above, so its labels no longer collide on the size axis. This is the fallback for
    // one whose sizes live only in the id — an older export, or a plain bundle's device fan-out —
    // where each size is still a card of its own (for example three "Edgebutton" cards at
    // Small/Large/XL Round). Add a qualifier only when the base label actually collides, keeping
    // ordinary one-card labels terse.
    val duplicateGridLabels =
      groups.groupingBy { previewDisplayName(it.default) }.eachCount().filterValues { it > 1 }.keys
    fun gridDisplayName(preview: ServePreview): String {
      val label = previewDisplayName(preview)
      if (label !in duplicateGridLabels) return label
      val size = sizeLabel(preview) ?: return label
      return "$label · $size"
    }
    // A card's pixel URL. With a prebaked thumbnail it carries `?thumb=<hash>`, which the render
    // lane answers from memory with the downscaled image; the id and every other param stay
    // identical, so the SAME URL still serves a full render once anything is layered on it (a
    // declared theme appends `themeProvider=`, and an override present means the thumbnail can't
    // answer). That is what lets one helper feed the card's `src`, its light/dark swap targets and
    // its themed-render base without any of them having to know which lane will answer.
    fun renderSrc(p: ServePreview): String {
      val base = "$basePath/render/${WebEscaping.urlEncodeSegment(p.id)}.png$q"
      val hash = thumbHash(p.id) ?: return base
      val sep = if (base.contains('?')) "&" else "?"
      return "$base$sep${ServeHeroImages.THUMB_PARAM}=${WebEscaping.urlEncodeSegment(hash)}"
    }
    fun viewerHref(p: ServePreview) = "$basePath/p/${WebEscaping.urlEncodeSegment(p.id)}$q"
    // The app-declared themes join the header's Theme control only when this session can actually
    // re-render a card under one — otherwise the chips would redraw nothing.
    fun themeRenderable(p: ServePreview) = canRenderThemeFor(p.id)
    // Whether a declared theme actually redraws this preview: it needs a daemon twin, must not be a
    // theme specimen (which has a twin but must keep its baked pixels — [isThemeSpecimen]), and
    // must be re-rendered by RE-RUNNING its composable rather than by replaying a captured document
    // ([irReplayFor]) — a theme provider wraps a composition, so a replay has nothing to wrap and
    // the server refuses that render 409.
    // ONE predicate feeding both the chip gate and the per-card URL, deliberately: gating the chips
    // on mere renderability while the URLs also excluded specimens would offer the control on a
    // catalog whose only twinned cards are specimens — every `themeBase` empty, the browser's
    // `if (!img || !base) return` skipping every card, and the chips a no-op.
    fun themeOverridable(p: ServePreview) =
      themeRenderable(p) && !isThemeSpecimen(p) && !irReplayFor(p.id)
    // The variant a card shows by default (server-side) — the one a declared theme re-renders.
    fun renderedVariant(card: GridCard) =
      if (card.swappable && darkFirst) card.dark!! else card.default
    val declaredThemeChips =
      if (declaredThemes.isEmpty()) emptyList()
      else if (groups.any { themeOverridable(renderedVariant(it)) }) declaredThemes else emptyList()
    // A card's themed-render base URL — "" when a declared theme wouldn't redraw it, so it keeps
    // its baked pixels.
    fun themeBase(card: GridCard) =
      renderedVariant(card).let { if (themeOverridable(it)) renderSrc(it) else "" }
    fun cardViews(card: GridCard): Long =
      listOfNotNull(card.light, card.dark, card.neutral).sumOf { engagement[it.id]?.views ?: 0L }
    fun swapCard(card: GridCard, anchor: String): String {
      val l = card.light!!
      val d = card.dark!!
      // Default to the light render (dark-first systems open dark); the JS re-swaps to the sticky
      // choice on load. Each theme's src / viewer href / id / label ride as data-* so the swap
      // needs
      // no URL-building in the browser.
      val def = if (darkFirst) d else l
      val defTheme = if (darkFirst) "dark" else "light"
      val lightLabel = gridDisplayName(l)
      val darkLabel = gridDisplayName(d)
      val defaultLabel = gridDisplayName(def)
      val issueBadge =
        parityIssueBadgeHtml(
          listOfNotNull(card.light, card.dark, card.neutral)
            .flatMap { issuesForPreview(parityIssues, it) }
            .distinctBy { it.repository to it.number }
        )
      // `data-def` is the variant a DECLARED theme re-renders (the server-side default), so picking
      // one doesn't also flip the card's light/dark base.
      return """
        <a class="cp-card"$anchor aria-label="${WebEscaping.htmlEscape(defaultLabel)}" data-swap="1" data-bg-theme="$defTheme" data-def="${if (darkFirst) "d" else "l"}"
          data-l-src="${renderSrc(l)}" data-l-href="${viewerHref(l)}"
          data-l-id="${WebEscaping.htmlEscape(l.id)}" data-l-label="${WebEscaping.htmlEscape(lightLabel)}"
          data-d-src="${renderSrc(d)}" data-d-href="${viewerHref(d)}"
          data-d-id="${WebEscaping.htmlEscape(d.id)}" data-d-label="${WebEscaping.htmlEscape(darkLabel)}"
          href="${viewerHref(def)}">
          <div class="cp-imgwrap">
            <img loading="lazy" alt="${WebEscaping.htmlEscape(defaultLabel)}" src="${renderSrc(def)}">
          </div>
          <div class="cp-meta">
            <div class="cp-label" title="${WebEscaping.htmlEscape(def.id)}">${WebEscaping.htmlEscape(defaultLabel)}</div>
            ${if (componentBrowser) "" else cardIdHtml(def.id)}$issueBadge
            ${if (componentBrowser) "" else viewCountHtml(cardViews(card))}
          </div>
        </a>
        """
        .trimIndent()
    }
    fun singleCard(p: ServePreview, anchor: String): String {
      val idSeg = WebEscaping.urlEncodeSegment(p.id)
      val label = WebEscaping.htmlEscape(gridDisplayName(p))
      val src = renderSrc(p)
      val idText = WebEscaping.htmlEscape(p.id)
      val issueBadge = parityIssueBadgeHtml(issuesForPreview(parityIssues, p))
      p.renderFailure?.let { failure ->
        val errorName = failure.errorClass.substringAfterLast('.').ifBlank { "RenderError" }
        val message = failure.message.takeIf { it.isNotBlank() } ?: "The preview did not render."
        val frame =
          failure.topAppFrame?.let {
            "<div class=\"cp-render-failure-frame\">at ${WebEscaping.htmlEscape(it.file)}:" +
              "${it.line} · ${WebEscaping.htmlEscape(it.function)}</div>"
          } ?: ""
        val stack =
          failure.stackTrace
            ?.takeIf { it.isNotBlank() }
            ?.let {
              "<details class=\"cp-render-stack\"><summary>Stack trace</summary><pre>" +
                WebEscaping.htmlEscape(it) +
                "</pre></details>"
            } ?: ""
        return """
          <details class="cp-card cp-card--render-failed"$anchor>
            <summary>
              <div class="cp-imgwrap cp-render-failure">
                <span class="cp-render-failure-mark">!</span>
                <strong>${WebEscaping.htmlEscape(errorName)}</strong>
                <span>${WebEscaping.htmlEscape(message)}</span>
              </div>
              <div class="cp-meta">
                <div class="cp-label" title="$idText">$label</div>
                <div class="cp-id">render failed · ${WebEscaping.htmlEscape(failure.phase)}</div>
              </div>
            </summary>
            <div class="cp-render-failure-detail">
              <strong>${WebEscaping.htmlEscape(failure.errorClass)}</strong>
              <p>${WebEscaping.htmlEscape(message)}</p>
              $frame
              $stack
            </div>
          </details>
          """
          .trimIndent()
      }
      // data-bg-theme is the thumbnail's background: what the preview declares for itself first,
      // then the explicit id token, then the dark-first default. Without the first rung the grid
      // and the reference page answered differently for the same preview — a card showing a
      // deliberately white specimen on this catalog's dark plate, and the comparison of that same
      // specimen on white.
      val bgAttr =
        (declaredBackdropTheme(p) ?: bgTheme(p.id, darkFirst))?.let { " data-bg-theme=\"$it\"" }
          ?: ""
      return """
          <a class="cp-card"$anchor$bgAttr href="$basePath/p/$idSeg$q" aria-label="$label">
            <div class="cp-imgwrap">
              ${thumbImg(src, label, " loading=\"lazy\"", thumbCrop(p.id))}
            </div>
            <div class="cp-meta">
              <div class="cp-label" title="$idText">$label</div>
              ${if (componentBrowser) "" else cardIdHtml(p.id)}$issueBadge
              ${if (componentBrowser) "" else viewCountHtml(engagement[p.id]?.views ?: 0L)}
            </div>
          </a>
          """
        .trimIndent()
    }
    // Every card carries the anchor its tree row jumps to. Derived from the default render's id
    // rather than from position, so a row keeps pointing at the same component as the catalog
    // grows.
    fun cardHtml(card: GridCard): String {
      // The `uses:` filter matches on the card's DEFAULT preview id, and one id is enough: a
      // component's themes, states, sizes and content variants are renders of one declaration, so
      // they share a source file and a body line and would every one of them carry the same answer.
      val usesId =
        if (usesFilter) " data-uses-id=\"${WebEscaping.htmlEscape(card.default.id)}\"" else ""
      val anchor = " id=\"${cardAnchors.getValue(card.default.id)}\"" + usesId
      return if (card.swappable) swapCard(card, anchor) else singleCard(card.default, anchor)
    }
    val cards =
      if (groups.isEmpty()) {
        "<p class=\"cp-sub\">No previews discovered in this module.</p>"
      } else {
        groups.joinToString("\n") { cardHtml(it) }
      }
    // A catalog whose previews carry sections renders as TABS (one per section, e.g. Themes /
    // Components / Screens / Animations) over per-section panels, with the component `group` as a
    // sub-heading inside a tab. A section-less catalog keeps a single flat grid — but still gains
    // synthesized family sub-group dividers ([synthesizeGroups]) when that helps a large catalog
    // scan, so compose-m3's 84 tiles read as grouped clusters instead of one undivided wall.
    val sections = buildSections(groups)
    val hasTabs = sections.isNotEmpty()
    // The tree's All row, and the landing selection ([catalogTreeHtml]). One section is already
    // the whole catalog, so it gets no row and its script never mentions `all`.
    val hasAllTab = sections.size > 1
    val synthGroups = if (hasTabs) null else synthesizeGroups(groups)
    // Any `.cp-subgroup` dividers present (authored tabs OR synthesized flat groups) → the filter
    // script must collapse an emptied sub-group on search, independent of the tab machinery.
    val hasGroups = hasTabs || synthGroups != null
    // Slugs for the synthesized families, so an outline row has an anchor to jump to. Assigned
    // here rather than in [synthesizeGroups] because only the tree needs them.
    if (synthGroups != null) {
      val used = HashSet<String>()
      synthGroups.forEach { g ->
        var slug = g.name?.let { sectionSlug(it) } ?: "ungrouped"
        var n = 2
        val base = slug
        while (!used.add(slug)) {
          slug = "$base-$n"
          n++
        }
        g.slug = slug
      }
    }
    // The component row for a card: its grid label, the card's own anchor, and the primary-axis
    // variants the grid folded out from under it. Built from the render the grid actually paints,
    // which on a dark-first system is the dark one.
    fun treeComponent(card: GridCard): TreeComponent {
      val shown = card.rendered(darkFirst)
      return TreeComponent(
        label = gridDisplayName(shown),
        anchorId = cardAnchors.getValue(card.default.id),
        variants = primaryVariants(shown, previews, darkFirst) { viewerHref(it) },
        href = viewerHref(shown),
      )
    }
    // The design file's pages, listed at the foot of whichever tree this catalog has. A catalog
    // with no tree (too few previews to synthesize families from, and no authored sections) has
    // nowhere to put them and keeps the header chip instead — see the action row below.
    val hasTree = hasTabs || synthGroups != null
    // Pages become a PANE beside Components rather than a branch under them — but only for a
    // catalog that has both, since a strip with one tab switches nothing. Without pages the tree
    // is emitted exactly as before, branch argument and all, so those goldens do not move.
    val hasPanes = hasTree && designPages.isNotEmpty()
    val pagesBranch = if (hasTree && !hasPanes) pagesBranchHtml(designPages, basePath, q) else ""
    val tabBar =
      when {
        hasTabs -> catalogTreeHtml(sections, ::treeComponent, pagesBranch)
        synthGroups != null -> catalogOutlineTreeHtml(synthGroups, ::treeComponent, pagesBranch)
        else -> ""
      }
    // The grid body: either the tabbed section panels (id=cp-grid, so the search box's
    // aria-controls
    // + the filter script still target it) or the plain flat grid. The flat form reproduces the
    // exact whitespace of the pre-tabs template (the `$cards` and `</div>` lines carried the body
    // template's 8-space indent, which survives `trimIndent` because the interpolated cards sit at
    // column 0) so a section-less catalog's committed golden is byte-for-byte unchanged.
    val gridBlock =
      if (!hasTabs && synthGroups != null) {
        // Section-less catalog with synthesized family dividers: a flat grid of labelled
        // sub-groups (no tab bar). `#cp-grid` still wraps it for the search box's aria-controls.
        buildString {
          append("<div class=\"cp-grid-groups\" id=\"cp-grid\">\n")
          synthGroups.forEach { g ->
            // `--cp-n` is the card count, and it is what stops a one-card family from reserving a
            // whole five-column row: the sheet is a FLOW of clusters, each asking for the width
            // its own cards occupy (see `.cp-subgroup` in serve.css). Written here rather than
            // measured in CSS because only the server knows how many cards the group holds.
            append("<div class=\"cp-subgroup\" id=\"${flatGroupAnchorId(g.slug)}\"")
            append(" style=\"--cp-n:${g.cards.size}\">\n")
            if (g.name != null)
              append("<h2 class=\"cp-group-head\">${WebEscaping.htmlEscape(g.name)}</h2>\n")
            append("<div class=\"cp-cards\">\n")
            g.cards.forEach { append(cardHtml(it)).append("\n") }
            append("</div>\n</div>\n")
          }
          append("</div>")
        }
      } else if (!hasTabs) {
        "<div class=\"cp-grid\" id=\"cp-grid\">\n        $cards\n        </div>"
      } else {
        buildString {
          append("<div class=\"cp-sections\" id=\"cp-grid\">\n")
          sections.forEach { sec ->
            // `role="region"`, not `tabpanel`: the navigation above is a tree, and a tabpanel with
            // no tab to own it is a role that describes a relationship the page no longer has.
            append("<section class=\"cp-section\" id=\"cp-panel-${sec.slug}\" role=\"region\"")
            append(" aria-labelledby=\"cp-tab-${sec.slug}\" data-section=\"${sec.slug}\">\n")
            append("<h2 class=\"cp-section-head\">${WebEscaping.htmlEscape(sec.name)}</h2>\n")
            sec.groups.forEach { g ->
              // The tree's group rows jump here, so a named group carries the anchor id the row
              // links to; an unnamed one has no row and needs none.
              val anchor = if (g.name == null) "" else " id=\"${groupAnchorId(sec.slug, g.slug)}\""
              // `--cp-n`: the cluster's width in cards — see the synthesized-groups branch above.
              append("<div class=\"cp-subgroup\"$anchor style=\"--cp-n:${g.cards.size}\">\n")
              if (g.name != null)
                append("<h3 class=\"cp-group-head\">${WebEscaping.htmlEscape(g.name)}</h3>\n")
              append("<div class=\"cp-cards\">\n")
              g.cards.forEach { append(cardHtml(it)).append("\n") }
              append("</div>\n</div>\n")
            }
            append("</section>\n")
          }
          append("</div>")
        }
      }
    // A tree stands BESIDE what it navigates. Its filter is part of that navigation, so the two
    // share one sidebar and remain together when the menu becomes sticky. A small catalog with no
    // tree keeps the filter in the toolbar above its flat grid.
    val sidebarSearch =
      if (tabBar.isEmpty() || previews.isEmpty()) "" else searchBoxHtml(usesFilter) + "\n"
    // With both lists in play the tree becomes the Components PANE and the pages get their own,
    // with the switch above the filter that serves them both. The strip leads: it says what the
    // column is showing, and the filter below it reads as belonging to whichever that is.
    val sidebarBody =
      if (!hasPanes) tabBar
      else
        paneTabsHtml(previews.size, designPages.size) +
          "\n" +
          sidebarSearch +
          "<div class=\"cp-pane\" id=\"cp-pane-components\" role=\"tabpanel\"" +
          " aria-labelledby=\"cp-pane-tab-components\">\n" +
          tabBar +
          "</div>\n" +
          pagesPaneHtml(designPages, basePath, q)
    val sidebarHead = if (hasPanes) "" else sidebarSearch
    val navAndGrid =
      if (tabBar.isEmpty()) "$tabBar$gridBlock"
      else
        "<div class=\"cp-catalog-body\">\n" +
          "<aside class=\"cp-catalog-menu\" aria-label=\"Catalog menu\">\n" +
          "$sidebarHead$sidebarBody</aside>\n$gridBlock\n</div>"
    // A catalog page links HOME (the front-door index) rather than sideways to its siblings: the
    // old design-systems nav row is replaced by a single back button, shown whenever this server
    // publishes catalogs (i.e. a home index exists to go back to). It rides in the site header's
    // brand slot with every other page's breadcrumb, rather than as the body's first line — a
    // catalog page's own heading and grid then start at the top of the content column.
    // The brand already links to the front door; an adjacent back button duplicated it.
    val back = ""
    // The catalog-provenance strip (delivery branch, generation date, tool versions, regenerate
    // link) rides in the site footer, next to the build and source links it belongs with, rather
    // than interrupting the route from the catalog's heading to its content. It renders expanded:
    // it is short, and the facts a visitor would cite a rendering by shouldn't need a click.
    val prov = provenance?.let { provenanceSection(it, refreshUrl) } ?: ""
    // The Theme control shows when there is more than one theme to choose between: a baked
    // light/dark pair to swap, and/or the app-declared themes this session can re-render under. A
    // catalog with neither (mostly theme-neutral app screens on a static bundle) never sprouts a
    // control that would do nothing.
    val hasBakedThemes = groups.any { it.swappable }
    val hasThemes = hasBakedThemes || declaredThemeChips.isNotEmpty()
    val themeToggle =
      if (hasThemes) themePickerHtml(hasBakedThemes, declaredThemeChips) + "\n" else ""
    // Search + empty-state + the combined filter script are shown whenever there are previews to
    // filter, independent of the theme axis.
    val hasPreviews = previews.isNotEmpty()
    val searchBox = if (hasPreviews && tabBar.isEmpty()) searchBoxHtml(usesFilter) + "\n" else ""
    val emptyState =
      if (hasPreviews)
        "\n<p id=\"cp-empty\" class=\"cp-empty\" hidden>No previews match your filter.</p>"
      else ""
    // The themed-render URLs in the grid's DOCUMENT order — the order the cards were just emitted
    // in, which is what `document.querySelectorAll(".cp-card")` will report. Empty (no array, no
    // theme-render machinery in the script) unless declared themes are actually offered.
    val orderedCards =
      when {
        hasTabs -> sections.flatMap { s -> s.groups.flatMap { it.cards } }
        synthGroups != null -> synthGroups.flatMap { it.cards }
        else -> groups
      }
    val themeBaseJs =
      if (declaredThemeChips.isEmpty()) ""
      else orderedCards.joinToString(", ", "[", "]") { WebEscaping.jsString(themeBase(it)) }
    val filterScript =
      if (hasPreviews)
        "\n${scriptTag("serve-components.js")}\n<script>${catalogFilterScript(
          hasThemes,
          hasTabs,
          hasGroups,
          tabBar.isNotEmpty(),
          themeStorageKey(sessionId, basePath),
          tabStorageKey(sessionId, basePath),
          themeBaseJs,
          themeLeaseUrl,
          presenceUrl,
          hasPanes,
          hasAllTab,
          usesUrl,
        )}</script>"
      else ""
    // The long-press live lane, in the SAME document order as the cards above (and as
    // [themeBaseJs]) — a card's entry is its light/dark pair of ids, or a pair of empty strings
    // when this session can't stream it.
    val liveScript =
      if (componentBrowser) ""
      else
        catalogLiveScript(
          basePath = basePath,
          query = linkQuery(token, linkSessionId, basePath, isPublic),
          cards =
            orderedCards.map { card ->
              fun streamable(p: ServePreview) = if (canStreamLiveFor(p.id)) p.id else ""
              if (card.swappable) streamable(card.light!!) to streamable(card.dark!!)
              else streamable(card.default).let { it to it }
            },
          signInHref = liveSignInHref,
        )
    // Discoverability for the gesture: the per-card affordance only appears on hover, so the
    // header says once that the lane exists. Shown exactly when a card can actually take it.
    val liveNote =
      if (liveScript.isEmpty()) ""
      else " · <span class=\"cp-live-note\">hold a card for a live session</span>"
    // ---- The catalog's actions
    // -------------------------------------------------------------------
    //
    // A row of M3 assist chips under the summary line, in place of the run of 0.75rem muted text
    // links this line used to end with (`… · compare formats · design parity · try in playground`).
    // Those were the page's only routes to the comparison and parity views, and they were styled to
    // disappear: smaller than the body copy, grey until hovered, and separated by interpuncts that
    // read as one sentence rather than as several destinations. A chip is the M3 vocabulary this
    // page already speaks (the theme toggle right below it is the same shape), and it makes each
    // route a thing you can see and hit.
    fun actionChip(href: String, label: String): String =
      "<a class=\"cp-action-chip\" href=\"${WebEscaping.htmlEscape(href)}\">" +
        "${WebEscaping.htmlEscape(label)}</a>"

    // One action per comparison a visitor might actually want, rather than a single "compare
    // formats" that made them discover the format switcher to find out what this catalog can even
    // compare. Each deep-links the comparison page's own `?format=` so the landing already answers
    // "compare *what*", and a catalog carrying only one of them shows only that one.
    fun compareChip(format: String, label: String): String {
      val query =
        listOf("format=$format", linkQuery(token, linkSessionId, basePath, isPublic))
          .filter { it.isNotEmpty() }
          .joinToString("&")
      return actionChip("$basePath/compare?$query", label)
    }
    val actionChips =
      listOfNotNull(
          compareChip("svg", "compare SVG").takeIf { hasSvgComparison },
          compareChip("rc", "compare RC players").takeIf { hasRcComparison },
          // Named after the design tool it compares against when the catalog identifies one, since
          // "compare to Figma" says what you get where "compare reference" would name the format
          // slug. It sits with the other compare chips because it goes where they go — the same
          // comparison page, deep-linked to its own format — rather than to a different page.
          compareChip(
              "reference",
              designToolLabel?.let { tool -> "compare to $tool" } ?: "compare to design references",
            )
            .takeIf { hasReferenceComparison },
          // The parity dashboard is a different question from the side-by-side: how the code and
          // the design file have *moved*, and how far apart they are — so it keeps its own name
          // rather than borrowing the comparison's.
          actionChip("$basePath/parity$q", "design parity").takeIf { hasParityView },
          // The motion browser. A destination like the comparisons and the parity view — captures
          // are scattered one-per-component and invisible until you open the component that has
          // one, so this is the only place a visitor can find out the catalog records anything at
          // all. It is NOT gated on having a tree the way the pages chip is: there is no tree
          // listing to fall back on, so without the chip the page would be published and
          // unreachable on every catalog.
          motionCaptureCount
            .takeIf { it > 0 }
            ?.let {
              actionChip(
                "$basePath/motion$q",
                "$it motion ${if (it == 1) "capture" else "captures"}",
              )
            },
          // Pages live in the navigation tree, which is where this catalog's other *places* are.
          // This chip is the fallback for a catalog too small to have a tree at all: without it
          // the pages would be published and unreachable. The count is in the label because one
          // page and thirty are different offers.
          designPages
            .takeIf { it.isNotEmpty() && !hasTree }
            ?.let {
              actionChip("$basePath/pages$q", "${it.size} ${if (it.size == 1) "page" else "pages"}")
            },
          playgroundHref?.takeIf { it.isNotBlank() }?.let { actionChip(it, "try in playground") },
        )
        .joinToString("\n          ")
    val transparentAction =
      if (hasPreviews && !componentBrowser)
        bgPickerHtml("Show the transparent checkerboard behind each preview")
      else ""
    val catalogActions =
      listOf(actionChips, transparentAction).filter { it.isNotBlank() }.joinToString("\n          ")
    // …behind one `⋯` menu beside the Theme pill, at every width. These are the catalog's
    // *destinations* — the comparison views, the parity view, the playground — plus the Transparent
    // toggle: things a visitor goes looking for, not things they read on the way past, which is why
    // they no longer spend a full row of their own above the grid on any viewport.
    val primaryActions =
      catalogActions
        .takeIf { it.isNotBlank() }
        ?.let {
          "<div class=\"cp-catalog-actions\">\n" +
            "          <details class=\"cp-actions-menu\">\n" +
            "            <summary class=\"cp-drawer-toggle cp-axis-toggle\" " +
            "title=\"More for this catalog\" aria-label=\"More for this catalog\" " +
            "aria-controls=\"cp-catalog-actions-panel\">" +
            "<span aria-hidden=\"true\">⋯</span></summary>\n" +
            "          </details>\n" +
            "          <div class=\"cp-actions-panel\" id=\"cp-catalog-actions-panel\">\n" +
            "          $it\n          </div>\n        </div>\n"
        } ?: ""
    val downloadAction =
      if (componentBrowser) ""
      else
        "\n<div class=\"cp-catalog-download\">" +
          actionChip("$basePath/bundle.zip$q", "download all (.zip)") +
          "</div>\n"
    // The viewer's identity line, on the landing: name, trust verdict, id and the preview/view
    // tally on ONE baseline (`.cp-preview-head` does the same three above the render). They all
    // answer "what am I looking at", and as two stacked blocks with a chip row under them they
    // answered it across three rows of a fold that is meant to be showing previews.
    val subLine =
      if (componentBrowser) ""
      else
        "<p class=\"cp-sub\">${counted(previews.size, "preview(s)")}" +
          (if (systemViews > 0) " · ${formatViews(systemViews)}" else "") +
          "$liveNote</p>"
    // …and the viewer's control row: the page's controls over what is *shown*, as compact pills at
    // the trailing edge of one bar (`.cp-head-toggles`, the same class and the same trailing auto
    // margin the viewer's title row uses), with the filter field taking the width beside them. The
    // Theme chips and the action chips used to be two rows of their own; behind their pills the
    // bar is one row on every viewport, and it is the row that sticks.
    val headToggles =
      (themeToggle + primaryActions)
        .takeIf { it.isNotBlank() }
        ?.let { "<div class=\"cp-head-toggles\">\n$it</div>\n" } ?: ""
    // The toolbar row is the FILTER's row, and the pills are its passengers. Where the filter is
    // not in it the row is a full-width sticky band holding two pills at its trailing edge and
    // nothing else — an empty strip between the heading and the grid, which is what browser mode
    // was given the identity row for. A SECTIONED catalog is the same case and was missed: its
    // filter belongs to the tree's sidebar, so its toolbar carries the Theme pill and the `⋯`
    // alone, and a catalog with one theme carries only the `⋯` — one 34px pill in an 80px band
    // above 190 previews (issue #4224). So the toggles ride on the identity row whenever the
    // toolbar has no filter to keep them company, and no toolbar row is emitted at all.
    val togglesOnTitleRow = componentBrowser || searchBox.isBlank()
    val titleRow =
      "<div class=\"cp-catalog-head-row\">" +
        "<div class=\"cp-catalog-title\">" +
        "<h1 class=\"cp-head cp-catalog-head\">${WebEscaping.htmlEscape(heading)}" +
        "${compactTrustBadge(trust)}</h1>$catalogId</div>$subLine" +
        (if (togglesOnTitleRow) headToggles else "") +
        "</div>"
    val tools =
      (searchBox + if (togglesOnTitleRow) "" else headToggles)
        .takeIf { it.isNotBlank() }
        ?.let { "<div class=\"cp-catalog-tools\">\n$it</div>\n" } ?: ""
    return document(
      changelogHref = changelogHref,
      title = "$heading — compose-preview",
      unfurlTitle = heading,
      unfurlDescription = catalogUnfurlDescription(previews.size, heading),
      unfurl = unfurl,
      navSuffix = navSuffix,
      headerBreadcrumb = back,
      version = version,
      footerNote = if (componentBrowser) "" else prov,
      themeCss = themeCss,
      // The bar names the catalog you are in, from the same heading the page shows.
      siteName = heading,
      themeStorageKey = themeStorageKey(sessionId, basePath),
      declaredThemes = declaredThemeChips,
      headerAction = if (componentBrowser) "" else githubAuthControl(githubAuth),
      body =
        """
        $titleRow
        ${degradeBanner(degradations)}$renderFailureSummary$tools$navAndGrid$emptyState$filterScript$liveScript$downloadAction
        <!-- Finishes the phone shape of this page's chrome: the tree sidebar's filter field moves
             into the sticky toolbar beside the Theme and `⋯` menus already there, and back out
             again above 640px, and the summary tally drops below the grid. Renders nothing;
             `serve.css` hides the tag. -->
        <cp-catalog-toolbar></cp-catalog-toolbar>
        """
          .trimIndent(),
      componentBrowser = componentBrowser,
      interfaceModeControl = true,
    )
  }

  /**
   * Display name for a design reference's `source.provider` token — `figma` → `Figma`.
   *
   * Null for a provider that names no design tool (a checked-in `png`, an `svg`, an `html` mock, or
   * the default `file`), so a caller falls back to neutral wording instead of inventing a vendor
   * the catalog never claimed. Only tokens we can name are mapped: an unknown provider is not
   * title-cased into a plausible-looking product name.
   */
  fun designToolLabel(provider: String?): String? =
    when (provider?.trim()?.lowercase()) {
      "figma" -> "Figma"
      "sketch" -> "Sketch"
      "penpot" -> "Penpot"
      "framer" -> "Framer"
      else -> null
    }

  /** PNG↔native-format and PNG↔design-reference comparison page for one served session. */
  fun comparisonPage(
    moduleLabel: String,
    previews: List<ServePreview>,
    token: String,
    sessionId: String? = null,
    basePath: String = "",
    isPublic: Boolean = false,
    trust: String? = null,
    declaredSurface: String? = null,
    /**
     * The served catalog's own palette as an inline `:root` override for the chrome's custom
     * properties, built by [ServeThemeCss] from the branch's `tokens.dtcg.json`. Empty ⇒ the page
     * keeps the built-in chrome (a plain module, or a catalog that publishes no tokens).
     */
    themeCss: String = "",
    hasSvgFor: (String) -> Boolean = { false },
    hasRemoteComposeFor: (String) -> Boolean = { false },
    /**
     * The catalog's **published** Remote Compose player comparison, when it has one. Present ⇒ the
     * `rc` format shows every player side by side from the offline run's renders (see
     * [rcLanesSection]) instead of rendering one player's output in the visitor's browser.
     */
    rcCompare: RcCompareManifest? = null,
    referencesFor: (String) -> List<DesignReference> = { emptyList() },
    unfurl: UnfurlMetadata? = null,
    /**
     * The **page-scoped** report this wall files against the catalog's own repo — the launcher's
     * catalog half, which is hidden on any page carrying no `#cp-report` (issue #4289).
     *
     * Page-scoped rather than per-preview because that is what this page honestly knows: it shows
     * every comparable component at once, and the thing that goes wrong here — a lane that scores
     * everything at zero, references paired with the wrong render, a whole catalog drawn in the
     * wrong palette — is about the wall, not about one row. A row's *own* defect already has a
     * better route: the reference opens the focused Reference / Diff / Actual page, which files a
     * report naming that exact preview and reference.
     *
     * Null (a session with no catalog to file against) renders nothing, and the launcher keeps
     * offering the server half alone — the behaviour every page had before.
     */
    reportIssue: ReportIssue? = null,
    /**
     * The catalog's published GitHub issues (`parity/issues.json`), which the wall joins to its
     * rows as the **Bugs** column.
     *
     * A row's score says how far the render is from its design; it cannot say whether anyone
     * already knows. Those are different questions and a triager needs both at once — a 61% row
     * with an open issue against it is somebody's work in progress, and a 61% row with nothing
     * against it is the one to open. The dashboard and the viewer already join this index; the wall
     * is where a reader is actually scanning for what to file (issue #4624).
     *
     * Empty (a session whose catalog publishes no index, or a plain local module) simply drops the
     * column.
     */
    parityIssues: List<ParityIssue> = emptyList(),
    /**
     * Running server version (`SERVE_VERSION`), shown in the minimal footer. Null omits the build
     * span.
     */
    version: String? = null,
    displayTitle: String? = null,
    /**
     * Whether this page is served as a **top-level site** ([ServeSites]) — its catalog rooted on a
     * hostname of its own. The session is then implied by the ORIGIN, exactly as a `/<system>`
     * mount implies it by the path, so same-session links must not repeat it as `?session=`. False
     * (the default) leaves every existing caller's URLs byte-identical.
     */
    sessionInOrigin: Boolean = false,
    /**
     * The catalog change feed the footer offers as **Changelog** and the head declares as this
     * page's RSS alternate. Empty when the server runs with the feed lane off. See [siteFooter].
     */
    changelogHref: String = "",
  ): String {
    // The session id links may carry. Null on a rooted site (and for the default session): the
    // URL already says which catalog this is. `sessionId` itself stays intact below — it keys the
    // per-catalog localStorage entries and the dark-first lookup, which a site still needs.
    val linkSessionId = if (sessionInOrigin) null else sessionId
    val q = querySuffix(linkQuery(token, linkSessionId, basePath, isPublic))
    val navSuffix =
      querySuffix(if (isPublic) "" else "token=" + WebEscaping.urlEncodeSegment(token))
    val heading = catalogHeading(displayTitle, moduleLabel)
    // Native-format rows retain the catalog's one-default-card presentation. A design reference,
    // however, names one exact preview state/props/size mapping, so that referenced variant must
    // remain independently visible instead of being folded out with the landing-page variants.
    //
    // The size axis is folded on exactly that condition and no other. A kit draws its screen cells
    // at one size, so the other breakpoints of a component carry no reference of their own and a
    // row for each is four rows saying "no reference" under one name; but a kit that DOES publish a
    // second size (Wear's `Picker`, at its `Larger Screen (BP)` cell) maps a reference to it, and
    // that row is the whole point of the page.
    val comparableDarkFirst = isDarkFirstSystem(basePath, sessionId, declaredSurface)
    val comparablePrimarySizes = primarySizeByComponent(previews, comparableDarkFirst)
    val comparablePreviews = previews.filterNot { preview ->
      (isNonDefaultState(preview) ||
        hasNonDefaultProps(preview) ||
        isNonPrimarySize(preview, comparablePrimarySizes, comparableDarkFirst)) &&
        referencesFor(preview.id).isEmpty()
    }
    val cards = groupPreviews(comparablePreviews)
    val hasSvg = comparablePreviews.any { hasSvgFor(it.id) }
    // The published comparison is a Remote Compose lane in its own right: it may cover previews
    // whose `.rc` sidecar never reached this box, so it turns the format on by itself.
    val hasRc = comparablePreviews.any { hasRemoteComposeFor(it.id) } || rcCompare != null
    val hasReference = comparablePreviews.any { referencesFor(it.id).isNotEmpty() }
    // Name the design lane after the tool the references actually came from ("PNG ↔ Figma"), the
    // same wording the catalog's own action uses, so the two read as one route rather than two
    // features. A catalog whose references are plain PNGs/mocks keeps the neutral label.
    val referenceToolLabel =
      comparablePreviews.firstNotNullOfOrNull { preview ->
        referencesFor(preview.id).firstNotNullOfOrNull { designToolLabel(it.source.provider) }
      } ?: "Design reference"
    val defaultFormat = if (hasSvg) "svg" else if (hasRc) "rc" else "reference"
    // An imported design spec is always drawn to the LEFT of the render it is compared against —
    // the same order the viewer's spec lane states three ways (the Spec / Diff / Render triptych,
    // the wipe's seam, and the focused Reference / Diff / Actual page). This wall's `reference`
    // lane is that comparison at catalog scale, so it leads with the spec; `svg` and `rc` pit a
    // render against an export OF that render, which is a different question and keeps the render
    // first. `compare/columns.ts` owns the rule, and `<cp-compare-wall>` re-asserts it whenever the
    // visitor switches lane — this only has to be right for the format the page is SERVED on.
    val specLeadsColumns = defaultFormat == "reference"
    val renderCell =
      "<td class=\"cp-compare-render-cell\"><div class=\"cp-compare-shot\">" +
        "<img class=\"cp-compare-png\" alt=\"\"></div></td>"
    // The Remote Compose canvas is CLASSED because a row now holds two of them — this one and the
    // delta map below — and `<cp-compare-wall>` has to tell the one it plays into from the one it
    // paints.
    val targetCell =
      "<td class=\"cp-compare-target-cell\"><div class=\"cp-compare-shot\">" +
        "<img class=\"cp-compare-vector\" alt=\"\"><canvas class=\"cp-compare-rc\" hidden></canvas>" +
        "</div></td>"
    // The delta map, and it belongs BETWEEN the pair wherever the pair ends up — the reference lane
    // leads with the spec, the vector lanes lead with the render, and either way the middle column
    // is what moved between the two beside it. That is the detail page's triptych at catalog scale.
    // Only the reference lane shows it (`serve.css` keys the column off
    // `#cp-compare[data-format]`):
    // the vector lanes compare a render against an export of THAT render, so a map of what moved
    // between them would be describing the exporter rather than the design.
    val diffCell =
      "<td class=\"cp-compare-diff-cell\"><div class=\"cp-compare-shot\">" +
        "<canvas class=\"cp-compare-diff\" aria-label=\"Highlighted pixel difference\"></canvas>" +
        "</div></td>"
    val pictureCells =
      (if (specLeadsColumns) listOf(targetCell, diffCell, renderCell)
        else listOf(renderCell, diffCell, targetCell))
        .joinToString("\n            ")
    val darkFirst = isDarkFirstSystem(basePath, sessionId, declaredSurface)
    // A viewer deep-link may name a non-default state/props variant that is intentionally folded
    // out of this gallery. Keep every sibling id as an alias on the included component row so the
    // client can still select that row instead of presenting an empty comparison page.
    val previewIdsByCard =
      previews.groupBy(::comparisonCardKey).mapValues { (_, values) -> values.map { it.id } }

    fun path(preview: ServePreview, extension: String): String =
      "$basePath/render/${WebEscaping.urlEncodeSegment(preview.id)}.$extension$q"

    fun attrs(
      kind: String,
      theme: String,
      preview: ServePreview?,
      available: (String) -> Boolean,
    ): String {
      if (preview == null || !available(preview.id)) return ""
      return " data-$kind-$theme=\"${WebEscaping.htmlEscape(path(preview, if (kind == "png") "png" else if (kind == "svg") "svg" else "rc"))}\""
    }

    /**
     * The score the delivery branch already measured for one pair, when it carries one.
     *
     * `design-reference-score.mjs` bakes it into `references/index.json` at publish time by driving
     * the wall's own scorer, and [ServeDesignReferenceStore] drops it unless it names this build's
     * kernel — so a number that survives to here is the number this page would compute. Null for a
     * catalog published before the producer existed, or by a run with no browser to score with.
     */
    fun bakedPercent(preview: ServePreview?): Double? = preview?.let {
      referencesFor(it.id).firstOrNull()?.match?.percent
    }

    /**
     * The baked score of the pair this row is SERVED showing — the one `variantFor` resolves for
     * the page's own theme, which is the catalog's theme and then `neutral`.
     *
     * This is what the served row order is taken on. It cannot follow the visitor into another
     * theme (the document is written once), and it does not have to: `<cp-compare-wall>` re-seeds
     * and re-sorts from the per-variant attributes whenever the lane or theme changes.
     */
    fun servedReferencePercent(card: GridCard): Double? {
      val themed = if (darkFirst) card.dark else card.light
      val variant =
        listOfNotNull(themed, card.neutral).firstOrNull { referencesFor(it.id).isNotEmpty() }
      return bakedPercent(variant)
    }

    /** The focused Reference / Diff / Actual page for one pair. */
    fun detailHref(preview: ServePreview, reference: DesignReference): String {
      val detailQuery =
        linkQuery(token, linkSessionId, basePath, isPublic).let { query ->
          listOf(query, "reference=${WebEscaping.urlEncodeSegment(reference.id)}")
            .filter { it.isNotEmpty() }
            .joinToString("&")
        }
      return "$basePath/compare/${WebEscaping.urlEncodeSegment(preview.id)}${querySuffix(detailQuery)}"
    }

    fun referenceAttrs(theme: String, preview: ServePreview?): String {
      val reference = preview?.let { referencesFor(it.id).firstOrNull() } ?: return ""
      val raster = "$basePath/reference/${WebEscaping.urlEncodeSegment(reference.id)}.png$q"
      val detail = detailHref(preview, reference)
      // The published score rides along with the pair it describes, per variant, because the pair
      // is per variant: a row's light and dark references are two independently-exported drawings
      // and two independently-measured numbers. `<cp-compare-wall>` reads the one matching the
      // variant it resolved, so switching theme cannot leave the other theme's number standing.
      val match =
        reference.match
          ?.let { " data-match-$theme=\"${String.format(Locale.ROOT, "%.2f", it.percent)}\"" }
          .orEmpty()
      return " data-reference-$theme=\"${WebEscaping.htmlEscape(raster)}\"" +
        " data-reference-detail-$theme=\"${WebEscaping.htmlEscape(detail)}\"" +
        match
    }

    val shownCards = cards.filter { card ->
      listOfNotNull(card.light, card.dark, card.neutral).any { p ->
        hasSvgFor(p.id) || hasRemoteComposeFor(p.id) || referencesFor(p.id).isNotEmpty()
      }
    }
    // Every id that has a row of its own. A design reference names one exact state/props mapping,
    // so that variant is deliberately kept OUT of the fold above and gets a row — which means it
    // must not also ride along as an alias on its siblings' rows. It used to: `previewIdsByCard`
    // is keyed state- and props-invariantly, so all fourteen published `button-elevated` rows
    // carried the same twenty-eight ids, and filtering the page by one variant's id matched the
    // lot. The alias exists for ids with NO row; an id that has one selects itself.
    val rowPreviewIds =
      shownCards
        .flatMap { card -> listOfNotNull(card.light, card.dark, card.neutral).map { it.id } }
        .toSet()
    // The genuinely folded-out siblings still have to select something, so each is aliased onto
    // exactly ONE row — the first row of its comparison card — rather than onto all of them.
    val aliasesClaimed = mutableSetOf<String>()
    // The **Bugs** column stands or falls with the catalog's published issue index: with no index
    // there is nothing to join and the column would be a row of bare "+ file" links, which is a
    // route every row already has through its reference. A catalog that publishes one gets the
    // column on every row, INCLUDING the rows with nothing filed — an unfiled bad score is exactly
    // what a reader is scanning this wall for, and a blank cell there would read as "no route from
    // here" rather than as "nobody has reported this yet".
    val showBugs = parityIssues.isNotEmpty()
    // **Served worst-first**, on the numbers the delivery branch already measured.
    //
    // The wall's order is the wall's whole argument — the rows that are wrong have to be the ones
    // on screen without scrolling — and until now that order only existed AFTER the browser had
    // decoded and scored two rasters per row, which on a catalog the size of m3-catalog is tens of
    // seconds of the page sitting in catalog order looking like nothing is wrong. The published
    // scores answer the same question at serve time, so the document leaves here in the order it
    // will settle into and the client's measurement becomes a refinement rather than the first
    // draft (issue #4624).
    //
    // Only the reference lane: `svg` and `rc` publish no score of their own, and re-ordering their
    // rows by a number about a different comparison would be worse than catalog order. A row with
    // no published score sorts AFTER the scored ones rather than leading like an unmeasurable row
    // does — "not scored yet" is not a finding, and a catalog published before the producer existed
    // would otherwise serve its whole table under a banner of rows claiming to be the worst.
    val orderedCards =
      if (defaultFormat != "reference") shownCards
      else shownCards.sortedBy { servedReferencePercent(it) ?: Double.MAX_VALUE }
    val rows =
      orderedCards.joinToString("\n") { card ->
        val variants = listOfNotNull(card.light, card.dark, card.neutral)
        val current = if (darkFirst) card.dark ?: card.default else card.default
        val component = componentKey(current)
        // The variant, spelled out. Every row of a component printed the bare component name, so
        // a component with a reference per state published a run of identically-labelled rows in
        // no stated order — fourteen rows reading `button-elevated`, each showing a different
        // button, which looks like the pairing is wrong rather than like the label is missing.
        val variant = compareVariantLabel(current)
        val label = if (variant.isEmpty()) component else "$component — $variant"
        val viewer = "$basePath/p/${WebEscaping.urlEncodeSegment(current.id)}$q"
        val cardKey = comparisonCardKey(current)
        val folded =
          if (aliasesClaimed.add(cardKey))
            previewIdsByCard[cardKey].orEmpty().filterNot { it in rowPreviewIds }
          else emptyList()
        val ids = (variants.map { it.id } + folded).distinct().joinToString(" ")
        // Every issue the catalog's index names against any of this row's previews, or against the
        // component itself. Matched on the row's WHOLE id set rather than on `current` alone: an
        // issue is filed from one theme's page and the row shows both, so joining on the served
        // variant would hide a dark-lane report from the reader looking at the light lane.
        val bugs = issuesForRow(parityIssues, variants.map { it.id } + folded, current.componentId)
        // Where "+ file" lands at rest: the focused Reference / Diff / Actual page for the pair
        // this row is SERVED showing, which files a report naming that exact preview and reference.
        // `<cp-compare-wall>` re-points it at the pair it resolves whenever the lane or theme
        // changes; the viewer's own report is the fallback, and the whole of it on a row with no
        // reference to focus on.
        val servedDetail =
          listOfNotNull(if (darkFirst) card.dark else card.light, card.neutral)
            .firstNotNullOfOrNull { preview ->
              referencesFor(preview.id).firstOrNull()?.let { detailHref(preview, it) }
            }
        val bugCell =
          if (showBugs) compareBugsCellHtml(bugs, servedDetail, "$viewer#cp-report") else ""
        // The issue numbers join the haystack, so `#4624` narrows the wall to the rows a report
        // names — the reverse of the join above, and the way back from an issue to the pictures it
        // is about.
        val hay =
          (listOf(label, ids) + bugs.map { "#${it.number}" })
            .filter { it.isNotEmpty() }
            .joinToString(" ")
            .lowercase()
        val pngAttrs =
          attrs("png", "light", card.light) { true } +
            attrs("png", "dark", card.dark) { true } +
            attrs("png", "neutral", card.neutral) { true }
        val svgAttrs =
          attrs("svg", "light", card.light, hasSvgFor) +
            attrs("svg", "dark", card.dark, hasSvgFor) +
            attrs("svg", "neutral", card.neutral, hasSvgFor)
        val rcAttrs =
          attrs("rc", "light", card.light, hasRemoteComposeFor) +
            attrs("rc", "dark", card.dark, hasRemoteComposeFor) +
            attrs("rc", "neutral", card.neutral, hasRemoteComposeFor)
        val referenceAttrs =
          referenceAttrs("light", card.light) +
            referenceAttrs("dark", card.dark) +
            referenceAttrs("neutral", card.neutral)
        // The ground each variant declares for ITSELF, where it declares one. Only the preview's
        // own rungs — the catalog default is the wall's `data-default-theme` and applying it here
        // too would make every row claim to have declared something. Without this the wall could
        // only choose between "the variant is named dark" and "the catalog is dark-first", so a
        // neutral pairing whose preview asks for a light ground inside a dark-first catalog had no
        // way to say so and landed on the dark sheet.
        val declaredBgAttrs =
          listOf("light" to card.light, "dark" to card.dark, "neutral" to card.neutral)
            .mapNotNull { (variant, preview) ->
              preview
                ?.let { declaredBackdropTheme(it) }
                ?.let { " data-declared-bg-$variant=\"$it\"" }
            }
            .joinToString("")
        """
          <tr class="cp-compare-row" data-label="${WebEscaping.htmlEscape(label)}"
            data-hay="${WebEscaping.htmlEscape(hay)}" data-preview-ids="${WebEscaping.htmlEscape(ids)}"$pngAttrs$svgAttrs$rcAttrs$referenceAttrs$declaredBgAttrs>
            <th scope="row"><a href="$viewer">${WebEscaping.htmlEscape(component)}${
            if (variant.isEmpty()) ""
            else "<span class=\"cp-compare-variant\">${WebEscaping.htmlEscape(variant)}</span>"
          }</a></th>
            $pictureCells
            <td class="cp-compare-score">waiting…</td>$bugCell
          </tr>
          """
          .trimIndent()
      }

    val formatControls = buildString {
      if (hasSvg)
        append(
          "<button type=\"button\" class=\"cp-theme-btn\" data-compare-format=\"svg\" " +
            "aria-pressed=\"${defaultFormat == "svg"}\">PNG ↔ SVG</button>"
        )
      if (hasRc)
        append(
          "<button type=\"button\" class=\"cp-theme-btn\" data-compare-format=\"rc\" " +
            "aria-pressed=\"${defaultFormat == "rc"}\">" +
            (if (rcCompare != null) "Remote Compose players" else "PNG ↔ Remote Compose") +
            "</button>"
        )
      if (hasReference)
        append(
          "<button type=\"button\" class=\"cp-theme-btn\" data-compare-format=\"reference\" " +
            // Named in the order the columns stand: the spec leads this lane, so the button that
            // enters it does too. Every other lane keeps the render first and says "PNG ↔ …".
            "aria-pressed=\"${defaultFormat == "reference"}\">" +
            "${WebEscaping.htmlEscape(referenceToolLabel)} ↔ PNG</button>"
        )
    }
    val themeControls =
      if (cards.any { it.swappable })
        // Wrapped so the lane wall can hide it wholesale: those renders were rasterised offline at
        // the run's own theme, so offering a theme switch over them would be a lie.
        """
        <span class="cp-compare-theme-controls">
          <span class="cp-compare-control-label">Theme</span>
          <span class="cp-theme" role="group" aria-label="Comparison theme">
            <button type="button" class="cp-theme-btn" data-compare-theme="light" aria-pressed="${!darkFirst}">Light</button>
            <button type="button" class="cp-theme-btn" data-compare-theme="dark" aria-pressed="$darkFirst">Dark</button>
          </span>
        </span>
        """
          .trimIndent()
      else ""

    // Named for the lane it is actually showing, not the constant `SVG` this used to be: with the
    // columns free to swap, a header over the wrong picture does not merely omit a fact, it states
    // the pair backwards. `compare/columns.ts` keeps the client's relabelling in step.
    val targetHead =
      when (defaultFormat) {
        "reference" -> referenceToolLabel
        "rc" -> "Remote Compose"
        else -> "SVG"
      }
    val renderHeadHtml = "<th class=\"cp-compare-render-head\">Rendered PNG</th>"
    val targetHeadHtml =
      "<th class=\"cp-compare-target-head\">${WebEscaping.htmlEscape(targetHead)}</th>"
    val diffHeadHtml = "<th class=\"cp-compare-diff-head\">Diff</th>"
    val pictureHeads =
      if (specLeadsColumns) targetHeadHtml + diffHeadHtml + renderHeadHtml
      else renderHeadHtml + diffHeadHtml + targetHeadHtml
    val empty =
      if (rows.isEmpty())
        "<p class=\"cp-empty\">No previews in this session carry a comparable format.</p>"
      else
        """
        <div class="cp-compare-table-wrap">
          <table class="cp-compare-table">
            <thead><tr><th>Preview</th>$pictureHeads<th>Match</th>${
          if (showBugs) "<th class=\"cp-compare-bugs-head\">Bugs</th>" else ""
        }</tr></thead>
            <tbody>$rows</tbody>
          </table>
        </div>
        <p id="cp-compare-empty" class="cp-empty" hidden>No comparisons match this filter.</p>
        """
          .trimIndent()
    val rcLanes = rcCompare?.let {
      rcLanesSection(it, previews, previewIdsByCard, token, linkSessionId, basePath, isPublic)
    }
    // Reuses the viewer's provenance row wholesale, and not only for the styling: `.cp-report`'s
    // panel is anchored to `.cp-preview-links` rather than to its own toggle, which is what keeps
    // it on screen at every width (see the comment block in `serve.css`).
    val reportRow =
      reportIssueHtml(reportIssue)
        .takeIf { it.isNotBlank() }
        ?.let {
          "\n          <div class=\"cp-preview-links cp-compare-links\">$it\n          </div>"
        } ?: ""
    val rootAttrs =
      "data-default-format=\"$defaultFormat\" data-default-theme=\"${if (darkFirst) "dark" else "light"}\" " +
        "data-theme-key=\"${WebEscaping.htmlEscape(themeStorageKey(sessionId, basePath))}\" " +
        "data-has-svg=\"${if (hasSvg) "1" else "0"}\" data-has-rc=\"${if (hasRc) "1" else "0"}\" " +
        "data-has-reference=\"${if (hasReference) "1" else "0"}\" " +
        "data-reference-label=\"${WebEscaping.htmlEscape(referenceToolLabel)}\"" +
        // The Bugs column is a fourth thing competing for the reference lane's row width, so
        // `serve.css` has to know it is there to pay for it out of the panels rather than out of
        // `Match`.
        (if (showBugs) " data-has-bugs=\"1\"" else "") +
        (if (rcLanes != null) " data-rc-lanes=\"1\"" else "")

    return document(
      changelogHref = changelogHref,
      title = "$heading — format comparison",
      unfurlTitle = "$heading format comparison",
      unfurlDescription = "Compare rendered PNG, SVG, and Remote Compose output for $heading",
      unfurl = unfurl,
      version = version,
      navSuffix = navSuffix,
      headerBreadcrumb = crumbHtml("$basePath/$q", heading, "Compare formats"),
      themeCss = themeCss,
      // The bar names the catalog you are in, from the same heading the page shows.
      siteName = heading,
      themeStorageKey = themeStorageKey(sessionId, basePath),
      // The PNG ↔ Remote Compose comparison plays the document in a canvas on this page and
      // *scores*
      // the result, so an unregistered typeface here doesn't just look wrong — it lands in the
      // reported fidelity number.
      rcFonts = hasRc,
      body =
        """
        <div id="cp-compare" $rootAttrs>
          <h1 class="cp-head">Format comparison${compactTrustBadge(trust)}</h1>
          <p class="cp-sub"><span class="cp-sub-formats">PNG, SVG and Remote Compose fidelity · scores measure the drawn content on a fixed backdrop</span>${
          if (rcLanes != null)
            "<span class=\"cp-sub-rc\">Every Remote Compose player side by side · pixel diffs from the published parity run</span>"
          else ""
        }</p>
          <div class="cp-compare-controls">
            <span class="cp-theme" role="group" aria-label="Comparison format">$formatControls</span>
            $themeControls
          </div>
          <div class="cp-searchbar cp-compare-searchbar">
            <input id="cp-compare-search" class="cp-search" type="search" placeholder="Filter comparisons…" aria-label="Filter comparisons">
            <span id="cp-compare-count" class="cp-count" role="status"></span>
          </div>$reportRow
          <div id="cp-compare-formats">$empty</div>
          ${rcLanes.orEmpty()}
        </div>
        <!-- The components bundle is UNCONDITIONAL here now: `<cp-compare-wall>` is the wall
             itself, not just the RC lane's player, so a catalog with no Remote Compose would
             otherwise get a page whose element never upgrades. The tag comes after
             `format-compare.js` for tidiness only — the element reads
             `window.ComposePreviewCompare` when it scores rather than when it upgrades, so no
             script order can silence it. -->
        ${scriptTag("serve-components.js")}
        ${scriptTag("format-compare.js")}
        <cp-compare-wall></cp-compare-wall>
        """
          .trimIndent(),
    )
  }

  /**
   * The **Remote Compose players** view: every player's published render of every `ir/<id>.rc`
   * document, one column per player, with the baked capture (the offline Robolectric/Skiko render,
   * and the reference the offline run scored everything against) first.
   *
   * Nothing is diffed until asked. Picking a column as the reference gives every *other* column a
   * pixel diff and a mismatch chip — which is the point of the view: "how far is cmp-wasm from
   * cmp-jvm?" is a question no build-time artifact answers, because the offline run only ever
   * diffed each player against the baked render.
   *
   * The whole thing replays what the delivery branch already published, so the page costs a few
   * `<img>` loads rather than a `.rc` fetch plus a canvas render per preview — and it shows five
   * players where the in-browser lane could only ever show the one that runs in a browser. The
   * mirror of the published `rc-compare.html` (`render-rc-compare-html.mjs`), which is built from
   * the same data.
   */
  private fun rcLanesSection(
    manifest: RcCompareManifest,
    previews: List<ServePreview>,
    previewIdsByCard: Map<String, List<String>>,
    token: String,
    /** The id links must carry as `?session=`, or null when the URL already implies it. */
    linkSessionId: String?,
    basePath: String,
    isPublic: Boolean,
  ): String? {
    if (manifest.lanes.isEmpty() || manifest.rows.isEmpty()) return null
    val q = querySuffix(linkQuery(token, linkSessionId, basePath, isPublic))
    val previewsById = previews.associateBy { it.id }
    fun asset(name: String): String =
      if (name.isEmpty()) "" else "$basePath/${ServeRcCompare.DIRECTORY}/$name$q"

    // Worst-match first on the worst-scoring player, so a preview only one player gets wrong still
    // sorts to the top; rows nothing scored sink, then alphabetical. Mirrors the published page.
    fun worst(row: RcCompareRow): Double? =
      if (row.referenceBlank) null
      else row.lanes.values.filter { it.rendered }.mapNotNull { it.mismatchPct }.maxOrNull()

    val labelled =
      manifest.rows.map { row ->
        val preview = previewsById[row.previewId]
        row to (preview?.let(::componentKey) ?: row.previewId)
      }
    val ordered =
      labelled.sortedWith(
        compareBy<Pair<RcCompareRow, String>>(
          { worst(it.first) == null },
          { -(worst(it.first) ?: 0.0) },
          { it.second },
        )
      )

    val head =
      "<tr><th>Preview</th>" +
        manifest.lanes.joinToString("") { "<th>${WebEscaping.htmlEscape(it.label)}</th>" } +
        "</tr>"

    val rows =
      ordered.withIndex().joinToString("\n") { (index, entry) ->
        val (row, label) = entry
        val preview = previewsById[row.previewId]
        val ids =
          preview
            ?.let { previewIdsByCard[comparisonCardKey(it)] }
            .orEmpty()
            .ifEmpty { listOf(row.previewId) }
        val hay = (label + " " + ids.joinToString(" ")).lowercase()
        val viewer = "$basePath/p/${WebEscaping.urlEncodeSegment(row.previewId)}$q"
        val dims = if (row.width > 0 && row.height > 0) "${row.width}×${row.height}" else ""
        val cells =
          manifest.lanes.joinToString("") { lane ->
            val cell = row.lanes[lane.id] ?: RcCompareCell()
            val body =
              if (cell.render.isNotEmpty())
                "<img loading=\"lazy\" src=\"${WebEscaping.htmlEscape(asset(cell.render))}\" " +
                  "alt=\"${WebEscaping.htmlEscape(label)} — ${WebEscaping.htmlEscape(lane.label)}\">"
              else
                "<div class=\"cp-rc-missing\">${WebEscaping.htmlEscape(cell.note.ifBlank { "—" })}</div>"
            """
            <td><figure class="cp-rc-cell" data-lane="${WebEscaping.htmlEscape(lane.id)}">
              <figcaption>${WebEscaping.htmlEscape(lane.label)}<span class="cp-rc-refbadge">reference</span></figcaption>
              $body
              <div class="cp-rc-diffslot" hidden></div>
            </figure></td>
            """
              .trimIndent()
          }
        """
        <tr class="cp-rc-row" data-row="$index" data-hay="${WebEscaping.htmlEscape(hay)}"
          data-preview-ids="${WebEscaping.htmlEscape(ids.joinToString(" "))}">
          <th scope="row">
            <a href="$viewer">${WebEscaping.htmlEscape(label)}</a>
            ${if (dims.isNotEmpty()) "<div class=\"cp-rc-dims\">$dims</div>" else ""}
            ${if (row.referenceBlank) "<div class=\"cp-rc-blank\">the baked render is fully transparent — nothing to compare against</div>" else ""}
            <div class="cp-rc-scores" data-scores></div>
          </th>$cells
        </tr>
        """
          .trimIndent()
      }

    val picker =
      "<button type=\"button\" class=\"cp-theme-btn\" data-rc-ref=\"none\" aria-pressed=\"true\">nothing</button>" +
        manifest.lanes.joinToString("") { lane ->
          "<button type=\"button\" class=\"cp-theme-btn\" data-rc-ref=\"${WebEscaping.htmlEscape(lane.id)}\" " +
            "aria-pressed=\"false\">${WebEscaping.htmlEscape(lane.short)}</button>"
        }

    val model =
      ServeRcCompare.ClientModel(
        threshold = manifest.threshold,
        lanes = manifest.lanes,
        rows =
          ordered.map { (row, label) ->
            ServeRcCompare.ClientRow(
              label = label,
              referenceBlank = row.referenceBlank,
              lanes =
                row.lanes.mapValues { (_, cell) ->
                  cell.copy(render = asset(cell.render), diff = asset(cell.diff))
                },
            )
          },
      )

    return """
      <section id="cp-rc-lanes" hidden>
        <p class="cp-sub">Pick a column and every other column grows a pixel diff and a mismatch chip.
          The baked lane replays the build-time <code>pixelmatch</code> diffs; another player diffs in your browser,
          which is how you compare two players directly.</p>
        <div class="cp-compare-controls">
          <span class="cp-compare-control-label">Diff against</span>
          <span class="cp-theme" role="group" aria-label="Diff reference">$picker</span>
          <span id="cp-rc-status" class="cp-rc-status" role="status"></span>
        </div>
        <div class="cp-compare-table-wrap">
          <table class="cp-compare-table cp-rc-table">
            <thead>$head</thead>
            <tbody>
$rows
            </tbody>
          </table>
        </div>
        <p id="cp-rc-empty" class="cp-empty" hidden>No comparisons match this filter.</p>
        <script type="application/json" id="cp-rc-model">${ServeRcCompare.encodeClientModel(model)}</script>
        <!-- Picks the reference, measures the rows and fills in the chips. Emitted LAST in this
             section, immediately after the model it reads: `format-compare.js` calls
             `window.cpRcLanes.filter()` on its very first pass, so the element has to be able to
             set itself up the moment the tag upgrades rather than one parse later. Renders
             nothing; `serve.css` hides the tag. -->
        <cp-rc-lanes></cp-rc-lanes>
      </section>
      """
      .trimIndent()
  }

  /** Focused design handoff view: independent reference, marked diff, and actual Compose output. */
  fun referenceComparisonPage(
    moduleLabel: String,
    preview: ServePreview,
    reference: DesignReference,
    references: List<DesignReference> = listOf(reference),
    token: String,
    sessionId: String? = null,
    basePath: String = "",
    isPublic: Boolean = false,
    trust: String? = null,
    /**
     * The catalog's declared stage surface (`catalog.json`'s `display.surface`), as everywhere
     * else. This page went without one for far too long, which is the whole of
     * yschimke/wear-m3-catalog#56: its three panels fell through to the `.cp-compare-shot`
     * checkerboard, so a dark-first catalog's white-on-transparent sticker was compared against its
     * reference while being nearly invisible in the panel meant to show it.
     */
    declaredSurface: String? = null,
    /**
     * The served catalog's own palette as an inline `:root` override for the chrome's custom
     * properties, built by [ServeThemeCss] from the branch's `tokens.dtcg.json`. Empty ⇒ the page
     * keeps the built-in chrome (a plain module, or a catalog that publishes no tokens).
     */
    themeCss: String = "",
    unfurl: UnfurlMetadata? = null,
    /**
     * Running server version (`SERVE_VERSION`), shown in the minimal footer. Null omits the build
     * span.
     */
    version: String? = null,
    displayTitle: String? = null,
    /**
     * Typography / layout annotations for the reference raster and the rendered frame. Either side
     * may be empty — a producer that annotates only one panel still gets that panel's layers, and a
     * session with no annotations at all renders exactly as before (no toggles, no payload).
     */
    referenceAnnotations: List<DesignAnnotation> = emptyList(),
    actualAnnotations: List<DesignAnnotation> = emptyList(),
    /**
     * The parity run's verdict for this exact (preview, reference) pair, as the catalog published
     * it in `parity/findings.json`. Empty ⇒ the page renders exactly as it did before the manifest
     * existed, which is every catalog whose producer does not write one.
     */
    parityFindings: List<ParityFindingSet> = emptyList(),
    /**
     * Whether the host can project the **derived** layers — typography, theme and layout read off
     * the render's own semantics tree — for this preview, i.e. answer `/render/<id>.annotations`.
     *
     * Separate from [actualAnnotations], which is the *producer-authored* list a bundle publishes
     * in `annotations/index.json`. This page carried only the authored one for as long as it has
     * existed, which is why most catalogs show a redline over the Reference panel and nothing over
     * the render — and why, before this, an element selector on the Actual side would have had
     * nothing to point at on the side that matters.
     */
    derivedAnnotations: Boolean = false,
    /**
     * Whether the catalog **published** typography over this preview's baked frame
     * ([ServeHost.hasPublishedTypographyFor]) — the other lane behind the same Typography layer,
     * and the only one a static bundle has.
     *
     * The viewer has always drawn this distinction ([hasPublishedTypography] there) and the
     * comparison must too, because the two lanes do not overlap where it matters. A static bundle
     * answers `.annotations` from `annotations/index.json` and never re-renders, so it is the one
     * host whose layers and PNG are the same frame by construction — which is exactly the host
     * [annotationsSelectable] is for. Gating this page's mount on [derivedAnnotations] alone put
     * the two behind mutually exclusive predicates: a bundle host has no daemon, so no mount was
     * emitted at all, while every host that got one renders per request and so is not selectable.
     * The intersection was empty and no deployed comparison offered a selectable box.
     *
     * Only the Typography row rides this lane. Theme attributes and Layout boxes are projected from
     * a semantics tree and nothing authors them into a bundle, so they stay gated on
     * [derivedAnnotations] rather than becoming checkboxes with nothing behind them.
     */
    publishedTypography: Boolean = false,
    /**
     * Whether a **tag** selection would describe the frame on screen, and so whether to offer the
     * picker at all. The URL itself is built here rather than passed in, so it goes through the
     * same [linkQuery] rules as every other link on the page — a hand-rolled query builder in the
     * handler read only the request's query parameters and so dropped the credential entirely for a
     * page authorized by header or by an agent's bearer grant, silently hiding the picker on a
     * catalog that publishes a perfectly good index.
     *
     * Null is not "no tags". `ServeHost.tagIndexForPreview` is the *published static* index,
     * measured in CI over the baked render, and both live host wrappers delegate to their baked
     * host — so an override-bearing or pinned frame is a different render than the one those bounds
     * came from. A tag selection persists those bounds into the locator as the acceptance's
     * baseline, so bounds read off another frame survive into a record that later reports an
     * unchanged element as *moved*: a false invalidation with a plausible explanation attached,
     * which is worse than a missing check. A dragged region has no such coupling — it is derived
     * from the displayed pixels, so it describes what the reporter saw by construction — and stays
     * offered either way.
     *
     * The index is a published artifact and is not scoped to the render query, so its URL carries
     * the session keys and nothing else — no overrides (there are none, or this would be false) and
     * no `reference=`.
     */
    tagIndexAvailable: Boolean = false,
    /**
     * Whether clicking one of the derived semantics boxes may **select** it.
     *
     * Separate from [derivedAnnotations], which decides whether the layers are drawn at all.
     * Drawing them over a frame the server rendered for this request is fine — a reading aid a
     * render out of date costs nothing. Recording one is not: `.annotations` is a separate request
     * from the PNG the client already decoded, so on a host that renders per request the two can
     * describe different frames wherever output varies, and a click would persist a region from one
     * of them as the acceptance's authoring-time baseline.
     */
    annotationsSelectable: Boolean = false,
    /**
     * Why the tag picker is absent, when the reason is worth saying out loud. Shown beside the
     * selector rather than left to be guessed at: "this catalog publishes no tag index" and "your
     * overrides mean the index describes a different render" are different problems with different
     * fixes, and a control that simply is not there teaches neither.
     */
    tagSelectionNote: String? = null,
    /**
     * The catalog's published revisions and which one this page is pinned to. This is the page the
     * permalink feature was raised against (issue #3723): a comparison URL names a preview and a
     * reference, both of which are republished, so without a pin it describes whatever the pair
     * happens to be when the link is opened.
     */
    revisions: CatalogRevisions = CatalogRevisions.NONE,
    /**
     * Whether this page is served as a **top-level site** ([ServeSites]) — its catalog rooted on a
     * hostname of its own. The session is then implied by the ORIGIN, exactly as a `/<system>`
     * mount implies it by the path, so same-session links must not repeat it as `?session=`. False
     * (the default) leaves every existing caller's URLs byte-identical.
     */
    sessionInOrigin: Boolean = false,
    /** Normalised render-lane query values that reproduce the compared candidate. */
    overrides: Map<String, String> = emptyMap(),
    /** Prefilled parity report for this exact preview/reference comparison. */
    reportIssue: ReportIssue? = null,
    /**
     * What the browser engine needs to evaluate this catalog's committed acceptances against this
     * comparison, or null to leave the band off the page entirely.
     *
     * Null on every catalog that has accepted nothing, which is most of them — and the band is
     * absent rather than empty, because "no acceptances here" and "nothing has been accepted in
     * this catalog" are the same fact and neither deserves a row saying so.
     */
    knownDifferences: KnownDifferenceScope? = null,
    parityIssues: List<ParityIssue> = emptyList(),
    /**
     * The complete issue index used to resolve acceptance lifecycle state. [parityIssues] remains
     * the comparison-filtered list rendered in the Issues panel; an acceptance can legitimately
     * refer to an issue whose independently published preview/reference locators are stale, so the
     * lifecycle join must not inherit that display filter.
     */
    acceptanceIssues: List<ParityIssue> = parityIssues,
    /**
     * The catalog change feed the footer offers as **Changelog** and the head declares as this
     * page's RSS alternate. Empty when the server runs with the feed lane off. See [siteFooter].
     */
    changelogHref: String = "",
  ): String {
    // The session id links may carry. Null on a rooted site (and for the default session): the
    // URL already says which catalog this is. `sessionId` itself stays intact below — it keys the
    // per-catalog localStorage entries and the dark-first lookup, which a site still needs.
    val linkSessionId = if (sessionInOrigin) null else sessionId
    val overrideQuery =
      overrides.entries
        .sortedBy { it.key }
        .joinToString("&") { (key, value) ->
          "${WebEscaping.urlEncodeSegment(key)}=${WebEscaping.urlEncodeSegment(value)}"
        }
    val linkQuery =
      listOf(linkQuery(token, linkSessionId, basePath, isPublic), overrideQuery)
        .filter { it.isNotEmpty() }
        .joinToString("&")
    val q = querySuffix(linkQuery)
    val navSuffix =
      querySuffix(if (isPublic) "" else "token=" + WebEscaping.urlEncodeSegment(token))
    val heading = catalogHeading(displayTitle, moduleLabel)
    // Both panels take the pin, or neither does. A pinned render scored against the current mock
    // would be a comparison across time rather than between the two sides.
    val assetQuery = withPin(q, revisions.pinned)
    val actual = "$basePath/render/${WebEscaping.urlEncodeSegment(preview.id)}.png$assetQuery"
    val raster = "$basePath/reference/${WebEscaping.urlEncodeSegment(reference.id)}.png$assetQuery"
    // The ground all three panels sit on. Both sides get the SAME one on purpose: the diff panel is
    // only meaningful if the reference and the render were composited onto identical pixels, and
    // showing each on its own preferred stage would put a ground difference into a comparison whose
    // entire job is to isolate the component's difference.
    val backdrop =
      backdropFor(
        preview,
        isDarkFirstSystem(basePath, sessionId, declaredSurface),
        // Both panels already take this override through `assetQuery`; the stage has to take it too
        // or the pixels and their ground describe different renders.
        uiModeOverride = overrides["uiMode"],
      )
    // The SHAPE of that ground. A round device's stage has to stop at the bezel, or the three
    // panels agree with each other about a watch that is square.
    val stageClip = stageClipFor(preview, overrides)
    val stageAttrs =
      backdrop.color?.let { color ->
        // The theme word drives the existing CSS; the exact colour rides along as a custom property
        // so a catalog whose stage is neither of the two literal plates still gets its own ground
        // rather than the nearest of them.
        val clipProperty =
          stageClip?.let { "; --cp-stage-clip: ${WebEscaping.htmlEscape(it)}" } ?: ""
        // The marker attribute AS WELL as the property, because the clip is not the only thing the
        // rules it gates do: they also take the ground off the panel and hand it to the image, so
        // the corners the clip opens up show the page's checkerboard rather than the stage colour.
        // CSS cannot branch on whether a custom property was set, so without a marker every
        // rectangular preview would lose its panel ground to buy a clip it never uses.
        val clipMarker = if (stageClip != null) " data-cp-stage-clip=\"1\"" else ""
        " data-bg-theme=\"${if (backdrop.isDark) "dark" else "light"}\"" +
          clipMarker +
          " style=\"--cp-stage-backdrop: ${WebEscaping.htmlEscape(color.asCssColor())}$clipProperty\""
      } ?: ""
    // One toggle per kind, offered only when some panel actually carries that kind — a control that
    // reveals nothing is worse than no control. The payload rides inline rather than behind a fetch
    // so the layers are there on first paint, like the rest of this page's data.
    val annotated = referenceAnnotations + actualAnnotations
    val annotationControls =
      if (annotated.isEmpty()) ""
      else {
        // Every kind [AnnotationKind.KNOWN] admits needs an entry here. A kind that loads and gets
        // a box built for it but has no toggle is drawn into a layer CSS keeps permanently hidden —
        // which is what happened to THEME: `ServeAnnotationStore` accepts it, `format-compare.js`
        // builds its box and legend row, and nothing could ever reveal either.
        val toggles =
          listOf(
              AnnotationKind.LAYOUT to "Layout",
              AnnotationKind.TYPOGRAPHY to "Typography",
              AnnotationKind.THEME to "Theme",
            )
            .filter { (kind, _) -> annotated.any { it.kind == kind } }
            .joinToString("\n") { (kind, label) ->
              "<label class=\"cp-annotation-toggle\"><input type=\"checkbox\" " +
                "data-cp-annotation-kind=\"$kind\"> ${WebEscaping.htmlEscape(label)}</label>"
            }
        """
        <div class="cp-annotation-controls" role="group" aria-label="Annotation layers">
          <span class="cp-compare-control-label">Annotations</span>
          $toggles
        </div>
        <script type="application/json" id="cp-annotations">${
          encodeAnnotationPayload(
            AnnotationPayload(reference = referenceAnnotations, actual = actualAnnotations)
          )
        }</script>
        """
          .trimIndent()
      }
    // The DERIVED layers, mounted over the Actual panel by the same `<cp-inspect-layers>` the
    // viewer
    // uses (see `inspect/host.ts`). Deliberately a second, separately-labelled control group rather
    // than more checkboxes in the one above: those toggle the redline a producer AUTHORED and that
    // this page inlines for both panels, these toggle what the render's own semantics tree SAYS,
    // and only the render has one. Folding them together would offer a Typography toggle that means
    // two different things depending on which panel you looked at.
    // Typography rides either lane; Theme and Layout only the semantics one. Same rule as the
    // viewer's Inspect group, and for the same reason: a row whose fetch can only come back empty
    // is a dead control.
    val derivedLayers = buildList {
      if (derivedAnnotations || publishedTypography) add("typography" to "Typography")
      if (derivedAnnotations) {
        add("theme" to "Theme")
        add("layout" to "Layout")
      }
    }
    val derivedControls =
      if (derivedLayers.isEmpty()) ""
      else {
        val toggles =
          derivedLayers.joinToString("\n") { (kind, label) ->
            "<label class=\"cp-annotation-toggle\"><input type=\"checkbox\" " +
              "class=\"cp-render-inspect\" data-cp-inspect=\"$kind\"> " +
              WebEscaping.htmlEscape(label) +
              "</label>"
          }
        """
        <div class="cp-annotation-controls cp-render-inspect-controls" role="group"
             aria-label="Render semantics layers">
          <span class="cp-compare-control-label">Render semantics</span>
          $toggles
        </div>
        <div class="cp-inspect-legend cp-render-inspect-legend" id="cp-render-inspect-legend"
             role="region" aria-label="Render semantics legend" hidden></div>
        <cp-inspect-layers
          data-cp-host="#cp-compare-actual"
          data-cp-layer="#cp-render-inspect-layer"
          data-cp-legend="#cp-render-inspect-legend"
          data-cp-toggles=".cp-render-inspect"
${if (annotationsSelectable) "          data-cp-selectable=\"1\"\n" else ""}          data-cp-base="${WebEscaping.htmlEscape(basePath)}"></cp-inspect-layers>
        """
          .trimIndent()
      }
    // The element selector. A dragged region needs nothing from the server — it is read off the
    // displayed pixels — so the control is offered on every focused comparison; the tag picker only
    // appears where the index describes the frame being shown. See [tagIndexUrl].
    val tagIndexUrl =
      if (!tagIndexAvailable) null
      else
        "$basePath/tags/${WebEscaping.urlEncodeSegment(preview.id)}" +
          querySuffix(linkQuery(token, linkSessionId, basePath, isPublic))
    val tagAttr = tagIndexUrl?.let { " data-cp-tags=\"${WebEscaping.htmlEscape(it)}\"" }.orEmpty()
    val tagNote =
      tagSelectionNote
        ?.takeIf { it.isNotBlank() }
        ?.let { "<p class=\"cp-selection-note\">${WebEscaping.htmlEscape(it)}</p>" }
        .orEmpty()
    val selectionControls =
      if (reportIssue == null) ""
      else
        """
        <div class="cp-selection-controls" id="cp-element-selection" role="group"
             aria-label="Report a single element"$tagAttr>
          <span class="cp-compare-control-label">Report</span>
          <select class="cp-selection-tag" aria-label="Tagged element" hidden>
            <option value="">the whole render</option>
          </select>
          <button type="button" class="cp-selection-drag">Drag a region…</button>
          <button type="button" class="cp-selection-clear" hidden>Clear</button>
          <p class="cp-selection-state" role="status">Reporting the whole render.</p>
          $tagNote
        </div>
        <cp-element-selection></cp-element-selection>
        """
          .trimIndent()
    // The acceptance band and its payload. Both are absent together on a catalog that has accepted
    // nothing — an empty band would say "0 accepted" on every comparison in every catalog, which is
    // noise rather than information.
    //
    // The band renders empty and `hidden`: the numbers are the browser's, computed from the same
    // rasters the diff uses, and the server has no scorer to write them with. What the server does
    // decide is whether the engine may run at all, which is the payload's presence.
    // The acceptance band and its payload. Both are absent together on a catalog that has accepted
    // nothing — an empty band would say "0 accepted" on every comparison in every catalog, which is
    // noise rather than information, and the page would also carry the engine's bundle to evaluate
    // nothing.
    //
    // The band renders empty and `hidden`: the numbers are the browser's, computed from the same
    // rasters the diff uses, and the server has no scorer to write them with. What the server
    // decides is whether the engine may run at all, which is the payload's presence.
    //
    // Both strings carry their own leading newline and sit at column zero, like every other
    // interpolated block on this page. That is not cosmetic: `trimIndent()` runs *after*
    // interpolation, so a block indented to match the template would drag the whole page's
    // indentation with it — and an empty one on its own template line would leave a blank line on
    // every catalog that has accepted nothing, which is exactly the golden drift this shape avoids.
    val acceptanceBand =
      if (knownDifferences == null) ""
      else "\n" + """<div class="cp-acceptance" id="cp-acceptance" role="status" hidden></div>"""
    val acceptanceContext = knownDifferences?.let { scope ->
      encodeKnownDifferenceContext(
        KnownDifferenceContext(
          // The document and the artifacts are published catalog files, not render output, so
          // they take the session keys and nothing else — no overrides, no `reference=`. The pin
          // is deliberately absent too: a historical revision's acceptances are not published, and
          // quoting today's against yesterday's pixels would gate a comparison nobody accepted
          // anything for.
          documentUrl =
            "$basePath/parity/known-differences.json" +
              querySuffix(linkQuery(token, linkSessionId, basePath, isPublic)),
          artifactBase = "$basePath/parity/known-differences/",
          artifactQuery = querySuffix(linkQuery(token, linkSessionId, basePath, isPublic)),
          // The two panels' own URLs, so the engine decodes the very frames the diff drew rather
          // than re-deriving a pair from the ids.
          referenceUrl = raster,
          candidateUrl = actual,
          scope = scope,
          issues =
            acceptanceIssues
              .map { issue ->
                KnownDifferenceIssue(
                  repository = issue.repository,
                  number = issue.number,
                  state = issue.state,
                )
              }
              .distinctBy { Triple(it.repository, it.number, it.state) },
        )
      )
    }
    val acceptanceScript =
      if (acceptanceContext == null) ""
      else
        "\n" +
          """<script type="application/json" id="cp-known-differences">$acceptanceContext</script>
${scriptTag("known-differences.js")}
<cp-acceptance></cp-acceptance>"""
    val source = WebEscaping.htmlEscape(reference.source.provider)
    val revision =
      reference.source.revision
        ?.takeIf { it.isNotBlank() }
        ?.let { " · revision ${WebEscaping.htmlEscape(it)}" }
        .orEmpty()
    val referenceChoices = (references + reference).distinctBy { it.id }
    // This page at a given pin (null ⇒ the live catalog), keeping the reference it is showing. Both
    // the revision control and the sibling-reference picker below build their links through it, so
    // moving between revisions and moving between references never drop each other.
    val pageHref: (String?, String) -> String = { pin, referenceId ->
      val query =
        listOfNotNull(
            linkQuery.takeIf { it.isNotEmpty() },
            "reference=${WebEscaping.urlEncodeSegment(referenceId)}",
          )
          .joinToString("&")
      withPin("$basePath/compare/${WebEscaping.urlEncodeSegment(preview.id)}?$query", pin)
    }
    val revisionsBlock = revisionsHtml(revisions) { pin -> pageHref(pin, reference.id) }
    val issueRows = parityIssueRowsHtml(parityIssues)
    val referencePicker =
      if (referenceChoices.size <= 1) ""
      else {
        val links =
          referenceChoices.joinToString("\n") { choice ->
            val href = WebEscaping.htmlEscape(pageHref(revisions.pinned, choice.id))
            val current = if (choice.id == reference.id) " aria-current=\"page\"" else ""
            "<a class=\"cp-reference-choice\" href=\"$href\"$current>${WebEscaping.htmlEscape(choice.label)}</a>"
          }
        """
        <nav class="cp-reference-picker" aria-label="Design references">
          <span>Design references</span>
          $links
        </nav>
        """
          .trimIndent()
      }
    val report = reportIssueHtml(reportIssue)
    val parityVerdict = parityVerdictHtml(parityFindings)
    return document(
      changelogHref = changelogHref,
      title = "${reference.label} — design comparison",
      unfurlTitle = "$heading design comparison",
      unfurlDescription = "Reference, diff, and Compose output for ${preview.id}",
      unfurl = unfurl,
      version = version,
      navSuffix = navSuffix,
      headerBreadcrumb = crumbHtml("$basePath/compare$q", heading, "Design comparison"),
      themeCss = themeCss,
      // The bar names the catalog you are in, from the same heading the page shows.
      siteName = heading,
      body =
        """
        <div id="cp-reference-compare" data-reference="$raster" data-actual="$actual"$stageAttrs>
          <h1 class="cp-head cp-catalog-head">${WebEscaping.htmlEscape(reference.label)}${compactTrustBadge(trust)}</h1>
          <p class="cp-sub">${WebEscaping.htmlEscape(previewDisplayName(preview))} · ${WebEscaping.htmlEscape(preview.id)}</p>
          $revisionsBlock
          $referencePicker$issueRows
          <div class="cp-reference-meta"><strong>Source:</strong> $source$revision</div>
          <div class="cp-reference-grid">
            <section><h2>Reference</h2><div class="cp-compare-shot" data-cp-annotated="reference"><img src="$raster" alt="Design reference"></div></section>
            <section><h2>Diff</h2><div class="cp-compare-shot"><canvas class="cp-reference-diff" aria-label="Highlighted pixel difference"></canvas></div></section>
            <section><h2>Actual</h2><div class="cp-compare-shot" data-cp-annotated="actual" id="cp-compare-actual" data-preview-id="${WebEscaping.htmlEscape(preview.id)}"><img src="$actual" alt="Actual Compose preview">${
              if (derivedLayers.isNotEmpty())
                "<div class=\"cp-inspect-layer\" id=\"cp-render-inspect-layer\"></div>"
              else ""
            }<div class="cp-selection-layer" id="cp-selection-layer" hidden></div></div></section>
          </div>
          $annotationControls
          $derivedControls
          <p class="cp-reference-result" role="status">comparing…</p>$acceptanceBand$parityVerdict
          $selectionControls$report
          <label class="cp-overlay-control">Overlay <input class="cp-overlay-range" type="range" min="0" max="100" value="50"><span>50%</span></label>
          <div class="cp-reference-overlay"><img src="$raster" alt=""><img src="$actual" alt=""></div>
        </div>
        <!-- `<cp-reference-compare>` owns everything on this page: the diff, the overlay slider and
             the annotation redline. `format-compare.js` is still here for the comparison
             primitives it publishes on `window.ComposePreviewCompare`, and the element reads that
             handle when it scores rather than when it upgrades, so the two tags may be in either
             order. -->
        ${scriptTag("serve-components.js")}
        ${scriptTag("format-compare.js")}
        <cp-reference-compare></cp-reference-compare>$acceptanceScript
        """
          .trimIndent(),
    )
  }

  /**
   * The catalog's **Pages** index: one card per published design page.
   *
   * Only rendered when the catalog published at least one, so an ordinary catalog never grows an
   * empty tab. Each card leads with the design's own drawing and states the coverage number the
   * whole surface exists to surface — how many of the sheet's components this catalog implements.
   */
  fun designPagesIndexPage(
    moduleLabel: String,
    pages: List<DesignPage>,
    token: String,
    sessionId: String? = null,
    basePath: String = "",
    isPublic: Boolean = false,
    trust: String? = null,
    themeCss: String = "",
    unfurl: UnfurlMetadata? = null,
    version: String? = null,
    displayTitle: String? = null,
    /**
     * Whether this page is served as a **top-level site** ([ServeSites]) — its catalog rooted on a
     * hostname of its own. The session is then implied by the ORIGIN, exactly as a `/<system>`
     * mount implies it by the path, so same-session links must not repeat it as `?session=`. False
     * (the default) leaves every existing caller's URLs byte-identical.
     */
    sessionInOrigin: Boolean = false,
    /**
     * The catalog change feed the footer offers as **Changelog** and the head declares as this
     * page's RSS alternate. Empty when the server runs with the feed lane off. See [siteFooter].
     */
    changelogHref: String = "",
  ): String {
    // The session id links may carry. Null on a rooted site (and for the default session): the
    // URL already says which catalog this is. `sessionId` itself stays intact below — it keys the
    // per-catalog localStorage entries and the dark-first lookup, which a site still needs.
    val linkSessionId = if (sessionInOrigin) null else sessionId
    val q = querySuffix(linkQuery(token, linkSessionId, basePath, isPublic))
    val navSuffix =
      querySuffix(if (isPublic) "" else "token=" + WebEscaping.urlEncodeSegment(token))
    val heading = catalogHeading(displayTitle, moduleLabel)
    val cards =
      pages.joinToString("\n") { page ->
        val id = WebEscaping.urlEncodeSegment(page.id)
        // Counted against what a catalog could actually implement, not against every node on the
        // sheet: a private component and a variant-set container are furniture, and counting them
        // reports a complete family as one short. See `DesignPage.coverageGaps`.
        val linked = page.linked.size
        // A sheet that is not a component inventory — the kit's icon page — has no fraction to
        // state, and stating `0 of 499` was the loudest wrong number on this index. Say what the
        // sheet is instead; the card still opens it.
        val count =
          if (!page.inventory) "${page.nodes.size} nodes · not a component inventory"
          else "$linked of ${page.coverageTotal} components implemented"
        """
        <a class="cp-page-card" href="$basePath/pages/$id$q">
          <img loading="lazy" alt="" src="$basePath/pages/$id.svg$q">
          <strong>${WebEscaping.htmlEscape(page.name)}</strong>
          <span class="cp-page-count">${WebEscaping.htmlEscape(count)}</span>
        </a>
        """
          .trimIndent()
      }
    return document(
      changelogHref = changelogHref,
      title = "$heading — pages",
      unfurlTitle = "$heading pages",
      unfurlDescription = "Pages of the design file, with each component linked back to its code",
      unfurl = unfurl,
      version = version,
      navSuffix = navSuffix,
      headerBreadcrumb = crumbHtml("$basePath/$q", heading, "Pages"),
      themeCss = themeCss,
      // The bar names the catalog you are in, from the same heading the page shows.
      siteName = heading,
      body =
        """
        <h1 class="cp-head cp-catalog-head">Pages${compactTrustBadge(trust)}</h1>
        <p class="cp-sub">Whole pages of the design file, with each component on them linked back
        to the code that implements it.</p>
        <div class="cp-page-cards">
        $cards
        </div>
        """
          .trimIndent(),
    )
  }

  /**
   * The **motion browser**: every recorded capture this catalog publishes, on one page.
   *
   * ### Why this is a page of its own
   *
   * A capture is per-preview surface — the viewer's Motion lane — and that is the right home for
   * *reading one*. It is the wrong home for the question this page answers, which is a catalog-wide
   * one: **does this design system move consistently?** Two containers that morph on the same
   * spatial spring and a third that cross-fades is a system bug, and it is invisible from three
   * separate component pages, each of which shows its own recording in isolation and says nothing
   * about its neighbours. Putting the recordings side by side is the entire feature; a grid is what
   * makes the odd one out obvious at a glance.
   *
   * It is also the only view that answers "what has motion at all". Captures are rare —
   * [ServeMotion] exists precisely because most components publish only a still — so today a reader
   * finds them by opening components one at a time and noticing a chip. That is not discovery, it
   * is luck.
   *
   * ### Nothing plays until it is asked to
   *
   * Same posture as the viewer's lane, for the same reason: motion is the answer to a question most
   * readers are not asking, and a page that starts thirty recordings at once is a page nobody can
   * read. Each card opens on its component's **still** — the baked pixels, the same image the grid
   * shows — and swaps to the capture only when someone presses it or presses **Play all**. That
   * makes `prefers-reduced-motion` a non-question here: there is no autoplay to suppress. The
   * per-card control is a button rather than a hover, so it works on a touch screen and from a
   * keyboard, and its pressed state says which cards are running.
   *
   * ### Grouped by component, one card per distinct recording
   *
   * A capture is declared on a component but published on every *render* of it: the catalog's
   * `variants.json` hangs the same recording off the default, the disabled state, the focus ring,
   * the RTL variant and every breakpoint. Listed one card per render × capture, `compose-m3`'s five
   * moving components filled this page with 320 cards pointing at ten files — the same two APNGs
   * seventy times over under Icon Button Filled, a screen and a half of identical thumbnails before
   * the next component. That is the opposite of the comparison the page exists for.
   *
   * So the page groups by [componentKey] — the same identity the grid folds its state / theme /
   * props / size axes onto — and inside a component keeps one card per *distinct* capture id. What
   * is folded is the repetition, not the captures: a component with two recordings still shows two
   * cards, because "Baseline swaps the shape, Expressive travels between them" is one component and
   * two things to compare, and folding those onto one card would hide the very comparison this page
   * is for. The component is named once, above its cards; each card deep-links to
   * `?mode=motion&motion=<id>` on the render that publishes it, so the viewer opens on that
   * recording rather than on the component's first one.
   *
   * Captures are labelled by [MotionCaptureLabels] — the same split the viewer's picker uses, so a
   * recording is called the same thing in both places — and a caption every recording of one
   * component shares is printed once under the component name rather than under each card, for the
   * same reason the name itself is: two cards repeating one sentence say it no better than one.
   */
  fun motionIndexPage(
    moduleLabel: String,
    previews: List<ServePreview>,
    token: String,
    sessionId: String? = null,
    basePath: String = "",
    isPublic: Boolean = false,
    trust: String? = null,
    themeCss: String = "",
    unfurl: UnfurlMetadata? = null,
    version: String? = null,
    displayTitle: String? = null,
    /** See [designPagesIndexPage]; a rooted site implies its session by the origin. */
    sessionInOrigin: Boolean = false,
    /**
     * The catalog change feed the footer offers as **Changelog** and the head declares as this
     * page's RSS alternate. Empty when the server runs with the feed lane off. See [siteFooter].
     */
    changelogHref: String = "",
  ): String {
    val linkSessionId = if (sessionInOrigin) null else sessionId
    val query = linkQuery(token, linkSessionId, basePath, isPublic)
    val q = querySuffix(query)
    val navSuffix =
      querySuffix(if (isPublic) "" else "token=" + WebEscaping.urlEncodeSegment(token))
    val heading = catalogHeading(displayTitle, moduleLabel)

    // Authoring order where the catalog published one, so the sections read in the order the
    // landing's tabs do rather than alphabetically by preview id.
    val withMotion =
      previews
        .filter { it.motion.isNotEmpty() }
        .sortedWith(compareBy({ it.catalogOrder ?: Int.MAX_VALUE }, { it.id }))

    /** One capture, and the render that publishes it — its still, its label and its deep link. */
    class Take(val owner: ServePreview, val capture: ServeMotion)

    /**
     * One recording of a component: the gesture, with its per-theme takes on ONE card.
     *
     * A catalog records a gesture once per theme and writes the caption once, so a light and a dark
     * take are not two things to compare — they are one recording, photographed twice. [takes] is
     * keyed by theme (`light` / `dark`) for the toolbar's Theme control to swap between in place,
     * exactly as the landing grid swaps a component's baked light and dark stills; a recording with
     * no theme in its id has a single unkeyed take and the control leaves it alone.
     */
    class Recording(val lead: Take, val takes: Map<String, Take>)

    /**
     * One component's block on this page: what to call it, and every recording it publishes once.
     */
    class MotionComponent(val lead: ServePreview, val recordings: List<Recording>)

    val leadTheme = if (isDarkFirstSystem(basePath, sessionId)) "dark" else "light"

    /**
     * Which of a component's renders speaks for a capture — because after the fold ONE of them
     * supplies the card's still and its deep link, and the wrong one puts a dark thumbnail on a
     * light recording.
     *
     * A capture id is a flattened preview id, so the render that recorded it usually IS in the list
     * under exactly that name; failing that, the render in the capture's own theme lane is the one
     * whose still the recording opens from. Only when neither matches does authored order decide,
     * which is the case for a hand-named capture (`card-filled__press`) that belongs to the whole
     * component rather than to one of its renders.
     */
    fun owner(renders: List<ServePreview>, capture: ServeMotion): ServePreview =
      renders.firstOrNull { it.id == capture.id }
        ?: cardTheme(capture.id)?.let { theme ->
          renders.firstOrNull { (it.theme ?: cardTheme(it.id)) == theme }
        }
        ?: renders.first()

    /**
     * The takes of one component, folded into recordings: grouped by the capture id with its theme
     * token removed ([baseKey], the same key that pairs the grid's light and dark cards).
     *
     * Folding is all-or-nothing per group, for the reason [MotionCaptureLabels] numbers rather than
     * half-names a set: a group folds only when every take in it names a theme and no two name the
     * same one. Anything else — a hand-named capture that happens to share a stem, two takes in one
     * theme — stays a card each, which is the behaviour every catalog had before the Theme control
     * existed.
     */
    fun fold(takes: List<Take>): List<Recording> =
      takes
        .groupBy { baseKey(it.capture.id) }
        .values
        .flatMap { group ->
          val themes = group.map { cardTheme(it.capture.id) }
          if (themes.any { it == null } || themes.distinct().size != themes.size)
            group.map { Recording(it, emptyMap()) }
          else {
            val byTheme = group.associateBy { cardTheme(it.capture.id)!! }
            listOf(Recording(byTheme[leadTheme] ?: group.first(), byTheme))
          }
        }

    // The fold. Every render of a component carries the same manifest entries, so the distinct
    // capture ids ARE the component's takes, however many renders republish them.
    val components =
      withMotion
        .groupBy { componentKey(it) }
        .map { (_, renders) ->
          // The component's own card leads: its default state and default props, in authored
          // order, which is the render the grid draws and the one its name should open.
          val ranked =
            renders.sortedWith(
              compareBy(
                { isNonDefaultState(it) },
                { hasNonDefaultProps(it) },
                // …and in the system's own theme lane, so a light-first catalog is not led by its
                // dark renders purely because `dark` sorts before `light`.
                { (it.theme ?: cardTheme(it.id)) != leadTheme },
                { it.catalogOrder ?: Int.MAX_VALUE },
              )
            )
          val captures = LinkedHashMap<String, ServeMotion>()
          ranked.forEach { render -> render.motion.forEach { captures.putIfAbsent(it.id, it) } }
          MotionComponent(ranked.first(), fold(captures.values.map { Take(owner(ranked, it), it) }))
        }
    val captureCount = components.sumOf { it.recordings.size }
    // Only offered where something can actually be swapped. A catalog that records one theme gets
    // no control, rather than a pair of buttons one of which does nothing.
    val themed = components.any { component -> component.recordings.any { it.takes.size > 1 } }

    /**
     * The viewer, opened on this exact recording — see [ServeMotion] and the viewer's `?motion=`.
     */
    fun viewerHref(preview: ServePreview, capture: ServeMotion): String {
      val parts =
        listOf(query, "mode=motion", "motion=" + WebEscaping.urlEncodeSegment(capture.id)).filter {
          it.isNotEmpty()
        }
      return "$basePath/p/${WebEscaping.urlEncodeSegment(preview.id)}?" + parts.joinToString("&")
    }

    /** The component itself in the viewer, on its Motion lane but on no recording in particular. */
    fun componentHref(preview: ServePreview): String {
      val parts = listOf(query, "mode=motion").filter { it.isNotEmpty() }
      return "$basePath/p/${WebEscaping.urlEncodeSegment(preview.id)}?" + parts.joinToString("&")
    }

    /**
     * One card: one recording, opening on the still of the render that took it.
     *
     * [detailHoisted] says the component printed this caption above the cards already — see
     * [componentHtml] — so repeating it here would be the same sentence twice on one screen. The
     * per-theme `data-motion-*-light` / `-dark` attributes are what the Theme control swaps
     * between: the recording, the still it returns to, the accessible name and the deep link all
     * move together, because a card left pointing at the light take's viewer page after the reader
     * switched to dark sends them somewhere they did not ask to go.
     */
    fun cardHtml(
      component: MotionComponent,
      recording: Recording,
      label: MotionCaptureLabel,
      detailHoisted: Boolean,
    ): String {
      val lead = recording.lead
      val capture = lead.capture
      fun posterOf(take: Take) =
        "$basePath/render/${WebEscaping.urlEncodeSegment(take.owner.id)}.png$q"
      fun srcOf(take: Take) =
        "$basePath/motion/${WebEscaping.urlEncodeSegment(take.capture.id)}" +
          "${take.capture.extension}$q"
      // The component is named above the cards, so the button says which of ITS recordings this is
      // — and, where the same gesture was recorded in both themes, which take is on the stage.
      fun playLabel(theme: String) =
        "Play the ${label.title} recording of ${previewDisplayName(component.lead)}" +
          if (recording.takes.size > 1 && theme.isNotEmpty())
            " (${theme.replaceFirstChar { it.uppercaseChar() }})"
          else ""
      val leadTakeTheme = recording.takes.entries.firstOrNull { it.value === lead }?.key ?: ""
      // The kind is what the annotation recorded, and it is a real distinction to a reader: a
      // scripted gesture proves the component's own input plumbing drives the transition, a
      // self-running animation proves only that the animation exists.
      val kind =
        when (capture.kind) {
          "interaction" -> "Interaction"
          "animation" -> "Animation"
          else -> "Capture"
        }
      // The full caption, printed under the card. Blank for a capture whose annotation declared
      // none — the title is then the kind, and a second line repeating it would say nothing.
      val detail =
        label.detail
          .takeIf { !detailHoisted && it.isNotBlank() && it != label.title }
          ?.let {
            "\n          <span class=\"cp-motion-card-detail\">${WebEscaping.htmlEscape(it)}</span>"
          } ?: ""
      val perTheme =
        recording.takes
          .takeIf { it.size > 1 }
          ?.entries
          ?.joinToString("") { (theme, take) ->
            "\n            data-motion-src-$theme=\"${WebEscaping.htmlEscape(srcOf(take))}\"" +
              "\n            data-motion-poster-$theme=" +
              "\"${WebEscaping.htmlEscape(posterOf(take))}\"" +
              "\n            data-motion-href-$theme=" +
              "\"${WebEscaping.htmlEscape(viewerHref(take.owner, take.capture))}\"" +
              "\n            data-motion-label-$theme=\"${WebEscaping.htmlEscape(playLabel(theme))}\""
          } ?: ""
      val play = WebEscaping.htmlEscape(playLabel(leadTakeTheme))
      return """
        <figure class="cp-motion-card">
          <button type="button" class="cp-motion-card-stage" aria-pressed="false"
            data-motion-src="${WebEscaping.htmlEscape(srcOf(lead))}"
            data-motion-poster="${WebEscaping.htmlEscape(posterOf(lead))}"$perTheme
            title="$play" aria-label="$play">
            <img class="cp-motion-card-img" loading="lazy" alt=""
              src="${WebEscaping.htmlEscape(posterOf(lead))}">
            <span class="cp-motion-card-cue" aria-hidden="true">▶</span>
          </button>
          <figcaption class="cp-motion-card-meta">
          <a class="cp-motion-card-title" href="${WebEscaping.htmlEscape(viewerHref(lead.owner, capture))}">${WebEscaping.htmlEscape(label.title)}</a>
          <span class="cp-motion-card-kind">$kind</span>$detail
          </figcaption>
        </figure>
      """
        .trimIndent()
    }

    // Grouped by the landing's own top-level section, so a reader who knows where a component lives
    // in the catalog finds its recording in the same place here. A catalog with no sections (a
    // plain bundle, an uploaded module) renders one unlabelled run of components, exactly as its
    // grid does — and its component names take the heading level the sections would have used, so
    // the outline never skips one.
    val sections = components.groupBy { it.lead.section }
    val sectioned = sections.keys.any { !it.isNullOrBlank() }
    val componentTag = if (sectioned) "h3" else "h2"

    fun componentHtml(component: MotionComponent): String {
      val labels = MotionCaptureLabels.of(component.recordings.map { it.lead.capture })
      // A caption that describes every recording of this component describes the COMPONENT, not one
      // card, so it is printed once, above them — where there is a whole row to spend on it rather
      // than a 220px column, and where two cards cannot repeat it at each other. That covers the
      // ordinary single-recording component too: a paragraph set under one narrow card is a column
      // of six-word lines.
      val shared =
        labels
          .map { it.detail }
          .distinct()
          .singleOrNull()
          ?.takeIf { it.isNotBlank() && it != labels.first().title }
      val note =
        shared?.let {
          "\n            <p class=\"cp-motion-component-note\">${WebEscaping.htmlEscape(it)}</p>"
        } ?: ""
      // Only worth saying when there is more than one: "1 recording" above a single card is a count
      // of the thing the reader is already looking at.
      val count =
        component.recordings.size
          .takeIf { it > 1 }
          ?.let {
            "\n              <span class=\"cp-motion-component-count\">$it recordings</span>"
          } ?: ""
      val cards =
        component.recordings
          .mapIndexed { i, recording -> cardHtml(component, recording, labels[i], shared != null) }
          .joinToString("\n")
      // Who the component is on the left, what it records on the right — one row per component on a
      // wide screen, stacked on a narrow one. A component with a single recording is the common
      // case, and left as a full-width block it spent a whole screen height on one 220px card.
      return """
        <article class="cp-motion-component">
          <div class="cp-motion-component-about">
            <$componentTag class="cp-motion-component-head">
              <a class="cp-motion-component-name" href="${WebEscaping.htmlEscape(componentHref(component.lead))}">${WebEscaping.htmlEscape(previewDisplayName(component.lead))}</a>$count
            </$componentTag>$note
          </div>
          <div class="cp-motion-cards">
$cards
          </div>
        </article>
      """
        .trimIndent()
    }

    val body =
      sections.entries.joinToString("\n") { (section, group) ->
        val blocks = group.joinToString("\n") { componentHtml(it) }
        val head =
          section
            ?.takeIf { it.isNotBlank() }
            ?.let { "<h2 class=\"cp-section-head\">${WebEscaping.htmlEscape(it)}</h2>\n" } ?: ""
        "<section class=\"cp-motion-section\">\n$head<div class=\"cp-motion-components\">\n$blocks\n</div>\n</section>"
      }

    val componentCount = components.size
    val componentWord = if (componentCount == 1) "component" else "components"
    val captureWord = if (captureCount == 1) "recording" else "recordings"
    // One axis, page-wide, in the same segmented shape the comparison views use: the light and the
    // dark take of a gesture are the same recording, so this swaps every card between them rather
    // than doubling the page. Server-rendered on the system's own lane; the script re-points it at
    // the theme this catalog is already remembered on.
    val themeControl =
      if (!themed) ""
      else
        "\n          <span class=\"cp-motion-theme\">" +
          "\n            <span class=\"cp-motion-theme-label\">Theme</span>" +
          "\n            <span class=\"cp-theme\" role=\"group\" aria-label=\"Recording theme\">" +
          "\n              <button type=\"button\" class=\"cp-theme-btn\" data-motion-theme=\"light\"" +
          "\n                aria-pressed=\"${leadTheme == "light"}\">Light</button>" +
          "\n              <button type=\"button\" class=\"cp-theme-btn\" data-motion-theme=\"dark\"" +
          "\n                aria-pressed=\"${leadTheme == "dark"}\">Dark</button>" +
          "\n            </span>" +
          "\n          </span>"
    return document(
      changelogHref = changelogHref,
      title = "$heading — motion",
      unfurlTitle = "$heading motion",
      unfurlDescription =
        "Every recorded interaction and animation this design system publishes, side by side",
      unfurl = unfurl,
      version = version,
      navSuffix = navSuffix,
      headerBreadcrumb = crumbHtml("$basePath/$q", heading, "Motion"),
      themeCss = themeCss,
      themeStorageKey = themeStorageKey(sessionId, basePath),
      siteName = heading,
      body =
        """
        <h1 class="cp-head cp-catalog-head">Motion${compactTrustBadge(trust)}</h1>
        <p class="cp-sub">Every recorded interaction and animation this catalog publishes, grouped
        by component and set side by side — so a transition that is shaped differently from its
        neighbours is visible without opening each component in turn.
        $captureCount $captureWord across $componentCount $componentWord.</p>
        <div class="cp-motion-toolbar">
          <button type="button" id="cp-motion-all" class="cp-action-chip cp-motion-all"
            aria-pressed="false" aria-controls="cp-motion-index">Play all</button>$themeControl
          <span class="cp-motion-hint">Nothing plays until you ask it to. Press a card to run one
          recording, or open a component to scrub it frame by frame.</span>
        </div>
        <div class="cp-motion-index" id="cp-motion-index">
        $body
        </div>
        <script>$MOTION_INDEX_SCRIPT</script>
        """
          .trimIndent(),
    )
  }

  /**
   * The motion browser's whole behaviour: swap a card between its still and its recording, and swap
   * every card between its light and its dark take.
   *
   * Inline rather than an asset because it is the only page that has it — the built bundles under
   * `cli/serve-web/` exist for the surfaces with real state machines (the viewer, the comparison
   * scorer), and adding a per-page file to that build would cost a round-trip on every visit.
   *
   * Swapping `src` is deliberately the entire mechanism. An `<img>` playing an APNG or a GIF cannot
   * be paused, sought, or rate-controlled from script — that is what the viewer's canvas player is
   * for, and why every card links to it. What an `<img>` *can* do is decode the format natively and
   * start over from frame one each time its `src` is set, which is exactly the two things a
   * browsing grid needs. Restoring the poster is what stops a recording, because a still that is no
   * longer decoding costs nothing while thirty of them are on screen.
   *
   * The Theme control re-points the card's `data-motion-*` pair (and its name and its link) at the
   * other take and re-applies whatever the card was doing, so switching theme mid-playback keeps
   * playing rather than silently stopping. It writes the choice where the rest of the catalog reads
   * it — the `?theme=` param, this catalog's `localStorage` key, and `cpPageTheme` for the chrome —
   * so a reader who picks Dark here finds the grid and the viewer already dark, and a reload opens
   * where they left off. That is also why the page can be SERVER-rendered on the system's own lane
   * and corrected on load: the choice lives outside this page.
   */
  private const val MOTION_INDEX_SCRIPT =
    """(function(){var stages=[].slice.call(document.querySelectorAll(".cp-motion-card-stage"));if(!stages.length)return;function attr(el,name){return el.getAttribute(name)||"";}function set(b,on){var img=b.querySelector(".cp-motion-card-img");if(!img)return;var src=attr(b,on?"data-motion-src":"data-motion-poster");if(!src)return;b.setAttribute("aria-pressed",on?"true":"false");if(img.getAttribute("src")!==src||on)img.setAttribute("src",src);}stages.forEach(function(b){b.addEventListener("click",function(){set(b,b.getAttribute("aria-pressed")!=="true");sync();});});var all=document.getElementById("cp-motion-all");function playing(){return stages.filter(function(b){return b.getAttribute("aria-pressed")==="true";}).length;}function sync(){if(!all)return;var on=playing()===stages.length;all.setAttribute("aria-pressed",on?"true":"false");all.textContent=playing()?"Stop all":"Play all";}if(all)all.addEventListener("click",function(){var on=playing()!==stages.length;stages.forEach(function(b){set(b,on);});sync();});var themeBtns=[].slice.call(document.querySelectorAll("[data-motion-theme]"));function applyTheme(theme){stages.forEach(function(b){var src=attr(b,"data-motion-src-"+theme);if(!src)return;b.setAttribute("data-motion-src",src);b.setAttribute("data-motion-poster",attr(b,"data-motion-poster-"+theme));var label=attr(b,"data-motion-label-"+theme);if(label){b.setAttribute("title",label);b.setAttribute("aria-label",label);}var href=attr(b,"data-motion-href-"+theme),link=b.parentNode&&b.parentNode.querySelector(".cp-motion-card-title");if(link&&href)link.setAttribute("href",href);set(b,b.getAttribute("aria-pressed")==="true");});themeBtns.forEach(function(t){t.setAttribute("aria-pressed",attr(t,"data-motion-theme")===theme?"true":"false");});}themeBtns.forEach(function(t){t.addEventListener("click",function(){var theme=attr(t,"data-motion-theme");applyTheme(theme);try{var key=document.documentElement.getAttribute("data-cp-theme-key");if(key)localStorage.setItem(key,theme);}catch(e){}if(window.cpUrlState)window.cpUrlState.push({theme:theme});if(window.cpPageTheme)window.cpPageTheme.follow(theme);});});if(themeBtns.length){var opening="";try{var fromUrl=new URLSearchParams(location.search).get("theme");var key=document.documentElement.getAttribute("data-cp-theme-key");var remembered=key?localStorage.getItem(key):"";opening=fromUrl||remembered||"";}catch(e){}if(opening==="light"||opening==="dark")applyTheme(opening);}})();"""

  /**
   * One **design page**: the sheet itself as inlined SVG, an outline over every component node on
   * it, and — behind a toggle — this catalog's own renders standing in for the design's drawing.
   *
   * ## Why the SVG is inlined rather than shown in an `<img>`
   *
   * Because an `<img>` is a picture and this needs to be a document. The entire feature is *take
   * the design's own drawing of `Shape=Circle` out of the sheet and put our `Shape/Circle` render
   * in the hole it leaves* — which means reaching a specific element inside the export, and nothing
   * can reach inside an `<img>`. Inlining is what makes the sheet addressable; `data-node-id`,
   * which the importer asks Figma for explicitly, is what names the elements.
   *
   * That is also why [svg] is interpolated **unescaped**, the only place on this server where
   * third-party markup is. It is not raw: [ServeDesignPageStore] runs it through [SvgSanitizer] at
   * load — allowlisted elements and attributes, no script, no `foreignObject`, no off-document URL
   * — and the store refuses a page whose export does not survive that. Escaping it instead would
   * print the markup as text; there is no third option that keeps the feature.
   *
   * ## Geometry
   *
   * There isn't any, here or in the manifest. The SVG knows where its own nodes are, so
   * `<cp-design-page>` measures each `[data-node-id]` element and places the outline over it. A
   * recorded rectangle would be a second answer to that question, and a worse one — Figma's export
   * box includes effect bleed, so it and the drawn shape disagree on anything with a shadow.
   *
   * ## Zoom
   *
   * The sheet is drawn at the size the design file drew it — m3-catalog's Styles page is 6263 px
   * across — and it lands in a content column a sixth of that, so every type specimen and swatch
   * number on it is sub-pixel. The `<cp-page-zoom>` element this page declares — a Lit component in
   * `cli/serve-web`, alongside `<cp-design-page>` — therefore makes the stage zoomable:
   * double-click drills one addressable level in (Figma's own gesture, and free here because a
   * Figma export is a tree of `<g data-node-id>`, so "one level in" is the next element down the
   * hit-test chain), ⌘/Ctrl + wheel zooms about the pointer, and dragging pans. That is also the
   * only reason the `.cp-page-canvas` wrapper exists: the export, the overlays over it and the
   * renders inside them are moved by ONE transform, so a slot cannot drift off the shape it stands
   * in at any factor. The tip and the corner zoom bar sit OUTSIDE it, since a tooltip that scaled
   * 12x would be unreadable and a control that panned away with the sheet could not be reached to
   * undo the pan.
   *
   * ## Trust
   *
   * [page] is third-party data — layer names are free text authored in the design file — so every
   * interpolation goes through [WebEscaping.htmlEscape], and the Figma deep link is reassembled
   * from a validated key + node id by [ServeFigmaSpec.url] rather than taken from the manifest.
   */
  fun designPage(
    moduleLabel: String,
    page: DesignPage,
    /** Sanitized export markup, inlined as-is. See the doc comment — this is deliberate. */
    svg: String,
    /** The file key the manifest declared, already validated. Empty ⇒ no design-tool deep links. */
    fileKey: String = "",
    /**
     * Preview ids this session can actually render. A node the producer mapped to a preview this
     * catalog doesn't publish keeps its outline (the mapping is still true) but gets no render and
     * no link — better than a card that can only 404.
     */
    renderablePreviewIds: Set<String> = emptySet(),
    token: String,
    sessionId: String? = null,
    basePath: String = "",
    isPublic: Boolean = false,
    trust: String? = null,
    themeCss: String = "",
    unfurl: UnfurlMetadata? = null,
    version: String? = null,
    displayTitle: String? = null,
    /**
     * Whether this page is served as a **top-level site** ([ServeSites]) — its catalog rooted on a
     * hostname of its own. The session is then implied by the ORIGIN, exactly as a `/<system>`
     * mount implies it by the path, so same-session links must not repeat it as `?session=`. False
     * (the default) leaves every existing caller's URLs byte-identical.
     */
    sessionInOrigin: Boolean = false,
    /**
     * The catalog change feed the footer offers as **Changelog** and the head declares as this
     * page's RSS alternate. Empty when the server runs with the feed lane off. See [siteFooter].
     */
    changelogHref: String = "",
  ): String {
    // The session id links may carry. Null on a rooted site (and for the default session): the
    // URL already says which catalog this is. `sessionId` itself stays intact below — it keys the
    // per-catalog localStorage entries and the dark-first lookup, which a site still needs.
    val linkSessionId = if (sessionInOrigin) null else sessionId
    val q = querySuffix(linkQuery(token, linkSessionId, basePath, isPublic))
    val navSuffix =
      querySuffix(if (isPublic) "" else "token=" + WebEscaping.urlEncodeSegment(token))
    val heading = catalogHeading(displayTitle, moduleLabel)

    /** The preview this node can be drawn with on this session, or null. */
    fun renderable(node: PageNode): String? =
      node.renderablePreviewId?.takeIf { it in renderablePreviewIds }

    // Which unlinked nodes are actually missing components. `data-cp-gap` is what the "only what we
    // don't implement" filter keys on — NOT `data-link="unlinked"`, which also catches the sheet's
    // private furniture and its variant-set containers. Filtering on the latter is what made a
    // fully-implemented Shape page report `.Header`, `.Header` and `Shape Set` as work to do.
    val gaps = page.coverageGaps.toSet()

    // A hit area per node, and nothing else: no resting outline, no colour, no fill. The sheet is
    // the content here, so a mark is something the reader asks for — by pointing at a component,
    // or by turning the whole layer on — rather than the page's opening statement.
    //
    // An `<a>`, because pointing and going are now split. POINTING describes: the node's detail
    // lands under the sheet as the pointer sweeps, so a reader can read several components without
    // committing to any of them. CLICKING goes there.
    //
    // A control that navigates should BE a link, and making it one is not a formality: the middle
    // click, the modifier click and the status-bar preview all start working, the destination is
    // announced instead of a pressed state that was never true, and the sheet still navigates with
    // no script at all.
    val components = page.nodes.filter(PageNode::isComponent)
    val outlines =
      components.joinToString("\n") { node ->
        val label =
          if (node.isUnlinked) "${node.name} — no code behind this"
          else "${node.name} — ${node.code.orEmpty()}"
        // A node with code goes to its preview; one without goes to the design file, which is the
        // only link it has.
        val href =
          renderable(node)?.let { "$basePath/p/${WebEscaping.urlEncodeSegment(it)}$q" }
            ?: ServeFigmaSpec.url(fileKey, node.nodeId)
        val tag = if (href == null) "span" else "a"
        val hrefAttr = href?.let { " href=\"${WebEscaping.htmlEscape(it)}\"" }.orEmpty()
        "<$tag class=\"cp-page-node\" " +
          // The anchor a section row in the catalog sidebar lands on. Every node carries one, not
          // just the sets — a fragment is free, and the id is what lets `<cp-design-page>` find the
          // node a URL names without a second lookup table.
          "id=\"${nodeAnchorId(node.nodeId)}\" " +
          "data-link=\"${WebEscaping.htmlEscape(node.link.wire)}\"" +
          (if (node in gaps) " data-cp-gap" else "") +
          // Separate from `data-link`, because it answers a different question: the link says HOW
          // we know this maps, the cell says WHAT is behind it. See `PageNode.cell`.
          (if (node.cell) " data-cp-cell" else "") +
          hrefAttr +
          " " +
          "data-cp-node=\"${WebEscaping.htmlEscape(node.nodeId)}\" " +
          "title=\"${WebEscaping.htmlEscape(label)}\"><span class=\"cp-visually-hidden\">" +
          "${WebEscaping.htmlEscape(label)}</span></$tag>"
      }

    // The renders live in an inert `<template>` and are adopted when the lane that needs them is
    // entered. The page now OPENS on that lane, so on a live catalog this is a daemon render per
    // node on first paint — `loading="lazy"` is what keeps that bounded, since a specimen sheet is
    // tall and most of it is below the fold. The template still earns its place: a reader who flips
    // to the spec and never flips back pays for nothing, and every URL in it stays server-built and
    // server-escaped (reading one out of the DOM into `img.src` is CodeQL's `js/xss-through-dom`).
    val renders =
      components
        .mapNotNull { node ->
          val previewId = renderable(node) ?: return@mapNotNull null
          "<img class=\"cp-page-render\" alt=\"\" loading=\"lazy\" " +
            "data-cp-node=\"${WebEscaping.htmlEscape(node.nodeId)}\" " +
            "src=\"$basePath/render/${WebEscaping.urlEncodeSegment(previewId)}.png$q\">"
        }
        .joinToString("\n")

    // The way out of the diff lane, one anchor per scoreable node, riding the same inert template
    // trick as the renders. `?mode=spec&specView=diff` is the viewer's own deep link into the full
    // Figma comparison — the diff map, the triptych, the wipe — so the sheet's number and the view
    // it opens are the same instrument.
    //
    // An ANCHOR the script clicks, rather than a URL in a data attribute the script reads and
    // assigns to `location`. That assignment is the taint path (`js/xss-through-dom`) the renders
    // already avoid, and the destination here is built from a preview id that came off a design
    // file. Cloning a server-built, server-escaped element has no sink in it at all.
    val diffLinks =
      components
        .mapNotNull { node ->
          val previewId = renderable(node) ?: return@mapNotNull null
          val sep = if (q.isEmpty()) "?" else "&"
          "<a class=\"cp-page-diff-link\" tabindex=\"-1\" aria-hidden=\"true\" " +
            "data-cp-node=\"${WebEscaping.htmlEscape(node.nodeId)}\" " +
            "href=\"$basePath/p/${WebEscaping.urlEncodeSegment(previewId)}$q${sep}mode=spec&amp;specView=diff\"></a>"
        }
        .joinToString("\n")

    // The audit list, and now also the source the selection strip is cloned from — which is why
    // every row is a link wherever it can be. A node with code goes to its preview; a node without
    // goes to the design file, built from the node's own id rather than parsed out of its `ref`
    // (the two are the same thing by definition, but `ref` is optional and this deep link is the
    // only link an unlinked node has).
    val rows =
      components.joinToString("\n") { node ->
        val previewId = renderable(node)
        val href =
          previewId?.let { "$basePath/p/${WebEscaping.urlEncodeSegment(it)}$q" }
            ?: ServeFigmaSpec.url(fileKey, node.nodeId)
        val tag = if (href == null) "div" else "a"
        val hrefAttr = href?.let { " href=\"${WebEscaping.htmlEscape(it)}\"" }.orEmpty()
        val code = node.code
        val detail = if (code != null) WebEscaping.htmlEscape(code) else "no code behind this"
        "<$tag class=\"cp-page-row\" data-link=\"${WebEscaping.htmlEscape(node.link.wire)}\"" +
          (if (node in gaps) " data-cp-gap" else "") +
          (if (node.cell) " data-cp-cell" else "") +
          " " +
          "data-cp-node=\"${WebEscaping.htmlEscape(node.nodeId)}\"$hrefAttr>" +
          "<span class=\"cp-page-dot\" aria-hidden=\"true\"></span>" +
          "<span class=\"cp-page-row-name\">${WebEscaping.htmlEscape(node.name)}</span>" +
          "<span class=\"cp-page-row-code\">$detail</span></$tag>"
      }

    val linked = page.linked.size
    // Counted against what a catalog could actually implement, not against every node on the sheet:
    // a private component and a variant-set container are furniture, and counting them reports a
    // complete family as one short. See `DesignPage.coverageGaps`.
    val total = page.coverageTotal
    // See the pages index: a non-inventory sheet says what it is rather than scoring itself.
    val coverageText =
      if (!page.inventory) "${page.nodes.size} nodes · not a component inventory"
      else "$linked of $total components implemented"
    val figmaLink =
      ServeFigmaSpec.url(fileKey, page.nodeId)
        ?.let {
          " · <a href=\"${WebEscaping.htmlEscape(it)}\" rel=\"noreferrer noopener\">Open in Figma</a>"
        }
        .orEmpty()
    // A specimen sheet is wider than it is tall, the opposite of the phone screens this surface
    // used
    // to show — so the stage's aspect ratio is the sheet's own, from the export's viewBox. The
    // design decides the shape of the box, not the stylesheet.
    //
    // Locale.ROOT, not `"%.4f".format(…)`: under a comma-decimal default locale the latter emits
    // `aspect-ratio:1,1843`, which is not CSS at all, and the stage would collapse on a box whose
    // LANG happened to be de_DE.
    val aspect = String.format(java.util.Locale.ROOT, "%.4f", page.frame.width / page.frame.height)

    return document(
      changelogHref = changelogHref,
      title = "${page.name} — page",
      unfurlTitle = "$heading — ${page.name}",
      unfurlDescription =
        if (!page.inventory) "${page.nodes.size} nodes on this page; not a component inventory"
        else "$linked of $total components on this page are implemented",
      unfurl = unfurl,
      version = version,
      navSuffix = navSuffix,
      headerBreadcrumb = crumbHtml("$basePath/pages$q", heading, page.name),
      themeCss = themeCss,
      // The bar names the catalog you are in, from the same heading the page shows.
      siteName = heading,
      body =
        """
        <div id="cp-design-page">
          <h1 class="cp-head cp-catalog-head">${WebEscaping.htmlEscape(page.name)}${compactTrustBadge(trust)}</h1>
          <p class="cp-sub">${WebEscaping.htmlEscape(coverageText)}$figmaLink</p>
          <div class="cp-page-controls">
            <div class="cp-page-lane" role="radiogroup" aria-label="What the sheet shows">
              <label><input type="radio" name="cp-page-lane" value="code" data-cp-page-lane checked>
                <span>Our renders</span></label>
              <label><input type="radio" name="cp-page-lane" value="design" data-cp-page-lane>
                <span>Design spec</span></label>
              <label><input type="radio" name="cp-page-lane" value="diff" data-cp-page-lane>
                <span>Diff %</span></label>
            </div>
            <label class="cp-page-opt"><input type="checkbox" data-cp-page-outlines> Outline every component</label>
            <label class="cp-page-opt"><input type="checkbox" data-cp-page-unlinked> Only what we don't implement</label>
            <span class="cp-page-hint">Double-click a section to zoom · ⌘/Ctrl-scroll · drag to pan
              · + / &#8722; / 0 by keyboard · Esc resets</span>
          </div>
          <div class="cp-page-legend" hidden>
            <span data-link="code-connect"><i class="cp-page-swatch" style="color:#2da44e"></i> Code Connect</span>
            <span data-link="manifest"><i class="cp-page-swatch" style="color:#0969da"></i> design-map</span>
            <span data-link="convention"><i class="cp-page-swatch" style="color:#bf8700"></i> name match</span>
            <span data-cp-cell><i class="cp-page-swatch" style="color:#8250df"></i> override variant</span>
            <span data-link="unlinked"><i class="cp-page-swatch" style="color:#cf222e;border-style:dashed"></i> not implemented</span>
          </div>
          <div class="cp-page-layout">
            <div class="cp-page-stage" style="--cp-page-aspect:$aspect">
              <div class="cp-page-canvas" data-cp-page-canvas>
                $svg
                <template data-cp-page-render-source>$renders</template>
                <template data-cp-page-diff-links>$diffLinks</template>
                $outlines
              </div>
              <div class="cp-page-tip" data-cp-page-tip hidden aria-live="polite"></div>
              <cp-page-zoom hidden></cp-page-zoom>
            </div>
            <details class="cp-page-nodes">
              <summary>$linked of $total components implemented</summary>
              <div class="cp-page-list">
              $rows
              </div>
            </details>
          </div>
        </div>
        <!-- The sheet's overlays, lanes and per-node scoring, alongside the zoom
             (`<cp-page-zoom>`) — both in the Lit bundle. `<cp-design-page>` reads
             `window.ComposePreviewCompare` when the diff lane is entered rather than when it
             upgrades, so it does not depend on following the script below. -->
        ${scriptTag("serve-components.js")}
        ${scriptTag("format-compare.js")}
        <cp-design-page></cp-design-page>
        """
          .trimIndent(),
    )
  }

  /**
   * The catalog's **Design parity** view: recent movement on both sides of the code ↔ design pair,
   * how far apart they are, and what isn't mapped yet.
   *
   * The page defaults to the two bands a reader can act on:
   *
   * 1. **Where we stand** — coverage (how many components carry a design reference), open Figma
   *    comments, and how recently each side moved. Computed live for the coverage half, so it is
   *    right even for a catalog that publishes no feed at all.
   * 2. **Activity and issues** — the merged feed plus components whose two sides moved *unevenly*
   *    inside the window. This is the band that justifies putting the feeds together: a component
   *    with a commit and no design change (or the reverse) is where the render and its reference
   *    are drifting apart, and every row links straight to that component's reference-vs-render
   *    comparison. The complete component inventory remains available in a collapsed comparison
   *    table. This keeps the default view useful without turning 78 healthy mappings into the
   *    page's main subject.
   *
   * Everything textual in [dashboard] is third-party — commit subjects and Figma comment bodies
   * written by other people — so every interpolation goes through [WebEscaping.htmlEscape], and
   * outbound hrefs were rebuilt from validated parts by [ServeParityActivityStore] rather than
   * taken from the catalog.
   */
  fun parityPage(
    moduleLabel: String,
    dashboard: ServeParityDashboard.Dashboard,
    token: String,
    sessionId: String? = null,
    basePath: String = "",
    isPublic: Boolean = false,
    trust: String? = null,
    themeCss: String = "",
    unfurl: UnfurlMetadata? = null,
    /**
     * Running server version (`SERVE_VERSION`), shown in the minimal footer. Null omits the build
     * span.
     */
    version: String? = null,
    displayTitle: String? = null,
    /** Whether a preview carries a design reference — decides "compare" vs "open" on a link. */
    hasReferenceFor: (String) -> Boolean = { false },
    parityIssues: List<ParityIssue> = emptyList(),
    /**
     * The catalog inventory the acceptance walk resolves its targets against — null when this
     * catalog publishes no known-difference document, which leaves the panel and the engine's
     * bundle off the page entirely.
     *
     * The identity comes from the handler and the URLs are built here, the same split
     * [KnownDifferenceScope] draws: every link on this page goes through one query builder, and a
     * hand-rolled query is how a credential gets dropped on a header-authorized host.
     */
    acceptanceAudit: List<KnownDifferenceCatalogPreview>? = null,
    /**
     * The design tool this catalog is specified by ("Figma", …) — names the whole-catalog compare
     * link. Null keeps the neutral "design references" wording. See [designToolLabel].
     */
    designToolLabel: String? = null,
    /**
     * Whether this page is served as a **top-level site** ([ServeSites]) — its catalog rooted on a
     * hostname of its own. The session is then implied by the ORIGIN, exactly as a `/<system>`
     * mount implies it by the path, so same-session links must not repeat it as `?session=`. False
     * (the default) leaves every existing caller's URLs byte-identical.
     */
    sessionInOrigin: Boolean = false,
    /**
     * The catalog change feed the footer offers as **Changelog** and the head declares as this
     * page's RSS alternate. Empty when the server runs with the feed lane off. See [siteFooter].
     */
    changelogHref: String = "",
  ): String {
    // The session id links may carry. Null on a rooted site (and for the default session): the
    // URL already says which catalog this is. `sessionId` itself stays intact below — it keys the
    // per-catalog localStorage entries and the dark-first lookup, which a site still needs.
    val linkSessionId = if (sessionInOrigin) null else sessionId
    fun esc(s: String) = WebEscaping.htmlEscape(s)
    val q = querySuffix(linkQuery(token, linkSessionId, basePath, isPublic))
    val navSuffix =
      querySuffix(if (isPublic) "" else "token=" + WebEscaping.urlEncodeSegment(token))
    val heading = catalogHeading(displayTitle, moduleLabel)
    val coverage = dashboard.coverage

    /**
     * The strongest link we can offer for a preview: the reference-vs-render comparison when the
     * catalog maps one, else the plain viewer. Never a dead link — the caller has already filtered
     * to preview ids this session actually serves.
     */
    fun previewHref(previewId: String): String {
      val seg = WebEscaping.urlEncodeSegment(previewId)
      return if (hasReferenceFor(previewId)) "$basePath/compare/$seg$q" else "$basePath/p/$seg$q"
    }

    fun previewLink(previewId: String, label: String): String =
      "<a href=\"${esc(previewHref(previewId))}\">${esc(label)}</a>"

    fun outboundLink(entry: ServeParityDashboard.FeedEntry): String {
      val href = entry.href ?: return ""
      val label = entry.hrefLabel ?: "open"
      return "<a class=\"cp-parity-out\" href=\"${esc(href)}\" rel=\"noopener\">${esc(label)} ↗</a>"
    }

    val laneLabel =
      mapOf(
        ServeParityDashboard.Lane.CODE to "code",
        ServeParityDashboard.Lane.FIGMA_VERSION to "figma",
        ServeParityDashboard.Lane.FIGMA_COMMENT to "comment",
      )

    val lastCode = dashboard.feed.firstOrNull { it.lane == ServeParityDashboard.Lane.CODE }?.at
    val lastDesign = dashboard.feed.firstOrNull { it.lane != ServeParityDashboard.Lane.CODE }?.at
    val stats = buildList {
      add("mapped" to "${coverage.mapped}/${coverage.components}")
      if (dashboard.hasActivity) {
        add("open comments" to dashboard.openComments.toString())
        add("last code change" to (lastCode?.let(::prettyDate) ?: "—"))
        add("last design change" to (lastDesign?.let(::prettyDate) ?: "—"))
      }
      if (dashboard.gaps.isNotEmpty()) add("declared gaps" to dashboard.gaps.size.toString())
    }
      .joinToString("\n") { (key, value) ->
        "<div class=\"cp-stat\"><div class=\"cp-stat-key\">${esc(key)}</div>" +
          "<div class=\"cp-stat-val\">${esc(value)}</div></div>"
      }

    // The coverage meter is a plain bar rather than a chart: one number, and the number is already
    // written beside it. `aria-*` carries the same value for a screen reader.
    val coverageMeter =
      """
      <div class="cp-parity-meter" role="img"
        aria-label="${esc("${coverage.percent}% of components carry a design reference")}">
        <div class="cp-parity-meter-fill" style="width: ${coverage.percent}%"></div>
      </div>
      """
        .trimIndent()

    val driftRows =
      dashboard.components
        .filter { it.correlation != ServeParityDashboard.Correlation.BOTH }
        .take(20)
    val driftBand =
      if (driftRows.isEmpty()) ""
      else {
        val rows =
          driftRows.joinToString("\n") { component ->
            val oneSided = component.correlation == ServeParityDashboard.Correlation.CODE_ONLY
            val badgeClass = if (oneSided) "cp-parity-lane--code" else "cp-parity-lane--figma"
            val badge = if (oneSided) "code only" else "design only"
            val why =
              if (oneSided) "the render moved; its reference did not"
              else "the design moved; the code did not"
            val name =
              component.previewId?.let { previewLink(it, component.name) } ?: esc(component.name)
            "<tr><td>$name</td>" +
              "<td><span class=\"cp-parity-lane $badgeClass\">${esc(badge)}</span></td>" +
              "<td class=\"cp-muted\">${esc(why)}</td>" +
              "<td class=\"cp-muted\">${esc(prettyDate(component.lastAt))}</td></tr>"
          }
        """
        <h3 class="cp-parity-sub">Out-of-sync activity</h3>
        <p class="cp-muted">Components that moved on one side only inside this window — where the
          render and its reference are most likely to have drifted apart.</p>
        <div class="cp-status-scroll">
          <table class="cp-table">
            <thead><tr><th>Component</th><th>Moved</th><th>Why it's here</th><th>Last change</th></tr></thead>
            <tbody>
            $rows
            </tbody>
          </table>
        </div>
        """
          .trimIndent()
      }

    val feedBand =
      if (dashboard.feed.isEmpty()) {
        """
          <h2 class="cp-status-sec">Activity</h2>
        <p class="cp-muted">This catalog publishes no activity feed yet. A producer adds one by
          emitting <code>parity/activity.json</code> beside its catalog — see the
          <a href="https://github.com/$SOURCE_REPO/blob/main/docs/public-preview-server.md">server
          docs</a>. Coverage above is computed live and needs nothing published.</p>
        """
          .trimIndent()
      } else {
        val items =
          dashboard.feed.joinToString("\n") { entry ->
            val lane = laneLabel[entry.lane].orEmpty()
            val laneClass =
              if (entry.lane == ServeParityDashboard.Lane.CODE) "cp-parity-lane--code"
              else "cp-parity-lane--figma"
            val resolved = if (entry.resolved) " cp-parity-entry--resolved" else ""
            val who =
              entry.author?.let { "<span class=\"cp-parity-who\">${esc(it)}</span>" }.orEmpty()
            val detail =
              entry.detail?.let { "<span class=\"cp-parity-detail\">${esc(it)}</span>" }.orEmpty()
            val resolvedBadge =
              if (entry.resolved) "<span class=\"cp-parity-detail\">resolved</span>" else ""
            // Inbound links are what make this a parity feed rather than a changelog: every row
            // that names previews this session serves offers a jump to their comparison.
            val targets =
              entry.previewIds
                .take(6)
                .mapIndexed { index, previewId ->
                  previewLink(previewId, entry.components.getOrNull(index) ?: previewId)
                }
                .joinToString(" · ")
            val targetsHtml =
              if (targets.isEmpty()) "" else "<div class=\"cp-parity-targets\">$targets</div>"
            val componentsHtml =
              if (entry.previewIds.isNotEmpty() || entry.components.isEmpty()) ""
              else
                "<div class=\"cp-parity-targets cp-muted\">" +
                  esc(entry.components.take(6).joinToString(" · ")) +
                  "</div>"
            """
            <li class="cp-parity-entry$resolved" data-lane="${esc(lane)}">
              <div class="cp-parity-when">${esc(prettyDate(entry.at))}</div>
              <div class="cp-parity-body">
                <div class="cp-parity-head">
                  <span class="cp-parity-lane $laneClass">${esc(lane)}</span>
                  <span class="cp-parity-title">${esc(entry.title)}</span>
                </div>
                <div class="cp-parity-meta">$who$detail$resolvedBadge${outboundLink(entry)}</div>
                $targetsHtml$componentsHtml
              </div>
            </li>
            """
              .trimIndent()
          }
        val filters =
          listOf("all" to "All", "code" to "Code", "figma" to "Figma", "comment" to "Comments")
            .joinToString("\n") { (value, label) ->
              val current = if (value == "all") " aria-current=\"page\"" else ""
              "<button type=\"button\" class=\"cp-state-btn\" data-parity-lane=\"$value\"$current>" +
                "${esc(label)}</button>"
            }
        """
        <h2 class="cp-status-sec">Activity</h2>
        <div class="cp-states" role="group" aria-label="Filter activity by lane">
        $filters
        </div>
        <ul class="cp-parity-feed" id="cp-parity-feed">
        $items
        </ul>
        <p class="cp-muted" id="cp-parity-feed-empty" hidden>No activity in this lane.</p>
        <!-- Wires the lane buttons above to the feed. Renders nothing, and the feed is fully
             readable without it; `serve.css` hides the tag. -->
        <cp-parity-lanes></cp-parity-lanes>
        """
          .trimIndent()
      }

    val unmappedBand =
      if (coverage.unmapped.isEmpty() && dashboard.gaps.isEmpty()) {
        if (coverage.components == 0) ""
        else
          """
          <p class="cp-muted">Every component in this catalog carries a design reference.</p>
          """
            .trimIndent()
      } else {
        val unmappedList =
          if (coverage.unmapped.isEmpty()) ""
          else {
            val chips =
              coverage.unmapped.joinToString("\n") { component ->
                val seg = WebEscaping.urlEncodeSegment(component.previewId)
                "<li><a class=\"cp-state-btn\" href=\"$basePath/p/$seg$q\">" +
                  "${esc(component.name)}</a></li>"
              }
            val overflow =
              if (coverage.unmappedOverflow <= 0) ""
              else "<p class=\"cp-muted\">…and ${coverage.unmappedOverflow} more.</p>"
            """
            <h3 class="cp-parity-sub">No design reference (${coverage.unmappedCount})</h3>
            <p class="cp-muted">These render, but nothing in the design file is mapped to them — so
              nothing can score them against a spec.</p>
            <ul class="cp-parity-chips">
            $chips
            </ul>
            $overflow
            """
              .trimIndent()
          }
        val gapRows =
          if (dashboard.gaps.isEmpty()) ""
          else {
            val kindLabel =
              mapOf(
                MappingGap.Kind.DANGLING_MAPPING to "mapping points at a missing preview",
                MappingGap.Kind.UNRENDERED_REFERENCE to "reference could not be published",
                MappingGap.Kind.UNMAPPED_DESIGN_NODE to "design node with no code",
              )
            val rows =
              dashboard.gaps.joinToString("\n") { gap ->
                val subject = gap.component ?: gap.previewId ?: gap.code ?: gap.ref ?: "—"
                "<tr><td class=\"cp-muted\">${esc(kindLabel[gap.kind] ?: gap.kind)}</td>" +
                  "<td><code>${esc(subject)}</code></td>" +
                  "<td>${esc(gap.detail)}</td></tr>"
              }
            """
            <h3 class="cp-parity-sub">Declared by the producer (${dashboard.gaps.size})</h3>
            <p class="cp-muted">Gaps only the publish job can see — it has the design file and the
              checkout; this server has neither.</p>
            <div class="cp-status-scroll">
              <table class="cp-table">
                <thead><tr><th>Kind</th><th>Subject</th><th>Detail</th></tr></thead>
                <tbody>
                $rows
                </tbody>
              </table>
            </div>
            """
              .trimIndent()
          }
        """
        $unmappedList
        $gapRows
        """
          .trimIndent()
      }

    val githubIssueBand =
      if (parityIssues.isEmpty()) ""
      else {
        val open = parityIssues.filter { it.state == "open" }
        val closed = parityIssues.filter { it.state == "closed" }
        val groups = open.groupBy { it.component ?: "Unscoped" }
        val summary =
          groups.entries.joinToString("\n") { (component, rows) ->
            "<section class=\"cp-parity-issue-group\"><h3>${esc(component)} (${rows.size})</h3>${parityIssueRowsHtml(rows)}</section>"
          }
        // The heading counts *components*, and `open` is rows: an umbrella issue contributes one
        // row per component it names, so counting rows here would report three components with an
        // open issue where one issue names three.
        val openBand =
          "<h2 class=\"cp-status-sec\">Components with open issues (${groups.size})</h2>" +
            if (open.isEmpty()) "<p class=\"cp-muted\">No open issues.</p>" else summary
        // The closed band is flat and its rows do not name a component, so one closed umbrella
        // issue
        // would otherwise render as three identical links under a count that claims three issues.
        // The open band above needs no such collapse: it groups by component, which is exactly what
        // distinguishes those rows from each other.
        val closedIssues = closed.distinctBy { it.repository to it.number }
        val closedBand =
          if (closedIssues.isEmpty()) ""
          else
            "<h2 class=\"cp-status-sec\">Closed issues (${closedIssues.size})</h2>${parityIssueRowsHtml(closedIssues)}"
        openBand + closedBand
      }
    val issueBand =
      if (
        parityIssues.isEmpty() &&
          driftBand.isEmpty() &&
          coverage.unmapped.isEmpty() &&
          dashboard.gaps.isEmpty()
      ) {
        """
        <h2 class="cp-status-sec">Issues</h2>
        <p class="cp-muted">No mapping gaps or one-sided changes were detected.</p>
        """
          .trimIndent()
      } else {
        """
        <h2 class="cp-status-sec">Issues</h2>
        $githubIssueBand
        $driftBand
        $unmappedBand
        """
          .trimIndent()
      }

    // The whole "Visual differences" band belongs to `<cp-parity-scores>`, which scores every
    // published render/reference pair and renders the result. The server used to emit the section
    // and its empty table here, which cost two things: the element had to build the rows by
    // hand-escaping into `innerHTML`, and a page with JavaScript off was left promising "Checking N
    // mapped comparison(s)…" forever. Declaring the tag says where the band goes and nothing about
    // what is in it, so a page that cannot run the scan shows nothing rather than a lie.
    val visualIssues =
      if (dashboard.comparisons.none { it.referenceId != null }) ""
      else "<cp-parity-scores></cp-parity-scores>"

    val comparisonBand =
      if (dashboard.comparisons.isEmpty()) ""
      else {
        val rows =
          dashboard.comparisons.joinToString("\n") { component ->
            val render = previewLink(component.previewId, "Open render")
            val design =
              if (component.hasReference) "<span class=\"cp-ok\">Mapped</span>"
              else "<span class=\"cp-parity-missing\">Missing</span>"
            val review =
              if (component.hasReference) previewLink(component.previewId, "Compare") else "—"
            val scoring =
              component.referenceId
                ?.let { referenceId ->
                  val actualUrl =
                    "$basePath/render/${WebEscaping.urlEncodeSegment(component.previewId)}.png$q"
                  val referenceUrl =
                    "$basePath/reference/${WebEscaping.urlEncodeSegment(referenceId)}.png$q"
                  " data-parity-comparison data-reference=\"${esc(referenceUrl)}\"" +
                    " data-actual=\"${esc(actualUrl)}\" data-name=\"${esc(component.name)}\"" +
                    " data-review=\"${esc(previewHref(component.previewId))}\""
                }
                .orEmpty()
            val score =
              if (component.referenceId != null)
                "<span class=\"cp-parity-score cp-muted\">Checking…</span>"
              else "—"
            "<tr$scoring><td>${esc(component.name)}</td><td>$render</td><td>$design</td>" +
              "<td>$score</td><td>$review</td></tr>"
          }
        """
        <details class="cp-parity-comparisons cp-disclosure">
          <summary>
            <span class="cp-parity-comparisons-title">All comparisons (${dashboard.comparisons.size})</span>
            <span class="cp-disclosure-hint">Browse every code component and its design mapping</span>
          </summary>
          <div class="cp-disclosure-body cp-status-scroll">
            <table class="cp-table">
              <thead><tr><th>Component</th><th>Code</th><th>Design reference</th><th>Structural match</th><th>Review</th></tr></thead>
              <tbody>
              $rows
              </tbody>
            </table>
          </div>
        </details>
        """
          .trimIndent()
      }

    // The catalog landing sends every design-tool question here ("compare to Figma"), so this page
    // owes a way back out to the side-by-side table of ALL mapped components — the comparison
    // page's `reference` format. Offered only when something is mapped; a feed-only catalog (no
    // references) would land on an empty table.
    val compareAllLink =
      if (coverage.mapped == 0) ""
      else {
        val query =
          listOf("format=reference", linkQuery(token, linkSessionId, basePath, isPublic))
            .filter { it.isNotEmpty() }
            .joinToString("&")
        val against = designToolLabel?.let(::esc) ?: "the design references"
        // The same assist chip the catalog landing uses for its actions, so the route on and the
        // route back are the same affordance rather than a chip in one direction and a grey text
        // link in the other.
        "\n        <div class=\"cp-catalog-actions\">" +
          "<a class=\"cp-action-chip\" href=\"$basePath/compare?$query\">" +
          "compare every mapped component against $against</a></div>"
      }

    // The catalog-wide acceptance audit. Both the band and its payload are absent together on a
    // catalog that has accepted nothing: an empty panel would say "0 known differences" on every
    // dashboard, and the page would carry the contract's whole engine to evaluate nothing.
    //
    // The band renders empty and `hidden` for the reason the comparison band does — the verdicts
    // are
    // the browser's, and a panel that appeared before the walk had run would be asserting something
    // nobody had measured.
    val acceptanceAuditBand =
      if (acceptanceAudit == null) ""
      else {
        val context =
          KnownDifferenceAuditContext(
            documentUrl = "$basePath/parity/known-differences.json$q",
            artifactBase = "$basePath/parity/known-differences/",
            artifactQuery = q,
            previews = acceptanceAudit,
            issues =
              parityIssues
                .map { KnownDifferenceIssue(it.repository, it.number, it.state) }
                .distinctBy { Triple(it.repository, it.number, it.state) },
          )
        "\n" +
          """<div class="cp-acceptance-audit" id="cp-acceptance-audit" role="status" hidden></div>
<script type="application/json" id="cp-known-difference-audit">${
            encodeKnownDifferenceAuditContext(context)
          }</script>
${scriptTag("known-differences.js")}
<cp-acceptance-audit></cp-acceptance-audit>"""
      }

    // `format-compare.js` still holds the scorer itself — `<cp-parity-scores>` calls into
    // `window.ComposePreviewCompare` — so it loads for a catalog with published references, and
    // must be defined before the components bundle upgrades the tag.
    val parityScripts = buildString {
      if (dashboard.comparisons.any { it.referenceId != null })
        append(scriptTag("format-compare.js"))
      if (dashboard.feed.isNotEmpty() || dashboard.comparisons.any { it.referenceId != null })
        append(scriptTag("serve-components.js"))
    }

    // Provenance for the page itself: this is snapshotted data, and saying so is the difference
    // between "nothing changed in Figma" and "we last looked a week ago".
    val sources = buildList {
      dashboard.codeRepo?.let { repo ->
        val ref = dashboard.codeRef?.let { " @ ${esc(it)}" }.orEmpty()
        add(
          "<span class=\"cp-prov-item\"><span class=\"cp-prov-key\">code</span> " +
            "<a href=\"${esc("https://github.com/$repo")}\">$GITHUB_ICON ${esc(repo)}</a>$ref</span>"
        )
      }
      dashboard.figmaFileHref?.let { href ->
        val name = dashboard.figmaFileName ?: "Figma file"
        add(
          "<span class=\"cp-prov-item\"><span class=\"cp-prov-key\">design</span> " +
            "<a href=\"${esc(href)}\" rel=\"noopener\">${esc(name)} ↗</a></span>"
        )
      }
      dashboard.generatedAt?.let {
        add(
          "<span class=\"cp-prov-item\"><span class=\"cp-prov-key\">snapshotted</span> " +
            "${esc(prettyDate(it))}</span>"
        )
      }
      dashboard.windowDays?.let {
        add(
          "<span class=\"cp-prov-item\"><span class=\"cp-prov-key\">window</span> " +
            "last $it days</span>"
        )
      }
    }
    val sourcesStrip =
      if (sources.isEmpty()) ""
      else
        """
        <details class="cp-prov cp-disclosure">
          <summary>
            <span class="cp-prov-title">Feed details</span>
            <span class="cp-disclosure-hint">Where this activity was read from, and when</span>
          </summary>
          <div class="cp-prov-body" aria-label="Activity provenance">
            ${sources.joinToString("\n            ")}
          </div>
        </details>
        """
          .trimIndent()

    return document(
      changelogHref = changelogHref,
      title = "Design parity — $heading — compose-preview",
      unfurlTitle = "$heading — design parity",
      unfurlDescription =
        "${coverage.mapped} of ${coverage.components} components in $heading are mapped to a design reference.",
      unfurl = unfurl,
      version = version,
      navSuffix = navSuffix,
      headerBreadcrumb = crumbHtml("$basePath/$q", heading, "Design parity"),
      themeCss = themeCss,
      // The bar names the catalog you are in, from the same heading the page shows.
      siteName = heading,
      body =
        """
        <h1 class="cp-head cp-catalog-head">Design parity${compactTrustBadge(trust)}</h1>
        <p class="cp-sub">How this catalog's code and its design file have moved, and how far apart
          they are.</p>$compareAllLink
        $sourcesStrip
        <div class="cp-status-grid">
        $stats
        </div>
        $coverageMeter
        <p class="cp-muted">${coverage.percent}% of ${coverage.components} component(s) carry a
          design reference.</p>
        $feedBand
        $issueBand$acceptanceAuditBand
        $visualIssues
        $comparisonBand
        $parityScripts
        """
          .trimIndent(),
    )
  }

  /**
   * Viewer page for one preview: an `<img>` driven by the override controls.
   *
   * [wasmSrc] (non-null only for a CMP catalog session the server carries a Wasm app for) adds a
   * "Run in browser (Wasm)" toggle that mounts that app in a sandboxed `<iframe>` at the
   * `data-mode="live"` seam — the M3 component renders **client-side** (no server round-trip), so
   * it's safe to run even for an unverified session. The theme / font-scale / locale controls
   * re-point the iframe's `?uiMode` / `?fontScale` / `?localeTag` so they drive the in-browser
   * render (device / orientation stay server-render-only). Absent ⇒ the snapshot viewer as before.
   */
  fun viewerPage(
    preview: ServePreview,
    token: String,
    sessionId: String? = null,
    /**
     * The catalog this preview belongs to, named in the header bar ([siteHeader]). The viewer
     * computes no heading of its own — its `<h1>` is the preview — so the name is supplied by the
     * caller, which is also the only place that knows the catalog's published title.
     */
    catalogName: String = "",
    canApplyOverrides: Boolean = false,
    /**
     * Whether the "Live (stream)" toggle is offered — the daemon live lane, distinct from
     * [canApplyOverrides] (which drives whether *snapshots* re-render on override edits). Defaults
     * to [canApplyOverrides] so plain daemon / static sessions are unchanged; a trusted-catalog
     * live session ([ServeCatalogLiveHost]) passes `canApplyOverrides = false` (static, instant
     * baked snapshots) with `hasLiveStream = true` (Live still offered on demand).
     */
    hasLiveStream: Boolean = canApplyOverrides,
    /**
     * Whether an override-bearing `/render` returns fresh pixels even though the *default* snapshot
     * lane is baked ([canApplyOverrides] false) — true for a trusted-catalog live session
     * ([ServeCatalogLiveHost]), whose carried daemon re-renders author-declared knob edits on
     * demand. Drives whether the declared knob controls are live (an edit re-renders via `/render`)
     * or disabled + informational. Defaults to [canApplyOverrides] so plain daemon / static
     * sessions are unchanged.
     */
    canRenderOverrides: Boolean = canApplyOverrides,
    /**
     * The override params THIS REQUEST carried (`knob.<key>`, `rc.<name>`), already filtered to the
     * render lane's own keys and normalised the way the page's links are (`requestOverrideParams`).
     *
     * Seeds the declared-knob controls, so a deep link opens with its values already on them. The
     * page's snapshot `<img>` has always carried the query; the CONTROLS did not, and everything
     * downstream reads the controls — the live socket's `setOverrides`, the export links, the next
     * `/render`. `hydrateFromUrl` re-applies the same params client-side on load, so this is the
     * server half of a restore the viewer already performs, not a second source of truth.
     *
     * Empty for a plain visit, which is exactly the previous behaviour.
     */
    requestOverrides: Map<String, String> = emptyMap(),
    /**
     * The override axes this request named that the page **withheld** from its controls
     * (`knob.<key>` / `rc.<name>`) — the complement of [requestOverrides] over what the URL asked
     * for.
     *
     * Published on the root as `data-unseeded-overrides`, because the server's decision is only
     * half of it: `hydrateFromUrl` restores every control from `location.search` on load and on
     * Back/Forward, so without being told it would put the withheld value straight back and the
     * markup's honesty would last one frame. The viewer reads this and defers to the declaration
     * for exactly these keys (`viewer/overrideSeeds.ts`).
     *
     * Empty for the ordinary page, where everything the URL names is seedable.
     */
    unseededOverrides: Set<String> = emptySet(),
    /**
     * Whether the session can export a `compose/figma-svg` for its previews (a daemon-backed host
     * or a catalog that carried baked vectors). Drives whether the copyable-links panel offers an
     * SVG download URL alongside the PNG one. Defaults to false (a plain bundle has no SVG lane).
     */
    hasSvgExport: Boolean = false,
    /** Whether the full-page raster/vector scroll export is available for this preview. */
    hasScrollExport: Boolean = false,
    /** Hydrated self-contained per-preview bundle download, when the server can provide one. */
    executableBundleHref: String? = null,
    /**
     * Whether this session can produce the accessibility data products the viewer's **Accessibility
     * inspection layer** draws from (`a11y/hierarchy`, plus ATF findings / touch targets where the
     * backend has them) — [ServeHost.hasA11yOverlay]. False ⇒ the layer's checkbox is omitted
     * rather than offered dead. Replaces the old daemon-composited "Accessibility (TalkBack)"
     * overlay, which baked one focus ring and its spoken text into the pixels.
     */
    hasA11yOverlay: Boolean = false,
    /**
     * Whether this session can derive the **Typography**, **Theme attributes** and **Layout boxes**
     * inspection layers from a render's `compose/semantics` tree
     * ([ServeHost.hasDesignAnnotations]). Same box + legend surface as the accessibility layer, and
     * the same reason for the gate: a static bundle has no daemon to capture the tree.
     */
    hasDesignAnnotations: Boolean = false,
    /**
     * Whether the catalog **published** typography annotations over this preview's baked frame
     * ([ServeHost.hasPublishedTypographyFor]) — the other lane behind the same Typography layer,
     * and the only one a static bundle has. Offers the checkbox where [hasDesignAnnotations] is
     * false but `.annotations` still answers; the Theme attributes and Layout boxes rows stay gated
     * on the semantics lane, which is the only thing that produces them.
     */
    hasPublishedTypography: Boolean = false,
    trust: String? = null,
    /**
     * Whether this preview carries a captured Remote Compose document
     * ([ServeHost.hasRemoteComposeDoc]) the viewer can render client-side in its `<canvas>` lane.
     * When true the viewer adds the "RC (browser)" toggle + `#cp-rc-canvas`: it loads the vendored
     * player (`/rc-player/bundle.js`), fetches `/render/<id>.rc`, and paints the document in the
     * browser with no daemon — and Remote Compose knob edits apply live via `setNamed*Override` +
     * `repaint()` instead of a server round-trip. Defaults false (no doc ⇒ no canvas lane, knobs
     * stay daemon-routed).
     */
    hasRemoteComposeDoc: Boolean = false,
    /**
     * Whether a server render of this preview **replays a captured document** rather than
     * re-running the composable — the same host question ([ServeHost.hasRemoteComposeDoc])
     * `ServeHttpServer.droppedOverridesFor` asks before reporting an override un-applied. Emitted
     * as `data-ir-replay` so the viewer can grey out the controls the server would answer with a
     * 409, instead of offering a slider that only produces an error.
     *
     * Deliberately its own flag rather than reusing `data-has-rc-doc`, even though the two coincide
     * on every host today: that one means "there are `.rc` bytes for the browser canvas lane", this
     * one means "the daemon cannot recompose this preview". Keeping them separate is what stops a
     * future host that serves a document for a class-backed preview from greying live controls.
     *
     * Note this covers a *narrow* set — see the `irReplay` block in `viewer.js`. Day/Night and font
     * scale stay live, because a document can defer both to the host and resolve them at paint
     * time.
     */
    irReplay: Boolean = false,
    /**
     * Whether a declared theme can still be applied to this preview **despite** [irReplay] — the
     * session publishes the theme's colours as named values (`ServeHost.themeReplayColors`), which
     * the player rewrites on a replayed document with no recomposition.
     *
     * Its own flag rather than a softening of [irReplay], because the two say different things and
     * only one of them moves: everything else [irReplay] greys out — locale, author knobs, string
     * `rc.` seeds — still cannot be honoured by a replay. Emitted as `data-replay-themes` so
     * `viewer.js` re-enables exactly the provider-theme options and nothing beside them.
     */
    replayThemes: Boolean = false,
    /**
     * The Remote Compose render backends the viewer may offer for this preview as a per-preview
     * **backend selector** — the [RcPlayerBackend.wire] ids the host reports via
     * [ServeHost.enabledRcPlayersFor]. Non-empty for a Remote Compose preview: the viewer renders
     * one chip per [RcPlayerBackend.UNIVERSE] entry, enables those in this list, and disables the
     * rest. The `js` chip drives the client-side `<canvas>` lane (so [hasRemoteComposeDoc] is what
     * carries the doc for it), while `java` / `cmp-android` re-render through the Android daemon
     * and `cmp-jvm` through its isolated desktop-player subprocess. Empty ⇒ no selector at all (not
     * a Remote Compose preview).
     */
    enabledRcPlayers: List<String> = emptyList(),
    wasmSrc: String? = null,
    /**
     * Whether the Wasm iframe may run with `allow-same-origin` (real origin) rather than the
     * opaque-origin `allow-scripts`-only sandbox. True ONLY for a **trusted** catalog's app —
     * unverified catalog-provided Wasm stays opaque so it can't reach the parent viewer's tokened
     * URLs / DOM. Defaults to false (fail-closed). See the `wasmFrame` sandbox note.
     */
    wasmSameOrigin: Boolean = false,
    /**
     * URL prefix for this session's links (`/<system>` when served under a path, empty otherwise).
     * The "← previews" link is prefixed with it; the viewer's own `/render` + `/ws` requests derive
     * their prefix from `location.pathname` at runtime, so they work under either mount. Empty ⇒
     * links are exactly as before.
     */
    basePath: String = "",
    /**
     * Public mode: drop the `token=` param from the server-rendered "← previews" link (every route
     * is open, so the token gates nothing). The viewer's own `/render` + `/ws` requests read the
     * token from the page URL at runtime, so they're naturally token-free too when the page arrived
     * without one. Off by default so a token-gated box keeps the token in links.
     */
    isPublic: Boolean = false,
    /**
     * Label for the corner "backend" badge while showing the baked snapshot — the renderer that
     * produced the PNG (e.g. `Android` for the design catalogs). The in-browser Wasm tier always
     * reads `CMP-WASM`; the daemon stream reads [liveBackend]. Null ⇒ a generic `Snapshot`.
     */
    snapshotBackend: String? = null,
    /**
     * Label for the badge while the daemon **live stream** drives the stage — the serving daemon's
     * platform, since a live session can be desktop/JVM **or** Android (a `RobolectricHost` streams
     * `BackendKind.ANDROID`), so it must come from the server, not a hard-coded tier name. Null ⇒ a
     * generic `Live`.
     */
    liveBackend: String? = null,
    /**
     * The app's declared `@ThemeCatalog` themes (module-global). When non-empty, the viewer adds an
     * "App theme" selector whose options re-render the preview under the chosen provider (the
     * `themeProvider` override) — daemon-only, so it's enabled exactly when a knob edit would be
     * (`canApplyOverrides || canRenderOverrides`). Empty ⇒ no selector (a static bundle, or a
     * module that declares none).
     */
    declaredThemes: List<ServeTheme> = emptyList(),
    /**
     * Whether this session's daemon can apply the one-handed **gesture** override (Android backend
     * only). Gates the "Show gesture hints" control, which is otherwise offered for a
     * `@GestureHintPreview`-detected preview — a desktop-backed session ignores the override, so
     * the control is omitted there rather than shown dead. Defaults false.
     */
    gesturesRenderable: Boolean = false,
    /**
     * The session's other previews, used to populate the left-hand **component nav** drawer (each
     * links to its own viewer page). Typically the whole `renderHost.previews` list including
     * [preview] itself — the current one is marked `aria-current` and never filtered out. When the
     * list holds no preview *other than* [preview] (empty, or a single-preview module's one entry)
     * the drawer and its toggle are omitted — there is nothing to navigate between.
     */
    siblings: List<ServePreview> = emptyList(),
    /**
     * The catalog's declared stage surface (`catalog.json`'s `display.surface`) — decides whether
     * an unthemed preview's stage backs on dark. Null ⇒ the system-name dark-first heuristic.
     */
    declaredSurface: String? = null,
    /**
     * The served catalog's own palette as an inline `:root` override for the chrome's custom
     * properties, built by [ServeThemeCss] from the branch's `tokens.dtcg.json`. Empty ⇒ the page
     * keeps the built-in chrome (a plain module, or a catalog that publishes no tokens).
     */
    themeCss: String = "",
    /**
     * Why this session is snapshot-only, when it is (no live bundle, unverified, …). When
     * non-empty, a banner under the header explains the catalog-level reason — complementing the
     * per-control `cp-note` (which explains what each override needs). Empty ⇒ no banner. See
     * [degradeBanner].
     */
    degradations: List<ServeDegradation> = emptyList(),
    /** Engagement count for this preview on the running server. */
    engagement: PreviewEngagement = PreviewEngagement(),
    /** Absolute viewer + PNG URLs for Open Graph/Twitter link previews. */
    unfurl: UnfurlMetadata? = null,
    /**
     * Running server version (`SERVE_VERSION`), shown in the minimal footer. Null omits the build
     * span.
     */
    version: String? = null,
    /**
     * Fully-formed GitHub link to this preview's source file, when it resolves — the caller builds
     * it from the session's delivery provenance (repo + branch) and the preview's `sourceFile` via
     * [ServeUrls.githubBlobUrl]. When non-null the header shows a "source" link beside the preview
     * label; null (a local session with no provenance, or a preview with no recorded source)
     * renders no link, matching how the footer/landing source links depend on a known repo.
     */
    sourceHref: String? = null,
    /**
     * Prefilled GitHub new-issue link for this preview, built by the caller from the session's
     * catalog source/provenance via [ServeIssueReport]. Null omits the affordance entirely (a
     * surface with nothing sensible to file against); see [reportIssueHtml].
     */
    reportIssue: ReportIssue? = null,
    /**
     * The Figma node this preview is specified by, when the served catalog publishes a Figma-backed
     * design reference for it (see [ServeFigmaSpec]). Null — every catalog that names none — omits
     * the affordance entirely rather than offering a guessed or dead link.
     */
    figmaSpec: FigmaSpec? = null,
    /**
     * The design reference this preview is specified by — the imported spec design-parity published
     * into `references/index.json` (see [ServeDesignReferenceStore]) — when the served catalog
     * carries one for this exact preview id.
     *
     * Present ⇒ the viewer offers a **Spec lane** beside the renderer chips: the same chip row that
     * chooses which Remote Compose player draws the stage also offers the imported spec, so the
     * visitor can flip between what the code renders and what the design says without leaving the
     * page (and step into the focused Reference/Diff/Actual comparison from the same group).
     *
     * The raster is the catalog's own canonical, inert PNG, served from this server's
     * `/reference/<id>.png` — nothing is fetched from Figma, here or anywhere else in `serve`. Null
     * (every catalog that has not adopted design-parity) omits the lane entirely.
     */
    designReference: DesignReference? = null,
    /**
     * The counterpart component's render in the `compareWith` sibling system, when this catalog
     * declares a pairing, the sibling is served on THIS host, and the counterpart has a render
     * (issue #4621). Offered as a second SOURCE for the spec lane, not a second mode — see
     * [SpecSource].
     *
     * Same origin as everything else the lane paints: the sibling is another catalog on this
     * server, so its render is `/<sibling>/render/<id>.png` and needs no cross-repo fetch and no
     * thumbnails baked at publish time. Null whenever any link of that chain is missing, which
     * omits the picker and leaves the lane exactly as it was.
     */
    parallelSource: SpecSource? = null,
    /**
     * Typography/layout facts captured from [designReference]'s own raster. The default Figma lane
     * draws these over the spec image; Diff/Triptych pair them with the current render and reduce
     * the legend to changed typography only.
     */
    referenceAnnotations: List<DesignAnnotation> = emptyList(),
    /**
     * `/playground?from=…` for this preview — opens its Kotlin in the editor against the catalog it
     * came from. Null on a host with no playground lane, or for a preview whose source path the
     * catalog never recorded; the affordance is then omitted rather than offered dead.
     */
    playgroundHref: String? = null,
    /**
     * `/usage/<id>` for this preview — the plain-Compose usage code the **Source** chip shows, or
     * null when this host cannot derive one and the chip is omitted rather than offered dead.
     *
     * A URL, not the snippet: the panel fetches it on first press, so a visitor who never opens the
     * panel costs the host nothing (deriving a snippet is a GitHub read on a cold cache).
     *
     * Deliberately independent of [playgroundHref]. Reading the code is useful wherever a catalog
     * can be browsed; only *running* it needs a host that can compile that catalog, which most of
     * the public deployment's catalogs have none. So a preview commonly offers Source without
     * offering the playground, and the panel links onward to the editor only when there is one.
     */
    usageHref: String? = null,
    /** GitHub sign-in prompt shown when the daemon live stream is present but requires auth. */
    liveAuthPrompt: LiveAuthPrompt? = null,
    /** Human catalog title used in the breadcrumb; falls back to a generic "Previews" label. */
    catalogTitle: String? = null,
    /**
     * `POST` URL that keeps this session (and its daemon) alive while the visitor has the viewer
     * open — see [presenceScript]. The viewer needs this at least as much as the grid does: it is
     * where someone settles on one preview, and where the theme and knob actions that *need* a warm
     * daemon are taken. Empty (the default) omits the heartbeat.
     */
    presenceUrl: String = "",
    /**
     * `history.json` on the delivery branch, or null when there is no delivery provenance (an
     * uploaded bundle, a local project). Null omits the timeline entirely rather than shipping a
     * control that can only fail — see [ServeUrls.historyManifestUrl].
     */
    historyManifestUrl: String? = null,
    /**
     * `owner/repo` of the delivery branch, used to address a historical render by commit sha.
     * Paired with [historyManifestUrl]: both or neither, since a timeline you cannot click through
     * to is not worth drawing.
     */
    historyRepo: String? = null,
    /**
     * A manifest payload inlined into the page instead of fetched. Exists so a fixture (and any
     * offline viewer) renders the timeline without reaching raw.githubusercontent.com — without it
     * the preview-harness capture is byte-identical whether the strip works or is deleted, which is
     * no coverage at all.
     */
    historyInlineJson: String? = null,
    /**
     * A `/api/render-runs` payload inlined into the revision menu instead of fetched, for exactly
     * the reason [historyInlineJson] exists: the run markers are drawn client-side from that lane,
     * so a harness capture would look the same whether they work or the feature is gone.
     */
    revisionRunsInlineJson: String? = null,
    /**
     * Project mode: the timeline was computed from the local repository ([ServeProjectHistory]), so
     * its entries link at this server's own `/history/render/<blob>.png` rather than at
     * raw.githubusercontent.com — a local checkout has no such URL. Also tells the viewer the strip
     * describes *published baselines* rather than the stage, which in project mode is rendered from
     * the working tree and so need not match the newest entry.
     *
     * Only honoured alongside [historyInlineJson]: with no payload there is nothing to link.
     */
    historyLocalRenders: Boolean = false,
    /**
     * The catalog's published revisions and which one this page is pinned to ([CatalogRevisions]).
     *
     * A pin makes the viewer a **reader of one publish**: the stage shows that revision's baked
     * pixels and every control that would re-render is refused, because the daemon renders today's
     * code and answering a request for the past with the present is precisely the failure a
     * permalink exists to prevent. Empty ⇒ the viewer behaves exactly as it always has.
     */
    revisions: CatalogRevisions = CatalogRevisions.NONE,
    /**
     * Render overrides already present on the viewer URL, without a leading `?`. Revision links
     * carry these between publishes so choosing a revision does not silently reset the selected
     * theme (or any other explicit render state). The pin itself is added separately.
     */
    revisionQuery: String = "",
    /**
     * Whether this page is served as a **top-level site** ([ServeSites]) — its catalog rooted on a
     * hostname of its own. The session is then implied by the ORIGIN, exactly as a `/<system>`
     * mount implies it by the path, so same-session links must not repeat it as `?session=`. False
     * (the default) leaves every existing caller's URLs byte-identical.
     */
    sessionInOrigin: Boolean = false,
    parityIssues: List<ParityIssue> = emptyList(),
    componentBrowser: Boolean = false,
    /**
     * The catalog change feed the footer offers as **Changelog** and the head declares as this
     * page's RSS alternate. Empty when the server runs with the feed lane off. See [siteFooter].
     */
    changelogHref: String = "",
  ): String {
    @Suppress("NAME_SHADOWING")
    val designReference = designReference?.takeUnless { componentBrowser }
    @Suppress("NAME_SHADOWING") val sourceHref = sourceHref?.takeUnless { componentBrowser }
    @Suppress("NAME_SHADOWING") val reportIssue = reportIssue?.takeUnless { componentBrowser }
    @Suppress("NAME_SHADOWING") val figmaSpec = figmaSpec?.takeUnless { componentBrowser }
    @Suppress("NAME_SHADOWING") val playgroundHref = playgroundHref?.takeUnless { componentBrowser }
    @Suppress("NAME_SHADOWING")
    val historyManifestUrl = historyManifestUrl?.takeUnless { componentBrowser }
    @Suppress("NAME_SHADOWING") val historyRepo = historyRepo?.takeUnless { componentBrowser }
    @Suppress("NAME_SHADOWING")
    val historyInlineJson = historyInlineJson?.takeUnless { componentBrowser }
    @Suppress("NAME_SHADOWING")
    val revisions = if (componentBrowser) CatalogRevisions.NONE else revisions
    @Suppress("NAME_SHADOWING")
    val parityIssues = if (componentBrowser) emptyList() else parityIssues
    @Suppress("NAME_SHADOWING")
    val degradations = if (componentBrowser) emptyList() else degradations
    // The session id links may carry. Null on a rooted site (and for the default session): the
    // URL already says which catalog this is. `sessionId` itself stays intact below — it keys the
    // per-catalog localStorage entries and the dark-first lookup, which a site still needs.
    val linkSessionId = if (sessionInOrigin) null else sessionId
    val idSeg = WebEscaping.urlEncodeSegment(preview.id)
    // A pin turns off every lane that would *produce* something, for one reason that covers all of
    // them: they run the catalog's current code. A knob edit, a declared theme, a live stream, the
    // in-browser Wasm app, the SVG export, a Remote Compose player, the inspection layers, a
    // full-page scroll capture, the downloadable bundle — each would answer a request for an old
    // publish with today's output, under a URL whose entire promise is that it cannot change.
    //
    // The line is "produced on demand" vs. "published bytes", not "interactive" vs. "static": a
    // baked PNG and a published design reference are both files on the branch at that commit, so
    // both pin (see `specRasterUrl` below, which takes the pin rather than being dropped). An SVG
    // is not — it is exported by the daemon per request — so it goes, however static it looks.
    //
    // The names are shadowed rather than threaded through the hundred-odd uses below so the rule
    // holds by construction: there is no path through this function where a pinned page reads the
    // un-pinned flag.
    val pinned = revisions.pinned
    // Remember capabilities before the pin suppresses their current-code implementations. A
    // pinned toolbar keeps these controls visible but disabled, explaining why the same component
    // has the feature at Current and cannot run it against historical bytes.
    val currentHasSvgExport = hasSvgExport
    @Suppress("NAME_SHADOWING") val canApplyOverrides = canApplyOverrides && pinned == null
    @Suppress("NAME_SHADOWING") val canRenderOverrides = canRenderOverrides && pinned == null
    @Suppress("NAME_SHADOWING")
    val hasLiveStream = hasLiveStream && pinned == null && !componentBrowser
    @Suppress("NAME_SHADOWING") val wasmSrc = wasmSrc?.takeIf { pinned == null }
    @Suppress("NAME_SHADOWING") val hasSvgExport = hasSvgExport && pinned == null
    @Suppress("NAME_SHADOWING")
    val hasScrollExport = hasScrollExport && pinned == null && !componentBrowser
    // Catalog mode keeps the Remote Compose facet whole — the `.rc` canvas and every player the
    // host offers, embedded included — rather than stripping it with the rest of the dev surface.
    //
    // It used to come off with `!componentBrowser`, and the cost was a broken link: with no canvas,
    // no chips and no lane select, nothing on the page owned the `rcPlayer` parameter, so
    // `url-state.js` cleared it from the address bar and a shared `?rcPlayer=…` silently became an
    // ordinary baked snapshot. Which player drew a document is the *subject* of a Remote Compose
    // catalog, not an operational detail, so a catalog reader is exactly who wants to switch
    // between them — and the lane a preview opens on stays the embedded player here as it is in
    // Dev, rather than the two modes disagreeing about what the default rendering of a document is.
    @Suppress("NAME_SHADOWING") val hasRemoteComposeDoc = hasRemoteComposeDoc && pinned == null
    @Suppress("NAME_SHADOWING")
    val enabledRcPlayers = if (pinned == null) enabledRcPlayers else emptyList()
    @Suppress("NAME_SHADOWING")
    val hasA11yOverlay = hasA11yOverlay && pinned == null && !componentBrowser
    @Suppress("NAME_SHADOWING")
    val hasDesignAnnotations = hasDesignAnnotations && pinned == null && !componentBrowser
    @Suppress("NAME_SHADOWING")
    val hasPublishedTypography = hasPublishedTypography && pinned == null && !componentBrowser
    // A published capture is a file on the branch exactly as the baked render and the design
    // reference are, so by the pinned-page rule it ought to STAY and take the pin. It cannot yet:
    // `/motion/<id><ext>` reads the branch tip the session is holding, with no revision to resolve
    // against. The stronger half of that rule — "a pinned request is never answered with current
    // bytes" — decides it, so the lane comes off a pinned page entirely rather than playing today's
    // recording beside a render from another commit. See docs/public-preview-server.md; when the
    // route learns to resolve a capture at a revision this becomes `withPin` like the reference.
    val motionCaptures = if (pinned == null) preview.motion else emptyList()
    @Suppress("NAME_SHADOWING")
    val executableBundleHref = executableBundleHref?.takeIf { pinned == null && !componentBrowser }
    val q = querySuffix(linkQuery(token, linkSessionId, basePath, isPublic))
    val navSuffix =
      querySuffix(if (isPublic) "" else "token=" + WebEscaping.urlEncodeSegment(token))
    val displayName = previewDisplayName(preview)
    val issueRows = if (componentBrowser) "" else parityIssueRowsHtml(parityIssues)
    val label = WebEscaping.htmlEscape(displayName)
    val idText = WebEscaping.htmlEscape(preview.id)
    val modes = preview.modes.joinToString(",") { it.wire }
    // Wear OS is an always-dark surface. Do not expose the generic day/night override: besides
    // being meaningless for Wear, an old light choice within the Wear catalog must not turn into a
    // confetti-wear live render.
    val wearAlwaysDark = SystemDisplay.isDarkFirst(basePath.trim('/').ifBlank { sessionId ?: "" })
    val alwaysDarkAttr = if (wearAlwaysDark) " data-always-dark=\"1\"" else ""
    val irReplayAttr = if (irReplay) " data-ir-replay=\"1\"" else ""
    val replayThemesAttr = if (replayThemes) " data-replay-themes=\"1\"" else ""
    // The baked fallback shown before any override is chosen. The unified Theme selector displays
    // this choice without sending a redundant uiMode override on first load.
    val viewerDarkFirst = isDarkFirstSystem(basePath, sessionId, declaredSurface)
    val viewerTheme = previewTheme(preview, viewerDarkFirst)
    // The Wasm tier is opt-in via a toggle (like "Live (stream)"), so the always-works PNG snapshot
    // stays the default. Both the iframe and the toggle are omitted entirely when no Wasm app backs
    // this session.
    val wasmAttr =
      if (wasmSrc != null) " data-wasm-src=\"${WebEscaping.htmlEscape(wasmSrc)}\"" else ""
    // `allow-same-origin` (alongside `allow-scripts`) is granted ONLY for a [wasmSameOrigin]
    // (trusted-catalog) app. That app is our own compiled catalog, served same-origin from this
    // box's `/wasm/<system>/`, so it isn't hostile content the opaque origin needs to wall off, and
    // the real origin stops the storage/history APIs the Kotlin/Wasm + Compose runtime touches
    // (`window.caches` via `supportsCacheApi`, history.pushState, …) from throwing `SecurityError`
    // in an opaque origin (console spam on every Wasm render), and lets Compose's resource loader
    // use the Cache API. An UNTRUSTED catalog's Wasm app stays opaque (`allow-scripts` only): the
    // `/wasm/` route serves an unverified catalog's app too, and same-origin there would let it
    // read
    // the parent viewer's tokened URLs / DOM or remove its own sandbox. `data-wasm-src` is
    // additionally same-origin-checked before it reaches the frame (see wasmBaseSrc).
    val wasmSandbox = if (wasmSameOrigin) "allow-scripts allow-same-origin" else "allow-scripts"
    val wasmFrame =
      if (wasmSrc != null)
        "<iframe id=\"cp-wasm\" hidden sandbox=\"$wasmSandbox\" title=\"$label (Wasm)\"></iframe>"
      else ""
    // The render mode is a single Static⇄Live toggle now, not a radio row. Behind it sit the mode
    // radios the transport JS still drives (`cp-mode-png` = static snapshot, `cp-live` = daemon
    // stream, `cp-wasm-toggle` = in-browser Wasm) — kept in the DOM but visually removed. SVG is no
    // longer an on-screen mode; it's an export format in the Direct-links group. The Wasm radio is
    // present only when a Wasm app backs the session.
    val wasmModeInput =
      if (wasmSrc != null)
        "<input type=\"radio\" name=\"cp-mode\" value=\"wasm\" id=\"cp-wasm-toggle\" tabindex=\"-1\">"
      else ""
    // The SVG format toggle — swaps the static snapshot between the raster PNG and the vector SVG
    // render. Offered only when the session can export SVG ([hasSvgExport]), the same gate as the
    // SVG direct-link row.
    val svgFmtToggle =
      if (currentHasSvgExport && !componentBrowser) {
        val availability =
          if (pinned == null) " title=\"Show the vector (SVG) render\""
          else
            " disabled aria-describedby=\"cp-pinned-controls-note\"" +
              " title=\"Pinned revision — SVG is generated from the current catalog\""
        val button =
          "<button type=\"button\" id=\"cp-svg-toggle\" class=\"cp-fmt-toggle\" " +
            "aria-pressed=\"false\"$availability>SVG</button>"
        if (pinned == null) button
        else
          "<span class=\"cp-disabled-control\" tabindex=\"0\" " +
            "aria-describedby=\"cp-pinned-controls-note\">$button</span>"
      } else ""
    // The exploded 3D toggle — the layered figma-svg tilted back and pulled apart into one sheet
    // per visible drawing level ([ExplodedSvg]). It sits beside the SVG toggle because it is
    // a view *of* that export rather than a separate renderer lane, and is gated on the same
    // per-preview [hasSvgExport]: with no layered export there is nothing to pull apart, so the
    // control is omitted rather than offered dead.
    val explodeToggle =
      if (currentHasSvgExport && !componentBrowser) {
        val availability =
          if (pinned == null) " title=\"Show how the visible drawing layers are composed\""
          else
            " disabled aria-describedby=\"cp-pinned-controls-note\"" +
              " title=\"Pinned revision — 3D is generated from the current catalog\""
        val button =
          "<button type=\"button\" id=\"cp-explode-toggle\" class=\"cp-fmt-toggle\" " +
            "aria-pressed=\"false\"$availability>3D</button>"
        if (pinned == null) button
        else
          "<span class=\"cp-disabled-control\" tabindex=\"0\" " +
            "aria-describedby=\"cp-pinned-controls-note\">$button</span>"
      } else ""
    val svgMatch =
      if (hasSvgExport && !componentBrowser) {
        val compareQuery =
          listOf(
              "format=svg",
              "preview=${WebEscaping.urlEncodeSegment(preview.id)}",
              linkQuery(token, linkSessionId, basePath, isPublic),
            )
            .filter { it.isNotEmpty() }
            .joinToString("&")
        "<span id=\"cp-svg-match\" class=\"cp-match\" role=\"status\" aria-live=\"polite\" hidden></span>" +
          "<a id=\"cp-svg-diff\" class=\"cp-format-link\" href=\"$basePath/compare?$compareQuery\" hidden>view diff →</a>"
      } else ""
    // The in-browser Remote Compose canvas lane. Offered (a `#cp-rc-canvas`, a hidden mode radio,
    // and
    // a toggle button) only when this preview carries a captured `.rc` document
    // ([hasRemoteComposeDoc]): the client loads the vendored player and paints the document with no
    // daemon. `data-has-rc-doc` flags the page so the transport JS wires the lane; the doc + player
    // URLs are built at runtime (the doc from the same `base` as the snapshot, the player from the
    // constant `/rc-player/bundle.js`). Reuses `.cp-live-toggle` styling so it reads as a peer of
    // the
    // Live / Wasm toggles.
    val rcAttr = if (hasRemoteComposeDoc) " data-has-rc-doc=\"1\"" else ""
    val rcCanvas = if (hasRemoteComposeDoc) "<canvas id=\"cp-rc-canvas\" hidden></canvas>" else ""
    val hasRcWasm = RcPlayerBackend.CMP_WASM.wire in enabledRcPlayers
    val rcWasmFrame =
      if (hasRcWasm)
        "<iframe id=\"cp-rc-wasm\" hidden sandbox=\"allow-scripts allow-same-origin\" " +
          "title=\"$label (Remote Compose CMP Wasm)\"></iframe>"
      else ""
    val rcModeInput =
      if (hasRemoteComposeDoc)
        "<input type=\"radio\" name=\"cp-mode\" value=\"rc\" id=\"cp-rc-toggle\" tabindex=\"-1\">"
      else ""
    val rcWasmModeInput =
      if (hasRcWasm)
        "<input type=\"radio\" name=\"cp-mode\" value=\"rc-wasm\" id=\"cp-rc-wasm-toggle\" tabindex=\"-1\">"
      else ""
    // The **Spec lane**: the imported design reference for this exact preview, offered as one more
    // entry in the renderer picker. Where the other lanes choose *which player draws the code*,
    // this chooses to look at *what the design says* instead — the catalog's own inert PNG, from
    // `/reference/<id>.png` (never fetched from Figma), swapped onto the same stage. Rendered only
    // when the catalog published a reference for this preview id, i.e. only when design-parity is
    // configured for the system; every other catalog's viewer is byte-identical to before.
    val specLabel = designReference?.let { it.label.takeIf { l -> l.isNotBlank() } ?: it.id }
    val specProviderLabel =
      when (designReference?.source?.provider?.trim()?.lowercase()) {
        "figma" -> "Figma"
        null -> null
        else -> "Spec"
      }
    // Pinned, not dropped: a design reference is a published file on the delivery branch like the
    // baked render is, so the spec lane is one of the few produced-on-demand-looking surfaces that
    // genuinely has a historical answer. Comparing this publish's render against this publish's
    // spec is also the comparison a pinned page is *for*.
    val specRasterUrl = designReference?.let {
      "$basePath/reference/${WebEscaping.urlEncodeSegment(it.id)}.png${withPin(q, pinned)}"
    }
    // The focused Reference / Diff / Actual page for this exact mapping — the same link the
    // comparison grid offers, so the picker's neighbour steps from "look at the spec" to "diff it".
    val specCompareHref = designReference?.let { reference ->
      val query =
        listOf(
            linkQuery(token, linkSessionId, basePath, isPublic),
            "reference=${WebEscaping.urlEncodeSegment(reference.id)}",
          )
          .filter { it.isNotEmpty() }
          .joinToString("&")
      // Carries the pin, so stepping out to the focused comparison keeps the publish you were
      // reading rather than silently landing on the live one.
      withPin("$basePath/compare/$idSeg${querySuffix(query)}", pinned)
    }
    // The four ways to look at the render/spec pair, offered on the stage itself the moment the
    // spec lane is up. The lane used to be a flip — spec on the stage instead of the render — which
    // answers "are these different?" only by asking the eye to hold one frame while looking at the
    // other. That finds a wholesale colour change and misses the 4dp of padding that is the actual
    // bug. The focused `/compare/<id>` page has always had the real instruments, but reaching it
    // means leaving the viewer, and with it the overrides, knobs and theme that produced the render
    // worth comparing. So the instruments come to the lane. `triptych` is the default (#4376): the
    // lane is entered to ask how the two compare, and side-by-side answers that on arrival, while
    // `spec` — the reference alone, the way the lane used to open — is one click away.
    val specViews =
      listOf(
        "spec" to ("Spec" to "The imported design reference on its own"),
        "diff" to ("Diff" to "Highlight every pixel where the render and the spec disagree"),
        "triptych" to ("Triptych" to "Spec, diff and render side by side"),
        "slider" to ("Slider" to "One frame, wiped between the spec and the render"),
      )
    // The spec lane's *carrier*, not a control: `data-spec-src` is the raster viewer.js paints onto
    // the stage when the lane is entered, the comparison group beside it chooses how that pair is
    // drawn, and the trailing link is the step out to the focused comparison page. Entering the
    // lane is [specChipHtml]'s job — a chip of its own on the bar, not an `<option>` inside the
    // renderer combo.
    // Every source the lane can compare this render against, in the order the picker offers them.
    // The kit reference leads: it is the specification, it is what the lane has always shown, and a
    // catalog with no pairing has only this one — so the default pair never moves.
    val specSources =
      listOfNotNull(
        if (specRasterUrl == null || specProviderLabel == null) null
        else
          SpecSource(
            id = "kit",
            label = specProviderLabel,
            rasterUrl = specRasterUrl,
            // No caveat to give: an imported reference is a static specification, and comparing
            // this publish's render against this publish's spec is the comparison the lane is for.
            provenance = "",
          ),
        parallelSource,
      )
    val specSelector =
      if (specRasterUrl == null || specProviderLabel == null || specLabel == null) ""
      else {
        val tip = "Compare this render against the imported design spec — $specLabel"
        // Hidden until the lane is entered: while a render is on the stage there is no pair to
        // compare, and a control that acts on nothing is worse than no control. `<cp-spec-compare>`
        // reveals it from openSpec() and hides it again on the way out.
        // The SOURCE picker: which pair the four views are instruments over. Emitted only when
        // there is a genuine choice — one source collapses it away entirely, so every catalog that
        // declares no `compareWith` pairing keeps exactly the lane it had.
        //
        // Each button carries its own raster and label, server-built and server-escaped like every
        // other URL the lane paints, so switching source never means reading a URL out of the DOM
        // and handing it to an image (CodeQL's `js/xss-through-dom`, and the reason `data-spec-src`
        // is set here rather than assembled in the browser).
        val sourceButtons =
          if (specSources.size < 2) ""
          else
            "<span class=\"cp-spec-sources\" id=\"cp-spec-sources\" role=\"group\" " +
              "aria-label=\"Compare against\" hidden>" +
              specSources.joinToString("") { source ->
                "<button type=\"button\" class=\"cp-spec-source\" " +
                  "data-cp-spec-source=\"${WebEscaping.htmlEscape(source.id)}\" " +
                  "data-spec-src=\"${WebEscaping.htmlEscape(source.rasterUrl)}\" " +
                  "data-spec-label=\"${WebEscaping.htmlEscape(source.label)}\" " +
                  "data-spec-provenance=\"${WebEscaping.htmlEscape(source.provenance)}\" " +
                  "aria-pressed=\"${source.id == specSources.first().id}\" " +
                  "title=\"Compare this render against ${WebEscaping.htmlEscape(source.label)}\">" +
                  "${WebEscaping.htmlEscape(source.label)}</button>"
              } +
              "</span>"
        val viewButtons =
          specViews.joinToString("") { (value, text) ->
            val (viewLabel, viewTip) = text
            "<button type=\"button\" class=\"cp-spec-view\" data-cp-spec-view=\"$value\" " +
              "aria-pressed=\"${value == SPEC_DEFAULT_VIEW}\" " +
              "title=\"${WebEscaping.htmlEscape(viewTip)}\">${WebEscaping.htmlEscape(viewLabel)}</button>"
          }
        "<span class=\"cp-spec-lane\" id=\"cp-spec-lane\" " +
          // The FIRST source's raster and label stay on these two attributes, unchanged. They are
          // what a single-source lane has always carried and what the backend badge still reads, so
          // a catalog with no pairing produces byte-identical markup to before.
          "data-spec-src=\"${WebEscaping.htmlEscape(specRasterUrl)}\" " +
          "data-spec-label=\"${WebEscaping.htmlEscape(specProviderLabel)}\">" +
          sourceButtons +
          "<span class=\"cp-spec-views\" id=\"cp-spec-views\" role=\"group\" " +
          "aria-label=\"Design comparison\" hidden>$viewButtons</span>" +
          "<span class=\"cp-spec-score\" id=\"cp-spec-score\" role=\"status\" " +
          "aria-live=\"polite\" hidden></span>" +
          "<a class=\"cp-format-link cp-spec-diff\" " +
          "href=\"${WebEscaping.htmlEscape(specCompareHref.orEmpty())}\" " +
          "title=\"${WebEscaping.htmlEscape(tip)}\">spec diff →</a></span>"
      }
    // ---- The renderer picker -------------------------------------------------------------------
    //
    // One chip plus one combo box, in place of the row of per-lane chips this page used to carry
    // (`Live preview · In-browser (Wasm) · RC: JS CMP Wasm Java CMP Android CMP JVM · Spec: Figma ·
    // SVG · static snapshot`). That row asked a visitor to read up to eight independent
    // pressed-states to answer one question — *what is drawing this?* — and grew another chip every
    // time a lane was added.
    //
    // The replacement answers it once. [laneSelectHtml] is the single control that CHOOSES the
    // renderer; the `#cp-live-toggle` chip beside it NAMES the chosen one ("Java") and toggles it
    // live/interactive, with its status dot as the live indicator. viewer.js drives both from one
    // lane value (`syncLaneSelect`), so the two can never disagree about what's on the stage.
    val rcEnabled = enabledRcPlayers.toSet()
    // The lane the viewer opens on for a Remote Compose preview: the server-side `cmp-android`
    // (embedded) player when it's available, else `java`, else the client `js` canvas.
    //
    // The payoff is the data tier rather than the pixels (#3936). `java` is
    // `AndroidView { RemoteComposePlayer }`, so a whole document reaches Compose as one interop
    // leaf: `compose/figma-svg` exports it as a single raster wearing an `.svg` extension, and the
    // semantics tree describes a black box. The embedded player emits real Compose nodes, so the
    // same document exports editable geometry and describes the card.
    //
    // The two lanes were measured over all 164 documents of the homeassistant catalog before this
    // moved (`renders/rc-embedded-lane-ab/`): 34 byte-identical, and the residual is overwhelmingly
    // text rasterization — Skia and the Android canvas hint glyphs differently, which no amount of
    // player work removes. `?rcPlayer=java` still selects the old lane for anything that needs it.
    val defaultRcBackend =
      when {
        RcPlayerBackend.CMP_ANDROID.wire in rcEnabled -> RcPlayerBackend.CMP_ANDROID.wire
        RcPlayerBackend.JAVA.wire in rcEnabled -> RcPlayerBackend.JAVA.wire
        RcPlayerBackend.JS.wire in rcEnabled -> RcPlayerBackend.JS.wire
        else -> enabledRcPlayers.firstOrNull().orEmpty()
      }
    // Every lane this preview can be drawn by, in display order: the Remote Compose players (or the
    // plain snapshot, when this isn't a Remote Compose preview), the in-browser Wasm app, and the
    // imported design spec. A player the host doesn't offer is still listed — as a disabled option,
    // the same "shown but unavailable" treatment its chip had — so the set of players stays legible
    // from any session.
    data class ViewerLane(val value: String, val label: String, val enabled: Boolean)
    val lanes = buildList {
      if (enabledRcPlayers.isEmpty()) add(ViewerLane("png", "Snapshot", true))
      else
        RcPlayerBackend.UNIVERSE.forEach { backend ->
          add(ViewerLane("rc:${backend.wire}", backend.label, backend.wire in rcEnabled))
        }
      if (wasmSrc != null) add(ViewerLane("wasm", "In browser (Wasm)", true))
    }
    // The **design-spec chip** — the imported reference, promoted OUT of the renderer combo and
    // onto
    // the row as a control of its own.
    //
    // It used to be one `<option>` among the players ("Figma spec", after five Remote Compose
    // backends and the Wasm app), which put the one lane that answers a different *question* behind
    // the same menu as the ones that answer "which engine drew this?". Very few catalogs publish
    // references at all, so on the ones that do it is the most interesting thing on the page and it
    // was the least visible. As a chip it is one click from rest, it says which tool the spec came
    // from ("Figma") instead of a generic label, and — like the Live chip beside it — its
    // `aria-pressed` reports whether the spec is currently on the stage. viewer.js drives both from
    // the same lane state, so the chip and the combo cannot disagree.
    val specChipHtml =
      if (specRasterUrl == null || specProviderLabel == null) ""
      else {
        val name = if (specProviderLabel == "Figma") "Figma" else "Design spec"
        // The **verdict**, on the chip, at rest. The catalog exists to answer "does this render
        // match its design?", and the chip that led to that answer used to say only which tool the
        // design came from — the question was one click and two raster decodes away, on every page,
        // including the ones where the answer is 57%.
        //
        // One page, one number: a preview has one render and one reference, so there is exactly one
        // score to state. It is always printed rather than hidden behind a "clean" threshold — a
        // number that is usually high is still the thing a reader came for, and suppressing it
        // would make its absence ambiguous with "not scored". The BAND only picks the colour, so a
        // quiet 99.7% and a loud 85.8% read differently without either being hidden.
        val match = designReference.match
        val band = match?.let { specMatchBand(it.percent) }
        val label = if (match == null) name else "$name ${WebEscaping.formatPercent(match.percent)}"
        val tip =
          if (match == null)
            "Put the imported $specProviderLabel spec on the stage instead of the render"
          else
            buildString {
              append("${WebEscaping.formatPercent(match.percent)} match against the imported ")
              append("$specProviderLabel spec")
              match.changedPercent?.let {
                append(" · ${WebEscaping.formatPercent(it, 2)} pixels differ")
              }
              match.geometry?.let {
                append(" · ${WebEscaping.formatPercent(it, 1)} proportion difference")
              }
              append(" — click to see where")
            }
        val bandAttr = band?.let { " data-spec-match=\"$it\"" } ?: ""
        // What the chip says once the render has moved OFF the snapshot the verdict was measured
        // against. The baked number is taken against the catalog's own render — default theme,
        // declared knob defaults — while the imported spec is exported once and never re-exported
        // per theme. So a visitor who picks a theme changes one side of the comparison and not the
        // other, and the published number goes on describing a frame that has left the stage. It
        // does not merely go stale, it goes generous: a 99.6% chip over a render the spec lane
        // scores at 88.9% reads as the lane being broken rather than as the chip being out of date.
        // viewer.js publishes the baseline (`data-spec-baseline`) and `<cp-spec-compare>` swaps to
        // this label until it has a live measurement to put there instead.
        val staleTip =
          "The published match is measured against this catalog's default render — " +
            "click to compare the $specProviderLabel spec against what's on the stage now"
        val staleTipAttr =
          if (match == null) ""
          else " data-spec-chip-stale-tip=\"${WebEscaping.htmlEscape(staleTip)}\""
        "<button type=\"button\" id=\"cp-spec-chip\" class=\"cp-spec-chip\"$bandAttr " +
          "aria-pressed=\"false\" data-spec-chip-label=\"${WebEscaping.htmlEscape(label)}\" " +
          "data-spec-chip-name=\"${WebEscaping.htmlEscape(name)}\" " +
          "data-spec-chip-tip=\"${WebEscaping.htmlEscape(tip)}\"$staleTipAttr " +
          "title=\"${WebEscaping.htmlEscape(tip)}\">${WebEscaping.htmlEscape(label)}</button>"
      }
    val sourceKnown = !usageHref.isNullOrBlank()
    val usageAvailable = sourceKnown && pinned == null
    // The **Source chip** — the usage code behind this card, on the same row and for the same
    // reason the design-spec chip is there rather than inside the renderer combo: that combo is
    // headed "Switch renderer", and source is not a renderer. It answers a third question again,
    // beside "which engine drew this?" (the combo) and "what was it specified as?" (the spec chip):
    // *what do I type to get this?*
    //
    // Offered whenever this host can resolve a preview's source at all. It is deliberately NOT
    // gated on the playground being able to compile the catalog — reading the code is useful on
    // every host that can browse one, and most of the public deployment's catalogs cannot be
    // compiled here.
    val sourceChipHtml =
      if (!sourceKnown) ""
      else {
        val tip =
          if (pinned == null) "Show the plain Compose that produces this render"
          else "Pinned revision — source is only available from the current catalog"
        val tabClass = if (componentBrowser) " cp-browser-tab" else ""
        val tabAttrs = if (componentBrowser) " role=\"tab\" aria-selected=\"false\"" else ""
        val disabled =
          if (pinned == null) "" else " disabled aria-describedby=\"cp-pinned-controls-note\""
        val usageSrc =
          if (pinned == null) " data-usage-src=\"${WebEscaping.htmlEscape(usageHref)}\"" else ""
        val button =
          "<button type=\"button\" id=\"cp-source-chip\" class=\"cp-spec-chip cp-source-chip$tabClass\"$tabAttrs " +
            "aria-pressed=\"false\" aria-controls=\"cp-source-panel\" " +
            "data-source-chip-tip=\"${WebEscaping.htmlEscape(tip)}\"$usageSrc " +
            "title=\"${WebEscaping.htmlEscape(tip)}\"$disabled>Source</button>"
        if (pinned == null) button
        else
          "<span class=\"cp-disabled-control\" tabindex=\"0\" " +
            "aria-describedby=\"cp-pinned-controls-note\">$button</span>"
      }
    val browserPreviewTab =
      if (!componentBrowser || !usageAvailable) ""
      else
        "<button type=\"button\" id=\"cp-browser-preview-tab\" " +
          "class=\"cp-spec-chip cp-browser-tab\" role=\"tab\" aria-selected=\"true\">Preview</button>"
    // Catalog mode shows the **baked snapshot**, exactly like Dev mode does, and this script only
    // keeps the Preview / Source tab pair in sync with the source panel's own toggle.
    //
    // It used to force the in-browser Wasm app on every component page (`w.checked = true` at
    // parse time, plus on every return from Source), so that "interactive by default" was the
    // Catalog reading of a component browser. That was wrong twice over. It made the *snapshot*
    // — the thing the catalog publishes, and the artifact every other surface here compares
    // against — the one rendering Catalog mode never showed; and because entering an interactive
    // lane CANCELS the in-flight snapshot (viewer.js gates the bookmarked `?mode=` on the
    // snapshot having landed for exactly this reason, but a direct tick bypasses that gate), the
    // stage's <img> never got a src at all. The Wasm iframe is sized to that <img>'s box, so it
    // came up at the browser's ~104×20 alt-text placeholder and every Catalog preview looked
    // blank — with the still it was supposed to fall back to never having loaded (#4091).
    //
    // The lane is still reachable: `?mode=wasm` pins it, and Dev mode carries the renderer combo
    // and the Live chip.
    val browserTabsScript =
      if (!componentBrowser) ""
      else
        """
        <script>(function(){var p=document.getElementById("cp-browser-preview-tab"),s=document.getElementById("cp-source-chip"),r=document.getElementById("cp-source-toggle");function sync(){if(!p||!s||!r)return;var source=!!r.checked;p.setAttribute("aria-selected",source?"false":"true");s.setAttribute("aria-selected",source?"true":"false");}if(p&&s&&r){p.addEventListener("click",function(){if(r.checked)s.click();setTimeout(sync,0);});s.addEventListener("click",function(){setTimeout(sync,0);});window.addEventListener("popstate",function(){setTimeout(sync,0);});}setTimeout(sync,0);})();</script>
        """
          .trimIndent()
    // ---- The Motion lane -------------------------------------------------------------------
    //
    // The recorded interaction behind this card, on the stage in place of the still.
    //
    // A screenshot can only ever show a component at rest. Whether its own interaction plumbing
    // actually drives the transition — and what shape that transition has — is exactly what the
    // still cannot answer, so a preview that declared `@InteractionPreview` / `@AnimatedPreview`
    // publishes an animated capture beside its baked PNG (see the `motion/` directory on the
    // delivery branch).
    //
    // Offered as a CHIP, never as the default frame, for two reasons that point the same way. Most
    // readers open a component page to look at the component, and a page that starts animating at
    // them is answering a question they did not ask — the same judgement the design-spec and Source
    // chips already encode. And a capture is heavy: tens to hundreds of frames against one still,
    // so autoplaying it would put that on every visitor to every card that has one. The bytes are
    // requested on FIRST ENTRY and never at page load, exactly like the spec raster.
    //
    // Not an `<option>` inside the renderer combo, for the same reason the spec chip is not: that
    // combo is headed "Switch renderer" and this is not a renderer — it is the same render, moving.
    // What to call each capture, split in two by [MotionCaptureLabels]: a brief title for the menu
    // and the annotation's caption in full for the readout beside the frames. The captions catalogs
    // actually write are a line of instruction followed by a paragraph of what to watch for, and
    // printing that on a control is what made this row wider than the render it introduces.
    val motionLabels = MotionCaptureLabels.of(motionCaptures)
    // Session-scoped like every other asset link on this page. Deliberately NOT pin-carrying: the
    // route reads the bytes straight off the delivery branch the session is holding, so a `pin`
    // param would name a publish the handler has no way to honour — a link that quietly lies about
    // which publish it is showing is worse than one that does not offer the choice.
    fun motionSrc(capture: ServeMotion): String =
      "$basePath/motion/${WebEscaping.urlEncodeSegment(capture.id)}${capture.extension}$q"
    // The picker, and the src holder. Rendered even for a single capture — the option IS where the
    // lane reads its source from, so there is one code path rather than two — but the menu is
    // `hidden` until there is genuinely a choice to make, because a "pick one of one" control is
    // just noise on the bar.
    //
    // A menu rather than the segmented group the spec views use, which is where this started: a
    // segment is as wide as its label, so N captions sat across the bar at once, each one a
    // sentence, and the row grew past the render it introduces. A closed `<select>` shows ONE brief
    // title at a fixed width however many recordings there are — and unlike the renderer combo
    // beside it this is a state field, not a command menu: nothing else on the page names which
    // recording is playing, so the control has to keep showing it.
    //
    // The caption in full rides on the option and is printed by the readout on pick, so the words
    // the annotation wrote are one selection away rather than spent on the control.
    val motionSelector =
      if (motionCaptures.isEmpty()) ""
      else {
        val options =
          motionCaptures
            .mapIndexed { index, capture ->
              val label = motionLabels[index]
              val detail =
                if (label.detail == label.title) ""
                else " data-motion-detail=\"${WebEscaping.htmlEscape(label.detail)}\""
              "<option value=\"${WebEscaping.htmlEscape(capture.id)}\" " +
                "data-motion-src=\"${WebEscaping.htmlEscape(motionSrc(capture))}\"" +
                "$detail${if (index == 0) " selected" else ""}>" +
                "${WebEscaping.htmlEscape(label.title)}</option>"
            }
            .joinToString("")
        val menuHidden = if (motionCaptures.size < 2) " hidden" else ""
        "<span class=\"cp-motion-lane\" id=\"cp-motion-lane\" hidden>" +
          "<select class=\"cp-motion-select\" id=\"cp-motion-select\" " +
          "aria-label=\"Recorded interaction\" " +
          "title=\"Which recorded interaction to play\"$menuHidden>$options</select>" +
          "<span class=\"cp-motion-caption\" id=\"cp-motion-caption\" role=\"status\" " +
          "aria-live=\"polite\"></span></span>"
      }
    // The chip itself, beside Source and for the same reason it is there. It answers a fourth
    // question on that row: beside "which engine drew this?" (the combo), "what was it specified
    // as?" (the spec chip) and "what do I type to get this?" (Source) — *what does it do?*
    val motionChipHtml =
      if (motionCaptures.isEmpty()) ""
      else {
        val tip =
          if (motionCaptures.size == 1)
            // The caption IN FULL, not the menu's brief title: a tooltip has room for a sentence,
            // and this is the one place a reader can find out what the recording shows without
            // starting it. The readout inside the lane says the same thing once they have.
            "Play this preview's recorded interaction \u2014 " +
              motionLabels[0].detail.ifBlank { motionLabels[0].title }
          else "Play this preview's recorded interactions (${motionCaptures.size})"
        "<button type=\"button\" id=\"cp-motion-chip\" class=\"cp-spec-chip cp-motion-chip\" " +
          // Both, because which one carries the capture depends on whether the browser could
          // decode its frames: the player when it could, the plain image when it could not.
          "aria-pressed=\"false\" aria-controls=\"cp-motion-player cp-motion-img\" " +
          "data-motion-chip-tip=\"${WebEscaping.htmlEscape(tip)}\" " +
          "title=\"${WebEscaping.htmlEscape(tip)}\">Motion</button>"
      }
    // The stage image the Motion lane FALLS BACK to: a sibling of the snapshot `<img>`, left
    // `hidden` and src-less until the lane is entered — the same treatment [specImg] gets, and here
    // it is what keeps an unopened capture from being fetched *and* from playing.
    //
    // No longer the primary path. An `<img>` plays a capture the way the file says to and offers
    // the reader nothing: our APNGs are written with `loopCount = 0`, so a recording that toggles a
    // switch on and then off runs on → off → on → off with no seam and the reader cannot tell a
    // transition from its own reverse; there is no pausing it, no slowing it, and no sitting on the
    // two frames either side of the moment being documented. The canvas below is what the lane
    // actually uses. This stays for the browser that cannot decode frames for us (see `loadMotion`
    // in `viewer.ts`), where a looping capture beats no capture.
    val motionImg =
      if (motionCaptures.isEmpty()) ""
      else
        "<img id=\"cp-motion-img\" class=\"cp-motion-img\" hidden alt=\"" +
          "${WebEscaping.htmlEscape("$displayName \u2014 recorded interaction")}\">"
    // The player: the capture on a canvas, with its transport under it.
    //
    // A canvas because every one of the four things this lane is asked for \u2014 play once, show
    // where
    // playback is, scrub to a frame, change speed \u2014 needs the frames addressable one at a
    // time, and
    // an animated `<img>` exposes none of them. `viewer.ts` decodes the capture with `ImageDecoder`
    // and paints frame N here; nothing is fetched or decoded until the lane is entered, exactly as
    // before.
    //
    // The transport is `hidden` in the markup and revealed only once a decode has actually
    // succeeded, because a row of dead controls over a looping fallback would promise scrubbing the
    // page cannot do.
    val motionPlayer =
      if (motionCaptures.isEmpty()) ""
      else {
        val rateOptions =
          listOf("0.25" to "0.25\u00d7", "0.5" to "0.5\u00d7", "1" to "1\u00d7", "2" to "2\u00d7")
            .joinToString("") { (value, label) ->
              "<option value=\"$value\"${if (value == "1") " selected" else ""}>$label</option>"
            }
        "<div class=\"cp-motion-player\" id=\"cp-motion-player\" hidden>" +
          "<canvas id=\"cp-motion-canvas\" class=\"cp-motion-canvas\" role=\"img\" " +
          "aria-label=\"${WebEscaping.htmlEscape("$displayName \u2014 recorded interaction")}\">" +
          "</canvas>" +
          "<div class=\"cp-motion-transport\" id=\"cp-motion-transport\" hidden>" +
          "<button type=\"button\" id=\"cp-motion-play\" class=\"cp-motion-transport-btn\" " +
          "aria-pressed=\"false\" title=\"Play\" aria-label=\"Play\">\u25b6</button>" +
          "<button type=\"button\" id=\"cp-motion-replay\" class=\"cp-motion-transport-btn\" " +
          "title=\"Play again from the start\" aria-label=\"Play again from the start\">" +
          "\u21ba</button>" +
          // A range input, not a bar drawn by hand: it is the one control here a reader drags, and
          // the platform's own brings \u2190 / \u2192 frame stepping, Home / End, a focus ring and
          // a screen
          // reader that announces which frame of how many \u2014 all of which a `<div>` with a
          // pointer
          // handler would have to reimplement, and would get subtly wrong.
          "<input type=\"range\" id=\"cp-motion-scrub\" class=\"cp-motion-scrub\" " +
          "min=\"0\" max=\"0\" value=\"0\" step=\"1\" " +
          "title=\"Scrub to a frame\" aria-label=\"Frame\">" +
          "<span class=\"cp-motion-time\" id=\"cp-motion-time\"></span>" +
          "<select id=\"cp-motion-rate\" class=\"cp-motion-rate\" " +
          "title=\"Playback speed\" aria-label=\"Playback speed\">$rateOptions</select>" +
          "</div></div>"
      }
    // The lane's hidden mode radio. Motion is not a renderer either, but joining the same radio
    // group buys it every mechanism the other lanes get for free: `?mode=motion` in the URL,
    // restore on load, and Back/Forward through the lane. Without it `currentMode()` would keep
    // reporting the snapshot while a capture was on the stage.
    val motionModeInput =
      if (motionCaptures.isEmpty()) ""
      else
        "<input type=\"radio\" name=\"cp-mode\" value=\"motion\" id=\"cp-motion-toggle\" " +
          "tabindex=\"-1\">"
    val defaultLane = if (enabledRcPlayers.isEmpty()) "png" else "rc:$defaultRcBackend"
    // Rendered only when there is genuinely something to switch *to*: a single-lane preview keeps
    // the chip on its own rather than growing a combo box with one entry in it.
    //
    // It is a **command** menu, not a state field: the always-selected placeholder is what it shows
    // at rest, and `syncLaneSelect` returns it there after every pick. The chip immediately to its
    // left already names the current renderer, and a combo that repeated that name beside it read
    // as two controls arguing about the same fact ("Java  [Java ▾]"). So the chip answers *what am
    // I looking at* and this answers *what else could I look at* — which is the whole split.
    //
    // Catalog mode's switcher is the Remote Compose players and nothing else. The Wasm app already
    // has a chip of its own there, and "Snapshot" is not a destination when it is the only other
    // entry — so a Catalog page with no RC document keeps the bare chip row it always had, while a
    // Remote Compose one grows the full player combo.
    val selectLanes = if (componentBrowser) lanes.filter { it.value.startsWith("rc:") } else lanes
    val laneSelectHtml =
      if (selectLanes.size < 2) ""
      else
        selectLanes.joinToString(
          separator = "",
          prefix =
            "<select id=\"cp-lane-select\" class=\"cp-lane-select\" " +
              "aria-label=\"Switch renderer\" " +
              "title=\"Draw this preview with a different renderer\" " +
              "data-default=\"$defaultLane\" data-rc-default=\"$defaultRcBackend\"" +
              // Catalog mode drops the renderer chip with the rest of the Live control, and that
              // chip is what made this a *command* menu: "switch renderer…" at rest is only honest
              // while something beside it names the renderer in use. Without it the menu is the
              // sole indicator, so it has to hold its selection instead of bouncing back to the
              // placeholder — otherwise picking Java leaves nothing on the page, or in the
              // accessibility tree, saying Java is what is drawing.
              (if (componentBrowser) " data-lane-state=\"1\"" else "") +
              ">" +
              "<option value=\"\" selected>Switch renderer…</option>",
          postfix = "</select>",
        ) { lane ->
          val disabledAttr = if (lane.enabled) "" else " disabled"
          // `<option>` carries no tooltip anywhere reliable, so an unavailable lane says so in the
          // label itself rather than in a `title` nobody sees.
          val text = if (lane.enabled) lane.label else "${lane.label} (unavailable)"
          "<option value=\"${lane.value}\"$disabledAttr>" +
            "${WebEscaping.htmlEscape(text)}</option>"
        }
    // The step from "look at one player" to "look at them all": the format-comparison page, focused
    // on this preview and opened on its Remote Compose lane. A subtle text link rather than another
    // chip — it navigates away, so it deliberately stays out of the picker's affordance set.
    val comparePlayersLink =
      if (enabledRcPlayers.size < 2) ""
      else {
        val compareQuery =
          listOf(
              "format=rc",
              "preview=${WebEscaping.urlEncodeSegment(preview.id)}",
              linkQuery(token, linkSessionId, basePath, isPublic),
            )
            .filter { it.isNotEmpty() }
            .joinToString("&")
        "<a class=\"cp-format-link cp-compare-players\" href=\"$basePath/compare?$compareQuery\" " +
          "title=\"See every Remote Compose player's render of this screen side by side\">" +
          "compare players →</a>"
      }
    // The stage image the Spec lane paints into: a sibling of the snapshot `<img>`, left `hidden`
    // (and src-less) until the lane is entered, so a viewer that never opens it costs no request.
    // The Source panel: a sibling of the snapshot `<img>` on the stage, left empty and `hidden`
    // until the chip is pressed. The code is fetched then, from `/usage/<id>` — a preview most
    // visitors look at without ever opening this, and the snippet costs a GitHub read on a cold
    // cache, so a page load must not pay for one.
    //
    // Server-rendered empty (rather than created by the script) so the panel has a stable place in
    // the stage and the layout does not jump the first time it is opened — the same reason the
    // inspection legend is rendered empty.
    val sourcePanelHtml =
      if (!usageAvailable) ""
      else
        "<div class=\"cp-source-panel\" id=\"cp-source-panel\" role=\"region\" " +
          "aria-label=\"Usage source\" hidden></div>"
    val specImg =
      if (specRasterUrl == null) ""
      else
        "<img id=\"cp-spec-img\" class=\"cp-spec-img\" hidden alt=\"" +
          "${WebEscaping.htmlEscape("$displayName — design spec")}\">"
    // The comparison surface the Diff / Triptych / Slider views paint into — a second stage child
    // beside [specImg], `hidden` until one of them is picked. Every panel is a `<canvas>` rather
    // than an `<img>` on purpose: `<cp-spec-compare>` normalises both frames to one pixel space
    // before painting (a reference exported at a different scale than the render is the normal
    // case), and only canvases can carry that redrawn result. Nothing is fetched until a
    // comparison view is actually chosen.
    val specCompare =
      if (specRasterUrl == null) ""
      else {
        fun panel(kind: String, id: String, caption: String, description: String) =
          "<figure class=\"cp-spec-panel\" data-cp-spec-panel=\"$kind\">" +
            "<canvas id=\"$id\" aria-label=\"${WebEscaping.htmlEscape(description)}\"></canvas>" +
            "<figcaption>${WebEscaping.htmlEscape(caption)}</figcaption></figure>"
        "<div class=\"cp-spec-compare\" id=\"cp-spec-compare\" hidden " +
          "data-view=\"$SPEC_DEFAULT_VIEW\" " +
          "data-reference=\"${WebEscaping.htmlEscape(specRasterUrl)}\">" +
          panel("reference", "cp-spec-reference", "Spec", "Imported design spec") +
          panel("diff", "cp-spec-diff", "Diff", "Pixels where the render and the spec disagree") +
          panel("actual", "cp-spec-actual", "Render", "This preview's Compose render") +
          "<div class=\"cp-spec-wipe\">" +
          "<canvas id=\"cp-spec-wipe-canvas\" " +
          "aria-label=\"Spec on the left of the seam, Compose render on the right\"></canvas>" +
          "<label class=\"cp-spec-wipe-control\"><span>Spec</span>" +
          "<input id=\"cp-spec-wipe-range\" class=\"cp-spec-wipe-range\" type=\"range\" " +
          "min=\"0\" max=\"100\" value=\"50\" " +
          "aria-label=\"Wipe between the design spec and the Compose render\">" +
          "<span>Render</span></label></div>" +
          "</div>" +
          "<script type=\"application/json\" id=\"cp-spec-annotations\">" +
          encodeAnnotationPayload(AnnotationPayload(reference = referenceAnnotations)) +
          "</script>" +
          // Drives the panel above: the view buttons, the four surfaces, and the verdict on the
          // design-spec chip. Emitted immediately after it — `viewer.js` calls
          // `window.cpSpecCompare` on the way into the lane, so the element has to be able to set
          // itself up the moment the tag upgrades rather than one parse later. Renders nothing;
          // `serve.css` hides the tag.
          "<cp-spec-compare></cp-spec-compare>"
      }
    // The Source lane's hidden mode radio. It is not a render lane, but it joins the same radio
    // group as the rest so it inherits every mechanism they get for free: `?mode=source` in the
    // URL, restore on load, and Back/Forward through the lane. Without it `currentMode()` — which
    // reads the checked radio — would keep reporting the snapshot while the panel was on the stage.
    val sourceModeInput =
      if (!usageAvailable) ""
      else
        "<input type=\"radio\" name=\"cp-mode\" value=\"source\" id=\"cp-source-toggle\" " +
          "tabindex=\"-1\">"
    val specModeInput =
      if (specRasterUrl == null) ""
      else
        "<input type=\"radio\" name=\"cp-mode\" value=\"spec\" id=\"cp-spec-toggle\" tabindex=\"-1\">"
    val isAppScreen = isScreenPreview(preview)
    // A Wear catalog's screens are watch faces/tiles/activities — offering Pixel phones, a foldable
    // and a tablet there is nonsense (and renders a watch-shaped composable onto a 1280dp stage).
    // Same system-id signal the always-dark stage uses, so one heuristic decides "this is a Wear
    // system" for both.
    val isWearSystem = SystemDisplay.isWearOs(basePath.trim('/').ifBlank { sessionId ?: "" })
    val screenDeviceOptions =
      screenDevicesFor(isWearSystem).joinToString("\n                  ") { device ->
        val value = WebEscaping.htmlEscape(device.id)
        val label = WebEscaping.htmlEscape("${device.name} · ${device.kind} (${device.sizeDp})")
        "<option value=\"$value\">$label</option>"
      }
    // A static bundle/catalog replays baked PNGs — the server can't re-render, so the override
    // controls that rebuild the /render URL (device/locale/font scale/orientation + the live
    // stream)
    // do nothing. Disable them (with a note) instead of leaving dead knobs the user fiddles with.
    // Theme is the exception when a Wasm app backs the session: it re-points the in-browser
    // iframe's
    // ?uiMode, so it stays live there. Live daemon sessions (canApplyOverrides) keep everything on.
    val staticSnapshot = !canApplyOverrides
    // Whether the server can produce a *fresh, overridden* render at all — either the default
    // snapshot lane re-renders ([canApplyOverrides]) OR a carried catalog daemon re-renders an
    // override on demand ([canRenderOverrides], the published-CMP-catalog case). When true the
    // server-render controls (size, device, locale, …) are LIVE even before the Live toggle is
    // flipped: editing one re-points `/render`, which the daemon serves freshly. This is what makes
    // "most override modes" work for a CMP catalog (compose-m3) instead of sitting greyed out until
    // a live stream is opened.
    val overridesLive = canApplyOverrides || canRenderOverrides
    // Server-render controls (size / device / orientation): enabled whenever the
    // server can render an override ([overridesLive]); a plain static bundle (neither) keeps them
    // disabled with the note.
    val serverDis = if (overridesLive) "" else " disabled"
    // The "Live (stream)" toggle keys off [hasLiveStream], NOT staticSnapshot: a trusted-catalog
    // live session serves static baked snapshots (staticSnapshot=true) yet still offers the daemon
    // stream on demand. For plain daemon / static sessions hasLiveStream tracks canApplyOverrides,
    // so
    // this is unchanged there.
    val liveAuthBlocksStream = hasLiveStream && liveAuthPrompt != null
    val liveDis = if (hasLiveStream && !liveAuthBlocksStream) "" else " disabled"
    // Whether the single Static⇄Live preview toggle has any interactive lane to switch to — the
    // daemon stream ([hasLiveStream]) or the in-browser Wasm app ([wasmSrc]). Disabled (with the
    // note) on a pure static bundle with neither.
    val liveToggleDis =
      if ((hasLiveStream || wasmSrc != null) && !liveAuthBlocksStream) "" else " disabled"
    val liveAuthTitle = liveAuthPrompt?.let { "Sign in with GitHub to enable Live preview." }
    // The chip names the renderer on the stage and its dot is the live indicator, so the tooltip
    // has to say what pressing it *does* — "Java" alone reads as a label, not a switch.
    //
    // This is only the OPENING text. The chip's state changes under the visitor (into Live, into a
    // client-side player lane, back out), and a fixed tooltip would then contradict the control it
    // is attached to — promising "click for live" on a chip whose click now exits to the snapshot.
    // `updateLiveToggle()` re-derives it on every transition from the same state that decides the
    // dot and the pressed flag; this string is what the server-rendered markup opens on, and it
    // matches what that function computes for the initial (static, not-yet-interactive) state —
    // including the honest wording for a session with no live lane to enter at all.
    // The chip's opening label: the lane it opens on whenever something else on the row can put a
    // different lane on the stage (the renderer combo, or the design-spec chip). With no such
    // control the chip is the only lane affordance on the row and there is nothing to disambiguate
    // against, so it names the STATE the stage is in instead — and which word does that depends on
    // whether the chip is about to carry a verb:
    //
    //   - a lane to enter → "Snapshot", so the chip reads "Snapshot ▸ Live": a state and the switch
    //     out of it. The old wording put the destination in the label, which read "Live preview ▸
    //     Live" the moment the verb arrived — the chip naming the same lane twice.
    //   - nothing to enter → "Live preview", the plain (disabled) invitation. There is no verb to
    //     pair with here, and "Snapshot" alone beside a dead dot says nothing about what the chip
    //     is for.
    val primaryLaneLabel =
      if (laneSelectHtml.isEmpty() && specChipHtml.isEmpty())
        if (liveToggleDis.isEmpty()) "Snapshot" else "Live preview"
      else lanes.firstOrNull { it.value == defaultLane }?.label ?: "Live preview"
    val liveToggleTitleAttr =
      " title=\"" +
        WebEscaping.htmlEscape(
          liveAuthTitle
            ?: if (liveToggleDis.isEmpty())
              "Static snapshot — click for the live, interactive preview"
            else "Static snapshot — this session has no live lane to switch to"
        ) +
        "\""
    // The chip has to read as a SWITCH, not a caption. Its label NAMES the lane on the stage
    // ("Java", "Live preview") — a noun, sitting beside a status dot, which is the grammar of a
    // readout rather than of a control, and that is why a visitor never learns it is clickable.
    // The verb supplies the missing half by naming the DESTINATION instead ("Java ▸ Live"), so the
    // chip states where a click goes without the label having to stop naming where it already is.
    //
    // `aria-hidden`, deliberately: the accessible name stays the lane's own name, and the
    // `aria-pressed` flag plus the tooltip already carry the switch semantics. Without it the
    // button announces "Java ▸ Live, toggle button, not pressed" — the arrow read aloud as a name.
    //
    // Empty when there is no lane to enter: a disabled chip must not promise a destination it
    // cannot reach. `updateLiveToggle()` re-derives this on every transition from the same state
    // that decides the dot, the tooltip and the stage hint — so the two halves of the chip can
    // never disagree about which way the switch is pointing.
    val liveToggleVerb =
      if (liveToggleDis.isEmpty())
        "            <span class=\"cp-live-toggle-verb\" id=\"cp-live-toggle-verb\" " +
          "aria-hidden=\"true\">▸ Live</span>\n"
      else ""
    val liveToggleButton =
      "<button type=\"button\" id=\"cp-live-toggle\" class=\"cp-live-toggle\" " +
        "aria-pressed=\"false\" " +
        // What the chip goes back to naming when it leaves the design-spec lane on a preview with
        // no renderer combo. `laneLabelText()` reads the combo's options for this everywhere else;
        // with no combo there is nothing to read, and without this the chip would come back from
        // the spec lane calling a static snapshot "Live preview".
        "data-default-lane-label=\"${WebEscaping.htmlEscape(primaryLaneLabel)}\"" +
        "$liveToggleTitleAttr$liveToggleDis>\n" +
        "            <span class=\"cp-live-dot\" aria-hidden=\"true\"></span>\n" +
        "            <span id=\"cp-live-toggle-label\">" +
        "${WebEscaping.htmlEscape(primaryLaneLabel)}</span>\n" +
        liveToggleVerb +
        "          </button>"
    // When sign-in is the ONLY thing between the visitor and the daemon lane, offer the sign-in
    // itself rather than a dead control.
    //
    // What this replaces: a `disabled` button wrapped in a span carrying `data-github-login`. That
    // said "sign in" three ways that a visitor cannot act on — a `title` tooltip (never shown on
    // touch, and never announced for a `disabled` button, which is not focusable), a greyed-out
    // chip that reads as "not available here" rather than "one click away", and a login URL sitting
    // in the DOM that **no script ever read** (nothing anywhere referenced `data-github-login`), so
    // clicking did nothing at all.
    //
    // An anchor fixes all three at once: the reason is in the visible label, it is focusable and
    // keyboard-activatable, and following it is the browser's job rather than a handler that was
    // never written. It deliberately does NOT carry `id="cp-live-toggle"` — `updateLiveToggle()`
    // drives that element through `.disabled` and `aria-pressed`, which are meaningless on a link.
    // Leaving the id off makes `liveToggle` null, so every `if (liveToggle)` branch skips instead
    // of quietly writing button properties onto an anchor.
    val liveSignInLink = liveAuthPrompt?.let {
      "<a id=\"cp-live-signin\" class=\"cp-live-toggle cp-live-signin\" " +
        "href=\"${WebEscaping.htmlEscape(it.loginHref)}\" " +
        "title=\"Sign in with GitHub to enable Live preview. " +
        (if (it.restrictedToAllowedUsers) "This server allows named GitHub users only."
        else "Any GitHub account works.") +
        "\">\n" +
        "            <span class=\"cp-live-dot\" aria-hidden=\"true\"></span>\n" +
        "            <span>Live preview — sign in</span>\n" +
        "          </a>"
    }
    // Only swap in the sign-in link when auth is what's blocking the stream. A pure static bundle
    // has no lane to unlock, so it keeps the honestly-disabled toggle — inviting a sign-in that
    // would change nothing is worse than the greyed chip.
    val liveToggleHtml =
      if (liveAuthBlocksStream && liveSignInLink != null) liveSignInLink else liveToggleButton
    // Controls the in-browser Wasm app also honours — day/night (uiMode), font scale (density),
    // locale (layout direction): live whenever the server can render an override OR a Wasm app
    // backs
    // the session.
    val wasmDis = if (overridesLive || wasmSrc != null) "" else " disabled"
    // The static-snapshot note is only shown when overrides genuinely can't re-render on the server
    // ([overridesLive] false): a plain static bundle, or a Wasm-only published catalog (where
    // day/night, font scale, locale &amp; knobs apply in the browser but size/device/orientation
    // need a live server). A catalog whose carried daemon re-renders on demand ([overridesLive]
    // true) needs no note — its controls all take effect.
    // Watches don't rotate, so a Wear screen gets the device picker without the Orientation control
    // — and the notes below must not promise a knob that isn't on the page.
    val showOrientation = isAppScreen && !isWearSystem
    val serverOnlyOverrideNote =
      when {
        showOrientation -> "Device size &amp; Orientation need the live server. "
        isAppScreen -> "Device size needs the live server. "
        else -> "Size needs the live server. "
      }
    val snapshotOverrideList =
      when {
        showOrientation -> "device size, locale, font scale, orientation"
        isAppScreen -> "device size, locale, font scale"
        else -> "size, locale, font scale"
      }
    val snapshotNote =
      if (componentBrowser) ""
      else
        when {
          overridesLive -> ""
          wasmSrc != null ->
            "<div class=\"cp-note\">Pre-rendered snapshot — turn on <strong>Live preview</strong> to " +
              "interact. Day/Night, Font scale, Locale &amp; declared knob values apply in " +
              "the browser; " +
              serverOnlyOverrideNote +
              "<a href=\"$LOCAL_SERVER_DOCS\">Enable a local preview server.</a></div>"
          else ->
            "<div class=\"cp-note\">Pre-rendered snapshot — overrides (" +
              snapshotOverrideList +
              ") need the live server, not a published catalog. " +
              "<a href=\"$LOCAL_SERVER_DOCS\">Enable a local preview server.</a></div>"
        }
    // The stage's own invitation into the live lane.
    //
    // Until this, the only route in was the chip in the toolbar: nothing on the preview itself said
    // the picture could be made interactive, and an affordance a visitor has to hover a toolbar to
    // discover is one most of them never find. The grid solved exactly this for its cards with
    // `.cp-live-hint` (`CatalogLive.ts`); that vocabulary never reached the single-preview page, so
    // this reuses the same badge — same shape, same placement — and only the wording differs,
    // because the gesture does. One click here, a long press there; a hint naming the wrong gesture
    // would be worse than no hint.
    //
    // Rendered only when there is genuinely a lane to enter (the same condition the chip is enabled
    // on) and never in the component browser, which carries no live toggle at all. It stays hidden
    // until `updateLiveToggle()` reveals it, which is deliberate: the click it advertises is wired
    // in `viewer.ts`, so a page whose script never ran must not offer a gesture nothing implements.
    val stageLiveHint =
      if (componentBrowser || liveToggleDis.isNotEmpty()) ""
      else
        "<span class=\"cp-live-hint cp-stage-live-hint\" id=\"cp-stage-live-hint\" " +
          "aria-hidden=\"true\">click for live</span>"
    val backendLabel = WebEscaping.htmlEscape(snapshotBackend ?: "Snapshot")
    val liveLabel = WebEscaping.htmlEscape(liveBackend ?: "Live")
    // One Theme axis replaces the separate Day/Night + app-theme controls. The two defaults map to
    // uiMode; every `theme:<provider>` option maps to themeProvider and deliberately clears uiMode,
    // because an app-declared theme already owns its day/night palette.
    //
    // A theme specimen documents ONE named theme, so the whole Theme axis is withdrawn here
    // exactly as the landing withholds its themed-render URL. Without this the annotation stopped
    // working the moment the card was opened: the viewer received every declared theme and
    // happily re-rendered the specimen under another one, contradicting its own caption.
    //
    // BOTH axes go, not just `theme:<provider>`. Day/Night is not a navigation control — it maps
    // to a `uiMode` override, and `CatalogLiveRouting.overridesAffectRender` routes a uiMode
    // differing from the id's baked `__light`/`__dark` segment to a fresh daemon render. So on a
    // specimen it either redraws a supposedly fixed sheet in the opposite mode, or (when the
    // sheet hard-codes its theme) leaves the selector reading "Night" over unchanged light
    // pixels. A light/dark pair of specimens is authored as two previews with their own cards;
    // this control never reached the sibling.
    val themeFixed = isThemeSpecimen(preview)
    val viewerDeclaredThemes = if (themeFixed) emptyList() else declaredThemes
    val themeSelectorHtml = run {
      val declaredThemes = viewerDeclaredThemes
      val themeDis =
        if (
          !themeFixed &&
            ((!wearAlwaysDark && (overridesLive || wasmSrc != null)) ||
              (declaredThemes.isNotEmpty() && overridesLive))
        )
          ""
        else " disabled"
      val providerDis = if (overridesLive) "" else " disabled"
      val grouped = declaredThemes.groupBy { it.group }
      val optionsOf: (List<ServeTheme>) -> String = { list ->
        list.joinToString("\n") { t ->
          val modeAttr = t.mode?.let { " data-theme-mode=\"${WebEscaping.htmlEscape(it)}\"" } ?: ""
          "<option value=\"theme:${WebEscaping.htmlEscape(t.providerFqn)}\"$modeAttr$providerDis>" +
            "${WebEscaping.htmlEscape(t.name)}</option>"
        }
      }
      val body = buildString {
        // Ungrouped themes first (flat), then one <optgroup> per declared group.
        grouped[null]?.let { append(optionsOf(it)).append('\n') }
        grouped
          .filterKeys { it != null }
          .forEach { (group, list) ->
            append("<optgroup label=\"${WebEscaping.htmlEscape(group!!)}\">")
              .append(optionsOf(list))
              .append("</optgroup>\n")
          }
      }
      val daySelected = if (viewerTheme != "dark") " selected" else ""
      val nightSelected = if (viewerTheme == "dark") " selected" else ""
      val defaults =
        if (wearAlwaysDark) "<option value=\"dark\"$nightSelected>Dark (Default)</option>"
        else
          "<option value=\"light\"$daySelected>Light (Default)</option>\n" +
            "            <option value=\"dark\"$nightSelected>Dark (Default)</option>"
      val providerOptions = body.trimEnd().let { if (it.isEmpty()) "" else "\n            $it" }
      // Visually removed, deliberately — the same treatment the render-mode radios get. The Theme
      // axis is now picked from the chips on the viewer bar ([themeBarHtml]), but this select stays
      // the axis's single state holder: viewer.js reads it for every render (`activeThemeChoice`),
      // the sticky script seeds it from the URL + localStorage, and Back/Forward hydration writes
      // to it. Two visible controls for one value is worse than one, so only the chips are shown.
      //
      // `data-default-theme` is the theme this preview is BAKED in — what the select shows with
      // nothing picked. It rides as its own attribute because the `selected` option stops
      // answering for it the moment the sticky script writes `el.value`, and every consumer that
      // asks "has the visitor actually pinned a theme?" (`pinsTheme`) reads it after that point.
      // Empty when the catalog names no theme for the preview, which is deliberately NOT the same
      // as "light": the select falls back to displaying Light, but a `uiMode=light` there is a
      // real request the baked pixels may not answer, so it stays an override.
      // `tabindex="-1"` keeps the hidden select out of the tab order, which is what makes the
      // `aria-hidden` wrapper legitimate.
      """
        <span class="cp-modes-inputs" aria-hidden="true">
          <select id="cp-theme" class="cp-knob-theme" data-theme-active="0" data-default-theme="${viewerTheme.orEmpty()}" data-has-declared-themes="${declaredThemes.isNotEmpty()}" data-fixed-theme="$themeFixed" tabindex="-1"$themeDis>
            $defaults$providerOptions
          </select>
        </span>
        """
        .trimIndent()
    }
    // The Theme BAR: the same chips the catalog grid carries ([themePickerHtml]), on the viewer's
    // own toolbar — so picking a theme is one visible click on both pages instead of a chip row on
    // the grid and a select buried in the ⚙ Overrides drawer here. The values are exactly the
    // select's option values (`light` / `dark` / `theme:<providerFqn>`), which is what lets
    // viewer.js drive one from the other: a chip click writes the select and fires its `change`,
    // and every existing lane (daemon re-render, Wasm ?uiMode, URL state, the catalog-scoped sticky
    // key) keeps working untouched. Day/Night rather than Light/Dark to match the labels the select
    // used; a dark-first (Wear) system offers Night alone, as its select did.
    // …and, like the axes rows above, the bar FOLDS once a catalog declares enough themes that the
    // chips stop fitting. Eight chips is the published compose-m3 shape: they ellipsise to stubs
    // ("Light Medi…", "Dark Hig…") and the group scrolls within itself, so the row is spending full
    // width to show names it has already truncated. Behind the title-bar toggle the *current*
    // theme's full name is always readable and the chips are one click away. Under
    // [THEME_CHIPS_INLINE] — a plain light/dark catalog, or one with a theme or two — the bar shows
    // as it always has.
    val themeBarHtml =
      themeChipsHtml(
          builtIns =
            if (wearAlwaysDark) listOf("dark" to "Dark")
            else listOf("light" to "Light", "dark" to "Dark"),
          declared = viewerDeclaredThemes,
          indent = "          ",
        )
        .let {
          "<span class=\"cp-theme cp-theme-bar\" id=\"cp-theme-bar\" role=\"group\"" +
            " aria-label=\"Preview theme\">\n" +
            "          $it\n        </span>"
        }
    // The theme toggle's *value* is seeded server-side from the lane this preview is baked in, then
    // kept in sync client-side (viewer-drawers.js mirrors whichever chip `viewer.js` marks pressed)
    // — the theme is picked without a page load, so a server-rendered label alone would go stale on
    // the first click.
    val themeToggle =
      if (pinned != null)
        """
        <span class="cp-disabled-control" tabindex="0" aria-describedby="cp-pinned-controls-note"><button type="button" class="cp-drawer-toggle cp-axis-toggle" id="cp-theme-toggle" disabled aria-describedby="cp-pinned-controls-note"
          title="Pinned revision — theme overrides are not applied to published pixels">
          <span class="cp-toggle-label">Theme</span>
          <span class="cp-toggle-value" id="cp-theme-toggle-value">${if (viewerTheme == "dark") "Dark" else "Light"}</span>
        </button></span>
        """
          .trimIndent()
      else
        """
      <details class="cp-theme-menu">
        <summary class="cp-drawer-toggle cp-axis-toggle" id="cp-theme-toggle" aria-controls="cp-theme-bar">
          <span class="cp-toggle-label">Theme</span>
          <span class="cp-toggle-value" id="cp-theme-toggle-value">${if (viewerTheme == "dark") "Dark" else "Light"}</span>
          <span class="cp-theme-caret" aria-hidden="true">▾</span>
        </summary>
        <div class="cp-theme-menu-panel">$themeBarHtml</div>
      </details>
      """
          .trimIndent()
    // Inspection layers (see `<cp-inspect-layers>`): what the frame is MADE OF, drawn over the
    // pixels the server already sent — the accessibility focus map, the resolved typography, the
    // resolved theme attributes. Each is a box + numbered badge on the stage and a readable row in
    // the legend beside it, so the facts stay legible and hoverable instead of being composited
    // into the render. This is what replaced the old "Accessibility (TalkBack)" toggle, which
    // asked the daemon to bake one focus rectangle and a wall of spoken text into the PNG: it
    // covered the component it was describing, couldn't be inspected, and said nothing about the
    // other stops on the screen.
    //
    // Each row is offered only when its host can actually produce the data (an a11y-capable daemon
    // for the first, a semantics-capturing one for the rest) — never as a dead control.
    // Published reference typography is self-contained, so static bundle viewers can inspect the
    // Figma lane even though they cannot apply overrides or ask a daemon for render annotations.
    val hasTypographyInspection =
      hasDesignAnnotations ||
        hasPublishedTypography ||
        referenceAnnotations.any { it.kind == AnnotationKind.TYPOGRAPHY }
    val inspectRows = buildString {
      if (hasA11yOverlay)
        append(
          "<label class=\"cp-live-row\"><input class=\"cp-inspect\" id=\"cp-inspect-a11y\" " +
            "data-cp-inspect=\"a11y\" type=\"checkbox\"> Accessibility</label>\n"
        )
      if (hasTypographyInspection) {
        append(
          "<label class=\"cp-live-row\"><input class=\"cp-inspect\" " +
            "id=\"cp-inspect-typography\" data-cp-inspect=\"typography\" type=\"checkbox\"> " +
            "Typography</label>\n"
        )
      }
      if (hasDesignAnnotations) {
        append(
          "<label class=\"cp-live-row\"><input class=\"cp-inspect\" id=\"cp-inspect-theme\" " +
            "data-cp-inspect=\"theme\" type=\"checkbox\"> Theme attributes</label>\n"
        )
        append(
          "<label class=\"cp-live-row\"><input class=\"cp-inspect\" id=\"cp-inspect-layout\" " +
            "data-cp-inspect=\"layout\" type=\"checkbox\"> Layout boxes</label>\n"
        )
      }
    }
    val inspectGroupHtml =
      if (inspectRows.isEmpty()) ""
      else
        """
            <div class="cp-overlays">
              <div class="cp-overlays-head">Inspect</div>
              ${inspectRows.trimEnd().prependIndent("              ").trimStart()}
            </div>
        """
          .trimIndent()
    // The legend panel beside the stage, populated client-side by `<cp-inspect-layers>` and hidden
    // until a layer is on. Server-rendered (empty) rather than created by the script so the panel
    // has a stable place in the flex row and the stage doesn't jump sideways the first time a
    // layer is ticked.
    val inspectLayerHtml =
      if (inspectRows.isEmpty()) ""
      else "<div class=\"cp-inspect-layer\" id=\"cp-inspect-layer\"></div>"
    val inspectLegendHtml =
      if (inspectRows.isEmpty()) ""
      else
        "<div class=\"cp-inspect-legend\" id=\"cp-inspect-legend\" role=\"region\" " +
          "aria-label=\"Inspection legend\" hidden></div>" +
          // Fills the layer and the legend above from the frame on screen. Emitted after both, so
          // everything it reads exists the moment the tag upgrades. Renders nothing; `serve.css`
          // hides the tag.
          "<cp-inspect-layers></cp-inspect-layers>"
    // Live overlay toggles (touch visualization). The daemon composites these onto the held
    // session's frames, so they mean nothing on a baked PNG — offered only when a Live Compose
    // stream is available, and omitted entirely otherwise rather than left permanently dead.
    // Rendered **enabled**: a visitor who ticks one while the viewer is still on the static
    // snapshot is asking to see the overlay, so the JS switches into Live Compose for them (the
    // ticked toggle rides in on the stream's initial overrides) instead of presenting a dead
    // control that first demands a click on "Live preview". They carry `$liveDis` — the same gate
    // as the live transport radio — so the one case where they really are dead (the stream exists
    // but is behind sign-in) stays greyed out in the server-rendered markup, matching what
    // `syncOverlayToggles()` reconciles to. `cp-overlay` marks them for the JS collector + sync.
    val liveOverlaysHtml =
      if (hasLiveStream)
        """
            <div class="cp-overlays">
              <div class="cp-overlays-head">Overlays (Live Compose)</div>
              <label class="cp-live-row"><input class="cp-overlay" id="cp-touchOverlay" type="checkbox"$liveDis> Show touches</label>
            </div>
        """
          .trimIndent()
      else ""
    val overlaysHtml =
      if (inspectGroupHtml.isEmpty() && liveOverlaysHtml.isEmpty()) ""
      else
        """
        <details class="cp-group" data-cp-group="overlays">
          <summary>Overlays</summary>
          <div class="cp-group-body">
            $inspectGroupHtml
            $liveOverlaysHtml
          </div>
        </details>
        """
          .trimIndent()
    // Detected-feature controls — shown ONLY for previews that actually support the feature (so
    // it's
    // never a dead control everywhere), and routed like a knob via onKnobChanged (`cp-feature`),
    // disabled unless the host can render an override:
    //  - "Keyboard focus" for a `@FocusedPreview` preview (`focus=0` — focus the first focusable +
    //    draw the focus overlay). Honoured on both daemon backends.
    //  - "Show gesture hints" for a `@GestureHintPreview` preview (`gestures=true`), but ONLY on an
    //    Android-backed session ([gesturesRenderable]) — the desktop daemon ignores the override,
    // so
    //    the row is omitted there rather than shown dead.
    val featureDaemonDis = if (canApplyOverrides || canRenderOverrides) "" else " disabled"
    val showGestureRow = preview.supportsGestures && gesturesRenderable
    val featureRows = buildString {
      if (preview.supportsFocus)
        append(
          "<label class=\"cp-live-row\"><input class=\"cp-feature\" id=\"cp-focus\" " +
            "type=\"checkbox\"$featureDaemonDis> Keyboard focus</label>\n"
        )
      if (showGestureRow)
        append(
          "<label class=\"cp-live-row\"><input class=\"cp-feature\" id=\"cp-gestures\" " +
            "type=\"checkbox\"$featureDaemonDis> Show gesture hints</label>\n"
        )
    }
    val featureControlsHtml =
      if (componentBrowser || featureRows.isEmpty()) ""
      else
        """
        <details class="cp-group" data-cp-group="features">
          <summary>Detected features</summary>
          <div class="cp-group-body">
            <div class="cp-overlays">
              <div class="cp-overlays-head">Detected features</div>
              $featureRows
            </div>
          </div>
        </details>
        """
          .trimIndent()
    // Catalog app screens represent a whole device surface. Arbitrary min/max constraints are
    // useful for components, but are a poor model for a screen; give screens a handful of
    // recognisable, deliberately varied device profiles instead — Android phones/foldable/tablet
    // for a phone catalog, the Wear OS watch shapes for a Wear one. The select retains #cp-device,
    // so the existing override transport and deep-link behaviour apply unchanged.
    val orientationControlHtml =
      if (showOrientation)
        """
        <label>Orientation
          <select id="cp-orientation"$serverDis>
            <option value="">(device default)</option>
            <option value="portrait">Portrait</option>
            <option value="landscape">Landscape</option>
          </select>
        </label>
        """
          .trimIndent()
          .prependIndent("    ") + "\n"
      else ""
    val sizeControlsHtml =
      if (componentBrowser && !isAppScreen) ""
      else if (isAppScreen)
        """
        <details class="cp-group" data-cp-group="size">
          <summary>Size</summary>
          <div class="cp-group-body">
            <label>Device size
              <select id="cp-device"$serverDis>
                <option value="">(preview default)</option>
                SCREEN_DEVICE_OPTIONS_PLACEHOLDER
              </select>
            </label>
        ORIENTATION_CONTROL_PLACEHOLDER
          </div>
        </details>
        """
          .trimIndent()
          .replace("ORIENTATION_CONTROL_PLACEHOLDER\n", orientationControlHtml)
          .replace("\n", "\n          ")
          .replace("SCREEN_DEVICE_OPTIONS_PLACEHOLDER", screenDeviceOptions)
      else
        """
        <details class="cp-group" data-cp-group="size">
          <summary>Size</summary>
          <div class="cp-group-body">
            <div class="cp-size">
              <label>Size mode
                <select id="cp-sizeMode"$serverDis>
                  <option value="">(default)</option>
                  <option value="fixed">Fixed size</option>
                  <option value="max">Max</option>
                  <option value="min">Min</option>
                  <option value="within">Within (min–max)</option>
                </select>
              </label>
              <div class="cp-size-row" id="cp-size-fixed" hidden>
                <label>Width (dp)<input id="cp-fixedW" type="number" min="1" step="1" inputmode="numeric" placeholder="auto" autocomplete="off"$serverDis></label>
                <label>Height (dp)<input id="cp-fixedH" type="number" min="1" step="1" inputmode="numeric" placeholder="auto" autocomplete="off"$serverDis></label>
              </div>
              <div class="cp-size-row" id="cp-size-min" hidden>
                <label>Min width (dp)<input id="cp-minW" type="number" min="1" step="1" inputmode="numeric" placeholder="auto" autocomplete="off"$serverDis></label>
                <label>Min height (dp)<input id="cp-minH" type="number" min="1" step="1" inputmode="numeric" placeholder="auto" autocomplete="off"$serverDis></label>
              </div>
              <div class="cp-size-row" id="cp-size-max" hidden>
                <label>Max width (dp)<input id="cp-maxW" type="number" min="1" step="1" inputmode="numeric" placeholder="auto" autocomplete="off"$serverDis></label>
                <label>Max height (dp)<input id="cp-maxH" type="number" min="1" step="1" inputmode="numeric" placeholder="auto" autocomplete="off"$serverDis></label>
              </div>
            </div>
          </div>
        </details>
        """
          .trimIndent()
          .replace("\n", "\n          ")
    // The overrides drawer is opt-in: the preview leads at every width, and the toggle opens the
    // controls only when they are needed.
    val controlsToggle =
      "<button type=\"button\" class=\"cp-drawer-toggle\" id=\"cp-controls-toggle\" " +
        "aria-expanded=\"false\" aria-controls=\"cp-controls\">⚙ ${if (componentBrowser) "Controls" else "Overrides"}</button>"
    // Stage background follows the preview's theme (dark variant → dark stage), with a dark-first
    // system (Wear) defaulting to dark — see the `.cp-viewer[data-bg-theme] .cp-stage` CSS. Kept
    // separate from the filter's data-card-theme; the viewer JS re-syncs it on a Theme (uiMode)
    // change so a re-render in the opposite theme doesn't clash with a stale backing color.
    val bgThemeAttr = viewerTheme?.let { " data-bg-theme=\"$it\"" } ?: ""
    // The component's renders, as a SUBTREE of the catalog tree filtered to this component: the
    // component row and every primary-axis render under it, the one on screen marked. This replaced
    // two rows of chips — a `State` row and a `Variant` row — that keyed identically to the tree's
    // own [primaryVariants] and so always listed the same renders, only in a second shape, in a
    // second place, with the two axes torn apart into rows that never named their relationship.
    // Empty for a component with no second render, exactly as the chip rows were.
    val axesTree = componentSubtreeHtml(preview, siblings, basePath, q, viewerDarkFirst)
    val navDrawer =
      navDrawerHtml(preview, siblings, basePath, q, viewerTheme, axesTree, viewerDarkFirst)
    val navToggle =
      if (navDrawer.isEmpty()) ""
      else
        "<button type=\"button\" class=\"cp-drawer-toggle\" id=\"cp-nav-toggle\" " +
          "aria-expanded=\"false\" aria-controls=\"cp-nav\">☰ Components</button>"
    val browserComponentNav =
      if (!componentBrowser) ""
      else {
        val seeds = LinkedHashMap<String, ServePreview>()
        siblings
          .filter { it.renderFailure == null }
          .forEach { candidate ->
            val key = componentKey(candidate)
            val existing = seeds[key]
            val candidateIsDefault = !isNonDefaultState(candidate) && !hasNonDefaultProps(candidate)
            val existingIsDefault =
              existing != null && !isNonDefaultState(existing) && !hasNonDefaultProps(existing)
            if (existing == null || (candidateIsDefault && !existingIsDefault))
              seeds[key] = candidate
          }
        val components = groupPreviews(seeds.values.toList()).map { it.rendered(viewerDarkFirst) }
        val currentIndex = components.indexOfFirst { componentKey(it) == componentKey(preview) }
        fun linkAt(index: Int, relation: String, arrow: String): String {
          val target = components.getOrNull(index) ?: return ""
          val href = "$basePath/p/${WebEscaping.urlEncodeSegment(target.id)}$q"
          return "<a class=\"cp-browser-sibling cp-browser-sibling-$relation\" href=\"$href\" " +
            "rel=\"$relation\"><span aria-hidden=\"true\">$arrow</span>" +
            "<span>${WebEscaping.htmlEscape(previewDisplayName(target))}</span></a>"
        }
        if (currentIndex < 0) ""
        else
          "<nav class=\"cp-browser-siblings\" aria-label=\"Adjacent components\">" +
            linkAt(currentIndex - 1, "prev", "←") +
            linkAt(currentIndex + 1, "next", "→") +
            "</nav>"
      }
    val browserVariantLabel =
      if (!componentBrowser) ""
      else
        buildList {
            preview.state?.takeUnless { it == "default" }?.let { add(stateLabel(it)) }
            propsLabel(preview.props).takeIf { it.isNotBlank() }?.let { add(it) }
          }
          .joinToString(" · ")
    val browserBreadcrumb =
      if (!componentBrowser) ""
      else {
        val parts = buildList {
          val catalogLabel = catalogTitle?.takeIf { it.isNotBlank() } ?: "Components"
          add("<a href=\"$basePath/$q\">${WebEscaping.htmlEscape(catalogLabel)}</a>")
          preview.section
            ?.takeIf { it.isNotBlank() }
            ?.let { add("<span>${WebEscaping.htmlEscape(it)}</span>") }
          preview.group
            ?.takeIf { it.isNotBlank() }
            ?.let { add("<span>${WebEscaping.htmlEscape(it)}</span>") }
          add(
            "<span${if (browserVariantLabel.isBlank()) " aria-current=\"page\"" else ""}>" +
              "${WebEscaping.htmlEscape(displayName)}</span>"
          )
          if (browserVariantLabel.isNotBlank()) {
            add("<span aria-current=\"page\">${WebEscaping.htmlEscape(browserVariantLabel)}</span>")
          }
        }
        "<nav class=\"cp-browser-breadcrumb\" aria-label=\"Breadcrumb\">" +
          parts.joinToString("<span class=\"cp-browser-separator\" aria-hidden=\"true\">›</span>") +
          "</nav>"
      }
    // Left to right: the chip that names the current renderer and toggles it live, the combo box of
    // alternatives, the design-spec chip (top level, not an option inside the combo), the two
    // subtle
    // "go compare this elsewhere" links, then the SVG format toggle for whatever the chip is
    // currently showing.
    val primaryControls =
      listOf(
          browserPreviewTab,
          liveToggleHtml.takeUnless { componentBrowser }.orEmpty(),
          laneSelectHtml,
          specChipHtml,
          sourceChipHtml,
          motionChipHtml,
          comparePlayersLink,
          specSelector,
          motionSelector,
          svgFmtToggle,
          explodeToggle,
          svgMatch,
          bgPickerHtml("Show the transparent checkerboard behind the preview"),
          "<button type=\"button\" class=\"cp-bg-btn cp-zoom-toggle\" aria-pressed=\"false\" " +
            "title=\"Show the preview at full width instead of fitting it to the screen\">Fit width</button>",
        )
        .filter { it.isNotBlank() }
        .joinToString("\n")
    val pinnedControlsNote =
      if (pinned == null) ""
      else
        "<span class=\"cp-pinned-controls-note\" id=\"cp-pinned-controls-note\" role=\"note\">" +
          "Pinned revision: Theme overrides are not applied; Source, SVG, and 3D use the " +
          "current catalog and are unavailable.</span>"
    // Both or neither: a timeline the visitor cannot click through to an old render is worse than
    // no timeline, so a missing repo suppresses the whole feature rather than half of it.
    val historyAttrs =
      if (!historyManifestUrl.isNullOrBlank() && !historyRepo.isNullOrBlank()) {
        " data-history-url=\"${WebEscaping.htmlEscape(historyManifestUrl)}\"" +
          " data-history-repo=\"${WebEscaping.htmlEscape(historyRepo)}\""
      } else if (historyLocalRenders && !historyInlineJson.isNullOrBlank()) {
        // The project-mode twin of the pair above: no delivery repo to address a historical render
        // on, so the entries point back at this server, which reads the bytes out of the local
        // object store by content sha. `{blob}` is substituted client-side — a template rather than
        // one URL per version keeps the payload to the shas the manifest already carries.
        " data-history-blob-url=\"" +
          WebEscaping.htmlEscape("$basePath/history/render/{blob}.png$q") +
          "\""
      } else ""
    // The revision control, and the attribute that makes the pin reach the pixels: `viewer.js`
    // appends `at=<sha>` to every render request it builds, so the stage, the export links and the
    // Copy PNG button all read the same publish the banner names.
    val revisionBaseQuery =
      listOf(linkQuery(token, linkSessionId, basePath, isPublic), revisionQuery)
        .filter { it.isNotBlank() }
        .joinToString("&")
    val revisionHref: (String?) -> String = { pin ->
      withPin("$basePath/p/$idSeg${querySuffix(revisionBaseQuery)}", pin)
    }
    // What `<cp-revision-runs>` needs to mark which of those revisions actually differ: the lane
    // that answers it, and the render URL to pin per run head. Deliberately built from the
    // *unpinned* query (`q`, not `revisionBaseQuery`) — the element appends its own `at=<sha>` per
    // thumbnail, and a page that is already pinned would otherwise hand it a URL carrying a second,
    // contradictory pin.
    val runsAttrs =
      " data-runs-url=\"${WebEscaping.htmlEscape("$basePath/api/render-runs/$idSeg$q")}\"" +
        " data-render-url=\"${WebEscaping.htmlEscape("$basePath/render/$idSeg.png$q")}\""
    val revisionMenu =
      revisionsHtml(
        revisions,
        includeBanner = false,
        runsAttrs = runsAttrs,
        runsInlineJson = revisionRunsInlineJson.orEmpty(),
        hrefFor = revisionHref,
      )
    val revisionBanner = revisionBannerHtml(revisions, revisionHref)
    val pinnedAttr =
      revisions.pinned?.let { " data-pinned-at=\"${WebEscaping.htmlEscape(it)}\"" }.orEmpty()
    // The axes the URL named and this page withheld, for `hydrateFromUrl` to defer on. Sorted so
    // the markup is stable across requests; absent entirely on the ordinary page.
    //
    // A JSON array rather than a delimited list, because a knob key is an author string and nothing
    // forbids a comma in one. `knob.price,discount` comma-joined splits into two names that match
    // nothing, and the real axis silently stops being withheld — on a pinned page, exactly the
    // value the render ignored would come back.
    val unseededAttr =
      unseededOverrides
        .takeIf { it.isNotEmpty() }
        ?.let { axes ->
          val json = JsonArray(axes.sorted().map { JsonPrimitive(it) }).toString()
          " data-unseeded-overrides=\"${WebEscaping.htmlEscape(json)}\""
        }
        .orEmpty()
    // `</script>` inside a JSON payload would end the element early, so the only sequence that can
    // break out is neutralised. The payload itself is server-built from the catalog's own manifest.
    val historyInlineHtml =
      historyInlineJson
        ?.takeIf { it.isNotBlank() }
        ?.let {
          "<script type=\"application/json\" id=\"cp-history-data\">" +
            it.replace("</", "<\\/") +
            "</script>"
        }
        .orEmpty()
    val modeInputs =
      listOf(
          "<input type=\"radio\" name=\"cp-mode\" value=\"png\" id=\"cp-mode-png\" tabindex=\"-1\" checked>",
          "<input type=\"radio\" name=\"cp-mode\" value=\"live\" id=\"cp-live\" tabindex=\"-1\"$liveDis>",
          wasmModeInput,
          rcModeInput,
          rcWasmModeInput,
          specModeInput,
          motionModeInput,
          sourceModeInput,
        )
        .filter { it.isNotBlank() }
        .joinToString("\n")
    // `format-compare.js` holds the comparison primitives — content-box normalisation, the
    // edge-tolerant score, the magenta delta map — that BOTH the SVG/PNG fidelity toggle and the
    // spec lane's Diff / Triptych / Slider views draw from, so it loads for either.
    //
    // `<cp-spec-compare>` sits on top of it and publishes `window.cpSpecCompare`, which `viewer.js`
    // calls on the way into (and out of) the lane. The components bundle is emitted above both, and
    // the element wires itself up as soon as its tag upgrades, so the ordering `spec-compare.js`
    // needed is preserved without a script tag of its own.
    val compareScriptTags =
      listOfNotNull(
          scriptTag("format-compare.js").takeIf {
            (hasSvgExport && !componentBrowser) || specRasterUrl != null
          }
        )
        .joinToString("") { "$it\n      " }
    // The Source lane uses the same vendored Kotlin grammar as the playground, but only on pages
    // that can actually offer source. CodeMirror is one of the deliberately selective heavy
    // assets: a component without a derivable usage snippet should not pay its ~114 kB wire cost.
    // viewer.js still paints a plain <pre><code> first, so either asset failing leaves readable
    // source rather than turning an optional highlighter into a lane dependency.
    val sourceCodeStylesheet =
      if (usageAvailable)
        "<link rel=\"stylesheet\" href=\"${assetHref("codemirror.css")}\">\n      "
      else ""
    val sourceCodeScriptTag = if (usageAvailable) "${scriptTag("codemirror.js")}\n      " else ""
    // The provenance row (source / playground / report an issue / figma spec) no longer sits under
    // the title. It is *about* the preview rather than a control over it, and four lines of small
    // links between the heading and the renderer controls is four lines of chrome between the
    // visitor and the render. It now rides directly above the export bar, where the other
    // "take this away with you" affordances (the PNG and SVG links) already live.
    val previewLinks =
      if (componentBrowser) ""
      else
        previewLinksHtml(
          sourceHref,
          preview.sourceFile,
          reportIssue,
          figmaSpec,
          playgroundHref,
          executableBundleHref,
        )
    // Every disclosure the page has, in one group, at the end of the identity row: the component
    // list, the state/variant axes, the theme chips, the overrides drawer. They were scattered —
    // two on the viewer bar, two implicit in rows that were simply always open — which is why the
    // page had no single answer to "what can I put away". Ordered as the surfaces they own read on
    // the page (left column, then the two rows below the title, then the right column), and each
    // closed one still names its current value, so folding a row never costs the fact it carried.
    // The render-history menu sits after Revision — both answer "which version of these pixels am
    // I looking at" — and before the Overrides drawer, which stays last where the thumb expects it.
    // `viewer-history.js` used to build the menu at runtime and then go looking for somewhere to
    // put it, with a fallback for a page whose toggle row predated it; the server knows where the
    // control belongs, so it declares the tag here and the placement question stops existing. The
    // element draws nothing at all when the timeline is too short to be one, so an empty tag is the
    // no-history case rather than an empty control.
    val historyMenu = if (historyAttrs.isEmpty()) "" else "<cp-history-menu></cp-history-menu>"
    val headToggles =
      listOf(navToggle, themeToggle, revisionMenu, historyMenu, controlsToggle).filter {
        it.isNotBlank()
      }
    val headTogglesHtml =
      if (headToggles.isEmpty()) ""
      else "\n        <div class=\"cp-head-toggles\">${headToggles.joinToString("")}</div>"
    val browserVariant =
      if (!componentBrowser) ""
      else if (browserVariantLabel.isBlank()) ""
      else "<p class=\"cp-browser-variant\">" + WebEscaping.htmlEscape(browserVariantLabel) + "</p>"
    // The component's authored one-line description, under its name. The catalog has always
    // written this (`@CatalogComponent(caption = …)`) and the browse surface has never shown it,
    // so every sheet named its components and left what they were FOR in the source: a reader who
    // does not already know what "Button Loading" is had nowhere on the page to find out. One
    // sentence, above the fold, in the design system's own words. Blank for a catalog that authors
    // none and for a plain uploaded bundle, which is most of them — so nothing shifts there.
    val captionHtml =
      preview.caption
        ?.takeIf { it.isNotBlank() }
        ?.let { "<p class=\"cp-preview-caption\">${WebEscaping.htmlEscape(it)}</p>" }
        .orEmpty()
    // Title, trust badge, id and the view tally on ONE baseline-aligned row. They are all
    // *identity* — three separate blocks said so three times, at the cost of ~90px above the fold.
    val body =
      """
      $sourceCodeStylesheet${if (browserBreadcrumb.isBlank()) "" else "$browserBreadcrumb\n      "}<div class="cp-preview-head">
        <h1 class="cp-head cp-preview-title">$label${compactTrustBadge(trust)}</h1>
        ${if (componentBrowser) "" else "<code class=\"cp-preview-id\" title=\"$idText\">$idText</code>"}
        ${if (componentBrowser) "" else viewerViewCountHtml(engagement.views)}$headTogglesHtml
      </div>${if (captionHtml.isBlank()) "" else "\n      $captionHtml"}${if (browserVariant.isBlank()) "" else "\n      $browserVariant"}
      $revisionBanner${degradeBanner(degradations)}$issueRows
      <div class="cp-preview-primary" aria-label="Preview renderer">
      $primaryControls${if (pinnedControlsNote.isBlank()) "" else "\n        $pinnedControlsNote"}
        <span class="cp-mode-hint" id="cp-mode-hint"></span>
        <span class="cp-modes-inputs" aria-hidden="true">
      $modeInputs
        </span>
      </div>
      $historyInlineHtml
      <div class="cp-viewer"$bgThemeAttr$alwaysDarkAttr$irReplayAttr$replayThemesAttr data-preview-id="$idText" data-mode="snapshot" data-modes="$modes" data-static-snapshot="$staticSnapshot" data-can-render-overrides="$canRenderOverrides" data-snapshot-backend="$backendLabel" data-live-backend="$liveLabel" data-render-density="$RENDER_DENSITY" data-fold-scope="${foldStorageScope(sessionId, basePath)}"$unseededAttr$wasmAttr$rcAttr$historyAttrs$pinnedAttr>
        $navDrawer
        <div class="cp-stage"><cp-backend-badge class="cp-backend" id="cp-backend" role="status" aria-live="polite"></cp-backend-badge><img id="cp-img" alt="$label"><canvas id="cp-canvas" hidden></canvas>$rcCanvas$wasmFrame$rcWasmFrame$specImg$motionImg$motionPlayer$sourcePanelHtml$specCompare$inspectLayerHtml$stageLiveHint<div class="cp-error" id="cp-error" role="alert" hidden></div></div>
        $inspectLegendHtml
        <div class="cp-controls" id="cp-controls">
          <!-- No "Appearance" group. Its only ever-visible control was a Background select
               offering "(default) / Clear (crisp outline)" — which read as a duplicate of the
               viewer bar's **Transparent** toggle: same word, same apparent job, two places, one
               of them buried behind a drawer. With that gone the group held nothing but the
               visually-hidden Theme state below, so an empty collapsible card would have sat at
               the top of every viewer's panel; the group goes with the control.

               Neither affordance is lost. Transparent still shows a preview's real alpha on the
               bar, and stripping a preview's *authored* background is still `background=clear` on
               /render (and the VS Code extension's own override) — the authoring lane, which is
               where it belongs, rather than the reading one.

               The Theme select stays in the panel, outside any group: it is `aria-hidden` and out
               of the tab order, but it is the Theme axis's single state holder — viewer.js reads
               it on every render and Back/Forward hydration writes to it — so it has to remain in
               the DOM. The visible Theme control is the chip row on the viewer bar. -->
          $themeSelectorHtml
          $sizeControlsHtml
          ${if (componentBrowser) "" else exportShapeGroupsHtml(hasScrollExport, hasSvgExport)}
          <details class="cp-group" data-cp-group="locale">
            <summary>Locale &amp; text</summary>
            <div class="cp-group-body">
              <label>Locale
                <input id="cp-localeTag" type="text" list="cp-localeTag-list" placeholder="e.g. en-GB, zh-Hant-TW" autocomplete="off"$wasmDis>
                <!-- A datalist, not a fixed <select>: the presets (pseudolocales, RTL, common
                     tags) drop down for quick picking, but any valid BCP-47 tag the server
                     accepts can still be typed in — so this is the OPEN form of the same value
                     set an author declares with `previewOverrideChoice`, rendered through the
                     same helper rather than hand-written twice. -->
                <datalist id="cp-localeTag-list">
                  ${datalistOptionsHtml(LOCALE_PRESETS, indent = "                  ")}
                </datalist>
              </label>
              <label>Font scale: <span id="cp-fontScale-val">default</span>
                <input id="cp-fontScale" type="range" min="0.5" max="2.0" step="0.1" value="1.0"$wasmDis>
              </label>
            </div>
          </details>
          $overlaysHtml
          $featureControlsHtml
          ${overrideKnobsHtml(preview, canApplyOverrides || canRenderOverrides, wasmSrc != null, requestOverrides)}
          ${if (componentBrowser) "" else remoteComposeKnobsHtml(preview, canApplyOverrides || canRenderOverrides || hasRcWasm, requestOverrides)}
          <div class="cp-status" id="cp-status"></div>
        </div>
      </div>
      <!-- Export remains below the workspace; renderer selection is kept beside the preview
           heading so it is visible before a tall stage. The export bar is a SIBLING of the note
           column rather than a child: the note is prose and reads better at `.cp-below`'s measure,
           while the bar has to run the full content width to stay on one line. -->
      <div class="cp-below">
        $snapshotNote
      </div>$previewLinks
      ${downloadLinksHtml(hasSvgExport)}${if (browserComponentNav.isBlank()) "" else "\n      $browserComponentNav"}
      <!-- Backdrop shown behind an open drawer on mobile (drawers become bottom sheets there);
           tapping it dismisses the sheet. Inert on desktop. -->
      <div class="cp-scrim" id="cp-scrim" aria-hidden="true"></div>
      <!-- Remembers which control drawers this visitor left open (`cp-grp.<id>` per
           `details.cp-group[data-cp-group]`). Renders nothing; `serve.css` hides the tag. -->
      <cp-group-memory></cp-group-memory>
      <!-- Resolve a deep-linked or remembered theme and publish the design-score baseline before
           the component bundle upgrades the comparison control. -->
      <script>${viewerThemeStickyScript(themeStorageKey(sessionId, basePath))}</script>
      ${scriptTag("serve-components.js")}
      <!-- The viewer's drawers, the phone row order, the theme toggle's value and the component
           filter. Renders nothing; `serve.css` hides the tag. -->
      <cp-viewer-drawers></cp-viewer-drawers>
      ${presenceScriptTag(presenceUrl)}
      $compareScriptTags$sourceCodeScriptTag${scriptTag("viewer.js")}$browserTabsScript
      """
        .trimIndent()
        .lineSequence()
        .joinToString("\n") { it.trimEnd() }
    return document(
      changelogHref = changelogHref,
      title = "$displayName — compose-preview",
      body = body,
      unfurlTitle = displayName,
      unfurlDescription = "Compose preview for $displayName",
      unfurl = unfurl,
      version = version,
      navSuffix = navSuffix,
      headerBreadcrumb =
        crumbHtml(
          "$basePath/$q",
          catalogTitle?.takeIf { it.isNotBlank() } ?: "Previews",
          "Component",
        ),
      themeCss = themeCss,
      siteName = catalogName,
      themeStorageKey = themeStorageKey(sessionId, basePath),
      declaredThemes = if (overridesLive) viewerDeclaredThemes else emptyList(),
      // Only the `js` chip paints in this document's canvas, and it only exists when the preview
      // carries a captured document.
      rcFonts = hasRemoteComposeDoc,
      componentBrowser = componentBrowser,
      interfaceModeControl = true,
    )
  }

  /**
   * The left-hand component-nav drawer: a filterable list of the session's [siblings], each linking
   * to its own viewer page (same `$basePath/p/<id>$q` shape the landing cards use). The current
   * [preview] is marked `aria-current="page"`. Returns "" when there is nothing to navigate *to* —
   * an empty [siblings], or a list whose only entry is [preview] itself — so a single-preview
   * session omits both the drawer and its toggle rather than showing a one-item self-link. (Callers
   * can pass the whole `renderHost.previews` list, current preview included, without special-casing
   * the single-preview module.) The drawer starts closed (the `cp-nav-open` class is absent from
   * `.cp-viewer` until the toggle adds it).
   */
  private fun navDrawerHtml(
    preview: ServePreview,
    siblings: List<ServePreview>,
    basePath: String,
    q: String,
    /**
     * The theme the viewer is currently showing (`"light"`/`"dark"`, or null when neither the
     * preview nor a dark-first catalog forces one). Each collapsed entry links to its component's
     * render in THIS theme when it has one, so navigating from a dark preview (or anywhere in a
     * dark-first Wear catalog) stays on the dark render instead of snapping back to light — the
     * same theme-preserving behaviour as the state/variant switchers.
     */
    theme: String?,
    axesTree: String = "",
    /**
     * The catalog's primary lane, so the size fold resolves per lane exactly as the grid's does.
     */
    darkFirst: Boolean = false,
  ): String {
    // Collapse to ONE entry per component — the same folding the landing grid does — so the nav
    // reads as a list of components, not of every baked state/theme/props/size permutation
    // (`button-filled` once, not ~14 times). The SIZE axis folds here for the same reason it folds
    // on the grid (#4279): a catalog documenting five breakpoints otherwise fills the drawer with
    // five identically-named rows per full-screen component — "Alert Dialog" five times over, with
    // nothing in the row to say which watch each one is. Each entry links to the component's render
    // in the viewer's current [theme] (falling back to its default when it has no such variant);
    // the viewer's own state/variant/size switchers reach that component's other axes.
    // `aria-current` pins the component being viewed, even when the current preview is a folded
    // (non-default) variant that has no card of its own.
    val navPrimarySizes = primarySizeByComponent(siblings, darkFirst)
    val representatives =
      groupPreviews(
          siblings.filterNot {
            it.renderFailure == null &&
              (isNonDefaultState(it) ||
                hasNonDefaultProps(it) ||
                isNonPrimarySize(it, navPrimarySizes, darkFirst))
          }
        )
        .map {
          when (theme) {
            "dark" -> it.dark ?: it.default
            "light" -> it.light ?: it.default
            else -> it.default
          }
        }
        // ONE row per component, decided AFTER the lane pick. A sparse theme × size product leaves
        // two survivors for one component (its light render at one breakpoint, its dark at another)
        // whose ids differ by size, so [groupPreviews] cannot pair them into a single card and the
        // drawer would name that component twice — once on a link that walks out of the theme being
        // viewed. Prefer the survivor already in the viewer's [theme]; a component with nothing in
        // that lane keeps its first survivor rather than vanishing from the list.
        .let { picked ->
          val byComponent = LinkedHashMap<String, ServePreview>()
          picked.forEach { p ->
            val existing = byComponent[componentKey(p)]
            if (existing == null || (theme != null && existing.theme != theme && p.theme == theme))
              byComponent[componentKey(p)] = p
          }
          byComponent.values.toList()
        }
        .sortedBy { if (componentKey(it) == componentKey(preview)) 0 else 1 }
    // Nothing to navigate to when the collapsed list is empty or holds only the current component.
    val currentKey = componentKey(preview)
    if (axesTree.isBlank() && representatives.none { componentKey(it) != currentKey }) return ""
    val listRepresentatives =
      if (axesTree.isBlank()) representatives
      else representatives.filterNot { componentKey(it) == currentKey }
    val items =
      listRepresentatives.joinToString("\n") { p ->
        val segItem = WebEscaping.urlEncodeSegment(p.id)
        val labelItem = WebEscaping.htmlEscape(previewDisplayName(p))
        val idItem = WebEscaping.htmlEscape(p.id)
        // data-search folds label + id so the drawer filter matches either. aria-current pins the
        // one we're viewing (styled as active, and it stays visible even under a filter miss so the
        // list never looks empty-of-self).
        val current = if (componentKey(p) == currentKey) " aria-current=\"page\"" else ""
        // The row's tooltip is the component's caption when it has one, and its preview id
        // otherwise. The id was the only thing here, which tells a reader who is already lost
        // exactly what they already knew — the name in slug form. The caption is the sentence that
        // answers "what IS this", which is the question a list of forty component names provokes.
        val tip = p.caption?.takeIf { it.isNotBlank() } ?: p.id
        // A small thumbnail render to the left of the name — the same baked PNG the landing cards
        // use, so the nav reads like a mini gallery. `alt=""` since the name label beside it
        // already
        // names the component (decorative image).
        "<li><a class=\"cp-nav-item\" href=\"$basePath/p/$segItem$q\"$current " +
          "title=\"${WebEscaping.htmlEscape(tip)}\" data-search=\"$labelItem $idItem\">" +
          "<img class=\"cp-nav-thumb\" loading=\"lazy\" alt=\"\" src=\"$basePath/render/$segItem.png$q\">" +
          "<span class=\"cp-nav-name\">$labelItem</span></a></li>"
      }
    return """
      <aside class="cp-nav" id="cp-nav" aria-label="Components">
        <div class="cp-nav-head"><span>Components</span><button type="button" class="cp-nav-close" id="cp-nav-close" aria-label="Close component navigation">×</button></div>
        <input type="search" class="cp-nav-search" id="cp-nav-search" placeholder="Filter components" autocomplete="off" aria-label="Filter components">
        ${if (axesTree.isBlank()) "" else "<div class=\"cp-nav-current\">$axesTree</div>"}
        <ul class="cp-nav-list" id="cp-nav-list">
        $items
        </ul>
        <p class="cp-nav-empty" id="cp-nav-empty" hidden>No components match.</p>
      </aside>
      """
      .trimIndent()
  }

  private fun document(
    title: String,
    body: String,
    unfurlTitle: String? = null,
    unfurlDescription: String? = null,
    unfurl: UnfurlMetadata? = null,
    navSuffix: String = "",
    headerAction: String = "",
    /**
     * The page's breadcrumb / back link, rendered in the header's brand slot by [siteHeader] rather
     * than as the body's first line — see that function for why. Empty (the front door, which is
     * already home) renders nothing.
     */
    headerBreadcrumb: String = "",
    /**
     * Running server version (the CLI's `SERVE_VERSION`), shown in the minimal [siteFooter] every
     * page ends with. Null omits just the build span; the fixture goldens pass a fixed string so a
     * release never churns the committed HTML.
     */
    version: String? = null,
    /**
     * The page's own block inside [siteFooter], above the source/`/version` links — the catalog
     * landing's provenance disclosure. Empty on every other page.
     */
    footerNote: String = "",
    /**
     * The served catalog's own palette, projected onto the chrome's custom properties by
     * [ServeThemeCss] and inlined after `serve.css` so it wins at equal specificity. Empty for a
     * plain module / a catalog that publishes no tokens — the page then uses the built-in chrome.
     */
    themeCss: String = "",
    /**
     * The catalog-scoped `localStorage` key this page's theme choice is remembered under (as
     * produced by [themeStorageKey]) — published to the client on `<html data-cp-theme-key>` and
     * read back by the pre-paint script and the Page theme setting, which need the remembered
     * choice to resolve the page's colour scheme. Empty for a page with no theme control at all
     * (the front door, `/status`, a shared document): those never pin a scheme.
     */
    themeStorageKey: String = "",
    /** The catalog this page belongs to, named in the header bar. See [siteHeader]. */
    siteName: String = "",
    /**
     * Declared themes whose resolved mode lets the head script paint correctly before first draw.
     */
    declaredThemes: List<ServeTheme> = emptyList(),
    /**
     * Register the vendored Remote Compose typefaces ([ServeRcFonts]) on this page. True for the
     * pages that play a `.rc` document **client-side** — without the faces the player's `Roboto,
     * sans-serif` request falls through to whatever the *viewer's* machine calls `sans-serif`, so
     * the same document renders in a different typeface, at different metrics and without the
     * Medium weight, depending on who is looking (issue #3480). Off elsewhere: the page chrome is
     * deliberately system-font, and a page with no canvas lane shouldn't carry the block.
     */
    rcFonts: Boolean = false,
    /** Streamlined component-browser chrome; full mode remains the default. */
    componentBrowser: Boolean = false,
    /** Show and persist the Catalog / Dev switch on pages that support both presentations. */
    interfaceModeControl: Boolean = false,
    /**
     * Offer the footer's "report a bug" entry. False only on the report page itself, which is what
     * that entry opens — see [reportBugFormHtml]. Independent of [componentBrowser], which drops
     * the footer altogether: this chooses what the footer contains, that chooses whether there is
     * one.
     */
    bugReport: Boolean = true,
    /**
     * The catalog change feed this page's footer links as **Changelog**, and that the head declares
     * as the page's RSS alternate so a reader's subscribe affordance finds it. Empty on every page
     * that belongs to no published catalog. See [siteFooter].
     */
    changelogHref: String = "",
  ): String {
    val unfurlHtml =
      if (unfurl == null) ""
      else {
        val metaTitle = WebEscaping.htmlEscape(unfurlTitle ?: title)
        val description =
          WebEscaping.htmlEscape(unfurlDescription ?: "Compose preview rendered by compose-preview")
        val pageUrl = WebEscaping.htmlEscape(unfurl.pageUrl)
        val imageUrl = unfurl.imageUrl?.let(WebEscaping::htmlEscape)
        // Only when both are known: a card given one axis has to measure the image anyway, and a
        // half-declared size is the one input an unfurler can't sanity-check against the pixels.
        val dimensionsHtml =
          if (unfurl.imageWidth == null || unfurl.imageHeight == null) ""
          else
            """

            <meta property="og:image:width" content="${unfurl.imageWidth}">
            <meta property="og:image:height" content="${unfurl.imageHeight}">"""
              .trimIndent()
        val imageHtml =
          if (imageUrl == null) ""
          else
            """
            <meta property="og:image" content="$imageUrl">
            <meta property="og:image:type" content="image/png">
            <meta property="og:image:alt" content="$metaTitle">$dimensionsHtml
            """
              .trimIndent()
        val twitterImageHtml =
          if (imageUrl == null) ""
          else
            """
            <meta name="twitter:image" content="$imageUrl">
            <meta name="twitter:image:alt" content="$metaTitle">
            """
              .trimIndent()
        """
        <meta property="og:type" content="website">
        <meta property="og:site_name" content="compose-preview">
        <meta property="og:title" content="$metaTitle">
        <meta property="og:description" content="$description">
        <meta property="og:url" content="$pageUrl">
        $imageHtml
        <meta name="twitter:card" content="${twitterCard(unfurl)}">
        <meta name="twitter:title" content="$metaTitle">
        <meta name="twitter:description" content="$description">
        $twitterImageHtml
        """
          .trimIndent()
      }
    val unfurlBlock = if (unfurlHtml.isEmpty()) "" else "\n${unfurlHtml.prependIndent("        ")}"
    val footerBlock =
      if (componentBrowser) ""
      else
        "\n${siteFooter(version, footerNote, bugReport, changelogHref).prependIndent("        ")}"
    // The floating launcher rides the same two conditions as the footer entry it duplicates from a
    // fixed position: dropped in component-browser mode (which has no site chrome at all) and on
    // the report page itself, where it would be a button back to the page you are already on.
    val launcherBlock =
      if (componentBrowser || !bugReport) ""
      else "\n${reportLauncherHtml(assetHref("report-capture.js")).prependIndent("        ")}"
    // Feed autodiscovery: the same document the footer's Changelog entry links, declared where a
    // reader's "subscribe to this page" affordance looks for it.
    val feedLink =
      changelogHref
        .takeIf { it.isNotBlank() }
        ?.let {
          "\n        <link rel=\"alternate\" type=\"application/rss+xml\"" +
            " title=\"Catalog changes\" href=\"${WebEscaping.htmlEscape(it)}\">"
        } ?: ""
    // Before `themeCss`, so a catalog palette still wins at equal specificity; the font block
    // declares faces only and collides with nothing in the chrome.
    val rcFontsBlock = if (rcFonts) "\n" + ServeRcFonts.linkTag().prependIndent("        ") else ""
    val themeBlock =
      themeCss
        .takeIf { it.isNotBlank() }
        ?.let { "\n" + ("<style>\n" + it.trimEnd() + "\n</style>").prependIndent("        ") } ?: ""
    val themeKeyAttr =
      themeStorageKey
        .takeIf { it.isNotBlank() }
        ?.let { " data-cp-theme-key=\"${WebEscaping.htmlEscape(it)}\"" } ?: ""
    // Carry the mode over from the `localStorage` key this switch used before it became a cookie,
    // so an upgrade doesn't quietly drop a visitor back into the server's default presentation. It
    // clears the key whatever it held, so it runs at most once per browser and the whole block can
    // be deleted a release or two from now. The reload is what makes the carried-over mode take
    // effect: the server has already rendered this page, and it rendered it without the cookie.
    val interfaceModeBoot =
      if (interfaceModeControl)
        "\n        " +
          """<script>try{var s=localStorage.getItem("cp-interface-mode");if(s){localStorage.removeItem("cp-interface-mode");if((s==="catalog"||s==="dev")&&document.cookie.indexOf("$INTERFACE_MODE_COOKIE=")<0){document.cookie="$INTERFACE_MODE_COOKIE="+s+"$INTERFACE_MODE_COOKIE_ATTRS"+(location.protocol==="https:"?"; secure":"");if(document.cookie.indexOf("$INTERFACE_MODE_COOKIE="+s)>=0&&!/[?&]chrome=/.test(location.search))location.reload();}}}catch(e){}</script>"""
      else ""
    // The switch itself: remember the choice in the cookie the server reads, drop any `?chrome=`
    // the current URL pinned (an explicit permalink outranks the cookie, so leaving it on would
    // make the button appear to do nothing), and reload into the chosen mode. Nothing rewrites the
    // page's links any more — the cookie travels on its own.
    val interfaceModeControls =
      if (interfaceModeControl)
        "\n        " +
          """<script>(function(){var key="$INTERFACE_MODE_COOKIE";document.querySelectorAll("[data-cp-interface-mode]").forEach(function(b){b.addEventListener("click",function(){var mode=b.getAttribute("data-cp-interface-mode");if(mode!=="catalog"&&mode!=="dev")return;try{document.cookie=key+"="+mode+"$INTERFACE_MODE_COOKIE_ATTRS"+(location.protocol==="https:"?"; secure":"");}catch(e){}var u=new URL(location.href);u.searchParams.delete("chrome");if(document.cookie.indexOf(key+"="+mode)<0)u.searchParams.set("chrome",mode);var q=u.searchParams.toString();location.assign(u.pathname+(q?"?"+q:"")+u.hash);});});})();</script>"""
      else ""
    // `serve-chrome.js` is emitted as the first thing in <body>, ahead of every surface's own
    // scripts, because they read the globals it installs: the component bundle's Transparent
    // toggle wires Back through the URL-state global as it upgrades, and three of the legacy
    // enhancement scripts read it at their own IIFE time. It is unconditional because it also
    // carries the Page theme setting, which every page has — and it can afford to be, at ~1 kB
    // gzipped with no Lit in it. Deliberately NOT commented in the emitted HTML: a note naming
    // those script files would ship to every visitor, and `html.contains("format-compare.js")` is
    // exactly how several tests ask whether a lane is loaded.
    return """
    <!doctype html>
    <html lang="en"$themeKeyAttr>
      <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">$unfurlBlock
        <title>${WebEscaping.htmlEscape(title)}</title>
${ServeSiteIcon.linkTags().prependIndent("        ")}
        <link rel="stylesheet" href="${assetHref("serve.css")}">$feedLink$rcFontsBlock$themeBlock$interfaceModeBoot
        <!-- Apply the Transparent choice before first paint (no checkerboard flash).
             A `?bg=` on the URL is an explicit, shareable choice and outranks the sticky one. -->
        <script>try{var b=new URLSearchParams(location.search).get("bg");if(b?b==="off":localStorage.getItem("cp-bg")==="off")document.documentElement.classList.add("cp-bg-transparent");}catch(e){}</script>
        ${pageThemeScript(themeStorageKey, declaredThemes)}
      </head>
      <body${if (componentBrowser) " class=\"cp-component-browser\"" else ""}>
        ${scriptTag("serve-chrome.js")}
        ${siteHeader(navSuffix, headerAction, headerBreadcrumb, siteName, componentBrowser, interfaceModeControl, themeStorageKey.isNotBlank() && interfaceModeControl)}
        <main class="cp-main">
        $body
        </main>$footerBlock$launcherBlock$interfaceModeControls
        ${if (componentBrowser) "" else scriptTag("keyboard-navigation.js")}
      </body>
    </html>
    """
      .trimIndent() + "\n"
  }

  /**
   * Pin the page's colour scheme to the selected preview theme **before first paint**, when the
   * Page theme setting is on (its default — see `chrome/pageTheme.ts` for why it is a setting).
   *
   * Inline and in the `<head>` for the same reason the Transparent restore above is: resolving this
   * from the deferred shell bundle would paint the page in the wrong mode first and correct it a
   * frame later, which on a dark-to-light swap is a full-screen flash. It is deliberately the whole
   * resolution rather than a call into that file — the file has not loaded yet.
   *
   * The order is the same one the grid and the viewer use for the theme itself: the URL wins
   * (`?theme=` on a catalog landing, `?uiMode=` in the viewer — someone picked that chip or was
   * handed the link), then the choice this catalog remembers. A declared theme moves the chrome
   * when [ServeTheme.mode] is unambiguous; unqualified themes still follow the visitor's OS.
   */
  private fun pageThemeScript(themeStorageKey: String, declaredThemes: List<ServeTheme>): String {
    val storedTheme =
      themeStorageKey
        .takeIf { it.isNotBlank() }
        ?.let {
          "||(((decodeURIComponent(location.pathname).split('/').pop()||\"\")" +
            ".match(/(?:^|__)(light|dark)(?:__|$)/)||[])[1]||" +
            "localStorage.getItem(${WebEscaping.jsString(it)}))"
        } ?: ""
    val modeEntries = declaredThemes.mapNotNull { theme ->
      theme.mode?.let { mode ->
        WebEscaping.jsString("theme:${theme.providerFqn}") + ":" + WebEscaping.jsString(mode)
      }
    }
    val modeInit =
      modeEntries.takeIf { it.isNotEmpty() }?.let { "m={${it.joinToString(",")}}," } ?: ""
    val modeResolve = if (modeEntries.isEmpty()) "" else "t=m[t]||t;"
    return "<script>try{var p=new URLSearchParams(location.search),$modeInit" +
      "t=localStorage.getItem(\"cp-page-theme\")===\"system\"?\"\"" +
      ":(p.get(\"theme\")||(p.get(\"themeProvider\")?\"theme:\"+p.get(\"themeProvider\"):\"\")||p.get(\"uiMode\")$storedTheme);" +
      modeResolve +
      "if(t===\"light\"||t===\"dark\")document.documentElement.classList.add(\"cp-scheme-\"+t);" +
      "}catch(e){}</script>"
  }

  /**
   * The export bar: the `/render/<id>.png` (and, when [hasSvgExport], `.svg`) URL for the preview
   * **with the current overrides applied**, offered as three plainly-named actions per format —
   * "Copy link" (the shareable, `curl`-able render URL), "Copy PNG"/"Copy SVG" (the rendered
   * artefact itself onto the clipboard: real `image/png` bytes, or SVG markup verbatim), and
   * "Download" (`<a download>`). The viewer JS keeps the URLs in sync as the controls / knobs
   * change (see `refreshLinks`), so whatever is copied always reflects the on-screen state. The
   * URLs are built client-side from `location.origin` + the session base, so they're absolute and
   * work from anywhere; the `#cp-url-<ext>` fields that hold them start empty and are filled on
   * first render.
   *
   * Two deliberate shapes here:
   * * It is **one always-visible line**, not a `<details>`. Grabbing the URL / PNG / SVG of what's
   *   on screen is the viewer's primary hand-off; a disclosure hid the whole hand-off behind a
   *   click, and a URL field per format wrapped the row onto three lines for no one's benefit.
   * * The URL itself lives in a `tabindex="-1"` field the CSS takes out of the flow rather than on
   *   screen: an 200-character absolute `/render` URL is not something anyone reads, and "Copy
   *   link" says what the field's `title="Click to copy"` never managed to. It stays a real input
   *   because `refreshLinks` and both copy buttons read it, and it is what the lane e2e asserts on.
   *
   * The one control that genuinely *shapes* the export — "Full page (scroll)" — lives in the
   * overrides drawer's Scroll group instead ([scrollGroupHtml]).
   */
  private fun downloadLinksHtml(hasSvgExport: Boolean): String {
    fun group(kind: String, ext: String): String =
      """
      <span class="cp-link-group">
        <span class="cp-link-kind">$kind</span>
        <button type="button" class="cp-copyurl" data-copyurl-target="cp-url-$ext"
          title="Copy the $kind URL of the current view (overrides applied)">Copy link</button>
        <button type="button" class="cp-copyimg" data-copyimg-target="cp-url-$ext"
          data-copyimg-ext=".$ext" title="Copy the $kind itself to the clipboard">Copy $kind</button>
        <a id="cp-dl-$ext" class="cp-dl" download title="Save the $kind to a file">Download</a>
        <input id="cp-url-$ext" class="cp-url" type="text" readonly tabindex="-1"
          aria-label="$kind URL">
      </span>
      """
        .trimIndent()
    // The SVG lane is export-only now (no on-screen SVG mode); its shape is controlled by the
    // "Full page (scroll)" toggle over in the overrides drawer's Scroll group.
    val svgGroup = if (hasSvgExport) "\n" + group("SVG", "svg") else ""
    return """
      <div class="cp-export" aria-label="Export the current view">
        <span class="cp-export-head" id="cp-export-head">Export</span>
        ${group("PNG", "png")}$svgGroup
      </div>
      """
      .trimIndent()
  }

  /**
   * The drawer groups that shape the *export* rather than the render — Scroll and Exploded 3D —
   * joined into one slot so a session that offers neither contributes nothing at all. (Interpolated
   * separately, an absent group left a blank line in every viewer that can't export SVG, which is
   * most of them.)
   */
  private fun exportShapeGroupsHtml(hasScrollExport: Boolean, hasSvgExport: Boolean): String =
    listOf(scrollGroupHtml(hasScrollExport, hasSvgExport), explodeGroupHtml(hasSvgExport))
      .filter { it.isNotBlank() }
      .joinToString("\n          ")

  /**
   * The overrides drawer's "Exploded 3D" group: the camera and separation knobs behind the viewer
   * bar's **3D** toggle.
   *
   * The toggle alone is the whole feature for most visitors — the defaults are the readable preset
   * — so the axes live in the drawer rather than on the bar, next to the other things that shape an
   * export. They are `<input type="range">` rather than numbers because nobody knows what tilt they
   * want in degrees; they know it when they see it, and the SVG re-projects per drag.
   *
   * Every knob carries `data-cp-default`, which is what lets the viewer JS omit an untouched axis
   * from the URL (so the common link stays `?exploded=1`) and reset it on a Back that drops the
   * param. The values must therefore stay equal to `ExplodedSvg.Options`' own defaults; the fixture
   * test is what notices when they drift apart.
   *
   * Empty when the session can't export SVG at all — there is no layered vector to pull apart.
   */
  private fun explodeGroupHtml(hasSvgExport: Boolean): String {
    if (!hasSvgExport) return ""
    fun slider(
      id: String,
      label: String,
      min: String,
      max: String,
      step: String,
      default: String,
      unit: String,
      hint: String,
    ): String =
      """
      <label class="cp-explode-row" title="$hint">$label
        <input id="cp-explode-$id" class="cp-explode-knob" type="range" min="$min" max="$max"
          step="$step" value="$default" data-cp-default="$default" data-cp-unit="$unit" disabled>
        <output id="cp-explode-$id-value" class="cp-explode-value">$default$unit</output>
      </label>
      """
        .trimIndent()
    return """
      <details class="cp-group" data-cp-group="explode">
        <summary>Exploded 3D</summary>
        <div class="cp-group-body">
          ${slider("tilt", "Lean", "0", "75", "1", "28", "°", "How far the layers lean away from you; 0 is face-on").prependIndent("          ").trimStart()}
          ${slider("spin", "Spin", "-80", "80", "1", "-16", "°", "How far the layers are turned in their own plane").prependIndent("          ").trimStart()}
          ${slider("gap", "Separation", "0", "600", "5", "0", "", "Distance between layers; 0 derives one from the preview's size").prependIndent("          ").trimStart()}
          ${slider("depth", "Layers", "1", "16", "1", "6", "", "Composables nested deeper than this fold into the last layer").prependIndent("          ").trimStart()}
          <div class="cp-knobs-head">One sheet per visible drawing level. Structural-only
            composables are kept in the next sheet's breadcrumb. Rides the SVG link and download.</div>
        </div>
      </details>
      """
      .trimIndent()
  }

  /**
   * The overrides drawer's Scroll group: "Full page (scroll)", which points the copyable /
   * downloadable PNG and SVG exports at the full-page `?scroll=long` render of a scrolling preview
   * (a tall Wear capsule / grown LazyColumn) instead of the viewport-sized image. It's an override
   * on what gets rendered — not a link — so it sits with the other axes in the drawer rather than
   * in the always-visible export section. The viewer JS (`withScroll`) folds it into both export
   * URLs; empty when the session can't export SVG at all.
   */
  private fun scrollGroupHtml(hasScrollExport: Boolean, hasSvgExport: Boolean): String =
    if (!hasScrollExport) ""
    else
      """
      <details class="cp-group" data-cp-group="scroll">
        <summary>Scroll</summary>
        <div class="cp-group-body">
          <label class="cp-live-row"><input id="cp-scroll-long" type="checkbox"> Full page (scroll)</label>
          <div class="cp-knobs-head">Exports the whole scrollable page as PNG${if (hasSvgExport) " or SVG" else ""}.</div>
        </div>
      </details>
      """
        .trimIndent()

  /**
   * Renders the preview's author-declared editable knobs (the `compose/overrides` payload carried
   * in a bundle's `previews/<id>.overrides.json`) as a labelled control list. Indexed knobs
   * (per-item values on a repeated component) are grouped under their base key with a `#<index>`
   * suffix. The controls are live when [canApplyOverrides] (a daemon re-renders the edit) **or**
   * [wasmAvailable] (the in-browser catalog app seeds its `catalogOverride*` from the edit); a
   * plain static bundle with neither leaves them disabled with a one-line note. Empty string when
   * the preview declared no knobs (the common case).
   */
  /**
   * The fonts.google.com family names offered in a font knob's autocomplete, loaded once from the
   * committed `google-fonts.txt` classpath resource (regenerated by
   * `scripts/fonts/build-google-fonts-list.mjs`). Lines starting with `#` are provenance and
   * skipped. Empty if the resource is somehow absent — a font knob's datalist then carries only its
   * declared [PreviewOverrideDeclaration.suggestions].
   */
  /**
   * The locale field's **value set** — the tags worth offering, each with the name the picker
   * shows.
   *
   * Open rather than exhaustive: these drop down for quick picking, and any valid BCP-47 tag the
   * server accepts stays typeable, which is why the control remains an `<input list>` rather than
   * becoming a `<select>`. Declared here as data so it renders through the same
   * [datalistOptionsHtml] an author-declared value set does instead of being hand-written HTML —
   * the labels are the whole reason a bare tag list is a poor control, and they were previously
   * spelled out inline where nothing could reuse them.
   *
   * Pseudolocales lead (they are the reason to reach for this control at all), then the real RTL
   * languages, then common tags.
   */
  private val LOCALE_PRESETS: List<PreviewOverrideOption> =
    listOf(
      PreviewOverrideOption("en-XA", "Accented (pseudo)"),
      PreviewOverrideOption("ar-XB", "Bidi / RTL (pseudo)"),
      PreviewOverrideOption("ar", "Arabic (RTL)"),
      PreviewOverrideOption("he", "Hebrew (RTL)"),
      PreviewOverrideOption("fa", "Persian (RTL)"),
      PreviewOverrideOption("en-US"),
      PreviewOverrideOption("en-GB"),
      PreviewOverrideOption("de-DE"),
      PreviewOverrideOption("fr-FR"),
      PreviewOverrideOption("es-ES"),
      PreviewOverrideOption("pt-BR"),
      PreviewOverrideOption("ru-RU"),
      PreviewOverrideOption("ja-JP"),
      PreviewOverrideOption("ko-KR"),
      PreviewOverrideOption("zh-CN"),
      PreviewOverrideOption("zh-Hant-TW"),
      PreviewOverrideOption("hi-IN"),
      PreviewOverrideOption("th-TH"),
    )

  private val googleFontFamilies: List<String> by lazy {
    ServeWeb::class
      .java
      .classLoader
      .getResourceAsStream("ee/schimke/composeai/cli/serve/google-fonts.txt")
      ?.bufferedReader()
      ?.useLines { lines ->
        lines.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toList()
      }
      .orEmpty()
  }

  /**
   * `<option>`s for a font knob's `<datalist>`: the declared [suggestions] first (so "by default
   * show the typography catalog" holds), then — when [googleFonts] — the full fonts.google.com
   * list, de-duplicated (a suggestion that's also a Google family isn't repeated). Order is
   * preserved.
   */
  private fun fontDatalistOptions(suggestions: List<String>, googleFonts: Boolean): String {
    val seen = LinkedHashSet<String>()
    suggestions.forEach { if (it.isNotBlank()) seen.add(it) }
    if (googleFonts) seen.addAll(googleFontFamilies)
    return datalistOptionsHtml(seen.map { PreviewOverrideOption(it) })
  }

  /**
   * `<option>`s for a **`<datalist>`** — the open form of a value set, where the field stays
   * free-text and the options are a shortlist.
   *
   * A value whose label differs from it carries `label=`, which is what lets the locale presets
   * read "Accented (pseudo)" while seeding `en-XA`; a self-labelling value emits the bare `value=`
   * a font family always did, so a font knob's markup is unchanged.
   */
  private fun datalistOptionsHtml(
    options: List<PreviewOverrideOption>,
    /**
     * Leading whitespace for each line after the first. A template interpolation only indents where
     * the `$…` sits, so a multi-line block otherwise lands flush against the margin — invisible in
     * a browser, but the viewer pages are checked in as golden fixtures and read by humans there.
     */
    indent: String = "",
  ): String =
    options.joinToString("\n$indent") { o ->
      val value = WebEscaping.htmlEscape(o.value)
      if (o.label == o.value) "<option value=\"$value\"></option>"
      else "<option value=\"$value\" label=\"${WebEscaping.htmlEscape(o.label)}\"></option>"
    }

  /**
   * `<option>`s for a **`<select>`** — the closed form, where [selected] is the value the control
   * opens on.
   *
   * A [selected] outside the set is emitted as an extra leading option rather than dropped. The set
   * is what the *author* declared, and a render can still be reached carrying something else (a
   * hand-written `knob.size=xxl`, a link from before a value was renamed); showing it keeps the
   * control honest about what is on screen, where silently snapping to the first option would lie.
   */
  private fun selectOptionsHtml(options: List<PreviewOverrideOption>, selected: String): String {
    val known = options.any { it.value == selected }
    val all = if (known) options else listOf(PreviewOverrideOption(selected)) + options
    return all.joinToString("\n") { o ->
      val active = if (o.value == selected) " selected" else ""
      "<option value=\"${WebEscaping.htmlEscape(o.value)}\"$active>" +
        "${WebEscaping.htmlEscape(o.label)}</option>"
    }
  }

  /**
   * A knob's boolean text, read the way [ServeOverrides.parse] reads one: `true` for `1` or `true`
   * in any case.
   *
   * Case-insensitive because the parser is (`equals("true", ignoreCase = true)`), and the parser is
   * what decides the pixels. `?knob.enabled=bool:TRUE` is an accepted deep link that renders true,
   * so a control testing only the lowercase spelling would draw the box unticked beside it. The
   * declaration's own text is always `true` / `false` — `Boolean.toString()` — so this widened rule
   * changes nothing for a plain visit.
   */
  private fun boolText(raw: String): String =
    if (raw == "1" || raw.equals("true", ignoreCase = true)) "true" else "false"

  private fun overrideKnobsHtml(
    preview: ServePreview,
    canApplyOverrides: Boolean,
    wasmAvailable: Boolean = false,
    requestOverrides: Map<String, String> = emptyMap(),
  ): String {
    if (preview.overrides.isEmpty()) return ""
    // Editable when the server can re-render (canApplyOverrides) OR an in-browser app can honour
    // the
    // edit (wasmAvailable — its `catalogOverride*` seed from the `knob.<key>` patch). A plain
    // static
    // bundle with neither shows *what* is editable but stays disabled. The viewer JS collects
    // `.cp-knob` values into `knob.<key>=<value>` params.
    val editable = canApplyOverrides || wasmAvailable
    val dis = if (editable) "" else " disabled"
    val rows =
      preview.overrides.joinToString("\n") { d ->
        val name = if (d.index == null) d.key else "${d.key} #${d.index}"
        val label = WebEscaping.htmlEscape(name)
        // Daemon map key: base key, plus `[index]` for an indexed (per-item) knob.
        val rawWireKey = if (d.index == null) d.key else "${d.key}[${d.index}]"
        val wireKey = WebEscaping.htmlEscape(rawWireKey)
        val kind = knobKind(d.type)
        // What the preview DECLARES: the author default, or the `@OverrideVariant` seed on a
        // synthetic variant. Both `data-*` attributes below are read off this, never off the
        // request.
        val declared = overrideValueText(d.current ?: d.default)
        // …and what THIS REQUEST asks for, which is not the same question. A deep link names values
        // the declaration doesn't — `?knob.secondary=true`, a copied "Direct links — overrides
        // applied" URL, the viewer link in a bug report — and the control has to OPEN on those or
        // the page disagrees with its own address: the snapshot `<img>` carries the query, so it
        // shows the override while everything that reads the CONTROLS instead (the live socket's
        // `setOverrides`, the export links, the next `/render`) sends the declared value.
        // `hydrateFromUrl` corrects this client-side on load; seeding it here is what stops the
        // first paint from disagreeing, and what keeps the markup honest for anything reading it
        // without running the viewer's JS.
        // …with any legacy `<kind>:` wire tag stripped exactly where the parser strips it, so
        // `?knob.count=int:3` puts `3` in the number input rather than `int:3` — which the browser
        // sanitizes to empty, leaving the control blank beside a render that used the value.
        val shown =
          requestOverrides[ServeOverrides.KNOB_PREFIX + rawWireKey]?.let {
            ServeOverrides.knobControlValue(it, kind)
          } ?: declared
        val value = WebEscaping.htmlEscape(shown)
        // `data-knob-initial` stays the DECLARED value even when the request seeds another, and
        // that gap is load-bearing rather than an oversight: the viewer omits a knob still equal to
        // it, so a plain visit carries no `knob.*` and the published catalog serves the instant
        // baked PNG rather than waking the daemon for a fresh (slower, subtly different) re-render
        // — while a deep-linked knob DIFFERS from it and therefore rides into every render the page
        // asks for. Pointing this at the request would swallow exactly the override the visitor
        // came for.
        val bool = kind == "bool"
        val initial = if (bool) boolText(declared) else WebEscaping.htmlEscape(declared)
        // …and `data-knob-default` is the AUTHOR default, which for a seeded variant is not the
        // same thing. A `@OverrideVariant` preview opens on `current` (`enabled=false`) while its
        // author default is `true`, and the Wasm tier — unlike the PNG lane — has no baked artifact
        // carrying that seed: it mounts the live component and has to be told. So the Wasm patch
        // compares against this rather than against `initial`, or a variant would mount as its
        // primary (see `wasmOverridePatch`).
        val authorDefault = overrideValueText(d.default)
        val defaultAttr =
          if (bool) boolText(authorDefault) else WebEscaping.htmlEscape(authorDefault)
        val attrs =
          "class=\"cp-knob\" data-knob-key=\"$wireKey\" data-knob-kind=\"$kind\" " +
            "data-knob-initial=\"$initial\" data-knob-default=\"$defaultAttr\""
        if (bool) {
          val checked = if (boolText(shown) == "true") " checked" else ""
          "<label class=\"cp-live-row\"><input type=\"checkbox\" $attrs$checked$dis> $label</label>"
        } else if (d.optionsExhaustive && d.options.isNotEmpty()) {
          // A CLOSED value set (`previewOverrideChoice`): every value is on screen and nothing else
          // is expressible, so this is a `<select>` rather than a field the visitor has to already
          // know the vocabulary for. `xs`/`s`/`m`/`l`/`xl` was previously a text box showing `s` —
          // the current value was visible, the alternatives were not.
          //
          // The viewer JS needs no branch for it: it reads `.value` / `.disabled` off the control
          // and only special-cases `type === "checkbox"`, which a `<select>` (`select-one`) is not.
          """
          <label>${label}
            <select $attrs$dis>
          ${selectOptionsHtml(d.options, shown)}
            </select>
          </label>
          """
            .trimIndent()
        } else {
          val inputType = if (d.type == "int" || d.type == "float") "number" else "text"
          // Any knob that carries discovered options — a font knob (declared via
          // `previewOverrideFont` / `catalogOverrideFont`, with autocomplete suggestions and/or the
          // Google Fonts flag), a non-exhaustive value set, or any other knob with declared
          // `suggestions` (e.g. `theme.colors`) — renders as a combobox "like Locale": a free-text
          // `<input list>` bound to a `<datalist>` (declared names first, then, for a font knob,
          // the
          // full fonts.google.com list). Any knob with no options stays a plain text/number input.
          val hasOptions = d.googleFonts || d.suggestions.isNotEmpty() || d.options.isNotEmpty()
          if (hasOptions) {
            val listId = "cp-dl-" + wireKey.replace(Regex("[^A-Za-z0-9_-]"), "-")
            val options =
              if (d.options.isNotEmpty()) datalistOptionsHtml(d.options)
              else fontDatalistOptions(d.suggestions, d.googleFonts)
            """
            <label>${label}
              <input type="$inputType" $attrs value="$value" list="$listId"$dis>
              <datalist id="$listId">
            $options
              </datalist>
            </label>
            """
              .trimIndent()
          } else {
            """
            <label>${label}
              <input type="$inputType" $attrs value="$value"$dis>
            </label>
            """
              .trimIndent()
          }
        }
      }
    val note =
      when {
        canApplyOverrides -> "Declared overrides — edit a value to re-render."
        wasmAvailable -> "Declared overrides — edit a value to apply it in the browser (Wasm)."
        else -> "Declared overrides — static bundle, values are baked in."
      }
    return """
      <details class="cp-group" data-cp-group="overrides">
        <summary>Overrides</summary>
        <div class="cp-group-body">
          <div class="cp-knobs">
            <div class="cp-knobs-head">$note</div>
            $rows
          </div>
        </div>
      </details>
      """
      .trimIndent()
  }

  /**
   * Map a declaration's `type` string to the [PreviewOverrideValue] wire kind the daemon expects.
   */
  private fun knobKind(type: String): String = ServeOverrides.knobKind(type)

  /** Human text for a [ee.schimke.composeai.data.overrides.PreviewOverrideValue] in the viewer. */
  private fun overrideValueText(
    v: ee.schimke.composeai.data.overrides.PreviewOverrideValue
  ): String =
    when (v) {
      is ee.schimke.composeai.data.overrides.PreviewOverrideValue.StringValue -> v.value
      is ee.schimke.composeai.data.overrides.PreviewOverrideValue.IntValue -> v.value.toString()
      is ee.schimke.composeai.data.overrides.PreviewOverrideValue.FloatValue -> v.value.toString()
      is ee.schimke.composeai.data.overrides.PreviewOverrideValue.BooleanValue -> v.value.toString()
      is ee.schimke.composeai.data.overrides.PreviewOverrideValue.ColorValue -> v.argb
    }

  /**
   * Renders the preview's declared **Remote Compose** named-value knobs (the
   * `compose/remotecompose` payload carried in a bundle's `previews/<id>.remotecompose.json`) as a
   * labelled control list — the RC counterpart of [overrideKnobsHtml]. One control per knob
   * (checkbox for bool, number for int / float / dp, text for string and `#AARRGGBB` colour), whose
   * edits round-trip through the `rc.<name>=<kind>:<value>` render param ([ServeOverrides] parses
   * it back into `PreviewOverrides.remoteCompose.namedValues`). Live when [canApplyOverrides]
   * includes server rendering or the CMP/Wasm host; a plain static bundle without either player
   * shows the controls disabled with a one-line note. Empty string when the preview declared no RC
   * knobs (the common case). The controls are marked `.cp-rc-knob` and carry `data-rc-name` /
   * `data-rc-kind` / `data-rc-initial`; the viewer JS collects them into typed values and routes
   * edits through the active player.
   */
  private fun remoteComposeKnobsHtml(
    preview: ServePreview,
    canApplyOverrides: Boolean,
    requestOverrides: Map<String, String> = emptyMap(),
  ): String {
    if (preview.remoteComposeKnobs.isEmpty()) return ""
    // A static bundle without either a server renderer or CMP/Wasm keeps these informational.
    val dis = if (canApplyOverrides) "" else " disabled"
    val rows =
      preview.remoteComposeKnobs.joinToString("\n") { d ->
        val label = WebEscaping.htmlEscape(d.name)
        val wireName = WebEscaping.htmlEscape(d.name)
        val kind = rcKnobKind(d.default)
        val declared = rcKnobValueText(d.default)
        // The request's value for this knob, seeded onto the control exactly as `overrideKnobsHtml`
        // seeds a declared one and for the same reason — but under RC's own typing rules, which are
        // stricter: an RC seed carries its kind on the wire and defaults to `string` with no
        // declaration lookup, so only a seed that will PARSE as this knob's kind may be shown.
        // `ServeOverrides.rcControlValue` holds that rule and returns null for the rest, leaving
        // the
        // control on what the render actually used.
        val shown =
          requestOverrides[ServeOverrides.RC_NAMED_PREFIX + d.name]?.let {
            ServeOverrides.rcControlValue(it, kind)
          } ?: declared
        val value = WebEscaping.htmlEscape(shown)
        // `data-rc-initial` is the AUTHOR default, not what the control opens on when a deep link
        // seeds it — same load-bearing gap as `data-knob-initial`: the viewer omits a knob still
        // equal to it, so a plain visit carries no `rc.*` and a published catalog serves the
        // instant baked snapshot, while a deep-linked value differs and rides into the render.
        val attrs =
          "class=\"cp-rc-knob\" data-rc-name=\"$wireName\" data-rc-kind=\"$kind\" " +
            "data-rc-initial=\"${WebEscaping.htmlEscape(declared)}\""
        if (kind == "bool") {
          // `true` OR `1`, the same rule the parser and `hydrateFromUrl` read a bool by. Testing
          // only for `true` was safe while this always rendered the declaration (whose text is
          // `true`/`false`); a deep-linked `rc.enabled=bool:1` is a real value the render obeys and
          // would have drawn the box unticked beside it.
          val checked = if (boolText(shown) == "true") " checked" else ""
          "<label class=\"cp-live-row\"><input type=\"checkbox\" $attrs$checked$dis> $label</label>"
        } else {
          val inputType = if (kind == "int" || kind == "float" || kind == "dp") "number" else "text"
          """
          <label>${label}
            <input type="$inputType" $attrs value="$value"$dis>
          </label>
          """
            .trimIndent()
        }
      }
    val note =
      if (canApplyOverrides) "Declared Remote Compose knobs — edit a value to re-render."
      else "Declared Remote Compose knobs — static bundle, values are baked in."
    return """
      <details class="cp-group" data-cp-group="remotecompose">
        <summary>Remote Compose</summary>
        <div class="cp-group-body">
          <div class="cp-knobs">
            <div class="cp-knobs-head">$note</div>
            $rows
          </div>
        </div>
      </details>
      """
      .trimIndent()
  }

  /** The `<kind>` wire tag for a Remote Compose knob's typed default (see `RemoteNamedValue`). */
  private fun rcKnobKind(v: ee.schimke.composeai.daemon.protocol.RemoteNamedValue): String =
    when (v) {
      is ee.schimke.composeai.daemon.protocol.RemoteNamedValue.FloatValue -> "float"
      is ee.schimke.composeai.daemon.protocol.RemoteNamedValue.DpValue -> "dp"
      is ee.schimke.composeai.daemon.protocol.RemoteNamedValue.IntValue -> "int"
      is ee.schimke.composeai.daemon.protocol.RemoteNamedValue.StringValue -> "string"
      is ee.schimke.composeai.daemon.protocol.RemoteNamedValue.BooleanValue -> "bool"
      is ee.schimke.composeai.daemon.protocol.RemoteNamedValue.ColorValue -> "color"
    }

  /**
   * Human/edit text for a Remote Compose knob's typed default; colour is its `#AARRGGBB` string.
   */
  private fun rcKnobValueText(v: ee.schimke.composeai.daemon.protocol.RemoteNamedValue): String =
    when (v) {
      is ee.schimke.composeai.daemon.protocol.RemoteNamedValue.FloatValue -> v.value.toString()
      is ee.schimke.composeai.daemon.protocol.RemoteNamedValue.DpValue -> v.value.toString()
      is ee.schimke.composeai.daemon.protocol.RemoteNamedValue.IntValue -> v.value.toString()
      is ee.schimke.composeai.daemon.protocol.RemoteNamedValue.StringValue -> v.value
      is ee.schimke.composeai.daemon.protocol.RemoteNamedValue.BooleanValue -> v.value.toString()
      is ee.schimke.composeai.daemon.protocol.RemoteNamedValue.ColorValue -> v.argb
    }

  /**
   * A small built-in device menu for the viewer dropdown. Pairs are `device-token` → display name;
   * the tokens are the `@Preview(device=…)` grammar the daemon resolves. TODO: source the full list
   * from the daemon's `DeviceDimensions` catalog so the menu always matches what the backend knows.
   */
  /**
   * Where the snapshot note sends a viewer who wants the disabled overrides to work: the doc that
   * explains running your own `compose-preview serve` (the live, daemon-backed tier that re-renders
   * device/orientation/locale/font-scale for real). A published catalog like `preview.coo.ee` only
   * replays baked PNGs, so those knobs need a local live server. Points at the source doc on `main`
   * (matching the landing page's `source` link) since the published docs site has no serve page.
   */
  private const val LOCAL_SERVER_DOCS =
    "https://github.com/yschimke/compose-ai-tools/blob/main/docs/public-preview-server.md#running-one"

  /**
   * Render density the `serve` backend captures at (the manifest default — `PreviewManifestEntry`
   * resolves `density ?: 2.0f`). The size-override inputs are authored in **dp** (the Compose
   * unit); the viewer converts dp→px against this factor before sending the px-valued `widthPx` /
   * `min…Px` / `max…Px` query params, so the wire and copyable `/render` URLs stay in pixels like
   * every other override. Carried to the page as `data-render-density` so the conversion isn't a
   * hidden magic number.
   */
  private const val RENDER_DENSITY = 2

  private data class ScreenDevice(
    val id: String,
    val name: String,
    val kind: String,
    val sizeDp: String,
  )

  /** Phone-family device profiles offered for an ordinary (handheld) catalog's screens. */
  private val SCREEN_DEVICES: List<ScreenDevice> =
    listOf(
      ScreenDevice("id:pixel_5", "Pixel 5", "compact phone", "393 × 851 dp"),
      ScreenDevice("id:pixel_7", "Pixel 7", "standard phone", "411 × 914 dp"),
      ScreenDevice("id:pixel_fold", "Pixel Fold", "foldable", "841 × 701 dp"),
      ScreenDevice("id:pixel_tablet", "Pixel Tablet", "tablet", "1280 × 800 dp"),
    )

  /**
   * Watch profiles offered instead for a Wear system's screens. Same ids and dimensions the
   * renderer already resolves for `@Preview(device = …)`
   * ([ee.schimke.composeai.daemon.devices.DeviceDimensions]), so a chosen override renders at the
   * shape the author would have got from the annotation. Round shapes lead because Wear OS is
   * overwhelmingly round; square/rectangular stay available for the shapes that still ship.
   */
  private val WEAR_SCREEN_DEVICES: List<ScreenDevice> =
    listOf(
      ScreenDevice("id:wearos_small_round", "Small round", "Wear OS watch", "192 × 192 dp"),
      ScreenDevice("id:wearos_large_round", "Large round", "Wear OS watch", "227 × 227 dp"),
      ScreenDevice("id:wearos_xl_round", "Extra large round", "Wear OS watch", "240 × 240 dp"),
      ScreenDevice("id:wearos_square", "Square", "Wear OS watch", "180 × 180 dp"),
      ScreenDevice("id:wearos_rect", "Rectangular", "Wear OS watch", "201 × 238 dp"),
    )

  /**
   * The device profiles a screen's "Device size" picker offers — watch shapes for a Wear system,
   * phones/foldable/tablet otherwise.
   */
  private fun screenDevicesFor(isWearSystem: Boolean): List<ScreenDevice> =
    if (isWearSystem) WEAR_SCREEN_DEVICES else SCREEN_DEVICES
}
