import { test } from "node:test";
import assert from "node:assert/strict";

import { applyMotion } from "./apply-motion.mjs";

const comp = (componentId, extra = {}) => ({ componentId, images: [], ...extra });
const manifest = (components) => ({ schema: "design-parity-catalog/v1", system: "s", components });
const capture = (path, theme) => ({
  path,
  kind: "interaction",
  caption: "Toggle quickly.",
  ...(theme ? { theme } : {}),
});

test("stamps the joined captures onto the matching manifest components", () => {
  const m = manifest([comp("Switch/On"), comp("NavigationBar/Short"), comp("Chip/Assist")]);
  const joined = new Map([
    ["Switch/On", [capture("previews/SwitchOn_Light-c168.apng", "light"), capture("previews/SwitchOn_Dark-d0f2.apng", "dark")]],
    ["NavigationBar/Short", [capture("previews/ShortBar_Light-aa11.apng", "light")]],
  ]);

  const result = applyMotion(m, joined);

  assert.equal(result.stamped, 2);
  assert.equal(result.captures, 3);
  assert.deepEqual(result.unmatched, []);
  assert.equal(m.components[0].motion.length, 2);
  assert.equal(m.components[0].motion[1].theme, "dark");
  assert.equal(m.components[1].motion.length, 1);
  assert.equal("motion" in m.components[2], false);
});

test("is a no-op for a catalog that declares no interaction captures", () => {
  const m = manifest([comp("Chip/Assist")]);

  const result = applyMotion(m, new Map());

  assert.deepEqual(result, { stamped: 0, captures: 0, unmatched: [] });
  assert.equal("motion" in m.components[0], false);
});

test("never clobbers a motion axis the component already carries", () => {
  // What a future catalog-export that propagates `motion` itself would leave behind.
  const own = [capture("motion/switch-on/ideal__default__light.apng", "light")];
  const m = manifest([comp("Switch/On", { motion: own })]);

  const result = applyMotion(m, new Map([["Switch/On", [capture("previews/SwitchOn_Light.apng", "light")]]]));

  assert.equal(result.stamped, 0);
  assert.equal(m.components[0].motion, own);
});

test("is idempotent — a second pass stamps nothing", () => {
  const m = manifest([comp("Switch/On")]);
  const joined = new Map([["Switch/On", [capture("previews/SwitchOn_Light.apng", "light")]]]);

  assert.equal(applyMotion(m, joined).stamped, 1);
  assert.equal(applyMotion(m, joined).stamped, 0);
  assert.equal(m.components[0].motion.length, 1);
});

test("reports captures whose component never reached the manifest", () => {
  const m = manifest([comp("Switch/On")]);
  const joined = new Map([
    ["Switch/On", [capture("previews/SwitchOn_Light.apng", "light")]],
    ["Ghost/Component", [capture("previews/Ghost_Light.apng", "light")]],
  ]);

  const result = applyMotion(m, joined);

  assert.equal(result.stamped, 1);
  assert.deepEqual(result.unmatched, ["Ghost/Component"]);
});

test("an empty capture list stamps no field at all, and is not reported unmatched", () => {
  const m = manifest([comp("Switch/On")]);

  const result = applyMotion(m, new Map([["Switch/On", []], ["Absent/Component", []]]));

  assert.deepEqual(result, { stamped: 0, captures: 0, unmatched: [] });
  assert.equal("motion" in m.components[0], false);
});

test("tolerates a missing manifest / missing map without throwing", () => {
  assert.deepEqual(applyMotion({}, new Map()), { stamped: 0, captures: 0, unmatched: [] });
  assert.deepEqual(applyMotion({ components: [comp("X")] }, undefined), {
    stamped: 0,
    captures: 0,
    unmatched: [],
  });
});
