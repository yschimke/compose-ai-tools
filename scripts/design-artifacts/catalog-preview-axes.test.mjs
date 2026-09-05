import { test } from "node:test";
import assert from "node:assert/strict";

import { applySpecBreakpoints } from "./catalog-breakpoints.mjs";
import { applyCatalogPreviewAxes } from "./catalog-preview-axes.mjs";
import { catalogImagePath } from "./catalog-image-path.mjs";
import { foldVariants, outputAxisKey } from "./catalog-variants.mjs";

const candidate = (id, images = [{}]) => ({
  componentId: "HomePreview",
  previewId: id,
  images,
});

test("Wear font-scale previews become distinct catalog props axes", () => {
  const candidates = [candidate("Home_Fonts")];
  const previews = [
    {
      id: "Home_Fonts",
      params: { device: "id:wearos_small_round", widthDp: 192, fontScale: 1 },
      captures: [
        { params: { fontScale: 1.24 } },
      ],
    },
  ];

  const result = applyCatalogPreviewAxes(candidates, previews);

  assert.deepEqual(result, { fontScales: 1, duplicates: 0, locales: 0 });
  assert.deepEqual(candidates[0].images[0].props, { fontScale: "1.24" });
});

test("overlapping Wear multi-previews keep scaled/device renders and dedupe only small/default", () => {
  const candidates = [
    candidate("Home_Devices_Large"),
    candidate("Home_Devices_Small"),
    candidate("Home_Fonts_Small"),
    candidate("Home_Fonts_Normal"),
    candidate("Home_Fonts_Large"),
  ];
  const previews = [
    {
      id: "Home_Devices_Large",
      params: {
        group: "Devices - Large Round",
        device: "id:wearos_large_round",
        widthDp: 227,
        fontScale: 1,
      },
    },
    {
      id: "Home_Devices_Small",
      params: {
        group: "Devices - Small Round",
        device: "id:wearos_small_round",
        widthDp: 192,
        fontScale: 1,
      },
    },
    {
      id: "Home_Fonts_Small",
      params: {
        group: "Fonts - Small",
        device: "id:wearos_small_round",
        widthDp: 192,
        fontScale: 0.94,
      },
    },
    {
      id: "Home_Fonts_Normal",
      params: {
        group: "Fonts - Normal",
        device: "id:wearos_small_round",
        widthDp: 192,
        fontScale: 1,
      },
    },
    {
      id: "Home_Fonts_Large",
      params: {
        group: "Fonts - Large",
        device: "id:wearos_small_round",
        widthDp: 192,
        fontScale: 1.12,
      },
    },
  ];

  const result = applyCatalogPreviewAxes(candidates, previews);

  assert.deepEqual(result, { fontScales: 2, duplicates: 1, locales: 0 });
  assert.deepEqual(
    candidates.map(({ previewId, images }) => [previewId, images[0]?.props?.fontScale]),
    [
      ["Home_Devices_Large", undefined],
      ["Home_Devices_Small", undefined],
      ["Home_Fonts_Small", "0.94"],
      ["Home_Fonts_Normal", undefined],
      ["Home_Fonts_Large", "1.12"],
    ],
  );
  assert.equal(candidates[3].images.length, 0);

  applySpecBreakpoints(candidates, previews, [
    { size: "smallRound", widthDp: 192 },
    { size: "largeRound", widthDp: 227 },
  ]);
  const merged = candidates.flatMap(({ images }) => images);
  assert.doesNotThrow(() =>
    foldVariants(merged, { componentId: "Home" }, new Map()),
  );
  assert.deepEqual(
    merged.map(({ size, props }) => [size, props?.fontScale]),
    [
      ["largeRound", undefined],
      ["smallRound", undefined],
      ["smallRound", "0.94"],
      ["smallRound", "1.12"],
    ],
  );
});

test("equal display params do not collapse distinct synthetic states", () => {
  const candidates = [
    candidate("Home_Default", [{ state: "default" }]),
    candidate("Home_Selected", [{ state: "selected" }]),
  ];
  const previews = [
    { id: "Home_Default", params: { widthDp: 192, fontScale: 1 } },
    { id: "Home_Selected", params: { widthDp: 192, fontScale: 1 } },
  ];

  const result = applyCatalogPreviewAxes(candidates, previews);

  assert.equal(result.duplicates, 0);
  assert.equal(candidates[0].images.length, 1);
  assert.equal(candidates[1].images.length, 1);
});

