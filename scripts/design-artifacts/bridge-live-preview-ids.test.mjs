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
  for (const image of manifest.components[0].images)
    out[image.state] = image.previewId;
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
      {
        componentId: "Button/Filled",
        images: [{ state: "default" }, { state: "pressed" }],
      },
    ],
  };

  bridgeLivePreviewIds(manifest, spec, bundle, new Set());

  assert.deepEqual(mapped(manifest), {
    default: "pkg.CatalogKt.FilledButton",
    pressed: "pkg.CatalogKt.ButtonPressed",
  });
});

test("@OverrideVariant state (no spec variant) maps to the base's _VARIANT_ preview", () => {
  // The off state rides `@OverrideVariant` on `SwitchButtonOn`, so the spec lists NO `off` variant;
  // the daemon preview is the synthetic `SwitchButtonOn_VARIANT_off`.
  const spec = {
    system: "wear-m3",
    groups: [
      {
        components: [
          { componentId: "SwitchButton/On", preview: "SwitchButtonOn" },
        ],
      },
    ],
  };
  const bundle = {
    previews: [
      { id: "pkg.CatalogKt.SwitchButtonOn", functionName: "SwitchButtonOn" },
      {
        id: "pkg.CatalogKt.SwitchButtonOn_VARIANT_off",
        functionName: "SwitchButtonOn",
      },
    ],
  };
  const manifest = {
    system: "wear-m3",
    components: [
      {
        componentId: "SwitchButton/On",
        images: [{ state: "default" }, { state: "off" }],
      },
    ],
  };
  bridgeLivePreviewIds(manifest, spec, bundle, new Set());
  assert.deepEqual(mapped(manifest), {
    default: "pkg.CatalogKt.SwitchButtonOn",
    off: "pkg.CatalogKt.SwitchButtonOn_VARIANT_off",
  });
});

test("themed @OverrideVariant state maps to <baseId>_VARIANT_<state> per theme", () => {
  const spec = {
    system: "compose-m3",
    groups: [
      { components: [{ componentId: "Switch/On", preview: "SwitchOn" }] },
    ],
  };
  const bundle = {
    previews: [
      { id: "pkg.SwitchOn_Light", functionName: "SwitchOn" },
      { id: "pkg.SwitchOn_Dark", functionName: "SwitchOn" },
      { id: "pkg.SwitchOn_Light_VARIANT_off", functionName: "SwitchOn" },
      { id: "pkg.SwitchOn_Dark_VARIANT_off", functionName: "SwitchOn" },
    ],
  };
  const manifest = {
    system: "compose-m3",
    components: [
      {
        componentId: "Switch/On",
        images: [
          { state: "default", theme: "light" },
          { state: "default", theme: "dark" },
          { state: "off", theme: "light" },
          { state: "off", theme: "dark" },
        ],
      },
    ],
  };
  bridgeLivePreviewIds(manifest, spec, bundle, new Set());
  const byKey = {};
  for (const img of manifest.components[0].images)
    byKey[`${img.state}/${img.theme}`] = img.previewId;
  assert.deepEqual(byKey, {
    "default/light": "pkg.SwitchOn_Light",
    "default/dark": "pkg.SwitchOn_Dark",
    "off/light": "pkg.SwitchOn_Light_VARIANT_off",
    "off/dark": "pkg.SwitchOn_Dark_VARIANT_off",
  });
});

test("@OverrideVariant fallback skips a state whose _VARIANT_ preview didn't render", () => {
  const spec = {
    system: "wear-m3",
    groups: [
      {
        components: [
          { componentId: "SwitchButton/On", preview: "SwitchButtonOn" },
        ],
      },
    ],
  };
  const bundle = {
    previews: [{ id: "pkg.SwitchButtonOn", functionName: "SwitchButtonOn" }],
  };
  const manifest = {
    system: "wear-m3",
    components: [
      {
        componentId: "SwitchButton/On",
        images: [{ state: "default" }, { state: "off" }],
      },
    ],
  };
  bridgeLivePreviewIds(manifest, spec, bundle, new Set());
  assert.equal(
    manifest.components[0].images.find((i) => i.state === "off").previewId,
    undefined,
  );
});

