/**
 * Unit tests for the Figma Code Connect emit join: componentId → `@Preview` function + repo source,
 * keyed by the Figma layer name so `publish-code-connect.mjs` can resolve node ids later.
 *
 * Run with `node --test scripts/design-artifacts/`.
 */
import assert from "node:assert/strict";
import { test } from "node:test";

import {
  COMPOSE_LABEL,
  buildCodeConnectManifest,
  importFor,
  renderCallSite,
  resolveSource,
} from "./figma-code-connect-emit.mjs";

test("importFor derives the import from the owner-facade FQN", () => {
  assert.equal(importFor("com.x.ui.ButtonsKt", "Button"), "import com.x.ui.Button");
  assert.equal(importFor("Button", "Button"), null); // no package
  assert.equal(importFor(undefined, "Button"), null);
});

test("renderCallSite renders only required params, slots as lambdas", () => {
  const { codeSnippet, imports } = renderCallSite("DeviceSummaryCard", "import com.x.DeviceSummaryCard", [
    { name: "state", type: "State", hasDefault: false },
    { name: "modifier", type: "Modifier", hasDefault: true }, // defaulted → omitted
    { name: "content", type: "() -> Unit", hasDefault: false, composableSlot: true },
  ]);
  assert.equal(
    codeSnippet,
    'DeviceSummaryCard(\n    state = TODO("State"),\n    content = { },\n)',
  );
  assert.deepEqual(imports, ["import com.x.DeviceSummaryCard"]);
});

test("renderCallSite with no required params is a bare call", () => {
  assert.equal(renderCallSite("Foo", null, [{ name: "a", type: "Int", hasDefault: true }]).codeSnippet, "Foo()");
  assert.equal(renderCallSite("Foo", null, []).codeSnippet, "Foo()");
});

test("resolveSource builds a GitHub blob URL from repo + ref + module-relative source file", () => {
  const url = resolveSource({
    repo: "yschimke/meshcore-mobile",
    ref: "main",
    module: ":app",
    sourceFile: "src/main/kotlin/ee/schimke/meshcore/app/ui/ComponentPreviews.kt",
  });
  assert.equal(
    url,
    "https://github.com/yschimke/meshcore-mobile/blob/main/app/src/main/kotlin/ee/schimke/meshcore/app/ui/ComponentPreviews.kt",
  );
});

test("resolveSource maps a nested module id to a path segment", () => {
  const url = resolveSource({
    repo: "o/r",
    ref: "v1",
    module: ":features:home",
    sourceFile: "Foo.kt",
  });
  assert.equal(url, "https://github.com/o/r/blob/v1/features/home/Foo.kt");
});

test("resolveSource falls back to the module directory when no source file is known", () => {
  assert.equal(resolveSource({ repo: "o/r", ref: "main", module: ":app" }), "https://github.com/o/r/blob/main/app");
  // No repo/ref ⇒ a bare repo-relative path, still useful for review.
  assert.equal(resolveSource({ module: ":app", sourceFile: "Foo.kt" }), "app/Foo.kt");
  // Nothing at all ⇒ empty string, never a broken URL.
  assert.equal(resolveSource({}), "");
});

const slug = (id) => id.toLowerCase().replaceAll("/", "-");

