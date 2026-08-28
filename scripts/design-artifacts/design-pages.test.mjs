import { test } from "node:test";
import assert from "node:assert/strict";

import {
  PAGES_VERSION,
  catalogOwnsNode,
  declaringClassOf,
  declaringClasses,
  publishingSourcePaths,
  pageImageName,
  planDesignPages,
} from "./design-pages.mjs";

/** A catalog whose stickers carry the discovery preview ids a design-map entry would name. */
const catalog = {
  components: [
    {
      componentId: "TopAppBar/Medium",
      images: [
        {
          path: "images/top-app-bar-medium/ideal__default__light.png",
          previewId:
            "ee.schimke.m3catalog.sections.TopAppBarsKt_MediumTopAppBarSticker_Light",
        },
        {
          path: "images/top-app-bar-medium/ideal__default__dark.png",
          previewId:
            "ee.schimke.m3catalog.sections.TopAppBarsKt_MediumTopAppBarSticker_Dark",
        },
      ],
    },
    {
      componentId: "List/Item",
      images: [
        {
          path: "images/list-item/ideal__default__light.png",
          previewId: "list_Light",
        },
      ],
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
  return {
    version: 2,
    source: "figma",
    fileKey: "ocdacdEsnHipMJD3egzxKb",
    pages,
  };
}

const appBar = {
  nodeId: "1:1",
  name: "App bar",
  depth: 0,
  ref: "figma:ocdacdEsnHipMJD3egzxKb/1:1",
  link: "manifest",
  code: "catalog/src/main/kotlin/ee/schimke/m3catalog/sections/TopAppBars.kt#MediumTopAppBarSticker",
  previewId:
    "ee.schimke.m3catalog.sections.TopAppBarsKt_MediumTopAppBarSticker_Light",
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
  const plan = planDesignPages({
    manifest: manifest([page([appBar])]),
    spec,
    catalog,
  });
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
  const plan = planDesignPages({
    manifest: manifest([page([appBar, statusBar])]),
    spec,
    catalog,
  });
  const nodes = plan.manifest.pages[0].nodes;
  assert.equal(nodes.length, 2);
  assert.equal(nodes[1].link, "unlinked");
  assert.equal(nodes[1].previewId, undefined);
  assert.equal(nodes[1].code, undefined);
});

test("a linked node the catalog publishes no sticker for keeps its mapping and warns", () => {
  const orphan = {
    ...appBar,
    previewId: "nothing_Light",
    code: "ui/Ghost.kt#GhostSticker",
  };
  const plan = planDesignPages({
    manifest: manifest([page([orphan])]),
    spec,
    catalog,
  });
  const node = plan.manifest.pages[0].nodes[0];
  // Dropping it would understate the page's coverage, which is the number this surface reports.
  assert.equal(node.link, "manifest");
  assert.equal(node.code, "ui/Ghost.kt#GhostSticker");
  assert.equal(node.previewId, undefined);
  assert.match(
    plan.warnings.join("\n"),
    /1 linked node\(s\) map to no published sticker/,
  );
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
          {
            path: "images/a/ideal__default__light.png",
            previewId: "Kt_Sticker_Small Round",
          },
          {
            path: "images/b/ideal__default__light.png",
            previewId: "Kt_Sticker_Small_Round",
          },
          {
            path: "images/c/ideal__default__light.png",
            previewId: "Kt_Sticker_Small_Round_1",
          },
        ],
      },
    ],
  };
  const collidingSpec = {
    groups: [
      {
        components: [
          {
            componentId: "TopAppBar/Medium",
            preview: "MediumTopAppBarSticker",
          },
        ],
      },
    ],
  };
  const plan = planDesignPages({
    manifest: manifest([
      page([{ ...appBar, previewId: "Kt_Sticker_Small_Round" }]),
    ]),
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
    manifest: manifest([
      page([appBar], { id: "shape.svg" }),
      page([appBar], { id: "library" }),
    ]),
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
  const plan = planDesignPages({
    manifest: manifest([page([odd])]),
    spec,
    catalog,
  });
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
  assert.match(
    plan.warnings.join("\n"),
    /version 99 is not one this catalog can publish/,
  );
});

test("unroutable ids, duplicate ids and unusable frames are dropped; siblings survive", () => {
  const escaping = page([appBar], { id: "../escape" });
  const noFrame = page([appBar], {
    id: "home",
    frame: { width: 0, height: 4497 },
  });
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
  const plan = planDesignPages({
    manifest: manifest([page([anonymous, statusBar])]),
    spec,
    catalog,
  });
  assert.deepEqual(
    plan.manifest.pages[0].nodes.map((p) => p.name),
    ["Status bar"],
  );
});

