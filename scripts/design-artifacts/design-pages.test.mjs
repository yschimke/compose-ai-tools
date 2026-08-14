import { test } from "node:test";
import assert from "node:assert/strict";

import { PAGES_VERSION, pageImageName, planDesignPages } from "./design-pages.mjs";

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

function page(nodes, overrides = {}) {
  return {
    id: "shape",
    name: "Shape",
    nodeId: "58548:7093",
    frame: { width: 5326, height: 4497 },
    image: { uri: "shape-page.svg", format: "svg" },
    nodes,
    ...overrides,
  };
}

function manifest(pages) {
  return { version: 2, source: "figma", fileKey: "ocdacdEsnHipMJD3egzxKb", pages };
}

const appBar = {
  nodeId: "1:1",
  name: "App bar",
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
  depth: 0,
  ref: "figma:ocdacdEsnHipMJD3egzxKb/1:2",
  link: "unlinked",
};

test("a node's discovery preview id is re-keyed to the catalog's serve preview id", () => {
  const plan = planDesignPages({ manifest: manifest([page([appBar])]), spec, catalog });
  const node = plan.manifest.pages[0].nodes[0];
  // The whole point: the repo's id renders nothing on the server; this one renders the sticker.
  assert.equal(node.previewId, "top-app-bar-medium__ideal__default__light");
  assert.equal(node.code, appBar.code);
  assert.equal(node.confidence, "high");
  assert.deepEqual(plan.images, [{ pageId: "shape", from: "shape-page.svg" }]);
  assert.equal(plan.manifest.pages[0].image.uri, pageImageName("shape"));
  assert.equal(plan.manifest.version, PAGES_VERSION);
});

test("a node with no preview id falls back to the code handle's function name", () => {
  const { previewId, ...noPreviewId } = appBar;
  const plan = planDesignPages({
    manifest: manifest([page([noPreviewId])]),
    spec,
    catalog,
  });
  assert.equal(
    plan.manifest.pages[0].nodes[0].previewId,
    "top-app-bar-medium__ideal__default__light",
  );
});

test("an unlinked node is kept, without a preview id", () => {
  const plan = planDesignPages({ manifest: manifest([page([appBar, statusBar])]), spec, catalog });
  const nodes = plan.manifest.pages[0].nodes;
  assert.equal(nodes.length, 2);
  assert.equal(nodes[1].link, "unlinked");
  assert.equal(nodes[1].previewId, undefined);
  assert.equal(nodes[1].code, undefined);
});

test("a linked node the catalog publishes no sticker for keeps its mapping and warns", () => {
  const orphan = { ...appBar, previewId: "nothing_Light", code: "ui/Ghost.kt#GhostSticker" };
  const plan = planDesignPages({ manifest: manifest([page([orphan])]), spec, catalog });
  const node = plan.manifest.pages[0].nodes[0];
  // Dropping it would understate the page's coverage, which is the number this surface reports.
  assert.equal(node.link, "manifest");
  assert.equal(node.code, "ui/Ghost.kt#GhostSticker");
  assert.equal(node.previewId, undefined);
  assert.match(plan.warnings.join("\n"), /1 linked node\(s\) map to no published sticker/);
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
  const plan = planDesignPages({
    manifest: manifest([page([{ ...appBar, previewId: "Kt_Sticker_Small_Round" }])]),
    spec: collidingSpec,
    catalog: colliding,
  });
  assert.equal(plan.manifest.pages[0].nodes[0].previewId, undefined);
  assert.equal(plan.manifest.pages[0].nodes[0].link, "manifest");
});

