/**
 * The separated-plane scoring path — batch 05's half of
 * `compose-preview-known-differences/v1`.
 *
 * [`known-differences.mjs`](./known-differences.mjs) decides every *verdict*: the refusals, the five
 * gates, the status precedence. It deliberately computes no numbers, because
 * [§4](../../docs/design/COMPONENT_PARITY_WORKFLOW.md#the-normative-contract) settles the gates
 * first (I1: every gate resolves before any score is computed) and left the score — and only the
 * score — to this batch. This file is that score: given the surviving union of masks, it reports
 * `raw`, `accepted` and `unaccepted` over one comparison.
 *
 * **The raw finding is never destroyed.** An acceptance does not remove a difference from the
 * report; it moves that difference into `accepted` while `unaccepted` keeps everything else
 * visible, and `raw` goes on measuring the pair as though nothing had been accepted at all. That is
 * the epic requirement the whole model exists for, and it is why `raw` is computed on its own
 * stages rather than reconstructed from the other two.
 *
 * ## What is *not* negotiable here
 *
 * Four invariants from §4 decide the shape of everything below, and each of them rules out a
 * cheaper implementation that looks equivalent:
 *
 * - **I3/I4 — separation precedes every resample, on both inputs.** The masked and unmasked regions
 *   of the reference *and* the candidate are split in their own pixel space first, and each region
 *   is resampled on its own. A single pre-averaged composite would let an accepted difference on one
 *   side of a straddling footprint cancel an opposite unaccepted regression on the other before
 *   scoring ever saw the pixel — the two are opposite in sign and average to the reference.
 * - **I5 — the union is the union of `valid` survivors.** `resolved`, `invalidated` and `refused`
 *   acceptances suppress nothing. Keeping a `resolved` mask would remove its pixels as neighbourhood
 *   candidates for the pixels *around* it, which hides a regression sitting next to the thing that
 *   was just fixed.
 * - **I6 — raw and unaccepted traverse identical stages.** Filtering is not associative, so a
 *   shortcut path for raw makes `raw ≠ unaccepted` even with an empty union, manufacturing a delta
 *   out of nothing. With no surviving mask this file's `unaccepted` is `raw`, bit for bit, and a
 *   fixture pins it.
 * - **I10 — one resample, source → score plane, at the candidate box's dimensions.** The canonical
 *   plane is for the *gates*: it is where a glyph is still a glyph and where the mask and the
 *   accepted candidate are stored. The score draws each region straight from the source image into
 *   the score plane, exactly as `scoreImages` does today, so enabling acceptance never moves a
 *   number by itself.
 *
 * ## The number moves once, deliberately
 *
 * The kernel here is the portable area average, not the host's `drawImage`, so the published parity
 * numbers shift the moment the browser adopts this path — see
 * [D3](parity-batches/00-decisions.md#d3--the-score-rebaseline-is-versioned-and-when). What I10
 * buys is that they do not move a *second* time: the geometry stays one resample from source to
 * score plane at the candidate box's dimensions. The rebaseline is a versioned change of its own,
 * carrying regenerated baselines and a release note and touching no acceptance semantics.
 */

import { SCORE_TUNING } from "./known-difference-tuning.mjs";

const {
  MAX_SIDE,
  EDGE_SEARCH_RADIUS,
  EDGE_POSITION_COST,
  EDGE_GRADIENT_THRESHOLD,
  LUMA_TOLERANCE,
  FULL_DIFFERENCE_DELTA,
  CONTENT_DILATION,
  COMPARISON_GROUNDS,
  GROUND_PLANE_TOLERANCE,
} = SCORE_TUNING;

/**
 * The three regions a comparison is scored over.
 *
 * Named rather than positional because every stage below is pinned per region by the conformance
 * fixtures, and a runner in another language has to be able to name the one that disagreed.
 */
export const REGIONS = ["whole", "accepted", "unaccepted"];

// ---------------------------------------------------------------------------------------------
// Geometry: which source pixels belong to which region
// ---------------------------------------------------------------------------------------------

