/**
 * The portable resampler, on its own, so a consumer can take the kernel without the contract.
 *
 * It was extracted out of [`known-differences.mjs`](./known-differences.mjs) — which still
 * re-exports it, so nothing downstream moved — for one reason: the browser's live scorer now
 * measures through this kernel rather than through `drawImage`
 * ([D3](../../docs/design/parity-batches/00-decisions.md#d3--the-score-rebaseline-is-versioned-and-when)),
 * and `format-compare.js` is loaded by four consumers that have nothing to do with acceptances.
 * Importing the resampler from the module that also carries the document ladder, the five gates and
 * the whole status precedence would have put all of that on the compare wall to get at forty lines
 * of arithmetic.
 *
 * Nothing here knows what a comparison is. Rasters in, raster out.
 */

/** A decoded RGBA raster: `pixels` is `width * height * 4` non-premultiplied bytes. */

/**
 * The named resampler: an **area average over exact source footprints**, per channel, on
 * non-premultiplied 8-bit RGBA, rounded half-up and clamped.
 *
 * Chosen over anything host-provided because `drawImage`'s filter is not reproducible off-browser
 * and its smoothing quality is implementation-dependent — the same unchanged candidate bytes would
 * otherwise produce different canonical pixels in the two engines and falsely invalidate as
 * `candidate-changed`. An area average needs no kernel radius, no edge-extension rule (a footprint
 * is clipped to the source rectangle and never samples outside it), and reduces to an exact box
 * filter at integer ratios and to nearest-neighbour when upscaling by an integer — so the three
 * cases an implementation is most likely to special-case are all the same arithmetic here.
 *
 * **Not premultiplied**, deliberately: premultiplying and un-premultiplying introduces a rounding
 * step each way that two engines would have to agree on for no benefit, and this contract's
 * artifacts are opaque by construction (a mask is greyscale with no alpha; an accepted candidate is
 * a crop of an already-composited render). Alpha is averaged as an ordinary fourth channel.
 *
 * Accumulate in double precision and round **half-up** (`Math.floor(v + 0.5)`) exactly once, at the
 * end — rounding per contribution is where two implementations drift.
 */
export function resampleArea(source, targetWidth, targetHeight) {
  const { width, height, pixels } = source;
  const out = new Uint8Array(targetWidth * targetHeight * 4);
  // **Exact integer arithmetic, not floating footprints.** Scaling every coordinate by the target
  // dimension turns each overlap into a difference of integers: destination `tx` covers source
  // units `[tx·W, (tx+1)·W]` once both sides are multiplied by `T`, and a source column `sx` covers
  // `[sx·T, (sx+1)·T]`. Their overlap is then exact, the weights are exact, and the average is a
  // ratio of two integers rounded once.
  //
  // The floating version was subtly wrong at exactly the boundary this contract cares most about:
  // resizing 108 columns to 87 puts destination 84 on a true `201.5`, which double arithmetic
  // computes as `201.4999999999998` and rounds *down* — half-up in the specification and half-down
  // in the implementation, from one unlucky ratio. A one-channel error is enough to move a
  // tolerance-boundary gate verdict, so the kernel that exists to make two engines agree cannot be
  // the thing that disagrees. The magnitudes stay far inside the safe-integer range: the scaled area
  // of one destination pixel is `W × H`, at most 8192² here, and the numerator at most 255 times
  // that.
  for (let ty = 0; ty < targetHeight; ty++) {
    const y0 = ty * height;
    const y1 = (ty + 1) * height;
    for (let tx = 0; tx < targetWidth; tx++) {
      const x0 = tx * width;
      const x1 = (tx + 1) * width;
      let r = 0;
      let g = 0;
      let b = 0;
      let a = 0;
      let area = 0;
      for (let sy = Math.floor(y0 / targetHeight); sy < height; sy++) {
        const coverY = Math.min(y1, (sy + 1) * targetHeight) - Math.max(y0, sy * targetHeight);
        if (coverY <= 0) break;
        for (let sx = Math.floor(x0 / targetWidth); sx < width; sx++) {
          const coverX = Math.min(x1, (sx + 1) * targetWidth) - Math.max(x0, sx * targetWidth);
          if (coverX <= 0) break;
          const weight = coverX * coverY;
          const i = (sy * width + sx) * 4;
          r += pixels[i] * weight;
          g += pixels[i + 1] * weight;
          b += pixels[i + 2] * weight;
          a += pixels[i + 3] * weight;
          area += weight;
        }
      }
      const d = (ty * targetWidth + tx) * 4;
      if (area === 0) continue;
      out[d] = roundHalfUp(r, area);
      out[d + 1] = roundHalfUp(g, area);
      out[d + 2] = roundHalfUp(b, area);
      out[d + 3] = roundHalfUp(a, area);
    }
  }
  return { width: targetWidth, height: targetHeight, pixels: out };
}

/**
 * `round(numerator / denominator)` with halves going up, computed without ever forming the quotient.
 *
 * Both arguments are non-negative integers, so this is `floor((2n + d) / 2d)` — exact wherever the
 * inputs are, which is the whole point of the integer footprints above. Clamped for the same reason
 * the float version was: a channel is a byte.
 */
function roundHalfUp(numerator, denominator) {
  const value = Math.floor((2 * numerator + denominator) / (2 * denominator));
  return Math.max(0, Math.min(255, value));
}


/**
 * One side's box, resampled to a target size: the crop-then-resample every plane in this contract
 * is built by, and now the browser's score plane too.
 *
 * The two axes scale independently, deliberately. Width and height are stretched separately because
 * the comparison explicitly supports the two content boxes disagreeing about proportion — a
 * single-ratio resample would land the candidate at the right x and the wrong y — and the
 * proportion difference is reported beside the score rather than folded into it.
 *
 * A box that already matches the target is copied and not resampled: at that size the kernel is the
 * identity, and skipping it says so in one place rather than trusting every caller to notice.
 */
export function cropTo(image, box, width, height) {
  const cropped = {
    width: box.width,
    height: box.height,
    pixels: new Uint8Array(box.width * box.height * 4),
  };
  for (let y = 0; y < box.height; y++) {
    for (let x = 0; x < box.width; x++) {
      const source = ((box.y + y) * image.width + (box.x + x)) * 4;
      cropped.pixels.set(image.pixels.subarray(source, source + 4), (y * box.width + x) * 4);
    }
  }
  if (cropped.width === width && cropped.height === height) return cropped;
  return resampleArea(cropped, width, height);
}
