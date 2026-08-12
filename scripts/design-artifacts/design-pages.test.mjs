import { test } from "node:test";
import assert from "node:assert/strict";

import { PAGES_VERSION, pageImageName, planPageBackdrops } from "./design-pages.mjs";

/** A catalog whose stickers carry the discovery preview ids a design-map entry would name. */
const catalog = {
  components: [
    {
      componentId: "TopAppBar/Medium",
      images: [
        {
          path: "images/top-app-bar-medium/ideal__default__light.png",
          previewId: "ee.schimke.m3catalog.sections.TopAppBarsKt_MediumTopAppBarSticker_Light",
        },
        {
          path: "images/top-app-bar-medium/ideal__default__dark.png",
          previewId: "ee.schimke.m3catalog.sections.TopAppBarsKt_MediumTopAppBarSticker_Dark",
        },
      ],
    },
    {
      componentId: "List/Item",
      images: [{ path: "images/list-item/ideal__default__light.png", previewId: "list_Light" }],
    },
  ],
};

const spec = {
  groups: [
    {
      components: [
        { componentId: "List/Item", preview: "ListItemSticker" },
        { componentId: "TopAppBar/Medium", preview: "MediumTopAppBarSticker" },
      ],
    },
  ],
};

function page(placements, overrides = {}) {
  return {
    id: "upcoming",
    name: "Upcoming-Mobile",
    nodeId: "56615:48121",
    frame: { width: 412, height: 954 },
    image: { uri: "upcoming-mobile.png", scale: 2 },
    placements,
    ...overrides,
  };
}

function manifest(pages) {
  return { version: 1, source: "figma", fileKey: "ocdacdEsnHipMJD3egzxKb", pages };
}

const appBar = {
  nodeId: "1:1",
  name: "App bar",
  bounds: { x: 0, y: 48, width: 412, height: 64 },
  depth: 0,
  ref: "figma:ocdacdEsnHipMJD3egzxKb/1:1",
  link: "manifest",
  code: "catalog/src/main/kotlin/ee/schimke/m3catalog/sections/TopAppBars.kt#MediumTopAppBarSticker",
  previewId: "ee.schimke.m3catalog.sections.TopAppBarsKt_MediumTopAppBarSticker_Light",
  confidence: "high",
};

const statusBar = {
  nodeId: "1:2",
  name: "Status bar",
  bounds: { x: 0, y: 0, width: 412, height: 48 },
  depth: 0,
  ref: "figma:ocdacdEsnHipMJD3egzxKb/1:2",
  link: "unlinked",
};

test("a placement's discovery preview id is re-keyed to the catalog's serve preview id", () => {
  const plan = planPageBackdrops({ manifest: manifest([page([appBar])]), spec, catalog });
  const placement = plan.manifest.pages[0].placements[0];
  // The whole point: the repo's id renders nothing on the server; this one renders the sticker.
  assert.equal(placement.previewId, "top-app-bar-medium__ideal__default__light");
  assert.equal(placement.code, appBar.code);
  assert.equal(placement.confidence, "high");
  assert.deepEqual(plan.images, [{ pageId: "upcoming", from: "upcoming-mobile.png" }]);
  assert.equal(plan.manifest.pages[0].image.uri, pageImageName("upcoming"));
  assert.equal(plan.manifest.version, PAGES_VERSION);
});

test("a placement with no preview id falls back to the code handle's function name", () => {
  const { previewId, ...noPreviewId } = appBar;
  const plan = planPageBackdrops({
    manifest: manifest([page([noPreviewId])]),
    spec,
    catalog,
  });
  assert.equal(
    plan.manifest.pages[0].placements[0].previewId,
    "top-app-bar-medium__ideal__default__light",
  );
});

test("an unlinked placement is kept, without a preview id", () => {
  const plan = planPageBackdrops({ manifest: manifest([page([appBar, statusBar])]), spec, catalog });
  const placements = plan.manifest.pages[0].placements;
  assert.equal(placements.length, 2);
  assert.equal(placements[1].link, "unlinked");
  assert.equal(placements[1].previewId, undefined);
  assert.equal(placements[1].code, undefined);
});

