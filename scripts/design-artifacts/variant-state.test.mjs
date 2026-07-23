import { test } from "node:test";
import assert from "node:assert/strict";

import { variantStateFromId } from "./variant-state.mjs";

test("variantStateFromId extracts the _VARIANT_<name> suffix", () => {
  assert.equal(variantStateFromId("SwitchButtonOn_VARIANT_off"), "off");
  assert.equal(variantStateFromId("FilledButton_VARIANT_disabled"), "disabled");
});

test("variantStateFromId returns null for an ordinary preview id", () => {
  assert.equal(variantStateFromId("SwitchButtonOn"), null);
  assert.equal(variantStateFromId("FilledButton_Light"), null);
  assert.equal(variantStateFromId("card-slots__ideal__default__light"), null);
});

test("variantStateFromId keeps the suffix after a multipreview segment", () => {
  // The tag is always the trailing suffix (`id = base.id + tag`), even when the base id already
  // ends in a `_Light` / `_Dark` multipreview segment.
  assert.equal(variantStateFromId("FilledButton_Dark_VARIANT_off"), "off");
});

test("variantStateFromId tolerates non-string input", () => {
  assert.equal(variantStateFromId(undefined), null);
  assert.equal(variantStateFromId(null), null);
  assert.equal(variantStateFromId(42), null);
});
