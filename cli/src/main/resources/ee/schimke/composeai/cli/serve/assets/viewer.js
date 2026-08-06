(function () {
  "use strict";
  var root = document.querySelector(".cp-viewer");
  var img = document.getElementById("cp-img");
  var stage = document.querySelector(".cp-stage");
  var canvas = document.getElementById("cp-canvas");
  var status = document.getElementById("cp-status");
  var errorBox = document.getElementById("cp-error");
  var live = document.getElementById("cp-live");
  // Tall previews used to size the stage from their full width-constrained height, which could
  // push the rest of the viewer several screens below the fold. Default to a viewport-bounded
  // contain fit; "Fit width" deliberately restores the old unconstrained-height presentation.
  // The snapshot remains the geometry source for Live/Wasm, so re-pin an active overlay after
  // changing modes.
  var zoomBtns = document.querySelectorAll(".cp-zoom-btn");
  function applyZoom(mode) {
    if (mode !== "width") mode = "fit";
    var maxHeight = mode === "fit" ? "72vh" : "";
    img.style.maxHeight = maxHeight;
    var rcZoomCanvas = document.getElementById("cp-rc-canvas");
    if (rcZoomCanvas) rcZoomCanvas.style.maxHeight = maxHeight;
    root.setAttribute("data-zoom", mode);
    zoomBtns.forEach(function (b) {
      b.setAttribute(
        "aria-pressed",
        b.getAttribute("data-zoom-mode") === mode ? "true" : "false"
      );
    });
    window.requestAnimationFrame(function () {
      if (live && live.checked && !canvas.hidden) fitLiveCanvas();
      if (wasmActive()) positionWasmFrame();
    });
  }
  zoomBtns.forEach(function (b) {
    b.addEventListener("click", function () {
      applyZoom(b.getAttribute("data-zoom-mode"));
    });
  });
  applyZoom("fit");
  // Surface a mode-activation failure visibly, instead of leaving a stale frame that reads as a
  // (wrong) render. Every lane routes its failure here — a dead Live stream, a Wasm app that
  // never boots, a /render that errors — so "can't activate this mode" is never silent.
  // Activation state for a lane that hasn't painted yet, surfaced on the stage's backend badge
  // (see backendBadgeScript) instead of the controls footer — a "connecting…" nobody scrolls to
  // isn't feedback. Pass null to clear.
  function setPending(label) {
    if (label) root.setAttribute("data-pending", label);
    else root.removeAttribute("data-pending");
  }
  function showModeError(msg) {
    setPending(null);
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
  // Hydrating the controls from the page URL's params — the knobs this used to do inline, plus
  // every display axis — now happens in one place (hydrateFromUrl, at the bottom of this file),
  // because Back/Forward needs to run exactly the same restore. It still lands before the first
  // render, so a deep link (or a copied "Direct links — overrides applied" URL) opens with those
  // values already set and carries them through whichever transport is live.
  // The selects + text input are opt-in (empty value = "use the preview's default"). The font
  // scale slider has no empty state, so it's gated separately: we only send fontScale once the
  // user moves it (fontScaleTouched), otherwise the slider's standing 1.0 would override a
  // preview's declared default font scale and the first render wouldn't match the thumbnail.
  var fields = ["device", "localeTag", "orientation", "background"];
  var fs = document.getElementById("cp-fontScale");
  var fsVal = document.getElementById("cp-fontScale-val");
  var fontScaleTouched = false;
  var ws = null;
  var themeChoice = document.getElementById("cp-theme");
  function activeThemeChoice() {
    return themeChoice && !themeChoice.disabled &&
      themeChoice.getAttribute("data-theme-active") === "1" ? themeChoice.value : "";
  }
  function chosenUiMode() {
    var value = activeThemeChoice();
    return value === "light" || value === "dark" ? value : "";
  }
  function chosenThemeProvider() {
    var value = activeThemeChoice();
    return value.indexOf("theme:") === 0 ? value.substring(6) : "";
  }
  // The snapshot lane serves either the raster PNG or the vector SVG through the same <img>.
  // The render-mode radio flips this (".png" default, ".svg" in SVG mode); refreshSnapshot and
  // the copyable links read it so a re-render / copied URL matches the on-screen format.
  var snapshotExt = ".png";
  // Keep the current frame visible while an override-triggered render is in flight. A generation
  // token prevents an older, slower request from clearing the busy treatment (or replacing the
  // pixels) after a newer control edit has already started another render.
  var snapshotGen = 0;
  function setSnapshotLoading(loading) {
    if (loading) {
      root.setAttribute("data-reloading", "true");
      if (stage) stage.setAttribute("aria-busy", "true");
    } else {
      root.removeAttribute("data-reloading");
      if (stage) stage.removeAttribute("aria-busy");
    }
  }
  function cancelSnapshotLoading() {
    snapshotGen++;
    status.textContent = "";
    setSnapshotLoading(false);
  }

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
      if (el && !el.disabled && el.value) o[f] = el.value;
    });
    var uiMode = chosenUiMode();
    if (uiMode) o.uiMode = uiMode;
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
    // Remote Compose knobs carry their own `<kind>:` tag: `rc.<name>=<kind>:<value>`. Sent for
    // every RC knob (like the plain knobs) so a Live setOverrides doesn't reset the others.
    document.querySelectorAll(".cp-rc-knob").forEach(function (el) {
      if (el.disabled) return;
      var name = el.getAttribute("data-rc-name");
      if (!name) return;
      var kind = el.getAttribute("data-rc-kind") || "string";
      var val = (el.type === "checkbox") ? (el.checked ? "true" : "false") : el.value;
      if (val === "") return;
      o["rc." + name] = kind + ":" + val;
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
    var tp = chosenThemeProvider();
    if (tp) o["themeProvider"] = tp;
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
  // RC backend selector state (the #cp-rc-backends chips). `rcPlayerBackend` is the current pick
  // and `rcPlayerPicked` gates whether it rides the render URL — so page load stays on the
  // instant default snapshot until the visitor actually chooses a server-side backend.
  var rcBackendsEl = document.getElementById("cp-rc-backends");
  var rcPlayerBackend = rcBackendsEl ? (rcBackendsEl.getAttribute("data-default") || "") : "";
  var rcPlayerPicked = false;
  // Reconcile the backend chips' pressed state with the active lane. Hoisted (the real impl is
  // assigned in the selector block below) so the common mode-transition path (enterMode) can
  // call it whenever the viewer leaves a lane through ANY control — not only a chip click — so
  // the JS chip can't stay pressed after e.g. Live/Wasm/SVG takes over the stage. A no-op stub
  // for a non-Remote-Compose preview (no selector present).
  var syncRcBackendChips = function () {};
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
    // Remote Compose knobs: rc.<name>=<kind>:<value>. The <kind>: prefix types the seed
    // (color:%23AARRGGBB, int:…, bool:true, …). A knob still at its declared default is omitted
    // so the URL stays on the instant baked snapshot until it's actually changed.
    document.querySelectorAll(".cp-rc-knob").forEach(function (el) {
      if (el.disabled) return;
      var name = el.getAttribute("data-rc-name");
      if (!name) return;
      var kind = el.getAttribute("data-rc-kind") || "string";
      var val = (el.type === "checkbox") ? (el.checked ? "true" : "false") : el.value;
      if (val === "") return;
      if (val === (el.getAttribute("data-rc-initial") || "")) return;
      parts.push("rc." + encodeURIComponent(name) + "=" + encodeURIComponent(kind + ":" + val));
    });
    // App-declared theme (themeProvider = provider FQN). Routes to the daemon like a knob; a
    // published catalog re-renders on demand. Omitted at "(default)" so the URL stays on the
    // instant baked snapshot until a theme is actually chosen.
    var tp = chosenThemeProvider();
    if (tp) parts.push("themeProvider=" + encodeURIComponent(tp));
    // Detected-feature: keyboard focus (focus=0). Routes to the daemon like a knob; omitted when
    // unchecked so the URL stays on the baked snapshot.
    var fc = document.getElementById("cp-focus");
    if (fc && !fc.disabled && fc.checked) parts.push("focus=0");
    // Detected-feature: one-handed gesture hints (gestures=true). Routes to the daemon like a
    // knob; omitted when unchecked so the URL stays on the baked snapshot.
    var gc = document.getElementById("cp-gestures");
    if (gc && !gc.disabled && gc.checked) parts.push("gestures=true");
    // Remote Compose render backend: a server-side player pick rides the render as
    // rcPlayer=<wire>. Emitted only once the visitor picks a backend (rcPlayerPicked) and only
    // for a server-side lane — java / cmp-android render through the daemon, cmp-jvm through its
    // isolated desktop subprocess (all three PNG lanes). The js canvas replays the doc in-browser
    // (no server render), so it never sends the param, and an unpicked default stays on the
    // instant baked snapshot.
    if (
      rcPlayerPicked &&
      (rcPlayerBackend === "java" ||
        rcPlayerBackend === "cmp-android" ||
        rcPlayerBackend === "cmp-jvm")
    ) {
      parts.push("rcPlayer=" + encodeURIComponent(rcPlayerBackend));
    }
    return parts.join("&");
  }
  // "Full page (scroll)" appends `scroll=long` to both snapshot formats. The server routes SVG to
  // compose/figma-svg-long and PNG to render/scroll/long.
  var scrollLong = document.getElementById("cp-scroll-long");
  function withScroll(ext, qs) {
    if (scrollLong && scrollLong.checked) {
      return qs ? qs + "&scroll=long" : "scroll=long";
    }
    return qs;
  }
  function refreshSnapshot() {
    status.textContent = "rendering…";
    var gen = ++snapshotGen;
    setSnapshotLoading(true);
    var qs = withScroll(snapshotExt, query());
    var url =
      base + "/render/" + encodeURIComponent(previewId) + snapshotExt + (qs ? "?" + qs : "");
    var requestedExt = snapshotExt;
    // Override-bearing renders are deliberately `no-store`. Preloading with `new Image()` and
    // then assigning the same URL to the visible image therefore performs two server renders,
    // and the second one can race the first through the daemon's shared override state. Fetch the
    // bytes once and hand the resulting blob URL to the image instead. This also keeps the current
    // frame visible until the replacement has decoded.
    fetch(url, { credentials: "same-origin" })
      .then(function (response) {
        if (!response.ok) throw new Error("render " + response.status);
        return response.blob();
      })
      .then(function (blob) {
        if (gen !== snapshotGen) return;
        var objectUrl = URL.createObjectURL(blob);
        var next = new Image();
        next.onload = function () {
          if (gen !== snapshotGen) { URL.revokeObjectURL(objectUrl); return; }
          var previous = img.getAttribute("data-cp-blob");
          img.src = objectUrl;
          img.setAttribute("data-cp-blob", objectUrl);
          // The blob URL is opaque — it says nothing about which render produced the pixels on
          // screen. Record the /render URL we actually fetched so the visible frame's provenance
          // stays inspectable: which format, which knobs, which lane. `#cp-url-png` / `#cp-url-svg`
          // track the *current controls*, which is not the same thing — they update the instant a
          // knob moves, while this only lands once the matching bytes have decoded. That gap is
          // exactly what the serve-lanes e2e asserts on.
          img.setAttribute("data-cp-src", url);
          if (previous) URL.revokeObjectURL(previous);
          status.textContent = "";
          setSnapshotLoading(false);
          clearModeError();
        };
        next.onerror = function () {
          URL.revokeObjectURL(objectUrl);
          if (gen !== snapshotGen) return;
          setSnapshotLoading(false);
          showModeError((requestedExt === ".svg" ? "SVG" : "PNG") +
            " render failed for this preview.");
        };
        next.src = objectUrl;
      })
      .catch(function () {
        if (gen !== snapshotGen) return;
        setSnapshotLoading(false);
        showModeError((requestedExt === ".svg" ? "SVG" : "PNG") +
          " render failed for this preview.");
      });
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
  function withMode(url, mode) {
    return url + (url.indexOf("?") >= 0 ? "&" : "?") + "mode=" + mode;
  }
  function refreshLinks() {
    // The page's own URL is kept in step with the controls for the same reason the direct links
    // are: what's on screen should be something you can bookmark or hand to someone. Every path
    // that changes viewer state already refreshes the links, so this one call covers all of them.
    syncUrl();
    [["png", ".png"], ["svg", ".svg"]].forEach(function (pair) {
      var field = document.getElementById("cp-url-" + pair[0]);
      if (!field) return;
      var embed = renderUrl(pair[1]);
      var dl = document.getElementById("cp-dl-" + pair[0]);
      if (pair[1] === ".svg") {
        // Copy URL yields the web/document variant (`?mode=web` → external Google Fonts
        // @import), so opening the copied link in a browser pulls the faces from Google. Copy
        // SVG and the download stay on the embedded variant (self-contained — right for pasting
        // into Figma or an <img>, where external refs don't load); the button reads it from
        // data-embed-url.
        field.value = withMode(embed, "web");
        field.setAttribute("data-embed-url", embed);
        if (dl) dl.href = embed;
      } else {
        field.value = embed;
        if (dl) dl.href = embed;
      }
    });
    updateSvgMatch();
    refreshReportLink();
  }
  // Keep the "report an issue" report pointed at what is on screen. The server filled the form's
  // hidden `body` for the settings the page was served at (so this works with JS off); the
  // template it carries has the render URL as a `{{render}}` placeholder, which we swap for the
  // live /render URL so a report filed after fiddling with the knobs shows the render that
  // prompted it. The token is stripped for the same reason the server strips it: an issue body is
  // public, a session token is a capability.
  //
  // Note this writes an INPUT VALUE, never an href: the affordance is a GET form whose action is a
  // server-rendered literal, so no page-derived string ever reaches a navigation sink. The browser
  // does the query encoding on submit, which is why the substituted URL goes in raw here.
  function refreshReportLink() {
    var body = document.getElementById("cp-report-body");
    if (!body) return;
    var tpl = body.getAttribute("data-report-template");
    var field = document.getElementById("cp-url-png");
    if (!tpl || !field || !field.value) return;
    body.value = tpl.replace("{{render}}", stripToken(field.value));
  }
  function stripToken(url) {
    var cut = url.indexOf("?");
    if (cut < 0) return url;
    var kept = url.slice(cut + 1).split("&").filter(function (p) {
      return p && p.slice(0, 6) !== "token=";
    });
    return kept.length ? url.slice(0, cut) + "?" + kept.join("&") : url.slice(0, cut);
  }
  var svgMatch = document.getElementById("cp-svg-match");
  var svgDiff = document.getElementById("cp-svg-diff");
  var svgMatchGeneration = 0;
  var svgMatchKey = "";
  function updateSvgMatch() {
    if (!svgMatch) return;
    if (!svgOn()) {
      svgMatch.hidden = true;
      if (svgDiff) svgDiff.hidden = true;
      return;
    }
    var png = document.getElementById("cp-url-png");
    var svg = document.getElementById("cp-url-svg");
    var svgUrl = svg && (svg.getAttribute("data-embed-url") || svg.value);
    if (!png || !png.value || !svgUrl || !window.ComposePreviewCompare) return;
    var key = png.value + "\n" + svgUrl;
    if (key === svgMatchKey && svgMatch.textContent && svgMatch.textContent !== "comparing…") {
      svgMatch.hidden = false;
      if (svgDiff) svgDiff.hidden = false;
      return;
    }
    svgMatchKey = key;
    var generation = ++svgMatchGeneration;
    svgMatch.hidden = false;
    svgMatch.className = "cp-match";
    svgMatch.textContent = "comparing…";
    if (svgDiff) svgDiff.hidden = true;
    window.ComposePreviewCompare.scoreSvgUrls(png.value, svgUrl).then(function (percent) {
      if (generation !== svgMatchGeneration || !svgOn()) return;
      svgMatch.textContent = percent.toFixed(1) + "% match";
      svgMatch.className = "cp-match cp-match--" +
        (percent >= 90 ? "good" : percent >= 75 ? "warn" : "bad");
      if (svgDiff) svgDiff.hidden = false;
    }, function () {
      if (generation !== svgMatchGeneration || !svgOn()) return;
      svgMatch.textContent = "match unavailable";
      svgMatch.className = "cp-match cp-match--na";
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
  // "Copy PNG" / "Copy SVG": fetch the current /render artefact and put it on the clipboard —
  // PNG as real image/png bytes (falling back to a base64 data: URI), SVG as markup verbatim — so
  // it can be pasted straight into an issue, editor, or prompt without downloading a file. Uses
  // the same live cp-url-<ext> field the URL Copy button reads, so the copied artefact matches the
  // on-screen overrides.
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
      if (!navigator.clipboard) { reset("No clipboard"); return; }
      btn.textContent = "Copying…";
      // Copy SVG targets the EMBEDDED variant (data-embed-url) — the field itself holds the
      // web-mode URL for Copy URL, but a copied SVG is usually pasted into Figma / an editor,
      // which needs the fonts baked in, not an external @import. PNG has one variant.
      var src = (ext === ".svg" && field.getAttribute("data-embed-url")) || field.value;
      // fetch() resolves even on a non-2xx render (503 saturated, 400 bad override, 404 a
      // preview that can't export that lane), so guard on r.ok — otherwise the error body,
      // not the artefact, would land on the clipboard and still report "Copied".
      var okOrThrow = function (r) { if (!r.ok) throw new Error("render " + r.status); return r; };
      // PNG: hand the clipboard the real image/png bytes when the browser has ClipboardItem, so
      // pasting into a GitHub issue (or a doc, or a chat) lands the picture — which is what makes
      // "Copy PNG → paste into the bug report" a one-keystroke screenshot. The blob goes in as a
      // *promise* because Safari requires the ClipboardItem to be constructed synchronously inside
      // the click; awaiting the fetch first would lose the user gesture. Anything that can't do it
      // — no ClipboardItem, a denied permission, a non-image response — falls through to the
      // original base64 data: URI text, which still pastes into an editor or a prompt.
      var copyAsText = function () {
        if (!navigator.clipboard.writeText) { reset("No clipboard"); return; }
        var toText =
          ext === ".svg"
            ? fetch(src).then(okOrThrow).then(function (r) { return r.text(); })
            : fetch(src)
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
      };
      if (ext === ".png" && window.ClipboardItem && navigator.clipboard.write) {
        var pngBlob = fetch(src).then(okOrThrow).then(function (r) { return r.blob(); });
        navigator.clipboard
          .write([new ClipboardItem({ "image/png": pngBlob })])
          .then(function () { reset("Copied"); }, copyAsText);
        return;
      }
      copyAsText();
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
    setPending("connecting…");
    var proto = location.protocol === "https:" ? "wss:" : "ws:";
    // Request WebP frames (smaller; the browser decodes them via the data URL, and the daemon
    // downgrades to PNG when it can't encode WebP — each frame carries its actual codec).
    var qs = query();
    // Track whether the stream ever delivered a frame: a close/error *before* the first frame is
    // a failed activation (surface it), whereas a close *after* frames is just a normal teardown.
    var liveGotFrame = false;
    // Hold the socket in a per-activation local as well as `ws`, and gate every callback on
    // `ws === sock`. Toggling Live off and straight back on opens a replacement before the old
    // socket's close event is delivered, and that stale callback would otherwise clear the NEW
    // connection's pending badge (its own liveGotFrame is true, so it skips the error branch)
    // and null out `ws` — orphaning the live socket the input/override senders reach for.
    var sock = new WebSocket(proto + "//" + location.host + base + "/ws/" +
      encodeURIComponent(previewId) + "?" + (qs ? qs + "&codec=webp" : "codec=webp"));
    ws = sock;
    sock.onopen = function () {
      // The connect URL seeds only query()'s fields — the display axes plus changed knobs — so
      // the live-only overlays (talkBack / touchOverlay) and anything toggled during the
      // connecting window aren't in it. Replay the full live override map once the socket is
      // ready so the daemon reflects the exact current control state, including an overlay
      // checked before onopen whose change event the readyState guard dropped.
      sock.send(JSON.stringify({ type: "setOverrides", overrides: liveOverrides() }));
    };
    sock.onmessage = function (ev) {
      // A frame from a socket the viewer has already replaced is stale in both senses: it must
      // not paint over the new lane's stage, nor report it as connected.
      if (ws !== sock) return;
      var m;
      try { m = JSON.parse(ev.data); } catch (e) { return; }
      if (m.type === "frame") { liveGotFrame = true; clearModeError(); drawFrame(m.dataBase64, m.codec); setPending(null); }
      else if (m.type === "error") { showModeError(m.message || "Live preview error."); }
    };
    // onerror always precedes onclose; let onclose decide (it carries the code/reason). Only
    // surface here if the socket somehow errors while already open+frame-less and never closes.
    sock.onerror = function () { if (ws === sock && !liveGotFrame) setPending("connecting…"); };
    sock.onclose = function (ev) {
      // Not the current socket ⇒ a teardown the viewer already accounted for in closeStream()
      // (which cleared pending and restored the snapshot). Leave the live lane's state alone.
      if (ws !== sock) return;
      ws = null;
      // The lane is done waiting either way — it painted, or it failed (showModeError below).
      setPending(null);
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
    // Toggling Live off mid-connect must not leave the badge stuck on "connecting…".
    setPending(null);
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
    var uiMode = chosenUiMode();
    if (uiMode) parts.push("uiMode=" + encodeURIComponent(uiMode));
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

  // ---- In-browser Remote Compose canvas lane ----------------------------------------------
  // When this preview carries a captured `.rc` document, the "RC (browser)" toggle paints it with
  // the vendored player (RC.RcdPlayer) into #cp-rc-canvas — no daemon — and Remote Compose knob
  // edits apply live via setNamed*Override + repaint (onRcKnobChanged) instead of a /render
  // round-trip. Opt-in like Live / Wasm, so the default PNG snapshot is untouched.
  var rcCanvasEl = document.getElementById("cp-rc-canvas");
  var rcToggle = document.getElementById("cp-rc-toggle");
  var rcWasmFrame = document.getElementById("cp-rc-wasm");
  var rcWasmToggle = document.getElementById("cp-rc-wasm-toggle");
  var rcWasmReady = false;
  var rcWasmBootTimer = null;
  var hasRcDoc = root.getAttribute("data-has-rc-doc") === "1";
  var rcBtn = document.getElementById("cp-rc-btn");
  var rcPlayer = null; // the RC.RcdPlayer instance (created lazily on first open)
  var rcCtx = null; // its WebRemoteContext, for named-value overrides
  var rcReady = false; // a first frame is painted and the canvas revealed
  var rcScriptState = 0; // 0 = not loaded, 1 = loading, 2 = ready
  var rcScriptWaiters = [];
  function rcAvailable() { return !!(hasRcDoc && rcCanvasEl); }
  function rcActive() { return !!(rcToggle && rcToggle.checked); }
  function rcWasmActive() { return !!(rcWasmToggle && rcWasmToggle.checked); }
  // Lazy-load the shared player bundle once (a constant, session-independent path); queue callers
  // while it loads so a fast re-open can't inject the script twice.
  function ensureRcScript(cb) {
    if (rcScriptState === 2 || window.RC) { rcScriptState = 2; cb(true); return; }
    rcScriptWaiters.push(cb);
    if (rcScriptState === 1) return;
    rcScriptState = 1;
    var s = document.createElement("script");
    s.src = "/rc-player/bundle.js";
    s.onload = function () {
      rcScriptState = 2;
      var ws = rcScriptWaiters; rcScriptWaiters = [];
      ws.forEach(function (f) { f(true); });
    };
    s.onerror = function () {
      rcScriptState = 0;
      var ws = rcScriptWaiters; rcScriptWaiters = [];
      ws.forEach(function (f) { f(false); });
    };
    document.head.appendChild(s);
  }
  // The `.rc` document URL — the same `base` + token/session as the snapshot, but no override qs
  // (the lane serves the document verbatim; knob edits apply client-side).
  function rcDocUrl() {
    var parts = [];
    if (token) parts.push("token=" + encodeURIComponent(token));
    if (session) parts.push("session=" + encodeURIComponent(session));
    var qs = parts.join("&");
    return base + "/render/" + encodeURIComponent(previewId) + ".rc" + (qs ? "?" + qs : "");
  }
  // Parse #RRGGBB / #AARRGGBB (optionally %23-escaped) into a 0xAARRGGBB int (opaque when no alpha).
  function parseRcColor(v) {
    if (!v) return null;
    var h = v.replace(/^%23/, "").replace(/^#/, "");
    if (h.length === 6) h = "FF" + h;
    if (h.length !== 8) return null;
    var n = parseInt(h, 16);
    return isNaN(n) ? null : (n >>> 0);
  }
  // Push every Remote Compose knob's current value onto the player's context, then repaint. Names
  // are USER:-domain-qualified (the connector registers author knobs under USER:), matching the
  // document's named variables; kinds mirror query()'s rc.<name>=<kind>:<value> typing.
  function applyRcOverrides() {
    if (!rcCtx) return;
    document.querySelectorAll(".cp-rc-knob").forEach(function (el) {
      var name = el.getAttribute("data-rc-name");
      if (!name) return;
      var qn = "USER:" + name;
      var kind = el.getAttribute("data-rc-kind") || "string";
      var val = (el.type === "checkbox") ? (el.checked ? "true" : "false") : el.value;
      try {
        if (kind === "color") {
          var argb = parseRcColor(val);
          if (argb !== null && rcCtx.setNamedColorOverride) rcCtx.setNamedColorOverride(qn, argb);
        } else if (kind === "float" || kind === "dp") {
          var f = parseFloat(val);
          if (!isNaN(f) && rcCtx.setNamedFloatOverride) rcCtx.setNamedFloatOverride(qn, f);
        } else if (kind === "int" || kind === "integer") {
          var n = parseInt(val, 10);
          if (!isNaN(n) && rcCtx.setNamedIntegerOverride) rcCtx.setNamedIntegerOverride(qn, n);
        } else if (kind === "bool" || kind === "boolean") {
          // The player's setNamedBooleanOverride only records the value — it doesn't touch the
          // render state — so route booleans through the integer setter as 1/0, matching the
          // daemon's BooleanValue → user-local-integer mapping.
          if (rcCtx.setNamedIntegerOverride) {
            rcCtx.setNamedIntegerOverride(qn, val === "true" ? 1 : 0);
          }
        } else if (rcCtx.setNamedStringOverride) {
          rcCtx.setNamedStringOverride(qn, val);
        }
      } catch (e) { /* a knob the document doesn't declare is a harmless no-op */ }
    });
    if (rcPlayer && rcPlayer.repaint) rcPlayer.repaint();
  }
  function openRc() {
    if (!rcAvailable()) return;
    root.setAttribute("data-mode", "rc");
    canvas.hidden = true;
    rcReady = false;
    status.textContent = "loading RC player…";
    ensureRcScript(function (ok) {
      if (!ok || !window.RC) { showModeError("The Remote Compose player failed to load."); return; }
      if (!rcActive()) return; // toggled away while the script loaded
      fetch(rcDocUrl())
        .then(function (r) { if (!r.ok) throw new Error("doc " + r.status); return r.arrayBuffer(); })
        .then(function (buf) {
          if (!rcActive()) return null;
          // Size the canvas to the preview's real pixel dimensions BEFORE loading: the player
          // derives the document viewport from the canvas's current size at load time, and a
          // resize afterwards can't recover it. The baked snapshot <img> carries those
          // dimensions (rendered at the same density), so a non-default-shaped preview fills
          // the canvas instead of being letterboxed into the 300×150 default.
          var w = img.naturalWidth || 0, h = img.naturalHeight || 0;
          if (w > 0 && h > 0) { rcCanvasEl.width = w; rcCanvasEl.height = h; }
          if (!rcPlayer) rcPlayer = new window.RC.RcdPlayer(rcCanvasEl);
          return rcPlayer.loadFromArrayBuffer(buf);
        })
        .then(function () {
          if (!rcActive()) return;
          rcCtx = rcPlayer.getRemoteContext ? rcPlayer.getRemoteContext() : null;
          applyRcOverrides();
          if (rcPlayer.repaint) rcPlayer.repaint();
          revealRc();
        })
        .catch(function () { showModeError("Rendering the Remote Compose document failed."); });
    });
  }
  // Swap the stage from the snapshot to the painted canvas. The snapshot is removed from flow
  // (display:none) so the stage takes the document's own size rather than stacking both.
  function revealRc() {
    if (!rcActive() || rcReady) return;
    rcReady = true;
    clearModeError();
    rcCanvasEl.hidden = false;
    img.style.display = "none";
    if (rcBtn) rcBtn.setAttribute("aria-pressed", "true");
    status.textContent = "";
  }
  function closeRc() {
    if (!rcCanvasEl) return;
    root.setAttribute("data-mode", "snapshot");
    rcReady = false;
    rcCanvasEl.hidden = true;
    img.style.removeProperty("display");
    img.hidden = false;
    if (rcBtn) rcBtn.setAttribute("aria-pressed", "false");
  }

  // AndroidX-conformant Compose Multiplatform/Wasm RC lane. This is an isolated app rather than
  // another implementation hidden behind the legacy canvas API: it receives the document URL and
  // explicitly announces its first rendered frame.
  function positionRcWasmFrame() { if (rcWasmFrame) positionOverlay(rcWasmFrame); }
  function rcWasmNamedValues() {
    var values = [];
    document.querySelectorAll(".cp-rc-knob").forEach(function (el) {
      var name = el.getAttribute("data-rc-name");
      if (!name) return;
      values.push({
        name: name,
        kind: el.getAttribute("data-rc-kind") || "string",
        value: el.type === "checkbox" ? (el.checked ? "true" : "false") : el.value
      });
    });
    return values;
  }
  function rcWasmSrc() {
    var absoluteDoc = new URL(rcDocUrl(), location.origin).href;
    var src = "/rc-player-wasm/index.html?src=" + encodeURIComponent(absoluteDoc);
    var uiMode = document.getElementById("cp-uiMode");
    if (uiMode && (uiMode.value === "light" || uiMode.value === "dark")) {
      src += "&theme=" + encodeURIComponent(uiMode.value);
    }
    var namedValues = rcWasmNamedValues();
    if (namedValues.length) {
      src += "&namedValues=" + encodeURIComponent(JSON.stringify(namedValues));
    }
    return src;
  }
  function revealRcWasm() {
    if (!rcWasmActive() || rcWasmReady) return;
    rcWasmReady = true;
    if (rcWasmBootTimer) { clearTimeout(rcWasmBootTimer); rcWasmBootTimer = null; }
    clearModeError();
    positionRcWasmFrame();
    rcWasmFrame.classList.add("cp-wasm-live");
    img.style.visibility = "hidden";
    status.textContent = "";
  }
  function openRcWasm() {
    if (!rcWasmFrame) return;
    root.setAttribute("data-mode", "rc-wasm");
    canvas.hidden = true;
    positionRcWasmFrame();
    rcWasmFrame.hidden = false;
    rcWasmReady = false;
    rcWasmFrame.src = rcWasmSrc();
    status.textContent = "loading CMP Wasm RC player…";
    if (rcWasmBootTimer) clearTimeout(rcWasmBootTimer);
    rcWasmBootTimer = setTimeout(function () {
      if (!rcWasmReady && rcWasmActive()) showModeError("CMP Wasm RC player didn't start.");
    }, 20000);
  }
  function closeRcWasm() {
    if (!rcWasmFrame) return;
    rcWasmReady = false;
    if (rcWasmBootTimer) { clearTimeout(rcWasmBootTimer); rcWasmBootTimer = null; }
    rcWasmFrame.classList.remove("cp-wasm-live");
    rcWasmFrame.hidden = true;
    rcWasmFrame.removeAttribute("src");
    img.style.removeProperty("visibility");
  }
  if (rcWasmFrame) {
    window.addEventListener("message", function (e) {
      if (e.source !== rcWasmFrame.contentWindow || e.origin !== location.origin) return;
      if (e.data === "cp-rc-wasm-ready") revealRcWasm();
      else if (typeof e.data === "string" && e.data.indexOf("cp-rc-wasm-error:") === 0) {
        showModeError("Rendering the Remote Compose document failed in CMP Wasm.");
      } else if (e.data && (e.data.type === "cp-rc-host-action" ||
          e.data.type === "cp-rc-host-named-action")) {
        // The viewer never executes an action payload. It exposes the validated event to an
        // embedding host and leaves policy/navigation to that host.
        window.dispatchEvent(new CustomEvent(e.data.type, { detail: e.data }));
        status.textContent = e.data.type === "cp-rc-host-action"
          ? "Remote Compose host action " + String(e.data.actionId)
          : "Remote Compose named action “" + String(e.data.name || "") + "”";
      }
    });
  }

  function onControlsChanged() {
    // Keep the copyable direct links current no matter which transport handles the change.
    refreshLinks();
    if (rcWasmActive()) {
      // Remote Compose currently consumes only Day/Night at this boundary. The control is the
      // only wasm-honoured field enabled in this lane, so reload the isolated player with the
      // new theme query while retaining the same tokened document URL.
      openRcWasm();
      return;
    }
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
  // The static lane additionally honours the SVG format toggle: the same <img>, pointed at the
  // vector `/render/<id>.svg` instead of the raster `.png`. A live lane (stream / wasm) is raster
  // frames, so entering it clears SVG.
  var svgToggle = document.getElementById("cp-svg-toggle");
  function svgOn() {
    return !!(svgToggle && svgToggle.getAttribute("aria-pressed") === "true");
  }
  function enterMode(m) {
    // A lane switch is a discrete choice, so the URL sync it ends up triggering pushes a history
    // entry rather than replacing one — Back returns to the lane the visitor came from. Set here
    // rather than on each control because every transition (radio, Live/Wasm/RC toggle, or an
    // auto-enable) passes through this function.
    urlPush = true;
    // A mode switch always clears a prior lane's error; the new lane re-raises its own if it fails.
    clearModeError();
    if (m === "live") {
      cancelSnapshotLoading();
      snapshotExt = ".png";
      if (svgToggle) svgToggle.setAttribute("aria-pressed", "false");
      closeWasm();
      closeRc();
      closeRcWasm();
      openStream();
    } else if (m === "wasm") {
      cancelSnapshotLoading();
      snapshotExt = ".png";
      if (svgToggle) svgToggle.setAttribute("aria-pressed", "false");
      closeStream();
      closeRc();
      closeRcWasm();
      openWasm();
    } else if (m === "rc") {
      cancelSnapshotLoading();
      snapshotExt = ".png";
      if (svgToggle) svgToggle.setAttribute("aria-pressed", "false");
      closeStream();
      closeWasm();
      closeRcWasm();
      openRc();
    } else if (m === "rc-wasm") {
      cancelSnapshotLoading();
      snapshotExt = ".png";
      if (svgToggle) svgToggle.setAttribute("aria-pressed", "false");
      closeStream();
      closeWasm();
      closeRc();
      openRcWasm();
    } else {
      closeStream();
      closeWasm();
      closeRc();
      closeRcWasm();
      // Static snapshot lane: raster PNG, or the vector SVG when the format toggle is on.
      snapshotExt = svgOn() ? ".svg" : ".png";
      if (svgOn()) root.setAttribute("data-mode", "svg");
    }
    syncOverlayToggles();
    syncServerControls();
    updateLiveToggle();
    // Every lane transition passes through here, so the backend chips are re-reconciled whether
    // the viewer entered/left the JS canvas via a chip or via Live/Wasm/SVG/snapshot controls.
    syncRcBackendChips();
    // Render the static lane AFTER syncServerControls() has reconciled the daemon-only controls
    // for the new lane. Returning from Wasm this re-enables the `.cp-rc-knob` inputs first, so
    // query() includes an rc.* value edited before the Wasm detour in the first snapshot render
    // (and its direct links) instead of skipping the still-disabled control. The live/wasm lanes
    // drive their own render (openStream / openWasm), so only the static lane renders here.
    if (m !== "live" && m !== "wasm" && m !== "rc" && m !== "rc-wasm") refreshSnapshot();
    // The interactive lanes drive their own render and never reach refreshLinks, so the URL would
    // still describe the snapshot the visitor just left — the chosen lane unbookmarkable until
    // some unrelated control moved, and the pending push landing on that edit instead. Sync here
    // so every transition writes `?mode=` at the moment it happens. (The snapshot branch already
    // synced via refreshSnapshot; this second call is a no-op replace with identical values.)
    else syncUrl();
  }
  // SVG format toggle: swap the static snapshot between raster and vector. Pressing it while a
  // live lane is active drops back to the static vector render; pressing it in the static lane
  // swaps the extension in place.
  if (svgToggle) {
    svgToggle.addEventListener("click", function () {
      var turnOn = !svgOn();
      svgToggle.setAttribute("aria-pressed", turnOn ? "true" : "false");
      if (turnOn && (live.checked || wasmActive())) {
        setMode("png"); // enterMode("png") reads svgOn() → renders the .svg
      } else {
        snapshotExt = turnOn ? ".svg" : ".png";
        root.setAttribute("data-mode", turnOn ? "svg" : "snapshot");
        refreshSnapshot();
      }
    });
  }
  // "Full page (scroll)" re-renders the active snapshot format and reshapes both export URLs.
  if (scrollLong) {
    scrollLong.addEventListener("change", function () {
      refreshSnapshot();
    });
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
  var wasmHonouredControlIds = ["localeTag", "fontScale"];
  var alwaysDark = root.getAttribute("data-always-dark") === "1";
  function syncServerControls() {
    // The in-browser Wasm lane only honours the wasm-honoured trio (uiMode/locale/fontScale) +
    // knobs (see wasmOverridePatch); size/device/orientation/background and the app-theme
    // selector re-point /render, which the iframe ignores. So while Wasm is the active lane they
    // are dead — disable them (even on a catalog that can otherwise re-render) and restore them
    // when the lane leaves Wasm. Called on every mode transition, so the states track the lane.
    var onWasm = wasmActive();
    // The RC canvas lane, like Wasm, honours only its own overrides (the Remote Compose knobs,
    // applied client-side): size/device/locale/theme all re-point /render, which the painted
    // canvas ignores. So it's as "dead" for the server + wasm-honoured controls as the Wasm lane.
    var onRcCanvas = rcActive();
    var onRcWasm = rcWasmActive();
    var onRc = onRcCanvas || onRcWasm;
    var canServerRender =
      !onWasm && !onRc && (!staticSnapshot || canRenderOverrides || !!(live && live.checked));
    serverOnlyControlIds.forEach(function (id) {
      var el = document.getElementById("cp-" + id);
      if (el) el.disabled = !canServerRender;
    });
    // The wasm-honoured trio stays live in the in-browser Wasm lane (the app applies it), so it's
    // enabled whenever the server can render OR a Wasm app backs the session — but not in the RC
    // canvas lane, which doesn't map them onto the document.
    wasmHonouredControlIds.forEach(function (id) {
      var el = document.getElementById("cp-" + id);
      if (el) el.disabled = (id === "uiMode" && alwaysDark) ||
        !(canServerRender || (wasmSrc && !onRc) || (id === "uiMode" && onRcWasm));
    });
    // Day/Night options work in Wasm; declared provider options need the daemon. Keep the unified
    // select usable whenever at least one kind can work, and gate its individual option families.
    if (themeChoice) {
      var hasDeclaredThemes = themeChoice.getAttribute("data-has-declared-themes") === "true";
      // This preview's SUBJECT is a theme (@FixedTheme, or a Themes-section specimen). Both axes
      // are off: `theme:<provider>` redraws it under another theme, and Day/Night is a `uiMode`
      // override that re-renders it in the opposite mode rather than navigating to the baked
      // sibling. Recomputed here rather than left to the server's `disabled` attribute, because
      // this block reassigns `themeChoice.disabled` outright and would otherwise re-enable it.
      var fixedTheme = themeChoice.getAttribute("data-fixed-theme") === "true";
      var canProviderTheme = !fixedTheme && hasDeclaredThemes && !onWasm && !onRc &&
        (!staticSnapshot || canRenderOverrides);
      // Wear has no day/night axis, but Night (Default) must remain selectable when provider
      // themes are offered so the visitor can clear a chosen provider and return to the app.
      var canDefaultTheme = !fixedTheme && !onRc &&
        ((!alwaysDark && (canServerRender || !!wasmSrc)) || (alwaysDark && canProviderTheme));
      Array.prototype.forEach.call(themeChoice.options, function (option) {
        option.disabled = option.value.indexOf("theme:") === 0 ? !canProviderTheme : !canDefaultTheme;
      });
      themeChoice.disabled = !canDefaultTheme && !canProviderTheme;
    }
    // Remote Compose knobs are LIVE in the RC canvas lane — an edit applies client-side via
    // setNamed*Override + repaint (onRcKnobChanged), no daemon needed — so enable them whenever
    // that lane is active. The CMP/Wasm lane applies the same typed values while reloading its
    // isolated document; outside either browser RC lane they're gated on server rendering.
    document.querySelectorAll(".cp-rc-knob").forEach(function (el) {
      el.disabled = (rcActive() || rcWasmActive()) ? false
        : (onWasm || !(!staticSnapshot || canRenderOverrides));
    });
  }
  // Programmatic switch (the live toggle, or a wasm-only control auto-enabling Wasm): tick the
  // hidden mode radio so its state is consistent, then run the transition.
  function setMode(m) {
    var r = document.getElementById(
      m === "live" ? "cp-live" :
      m === "wasm" ? "cp-wasm-toggle" :
      m === "rc-wasm" ? "cp-rc-wasm-toggle" :
      m === "rc" ? "cp-rc-toggle" : "cp-mode-png");
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
  // The dedicated in-browser Wasm toggle (present only when the session has BOTH a daemon lane
  // and a Wasm app). When it's here, "Live preview" owns the daemon lane and this owns the Wasm
  // lane, so the Wasm tier is reachable rather than hidden behind bestLiveMode()'s daemon
  // preference. Null in the daemon-only / wasm-only cases, where the single toggle stays generic.
  var wasmBtn = document.getElementById("cp-wasm-btn");
  // Present only when GitHub auth is the one thing blocking the daemon lane (see ServeWeb's
  // liveSignInLink). Deliberately not `#cp-live-toggle` — it's a link, so the toggle's
  // `.disabled` / `aria-pressed` handling must not touch it — which is why it's looked up
  // separately here rather than inferred from `liveToggle`.
  var liveSignIn = document.getElementById("cp-live-signin");
  var modeHint = document.getElementById("cp-mode-hint");
  function liveTransportAvailable() { return (live && !live.disabled) || !!wasmToggle; }
  function bestLiveMode() { return (live && !live.disabled) ? "live" : (wasmToggle ? "wasm" : null); }
  function anyLiveActive() { return !!(live && live.checked) || !!(wasmToggle && wasmToggle.checked); }
  function updateLiveToggle() {
    var liveOn = !!(live && live.checked);
    var wasmOn = !!(wasmToggle && wasmToggle.checked);
    if (liveToggle) {
      // With a separate Wasm button, "Live preview" reflects the daemon lane alone; without one
      // it's the generic interactive toggle that lights for either lane.
      var livePressed = wasmBtn ? liveOn : (liveOn || wasmOn);
      liveToggle.setAttribute("aria-pressed", livePressed ? "true" : "false");
      liveToggle.disabled = wasmBtn ? !(live && !live.disabled) : !liveTransportAvailable();
    }
    if (wasmBtn) { wasmBtn.setAttribute("aria-pressed", wasmOn ? "true" : "false"); }
    if (modeHint) {
      modeHint.textContent = (liveOn || wasmOn) ? "interactive — click / scroll the preview"
        // "no live lane" is only true when there is genuinely nothing to switch to. When the lane
        // exists and is merely behind sign-in, the transport radio is (correctly) disabled, so
        // liveTransportAvailable() is false and this used to read "no live lane" right beside a
        // chip offering to sign in for one — telling the visitor in the same breath that the thing
        // is available and that it doesn't exist. The sign-in link's presence is the signal.
        : (liveTransportAvailable() ? "static snapshot"
          : (liveSignIn ? "static snapshot — sign in for live"
            : "static snapshot (no live lane)"));
    }
  }
  if (liveToggle) {
    liveToggle.addEventListener("click", function () {
      if (wasmBtn) {
        // Daemon lane specifically — the Wasm button owns the in-browser lane.
        if (live && live.checked) { setMode("png"); }
        else if (live && !live.disabled) { setMode("live"); }
      } else if (anyLiveActive()) { setMode("png"); }
      else { var m = bestLiveMode(); if (m) setMode(m); }
    });
  }
  if (wasmBtn) {
    wasmBtn.addEventListener("click", function () {
      if (wasmActive()) { setMode("png"); } else { setMode("wasm"); }
    });
  }
  if (rcBtn) {
    rcBtn.addEventListener("click", function () {
      if (rcActive()) { setMode("png"); } else { setMode("rc"); }
    });
  }
  // ---- RC backend selector -----------------------------------------------------------------
  // Chips choose which Remote Compose player draws the stage: `js` (the in-browser canvas lane,
  // via setMode("rc")), or a server-side player (`java` / `cmp-android`) that re-renders the PNG
  // with rcPlayer=<wire> (see query()). `cmp-jvm` is rendered disabled (no Skiko draw path yet).
  // The pressed chip tracks the active backend and stays in sync with the js canvas toggle.
  if (rcBackendsEl) {
    var rcChips = rcBackendsEl.querySelectorAll(".cp-rc-backend[data-rc-backend]");
    var rcDefaultBackend = rcBackendsEl.getAttribute("data-default") || "";
    // Assign the hoisted stub with the real reconciler (see the declaration before query()).
    syncRcBackendChips = function () {
      // The js lane wins the "current" marker whenever its canvas is active; otherwise it's the
      // picked server backend, or the default until the visitor picks one. Any OTHER active lane
      // (Live / Wasm) means no server backend is on screen, so no chip is marked current.
      var active =
        rcWasmActive() ? "cmp-wasm"
        : rcActive() ? "js"
        : anyLiveActive() ? ""
        : (rcPlayerPicked ? rcPlayerBackend : rcDefaultBackend);
      Array.prototype.forEach.call(rcChips, function (c) {
        var on = c.getAttribute("data-rc-backend") === active;
        c.setAttribute("aria-pressed", on ? "true" : "false");
      });
    };
    function pickRcBackend(w) {
      if (w === "js") {
        // The client canvas lane. Leave the server pick untouched so returning to a server
        // backend restores it. setMode("rc") (via enterMode) closes any Live/Wasm lane, opens the
        // canvas, and re-syncs the chips; if the canvas is already up there's nothing to do but
        // reconcile the marker.
        rcPlayerPicked = false;
        if (!rcActive()) setMode("rc");
        else syncRcBackendChips();
      } else if (w === "cmp-wasm") {
        rcPlayerPicked = false;
        if (!rcWasmActive()) setMode("rc-wasm");
        else syncRcBackendChips();
      } else {
        // A server-side backend. Record the pick FIRST so the single static-lane render carries
        // rcPlayer=<wire>, then transition to the static snapshot exactly once for EVERY other
        // lane — js canvas, Live, or Wasm. setMode("png") → enterMode("png") closes them all,
        // renders the snapshot once (no racing double render), and re-syncs the chips. Without
        // this a pick made while Live/Wasm was active only reloaded the hidden <img> and left the
        // interactive renderer on screen under a pressed chip.
        rcPlayerBackend = w;
        rcPlayerPicked = true;
        setMode("png");
      }
    }
    Array.prototype.forEach.call(rcChips, function (c) {
      if (c.disabled) return;
      c.addEventListener("click", function () {
        pickRcBackend(c.getAttribute("data-rc-backend"));
      });
    });
    syncRcBackendChips();
  }
  // Keep the live canvas overlay tracking the snapshot's slot when the page reflows (the Wasm
  // overlay has its own resize hook below; this covers a live session with no Wasm app).
  window.addEventListener("resize", function () {
    if (live && live.checked && !canvas.hidden) fitLiveCanvas();
    if (rcWasmActive()) positionRcWasmFrame();
  });
  // Re-pin the active overlay whenever the snapshot image itself loads — its first render, or a
  // re-render at a new size. positionOverlay/fitLiveCanvas measure `img.getBoundingClientRect()`,
  // which only becomes final once the new bytes are decoded, so without this an overlay picked
  // (e.g. Live selected before the first /render lands) or a re-rendered snapshot kept the stale
  // box until the next window resize (issue #2359).
  img.addEventListener("load", function () {
    if (live && live.checked && !canvas.hidden) fitLiveCanvas();
    // Mirror the Wasm resize handler: the checkerboard phase moves with the overlay box, so
    // re-hand the patch (which carries bgPhase) to a ready app — not just reposition the frame.
    if (wasmActive()) {
      positionWasmFrame();
      if (wasmReady && wasmFrame.contentWindow) {
        wasmFrame.contentWindow.postMessage(wasmOverridePatch(), "*");
      }
    }
    if (rcWasmActive()) positionRcWasmFrame();
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
  if (themeChoice) themeChoice.addEventListener("change", function () {
    themeChoice.setAttribute("data-theme-active", "1");
    // Like a lane switch: a picked theme earns its own history entry.
    urlPush = true;
    if (chosenThemeProvider()) onKnobChanged();
    else onControlsChanged();
  });
  // Detected-feature toggles (Keyboard focus) re-render on the daemon like a knob — same routing,
  // never the wasm auto-enable path.
  document.querySelectorAll(".cp-feature").forEach(function (el) {
    el.addEventListener("change", onKnobChanged);
  });
  // Remote Compose knobs apply in-browser in both RC lanes: repaint for JS, isolated reload for
  // CMP/Wasm. Otherwise they route through the server daemon like theme/feature controls.
  function onRcKnobChanged() {
    if (rcActive()) { refreshLinks(); applyRcOverrides(); return; }
    if (rcWasmActive()) { refreshLinks(); openRcWasm(); return; }
    onKnobChanged();
  }
  document.querySelectorAll(".cp-rc-knob").forEach(function (el) {
    el.addEventListener(el.type === "checkbox" ? "change" : "input", onRcKnobChanged);
  });
  // ——— Address-bar state ————————————————————————————————————————————————————————————————————
  //
  // The viewer's controls already produce a shareable /render URL; until now the *page* URL said
  // nothing about them, so a bookmark of "this preview, Dynamic Dark, RTL, font scale 1.3"
  // reopened on the preview's defaults. The params are exactly the /render override names, so the
  // viewer URL and the copyable render URL describe the same state and a param learned from one
  // works in the other.
  //
  // Only the params below are ours: `token` / `session` (and anything else the server put on the
  // URL) are never touched, and a control returning to its default *removes* its param rather
  // than pinning a redundant value, so an untouched viewer keeps the clean URL it was opened
  // with.
  var URL_STATE_PARAMS = [
    "device", "localeTag", "orientation", "background", "fontScale",
    "uiMode", "themeProvider", "focus", "gestures", "scroll", "mode", "sizeMode", "rcPlayer",
    "widthPx", "heightPx", "minWidthPx", "minHeightPx", "maxWidthPx", "maxHeightPx",
  ];
  function ownsUrlParam(name) {
    return URL_STATE_PARAMS.indexOf(name) >= 0 ||
      name.indexOf("knob.") === 0 || name.indexOf("rc.") === 0;
  }
  function currentMode() {
    var checked = document.querySelector("input[name=\"cp-mode\"]:checked");
    return checked ? checked.value : "png";
  }
  // Set before a discrete choice (a lane switch, a theme pick) so the sync it triggers PUSHES a
  // history entry — Back then returns to the previous lane/theme. Continuous edits (a slider, a
  // typed knob) leave it false and replace instead, so one drag can't bury the catalog page under
  // fifty entries. Consumed by the first sync that follows.
  var urlPush = false;
  function syncUrl() {
    var push = urlPush;
    urlPush = false;
    if (!window.cpUrlState) return;
    var values = {};
    new URLSearchParams(query()).forEach(function (value, name) {
      if (ownsUrlParam(name)) values[name] = value;
    });
    if (scrollLong && scrollLong.checked) values.scroll = "long";
    var mode = currentMode();
    if (mode !== "png") values.mode = mode;
    var sizeModeEl = document.getElementById("cp-sizeMode");
    if (sizeModeEl && sizeModeEl.value) values.sizeMode = sizeModeEl.value;
    window.cpUrlState.sync(values, ownsUrlParam, !push);
  }
  // What the controls hold when the URL names nothing — captured after the server markup and the
  // sticky-theme script have had their say, so Back out of a choice restores the page as it first
  // opened rather than whatever localStorage was last written with.
  var initialTheme = themeChoice ? themeChoice.value : "";
  var initialThemeActive = themeChoice ? themeChoice.getAttribute("data-theme-active") : "0";
  // px on the wire (like every override), dp in the input — the inverse of sizePx().
  function setSizeInput(id, px) {
    var el = document.getElementById(id);
    if (!el) return;
    var value = parseFloat(px);
    el.value = value > 0 ? String(Math.round(value / renderDensity)) : "";
  }
  // Restore every owned control from the URL. Also runs for Back/Forward, so a param the entry
  // does NOT carry has to reset its control — leaving the live value would make the restored page
  // disagree with its own URL.
  function hydrateFromUrl(popped) {
    var q = new URLSearchParams(location.search);
    fields.forEach(function (f) {
      var el = document.getElementById("cp-" + f);
      if (el) el.value = q.get(f) || "";
    });
    if (fs) {
      var scale = q.get("fontScale");
      fontScaleTouched = !!scale;
      fs.value = scale || "1.0";
      if (fsVal) fsVal.textContent = scale ? fs.value : "default";
    }
    if (scrollLong) scrollLong.checked = q.get("scroll") === "long";
    ["focus", "gestures"].forEach(function (f) {
      var el = document.getElementById("cp-" + f);
      if (el) el.checked = q.get(f) !== null;
    });
    var sizeModeEl = document.getElementById("cp-sizeMode");
    if (sizeModeEl) {
      sizeModeEl.value = q.get("sizeMode") || "";
      setSizeInput("cp-fixedW", q.get("widthPx"));
      setSizeInput("cp-fixedH", q.get("heightPx"));
      setSizeInput("cp-minW", q.get("minWidthPx"));
      setSizeInput("cp-minH", q.get("minHeightPx"));
      setSizeInput("cp-maxW", q.get("maxWidthPx"));
      setSizeInput("cp-maxH", q.get("maxHeightPx"));
      if (typeof syncSizeRows === "function") syncSizeRows();
    }
    document.querySelectorAll(".cp-knob").forEach(function (el) {
      var key = el.getAttribute("data-knob-key");
      if (!key) return;
      var value = q.get("knob." + key);
      if (value === null) value = el.getAttribute("data-knob-initial") || "";
      if (el.type === "checkbox") el.checked = (value === "true" || value === "1");
      else el.value = value;
    });
    document.querySelectorAll(".cp-rc-knob").forEach(function (el) {
      var name = el.getAttribute("data-rc-name");
      if (!name) return;
      var kind = el.getAttribute("data-rc-kind") || "";
      var value = q.get("rc." + name);
      if (value === null) value = el.getAttribute("data-rc-initial") || "";
      else if (kind && value.indexOf(kind + ":") === 0) value = value.substring(kind.length + 1);
      if (el.type === "checkbox") el.checked = (value === "true" || value === "1");
      else el.value = value;
    });
    // The theme select is seeded (from the URL first, then localStorage) by the sticky script
    // before this file runs, so the initial pass must not touch it. A Back/Forward pass owns it:
    // the entry's theme, or the one the page opened with when it names none.
    if (popped && themeChoice) {
      var provider = q.get("themeProvider");
      var uiMode = q.get("uiMode");
      var choice = provider ? "theme:" + provider
        : (uiMode === "light" || uiMode === "dark" ? uiMode : "");
      var offered = false;
      Array.prototype.forEach.call(themeChoice.options, function (o) {
        if (choice && o.value === choice) offered = true;
      });
      themeChoice.value = offered ? choice : initialTheme;
      themeChoice.setAttribute("data-theme-active", offered ? "1" : initialThemeActive);
    }
  }
  hydrateFromUrl(false);
  // Read the bookmarked lane NOW, before the first refreshSnapshot's sync clears a param no
  // control is holding yet. It is applied at the very bottom of this file, once the snapshot every
  // lane falls back to has been requested.
  var initialUrlMode = new URLSearchParams(location.search).get("mode") || "";
  if (window.cpUrlState) {
    window.cpUrlState.onPop(function () {
      hydrateFromUrl(true);
      var mode = currentMode();
      var wanted = new URLSearchParams(location.search).get("mode") || "png";
      // A lane change re-renders through enterMode; otherwise the restored overrides go out over
      // whichever transport is already up. Either way nothing reloads.
      if (wanted !== mode) setMode(wanted);
      else onControlsChanged();
    });
  }
  // Reconcile the control enabled-state + the toggle's initial look with the session's
  // capabilities (matches the server-rendered markup; keeps them in sync after hydration).
  syncServerControls();
  updateLiveToggle();
  refreshSnapshot();
  // A bookmarked `?mode=live` / `wasm` / `rc` opens in that lane — but only once the initial
  // snapshot has LANDED, not merely been requested.
  //
  // The stage's <img> is emitted with no src: the refreshSnapshot() above is the only thing that
  // will ever put pixels in it. Entering an interactive lane cancels any in-flight snapshot
  // (cancelSnapshotLoading bumps the generation), so switching immediately would discard that one
  // render and leave a cold bookmarked load looking at an empty stage behind a lane that may take
  // seconds to paint — or that fails and shows an activation error over nothing. Waiting for the
  // frame first makes the bookmark land in exactly the state a visitor reaches by loading the page
  // and clicking the toggle, which is the whole claim.
  //
  // Bounded, because the snapshot may never settle: a render that errors sets no src (so neither
  // event fires) and one that hangs would strand the bookmark on the snapshot lane forever.
  // A mode this session doesn't offer (no daemon, no Wasm app) is ignored rather than entering a
  // lane whose control is absent or disabled — the page stays on the snapshot and the param clears
  // on the next sync.
  (function () {
    var wanted = initialUrlMode;
    if (!wanted || wanted === "png" || wanted === currentMode()) return;
    var radio = null;
    Array.prototype.forEach.call(
      document.querySelectorAll("input[name=\"cp-mode\"]"),
      function (r) { if (r.value === wanted) radio = r; });
    if (!radio || radio.disabled) return;
    var entered = false;
    function enterBookmarkedMode() {
      if (entered) return;
      entered = true;
      img.removeEventListener("load", enterBookmarkedMode);
      img.removeEventListener("error", enterBookmarkedMode);
      setMode(wanted);
    }
    img.addEventListener("load", enterBookmarkedMode);
    img.addEventListener("error", enterBookmarkedMode);
    setTimeout(enterBookmarkedMode, 8000);
  })();
})();
