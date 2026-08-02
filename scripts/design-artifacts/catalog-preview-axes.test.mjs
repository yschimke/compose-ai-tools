import { test } from "node:test";
import assert from "node:assert/strict";

import { applySpecBreakpoints } from "./catalog-breakpoints.mjs";
import { applyCatalogPreviewAxes } from "./catalog-preview-axes.mjs";
import { foldVariants } from "./catalog-variants.mjs";

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

  assert.deepEqual(result, { fontScales: 1, duplicates: 0 });
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

  assert.deepEqual(result, { fontScales: 2, duplicates: 1 });
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
