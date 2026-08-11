import { test } from "node:test";
import assert from "node:assert/strict";

import {
  REFERENCES_SCHEMA,
  derivationMismatches,
  functionNameOf,
  imagesByPreviewFunction,
  imagesByPreviewId,
  sanitizeBundleEntryId,
  planDesignReferences,
  referenceId,
  referenceManifest,
  servePreviewId,
} from "./design-references.mjs";

test("servePreviewId mirrors ServeCatalogStore.previewIdFor", () => {
  assert.equal(
    servePreviewId("images/button-filled/ideal__default__dark.png"),
    "button-filled__ideal__default__dark",
  );
  assert.equal(
    servePreviewId("images/chat-contact/ideal__default__compact__locale-ar.png"),
    "chat-contact__ideal__default__compact__locale-ar",
  );
});

test("derivationMismatches accepts a livePreview that agrees and reports one that doesn't", () => {
  const ok = {
    components: [
      {
        componentId: "Chat/Contact",
        images: [
          {
            path: "images/chat-contact/ideal__default__compact.png",
            livePreview: "https://preview.coo.ee/x/p/chat-contact__ideal__default__compact",
          },
        ],
      },
    ],
  };
  assert.deepEqual(derivationMismatches(ok), []);

  const drifted = structuredClone(ok);
  drifted.components[0].images[0].livePreview = "https://preview.coo.ee/x/p/chat-contact-ideal";
  assert.equal(derivationMismatches(drifted).length, 1);
});

test("functionNameOf takes the #Member of a code handle", () => {
  assert.equal(functionNameOf("ui/ChatBodyPreviews.kt#ContactChatPreview"), "ContactChatPreview");
  assert.equal(functionNameOf("ui/ChatBodyPreviews.kt"), null);
  assert.equal(functionNameOf("ui/ChatBodyPreviews.kt#"), null);
});

/** The meshcore-mobile shape: one component, light + dark from two separate @Preview functions. */
const SPEC = {
  groups: [
    {
      name: "Chat",
      components: [
        {
          componentId: "Chat/Contact",
          preview: "ContactChatPreview",
          variants: [
            { theme: "dark", preview: "ContactChatDarkPreview" },
            { props: { locale: "ar" }, preview: "ContactChatArabicPreview" },
          ],
        },
      ],
    },
  ],
};

const CATALOG = {
  components: [
    {
      componentId: "Chat/Contact",
      images: [
        {
          path: "images/chat-contact/ideal__default__compact.png",
          state: "default",
          size: "compact",
          width: 1078,
          height: 2399,
        },
        {
          path: "images/chat-contact/ideal__default__dark__compact.png",
          state: "default",
          theme: "dark",
          size: "compact",
          width: 1078,
          height: 2399,
        },
        {
          path: "images/chat-contact/ideal__default__compact__locale-ar.png",
          state: "default",
          size: "compact",
          props: { locale: "ar" },
          width: 1078,
          height: 2399,
        },
      ],
    },
  ],
};

test("imagesByPreviewFunction partitions stickers between the default preview and its variants", () => {
  const index = imagesByPreviewFunction(SPEC, CATALOG);

  assert.deepEqual(
    index.get("ContactChatPreview").map((m) => m.image.path),
    ["images/chat-contact/ideal__default__compact.png"],
  );
  assert.deepEqual(
    index.get("ContactChatDarkPreview").map((m) => m.image.path),
    ["images/chat-contact/ideal__default__dark__compact.png"],
  );
  assert.deepEqual(
    index.get("ContactChatArabicPreview").map((m) => m.image.path),
    ["images/chat-contact/ideal__default__compact__locale-ar.png"],
  );
});

test("a dark sticker never lands on the light function", () => {
  // The bug this partition exists to prevent: assigning every image to the default
  // preview would give the light reference the dark sticker's preview id, so the
  // server would diff a light mock against a dark render.
  const index = imagesByPreviewFunction(SPEC, CATALOG);
  const light = index.get("ContactChatPreview").map((m) => m.image.theme);
  assert.deepEqual(light, [undefined]);
});

