import { test } from "node:test";
import assert from "node:assert/strict";

import { unclaimedMotionPreviews, unclaimedMotionWarning } from "./unclaimed-motion.mjs";

const animated = (caption) => ({ animation: caption ? { caption } : {} });
const interacted = (caption) => ({ interaction: caption ? { caption } : {} });
const preview = (functionName, captures) => ({ id: `${functionName}_Light`, functionName, captures });
const bundle = (previews) => ({ previews });
const group = (components) => ({ name: "Components", components });

test("names a recording no component claims", () => {
  // wear-m3-catalog's shape: motion authored on standalone functions, no component pointing at it.
  const previews = [
    preview("SwitchTransitionMotion", [animated("off → on")]),
    preview("SwitchButtonSticker", [{}]),
  ];

  const unclaimed = unclaimedMotionPreviews(bundle(previews), [
    group([{ componentId: "Toggles/Switch", preview: "SwitchButtonSticker" }]),
  ]);

  assert.deepEqual(unclaimed, [{ functionName: "SwitchTransitionMotion", kinds: ["animation"] }]);
});

test("stays silent once a component's motionPreview points at the function", () => {
  const previews = [
    preview("SwitchTransitionMotion", [animated("off → on")]),
    preview("SwitchButtonSticker", [{}]),
  ];

  const unclaimed = unclaimedMotionPreviews(bundle(previews), [
    group([
      {
        componentId: "Toggles/Switch",
        preview: "SwitchButtonSticker",
        motionPreview: "SwitchTransitionMotion",
      },
    ]),
  ]);

  assert.deepEqual(unclaimed, []);
});

test("stays silent for the ordinary case: motion beside the component's own sticker", () => {
  // m3-catalog's shape — the default `motionPreviewFor` path, which was never broken.
  const unclaimed = unclaimedMotionPreviews(bundle([preview("SwitchOn", [interacted("tap")])]), [
    group([{ componentId: "Switch/On", preview: "SwitchOn" }]),
  ]);

  assert.deepEqual(unclaimed, []);
});

test("ignores a preview that declares no motion at all", () => {
  const unclaimed = unclaimedMotionPreviews(bundle([preview("PlainSticker", [{}, {}])]), []);

  assert.deepEqual(unclaimed, []);
});

test("collects both kinds when one function declares each, de-duplicated across its captures", () => {
  const previews = [
    preview("BothMotion", [animated("spins"), interacted("tap"), animated("spins")]),
  ];

  const unclaimed = unclaimedMotionPreviews(bundle(previews), []);

  assert.deepEqual(unclaimed, [
    { functionName: "BothMotion", kinds: ["animation", "interaction"] },
  ]);
});

test("reads every bundle the join reads, and reports each function once", () => {
  // The join passes `[bundle, ...additionalBundles]`; a multi-module catalog must not double-report
  // a function whose light and dark previews both declare the capture.
  const bundles = [
    bundle([preview("EdgeButtonRevealMotion", [animated()])]),
    bundle([
      { id: "EdgeButtonRevealMotion_Dark", functionName: "EdgeButtonRevealMotion", captures: [animated()] },
      preview("SwipeToRevealMotion", [animated()]),
    ]),
  ];

  const unclaimed = unclaimedMotionPreviews(bundles, []);

  assert.deepEqual(unclaimed, [
    { functionName: "EdgeButtonRevealMotion", kinds: ["animation"] },
    { functionName: "SwipeToRevealMotion", kinds: ["animation"] },
  ]);
});

test("falls back to the preview id when a bundle entry carries no function name", () => {
  const unclaimed = unclaimedMotionPreviews(bundle([{ id: "LooseMotion", captures: [animated()] }]), []);

  assert.deepEqual(unclaimed, [{ functionName: "LooseMotion", kinds: ["animation"] }]);
});

test("tolerates an empty or malformed inventory rather than throwing mid-publish", () => {
  const previews = [preview("OrphanMotion", [animated()])];

  assert.deepEqual(unclaimedMotionPreviews(bundle(previews), undefined), [
    { functionName: "OrphanMotion", kinds: ["animation"] },
  ]);
  assert.deepEqual(unclaimedMotionPreviews(undefined, [group([])]), []);
  assert.deepEqual(unclaimedMotionPreviews(bundle(previews), [{ name: "No components" }]), [
    { functionName: "OrphanMotion", kinds: ["animation"] },
  ]);
});

test("the warning names every function and how to wire it", () => {
  const message = unclaimedMotionWarning("wear-m3-catalog", [
    { functionName: "SwipeToRevealMotion", kinds: ["animation"] },
    { functionName: "SwitchOn", kinds: ["animation", "interaction"] },
  ]);

  assert.match(message, /^\[wear-m3-catalog\] motion: 2 @Preview function\(s\)/);
  assert.match(message, /SwipeToRevealMotion \(animation\)/);
  assert.match(message, /SwitchOn \(animation\+interaction\)/);
  assert.match(message, /motionPreview/);
});

test("no findings, no line — a catalog with no motion must not grow a warning", () => {
  assert.equal(unclaimedMotionWarning("m3-catalog", []), null);
  assert.equal(unclaimedMotionWarning("m3-catalog", undefined), null);
});