/**
 * The union of the surviving masks, as one binary coverage plane in the canonical plane.
 *
 * A set union rather than a sum: two acceptances whose masks overlap suppress the overlap once, and
 * an implementation that counted coverage would double-charge the seam — invisible with a single
 * acceptance, which is why the fixtures carry an overlapping pair.
 *
 * `masks` are decoded canonical-plane rasters (RGBA, as {@link decodePng} hands them over); the
 * contract fixes the encoding at 8-bit greyscale with `255` meaning masked, so the red channel
 * carries the whole answer and anything that is not `255` is unmasked.
 */
export function unionCoverage(masks, width, height) {
  const coverage = new Uint8Array(width * height);
  for (const mask of masks) {
    if (mask.width !== width || mask.height !== height) {
      throw new Error(`mask ${mask.width}×${mask.height} is not the canonical plane ${width}×${height}`);
    }
    for (let i = 0; i < coverage.length; i++) {
      if (mask.pixels[i * 4] === 255) coverage[i] = 1;
    }
  }
  return coverage;
}

/**
 * Project a canonical-plane coverage plane onto one side's own source box.
 *
 * The reference's box *is* the canonical plane (post-gate), so this is the identity there and the
 * arithmetic below collapses to `cx === bx`. The candidate's box is a different size, and the two
 * axes scale independently — `boxCanvas` stretches width and height separately, and the comparison
 * explicitly supports the two content boxes disagreeing about proportion, so a single-ratio
 * projection would put a mask at the right x and the wrong y.
 *
 * **Overlap, not a rounded centre**, and computed in exact integers by the same scaling trick
 * {@link resampleArea} uses: a source pixel belongs to the region when its footprint overlaps *any*
 * covered canonical pixel with positive area. That is D5 answer 5 — outward rounding — applied to a
 * region rather than to a box: a projection that rounded to the nearest covered pixel would be
 * smaller than the region the author looked at, which is the direction that silently stops covering
 * pixels.
 */
export function projectCoverage(coverage, planeWidth, planeHeight, boxWidth, boxHeight) {
  const out = new Uint8Array(boxWidth * boxHeight);
  for (let by = 0; by < boxHeight; by++) {
    const y0 = by * planeHeight;
    const y1 = (by + 1) * planeHeight;
    for (let bx = 0; bx < boxWidth; bx++) {
      const x0 = bx * planeWidth;
      const x1 = (bx + 1) * planeWidth;
      let covered = false;
      for (let cy = Math.floor(y0 / boxHeight); cy < planeHeight && !covered; cy++) {
        if (Math.min(y1, (cy + 1) * boxHeight) - Math.max(y0, cy * boxHeight) <= 0) break;
        for (let cx = Math.floor(x0 / boxWidth); cx < planeWidth; cx++) {
          if (Math.min(x1, (cx + 1) * boxWidth) - Math.max(x0, cx * boxWidth) <= 0) break;
          if (coverage[cy * planeWidth + cx]) {
            covered = true;
            break;
          }
        }
      }
      out[by * boxWidth + bx] = covered ? 1 : 0;
    }
  }
  return out;
}

/** The score plane's dimensions: the **candidate** box, scaled to {@link MAX_SIDE}. */
export function scorePlaneSize(candidateBox) {
  const scale = Math.min(1, MAX_SIDE / Math.max(candidateBox.width, candidateBox.height));
  return {
    scale,
    width: Math.max(1, Math.round(candidateBox.width * scale)),
    height: Math.max(1, Math.round(candidateBox.height * scale)),
  };
}

// ---------------------------------------------------------------------------------------------
// The separated resample
// ---------------------------------------------------------------------------------------------

/**
 * One region of one side, resampled from the **source image** straight into the score plane.
 *
 * The area average of {@link resampleAreaPremultiplied}, restricted to the region's own pixels: a
 * destination
 * pixel averages only the source pixels that are in the region, and a destination pixel whose
 * footprint contains none of them is **absent** rather than filled. Absence is not a value — the
 * whole point of separating is that nothing invented is allowed to reach the scorer, because a
 * filler colour manufactures or suppresses an edge at the boundary and that decides whether a
 * neighbouring pixel gets the displaced search at all.
 *
 * A destination pixel whose footprint straddles the region boundary is therefore present in **both**
 * regions, each carrying only its own contributions. That is the straddling-footprint answer §4
 * calls for: neither dropping the pixel (which hides the boundary ring) nor keeping it whole (which
 * lets accepted pixels bleed into the score they were meant to leave alone).
 *
 * The output's RGB is **premultiplied** (`mean(a·c)`) while its alpha is the ordinary `mean(a)`, so
 * {@link lumaPlane} composites by adding the ground's share rather than by weighting again. See
 * {@link resampleAreaPremultiplied} for why the score plane premultiplies where the gate plane does
 * not.
 *
 * @param source the full source raster, `{ width, height, pixels }` in 8-bit RGBA.
 * @param box the normalised content box, in `source`'s own pixels.
 * @param member the region, one byte per box pixel, from {@link projectCoverage} (or `null` for the
 *   whole box — the `raw` stage, which is separated against nothing).
 */
