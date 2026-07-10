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
    .cp-syslist .cp-imgwrap { min-height: 180px; }
    .cp-sys-title { font-size: 0.98rem; font-weight: 600; }
    .cp-sys-desc { margin-top: 4px; font-size: 0.76rem; color: #6b6b70; line-height: 1.4; }
    .cp-sys-foot { margin-top: 8px; font-size: 0.74rem; color: #6b6b70; }
    .cp-sys-noimg { font-size: 0.78rem; color: #a0a0a8; }
    .cp-card { border: 1px solid #e3e3e8; border-radius: 10px; overflow: hidden; background: #fff;
      display: block; color: inherit; }
    .cp-card[hidden] { display: none; }
    .cp-imgwrap { display: flex; align-items: center; justify-content: center; min-height: 140px;
      background: repeating-conic-gradient(#f4f4f6 0% 25%, #fff 0% 50%) 50% / 16px 16px; padding: 8px; }
    .cp-imgwrap img { max-width: 100%; height: auto; display: block; }
    .cp-meta { padding: 8px 10px; font-size: 0.82rem; }
    .cp-label { font-weight: 500; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .cp-id { color: #6b6b70; font-size: 0.7rem; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .cp-viewer { display: flex; gap: 24px; flex-wrap: wrap; align-items: flex-start; }
    .cp-stage { position: relative; flex: 1 1 360px; min-width: 280px; border: 1px solid #e3e3e8;
      border-radius: 10px;
      background: repeating-conic-gradient(#f4f4f6 0% 25%, #fff 0% 50%) 50% / 16px 16px; padding: 12px;
      display: flex; align-items: center; justify-content: center; min-height: 320px; }
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
    .cp-note { font-size: 0.75rem; color: #6b6b70; line-height: 1.4; padding: 8px 10px;
      border-radius: 8px; background: #f4f4f6; }
    .cp-controls label:has(:disabled) { opacity: 0.55; }
    .cp-knobs { border-top: 1px solid #e3e3e8; padding-top: 12px; display: flex; flex-direction: column; gap: 8px; }
    .cp-knobs-head { font-size: 0.72rem; color: #6b6b70; }
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
      .cp-imgwrap, .cp-stage { background: repeating-conic-gradient(#26262b 0% 25%, #1d1d20 0% 50%) 50% / 16px 16px; }
      .cp-badge--trusted { background: #14361f; color: #6cd98a; border-color: #2c6b40; }
      .cp-badge--unverified { background: #3a2a12; color: #e6b067; border-color: #6b4f24; }
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
   * (always that), else the server-supplied `data-live-backend` / `data-snapshot-backend` label, so
   * the daemon's actual platform (desktop/JVM or Android) and the snapshot renderer stay accurate.
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
            "Theme, Font scale, Locale &amp; background apply in the browser. Device/Orientation " +
            "need the live server. <a href=\"$LOCAL_SERVER_DOCS\">Enable a local preview server.</a>" +
            "</div>"
        else ->
          "<div class=\"cp-note\">Pre-rendered snapshot — overrides (device, locale, font scale, " +
            "orientation) need the live server, not a published catalog. " +
            "<a href=\"$LOCAL_SERVER_DOCS\">Enable a local preview server.</a></div>"
      }
    val backendLabel = WebEscaping.htmlEscape(snapshotBackend ?: "Snapshot")
    val liveLabel = WebEscaping.htmlEscape(liveBackend ?: "Live")
    val body =
      """
      <p class="cp-head"><a href="$basePath/$q">← previews</a>${trustBadge(trust)}</p>
      <p class="cp-sub" title="$idText">$label</p>
      <div class="cp-viewer" data-preview-id="$idText" data-mode="snapshot" data-modes="$modes" data-static-snapshot="$staticSnapshot" data-can-render-overrides="$canRenderOverrides" data-snapshot-backend="$backendLabel" data-live-backend="$liveLabel"$wasmAttr>
        <div class="cp-stage"><span class="cp-backend" id="cp-backend"></span><img id="cp-img" alt="$label"><canvas id="cp-canvas" hidden></canvas>$wasmFrame</div>
        <div class="cp-controls">
          $snapshotNote
          <div class="cp-modes" role="radiogroup" aria-label="Render mode">
            <label class="cp-live-row"><input type="radio" name="cp-mode" value="png" id="cp-mode-png" checked> PNG</label>
            <label class="cp-live-row"><input type="radio" name="cp-mode" value="live" id="cp-live"$liveDis> Live Compose</label>
            $wasmModeRadio
          </div>
          $wasmBgRow
          <label>Theme
            <select id="cp-uiMode"$wasmDis>
              <option value="">(default)</option>
              <option value="light">Light</option>
              <option value="dark">Dark</option>
            </select>
          </label>
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
          ${overrideKnobsHtml(preview, canApplyOverrides || canRenderOverrides)}
          ${downloadLinksHtml(hasSvgExport)}
          <div class="cp-status" id="cp-status"></div>
        </div>
      </div>
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
      var live = document.getElementById("cp-live");
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
      // The selects + text input are opt-in (empty value = "use the preview's default"). The font
      // scale slider has no empty state, so it's gated separately: we only send fontScale once the
      // user moves it (fontScaleTouched), otherwise the slider's standing 1.0 would override a
      // preview's declared default font scale and the first render wouldn't match the thumbnail.
      var fields = ["uiMode", "device", "localeTag", "orientation", "background"];
      var fs = document.getElementById("cp-fontScale");
      var fsVal = document.getElementById("cp-fontScale-val");
      var fontScaleTouched = false;
      var ws = null;

      function overrides() {
        var o = {};
        fields.forEach(function (f) {
          var el = document.getElementById("cp-" + f);
          if (el && el.value) o[f] = el.value;
        });
        if (fontScaleTouched && fs) o.fontScale = fs.value;
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
        return parts.join("&");
      }
      function refreshSnapshot() {
        status.textContent = "rendering…";
        var qs = query();
        var url = base + "/render/" + encodeURIComponent(previewId) + ".png" + (qs ? "?" + qs : "");
        var next = new Image();
        next.onload = function () { img.src = url; status.textContent = ""; };
        next.onerror = function () { status.textContent = "render failed"; };
        next.src = url;
        refreshLinks();
      }
      // The copyable direct-link panel: rebuild the absolute /render URLs (PNG + optional SVG) from
      // the current controls so a copied/downloaded link reproduces exactly what's on screen. Built
      // on location.origin so the link is absolute (curl-able / shareable), and kept in sync on
      // every control or knob change — even the ones that don't re-render the snapshot themselves.
      function renderUrl(ext) {
        var qs = query();
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
        ws = new WebSocket(proto + "//" + location.host + base + "/ws/" +
          encodeURIComponent(previewId) + "?" + (qs ? qs + "&codec=webp" : "codec=webp"));
        ws.onmessage = function (ev) {
          var m;
          try { m = JSON.parse(ev.data); } catch (e) { return; }
          if (m.type === "frame") { drawFrame(m.dataBase64, m.codec); status.textContent = ""; }
          else if (m.type === "error") { status.textContent = m.message || "error"; }
        };
        ws.onerror = function () { status.textContent = "stream error"; };
        ws.onclose = function () { ws = null; };
      }
      function closeStream() {
        root.setAttribute("data-mode", "snapshot");
        if (ws) { ws.close(); ws = null; }
        canvas.hidden = true;
        // Tear down the overlay: drop the absolute positioning and restore the snapshot img's slot.
        canvas.classList.remove("cp-canvas-live");
        canvas.style.removeProperty("left"); canvas.style.removeProperty("top");
        canvas.style.removeProperty("width"); canvas.style.removeProperty("height");
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
      // Swap the stage from the snapshot to the (already-painted) Wasm frame. The snapshot keeps
      // its layout slot (visibility, not display) so the stage geometry — and the overlay tracking
      // it — never shifts.
      function revealWasm() {
        if (!wasmActive() || wasmReady) return;
        wasmReady = true;
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
      }
      function closeWasm() {
        // No Wasm iframe (non-Wasm session) ⇒ nothing to tear down; enterMode() still calls this
        // unconditionally when switching to Live/PNG, so guard against the null frame.
        if (!wasmFrame) return;
        root.setAttribute("data-mode", "snapshot");
        wasmReady = false;
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

      // Render-mode radio group (PNG / Live Compose / Wasm). Selecting one tears down the other
      // transport and enters the chosen one; PNG is the baked snapshot. closeStream / closeWasm are
      // idempotent, so a mode switch can safely tear down both regardless of the prior state.
      function enterMode(m) {
        if (m === "live") { closeWasm(); openStream(); }
        else if (m === "wasm") { closeStream(); openWasm(); }
        else { closeStream(); closeWasm(); refreshSnapshot(); }
      }
      // Programmatic switch (e.g. a wasm-only control auto-enabling Wasm): tick the radio so the UI
      // reflects it, then run the transition.
      function setMode(m) {
        var r = document.getElementById(
          m === "live" ? "cp-live" : m === "wasm" ? "cp-wasm-toggle" : "cp-mode-png");
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
        if (live && live.checked && !canvas.hidden) positionOverlay(canvas);
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
      // Author-declared knobs re-render on edit (text/number debounce via "input", toggles "change").
      // They route differently from the display axes: a named knob (label, a color, …) is honoured
      // ONLY by the server daemon — the in-browser Wasm tier's `catalogOverride*` returns the author
      // default — so a knob edit must hit /render (onKnobChanged), never the wasm auto-enable path.
      function onKnobChanged() {
        refreshLinks();
        if (live.checked && ws && ws.readyState === 1) {
          ws.send(JSON.stringify({ type: "setOverrides", overrides: liveOverrides() }));
        } else if (canRenderOverrides) {
          refreshSnapshot();
        }
      }
      document.querySelectorAll(".cp-knob").forEach(function (el) {
        el.addEventListener(el.type === "checkbox" ? "change" : "input", onKnobChanged);
      });
      refreshSnapshot();
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
   * suffix. The controls are disabled when [canApplyOverrides] is false — a static bundle replays
   * baked PNGs and can't re-render — with a one-line note explaining why; the live daemon-backed
   * re-render loop for these knobs is a follow-up. Empty string when the preview declared no knobs
   * (the common case).
   */
  private fun overrideKnobsHtml(preview: ServePreview, canApplyOverrides: Boolean): String {
    if (preview.overrides.isEmpty()) return ""
    // Editable only on a daemon-backed session (canApplyOverrides) — a static bundle / the Wasm
    // tier
    // can't re-render with new knob values, so there the controls show *what* is editable but stay
    // disabled. The viewer JS collects `.cp-knob` values into `knob.<key>=<kind>:<value>` params.
    val dis = if (canApplyOverrides) "" else " disabled"
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
      if (canApplyOverrides) "Declared overrides — edit a value to re-render."
      else "Declared overrides — static bundle, values are baked in."
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

  /** Human text for a [ee.schimke.composeai.daemon.protocol.PreviewOverrideValue] in the viewer. */
  private fun overrideValueText(
    v: ee.schimke.composeai.daemon.protocol.PreviewOverrideValue
  ): String =
    when (v) {
      is ee.schimke.composeai.daemon.protocol.PreviewOverrideValue.StringValue -> v.value
      is ee.schimke.composeai.daemon.protocol.PreviewOverrideValue.IntValue -> v.value.toString()
      is ee.schimke.composeai.daemon.protocol.PreviewOverrideValue.FloatValue -> v.value.toString()
      is ee.schimke.composeai.daemon.protocol.PreviewOverrideValue.BooleanValue ->
        v.value.toString()
      is ee.schimke.composeai.daemon.protocol.PreviewOverrideValue.ColorValue -> v.argb
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