test("themed catalog (compose-m3): light/dark stickers still map on (function, theme)", () => {
  const spec = {
    system: "compose-m3",
    groups: [
      {
        components: [
          {
            componentId: "Button/Filled",
            preview: "FilledButton",
            variants: [],
          },
        ],
      },
    ],
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
  assert.deepEqual(ids, [
    "pkg.CatalogKt.FilledButton_Light",
    "pkg.CatalogKt.FilledButton_Dark",
  ]);
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
      {
        id: "pkg.ChatKt.ContactChatPreview_Contact chat",
        functionName: "ContactChatPreview",
      },
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
            variants: [
              { theme: "dark", preview: "DeviceSettingsNightPreview" },
            ],
          },
        ],
      },
    ],
  };
  const bundle = {
    previews: [
      {
        id: "pkg.SettingsKt.DeviceSettingsPreview_Ready",
        functionName: "DeviceSettingsPreview",
      },
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
              {
                state: "empty",
                theme: "dark",
                preview: "DeviceEmptyDarkPreview",
              },
            ],
          },
        ],
      },
    ],
  };
  const bundle = {
    previews: [
      { id: "pkg.DeviceKt.DevicePreview", functionName: "DevicePreview" },
      {
        id: "pkg.DeviceKt.DeviceEmptyPreview",
        functionName: "DeviceEmptyPreview",
      },
      {
        id: "pkg.DeviceKt.DeviceEmptyDarkPreview",
        functionName: "DeviceEmptyDarkPreview",
      },
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

test("an --extra-renders-only component bridges from the supplementary bundle", () => {
  // Regression: the bridge took a single bundle, so a component whose previews live ONLY in the
  // `--extra-renders` supplement (a screen rendered from a second CMP-desktop module) got no
  // `previewId` on any image — costing it both the live lane and, once the per-variant figma-svg
  // emit keyed off previewId, every editable vector. It hid because "no previewId" is also the
  // legitimate outcome for a deliberately-skipped image.
  const spec = {
    system: "meshcore-mobile",
    groups: [
      {
        components: [
          { componentId: "Button/Filled", preview: "FilledButton" },
          { componentId: "Chat/Contact", preview: "ContactChatPreview" },
        ],
      },
    ],
  };
  const primary = {
    previews: [
      { id: "app.CatalogKt.FilledButton", functionName: "FilledButton" },
    ],
  };
  const extra = {
    previews: [
      {
        id: "cmp.ChatKt.ContactChatPreview",
        functionName: "ContactChatPreview",
      },
    ],
  };
  const manifest = {
    system: "meshcore-mobile",
    components: [
      { componentId: "Button/Filled", images: [{ state: "default" }] },
      { componentId: "Chat/Contact", images: [{ state: "default" }] },
    ],
  };

  bridgeLivePreviewIds(manifest, spec, [primary, extra], new Set());

  assert.equal(
    manifest.components[0].images[0].previewId,
    "app.CatalogKt.FilledButton",
  );
  // The one that used to come back undefined.
  assert.equal(
    manifest.components[1].images[0].previewId,
    "cmp.ChatKt.ContactChatPreview",
  );
});

test("a falsy bundle in the list is skipped (no --extra-renders)", () => {
  const spec = {
    system: "wear-m3",
    groups: [
      {
        components: [{ componentId: "Button/Filled", preview: "FilledButton" }],
      },
    ],
  };
  const bundle = {
    previews: [
      { id: "pkg.CatalogKt.FilledButton", functionName: "FilledButton" },
    ],
  };
  const manifest = {
    system: "wear-m3",
    components: [
      { componentId: "Button/Filled", images: [{ state: "default" }] },
    ],
  };

  bridgeLivePreviewIds(manifest, spec, [bundle, null], new Set());

  assert.equal(
    manifest.components[0].images[0].previewId,
    "pkg.CatalogKt.FilledButton",
  );
});

test("the primary bundle wins when both carry the same function", () => {
  const spec = {
    system: "meshcore-mobile",
    groups: [
      {
        components: [{ componentId: "Button/Filled", preview: "FilledButton" }],
      },
    ],
  };
  const primary = {
    previews: [
      { id: "app.CatalogKt.FilledButton", functionName: "FilledButton" },
    ],
  };
  const extra = {
    previews: [
      { id: "cmp.CatalogKt.FilledButton", functionName: "FilledButton" },
    ],
  };
  const manifest = {
    system: "meshcore-mobile",
    components: [
      { componentId: "Button/Filled", images: [{ state: "default" }] },
    ],
  };

  bridgeLivePreviewIds(manifest, spec, [primary, extra], new Set());

  assert.equal(
    manifest.components[0].images[0].previewId,
    "app.CatalogKt.FilledButton",
  );
});

test("a bare bundle (not an array) is still accepted", () => {
  const spec = {
    system: "wear-m3",
    groups: [
      {
        components: [{ componentId: "Button/Filled", preview: "FilledButton" }],
      },
    ],
  };
  const bundle = {
    previews: [
      { id: "pkg.CatalogKt.FilledButton", functionName: "FilledButton" },
    ],
  };
  const manifest = {
    system: "wear-m3",
    components: [
      { componentId: "Button/Filled", images: [{ state: "default" }] },
    ],
  };

  bridgeLivePreviewIds(manifest, spec, bundle, new Set());

  assert.equal(
    manifest.components[0].images[0].previewId,
    "pkg.CatalogKt.FilledButton",
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
      {
        componentId: "Button/Filled",
        images: [{ state: "default" }, { state: "pressed" }],
      },
    ],
  };

  // The default render's function is replaced by an Android-only supplement, so it
  // must NOT reach the desktop/live daemon (its baked pixels differ).
  bridgeLivePreviewIds(manifest, spec, bundle, new Set(["FilledButton"]));

  assert.equal(manifest.components[0].images[0].previewId, undefined);
  assert.equal(
    manifest.components[0].images[1].previewId,
    "pkg.CatalogKt.ButtonPressed",
  );
});

test("multi-annotation screen: each variant sticker gets its OWN annotation's preview id", () => {
  // Regression for #2883. A screen function carrying three `@Preview` annotations — default
  // compact, dark compact, large-font medium — produces three daemon previews that all share one
  // `functionName`. The old lookup kept the FIRST id per function, so all three stickers resolved
  // to the same preview; since the per-variant figma-svg emit keys off `image.previewId`, all
  // three variants were then handed the same vector (with whichever annotation rendered first
  // supplying the palette), while the Gradle-rendered PNGs correctly differed.
  //
  // The dark annotation deliberately does NOT end in "dark" — `@Preview(name = "dark theme")` is
  // how compose-samples writes it — so nothing but the `uiMode` bits can tell it apart.
  const spec = {
    system: "jetsnack",
    breakpoints: [
      { size: "compact", widthDp: 412 },
      { size: "medium", widthDp: 700 },
    ],
    groups: [
      {
        components: [
          { componentId: "Screens/Feed", preview: "FeedScreenPreview", variants: [] },
        ],
      },
    ],
  };
  const bundle = {
    previews: [
      {
        id: "app.FeedKt.FeedScreenPreview_default",
        functionName: "FeedScreenPreview",
        params: { widthDp: 412, uiMode: 0 },
      },
      {
        id: "app.FeedKt.FeedScreenPreview_dark_theme",
        functionName: "FeedScreenPreview",
        params: { widthDp: 412, uiMode: 0x20 },
      },
      {
        id: "app.FeedKt.FeedScreenPreview_large_font",
        functionName: "FeedScreenPreview",
        params: { widthDp: 700, uiMode: 0, fontScale: 1.5 },
      },
    ],
  };
  const manifest = {
    system: "jetsnack",
    components: [
      {
        componentId: "Screens/Feed",
        images: [
          { state: "default", size: "compact" },
          { state: "default", theme: "dark", size: "compact" },
          { state: "default", size: "medium" },
        ],
      },
    ],
  };

  bridgeLivePreviewIds(manifest, spec, bundle, new Set());

  const ids = manifest.components[0].images.map((i) => i.previewId);
  assert.deepEqual(ids, [
    "app.FeedKt.FeedScreenPreview_default",
    "app.FeedKt.FeedScreenPreview_dark_theme",
    "app.FeedKt.FeedScreenPreview_large_font",
  ]);
  assert.equal(new Set(ids).size, 3, "each variant must resolve to a distinct render");
});

test("a single-annotation function still resolves for every sticker, unconstrained", () => {
  // The complement of the test above: nothing about the per-variant pick may cost a component
  // whose function has exactly one `@Preview` its id, whatever axes its stickers carry.
  const spec = {
    system: "wear-m3",
    groups: [
      {
        components: [
          { componentId: "Button/Filled", preview: "FilledButton", variants: [] },
        ],
      },
    ],
  };
  const bundle = {
    previews: [{ id: "pkg.CatalogKt.FilledButton", functionName: "FilledButton" }],
  };
  const manifest = {
    system: "wear-m3",
    components: [
      {
        componentId: "Button/Filled",
        images: [
          { state: "default" },
          { state: "default", theme: "dark", size: "largeRound" },
        ],
      },
    ],
  };

  bridgeLivePreviewIds(manifest, spec, bundle, new Set());

  assert.deepEqual(
    manifest.components[0].images.map((i) => i.previewId),
    ["pkg.CatalogKt.FilledButton", "pkg.CatalogKt.FilledButton"],
  );
});

test("font-scale-only annotations do not collapse onto one preview id", () => {
  // Follow-up to #2883: when two `@Preview` annotations differ ONLY by `fontScale` (same width,
  // same theme), scoring on theme + width alone ties them and the first id wins for both stickers
  // — the very collapse the per-variant routing exists to prevent. The spec expresses font scale
  // as a props variant, so the pick has to score it too. The default sticker (no props) must land
  // on the unscaled annotation rather than an arbitrary one.
  const spec = {
    system: "jetsnack",
    groups: [
      {
        components: [
          {
            componentId: "Screens/Feed",
            preview: "FeedScreenPreview",
            variants: [
              { props: { fontScale: 2 }, preview: "FeedScreenPreview" },
            ],
          },
        ],
      },
    ],
  };
  const bundle = {
    previews: [
      {
        id: "app.FeedKt.FeedScreenPreview_default",
        functionName: "FeedScreenPreview",
        params: { widthDp: 412 },
      },
      {
        id: "app.FeedKt.FeedScreenPreview_large_font",
        functionName: "FeedScreenPreview",
        params: { widthDp: 412, fontScale: 2 },
      },
    ],
  };
  const manifest = {
    system: "jetsnack",
    components: [
      {
        componentId: "Screens/Feed",
        images: [{ state: "default" }, { state: "default", props: { fontScale: 2 } }],
      },
    ],
  };

  bridgeLivePreviewIds(manifest, spec, bundle, new Set());

  assert.deepEqual(
    manifest.components[0].images.map((i) => i.previewId),
    [
      "app.FeedKt.FeedScreenPreview_default",
      "app.FeedKt.FeedScreenPreview_large_font",
    ],
  );
});

test("an explicit fontScale of 1 matches an annotation that omits it", () => {
  // `1` is the annotation default, so a spec that spells it out must still land on the preview
  // that simply left `fontScale` unset — not score itself away from it.
  const spec = {
    system: "jetsnack",
    groups: [
      {
        components: [
          {
            componentId: "Screens/Feed",
            preview: "FeedScreenPreview",
            variants: [{ props: { fontScale: "1x" }, preview: "FeedScreenPreview" }],
          },
        ],
      },
    ],
  };
  const bundle = {
    previews: [
      {
        id: "app.FeedKt.FeedScreenPreview_large_font",
        functionName: "FeedScreenPreview",
        params: { widthDp: 412, fontScale: 2 },
      },
      {
        id: "app.FeedKt.FeedScreenPreview_default",
        functionName: "FeedScreenPreview",
        params: { widthDp: 412 },
      },
    ],
  };
  const manifest = {
    system: "jetsnack",
    components: [
      {
        componentId: "Screens/Feed",
        images: [{ state: "default", props: { fontScale: "1x" } }],
      },
    ],
  };

  bridgeLivePreviewIds(manifest, spec, bundle, new Set());

  assert.equal(
    manifest.components[0].images[0].previewId,
    "app.FeedKt.FeedScreenPreview_default",
  );
});

test("an untagged (default) sticker takes the light annotation, not the dark one", () => {
  // Reopened #2883. Jetsnack tags only its DARK stickers — the light one's path is
  // `ideal__default__compact`, with no theme segment — so `image.theme` is undefined for it. The
  // first pass scored an absent theme as "unconstrained", which let the light sticker tie with the
  // dark annotation and take whichever the bundle listed first. With the dark preview listed
  // first (as here), every light sticker shipped the dark vector against a light PNG.
  const spec = {
    system: "jetsnack",
    groups: [
      {
        components: [
          { componentId: "Snack/Card", preview: "SnackCardPreview", variants: [] },
        ],
      },
    ],
  };
  const bundle = {
    previews: [
      // Dark deliberately first — that ordering is what the tie resolved to before.
      {
        id: "app.SnackKt.SnackCardPreview_dark",
        functionName: "SnackCardPreview",
        params: { widthDp: 412, uiMode: 0x20 },
      },
      {
        id: "app.SnackKt.SnackCardPreview",
        functionName: "SnackCardPreview",
        params: { widthDp: 412 },
      },
    ],
  };
  const manifest = {
    system: "jetsnack",
    components: [
      {
        componentId: "Snack/Card",
        images: [{ state: "default" }, { state: "default", theme: "dark" }],
      },
    ],
  };

  bridgeLivePreviewIds(manifest, spec, bundle, new Set());

  assert.deepEqual(
    manifest.components[0].images.map((i) => i.previewId),
    ["app.SnackKt.SnackCardPreview", "app.SnackKt.SnackCardPreview_dark"],
  );
});

test("an un-themed catalog is unaffected by the untagged-sticker preference", () => {
  // wear-m3 / remote-m3 carry no theme on either side: the stickers are untagged AND the daemon
  // ids don't end in light/dark, so every candidate scores the same +1 and the first still wins.
  // This pins that the fix above can't disturb the state-variant catalogs.
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
      {
        componentId: "Button/Filled",
        images: [{ state: "default" }, { state: "pressed" }],
      },
    ],
  };

  bridgeLivePreviewIds(manifest, spec, bundle, new Set());

  assert.deepEqual(
    manifest.components[0].images.map((i) => i.previewId),
    ["pkg.CatalogKt.FilledButton", "pkg.CatalogKt.ButtonPressed"],
  );
});
