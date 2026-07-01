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
    .cp-badge { display: inline-block; margin-left: 8px; padding: 1px 8px; border-radius: 999px;
      font-size: 0.7rem; font-weight: 600; vertical-align: middle; white-space: nowrap; }
    .cp-badge--trusted { background: #e7f4ea; color: #1e7a34; border: 1px solid #b6e0c2; }
    .cp-badge--unverified { background: #fdf0e3; color: #8a5300; border: 1px solid #f0d3a8; }
    .cp-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(200px, 1fr)); gap: 16px; }
    .cp-card { border: 1px solid #e3e3e8; border-radius: 10px; overflow: hidden; background: #fff;
      display: block; color: inherit; }
    .cp-imgwrap { display: flex; align-items: center; justify-content: center; min-height: 140px;
      background: repeating-conic-gradient(#f4f4f6 0% 25%, #fff 0% 50%) 50% / 16px 16px; padding: 8px; }
    .cp-imgwrap img { max-width: 100%; height: auto; display: block; }
    .cp-meta { padding: 8px 10px; font-size: 0.82rem; }
    .cp-label { font-weight: 500; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .cp-id { color: #6b6b70; font-size: 0.7rem; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .cp-viewer { display: flex; gap: 24px; flex-wrap: wrap; align-items: flex-start; }
    .cp-stage { flex: 1 1 360px; min-width: 280px; border: 1px solid #e3e3e8; border-radius: 10px;
      background: repeating-conic-gradient(#f4f4f6 0% 25%, #fff 0% 50%) 50% / 16px 16px; padding: 12px;
      display: flex; align-items: center; justify-content: center; min-height: 320px; }
    .cp-stage img, .cp-stage canvas { max-width: 100%; height: auto; }
    .cp-stage iframe { width: 100%; height: 320px; border: 0; background: transparent; }
    .cp-live-row { flex-direction: row !important; align-items: center; gap: 6px !important; }
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
    @media (prefers-color-scheme: dark) {
      body { color: #e6e6e9; background: #161618; }
      .cp-sub, .cp-id, .cp-status, .cp-about-links { color: #a0a0a8; }
      .cp-card, .cp-stage, .cp-knobs, .cp-about { border-color: #34343a; }
      .cp-card, .cp-about { background: #1d1d20; }
      .cp-about-body { color: #c9c9d0; }
      .cp-about-body code { background: #2a2a30; }
      .cp-systems-label { color: #a0a0a8; }
      .cp-systems a, .cp-systems-cur { border-color: #34343a; }
      .cp-systems a { background: #1d1d20; }
      .cp-systems-cur { background: #26264a; border-color: #45458a; color: #c9c9ff; }
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
   */
  private fun queryString(token: String, sessionId: String?): String {
    val t = "token=" + WebEscaping.urlEncodeSegment(token)
    return if (sessionId == null) t else t + "&session=" + WebEscaping.urlEncodeSegment(sessionId)
  }

  /**
   * The query string for a same-session link, given the page's [basePath]. When the page is served
   * under a `/<system>` path ([basePath] non-empty) the session is carried by the path, so links
   * are **token-only** — no `&session=`. When it's the root-mounted default/legacy `?session=` form
   * ([basePath] empty) it falls back to [queryString], preserving the historical link shape (and
   * byte-identical goldens for the callers that don't pass a basePath).
   */
  private fun linkQuery(token: String, sessionId: String?, basePath: String): String =
    if (basePath.isEmpty()) queryString(token, sessionId)
    else "token=" + WebEscaping.urlEncodeSegment(token)

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
  private fun catalogNav(catalogs: List<String>, token: String, sessionId: String?): String {
    if (catalogs.isEmpty()) return ""
    val tokenQuery = "token=" + WebEscaping.urlEncodeSegment(token)
    val links =
      catalogs.joinToString("\n") { sys ->
        val name = WebEscaping.htmlEscape(sys)
        if (sys == sessionId) {
          "<span class=\"cp-systems-cur\" aria-current=\"page\">$name</span>"
        } else {
          "<a href=\"/${WebEscaping.urlEncodeSegment(sys)}/?$tokenQuery\">$name</a>"
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
    val q = linkQuery(token, sessionId, basePath)
    val cards =
      if (previews.isEmpty()) {
        "<p class=\"cp-sub\">No previews discovered in this module.</p>"
      } else {
        previews.joinToString("\n") { p ->
          val idSeg = WebEscaping.urlEncodeSegment(p.id)
          val label = WebEscaping.htmlEscape(p.label)
          val idText = WebEscaping.htmlEscape(p.id)
          """
          <a class="cp-card" href="$basePath/p/$idSeg?$q">
            <div class="cp-imgwrap">
              <img loading="lazy" alt="$label" src="$basePath/render/$idSeg.png?$q">
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
    val nav = if (catalogs.isNotEmpty()) catalogNav(catalogs, token, sessionId) + "\n" else ""
    return document(
      title = "$moduleLabel — compose-preview",
      body =
        """
        $about$nav<p class="cp-head">${WebEscaping.htmlEscape(moduleLabel)}${trustBadge(trust)}</p>
        <p class="cp-sub">${previews.size} preview(s) · click one to view with overrides ·
          <a href="$basePath/bundle.zip?$q">download all (.zip)</a></p>
        <div class="cp-grid">
        $cards
        </div>
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
    trust: String? = null,
    wasmSrc: String? = null,
    /**
     * URL prefix for this session's links (`/<system>` when served under a path, empty otherwise).
     * The "← previews" link is prefixed with it; the viewer's own `/render` + `/ws` requests derive
     * their prefix from `location.pathname` at runtime, so they work under either mount. Empty ⇒
     * links are exactly as before.
     */
    basePath: String = "",
  ): String {
    val idSeg = WebEscaping.urlEncodeSegment(preview.id)
    val q = linkQuery(token, sessionId, basePath)
    val label = WebEscaping.htmlEscape(preview.label)
    val idText = WebEscaping.htmlEscape(preview.id)
    val modes = preview.modes.joinToString(",") { it.wire }
    // The Wasm tier is opt-in via a toggle (like "Live (stream)"), so the always-works PNG snapshot
    // stays the default. Both the iframe and the toggle are omitted entirely when no Wasm app backs
    // this session.
    val wasmAttr =
      if (wasmSrc != null) " data-wasm-src=\"${WebEscaping.htmlEscape(wasmSrc)}\"" else ""
    val wasmFrame =
      if (wasmSrc != null)
        "<iframe id=\"cp-wasm\" hidden sandbox=\"allow-scripts\" title=\"$label (Wasm)\"></iframe>"
      else ""
    val wasmToggle =
      if (wasmSrc != null)
        "<label class=\"cp-live-row\"><input id=\"cp-wasm-toggle\" type=\"checkbox\"> " +
          "Run in browser (Wasm)</label>"
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
    // Controls the in-browser Wasm app also honours — theme (uiMode), font scale (density), locale
    // (layout direction): live whenever the server can render OR a Wasm app backs the session.
    val wasmDis = if (staticSnapshot && wasmSrc == null) " disabled" else ""
    val snapshotNote =
      when {
        !staticSnapshot -> ""
        wasmSrc != null ->
          "<div class=\"cp-note\">Pre-rendered snapshot — tick “Run in browser (Wasm)” to interact: " +
            "Theme, Font scale &amp; Locale apply in the browser. Device/Orientation need the live " +
            "server.</div>"
        else ->
          "<div class=\"cp-note\">Pre-rendered snapshot — overrides (device, locale, font scale, " +
            "orientation) need the live server, not a published catalog.</div>"
      }
    val body =
      """
      <p class="cp-head"><a href="$basePath/?$q">← previews</a>${trustBadge(trust)}</p>
      <p class="cp-sub" title="$idText">$label</p>
      <div class="cp-viewer" data-preview-id="$idText" data-mode="snapshot" data-modes="$modes"$wasmAttr>
        <div class="cp-stage"><img id="cp-img" alt="$label"><canvas id="cp-canvas" hidden></canvas>$wasmFrame</div>
        <div class="cp-controls">
          $snapshotNote
          <label class="cp-live-row"><input id="cp-live" type="checkbox"$serverDis> Live (stream)</label>
          $wasmToggle
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
          ${overrideKnobsHtml(preview, canApplyOverrides)}
          <div class="cp-status" id="cp-status"></div>
        </div>
      </div>
      <script>${viewerScript()}</script>
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
      var fields = ["uiMode", "device", "localeTag", "orientation"];
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
      function query() {
        var o = overrides();
        var q = "token=" + encodeURIComponent(token);
        if (session) q += "&session=" + encodeURIComponent(session);
        Object.keys(o).forEach(function (k) { q += "&" + k + "=" + encodeURIComponent(o[k]); });
        // Author-declared knobs (enabled only on a daemon session): knob.<key>=<kind>:<value>.
        document.querySelectorAll(".cp-knob").forEach(function (el) {
          if (el.disabled) return;
          var key = el.getAttribute("data-knob-key");
          var kind = el.getAttribute("data-knob-kind") || "string";
          if (!key) return;
          var val = (el.type === "checkbox") ? (el.checked ? "true" : "false") : el.value;
          if (val === "") return;
          q += "&knob." + encodeURIComponent(key) + "=" + encodeURIComponent(kind + ":" + val);
        });
        return q;
      }
      function refreshSnapshot() {
        status.textContent = "rendering…";
        var url = base + "/render/" + encodeURIComponent(previewId) + ".png?" + query();
        var next = new Image();
        next.onload = function () { img.src = url; status.textContent = ""; };
        next.onerror = function () { status.textContent = "render failed"; };
        next.src = url;
      }
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
        img.hidden = true;
        canvas.hidden = false;
        status.textContent = "connecting…";
        var proto = location.protocol === "https:" ? "wss:" : "ws:";
        // Request WebP frames (smaller; the browser decodes them via the data URL, and the daemon
        // downgrades to PNG when it can't encode WebP — each frame carries its actual codec).
        ws = new WebSocket(proto + "//" + location.host + base + "/ws/" +
          encodeURIComponent(previewId) + "?" + query() + "&codec=webp");
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
        img.hidden = false;
      }
      // --- Wasm tier (the in-browser CMP app, mounted in a sandboxed iframe). Only wired when the
      // session carries a Wasm app (data-wasm-src present). Theme/font-scale/locale re-point the
      // iframe's ?uiMode/?fontScale/?localeTag (device/orientation are server-render-only).
      var wasmFrame = document.getElementById("cp-wasm");
      var wasmToggle = document.getElementById("cp-wasm-toggle");
      var wasmSrc = root.getAttribute("data-wasm-src") || "";
      // Set once the iframe's Wasm app has loaded and registered its postMessage listener. Until
      // then a control change re-points ?query (initial load); after, it posts an override patch so
      // the app recomposes in place instead of reloading the whole ~20 MB Wasm bundle.
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
      // The override patch (theme / font scale / locale) the running app merges over its baked base —
      // a bare `a=b&c=d` query. An absent key falls back to the app's baked default (e.g. cleared
      // Theme → the variant's uiMode). Device / orientation are server-render-only, so not forwarded.
      function wasmOverridePatch() {
        var parts = [];
        var el = document.getElementById("cp-uiMode");
        if (el && el.value) parts.push("uiMode=" + encodeURIComponent(el.value));
        var loc = document.getElementById("cp-localeTag");
        if (loc && loc.value) parts.push("localeTag=" + encodeURIComponent(loc.value));
        if (fontScaleTouched && fs) parts.push("fontScale=" + encodeURIComponent(fs.value));
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
      function openWasm() {
        // Wasm and the daemon stream are mutually exclusive — only one transport drives the stage.
        if (live.checked) { live.checked = false; closeStream(); }
        root.setAttribute("data-mode", "wasm");
        img.hidden = true; canvas.hidden = true; wasmFrame.hidden = false;
        wasmReady = false;
        wasmFrame.src = wasmInitialSrc();
        status.textContent = "";
      }
      function closeWasm() {
        root.setAttribute("data-mode", "snapshot");
        wasmReady = false;
        wasmFrame.hidden = true; wasmFrame.removeAttribute("src");
        img.hidden = false;
      }
      function wasmActive() { return wasmToggle && wasmToggle.checked; }

      function onControlsChanged() {
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
          ws.send(JSON.stringify({ type: "setOverrides", overrides: overrides() }));
        } else if (live.disabled && wasmToggle) {
          // Static snapshot backed by a Wasm app: the baked PNG can't honour theme/font-scale/locale
          // (only the in-browser tier can), and /render can't re-render a published catalog. So a
          // wasm-honoured control change auto-enables the Wasm tier and applies there, instead of
          // firing a dead refreshSnapshot the user sees as "the control does nothing". (live.disabled
          // marks a non-renderable session; device/orientation stay disabled, so only the
          // wasm-honoured controls reach here.)
          wasmToggle.checked = true;
          openWasm();
        } else if (!live.checked) {
          refreshSnapshot();
        }
      }

      live.addEventListener("change", function () {
        if (live.checked) {
          if (wasmToggle && wasmToggle.checked) { wasmToggle.checked = false; closeWasm(); }
          openStream();
        } else { closeStream(); refreshSnapshot(); }
      });
      if (wasmToggle) {
        wasmToggle.addEventListener("change", function () {
          if (wasmToggle.checked) openWasm();
          else { closeWasm(); refreshSnapshot(); }
        });
        // The app registers its message listener during startup; mark ready on iframe load so
        // subsequent control changes recompose in place rather than reloading the bundle.
        wasmFrame.addEventListener("load", function () {
          wasmReady = true;
          // Re-sync any control changed during load (the fragment only captured open-time state).
          var patch = wasmOverridePatch();
          if (patch) wasmFrame.contentWindow.postMessage(patch, "*");
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
      // Author-declared knobs re-render on edit (text/number debounce via "input", toggles "change").
      document.querySelectorAll(".cp-knob").forEach(function (el) {
        el.addEventListener(el.type === "checkbox" ? "change" : "input", onControlsChanged);
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
        val attrs = "class=\"cp-knob\" data-knob-key=\"$wireKey\" data-knob-kind=\"$kind\""
        if (kind == "bool") {
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
  private fun knobKind(type: String): String =
    when (type.lowercase()) {
      "int" -> "int"
      "float",
      "dp" -> "float"
      "bool",
      "boolean" -> "bool"
      "color" -> "color"
      else -> "string"
    }

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
