// Render-history timeline for the viewer.
//
// The delivery branch carries `history.json` (see PreviewHistoryManifest): a precomputed per-preview
// list of versions, newest first. This turns that into a strip under the preview, where each entry
// swaps the stage image for that version's render.
//
// Old renders are addressable because raw.githubusercontent.com serves any commit, not just branch
// names — so a version's `commit` + the preview's `path` is a direct URL. Nothing is unpacked and
// there is no server round-trip; if the manifest is missing or unreadable the strip simply never
// appears, which is why every failure path below is silent rather than an error banner.
(function () {
  "use strict";

  var root = document.querySelector(".cp-viewer");
  if (!root) return;
  var manifestUrl = root.getAttribute("data-history-url");
  var repo = root.getAttribute("data-history-repo");
  var previewId = root.getAttribute("data-preview-id");
  // Absent attributes mean "no delivery provenance" — an uploaded bundle or a local project. That
  // is a normal configuration, not a failure, so leave the DOM untouched.
  if (!manifestUrl || !repo || !previewId) return;

  var img = document.getElementById("cp-img");
  var bar = document.querySelector(".cp-viewer-bar");
  if (!img || !bar) return;

  // Mirrors ServeUrls.historicalRenderUrl, and rejects the same inputs for the same reason: the
  // manifest records shas, so accepting a ref would let a malformed manifest point the viewer at an
  // arbitrary branch.
  function renderUrlAt(commit, path) {
    if (!/^[0-9a-fA-F]{7,40}$/.test(commit || "")) return null;
    if (!path || path.indexOf("renders/") !== 0 || path.indexOf("..") !== -1) return null;
    var encoded = path.split("/").map(encodeURIComponent).join("/");
    return "https://raw.githubusercontent.com/" + repo + "/" + commit + "/" + encoded;
  }

  // The "current" chip restores whatever the stage was showing, which means reading a src out of
  // the DOM and writing it back — a flow CodeQL flags, and rightly: a `javascript:` or `data:` src
  // reaching an image sink is how that becomes script execution. The value here is server-set, but
  // "safe because of where it came from" is exactly the assumption that rots, so allow only a
  // same-origin path or an https URL and drop anything else.
  function safeSrc(value) {
    if (!value) return null;
    if (/^\/(?!\/)/.test(value)) return value; // same-origin absolute path, not "//host"
    if (/^https:\/\//i.test(value)) return value;
    return null;
  }

  function shortDate(iso) {
    var m = /^(\d{4})-(\d{2})-(\d{2})/.exec(iso || "");
    return m ? m[1] + "-" + m[2] + "-" + m[3] : "";
  }

  function build(entry) {
    var versions = (entry && entry.versions) || [];
    if (versions.length < 2) return; // A single version is not a timeline.

    var wrap = document.createElement("div");
    wrap.className = "cp-history";
    wrap.setAttribute("aria-label", "Render history");

    var head = document.createElement("div");
    head.className = "cp-history-head";
    var title = document.createElement("span");
    title.className = "cp-history-title";
    title.textContent = "History";
    head.appendChild(title);

    var count = document.createElement("span");
    count.className = "cp-history-count";
    count.textContent =
      versions.length + " versions over " + (entry.observations || versions.length) + " publishes";
    head.appendChild(count);

    // Surface instability rather than quietly showing a trimmed list: when a render keeps reverting
    // to bytes it already had, the entries below are a trimmed view and would otherwise not add up
    // to the publish count beside them.
    if (entry.unstable) {
      var warn = document.createElement("span");
      warn.className = "cp-history-unstable";
      warn.textContent = "unstable";
      warn.title =
        "This render keeps reverting to bytes it had already moved away from (" +
        (entry.flapCount || 0) +
        " returns). The list below is trimmed to the states it flips between.";
      head.appendChild(warn);
    }
    wrap.appendChild(head);

    var list = document.createElement("div");
    list.className = "cp-history-list";
    var current = safeSrc(img.getAttribute("src"));
    var buttons = [];

    function select(btn, url) {
      buttons.forEach(function (b) {
        b.setAttribute("aria-pressed", b === btn ? "true" : "false");
      });
      root.setAttribute("data-history-viewing", btn === buttons[0] ? "" : "1");
      if (btn === buttons[0]) root.removeAttribute("data-history-viewing");
      img.setAttribute("src", url);
    }

    versions.forEach(function (v, i) {
      var url = (i === 0 ? current : null) || renderUrlAt(v.commit, entry.path);
      if (!url) return; // Skip an entry we cannot address rather than rendering a dead control.
      var btn = document.createElement("button");
      btn.type = "button";
      btn.className = "cp-history-item";
      btn.setAttribute("aria-pressed", i === 0 ? "true" : "false");

      var date = document.createElement("span");
      date.className = "cp-history-date";
      date.textContent = shortDate(v.date);
      btn.appendChild(date);

      var meta = document.createElement("span");
      meta.className = "cp-history-meta";
      // sourceSha is the commit the render was produced from — far more useful to a human than the
      // delivery-branch commit, which is just a publish marker.
      meta.textContent = i === 0 ? "current" : (v.sourceSha || (v.commit || "").slice(0, 8));
      btn.appendChild(meta);

      if (v.commits > 1) {
        var span = document.createElement("span");
        span.className = "cp-history-span";
        span.textContent = "×" + v.commits;
        span.title = v.commits + " publishes carried these bytes";
        btn.appendChild(span);
      }

      btn.title =
        (i === 0 ? "Current render" : "Render as of " + shortDate(v.date)) +
        (v.sourceSha ? " (source " + v.sourceSha + ")" : "");
      btn.addEventListener("click", function () {
        select(btn, url);
      });
      buttons.push(btn);
      list.appendChild(btn);
    });

    if (buttons.length < 2) return;
    wrap.appendChild(list);
    bar.parentNode.insertBefore(wrap, bar.nextSibling);
  }

  fetch(manifestUrl)
    .then(function (r) {
      if (!r.ok) throw new Error("history " + r.status);
      return r.json();
    })
    .then(function (m) {
      if (!m || !m.previews) return;
      build(m.previews[previewId]);
    })
    .catch(function () {
      // A missing or unreadable manifest is expected on a branch that has not published one yet.
      // The preview itself is unaffected, so fail silently rather than shouting about it.
    });
})();