test("buildCodeConnectManifest maps each component to its preview function and layer name", () => {
  const manifest = buildCodeConnectManifest({
    components: [{ componentId: "DeviceSummaryCard/Populated" }, { componentId: "ContactRow/Variants" }],
    fnByComponentId: new Map([
      ["DeviceSummaryCard/Populated", "DeviceSummaryCardPopulatedPreview"],
      ["ContactRow/Variants", "ContactRowVariantsPreview"],
    ]),
    slug,
    figmaSvgSlugs: new Set(["devicesummarycard-populated"]),
    sourceByFn: new Map([
      ["DeviceSummaryCardPopulatedPreview", { sourceFile: "src/DeviceBodyPreviews.kt" }],
    ]),
    system: "meshcore-mobile",
    title: "MeshCore Mobile",
    source: { repo: "yschimke/meshcore-mobile", ref: "main", module: ":app" },
    generatedAt: "2026-07-17T00:00:00.000Z",
  });

  assert.equal(manifest.system, "meshcore-mobile");
  assert.equal(manifest.label, COMPOSE_LABEL);
  assert.equal(manifest.source.repo, "yschimke/meshcore-mobile");
  assert.equal(manifest.mappings.length, 2);

  const device = manifest.mappings[0];
  assert.equal(device.componentId, "DeviceSummaryCard/Populated");
  // The layer name a Figma node must carry to receive this mapping = componentId verbatim.
  assert.equal(device.figmaLayerName, "DeviceSummaryCard/Populated");
  // No explicit spec component and no inferred target here ⇒ the preview function is the fallback.
  assert.equal(device.componentName, "DeviceSummaryCardPopulatedPreview");
  assert.equal(device.confidence, "preview-fallback");
  assert.equal(device.previewName, "DeviceSummaryCardPopulatedPreview");
  assert.equal(
    device.source,
    "https://github.com/yschimke/meshcore-mobile/blob/main/app/src/DeviceBodyPreviews.kt",
  );
  assert.equal(device.label, "Compose");
  // Only components that carried a figma-svg get a vector link.
  assert.equal(device.figmaSvg, "figma/devicesummarycard-populated.svg");
  assert.equal(manifest.mappings[1].figmaSvg, undefined);
});

test("buildCodeConnectManifest prefers an inferred target over the preview function", () => {
  const manifest = buildCodeConnectManifest({
    components: [{ componentId: "DeviceSummaryCard/Populated" }],
    fnByComponentId: new Map([["DeviceSummaryCard/Populated", "DeviceSummaryCardPopulatedPreview"]]),
    targetByFn: new Map([
      [
        "DeviceSummaryCardPopulatedPreview",
        { functionName: "DeviceSummaryCard", sourceFile: "src/DeviceSummaryCard.kt", confidence: "HIGH" },
      ],
    ]),
    slug,
    figmaSvgSlugs: new Set(),
    source: { repo: "o/r", ref: "main", module: ":app" },
  });
  const m = manifest.mappings[0];
  // Points at the real component, not the wrapper.
  assert.equal(m.componentName, "DeviceSummaryCard");
  assert.equal(m.source, "https://github.com/o/r/blob/main/app/src/DeviceSummaryCard.kt");
  assert.equal(m.confidence, "HIGH");
  // The preview that rendered the sticker is still recorded for traceability.
  assert.equal(m.previewName, "DeviceSummaryCardPopulatedPreview");
});

test("buildCodeConnectManifest attaches a call site when the emitted component is the inferred target", () => {
  const manifest = buildCodeConnectManifest({
    components: [{ componentId: "Device/Summary" }],
    fnByComponentId: new Map([["Device/Summary", "DeviceSummaryPreview"]]),
    targetByFn: new Map([
      [
        "DeviceSummaryPreview",
        {
          functionName: "DeviceSummaryCard",
          className: "com.x.ui.DeviceKt",
          confidence: "HIGH",
          parameters: [
            { name: "state", type: "State", hasDefault: false },
            { name: "modifier", type: "Modifier", hasDefault: true },
          ],
        },
      ],
    ]),
    slug,
    figmaSvgSlugs: new Set(),
    source: { repo: "o/r", ref: "main", module: ":app" },
  });
  const m = manifest.mappings[0];
  assert.equal(m.componentName, "DeviceSummaryCard");
  assert.equal(m.codeSnippet, 'DeviceSummaryCard(\n    state = TODO("State"),\n)');
  assert.deepEqual(m.imports, ["import com.x.ui.DeviceSummaryCard"]);
  assert.equal(m.parameters.length, 2);
});

test("buildCodeConnectManifest: no call site for a preview-fallback (no matching target)", () => {
  const manifest = buildCodeConnectManifest({
    components: [{ componentId: "Theme/Light" }],
    fnByComponentId: new Map([["Theme/Light", "ThemeLightPreview"]]),
    slug,
    figmaSvgSlugs: new Set(),
    source: {},
  });
  assert.equal(manifest.mappings[0].codeSnippet, undefined);
});