test("two variants sharing a state but differing in props each claim their own sticker", () => {
  // Matching on only the FIRST declared axis let the `icon+label` variant claim both
  // disabled stickers — publishing its reference against the wrong one and leaving the
  // `label` variant unmapped. Every declared axis has to match.
  const spec = {
    groups: [
      {
        components: [
          {
            componentId: "Button/Filled",
            preview: "FilledButtonPreview",
            variants: [
              {
                state: "disabled",
                props: { content: "icon+label" },
                preview: "FilledButtonDisabledIconPreview",
              },
              {
                state: "disabled",
                props: { content: "label" },
                preview: "FilledButtonDisabledLabelPreview",
              },
            ],
          },
        ],
      },
    ],
  };
  const catalog = {
    components: [
      {
        componentId: "Button/Filled",
        images: [
          { path: "images/button-filled/ideal__default.png", state: "default" },
          {
            path: "images/button-filled/ideal__disabled__content-icon-label.png",
            state: "disabled",
            props: { content: "icon+label" },
          },
          {
            path: "images/button-filled/ideal__disabled__content-label.png",
            state: "disabled",
            props: { content: "label" },
          },
        ],
      },
    ],
  };

  const index = imagesByPreviewFunction(spec, catalog);

  assert.deepEqual(
    index.get("FilledButtonDisabledIconPreview").map((m) => m.image.path),
    ["images/button-filled/ideal__disabled__content-icon-label.png"],
  );
  assert.deepEqual(
    index.get("FilledButtonDisabledLabelPreview").map((m) => m.image.path),
    ["images/button-filled/ideal__disabled__content-label.png"],
  );
  assert.deepEqual(
    index.get("FilledButtonPreview").map((m) => m.image.path),
    ["images/button-filled/ideal__default.png"],
  );
});

test("a variant that declares no axis claims nothing", () => {
  const spec = {
    groups: [
      {
        components: [
          {
            componentId: "Button/Filled",
            preview: "FilledButtonPreview",
            variants: [{ preview: "UnderSpecifiedPreview" }],
          },
        ],
      },
    ],
  };
  const catalog = {
    components: [
      {
        componentId: "Button/Filled",
        images: [{ path: "images/button-filled/ideal__default.png", state: "default" }],
      },
    ],
  };

  const index = imagesByPreviewFunction(spec, catalog);

  assert.equal(index.has("UnderSpecifiedPreview"), false);
  assert.equal(index.get("FilledButtonPreview").length, 1);
});

test("planDesignReferences maps design-map entries onto serve preview ids", () => {
  const designMap = {
    components: [
      {
        code: "meshcore-components/src/commonMain/kotlin/ui/ChatBodyPreviews.kt#ContactChatPreview",
        source: "figma",
        ref: "figma:abc/73:5",
      },
      {
        code: "meshcore-components/src/commonMain/kotlin/ui/ChatBodyPreviews.kt#ContactChatDarkPreview",
        source: "claude-design",
        ref: "design/ContactChat.dark.html",
      },
    ],
  };

  const { records, warnings } = planDesignReferences({ designMap, spec: SPEC, catalog: CATALOG });

  assert.deepEqual(warnings, []);
  assert.deepEqual(
    records.map((r) => r.previewId),
    ["chat-contact__ideal__default__compact", "chat-contact__ideal__default__dark__compact"],
  );
  // Dimensions come from the sticker, because the server refuses to score a pair
  // whose sizes differ rather than scaling one into a misleading number.
  assert.deepEqual(records[0].raster, {
    path: "references/chat-contact__ideal__default__compact.png",
    width: 1078,
    height: 2399,
  });
  assert.equal(records[0].source.provider, "figma");
  assert.equal(records[0].source.uri, "figma:abc/73:5");
  assert.equal(records[1].origin.ref, "design/ContactChat.dark.html");
});

