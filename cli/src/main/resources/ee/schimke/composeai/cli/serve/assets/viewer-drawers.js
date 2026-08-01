(function () {
  var viewer = document.querySelector(".cp-viewer");
  if (!viewer) return;
  var scrim = document.getElementById("cp-scrim");
  function isMobile() {
    return !!(window.matchMedia && window.matchMedia("(max-width: 640px)").matches);
  }
  // On a phone the drawers open as bottom sheets over the preview; show a scrim behind whichever
  // is open. On desktop they're inline columns and the scrim rule never applies.
  function syncScrim() {
    var anyOpen =
      viewer.classList.contains("cp-controls-open") ||
      viewer.classList.contains("cp-nav-open");
    if (scrim) scrim.classList.toggle("cp-scrim-on", anyOpen);
  }
  function toggleId(cls) {
    return cls === "cp-nav-open" ? "cp-nav-toggle" : "cp-controls-toggle";
  }
  function setOpen(cls, open) {
    // On mobile, opening one sheet closes the other so they never stack.
    if (open && isMobile()) {
      var other = cls === "cp-controls-open" ? "cp-nav-open" : "cp-controls-open";
      if (viewer.classList.contains(other)) {
        viewer.classList.remove(other);
        var ob = document.getElementById(toggleId(other));
        if (ob) ob.setAttribute("aria-expanded", "false");
      }
    }
    viewer.classList.toggle(cls, open);
    var b = document.getElementById(toggleId(cls));
    if (b) b.setAttribute("aria-expanded", open ? "true" : "false");
    syncScrim();
  }
  function bindToggle(btnId, cls) {
    var btn = document.getElementById(btnId);
    if (!btn) return;
    btn.addEventListener("click", function () {
      setOpen(cls, !viewer.classList.contains(cls));
    });
  }
  // Start with the overrides drawer collapsed on a phone so the preview leads; the sticky toggle
  // bar keeps it (and the component list) one tap away as a bottom sheet.
  if (isMobile()) setOpen("cp-controls-open", false);
  bindToggle("cp-controls-toggle", "cp-controls-open");
  bindToggle("cp-nav-toggle", "cp-nav-open");
  var close = document.getElementById("cp-nav-close");
  if (close) close.addEventListener("click", function () { setOpen("cp-nav-open", false); });
  // Tap the scrim to dismiss whichever bottom sheet is open.
  if (scrim)
    scrim.addEventListener("click", function () {
      setOpen("cp-controls-open", false);
      setOpen("cp-nav-open", false);
    });
  syncScrim();
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
