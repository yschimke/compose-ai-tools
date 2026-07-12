package ee.schimke.composeai.cli.serve

/**
 * In-code HTML/CSS/JS for the `compose-preview serve` web surface. Generated as Kotlin raw strings
 * (the house style — see [ee.schimke.composeai.cli.WebEmbed]) so the token + preview ids inject
 * straight into the pages with no build step. Two surfaces: a landing page listing the module's
 * previews, and a viewer page with override controls that re-point an `<img>` at `/render`.
 *
 * The viewer is written against a `data-mode` seam (snapshot today) so the future in-browser CMP
 * (`live`) transport mounts into the same page rather than a parallel one.
 */
object ServeWeb {

  private val STYLE =
    """
    :root { color-scheme: light dark; }
    * { box-sizing: border-box; }
    body { margin: 0; padding: 24px; font-family: system-ui, -apple-system, Segoe UI, Roboto, sans-serif;
      color: #1b1b1f; background: #fafafb; }
    a { color: #5b5bd6; text-decoration: none; }
    a:hover { text-decoration: underline; }
    .cp-head { margin: 0 0 4px; font-size: 1.2rem; font-weight: 600; }
    .cp-sub { margin: 0 0 20px; font-size: 0.82rem; color: #6b6b70; }
    .cp-about { margin: 0 0 24px; padding: 14px 16px; border: 1px solid #e3e3e8; border-radius: 10px;
      background: #fff; max-width: 720px; }
    .cp-about-title { margin: 0 0 6px; font-size: 0.95rem; font-weight: 600; }
    .cp-about-body { margin: 0 0 8px; font-size: 0.84rem; line-height: 1.45; color: #45454c; }
    .cp-about-body code { font-size: 0.8rem; padding: 0 3px; border-radius: 4px; background: #f0f0f3; }
    .cp-about-links { margin: 0; font-size: 0.8rem; color: #6b6b70; }
    .cp-systems { margin: 0 0 20px; display: flex; flex-wrap: wrap; align-items: center; gap: 10px;
      font-size: 0.85rem; }
    .cp-systems-label { font-weight: 600; color: #6b6b70; }
    .cp-systems a, .cp-systems-cur { padding: 3px 10px; border-radius: 999px; border: 1px solid #d7d7de; }
    .cp-systems a { background: #fff; }
    .cp-systems-cur { background: #ececff; border-color: #c4c4f5; color: #3a3a8a; font-weight: 600; }
    .cp-toolbar { margin: 0 0 16px; }
    .cp-searchbar { margin: 0 0 16px; display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
    .cp-search { flex: 1 1 260px; max-width: 420px; padding: 7px 12px; font: inherit; font-size: 0.85rem;
      border: 1px solid #d7d7de; border-radius: 999px; background: #fff; color: inherit; }
    .cp-search:focus { outline: 2px solid #c4c4f5; outline-offset: 1px; }
    .cp-count { font-size: 0.78rem; color: #6b6b70; }
    .cp-empty { margin: 12px 0 0; font-size: 0.85rem; color: #6b6b70; }
    .cp-empty[hidden] { display: none; }
    .cp-theme { display: inline-flex; border: 1px solid #d7d7de; border-radius: 999px; overflow: hidden; }
    .cp-theme-btn { border: 0; background: #fff; color: #6b6b70; font: inherit; font-size: 0.78rem;
      padding: 3px 14px; cursor: pointer; }
    .cp-theme-btn[aria-pressed="true"] { background: #ececff; color: #3a3a8a; font-weight: 600; }
    .cp-badge { display: inline-block; margin-left: 8px; padding: 1px 8px; border-radius: 999px;
      font-size: 0.7rem; font-weight: 600; vertical-align: middle; white-space: nowrap; }
    .cp-badge--trusted { background: #e7f4ea; color: #1e7a34; border: 1px solid #b6e0c2; }
    .cp-badge--unverified { background: #fdf0e3; color: #8a5300; border: 1px solid #f0d3a8; }
    .cp-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(200px, 1fr)); gap: 16px; }
    .cp-syslist { grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); }
    .cp-syslist .cp-imgwrap { min-height: 120px; }
    .cp-sys-title { font-size: 0.98rem; font-weight: 600; }
    .cp-sys-desc { margin-top: 4px; font-size: 0.76rem; color: #6b6b70; line-height: 1.4; }
    .cp-sys-foot { margin-top: 8px; font-size: 0.74rem; color: #6b6b70; }
    .cp-sys-noimg { font-size: 0.78rem; color: #a0a0a8; }
    .cp-card { border: 1px solid #e3e3e8; border-radius: 10px; overflow: hidden; background: #fff;
      display: block; color: inherit; }
    .cp-card[hidden] { display: none; }
    .cp-imgwrap { display: flex; align-items: center; justify-content: center; min-height: 88px;
      background: #fff; padding: 12px; }
    .cp-imgwrap img { max-width: 100%; max-height: 240px; height: auto; display: block; }
    /* Sticker backing: the preview server shows components on a solid surface by DEFAULT, so a
       transparent sticker reads like a real component instead of washing out. The header's
       Background/Transparent toggle flips the whole page to a checkerboard (html.cp-bg-transparent)
       to inspect the raw transparent PNG; the choice persists per-visitor in localStorage['cp-bg']. */
    html.cp-bg-transparent .cp-imgwrap, html.cp-bg-transparent .cp-stage {
      background: repeating-conic-gradient(#f4f4f6 0% 25%, #fff 0% 50%) 50% / 16px 16px; }
    /* Back each card by its OWN render theme: a light-rendered sticker always sits on a light
       surface and a dark-rendered one on a dark surface, independent of the browser's color scheme
       or the explicitly-selected catalog theme. Without this a transparent light sticker (dark
       strokes) shown on the dark browser backing — or dark on light — washes to near-invisible.
       Solid mode only; the Transparent toggle's checkerboard still wins via cp-bg-transparent. */
    html:not(.cp-bg-transparent) .cp-card[data-card-theme="light"] .cp-imgwrap { background: #fff; }
    html:not(.cp-bg-transparent) .cp-card[data-card-theme="dark"] .cp-imgwrap { background: #1d1d20; }
    .cp-bg { display: inline-flex; border: 1px solid #d7d7de; border-radius: 999px; overflow: hidden; }
    .cp-bg-btn { border: 0; background: #fff; color: #6b6b70; font: inherit; font-size: 0.78rem;
      padding: 3px 14px; cursor: pointer; }
    .cp-bg-btn[aria-pressed="true"] { background: #ececff; color: #3a3a8a; font-weight: 600; }
    .cp-meta { padding: 8px 10px; font-size: 0.82rem; }
    .cp-label { font-weight: 500; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .cp-id { color: #6b6b70; font-size: 0.7rem; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .cp-viewer { display: flex; gap: 24px; flex-wrap: wrap; align-items: flex-start; }
    .cp-stage { position: relative; flex: 1 1 360px; min-width: 280px; border: 1px solid #e3e3e8;
      border-radius: 10px;
      background: #fff; padding: 12px;
      display: flex; align-items: center; justify-content: center; min-height: 220px; }
    .cp-stage img, .cp-stage canvas { max-width: 100%; height: auto; }
    .cp-backend { position: absolute; top: 8px; right: 8px; font-size: 0.62rem; font-weight: 600;
      letter-spacing: 0.02em; padding: 2px 8px; border-radius: 999px; background: rgba(20,20,22,0.72);
      color: #fff; pointer-events: none; }
    .cp-stage iframe { position: absolute; border: 0; background: transparent; opacity: 0;
      transition: opacity 0.15s ease; pointer-events: none; }
    .cp-stage iframe.cp-wasm-live { opacity: 1; pointer-events: auto; }
    /* Live daemon frames paint into the SAME absolute overlay box the Wasm iframe uses, pinned to
       the snapshot img's slot — so the stage is one fixed box every transport fills and toggling a
       mode never resizes or shifts it (the img keeps its layout slot underneath via visibility). */
    .cp-stage canvas.cp-canvas-live { position: absolute; max-width: none; }
    .cp-live-row { flex-direction: row !important; align-items: center; gap: 6px !important; }
    .cp-modes { display: flex; flex-direction: row; flex-wrap: wrap; gap: 6px 14px;
      font-size: 0.8rem; }
    .cp-controls { flex: 0 0 240px; display: flex; flex-direction: column; gap: 14px; }
    .cp-controls label { display: flex; flex-direction: column; gap: 4px; font-size: 0.8rem; }
    .cp-controls input, .cp-controls select { padding: 5px 6px; font-size: 0.85rem; }
    .cp-status { font-size: 0.75rem; color: #6b6b70; min-height: 1em; }
    /* Visible mode-activation error overlay: shown when a lane (Live stream, Wasm
       app, or a /render) fails to activate, so a failed mode reads as an error
       instead of a silent stale frame. Centered over the stage; [hidden] hides it. */
    .cp-error { position: absolute; left: 50%; top: 50%; transform: translate(-50%, -50%);
      max-width: 80%; z-index: 3; padding: 10px 14px; border-radius: 8px;
      font-size: 0.8rem; line-height: 1.4; text-align: center; color: #8a1c1c;
      background: #fdecec; border: 1px solid #f3b6b6; box-shadow: 0 1px 4px rgba(0,0,0,0.12); }
    .cp-error[hidden] { display: none; }
    .cp-note { font-size: 0.75rem; color: #6b6b70; line-height: 1.4; padding: 8px 10px;
      border-radius: 8px; background: #f4f4f6; }
    .cp-controls label:has(:disabled) { opacity: 0.55; }
    .cp-size { display: flex; flex-direction: column; gap: 8px; }
    /* :not([hidden]) so the mode toggle's `hidden` attribute still wins over this display rule. */
    .cp-size-row:not([hidden]) { display: flex; gap: 8px; }
    .cp-size-row label { flex: 1; }
    .cp-size-row input { width: 100%; box-sizing: border-box; }
    .cp-knobs { border-top: 1px solid #e3e3e8; padding-top: 12px; display: flex; flex-direction: column; gap: 8px; }
    .cp-knobs-head { font-size: 0.72rem; color: #6b6b70; }
    .cp-overlays { border-top: 1px solid #e3e3e8; padding-top: 12px; display: flex; flex-direction: column; gap: 8px; }
    .cp-overlays-head { font-size: 0.72rem; color: #6b6b70; }
    .cp-knobs input:disabled { opacity: 0.7; }
    .cp-links { border-top: 1px solid #e3e3e8; padding-top: 12px; display: flex; flex-direction: column; gap: 8px; }
    .cp-link-row { display: flex; align-items: center; gap: 6px; }
    .cp-link-kind { font-size: 0.72rem; font-weight: 600; color: #6b6b70; width: 30px; flex: none; }
    .cp-url { flex: 1; min-width: 0; font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
      font-size: 0.72rem; padding: 4px 6px; border: 1px solid #d7d7de; border-radius: 6px;
      background: #fff; color: #1b1b1f; }
    .cp-copy, .cp-dl { font-size: 0.72rem; padding: 4px 8px; border-radius: 6px; border: 1px solid #d7d7de;
      background: #fff; color: #5b5bd6; cursor: pointer; text-decoration: none; flex: none; }
    .cp-copy:hover, .cp-dl:hover { background: #f0f0f3; }
    /* Drawer toggles + the two collapsible drawers (left component nav, right overrides). The open
       state is a class on .cp-viewer — cp-controls-open (default on) and cp-nav-open (default off) —
       so a drawer hides purely in CSS and the toggle JS just flips the class + aria-expanded. */
    .cp-viewer-bar { display: flex; flex-wrap: wrap; gap: 8px; margin: 0 0 14px; }
    .cp-drawer-toggle { display: inline-flex; align-items: center; gap: 6px; font: inherit;
      font-size: 0.78rem; padding: 5px 12px; border-radius: 999px; border: 1px solid #d7d7de;
      background: #fff; color: #45454c; cursor: pointer; }
    .cp-drawer-toggle:hover { background: #f0f0f3; }
    .cp-drawer-toggle[aria-expanded="true"] { background: #ececff; border-color: #c4c4f5;
      color: #3a3a8a; font-weight: 600; }
    .cp-nav { flex: 0 0 220px; align-self: stretch; max-height: 72vh; overflow: auto;
      border: 1px solid #e3e3e8; border-radius: 10px; background: #fff; padding: 12px;
      display: flex; flex-direction: column; gap: 10px; }
    .cp-nav-head { display: flex; align-items: center; justify-content: space-between;
      font-size: 0.8rem; font-weight: 600; color: #45454c; }
    .cp-nav-close { border: 0; background: none; font: inherit; font-size: 1.1rem; line-height: 1;
      color: #6b6b70; cursor: pointer; padding: 0 2px; }
    .cp-nav-search { padding: 6px 10px; font: inherit; font-size: 0.8rem; border: 1px solid #d7d7de;
      border-radius: 8px; background: #fff; color: inherit; }
    .cp-nav-list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 2px; }
    .cp-nav-item { display: flex; align-items: center; gap: 8px; padding: 4px 8px; border-radius: 6px;
      font-size: 0.8rem; color: inherit; }
    .cp-nav-thumb { flex: none; width: 28px; height: 28px; object-fit: contain; border-radius: 4px;
      background: repeating-conic-gradient(#f4f4f6 0% 25%, #fff 0% 50%) 50% / 8px 8px; }
    .cp-nav-name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .cp-nav-item:hover { background: #f0f0f3; text-decoration: none; }
    .cp-nav-item[aria-current="page"] { background: #ececff; color: #3a3a8a; font-weight: 600; }
    .cp-nav-empty { font-size: 0.78rem; color: #6b6b70; }
    .cp-nav-empty[hidden] { display: none; }
    .cp-viewer:not(.cp-nav-open) .cp-nav { display: none; }
    .cp-viewer:not(.cp-controls-open) .cp-controls { display: none; }
    @media (prefers-color-scheme: dark) {
      body { color: #e6e6e9; background: #161618; }
      .cp-sub, .cp-id, .cp-status, .cp-about-links, .cp-sys-desc, .cp-sys-foot { color: #a0a0a8; }
      .cp-card, .cp-stage, .cp-knobs, .cp-links, .cp-about { border-color: #34343a; }
      .cp-url { background: #1d1d20; color: #e6e6e9; border-color: #34343a; }
      .cp-copy, .cp-dl { background: #1d1d20; border-color: #34343a; }
      .cp-copy:hover, .cp-dl:hover { background: #26262b; }
      .cp-card, .cp-about { background: #1d1d20; }
      .cp-about-body { color: #c9c9d0; }
      .cp-about-body code { background: #2a2a30; }
      .cp-systems-label { color: #a0a0a8; }
      .cp-systems a, .cp-systems-cur { border-color: #34343a; }
      .cp-systems a { background: #1d1d20; }
      .cp-systems-cur { background: #26264a; border-color: #45458a; color: #c9c9ff; }
      .cp-search { border-color: #34343a; background: #1d1d20; }
      .cp-count, .cp-empty { color: #a0a0a8; }
      .cp-theme { border-color: #34343a; }
      .cp-theme-btn { background: #1d1d20; color: #a0a0a8; }
      .cp-theme-btn[aria-pressed="true"] { background: #26264a; color: #c9c9ff; }
      .cp-note { background: #26262b; color: #a0a0a8; }
      .cp-imgwrap, .cp-stage { background: #1d1d20; }
      html.cp-bg-transparent .cp-imgwrap, html.cp-bg-transparent .cp-stage {
        background: repeating-conic-gradient(#26262b 0% 25%, #1d1d20 0% 50%) 50% / 16px 16px; }
      .cp-bg { border-color: #34343a; }
      .cp-bg-btn { background: #1d1d20; color: #a0a0a8; }
      .cp-bg-btn[aria-pressed="true"] { background: #26264a; color: #c9c9ff; }
      .cp-badge--trusted { background: #14361f; color: #6cd98a; border-color: #2c6b40; }
      .cp-badge--unverified { background: #3a2a12; color: #e6b067; border-color: #6b4f24; }
      .cp-drawer-toggle { background: #1d1d20; border-color: #34343a; color: #c9c9d0; }
      .cp-drawer-toggle:hover { background: #26262b; }
      .cp-drawer-toggle[aria-expanded="true"] { background: #26264a; border-color: #45458a; color: #c9c9ff; }
      .cp-nav { background: #1d1d20; border-color: #34343a; }
      .cp-nav-head { color: #c9c9d0; }
      .cp-nav-search { background: #1d1d20; border-color: #34343a; }
      .cp-nav-item:hover { background: #26262b; }
      .cp-nav-item[aria-current="page"] { background: #26264a; color: #c9c9ff; }
      .cp-nav-thumb { background: repeating-conic-gradient(#26262b 0% 25%, #1d1d20 0% 50%) 50% / 8px 8px; }
      .cp-nav-empty { color: #a0a0a8; }
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
   * Public-mode "about" intro: a short, static explanation of what the host is and its safety
   * model, shown only when [landingPage] is asked for [isPublic]. Deliberately carries **no**
   * version string (that lives at `/version`) so a release never churns the committed HTML golden.
   * Links out to the source repo and the machine-readable `/version`.
   */
  private fun aboutSection(): String =
    """
    <section class="cp-about">
      <p class="cp-about-title">compose-preview · public preview server</p>
      <p class="cp-about-body">Browse rendered Compose &amp; Compose&nbsp;Multiplatform design
        catalogs live. CMP components run <strong>in your browser</strong> (Kotlin/Wasm, sandboxed);
        everything else is served as pre-rendered snapshots. The server never re-runs untrusted code
        — catalogs are trusted via signature or their published <code>design-artifacts</code> branch,
        and anything unverified is badged.</p>
      <p class="cp-about-links">
        <a href="https://github.com/yschimke/compose-ai-tools">source</a> ·
        <a href="/version">/version</a>
      </p>
    </section>
    """
      .trimIndent()

  /**
   * Design-system nav: a pill row linking each served `--catalogs` system to its canonical
   * `/<system>/` landing, so the public front door lists the catalogs instead of hiding them behind
   * the query. The current session (when it is one of the catalogs) renders as a non-link,
   * current-marked pill. Empty [catalogs] ⇒ no row.
   */
  private fun catalogNav(
    catalogs: List<String>,
    token: String,
    sessionId: String?,
    isPublic: Boolean,
  ): String {
    if (catalogs.isEmpty()) return ""
    // Public routes are open, so a nav pill needs no token; token-gated boxes keep it.
    val suffix = querySuffix(if (isPublic) "" else "token=" + WebEscaping.urlEncodeSegment(token))
    val links =
      catalogs.joinToString("\n") { sys ->
        val name = WebEscaping.htmlEscape(sys)
        if (sys == sessionId) {
          "<span class=\"cp-systems-cur\" aria-current=\"page\">$name</span>"
        } else {
          "<a href=\"/${WebEscaping.urlEncodeSegment(sys)}/$suffix\">$name</a>"
        }
      }
    return """
      <nav class="cp-systems" aria-label="Design systems">
        <span class="cp-systems-label">Design systems</span>
        $links
      </nav>
      """
      .trimIndent()
  }

  /**
   * The theme axis (`light`/`dark`) baked into a flattened catalog id, or null if it carries none.
   */
  private fun cardTheme(id: String): String? =
    id.split("__").drop(1).lastOrNull { it == "light" || it == "dark" }

  /**
   * The sticky light/dark control for the catalog header. Persists to `localStorage['cp-theme']`
   * (shared with the viewer's Theme select) and filters the card grid to the chosen theme's
   * variants. Purely client-side — the server emits every card tagged with `data-card-theme`, and
   * [catalogThemeScript] does the hiding, so a no-JS client still sees the full catalog.
   */
  private fun themeToggleHtml(): String =
    """
    <div class="cp-toolbar">
      <span class="cp-theme" role="group" aria-label="Preview theme">
        <button type="button" class="cp-theme-btn" data-theme-choice="light">Light</button>
        <button type="button" class="cp-theme-btn" data-theme-choice="dark">Dark</button>
      </span>
    </div>
    """
      .trimIndent()

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
      <span class="cp-bg" role="group" aria-label="Sticker background">
        <button type="button" class="cp-bg-btn" data-bg-choice="on">Background</button>
        <button type="button" class="cp-bg-btn" data-bg-choice="off">Transparent</button>
      </span>
    </div>
    """
      .trimIndent()