test("planDesignReferences publishes the untagged binding from variant arrays", () => {
  const designMap = {
    components: [
      {
        code: "meshcore-components/src/commonMain/kotlin/ui/ChatBodyPreviews.kt#ContactChatPreview",
        source: "figma",
        ref: [
          { ref: "figma:abc/73:5" },
          { ref: "figma:abc/73:6", state: "disabled" },
        ],
        previewId: [
          { previewId: "ee.components.ui.ChatBodyPreviewsKt.ContactChatPreview" },
          {
            previewId: "ee.components.ui.ChatBodyPreviewsKt.ContactChatPreview_Disabled",
            state: "disabled",
          },
        ],
      },
    ],
  };

  const { records, warnings } = planDesignReferences({ designMap, spec: SPEC, catalog: CATALOG });

  assert.deepEqual(warnings, []);
  assert.equal(records.length, 1);
  assert.equal(records[0].origin.ref, "figma:abc/73:5");
  assert.equal(
    records[0].origin.previewId,
    "ee.components.ui.ChatBodyPreviewsKt.ContactChatPreview",
  );
  assert.equal(records[0].source.uri, "figma:abc/73:5");
});

test("planDesignReferences accepts string defaults in variant arrays", () => {
  const designMap = {
    components: [
      {
        code: "meshcore-components/src/commonMain/kotlin/ui/ChatBodyPreviews.kt#ContactChatPreview",
        source: "figma",
        ref: ["figma:abc/73:5", { ref: "figma:abc/73:6", state: "disabled" }],
        previewId: [
          "ee.components.ui.ChatBodyPreviewsKt.ContactChatPreview",
          {
            previewId: "ee.components.ui.ChatBodyPreviewsKt.ContactChatPreview_Disabled",
            state: "disabled",
          },
        ],
      },
    ],
  };

  const { records, warnings } = planDesignReferences({ designMap, spec: SPEC, catalog: CATALOG });

  assert.deepEqual(warnings, []);
  assert.equal(records.length, 1);
  assert.equal(records[0].origin.ref, "figma:abc/73:5");
  assert.equal(
    records[0].origin.previewId,
    "ee.components.ui.ChatBodyPreviewsKt.ContactChatPreview",
  );
});

test("planDesignReferences warns before dropping invalid scalar and array refs", () => {
  const designMap = {
    components: [
      {
        code: "meshcore-components/src/commonMain/kotlin/ui/ChatBodyPreviews.kt#ContactChatPreview",
        source: "figma",
        ref: "",
      },
      {
        code: "meshcore-components/src/commonMain/kotlin/ui/ChatBodyPreviews.kt#ContactChatDarkPreview",
        source: "figma",
        ref: [{}],
      },
    ],
  };

  const { records, warnings } = planDesignReferences({ designMap, spec: SPEC, catalog: CATALOG });

  assert.deepEqual(records, []);
  assert.equal(warnings.length, 2);
  assert.match(warnings[0], /ContactChatPreview.*invalid ref binding/);
  assert.match(warnings[1], /ContactChatDarkPreview.*invalid ref binding/);
});

test("planDesignReferences refuses an array with no untagged catalog binding", () => {
  const designMap = {
    components: [
      {
        code: "meshcore-components/src/commonMain/kotlin/ui/ChatBodyPreviews.kt#ContactChatPreview",
        source: "figma",
        ref: [{ ref: "figma:abc/73:6", state: "disabled" }],
        previewId: [
          {
            previewId: "ee.components.ui.ChatBodyPreviewsKt.ContactChatPreview_Disabled",
            state: "disabled",
          },
        ],
      },
    ],
  };

  const { records, warnings } = planDesignReferences({ designMap, spec: SPEC, catalog: CATALOG });

  assert.deepEqual(records, []);
  assert.equal(warnings.length, 2);
  assert.match(warnings[0], /0 untagged ref bindings/);
  assert.match(warnings[1], /0 untagged previewId bindings/);
});

test("planDesignReferences carries a declared board density to the driver", () => {
  // Only the design-map author knows a board's scale, and it is what lets the reference column be
  // quoted in dp/sp instead of the board's own pixels (design-parity#279). It is driver input, not
  // part of the served manifest, so it rides on `origin` — and is absent, never guessed, when the
  // entry says nothing.
  const designMap = {
    components: [
      {
        code: "meshcore-components/src/commonMain/kotlin/ui/ChatBodyPreviews.kt#ContactChatPreview",
        source: "figma",
        ref: "figma:abc/73:5",
        density: 3,
      },
      {
        code: "meshcore-components/src/commonMain/kotlin/ui/ChatBodyPreviews.kt#ContactChatDarkPreview",
        source: "claude-design",
        ref: "design/ContactChat.dark.html",
      },
    ],
  };

  const { records } = planDesignReferences({ designMap, spec: SPEC, catalog: CATALOG });
  assert.equal(records[0].origin.density, 3);
  assert.equal(records[1].origin.density, undefined);
});

