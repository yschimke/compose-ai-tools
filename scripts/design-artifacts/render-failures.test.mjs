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

test("failed fan-out previews honor component and variant selections", () => {
  const error = {
    schema: "compose-preview-error/v1",
    exception: "java.lang.IllegalStateException",
    message: "boom",
  };
  const bundle = {
    previews: [
      {
        id: "RoundPreview",
        functionName: "RoundPreview",
        params: { device: "id:wearos_large_round" },
      },
    ],
    entries: {
      "previews/RoundPreview.error.json": new TextEncoder().encode(JSON.stringify(error)),
    },
  };
  const spec = {
    breakpoints: [
      { size: "smallRound", device: "id:wearos_small_round" },
      { size: "largeRound", device: "id:wearos_large_round" },
    ],
    groups: [
      {
        components: [
          { componentId: "Small", preview: "RoundPreview", select: { size: "smallRound" } },
          {
            componentId: "Large",
            preview: "RoundPreview",
            select: { size: "largeRound" },
          },
        ],
      },
    ],
  };

  assert.deepEqual(
    renderFailuresFromBundles([bundle], spec).map((failure) => failure.componentId),
    ["Large"],
  );
});

test("render failure ids remain unique when slugs collide", () => {
  const error = {
    schema: "compose-preview-error/v1",
    exception: "First",
    message: "one",
  };
  const second = { ...error, exception: "Second", message: "two" };
  const bundle = {
    previews: [
      { id: "Button+A", functionName: "ButtonPreview" },
      { id: "Button-A", functionName: "ButtonPreview" },
    ],
    entries: {
      "previews/Button+A.error.json": new TextEncoder().encode(JSON.stringify(error)),
      "previews/Button-A.error.json": new TextEncoder().encode(JSON.stringify(second)),
    },
  };

  assert.deepEqual(
    renderFailuresFromBundles([bundle], {}).map((failure) => failure.id),
    ["render-failed--buttonpreview--button-a", "render-failed--buttonpreview--button-a--2"],
  );
});
