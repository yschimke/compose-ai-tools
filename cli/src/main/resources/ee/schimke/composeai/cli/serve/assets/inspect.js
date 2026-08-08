// Inspection layers for the viewer: draw what the render *is made of* over the frame it produced,
// as numbered boxes on the image plus a legend beside it.
//
// Three layers, each a checkbox in the Overrides panel's "Inspect" group:
//
//   - Accessibility — `/render/<id>.a11y`: the `a11y/hierarchy` nodes (what a screen reader sees:
//     label, role, states, focusable-or-merged), with `a11y/atf` findings and `a11y/touchTargets`
//     sizes folded onto the node whose bounds they match. This replaces the old daemon-composited
//     "Accessibility (TalkBack)" toggle, which baked one focus rectangle and a wall of spoken text
//     into the pixels: unreadable at preview size, un-hoverable, and gone from the exported PNG's
//     meaning the moment you looked away from it.
//   - Typography — `/render/<id>.annotations`, kind `typography`: the size / face / weight / line
//     height each text node actually resolved.
//   - Theme attributes — the same endpoint, kind `theme`: resolved fill, border, corner radius and
//     shape per container.
//
// The box + numbered-badge + legend idiom is deliberately the compare page's
// (`format-compare.js`'s annotation layers), because a spec label is far wider than the box it
// describes: the box carries an index, the readable text lives in the legend, and hovering either
// one lights up the other.
//
// Geometry: every source reports bounds in the *render's own pixel space*, which is exactly the
// snapshot `<img>`'s natural size — so one uniform scale (`clientWidth / naturalWidth`) places
// every layer, re-applied on resize and whenever new pixels land.
(function () {
  "use strict";
  var root = document.querySelector(".cp-viewer");
  var img = document.getElementById("cp-img");
  var layer = document.getElementById("cp-inspect-layer");
  var legend = document.getElementById("cp-inspect-legend");
  var toggles = document.querySelectorAll(".cp-inspect");
  if (!root || !img || !layer || !legend || !toggles.length) return;

  var previewId = root.getAttribute("data-preview-id");
  var base = location.pathname.replace(/\/p\/[^/]*\/?$/, "");
  var params = new URLSearchParams(location.search);
  var token = params.get("token") || "";
  var session = params.get("session") || "";

  // Distinct hues per node for the accessibility layer's un-flagged stops — the same intent as the
  // VS Code panel's a11y palette: with one colour for everything, adjacent focus targets in a list
  // merge into a single block and the legend can't be matched back to a box by eye.
  var PALETTE = [
    "#f28b82", "#aecbfa", "#a8dab5", "#fdd663",
    "#d7aefb", "#fcad70", "#80cbc4", "#f6aea9",
  ];

  var LAYERS = [
    { kind: "a11y", label: "Accessibility", source: "a11y" },
    { kind: "typography", label: "Typography", source: "annotations" },
    { kind: "theme", label: "Theme", source: "annotations" },
  ];

  // Cache per (source × frame): both annotation layers come from ONE fetch, and re-ticking a layer
  // must not re-run a daemon render that produced the same frame we are already looking at.
  var cache = {};
  var cacheKey = "";
  // Entries currently drawn, in legend order. Rebuilt whenever the active layers change.
  var entries = [];
  var boxes = [];
  var activeId = null;

  function activeKinds() {
    var out = [];
    Array.prototype.forEach.call(toggles, function (el) {
      if (el.checked && !el.disabled) out.push(el.getAttribute("data-cp-inspect"));
    });
    return out;
  }

  // The URL of the frame ON SCREEN, which viewer.js records once its bytes have decoded. Deriving
  // the data URLs from it (rather than rebuilding the override query here) is what guarantees the
  // overlay describes the pixels the visitor is looking at, including every knob and display axis,
  // with no second copy of query()'s rules to keep in step.
  function frameUrl() {
    return img.getAttribute("data-cp-src") || "";
  }

  function dataUrl(suffix) {
    var src = frameUrl();
    if (src) {
      var cut = src.indexOf("?");
      var path = cut < 0 ? src : src.substring(0, cut);
      var qs = cut < 0 ? "" : src.substring(cut);
      // Only the format suffix changes; a `scroll=long` frame has no inspection product of its
      // own, so it falls back to the viewport-sized one rather than 500ing.
      path = path.replace(/\.(png|svg)$/, "");
      return path + "." + suffix + qs;
    }
    var parts = [];
    if (token) parts.push("token=" + encodeURIComponent(token));
    if (session) parts.push("session=" + encodeURIComponent(session));
    return base + "/render/" + encodeURIComponent(previewId) + "." + suffix +
      (parts.length ? "?" + parts.join("&") : "");
  }

  function fetchSource(source) {
    if (cacheKey !== frameUrl()) { cache = {}; cacheKey = frameUrl(); }
    if (cache[source]) return cache[source];
    var pending = fetch(dataUrl(source), { credentials: "same-origin" })
      .then(function (r) {
        if (!r.ok) throw new Error(source + " " + r.status);
        return r.json();
      })
      .catch(function () { return null; });
    cache[source] = pending;
    return pending;
  }

  // ---- entry building ------------------------------------------------------

  function parseBounds(wire) {
    var parts = String(wire || "").split(",");
    if (parts.length !== 4) return null;
    var n = parts.map(function (p) { return parseInt(p, 10); });
    if (n.some(isNaN)) return null;
    var box = { x: n[0], y: n[1], width: n[2] - n[0], height: n[3] - n[1] };
    return box.width > 0 && box.height > 0 ? box : null;
  }

  function levelOf(raw) {
    var lower = String(raw || "").toLowerCase();
    if (lower === "error") return "error";
    if (lower === "warning" || lower === "warn") return "warning";
    return "info";
  }

  /**
   * Accessibility entries: one per screen-reader stop (`merged` nodes — an unmerged inner Text
   * duplicates its focusable ancestor's box, so drawing it stacks a second rectangle on the same
   * pixels), carrying the ATF findings and touch-target size whose bounds match it.
   */
  function a11yEntries(payload) {
    if (!payload) return [];
    var nodes = payload.nodes || [];
    var findings = payload.findings || [];
    var targets = payload.touchTargets || [];
    var findingsByBounds = {};
    findings.forEach(function (f) {
      var key = (f.boundsInScreen || "").trim();
      if (!key) return;
      (findingsByBounds[key] = findingsByBounds[key] || []).push(f);
    });
    var targetByBounds = {};
    targets.forEach(function (t) {
      var key = (t.boundsInScreen || "").trim();
      if (key) targetByBounds[key] = t;
    });
    var out = [];
    nodes.forEach(function (node, index) {
      // The daemon omits `merged: true` from the wire (it is the Kotlin default), so anything
      // other than an explicit `false` is a focus stop.
      if (node.merged === false) return;
      var bounds = parseBounds(node.boundsInScreen);
      if (!bounds) return;
      var key = (node.boundsInScreen || "").trim();
      var matched = findingsByBounds[key] || [];
      var target = targetByBounds[key];
      var level = null;
      matched.forEach(function (f) {
        var l = levelOf(f.level);
        if (l === "error" || (l === "warning" && level !== "error")) level = l;
      });
      if (!level && target && target.findings && target.findings.length) level = "warning";
      var detail = [];
      if (node.role) detail.push(node.role);
      if (node.states && node.states.length) detail.push(node.states.join(", "));
      if (target) {
        detail.push(Math.round(target.widthDp) + "×" + Math.round(target.heightDp) + " dp");
      }
      matched.forEach(function (f) { detail.push(f.type + ": " + f.message); });
      if (target && target.findings) {
        target.findings.forEach(function (f) { detail.push(f); });
      }
      out.push({
        kind: "a11y",
        bounds: bounds,
        title: node.label || "(unlabelled)",
        detail: detail.join(" · "),
        level: level || "info",
        color: level ? null : PALETTE[index % PALETTE.length],
      });
    });
    // Findings the hierarchy has no node for are still real problems — surface them rather than
    // dropping them on the floor because the bounds didn't line up.
    var known = {};
    nodes.forEach(function (n) {
      var key = (n.boundsInScreen || "").trim();
      if (key) known[key] = true;
    });
    findings.forEach(function (f) {
      var key = (f.boundsInScreen || "").trim();
      if (key && known[key]) return;
      var bounds = parseBounds(f.boundsInScreen);
      if (!bounds) return;
      out.push({
        kind: "a11y",
        bounds: bounds,
        title: f.viewDescription || "(no element)",
        detail: f.type + ": " + f.message,
        level: levelOf(f.level),
        color: null,
      });
    });
    return out;
  }

  /** Typography / theme entries, straight off the shared design-annotation payload. */
  function annotationEntries(payload, kind) {
    if (!payload) return [];
    return (payload.annotations || [])
      .filter(function (a) { return a && a.kind === kind && a.bounds; })
      .map(function (a) {
        return {
          kind: kind,
          bounds: a.bounds,
          title: a.role || a.label,
          detail: a.role ? a.label : "",
          level: "info",
          color: null,
        };
      });
  }

  // ---- rendering -----------------------------------------------------------

  function place() {
    var natural = img.naturalWidth;
    if (!natural || !boxes.length) return;
    var scale = img.clientWidth / natural;
    layer.style.width = img.clientWidth + "px";
    layer.style.height = img.clientHeight + "px";
    // The stage centres the image, so the layer has to sit where the image sits rather than at the
    // stage's own origin — otherwise every box drifts left by half the slack.
    layer.style.left = img.offsetLeft + "px";
    layer.style.top = img.offsetTop + "px";
    boxes.forEach(function (box) {
      box.node.style.left = (box.bounds.x * scale) + "px";
      box.node.style.top = (box.bounds.y * scale) + "px";
      box.node.style.width = (box.bounds.width * scale) + "px";
      box.node.style.height = (box.bounds.height * scale) + "px";
    });
  }

  function highlight(id) {
    activeId = id;
    boxes.forEach(function (box) {
      box.node.classList.toggle("cp-inspect-box-active", box.id === id);
    });
    Array.prototype.forEach.call(
      legend.querySelectorAll("[data-cp-entry]"),
      function (row) {
        row.classList.toggle(
          "cp-inspect-entry-active",
          row.getAttribute("data-cp-entry") === id,
        );
      },
    );
  }

  function draw() {
    layer.textContent = "";
    legend.textContent = "";
    boxes = [];
    if (!entries.length) {
      legend.hidden = true;
      root.removeAttribute("data-inspect");
      return;
    }
    root.setAttribute("data-inspect", "on");
    legend.hidden = false;

    var head = document.createElement("div");
    head.className = "cp-inspect-legend-head";
    head.textContent = "Inspect";
    var count = document.createElement("span");
    count.className = "cp-inspect-legend-count";
    count.textContent = String(entries.length);
    head.appendChild(count);
    legend.appendChild(head);

    LAYERS.forEach(function (spec) {
      var mine = entries.filter(function (e) { return e.kind === spec.kind; });
      if (!mine.length) return;
      var section = document.createElement("div");
      section.className = "cp-inspect-section";
      var title = document.createElement("div");
      title.className = "cp-inspect-section-head";
      title.textContent = spec.label + " (" + mine.length + ")";
      section.appendChild(title);
      var list = document.createElement("ol");
      list.className = "cp-inspect-list";
      mine.forEach(function (entry, index) {
        var ordinal = String(index + 1);
        var id = spec.kind + "-" + index;

        var box = document.createElement("div");
        box.className = "cp-inspect-box";
        box.setAttribute("data-cp-kind", spec.kind);
        box.setAttribute("data-level", entry.level);
        box.title = entry.detail ? entry.title + " · " + entry.detail : entry.title;
        if (entry.color) box.style.setProperty("--cp-inspect-color", entry.color);
        var badge = document.createElement("span");
        badge.className = "cp-inspect-badge";
        badge.textContent = ordinal;
        box.appendChild(badge);
        box.addEventListener("mouseenter", function () { highlight(id); });
        box.addEventListener("mouseleave", function () { highlight(null); });
        layer.appendChild(box);
        boxes.push({ id: id, node: box, bounds: entry.bounds });

        var row = document.createElement("li");
        row.className = "cp-inspect-entry";
        row.setAttribute("data-cp-entry", id);
        row.setAttribute("data-cp-kind", spec.kind);
        row.setAttribute("data-level", entry.level);
        row.tabIndex = 0;
        if (entry.color) row.style.setProperty("--cp-inspect-color", entry.color);
        var marker = document.createElement("span");
        marker.className = "cp-inspect-badge";
        marker.textContent = ordinal;
        row.appendChild(marker);
        var text = document.createElement("span");
        text.className = "cp-inspect-text";
        var strong = document.createElement("strong");
        strong.textContent = entry.title;
        text.appendChild(strong);
        if (entry.detail) {
          var sub = document.createElement("span");
          sub.className = "cp-inspect-detail";
          sub.textContent = entry.detail;
          text.appendChild(sub);
        }
        row.appendChild(text);
        row.addEventListener("mouseenter", function () { highlight(id); });
        row.addEventListener("mouseleave", function () { highlight(null); });
        row.addEventListener("focus", function () { highlight(id); });
        row.addEventListener("blur", function () { highlight(null); });
        list.appendChild(row);
      });
      section.appendChild(list);
      legend.appendChild(section);
    });
    place();
    highlight(activeId);
  }

  // ---- refresh -------------------------------------------------------------

  var refreshGen = 0;
  function refresh() {
    var kinds = activeKinds();
    syncUrl(kinds);
    if (!kinds.length) {
      entries = [];
      draw();
      return;
    }
    var gen = ++refreshGen;
    legend.setAttribute("aria-busy", "true");
    var sources = {};
    kinds.forEach(function (kind) {
      LAYERS.forEach(function (spec) {
        if (spec.kind === kind) sources[spec.source] = true;
      });
    });
    var names = Object.keys(sources);
    Promise.all(names.map(fetchSource)).then(function (results) {
      if (gen !== refreshGen) return;
      legend.removeAttribute("aria-busy");
      var byName = {};
      names.forEach(function (name, i) { byName[name] = results[i]; });
      var next = [];
      LAYERS.forEach(function (spec) {
        if (kinds.indexOf(spec.kind) < 0) return;
        var payload = byName[spec.source];
        next = next.concat(
          spec.kind === "a11y" ? a11yEntries(payload) : annotationEntries(payload, spec.kind),
        );
      });
      entries = next;
      draw();
    });
  }

  // Deep-link / Back-Forward state: `?inspect=a11y,typography`. Written with replaceState so
  // ticking a layer doesn't stack history entries the way a knob edit does — it's a reading aid
  // over the same frame, not a different render.
  function syncUrl(kinds) {
    try {
      var url = new URL(location.href);
      if (kinds.length) url.searchParams.set("inspect", kinds.join(","));
      else url.searchParams.delete("inspect");
      history.replaceState(history.state, "", url.toString());
    } catch (e) {
      /* a browser that refuses the rewrite still gets the overlay */
    }
  }

  function hydrate() {
    var wanted = (params.get("inspect") || "").split(",").filter(Boolean);
    if (!wanted.length) return;
    Array.prototype.forEach.call(toggles, function (el) {
      if (wanted.indexOf(el.getAttribute("data-cp-inspect")) >= 0) el.checked = true;
    });
  }

  Array.prototype.forEach.call(toggles, function (el) {
    el.addEventListener("change", refresh);
  });
  window.addEventListener("resize", place);
  img.addEventListener("load", place);
  // New pixels ⇒ new geometry and new facts. viewer.js stamps `data-cp-src` once the replacement
  // frame has decoded, so that attribute is the one honest "the render changed" signal available
  // from here — cheaper and more accurate than re-deriving the override query on every control.
  if (typeof MutationObserver === "function") {
    new MutationObserver(function () {
      if (activeKinds().length) refresh();
      else place();
    }).observe(img, { attributes: true, attributeFilter: ["data-cp-src"] });
  }
  hydrate();
  if (activeKinds().length) refresh();
})();
