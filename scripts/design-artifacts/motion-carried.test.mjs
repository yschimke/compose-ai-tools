import { test } from "node:test";
import assert from "node:assert/strict";

import { checkMotionCarried } from "./motion-carried.mjs";

const comp = (componentId, motion) => ({
  componentId,
  images: [],
  ...(motion ? { motion } : {}),
});
const manifest = (components) => ({ schema: "design-parity-catalog/v1", system: "s", components });
const capture = (path, theme) => ({ path, kind: "interaction", ...(theme ? { theme } : {}) });

test("reports the axis as carried when the export kept it", () => {
  const light = capture("motion/switch-on/ideal__default__light.apng", "light");
  const dark = capture("motion/switch-on/ideal__default__dark.apng", "dark");
  const m = manifest([comp("Switch/On", [light, dark]), comp("Chip/Assist")]);

  const result = checkMotionCarried(m, new Map([["Switch/On", [light, dark]]]));

  assert.deepEqual(result, { declared: 1, carried: 1, captures: 2, dropped: [] });
});

test("names every component whose captures the export dropped", () => {
  // What every run looked like before the pin carried `motion`: the join resolved captures, the
  // written manifest has none of them, and nothing downstream had anything to say about it.
  const m = manifest([comp("Switch/On"), comp("NavigationBar/Short"), comp("Chip/Assist")]);
  const joined = new Map([
    ["Switch/On", [capture("previews/SwitchOn_Light.apng", "light")]],
    ["NavigationBar/Short", [capture("previews/ShortBar_Light.apng", "light")]],
  ]);

  const result = checkMotionCarried(m, joined);

  assert.equal(result.declared, 2);
  assert.equal(result.carried, 0);
  assert.deepEqual(result.dropped, ["Switch/On", "NavigationBar/Short"]);
});

test("reports a partial drop rather than rounding it to all-or-nothing", () => {
  const kept = capture("motion/switch-on/ideal__default__light.apng", "light");
  const m = manifest([comp("Switch/On", [kept]), comp("NavigationBar/Short")]);
  const joined = new Map([
    ["Switch/On", [kept]],
    ["NavigationBar/Short", [capture("previews/ShortBar_Light.apng", "light")]],
  ]);

  const result = checkMotionCarried(m, joined);

  assert.equal(result.carried, 1);
  assert.deepEqual(result.dropped, ["NavigationBar/Short"]);
});

test("flags a component the join resolved captures for that never reached the manifest", () => {
  const m = manifest([comp("Switch/On", [capture("motion/switch-on/x.apng", "light")])]);
  const joined = new Map([
    ["Switch/On", [capture("motion/switch-on/x.apng", "light")]],
    ["Ghost/Component", [capture("previews/Ghost_Light.apng", "light")]],
  ]);

  assert.deepEqual(checkMotionCarried(m, joined).dropped, ["Ghost/Component"]);
});

test("a catalog that declares no motion has nothing to check", () => {
  const m = manifest([comp("Chip/Assist")]);
  assert.deepEqual(checkMotionCarried(m, new Map()), {
    declared: 0,
    carried: 0,
    captures: 0,
    dropped: [],
  });
});

test("an empty capture list is not a declaration, so it cannot be dropped", () => {
  const m = manifest([comp("Switch/On")]);
  const result = checkMotionCarried(m, new Map([["Switch/On", []]]));
  assert.deepEqual(result, { declared: 0, carried: 0, captures: 0, dropped: [] });
});

test("never mutates the manifest", () => {
  const m = manifest([comp("Switch/On")]);
  checkMotionCarried(m, new Map([["Switch/On", [capture("previews/x.apng", "light")]]]));
  assert.equal("motion" in m.components[0], false);
});

test("tolerates a missing manifest / missing map without throwing", () => {
  assert.equal(checkMotionCarried({}, new Map()).declared, 0);
  assert.equal(checkMotionCarried({ components: [comp("X")] }, undefined).declared, 0);
});
