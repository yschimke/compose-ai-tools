// Tests for the render-assertions evaluator (issue #5467).
//
// The cases are written around the failure this feature exists to catch: `:glimmer-catalog` typed
// every Glimmer role at weight 400 while claiming Google Sans Flex, and every existing gate said
// green. So the suite asserts on what those gates could NOT see — the resolved family, the
// per-node variation axes — and on the two ways an assertion framework quietly stops asserting:
// matching no data, and accumulating exceptions nobody revisits.

import test from "node:test";
import assert from "node:assert/strict";

import {
  SUPPORTED,
  evaluate,
  formatResult,
  observe,
  predicateFor,
  previewMatches,
  productsFromEntries,
  runAssertions,
  validateAssertions,
} from "./render-assertions.mjs";

// ---------------------------------------------------------------- fixtures

/** A `fonts-used` product where every face resolved to the family it asked for. */
const fontsGood = {
  fonts: [
    { requestedFamily: "Google Sans Flex", resolvedFamily: "Google Sans Flex", weight: 400 },
    { requestedFamily: "Google Sans Flex", resolvedFamily: "Google Sans Flex", weight: 750 },
  ],
};

/** The Glimmer bug's shape: the request was honoured by name, the face was not. */
const fontsFellBack = {
  fonts: [
    { requestedFamily: "Google Sans Flex", resolvedFamily: "Google Sans Flex", weight: 400 },
    {
      requestedFamily: "Google Sans Flex",
      resolvedFamily: "Roboto",
      weight: 750,
      fellBackFrom: "Google Sans Flex",
    },
  ],
};

const semantics = (nodes) => ({ root: { nodeId: "root", children: nodes } });
const textNode = (id, family, axes) => ({
  nodeId: id,
  text: id,
  typography: { fontFamily: family, fontVariationSettings: axes },
});

const assertFamily = {
  id: "glimmer-types-in-google-sans-flex",
  product: "fonts-used",
  because: "a sticker sheet that silently types in Roboto is not the design system it claims",
  require: { "everyFont.resolvedFamily": "Google Sans Flex" },
};

// ---------------------------------------------------------------- validation

test("validateAssertions accepts a well-formed document", () => {
  assert.deepEqual(validateAssertions({ assertions: [assertFamily] }), []);
});

test("validateAssertions rejects an unknown product rather than skipping it", () => {
  const errors = validateAssertions({
    assertions: [{ ...assertFamily, product: "pixel-histogram" }],
  });
  assert.equal(errors.length, 1);
  assert.match(errors[0], /unknown product "pixel-histogram"/);
});

test("validateAssertions rejects a path the product does not expose", () => {
  const errors = validateAssertions({
    assertions: [{ ...assertFamily, require: { "everyFont.hintingMode": "slight" } }],
  });
  assert.equal(errors.length, 1);
  assert.match(errors[0], /unknown path "everyFont.hintingMode"/);
});

test("validateAssertions requires a because, an id, and unique ids", () => {
  const errors = validateAssertions({
    assertions: [
      { ...assertFamily, because: "   " },
      { ...assertFamily, id: "" },
      assertFamily,
      assertFamily,
    ],
  });
  assert.ok(errors.some((e) => /needs a "because"/.test(e)));
  assert.ok(errors.some((e) => /needs a non-empty id/.test(e)));
  assert.ok(errors.some((e) => /duplicate id/.test(e)));
});

test("validateAssertions requires every exception to carry a reason", () => {
  const errors = validateAssertions({
    assertions: [{ ...assertFamily, exceptions: [{ preview: "LegacyBanner" }] }],
  });
  assert.equal(errors.length, 1);
  assert.match(errors[0], /needs a "reason"/);
});

test("validateAssertions requires exactly one path per require", () => {
  const two = validateAssertions({
    assertions: [
      {
        ...assertFamily,
        require: {
          "everyFont.resolvedFamily": "Google Sans Flex",
          "everyFont.requestedFamily": "Google Sans Flex",
        },
      },
    ],
  });
  assert.ok(two.some((e) => /exactly one path/.test(e)));
  assert.ok(
    validateAssertions({ assertions: [{ ...assertFamily, require: {} }] }).some((e) =>
      /exactly one path/.test(e),
    ),
  );
});

