// Lane filter for the design-parity feed. The rows are already in the document (the page is
// server-rendered), so this only toggles visibility — no fetch, no re-render, and the page is
// fully readable with JavaScript off.
(function () {
  "use strict";

  var feed = document.getElementById("cp-parity-feed");
  if (!feed) return;
  var empty = document.getElementById("cp-parity-feed-empty");
  var buttons = Array.prototype.slice.call(
    document.querySelectorAll("[data-parity-lane]"),
  );
  if (!buttons.length) return;

  function apply(lane) {
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
})();
