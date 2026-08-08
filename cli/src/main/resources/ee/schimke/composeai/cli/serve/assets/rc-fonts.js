// rc-fonts.js — make the page's registered faces paintable BEFORE a Remote Compose lane paints.
//
// `@font-face` is lazy and canvas does not drive it: `ctx.font` neither triggers a load nor waits for
// one, and a canvas asked for an unloaded face silently paints the fallback — no `font-display` swap,
// no repaint. So a page can carry exactly the right `@font-face` block (`/rc-fonts/fonts.css`, see
// `ServeRcFonts`) and still draw its first frames in the viewer's own generics, which is the whole
// bug this file exists to close (issue #3480). `document.fonts.ready` is not enough either — it
// resolves while a declared face is still `unloaded`.
//
// The faces are read out of `document.fonts` rather than restated here: the stylesheet is generated
// from `ServeRcFonts.FACES`, so iterating the registry keeps this file automatically in step with it
// (and with the parity harness's table) instead of being a third list to keep in sync. Named
// families the player fetches for itself are unaffected — those are added to the registry later and
// the player repaints through `onFontLoaded`.
//
// `window.cpRcFonts.ready()` resolves once (memoized) and NEVER rejects: a font that fails to load
// must degrade to the fallback face, not stop the lane from rendering at all.
(function () {
  var pending = null;

  function loadDeclaredFaces() {
    if (typeof document === "undefined" || !document.fonts) return Promise.resolve();
    var loads = [];
    try {
      document.fonts.forEach(function (face) {
        if (face.status === "loaded") return;
        try {
          loads.push(face.load().catch(function () {}));
        } catch (e) {
          /* a face the browser can't even start is a fallback, not a failure */
        }
      });
    } catch (e) {
      return Promise.resolve();
    }
    return Promise.all(loads).then(
      function () {
        return document.fonts.ready;
      },
      function () {}
    );
  }

  window.cpRcFonts = {
    ready: function () {
      if (!pending) pending = loadDeclaredFaces();
      return pending;
    }
  };
})();