test("validateAssertions rejects a document that is not a list of assertions", () => {
  assert.deepEqual(validateAssertions(null), ["not an object"]);
  assert.deepEqual(validateAssertions({}), ["`assertions` must be an array"]);
});

test("every path named in SUPPORTED is one observe actually implements", () => {
  const data = {
    "fonts-used": fontsFellBack,
    "compose-semantics": semantics([textNode("Display", "Google Sans Flex", "'wght' 750")]),
  };
  for (const [product, paths] of Object.entries(SUPPORTED))
    for (const path of paths)
      assert.ok(
        observe(product, path, data[product]).length > 0,
        `${product} ${path} observed nothing — the path is declared but not read`,
      );
});

// ---------------------------------------------------------------- matching

test("previewMatches globs only where a * is written", () => {
  assert.ok(previewMatches("*", "Anything"));
  assert.ok(previewMatches("Glimmer*", "GlimmerStickerSheet"));
  assert.ok(previewMatches("*Sticker*", "GlimmerStickerSheet"));
  assert.ok(!previewMatches("Glimmer", "GlimmerStickerSheet"));
  // A pattern's regex metacharacters are literal, so a dotted id cannot match a neighbour.
  assert.ok(!previewMatches("a.c", "abc"));
});

// ---------------------------------------------------------------- the Glimmer case

test("a preview whose resolved family fell back to Roboto fails", () => {
  const result = evaluate(assertFamily, { GlimmerStickerSheet: fontsFellBack });
  assert.equal(result.failures.length, 1);
  assert.equal(result.failures[0].preview, "GlimmerStickerSheet");
  assert.match(result.failures[0].observed.join(" "), /Roboto/);
});

test("a preview that resolved the family it asked for passes", () => {
  const result = evaluate(assertFamily, { GlimmerStickerSheet: fontsGood });
  assert.deepEqual(result.failures, []);
  assert.equal(result.checked, 1);
});

test("the report names the observed value and the reason, not just the id", () => {
  const text = formatResult(assertFamily, evaluate(assertFamily, { Sheet: fontsFellBack }));
  assert.match(text, /^FAIL glimmer-types-in-google-sans-flex/m);
  assert.match(text, /Roboto/);
  assert.match(text, /because: a sticker sheet/);
});

test("a passing assertion reports the count it actually checked", () => {
  const text = formatResult(assertFamily, evaluate(assertFamily, { A: fontsGood, B: fontsGood }));
  assert.equal(text, "ok   glimmer-types-in-google-sans-flex (2 previews)");
});

// ---------------------------------------------------------------- the variation-axis case

const assertAxes = {
  id: "glimmer-roles-carry-their-weight-axis",
  product: "compose-semantics",
  because: "every Glimmer role collapsed to wght 400 while the family still read as correct",
  require: { "everyTextNode.typography.fontVariationSettings": "contains 'wght'" },
};

test("text nodes that lost their variation axes fail even though the family is right", () => {
  const collapsed = semantics([
    textNode("Display", "Google Sans Flex", null),
    textNode("Body", "Google Sans Flex", "'wght' 400"),
  ]);
  const result = evaluate(assertAxes, { GlimmerTypeScale: collapsed });
  assert.equal(result.failures.length, 1);
  assert.match(result.failures[0].observed.join(" "), /Display/);
});

test("text nodes carrying their axes pass, and nested children are walked", () => {
  const nested = {
    root: {
      nodeId: "root",
      children: [
        { nodeId: "column", children: [textNode("Display", "Google Sans Flex", "'wght' 750")] },
      ],
    },
  };
  assert.deepEqual(evaluate(assertAxes, { GlimmerTypeScale: nested }).failures, []);
});

// ---------------------------------------------------------------- exceptions

const withException = {
  ...assertFamily,
  exceptions: [
    { preview: "LegacyBanner", reason: "ships a baked bitmap wordmark; tracked in #5467" },
  ],
};

