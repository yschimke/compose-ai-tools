import { test } from "node:test";
import assert from "node:assert/strict";

import { RENDER_PIXELS, catalogTagIndex, tagIndex } from "./tag-index.mjs";

const node = (bounds, testTag, children = []) => ({
  nodeId: "n",
  boundsInRoot: bounds,
  ...(testTag === undefined ? {} : { testTag }),
  children,
});

const index = (root) => tagIndex({ root });

test("a unique tag reports count one and its render-pixel box", () => {
  const tags = index(node("0,0,100,100", undefined, [node("24,24,48,48", "glyph")]));
  assert.deepEqual(Object.keys(tags), ["glyph"]);
  assert.equal(tags.glyph.count, 1);
  assert.deepEqual(tags.glyph.bounds, { x: 24, y: 24, width: 24, height: 24 });
  assert.equal(tags.glyph.space, RENDER_PIXELS);
});

test("a repeated tag reports every occurrence", () => {
  const tags = index(
    node("0,0,100,100", undefined, [
      node("0,0,20,20", "row"),
      node("0,20,20,40", "row"),
      node("0,40,20,60", "row"),
    ]),
  );
  assert.equal(tags.row.count, 3);
});

// The case the count field exists for: dropping a zero-area duplicate would report `count: 1` for a
// tag two nodes carry, and an acceptance would resolve it as unique.
test("a duplicate with no usable bounds still raises the count", () => {
  const tags = index(
    node("0,0,100,100", undefined, [
      node("10,10,30,30", "chip"),
      node("40,40,40,40", "chip"), // zero area
      node("not,a,box", "chip"), // unparseable
    ]),
  );
  assert.equal(tags.chip.count, 3);
  // The first usable box wins, so the geometry is still the drawable node's.
  assert.deepEqual(tags.chip.bounds, { x: 10, y: 10, width: 20, height: 20 });
});

test("a tag whose only node has no usable bounds reports a count and no box", () => {
  const tags = index(node("0,0,100,100", undefined, [node("5,5,5,5", "ghost")]));
  assert.equal(tags.ghost.count, 1);
  assert.equal(tags.ghost.bounds, undefined);
});

test("the first usable box in depth-first order is the one reported", () => {
  const tags = index(
    node("0,0,100,100", undefined, [
      node("0,0,50,50", undefined, [node("1,1,11,11", "dup")]),
      node("50,50,90,90", "dup"),
    ]),
  );
  assert.equal(tags.dup.count, 2);
  assert.deepEqual(tags.dup.bounds, { x: 1, y: 1, width: 10, height: 10 });
});

// Compose matches a testTag as the exact string. Trimming would merge these into one `count: 2`
// entry — false ambiguity for "pad", and no key at all for an acceptance recording " pad ".
test("a tag is keyed verbatim, not trimmed", () => {
  const tags = index(
    node("0,0,100,100", undefined, [node("0,0,10,10", "pad"), node("20,20,30,30", " pad ")]),
  );
  assert.deepEqual(Object.keys(tags).sort(), [" pad ", "pad"]);
  assert.equal(tags["pad"].count, 1);
  assert.equal(tags[" pad "].count, 1);
});

test("blank and absent tags are not keys", () => {
  const tags = index(
    node("0,0,100,100", undefined, [
      node("0,0,10,10"),
      node("0,0,10,10", ""),
      node("0,0,10,10", "   "),
    ]),
  );
  assert.deepEqual(Object.keys(tags), []);
});

test("a tagged root is indexed like any other node", () => {
  const tags = index(node("0,0,64,64", "root-tag"));
  assert.equal(tags["root-tag"].count, 1);
  assert.deepEqual(tags["root-tag"].bounds, { x: 0, y: 0, width: 64, height: 64 });
});

// A catalog is third-party data and `__proto__` is a perfectly good testTag. Keying a plain object
// literal with it mutates the prototype instead of creating an own property, so the entry vanishes
// and duplicate detection breaks — the same hazard the Kotlin side answers by keying a Map.
test("a __proto__ tag becomes an own property rather than mutating the prototype", () => {
  const tags = index(node("0,0,100,100", undefined, [node("0,0,10,10", "__proto__")]));
  assert.equal(Object.prototype.hasOwnProperty.call(tags, "__proto__"), true);
  assert.equal(tags["__proto__"].count, 1);
  assert.equal({}.hasOwnProperty("__proto__"), false, "the global prototype must be untouched");
});

test("a malformed or absent payload yields an empty index rather than throwing", () => {
  assert.deepEqual(Object.keys(tagIndex(undefined)), []);
  assert.deepEqual(Object.keys(tagIndex({})), []);
  assert.deepEqual(Object.keys(tagIndex({ root: null })), []);
});

