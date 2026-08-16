(function () {
  "use strict";

  var MAX_SIDE = 192;
  // Figma's browser SVG rasteriser and Skia's Compose rasteriser cover the same vector edge with
  // different sub-pixels. Search a small neighbourhood only for actual edge pixels, and charge a
  // positional cost for displacement so repeated luminances cannot hide a missing/added mark.
  var EDGE_SEARCH_RADIUS = 5;
  var EDGE_POSITION_COST = 10;
  var EDGE_GRADIENT_THRESHOLD = 12;
  var LUMA_TOLERANCE = 16;
  // Longest side of the downscale that content-box detection samples, and how far a pixel may sit
  // from the backdrop colour before it counts as drawn.
  var BOX_SAMPLE_SIDE = 256;
  var BOX_COLOUR_TOLERANCE = 12;
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
    // The comparison backdrop is deliberately fixed: site light/dark appearance must not change
    // the score, and a transparent frame composited onto anything else would be scored against a
    // different ground than its partner.
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

  function edgeMask(plane, width, height) {
    var mask = new Uint8Array(width * height);
    for (var y = 0; y < height; y++) {
      for (var x = 0; x < width; x++) {
        var index = y * width + x;
        var value = plane[index];
        var gradient = Math.max(
          Math.abs(value - plane[y * width + Math.max(0, x - 1)]),
          Math.abs(value - plane[y * width + Math.min(width - 1, x + 1)]),
          Math.abs(value - plane[Math.max(0, y - 1) * width + x]),
          Math.abs(value - plane[Math.min(height - 1, y + 1) * width + x])
        );
        if (gradient >= EDGE_GRADIENT_THRESHOLD) mask[index] = 1;
      }
    }
    return mask;
  }

  function yieldScorer() {
    return new Promise(function (resolve) { setTimeout(resolve, 0); });
  }

  async function scorePlanes(reference, candidate, width, height) {
    var referenceEdges = edgeMask(reference, width, height);
    var candidateEdges = edgeMask(candidate, width, height);
    async function directed(source, target, sourceEdges, targetEdges) {
      var total = 0;
      for (var y = 0; y < height; y++) {
        for (var x = 0; x < width; x++) {
          var index = y * width + x;
          var value = source[index];
          var best = Math.abs(value - target[index]);
          if (sourceEdges[index] && best > LUMA_TOLERANCE) {
            for (var oy = -EDGE_SEARCH_RADIUS; oy <= EDGE_SEARCH_RADIUS; oy++) {
              var yy = y + oy;
              if (yy < 0 || yy >= height) continue;
              for (var ox = -EDGE_SEARCH_RADIUS; ox <= EDGE_SEARCH_RADIUS; ox++) {
                var xx = x + ox;
                if (xx < 0 || xx >= width) continue;
                var targetIndex = yy * width + xx;
                if (!targetEdges[targetIndex]) continue;
                var displaced = Math.abs(value - target[targetIndex]) +
                  Math.sqrt(ox * ox + oy * oy) * EDGE_POSITION_COST;
                best = Math.min(best, displaced);
              }
            }
          }
          total += Math.max(0, best - LUMA_TOLERANCE) / (255 - LUMA_TOLERANCE);
        }
        // A full catalog performs dozens of comparisons. Yield in row-sized chunks so input and
        // painting remain responsive even for a dense edge mask.
        if (y % 8 === 7) await yieldScorer();
      }
      return total / (width * height);
    }
    // Both directions matter: a one-way search would let an extra mark hide beside a matching one.
    var mismatch = (
      await directed(reference, candidate, referenceEdges, candidateEdges) +
      await directed(candidate, reference, candidateEdges, referenceEdges)
    ) / 2;
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
      return scorePlanes(reference, candidate, width, height).then(function (percent) {
        return { percent: percent, geometry: boxes.geometry };
      });
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

  window.ComposePreviewCompare = {
    loadImage: loadImage,
    scoreSvgUrls: scoreSvgUrls,
    scoreCanvas: scoreCanvas,
    scoreImageUrls: scoreImageUrls,
    scoreImages: scoreImages,
    normaliseImageUrls: normaliseImageUrls,
    diffCanvases: diffCanvases
  };

})();