test("a reasoned exception excuses its preview and is not counted as checked", () => {
  const result = evaluate(withException, {
    GlimmerStickerSheet: fontsGood,
    LegacyBanner: fontsFellBack,
  });
  assert.deepEqual(result.failures, []);
  assert.deepEqual(result.staleExceptions, []);
  assert.equal(result.checked, 1);
});

test("an exception for a preview that now passes is itself a failure", () => {
  const result = evaluate(withException, { LegacyBanner: fontsGood });
  assert.deepEqual(result.staleExceptions, ["LegacyBanner"]);
  assert.match(formatResult(withException, result), /exception for "LegacyBanner" is stale/);
});

test("an exception for a preview that no longer exists is a failure", () => {
  const result = evaluate(withException, { GlimmerStickerSheet: fontsGood });
  assert.deepEqual(result.staleExceptions, ["LegacyBanner"]);
});

// ---------------------------------------------------------------- the silent-pass failure modes

test("a preview with no data for the product fails rather than passing vacuously", () => {
  const result = evaluate(assertFamily, { GlimmerStickerSheet: { fonts: [] } });
  assert.deepEqual(result.noData, ["GlimmerStickerSheet"]);
  assert.match(formatResult(assertFamily, result), /an assertion matching nothing is not a pass/);
});

test("a missing product is no-data, not a pass", () => {
  assert.deepEqual(evaluate(assertFamily, { GlimmerStickerSheet: undefined }).noData, [
    "GlimmerStickerSheet",
  ]);
});

test("appliesTo narrows the set, and a narrowing that matches nothing checks nothing", () => {
  const scoped = { ...assertFamily, appliesTo: { previews: ["Glimmer*"] } };
  const result = evaluate(scoped, { GlimmerSheet: fontsGood, WearWatchFace: fontsFellBack });
  assert.deepEqual(result.failures, []);
  assert.equal(result.checked, 1);
  assert.equal(evaluate(scoped, { WearWatchFace: fontsFellBack }).checked, 0);
});

// ---------------------------------------------------------------- quantifier semantics

test("noFont.* is universal: one face falling back fails the sheet", () => {
  const noFallback = {
    id: "no-face-falls-back",
    product: "fonts-used",
    because: "one fallback face in twenty is still the wrong type on screen",
    require: { "noFont.fellBackFrom": null },
  };
  assert.equal(evaluate(noFallback, { Sheet: fontsFellBack }).failures.length, 1);
  assert.deepEqual(evaluate(noFallback, { Sheet: fontsGood }).failures, []);
});

test("anyNode.* is existential — the Wear position-indicator case", () => {
  const indicator = {
    id: "scrolling-wear-screens-show-a-position-indicator",
    product: "compose-semantics",
    because: "a scrollable Wear screen with no position indicator strands the user mid-list",
    require: { "anyNode.role": "ScrollPositionIndicator" },
  };
  const withIndicator = semantics([
    { nodeId: "list", role: "ScrollableContainer" },
    { nodeId: "indicator", role: "ScrollPositionIndicator" },
  ]);
  const without = semantics([{ nodeId: "list", role: "ScrollableContainer" }]);
  assert.deepEqual(evaluate(indicator, { WearList: withIndicator }).failures, []);
  assert.equal(evaluate(indicator, { WearList: without }).failures.length, 1);
});

test("predicateFor distinguishes absence, substring and exact equality", () => {
  assert.ok(predicateFor("noFont.fellBackFrom", null)({ value: null }));
  assert.ok(!predicateFor("noFont.fellBackFrom", null)({ value: "Google Sans Flex" }));
  assert.ok(predicateFor("everyFont.resolvedFamily", "contains Sans")({ value: "Google Sans" }));
  assert.ok(!predicateFor("everyFont.resolvedFamily", "contains Sans")({ value: "Roboto" }));
  assert.ok(predicateFor("everyFont.resolvedFamily", "Roboto")({ value: "Roboto" }));
  // A null observation never satisfies a substring check by stringifying to "null".
  assert.ok(!predicateFor("everyFont.resolvedFamily", "contains ul")({ value: null }));
});

// ---------------------------------------------------------------- bundle indexing

