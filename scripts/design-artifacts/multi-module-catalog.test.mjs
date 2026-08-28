import assert from "node:assert/strict";
import test from "node:test";

import {
  additionalBundleLiveConflict,
  claimedComponentIds,
  claimedPreviewFunctions,
  combinedBundleMap,
  combinedBundleEntries,
  generatedFallbackGroups,
  moduleArtifactKey,
  namespaceModuleRecords,
} from "./multi-module-catalog.mjs";

test("module artifact keys are stable and filesystem safe", () => {
  assert.equal(moduleArtifactKey(":tv"), "module_3a7476");
  assert.match(moduleArtifactKey(":feature:home"), /^module_[0-9a-f]+$/);
});

const record = (module, functions) => ({
  bundle: {
    manifest: { modulePath: module },
    previews: functions.map((fn) => ({
      id: `${module}.${fn}`,
      functionName: fn,
      params: {
        group: fn === "Grouped" ? "Controls" : null,
        name: `${fn} caption`,
      },
    })),
    entries: { [`${module}.entry`]: module },
  },
  candidates: functions.map((fn) => ({
    componentId: fn,
    images: [{ path: `${fn}.png` }],
  })),
});

test("additional modules are sorted and duplicate functions are deterministically namespaced", () => {
  const [primary, a, z] = namespaceModuleRecords(
    record(":catalog", ["Shared", "CatalogOnly"]),
    [record(":z", ["Shared"]), record(":a", ["Shared", "AOnly"])],
  );
  assert.equal(primary.module, ":catalog");
  assert.equal(a.module, ":a");
  assert.equal(z.module, ":z");
  assert.deepEqual(
    primary.candidates.map((it) => it.functionName),
    ["Shared", "CatalogOnly"],
  );
  assert.deepEqual(
    a.candidates.map((it) => it.functionName),
    [":a::Shared", "AOnly"],
  );
  assert.deepEqual(
    z.candidates.map((it) => it.functionName),
    [":z::Shared"],
  );
});

test("additional modules namespace equal preview ids and every keyed sidecar", () => {
  const bytes = (value) => new TextEncoder().encode(value);
  const collisionRecord = (module) => ({
    bundle: {
      manifest: {
        modulePath: module,
        previewIds: ["pkg.ScreenPreview"],
        rawPreviewIds: ["pkg.ScreenPreview"],
      },
      previews: [
        {
          id: "pkg.ScreenPreview",
          functionName: "ScreenPreview",
          captures: [{ renderOutput: "previews/ScreenPreview-deadbeef.gif" }],
        },
      ],
      entries: {
        "previews/pkg.ScreenPreview.png": bytes(module),
        "previews/pkg.ScreenPreview.semantics.json": bytes(module),
        "previews/pkg.ScreenPreview.figma-raster/node.png": bytes(module),
        "previews/ScreenPreview-deadbeef.gif": bytes(`motion:${module}`),
        "previews.json": bytes(
          JSON.stringify({
            previews: [
              {
                id: "pkg.ScreenPreview",
                functionName: "ScreenPreview",
                targets: [],
              },
            ],
          }),
        ),
      },
    },
    candidates: [
      {
        componentId: "ScreenPreview",
        previewId: "pkg.ScreenPreview",
        images: [{ path: "ScreenPreview.png", previewId: "pkg.ScreenPreview" }],
      },
    ],
  });

  const [primary, additional] = namespaceModuleRecords(
    collisionRecord(":app"),
    [collisionRecord(":feature")],
  );
  const additionalId = additional.bundle.previews[0].id;
  assert.equal(primary.bundle.previews[0].id, "pkg.ScreenPreview");
  assert.notEqual(additionalId, "pkg.ScreenPreview");
  assert.equal(additional.candidates[0].previewId, additionalId);
  assert.equal(additional.candidates[0].images[0].previewId, additionalId);
  const additionalMotion =
    additional.bundle.previews[0].captures[0].renderOutput;
  assert.notEqual(additionalMotion, "previews/ScreenPreview-deadbeef.gif");
  assert.ok(additional.bundle.entries[additionalMotion]);
  assert.ok(additional.bundle.entries[`previews/${additionalId}.png`]);
  assert.ok(
    additional.bundle.entries[`previews/${additionalId}.semantics.json`],
  );
  assert.ok(
    additional.bundle.entries[`previews/${additionalId}.figma-raster/node.png`],
  );
  assert.deepEqual(
    Object.keys(
      combinedBundleEntries([primary.bundle, additional.bundle]),
    ).filter((path) => path.endsWith(".semantics.json")),
    [
      "previews/pkg.ScreenPreview.semantics.json",
      `previews/${additionalId}.semantics.json`,
    ],
  );
  const raw = JSON.parse(
    new TextDecoder().decode(additional.bundle.entries["previews.json"]),
  );
  assert.equal(raw.previews[0].id, additionalId);
  assert.equal(raw.previews[0].functionName, ":feature::ScreenPreview");
});

test("fallback inventory groups by Gradle module and preview group and skips curated previews", () => {
  const records = namespaceModuleRecords(record(":catalog", ["Curated"]), [
    record(":feature", ["Grouped", "Plain"]),
  ]);
  const claimed = claimedPreviewFunctions([
    {
      name: "Authored",
      components: [{ componentId: "curated", preview: "Curated" }],
    },
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
        {
          componentId: "feature/Plain",
          preview: "Plain",
          caption: "Plain caption",
        },
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

test("additional renders allow live bundles but reject a single source module", () => {
  assert.deepEqual(
    additionalBundleLiveConflict({
      "additional-renders": ["feature.png"],
      "publish-live-bundle": true,
      "source-module": ":app",
    }),
    ["--source-module"],
  );
  assert.equal(
    additionalBundleLiveConflict({
      "additional-renders": ["feature.png"],
      "publish-live-bundle": true,
    }),
    null,
  );
  assert.equal(
    additionalBundleLiveConflict({ "additional-renders": ["feature.png"] }),
    null,
  );
  assert.equal(
    additionalBundleLiveConflict({ "publish-live-bundle": true }),
    null,
  );
});

test("namespacing keeps the declared function name beside the join key", () => {
  // A colliding name becomes `:module::Foo` so two modules can share a catalog. That key is not a
  // Kotlin identifier, so publishing it as a source anchor emits `File.kt#:feature::Foo`, which
  // names nothing — the declared name rides along for anything that has to state it.
  const record = (module, fn) => ({
    bundle: {
      manifest: {
        modulePath: module,
        previewIds: [`${fn}_p`],
        rawPreviewIds: [`${fn}_p`],
      },
      previews: [{ id: `${fn}_p`, functionName: fn }],
    },
    candidates: [],
  });
  const [, additional] = namespaceModuleRecords(record(":app", "Foo"), [
    record(":feature", "Foo"),
  ]);
  const preview = additional.bundle.previews[0];
  assert.equal(
    preview.functionName,
    ":feature::Foo",
    "the join key is namespaced",
  );
  assert.equal(
    preview.declaredFunctionName,
    "Foo",
    "the source still declares Foo",
  );
});
