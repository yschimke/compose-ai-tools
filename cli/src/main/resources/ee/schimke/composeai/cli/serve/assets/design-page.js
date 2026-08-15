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
  var tip = root.querySelector("[data-cp-page-tip]");
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
      standIn(image, entry.target);
    }
    renderSource.remove();
    renderSource = null;
    measure();
  }

  // The design's drawing is hidden ONLY once ours has actually arrived, and comes back if it never
  // does. Hiding on adoption instead — which is what this did while the swap was opt-in — leaves an
  // empty slot for any render the server can't produce: a preview that throws, a daemon that falls
  // over, a 404. That was survivable while the reader had opted in and could untick "hide the
  // design's own" to get the sheet back; the page opens on this lane now and that control is gone,
  // so a failed render would be a hole where the whole point is that something is in the slot.
  //
  // Also hides the failed `<img>` itself, or the browser's broken-image glyph would sit on top of
  // the drawing we just restored.
  function standIn(image, target) {
    if (image.complete && image.naturalWidth > 0) {
      target.classList.add("cp-page-replaced");
      return;
    }
    image.addEventListener("load", function () {
      image.hidden = false;
      target.classList.add("cp-page-replaced");
    });
    image.addEventListener("error", function () {
      image.hidden = true;
      target.classList.remove("cp-page-replaced");
    });
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
      // Keyed on the GAP, not on "unlinked" — the filter shows components with no code behind
      // them, and the sheet's private furniture and variant-set containers are neither.
      var inert = unlinkedOnly && !spot.hasAttribute("data-cp-gap");
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

  // The flip. One sheet, three lanes: this catalog's renders standing in the design's slots, the
  // design's own drawing, or the difference between them scored per node.
  //
  // The first two are deliberately not a composite — no opacity slider, no `difference` blend over
  // the whole sheet. Those answered "how close are these two pictures" by making the reader squint;
  // the diff lane answers it with a number and a map, and the eye compares two clean frames better
  // than one muddy one.
  function lane() {
    for (var l = 0; l < lanes.length; l++) {
      if (lanes[l].checked) return lanes[l].value;
    }
    return "code";
  }

  function applyLane() {
    var which = lane();
    var ours = which !== "design";
    if (ours) armRenders();
    stage.classList.toggle("cp-page-swap-on", ours);
    stage.classList.toggle("cp-page-hide-design", ours);
    stage.classList.toggle("cp-page-diff-on", which === "diff");
    if (which === "diff") score();
  }

  // ---- the diff lane -------------------------------------------------------------------------
  //
  // WHAT IS COMPARED, AND WHY IT IS THE SHEET ITSELF
  //
  // The reference is this page's own SVG, cropped to the node — not the component's imported
  // reference raster. Both are defensible, but only one is on the page already: cropping the export
  // needs no server round trip, no manifest field, and covers every node that has a render rather
  // than only those with an imported reference. It also answers the question the page actually
  // poses, which is about THIS slot: how far is our pixel from the design's pixel, here, at this
  // size, in the layout the designer drew.
  //
  // The scoring itself is `ComposePreviewCompare` — the same normalise-then-count used by the
  // viewer's spec lane and the format wall, so a number here means what a number there means.
  var compare = window.ComposePreviewCompare;
  var scored = false;

  // ONE raster of the sheet, cropped per node — not one clone of the sheet per node.
  //
  // The first cut cloned, serialised and URI-encoded the whole export for every scoreable node. On
  // this catalog's own Shape page that is 858 KB x 35 nodes, so entering the lane built well over
  // 100 MB of transient markup before a single comparison settled. One raster costs one
  // serialisation and one decode, and every crop is a `drawImage` out of it.
  //
  // Capped by TOTAL pixels rather than by side: the scorer downsamples to 192px on the longest side
  // anyway (`MAX_SIDE`), so resolution beyond "the smallest node still lands around 192px" buys
  // nothing and costs 4 bytes a pixel.
  var MAX_SHEET_PIXELS = 4e6;
  var sheetRaster = null;

  function rasteriseSheet() {
    if (sheetRaster) return sheetRaster;
    var view = svg.viewBox && svg.viewBox.baseVal;
    var units = view && view.width > 0 && view.height > 0
      ? { x: view.x, y: view.y, width: view.width, height: view.height }
      : null;
    if (!units) return (sheetRaster = Promise.resolve(null));
    var scale = Math.min(1, Math.sqrt(MAX_SHEET_PIXELS / (units.width * units.height)));
    var clone = svg.cloneNode(true);
    clone.setAttribute("width", String(Math.max(1, Math.round(units.width * scale))));
    clone.setAttribute("height", String(Math.max(1, Math.round(units.height * scale))));
    clone.removeAttribute("style");
    var markup = new XMLSerializer().serializeToString(clone);
    // A `data:` URL rather than a blob: nothing is allocated to leak, and the string is one this
    // script just built out of the page's own markup rather than anything a URL could be read from.
    var url = "data:image/svg+xml;charset=utf-8," + encodeURIComponent(markup);
    sheetRaster = new Promise(function (resolve) {
      var image = new Image();
      image.onload = function () {
        resolve({ image: image, units: units, scale: scale });
      };
      // A sheet that cannot be rasterised (a font it cannot reach, markup a browser refuses) scores
      // nothing rather than scoring wrongly.
      image.onerror = function () {
        resolve(null);
      };
      image.src = url;
    });
    return sheetRaster;
  }

  // The node's own drawing, cut out of that raster.
  //
  // The box is MEASURED — the node's client rect mapped through the root's — rather than read from
  // `getBBox()`. `getBBox()` answers in the element's own user space, so a `transform` on the node
  // or on any ancestor puts the crop somewhere else entirely; a measured rect has every transform
  // already applied, and it is the same mapping `measure()` uses to place the overlay, so the crop
  // and the slot cannot drift apart.
  //
  // KNOWN LIMIT: the crop is of the sheet, so whatever the design drew BEHIND or across the node —
  // a page backdrop, an overlapping neighbour — is in the reference while our render carries only
  // the component. On a definition sheet (a grid of component sets on a flat ground) that is a
  // near-uniform background against the scorer's own white, which is small; on a composed screen it
  // would not be. Isolating the node would mean a clone per node, which is the cost this function
  // exists to avoid.
  function cropFor(sheet, target) {
    var rootRect = svg.getBoundingClientRect();
    var rect = target.getBoundingClientRect();
    if (!(rootRect.width > 0 && rootRect.height > 0)) return null;
    if (!(rect.width > 0 && rect.height > 0)) return null;
    var perUnitX = (sheet.image.naturalWidth || sheet.image.width) / rootRect.width;
    var perUnitY = (sheet.image.naturalHeight || sheet.image.height) / rootRect.height;
    return {
      x: (rect.left - rootRect.left) * perUnitX,
      y: (rect.top - rootRect.top) * perUnitY,
      width: Math.max(1, rect.width * perUnitX),
      height: Math.max(1, rect.height * perUnitY),
    };
  }

  function sheetImage(target) {
    return rasteriseSheet().then(function (sheet) {
      if (!sheet) return null;
      var crop = cropFor(sheet, target);
      if (!crop) return null;
      var canvas = document.createElement("canvas");
      canvas.width = Math.max(1, Math.round(crop.width));
      canvas.height = Math.max(1, Math.round(crop.height));
      var context = canvas.getContext("2d");
      // White, to match what the scorer composites OUR render onto. Without it a transparent design
      // node would be compared as black and every score would be wrong in the same direction.
      context.fillStyle = "#fff";
      context.fillRect(0, 0, canvas.width, canvas.height);
      context.drawImage(
        sheet.image,
        crop.x, crop.y, crop.width, crop.height,
        0, 0, canvas.width, canvas.height,
      );
      return canvas;
    });
  }

  // A render is `loading="lazy"`, so on a tall sheet most of them have not been fetched — let alone
  // decoded — when the lane is entered. Scoring an undecoded image measures a blank, so each
  // comparison waits for its own image and a failure is left RETRYABLE rather than burned into a
  // permanent dash.
  function decoded(image) {
    if (image.complete && image.naturalWidth > 0) return Promise.resolve(image);
    return new Promise(function (resolve, reject) {
      image.addEventListener("load", function () {
        resolve(image);
      });
      image.addEventListener("error", function () {
        reject(new Error("render unavailable"));
      });
      // `loading="lazy"` only fetches on approach, and a node far below the fold may never be
      // approached. Asking for it explicitly is what makes the lane answer for the whole sheet
      // rather than only the part that has been scrolled past.
      if (image.loading === "lazy") image.loading = "eager";
    });
  }

  function badgeFor(overlay) {
    var badge = overlay.querySelector(".cp-page-score");
    if (!badge) {
      badge = document.createElement("span");
      badge.className = "cp-page-score";
      overlay.appendChild(badge);
    }
    return badge;
  }

  // Scored once per page. The numbers cannot move without the renders moving, and re-scoring on
  // every flip back would redo dozens of rasterise-and-count passes for an answer already on
  // screen. A node that FAILED is not counted as scored, so re-entering the lane retries it.
  var scoredNodes = Object.create(null);

  function score() {
    if (!compare) return;
    for (var s = 0; s < nodes.length; s++) {
      (function (entry) {
        var id = entry.overlay.getAttribute("data-cp-node") || "";
        if (scoredNodes[id]) return;
        var render = entry.overlay.querySelector(".cp-page-render");
        // No render, or one the server could not produce: there is nothing to compare the design
        // against, and saying so beats printing a number that means "absent" rather than "apart".
        if (!render || render.hidden) return;
        scoredNodes[id] = true;
        var badge = badgeFor(entry.overlay);
        badge.textContent = "…";
        Promise.all([sheetImage(entry.target), decoded(render)])
          .then(function (pair) {
            // The badge is the whole readout, deliberately. A per-node diff MAP was the obvious
            // next thing and is the wrong thing here: thirty-eight magenta thumbnails at slot size
            // is the annotated sheet this page was just rescued from. The number triages; the map
            // is one click away, at a size where it can be read.
            return pair[0] ? compare.scoreImages(pair[0], pair[1]) : null;
          })
          .then(function (result) {
            if (!result) throw new Error("not scoreable");
            // `scoreImages` answers with a MATCH percentage — identical images score 100. This lane
            // reports DRIFT, so it is inverted here. Getting that backwards prints "100.0%" in red
            // for a perfect match and green for a total mismatch, which is a readout that lies
            // rather than one that is merely wrong; `contract · the scorer answers match` pins the
            // direction so it cannot silently flip again.
            var drift = Math.max(0, 100 - result.percent);
            // Proportion difference is deliberately held OUT of the match number by the scorer,
            // which normalises both content boxes onto one size first. Ignoring it would report a
            // component rendered at the wrong aspect as a near-perfect match, so the larger of the
            // two is what the badge shows and the geometry is named in the tooltip.
            var geometry = Math.max(0, result.geometry || 0);
            // The NUMBER is the drift and only the drift, so it keeps meaning "how different does
            // this look". Taking `max(drift, geometry)` as the headline — the first attempt — made
            // every badge on this fixture read 52.4%, which was the aspect difference wearing the
            // label of a pixel difference: two measures conflated into one lie.
            //
            // Geometry still cannot hide. It marks the badge, it is spelled out in the tooltip, and
            // it counts for the BAND, so a component rendered at the wrong shape still triages red
            // however well its pixels line up once the scorer has normalised the two boxes.
            var stretched = geometry > 2;
            badge.textContent = drift.toFixed(1) + "%" + (stretched ? " ⇲" : "");
            badge.title =
              drift.toFixed(1) + "% different" +
              (geometry > 0.05 ? " · " + geometry.toFixed(1) + "% proportion difference" : "");
            var worst = Math.max(drift, geometry);
            // Three bands rather than a gradient: the reader is triaging, and "which of these needs
            // looking at" is a decision, not a measurement.
            badge.setAttribute(
              "data-cp-score",
              worst < 2 ? "close" : worst < 10 ? "drifting" : "far",
            );
            entry.overlay.setAttribute("data-cp-score-value", worst.toFixed(1));
          })
          .catch(function () {
            badge.textContent = "—";
            badge.setAttribute("data-cp-score", "none");
            badge.title = "not scoreable";
            // Retryable: a render that had not arrived yet is the likeliest reason to be here.
            scoredNodes[id] = false;
          });
      })(nodes[s]);
    }
  }

  // The way out. In the diff lane a node's click leaves for the component's full Figma comparison
  // rather than selecting in place — the number on the sheet is the invitation, and the diff map,
  // triptych and wipe are what it opens onto. Clicking a server-built anchor, never assigning a URL.
  var diffLinkSource = stage.querySelector("[data-cp-page-diff-links]");

  function armDiffLinks() {
    if (!diffLinkSource) return;
    var links = diffLinkSource.content.querySelectorAll(".cp-page-diff-link");
    for (var d = 0; d < links.length; d++) {
      var link = links[d];
      var entry = byId[link.getAttribute("data-cp-node") || ""];
      if (!entry) continue;
      entry.overlay.appendChild(link);
    }
    diffLinkSource.remove();
    diffLinkSource = null;
  }
  armDiffLinks();

  // Describing a node follows the POINTER rather than landing in a strip under the sheet.
  //
  // The strip was in the wrong place: a specimen sheet is taller than the fold, so on the shapes
  // two thirds down the page the answer appeared somewhere the reader could not see it while
  // pointing. A tooltip at the cursor is the same information where the eye already is.
  //
  // Its content is the audit list's own row, CLONED — one server-built description of a node
  // instead of two that can disagree, and the row's `href` never passes through JavaScript as a
  // string, so the tip cannot become the taint path `armRenders` avoids for the same reason. The
  // tip itself is inert (`pointer-events: none`), so it can never eat the click meant for the node
  // underneath it.
  var described = null;

  function rowFor(nodeId) {
    if (!list) return null;
    var rows = list.querySelectorAll("[data-cp-node]");
    for (var r = 0; r < rows.length; r++) {
      if (rows[r].getAttribute("data-cp-node") === nodeId) return rows[r];
    }
    return null;
  }

  function describe(nodeId) {
    described = nodeId;
    for (var s = 0; s < overlays.length; s++) {
      overlays[s].classList.toggle(
        "cp-page-selected",
        overlays[s].getAttribute("data-cp-node") === nodeId,
      );
    }
    if (!tip) return;
    var row = nodeId && rowFor(nodeId);
    if (!row) {
      tip.hidden = true;
      tip.textContent = "";
      return;
    }
    tip.textContent = "";
    var clone = row.cloneNode(true);
    clone.classList.add("cp-page-tip-card");
    // A second element carrying the same `data-cp-node` would answer `rowFor` on the next hover and
    // the tip would start cloning itself.
    clone.removeAttribute("data-cp-node");
    tip.appendChild(clone);
    tip.hidden = false;
  }

  // Positioned against the STAGE, and flipped rather than allowed to leave it: a tip that ran off
  // the right-hand shapes (or off the bottom row) would be describing something the reader cannot
  // read. Offset from the cursor so it never sits under the pointer itself.
  function moveTip(clientX, clientY) {
    if (!tip || tip.hidden) return;
    var box = stage.getBoundingClientRect();
    var size = tip.getBoundingClientRect();
    var pad = 14;
    var x = clientX - box.left + pad;
    var y = clientY - box.top + pad;
    if (x + size.width > box.width) x = clientX - box.left - size.width - pad;
    if (y + size.height > box.height) y = clientY - box.top - size.height - pad;
    tip.style.left = Math.max(0, x) + "px";
    tip.style.top = Math.max(0, y) + "px";
  }

  // A keyboard reader gets the same tip, parked at the node instead of at a pointer that isn't
  // there. Without this, tabbing the sheet would light the outline and say nothing.
  function parkTipAt(spot) {
    if (!tip || tip.hidden) return;
    var box = stage.getBoundingClientRect();
    var rect = spot.getBoundingClientRect();
    moveTip(rect.left + rect.width / 2, rect.bottom);
    if (rect.bottom - box.top + tip.getBoundingClientRect().height > box.height) {
      moveTip(rect.left + rect.width / 2, rect.top - tip.getBoundingClientRect().height);
    }
  }

  function hideTip() {
    if (!tip) return;
    tip.hidden = true;
    tip.textContent = "";
    described = null;
    for (var h = 0; h < overlays.length; h++) overlays[h].classList.remove("cp-page-selected");
  }

  for (var o = 0; o < overlays.length; o++) {
    (function (spot) {
      // Clicking GOES. The overlay is an anchor, so the browser already does the right thing for
      // the ordinary case, for the middle click and for a modifier click — this handler exists only
      // to redirect the diff lane, where the destination is the component's full comparison rather
      // than its preview. Redirected by clicking a second server-built anchor, never by assigning a
      // URL, and only for a plain left click so "open in a new tab" keeps working.
      spot.addEventListener("click", function (event) {
        if (lane() !== "diff") return;
        if (event.defaultPrevented || event.button !== 0) return;
        if (event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
        var out = spot.querySelector(".cp-page-diff-link");
        if (!out) return;
        event.preventDefault();
        out.click();
      });
    })(overlays[o]);
  }

  // Escape clears the selection from anywhere on the page — including from inside the disclosure,
  // where a reader who arrived by keyboard is most likely to be.
  root.addEventListener("keydown", function (event) {
    if (event.key === "Escape" && described) {
      var spot = null;
      for (var e = 0; e < overlays.length; e++) {
        if (overlays[e].getAttribute("data-cp-node") === described) spot = overlays[e];
      }
      hideTip();
      // Focus goes back to the node that was selected — but only while that node is still exposed.
      // The coverage filter takes the nodes it mutes out of the accessibility tree, and a selection
      // survives the filter being switched on, so the node Escape wants to hand focus back to may
      // by then be `aria-hidden` and 12% opaque. Focusing it would drop a keyboard or screen-reader
      // user onto an element the page has deliberately hidden; leaving focus where it is (on the
      // filter they just used) is the honest alternative.
      if (spot && !spot.hasAttribute("aria-hidden")) spot.focus();
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
      // Pointing DESCRIBES. Sweeping the sheet fills the strip below it, so several components can
      // be read in one pass without committing to any of them — the reading motion the page is for.
      // Keyboard focus does exactly the same thing, so tabbing the sheet reads like sweeping it.
      //
      // Leaving does NOT clear the description. The commonest next move after finding the one you
      // wanted is to go to its link, and a strip that emptied itself on the way to being read would
      // be unusable — so the last thing pointed at stays described until something else is.
      el.addEventListener("mouseenter", function (event) {
        pair(id, true);
        describe(id);
        moveTip(event.clientX, event.clientY);
      });
      el.addEventListener("mousemove", function (event) {
        moveTip(event.clientX, event.clientY);
      });
      el.addEventListener("mouseleave", function () {
        pair(id, false);
        // Unlike the strip it replaced, the tip DOES clear on the way out — it sits over the sheet,
        // so leaving it up would cover the very drawing the reader moved on to look at.
        if (described === id) hideTip();
      });
      el.addEventListener("focus", function () {
        pair(id, true);
        describe(id);
        parkTipAt(el);
      });
      el.addEventListener("blur", function () {
        pair(id, false);
        if (described === id) hideTip();
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
