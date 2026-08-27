package ee.schimke.composeai.cli.serve

/**
 * Builds the prefilled GitHub **new-issue** report for a bug in the **preview server itself** — the
 * running `compose-preview serve` process, its render lanes, and the web UI it draws — as opposed
 * to [ServeIssueReport], which files a bug about a *preview* against the repo whose Kotlin declares
 * it.
 *
 * **Why the two are separate rather than one affordance with a repo switch.** They differ in every
 * dimension that matters. [ServeIssueReport] targets a repo it has to *derive* (the catalog's
 * source, else its delivery repo, else a fallback), and the facts it carries identify a preview —
 * component, variant, reference, overrides — because that is what a catalog maintainer needs to
 * reproduce a wrong-looking button. This one always targets [REPO], because there is exactly one
 * repo that ships the server, and the facts it carries describe a *deployment*: which build is
 * running, on which JVM and OS, in which posture, with which catalogs loaded or failed, and what
 * the render lanes have been doing. A visitor whose knob does nothing, whose render 500s, or whose
 * page draws wrong is looking at a server bug, and routing that into the catalog's issue tracker
 * sends it to people who cannot fix it. Making one report try to be both would mean a body that is
 * half-empty whichever bug it is.
 *
 * **Why the affordance is server-wide rather than per-preview.** The per-preview report hangs off a
 * preview because that is its subject. A server bug has no such anchor — the page that misbehaved
 * may be the front door, `/status`, or a catalog that failed to load and has no viewer at all — so
 * this one rides in the site footer on every page, beside the build number it is a bug in.
 *
 * **Why a prefilled link and not a server-side filing**, and **why a form rather than an anchor**:
 * both for the reasons written up on [ServeIssueReport] — the server holds no issue-write token by
 * design, and writing page state into an `href` is a navigation sink. The same [ServeIssueReport]
 * helpers are reused here rather than reimplemented, so a token can't leak into one report body
 * after being stripped from the other.
 */
internal object ServeBugReport {

  /**
   * The repo that ships the preview server. Fixed, not derived: unlike a preview — which belongs to
   * whichever project declared it — the server has exactly one home, and a bug in it filed anywhere
   * else reaches people who cannot fix it.
   */
  const val REPO: String = "yschimke/compose-ai-tools"

  /** Labels pre-applied to reports opened by the server UI. */
  const val LABELS: String = "ui-report,bug,daemon"

  /** The report page's path, offered from the site footer on every browser-facing page. */
  const val PATH: String = "/report-bug"

  /** Query parameter naming the in-server page the visitor pressed "report a bug" from. */
  const val FROM_PARAM: String = "from"

  /**
   * Stand-in for the **browser** facts block inside [body]. Only the client knows its user agent,
   * viewport and colour scheme, and those are exactly what a "the page draws wrong" report turns on
   * — so the server leaves this marker in the form's hidden `body` and the page script swaps in a
   * filled block. With JS off the marker is dropped rather than shipped, leaving a report that is
   * simply missing its browser section instead of one carrying a literal `{{client}}`.
   */
  const val CLIENT_PLACEHOLDER: String = "{{client}}"

  /** Facts about the running server, independent of which page the visitor came from. */
  data class Server(
    /** `SERVE_VERSION` — the build the bug is in. */
    val version: String?,
    /** True when the host answers without a token (`--public`). */
    val public: Boolean,
    /** Seconds since the process started; a bug that only appears after a long uptime says so. */
    val uptimeSeconds: Long? = null,
    /** `java.version` (`java.vendor`), as the render JVM reports it. */
    val java: String? = null,
    /** `os.name os.version (os.arch)`. */
    val os: String? = null,
    /** Catalogs that are not cleanly loaded right now, as `<system>: <state>` lines. */
    val unhealthyCatalogs: List<String> = emptyList(),
    /** Most recent daemon-startup / render failures, newest first, already one-line each. */
    val recentFailures: List<String> = emptyList(),
  )

