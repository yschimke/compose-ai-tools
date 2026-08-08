(function () {
  var root = document.querySelector(".cp-viewer");
  var badge = document.getElementById("cp-backend");
  if (!root || !badge) return;
  // The badge carries an icon that flips with the lane: ▶ for an interactive live lane (the
  // daemon stream or the in-browser Wasm app), ▪ for the static snapshot — the visible signal
  // that the Static⇄Live toggle changed state (paired with a green accent via data-live).
  function isLive(mode) { return mode === "wasm" || mode === "live"; }
  var specLane = document.getElementById("cp-spec-lane");
  function label(mode) {
    if (mode === "wasm") return "▶ CMP-WASM";
    if (mode === "live") return "▶ " + (root.getAttribute("data-live-backend") || "Live");
    if (mode === "svg") return "▪ SVG";
    // ◇ (an outline diamond) for the design spec: what is on the stage is the imported reference,
    // not something this server rendered, so it must not wear a renderer's ▪/▶ icon.
    if (mode === "spec") {
      return "◇ " + ((specLane && specLane.getAttribute("data-spec-label")) || "Spec");
    }
    return "▪ " + (root.getAttribute("data-snapshot-backend") || "Snapshot");
  }
  function refresh() {
    var mode = root.getAttribute("data-mode");
    // ◌ (an open circle) reads as "not yet painting", distinct from the ▶/▪ lane icons.
    var pending = root.getAttribute("data-pending");
    badge.textContent = pending ? "◌ " + pending : label(mode);
    badge.setAttribute("data-live", isLive(mode) ? "true" : "false");
    if (pending) badge.setAttribute("data-pending", "true");
    else badge.removeAttribute("data-pending");
  }
  new MutationObserver(refresh)
    .observe(root, { attributes: true, attributeFilter: ["data-mode", "data-pending"] });
  refresh();
})();
