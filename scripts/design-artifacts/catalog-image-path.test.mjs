import { test } from "node:test";
import assert from "node:assert/strict";

import {
  catalogImagePath,
  catalogSlug,
  derivationMismatches,
} from "./catalog-image-path.mjs";

test("catalogSlug lowercases and collapses to the exporter's charset", () => {
  assert.equal(catalogSlug("Button/Filled"), "button-filled");
  assert.equal(catalogSlug("Template/TimeText"), "template-timetext");
  // `.`, `_` and `-` survive — that's why fontScale 2.0 keeps its dot.
  assert.equal(catalogSlug("fontScale-2.0"), "fontscale-2.0");
  assert.equal(catalogSlug("keyboard-focus"), "keyboard-focus");
  assert.equal(catalogSlug("--Odd  name!--"), "odd-name");
});

test("an unadorned image is images/<slug>/ideal__default.png", () => {
  assert.equal(
    catalogImagePath("Button/Filled", {}),
    "images/button-filled/ideal__default.png",
  );
});

test("state, theme and size are appended in the exporter's order", () => {
  assert.equal(
    catalogImagePath("Button/Filled", { state: "keyboard-focus", theme: "dark" }),
    "images/button-filled/ideal__keyboard-focus__dark.png",
  );
  assert.equal(
    catalogImagePath("Template/AppScaffold", {
      state: "default",
      theme: "light",
      size: "compact",
    }),
    "images/template-appscaffold/ideal__default__light__compact.png",
  );
});

test("props become one trailing segment each, sorted by key so order can't drift", () => {
  assert.equal(
    catalogImagePath("Button/Filled", {
      theme: "light",
      props: { content: "icon+label" },
    }),
    "images/button-filled/ideal__default__light__content-icon-label.png",
  );
  assert.equal(
    catalogImagePath("Button/Filled", { theme: "dark", props: { locale: "ar-XB" } }),
    "images/button-filled/ideal__default__dark__locale-ar-xb.png",
  );
  assert.equal(
    catalogImagePath("Button/Filled", { theme: "dark", props: { fontScale: "2.0" } }),
    "images/button-filled/ideal__default__dark__fontscale-2.0.png",
  );
  // Insertion order must not change the name.
  assert.equal(
    catalogImagePath("X", { props: { b: "2", a: "1" } }),
    catalogImagePath("X", { props: { a: "1", b: "2" } }),
  );
});

test("a non-ideal variant keeps its own segment", () => {
  assert.equal(
    catalogImagePath("Card", { variant: "compact", state: "pressed" }),
    "images/card/compact__pressed.png",
  );
});

test("derivationMismatches is empty while the derivation agrees with the exporter", () => {
  const manifest = {
    components: [
      {
        componentId: "Button/Filled",
        images: [
          { theme: "light", path: "images/button-filled/ideal__default__light.png" },
          {
            state: "pressed",
            theme: "dark",
            path: "images/button-filled/ideal__pressed__dark.png",
          },
        ],
      },
    ],
  };
  assert.deepEqual(derivationMismatches(manifest), []);
});

test("derivationMismatches reports a path the exporter named differently", () => {
  // The drift guard: were `buildCatalog` to change its naming, the export must notice here rather
  // than publish a deferred record pointing at a route no sticker would ever occupy.
  const manifest = {
    components: [
      {
        componentId: "Button/Filled",
        images: [{ theme: "light", path: "images/button-filled/ideal-default-light.png" }],
      },
    ],
  };
  assert.deepEqual(derivationMismatches(manifest), [
    {
      expected: "images/button-filled/ideal__default__light.png",
      actual: "images/button-filled/ideal-default-light.png",
    },
  ]);
});
