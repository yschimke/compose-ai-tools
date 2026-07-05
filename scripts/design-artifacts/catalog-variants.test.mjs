import { test } from "node:test";
import assert from "node:assert/strict";

import { foldVariants } from "./catalog-variants.mjs";
import { renderIndexHtml } from "./render-index-html.mjs";

const img = (state, theme) => ({ state, theme, uri: "x", width: 100, height: 40 });

test("foldVariants appends variant renders re-tagged with their state", () => {
  const byFunction = new Map([
    ["FilledButtonPressed", { images: [img("default", "light"), img("default", "dark")] }],
    ["FilledButtonDisabled", { images: [img("default", "light")] }],
  ]);
  const component = {
    componentId: "Button/Filled",
    variants: [
      { state: "pressed", preview: "FilledButtonPressed" },
      { state: "disabled", preview: "FilledButtonDisabled" },
    ],
  };
  const { ideal, missing } = foldVariants(
    [img("default", "light"), img("default", "dark")],
    component,
    byFunction,
  );
  assert.deepEqual(missing, []);
  // 2 default + 2 pressed + 1 disabled
  assert.equal(ideal.length, 5);
  assert.deepEqual(
    ideal.map((i) => i.state),
    ["default", "default", "pressed", "pressed", "disabled"],
  );
  // Original default images keep their state; the theme tag is preserved on folds.
  assert.equal(ideal[2].theme, "light");
  assert.equal(ideal[3].theme, "dark");
});

test("foldVariants reports a variant whose preview did not render", () => {
  const { ideal, missing } = foldVariants(
    [img("default", "light")],
    { componentId: "Button/Filled", variants: [{ state: "focused", preview: "Missing" }] },
    new Map(),
  );
  assert.deepEqual(missing, ["Button/Filled [focused]"]);
  assert.equal(ideal.length, 1); // just the default
});

test("foldVariants is a no-op for a component without variants", () => {
  const defaults = [img("default", "light")];
  const { ideal, missing } = foldVariants(defaults, { componentId: "X" }, new Map());
  assert.deepEqual(missing, []);
  assert.deepEqual(ideal, defaults);
});

// --- index.html: default in the grid, states in the zoom view -----------------

const catalogWithStates = () => ({
  meta: { system: "compose-m3", title: "Compose M3" },
  components: [
    {
      componentId: "Button/Filled",
      group: "Buttons",
      caption: "Primary action.",
      greenlines: [],
      images: [
        { variant: "ideal", state: "default", theme: "light", path: "images/button-filled/ideal__default__light.png", width: 200, height: 80 },
        { variant: "ideal", state: "focused", theme: "light", path: "images/button-filled/ideal__focused__light.png", width: 200, height: 80 },
        { variant: "ideal", state: "disabled", theme: "light", path: "images/button-filled/ideal__disabled__light.png", width: 200, height: 80 },
      ],
    },
  ],
});

test("grid hero is the default state, not a folded variant", () => {
  const html = renderIndexHtml(catalogWithStates());
  // The card's <img> (inside <a class="shot">) must point at the default render.
  const shot = html.match(/<a class="shot"[^>]*>\s*<img[^>]*src="([^"]+)"/);
  assert.ok(shot, "expected a card shot image");
  assert.match(shot[1], /ideal__default__light\.png$/);
});

test("a states chip links into the zoom view when variants exist", () => {
  const html = renderIndexHtml(catalogWithStates());
  assert.match(html, /class="statechip" href="#d-button-filled">\+2 states</);
});

test("the zoom overlay lists every folded state, default first", () => {
  const html = renderIndexHtml(catalogWithStates());
  const detail = html.match(/<div class="detail" id="d-button-filled"[\s\S]*?<\/div>\s*<\/div>\s*<\/div>/);
  assert.ok(detail, "expected a detail overlay for the component");
  const block = detail[0];
  for (const state of ["default", "focused", "disabled"]) {
    assert.match(block, new RegExp(`<figcaption>${state}</figcaption>`));
  }
  // default figcaption comes before focused/disabled
  assert.ok(
    block.indexOf(">default<") < block.indexOf(">focused<"),
    "default state should be shown first",
  );
});

test("a component with only a default state gets no states chip", () => {
  const catalog = {
    meta: { system: "s", title: "t" },
    components: [
      {
        componentId: "Card/Filled",
        group: "Containment",
        greenlines: [],
        images: [
          { variant: "ideal", state: "default", theme: "light", path: "images/card/ideal__default__light.png", width: 160, height: 80 },
          { variant: "ideal", state: "default", theme: "dark", path: "images/card/ideal__default__dark.png", width: 160, height: 80 },
        ],
      },
    ],
  };
  const html = renderIndexHtml(catalog);
  assert.doesNotMatch(html, /class="statechip"/); // the CSS rule may mention it; no chip element
  // but it still has a zoom overlay (to see light + dark larger)
  assert.match(html, /id="d-card-filled"/);
});
