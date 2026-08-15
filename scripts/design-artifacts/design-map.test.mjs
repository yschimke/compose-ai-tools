import { test } from "node:test";
import assert from "node:assert/strict";

import {
  DESIGN_MAP_VARIANTS_SCHEMA,
  codeHandle,
  projectDesignMap,
  sourceForRef,
  variantRendersByComponent,
  variantSeeds,
} from "./design-map.mjs";

const FILE = "AbCdEf";
const ref = (nodeId) => `figma:${FILE}/${nodeId}`;

/** A COMPONENT-role light capture — the shape every mapped component arrives in. */
const component = (name, catalog, extra = {}) => ({
  id: `com.example.CatalogKt.${name}_Light`,
  functionName: name,
  sourceFile: "Catalog.kt",
  catalog: { role: "COMPONENT", componentId: name, ...catalog },
  ...extra,
});

/** An `@OverrideVariant` reseed of `name` — same composable, so still COMPONENT role. */
const overrideVariant = (name, variant, overrides) => ({
  id: `com.example.CatalogKt.${name}_Light_VARIANT_${variant}`,
  functionName: name,
  sourceFile: "Catalog.kt",
  catalog: { role: "COMPONENT", componentId: name },
  overrides: { name: variant, ...overrides },
});

/** A `@CatalogVariant` — its own composable, so VARIANT role and an ordinary light id. */
const catalogVariant = (name, of, catalog) => ({
  id: `com.example.CatalogKt.${name}_Light`,
  functionName: name,
  sourceFile: "Catalog.kt",
  catalog: { role: "VARIANT", componentId: of, ...catalog },
});

test("codeHandle addresses a subject as <path>#<function>", () => {
  assert.equal(
    codeHandle({ sourceFile: "Catalog.kt", functionName: "FilledButton" }),
    "catalog/Catalog.kt#FilledButton",
  );
  assert.equal(
    codeHandle({ sourceFile: "ui/Buttons.kt", functionName: "Fab" }, { prefix: "app/src" }),
    "app/src/ui/Buttons.kt#Fab",
  );
});

test("sourceForRef dispatches on the ref's scheme", () => {
  assert.equal(sourceForRef(ref("1:2")), "figma");
  assert.equal(sourceForRef("claude-design:export/button.html"), "claude-design");
});

test("projects one entry per component, from the light capture only", () => {
  const { map } = projectDesignMap([
    component("FilledButton", { reference: ref("1:2") }),
    { ...component("FilledButton", { reference: ref("1:2") }), id: "com.example.CatalogKt.FilledButton_Dark" },
  ]);
  assert.equal(map.components.length, 1);
  assert.deepEqual(map.components[0], {
    code: "catalog/Catalog.kt#FilledButton",
    source: "figma",
    ref: ref("1:2"),
    previewId: "com.example.CatalogKt.FilledButton_Light",
  });
});

test("carries refSet and a referenceContentsOnly opt-out only when declared", () => {
  const { map } = projectDesignMap([
    component("A", { reference: ref("1:2"), referenceSet: ref("1:1") }),
    component("B", { reference: ref("2:2"), referenceContentsOnly: false }),
    component("C", { reference: ref("3:2"), referenceContentsOnly: true }),
  ]);
  const [a, b, c] = map.components;
  assert.equal(a.refSet, ref("1:1"));
  assert.equal(b.referenceContentsOnly, false);
  // The default is true; restating it would put a field on every entry that says nothing.
  assert.ok(!("referenceContentsOnly" in c));
  assert.ok(!("refSet" in c));
});

test("separates a stated absence from nobody having looked", () => {
  const { map, diagnostics } = projectDesignMap([
    component("Mapped", { reference: ref("1:2") }),
    component("Retired", { noReference: "the kit retired this pattern in M3" }),
    component("Forgotten", {}),
  ]);
  assert.deepEqual(map.components.map((c) => c.code), ["catalog/Catalog.kt#Mapped"]);
  assert.deepEqual(diagnostics.statedAbsent, [
    { componentId: "Retired", reason: "the kit retired this pattern in M3" },
  ]);
  assert.deepEqual(diagnostics.unmapped, ["Forgotten"]);
});

test("entries are sorted by code handle, so the file is diffable", () => {
  const { map } = projectDesignMap([
    component("Zebra", { reference: ref("1:2") }),
    component("Alpha", { reference: ref("2:2") }),
  ]);
  assert.deepEqual(map.components.map((c) => c.code), [
    "catalog/Catalog.kt#Alpha",
    "catalog/Catalog.kt#Zebra",
  ]);
});

test("variantSeeds prefers the full axis assignment over the non-default seeds", () => {
  // `seeds` holds only what differs from the composable's defaults; `props` carries every axis the
  // cell sits at. A kit that spells its default size explicitly in a combination cell has nothing
  // to match against if only the non-default half arrives.
  const preview = overrideVariant("Button", "s-square", {
    seeds: [{ key: "shape", kind: "STRING", raw: "square" }],
    props: [
      { key: "size", value: "s" },
      { key: "shape", value: "square" },
    ],
  });
  assert.deepEqual(variantSeeds(preview), [
    { key: "size", raw: "s" },
    { key: "shape", raw: "square" },
  ]);
});

