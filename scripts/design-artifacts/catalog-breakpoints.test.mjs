import { test } from "node:test";
import assert from "node:assert/strict";

import {
  applySpecBreakpoints,
  catalogBreakpoints,
  undeclaredBreakpointDevices,
  DEFAULT_WEAR_BREAKPOINTS,
} from "./catalog-breakpoints.mjs";

test("Wear catalogs default to standard round device breakpoints", () => {
  assert.equal(
    catalogBreakpoints({ library: ["androidx.wear.compose:compose-material3"] }),
    DEFAULT_WEAR_BREAKPOINTS,
  );
  // Every default carries BOTH keys: the device id is what the built-in multipreview expansions
  // (`@WearPreviewDevices` → `id:wearos_small_round` + `id:wearos_large_round`) actually set, and
  // the width is what the live-preview bridge scores a candidate annotation against.
  assert.deepEqual(DEFAULT_WEAR_BREAKPOINTS, [
    { size: "smallRound", widthDp: 192, device: "id:wearos_small_round" },
    { size: "largeRound", widthDp: 227, device: "id:wearos_large_round" },
    { size: "xlRound", widthDp: 240, device: "id:wearos_xl_round" },
    { size: "smallSquare", widthDp: 180, device: "id:wearos_square" },
  ]);
});

test("explicit breakpoints override Wear defaults, including an empty opt-out", () => {
  const declared = [{ size: "xlRound", widthDp: 240 }];
  assert.equal(
    catalogBreakpoints({
      library: ["androidx.wear.compose:compose-material3"],
      breakpoints: declared,
    }),
    declared,
  );
  assert.deepEqual(
    catalogBreakpoints({
      library: ["androidx.wear.compose:compose-material3"],
      breakpoints: [],
    }),
    [],
  );
});

test("non-Wear catalogs do not acquire round device breakpoints", () => {
  assert.equal(
    catalogBreakpoints({ library: ["androidx.compose.material3:material3"] }),
    undefined,
  );
});

test("declared Wear breakpoints replace canonical compact sizes", () => {
  const candidates = [
    {
      componentId: "PodcastScreenPreview",
      previewId: "PodcastScreenPreview_Small_Round",
      images: [{ size: "compact" }, { size: "compact" }],
    },
  ];
  const previews = [
    {
      id: "PodcastScreenPreview_Small_Round",
      params: { widthDp: 192, heightDp: 192 },
      captures: [
        {},
        { params: { widthDp: 227, heightDp: 227 } },
      ],
    },
  ];

  const applied = applySpecBreakpoints(candidates, previews, [
    { size: "smallRound", widthDp: 192 },
    { size: "largeRound", widthDp: 227 },
  ]);

  assert.equal(applied, 2);
  assert.deepEqual(
    candidates[0].images.map((image) => image.size),
    ["smallRound", "largeRound"],
  );
});

test("capture params override preview params when resolving a breakpoint", () => {
  const candidates = [
    {
      componentId: "Screen",
      previewId: "Screen_Large",
      images: [{ size: "compact" }],
    },
  ];
  const previews = [
    {
      id: "Screen_Large",
      params: { widthDp: 192 },
      captures: [{ params: { widthDp: 227 } }],
    },
  ];

  applySpecBreakpoints(candidates, previews, [
    { size: "smallRound", widthDp: 192 },
    { size: "largeRound", widthDp: 227 },
  ]);

  assert.equal(candidates[0].images[0].size, "largeRound");
});

test("undeclared widths keep the candidate reader's canonical size", () => {
  const candidates = [
    {
      componentId: "TabletPreview",
      previewId: "TabletPreview",
      images: [{ size: "medium" }],
    },
  ];
  const previews = [
    {
      id: "TabletPreview",
      params: { widthDp: 700 },
    },
  ];

  const applied = applySpecBreakpoints(candidates, previews, [
    { size: "compactPhone", widthDp: 360 },
  ]);

  assert.equal(applied, 0);
  assert.equal(candidates[0].images[0].size, "medium");
});

test("a breakpoint's device id matches even when two devices share a width", () => {
  const candidates = [
    {
      componentId: "Screen",
      previewId: "Screen_Devices",
      images: [{ size: "compact" }, { size: "compact" }],
    },
  ];
  const previews = [
    {
      id: "Screen_Devices",
      params: { device: "id:wearos_small_round", widthDp: 192 },
      // Both expansions render 192 dp wide — only the device tells them apart, which is exactly
      // the case a width table silently collapses.
      captures: [{}, { params: { device: "id:wearos_square", widthDp: 192 } }],
    },
  ];

  const applied = applySpecBreakpoints(candidates, previews, [
    { size: "smallRound", device: "id:wearos_small_round", widthDp: 192 },
    { size: "smallSquare", device: "id:wearos_square", widthDp: 192 },
  ]);

  assert.equal(applied, 2);
  assert.deepEqual(
    candidates[0].images.map((image) => image.size),
    ["smallRound", "smallSquare"],
  );
});

test("width remains the fallback for a device the breakpoints do not name", () => {
  const candidates = [
    { componentId: "Screen", previewId: "Screen", images: [{ size: "compact" }] },
  ];
  const previews = [
    { id: "Screen", params: { device: "id:wearos_rect", widthDp: 227 } },
  ];

  applySpecBreakpoints(candidates, previews, [{ size: "largeRound", widthDp: 227 }]);

  assert.equal(candidates[0].images[0].size, "largeRound");
});

test("undeclaredBreakpointDevices names the devices no breakpoint claims", () => {
  const previews = [
    {
      id: "Screen_Devices",
      params: { device: "id:wearos_small_round", widthDp: 192 },
      captures: [{}, { params: { device: "id:wearos_xl_round", widthDp: 240 } }],
    },
    { id: "Other", params: { device: "id:wearos_xl_round", widthDp: 240 } },
  ];
  const breakpoints = [{ size: "smallRound", device: "id:wearos_small_round", widthDp: 192 }];

  assert.deepEqual(undeclaredBreakpointDevices(previews, breakpoints), ["id:wearos_xl_round"]);
  // A catalog with no size axis has opted out — nothing to report against.
  assert.deepEqual(undeclaredBreakpointDevices(previews, []), []);
  // A device resolved by width alone is declared, just not by id.
  assert.deepEqual(
    undeclaredBreakpointDevices(previews, [
      { size: "smallRound", widthDp: 192 },
      { size: "xlRound", widthDp: 240 },
    ]),
    [],
  );
});
