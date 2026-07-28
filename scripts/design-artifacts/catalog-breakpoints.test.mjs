import { test } from "node:test";
import assert from "node:assert/strict";

import { applySpecBreakpoints } from "./catalog-breakpoints.mjs";

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
