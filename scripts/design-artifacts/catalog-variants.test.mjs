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

test("foldVariants reports a render-less `capture: none` variant separately, not as missing", () => {
  const { ideal, missing, noSticker } = foldVariants(
    [img("default", "light")],
    {
      componentId: "Screens/Watch list",
      variants: [
        { state: "scrolling", preview: "WatchListScrolling", capture: "none" },
        { state: "focused", preview: "WatchListFocused" },
      ],
    },
    new Map(),
  );
  // The undeclared one still sinks the completeness gate; the declared one is only recorded.
  assert.deepEqual(missing, ["Screens/Watch list [focused]"]);
  assert.deepEqual(noSticker, ["Screens/Watch list [scrolling]"]);
  assert.equal(ideal.length, 1); // just the default
});

test("foldVariants is a no-op for a component without variants", () => {
  const defaults = [img("default", "light")];
  const { ideal, missing } = foldVariants(defaults, { componentId: "X" }, new Map());
  assert.deepEqual(missing, []);
  assert.deepEqual(ideal, defaults);
});

test("foldVariants folds a content-axis (props) variant, keeping the default state", () => {
  const byFunction = new Map([
    ["FilledButtonIconLabel", { images: [img("default", "light"), img("default", "dark")] }],
  ]);
  const component = {
    componentId: "Button/Filled",
    variants: [{ props: { content: "icon+label" }, preview: "FilledButtonIconLabel" }],
  };
  const { ideal, missing } = foldVariants(
    [img("default", "light"), img("default", "dark")],
    component,
    byFunction,
  );
  assert.deepEqual(missing, []);
  // 2 default (no props) + 2 icon+label (default state, content prop).
  assert.equal(ideal.length, 4);
  assert.equal(ideal[0].props, undefined);
  assert.deepEqual(ideal[2].props, { content: "icon+label" });
  assert.equal(ideal[2].state, "default"); // props variant keeps the default state
  assert.equal(ideal[2].theme, "light");
  assert.equal(ideal[3].theme, "dark");
});

test("foldVariants reports a props-only variant that did not render, labelled by its axes", () => {
  const { missing } = foldVariants(
    [img("default", "light")],
    {
      componentId: "Button/Filled",
      variants: [{ props: { content: "icon+label" }, preview: "Missing" }],
    },
    new Map(),
  );
  assert.deepEqual(missing, ["Button/Filled [content=icon+label]"]);
});

test("foldVariants folds a theme variant, pairing a split dark @Preview onto the light default", () => {
  // A screen whose light and dark renders are two SEPARATE @Preview functions
  // (FooScreen / FooScreenDark) — the app pattern the catalog server can't pair
  // by function name on its own. A `theme` variant links the dark function's
  // render onto the light component so it folds into one __light/__dark card the
  // viewer swaps between, keeping a night-mode browse on the baked PNG.
  const byFunction = new Map([
    // The dark function renders dark, but its image carries whatever theme tag the
    // render produced — the variant's `theme` re-tags it authoritatively.
    ["FooScreenDark", { images: [img("default", "dark")] }],
  ]);
  const component = {
    componentId: "Screen/Foo",
    variants: [{ theme: "dark", preview: "FooScreenDark" }],
  };
  const { ideal, missing } = foldVariants([img("default", "light")], component, byFunction);
  assert.deepEqual(missing, []);
  // 1 light default + 1 dark variant → a pair the server folds into one swap card.
  assert.equal(ideal.length, 2);
  assert.equal(ideal[0].theme, "light");
  assert.equal(ideal[1].theme, "dark");
  assert.equal(ideal[1].state, "default"); // a theme variant keeps the default state
});

test("foldVariants re-tags a mis-tagged theme variant render authoritatively", () => {
  // Belt-and-suspenders: even if the dark function's render came back tagged
  // "light" (a preview that forgot uiMode = NIGHT_YES), the `theme` axis forces
  // the tag so the pair still resolves to __light/__dark rather than collapsing.
  const byFunction = new Map([["FooScreenDark", { images: [img("default", "light")] }]]);
  const { ideal } = foldVariants(
    [img("default", "light")],
    { componentId: "Screen/Foo", variants: [{ theme: "dark", preview: "FooScreenDark" }] },
    byFunction,
  );
  assert.equal(ideal[1].theme, "dark");
});

test("foldVariants reports a theme variant that did not render, labelled by its theme", () => {
  const { missing } = foldVariants(
    [img("default", "light")],
    { componentId: "Screen/Foo", variants: [{ theme: "dark", preview: "Missing" }] },
    new Map(),
  );
  assert.deepEqual(missing, ["Screen/Foo [dark]"]);
});

test("foldVariants refuses duplicate effective output axes before export", () => {
  const byFunction = new Map([
    ["SecondDark", { images: [img("default", "light")] }],
  ]);
  assert.throws(
    () =>
      foldVariants(
        [img("default", "dark")],
        {
          componentId: "Screen/Foo",
          variants: [{ theme: "dark", preview: "SecondDark" }],
        },
        byFunction,
      ),
    /produces duplicate output axes/,
  );
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

test("a content-axis (props) variant never wins the hero and is labelled in the zoom view", () => {
  const catalog = {
    meta: { system: "compose-m3", title: "Compose M3" },
    components: [
      {
        componentId: "Button/Filled",
        group: "Buttons",
        greenlines: [],
        images: [
          // the label-only default is NARROWER than the icon+label render below,
          // so the old "largest default-state" hero would have picked icon+label.
          { variant: "ideal", state: "default", theme: "light", path: "images/button-filled/ideal__default__light.png", width: 120, height: 40 },
          { variant: "ideal", state: "default", theme: "light", props: { content: "icon+label" }, path: "images/button-filled/ideal__default__light__content-icon-label.png", width: 168, height: 40 },
        ],
      },
    ],
  };
  const html = renderIndexHtml(catalog);
  // Hero is the label-only default, not the wider props render.
  const shot = html.match(/<a class="shot"[^>]*>\s*<img[^>]*src="([^"]+)"/);
  assert.ok(shot, "expected a card shot image");
  assert.match(shot[1], /ideal__default__light\.png$/);
  // The chip counts it as a variant (not a "state"), and the zoom view labels the axis.
  assert.match(html, /class="statechip"[^>]*>\+1 variant</);
  assert.match(html, /<figcaption>content=icon\+label<\/figcaption>/);
});
