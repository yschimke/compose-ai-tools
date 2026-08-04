import { test } from "node:test";
import assert from "node:assert/strict";

import {
  SELECT_AXES,
  availableAxisValues,
  selectComponentImages,
  selectImages,
  selectLabel,
  selectOf,
} from "./catalog-select.mjs";
import { foldVariants } from "./catalog-variants.mjs";

const img = (size, extra = {}) => ({ variant: "ideal", state: "default", size, ...extra });

test("selectOf reads a selection and treats an empty one as none", () => {
  assert.deepEqual(selectOf({ select: { size: "largeRound" } }), { size: "largeRound" });
  assert.equal(selectOf({ select: {} }), undefined);
  assert.equal(selectOf({}), undefined);
  assert.equal(selectOf({ select: null }), undefined);
  assert.equal(selectOf({ select: ["largeRound"] }), undefined, "an array is not a selection");
});

test("selectImages keeps only the named axis value", () => {
  const images = [img("smallRound"), img("largeRound")];
  assert.deepEqual(selectImages(images, { size: "largeRound" }), [img("largeRound")]);
  assert.deepEqual(selectImages(images, { size: "xlRound" }), []);
});

test("selectImages returns every image when nothing is selected", () => {
  const images = [img("smallRound"), img("largeRound")];
  assert.deepEqual(selectImages(images, undefined), images);
  assert.deepEqual(selectImages(images, {}), images);
});

test("an untagged image never satisfies a selection", () => {
  // The untagged case IS the failure mode worth being strict about: a width no breakpoint declares
  // leaves the candidate reader's canonical size in place, so letting it through would hand the
  // entry a render from a device it did not ask for.
  assert.deepEqual(selectImages([{ variant: "ideal" }], { size: "largeRound" }), []);
});

test("selectLabel and availableAxisValues describe a failed selection", () => {
  assert.equal(selectLabel({ size: "largeRound" }), "size=largeRound");
  assert.deepEqual(
    availableAxisValues([img("smallRound"), img("largeRound"), { variant: "ideal" }], "size"),
    ["<untagged>", "largeRound", "smallRound"],
  );
});

test("only `size` is selectable today", () => {
  assert.deepEqual(SELECT_AXES, ["size"]);
});

// --- foldVariants ------------------------------------------------------------

test("a variant may select one breakpoint of another function's multipreview", () => {
  const byFunction = new Map([
    ["ScreenLoading", { images: [img("smallRound"), img("largeRound")] }],
  ]);
  const component = {
    componentId: "Screen/Home",
    preview: "Screen",
    variants: [{ state: "loading", preview: "ScreenLoading", select: { size: "largeRound" } }],
  };

  const { ideal, missing } = foldVariants([img("largeRound")], component, byFunction);

  assert.deepEqual(missing, []);
  assert.deepEqual(ideal, [img("largeRound"), img("largeRound", { state: "loading" })]);
});

test("a variant selection that matches nothing is a missing render, labelled with the axis", () => {
  const byFunction = new Map([["ScreenLoading", { images: [img("smallRound")] }]]);
  const component = {
    componentId: "Screen/Home",
    preview: "Screen",
    variants: [{ state: "loading", preview: "ScreenLoading", select: { size: "largeRound" } }],
  };

  const { ideal, missing } = foldVariants([img("largeRound")], component, byFunction);

  assert.deepEqual(ideal, [img("largeRound")]);
  assert.deepEqual(missing, ["Screen/Home [loading, size=largeRound]"]);
});

test("a same-function variant is satisfied by an already-selected default image", () => {
  // The component selected `largeRound` out of its own multipreview, so the fold was handed a
  // FILTERED view rather than the candidate's own array — the identity check alone would miss it
  // and re-append a duplicate.
  const candidate = { images: [img("smallRound"), img("largeRound")] };
  const byFunction = new Map([["Screen", candidate]]);
  const component = {
    componentId: "Screen/Home",
    preview: "Screen",
    variants: [{ preview: "Screen", select: { size: "largeRound" } }],
  };

  const { ideal, missing } = foldVariants([img("largeRound")], component, byFunction);

  assert.deepEqual(missing, []);
  assert.deepEqual(ideal, [img("largeRound")], "no duplicate appended");
});

// --- selectComponentImages: the driver's entry-level step --------------------

test("selectComponentImages narrows an entry to the breakpoint it selects", () => {
  const candidate = { images: [img("smallRound"), img("largeRound")] };
  const { images, missing } = selectComponentImages(
    { componentId: "Home/Large", preview: "HomeListViewPreview", select: { size: "largeRound" } },
    candidate,
  );
  assert.equal(missing, null);
  assert.deepEqual(images, [img("largeRound")]);
});

test("selectComponentImages returns the candidate's own array when nothing is selected", () => {
  const candidate = { images: [img("smallRound"), img("largeRound")] };
  const { images, missing } = selectComponentImages(
    { componentId: "Home", preview: "HomeListViewPreview" },
    candidate,
  );
  assert.equal(missing, null);
  // Identity, not a copy: foldVariants recognises a same-function variant partly by it.
  assert.equal(images, candidate.images);
});

test("a selection matching nothing reports what the function did render", () => {
  const candidate = { images: [img("smallRound"), img("largeRound")] };
  const { images, missing } = selectComponentImages(
    { componentId: "Home/XL", preview: "HomeListViewPreview", select: { size: "xlRound" } },
    candidate,
  );
  assert.deepEqual(images, []);
  assert.equal(
    missing,
    "Home/XL [select size=xlRound; HomeListViewPreview renders size ∈ {largeRound, smallRound}]",
  );
});