  /**
   * What the visitor was looking at. Every field is optional: pressing the affordance on the front
   * door yields a report with no page section at all, which is correct — there is no catalog, no
   * preview and no render lane to name, and inventing rows for them would pad the report with
   * "unknown" where "not applicable" is the truth.
   */
  data class Page(
    /** In-server path, token-stripped (`/m3/view/Button__filled`). */
    val path: String? = null,
    /** Absolute URL of that page, token-stripped, so a triager can open what the reporter saw. */
    val url: String? = null,
    /** Served design system, when the page belonged to one. */
    val system: String? = null,
    /** The preview on screen, when the page was a viewer or a comparison. */
    val previewId: String? = null,
    /** Delivery provenance as `owner/repo@branch`. */
    val catalog: String? = null,
    /** compose-ai-tools version that produced that catalog — often *not* [Server.version]. */
    val catalogToolVersion: String? = null,
    /** Bundle-verification verdict for the served catalog. */
    val trust: String? = null,
    /** How this session renders: a live daemon, baked PNGs, … */
    val renderLane: String? = null,
    /**
     * Which of the viewer's lanes and views the page was actually showing, as [viewLabel] reads it
     * out of the reporter's own query — `design spec — triptych`, `motion`, `exploded layers`.
     *
     * Load-bearing for issue #4261. The [renderUrl] this report embeds is always the plain
     * `/render` PNG, because that is the only image of a preview the server can *produce*: the spec
     * lane's triptych, the wipe, the exploded stack and the Remote Compose canvas are all composed
     * in the browser out of several artefacts, and none of them has a URL. So a report filed from
     * the triptych used to arrive showing a single ordinary render, with nothing anywhere in it
     * admitting that the reporter had been looking at something else — the triager saw a picture
     * that contradicted the complaint. This row is the honest half of the fix: it names the view,
     * so the embedded PNG reads as "the base render at these settings" rather than as "what they
     * saw". The other half is the browser-side capture the report page offers, which is the only
     * way to get the actual pixels.
     */
    val view: String? = null,
    /** Why the session is degraded, when it is — `<code> — <detail>` lines. */
    val degradations: List<String> = emptyList(),
    /** `/render/<id>.png` at the overrides in force, token-stripped. */
    val renderUrl: String? = null,
    /**
     * Whether the render lane answers **without a token** — i.e. the server is `--public`. Same
     * rule as [ServeIssueReport.Context.publicRender]: a token-gated lane 404s the tokenless URL
     * this body carries, so embedding it would put a broken image in every filed issue.
     */
    val publicRender: Boolean = false,
  )

  /** The GitHub new-issue form for [REPO]. A literal — see [ServeIssueReport.action]. */
  fun action(): String = "https://github.com/$REPO/issues/new"

  /**
   * Issue body, in markdown.
   *
   * [clientPlaceholder] leaves [CLIENT_PLACEHOLDER] where the browser block goes, for the hidden
   * form input the page script rewrites; the visible copy shown on the report page passes false so
   * the reporter reads the same text that will be filed, minus the part their browser fills in.
   */
  fun body(server: Server, page: Page, clientPlaceholder: Boolean = false): String {
    val render = ServeIssueReport.withoutToken(page.renderUrl)?.takeIf { it.isNotBlank() }
    // Same two independent conditions the per-preview report checks: GitHub's camo proxy has to
    // reach the URL, and the lane has to answer it without the token this body strips.
    val embed = render != null && page.publicRender && ServeIssueReport.isEmbeddable(page.renderUrl)
    return buildString {
      append("### What went wrong\n\n")
      append("<!-- What were you doing, what did you expect, and what happened instead? -->\n\n\n")
      append("### Screenshot\n\n")
      append("<!-- Paste your capture of the page here. -->\n\n\n")
      // The base render goes BELOW the paste slot and says what it is, rather than standing in as
      // "the screenshot" — see [Page.view] and issue #4261. It is the plain `/render` PNG at the
      // overrides in force, which is the right evidence for "this button is the wrong colour" and
      // the wrong evidence for "the triptych draws its middle panel twice": the server has no URL
      // for a browser-composed view, so the only honest thing it can do is label what it does have
      // and let the reporter paste the rest.
      val onView = view(page)
      if (embed) {
        append("### Base render").append(onView).append("\n\n")
        append("![render](").append(render).append(")\n\n")
        append(
          "<!-- That image is a LIVE render: it re-renders if the catalog changes, so it may " +
            "stop showing what you saw. GitHub displays it through Camo, but Camo proxies the " +
            "source URL; it does not make a versioned snapshot. A pasted screenshot of the " +
            "page stays put because GitHub hosts those pixels itself. -->\n\n\n"
        )
      } else if (render != null) {
        append("### Base render").append(onView).append("\n\n")
        append("[PNG at these settings](").append(render).append(")\n\n\n")
      }
      append("### Server\n\n")
      append(table(serverRows(server)))
      pageRows(page)
        .takeIf { it.isNotEmpty() }
        ?.let {
          append("\n### Page\n\n")
          append(table(it))
        }
      if (clientPlaceholder) append("\n").append(CLIENT_PLACEHOLDER).append("\n")
      server.unhealthyCatalogs
        .takeIf { it.isNotEmpty() }
        ?.let { append("\n### Catalogs not loaded\n\n").append(fence(it)) }
      server.recentFailures
        .takeIf { it.isNotEmpty() }
        ?.let { append("\n### Recent failures\n\n").append(fence(it)) }
    }
  }

