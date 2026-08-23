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
  COLOUR_PALETTE,
  COLOUR_RGB,
  buildPng,
  chunk,
  encodePng,
  idat,
  ihdr,
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
  const accepted = crop(candidate, box);

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
  const world = glyphWorld();
  const record = glyphRecord(world);
  addCase({
    id: "gate-element-ambiguous",
    title: "The tag is carried by more than one node",
    why:
      "Uniqueness is re-checked at evaluation time, against the full semantics payload rather than " +
      "the annotation layer — it was unique when the acceptance was authored, and only this check " +
      "notices when it stops being. Ambiguity short-circuits the *bounds* check, so the causes list " +
      "is exactly `[element-ambiguous]`.",
    document: document([record]),
    files: glyphFiles(world, record),
    comparison: glyphComparison(world, {
      tagIndex: { "iconbutton-tonal-glyph": { count: 2, bounds: { x: 8, y: 8, width: 8, height: 8 } } },
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
    document: documentText ?? document([record]),
    files,
    comparison: glyphComparison(world, comparisonOverrides),
    catalog,
    synthesize,
    expected,
  });
}

const refused = (reasons, recordId = "m3-iconbutton-tonal-glyph") => ({
  pins: ["statuses", "validationFailures"],
  statuses: { [recordId]: { status: "refused", reasons } },
  validationFailures: reasons.map((reason) => ({ id: recordId, reason })),
});

// --- the document itself -----------------------------------------------------------------------

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

  const bigger = lyingGreyPng(8000, 8001);
  addCase({
    id: "document-pixels-over-cap",
    title: "128,008,000 megapixels declared — one raster past the cap",
    why:
      "**Compare as you go and short-circuit.** Summing across a third-party set is exactly where " +
      "two engines diverge silently: a Kotlin accumulator can wrap into a value that sits under the " +
      "cap while JavaScript keeps a large positive `Number` and rejects, and the offline consumer " +
      "then allocates what the browser refused.",
    document: document([
      glyphRecord(world, { maskSha256: sha256Hex(mask), acceptedCandidateSha256: sha256Hex(bigger) }),
    ]),
    files: {
      "artifacts/m3-iconbutton-tonal-glyph/mask.png": mask,
      "artifacts/m3-iconbutton-tonal-glyph/accepted-candidate.png": bigger,
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
  // 8 MiB + 1 byte. The padding is a synthesis instruction rather than a committed blob: any
  // runtime can materialise it from the recipe — append this many zero bytes to the base file — and
  // the repo stores a few hundred bytes instead of eight megabytes. Trailing bytes after `IEND` are
  // ignored by every decoder and never reached by a preflight that stops at the first `IDAT`, so
  // the only thing the padding changes is the one thing under test: the encoded byte length.
  const world = glyphWorld();
  const base = encodePng({ width: 24, height: 24, colourType: COLOUR_GREY, samples: (() => {
    const samples = new Uint8Array(24 * 24);
    for (let y = 8; y < 16; y++) for (let x = 8; x < 16; x++) samples[y * 24 + x] = 255;
    return samples;
  })() });
  const target = 8 * 1024 * 1024 + 1;
  const materialised = new Uint8Array(target);
  materialised.set(base, 0);
  const atCap = new Uint8Array(8 * 1024 * 1024);
  atCap.set(base, 0);
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
        padZerosTo: 8 * 1024 * 1024,
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
        padZerosTo: target,
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
    "`synthesize` is how a case expresses a file too big to commit: append `padZerosTo - length` zero",
    "bytes to the named base file. Trailing bytes after `IEND` are ignored by every decoder and never",
    "reached by a preflight that stops at the first `IDAT`, so the only thing they change is the encoded",
    "byte length.",
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
