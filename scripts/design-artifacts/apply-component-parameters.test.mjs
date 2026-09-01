import assert from "node:assert/strict";
import { test } from "node:test";

import { applyComponentParameters } from "./apply-component-parameters.mjs";

test("stamps ordered component parameters including slots and defaults", () => {
  const manifest = {
    components: [{ componentId: "Layout/Row", images: [] }, { componentId: "Text", images: [] }],
  };
  const spec = {
    groups: [
      {
        components: [
          { componentId: "Layout/Row", preview: "RowPreview" },
          { componentId: "Text", preview: "TextPreview" },
        ],
      },
    ],
  };
  const targets = new Map([
    [
      "RowPreview",
      {
        parameters: [
          { name: "spacing", type: "Dp", hasDefault: true },
          {
            name: "content",
            type: "RowScope.() -> Unit",
            hasDefault: false,
            composableSlot: true,
          },
        ],
      },
    ],
  ]);

  assert.equal(applyComponentParameters(manifest, spec, targets), 1);
  assert.deepEqual(manifest.components[0].parameters, [
    { name: "spacing", type: "Dp", hasDefault: true },
    { name: "content", type: "RowScope.() -> Unit", composableSlot: true },
  ]);
  assert.equal("parameters" in manifest.components[1], false);
});

test("leaves an older or uninferred component unchanged", () => {
  const manifest = { components: [{ componentId: "Unknown", images: [] }] };
  const spec = {
    groups: [{ components: [{ componentId: "Unknown", preview: "UnknownPreview" }] }],
  };

  assert.equal(applyComponentParameters(manifest, spec, new Map()), 0);
  assert.deepEqual(manifest, { components: [{ componentId: "Unknown", images: [] }] });
});

test("does not attach an inference rejected by an explicit component override", () => {
  const manifest = { components: [{ componentId: "Button", images: [] }] };
  const spec = {
    groups: [
      {
        components: [
          { componentId: "Button", preview: "ButtonPreview", component: "CorrectButton" },
        ],
      },
    ],
  };
  const targets = new Map([
    ["ButtonPreview", { functionName: "WrongButton", parameters: [{ name: "wrong", type: "Int" }] }],
  ]);

  assert.equal(applyComponentParameters(manifest, spec, targets), 0);
  assert.equal("parameters" in manifest.components[0], false);
});