test("variantSeeds falls back to seeds for a hand-written override variant", () => {
  const preview = overrideVariant("Button", "l", {
    seeds: [{ key: "size", kind: "STRING", raw: "l" }],
  });
  assert.deepEqual(variantSeeds(preview), [{ key: "size", raw: "l" }]);
});

test("variantSeeds reads a CatalogVariant's props, and its state shorthand", () => {
  assert.deepEqual(
    variantSeeds(catalogVariant("FabLarge", "Fab", { props: [{ key: "size", value: "large" }] })),
    [{ key: "size", raw: "large" }],
  );
  // `state` is the annotation's shorthand for the one axis common enough to have its own parameter.
  assert.deepEqual(variantSeeds(catalogVariant("ButtonDisabled", "Button", { state: "disabled" })), [
    { key: "state", raw: "disabled" },
  ]);
  // An explicit state prop wins; the shorthand does not get appended twice.
  assert.deepEqual(
    variantSeeds(
      catalogVariant("X", "Button", { state: "disabled", props: [{ key: "state", value: "off" }] }),
    ),
    [{ key: "state", raw: "off" }],
  );
});

test("collects both annotation forms under the component they fold onto", () => {
  const byComponent = variantRendersByComponent([
    component("Fab", { reference: ref("1:2") }),
    overrideVariant("Fab", "l", { seeds: [{ key: "size", kind: "STRING", raw: "l" }] }),
    catalogVariant("FabLarge", "Fab", { props: [{ key: "size", value: "large" }] }),
  ]);
  assert.deepEqual(
    byComponent.get("Fab").map((r) => r.name),
    ["l", "large"],
  );
});

test("ignores a variant that names no axis, and non-light captures", () => {
  const byComponent = variantRendersByComponent([
    // Named, but says only "this is different" — nothing to look up in a kit.
    overrideVariant("Button", "special", { seeds: [] }),
    // A dark capture of a real variant: the light one already stands for it.
    {
      ...overrideVariant("Button", "l", { seeds: [{ key: "size", kind: "STRING", raw: "l" }] }),
      id: "com.example.CatalogKt.Button_Dark_VARIANT_l",
    },
    // A VARIANT-role dark capture, likewise.
    { ...catalogVariant("B", "Button", { state: "disabled" }), id: "com.example.CatalogKt.B_Dark" },
  ]);
  assert.equal(byComponent.size, 0);
});

test("emits variant declarations in a sidecar, unresolved", () => {
  const { map, variants, diagnostics } = projectDesignMap([
    component("Button", { reference: ref("1:2") }),
    overrideVariant("Button", "l", { seeds: [{ key: "size", kind: "STRING", raw: "l" }] }),
    overrideVariant("Button", "square", { seeds: [{ key: "shape", kind: "STRING", raw: "square" }] }),
  ]);

  // The map keeps the base ref as a plain string — resolving the variants is somebody else's job,
  // and a map that guessed at them would be worse than one that says nothing.
  assert.equal(map.components[0].ref, ref("1:2"));
  assert.equal(variants.schema, DESIGN_MAP_VARIANTS_SCHEMA);
  assert.deepEqual(variants.components, [
    {
      code: "catalog/Catalog.kt#Button",
      componentId: "Button",
      reference: ref("1:2"),
      basePreviewId: "com.example.CatalogKt.Button_Light",
      renders: [
        {
          previewId: "com.example.CatalogKt.Button_Light_VARIANT_l",
          name: "l",
          seeds: [{ key: "size", raw: "l" }],
        },
        {
          previewId: "com.example.CatalogKt.Button_Light_VARIANT_square",
          name: "square",
          seeds: [{ key: "shape", raw: "square" }],
        },
      ],
    },
  ]);
  assert.equal(diagnostics.variantRenders, 2);
});

test("declares no variants for a component with none, rather than an empty entry", () => {
  const { variants } = projectDesignMap([component("Button", { reference: ref("1:2") })]);
  assert.deepEqual(variants.components, []);
});

test("drops variants of a component that has no reference to hang them on", () => {
  // Without a base ref there is nothing for a resolver to walk from, so declaring the renders
  // would hand it a question it cannot answer.
  const { variants, diagnostics } = projectDesignMap([
    component("Button", {}),
    overrideVariant("Button", "l", { seeds: [{ key: "size", kind: "STRING", raw: "l" }] }),
  ]);
  assert.deepEqual(variants.components, []);
  assert.deepEqual(diagnostics.unmapped, ["Button"]);
});

test("counts the components naming a component set", () => {
  const { diagnostics } = projectDesignMap([
    component("A", { reference: ref("1:2"), referenceSet: ref("1:1") }),
    component("B", { reference: ref("2:2") }),
  ]);
  assert.equal(diagnostics.withSet, 1);
});

test("projects an empty manifest without complaint", () => {
  const { map, variants, diagnostics } = projectDesignMap([]);
  assert.deepEqual(map, { components: [] });
  assert.deepEqual(variants.components, []);
  assert.deepEqual(diagnostics.unmapped, []);
});
