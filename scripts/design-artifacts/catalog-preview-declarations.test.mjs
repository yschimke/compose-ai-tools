import { test } from "node:test";
import assert from "node:assert/strict";

import {
  applyCatalogPreviewDeclarations,
  declarationsByPreviewId,
} from "./catalog-preview-declarations.mjs";

const bytes = (value) => new TextEncoder().encode(JSON.stringify(value));

test("reads authored declarations and detected features from a preview bundle", () => {
  const bundle = {
    previews: [
      {
        id: "Device_Dark",
        captures: [{ focus: {} }, { gestureHint: { direction: "UP" } }],
      },
    ],
    entries: {
      "previews/Device_Dark.overrides.json": bytes({
        declarations: [{ key: "count", type: "int", default: { kind: "int", value: 2 } }],
      }),
      "previews/Device_Dark.remotecompose.json": bytes({
        declarations: [{ name: "label", default: { kind: "string", value: "Hi" } }],
      }),
    },
  };

  assert.deepEqual(declarationsByPreviewId(bundle).get("Device_Dark"), {
    overrides: [{ key: "count", type: "int", default: { kind: "int", value: 2 } }],
    remoteComposeKnobs: [
      { name: "label", default: { kind: "string", value: "Hi" } },
    ],
    supportsFocus: true,
    supportsGestures: true,
  });
});

test("stamps supplement-only image declarations by bridged daemon id", () => {
  const manifest = {
    components: [
      {
        images: [
          { path: "images/device/dark.png", previewId: "Device_Dark" },
          { path: "images/primary/light.png", previewId: "Primary_Light" },
        ],
      },
    ],
  };
  const primary = { previews: [{ id: "Primary_Light", captures: [] }], entries: {} };
  const supplement = {
    previews: [{ id: "Device_Dark", captures: [{ focusGif: {} }] }],
    entries: {
      "previews/Device_Dark.overrides.json": bytes({
        declarations: [
          { key: "expanded", type: "bool", default: { kind: "bool", value: false } },
        ],
      }),
    },
  };

  assert.equal(applyCatalogPreviewDeclarations(manifest, [primary, supplement]), 1);
  assert.deepEqual(manifest.components[0].images[0], {
    path: "images/device/dark.png",
    previewId: "Device_Dark",
    overrides: [
      { key: "expanded", type: "bool", default: { kind: "bool", value: false } },
    ],
    supportsFocus: true,
  });
  assert.deepEqual(manifest.components[0].images[1], {
    path: "images/primary/light.png",
    previewId: "Primary_Light",
  });
});

test("carries @FixedTheme onto the catalog image on its own", () => {
  // A theme specimen declares no knobs and detects no features, so `fixedTheme` has to be enough
  // on its own to produce a declaration entry — otherwise the flag never reaches catalog.json and
  // the specimen re-themes on the browse surface until its daemon happens to be opened.
  const bundle = {
    previews: [{ id: "themecatalog__Brand", captures: [], fixedTheme: true }],
    entries: {},
  };

  assert.deepEqual(declarationsByPreviewId(bundle).get("themecatalog__Brand"), {
    fixedTheme: true,
  });
});

test("leaves fixedTheme off an ordinary preview", () => {
  const bundle = {
    previews: [{ id: "Primary_Light", captures: [{ focus: {} }] }],
    entries: {},
  };

  assert.deepEqual(declarationsByPreviewId(bundle).get("Primary_Light"), {
    supportsFocus: true,
  });
});

test("stamps fixedTheme onto a deferred (live-only) specimen", () => {
  // A deferred record carries no image, so the component loop never sees it — and a live-only
  // specimen has no baked pixels to fall back to, so missing the flag is worse here than anywhere.
  const manifest = {
    components: [],
    deferred: [
      { previewId: "themecatalog__Brand", componentId: "Theme/Brand" },
      { previewId: "Primary_Light", componentId: "Button/Primary" },
      { previewIds: ["themecatalog__Solo"], componentId: "Theme/Solo" },
      { previewIds: ["A", "B"], componentId: "Ambiguous" },
    ],
  };
  const bundle = {
    previews: [
      { id: "themecatalog__Brand", captures: [], fixedTheme: true },
      { id: "themecatalog__Solo", captures: [], fixedTheme: true },
      { id: "Primary_Light", captures: [{ focus: {} }] },
      { id: "A", captures: [], fixedTheme: true },
    ],
    entries: {},
  };

  applyCatalogPreviewDeclarations(manifest, [bundle]);

  assert.equal(manifest.deferred[0].fixedTheme, true);
  assert.equal(manifest.deferred[1].fixedTheme, undefined, "an ordinary deferred card is untouched");
  assert.equal(manifest.deferred[2].fixedTheme, true, "a single-entry previewIds resolves");
  // Two ids is a guess between annotations, exactly as `Deferred.daemonId` refuses to make one.
  assert.equal(manifest.deferred[3].fixedTheme, undefined);
});

test("ignores missing and malformed sidecars", () => {
  const bundle = {
    previews: [{ id: "Bare", captures: [] }, { id: "Broken", captures: [] }],
    entries: {
      "previews/Broken.overrides.json": new TextEncoder().encode("not json"),
    },
  };

  assert.deepEqual([...declarationsByPreviewId(bundle)], []);
});

