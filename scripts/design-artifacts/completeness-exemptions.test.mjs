import { test } from "node:test";
import assert from "node:assert/strict";

import {
  exemptSemanticsPatterns,
  matchesExemption,
  partitionExemptSemantics,
} from "./completeness-exemptions.mjs";

test("a spec with no completeness block exempts nothing", () => {
  assert.deepEqual(exemptSemanticsPatterns(undefined), []);
  assert.deepEqual(exemptSemanticsPatterns({}), []);
  assert.deepEqual(exemptSemanticsPatterns({ completeness: {} }), []);
});

test("declared patterns are cleaned up, deduped, and kept in spec order", () => {
  assert.deepEqual(
    exemptSemanticsPatterns({
      completeness: {
        exemptSemantics: ["*/MainActivity", " app/getting-started ", "*/MainActivity", "", 7, null],
      },
    }),
    ["*/MainActivity", "app/getting-started"],
  );
});

test("a malformed completeness block exempts nothing rather than everything", () => {
  // Shape is the validator's business; here the safe direction is a strict gate.
  assert.deepEqual(exemptSemanticsPatterns({ completeness: [1, 2] }), []);
  assert.deepEqual(exemptSemanticsPatterns({ completeness: { exemptSemantics: "*" } }), []);
});

test("patterns are anchored, so a bare name is not a substring match", () => {
  assert.equal(matchesExemption("app/MainActivity", "app/MainActivity"), true);
  assert.equal(matchesExemption("MainActivity", "app/MainActivity"), false);
  assert.equal(matchesExemption("Activity", "app/MainActivity"), false);
  assert.equal(matchesExemption("app/Main", "app/MainActivity"), false);
});

test("`*` spans path separators, so one pattern reaches a nested module", () => {
  assert.equal(matchesExemption("*/MainActivity", "app/MainActivity"), true);
  assert.equal(matchesExemption("*/MainActivity", "feature/home/MainActivity"), true);
  assert.equal(matchesExemption("*Activity", "wear/WearMainActivity"), true);
  assert.equal(matchesExemption("*/*Activity", "wear/LicenseActivity"), true);
  assert.equal(matchesExemption("*", "anything/at/all"), true);
});

test("regex metacharacters in a pattern are literal", () => {
  assert.equal(matchesExemption("app/Getting.Started", "app/GettingXStarted"), false);
  assert.equal(matchesExemption("app/Getting.Started", "app/Getting.Started"), true);
  assert.equal(matchesExemption("app/Button+", "app/Button+"), true);
  assert.equal(matchesExemption("app/Button+", "app/Buttonn"), false);
});

test("non-string inputs never match", () => {
  assert.equal(matchesExemption(undefined, "app/MainActivity"), false);
  assert.equal(matchesExemption("*", undefined), false);
});

test("the partition excuses only the matched ids, keeping input order", () => {
  const { counted, exempt, unusedPatterns } = partitionExemptSemantics(
    ["app/MainActivity", "Buttons/Filled", "wear/WearMainActivity", "app/getting-started"],
    ["*/MainActivity", "*Activity", "app/getting-started"],
  );
  assert.deepEqual(counted, ["Buttons/Filled"]);
  assert.deepEqual(exempt, ["app/MainActivity", "wear/WearMainActivity", "app/getting-started"]);
  assert.deepEqual(unusedPatterns, []);
});

test("no patterns leaves the gate exactly as strict as before", () => {
  const withoutSemantics = ["app/MainActivity", "Buttons/Filled"];
  const { counted, exempt, unusedPatterns } = partitionExemptSemantics(withoutSemantics, []);
  assert.deepEqual(counted, withoutSemantics);
  assert.deepEqual(exempt, []);
  assert.deepEqual(unusedPatterns, []);
});

test("a pattern that matches nothing is reported as drift", () => {
  const { counted, exempt, unusedPatterns } = partitionExemptSemantics(
    ["Buttons/Filled"],
    ["*/MainActivity", "app/getting-started"],
  );
  assert.deepEqual(counted, ["Buttons/Filled"]);
  assert.deepEqual(exempt, []);
  assert.deepEqual(unusedPatterns, ["*/MainActivity", "app/getting-started"]);
});

test("an exemption is not drift merely because a broader pattern also covers the id", () => {
  // Both patterns matched `app/MainActivity`, so neither is reported as stale.
  const { unusedPatterns } = partitionExemptSemantics(
    ["app/MainActivity"],
    ["*Activity", "app/MainActivity"],
  );
  assert.deepEqual(unusedPatterns, []);
});

test("an empty withoutSemantics list makes every declared pattern drift", () => {
  const { counted, exempt, unusedPatterns } = partitionExemptSemantics([], ["*Activity"]);
  assert.deepEqual(counted, []);
  assert.deepEqual(exempt, []);
  assert.deepEqual(unusedPatterns, ["*Activity"]);
});
