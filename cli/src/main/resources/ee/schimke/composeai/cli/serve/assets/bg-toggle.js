// The Transparent toggle, shared by the catalog grid and the single-preview viewer.
//
// The preview server shows components on a SOLID surface by default, so a transparent sticker
// reads like a real component instead of washing out against the page. This button flips the whole
// page to a checkerboard (`cp-bg-transparent` on <html>) to inspect the raw alpha, and persists
// the choice per-visitor in `localStorage['cp-bg']`.
//
// ONE button, not a Background / Transparent pair. The axis has two states and a default, which is
// exactly what `aria-pressed` on a single toggle expresses: the label names the non-default state
// and pressed-ness says whether it is on. The pair spent twice the toolbar width to say the same
// thing, and half of it was always a button that did nothing when clicked.
//
// Only the control lives here. The CSS backs both `.cp-imgwrap` (grid thumbnails) and `.cp-stage`
// (the viewer) off that one class, and the pre-paint script in the page <head> restores the choice
// before first paint — so the viewer already honoured the setting it had no way to change. This
// file is what gives it the button, and is why both pages toggle identically rather than through
// two implementations.
(function () {
  "use strict";

  var btns = document.querySelectorAll(".cp-bg-toggle");
  if (!btns.length) return;
  var root = document.documentElement;
  var urlState = window.cpUrlState || null;
  // What Back falls back to when a history entry carries no `bg`: what THIS load resolved to,
  // read off the class the pre-paint script set. Re-reading localStorage on pop would return the
  // value a later click wrote, so Back out of Transparent would stay transparent.
  var initial = root.classList.contains("cp-bg-transparent") ? "off" : "on";

  function transparent() {
    return root.classList.contains("cp-bg-transparent");
  }

  function reflect() {
    btns.forEach(function (b) {
      b.setAttribute("aria-pressed", transparent() ? "true" : "false");
    });
  }

  function paint(choice) {
    root.classList.toggle("cp-bg-transparent", choice === "off");
    reflect();
  }

  btns.forEach(function (b) {
    b.addEventListener("click", function () {
      var choice = transparent() ? "on" : "off";
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
