/**
 * Content-box detection and the canonical plane, portably.
 *
 * The plane gate compares a **recomputed** plane against the one an acceptance recorded, so both
 * engines have to measure the same box from the same bytes — which makes content-box detection part
 * of the portable path rather than a host detail, exactly as
 * [§4's fixture-stage table](../../docs/design/COMPONENT_PARITY_WORKFLOW.md#two-engines-one-semantics)
 * says: *"content-box detection is itself part of the portable path and two engines can otherwise
 * measure differently near a sampled edge or the `MIN_BOX_COVERAGE` threshold"*. A one-pixel
 * disagreement there is not a rounding difference in a score; it is `plane-changed` on one engine
 * and `valid` on the other, from identical inputs.
 *
 * The **decisions** below — sheet-or-whole-image, alpha before colour, widen by one sample cell, the
 * coverage fallback — are `cli/serve-web/src/scorer/contentBox.ts`'s, unchanged, and its comments are
 * where the reasoning for each lives. What this file changes is the one host-dependent step: the
 * sampling downscale is the portable area average rather than `drawImage`, whose filter is not
 * reproducible off-browser.
 *
 * **Which means the browser's own `contentBox` and this one differ until the rebaseline.** They are
 * measuring the same picture through two kernels, so they agree on any box whose edges are not
 * within a sample cell of a partially-covered pixel and can differ by one there. That is the same
 * one-off move [D3](parity-batches/00-decisions.md#d3--the-score-rebaseline-is-versioned-and-when)
 * already covers, and it is why the acceptance path uses *this* function on both engines rather than
 * whichever one the host has: an acceptance evaluated against two different planes is the failure
 * this module exists to prevent, while a legacy score measured through `drawImage` is merely a
 * number that is about to move once.
 */

// The resampler and the outward-rounding rule live with the rest of the reference implementation of
// the contract; this file is a second consumer of them rather than a second copy.
import { enclosingBox, resampleArea } from "./known-differences.mjs";

/**
 * Detection tuning, mirroring `cli/serve-web/src/scorer/tuning.ts` for the reason
 * `known-difference-tuning.mjs` does — and checked against it by the same test.
 */
export const PLANE_TUNING = {
  /** Longest side of the downscale content-box detection samples. */
  BOX_SAMPLE_SIDE: 256,
  /** How far a pixel may sit from the backdrop colour before it counts as drawn. */
  BOX_COLOUR_TOLERANCE: 12,
  /** Smallest share of its canvas a content box may cover before cropping stops being trustworthy. */
  MIN_BOX_COVERAGE: 0.05,
  /** The backing colours `@Preview(showBackground = true)` resolves to. */
  SCAFFOLD_SHEETS: [
    [255, 255, 255],
    [28, 27, 31],
  ],
  /** Slack for PNG round-tripping and the detection downscale's resampling of an edge pixel. */
  SHEET_TOLERANCE: 6,
};

export const wholeImage = (size) => ({ x: 0, y: 0, width: size.width, height: size.height });

/** Whether an opaque corner colour is one of the sheets `showBackground` paints. */
export function isScaffoldSheet(rgb) {
  return PLANE_TUNING.SCAFFOLD_SHEETS.some(
    (sheet) =>
      Math.abs(rgb[0] - sheet[0]) <= PLANE_TUNING.SHEET_TOLERANCE &&
      Math.abs(rgb[1] - sheet[1]) <= PLANE_TUNING.SHEET_TOLERANCE &&
      Math.abs(rgb[2] - sheet[2]) <= PLANE_TUNING.SHEET_TOLERANCE,
  );
}

/** Whether any pixel in an RGBA buffer is meaningfully transparent. */
export function hasTransparency(pixels) {
  for (let probe = 3; probe < pixels.length; probe += 4) {
    if (pixels[probe] < 250) return true;
  }
  return false;
}

/**
 * The drawn rectangle, read off a downscaled RGBA sample and mapped back to source pixels.
 *
 * Detection uses alpha where the image has any: a transparent pixel is unambiguously not artwork. An
 * opaque image's backdrop is **not guessed** — it is trusted only when the corner is a sheet the
 * preview renderer actually paints, because "a uniform border around an interior region" is the same
 * picture whether the border is a scaffold sheet with a card inset on it or a card that bleeds to
 * the artboard edge with text inset on it, and guessing gets the second one exactly backwards.
 */
