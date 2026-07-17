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
  resolveSource,
} from "./figma-code-connect-emit.mjs";

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
  assert.equal(device.componentName, "DeviceSummaryCardPopulatedPreview");
  assert.equal(
    device.source,
    "https://github.com/yschimke/meshcore-mobile/blob/main/app/src/DeviceBodyPreviews.kt",
  );
  assert.equal(device.label, "Compose");
  // Only components that carried a figma-svg get a vector link.
  assert.equal(device.figmaSvg, "figma/devicesummarycard-populated.svg");
  assert.equal(manifest.mappings[1].figmaSvg, undefined);
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
