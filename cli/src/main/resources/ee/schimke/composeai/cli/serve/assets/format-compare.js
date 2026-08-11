(function () {
  "use strict";

  var C1 = 6.5025;
  var C2 = 58.5225;
  var MAX_SIDE = 192;
  // Figma's browser SVG rasteriser and Skia's Compose rasteriser cover the same vector edge with
  // different sub-pixels. Search a small neighbourhood on the comparison plane, symmetrically, so
  // those edge pixels do not become a structural failure. Five pixels at the capped 192 px plane
  // is still narrow enough that displaced or missing marks remain visible to the score.
  var EDGE_SEARCH_RADIUS = 5;
  var LUMA_TOLERANCE = 16;
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
    function directed(source, target) {
      var total = 0;
      for (var y = 0; y < height; y++) {
        for (var x = 0; x < width; x++) {
          var value = source[y * width + x];
          var best = 255;
          for (var oy = -EDGE_SEARCH_RADIUS; oy <= EDGE_SEARCH_RADIUS; oy++) {
            var yy = Math.max(0, Math.min(height - 1, y + oy));
            for (var ox = -EDGE_SEARCH_RADIUS; ox <= EDGE_SEARCH_RADIUS; ox++) {
              var xx = Math.max(0, Math.min(width - 1, x + ox));
              best = Math.min(best, Math.abs(value - target[yy * width + xx]));
            }
          }
          total += Math.max(0, best - LUMA_TOLERANCE) / (255 - LUMA_TOLERANCE);
        }
      }
      return total / (width * height);
    }
    // Both directions matter: a one-way search would let an extra mark hide beside a matching one.
    var mismatch = (directed(reference, candidate) + directed(candidate, reference)) / 2;
    return Math.max(0, Math.min(100, (1 - mismatch) * 100));
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
   * image is translated and rescaled relative to its partner, which reads as total mismatch.
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
      return scoreImages(images[0], images[1]);
    });
  }

  /**
   * [scoreImageUrls] over frames that are already decoded.
   *
   * Split out so a caller holding the images — the viewer's spec lane, which has just normalised
   * them onto its canvases — can score the very frames it drew instead of re-requesting the URLs.
   * That matters beyond the wasted work: an override-bearing `/render` is `no-store`, so a second
   * request is a second render, and the score could end up describing a different frame than the
   * diff beside it. The downscale still starts from the ORIGINAL images (not from the normalised
   * canvases), so this is one resample exactly as before and the numbers are unchanged.
   */
  function scoreImages(referenceImage, candidateImage) {
    return Promise.resolve().then(function () {
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
   * One image's content box redrawn into a fresh canvas of the shared comparison size.
   *
   * `willReadFrequently` because the very next thing anyone does with these is `getImageData` (the
   * diff walks both of them pixel by pixel), and the flag has to be set on the FIRST getContext —
   * a later call with different attributes silently returns the existing context.
   */
  function boxCanvas(image, box, width, height) {
    var canvas = document.createElement("canvas");
    canvas.width = width;
    canvas.height = height;
    canvas
      .getContext("2d", { willReadFrequently: true })
      .drawImage(image, box.x, box.y, box.width, box.height, 0, 0, width, height);
    return canvas;
  }

  /**
   * Both frames redrawn at ONE shared size: each side's content box (see [normalisedBoxes]) scaled
   * onto the candidate's box.
   *
   * This is the step every pixel-for-pixel surface needs before it can say anything true — the diff
   * map, the triptych's three panels, the wipe's two halves. A design reference exported at a
   * different scale, or with different padding, than the render is the normal case, not the
   * exception; comparing the raw frames would put the two components' pixels at different addresses
   * and every downstream surface would be reporting the offset rather than the divergence.
   */
  function normaliseImageUrls(referenceUrl, candidateUrl) {
    return Promise.all([loadImage(referenceUrl), loadImage(candidateUrl)]).then(function (images) {
      var boxes = normalisedBoxes(images[0], images[1]);
      var width = boxes.candidate.width;
      var height = boxes.candidate.height;
      return {
        width: width,
        height: height,
        geometry: boxes.geometry,
        reference: boxCanvas(images[0], boxes.reference, width, height),
        candidate: boxCanvas(images[1], boxes.candidate, width, height),
        // The decoded originals, so a caller can score (see scoreImages) without asking the
        // network for the same two frames a second time.
        images: images
      };
    });
  }

  /**
   * Paint the magenta delta map of two already-normalised, same-sized canvases into `target`, and
   * report how many pixels actually moved.
   *
   * A pixel is "changed" once any channel — alpha included, so a mark appearing over transparency
   * counts — differs by more than 3/255, which is PNG round-tripping and resampling noise. The mark
   * grows more opaque with the size of the delta, so a wholesale colour swap reads louder than a
   * one-pixel edge shift.
   */
  function diffCanvases(reference, candidate, target) {
    var width = reference.width;
    var height = reference.height;
    target.width = width;
    target.height = height;
    var context = target.getContext("2d", { willReadFrequently: true });
    var referenceData = reference.getContext("2d", { willReadFrequently: true })
      .getImageData(0, 0, width, height);
    var candidateData = candidate.getContext("2d", { willReadFrequently: true })
      .getImageData(0, 0, width, height);
    var diff = context.createImageData(width, height);
    var changed = 0;
    for (var i = 0; i < referenceData.data.length; i += 4) {
      var delta = Math.max(
        Math.abs(referenceData.data[i] - candidateData.data[i]),
        Math.abs(referenceData.data[i + 1] - candidateData.data[i + 1]),
        Math.abs(referenceData.data[i + 2] - candidateData.data[i + 2]),
        Math.abs(referenceData.data[i + 3] - candidateData.data[i + 3])
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
    return changed;
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
    return normaliseImageUrls(referenceUrl, actualUrl).then(function (frames) {
      var changed = diffCanvases(frames.reference, frames.candidate, canvas);
      return scoreImages(frames.images[0], frames.images[1]).then(function (score) {
        return {
          score: score.percent,
          geometry: score.geometry,
          changed: changed,
          pixels: frames.width * frames.height
        };
      });
    });
  }

  window.ComposePreviewCompare = {
    loadImage: loadImage,
    scoreSvgUrls: scoreSvgUrls,
    scoreCanvas: scoreCanvas,
    scoreImageUrls: scoreImageUrls,
    scoreImages: scoreImages,
    normaliseImageUrls: normaliseImageUrls,
    diffCanvases: diffCanvases,
    compareImageUrls: compareImageUrls,
    matchAnnotationItems: matchAnnotationItems
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
   * Pair the annotations that describe the same element on each side.
   *
   * Figma commonly exposes a much deeper tree than Compose semantics (state layers, icon
   * containers, dividers, and so on). Drawing both flattened trees independently therefore gives
   * unrelated ordinal numbers and, in a menu, can put ninety Figma boxes beside six Compose
   * boxes. Use the design side as the inventory and consume each reference item at most once.
   * Candidate bounds are first mapped into the reference frame by the same width-derived uniform
   * scale used by design-parity's structural layout diff; aligning the two largest layout boxes
   * also removes a preview scaffold/crop offset. Role/label similarity breaks geometric ties.
   *
   * The result contains min(reference, actual) items for every kind present on both sides. An
   * annotation kind captured on only one side is retained as a useful inspection-only layer.
   */
  function matchAnnotationItems(reference, actual) {
    reference = Array.isArray(reference) ? reference.filter(annotationHasBounds) : [];
    actual = Array.isArray(actual) ? actual.filter(annotationHasBounds) : [];
    if (!reference.length || !actual.length) return { reference: reference, actual: actual };

    var referenceFrame = largestAnnotationFrame(reference);
    var actualFrame = largestAnnotationFrame(actual);
    var scale = referenceFrame && actualFrame && actualFrame.width > 0
      ? referenceFrame.width / actualFrame.width
      : 1;
    var kinds = [];
    reference.concat(actual).forEach(function (item) {
      if (kinds.indexOf(item.kind) < 0) kinds.push(item.kind);
    });

    var pairs = [];
    var referenceOnly = [];
    var actualOnly = [];
    kinds.forEach(function (kind) {
      var refs = [];
      var cands = [];
      reference.forEach(function (item, index) {
        if (item.kind === kind) refs.push({ item: item, index: index });
      });
      actual.forEach(function (item, index) {
        if (item.kind === kind) cands.push({ item: item, index: index });
      });
      if (!refs.length) {
        actualOnly = actualOnly.concat(cands);
        return;
      }
      if (!cands.length) {
        referenceOnly = referenceOnly.concat(refs);
        return;
      }

      var used = {};
      cands.forEach(function (cand) {
        var mapped = mapAnnotationBounds(cand.item.bounds, referenceFrame, actualFrame, scale);
        var best = -1;
        var bestCost = Infinity;
        refs.forEach(function (ref, refIndex) {
          if (used[refIndex]) return;
          var cost = annotationMatchCost(ref.item, cand.item, ref.item.bounds, mapped, referenceFrame);
          if (cost < bestCost) {
            bestCost = cost;
            best = refIndex;
          }
        });
        // Once the design inventory is exhausted, extra render nodes have no design element to
        // compare with. Deliberately omit them rather than restarting/reusing ordinal numbers.
        if (best < 0) return;
        used[best] = true;
        pairs.push({ reference: refs[best], actual: cand });
      });
    });

    // Both legends follow design order, so a shared ordinal identifies the same element on the two
    // panels even when the Compose tree arrived in a different traversal order.
    pairs.sort(function (a, b) { return a.reference.index - b.reference.index; });
    var referenceOut = [];
    var actualOut = [];
    pairs.forEach(function (pair, index) {
      var ordinal = index + 1;
      referenceOut.push(withAnnotationOrdinal(pair.reference.item, ordinal));
      actualOut.push(withAnnotationOrdinal(pair.actual.item, ordinal));
    });
    referenceOnly.forEach(function (entry) {
      referenceOut.push(withAnnotationOrdinal(entry.item, referenceOut.length + 1));
    });
    actualOnly.forEach(function (entry) {
      actualOut.push(withAnnotationOrdinal(entry.item, actualOut.length + 1));
    });
    return { reference: referenceOut, actual: actualOut };
  }

  function annotationHasBounds(item) {
    var b = item && item.bounds;
    return !!b && isFinite(b.x) && isFinite(b.y) && isFinite(b.width) && isFinite(b.height) &&
      b.width > 0 && b.height > 0;
  }

  function largestAnnotationFrame(items) {
    var layouts = items.filter(function (item) { return item.kind === "layout"; });
    var pool = layouts.length ? layouts : items;
    var largest = null;
    pool.forEach(function (item) {
      if (!largest || item.bounds.width * item.bounds.height > largest.width * largest.height) {
        largest = item.bounds;
      }
    });
    return largest;
  }

  function mapAnnotationBounds(bounds, referenceFrame, actualFrame, scale) {
    if (!referenceFrame || !actualFrame) return bounds;
    return {
      x: referenceFrame.x + (bounds.x - actualFrame.x) * scale,
      y: referenceFrame.y + (bounds.y - actualFrame.y) * scale,
      width: bounds.width * scale,
      height: bounds.height * scale
    };
  }

  function annotationText(value) {
    return String(value || "").trim().toLowerCase().replace(/\b(?:px|dp|sp)\b/g, "u");
  }

  function annotationRoleFamily(value) {
    return annotationText(value)
      .replace(/\b(?:first|last)\b/g, "")
      .replace(/\b\d+\b/g, "")
      .replace(/[^a-z]+/g, " ")
      .trim();
  }

  function annotationMatchCost(ref, cand, rb, cb, frame) {
    var fw = frame && frame.width > 0 ? frame.width : Math.max(rb.width, cb.width, 1);
    var fh = frame && frame.height > 0 ? frame.height : Math.max(rb.height, cb.height, 1);
    var rcx = rb.x + rb.width / 2;
    var rcy = rb.y + rb.height / 2;
    var ccx = cb.x + cb.width / 2;
    var ccy = cb.y + cb.height / 2;
    var position = Math.hypot((rcx - ccx) / fw, (rcy - ccy) / fh);
    var size = Math.abs(rb.width - cb.width) / fw + Math.abs(rb.height - cb.height) / fh;
    var cost = position + size * 0.5;
    var rr = annotationText(ref.role);
    var cr = annotationText(cand.role);
    if (rr && cr) {
      if (rr === cr) cost -= 0.06;
      else if (annotationRoleFamily(rr) === annotationRoleFamily(cr)) cost -= 0.03;
      else cost += 0.02;
    }
    if (annotationText(ref.label) === annotationText(cand.label)) cost -= 0.04;
    return cost;
  }

  function withAnnotationOrdinal(item, ordinal) {
    var copy = {};
    Object.keys(item).forEach(function (key) { copy[key] = item[key]; });
    copy.comparisonOrdinal = ordinal;
    return copy;
  }

  /**
   * Draw the typography / layout annotation layers over the reference and actual panels.
   *
   * Annotation bounds are in each image's own pixel space, and the two frames are routinely
   * different sizes, so every layer is scaled to its own panel's rendered size rather than to a
   * shared coordinate space. The boxes are re-laid-out on resize and once the image has loaded,
   * since natural dimensions are what the scale is computed from.
   */
  function annotationNumber(value) {
    if (value === undefined || value === null || value === "") return undefined;
    var number = Number(value);
    return Number.isFinite(number) ? number : undefined;
  }

  function typographyToken(detail) {
    var token = String((detail && detail.token) || "").trim();
    if (!token || token.toLowerCase() === "text") return undefined;
    var m3 = token.match(/^m3[\/-](display|headline|title|body|label)[\/-](large|medium|small)$/i);
    if (m3) return m3[1].toLowerCase() + m3[2].charAt(0).toUpperCase() + m3[2].slice(1).toLowerCase();
    return token;
  }

  function typographyFamily(value) {
    var raw = String(value || "").trim();
    if (!raw) return undefined;
    return raw.replace(/[-_](regular|medium|semibold|bold)$/i, "").trim();
  }

  function typographySpec(item) {
    var detail = item.detail || {};
    var size = annotationNumber(detail.fontSize !== undefined ? detail.fontSize : detail.size);
    var lineHeight = annotationNumber(detail.lineHeight);
    var weight = annotationNumber(detail.fontWeight);
    var family = String(detail.fontFamily || "").trim() || undefined;
    var unit = String(detail.unit || "").trim() || undefined;
    var tracking = String(detail.letterSpacing || detail.tracking || "").trim() || undefined;
    var style = String(detail.fontStyle || "").trim() || undefined;
    return {
      token: typographyToken(detail),
      family: family,
      familyKey: typographyFamily(family),
      size: size,
      lineHeight: lineHeight,
      weight: weight,
      unit: unit,
      tracking: tracking,
      style: style,
      label: item.label
    };
  }

  function typographyGroupKey(spec) {
    return [spec.token || "", spec.familyKey || "", spec.size, spec.lineHeight, spec.weight,
      spec.tracking || "", spec.style || ""].join("|");
  }

  function groupTypography(items) {
    var byKey = new Map();
    items.filter(function (item) { return item.kind === "typography"; }).forEach(function (item) {
      var spec = typographySpec(item);
      var key = typographyGroupKey(spec);
      var group = byKey.get(key);
      if (!group) {
        group = { key: key, spec: spec, items: [], roles: new Set() };
        byKey.set(key, group);
      }
      group.items.push(item);
      if (item.role) group.roles.add(String(item.role).trim().toLowerCase());
    });
    return Array.from(byKey.values());
  }

  function typographyDefaults(groups) {
    var defaults = new Map();
    groups.forEach(function (group) {
      if (!group.spec.token) return;
      var current = defaults.get(group.spec.token);
      if (!current || group.items.length > current.items.length) defaults.set(group.spec.token, group);
    });
    return defaults;
  }

  function typographyDistance(left, right) {
    if (left.key === right.key) return -200;
    if (left.spec.token && right.spec.token && left.spec.token === right.spec.token) return -100;
    var commonRoles = 0;
    left.roles.forEach(function (role) { if (right.roles.has(role)) commonRoles += 1; });
    if (commonRoles) return -50 - commonRoles;
    var a = left.spec;
    var b = right.spec;
    var distance = 0;
    if (a.size !== undefined && b.size !== undefined) distance += Math.abs(a.size - b.size) * 3;
    else if (a.size !== b.size) distance += 8;
    if (a.lineHeight !== undefined && b.lineHeight !== undefined)
      distance += Math.abs(a.lineHeight - b.lineHeight) * 2;
    else if (a.lineHeight !== b.lineHeight) distance += 5;
    if (a.weight !== undefined && b.weight !== undefined) distance += Math.abs(a.weight - b.weight) / 100;
    else if (a.weight !== b.weight) distance += 2;
    if ((a.familyKey || "").toLowerCase() !== (b.familyKey || "").toLowerCase()) distance += 2;
    if ((a.style || "").toLowerCase() !== (b.style || "").toLowerCase()) distance += 1;
    if ((a.tracking || "") !== (b.tracking || "")) distance += 1;
    return distance;
  }

  function pairTypography(reference, actual) {
    var remaining = actual.slice();
    var pairs = reference.map(function (ref) {
      var bestIndex = -1;
      var bestDistance = Infinity;
      remaining.forEach(function (candidate, index) {
        var distance = typographyDistance(ref, candidate);
        if (distance < bestDistance) {
          bestIndex = index;
          bestDistance = distance;
        }
      });
      var matched = bestIndex >= 0 && bestDistance <= 15 ? remaining.splice(bestIndex, 1)[0] : undefined;
      return { reference: ref, actual: matched };
    });
    remaining.forEach(function (actualOnly) { pairs.push({ actual: actualOnly }); });
    pairs.forEach(function (pair, index) {
      var marker = index < 26 ? String.fromCharCode(65 + index) : String(index + 1);
      pair.marker = marker;
      if (pair.reference) pair.reference.marker = marker;
      if (pair.actual) pair.actual.marker = marker;
    });
    return pairs;
  }

  function expandedBoxesTouch(left, right, xGap, yGap) {
    return left.x <= right.x + right.width + xGap &&
      right.x <= left.x + left.width + xGap &&
      left.y <= right.y + right.height + yGap &&
      right.y <= left.y + left.height + yGap;
  }

  function unionBounds(items) {
    var left = Math.min.apply(null, items.map(function (item) { return item.bounds.x; }));
    var top = Math.min.apply(null, items.map(function (item) { return item.bounds.y; }));
    var right = Math.max.apply(null, items.map(function (item) { return item.bounds.x + item.bounds.width; }));
    var bottom = Math.max.apply(null, items.map(function (item) { return item.bounds.y + item.bounds.height; }));
    return { x: left, y: top, width: right - left, height: bottom - top };
  }

  function clusterTypography(group) {
    var lineHeight = group.spec.lineHeight || 16;
    var xGap = Math.max(12, lineHeight * 4);
    var yGap = Math.max(8, lineHeight * 1.25);
    var clusters = [];
    group.items.forEach(function (item) {
      var touching = [];
      clusters.forEach(function (cluster, index) {
        if (cluster.items.some(function (other) {
          return expandedBoxesTouch(item.bounds, other.bounds, xGap, yGap);
        })) touching.push(index);
      });
      if (!touching.length) {
        clusters.push({ items: [item] });
        return;
      }
      var target = clusters[touching[0]];
      target.items.push(item);
      for (var i = touching.length - 1; i > 0; i -= 1) {
        target.items = target.items.concat(clusters[touching[i]].items);
        clusters.splice(touching[i], 1);
      }
    });
    return clusters.map(function (cluster) { return unionBounds(cluster.items); });
  }

  function typographyValue(spec, field) {
    if (!spec) return "—";
    if (field === "token") return spec.token || "unmapped";
    if (field === "family") return spec.family || "unspecified";
    if (field === "size") {
      if (spec.size === undefined) return "—";
      return String(spec.size) + (spec.unit || "");
    }
    if (field === "lineHeight") return spec.lineHeight === undefined ? "—" : String(spec.lineHeight);
    if (field === "weight") return spec.weight === undefined ? "—" : String(spec.weight);
    if (field === "tracking") return spec.tracking || "default";
    if (field === "style") return spec.style || "normal";
    return "—";
  }

  function typographyComparableValue(spec, field) {
    if (!spec) return "—";
    if (field === "family") return (spec.familyKey || "unspecified").toLowerCase();
    if (field === "size") return spec.size === undefined ? "—" : String(spec.size);
    return typographyValue(spec, field).toLowerCase();
  }

  function typographyInlineSide(label, group, other, baseline) {
    var side = document.createElement("span");
    side.className = "cp-typography-inline";
    var sideLabel = document.createElement("span");
    sideLabel.className = "cp-typography-side";
    sideLabel.textContent = label;
    side.appendChild(sideLabel);
    if (!group) {
      side.appendChild(document.createTextNode(" No matching usage"));
      return side;
    }
    var fields = ["token", "family", "weight"];
    fields.push("size");
    fields.forEach(function (field) {
      side.appendChild(document.createTextNode(" · "));
      var value = document.createElement("span");
      var text = typographyValue(group.spec, field);
      if (field === "weight" && text !== "—") text = "wght " + text;
      if (field === "size" && group.spec.lineHeight !== undefined)
        text += "/" + group.spec.lineHeight;
      value.textContent = text;
      var comparisonChanged = other && (
        typographyComparableValue(group.spec, field) !== typographyComparableValue(other.spec, field) ||
        (field === "size" && typographyComparableValue(group.spec, "lineHeight") !== typographyComparableValue(other.spec, "lineHeight"))
      );
      var overrideChanged = baseline && baseline !== group && (
        typographyComparableValue(group.spec, field) !== typographyComparableValue(baseline.spec, field) ||
        (field === "size" && typographyComparableValue(group.spec, "lineHeight") !== typographyComparableValue(baseline.spec, "lineHeight"))
      );
      if (comparisonChanged || overrideChanged) value.className = "cp-typography-changed";
      if (overrideChanged) {
        value.classList.add("cp-typography-override");
        value.title = "Changed from " + group.spec.token + " default";
      }
      side.appendChild(value);
    });
    if (group.spec.tracking && group.spec.tracking !== "default") {
      side.appendChild(document.createTextNode(" · "));
      var tracking = document.createElement("span");
      tracking.textContent = "tracking " + group.spec.tracking;
      var trackingChanged = (other && typographyComparableValue(group.spec, "tracking") !== typographyComparableValue(other.spec, "tracking")) ||
        (baseline && baseline !== group && typographyComparableValue(group.spec, "tracking") !== typographyComparableValue(baseline.spec, "tracking"));
      if (trackingChanged) tracking.className = "cp-typography-changed";
      if (baseline && baseline !== group && typographyComparableValue(group.spec, "tracking") !== typographyComparableValue(baseline.spec, "tracking")) {
        tracking.classList.add("cp-typography-override");
        tracking.title = "Changed from " + group.spec.token + " default";
      }
      side.appendChild(tracking);
    }
    if (group.spec.style && group.spec.style !== "normal") {
      side.appendChild(document.createTextNode(" · "));
      var style = document.createElement("span");
      style.textContent = group.spec.style;
      var styleChanged = (other && typographyComparableValue(group.spec, "style") !== typographyComparableValue(other.spec, "style")) ||
        (baseline && baseline !== group && typographyComparableValue(group.spec, "style") !== typographyComparableValue(baseline.spec, "style"));
      if (styleChanged) style.className = "cp-typography-changed";
      if (baseline && baseline !== group && typographyComparableValue(group.spec, "style") !== typographyComparableValue(baseline.spec, "style")) {
        style.classList.add("cp-typography-override");
        style.title = "Changed from " + group.spec.token + " default";
      }
      side.appendChild(style);
    }
    var count = document.createElement("span");
    count.className = "cp-typography-count";
    count.textContent = group.items.length + (group.items.length === 1 ? " usage" : " usages");
    side.appendChild(document.createTextNode(" · "));
    side.appendChild(count);
    return side;
  }

  function appendTypographySummary(root, grid, pairs, referenceDefaults, actualDefaults) {
    if (!pairs.length) return;
    var summary = document.createElement("section");
    summary.className = "cp-typography-summary";
    summary.setAttribute("aria-label", "Typography style comparison");
    var heading = document.createElement("h2");
    heading.textContent = "Typography styles";
    summary.appendChild(heading);
    var list = document.createElement("div");
    list.className = "cp-typography-groups";
    pairs.forEach(function (pair) {
      var ref = pair.reference && pair.reference.spec;
      var actual = pair.actual && pair.actual.spec;
      var row = document.createElement("article");
      row.className = "cp-typography-group";
      row.setAttribute("data-cp-typography-marker", pair.marker);
      row.setAttribute("tabindex", "0");
      var marker = document.createElement("span");
      marker.className = "cp-annotation-badge cp-typography-marker";
      marker.textContent = pair.marker;
      row.appendChild(marker);
      row.appendChild(typographyInlineSide("Reference", pair.reference, pair.actual,
        pair.reference && referenceDefaults.get(pair.reference.spec.token)));
      var arrow = document.createElement("span");
      arrow.className = "cp-typography-arrow";
      arrow.setAttribute("aria-hidden", "true");
      arrow.textContent = "→";
      row.appendChild(arrow);
      row.appendChild(typographyInlineSide("Actual", pair.actual, pair.reference,
        pair.actual && actualDefaults.get(pair.actual.spec.token)));
      list.appendChild(row);
    });
    summary.appendChild(list);
    grid.parentNode.insertBefore(summary, grid.nextSibling);
  }

  function setUpAnnotations(root) {
    var payloadNode = document.getElementById("cp-annotations");
    if (!payloadNode) return;
    var payload;
    try {
      payload = JSON.parse(payloadNode.textContent);
    } catch (error) {
      return;
    }
    payload = matchAnnotationItems(payload.reference, payload.actual);
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
      panels.push({ shot: shot, image: image, side: side, items: items, layer: layer, boxes: [] });
    });
    if (!panels.length) return;

    var referenceGroups = groupTypography(payload.reference || []);
    var actualGroups = groupTypography(payload.actual || []);
    var typographyPairs = pairTypography(referenceGroups, actualGroups);
    var grid = root.querySelector(".cp-reference-grid");
    if (grid) appendTypographySummary(root, grid, typographyPairs,
      typographyDefaults(referenceGroups), typographyDefaults(actualGroups));

    // Layout remains an instance-level redline. Typography is style-level: every matching use gets
    // the same letter and nearby uses are surrounded by one cluster box, while the readable type
    // settings live once in the comparison table below the three panels.
    panels.forEach(function (panel) {
      var legend = document.createElement("ol");
      legend.className = "cp-annotation-legend";
      var layoutItems = panel.items.filter(function (item) { return item.kind !== "typography"; });
      layoutItems.forEach(function (item, index) {
        var ordinal = String(item.comparisonOrdinal || index + 1);
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
      if (layoutItems.length) panel.shot.parentNode.appendChild(legend);

      var groups = panel.side === "reference" ? referenceGroups : actualGroups;
      groups.forEach(function (group) {
        clusterTypography(group).forEach(function (bounds) {
          var box = document.createElement("div");
          box.className = "cp-annotation cp-annotation--typography cp-annotation--typography-cluster";
          box.setAttribute("data-cp-kind", "typography");
          box.setAttribute("data-cp-typography-marker", group.marker);
          box.title = ((group.spec.token || "Resolved style") + " · " + group.spec.label);
          var badge = document.createElement("span");
          badge.className = "cp-annotation-badge";
          badge.textContent = group.marker;
          box.appendChild(badge);
          panel.layer.appendChild(box);
          panel.boxes.push({ node: box, bounds: bounds });
        });
        group.items.forEach(function (item) {
          var hit = document.createElement("div");
          hit.className = "cp-annotation cp-annotation--typography cp-annotation--typography-hit";
          hit.setAttribute("data-cp-kind", "typography");
          hit.setAttribute("data-cp-typography-marker", group.marker);
          panel.layer.appendChild(hit);
          panel.boxes.push({ node: hit, bounds: item.bounds });
        });
      });
    });

    Array.prototype.forEach.call(root.querySelectorAll(".cp-typography-group"), function (row) {
      var marker = row.getAttribute("data-cp-typography-marker");
      var setActive = function (active) {
        Array.prototype.forEach.call(
          root.querySelectorAll('.cp-annotation[data-cp-typography-marker="' + marker + '"]'),
          function (node) { node.classList.toggle("cp-annotation-active", active); }
        );
      };
      row.addEventListener("mouseenter", function () { setActive(true); });
      row.addEventListener("mouseleave", function () { setActive(false); });
      row.addEventListener("focus", function () { setActive(true); });
      row.addEventListener("blur", function () { setActive(false); });
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
  var body = root.querySelector("#cp-compare-formats tbody");
  // The published Remote Compose player wall (rc-lanes.js), when this catalog has one. It replaces
  // the client-rendered `rc` lane wholesale: it shows every player rather than the one that runs in
  // a browser, and it replays renders the delivery branch already carries instead of decoding a
  // document per preview here.
  var lanesPane = document.getElementById("cp-rc-lanes");
  var formatsPane = document.getElementById("cp-compare-formats");
  function lanesActive() { return !!lanesPane && format === "rc"; }
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
    // The page registers the vendored faces the player's generic-family stacks name
    // (`/rc-fonts/fonts.css`); `cpRcFonts.ready()` is what actually *loads* them, since canvas
    // neither drives a lazy `@font-face` nor repaints when one arrives. Unawaited, this lane would
    // score the document drawn in the visitor's own `sans-serif` against a PNG baked with Roboto —
    // a permanent residual that reads as a layout defect.
    var fontsReady = window.cpRcFonts ? window.cpRcFonts.ready() : Promise.resolve();
    return Promise.all([ensureRcPlayer(), loadImage(pngUrl), fetch(documentUrl), fontsReady]).then(function (values) {
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
    if (lanesActive()) {
      if (window.cpRcLanes) window.cpRcLanes.filter(search.value);
      return;
    }
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
    if (count) count.textContent = visible + (visible === 1 ? " comparison" : " comparisons");
    if (empty) empty.hidden = visible !== 0;
  }

  function run() {
    var runId = ++sequence;
    setPressed(formatButtons, "data-compare-format", format);
    setPressed(themeButtons, "data-compare-theme", theme);
    root.setAttribute("data-format", format);
    root.setAttribute("data-theme", theme);
    // The lane wall is its own view: it owns its rows, its reference picker and its diffs, and it
    // needs none of the per-row scoring below (every number it shows was computed offline). Hand it
    // the filter and stop — leaving the client-rendered table running behind it would decode a
    // document per preview for a table nobody can see.
    if (lanesPane) lanesPane.hidden = !lanesActive();
    if (formatsPane) formatsPane.hidden = lanesActive();
    if (lanesActive()) {
      applySearch();
      return;
    }
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
      // Paint the page to match the theme being compared, when the visitor's Page theme setting
      // asks for that (page-theme.js decides; it is loaded after this file, hence the guard).
      if (window.cpPageTheme) window.cpPageTheme.follow(theme);
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
      if (window.cpPageTheme) window.cpPageTheme.follow(theme);
      run();
    });
  }
  run();
})();