const enc = (value) => new TextEncoder().encode(JSON.stringify(value));

test("productsFromEntries indexes both sidecar kinds by preview id", () => {
  const { products, unreadable } = productsFromEntries({
    "previews/GlimmerSheet.fonts.json": enc(fontsGood),
    "previews/GlimmerSheet.semantics.json": enc(semantics([])),
    "previews/GlimmerSheet.png": new Uint8Array([1, 2, 3]),
    "bundle.json": enc({}),
  });
  assert.deepEqual(unreadable, []);
  assert.deepEqual(Object.keys(products["fonts-used"]), ["GlimmerSheet"]);
  assert.deepEqual(Object.keys(products["compose-semantics"]), ["GlimmerSheet"]);
  assert.deepEqual(products["fonts-used"].GlimmerSheet, fontsGood);
});

test("a preview id containing dots keeps its full id", () => {
  const { products } = productsFromEntries({
    "previews/pkg.ScreenPreview.fonts.json": enc(fontsGood),
  });
  assert.deepEqual(Object.keys(products["fonts-used"]), ["pkg.ScreenPreview"]);
});

test("productsFromEntries accepts string entries as well as bytes", () => {
  const { products } = productsFromEntries({
    "previews/A.fonts.json": JSON.stringify(fontsGood),
  });
  assert.deepEqual(products["fonts-used"].A, fontsGood);
});

test("an unparseable sidecar is surfaced, not silently dropped", () => {
  const { products, unreadable } = productsFromEntries({
    "previews/Broken.fonts.json": new TextEncoder().encode("not json"),
  });
  assert.equal(Object.keys(products["fonts-used"]).length, 0);
  assert.equal(unreadable.length, 1);
  assert.match(unreadable[0], /previews\/Broken\.fonts\.json/);
});

// ---------------------------------------------------------------- the runner

test("runAssertions refuses to evaluate a document that does not validate", () => {
  const { ok, results, report } = runAssertions(
    { assertions: [{ ...assertFamily, product: "pixel-histogram" }] },
    { "fonts-used": { Sheet: fontsGood } },
  );
  assert.equal(ok, false);
  assert.deepEqual(results, []);
  assert.match(report, /invalid render-assertions document/);
});

test("runAssertions routes each assertion to its own product", () => {
  const doc = { assertions: [assertFamily, assertAxes] };
  const products = {
    "fonts-used": { Sheet: fontsGood },
    "compose-semantics": {
      Sheet: semantics([textNode("Display", "Google Sans Flex", "'wght' 750")]),
    },
  };
  const { ok, results } = runAssertions(doc, products);
  assert.equal(ok, true);
  assert.deepEqual(
    results.map((r) => r.id),
    [assertFamily.id, assertAxes.id],
  );
});

test("an assertion that matched no preview fails rather than passing vacuously", () => {
  // An empty render set, a module dropped from the catalog, and an `appliesTo` whose pattern no
  // longer matches anything all land here: a rule that reads as coverage while asserting nothing.
  const empty = runAssertions({ assertions: [assertFamily] }, {});
  assert.equal(empty.ok, false);
  assert.match(empty.report, /matched no preview/);

  const scoped = { ...assertFamily, appliesTo: { previews: ["Renamed*"] } };
  const missed = runAssertions(
    { assertions: [scoped] },
    { "fonts-used": { GlimmerSheet: fontsGood } },
  );
  assert.equal(missed.ok, false);
  assert.match(missed.report, /matched no preview/);
});

test("runAssertions fails on a failure, a no-data preview, or a stale exception alike", () => {
  const fail = runAssertions(
    { assertions: [assertFamily] },
    { "fonts-used": { S: fontsFellBack } },
  );
  assert.equal(fail.ok, false);
  const noData = runAssertions(
    { assertions: [assertFamily] },
    { "fonts-used": { S: { fonts: [] } } },
  );
  assert.equal(noData.ok, false);
  const stale = runAssertions(
    { assertions: [withException] },
    { "fonts-used": { LegacyBanner: fontsGood } },
  );
  assert.equal(stale.ok, false);
});
