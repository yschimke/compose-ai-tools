(function () {
  "use strict";

  var C1 = 6.5025;
  var C2 = 58.5225;
  var MAX_SIDE = 192;
  // Longest side of the downscale that content-box detection samples, and how far a pixel may sit
  // from the backdrop colour before it counts as drawn.
  var BOX_SAMPLE_SIDE = 256;
  var BOX_COLOUR_TOLERANCE = 12;
  // Below this, a content-box proportion difference is rasteriser noise rather than a finding.
  var GEOMETRY_REPORT_THRESHOLD = 2;
  // Smallest share of its canvas a content box may cover before cropping to it stops being
  // trustworthy — see `normalisedBoxes`.
  var MIN_BOX_COVERAGE = 0.05;
  // The backing colours `@Preview(showBackground = true)` resolves to — white for a day uiMode and
  // Material 3's dark surface (#1C1B1F) for a night one, mirroring `PreviewBackground` on the
  // server. An opaque capture whose corner is one of these is sitting on a scaffold sheet; any
  // other corner colour is artwork reaching the edge. See `contentBox`.
  var SCAFFOLD_SHEETS = [[255, 255, 255], [28, 27, 31]];
  // Slack for PNG round-tripping and the detection downscale's resampling of an edge pixel.
  var SHEET_TOLERANCE = 6;

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

  function imageDimensions(image) {
    return { width: image.naturalWidth || image.width, height: image.naturalHeight || image.height };
  }

  /** Whether an opaque corner colour is one of the sheets `showBackground` paints. */
  function isScaffoldSheet(rgb) {
    for (var i = 0; i < SCAFFOLD_SHEETS.length; i++) {
      var sheet = SCAFFOLD_SHEETS[i];
      if (
        Math.abs(rgb[0] - sheet[0]) <= SHEET_TOLERANCE &&
        Math.abs(rgb[1] - sheet[1]) <= SHEET_TOLERANCE &&
        Math.abs(rgb[2] - sheet[2]) <= SHEET_TOLERANCE
      ) {
        return true;
      }
    }
    return false;
  }

  /**
   * The rectangle an image actually draws in, in source pixels.
   *
   * A design reference and a rendered preview are authored to different edges. The preview carries
   * whatever its `@Preview` scaffold added — `showBackground`'s opaque sheet, a `padding()` inset, a
   * fixed-height container the content does not fill — while the reference is usually cropped to the
   * artboard. Scoring those against each other measures the scaffold, not the component: the whole
   * image is translated and rescaled relative to its partner, which SSIM reads as total mismatch.
   *
   * Detection uses alpha where the image has any: a transparent pixel is unambiguously not artwork.
   *
   * An opaque image is the hard case, because "a uniform border around an interior region" is the
   * *same picture* whether the border is a scaffold sheet with a card inset on it or a card that
   * bleeds to the artboard edge with text inset on it. Guessing from the corner pixel gets the
   * second one exactly backwards — it strips the component's own surface and boxes only its text,
   * so a tightly-cropped card reference gets stretched against a whole-card render and the pair
   * reads as a total mismatch. The denser the card, the worse the score.
   *
   * So an opaque image's backdrop is not guessed. It is trusted only when the corner is a sheet the
   * preview renderer actually paints — `showBackground` resolves to exactly white or Material 3's
   * dark surface, per `PreviewBackground` — and any other corner colour means the pixels there
   * could be the artwork, so the whole image is the content box. That errs toward comparing too
   * much, which costs a little of the scaffold correction on a preview with a custom
   * `backgroundColor`, rather than toward silently comparing the wrong region.
   *
   * Sampling is done on a downscale: a crop rectangle needs to be roughly right, not exact, and a
   * full-resolution scan of a 1078x2399 device shot per row is real time on the client.
   */
  function contentBox(image) {
    var dimensions = imageDimensions(image);
    var scale = Math.min(1, BOX_SAMPLE_SIDE / Math.max(dimensions.width, dimensions.height));
    var width = Math.max(1, Math.round(dimensions.width * scale));
    var height = Math.max(1, Math.round(dimensions.height * scale));
    var canvas = document.createElement("canvas");
    canvas.width = width;
    canvas.height = height;
    var context = canvas.getContext("2d", { willReadFrequently: true });
    context.imageSmoothingEnabled = true;
    context.imageSmoothingQuality = "high";
    context.drawImage(image, 0, 0, width, height);
    var data;
    try {
      data = context.getImageData(0, 0, width, height).data;
    } catch (ignore) {
      // A tainted canvas (cross-origin artifact) cannot be sampled. Fall back to the whole image.
      return { x: 0, y: 0, width: dimensions.width, height: dimensions.height };
    }
    var transparent = false;
    for (var probe = 3; probe < data.length; probe += 4) {
      if (data[probe] < 250) {
        transparent = true;
        break;
      }
    }
    var backdrop = [data[0], data[1], data[2]];
    // An opaque corner is only a backdrop if it is a sheet the renderer paints; otherwise it is
    // artwork reaching the edge, and there is nothing here to strip.
    if (!transparent && !isScaffoldSheet(backdrop)) {
      return { x: 0, y: 0, width: dimensions.width, height: dimensions.height };
    }
    var minX = width;
    var minY = height;
    var maxX = -1;
    var maxY = -1;
    for (var y = 0; y < height; y++) {
      for (var x = 0; x < width; x++) {
        var i = (y * width + x) * 4;
        var drawn = transparent
          ? data[i + 3] > 8
          : Math.abs(data[i] - backdrop[0]) +
              Math.abs(data[i + 1] - backdrop[1]) +
              Math.abs(data[i + 2] - backdrop[2]) >
            BOX_COLOUR_TOLERANCE;
        if (drawn) {
          if (x < minX) minX = x;
          if (x > maxX) maxX = x;
          if (y < minY) minY = y;
          if (y > maxY) maxY = y;
        }
      }
    }
    // A blank capture has no content box; comparing whole-image is the only meaningful answer.
    if (maxX < 0) return { x: 0, y: 0, width: dimensions.width, height: dimensions.height };
    // Widen by one sample cell each way — the downscale can shave a partially-covered edge pixel.
    var inverse = 1 / scale;
    var x0 = Math.max(0, Math.floor((minX - 1) * inverse));
    var y0 = Math.max(0, Math.floor((minY - 1) * inverse));
    var x1 = Math.min(dimensions.width, Math.ceil((maxX + 2) * inverse));
    var y1 = Math.min(dimensions.height, Math.ceil((maxY + 2) * inverse));
    return { x: x0, y: y0, width: Math.max(1, x1 - x0), height: Math.max(1, y1 - y0) };
  }

  /**
   * How far apart the two content boxes are in shape, 0 (identical proportions) to 100.
   *
   * Reported beside the score rather than folded into it. Normalising both sides to a common box
   * before scoring is what makes the appearance comparison meaningful, but it also makes a genuine
   * proportion difference invisible — and on this page a reference stretched into the render's
   * canvas is a real finding, not noise to be smoothed away. Two honest numbers beat one blended
   * one.
   */
  function aspectDelta(a, b) {
    var ratioA = a.width / a.height;
    var ratioB = b.width / b.height;
    return (Math.abs(ratioA - ratioB) / Math.max(ratioA, ratioB)) * 100;
  }

  /**
   * The rectangles to actually compare over, plus the measured boxes for reporting.
   *
   * Cropping to content is the right move while both captures have enough content to locate. It
   * stops being right on a near-empty one: an empty-state preview whose only mark is a heading
   * yields a box of a few percent of the canvas, and stretching that sliver across its partner
   * turns one line of text into the entire comparison. An empty state that genuinely matches its
   * reference then scores like a total mismatch.
   *
   * So the crop is conditional. When either side's box is too small to be a reliable frame, both
   * sides fall back to the whole canvas — the behaviour that was always correct for this case. The
   * measured boxes are still reported either way; "these two match but are framed very differently"
   * is worth surfacing even when the score is computed whole-canvas.
   */
  function normalisedBoxes(referenceImage, candidateImage) {
    var referenceSize = imageDimensions(referenceImage);
    var candidateSize = imageDimensions(candidateImage);
    var referenceBox = contentBox(referenceImage);
    var candidateBox = contentBox(candidateImage);
    var coverage = Math.min(
      (referenceBox.width * referenceBox.height) / (referenceSize.width * referenceSize.height),
      (candidateBox.width * candidateBox.height) / (candidateSize.width * candidateSize.height)
    );
    var full = coverage < MIN_BOX_COVERAGE;
    return {
      reference: full ? { x: 0, y: 0, width: referenceSize.width, height: referenceSize.height } : referenceBox,
      candidate: full ? { x: 0, y: 0, width: candidateSize.width, height: candidateSize.height } : candidateBox,
      geometry: aspectDelta(referenceBox, candidateBox),
      cropped: !full
    };
  }

  /**
   * Score a design reference against a rendered preview.
   *
   * Both sides are cropped to their content box and drawn into one common target box, so the score
   * answers "does this component look like its design?" rather than "were these two files exported
   * at the same size?". Dimensions no longer have to agree — requiring that was what pushed
   * producers into resampling reference art to fit the render's canvas in the first place.
   */
  function scoreImageUrls(referenceUrl, candidateUrl) {
    return Promise.all([loadImage(referenceUrl), loadImage(candidateUrl)]).then(function (images) {
      var referenceImage = images[0];
      var candidateImage = images[1];
      var boxes = normalisedBoxes(referenceImage, candidateImage);
      var referenceBox = boxes.reference;
      var candidateBox = boxes.candidate;
      var scale = Math.min(1, MAX_SIDE / Math.max(candidateBox.width, candidateBox.height));
      var width = Math.max(1, Math.round(candidateBox.width * scale));
      var height = Math.max(1, Math.round(candidateBox.height * scale));
      var reference = grayFromDraw(function (context) {
        context.drawImage(
          referenceImage,
          referenceBox.x, referenceBox.y, referenceBox.width, referenceBox.height,
          0, 0, width, height
        );
      }, width, height);
      var candidate = grayFromDraw(function (context) {
        context.drawImage(
          candidateImage,
          candidateBox.x, candidateBox.y, candidateBox.width, candidateBox.height,
          0, 0, width, height
        );
      }, width, height);
      return {
        percent: scorePlanes(reference, candidate, width, height),
        geometry: boxes.geometry
      };
    });
  }

  /**
   * The pixel diff behind the Reference / Diff / Actual detail page.
   *
   * Diffed over the same content-box normalisation the score uses. Diffing raw canvases only worked
   * while both sides happened to be exported at identical dimensions, and when they were not, the
   * page had nothing to show but "dimensions differ" — least useful exactly when a visitor most
   * wants to see where the two drift apart.
   */
  function compareImageUrls(referenceUrl, actualUrl, canvas) {
    return Promise.all([loadImage(referenceUrl), loadImage(actualUrl)]).then(function (images) {
      var boxes = normalisedBoxes(images[0], images[1]);
      var referenceBox = boxes.reference;
      var actualBox = boxes.candidate;
      var width = actualBox.width;
      var height = actualBox.height;
      canvas.width = width;
      canvas.height = height;
      var context = canvas.getContext("2d", { willReadFrequently: true });
      function readNormalised(image, box) {
        context.clearRect(0, 0, width, height);
        context.drawImage(image, box.x, box.y, box.width, box.height, 0, 0, width, height);
        return context.getImageData(0, 0, width, height);
      }
      var reference = readNormalised(images[0], referenceBox);
      var actual = readNormalised(images[1], actualBox);
      var diff = context.createImageData(width, height);
      var changed = 0;
      for (var i = 0; i < reference.data.length; i += 4) {
        var delta = Math.max(
          Math.abs(reference.data[i] - actual.data[i]),
          Math.abs(reference.data[i + 1] - actual.data[i + 1]),
          Math.abs(reference.data[i + 2] - actual.data[i + 2]),
          Math.abs(reference.data[i + 3] - actual.data[i + 3])
        );
        if (delta > 3) {
          changed++;
          diff.data[i] = 229;
          diff.data[i + 1] = 46;
          diff.data[i + 2] = 115;
          diff.data[i + 3] = Math.min(255, 96 + delta);
        }
      }
      context.clearRect(0, 0, width, height);
      context.putImageData(diff, 0, 0);
      return scoreImageUrls(referenceUrl, actualUrl).then(function (score) {
        return {
          score: score.percent,
          geometry: score.geometry,
          changed: changed,
          pixels: width * height
        };
      });
    });
  }

  window.ComposePreviewCompare = {
    loadImage: loadImage,
    scoreSvgUrls: scoreSvgUrls,
    scoreCanvas: scoreCanvas,
    scoreImageUrls: scoreImageUrls,
    compareImageUrls: compareImageUrls
  };

  var referenceRoot = document.getElementById("cp-reference-compare");
  if (referenceRoot) {
    var referenceUrl = referenceRoot.getAttribute("data-reference");
    var actualUrl = referenceRoot.getAttribute("data-actual");
    var diffCanvas = referenceRoot.querySelector(".cp-reference-diff");
    var resultText = referenceRoot.querySelector(".cp-reference-result");
    compareImageUrls(referenceUrl, actualUrl, diffCanvas).then(function (result) {
      var changedPercent = result.pixels ? result.changed * 100 / result.pixels : 0;
      // The geometry figure is only worth the visitor's attention once the two content boxes are
      // meaningfully different in shape; below that it is rasteriser noise.
      var geometry = result.geometry >= GEOMETRY_REPORT_THRESHOLD
        ? " · " + result.geometry.toFixed(1) + "% proportion difference"
        : "";
      resultText.textContent = result.score.toFixed(1) + "% structural match · " +
        changedPercent.toFixed(2) + "% pixels changed" + geometry;
    }, function () {
      resultText.textContent = "Comparison unavailable";
    });
    var overlayRange = referenceRoot.querySelector(".cp-overlay-range");
    var overlayActual = referenceRoot.querySelector(".cp-reference-overlay img:last-child");
    var overlayValue = referenceRoot.querySelector(".cp-overlay-control span");
    function applyOverlay() {
      overlayActual.style.opacity = String(parseInt(overlayRange.value, 10) / 100);
      overlayValue.textContent = overlayRange.value + "%";
    }
    overlayRange.addEventListener("input", applyOverlay);
    applyOverlay();
    setUpAnnotations(referenceRoot);
  }

  /**
   * Draw the typography / layout annotation layers over the reference and actual panels.
   *
   * Annotation bounds are in each image's own pixel space, and the two frames are routinely
   * different sizes, so every layer is scaled to its own panel's rendered size rather than to a
   * shared coordinate space. The boxes are re-laid-out on resize and once the image has loaded,
   * since natural dimensions are what the scale is computed from.
   */
  function setUpAnnotations(root) {
    var payloadNode = document.getElementById("cp-annotations");
    if (!payloadNode) return;
    var payload;
    try {
      payload = JSON.parse(payloadNode.textContent);
    } catch (error) {
      return;
    }
    var toggles = root.querySelectorAll("[data-cp-annotation-kind]");
    if (!toggles.length) return;

    var panels = [];
    Array.prototype.forEach.call(root.querySelectorAll("[data-cp-annotated]"), function (shot) {
      var side = shot.getAttribute("data-cp-annotated");
      var items = (payload[side] || []).filter(function (item) { return item && item.bounds; });
      if (!items.length) return;
      var image = shot.querySelector("img");
      if (!image) return;
      var layer = document.createElement("div");
      layer.className = "cp-annotation-layer";
      shot.appendChild(layer);
      panels.push({ shot: shot, image: image, items: items, layer: layer, boxes: [] });
    });
    if (!panels.length) return;

    // A spec label runs ~100px against panels ~200px wide, so an always-on caption per box cannot
    // fit once two annotations overlap — they truncate each other exactly where the nesting is
    // densest. The box therefore carries only an index badge, and the readable text goes in a
    // legend under the panel, where it also stays selectable and reachable without a pointer.
    panels.forEach(function (panel) {
      var legend = document.createElement("ol");
      legend.className = "cp-annotation-legend";
      panel.items.forEach(function (item, index) {
        var ordinal = String(index + 1);
        var box = document.createElement("div");
        box.className = "cp-annotation cp-annotation--" + item.kind;
        box.setAttribute("data-cp-kind", item.kind);
        box.title = item.role ? item.role + " · " + item.label : item.label;
        var badge = document.createElement("span");
        badge.className = "cp-annotation-badge";
        badge.textContent = ordinal;
        box.appendChild(badge);
        panel.layer.appendChild(box);
        panel.boxes.push({ node: box, bounds: item.bounds });

        var row = document.createElement("li");
        row.className = "cp-annotation-entry cp-annotation-entry--" + item.kind;
        row.setAttribute("data-cp-kind", item.kind);
        var marker = document.createElement("span");
        marker.className = "cp-annotation-badge";
        marker.textContent = ordinal;
        row.appendChild(marker);
        if (item.role) {
          var role = document.createElement("span");
          role.className = "cp-annotation-role";
          role.textContent = item.role;
          row.appendChild(role);
        }
        var text = document.createElement("span");
        text.className = "cp-annotation-spec";
        text.textContent = item.label;
        row.appendChild(text);
        legend.appendChild(row);
      });
      panel.shot.parentNode.appendChild(legend);
    });

    function place() {
      panels.forEach(function (panel) {
        var natural = panel.image.naturalWidth;
        if (!natural) return;
        // The image is width-constrained by the grid; scale uniformly off the rendered width so the
        // boxes track it through any responsive resize.
        var scale = panel.image.clientWidth / natural;
        panel.layer.style.width = panel.image.clientWidth + "px";
        panel.layer.style.height = panel.image.clientHeight + "px";
        panel.boxes.forEach(function (box) {
          box.node.style.left = (box.bounds.x * scale) + "px";
          box.node.style.top = (box.bounds.y * scale) + "px";
          box.node.style.width = (box.bounds.width * scale) + "px";
          box.node.style.height = (box.bounds.height * scale) + "px";
        });
      });
    }

    function syncKinds() {
      Array.prototype.forEach.call(toggles, function (toggle) {
        var kind = toggle.getAttribute("data-cp-annotation-kind");
        root.setAttribute("data-annotate-" + kind, toggle.checked ? "on" : "off");
      });
      place();
    }

    Array.prototype.forEach.call(toggles, function (toggle) {
      toggle.addEventListener("change", syncKinds);
    });
    window.addEventListener("resize", place);
    panels.forEach(function (panel) {
      if (panel.image.complete) return;
      panel.image.addEventListener("load", place);
    });
    syncKinds();
  }

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
  // Which formats this page actually has something to compare — a `?format=` naming any other one
  // is ignored rather than emptying the table.
  function supportsFormat(candidate) {
    if (candidate === "rc") return root.getAttribute("data-has-rc") === "1";
    if (candidate === "svg") return root.getAttribute("data-has-svg") === "1";
    if (candidate === "reference") return root.getAttribute("data-has-reference") === "1";
    return false;
  }
  if (params.get("format") && supportsFormat(params.get("format"))) format = params.get("format");
  try {
    var remembered = localStorage.getItem(root.getAttribute("data-theme-key"));
    if (remembered === "light" || remembered === "dark") theme = remembered;
  } catch (ignore) {}
  // An explicit ?theme= outranks the remembered one — the URL is only carrying it because someone
  // picked it here or was handed the link.
  var urlTheme = params.get("theme");
  if (urlTheme === "light" || urlTheme === "dark") theme = urlTheme;
  if (search && params.get("q")) search.value = params.get("q");
  // What Back falls back to on an entry that names no format/theme: what this load resolved to.
  var initialFormat = format;
  var initialTheme = theme;
  function pushUrl(values) { if (window.cpUrlState) window.cpUrlState.push(values); }
  function replaceUrl(values) { if (window.cpUrlState) window.cpUrlState.replace(values); }

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
      // Artifact theme is an explicit comparison input; it must not inherit the site's / OS's
      // prefers-color-scheme or a light PNG can be scored against a dark RC canvas.
      player.setTheme(theme);
      return player.loadFromArrayBuffer(buffer).then(function () {
        if (player.repaint) player.repaint();
        // The first paint discovers named font families. Wait for those faces, repaint with the
        // resolved glyphs, and only then take the single-shot fidelity measurement.
        return player.fontsReady().then(function () {
          if (player.repaint) player.repaint();
          return nextFrame().then(nextFrame).then(function () { return scoreCanvas(pngUrl, canvas); });
        });
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
    if (format === "svg" || format === "reference") {
      vector.hidden = false;
      canvas.hidden = true;
      vector.src = candidateUrl;
      vector.alt = row.getAttribute("data-label") + (format === "svg" ? " SVG" : " design reference");
      vector.title = format === "reference" ? "Open Reference / Diff / Actual" : "";
      vector.onclick = format === "reference"
        ? function () { location.href = sourceFor(row, "reference-detail", variant); }
        : null;
    } else {
      vector.hidden = true;
      canvas.hidden = false;
    }
    // The vector lanes score a render against an export of that same render, so they share its
    // geometry by construction and report a bare percentage. Only the reference lane compares
    // independently-authored artwork, and only it carries a geometry figure.
    var result = format === "svg"
      ? scoreSvgUrls(pngUrl, candidateUrl).then(function (percent) { return { percent: percent }; })
      : format === "reference"
        ? scoreImageUrls(candidateUrl, pngUrl)
        : renderRc(row, pngUrl, candidateUrl).then(function (percent) { return { percent: percent }; });
    return result.then(function (measured) {
      if (run !== sequence) return null;
      var percent = measured.percent;
      row.setAttribute("data-score", String(percent));
      score.textContent = percent.toFixed(1) + "%";
      score.className = "cp-compare-score cp-compare-score--" + grade(percent);
      if (typeof measured.geometry === "number") {
        row.setAttribute("data-geometry-delta", measured.geometry.toFixed(2));
        score.title = measured.geometry >= GEOMETRY_REPORT_THRESHOLD
          ? measured.geometry.toFixed(1) + "% proportion difference between the two content boxes"
          : "";
      } else {
        row.removeAttribute("data-geometry-delta");
      }
      return percent;
    }, function () {
      if (run !== sequence) return null;
      row.setAttribute("data-score", "-1");
      row.removeAttribute("data-geometry-delta");
      score.textContent = "unavailable";
      score.className = "cp-compare-score cp-compare-score--na";
      return null;
    });
  }

  function applySearch() {
    var query = (search.value || "").trim().toLowerCase();
    var preview = (new URLSearchParams(location.search).get("preview") || "").toLowerCase();
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
      // A discrete pick gets its own history entry (it used to overwrite the current one), so Back
      // returns to the format the visitor was comparing before.
      pushUrl({ format: format });
      run();
    });
  });
  Array.prototype.forEach.call(themeButtons, function (button) {
    button.addEventListener("click", function () {
      theme = button.getAttribute("data-compare-theme");
      try { localStorage.setItem(root.getAttribute("data-theme-key"), theme); } catch (ignore) {}
      pushUrl({ theme: theme });
      run();
    });
  });
  // Typing replaces rather than pushes: the filter is still bookmarkable, without one entry per
  // keystroke.
  search.addEventListener("input", function () {
    replaceUrl({ q: search.value.trim() });
    applySearch();
  });
  if (window.cpUrlState) {
    window.cpUrlState.onPop(function () {
      var popped = new URLSearchParams(location.search);
      var poppedFormat = popped.get("format");
      format = poppedFormat && supportsFormat(poppedFormat) ? poppedFormat : initialFormat;
      var poppedTheme = popped.get("theme");
      theme = (poppedTheme === "light" || poppedTheme === "dark") ? poppedTheme : initialTheme;
      if (search) search.value = popped.get("q") || "";
      run();
    });
  }
  run();
})();
