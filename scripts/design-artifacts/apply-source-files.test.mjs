import { test } from "node:test";
import assert from "node:assert/strict";

import { applySourceFiles } from "./apply-source-files.mjs";

const comp = (componentId, extra = {}) => ({ componentId, images: [], ...extra });
const manifest = (components) => ({ schema: "design-parity-catalog/v1", system: "s", components });
const byFn = (entries) => new Map(entries.map(([fn, sf]) => [fn, { sourceFile: sf }]));

test("stamps each component's sourceFile from its spec function's discovery path", () => {
  const spec = {
    groups: [
      { name: "Buttons", components: [{ componentId: "Buttons/Filled", preview: "FilledButtonPreview" }] },
      { name: "Cards", components: [{ componentId: "Cards/Elevated", preview: "ElevatedCardPreview" }] },
    ],
  };
  const m = manifest([comp("Buttons/Filled"), comp("Cards/Elevated")]);
  const sources = byFn([
    ["FilledButtonPreview", "src/main/kotlin/com/example/Buttons.kt"],
    ["ElevatedCardPreview", "src/main/kotlin/com/example/Cards.kt"],
  ]);

  const stamped = applySourceFiles(m, spec, sources);

  assert.equal(stamped, 2);
  assert.equal(m.components[0].sourceFile, "src/main/kotlin/com/example/Buttons.kt");
  assert.equal(m.components[1].sourceFile, "src/main/kotlin/com/example/Cards.kt");
});

test("leaves a component untouched when its function carried no recorded path", () => {
  const spec = {
    groups: [{ name: "Buttons", components: [{ componentId: "Buttons/Filled", preview: "FilledButtonPreview" }] }],
  };
  const m = manifest([comp("Buttons/Filled")]);
  // Discovery recorded the function but with no sourceFile (undefined).
  const sources = new Map([["FilledButtonPreview", { sourceFile: undefined }]]);

  const stamped = applySourceFiles(m, spec, sources);

  assert.equal(stamped, 0);
  assert.equal("sourceFile" in m.components[0], false);
});

test("never clobbers a sourceFile already on the component", () => {
  const spec = {
    groups: [{ name: "Buttons", components: [{ componentId: "Buttons/Filled", preview: "FilledButtonPreview" }] }],
  };
  const m = manifest([comp("Buttons/Filled", { sourceFile: "already/There.kt" })]);
  const sources = byFn([["FilledButtonPreview", "src/main/kotlin/com/example/Buttons.kt"]]);

  const stamped = applySourceFiles(m, spec, sources);

  assert.equal(stamped, 0);
  assert.equal(m.components[0].sourceFile, "already/There.kt");
});

test("leaves manifest components absent from the spec untouched", () => {
  const spec = {
    groups: [{ name: "Buttons", components: [{ componentId: "Buttons/Filled", preview: "FilledButtonPreview" }] }],
  };
  const m = manifest([comp("Buttons/Filled"), comp("Orphan/NotInSpec")]);
  const sources = byFn([["FilledButtonPreview", "src/main/kotlin/com/example/Buttons.kt"]]);

  applySourceFiles(m, spec, sources);

  assert.equal(m.components[0].sourceFile, "src/main/kotlin/com/example/Buttons.kt");
  assert.equal("sourceFile" in m.components[1], false);
});

test("is idempotent — a second pass stamps nothing", () => {
  const spec = {
    groups: [{ name: "Buttons", components: [{ componentId: "Buttons/Filled", preview: "FilledButtonPreview" }] }],
  };
  const m = manifest([comp("Buttons/Filled")]);
  const sources = byFn([["FilledButtonPreview", "src/main/kotlin/com/example/Buttons.kt"]]);

  assert.equal(applySourceFiles(m, spec, sources), 1);
  assert.equal(applySourceFiles(m, spec, sources), 0);
  assert.equal(m.components[0].sourceFile, "src/main/kotlin/com/example/Buttons.kt");
});

test("is a no-op with an empty / missing lookup and tolerates empty inputs", () => {
  const spec = {
    groups: [{ name: "Buttons", components: [{ componentId: "Buttons/Filled", preview: "FilledButtonPreview" }] }],
  };
  assert.equal(applySourceFiles(manifest([comp("Buttons/Filled")]), spec, new Map()), 0);
  assert.equal(applySourceFiles(manifest([comp("Buttons/Filled")]), spec, undefined), 0);
  assert.equal(applySourceFiles({ components: [] }, {}, byFn([["X", "a.kt"]])), 0);
  assert.equal(applySourceFiles({}, { groups: [] }, byFn([["X", "a.kt"]])), 0);
});
