/**
 * Publish a design reference on the same stage its sticker was drawn on.
 *
 * A `@Preview` that declares `showBackground = true` is rendered onto an opaque ground — a full
 * frame, or, on a round Wear device, the watch face inscribed in it. Its Figma counterpart is a
 * node export, and a node export carries only the node: the ground is either an overlapping sheet
 * layer Figma is asked to leave out, or it simply isn't in the kit at all, because the kit draws
 * that component as a cell on a specimen board rather than on a watch.
 *
 * So the published pair is a black disc on one side and a small piece of artwork on transparency on
 * the other, and the scorer (`design-reference-score.mjs`, driving the viewer's own
 * `format-compare.js`) *crops both sides to their content box* before comparing. The reference's
 * content box is the artwork; the sticker's is the whole face. The comparison therefore blows a
 * 60x30 clock strip up to 384x384 and reports how badly it matches a watch face — a number about
 * the missing ground, not about the component, and on a dark-first catalog it dominates every
 * full-screen row. wear-m3-catalog measured 0.2–12% opaque coverage on exactly those references
 * against 78.3% (= the inscribed disc) on the ones whose kit cell happens to include a face.
 *
 * Laying the ground under the reference before it is published puts both sides on the same stage:
 * the content boxes agree, the artwork keeps its true size within the frame, and what is left to
 * score is the component.
 *
 * ## The stage is recognised, not guessed
 *
 * The colour is DECLARED by the caller (`--reference-backdrop '#000000'`), because only the catalog
 * knows what its previews asked for and a colour inferred from the sticker's own pixels would be
 * read off the very image the reference is about to be compared against.
 *
 * The SHAPE comes from the sticker's alpha channel, and [stageOf] recognises exactly the two shapes
 * `showBackground` can produce — the whole frame, and the disc inscribed in a square frame — rather
 * than accepting whatever it finds. That distinction is the whole safety argument: a *component*
 * sticker (`showBackground = false`, the transparent kind a designer drops onto their own canvas)
 * has an alpha channel shaped like the component, matches neither, and is left exactly as it is.
 * In wear-m3-catalog that is ~170 of 187 references, so a shape test that merely *guessed* would
 * quietly repaint the majority of the lane.
 *
 * A shape neither test recognises — the Wear scroll capsule's vertical stadium, a partially
 * transparent scrim — is skipped and counted, never approximated. The caller reports the tally, so
 * "the backdrop did nothing here" is visible rather than inferred from unchanged bytes.
 *
 * Pure and dependency-free (plain RGBA buffers, no pngjs import) so it unit-tests without an
 * `npm ci`, like its sibling `png-resample.mjs`.
 */

/** Alpha at or above which a pixel counts as painted ground. */
const OPAQUE = 250;

/** Alpha at or below which a pixel counts as bare canvas. */
const CLEAR = 5;

/**
 * How far either side of the disc's edge is conceded to antialiasing, in pixels. The renderer's own
 * mask is antialiased, so the rim is a band of partial alpha that is neither ground nor canvas;
 * pixels within this distance of the ideal edge are not examined at all.
 */
const EDGE_BAND = 2;

/**
 * Fraction of examined pixels allowed to disagree with the recognised shape. Non-zero because a
 * mask is rasterised, not analytic — but small enough that a component silhouette, which disagrees
 * with both shapes across most of the frame, cannot slip through.
 */
const SHAPE_TOLERANCE = 0.002;

/**
 * Parse a `--reference-backdrop` value into `{ r, g, b }`, or null for "no backdrop".
 *
 * Accepts `#rgb` / `#rrggbb` (with or without the `#`) plus the explicit off-switches `none` and
 * the empty string, so a workflow can wire the input unconditionally and pass `''` for a catalog
 * that wants nothing. Throws on anything else rather than defaulting: a typo'd colour that
 * silently meant "off" would publish a whole catalog's references unchanged and look like the
 * feature simply didn't work.
 */
export function parseBackdrop(spec) {
  const raw = String(spec ?? "").trim();
  if (raw === "" || raw.toLowerCase() === "none") return null;
  const hex = raw.startsWith("#") ? raw.slice(1) : raw;
  const expanded =
    hex.length === 3
      ? hex
          .split("")
          .map((c) => c + c)
          .join("")
      : hex;
  if (!/^[0-9a-fA-F]{6}$/.test(expanded)) {
    throw new Error(
      `reference-backdrop: '${spec}' is not a colour — expected #rrggbb, #rgb, or 'none'`,
    );
  }
  return {
    r: parseInt(expanded.slice(0, 2), 16),
    g: parseInt(expanded.slice(2, 4), 16),
    b: parseInt(expanded.slice(4, 6), 16),
  };
}

