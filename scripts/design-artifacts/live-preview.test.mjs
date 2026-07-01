/**
 * Unit tests for the live-preview deep-link helpers + the README "Customise
 * live" link. Run with `node --test scripts/design-artifacts/`.
 *
 * The preview-id derivation MUST stay in lockstep with the server
 * (`ServeCatalogStore.previewIdFor`) and design-parity (`livePreviewUrl`), so a
 * link resolves to the matching live render — these tests pin that contract.
 */
import assert from "node:assert/strict";
import { test } from "node:test";

import {
  catalogPreviewId,
  hasWasmTier,
  liveSessionUrl,
  livePreviewUrl,
  wasmLiveUrl,
} from "./live-preview.mjs";
import { renderReadmeMd } from "./render-readme-md.mjs";

test("catalogPreviewId flattens the image path to a route-safe id", () => {
  assert.equal(
    catalogPreviewId("images/button-filled/ideal__default__dark.png"),
    "button-filled__ideal__default__dark",
  );
});

test("livePreviewUrl targets the /<system>/p viewer path with the flattened preview id", () => {
  assert.equal(
    livePreviewUrl("https://preview.coo.ee///", "compose-m3", "images/fab/ideal__default__dark.png"),
    "https://preview.coo.ee/compose-m3/p/fab__ideal__default__dark",
  );
});

test("liveSessionUrl is the system landing on the live server at its canonical path", () => {
  assert.equal(
    liveSessionUrl("https://preview.coo.ee", "wear-m3"),
    "https://preview.coo.ee/wear-m3/",
  );
});

test("the README carries a Customise-live link to the live session", () => {
  const md = renderReadmeMd(
    { meta: { system: "compose-m3", title: "Compose Material 3" }, components: [] },
    { previewBase: "https://preview.coo.ee" },
  );
  assert.match(md, /## 🎛 Customise live/);
  // Compare against the helper's own output (not a bare URL literal) so CodeQL's
  // incomplete-url-substring-sanitization rule doesn't flag a URL literal in `.includes`.
  assert.ok(md.includes(liveSessionUrl("https://preview.coo.ee", "compose-m3")));
});

test("wasmLiveUrl targets the in-browser /wasm route only for CMP systems", () => {
  assert.ok(hasWasmTier("compose-m3"));
  assert.ok(!hasWasmTier("wear-m3"));
  assert.equal(
    wasmLiveUrl("https://preview.coo.ee//", "compose-m3", "button-filled"),
    "https://preview.coo.ee/wasm/compose-m3/?id=button-filled",
  );
  assert.equal(
    wasmLiveUrl("https://preview.coo.ee", "compose-m3", "switch", { dark: true }),
    "https://preview.coo.ee/wasm/compose-m3/?id=switch&uiMode=dark",
  );
  // Wear has no wasm target → no in-browser tier.
  assert.equal(wasmLiveUrl("https://preview.coo.ee", "wear-m3", "button"), null);
});

test("the compose-m3 README advertises the Kotlin/Wasm in-browser tier; wear-m3 does not", () => {
  // Components are keyed by `componentId` (e.g. "Switch/On") — the real catalog
  // shape, not a fabricated `id`. The Wasm link must use its slug.
  const cmp = renderReadmeMd(
    {
      meta: { system: "compose-m3", title: "Compose Material 3" },
      components: [{ componentId: "Switch/On", images: [] }],
    },
    { previewBase: "https://preview.coo.ee" },
  );
  assert.match(cmp, /## 🌐 Run it in your browser \(Kotlin\/Wasm\)/);
  assert.ok(cmp.includes("https://preview.coo.ee/wasm/compose-m3/?id=switch-on"));

  const wear = renderReadmeMd(
    {
      meta: { system: "wear-m3", title: "Wear Material 3" },
      components: [{ componentId: "Button/Filled", images: [] }],
    },
    { previewBase: "https://preview.coo.ee" },
  );
  assert.doesNotMatch(wear, /Kotlin\/Wasm/);
});