export function resampleRegion(source, box, member, targetWidth, targetHeight) {
  const pixels = new Uint8Array(targetWidth * targetHeight * 4);
  const present = new Uint8Array(targetWidth * targetHeight);
  for (let ty = 0; ty < targetHeight; ty++) {
    const y0 = ty * box.height;
    const y1 = (ty + 1) * box.height;
    for (let tx = 0; tx < targetWidth; tx++) {
      const x0 = tx * box.width;
      const x1 = (tx + 1) * box.width;
      let r = 0;
      let g = 0;
      let b = 0;
      let a = 0;
      let area = 0;
      for (let sy = Math.floor(y0 / targetHeight); sy < box.height; sy++) {
        const coverY = Math.min(y1, (sy + 1) * targetHeight) - Math.max(y0, sy * targetHeight);
        if (coverY <= 0) break;
        for (let sx = Math.floor(x0 / targetWidth); sx < box.width; sx++) {
          const coverX = Math.min(x1, (sx + 1) * targetWidth) - Math.max(x0, sx * targetWidth);
          if (coverX <= 0) break;
          if (member && !member[sy * box.width + sx]) continue;
          const px = box.x + sx;
          const py = box.y + sy;
          // A box is measured inside its own raster, so this never fires on a well-formed
          // comparison — but a caller passing a box that reaches past the image would otherwise
          // read another row's pixels through the stride and score them as though they were there.
          if (px < 0 || py < 0 || px >= source.width || py >= source.height) continue;
          const weight = coverX * coverY;
          const i = (py * source.width + px) * 4;
          const alpha = source.pixels[i + 3];
          // Premultiplied, like `resampleAreaPremultiplied`, and for the same reason: averaging
          // straight colour and compositing afterwards do not commute, so two encodings of one
          // half-covered edge scored differently. The extra factor of 255 rides in the denominator
          // so the premultiply stays exact integer arithmetic and rounds once.
          r += source.pixels[i] * alpha * weight;
          g += source.pixels[i + 1] * alpha * weight;
          b += source.pixels[i + 2] * alpha * weight;
          a += alpha * weight;
          area += weight;
        }
      }
      if (area === 0) continue;
      const d = (ty * targetWidth + tx) * 4;
      pixels[d] = roundHalfUp(r, area * 255);
      pixels[d + 1] = roundHalfUp(g, area * 255);
      pixels[d + 2] = roundHalfUp(b, area * 255);
      pixels[d + 3] = roundHalfUp(a, area);
      present[ty * targetWidth + tx] = 1;
    }
  }
  return { width: targetWidth, height: targetHeight, pixels, present };
}

/**
 * `round(numerator / denominator)` with halves going up, computed without forming the quotient.
 *
 * The same rule and the same reason as {@link resampleArea}'s: rounding per contribution, or
 * through a double, is where two implementations drift. Duplicated rather than imported so this
 * file can be read as one pipeline; the fixtures pin both against the same expectations.
 */
function roundHalfUp(numerator, denominator) {
  const value = Math.floor((2 * numerator + denominator) / (2 * denominator));
  return Math.max(0, Math.min(255, value));
}

/**
 * A resampled region composited onto one ground, as a luminance plane.
 *
 * The ground is a parameter rather than a constant for the reason `COMPARISON_GROUNDS` exists: one
 * opaque ground *annihilates* ink that matches it, and the metric reads two blank planes as a
 * perfect match. Both sides of a comparison are always handed the same one.
 *
 * `region`'s colour arrives **premultiplied** from {@link resampleRegion}, so compositing is
 * `mean(a·c) + g·(1 − mean(a))` — the ground's share added, not the colour weighted a second time.
 *
 * That ordering is the correction to D5 answer 1, which originally composited straight samples after
 * the resample. Those two steps do not commute for alpha-bearing artwork: the same half-covered edge
 * scored 128 encoded as one pixel at alpha 128 and 64 encoded as an opaque pixel beside a
 * transparent one, so two visually identical exports at different resolutions read as a mismatch.
 * Compositing per ground before the average gives the same expression as premultiplying before it;
 * this is the second, which keeps one raster per region rather than one per region per ground.
 */