test("planDesignReferences carries a per-reference contents-only override to the driver", () => {
  const designMap = {
    components: [
      {
        code: "meshcore-components/src/commonMain/kotlin/ui/ChatBodyPreviews.kt#ContactChatPreview",
        source: "figma",
        ref: "figma:abc/73:5",
        referenceContentsOnly: false,
      },
    ],
  };

  const { records } = planDesignReferences({ designMap, spec: SPEC, catalog: CATALOG });
  assert.equal(records[0].origin.referenceContentsOnly, false);
});

test("planDesignReferences carries an image renderer density to the raster target", () => {
  const catalog = structuredClone(CATALOG);
  catalog.components[0].images[0].density = 3;
  const designMap = {
    components: [
      {
        code: "meshcore-components/src/commonMain/kotlin/ui/ChatBodyPreviews.kt#ContactChatPreview",
        source: "figma",
        ref: "figma:abc/73:5",
      },
    ],
  };

  const { records } = planDesignReferences({ designMap, spec: SPEC, catalog });

  assert.equal(records[0].raster.density, 3);
});

test("planDesignReferences warns rather than throwing when a handle maps to nothing", () => {
  const designMap = {
    components: [{ code: "ui/Other.kt#NotInTheSpec", source: "claude-design", ref: "x.html" }],
  };
  const { records, warnings } = planDesignReferences({ designMap, spec: SPEC, catalog: CATALOG });

  assert.deepEqual(records, []);
  assert.equal(warnings.length, 1);
  assert.match(warnings[0], /NotInTheSpec/);
});

test("referenceId disambiguates two references on one preview and caps at the server's limit", () => {
  assert.equal(referenceId("chat-contact__ideal", 0), "chat-contact__ideal");
  assert.equal(referenceId("chat-contact__ideal", 1), "chat-contact__ideal--2");

  const long = "a".repeat(400);
  assert.ok(referenceId(long, 0).length <= 160);
  assert.ok(referenceId(long, 1).length <= 160);
  assert.match(referenceId(long, 0), /^[A-Za-z0-9._-]{1,160}$/);
});

test("referenceManifest emits the served schema and drops driver-only fields", () => {
  const records = [
    {
      id: "a",
      previewId: "p",
      label: "l",
      raster: { path: "references/a.png", width: 1, height: 2 },
      source: { provider: "figma", attributes: {} },
      origin: { source: "figma", ref: "figma:x/1:1" },
    },
    {
      id: "b",
      previewId: "q",
      label: "l",
      raster: { path: "references/b.png", width: 1, height: 2 },
      source: { provider: "figma", attributes: {} },
      origin: {},
      rastered: false,
    },
  ];

  const manifest = referenceManifest(records);

  assert.equal(manifest.schema, REFERENCES_SCHEMA);
  // The un-rasterised record is dropped, not published with a missing PNG.
  assert.deepEqual(
    manifest.references.map((r) => r.id),
    ["a"],
  );
  assert.equal("origin" in manifest.references[0], false);
  assert.equal("rastered" in manifest.references[0], false);
});

// A multipreview component: light and dark are two @Preview annotations on ONE function, so
// imagesByPreviewFunction cannot split them the way it splits a declared `variants` entry.
const MULTIPREVIEW_SPEC = {
  groups: [
    {
      name: "Contacts",
      components: [{ componentId: "ContactRow/Chat", preview: "ContactRowChatPreview" }],
    },
  ],
};

const MULTIPREVIEW_CATALOG = {
  components: [
    {
      componentId: "ContactRow/Chat",
      images: [
        {
          path: "images/contactrow-chat/ideal__default__light__compact.png",
          state: "default",
          theme: "light",
          size: "compact",
          width: 805,
          height: 147,
          previewId: "ee.app.ui.ComponentPreviewsKt.ContactRowChatPreview_Light",
        },
        {
          path: "images/contactrow-chat/ideal__default__dark__compact.png",
          state: "default",
          theme: "dark",
          size: "compact",
          width: 805,
          height: 147,
          previewId: "ee.app.ui.ComponentPreviewsKt.ContactRowChatPreview_Dark",
        },
      ],
    },
  ],
};

