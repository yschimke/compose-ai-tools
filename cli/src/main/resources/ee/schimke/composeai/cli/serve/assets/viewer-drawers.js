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
  // Every fold on this page remembers itself per-visitor, the same way the override groups do
  // (`cp-grp.<id>` in `<cp-group-memory>`) — a reader who puts the component list or a wide state axis
  // away has said so about the *catalog*, not about one preview, and re-opening it on every
  // navigation would make the toggle worthless on exactly the catalogs that need it.
  // Scoped to the CATALOG, the way `cp-theme:<catalog>` and `cp-tab:<catalog>` already are:
  // `localStorage` is per-origin and one host serves many catalogs under different base paths, so
  // an unscoped key would let folding this catalog's thirty-state axis fold a normally-inline axis
  // on every unrelated catalog beside it.
  var foldScope = viewer.getAttribute("data-fold-scope") || "default";
  function foldKey(id) { return "cp-fold:" + foldScope + "." + id; }
  function readFold(id) {
    try { return localStorage.getItem(foldKey(id)); } catch (e) { return null; }
  }
  function writeFold(id, open) {
    try { localStorage.setItem(foldKey(id), open ? "1" : "0"); } catch (e) {}
  }
  // Desktop shows the component list by default (the CSS rule at min-width:1100px); narrower
  // viewports don't. That default is what a null preference means.
  function isWide() {
    return !!(window.matchMedia && window.matchMedia("(min-width: 1100px)").matches);
  }
  function setOpen(cls, open) {
    // On mobile, opening one sheet closes the other so they never stack.
    if (open && isMobile()) {
      var other = cls === "cp-controls-open" ? "cp-nav-open" : "cp-controls-open";
      if (viewer.classList.contains(other)) {
        viewer.classList.remove(other);
        if (other === "cp-nav-open") viewer.classList.add("cp-nav-closed");
        var ob = document.getElementById(toggleId(other));
        if (ob) ob.setAttribute("aria-expanded", "false");
      }
    }
    viewer.classList.toggle(cls, open);
    // The nav's closed state has to be said out loud, not merely implied by the absence of
    // `cp-nav-open`: on a desktop the absence means *open* (see the min-width:1100px rule), so
    // without this class the toggle would be inert at the width where the 240px column costs most.
    if (cls === "cp-nav-open") viewer.classList.toggle("cp-nav-closed", !open);
    var b = document.getElementById(toggleId(cls));
    if (b) b.setAttribute("aria-expanded", open ? "true" : "false");
    syncScrim();
  }
  function bindToggle(btnId, cls) {
    var btn = document.getElementById(btnId);
    if (!btn) return;
    btn.addEventListener("click", function () {
      var open = !viewer.classList.contains(cls);
      setOpen(cls, open);
      // BOTH drawers are MODAL bottom sheets on a phone, not columns beside the preview — opened
      // for one thing and dismissed. Remembering either one open would restore the sheet on the
      // page you navigate to next, so every component you pick would arrive covered and need
      // dismissing (and the drawers close each other there, which would store a state the visitor
      // never chose). A sheet is transient by nature, so a phone stores nothing about them; the
      // in-page folds below are ordinary rows, not sheets, and keep their memory at every width.
      if (isMobile()) return;
      writeFold(btnId, open);
    });
  }
  // ── Bar, title, preview ──────────────────────────────────────────────────────────────────────
  // On a phone that is the whole page order, so the two control rows that sat between the title
  // and the stage — the disclosure pills, and the row naming the lane on the stage — move BELOW
  // it. Everything above the render is now the one line that says which component this is.
  //
  // Moved in the DOM rather than with `order`, and moved back above 640px, so reading order,
  // painting order and tab order stay the same order at every width — a CSS re-order would leave a
  // keyboard walking to controls that are a screenful further down the page than they look.
  // Re-ordered against each other too: the lane row describes what is ON the stage, so it stays
  // next to it, and the disclosures — the most-tapped controls, each opening a bottom sheet — go
  // last, nearest the thumb.
  var primaryRow = document.querySelector(".cp-preview-primary");
  var headToggles = document.querySelector(".cp-head-toggles");
  // Where each one came from, captured before anything moves. `nextSibling` rather than an index:
  // the anchors are stable nodes that never move themselves, so restoring is exact even though the
  // two rows leave from different parents (the primary row from <main>, the pills from the sticky
  // title row).
  var rowHomes = [primaryRow, headToggles].filter(Boolean).map(function (el) {
    return { el: el, parent: el.parentNode, next: el.nextSibling };
  });
  function reflowRows() {
    if (!viewer.parentNode) return;
    if (isMobile()) {
      var after = viewer;
      [primaryRow, headToggles].forEach(function (el) {
        if (!el) return;
        viewer.parentNode.insertBefore(el, after.nextSibling);
        after = el;
      });
    } else {
      rowHomes.forEach(function (home) {
        home.parent.insertBefore(home.el, home.next);
      });
    }
  }
  reflowRows();
  var phoneQuery = window.matchMedia && window.matchMedia("(max-width: 640px)");
  if (phoneQuery && phoneQuery.addEventListener)
    phoneQuery.addEventListener("change", reflowRows);
  // Start with the overrides drawer collapsed on a phone so the preview leads; the toggle row
  // keeps it (and the component list) one tap away as a bottom sheet. A stored preference is
  // honoured only OFF the phone — restoring it there would put a sheet back over the preview, and
  // "the preview leads" is the rule this breakpoint exists to state.
  if (isMobile()) setOpen("cp-controls-open", false);
  else {
    var controlsPref = readFold("cp-controls-toggle");
    if (controlsPref !== null) setOpen("cp-controls-open", controlsPref === "1");
  }
  // The nav's server markup carries NEITHER class, so its resting state is the CSS default — shown
  // on a desktop, hidden below — and `classList.contains("cp-nav-open")` would read that default as
  // "closed" on the very width where it is open. Resolve it into an explicit class so everything
  // downstream (the toggle, the ×, the scrim) can trust it and `aria-expanded` starts out honest.
  // On a phone the answer is always CLOSED, whatever a desktop visit stored: an open bottom sheet
  // is a modal over the preview, never a resting state to restore.
  function resolvedNavOpen() {
    if (isMobile()) return false;
    var pref = readFold("cp-nav-toggle");
    return pref !== null ? pref === "1" : isWide();
  }
  var navToggleBtn = document.getElementById("cp-nav-toggle");
  if (navToggleBtn) {
    setOpen("cp-nav-open", resolvedNavOpen());
    // …and re-resolve it when the viewport crosses a breakpoint. Making the state explicit is what
    // lost the CSS default's own responsiveness: a page opened wide and then narrowed to a phone
    // would otherwise keep `cp-nav-open`, which below 640px is a fixed bottom sheet and a scrim
    // dropped over a viewer nobody asked to cover.
    [
      window.matchMedia && window.matchMedia("(min-width: 1100px)"),
      window.matchMedia && window.matchMedia("(max-width: 640px)"),
    ].forEach(function (query) {
      if (!query || !query.addEventListener) return;
      query.addEventListener("change", function () {
        setOpen("cp-nav-open", resolvedNavOpen());
      });
    });
  }
  bindToggle("cp-controls-toggle", "cp-controls-open");
  bindToggle("cp-nav-toggle", "cp-nav-open");
  var close = document.getElementById("cp-nav-close");
  if (close)
    close.addEventListener("click", function () {
      setOpen("cp-nav-open", false);
      // Same rule as the toggle: dismissing the phone's bottom sheet is not a statement about the
      // desktop column, so it stores nothing.
      if (!isMobile()) writeFold("cp-nav-toggle", false);
    });
  // Tap the scrim to dismiss whichever bottom sheet is open.
  if (scrim)
    scrim.addEventListener("click", function () {
      setOpen("cp-controls-open", false);
      setOpen("cp-nav-open", false);
    });
  syncScrim();
  // The in-page state/variant fold. Unlike the drawers
  // these hide with the `hidden` attribute rather than a class on .cp-viewer, because they are not
  // columns of the viewer layout: they are rows above it, and the server has already decided
  // whether each starts folded (it knows how many chips there are, so a busy catalog opens folded
  // with no layout jump on load). A stored preference overrides that decision in either direction.
  function bindFold(btnId, targetId) {
    var btn = document.getElementById(btnId);
    var target = document.getElementById(targetId);
    if (!btn || !target) return;
    function apply(open) {
      target.hidden = !open;
      btn.setAttribute("aria-expanded", open ? "true" : "false");
    }
    var pref = readFold(btnId);
    if (pref !== null) apply(pref === "1");
    btn.addEventListener("click", function () {
      var open = target.hidden;
      apply(open);
      writeFold(btnId, open);
    });
  }
  bindFold("cp-axes-toggle", "cp-axes");
  // The Theme menu must still say which theme is showing, and the theme changes without a page
  // load — so the toggle's value half mirrors whichever chip viewer.js has marked pressed rather
  // than the lane the server baked. Observing `aria-pressed` keeps this decoupled from viewer.js's
  // own sync (`syncThemeBar`), which has several callers and no hook of its own.
  var themeBar = document.getElementById("cp-theme-bar");
  var themeValue = document.getElementById("cp-theme-toggle-value");
  if (themeBar && themeValue) {
    var syncThemeValue = function () {
      var on = themeBar.querySelector('.cp-theme-btn[aria-pressed="true"]');
      if (!on) return;
      var name = (on.textContent || "").trim();
      if (name) themeValue.textContent = name;
    };
    syncThemeValue();
    if (window.MutationObserver)
      new MutationObserver(syncThemeValue).observe(themeBar, {
        subtree: true,
        attributes: true,
        attributeFilter: ["aria-pressed"],
      });
    themeBar.addEventListener("click", function (event) {
      if (!event.target.closest(".cp-theme-btn")) return;
      var menu = themeBar.closest(".cp-theme-menu");
      if (menu) menu.open = false;
    });
  }
  var search = document.getElementById("cp-nav-search");
  if (search)
    search.addEventListener("input", function () {
      var query = search.value.trim().toLowerCase();
      var items = document.querySelectorAll("#cp-nav-list .cp-nav-item");
      // The active component and its variants are pinned above the filtered sibling list.
      var shown = document.querySelector(".cp-nav-current") ? 1 : 0;
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
