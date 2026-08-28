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

import {
  comparableEntries,
  crossSystemMatches,
  renderCrossSystemHtml,
  variantDiscriminator,
} from "./render-cross-system-html.mjs";

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

test("the row offset reserves a wrapped heading, derived from the header's own metrics", () => {
  // A flat 44px is the height of a ONE-LINE heading, and headings wrap — the
  // design-reference column's "M3 Wear OS Apps Design Kit" does it at a narrow
  // viewport, putting the row back under the header the offset exists to clear. The
  // page carries no script (see the fully-static test), so the reserve is arithmetic
  // on the header's own type metrics rather than a measurement; both halves are
  // pinned here so neither can move without the other.
  const html = renderCrossSystemHtml(catalog, opts);

  assert.match(html, /thead th \{[^}]*line-height:1\.35;/);
  assert.match(html, /tr\.crow \{ scroll-margin-top:calc\(3 \* 1\.35 \* 12px \+ 21px\); \}/);
  // 3 heading lines + 10px padding top and bottom + the 1px border ≈ 70px, against
  // the 44px one line needs. Overshooting leaves a little space above the row;
  // undershooting hides it — so this deliberately reserves more than any current
  // heading uses.
  assert.doesNotMatch(html, /scroll-margin-top:44px/);
});

test("two component ids that slug the same still get their own anchor", () => {
  // `slug` collapses every run of non-alphanumerics to one `-`, so `Button/A+B` and
  // `Button/A B` are both `button-a-b`. Deriving the anchor per row emitted a
  // duplicate id and made the second row unaddressable: its self-link, and any bug
  // report quoting it, resolved to the first — the one thing a per-row anchor exists
  // to prevent.
  const colliding = {
    system: "remote-m3",
    components: [
      { componentId: "Button/A+B", group: "Buttons", images: [] },
      { componentId: "Button/A B", group: "Buttons", images: [] },
    ],
  };
  const html = renderCrossSystemHtml(colliding, {
    ...opts,
    parallelById: { "Button/A+B": "Button/Filled", "Button/A B": "Button/Icon" },
  });

  assert.match(html, /<tr class="crow" id="c-button-a-b">/);
  assert.match(html, /<tr class="crow" id="c-button-a-b-2">/);
  // ...and each row's self-link points at its OWN row, not at the first one.
  assert.match(html, /href="#c-button-a-b">Button\/A\+B</);
  assert.match(html, /href="#c-button-a-b-2">Button\/A B</);
  assert.equal(html.match(/id="c-button-a-b"/g).length, 1);
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

// --- compared variants -------------------------------------------------------
//
// A variant is paired in its own right rather than through its parent. Before this, walking
// `components` alone meant folding a render under a parent silently dropped it from this page,
// which made "component or variant?" a question about parity coverage instead of structure.

const withVariants = {
  system: "remote-m3",
  title: "Remote Compose Material 3",
  components: [
    {
      componentId: "Button/Compact",
      group: "Buttons",
      caption: "Compact button.",
      images: [
        png("images/button-compact/ideal__default.png"),
        png("images/button-compact/ideal__default__content-icon-only.png", {
          props: { content: "icon-only" },
        }),
      ],
      variants: [
        {
          preview: "CompactIconOnly",
          props: { content: "icon-only" },
          parallel: "Button/Compact",
          caption: "Icon-only compact button.",
        },
        { preview: "CompactPressed", state: "pressed" },
      ],
    },
  ],
};

test("comparableEntries emits a row per compared variant, after its parent", () => {
  const rows = comparableEntries(withVariants.components);
  assert.deepEqual(
    rows.map((r) => r.componentId),
    ["Button/Compact", "Button/Compact \u00b7 content=icon-only"],
  );
  // The variant that declares no parallel has nothing to pair against and stays folded.
  assert.equal(rows.length, 2);
});

test("comparableEntries gives a variant row its parent's group and its own parallel", () => {
  const [, variantRow] = comparableEntries(withVariants.components);
  assert.equal(variantRow.group, "Buttons");
  assert.equal(variantRow.parallel, "Button/Compact");
  assert.equal(variantRow.variantOf, "Button/Compact");
  assert.equal(variantRow.caption, "Icon-only compact button.");
  // It must not carry the nested list on, or the next walk would expand it again.
  assert.equal(variantRow.variants, undefined);
});

test("crossSystemMatches prefers an entry's own parallel over the id map", () => {
  // The map is keyed by component id, and a variant has no entry in it -- so a variant would be
  // reported unpaired if the map were the only source.
  const rows = comparableEntries(withVariants.components);
  const { paired, onlyLocal } = crossSystemMatches(rows, {}, [
    { componentId: "Button/Compact", group: "Buttons" },
  ]);
  assert.equal(paired.length, 1);
  assert.equal(paired[0].local.componentId, "Button/Compact \u00b7 content=icon-only");
  assert.deepEqual(
    onlyLocal.map((c) => c.componentId),
    ["Button/Compact"],
  );
});

test("a variant row bakes the variant's own render, not the parent's hero", () => {
  const html = renderCrossSystemHtml(withVariants, {
    parallelById: {},
    otherSystem: "wear-m3",
    otherComponents: [{ componentId: "Button/Compact", group: "Buttons" }],
  });
  // The row exists, anchored by its own id...
  assert.ok(html.includes('id="c-button-compact-content-icon-only"'));
  // ...and shows the props-tagged PNG rather than the default one.
  assert.ok(html.includes("images/button-compact/ideal__default__content-icon-only.png"));
});

test("variantDiscriminator names a single-axis variant by that axis, else its preview", () => {
  assert.equal(variantDiscriminator({ state: "pressed" }), "pressed");
  assert.equal(variantDiscriminator({ props: { content: "icon-only" } }), "content=icon-only");
  assert.equal(variantDiscriminator({ theme: "dark" }), "dark");
  assert.equal(variantDiscriminator({ preview: "SomeRender" }), "SomeRender");
  assert.equal(variantDiscriminator({}), "variant");
});

test("comparableEntries is a no-op for a catalog with no variants", () => {
  const plain = [{ componentId: "Card", group: "Containment", images: [] }];
  assert.deepEqual(comparableEntries(plain), plain);
});

test("variantDiscriminator names a variant by every axis it declares, not the first", () => {
  // Two variants of one parent sharing a state and differing by props both read `Parent · pressed`
  // when the discriminator returns on `state`: the anchor allocator kept the hidden ids unique, but
  // a reader comparing the rows — or quoting one in a finding — had nothing to tell them apart.
  assert.equal(
    variantDiscriminator({ state: "pressed", props: { size: "small" } }),
    "pressed, size=small",
  );
  assert.notEqual(
    variantDiscriminator({ state: "pressed", props: { size: "small" } }),
    variantDiscriminator({ state: "pressed", props: { size: "large" } }),
  );
  assert.equal(variantDiscriminator({ state: "pressed", theme: "dark" }), "pressed, dark");
  assert.equal(variantDiscriminator({ select: { size: "smallRound" } }), "size=smallRound");
});

test("a variant row publishes its authored reason for having no kit reference", () => {
  // A `noReference` is a finding — "the kit exports no Text=No cell" — and the generic cell
  // published it as the same thing as an unaudited gap, which is the one distinction it makes.
  const withVariant = {
    ...catalog,
    components: [
      {
        ...catalog.components[0],
        variants: [
          {
            preview: "ButtonTextless",
            state: "textless",
            parallel: "Button/Filled",
            noReference: "the kit exports no Text=No cell",
          },
        ],
      },
      ...catalog.components.slice(1),
    ],
  };
  const html = renderCrossSystemHtml(withVariant, {
    ...opts,
    designRefById: new Map([["Button/Filled", { url: "https://example.test/r.png" }]]),
  });

  assert.match(html, /no kit reference — the kit exports no Text=No cell/);
  // The unexplained row beside it still reads as the plain gap it is.
  assert.match(html, /class="pv-missing">no kit reference</);
});

test("a variant's stated reason is escaped, never injected as markup", () => {
  const withVariant = {
    ...catalog,
    components: [
      {
        ...catalog.components[0],
        variants: [
          {
            preview: "P",
            state: "x",
            parallel: "Button/Filled",
            noReference: '<img src=x onerror="alert(1)">',
          },
        ],
      },
    ],
  };
  const html = renderCrossSystemHtml(withVariant, {
    ...opts,
    designRefById: new Map([["Button/Filled", { url: "https://example.test/r.png" }]]),
  });
  assert.doesNotMatch(html, /<img src=x/);
  assert.match(html, /&lt;img src=x/);
});

test("a catalog with only stated absences still gets the kit column", () => {
  // The driver passes an empty `designRefById` when nothing resolved, which used to normalise to
  // null and drop the column entirely — so the authored reasons showed up only when some unrelated
  // component happened to contribute a reference, as every other test here does.
  const absencesOnly = {
    ...catalog,
    components: [
      { ...catalog.components[0], noReference: "the kit retired this button" },
      catalog.components[1],
    ],
  };
  const html = renderCrossSystemHtml(absencesOnly, { ...opts, designRefById: new Map() });

  assert.match(html, /<th scope="col">Design kit<\/th>/);
  assert.match(html, /no kit reference — the kit retired this button/);
  // Counted honestly: no reference resolved, so the page does not claim a three-way comparison.
  assert.match(html, /1 with a stated absence/);
  assert.doesNotMatch(html, /against a kit reference/);
  assert.doesNotMatch(html, /both against Design kit/);
});

test("no references and no stated absences leaves the kit column off", () => {
  const html = renderCrossSystemHtml(catalog, { ...opts, designRefById: new Map() });
  assert.doesNotMatch(html, /Design kit/);
  assert.doesNotMatch(html, /col-d/);
  assert.doesNotMatch(html, /with a stated absence/);
});

test("a design column turned off in the spec is not handed back by a stated absence", () => {
  // `compareWith.design: false` makes the generator omit `designRefById` entirely. An empty map is
  // the DIFFERENT statement "the column is on and nothing resolved", which is what lets an absence
  // carry the column — so the opt-out has to be spelled by the key's absence, not by an empty map.
  const absencesOnly = {
    ...catalog,
    components: [
      { ...catalog.components[0], noReference: "the kit retired this button" },
      catalog.components[1],
    ],
  };
  const html = renderCrossSystemHtml(absencesOnly, opts); // no designRefById at all
  assert.doesNotMatch(html, /Design kit/);
  assert.doesNotMatch(html, /col-d/);
  assert.doesNotMatch(html, /with a stated absence/);
});

test("an absence-only column says it carries no reference, not that it carries one", () => {
  // The explanatory note is the page's claim about what the leading column IS. Reusing the
  // reference wording for a column that resolved nothing tells a reader a picture exists and was
  // contributed by a `figma:` mapping — the opposite of the authored absence the column is showing.
  const absencesOnly = {
    ...catalog,
    components: [
      { ...catalog.components[0], noReference: "the kit retired this button" },
      catalog.components[1],
    ],
  };
  const html = renderCrossSystemHtml(absencesOnly, { ...opts, designRefById: new Map() });
  assert.match(html, /column carries no reference on this page/);
  assert.doesNotMatch(html, /is the published design reference BOTH/);

  // …and the reference wording is still exactly what a page with real references says.
  const withRefs = renderCrossSystemHtml(absencesOnly, {
    ...opts,
    designRefById: new Map([["Button/Filled", { url: "https://example.test/r.png" }]]),
  });
  assert.match(withRefs, /is the published design reference BOTH/);
  assert.doesNotMatch(withRefs, /column carries no reference on this page/);
});