test("planDesignReferences narrows a multipreview function to the previewId the entry names", () => {
  const designMap = {
    components: [
      {
        code: "app/src/main/kotlin/ui/ComponentPreviews.kt#ContactRowChatPreview",
        source: "figma",
        ref: "figma:abc/87:477",
        previewId: "ee.app.ui.ComponentPreviewsKt.ContactRowChatPreview_Light",
      },
    ],
  };

  const { records, warnings } = planDesignReferences({
    designMap,
    spec: MULTIPREVIEW_SPEC,
    catalog: MULTIPREVIEW_CATALOG,
  });

  assert.deepEqual(warnings, []);
  // Without the narrowing the light-only Figma node is published against the dark sticker too,
  // and the server scores a dark render against a light design.
  assert.deepEqual(
    records.map((r) => r.previewId),
    ["contactrow-chat__ideal__default__light__compact"],
  );
});

test("planDesignReferences keeps both stickers when the entry names no previewId", () => {
  const designMap = {
    components: [
      {
        code: "app/src/main/kotlin/ui/ComponentPreviews.kt#ContactRowChatPreview",
        source: "figma",
        ref: "figma:abc/87:477",
      },
    ],
  };

  const { records } = planDesignReferences({
    designMap,
    spec: MULTIPREVIEW_SPEC,
    catalog: MULTIPREVIEW_CATALOG,
  });

  assert.equal(records.length, 2);
});

test("planDesignReferences ignores previewId when the catalog images carry none", () => {
  // The `--extra-renders` case: images folded in from a second module have no previewId, so
  // narrowing on an absent field would silently unmap every one of them.
  const designMap = {
    components: [
      {
        code: "meshcore-components/src/commonMain/kotlin/ui/ChatBodyPreviews.kt#ContactChatPreview",
        source: "figma",
        ref: "figma:abc/73:5",
        previewId: "ee.components.ui.ChatBodyPreviewsKt.ContactChatPreview_Contact chat",
      },
    ],
  };

  const { records, warnings } = planDesignReferences({ designMap, spec: SPEC, catalog: CATALOG });

  assert.deepEqual(warnings, []);
  assert.deepEqual(
    records.map((r) => r.previewId),
    ["chat-contact__ideal__default__compact"],
  );
});

test("planDesignReferences warns when the entry's previewId matches no published sticker", () => {
  const designMap = {
    components: [
      {
        code: "app/src/main/kotlin/ui/ComponentPreviews.kt#ContactRowChatPreview",
        source: "figma",
        ref: "figma:abc/87:477",
        previewId: "ee.app.ui.ComponentPreviewsKt.ContactRowChatPreview_Typo",
      },
    ],
  };

  const { records, warnings } = planDesignReferences({
    designMap,
    spec: MULTIPREVIEW_SPEC,
    catalog: MULTIPREVIEW_CATALOG,
  });

  assert.deepEqual(records, []);
  assert.equal(warnings.length, 1);
  assert.match(warnings[0], /ContactRowChatPreview_Typo/);
  assert.match(warnings[0], /ContactRowChatPreview_Light/);
});

// --- Annotation-led catalogs (no `spec.groups`) ------------------------------------------------
//
// The inventory lives in `@CatalogComponent` / `@CatalogVariant` annotations and `catalog.spec.json`
// carries only cover-sheet fields, so the function-name index has nothing to walk. Before the
// previewId fallback, every entry in such a repo warned "matches no published sticker" and the
// delivery branch published no `references/` at all — a silent, total loss of the PNG ↔ Design lane.

const ANNOTATION_SPEC = {
  system: "m3-catalog",
  title: "Material 3 Design Kit",
  modes: ["light", "dark"],
};

