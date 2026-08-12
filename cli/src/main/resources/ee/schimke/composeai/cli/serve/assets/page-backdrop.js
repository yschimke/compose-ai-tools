// Page backdrop viewer: hotspots over a design screen, and the catalog's own renders laid on top.
//
// Everything positional is already expressed as a percentage of the frame by the server (the
// manifest's bounds are frame-local design units, so `x / frame.width` is resolution-independent).
// That is deliberate: this file therefore does no geometry at all — no scale factor, no image
// natural size, no resize listener. It only toggles classes and writes two custom properties, so
// the layout stays correct while the backdrop is responsive, zoomed, or printed.
(function () {
  "use strict";

  var root = document.getElementById("cp-page-backdrop");
  if (!root) return;

  var stage = root.querySelector(".cp-backdrop-stage");
  var hotspotsToggle = root.querySelector("[data-cp-backdrop-hotspots]");
  var rendersToggle = root.querySelector("[data-cp-backdrop-renders]");
  var opacity = root.querySelector("[data-cp-backdrop-opacity]");
  var opacityOut = root.querySelector("[data-cp-backdrop-opacity-value]");
  var blend = root.querySelector("[data-cp-backdrop-blend]");
  var unlinkedToggle = root.querySelector("[data-cp-backdrop-unlinked]");

  // A hotspot that is hidden or muted is also taken out of the tab order and the accessibility
  // tree. CSS alone can't do this: `opacity: 0` + `pointer-events: none` still leaves an anchor
  // focusable, so a keyboard user could tab onto an invisible rectangle — no focus ring, no
  // indication of where they are — and press Enter to navigate. Kept in one place because both
  // toggles produce non-interactive hotspots and they compose.
  function syncFocusability() {
    var hidden = hotspotsToggle && !hotspotsToggle.checked;
    var unlinkedOnly = unlinkedToggle && unlinkedToggle.checked;
    var spots = stage.querySelectorAll(".cp-backdrop-hotspot");
    for (var i = 0; i < spots.length; i++) {
      var spot = spots[i];
      var inert = hidden || (unlinkedOnly && spot.getAttribute("data-link") !== "unlinked");
      if (inert) {
        spot.setAttribute("tabindex", "-1");
        spot.setAttribute("aria-hidden", "true");
      } else {
        spot.removeAttribute("tabindex");
        spot.removeAttribute("aria-hidden");
      }
    }
  }

  function applyHotspots() {
    if (!hotspotsToggle) return;
    stage.classList.toggle("cp-backdrop-no-hotspots", !hotspotsToggle.checked);
    syncFocusability();
  }

  function applyUnlinked() {
    if (!unlinkedToggle) return;
    stage.classList.toggle("cp-backdrop-unlinked-only", unlinkedToggle.checked);
    syncFocusability();
  }

  // The overlay images are served inside an inert `<template>` and cloned into the stage the first
  // time the toggle is switched on. A screen can carry a couple of dozen placements, and on a live
  // catalog each one is a daemon render — a visitor who never opens the overlay should cost the
  // server nothing.
  //
  // A template rather than a `data-src` swap, deliberately. Template content is inert: the browser
  // parses it but loads none of its resources until it is adopted into the document, which gives
  // the same laziness with none of the client-side URL handling. That matters beyond tidiness — the
  // swap made this file read a URL out of the DOM and assign it to `img.src`, which is a taint
  // path (CodeQL flagged it), and no amount of client-side validation is as good as not having the
  // sink. The URLs now stay where every other URL on the page is built and escaped: server-side.
  var renderSource = stage.querySelector("[data-cp-backdrop-render-source]");

  function armRenders() {
    if (!renderSource) return;
    // Before the first hotspot, so the rectangles keep painting over the renders rather than under
    // them; the renders are `pointer-events: none`, so this is about paint order, not hit testing.
    stage.insertBefore(renderSource.content.cloneNode(true), stage.querySelector(".cp-backdrop-hotspot"));
    renderSource.remove();
    renderSource = null;
  }

  function applyRenders() {
    if (!rendersToggle) return;
    var on = rendersToggle.checked;
    if (on) armRenders();
    stage.classList.toggle("cp-backdrop-overlay-on", on);
  }

  function applyOpacity() {
    if (!opacity) return;
    var pct = Number(opacity.value);
    stage.style.setProperty("--cp-backdrop-render-opacity", String(pct / 100));
    if (opacityOut) opacityOut.textContent = pct + "%";
  }

  function applyBlend() {
    if (!blend) return;
    stage.style.setProperty("--cp-backdrop-render-blend", blend.value);
  }

  if (hotspotsToggle) hotspotsToggle.addEventListener("change", applyHotspots);
  if (unlinkedToggle) unlinkedToggle.addEventListener("change", applyUnlinked);
  if (rendersToggle) rendersToggle.addEventListener("change", applyRenders);
  if (opacity) opacity.addEventListener("input", applyOpacity);
  if (blend) blend.addEventListener("change", applyBlend);

  // Hovering a row in the placement list highlights its rectangle on the screen, and vice versa —
  // the cheapest way to answer "which one is that?" on a screen with five identical list items.
  //
  // Compares attribute values rather than building a selector string out of one: a node id is text
  // from the design file, and interpolating it into a selector would be an injection with the same
  // shape as an HTML one. `CSS.escape` is the usual patch, but not constructing the selector at all
  // is simpler and has no browser-support caveat.
  function pair(nodeId, on) {
    var all = root.querySelectorAll("[data-cp-placement]");
    for (var i = 0; i < all.length; i++) {
      if (all[i].getAttribute("data-cp-placement") === nodeId) {
        all[i].classList.toggle("cp-backdrop-active", on);
      }
    }
  }

  var linked = root.querySelectorAll("[data-cp-placement]");
  for (var i = 0; i < linked.length; i++) {
    (function (el) {
      var id = el.getAttribute("data-cp-placement");
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
    })(linked[i]);
  }

  applyHotspots();
  applyUnlinked();
  applyRenders();
  applyOpacity();
  applyBlend();
})();