// --- the catalog-level join -------------------------------------------------

const bundleWith = (trees) => ({
  previews: Object.keys(trees).map((id) => ({ id })),
  entries: Object.fromEntries(
    Object.entries(trees).map(([id, tree]) => [
      `previews/${id}.semantics.json`,
      new TextEncoder().encode(JSON.stringify(tree)),
    ]),
  ),
});

const manifestWith = (images) => ({
  components: [{ componentId: "Button/Filled", images }],
});

test("the index is keyed by the SERVED preview id, not the daemon one", () => {
  const out = catalogTagIndex(
    manifestWith([
      { path: "images/button-filled/ideal__default__light.png", previewId: "Filled_Light" },
    ]),
    [bundleWith({ Filled_Light: { root: node("0,0,100,40", undefined, [node("8,8,32,32", "glyph")]) } })],
  );
  // `Filled_Light` is the daemon id; the route the viewer serves is derived from the image path.
  assert.deepEqual(Object.keys(out.previews), ["button-filled__ideal__default__light"]);
  assert.equal(out.previews["button-filled__ideal__default__light"].glyph.count, 1);
  assert.equal(out.indexed, 1);
  assert.equal(out.gaps, 0);
  assert.equal(out.schema, "compose-preview-tags/v1");
});

// Bounds are per-variant, so a sibling variant's tree describes different pixels. An absent entry
// costs an element gate; a wrong one produces a wrong `element-moved` verdict.
test("an unbridged image is skipped rather than borrowing a sibling's tree", () => {
  const out = catalogTagIndex(
    manifestWith([
      { path: "images/button-filled/ideal__default__light.png", previewId: "Filled_Light" },
      { path: "images/button-filled/ideal__default__dark.png" }, // no previewId — never bridged
    ]),
    [bundleWith({ Filled_Light: { root: node("0,0,100,40", undefined, [node("8,8,32,32", "glyph")]) } })],
  );
  assert.deepEqual(Object.keys(out.previews), ["button-filled__ideal__default__light"]);
  assert.equal(out.gaps, 0, "an image that never bridged is not a gap — there is nothing to carry");
});

test("a bridged image whose bundle carried no tree is counted as a gap", () => {
  const out = catalogTagIndex(
    manifestWith([
      { path: "images/button-filled/ideal__default__dark.png", previewId: "Filled_Dark" },
    ]),
    [bundleWith({ Filled_Light: { root: node("0,0,10,10", "x") } })],
  );
  assert.deepEqual(Object.keys(out.previews), []);
  assert.equal(out.gaps, 1, "a bridged image with no carried tree is the case worth reporting");
});

test("trees are folded across bundles, the supplement winning", () => {
  const primary = bundleWith({ Filled_Light: { root: node("0,0,10,10", "from-primary") } });
  const extra = bundleWith({ Filled_Light: { root: node("0,0,10,10", "from-extra") } });
  const out = catalogTagIndex(
    manifestWith([
      { path: "images/button-filled/ideal__default__light.png", previewId: "Filled_Light" },
    ]),
    [primary, extra],
  );
  assert.deepEqual(
    Object.keys(out.previews["button-filled__ideal__default__light"]),
    ["from-extra"],
  );
});

test("a malformed sidecar costs that preview its entry, not the catalog", () => {
  const bundle = {
    previews: [{ id: "Filled_Light" }, { id: "Filled_Dark" }],
    entries: {
      "previews/Filled_Light.semantics.json": new TextEncoder().encode("{ not json"),
      "previews/Filled_Dark.semantics.json": new TextEncoder().encode(
        JSON.stringify({ root: node("0,0,10,10", "ok") }),
      ),
    },
  };
  const out = catalogTagIndex(
    manifestWith([
      { path: "images/button-filled/ideal__default__light.png", previewId: "Filled_Light" },
      { path: "images/button-filled/ideal__default__dark.png", previewId: "Filled_Dark" },
    ]),
    [bundle],
  );
  assert.deepEqual(Object.keys(out.previews), ["button-filled__ideal__default__dark"]);
  assert.equal(out.gaps, 1);
});

test("a preview whose tree carries no tags contributes no entry", () => {
  const out = catalogTagIndex(
    manifestWith([
      { path: "images/button-filled/ideal__default__light.png", previewId: "Filled_Light" },
    ]),
    [bundleWith({ Filled_Light: { root: node("0,0,100,40") } })],
  );
  assert.deepEqual(Object.keys(out.previews), []);
});

test("an empty or absent manifest yields an empty index rather than throwing", () => {
  assert.deepEqual(Object.keys(catalogTagIndex(undefined, []).previews), []);
  assert.deepEqual(Object.keys(catalogTagIndex({}, undefined).previews), []);
});
