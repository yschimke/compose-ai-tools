import assert from "node:assert/strict";
import test from "node:test";

import {
  claimedPreviewFunctions,
  combinedBundleEntries,
  generatedFallbackGroups,
  namespaceModuleRecords,
} from "./multi-module-catalog.mjs";

const record = (module, functions) => ({
  bundle: {
    manifest: { modulePath: module },
    previews: functions.map((fn) => ({
      id: `${module}.${fn}`,
      functionName: fn,
      params: { group: fn === "Grouped" ? "Controls" : null, name: `${fn} caption` },
    })),
    entries: { [`${module}.entry`]: module },
  },
  candidates: functions.map((fn) => ({ componentId: fn, images: [{ path: `${fn}.png` }] })),
});

test("additional modules are sorted and duplicate functions are deterministically namespaced", () => {
  const [primary, a, z] = namespaceModuleRecords(
    record(":catalog", ["Shared", "CatalogOnly"]),
    [record(":z", ["Shared"]), record(":a", ["Shared", "AOnly"])],
  );
  assert.equal(primary.module, ":catalog");
  assert.equal(a.module, ":a");
  assert.equal(z.module, ":z");
  assert.deepEqual(primary.candidates.map((it) => it.functionName), ["Shared", "CatalogOnly"]);
  assert.deepEqual(a.candidates.map((it) => it.functionName), [":a::Shared", "AOnly"]);
  assert.deepEqual(z.candidates.map((it) => it.functionName), [":z::Shared"]);
});

test("fallback inventory groups by Gradle module and preview group and skips curated previews", () => {
  const records = namespaceModuleRecords(
    record(":catalog", ["Curated"]),
    [record(":feature", ["Grouped", "Plain"])],
  );
  const claimed = claimedPreviewFunctions([
    { name: "Authored", components: [{ componentId: "curated", preview: "Curated" }] },
  ]);
  assert.deepEqual(generatedFallbackGroups(records, claimed), [
    {
      name: "Controls",
      section: ":feature",
      components: [
        {
          componentId: "feature/Grouped",
          preview: "Grouped",
          caption: "Grouped caption",
        },
      ],
    },
    {
      name: "Previews",
      section: ":feature",
      components: [
        { componentId: "feature/Plain", preview: "Plain", caption: "Plain caption" },
      ],
    },
  ]);
});

test("combined entries keep primary bytes on collisions", () => {
  assert.deepEqual(
    combinedBundleEntries([
      { entries: { same: "primary", a: "a" } },
      { entries: { same: "additional", b: "b" } },
    ]),
    { same: "primary", a: "a", b: "b" },
  );
});
