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
    .cp-states { display: inline-flex; flex-wrap: wrap; gap: 6px; margin: 0 0 10px; }
    .cp-state-btn { border: 1px solid #d7d7de; border-radius: 999px; background: #fff; color: #6b6b70;
      font: inherit; font-size: 0.78rem; padding: 3px 14px; cursor: pointer; text-decoration: none; }
    .cp-state-btn:hover { border-color: #b9b9c6; color: #3a3a8a; }
    .cp-state-btn[aria-current="page"] { background: #ececff; border-color: #c5c5f0; color: #3a3a8a; font-weight: 600; }
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
    /* A framed thumbnail: a fixed-size clip window (inline width/height = the component box) that
       crops away a sticker's empty watch canvas. The render <img> inside is absolutely positioned,
       sized + offset inline so only the component shows. Overrides the fit-to-box rule above. */
    .cp-crop { position: relative; overflow: hidden; display: block; }
    .cp-imgwrap .cp-crop img { position: absolute; max-width: none; max-height: none; margin: 0; }
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
    html:not(.cp-bg-transparent) .cp-card[data-bg-theme="light"] .cp-imgwrap { background: #fff; }
    html:not(.cp-bg-transparent) .cp-card[data-bg-theme="dark"] .cp-imgwrap { background: #1d1d20; }
    /* The single-preview viewer's stage follows the preview's background theme the same way the grid
       thumbnails do (data-bg-theme on .cp-viewer, kept SEPARATE from the explicit-only
       data-card-theme filter axis): a dark variant — or any preview in a dark-first system like Wear
       — sits on a dark stage so a light-on-transparent render stays readable. The viewer JS keeps
       data-bg-theme in step with the chosen Theme (uiMode) so it doesn't clash after a re-render.
       Solid mode only; the Transparent checkerboard still wins via cp-bg-transparent. */
    html:not(.cp-bg-transparent) .cp-viewer[data-bg-theme="dark"] .cp-stage { background: #1d1d20; }
    html:not(.cp-bg-transparent) .cp-viewer[data-bg-theme="light"] .cp-stage { background: #fff; }
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
      background: #fff; color: #1b1b1f; cursor: pointer; }
    .cp-url.cp-url-copied { border-color: #5b5bd6; box-shadow: 0 0 0 2px rgba(91, 91, 214, 0.25); }
    .cp-copyimg, .cp-dl { font-size: 0.72rem; padding: 4px 8px; border-radius: 6px; border: 1px solid #d7d7de;
      background: #fff; color: #5b5bd6; cursor: pointer; text-decoration: none; flex: none; white-space: nowrap; }
    .cp-copyimg:hover, .cp-dl:hover { background: #f0f0f3; }
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
    /* Collapsible override groups: each axis-set is a <details class="cp-group"> the viewer
       expands/collapses on its own. The summary is the group title with a rotating disclosure
       caret; the body holds the labelled controls. Replaces the old flat control stack so the
       overrides panel reads as a small set of named, foldable groups. */
    .cp-group { border: 1px solid #e3e3e8; border-radius: 8px; background: #fff; }
    .cp-group > summary { cursor: pointer; list-style: none; display: flex; align-items: center;
      gap: 6px; padding: 8px 10px; font-size: 0.78rem; font-weight: 600; color: #45454c;
      user-select: none; }
    .cp-group > summary::-webkit-details-marker { display: none; }
    .cp-group > summary::before { content: "\25B8"; font-size: 0.72rem; color: #8a8a92;
      transition: transform 0.12s ease; }
    .cp-group[open] > summary::before { transform: rotate(90deg); }
    .cp-group > summary:hover { color: #3a3a8a; }
    .cp-group-body { display: flex; flex-direction: column; gap: 12px; padding: 0 10px 12px; }
    /* The single Static⇄Live preview toggle (replaces the old PNG/SVG/Live/Wasm radio row). Off =
       the baked / on-demand snapshot; on = the interactive lane (daemon stream, or the in-browser
       Wasm app). The hidden mode radios behind it are what the transport JS actually reads. */
    .cp-preview-mode { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
    .cp-live-toggle { display: inline-flex; align-items: center; gap: 7px; font: inherit;
      font-size: 0.8rem; font-weight: 600; padding: 6px 14px; border-radius: 999px;
      border: 1px solid #d7d7de; background: #fff; color: #45454c; cursor: pointer; }
    .cp-live-toggle:hover:not(:disabled) { background: #f0f0f3; }
    .cp-live-toggle:disabled { opacity: 0.5; cursor: default; }
    .cp-live-toggle[aria-pressed="true"] { background: #e7f4ea; border-color: #b6e0c2; color: #1e7a34; }
    .cp-live-dot { width: 9px; height: 9px; border-radius: 50%; background: #b9b9c6; flex: none; }
    .cp-live-toggle[aria-pressed="true"] .cp-live-dot { background: #1e9c3f;
      box-shadow: 0 0 0 3px rgba(30, 156, 63, 0.2); }
    .cp-mode-hint { font-size: 0.72rem; color: #6b6b70; }
    /* The mode radios are kept in the DOM (the transport JS drives them) but visually removed —
       the single toggle above is the only visible mode control. */
    .cp-modes-inputs { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0 0 0 0); }
    /* Backend badge flips its accent green when a live lane drives the stage (see backendBadgeScript). */
    .cp-backend[data-live="true"] { background: rgba(30, 122, 52, 0.82); }
    @media (prefers-color-scheme: dark) {
      body { color: #e6e6e9; background: #161618; }
      .cp-sub, .cp-id, .cp-status, .cp-about-links, .cp-sys-desc, .cp-sys-foot { color: #a0a0a8; }
      .cp-card, .cp-stage, .cp-knobs, .cp-links, .cp-about { border-color: #34343a; }
      .cp-url { background: #1d1d20; color: #e6e6e9; border-color: #34343a; }
      .cp-url.cp-url-copied { border-color: #8f8ff0; box-shadow: 0 0 0 2px rgba(143, 143, 240, 0.3); }
      .cp-copyimg, .cp-dl { background: #1d1d20; border-color: #34343a; }
      .cp-copyimg:hover, .cp-dl:hover { background: #26262b; }
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
      .cp-state-btn { background: #1d1d20; color: #a0a0a8; border-color: #34343a; }
      .cp-state-btn:hover { border-color: #4a4a55; color: #c9c9ff; }
      .cp-state-btn[aria-current="page"] { background: #26264a; border-color: #3a3a6a; color: #c9c9ff; }
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
      .cp-group { background: #1d1d20; border-color: #34343a; }
      .cp-group > summary { color: #c9c9d0; }
      .cp-group > summary:hover { color: #c9c9ff; }
      .cp-live-toggle { background: #1d1d20; border-color: #34343a; color: #c9c9d0; }
      .cp-live-toggle:hover:not(:disabled) { background: #26262b; }
      .cp-live-toggle[aria-pressed="true"] { background: #14361f; border-color: #2c6b40; color: #6cd98a; }
      .cp-mode-hint { color: #a0a0a8; }
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
   * A dark-first design system draws its components for a dark surface (Wear OS is
   * black-watch-face-first), so a preview with no explicit light/dark token should sit on the DARK
   * stage — otherwise a light-on-transparent Wear render lands on the default white stage and its
   * light text is unreadable. Keyed off the served system name — the `/<system>` path mount
   * ([basePath]) or, for the legacy `?session=` form, the session id.
   */
  private fun isDarkFirstSystem(basePath: String, sessionId: String?): Boolean {
    val system = basePath.trim('/').ifBlank { sessionId ?: "" }
    return system.startsWith("wear", ignoreCase = true)
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
   * Whether [p] is a **non-default** component state render (`unchecked`, `pressed`, `disabled`,
   * `unselected`, …) — a render the grid folds out so each component shows a single (default) card,
   * with its other states reachable via the viewer's [state switcher][stateSwitcherHtml]. Keyed off
   * the catalog's `state` metadata (from `variants.json`), not the id: a stateless preview / plain
   * bundle screen has `state == null` and is treated as default (always shown).
   */
  private fun isNonDefaultState(p: ServePreview): Boolean = p.state != null && p.state != "default"

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
  private fun stateInvariantKey(p: ServePreview): String {
    val state = p.state ?: return p.id
    val parts = p.id.split("__")
    val idealIdx = parts.indexOf("ideal")
    val stateIdx =
      if (idealIdx in 0 until parts.lastIndex && parts[idealIdx + 1] == state) idealIdx + 1
      else parts.indexOfFirst { it == state }.takeIf { it >= 1 } ?: return p.id
    return parts.filterIndexed { i, _ -> i != stateIdx }.joinToString("__")
  }

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

  /**
   * The sticky light/dark control for the catalog header. Persists to `localStorage['cp-theme']`
   * (shared with the viewer's Theme select). [catalogFilterScript] wires it to *swap* each
   * swappable card between its baked light/dark render in place — a no-JS client still sees the
   * full catalog on its default renders. Shown only when the grid has at least one light/dark pair
   * to swap.
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
   * Landing-grid controls: the search box (matches a card's label + id, case-insensitive) and, when
   * the catalog carries light/dark pairs, the sticky Light/Dark **toggle** — which *swaps* each
   * swappable card between its baked light and dark render in place (image, viewer link, id, label,
   * and stage backing), rather than hiding cards. Single-theme / theme-neutral cards carry no swap
   * data and are left untouched. Theme state persists to the shared `localStorage['cp-theme']` key
   * (round-tripped with the viewer's Theme select); the search text is ephemeral. Fully client-side
   * progressive enhancement — a no-JS client sees the full grid on its baked (default) renders.
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
    // Swap every swappable card to the chosen theme's baked render (src / viewer href / id / label
    // /
    // stage backing), and light up the pressed button. A card missing the chosen theme is skipped.
    val applyTheme =
      if (hasThemes)
        """
        themeBtns.forEach(function (b) {
          b.setAttribute("aria-pressed", b.getAttribute("data-theme-choice") === theme ? "true" : "false");
        });
        var k = theme === "dark" ? "d" : "l";
        cards.forEach(function (c) {
          if (c.getAttribute("data-swap") !== "1") return;
          var src = c.getAttribute("data-" + k + "-src");
          if (!src) return;
          var img = c.querySelector("img");
          var lab = c.querySelector(".cp-label");
          var idn = c.querySelector(".cp-id");
          var lbl = c.getAttribute("data-" + k + "-label");
          if (img) { img.src = src; img.setAttribute("alt", lbl); }
          c.setAttribute("href", c.getAttribute("data-" + k + "-href"));
          if (lab) { lab.textContent = lbl; lab.setAttribute("title", lbl); }
          if (idn) idn.textContent = c.getAttribute("data-" + k + "-id");
          c.setAttribute("data-bg-theme", theme);
        });
        """
          .trimIndent()
      else ""
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
        $applyTheme
        var q = input ? input.value.trim().toLowerCase() : "";
        var shown = 0;
        cards.forEach(function (c) {
          var lab = c.querySelector(".cp-label");
          var idn = c.querySelector(".cp-id");
          var hay = ((lab ? lab.textContent : "") + " " + (idn ? idn.textContent : "")).toLowerCase();
          var searchOk = q === "" || hay.indexOf(q) !== -1;
          c.hidden = !searchOk;
          if (searchOk) shown++;
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
      // Round-trip: a Theme change writes the shared key so the catalog remembers it, and re-syncs
      // the stage backing colour.
      el.addEventListener("change", function () {
        if (el.value === "light" || el.value === "dark") {
          try { localStorage.setItem("cp-theme", el.value); } catch (e) {}
        }
        syncBg();
      });
      syncBg();
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
      // The badge carries an icon that flips with the lane: ▶ for an interactive live lane (the
      // daemon stream or the in-browser Wasm app), ▪ for the static snapshot — the visible signal
      // that the Static⇄Live toggle changed state (paired with a green accent via data-live).
      function isLive(mode) { return mode === "wasm" || mode === "live"; }
      function label(mode) {
        if (mode === "wasm") return "▶ CMP-WASM";
        if (mode === "live") return "▶ " + (root.getAttribute("data-live-backend") || "Live");
        return "▪ " + (root.getAttribute("data-snapshot-backend") || "Snapshot");
      }
      function refresh() {
        var mode = root.getAttribute("data-mode");
        badge.textContent = label(mode);
        badge.setAttribute("data-live", isLive(mode) ? "true" : "false");
      }
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
    /** Content-crop for the hero thumbnail (frames a Wear sticker to its component); null ⇒ raw. */
    val heroCrop: ContentCrop? = null,
  )

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
    val cropped =
      "<img$extraImgAttrs alt=\"$alt\" src=\"$src\" " +
        "style=\"width:${crop.imgW}px;height:${crop.imgH}px;left:${crop.left}px;top:${crop.top}px\">"
    return "<span class=\"cp-crop\" style=\"width:${crop.boxW}px;height:${crop.boxH}px\">$cropped</span>"
  }

  /**
   * The public preview server's **front door**: an index of the systems it publishes, each a card
   * carrying a meaningful preview, the system's title + library, its trust badge, and a link to its
   * `/<system>/` catalog. This replaces showing an arbitrary default module's previews at `/` (the
   * point of `preview.coo.ee` is the catalogs, so the landing lists them rather than hiding them
   * behind a nav pill). Non-catalog `serve` (no `--catalogs`) keeps the plain [landingPage].
   *
   * [systems] are the published design systems (the `--catalogs` set) shown under "Design systems";
   * [apps] are the app catalogs (the `--catalogs-unlisted` set, e.g. meshcore-mobile / cadence)
   * shown under a separate "Apps" section so they surface on the front door too — while staying off
   * the in-catalog "Design systems" nav row. Either group may be empty.
   */
  fun homeIndexPage(
    systems: List<HomeSystem>,
    token: String,
    isPublic: Boolean = false,
    apps: List<HomeSystem> = emptyList(),
  ): String {
    val about = if (isPublic) aboutSection() + "\n" else ""
    // Public routes are open — no token param on the cards; a token-gated box keeps it.
    val suffix = querySuffix(if (isPublic) "" else "token=" + WebEscaping.urlEncodeSegment(token))
    fun card(s: HomeSystem): String {
      val sysSeg = WebEscaping.urlEncodeSegment(s.system)
      val title = WebEscaping.htmlEscape(s.title)
      val sysId = WebEscaping.htmlEscape(s.system)
      val img =
        if (s.heroPreviewId != null) {
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
      return """
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
    fun section(heading: String, list: List<HomeSystem>, noun: String, gridId: String): String =
      """
      <p class="cp-head">$heading</p>
      <p class="cp-sub">${list.size} $noun · pick one to browse its components and
        open a live, customisable preview.</p>
      <div class="cp-grid cp-syslist" id="$gridId">
      ${list.joinToString("\n") { card(it) }}
      </div>
      """
        .trimIndent()
    val body =
      if (systems.isEmpty() && apps.isEmpty()) {
        "<p class=\"cp-head\">Design systems</p>\n" +
          "<p class=\"cp-sub\">No design systems are configured on this server.</p>"
      } else {
        buildList {
            if (systems.isNotEmpty())
              add(section("Design systems", systems, "design system(s)", "cp-grid"))
            if (apps.isNotEmpty()) add(section("Apps", apps, "app(s)", "cp-grid-apps"))
          }
          .joinToString("\n")
      }
    return document(
      title = "Design systems — compose-preview",
      body =
        """
        $about$body
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
    /**
     * Per-preview thumbnail content-crop lookup — frames a card's render to its component box (a
     * Wear sticker on a 454² watch canvas shows just the component). Returns null for a card that
     * should show the raw render (no figma-svg, or a render already tight to the component). The
     * default `{ null }` keeps every card uncropped — used by the plain-module landing and by
     * tests.
     */
    thumbCrop: (String) -> ContentCrop? = { null },
  ): String {
    val q = querySuffix(linkQuery(token, sessionId, basePath, isPublic))
    // A dark-first system (Wear) puts every unthemed card on the dark stage; explicit light/dark
    // variants keep their own token. Only affects the background — the Light/Dark filter axis below
    // still keys off the explicit-only [cardTheme].
    val darkFirst = isDarkFirstSystem(basePath, sessionId)
    // Collapse per-theme variants into one card each so the Light/Dark control swaps a card between
    // its baked light/dark render *in place*, rather than filtering two cards. A single-theme /
    // theme-neutral card carries no swap data and the toggle leaves it alone.
    // Fold non-default component states (unchecked/pressed/disabled/…) out of the grid first, so a
    // component shows ONE card (its default state) instead of a card per state; the folded states
    // stay reachable through the viewer's state switcher. Stateless previews (plain bundle screens)
    // have no state and pass straight through.
    val groups = groupPreviews(previews.filterNot { isNonDefaultState(it) })
    fun renderSrc(p: ServePreview) = "$basePath/render/${WebEscaping.urlEncodeSegment(p.id)}.png$q"
    fun viewerHref(p: ServePreview) = "$basePath/p/${WebEscaping.urlEncodeSegment(p.id)}$q"
    fun swapCard(card: GridCard): String {
      val l = card.light!!
      val d = card.dark!!
      // Default to the light render (dark-first systems open dark); the JS re-swaps to the sticky
      // choice on load. Each theme's src / viewer href / id / label ride as data-* so the swap
      // needs
      // no URL-building in the browser.
      val def = if (darkFirst) d else l
      val defTheme = if (darkFirst) "dark" else "light"
      return """
        <a class="cp-card" data-swap="1" data-bg-theme="$defTheme"
          data-l-src="${renderSrc(l)}" data-l-href="${viewerHref(l)}"
          data-l-id="${WebEscaping.htmlEscape(l.id)}" data-l-label="${WebEscaping.htmlEscape(l.label)}"
          data-d-src="${renderSrc(d)}" data-d-href="${viewerHref(d)}"
          data-d-id="${WebEscaping.htmlEscape(d.id)}" data-d-label="${WebEscaping.htmlEscape(d.label)}"
          href="${viewerHref(def)}">
          <div class="cp-imgwrap">
            <img loading="lazy" alt="${WebEscaping.htmlEscape(def.label)}" src="${renderSrc(def)}">
          </div>
          <div class="cp-meta">
            <div class="cp-label" title="${WebEscaping.htmlEscape(def.label)}">${WebEscaping.htmlEscape(def.label)}</div>
            <div class="cp-id">${WebEscaping.htmlEscape(def.id)}</div>
          </div>
        </a>
        """
        .trimIndent()
    }
    fun singleCard(p: ServePreview): String {
      val idSeg = WebEscaping.urlEncodeSegment(p.id)
      val label = WebEscaping.htmlEscape(p.label)
      val idText = WebEscaping.htmlEscape(p.id)
      // data-bg-theme is the thumbnail's background (explicit token, else the dark-first default).
      val bgAttr = bgTheme(p.id, darkFirst)?.let { " data-bg-theme=\"$it\"" } ?: ""
      return """
          <a class="cp-card"$bgAttr href="$basePath/p/$idSeg$q">
            <div class="cp-imgwrap">
              ${thumbImg("$basePath/render/$idSeg.png$q", label, " loading=\"lazy\"", thumbCrop(p.id))}
            </div>
            <div class="cp-meta">
              <div class="cp-label" title="$idText">$label</div>
              <div class="cp-id">$idText</div>
            </div>
          </a>
          """
        .trimIndent()
    }
    val cards =
      if (groups.isEmpty()) {
        "<p class=\"cp-sub\">No previews discovered in this module.</p>"
      } else {
        groups.joinToString("\n") { if (it.swappable) swapCard(it) else singleCard(it.default) }
      }
    val about = if (isPublic) aboutSection() + "\n" else ""
    val nav =
      if (catalogs.isNotEmpty()) catalogNav(catalogs, token, sessionId, isPublic) + "\n" else ""
    // The Light/Dark toggle shows only when at least one component is baked in BOTH themes, i.e.
    // the
    // grid has something to swap. A catalog with no light/dark pairs (mostly theme-neutral app
    // screens) never sprouts a control that would do nothing.
    val hasThemes = groups.any { it.swappable }
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
    // The render mode is a single Static⇄Live toggle now, not a radio row. Behind it sit the mode
    // radios the transport JS still drives (`cp-mode-png` = static snapshot, `cp-live` = daemon
    // stream, `cp-wasm-toggle` = in-browser Wasm) — kept in the DOM but visually removed. SVG is no
    // longer an on-screen mode; it's an export format in the Direct-links group. The Wasm radio is
    // present only when a Wasm app backs the session.
    val wasmModeInput =
      if (wasmSrc != null)
        "<input type=\"radio\" name=\"cp-mode\" value=\"wasm\" id=\"cp-wasm-toggle\" tabindex=\"-1\">"
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
    val liveDis = if (hasLiveStream) "" else " disabled"
    // Whether the single Static⇄Live preview toggle has any interactive lane to switch to — the
    // daemon stream ([hasLiveStream]) or the in-browser Wasm app ([wasmSrc]). Disabled (with the
    // note) on a pure static bundle with neither.
    val liveToggleDis = if (hasLiveStream || wasmSrc != null) "" else " disabled"
    // Controls the in-browser Wasm app also honours — day/night (uiMode), font scale (density),
    // locale (layout direction): live whenever the server can render an override OR a Wasm app backs
    // the session.
    val wasmDis = if (overridesLive || wasmSrc != null) "" else " disabled"
    // The static-snapshot note is only shown when overrides genuinely can't re-render on the server
    // ([overridesLive] false): a plain static bundle, or a Wasm-only published catalog (where
    // day/night, font scale, locale &amp; knobs apply in the browser but size/device/orientation
    // need a live server). A catalog whose carried daemon re-renders on demand ([overridesLive]
    // true) needs no note — its controls all take effect.
    val snapshotNote =
      when {
        overridesLive -> ""
        wasmSrc != null ->
          "<div class=\"cp-note\">Pre-rendered snapshot — turn on <strong>Live preview</strong> to " +
            "interact. Day/Night, Font scale, Locale, background &amp; declared knob values apply in " +
            "the browser; Size, Device &amp; Orientation need the live server. " +
            "<a href=\"$LOCAL_SERVER_DOCS\">Enable a local preview server.</a></div>"
        else ->
          "<div class=\"cp-note\">Pre-rendered snapshot — overrides (size, device, locale, font " +
            "scale, orientation) need the live server, not a published catalog. " +
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
        <label>Theme
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
    // Stage background follows the preview's theme (dark variant → dark stage), with a dark-first
    // system (Wear) defaulting to dark — see the `.cp-viewer[data-bg-theme] .cp-stage` CSS. Kept
    // separate from the filter's data-card-theme; the viewer JS re-syncs it on a Theme (uiMode)
    // change so a re-render in the opposite theme doesn't clash with a stale backing color.
    val bgThemeAttr =
      bgTheme(preview.id, isDarkFirstSystem(basePath, sessionId))?.let { " data-bg-theme=\"$it\"" }
        ?: ""
    // The component-state switcher: plain links to this component's other baked states (same
    // theme).
    // Empty for a single-state component / a stateless preview, so nothing renders there.
    val stateSwitcher = stateSwitcherHtml(preview, siblings, basePath, q)
    val body =
      """
      <p class="cp-head"><a href="$basePath/$q">← previews</a>${trustBadge(trust)}</p>
      <p class="cp-sub" title="$idText">$label</p>
      $stateSwitcher
      <div class="cp-viewer-bar">
        $navToggle
        <button type="button" class="cp-drawer-toggle" id="cp-controls-toggle" aria-expanded="true" aria-controls="cp-controls">⚙ Overrides</button>
      </div>
      <div class="cp-viewer cp-controls-open"$bgThemeAttr data-preview-id="$idText" data-mode="snapshot" data-modes="$modes" data-static-snapshot="$staticSnapshot" data-can-render-overrides="$canRenderOverrides" data-snapshot-backend="$backendLabel" data-live-backend="$liveLabel" data-render-density="$RENDER_DENSITY"$wasmAttr>
        $navDrawer
        <div class="cp-stage"><span class="cp-backend" id="cp-backend"></span><img id="cp-img" alt="$label"><canvas id="cp-canvas" hidden></canvas>$wasmFrame<div class="cp-error" id="cp-error" role="alert" hidden></div></div>
        <div class="cp-controls" id="cp-controls">
          $snapshotNote
          <div class="cp-preview-mode">
            <button type="button" id="cp-live-toggle" class="cp-live-toggle" aria-pressed="false"$liveToggleDis>
              <span class="cp-live-dot" aria-hidden="true"></span>
              <span id="cp-live-toggle-label">Live preview</span>
            </button>
            <span class="cp-mode-hint" id="cp-mode-hint"></span>
            <!-- The mode radios the transport JS drives; visually removed (the toggle above is the
                 only visible mode control). png = static snapshot, live = daemon stream, wasm =
                 in-browser app. -->
            <span class="cp-modes-inputs" aria-hidden="true">
              <input type="radio" name="cp-mode" value="png" id="cp-mode-png" tabindex="-1" checked>
              <input type="radio" name="cp-mode" value="live" id="cp-live" tabindex="-1"$liveDis>
              $wasmModeInput
            </span>
          </div>
          <details class="cp-group" open>
            <summary>Appearance</summary>
            <div class="cp-group-body">
              <label>Day / Night
                <select id="cp-uiMode"$wasmDis>
                  <option value="">Auto</option>
                  <option value="light">Light</option>
                  <option value="dark">Dark</option>
                </select>
              </label>
              $themeSelectorHtml
              <label>Background
                <select id="cp-background"$serverDis>
                  <option value="">(default)</option>
                  <option value="clear">Clear (crisp outline)</option>
                </select>
              </label>
            </div>
          </details>
          <details class="cp-group" open>
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
          <details class="cp-group">
            <summary>Locale &amp; text</summary>
            <div class="cp-group-body">
              <label>Locale
                <select id="cp-localeTag"$wasmDis>
                  <option value="">(default)</option>
                  <optgroup label="Pseudolocales (layout stress-test)">
                    <option value="en-XA">Accented — en-XA</option>
                    <option value="ar-XB">Bidi / RTL — ar-XB</option>
                  </optgroup>
                  <optgroup label="Right-to-left">
                    <option value="ar">Arabic — ar</option>
                    <option value="he">Hebrew — he</option>
                    <option value="fa">Persian — fa</option>
                  </optgroup>
                  <optgroup label="Common">
                    <option value="en-US">English — en-US</option>
                    <option value="de-DE">German — de-DE</option>
                    <option value="fr-FR">French — fr-FR</option>
                    <option value="es-ES">Spanish — es-ES</option>
                    <option value="pt-BR">Portuguese — pt-BR</option>
                    <option value="ru-RU">Russian — ru-RU</option>
                    <option value="ja-JP">Japanese — ja-JP</option>
                    <option value="ko-KR">Korean — ko-KR</option>
                    <option value="zh-CN">Chinese — zh-CN</option>
                    <option value="hi-IN">Hindi — hi-IN</option>
                    <option value="th-TH">Thai — th-TH</option>
                  </optgroup>
                </select>
              </label>
              <label>Font scale: <span id="cp-fontScale-val">default</span>
                <input id="cp-fontScale" type="range" min="0.5" max="2.0" step="0.1" value="1.0"$wasmDis>
              </label>
            </div>
          </details>
          <details class="cp-group">
            <summary>Device</summary>
            <div class="cp-group-body">
              <label>Device
                <select id="cp-device"$serverDis>
                  <option value="">(default)</option>
                  $deviceOptions
                </select>
              </label>
              <label>Orientation
                <select id="cp-orientation"$serverDis>
                  <option value="">(default)</option>
                  <option value="portrait">Portrait</option>
                  <option value="landscape">Landscape</option>
                </select>
              </label>
            </div>
          </details>
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
      // Click the read-only URL field to copy the /render URL to the clipboard (no separate button).
      // The text is selected either way as a fallback + visible affordance, and the field flashes via
      // .cp-url-copied so the click reads as "copied".
      document.querySelectorAll(".cp-url").forEach(function (field) {
        field.addEventListener("click", function () {
          field.select();
          var flash = function () {
            field.classList.add("cp-url-copied");
            setTimeout(function () { field.classList.remove("cp-url-copied"); }, 1200);
          };
          if (navigator.clipboard && navigator.clipboard.writeText) {
            navigator.clipboard.writeText(field.value).then(flash, function () {});
          } else {
            try { document.execCommand("copy"); flash(); } catch (e) {}
          }
        });
      });
      // "Copy PNG" / "Copy SVG": fetch the current /render artefact and put it on the clipboard as
      // text — SVG markup verbatim, PNG as a base64 data: URI — so it can be pasted straight into an
      // editor, prompt, or issue without downloading a file. Uses the same live cp-url-<ext> field
      // the URL Copy button reads, so the copied artefact matches the on-screen overrides.
      document.querySelectorAll(".cp-copyimg").forEach(function (btn) {
        btn.addEventListener("click", function () {
          var field = document.getElementById(btn.getAttribute("data-copyimg-target"));
          if (!field || !field.value) return;
          var ext = btn.getAttribute("data-copyimg-ext");
          var was = btn.getAttribute("data-copyimg-label") || btn.textContent;
          btn.setAttribute("data-copyimg-label", was);
          var reset = function (label) {
            btn.textContent = label;
            setTimeout(function () { btn.textContent = was; }, 1400);
          };
          if (!navigator.clipboard || !navigator.clipboard.writeText) { reset("No clipboard"); return; }
          btn.textContent = "Copying…";
          // fetch() resolves even on a non-2xx render (503 saturated, 400 bad override, 404 a
          // preview that can't export that lane), so guard on r.ok — otherwise the error body,
          // not the artefact, would land on the clipboard and still report "Copied".
          var okOrThrow = function (r) { if (!r.ok) throw new Error("render " + r.status); return r; };
          var toText =
            ext === ".svg"
              ? fetch(field.value).then(okOrThrow).then(function (r) { return r.text(); })
              : fetch(field.value)
                  .then(okOrThrow)
                  .then(function (r) { return r.blob(); })
                  .then(function (blob) {
                    return new Promise(function (resolve, reject) {
                      var fr = new FileReader();
                      fr.onload = function () { resolve(fr.result); };
                      fr.onerror = function () { reject(fr.error); };
                      fr.readAsDataURL(blob);
                    });
                  });
          toText
            .then(function (text) { return navigator.clipboard.writeText(text); })
            .then(function () { reset("Copied"); }, function () { reset("Failed"); });
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
          return;
        }
        // Not in an interactive lane. Whenever the server can produce a fresh overridden render — a
        // live daemon session (!staticSnapshot) OR a published catalog whose carried daemon
        // re-renders on demand (canRenderOverrides) — just re-point /render. This is what lets Size,
        // Locale, Device, … take effect for a CMP catalog while still showing static snapshots, so
        // the controls aren't dead until a live stream is opened.
        if (!staticSnapshot || canRenderOverrides) { refreshSnapshot(); return; }
        // Pure static published catalog whose only interactive lane is the in-browser app: the
        // wasm-honoured controls (day/night, font scale, locale) can only apply in the browser, so
        // auto-enable the Wasm tier and let it apply the change, instead of a dead /render the
        // catalog can't serve. (Size/Device stay disabled here — the Wasm app can't honour them.)
        if (wasmToggle) { setMode("wasm"); return; }
        refreshSnapshot();
      }

      // The single Static⇄Live toggle drives these transports. "live" opens the daemon stream,
      // "wasm" mounts the in-browser app, "png" (the default) is the static snapshot. closeStream /
      // closeWasm are idempotent, so a switch safely tears down both regardless of the prior state.
      // SVG is no longer an on-screen mode — it's an export format in the Direct-links group.
      function enterMode(m) {
        // A mode switch always clears a prior lane's error; the new lane re-raises its own if it fails.
        clearModeError();
        if (m === "live") { closeWasm(); openStream(); }
        else if (m === "wasm") { closeStream(); openWasm(); }
        else { closeStream(); closeWasm(); refreshSnapshot(); }
        syncOverlayToggles();
        syncServerControls();
        updateLiveToggle();
      }
      // "Full page (scroll)" only shapes the copyable/downloadable SVG export URL (withScroll scopes
      // it to the `.svg` lane), so it's always live and just refreshes the links on change.
      if (scrollLong) {
        scrollLong.addEventListener("change", refreshLinks);
      }
      // The live-only overlay toggles (talkBack / touchOverlay) are meaningful only while the daemon
      // holds the composition, so they're enabled iff the live stream is the active mode and greyed
      // out otherwise. Called on every mode transition.
      var overlayToggles = document.querySelectorAll(".cp-overlay");
      function syncOverlayToggles() {
        var on = !!(live && live.checked);
        Array.prototype.forEach.call(overlayToggles, function (el) { el.disabled = !on; });
      }
      // Enable/disable the display controls to match what the active session can actually render.
      // A server-render control (Size / Device / Orientation / Background) takes effect whenever the
      // server can produce a fresh overridden render: a live daemon session (!staticSnapshot), a
      // catalog whose carried daemon re-renders on demand (canRenderOverrides), or an active live
      // stream. The wasm-honoured trio (Day/Night / Locale / Font scale) additionally applies in the
      // in-browser app, so it's also enabled whenever a Wasm app backs the session. This is what
      // makes "most override modes" live for a CMP catalog (compose-m3) instead of greyed out until
      // a stream is opened; the server-rendered markup already reflects this, and this keeps it in
      // sync across mode transitions.
      var serverOnlyControlIds =
        ["device", "orientation", "background", "sizeMode",
         "fixedW", "fixedH", "minW", "minH", "maxW", "maxH"];
      var wasmHonouredControlIds = ["uiMode", "localeTag", "fontScale"];
      function syncServerControls() {
        var canServerRender = !staticSnapshot || canRenderOverrides || !!(live && live.checked);
        serverOnlyControlIds.forEach(function (id) {
          var el = document.getElementById("cp-" + id);
          if (el) el.disabled = !canServerRender;
        });
        wasmHonouredControlIds.forEach(function (id) {
          var el = document.getElementById("cp-" + id);
          if (el) el.disabled = !(canServerRender || wasmSrc);
        });
      }
      // Programmatic switch (the live toggle, or a wasm-only control auto-enabling Wasm): tick the
      // hidden mode radio so its state is consistent, then run the transition.
      function setMode(m) {
        var r = document.getElementById(
          m === "live" ? "cp-live" :
          m === "wasm" ? "cp-wasm-toggle" : "cp-mode-png");
        if (r) r.checked = true;
        enterMode(m);
      }
      Array.prototype.forEach.call(
        document.querySelectorAll("input[name=\"cp-mode\"]"),
        function (r) {
          r.addEventListener("change", function () { if (r.checked) enterMode(r.value); });
        });
      // The single Static⇄Live preview toggle. Off = the baked / on-demand snapshot; on = the best
      // interactive lane this session offers (the daemon stream when present, else the in-browser
      // Wasm app). Overrides still take effect while static (a catalog re-renders /render on
      // demand), so this toggle is specifically about *interacting* with the running composition —
      // clicking, scrolling, typing. The corner backend badge flips its icon/accent to match (see
      // backendBadgeScript).
      var liveToggle = document.getElementById("cp-live-toggle");
      var liveToggleLabel = document.getElementById("cp-live-toggle-label");
      var modeHint = document.getElementById("cp-mode-hint");
      function liveTransportAvailable() { return (live && !live.disabled) || !!wasmToggle; }
      function bestLiveMode() { return (live && !live.disabled) ? "live" : (wasmToggle ? "wasm" : null); }
      function anyLiveActive() { return !!(live && live.checked) || !!(wasmToggle && wasmToggle.checked); }
      function updateLiveToggle() {
        if (!liveToggle) return;
        var on = anyLiveActive();
        liveToggle.setAttribute("aria-pressed", on ? "true" : "false");
        liveToggle.disabled = !liveTransportAvailable();
        if (modeHint) {
          modeHint.textContent = on ? "interactive — click / scroll the preview"
            : (liveTransportAvailable() ? "static snapshot" : "static snapshot (no live lane)");
        }
      }
      if (liveToggle) {
        liveToggle.addEventListener("click", function () {
          if (anyLiveActive()) { setMode("png"); }
          else { var m = bestLiveMode(); if (m) setMode(m); }
        });
      }
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
      // Reconcile the control enabled-state + the toggle's initial look with the session's
      // capabilities (matches the server-rendered markup; keeps them in sync after hydration).
      syncServerControls();
      updateLiveToggle();
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
   * for the preview **with the current overrides applied**. Each row shows a read-only URL field
   * (click it to copy the URL), a one-click "Copy PNG"/"Copy SVG" button that copies the rendered
   * artefact itself as clipboard text (SVG markup verbatim; PNG as a base64 `data:` URI), and a
   * Download link (`<a download>`). The viewer JS keeps the URLs in sync as the controls / knobs
   * change (see `refreshLinks`), so the copied URL/artefact always reflects the on-screen state — a
   * shareable, scriptable handle on the exact render (a `curl`-able PNG/SVG). The URLs are built
   * client-side from `location.origin` + the session base, so they're absolute and work from
   * anywhere; the fields start empty and are filled on first render.
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
    // The SVG lane is export-only now (no on-screen SVG mode). Its download row carries the
    // "Full page (scroll)" toggle, which points the copyable/downloadable SVG URL at the full-page
    // `?scroll=long` export of a scrolling preview (a tall Wear capsule / grown LazyColumn) instead
    // of the viewport-sized SVG. The viewer JS (`withScroll`) folds it into the `.svg` URL only.
    val svgRow =
      if (hasSvgExport)
        "\n" + row("SVG", "svg") +
          "\n<label class=\"cp-live-row\"><input id=\"cp-scroll-long\" type=\"checkbox\"> " +
          "Full page (scroll) — SVG only</label>"
      else ""
    return """
      <details class="cp-group">
        <summary>Export &amp; direct links</summary>
        <div class="cp-group-body">
          <div class="cp-links">
            <div class="cp-knobs-head">The current view as a URL (overrides applied)</div>
            ${row("PNG", "png")}$svgRow
          </div>
        </div>
      </details>
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
        val attrs =
          "class=\"cp-knob\" data-knob-key=\"$wireKey\" data-knob-kind=\"$kind\" " +
            "data-knob-initial=\"$initial\""
        if (bool) {
          val checked = if (value == "true" || value == "1") " checked" else ""
          "<label class=\"cp-live-row\"><input type=\"checkbox\" $attrs$checked$dis> $label</label>"
        } else {
          val inputType = if (d.type == "int" || d.type == "float") "number" else "text"
          // A font knob (declared via `previewOverrideFont` / `catalogOverrideFont`) carries
          // autocomplete suggestions and/or the Google Fonts flag — render it as a combobox: a
          // free-text `<input list>` bound to a `<datalist>` (declared names first, then the full
          // fonts.google.com list). Any other string knob stays a plain text input.
          val fontField = d.googleFonts || d.suggestions.isNotEmpty()
          if (fontField) {
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