/** Whether every pixel of the raster is opaque — the ground a square `showBackground` paints. */
function isFullFrame(data, width, height) {
  for (let i = 3; i < width * height * 4; i += 4) if (data[i] < OPAQUE) return false;
  return true;
}

/**
 * Whether the raster's opaque region is the disc inscribed in a square frame — the ground a round
 * Wear device paints, with the corners left bare.
 *
 * Checked from both sides: inside the disc must be ground, outside it must be bare. Testing only
 * the inside would accept a full frame (already recognised separately, but the ordering should not
 * be load-bearing); testing only the outside would accept an empty canvas.
 */
function isInscribedDisc(data, width, height) {
  if (width !== height) return false;
  const centre = width / 2;
  const radius = width / 2;
  const inner = (radius - EDGE_BAND) ** 2;
  const outer = (radius + EDGE_BAND) ** 2;
  let examined = 0;
  let disagreed = 0;
  for (let y = 0; y < height; y++) {
    const dy = y + 0.5 - centre;
    for (let x = 0; x < width; x++) {
      const dx = x + 0.5 - centre;
      const d2 = dx * dx + dy * dy;
      // The antialiased rim is neither ground nor canvas; skip the band rather than budget for it.
      if (d2 > inner && d2 < outer) continue;
      const alpha = data[(y * width + x) * 4 + 3];
      examined++;
      if (d2 <= inner ? alpha < OPAQUE : alpha > CLEAR) disagreed++;
    }
  }
  return examined > 0 && disagreed / examined <= SHAPE_TOLERANCE;
}

/**
 * The ground [data] (RGBA, `width` x `height`) was drawn on — `{ kind, coverage }`, where `kind` is
 * `"frame"` for a fully opaque raster and `"disc"` for the inscribed watch face — or null when the
 * raster is neither, which is the answer for every transparent component sticker.
 *
 * `coverage` is the sticker's own alpha plane, and it is what [applyBackdrop] paints through rather
 * than re-deriving the shape analytically. Once the shape has been *recognised*, the sticker's mask
 * is a strictly better stencil than the ideal it matched: it carries the renderer's antialiasing, so
 * a reference laid on it gets a rim identical to the one it will be compared against. Painting a
 * hard-edged ideal disc instead cost every already-correct reference 0.02–0.04 points of match on
 * wear-m3-catalog — small, but a change whose whole purpose is to make the number honest should not
 * move it at all where there was nothing wrong.
 */
export function stageOf(data, width, height) {
  if (width <= 0 || height <= 0) return null;
  const kind = isFullFrame(data, width, height)
    ? "frame"
    : isInscribedDisc(data, width, height)
      ? "disc"
      : null;
  if (!kind) return null;
  const coverage = new Uint8Array(width * height);
  for (let p = 0; p < coverage.length; p++) coverage[p] = data[p * 4 + 3];
  return { kind, coverage };
}

/**
 * Fill [data] up to [stage]'s coverage with [colour], returning a new buffer.
 *
 * The ground contributes exactly the coverage the reference lacks — `max(0, ground - reference)` —
 * so the result's alpha is `max(reference, ground)`. Deliberately NOT source-over on alpha, and the
 * difference is not academic: the two sides describe the *same* silhouette, so `over` stacks a
 * coincident edge against itself. A reference that already carries its own watch face has a rim
 * alpha of 47 where the sticker's is 27, and `over` promotes that to 69 — a harder edge than either
 * image has, on the ~1300 rim pixels of every such reference, for no reason. Filling only what is
 * missing leaves those bytes untouched, which is what a catalog whose kit cells already draw the
 * ground should get: nothing.
 *
 * Where the ground does contribute, the colour is mixed under the reference's own pixel by coverage,
 * so an antialiased glyph edge keeps its softness against the ground rather than being replaced by
 * it. Outside the mask the raster is returned as it was, corners included: on a round face those are
 * bare on the sticker too, and filling them would trade the missing ground for a pair of mismatched
 * corners.
 */
export function applyBackdrop(data, width, height, stage, colour) {
  const out = Buffer.from(data);
  const { coverage } = stage;
  for (let p = 0; p < width * height; p++) {
    const i = p * 4;
    const source = data[i + 3];
    const added = coverage[p] - source;
    if (added <= 0) continue;
    const alpha = source + added;
    const blend = (channel, ink) => Math.round((channel * source + ink * added) / alpha);
    out[i] = blend(data[i], colour.r);
    out[i + 1] = blend(data[i + 1], colour.g);
    out[i + 2] = blend(data[i + 2], colour.b);
    out[i + 3] = alpha;
  }
  return out;
}
