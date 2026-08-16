import assert from "node:assert/strict";
import test from "node:test";
import { previewModules } from "./preview-modules.mjs";

test("preview modules are unique and sorted", () => {
  assert.deepEqual(
    previewModules({ previews: [{ module: ":z" }, { module: ":a" }, { module: ":z" }] }),
    [":a", ":z"],
  );
});

test("preferred spec module is first when present", () => {
  assert.deepEqual(
    previewModules({ previews: [{ module: ":feature" }, { module: ":catalog" }] }, ":catalog"),
    [":catalog", ":feature"],
  );
});

test("preferred spec module matches discovery without a leading colon", () => {
  assert.deepEqual(
    previewModules({ previews: [{ module: "feature" }, { module: "catalog" }] }, ":catalog"),
    ["catalog", "feature"],
  );
});
