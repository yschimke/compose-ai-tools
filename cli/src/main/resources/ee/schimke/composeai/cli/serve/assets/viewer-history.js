// Render-history timeline for the viewer.
//
// The delivery branch carries `history.json` (see PreviewHistoryManifest): a precomputed per-preview
// list of versions, newest first. This turns that into a strip under the viewer bar, where each
// entry links to that version's render.
//
// Old renders are addressable because raw.githubusercontent.com serves any commit, not just branch
// names — so a version's `commit` + the preview's `path` is a direct URL.
//
// **Each entry is a link, not a stage swap.** An earlier revision replaced `#cp-img`'s src in place,
// which was wrong in four separate ways, all of which this removes by construction rather than by
// patching: the export panel, Copy PNG and issue-report kept pointing at the *current* render, so
// the viewer exported different pixels than it displayed; a snapshot re-render (an override edit)
// replaced the src and revoked the previous blob URL, leaving the strip's pressed state stale and a
// "current" click loading a revoked URL; building the strip needed the stage's src to already be
// resolved, so a fast manifest and a slow first render mislabelled the newest version; and reading
// a src out of the DOM and writing it back is a DOM-text-to-src flow CodeQL flags. Linking out owns
// none of that — the stage is never touched, so nothing can disagree with it.
(function () {
  "use strict";

  var root = document.querySelector(".cp-viewer");
  if (!root) return;
  var repo = root.getAttribute("data-history-repo");
  var previewId = root.getAttribute("data-preview-id");
  var bar = document.querySelector(".cp-viewer-bar");
  if (!repo || !previewId || !bar) return;
  // `repo` is DOM text and is interpolated into every link's href below. Linking out rather than
  // swapping the stage moved that sink from `img.src` to `a.href` — it did not remove it.
  //
  // Validating in place is not enough, and this is the part three earlier attempts got wrong: a
  // guard that inspects a string and hands the *same* string onward leaves the DOM value reaching
  // the href verbatim, which is the flow `js/xss-through-dom` reports (source
  // `getAttribute("data-history-repo")` at this line, sink `link.href` below). So match, then
  // rebuild from the captured segments and encode each one — the same treatment `path` already
  // gets in `renderUrlAt`, which is why `path` has never appeared in that flow. What reaches the
  // href is constructed here; nothing is passed through.
  //
  // The pattern admits only the shape a GitHub `owner/name` can take, and every character it
  // admits is URI-unreserved, so the encoding is a no-op on real values — identical bytes on the
  // wire, and a value that cannot be made safe by escaping simply doesn't draw the strip.
  var repoParts = /^([A-Za-z0-9][A-Za-z0-9._-]*)\/([A-Za-z0-9][A-Za-z0-9._-]*)$/.exec(repo);
  if (!repoParts) return;
  var repoPath = encodeURIComponent(repoParts[1]) + "/" + encodeURIComponent(repoParts[2]);

  // Mirrors ServeUrls.historicalRenderUrl, and rejects the same inputs for the same reason: the
  // manifest records shas, so accepting a ref would let a malformed manifest point the viewer at an
  // arbitrary branch.
  function renderUrlAt(commit, path) {
    if (!/^[0-9a-fA-F]{7,40}$/.test(commit || "")) return null;
    if (!path || path.indexOf("renders/") !== 0 || path.indexOf("..") !== -1) return null;
    return (
      "https://raw.githubusercontent.com/" +
      repoPath +
      "/" +
      commit +
      "/" +
      path.split("/").map(encodeURIComponent).join("/")
    );
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
    var made = 0;

    versions.forEach(function (v, i) {
      // Every entry, including the newest, is addressed from the manifest — never from the stage.
      // That is what keeps the strip independent of whether the render has loaded yet.
      var url = renderUrlAt(v.commit, entry.path);
      if (!url) return; // Skip an entry we cannot address rather than rendering a dead control.
      var link = document.createElement("a");
      link.className = "cp-history-item";
      link.href = url;
      link.target = "_blank";
      link.rel = "noopener noreferrer";
      if (i === 0) link.setAttribute("data-current", "1");

      var date = document.createElement("span");
      date.className = "cp-history-date";
      date.textContent = shortDate(v.date);
      link.appendChild(date);

      var meta = document.createElement("span");
      meta.className = "cp-history-meta";
      // sourceSha is the commit the render was produced from — far more useful to a human than the
      // delivery-branch commit, which is just a publish marker.
      meta.textContent = i === 0 ? "current" : v.sourceSha || (v.commit || "").slice(0, 8);
      link.appendChild(meta);

      if (v.commits > 1) {
        var span = document.createElement("span");
        span.className = "cp-history-span";
        span.textContent = "×" + v.commits;
        span.title = v.commits + " publishes carried these bytes";
        link.appendChild(span);
      }

      link.title =
        (i === 0 ? "Open the current render" : "Open the render as of " + shortDate(v.date)) +
        (v.sourceSha ? " (source " + v.sourceSha + ")" : "");
      made++;
      list.appendChild(link);
    });

    if (made < 2) return;
    wrap.appendChild(list);
    bar.parentNode.insertBefore(wrap, bar.nextSibling);
  }

  // An inline payload lets a fixture (and any offline viewer) render the strip without reaching
  // raw.githubusercontent.com. Without it the preview-harness capture is byte-identical whether the
  // timeline works or is deleted, i.e. no coverage at all.
  var inline = document.getElementById("cp-history-data");
  if (inline) {
    var parsed = null;
    try {
      parsed = JSON.parse(inline.textContent || "null");
    } catch (e) {
      parsed = null;
    }
    if (parsed && parsed.previews) {
      build(parsed.previews[previewId]);
      return;
    }
  }

  var manifestUrl = root.getAttribute("data-history-url");
  if (!manifestUrl) return;
  fetch(manifestUrl)
    .then(function (r) {
      if (!r.ok) throw new Error("history " + r.status);
      return r.json();
    })
    .then(function (m) {
      if (m && m.previews) build(m.previews[previewId]);
    })
    .catch(function () {
      // A missing or unreadable manifest is expected on a branch that has not published one yet.
      // The preview itself is unaffected, so fail silently rather than shouting about it.
    });
})();
