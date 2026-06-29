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
  liveSessionUrl,
  livePreviewUrl,
} from "./live-preview.mjs";
import { renderReadmeMd } from "./render-readme-md.mjs";

test("catalogPreviewId flattens the image path to a route-safe id", () => {
  assert.equal(
    catalogPreviewId("images/button-filled/ideal__default__dark.png"),
    "button-filled__ideal__default__dark",
  );
});

test("livePreviewUrl targets the /p viewer route with the flattened preview id", () => {
  assert.equal(
    livePreviewUrl("https://preview.coo.ee///", "compose-m3", "images/fab/ideal__default__dark.png"),
    "https://preview.coo.ee/p/fab__ideal__default__dark?session=compose-m3",
  );
});

test("liveSessionUrl is the system landing on the live server", () => {
  assert.equal(
    liveSessionUrl("https://preview.coo.ee", "wear-m3"),
    "https://preview.coo.ee/?session=wear-m3",
  );
});

test("the README carries a Customise-live link to the live session", () => {
  const md = renderReadmeMd(
    { meta: { system: "compose-m3", title: "Compose Material 3" }, components: [] },
    { previewBase: "https://preview.coo.ee" },
  );
  assert.match(md, /## 🎛 Customise live/);
  assert.ok(md.includes("https://preview.coo.ee/?session=compose-m3"));
});
