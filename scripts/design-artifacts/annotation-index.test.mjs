import { test } from "node:test";
import assert from "node:assert/strict";

import { perRenderAnnotations } from "./annotation-index.mjs";

/** A bundle carrying one `previews/<id>.semantics.json` sidecar per entry. */
const bundleOf = (trees) => ({
  previews: Object.keys(trees).map((id) => ({ id })),
  entries: Object.fromEntries(
    Object.entries(trees).map(([id, tree]) => [
      `previews/${id}.semantics.json`,
      new TextEncoder().encode(JSON.stringify(tree)),
    ]),
  ),
});

/** The layer builder, standing in for `treeAnnotations`: one annotation naming the tree's label. */
const annotate = (tree) =>
  tree.root.label
    ? [
        {
          kind: "typography",
          bounds: { x: 0, y: 0, width: 10, height: 10 },
          label: tree.root.label,
        },
      ]
    : [];

const image = (path, previewId) => ({ path, ...(previewId ? { previewId } : {}) });

const fannedOut = (keys, label) => ({
  schema: "compose-preview-annotations/v1",
  previews: Object.fromEntries(
    keys.map((key) => [key, [{ kind: "typography", label }]]),
  ),
  references: { "figma:1": [{ kind: "layout", label: "pad 8px" }] },
});

// The bug: one component's layer written under every sticker it publishes, so a content variant
// draws the default render's boxes (compose-preview-server#555).
test("each render is annotated from its own carried tree", () => {
  const published = fannedOut(
    ["button__ideal__default__compact", "button__ideal__icon__compact"],
    "default",
  );
  const manifest = {
    components: [
      {
        componentId: "Button",
        images: [
          image("images/button/ideal__default__compact.png", "Fn_default"),
          image("images/button/ideal__icon__compact.png", "Fn_VARIANT_icon"),
        ],
      },
    ],
  };
  const bundle = bundleOf({
    Fn_default: { root: { label: "one label" } },
    Fn_VARIANT_icon: { root: { label: "icon + two labels" } },
  });

  const out = perRenderAnnotations(published, manifest, [bundle], undefined, annotate);

  assert.equal(out.manifest.previews["button__ideal__default__compact"][0].label, "one label");
  assert.equal(
    out.manifest.previews["button__ideal__icon__compact"][0].label,
    "icon + two labels",
  );
  assert.equal(out.measured, 2);
});

test("the fully-qualified previewId alias is rewritten with its sticker", () => {
  const published = fannedOut(["button__ideal__icon__compact", "Fn_VARIANT_icon"], "default");
  const manifest = {
    components: [
      {
        componentId: "Button",
        images: [image("images/button/ideal__icon__compact.png", "Fn_VARIANT_icon")],
      },
    ],
  };
  const bundle = bundleOf({ Fn_VARIANT_icon: { root: { label: "icon" } } });

  const out = perRenderAnnotations(published, manifest, [bundle], undefined, annotate);

  assert.equal(out.manifest.previews["Fn_VARIANT_icon"][0].label, "icon");
  assert.equal(out.manifest.previews["button__ideal__icon__compact"][0].label, "icon");
});

// An absent overlay degrades to "no redline"; a sibling's overlay asserts a spec this render does
// not have, and nothing on the page says which of the two you are looking at.
test("a render with no carried tree loses the layer measured on its sibling", () => {
  const published = fannedOut(
    ["button__ideal__default__compact", "button__ideal__icon__compact"],
    "default",
  );
  const manifest = {
    components: [
      {
        componentId: "Button",
        images: [
          image("images/button/ideal__default__compact.png", "Fn_default"),
          image("images/button/ideal__icon__compact.png", "Fn_VARIANT_icon"),
        ],
      },
    ],
  };
  const bundle = bundleOf({ Fn_default: { root: { label: "one label" } } });

  const out = perRenderAnnotations(published, manifest, [bundle], undefined, annotate);

  assert.equal(out.manifest.previews["button__ideal__default__compact"][0].label, "one label");
  assert.equal(out.manifest.previews["button__ideal__icon__compact"], undefined);
  assert.equal(out.dropped, 1);
  assert.equal(out.gaps, 1);
});

// A catalog packed without `--with-semantics` has nothing better to offer than what was published.
test("a component that resolves no tree at all is left exactly as published", () => {
  const published = fannedOut(
    ["button__ideal__default__compact", "button__ideal__icon__compact"],
    "default",
  );
  const manifest = {
    components: [
      {
        componentId: "Button",
        images: [
          image("images/button/ideal__default__compact.png", "Fn_default"),
          image("images/button/ideal__icon__compact.png", "Fn_VARIANT_icon"),
        ],
      },
    ],
  };

  const out = perRenderAnnotations(published, manifest, [bundleOf({})], undefined, annotate);

  assert.deepEqual(out.manifest.previews, published.previews);
  assert.equal(out.unresolved, 1);
  assert.equal(out.measured, 0);
});

// `bridgeLivePreviewIds` deliberately withholds a live alias from an image the Android-only
// supplement overrode. Those pixels still have a tree; only their live lane is missing.
test("an unbridged image resolves through the unfiltered semantics-id map", () => {
  const published = fannedOut(["button__ideal__default__compact"], "default");
  const manifest = {
    components: [
      {
        componentId: "Button",
        images: [image("images/button/ideal__default__compact.png")],
      },
    ],
  };
  const bundle = bundleOf({ Fn_default: { root: { label: "supplement" } } });

  const out = perRenderAnnotations(
    published,
    manifest,
    [bundle],
    new Map([["images/button/ideal__default__compact.png", "Fn_default"]]),
    annotate,
  );

  assert.equal(out.manifest.previews["button__ideal__default__compact"][0].label, "supplement");
});

test("the reference layer and schema are carried through untouched", () => {
  const published = fannedOut(["button__ideal__default__compact"], "default");
  const manifest = {
    components: [
      {
        componentId: "Button",
        images: [image("images/button/ideal__default__compact.png", "Fn_default")],
      },
    ],
  };
  const bundle = bundleOf({ Fn_default: { root: { label: "one label" } } });

  const out = perRenderAnnotations(published, manifest, [bundle], undefined, annotate);

  assert.deepEqual(out.manifest.references, published.references);
  assert.equal(out.manifest.schema, "compose-preview-annotations/v1");
});

// The export package writes nothing when a component annotates to nothing, and a tree that
// annotates to nothing must not resurrect a key.
test("a tree that annotates to nothing publishes no key", () => {
  const published = { schema: "compose-preview-annotations/v1", previews: {}, references: {} };
  const manifest = {
    components: [
      {
        componentId: "Button",
        images: [image("images/button/ideal__default__compact.png", "Fn_default")],
      },
    ],
  };
  const bundle = bundleOf({ Fn_default: { root: {} } });

  const out = perRenderAnnotations(published, manifest, [bundle], undefined, annotate);

  assert.deepEqual(out.manifest.previews, {});
  assert.equal(out.dropped, 0);
});
