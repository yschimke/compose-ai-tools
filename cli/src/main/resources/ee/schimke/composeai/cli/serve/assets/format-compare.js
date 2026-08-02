(function () {
  "use strict";

  var C1 = 6.5025;
  var C2 = 58.5225;
  var MAX_SIDE = 192;

  function loadImage(src) {
    return new Promise(function (resolve, reject) {
      var img = new Image();
      img.decoding = "async";
      img.onload = function () { resolve(img); };
      img.onerror = function () { reject(new Error("image load failed")); };
      img.src = src;
    });
  }

  function svgImage(text) {
    var url = URL.createObjectURL(new Blob([text], { type: "image/svg+xml" }));
    return loadImage(url).then(function (img) {
      setTimeout(function () { URL.revokeObjectURL(url); }, 0);
      return img;
    }, function (error) {
      URL.revokeObjectURL(url);
      throw error;
    });
  }

  function translateOf(svgText) {
    var match = /translate\(\s*(-?\d+)\s*,\s*(-?\d+)\s*\)/.exec(svgText);
    return match
      ? { x: parseInt(match[1], 10), y: parseInt(match[2], 10) }
      : { x: 0, y: 0 };
  }

  function grayFromDraw(draw, width, height) {
    var canvas = document.createElement("canvas");
    canvas.width = width;
    canvas.height = height;
    var context = canvas.getContext("2d", { willReadFrequently: true });
    context.imageSmoothingEnabled = true;
    context.imageSmoothingQuality = "high";
    // The comparison backdrop is deliberately fixed. Site light/dark appearance must not change
    // the score, and this matches the existing generated compare.html scorer.
    context.fillStyle = "#ffffff";
    context.fillRect(0, 0, width, height);
    draw(context);
    var rgba = context.getImageData(0, 0, width, height).data;
    var gray = new Float32Array(width * height);
    for (var i = 0; i < gray.length; i++) {
      gray[i] = 0.299 * rgba[i * 4] + 0.587 * rgba[i * 4 + 1] + 0.114 * rgba[i * 4 + 2];
    }
    return gray;
  }

  function blur(source, width, height) {
    var temp = new Float32Array(width * height);
    var output = new Float32Array(width * height);
    var x;
    var y;
    for (y = 0; y < height; y++) {
      for (x = 0; x < width; x++) {
        var left = source[y * width + Math.max(0, x - 1)];
        var center = source[y * width + x];
        var right = source[y * width + Math.min(width - 1, x + 1)];
        temp[y * width + x] = (left + 2 * center + right) / 4;
      }
    }
    for (y = 0; y < height; y++) {
      for (x = 0; x < width; x++) {
        var up = temp[Math.max(0, y - 1) * width + x];
        var middle = temp[y * width + x];
        var down = temp[Math.min(height - 1, y + 1) * width + x];
        output[y * width + x] = (up + 2 * middle + down) / 4;
      }
    }
    return output;
  }

  function globalSsim(a, b) {
    var n = a.length;
    var s1 = 0;
    var s2 = 0;
    var s11 = 0;
    var s22 = 0;
    var s12 = 0;
    for (var i = 0; i < n; i++) {
      s1 += a[i];
      s2 += b[i];
      s11 += a[i] * a[i];
      s22 += b[i] * b[i];
      s12 += a[i] * b[i];
    }
    var m1 = s1 / n;
    var m2 = s2 / n;
    var v1 = s11 / n - m1 * m1;
    var v2 = s22 / n - m2 * m2;
    var covariance = s12 / n - m1 * m2;
    return ((2 * m1 * m2 + C1) * (2 * covariance + C2)) /
      ((m1 * m1 + m2 * m2 + C1) * (v1 + v2 + C2));
  }

  function ssim(a, b, width, height) {
    var windowSize = 8;
    var stride = 4;
    if (width < windowSize || height < windowSize) return globalSsim(a, b);
    var total = 0;
    var count = 0;
    for (var y = 0; y + windowSize <= height; y += stride) {
      for (var x = 0; x + windowSize <= width; x += stride) {
        var s1 = 0;
        var s2 = 0;
        var s11 = 0;
        var s22 = 0;
        var s12 = 0;
        for (var j = 0; j < windowSize; j++) {
          for (var i = 0; i < windowSize; i++) {
            var index = (y + j) * width + x + i;
            var va = a[index];
            var vb = b[index];
            s1 += va;
            s2 += vb;
            s11 += va * va;
            s22 += vb * vb;
            s12 += va * vb;
          }
        }
        var n = windowSize * windowSize;
        var m1 = s1 / n;
        var m2 = s2 / n;
        var v1 = s11 / n - m1 * m1;
        var v2 = s22 / n - m2 * m2;
        var covariance = s12 / n - m1 * m2;
        total += ((2 * m1 * m2 + C1) * (2 * covariance + C2)) /
          ((m1 * m1 + m2 * m2 + C1) * (v1 + v2 + C2));
        count++;
      }
    }
    return count ? total / count : 1;
  }

  function scorePlanes(reference, candidate, width, height) {
    var value = ssim(blur(reference, width, height), blur(candidate, width, height), width, height);
    return Math.max(0, Math.min(100, value * 100));
  }

  function scoreSvgUrls(pngUrl, svgUrl) {
    return Promise.all([
      loadImage(pngUrl),
      fetch(svgUrl).then(function (response) {
        if (!response.ok) throw new Error("SVG " + response.status);
        return response.text();
      })
    ]).then(function (values) {
      var png = values[0];
      var text = values[1];
      return svgImage(text).then(function (svg) {
        var renderWidth = png.naturalWidth || png.width;
        var renderHeight = png.naturalHeight || png.height;
        var scale = Math.min(1, MAX_SIDE / Math.max(renderWidth, renderHeight));
        var width = Math.max(1, Math.round(renderWidth * scale));
        var height = Math.max(1, Math.round(renderHeight * scale));
        var reference = grayFromDraw(function (context) {
          context.drawImage(png, 0, 0, renderWidth * scale, renderHeight * scale);
        }, width, height);
        var translate = translateOf(text);
        var svgWidth = svg.naturalWidth || svg.width;
        var svgHeight = svg.naturalHeight || svg.height;
        var candidate = grayFromDraw(function (context) {
          context.drawImage(
            svg,
            -translate.x * scale,
            -translate.y * scale,
            svgWidth * scale,
            svgHeight * scale
          );
        }, width, height);
        return scorePlanes(reference, candidate, width, height);
      });
    });
  }

  function scoreCanvas(pngUrl, sourceCanvas) {
    return loadImage(pngUrl).then(function (png) {
      var renderWidth = png.naturalWidth || png.width;
      var renderHeight = png.naturalHeight || png.height;
      var scale = Math.min(1, MAX_SIDE / Math.max(renderWidth, renderHeight));
      var width = Math.max(1, Math.round(renderWidth * scale));
      var height = Math.max(1, Math.round(renderHeight * scale));
      var reference = grayFromDraw(function (context) {
        context.drawImage(png, 0, 0, renderWidth * scale, renderHeight * scale);
      }, width, height);
      var candidate = grayFromDraw(function (context) {
        context.drawImage(sourceCanvas, 0, 0, renderWidth * scale, renderHeight * scale);
      }, width, height);
      return scorePlanes(reference, candidate, width, height);
    });
  }

  window.ComposePreviewCompare = {
    loadImage: loadImage,
    scoreSvgUrls: scoreSvgUrls,
    scoreCanvas: scoreCanvas
  };

  var root = document.getElementById("cp-compare");
  if (!root) return;

  var formatButtons = root.querySelectorAll("[data-compare-format]");
  var themeButtons = root.querySelectorAll("[data-compare-theme]");
  var rows = Array.prototype.slice.call(root.querySelectorAll(".cp-compare-row"));
  var body = root.querySelector("tbody");
  var count = document.getElementById("cp-compare-count");
  var search = document.getElementById("cp-compare-search");
  var empty = document.getElementById("cp-compare-empty");
  var sequence = 0;
  var format = root.getAttribute("data-default-format") || "svg";
  var theme = root.getAttribute("data-default-theme") || "light";
  var params = new URLSearchParams(location.search);
  if (params.get("format") === "rc" && root.getAttribute("data-has-rc") === "1") format = "rc";
  if (params.get("format") === "svg" && root.getAttribute("data-has-svg") === "1") format = "svg";
  try {
    var remembered = localStorage.getItem(root.getAttribute("data-theme-key"));
    if (remembered === "light" || remembered === "dark") theme = remembered;
  } catch (ignore) {}

  function grade(percent) {
    if (percent >= 90) return "good";
    if (percent >= 75) return "warn";
    return "bad";
  }

  function variantFor(row) {
    // Never substitute the opposite baked theme: a dark PNG paired with a light vector can look
    // plausible while producing a meaningless score. Theme-neutral components remain visible.
    var choices = [theme, "neutral"];
    for (var i = 0; i < choices.length; i++) {
      var variant = choices[i];
      if (row.getAttribute("data-png-" + variant) && row.getAttribute("data-" + format + "-" + variant)) {
        return variant;
      }
    }
    return "";
  }

  function sourceFor(row, kind, variant) {
    return variant ? (row.getAttribute("data-" + kind + "-" + variant) || "") : "";
  }

  function setPressed(buttons, attribute, value) {
    Array.prototype.forEach.call(buttons, function (button) {
      button.setAttribute("aria-pressed", button.getAttribute(attribute) === value ? "true" : "false");
    });
  }

  function ensureRcPlayer() {
    if (window.RC) return Promise.resolve();
    return new Promise(function (resolve, reject) {
      var existing = document.querySelector("script[data-cp-rc-compare]");
      if (existing) {
        existing.addEventListener("load", resolve, { once: true });
        existing.addEventListener("error", reject, { once: true });
        return;
      }
      var script = document.createElement("script");
      script.src = "/rc-player/bundle.js";
      script.setAttribute("data-cp-rc-compare", "1");
      script.onload = resolve;
      script.onerror = reject;
      document.head.appendChild(script);
    });
  }

  function nextFrame() {
    return new Promise(function (resolve) { requestAnimationFrame(function () { resolve(); }); });
  }

  function renderRc(row, pngUrl, documentUrl) {
    var canvas = row.querySelector("canvas");
    return Promise.all([ensureRcPlayer(), loadImage(pngUrl), fetch(documentUrl)]).then(function (values) {
      var png = values[1];
      var response = values[2];
      if (!response.ok) throw new Error("RC " + response.status);
      canvas.width = png.naturalWidth || png.width;
      canvas.height = png.naturalHeight || png.height;
      return response.arrayBuffer();
    }).then(function (buffer) {
      var player = new window.RC.RcdPlayer(canvas);
      return player.loadFromArrayBuffer(buffer).then(function () {
        if (player.repaint) player.repaint();
        return nextFrame().then(nextFrame).then(function () { return scoreCanvas(pngUrl, canvas); });
      });
    });
  }

  function scoreRow(row, run) {
    var variant = variantFor(row);
    var pngUrl = sourceFor(row, "png", variant);
    var candidateUrl = sourceFor(row, format, variant);
    var score = row.querySelector(".cp-compare-score");
    var png = row.querySelector(".cp-compare-png");
    var vector = row.querySelector(".cp-compare-vector");
    var canvas = row.querySelector("canvas");
    if (!pngUrl || !candidateUrl) {
      row.hidden = true;
      return Promise.resolve(null);
    }
    row.hidden = false;
    row.setAttribute("data-bg-theme", variant === "dark" ? "dark" : "light");
    png.src = pngUrl;
    png.alt = row.getAttribute("data-label") + " rendered PNG";
    score.textContent = "comparing…";
    score.className = "cp-compare-score";
    if (format === "svg") {
      vector.hidden = false;
      canvas.hidden = true;
      vector.src = candidateUrl;
      vector.alt = row.getAttribute("data-label") + " SVG";
    } else {
      vector.hidden = true;
      canvas.hidden = false;
    }
    var result = format === "svg"
      ? scoreSvgUrls(pngUrl, candidateUrl)
      : renderRc(row, pngUrl, candidateUrl);
    return result.then(function (percent) {
      if (run !== sequence) return null;
      row.setAttribute("data-score", String(percent));
      score.textContent = percent.toFixed(1) + "%";
      score.className = "cp-compare-score cp-compare-score--" + grade(percent);
      return percent;
    }, function () {
      if (run !== sequence) return null;
      row.setAttribute("data-score", "-1");
      score.textContent = "unavailable";
      score.className = "cp-compare-score cp-compare-score--na";
      return null;
    });
  }

  function applySearch() {
    var query = (search.value || "").trim().toLowerCase();
    var preview = (params.get("preview") || "").toLowerCase();
    var visible = 0;
    rows.forEach(function (row) {
      var hasFormat = !!variantFor(row);
      var matchesQuery = !query || row.getAttribute("data-hay").indexOf(query) >= 0;
      var matchesPreview = !preview || row.getAttribute("data-preview-ids").toLowerCase().indexOf(preview) >= 0;
      row.hidden = !(hasFormat && matchesQuery && matchesPreview);
      if (!row.hidden) visible++;
    });
    count.textContent = visible + (visible === 1 ? " comparison" : " comparisons");
    empty.hidden = visible !== 0;
  }

  function run() {
    var runId = ++sequence;
    setPressed(formatButtons, "data-compare-format", format);
    setPressed(themeButtons, "data-compare-theme", theme);
    root.setAttribute("data-format", format);
    root.setAttribute("data-theme", theme);
    rows.forEach(function (row) { row.removeAttribute("data-score"); });
    applySearch();
    var visible = rows.filter(function (row) { return !row.hidden; });
    var chain = Promise.resolve();
    visible.forEach(function (row) {
      chain = chain.then(function () { return scoreRow(row, runId); });
    });
    chain.then(function () {
      if (runId !== sequence) return;
      visible.sort(function (a, b) {
        return parseFloat(a.getAttribute("data-score") || "-1") -
          parseFloat(b.getAttribute("data-score") || "-1");
      });
      visible.forEach(function (row) { body.appendChild(row); });
      applySearch();
    });
  }

  Array.prototype.forEach.call(formatButtons, function (button) {
    button.addEventListener("click", function () {
      format = button.getAttribute("data-compare-format");
      params.set("format", format);
      history.replaceState(null, "", location.pathname + "?" + params.toString());
      run();
    });
  });
  Array.prototype.forEach.call(themeButtons, function (button) {
    button.addEventListener("click", function () {
      theme = button.getAttribute("data-compare-theme");
      try { localStorage.setItem(root.getAttribute("data-theme-key"), theme); } catch (ignore) {}
      run();
    });
  });
  search.addEventListener("input", applySearch);
  run();
})();