test("a page id ending in .svg is refused — the suffix is the export route", () => {
  // `/pages/shape.svg` reads as "the export of the page `shape`", so a page id'd `shape.svg` would
  // be unreachable behind it. The server refuses one too; refusing here keeps it off the branch.
  const plan = planDesignPages({
    manifest: manifest([page([appBar], { id: "shape.svg" }), page([appBar], { id: "library" })]),
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
  const plan = planDesignPages({ manifest: manifest([page([odd])]), spec, catalog });
  assert.equal(plan.manifest.pages[0].nodes[0].link, "unlinked");
  assert.equal(plan.manifest.pages[0].nodes[0].previewId, undefined);
});

test("a future manifest version publishes nothing rather than half of it", () => {
  const plan = planDesignPages({
    manifest: { ...manifest([page([appBar])]), version: 99 },
    spec,
    catalog,
  });
  assert.equal(plan.manifest, null);
  assert.match(plan.warnings.join("\n"), /version 99 is not one this catalog can publish/);
});

test("unroutable ids, duplicate ids and unusable frames are dropped; siblings survive", () => {
  const escaping = page([appBar], { id: "../escape" });
  const noFrame = page([appBar], { id: "home", frame: { width: 0, height: 4497 } });
  const first = page([appBar], { id: "library", name: "Library" });
  const duplicate = page([appBar], { id: "library", name: "Impostor" });
  const plan = planDesignPages({
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

test("a node with no node id is dropped — the id is the only handle there is", () => {
  // There is no recorded rectangle in this contract: the SVG is the geometry, and a node is found
  // by its `data-node-id`. A node without one could never be outlined, hidden or swapped.
  const anonymous = { ...appBar, nodeId: "  " };
  const plan = planDesignPages({ manifest: manifest([page([anonymous, statusBar])]), spec, catalog });
  assert.deepEqual(
    plan.manifest.pages[0].nodes.map((p) => p.name),
    ["Status bar"],
  );
});

test("a page exported as a raster is refused, not republished", () => {
  // The surface's whole capability is addressing nodes inside the export. A raster is a picture,
  // and a page the server can only stare at is worse than a page it never advertises.
  const raster = page([appBar], { id: "home", image: { uri: "home.png", format: "png" } });
  const plan = planDesignPages({
    manifest: manifest([raster, page([appBar], { id: "library" })]),
    spec,
    catalog,
  });
  assert.deepEqual(
    plan.manifest.pages.map((p) => p.id),
    ["library"],
  );
  assert.match(plan.warnings.join("\n"), /exports as png, not svg/);
});

test("an annotation-led catalog with no spec still matches on the preview id", () => {
  const plan = planDesignPages({ manifest: manifest([page([appBar])]), spec: {}, catalog });
  assert.equal(
    plan.manifest.pages[0].nodes[0].previewId,
    "top-app-bar-medium__ideal__default__light",
  );
});

test("a structurally malformed manifest is refused, not thrown out of", () => {
  // `{"pages":{}}` is valid JSON, so it survives the parse — and `for (const p of {})` would throw
  // "object is not iterable" straight into the workflow's `set -e`, taking the catalog publish
  // with it. Same for a nodes object.
  const plan = planDesignPages({ manifest: { version: 2, pages: {} }, spec, catalog });
  assert.equal(plan.manifest, null);
  assert.match(plan.warnings.join("\n"), /no usable pages array/);

  const oddPlacements = planDesignPages({
    manifest: manifest([page([], { nodes: {} })]),
    spec,
    catalog,
  });
  assert.deepEqual(oddPlacements.manifest.pages[0].nodes, []);
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
  const plan = planDesignPages({
    manifest: manifest([page([{ ...noPreviewId, code: "ui/A.kt#DefaultPreview" }])]),
    spec: sharedNameSpec,
    catalog: ambiguous,
  });
  assert.equal(plan.manifest.pages[0].nodes[0].previewId, undefined);
  assert.equal(plan.manifest.pages[0].nodes[0].link, "manifest");
});

test("a dot-segment page id is refused — a browser would normalise it away", () => {
  // `/pages/..` normalises to `/` before the request is sent, so such a page could never be
  // opened even though its image published.
  const plan = planDesignPages({
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
  // parse for the WHOLE manifest — one bad string hiding every page the catalog publishes.
  const odd = { ...appBar, confidence: "certain" };
  const plan = planDesignPages({ manifest: manifest([page([odd])]), spec, catalog });
  const node = plan.manifest.pages[0].nodes[0];
  assert.equal(node.confidence, undefined);
  // The rest of the node survives — only the styling hint is lost.
  assert.equal(node.link, "manifest");
  assert.equal(node.previewId, "top-app-bar-medium__ideal__default__light");
});

test("a node's design-file type is republished, so containers are exact not inferred", () => {
  // `DesignPage.coverageGaps` reads `type` to tell a COMPONENT_SET container from the components
  // inside it, and infers from nesting depth only when no type is stated. Stripping the field here
  // meant every delivery branch took the inference, which an unlisted frame between two components
  // can fool.
  const set = { ...statusBar, nodeId: "1:3", name: "Switch", type: "COMPONENT_SET" };
  const plan = planDesignPages({ manifest: manifest([page([set, appBar])]), spec, catalog });
  const [container, component] = plan.manifest.pages[0].nodes;
  assert.equal(container.type, "COMPONENT_SET");
  assert.equal(component.type, undefined);
  // Not a type: an empty string would read as one on the consumer's side.
  const blank = { ...statusBar, nodeId: "1:4", type: "  " };
  const other = planDesignPages({ manifest: manifest([page([blank])]), spec, catalog });
  assert.equal(other.manifest.pages[0].nodes[0].type, undefined);
});

test("an out-of-range depth is normalised rather than republished", () => {
  // `Number.isInteger(2147483648)` is true, but the consumer decodes depth as a Kotlin Int — so
  // republishing it fails the parse for the whole manifest and hides every page. Depth is only a
  // nesting hint, so an out-of-range one becomes 0.
  const deep = { ...appBar, depth: 2147483648 };
  const plan = planDesignPages({ manifest: manifest([page([deep])]), spec, catalog });
  assert.equal(plan.manifest.pages[0].nodes[0].depth, 0);
  // The node itself survives — only the hint is normalised.
  assert.equal(plan.manifest.pages[0].nodes[0].link, "manifest");
});
