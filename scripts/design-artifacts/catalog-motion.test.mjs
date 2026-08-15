import assert from "node:assert/strict";
import { test } from "node:test";

import {
  foldMotion,
  motionArtifactsFor,
  motionDeclarationOf,
  motionPreviewFor,
} from "./catalog-motion.mjs";

const interactionCapture = (renderOutput, caption) => ({
  renderOutput,
  interaction: { gesture: "TAP", targets: [0, 0], caption },
});

const animationCapture = (renderOutput, caption) => ({
  renderOutput,
  animation: { durationMs: 0, frameIntervalMs: 16, caption },
});

test("motionPreviewFor uses an explicit motion function and otherwise falls back to the still", () => {
  assert.equal(motionPreviewFor({ preview: "Spinner" }), "Spinner");
  assert.equal(
    motionPreviewFor({ preview: "Spinner", motionPreview: "SpinnerMotion" }),
    "SpinnerMotion",
  );
});

test("motionDeclarationOf names the kind and keeps the caption", () => {
  assert.deepEqual(motionDeclarationOf(interactionCapture("renders/A.apng", "Toggle it")), {
    kind: "interaction",
    caption: "Toggle it",
  });
  assert.deepEqual(motionDeclarationOf(animationCapture("renders/A.gif", "It spins")), {
    kind: "animation",
    caption: "It spins",
  });
});

test("an empty caption is dropped rather than published as an empty line", () => {
  assert.deepEqual(motionDeclarationOf(animationCapture("renders/A.gif", "")), {
    kind: "animation",
    caption: undefined,
  });
});

test("a still-only capture is not motion", () => {
  assert.equal(motionDeclarationOf({ renderOutput: "renders/A.png" }), null);
  assert.equal(motionDeclarationOf(undefined), null);
});

test("artifacts are collected per function, across every preview it fanned out to", () => {
  const bundle = {
    previews: [
      {
        id: "Sw_Light",
        functionName: "SwitchOn",
        captures: [{ renderOutput: "renders/Sw_Light.png" }, interactionCapture("renders/Sw_Light.apng", "Toggle")],
      },
      {
        id: "Sw_Dark",
        functionName: "SwitchOn",
        captures: [interactionCapture("renders/Sw_Dark.apng", "Toggle")],
      },
      { id: "Other", functionName: "Elsewhere", captures: [interactionCapture("renders/Other.apng", "x")] },
    ],
    entries: {
      "previews/Sw_Light.apng": {},
      "previews/Sw_Dark.apng": {},
      "previews/Other.apng": {},
    },
  };

  assert.deepEqual(motionArtifactsFor(bundle, "SwitchOn"), [
    { path: "previews/Sw_Light.apng", kind: "interaction", caption: "Toggle" },
    { path: "previews/Sw_Dark.apng", kind: "interaction", caption: "Toggle" },
  ]);
});

test("a declared capture the render never wrote is dropped, not published as a 404", () => {
  const bundle = {
    previews: [
      { id: "Sw", functionName: "SwitchOn", captures: [interactionCapture("renders/Sw.apng", "Toggle")] },
    ],
    entries: {},
  };

  assert.deepEqual(motionArtifactsFor(bundle, "SwitchOn"), []);
});

test("a function carrying both annotations keeps its two captures apart by renderOutput", () => {
  // This is the case the plain `previews/<id>.<ext>` fallback cannot answer: both artifacts belong
  // to one preview id, and only the manifest says which file is the interaction.
  const bundle = {
    previews: [
      {
        id: "Spinner",
        functionName: "Spinner",
        captures: [
          animationCapture("renders/Spinner.apng", "Spins on its own"),
          interactionCapture("renders/Spinner_interaction.apng", "Tap to restart"),
        ],
      },
    ],
    entries: { "previews/Spinner.apng": {}, "previews/Spinner_interaction.apng": {} },
  };

  assert.deepEqual(motionArtifactsFor(bundle, "Spinner"), [
    { path: "previews/Spinner.apng", kind: "animation", caption: "Spins on its own" },
    { path: "previews/Spinner_interaction.apng", kind: "interaction", caption: "Tap to restart" },
  ]);
});

test("foldMotion tags each capture with the theme of the still it shares a preview with", () => {
  const images = [
    { path: "previews/Sw_Light.png", theme: "light" },
    { path: "previews/Sw_Dark.png", theme: "dark" },
  ];
  const artifacts = [
    { path: "previews/Sw_Light.apng", kind: "interaction", caption: "Toggle" },
    { path: "previews/Sw_Dark.apng", kind: "interaction", caption: "Toggle" },
  ];

  assert.deepEqual(foldMotion(images, artifacts), [
    { path: "previews/Sw_Light.apng", kind: "interaction", caption: "Toggle", theme: "light" },
    { path: "previews/Sw_Dark.apng", kind: "interaction", caption: "Toggle", theme: "dark" },
  ]);
});

test("an untagged catalog folds motion with no theme rather than inventing one", () => {
  const images = [{ path: "previews/Sw.png" }];
  const artifacts = [{ path: "previews/Sw.apng", kind: "interaction" }];

  assert.deepEqual(foldMotion(images, artifacts), [
    { path: "previews/Sw.apng", kind: "interaction" },
  ]);
});

test("no artifacts folds to nothing, so a component without motion gains no field", () => {
  assert.deepEqual(foldMotion([{ path: "previews/Sw.png", theme: "light" }], []), []);
  assert.deepEqual(foldMotion([], undefined), []);
});