export function lumaPlane(region, ground) {
  const plane = new Float64Array(region.width * region.height);
  for (let i = 0; i < plane.length; i++) {
    if (!region.present[i]) continue;
    const rest = 1 - region.pixels[i * 4 + 3] / 255;
    const r = region.pixels[i * 4] + ground[0] * rest;
    const g = region.pixels[i * 4 + 1] + ground[1] * rest;
    const b = region.pixels[i * 4 + 2] + ground[2] * rest;
    plane[i] = 0.299 * r + 0.587 * g + 0.114 * b;
  }
  return plane;
}

// ---------------------------------------------------------------------------------------------
// The metric, region-aware
// ---------------------------------------------------------------------------------------------

/**
 * Which present pixels sit on an edge — the 4-neighbour maximum absolute gradient.
 *
 * **Classification runs within the region, and an absent neighbour contributes no gradient term**
 * (D5 answer 2). Excluding masked coordinates as sources and as search candidates is not sufficient
 * on its own: the classifier reads raw neighbour values, so whatever fills a separated region can
 * manufacture or suppress an edge at the boundary, and that decides whether a neighbouring pixel
 * gets the displaced search at all. A present pixel with no present neighbours is not an edge —
 * there is no gradient to take.
 *
 * The browser's whole-plane version clamps at the canvas border, which compares a pixel against
 * itself and contributes a zero term; a maximum over fewer non-negative terms is the same answer, so
 * the two agree on an unseparated plane.
 */
export function edgeMask(plane, present, width, height) {
  const mask = new Uint8Array(width * height);
  for (let y = 0; y < height; y++) {
    for (let x = 0; x < width; x++) {
      const index = y * width + x;
      if (!present[index]) continue;
      const value = plane[index];
      let gradient = -1;
      for (const [nx, ny] of [
        [x - 1, y],
        [x + 1, y],
        [x, y - 1],
        [x, y + 1],
      ]) {
        if (nx < 0 || ny < 0 || nx >= width || ny >= height) continue;
        const neighbour = ny * width + nx;
        if (!present[neighbour]) continue;
        gradient = Math.max(gradient, Math.abs(value - plane[neighbour]));
      }
      if (gradient >= EDGE_GRADIENT_THRESHOLD) mask[index] = 1;
    }
  }
  return mask;
}

/**
 * Where the two frames have something to say — the union of their detail, widened by
 * {@link CONTENT_DILATION}, and restricted to present coordinates.
 *
 * The restriction is the second half of D5 answer 2. Without it a masked coordinate re-enters the
 * denominator through the dilation of an edge beside it, so a mask lowers the score by existing —
 * which is the failure the denominator rule exists to prevent, reached through the back door.
 */
export function contentMask(referenceEdges, candidateEdges, present, width, height) {
  const mask = new Uint8Array(width * height);
  for (let y = 0; y < height; y++) {
    for (let x = 0; x < width; x++) {
      const index = y * width + x;
      if (!referenceEdges[index] && !candidateEdges[index]) continue;
      for (let oy = -CONTENT_DILATION; oy <= CONTENT_DILATION; oy++) {
        const ny = y + oy;
        if (ny < 0 || ny >= height) continue;
        for (let ox = -CONTENT_DILATION; ox <= CONTENT_DILATION; ox++) {
          const nx = x + ox;
          if (nx < 0 || nx >= width) continue;
          const neighbour = ny * width + nx;
          if (!present[neighbour]) continue;
          mask[neighbour] = 1;
        }
      }
    }
  }
  return mask;
}

/** What one pixel's luminance gap costs, 0–1: free within tolerance, full price from the delta up. */
export function pixelCost(delta) {
  const span = FULL_DIFFERENCE_DELTA - LUMA_TOLERANCE;
  return Math.min(1, Math.max(0, delta - LUMA_TOLERANCE) / span);
}

