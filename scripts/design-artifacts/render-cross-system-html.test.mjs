/**
 * Unit tests for the cross-system component-parallel page (`matches.html`): each
 * row pairs a component with its authored parallel in a sibling system. Both
 * columns are STATIC PNG thumbnails baked at build time that link to the live
 * preview server — no runtime fetch (which htmlpreview's CSP silently blocks). We
 * pin the pure pairing logic and the page structure: the buckets, the baked local
 * + sibling thumbnails, the live-server links, the "not rendered yet" fallback
 * when a sibling render is absent, the unpaired flag, and that the page carries no
 * view-time resolver.
 *
 * Run with `node --test scripts/design-artifacts/`.
 */
import assert from "node:assert/strict";
import { test } from "node:test";

import { crossSystemMatches, renderCrossSystemHtml } from "./render-cross-system-html.mjs";

const png = (path, extra = {}) => ({
  path,
  variant: "ideal",
  state: "default",
  theme: "light",
  width: 200,
  height: 100,
  ...extra,
});

const catalog = {
  system: "remote-m3",
  title: "Remote Compose Material 3",
  components: [
    {
      componentId: "Button/Filled",
      group: "Buttons",
      images: [
        png("images/button-filled/ideal__default__light.png", {
          livePreview: "https://preview.coo.ee/remote-m3/p/button-filled__ideal__default__light",
        }),
      ],
    },
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

// The sibling's rendered catalog.json: Button/Filled is rendered (device-pinned,
// so its image carries no `theme`); IconButton is declared in the spec but not
// (yet) rendered on the branch.
const otherManifest = {
  system: "wear-m3",
  components: [
    {
      componentId: "Button/Filled",
      images: [
        png("images/button-filled/ideal__default__compact.png", {
          theme: undefined,
          size: "compact",
          livePreview: "https://preview.coo.ee/wear-m3/p/button-filled__ideal__default__compact",
        }),
      ],
    },
  ],
};

const opts = {
  parallelById,
  otherComponents,
  otherManifest,
  otherSystem: "wear-m3",
  otherTitle: "Wear Compose Material 3",
  repo: "yschimke/compose-ai-tools",
};

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
  assert.deepEqual(onlyLocal.map((c) => c.componentId), ["Shader/LinearGradient"]);
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

test("a paired row bakes both renders as static thumbnails linking to the live server", () => {
  const html = renderCrossSystemHtml(catalog, opts);
  // Local render baked from this branch's relative path…
  assert.match(html, /<img class="pv-img" src="images\/button-filled\/ideal__default__light\.png"/);
  // …linking to this system's live preview.
  assert.match(html, /href="https:\/\/preview\.coo\.ee\/remote-m3\/p\/button-filled__ideal__default__light"/);
  // Sibling render baked to the OTHER branch's raw URL…
  assert.match(
    html,
    /src="https:\/\/raw\.githubusercontent\.com\/yschimke\/compose-ai-tools\/design-artifacts\/wear-m3\/images\/button-filled\/ideal__default__compact\.png"/,
  );
  // …linking to the sibling system's live preview.
  assert.match(html, /href="https:\/\/preview\.coo\.ee\/wear-m3\/p\/button-filled__ideal__default__compact"/);
});

test("the page is fully static — no view-time fetch/resolver, never a perpetual 'loading'", () => {
  const html = renderCrossSystemHtml(catalog, opts);
  assert.doesNotMatch(html, /fetch\(/);
  assert.doesNotMatch(html, /querySelectorAll/);
  assert.doesNotMatch(html, /loading [A-Z]/); // no "loading Button/Filled…" placeholder text
  assert.doesNotMatch(html, /<script/);
});

test("a declared parallel not yet rendered on the sibling shows 'not rendered yet' + a link", () => {
  const html = renderCrossSystemHtml(catalog, opts);
  // IconButton is in the sibling spec + parallel map, but absent from otherManifest.
  assert.match(html, /not rendered yet/);
  assert.match(html, /class="pv-open"[^>]*>open wear-m3 ↗</);
  // It is NOT baked as an image.
  assert.doesNotMatch(html, /design-artifacts\/wear-m3\/images\/iconbutton/);
});

test("with no sibling manifest, paired cells fall back rather than fetch", () => {
  const html = renderCrossSystemHtml(catalog, { ...opts, otherManifest: undefined });
  assert.match(html, /not rendered yet/);
  assert.doesNotMatch(html, /wear-m3\/images\//); // nothing baked from the sibling branch
  assert.doesNotMatch(html, /fetch\(/);
  assert.match(html, /0 rendered both sides/);
});

test("an uncatalogued parallel is flagged unpaired", () => {
  const html = renderCrossSystemHtml(
    {
      system: "remote-m3",
      title: "Remote",
      components: [{ componentId: "Button/Compact", group: "Buttons", images: [] }],
    },
    { parallelById: { "Button/Compact": "CompactButton" }, otherComponents: [], otherSystem: "wear-m3" },
  );
  assert.match(html, /class="badge"[^>]*>unpaired</);
  assert.match(html, /no <code>CompactButton<\/code> sticker yet/);
});

test("only-local and only-other inventories are listed", () => {
  const html = renderCrossSystemHtml(catalog, opts);
  assert.match(html, /Only in remote-m3 <span>1<\/span>/);
  assert.match(html, /Only in wear-m3 <span>1<\/span>/);
  assert.match(html, /Shader\/LinearGradient/);
  assert.match(html, />Card</);
});

test("the summary counts pairs and how many render both sides", () => {
  const html = renderCrossSystemHtml(catalog, opts);
  // Button/Filled renders both sides; Button/Icon's sibling (IconButton) doesn't.
  assert.match(html, /2 paired/);
  assert.match(html, /1 rendered both sides/);
});

test("a cross-repo sibling bakes its thumbnails under ITS repository, not this one", () => {
  const html = renderCrossSystemHtml(catalog, {
    parallelById,
    otherComponents,
    otherManifest,
    otherSystem: "wear-m3-catalog",
    otherTitle: "M3 Wear OS Apps",
    repo: "yschimke/compose-ai-tools",
    otherRepo: "yschimke/wear-m3-catalog",
  });

  assert.match(
    html,
    /src="https:\/\/raw\.githubusercontent\.com\/yschimke\/wear-m3-catalog\/design-artifacts\/wear-m3-catalog\/images\/button-filled/,
  );
  // The "open the sibling" link follows the same repo, or it lands on a branch that isn't there.
  assert.match(html, /github\.com\/yschimke\/wear-m3-catalog\/blob\/design-artifacts\/wear-m3-catalog/);
  assert.doesNotMatch(
    html,
    /raw\.githubusercontent\.com\/yschimke\/compose-ai-tools\/design-artifacts\/wear-m3-catalog/,
  );
});

test("without design references the page stays exactly two implementation columns", () => {
  const html = renderCrossSystemHtml(catalog, {
    parallelById,
    otherComponents,
    otherManifest,
    otherSystem: "wear-m3",
    otherTitle: "Wear Compose Material 3",
  });

  assert.doesNotMatch(html, /Design kit/);
  assert.doesNotMatch(html, /col-d/);
  assert.doesNotMatch(html, /no kit reference/);
});

test("design references add a leading kit column, and only where one resolved", () => {
  const designRefById = new Map([
    [
      "Button/Filled",
      {
        url: "https://raw.githubusercontent.com/yschimke/wear-m3-catalog/design-artifacts/wear-m3-catalog/references/button-filled.png",
        uri: "figma:B24oss2tTeXAFykyeyusz0/35239:93092",
        from: "wear-m3-catalog",
      },
    ],
    // Button/Icon is paired but neither catalog maps it to the kit.
  ]);
  const html = renderCrossSystemHtml(catalog, {
    parallelById,
    otherComponents,
    otherManifest,
    otherSystem: "wear-m3-catalog",
    otherTitle: "M3 Wear OS Apps",
    otherRepo: "yschimke/wear-m3-catalog",
    designRefById,
  });

  assert.match(html, /<th scope="col">Design kit<\/th>/);
  assert.match(html, /references\/button-filled\.png/);
  // The kit node is on the cell so a reader can trace the picture back to the file it came from.
  assert.match(html, /figma:B24oss2tTeXAFykyeyusz0\/35239:93092/);
  // Paired-but-unmapped reads as an inert cell, never a spinner or a borrowed picture.
  assert.match(html, /no kit reference/);
  // The header counts what is actually three-way, not what is merely paired.
  assert.match(html, /1 against a kit reference/);
  // Both implementations against one reference — NOT a derivation chain, which would contradict
  // the this-system-first column order under it.
  assert.match(html, /M3 Wear OS Apps, both against Design kit/);
});

test("the kit column is still baked — no view-time fetch reaches the design branch", () => {
  const designRefById = new Map([
    ["Button/Filled", { url: "https://example.test/references/button-filled.png", from: "wear-m3-catalog" }],
  ]);
  const html = renderCrossSystemHtml(catalog, {
    parallelById,
    otherComponents,
    otherManifest,
    otherSystem: "wear-m3-catalog",
    designRefById,
  });

  assert.doesNotMatch(html, /fetch\(/);
  assert.doesNotMatch(html, /<script/);
});

test("a sibling title fetched from another repo cannot inject markup into the subtitle", () => {
  // With the cross-repo form, `otherTitle` can come straight out of a fetched sibling
  // catalog.json. The heading and table header always escaped it; the subtitle did not.
  const html = renderCrossSystemHtml(catalog, {
    parallelById,
    otherComponents,
    otherManifest,
    otherSystem: "wear-m3-catalog",
    otherTitle: '<img src=x onerror="alert(1)">',
    otherRepo: "yschimke/wear-m3-catalog",
    designRefById: new Map([["Button/Filled", { url: "https://example.test/r.png" }]]),
  });

  assert.doesNotMatch(html, /<img src=x/);
  assert.match(html, /&lt;img src=x/);
  // The arrow span is ours and stays real markup.
  assert.match(html, /<span class="arrow">↔<\/span>/);
});

test("every paired row is anchored, and its component id links to itself", () => {
  // The page a cross-system bug report wants to point at: it is the only one
  // carrying the kit reference and both renditions of a cell side by side. Before
  // this, the best a report could do was link the page and name the row.
  const html = renderCrossSystemHtml(catalog, opts);

  assert.match(html, /<tr class="crow" id="c-button-filled">/);
  assert.match(html, /<a class="cid anchor" href="#c-button-filled">Button\/Filled<\/a>/);
  // Same `c-<slug>` scheme renderIndexHtml uses, so one convention covers both pages.
  assert.match(html, /<tr class="crow" id="c-button-icon">/);
  // Sticky `thead th` would otherwise hide the row an anchor jumps to.
  assert.match(html, /tr\.crow \{ scroll-margin-top:/);
});

test("an anchor cannot be smuggled out of a component id", () => {
  // componentId reaches the id attribute AND an href. `slug` collapses everything
  // non-alphanumeric, so the danger is a quote surviving into either — assert on
  // the slug rather than trusting escaping alone.
  const hostile = {
    system: "remote-m3",
    components: [{ componentId: 'Button/"><img src=x>', group: "Buttons", images: [] }],
  };
  const html = renderCrossSystemHtml(hostile, {
    parallelById: { 'Button/"><img src=x>': "Button/Filled" },
    otherComponents,
    otherManifest,
    otherSystem: "wear-m3-catalog",
  });

  assert.doesNotMatch(html, /<img src=x/);
  assert.match(html, /id="c-button-img-src-x"/);
});
