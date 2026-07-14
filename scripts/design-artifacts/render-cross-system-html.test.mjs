/**
 * Unit tests for the cross-system component-parallel page (`matches.html`): each
 * row pairs a component with its authored parallel in a sibling system, and the
 * page carries a client resolver that fetches the other branch's catalog.json to
 * fill in the parallel thumbnails. The fetch/canvas work runs in a browser
 * (untestable under `node --test`); here we pin the pure pairing logic and the
 * page structure — the buckets, the row wiring, the unpaired flag, and that the
 * resolver + other-branch URLs are present.
 *
 * Run with `node --test scripts/design-artifacts/`.
 */
import assert from "node:assert/strict";
import { test } from "node:test";

import { crossSystemMatches, renderCrossSystemHtml } from "./render-cross-system-html.mjs";

const png = (path) => ({ path, variant: "ideal", state: "default", theme: "light", width: 200, height: 100 });

const catalog = {
  system: "remote-m3",
  title: "Remote Compose Material 3",
  components: [
    { componentId: "Button/Filled", group: "Buttons", images: [png("images/button-filled/ideal__default__light.png")] },
    { componentId: "Button/Icon", group: "Buttons", images: [png("images/button-icon/ideal__default__light.png")] },
    { componentId: "Shader/LinearGradient", group: "Shaders", images: [png("images/shader/ideal__default__light.png")] },
  ],
};

const parallelById = {
  "Button/Filled": "Button/Filled",
  "Button/Icon": "IconButton",
  // Shader/LinearGradient has no parallel declared.
};

const otherComponents = [
  { componentId: "Button/Filled", group: "Buttons", caption: "Filled button." },
  { componentId: "IconButton", group: "Buttons", caption: "Round icon button." },
  { componentId: "Card", group: "Containment", caption: "A card." },
];

test("crossSystemMatches buckets paired / only-local / only-other", () => {
  const { paired, onlyLocal, onlyOther } = crossSystemMatches(
    catalog.components,
    parallelById,
    otherComponents,
  );
  assert.equal(paired.length, 2);
  assert.deepEqual(
    paired.map((p) => [p.local.componentId, p.parallelId, p.other?.componentId]),
    [
      ["Button/Filled", "Button/Filled", "Button/Filled"],
      ["Button/Icon", "IconButton", "IconButton"],
    ],
  );
  // Shader has no parallel → only-local.
  assert.deepEqual(onlyLocal.map((c) => c.componentId), ["Shader/LinearGradient"]);
  // Card is never referenced by a parallel → only-other.
  assert.deepEqual(onlyOther.map((c) => c.componentId), ["Card"]);
});

test("a parallel not catalogued in the other system pairs with other=null", () => {
  const { paired } = crossSystemMatches(
    [{ componentId: "Button/Compact", group: "Buttons", images: [] }],
    { "Button/Compact": "CompactButton" },
    [], // other system has no CompactButton yet
  );
  assert.equal(paired.length, 1);
  assert.equal(paired[0].other, null);
  assert.equal(paired[0].parallelId, "CompactButton");
});

test("each paired row shows the local render and a slot for the parallel", () => {
  const html = renderCrossSystemHtml(catalog, { parallelById, otherComponents, otherSystem: "wear-m3", otherTitle: "Wear Compose Material 3" });
  // Local render baked in.
  assert.match(html, /<img[^>]*src="images\/button-filled\/ideal__default__light\.png"/);
  // Parallel slot keyed by the parallel's slug for the client resolver.
  assert.match(html, /class="shot shot--other" data-parallel="iconbutton"/);
});

test("an uncatalogued parallel is flagged unpaired", () => {
  const html = renderCrossSystemHtml(
    { system: "remote-m3", title: "Remote", components: [{ componentId: "Button/Compact", group: "Buttons", images: [] }] },
    { parallelById: { "Button/Compact": "CompactButton" }, otherComponents: [], otherSystem: "wear-m3" },
  );
  assert.match(html, /class="badge"[^>]*>unpaired</);
  assert.match(html, /no <code>CompactButton<\/code> sticker yet/);
});

test("only-local and only-other inventories are listed", () => {
  const html = renderCrossSystemHtml(catalog, { parallelById, otherComponents, otherSystem: "wear-m3" });
  assert.match(html, /Only in remote-m3 <span>1<\/span>/);
  assert.match(html, /Only in wear-m3 <span>1<\/span>/);
  assert.match(html, /Shader\/LinearGradient/);
  assert.match(html, />Card</);
});

test("the client resolver fetches the other branch catalog for thumbnails", () => {
  const html = renderCrossSystemHtml(catalog, {
    parallelById,
    otherComponents,
    otherSystem: "wear-m3",
    repo: "yschimke/compose-ai-tools",
  });
  assert.match(html, /raw\.githubusercontent\.com\/yschimke\/compose-ai-tools\/design-artifacts\/wear-m3\/catalog\.json/);
  assert.match(html, /querySelectorAll\("\.shot--other\[data-parallel\]"\)/);
  // Cross-origin so the other-branch images load on htmlpreview.
  assert.match(html, /img\.crossOrigin = "anonymous"/);
});

test("the resolver marks a paired slot 'not published yet' when the sibling render is missing", () => {
  const html = renderCrossSystemHtml(catalog, { parallelById, otherComponents, otherSystem: "wear-m3" });
  // When the fetched sibling catalog carries no render for a declared parallel,
  // the slot must not stay stuck on the "loading …" placeholder forever.
  assert.match(html, /if \(!path\) \{/);
  assert.match(html, /textContent = "not published yet"/);
  assert.match(html, /shot--stale/);
});

test("the summary counts pairs and how many render both sides", () => {
  const html = renderCrossSystemHtml(catalog, { parallelById, otherComponents, otherSystem: "wear-m3" });
  assert.match(html, /2 paired/);
  assert.match(html, /2 rendered both sides/);
});
