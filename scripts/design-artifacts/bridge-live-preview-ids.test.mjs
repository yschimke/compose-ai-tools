/**
 * Unit tests for the catalog-sticker → daemon-preview-id bridge. Run with
 * `node --test scripts/design-artifacts/`.
 *
 * `ServeCatalogStore` builds its catalog-id → daemon-id alias solely from each
 * image's `previewId`; these pin that both THEMED catalogs (compose-m3, keyed on
 * `(function, theme)`) and UN-THEMED state-variant catalogs (wear-m3 / remote-m3,
 * keyed on the bare function — the case that was silently dropped, leaving the
 * viewer with overrides disabled even though the daemon was live).
 */
import assert from "node:assert/strict";
import { test } from "node:test";

import { bridgeLivePreviewIds } from "./bridge-live-preview-ids.mjs";

/** previewId by image state, for one single-component manifest. */
function mapped(manifest) {
  const out = {};
  for (const image of manifest.components[0].images) out[image.state] = image.previewId;
  return out;
}

test("un-themed state-variant catalog (Wear): each state maps to its daemon preview id", () => {
  const spec = {
    system: "wear-m3",
    groups: [
      {
        components: [
          {
            componentId: "Button/Filled",
            preview: "FilledButton",
            variants: [{ state: "pressed", preview: "ButtonPressed" }],
          },
        ],
      },
    ],
  };
  const bundle = {
    previews: [
      { id: "pkg.CatalogKt.FilledButton", functionName: "FilledButton" },
      { id: "pkg.CatalogKt.ButtonPressed", functionName: "ButtonPressed" },
    ],
  };
  const manifest = {
    system: "wear-m3",
    components: [
      { componentId: "Button/Filled", images: [{ state: "default" }, { state: "pressed" }] },
    ],
  };

  bridgeLivePreviewIds(manifest, spec, bundle, new Set());

  assert.deepEqual(mapped(manifest), {
    default: "pkg.CatalogKt.FilledButton",
    pressed: "pkg.CatalogKt.ButtonPressed",
  });
});

test("themed catalog (compose-m3): light/dark stickers still map on (function, theme)", () => {
  const spec = {
    system: "compose-m3",
    groups: [{ components: [{ componentId: "Button/Filled", preview: "FilledButton", variants: [] }] }],
  };
  const bundle = {
    previews: [
      { id: "pkg.CatalogKt.FilledButton_Light", functionName: "FilledButton" },
      { id: "pkg.CatalogKt.FilledButton_Dark", functionName: "FilledButton" },
    ],
  };
  const manifest = {
    system: "compose-m3",
    components: [
      {
        componentId: "Button/Filled",
        images: [
          { state: "default", theme: "light" },
          { state: "default", theme: "dark" },
        ],
      },
    ],
  };

  bridgeLivePreviewIds(manifest, spec, bundle, new Set());

  const ids = manifest.components[0].images.map((i) => i.previewId);
  assert.deepEqual(ids, ["pkg.CatalogKt.FilledButton_Light", "pkg.CatalogKt.FilledButton_Dark"]);
});

test("overridden functions (Android-only supplement) get no daemon id", () => {
  const spec = {
    system: "wear-m3",
    groups: [
      {
        components: [
          {
            componentId: "Button/Filled",
            preview: "FilledButton",
            variants: [{ state: "pressed", preview: "ButtonPressed" }],
          },
        ],
      },
    ],
  };
  const bundle = {
    previews: [
      { id: "pkg.CatalogKt.FilledButton", functionName: "FilledButton" },
      { id: "pkg.CatalogKt.ButtonPressed", functionName: "ButtonPressed" },
    ],
  };
  const manifest = {
    system: "wear-m3",
    components: [
      { componentId: "Button/Filled", images: [{ state: "default" }, { state: "pressed" }] },
    ],
  };

  // The default render's function is replaced by an Android-only supplement, so it
  // must NOT reach the desktop/live daemon (its baked pixels differ).
  bridgeLivePreviewIds(manifest, spec, bundle, new Set(["FilledButton"]));

  assert.equal(manifest.components[0].images[0].previewId, undefined);
  assert.equal(manifest.components[0].images[1].previewId, "pkg.CatalogKt.ButtonPressed");
});
