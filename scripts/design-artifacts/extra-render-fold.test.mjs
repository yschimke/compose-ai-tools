import { test } from "node:test";
import assert from "node:assert/strict";

import {
  extraOnlyFunctions,
  unbridgeableFunctions,
} from "./extra-render-fold.mjs";

const PRIMARY = ["FilledButton", "Checkbox", "ThemeFoundation"];
const EXTRA = ["FilledButton", "DeviceBody", "ContactChat"];

test("without a live lane the whole supplement stays unbridged", () => {
  // The behaviour every catalog published before --extra-live-bundle was generated under: the
  // supplement is pixels-only, so none of its functions may claim a daemon twin.
  assert.deepEqual([...unbridgeableFunctions(PRIMARY, EXTRA, false)].sort(), [
    "ContactChat",
    "DeviceBody",
    "FilledButton",
  ]);
});

test("with a live lane only the true overrides stay unbridged", () => {
  // `FilledButton` is in both bundles: the supplement replaced the primary's pixels, and the
  // catalog's monolithic daemon runs the primary — so a live render would contradict the sticker.
  assert.deepEqual(
    [...unbridgeableFunctions(PRIMARY, EXTRA, true)],
    ["FilledButton"],
  );
});

test("a supplement that only adds keeps every function live", () => {
  // meshcore-mobile's shape: `:meshcore-components` screens that `:app` doesn't carry at all.
  // Nothing was overridden, so nothing has to lose its live lane.
  assert.deepEqual(
    [...unbridgeableFunctions(PRIMARY, ["DeviceBody", "ContactChat"], true)],
    [],
  );
});

test("a supplement that only overrides gains nothing", () => {
  // The original design-catalog-m3 case — every extra function replaces a primary one. Turning the
  // flag on must not quietly hand those a live lane.
  const extra = ["FilledButton", "Checkbox"];
  assert.deepEqual([...unbridgeableFunctions(PRIMARY, extra, true)].sort(), [
    "Checkbox",
    "FilledButton",
  ]);
  assert.deepEqual(extraOnlyFunctions(PRIMARY, extra), []);
});

test("additions are reported separately from overrides", () => {
  assert.deepEqual(extraOnlyFunctions(PRIMARY, EXTRA), [
    "DeviceBody",
    "ContactChat",
  ]);
});

test("a duplicated function name is counted once", () => {
  // `extra.map(functionOf)` yields one entry per RENDER, so a function with several previews
  // (light/dark, per-size) appears repeatedly. The counts in the render log must not multiply.
  const extra = ["DeviceBody", "DeviceBody", "DeviceBody", "FilledButton"];
  assert.deepEqual(extraOnlyFunctions(PRIMARY, extra), ["DeviceBody"]);
  assert.deepEqual(
    [...unbridgeableFunctions(PRIMARY, extra, true)],
    ["FilledButton"],
  );
});