  /**
   * The parenthetical that keeps the "Base render" heading from over-claiming: on a page that was
   * showing a browser-composed view, it says which one, so the single PNG under the heading is not
   * mistaken for the thing the reporter is complaining about.
   */
  private fun view(page: Page): String =
    page.view?.trim()?.takeIf { it.isNotEmpty() }?.let { " — you were on the ${text(it)}" } ?: ""

  /** The browser half of the report, filled client-side and spliced over [CLIENT_PLACEHOLDER]. */
  fun clientBlock(rows: List<Pair<String, String>>): String =
    if (rows.isEmpty()) "" else "### Browser\n\n" + table(rows)

  private fun serverRows(server: Server): List<Pair<String, String>> = buildList {
    server.version?.takeIf { it.isNotBlank() }?.let { add("compose-preview" to code(it)) }
    add("Mode" to (if (server.public) "public (open)" else "token-gated"))
    server.uptimeSeconds?.takeIf { it >= 0 }?.let { add("Uptime" to duration(it)) }
    // Labelled "Server JVM", not "Java", because that is all it is. A project whose
    // `daemon-launch.json` names a `javaLauncher` renders on THAT JDK, not on the one running the
    // HTTP server — so calling this "Java" would file a render failure under the wrong runtime and
    // send a triager looking at the wrong toolchain. Naming the scope is honest and costs nothing;
    // claiming the renderer's JDK without reading the daemon descriptor would not be.
    server.java?.takeIf { it.isNotBlank() }?.let { add("Server JVM" to code(it)) }
    server.os?.takeIf { it.isNotBlank() }?.let { add("Server OS" to code(it)) }
  }

  private fun pageRows(page: Page): List<Pair<String, String>> = buildList {
    val url = ServeIssueReport.withoutToken(page.url)?.takeIf { it.isNotBlank() }
    val path = page.path?.trim()?.takeIf { it.isNotEmpty() }
    when {
      // The path is the readable identity and the URL is the openable one, so when both are known
      // the row is a link *labelled* by the path rather than a bare URL or a dead code span.
      url != null && path != null -> add("Page" to "[${code(path)}](${cell(url)})")
      url != null -> add("Page" to text(url))
      path != null -> add("Page" to code(path))
    }
    page.system?.trim()?.takeIf { it.isNotEmpty() }?.let { add("Design system" to code(it)) }
    page.previewId?.trim()?.takeIf { it.isNotEmpty() }?.let { add("Preview" to code(it)) }
    page.catalog?.trim()?.takeIf { it.isNotEmpty() }?.let { add("Catalog" to code(it)) }
    page.catalogToolVersion
      ?.trim()
      ?.takeIf { it.isNotEmpty() }
      ?.let { add("Catalog rendered by" to "compose-ai-tools ${text(it)}") }
    page.trust?.trim()?.takeIf { it.isNotEmpty() }?.let { add("Trust" to text(it)) }
    page.renderLane?.trim()?.takeIf { it.isNotEmpty() }?.let { add("Render lane" to text(it)) }
    page.view?.trim()?.takeIf { it.isNotEmpty() }?.let { add("View" to text(it)) }
    page.degradations
      .map { it.trim() }
      .filter { it.isNotEmpty() }
      .takeIf { it.isNotEmpty() }
      ?.let { add("Degraded" to text(it.joinToString("; "))) }
  }

