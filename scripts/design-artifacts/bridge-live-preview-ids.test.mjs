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

test("theme-folded catalog (split light/dark functions): each theme maps to its OWN function", () => {
  // Regression: a screen whose light and dark renders are two separate `@Preview`
  // functions, folded into one component by the spec's `theme` variant axis. The
  // theme-only variant used to be dropped at registration (the guard demanded
  // state or props) AND was unrepresentable in the key, so the dark sticker fell
  // through to the component's default function and took the LIGHT preview's id —
  // mis-pointing livePreview/ServeCatalogStore, and (once the per-variant figma-svg
  // emit keys off previewId) writing the light vector at the dark path.
  const spec = {
    system: "meshcore-mobile",
    groups: [
      {
        components: [
          {
            componentId: "Chat/Contact",
            preview: "ContactChatPreview",
            variants: [{ theme: "dark", preview: "ContactChatDarkPreview" }],
          },
        ],
      },
    ],
  };
  const bundle = {
    previews: [
      { id: "pkg.ChatKt.ContactChatPreview_Contact chat", functionName: "ContactChatPreview" },
      {
        id: "pkg.ChatKt.ContactChatDarkPreview_Contact chat — dark",
        functionName: "ContactChatDarkPreview",
      },
    ],
  };
  const manifest = {
    system: "meshcore-mobile",
    components: [
      {
        componentId: "Chat/Contact",
        images: [
          { state: "default", theme: "light" },
          { state: "default", theme: "dark" },
        ],
      },
    ],
  };

  bridgeLivePreviewIds(manifest, spec, bundle, new Set());

  const ids = manifest.components[0].images.map((i) => i.previewId);
  assert.deepEqual(ids, [
    "pkg.ChatKt.ContactChatPreview_Contact chat",
    "pkg.ChatKt.ContactChatDarkPreview_Contact chat — dark",
  ]);
  // The point of the regression: the two must differ. Before the fix both were the light id.
  assert.notEqual(ids[0], ids[1]);
});

test("theme-folded: a per-theme function whose preview id carries no light/dark suffix still resolves", () => {
  // `themeOfPreviewId` only recognises ids ending in light/dark. A split dark function
  // whose @Preview `name` doesn't end that way lands in the un-themed map instead, so the
  // daemon-id lookup must fall back to the bare function — the function is already
  // theme-specific, so the bare id is the right one.
  const spec = {
    system: "meshcore-mobile",
    groups: [
      {
        components: [
          {
            componentId: "Settings/Ready",
            preview: "DeviceSettingsPreview",
            variants: [{ theme: "dark", preview: "DeviceSettingsNightPreview" }],
          },
        ],
      },
    ],
  };
  const bundle = {
    previews: [
      { id: "pkg.SettingsKt.DeviceSettingsPreview_Ready", functionName: "DeviceSettingsPreview" },
      {
        id: "pkg.SettingsKt.DeviceSettingsNightPreview_Night mode",
        functionName: "DeviceSettingsNightPreview",
      },
    ],
  };
  const manifest = {
    system: "meshcore-mobile",
    components: [
      {
        componentId: "Settings/Ready",
        images: [
          { state: "default", theme: "light" },
          { state: "default", theme: "dark" },
        ],
      },
    ],
  };

  bridgeLivePreviewIds(manifest, spec, bundle, new Set());

  assert.deepEqual(
    manifest.components[0].images.map((i) => i.previewId),
    [
      "pkg.SettingsKt.DeviceSettingsPreview_Ready",
      "pkg.SettingsKt.DeviceSettingsNightPreview_Night mode",
    ],
  );
});

test("theme and state coexist: a themed variant of a non-default state keys on both", () => {
  const spec = {
    system: "meshcore-mobile",
    groups: [
      {
        components: [
          {
            componentId: "Device/Screen",
            preview: "DevicePreview",
            variants: [
              { state: "empty", preview: "DeviceEmptyPreview" },
              { state: "empty", theme: "dark", preview: "DeviceEmptyDarkPreview" },
            ],
          },
        ],
      },
    ],
  };
  const bundle = {
    previews: [
      { id: "pkg.DeviceKt.DevicePreview", functionName: "DevicePreview" },
      { id: "pkg.DeviceKt.DeviceEmptyPreview", functionName: "DeviceEmptyPreview" },
      { id: "pkg.DeviceKt.DeviceEmptyDarkPreview", functionName: "DeviceEmptyDarkPreview" },
    ],
  };
  const manifest = {
    system: "meshcore-mobile",
    components: [
      {
        componentId: "Device/Screen",
        images: [
          { state: "empty", theme: "light" },
          { state: "empty", theme: "dark" },
        ],
      },
    ],
  };

  bridgeLivePreviewIds(manifest, spec, bundle, new Set());

  // light has no theme-qualified entry, so it falls back to the state-only variant;
  // dark hits its own (state, theme) entry.
  assert.deepEqual(
    manifest.components[0].images.map((i) => i.previewId),
    ["pkg.DeviceKt.DeviceEmptyPreview", "pkg.DeviceKt.DeviceEmptyDarkPreview"],
  );
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
