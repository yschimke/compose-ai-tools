import { test } from "node:test";
import assert from "node:assert/strict";

import {
  DEFERRED,
  REQUIRED,
  deferralPlan,
  deferredModes,
  entryPriority,
  isImageDeferred,
  modePriority,
  previewForImage,
  previewNamesByPriority,
  renderFilterPatterns,
  specDefersAnything,
  splitDeferredImages,
  splitDeferredVariants,
} from "./catalog-priority.mjs";

/** A minimal spec: one group, components as given. */
function spec(components, extra = {}) {
  return {
    system: "demo",
    title: "Demo",
    groups: [{ name: "Components", components }],
    ...extra,
  };
}

test("entryPriority defaults to required and only reads the documented deferred value", () => {
  assert.equal(entryPriority({}), REQUIRED);
  assert.equal(entryPriority({ priority: "required" }), REQUIRED);
  assert.equal(entryPriority({ priority: "deferred" }), DEFERRED);
  // Fails closed: an unrecognised value bakes the entry (validation rejects it separately).
  assert.equal(entryPriority({ priority: "defered" }), REQUIRED);
  assert.equal(entryPriority(undefined), REQUIRED);
});

test("modePriority resolves exact key, then wildcard, then the required default", () => {
  const s = spec([], { modes: ["light", "dark"], modePriority: { light: "required", "*": "deferred" } });
  assert.equal(modePriority(s, "light"), REQUIRED);
  assert.equal(modePriority(s, "dark"), DEFERRED);
  assert.equal(modePriority(s, "sepia"), DEFERRED);
  // No table at all, and a null mode (an untagged sticker), stay required.
  assert.equal(modePriority(spec([]), "dark"), REQUIRED);
  assert.equal(modePriority(s, null), REQUIRED);
});

test("isImageDeferred never defers a sticker that names no theme", () => {
  const s = spec([], { modes: ["light", "dark"], modePriority: { "*": "deferred" } });
  // The untagged primary render survives even under a defer-everything wildcard, so every
  // component keeps baked pixels.
  assert.equal(isImageDeferred(s, { state: "default" }), false);
  assert.equal(isImageDeferred(s, { theme: "dark" }), true);
});

test("deferredModes expands the wildcard over declared modes", () => {
  const s = spec([], { modes: ["light", "dark", "sepia"], modePriority: { light: "required", "*": "deferred" } });
  assert.deepEqual(deferredModes(s), ["dark", "sepia"]);
  // A wildcard with no `modes` list to expand over still reports as deferring.
  assert.deepEqual(deferredModes(spec([], { modePriority: { "*": "deferred" } })), ["*"]);
  assert.deepEqual(deferredModes(spec([])), []);
});

test("previewNamesByPriority only defers a function nothing required points at", () => {
  const { required, deferred } = previewNamesByPriority(
    spec([
      { componentId: "A", preview: "Alpha" },
      { componentId: "B", preview: "Beta", priority: "deferred" },
      // Shared function: one entry defers it, another needs it — so it must still render.
      { componentId: "C", preview: "Alpha", priority: "deferred" },
      {
        componentId: "D",
        preview: "Delta",
        variants: [
          { preview: "DeltaPressed", state: "pressed" },
          { preview: "DeltaDisabled", state: "disabled", priority: "deferred" },
        ],
      },
    ]),
  );
  assert.deepEqual(required, ["Alpha", "Delta", "DeltaPressed"]);
  // "Alpha" is required by componentId A even though C defers it, so it must still render.
  assert.deepEqual(deferred, ["Beta", "DeltaDisabled"]);
});

test("renderFilterPatterns is empty when nothing is deferred, and positive when it is", () => {
  assert.deepEqual(renderFilterPatterns(spec([{ componentId: "A", preview: "Alpha" }])), []);
  // Mode-only deferral thins what is published, not what is rendered: the fan-out lives inside
  // one @Preview function, which the render filter cannot split.
  assert.deepEqual(
    renderFilterPatterns(
      spec([{ componentId: "A", preview: "Alpha" }], {
        modes: ["light", "dark"],
        modePriority: { "*": "deferred" },
      }),
    ),
    [],
  );
  assert.deepEqual(
    renderFilterPatterns(
      spec([
        { componentId: "A", preview: "Alpha" },
        { componentId: "B", preview: "Beta", priority: "deferred" },
      ]),
    ),
    // Positive list: render what is still required. The driver drops "Beta" from the publish too, so
    // the render set and the published set agree — the invariant this whole module exists to keep.
    ["Alpha"],
  );
});

