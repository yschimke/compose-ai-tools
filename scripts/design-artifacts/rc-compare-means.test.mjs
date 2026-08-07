/**
 * The bug these pin: scoping an optional lane's means to the *JS* lane's scored rows. The lanes are
 * independent, so a document the JS player fails and the embedded player renders must still count
 * toward the embedded lane — otherwise its split disagrees with the `meanMismatchPct` and `scored`
 * count printed next to it.
 *
 * Run with `node --test scripts/design-artifacts/*.test.mjs`.
 */
import assert from "node:assert/strict";
import { test } from "node:test";

import { laneRows, laneSplit, meanOf } from "./rc-compare-means.mjs";

/** Two rows: the JS player only managed the first, the embedded player managed both. */
const rows = [
  {
    rendered: true,
    referenceBlank: false,
    embeddedRendered: true,
    embeddedCoverageDeltaPct: 2,
    embeddedContentMismatchPct: 10,
  },
  {
    rendered: false, // JS player choked on this document…
    referenceBlank: false,
    embeddedRendered: true, // …the embedded player did not.
    embeddedCoverageDeltaPct: 8,
    embeddedContentMismatchPct: 30,
  },
];

test("a lane's means include rows the JS player failed", () => {
  assert.equal(laneRows(rows, "embedded").length, 2);
  const split = laneSplit(rows, "embedded");
  assert.equal(split.meanCoverageDeltaPct, 5, "mean of 2 and 8, not just the JS-rendered 2");
  assert.equal(split.meanContentMismatchPct, 20, "mean of 10 and 30");
});

test("a lane's means exclude rows that lane did not render", () => {
  const mixed = [...rows, { rendered: true, referenceBlank: false, embeddedRendered: false }];
  assert.equal(laneRows(mixed, "embedded").length, 2, "the un-rendered row is not scored");
  assert.equal(laneSplit(mixed, "embedded").meanCoverageDeltaPct, 5);
});

test("a blank reference is unscorable even when the lane rendered it", () => {
  const withBlank = [
    ...rows,
    {
      rendered: true,
      referenceBlank: true,
      embeddedRendered: true,
      embeddedCoverageDeltaPct: 99,
      embeddedContentMismatchPct: 99,
    },
  ];
  assert.equal(laneRows(withBlank, "embedded").length, 2);
  assert.equal(laneSplit(withBlank, "embedded").meanCoverageDeltaPct, 5, "the blank row is ignored");
});

test("a lane that rendered nothing scorable reports null, not zero", () => {
  const none = [{ rendered: true, referenceBlank: false, cmpWasmRendered: false }];
  assert.deepEqual(laneSplit(none, "cmpWasm"), {
    meanCoverageDeltaPct: null,
    meanContentMismatchPct: null,
  });
});

test("meanOf ignores nulls rather than counting them as zero", () => {
  assert.equal(meanOf([{ v: 4 }, { v: null }, { v: 8 }], (r) => r.v), 6);
  assert.equal(meanOf([{ v: null }], (r) => r.v), null);
});