  /**
   * The header is the same two-column shell the other report uses. Values arrive already composed
   * (a code span, a link, plain text) with their *raw* parts escaped by [code] / [text] — escaping
   * here instead would mangle the markdown those rows deliberately contain.
   */
  private fun table(rows: List<Pair<String, String>>): String = buildString {
    append("| | |\n| --- | --- |\n")
    rows.forEach { (key, value) ->
      append("| ").append(key).append(" | ").append(value).append(" |\n")
    }
  }

  /**
   * Make arbitrary text safe inside a markdown table cell.
   *
   * Nearly every value in this report is text this server did not write: a degradation detail, a
   * catalog's own provenance and trust strings, a load error. A `|` in any of them shears the row
   * into extra columns, and a backtick closes the code span the value sits in and lets the rest
   * render as markdown — so a report about a broken catalog arrives with its diagnostics visibly
   * mangled, which is the worst moment for the table to stop being a table.
   *
   * Order matters: the backslash goes first, or it would double the escapes added after it. Same
   * rule, and the same reason, as the browser block's own escaping in `bugReport.ts`.
   */
  private fun cell(value: String): String =
    value.replace("\\", "\\\\").replace("|", "\\|").replace("`", "\\`")

  /** A value shown as a code span, with its content escaped. */
  private fun code(value: String): String = "`${cell(value)}`"

  /** A value shown as plain text, with its content escaped. */
  private fun text(value: String): String = cell(value)

  /**
   * Failure text is arbitrary — a stack frame, a classpath, a message with backticks in it — so it
   * goes in a fence rather than a table cell, where a stray `|` would shear the row. Any fence
   * marker inside the text is neutralised so it cannot close the block early and let the rest of
   * the failure render as markdown.
   */
  private fun fence(lines: List<String>): String =
    "```\n" + lines.joinToString("\n") { it.replace("```", "'''") } + "\n```\n"

  /** Compact uptime — `3d 4h`, `12m`, `45s`. Two units is as much as a bug report needs. */
  fun duration(seconds: Long): String {
    if (seconds < 60) return "${seconds}s"
    val minutes = seconds / 60
    if (minutes < 60) return "${minutes}m"
    val hours = minutes / 60
    if (hours < 24) return if (minutes % 60 == 0L) "${hours}h" else "${hours}h ${minutes % 60}m"
    val days = hours / 24
    return if (hours % 24 == 0L) "${days}d" else "${days}d ${hours % 24}h"
  }

  /**
   * The visitor's own page, as a path this server can safely echo into a report and a link.
   *
   * The value arrives from the browser (the footer form's hidden `from` input, filled by the page
   * script from `location`), so it is untrusted input that ends up in HTML, in a link, and in an
   * issue body. Accepted only as a **same-origin absolute path**: it must start with a single `/`,
   * must not start with `//` (a protocol-relative URL, which is a different origin wearing a path's
   * shape), must carry no scheme, no fragment and no control characters, and must be short.
   * Anything else yields null and the report simply has no page section — a report missing a row is
   * a far better outcome than one carrying an attacker-chosen link.
   *
   * The token is stripped for the same reason it is stripped everywhere else in a report: the token
   * is the capability to drive the server, and an issue body is public.
   */
  fun sanitizeFrom(raw: String?): String? {
    val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (value.length > MAX_FROM_LENGTH) return null
    if (!value.startsWith("/") || value.startsWith("//")) return null
    if (value.any { it.isISOControl() }) return null
    if (value.contains('#') || value.contains('\\')) return null
    return ServeIssueReport.withoutToken(value)
  }

  /**
   * A URL long enough for any real viewer link (every override the viewer offers, plus the preview
   * id) and short enough that a hostile one cannot pad an issue body.
   */
  private const val MAX_FROM_LENGTH = 2048

