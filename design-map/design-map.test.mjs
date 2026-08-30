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

test("a folded variant's stated absence is reported under the variant, not its parent", () => {
  // Scanning components alone meant folding a render under a parent silently dropped its stated
  // absence: a catalog could lose an audit signal by restructuring, which is what `statedAbsent`
  // exists to prevent. The parent keeps its own reference and stays out of the report.
  const { diagnostics } = projectDesignMap([
    component("Button", { reference: ref("1:2") }),
    catalogVariant("ButtonIconOnly", "Button", {
      state: "icon-only",
      noReference: "the kit exports no Text=No cell for this set",
    }),
  ]);
  assert.deepEqual(diagnostics.statedAbsent, [
    {
      componentId: "Button [state=icon-only]",
      reason: "the kit exports no Text=No cell for this set",
    },
  ]);
  assert.deepEqual(diagnostics.unmapped, []);
});

test("two variants sharing a state but differing in props keep both absences", () => {
  // The label is a Map key. `variantName` narrows to the state alone whenever there is one, so
  // building the label from it collided these two and silently dropped one of the reasons --
  // losing exactly the record this diagnostic exists to keep. The full seed vector distinguishes
  // them.
  const { diagnostics } = projectDesignMap([
    component("Button", { reference: ref("1:2") }),
    catalogVariant("ButtonDisabledIcon", "Button", {
      state: "disabled",
      props: [{ key: "content", value: "icon-only" }],
      noReference: "no disabled icon-only cell",
    }),
    catalogVariant("ButtonDisabledText", "Button", {
      state: "disabled",
      props: [{ key: "content", value: "text-only" }],
      noReference: "no disabled text-only cell",
    }),
  ]);
  assert.equal(diagnostics.statedAbsent.length, 2);
  assert.deepEqual(
    diagnostics.statedAbsent.map((s) => s.reason).sort(),
    ["no disabled icon-only cell", "no disabled text-only cell"],
  );
});

test("a prop value containing its own key=value text does not collide with two props", () => {
  // Discovery splits an annotation prop at its FIRST `=` only, so `props = ["a=b c=d"]` is ONE
  // prop whose value is `b c=d`. Joined for display it is indistinguishable from the two props
  // `a=b` and `c=d`. The label is therefore not safe as a Map key -- these two variants must both
  // survive, which they only do because the key is the capture subject.
  const { diagnostics } = projectDesignMap([
    component("Button", { reference: ref("1:2") }),
    catalogVariant("OneProp", "Button", {
      props: [{ key: "a", value: "b c=d" }],
      noReference: "one prop whose value looks like two",
    }),
    catalogVariant("TwoProps", "Button", {
      props: [
        { key: "a", value: "b" },
        { key: "c", value: "d" },
      ],
      noReference: "genuinely two props",
    }),
  ]);
  assert.equal(diagnostics.statedAbsent.length, 2);
  assert.deepEqual(
    diagnostics.statedAbsent.map((s) => s.reason).sort(),
    ["genuinely two props", "one prop whose value looks like two"],
  );
});

test("a seedless variant names itself, not its parent", () => {
  // `state` and `props` are both optional, so a variant can declare `noReference` and name no axis.
  // Falling back to the bare parent id published "Button - <reason>" for a parent that holds a
  // perfectly good reference: a finding about the wrong thing, with no clue which variant meant it.
  const { diagnostics } = projectDesignMap([
    component("Button", { reference: ref("1:2") }),
    catalogVariant("SpecialButton", "Button", { noReference: "the kit never drew this one" }),
  ]);
  assert.deepEqual(diagnostics.statedAbsent, [
    { componentId: "Button [SpecialButton]", reason: "the kit never drew this one" },
  ]);
  // The parent keeps its reference and stays out of both buckets.
  assert.deepEqual(diagnostics.unmapped, []);
});

test("a component id spelled like a capture subject does not collide with a variant", () => {
  // Component ids are free-form, so one may legally be spelled like a capture subject. Sharing a
  // map between the two namespaces without tagging the domain is the same collision one namespace
  // over -- either reason overwriting the other, or an unexplained component skipping `unmapped`.
  const subjectShaped = "com.example.CatalogKt.IconOnly";
  const { diagnostics } = projectDesignMap([
    component("Button", { reference: ref("1:2") }),
    component(subjectShaped, { noReference: "component that is named like a subject" }),
    catalogVariant("IconOnly", "Button", {
      state: "icon-only",
      noReference: "variant whose subject is that name",
    }),
  ]);
  assert.equal(diagnostics.statedAbsent.length, 2);
  assert.deepEqual(
    diagnostics.statedAbsent.map((s) => s.reason).sort(),
    ["component that is named like a subject", "variant whose subject is that name"],
  );
});