test("splitDeferredImages keeps the untagged sticker and drops deferred themes", () => {
  const s = spec([], { modes: ["light", "dark"], modePriority: { light: "required", "*": "deferred" } });
  const images = [
    { path: "a.png", state: "default" },
    { path: "b.png", theme: "light" },
    { path: "c.png", theme: "dark" },
    { path: "d.png", theme: "sepia" },
  ];
  const { baked, deferred } = splitDeferredImages(images, s);
  assert.deepEqual(
    baked.map((i) => i.path),
    ["a.png", "b.png"],
  );
  assert.deepEqual(
    deferred.map((i) => i.path),
    ["c.png", "d.png"],
  );
});

test("splitDeferredVariants returns the component untouched when nothing is deferred", () => {
  const component = { componentId: "A", preview: "Alpha", variants: [{ preview: "P", state: "pressed" }] };
  const result = splitDeferredVariants(component);
  assert.equal(result.component, component, "same object — the common path allocates nothing");
  assert.deepEqual(result.deferredVariants, []);
});

test("splitDeferredVariants folds out the deferred variants", () => {
  const component = {
    componentId: "A",
    preview: "Alpha",
    variants: [
      { preview: "P", state: "pressed" },
      { preview: "D", state: "disabled", priority: "deferred" },
    ],
  };
  const { component: trimmed, deferredVariants } = splitDeferredVariants(component);
  assert.deepEqual(
    trimmed.variants.map((v) => v.preview),
    ["P"],
  );
  assert.deepEqual(
    deferredVariants.map((v) => v.preview),
    ["D"],
  );
  assert.equal(component.variants.length, 2, "the input spec is not mutated");
});

test("specDefersAnything sees entries, variants and modes", () => {
  assert.equal(specDefersAnything(spec([{ componentId: "A", preview: "Alpha" }])), false);
  assert.equal(
    specDefersAnything(spec([{ componentId: "A", preview: "Alpha", priority: "deferred" }])),
    true,
  );
  assert.equal(
    specDefersAnything(
      spec([
        {
          componentId: "A",
          preview: "Alpha",
          variants: [{ preview: "D", state: "disabled", priority: "deferred" }],
        },
      ]),
    ),
    true,
  );
  assert.equal(
    specDefersAnything(spec([], { modes: ["light", "dark"], modePriority: { "*": "deferred" } })),
    true,
  );
});

test("deferralPlan summarises what a spec defers", () => {
  const plan = deferralPlan(
    spec(
      [
        { componentId: "A", preview: "Alpha" },
        { componentId: "B", preview: "Beta", priority: "deferred" },
        {
          componentId: "C",
          preview: "Gamma",
          variants: [{ preview: "GammaOff", state: "off", priority: "deferred" }],
        },
      ],
      { modes: ["light", "dark"], modePriority: { light: "required", dark: "deferred" } },
    ),
  );
  assert.deepEqual(plan.modes, ["dark"]);
  assert.equal(plan.defersAnything, true);
  assert.equal(plan.entries, 1);
  assert.equal(plan.variants, 1);
  assert.deepEqual(plan.deferredPreviews, ["Beta", "GammaOff"]);
  assert.deepEqual(plan.renderFilter, ["Alpha", "Gamma"]);
});

test("previewForImage resolves a variant's sticker back to the variant's own @Preview", () => {
  const component = {
    componentId: "A",
    preview: "Alpha",
    variants: [
      { preview: "AlphaPressed", state: "pressed" },
      { preview: "AlphaDark", theme: "dark" },
      { preview: "AlphaIconLabel", props: { content: "icon+label" } },
      // No distinguishing axis: indistinguishable from the default, so never matched.
      { preview: "AlphaBare" },
    ],
  };
  assert.equal(previewForImage(component, { state: "default" }), "Alpha");
  assert.equal(previewForImage(component, { state: "pressed" }), "AlphaPressed");
  assert.equal(previewForImage(component, { theme: "dark" }), "AlphaDark");
  assert.equal(
    previewForImage(component, { props: { content: "icon+label" } }),
    "AlphaIconLabel",
  );
  // A theme the spec never folded in (a multipreview's own dark render) stays on the component.
  assert.equal(previewForImage({ componentId: "B", preview: "Beta" }, { theme: "dark" }), "Beta");
});
