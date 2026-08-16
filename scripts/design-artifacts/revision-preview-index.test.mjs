import assert from "node:assert/strict";
import test from "node:test";
import { SCHEMA, previewIds, updateRevisionPreviewIndex } from "./revision-preview-index.mjs";

const catalog = (...paths) => ({ components: [{ images: paths.map((path) => ({ path })) }] });

test("preview ids use the server route identity", () => {
  assert.deepEqual(previewIds(catalog("images/dialog-list/ideal__icon.png", "not-an-image")), [
    "dialog-list__ideal__icon",
  ]);
});

test("the previous current inventory is promoted under the parent commit", () => {
  const prior = {
    schema: SCHEMA,
    current: ["old", "shared"],
    revisions: [{ commit: "aaaaaaa", previews: ["older"] }],
  };
  assert.deepEqual(updateRevisionPreviewIndex(catalog("images/new/ideal.png"), prior, "bbbbbbb"), {
    schema: SCHEMA,
    current: ["new__ideal"],
    revisions: [
      { commit: "bbbbbbb", previews: ["old", "shared"] },
      { commit: "aaaaaaa", previews: ["older"] },
    ],
  });
});

test("a branch without an index starts with only current", () => {
  assert.deepEqual(updateRevisionPreviewIndex(catalog("images/new/ideal.png"), null, "bbbbbbb"), {
    schema: SCHEMA,
    current: ["new__ideal"],
    revisions: [],
  });
});
