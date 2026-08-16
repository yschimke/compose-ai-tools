import assert from "node:assert/strict";
import test from "node:test";

import {
  additionalBundleLiveConflict,
  claimedComponentIds,
  claimedPreviewFunctions,
  combinedBundleMap,
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

test("fallback inventory rejects a generated component ID already owned by authored inventory", () => {
  const records = namespaceModuleRecords(record(":feature", ["Foo"]));
  assert.throws(
    () => generatedFallbackGroups(records, new Set(), new Set(["feature/Foo"])),
    /generated fallback componentId 'feature\/Foo' collides/,
  );
});

test("claimed component IDs include authored and annotation-derived inventory", () => {
  assert.deepEqual(
    claimedComponentIds([
      { components: [{ componentId: "one" }, { componentId: "two" }] },
      { components: [{ componentId: "three" }] },
    ]),
    new Set(["one", "two", "three"]),
  );
});

test("combined bundle metadata uses later-bundle precedence", () => {
  const bundles = [
    { metadata: new Map([["Shared", "primary"]]) },
    {
      metadata: new Map([
        ["Shared", "extra"],
        ["Only", "additional"],
      ]),
    },
  ];
  assert.deepEqual(
    combinedBundleMap(bundles, (bundle) => bundle.metadata),
    new Map([
      ["Shared", "extra"],
      ["Only", "additional"],
    ]),
  );
});

test("additional renders reject primary-only live publication modes", () => {
  assert.deepEqual(
    additionalBundleLiveConflict({
      "additional-renders": ["feature.png"],
      "publish-live-bundle": true,
      "source-module": ":app",
    }),
    ["--publish-live-bundle", "--source-module"],
  );
  assert.equal(additionalBundleLiveConflict({ "additional-renders": ["feature.png"] }), null);
  assert.equal(additionalBundleLiveConflict({ "publish-live-bundle": true }), null);
});
