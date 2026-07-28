/**
 * Unit tests for resolving the production composable a preview renders (Code Connect should point at
 * the real component, not the @Preview wrapper). Covers both read paths: parsed `preview.targets`
 * and the raw `previews.json` fallback.
 *
 * Run with `node --test scripts/design-artifacts/`.
 */
import assert from "node:assert/strict";
import { test } from "node:test";

import {
  bestTarget,
  isValidKotlinIdentifier,
  targetsByFunction,
} from "./figma-code-connect-target.mjs";

test("bestTarget takes the first (most-confident) entry, or null when none", () => {
  assert.deepEqual(
    bestTarget([
      {
        functionName: "DeviceSummaryCard",
        className: "com.x.DeviceKt",
        sourceFile: "a.kt",
        confidence: "HIGH",
        parameters: [{ name: "state", type: "State", hasDefault: false }],
      },
      { functionName: "Other", confidence: "LOW" },
    ]),
    {
      functionName: "DeviceSummaryCard",
      className: "com.x.DeviceKt",
      sourceFile: "a.kt",
      confidence: "HIGH",
      parameters: [{ name: "state", type: "State", hasDefault: false }],
    },
  );
  assert.equal(bestTarget([]), null);
  assert.equal(bestTarget(undefined), null);
  // A malformed entry with no functionName is treated as "no target".
  assert.equal(bestTarget([{ sourceFile: "a.kt" }]), null);
  // A target with no parameters carried yields an empty array (never undefined).
  assert.deepEqual(bestTarget([{ functionName: "X" }]).parameters, []);
});

test("bestTarget rejects JVM-mangled names that cannot appear in Kotlin source", () => {
  assert.equal(bestTarget([{ functionName: "Screen-G2aJUZY", confidence: "HIGH" }]), null);
  assert.equal(isValidKotlinIdentifier("Screen"), true);
  assert.equal(isValidKotlinIdentifier("Screen-G2aJUZY"), false);
});

test("targetsByFunction reads parsed preview.targets, preferring the light variant", () => {
  const bundle = {
    previews: [
      {
        id: "Fab_Dark",
        functionName: "FabPreview",
        targets: [{ functionName: "Fab", sourceFile: "dark.kt", confidence: "MEDIUM" }],
      },
      {
        id: "Fab_Light",
        functionName: "FabPreview",
        targets: [{ functionName: "Fab", sourceFile: "light.kt", confidence: "HIGH" }],
      },
    ],
  };
  const byFn = targetsByFunction(bundle);
  assert.equal(byFn.get("FabPreview").functionName, "Fab");
  assert.equal(byFn.get("FabPreview").sourceFile, "light.kt");
  assert.equal(byFn.get("FabPreview").confidence, "HIGH");
});

test("targetsByFunction omits previews that carried no target", () => {
  const bundle = { previews: [{ id: "X_Light", functionName: "XPreview", targets: [] }] };
  assert.equal(targetsByFunction(bundle).has("XPreview"), false);
});

test("targetsByFunction falls back to the raw previews.json entry when parsing dropped targets", () => {
  const raw = JSON.stringify({
    previews: [
      {
        id: "Card_Light",
        functionName: "CardPreview",
        targets: [{ functionName: "Card", sourceFile: "Card.kt", confidence: "HIGH" }],
      },
    ],
  });
  const bundle = {
    // Reader surfaced previews WITHOUT the targets field…
    previews: [{ id: "Card_Light", functionName: "CardPreview" }],
    // …but the raw manifest entry still carries them.
    entries: { "previews.json": new TextEncoder().encode(raw) },
  };
  const byFn = targetsByFunction(bundle);
  assert.equal(byFn.get("CardPreview").functionName, "Card");
  assert.equal(byFn.get("CardPreview").sourceFile, "Card.kt");
  assert.equal(byFn.get("CardPreview").confidence, "HIGH");
});

test("targetsByFunction carries the target's parameters and className", () => {
  const bundle = {
    previews: [
      {
        id: "Btn_Light",
        functionName: "BtnPreview",
        targets: [
          {
            functionName: "Button",
            className: "com.x.ButtonsKt",
            confidence: "HIGH",
            parameters: [
              { name: "label", type: "String", hasDefault: false },
              { name: "onClick", type: "() -> Unit", hasDefault: false, composableSlot: true },
            ],
          },
        ],
      },
    ],
  };
  const t = targetsByFunction(bundle).get("BtnPreview");
  assert.equal(t.className, "com.x.ButtonsKt");
  assert.equal(t.parameters.length, 2);
  assert.equal(t.parameters[1].composableSlot, true);
});

test("targetsByFunction handles a bare-array previews.json and bad JSON gracefully", () => {
  const arr = JSON.stringify([
    { id: "A_Light", functionName: "APreview", targets: [{ functionName: "A", confidence: "LOW" }] },
  ]);
  assert.equal(
    targetsByFunction({ entries: { "previews.json": new TextEncoder().encode(arr) } }).get("APreview")
      .functionName,
    "A",
  );
  // Malformed JSON ⇒ empty map, never a throw.
  assert.equal(
    targetsByFunction({ entries: { "previews.json": new TextEncoder().encode("{not json") } }).size,
    0,
  );
  // No previews and no entry ⇒ empty map.
  assert.equal(targetsByFunction({}).size, 0);
});
