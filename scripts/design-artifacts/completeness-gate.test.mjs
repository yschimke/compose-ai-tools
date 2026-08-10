import { test } from "node:test";
import assert from "node:assert/strict";

import { completenessFailure } from "./completeness-gate.mjs";

const failure = (overrides = {}) =>
  completenessFailure({
    allowIncomplete: false,
    resolvedCount: 1,
    missingCount: 0,
    withoutSemanticsCount: 0,
    ...overrides,
  });

test("strict mode rejects an incomplete render", () => {
  assert.equal(failure({ missingCount: 1 }), "incomplete");
  assert.equal(failure({ withoutSemanticsCount: 1 }), "incomplete");
});

test("allow-incomplete permits a partial render", () => {
  assert.equal(
    failure({ allowIncomplete: true, resolvedCount: 1, missingCount: 2 }),
    null,
  );
});

test("allow-incomplete still rejects a total render miss", () => {
  assert.equal(
    failure({ allowIncomplete: true, resolvedCount: 0, missingCount: 3 }),
    "empty",
  );
});

test("a catalog with only declared non-rendered entries is not a total miss", () => {
  assert.equal(
    failure({ allowIncomplete: true, resolvedCount: 0, missingCount: 0 }),
    null,
  );
});