  /**
   * Combined landing-grid filter: owns every `.cp-card`'s visibility from two independent inputs —
   * the search box (matches the card's label + id, case-insensitive) and, when the catalog carries
   * per-theme variants, the sticky light/dark toggle. A card is shown only when it satisfies BOTH,
   * so the two filters compose instead of fighting over `hidden`. Theme state persists to the
   * shared `localStorage['cp-theme']` key (round-tripped with the viewer's Theme select); the
   * search text is ephemeral. Fully client-side progressive enhancement — a no-JS client sees the
   * whole grid.
   */
  private fun catalogFilterScript(hasThemes: Boolean): String {
    val themeInit =
      if (hasThemes)
        """
        var stored = null;
        try { stored = localStorage.getItem("cp-theme"); } catch (e) {}
        var theme = (stored === "light" || stored === "dark") ? stored
          : (window.matchMedia && window.matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light");
        var themeBtns = document.querySelectorAll(".cp-theme-btn");
        """
          .trimIndent()
      else ""
    val reflectTheme =
      if (hasThemes)
        """themeBtns.forEach(function (b) {
          b.setAttribute("aria-pressed", b.getAttribute("data-theme-choice") === theme ? "true" : "false");
        });"""
      else ""
    val themeOk = if (hasThemes) "(!ct || ct === theme)" else "true"
    val themeWiring =
      if (hasThemes)
        """themeBtns.forEach(function (b) {
        b.addEventListener("click", function () {
          theme = b.getAttribute("data-theme-choice");
          try { localStorage.setItem("cp-theme", theme); } catch (e) {}
          apply();
        });
      });"""
      else ""
    return """
    (function () {
      var cards = document.querySelectorAll(".cp-card");
      var input = document.getElementById("cp-search");
      var count = document.getElementById("cp-count");
      var empty = document.getElementById("cp-empty");
      var total = cards.length;
      $themeInit
      function apply() {
        $reflectTheme
        var q = input ? input.value.trim().toLowerCase() : "";
        var shown = 0;
        cards.forEach(function (c) {
          var ct = c.getAttribute("data-card-theme");
          var lab = c.querySelector(".cp-label");
          var idn = c.querySelector(".cp-id");
          var hay = ((lab ? lab.textContent : "") + " " + (idn ? idn.textContent : "")).toLowerCase();
          var searchOk = q === "" || hay.indexOf(q) !== -1;
          var visible = $themeOk && searchOk;
          c.hidden = !visible;
          if (visible) shown++;
        });
        if (count) count.textContent = q === "" ? "" : (shown + " of " + total);
        if (empty) empty.hidden = shown !== 0;
      }
      if (input) input.addEventListener("input", apply);
      $themeWiring
      // Background/Transparent toggle: flips the whole page between a solid surface (default) and the
      // transparent-checkerboard backing, persisting the choice. Independent of theme/search filters.
      var bgBtns = document.querySelectorAll(".cp-bg-btn");
      function reflectBg() {
        var off = document.documentElement.classList.contains("cp-bg-transparent");
        bgBtns.forEach(function (b) {
          b.setAttribute("aria-pressed",
            b.getAttribute("data-bg-choice") === (off ? "off" : "on") ? "true" : "false");
        });
      }
      bgBtns.forEach(function (b) {
        b.addEventListener("click", function () {
          var choice = b.getAttribute("data-bg-choice");
          document.documentElement.classList.toggle("cp-bg-transparent", choice === "off");
          try { localStorage.setItem("cp-bg", choice); } catch (e) {}
          reflectBg();
        });
      });
      reflectBg();
      apply();
    })();
    """
      .trimIndent()
  }