const ANNOTATION_CATALOG = {
  components: [
    {
      componentId: "Button/Filled",
      images: [
        {
          variant: "ideal",
          path: "images/button-filled/ideal__default__light.png",
          state: "default",
          theme: "light",
          width: 300,
          height: 210,
          previewId: "ee.m3catalog.sections.ButtonsKt.FilledButton_Light",
        },
        {
          variant: "ideal",
          path: "images/button-filled/ideal__default__dark.png",
          state: "default",
          theme: "dark",
          width: 300,
          height: 210,
          previewId: "ee.m3catalog.sections.ButtonsKt.FilledButton_Dark",
        },
      ],
    },
  ],
};

test("planDesignReferences joins on previewId when the spec declares no groups", () => {
  const designMap = {
    components: [
      {
        code: "catalog/src/main/kotlin/ee/m3catalog/sections/Buttons.kt#FilledButton",
        source: "figma",
        ref: "figma:abc/57994:2227",
        previewId: "ee.m3catalog.sections.ButtonsKt.FilledButton_Light",
      },
    ],
  };

  const { records, warnings } = planDesignReferences({
    designMap,
    spec: ANNOTATION_SPEC,
    catalog: ANNOTATION_CATALOG,
  });

  assert.deepEqual(warnings, []);
  // Exactly the light sticker: the previewId join selects one image, so the dark twin is not
  // published against a light design.
  assert.equal(records.length, 1);
  assert.equal(records[0].raster.width, 300);
  assert.match(records[0].label, /^Button\/Filled — figma$/);
});

test("planDesignReferences still warns when no published image carries the entry's previewId", () => {
  const designMap = {
    components: [
      {
        code: "catalog/src/main/kotlin/ee/m3catalog/sections/Buttons.kt#GhostButton",
        source: "figma",
        ref: "figma:abc/1:2",
        previewId: "ee.m3catalog.sections.ButtonsKt.GhostButton_Light",
      },
    ],
  };

  const { records, warnings } = planDesignReferences({
    designMap,
    spec: ANNOTATION_SPEC,
    catalog: ANNOTATION_CATALOG,
  });

  assert.deepEqual(records, []);
  assert.equal(warnings.length, 1);
  assert.match(warnings[0], /matches no published sticker/);
});

test("imagesByPreviewId indexes every image that carries a previewId", () => {
  const { exact } = imagesByPreviewId(ANNOTATION_CATALOG);
  assert.deepEqual(
    [...exact.keys()].sort(),
    [
      "ee.m3catalog.sections.ButtonsKt.FilledButton_Dark",
      "ee.m3catalog.sections.ButtonsKt.FilledButton_Light",
    ],
  );
  // Images with no previewId (the `--extra-renders` fold-in) are simply absent rather than keyed
  // under undefined, which would collide every one of them onto a single bucket.
  assert.equal(imagesByPreviewId({ components: [{ images: [{ path: "a.png" }] }] }).exact.size, 0);
});

// --- The two id namespaces ---------------------------------------------------------------------
//
// A design-map entry records the RAW discovery id; a catalog image's previewId is the SANITISED
// in-bundle form. Comparing them verbatim drops every entry whose `@Preview(name = …)` contains a
// space — which is why a catalog using only "Light"/"Dark" never notices.

const SANITISED_CATALOG = {
  components: [
    {
      componentId: "Home/SmallRound",
      images: [
        {
          variant: "ideal",
          path: "images/home-smallround/ideal__default__light.png",
          state: "default",
          width: 192,
          height: 192,
          // What the bundle manifest carries: spaces replaced with underscores.
          previewId: "ee.app.ui.HomeKt.HomeListViewPreview_Devices_-_Small_Round",
        },
      ],
    },
  ],
};

const SANITISED_SPEC = {
  groups: [
    {
      components: [
        {
          componentId: "Home/SmallRound",
          preview: "HomeListViewPreview",
        },
      ],
    },
  ],
};

test("sanitizeBundleEntryId mirrors the Kotlin transform and is idempotent", () => {
  assert.equal(sanitizeBundleEntryId("Foo_A B"), "Foo_A_B");
  assert.equal(sanitizeBundleEntryId("com.example.FooKt.Bar_Font scale 1.5x"),
    "com.example.FooKt.Bar_Font_scale_1.5x");
  assert.equal(sanitizeBundleEntryId("Foo_wearos-small-round"), "Foo_wearos-small-round");
  assert.equal(sanitizeBundleEntryId(sanitizeBundleEntryId("Foo_A B")), sanitizeBundleEntryId("Foo_A B"));
});

