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
  // Project mode (`serve` against a local checkout): there is no delivery repo to address an old
  // render on, so the server offers its own content-addressed lane instead — see
  // ServeProjectHistory. Exactly one of the two is ever present.
  var blobUrl = root.getAttribute("data-history-blob-url");
  var previewId = root.getAttribute("data-preview-id");
  var bar = document.querySelector(".cp-viewer-bar");
  if (!previewId || !bar) return;
  // `repo` is DOM text and is interpolated into every link's href below. Linking out rather than
  // swapping the stage moved that sink from `img.src` to `a.href` — it did not remove it — so
  // constrain the value to the only shape a GitHub `owner/name` can take before it can reach a URL.
  // A value that does not match cannot be made safe by escaping, so the strip is simply not drawn.
  if (repo && !/^[A-Za-z0-9][A-Za-z0-9._-]*\/[A-Za-z0-9][A-Za-z0-9._-]*$/.test(repo)) return;
  // The same treatment for the local template, and for the same reason: it reaches an href. One
  // anchored pattern rather than a series of charAt/indexOf checks, because the whole shape is
  // known — this server emits it — and because a pattern that describes the *entire* accepted
  // string is what makes the guard legible to a reader and to CodeQL alike (piecemeal checks are
  // neither):
  //   `/` not followed by `/`   — site-relative only. The character class excludes `:`, so no
  //                                `javascript:` URL can match, and the lookahead is what stops a
  //                                protocol-relative `//host/…` — which the class alone would
  //                                happily accept, since it has to allow `/` as a separator;
  //   `{blob}.png`               — the placeholder is mandatory (a template without one would
  //                                point every version at the same render) and the extension is
  //                                fixed;
  //   an optional query          — the session token, whose base64url alphabet plus `&`/`=`/`%` is
  //                                all this allows.
  // Nothing matching this can carry an HTML meta-character into the href. A value that does not
  // match cannot be made safe by escaping, so the strip is not drawn at all.
  var BLOB_URL = /^\/(?!\/)[A-Za-z0-9._~%/-]*\{blob\}\.png(\?[A-Za-z0-9._~%&=-]*)?$/;
  if (blobUrl && !BLOB_URL.test(blobUrl)) return;
  if (!repo && !blobUrl) return;
  // Whether the strip describes renders this page is *not* showing. In project mode the stage comes
  // from the working tree while the timeline comes from the published baselines, so the newest
  // entry is the last publish rather than "what you are looking at".
  var local = !repo && !!blobUrl;

  // Mirrors ServeUrls.historicalRenderUrl, and rejects the same inputs for the same reason: the
  // manifest records shas, so accepting a ref would let a malformed manifest point the viewer at an
  // arbitrary branch. In project mode the version's content sha addresses it directly instead —
  // same rule, one identifier shorter, and the server refuses any sha its own timeline doesn't name.
  function renderUrlAt(version, path) {
    // `local` rather than `blobUrl`, so a page that somehow carried both stays coherent: one flag
    // decides both how an entry is addressed and how it is labelled.
    if (local) {
      if (!/^[0-9a-f]{40}$/.test(version.blob || "")) return null;
      return blobUrl.replace("{blob}", version.blob);
    }
    var commit = version.commit;
    if (!/^[0-9a-fA-F]{7,40}$/.test(commit || "")) return null;
    if (!path || path.indexOf("renders/") !== 0 || path.indexOf("..") !== -1) return null;
    return (
      "https://raw.githubusercontent.com/" +
      repo +
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

    // Say where the strip came from when it is not describing the stage. Without this the newest
    // chip reads as "the render above", which in project mode it is not: the stage is rendered from
    // the working tree and the timeline is the published baseline history.
    if (local) {
      var scope = document.createElement("span");
      scope.className = "cp-history-scope";
      scope.textContent = "published baselines";
      scope.title =
        "Read from the delivery branch in your local checkout. The preview above is rendered from " +
        "your working tree, so it may differ from the newest entry.";
      head.appendChild(scope);
    }

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
      var url = renderUrlAt(v, entry.path);
      if (!url) return; // Skip an entry we cannot address rather than rendering a dead control.
      var current = i === 0 && !local;
      var link = document.createElement("a");
      link.className = "cp-history-item";
      link.href = url;
      link.target = "_blank";
      link.rel = "noopener noreferrer";
      if (current) link.setAttribute("data-current", "1");

      var date = document.createElement("span");
      date.className = "cp-history-date";
      date.textContent = shortDate(v.date);
      link.appendChild(date);

      var meta = document.createElement("span");
      meta.className = "cp-history-meta";
      // sourceSha is the commit the render was produced from — far more useful to a human than the
      // delivery-branch commit, which is just a publish marker.
      meta.textContent = current ? "current" : v.sourceSha || (v.commit || "").slice(0, 8);
      link.appendChild(meta);

      if (v.commits > 1) {
        var span = document.createElement("span");
        span.className = "cp-history-span";
        span.textContent = "×" + v.commits;
        span.title = v.commits + " publishes carried these bytes";
        link.appendChild(span);
      }

      link.title =
        (current ? "Open the current render" : "Open the render as of " + shortDate(v.date)) +
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