test("a linked placement the catalog publishes no sticker for keeps its mapping and warns", () => {
  const orphan = { ...appBar, previewId: "nothing_Light", code: "ui/Ghost.kt#GhostSticker" };
  const plan = planPageBackdrops({ manifest: manifest([page([orphan])]), spec, catalog });
  const placement = plan.manifest.pages[0].placements[0];
  // Dropping it would understate the screen's coverage, which is the number this surface reports.
  assert.equal(placement.link, "manifest");
  assert.equal(placement.code, "ui/Ghost.kt#GhostSticker");
  assert.equal(placement.previewId, undefined);
  assert.match(plan.warnings.join("\n"), /1 linked placement\(s\) map to no published sticker/);
});

test("a declared preview id that resolves to nothing does not fall back to the function name", () => {
  // `matchesForPreviewId` returns empty for a sanitised bundle-id collision family — a refusal, not
  // a miss. Falling through to the function-name index would then pick the first image of a
  // `@Preview` that may cover several themes or states, overlaying a sticker the producer
  // explicitly declined to name.
  const colliding = {
    components: [
      {
        componentId: "TopAppBar/Medium",
        images: [
          { path: "images/a/ideal__default__light.png", previewId: "Kt_Sticker_Small Round" },
          { path: "images/b/ideal__default__light.png", previewId: "Kt_Sticker_Small_Round" },
          { path: "images/c/ideal__default__light.png", previewId: "Kt_Sticker_Small_Round_1" },
        ],
      },
    ],
  };
  const collidingSpec = {
    groups: [{ components: [{ componentId: "TopAppBar/Medium", preview: "MediumTopAppBarSticker" }] }],
  };
  const plan = planPageBackdrops({
    manifest: manifest([page([{ ...appBar, previewId: "Kt_Sticker_Small_Round" }])]),
    spec: collidingSpec,
    catalog: colliding,
  });
  assert.equal(plan.manifest.pages[0].placements[0].previewId, undefined);
  assert.equal(plan.manifest.pages[0].placements[0].link, "manifest");
});

test("a page id ending in .png is refused — the suffix is the image route", () => {
  // `/pages/home.png` reads as "the image of the page `home`", so a page id'd `home.png` would be
  // unreachable behind it. The server refuses one too; refusing here keeps it off the branch.
  const plan = planPageBackdrops({
    manifest: manifest([page([appBar], { id: "home.png" }), page([appBar], { id: "library" })]),
    spec,
    catalog,
  });
  assert.deepEqual(
    plan.manifest.pages.map((p) => p.id),
    ["library"],
  );
  assert.match(plan.warnings.join("\n"), /no route-safe id/);
});

test("an unknown link method degrades to unlinked", () => {
  const odd = { ...appBar, link: "vibes" };
  const plan = planPageBackdrops({ manifest: manifest([page([odd])]), spec, catalog });
  assert.equal(plan.manifest.pages[0].placements[0].link, "unlinked");
  assert.equal(plan.manifest.pages[0].placements[0].previewId, undefined);
});

test("a future manifest version publishes nothing rather than half of it", () => {
  const plan = planPageBackdrops({
    manifest: { ...manifest([page([appBar])]), version: 99 },
    spec,
    catalog,
  });
  assert.equal(plan.manifest, null);
  assert.match(plan.warnings.join("\n"), /version 99 is not one this catalog can publish/);
});

test("unroutable ids, duplicate ids and unusable frames are dropped; siblings survive", () => {
  const escaping = page([appBar], { id: "../escape" });
  const noFrame = page([appBar], { id: "home", frame: { width: 0, height: 954 } });
  const first = page([appBar], { id: "library", name: "Library" });
  const duplicate = page([appBar], { id: "library", name: "Impostor" });
  const plan = planPageBackdrops({
    manifest: manifest([escaping, noFrame, first, duplicate]),
    spec,
    catalog,
  });
  assert.deepEqual(
    plan.manifest.pages.map((p) => p.name),
    ["Library"],
  );
  assert.match(plan.warnings.join("\n"), /no route-safe id/);
  assert.match(plan.warnings.join("\n"), /no usable frame size/);
  assert.match(plan.warnings.join("\n"), /declared twice/);
});

