(function () {
  var groups = document.querySelectorAll("details.cp-group[data-cp-group]");
  function key(g) { return "cp-grp." + g.getAttribute("data-cp-group"); }
  for (var i = 0; i < groups.length; i++) {
    (function (g) {
      try {
        var v = localStorage.getItem(key(g));
        if (v === "1") g.open = true;
        else if (v === "0") g.open = false;
      } catch (e) {}
      g.addEventListener("toggle", function () {
        try { localStorage.setItem(key(g), g.open ? "1" : "0"); } catch (e) {}
      });
    })(groups[i]);
  }
})();
