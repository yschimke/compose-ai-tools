// Lane filter for the design-parity feed. The rows are already in the document (the page is
// server-rendered), so this only toggles visibility — no fetch, no re-render, and the page is
// fully readable with JavaScript off.
(function () {
  "use strict";

  var feed = document.getElementById("cp-parity-feed");
  var empty = document.getElementById("cp-parity-feed-empty");
  var buttons = Array.prototype.slice.call(document.querySelectorAll("[data-parity-lane]"));

  function apply(lane) {
    if (!feed) return;
    var shown = 0;
    var entries = feed.querySelectorAll(".cp-parity-entry");
    for (var i = 0; i < entries.length; i++) {
      var entry = entries[i];
      var match = lane === "all" || entry.dataset.lane === lane;
      entry.hidden = !match;
      if (match) shown++;
    }
    if (empty) empty.hidden = shown > 0;
    for (var j = 0; j < buttons.length; j++) {
      var button = buttons[j];
      if (button.dataset.parityLane === lane) button.setAttribute("aria-current", "page");
      else button.removeAttribute("aria-current");
    }
  }

  for (var k = 0; k < buttons.length; k++) {
    buttons[k].addEventListener("click", function (event) {
      apply(event.currentTarget.dataset.parityLane);
    });
  }

  // Score every published render/reference pair with the same SSIM implementation as the focused
  // comparison page. Four workers keep a large catalog responsive while still turning the parity
  // page into an issues view instead of treating "mapped" as "matching".
  var compare = window.ComposePreviewCompare;
  var rows = Array.prototype.slice.call(document.querySelectorAll("[data-parity-comparison]"));
  if (!compare || !rows.length) return;
  var status = document.getElementById("cp-parity-score-status");
  var results = document.getElementById("cp-parity-score-results");
  var issueBody = document.getElementById("cp-parity-score-issues");
  var next = 0;
  var completed = 0;
  var findings = [];
  var failures = 0;
  var GEOMETRY_REPORT_THRESHOLD = 2;

  function esc(value) {
    var span = document.createElement("span");
    span.textContent = value;
    return span.innerHTML;
  }

  function finish() {
    findings.sort(function (a, b) { return (a.score ?? -1) - (b.score ?? -1); });
    if (issueBody) {
      issueBody.innerHTML = findings.map(function (finding) {
        var result = finding.unavailable
          ? "Unavailable"
          : finding.score.toFixed(1) + "%" +
            (finding.geometry >= GEOMETRY_REPORT_THRESHOLD
              ? " · " + finding.geometry.toFixed(1) + "% proportion drift" : "");
        return "<tr><td>" + esc(finding.name) + "</td><td class=\"cp-parity-missing\">" +
          result + "</td><td><a href=\"" + esc(finding.review) +
          "\">Compare</a></td></tr>";
      }).join("");
    }
    if (results) results.hidden = findings.length === 0;
    if (status) status.textContent = failures
      ? failures + " of " + rows.length + " mapped comparison(s) are unavailable; " +
        findings.length + " require review."
      : findings.length
      ? findings.length + " mapped component(s) have a structural or proportion difference."
      : "All " + rows.length + " mapped components are at least 90% structural match.";
  }

  function worker() {
    var index = next++;
    if (index >= rows.length) return Promise.resolve();
    var row = rows[index];
    return compare.scoreImageUrls(row.dataset.reference, row.dataset.actual).then(function (measured) {
      var score = measured.percent;
      var geometry = typeof measured.geometry === "number" ? measured.geometry : 0;
      var hasFinding = score < 90 || geometry >= GEOMETRY_REPORT_THRESHOLD;
      var cell = row.querySelector(".cp-parity-score");
      if (cell) {
        cell.textContent = score.toFixed(1) + "%";
        cell.className = "cp-parity-score " + (hasFinding ? "cp-parity-missing" : "cp-ok");
        if (geometry >= GEOMETRY_REPORT_THRESHOLD) {
          cell.title = geometry.toFixed(1) + "% proportion difference";
        }
      }
      if (hasFinding) findings.push({
        name: row.dataset.name,
        review: row.dataset.review,
        score: score,
        geometry: geometry,
      });
    }, function () {
      var cell = row.querySelector(".cp-parity-score");
      if (cell) {
        cell.textContent = "Unavailable";
        cell.className = "cp-parity-score cp-parity-missing";
      }
      failures++;
      findings.push({
        name: row.dataset.name,
        review: row.dataset.review,
        score: null,
        geometry: 0,
        unavailable: true,
      });
    }).then(function () {
      completed++;
      if (status) status.textContent = "Checked " + completed + " of " + rows.length + " comparisons…";
      return worker();
    });
  }

  Promise.all([worker(), worker(), worker(), worker()]).then(finish);
})();
