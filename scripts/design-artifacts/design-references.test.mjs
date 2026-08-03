import { test } from "node:test";
import assert from "node:assert/strict";

import {
  REFERENCES_SCHEMA,
  derivationMismatches,
  functionNameOf,
  imagesByPreviewFunction,
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
