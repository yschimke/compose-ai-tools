/**
 * Regenerate `fixtures/known-differences/` — the cross-runtime conformance suite for
 * `compose-preview-known-differences/v1`.
 *
 * The fixtures are the deliverable of batch 04, not a follow-up: they are the only thing that keeps
 * three runners (this repo's JS suite, `design-parity`'s own, and the server projector's Kotlin
 * tests) honest about one definition. They are committed, so this script exists to make them
 * *reproducible* rather than hand-placed bytes — run it and the tree comes out byte-identical, which
 * is what lets a reviewer check a fixture by reading its recipe instead of a hex dump.
 *
 * **Expected values are declared by hand, never harvested from the implementation.** Writing
 * `expected.json` from a run of `known-differences.mjs` would make every fixture agree with whatever
 * that file happens to do, bugs included — the suite would then pin the implementation instead of
 * the contract. Each case below therefore states its verdict as data, and
 * `known-differences.test.mjs` is what discovers whether the implementation agrees.
 *
 *     node build-known-difference-fixtures.mjs
 */

import { mkdirSync, rmSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

import {
  COLOUR_GREY,
  COLOUR_GREY_ALPHA,
  COLOUR_PALETTE,
  COLOUR_RGB,
  COLOUR_RGBA,
  buildPng,
  chunk,
  encodePng,
  idat,
  filteredIdat,
  ihdr,
  padPngTo,
  sha256Hex,
} from "./png-lite.mjs";

// Overridable so the conformance suite can regenerate into a scratch directory and prove the
// committed tree still matches its recipe. "Generated" is only true while something enforces it.
const ROOT =
  process.env.KNOWN_DIFFERENCE_FIXTURE_ROOT ??
  join(dirname(fileURLToPath(import.meta.url)), "fixtures", "known-differences");

// --------------------------------------------------------------------------------------------
// Raster helpers. Every fixture raster is a few dozen pixels — big enough to carry a mask edge, a
// distinguished element and a neighbouring regression, small enough to read as a table.
// --------------------------------------------------------------------------------------------

const WHITE = [255, 255, 255, 255];
const BLACK = [0, 0, 0, 255];
const RED = [200, 60, 60, 255];
const GREEN = [60, 200, 60, 255];
const GREY = [128, 128, 128, 255];

function raster(width, height, fill = WHITE) {
  const pixels = new Uint8Array(width * height * 4);
  for (let i = 0; i < width * height; i++) pixels.set(fill, i * 4);
  return { width, height, pixels };
}

function fillRect(image, { x, y, width, height }, colour) {
  for (let py = y; py < y + height; py++) {
    for (let px = x; px < x + width; px++) {
      if (px < 0 || py < 0 || px >= image.width || py >= image.height) continue;
      image.pixels.set(colour, (py * image.width + px) * 4);
    }
  }
  return image;
}

function rgbaPng(image) {
  return encodePng({ width: image.width, height: image.height, samples: image.pixels });
}

function crop(image, { x, y, width, height }) {
  const out = raster(width, height);
  for (let py = 0; py < height; py++) {
    for (let px = 0; px < width; px++) {
      const source = ((y + py) * image.width + (x + px)) * 4;
      out.pixels.set(image.pixels.subarray(source, source + 4), (py * width + px) * 4);
    }
  }
  return out;
}

/** An 8-bit greyscale, no-alpha mask: `0` unmasked, `255` masked, strictly binary. */
function maskPng(width, height, paint) {
  const samples = new Uint8Array(width * height);
  paint((box, value = 255) => {
    for (let y = box.y; y < box.y + box.height; y++) {
      for (let x = box.x; x < box.x + box.width; x++) {
        if (x < 0 || y < 0 || x >= width || y >= height) continue;
        samples[y * width + x] = value;
      }
    }
  });
  return { png: encodePng({ width, height, colourType: COLOUR_GREY, samples }), samples };
}

function maskBox(samples, width, height) {
  let minX = Infinity;
  let minY = Infinity;
  let maxX = -1;
  let maxY = -1;
  for (let y = 0; y < height; y++) {
    for (let x = 0; x < width; x++) {
      if (samples[y * width + x] !== 255) continue;
      if (x < minX) minX = x;
      if (x > maxX) maxX = x;
      if (y < minY) minY = y;
      if (y > maxY) maxY = y;
    }
  }
  return { x: minX, y: minY, width: maxX - minX + 1, height: maxY - minY + 1 };
}

// --------------------------------------------------------------------------------------------
// Case authoring
// --------------------------------------------------------------------------------------------

const cases = [];

/**
 * Declare one case.
 *
 * `files` is every byte the case ships, keyed by its path inside the case directory. Artifact paths
 * live under `artifacts/<id>/…`, which is the fixture tree's stand-in for
 * `.design-parity/known-differences/<id>/…`.
 */
function addCase({ id, title, site, why, document, documentText = null, files, comparison = null, catalog = null, synthesize = [], expected }) {
  cases.push({ id, title, site, why, document, documentText, files, comparison, catalog, synthesize, expected });
}

/** The worked example's world, reused by most gate and validation cases. */
function glyphWorld({ candidateGlyph = RED, acceptedGlyph = RED, adjacentRegression = false } = {}) {
  const plane = { x: 4, y: 4, width: 24, height: 24 };
  const glyph = { x: 8, y: 8, width: 8, height: 8 };

  const reference = fillRect(raster(24, 24), glyph, BLACK);
  fillRect(reference, { x: 0, y: 0, width: 4, height: 4 }, GREY);

  const candidate = fillRect(raster(24, 24), glyph, candidateGlyph);
  fillRect(candidate, { x: 0, y: 0, width: 4, height: 4 }, GREY);
  // Two pixels immediately outside the mask edge. Only `valid` acceptances contribute a mask to the
  // scoring union, so a `resolved` region must not go on removing its neighbours from the
  // neighbourhood search — this is the regression that reading would hide.
  if (adjacentRegression) fillRect(candidate, { x: 16, y: 8, width: 2, height: 8 }, GREEN);

  const accepted = fillRect(crop(candidate, glyph), { x: 0, y: 0, width: 8, height: 8 }, acceptedGlyph);
  const { png: mask } = maskPng(24, 24, (paint) => paint(glyph));

  return {
    plane: { plane: "content-box", box: plane },
    glyph,
    maskPngBytes: mask,
    acceptedPngBytes: rgbaPng(accepted),
    referencePngBytes: rgbaPng(reference),
    candidatePngBytes: rgbaPng(candidate),
  };
}

// Hex *with letters in it*, because the uppercase-served fixture is meaningless otherwise: a digest
// spelled only in digits is its own uppercase, so a validator that stopped normalising would still
// pass the case that exists to catch it.
const REFERENCE_SHA = "a1b2c3d4e5f60718".repeat(4);

/** A record in the shape the schema spells, with the worked example's scope. */
function glyphRecord(world, overrides = {}) {
  return {
    id: "m3-iconbutton-tonal-glyph",
    issue: "https://github.com/yschimke/m3-catalog/issues/40",
    system: "m3",
    component: "IconButton/Tonal",
    previewId: "iconbutton-tonal__ideal__default__light",
    referenceId: "iconbutton-tonal-ideal-light",
    variant: "ideal/default/light",
    mask: "mask.png",
    acceptedCandidate: "accepted-candidate.png",
    referenceSha256: REFERENCE_SHA,
    maskSha256: sha256Hex(world.maskPngBytes),
    acceptedCandidateSha256: sha256Hex(world.acceptedPngBytes),
    plane: world.plane,
    candidateTolerance: 2,
    element: {
      kind: "tag",
      tag: "iconbutton-tonal-glyph",
      bounds: { x: 8, y: 8, width: 8, height: 8 },
      tolerance: 0.1,
    },
    note: "Tonal icon button draws its glyph in onSurfaceVariant; the kit uses onSecondaryContainer.",
    acceptedAt: "2026-08-22T00:00:00Z",
    ...overrides,
  };
}

function glyphComparison(world, overrides = {}) {
  return {
    system: "m3",
    component: "IconButton/Tonal",
    previewId: "iconbutton-tonal__ideal__default__light",
    referenceId: "iconbutton-tonal-ideal-light",
    variant: "ideal/default/light",
    overrides: {},
    referenceSha256: REFERENCE_SHA,
    plane: world.plane,
    canonicalReference: "canonical-reference.png",
    canonicalCandidate: "canonical-candidate.png",
    tagIndex: { "iconbutton-tonal-glyph": { count: 1, bounds: { x: 8, y: 8, width: 8, height: 8 } } },
    ...overrides,
  };
}

function glyphFiles(world, record = glyphRecord(world)) {
  return {
    [`artifacts/${record.id}/mask.png`]: world.maskPngBytes,
    [`artifacts/${record.id}/accepted-candidate.png`]: world.acceptedPngBytes,
    "canonical-reference.png": world.referencePngBytes,
    "canonical-candidate.png": world.candidatePngBytes,
  };
}

function document(acceptances) {
  return { schema: "compose-preview-known-differences/v1", acceptances };
}

// --------------------------------------------------------------------------------------------
// 1. The pilot population — one case per site, so a reviewer can point at each of the six and say
//    which fixture covers it. Measured, not assumed: four issues across six sites, and only #40's
//    mask is glyph-sized.
// --------------------------------------------------------------------------------------------

{
  const world = glyphWorld();
  const record = glyphRecord(world);
  addCase({
    id: "pilot-40-iconbutton-tonal-glyph",
    title: "m3-catalog#40 — IconButton/Tonal glyph colour",
    site: "yschimke/m3-catalog#40",
    why:
      "The worked example, and the only one of the six sites whose mask is glyph-sized. One " +
      "component, one preview, an element the semantics tree can name — so it carries the element " +
      "gate that separates 'the glyph disappeared' from 'the glyph is still the wrong colour'.",
    document: document([record]),
    files: glyphFiles(world, record),
    comparison: glyphComparison(world),
    expected: {
      pins: ["statuses", "validationFailures"],
      statuses: { "m3-iconbutton-tonal-glyph": { status: "valid" } },
      validationFailures: [],
    },
  });
}

{
  // #41 is a layout failure: the bar measures its items at full width, so the mask is most of the
  // bar rather than one element. `v1` permits a geometric acceptance for exactly this reason —
  // requiring an element gate outright would make #41 inexpressible until the bar's parts are
  // tagged, which puts batch 03's work in front of this one.
  const plane = { plane: "content-box", box: { x: 0, y: 0, width: 48, height: 12 } };
  const reference = fillRect(raster(48, 12), { x: 4, y: 2, width: 8, height: 8 }, BLACK);
  fillRect(reference, { x: 20, y: 2, width: 8, height: 8 }, BLACK);
  fillRect(reference, { x: 36, y: 2, width: 8, height: 8 }, BLACK);
  const candidate = fillRect(raster(48, 12), { x: 2, y: 2, width: 8, height: 8 }, BLACK);
  fillRect(candidate, { x: 22, y: 2, width: 8, height: 8 }, BLACK);
  fillRect(candidate, { x: 40, y: 2, width: 6, height: 8 }, BLACK);
  const { png: mask, samples } = maskPng(48, 12, (paint) => paint({ x: 4, y: 1, width: 40, height: 10 }));
  const box = maskBox(samples, 48, 12);
  const accepted = crop(candidate, box);

  const record = {
    id: "m3-navigationbar-short-items",
    issue: "https://github.com/yschimke/m3-catalog/issues/41",
    system: "m3",
    component: "NavigationBar/Short",
    previewId: "navigationbar-short__ideal__compact__light",
    referenceId: "navigationbar-short-ideal-light",
    variant: "ideal/compact/light",
    mask: "mask.png",
    acceptedCandidate: "accepted-candidate.png",
    referenceSha256: REFERENCE_SHA,
    maskSha256: sha256Hex(mask),
    acceptedCandidateSha256: sha256Hex(rgbaPng(accepted)),
    plane,
    candidateTolerance: 2,
    note: "ShortNavigationBar measures its items at full bar width. No element is tagged yet, so this is geometric.",
    acceptedAt: "2026-08-22T00:00:00Z",
  };
  addCase({
    id: "pilot-41-navigationbar-short",
    title: "m3-catalog#41 — ShortNavigationBar measures items at full bar width",
    site: "yschimke/m3-catalog#41",
    why:
      "The geometric shape: no `element` key at all, and a mask covering most of the bar. It " +
      "re-invalidates on every render change, and that churn is the price of being able to express " +
      "#41 before the bar's parts are tagged.",
    document: document([record]),
    files: {
      "artifacts/m3-navigationbar-short-items/mask.png": mask,
      "artifacts/m3-navigationbar-short-items/accepted-candidate.png": rgbaPng(accepted),
      "canonical-reference.png": rgbaPng(reference),
      "canonical-candidate.png": rgbaPng(candidate),
    },
    comparison: {
      system: "m3",
      component: "NavigationBar/Short",
      previewId: "navigationbar-short__ideal__compact__light",
      referenceId: "navigationbar-short-ideal-light",
      variant: "ideal/compact/light",
      overrides: {},
      referenceSha256: REFERENCE_SHA,
      plane,
      canonicalReference: "canonical-reference.png",
      canonicalCandidate: "canonical-candidate.png",
      tagIndex: {},
    },
    expected: {
      pins: ["statuses", "validationFailures"],
      statuses: { "m3-navigationbar-short-items": { status: "valid" } },
      validationFailures: [],
    },
  });
}

{
  // #87 is a 2dp ring around a 20dp box — a mask with a hole in it, which is the shape that breaks
  // any implementation treating a mask as its bounding rectangle.
  const plane = { plane: "content-box", box: { x: 2, y: 2, width: 24, height: 24 } };
  const reference = raster(24, 24);
  fillRect(reference, { x: 2, y: 2, width: 20, height: 20 }, BLACK);
  fillRect(reference, { x: 4, y: 4, width: 16, height: 16 }, WHITE);
  const candidate = raster(24, 24);
  fillRect(candidate, { x: 0, y: 0, width: 24, height: 24 }, WHITE);
  fillRect(candidate, { x: 2, y: 2, width: 20, height: 20 }, GREY);
  fillRect(candidate, { x: 4, y: 4, width: 16, height: 16 }, WHITE);
  const { png: mask, samples } = maskPng(24, 24, (paint) => {
    paint({ x: 2, y: 2, width: 20, height: 20 }, 255);
    paint({ x: 4, y: 4, width: 16, height: 16 }, 0);
  });
  const box = maskBox(samples, 24, 24);
  // **The hole has to disagree, or the case proves nothing.** Cropping the candidate exactly made
  // the *unselected* centre agree too, so an engine comparing the whole bounding rectangle — the
  // very implementation this mask exists to catch — passed it. One pixel inside the hole is changed
  // between the accepted crop and the candidate the comparison supplies; the ring is untouched, so
  // the acceptance is still `valid`, and only an engine that honours the `255` samples can say so.
  const accepted = crop(candidate, box);
  const holePixel = ((12 - box.y) * box.width + (12 - box.x)) * 4;
  accepted.pixels[holePixel] = 7;
  accepted.pixels[holePixel + 1] = 11;
  accepted.pixels[holePixel + 2] = 13;

  const record = {
    id: "m3-checkbox-checked-ring",
    issue: "https://github.com/yschimke/m3-catalog/issues/87",
    system: "m3",
    component: "Checkbox/Checked",
    previewId: "checkbox-checked__ideal__default__light",
    referenceId: "checkbox-checked-ideal-light",
    variant: "ideal/default/light",
    mask: "mask.png",
    acceptedCandidate: "accepted-candidate.png",
    referenceSha256: REFERENCE_SHA,
    maskSha256: sha256Hex(mask),
    acceptedCandidateSha256: sha256Hex(rgbaPng(accepted)),
    plane,
    candidateTolerance: 2,
    element: {
      kind: "tag",
      tag: "checkbox-checked-box",
      bounds: { x: 2, y: 2, width: 20, height: 20 },
      tolerance: 0.1,
    },
    note: "Checkbox draws its box with 2dp padding where the kit uses 4dp.",
    acceptedAt: "2026-08-22T00:00:00Z",
  };
  addCase({
    id: "pilot-87-checkbox-checked-ring",
    title: "m3-catalog#87 — Checkbox box padding 2dp vs 4dp",
    site: "yschimke/m3-catalog#87",
    why:
      "A 2dp ring around a 20dp box: the mask is an annulus, so its bounding box contains " +
      "sixteen-by-sixteen unmasked pixels in the middle. `accepted-candidate.png` is still the " +
      "bounding-box crop — the contract stores the crop, and the mask decides which of its pixels " +
      "are compared.",
    document: document([record]),
    files: {
      "artifacts/m3-checkbox-checked-ring/mask.png": mask,
      "artifacts/m3-checkbox-checked-ring/accepted-candidate.png": rgbaPng(accepted),
      "canonical-reference.png": rgbaPng(reference),
      "canonical-candidate.png": rgbaPng(candidate),
    },
    comparison: {
      system: "m3",
      component: "Checkbox/Checked",
      previewId: "checkbox-checked__ideal__default__light",
      referenceId: "checkbox-checked-ideal-light",
      variant: "ideal/default/light",
      overrides: {},
      referenceSha256: REFERENCE_SHA,
      plane,
      canonicalReference: "canonical-reference.png",
      canonicalCandidate: "canonical-candidate.png",
      tagIndex: { "checkbox-checked-box": { count: 1, bounds: { x: 2, y: 2, width: 20, height: 20 } } },
    },
    expected: {
      pins: ["statuses", "validationFailures"],
      statuses: { "m3-checkbox-checked-ring": { status: "valid" } },
      validationFailures: [],
    },
  });
}

{
  // #42 names three components, so its body carries three locator blocks, the index three rows and
  // §4 three acceptances — all pointing at one tracking issue. An issue is closable only once every
  // acceptance linked to it resolves, which is why the case pins `locallyResolvedIssues` as well: one
  // comparison can only reach one of the three, and the other two are `out-of-scope` rather than
  // absent.
  const plane = { plane: "content-box", box: { x: 0, y: 0, width: 32, height: 24 } };
  const component = { x: 4, y: 4, width: 24, height: 16 };
  const shadow = { x: 2, y: 2, width: 28, height: 20 };

  const reference = raster(32, 24);
  fillRect(reference, shadow, GREY);
  fillRect(reference, component, BLACK);
  const candidate = raster(32, 24);
  fillRect(candidate, shadow, WHITE);
  fillRect(candidate, component, BLACK);
  const { png: mask, samples } = maskPng(32, 24, (paint) => {
    paint(shadow, 255);
    paint(component, 0);
  });
  const box = maskBox(samples, 32, 24);
  const accepted = crop(candidate, box);

  const sites = [
    ["m3-button-elevated-shadow", "Button/Elevated", "button-elevated"],
    ["m3-card-elevated-shadow", "Card/Elevated", "card-elevated"],
    ["m3-togglebutton-elevated-shadow", "ToggleButton/Elevated", "togglebutton-elevated"],
  ];
  const acceptances = sites.map(([id, componentName, slug]) => ({
    id,
    issue: "https://github.com/yschimke/m3-catalog/issues/42",
    system: "m3",
    component: componentName,
    previewId: `${slug}__ideal__default__light`,
    referenceId: `${slug}-ideal-light`,
    variant: "ideal/default/light",
    mask: "mask.png",
    acceptedCandidate: "accepted-candidate.png",
    referenceSha256: REFERENCE_SHA,
    maskSha256: sha256Hex(mask),
    acceptedCandidateSha256: sha256Hex(rgbaPng(accepted)),
    plane,
    candidateTolerance: 2,
    note: "Elevated containers draw no shadow; the kit draws level 1.",
    acceptedAt: "2026-08-22T00:00:00Z",
  }));

  const files = { "canonical-reference.png": rgbaPng(reference), "canonical-candidate.png": rgbaPng(candidate) };
  for (const [id] of sites) {
    files[`artifacts/${id}/mask.png`] = mask;
    files[`artifacts/${id}/accepted-candidate.png`] = rgbaPng(accepted);
  }

  addCase({
    id: "pilot-42-elevated-shadow-trio",
    title: "m3-catalog#42 — Elevated shadow level, three components on one issue",
    site: "yschimke/m3-catalog#42 (Button/Elevated, Card/Elevated, ToggleButton/Elevated)",
    why:
      "Three of the six sites at once, and the case the closure rule is built on: the tracking " +
      "issue is mandatory per acceptance but not unique to one. A comparison reaches exactly one " +
      "of the three, so the other two are `out-of-scope` — and the issue is not closable while any " +
      "of them is unresolved.",
    document: document(acceptances),
    files,
    comparison: {
      system: "m3",
      component: "Button/Elevated",
      previewId: "button-elevated__ideal__default__light",
      referenceId: "button-elevated-ideal-light",
      variant: "ideal/default/light",
      overrides: {},
      referenceSha256: REFERENCE_SHA,
      plane,
      canonicalReference: "canonical-reference.png",
      canonicalCandidate: "canonical-candidate.png",
      tagIndex: {},
    },
    expected: {
      pins: ["statuses", "validationFailures", "locallyResolvedIssues"],
      statuses: {
        "m3-button-elevated-shadow": { status: "valid" },
        "m3-card-elevated-shadow": { status: "out-of-scope" },
        "m3-togglebutton-elevated-shadow": { status: "out-of-scope" },
      },
      validationFailures: [],
      locallyResolvedIssues: [],
    },
  });
}

{
  // **One acceptance of three resolves, and the issue still does not close.** This is the case the
  // closure rule is actually about, and neither existing fixture reaches it: the trio has no
  // `resolved` record at all, and the only `resolved` fixture has a single acceptance, whose issue
  // is therefore trivially fully-resolved. An engine that closes an issue as soon as *any* linked
  // acceptance resolves passes both of them — and closing here would orphan the two live siblings,
  // which is exactly what Phase 4's stale detection would then flag.
  const plane = { plane: "content-box", box: { x: 0, y: 0, width: 32, height: 24 } };
  const component = { x: 4, y: 4, width: 24, height: 16 };
  const shadow = { x: 2, y: 2, width: 28, height: 20 };

  const reference = raster(32, 24);
  fillRect(reference, shadow, GREY);
  fillRect(reference, component, BLACK);
  // The candidate as it was when the acceptances were authored — the shadow missing.
  const brokenCandidate = raster(32, 24);
  fillRect(brokenCandidate, shadow, WHITE);
  fillRect(brokenCandidate, component, BLACK);
  // The candidate now: the shadow is drawn, so the reached acceptance has been fixed upstream.
  const fixedCandidate = raster(32, 24);
  fillRect(fixedCandidate, shadow, GREY);
  fillRect(fixedCandidate, component, BLACK);

  const { png: mask, samples } = maskPng(32, 24, (paint) => {
    paint(shadow, 255);
    paint(component, 0);
  });
  const box = maskBox(samples, 32, 24);
  const accepted = crop(brokenCandidate, box);

  const sites = [
    ["m3-button-elevated-shadow", "Button/Elevated", "button-elevated"],
    ["m3-card-elevated-shadow", "Card/Elevated", "card-elevated"],
    ["m3-togglebutton-elevated-shadow", "ToggleButton/Elevated", "togglebutton-elevated"],
  ];
  const acceptances = sites.map(([id, componentName, slug], index) => ({
    id,
    // **The reached acceptance spells its issue differently, and it is the same issue.** Mixed-case
    // owner and a trailing slash: `new URL` and the canonical key both fold them, and an engine
    // grouping on the raw string does not — it would see the one *resolved* record alone in its own
    // group, find that group fully resolved, and close #42 while two siblings are still live. The
    // spelling variation has to sit on the resolved record for that to be the failure; on a live
    // sibling it would merely split two unresolved groups and change nothing.
    issue:
      index === 0
        ? "https://github.com/YSchimke/m3-catalog/issues/42/"
        : "https://github.com/yschimke/m3-catalog/issues/42",
    system: "m3",
    component: componentName,
    previewId: `${slug}__ideal__default__light`,
    referenceId: `${slug}-ideal-light`,
    variant: "ideal/default/light",
    mask: "mask.png",
    acceptedCandidate: "accepted-candidate.png",
    referenceSha256: REFERENCE_SHA,
    maskSha256: sha256Hex(mask),
    acceptedCandidateSha256: sha256Hex(rgbaPng(accepted)),
    plane,
    candidateTolerance: 2,
    note: "Elevated containers draw no shadow; the kit draws level 1.",
    acceptedAt: "2026-08-22T00:00:00Z",
  }));

  const files = {
    "canonical-reference.png": rgbaPng(reference),
    "canonical-candidate.png": rgbaPng(fixedCandidate),
  };
  for (const [id] of sites) {
    files[`artifacts/${id}/mask.png`] = mask;
    files[`artifacts/${id}/accepted-candidate.png`] = rgbaPng(accepted);
  }

  addCase({
    id: "issue-partially-resolved-across-siblings",
    title: "m3-catalog#42 — one of three acceptances resolves, and the issue stays open",
    site: "yschimke/m3-catalog#42 (Button/Elevated resolved; Card, ToggleButton still live)",
    why:
      "`locallyResolvedIssues` aggregates by issue, and the aggregation is the whole rule — a per-" +
      "acceptance reading of it is indistinguishable from the correct one on every other fixture in " +
      "this tree. Here the reached acceptance is genuinely fixed (`resolved`) while its two siblings " +
      "on the same issue are merely not reached by this comparison (`out-of-scope`), which is not " +
      "evidence that they are fixed. So `locallyResolvedIssues` is empty even though a `resolved` " +
      "status is present, which no other case asserts.",
    document: document(acceptances),
    files,
    comparison: {
      system: "m3",
      component: "Button/Elevated",
      previewId: "button-elevated__ideal__default__light",
      referenceId: "button-elevated-ideal-light",
      variant: "ideal/default/light",
      overrides: {},
      referenceSha256: REFERENCE_SHA,
      plane,
      canonicalReference: "canonical-reference.png",
      canonicalCandidate: "canonical-candidate.png",
      tagIndex: {},
    },
    expected: {
      pins: ["statuses", "validationFailures", "locallyResolvedIssues"],
      statuses: {
        "m3-button-elevated-shadow": { status: "resolved" },
        "m3-card-elevated-shadow": { status: "out-of-scope" },
        "m3-togglebutton-elevated-shadow": { status: "out-of-scope" },
      },
      validationFailures: [],
      locallyResolvedIssues: [],
    },
  });
}

// --------------------------------------------------------------------------------------------
// 2. The gates, and the status precedence table.
// --------------------------------------------------------------------------------------------

{
  const world = glyphWorld({ candidateGlyph: BLACK, adjacentRegression: true });
  const record = glyphRecord(world);
  addCase({
    id: "gate-resolved-fixed-candidate",
    title: "The candidate gate fired and the region converged on the reference",
    site: "yschimke/m3-catalog#40 (fixed)",
    why:
      "The required fixed-candidate case: `resolved` outranks `candidate-changed`, and only the " +
      "comparison against the *reference* tells 'it was fixed' apart from 'it changed into " +
      "something else'. It carries an **adjacent regression** two pixels outside the mask edge on " +
      "purpose — a resolved acceptance contributes no mask to the scoring union, and the wrong " +
      "reading keeps suppressing that neighbour.",
    document: document([record]),
    files: glyphFiles(world, record),
    comparison: glyphComparison(world),
    expected: {
      pins: ["statuses", "validationFailures", "locallyResolvedIssues"],
      statuses: { "m3-iconbutton-tonal-glyph": { status: "resolved" } },
      validationFailures: [],
      locallyResolvedIssues: ["yschimke/m3-catalog#40"],
    },
  });
}

// **The metric itself, pinned by two cases rather than assumed.** Every gate case so far either
// agrees exactly or disagrees by 140 in two channels at once, which *every* candidate metric —
// maximum-channel, mean, sum, Euclidean — reports the same way. These two separate them, in the two
// directions they can differ:
//
//   - one channel over by 1: maximum-channel says changed, a **mean** over four channels (0.75) says
//     unchanged;
//   - all four channels at exactly the tolerance: maximum-channel says unchanged, while a **sum**
//     (8) or a **Euclidean** distance (4) says changed.
//
// Together they admit only "max absolute per-channel difference, compared with `>`", which is what
// §4 answer 6 settles. The second also pins the inclusive boundary from the accepting side.
for (const [id, title, delta, status, why] of [
  [
    "gate-metric-single-channel-over",
    "One channel past `candidateTolerance`, three identical",
    [0, 3, 0, 0],
    { status: "invalidated", causes: ["candidate-changed"] },
    "Maximum-channel distance is 3 against a tolerance of 2, so the gate fires. A mean over the " +
      "four channels is 0.75 and would call this pixel unchanged — the reading this case exists to " +
      "refuse, since it lets a single-channel colour regression sit inside an acceptance forever.",
  ],
  [
    "gate-metric-every-channel-at-tolerance",
    "Every channel exactly at `candidateTolerance`",
    [2, 2, 2, -2],
    { status: "valid" },
    "Maximum-channel distance is exactly 2 and the comparison is `>`, so this is legal — the " +
      "inclusive boundary, from the accepting side. A summed distance (8) or a Euclidean one (4) " +
      "would call it changed, which is how this case tells those two apart from the settled metric. " +
      "Alpha moves the other way so the case cannot be satisfied by an implementation that only " +
      "looks at RGB.",
  ],
]) {
  const world = glyphWorld();
  // The accepted crop is the candidate's glyph, one pixel of it nudged by `delta`.
  const accepted = raster(8, 8, RED);
  for (let channel = 0; channel < 4; channel++) {
    accepted.pixels[channel] = RED[channel] + delta[channel];
  }
  const acceptedPng = rgbaPng(accepted);
  const record = glyphRecord(world, { acceptedCandidateSha256: sha256Hex(acceptedPng) });
  addCase({
    id,
    title,
    why,
    document: document([record]),
    files: { ...glyphFiles(world, record), "artifacts/m3-iconbutton-tonal-glyph/accepted-candidate.png": acceptedPng },
    comparison: glyphComparison(world),
    expected: {
      pins: ["statuses", "validationFailures"],
      statuses: { "m3-iconbutton-tonal-glyph": status },
      validationFailures: [],
    },
  });
}

{
  const world = glyphWorld({ candidateGlyph: GREEN });
  const record = glyphRecord(world);
  addCase({
    id: "gate-candidate-changed",
    title: "The masked region is neither the accepted difference nor the reference",
    why: "The candidate gate fired and the region did not converge — row 4 of the precedence table.",
    document: document([record]),
    files: glyphFiles(world, record),
    comparison: glyphComparison(world),
    expected: {
      pins: ["statuses", "validationFailures"],
      statuses: { "m3-iconbutton-tonal-glyph": { status: "invalidated", causes: ["candidate-changed"] } },
      validationFailures: [],
    },
  });
}

{
  const world = glyphWorld();
  const record = glyphRecord(world);
  addCase({
    id: "gate-reference-changed",
    title: "The served reference no longer hashes to the recorded one",
    why:
      "The fingerprint gate. `reference-changed` is metadata, so it fires before anything is " +
      "decoded — and it suppresses the no-op check, which would otherwise be evaluated against an " +
      "image the acceptance was never authored against.",
    document: document([record]),
    files: glyphFiles(world, record),
    comparison: glyphComparison(world, { referenceSha256: "2".repeat(64) }),
    expected: {
      pins: ["statuses", "validationFailures"],
      statuses: { "m3-iconbutton-tonal-glyph": { status: "invalidated", causes: ["reference-changed"] } },
      validationFailures: [],
    },
  });
}

{
  const world = glyphWorld();
  const record = glyphRecord(world);
  addCase({
    id: "gate-served-hash-uppercase",
    title: "An uppercase *served* reference hash must not report `reference-changed`",
    why:
      "`ServeDesignReferenceStore` lowercases a reference hash to validate it and then serves the " +
      "original spelling, so raw string inequality reports 'the design moved' for a reference that " +
      "never changed. Both sides are lowercased before comparison. Its sibling fixture — an " +
      "uppercase *recorded* hash — must be `schema-invalid`, because we can refuse two spellings of " +
      "our own fields even though we cannot constrain what upstream publishes.",
    document: document([record]),
    files: glyphFiles(world, record),
    comparison: glyphComparison(world, { referenceSha256: REFERENCE_SHA.toUpperCase() }),
    expected: {
      pins: ["statuses", "validationFailures"],
      statuses: { "m3-iconbutton-tonal-glyph": { status: "valid" } },
      validationFailures: [],
    },
  });
}

{
  const world = glyphWorld();
  const record = glyphRecord(world);
  addCase({
    id: "gate-plane-changed-short-circuits-element",
    title: "A changed plane short-circuits the element gates",
    why:
      "The tag is deliberately ambiguous in this comparison's index. Only `plane-changed` may be " +
      "reported: the index carries bounds in the comparison's plane, so running the element gate " +
      "against a plane the acceptance was not authored in manufactures a false cause on top of a " +
      "correct one.",
    document: document([record]),
    files: glyphFiles(world, record),
    comparison: glyphComparison(world, {
      plane: { plane: "full-canvas", box: { x: 0, y: 0, width: 32, height: 32 } },
      tagIndex: { "iconbutton-tonal-glyph": { count: 3, bounds: { x: 8, y: 8, width: 8, height: 8 } } },
    }),
    expected: {
      pins: ["statuses", "validationFailures"],
      statuses: { "m3-iconbutton-tonal-glyph": { status: "invalidated", causes: ["plane-changed"] } },
      validationFailures: [],
    },
  });
}

{
  // **A record actually authored on `full-canvas`**, not merely a comparison that changed into one.
  // The only other appearance of the token is the deliberately-mismatched plane in the case above,
  // so an engine recognising it well enough to report `plane-changed` — and no further — passed the
  // suite while mishandling the fallback plane the contract selects whenever content coverage falls
  // under `MIN_BOX_COVERAGE`. That fallback is not exotic: it is what a nearly-empty preview gets.
  const world = glyphWorld();
  const plane = { plane: "full-canvas", box: { x: 0, y: 0, width: 24, height: 24 } };
  const record = glyphRecord(world, { plane });
  addCase({
    id: "plane-full-canvas-acceptance",
    title: "An acceptance authored and evaluated on the full-canvas plane",
    why:
      "The `plane` field is a discriminant *and* a box, and both halves must match. Here both sides " +
      "say `full-canvas` over the same box, so the acceptance is evaluated exactly as a " +
      "`content-box` one would be — which is the point: the fallback changes which pixels the plane " +
      "covers, not how any gate behaves.",
    document: document([record]),
    files: glyphFiles(world, record),
    comparison: glyphComparison(world, { plane }),
    expected: {
      pins: ["statuses", "validationFailures"],
      statuses: { "m3-iconbutton-tonal-glyph": { status: "valid" } },
      validationFailures: [],
    },
  });
}

{
  const world = glyphWorld();
  const record = glyphRecord(world);
  addCase({
    id: "gate-element-ambiguous",
    title: "The tag is carried by more than one node",
    why:
      "Uniqueness is re-checked at evaluation time, against the full semantics payload rather than " +
      "the annotation layer — it was unique when the acceptance was authored, and only this check " +
      "notices when it stops being. Ambiguity short-circuits the *bounds* check, so the causes list " +
      "is exactly `[element-ambiguous]` — and the supplied bounds are displaced **past tolerance** " +
      "so that the short-circuit is actually exercised: with bounds equal to the baseline, an engine " +
      "running the bounds check *after* detecting ambiguity emits the same single cause and the case " +
      "proves nothing. Here that engine emits `[element-ambiguous, element-moved]` and fails.",
    document: document([record]),
    files: glyphFiles(world, record),
    comparison: glyphComparison(world, {
      tagIndex: { "iconbutton-tonal-glyph": { count: 2, bounds: { x: 20, y: 20, width: 8, height: 8 } } },
    }),
    expected: {
      pins: ["statuses", "validationFailures"],
      statuses: { "m3-iconbutton-tonal-glyph": { status: "invalidated", causes: ["element-ambiguous"] } },
      validationFailures: [],
    },
  });
}

{
  const world = glyphWorld();
  const record = glyphRecord(world);
  addCase({
    id: "gate-element-vanished",
    title: "The tag resolves to nothing at all",
    why:
      "Zero matches is always evaluated and is always `element-moved` — that is 'the glyph " +
      "vanished', the case the element gate exists for. Reading the exactly-one rule as covering it " +
      "leaves the acceptance `valid` and still suppressing the pixels of an element that is gone.",
    document: document([record]),
    files: glyphFiles(world, record),
    comparison: glyphComparison(world, { tagIndex: {} }),
    expected: {
      pins: ["statuses", "validationFailures"],
      statuses: { "m3-iconbutton-tonal-glyph": { status: "invalidated", causes: ["element-moved"] } },
      validationFailures: [],
    },
  });
}

{
  // **Resolved exactly once, and still unusable.** The contract's `element-moved` clause has three
  // limbs — no match, a *single* match whose indexed bounds are missing/malformed/zero-area, and a
  // single match that moved too far — and only the first and third are fixtured. Every committed
  // `count: 1` case supplies a healthy positive-area box, so "unique means resolved" passes the whole
  // tree and leaves an acceptance `valid`, still suppressing pixels, when the projector could not
  // place the node at all.
  const world = glyphWorld();
  const record = glyphRecord(world, {
    element: { kind: "tag", tag: "iconbutton-tonal-glyph", bounds: { x: 8, y: 8, width: 8, height: 8 }, tolerance: 0.25 },
  });
  for (const [suffix, title, bounds] of [
    ["absent", "carries no bounds at all", undefined],
    ["zero-area", "carries a zero-area box", { x: 8, y: 8, width: 0, height: 8 }],
  ]) {
    addCase({
      id: `gate-element-unique-bounds-${suffix}`,
      title: `The tag resolves to exactly one node that ${title}`,
      why:
        "Uniqueness is necessary and not sufficient: without usable bounds there is nothing to " +
        "measure a displacement *from*, so the element cannot be shown to have stayed put and the " +
        "acceptance must not keep suppressing its pixels. Zero-area is included because it is the " +
        "shape a projector emits for a node it laid out but never placed — structurally a box, " +
        "measurably nothing.",
      document: document([record]),
      files: glyphFiles(world, record),
      comparison: glyphComparison(world, {
        tagIndex: { "iconbutton-tonal-glyph": bounds === undefined ? { count: 1 } : { count: 1, bounds } },
      }),
      expected: {
        pins: ["statuses", "validationFailures"],
        statuses: {
          "m3-iconbutton-tonal-glyph": { status: "invalidated", causes: ["element-moved"] },
        },
        validationFailures: [],
      },
    });
  }
}

{
  const world = glyphWorld();
  const record = glyphRecord(world);
  addCase({
    id: "gate-element-moved-past-tolerance",
    title: "The resolved element moved further than `element.tolerance` allows",
    why:
      "`0.1 × min(8, 8) = 0.8`, and the largest edge displacement is 1 — the comparison is `>`, so " +
      "this fires. Its sibling `gate-element-at-tolerance` sits exactly on the threshold and passes.",
    document: document([record]),
    files: glyphFiles(world, record),
    comparison: glyphComparison(world, {
      tagIndex: { "iconbutton-tonal-glyph": { count: 1, bounds: { x: 9, y: 8, width: 8, height: 8 } } },
    }),
    expected: {
      pins: ["statuses", "validationFailures"],
      statuses: { "m3-iconbutton-tonal-glyph": { status: "invalidated", causes: ["element-moved"] } },
      validationFailures: [],
    },
  });
}

{
  const world = glyphWorld();
  const record = glyphRecord(world);
  addCase({
    id: "gate-element-resized-not-moved",
    title: "The element kept its origin and changed size",
    why:
      "Every other one-match case *translates* the element, so an engine comparing only `x` and `y` " +
      "passes them all and leaves a resized element `valid` — still suppressing pixels of something " +
      "that is no longer the shape it was. Here the origin is untouched and only the far edges move: " +
      "`0.1 × min(8, 8) = 0.8` against a far-edge displacement of 2.",
    document: document([record]),
    files: glyphFiles(world, record),
    comparison: glyphComparison(world, {
      tagIndex: { "iconbutton-tonal-glyph": { count: 1, bounds: { x: 8, y: 8, width: 10, height: 10 } } },
    }),
    expected: {
      pins: ["statuses", "validationFailures"],
      statuses: { "m3-iconbutton-tonal-glyph": { status: "invalidated", causes: ["element-moved"] } },
      validationFailures: [],
    },
  });
}

{
  const world = glyphWorld();
  // A **rectangular** baseline, and a displacement between the two candidate denominators.
  const record = glyphRecord(world, {
    element: {
      kind: "tag",
      tag: "iconbutton-tonal-glyph",
      bounds: { x: 8, y: 8, width: 40, height: 8 },
      tolerance: 0.25,
    },
  });
  addCase({
    id: "gate-element-denominator-is-the-smaller-side",
    title: "A rectangular baseline, displaced between the two possible thresholds",
    why:
      "Every other one-match case uses a **square** baseline, where `min` and `max` are the same " +
      "number — so an engine normalising by the larger dimension passes all of them while granting " +
      "five times the movement here. `0.25 × min(40, 8) = 2` and `0.25 × max(40, 8) = 10`; the " +
      "displacement is 4, which fires under the contract's denominator and passes under the other.",
    document: document([record]),
    files: glyphFiles(world, record),
    comparison: glyphComparison(world, {
      tagIndex: {
        "iconbutton-tonal-glyph": { count: 1, bounds: { x: 12, y: 8, width: 40, height: 8 } },
      },
    }),
    expected: {
      pins: ["statuses", "validationFailures"],
      statuses: { "m3-iconbutton-tonal-glyph": { status: "invalidated", causes: ["element-moved"] } },
      validationFailures: [],
    },
  });
}

{
  // **A legal negative origin, carried all the way to a verdict.** `v1` permits negative `x` and `y`
  // — a transformed element or a content box can begin left of or above the canvas origin — and the
  // only fixture with one lives in the standalone `rounding/` group, which never builds a document,
  // never validates a schema and never runs a gate. So an engine adding the conventional
  // `x >= 0 && y >= 0` raster-bounds check passes the entire conformance tree while refusing legal
  // acceptances. The baseline and the resolved node agree exactly, so the element gate is satisfied
  // and the only thing this case can fail on is the sign.
  const world = glyphWorld();
  const bounds = { x: -4, y: -4, width: 8, height: 8 };
  const record = glyphRecord(world, {
    element: { kind: "tag", tag: "iconbutton-tonal-glyph", bounds, tolerance: 0.25 },
  });
  addCase({
    id: "element-bounds-negative-origin",
    title: "An acceptance whose element baseline has a negative origin",
    why:
      "Negative coordinates are legal and unexercised anywhere a verdict is produced. This record " +
      "validates, resolves its tag to a node at the same negative origin, measures a displacement " +
      "of zero and is `valid` — so a bounds check that rejects the sign refuses it outright, and " +
      "one that takes the absolute value measures a displacement of 8 and reports `element-moved`.",
    document: document([record]),
    files: glyphFiles(world, record),
    comparison: glyphComparison(world, {
      tagIndex: { "iconbutton-tonal-glyph": { count: 1, bounds } },
    }),
    expected: {
      pins: ["statuses", "validationFailures"],
      statuses: { "m3-iconbutton-tonal-glyph": { status: "valid" } },
      validationFailures: [],
    },
  });
}

{
  const world = glyphWorld();
  const record = glyphRecord(world, {
    element: { kind: "tag", tag: "iconbutton-tonal-glyph", bounds: { x: 8, y: 8, width: 8, height: 8 }, tolerance: 0.25 },
  });
  addCase({
    id: "gate-element-at-tolerance",
    title: "A displacement exactly at tolerance passes",
    why:
      "`0.25 × min(8, 8) = 2`, and every edge moved by exactly 2. The contract fixes the fraction " +
      "against the **smaller baseline dimension**, compares it against the **maximum of the four " +
      "edge displacements**, and uses `>` — the three parts two implementations would otherwise " +
      "choose differently.",
    document: document([record]),
    files: glyphFiles(world, record),
    comparison: glyphComparison(world, {
      tagIndex: { "iconbutton-tonal-glyph": { count: 1, bounds: { x: 10, y: 10, width: 8, height: 8 } } },
    }),
    expected: {
      pins: ["statuses", "validationFailures"],
      statuses: { "m3-iconbutton-tonal-glyph": { status: "valid" } },
      validationFailures: [],
    },
  });
}

{
  const world = glyphWorld();
  const record = glyphRecord(world, {
    element: {
      kind: "tag",
      tag: "iconbutton-tonal-glyph",
      bounds: { x: 0, y: 0, width: 200, height: 200 },
      tolerance: 0.145,
    },
  });
  addCase({
    id: "gate-element-at-tolerance-inexact-product",
    title: "A displacement at a tolerance whose product is not exact in binary",
    why:
      "Its sibling above sits on `0.25 × 8 = 2`, which double arithmetic gets exactly right, so it " +
      "says nothing about how the boundary is computed. `0.145 × 200` is `28.999999999999996`, so a " +
      "displacement of exactly 29 — the inclusive boundary — is `element-moved` under a scaled " +
      "tolerance and `valid` under a decimal or ratio consumer: the same bytes, two verdicts, from " +
      "the last binary digit. Comparing `displacement / min(width, height)` against the recorded " +
      "tolerance is exact here, because `29 / 200` and the literal `0.145` are the same double.",
    document: document([record]),
    files: glyphFiles(world, record),
    comparison: glyphComparison(world, {
      tagIndex: {
        "iconbutton-tonal-glyph": { count: 1, bounds: { x: 29, y: 29, width: 200, height: 200 } },
      },
    }),
    expected: {
      pins: ["statuses", "validationFailures"],
      statuses: { "m3-iconbutton-tonal-glyph": { status: "valid" } },
      validationFailures: [],
    },
  });
}

{
  const world = glyphWorld();
  const record = glyphRecord(world);
  addCase({
    id: "gate-multiple-causes",
    title: "Several gates fire at once",
    why:
      "Causes are a list, not a single value: with a singular field two engines would each pick one " +
      "and report different statuses while both obeyed every gate. Ordered as the gate table lists " +
      "them, which is what makes this case comparable across engines.",
    document: document([record]),
    files: glyphFiles(world, record),
    comparison: glyphComparison(world, {
      referenceSha256: "3".repeat(64),
      tagIndex: { "iconbutton-tonal-glyph": { count: 4, bounds: { x: 8, y: 8, width: 8, height: 8 } } },
    }),
    expected: {
      pins: ["statuses", "validationFailures"],
      statuses: {
        "m3-iconbutton-tonal-glyph": {
          status: "invalidated",
          causes: ["reference-changed", "element-ambiguous"],
        },
      },
      validationFailures: [],
    },
  });
}

{
  const world = glyphWorld();
  const record = glyphRecord(world);
  addCase({
    id: "gate-multiple-causes-reference-and-plane",
    title: "The reference and the plane both changed",
    why:
      "`causes` is ordered as the gate table lists them, and the only other multi-cause case pairs " +
      "`reference-changed` with `element-ambiguous` — so nothing pinned where `plane-changed` sits " +
      "relative to `reference-changed`, and an engine emitting them the other way round passed. " +
      "This pair is also the one a real reference refresh produces, since re-rendering a reference " +
      "usually moves its content box too.",
    document: document([record]),
    files: glyphFiles(world, record),
    comparison: glyphComparison(world, {
      referenceSha256: "3".repeat(64),
      plane: { plane: "content-box", box: { x: 5, y: 4, width: 24, height: 24 } },
    }),
    expected: {
      pins: ["statuses", "validationFailures"],
      statuses: {
        "m3-iconbutton-tonal-glyph": {
          status: "invalidated",
          causes: ["reference-changed", "plane-changed"],
        },
      },
      validationFailures: [],
    },
  });
}

/**
 * Two acceptances on one comparison.
 *
 * A fixture carrying one acceptance exercises none of the behaviour that only appears with several:
 * masks that overlap, and mixed validity where the failure mode is retaining suppression from the
 * invalidated mask. Both engines can pass every single-acceptance case and still disagree on these.
 */
function pairWorld({ maskA, maskB, candidatePaint }) {
  const plane = { plane: "content-box", box: { x: 0, y: 0, width: 24, height: 24 } };
  const reference = raster(24, 24);
  fillRect(reference, maskA, BLACK);
  fillRect(reference, maskB, BLACK);
  const candidate = raster(24, 24);
  candidatePaint(candidate);

  const build = (id, box) => {
    const { png, samples } = maskPng(24, 24, (paint) => paint(box));
    const bounds = maskBox(samples, 24, 24);
    const accepted = rgbaPng(crop(candidate, bounds));
    return { id, png, accepted, bounds };
  };
  return {
    plane,
    reference: rgbaPng(reference),
    candidate: rgbaPng(candidate),
    a: build("m3-pair-first", maskA),
    b: build("m3-pair-second", maskB),
  };
}

function pairRecord(world, part, extra = {}) {
  return {
    id: part.id,
    issue: "https://github.com/yschimke/m3-catalog/issues/40",
    system: "m3",
    component: "IconButton/Tonal",
    previewId: "iconbutton-tonal__ideal__default__light",
    referenceId: "iconbutton-tonal-ideal-light",
    variant: "ideal/default/light",
    mask: "mask.png",
    acceptedCandidate: "accepted-candidate.png",
    referenceSha256: REFERENCE_SHA,
    maskSha256: sha256Hex(part.png),
    acceptedCandidateSha256: sha256Hex(part.accepted),
    plane: world.plane,
    candidateTolerance: 2,
    acceptedAt: "2026-08-22T00:00:00Z",
    ...extra,
  };
}

function pairFiles(world) {
  return {
    [`artifacts/${world.a.id}/mask.png`]: world.a.png,
    [`artifacts/${world.a.id}/accepted-candidate.png`]: world.a.accepted,
    [`artifacts/${world.b.id}/mask.png`]: world.b.png,
    [`artifacts/${world.b.id}/accepted-candidate.png`]: world.b.accepted,
    "canonical-reference.png": world.reference,
    "canonical-candidate.png": world.candidate,
  };
}

function pairComparison(world, extra = {}) {
  return {
    system: "m3",
    component: "IconButton/Tonal",
    previewId: "iconbutton-tonal__ideal__default__light",
    referenceId: "iconbutton-tonal-ideal-light",
    variant: "ideal/default/light",
    overrides: {},
    referenceSha256: REFERENCE_SHA,
    plane: world.plane,
    canonicalReference: "canonical-reference.png",
    canonicalCandidate: "canonical-candidate.png",
    tagIndex: {},
    ...extra,
  };
}

{
  const boxA = { x: 4, y: 4, width: 10, height: 10 };
  const boxB = { x: 8, y: 8, width: 10, height: 10 };
  const world = pairWorld({
    maskA: boxA,
    maskB: boxB,
    candidatePaint: (image) => {
      fillRect(image, boxA, RED);
      fillRect(image, boxB, RED);
    },
  });
  addCase({
    id: "set-overlapping-masks",
    title: "Two acceptances whose masks overlap",
    why:
      "The union is what scoring excludes, so double-counting or gapping at the seam is invisible " +
      "with a single acceptance. Both survive here, so the union is the union of both masks and " +
      "the six-by-six overlap belongs to it exactly once.",
    document: document([pairRecord(world, world.a), pairRecord(world, world.b)]),
    files: pairFiles(world),
    comparison: pairComparison(world),
    expected: {
      pins: ["statuses", "validationFailures"],
      statuses: {
        "m3-pair-first": { status: "valid" },
        "m3-pair-second": { status: "valid" },
      },
      validationFailures: [],
    },
  });
}

{
  const boxA = { x: 3, y: 3, width: 6, height: 6 };
  const boxB = { x: 15, y: 15, width: 6, height: 6 };
  const world = pairWorld({
    maskA: boxA,
    maskB: boxB,
    candidatePaint: (image) => {
      fillRect(image, boxA, RED);
      fillRect(image, boxB, RED);
    },
  });
  addCase({
    id: "set-mixed-validity",
    title: "One acceptance survives while its sibling is invalidated",
    why:
      "Scoring runs against the union of **survivors**, and 'survivor' means status `valid` rather " +
      "than 'reached the end of the gates'. A single aggregate status cannot express this, so both " +
      "engines could emit the same summary while disagreeing about which mask survived.",
    document: document([
      pairRecord(world, world.a),
      pairRecord(world, world.b, {
        element: { kind: "tag", tag: "gone", bounds: { x: 15, y: 15, width: 6, height: 6 }, tolerance: 0.1 },
      }),
    ]),
    files: pairFiles(world),
    comparison: pairComparison(world),
    expected: {
      pins: ["statuses", "validationFailures"],
      statuses: {
        "m3-pair-first": { status: "valid" },
        "m3-pair-second": { status: "invalidated", causes: ["element-moved"] },
      },
      validationFailures: [],
    },
  });
}

// --------------------------------------------------------------------------------------------
// 3. Scope. Served ids are unique only within a system, and overrides are part of the scope.
// --------------------------------------------------------------------------------------------

// **One field at a time, all of them.** With only `system`, `component` and the overrides varied, a
// second engine that never compares `previewId`, `referenceId` or `variant` passes the whole suite —
// and then applies a mask authored for one preview of a component to a *different* preview,
// reference or variant of it, where the same geometry suppresses unrelated pixels. Each case below
// holds every other field constant so it can fail for exactly one reason.
for (const [field, value, why] of [
  [
    "previewId",
    "iconbutton-tonal__ideal__default__dark",
    "The same component in its dark preview: same geometry, different pixels beneath the mask.",
  ],
  [
    "referenceId",
    "iconbutton-tonal-ideal-dark",
    "The same preview compared against a different reference — what the difference *is* changes, " +
      "so an acceptance of the old difference says nothing about the new one.",
  ],
  [
    "variant",
    "ideal/compact/light",
    "A variant is a layout: the accepted region is somewhere else on the canvas, and the recorded " +
      "mask lands on whatever now occupies those coordinates.",
  ],
]) {
  const world = glyphWorld();
  const record = glyphRecord(world, { [field]: value });
  addCase({
    id: `scope-other-${field.toLowerCase()}`,
    title: `An acceptance authored for another \`${field}\``,
    why: `${why} Every recorded scope field must match, and this case varies only \`${field}\` so it can fail for one reason and no other.`,
    document: document([record]),
    files: glyphFiles(world, record),
    comparison: glyphComparison(world),
    expected: {
      pins: ["statuses", "validationFailures"],
      statuses: { "m3-iconbutton-tonal-glyph": { status: "out-of-scope" } },
      validationFailures: [],
    },
  });
}

{
  const world = glyphWorld();
  const record = glyphRecord(world, { system: "wear-m3" });
  addCase({
    id: "scope-other-system",
    title: "A `wear-m3` acceptance must not suppress pixels in `m3`",
    why:
      "Served preview and reference ids are unique only *within* a system, so matching on the " +
      "page's `(previewId, referenceId)` key alone lets one system's acceptance apply a mask to a " +
      "component nobody accepted anything for. Every recorded field must match; `system` and " +
      "`component` are the two a comparison-shaped mental model quietly drops.",
    document: document([record]),
    files: glyphFiles(world, record),
    comparison: glyphComparison(world),
    expected: {
      pins: ["statuses", "validationFailures"],
      statuses: { "m3-iconbutton-tonal-glyph": { status: "out-of-scope" } },
      validationFailures: [],
    },
  });
}

{
  const world = glyphWorld();
  // Authored and compared under the *same* non-empty overrides, spelled in opposite key orders.
  {
    // **An explicitly empty `overrides`, which the schema permits and nothing else here writes.**
    // Every other record either omits the key or carries a non-empty map, so "absent" and "present
    // but empty" are never distinguished — and a consumer that handles absence and non-empty
    // equality correctly can still treat a present `{}` as a distinct or unsupported scope, leaving
    // a legal default-frame acceptance permanently `out-of-scope` and suppressing nothing.
    const emptyWorld = glyphWorld();
    const emptyRecord = glyphRecord(emptyWorld, { overrides: {} });
    addCase({
      id: "scope-overrides-explicitly-empty",
      title: "An acceptance carrying an explicit empty `overrides` map",
      why:
        "`{}` and an absent key mean the same scope: the default frame, no overrides. The " +
        "comparison here carries `{}` too, so both spellings of 'nothing' meet — and an engine " +
        "keying on presence rather than on the entries reports `out-of-scope` for a record that " +
        "names exactly this comparison. The matching and mismatching override cases beside this one " +
        "both use non-empty maps, so neither reaches the empty one.",
      document: document([emptyRecord]),
      files: glyphFiles(emptyWorld, emptyRecord),
      comparison: glyphComparison(emptyWorld),
      expected: {
        pins: ["statuses", "validationFailures"],
        statuses: { "m3-iconbutton-tonal-glyph": { status: "valid" } },
        validationFailures: [],
      },
    });
  }

  const record = glyphRecord(world, {
    overrides: { fontScale: "1.5", "knob.density": "compact" },
  });
  addCase({
    id: "scope-overrides-match",
    title: "An acceptance authored under overrides applies at the frame carrying the same ones",
    why:
      "The matching half of the override rule, and the half that actually gates. With only the " +
      "mismatch pinned, an engine that treats *any* acceptance carrying overrides as " +
      "`out-of-scope` — never comparing pixels at all — passes the whole suite while suppressing " +
      "nothing. Here the two maps are equal and the acceptance must reach its gate verdict. The " +
      "two sides spell the keys in opposite orders on purpose: matching is over the set of " +
      "key/value pairs, not over a serialisation, so a consumer comparing rendered JSON rather " +
      "than entries fails exactly this case.",
    document: document([record]),
    files: glyphFiles(world, record),
    comparison: glyphComparison(world, {
      overrides: { "knob.density": "compact", fontScale: "1.5" },
    }),
    expected: {
      pins: ["statuses", "validationFailures"],
      statuses: { "m3-iconbutton-tonal-glyph": { status: "valid" } },
      validationFailures: [],
    },
  });
}

{
  const world = glyphWorld();
  const record = glyphRecord(world, { overrides: { fontScale: "1.5" } });
  addCase({
    id: "scope-overrides-differ",
    title: "An acceptance authored at `fontScale=1.5` does not apply at the default frame",
    why:
      "Overrides change layout and a mask is geometry, so an acceptance for a glyph at one font " +
      "scale covers different pixels at another. Matching is exact over the **whole** map — every " +
      "key the render lane accepts, including `knob.<key>` and `rc.<name>`, because a key that did " +
      "not affect the render would not be in the map.",
    document: document([record]),
    files: glyphFiles(world, record),
    comparison: glyphComparison(world),
    expected: {
      pins: ["statuses", "validationFailures"],
      statuses: { "m3-iconbutton-tonal-glyph": { status: "out-of-scope" } },
      validationFailures: [],
    },
  });
}

{
  const world = glyphWorld();
  const record = glyphRecord(world, { system: "wear-m3", maskSha256: "0".repeat(64) });
  addCase({
    id: "scope-refusal-is-comparison-independent",
    title: "A record that is out of scope *and* broken is still `refused`",
    why:
      "Refusal outranks scope, because a broken artifact is broken on every page and a build gate's " +
      "`validationFailures` must not depend on which comparison happened to run. The two " +
      "comparison-scoped refusals — `reference-hash-missing` and `acceptance-is-noop` — are the " +
      "exceptions, and they are only reachable in scope.",
    document: document([record]),
    files: glyphFiles(world, record),
    comparison: glyphComparison(world),
    expected: {
      pins: ["statuses", "validationFailures"],
      statuses: { "m3-iconbutton-tonal-glyph": { status: "refused", reasons: ["mask-hash-mismatch"] } },
      validationFailures: [{ id: "m3-iconbutton-tonal-glyph", reason: "mask-hash-mismatch" }],
    },
  });
}

// --------------------------------------------------------------------------------------------
// 4. Validation. Every rule in the contract gets at least one rejecting fixture and one accepting
//    one; the accepting halves are the cases above.
// --------------------------------------------------------------------------------------------

/** A one-record validation case built on the worked example's world. */
function glyphValidation({ id, title, why, record: recordOverrides = {}, files: fileOverrides = {}, comparison: comparisonOverrides = {}, catalog = null, synthesize = [], expected, documentText = null }) {
  const world = glyphWorld();
  const record = glyphRecord(world, recordOverrides);
  const files = { ...glyphFiles(world, glyphRecord(world)), ...fileOverrides };
  addCase({
    id,
    title,
    why,
    // `documentText` is committed **verbatim**, so a fixture can carry bytes `JSON.stringify` would
    // not produce — a repeated member name, or a fractional token that rounds onto an integer.
    // Routing it through `document` instead would commit a JSON-encoded *string*, which is
    // `document-unreadable` whatever it contains: a case passing for the wrong reason.
    document: documentText ? null : document([record]),
    documentText,
    files,
    comparison: glyphComparison(world, comparisonOverrides),
    catalog,
    synthesize,
    expected,
  });
}

// **Declared here, not imported from the module.** Every expected value in this tree is written by
// hand for the same reason: a fixture that reads the implementation's constant agrees with whatever
// the implementation does, bugs included. `v1` names 1 MiB, so 1 MiB is what the fixture spells, and
// the suite's own budget test is what holds the module to the same number.
const MAX_DOCUMENT_BYTES = 1024 * 1024;

const refused = (reasons, recordId = "m3-iconbutton-tonal-glyph") => ({
  pins: ["statuses", "validationFailures"],
  statuses: { [recordId]: { status: "refused", reasons } },
  validationFailures: reasons.map((reason) => ({ id: recordId, reason })),
});

// --- the document itself -----------------------------------------------------------------------

{
  // **Exactly 1 MiB**, the accepting half of the document ceiling. Without it a consumer comparing
  // with `>=` refuses what `v1` calls legal and still passes every case: the over-cap fixture's
  // `note` is a full 1 MiB on its own, so the surrounding JSON puts that document strictly past the
  // ceiling and nothing lands on it. The padding is computed rather than guessed, and the text is
  // committed verbatim so the byte count survives regeneration.
  const world = glyphWorld();
  const empty = `${JSON.stringify(document([glyphRecord(world, { note: "" })]), null, 2)}\n`;
  const padding = MAX_DOCUMENT_BYTES - Buffer.byteLength(empty, "utf8");
  if (padding < 0) throw new Error("the glyph record no longer fits inside the document ceiling");
  const record = glyphRecord(world, { note: "x".repeat(padding) });
  const text = `${JSON.stringify(document([record]), null, 2)}\n`;
  if (Buffer.byteLength(text, "utf8") !== MAX_DOCUMENT_BYTES) {
    throw new Error(`document is ${Buffer.byteLength(text, "utf8")} bytes, not the ceiling`);
  }
  addCase({
    id: "document-at-byte-cap",
    title: "A document of exactly 1 MiB",
    why:
      "Inclusive, like every other cap in `v1` — 1 MiB is legal and one byte more refuses. Its " +
      "absence was reachable: an engine comparing `>=` against the ceiling refuses this document " +
      "and passes every other case, because the over-cap fixture overshoots by the whole of its " +
      "surrounding JSON. The acceptance is an ordinary valid one, so the case pins that the " +
      "document is *evaluated*, not merely not-refused.",
    document: null,
    documentText: text,
    files: glyphFiles(world, record),
    comparison: glyphComparison(world),
    expected: {
      pins: ["statuses", "validationFailures"],
      statuses: { "m3-iconbutton-tonal-glyph": { status: "valid" } },
      validationFailures: [],
    },
  });
}

{
  // **Bytes, not characters** — the same ceiling reached with a multibyte `note`.
  const world = glyphWorld();
  // `\u4e2d` is three bytes in UTF-8 and one UTF-16 code unit, so 400,000 of them are 1.2 MB
  // encoded while the string is 400,000 characters long — comfortably inside the ceiling under
  // either of the two wrong readings.
  const record = glyphRecord(world, { note: "\u4e2d".repeat(400_000) });
  addCase({
    id: "document-over-byte-cap-multibyte",
    title: "A document past the ceiling in bytes but not in characters",
    why:
      "Both other document-size cases pad with ASCII, where UTF-8 byte length, JavaScript string " +
      "length and Kotlin string length are the same number — so an engine measuring characters or " +
      "UTF-16 code units passes them and then accepts a document whose encoded bytes are over the " +
      "cap. `maxDocumentBytes` is a count of **bytes**, which is what a reader has to bound before " +
      "it fetches, and this is the case that says so.",
    document: document([record]),
    files: glyphFiles(world, record),
    comparison: glyphComparison(world),
    expected: {
      pins: ["statusesAbsent", "validationFailures"],
      statusesAbsent: true,
      validationFailures: [{ reason: "document-too-large" }],
    },
  });
}

{
  // One acceptance, and a `note` padded past the document ceiling.
  const world = glyphWorld();
  const record = glyphRecord(world, { note: "x".repeat(1024 * 1024) });
  addCase({
    id: "document-over-byte-cap",
    title: "A document past the 1 MiB ceiling",
    why:
      "Bounded **before** parsing, for the reason the artifact reader is bounded before opening: " +
      "every other budget fires after something has already been materialised unless it is checked " +
      "first, and `JSON.parse` allocates the whole payload before the acceptance and raster caps can " +
      "see it. A document with one enormous string and a single acceptance reaches none of them. " +
      "The reader should refuse to fetch past the ceiling for the same reason `readArtifact` must; " +
      "this is the defence in depth behind it.",
    document: document([record]),
    files: glyphFiles(world, record),
    comparison: glyphComparison(world),
    expected: {
      pins: ["statusesAbsent", "validationFailures"],
      statusesAbsent: true,
      validationFailures: [{ reason: "document-too-large" }],
    },
  });
}

addCase({
  id: "document-unreadable-truncated",
  title: "Truncated JSON",
  why:
    "Neither `schema-invalid` nor `id-missing` fits: there is no record to name and no index to " +
    "fall back on. Without a token an engine is free to simply throw, which is not a result any " +
    "fixture can compare against.",
  documentText: '{"schema":"compose-preview-known-differences/v1","acceptances":[',
  document: null,
  files: {},
  expected: {
    pins: ["statusesAbsent", "validationFailures"],
    statusesAbsent: true,
    validationFailures: [{ reason: "document-unreadable" }],
  },
});

addCase({
  id: "document-unreadable-wrong-schema-token",
  title: "A document carrying a different schema token",
  why: "A wrong schema token is a file we cannot read, not a record we can refuse.",
  document: { schema: "compose-preview-known-differences/v2", acceptances: [] },
  files: {},
  expected: {
    pins: ["statusesAbsent", "validationFailures"],
    statusesAbsent: true,
    validationFailures: [{ reason: "document-unreadable" }],
  },
});

addCase({
  id: "document-unreadable-acceptances-not-array",
  title: "`acceptances` is an object",
  why: "Same shape of failure, and the one an engine that trusts its deserializer walks straight past.",
  document: { schema: "compose-preview-known-differences/v1", acceptances: {} },
  files: {},
  expected: {
    pins: ["statusesAbsent", "validationFailures"],
    statusesAbsent: true,
    validationFailures: [{ reason: "document-unreadable" }],
  },
});

{
  const world = glyphWorld();
  const base = glyphRecord(world);
  addCase({
    id: "document-duplicate-ids",
    title: "One id used three times and a second used twice",
    why:
      "`statuses` is keyed by id, so two records sharing one have a single slot between them — the " +
      "result structure cannot represent the input at all, which makes this a property of the file " +
      "rather than of either record. One entry per **distinct duplicated value**, ordered by that " +
      "id's **first** occurrence; this case separates all three readings of 'report the offending " +
      "id' at once.",
    document: document([
      { ...base, id: "alpha" },
      { ...base, id: "beta" },
      { ...base, id: "alpha" },
      { ...base, id: "beta" },
      { ...base, id: "alpha" },
    ]),
    files: glyphFiles(world, base),
    comparison: glyphComparison(world),
    expected: {
      pins: ["statusesAbsent", "validationFailures"],
      statusesAbsent: true,
      validationFailures: [
        { id: "alpha", reason: "duplicate-id" },
        { id: "beta", reason: "duplicate-id" },
      ],
    },
  });
}

{
  const world = glyphWorld();
  const base = glyphRecord(world);
  addCase({
    id: "document-id-missing",
    title: "Absent, blank, numeric and object ids",
    why:
      "All four forms are `id-missing`, in the `{index, reason}` shape — the record's position in " +
      "`acceptances[]` is the only handle left. 'Missing' names the absence of a usable key rather " +
      "than a literally absent field, which is the reading the index-shaped entry already forces.",
    document: document([
      (() => {
        const copy = { ...base };
        delete copy.id;
        return copy;
      })(),
      { ...base, id: "  " },
      { ...base, id: 42 },
      { ...base, id: { name: "glyph" } },
    ]),
    files: glyphFiles(world, base),
    comparison: glyphComparison(world),
    expected: {
      pins: ["statusesAbsent", "validationFailures"],
      statusesAbsent: true,
      validationFailures: [
        { index: 0, reason: "id-missing" },
        { index: 1, reason: "id-missing" },
        { index: 2, reason: "id-missing" },
        { index: 3, reason: "id-missing" },
      ],
    },
  });
}

{
  const world = glyphWorld();
  const base = glyphRecord(world);
  // The filler records carry an `id` and nothing else. `id-not-safe` is the first rung of the
  // per-record ladder, so nothing further about them is ever read — and a fixture that repeated a
  // full record 256 times would be a third of a megabyte of committed noise.
  const filler = [];
  for (let i = 0; i < 255; i++) filler.push({ id: `cap-${String(i).padStart(3, "0")}!` });
  addCase({
    id: "document-count-over-cap",
    title: "257 acceptances — one past the cap",
    why:
      "The count cap is checked alongside the duplicate-id scan, before any pixel buffer is " +
      "allocated. **Exceeds, not reaches**: its sibling sits on exactly 256 and is evaluated " +
      "normally, and a `>=` check would reject both and leave two engines free to disagree about " +
      "the case in between.",
    document: document([
      ...filler,
      { id: "cap-255!" },
      { id: "cap-256!" },
    ]),
    files: {},
    expected: {
      pins: ["statusesAbsent", "validationFailures"],
      statusesAbsent: true,
      validationFailures: [{ reason: "document-too-large" }],
    },
  });
  addCase({
    id: "document-count-at-cap",
    title: "256 acceptances — exactly the cap",
    why:
      "The accepting half of the boundary. Each record here is refused for its own (deliberately " +
      "unsafe) id, which is the cheap way to say 256 times over that the **document** was not " +
      "rejected: `statuses` is present and carries one entry per record.",
    document: document([...filler, { id: "cap-255!" }]),
    files: {},
    expected: {
      pins: ["statusesAbsent", "statusCounts", "validationFailureCount"],
      statusesAbsent: false,
      statusCounts: { refused: 256 },
      validationFailureCount: 256,
    },
  });
}

{
  const world = glyphWorld();
  const base = glyphRecord(world);
  addCase({
    id: "document-combined-failures",
    title: "A duplicated id, an unkeyable record and an over-cap count at once",
    why:
      "Combined document failures are where an ordering that exists only implicitly diverges. " +
      "Document-wide tokens lead, then identity: `document-too-large`, then `duplicate-id`, then " +
      "`id-missing` — and within one token, the record's index in `acceptances[]`.",
    document: document([
      { ...base, id: "twice" },
      (() => {
        const copy = { ...base };
        delete copy.id;
        return copy;
      })(),
      { ...base, id: "twice" },
      ...Array.from({ length: 254 }, (_, i) => ({ id: `pad-${i}!` })),
    ]),
    files: {},
    expected: {
      pins: ["statusesAbsent", "validationFailures"],
      statusesAbsent: true,
      validationFailures: [
        { reason: "document-too-large" },
        { id: "twice", reason: "duplicate-id" },
        { index: 1, reason: "id-missing" },
      ],
    },
  });
}

// --- the budget's rasters ------------------------------------------------------------------------

/** A header that declares `width × height` over an `IDAT` far too small to hold it. */
function lyingGreyPng(width, height) {
  return buildPng([
    ihdr({ width, height, colourType: COLOUR_GREY }),
    idat([new Uint8Array(1)]),
    chunk("IEND"),
  ]);
}

{
  // 8000 × 8000 twice is 128 megapixels exactly — the accepting half of the pixel boundary. The
  // headers lie so the fixture stays a few hundred bytes, which is the point: the budget is
  // computed from the *declared* dimensions, before anything is decoded. Past the budget the lie is
  // caught, and `header-invalid` is the token for it.
  const world = glyphWorld();
  const mask = lyingGreyPng(8000, 8000);
  const accepted = lyingGreyPng(8000, 8000);
  const record = glyphRecord(world, {
    maskSha256: sha256Hex(mask),
    acceptedCandidateSha256: sha256Hex(accepted),
  });
  addCase({
    id: "document-pixels-at-cap",
    title: "128 megapixels declared across the set — exactly the cap",
    why:
      "Inclusive, like every other cap here. The document is evaluated, and the record is then " +
      "refused for the header that got it there.",
    document: document([record]),
    files: {
      "artifacts/m3-iconbutton-tonal-glyph/mask.png": mask,
      "artifacts/m3-iconbutton-tonal-glyph/accepted-candidate.png": accepted,
      "canonical-reference.png": world.referencePngBytes,
      "canonical-candidate.png": world.candidatePngBytes,
    },
    comparison: glyphComparison(world),
    expected: refused(["header-invalid"]),
  });

  // **The first illegal aggregate, not merely an illegal one.** 8000 × 8001 overshoots the cap by
  // 8,000 pixels, so a consumer whose ceiling is wrong by anything up to 7,999 refuses this case and
  // passes the suite — the constant would be pinned only to within a raster's height. These four
  // declared rasters total 128,000,001: 64,000,000 + 63,992,000 + 8,000 + 1, one pixel past.
  const nearlyBig = lyingGreyPng(7999, 8000);
  const strip = lyingGreyPng(8000, 1);
  const dot = lyingGreyPng(1, 1);
  addCase({
    id: "document-pixels-over-cap",
    title: "128,000,001 declared across the set — the first total past the cap",
    why:
      "**Compare as you go and short-circuit.** Summing across a third-party set is exactly where " +
      "two engines diverge silently: a Kotlin accumulator can wrap into a value that sits under the " +
      "cap while JavaScript keeps a large positive `Number` and rejects, and the offline consumer " +
      "then allocates what the browser refused. Spread over two records and four rasters so the sum " +
      "is the thing being pinned rather than any single header, and landing on **cap + 1** exactly " +
      "so the fixture pins the constant rather than a neighbourhood of it.",
    document: document([
      glyphRecord(world, {
        maskSha256: sha256Hex(mask),
        acceptedCandidateSha256: sha256Hex(nearlyBig),
      }),
      glyphRecord(world, {
        id: "m3-iconbutton-tonal-glyph-second",
        maskSha256: sha256Hex(strip),
        acceptedCandidateSha256: sha256Hex(dot),
      }),
    ]),
    files: {
      "artifacts/m3-iconbutton-tonal-glyph/mask.png": mask,
      "artifacts/m3-iconbutton-tonal-glyph/accepted-candidate.png": nearlyBig,
      "artifacts/m3-iconbutton-tonal-glyph-second/mask.png": strip,
      "artifacts/m3-iconbutton-tonal-glyph-second/accepted-candidate.png": dot,
      "canonical-reference.png": world.referencePngBytes,
      "canonical-candidate.png": world.candidatePngBytes,
    },
    comparison: glyphComparison(world),
    expected: {
      pins: ["statusesAbsent", "validationFailures"],
      statusesAbsent: true,
      validationFailures: [{ reason: "document-too-large" }],
    },
  });
}

{
  // A reference raster that is **not** the recorded canonical plane, agreeing at every masked
  // coordinate under its own stride. Two pixels wide against three, one masked pixel at (0,0).
  const plane = { plane: "content-box", box: { x: 0, y: 0, width: 2, height: 1 } };
  const candidate = raster(2, 1);
  fillRect(candidate, { x: 0, y: 0, width: 1, height: 1 }, RED);
  // Three wide, and RED where the mask looks — so a comparison that trusts the stride agrees.
  const reference = raster(3, 1);
  fillRect(reference, { x: 0, y: 0, width: 1, height: 1 }, RED);
  const { png: mask, samples } = maskPng(2, 1, (paint) => paint({ x: 0, y: 0, width: 1, height: 1 }));
  const black = raster(2, 1);
  fillRect(black, { x: 0, y: 0, width: 1, height: 1 }, BLACK);
  const accepted = rgbaPng(crop(black, maskBox(samples, 2, 1)));
  const record = {
    id: "m3-mismatched-reference",
    issue: "https://github.com/yschimke/m3-catalog/issues/40",
    system: "m3",
    component: "IconButton/Tonal",
    previewId: "iconbutton-tonal__ideal__default__light",
    referenceId: "iconbutton-tonal-ideal-light",
    variant: "ideal/default/light",
    mask: "mask.png",
    acceptedCandidate: "accepted-candidate.png",
    referenceSha256: REFERENCE_SHA,
    maskSha256: sha256Hex(mask),
    acceptedCandidateSha256: sha256Hex(accepted),
    plane,
    candidateTolerance: 2,
    acceptedAt: "2026-08-22T00:00:00Z",
  };
  addCase({
    id: "gate-resolution-reference-dimensions-differ",
    title: "A canonical reference whose dimensions are not the recorded plane's",
    why:
      "The resolution test compares the candidate against the **reference** as a second full-plane " +
      "raster, and only the first of the two was ever measured against the mask. So a reference of " +
      "different dimensions was indexed with its own stride and could agree at every masked " +
      "coordinate while holding different pixels there: here a one-pixel mask lets a 2-wide " +
      "candidate 'resolve' against a 3-wide reference that is not the recorded plane at all. " +
      "`resolved` closes an issue, which makes it the worst verdict to reach by inferring a plane " +
      "from a stride — the acceptance is `invalidated` by its candidate instead.",
    document: document([record]),
    files: {
      "artifacts/m3-mismatched-reference/mask.png": mask,
      "artifacts/m3-mismatched-reference/accepted-candidate.png": accepted,
      "canonical-reference.png": rgbaPng(reference),
      "canonical-candidate.png": rgbaPng(candidate),
    },
    comparison: {
      system: "m3",
      component: "IconButton/Tonal",
      previewId: "iconbutton-tonal__ideal__default__light",
      referenceId: "iconbutton-tonal-ideal-light",
      variant: "ideal/default/light",
      overrides: {},
      referenceSha256: REFERENCE_SHA,
      plane,
      canonicalReference: "canonical-reference.png",
      canonicalCandidate: "canonical-candidate.png",
      tagIndex: {},
    },
    expected: {
      pins: ["statuses", "validationFailures"],
      statuses: {
        "m3-mismatched-reference": { status: "invalidated", causes: ["candidate-changed"] },
      },
      validationFailures: [],
    },
  });
}

{
  // The per-axis cap is a separate number because the area cap does not imply it: `1 × 128,000,000`
  // is inside the area budget and undecodable in every browser. This pair sits on 8192 and one past
  // it, and the accepting half is a genuinely valid acceptance rather than a near miss.
  const plane = { plane: "content-box", box: { x: 0, y: 0, width: 8192, height: 1 } };
  const reference = raster(8192, 1);
  fillRect(reference, { x: 0, y: 0, width: 4, height: 1 }, BLACK);
  const candidate = raster(8192, 1);
  fillRect(candidate, { x: 0, y: 0, width: 4, height: 1 }, RED);
  const { png: mask, samples } = maskPng(8192, 1, (paint) => paint({ x: 0, y: 0, width: 4, height: 1 }));
  const accepted = rgbaPng(crop(candidate, maskBox(samples, 8192, 1)));
  const record = {
    id: "m3-wide-strip",
    issue: "https://github.com/yschimke/m3-catalog/issues/40",
    system: "m3",
    component: "IconButton/Tonal",
    previewId: "iconbutton-tonal__ideal__default__light",
    referenceId: "iconbutton-tonal-ideal-light",
    variant: "ideal/default/light",
    mask: "mask.png",
    acceptedCandidate: "accepted-candidate.png",
    referenceSha256: REFERENCE_SHA,
    maskSha256: sha256Hex(mask),
    acceptedCandidateSha256: sha256Hex(accepted),
    plane,
    candidateTolerance: 2,
    acceptedAt: "2026-08-22T00:00:00Z",
  };
  addCase({
    id: "document-axis-at-cap",
    title: "A raster exactly 8192 px on its long axis",
    why: "Legal, and evaluated normally — the accepting half of the axis boundary.",
    document: document([record]),
    files: {
      "artifacts/m3-wide-strip/mask.png": mask,
      "artifacts/m3-wide-strip/accepted-candidate.png": accepted,
      "canonical-reference.png": rgbaPng(reference),
      "canonical-candidate.png": rgbaPng(candidate),
    },
    comparison: {
      system: "m3",
      component: "IconButton/Tonal",
      previewId: "iconbutton-tonal__ideal__default__light",
      referenceId: "iconbutton-tonal-ideal-light",
      variant: "ideal/default/light",
      overrides: {},
      referenceSha256: REFERENCE_SHA,
      plane,
      canonicalReference: "canonical-reference.png",
      canonicalCandidate: "canonical-candidate.png",
      tagIndex: {},
    },
    expected: {
      pins: ["statuses", "validationFailures"],
      statuses: { "m3-wide-strip": { status: "valid" } },
      validationFailures: [],
    },
  });

  const world = glyphWorld();
  const wide = lyingGreyPng(8193, 1);
  addCase({
    id: "document-axis-over-cap",
    title: "A raster 8193 px on its long axis",
    why:
      "8192 clears every mainstream engine's canvas limit with room to spare and is still an order " +
      "of magnitude above any plausible canonical plane. Past it the browser reports a decode " +
      "failure for bytes the offline decoder evaluates normally — the divergence class this whole " +
      "budget exists to prevent, reached through a shape rather than a size.",
    document: document([
      glyphRecord(world, {
        maskSha256: sha256Hex(wide),
        acceptedCandidateSha256: sha256Hex(world.acceptedPngBytes),
      }),
    ]),
    files: {
      "artifacts/m3-iconbutton-tonal-glyph/mask.png": wide,
      "artifacts/m3-iconbutton-tonal-glyph/accepted-candidate.png": world.acceptedPngBytes,
      "canonical-reference.png": world.referencePngBytes,
      "canonical-candidate.png": world.candidatePngBytes,
    },
    comparison: glyphComparison(world),
    expected: {
      pins: ["statusesAbsent", "validationFailures"],
      statusesAbsent: true,
      validationFailures: [{ reason: "document-too-large" }],
    },
  });
}

{
  // 8 MiB + 1 byte. The padding is a synthesis instruction rather than a committed blob: any runtime
  // can materialise it from the recipe — pad this base file to this many bytes — and the repo stores
  // a few hundred bytes instead of eight megabytes twice over. The padding goes **inside the
  // compressed stream** (empty stored deflate blocks and zero-length `IDAT` chunks, see
  // {@link padPngTo}), so the artifact stays a PNG a strict decoder accepts and decodes to exactly
  // the image its base does. An earlier recipe appended zero bytes after `IEND`, which is cheaper
  // and wrong: `IEND` ends the datastream, so those bytes bypass the allowlist and every CRC.
  const world = glyphWorld();
  const base = encodePng({ width: 24, height: 24, colourType: COLOUR_GREY, samples: (() => {
    const samples = new Uint8Array(24 * 24);
    for (let y = 8; y < 16; y++) for (let x = 8; x < 16; x++) samples[y * 24 + x] = 255;
    return samples;
  })() });
  const target = 8 * 1024 * 1024 + 1;
  const materialised = padPngTo(base, target);
  const atCap = padPngTo(base, 8 * 1024 * 1024);
  addCase({
    id: "artifact-at-byte-cap",
    title: "A mask of exactly 8 MiB encoded",
    why:
      "The accepting half of the encoded-byte boundary, and the one cap whose inclusive side the " +
      "suite had left unpinned — the count, axis and pixel caps all carry both halves. Without it a " +
      "runtime rejecting with `>=` passes every committed case while refusing an artifact `v1` calls " +
      "legal. Same synthesis recipe as its sibling, one byte shorter.",
    document: document([
      glyphRecord(world, {
        maskSha256: sha256Hex(atCap),
        acceptedCandidateSha256: sha256Hex(world.acceptedPngBytes),
      }),
    ]),
    files: {
      "artifacts/m3-iconbutton-tonal-glyph/mask.base.png": base,
      "artifacts/m3-iconbutton-tonal-glyph/accepted-candidate.png": world.acceptedPngBytes,
      "canonical-reference.png": world.referencePngBytes,
      "canonical-candidate.png": world.candidatePngBytes,
    },
    synthesize: [
      {
        path: "artifacts/m3-iconbutton-tonal-glyph/mask.png",
        from: "artifacts/m3-iconbutton-tonal-glyph/mask.base.png",
        padTo: 8 * 1024 * 1024,
      },
    ],
    comparison: glyphComparison(world),
    expected: {
      pins: ["statuses", "validationFailures"],
      statuses: { "m3-iconbutton-tonal-glyph": { status: "valid" } },
      validationFailures: [],
    },
  });

  addCase({
    id: "artifact-too-large",
    title: "A mask one byte past 8 MiB encoded",
    why:
      "Dimensions do not bound file size: PNG compression varies by orders of magnitude with " +
      "content, and an acceptance's rasters are exactly the noisy sub-regions that compress worst. " +
      "The cap sits comfortably under `ServeCatalogStore`'s own 25 MB fetch limit, so the two " +
      "engines agree well before the host's fetch would fail, and far above a real mask or crop.",
    document: document([
      glyphRecord(world, {
        maskSha256: sha256Hex(materialised),
        acceptedCandidateSha256: sha256Hex(world.acceptedPngBytes),
      }),
    ]),
    files: {
      "artifacts/m3-iconbutton-tonal-glyph/mask.base.png": base,
      "artifacts/m3-iconbutton-tonal-glyph/accepted-candidate.png": world.acceptedPngBytes,
      "canonical-reference.png": world.referencePngBytes,
      "canonical-candidate.png": world.candidatePngBytes,
    },
    synthesize: [
      {
        path: "artifacts/m3-iconbutton-tonal-glyph/mask.png",
        from: "artifacts/m3-iconbutton-tonal-glyph/mask.base.png",
        padTo: target,
      },
    ],
    comparison: glyphComparison(world),
    expected: refused(["artifact-too-large"]),
  });
}

// --- identity and paths --------------------------------------------------------------------------

glyphValidation({
  id: "id-not-safe-proto",
  title: "An `id` of `__proto__`",
  why:
    "`__proto__` is a perfectly good path segment and a catastrophic object key: `statuses[id] = …` " +
    "in the browser mutates the prototype instead of creating the own-property the contract " +
    "requires, while the offline map stores it normally. Two defences — the reserved names are " +
    "rejected, **and** the browser builds `statuses` as a `Map` or a null-prototype object — and " +
    "this fixture makes an implementation using `{}` fail visibly rather than silently.",
  record: { id: "__proto__" },
  expected: refused(["id-not-safe"], "__proto__"),
});

glyphValidation({
  id: "id-not-safe-single-dot",
  title: "An `id` of `.` reaching a sibling's `mask.png`",
  why:
    "`.` is the one that reads as harmless: no separator, every character in the class, and not the " +
    "`..` everyone checks for — yet `known-differences/./` normalises to the root itself, so a " +
    "`mask` of `some-other-id/mask.png` is genuinely contained and the containment check passes. " +
    "One acceptance can then address every sibling's artifacts.",
  record: { id: ".", mask: "m3-iconbutton-tonal-glyph/mask.png" },
  expected: refused(["id-not-safe"], "."),
});

glyphValidation({
  id: "id-not-safe-parent-dot",
  title: "An `id` of `..`",
  why: "The half a `..`-only check does catch, kept as the sibling of the `.` case above.",
  record: { id: ".." },
  expected: refused(["id-not-safe"], ".."),
});

glyphValidation({
  id: "id-not-safe-separator",
  title: "An `id` carrying a path separator",
  why:
    "Checking a child path against `known-differences/<id>/` is worthless if `<id>` can move that " +
    "directory: `mask.png` is then perfectly contained within the escaped location.",
  record: { id: "m3/glyph" },
  expected: refused(["id-not-safe"], "m3/glyph"),
});

glyphValidation({
  id: "path-not-contained-case-folded-collision",
  title: "Two artifact paths differing only in case",
  why:
    "`mask.png` beside `MASK.PNG` is two committed files on Linux and one file on Windows and on a " +
    "default macOS filesystem, so the record either hashes the wrong bytes or cannot be checked out " +
    "intact. The identical failure the case-folded **id** check prevents, one level down — the " +
    "portable-identity rule has to apply wherever a name becomes a path, not only to the directory.",
  record: { acceptedCandidate: "MASK.PNG" },
  expected: refused(["path-not-contained"]),
});

{
  const world = glyphWorld();
  const record = glyphRecord(world, { acceptedAt: "2026-08-22T00:00:00.5Z" });
  addCase({
    id: "accepted-at-fractional-seconds",
    title: "An `acceptedAt` carrying a fractional second",
    why:
      "RFC 3339's `time-secfrac` is optional, and every other accepting timestamp here — including " +
      "the lowercase-separator and offset cases — is written to whole seconds. So a parser that " +
      "simply omits the production passes the entire suite and then refuses a legal timestamp, " +
      "which is the same wrong-verdict-on-valid-input direction the uppercase-only pattern was.",
    document: document([record]),
    files: glyphFiles(world, record),
    comparison: glyphComparison(world),
    expected: {
      pins: ["statuses", "validationFailures"],
      statuses: { "m3-iconbutton-tonal-glyph": { status: "valid" } },
      validationFailures: [],
    },
  });
}

{
  // **A path that resolves to a directory.** Contained, spelled exactly right, and still not an
  // artifact — so the refusal belongs to the *open*, not to containment. Reporting
  // `path-not-contained` here would be a wrong token for a path that is contained, and it is the
  // one shape of read failure the tree never reached: the missing-file case never resolves at all,
  // and the case-differs one cannot run on a case-sensitive checkout. A directory reproduces
  // everywhere.
  const world = glyphWorld();
  const record = glyphRecord(world);
  const files = glyphFiles(world, record);
  const flat = "artifacts/m3-iconbutton-tonal-glyph/mask.png";
  if (!files[flat]) throw new Error("the glyph mask is no longer committed at its flat path");
  // The mask's own bytes, one level further down: the name the record addresses becomes a directory
  // holding them rather than the file itself.
  files[`${flat}/scanline.png`] = files[flat];
  delete files[flat];
  addCase({
    id: "artifact-unreadable-path-is-a-directory",
    title: "A mask path that resolves to a directory",
    why:
      "The reader owns this refusal — a lexical grammar cannot tell a file from a directory, and " +
      "neither can a path check. `artifact-unreadable` is the token for a contained, correctly " +
      "spelled path that will not open, which is exactly what this is; `path-not-contained` would " +
      "say the opposite of what happened.",
    document: document([record]),
    files,
    comparison: glyphComparison(world),
    expected: refused(["artifact-unreadable"]),
  });
}

{
  // **A nested artifact path that is actually resolved.** `v1` permits safe segments joined by `/`,
  // but the only record in the tree whose path contains one has the id `.`, so identity validation
  // refuses it before the path is ever resolved — leaving "reject every nested path" and "resolve it
  // against the wrong directory" both passing. This one is nested *and* legal, so it has to resolve.
  const world = glyphWorld();
  const record = glyphRecord(world, { mask: "masks/tonal/glyph.png" });
  // The helper spells the mask at the flat path its own record uses, so the committed bytes have to
  // be moved to where *this* record addresses them — otherwise the case would pass or fail on a
  // missing file rather than on the nesting.
  const files = glyphFiles(world, record);
  const flat = "artifacts/m3-iconbutton-tonal-glyph/mask.png";
  if (!files[flat]) throw new Error("the glyph mask is no longer committed at its flat path");
  files[`artifacts/m3-iconbutton-tonal-glyph/${record.mask}`] = files[flat];
  delete files[flat];
  addCase({
    id: "artifact-path-nested-directories",
    title: "A mask stored below a nested directory inside its acceptance",
    why:
      "The accepting half of the artifact-path grammar. Its refusing siblings pin what a path may " +
      "not be — `..`, an absolute path, a reserved or case-colliding segment — and every one of " +
      "them is satisfied by an engine that simply refuses any path containing `/`. Resolution is " +
      "against `<root>/<id>/`, so a nested path also distinguishes that from resolution against " +
      "the artifacts root, which would find nothing here.",
    document: document([record]),
    files,
    comparison: glyphComparison(world),
    expected: {
      pins: ["statuses", "validationFailures"],
      statuses: { "m3-iconbutton-tonal-glyph": { status: "valid" } },
      validationFailures: [],
    },
  });
}

{
  // **One path in both fields is not a collision.** The case-folded artifact-path rule exists for
  // `mask.png` beside `MASK.PNG` — two spellings, one file on a case-insensitive checkout. An
  // *identical* path is one spelling of one file: it collides with nothing and escapes nowhere, so
  // spending `path-not-contained` on it refuses a record whose paths are contained and takes the
  // refusal away from what is actually wrong. Here that is the mask: an RGBA image is not a binary
  // mask, and `mask-encoding-invalid` is the token that says so.
  const base = glyphRecord(glyphWorld());
  glyphValidation({
    id: "mask-and-candidate-share-one-path",
    title: "A record naming the same artifact as both its mask and its accepted candidate",
    why:
      "The refusal must be attributed to the real defect, not to containment. Without this case the " +
      "case-folded path check is indistinguishable from one that also refuses identical paths — and " +
      "that reading reports a containment failure for a file sitting exactly where it belongs.",
    record: { mask: base.acceptedCandidate, maskSha256: base.acceptedCandidateSha256 },
    expected: refused(["mask-encoding-invalid"]),
  });
}

{
  const world = glyphWorld();
  const record = glyphRecord(world, { acceptedAt: "2026-08-22t00:00:00z" });
  addCase({
    id: "accepted-at-lowercase-separators",
    title: "An `acceptedAt` using lowercase `t` and `z`",
    why:
      "RFC 3339 says the `T` and the `Z` are case-insensitive, so an uppercase-only pattern refuses " +
      "a legal timestamp — a **wrong verdict** on valid input rather than a missing check, and the " +
      "direction that is easy to miss when the rule is written as a tightening.",
    document: document([record]),
    files: glyphFiles(world, record),
    comparison: glyphComparison(world),
    expected: {
      pins: ["statuses", "validationFailures"],
      statuses: { "m3-iconbutton-tonal-glyph": { status: "valid" } },
      validationFailures: [],
    },
  });
}

glyphValidation({
  id: "schema-invalid-issue-url-untrimmed",
  title: "An `issue` with surrounding whitespace",
  why:
    "`new URL` tolerates surrounding whitespace and the schema's `format: \"uri\"` does not, so " +
    "trimming before parsing accepts bytes a schema-first consumer refuses — the same divergence " +
    "this module already closes for unknown properties, reintroduced by a convenience.",
  record: { issue: " https://github.com/yschimke/m3-catalog/issues/40 " },
  expected: refused(["schema-invalid"]),
});

{
  const world = glyphWorld();
  const record = glyphRecord(world);
  delete record.acceptedAt;
  addCase({
    id: "accepted-at-absent-is-legal",
    title: "A record with no `acceptedAt` at all",
    why:
      "`acceptedAt` is optional in the schema and in the validator, and every *other* fixture that " +
      "reaches a non-refused status happens to carry one — so an engine that made it mandatory " +
      "passed the whole suite while rejecting legal hand-authored acceptances, which is precisely " +
      "the population `v1` expects to be written by hand. The optional fields need accepting cases " +
      "as much as the required ones need refusing cases.",
    document: document([record]),
    files: glyphFiles(world, record),
    comparison: glyphComparison(world),
    expected: {
      pins: ["statuses", "validationFailures"],
      statuses: { "m3-iconbutton-tonal-glyph": { status: "valid" } },
      validationFailures: [],
    },
  });
}

glyphValidation({
  id: "schema-invalid-accepted-at-calendar-impossible",
  title: "An `acceptedAt` whose fields are each legal and whose date does not exist",
  why:
    "Its sibling `schema-invalid-accepted-at-impossible-date` puts every field out of range at once, " +
    "so an engine that merely bounds each field passes it — and then accepts `2026-02-30`, which is " +
    "the shape a real off-by-one produces. Every field here is individually legal; only the calendar " +
    "rejects it, which is why the check is a round trip through the date rather than four range " +
    "tests.",
  record: { acceptedAt: "2026-02-30T12:00:00Z" },
  expected: refused(["schema-invalid"]),
});

glyphValidation({
  id: "schema-invalid-accepted-at-leap-second-off-instant",
  title: "A second `60` away from the leap-second instant",
  why:
    "RFC 3339 admits `60` for exactly one instant — `23:59:60` **UTC** — so `2026-01-01T12:00:60Z` " +
    "matches the grammar and is not a date-time, and a strict consumer refuses a record this " +
    "evaluator would otherwise gate. Deliberately *not* a check that a leap second was really " +
    "inserted then: that needs the IERS table, which grows by announcement and cannot live in a " +
    "committed contract. This asks only whether the instant is one where a leap second could be " +
    "inserted, which is a property of the clock — so every real leap second stays legal, including " +
    "one announced after this was written.",
  record: { acceptedAt: "2026-01-01T12:00:60Z" },
  expected: refused(["schema-invalid"]),
});

glyphValidation({
  id: "accepted-at-leap-second-at-instant",
  title: "A real leap second, and one reached through an offset",
  why:
    "The accepting half, and it has to be here or the rule above becomes 'refuse `60`' by " +
    "accident — which would reject a legal timestamp, the failure mode the case-insensitive `T` and " +
    "`Z` fix already had to undo once. `2016-12-31T23:59:60Z` is a leap second that happened. The " +
    "offset arithmetic is what makes the rule non-trivial: `2017-01-01T08:59:60+09:00` is the same " +
    "instant written in Tokyo time, so the check has to convert before it looks.",
  record: { acceptedAt: "2016-12-31T23:59:60Z" },
  expected: {
    pins: ["statuses", "validationFailures"],
    statuses: { "m3-iconbutton-tonal-glyph": { status: "valid" } },
    validationFailures: [],
  },
});

glyphValidation({
  id: "accepted-at-leap-second-through-offset",
  title: "A leap second written in a non-UTC offset",
  why:
    "`2017-01-01T08:59:60+09:00` is `2016-12-31T23:59:60Z` — the same instant, spelled in Tokyo " +
    "time. An implementation that checks the *local* clock reads `08:59` and refuses it; one that " +
    "converts first accepts it. Its sibling above cannot tell those apart, because at `Z` the local " +
    "clock and UTC are the same reading.",
  record: { acceptedAt: "2017-01-01T08:59:60+09:00" },
  expected: {
    pins: ["statuses", "validationFailures"],
    statuses: { "m3-iconbutton-tonal-glyph": { status: "valid" } },
    validationFailures: [],
  },
});

glyphValidation({
  id: "schema-invalid-accepted-at-leap-second-off-month-end",
  title: "A leap second at the right time of day on the wrong day",
  why:
    "`2026-01-01T23:59:60Z` reads `23:59` UTC and is still not a leap-second instant: RFC 3339 " +
    "admits `:60` at the end of a UTC **month**, and 1 January is the start of one. Its sibling " +
    "`…-off-instant` only moves the *time of day*, so an engine checking the clock and not the " +
    "calendar passes that case and admits `:60` on 334 days of the year. Month-end is as static as " +
    "the time of day — no table, nothing to keep current — so both halves are checkable.",
  record: { acceptedAt: "2026-01-01T23:59:60Z" },
  expected: refused(["schema-invalid"]),
});

glyphValidation({
  id: "accepted-at-leap-second-month-end-june",
  title: "A leap second at the end of June",
  why:
    "The accepting half of the month-end rule, and not December — the two real leap seconds in the " +
    "tree are both 31 December, so an engine hardcoding that one date passes them and refuses the " +
    "other month the IERS actually uses. Nothing here needs a historical lookup: the rule is that " +
    "the instant *could* carry a leap second.",
  record: { acceptedAt: "2026-06-30T23:59:60Z" },
  expected: {
    pins: ["statuses", "validationFailures"],
    statuses: { "m3-iconbutton-tonal-glyph": { status: "valid" } },
    validationFailures: [],
  },
});

glyphValidation({
  id: "accepted-at-leap-second-negative-offset",
  title: "A leap second written in a negative offset",
  why:
    "`2016-12-31T18:59:60-05:00` is the same instant as `2016-12-31T23:59:60Z` — New York rather " +
    "than Tokyo. Its `+09:00` sibling cannot pin the sign: an engine that adds the offset " +
    "magnitude whichever way it points reaches `23:59:60` from `+09:00` by luck and lands on " +
    "`13:59:60` here, refusing a legal timestamp. Both signs, or the branch is untested.",
  record: { acceptedAt: "2016-12-31T18:59:60-05:00" },
  expected: {
    pins: ["statuses", "validationFailures"],
    statuses: { "m3-iconbutton-tonal-glyph": { status: "valid" } },
    validationFailures: [],
  },
});

glyphValidation({
  id: "schema-invalid-accepted-at-impossible-date",
  title: "An `acceptedAt` with the right shape and impossible values",
  why:
    "`2026-99-99T99:99:99Z` matches the punctuation and the digit counts and is not a date, so a " +
    "validator asserting the schema's `date-time` format refuses what a pattern check accepts — the " +
    "same gap the pattern was added to close, one level down. Shape is checked by the pattern; " +
    "meaning by a round trip through the calendar.",
  record: { acceptedAt: "2026-99-99T99:99:99Z" },
  expected: refused(["schema-invalid"]),
});

glyphValidation({
  id: "schema-invalid-accepted-at-not-a-timestamp",
  title: "An `acceptedAt` that is a string but not a date-time",
  why:
    "The schema declares `format: \"date-time\"`. JSON Schema treats `format` as an annotation by " +
    "default, so a consumer with assertion enabled rejects what a type-only check accepts — and " +
    "`acceptedAt` is a recorded fact, so a string that is not a timestamp is a producer bug either " +
    "way.",
  record: { acceptedAt: "not-a-date" },
  expected: refused(["schema-invalid"]),
});

glyphValidation({
  id: "path-not-contained-windows-reserved-name",
  title: "An artifact path segment Windows cannot open",
  why:
    "`CON.png` commits fine, evaluates fine on POSIX, and cannot be created under that name on " +
    "Windows at all — reserved device names apply with any extension. The offline engine then reads " +
    "a file the serving host reports as `artifact-unreadable`, which is exactly the divergence the " +
    "'contained **and** portable' rule exists to close. Containment was never the whole claim.",
  record: { mask: "CON.png" },
  expected: refused(["path-not-contained"]),
});

glyphValidation({
  id: "path-not-contained-trailing-dot",
  title: "An artifact path segment ending in a dot",
  why:
    "Windows silently strips a trailing dot, so two distinct committed names collapse onto one file " +
    "there. Same class as the reserved names, and the same token.",
  record: { acceptedCandidate: "accepted-candidate.png." },
  expected: refused(["path-not-contained"]),
});

glyphValidation({
  id: "id-not-safe-integer-like",
  title: "An `id` that is a canonical integer",
  why:
    "The same family of map-key hazard as `__proto__`: JavaScript orders canonical array-index keys " +
    "ahead of every other key and numerically among themselves, so a document listing `10` before " +
    "`2` serialises them the other way round while an ordered-map consumer keeps the input order. " +
    "`statuses` is a map and this contract promises it no ordering, so nothing is *wrong* today — " +
    "but the `id` is doing double duty as an identifier and a key, and a key whose behaviour depends " +
    "on the host language's property semantics is not one this schema should mint. Only canonical " +
    "integers are affected; `2024-fix` is unaffected.",
  record: { id: "10" },
  expected: refused(["id-not-safe"], "10"),
});

glyphValidation({
  id: "schema-invalid-box-far-edge-unsafe",
  title: "A box whose fields are safe but whose far edge is not",
  why:
    "The completion of the safe-integer rule, and the half that actually reaches a gate: every " +
    "measurement adds the edges — element displacement compares `x + width` against a baseline's — " +
    "and a sum of two safe integers need not be safe. `{x: 9007199254740990, width: 3}` and " +
    "`{x: 9007199254740990, width: 2}` round to the same JavaScript edge, so this engine measures no " +
    "displacement where an exact-integer consumer measures one: `valid` against `element-moved`, " +
    "from identical bytes.",
  record: {
    element: {
      kind: "tag",
      tag: "iconbutton-tonal-glyph",
      bounds: { x: 9007199254740990, y: 8, width: 3, height: 8 },
      tolerance: 0.1,
    },
  },
  expected: refused(["schema-invalid"]),
});

// **The accepting halves of the segment ceiling.** With only the two 256-character refusals
// committed, a consumer spelling the rule `segment.length >= 255` rejects legal ids and paths and
// still passes every case — the same `>=` hazard the budget caps each carry an at-the-limit fixture
// for. 255 is legal, and these two evaluate normally to prove it.
{
  const world = glyphWorld();
  const longId = "a".repeat(255);
  const record = glyphRecord(world, { id: longId });
  addCase({
    id: "id-at-segment-length-cap",
    title: "An `id` of exactly 255 bytes",
    why:
      "The inclusive half of the portable-segment rule: 255 is a legal filesystem component " +
      "everywhere this repository is checked out, so the record is evaluated like any other and " +
      "its artifacts are read from a directory of that name.",
    document: document([record]),
    files: glyphFiles(world, record),
    comparison: glyphComparison(world),
    expected: {
      pins: ["statuses", "validationFailures"],
      statuses: { [longId]: { status: "valid" } },
      validationFailures: [],
    },
  });
}

{
  const world = glyphWorld();
  const longMask = `${"m".repeat(251)}.png`;
  const record = glyphRecord(world, { mask: longMask });
  addCase({
    id: "path-at-segment-length-cap",
    title: "An artifact path segment of exactly 255 bytes",
    why: "The path half of the same inclusive boundary — extension included, since the filesystem counts the whole component.",
    document: document([record]),
    files: {
      [`artifacts/${record.id}/${longMask}`]: world.maskPngBytes,
      [`artifacts/${record.id}/accepted-candidate.png`]: world.acceptedPngBytes,
      "canonical-reference.png": world.referencePngBytes,
      "canonical-candidate.png": world.candidatePngBytes,
    },
    comparison: glyphComparison(world),
    expected: {
      pins: ["statuses", "validationFailures"],
      statuses: { "m3-iconbutton-tonal-glyph": { status: "valid" } },
      validationFailures: [],
    },
  });
}

glyphValidation({
  id: "id-not-safe-segment-too-long",
  title: "An `id` longer than a filesystem component",
  why:
    "256 allowed ASCII characters, and every filesystem a checkout plausibly lands on caps a *path " +
    "component* at 255 — ext4, APFS and NTFS alike. So a URL-backed consumer fetches and evaluates " +
    "this record happily while a normal `git checkout` cannot create the directory it names, and " +
    "the offline engine reports `artifact-unreadable` for bytes the serving host just validated. " +
    "Same host-versus-checkout divergence as the reserved names and the trailing dot, so it gets " +
    "the same token rather than a new one. 255 is legal and 256 refuses — the inclusive convention " +
    "the budget caps and the tolerance ranges already use.",
  record: { id: "a".repeat(256) },
  expected: refused(["id-not-safe"], "a".repeat(256)),
});

glyphValidation({
  id: "artifact-unreadable-case-differs",
  title: "A path whose casing is not the committed file's",
  why:
    "`MASK.png` against a committed `mask.png` is not a containment failure and not a grammar " +
    "failure — the path is perfectly portable and perfectly contained. It is a *resolution* " +
    "failure, and only on some hosts: a case-insensitive Windows or macOS filesystem opens the " +
    "file and evaluates the record, while a Linux checkout or a case-sensitive URL space cannot " +
    "find it. So the reader owes exact-case resolution the way it owes containment and a bounded " +
    "read, and reports the mismatch as a failed open rather than serving what it happened to find.",
  record: { mask: "MASK.png" },
  expected: refused(["artifact-unreadable"]),
});

glyphValidation({
  id: "path-not-contained-segment-too-long",
  title: "An artifact path segment longer than a filesystem component",
  why:
    "The path half of the same rule. Per *segment* and not per path on purpose: `PATH_MAX` is a " +
    "property of the reader's working directory rather than of the document, so a total-length rule " +
    "would make identical committed bytes legal in one checkout and refused in another — which is " +
    "the divergence, not a fix for it.",
  record: { mask: `${"m".repeat(252)}.png` },
  expected: refused(["path-not-contained"]),
});

glyphValidation({
  id: "id-not-safe-windows-reserved-name",
  title: "An `id` Windows cannot open",
  why:
    "The `id` is doing double duty as an identifier and a directory name, so it is held to the same " +
    "portability grammar as the paths beneath it.",
  record: { id: "nul" },
  expected: refused(["id-not-safe"], "nul"),
});

glyphValidation({
  id: "path-not-contained-backslash",
  title: "An artifact path containing a backslash",
  why:
    "Containment is not portability. `isSafeRelativePath` rewrites `\\` to `/` before splitting, so " +
    "`a\\b.png` is *checked* as two segments and *opened* as one filename on POSIX and as two on " +
    "Windows — the offline engine hashes one file while the host fetches another.",
  record: { mask: "sub\\mask.png" },
  expected: refused(["path-not-contained"]),
});

glyphValidation({
  id: "path-not-contained-hash",
  title: "An artifact path containing `#`",
  why:
    "`#` and `?` are ordinary filename characters that become fragment and query syntax the moment " +
    "the serving host fetches the artifact by URL rather than reading it off disk. " +
    "Percent-encoding rules would settle it and are one more thing to get differently right twice, " +
    "so the grammar is simply narrow.",
  record: { mask: "mask#1.png" },
  expected: refused(["path-not-contained"]),
});

glyphValidation({
  id: "path-not-contained-parent",
  title: "An artifact path leaving the acceptance's directory",
  why:
    "`mask` and `acceptedCandidate` resolve against `known-differences/<id>/` — not the repo root, " +
    "not the JSON file's location, not an implicit `.design-parity/`, because 'an ordinary relative " +
    "path' resolves to three different files under those three readings.",
  record: { mask: "../other/mask.png" },
  expected: refused(["path-not-contained"]),
});

glyphValidation({
  id: "path-not-contained-absolute",
  title: "An absolute artifact path",
  why: "These paths are read during staging on a host that fetches third-party catalogs, so a traversal is an escape from the artifact tree rather than a typo.",
  record: { acceptedCandidate: "/etc/passwd" },
  expected: refused(["path-not-contained"]),
});

// --- the mask's encoding -------------------------------------------------------------------------

{
  const world = glyphWorld();
  const rgbaMask = (() => {
    const image = raster(24, 24, [0, 0, 0, 255]);
    fillRect(image, { x: 8, y: 8, width: 8, height: 8 }, [255, 255, 255, 255]);
    return rgbaPng(image);
  })();
  glyphValidation({
    id: "mask-encoding-rgba-with-binary-samples",
    title: "An RGBA mask whose samples are strictly binary",
    why:
      "Precisely the file a sample-only check accepts. The browser's only decode path normalises " +
      "everything to 8-bit RGBA, so this sails through a value check while the offline engine, " +
      "decoding natively, sees a different type entirely. The encoding is therefore checked in the " +
      "`IHDR` — bit depth `8`, colour type `0` — before any decode.",
    record: { maskSha256: sha256Hex(rgbaMask) },
    files: { "artifacts/m3-iconbutton-tonal-glyph/mask.png": rgbaMask },
    expected: refused(["mask-encoding-invalid"]),
  });

  const paletteMask = (() => {
    const samples = new Uint8Array(24 * 24);
    for (let y = 8; y < 16; y++) for (let x = 8; x < 16; x++) samples[y * 24 + x] = 1;
    const rows = [];
    for (let y = 0; y < 24; y++) rows.push(samples.subarray(y * 24, (y + 1) * 24));
    return buildPng([
      ihdr({ width: 24, height: 24, colourType: COLOUR_PALETTE }),
      chunk("PLTE", Uint8Array.from([0, 0, 0, 255, 255, 255])),
      idat(rows),
      chunk("IEND"),
    ]);
  })();
  glyphValidation({
    id: "mask-encoding-palette-with-binary-samples",
    title: "An indexed mask whose palette entries are strictly binary",
    why:
      "The second file a sample-only check accepts, and the one whose failure only becomes visible " +
      "when a palette entry between the two values arrives. Refused in the same `IHDR` preflight " +
      "that yields `width × height`, so it lands on the same side of the budget as an unreadable " +
      "header: neither raster is charged.",
    record: { maskSha256: sha256Hex(paletteMask) },
    files: { "artifacts/m3-iconbutton-tonal-glyph/mask.png": paletteMask },
    expected: refused(["mask-encoding-invalid"]),
  });

  const softMask = (() => {
    const samples = new Uint8Array(24 * 24);
    for (let y = 8; y < 16; y++) for (let x = 8; x < 16; x++) samples[y * 24 + x] = 255;
    samples[8 * 24 + 8] = 128;
    return encodePng({ width: 24, height: 24, colourType: COLOUR_GREY, samples });
  })();
  glyphValidation({
    id: "mask-encoding-anti-aliased-sample",
    title: "A greyscale mask carrying one intermediate value",
    why:
      "Strictly binary rather than a threshold, because a threshold is one more constant two " +
      "engines could pick differently and an anti-aliased edge is exactly the boundary case the " +
      "separation rules work hardest to keep unambiguous. A producer with a soft-edged selection " +
      "must decide where the edge falls before committing it.",
    record: { maskSha256: sha256Hex(softMask) },
    files: { "artifacts/m3-iconbutton-tonal-glyph/mask.png": softMask },
    expected: refused(["mask-encoding-invalid"]),
  });

  const transparentMask = (() => {
    const samples = new Uint8Array(24 * 24);
    for (let y = 8; y < 16; y++) for (let x = 8; x < 16; x++) samples[y * 24 + x] = 255;
    const rows = [];
    for (let y = 0; y < 24; y++) rows.push(samples.subarray(y * 24, (y + 1) * 24));
    return buildPng([
      ihdr({ width: 24, height: 24, colourType: COLOUR_GREY }),
      chunk("tRNS", Uint8Array.from([0, 255])),
      idat(rows),
      chunk("IEND"),
    ]);
  })();
  glyphValidation({
    id: "mask-encoding-transparency",
    title: "A greyscale mask carrying `tRNS`",
    why:
      "The mask is greyscale with **no alpha**, and `tRNS` is how a greyscale PNG carries alpha " +
      "anyway — so this is the one place the chunk allowlist and the mask's own encoding rule " +
      "disagree, since `tRNS` is legitimately permitted on the accepted candidate. Left admitted, " +
      "the decode gives a matching sample alpha `0` while the coverage scan reads only the grey " +
      "channel: a transparent white pixel would suppress a comparison here and refuse the mask on a " +
      "consumer enforcing the no-alpha rule as written. Refused in the same `IHDR` preflight that " +
      "already decides the encoding.",
    record: { maskSha256: sha256Hex(transparentMask) },
    files: { "artifacts/m3-iconbutton-tonal-glyph/mask.png": transparentMask },
    expected: refused(["mask-encoding-invalid"]),
  });

  const emptyMask = encodePng({ width: 24, height: 24, colourType: COLOUR_GREY, samples: new Uint8Array(24 * 24) });
  glyphValidation({
    id: "mask-empty",
    title: "A mask that selects nothing",
    why:
      "An all-zero mask satisfies the encoding and dimension rules and still has no bounding box, " +
      "which leaves `accepted-candidate.png`'s required dimensions undefined — one engine treats it " +
      "as a harmless no-op, another refuses, a third throws while cropping.",
    record: { maskSha256: sha256Hex(emptyMask) },
    files: { "artifacts/m3-iconbutton-tonal-glyph/mask.png": emptyMask },
    expected: refused(["mask-empty"]),
  });
}

{
  const world = glyphWorld();
  const animated = buildPng([
    ihdr({ width: 24, height: 24, colourType: COLOUR_GREY }),
    chunk("acTL", Uint8Array.from([0, 0, 0, 2, 0, 0, 0, 0])),
    idat([new Uint8Array(24)]),
    chunk("IEND"),
  ]);
  glyphValidation({
    id: "animated-png-mask",
    title: "An animated mask",
    why:
      "An APNG is a PNG: signature, conforming `IHDR`, honest dimensions, a hash that verifies — " +
      "every other check accepts it, and then the two engines read different pixels out of it, " +
      "because a decoding library returns the `IDAT` default image while an `<img>` may advance the " +
      "animation. A mask that changes between frames is a suppression union that changes while you " +
      "look at it. Rejected rather than pinned to frame zero: a static acceptance artifact has no " +
      "use for frames, so the file is a mistake or an attack either way.",
    record: { maskSha256: sha256Hex(animated) },
    files: { "artifacts/m3-iconbutton-tonal-glyph/mask.png": animated },
    expected: refused(["animated-png"]),
  });

  const animatedColour = buildPng([
    ihdr({ width: 8, height: 8 }),
    chunk("acTL", Uint8Array.from([0, 0, 0, 2, 0, 0, 0, 0])),
    idat([new Uint8Array(32)]),
    chunk("IEND"),
  ]);
  // **Both headers were read, so both get to speak.** Gating the animation and mask-encoding checks
  // on *both* headers parsing makes a record's reason set depend on its sibling artifact: the APNG
  // here is perfectly detectable, its header parsed, and an engine that reports only
  // `header-invalid` has dropped evidence it already held. No other fixture pairs a header failure
  // with a *different* header-stage failure, so every one of them passes either way.
  {
    const truncatedMask = new Uint8Array([137, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13]);
    glyphValidation({
      id: "header-invalid-beside-animated-sibling",
      title: "An unreadable mask header beside an animated accepted candidate",
      why:
        "The reason set is exact and deduplicated per `(record, reason)` pair, and the second-read " +
        "stage already accumulates across both artifacts. This pins that the first preflight does " +
        "too — an engine short-circuiting on the first failed header reports one token where the " +
        "contract names two, and which one it drops depends on the order it happened to read them.",
      record: {
        maskSha256: sha256Hex(truncatedMask),
        acceptedCandidateSha256: sha256Hex(animatedColour),
      },
      files: {
        "artifacts/m3-iconbutton-tonal-glyph/mask.png": truncatedMask,
        "artifacts/m3-iconbutton-tonal-glyph/accepted-candidate.png": animatedColour,
      },
      expected: refused(["header-invalid", "animated-png"]),
    });
  }

  glyphValidation({
    id: "animated-png-accepted-candidate",
    title: "An animated accepted candidate",
    why: "Both rasters, not just the mask — the accepted candidate decides what the suppressed pixels may look like.",
    record: { acceptedCandidateSha256: sha256Hex(animatedColour) },
    files: { "artifacts/m3-iconbutton-tonal-glyph/accepted-candidate.png": animatedColour },
    expected: refused(["animated-png"]),
  });
}

// --- dimensions, hashes and readability -----------------------------------------------------------

{
  const world = glyphWorld();
  const wrongPlane = encodePng({
    width: 20,
    height: 20,
    colourType: COLOUR_GREY,
    samples: (() => {
      const samples = new Uint8Array(20 * 20);
      for (let y = 6; y < 14; y++) for (let x = 6; x < 14; x++) samples[y * 20 + x] = 255;
      return samples;
    })(),
  });
  glyphValidation({
    id: "dimension-mismatch-mask-against-plane",
    title: "A mask that is not the recorded plane's size",
    why:
      "`mask.png` must match the recorded canonical plane's `width × height` exactly. Otherwise one " +
      "consumer rescales, another rejects, a third compares only the overlap — same acceptance, " +
      "three different suppression unions.",
    record: { maskSha256: sha256Hex(wrongPlane) },
    files: { "artifacts/m3-iconbutton-tonal-glyph/mask.png": wrongPlane },
    expected: refused(["dimension-mismatch"]),
  });

  const wrongCrop = rgbaPng(raster(6, 6, RED));
  glyphValidation({
    id: "dimension-mismatch-accepted-against-mask-box",
    title: "An accepted candidate that is not the mask's bounding box",
    why: "The other half of the same rule: the crop is stored in the canonical plane, at the mask's bounding box, exactly.",
    record: { acceptedCandidateSha256: sha256Hex(wrongCrop) },
    files: { "artifacts/m3-iconbutton-tonal-glyph/accepted-candidate.png": wrongCrop },
    expected: refused(["dimension-mismatch"]),
  });

  glyphValidation({
    id: "hash-mismatch-accepted-candidate-only",
    title: "Only the accepted candidate fails its recorded hash",
    why:
      "The mask-only mismatch and the both-artifacts case leave one shape uncovered, and it is the " +
      "one a real corrupt crop takes. An engine that reports `mask-hash-mismatch` whenever *either* " +
      "hash fails satisfies both of those expectations and emits the wrong reason set here — the " +
      "exact set is what the contract pins, so each artifact needs its own single-failure case.",
    record: { acceptedCandidateSha256: "c".repeat(64) },
    expected: refused(["accepted-candidate-hash-mismatch"]),
  });

  glyphValidation({
    id: "hash-mismatch-both-artifacts",
    title: "Both artifacts fail their recorded hash",
    why:
      "A mask we cannot trust is a broken artifact rather than a stale one, so this is a **hard " +
      "validation failure** and not an invalidation that degrades to 'compare normally'. Both " +
      "tokens are reported: `reasons` is an array for the same reason `causes` is, and a " +
      "single-value field would leave two engines free to pick different ones.",
    record: { maskSha256: "a".repeat(64), acceptedCandidateSha256: "b".repeat(64) },
    expected: refused(["mask-hash-mismatch", "accepted-candidate-hash-mismatch"]),
  });

  glyphValidation({
    id: "hash-recorded-uppercase",
    title: "An uppercase **recorded** hash",
    why:
      "The sibling of `gate-served-hash-uppercase`, and they must not be collapsed: we cannot " +
      "constrain what a producer publishes upstream, but we can refuse two spellings of our own " +
      "fields. One engine lowercasing a recorded hash and accepting it while another rejects is a " +
      "divergence produced by the validator itself.",
    record: { maskSha256: sha256Hex(glyphWorld().maskPngBytes).toUpperCase() },
    expected: refused(["schema-invalid"]),
  });

  glyphValidation({
    id: "artifact-unreadable-missing-file",
    title: "A path that resolves to no file at all",
    why:
      "Contained and syntactically perfect while the file is missing — at which point there are no " +
      "bytes to hash, no header to parse and no decode to attempt, so none of the other tokens " +
      "apply. Left unnamed, the browser turns a failed fetch into a local refusal while the offline " +
      "reader throws or silently drops the record.",
    record: { mask: "absent.png" },
    expected: refused(["artifact-unreadable"]),
  });

  const truncated = new Uint8Array([137, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13]);
  glyphValidation({
    id: "header-invalid-truncated-file",
    title: "A file that opens and holds too few bytes for an `IHDR`",
    why:
      "**Strictly a fetch/open/read failure** is what `artifact-unreadable` covers. A file that " +
      "*opens* and is merely truncated is not that: the preflight gets its hands on the bytes and " +
      "finds too few of them. The line is where the failure occurs, not how little data there " +
      "turned out to be — otherwise the same bytes are describable by both tokens and two engines " +
      "pick differently.",
    record: { maskSha256: sha256Hex(truncated) },
    files: { "artifacts/m3-iconbutton-tonal-glyph/mask.png": truncated },
    expected: refused(["header-invalid"]),
  });

  const corrupt = buildPng([
    ihdr({ width: 24, height: 24, colourType: COLOUR_GREY }),
    chunk("IDAT", Uint8Array.from([1, 2, 3, 4, 5, 6, 7, 8])),
    chunk("IEND"),
  ]);
  glyphValidation({
    id: "decode-failed-correctly-hashed-garbage",
    title: "A correctly hashed artifact that is not decodable",
    why:
      "A correct hash proves nobody edited the file, not that the file was ever valid. Left " +
      "undefined, one engine aborts the whole comparison and another silently drops the acceptance, " +
      "and neither produces the per-acceptance status the contract promises.",
    record: { maskSha256: sha256Hex(corrupt) },
    files: { "artifacts/m3-iconbutton-tonal-glyph/mask.png": corrupt },
    expected: refused(["decode-failed"]),
  });
}

// --- tolerances ------------------------------------------------------------------------------------

glyphValidation({
  id: "tolerance-candidate-at-ceiling",
  title: "`candidateTolerance` of exactly 8",
  why:
    "The bound is inclusive. `8` is the defensible upper end — the only real source of slack is the " +
    "single resample into the canonical plane, and it sits comfortably below the `LUMA_TOLERANCE = " +
    "16` at which the existing scorer already stops charging for a pixel at all.",
  record: { candidateTolerance: 8 },
  expected: {
    pins: ["statuses", "validationFailures"],
    statuses: { "m3-iconbutton-tonal-glyph": { status: "valid" } },
    validationFailures: [],
  },
});

glyphValidation({
  id: "tolerance-candidate-at-floor",
  title: "`candidateTolerance` of exactly 0",
  why:
    "The inclusive **lower** bound, which the suite had left unpinned on both tolerance fields while " +
    "pinning both ceilings. A consumer using `<= 0` would refuse a legal acceptance and still pass " +
    "every committed case. Zero is also the strictest useful authoring value — exact channel " +
    "equality inside the mask — so it is a shape a real record will take.",
  record: { candidateTolerance: 0 },
  expected: {
    pins: ["statuses", "validationFailures"],
    statuses: { "m3-iconbutton-tonal-glyph": { status: "valid" } },
    validationFailures: [],
  },
});

glyphValidation({
  id: "tolerance-element-at-floor",
  title: "`element.tolerance` of exactly 0",
  why: "The other half of the same omission: an element that must not have moved at all is a legal acceptance, not a refused one.",
  record: {
    element: { kind: "tag", tag: "iconbutton-tonal-glyph", bounds: { x: 8, y: 8, width: 8, height: 8 }, tolerance: 0 },
  },
  expected: {
    pins: ["statuses", "validationFailures"],
    statuses: { "m3-iconbutton-tonal-glyph": { status: "valid" } },
    validationFailures: [],
  },
});

glyphValidation({
  id: "tolerance-candidate-over-ceiling",
  title: "`candidateTolerance` of 9",
  why:
    "It is the one field an author can use to defeat the entire model: at the maximum channel " +
    "distance every future candidate matches, so the mask suppresses a missing glyph forever — " +
    "precisely the ignore rectangle the non-goals rule out, reached through a number rather than a " +
    "shape. A tolerance that needs to be large is evidence the acceptance is wrong.",
  record: { candidateTolerance: 9 },
  expected: refused(["tolerance-out-of-range"]),
});

glyphValidation({
  id: "tolerance-candidate-negative",
  title: "`candidateTolerance` of -1",
  why: "The other end of the range, because a range check that only guards the ceiling is half a check.",
  record: { candidateTolerance: -1 },
  expected: refused(["tolerance-out-of-range"]),
});

glyphValidation({
  id: "tolerance-candidate-fractional",
  title: "`candidateTolerance` of 0.5",
  why:
    "The integer requirement is normative and easy to drop: JSON has one number type, so `0.5` " +
    "sails through a range-only check in JavaScript and is rejected by a Kotlin `Int` field — a " +
    "cross-engine divergence produced by the validator itself. This is why the fixtures cover a " +
    "**fractional** value and not just the endpoints.",
  record: { candidateTolerance: 0.5 },
  expected: refused(["tolerance-out-of-range"]),
});

glyphValidation({
  id: "tolerance-element-over-ceiling",
  title: "`element.tolerance` of 0.3",
  why:
    "`0.25` is where the gate stops meaning anything: every edge may then move by a quarter of the " +
    "smaller baseline dimension, so the whole element can translate by that much and still be " +
    "judged to have stayed put — a 16 px icon that slid 4 px is not the element the mask was " +
    "authored over.",
  record: {
    element: { kind: "tag", tag: "iconbutton-tonal-glyph", bounds: { x: 8, y: 8, width: 8, height: 8 }, tolerance: 0.3 },
  },
  expected: refused(["tolerance-out-of-range"]),
});

glyphValidation({
  id: "tolerance-element-negative",
  title: "`element.tolerance` of -0.01",
  why: "Bounded **and** non-negative, for exactly the reason `candidateTolerance` is.",
  record: {
    element: { kind: "tag", tag: "iconbutton-tonal-glyph", bounds: { x: 8, y: 8, width: 8, height: 8 }, tolerance: -0.01 },
  },
  expected: refused(["tolerance-out-of-range"]),
});

// --- the two comparison-scoped refusals, the schema shape, and the orphan walk ---------------------

glyphValidation({
  id: "reference-hash-missing",
  title: "The targeted reference publishes no `sha256`",
  why:
    "Refused, not `invalidated: reference-changed`. The fingerprint gate compares a recorded hash " +
    "against a served one; with nothing to compare, the gate cannot run, and an acceptance whose " +
    "primary safety check is inoperable is a broken configuration rather than a stale one. " +
    "`reference-changed` reads as 'the design moved' — a fact about the world — while this is 'we " +
    "cannot tell', which needs a different fix and a different message.",
  comparison: { referenceSha256: "" },
  expected: refused(["reference-hash-missing"]),
});

{
  // The stored candidate already agrees with the reference inside the mask, so this acceptance
  // accepts a difference that does not exist.
  const world = glyphWorld({ candidateGlyph: BLACK, acceptedGlyph: BLACK });
  const record = glyphRecord(world);
  addCase({
    id: "acceptance-is-noop",
    title: "A stored candidate that already agrees with the reference",
    why:
      "Row 3's guard is not redundant: the resolution metric is permitted to be tolerant, so an " +
      "unchanged candidate can agree with `accepted-candidate.png` **and** with the reference " +
      "whenever the accepted delta was itself within tolerance. Without the check such a record is " +
      "simply `valid` and its mask joins the suppression union, hiding whatever later appears in " +
      "that region on the strength of an acceptance that never accepted anything. §7 records that " +
      "mask authoring is currently manual, so 'authoring rejects it' describes a step that does not " +
      "yet exist — the evaluator checks it directly.",
    document: document([record]),
    files: glyphFiles(world, record),
    comparison: glyphComparison(world),
    expected: refused(["acceptance-is-noop"]),
  });

  addCase({
    id: "acceptance-is-noop-yields-to-reference-changed",
    title: "A no-op acceptance whose reference has also moved",
    why:
      "Sequenced **after** the fingerprint gate. The no-op check compares the stored candidate " +
      "against the *served* reference, and the reference the acceptance was authored against is not " +
      "kept — so the moment the hash differs the predicate is being evaluated against the wrong " +
      "image, and refusal outranking everything would turn the correct `invalidated: " +
      "reference-changed` into `refused: acceptance-is-noop`.",
    document: document([record]),
    files: glyphFiles(world, record),
    comparison: glyphComparison(world, { referenceSha256: "4".repeat(64) }),
    expected: {
      pins: ["statuses", "validationFailures"],
      statuses: { "m3-iconbutton-tonal-glyph": { status: "invalidated", causes: ["reference-changed"] } },
      validationFailures: [],
    },
  });
}

glyphValidation({
  id: "schema-invalid-missing-issue",
  title: "A record with no `issue`",
  why:
    "The tracking issue is **mandatory** per acceptance — an acceptance nobody filed is an ignore " +
    "rectangle with a note attached. Present and valid `id`, so the failure is per-acceptance and " +
    "the rest of the document evaluates normally.",
  record: { issue: undefined },
  expected: refused(["schema-invalid"]),
});

glyphValidation({
  id: "schema-invalid-unparseable-issue",
  title: "An `issue` that is not a GitHub issue URL",
  why:
    "Issue identity is the canonical `owner/repo/number`, not the URL string: acceptances are " +
    "hand-authored, so the same issue arrives spelled several ways and aggregating on the raw " +
    "string splits those into groups that each look fully resolved. A URL that does not parse is " +
    "`schema-invalid` rather than its own group of one.",
  record: { issue: "see the tracker" },
  expected: refused(["schema-invalid"]),
});

glyphValidation({
  id: "schema-invalid-unknown-element-kind",
  title: "An `element.kind` this version does not define",
  why:
    "`v1` defines exactly one identifying kind. An earlier draft allowed a `producer` kind and it " +
    "is cut, because nothing can currently carry it — a selector kind with no authoring path is a " +
    "capability on paper only, and worse than absent because it reads as available.",
  record: {
    element: { kind: "producer", id: "figma:1:2", bounds: { x: 8, y: 8, width: 8, height: 8 }, tolerance: 0.1 },
  },
  expected: refused(["schema-invalid"]),
});

glyphValidation({
  id: "schema-invalid-box-beyond-safe-integer",
  title: "A box coordinate past the safe-integer range",
  why:
    "`9007199254740993` is already `…992` by the time a JSON parser hands it over, so an " +
    "`isInteger` check accepts a coordinate a Kotlin `Long` consumer retains exactly — two runtimes " +
    "reading one document as two different geometries. Refusing what cannot round-trip is cheaper " +
    "than reasoning about where the readings would first diverge, and the schema carries matching " +
    "bounds so a schema-first consumer reaches the same verdict.",
  record: {
    plane: { plane: "content-box", box: { x: 4, y: 4, width: 24, height: 9007199254740993 } },
  },
  expected: refused(["schema-invalid"]),
});

glyphValidation({
  id: "schema-invalid-missing-plane",
  title: "A record with no recorded canonical plane",
  why:
    "`normalisedBoxes` falls back to the full canvas below `MIN_BOX_COVERAGE`, so the plane's " +
    "definition can flip between 'content box' and 'full raster' depending on the candidate's " +
    "coverage — which the reference's `sha256` does not pin. Without the discriminant and the " +
    "resolved box the plane gate cannot be evaluated at all.",
  record: { plane: undefined },
  expected: refused(["schema-invalid"]),
});

{
  const world = glyphWorld();
  const catalog = {
    previews: [
      {
        system: "m3",
        id: "iconbutton-tonal__ideal__default__light",
        component: "IconButton/Filled",
        variant: "ideal/default/light",
        referenceIds: ["iconbutton-tonal-ideal-light"],
      },
    ],
  };
  glyphValidation({
    id: "orphaned-target-component-renamed",
    title: "The component was renamed while its ids stayed put",
    why:
      "The one case an id-existence walk passes. Scope matching uses the full locator, so *any* " +
      "recorded field diverging from the catalog makes the acceptance permanently unreachable: it " +
      "produces no status, appears in no dashboard, and survives every cleanup pass by being " +
      "invisible to all of them. The walk therefore resolves the preview **within its system**, " +
      "requires the resolved preview's component and axes to match, and requires the reference to " +
      "hang off *that* preview.",
    catalog,
    expected: refused(["orphaned-target"]),
  });

  glyphValidation({
    id: "orphaned-target-reference-detached",
    title: "The reference now hangs off a different preview",
    why: "A reference that exists but is attached elsewhere is as unreachable as one that was deleted.",
    catalog: {
      previews: [
        {
          system: "m3",
          id: "iconbutton-tonal__ideal__default__light",
          component: "IconButton/Tonal",
          variant: "ideal/default/light",
          referenceIds: ["iconbutton-tonal-ideal-dark"],
        },
      ],
    },
    expected: refused(["orphaned-target"]),
  });

  glyphValidation({
    id: "orphaned-target-variant-disagrees-with-preview-id",
    title: "A recorded `variant` that disagrees with its own `previewId`",
    why:
      "This reads as redundant — §2 derives `variant` from the preview id's own axis segments, so a " +
      "resolved preview always has the axes its id spells — and it is checked precisely because the " +
      "record's copy can disagree. That record matches nothing under full-scope matching either, " +
      "and a walk that skips the check because 'it must agree' leaves the one case where it does " +
      "not as the invisible kind.",
    record: { variant: "ideal/default/dark" },
    catalog: {
      previews: [
        {
          system: "m3",
          id: "iconbutton-tonal__ideal__default__light",
          component: "IconButton/Tonal",
          variant: "ideal/default/light",
          referenceIds: ["iconbutton-tonal-ideal-light"],
        },
      ],
    },
    expected: refused(["orphaned-target"]),
  });
}

{
  const world = glyphWorld();
  const base = glyphRecord(world);
  addCase({
    id: "document-duplicate-ids-case-folded",
    title: "Two ids differing only in case",
    why:
      "`foo` and `FOO` are distinct map keys and the **same directory** on Windows and on a default " +
      "macOS filesystem — so this document evaluates cleanly on Linux and, checked out anywhere " +
      "else, has two records reading one another's artifacts. It cannot even be checked out intact. " +
      "The `id` is doing double duty as an identifier and a path, and the path half decides whether " +
      "two records are really two. Reported under the **first spelling seen**, since that is the " +
      "position every engine has already reached at the moment it detects the collision.",
    document: document([
      { ...base, id: "m3-Glyph" },
      { ...base, id: "m3-glyph" },
    ]),
    files: glyphFiles(world, base),
    comparison: glyphComparison(world),
    expected: {
      pins: ["statusesAbsent", "validationFailures"],
      statusesAbsent: true,
      validationFailures: [{ id: "m3-Glyph", reason: "duplicate-id" }],
    },
  });
}

addCase({
  id: "document-unreadable-fractional-coordinate",
  title: "A geometry coordinate written as a non-integer",
  why:
    "`9007199254740991.1` is already `…991` by the time any check can look at it, so an " +
    "`isSafeInteger` gate accepts a coordinate a lossless consumer refuses as fractional — and the " +
    "far-edge rule made that reachable from *inside* the safe range rather than beyond it. No bound " +
    "closes the hole: at every magnitude some fractional literal sits nearer an integer than the " +
    "spacing of doubles there, so the token is checked as written and `x`, `y`, `width` and " +
    "`height` must be canonical JSON integers — no fraction, no exponent. `element.tolerance` is a " +
    "real number by design and is untouched.",
  documentText:
    '{"schema":"compose-preview-known-differences/v1","acceptances":[{"id":"a","element":' +
    '{"kind":"tag","tag":"t","bounds":{"x":9007199254740991.1,"y":8,"width":3,"height":8},' +
    '"tolerance":0.1}}]}',
  document: null,
  files: {},
  expected: {
    pins: ["statusesAbsent", "validationFailures"],
    statusesAbsent: true,
    validationFailures: [{ reason: "document-unreadable" }],
  },
});

addCase({
  id: "document-unreadable-duplicate-member-escaped",
  title: "A repeated member name spelled with an escape",
  why:
    "`\\u0069d` **is** `id` — JSON names are compared after unescaping — so this document repeats a " +
    "member exactly as its sibling does, and parsers can still choose different winners for the " +
    "pair. An engine scanning raw key tokens sees two different strings and passes the case, which " +
    "is why the sibling alone does not pin the unescaping step the rule actually requires.",
  documentText:
    '{"schema":"compose-preview-known-differences/v1","acceptances":' +
    '[{"id":"safe","\\u0069d":".."}]}',
  document: null,
  files: {},
  expected: {
    pins: ["statusesAbsent", "validationFailures"],
    statusesAbsent: true,
    validationFailures: [{ reason: "document-unreadable" }],
  },
});

addCase({
  id: "document-unreadable-duplicate-member",
  title: "An acceptance repeating a member name",
  why:
    "RFC 8259 leaves a repeated member name undefined and runtimes genuinely differ: V8 keeps the " +
    "last value, several keep the first, and strict parsers refuse the input. So this record " +
    "addresses the `safe` artifact directory under a JavaScript engine and `..` under one that " +
    "keeps the first — from byte-identical committed input, which is the single outcome a contract " +
    "two engines are written against cannot tolerate. The document is refused rather than " +
    "disambiguated, because there is no spelling of this file that both engines would agree on. " +
    "`document-unreadable` for the reason the unknown document property gets it: there is no record " +
    "to attribute it to. An engine that trusts its deserializer walks straight past this one — by " +
    "the time there is an object, the evidence is gone.",
  documentText:
    '{"schema":"compose-preview-known-differences/v1","acceptances":[{"id":"safe","id":".."}]}',
  document: null,
  files: {},
  expected: {
    pins: ["statusesAbsent", "validationFailures"],
    statusesAbsent: true,
    validationFailures: [{ reason: "document-unreadable" }],
  },
});

addCase({
  id: "document-unreadable-unknown-property",
  title: "A document carrying a property `v1` does not define",
  why:
    "The document level gets the same rule the record level does, and for the same reason: the " +
    "published schema declares `additionalProperties: false` at both, so a schema-first consumer " +
    "rejects bytes a required-fields-only consumer evaluates normally. `document-unreadable` rather " +
    "than `schema-invalid` because there is no record to attribute it to — this is a property of the " +
    "file.",
  document: { schema: "compose-preview-known-differences/v1", acceptances: [], extra: true },
  files: {},
  expected: {
    pins: ["statusesAbsent", "validationFailures"],
    statusesAbsent: true,
    validationFailures: [{ reason: "document-unreadable" }],
  },
});

// --- shapes that are not records, and fields v1 does not define -----------------------------------

{
  const world = glyphWorld();
  const base = glyphRecord(world);
  addCase({
    id: "document-non-object-acceptances",
    title: "`acceptances` holding `null`, a string and an array",
    why:
      "`acceptances` is third-party data and its entries need not be objects at all. All three are " +
      "`id-missing` — there is no usable key, so the record is identified by its position — and the " +
      "document is rejected. The case exists because the *evaluator* must not dereference what it " +
      "was handed on the way to that verdict: the pixel budget is reached from per-record " +
      "preflights, so those run even for a document already known to be doomed.",
    document: document([null, "an acceptance", [1, 2], base]),
    files: glyphFiles(world, base),
    comparison: glyphComparison(world),
    expected: {
      pins: ["statusesAbsent", "validationFailures"],
      statusesAbsent: true,
      validationFailures: [
        { index: 0, reason: "id-missing" },
        { index: 1, reason: "id-missing" },
        { index: 2, reason: "id-missing" },
      ],
    },
  });
}

glyphValidation({
  id: "schema-invalid-unknown-property",
  title: "A record carrying the `finding` field cut from `v1`",
  why:
    "`known-differences.schema.json` declares `additionalProperties: false`, so a consumer that runs " +
    "the schema first rejects bytes a consumer that validates only the required fields accepts — the " +
    "cross-runtime divergence manufactured by the validator itself. It is also what keeps the two " +
    "fields cut from `v1` cut: a `finding` matcher or a `producer` selector is refused rather than " +
    "silently ignored by one engine and acted on by a later one.",
  record: { finding: { kind: "color", token: "onSurfaceVariant" } },
  expected: refused(["schema-invalid"]),
});

// Written by hand rather than through `JSON.stringify`, which would re-emit the already-rounded
// `9007199254740991` and commit a fixture that exercises nothing.
function geometryNamedUnknownPropertyText() {
  const world = glyphWorld();
  const record = { ...glyphRecord(world), x: 0 };
  const text = JSON.stringify(document([record]), null, 2);
  const marked = text.replace('"x": 0\n', '"x": 9007199254740991.1\n');
  if (marked === text) throw new Error("the unknown `x` property did not survive serialisation");
  return `${marked}\n`;
}

glyphValidation({
  id: "schema-invalid-unknown-property-named-like-geometry",
  title: "An unknown record property that shares a geometry field's name",
  why:
    "The scoping half of the integer-token rule. Coordinates are checked as *written*, on the text, " +
    "and a check keyed on the member **name** would answer `document-unreadable` here — dropping " +
    "the `statuses` entry of every well-formed sibling acceptance, where a schema-first consumer " +
    "reports `schema-invalid` for this record alone. So the token check is keyed on the containing " +
    "object's path (`plane.box`, `element.bounds`, the acceptance itself), never on the name, and " +
    "an unknown property called `x` is an unknown property like any other. The token is the one " +
    "that *does* round onto an integer, so a name-keyed check fires on it for certain.",
  documentText: geometryNamedUnknownPropertyText(),
  expected: refused(["schema-invalid"]),
});

glyphValidation({
  id: "document-unreadable-element-tolerance-over-by-rounding",
  title: "An `element.tolerance` just past the ceiling, rounded back inside it",
  why:
    "`element.tolerance` is the one field `v1` bounds without requiring an integer, so the integer " +
    "token rule cannot carry it — and the same rounding hole is open: `0.25000000000000000001` is " +
    "`0.25` by the time a range check can look, so this engine gates a record a lossless validator " +
    "refuses as over the declared maximum. Decided as an exact decimal, without ever forming the " +
    "double. A value that is *already* out of range after parsing — `0.3` — keeps its precise " +
    "`tolerance-out-of-range`, which names the record; only the hidden case is traded up to a " +
    "document refusal, and only because nothing else can see it.",
  documentText:
    '{"schema":"compose-preview-known-differences/v1","acceptances":[{"id":"a","element":' +
    '{"kind":"tag","tag":"t","bounds":{"x":1,"y":1,"width":2,"height":2},' +
    '"tolerance":0.25000000000000000001}}]}',
  expected: {
    pins: ["statusesAbsent", "validationFailures"],
    statusesAbsent: true,
    validationFailures: [{ reason: "document-unreadable" }],
  },
});

{
  // **Exactly `0.25`, spelled with a hundred trailing zeroes** — the accepting half of the range
  // walk, and the case its refusing sibling cannot stand in for. The walk truncates the mantissa
  // before comparing, because a million-digit token is legal and only the leading digits can decide
  // a comparison against a two-digit bound; the question that decides a tie is then whether any
  // *discarded* digit was non-zero, not whether digits were discarded at all. An engine that
  // conflates the two refuses this document — a value that is the declared maximum and passes the
  // schema — and still passes every case above it. The text is built from the worked example and
  // patched, so the padding cannot quietly stop landing on the token it is meant to pad.
  const world = glyphWorld();
  const element = {
    kind: "tag",
    tag: "iconbutton-tonal-glyph",
    bounds: { x: 8, y: 8, width: 8, height: 8 },
    tolerance: 0.25,
  };
  const text = `${JSON.stringify(document([glyphRecord(world, { element })]), null, 2)}\n`;
  const marker = '"tolerance": 0.25';
  if (text.split(marker).length !== 2) {
    throw new Error("the element tolerance is no longer a single token spelled `0.25`");
  }
  glyphValidation({
    id: "element-tolerance-at-ceiling-padded",
    title: "An `element.tolerance` of `0.25` written past the digit limit",
    why:
      "The maximum is legal, and staying legal must not depend on how many zeroes follow it. This " +
      "is the accepting half of the exact-decimal range rule: the refusing sibling " +
      "(`0.25000000000000000001`) is satisfied by *any* engine that treats a long token as over " +
      "the ceiling, so it alone would ratify one that refuses the ceiling itself.",
    documentText: text.replace(marker, `${marker}${"0".repeat(100)}`),
    expected: {
      pins: ["statuses", "validationFailures"],
      statuses: { "m3-iconbutton-tonal-glyph": { status: "valid" } },
      validationFailures: [],
    },
  });
}

{
  // **An exponent that overflows the double is still an exponent.** `1e999` is a legal JSON number,
  // and at a path requiring a *canonical integer* it is the same defect as `2e0` — which the tree
  // already refuses as `document-unreadable`. It escaped only because `Number("1e999")` is
  // `Infinity`, so a check asking "did this round onto an integer" answers no. The parse has
  // destroyed the spelling either way, which is the whole test.
  const world = glyphWorld();
  const text = `${JSON.stringify(document([glyphRecord(world)]), null, 2)}\n`;
  const marker = /"candidateTolerance": -?\d+/;
  if (!marker.test(text)) throw new Error("`candidateTolerance` is no longer a plain integer token");
  glyphValidation({
    id: "document-unreadable-candidate-tolerance-exponent-overflow",
    title: "A `candidateTolerance` whose exponent overflows the double",
    why:
      "Its sibling `document-unreadable-fractional-candidate-tolerance` covers the spelling that " +
      "*rounds onto* an integer; this covers the one that overflows past every integer. Both are " +
      "an exponent where `v1` requires canonical digits, and both are unrecoverable once parsed — " +
      "so answering `document-unreadable` for one and an attributed `schema-invalid` for the other " +
      "makes the verdict turn on how large the exponent happened to be.",
    documentText: text.replace(marker, '"candidateTolerance": 1e999'),
    expected: {
      pins: ["statusesAbsent", "validationFailures"],
      statusesAbsent: true,
      validationFailures: [{ reason: "document-unreadable" }],
    },
  });
}

{
  // **The same token, at the one field where an exponent is legal.** `element.tolerance` is a real
  // number, so `1e999` is well-formed there and no text rule touches it — it simply parses to
  // `Infinity`, which is unambiguously outside `[0, 0.25]`. That is a *range* failure, and it has an
  // attributed token; reporting `schema-invalid` would call a magnitude problem a shape problem, and
  // a lossless consumer that never forms the double would disagree.
  const world = glyphWorld();
  const element = {
    kind: "tag",
    tag: "iconbutton-tonal-glyph",
    bounds: { x: 8, y: 8, width: 8, height: 8 },
    tolerance: 0.25,
  };
  const text = `${JSON.stringify(document([glyphRecord(world, { element })]), null, 2)}\n`;
  const marker = '"tolerance": 0.25';
  if (text.split(marker).length !== 2) {
    throw new Error("the element tolerance is no longer a single token spelled `0.25`");
  }
  glyphValidation({
    id: "tolerance-element-exponent-overflow",
    title: "An `element.tolerance` whose exponent overflows the double",
    why:
      "The pair to the integer-path case above, and the reason the two need separate fixtures: an " +
      "exponent is illegal at an integer path and legal here, so the *same token* earns a " +
      "document-level refusal in one place and a precise, attributed record-level one in the other. " +
      "An engine treating non-finite as structurally invalid collapses that distinction.",
    documentText: text.replace(marker, '"tolerance": 1e999'),
    expected: refused(["tolerance-out-of-range"]),
  });
}

glyphValidation({
  id: "document-unreadable-element-tolerance-negative-underflow",
  title: "An `element.tolerance` negative by a magnitude too small to survive the parse",
  why:
    "`-1e-999999` is a legal JSON number strictly below the declared minimum of `0`, and it is " +
    "`-0` by the time any range check can look — so the sign, the only thing that makes it " +
    "invalid, is exactly what the parse destroys. It is the mirror of the over-the-ceiling case at " +
    "the other bound, and it needs the *sign* carried through a magnitude test that has already " +
    "discarded the digits: an engine deciding a far-below-scale token from its magnitude alone " +
    "puts it inside any range that spans zero and gates a record a lossless validator refuses.",
  documentText:
    '{"schema":"compose-preview-known-differences/v1","acceptances":[{"id":"a","element":' +
    '{"kind":"tag","tag":"t","bounds":{"x":1,"y":1,"width":2,"height":2},' +
    '"tolerance":-1e-999999}}]}',
  expected: {
    pins: ["statusesAbsent", "validationFailures"],
    statusesAbsent: true,
    validationFailures: [{ reason: "document-unreadable" }],
  },
});

glyphValidation({
  id: "document-unreadable-fractional-candidate-tolerance",
  title: "A `candidateTolerance` written as a near-integer fraction",
  why:
    "`2.00000000000000000001` is `2` by the time an integer check can look, so this engine reaches " +
    "a gate verdict where a lossless validator or a Kotlin `Int` decoder refuses the record — the " +
    "same rounding divergence as a fractional coordinate, at the other end of the magnitude range. " +
    "The existing `0.5` case does not reach it: that value stays fractional through the parse and " +
    "is refused by the ordinary integer check. Written at a path where an integer is required, so " +
    "the text walk catches it.",
  documentText:
    '{"schema":"compose-preview-known-differences/v1","acceptances":[{"id":"a",' +
    '"candidateTolerance":2.00000000000000000001}]}',
  expected: {
    pins: ["statusesAbsent", "validationFailures"],
    statusesAbsent: true,
    validationFailures: [{ reason: "document-unreadable" }],
  },
});

glyphValidation({
  id: "schema-invalid-unknown-element-property",
  title: "An `element` carrying a property `v1` does not define",
  why:
    "Nested objects are held to the same rule as the record, and this is the case its sibling misses: " +
    "`schema-invalid-unknown-element-kind` is caught by the `kind` discriminant alone, so it says " +
    "nothing about whether a *well-kinded* element may smuggle extra fields past the validator while " +
    "the schema's `additionalProperties: false` rejects the same bytes.",
  record: {
    element: {
      kind: "tag",
      tag: "iconbutton-tonal-glyph",
      bounds: { x: 8, y: 8, width: 8, height: 8 },
      tolerance: 0.1,
      ref: "semantics:17",
    },
  },
  expected: refused(["schema-invalid"]),
});

glyphValidation({
  id: "schema-invalid-note-wrong-type",
  title: "A numeric `note`",
  why: "The optional fields are typed too; a schema-first consumer rejects this and a required-fields-only one does not.",
  record: { note: 42 },
  expected: refused(["schema-invalid"]),
});

{
  // A preview id carrying no `__` axes has an empty variant, and that is a fact about the preview
  // rather than a mangled record.
  const world = glyphWorld();
  const record = glyphRecord(world, { previewId: "iconbutton-tonal", variant: "" });
  addCase({
    id: "variant-empty-is-valid",
    title: "A default preview's empty `variant`",
    why:
      "The locator contract settles this: **`variant` is always present and may be empty** — " +
      "`ServeIssueReport.variantFor` returns `\"\"` for a preview id carrying no `__` axes, while " +
      "every *other* field emptied means the record no longer names one component. Refusing a blank " +
      "variant here would make every default preview's acceptance inexpressible, which is exactly " +
      "the class of defect §2's blank-vs-absent rules exist to prevent.",
    document: document([record]),
    files: glyphFiles(world, record),
    comparison: glyphComparison(world, { previewId: "iconbutton-tonal", variant: "" }),
    expected: {
      pins: ["statuses", "validationFailures"],
      statuses: { "m3-iconbutton-tonal-glyph": { status: "valid" } },
      validationFailures: [],
    },
  });
}

// --- artifacts that are well-formed enough to reach a decoder, and should not survive it ----------

{
  const world = glyphWorld();
  // A correct header, a correct hash, and one flipped byte inside `IDAT`'s stored CRC.
  const corruptCrc = (() => {
    const good = encodePng({
      width: 24,
      height: 24,
      colourType: COLOUR_GREY,
      samples: (() => {
        const samples = new Uint8Array(24 * 24);
        for (let y = 8; y < 16; y++) for (let x = 8; x < 16; x++) samples[y * 24 + x] = 255;
        return samples;
      })(),
    });
    const copy = Uint8Array.from(good);
    copy[copy.length - 13] ^= 0xff;
    return copy;
  })();
  // The same corruption on the chunk the decoder reads *first*. `IHDR` sits at a fixed offset, so
  // its CRC is the four bytes at 29..32.
  const corruptIhdrCrc = (() => {
    const good = encodePng({
      width: 24,
      height: 24,
      colourType: COLOUR_GREY,
      samples: (() => {
        const samples = new Uint8Array(24 * 24);
        for (let y = 8; y < 16; y++) for (let x = 8; x < 16; x++) samples[y * 24 + x] = 255;
        return samples;
      })(),
    });
    const copy = Uint8Array.from(good);
    copy[30] ^= 0xff;
    return copy;
  })();
  glyphValidation({
    id: "decode-failed-ihdr-crc-mismatch",
    title: "A hash-valid artifact whose `IHDR` CRC does not verify",
    why:
      "Its `IDAT` sibling leaves 'verify the CRC of the compressed data only' passing the whole " +
      "suite, and that is a natural thing to implement — the data chunk is the one whose corruption " +
      "obviously matters. But `v1` permits exactly five chunks, every one of them consumed, so " +
      "every CRC is fatal; a corrupt `IHDR`, `PLTE`, `tRNS` or `IEND` otherwise reaches a gate " +
      "verdict here and `decode-failed` from a native decoder. The header bytes themselves are " +
      "left valid, so only the checksum can refuse this file.",
    record: { maskSha256: sha256Hex(corruptIhdrCrc) },
    files: { "artifacts/m3-iconbutton-tonal-glyph/mask.png": corruptIhdrCrc },
    expected: refused(["decode-failed"]),
  });

  glyphValidation({
    id: "decode-failed-chunk-crc-mismatch",
    title: "A hash-valid artifact whose `IDAT` CRC does not verify",
    why:
      "The artifact's own `sha256` proves nobody edited the file in flight; it says nothing about " +
      "whether the file was ever well-formed. Without a CRC check a committed-corrupt PNG decodes " +
      "on one side of the contract and is rejected by a native decoder on the other — one set of " +
      "hash-valid bytes, two verdicts.",
    record: { maskSha256: sha256Hex(corruptCrc) },
    files: { "artifacts/m3-iconbutton-tonal-glyph/mask.png": corruptCrc },
    expected: refused(["decode-failed"]),
  });

  // A complete, correct `IDAT` with four extra bytes after the end of its zlib stream — still inside
  // the chunk, so the length and the CRC are both right and nothing but the payload is unusual.
  const trailingInsideIdat = (() => {
    const samples = new Uint8Array(24 * 24);
    for (let y = 8; y < 16; y++) for (let x = 8; x < 16; x++) samples[y * 24 + x] = 255;
    const rows = [];
    for (let y = 0; y < 24; y++) rows.push(samples.subarray(y * 24, (y + 1) * 24));
    const honest = idat(rows);
    const payload = honest.subarray(8, honest.length - 4);
    const padded = new Uint8Array(payload.length + 4);
    padded.set(payload, 0);
    padded.set([9, 9, 9, 9], payload.length);
    return buildPng([
      ihdr({ width: 24, height: 24, colourType: COLOUR_GREY }),
      chunk("IDAT", padded),
      chunk("IEND"),
    ]);
  })();
  glyphValidation({
    id: "decode-failed-bytes-after-idat-stream",
    title: "Bytes after the end of the `IDAT` zlib stream",
    why:
      "The `IDAT` run is exactly one zlib datastream. An inflater stops at the end of the first one " +
      "and ignores whatever follows, so a second compressed stream — or any bytes at all — can ride " +
      "inside a permitted chunk with a correct length and a correct CRC, and still decode to the " +
      "declared image here while a strict decoder refuses the file. The same shape as the " +
      "bytes-after-`IEND` case one level down: the allowlist stops applying wherever the reader " +
      "stops reading, so the decode has to assert that inflation consumed the whole payload.",
    record: { maskSha256: sha256Hex(trailingInsideIdat) },
    files: { "artifacts/m3-iconbutton-tonal-glyph/mask.png": trailingInsideIdat },
    expected: refused(["decode-failed"]),
  });

  // A legal 24×24 greyscale header in front of an `IDAT` that inflates to far more than 24 rows.
  const bomb = (() => {
    const rows = [];
    for (let y = 0; y < 4096; y++) rows.push(new Uint8Array(24));
    return buildPng([ihdr({ width: 24, height: 24, colourType: COLOUR_GREY }), idat(rows), chunk("IEND")]);
  })();
  glyphValidation({
    id: "header-invalid-inflates-past-declared-size",
    title: "A small legal header in front of a much larger inflation",
    why:
      "None of the preflight budgets can see past the header, so a compression bomb would otherwise " +
      "be inflated in full *after* every cap had passed — these artifacts are third-party and may " +
      "carry up to 8 MiB of compressed data, which deflate expands by orders of magnitude. " +
      "Inflation is bounded by the declared scanline size, and anything over it is a header that " +
      "lied about its dimensions either way. This fixture stays under a kilobyte because a bomb and " +
      "an honest oversize are the same verdict.",
    record: { maskSha256: sha256Hex(bomb) },
    files: { "artifacts/m3-iconbutton-tonal-glyph/mask.png": bomb },
    expected: refused(["header-invalid"]),
  });

  // Ancillary metadata of any kind, with a perfectly good CRC.
  const withText = (() => {
    const samples = new Uint8Array(24 * 24);
    for (let y = 8; y < 16; y++) for (let x = 8; x < 16; x++) samples[y * 24 + x] = 255;
    const rows = [];
    for (let y = 0; y < 24; y++) rows.push(samples.subarray(y * 24, (y + 1) * 24));
    return buildPng([
      ihdr({ width: 24, height: 24, colourType: COLOUR_GREY }),
      chunk("tEXt", Uint8Array.from("note\u0000ok", (ch) => ch.charCodeAt(0))),
      idat(rows),
      chunk("IEND"),
    ]);
  })();
  // A complete, correct PNG with a second `IHDR` appended after `IEND` — and a valid CRC on it, so
  // nothing but the position is wrong.
  const afterIend = (() => {
    const samples = new Uint8Array(24 * 24);
    for (let y = 8; y < 16; y++) for (let x = 8; x < 16; x++) samples[y * 24 + x] = 255;
    const rows = [];
    for (let y = 0; y < 24; y++) rows.push(samples.subarray(y * 24, (y + 1) * 24));
    const complete = buildPng([
      ihdr({ width: 24, height: 24, colourType: COLOUR_GREY }),
      idat(rows),
      chunk("IEND"),
    ]);
    const trailing = ihdr({ width: 1, height: 1, colourType: COLOUR_GREY });
    const out = new Uint8Array(complete.length + trailing.length);
    out.set(complete, 0);
    out.set(trailing, complete.length);
    return out;
  })();
  glyphValidation({
    id: "decode-failed-bytes-after-iend",
    title: "An artifact carrying a chunk after `IEND`",
    why:
      "`IEND` ends the PNG datastream, so it must end the file. A decoder that stops at `IEND` " +
      "simply never looks at what follows — which means the allowlist, the placement rules and every " +
      "CRC stop applying one byte past where they were looking, and an artifact can carry a second " +
      "`IHDR`, an `acTL`, or a kilobyte of anything at all and still reach a gate verdict here while " +
      "a strict decoder refuses the datastream. The trailing chunk in this fixture is *itself* " +
      "well-formed, CRC and all: position is the only thing wrong with it.",
    record: { maskSha256: sha256Hex(afterIend) },
    files: { "artifacts/m3-iconbutton-tonal-glyph/mask.png": afterIend },
    expected: refused(["decode-failed"]),
  });

  glyphValidation({
    id: "decode-failed-chunk-not-permitted",
    title: "An artifact carrying an ancillary chunk",
    why:
      "`v1` permits exactly five chunks — `IHDR`, `PLTE`, `IDAT`, `tRNS`, `IEND` — and refuses " +
      "anything else, critical or ancillary, known or invented. An allowlist rather than a growing " +
      "list of things to reject, because every PNG feature is another place a lenient decoder and a " +
      "colour-managed browser disagree about the pixels a gate then compares, and each one caught " +
      "individually is one more round of the same argument. The cost is that a producer must not " +
      "emit ancillary chunks, which is one line in any encoder.",
    record: { maskSha256: sha256Hex(withText) },
    files: { "artifacts/m3-iconbutton-tonal-glyph/mask.png": withText },
    expected: refused(["decode-failed"]),
  });

  // The case that motivated the allowlist rather than a sixth individual rule.
  const colourManaged = (() => {
    const samples = new Uint8Array(24 * 24);
    for (let y = 8; y < 16; y++) for (let x = 8; x < 16; x++) samples[y * 24 + x] = 255;
    const rows = [];
    for (let y = 0; y < 24; y++) rows.push(samples.subarray(y * 24, (y + 1) * 24));
    return buildPng([
      ihdr({ width: 24, height: 24, colourType: COLOUR_GREY }),
      chunk("gAMA", Uint8Array.from([0, 0, 177, 143])),
      idat(rows),
      chunk("IEND"),
    ]);
  })();
  glyphValidation({
    id: "decode-failed-colour-space-chunk",
    title: "An artifact carrying a colour-space chunk",
    why:
      "`gAMA`, `sRGB` and `iCCP` are not inert metadata: a colour-managed decoder transforms the " +
      "samples through them and an unmanaged one returns them unchanged, so the same hash-valid " +
      "accepted candidate yields different candidate and resolution verdicts on the two sides of " +
      "the contract. Implementing colour management identically in two engines is precisely the " +
      "kind of question this contract refuses to answer, so the chunk is refused instead — which " +
      "the allowlist already does, without needing a rule of its own.",
    record: { acceptedCandidateSha256: sha256Hex(colourManaged) },
    files: { "artifacts/m3-iconbutton-tonal-glyph/accepted-candidate.png": colourManaged },
    expected: refused(["decode-failed"]),
  });

  // Allowed chunks, disallowed placement.
  const greyRows = () => {
    const samples = new Uint8Array(24 * 24);
    for (let y = 8; y < 16; y++) for (let x = 8; x < 16; x++) samples[y * 24 + x] = 255;
    const rows = [];
    for (let y = 0; y < 24; y++) rows.push(samples.subarray(y * 24, (y + 1) * 24));
    return rows;
  };
  const duplicateHeader = buildPng([
    ihdr({ width: 24, height: 24, colourType: COLOUR_GREY }),
    ihdr({ width: 24, height: 24, colourType: COLOUR_GREY }),
    idat(greyRows()),
    chunk("IEND"),
  ]);
  glyphValidation({
    id: "decode-failed-duplicate-ihdr",
    title: "A second `IHDR`",
    why:
      "**Permitted is not the same as well-placed.** Every chunk here is on the allowlist and the " +
      "file is still malformed — a conforming decoder rejects it, so admitting it on membership " +
      "alone reaches a gate verdict where the other side reaches `decode-failed`. There are only " +
      "five chunks to constrain, which is what the allowlist bought: the structural rules are finite " +
      "because the vocabulary is.",
    record: { maskSha256: sha256Hex(duplicateHeader) },
    files: { "artifacts/m3-iconbutton-tonal-glyph/mask.png": duplicateHeader },
    expected: refused(["decode-failed"]),
  });

  const trailingTrns = buildPng([
    ihdr({ width: 24, height: 24, colourType: COLOUR_GREY }),
    idat(greyRows()),
    chunk("tRNS", Uint8Array.from([0, 255])),
    chunk("IEND"),
  ]);
  glyphValidation({
    id: "decode-failed-trns-after-idat",
    title: "A `tRNS` after the image data",
    why:
      "`tRNS` and `PLTE` describe how the image data is to be read, so both must precede it. After " +
      "`IDAT` a decoder either ignores the chunk or applies it retroactively, and those are two " +
      "different rasters for one set of hash-valid bytes.",
    record: { acceptedCandidateSha256: sha256Hex(trailingTrns) },
    files: { "artifacts/m3-iconbutton-tonal-glyph/accepted-candidate.png": trailingTrns },
    expected: refused(["decode-failed"]),
  });

  const fatIend = buildPng([
    ihdr({ width: 24, height: 24, colourType: COLOUR_GREY }),
    idat(greyRows()),
    chunk("IEND", Uint8Array.from([1, 2, 3, 4])),
  ]);
  glyphValidation({
    id: "decode-failed-non-empty-iend",
    title: "A non-empty `IEND`",
    why:
      "`IEND` carries no data by definition, so bytes inside it are a place for content to hide that " +
      "one consumer skips and another refuses. Note the contract still tolerates bytes *after* " +
      "`IEND`: nothing reads them, the byte cap fires on them before any decode, and policing them " +
      "would add a rule with no divergence behind it.",
    record: { maskSha256: sha256Hex(fatIend) },
    files: { "artifacts/m3-iconbutton-tonal-glyph/mask.png": fatIend },
    expected: refused(["decode-failed"]),
  });

  const trnsOnRgba = buildPng([
    ihdr({ width: 8, height: 8 }),
    chunk("tRNS", Uint8Array.from([0, 0, 0, 0, 0, 0])),
    idat([new Uint8Array(32)]),
    chunk("IEND"),
  ]);
  glyphValidation({
    id: "decode-failed-trns-on-alpha-colour-type",
    title: "A `tRNS` beside a colour type that already carries alpha",
    why:
      "Placement was only half of it: this chunk sits exactly where it belongs and is still illegal " +
      "*for this image*, because PNG forbids `tRNS` for colour types 4 and 6. A conforming decoder " +
      "rejects it while a placement-only check admits it and the decoder silently ignores the " +
      "chunk — a gate verdict against a refusal, for one set of hash-valid bytes.",
    record: { acceptedCandidateSha256: sha256Hex(trnsOnRgba) },
    files: { "artifacts/m3-iconbutton-tonal-glyph/accepted-candidate.png": trnsOnRgba },
    expected: refused(["decode-failed"]),
  });

  // Two encodings of "invisible", differing **only** in the colour behind the transparency — so the
  // verdict turns on the normalisation and nothing else. An earlier version of this case compared a
  // transparent accepted candidate against an *opaque* canonical pixel, which differs on alpha
  // whether or not the hidden RGB is normalised: it would have passed against a decoder that
  // preserved the colour, testing nothing.
  {
    const plane = { plane: "content-box", box: { x: 4, y: 4, width: 24, height: 24 } };
    const glyph = { x: 8, y: 8, width: 8, height: 8 };

    // The reference draws an opaque glyph; the candidate leaves that region fully transparent.
    const reference = fillRect(raster(24, 24), glyph, BLACK);
    const candidate = fillRect(raster(24, 24), glyph, [0, 0, 0, 0]);
    // The accepted crop records the same transparency with colour still sitting behind it.
    const accepted = fillRect(raster(8, 8), { x: 0, y: 0, width: 8, height: 8 }, [77, 88, 99, 0]);
    const { png: mask } = maskPng(24, 24, (paint) => paint(glyph));

    const record = {
      id: "m3-transparent-glyph",
      issue: "https://github.com/yschimke/m3-catalog/issues/40",
      system: "m3",
      component: "IconButton/Tonal",
      previewId: "iconbutton-tonal__ideal__default__light",
      referenceId: "iconbutton-tonal-ideal-light",
      variant: "ideal/default/light",
      mask: "mask.png",
      acceptedCandidate: "accepted-candidate.png",
      referenceSha256: REFERENCE_SHA,
      maskSha256: sha256Hex(mask),
      acceptedCandidateSha256: sha256Hex(rgbaPng(accepted)),
      plane,
      candidateTolerance: 2,
      acceptedAt: "2026-08-22T00:00:00Z",
    };
    addCase({
      id: "zero-alpha-rgb-is-normalised",
      title: "Transparent pixels whose hidden colour differs",
      why:
        "Reading a canvas back commonly returns `0,0,0,0` for a fully transparent pixel — " +
        "premultiplying by zero alpha destroys the colour and unpremultiplying cannot recover it. " +
        "The match metric charges all four channels (D5 answer 6), so a decoder preserving the " +
        "hidden RGB compares these two encodings of *invisible* unequal where a browser compares " +
        "them equal, and the disagreement lands straight in the candidate gate. Normalised, the " +
        "acceptance is `valid`; unnormalised it is `invalidated: [candidate-changed]` — so this case " +
        "decides the rule rather than merely mentioning it.",
      document: document([record]),
      files: {
        "artifacts/m3-transparent-glyph/mask.png": mask,
        "artifacts/m3-transparent-glyph/accepted-candidate.png": rgbaPng(accepted),
        "canonical-reference.png": rgbaPng(reference),
        "canonical-candidate.png": rgbaPng(candidate),
      },
      comparison: {
        system: "m3",
        component: "IconButton/Tonal",
        previewId: "iconbutton-tonal__ideal__default__light",
        referenceId: "iconbutton-tonal-ideal-light",
        variant: "ideal/default/light",
        overrides: {},
        referenceSha256: REFERENCE_SHA,
        plane,
        canonicalReference: "canonical-reference.png",
        canonicalCandidate: "canonical-candidate.png",
        tagIndex: {},
      },
      expected: {
        pins: ["statuses", "validationFailures"],
        statuses: { "m3-transparent-glyph": { status: "valid" } },
        validationFailures: [],
      },
    });
  }

  const plteAfterTrns = buildPng([
    ihdr({ width: 8, height: 8, colourType: COLOUR_RGB }),
    chunk("tRNS", Uint8Array.from([0, 0, 0, 0, 0, 0])),
    chunk("PLTE", Uint8Array.from([1, 2, 3])),
    idat([new Uint8Array(24)]),
    chunk("IEND"),
  ]);
  glyphValidation({
    id: "decode-failed-plte-after-trns",
    title: "A truecolor `PLTE` placed after `tRNS`",
    why:
      "`tRNS` describes the palette, so it follows it whenever both are present — for truecolor's " +
      "optional suggested palette as much as for an indexed image. The indexed branch already " +
      "required `PLTE` first; this is the same rule reached from the other side, and without it a " +
      "strict decoder refuses bytes this evaluator carried to a gate verdict.",
    record: { acceptedCandidateSha256: sha256Hex(plteAfterTrns) },
    files: { "artifacts/m3-iconbutton-tonal-glyph/accepted-candidate.png": plteAfterTrns },
    expected: refused(["decode-failed"]),
  });

  const outOfRangeTrns = (() => {
    const samples = new Uint8Array(24 * 24);
    for (let y = 8; y < 16; y++) for (let x = 8; x < 16; x++) samples[y * 24 + x] = 255;
    const rows = [];
    for (let y = 0; y < 24; y++) rows.push(samples.subarray(y * 24, (y + 1) * 24));
    return buildPng([
      ihdr({ width: 24, height: 24, colourType: COLOUR_GREY }),
      chunk("tRNS", Uint8Array.from([1, 255])),
      idat(rows),
      chunk("IEND"),
    ]);
  })();
  glyphValidation({
    id: "decode-failed-trns-sample-out-of-range",
    title: "A `tRNS` sample the image's bit depth cannot contain",
    why:
      "`tRNS` stores its samples as 16-bit values whatever the bit depth, and at depth 8 the range " +
      "is 0–255 — so `0x01ff` names a sample no pixel can hold. Not a harmless spare byte: reading " +
      "the low half alone makes a real pixel transparent, while a decoder honouring the range finds " +
      "no match and leaves it opaque. Two rasters from one hash-valid file, and the difference lands " +
      "straight in the candidate gate.",
    record: { acceptedCandidateSha256: sha256Hex(outOfRangeTrns) },
    files: { "artifacts/m3-iconbutton-tonal-glyph/accepted-candidate.png": outOfRangeTrns },
    expected: refused(["decode-failed"]),
  });

  // **Colour type 4 — greyscale with alpha — has no artifact at all.** The decoder declares it
  // supported and the successful candidates cover 0, 2, 3 and 6, so an engine refusing type 4, or
  // reading its second byte as anything but alpha, passes the entire suite and then produces a wrong
  // candidate verdict for a legal accepted candidate.
  {
    const world = glyphWorld();
    const candidate = raster(24, 24);
    fillRect(candidate, { x: 0, y: 0, width: 4, height: 4 }, GREY);
    fillRect(candidate, world.glyph, [90, 90, 90, 128]);
    const rows = [];
    for (let y = 0; y < 8; y++) {
      const row = new Uint8Array(8 * 2);
      // **Not `255`.** An opaque alpha sample cannot distinguish a decoder that reads the second
      // byte from one that discards it and writes `255` — both produce the same raster, so the
      // fixture would ratify the very implementation it exists to refuse.
      for (let x = 0; x < 8; x++) row.set([90, 128], x * 2);
      rows.push(row);
    }
    const accepted = buildPng([
      ihdr({ width: 8, height: 8, colourType: COLOUR_GREY_ALPHA }),
      idat(rows),
      chunk("IEND"),
    ]);
    const record = glyphRecord(world, { acceptedCandidateSha256: sha256Hex(accepted) });
    addCase({
      id: "artifact-greyscale-alpha-decodes",
      title: "A greyscale-alpha accepted candidate",
      why:
        "The one permitted colour type with no artifact in the tree. Each pixel is a grey sample " +
        "followed by an alpha sample, so an engine that treats the second byte as anything else — " +
        "or refuses the type outright — reads different pixels here and cannot reach `valid`, while " +
        "passing every other case. `90, 128` decodes to `90,90,90,128`, which is what the canonical " +
        "candidate holds under the mask. The alpha is deliberately **not** opaque: with `255` the " +
        "case is satisfied by a decoder that never looks at the second byte at all.",
      document: document([record]),
      files: {
        "artifacts/m3-iconbutton-tonal-glyph/mask.png": world.maskPngBytes,
        "artifacts/m3-iconbutton-tonal-glyph/accepted-candidate.png": accepted,
        "canonical-reference.png": world.referencePngBytes,
        "canonical-candidate.png": rgbaPng(candidate),
      },
      comparison: glyphComparison(world),
      expected: {
        pins: ["statuses", "validationFailures"],
        statuses: { "m3-iconbutton-tonal-glyph": { status: "valid" } },
        validationFailures: [],
      },
    });
  }

  // **A suggested palette on a truecolour image is legal**, and the only truecolour `PLTE` in the
  // tree sits illegally after `tRNS` and expects a refusal — so an engine rejecting every `PLTE` on
  // colour type 2 passes the suite and refuses legal accepted candidates.
  {
    const world = glyphWorld();
    const rows = [];
    for (let y = 0; y < 8; y++) {
      const row = new Uint8Array(8 * 3);
      for (let x = 0; x < 8; x++) row.set([200, 60, 60], x * 3);
      rows.push(row);
    }
    const accepted = buildPng([
      ihdr({ width: 8, height: 8, colourType: COLOUR_RGB }),
      chunk("PLTE", Uint8Array.from([200, 60, 60, 0, 0, 0, 255, 255, 255])),
      idat(rows),
      chunk("IEND"),
    ]);
    const record = glyphRecord(world, { acceptedCandidateSha256: sha256Hex(accepted) });
    addCase({
      id: "artifact-truecolour-suggested-palette",
      title: "A truecolour accepted candidate carrying a suggested palette",
      why:
        "`PLTE` before the image data is permitted on colour type 2, where it is a *suggestion* for " +
        "quantising displays and contributes nothing to the pixels — so the decode must ignore it " +
        "rather than refuse the file or index through it. The refusing sibling only ever places one " +
        "after `tRNS`, which says nothing about the legal position.",
      document: document([record]),
      files: {
        "artifacts/m3-iconbutton-tonal-glyph/mask.png": world.maskPngBytes,
        "artifacts/m3-iconbutton-tonal-glyph/accepted-candidate.png": accepted,
        "canonical-reference.png": world.referencePngBytes,
        "canonical-candidate.png": world.candidatePngBytes,
      },
      comparison: glyphComparison(world),
      expected: {
        pins: ["statuses", "validationFailures"],
        statuses: { "m3-iconbutton-tonal-glyph": { status: "valid" } },
        validationFailures: [],
      },
    });
  }

  // **The same suggested palette on an RGBA image**, which PNG permits for colour type 6 exactly as
  // it does for 2. Its truecolour sibling above cannot stand in: an engine that special-cases
  // `PLTE` on type 2 and refuses it on every RGBA candidate passes that fixture and this tree, then
  // refuses legal accepted artifacts. Same pixels as the sibling, so the only difference between
  // the two files is the encoding, and the verdict must not notice.
  {
    const world = glyphWorld();
    const rows = [];
    for (let y = 0; y < 8; y++) {
      const row = new Uint8Array(8 * 4);
      for (let x = 0; x < 8; x++) row.set([200, 60, 60, 255], x * 4);
      rows.push(row);
    }
    const accepted = buildPng([
      ihdr({ width: 8, height: 8, colourType: COLOUR_RGBA }),
      chunk("PLTE", Uint8Array.from([200, 60, 60, 0, 0, 0, 255, 255, 255])),
      idat(rows),
      chunk("IEND"),
    ]);
    const record = glyphRecord(world, { acceptedCandidateSha256: sha256Hex(accepted) });
    addCase({
      id: "artifact-rgba-suggested-palette",
      title: "An RGBA accepted candidate carrying a suggested palette",
      why:
        "`PLTE` is optional-and-ignored on colour type 6 for the same reason it is on type 2 — it " +
        "quantises nothing and indexes nothing. The tree otherwise only ever pairs `PLTE` with an " +
        "indexed image or a truecolour one, which leaves 'reject `PLTE` unless colour type is 2 or " +
        "3' passing every case while refusing this legal file.",
      document: document([record]),
      files: {
        "artifacts/m3-iconbutton-tonal-glyph/mask.png": world.maskPngBytes,
        "artifacts/m3-iconbutton-tonal-glyph/accepted-candidate.png": accepted,
        "canonical-reference.png": world.referencePngBytes,
        "canonical-candidate.png": world.candidatePngBytes,
      },
      comparison: glyphComparison(world),
      expected: {
        pins: ["statuses", "validationFailures"],
        statuses: { "m3-iconbutton-tonal-glyph": { status: "valid" } },
        validationFailures: [],
      },
    });
  }

  // **An indexed image with no `tRNS` at all**, which is the ordinary opaque case and the one the
  // tree was missing. Every successful indexed fixture carries a transparency table, so two wrong
  // implementations survive: one requiring `tRNS` on every palette image, and one defaulting an
  // absent table to alpha `0` rather than `255`. The second is the more dangerous — it decodes a
  // perfectly ordinary candidate as fully transparent.
  {
    const world = glyphWorld();
    const rows = [];
    for (let y = 0; y < 8; y++) rows.push(new Uint8Array(8));
    const accepted = buildPng([
      ihdr({ width: 8, height: 8, colourType: COLOUR_PALETTE }),
      chunk("PLTE", Uint8Array.from([200, 60, 60, 0, 0, 0, 255, 255, 255])),
      idat(rows),
      chunk("IEND"),
    ]);
    const record = glyphRecord(world, { acceptedCandidateSha256: sha256Hex(accepted) });
    addCase({
      id: "artifact-indexed-opaque-without-trns",
      title: "An indexed accepted candidate with no transparency chunk",
      why:
        "`tRNS` is optional, and its absence means every palette entry is opaque — not that the " +
        "file is malformed, and emphatically not that alpha is `0`. Same decoded pixels as the " +
        "truecolour and RGBA suggested-palette cases, reached through the indexing path, so the " +
        "three together pin that the encoding is invisible to the verdict.",
      document: document([record]),
      files: {
        "artifacts/m3-iconbutton-tonal-glyph/mask.png": world.maskPngBytes,
        "artifacts/m3-iconbutton-tonal-glyph/accepted-candidate.png": accepted,
        "canonical-reference.png": world.referencePngBytes,
        "canonical-candidate.png": world.candidatePngBytes,
      },
      comparison: glyphComparison(world),
      expected: {
        pins: ["statuses", "validationFailures"],
        statuses: { "m3-iconbutton-tonal-glyph": { status: "valid" } },
        validationFailures: [],
      },
    });
  }

  // **A pixel selecting a palette entry that does not exist.** The tree covers well-formed indexed
  // images and malformed `PLTE` *chunk structure*, but nothing reaches the decoder with an index
  // past the end of the palette — so an engine that clamps to the last entry, or substitutes
  // transparent black, decodes this file happily and reaches a gate verdict where the reference
  // decoder refuses it. The palette is deliberately well-formed; only the sample is wrong.
  {
    const world = glyphWorld();
    const rows = [];
    for (let y = 0; y < 8; y++) rows.push(new Uint8Array(8).fill(1));
    const outOfRangeIndex = buildPng([
      ihdr({ width: 8, height: 8, colourType: COLOUR_PALETTE }),
      chunk("PLTE", Uint8Array.from([200, 60, 60])),
      idat(rows),
      chunk("IEND"),
    ]);
    const record = glyphRecord(world, { acceptedCandidateSha256: sha256Hex(outOfRangeIndex) });
    addCase({
      id: "decode-failed-palette-index-out-of-range",
      title: "An indexed accepted candidate selecting an entry its palette does not define",
      why:
        "A one-entry `PLTE` and every pixel asking for entry 1. There is no pixel this file could " +
        "decode to, so the only conformant answer is to refuse it — and the two plausible ways to " +
        "cope, clamping to the last entry or filling transparent black, both produce a raster and " +
        "then a verdict. Its sibling `artifact-indexed-entry-beyond-trns` is the case this must not " +
        "be confused with: there the *palette* covers the index and only `tRNS` runs short, which " +
        "is legal and decodes opaque.",
      document: document([record]),
      files: {
        "artifacts/m3-iconbutton-tonal-glyph/mask.png": world.maskPngBytes,
        "artifacts/m3-iconbutton-tonal-glyph/accepted-candidate.png": outOfRangeIndex,
        "canonical-reference.png": world.referencePngBytes,
        "canonical-candidate.png": world.candidatePngBytes,
      },
      comparison: glyphComparison(world),
      expected: refused(["decode-failed"]),
    });
  }

  // **A palette entry past the end of `tRNS`.** The transparency table may be shorter than the
  // palette, and every entry it does not reach is opaque. The existing indexed fixtures cannot pin
  // that: one has a `tRNS` exactly as long as its palette, the other has no `tRNS` at all, so an
  // engine defaulting *omitted entries of a present table* to `0` — rather than `255` — passes both
  // and then reads a legal partially-transparent indexed candidate as transparent where it is not.
  {
    const world = glyphWorld();
    const rows = [];
    // Every pixel selects entry 1; `tRNS` describes only entry 0.
    for (let y = 0; y < 8; y++) rows.push(new Uint8Array(8).fill(1));
    const accepted = buildPng([
      ihdr({ width: 8, height: 8, colourType: COLOUR_PALETTE }),
      chunk("PLTE", Uint8Array.from([0, 0, 0, 200, 60, 60])),
      chunk("tRNS", Uint8Array.from([0])),
      idat(rows),
      chunk("IEND"),
    ]);
    const record = glyphRecord(world, { acceptedCandidateSha256: sha256Hex(accepted) });
    addCase({
      id: "artifact-indexed-entry-beyond-trns",
      title: "An indexed accepted candidate selecting a palette entry `tRNS` does not describe",
      why:
        "`tRNS` is a prefix of the palette, not a parallel array that must match its length. Entry " +
        "1 has no alpha entry, so it is opaque — and the file is otherwise the same red as the " +
        "truecolour, RGBA and no-`tRNS` cases, so all four decode to identical pixels by four " +
        "different routes. An engine that treats 'past the end of the table' as transparent " +
        "produces `0,0,0,0` here and cannot reach `valid`.",
      document: document([record]),
      files: {
        "artifacts/m3-iconbutton-tonal-glyph/mask.png": world.maskPngBytes,
        "artifacts/m3-iconbutton-tonal-glyph/accepted-candidate.png": accepted,
        "canonical-reference.png": world.referencePngBytes,
        "canonical-candidate.png": world.candidatePngBytes,
      },
      comparison: glyphComparison(world),
      expected: {
        pins: ["statuses", "validationFailures"],
        statuses: { "m3-iconbutton-tonal-glyph": { status: "valid" } },
        validationFailures: [],
      },
    });
  }

  // **Scanline filters on a multi-channel image.** The tree's only filtered artifact is a greyscale
  // mask, so the Sub/Average/Paeth predictors are only ever exercised with a left-neighbour distance
  // of **one byte** — and a decoder that hardcodes that distance passes all four filter types. The
  // distance is the pixel stride: three bytes for RGB, two for greyscale-alpha, four here.
  {
    const world = glyphWorld();
    const rows = [];
    for (let y = 0; y < 8; y++) {
      const row = new Uint8Array(8 * 4);
      for (let x = 0; x < 8; x++) row.set([200, 60, 60, 255], x * 4);
      rows.push(row);
    }
    const accepted = buildPng([
      ihdr({ width: 8, height: 8, colourType: COLOUR_RGBA }),
      filteredIdat(rows, 4),
      chunk("IEND"),
    ]);
    const record = glyphRecord(world, { acceptedCandidateSha256: sha256Hex(accepted) });
    addCase({
      id: "artifact-scanline-filters-multi-channel",
      title: "An RGBA accepted candidate whose scanlines use filters 1–4",
      why:
        "Its greyscale sibling pins that the filter byte is *read*; this pins that the predictor " +
        "steps by the pixel stride. With one channel the two are indistinguishable — `bpp` is 1 " +
        "either way — so an engine with the distance hardcoded decodes that fixture correctly and " +
        "mis-decodes every ordinary filtered RGB or RGBA candidate in the field. Same pixels as the " +
        "unfiltered RGBA case, so only the filtering can make the verdict differ.",
      document: document([record]),
      files: {
        "artifacts/m3-iconbutton-tonal-glyph/mask.png": world.maskPngBytes,
        "artifacts/m3-iconbutton-tonal-glyph/accepted-candidate.png": accepted,
        "canonical-reference.png": world.referencePngBytes,
        "canonical-candidate.png": world.candidatePngBytes,
      },
      comparison: glyphComparison(world),
      expected: {
        pins: ["statuses", "validationFailures"],
        statuses: { "m3-iconbutton-tonal-glyph": { status: "valid" } },
        validationFailures: [],
      },
    });
  }

  // **`tRNS` that actually decodes**, on the two permitted colour types that are not the palette.
  // The single successful transparency fixture is palette-based, so an engine applying `tRNS` only
  // to palette images passes the suite and then reads a greyscale or truecolour accepted candidate
  // as fully opaque — a different raster in the candidate gate, for legal bytes. Both cases make the
  // glyph *transparent*, so honouring the chunk is the only way to reach the expected verdict: the
  // canonical candidate holds `0,0,0,0` there, and an engine ignoring `tRNS` decodes an opaque
  // colour instead and reports `candidate-changed`.
  for (const [id, title, colourType, plte, trns, sample, extra] of [
    [
      "artifact-trns-greyscale-decodes",
      "A greyscale accepted candidate whose `tRNS` names its own sample",
      COLOUR_GREY,
      null,
      Uint8Array.from([0, 90]),
      [90],
      "A greyscale `tRNS` is one 16-bit sample; at depth 8 the high byte is zero and the low byte " +
        "names the transparent grey.",
    ],
    [
      "artifact-trns-truecolour-decodes",
      "A truecolour accepted candidate whose `tRNS` names its own colour",
      COLOUR_RGB,
      null,
      Uint8Array.from([0, 200, 0, 60, 0, 60]),
      [200, 60, 60],
      "A truecolour `tRNS` is three 16-bit samples, so it is six bytes at any bit depth — the shape " +
        "a length check keyed on the colour type has to know.",
    ],
  ]) {
    const world = glyphWorld();
    // The candidate the comparison supplies: the glyph is fully transparent there.
    const candidate = raster(24, 24);
    fillRect(candidate, { x: 0, y: 0, width: 4, height: 4 }, GREY);
    fillRect(candidate, world.glyph, [0, 0, 0, 0]);
    const channels = colourType === COLOUR_GREY ? 1 : 3;
    const rows = [];
    for (let y = 0; y < 8; y++) {
      const row = new Uint8Array(8 * channels);
      for (let x = 0; x < 8; x++) row.set(sample, x * channels);
      rows.push(row);
    }
    const accepted = buildPng([
      ihdr({ width: 8, height: 8, colourType }),
      ...(plte ? [chunk("PLTE", plte)] : []),
      chunk("tRNS", trns),
      idat(rows),
      chunk("IEND"),
    ]);
    const record = glyphRecord(world, { acceptedCandidateSha256: sha256Hex(accepted) });
    addCase({
      id,
      title,
      why:
        `${extra} The acceptance is \`valid\` only if the chunk is honoured: the glyph decodes to ` +
        "alpha `0` — and to `0,0,0` RGB, since a fully transparent pixel is normalised at decode — " +
        "which is exactly what the canonical candidate holds there. An engine that applies `tRNS` " +
        "to palette images alone decodes an opaque colour and reports `candidate-changed`.",
      document: document([record]),
      files: {
        "artifacts/m3-iconbutton-tonal-glyph/mask.png": world.maskPngBytes,
        "artifacts/m3-iconbutton-tonal-glyph/accepted-candidate.png": accepted,
        "canonical-reference.png": world.referencePngBytes,
        "canonical-candidate.png": rgbaPng(candidate),
      },
      comparison: glyphComparison(world),
      expected: {
        pins: ["statuses", "validationFailures"],
        statuses: { "m3-iconbutton-tonal-glyph": { status: "valid" } },
        validationFailures: [],
      },
    });
  }

  const emptyPaletteTrns = buildPng([
    ihdr({ width: 8, height: 8, colourType: COLOUR_PALETTE }),
    chunk("PLTE", Uint8Array.from([200, 60, 60, 0, 0, 0])),
    chunk("tRNS", new Uint8Array(0)),
    idat([new Uint8Array(8)]),
    chunk("IEND"),
  ]);
  glyphValidation({
    id: "decode-failed-empty-palette-trns",
    title: "A zero-length palette `tRNS`",
    why:
      "The upper bound was checked and the lower one was not: PNG requires a palette `tRNS` to carry " +
      "at least one alpha entry, so an empty one is malformed and a conforming decoder refuses it " +
      "while a length-ceiling check decodes the image as fully opaque.",
    record: { acceptedCandidateSha256: sha256Hex(emptyPaletteTrns) },
    files: { "artifacts/m3-iconbutton-tonal-glyph/accepted-candidate.png": emptyPaletteTrns },
    expected: refused(["decode-failed"]),
  });

  const paletteOnGrey = buildPng([
    ihdr({ width: 24, height: 24, colourType: COLOUR_GREY }),
    chunk("PLTE", Uint8Array.from([0, 0, 0, 255, 255, 255])),
    idat(greyRows()),
    chunk("IEND"),
  ]);
  glyphValidation({
    id: "decode-failed-palette-on-greyscale",
    title: "A `PLTE` in a greyscale image",
    why: "The other half of the same rule, pointed at the other chunk: a palette is meaningless — and forbidden — for a greyscale colour type.",
    record: { maskSha256: sha256Hex(paletteOnGrey) },
    files: { "artifacts/m3-iconbutton-tonal-glyph/mask.png": paletteOnGrey },
    expected: refused(["decode-failed"]),
  });

  // A stream that stops after a complete `IDAT`.
  const noIend = (() => {
    const samples = new Uint8Array(24 * 24);
    for (let y = 8; y < 16; y++) for (let x = 8; x < 16; x++) samples[y * 24 + x] = 255;
    const rows = [];
    for (let y = 0; y < 24; y++) rows.push(samples.subarray(y * 24, (y + 1) * 24));
    return buildPng([ihdr({ width: 24, height: 24, colourType: COLOUR_GREY }), idat(rows)]);
  })();
  glyphValidation({
    id: "decode-failed-missing-iend",
    title: "A stream truncated after a complete `IDAT`",
    why:
      "It decodes to *something* — how much depends on where the truncation landed, which is exactly " +
      "the consumer-dependent answer this contract cannot have. `IEND` is mandatory, so requiring it " +
      "is the deterministic reading. Deliberately stricter than a browser, which will happily paint " +
      "a partial raster: a committed artifact missing its terminator is broken, not partial.",
    record: { maskSha256: sha256Hex(noIend) },
    files: { "artifacts/m3-iconbutton-tonal-glyph/mask.png": noIend },
    expected: refused(["decode-failed"]),
  });

  // A perfectly ordinary mask whose scanlines carry filter types 1–4 instead of 0.
  const filteredMask = (() => {
    const samples = new Uint8Array(24 * 24);
    for (let y = 8; y < 16; y++) for (let x = 8; x < 16; x++) samples[y * 24 + x] = 255;
    const rows = [];
    for (let y = 0; y < 24; y++) rows.push(samples.subarray(y * 24, (y + 1) * 24));
    return buildPng([
      ihdr({ width: 24, height: 24, colourType: COLOUR_GREY }),
      filteredIdat(rows, 1),
      chunk("IEND"),
    ]);
  })();
  glyphValidation({
    id: "artifact-scanline-filters-are-honoured",
    title: "An artifact whose scanlines use filters 1–4",
    why:
      "Every other PNG in this tree is written with filter `0` on every row, so the whole committed " +
      "suite could be decoded by an engine implementing none of Sub, Up, Average or Paeth — and " +
      "those are ordinary PNG that any encoder emits, so that engine then refuses or mis-decodes a " +
      "perfectly legal accepted candidate in the field. This mask decodes to exactly the same " +
      "samples as its unfiltered twin and the acceptance is `valid`; an engine that ignores the " +
      "filter byte reads different pixels and cannot reach that verdict. The rows cycle through all " +
      "four so no single filter can be the one that happens to be implemented.",
    record: { maskSha256: sha256Hex(filteredMask) },
    files: { "artifacts/m3-iconbutton-tonal-glyph/mask.png": filteredMask },
    expected: {
      pins: ["statuses", "validationFailures"],
      statuses: { "m3-iconbutton-tonal-glyph": { status: "valid" } },
      validationFailures: [],
    },
  });

  // The **filter** method byte, on its own — the compression byte left at its legal zero.
  const badFilterMethod = (() => {
    const samples = new Uint8Array(24 * 24);
    for (let y = 8; y < 16; y++) for (let x = 8; x < 16; x++) samples[y * 24 + x] = 255;
    const rows = [];
    for (let y = 0; y < 24; y++) rows.push(samples.subarray(y * 24, (y + 1) * 24));
    return buildPng([
      ihdr({ width: 24, height: 24, colourType: COLOUR_GREY, filter: 1 }),
      idat(rows),
      chunk("IEND"),
    ]);
  })();
  glyphValidation({
    id: "decode-failed-unsupported-filter-method",
    title: "An `IHDR` declaring a filter method the specification does not define",
    why:
      "`IHDR` carries **two** method bytes and the sibling case only ever moves one of them, so an " +
      "engine that validates `compression` and ignores `filter` passes the suite and then accepts a " +
      "correctly hashed PNG declaring `filter: 1` — reaching a gate verdict where a conforming " +
      "decoder refuses the header. Not to be confused with the per-scanline filter *type*, which is " +
      "a byte on every row and of which five are legal; this is the method byte, of which one is.",
    record: { maskSha256: sha256Hex(badFilterMethod) },
    files: { "artifacts/m3-iconbutton-tonal-glyph/mask.png": badFilterMethod },
    expected: refused(["decode-failed"]),
  });

  // A legal-looking header declaring a compression method the specification does not define.  // A legal-looking header declaring a compression method the specification does not define.
  const badMethod = (() => {
    const samples = new Uint8Array(24 * 24);
    for (let y = 8; y < 16; y++) for (let x = 8; x < 16; x++) samples[y * 24 + x] = 255;
    const rows = [];
    for (let y = 0; y < 24; y++) rows.push(samples.subarray(y * 24, (y + 1) * 24));
    return buildPng([
      ihdr({ width: 24, height: 24, colourType: COLOUR_GREY, compression: 1 }),
      idat(rows),
      chunk("IEND"),
    ]);
  })();
  glyphValidation({
    id: "decode-failed-unsupported-compression-method",
    title: "An `IHDR` declaring a compression method the specification does not define",
    why:
      "`IHDR` carries a compression method and a filter method, and the specification defines " +
      "exactly one of each. Ignoring those two bytes means inflating ordinary-looking scanlines and " +
      "reaching a *gate verdict* where a conforming decoder reaches `decode-failed` — the same " +
      "class as an interlaced file, and the same token. The method byte is written *into* the " +
      "chunk, so its CRC is correct: poking it into a finished file would leave a stale CRC, and the " +
      "file would then be refused by the CRC check before the method byte was ever read — a fixture " +
      "that passes for the wrong reason.",
    record: { maskSha256: sha256Hex(badMethod) },
    files: { "artifacts/m3-iconbutton-tonal-glyph/mask.png": badMethod },
    expected: refused(["decode-failed"]),
  });

  // A perfectly valid interlaced PNG, and a perfectly valid 16-bit one.
  const interlaced = buildPng([
    ihdr({ width: 8, height: 8, interlace: 1 }),
    idat([new Uint8Array(4)]),
    chunk("IEND"),
  ]);
  glyphValidation({
    id: "decode-failed-interlaced-accepted-candidate",
    title: "An interlaced accepted candidate",
    why:
      "`v1` decodes a **subset** of PNG — 8-bit, non-interlaced — and says so, for both artifacts. " +
      "The alternative was to implement Adam7 and the 1/2/4/16-bit depths in every engine, which " +
      "buys nothing an authoring tool cannot trivially avoid and adds a large new surface for the " +
      "two engines to disagree on (16-bit reduction alone is a rounding decision). Restricting " +
      "rather than answering is what this contract does with the mask's encoding and with animation, " +
      "for the same reason. Stated in §4 so it is a shared restriction rather than an accident of " +
      "one decoder.",
    record: { acceptedCandidateSha256: sha256Hex(interlaced) },
    files: { "artifacts/m3-iconbutton-tonal-glyph/accepted-candidate.png": interlaced },
    expected: refused(["decode-failed"]),
  });

  const deep = buildPng([
    ihdr({ width: 8, height: 8, bitDepth: 16, colourType: COLOUR_GREY }),
    idat([new Uint8Array(16)]),
    chunk("IEND"),
  ]);
  glyphValidation({
    id: "decode-failed-16-bit-accepted-candidate",
    title: "A 16-bit accepted candidate",
    why: "The other half of the same restriction, and the one a bit-depth check written only for the mask would miss.",
    record: { acceptedCandidateSha256: sha256Hex(deep) },
    files: { "artifacts/m3-iconbutton-tonal-glyph/accepted-candidate.png": deep },
    expected: refused(["decode-failed"]),
  });

  // A chunk type whose first letter is uppercase — critical — and which nothing recognises.
  const unknownCritical = (() => {
    const samples = new Uint8Array(24 * 24);
    for (let y = 8; y < 16; y++) for (let x = 8; x < 16; x++) samples[y * 24 + x] = 255;
    const rows = [];
    for (let y = 0; y < 24; y++) rows.push(samples.subarray(y * 24, (y + 1) * 24));
    return buildPng([
      ihdr({ width: 24, height: 24, colourType: COLOUR_GREY }),
      chunk("ABCD", Uint8Array.from([1, 2, 3])),
      idat(rows),
      chunk("IEND"),
    ]);
  })();
  glyphValidation({
    id: "decode-failed-unrecognized-critical-chunk",
    title: "An unrecognized **critical** chunk with a valid CRC",
    why:
      "The PNG specification requires a decoder to stop on a critical chunk it does not recognise, " +
      "and a browser obeys it. `v1` goes further and refuses every chunk outside its allowlist, so " +
      "this case and `decode-failed-chunk-not-permitted` reach the same verdict by the same rule — " +
      "kept separate because a reader looking for the specification's requirement should find it " +
      "covered, not inferred.",
    record: { maskSha256: sha256Hex(unknownCritical) },
    files: { "artifacts/m3-iconbutton-tonal-glyph/mask.png": unknownCritical },
    expected: refused(["decode-failed"]),
  });

  // A palette accepted candidate whose single entry is transparent.
  const translucent = (() => {
    const samples = new Uint8Array(8 * 8);
    const rows = [];
    for (let y = 0; y < 8; y++) rows.push(samples.subarray(y * 8, (y + 1) * 8));
    return buildPng([
      ihdr({ width: 8, height: 8, colourType: COLOUR_PALETTE }),
      chunk("PLTE", Uint8Array.from([200, 60, 60])),
      chunk("tRNS", Uint8Array.from([0])),
      idat(rows),
      chunk("IEND"),
    ]);
  })();
  glyphValidation({
    id: "trns-transparency-is-decoded",
    title: "An accepted candidate carrying `tRNS`",
    why:
      "`accepted-candidate.png` is an ordinary colour raster and carries no encoding rule, so a " +
      "palette file with a `tRNS` chunk is legal. A decoder that hardcodes alpha to `255` reads its " +
      "pixels as opaque red and the candidate gate passes; a browser applies the transparency and " +
      "the gate fires. Same hash-valid bytes, two verdicts — so the decoder must apply `tRNS` for " +
      "palette, greyscale and RGB alike. The expected verdict here is the one a correct decoder " +
      "reaches: alpha `0` against the candidate's `255` is a per-channel distance of 255.",
    record: { acceptedCandidateSha256: sha256Hex(translucent) },
    files: { "artifacts/m3-iconbutton-tonal-glyph/accepted-candidate.png": translucent },
    expected: {
      pins: ["statuses", "validationFailures"],
      statuses: { "m3-iconbutton-tonal-glyph": { status: "invalidated", causes: ["candidate-changed"] } },
      validationFailures: [],
    },
  });
}

// --------------------------------------------------------------------------------------------
// 5. The portable resampler, pinned on its own.
//
// The gate cases above take their canonical-plane rasters as inputs, deliberately: a resampler
// divergence must fail *as* a resampler divergence rather than surfacing as a wrong verdict, which
// is the whole reason for pinning intermediate stages. So the kernel gets its own group, with
// expected pixels stated as arithmetic rather than harvested from a run.
// --------------------------------------------------------------------------------------------

const resampleCases = [];

function addResample({ id, title, why, source, target, expected }) {
  resampleCases.push({ id, title, why, source, target, expected });
}

function rgbaFrom(rows) {
  const height = rows.length;
  const width = rows[0].length;
  const pixels = new Uint8Array(width * height * 4);
  rows.forEach((row, y) => row.forEach((value, x) => pixels.set(value, (y * width + x) * 4)));
  return { width, height, pixels };
}

const grey = (v, a = 255) => [v, v, v, a];

addResample({
  id: "downscale-2x1-average",
  title: "Four pixels averaged into one",
  why:
    "The plain case, and the one that pins the rounding: `(0 + 100 + 200 + 255) / 4 = 138.75`, " +
    "rounded **half-up** to `139`. Accumulate in double precision and round exactly once, at the " +
    "end — rounding per contribution is where two implementations drift.",
  source: rgbaFrom([
    [grey(0), grey(100)],
    [grey(200), grey(255)],
  ]),
  target: { width: 1, height: 1 },
  expected: [grey(139)],
});

addResample({
  id: "rounding-exactly-half",
  title: "An average landing exactly on .5",
  why:
    "`(100 + 101) / 2 = 100.5`. Half-up gives `101`; banker's rounding gives `100`. Both are " +
    "defensible and only one of them can be the contract, so the fixture names it.",
  source: rgbaFrom([[grey(100), grey(101)]]),
  target: { width: 1, height: 1 },
  expected: [grey(101)],
});

addResample({
  id: "downscale-non-integer-ratio",
  title: "Three pixels into two — partial footprints",
  why:
    "The case a box filter at integer ratios never reaches, and the reason the kernel is defined as " +
    "an **area average over exact source footprints**: destination 0 covers `[0, 1.5)`, so pixel 1 " +
    "contributes half its area — `(0 × 1 + 90 × 0.5) / 1.5 = 30` — and destination 1 covers " +
    "`[1.5, 3)` for `(90 × 0.5 + 240 × 1) / 1.5 = 190`. No kernel radius, no edge-extension rule: a " +
    "footprint is clipped to the source rectangle and never samples outside it.",
  source: rgbaFrom([[grey(0), grey(90), grey(240)]]),
  target: { width: 2, height: 1 },
  expected: [grey(30), grey(190)],
});

addResample({
  id: "downscale-non-integer-ratio-vertical",
  title: "Three rows into two — the same partial footprints, on the other axis",
  why:
    "Its horizontal sibling is one row tall, as is every other non-integer case here, and the only " +
    "multi-row fixture is an integer 2×2→1×1. So a **separable** implementation that applies the " +
    "area kernel across x and nearest-neighbour down y passes the entire group while producing " +
    "different canonical pixels for any real resize. The arithmetic is the mirror image — row 0 " +
    "covers `[0, 1.5)` for `(0 × 1 + 90 × 0.5) / 1.5 = 30`, row 1 covers `[1.5, 3)` for " +
    "`(90 × 0.5 + 240 × 1) / 1.5 = 190` — which is the point: the kernel is one rule over an area, " +
    "not a rule about columns.",
  source: rgbaFrom([[grey(0)], [grey(90)], [grey(240)]]),
  target: { width: 1, height: 2 },
  expected: [grey(30), grey(190)],
});

addResample({
  id: "downscale-non-integer-ratio-both-axes",
  title: "Three by three into two by two — a genuinely two-dimensional footprint",
  why:
    "Both one-dimensional cases can still be satisfied by running a 1-D kernel twice with a " +
    "rounding step in between, and by any implementation whose axes happen not to interact. Here " +
    "every destination covers a 1.5 × 1.5 source rectangle, so all four source pixels under it " +
    "carry a *product* of two partial weights — the `0.25` corner term exists on no other fixture. " +
    "Values chosen so each destination is an exact integer: this case is about the geometry, and " +
    "the rounding rule is pinned by cases of its own.",
  source: rgbaFrom([
    [grey(0), grey(60), grey(120)],
    [grey(60), grey(120), grey(180)],
    [grey(120), grey(180), grey(240)],
  ]),
  target: { width: 2, height: 2 },
  expected: [grey(40), grey(120), grey(120), grey(200)],
});

addResample({
  id: "upscale-integer-ratio",
  title: "Two pixels into four",
  why:
    "Upscaling by an integer reduces to nearest-neighbour under the same arithmetic, so the three " +
    "cases an implementation is most likely to special-case — integer downscale, fractional " +
    "downscale, upscale — are all one rule here.",
  source: rgbaFrom([[grey(10), grey(20)]]),
  target: { width: 4, height: 1 },
  expected: [grey(10), grey(10), grey(20), grey(20)],
});

addResample({
  id: "upscale-non-integer-ratio",
  title: "Two pixels into three — the upscale that does not reduce to nearest-neighbour",
  why:
    "Its sibling above cannot catch the shortcut it warns about: at an integer ratio the area " +
    "average *is* nearest-neighbour, so an engine using the kernel for downscales and copying the " +
    "nearest source pixel for every upscale passes it. At 2 → 3 the middle destination covers " +
    "`[2/3, 4/3)` and takes a third of each source pixel — `(30 × 1/3 + 210 × 1/3) / (2/3) = 120` — " +
    "while nearest-neighbour reads its centre at `1.0` and answers `210`. The outer two destinations " +
    "sit wholly inside one source pixel each and agree under both readings, which is what makes the " +
    "middle one the whole test.",
  source: rgbaFrom([[grey(30), grey(210)]]),
  target: { width: 3, height: 1 },
  expected: [grey(30), grey(120), grey(210)],
});

addResample({
  id: "rounding-half-survives-the-ratio",
  title: "An exact half that floating-point footprints lose",
  why:
    "`rounding-exactly-half` pins half-up on a ratio where double arithmetic happens to be exact, so " +
    "it cannot catch an implementation whose *footprints* are floats. Four columns into three puts " +
    "destination 0 on `(0 × 3 + 2 × 1) / 4 = 0.5` — one half-up, to `1`. Computed with floating " +
    "footprints the same quantity is `0.49999999999999994`, which rounds to `0`: the specification " +
    "says half-up and the implementation delivers half-down, from one unlucky ratio and a difference " +
    "of a single unit. The fix is exact integer footprints, and this is the case that holds them to " +
    "it. Every number here is derivable by hand, which is the point — scale by the target width and " +
    "the overlaps are integers.",
  source: rgbaFrom([[grey(0), grey(2), grey(0), grey(0)]]),
  target: { width: 3, height: 1 },
  expected: [grey(1), grey(1), grey(0)],
});

addResample({
  id: "alpha-is-a-fourth-channel",
  title: "Alpha averaged without premultiplication",
  why:
    "Premultiplying and un-premultiplying introduces a rounding step each way that two engines would " +
    "have to agree on for no benefit. `(64 + 255) / 2 = 159.5 → 160` on alpha, and the colour " +
    "channels average independently of it — under premultiplied arithmetic they would be weighted by " +
    "it instead. The partly-transparent pixel is deliberate: a **fully** transparent one cannot reach " +
    "the resampler, because decoding normalises its RGB to zero (a browser cannot recover colour it " +
    "premultiplied away), so a fixture built on one would be testing a state no decoded raster holds.",
  source: rgbaFrom([[[10, 20, 30, 64], [200, 100, 50, 255]]]),
  target: { width: 1, height: 1 },
  expected: [[105, 60, 40, 160]],
});

// --------------------------------------------------------------------------------------------
// 5b. Sub-pixel rounding, pinned on its own.
//
// D5 answer 5 is outward rounding to the enclosing integer box, and until this group existed the
// suite did not test it at all: every gate case hands the evaluator canonical boxes that are already
// integers, so a second engine could round inward or to nearest and still pass all eighty-six. A
// claim the fixtures do not exercise is a claim two engines can each believe they implemented.
// --------------------------------------------------------------------------------------------

const roundingCases = [];

function addRounding({ id, title, why, box, expected }) {
  roundingCases.push({ id, title, why, box, expected });
}

addRounding({
  id: "integer-box-is-unchanged",
  title: "A box already on the grid",
  why: "The identity case. Outward rounding must not inflate a box that needs no rounding.",
  box: { x: 8, y: 8, width: 8, height: 8 },
  expected: { x: 8, y: 8, width: 8, height: 8 },
});

addRounding({
  id: "fractional-origin-floors",
  title: "A fractional origin",
  why:
    "`floor` the origin, so the box grows *towards* the pixel the author's selection already " +
    "touched. Rounding the origin to nearest would move the left edge inward for anything past the " +
    "half-pixel, which is the direction that silently stops covering pixels.",
  box: { x: 8.4, y: 8.6, width: 8, height: 8 },
  expected: { x: 8, y: 8, width: 9, height: 9 },
});

addRounding({
  id: "fractional-far-edge-ceils",
  title: "A fractional far edge",
  why:
    "`ceil` the far edge, computed as `x + width` rather than by rounding the *width* — those differ " +
    "whenever the origin is fractional, and only the first is the enclosing box.",
  box: { x: 8, y: 8, width: 7.2, height: 7.8 },
  expected: { x: 8, y: 8, width: 8, height: 8 },
});

addRounding({
  id: "fractional-both-ends",
  title: "Fractional at both ends",
  why:
    "The case that separates outward rounding from inward: `ceil` the origin and `floor` the far " +
    "edge and this box becomes `{x: 9, y: 3, width: 6, height: 2}` — half the height, and shifted.",
  box: { x: 8.5, y: 2.25, width: 7.25, height: 3.5 },
  expected: { x: 8, y: 2, width: 8, height: 4 },
});

addRounding({
  id: "negative-origin",
  title: "A box whose origin is negative",
  why:
    "A transform can put a selection's origin outside the plane before clipping, and `floor` is not " +
    "truncation there — `Math.trunc(-0.5)` is `0` and moves the edge *inward*. Languages differ on " +
    "which one their integer cast performs, so the fixture pins the one this contract means.",
  box: { x: -0.5, y: -2.5, width: 4, height: 4 },
  expected: { x: -1, y: -3, width: 5, height: 5 },
});

// --------------------------------------------------------------------------------------------
// 6. Write the tree.
// --------------------------------------------------------------------------------------------

function write(path, contents) {
  const full = join(ROOT, path);
  mkdirSync(dirname(full), { recursive: true });
  writeFileSync(full, contents);
}

function json(value) {
  return `${JSON.stringify(value, null, 2)}\n`;
}

rmSync(ROOT, { recursive: true, force: true });
mkdirSync(ROOT, { recursive: true });

for (const entry of cases) {
  const dir = `cases/${entry.id}`;
  write(
    `${dir}/case.json`,
    json({
      title: entry.title,
      site: entry.site ?? null,
      why: entry.why,
      comparison: entry.comparison,
      catalog: entry.catalog,
      synthesize: entry.synthesize,
    }),
  );
  write(`${dir}/known-differences.json`, entry.documentText ?? json(entry.document));
  for (const [path, bytes] of Object.entries(entry.files)) write(`${dir}/${path}`, Buffer.from(bytes));
  write(`${dir}/expected.json`, json(entry.expected));
}

for (const entry of roundingCases) {
  const dir = `rounding/${entry.id}`;
  write(`${dir}/case.json`, json({ title: entry.title, why: entry.why, box: entry.box }));
  write(`${dir}/expected.json`, json(entry.expected));
}

for (const entry of resampleCases) {
  const dir = `resample/${entry.id}`;
  write(`${dir}/source.png`, Buffer.from(rgbaPng(entry.source)));
  write(
    `${dir}/case.json`,
    json({ title: entry.title, why: entry.why, target: entry.target }),
  );
  write(
    `${dir}/expected.json`,
    json({
      width: entry.target.width,
      height: entry.target.height,
      pixels: entry.expected,
    }),
  );
}

write(
  "index.json",
  json({
    schema: "compose-preview-known-differences/v1",
    cases: cases.map((entry) => ({ id: entry.id, title: entry.title, site: entry.site ?? null })),
    resample: resampleCases.map((entry) => ({ id: entry.id, title: entry.title })),
    rounding: roundingCases.map((entry) => ({ id: entry.id, title: entry.title })),
  }),
);

write(
  "README.md",
  [
    "# `known-differences/` — conformance fixtures for `compose-preview-known-differences/v1`",
    "",
    "**Generated. Do not hand-edit — run `node build-known-difference-fixtures.mjs` instead.**",
    "The recipe for every byte here is in that script, so a reviewer checks a fixture by reading how",
    "it was built rather than a hex dump.",
    "",
    "The contract these pin is",
    "[`COMPONENT_PARITY_WORKFLOW.md` §4](../../../../docs/design/COMPONENT_PARITY_WORKFLOW.md#the-normative-contract).",
    "**One runtime reads it today** — this repo's `known-differences.test.mjs`. Two more are *intended*",
    "consumers, and neither exists yet: `design-parity`'s own suite and the server projector's Kotlin",
    "tests, both batch 05's work. The layout assumes no language so those two can be written against it",
    "unchanged, but until they exist this tree has single-runtime coverage, and a divergence only the",
    "Kotlin engine would show is caught by nothing here. Saying so is the point: a README describing",
    "three live runners would let exactly that drift pass for cross-runtime agreement.",
    "",
    "## A case",
    "",
    "```",
    "cases/<case-id>/",
    "  case.json                  # the comparison, the catalog for the orphan walk, synthesis recipes",
    "  known-differences.json     # the document under test (raw text, so `document-unreadable` is reachable)",
    "  artifacts/<id>/mask.png    # `.design-parity/known-differences/<id>/` stands in here",
    "  artifacts/<id>/accepted-candidate.png",
    "  canonical-reference.png    # the comparison's canonical-plane rasters, already resampled",
    "  canonical-candidate.png",
    "  expected.json              # the verdict, and which of its keys are normative",
    "```",
    "",
    "`expected.json` is a **partial** pin: its `pins` array names the keys a runner must check. A key",
    "listed there must match exactly; a key that is absent is not pinned by any batch *yet*. The score",
    "stages — `raw`, `accepted`, `unaccepted` — are the ones batch 05 adds, over these same cases.",
    "",
    "The canonical-plane rasters arrive **already resampled**, deliberately. The portable kernel has its",
    "own group under `resample/`, so a resampler divergence fails there rather than surfacing as a wrong",
    "verdict in sixty gate cases at once — which is the entire reason for pinning intermediate stages.",
    "",
    "`synthesize` is how a case expresses a file too big to commit: pad the named base file to `padTo`",
    "bytes. The padding goes **inside the compressed stream** — empty stored deflate blocks and",
    "zero-length `IDAT` chunks — so the artifact stays a PNG a strict decoder accepts and decodes to",
    "exactly the image its base does, and the only thing the recipe changes is the encoded byte length.",
    "Appending bytes after `IEND` would be cheaper and wrong: `IEND` ends the datastream, so anything",
    "after it bypasses the chunk allowlist, the placement rules and every CRC.",
    "",
    "## The pilot population",
    "",
    "Measured rather than assumed, and smaller and more awkward than a dozen known differences",
    "suggests: **four issues across six sites**, of which exactly one is the shape the model was drawn",
    "around. Each has a case here.",
    "",
    "| Site | Mask | Case |",
    "| --- | --- | --- |",
    "| m3-catalog#40 `IconButton/Tonal` | a glyph — the worked example | `pilot-40-iconbutton-tonal-glyph` |",
    "| m3-catalog#41 `NavigationBar/Short` | most of the bar | `pilot-41-navigationbar-short` |",
    "| m3-catalog#87 `Checkbox/Checked` | a 2dp ring around a 20dp box | `pilot-87-checkbox-checked-ring` |",
    "| m3-catalog#42 ×3 (`Button/`, `Card/`, `ToggleButton/Elevated`) | a shadow surrounding each component | `pilot-42-elevated-shadow-trio` |",
    "",
    "#89 and #93 are indexable and have nothing to accept, which is why the two counts name different",
    "issues — six issues can carry a locator, four are acceptance candidates.",
    "",
    "## Every case",
    "",
    "| Case | What it pins |",
    "| --- | --- |",
    ...cases.map((entry) => `| \`${entry.id}\` | ${entry.title} |`),
    "",
    "## The resampler",
    "",
    "| Case | What it pins |",
    "| --- | --- |",
    ...resampleCases.map((entry) => `| \`${entry.id}\` | ${entry.title} |`),
    "",
    "## Sub-pixel rounding",
    "",
    "Outward, to the enclosing integer box. Its own group because every gate case is handed canonical",
    "boxes that are already integers — without these, a second engine could round inward or to nearest",
    "and still pass the whole suite.",
    "",
    "| Case | What it pins |",
    "| --- | --- |",
    ...roundingCases.map((entry) => `| \`${entry.id}\` | ${entry.title} |`),
    "",
  ].join("\n"),
);

process.stdout.write(
  `known-differences fixtures: ${cases.length} cases, ${resampleCases.length} resample cases, ` +
    `${roundingCases.length} rounding cases\n`,
);
