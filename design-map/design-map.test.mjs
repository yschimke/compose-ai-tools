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

/** A dark-first catalog's component: one capture, drawn dark, so its id carries no mode segment. */
const darkOnly = (name, catalog, extra = {}) => ({
  id: `com.example.CatalogKt.${name}`,
  functionName: name,
  sourceFile: "Catalog.kt",
  catalog: { role: "COMPONENT", componentId: name, ...catalog },
  ...extra,
});

test("a dark-only catalog maps its sole capture, rather than projecting nothing", () => {
  // A Wear watch face is a black screen, so the component multipreview is a single dark capture and
  // no id ends in `_Light`. Demanding one projected the whole catalog to an empty map
  // (compose-ai-tools#4192).
  const { map, diagnostics } = projectDesignMap([
    darkOnly("FilledButton", { reference: ref("1:2") }),
    darkOnly("Card", { reference: ref("3:4") }),
  ]);
  assert.deepEqual(map.components.map((c) => c.previewId), [
    "com.example.CatalogKt.Card",
    "com.example.CatalogKt.FilledButton",
  ]);
  assert.deepEqual(diagnostics.ambiguousMode, []);
});

test("a dark-only catalog's variants declare against the same sole capture", () => {
  const { variants } = projectDesignMap([
    darkOnly("Button", { reference: ref("1:2") }),
    {
      ...overrideVariant("Button", "disabled", {
        booleans: [{ key: "enabled", raw: "false" }],
        props: [{ key: "enabled", value: "false" }],
      }),
      id: "com.example.CatalogKt.Button_VARIANT_disabled",
    },
  ]);
  assert.deepEqual(variants.components[0].basePreviewId, "com.example.CatalogKt.Button");
  assert.deepEqual(
    variants.components[0].renders.map((r) => r.previewId),
    ["com.example.CatalogKt.Button_VARIANT_disabled"],
  );
});

test("a named single mode counts as the sole capture too, whatever it is called", () => {
  const { map } = projectDesignMap([
    {
      ...component("FilledButton", { reference: ref("1:2") }),
      id: "com.example.CatalogKt.FilledButton_Dark",
    },
  ]);
  assert.deepEqual(map.components.map((c) => c.previewId), [
    "com.example.CatalogKt.FilledButton_Dark",
  ]);
});

