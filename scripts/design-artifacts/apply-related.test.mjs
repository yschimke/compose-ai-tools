import { test } from "node:test";
import assert from "node:assert/strict";

import { applyRelated, relatedIndex } from "./apply-related.mjs";

const comp = (componentId, extra = {}) => ({
  componentId,
  images: [],
  ...extra,
});
const manifest = (components) => ({
  schema: "design-parity-catalog/v1",
  system: "s",
  components,
});
const specOf = (components) => ({ groups: [{ name: "Buttons", components }] });

test("stamps each spec component's related links onto the matching manifest component", () => {
  const spec = specOf([
    {
      componentId: "Button/Filled",
      related: [{ system: "m3-samples", label: "Samples" }],
    },
    {
      componentId: "Button/Outlined",
      related: [{ system: "m3-samples", componentId: "Button/Outlined" }],
    },
  ]);
  const m = manifest([comp("Button/Filled"), comp("Button/Outlined")]);

  assert.equal(applyRelated(m, spec), 2);
  assert.deepEqual(m.components[0].related, [
    { system: "m3-samples", label: "Samples" },
  ]);
  assert.deepEqual(m.components[1].related, [
    { system: "m3-samples", componentId: "Button/Outlined" },
  ]);
});

test("carries several links for one component — what a scalar `parallel` cannot express", () => {
  const spec = specOf([
    {
      componentId: "Button/Filled",
      related: [
        { system: "wear-m3-catalog", label: "Wear" },
        { system: "wear-m3-samples", label: "Wear samples" },
      ],
    },
  ]);
  const m = manifest([comp("Button/Filled")]);

  assert.equal(applyRelated(m, spec), 1);
  assert.equal(m.components[0].related.length, 2);
});

test("is a no-op for a catalog that declares no related links", () => {
  const spec = specOf([{ componentId: "Button/Filled" }]);
  const m = manifest([comp("Button/Filled")]);

  assert.equal(applyRelated(m, spec), 0);
  assert.equal("related" in m.components[0], false);
});

test("never clobbers an exporter that already carries the field", () => {
  const spec = specOf([
    { componentId: "Button/Filled", related: [{ system: "m3-samples" }] },
  ]);
  const m = manifest([
    comp("Button/Filled", { related: [{ system: "from-exporter" }] }),
  ]);

  assert.equal(applyRelated(m, spec), 0);
  assert.deepEqual(m.components[0].related, [{ system: "from-exporter" }]);
});

test("is idempotent — a second pass stamps nothing and changes nothing", () => {
  const spec = specOf([
    { componentId: "Button/Filled", related: [{ system: "m3-samples" }] },
  ]);
  const m = manifest([comp("Button/Filled")]);

  assert.equal(applyRelated(m, spec), 1);
  const after = JSON.stringify(m);
  assert.equal(applyRelated(m, spec), 0);
  assert.equal(JSON.stringify(m), after);
});

test("leaves a manifest component the spec does not mention alone", () => {
  const spec = specOf([
    { componentId: "Button/Filled", related: [{ system: "m3-samples" }] },
  ]);
  const m = manifest([comp("Button/Filled"), comp("Card/Elevated")]);

  assert.equal(applyRelated(m, spec), 1);
  assert.equal("related" in m.components[1], false);
});

test("drops a link that names no system rather than publishing half of one", () => {
  const spec = specOf([
    {
      componentId: "Button/Filled",
      related: [{ label: "Samples" }, { system: "  " }, { system: "m3-samples" }],
    },
  ]);
  const m = manifest([comp("Button/Filled")]);

  assert.equal(applyRelated(m, spec), 1);
  assert.deepEqual(m.components[0].related, [{ system: "m3-samples" }]);
});

test("publishes nothing when every declared link is unusable", () => {
  const spec = specOf([{ componentId: "Button/Filled", related: [{ label: "x" }] }]);
  const m = manifest([comp("Button/Filled")]);

  assert.equal(applyRelated(m, spec), 0);
  assert.equal("related" in m.components[0], false);
});

test("omits a blank componentId rather than emitting an id that matches nothing", () => {
  // An ABSENT componentId means "the same id as mine" — the id-parity case. An empty string would
  // read as a declared id, and resolve against no component in the other catalog.
  const spec = specOf([
    {
      componentId: "Button/Filled",
      related: [{ system: "m3-samples", componentId: "", label: "" }],
    },
  ]);
  const m = manifest([comp("Button/Filled")]);

  assert.equal(applyRelated(m, spec), 1);
  assert.deepEqual(m.components[0].related, [{ system: "m3-samples" }]);
});

test("trims the declared values", () => {
  const spec = specOf([
    {
      componentId: "Button/Filled",
      related: [
        { system: " m3-samples ", componentId: " Button/Filled ", label: " Samples " },
      ],
    },
  ]);
  const m = manifest([comp("Button/Filled")]);

  applyRelated(m, spec);
  assert.deepEqual(m.components[0].related, [
    { system: "m3-samples", componentId: "Button/Filled", label: "Samples" },
  ]);
});

test("ignores a `related` that is not an array (the spec validator is what reports it)", () => {
  const spec = specOf([{ componentId: "Button/Filled", related: "m3-samples" }]);
  const m = manifest([comp("Button/Filled")]);

  assert.equal(applyRelated(m, spec), 0);
  assert.equal("related" in m.components[0], false);
});

test("survives a manifest or spec with no components at all", () => {
  assert.equal(applyRelated({}, {}), 0);
  assert.equal(applyRelated(undefined, undefined), 0);
  assert.equal(relatedIndex(undefined).size, 0);
});

test("relatedIndex is keyed by componentId across groups, for the deferred stamp to read", () => {
  const spec = {
    groups: [
      {
        name: "Buttons",
        components: [
          { componentId: "Button/Filled", related: [{ system: "m3-samples" }] },
        ],
      },
      {
        name: "Cards",
        components: [
          { componentId: "Card/Elevated", related: [{ system: "m3-samples" }] },
          { componentId: "Card/Outlined" },
        ],
      },
    ],
  };

  const index = relatedIndex(spec);
  assert.deepEqual([...index.keys()], ["Button/Filled", "Card/Elevated"]);
});