test("a placement with undrawable bounds is dropped", () => {
  const zero = { ...appBar, bounds: { x: 0, y: 0, width: 0, height: 64 } };
  const plan = planPageBackdrops({ manifest: manifest([page([zero, statusBar])]), spec, catalog });
  assert.deepEqual(
    plan.manifest.pages[0].placements.map((p) => p.name),
    ["Status bar"],
  );
});

test("an annotation-led catalog with no spec still matches on the preview id", () => {
  const plan = planPageBackdrops({ manifest: manifest([page([appBar])]), spec: {}, catalog });
  assert.equal(
    plan.manifest.pages[0].placements[0].previewId,
    "top-app-bar-medium__ideal__default__light",
  );
});

test("a structurally malformed manifest is refused, not thrown out of", () => {
  // `{"pages":{}}` is valid JSON, so it survives the parse — and `for (const p of {})` would throw
  // "object is not iterable" straight into the workflow's `set -e`, taking the catalog publish
  // with it. Same for a placements object.
  const plan = planPageBackdrops({ manifest: { version: 1, pages: {} }, spec, catalog });
  assert.equal(plan.manifest, null);
  assert.match(plan.warnings.join("\n"), /no usable pages array/);

  const oddPlacements = planPageBackdrops({
    manifest: manifest([page([], { placements: {} })]),
    spec,
    catalog,
  });
  assert.deepEqual(oddPlacements.manifest.pages[0].placements, []);
});

test("an ambiguous function-name fallback is declined rather than guessed", () => {
  // Two components whose preview functions share a member name land in the same `byFunction`
  // bucket. Taking the first would overlay component A inside component B's rectangle.
  const ambiguous = {
    components: [
      { componentId: "A", images: [{ path: "images/a/ideal__default__light.png", previewId: "a" }] },
      { componentId: "B", images: [{ path: "images/b/ideal__default__light.png", previewId: "b" }] },
    ],
  };
  const sharedNameSpec = {
    groups: [
      {
        components: [
          { componentId: "A", preview: "DefaultPreview" },
          { componentId: "B", preview: "DefaultPreview" },
        ],
      },
    ],
  };
  const { previewId, ...noPreviewId } = appBar;
  const plan = planPageBackdrops({
    manifest: manifest([page([{ ...noPreviewId, code: "ui/A.kt#DefaultPreview" }])]),
    spec: sharedNameSpec,
    catalog: ambiguous,
  });
  assert.equal(plan.manifest.pages[0].placements[0].previewId, undefined);
  assert.equal(plan.manifest.pages[0].placements[0].link, "manifest");
});

test("a dot-segment page id is refused — a browser would normalise it away", () => {
  // `/pages/..` normalises to `/` before the request is sent, so such a page could never be
  // opened even though its image published.
  const plan = planPageBackdrops({
    manifest: manifest([
      page([appBar], { id: "." }),
      page([appBar], { id: ".." }),
      page([appBar], { id: "library" }),
    ]),
    spec,
    catalog,
  });
  assert.deepEqual(
    plan.manifest.pages.map((p) => p.id),
    ["library"],
  );
});

test("an unsupported confidence is dropped, not republished", () => {
  // The consumer decodes this into a strict enum, so republishing an unknown value would fail the
  // parse for the WHOLE manifest — one bad string hiding every screen the catalog publishes.
  const odd = { ...appBar, confidence: "certain" };
  const plan = planPageBackdrops({ manifest: manifest([page([odd])]), spec, catalog });
  const placement = plan.manifest.pages[0].placements[0];
  assert.equal(placement.confidence, undefined);
  // The rest of the placement survives — only the styling hint is lost.
  assert.equal(placement.link, "manifest");
  assert.equal(placement.previewId, "top-app-bar-medium__ideal__default__light");
});

test("an out-of-range depth is normalised rather than republished", () => {
  // `Number.isInteger(2147483648)` is true, but the consumer decodes depth as a Kotlin Int — so
  // republishing it fails the parse for the whole manifest and hides every screen. Depth is only a
  // nesting hint, so an out-of-range one becomes 0.
  const deep = { ...appBar, depth: 2147483648 };
  const plan = planPageBackdrops({ manifest: manifest([page([deep])]), spec, catalog });
  assert.equal(plan.manifest.pages[0].placements[0].depth, 0);
  // The placement itself survives — only the hint is normalised.
  assert.equal(plan.manifest.pages[0].placements[0].link, "manifest");
});