export function boxFromSamples(pixels, width, height, size, scale) {
  const transparent = hasTransparency(pixels);
  const backdrop = [pixels[0], pixels[1], pixels[2]];
  if (!transparent && !isScaffoldSheet(backdrop)) return wholeImage(size);

  let minX = width;
  let minY = height;
  let maxX = -1;
  let maxY = -1;
  for (let y = 0; y < height; y++) {
    for (let x = 0; x < width; x++) {
      const i = (y * width + x) * 4;
      const drawn = transparent
        ? pixels[i + 3] > 8
        : Math.abs(pixels[i] - backdrop[0]) +
            Math.abs(pixels[i + 1] - backdrop[1]) +
            Math.abs(pixels[i + 2] - backdrop[2]) >
          PLANE_TUNING.BOX_COLOUR_TOLERANCE;
      if (!drawn) continue;
      if (x < minX) minX = x;
      if (x > maxX) maxX = x;
      if (y < minY) minY = y;
      if (y > maxY) maxY = y;
    }
  }
  // A blank capture has no content box; comparing whole-image is the only meaningful answer.
  if (maxX < 0) return wholeImage(size);

  // Widen by one sample cell each way — the downscale can shave a partially-covered edge pixel.
  const inverse = 1 / scale;
  const x0 = Math.max(0, Math.floor((minX - 1) * inverse));
  const y0 = Math.max(0, Math.floor((minY - 1) * inverse));
  const x1 = Math.min(size.width, Math.ceil((maxX + 2) * inverse));
  const y1 = Math.min(size.height, Math.ceil((maxY + 2) * inverse));
  return { x: x0, y: y0, width: Math.max(1, x1 - x0), height: Math.max(1, y1 - y0) };
}

/** The rectangle an image actually draws in, in source pixels. */
export function contentBox(image) {
  const size = { width: image.width, height: image.height };
  const scale = Math.min(1, PLANE_TUNING.BOX_SAMPLE_SIDE / Math.max(size.width, size.height));
  const width = Math.max(1, Math.round(size.width * scale));
  const height = Math.max(1, Math.round(size.height * scale));
  // The one host-dependent step, made portable. At `scale === 1` the resample is the identity, so a
  // preview-sized capture is sampled at full resolution and the kernel cannot matter at all.
  const sampled = scale === 1 ? image : resampleArea(image, width, height);
  return boxFromSamples(sampled.pixels, width, height, size, scale);
}

/** How far apart two content boxes are in shape, 0 (identical proportions) to 100. */
export function aspectDelta(a, b) {
  const ratioA = a.width / a.height;
  const ratioB = b.width / b.height;
  return (Math.abs(ratioA - ratioB) / Math.max(ratioA, ratioB)) * 100;
}

/**
 * The rectangles to actually compare over, plus the measured boxes for reporting.
 *
 * When **either** side's box is too small to be a reliable frame, **both** fall back to the whole
 * canvas — cropping one and not the other would be worse than not cropping. That fallback is what
 * the acceptance's `plane` discriminant records, and why it is a recorded field rather than
 * something re-derived: it depends on the *candidate's* coverage, which the reference's `sha256`
 * does not pin.
 */
export function normalisedBoxes(referenceSize, candidateSize, referenceBox, candidateBox) {
  const coverage = Math.min(
    (referenceBox.width * referenceBox.height) / (referenceSize.width * referenceSize.height),
    (candidateBox.width * candidateBox.height) / (candidateSize.width * candidateSize.height),
  );
  const full = coverage < PLANE_TUNING.MIN_BOX_COVERAGE;
  return {
    reference: full ? wholeImage(referenceSize) : referenceBox,
    candidate: full ? wholeImage(candidateSize) : candidateBox,
    geometry: aspectDelta(referenceBox, candidateBox),
    cropped: !full,
  };
}

/**
 * The canonical plane for one comparison — the discriminant and the resolved box an acceptance
 * records, plus the two boxes the score is taken over.
 *
 * The plane is defined by the **reference**: normally its content box at its own resolution, and the
 * full canvas whenever this pair fell below `MIN_BOX_COVERAGE` (I9). A comparison whose fallback
 * disagrees with an acceptance's recorded one is `plane-changed` rather than silently compared in
 * the wrong space, which is the whole reason the discriminant is on the wire.
 */
export function resolvePlane(reference, candidate) {
  const referenceSize = { width: reference.width, height: reference.height };
  const candidateSize = { width: candidate.width, height: candidate.height };
  const boxes = normalisedBoxes(referenceSize, candidateSize, contentBox(reference), contentBox(candidate));
  return {
    plane: { plane: boxes.cropped ? "content-box" : "full-canvas", box: boxes.reference },
    boxes: { reference: boxes.reference, candidate: boxes.candidate },
    geometry: boxes.geometry,
  };
}