  /**
   * Viewer theme-sticky script: when the Theme select is set to light/dark, write it to the shared
   * `localStorage['cp-theme']` so the catalog remembers the last theme the visitor viewed a
   * component in (the other half of the catalog toggle's stickiness).
   */
  private fun viewerThemeStickyScript(): String =
    """
    (function () {
      var el = document.getElementById("cp-uiMode");
      if (!el) return;
      // Inherit the catalog's sticky theme on the first render — but ONLY for a theme-less preview
      // (id carries no __light/__dark segment), which would otherwise open at (default). A themed
      // variant's theme is explicit in its deep link, so it opens on its baked (instant) pixels and
      // is not overridden by a remembered theme — seeding it would force a daemon re-render on a
      // fresh deep link, defeating baked-until-changed. Runs before viewerScript()'s initial render.
      var root = document.querySelector(".cp-viewer");
      var pid = (root && root.getAttribute("data-preview-id")) || "";
      var themed = pid.split("__").some(function (s) { return s === "light" || s === "dark"; });
      try {
        var stored = localStorage.getItem("cp-theme");
        if (!themed && !el.value && (stored === "light" || stored === "dark")) el.value = stored;
      } catch (e) {}
      // Round-trip: a Theme change writes the shared key so the catalog remembers it.
      el.addEventListener("change", function () {
        if (el.value === "light" || el.value === "dark") {
          try { localStorage.setItem("cp-theme", el.value); } catch (e) {}
        }
      });
    })();
    """
      .trimIndent()

