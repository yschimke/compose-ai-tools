import assert from "node:assert/strict";
import test from "node:test";

import { renderFailuresFromBundles } from "./render-failures.mjs";

test("render error sidecars retain catalog location, mode, phase, and diagnostics", () => {
  const error = {
    schema: "compose-preview-error/v1",
    phase: "evaluateRule",
    exception: "java.lang.NoSuchMethodError",
    message: "MaterialTheme.colors()",
    stackTrace: "java.lang.NoSuchMethodError\n at ButtonKt:42",
  };
  const bundle = {
    manifest: { previewIds: ["ButtonPreview_Dark"], rawPreviewIds: ["ButtonPreview_Dark"] },
    previews: [
      { id: "ButtonPreview_Dark", functionName: "ButtonPreview", sourceFile: "Button.kt" },
    ],
    entries: {
      "previews/ButtonPreview_Dark.error.json": new TextEncoder().encode(JSON.stringify(error)),
    },
  };
  const spec = {
    modes: ["light", "dark"],
    groups: [
      {
        name: "Actions",
        section: "Components",
        components: [{ componentId: "Button/Filled", preview: "ButtonPreview" }],
      },
    ],
  };

  const [failure] = renderFailuresFromBundles([bundle], spec);

  assert.deepEqual(failure, {
    id: "render-failed--button-filled--buttonpreview-dark",
    componentId: "Button/Filled",
    preview: "ButtonPreview_Dark",
    phase: "evaluateRule",
    errorClass: "java.lang.NoSuchMethodError",
    message: "MaterialTheme.colors()",
    stackTrace: "java.lang.NoSuchMethodError\n at ButtonKt:42",
    group: "Actions",
    section: "Components",
    mode: "dark",
    sourceFile: "Button.kt",
  });
});

test("malformed and unknown error schemas are ignored", () => {
  const bundle = {
    previews: [{ id: "bad" }, { id: "future" }],
    entries: {
      "previews/bad.error.json": new TextEncoder().encode("{"),
      "previews/future.error.json": new TextEncoder().encode(
        JSON.stringify({ schema: "compose-preview-error/v2" }),
      ),
    },
  };

  assert.deepEqual(renderFailuresFromBundles([bundle], {}), []);
});