  /**
   * What a served path says about itself: which system it belongs to, and which preview it shows.
   *
   * [previewSegment] is left **percent-encoded**, exactly as it appeared in the path. Decoding it
   * here would repeat the bug the usage route documents — `URLDecoder` turns a legitimately escaped
   * `%2B` in a preview id into a space and `%2F` into a separator, so an id that renders a viewer
   * page perfectly stops resolving. The caller matches it against the session's own preview ids
   * re-encoded the same way, which needs no decoder and cannot round-trip wrong.
   */
  data class PageRef(val system: String? = null, val previewSegment: String? = null)

  /**
   * Split a sanitised in-server path ([sanitizeFrom]) into its system and preview.
   *
   * Mirrors the route table's two shapes — `/p/{name}` and `/compare/{name}` at the root, and the
   * same pair under a `/{system}` prefix — and recognises a bare `/{system}/` landing. Anything
   * else (the front door, `/status`, a design page) yields an empty ref, which is the honest
   * answer: those pages belong to no preview, and the report simply omits the rows.
   */
  fun parsePath(path: String?): PageRef {
    val clean = path?.substringBefore('?')?.trim()?.takeIf { it.isNotEmpty() } ?: return PageRef()
    val segments = clean.split('/').filter { it.isNotEmpty() }
    return when {
      segments.isEmpty() -> PageRef()
      // `/p/<preview>` · `/compare/<preview>` — the rooted single-session form.
      segments.size == 2 && segments[0] in PREVIEW_SEGMENTS -> PageRef(previewSegment = segments[1])
      // `/<system>/p/<preview>` · `/<system>/compare/<preview>`.
      segments.size == 3 && segments[1] in PREVIEW_SEGMENTS ->
        PageRef(system = segments[0], previewSegment = segments[2])
      // A catalog landing. Only when the single segment isn't one of the server's own top-level
      // routes, which are pages of the box rather than of a system.
      segments.size == 1 && segments[0] !in SERVER_SEGMENTS -> PageRef(system = segments[0])
      // `/<system>/<anything-else>` — a design page, the parity dashboard, a format comparison.
      // The system still holds; the preview does not.
      segments.size >= 2 && segments[0] !in SERVER_SEGMENTS -> PageRef(system = segments[0])
      else -> PageRef()
    }
  }

  /**
   * What the viewer was **showing** when the report was filed, read out of the reporter's own query
   * — `design spec (triptych)`, `motion`, `exploded layers`, `Remote Compose (wasm player)`.
   *
   * The point of this, and of issue #4261: every one of those views is composed in the BROWSER out
   * of artefacts the server serves separately — a render plus an imported reference plus a diff, a
   * frame sequence, a stack of layers — so none of them has a URL, and the `/render` PNG the report
   * embeds is not what the reporter was looking at. Naming the view is the one thing the server can
   * do about that from the query alone, and it is worth doing on its own: "the triptych's middle
   * panel is blank" filed against a picture of a perfectly good button is a report a triager cannot
   * even parse.
   *
   * Strictly an **allowlist of values this server's own viewer writes**. The query arrives from the
   * browser via `from` and lands in a public issue body, so an unrecognised `mode` is dropped
   * rather than echoed — the same rule the browser block's `?scheme=` follows in `bugReport.ts`,
   * and for the same reason: there are finitely many real answers, and anything else is not a
   * mangled view but a value that was never a view at all.
   *
   * Null — the plain render lane, and every page that is not a viewer — adds no row at all, rather
   * than a "View | default" that says nothing.
   */
  fun viewLabel(from: String?): String? {
    val params = queryParams(from)
    val explode = params["exploded"]?.lowercase()?.let { it in EXPLODE_ON } == true
    val lane = LANES[params["mode"]?.lowercase()]
    // The spec lane's four views are one lane with four presentations, and the difference between
    // them is exactly what a spec-lane bug is usually about — so the view qualifies the lane rather
    // than replacing it. A spec-lane URL that names NO view is not silent about which one was up:
    // it is the lane's default ([ServeWeb.SPEC_DEFAULT_VIEW]), which the viewer leaves out of the
    // query precisely because it needs no parameter. So resolve it rather than dropping the row's
    // most useful half — before #4376 that default was the plain reference and naming it added
    // nothing; now it is the triptych, and "design spec" alone would leave a triager guessing.
    val spec =
      if (params["mode"]?.lowercase() != "spec") null
      else
        SPEC_VIEW_LABELS[
          params["specView"]?.lowercase()?.takeIf { it in SPEC_VIEW_LABELS }
            ?: ServeWeb.SPEC_DEFAULT_VIEW]
    val laneLabel = lane?.let { if (spec == null) it else "$it ($spec)" }
    return when {
      laneLabel != null && explode -> "$laneLabel, exploded layers"
      laneLabel != null -> laneLabel
      explode -> "exploded layers"
      else -> null
    }
  }

