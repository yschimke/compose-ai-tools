import assert from "node:assert/strict";
import test from "node:test";
import {
  previewModuleRecords,
  previewModules,
  previewModuleSources,
} from "./preview-modules.mjs";

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

test("module records retain Gradle-resolved nonconventional project directories", () => {
  const records = previewModuleRecords(
      {
        previews: [
          { module: "ui", projectDirectory: "/workspace/components/ui" },
          { module: "app", projectDirectory: "/workspace/application" },
          { module: "ui", projectDirectory: "/workspace/components/ui" },
        ],
      },
      ":ui",
    );
  assert.deepEqual(
    records,
    [
      { module: "ui", projectDirectory: "/workspace/components/ui" },
      { module: "app", projectDirectory: "/workspace/application" },
    ],
  );
  assert.deepEqual(previewModuleSources(records, "/workspace"), [
    "/workspace/components/ui",
    "/workspace/application",
  ]);
});

test("module sources retain a conventional fallback for pre-field CLI output", () => {
  assert.deepEqual(
    previewModuleSources(
      [
        { module: "feature:ui" },
        { module: ":app" },
      ],
      "/workspace/build-root",
    ),
    ["/workspace/build-root/feature/ui", "/workspace/build-root/app"],
  );
});