/**
 * The canonical-plane raster of one side: its box, resampled to the plane's dimensions.
 *
 * The reference's box *is* the plane, so this is a crop there and nothing more. The candidate's is a
 * different size, and the two axes scale independently — `boxCanvas` stretches width and height
 * separately and the comparison explicitly supports the two boxes disagreeing about proportion, so a
 * single-ratio resample would land the candidate at the right x and the wrong y.
 */
export function canonicalRaster(image, box, plane) {
  const cropped = { width: box.width, height: box.height, pixels: new Uint8Array(box.width * box.height * 4) };
  for (let y = 0; y < box.height; y++) {
    for (let x = 0; x < box.width; x++) {
      const source = ((box.y + y) * image.width + (box.x + x)) * 4;
      cropped.pixels.set(image.pixels.subarray(source, source + 4), (y * box.width + x) * 4);
    }
  }
  if (cropped.width === plane.box.width && cropped.height === plane.box.height) return cropped;
  return resampleArea(cropped, plane.box.width, plane.box.height);
}

/**
 * Project the published tag index into the canonical plane.
 *
 * **The index publishes `boundsInRoot` in render pixels and says so on the wire**
 * ([D1](parity-batches/00-decisions.md#d1--which-plane-the-element-tag-index-reports-bounds-in)),
 * while an acceptance's `element.bounds` is its authoring-time baseline in *canonical* coordinates.
 * The element gate compares the two directly, so somebody has to convert — and D1 settled who: a
 * plane is a property of a comparison and the index is a property of a render, so the transform is a
 * step of **the comparison**, which is this function.
 *
 * §4 states the mistake this exists to prevent, in as many words: *"an engine that expects canonical
 * bounds from the index either compares raw render coordinates or transforms an already-transformed
 * box, and both report `element-moved` for an element that never moved."* A false invalidation with
 * a plausible explanation attached is worse than a missing check, because nothing surfaces it.
 *
 * The transform, exactly as §4's table gives it: subtract the candidate box's origin, then scale
 * **x and y independently** — `plane.width / candidateBox.width` for x, `plane.height /
 * candidateBox.height` for y. Independently because `boxCanvas` stretches width and height
 * separately and the comparison explicitly *supports* the two content boxes disagreeing about
 * proportion, which is exactly the case an acceptance is most likely to be sitting on; a
 * single-ratio projection would land the box at the right x and the wrong y.
 *
 * Rounding is outward at both ends (D5 answer 5) — the index's own render-pixel box first, then the
 * transformed one — so displacement is measured between two integer boxes. Outward rounding is
 * idempotent on a box that is already integral, so an already-integer index is not inflated.
 *
 * A box that clips to nothing keeps its `count` and loses its `bounds`: the tag still exists in the
 * tree, and the gate's own rule for a resolved node carrying no usable geometry takes over from
 * there. Dropping the entry entirely would say the tag had *vanished*, which is a different verdict.
 */
export function projectTagIndex(tagIndex, candidateBox, plane) {
  const projected = {};
  for (const [tag, entry] of Object.entries(tagIndex ?? {})) {
    if (!entry || typeof entry !== "object") continue;
    const bounds = projectRenderBox(entry.bounds, candidateBox, plane);
    projected[tag] = bounds ? { count: entry.count, bounds } : { count: entry.count };
  }
  return projected;
}

/**
 * One render-pixel box into the canonical plane, or null when it clips to nothing.
 *
 * The plane's coordinates are **plane-local** — a mask is authored at `(0, 0)` whatever the box's
 * origin in the reference raster is — so the clip is against `plane.box`'s *dimensions* rather than
 * against its position.
 */
export function projectRenderBox(box, candidateBox, plane) {
  if (!box || !Number.isFinite(box.x) || !Number.isFinite(box.y)) return null;
  if (!(box.width > 0) || !(box.height > 0)) return null;
  // The index's own box first. A producer publishes integers, so this is normally the identity — but
  // rounding at both ends is what makes the rule true of any producer rather than of ours.
  const source = enclosingBox(box);
  const scaleX = plane.box.width / candidateBox.width;
  const scaleY = plane.box.height / candidateBox.height;
  const transformed = enclosingBox({
    x: (source.x - candidateBox.x) * scaleX,
    y: (source.y - candidateBox.y) * scaleY,
    width: source.width * scaleX,
    height: source.height * scaleY,
  });
  const x0 = Math.max(0, transformed.x);
  const y0 = Math.max(0, transformed.y);
  const x1 = Math.min(plane.box.width, transformed.x + transformed.width);
  const y1 = Math.min(plane.box.height, transformed.y + transformed.height);
  if (x1 <= x0 || y1 <= y0) return null;
  return { x: x0, y: y0, width: x1 - x0, height: y1 - y0 };
}