test("an explicit same-function font-scale variant is not folded twice", () => {
  const candidates = [
    candidate("Feed_Default"),
    candidate("Feed_LargeFont"),
  ];
  const previews = [
    { id: "Feed_Default", params: { widthDp: 412, fontScale: 1 } },
    { id: "Feed_LargeFont", params: { widthDp: 412, fontScale: 2 } },
  ];

  applyCatalogPreviewAxes(candidates, previews);
  const images = candidates.flatMap((entry) => entry.images);
  const byFunction = new Map([["FeedScreenPreview", { images }]]);

  const { ideal, missing } = foldVariants(
    images,
    {
      componentId: "Screens/Feed",
      variants: [{ props: { fontScale: 2 }, preview: "FeedScreenPreview" }],
    },
    byFunction,
  );

  assert.deepEqual(missing, []);
  assert.equal(ideal.length, 2);
  assert.deepEqual(
    ideal.map((image) => image.props?.fontScale),
    [undefined, "2.0"],
  );
});

// --- the locale axis (issue #5059) -------------------------------------------

/** The DroidKaigi shape: one function, one arm per declared locale, identical otherwise. */
const localeFanOut = () => ({
  candidates: [
    { componentId: "LanguageToggleButtonPreview", previewId: "LanguageToggleButtonPreview_en", images: [{}] },
    { componentId: "LanguageToggleButtonPreview", previewId: "LanguageToggleButtonPreview_ja", images: [{}] },
  ],
  previews: [
    { id: "LanguageToggleButtonPreview_en", params: { locale: "en" } },
    { id: "LanguageToggleButtonPreview_ja", params: { locale: "ja" } },
  ],
});

test("a declared locale suffix becomes a props axis, so the arms stop colliding", () => {
  const { candidates, previews } = localeFanOut();

  const result = applyCatalogPreviewAxes(candidates, previews, undefined, ["en", "ja"]);

  assert.equal(result.locales, 2);
  assert.deepEqual(candidates[0].images[0].props, { locale: "en" });
  assert.deepEqual(candidates[1].images[0].props, { locale: "ja" });
  // The collision the catalog used to refuse on is exactly key equality, so this is the assertion
  // that matters: two arms, two output keys.
  assert.notEqual(
    outputAxisKey(candidates[0].images[0]),
    outputAxisKey(candidates[1].images[0]),
  );
});

test("without a locales declaration nothing is tagged, and the collision is the old one", () => {
  const { candidates, previews } = localeFanOut();

  const result = applyCatalogPreviewAxes(candidates, previews);

  assert.equal(result.locales, 0);
  assert.deepEqual(candidates[0].images[0].props ?? {}, {});
  assert.equal(
    outputAxisKey(candidates[0].images[0]),
    outputAxisKey(candidates[1].images[0]),
  );
});

test("an id naming no declared locale stays untagged, so the primary sticker is unchanged", () => {
  const candidates = [candidate("LanguageToggleButtonPreview")];
  const previews = [{ id: "LanguageToggleButtonPreview", params: {} }];

  const result = applyCatalogPreviewAxes(candidates, previews, undefined, ["en", "ja"]);

  assert.equal(result.locales, 0);
  assert.equal(candidates[0].images[0].props, undefined);
});

test("a locale is not matched mid-word, only at a segment boundary", () => {
  // `ja` inside `Ninja` is the boundary case `modeOfPreviewId` documents for modes; sharing the
  // matcher is what keeps the two axes from disagreeing about it.
  const candidates = [candidate("NinjaPreview_en"), candidate("SomethingNinja")];
  const previews = [{ id: "NinjaPreview_en", params: {} }, { id: "SomethingNinja", params: {} }];

  applyCatalogPreviewAxes(candidates, previews, undefined, ["en", "ja"]);

  assert.deepEqual(candidates[0].images[0].props, { locale: "en" });
  assert.equal(candidates[1].images[0].props, undefined);
});

test("the locale axis rides through to the sticker path, so the arms get separate PNGs", () => {
  const { candidates, previews } = localeFanOut();
  applyCatalogPreviewAxes(candidates, previews, undefined, ["en", "ja"]);

  assert.equal(
    catalogImagePath("language-toggle", candidates[1].images[0]),
    "images/language-toggle/ideal__default__locale-ja.png",
  );
});