test("several modes with no light among them are reported, not guessed at", () => {
  // Pairing `Dark` when the kit drew `Coral` diffs a whole palette, so neither is chosen.
  const dark = { ...component("Chip", { reference: ref("1:2") }), id: "com.example.CatalogKt.Chip_Dark" };
  const coral = { ...component("Chip", { reference: ref("1:2") }), id: "com.example.CatalogKt.Chip_Coral" };
  const { map, diagnostics } = projectDesignMap([dark, coral]);
  assert.deepEqual(map.components, []);
  assert.deepEqual(diagnostics.ambiguousMode, [
    {
      subject: "com.example.CatalogKt.Chip",
      componentIds: ["Chip"],
      modes: ["Coral", "Dark"],
    },
  ]);
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

test("variantSeeds turns an interaction variant into a state seed", () => {
  // `@OverrideVariant(interaction = Pressed)` seeds no knob — the renderer drives a real press
  // against the composed node. A kit models that as a value of the same `State` axis that carries
  // Enabled and Disabled, so it has to enter resolution as a `state` seed or the render declares
  // nothing and is dropped.
  for (const interaction of ["Hovered", "Focused", "Pressed"]) {
    assert.deepEqual(
      variantSeeds(overrideVariant("Button", interaction.toLowerCase(), { interaction })),
      [{ key: "state", raw: interaction.toLowerCase() }],
    );
  }
});

test("variantSeeds ignores the None interaction, which is the absence of one", () => {
  assert.deepEqual(variantSeeds(overrideVariant("Button", "x", { interaction: "None" })), []);
});

test("variantSeeds keeps an interaction beside the knobs a variant also seeds", () => {
  assert.deepEqual(
    variantSeeds(
      overrideVariant("Button", "l-pressed", {
        interaction: "Pressed",
        seeds: [{ key: "size", kind: "STRING", raw: "l" }],
      }),
    ),
    [
      { key: "size", raw: "l" },
      { key: "state", raw: "pressed" },
    ],
  );
});

test("variantSeeds does not double up when the variant already names a state", () => {
  assert.deepEqual(
    variantSeeds(
      overrideVariant("Button", "disabled", {
        interaction: "Pressed",
        seeds: [{ key: "state", kind: "STRING", raw: "disabled" }],
      }),
    ),
    [{ key: "state", raw: "disabled" }],
  );
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

test("ignores a variant that names no axis, and the dark half of a themed pair", () => {
  const size = { seeds: [{ key: "size", kind: "STRING", raw: "l" }] };
  const byComponent = variantRendersByComponent([
    // Named, but says only "this is different" — nothing to look up in a kit.
    overrideVariant("Button", "special", { seeds: [] }),
    // A dark capture of a real variant: the light one it is published beside stands for it.
    overrideVariant("Button", "l", size),
    {
      ...overrideVariant("Button", "l", size),
      id: "com.example.CatalogKt.Button_Dark_VARIANT_l",
    },
    // A VARIANT-role dark capture, likewise — its own composable, its own light sibling.
    catalogVariant("B", "Button", { state: "disabled" }),
    { ...catalogVariant("B", "Button", { state: "disabled" }), id: "com.example.CatalogKt.B_Dark" },
  ]);
  assert.deepEqual(
    byComponent.get("Button").map((r) => r.previewId),
    ["com.example.CatalogKt.Button_Light_VARIANT_l", "com.example.CatalogKt.B_Light"],
  );
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

/** A `@CatalogVariant` render carrying an `@OverrideVariant` cell of its own. */
const foldedCell = (name, of, catalog, variant, overrides) => ({
  id: `com.example.CatalogKt.${name}_Light_VARIANT_${variant}`,
  functionName: name,
  sourceFile: "Catalog.kt",
  catalog: { role: "VARIANT", componentId: of, ...catalog },
  overrides: { name: variant, ...overrides },
});

test("variantSeeds crosses a fold's axis with its own override cell", () => {
  assert.deepEqual(
    variantSeeds(
      foldedCell("CircularWavy", "Progress/Circular", { props: [{ key: "type", value: "wave" }] }, "full", {
        seeds: [{ key: "progress", kind: "FLOAT", raw: "1.0" }],
      }),
    ),
    [
      { key: "type", raw: "wave" },
      { key: "progress", raw: "1.0" },
    ],
  );
});

test("variantSeeds lets the override cell win a key collision with the fold", () => {
  assert.deepEqual(
    variantSeeds(
      foldedCell("V", "Button", { state: "disabled" }, "error", {
        seeds: [{ key: "state", kind: "STRING", raw: "error" }],
      }),
    ),
    [{ key: "state", raw: "error" }],
  );
});

test("a folded component's cells are collected under its parent, named for both axes", () => {
  const byComponent = variantRendersByComponent([
    component("CircularProgress", { reference: ref("1:2") }),
    catalogVariant("CircularWavy", "CircularProgress", { props: [{ key: "type", value: "wave" }] }),
    foldedCell("CircularWavy", "CircularProgress", { props: [{ key: "type", value: "wave" }] }, "full", {
      seeds: [{ key: "progress", kind: "FLOAT", raw: "1.0" }],
    }),
  ]);
  assert.deepEqual(
    byComponent.get("CircularProgress").map((r) => r.name),
    ["wave", "wave-full"],
  );
});

test("a folded component's DARK cell is still ignored, like every other dark capture", () => {
  const cell = foldedCell(
    "W",
    "Button",
    { props: [{ key: "type", value: "wave" }] },
    "full",
    { seeds: [{ key: "progress", kind: "FLOAT", raw: "1.0" }] },
  );
  const byComponent = variantRendersByComponent([
    cell,
    { ...cell, id: "com.example.CatalogKt.W_Dark_VARIANT_full" },
  ]);
  assert.deepEqual(
    byComponent.get("Button").map((r) => r.previewId),
    ["com.example.CatalogKt.W_Light_VARIANT_full"],
  );
});

test("variantSeeds carries a cell's declared kit axis and value onto its seed", () => {
  assert.deepEqual(
    variantSeeds(
      overrideVariant("DatePicker", "range", {
        seeds: [{ key: "type", raw: "range" }],
        kitAxis: "Type",
        kitValue: "Full-screen (range)",
      }),
    ),
    [{ key: "type", raw: "range", kitAxis: "Type", kitValue: "Full-screen (range)" }],
  );
});

test("variantSeeds carries a CatalogVariant's declaration onto its prop", () => {
  assert.deepEqual(
    variantSeeds(
      catalogVariant("DatePickerRange", "DatePicker", {
        props: [{ key: "type", value: "range" }],
        kitAxis: "Type",
        kitValue: "Full-screen (range)",
      }),
    ),
    [{ key: "type", raw: "range", kitAxis: "Type", kitValue: "Full-screen (range)" }],
  );
});

test("either kit name stands alone — an axis without a value, a value without an axis", () => {
  assert.deepEqual(
    variantSeeds(
      overrideVariant("ListItem", "avatar", {
        seeds: [{ key: "content", raw: "avatar" }],
        kitAxis: "Show avatar",
      }),
    ),
    [{ key: "content", raw: "avatar", kitAxis: "Show avatar" }],
  );
  assert.deepEqual(
    variantSeeds(
      overrideVariant("Carousel", "hero", {
        seeds: [{ key: "layout", raw: "hero" }],
        kitValue: "Center-aligned hero",
      }),
    ),
    [{ key: "layout", raw: "hero", kitValue: "Center-aligned hero" }],
  );
});

test("a component's kitAxis is a default its cells inherit, and each cell can override", () => {
  const previews = [
    component("ListItem", { reference: ref("1:2"), kitAxis: "Show avatar" }),
    overrideVariant("ListItem", "avatar", { seeds: [{ key: "content", raw: "avatar" }] }),
    overrideVariant("ListItem", "icon", {
      seeds: [{ key: "content", raw: "icon" }],
      kitAxis: "Show icon",
    }),
  ];
  // A cell render is `base.copy(id = …, overrides = spec)`, so it carries the SAME catalog entry
  // as its component — which is how a component-level default reaches a cell at all.
  for (const preview of previews.slice(1)) preview.catalog.kitAxis = "Show avatar";
  const { variants } = projectDesignMap(previews);
  assert.deepEqual(
    variants.components[0].renders.map((r) => r.seeds[0].kitAxis),
    ["Show avatar", "Show icon"],
  );
});

test("a declaration with more than one knob to sit on is reported, not guessed at", () => {
  // Which of the two knobs does `Type` name? The annotation carries one pair per cell and cannot
  // say, and picking one would pin the wrong axis — a confident reference to the wrong node.
  const { variants, diagnostics } = projectDesignMap([
    component("DatePicker", { reference: ref("1:2") }),
    overrideVariant("DatePicker", "range-input", {
      seeds: [
        { key: "type", raw: "range" },
        { key: "mode", raw: "input" },
      ],
      kitAxis: "Type",
    }),
  ]);
  assert.deepEqual(diagnostics.unplacedDeclarations, [
    {
      previewId: "com.example.CatalogKt.DatePicker_Light_VARIANT_range-input",
      kitAxis: "Type",
      kitValue: undefined,
      seeds: ["type", "mode"],
    },
  ]);
  // The seeds still resolve on their own spelling; only the declaration is dropped.
  assert.deepEqual(variants.components[0].renders[0].seeds, [
    { key: "type", raw: "range" },
    { key: "mode", raw: "input" },
  ]);
});

test("a component default that cannot be placed is silent — a default need not cover everything", () => {
  const { diagnostics } = projectDesignMap([
    component("DatePicker", { reference: ref("1:2"), kitAxis: "Type" }),
    overrideVariant("DatePicker", "range-input", {
      seeds: [
        { key: "type", raw: "range" },
        { key: "mode", raw: "input" },
      ],
    }),
  ]);
  assert.deepEqual(diagnostics.unplacedDeclarations, []);
});

test("declared kit names reach the sidecar, which is the only reader that matters", () => {
  const { variants } = projectDesignMap([
    component("DatePicker", { reference: ref("1:2") }),
    overrideVariant("DatePicker", "range", {
      seeds: [{ key: "type", raw: "range" }],
      kitAxis: "Type",
      kitValue: "Full-screen (range)",
    }),
  ]);
  assert.deepEqual(variants.components[0].renders[0].seeds, [
    { key: "type", raw: "range", kitAxis: "Type", kitValue: "Full-screen (range)" },
  ]);
});

test("a cell that names only its exceptional value inherits the component's axis", () => {
  // The documented shape of the component-level default: the component names the kit property
  // once, and a cell only has to say which of its values it sits at.
  const previews = [
    component("ListItem", { reference: ref("1:2"), kitAxis: "Show avatar" }),
    overrideVariant("ListItem", "avatar", {
      seeds: [{ key: "content", raw: "avatar" }],
      kitValue: "True",
    }),
  ];
  previews[1].catalog.kitAxis = "Show avatar";
  const { variants } = projectDesignMap(previews);
  assert.deepEqual(variants.components[0].renders[0].seeds, [
    { key: "content", raw: "avatar", kitAxis: "Show avatar", kitValue: "True" },
  ]);
});

test("a driven interaction does not make a one-knob cell's declaration ambiguous", () => {
  // The `state` seed comes from the harness, not from the annotation, so it is not one of the
  // knobs the declaration might be naming. Counting it would report a cell whose annotation is
  // perfectly clear about its single seeded knob as unplaceable.
  const { variants, diagnostics } = projectDesignMap([
    component("Button", { reference: ref("1:2") }),
    overrideVariant("Button", "l-pressed", {
      seeds: [{ key: "size", raw: "l" }],
      interaction: "Pressed",
      kitAxis: "Size",
      kitValue: "Large",
    }),
  ]);
  assert.deepEqual(diagnostics.unplacedDeclarations, []);
  assert.deepEqual(variants.components[0].renders[0].seeds, [
    { key: "size", raw: "l", kitAxis: "Size", kitValue: "Large" },
    { key: "state", raw: "pressed" },
  ]);
});

test("an interaction-only cell's declaration lands on the state seed it does have", () => {
  const { variants } = projectDesignMap([
    component("Button", { reference: ref("1:2") }),
    overrideVariant("Button", "pressed", {
      interaction: "Pressed",
      kitAxis: "State",
      kitValue: "Presssed",
    }),
  ]);
  assert.deepEqual(variants.components[0].renders[0].seeds, [
    { key: "state", raw: "pressed", kitAxis: "State", kitValue: "Presssed" },
  ]);
});

test("a cell overriding the fold's own knob keeps the fold's kit axis, not its value", () => {
  // The cell wins the value; the AXIS is a fact about the knob and survives it. Losing it here
  // would strand the one name a resolver cannot work out for itself — and silently, since the
  // declaration was placed perfectly well before the merge dropped its seed.
  const preview = {
    id: "com.example.CatalogKt.ProgressWave_Light_VARIANT_error",
    functionName: "ProgressWave",
    sourceFile: "Catalog.kt",
    catalog: {
      role: "VARIANT",
      componentId: "Progress/Circular",
      props: [{ key: "state", value: "disabled" }],
      kitAxis: "Interaction state",
      kitValue: "Disabled",
    },
    overrides: { name: "error", seeds: [{ key: "state", raw: "error" }] },
  };
  assert.deepEqual(variantSeeds(preview), [
    { key: "state", raw: "error", kitAxis: "Interaction state" },
  ]);
});

// BREAKPOINTS. A Wear multipreview draws one composable at several screen sizes, which produces
// several captures of it told apart by the same id segment a themed pair uses. Read as colour
// modes they are unresolvable and the component drops out of the map entirely — which is what
// happened to every full-screen component of a Wear catalog the moment it gained a second size.
const atWidth = (name, widthDp, catalog = {}) => ({
  id: `com.example.CatalogKt.${name}_wearos_${widthDp}`,
  functionName: name,
  sourceFile: "Catalog.kt",
  params: { device: `id:wearos_${widthDp}`, widthDp },
  catalog: { role: "COMPONENT", componentId: name, ...catalog },
});

test("a breakpoint fan-out maps from its narrowest capture, not as an ambiguous mode", () => {
  const { map, diagnostics } = projectDesignMap([
    atWidth("Picker", 227, { reference: ref("1:2") }),
    atWidth("Picker", 192, { reference: ref("1:2") }),
    atWidth("Picker", 240, { reference: ref("1:2") }),
  ]);
  assert.deepEqual(diagnostics.ambiguousMode, []);
  assert.equal(map.components.length, 1);
  assert.match(map.components[0].previewId, /_wearos_192$/);
});

test("--base-breakpoint names the width the kit draws, when it is not the narrowest", () => {
  const previews = [
    atWidth("Picker", 192, { reference: ref("1:2") }),
    atWidth("Picker", 225, { reference: ref("1:2") }),
  ];
  const { map } = projectDesignMap(previews, { baseBreakpointDp: 225 });
  assert.match(map.components[0].previewId, /_wearos_225$/);
});

test("a named base this composable does not render falls back rather than dropping it", () => {
  const { map, diagnostics } = projectDesignMap(
    [
      atWidth("Picker", 192, { reference: ref("1:2") }),
      atWidth("Picker", 240, { reference: ref("1:2") }),
    ],
    { baseBreakpointDp: 225 },
  );
  assert.deepEqual(diagnostics.ambiguousMode, []);
  assert.match(map.components[0].previewId, /_wearos_192$/);
});

test("the sizes the base did not take fold under it as cells, seeded with their width", () => {
  const { variants } = projectDesignMap([
    atWidth("Picker", 192, { reference: ref("1:2") }),
    atWidth("Picker", 225, { reference: ref("1:2") }),
    atWidth("Picker", 240, { reference: ref("1:2") }),
  ]);
  const renders = variants.components[0].renders;
  assert.deepEqual(
    renders.map((r) => r.name).sort(),
    ["225dp", "240dp"],
  );
  assert.deepEqual(renders.find((r) => r.name === "225dp").seeds, [
    { key: "breakpoint", raw: "225" },
  ]);
});

test("a themed pair is still a mode, never a breakpoint — neither capture names a device", () => {
  const { diagnostics } = projectDesignMap([
    { ...component("Themed", { reference: ref("1:2") }), id: "com.example.CatalogKt.Themed_Dark" },
    { ...component("Themed", { reference: ref("1:2") }), id: "com.example.CatalogKt.Themed_Coral" },
  ]);
  assert.equal(diagnostics.ambiguousMode.length, 1);
  assert.deepEqual(diagnostics.ambiguousMode[0].modes, ["Coral", "Dark"]);
});

test("same-width captures are a mode, not a size — two devices of one width cannot be ordered", () => {
  const square = {
    id: "com.example.CatalogKt.Screen_wearos_square",
    functionName: "Screen",
    sourceFile: "Catalog.kt",
    params: { device: "id:wearos_square", widthDp: 192 },
    catalog: { role: "COMPONENT", componentId: "Screen", reference: ref("1:2") },
  };
  const { diagnostics } = projectDesignMap([atWidth("Screen", 192, { reference: ref("1:2") }), square]);
  assert.equal(diagnostics.ambiguousMode.length, 1);
});

test("an override cell rides the base breakpoint, and is not repeated at every size", () => {
  const cell = (widthDp) => ({
    id: `com.example.CatalogKt.Picker_wearos_${widthDp}_VARIANT_month`,
    functionName: "Picker",
    sourceFile: "Catalog.kt",
    params: { device: `id:wearos_${widthDp}`, widthDp },
    catalog: { role: "COMPONENT", componentId: "Picker" },
    overrides: { name: "month", seeds: [{ key: "order", raw: "month" }] },
  });
  const { variants } = projectDesignMap([
    atWidth("Picker", 192, { reference: ref("1:2") }),
    atWidth("Picker", 240, { reference: ref("1:2") }),
    cell(192),
    cell(240),
  ]);
  assert.deepEqual(
    variants.components[0].renders.map((r) => r.name).sort(),
    ["240dp", "month"],
  );
});