test("a stated-absent variant's ambiguous modes are suppressed even when its parent has a reference", () => {
  // The ambiguity filter matches on componentId, which for a VARIANT is the PARENT's -- so a
  // variant's own stated absence could not suppress its own record, and a parent carrying a good
  // reference left it standing. `--strict --allow-stated-absence` then failed on precisely the
  // case that flag exists to accept. A variant render has its own capture subject.
  const dark = {
    ...catalogVariant("ButtonIconOnly", "Button", {
      state: "icon-only",
      noReference: "the kit exports no Text=No cell",
    }),
    id: "com.example.CatalogKt.ButtonIconOnly_Dark",
  };
  const coral = {
    ...catalogVariant("ButtonIconOnly", "Button", {
      state: "icon-only",
      noReference: "the kit exports no Text=No cell",
    }),
    id: "com.example.CatalogKt.ButtonIconOnly_Coral",
  };
  const { diagnostics } = projectDesignMap([
    component("Button", { reference: ref("1:2") }),
    dark,
    coral,
  ]);
  assert.equal(diagnostics.statedAbsent.length, 1);
  assert.deepEqual(diagnostics.ambiguousMode, []);
});

test("a variant that says nothing about the kit is not reported as unmapped", () => {
  // Silence under a parent is the parent's business: an ordinary state variant has never had a
  // reference and reporting one per fold would drown the signal it is meant to carry.
  const { diagnostics } = projectDesignMap([
    component("Button", { reference: ref("1:2") }),
    catalogVariant("ButtonPressed", "Button", { state: "pressed" }),
  ]);
  assert.deepEqual(diagnostics.statedAbsent, []);
  assert.deepEqual(diagnostics.unmapped, []);
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

test("kitProps declares a whole kit assignment for a cell that turns several knobs", () => {
  // The Wear kit's `Button` set has no `Icon=Yes, Icon size=n/a, Alignment=Center` node — turning
  // Icon on drags the other two with it — so the cell that lands on a real node seeds three knobs,
  // and a single kitAxis has no way to say which one it names.
  assert.deepEqual(
    variantSeeds(
      overrideVariant("Button/Filled", "icon-large", {
        seeds: [
          { key: "icon", raw: "true" },
          { key: "iconSize", raw: "lrg-32" },
          { key: "alignment", raw: "left" },
        ],
        kitProps: [
          { key: "Icon", value: "Yes" },
          { key: "Icon size", value: "Lrg 32" },
          { key: "Alignment", value: "Left" },
        ],
      }),
    ),
    [
      { key: "Icon", raw: "Yes", kitAxis: "Icon", kitValue: "Yes" },
      { key: "Icon size", raw: "Lrg 32", kitAxis: "Icon size", kitValue: "Lrg 32" },
      { key: "Alignment", raw: "Left", kitAxis: "Alignment", kitValue: "Left" },
    ],
  );
});

test("kitProps REPLACES the knob seeds rather than joining them", () => {
  // Load-bearing, not tidiness: a resolver has to place every seed it is given, so one knob seed
  // that aliases to nothing would kill a cell whose declaration is complete and correct.
  const seeds = variantSeeds(
    overrideVariant("Button/Filled", "left", {
      seeds: [{ key: "alignment", raw: "left" }],
      kitProps: [{ key: "Alignment", value: "Left" }],
    }),
  );
  assert.deepEqual(seeds, [
    { key: "Alignment", raw: "Left", kitAxis: "Alignment", kitValue: "Left" },
  ]);
  assert.equal(
    seeds.some((s) => s.key === "alignment"),
    false,
  );
});

test("a cell with no kitProps is untouched — every variant written before the field", () => {
  assert.deepEqual(
    variantSeeds(
      overrideVariant("Switch", "off", { seeds: [{ key: "checked", raw: "false" }] }),
    ),
    [{ key: "checked", raw: "false" }],
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

test("a breakpoint cell names its kit axis when the component declares one", () => {
  // #4827. The kit's `Picker` set publishes `Larger Screen (BP)=Yes` cells, and the catalog already
  // renders the picture — it was just handed to the resolver as a bare `breakpoint=225`, a value no
  // kit vocabulary contains, so 21 published kit cells sat uncompared.
  const { variants } = projectDesignMap([
    atWidth("Picker", 192, { reference: ref("1:2") }),
    atWidth("Picker", 225, {
      reference: ref("1:2"),
      breakpointKit: ["225=Larger Screen (BP)=Yes"],
    }),
  ]);
  assert.deepEqual(variants.components[0].renders.find((r) => r.name === "225dp").seeds, [
    { key: "breakpoint", raw: "225", kitAxis: "Larger Screen (BP)", kitValue: "Yes" },
  ]);
});

test("an undeclared size keeps its bare seed, so it is reported unresolved rather than mispaired", () => {
  // The majority case: a kit that draws every screen cell at one size has no size axis at all, and
  // the other unresolved breakpoint captures are CORRECTLY unresolved. Guessing an axis for them
  // would pair a render against a kit cell that does not describe it.
  const { variants } = projectDesignMap([
    atWidth("Picker", 192, { reference: ref("1:2") }),
    atWidth("Picker", 240, {
      reference: ref("1:2"),
      breakpointKit: ["225=Larger Screen (BP)=Yes"],
    }),
  ]);
  assert.deepEqual(variants.components[0].renders.find((r) => r.name === "240dp").seeds, [
    { key: "breakpoint", raw: "240" },
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

test("a component named like a namespaced absence key still reports as unmapped", () => {
  // `statedAbsentIds` is keyed `component:<id>` / `subject:<id>`, and a `@CatalogComponent(id = …)`
  // may legally be spelled the same way. Testing the bare id against that map read the FIRST
  // component's entry as the SECOND's, and dropped the second from `unmapped` — `--strict` then
  // passed over a component with no reference and no stated reason.
  const { diagnostics } = projectDesignMap([
    component("Icon", { componentId: "X", noReference: "retired in the kit" }),
    component("Chip", { componentId: "component:X" }),
  ]);
  assert.deepEqual(diagnostics.unmapped, ["component:X"]);
  assert.deepEqual(
    diagnostics.statedAbsent,
    [{ componentId: "X", reason: "retired in the kit" }],
  );
});

test("a function whose own name contains _VARIANT_ is a base capture, not a reseed", () => {
  // `Icon_VARIANT_Only` is a legal Kotlin name, so its base id carries the marker discovery uses
  // for a generated reseed. Read as a reseed, the component was skipped everywhere the marker
  // gates — losing its explicit `noReference`, the one record that diagnostic exists to keep.
  const marked = {
    id: "com.example.CatalogKt.Icon_VARIANT_Only_Light",
    functionName: "Icon_VARIANT_Only",
    sourceFile: "Catalog.kt",
    catalog: {
      role: "COMPONENT",
      componentId: "Icon_VARIANT_Only",
      noReference: "the kit draws no icon-only cell",
    },
  };
  const { diagnostics } = projectDesignMap([marked]);
  assert.deepEqual(diagnostics.statedAbsent, [
    { componentId: "Icon_VARIANT_Only", reason: "the kit draws no icon-only cell" },
  ]);
  // And its reseeds are still reseeds: the marker discovery appended is the LAST one.
  const { variants } = projectDesignMap([
    { ...marked, catalog: { ...marked.catalog, noReference: undefined, reference: ref("1:2") } },
    {
      id: "com.example.CatalogKt.Icon_VARIANT_Only_Light_VARIANT_pressed",
      functionName: "Icon_VARIANT_Only",
      sourceFile: "Catalog.kt",
      catalog: { role: "COMPONENT", componentId: "Icon_VARIANT_Only" },
      overrides: { name: "pressed", seeds: [{ key: "state", raw: "pressed" }] },
    },
  ]);
  assert.deepEqual(
    variants.components[0].renders.map((r) => r.name),
    ["pressed"],
  );
});

test("a variant that states its absence is not handed to the parent's resolver", () => {
  // The declaration resolves against the PARENT's reference, so emitting a render whose annotation
  // says the kit exports no cell for it scores it against the parent's picture — the mispairing
  // `noReference` exists to prevent. Reported once, as a stated absence, and never paired.
  const { variants, diagnostics } = projectDesignMap([
    component("Button", { reference: ref("1:2") }),
    catalogVariant("ButtonTextless", "Button", {
      state: "textless",
      noReference: "the kit exports no Text=No cell",
    }),
  ]);
  assert.deepEqual(variants.components, []);
  assert.deepEqual(diagnostics.statedAbsent, [
    { componentId: "Button [state=textless]", reason: "the kit exports no Text=No cell" },
  ]);
});

test("a variant's own reference rides its render into the sidecar", () => {
  const { variants } = projectDesignMap([
    component("Button", { reference: ref("1:2") }),
    catalogVariant("ButtonCompact", "Button", { state: "compact", reference: ref("9:9") }),
    catalogVariant("ButtonLoud", "Button", { state: "loud" }),
  ]);
  assert.deepEqual(
    variants.components[0].renders.map((r) => [r.name, r.reference]),
    [
      ["compact", ref("9:9")],
      ["loud", undefined],
    ],
  );
});
