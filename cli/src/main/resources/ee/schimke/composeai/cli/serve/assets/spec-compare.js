// Diff options for the viewer's design-spec lane.
//
// The spec lane already put the imported design reference on the same stage as the render, so the
// two could be flipped between. Flipping is a weak instrument: it answers "are these different?"
// by asking a visitor to hold one frame in their head while looking at the other, which finds a
// wholesale colour change and misses the 4dp of padding that is the actual bug. The focused
// `/compare/<id>` page has the real instruments, but reaching it means leaving the viewer — and
// with it every override, knob and theme that produced the render worth comparing.
//
// So the instruments come to the lane instead. While the spec lane is up, this module offers four
// ways to look at the same pair, one click apart:
//
//   Spec      the imported reference alone — the lane's original behaviour, still the default
//   Diff      the magenta delta map: where, exactly, the two disagree
//   Triptych  Spec | Diff | Render side by side — the shape the /compare page is built around
//   Slider    one frame, wiped between spec and render — the alignment instrument
//
// Every surface is drawn from ONE normalisation pass (ComposePreviewCompare.normaliseImageUrls), so
// the diff, the three panels and the wipe are all in the same pixel space: a reference exported at
// a different scale than the render lines up here instead of reading as a total mismatch.
//
// Inert unless the served catalog published a design reference for this preview — every other
// session finds no `#cp-spec-compare` and this file returns immediately.
(function () {
  "use strict";

  var VIEWS = ["spec", "diff", "triptych", "slider"];
  var DEFAULT_VIEW = "spec";
  // Below this the two content boxes are the same shape to within rasteriser noise, and reporting
  // a proportion difference would be reporting the rasteriser. Matches format-compare.js.
  var GEOMETRY_REPORT_THRESHOLD = 2;

  var root = document.querySelector(".cp-viewer");
  var compare = document.getElementById("cp-spec-compare");
  var views = document.getElementById("cp-spec-views");
  if (!root || !compare || !views) return;

  var score = document.getElementById("cp-spec-score");
  var referenceCanvas = document.getElementById("cp-spec-reference");
  var diffCanvas = document.getElementById("cp-spec-diff");
  var actualCanvas = document.getElementById("cp-spec-actual");
  var wipeCanvas = document.getElementById("cp-spec-wipe-canvas");
  var wipeRange = document.getElementById("cp-spec-wipe-range");
  var referenceUrl = compare.getAttribute("data-reference") || "";

  var view = DEFAULT_VIEW;
  var open = false;
  var actualUrl = "";
  // The normalised pair currently painted, plus the (reference, actual) it was computed from — a
  // view switch inside one lane visit must not re-run the comparison, but a re-entry after the
  // render changed underneath (a new theme, a new knob) must.
  var framesKey = "";
  var frames = null;
  var pending = 0;

  function api() {
    return window.ComposePreviewCompare;
  }

  /**
   * Resolve a server-set URL against our own origin and refuse it if it leaves — the same guard
   * viewer.js puts on the spec raster and the Wasm frame. A `javascript:`/`data:` URL can never
   * reach a canvas here even if the attribute were ever mis-set.
   */
  function sameOrigin(candidate) {
    if (!candidate) return "";
    // A blob: URL minted by this page from its own fetch is already ours; `new URL` reports its
    // origin as the page's, so it passes the check below on every browser that implements it, but
    // spelling it out keeps the intent legible.
    var url;
    try {
      url = new URL(candidate, location.origin);
    } catch (e) {
      return "";
    }
    if (url.protocol === "blob:") return url.href;
    return url.origin === location.origin ? url.href : "";
  }

  function setScore(text) {
    if (score) score.textContent = text;
  }

  function splitFraction() {
    if (!wipeRange) return 0.5;
    var value = parseInt(wipeRange.value, 10);
    return isNaN(value) ? 0.5 : Math.max(0, Math.min(100, value)) / 100;
  }

  /**
   * The wipe: spec on the left of the split, render on the right, in one frame at one size.
   *
   * Drawn rather than clip-path'd over two stacked elements because the two sources are already
   * canvases in a shared pixel space — compositing them here keeps the split exact at every
   * fraction and costs two drawImage calls per drag frame.
   */
  function drawWipe() {
    if (!wipeCanvas || !frames) return;
    var width = frames.width;
    var height = frames.height;
    wipeCanvas.width = width;
    wipeCanvas.height = height;
    var context = wipeCanvas.getContext("2d");
    var split = Math.round(width * splitFraction());
    context.clearRect(0, 0, width, height);
    context.drawImage(frames.reference, 0, 0);
    if (split < width) {
      context.save();
      context.beginPath();
      context.rect(split, 0, width - split, height);
      context.clip();
      context.drawImage(frames.candidate, 0, 0);
      context.restore();
    }
    // The seam, in the diff map's magenta so the two comparison surfaces read as one instrument.
    context.fillStyle = "#e52e73";
    context.fillRect(Math.max(0, Math.min(width - 2, split - 1)), 0, 2, height);
  }

  /** Paint every comparison surface from one normalisation of the current pair. */
  function compute() {
    var reference = sameOrigin(referenceUrl);
    var actual = sameOrigin(actualUrl);
    var key = reference + "\n" + actual;
    if (!reference || !actual || !api()) {
      frames = null;
      framesKey = "";
      setScore("Comparison unavailable");
      return;
    }
    if (key === framesKey && frames) {
      drawWipe();
      return;
    }
    var generation = ++pending;
    setScore("comparing…");
    api()
      .normaliseImageUrls(reference, actual)
      .then(function (next) {
        if (generation !== pending) return;
        frames = next;
        framesKey = key;
        copyInto(next.reference, referenceCanvas);
        copyInto(next.candidate, actualCanvas);
        var changed = diffCanvas ? api().diffCanvases(next.reference, next.candidate, diffCanvas) : 0;
        drawWipe();
        return api()
          .scoreImageUrls(reference, actual)
          .then(function (result) {
            if (generation !== pending) return;
            var pixels = next.width * next.height;
            var changedPercent = pixels ? (changed * 100) / pixels : 0;
            var geometry =
              result.geometry >= GEOMETRY_REPORT_THRESHOLD
                ? " · " + result.geometry.toFixed(1) + "% proportion difference"
                : "";
            setScore(
              result.percent.toFixed(1) +
                "% match · " +
                changedPercent.toFixed(2) +
                "% pixels differ" +
                geometry
            );
          });
      })
      .catch(function () {
        if (generation !== pending) return;
        frames = null;
        framesKey = "";
        setScore("Comparison unavailable");
      });
  }

  function copyInto(source, target) {
    if (!target) return;
    target.width = source.width;
    target.height = source.height;
    target.getContext("2d").drawImage(source, 0, 0);
  }

  function syncButtons() {
    Array.prototype.forEach.call(views.querySelectorAll("[data-cp-spec-view]"), function (button) {
      var value = button.getAttribute("data-cp-spec-view");
      button.setAttribute("aria-pressed", value === view ? "true" : "false");
    });
  }

  /**
   * Reconcile the stage with the chosen view.
   *
   * `spec` deliberately touches nothing but its own container: the raster `<img>` viewer.js put on
   * the stage stays the whole surface, so a session that never picks a comparison view behaves
   * exactly as it did before this file existed. The other three hide that `<img>` from CSS (see
   * `.cp-viewer[data-spec-view]` in serve.css) and take the stage themselves.
   */
  function apply() {
    root.setAttribute("data-spec-view", view);
    compare.setAttribute("data-view", view);
    views.hidden = !open;
    if (score) score.hidden = !open || view === DEFAULT_VIEW;
    compare.hidden = !open || view === DEFAULT_VIEW;
    syncButtons();
    if (open && view !== DEFAULT_VIEW) compute();
  }

  function setView(next, push) {
    var wanted = VIEWS.indexOf(next) >= 0 ? next : DEFAULT_VIEW;
    if (wanted === view) return;
    view = wanted;
    apply();
    // A discrete choice, so it PUSHES: Back returns to the view you were looking at, the same way
    // it returns to the previous lane or theme. viewer.js re-emits the param on every later sync
    // (see syncUrl), so a knob moved afterwards can't quietly drop it.
    if (push && window.cpUrlState) {
      window.cpUrlState.push({ specView: view === DEFAULT_VIEW ? "" : view });
    }
  }

  views.addEventListener("click", function (event) {
    var button = event.target.closest ? event.target.closest("[data-cp-spec-view]") : null;
    if (!button || !views.contains(button)) return;
    setView(button.getAttribute("data-cp-spec-view"), true);
  });

  if (wipeRange) {
    // A drag is continuous input: redraw every frame, but leave the URL alone (the chosen VIEW is
    // the shareable state; where the seam happened to sit when you stopped dragging is not).
    wipeRange.addEventListener("input", drawWipe);
  }

  // Dragging on the frame itself is what "slider" means to anyone who has used one; the range
  // input underneath remains the keyboard/assistive path and stays the single source of the split.
  var wipeFrame = compare.querySelector(".cp-spec-wipe");
  if (wipeFrame && wipeRange && wipeCanvas) {
    var dragging = false;
    function seekTo(event) {
      var box = wipeCanvas.getBoundingClientRect();
      if (!box.width) return;
      var fraction = (event.clientX - box.left) / box.width;
      wipeRange.value = String(Math.round(Math.max(0, Math.min(1, fraction)) * 100));
      drawWipe();
    }
    wipeCanvas.addEventListener("pointerdown", function (event) {
      dragging = true;
      if (wipeCanvas.setPointerCapture) wipeCanvas.setPointerCapture(event.pointerId);
      seekTo(event);
      event.preventDefault();
    });
    wipeCanvas.addEventListener("pointermove", function (event) {
      if (dragging) seekTo(event);
    });
    function endDrag() {
      dragging = false;
    }
    wipeCanvas.addEventListener("pointerup", endDrag);
    wipeCanvas.addEventListener("pointercancel", endDrag);
  }

  window.cpSpecCompare = {
    /** The chosen view, for viewer.js's URL sync. */
    view: function () {
      return view;
    },
    /** Restore from the address bar (initial load and Back/Forward), without writing it back. */
    hydrate: function (next) {
      view = VIEWS.indexOf(next) >= 0 ? next : DEFAULT_VIEW;
      apply();
    },
    /**
     * The spec lane has been entered, against this render. Called on every entry, so a lane
     * re-entered after the render changed re-compares rather than showing the previous pair.
     */
    open: function (url) {
      open = true;
      actualUrl = url || "";
      apply();
    },
    /** The spec lane has been left: put the stage back and stop offering the comparison. */
    close: function () {
      open = false;
      apply();
    }
  };

  apply();
})();
