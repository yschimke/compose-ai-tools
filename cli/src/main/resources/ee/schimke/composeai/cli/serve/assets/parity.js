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

  function esc(value) {
    var span = document.createElement("span");
    span.textContent = value;
    return span.innerHTML;
  }

  function finish() {
    findings.sort(function (a, b) { return a.score - b.score; });
    if (issueBody) {
      issueBody.innerHTML = findings.map(function (finding) {
        return "<tr><td>" + esc(finding.name) + "</td><td class=\"cp-parity-missing\">" +
          finding.score.toFixed(1) + "%</td><td><a href=\"" + esc(finding.review) +
          "\">Compare</a></td></tr>";
      }).join("");
    }
    if (results) results.hidden = findings.length === 0;
    if (status) status.textContent = findings.length
      ? findings.length + " mapped component(s) are below 90% structural match."
      : "All " + rows.length + " mapped components are at least 90% structural match.";
  }

  function worker() {
    var index = next++;
    if (index >= rows.length) return Promise.resolve();
    var row = rows[index];
    return compare.scoreImageUrls(row.dataset.reference, row.dataset.actual).then(function (measured) {
      var score = measured.percent;
      var cell = row.querySelector(".cp-parity-score");
      if (cell) {
        cell.textContent = score.toFixed(1) + "%";
        cell.className = "cp-parity-score " + (score < 90 ? "cp-parity-missing" : "cp-ok");
      }
      if (score < 90) findings.push({
        name: row.dataset.name,
        review: row.dataset.review,
        score: score,
      });
    }, function () {
      var cell = row.querySelector(".cp-parity-score");
      if (cell) cell.textContent = "Unavailable";
    }).then(function () {
      completed++;
      if (status) status.textContent = "Checked " + completed + " of " + rows.length + " comparisons…";
      return worker();
    });
  }

  Promise.all([worker(), worker(), worker(), worker()]).then(finish);
})();
