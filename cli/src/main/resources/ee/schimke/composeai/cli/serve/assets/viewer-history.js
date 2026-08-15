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
  if (!previewId) return;
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
  var repoPath = null;
  if (repo) {
    var repoParts = /^([A-Za-z0-9][A-Za-z0-9._-]*)\/([A-Za-z0-9][A-Za-z0-9._-]*)$/.exec(repo);
    if (!repoParts) return;
    repoPath = encodeURIComponent(repoParts[1]) + "/" + encodeURIComponent(repoParts[2]);
  }
  // Whether the strip describes renders this page is *not* showing. In project mode the stage comes
  // from the working tree while the timeline comes from the published baselines, so the newest
  // entry is the last publish rather than "what you are looking at".
  var local = !repo && !!blobUrl;
  // The local template gets the identical treatment, because it is the identical flow: DOM text
  // reaching an href. Matched, then rebuilt from its captured parts — the `{blob}` placeholder is
  // never substituted into the passed-through string, it is dropped and the URL reassembled around
  // the version's own sha. The pattern's leading `\/(?!\/)` keeps it site-relative (the character
  // class must admit `/` as a separator, so the lookahead is what rejects a protocol-relative
  // `//host/…`), and no `:` is admitted anywhere, so no `javascript:` URL can match.
  var blobBase = null;
  var blobQuery = "";
  if (local) {
    var blobParts =
      /^(\/(?!\/)[A-Za-z0-9._~%/-]*)\{blob\}(\.png)(\?[A-Za-z0-9._~%&=-]*)?$/.exec(blobUrl);
    if (!blobParts) return;
    // Path segments and query words are re-encoded individually, leaving the `/`, `?`, `&` and `=`
    // structure intact. Decoded first so a segment the server already percent-encoded round-trips
    // to the same bytes instead of double-encoding (`%3A` → `:` → `%3A`, not `%253A`).
    blobBase = blobParts[1].split("/").map(reencode).join("/");
    blobQuery = (blobParts[3] || "").replace(/[^?&=]+/g, reencode);
  }

  /** Percent-encode one URL word, idempotent for one that already is. */
  function reencode(word) {
    try {
      return encodeURIComponent(decodeURIComponent(word));
    } catch (e) {
      // A stray `%` isn't a valid escape and decodeURIComponent throws on it; encode as literal.
      return encodeURIComponent(word);
    }
  }

  // Mirrors ServeUrls.historicalRenderUrl, and rejects the same inputs for the same reason: the
  // manifest records shas, so accepting a ref would let a malformed manifest point the viewer at an
  // arbitrary branch. In project mode the version's content sha addresses it directly instead —
  // same rule, one identifier shorter, and the server refuses any sha its own timeline doesn't name.
  function renderUrlAt(version, path) {
    // `local` rather than `blobUrl`, so a page that somehow carried both stays coherent: one flag
    // decides both how an entry is addressed and how it is labelled.
    if (local) {
      if (!/^[0-9a-f]{40}$/.test(version.blob || "")) return null;
      return blobBase + version.blob + ".png" + blobQuery;
    }
    var commit = version.commit;
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
    place(wrap);
  }

  // Anchored to the STAGE — not to whatever row happens to sit next to it. This used to insert
  // itself after `.cp-viewer-bar` and bail out when that element was missing, so when #3893 folded
  // that bar's controls into the title row and stopped emitting it, this script started returning
  // at its second line and the timeline silently stopped drawing on every viewer page. `.cp-viewer`
  // is the stage's own container: it is what the strip describes, and it is the one element here
  // that cannot be reorganised out from under this without the page having no viewer at all.
  //
  // WHICH SIDE of the stage depends on the width, because the two widths want opposite things. On a
  // laptop the strip reads as metadata about the render and sits above it, where it has always
  // been. On a phone the page is bar, title, preview — the rule the mobile layout was rebuilt
  // around — and a strip above the stage costs 113px of that: the render starts at 126px on every
  // other preview and at 239px on one with a history. So there it goes BELOW the stage, joining the
  // rows `viewer-drawers.js` moves down for the same reason, and immediately after it rather than
  // after those rows: this arrives asynchronously from a fetch, and anchoring to a sibling that
  // another script may or may not have moved yet would make the order depend on which won the race.
  function place(wrap) {
    var phone = window.matchMedia && window.matchMedia("(max-width: 640px)");
    if (phone && phone.matches) root.parentNode.insertBefore(wrap, root.nextSibling);
    else root.parentNode.insertBefore(wrap, root);
    if (phone && phone.addEventListener && !wrap.dataset.cpHistoryPlaced) {
      wrap.dataset.cpHistoryPlaced = "1";
      phone.addEventListener("change", function () {
        place(wrap);
      });
    }
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
