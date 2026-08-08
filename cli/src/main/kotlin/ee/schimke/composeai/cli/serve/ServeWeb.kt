package ee.schimke.composeai.cli.serve

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
   */
  data class UnfurlMetadata(val pageUrl: String, val imageUrl: String? = null)

  /** Aggregate engagement metrics surfaced by the live server UI/API. */
  data class PreviewEngagement(val views: Long = 0)

  private fun assetHref(name: String): String = ServeWebAssets.href(name)

  private fun scriptTag(name: String): String = "<script src=\"${assetHref(name)}\"></script>"

  private fun viewCountHtml(views: Long): String =
    if (views <= 0) "" else "<div class=\"cp-engage\">${formatViews(views)}</div>"

  private fun viewerViewCountHtml(views: Long): String =
    if (views <= 0) "" else "<p class=\"cp-viewer-engage\">${formatViews(views)}</p>"

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
   * model, shown on public catalog landing pages. The home page uses [homeAboutSection], keeping
   * build links in [homeFooter].
   */
  private fun aboutSection(version: String?): String {
    val ver =
      version
        ?.takeIf { it.isNotBlank() }
        ?.let {
          " · <span class=\"cp-about-ver\" title=\"running preview-server build\">" +
            "server v${WebEscaping.htmlEscape(it)}</span>"
        } ?: ""
    return """
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
        <p class="cp-about-links">
          <a href="https://github.com/$SOURCE_REPO">$GITHUB_ICON source</a> ·
          <a href="/version">/version</a>$ver
        </p>
      </div>
    </details>
    """
      .trimIndent()
  }

  /** Home-page safety explanation without account or build metadata. */
  private fun homeAboutSection(): String =
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

  /** Source and running-build metadata at the bottom of the public home page. */
  private fun homeFooter(version: String?): String {
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
   */
  private fun siteHeader(navSuffix: String, action: String = ""): String {
    val actionHtml = action.takeIf { it.isNotBlank() }?.let { "\n          $it" } ?: ""
    return """
      <header class="cp-site-header">
        <a class="cp-site-brand" href="/$navSuffix" aria-label="compose-preview home">
          <span class="cp-site-mark" aria-hidden="true">◇</span>
          <span>compose-preview</span>
        </a>
        <div class="cp-site-status">
          <span class="cp-daemon-status" id="cp-daemon-status" role="status" hidden></span>
        </div>
        <nav class="cp-site-nav" aria-label="Primary navigation">
          <a href="/$navSuffix">Catalogs</a>
          <a href="/status$navSuffix">Status</a>
          <a href="https://github.com/$SOURCE_REPO">GitHub</a>$actionHtml
        </nav>
      </header>
      """
      .trimIndent()
  }

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
  ): String {
    val links =
      sourceLinkHtml(sourceHref, sourcePath) +
        playgroundLinkHtml(playgroundHref) +
        reportIssueHtml(report) +
        figmaSpecHtml(figmaSpec)
    if (links.isBlank()) return ""
    return "\n      <div class=\"cp-preview-links\">$links\n      </div>"
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
   * with its other states reachable via the viewer's [state switcher][stateSwitcherHtml]. Keyed off
   * the catalog's `state` metadata (from `variants.json`), not the id: a stateless preview / plain
   * bundle screen has `state == null` and is treated as default (always shown).
   */
  private fun isNonDefaultState(p: ServePreview): Boolean = p.state != null && p.state != "default"

  /**
   * Whether [p] is a **non-default props variant** — an i18n / content / a11y axis render
   * (`{"locale":"ar-XB"}`, `{"direction":"rtl"}`, `{"fontScale":"2.0"}`,
   * `{"content":"icon+label"}`, …) the grid folds out so a component shows ONE card (its default
   * render) instead of a card per variant, with the folded variants reachable via the viewer's
   * [variant switcher] [variantSwitcherHtml]. Keyed off the catalog's `props` metadata (from
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
   * The viewer's **state switcher**: a `<nav>` of plain links from [current] to each of its
   * component's baked states *in the same theme* (one link per distinct state, the default state
   * first, the current one marked `aria-current="page"`). No daemon, no JS state machine — each
   * link is a normal navigation to a sibling `/p/<id>` page, so it works with scripting off.
   *
   * Siblings are drawn from [all] (the host's whole preview list, which still carries the
   * non-default states the grid folds out) by [stateInvariantKey] + [ServePreview.theme]: renders
   * that differ *only* in state, holding the theme and any other variant axis (content / size /
   * props) fixed, so a component that also varies on a non-state axis doesn't cross-link its axes.
   * Returns the empty string when fewer than two states share this key — nothing to toggle.
   */
  private fun stateSwitcherHtml(
    current: ServePreview,
    all: List<ServePreview>,
    basePath: String,
    q: String,
  ): String {
    val key = stateInvariantKey(current)
    // One preview per distinct state, first appearance wins, restricted to the current variant
    // (same
    // key) and theme so the switcher never jumps the visitor across a non-state axis or light/dark.
    val byState = LinkedHashMap<String, ServePreview>()
    for (p in all) {
      if (stateInvariantKey(p) != key || p.theme != current.theme) continue
      byState.putIfAbsent(p.state ?: "default", p)
    }
    if (byState.size < 2) return ""
    // Default state leads; the rest keep catalog order (a stable sort preserves first appearance).
    val ordered = byState.entries.sortedBy { if (it.key == "default") 0 else 1 }
    val links =
      ordered.joinToString("\n") { (_, p) ->
        val href = "$basePath/p/${WebEscaping.urlEncodeSegment(p.id)}$q"
        val active = if (p.id == current.id) " aria-current=\"page\"" else ""
        "<a class=\"cp-state-btn\" href=\"$href\"$active>${WebEscaping.htmlEscape(stateLabel(p.state))}</a>"
      }
    return """
      <nav class="cp-states" aria-label="Component state">
        <span class="cp-axis-label">State</span>
        $links
      </nav>
      """
      .trimIndent()
  }

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
   * The viewer's **variant switcher**: a `<nav>` of plain links from [current] to its component's
   * baked props-axis variants (an RTL render, a pseudo-locale, a large-font render, an icon+label
   * render) in the SAME theme and state, the default first, the current one marked
   * `aria-current="page"`. The props analogue of [stateSwitcherHtml]: the grid folds these variants
   * out ([hasNonDefaultProps]) so a component shows one card, and this keeps them reachable. Drawn
   * from [all] (the host's whole preview list) by [propsFamilyKey] + theme + state so a component
   * that also varies on state or theme doesn't cross those axes. Empty when fewer than two variants
   * share the family — nothing to switch.
   */
  private fun variantSwitcherHtml(
    current: ServePreview,
    all: List<ServePreview>,
    basePath: String,
    q: String,
  ): String {
    val key = propsFamilyKey(current)
    val curState = current.state ?: "default"
    // One preview per distinct props signature, first appearance wins, restricted to the current
    // family + theme + state so the switcher never jumps the visitor across a non-props axis.
    val byVariant = LinkedHashMap<String, ServePreview>()
    for (p in all) {
      if (propsFamilyKey(p) != key) continue
      if (p.theme != current.theme) continue
      if ((p.state ?: "default") != curState) continue
      byVariant.putIfAbsent(propsSignature(p.props), p)
    }
    if (byVariant.size < 2) return ""
    // The default (empty props) leads; the rest keep catalog order (a stable sort preserves it).
    val ordered = byVariant.entries.sortedBy { if (it.key == "") 0 else 1 }
    val links =
      ordered.joinToString("\n") { (_, p) ->
        val href = "$basePath/p/${WebEscaping.urlEncodeSegment(p.id)}$q"
        val active = if (p.id == current.id) " aria-current=\"page\"" else ""
        "<a class=\"cp-state-btn\" href=\"$href\"$active>${WebEscaping.htmlEscape(propsLabel(p.props))}</a>"
      }
    return """
      <nav class="cp-states" aria-label="Component variant">
        <span class="cp-axis-label">Variant</span>
        $links
      </nav>
      """
      .trimIndent()
  }

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

  /** One sub-heading group inside a section tab: its [name] (null ⇒ ungrouped) and its cards. */
  private class LandingGroup(val name: String?) {
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
        acc.groups.values
          .sortedBy { g -> g.cards.minOf { ord(it) } }
          .forEach { g ->
            val ordered = LandingGroup(g.name)
            ordered.cards.addAll(g.cards.sortedBy { ord(it) })
            section.groups.add(ordered)
          }
        section
      }
  }

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
        append(" data-theme-choice=\"theme:${WebEscaping.htmlEscape(t.providerFqn)}\"$title>")
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
   * simply missing the control rather than the behaviour. `bg-toggle.js` wires both.
   */
  private fun bgPickerHtml(title: String): String =
    "<button type=\"button\" class=\"cp-bg-btn cp-bg-toggle\" aria-pressed=\"false\"" +
      " title=\"${WebEscaping.htmlEscape(title)}\">Transparent</button>"

  /**
   * The search box for the landing grid: a text input that filters cards to those whose label or id
   * contains the typed text, plus a live result count. Progressive enhancement — the server emits
   * every card and [catalogFilterScript] does the hiding, so a no-JS client still sees the full
   * grid. Shown whenever the module has previews (independent of the theme toggle, which only
   * appears for per-theme catalogs). [count] seeds the total for the "N of M" readout.
   */
  private fun searchBoxHtml(count: Int): String =
    """
    <div class="cp-searchbar">
      <input id="cp-search" class="cp-search" type="search" placeholder="Filter previews…"
        autocomplete="off" spellcheck="false" aria-label="Filter previews" aria-controls="cp-grid">
      <span id="cp-count" class="cp-count" role="status" aria-live="polite" data-total="$count"></span>
      ${bgPickerHtml("Show the transparent checkerboard behind each preview")}
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
          "\n            if (img && base) setCardSrc(img, base);"
      else ""
    val applyTheme =
      if (hasThemes)
        """
        themeBtns.forEach(function (b) {
          b.setAttribute("aria-pressed", b.getAttribute("data-theme-choice") === theme ? "true" : "false");
        });
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
        // apply() also runs on every search keystroke; re-point the cards only when the THEME
        // actually changed, so typing never restarts an in-flight themed-render queue.
        function applyThemeChoice() {
          if (theme === appliedTheme) return;
          appliedTheme = theme;$applyDeclaredThemeIndented
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
    val tabDecls =
      if (hasTabs)
        "\n      var tabBtns = document.querySelectorAll(\".cp-tab\");" +
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
          "\n      function reflectTabs() {" +
          "\n        tabBtns.forEach(function (t) {" +
          "\n          t.setAttribute(\"aria-selected\", t.getAttribute(\"data-tab\") === current ? \"true\" : \"false\");" +
          "\n        });" +
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
      if (input) { var urlQuery = urlParam("q"); if (urlQuery) input.value = urlQuery; }$groupDecls$tabDecls
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
        if (empty) empty.hidden = shown !== 0;$groupPost$sectionPost
      }
      if (input) input.addEventListener("input", function () {
        // Typing REPLACES rather than pushes: a five-character filter must not bury the page the
        // visitor arrived from under five entries. The URL still carries the query, so the
        // filtered grid is bookmarkable.
        replaceUrl({ q: input.value.trim() });
        apply();
      });
      $themeWiring$tabWiring$popWiring
      apply();$presenceWiring
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
        var chosen =
          !el.disabled && (el.value === "light" || el.value === "dark") ? el.value : "";
        var m = chosen || bgDefault;
        if (m) root.setAttribute("data-bg-theme", m);
        else root.removeAttribute("data-bg-theme");
      }
      // Round-trip every unified choice, including `theme:<provider>`, to the catalog page.
      el.addEventListener("change", function () {
        el.setAttribute("data-theme-active", "1");
        try { localStorage.setItem("$themeStorageKey", el.value); } catch (e) {}
        syncBg();
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
     * Running server version (the CLI's `BUNDLE_VERSION`), surfaced in the home footer beside the
     * source/`/version` links so the live build is visible on the front door. Null omits it; the
     * fixture golden passes a fixed string so a release never churns the committed HTML.
     */
    version: String? = null,
    /** Absolute page + representative hero URLs for Open Graph/Twitter link previews. */
    unfurl: UnfurlMetadata? = null,
    githubAuth: GitHubAuthStatus? = null,
  ): String {
    val about = if (isPublic) homeAboutSection() + "\n" else ""
    val headerAction = githubAuthControl(githubAuth)
    val footer = if (isPublic) homeFooter(version) else ""
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
      title = "Design systems — compose-preview",
      unfurlTitle = "Compose previews",
      unfurlDescription =
        "Browse ${systems.size} published Compose design system and app catalogs.",
      unfurl = unfurl,
      navSuffix = suffix,
      headerAction = headerAction,
      footer = footer,
      body =
        """
        $about$body
        """
          .trimIndent(),
    )
  }

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
  ): String {
    val suffix = querySuffix(if (isPublic) "" else "token=" + WebEscaping.urlEncodeSegment(token))
    return document(
      title = "Not found — compose-preview",
      unfurlDescription = message,
      unfurl = unfurl,
      navSuffix = suffix,
      body =
        """
        <h1 class="cp-head">Not found</h1>
        <p class="cp-sub">${WebEscaping.htmlEscape(message)}</p>
        <a class="cp-back" href="/$suffix">← All design systems</a>
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
    unfurl: UnfurlMetadata? = null,
  ): String {
    val suffix = querySuffix(queryString(token, sessionId = null, isPublic = isPublic))
    val sample = WebEscaping.htmlEscape(seed?.text ?: PLAYGROUND_SAMPLE)
    val fileName = seed?.fileName ?: "Snippet.kt"
    // A seed names its own catalog; a catalog-page link names one without any source. Either way it
    // only wins if this host actually offers it — a link built before a catalog loaded (or against
    // one whose backend this host can't render) falls back to the first entry rather than
    // preselecting something the Run button would refuse.
    val wanted = (seed?.catalog ?: preselectCatalog)?.takeIf { id -> catalogs.any { it.id == id } }
    val selectedIndex = catalogs.indexOfFirst { it.id == wanted }.takeIf { it >= 0 } ?: 0
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
      navSuffix = suffix,
      body =
        """
        <link rel="stylesheet" href="${assetHref("codemirror.css")}">
        <link rel="stylesheet" href="${assetHref("playground.css")}">
        <h1 class="cp-head">Playground</h1>
        <p class="cp-sub">Write a Compose snippet, compile it against the live catalog, and open a
          preview. This lane runs your code on the server, so it stays behind your token.</p>
        <div class="cp-pg">$emptyNote$seedNote
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
  ): String {
    val suffix = querySuffix(if (isPublic) "" else "token=" + WebEscaping.urlEncodeSegment(token))
    return document(
      title = "Playground unavailable — compose-preview",
      unfurlDescription = "The playground is not enabled on this server.",
      unfurl = unfurl,
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
    return document(
      title = "${doc.name} — compose-preview",
      unfurlDescription = "A shared ${doc.formatLabel} document, played back in your browser.",
      unfurl = unfurl,
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
        <script>${docPlayerScript(doc, rawUrl)}</script>
        """
          .trimIndent(),
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
          """
          fetch(raw).then(function (r) { return r.arrayBuffer(); }).then(function (buf) {
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
  fun statusPage(view: StatusView, token: String, unfurl: UnfurlMetadata? = null): String {
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
          "<tr>" +
            "<td>$title$listed" +
            "<div class=\"cp-muted\">${esc(c.id)}</div>$prov</td>" +
            "<td>$trustCell</td>" +
            "<td>${c.previews}</td>" +
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
      title = "Server status — compose-preview",
      body = body,
      unfurlDescription =
        "Live catalog, render-daemon, and deployment status for this compose-preview server.",
      unfurl = unfurl,
      navSuffix = suffix,
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
    if (previews.isEmpty()) return null
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
    val anyScreen = previews.any { isScreenPreview(it) }
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
    return previews.sortedWith(compareBy({ score(it) }, { it.id })).first().id
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
    /** Whether this session has at least one SVG or Remote Compose format to compare. */
    hasFormatComparison: Boolean = false,
    /**
     * Whether this catalog has a design-parity view to link to — it maps at least one preview to a
     * design reference, or it publishes a `parity/activity.json` feed. False (the default) omits
     * the link entirely rather than offering a page of zeroes, so a plain module / an unmapped
     * catalog's landing is unchanged.
     */
    hasParityView: Boolean = false,
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
     * Running server version (the CLI's `BUNDLE_VERSION`), surfaced in the (now bottom-of-page)
     * about box beside the source/`/version` links. Null omits it; the fixture golden passes a
     * fixed string so a release never churns the committed HTML.
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
     * only for cards the session can actually re-render ([canRenderThemeFor]); empty (default)
     * keeps the plain light/dark axis.
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
  ): String {
    val q = querySuffix(linkQuery(token, sessionId, basePath, isPublic))
    val themeLeaseUrl =
      if (themeRenderBurstCapacity > 1) "$basePath/api/theme-render-lease$q" else ""
    val navSuffix =
      querySuffix(if (isPublic) "" else "token=" + WebEscaping.urlEncodeSegment(token))
    val heading = displayTitle?.takeIf { it.isNotBlank() } ?: moduleLabel
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
      groupPreviews(previews.filterNot { isNonDefaultState(it) || hasNonDefaultProps(it) })
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
    // Whether a declared theme actually redraws this preview: it needs a daemon twin AND must not
    // be a theme specimen, which has a twin but must keep its baked pixels ([isThemeSpecimen]).
    // ONE predicate feeding both the chip gate and the per-card URL, deliberately: gating the chips
    // on mere renderability while the URLs also excluded specimens would offer the control on a
    // catalog whose only twinned cards are specimens — every `themeBase` empty, the browser's
    // `if (!img || !base) return` skipping every card, and the chips a no-op.
    fun themeOverridable(p: ServePreview) = themeRenderable(p) && !isThemeSpecimen(p)
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
    fun swapCard(card: GridCard): String {
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
      // `data-def` is the variant a DECLARED theme re-renders (the server-side default), so picking
      // one doesn't also flip the card's light/dark base.
      return """
        <a class="cp-card" data-swap="1" data-bg-theme="$defTheme" data-def="${if (darkFirst) "d" else "l"}"
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
            <div class="cp-id">${WebEscaping.htmlEscape(def.id)}</div>
            ${viewCountHtml(cardViews(card))}
          </div>
        </a>
        """
        .trimIndent()
    }
    fun singleCard(p: ServePreview): String {
      val idSeg = WebEscaping.urlEncodeSegment(p.id)
      val label = WebEscaping.htmlEscape(gridDisplayName(p))
      val src = renderSrc(p)
      val idText = WebEscaping.htmlEscape(p.id)
      // data-bg-theme is the thumbnail's background (explicit token, else the dark-first default).
      val bgAttr = bgTheme(p.id, darkFirst)?.let { " data-bg-theme=\"$it\"" } ?: ""
      return """
          <a class="cp-card"$bgAttr href="$basePath/p/$idSeg$q">
            <div class="cp-imgwrap">
              ${thumbImg(src, label, " loading=\"lazy\"", thumbCrop(p.id))}
            </div>
            <div class="cp-meta">
              <div class="cp-label" title="$idText">$label</div>
              <div class="cp-id">$idText</div>
              ${viewCountHtml(engagement[p.id]?.views ?: 0L)}
            </div>
          </a>
          """
        .trimIndent()
    }
    fun cardHtml(card: GridCard): String =
      if (card.swappable) swapCard(card) else singleCard(card.default)
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
    val tabBar =
      if (!hasTabs) ""
      else
        buildString {
          append(
            "<nav class=\"cp-tabs\" id=\"cp-tabs\" role=\"tablist\" aria-label=\"Catalog sections\">\n"
          )
          sections.forEachIndexed { i, sec ->
            val selected = if (i == 0) "true" else "false"
            append("  <a class=\"cp-tab\" role=\"tab\" id=\"cp-tab-${sec.slug}\"")
            append(" href=\"#cp-panel-${sec.slug}\" data-tab=\"${sec.slug}\"")
            append(" aria-controls=\"cp-panel-${sec.slug}\" aria-selected=\"$selected\">")
            append(
              "${WebEscaping.htmlEscape(sec.name)}<span class=\"cp-tab-count\">${sec.count}</span></a>\n"
            )
          }
          append("</nav>\n")
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
            append("<div class=\"cp-subgroup\">\n")
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
            append("<section class=\"cp-section\" id=\"cp-panel-${sec.slug}\" role=\"tabpanel\"")
            append(" aria-labelledby=\"cp-tab-${sec.slug}\" data-section=\"${sec.slug}\">\n")
            append("<h2 class=\"cp-section-head\">${WebEscaping.htmlEscape(sec.name)}</h2>\n")
            sec.groups.forEach { g ->
              append("<div class=\"cp-subgroup\">\n")
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
    // The "about" intro now sits at the BOTTOM of a catalog page (below the grid) so the catalog's
    // own content leads; it still appears only for the public server.
    val about = if (isPublic) "\n" + aboutSection(version) else ""
    // A catalog page links HOME (the front-door index) rather than sideways to its siblings: the
    // old design-systems nav row is replaced by a single back button, shown whenever this server
    // publishes catalogs (i.e. a home index exists to go back to).
    val back = if (hasHomeIndex) backButton(token, isPublic) + "\n" else ""
    // The catalog-provenance strip (delivery branch, generation date, tool versions, regenerate
    // link), shown under the header for a served design-system catalog.
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
    val searchBox = if (hasPreviews) searchBoxHtml(previews.size) + "\n" else ""
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
        "\n${scriptTag("url-state.js")}\n${scriptTag("bg-toggle.js")}\n<script>${catalogFilterScript(
          hasThemes,
          hasTabs,
          hasGroups,
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
        query = linkQuery(token, sessionId, basePath, isPublic),
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
    val formatLink =
      if (hasFormatComparison)
        " · <a class=\"cp-format-link\" href=\"$basePath/compare$q\">compare formats</a>"
      else ""
    // Joins the same run of catalog-level actions as "compare formats" rather than becoming a tab:
    // parity is a property OF this catalog, and the grid stays the page's subject.
    val parityLink =
      if (hasParityView)
        " · <a class=\"cp-format-link\" href=\"$basePath/parity$q\">design parity</a>"
      else ""
    // Subtle by placement, not by styling: it joins the summary line's existing run of
    // catalog-level actions rather than becoming a button competing with the grid. Absent on a host
    // with no playground lane, so it never reads as an offer this server cannot keep.
    val playgroundLink =
      playgroundHref
        ?.takeIf { it.isNotBlank() }
        ?.let {
          " · <a class=\"cp-format-link\" href=\"${WebEscaping.htmlEscape(it)}\">" +
            "try in playground</a>"
        } ?: ""
    return document(
      title = "$heading — compose-preview",
      unfurlTitle = heading,
      unfurlDescription = "${previews.size} Compose previews in $heading",
      unfurl = unfurl,
      navSuffix = navSuffix,
      themeCss = themeCss,
      body =
        """
        $back<h1 class="cp-head cp-catalog-head">${WebEscaping.htmlEscape(heading)}${compactTrustBadge(trust)}</h1>
        $catalogId${degradeBanner(degradations)}$prov<p class="cp-sub">${previews.size} preview(s)${if (systemViews > 0) " · ${formatViews(systemViews)}" else ""} ·
          <a href="$basePath/bundle.zip$q">download all (.zip)</a>$formatLink$parityLink$playgroundLink$liveNote</p>
        <div class="cp-catalog-tools">
        $themeToggle$searchBox</div>
        $tabBar$gridBlock$emptyState$filterScript$liveScript$about
        """
          .trimIndent(),
    )
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
    displayTitle: String? = null,
  ): String {
    val q = querySuffix(linkQuery(token, sessionId, basePath, isPublic))
    val navSuffix =
      querySuffix(if (isPublic) "" else "token=" + WebEscaping.urlEncodeSegment(token))
    val heading = displayTitle?.takeIf { it.isNotBlank() } ?: moduleLabel
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
        linkQuery(token, sessionId, basePath, isPublic).let { query ->
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
            "aria-pressed=\"${defaultFormat == "reference"}\">PNG ↔ Design reference</button>"
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
      rcLanesSection(it, previews, previewIdsByCard, token, sessionId, basePath, isPublic)
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
      navSuffix = navSuffix,
      themeCss = themeCss,
      body =
        """
        <div id="cp-compare" $rootAttrs>
          <p class="cp-breadcrumb"><a href="$basePath/$q">${WebEscaping.htmlEscape(heading)}</a> / Compare formats</p>
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
    sessionId: String?,
    basePath: String,
    isPublic: Boolean,
  ): String? {
    if (manifest.lanes.isEmpty() || manifest.rows.isEmpty()) return null
    val q = querySuffix(linkQuery(token, sessionId, basePath, isPublic))
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
    displayTitle: String? = null,
    /**
     * Typography / layout annotations for the reference raster and the rendered frame. Either side
     * may be empty — a producer that annotates only one panel still gets that panel's layers, and a
     * session with no annotations at all renders exactly as before (no toggles, no payload).
     */
    referenceAnnotations: List<DesignAnnotation> = emptyList(),
    actualAnnotations: List<DesignAnnotation> = emptyList(),
  ): String {
    val q = querySuffix(linkQuery(token, sessionId, basePath, isPublic))
    val navSuffix =
      querySuffix(if (isPublic) "" else "token=" + WebEscaping.urlEncodeSegment(token))
    val heading = displayTitle?.takeIf { it.isNotBlank() } ?: moduleLabel
    val actual = "$basePath/render/${WebEscaping.urlEncodeSegment(preview.id)}.png$q"
    val raster = "$basePath/reference/${WebEscaping.urlEncodeSegment(reference.id)}.png$q"
    // One toggle per kind, offered only when some panel actually carries that kind — a control that
    // reveals nothing is worse than no control. The payload rides inline rather than behind a fetch
    // so the layers are there on first paint, like the rest of this page's data.
    val annotated = referenceAnnotations + actualAnnotations
    val annotationControls =
      if (annotated.isEmpty()) ""
      else {
        val toggles =
          listOf(AnnotationKind.LAYOUT to "Layout", AnnotationKind.TYPOGRAPHY to "Typography")
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
    val referencePicker =
      if (referenceChoices.size <= 1) ""
      else {
        val baseQuery = linkQuery(token, sessionId, basePath, isPublic)
        val links =
          referenceChoices.joinToString("\n") { choice ->
            val query =
              listOf(
                  baseQuery.takeIf { it.isNotEmpty() },
                  "reference=${WebEscaping.urlEncodeSegment(choice.id)}",
                )
                .filterNotNull()
                .joinToString("&")
            val href =
              WebEscaping.htmlEscape(
                "$basePath/compare/${WebEscaping.urlEncodeSegment(preview.id)}?${query}"
              )
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
    return document(
      title = "${reference.label} — design comparison",
      unfurlTitle = "$heading design comparison",
      unfurlDescription = "Reference, diff, and Compose output for ${preview.id}",
      unfurl = unfurl,
      navSuffix = navSuffix,
      themeCss = themeCss,
      body =
        """
        <div id="cp-reference-compare" data-reference="$raster" data-actual="$actual">
          <p class="cp-breadcrumb"><a href="$basePath/compare$q">${WebEscaping.htmlEscape(heading)}</a> / Design comparison</p>
          <h1 class="cp-head cp-catalog-head">${WebEscaping.htmlEscape(reference.label)}${compactTrustBadge(trust)}</h1>
          <p class="cp-sub">${WebEscaping.htmlEscape(previewDisplayName(preview))} · ${WebEscaping.htmlEscape(preview.id)}</p>
          $referencePicker
          <div class="cp-reference-meta"><strong>Source:</strong> $source$revision</div>
          <div class="cp-reference-grid">
            <section><h2>Reference</h2><div class="cp-compare-shot" data-cp-annotated="reference"><img src="$raster" alt="Design reference"></div></section>
            <section><h2>Diff</h2><div class="cp-compare-shot"><canvas class="cp-reference-diff" aria-label="Highlighted pixel difference"></canvas></div></section>
            <section><h2>Actual</h2><div class="cp-compare-shot" data-cp-annotated="actual"><img src="$actual" alt="Actual Compose preview"></div></section>
          </div>
          $annotationControls
          <p class="cp-reference-result" role="status">comparing…</p>
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
   * The catalog's **Design parity** view: recent movement on both sides of the code ↔ design pair,
   * how far apart they are, and what isn't mapped yet.
   *
   * The page is three bands, in the order a reader actually needs them:
   *
   * 1. **Where we stand** — coverage (how many components carry a design reference), open Figma
   *    comments, and how recently each side moved. Computed live for the coverage half, so it is
   *    right even for a catalog that publishes no feed at all.
   * 2. **Needs a look** — components whose two sides moved *unevenly* inside the window. This is
   *    the band that justifies putting the feeds together: a component with a commit and no design
   *    change (or the reverse) is where the render and its reference are drifting apart, and every
   *    row links straight to that component's reference-vs-render comparison.
   * 3. **Recent activity** — the merged, reverse-chronological feed itself, filterable by lane.
   *
   * Then the gaps: components with no reference (derived here) plus the producer-declared gaps only
   * the publish job can see.
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
    displayTitle: String? = null,
    /** Whether a preview carries a design reference — decides "compare" vs "open" on a link. */
    hasReferenceFor: (String) -> Boolean = { false },
  ): String {
    fun esc(s: String) = WebEscaping.htmlEscape(s)
    val q = querySuffix(linkQuery(token, sessionId, basePath, isPublic))
    val navSuffix =
      querySuffix(if (isPublic) "" else "token=" + WebEscaping.urlEncodeSegment(token))
    val heading = displayTitle?.takeIf { it.isNotBlank() } ?: moduleLabel
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
    val stats =
      buildList {
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
        <h2 class="cp-status-sec">Needs a look</h2>
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
        <h2 class="cp-status-sec">Recent activity</h2>
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
        <h2 class="cp-status-sec">Recent activity</h2>
        <div class="cp-states" role="group" aria-label="Filter activity by lane">
        $filters
        </div>
        <ul class="cp-parity-feed" id="cp-parity-feed">
        $items
        </ul>
        <p class="cp-muted" id="cp-parity-feed-empty" hidden>No activity in this lane.</p>
        ${scriptTag("parity.js")}
        """
          .trimIndent()
      }

    val unmappedBand =
      if (coverage.unmapped.isEmpty() && dashboard.gaps.isEmpty()) {
        if (coverage.components == 0) ""
        else
          """
          <h2 class="cp-status-sec">Mapping</h2>
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
        <h2 class="cp-status-sec">Mapping</h2>
        $unmappedList
        $gapRows
        """
          .trimIndent()
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
      navSuffix = navSuffix,
      themeCss = themeCss,
      body =
        """
        <p class="cp-breadcrumb"><a href="$basePath/$q">← ${esc(heading)}</a></p>
        <h1 class="cp-head cp-catalog-head">Design parity${compactTrustBadge(trust)}</h1>
        <p class="cp-sub">How this catalog's code and its design file have moved, and how far apart
          they are.</p>
        $sourcesStrip
        <div class="cp-status-grid">
        $stats
        </div>
        $coverageMeter
        <p class="cp-muted">${coverage.percent}% of ${coverage.components} component(s) carry a
          design reference.</p>
        $driftBand
        $feedBand
        $unmappedBand
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
  ): String {
    val idSeg = WebEscaping.urlEncodeSegment(preview.id)
    val q = querySuffix(linkQuery(token, sessionId, basePath, isPublic))
    val navSuffix =
      querySuffix(if (isPublic) "" else "token=" + WebEscaping.urlEncodeSegment(token))
    val displayName = previewDisplayName(preview)
    val label = WebEscaping.htmlEscape(displayName)
    val idText = WebEscaping.htmlEscape(preview.id)
    val catalogName = WebEscaping.htmlEscape(catalogTitle?.takeIf { it.isNotBlank() } ?: "Previews")
    val modes = preview.modes.joinToString(",") { it.wire }
    // Wear OS is an always-dark surface. Do not expose the generic day/night override: besides
    // being meaningless for Wear, an old light choice within the Wear catalog must not turn into a
    // confetti-wear live render.
    val wearAlwaysDark = SystemDisplay.isDarkFirst(basePath.trim('/').ifBlank { sessionId ?: "" })
    val alwaysDarkAttr = if (wearAlwaysDark) " data-always-dark=\"1\"" else ""
    // The baked fallback shown before any override is chosen. The unified Theme selector displays
    // this choice without sending a redundant uiMode override on first load.
    val viewerTheme = previewTheme(preview, isDarkFirstSystem(basePath, sessionId, declaredSurface))
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
    val svgMatch =
      if (hasSvgExport) {
        val compareQuery =
          listOf(
              "format=svg",
              "preview=${WebEscaping.urlEncodeSegment(preview.id)}",
              linkQuery(token, sessionId, basePath, isPublic),
            )
            .filter { it.isNotEmpty() }
            .joinToString("&")
        "<span id=\"cp-svg-match\" class=\"cp-match\" role=\"status\" aria-live=\"polite\" hidden></span>" +
          "<a id=\"cp-svg-diff\" class=\"cp-format-link\" href=\"$basePath/compare?$compareQuery\" hidden>view diff →</a>"
      } else ""
    // A dedicated "In-browser (Wasm)" toggle, shown only when the session carries BOTH a daemon
    // live
    // lane ([hasLiveStream]) and a Wasm app ([wasmSrc]). The single Static⇄Live toggle prefers the
    // daemon (bestLiveMode), which otherwise leaves the Wasm tier unreachable from the viewer even
    // though it's registered. Omitted when no Wasm app backs the session, and when there's no
    // daemon
    // (the Static⇄Live toggle already drops into Wasm as its only interactive lane). Reuses the
    // `.cp-live-toggle` styling so it reads as a peer of "Live preview".
    val wasmToggleBtn =
      if (wasmSrc != null && hasLiveStream)
        "<button type=\"button\" id=\"cp-wasm-btn\" class=\"cp-live-toggle\" aria-pressed=\"false\" " +
          "title=\"Run this component in your browser (Kotlin/Wasm)\">" +
          "<span class=\"cp-live-dot\" aria-hidden=\"true\"></span><span>In-browser (Wasm)</span>" +
          "</button>"
      else ""
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
    // The Remote Compose backend selector (#cp-rc-backends): one chip per RcPlayerBackend.UNIVERSE,
    // enabled for those the host reports in [enabledRcPlayers] and disabled otherwise. It replaces
    // the former single "RC (browser)" button — the `js` chip drives the same in-browser canvas
    // lane (setMode("rc")), while `java` / `cmp-android` re-render the PNG server-side via
    // `rcPlayer=<wire>`. `cmp-jvm` uses the same URL through its isolated subprocess lane. Rendered
    // only for a Remote Compose preview (a non-empty enabled set). `data-default` seeds the
    // initially-current chip: the server-side `java` player when it's available (the default
    // snapshot lane), else the client `js` canvas.
    val rcBackendSelector =
      if (enabledRcPlayers.isEmpty()) ""
      else {
        val enabled = enabledRcPlayers.toSet()
        val defaultBackend =
          when {
            RcPlayerBackend.JAVA.wire in enabled -> RcPlayerBackend.JAVA.wire
            RcPlayerBackend.JS.wire in enabled -> RcPlayerBackend.JS.wire
            else -> enabled.first()
          }
        val chips =
          RcPlayerBackend.UNIVERSE.joinToString("") { backend ->
            val on = backend.wire in enabled
            val disabledAttr = if (on) "" else " disabled"
            val title =
              when {
                on ->
                  "Render this component's Remote Compose document with the ${backend.label} player"
                backend == RcPlayerBackend.CMP_JVM ->
                  "CMP JVM player — not available for this session (install lib-rcjvm and " +
                    "lib-daemon-desktop)"
                else -> "${backend.label} player — not available for this session"
              }
            "<button type=\"button\" class=\"cp-live-toggle cp-rc-backend\" " +
              "data-rc-backend=\"${backend.wire}\" aria-pressed=\"false\"$disabledAttr " +
              "title=\"${WebEscaping.htmlEscape(title)}\">" +
              "<span class=\"cp-live-dot\" aria-hidden=\"true\"></span>" +
              "<span>${WebEscaping.htmlEscape(backend.label)}</span></button>"
          }
        "<span class=\"cp-rc-backends\" id=\"cp-rc-backends\" role=\"group\" " +
          "aria-label=\"Remote Compose renderer\" data-default=\"$defaultBackend\">" +
          "<span class=\"cp-rc-backends-label\">RC:</span>$chips</span>"
      }
    // The **Spec lane**: the imported design reference for this exact preview, offered as a peer of
    // the renderer chips. Where the RC chips choose *which player draws the code*, this chooses to
    // look at *what the design says* instead — the catalog's own inert PNG, served from
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
    val specRasterUrl = designReference?.let {
      "$basePath/reference/${WebEscaping.urlEncodeSegment(it.id)}.png$q"
    }
    // The focused Reference / Diff / Actual page for this exact mapping — the same link the
    // comparison grid offers, so the chip's neighbour steps from "look at the spec" to "diff it".
    val specCompareHref = designReference?.let { reference ->
      val query =
        listOf(
            linkQuery(token, sessionId, basePath, isPublic),
            "reference=${WebEscaping.urlEncodeSegment(reference.id)}",
          )
          .filter { it.isNotEmpty() }
          .joinToString("&")
      "$basePath/compare/$idSeg${querySuffix(query)}"
    }
    val specSelector =
      if (specRasterUrl == null || specProviderLabel == null || specLabel == null) ""
      else {
        val tip = "Show the imported design spec for this preview — $specLabel"
        "<span class=\"cp-spec-lane\" id=\"cp-spec-lane\" role=\"group\" " +
          "aria-label=\"Design spec\" " +
          "data-spec-src=\"${WebEscaping.htmlEscape(specRasterUrl)}\" " +
          "data-spec-label=\"${WebEscaping.htmlEscape(specProviderLabel)}\">" +
          "<span class=\"cp-rc-backends-label\">Spec:</span>" +
          "<button type=\"button\" class=\"cp-live-toggle cp-spec-btn\" id=\"cp-spec-btn\" " +
          "aria-pressed=\"false\" title=\"${WebEscaping.htmlEscape(tip)}\">" +
          "<span class=\"cp-live-dot\" aria-hidden=\"true\"></span>" +
          "<span>${WebEscaping.htmlEscape(specProviderLabel)}</span></button>" +
          "<a class=\"cp-format-link cp-spec-diff\" " +
          "href=\"${WebEscaping.htmlEscape(specCompareHref.orEmpty())}\" " +
          "title=\"Compare this render against the spec\">view diff →</a></span>"
      }
    // The stage image the Spec lane paints into: a sibling of the snapshot `<img>`, left `hidden`
    // (and src-less) until the lane is entered, so a viewer that never opens it costs no request.
    val specImg =
      if (specRasterUrl == null) ""
      else
        "<img id=\"cp-spec-img\" class=\"cp-spec-img\" hidden alt=\"" +
          "${WebEscaping.htmlEscape("$displayName — design spec")}\">"
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
    // Server-render controls (size / device / orientation / background): enabled whenever the
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
    val liveToggleTitleAttr =
      liveAuthTitle?.let { " title=\"${WebEscaping.htmlEscape(it)}\"" } ?: ""
    val liveToggleButton =
      "<button type=\"button\" id=\"cp-live-toggle\" class=\"cp-live-toggle\" " +
        "aria-pressed=\"false\"$liveToggleDis>\n" +
        "            <span class=\"cp-live-dot\" aria-hidden=\"true\"></span>\n" +
        "            <span id=\"cp-live-toggle-label\">Live preview</span>\n" +
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
            "interact. Day/Night, Font scale, Locale, background &amp; declared knob values apply in " +
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
          "<option value=\"theme:${WebEscaping.htmlEscape(t.providerFqn)}\"$providerDis>" +
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
    val themeBarHtml =
      themeChipsHtml(
          builtIns =
            if (wearAlwaysDark) listOf("dark" to "Night")
            else listOf("light" to "Day", "dark" to "Night"),
          declared = viewerDeclaredThemes,
          indent = "          ",
        )
        .let {
          "<span class=\"cp-theme cp-theme-bar\" role=\"group\" aria-label=\"Preview theme\">\n" +
            "          $it\n        </span>"
        }
    // Live overlay toggles (accessibility / touch visualization). The daemon composites these onto
    // the held session's frames, so they mean nothing on a baked PNG — offered only when a Live
    // Compose stream is available, and omitted entirely otherwise rather than left permanently
    // dead. Rendered **enabled**: a visitor who ticks one while the viewer is still on the static
    // snapshot is asking to see the overlay, so the JS switches into Live Compose for them (the
    // ticked toggle rides in on the stream's initial overrides) instead of presenting a dead
    // control that first demands a click on "Live preview". They carry `$liveDis` — the same gate
    // as the live transport radio — so the one case where they really are dead (the stream exists
    // but is behind sign-in) stays greyed out in the server-rendered markup, matching what
    // `syncOverlayToggles()` reconciles to. `cp-overlay` marks them for the JS collector + sync.
    val overlaysHtml =
      if (hasLiveStream)
        """
        <details class="cp-group" data-cp-group="overlays">
          <summary>Overlays</summary>
          <div class="cp-group-body">
            <div class="cp-overlays">
              <div class="cp-overlays-head">Overlays (Live Compose)</div>
              <label class="cp-live-row"><input class="cp-overlay" id="cp-talkBack" type="checkbox"$liveDis> Accessibility (TalkBack)</label>
              <label class="cp-live-row"><input class="cp-overlay" id="cp-touchOverlay" type="checkbox"$liveDis> Show touches</label>
            </div>
          </div>
        </details>
        """
          .trimIndent()
      else ""
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
    // Stage background follows the preview's theme (dark variant → dark stage), with a dark-first
    // system (Wear) defaulting to dark — see the `.cp-viewer[data-bg-theme] .cp-stage` CSS. Kept
    // separate from the filter's data-card-theme; the viewer JS re-syncs it on a Theme (uiMode)
    // change so a re-render in the opposite theme doesn't clash with a stale backing color.
    val bgThemeAttr = viewerTheme?.let { " data-bg-theme=\"$it\"" } ?: ""
    // The component-state switcher: plain links to this component's other baked states (same
    // theme).
    // Empty for a single-state component / a stateless preview, so nothing renders there.
    val stateSwitcher = stateSwitcherHtml(preview, siblings, basePath, q)
    // The variant switcher: links to this component's props-axis variants (RTL / locale / fontScale
    // / content) folded out of the grid, in the same theme + state. Empty when the component has no
    // such variants, so a plain / state-only catalog is unchanged. Concatenated after the state
    // switcher (both empty ⇒ the interpolation stays "", preserving the section-less golden).
    val variantSwitcher = variantSwitcherHtml(preview, siblings, basePath, q)
    val switchers =
      listOf(stateSwitcher, variantSwitcher).filter { it.isNotBlank() }.joinToString("\n")
    val primaryControls =
      listOf(liveToggleHtml, wasmToggleBtn, rcBackendSelector, specSelector, svgFmtToggle, svgMatch)
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
        )
        .filter { it.isNotBlank() }
        .joinToString("\n")
    val body =
      """
      <p class="cp-breadcrumb"><a href="$basePath/$q">$catalogName</a> / Component</p>
      <h1 class="cp-head cp-preview-title">$label${compactTrustBadge(trust)}</h1>
      <p class="cp-preview-id" title="$idText"><code>$idText</code></p>
      ${degradeBanner(degradations)}${previewLinksHtml(sourceHref, preview.sourceFile, reportIssue, figmaSpec, playgroundHref)}
      ${viewerViewCountHtml(engagement.views)}
      $switchers
      <div class="cp-preview-primary" aria-label="Preview renderer">
      $primaryControls
        <span class="cp-mode-hint" id="cp-mode-hint"></span>
        <span class="cp-modes-inputs" aria-hidden="true">
      $modeInputs
        </span>
      </div>
      <div class="cp-viewer-bar">
        $navToggle
        $themeBarHtml
        ${bgPickerHtml("Show the transparent checkerboard behind the preview")}
        <button type="button" class="cp-bg-btn cp-zoom-toggle" aria-pressed="false" title="Show the preview at full width instead of fitting it to the screen">Fit width</button>
        <button type="button" class="cp-drawer-toggle" id="cp-controls-toggle" aria-expanded="true" aria-controls="cp-controls">⚙ Overrides</button>
      </div>
      $historyInlineHtml
      <div class="cp-viewer cp-controls-open"$bgThemeAttr$alwaysDarkAttr data-preview-id="$idText" data-mode="snapshot" data-modes="$modes" data-static-snapshot="$staticSnapshot" data-can-render-overrides="$canRenderOverrides" data-snapshot-backend="$backendLabel" data-live-backend="$liveLabel" data-render-density="$RENDER_DENSITY"$wasmAttr$rcAttr$historyAttrs>
        $navDrawer
        <div class="cp-stage"><span class="cp-backend" id="cp-backend" role="status" aria-live="polite"></span><img id="cp-img" alt="$label"><canvas id="cp-canvas" hidden></canvas>$rcCanvas$wasmFrame$rcWasmFrame$specImg<div class="cp-error" id="cp-error" role="alert" hidden></div></div>
        <div class="cp-controls" id="cp-controls">
          <details class="cp-group" data-cp-group="appearance">
            <summary>Appearance</summary>
            <div class="cp-group-body">
              $themeSelectorHtml
              <label>Background
                <select id="cp-background"$serverDis>
                  <option value="">(default)</option>
                  <option value="clear">Clear (crisp outline)</option>
                </select>
              </label>
            </div>
          </details>
          $sizeControlsHtml
          ${scrollGroupHtml(hasScrollExport, hasSvgExport)}
          <details class="cp-group" data-cp-group="locale">
            <summary>Locale &amp; text</summary>
            <div class="cp-group-body">
              <label>Locale
                <input id="cp-localeTag" type="text" list="cp-localeTag-list" placeholder="e.g. en-GB, zh-Hant-TW" autocomplete="off"$wasmDis>
                <!-- A datalist, not a fixed <select>: the presets (pseudolocales, RTL, common
                     tags) drop down for quick picking, but any valid BCP-47 tag the server
                     accepts can still be typed in. -->
                <datalist id="cp-localeTag-list">
                  <option value="en-XA" label="Accented (pseudo)"></option>
                  <option value="ar-XB" label="Bidi / RTL (pseudo)"></option>
                  <option value="ar" label="Arabic (RTL)"></option>
                  <option value="he" label="Hebrew (RTL)"></option>
                  <option value="fa" label="Persian (RTL)"></option>
                  <option value="en-US"></option>
                  <option value="en-GB"></option>
                  <option value="de-DE"></option>
                  <option value="fr-FR"></option>
                  <option value="es-ES"></option>
                  <option value="pt-BR"></option>
                  <option value="ru-RU"></option>
                  <option value="ja-JP"></option>
                  <option value="ko-KR"></option>
                  <option value="zh-CN"></option>
                  <option value="zh-Hant-TW"></option>
                  <option value="hi-IN"></option>
                  <option value="th-TH"></option>
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
           heading so it is visible before a tall stage. -->
      <div class="cp-below">
        $snapshotNote
        ${downloadLinksHtml(hasSvgExport)}
      </div>
      <!-- Backdrop shown behind an open drawer on mobile (drawers become bottom sheets there);
           tapping it dismisses the sheet. Inert on desktop. -->
      <div class="cp-scrim" id="cp-scrim" aria-hidden="true"></div>
      ${scriptTag("url-state.js")}
      ${scriptTag("bg-toggle.js")}
      ${scriptTag("viewer-groups.js")}
      ${scriptTag("viewer-drawers.js")}
      ${scriptTag("viewer-history.js")}
      <script>${viewerThemeStickyScript(themeStorageKey(sessionId, basePath))}</script>${presenceScriptTag(presenceUrl)}
      ${if (hasSvgExport) "${scriptTag("format-compare.js")}\n      " else ""}${scriptTag("viewer.js")}
      ${scriptTag("backend-badge.js")}
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
      navSuffix = navSuffix,
      themeCss = themeCss,
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
      groupPreviews(siblings.filterNot { isNonDefaultState(it) || hasNonDefaultProps(it) }).map {
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
    footer: String = "",
    /**
     * The served catalog's own palette, projected onto the chrome's custom properties by
     * [ServeThemeCss] and inlined after `serve.css` so it wins at equal specificity. Empty for a
     * plain module / a catalog that publishes no tokens — the page then uses the built-in chrome.
     */
    themeCss: String = "",
  ): String {
    val unfurlHtml =
      if (unfurl == null) ""
      else {
        val metaTitle = WebEscaping.htmlEscape(unfurlTitle ?: title)
        val description =
          WebEscaping.htmlEscape(unfurlDescription ?: "Compose preview rendered by compose-preview")
        val pageUrl = WebEscaping.htmlEscape(unfurl.pageUrl)
        val imageUrl = unfurl.imageUrl?.let(WebEscaping::htmlEscape)
        val imageHtml =
          if (imageUrl == null) ""
          else
            """
            <meta property="og:image" content="$imageUrl">
            <meta property="og:image:type" content="image/png">
            <meta property="og:image:alt" content="$metaTitle">
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
        <meta name="twitter:card" content="${if (imageUrl == null) "summary" else "summary_large_image"}">
        <meta name="twitter:title" content="$metaTitle">
        <meta name="twitter:description" content="$description">
        $twitterImageHtml
        """
          .trimIndent()
      }
    val unfurlBlock = if (unfurlHtml.isEmpty()) "" else "\n${unfurlHtml.prependIndent("        ")}"
    val footerBlock =
      footer.takeIf { it.isNotBlank() }?.let { "\n${it.prependIndent("        ")}" } ?: ""
    val themeBlock =
      themeCss
        .takeIf { it.isNotBlank() }
        ?.let { "\n" + ("<style>\n" + it.trimEnd() + "\n</style>").prependIndent("        ") } ?: ""
    return """
    <!doctype html>
    <html lang="en">
      <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">$unfurlBlock
        <title>${WebEscaping.htmlEscape(title)}</title>
        <link rel="stylesheet" href="${assetHref("serve.css")}">$themeBlock
        <!-- Apply the Transparent choice before first paint (no checkerboard flash).
             A `?bg=` on the URL is an explicit, shareable choice and outranks the sticky one. -->
        <script>try{var b=new URLSearchParams(location.search).get("bg");if(b?b==="off":localStorage.getItem("cp-bg")==="off")document.documentElement.classList.add("cp-bg-transparent");}catch(e){}</script>
      </head>
      <body>
        ${siteHeader(navSuffix, headerAction)}
        <main class="cp-main">
        $body
        </main>$footerBlock
      </body>
    </html>
    """
      .trimIndent() + "\n"
  }

  /**
   * The copyable direct-link panel: the `/render/<id>.png` (and, when [hasSvgExport], `.svg`) URL
   * for the preview **with the current overrides applied**. Each row shows a read-only URL field
   * (click it to copy the URL), a one-click "Copy PNG"/"Copy SVG" button that copies the rendered
   * artefact itself as clipboard text (SVG markup verbatim; PNG as a base64 `data:` URI), and a
   * Download link (`<a download>`). The viewer JS keeps the URLs in sync as the controls / knobs
   * change (see `refreshLinks`), so the copied URL/artefact always reflects the on-screen state — a
   * shareable, scriptable handle on the exact render (a `curl`-able PNG/SVG). The URLs are built
   * client-side from `location.origin` + the session base, so they're absolute and work from
   * anywhere; the fields start empty and are filled on first render.
   *
   * This is a **page-level section under the stage**, not a group inside the collapsible overrides
   * drawer: grabbing the URL / PNG / SVG of what's on screen is the viewer's primary hand-off, so
   * it stays visible whether or not the ⚙ Overrides drawer is open (and, on mobile, without opening
   * a bottom sheet). The one control that genuinely *shapes* the export — "Full page (scroll)" —
   * lives in the overrides drawer's Scroll group instead ([scrollGroupHtml]).
   */
  private fun downloadLinksHtml(hasSvgExport: Boolean): String {
    fun row(kind: String, ext: String): String =
      """
      <div class="cp-link-row">
        <span class="cp-link-kind">$kind</span>
        <input id="cp-url-$ext" class="cp-url" type="text" readonly aria-label="$kind URL"
          title="Click to copy the URL">
        <button type="button" class="cp-copyimg" data-copyimg-target="cp-url-$ext"
          data-copyimg-ext=".$ext">Copy $kind</button>
        <a id="cp-dl-$ext" class="cp-dl" download>Download</a>
      </div>
      """
        .trimIndent()
    // The SVG lane is export-only now (no on-screen SVG mode); its shape is controlled by the
    // "Full page (scroll)" toggle over in the overrides drawer's Scroll group.
    val svgRow = if (hasSvgExport) "\n" + row("SVG", "svg") else ""
    return """
      <details class="cp-export cp-disclosure">
        <summary id="cp-export-head">Export &amp; direct links</summary>
        <div class="cp-links cp-disclosure-body">
          <div class="cp-knobs-head">The current view as a URL (overrides applied)</div>
          ${row("PNG", "png")}$svgRow
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
    return seen.joinToString("\n") { "<option value=\"${WebEscaping.htmlEscape(it)}\"></option>" }
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
        } else {
          val inputType = if (d.type == "int" || d.type == "float") "number" else "text"
          // Any knob that carries discovered options — a font knob (declared via
          // `previewOverrideFont` / `catalogOverrideFont`, with autocomplete suggestions and/or the
          // Google Fonts flag) or any other knob with declared `suggestions` (e.g. `theme.colors`)
          // —
          // renders as a combobox "like Locale": a free-text `<input list>` bound to a `<datalist>`
          // (declared names first, then, for a font knob, the full fonts.google.com list). Any knob
          // with no options stays a plain text/number input.
          val hasOptions = d.googleFonts || d.suggestions.isNotEmpty()
          if (hasOptions) {
            val listId = "cp-dl-" + wireKey.replace(Regex("[^A-Za-z0-9_-]"), "-")
            val options = fontDatalistOptions(d.suggestions, d.googleFonts)
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