test("a page exported as a raster is refused, not republished", () => {
  // The surface's whole capability is addressing nodes inside the export. A raster is a picture,
  // and a page the server can only stare at is worse than a page it never advertises.
  const raster = page([appBar], {
    id: "home",
    image: { uri: "home.png", format: "png" },
  });
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
  const plan = planDesignPages({
    manifest: manifest([page([appBar])]),
    spec: {},
    catalog,
  });
  assert.equal(
    plan.manifest.pages[0].nodes[0].previewId,
    "top-app-bar-medium__ideal__default__light",
  );
});

test("a structurally malformed manifest is refused, not thrown out of", () => {
  // `{"pages":{}}` is valid JSON, so it survives the parse — and `for (const p of {})` would throw
  // "object is not iterable" straight into the workflow's `set -e`, taking the catalog publish
  // with it. Same for a nodes object.
  const plan = planDesignPages({
    manifest: { version: 2, pages: {} },
    spec,
    catalog,
  });
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
      {
        componentId: "A",
        images: [
          { path: "images/a/ideal__default__light.png", previewId: "a" },
        ],
      },
      {
        componentId: "B",
        images: [
          { path: "images/b/ideal__default__light.png", previewId: "b" },
        ],
      },
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
    manifest: manifest([
      page([{ ...noPreviewId, code: "ui/A.kt#DefaultPreview" }]),
    ]),
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

test("a container flag survives publishing", () => {
  // The projection rebuilds every node from an allowlist, so a field that is not named here is
  // silently dropped on the way to the delivery branch. When that field is `container`, the
  // COMPONENT_SET comes back as a component nobody implemented and a fully-implemented page
  // reports missing work — the exact regression the consumer-side fix was for.
  const set = { ...appBar, nodeId: "1:8", name: "Shape Set", container: true };
  const plan = planDesignPages({
    manifest: manifest([page([set])]),
    spec,
    catalog,
  });
  const node = plan.manifest.pages[0].nodes.find((n) => n.nodeId === "1:8");
  assert.equal(node.container, true);
});

test("a truthy non-boolean container is dropped rather than republished", () => {
  // It decodes into a Kotlin Boolean; a string there fails the parse for the WHOLE manifest and
  // hides every page, which is a far worse outcome than counting one set as a gap.
  const set = { ...appBar, nodeId: "1:8", name: "Shape Set", container: "yes" };
  const plan = planDesignPages({
    manifest: manifest([page([set])]),
    spec,
    catalog,
  });
  const node = plan.manifest.pages[0].nodes.find((n) => n.nodeId === "1:8");
  assert.equal(node.container, undefined);
});

test("a node's inventory=false survives publishing; true is left implicit", () => {
  // Same allowlist hazard as `container`: drop the field on the way to the delivery branch and the
  // kit's own base parts come back as 24 components nobody implemented. `true` is the consumer's
  // default, so emitting it would only make every manifest larger and every re-import noisier.
  const base = {
    ...appBar,
    nodeId: "1:8",
    name: "Base / Loading Icon",
    inventory: false,
  };
  const kept = { ...appBar, nodeId: "1:9", name: "Button", inventory: true };
  const plan = planDesignPages({
    manifest: manifest([page([base, kept])]),
    spec,
    catalog,
  });
  const nodes = plan.manifest.pages[0].nodes;
  assert.equal(nodes.find((n) => n.nodeId === "1:8").inventory, false);
  assert.equal(nodes.find((n) => n.nodeId === "1:9").inventory, undefined);
});

test("a non-boolean inventory is dropped rather than republished", () => {
  // It decodes into a Kotlin Boolean. Same reasoning as `container`: one bad string there fails the
  // parse for the whole manifest and hides every page, which is worse than counting one base part.
  const base = {
    ...appBar,
    nodeId: "1:8",
    name: "Base / Loading Icon",
    inventory: "no",
  };
  const plan = planDesignPages({
    manifest: manifest([page([base])]),
    spec,
    catalog,
  });
  assert.equal(plan.manifest.pages[0].nodes[0].inventory, undefined);
});

test("a page's inventory=false survives publishing; true is left implicit", () => {
  const icons = page([appBar], { id: "icons", inventory: false });
  const shapes = page([appBar], { id: "shapes", inventory: true });
  const plan = planDesignPages({
    manifest: manifest([icons, shapes]),
    spec,
    catalog,
  });
  const [published, other] = plan.manifest.pages;
  assert.equal(published.inventory, false);
  assert.equal(other.inventory, undefined);
});

test("a node drawn by an override cell says so; one drawn by its own preview does not", () => {
  // The page has to be able to tell a component someone WROTE from a kit variant we REACHED by
  // seeding a knob on a neighbouring one. Both are `link: manifest`, so the link cannot say it.
  const cell = {
    ...appBar,
    nodeId: "1:8",
    previewId:
      "ee.schimke.m3catalog.sections.TopAppBarsKt_MediumTopAppBarSticker_Light_VARIANT_off",
  };
  const plan = planDesignPages({
    manifest: manifest([page([appBar, cell])]),
    spec,
    catalog: {
      components: [
        {
          componentId: "TopAppBar/Medium",
          images: [
            { previewId: appBar.previewId, path: "images/top-app-bar.png" },
            { previewId: cell.previewId, path: "images/top-app-bar__off.png" },
          ],
        },
      ],
    },
  });
  const nodes = plan.manifest.pages[0].nodes;
  assert.equal(nodes.find((n) => n.nodeId === "1:1").cell, undefined);
  assert.equal(nodes.find((n) => n.nodeId === "1:8").cell, true);
});

test("an unlinked node makes no cell claim, even carrying a stale variant preview id", () => {
  // A manifest can carry `link: unlinked` beside a leftover `previewId`, and the consumer already
  // refuses to DRAW that (`renderablePreviewId`). Claiming cell-ness off it would be the same
  // contradiction in the other direction — the page saying "no code behind this" in red while
  // colouring it as something we reached.
  const stale = {
    ...appBar,
    nodeId: "1:8",
    link: "unlinked",
    previewId: "com.example.SwitchKt_SwitchOn_Light_VARIANT_off",
  };
  const plan = planDesignPages({
    manifest: manifest([page([stale])]),
    spec,
    catalog,
  });
  assert.equal(plan.manifest.pages[0].nodes[0].cell, undefined);
});

test("an unsupported confidence is dropped, not republished", () => {
  // The consumer decodes this into a strict enum, so republishing an unknown value would fail the
  // parse for the WHOLE manifest — one bad string hiding every page the catalog publishes.
  const odd = { ...appBar, confidence: "certain" };
  const plan = planDesignPages({
    manifest: manifest([page([odd])]),
    spec,
    catalog,
  });
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
  const set = {
    ...statusBar,
    nodeId: "1:3",
    name: "Switch",
    type: "COMPONENT_SET",
  };
  const plan = planDesignPages({
    manifest: manifest([page([set, appBar])]),
    spec,
    catalog,
  });
  const [container, component] = plan.manifest.pages[0].nodes;
  assert.equal(container.type, "COMPONENT_SET");
  assert.equal(component.type, undefined);
  // Not a type: an empty string would read as one on the consumer's side.
  const blank = { ...statusBar, nodeId: "1:4", type: "  " };
  const other = planDesignPages({
    manifest: manifest([page([blank])]),
    spec,
    catalog,
  });
  assert.equal(other.manifest.pages[0].nodes[0].type, undefined);
});

test("an out-of-range depth is normalised rather than republished", () => {
  // `Number.isInteger(2147483648)` is true, but the consumer decodes depth as a Kotlin Int — so
  // republishing it fails the parse for the whole manifest and hides every page. Depth is only a
  // nesting hint, so an out-of-range one becomes 0.
  const deep = { ...appBar, depth: 2147483648 };
  const plan = planDesignPages({
    manifest: manifest([page([deep])]),
    spec,
    catalog,
  });
  assert.equal(plan.manifest.pages[0].nodes[0].depth, 0);
  // The node itself survives — only the hint is normalised.
  assert.equal(plan.manifest.pages[0].nodes[0].link, "manifest");
});

// ---- A shared page import across two catalogs (wear-m3-catalog#98) -----------------------------
//
// `design/pages/pages.json` is a REPO-level artifact: one import describes the design file, and a
// repo publishing two catalogs hands the same nodes to both. Everything below is about telling
// "this node is mine" from "this node is my sibling's", which nothing used to ask.

/** The sibling module's catalog — the one the shared import was written against. */
const ownerCatalog = {
  source: { module: ":catalog" },
  components: [
    {
      componentId: "Button/Filled",
      sourceFile: "src/main/kotlin/app/sections/Buttons.kt",
      sourceModule: ":catalog",
      images: [
        {
          path: "images/button-filled/ideal__default.png",
          previewId: "app.sections.ButtonsKt.FilledButton",
        },
      ],
    },
  ],
};

/** The parallel catalog in the same repo: different module, reproducing the same kit node. */
const parallelCatalog = {
  source: { module: ":remote-catalog" },
  components: [
    {
      componentId: "Button/Filled",
      reference: "figma:FILE/35239:93092",
      sourceFile: "src/main/kotlin/app/remote/RemotePreviews.kt",
      sourceModule: ":remote-catalog",
      // Carried by the producer, not derived here: the directory because `projectDir` may remap a
      // logical path anywhere, the function because an arbitrary `@Preview(name = …)` makes a
      // preview id unsplittable.
      sourceDirectory: "remote-catalog",
      sourceFunction: "FilledRemoteButton",
      images: [
        {
          path: "images/button-filled/ideal__default.png",
          previewId:
            "app.remote.RemotePreviewsKt.FilledRemoteButton_width_227dp",
        },
      ],
    },
  ],
};

const sharedImport = {
  version: PAGES_VERSION,
  pages: [
    {
      id: "buttons",
      name: "Buttons",
      nodeId: "1:1",
      frame: { width: 100, height: 100 },
      image: { uri: "buttons.svg", format: "svg" },
      nodes: [
        {
          nodeId: "35239:93092",
          name: "Style=Filled",
          link: "manifest",
          ref: "figma:FILE/35239:93092",
          code: "catalog/src/main/kotlin/app/sections/Buttons.kt#FilledButton",
          previewId: "app.sections.ButtonsKt.FilledButton",
        },
      ],
    },
  ],
};

const onlyNode = (result) => result.manifest.pages[0].nodes[0];

test("the catalog the import was written for is completely unaffected", () => {
  const result = planDesignPages({
    manifest: sharedImport,
    spec: {},
    catalog: ownerCatalog,
  });
  const node = onlyNode(result);
  // Verbatim: the incoming handle is repo-relative and already correct, so nothing is rewritten and
  // no working source link can move.
  assert.equal(
    node.code,
    "catalog/src/main/kotlin/app/sections/Buttons.kt#FilledButton",
  );
  assert.equal(node.link, "manifest");
  assert.equal(node.previewId, "button-filled__ideal__default");
  assert.equal(
    result.warnings.filter((w) => w.includes("does not publish")).length,
    0,
    "nothing is foreign to the module that owns the import",
  );
});

test("the sibling catalog publishes ITS OWN component for the same design node", () => {
  // The bug: remote-m3's pages listed 75 components under the wear catalog's files and scored
  // nothing, because the id and function joins are both in the producing module's namespace.
  const result = planDesignPages({
    manifest: sharedImport,
    spec: {},
    catalog: parallelCatalog,
  });
  const node = onlyNode(result);
  assert.equal(
    node.code,
    "remote-catalog/src/main/kotlin/app/remote/RemotePreviews.kt#FilledRemoteButton",
    "our file AND our function — a sibling's #Member beside our file names nothing that exists",
  );
  assert.equal(
    node.previewId,
    "button-filled__ideal__default",
    "and it has a render to score",
  );
});

test("a node this catalog neither owns nor reproduces publishes as unlinked", () => {
  const stranger = {
    ...sharedImport,
    pages: [
      {
        ...sharedImport.pages[0],
        nodes: [
          {
            ...sharedImport.pages[0].nodes[0],
            ref: "figma:FILE/nobody-references-this",
          },
        ],
      },
    ],
  };
  const result = planDesignPages({
    manifest: stranger,
    spec: {},
    catalog: parallelCatalog,
  });
  const node = onlyNode(result);
  assert.equal(
    node.link,
    "unlinked",
    "the claim is demonstrably another module's",
  );
  assert.equal(
    "code" in node,
    false,
    "so it is not republished as this catalog's work",
  );
  assert.equal(
    result.warnings.some((w) => w.includes("does not publish")),
    true,
    "and the run says so, since a whole sheet of these is a misconfiguration",
  );
});

test("an ambiguous reference is refused rather than guessed", () => {
  // Two components claiming one kit node — `Card` and `TitleCard` in the catalog that motivated
  // this. Putting one component's render inside the other's outline is worse than no render.
  const ambiguous = {
    ...parallelCatalog,
    components: [
      parallelCatalog.components[0],
      { ...parallelCatalog.components[0], componentId: "Button/FilledAlt" },
    ],
  };
  const result = planDesignPages({
    manifest: sharedImport,
    spec: {},
    catalog: ambiguous,
  });
  const node = onlyNode(result);
  assert.equal(node.link, "unlinked");
  assert.equal(node.previewId, undefined);
});

test("a component with no sourceFile is dropped rather than published one namespace out", () => {
  const noSource = {
    ...parallelCatalog,
    components: [{ ...parallelCatalog.components[0], sourceFile: undefined }],
  };
  const result = planDesignPages({
    manifest: sharedImport,
    spec: {},
    catalog: noSource,
  });
  assert.equal("code" in onlyNode(result), false);
});

test("a node with no declared previewId keeps its claim, as it always did", () => {
  // Nothing can place it, so nothing can prove it foreign — the pre-existing behaviour for every
  // manifest that names no preview ids at all.
  const undeclared = {
    ...sharedImport,
    pages: [
      {
        ...sharedImport.pages[0],
        nodes: [
          {
            ...sharedImport.pages[0].nodes[0],
            previewId: undefined,
            ref: undefined,
          },
        ],
      },
    ],
  };
  const result = planDesignPages({
    manifest: undeclared,
    spec: {},
    catalog: parallelCatalog,
  });
  assert.equal(
    onlyNode(result).code,
    "catalog/src/main/kotlin/app/sections/Buttons.kt#FilledButton",
  );
});

// ---- The joins themselves ---------------------------------------------------------------------

test("a root-project catalog publishes its bare, already-repo-relative sourceFile", () => {
  // The root project's carried directory is the empty string — a real answer, not a missing one.
  const rootCatalog = {
    source: { module: ":" },
    components: [
      {
        ...parallelCatalog.components[0],
        sourceModule: ":",
        sourceDirectory: "",
        sourceFile: "app/src/main/kotlin/app/remote/RemotePreviews.kt",
      },
    ],
  };
  const result = planDesignPages({
    manifest: sharedImport,
    spec: {},
    catalog: rootCatalog,
  });
  assert.equal(
    onlyNode(result).code,
    "app/src/main/kotlin/app/remote/RemotePreviews.kt#FilledRemoteButton",
  );
});

test("declaringClasses reads the files a catalog publishes previews from", () => {
  assert.deepEqual(
    [...declaringClasses(parallelCatalog)],
    ["app.remote.RemotePreviewsKt"],
  );
  assert.deepEqual([...declaringClasses({})], []);
});

test("catalogOwnsNode places a claim by its declaring file", () => {
  const classes = declaringClasses(ownerCatalog);
  assert.equal(
    catalogOwnsNode(
      { previewId: "app.sections.ButtonsKt.FilledButton" },
      classes,
    ),
    true,
  );
  // A variant this catalog did not bake is still ours — the file is what the claim is about. One of
  // wear-m3-catalog's 185 real nodes is exactly this case, and an exact-id test would drop it.
  assert.equal(
    catalogOwnsNode(
      { previewId: "app.sections.ButtonsKt.NeverBaked_VARIANT_x" },
      classes,
    ),
    true,
  );
  assert.equal(
    catalogOwnsNode(
      { previewId: "app.remote.RemotePreviewsKt.FilledRemoteButton" },
      classes,
    ),
    false,
  );
  assert.equal(
    catalogOwnsNode({}, classes),
    true,
    "nothing to place ⇒ nothing proves it foreign",
  );
});

test("a sibling module compiling the same class is not ours", () => {
  // The class half of ownership cannot separate two modules: a discovery id is `classInfo.name` +
  // `method.name` and names no module, while nothing stops `:app` and `:feature` each compiling an
  // `ee/app/sections/Buttons.kt`. Without the module half the sibling's node reads as local, and
  // the page then pairs our render with its code or drops one the reference join would have found.
  const catalog = {
    components: [
      {
        componentId: "Button/Filled",
        sourceDirectory: "app",
        sourceFile: "src/main/kotlin/app/sections/Buttons.kt",
        images: [
          {
            path: "images/button-filled/ideal__default.png",
            previewId: "app.sections.ButtonsKt.FilledButton",
          },
        ],
      },
    ],
  };
  const classes = declaringClasses(catalog);
  const paths = publishingSourcePaths(catalog);
  assert.deepEqual(
    [...paths.get("app.sections.ButtonsKt")],
    ["app/src/main/kotlin/app/sections/Buttons.kt"],
  );

  const ourNode = {
    previewId: "app.sections.ButtonsKt.FilledButton",
    code: "app/src/main/kotlin/app/sections/Buttons.kt#FilledButton",
  };
  const siblingNode = {
    previewId: "app.sections.ButtonsKt.FilledButton",
    code: "feature/src/main/kotlin/app/sections/Buttons.kt#FilledButton",
  };

  // Both nodes carry the SAME declaring class, so the class test alone says yes to both — which is
  // what makes this a module question rather than a naming one.
  assert.equal(catalogOwnsNode(ourNode, classes), true);
  assert.equal(catalogOwnsNode(siblingNode, classes), true);

  assert.equal(catalogOwnsNode(ourNode, classes, paths), true);
  assert.equal(catalogOwnsNode(siblingNode, classes, paths), false);
});

test("a nested Gradle project is not the parent's", () => {
  // Directory containment would hand `:app` every node `:app:feature` declares, and the root
  // project's `""` the whole repository — the same collision, in the layout where it is most
  // likely. Exact source paths have no such ambiguity.
  const parent = {
    components: [
      {
        componentId: "Button/Filled",
        sourceDirectory: "app",
        sourceFile: "src/main/kotlin/app/sections/Buttons.kt",
        images: [{ previewId: "app.sections.ButtonsKt.FilledButton" }],
      },
    ],
  };
  const nestedNode = {
    previewId: "app.sections.ButtonsKt.FilledButton",
    code: "app/feature/src/main/kotlin/app/sections/Buttons.kt#FilledButton",
  };
  assert.equal(
    catalogOwnsNode(nestedNode, declaringClasses(parent), publishingSourcePaths(parent)),
    false,
  );

  // And the root project cannot claim its own children either.
  const root = {
    components: [
      {
        componentId: "Button/Filled",
        sourceDirectory: "",
        sourceFile: "src/main/kotlin/app/sections/Buttons.kt",
        images: [{ previewId: "app.sections.ButtonsKt.FilledButton" }],
      },
    ],
  };
  const rootPaths = publishingSourcePaths(root);
  assert.deepEqual(
    [...rootPaths.get("app.sections.ButtonsKt")],
    ["src/main/kotlin/app/sections/Buttons.kt"],
  );
  assert.equal(
    catalogOwnsNode(
      {
        previewId: "app.sections.ButtonsKt.FilledButton",
        code: "modules/x/src/main/kotlin/app/sections/Buttons.kt#FilledButton",
      },
      declaringClasses(root),
      rootPaths,
    ),
    false,
  );
  // Its own bare, already-repository-relative handle still resolves.
  assert.equal(
    catalogOwnsNode(
      {
        previewId: "app.sections.ButtonsKt.FilledButton",
        code: "src/main/kotlin/app/sections/Buttons.kt#FilledButton",
      },
      declaringClasses(root),
      rootPaths,
    ),
    true,
  );
});

test("a partly-stamped catalog does not disown its own unstamped components", () => {
  // `applySourceFiles` stamps identity per component, and only when discovery resolved that
  // component's preview function — so a catalog can carry it for some components and not others.
  // Judging every node against one catalog-wide path set would then call the unstamped
  // component's OWN node foreign as soon as any sibling was stamped, which is worse than the
  // class test alone. Identity is scoped per declaring class for exactly this reason.
  const mixed = {
    components: [
      {
        componentId: "Button/Filled",
        sourceDirectory: "app",
        sourceFile: "src/main/kotlin/app/sections/Buttons.kt",
        images: [{ previewId: "app.sections.ButtonsKt.FilledButton" }],
      },
      {
        // Discovery could not resolve this one, so it carries no source identity at all.
        componentId: "Card/Elevated",
        images: [{ previewId: "app.sections.CardsKt.ElevatedCard" }],
      },
    ],
  };
  const classes = declaringClasses(mixed);
  const paths = publishingSourcePaths(mixed);

  // The stamped class is placed, so a foreign node carrying it is still rejected.
  assert.equal(paths.has("app.sections.ButtonsKt"), true);
  assert.equal(
    catalogOwnsNode(
      {
        previewId: "app.sections.ButtonsKt.FilledButton",
        code: "feature/src/main/kotlin/app/sections/Buttons.kt#FilledButton",
      },
      classes,
      paths,
    ),
    false,
  );

  // The unstamped class is not in the map, so its own node keeps the class test's answer.
  assert.equal(paths.has("app.sections.CardsKt"), false);
  assert.equal(
    catalogOwnsNode(
      {
        previewId: "app.sections.CardsKt.ElevatedCard",
        code: "app/src/main/kotlin/app/sections/Cards.kt#ElevatedCard",
      },
      classes,
      paths,
    ),
    true,
  );
});

test("ownership falls open when either side names no module", () => {
  const withPath = {
    components: [
      {
        componentId: "Button/Filled",
        sourceDirectory: "app",
        sourceFile: "src/main/kotlin/app/sections/Buttons.kt",
        images: [{ previewId: "app.sections.ButtonsKt.FilledButton" }],
      },
    ],
  };

  // A node with no code handle: nothing to place it in, so the class match stands alone.
  assert.equal(
    catalogOwnsNode(
      { previewId: "app.sections.ButtonsKt.FilledButton" },
      declaringClasses(withPath),
      publishingSourcePaths(withPath),
    ),
    true,
  );

  // A bundle predating the identity fields records none, and every node keeps the old behaviour.
  const legacy = {
    components: [
      {
        componentId: "Button/Filled",
        images: [{ previewId: "app.sections.ButtonsKt.FilledButton" }],
      },
    ],
  };
  assert.equal(publishingSourcePaths(legacy).size, 0);
  assert.equal(
    catalogOwnsNode(
      {
        previewId: "app.sections.ButtonsKt.FilledButton",
        code: "feature/src/main/kotlin/app/sections/Buttons.kt#FilledButton",
      },
      declaringClasses(legacy),
      publishingSourcePaths(legacy),
    ),
    true,
  );
});

// ---- Review findings on the shared-import fix (#4680) ------------------------------------------

test("an extra-renders catalog keeps its links instead of losing every one", () => {
  // `--extra-renders` images deliberately carry no `previewId` (design-references.mjs), so the
  // declaring-class set is EMPTY. That is ignorance, not evidence of foreignness — judging on it
  // would unlink the whole page surface for such a catalog.
  const extraRenders = {
    source: { module: ":catalog" },
    components: [
      {
        componentId: "Button/Filled",
        sourceFile: "src/main/kotlin/app/sections/Buttons.kt",
        images: [{ path: "images/button-filled/ideal__default.png" }],
      },
    ],
  };
  const result = planDesignPages({
    manifest: sharedImport,
    spec: {},
    catalog: extraRenders,
  });
  const node = onlyNode(result);
  assert.equal(node.link, "manifest", "unable to judge ⇒ the claim is kept");
  assert.equal(
    node.code,
    "catalog/src/main/kotlin/app/sections/Buttons.kt#FilledButton",
  );
});

test("a foreign node never resolves through a colliding local function name", () => {
  // The function join is in the PRODUCING module's namespace. A generic `#Member` shared with an
  // unrelated local preview would pair one component's code with another's render.
  const collidingSpec = {
    groups: [
      {
        components: [
          { componentId: "Unrelated/Thing", preview: "FilledButton" },
        ],
      },
    ],
  };
  const colliding = {
    source: { module: ":remote-catalog" },
    components: [
      // Same @Preview function name as the owner's node, but a different component entirely, and
      // it claims no reference.
      {
        componentId: "Unrelated/Thing",
        sourceFile: "src/main/kotlin/app/remote/Unrelated.kt",
        sourceModule: ":remote-catalog",
        images: [
          {
            path: "images/unrelated-thing/ideal__default.png",
            previewId: "app.remote.UnrelatedKt.FilledButton",
          },
        ],
      },
    ],
  };
  const result = planDesignPages({
    manifest: sharedImport,
    spec: collidingSpec,
    catalog: colliding,
  });
  const node = onlyNode(result);
  assert.equal(
    node.link,
    "unlinked",
    "no reference claims this node, so nothing substantiates it",
  );
  assert.equal(
    node.previewId,
    undefined,
    "and emphatically not the colliding component's render",
  );
});

test("a rewritten node drops the owner's provenance rather than wearing it", () => {
  // `confidence` grades a link we did not make; `cell` describes an override capture named in the
  // SIBLING's id namespace. Neither is true of our component.
  const owned = {
    ...sharedImport,
    pages: [
      {
        ...sharedImport.pages[0],
        nodes: [
          {
            ...sharedImport.pages[0].nodes[0],
            link: "code-connect",
            confidence: "high",
            previewId: "app.sections.ButtonsKt.FilledButton_VARIANT_off",
          },
        ],
      },
    ],
  };
  const result = planDesignPages({
    manifest: owned,
    spec: {},
    catalog: parallelCatalog,
  });
  const node = onlyNode(result);
  assert.equal(
    node.link,
    "manifest",
    "our own reference tied this, not the owner's Code Connect",
  );
  assert.equal("confidence" in node, false);
  assert.equal(
    "cell" in node,
    false,
    "the override-variant claim was about the sibling's id",
  );

  // …and the owning catalog keeps all of it, because nothing was replaced.
  const kept = onlyNode(
    planDesignPages({ manifest: owned, spec: {}, catalog: ownerCatalog }),
  );
  assert.equal(kept.link, "code-connect");
  assert.equal(kept.confidence, "high");
  assert.equal(kept.cell, true);
});

test("a dotted @Preview name does not split the declaring class", () => {
  // `sanitizeForPath` deliberately keeps dots so an id stays lossless, so `@Preview(name = "Phone.v2")`
  // ends `…FooKt.Render_Phone.v2`. Splitting at the last dot put two variants of ONE function in
  // different "classes", and the unbaked one then read as foreign.
  assert.equal(declaringClassOf("pkg.FooKt.Render_Phone.v2"), "pkg.FooKt");
  assert.equal(declaringClassOf("pkg.FooKt.Render_Tablet.v3"), "pkg.FooKt");
  assert.equal(
    declaringClassOf("ee.app.sections.ButtonsKt.TextAction"),
    "ee.app.sections.ButtonsKt",
  );
  assert.equal(declaringClassOf("nodots"), "");

  // …so both variants place under one class, and neither is mistaken for a sibling's work.
  const dotted = {
    components: [
      {
        componentId: "Render",
        images: [
          {
            path: "images/render/a.png",
            previewId: "pkg.FooKt.Render_Phone.v2",
          },
        ],
      },
    ],
  };
  const classes = declaringClasses(dotted);
  assert.deepEqual([...classes], ["pkg.FooKt"]);
  assert.equal(
    catalogOwnsNode({ previewId: "pkg.FooKt.Render_Tablet.v3" }, classes),
    true,
  );
});

// ---- The producer carries what cannot be derived (wear-m3-catalog#98 follow-up) ----------------

test("a remapped project directory is published as recorded, not as derived", () => {
  // `project(":bundle-format").projectDir = file("bundle/format")`. This repository remaps 100
  // projects and NOT ONE derives correctly from its Gradle path, so the directory is carried.
  const remapped = {
    source: { module: ":bundle-format" },
    components: [
      {
        ...parallelCatalog.components[0],
        sourceModule: ":bundle-format",
        sourceDirectory: "bundle/format",
        sourceFile: "src/main/kotlin/app/Preview.kt",
        sourceFunction: "Sticker",
      },
    ],
  };
  const result = planDesignPages({
    manifest: sharedImport,
    spec: {},
    catalog: remapped,
  });
  assert.equal(
    onlyNode(result).code,
    "bundle/format/src/main/kotlin/app/Preview.kt#Sticker",
  );
});

test("a carried function name survives what no id parse could recover", () => {
  // `@Preview(name = "Large Round")` and `name = "Phone.v2"` reach the id through `sanitizeForPath`,
  // which passes spaces and dots verbatim — the id does not split back into function and label.
  for (const fn of ["Filled_Button", "Render", "A_B_C"]) {
    const catalog = {
      components: [
        {
          ...parallelCatalog.components[0],
          sourceFunction: fn,
          images: [
            {
              path: "images/button-filled/ideal__default.png",
              previewId: `app.remote.RemotePreviewsKt.${fn}_Large Round`,
            },
          ],
        },
      ],
    };
    assert.equal(
      onlyNode(planDesignPages({ manifest: sharedImport, spec: {}, catalog }))
        .code,
      `remote-catalog/src/main/kotlin/app/remote/RemotePreviews.kt#${fn}`,
    );
  }
});

test("a catalog published before the fields existed keeps its render, losing only the label", () => {
  // The migration case, and the reason the render does not hang off the code handle: a reference
  // match is an identity, so the sticker stands even when nothing can be said about its source.
  const older = {
    components: [
      {
        componentId: "Button/Filled",
        reference: "figma:FILE/35239:93092",
        sourceFile: "src/main/kotlin/app/remote/RemotePreviews.kt",
        sourceModule: ":remote-catalog",
        images: [
          {
            path: "images/button-filled/ideal__default.png",
            previewId: "app.remote.RemotePreviewsKt.FilledRemoteButton",
          },
        ],
      },
    ],
  };
  const node = onlyNode(
    planDesignPages({ manifest: sharedImport, spec: {}, catalog: older }),
  );
  assert.equal(node.link, "manifest", "the pairing is still established");
  assert.equal(
    node.previewId,
    "button-filled__ideal__default",
    "and still scores",
  );
  assert.equal(
    "code" in node,
    false,
    "but nothing is claimed about the source",
  );
});

test("the root project's empty directory is a usable answer, not a missing one", () => {
  // The producer records `""` for a root-project catalog and the stamp must carry it: treating it
  // as absent dropped every handle such a catalog could publish.
  const root = {
    components: [
      {
        ...parallelCatalog.components[0],
        sourceDirectory: "",
        sourceFile: "app/src/main/kotlin/app/Preview.kt",
        sourceFunction: "Sticker",
      },
    ],
  };
  assert.equal(
    onlyNode(
      planDesignPages({ manifest: sharedImport, spec: {}, catalog: root }),
    ).code,
    "app/src/main/kotlin/app/Preview.kt#Sticker",
  );
});
