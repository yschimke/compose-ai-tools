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

test("a module whose previews are all synthetic drops out when those ids are ignored", () => {
  // An imported project renders composables only, so the app-launching synthetic previews are
  // excluded at render time. A module holding nothing else would have its whole set excluded and
  // throw, sinking the sweep — so it must not reach the render list at all.
  const response = {
    previews: [
      { module: ":app", id: "activity__MainActivity" },
      { module: ":app", id: "apptour__default" },
      { module: ":ui", id: "Button_Light" },
    ],
  };
  assert.deepEqual(previewModules(response, null, ["activity__", "apptour__"]), [":ui"]);
  // First-party runs pass no prefixes and keep the activity-only module: its capture is the point.
  assert.deepEqual(previewModules(response), [":app", ":ui"]);
});

test("a module keeps its place when it holds an authored preview alongside synthetic ones", () => {
  const response = {
    previews: [
      { module: ":app", id: "activity__MainActivity" },
      { module: ":app", id: "PersonView_Light" },
    ],
  };
  assert.deepEqual(previewModules(response, null, ["activity__", "apptour__"]), [":app"]);
});

test("previews with no id survive an ignore list rather than vanishing", () => {
  // Defensive: an older CLI's `list --json` may omit `id`. Dropping those would silently thin the
  // module list, which is the failure mode this whole lane exists to avoid.
  assert.deepEqual(
    previewModules({ previews: [{ module: ":ui" }] }, null, ["activity__"]),
    [":ui"],
  );
});