test("lifts the ground and device frame a browse surface needs before opening anything", () => {
  // A published catalog stages `previews/variants.json` and NOT a root `previews.json`, so these
  // are unrecoverable downstream unless the export writes them. Without them the read-only serving
  // path resolves every preview's ground from the catalog's declared stage and never resolves a
  // device clip at all — a round Wear comparison is drawn on a square stage there and nowhere else.
  const bundle = {
    previews: [
      {
        id: "TimeText_Large_Round",
        params: {
          device: "id:wearos_large_round",
          widthDp: 227,
          heightDp: 227,
          showBackground: true,
          backgroundColor: 4278190080,
          uiMode: 32,
          // Produced-the-render params, deliberately NOT republished: they are already baked into
          // the pixels and say nothing about how the image should be presented.
          density: 2.0,
          fontScale: 1.0,
          locale: "en-GB",
        },
      },
    ],
    entries: {},
  };

  assert.deepEqual(declarationsByPreviewId(bundle).get("TimeText_Large_Round"), {
    previewParams: {
      uiMode: 32,
      showBackground: true,
      backgroundColor: 4278190080,
      device: "id:wearos_large_round",
      widthDp: 227,
      heightDp: 227,
    },
  });
});

test("records nothing for a preview that states no ground and no device", () => {
  // The ordinary component sticker. A `previewParams: {}` on every image would grow every catalog
  // for no reader, and Kotlin reads a missing record back as null — its existing behaviour.
  const bundle = {
    previews: [{ id: "FilledButton", params: { density: 2.0, fontScale: 1.0 } }],
    entries: {},
  };
  assert.equal(declarationsByPreviewId(bundle).get("FilledButton"), undefined);
});

test("drops dp that name no device, because nothing downstream can use them", () => {
  // The frame resolver applies annotation dp against a NAMED device, both axes or neither. On their
  // own they qualify nothing, so republishing them would be bytes a reader must then ignore.
  const bundle = {
    previews: [{ id: "Sized", params: { widthDp: 320, heightDp: 480, showBackground: true } }],
    entries: {},
  };
  assert.deepEqual(declarationsByPreviewId(bundle).get("Sized"), {
    previewParams: { showBackground: true },
  });
});

test("publishes a capture gutter in render pixels, resolved per edge", () => {
  // m3-catalog's elevated button: `@CaptureGutter(all = 4, bottom = 5)` at the catalog's 2.625
  // density is the 11/11/11/13 px the renderer actually added, each edge rounded on its own. A
  // sheet subtracting these lands on the component, which is the whole point of publishing them.
  const bundle = {
    previews: [
      {
        id: "ElevatedButtonSticker_Light",
        params: {
          density: 2.625,
          captureGutter: { start: 4, top: 4, end: 4, bottom: 5 },
        },
      },
    ],
    entries: {},
  };
  assert.deepEqual(declarationsByPreviewId(bundle).get("ElevatedButtonSticker_Light"), {
    previewParams: { captureGutter: { left: 11, top: 11, right: 11, bottom: 13 } },
  });
});

test("resolves start/end onto physical edges for an RTL capture", () => {
  // The renderer placed `start` against the layout direction it composed in, so on an Arabic
  // capture the leading margin is the RIGHT one. A consumer sees pixels, not a direction — it
  // could only guess — so the published record is about the image, not about the annotation.
  const bundle = {
    previews: [
      {
        id: "Sticker_Rtl",
        params: {
          density: 1,
          locale: "ar",
          captureGutter: { start: 4, top: 1, end: 12, bottom: 5 },
        },
      },
      {
        id: "Sticker_Ltr",
        params: {
          density: 1,
          locale: "en-GB",
          captureGutter: { start: 4, top: 1, end: 12, bottom: 5 },
        },
      },
    ],
    entries: {},
  };
  const declarations = declarationsByPreviewId(bundle);
  assert.deepEqual(declarations.get("Sticker_Rtl").previewParams.captureGutter, {
    left: 12,
    top: 1,
    right: 4,
    bottom: 5,
  });
  assert.deepEqual(declarations.get("Sticker_Ltr").previewParams.captureGutter, {
    left: 4,
    top: 1,
    right: 12,
    bottom: 5,
  });
});

test("the bidi pseudolocale mirrors, the accented one does not", () => {
  const of = (locale) =>
    declarationsByPreviewId({
      previews: [
        { id: locale, params: { density: 1, locale, captureGutter: { start: 4, end: 12 } } },
      ],
      entries: {},
    }).get(locale).previewParams.captureGutter;
  assert.equal(of("ar-XB").left, 12);
  assert.equal(of("en-XA").left, 4);
});

test("records no gutter for a preview that declares none, or declares an empty one", () => {
  // `@CaptureGutter(all = 0)` is equivalent to no annotation (discovery drops it), and a preview
  // without one must stay out of the record entirely — see the "nothing" test above.
  const bundle = {
    previews: [
      { id: "Plain", params: { density: 2.625 } },
      { id: "Zeroed", params: { density: 2.625, captureGutter: { start: 0, top: 0, end: 0, bottom: 0 } } },
    ],
    entries: {},
  };
  const declarations = declarationsByPreviewId(bundle);
  assert.equal(declarations.get("Plain"), undefined);
  assert.equal(declarations.get("Zeroed"), undefined);
});

test("falls back to 1x when a manifest states no density, rather than inventing one", () => {
  const bundle = {
    previews: [{ id: "Densityless", params: { captureGutter: { start: 4, top: 4, end: 4, bottom: 5 } } }],
    entries: {},
  };
  assert.deepEqual(declarationsByPreviewId(bundle).get("Densityless"), {
    previewParams: { captureGutter: { left: 4, top: 4, right: 4, bottom: 5 } },
  });
});
