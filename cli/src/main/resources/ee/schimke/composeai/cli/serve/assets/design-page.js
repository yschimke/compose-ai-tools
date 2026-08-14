// Design page viewer: a whole page of the design file, inlined as SVG, with this catalog's renders
// standing in for the design's own drawing of the components it implements.
//
// THE SVG IS THE GEOMETRY, AND THAT IS THE WHOLE DESIGN
//
// The screen backdrop this replaced was a flat PNG, so its manifest had to carry a rectangle per
// component and this file did no measuring at all. An inlined SVG is a document: the node is right
// there, `data-node-id` names it, and its box is whatever the browser says it is. So the manifest
// carries no geometry, and everything positional here is measured rather than declared. That is
// strictly more accurate — a Figma export box includes effect bleed, so a recorded rectangle and
// the drawn shape disagree by a few pixels on anything with a shadow — and it is what makes the
// swap land exactly on the shape it replaces.
//
// Positions are written as percentages of the stage, so a resize only has to re-measure rather than
// re-place, and a stale measurement degrades into a small offset rather than a wrong corner.
(function () {
  "use strict";

  var root = document.getElementById("cp-design-page");
  if (!root) return;

  var stage = root.querySelector(".cp-page-stage");
  var svg = stage && stage.querySelector("svg");
  if (!stage || !svg) return;

  var lanes = root.querySelectorAll("[data-cp-page-lane]");
  var outlinesToggle = root.querySelector("[data-cp-page-outlines]");
  var unlinkedToggle = root.querySelector("[data-cp-page-unlinked]");
  var legend = root.querySelector(".cp-page-legend");
  var selection = root.querySelector("[data-cp-page-selection]");
  var list = root.querySelector(".cp-page-list");
  var disclosure = root.querySelector(".cp-page-nodes");

  var nodes = [];
  var byId = Object.create(null);
  var overlays = stage.querySelectorAll(".cp-page-node");
  for (var i = 0; i < overlays.length; i++) {
    var overlay = overlays[i];
    var id = overlay.getAttribute("data-cp-node") || "";
    var target = findInSvg(id);
    if (!target) {
      // Named by the manifest, absent from the export — a layer the design tool flattened on the
      // way out. Say so on the element rather than dropping it: the row in the list still shows the
      // mapping, and `[data-cp-missing]` is what a test (or a person wondering where their shape
      // went) can look for.
      overlay.setAttribute("data-cp-missing", "");
      continue;
    }
    var entry = { overlay: overlay, target: target };
    nodes.push(entry);
    byId[id] = entry;
  }

  // The renders are served inside an inert `<template>` and adopted when the lane that draws them
  // is entered. That is the lane the page opens on, so this normally runs on first paint; the
  // images carry `loading="lazy"`, which is what keeps a tall sheet from asking the daemon for
  // every node at once. A reader who flips to the spec and never flips back still pays nothing.
  //
  // A template rather than a `data-src` swap, deliberately: template content is inert, so the
  // browser parses it and loads none of its images until it is adopted — and it keeps every URL
  // server-built and server-escaped. Reading a URL out of the DOM and assigning it to `img.src` is
  // a taint path (CodeQL `js/xss-through-dom`), and not having the sink beats validating it.
  var renderSource = stage.querySelector("[data-cp-page-render-source]");

  function armRenders() {
    if (!renderSource) return;
    var images = renderSource.content.querySelectorAll(".cp-page-render");
    for (var r = 0; r < images.length; r++) {
      var image = images[r];
      var entry = byId[image.getAttribute("data-cp-node") || ""];
      // No entry means the export doesn't carry that node, so there is no box to put a render in
      // and nothing to hide. Skipping leaves the row in the list, which is the honest state.
      if (!entry) continue;
      entry.overlay.appendChild(image);
      // Marked here rather than at load: the class is what "hide the design's own" acts on, and a
      // node with no render must keep showing the design's drawing whatever the toggles say.
      entry.target.classList.add("cp-page-replaced");
    }
    renderSource.remove();
    renderSource = null;
    measure();
  }

  // Figma writes node ids with a colon (`58548:7249`); the same id appears hyphenated in its own
  // URLs, and a hand-written manifest may use either. Both are looked up rather than normalised,
  // because normalising would mean rewriting the export's attributes — far more invasive than
  // trying the second spelling.
  //
  // Built with `querySelector` on an ATTRIBUTE VALUE, which needs escaping, so the id is matched by
  // comparison instead — the same reasoning as `pair()` below. A node id is text from a design
  // file, and interpolating it into a selector has the same shape as an HTML injection.
  function findInSvg(id) {
    if (!id) return null;
    var alternate = id.indexOf(":") >= 0 ? id.replace(/:/g, "-") : id.replace(/-/g, ":");
    var all = svg.querySelectorAll("[data-node-id]");
    for (var j = 0; j < all.length; j++) {
      var value = all[j].getAttribute("data-node-id");
      if (value === id || value === alternate) return all[j];
    }
    return null;
  }

  function measure() {
    var box = stage.getBoundingClientRect();
    // A stage with no size yet (a page opened in a background tab, a font still loading) would put
    // every overlay at 0×0 and cache that. Leave the previous placement alone and wait for the
    // observer to fire again with a real box.
    if (box.width <= 0 || box.height <= 0) return;
    for (var k = 0; k < nodes.length; k++) {
      var entry = nodes[k];
      var rect = entry.target.getBoundingClientRect();
      if (rect.width <= 0 || rect.height <= 0) {
        entry.overlay.setAttribute("data-cp-missing", "");
        continue;
      }
      entry.overlay.removeAttribute("data-cp-missing");
      var style = entry.overlay.style;
      style.left = pct(rect.left - box.left, box.width);
      style.top = pct(rect.top - box.top, box.height);
      style.width = pct(rect.width, box.width);
      style.height = pct(rect.height, box.height);
    }
  }

  function pct(value, span) {
    return (value / span) * 100 + "%";
  }

  // A muted overlay is also taken out of the tab order and the accessibility tree. CSS alone can't
  // do this: `opacity: 0` + `pointer-events: none` still leaves a control focusable, so a keyboard
  // user could tab onto an invisible rectangle — no focus ring, no indication of where they are.
  //
  // Only the "what we don't implement" filter does this now. Whether the resting outlines are drawn
  // no longer decides whether a node can be pointed at: an unmarked sheet is the default, and a
  // sheet you cannot interrogate would be a picture.
  function syncFocusability() {
    var unlinkedOnly = unlinkedToggle && unlinkedToggle.checked;
    for (var n = 0; n < overlays.length; n++) {
      var spot = overlays[n];
      var inert = unlinkedOnly && spot.getAttribute("data-link") !== "unlinked";
      if (inert) {
        spot.setAttribute("tabindex", "-1");
        spot.setAttribute("aria-hidden", "true");
      } else {
        spot.removeAttribute("tabindex");
        spot.removeAttribute("aria-hidden");
      }
    }
  }

  // The resting layer of colour over every node: off by default, because the sheet is the content
  // and thirty-eight coloured rectangles are an answer to a question nobody asked yet. The legend
  // only explains marks that are actually on screen, so it follows the toggle rather than standing
  // above an unmarked sheet naming four colours it isn't showing.
  function applyOutlines() {
    if (!outlinesToggle) return;
    stage.classList.toggle("cp-page-outlines-on", outlinesToggle.checked);
    if (legend) legend.hidden = !outlinesToggle.checked;
  }

  function applyUnlinked() {
    if (!unlinkedToggle) return;
    var on = unlinkedToggle.checked;
    stage.classList.toggle("cp-page-unlinked-only", on);
    // A coverage filter with nothing to draw on is a no-op the reader can't see, so asking for it
    // turns the marks on. Unchecking leaves them on: it was an explicit state to arrive at, and
    // silently repainting the sheet plain would read as the filter having broken something.
    if (on && outlinesToggle && !outlinesToggle.checked) {
      outlinesToggle.checked = true;
      applyOutlines();
    }
    syncFocusability();
  }

  // The flip. One sheet, two lanes: this catalog's renders standing in the design's slots, or the
  // design's own drawing. Deliberately not a composite — no opacity, no difference blend. Those
  // answered "how close are these two pictures", which is the parity view's job; this page answers
  // "what does the spec say, and what did we build", and the eye compares two clean frames in the
  // same layout better than one muddy one.
  function applyLane() {
    var design = false;
    for (var l = 0; l < lanes.length; l++) {
      if (lanes[l].checked) design = lanes[l].value === "design";
    }
    if (!design) armRenders();
    stage.classList.toggle("cp-page-swap-on", !design);
    stage.classList.toggle("cp-page-hide-design", !design);
  }

  // Selecting a node puts its detail under the sheet and keeps the reader on the page, which is
  // what makes scanning several components in a row possible.
  //
  // The detail is the audit list's own row, CLONED. That keeps one server-built description of a
  // node instead of two that can disagree, and — the reason it is a clone rather than markup built
  // here — the row's `href` never passes through JavaScript as a string, so the link out cannot
  // become the taint path `armRenders` avoids for the same reason.
  var selected = null;

  function rowFor(nodeId) {
    if (!list) return null;
    var rows = list.querySelectorAll("[data-cp-node]");
    for (var r = 0; r < rows.length; r++) {
      if (rows[r].getAttribute("data-cp-node") === nodeId) return rows[r];
    }
    return null;
  }

  function select(nodeId) {
    selected = nodeId;
    for (var s = 0; s < overlays.length; s++) {
      var on = overlays[s].getAttribute("data-cp-node") === nodeId;
      overlays[s].classList.toggle("cp-page-selected", on);
      overlays[s].setAttribute("aria-pressed", on ? "true" : "false");
    }
    if (!selection) return;
    selection.textContent = "";
    var row = nodeId && rowFor(nodeId);
    if (row) {
      var clone = row.cloneNode(true);
      clone.classList.add("cp-page-selection-card");
      // The clone is a second element carrying the same `data-cp-node`, which is what makes the
      // hover pairing light the selection card too — wanted — but it must not answer `rowFor` on
      // the next selection, or the strip would start cloning itself.
      clone.removeAttribute("data-cp-node");
      selection.appendChild(clone);
    } else {
      var hint = document.createElement("p");
      hint.className = "cp-page-selection-hint";
      hint.textContent = "Pick a component on the sheet to see what implements it.";
      selection.appendChild(hint);
    }
  }

  for (var o = 0; o < overlays.length; o++) {
    (function (spot) {
      spot.addEventListener("click", function () {
        var id = spot.getAttribute("data-cp-node");
        // Pressing the selected node again clears it, so the sheet can be put back to plain
        // without hunting for a control that undoes it.
        select(selected === id ? null : id);
      });
    })(overlays[o]);
  }

  // Escape clears the selection from anywhere on the page — including from inside the disclosure,
  // where a reader who arrived by keyboard is most likely to be.
  root.addEventListener("keydown", function (event) {
    if (event.key === "Escape" && selected) {
      var spot = null;
      for (var e = 0; e < overlays.length; e++) {
        if (overlays[e].getAttribute("data-cp-node") === selected) spot = overlays[e];
      }
      select(null);
      // Focus would otherwise be left on an element that no longer says anything about itself.
      if (spot) spot.focus();
    }
  });

  // Hovering a row in the list highlights its node on the sheet, and vice versa — the cheapest way
  // to answer "which one is that?" on a sheet with thirty-five near-identical silhouettes.
  //
  // Compares attribute values rather than building a selector string out of one, for the reason
  // given on `findInSvg`.
  function pair(nodeId, on) {
    var all = root.querySelectorAll("[data-cp-node]");
    for (var p = 0; p < all.length; p++) {
      if (all[p].getAttribute("data-cp-node") === nodeId) {
        all[p].classList.toggle("cp-page-active", on);
      }
    }
  }

  var linked = root.querySelectorAll("[data-cp-node]");
  for (var q = 0; q < linked.length; q++) {
    (function (el) {
      var id = el.getAttribute("data-cp-node");
      el.addEventListener("mouseenter", function () {
        pair(id, true);
      });
      el.addEventListener("mouseleave", function () {
        pair(id, false);
      });
      el.addEventListener("focus", function () {
        pair(id, true);
      });
      el.addEventListener("blur", function () {
        pair(id, false);
      });
    })(linked[q]);
  }

  if (outlinesToggle) outlinesToggle.addEventListener("change", applyOutlines);
  if (unlinkedToggle) unlinkedToggle.addEventListener("change", applyUnlinked);
  for (var v = 0; v < lanes.length; v++) lanes[v].addEventListener("change", applyLane);
  // Opening the audit list changes nothing about the sheet, but it does change how tall the stage's
  // container is on a short viewport, and every overlay is placed off a measured box.
  if (disclosure) disclosure.addEventListener("toggle", measure);

  applyOutlines();
  applyUnlinked();
  applyLane();
  measure();

  if (typeof ResizeObserver === "function") {
    new ResizeObserver(measure).observe(stage);
  } else {
    window.addEventListener("resize", measure);
  }
  // Outlined text is the bulk of a specimen sheet and lands with the markup, but a page that also
  // carries a webfont or an embedded raster can reflow after first paint.
  window.addEventListener("load", measure);
})();
