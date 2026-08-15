import { test } from "node:test";
import assert from "node:assert/strict";

import {
  DEFERRED,
  REQUIRED,
  deferralPlan,
  deferredModes,
  entryPriority,
  variantPriority,
  isImageDeferred,
  modePriority,
  previewForImage,
  previewNamesByPriority,
  renderFilterPatterns,
  defersEveryPreview,
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

test("variantPriority inherits a deferred component, so a mixed component can't leak coverage", () => {
  const deferredComponent = { componentId: "A", preview: "Alpha", priority: "deferred" };
  const requiredComponent = { componentId: "B", preview: "Beta" };
  // A variant left at the `required` default under a deferred component is deferred WITH it. The
  // driver short-circuits a deferred component before folding variants, so treating this one as
  // required would render it and then neither bake it nor record it anywhere.
  assert.equal(variantPriority(deferredComponent, { preview: "AlphaOff", state: "off" }), DEFERRED);
  assert.equal(
    variantPriority(deferredComponent, { preview: "AlphaOn", state: "on", priority: "required" }),
    DEFERRED,
    "an explicit `required` under a deferred component does not resurrect it",
  );
  // The useful direction still works: a required component with one deferred variant.
  assert.equal(variantPriority(requiredComponent, { preview: "BetaOff", state: "off" }), REQUIRED);
  assert.equal(
    variantPriority(requiredComponent, { preview: "BetaOff", state: "off", priority: "deferred" }),
    DEFERRED,
  );
});

test("a deferred component's variants stay out of the render filter", () => {
  // The invariant: what the filter renders and what the driver bakes must agree. A variant of a
  // deferred component is baked by nothing, so its function must not be kept alive here either.
  const s = spec([
    { componentId: "A", preview: "Alpha" },
    {
      componentId: "B",
      preview: "Beta",
      priority: "deferred",
      variants: [{ preview: "BetaOff", state: "off" }],
    },
  ]);
  const { required, deferred } = previewNamesByPriority(s);
  assert.deepEqual(required, ["Alpha"]);
  assert.deepEqual(deferred, ["Beta", "BetaOff"]);
  assert.deepEqual(renderFilterPatterns(s), ["Alpha"]);
  // splitDeferredVariants agrees, for a caller that reaches it with a deferred component.
  const { deferredVariants } = splitDeferredVariants(s.groups[0].components[1]);
  assert.deepEqual(
    deferredVariants.map((v) => v.preview),
    ["BetaOff"],
  );
  // And the plan counts it, so the pre-flight reports the variant rather than only the entry.
  const plan = deferralPlan(s);
  assert.equal(plan.entries, 1);
  assert.equal(plan.variants, 1);
});

test("previewNamesByPriority only defers a function nothing required points at", () => {
  const { required, deferred } = previewNamesByPriority(
    spec([
      { componentId: "A", preview: "Alpha", motionPreview: "AlphaMotion" },
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
  assert.deepEqual(required, ["Alpha", "AlphaMotion", "Delta", "DeltaPressed"]);
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

test("defersEveryPreview flags an all-deferred catalog and nothing else (#2993)", () => {
  // The degenerate case: every entry deferred, no required preview left. This is the empty filter
  // that both workflows would read as render-all.
  assert.equal(
    defersEveryPreview(spec([{ componentId: "A", preview: "Alpha", priority: "deferred" }])),
    true,
  );
  // A variant of a deferred component inherits deferral, so an all-deferred-with-variants catalog is
  // caught too (the case #2991 widened).
  assert.equal(
    defersEveryPreview(
      spec([
        {
          componentId: "A",
          preview: "Alpha",
          priority: "deferred",
          variants: [{ preview: "AlphaOff", state: "off" }],
        },
      ]),
    ),
    true,
  );
  // One required entry left over is enough — the filter is a real, non-empty render set.
  assert.equal(
    defersEveryPreview(
      spec([
        { componentId: "A", preview: "Alpha" },
        { componentId: "B", preview: "Beta", priority: "deferred" },
      ]),
    ),
    false,
  );
  // A shared function required by another entry keeps the catalog non-empty.
  assert.equal(
    defersEveryPreview(
      spec([
        { componentId: "A", preview: "Alpha", priority: "deferred" },
        { componentId: "B", preview: "Alpha" },
      ]),
    ),
    false,
  );
  // Deferring nothing is the ordinary render-all case, not the all-deferred defect.
  assert.equal(defersEveryPreview(spec([{ componentId: "A", preview: "Alpha" }])), false);
  // Mode-only deferral never empties the preview list, so it can't trip this.
  assert.equal(
    defersEveryPreview(
      spec([{ componentId: "A", preview: "Alpha" }], {
        modes: ["light", "dark"],
        modePriority: { "*": "deferred" },
      }),
    ),
    false,
  );
  // An empty catalog defers nothing.
  assert.equal(defersEveryPreview(spec([])), false);
  // Tolerant of malformed structure — a non-array `groups` / `components` can't throw here; the shape
  // error is validateSpec's job to report (#2993).
  assert.doesNotThrow(() => defersEveryPreview({ groups: {} }));
  assert.equal(defersEveryPreview({ groups: {} }), false);
  assert.equal(defersEveryPreview({ groups: [{ name: "G", components: {} }] }), false);
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
