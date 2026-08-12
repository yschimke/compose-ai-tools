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

  function applyHotspots() {
    if (!hotspotsToggle) return;
    stage.classList.toggle("cp-backdrop-no-hotspots", !hotspotsToggle.checked);
  }

  function applyUnlinked() {
    if (!unlinkedToggle) return;
    stage.classList.toggle("cp-backdrop-unlinked-only", unlinkedToggle.checked);
  }

  // The renders are `loading="lazy"` and only get their real `src` when the overlay is first turned
  // on. A screen can carry a couple of dozen placements, and a visitor who never opens the overlay
  // should not cost the server a render request per placement — on a live catalog each of those is
  // a daemon render, not a static file.
  function armRenders() {
    var pending = stage.querySelectorAll(".cp-backdrop-render[data-src]");
    for (var i = 0; i < pending.length; i++) {
      var img = pending[i];
      img.src = img.getAttribute("data-src");
      img.removeAttribute("data-src");
    }
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
  function pair(nodeId, on) {
    var selector = '[data-cp-placement="' + (window.CSS && CSS.escape ? CSS.escape(nodeId) : nodeId) + '"]';
    var matches = root.querySelectorAll(selector);
    for (var i = 0; i < matches.length; i++) {
      matches[i].classList.toggle("cp-backdrop-active", on);
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