test("buildCodeConnectManifest: explicit spec component wins over an inferred target", () => {
  const manifest = buildCodeConnectManifest({
    components: [{ componentId: "Btn/Primary" }],
    fnByComponentId: new Map([["Btn/Primary", "BtnPrimaryPreview"]]),
    componentByComponentId: new Map([
      ["Btn/Primary", { component: "PrimaryButton", import: "import com.x.PrimaryButton", source: "src/Button.kt" }],
    ]),
    targetByFn: new Map([["BtnPrimaryPreview", { functionName: "WrongGuess", confidence: "LOW" }]]),
    slug,
    figmaSvgSlugs: new Set(),
    source: { repo: "o/r", ref: "main", module: ":ui" },
  });
  const m = manifest.mappings[0];
  assert.equal(m.componentName, "PrimaryButton");
  assert.equal(m.confidence, "explicit");
  assert.equal(m.import, "import com.x.PrimaryButton");
  assert.equal(m.source, "https://github.com/o/r/blob/main/ui/src/Button.kt");
});

test("explicit component without a source does NOT inherit a rejected inference's file", () => {
  const manifest = buildCodeConnectManifest({
    components: [{ componentId: "Btn/Primary" }],
    fnByComponentId: new Map([["Btn/Primary", "BtnPrimaryPreview"]]),
    // Author pins the component to override the inference, but supplies no explicit source.
    componentByComponentId: new Map([["Btn/Primary", { component: "PrimaryButton" }]]),
    // The inference guessed a DIFFERENT composable — its file must not be linked.
    targetByFn: new Map([
      ["BtnPrimaryPreview", { functionName: "WrongGuess", sourceFile: "src/WrongGuess.kt", confidence: "LOW" }],
    ]),
    // The preview's own file is the honest fallback.
    sourceByFn: new Map([["BtnPrimaryPreview", { sourceFile: "src/BtnPreviews.kt" }]]),
    slug,
    figmaSvgSlugs: new Set(),
    source: { repo: "o/r", ref: "main", module: ":ui" },
  });
  const m = manifest.mappings[0];
  assert.equal(m.componentName, "PrimaryButton");
  // Falls back to the preview's file, NOT src/WrongGuess.kt.
  assert.equal(m.source, "https://github.com/o/r/blob/main/ui/src/BtnPreviews.kt");
});

test("explicit component reuses the inferred file only when the inference matches it", () => {
  const manifest = buildCodeConnectManifest({
    components: [{ componentId: "Fab/Default" }],
    fnByComponentId: new Map([["Fab/Default", "FabPreview"]]),
    componentByComponentId: new Map([["Fab/Default", { component: "Fab" }]]),
    // Inference agrees with the explicit component ⇒ its source file is trustworthy.
    targetByFn: new Map([["FabPreview", { functionName: "Fab", sourceFile: "src/Fab.kt", confidence: "HIGH" }]]),
    slug,
    figmaSvgSlugs: new Set(),
    source: { repo: "o/r", ref: "main", module: ":ui" },
  });
  assert.equal(manifest.mappings[0].source, "https://github.com/o/r/blob/main/ui/src/Fab.kt");
});

test("buildCodeConnectManifest skips components with no preview function (nothing to bind)", () => {
  const manifest = buildCodeConnectManifest({
    components: [{ componentId: "Known/One" }, { componentId: "Orphan/Two" }],
    fnByComponentId: new Map([["Known/One", "KnownOnePreview"]]),
    slug,
    figmaSvgSlugs: new Set(),
    system: "s",
    source: {},
  });
  assert.equal(manifest.mappings.length, 1);
  assert.equal(manifest.mappings[0].componentId, "Known/One");
});

test("buildCodeConnectManifest omits generatedAt and empty source fields when absent", () => {
  const manifest = buildCodeConnectManifest({
    components: [{ componentId: "A/B" }],
    fnByComponentId: new Map([["A/B", "AbPreview"]]),
    slug,
    figmaSvgSlugs: new Set(),
    system: "s",
    source: {},
  });
  assert.equal(manifest.generatedAt, undefined);
  assert.deepEqual(manifest.source, {});
  // Falls back to an empty source string rather than a broken URL when nothing is known.
  assert.equal(manifest.mappings[0].source, "");
});
