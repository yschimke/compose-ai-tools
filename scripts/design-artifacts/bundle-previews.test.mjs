import { test } from "node:test";
import assert from "node:assert/strict";

import { dropNonRasterPreviews } from "./bundle-previews.mjs";

const enc = (s) => new TextEncoder().encode(s);

test("keeps PNG-backed previews and drops PNG-less (animated GIF) ones", () => {
  const bundle = {
    previews: [
      { id: "Card_Light" },
      { id: "Card_Dark" },
      { id: "CardScalingScrollGif_Large Round" }, // GIF preview: only a .gif in the zip
    ],
    entries: {
      "previews/Card_Light.png": enc("png"),
      "previews/Card_Dark.png": enc("png"),
      "previews/CardScalingScrollGif_Large Round.gif": enc("gif"),
    },
  };
  const dropped = dropNonRasterPreviews(bundle);
  assert.deepEqual(dropped, ["CardScalingScrollGif_Large Round"]);
  assert.deepEqual(
    bundle.previews.map((p) => p.id),
    ["Card_Light", "Card_Dark"],
  );
});

test("no-op when every preview has a PNG", () => {
  const bundle = {
    previews: [{ id: "A" }, { id: "B" }],
    entries: { "previews/A.png": enc("x"), "previews/B.png": enc("x") },
  };
  assert.deepEqual(dropNonRasterPreviews(bundle), []);
  assert.equal(bundle.previews.length, 2);
});

test("tolerates a bundle with no previews / no entries", () => {
  assert.deepEqual(dropNonRasterPreviews({}), []);
  assert.deepEqual(dropNonRasterPreviews({ previews: [{ id: "X" }] }), ["X"]);
});