/**
 * One direction of the search: what each present pixel of `source` costs against `target`.
 *
 * **A coordinate outside the region is excluded in both roles.** Skipping absent pixels as scored
 * sources is the obvious half and is not sufficient: each directed pass searches a
 * ±{@link EDGE_SEARCH_RADIUS} neighbourhood of the *target* plane for a best match, so a present
 * pixel just outside the union could otherwise find its match at an accepted-but-different
 * coordinate inside it — and a regression within five score-plane pixels of an accepted region would
 * score as clean. A source pixel whose entire neighbourhood is absent contributes its
 * same-coordinate cost rather than a best-of-nothing default; if the coordinate itself is absent on
 * the target side there is nothing to compare and the pixel contributes nothing.
 */
export function directedCosts(source, target, sourceEdges, targetEdges, present, width, height) {
  const costs = new Float64Array(width * height);
  for (let y = 0; y < height; y++) {
    for (let x = 0; x < width; x++) {
      const index = y * width + x;
      if (!present[index]) continue;
      const value = source[index];
      let best = Math.abs(value - target[index]);
      if (sourceEdges[index] && best > LUMA_TOLERANCE) {
        for (let oy = -EDGE_SEARCH_RADIUS; oy <= EDGE_SEARCH_RADIUS; oy++) {
          const yy = y + oy;
          if (yy < 0 || yy >= height) continue;
          for (let ox = -EDGE_SEARCH_RADIUS; ox <= EDGE_SEARCH_RADIUS; ox++) {
            const xx = x + ox;
            if (xx < 0 || xx >= width) continue;
            const targetIndex = yy * width + xx;
            if (!present[targetIndex]) continue;
            if (!targetEdges[targetIndex]) continue;
            const displaced =
              Math.abs(value - target[targetIndex]) + Math.sqrt(ox * ox + oy * oy) * EDGE_POSITION_COST;
            best = Math.min(best, displaced);
          }
        }
      }
      costs[index] = pixelCost(best);
    }
  }
  return costs;
}

/**
 * The structural match of two luminance planes over one region, 0–100.
 *
 * The denominator is **the scorer's own, restricted to present coordinates** (D5 answer 3): the
 * pixels either frame drew on or disagrees about, minus everything outside the region. Not the full
 * plane — a mask would otherwise lower the score simply by existing. A region that measures nothing
 * returns `100`, which is what `scorePlanes` already answers for two blank planes; reusing that
 * answer is what stops two engines picking two conventions for the all-masked case.
 */
export function scoreRegion(reference, candidate, present, width, height) {
  const referenceEdges = edgeMask(reference, present, width, height);
  const candidateEdges = edgeMask(candidate, present, width, height);
  const forwards = directedCosts(reference, candidate, referenceEdges, candidateEdges, present, width, height);
  const backwards = directedCosts(candidate, reference, candidateEdges, referenceEdges, present, width, height);
  const content = contentMask(referenceEdges, candidateEdges, present, width, height);
  let cost = 0;
  let measured = 0;
  for (let index = 0; index < forwards.length; index++) {
    if (!present[index]) continue;
    const pixel = (forwards[index] + backwards[index]) / 2;
    cost += pixel;
    if (content[index] || pixel > 0) measured++;
  }
  if (measured === 0) return 100;
  return Math.max(0, Math.min(100, (1 - cost / measured) * 100));
}

// ---------------------------------------------------------------------------------------------
// The comparison
// ---------------------------------------------------------------------------------------------

/**
 * Score one comparison against the surviving union, reporting `raw`, `accepted` and `unaccepted`.
 *
 * @param reference the full **source** reference raster — not the canonical plane. I10: the score
 *   draws from the source, once, at the candidate box's dimensions.
 * @param candidate the full source candidate raster.
 * @param referenceBox the normalised reference content box, in reference pixels.
 * @param candidateBox the normalised candidate content box, in candidate pixels. Its dimensions
 *   decide the score plane's.
 * @param plane the acceptance's **recorded** canonical plane, `{ plane, box }` — the space the masks
 *   are authored in (I9).
 * @param masks the decoded canonical-plane masks of the acceptances whose status is `valid`, and of
 *   no others (I5). An empty list means nothing is suppressed and `unaccepted` is `raw`.
 * @returns `{ raw, accepted, unaccepted, stages }`. The three numbers share a scale, a polarity and
 *   a set of stages, and are **not** comparable by subtraction — `accepted` is the accepted region's
 *   own regional match, not the difference between the other two (D5 answer 4). A reader wanting
 *   "what did acceptance buy" reads `unaccepted` against `raw`, which is a signed effect and can
 *   legitimately go either way.
 */