  /**
   * The reporter's query as a map, last value winning — which is what a browser's own
   * `URLSearchParams.get` returns, and this string was written by one.
   *
   * Values are left percent-encoded on purpose. Every value this reads is matched against a fixed
   * allowlist of ASCII tokens the viewer writes, so decoding could only ever turn a value that is
   * not on the list into a different value that is not on the list — while `URLDecoder` would also
   * turn a `+` into a space, which is the decoding bug [PageRef.previewSegment] documents.
   */
  private fun queryParams(from: String?): Map<String, String> {
    val query = from?.substringAfter('?', missingDelimiterValue = "").orEmpty()
    if (query.isEmpty()) return emptyMap()
    return query
      .split('&')
      .filter { it.isNotEmpty() }
      .associate { pair ->
        pair.substringBefore('=') to pair.substringAfter('=', missingDelimiterValue = "")
      }
  }

  /** `?mode=` values the viewer writes, as a reader of the issue would say them. */
  private val LANES =
    mapOf(
      // `png` is the static render lane — the default, and what the embedded PNG already is.
      "spec" to "design spec",
      "source" to "source view",
      "motion" to "motion playback",
      "rc" to "Remote Compose (canvas player)",
      "rc-wasm" to "Remote Compose (wasm player)",
      "wasm" to "wasm lane",
      "live" to "live daemon lane",
    )

  /**
   * The spec lane's presentations, as a report names them — mirrors `spec/views.ts`, whose default
   * is `triptych`. Only `spec` is renamed: "design spec (spec)" reads as a stutter or a typo where
   * what it means is the imported reference on the stage by itself.
   */
  private val SPEC_VIEW_LABELS =
    mapOf(
      "spec" to "reference only",
      "diff" to "diff",
      "triptych" to "triptych",
      "slider" to "slider",
    )

  /** Truthy `?exploded=` spellings — mirrors `explodeParamOn` in `viewer/renderQuery.ts`. */
  private val EXPLODE_ON = setOf("", "1", "true", "on", "yes")

  /** Route prefixes whose next segment is a preview id. */
  private val PREVIEW_SEGMENTS = setOf("p", "compare")

  /**
   * Top-level paths that belong to the **server**, not to a design system, so a leading segment
   * matching one of these never names a catalog. Deliberately a small list of the routes a visitor
   * can actually be looking at when they press the affordance — a catalog whose system id collided
   * with one of these could not be served at those URLs in the first place.
   */
  private val SERVER_SEGMENTS =
    setOf(
      "status",
      "status.json",
      "version",
      "healthz",
      "readyz",
      "assets",
      "docs",
      "playground",
      "report-bug",
      "p",
      "compare",
      // Query-mode routes: `/pages/foo?session=…`, `/parity?session=…`. These ARE catalog pages,
      // but the catalog is named by `?session=`, not by the first segment — reading `pages` or
      // `parity` as a system id would invent a design system that does not exist and file the
      // report against it. Which catalog they belong to is recovered from the explicit session.
      "pages",
      "parity",
      // `/motion?session=…` — the motion browser, and `/motion/<id>.apng` under it. Same reason
      // as the two above: the catalog is named by `?session=`, so reading `motion` as a system id
      // would file the report against a design system that does not exist.
      "motion",
      "usage",
      "render",
      "reference",
      "hero",
      "api",
      "admin",
      "wasm",
    )
}
