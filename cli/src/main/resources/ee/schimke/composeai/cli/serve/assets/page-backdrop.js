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

  // The path this render may be fetched from, or null.
  //
  // The value is written by the server from a URL-encoded preview id, so in practice it is always
  // a same-origin path — but it reaches this code as DOM text, and DOM text is not a trust
  // boundary. Parsed against the document rather than prefix-tested: `new URL` normalises first, so
  // this rejects the cases a `startsWith("/")` check waves through — `\\evil.com`, a tab or
  // newline inside the scheme, an encoded `javascript:` — and what gets assigned is the *parsed*
  // path rather than the original text.
  function sameOriginPath(value) {
    if (typeof value !== "string" || value === "") return null;
    try {
      var url = new URL(value, window.location.href);
      return url.origin === window.location.origin ? url.pathname + url.search : null;
    } catch (e) {
      return null;
    }
  }

  // The renders only get their real `src` when the overlay is first turned on. A screen can carry a
  // couple of dozen placements, and a visitor who never opens the overlay should not cost the
  // server a render request per placement — on a live catalog each of those is a daemon render,
  // not a static file.
  function armRenders() {
    var pending = stage.querySelectorAll(".cp-backdrop-render[data-src]");
    for (var i = 0; i < pending.length; i++) {
      var img = pending[i];
      var path = sameOriginPath(img.getAttribute("data-src"));
      img.removeAttribute("data-src");
      if (path) img.src = path;
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
