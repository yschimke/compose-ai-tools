// The Background/Transparent toggle, shared by the catalog grid and the single-preview viewer.
//
// The preview server shows components on a SOLID surface by default, so a transparent sticker
// reads like a real component instead of washing out against the page. This pair flips the whole
// page to a checkerboard (`cp-bg-transparent` on <html>) to inspect the raw alpha, and persists
// the choice per-visitor in `localStorage['cp-bg']`.
//
// Only the control lives here. The CSS backs both `.cp-imgwrap` (grid thumbnails) and `.cp-stage`
// (the viewer) off that one class, and the pre-paint script in the page <head> restores the choice
// before first paint — so the viewer already honoured the setting it had no way to change. This
// file is what gives it the buttons, and is why both pages toggle identically rather than through
// two implementations.
(function () {
  "use strict";

  var btns = document.querySelectorAll(".cp-bg-btn[data-bg-choice]");
  if (!btns.length) return;
  var root = document.documentElement;
  var urlState = window.cpUrlState || null;
  // What Back falls back to when a history entry carries no `bg`: what THIS load resolved to,
  // read off the class the pre-paint script set. Re-reading localStorage on pop would return the
  // value a later click wrote, so Back out of Transparent would stay transparent.
  var initial = root.classList.contains("cp-bg-transparent") ? "off" : "on";

  function reflect() {
    var choice = root.classList.contains("cp-bg-transparent") ? "off" : "on";
    btns.forEach(function (b) {
      b.setAttribute(
        "aria-pressed",
        b.getAttribute("data-bg-choice") === choice ? "true" : "false"
      );
    });
  }

  function paint(choice) {
    root.classList.toggle("cp-bg-transparent", choice === "off");
    reflect();
  }

  btns.forEach(function (b) {
    b.addEventListener("click", function () {
      var choice = b.getAttribute("data-bg-choice");
      paint(choice);
      try {
        localStorage.setItem("cp-bg", choice);
      } catch (e) {}
      // A discrete choice, so it earns its own history entry — the URL describes the page on
      // screen and the checkerboard view is shareable.
      if (urlState) urlState.push({ bg: choice });
    });
  });

  if (urlState) {
    urlState.onPop(function () {
      paint(urlState.get("bg") || initial);
    });
  }

  reflect();
  // Other scripts (the catalog filter, the viewer) re-run their own reflection after a pop; expose
  // ours so nothing has to reimplement the aria-pressed pass.
  window.cpBgToggle = { reflect: reflect };
})();
