package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.data.overrides.PreviewOverrideOption
import ee.schimke.composeai.designpages.DesignPage
import ee.schimke.composeai.designpages.PageNode
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
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

  /** Sign-in affordance for a GitHub-protected live stream lane. */
  data class LiveAuthPrompt(val loginHref: String, val repository: String)

  /** Front-door GitHub auth state, shown when the public server protects code-running surfaces. */
  data class GitHubAuthStatus(
    val loginHref: String,
    val login: String? = null,
    val restrictedToAllowedUsers: Boolean = false,
  )

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

  private fun scriptTag(name: String): String = "<script src=\"${assetHref(name)}\"></script>"

  private fun viewCountHtml(views: Long): String =
    if (views <= 0) "" else "<div class=\"cp-engage\">${formatViews(views)}</div>"

  /**
   * The viewer's view tally. A `<span>`, not a block: it sits on the title row beside the id, where
   * it reads as one more fact about this preview rather than a paragraph of its own.
   */
  private fun viewerViewCountHtml(views: Long): String =
    if (views <= 0) "" else "<span class=\"cp-viewer-engage\">${formatViews(views)}</span>"

  /**
   * A title-bar disclosure toggle: a [label] naming the axis it folds, plus the [value] that axis
   * currently holds. Both halves matter. A bare "State" would make the reader open the row to learn
   * what they are looking at — the very cost the fold was meant to remove — so a closed toggle
   * reads "State · M wide" and the row underneath is genuinely optional.
   *
   * Shares `.cp-drawer-toggle` with the two drawer toggles it now sits beside, so the four
   * disclosures on this page cannot drift apart visually; `aria-expanded` + `aria-controls` are
   * what make it a disclosure rather than four buttons that happen to look alike.
   *
   * [valueId] labels the value span when something client-side has to keep it current (the theme,
   * which changes without a page load).
   */
  private fun disclosureToggleHtml(
    id: String,
    controls: String,
    label: String,
    value: String,
    open: Boolean,
    valueId: String? = null,
  ): String {
    val valueIdAttr = valueId?.let { " id=\"${WebEscaping.htmlEscape(it)}\"" } ?: ""
    return "<button type=\"button\" class=\"cp-drawer-toggle cp-axis-toggle\"" +
      " id=\"${WebEscaping.htmlEscape(id)}\" aria-expanded=\"$open\"" +
      " aria-controls=\"${WebEscaping.htmlEscape(controls)}\">" +
      "<span class=\"cp-toggle-label\">${WebEscaping.htmlEscape(label)}</span>" +
      "<span class=\"cp-toggle-value\"$valueIdAttr>${WebEscaping.htmlEscape(value)}</span>" +
      "</button>"
  }

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
   * Producer-trust badge for a bundle/catalog session ([BundleVerifier.summary]); empty for a live
   * daemon-backed module (trust applies to detached bundles/catalogs, not the operator's own served
   * module). A non-`unverified` verdict reads as trusted (green ✓); `unverified` is amber (⚠).
   */
  private fun trustBadge(trust: String?): String {
    if (trust.isNullOrBlank()) return ""
    val unverified = trust == "unverified"
    val cls = if (unverified) "cp-badge cp-badge--unverified" else "cp-badge cp-badge--trusted"
    val icon = if (unverified) "⚠" else "✓"
    val label = WebEscaping.htmlEscape(trust)
    return " <span class=\"$cls\" title=\"producer trust: $label\">$icon $label</span>"
  }

  /**
   * A compact trust badge for a home-index card: the icon + a one-word verdict (`trusted` /
   * `unverified`) rather than the full basis string, which is too long for a narrow card title. The
   * full basis is kept in the `title` tooltip and shown in full on the system's own landing.
   */
  private fun compactTrustBadge(trust: String?): String {
    if (trust.isNullOrBlank()) return ""
    val unverified = trust == "unverified"
    val cls = if (unverified) "cp-badge cp-badge--unverified" else "cp-badge cp-badge--trusted"
    val icon = if (unverified) "⚠" else "✓"
    val word = if (unverified) "unverified" else "trusted"
    val full = WebEscaping.htmlEscape(trust)
    return " <span class=\"$cls\" title=\"producer trust: $full\">$icon $word</span>"
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
  internal fun revisionsHtml(revisions: CatalogRevisions, hrefFor: (String?) -> String): String {
    if (revisions.isEmpty) return ""
    val pinned = revisions.pinned
    val current = revisions.revisions.firstOrNull()?.commit
    val banner =
      if (pinned == null) ""
      else {
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
        """
        <section class="cp-pinned" role="note" aria-label="Pinned revision">
          <span class="cp-pinned-icon" aria-hidden="true">⚓</span>
          <span>Pinned to catalog revision $shaLink$published — these pixels cannot change.</span>
          <a class="cp-pinned-current" href="${WebEscaping.htmlEscape(hrefFor(null))}">view current</a>
        </section>
        """
          .trimIndent()
      }
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
        "<a class=\"cp-revision\" rel=\"nofollow\" href=\"${WebEscaping.htmlEscape(href)}\"$mark>" +
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
    return banner +
      """
      <details class="cp-revisions">
        <summary class="cp-revisions-btn">
          <span class="cp-revisions-key">Revision</span>
          <span class="cp-revision-date">${WebEscaping.htmlEscape(shownDate)}</span>
          ${shownSha?.let { "<code class=\"cp-revision-sha\">${WebEscaping.htmlEscape(it)}</code>" }.orEmpty()}
          $shownTag
          <span class="cp-revisions-caret" aria-hidden="true">▾</span>
        </summary>
        <div class="cp-revisions-menu">
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
   * How many rows the viewer's component subtree shows inline before folding behind its title-bar
   * toggle — counting the component row, which is itself a render (the default one), not just its
   * children. Lower than the chip rows this replaced needed, because a tree spends a whole line per
   * render where a chip row wrapped several onto one: four rows is about the point past which the
   * list costs more of the fold than the render it sits above.
   */
  private const val AXIS_ROWS_INLINE = 4

  /**
   * How many theme chips the viewer bar shows inline before folding. Lower than [AXIS_CHIPS_INLINE]
   * because the bar is capped at a single non-wrapping row: past a handful the chips ellipsise into
   * stubs and the group scrolls within itself, which is worse than a toggle that spells the current
   * theme out in full.
   */
  private const val THEME_CHIPS_INLINE = 4

  // android.content.res.Configuration values, kept local so the CLI has no Android dependency.
  private const val UI_MODE_NIGHT_MASK = 0x30
  private const val UI_MODE_NIGHT_NO = 0x10
  private const val UI_MODE_NIGHT_YES = 0x20

  /** Inline GitHub mark (Octicons, MIT). Rendered beside source and authentication links. */
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
   * Public-mode "about" intro: a short, static explanation of what the host is and its safety
   * model. It sits at the **bottom** of the page it appears on (below the catalog grid / the home
   * index), directly above [siteFooter] — the page's own content leads, and the explanation is
   * there for whoever scrolls to it. Build and source links are NOT repeated here: [siteFooter]
   * carries them on every page.
   */
  private fun aboutSection(): String =
    """
    <details class="cp-about cp-disclosure">
      <summary>
        <span class="cp-about-title">About this preview server</span>
        <span class="cp-disclosure-hint">How previews run and catalogs are trusted</span>
      </summary>
      <div class="cp-disclosure-body">
        <p class="cp-about-body">Compose Multiplatform components can run <strong>in your browser</strong>
          using sandboxed Kotlin/Wasm; other previews are published as pre-rendered snapshots. The
          server never re-runs untrusted code. Catalogs are trusted by signature or their published
          <code>design-artifacts</code> branch, and anything unverified is badged.</p>
      </div>
    </details>
    """
      .trimIndent()

  /** GitHub session action shown in the home-page header when OAuth is configured. */
  private fun githubAuthControl(status: GitHubAuthStatus?): String {
    status ?: return ""
    val restricted =
      if (status.restrictedToAllowedUsers)
        " title=\"Live preview access is limited to configured GitHub users\""
      else " title=\"Live previews require a GitHub sign-in\""
    val login = status.login?.takeIf { it.isNotBlank() }
    return if (login == null) {
      "<a class=\"cp-gh-auth\" href=\"${WebEscaping.htmlEscape(status.loginHref)}\"" +
        "$restricted>$GITHUB_ICON Sign in with GitHub</a>"
    } else {
      "<span class=\"cp-gh-auth cp-gh-auth--signed\"$restricted>$GITHUB_ICON " +
        "Signed in as ${WebEscaping.htmlEscape(login)}</span>"
    }
  }

  /**
   * The minimal site footer — source, `/version`, and the running build — rendered by [document] at
   * the bottom of **every** browser-facing page, below the body (and so below the bottom-of-page
   * [aboutSection] on the pages that carry one). [version] null/blank just drops the build span;
   * the source and `/version` links stay, so the footer is never empty.
   */
  private fun siteFooter(version: String?): String {
    val ver =
      version
        ?.takeIf { it.isNotBlank() }
        ?.let {
          " · <span class=\"cp-about-ver\" title=\"running preview-server build\">" +
            "server v${WebEscaping.htmlEscape(it)}</span>"
        } ?: ""
    return """
      <footer class="cp-site-footer">
        <a href="https://github.com/$SOURCE_REPO">$GITHUB_ICON source</a> ·
        <a href="/version">/version</a>$ver
      </footer>
      """
      .trimIndent()
  }

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
  ): String {
    val actionHtml = action.takeIf { it.isNotBlank() }?.let { "\n          $it" } ?: ""
    val crumb = breadcrumb.takeIf { it.isNotBlank() }?.let { "\n          $it" } ?: ""
    val name =
      siteName
        .takeIf { it.isNotBlank() }
        ?.let { "\n          <span class=\"cp-site-catalog\">${WebEscaping.htmlEscape(it)}</span>" }
        ?: ""
    return """
      <header class="cp-site-header">
        <div class="cp-site-lead">
          <a class="cp-site-brand" href="/$navSuffix" aria-label="compose-preview home">
            <span class="cp-site-mark" aria-hidden="true">◇</span>
            <span>compose-preview</span>
          </a>$name$crumb
        </div>
        <div class="cp-site-status">
          <span class="cp-daemon-status" id="cp-daemon-status" role="status" hidden></span>
        </div>
        <nav class="cp-site-nav" aria-label="Primary navigation">
          <a href="/$navSuffix">Catalogs</a>
          <a href="/status$navSuffix">Status</a>
          <a href="https://github.com/$SOURCE_REPO">GitHub</a>$actionHtml
          ${settingsMenuHtml().prependIndent("          ").trimStart()}
        </nav>
      </header>
      """
      .trimIndent()
  }

  /**
   * The header's **Settings** menu: standing per-visitor preferences, as opposed to the controls
   * that describe what is on screen (the Theme chips, Transparent, the override drawers). One
   * setting lives here today — **Page theme**, whether the chrome follows the selected preview
   * theme or the visitor's operating system (see `page-theme.js`) — and it is a setting rather than
   * another toolbar control precisely because it is answered once and then applies to every catalog
   * and every page.
   *
   * A plain `<details>`, so it opens and the radios record a choice with **no JavaScript at all**;
   * `page-theme.js` only reflects the stored value into them and repaints when one changes. It sits
   * in the nav so it is in the same place on every page, and last so it never displaces the links.
   */
  private fun settingsMenuHtml(): String =
    """
    <details class="cp-settings">
      <summary class="cp-settings-btn" title="Settings" aria-label="Settings">
        <span aria-hidden="true">⚙</span><span class="cp-settings-btn-label">Settings</span>
      </summary>
      <div class="cp-settings-panel">
        <fieldset class="cp-settings-group">
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
            match. Turn it off to keep the page on your operating system's setting.</p>
        </fieldset>
      </div>
    </details>
    """
      .trimIndent()

  /**
   * A "back to all design systems" button for a catalog landing — replaces the in-catalog
   * design-systems nav row, so a catalog page links **home** (the front-door index at `/`) rather
   * than sideways to its siblings. Token-free in [isPublic] mode; a token-gated box keeps the
   * token.
   */
  private fun backButton(token: String, isPublic: Boolean): String {
    val suffix = querySuffix(if (isPublic) "" else "token=" + WebEscaping.urlEncodeSegment(token))
    return """<a class="cp-back" href="/$suffix">← All design systems</a>"""
  }

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
   * form rather than a link). [action] is the issue form's URL, [title] and [body] are its hidden
   * inputs — filled for the settings the page was served at, so it works with JS off — and
   * [bodyTemplate] is the same body with the render link left as
   * [ServeIssueReport.RENDER_PLACEHOLDER], which the viewer JS re-substitutes as the overrides
   * change. [repo] names the target in the tooltip so nobody files against a repo they didn't mean
   * to, and [login] — present only when the visitor has a GitHub session on this server — says
   * whose account will author it.
   */
  data class ReportIssue(
    val action: String,
    val title: String,
    val body: String,
    val bodyTemplate: String,
    val repo: String,
    val login: String? = null,
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
  data class PageLink(val id: String, val name: String)

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
   * Renders [report] as the GET form that sits beside the per-preview "source" link — styled as a
   * link, since that is what it behaves like. Null (a surface with no repo to file against) renders
   * nothing.
   */
  private fun reportIssueHtml(report: ReportIssue?): String {
    val r = report ?: return ""
    val who =
      r.login?.takeIf { it.isNotBlank() }?.let { " as @${WebEscaping.htmlEscape(it)}" } ?: ""
    val tip = "File an issue on ${WebEscaping.htmlEscape(r.repo)}$who"
    return "\n      <form class=\"cp-report\" id=\"cp-report\" method=\"get\" target=\"_blank\"" +
      " rel=\"noopener\" action=\"${WebEscaping.htmlEscape(r.action)}\">" +
      "<input type=\"hidden\" name=\"title\" value=\"${WebEscaping.htmlEscape(r.title)}\">" +
      "<input type=\"hidden\" name=\"body\" id=\"cp-report-body\"" +
      " value=\"${WebEscaping.htmlEscape(r.body)}\"" +
      " data-report-template=\"${WebEscaping.htmlEscape(r.bodyTemplate)}\">" +
      "<button type=\"submit\" class=\"cp-report-link\" title=\"$tip\">" +
      "$GITHUB_ICON report an issue</button></form>"
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
    val label =
      buildList {
          if (open > 0) add("$open open")
          if (closed > 0) add("$closed closed")
        }
        .joinToString(" · ")
    val title = issues.joinToString("; ") { "#${it.number} ${it.title}" }
    return "<span class=\"cp-issue-badge\" title=\"${WebEscaping.htmlEscape(title)}\">${WebEscaping.htmlEscape(label)} issue${if (issues.size == 1) "" else "s"}</span>"
  }

  private fun issuesForPreview(
    issues: List<ParityIssue>,
    preview: ServePreview,
  ): List<ParityIssue> = issues.filter { issue ->
    preview.id in issue.previewIds ||
      (preview.componentId != null && issue.component == preview.componentId)
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
      <details class="cp-prov cp-disclosure">
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

  /** The preview's baked theme, preferring its actual discovery-time uiMode over id heuristics. */
  private fun previewTheme(preview: ServePreview, darkFirst: Boolean): String? =
    when (preview.uiMode and UI_MODE_NIGHT_MASK) {
      UI_MODE_NIGHT_YES -> "dark"
      UI_MODE_NIGHT_NO -> "light"
      else -> bgTheme(preview.id, darkFirst)
    }

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
   * The comparison-table card family for [p]: fold state, props, and the baked light/dark pair,
   * while preserving independent axes such as size. This mirrors the default-card grouping used by
   * [groupPreviews] without broadening aliases to every render of the same [componentKey].
   */
  private fun comparisonCardKey(p: ServePreview): String =
    baseKey(stateInvariantKey(propsFamilyKey(p), p.state))

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
    val usedSlugs = HashSet<String>()
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
   */
  private fun catalogTreeHtml(
    sections: List<LandingSection>,
    components: (GridCard) -> TreeComponent,
    /** The design-pages branch ([pagesBranchHtml]), appended after the sections. Empty ⇒ none. */
    pagesBranch: String = "",
  ): String = buildString {
    append("<nav class=\"cp-tree\" id=\"cp-tabs\" aria-label=\"Catalog sections\">\n")
    append("<ul class=\"cp-tree-list\" role=\"tree\" aria-label=\"Catalog sections\">\n")
    sections.forEachIndexed { i, sec ->
      val selected = if (i == 0) "true" else "false"
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
      if (named.isNotEmpty()) append(" aria-expanded=\"$selected\" aria-owns=\"$childrenId\"")
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
   * The anchor on a synthesized flat sub-group divider — no section owns it, so it stands alone.
   */
  private fun flatGroupAnchorId(groupSlug: String) = "cp-group-$groupSlug"

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
    return all.firstOrNull {
      componentKey(it) == key &&
        themeLane(it, darkFirst) == lane &&
        !isNonDefaultState(it) &&
        !hasNonDefaultProps(it)
    } ?: current
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

  /**
   * Whether this component varies on [axis] (`"state"` or `"props"`), which is what lets the folded
   * disclosure name the axes it put away. Derived from [componentRenderRows] rather than
   * re-deriving the axis rules, so the toggle can never claim an axis the subtree underneath it
   * does not offer.
   */
  private fun componentHasAxis(
    preview: ServePreview,
    siblings: List<ServePreview>,
    darkFirst: Boolean,
    axis: String,
  ): Boolean = componentRenderRows(preview, siblings, darkFirst) { it.id }.any { it.axis == axis }

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
    if (!crossProduct) if (axis == "state") stateLabel(p.state) else propsLabel(p.props)
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
    val chips = themeChipsHtml(builtIns, declared)
    return """
    <div class="cp-toolbar">
      <span class="cp-theme" role="group" aria-label="Preview theme">
        $chips
      </span>
    </div>
    """
      .trimIndent()
  }

  /**
   * The theme chips themselves, shared by the landing picker ([themePickerHtml]) and the viewer bar
   * ([viewerThemePickerHtml]) so one control appears on both pages instead of two that drift.
   *
   * [builtIns] are the `(choice value, label)` pairs the page offers before any app-declared theme
   * — the landing's baked `light`/`dark` swap (or its lone `default`), the viewer's Day/Night
   * uiMode pair (or Night alone on a dark-first system). [declared] follows as one
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
  private fun searchBoxHtml(): String =
    """
    <div class="cp-searchbar">
      <input id="cp-search" class="cp-search" type="search" placeholder="Filter previews…"
        autocomplete="off" spellcheck="false" aria-label="Filter previews" aria-controls="cp-grid">
    </div>
    """
      .trimIndent()

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
  ): String {
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
        var themeLease = null;
        var themeRenderRetries = 3;
        function releaseThemeLease(lease, beacon) {
          if (!lease || !themeLeaseUrl) return;
          if (themeLease === lease) themeLease = null;
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
              themeLease = lease;
              callback(lease, concurrency);
            })
            .catch(function () { if (gen === themeGen) callback(null, 1); });
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
        // meets finished pixels rather than a spinner. No lease is taken: this is a trickle behind
        // the visitor's scroll, not the burst the visible batch asks for.
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
        function deferTheme(jobs, gen) {
          if (!jobs.length) return;
          // No IntersectionObserver (old browser): fall back to rendering them, serially, rather
          // than leaving those cards stuck on the wrong theme forever.
          if (!window.IntersectionObserver) { runThemeQueue(jobs, gen, null, 1); return; }
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
            if (due.length) runThemeQueue(due, gen, null, 1);
          }, { rootMargin: "400px" });
          themeObserver = observer;
          jobs.forEach(function (job) { observer.observe(job.card); });
        }
        window.addEventListener("pagehide", function () { releaseThemeLease(themeLease, true); });
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
    val correctInitialThemeVisibility =
      if (hasTabs)
        "\n            var themeSection = c.closest(\".cp-section\");" +
          "\n            if (themeVisible && themeSection && (!input || input.value.trim() === \"\")) {" +
          "\n              themeVisible = themeSection.getAttribute(\"data-section\") === current;" +
          "\n            }"
      else ""
    val applyDeclaredTheme =
      if (hasDeclaredThemes)
        """
        var provider = theme.indexOf("theme:") === 0 ? theme.slice(6) : "";
        releaseThemeLease(themeLease, false);
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
            if (lease) {
              // Deferred jobs are stamped with the SAME page lease. They run one at a time, so they
              // need none of its burst — but an unleased render queues on the server's single
              // unleased-render semaphore, shared with every other page, where a scrolling visitor
              // would be starved behind unrelated traffic. The grant is what says these renders
              // belong to a page that was admitted.
              themeQueue.concat(themeDeferredQueue).forEach(function (job) {
                job.baseSrc += "&_themeLease=" + encodeURIComponent(lease);
                job.src = job.baseSrc;
              });
            }
            runThemeQueue(themeQueue, themeQueueGen, lease, concurrency);
            // Passed WITHOUT the lease as the batch's own: the visible batch releases the grant
            // when it drains, and a deferred batch must never release it a second time (nor hold
            // it open across an idle scroll). The stamped URL above is what carries the token.
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
        function applyVariant(c, k, withSrc) {
          var src = c.getAttribute("data-" + k + "-src");
          if (!src) return;
          var img = c.querySelector("img");
          var lab = c.querySelector(".cp-label");
          var idn = c.querySelector(".cp-id");
          var lbl = c.getAttribute("data-" + k + "-label");
          if (img) { if (withSrc) setCardSrc(img, src); img.setAttribute("alt", lbl); }
          c.setAttribute("href", c.getAttribute("data-" + k + "-href"));
          if (lab) { lab.textContent = lbl; lab.setAttribute("title", lbl); }
          if (idn) idn.textContent = c.getAttribute("data-" + k + "-id");
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
          // follows an explicit Light/Dark pick (page-theme.js decides; a declared theme leaves it
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
    val treeDecls =
      if (!hasTree) ""
      else
        "\n      var treeGroups = document.querySelectorAll(\".cp-tree-group\");" +
          "\n      var treeComponents = document.querySelectorAll(\".cp-tree-component\");" +
          "\n      var treeLinks = document.querySelectorAll(\".cp-tree-link\");" +
          // The tree's own roving-tab-stop pass, published so `apply()` can call it from outside
          // the tree script's closure once the filter has changed which rows are on screen.
          "\n      var cpTreeStops = null;"
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
          "\n        var stop = null;" +
          "\n        var firstShown = null;" +
          "\n        tabBtns.forEach(function (t) {" +
          "\n          var on = t.getAttribute(\"data-tab\") === current;" +
          "\n          t.setAttribute(\"aria-selected\", on ? \"true\" : \"false\");" +
          "\n          if (t.hasAttribute(\"aria-expanded\"))" +
          "\n            t.setAttribute(\"aria-expanded\", on || searching ? \"true\" : \"false\");" +
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
    val tabOkLine =
      if (hasTabs)
        "\n          var sec = c.closest(\".cp-section\");" +
          "\n          var tabOk = q !== \"\" || !sec || sec.getAttribute(\"data-section\") === current;"
      else ""
    val hiddenExpr = if (hasTabs) "!(searchOk && tabOk)" else "!searchOk"
    val shownCond = if (hasTabs) "searchOk && tabOk" else "searchOk"
    // After the per-card pass, collapse any sub-group / section left with no visible card.
    val groupPost =
      if (hasGroups)
        "\n        navGroups.forEach(function (g) { g.hidden = !g.querySelector(\".cp-card:not([hidden])\"); });"
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
        catalogTreeScript(tabStorageKey, hasTabs).lines().joinToString("") {
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
      // Address-bar state (url-state.js). Every selection below is reflected into the URL so the
      // page someone is looking at is the page its URL describes — bookmarkable, shareable, and
      // reachable with Back — without ever reloading: the grid re-points its own images.
      var urlState = window.cpUrlState || null;
      function urlParam(n) { return urlState ? urlState.get(n) : ""; }
      function pushUrl(v) { if (urlState) urlState.push(v); }
      function replaceUrl(v) { if (urlState) urlState.replace(v); }
      if (input) { var urlQuery = urlParam("q"); if (urlQuery) input.value = urlQuery; }$groupDecls$treeDecls$tabDecls
      ${listOf(themeInit, themeRenderInit).filter { it.isNotEmpty() }.joinToString("\n")}
      function apply() {
        $applyTheme
        var q = input ? input.value.trim().toLowerCase() : "";
        var shown = 0;
        cards.forEach(function (c) {
          var lab = c.querySelector(".cp-label");
          var idn = c.querySelector(".cp-id");
          var hay = ((lab ? lab.textContent : "") + " " + (idn ? idn.textContent : "")).toLowerCase();
          var searchOk = q === "" || hay.indexOf(q) !== -1;$tabOkLine
          c.hidden = $hiddenExpr;
          if ($shownCond) shown++;
        });
        if (count) count.textContent = q === "" ? (total + " preview" + (total === 1 ? "" : "s")) : (shown + " of " + total);
        if (empty) empty.hidden = shown !== 0;$groupPost$sectionPost$treePost
      }
      if (input) input.addEventListener("input", function () {
        // Typing REPLACES rather than pushes: a five-character filter must not bury the page the
        // visitor arrived from under five entries. The URL still carries the query, so the
        // filtered grid is bookmarkable.
        replaceUrl({ q: input.value.trim() });
        apply();
      });
      $themeWiring$tabWiring$popWiring$treeWiring
      apply();$presenceWiring
    })();
    """
      .trimIndent()
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
  private fun catalogTreeScript(tabStorageKey: String, hasTabs: Boolean): String {
    // Section switching only exists for a catalog that HAS sections. An outline tree (a
    // section-less catalog) hides nothing and remembers nothing — every row is purely a jump — so
    // the pieces that talk to `current` / `selectTab` are spliced out rather than guarded at
    // runtime, and its script never mentions a tab.
    val selectOwningTab =
      if (hasTabs) "\n        selectTab(row.getAttribute(\"data-tab\"));" else ""
    val tabRows = if (hasTabs) ".cp-tab, " else ""
    // The rows a `#cp-panel-<slug>` fragment could name, and the three operations that only mean
    // something when sections exist. An outline tree gets inert stand-ins rather than `if
    // (hasTabs)`
    // scattered through the body.
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
        function applyLandingTab(landing) {
          if (!landing.tab || landing.tab === current) return;
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
            if (!popped || popped.tab === current) return;
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
      // The badge has a reserved slot in the site header (see ServeWeb.siteHeader) — it is
      // server-rendered, hidden, and centred, so filling it never moves the brand or the nav. The
      // create-and-append fallback is only for a surface that predates the slot.
      var daemonUrl = presenceUrl.replace("/api/presence", "/api/daemons");
      var daemonBadge = null;
      function daemonBadgeEl() {
        if (daemonBadge) return daemonBadge;
        daemonBadge = document.getElementById("cp-daemon-status");
        if (!daemonBadge) {
          daemonBadge = document.createElement("span");
          daemonBadge.id = "cp-daemon-status";
          daemonBadge.className = "cp-daemon-status";
          daemonBadge.setAttribute("role", "status");
          var host = document.querySelector(".cp-site-status") || document.querySelector("header");
          (host || document.body).appendChild(daemonBadge);
        }
        return daemonBadge;
      }
      function paintDaemonStatus(state) {
        var el = daemonBadgeEl();
        if (!state) { el.hidden = true; return; }
        el.hidden = false;
        // "not running" is a normal resting state, not a fault — a catalog nobody has rendered on
        // simply has no process yet. Word it so it doesn't read as an error.
        var label = state.running
          ? "Render server: connected"
          : "Render server: not running";
        var count = state.instances || 0;
        if (state.running) {
          label += " \u00b7 " + count + (count === 1 ? " instance" : " instances");
          if (state.activeStreams > 0) label += ", " + state.activeStreams + " live";
        }
        el.textContent = label;
        el.setAttribute("data-cp-daemon-running", state.running ? "1" : "0");
        el.title = state.pooled
          ? state.pooled + " of " + state.poolCapacity + " pooled daemons resident"
          : "";
      }
      function pollDaemons() {
        if (!daemonUrl || document.visibilityState !== "visible") return;
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
      // Runs before viewer.js' initial render. A declared app theme is intentionally inherited even
      // by an explicit __light/__dark preview: that path token selects the baked fallback, while the
      // catalog's Theme choice is the active override the visitor asked to keep while navigating.
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
      if (urlOption) {
        el.value = urlChoice;
        el.setAttribute("data-theme-active", "1");
      }
      try {
        var stored = localStorage.getItem("$themeStorageKey");
        var declared = stored && stored.indexOf("theme:") === 0;
        var option = null;
        Array.prototype.forEach.call(el.options, function (o) { if (o.value === stored) option = o; });
        if (!urlOption && option && !option.disabled && (declared || (!themed && (stored === "light" || stored === "dark")))) {
          el.value = stored;
          el.setAttribute("data-theme-active", "1");
        }
      } catch (e) {}
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
  )

  /**
   * A front-page section a catalog may be published under: the [heading] shown, its count [noun],
   * and the [repos] whose bytes are allowed to appear under it.
   */
  data class HomeGroup(
    val heading: String,
    val noun: String = ServeCatalogsConfig.DEFAULT_NOUN,
    val repos: Set<String> = emptySet(),
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
    return "<span class=\"cp-crop\" style=\"width:${crop.boxW}px;aspect-ratio:${crop.boxW}/${crop.boxH}\">$cropped</span>"
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
     * Running server version (the CLI's `BUNDLE_VERSION`), surfaced in the minimal footer beside
     * the source/`/version` links so the live build is visible on the front door. Null omits it;
     * the fixture golden passes a fixed string so a release never churns the committed HTML.
     */
    version: String? = null,
    /** Absolute page + representative hero URLs for Open Graph/Twitter link previews. */
    unfurl: UnfurlMetadata? = null,
    githubAuth: GitHubAuthStatus? = null,
  ): String {
    // The "about" intro sits at the BOTTOM of the front door (below the catalog cards, above the
    // footer) so the systems this server publishes lead the page; it still appears only for the
    // public server.
    val about = if (isPublic) "\n" + aboutSection() else ""
    val headerAction = githubAuthControl(githubAuth)
    // Public routes are open — no token param on the cards; a token-gated box keeps it.
    val suffix = querySuffix(if (isPublic) "" else "token=" + WebEscaping.urlEncodeSegment(token))
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
      // A dark-first (Wear) system backs its hero on the dark stage — same `data-bg-theme` hook the
      // catalog grid and viewer use — so a light-on-transparent Wear sticker isn't washed out on
      // white.
      val bg = if (s.darkStage) " data-bg-theme=\"dark\"" else ""
      return """
      <a class="cp-card cp-sys"$bg href="/$sysSeg/$suffix">
        <div class="cp-imgwrap">$img</div>
        <div class="cp-meta">
          <div class="cp-sys-title">$title${homeTrustBadge(s.trust)}</div>
          <div class="cp-id">$sysId</div>$desc
          <div class="cp-sys-foot">${s.previewCount} preview(s)${if (s.views > 0) " · ${formatViews(s.views)}" else ""}</div>
        </div>
      </a>
      """
        .trimIndent()
    }
    // Headings and nouns come from operator config (and, for the fallback sections, from a
    // catalog's own provenance), so they're escaped like any other data on the page.
    fun section(heading: String, list: List<HomeSystem>, noun: String, gridId: String): String {
      val head = WebEscaping.htmlEscape(heading)
      val count = "${list.size} ${WebEscaping.htmlEscape(noun)}"
      return """
      <div class="cp-section-title">
        <h1 class="cp-head">$head</h1>
        <span class="cp-section-count">$count</span>
      </div>
      <div class="cp-grid cp-syslist" id="$gridId">
      ${list.joinToString("\n") { card(it) }}
      </div>
      """
        .trimIndent()
    }
    val sections = homeSections(systems)
    val body =
      if (systems.isEmpty()) {
        "<h1 class=\"cp-head\">Design Systems</h1>\n" +
          "<p class=\"cp-sub\">No design systems are configured on this server.</p>"
      } else {
        sections
          .mapIndexed { index, s ->
            section(s.heading, s.systems, s.noun, if (index == 0) "cp-grid" else "cp-grid-$index")
          }
          .joinToString("\n")
      }
    return document(
      title = "$HOME_TITLE — compose-preview",
      unfurlTitle = HOME_TITLE,
      unfurlDescription = homeUnfurlDescription(systems.size),
      unfurl = unfurl,
      navSuffix = suffix,
      headerAction = headerAction,
      version = version,
      body = body + about,
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
   * never promoted. Sections come out in first-appearance (i.e. configured) order, so the operator
   * controls the front page's running order, with "Other" pinned last.
   */
  internal fun homeSections(systems: List<HomeSystem>): List<HomeSection> {
    val grouped = LinkedHashMap<String, MutableList<HomeSystem>>()
    val nouns = LinkedHashMap<String, String>()
    for (s in systems) {
      // The claim only holds when the bytes came from a repo the operator named for this entry.
      val claimed = s.group?.takeIf { g -> s.sourceRepo != null && s.sourceRepo in g.repos }
      val heading = claimed?.heading ?: ownerHeading(s.sourceRepo)
      grouped.getOrPut(heading) { mutableListOf() } += s
      nouns.putIfAbsent(heading, claimed?.noun ?: ServeCatalogsConfig.DEFAULT_NOUN)
    }
    val sections = grouped.map { (heading, list) ->
      HomeSection(heading, list, nouns.getValue(heading))
    }
    // "Other" is the unattributed bucket, so it reads last regardless of when it first appeared.
    return sections.filterNot { it.heading == OTHER_HEADING } +
      sections.filter { it.heading == OTHER_HEADING }
  }

  /** The heading an ungrouped catalog falls back to: its repo owner's, else the "Other" bucket. */
  private fun ownerHeading(sourceRepo: String?): String {
    val owner = sourceRepo?.substringBefore('/')?.takeIf { it.isNotBlank() && it != sourceRepo }
    return if (owner == null) OTHER_HEADING else "$owner org"
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
     * Running server version (`BUNDLE_VERSION`), shown in the minimal footer. Null omits the build
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
     * Running server version (`BUNDLE_VERSION`), shown in the minimal footer. Null omits the build
     * span.
     */
    version: String? = null,
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
    val wantedIndex = handoffCatalog?.let { id ->
      catalogs.indexOfFirst { it.id == id }.takeIf { it >= 0 }
        ?: catalogs
          .indexOfFirst { it.id.isEmpty() }
          .takeIf { it >= 0 && id in pinnedCatalogSystems }
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
            <button id="pg-run" class="cp-doc-btn cp-pg-run" type="button">Run</button>
          </div>
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
          <div id="pg-status" class="cp-pg-status" hidden></div>
          <ul id="pg-diagnostics" class="cp-pg-diags" hidden></ul>
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
      });
      renderFiles();
      function setStatus(text, isError) {
        statusEl.hidden = false;
        statusEl.className = "cp-pg-status" + (isError ? " cp-doc-error" : "");
        statusEl.textContent = text;
      }
      function clearOut() {
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
      }
      function renderDiags(list) {
        if (!list || !list.length) return;
        diags.hidden = false;
        list.forEach(function (d) {
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
            li.title = "Show " + d.file;
            li.addEventListener("click", function () { showFile(owner); });
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
          files: files
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
            renderDiags(res.diagnostics);
            var hasError = (res.diagnostics || []).some(function (d) { return d.severity === "error"; });
            if (res.exception) { setStatus(res.exception, true); return; }
            if (hasError) { setStatus("Compilation failed.", true); return; }
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
          })
          .catch(function (e) {
            if (myId !== reqId) return;
            run.disabled = false;
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
     * Running server version (`BUNDLE_VERSION`), shown in the minimal footer. Null omits the build
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
     * Running server version (`BUNDLE_VERSION`), shown in the minimal footer. Null omits the build
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
     * Running server version (`BUNDLE_VERSION`), shown in the minimal footer. Null omits the build
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
    val rcFontsScript = if (isRemoteComposeDoc) scriptTag("rc-fonts.js") + "\n        " else ""
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
          // (see `rc-fonts.js`). `cpRcFonts` is absent only if that script failed to load, in which
          // case the lane still renders — in the fallback face, as it did before.
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

  /** A labelled figure (a stat tile / config row) on the [statusPage]. */
  data class Stat(val key: String, val value: String)

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
    /** No catalog load or recent daemon startup failures (drives the header badge). */
    val overallOk: Boolean,
    val summary: List<Stat>,
    val config: List<Stat>,
    val catalogs: List<StatusCatalog>,
    val servers: List<StatusServer>,
    val failures: List<StatusFailure>,
    val renderFailures: List<StatusRenderFailure> = emptyList(),
  )

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
    /** Running server version (`BUNDLE_VERSION`), shown in the minimal footer. */
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
  ): String {
    fun esc(s: String) = WebEscaping.htmlEscape(s)
    // Gated-link suffix: token-gated ⇒ carry the token; public ⇒ nothing (routes are open).
    val suffix = if (view.public) "" else "?token=" + WebEscaping.urlEncodeSegment(token)
    fun stat(s: Stat) =
      "<div class=\"cp-stat\"><div class=\"cp-stat-key\">${esc(s.key)}</div>" +
        "<div class=\"cp-stat-val\">${esc(s.value)}</div></div>"

    val healthBadge =
      if (view.overallOk) " <span class=\"cp-badge cp-badge--trusted\">✓ healthy</span>"
      else " <span class=\"cp-badge cp-badge--unverified\">⚠ degraded</span>"

    val summaryGrid = view.summary.joinToString("\n") { stat(it) }
    val configGrid = view.config.joinToString("\n") { stat(it) }

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
              val detail =
                if (optimization.fullyOptimized) {
                  "themes optimized ${optimization.cached}/${optimization.total}"
                } else {
                  "theme optimization ${optimization.state} · " +
                    "${optimization.cached}/${optimization.total} cached" +
                    if (optimization.failed > 0) " · ${optimization.failed} failed" else ""
                }
              "<div class=\"cp-muted\">${esc(detail)}</div>"
            } ?: ""
          val renderCache =
            c.renderCache?.let { cache ->
              val detail =
                "preview cache ${cache.entries} entries · " +
                  "${humanBytes(cache.bytes)} / ${humanBytes(cache.maxBytes)}" +
                  if (cache.evictions > 0) " · ${cache.evictions} evicted" else ""
              "<div class=\"cp-muted\">${esc(detail)}</div>"
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
          "<tr>" +
            "<td>${esc(s.label)}<div class=\"cp-muted\">${esc(s.id)}</div></td>" +
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

      <p class="cp-status-sec">Catalogs</p>
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
      <div class="cp-status-grid">
      $configGrid
      </div>

      <p class="cp-status-sec">Recent daemon startup failures</p>
      $failureSection

      <p class="cp-status-sec">Recent render failures</p>
      $renderFailureSection
      """
        .trimIndent()

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
    )
  }

  /** Recent generation times read naturally; older builds use a compact, unambiguous UTC date. */
  private fun friendlyGeneratedAt(iso: String, nowMillis: Long): String {
    val generated = runCatching { Instant.parse(iso) }.getOrNull() ?: return prettyDate(iso)
    val ageSeconds = (nowMillis - generated.toEpochMilli()) / 1000
    if (ageSeconds >= 0 && ageSeconds < 86_400) {
      return when {
        ageSeconds < 60 -> "just now"
        ageSeconds < 3_600 -> {
          val minutes = ageSeconds / 60
          "$minutes ${if (minutes == 1L) "minute" else "minutes"} ago"
        }
        else -> {
          val hours = ageSeconds / 3_600
          "$hours ${if (hours == 1L) "hour" else "hours"} ago"
        }
      }
    }
    return STATUS_DATE_FORMAT.format(generated)
  }

  private val STATUS_DATE_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm 'UTC'", Locale.ENGLISH).withZone(ZoneOffset.UTC)

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
   * This emits the browser side's configuration and loads [ServeWebAssets] `catalog-live.js`. The
   * per-card preview ids ride in a **server-emitted object literal**, in the grid's document order,
   * rather than being read back off `data-` attributes — the same rule the themed-render URLs
   * follow, so no id this page turns into a socket URL originates as DOM text. Each entry carries
   * the card's light and dark ids (identical for a single-variant card, empty for one the session
   * can't stream), so a card swapped to its dark render goes live on what is actually on screen.
   *
   * Empty — no config, no script tag — when no card can stream, which is every static bundle and
   * every baked-only catalog. Those pages are byte-for-byte what they always were.
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
    return "\n<script>$config</script>\n${scriptTag("catalog-live.js")}"
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
     * Whether this catalog has a design-parity view to link to — it maps at least one preview to a
     * design reference, or it publishes a `parity/activity.json` feed. False (the default) omits
     * the link entirely rather than offering a page of zeroes, so a plain module / an unmapped
     * catalog's landing is unchanged.
     */
    hasParityView: Boolean = false,
    /**
     * The design pages this catalog publishes ([ServeDesignPages]), in publication order. Listed by
     * name in the navigation tree ([pagesBranchHtml]); a catalog with no tree to put them in falls
     * back to a header action chip. Empty (the default) offers neither, so a catalog that publishes
     * no pages is unchanged.
     */
    designPages: List<PageLink> = emptyList(),
    /**
     * The design tool this catalog is specified by ("Figma", …), from its references' provider or
     * its parity feed — names the parity action after the thing it compares against ("compare to
     * Figma") rather than after the internal feature. Null (no identifiable tool) keeps the generic
     * "design parity" label. See [designToolLabel].
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
     * Running server version (the CLI's `BUNDLE_VERSION`), surfaced in the minimal footer beside
     * the source/`/version` links. Null omits it; the fixture golden passes a fixed string so a
     * release never churns the committed HTML.
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
     * the same host question (`ServeHttpServer.droppedOverridesAreTerminal`) that decides whether a
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
  ): String {
    // The session id links may carry. Null on a rooted site (and for the default session): the
    // URL already says which catalog this is. `sessionId` itself stays intact below — it keys the
    // per-catalog localStorage entries and the dark-first lookup, which a site still needs.
    val linkSessionId = if (sessionInOrigin) null else sessionId
    val q = querySuffix(linkQuery(token, linkSessionId, basePath, isPublic))
    val themeLeaseUrl =
      if (themeRenderBurstCapacity > 1) "$basePath/api/theme-render-lease$q" else ""
    val navSuffix =
      querySuffix(if (isPublic) "" else "token=" + WebEscaping.urlEncodeSegment(token))
    val heading = catalogHeading(displayTitle, moduleLabel)
    val catalogId =
      if (heading == moduleLabel) ""
      else "<p class=\"cp-catalog-id\">${WebEscaping.htmlEscape(moduleLabel)}</p>"
    // A dark-first system (Wear) puts every unthemed card on the dark stage; explicit light/dark
    // variants keep their own token. Only affects the background — the Light/Dark filter axis below
    // still keys off the explicit-only [cardTheme].
    val darkFirst = isDarkFirstSystem(basePath, sessionId, declaredSurface)
    // Collapse per-theme variants into one card each so the Light/Dark control swaps a card between
    // its baked light/dark render *in place*, rather than filtering two cards. A single-theme /
    // theme-neutral card carries no swap data and the toggle leaves it alone.
    // Fold non-default component states (unchecked/pressed/disabled/…) AND props-axis variants
    // (locale/direction-rtl/fontScale/content) out of the grid first, so a component shows ONE card
    // (its default render) instead of a card per state or per variant; the folded renders stay
    // reachable through the viewer's state + variant switchers. Plain bundle screens (no state, no
    // props) pass straight through.
    val groups =
      groupPreviews(
        previews.filterNot {
          it.renderFailure == null && (isNonDefaultState(it) || hasNonDefaultProps(it))
        }
      )
    val cardAnchors = mintCardAnchors(groups)
    val renderFailureSummary =
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
    // Size/breakpoint variants intentionally remain separate cards, but catalog-authored labels
    // often omit that axis (for example three "Edgebutton" cards at Small/Large/XL Round). Add a
    // qualifier only when the base label actually collides, keeping ordinary one-card labels terse.
    val duplicateGridLabels =
      groups.groupingBy { previewDisplayName(it.default) }.eachCount().filterValues { it > 1 }.keys
    fun gridDisplayName(preview: ServePreview): String {
      val label = previewDisplayName(preview)
      if (label !in duplicateGridLabels) return label
      val size = previewSizeVariantLabel(preview.id) ?: return label
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
        <a class="cp-card"$anchor data-swap="1" data-bg-theme="$defTheme" data-def="${if (darkFirst) "d" else "l"}"
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
            <div class="cp-id">${WebEscaping.htmlEscape(def.id)}</div>$issueBadge
            ${viewCountHtml(cardViews(card))}
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
      // data-bg-theme is the thumbnail's background (explicit token, else the dark-first default).
      val bgAttr = bgTheme(p.id, darkFirst)?.let { " data-bg-theme=\"$it\"" } ?: ""
      return """
          <a class="cp-card"$anchor$bgAttr href="$basePath/p/$idSeg$q">
            <div class="cp-imgwrap">
              ${thumbImg(src, label, " loading=\"lazy\"", thumbCrop(p.id))}
            </div>
            <div class="cp-meta">
              <div class="cp-label" title="$idText">$label</div>
              <div class="cp-id">$idText</div>$issueBadge
              ${viewCountHtml(engagement[p.id]?.views ?: 0L)}
            </div>
          </a>
          """
        .trimIndent()
    }
    // Every card carries the anchor its tree row jumps to. Derived from the default render's id
    // rather than from position, so a row keeps pointing at the same component as the catalog
    // grows.
    fun cardHtml(card: GridCard): String {
      val anchor = " id=\"${cardAnchors.getValue(card.default.id)}\""
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
    val pagesBranch = if (hasTree) pagesBranchHtml(designPages, basePath, q) else ""
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
            append("<div class=\"cp-subgroup\" id=\"${flatGroupAnchorId(g.slug)}\">\n")
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
              append("<div class=\"cp-subgroup\"$anchor>\n")
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
    val sidebarSearch = if (tabBar.isEmpty() || previews.isEmpty()) "" else searchBoxHtml() + "\n"
    val navAndGrid =
      if (tabBar.isEmpty()) "$tabBar$gridBlock"
      else
        "<div class=\"cp-catalog-body\">\n" +
          "<aside class=\"cp-catalog-menu\" aria-label=\"Catalog menu\">\n" +
          "$sidebarSearch$tabBar</aside>\n$gridBlock\n</div>"
    // The "about" intro now sits at the BOTTOM of a catalog page (below the grid) so the catalog's
    // own content leads; it still appears only for the public server.
    val about = if (isPublic) "\n" + aboutSection() else ""
    // A catalog page links HOME (the front-door index) rather than sideways to its siblings: the
    // old design-systems nav row is replaced by a single back button, shown whenever this server
    // publishes catalogs (i.e. a home index exists to go back to). It rides in the site header's
    // brand slot with every other page's breadcrumb, rather than as the body's first line — a
    // catalog page's own heading and grid then start at the top of the content column.
    val back = if (hasHomeIndex) backButton(token, isPublic) else ""
    // The catalog-provenance strip (delivery branch, generation date, tool versions, regenerate
    // link) closes the catalog instead of interrupting the route from its heading to its content.
    val prov = provenance?.let { provenanceSection(it, refreshUrl) + "\n" } ?: ""
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
    val searchBox = if (hasPreviews && tabBar.isEmpty()) searchBoxHtml() + "\n" else ""
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
        "\n${scriptTag("url-state.js")}\n${scriptTag("serve-components.js")}\n<script>${catalogFilterScript(
          hasThemes,
          hasTabs,
          hasGroups,
          tabBar.isNotEmpty(),
          themeStorageKey(sessionId, basePath),
          tabStorageKey(sessionId, basePath),
          themeBaseJs,
          themeLeaseUrl,
          presenceUrl,
        )}</script>"
      else ""
    // The long-press live lane, in the SAME document order as the cards above (and as
    // [themeBaseJs]) — a card's entry is its light/dark pair of ids, or a pair of empty strings
    // when this session can't stream it.
    val liveScript =
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
          // Named after the design tool it compares against when the catalog identifies one —
          // "compare to Figma" says what the page is for, where "design parity" only named the
          // feature.
          hasParityView
            .takeIf { it }
            ?.let {
              actionChip(
                "$basePath/parity$q",
                designToolLabel?.let { tool -> "compare to $tool" } ?: "design parity",
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
      if (hasPreviews) bgPickerHtml("Show the transparent checkerboard behind each preview") else ""
    val catalogActions =
      listOf(actionChips, transparentAction).filter { it.isNotBlank() }.joinToString("\n          ")
    val primaryActions =
      catalogActions
        .takeIf { it.isNotBlank() }
        ?.let { "<div class=\"cp-catalog-actions\">\n          $it\n        </div>\n" } ?: ""
    val downloadAction =
      "\n<div class=\"cp-catalog-download\">" +
        actionChip("$basePath/bundle.zip$q", "download all (.zip)") +
        "</div>\n"
    val titleRow =
      "<div class=\"cp-catalog-title\">" +
        "<h1 class=\"cp-head cp-catalog-head\">${WebEscaping.htmlEscape(heading)}" +
        "${compactTrustBadge(trust)}</h1>$catalogId</div>"
    val tools =
      (themeToggle + searchBox)
        .takeIf { it.isNotBlank() }
        ?.let { "<div class=\"cp-catalog-tools\">\n$it</div>\n" } ?: ""
    return document(
      title = "$heading — compose-preview",
      unfurlTitle = heading,
      unfurlDescription = catalogUnfurlDescription(previews.size, heading),
      unfurl = unfurl,
      navSuffix = navSuffix,
      headerBreadcrumb = back,
      version = version,
      themeCss = themeCss,
      // The bar names the catalog you are in, from the same heading the page shows.
      siteName = heading,
      themeStorageKey = themeStorageKey(sessionId, basePath),
      declaredThemes = declaredThemeChips,
      body =
        """
        $titleRow
        ${degradeBanner(degradations)}<p class="cp-sub">${previews.size} preview(s)${if (systemViews > 0) " · ${formatViews(systemViews)}" else ""}$liveNote</p>
        $primaryActions$renderFailureSummary$tools$navAndGrid$emptyState$filterScript$liveScript$downloadAction$prov$about
        """
          .trimIndent(),
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
     * Running server version (`BUNDLE_VERSION`), shown in the minimal footer. Null omits the build
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
    // however, names one exact preview state/props mapping, so that referenced variant must remain
    // independently visible instead of being folded out with the landing-page variants.
    val comparablePreviews = previews.filterNot { preview ->
      (isNonDefaultState(preview) || hasNonDefaultProps(preview)) &&
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

    fun referenceAttrs(theme: String, preview: ServePreview?): String {
      val reference = preview?.let { referencesFor(it.id).firstOrNull() } ?: return ""
      val raster = "$basePath/reference/${WebEscaping.urlEncodeSegment(reference.id)}.png$q"
      val detailQuery =
        linkQuery(token, linkSessionId, basePath, isPublic).let { query ->
          listOf(query, "reference=${WebEscaping.urlEncodeSegment(reference.id)}")
            .filter { it.isNotEmpty() }
            .joinToString("&")
        }
      val detail =
        "$basePath/compare/${WebEscaping.urlEncodeSegment(preview.id)}${querySuffix(detailQuery)}"
      return " data-reference-$theme=\"${WebEscaping.htmlEscape(raster)}\"" +
        " data-reference-detail-$theme=\"${WebEscaping.htmlEscape(detail)}\""
    }

    val rows =
      cards
        .filter { card ->
          listOfNotNull(card.light, card.dark, card.neutral).any { p ->
            hasSvgFor(p.id) || hasRemoteComposeFor(p.id) || referencesFor(p.id).isNotEmpty()
          }
        }
        .joinToString("\n") { card ->
          val variants = listOfNotNull(card.light, card.dark, card.neutral)
          val current = if (darkFirst) card.dark ?: card.default else card.default
          val label = componentKey(current)
          val viewer = "$basePath/p/${WebEscaping.urlEncodeSegment(current.id)}$q"
          val ids = previewIdsByCard[comparisonCardKey(current)].orEmpty().joinToString(" ")
          val hay = (label + " " + ids).lowercase()
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
          """
          <tr class="cp-compare-row" data-label="${WebEscaping.htmlEscape(label)}"
            data-hay="${WebEscaping.htmlEscape(hay)}" data-preview-ids="${WebEscaping.htmlEscape(ids)}"$pngAttrs$svgAttrs$rcAttrs$referenceAttrs>
            <th scope="row"><a href="$viewer">${WebEscaping.htmlEscape(label)}</a></th>
            <td><div class="cp-compare-shot"><img class="cp-compare-png" alt=""></div></td>
            <td><div class="cp-compare-shot"><img class="cp-compare-vector" alt=""><canvas hidden></canvas></div></td>
            <td class="cp-compare-score">waiting…</td>
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
            "aria-pressed=\"${defaultFormat == "reference"}\">PNG ↔ " +
            "${WebEscaping.htmlEscape(referenceToolLabel)}</button>"
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

    val empty =
      if (rows.isEmpty())
        "<p class=\"cp-empty\">No previews in this session carry a comparable format.</p>"
      else
        """
        <div class="cp-compare-table-wrap">
          <table class="cp-compare-table">
            <thead><tr><th>Preview</th><th>Rendered PNG</th><th class="cp-compare-target-head">SVG</th><th>Match</th></tr></thead>
            <tbody>$rows</tbody>
          </table>
        </div>
        <p id="cp-compare-empty" class="cp-empty" hidden>No comparisons match this filter.</p>
        """
          .trimIndent()
    val rcLanes = rcCompare?.let {
      rcLanesSection(it, previews, previewIdsByCard, token, linkSessionId, basePath, isPublic)
    }
    val rootAttrs =
      "data-default-format=\"$defaultFormat\" data-default-theme=\"${if (darkFirst) "dark" else "light"}\" " +
        "data-theme-key=\"${WebEscaping.htmlEscape(themeStorageKey(sessionId, basePath))}\" " +
        "data-has-svg=\"${if (hasSvg) "1" else "0"}\" data-has-rc=\"${if (hasRc) "1" else "0"}\" " +
        "data-has-reference=\"${if (hasReference) "1" else "0"}\"" +
        (if (rcLanes != null) " data-rc-lanes=\"1\"" else "")

    return document(
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
          <p class="cp-sub"><span class="cp-sub-formats">PNG, SVG and Remote Compose fidelity · scores use structural similarity on a fixed backdrop</span>${
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
          </div>
          <div id="cp-compare-formats">$empty</div>
          ${rcLanes.orEmpty()}
        </div>
        ${scriptTag("url-state.js")}
        ${if (hasRc) scriptTag("rc-fonts.js") else ""}
        ${if (rcLanes != null) scriptTag("rc-lanes.js") else ""}
        ${scriptTag("format-compare.js")}
        """
          .trimIndent(),
    )
  }

  /**
   * The **Remote Compose players** view: every player's published render of every `ir/<id>.rc`
   * document, one column per player, with the baked PNG (the offline Robolectric/Skiko render, and
   * the reference the offline run scored everything against) first.
   *
   * Nothing is diffed until asked. Picking a column as the reference gives every *other* column a
   * pixel diff and a mismatch chip — which is the point of the view: "how far is cmp-wasm from
   * cmp-jvm?" is a question no build-time artifact answers, because the offline run only ever
   * diffed each player against the baked PNG.
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
            ${if (row.referenceBlank) "<div class=\"cp-rc-blank\">baked PNG is fully transparent — nothing to compare against</div>" else ""}
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
          The baked PNG replays the build-time <code>pixelmatch</code> diffs; a player diffs in your browser,
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
     * The served catalog's own palette as an inline `:root` override for the chrome's custom
     * properties, built by [ServeThemeCss] from the branch's `tokens.dtcg.json`. Empty ⇒ the page
     * keeps the built-in chrome (a plain module, or a catalog that publishes no tokens).
     */
    themeCss: String = "",
    unfurl: UnfurlMetadata? = null,
    /**
     * Running server version (`BUNDLE_VERSION`), shown in the minimal footer. Null omits the build
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
    parityIssues: List<ParityIssue> = emptyList(),
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
    return document(
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
        <div id="cp-reference-compare" data-reference="$raster" data-actual="$actual">
          <h1 class="cp-head cp-catalog-head">${WebEscaping.htmlEscape(reference.label)}${compactTrustBadge(trust)}</h1>
          <p class="cp-sub">${WebEscaping.htmlEscape(previewDisplayName(preview))} · ${WebEscaping.htmlEscape(preview.id)}</p>
          $revisionsBlock
          $referencePicker$issueRows
          <div class="cp-reference-meta"><strong>Source:</strong> $source$revision</div>
          <div class="cp-reference-grid">
            <section><h2>Reference</h2><div class="cp-compare-shot" data-cp-annotated="reference"><img src="$raster" alt="Design reference"></div></section>
            <section><h2>Diff</h2><div class="cp-compare-shot"><canvas class="cp-reference-diff" aria-label="Highlighted pixel difference"></canvas></div></section>
            <section><h2>Actual</h2><div class="cp-compare-shot" data-cp-annotated="actual"><img src="$actual" alt="Actual Compose preview"></div></section>
          </div>
          $annotationControls
          <p class="cp-reference-result" role="status">comparing…</p>$report
          <label class="cp-overlay-control">Overlay <input class="cp-overlay-range" type="range" min="0" max="100" value="50"><span>50%</span></label>
          <div class="cp-reference-overlay"><img src="$raster" alt=""><img src="$actual" alt=""></div>
        </div>
        ${scriptTag("url-state.js")}
        ${scriptTag("format-compare.js")}
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
        """
        <a class="cp-page-card" href="$basePath/pages/$id$q">
          <img loading="lazy" alt="" src="$basePath/pages/$id.svg$q">
          <strong>${WebEscaping.htmlEscape(page.name)}</strong>
          <span class="cp-page-count">$linked of ${page.coverageTotal} components implemented</span>
        </a>
        """
          .trimIndent()
      }
    return document(
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
   * `design-page.js` measures each `[data-node-id]` element and places the outline over it. A
   * recorded rectangle would be a second answer to that question, and a worse one — Figma's export
   * box includes effect bleed, so it and the drawn shape disagree on anything with a shadow.
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
          "data-link=\"${WebEscaping.htmlEscape(node.link.wire)}\"" +
          (if (node in gaps) " data-cp-gap" else "") +
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
      title = "${page.name} — page",
      unfurlTitle = "$heading — ${page.name}",
      unfurlDescription = "$linked of $total components on this page are implemented",
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
          <p class="cp-sub">$linked of $total components implemented$figmaLink</p>
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
          </div>
          <div class="cp-page-legend" hidden>
            <span data-link="code-connect"><i class="cp-page-swatch" style="color:#2da44e"></i> Code Connect</span>
            <span data-link="manifest"><i class="cp-page-swatch" style="color:#0969da"></i> design-map</span>
            <span data-link="convention"><i class="cp-page-swatch" style="color:#bf8700"></i> name match</span>
            <span data-link="unlinked"><i class="cp-page-swatch" style="color:#cf222e;border-style:dashed"></i> not implemented</span>
          </div>
          <div class="cp-page-layout">
            <div class="cp-page-stage" style="--cp-page-aspect:$aspect">
              $svg
              <template data-cp-page-render-source>$renders</template>
              <template data-cp-page-diff-links>$diffLinks</template>
              $outlines
              <div class="cp-page-tip" data-cp-page-tip hidden aria-live="polite"></div>
            </div>
            <details class="cp-page-nodes">
              <summary>$linked of $total components implemented</summary>
              <div class="cp-page-list">
              $rows
              </div>
            </details>
          </div>
        </div>
        ${scriptTag("format-compare.js")}
        ${scriptTag("design-page.js")}
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
     * Running server version (`BUNDLE_VERSION`), shown in the minimal footer. Null omits the build
     * span.
     */
    version: String? = null,
    displayTitle: String? = null,
    /** Whether a preview carries a design reference — decides "compare" vs "open" on a link. */
    hasReferenceFor: (String) -> Boolean = { false },
    parityIssues: List<ParityIssue> = emptyList(),
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
        val openBand =
          "<h2 class=\"cp-status-sec\">Components with open issues (${open.size})</h2>" +
            if (open.isEmpty()) "<p class=\"cp-muted\">No open issues.</p>" else summary
        val closedBand =
          if (closed.isEmpty()) ""
          else
            "<h2 class=\"cp-status-sec\">Closed issues (${closed.size})</h2>${parityIssueRowsHtml(closed)}"
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

    val visualIssues =
      if (dashboard.comparisons.none { it.referenceId != null }) ""
      else {
        val count = dashboard.comparisons.count { it.referenceId != null }
        """
        <section class="cp-parity-visual-issues" id="cp-parity-visual-issues">
          <h3 class="cp-parity-sub">Visual differences</h3>
          <p class="cp-muted" id="cp-parity-score-status">Checking $count mapped comparison(s)…</p>
          <div class="cp-status-scroll" id="cp-parity-score-results" hidden>
            <table class="cp-table">
              <thead><tr><th>Component</th><th>Structural match</th><th>Review</th></tr></thead>
              <tbody id="cp-parity-score-issues"></tbody>
            </table>
          </div>
        </section>
        """
          .trimIndent()
      }

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

    val parityScripts = buildString {
      if (dashboard.comparisons.any { it.referenceId != null })
        append(scriptTag("format-compare.js"))
      if (dashboard.feed.isNotEmpty() || dashboard.comparisons.any { it.referenceId != null })
        append(scriptTag("parity.js"))
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
        $issueBand
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
     * Whether this session can derive the **Typography** and **Theme attributes** inspection layers
     * from a render's `compose/semantics` tree ([ServeHost.hasDesignAnnotations]). Same box +
     * legend surface as the accessibility layer, and the same reason for the gate: a static bundle
     * has no daemon to capture the tree.
     */
    hasDesignAnnotations: Boolean = false,
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
     * Running server version (`BUNDLE_VERSION`), shown in the minimal footer. Null omits the build
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
     * Whether this page is served as a **top-level site** ([ServeSites]) — its catalog rooted on a
     * hostname of its own. The session is then implied by the ORIGIN, exactly as a `/<system>`
     * mount implies it by the path, so same-session links must not repeat it as `?session=`. False
     * (the default) leaves every existing caller's URLs byte-identical.
     */
    sessionInOrigin: Boolean = false,
    parityIssues: List<ParityIssue> = emptyList(),
  ): String {
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
    @Suppress("NAME_SHADOWING") val canApplyOverrides = canApplyOverrides && pinned == null
    @Suppress("NAME_SHADOWING") val canRenderOverrides = canRenderOverrides && pinned == null
    @Suppress("NAME_SHADOWING") val hasLiveStream = hasLiveStream && pinned == null
    @Suppress("NAME_SHADOWING") val wasmSrc = wasmSrc?.takeIf { pinned == null }
    @Suppress("NAME_SHADOWING") val hasSvgExport = hasSvgExport && pinned == null
    @Suppress("NAME_SHADOWING") val hasScrollExport = hasScrollExport && pinned == null
    @Suppress("NAME_SHADOWING") val hasRemoteComposeDoc = hasRemoteComposeDoc && pinned == null
    @Suppress("NAME_SHADOWING")
    val enabledRcPlayers = if (pinned == null) enabledRcPlayers else emptyList()
    @Suppress("NAME_SHADOWING") val hasA11yOverlay = hasA11yOverlay && pinned == null
    @Suppress("NAME_SHADOWING") val hasDesignAnnotations = hasDesignAnnotations && pinned == null
    @Suppress("NAME_SHADOWING")
    val executableBundleHref = executableBundleHref?.takeIf { pinned == null }
    val q = querySuffix(linkQuery(token, linkSessionId, basePath, isPublic))
    val navSuffix =
      querySuffix(if (isPublic) "" else "token=" + WebEscaping.urlEncodeSegment(token))
    val displayName = previewDisplayName(preview)
    val issueRows = parityIssueRowsHtml(parityIssues)
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
      if (hasSvgExport)
        "<button type=\"button\" id=\"cp-svg-toggle\" class=\"cp-fmt-toggle\" " +
          "aria-pressed=\"false\" title=\"Show the vector (SVG) render\">SVG</button>"
      else ""
    // The exploded 3D toggle — the layered figma-svg tilted back and pulled apart into one sheet
    // per level of composable nesting ([ExplodedSvg]). It sits beside the SVG toggle because it is
    // a view *of* that export rather than a separate renderer lane, and is gated on the same
    // per-preview [hasSvgExport]: with no layered export there is nothing to pull apart, so the
    // control is omitted rather than offered dead.
    val explodeToggle =
      if (hasSvgExport)
        "<button type=\"button\" id=\"cp-explode-toggle\" class=\"cp-fmt-toggle\" " +
          "aria-pressed=\"false\" title=\"Explode the vector render into one layer per " +
          "composable\">3D</button>"
      else ""
    val svgMatch =
      if (hasSvgExport) {
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
    // worth comparing. So the instruments come to the lane. `spec` is first and is the default, so
    // a visitor who ignores this row sees exactly what the lane always showed.
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
    val specSelector =
      if (specRasterUrl == null || specProviderLabel == null || specLabel == null) ""
      else {
        val tip = "Compare this render against the imported design spec — $specLabel"
        // Hidden until the lane is entered: while a render is on the stage there is no pair to
        // compare, and a control that acts on nothing is worse than no control. spec-compare.js
        // reveals it from openSpec() and hides it again on the way out.
        val viewButtons =
          specViews.joinToString("") { (value, text) ->
            val (viewLabel, viewTip) = text
            "<button type=\"button\" class=\"cp-spec-view\" data-cp-spec-view=\"$value\" " +
              "aria-pressed=\"${value == "spec"}\" " +
              "title=\"${WebEscaping.htmlEscape(viewTip)}\">${WebEscaping.htmlEscape(viewLabel)}</button>"
          }
        "<span class=\"cp-spec-lane\" id=\"cp-spec-lane\" " +
          "data-spec-src=\"${WebEscaping.htmlEscape(specRasterUrl)}\" " +
          "data-spec-label=\"${WebEscaping.htmlEscape(specProviderLabel)}\">" +
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
    // The lane the viewer opens on for a Remote Compose preview: the server-side `java` player when
    // it's available (the default snapshot lane), else the client `js` canvas.
    val defaultRcBackend =
      when {
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
        val label = if (specProviderLabel == "Figma") "Figma" else "Design spec"
        val tip = "Put the imported $specProviderLabel spec on the stage instead of the render"
        "<button type=\"button\" id=\"cp-spec-chip\" class=\"cp-spec-chip\" " +
          "aria-pressed=\"false\" data-spec-chip-label=\"${WebEscaping.htmlEscape(label)}\" " +
          "data-spec-chip-tip=\"${WebEscaping.htmlEscape(tip)}\" " +
          "title=\"${WebEscaping.htmlEscape(tip)}\">${WebEscaping.htmlEscape(label)}</button>"
      }
    val usageAvailable = !usageHref.isNullOrBlank()
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
      if (!usageAvailable) ""
      else {
        val tip = "Show the plain Compose that produces this render"
        "<button type=\"button\" id=\"cp-source-chip\" class=\"cp-spec-chip cp-source-chip\" " +
          "aria-pressed=\"false\" aria-controls=\"cp-source-panel\" " +
          "data-source-chip-tip=\"${WebEscaping.htmlEscape(tip)}\" " +
          "data-usage-src=\"${WebEscaping.htmlEscape(usageHref ?: "")}\" " +
          "title=\"${WebEscaping.htmlEscape(tip)}\">Source</button>"
      }
    val defaultLane = if (enabledRcPlayers.isEmpty()) "png" else "rc:$defaultRcBackend"
    // Rendered only when there is genuinely something to switch *to*: a single-lane preview keeps
    // the chip on its own rather than growing a combo box with one entry in it.
    //
    // It is a **command** menu, not a state field: the always-selected placeholder is what it shows
    // at rest, and `syncLaneSelect` returns it there after every pick. The chip immediately to its
    // left already names the current renderer, and a combo that repeated that name beside it read
    // as two controls arguing about the same fact ("Java  [Java ▾]"). So the chip answers *what am
    // I looking at* and this answers *what else could I look at* — which is the whole split.
    val laneSelectHtml =
      if (lanes.size < 2) ""
      else
        lanes.joinToString(
          separator = "",
          prefix =
            "<select id=\"cp-lane-select\" class=\"cp-lane-select\" " +
              "aria-label=\"Switch renderer\" " +
              "title=\"Draw this preview with a different renderer\" " +
              "data-default=\"$defaultLane\" data-rc-default=\"$defaultRcBackend\">" +
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
    // The chip's opening label: the lane it opens on whenever something else on the row can put a
    // different lane on the stage (the renderer combo, or the design-spec chip), and the plain
    // "Live preview" invitation when this chip is the only lane control there is — with nothing to
    // disambiguate against, the invitation reads better than "Snapshot".
    val primaryLaneLabel =
      if (laneSelectHtml.isEmpty() && specChipHtml.isEmpty()) "Live preview"
      else lanes.firstOrNull { it.value == defaultLane }?.label ?: "Live preview"
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
    // than an `<img>` on purpose: spec-compare.js normalises both frames to one shared pixel space
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
        "<div class=\"cp-spec-compare\" id=\"cp-spec-compare\" hidden data-view=\"spec\" " +
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
          "</div>"
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
    val liveToggleTitleAttr =
      " title=\"" +
        WebEscaping.htmlEscape(
          liveAuthTitle
            ?: if (liveToggleDis.isEmpty())
              "Static snapshot — click for the live, interactive preview"
            else "Static snapshot — this session has no live lane to switch to"
        ) +
        "\""
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
        "data-github-repo=\"${WebEscaping.htmlEscape(it.repository)}\" " +
        "title=\"Sign in with GitHub (${WebEscaping.htmlEscape(it.repository)}) " +
        "to enable Live preview\">\n" +
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
        if (wearAlwaysDark) "<option value=\"dark\"$nightSelected>Night (Default)</option>"
        else
          "<option value=\"light\"$daySelected>Day (Default)</option>\n" +
            "            <option value=\"dark\"$nightSelected>Night (Default)</option>"
      val providerOptions = body.trimEnd().let { if (it.isEmpty()) "" else "\n            $it" }
      // Visually removed, deliberately — the same treatment the render-mode radios get. The Theme
      // axis is now picked from the chips on the viewer bar ([themeBarHtml]), but this select stays
      // the axis's single state holder: viewer.js reads it for every render (`activeThemeChoice`),
      // the sticky script seeds it from the URL + localStorage, and Back/Forward hydration writes
      // to it. Two visible controls for one value is worse than one, so only the chips are shown.
      // `tabindex="-1"` keeps the hidden select out of the tab order, which is what makes the
      // `aria-hidden` wrapper legitimate.
      """
        <span class="cp-modes-inputs" aria-hidden="true">
          <select id="cp-theme" class="cp-knob-theme" data-theme-active="0" data-has-declared-themes="${declaredThemes.isNotEmpty()}" data-fixed-theme="$themeFixed" tabindex="-1"$themeDis>
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
    val themeChipCount = (if (wearAlwaysDark) 1 else 2) + viewerDeclaredThemes.size
    val themeOpen = themeChipCount <= THEME_CHIPS_INLINE
    val themeBarHtml =
      themeChipsHtml(
          builtIns =
            if (wearAlwaysDark) listOf("dark" to "Night")
            else listOf("light" to "Day", "dark" to "Night"),
          declared = viewerDeclaredThemes,
          indent = "          ",
        )
        .let {
          "<span class=\"cp-theme cp-theme-bar\" id=\"cp-theme-bar\" role=\"group\"" +
            " aria-label=\"Preview theme\"${if (themeOpen) "" else " hidden"}>\n" +
            "          $it\n        </span>"
        }
    // The theme toggle's *value* is seeded server-side from the lane this preview is baked in, then
    // kept in sync client-side (viewer-drawers.js mirrors whichever chip `viewer.js` marks pressed)
    // — the theme is picked without a page load, so a server-rendered label alone would go stale on
    // the first click.
    val themeToggle =
      disclosureToggleHtml(
        id = "cp-theme-toggle",
        controls = "cp-theme-bar",
        label = "Theme",
        // Exactly the select's own default rule (`daySelected = viewerTheme != "dark"`), not its
        // inverse: a preview with neither a uiMode nor a light/dark id token has a null
        // [viewerTheme] and opens on Day, so anything other than an explicit dark lane is Day here
        // too. Testing for "light" instead would label every untagged preview Night, contradicting
        // the selected option beside it until the mutation observer got round to fixing it.
        value = if (viewerTheme == "dark") "Night" else "Day",
        open = themeOpen,
        valueId = "cp-theme-toggle-value",
      )
    // Inspection layers (see inspect.js): what the frame is MADE OF, drawn client-side over the
    // pixels the server already sent — the accessibility focus map, the resolved typography, the
    // resolved theme attributes. Each is a box + numbered badge on the stage and a readable row in
    // the legend beside it, so the facts stay legible and hoverable instead of being composited
    // into the render. This is what replaced the old "Accessibility (TalkBack)" toggle, which
    // asked the daemon to bake one focus rectangle and a wall of spoken text into the PNG: it
    // covered the component it was describing, couldn't be inspected, and said nothing about the
    // other stops on the screen.
    //
    // Each row is offered only when its host can actually produce the data (an a11y-capable daemon
    // for the first, a semantics-capturing one for the other two) — never as a dead control.
    val inspectRows = buildString {
      if (hasA11yOverlay)
        append(
          "<label class=\"cp-live-row\"><input class=\"cp-inspect\" id=\"cp-inspect-a11y\" " +
            "data-cp-inspect=\"a11y\" type=\"checkbox\"> Accessibility</label>\n"
        )
      if (hasDesignAnnotations) {
        append(
          "<label class=\"cp-live-row\"><input class=\"cp-inspect\" " +
            "id=\"cp-inspect-typography\" data-cp-inspect=\"typography\" type=\"checkbox\"> " +
            "Typography</label>\n"
        )
        append(
          "<label class=\"cp-live-row\"><input class=\"cp-inspect\" id=\"cp-inspect-theme\" " +
            "data-cp-inspect=\"theme\" type=\"checkbox\"> Theme attributes</label>\n"
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
    // The legend panel beside the stage, populated client-side by inspect.js and hidden until a
    // layer is on. Server-rendered (empty) rather than created by the script so the panel has a
    // stable place in the flex row and the stage doesn't jump sideways the first time a layer is
    // ticked.
    val inspectLayerHtml =
      if (inspectRows.isEmpty()) ""
      else "<div class=\"cp-inspect-layer\" id=\"cp-inspect-layer\"></div>"
    val inspectLegendHtml =
      if (inspectRows.isEmpty()) ""
      else
        "<div class=\"cp-inspect-legend\" id=\"cp-inspect-legend\" role=\"region\" " +
          "aria-label=\"Inspection legend\" hidden></div>"
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
      if (featureRows.isEmpty()) ""
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
      if (isAppScreen)
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
    // Left-hand component nav drawer (default closed) and its toggle — only when the session
    // carries
    // sibling previews to move between. The right-hand overrides drawer (.cp-controls) is always
    // present and defaults open (the `cp-controls-open` class on .cp-viewer).
    // The theme the viewer is showing: the preview's explicit light/dark token, else the dark-first
    // (Wear) default. Drives both the stage backing (below) and the collapsed component nav's link
    // theme, so navigating from a dark preview stays dark.
    val navDrawer = navDrawerHtml(preview, siblings, basePath, q, viewerTheme)
    val navToggle =
      if (navDrawer.isEmpty()) ""
      else
        "<button type=\"button\" class=\"cp-drawer-toggle\" id=\"cp-nav-toggle\" " +
          "aria-expanded=\"false\" aria-controls=\"cp-nav\">☰ Components</button>"
    // The right-hand overrides drawer's toggle, which now sits with the other three disclosures in
    // the title bar rather than alone at the end of the viewer bar. Server-side it is the one that
    // starts expanded (`cp-controls-open` on .cp-viewer below); the drawer script collapses it on a
    // phone, where the preview leads and the drawer opens as a bottom sheet.
    val controlsToggle =
      "<button type=\"button\" class=\"cp-drawer-toggle\" id=\"cp-controls-toggle\" " +
        "aria-expanded=\"true\" aria-controls=\"cp-controls\">⚙ Overrides</button>"
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
    // The axes DISCLOSURE. A component with a wide axis (the published m3-catalog's
    // `iconbutton-outlined` bakes ~30 states) is a long list, and its only load-bearing fact —
    // "which render am I on" — fits in the toggle's own label. So the subtree folds behind one
    // control in the title bar that names the current render, and opens on demand. Inline up to
    // [AXIS_ROWS_INLINE] rows: a two- or three-render component is a short list that reads better
    // shown than hidden behind a click.
    // `+ 1` for the component row, which is itself a render — the default one. Counting only the
    // children would make the threshold drift the moment the default was folded up into that row:
    // a five-render component would count four and open, having counted five and folded the day
    // before, for no reason a reader could see.
    val axisRows = axesTree.split("class=\"cp-tree-variant cp-tree-link\"").size
    val axisOpen = axisRows <= AXIS_ROWS_INLINE
    // What the toggle says when it is closed. The subtree folds BOTH axes, so it names both the
    // axes it folded and the values they hold — a component that varies on state *and* props (RTL,
    // a locale, a font scale) would otherwise lose the variant it is on the moment the tree went
    // away, which is exactly the cost this fold is not allowed to have. State leads, as the axis a
    // reader navigates most; either half drops out when the component does not vary on it.
    val hasStateAxis =
      axesTree.isNotBlank() && componentHasAxis(preview, siblings, viewerDarkFirst, "state")
    val hasPropsAxis =
      axesTree.isNotBlank() && componentHasAxis(preview, siblings, viewerDarkFirst, "props")
    val axisName =
      listOfNotNull("State".takeIf { hasStateAxis }, "Variant".takeIf { hasPropsAxis })
        .joinToString(" · ")
    val axisValue =
      listOfNotNull(
          stateLabel(preview.state).takeIf { hasStateAxis },
          propsLabel(preview.props).takeIf { hasPropsAxis },
        )
        .joinToString(" · ")
    val axesToggle =
      if (axesTree.isBlank()) ""
      else
        disclosureToggleHtml(
          id = "cp-axes-toggle",
          controls = "cp-axes",
          label = axisName,
          value = axisValue,
          open = axisOpen,
        )
    val axesBlock =
      if (axesTree.isBlank()) ""
      else
        "<div class=\"cp-axes\" id=\"cp-axes\"${if (axisOpen) "" else " hidden"}>\n" +
          "$axesTree\n      </div>"
    // Left to right: the chip that names the current renderer and toggles it live, the combo box of
    // alternatives, the design-spec chip (top level, not an option inside the combo), the two
    // subtle
    // "go compare this elsewhere" links, then the SVG format toggle for whatever the chip is
    // currently showing.
    val primaryControls =
      listOf(
          liveToggleHtml,
          laneSelectHtml,
          specChipHtml,
          sourceChipHtml,
          comparePlayersLink,
          specSelector,
          svgFmtToggle,
          explodeToggle,
          svgMatch,
        )
        .filter { it.isNotBlank() }
        .joinToString("\n")
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
    val revisionsBlock = revisionsHtml(revisions) { pin -> withPin("$basePath/p/$idSeg$q", pin) }
    val pinnedAttr =
      revisions.pinned?.let { " data-pinned-at=\"${WebEscaping.htmlEscape(it)}\"" }.orEmpty()
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
          sourceModeInput,
        )
        .filter { it.isNotBlank() }
        .joinToString("\n")
    // `format-compare.js` holds the comparison primitives — content-box normalisation, the
    // edge-tolerant score, the magenta delta map — that BOTH the SVG/PNG fidelity toggle and the
    // spec lane's
    // Diff / Triptych / Slider views draw from, so it loads for either. `spec-compare.js` sits on
    // top of it and must be defined before `viewer.js`, which calls into `window.cpSpecCompare` on
    // the way into (and out of) the lane.
    val compareScriptTags =
      listOfNotNull(
          scriptTag("format-compare.js").takeIf { hasSvgExport || specRasterUrl != null },
          scriptTag("spec-compare.js").takeIf { specRasterUrl != null },
        )
        .joinToString("") { "$it\n      " }
    // The provenance row (source / playground / report an issue / figma spec) no longer sits under
    // the title. It is *about* the preview rather than a control over it, and four lines of small
    // links between the heading and the renderer controls is four lines of chrome between the
    // visitor and the render. It now rides directly above the export bar, where the other
    // "take this away with you" affordances (the PNG and SVG links) already live.
    val previewLinks =
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
    val headToggles =
      listOf(navToggle, axesToggle, themeToggle, controlsToggle).filter { it.isNotBlank() }
    val headTogglesHtml =
      if (headToggles.isEmpty()) ""
      else "\n        <span class=\"cp-head-toggles\">${headToggles.joinToString("")}</span>"
    // Title, trust badge, id and the view tally on ONE baseline-aligned row. They are all
    // *identity* — three separate blocks said so three times, at the cost of ~90px above the fold.
    val body =
      """
      <div class="cp-preview-head">
        <h1 class="cp-head cp-preview-title">$label${compactTrustBadge(trust)}</h1>
        <code class="cp-preview-id" title="$idText">$idText</code>
        ${viewerViewCountHtml(engagement.views)}$headTogglesHtml
      </div>
      $revisionsBlock${degradeBanner(degradations)}$issueRows
      $axesBlock
      <div class="cp-preview-primary" aria-label="Preview renderer">
      $primaryControls
        <span class="cp-mode-hint" id="cp-mode-hint"></span>
        <span class="cp-modes-inputs" aria-hidden="true">
      $modeInputs
        </span>
      </div>
      <div class="cp-viewer-bar">
        $themeBarHtml
        ${bgPickerHtml("Show the transparent checkerboard behind the preview")}
        <button type="button" class="cp-bg-btn cp-zoom-toggle" aria-pressed="false" title="Show the preview at full width instead of fitting it to the screen">Fit width</button>
      </div>
      $historyInlineHtml
      <div class="cp-viewer cp-controls-open"$bgThemeAttr$alwaysDarkAttr$irReplayAttr$replayThemesAttr data-preview-id="$idText" data-mode="snapshot" data-modes="$modes" data-static-snapshot="$staticSnapshot" data-can-render-overrides="$canRenderOverrides" data-snapshot-backend="$backendLabel" data-live-backend="$liveLabel" data-render-density="$RENDER_DENSITY" data-fold-scope="${foldStorageScope(sessionId, basePath)}"$wasmAttr$rcAttr$historyAttrs$pinnedAttr>
        $navDrawer
        <div class="cp-stage"><span class="cp-backend" id="cp-backend" role="status" aria-live="polite"></span><img id="cp-img" alt="$label"><canvas id="cp-canvas" hidden></canvas>$rcCanvas$wasmFrame$rcWasmFrame$specImg$sourcePanelHtml$specCompare$inspectLayerHtml<div class="cp-error" id="cp-error" role="alert" hidden></div></div>
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
          ${exportShapeGroupsHtml(hasScrollExport, hasSvgExport)}
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
          ${overrideKnobsHtml(preview, canApplyOverrides || canRenderOverrides, wasmSrc != null)}
          ${remoteComposeKnobsHtml(preview, canApplyOverrides || canRenderOverrides || hasRcWasm)}
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
      ${downloadLinksHtml(hasSvgExport)}
      <!-- Backdrop shown behind an open drawer on mobile (drawers become bottom sheets there);
           tapping it dismisses the sheet. Inert on desktop. -->
      <div class="cp-scrim" id="cp-scrim" aria-hidden="true"></div>
      ${scriptTag("url-state.js")}
      ${scriptTag("serve-components.js")}
      ${scriptTag("viewer-groups.js")}
      ${scriptTag("viewer-drawers.js")}
      ${scriptTag("viewer-history.js")}
      <script>${viewerThemeStickyScript(themeStorageKey(sessionId, basePath))}</script>${presenceScriptTag(presenceUrl)}
      ${if (hasRemoteComposeDoc) "${scriptTag("rc-fonts.js")}\n      " else ""}$compareScriptTags${scriptTag("viewer.js")}
      ${scriptTag("backend-badge.js")}${if (inspectRows.isEmpty()) "" else "\n      " + scriptTag("inspect.js")}
      """
        .trimIndent()
        .lineSequence()
        .joinToString("\n") { it.trimEnd() }
    return document(
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
  ): String {
    // Collapse to ONE entry per component — the same folding the landing grid does — so the nav
    // reads as a list of components, not of every baked state/theme/props/size permutation
    // (`button-filled` once, not ~14 times). Each entry links to the component's render in the
    // viewer's current [theme] (falling back to its default when it has no such variant); the
    // viewer's own state/variant switchers reach that component's other axes. `aria-current` pins
    // the component being viewed, even when the current preview is a folded (non-default) variant
    // that has no card of its own.
    val representatives =
      groupPreviews(
          siblings.filterNot {
            it.renderFailure == null && (isNonDefaultState(it) || hasNonDefaultProps(it))
          }
        )
        .map {
          when (theme) {
            "dark" -> it.dark ?: it.default
            "light" -> it.light ?: it.default
            else -> it.default
          }
        }
    // Nothing to navigate to when the collapsed list is empty or holds only the current component.
    val currentKey = componentKey(preview)
    if (representatives.none { componentKey(it) != currentKey }) return ""
    val items =
      representatives.joinToString("\n") { p ->
        val segItem = WebEscaping.urlEncodeSegment(p.id)
        val labelItem = WebEscaping.htmlEscape(previewDisplayName(p))
        val idItem = WebEscaping.htmlEscape(p.id)
        // data-search folds label + id so the drawer filter matches either. aria-current pins the
        // one we're viewing (styled as active, and it stays visible even under a filter miss so the
        // list never looks empty-of-self).
        val current = if (componentKey(p) == currentKey) " aria-current=\"page\"" else ""
        // A small thumbnail render to the left of the name — the same baked PNG the landing cards
        // use, so the nav reads like a mini gallery. `alt=""` since the name label beside it
        // already
        // names the component (decorative image).
        "<li><a class=\"cp-nav-item\" href=\"$basePath/p/$segItem$q\"$current " +
          "title=\"$idItem\" data-search=\"$labelItem $idItem\">" +
          "<img class=\"cp-nav-thumb\" loading=\"lazy\" alt=\"\" src=\"$basePath/render/$segItem.png$q\">" +
          "<span class=\"cp-nav-name\">$labelItem</span></a></li>"
      }
    return """
      <aside class="cp-nav" id="cp-nav" aria-label="Components">
        <div class="cp-nav-head"><span>Components</span><button type="button" class="cp-nav-close" id="cp-nav-close" aria-label="Close component navigation">×</button></div>
        <input type="search" class="cp-nav-search" id="cp-nav-search" placeholder="Filter components" autocomplete="off" aria-label="Filter components">
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
     * Running server version (the CLI's `BUNDLE_VERSION`), shown in the minimal [siteFooter] every
     * page ends with. Null omits just the build span; the fixture goldens pass a fixed string so a
     * release never churns the committed HTML.
     */
    version: String? = null,
    /**
     * The served catalog's own palette, projected onto the chrome's custom properties by
     * [ServeThemeCss] and inlined after `serve.css` so it wins at equal specificity. Empty for a
     * plain module / a catalog that publishes no tokens — the page then uses the built-in chrome.
     */
    themeCss: String = "",
    /**
     * The catalog-scoped `localStorage` key this page's theme choice is remembered under (as
     * produced by [themeStorageKey]) — published to the client on `<html data-cp-theme-key>` and
     * read back by the pre-paint script and `page-theme.js`, which need the remembered choice to
     * resolve the page's colour scheme. Empty for a page with no theme control at all (the front
     * door, `/status`, a shared document): those never pin a scheme.
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
    val footerBlock = "\n${siteFooter(version).prependIndent("        ")}"
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
    return """
    <!doctype html>
    <html lang="en"$themeKeyAttr>
      <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">$unfurlBlock
        <title>${WebEscaping.htmlEscape(title)}</title>
${ServeSiteIcon.linkTags().prependIndent("        ")}
        <link rel="stylesheet" href="${assetHref("serve.css")}">$rcFontsBlock$themeBlock
        <!-- Apply the Transparent choice before first paint (no checkerboard flash).
             A `?bg=` on the URL is an explicit, shareable choice and outranks the sticky one. -->
        <script>try{var b=new URLSearchParams(location.search).get("bg");if(b?b==="off":localStorage.getItem("cp-bg")==="off")document.documentElement.classList.add("cp-bg-transparent");}catch(e){}</script>
        ${pageThemeScript(themeStorageKey, declaredThemes)}
      </head>
      <body>
        ${siteHeader(navSuffix, headerAction, headerBreadcrumb, siteName)}
        <main class="cp-main">
        $body
        </main>$footerBlock
        ${scriptTag("page-theme.js")}
      </body>
    </html>
    """
      .trimIndent() + "\n"
  }

  /**
   * Pin the page's colour scheme to the selected preview theme **before first paint**, when the
   * Page theme setting is on (its default — see `page-theme.js` for why it is a setting).
   *
   * Inline and in the `<head>` for the same reason the Transparent restore above is: resolving this
   * from the deferred `page-theme.js` would paint the page in the wrong mode first and correct it a
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
        ?.let { "||localStorage.getItem(${WebEscaping.jsString(it)})" } ?: ""
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
          <div class="cp-knobs-head">One layer per level of composable nesting, from the
            <code>compose/figma-svg</code> export. Rides the SVG link and download.</div>
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

  private fun overrideKnobsHtml(
    preview: ServePreview,
    canApplyOverrides: Boolean,
    wasmAvailable: Boolean = false,
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
        val wireKey = WebEscaping.htmlEscape(if (d.index == null) d.key else "${d.key}[${d.index}]")
        val kind = knobKind(d.type)
        val value = WebEscaping.htmlEscape(overrideValueText(d.current ?: d.default))
        // `data-knob-initial` is the value the control opens on (the author default / seeded
        // current). The viewer omits a knob still equal to it, so the first render carries no
        // `knob.*` and the published catalog serves the instant baked PNG rather than waking the
        // daemon for a fresh (slower, subtly different) re-render.
        val bool = kind == "bool"
        val initial =
          if (bool) (if (value == "true" || value == "1") "true" else "false") else value
        // …and `data-knob-default` is the AUTHOR default, which for a seeded variant is not the
        // same thing. A `@OverrideVariant` preview opens on `current` (`enabled=false`) while its
        // author default is `true`, and the Wasm tier — unlike the PNG lane — has no baked artifact
        // carrying that seed: it mounts the live component and has to be told. So the Wasm patch
        // compares against this rather than against `initial`, or a variant would mount as its
        // primary (see `wasmOverridePatch`).
        val authorDefault = WebEscaping.htmlEscape(overrideValueText(d.default))
        val defaultAttr =
          if (bool) (if (authorDefault == "true" || authorDefault == "1") "true" else "false")
          else authorDefault
        val attrs =
          "class=\"cp-knob\" data-knob-key=\"$wireKey\" data-knob-kind=\"$kind\" " +
            "data-knob-initial=\"$initial\" data-knob-default=\"$defaultAttr\""
        if (bool) {
          val checked = if (value == "true" || value == "1") " checked" else ""
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
          ${selectOptionsHtml(d.options, overrideValueText(d.current ?: d.default))}
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
  private fun remoteComposeKnobsHtml(preview: ServePreview, canApplyOverrides: Boolean): String {
    if (preview.remoteComposeKnobs.isEmpty()) return ""
    // A static bundle without either a server renderer or CMP/Wasm keeps these informational.
    val dis = if (canApplyOverrides) "" else " disabled"
    val rows =
      preview.remoteComposeKnobs.joinToString("\n") { d ->
        val label = WebEscaping.htmlEscape(d.name)
        val wireName = WebEscaping.htmlEscape(d.name)
        val kind = rcKnobKind(d.default)
        val value = WebEscaping.htmlEscape(rcKnobValueText(d.default))
        // `data-rc-initial` is the value the control opens on (the author default). The viewer
        // omits
        // a knob still equal to it, so the first render carries no `rc.*` and a published catalog
        // serves the instant baked snapshot rather than waking the daemon for a fresh re-render.
        val attrs =
          "class=\"cp-rc-knob\" data-rc-name=\"$wireName\" data-rc-kind=\"$kind\" " +
            "data-rc-initial=\"$value\""
        if (kind == "bool") {
          val checked = if (value == "true") " checked" else ""
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