test("planDesignReferences joins a raw previewId to its sanitised catalog twin", () => {
  const designMap = {
    components: [
      {
        code: "app/src/main/kotlin/ui/Home.kt#HomeListViewPreview",
        source: "figma",
        ref: "figma:abc/73:6",
        // The RAW id, as discovery emits it and design-map records it.
        previewId: "ee.app.ui.HomeKt.HomeListViewPreview_Devices - Small Round",
      },
    ],
  };

  const { records, warnings } = planDesignReferences({
    designMap,
    spec: ANNOTATION_SPEC,
    catalog: SANITISED_CATALOG,
  });

  assert.deepEqual(warnings, []);
  assert.equal(records.length, 1);
  assert.match(records[0].label, /^Home\/SmallRound — figma$/);
});

test("planDesignReferences narrows spec matches using a sanitised array primary id", () => {
  const designMap = {
    components: [
      {
        code: "app/src/main/kotlin/ui/Home.kt#HomeListViewPreview",
        source: "figma",
        ref: [{ ref: "figma:abc/73:6" }],
        previewId: [
          { previewId: "ee.app.ui.HomeKt.HomeListViewPreview_Devices - Small Round" },
        ],
      },
    ],
  };

  const { records, warnings } = planDesignReferences({
    designMap,
    spec: SANITISED_SPEC,
    catalog: SANITISED_CATALOG,
  });

  assert.deepEqual(warnings, []);
  assert.equal(records.length, 1);
  assert.equal(records[0].origin.ref, "figma:abc/73:6");
});

test("planDesignReferences refuses every id in an ambiguous bundle collision family", () => {
  // What a real bundle-derived catalog carries after raw `P_A B` and `P_A/B` collide: the first
  // sanitised claimant keeps the base and the second gets `_1`. The raw aliases that identify
  // which is which are not retained in catalog.json, so neither raw id can be reversed safely.
  const collidingCatalog = {
    components: [
      {
        componentId: "Home/Round",
        images: [
          { path: "a.png", width: 1, height: 1, previewId: "ee.HomeKt.P_A_B" },
          { path: "b.png", width: 1, height: 1, previewId: "ee.HomeKt.P_A_B_1" },
        ],
      },
    ],
  };

  for (const previewId of ["ee.HomeKt.P_A B", "ee.HomeKt.P_A/B"]) {
    const result = planDesignReferences({
      designMap: {
        components: [{ code: "a.kt#P", source: "figma", ref: "figma:abc/1:1", previewId }],
      },
      spec: ANNOTATION_SPEC,
      catalog: collidingCatalog,
    });
    assert.deepEqual(result.records, []);
    assert.equal(result.warnings.length, 1);
    assert.match(result.warnings[0], /matches no published sticker/);
  }

  const specLed = planDesignReferences({
    designMap: {
      components: [
        {
          code: "a.kt#P",
          source: "figma",
          ref: "figma:abc/1:1",
          previewId: "ee.HomeKt.P_A/B",
        },
      ],
    },
    spec: {
      groups: [{ components: [{ componentId: "Home/Round", preview: "P" }] }],
    },
    catalog: collidingCatalog,
  });
  assert.deepEqual(specLed.records, []);
  assert.equal(specLed.warnings.length, 1);
  assert.match(specLed.warnings[0], /collision family cannot be reversed/);

  const exactAuthoredSuffix = planDesignReferences({
    designMap: {
      components: [
        {
          code: "a.kt#P",
          source: "figma",
          ref: "figma:abc/1:1",
          previewId: "ee.HomeKt.P_A_B_1",
        },
      ],
    },
    spec: {
      groups: [{ components: [{ componentId: "Home/Round", preview: "P" }] }],
    },
    catalog: collidingCatalog,
  });
  assert.deepEqual(exactAuthoredSuffix.records, []);
  assert.equal(exactAuthoredSuffix.warnings.length, 1);
  assert.match(exactAuthoredSuffix.warnings[0], /collision family cannot be reversed/);
});
