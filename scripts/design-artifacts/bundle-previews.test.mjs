import { test } from "node:test";
import assert from "node:assert/strict";

import {
  bundleCapturedSemantics,
  candidatePreviewBundle,
  capturedPreviewIds,
  daemonPreviewCellsByFunction,
  daemonPreviewIdsByFunction,
} from "./bundle-previews.mjs";

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

test("daemonPreviewCellsByFunction preserves the parameters that identify fan-out axes", () => {
  const cells = daemonPreviewCellsByFunction([
    {
      previews: [
        { id: "Filled_Light", functionName: "Filled", params: { uiMode: 16 } },
        { id: "Filled_Dark", functionName: "Filled", params: { uiMode: 32 } },
      ],
    },
  ]);
  assert.deepEqual(cells.get("Filled"), [
    { id: "Filled_Light", params: { uiMode: 16 } },
    { id: "Filled_Dark", params: { uiMode: 32 } },
  ]);
});

// --- capturedPreviewIds: what came back at all, not what came back as a PNG ------------------

test("capturedPreviewIds counts any per-preview artifact, not just a PNG", () => {
  // The three PNG-less shapes are all legitimately captured: an animated GIF preview, a
  // catalog-token sheet, and a `"capture": "none"` entry that still gets its semantics blob.
  const bundle = {
    previews: [
      { id: "Card_Light" },
      { id: "CardScrollGif" },
      { id: "BrandDark_theme" },
      { id: "HostedView_Light" },
    ],
    entries: {
      "previews/Card_Light.png": enc("png"),
      "previews/Card_Light.semantics.json": enc("{}"),
      "previews/CardScrollGif.gif": enc("gif"),
      "previews/BrandDark_theme.catalog.json": enc("{}"),
      "previews/HostedView_Light.semantics.json": enc("{}"),
    },
  };
  assert.deepEqual(
    [...capturedPreviewIds(bundle)].sort(),
    ["BrandDark_theme", "CardScrollGif", "Card_Light", "HostedView_Light"],
  );
});

test("capturedPreviewIds omits a preview an exclusion skipped in both passes", () => {
  // This is the m3-catalog#15 signature: `--exclude-preview-id` skips the render AND the semantics
  // capture, so the preview is listed in the bundle with nothing at all under `previews/`.
  const bundle = {
    previews: [{ id: "Switch_On_Light" }, { id: "Switch_On_Light_VARIANT_off" }],
    entries: {
      "previews/Switch_On_Light.png": enc("png"),
      "previews/Switch_On_Light.semantics.json": enc("{}"),
    },
  };
  assert.deepEqual([...capturedPreviewIds(bundle)], ["Switch_On_Light"]);
});

test("capturedPreviewIds does not let a longer id claim a shorter one's artifact", () => {
  // `previews/Switch_On_Light_VARIANT_off.png` starts with the base id, so a prefix match without
  // the dot boundary would report the base as captured — exactly the substring confusion that
  // caused the bug this check exists to catch.
  const bundle = {
    previews: [{ id: "Switch_On_Light" }, { id: "Switch_On_Light_VARIANT_off" }],
    entries: { "previews/Switch_On_Light_VARIANT_off.png": enc("png") },
  };
  assert.deepEqual([...capturedPreviewIds(bundle)], ["Switch_On_Light_VARIANT_off"]);
});

test("capturedPreviewIds reports canonical discovery ids, not sanitized entry ids", () => {
  // Bundles store filename-safe ids; shard plans are authored against the discovery id.
  const bundle = {
    previews: [{ id: "Chip_Large_Round" }],
    entries: { "previews/Chip_Large_Round.png": enc("png") },
    manifest: { previewIds: ["Chip_Large_Round"], rawPreviewIds: ["Chip_Large Round"] },
  };
  const rawIdFor = (b, entry) => {
    const i = b.manifest.previewIds.indexOf(entry.id);
    return i >= 0 ? b.manifest.rawPreviewIds[i] : entry.id;
  };
  assert.deepEqual([...capturedPreviewIds(bundle, rawIdFor)], ["Chip_Large Round"]);
});

test("capturedPreviewIds ignores entries outside previews/ and unknown ids", () => {
  const bundle = {
    previews: [{ id: "Card_Light" }],
    entries: {
      "previews/Card_Light.png": enc("png"),
      "previews/Ghost_Light.png": enc("png"), // no matching previews.json entry
      "classes/app.jar": enc("jar"),
      "bundle.json": enc("{}"),
    },
  };
  assert.deepEqual([...capturedPreviewIds(bundle)], ["Card_Light"]);
});

test("capturedPreviewIds is empty for a bundle with nothing in it", () => {
  assert.equal(capturedPreviewIds({}).size, 0);
  assert.equal(capturedPreviewIds(undefined).size, 0);
});

test("capturedPreviewIds credits an artifact to the LONGEST declared id that fits", () => {
  // Ids can carry dots of their own, so `pkg.Screen` and `pkg.Screen.Dark` can both be declared.
  // Stopping at the first dot boundary would credit `previews/pkg.Screen.Dark.png` to `pkg.Screen`
  // and report the longer preview — fully rendered — as lost.
  const bundle = {
    previews: [{ id: "pkg.Screen" }, { id: "pkg.Screen.Dark" }],
    entries: {
      "previews/pkg.Screen.png": enc("png"),
      "previews/pkg.Screen.Dark.png": enc("png"),
    },
  };
  assert.deepEqual([...capturedPreviewIds(bundle)].sort(), ["pkg.Screen", "pkg.Screen.Dark"]);
});

test("capturedPreviewIds still resolves a dotted id's multi-extension sidecar", () => {
  const bundle = {
    previews: [{ id: "pkg.Screen" }],
    entries: { "previews/pkg.Screen.semantics.json": enc("{}") },
  };
  assert.deepEqual([...capturedPreviewIds(bundle)], ["pkg.Screen"]);
});

test("bundleCapturedSemantics reports whether the best-effort semantics pass produced anything", () => {
  const withSemantics = {
    entries: { "previews/A.png": enc("png"), "previews/A.semantics.json": enc("{}") },
  };
  assert.equal(bundleCapturedSemantics(withSemantics), true);
  // A failed daemon open leaves the pack exiting 0 with the PNGs but no semantics at all.
  assert.equal(bundleCapturedSemantics({ entries: { "previews/A.png": enc("png") } }), false);
  assert.equal(bundleCapturedSemantics({}), false);
});