export function scoreComparison({ reference, candidate, referenceBox, candidateBox, plane, masks = [] }) {
  const planeWidth = plane.box.width;
  const planeHeight = plane.box.height;
  const coverage = unionCoverage(masks, planeWidth, planeHeight);
  const { width, height } = scorePlaneSize(candidateBox);

  const sides = [
    { name: "reference", source: reference, box: referenceBox },
    { name: "candidate", source: candidate, box: candidateBox },
  ];

  // **Separation first, then one resample per region** (I3/I4/I10). Every region of every side is
  // built the same way from the same source, so `whole` and `unaccepted` differ by their membership
  // and by nothing else — which is what I6 asks for and what a shortcut path would break.
  const stages = { plane: { width, height }, regions: {} };
  for (const region of REGIONS) {
    stages.regions[region] = {};
    for (const side of sides) {
      const projected =
        region === "whole" ? null : projectCoverage(coverage, planeWidth, planeHeight, side.box.width, side.box.height);
      let member = projected;
      if (region === "unaccepted" && projected) {
        member = new Uint8Array(projected.length);
        for (let i = 0; i < projected.length; i++) member[i] = projected[i] ? 0 : 1;
      }
      stages.regions[region][side.name] = resampleRegion(side.source, side.box, member, width, height);
    }
  }

  // **The grounds are decided once, from the whole planes, and every region uses that decision.** A
  // second ground only means something where there is alpha for it to show through, and deciding
  // per region would let `raw` and `unaccepted` be measured on different ground sets — two numbers
  // on two scales, which is exactly the comparability I6 exists to protect.
  const grounds = groundsWorthScoring(
    COMPARISON_GROUNDS.map((ground) => ({
      reference: lumaPlane(stages.regions.whole.reference, ground),
      candidate: lumaPlane(stages.regions.whole.candidate, ground),
      ground,
    })),
  ).map((entry) => entry.ground);
  stages.grounds = grounds;

  const scores = {};
  for (const region of REGIONS) {
    const planes = stages.regions[region];
    // Present on **both** sides: a coordinate one side did not draw into has nothing to compare
    // against, and scoring it against an absent value is the invented pixel separation exists to
    // avoid. The two agree everywhere except at a straddling footprint on one side only.
    const present = new Uint8Array(width * height);
    for (let i = 0; i < present.length; i++) {
      present[i] = planes.reference.present[i] && planes.candidate.present[i] ? 1 : 0;
    }
    let worst = 100;
    for (const ground of grounds) {
      worst = Math.min(
        worst,
        scoreRegion(lumaPlane(planes.reference, ground), lumaPlane(planes.candidate, ground), present, width, height),
      );
    }
    scores[region] = worst;
  }

  return {
    raw: scores.whole,
    accepted: scores.accepted,
    unaccepted: scores.unaccepted,
    stages,
  };
}

/**
 * Which of the rasterised grounds deserve a score: all of them, or only the first.
 *
 * An opaque image composites identically onto every ground, so its planes come back equal — which is
 * also how this detects opacity, for free. The case it guards is a *mixed* pair: an opaque reference
 * against a render with a transparent surround, where nothing about the reference moves between
 * grounds while all of the render's surround does, so a second ground would report a difference that
 * is in the grounds rather than in the artwork.
 */
export function groundsWorthScoring(planes) {
  const varies = (side) => planes.some((entry) => !samePlane(entry[side], planes[0][side]));
  return varies("reference") && varies("candidate") ? planes : [planes[0]];
}

/**
 * Whether two luminance planes are the same picture.
 *
 * The tolerance is for a nearly-opaque pixel: alpha 254 lets a sliver of ground through and moves a
 * luminance by well under one unit, which is not the alpha this is looking for.
 */
function samePlane(a, b) {
  if (a.length !== b.length) return false;
  for (let i = 0; i < a.length; i++) {
    if (Math.abs(a[i] - b[i]) > GROUND_PLANE_TOLERANCE) return false;
  }
  return true;
}
