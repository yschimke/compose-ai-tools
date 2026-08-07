// The compare page's "Remote Compose players" view: every player's published render of the same
// `ir/*.rc` document side by side, and — once a column is picked as the reference — a pixel diff of
// every other column against it.
//
// The renders and the baked-PNG diffs were all computed offline by `scripts/design-artifacts/
// rc-compare.mjs` and published on the catalog's delivery branch, so this file never renders a
// document: it places `<img>`s and, for the one question the build cannot answer, diffs two of them
// on a canvas. Two diff paths, with very different costs:
//
//   * reference = the baked PNG → the offline run already diffed every player against it with
//     pixelmatch. Show those PNGs and those percentages: exact, and free.
//   * reference = a player → nothing precomputed compares two players, so diff in the browser.
//     The metric is pixelmatch's YIQ distance against `threshold² · 35215`, minus its
//     anti-aliasing pass, which reads a touch higher than the build-time numbers on text-heavy
//     previews. The status line says so rather than pretending they are interchangeable.
//
// Work is per row and lazy — an IntersectionObserver starts a row when it scrolls in — and every
// pass carries a token, so switching reference mid-scroll abandons the previous pass instead of
// racing it.
(function () {
  "use strict";

  var section = document.getElementById("cp-rc-lanes");
  var modelNode = document.getElementById("cp-rc-model");
  if (!section || !modelNode) return;

  var MODEL;
  try {
    MODEL = JSON.parse(modelNode.textContent);
  } catch (error) {
    return;
  }

  // pixelmatch's maximum YIQ difference — the scale its `threshold` option is expressed against.
  var MAX_DELTA = 35215;
  var THRESHOLD = typeof MODEL.threshold === "number" ? MODEL.threshold : 0.1;

  var rows = Array.prototype.slice.call(section.querySelectorAll(".cp-rc-row"));
  var buttons = section.querySelectorAll("[data-rc-ref]");
  var status = document.getElementById("cp-rc-status");
  var emptyNote = document.getElementById("cp-rc-empty");
  var count = document.getElementById("cp-compare-count");
  var laneIds = MODEL.lanes.map(function (lane) { return lane.id; });
  var token = 0;
  var reference = "none";

  var params = new URLSearchParams(location.search);
  if (params.get("ref") && laneIds.indexOf(params.get("ref")) >= 0) reference = params.get("ref");

  function shortLabel(id) {
    for (var i = 0; i < MODEL.lanes.length; i++) {
      if (MODEL.lanes[i].id === id) return MODEL.lanes[i].short;
    }
    return id;
  }

  // The same bands the published page uses, so a number means the same thing in both places.
  function band(pct) {
    if (pct === null || pct === undefined) return "na";
    if (pct < 2) return "good";
    if (pct < 10) return "ok";
    return "bad";
  }

  function chip(label, text, pct, px) {
    var line = document.createElement("div");
    line.className = "cp-rc-scoreline";
    var name = document.createElement("span");
    name.className = "cp-rc-scorelabel";
    name.textContent = label;
    var score = document.createElement("span");
    score.className = "cp-rc-score cp-rc-score--" + band(pct);
    score.textContent = text;
    line.appendChild(name);
    line.appendChild(score);
    if (px !== null && px !== undefined) {
      var pxEl = document.createElement("span");
      pxEl.className = "cp-rc-px";
      pxEl.textContent = px.toLocaleString("en-US") + " px";
      line.appendChild(pxEl);
    }
    return line;
  }

  function clearRow(row) {
    var scores = row.querySelector("[data-scores]");
    if (scores) scores.textContent = "";
    Array.prototype.forEach.call(row.querySelectorAll(".cp-rc-diffslot"), function (slot) {
      slot.textContent = "";
      slot.hidden = true;
    });
    Array.prototype.forEach.call(row.querySelectorAll(".cp-rc-cell"), function (cell) {
      cell.classList.remove("is-reference");
    });
  }

  function cellFor(row, laneId) {
    return row.querySelector('.cp-rc-cell[data-lane="' + laneId + '"]');
  }

  function showDiff(row, laneId, src) {
    var cell = cellFor(row, laneId);
    var slot = cell && cell.querySelector(".cp-rc-diffslot");
    if (!slot) return;
    var caption = document.createElement("div");
    caption.className = "cp-rc-difflabel";
    caption.textContent = "pixel diff vs " + shortLabel(reference);
    var img = document.createElement("img");
    img.loading = "lazy";
    img.src = src;
    img.alt = "pixel diff";
    slot.textContent = "";
    slot.appendChild(caption);
    slot.appendChild(img);
    slot.hidden = false;
  }

  var images = {};
  function load(src) {
    if (!images[src]) {
      images[src] = new Promise(function (resolve, reject) {
        var img = new Image();
        img.onload = function () { resolve(img); };
        img.onerror = function () { reject(new Error("could not load " + src)); };
        img.src = src;
      });
    }
    return images[src];
  }

  function pixels(img) {
    var canvas = document.createElement("canvas");
    canvas.width = img.naturalWidth;
    canvas.height = img.naturalHeight;
    var context = canvas.getContext("2d", { willReadFrequently: true });
    context.drawImage(img, 0, 0);
    return context.getImageData(0, 0, canvas.width, canvas.height);
  }

  /** pixelmatch's YIQ metric without its anti-aliasing pass. Both sides are already opaque. */
  function delta(a, b, i) {
    var ay = a[i] * 0.29889531 + a[i + 1] * 0.58662247 + a[i + 2] * 0.11448223;
    var by = b[i] * 0.29889531 + b[i + 1] * 0.58662247 + b[i + 2] * 0.11448223;
    var ai = a[i] * 0.59597799 - a[i + 1] * 0.2741761 - a[i + 2] * 0.32180189;
    var bi = b[i] * 0.59597799 - b[i + 1] * 0.2741761 - b[i + 2] * 0.32180189;
    var aq = a[i] * 0.21147017 - a[i + 1] * 0.52261711 + a[i + 2] * 0.31114694;
    var bq = b[i] * 0.21147017 - b[i + 1] * 0.52261711 + b[i + 2] * 0.31114694;
    var dy = ay - by;
    var di = ai - bi;
    var dq = aq - bq;
    return 0.5053 * dy * dy + 0.299 * di * di + 0.1957 * dq * dq;
  }

  function diffImages(refData, laneData) {
    var width = refData.width;
    var height = refData.height;
    var out = new ImageData(width, height);
    var limit = THRESHOLD * THRESHOLD * MAX_DELTA;
    var changed = 0;
    for (var i = 0; i < refData.data.length; i += 4) {
      if (delta(refData.data, laneData.data, i) > limit) {
        out.data[i] = 255;
        out.data[i + 1] = 60;
        out.data[i + 2] = 60;
        out.data[i + 3] = 255;
        changed++;
      } else {
        // pixelmatch's washed-out backdrop: the reference in grey at 10% so flagged pixels read.
        var grey = 255 + (refData.data[i] * 0.29889531 + refData.data[i + 1] * 0.58662247 +
          refData.data[i + 2] * 0.11448223 - 255) * 0.1;
        out.data[i] = grey;
        out.data[i + 1] = grey;
        out.data[i + 2] = grey;
        out.data[i + 3] = 255;
      }
    }
    var canvas = document.createElement("canvas");
    canvas.width = width;
    canvas.height = height;
    canvas.getContext("2d").putImageData(out, 0, 0);
    return { changed: changed, total: width * height, url: canvas.toDataURL("image/png") };
  }

  function scoreRow(row, pass) {
    var model = MODEL.rows[Number(row.getAttribute("data-row"))];
    if (!model) return Promise.resolve();
    var scores = row.querySelector("[data-scores]");
    var refCell = cellFor(row, reference);
    if (refCell) refCell.classList.add("is-reference");
    var refLane = model.lanes[reference];
    if (!refLane || !refLane.rendered) {
      scores.appendChild(chip(shortLabel(reference), "no reference", null, null));
      return Promise.resolve();
    }
    // A blank baked capture is no reference at all — but two *player* renders still compare, so the
    // short-circuit is scoped to the baked lane rather than to the whole row.
    if (model.referenceBlank && reference === "baked") {
      laneIds.forEach(function (id) {
        if (id !== reference) scores.appendChild(chip(shortLabel(id), "no reference", null, null));
      });
      return Promise.resolve();
    }

    var refData = null;
    var chain = Promise.resolve();
    laneIds.forEach(function (id) {
      if (id === reference) return;
      chain = chain.then(function () {
        if (pass !== token) return;
        var lane = model.lanes[id];
        if (!lane) return;
        if (!lane.rendered || !lane.render) {
          scores.appendChild(chip(shortLabel(id), lane.note || "no render", null, null));
          return;
        }
        // Build-time fast path: the offline run's own pixelmatch result against the baked PNG.
        if (reference === "baked" && lane.diff && lane.mismatchPct !== null &&
            lane.mismatchPct !== undefined) {
          scores.appendChild(
            chip(shortLabel(id), lane.mismatchPct.toFixed(2) + "%", lane.mismatchPct, lane.mismatchPx));
          showDiff(row, id, lane.diff);
          return;
        }
        return Promise.all([
          refData ? Promise.resolve(refData) : load(refLane.render).then(pixels),
          load(lane.render).then(pixels)
        ]).then(function (data) {
          if (pass !== token) return;
          refData = data[0];
          var laneData = data[1];
          if (laneData.width !== refData.width || laneData.height !== refData.height) {
            scores.appendChild(chip(shortLabel(id),
              laneData.width + "×" + laneData.height + " ≠ " + refData.width + "×" + refData.height,
              null, null));
            return;
          }
          var result = diffImages(refData, laneData);
          if (pass !== token) return;
          var pct = (100 * result.changed) / result.total;
          scores.appendChild(chip(shortLabel(id), pct.toFixed(2) + "%", pct, result.changed));
          showDiff(row, id, result.url);
        }, function () {
          if (pass !== token) return;
          scores.appendChild(chip(shortLabel(id), "diff failed", null, null));
        });
      });
    });
    return chain;
  }

  var scored = new WeakMap();
  var observer = new IntersectionObserver(function (entries) {
    entries.forEach(function (entry) {
      if (!entry.isIntersecting) return;
      var row = entry.target;
      if (reference === "none" || scored.get(row) === token) return;
      scored.set(row, token);
      scoreRow(row, token);
    });
  }, { rootMargin: "400px 0px" });

  function apply() {
    token++;
    Array.prototype.forEach.call(buttons, function (button) {
      button.setAttribute("aria-pressed",
        button.getAttribute("data-rc-ref") === reference ? "true" : "false");
    });
    section.setAttribute("data-reference", reference);
    // Unconditionally, and *before* re-observing: observe() on an already-observed target is a
    // no-op, so switching straight from one reference to another would leave every on-screen row
    // blank until it scrolled out and back. Disconnecting first queues a fresh initial callback.
    observer.disconnect();
    rows.forEach(function (row) {
      clearRow(row);
      scored.delete(row);
    });
    if (reference === "none") {
      if (status) status.textContent = "";
      return;
    }
    if (status) {
      status.textContent = reference === "baked"
        ? "showing the build-time pixel diffs against the baked PNG"
        : "diffing in your browser against " + shortLabel(reference) +
          " — no anti-aliasing pass, so text-heavy previews read slightly higher than the build-time numbers";
    }
    rows.forEach(function (row) {
      if (!row.hidden) observer.observe(row);
    });
  }

  // The shared search box filters this table too, so one filter covers both compare views.
  function filter(query) {
    var text = (query || "").trim().toLowerCase();
    var preview = (new URLSearchParams(location.search).get("preview") || "").toLowerCase();
    var visible = 0;
    rows.forEach(function (row) {
      var matchesQuery = !text || row.getAttribute("data-hay").indexOf(text) >= 0;
      var matchesPreview = !preview ||
        row.getAttribute("data-preview-ids").toLowerCase().indexOf(preview) >= 0;
      row.hidden = !(matchesQuery && matchesPreview);
      if (!row.hidden) visible++;
    });
    if (count) count.textContent = visible + (visible === 1 ? " comparison" : " comparisons");
    if (emptyNote) emptyNote.hidden = visible !== 0;
    if (reference !== "none") {
      rows.forEach(function (row) {
        if (row.hidden) observer.unobserve(row);
        else observer.observe(row);
      });
    }
  }

  Array.prototype.forEach.call(buttons, function (button) {
    button.addEventListener("click", function () {
      reference = button.getAttribute("data-rc-ref");
      if (window.cpUrlState) window.cpUrlState.push({ ref: reference === "none" ? "" : reference });
      apply();
    });
  });

  if (window.cpUrlState) {
    window.cpUrlState.onPop(function () {
      var popped = new URLSearchParams(location.search).get("ref");
      reference = popped && laneIds.indexOf(popped) >= 0 ? popped : "none";
      apply();
    });
  }

  // Exposed for format-compare.js, which owns the format switch and the search box.
  window.cpRcLanes = {
    filter: filter,
    refresh: apply
  };

  apply();
})();
