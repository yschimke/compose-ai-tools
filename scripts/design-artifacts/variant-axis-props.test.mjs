import { test } from "node:test";
import assert from "node:assert/strict";

import { applyVariantAxisProps, overridesByPreviewId } from "./variant-axis-props.mjs";

const axisSpec = (name, props) => ({
  name,
  seeds: [],
  props: Object.entries(props).map(([key, value]) => ({ key, value })),
});

test("an axis cell publishes its assignment as props", () => {
  const candidates = [
    { previewId: "pkg.Kt.FilledButton_Light_VARIANT_xs-square", images: [{ theme: "light" }] },
  ];
  const overrides = new Map([
    [
      "pkg.Kt.FilledButton_Light_VARIANT_xs-square",
      axisSpec("xs-square", { size: "xs", shape: "square" }),
    ],
  ]);

  const { stamped, claimed } = applyVariantAxisProps(candidates, overrides);

  assert.equal(stamped, 1);
  assert.deepEqual(candidates[0].images[0].props, { size: "xs", shape: "square" });
  assert.equal(claimed.has(candidates[0].images[0]), true);
});

test("a hand-written variant carries no props and is left for the state pass", () => {
  // Every pre-existing `@OverrideVariant` looks like this: a spec with seeds but no axis
  // assignment. It must come out of here untouched so its `_VARIANT_<name>` still becomes a state.
  const candidates = [{ previewId: "pkg.Kt.SwitchOn_VARIANT_off", images: [{}] }];
  const overrides = new Map([
    ["pkg.Kt.SwitchOn_VARIANT_off", { name: "off", seeds: [{ key: "checked" }] }],
  ]);

  const { stamped, claimed } = applyVariantAxisProps(candidates, overrides);

  assert.equal(stamped, 0);
  assert.equal(claimed.size, 0);
  assert.equal(candidates[0].images[0].props, undefined);
});

test("existing props win over the axis assignment rather than being clobbered", () => {
  // A `fontScale` promoted from preview params, or a spec-authored prop, is a different axis.
  const candidates = [
    { previewId: "p_VARIANT_xs", images: [{ props: { fontScale: "2.0", size: "kept" } }] },
  ];
  const overrides = new Map([["p_VARIANT_xs", axisSpec("xs", { size: "xs", shape: "round" })]]);

  applyVariantAxisProps(candidates, overrides);

  assert.deepEqual(candidates[0].images[0].props, {
    shape: "round",
    fontScale: "2.0",
    size: "kept",
  });
});

test("every image of a multi-theme cell is stamped", () => {
  const candidates = [
    { previewId: "p_VARIANT_xs", images: [{ theme: "light" }, { theme: "dark" }] },
  ];
  const overrides = new Map([["p_VARIANT_xs", axisSpec("xs", { size: "xs" })]]);

  const { stamped } = applyVariantAxisProps(candidates, overrides);

  assert.equal(stamped, 2);
  assert.deepEqual(candidates[0].images[1].props, { size: "xs" });
});

test("no overrides at all is a no-op", () => {
  const candidates = [{ previewId: "p", images: [{}] }];
  const { stamped, claimed } = applyVariantAxisProps(candidates, new Map());
  assert.equal(stamped, 0);
  assert.equal(claimed.size, 0);
  assert.equal(candidates[0].images[0].props, undefined);
});

test("overridesByPreviewId indexes only previews that carry a spec", () => {
  const bundle = {
    previews: [
      { id: "a", overrides: { name: "x", seeds: [] } },
      { id: "b" },
      { id: "c", overrides: { name: "y", seeds: [] } },
    ],
  };
  const map = overridesByPreviewId(bundle);
  assert.deepEqual([...map.keys()], ["a", "c"]);
});