  /**
   * Backend-provenance badge: a small corner label naming the tier that produced the pixels now on
   * the stage, so a viewer can tell an in-browser CMP render from a baked snapshot. Reads the
   * viewer's `data-mode` (kept in sync by the transport toggles) — `CMP-WASM` for the Wasm app
   * (always that), `SVG` for the vector snapshot lane, else the server-supplied `data-live-backend`
   * / `data-snapshot-backend` label, so the daemon's actual platform (desktop/JVM or Android) and
   * the snapshot renderer stay accurate.
   */
  private fun backendBadgeScript(): String =
    """
    (function () {
      var root = document.querySelector(".cp-viewer");
      var badge = document.getElementById("cp-backend");
      if (!root || !badge) return;
      function label(mode) {
        if (mode === "wasm") return "CMP-WASM";
        if (mode === "live") return root.getAttribute("data-live-backend") || "Live";
        if (mode === "svg") return "SVG";
        return root.getAttribute("data-snapshot-backend") || "Snapshot";
      }
      function refresh() { badge.textContent = label(root.getAttribute("data-mode")); }
      new MutationObserver(refresh).observe(root, { attributes: true, attributeFilter: ["data-mode"] });
      refresh();
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
    val heroPreviewId: String?,
  )

  /**
   * The public preview server's **front door**: an index of the design systems it publishes, each a
   * card carrying a meaningful preview, the system's title + library, its trust badge, and a link
   * to its `/<system>/` catalog. This replaces showing an arbitrary default module's previews at
   * `/` (the point of `preview.coo.ee` is the catalogs, so the landing lists them rather than
   * hiding them behind a nav pill). Non-catalog `serve` (no `--catalogs`) keeps the plain
   * [landingPage].
   */
  fun homeIndexPage(systems: List<HomeSystem>, token: String, isPublic: Boolean = false): String {
    val about = if (isPublic) aboutSection() + "\n" else ""
    // Public routes are open — no token param on the cards; a token-gated box keeps it.
    val suffix = querySuffix(if (isPublic) "" else "token=" + WebEscaping.urlEncodeSegment(token))
    val body =
      if (systems.isEmpty()) {
        "<p class=\"cp-sub\">No design systems are configured on this server.</p>"
      } else {
        val cards =
          systems.joinToString("\n") { s ->
            val sysSeg = WebEscaping.urlEncodeSegment(s.system)
            val title = WebEscaping.htmlEscape(s.title)
            val sysId = WebEscaping.htmlEscape(s.system)
            val img =
              if (s.heroPreviewId != null) {
                val idSeg = WebEscaping.urlEncodeSegment(s.heroPreviewId)
                "<img loading=\"lazy\" alt=\"$title preview\" src=\"/$sysSeg/render/$idSeg.png$suffix\">"
              } else {
                "<span class=\"cp-sys-noimg\">no preview</span>"
              }
            val desc =
              s.subtitle
                ?.takeIf { it.isNotBlank() }
                ?.let {
                  "\n            <div class=\"cp-sys-desc\">${WebEscaping.htmlEscape(it)}</div>"
                } ?: ""
            """
            <a class="cp-card cp-sys" href="/$sysSeg/$suffix">
              <div class="cp-imgwrap">$img</div>
              <div class="cp-meta">
                <div class="cp-sys-title">$title${compactTrustBadge(s.trust)}</div>
                <div class="cp-id">$sysId</div>$desc
                <div class="cp-sys-foot">${s.previewCount} preview(s)</div>
              </div>
            </a>
            """
              .trimIndent()
          }
        """
        <p class="cp-sub">${systems.size} design system(s) · pick one to browse its components and
          open a live, customisable preview.</p>
        <div class="cp-grid cp-syslist" id="cp-grid">
        $cards
        </div>
        """
          .trimIndent()
      }
    return document(
      title = "Design systems — compose-preview",
      body =
        """
        $about<p class="cp-head">Design systems</p>
        $body
        """
          .trimIndent(),
    )
  }

  /**
   * Pick a **meaningful** representative preview from a catalog's flattened ids for the home index
   * — one recognisable, default-state light render rather than an arbitrary (often alphabetically
   * first) edge case. Scores each id: light beats dark; a canonical button/filled hero is
   * preferred; disabled/error/pressed/… state variants are pushed down. Ties break on the id so the
   * choice is deterministic (stable goldens). Null when there are no previews.
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
    fun score(id: String): Int {
      val lower = id.lowercase()
      var s = 0
      if ("dark" in lower) s += 4
      demote.forEach { if (it in lower) s += 8 }
      if ("button" in lower) s -= 3
      if ("filled" in lower) s -= 2
      return s
    }
    return previews.map { it.id }.sortedWith(compareBy({ score(it) }, { it })).first()
  }

  /** Landing page: the module's preview list, each card linking to its viewer. */
  fun landingPage(
    moduleLabel: String,
    previews: List<ServePreview>,
    token: String,
    sessionId: String? = null,
    trust: String? = null,
    isPublic: Boolean = false,
    catalogs: List<String> = emptyList(),
    /**
     * URL prefix for this session's own links (`/<system>` when served under a path, empty for the
     * root-mounted default/legacy session). Card/render/zip links are prefixed with it and drop the
     * `&session=` param (the path carries the session). Empty ⇒ links are exactly as before.
     */
    basePath: String = "",
  ): String {
    val q = querySuffix(linkQuery(token, sessionId, basePath, isPublic))
    val cards =
      if (previews.isEmpty()) {
        "<p class=\"cp-sub\">No previews discovered in this module.</p>"
      } else {
        previews.joinToString("\n") { p ->
          val idSeg = WebEscaping.urlEncodeSegment(p.id)
          val label = WebEscaping.htmlEscape(p.label)
          val idText = WebEscaping.htmlEscape(p.id)
          val themeAttr = cardTheme(p.id)?.let { " data-card-theme=\"$it\"" } ?: ""
          """
          <a class="cp-card"$themeAttr href="$basePath/p/$idSeg$q">
            <div class="cp-imgwrap">
              <img loading="lazy" alt="$label" src="$basePath/render/$idSeg.png$q">
            </div>
            <div class="cp-meta">
              <div class="cp-label" title="$idText">$label</div>
              <div class="cp-id">$idText</div>
            </div>
          </a>
          """
            .trimIndent()
        }
      }
    val about = if (isPublic) aboutSection() + "\n" else ""
    val nav =
      if (catalogs.isNotEmpty()) catalogNav(catalogs, token, sessionId, isPublic) + "\n" else ""
    // The theme toggle only makes sense when the catalog carries per-theme variants to filter.
    val hasThemes = previews.any { cardTheme(it.id) != null }
    val themeToggle = if (hasThemes) themeToggleHtml() + "\n" else ""
    // Search + empty-state + the combined filter script are shown whenever there are previews to
    // filter, independent of the theme axis.
    val hasPreviews = previews.isNotEmpty()
    val searchBox = if (hasPreviews) searchBoxHtml(previews.size) + "\n" else ""
    val emptyState =
      if (hasPreviews)
        "\n<p id=\"cp-empty\" class=\"cp-empty\" hidden>No previews match your filter.</p>"
      else ""
    val filterScript =
      if (hasPreviews) "\n<script>${catalogFilterScript(hasThemes)}</script>" else ""
    return document(
      title = "$moduleLabel — compose-preview",
      body =
        """
        $about$nav<p class="cp-head">${WebEscaping.htmlEscape(moduleLabel)}${trustBadge(trust)}</p>
        $themeToggle<p class="cp-sub">${previews.size} preview(s) · click one to view with overrides ·
          <a href="$basePath/bundle.zip$q">download all (.zip)</a></p>
        $searchBox<div class="cp-grid" id="cp-grid">
        $cards
        </div>$emptyState$filterScript
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
    trust: String? = null,
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
  ): String {
    val idSeg = WebEscaping.urlEncodeSegment(preview.id)
    val q = querySuffix(linkQuery(token, sessionId, basePath, isPublic))
    val label = WebEscaping.htmlEscape(preview.label)
    val idText = WebEscaping.htmlEscape(preview.id)
    val modes = preview.modes.joinToString(",") { it.wire }
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
    // The render mode is a radio group — PNG (baked snapshot, default), Live Compose (daemon
    // stream), Wasm (in-browser CMP app). The Wasm option + its background checkbox appear only
    // when
    // a Wasm app backs the session; the Live radio is present but disabled when no stream is
    // offered.
    // Ids `cp-live` / `cp-wasm-toggle` are kept on the radios so the transition JS resolves them.
    val wasmModeRadio =
      if (wasmSrc != null)
        "<label class=\"cp-live-row\"><input type=\"radio\" name=\"cp-mode\" value=\"wasm\" " +
          "id=\"cp-wasm-toggle\"> Wasm</label>"
      else ""
    val wasmBgRow =
      if (wasmSrc != null)
        "<label class=\"cp-live-row\"><input id=\"cp-wasm-bg\" type=\"checkbox\"> " +
          "Component only (no background)</label>"
      else ""
    // SVG mode reuses the snapshot lane (the same `<img>`) but points it at the vector
    // `/render/<id>.svg` instead of the raster `.png`. Offered only when the session can export SVG
    // ([hasSvgExport]) — the same gate as the SVG direct-link row.
    val svgModeRadio =
      if (hasSvgExport)
        "<label class=\"cp-live-row\"><input type=\"radio\" name=\"cp-mode\" value=\"svg\" " +
          "id=\"cp-mode-svg\"> SVG</label>"
      else ""
    // "Full page (scroll)" — in SVG mode, points the vector `<img>` (and the copyable SVG link) at
    // `/render/<id>.svg?scroll=long`, the full-page export of a scrolling preview (a Wear scrolling
    // screen slice-stitched into a tall capsule, or a phone LazyColumn grown tall) instead of the
    // viewport-sized SVG. Enabled by the JS only while SVG is the active mode; the raster PNG lane
    // ignores it. Same [hasSvgExport] gate as the SVG radio.
    val scrollLongRow =
      if (hasSvgExport)
        "<label class=\"cp-live-row\"><input id=\"cp-scroll-long\" type=\"checkbox\" disabled> " +
          "Full page (scroll)</label>"
      else ""
    val deviceOptions =
      COMMON_DEVICES.joinToString("\n") { (value, name) ->
        val v = WebEscaping.htmlEscape(value)
        "<option value=\"$v\">${WebEscaping.htmlEscape(name)}</option>"
      }
    // A static bundle/catalog replays baked PNGs — the server can't re-render, so the override
    // controls that rebuild the /render URL (device/locale/font scale/orientation + the live
    // stream)
    // do nothing. Disable them (with a note) instead of leaving dead knobs the user fiddles with.
    // Theme is the exception when a Wasm app backs the session: it re-points the in-browser
    // iframe's
    // ?uiMode, so it stays live there. Live daemon sessions (canApplyOverrides) keep everything on.
    val staticSnapshot = !canApplyOverrides
    // Server-render-only controls (no client-side path): disabled on a static snapshot.
    val serverDis = if (staticSnapshot) " disabled" else ""
    // The "Live (stream)" toggle keys off [hasLiveStream], NOT staticSnapshot: a trusted-catalog
    // live session serves static baked snapshots (staticSnapshot=true) yet still offers the daemon
    // stream on demand. For plain daemon / static sessions hasLiveStream tracks canApplyOverrides,
    // so
    // this is unchanged there.
    val liveDis = if (hasLiveStream) "" else " disabled"
    // Controls the in-browser Wasm app also honours — theme (uiMode), font scale (density), locale
    // (layout direction): live whenever the server can render OR a Wasm app backs the session.
    val wasmDis = if (staticSnapshot && wasmSrc == null) " disabled" else ""
    val snapshotNote =
      when {
        !staticSnapshot -> ""
        wasmSrc != null ->
          "<div class=\"cp-note\">Pre-rendered snapshot — pick “Wasm” to interact: " +
            "Theme, Font scale, Locale, background &amp; declared knob values apply in the browser. " +
            "Device/Orientation need the live server. " +
            "<a href=\"$LOCAL_SERVER_DOCS\">Enable a local preview server.</a></div>"
        else ->
          "<div class=\"cp-note\">Pre-rendered snapshot — overrides (device, locale, font scale, " +
            "orientation) need the live server, not a published catalog. " +
            "<a href=\"$LOCAL_SERVER_DOCS\">Enable a local preview server.</a></div>"
      }
    val backendLabel = WebEscaping.htmlEscape(snapshotBackend ?: "Snapshot")
    val liveLabel = WebEscaping.htmlEscape(liveBackend ?: "Live")
    // The app-declared `@ThemeCatalog` theme selector — the discrete-theme axis. Rendered only when
    // the module declares themes; selecting one re-renders the preview under that provider (the
    // `themeProvider` override). Daemon-only, so it's disabled unless the host can render an
    // override (`canApplyOverrides || canRenderOverrides`) — a knob-style control, not a
    // client-side
    // one. Options carry the provider FQN as their value; `@ThemeCatalog(group=…)` buckets them
    // into
    // <optgroup>s. Marked `.cp-knob-theme` so the JS routes its change through the daemon path.
    val themeSelectorHtml =
      if (declaredThemes.isEmpty()) ""
      else {
        val themeDis = if (canApplyOverrides || canRenderOverrides) "" else " disabled"
        val grouped = declaredThemes.groupBy { it.group }
        val optionsOf: (List<ServeTheme>) -> String = { list ->
          list.joinToString("\n") { t ->
            "<option value=\"${WebEscaping.htmlEscape(t.providerFqn)}\">" +
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
        """
        <label>App theme
          <select id="cp-themeProvider" class="cp-knob-theme"$themeDis>
            <option value="">(default)</option>
            $body
          </select>
        </label>
        """
          .trimIndent()
      }
    // Live-only overlay toggles (accessibility / touch visualization). The daemon composites these
    // onto the held session's frames, so they mean nothing on a baked PNG — offered only when a
    // Live
    // Compose stream is available, and disabled until that mode is active (the mode-transition JS
    // flips them). Omitted entirely when no stream backs the session, rather than left permanently
    // dead. `cp-overlay` marks them for the JS collector + enable/disable sync.
    val overlaysHtml =
      if (hasLiveStream)
        """
        <div class="cp-overlays">
          <div class="cp-overlays-head">Overlays (Live Compose)</div>
          <label class="cp-live-row"><input class="cp-overlay" id="cp-talkBack" type="checkbox" disabled> Accessibility (TalkBack)</label>
          <label class="cp-live-row"><input class="cp-overlay" id="cp-touchOverlay" type="checkbox" disabled> Show touches</label>
        </div>
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
        <div class="cp-overlays">
          <div class="cp-overlays-head">Detected features</div>
          $featureRows
        </div>
        """
          .trimIndent()
    // Left-hand component nav drawer (default closed) and its toggle — only when the session
    // carries
    // sibling previews to move between. The right-hand overrides drawer (.cp-controls) is always
    // present and defaults open (the `cp-controls-open` class on .cp-viewer).
    val navDrawer = navDrawerHtml(preview, siblings, basePath, q)
    val navToggle =
      if (navDrawer.isEmpty()) ""
      else
        "<button type=\"button\" class=\"cp-drawer-toggle\" id=\"cp-nav-toggle\" " +
          "aria-expanded=\"false\" aria-controls=\"cp-nav\">☰ Components</button>"
    val body =
      """
      <p class="cp-head"><a href="$basePath/$q">← previews</a>${trustBadge(trust)}</p>
      <p class="cp-sub" title="$idText">$label</p>
      <div class="cp-viewer-bar">
        $navToggle
        <button type="button" class="cp-drawer-toggle" id="cp-controls-toggle" aria-expanded="true" aria-controls="cp-controls">⚙ Overrides</button>
      </div>
      <div class="cp-viewer cp-controls-open" data-preview-id="$idText" data-mode="snapshot" data-modes="$modes" data-static-snapshot="$staticSnapshot" data-can-render-overrides="$canRenderOverrides" data-snapshot-backend="$backendLabel" data-live-backend="$liveLabel" data-render-density="$RENDER_DENSITY"$wasmAttr>
        $navDrawer
        <div class="cp-stage"><span class="cp-backend" id="cp-backend"></span><img id="cp-img" alt="$label"><canvas id="cp-canvas" hidden></canvas>$wasmFrame<div class="cp-error" id="cp-error" role="alert" hidden></div></div>
        <div class="cp-controls" id="cp-controls">
          $snapshotNote
          <div class="cp-modes" role="radiogroup" aria-label="Render mode">
            <label class="cp-live-row"><input type="radio" name="cp-mode" value="png" id="cp-mode-png" checked> PNG</label>
            $svgModeRadio
            <label class="cp-live-row"><input type="radio" name="cp-mode" value="live" id="cp-live"$liveDis> Live Compose</label>
            $wasmModeRadio
          </div>
          $wasmBgRow
          $scrollLongRow
          <label>Theme
            <select id="cp-uiMode"$wasmDis>
              <option value="">(default)</option>
              <option value="light">Light</option>
              <option value="dark">Dark</option>
            </select>
          </label>
          $themeSelectorHtml
          <label>Device
            <select id="cp-device"$serverDis>
              <option value="">(default)</option>
              $deviceOptions
            </select>
          </label>
          <label>Locale (BCP-47)
            <input id="cp-localeTag" type="text" placeholder="e.g. ar, ja-JP" autocomplete="off"$wasmDis>
          </label>
          <label>Font scale: <span id="cp-fontScale-val">default</span>
            <input id="cp-fontScale" type="range" min="0.5" max="2.0" step="0.1" value="1.0"$wasmDis>
          </label>
          <label>Orientation
            <select id="cp-orientation"$serverDis>
              <option value="">(default)</option>
              <option value="portrait">Portrait</option>
              <option value="landscape">Landscape</option>
            </select>
          </label>
          <label>Background
            <select id="cp-background"$serverDis>
              <option value="">(default)</option>
              <option value="clear">Clear (crisp outline)</option>
            </select>
          </label>
          <div class="cp-size">
            <label>Size
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
          $overlaysHtml
          $featureControlsHtml
          ${overrideKnobsHtml(preview, canApplyOverrides || canRenderOverrides, wasmSrc != null)}
          ${downloadLinksHtml(hasSvgExport)}
          <div class="cp-status" id="cp-status"></div>
        </div>
      </div>
      <script>${drawerScript()}</script>
      <script>${viewerThemeStickyScript()}</script>
      <script>${viewerScript()}</script>
      <script>${backendBadgeScript()}</script>
      """
        .trimIndent()
    return document(title = "$label — compose-preview", body = body)
  }

  /**
   * Viewer JS. Two transports behind one set of controls (the `data-mode` seam):
   * - **snapshot** (default): rebuild the `/render` URL from the controls and swap `img.src`.
   * - **live** (tier-2): when "Live (stream)" is on, open the `/ws/{id}` WebSocket, paint pushed
   *   frames onto a `<canvas>`, send `setOverrides` as controls change, and forward pointer drags,
   *   wheel scroll, and keyboard into the held composition as `input` messages.
   *
   * Override collection ([overrides]) is shared by both, so an opt-in field stays opt-in either way
   * (notably fontScale, which is only sent once the slider is moved).
   */
  private fun viewerScript(): String =
    """
    (function () {
      "use strict";
      var root = document.querySelector(".cp-viewer");
      var img = document.getElementById("cp-img");
      var canvas = document.getElementById("cp-canvas");
      var status = document.getElementById("cp-status");
      var errorBox = document.getElementById("cp-error");
      var live = document.getElementById("cp-live");
      // Surface a mode-activation failure visibly, instead of leaving a stale frame that reads as a
      // (wrong) render. Every lane routes its failure here — a dead Live stream, a Wasm app that
      // never boots, a /render that errors — so "can't activate this mode" is never silent.
      function showModeError(msg) {
        if (!errorBox) { status.textContent = msg; return; }
        errorBox.textContent = msg;
        errorBox.hidden = false;
        status.textContent = "";
      }
      function clearModeError() { if (errorBox) { errorBox.hidden = true; errorBox.textContent = ""; } }
      // Human-readable reason for a Live stream that closed before delivering a frame. Maps the
      // server's close codes (1013 capacity, 1008 unauthorized, 1003/CANNOT_ACCEPT carries a reason);
      // a bare abnormal close (1006, e.g. a proxy 502 on the WS upgrade) gets the generic message.
      function liveCloseReason(ev) {
        if (ev && ev.code === 1013) return "Live preview is at capacity — try again shortly.";
        if (ev && ev.code === 1008) return "Live preview unauthorized.";
        if (ev && ev.reason) return "Live preview unavailable: " + ev.reason;
        return "Live preview couldn't connect — the live stream may be unavailable on this server.";
      }
      // Whether the snapshot lane is static (baked PNGs, no /render re-render) — the explicit signal
      // for the wasm auto-enable below. NOT `live.disabled`: a trusted-catalog live session serves
      // static snapshots yet leaves the Live toggle enabled, so `live.disabled` no longer implies
      // "static".
      var staticSnapshot = root.getAttribute("data-static-snapshot") === "true";
      // Whether an override-bearing /render returns fresh pixels even on a static snapshot lane (a
      // trusted-catalog live session: its carried daemon re-renders author-declared knob edits on
      // demand). When true, a knob edit re-points the snapshot /render URL rather than sitting dead.
      var canRenderOverrides = root.getAttribute("data-can-render-overrides") === "true";
      var previewId = root.getAttribute("data-preview-id");
      // The session path prefix ("/<system>") when this viewer is served under a path — it sits at
      // "<base>/p/<id>", so stripping the trailing "/p/<id>" recovers the base ("" for the root
      // mount / legacy ?session= form). /render + /ws requests are prefixed with it so they hit the
      // same session without needing ?session= threaded through.
      var base = location.pathname.replace(/\/p\/[^/]*\/?$/, "");
      var token = new URLSearchParams(location.search).get("token") || "";
      // Carry the tenant through follow-up requests so a non-default ?session= stays on its module.
      var session = new URLSearchParams(location.search).get("session") || "";
      // Hydrate the declared knob controls from the page URL's `knob.<key>` params, so a deep link
      // (or a copied "Direct links — overrides applied" URL) opens with those values already set.
      // The initial render then carries them through whichever transport is live — including the
      // Wasm iframe, whose fragment/patch is built purely from control state — instead of showing
      // the author default until the control is manually edited. Only the author-declared knobs this
      // feature drives are hydrated (display axes are unchanged, pre-existing behaviour).
      (function () {
        var q = new URLSearchParams(location.search);
        document.querySelectorAll(".cp-knob").forEach(function (el) {
          var key = el.getAttribute("data-knob-key");
          if (!key) return;
          var v = q.get("knob." + key);
          if (v === null) return;
          if (el.type === "checkbox") el.checked = (v === "true" || v === "1");
          else el.value = v;
        });
      })();
      // The selects + text input are opt-in (empty value = "use the preview's default"). The font
      // scale slider has no empty state, so it's gated separately: we only send fontScale once the
      // user moves it (fontScaleTouched), otherwise the slider's standing 1.0 would override a
      // preview's declared default font scale and the first render wouldn't match the thumbnail.
      var fields = ["uiMode", "device", "localeTag", "orientation", "background"];
      var fs = document.getElementById("cp-fontScale");
      var fsVal = document.getElementById("cp-fontScale-val");
      var fontScaleTouched = false;
      var ws = null;
      // The snapshot lane serves either the raster PNG or the vector SVG through the same <img>.
      // The render-mode radio flips this (".png" default, ".svg" in SVG mode); refreshSnapshot and
      // the copyable links read it so a re-render / copied URL matches the on-screen format.
      var snapshotExt = ".png";

      // Size overrides (the Fixed / Max / Min / Within modes). Which query params carry the numbers
      // is chosen by the mode: Fixed pins the frame via widthPx/heightPx; Max / Min / Within are
      // wrapped-axis bounds (maxWidthPx / minWidthPx …). Blank inputs are omitted, so one axis can be
      // bounded without the other. Server-side only (a daemon re-measures) — the inputs are
      // disabled on a static snapshot like Device/Orientation, so it never emits them.
      //
      // The inputs are authored in dp (the Compose unit); the wire stays in px like every other
      // override, so a dp value is multiplied by the backend's render density before it's sent (and
      // the copyable /render URL stays px-consistent). data-render-density carries the factor.
      var renderDensity = parseFloat(root.getAttribute("data-render-density")) || 2;
      // dp (string from the input) → a positive integer px value, or null when blank/non-positive.
      function sizePx(id) {
        var el = document.getElementById(id);
        if (!el || !el.value) return null;
        var dp = parseFloat(el.value);
        if (!(dp > 0)) return null;
        return String(Math.max(1, Math.round(dp * renderDensity)));
      }
      function sizeOverrides() {
        var mode = document.getElementById("cp-sizeMode");
        var o = {};
        if (!mode || !mode.value) return o;
        if (mode.value === "fixed") {
          if (sizePx("cp-fixedW")) o.widthPx = sizePx("cp-fixedW");
          if (sizePx("cp-fixedH")) o.heightPx = sizePx("cp-fixedH");
        }
        if (mode.value === "min" || mode.value === "within") {
          if (sizePx("cp-minW")) o.minWidthPx = sizePx("cp-minW");
          if (sizePx("cp-minH")) o.minHeightPx = sizePx("cp-minH");
        }
        if (mode.value === "max" || mode.value === "within") {
          if (sizePx("cp-maxW")) o.maxWidthPx = sizePx("cp-maxW");
          if (sizePx("cp-maxH")) o.maxHeightPx = sizePx("cp-maxH");
        }
        return o;
      }
      function overrides() {
        var o = {};
        fields.forEach(function (f) {
          var el = document.getElementById("cp-" + f);
          if (el && el.value) o[f] = el.value;
        });
        if (fontScaleTouched && fs) o.fontScale = fs.value;
        var size = sizeOverrides();
        Object.keys(size).forEach(function (k) { o[k] = size[k]; });
        return o;
      }
      // The live-stream override map: the display fields PLUS the author-declared knob values as
      // `knob.<key>=<value>` entries (the daemon's setOverrides parses the same map /render does,
      // typing each from the preview's declaration). Kept separate from overrides() so query() and
      // the Wasm patch — which append/ignore knobs their own way — are unaffected; without this a
      // knob edit during an active Live (stream) would send only the display fields and the daemon
      // would reset the others to their defaults. Unlike query(), every knob is sent (not just
      // changed ones) for exactly that reason, so defaults are not filtered here.
      function liveOverrides() {
        var o = overrides();
        document.querySelectorAll(".cp-knob").forEach(function (el) {
          if (el.disabled) return;
          var key = el.getAttribute("data-knob-key");
          if (!key) return;
          var val = (el.type === "checkbox") ? (el.checked ? "true" : "false") : el.value;
          if (val === "") return;
          o["knob." + key] = val;
        });
        // Live-only overlay toggles (talkBack / touchOverlay). Their id is "cp-<key>", so the daemon
        // key is the id minus the prefix. Sent as an explicit true/false so unchecking clears the
        // overlay on the next setOverrides (which replaces the whole map). Disabled ⇒ not live ⇒
        // skipped, so they never leak onto a snapshot.
        document.querySelectorAll(".cp-overlay").forEach(function (el) {
          if (el.disabled) return;
          o[el.id.replace(/^cp-/, "")] = el.checked ? "true" : "false";
        });
        // App-declared theme (themeProvider = provider FQN). Only when a theme is picked and the
        // control is live; "(default)" (empty) leaves the daemon on the preview's own wrapper.
        var tp = document.getElementById("cp-themeProvider");
        if (tp && !tp.disabled && tp.value) o["themeProvider"] = tp.value;
        // Detected-feature: keyboard focus. Checked ⇒ focus the first focusable + draw the overlay
        // (focus=0). Daemon-only, so skipped when disabled.
        var fc = document.getElementById("cp-focus");
        if (fc && !fc.disabled && fc.checked) o["focus"] = "0";
        // Detected-feature: one-handed gesture hints. Checked ⇒ draw the gesture-hint overlay
        // (gestures=true). Android-daemon-only, so skipped when disabled.
        var gc = document.getElementById("cp-gestures");
        if (gc && !gc.disabled && gc.checked) o["gestures"] = "true";
        return o;
      }
      function query() {
        var o = overrides();
        // Public routes are open, so a page that arrived without a token stays token-free — only
        // carry token= when this page's own URL had one (a token-gated box).
        var parts = [];
        if (token) parts.push("token=" + encodeURIComponent(token));
        if (session) parts.push("session=" + encodeURIComponent(session));
        Object.keys(o).forEach(function (k) { parts.push(k + "=" + encodeURIComponent(o[k])); });
        // Author-declared knobs: knob.<key>=<value>. The server infers the type from the preview's
        // declaration, so no <kind>: prefix. A knob still at its declared default is omitted — that
        // keeps the URL on the instant baked snapshot (any knob.* param routes a published catalog
        // to the daemon for a fresh re-render); only an actually-changed knob is sent.
        document.querySelectorAll(".cp-knob").forEach(function (el) {
          if (el.disabled) return;
          var key = el.getAttribute("data-knob-key");
          if (!key) return;
          var val = (el.type === "checkbox") ? (el.checked ? "true" : "false") : el.value;
          if (val === "") return;
          if (val === (el.getAttribute("data-knob-initial") || "")) return;
          parts.push("knob." + encodeURIComponent(key) + "=" + encodeURIComponent(val));
        });
        // App-declared theme (themeProvider = provider FQN). Routes to the daemon like a knob; a
        // published catalog re-renders on demand. Omitted at "(default)" so the URL stays on the
        // instant baked snapshot until a theme is actually chosen.
        var tp = document.getElementById("cp-themeProvider");
        if (tp && !tp.disabled && tp.value) {
          parts.push("themeProvider=" + encodeURIComponent(tp.value));
        }
        // Detected-feature: keyboard focus (focus=0). Routes to the daemon like a knob; omitted when
        // unchecked so the URL stays on the baked snapshot.
        var fc = document.getElementById("cp-focus");
        if (fc && !fc.disabled && fc.checked) parts.push("focus=0");
        // Detected-feature: one-handed gesture hints (gestures=true). Routes to the daemon like a
        // knob; omitted when unchecked so the URL stays on the baked snapshot.
        var gc = document.getElementById("cp-gestures");
        if (gc && !gc.disabled && gc.checked) parts.push("gestures=true");
        return parts.join("&");
      }
      // "Full page (scroll)" appends `scroll=long` — but only to the SVG lane (the raster PNG has no
      // full-page export), so the toggle scopes to `.svg` and leaves the PNG URL untouched.
      var scrollLong = document.getElementById("cp-scroll-long");
      function withScroll(ext, qs) {
        if (ext === ".svg" && scrollLong && scrollLong.checked) {
          return qs ? qs + "&scroll=long" : "scroll=long";
        }
        return qs;
      }
      function refreshSnapshot() {
        status.textContent = "rendering…";
        var qs = withScroll(snapshotExt, query());
        var url =
          base + "/render/" + encodeURIComponent(previewId) + snapshotExt + (qs ? "?" + qs : "");
        var next = new Image();
        next.onload = function () { img.src = url; status.textContent = ""; clearModeError(); };
        next.onerror = function () {
          showModeError((snapshotExt === ".svg" ? "SVG" : "PNG") + " render failed for this preview.");
        };
        next.src = url;
        refreshLinks();
      }
      // The copyable direct-link panel: rebuild the absolute /render URLs (PNG + optional SVG) from
      // the current controls so a copied/downloaded link reproduces exactly what's on screen. Built
      // on location.origin so the link is absolute (curl-able / shareable), and kept in sync on
      // every control or knob change — even the ones that don't re-render the snapshot themselves.
      function renderUrl(ext) {
        var qs = withScroll(ext, query());
        return location.origin + base + "/render/" + encodeURIComponent(previewId) + ext +
          (qs ? "?" + qs : "");
      }
      function refreshLinks() {
        [["png", ".png"], ["svg", ".svg"]].forEach(function (pair) {
          var field = document.getElementById("cp-url-" + pair[0]);
          if (!field) return;
          var url = renderUrl(pair[1]);
          field.value = url;
          var dl = document.getElementById("cp-dl-" + pair[0]);
          if (dl) dl.href = url;
        });
      }
      document.querySelectorAll(".cp-copy").forEach(function (btn) {
        btn.addEventListener("click", function () {
          var field = document.getElementById(btn.getAttribute("data-copy-target"));
          if (!field) return;
          var done = function () {
            var was = btn.textContent;
            btn.textContent = "Copied";
            setTimeout(function () { btn.textContent = was; }, 1200);
          };
          if (navigator.clipboard && navigator.clipboard.writeText) {
            navigator.clipboard.writeText(field.value).then(done, function () { field.select(); });
          } else {
            field.select();
            try { document.execCommand("copy"); done(); } catch (e) {}
          }
        });
      });
      function drawFrame(b64, codec) {
        var im = new Image();
        im.onload = function () {
          canvas.width = im.naturalWidth;
          canvas.height = im.naturalHeight;
          canvas.getContext("2d").drawImage(im, 0, 0);
          // A <canvas> stretches its buffer to fill its CSS box, so a daemon frame whose aspect
          // differs from the pinned snapshot box would squish. Cache the buffer dims and re-fit the
          // element (contain, centred) so the frame letterboxes within the snapshot footprint
          // instead of distorting to fill it.
          liveW = im.naturalWidth;
          liveH = im.naturalHeight;
          fitLiveCanvas();
        };
        im.src = "data:image/" + (codec || "png") + ";base64," + b64;
      }
      // --- Live input forwarding (no-op on the snapshot lane). Coordinates are image-natural
      // pixels; pointer events are grouped by pointerId so Compose's gesture pipeline tracks drags
      // and multi-touch. Keys map to Android KEYCODE_* decimal strings (the daemon's wire format).
      function liveActive() { return ws && ws.readyState === 1 && canvas.width; }
      function sendInput(msg) {
        if (liveActive()) ws.send(JSON.stringify(Object.assign({ type: "input" }, msg)));
      }
      function pixel(ev) {
        var rect = canvas.getBoundingClientRect();
        if (!rect.width || !rect.height) return null;
        return {
          x: Math.round((ev.clientX - rect.left) / rect.width * canvas.width),
          y: Math.round((ev.clientY - rect.top) / rect.height * canvas.height),
        };
      }
      // Per-pointer state. The pointerDown is *deferred* until the first move so a tap with no drag
      // becomes a single `click` (matching the daemon's CLICK fast-path, which renders between press
      // and release — a batched down+up can race Modifier.clickable). pointermove is coalesced to one
      // send per pointerId per animation frame, so a fast drag doesn't flood the lane and concurrent
      // fingers don't overwrite each other (multi-touch).
      var pointers = {};           // pointerId -> { x, y, moved }
      var pendingMoves = {};       // pointerId -> pointerMove message
      var moveScheduled = false;
      function flushMoves() {
        moveScheduled = false;
        var snapshot = pendingMoves;
        pendingMoves = {};
        Object.keys(snapshot).forEach(function (id) { sendInput(snapshot[id]); });
      }
      canvas.addEventListener("pointerdown", function (ev) {
        if (!liveActive()) return;
        var p = pixel(ev); if (!p) return;
        canvas.focus();
        try { canvas.setPointerCapture(ev.pointerId); } catch (e) {}
        pointers[ev.pointerId] = { x: p.x, y: p.y, moved: false };
      });
      canvas.addEventListener("pointermove", function (ev) {
        if (!liveActive() || ev.buttons === 0) return; // only while pressed (a drag)
        var st = pointers[ev.pointerId]; if (!st) return;
        var p = pixel(ev); if (!p) return;
        if (!st.moved) {
          // First movement → this is a drag: emit the deferred press at the original point.
          st.moved = true;
          sendInput({ kind: "pointerDown", pixelX: st.x, pixelY: st.y, pointerId: ev.pointerId });
        }
        pendingMoves[ev.pointerId] =
          { kind: "pointerMove", pixelX: p.x, pixelY: p.y, pointerId: ev.pointerId };
        if (!moveScheduled) { moveScheduled = true; requestAnimationFrame(flushMoves); }
      });
      function endPointer(ev) {
        var st = pointers[ev.pointerId]; if (!st) return;
        delete pointers[ev.pointerId];
        var p = pixel(ev) || { x: st.x, y: st.y };
        if (st.moved) {
          flushMoves();
          sendInput({ kind: "pointerUp", pixelX: p.x, pixelY: p.y, pointerId: ev.pointerId });
        } else {
          // No drag → a tap. Send a single CLICK (the daemon renders between press and release).
          sendInput({ kind: "click", pixelX: st.x, pixelY: st.y, pointerId: ev.pointerId });
        }
      }
      canvas.addEventListener("pointerup", endPointer);
      canvas.addEventListener("pointercancel", endPointer);
      canvas.addEventListener("wheel", function (ev) {
        if (!liveActive()) return;
        var p = pixel(ev); if (!p) return;
        ev.preventDefault();
        // Both daemon dispatchers drop non-key input without a position, so include the pixel.
        sendInput({ kind: "rotaryScroll", pixelX: p.x, pixelY: p.y, scrollDeltaY: ev.deltaY });
      }, { passive: false });
      // Keyboard: focus the canvas (tabindex) to type. Maps the common keys to Android keycodes;
      // unmapped keys are dropped (the daemon ignores codes outside its translation table anyway).
      canvas.tabIndex = 0;
      function androidKeycode(k) {
        if (k.length === 1) {
          var c = k.toLowerCase();
          if (c >= "a" && c <= "z") return String(29 + (c.charCodeAt(0) - 97)); // KEYCODE_A = 29
          if (c >= "0" && c <= "9") return String(7 + (c.charCodeAt(0) - 48)); // KEYCODE_0 = 7
          if (k === " ") return "62"; // SPACE
        }
        switch (k) {
          case "Enter": return "66";
          case "Backspace": return "67";
          case "Tab": return "61";
          case "Escape": return "111";
          case "Delete": return "112";
          case "ArrowUp": return "19";
          case "ArrowDown": return "20";
          case "ArrowLeft": return "21";
          case "ArrowRight": return "22";
          default: return null;
        }
      }
      function keyInput(kind, ev) {
        if (!liveActive()) return;
        var code = androidKeycode(ev.key);
        if (code === null) return;
        ev.preventDefault();
        sendInput({ kind: kind, keyCode: code });
      }
      canvas.addEventListener("keydown", function (ev) { keyInput("keyDown", ev); });
      canvas.addEventListener("keyup", function (ev) { keyInput("keyUp", ev); });
      function openStream() {
        root.setAttribute("data-mode", "live");
        // Seed the canvas buffer with the current snapshot *before* the swap, so there's no blank
        // flash while "connecting…" (the first frame overwrites it).
        if (img.naturalWidth && img.naturalHeight) {
          canvas.width = img.naturalWidth;
          canvas.height = img.naturalHeight;
          try { canvas.getContext("2d").drawImage(img, 0, 0); } catch (e) {}
        }
        // Mount the canvas as an absolute overlay on the snapshot's slot — the same fixed box the
        // Wasm tier locks to. The img stays in flow (visibility:hidden keeps its slot), so the stage
        // geometry is defined once by the snapshot and a live frame whose pixel dims differ from the
        // baked PNG scales into this box instead of resizing the stage (drawFrame only touches the
        // buffer now, never the layout). Input mapping reads the buffer size, so it's unaffected.
        canvas.classList.add("cp-canvas-live");
        positionOverlay(canvas);
        img.style.visibility = "hidden";
        canvas.hidden = false;
        status.textContent = "connecting…";
        var proto = location.protocol === "https:" ? "wss:" : "ws:";
        // Request WebP frames (smaller; the browser decodes them via the data URL, and the daemon
        // downgrades to PNG when it can't encode WebP — each frame carries its actual codec).
        var qs = query();
        // Track whether the stream ever delivered a frame: a close/error *before* the first frame is
        // a failed activation (surface it), whereas a close *after* frames is just a normal teardown.
        var liveGotFrame = false;
        ws = new WebSocket(proto + "//" + location.host + base + "/ws/" +
          encodeURIComponent(previewId) + "?" + (qs ? qs + "&codec=webp" : "codec=webp"));
        ws.onopen = function () {
          // The connect URL seeds only query()'s fields — the display axes plus changed knobs — so
          // the live-only overlays (talkBack / touchOverlay) and anything toggled during the
          // connecting window aren't in it. Replay the full live override map once the socket is
          // ready so the daemon reflects the exact current control state, including an overlay
          // checked before onopen whose change event the readyState guard dropped.
          ws.send(JSON.stringify({ type: "setOverrides", overrides: liveOverrides() }));
        };
        ws.onmessage = function (ev) {
          var m;
          try { m = JSON.parse(ev.data); } catch (e) { return; }
          if (m.type === "frame") { liveGotFrame = true; clearModeError(); drawFrame(m.dataBase64, m.codec); status.textContent = ""; }
          else if (m.type === "error") { showModeError(m.message || "Live preview error."); }
        };
        // onerror always precedes onclose; let onclose decide (it carries the code/reason). Only
        // surface here if the socket somehow errors while already open+frame-less and never closes.
        ws.onerror = function () { if (!liveGotFrame) status.textContent = "connecting…"; };
        ws.onclose = function (ev) {
          ws = null;
          // Closed before any frame ⇒ the mode failed to activate. Drop the stale seeded snapshot
          // from the canvas so it can't masquerade as a live render, and surface why.
          if (!liveGotFrame && live && live.checked) {
            canvas.hidden = true;
            img.style.removeProperty("visibility");
            showModeError(liveCloseReason(ev));
          }
        };
      }
      function closeStream() {
        root.setAttribute("data-mode", "snapshot");
        if (ws) { ws.close(); ws = null; }
        canvas.hidden = true;
        // Tear down the overlay: drop the absolute positioning and restore the snapshot img's slot.
        canvas.classList.remove("cp-canvas-live");
        canvas.style.removeProperty("left"); canvas.style.removeProperty("top");
        canvas.style.removeProperty("width"); canvas.style.removeProperty("height");
        // Forget the last frame's dims so a reconnect seeds from the snapshot (fill) until its own
        // first frame re-fits, rather than briefly letterboxing to a stale aspect.
        liveW = 0;
        liveH = 0;
        img.style.removeProperty("visibility");
        img.hidden = false;
      }
      // --- Wasm tier (the in-browser CMP app, mounted in a sandboxed iframe). Only wired when the
      // session carries a Wasm app (data-wasm-src present). Theme/font-scale/locale re-point the
      // iframe's ?uiMode/?fontScale/?localeTag (device/orientation are server-render-only).
      var stage = document.querySelector(".cp-stage");
      var wasmFrame = document.getElementById("cp-wasm");
      var wasmToggle = document.getElementById("cp-wasm-toggle");
      var wasmBg = document.getElementById("cp-wasm-bg");
      var wasmSrc = root.getAttribute("data-wasm-src") || "";
      // Set once the app has painted its first frame (its "cp-wasm-ready" message). Until then a
      // control change re-points ?query (initial load); after, it posts an override patch so the
      // app recomposes in place instead of reloading the whole ~20 MB Wasm bundle.
      var wasmReady = false;
      // Boot watchdog: if the app never signals "cp-wasm-ready" (bundle 404, Wasm/GL failure, …),
      // surface a visible error instead of leaving the stage stuck on "loading Wasm…" forever.
      var wasmBootTimer = null;
      function wasmBaseSrc() {
        if (!wasmSrc) return "";
        // The src comes from a server-set data- attribute, but resolve it against our own origin and
        // refuse anything not same-origin http(s) anyway — so a `javascript:`/`data:` URL can never
        // reach the iframe even if the attribute were ever mis-set (defuses DOM-text-as-HTML). The
        // query is left as the server baked it (the variant's default theme) — session overrides
        // never go in it, so it stays the app's clean base to revert to when a control is cleared.
        var u;
        try { u = new URL(wasmSrc, location.origin); } catch (e) { return ""; }
        if (u.origin !== location.origin) return "";
        return u.href;
      }
      // NOTE: the font prefetch lives in the app's own index.html (it starts the manifest+font
      // fetches at document load, in parallel with the Wasm boot), not on this page. That's where
      // it belongs regardless of the sandbox: it must be in flight before the iframe navigates, and
      // the app is the one that consumes the promises. (Historically the iframe was opaque-origin
      // with its own cache partition, so a page-side preload was also unreusable and fetched every
      // font twice; with allow-same-origin the partition is shared, but the app-side prefetch is
      // still the right home, so keep page-side preloads out — see the ServeWebFixtureTest guard.)
      // The override patch (theme / font scale / locale) the running app merges over its baked base —
      // a bare `a=b&c=d` query. An absent key falls back to the app's baked default (e.g. cleared
      // Theme → the variant's uiMode). Device / orientation are server-render-only, so not forwarded.
      // The stage checkerboard's tile origin in the iframe's own CSS-px coordinates. The app can't
      // render a transparent surface, so it paints this same pattern itself; handing it the phase
      // makes the in-canvas cells continue the page's cells exactly (the stylesheet positions the
      // 16px tile at 50% of the stage's padding box; the iframe sits at style.left/top within it).
      function wasmBgPhase() {
        var left = parseFloat(wasmFrame.style.left) || 0;
        var top = parseFloat(wasmFrame.style.top) || 0;
        var x = (stage.clientWidth - 16) / 2 - left;
        var y = (stage.clientHeight - 16) / 2 - top;
        return x.toFixed(2) + "," + y.toFixed(2);
      }
      function wasmOverridePatch() {
        var parts = [];
        var el = document.getElementById("cp-uiMode");
        if (el && el.value) parts.push("uiMode=" + encodeURIComponent(el.value));
        var loc = document.getElementById("cp-localeTag");
        if (loc && loc.value) parts.push("localeTag=" + encodeURIComponent(loc.value));
        if (fontScaleTouched && fs) parts.push("fontScale=" + encodeURIComponent(fs.value));
        if (wasmBg && wasmBg.checked) parts.push("background=off");
        parts.push("bgPhase=" + encodeURIComponent(wasmBgPhase()));
        // Author-declared knobs also apply in the browser: the wasm catalog seeds its
        // `catalogOverride*` from these `knob.<key>` params. Mirror query() — omit a knob still at
        // its `data-knob-initial` so an unedited knob reverts to the author default in the app.
        document.querySelectorAll(".cp-knob").forEach(function (el) {
          if (el.disabled) return;
          var key = el.getAttribute("data-knob-key");
          if (!key) return;
          var val = (el.type === "checkbox") ? (el.checked ? "true" : "false") : el.value;
          if (val === "") return;
          if (val === (el.getAttribute("data-knob-initial") || "")) return;
          parts.push("knob." + encodeURIComponent(key) + "=" + encodeURIComponent(val));
        });
        return parts.join("&");
      }
      // Initial iframe URL: the baked base plus the current overrides in the `#…` fragment, so the
      // app's first paint honours them yet keeps the query as its true base (a later clear reverts).
      function wasmInitialSrc() {
        var base = wasmBaseSrc();
        if (!base) return "";
        var patch = wasmOverridePatch();
        return patch ? base + "#" + patch : base;
      }
      // Pixel parity: lay an absolute overlay ([el] — the Wasm iframe or the live canvas) exactly
      // over the snapshot's rendered box, so switching to it shouldn't move anything. Both the Wasm
      // app (contain-fitting the same sticker geometry the snapshot baked) and the daemon (the same
      // preview re-rendered) fill this box, so the three transports share one geometry.
      function positionOverlay(el) {
        var sr = stage.getBoundingClientRect();
        var r = img.getBoundingClientRect();
        if (r.width > 0 && r.height > 0) {
          // Offsets are relative to the stage's padding box — subtract its border (clientLeft/Top).
          el.style.left = (r.left - sr.left - stage.clientLeft) + "px";
          el.style.top = (r.top - sr.top - stage.clientTop) + "px";
          el.style.width = r.width + "px";
          el.style.height = r.height + "px";
        } else {
          // No snapshot box to mirror (e.g. its render 404'd): fill the stage's content box.
          el.style.left = "12px"; el.style.top = "12px";
          el.style.width = "calc(100% - 24px)";
          el.style.height = (stage.clientHeight - 24) + "px";
        }
      }
      function positionWasmFrame() { positionOverlay(wasmFrame); }
      // The live canvas can't just fill the snapshot box like the Wasm frame does: a <canvas>
      // stretches its buffer to its CSS box, so pinning a differently-shaped daemon frame to the
      // snapshot's rect squished the render. Fit the frame (contain) inside that rect, centred — it
      // letterboxes within the snapshot footprint (so the stage still never resizes, the property
      // the pinned box was introduced for) instead of distorting. liveW/liveH cache the current
      // buffer so a window resize re-fits; unset (before the first frame, when the buffer is seeded
      // from the same-aspect snapshot) it fills the box exactly, matching positionOverlay.
      var liveW = 0;
      var liveH = 0;
      function fitLiveCanvas() {
        var sr = stage.getBoundingClientRect();
        var r = img.getBoundingClientRect();
        var boxLeft, boxTop, boxW, boxH;
        if (r.width > 0 && r.height > 0) {
          boxLeft = r.left - sr.left - stage.clientLeft;
          boxTop = r.top - sr.top - stage.clientTop;
          boxW = r.width;
          boxH = r.height;
        } else {
          boxLeft = 12;
          boxTop = 12;
          boxW = stage.clientWidth - 24;
          boxH = stage.clientHeight - 24;
        }
        var w = boxW;
        var h = boxH;
        if (liveW > 0 && liveH > 0) {
          var scale = Math.min(boxW / liveW, boxH / liveH);
          w = liveW * scale;
          h = liveH * scale;
        }
        canvas.style.left = (boxLeft + (boxW - w) / 2) + "px";
        canvas.style.top = (boxTop + (boxH - h) / 2) + "px";
        canvas.style.width = w + "px";
        canvas.style.height = h + "px";
      }
      // Swap the stage from the snapshot to the (already-painted) Wasm frame. The snapshot keeps
      // its layout slot (visibility, not display) so the stage geometry — and the overlay tracking
      // it — never shifts.
      function revealWasm() {
        if (!wasmActive() || wasmReady) return;
        wasmReady = true;
        if (wasmBootTimer) { clearTimeout(wasmBootTimer); wasmBootTimer = null; }
        clearModeError();
        positionWasmFrame();
        wasmFrame.classList.add("cp-wasm-live");
        img.style.visibility = "hidden";
        status.textContent = "";
        // Re-sync any control changed during load (the fragment only captured open-time state).
        var patch = wasmOverridePatch();
        if (patch && wasmFrame.contentWindow) wasmFrame.contentWindow.postMessage(patch, "*");
      }
      function openWasm() {
        // No-op without a Wasm app (wasmFrame absent): enterMode() calls this unconditionally, but a
        // non-Wasm daemon/static session has no iframe to drive. Guard so touching it can't throw.
        if (!wasmFrame) return;
        // Wasm and the daemon stream are mutually exclusive; the mode switch (enterMode) tears the
        // stream down before opening Wasm, so there's nothing extra to close here.
        root.setAttribute("data-mode", "wasm");
        canvas.hidden = true;
        // Keep the snapshot visible while the app loads; the iframe mounts over it at opacity 0
        // and only fades in on the app's first-frame signal — no blank/white flash.
        positionWasmFrame();
        wasmFrame.hidden = false;
        wasmReady = false;
        wasmFrame.src = wasmInitialSrc();
        status.textContent = "loading Wasm…";
        if (wasmBootTimer) clearTimeout(wasmBootTimer);
        wasmBootTimer = setTimeout(function () {
          if (!wasmReady && wasmActive()) {
            showModeError("Wasm preview didn't start — the in-browser app failed to load.");
          }
        }, 20000);
      }
      function closeWasm() {
        // No Wasm iframe (non-Wasm session) ⇒ nothing to tear down; enterMode() still calls this
        // unconditionally when switching to Live/PNG, so guard against the null frame.
        if (!wasmFrame) return;
        root.setAttribute("data-mode", "snapshot");
        wasmReady = false;
        if (wasmBootTimer) { clearTimeout(wasmBootTimer); wasmBootTimer = null; }
        wasmFrame.classList.remove("cp-wasm-live");
        wasmFrame.hidden = true; wasmFrame.removeAttribute("src");
        img.style.removeProperty("visibility");
        img.hidden = false;
        status.textContent = "";
      }
      function wasmActive() { return wasmToggle && wasmToggle.checked; }

      function onControlsChanged() {
        // Keep the copyable direct links current no matter which transport handles the change.
        refreshLinks();
        if (wasmActive()) {
          // Recompose in place once the app is up; before it's ready, re-point the initial src (the
          // fragment carries the overrides) — the load handler re-syncs the final state either way.
          if (wasmReady && wasmFrame.contentWindow) {
            wasmFrame.contentWindow.postMessage(wasmOverridePatch(), "*");
          } else {
            wasmFrame.src = wasmInitialSrc();
          }
          return;
        }
        if (live.checked && ws && ws.readyState === 1) {
          ws.send(JSON.stringify({ type: "setOverrides", overrides: liveOverrides() }));
        } else if (staticSnapshot && wasmToggle) {
          // Static snapshot backed by a Wasm app: the baked PNG can't honour theme/font-scale/locale
          // (only the in-browser tier can), and /render can't re-render a published catalog. So a
          // wasm-honoured control change auto-enables the Wasm tier and applies there, instead of
          // firing a dead refreshSnapshot the user sees as "the control does nothing". (staticSnapshot
          // marks a non-renderable snapshot lane — true even for a live catalog whose Live toggle is
          // enabled; device/orientation stay disabled, so only the wasm-honoured controls reach here.)
          setMode("wasm");
        } else if (!live.checked) {
          refreshSnapshot();
        }
      }

      // Render-mode radio group (PNG / SVG / Live Compose / Wasm). Selecting one tears down the
      // other transport and enters the chosen one. PNG and SVG both drive the baked snapshot lane —
      // same <img>, different render extension (snapshotExt). closeStream / closeWasm are
      // idempotent, so a mode switch can safely tear down both regardless of the prior state; live
      // and wasm reset snapshotExt so a later fallback refresh serves the raster PNG, not a stale
      // ".svg".
      function enterMode(m) {
        // A mode switch always clears a prior lane's error; the new lane re-raises its own if it fails.
        clearModeError();
        if (m === "live") { snapshotExt = ".png"; closeWasm(); openStream(); }
        else if (m === "wasm") { snapshotExt = ".png"; closeStream(); openWasm(); }
        else if (m === "svg") {
          // SVG reuses the snapshot lane; closeStream/closeWasm reset data-mode to "snapshot", so
          // stamp "svg" afterwards for the backend badge, then swap the vector into the <img>.
          closeStream(); closeWasm(); snapshotExt = ".svg";
          root.setAttribute("data-mode", "svg"); refreshSnapshot();
        } else { snapshotExt = ".png"; closeStream(); closeWasm(); refreshSnapshot(); }
        syncOverlayToggles();
        syncScrollToggle();
      }
      // "Full page (scroll)" is only meaningful for the SVG lane; enable it iff SVG is the active
      // mode (like the live-only overlay toggles), so it reads as inert under PNG / Live / Wasm.
      function syncScrollToggle() {
        if (scrollLong) scrollLong.disabled = snapshotExt !== ".svg";
      }
      if (scrollLong) {
        scrollLong.addEventListener("change", function () {
          if (snapshotExt === ".svg") refreshSnapshot(); else refreshLinks();
        });
      }
      // The live-only overlay toggles (talkBack / touchOverlay) are meaningful only while the daemon
      // holds the composition, so they're enabled iff Live Compose is the active mode and greyed out
      // (like the static-snapshot controls) otherwise. Called on every mode transition.
      var overlayToggles = document.querySelectorAll(".cp-overlay");
      function syncOverlayToggles() {
        var on = !!(live && live.checked);
        Array.prototype.forEach.call(overlayToggles, function (el) { el.disabled = !on; });
      }
      // Programmatic switch (e.g. a wasm-only control auto-enabling Wasm): tick the radio so the UI
      // reflects it, then run the transition.
      function setMode(m) {
        var r = document.getElementById(
          m === "live" ? "cp-live" :
          m === "wasm" ? "cp-wasm-toggle" :
          m === "svg" ? "cp-mode-svg" : "cp-mode-png");
        if (r) r.checked = true;
        enterMode(m);
      }
      Array.prototype.forEach.call(
        document.querySelectorAll("input[name=\"cp-mode\"]"),
        function (r) {
          r.addEventListener("change", function () { if (r.checked) enterMode(r.value); });
        });
      // Keep the live canvas overlay tracking the snapshot's slot when the page reflows (the Wasm
      // overlay has its own resize hook below; this covers a live session with no Wasm app).
      window.addEventListener("resize", function () {
        if (live && live.checked && !canvas.hidden) fitLiveCanvas();
      });
      if (wasmToggle) {
        // The app posts "cp-wasm-ready" once its first frame is on the canvas — the swap signal.
        // Match on source (the known frame's contentWindow), not e.origin — robust regardless of
        // the frame's origin, and the payload is a fixed string so there's no data surface.
        window.addEventListener("message", function (e) {
          if (e.source !== wasmFrame.contentWindow || e.data !== "cp-wasm-ready") return;
          revealWasm();
        });
        // Fallback for an app build that predates the ready signal: reveal a beat after the
        // document's load event rather than holding the snapshot forever.
        wasmFrame.addEventListener("load", function () {
          setTimeout(function () { revealWasm(); }, 8000);
        });
        // The overlay tracks the snapshot's box, which moves when the page reflows — and the
        // checkerboard phase moves with it, so re-hand it to the app.
        window.addEventListener("resize", function () {
          if (!wasmActive()) return;
          positionWasmFrame();
          if (wasmReady && wasmFrame.contentWindow) {
            wasmFrame.contentWindow.postMessage(wasmOverridePatch(), "*");
          }
        });
        if (wasmBg) {
          wasmBg.addEventListener("change", function () {
            // Background is an in-browser knob: auto-enable the Wasm tier if it isn't on yet.
            if (!wasmActive()) { setMode("wasm"); return; }
            onControlsChanged();
          });
        }
      }
      if (fs) {
        fs.addEventListener("input", function () {
          fsVal.textContent = fs.value;
          fontScaleTouched = true;
          onControlsChanged();
        });
      }
      fields.forEach(function (f) {
        var el = document.getElementById("cp-" + f);
        if (el) el.addEventListener("change", onControlsChanged);
      });
      // Size mode: show only the input rows the chosen mode uses (Within shows both min + max), then
      // re-render. The number inputs re-render on "input" (live typing) like the locale field.
      var sizeMode = document.getElementById("cp-sizeMode");
      if (sizeMode) {
        var syncSizeRows = function () {
          var m = sizeMode.value;
          var show = { fixed: m === "fixed", min: m === "min" || m === "within",
            max: m === "max" || m === "within" };
          ["fixed", "min", "max"].forEach(function (g) {
            var row = document.getElementById("cp-size-" + g);
            if (row) row.hidden = !show[g];
          });
        };
        syncSizeRows();
        sizeMode.addEventListener("change", function () { syncSizeRows(); onControlsChanged(); });
        ["cp-fixedW", "cp-fixedH", "cp-minW", "cp-minH", "cp-maxW", "cp-maxH"].forEach(function (id) {
          var el = document.getElementById(id);
          if (el) el.addEventListener("input", onControlsChanged);
        });
      }
      // Overlay toggles are live-only: they push a fresh setOverrides through the open stream and do
      // nothing otherwise. They get their own handler rather than onControlsChanged so a toggle mid
      // connect (ws not yet readyState 1) can't fall through to the snapshot / wasm-auto-enable
      // branches — an overlay never applies to a baked PNG or the in-browser tier.
      function onOverlayChanged() {
        if (live.checked && ws && ws.readyState === 1) {
          ws.send(JSON.stringify({ type: "setOverrides", overrides: liveOverrides() }));
        }
      }
      Array.prototype.forEach.call(overlayToggles, function (el) {
        el.addEventListener("change", onOverlayChanged);
      });
      // Author-declared **named knobs** (label, count, colour, …) re-render on edit (text/number
      // debounce via "input", toggles "change"). Unlike the app-theme selector and detected-feature
      // toggles below, these ARE honoured by the in-browser Wasm tier (its `catalogOverride*` seeds
      // from the `knob.<key>` patch), so a knob edit drives whichever transport is live: the Wasm
      // iframe when it's active (or auto-enable it on a static published catalog), the daemon stream
      // when Live is up, or a `/render` snapshot when the session can re-render.
      function onKnobEdited() {
        refreshLinks();
        if (wasmActive()) {
          if (wasmReady && wasmFrame.contentWindow) {
            wasmFrame.contentWindow.postMessage(wasmOverridePatch(), "*");
          } else {
            wasmFrame.src = wasmInitialSrc();
          }
          return;
        }
        if (live.checked && ws && ws.readyState === 1) {
          ws.send(JSON.stringify({ type: "setOverrides", overrides: liveOverrides() }));
        } else if (canRenderOverrides) {
          refreshSnapshot();
        } else if (staticSnapshot && wasmToggle) {
          // A published catalog can't re-render on the server, but its in-browser app can apply the
          // knob — auto-enable the Wasm tier and let its load carry the edit (wasmInitialSrc bakes
          // the patch into the fragment), mirroring the display-axis auto-enable in onControlsChanged.
          setMode("wasm");
        }
      }
      document.querySelectorAll(".cp-knob").forEach(function (el) {
        el.addEventListener(el.type === "checkbox" ? "change" : "input", onKnobEdited);
      });
      // The app-theme selector and detected-feature toggles route ONLY through the server daemon —
      // an app-declared theme provider is a server-side wrapper, and focus/gesture overlays are
      // daemon-rendered, neither of which the in-browser tier can produce — so they use a
      // daemon-only handler and never the wasm path.
      function onKnobChanged() {
        refreshLinks();
        if (live.checked && ws && ws.readyState === 1) {
          ws.send(JSON.stringify({ type: "setOverrides", overrides: liveOverrides() }));
        } else if (canRenderOverrides) {
          refreshSnapshot();
        }
      }
      var themeSel = document.getElementById("cp-themeProvider");
      if (themeSel) themeSel.addEventListener("change", onKnobChanged);
      // Detected-feature toggles (Keyboard focus) re-render on the daemon like a knob — same routing,
      // never the wasm auto-enable path.
      document.querySelectorAll(".cp-feature").forEach(function (el) {
        el.addEventListener("change", onKnobChanged);
      });
      refreshSnapshot();
    })();
    """
      .trimIndent()

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
  ): String {
    // Nothing to navigate to when the list is empty or holds only the current preview.
    if (siblings.none { it.id != preview.id }) return ""
    val items =
      siblings.joinToString("\n") { p ->
        val segItem = WebEscaping.urlEncodeSegment(p.id)
        val labelItem = WebEscaping.htmlEscape(p.label)
        val idItem = WebEscaping.htmlEscape(p.id)
        // data-search folds label + id so the drawer filter matches either. aria-current pins the
        // one we're viewing (styled as active, and it stays visible even under a filter miss so the
        // list never looks empty-of-self).
        val current = if (p.id == preview.id) " aria-current=\"page\"" else ""
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

  /**
   * Toggle wiring for the two viewer drawers. Each toggle flips an open-state class on `.cp-viewer`
   * (`cp-controls-open` for the right overrides drawer, `cp-nav-open` for the left component nav)
   * and mirrors it into `aria-expanded`; the drawers themselves show/hide purely via CSS on those
   * classes. The nav's own `×` closes it, and its search box filters the component list in place.
   */
  private fun drawerScript(): String =
    """
    (function () {
      var viewer = document.querySelector(".cp-viewer");
      if (!viewer) return;
      function bindToggle(btnId, cls) {
        var btn = document.getElementById(btnId);
        if (!btn) return;
        btn.addEventListener("click", function () {
          var open = viewer.classList.toggle(cls);
          btn.setAttribute("aria-expanded", open ? "true" : "false");
        });
      }
      bindToggle("cp-controls-toggle", "cp-controls-open");
      bindToggle("cp-nav-toggle", "cp-nav-open");
      var close = document.getElementById("cp-nav-close");
      if (close)
        close.addEventListener("click", function () {
          viewer.classList.remove("cp-nav-open");
          var t = document.getElementById("cp-nav-toggle");
          if (t) t.setAttribute("aria-expanded", "false");
        });
      var search = document.getElementById("cp-nav-search");
      if (search)
        search.addEventListener("input", function () {
          var query = search.value.trim().toLowerCase();
          var items = document.querySelectorAll("#cp-nav-list .cp-nav-item");
          var shown = 0;
          for (var i = 0; i < items.length; i++) {
            var el = items[i];
            var hay = (el.getAttribute("data-search") || "").toLowerCase();
            // The current preview (aria-current) always stays; others hide on a filter miss.
            var keep = query === "" || el.hasAttribute("aria-current") || hay.indexOf(query) !== -1;
            el.parentNode.hidden = !keep;
            if (keep) shown++;
          }
          var empty = document.getElementById("cp-nav-empty");
          if (empty) empty.hidden = shown > 0;
        });
    })();
    """
      .trimIndent()

  private fun document(title: String, body: String): String =
    """
    <!doctype html>
    <html lang="en">
      <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <title>${WebEscaping.htmlEscape(title)}</title>
        <style>$STYLE</style>
        <!-- Apply the sticky Background/Transparent choice before first paint (no checkerboard flash). -->
        <script>try{if(localStorage.getItem("cp-bg")==="off")document.documentElement.classList.add("cp-bg-transparent");}catch(e){}</script>
      </head>
      <body>
        $body
      </body>
    </html>
    """
      .trimIndent() + "\n"

  /**
   * The copyable direct-link panel: the `/render/<id>.png` (and, when [hasSvgExport], `.svg`) URL
   * for the preview **with the current overrides applied**. Each row shows a read-only URL field, a
   * Copy button, and a Download link (`<a download>`). The viewer JS keeps the URLs in sync as the
   * controls / knobs change (see `refreshLinks`), so the copied URL always reflects the on-screen
   * state — a shareable, scriptable handle on the exact render (a `curl`-able PNG/SVG). The URLs
   * are built client-side from `location.origin` + the session base, so they're absolute and work
   * from anywhere; the fields start empty and are filled on first render.
   */
  private fun downloadLinksHtml(hasSvgExport: Boolean): String {
    fun row(kind: String, ext: String): String =
      """
      <div class="cp-link-row">
        <span class="cp-link-kind">$kind</span>
        <input id="cp-url-$ext" class="cp-url" type="text" readonly aria-label="$kind URL">
        <button type="button" class="cp-copy" data-copy-target="cp-url-$ext">Copy</button>
        <a id="cp-dl-$ext" class="cp-dl" download>Download</a>
      </div>
      """
        .trimIndent()
    val svgRow = if (hasSvgExport) "\n" + row("SVG", "svg") else ""
    return """
      <div class="cp-links">
        <div class="cp-knobs-head">Direct links — the current view as a URL (overrides applied)</div>
        ${row("PNG", "png")}$svgRow
      </div>
      """
      .trimIndent()
  }

  /**
   * Renders the preview's author-declared editable knobs (the `compose/overrides` payload carried
   * in a bundle's `previews/<id>.overrides.json`) as a labelled control list. Indexed knobs
   * (per-item values on a repeated component) are grouped under their base key with a `#<index>`
   * suffix. The controls are live when [canApplyOverrides] (a daemon re-renders the edit) **or**
   * [wasmAvailable] (the in-browser catalog app seeds its `catalogOverride*` from the edit); a
   * plain static bundle with neither leaves them disabled with a one-line note. Empty string when
   * the preview declared no knobs (the common case).
   */
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
        val attrs =
          "class=\"cp-knob\" data-knob-key=\"$wireKey\" data-knob-kind=\"$kind\" " +
            "data-knob-initial=\"$initial\""
        if (bool) {
          val checked = if (value == "true" || value == "1") " checked" else ""
          "<label class=\"cp-live-row\"><input type=\"checkbox\" $attrs$checked$dis> $label</label>"
        } else {
          val inputType = if (d.type == "int" || d.type == "float") "number" else "text"
          """
          <label>${label}
            <input type="$inputType" $attrs value="$value"$dis>
          </label>
          """
            .trimIndent()
        }
      }
    val note =
      when {
        canApplyOverrides -> "Declared overrides — edit a value to re-render."
        wasmAvailable -> "Declared overrides — edit a value to apply it in the browser (Wasm)."
        else -> "Declared overrides — static bundle, values are baked in."
      }
    return """
      <div class="cp-knobs">
        <div class="cp-knobs-head">$note</div>
        $rows
      </div>
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

  private val COMMON_DEVICES: List<Pair<String, String>> =
    listOf(
      "id:pixel_5" to "Pixel 5",
      "id:pixel_7" to "Pixel 7",
      "id:pixel_tablet" to "Pixel Tablet",
      "id:pixel_fold" to "Pixel Fold",
      "id:wearos_small_round" to "Wear OS (small round)",
      "id:tv_1080p" to "TV 1080p",
    )
}
