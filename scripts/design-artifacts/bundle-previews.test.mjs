import { test } from "node:test";
import assert from "node:assert/strict";

import { candidatePreviewBundle, daemonPreviewIdsByFunction } from "./bundle-previews.mjs";

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
  const { bundle: view, dropped } = candidatePreviewBundle(bundle);
  assert.deepEqual(dropped, ["CardScalingScrollGif_Large Round"]);
  assert.deepEqual(
    view.previews.map((p) => p.id),
    ["Card_Light", "Card_Dark"],
  );
});

test("does not mutate the original bundle — catalog-token sheets survive for the token pass", () => {
  // A @ThemeCatalog/@ColorCatalog sheet is rendered PNG-less by design; its tokens are read later
  // via catalogTokensFromBundle(bundle). Filtering must not strip it from the original bundle.
  const bundle = {
    previews: [{ id: "Button_Light" }, { id: "MeshCoreLight_theme" }],
    entries: {
      "previews/Button_Light.png": enc("png"),
      "previews/MeshCoreLight_theme.catalog.json": enc("{}"),
    },
  };
  const { bundle: view, dropped } = candidatePreviewBundle(bundle);
  // The candidate view excludes the PNG-less sheet…
  assert.deepEqual(dropped, ["MeshCoreLight_theme"]);
  assert.deepEqual(
    view.previews.map((p) => p.id),
    ["Button_Light"],
  );
  // …but the original bundle still carries it (and its entries) for token extraction.
  assert.deepEqual(
    bundle.previews.map((p) => p.id),
    ["Button_Light", "MeshCoreLight_theme"],
  );
  assert.ok(bundle.entries["previews/MeshCoreLight_theme.catalog.json"]);
});

test("no-op view when every preview has a PNG", () => {
  const bundle = {
    previews: [{ id: "A" }, { id: "B" }],
    entries: { "previews/A.png": enc("x"), "previews/B.png": enc("x") },
  };
  const { bundle: view, dropped } = candidatePreviewBundle(bundle);
  assert.deepEqual(dropped, []);
  assert.equal(view.previews.length, 2);
});

test("tolerates a bundle with no previews / no entries", () => {
  assert.deepEqual(candidatePreviewBundle({}).dropped, []);
  assert.deepEqual(candidatePreviewBundle({ previews: [{ id: "X" }] }).dropped, ["X"]);
});

test("daemonPreviewIdsByFunction groups every preview id under its function", () => {
  const ids = daemonPreviewIdsByFunction([
    {
      previews: [
        { id: "Filled_Light", functionName: "Filled" },
        { id: "Filled_Dark", functionName: "Filled" },
        { id: "Outlined_Light", functionName: "Outlined" },
      ],
    },
    null,
  ]);
  assert.deepEqual(ids.get("Filled"), ["Filled_Light", "Filled_Dark"]);
  assert.deepEqual(ids.get("Outlined"), ["Outlined_Light"]);
});

test("daemonPreviewIdsByFunction folds a supplement, primary winning on a repeated id", () => {
  const ids = daemonPreviewIdsByFunction([
    { previews: [{ id: "Shared", functionName: "Primary" }] },
    {
      previews: [
        { id: "Shared", functionName: "Supplement" },
        { id: "ExtraOnly", functionName: "Supplement" },
      ],
    },
  ]);
  assert.deepEqual(ids.get("Primary"), ["Shared"]);
  assert.deepEqual(ids.get("Supplement"), ["ExtraOnly"]);
});

test("daemonPreviewIdsByFunction falls back to the id when a preview names no function", () => {
  const ids = daemonPreviewIdsByFunction([{ previews: [{ id: "Bare" }] }]);
  assert.deepEqual(ids.get("Bare"), ["Bare"]);
});
