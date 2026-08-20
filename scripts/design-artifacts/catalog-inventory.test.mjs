import { test } from "node:test";
import assert from "node:assert/strict";

import {
  applyGroupOrder,
  inventoryFromPreviews,
  mergeCatalogGroups,
} from "./catalog-inventory.mjs";

// A preview record as it appears in previews.json: a function name plus the
// discovery-resolved `catalog` identity (light/dark multipreviews share both).
const component = (functionName, catalog) => ({
  functionName,
  catalog: { role: "COMPONENT", ...catalog },
});
const variant = (functionName, catalog) => ({
  functionName,
  catalog: { role: "VARIANT", ...catalog },
});

// --- inventoryFromPreviews ----------------------------------------------------

test("inventoryFromPreviews builds one component per COMPONENT entry, keyed on function name", () => {
  const { groups } = inventoryFromPreviews([
    component("FilledButton", {
      componentId: "Button/Filled",
      group: "Buttons",
      caption: "Primary action.",
      parallel: "FilledButton",
    }),
    component("OutlinedButton", { componentId: "Button/Outlined", group: "Buttons" }),
  ]);
  assert.equal(groups.length, 1);
  assert.equal(groups[0].name, "Buttons");
  assert.deepEqual(groups[0].components, [
    {
      componentId: "Button/Filled",
      preview: "FilledButton",
      caption: "Primary action.",
      parallel: "FilledButton",
    },
    { componentId: "Button/Outlined", preview: "OutlinedButton" },
  ]);
});

test("inventoryFromPreviews carries motionPreview, and omits it when unset", () => {
  // The wiring for motion authored on its own function. Motion is collected per component, so a
  // recording nothing names publishes nowhere — this field is how a component claims one without
  // the annotation having to sit on the sticker (where an @OverrideVariant fan-out would duplicate
  // it, and where the sticker's cropped canvas is the wrong size for a GIF).
  const { groups } = inventoryFromPreviews([
    component("SwitchButtonSticker", {
      componentId: "Toggles/Switch",
      group: "Toggles",
      motionPreview: "SwitchTransitionMotion",
    }),
    component("FilledButton", { componentId: "Button/Filled", group: "Toggles" }),
  ]);
  assert.deepEqual(groups[0].components, [
    {
      componentId: "Toggles/Switch",
      preview: "SwitchButtonSticker",
      motionPreview: "SwitchTransitionMotion",
    },
    { componentId: "Button/Filled", preview: "FilledButton" },
  ]);
});

test("a spec motionPreview wins over the annotation's, like every other spec field", () => {
  const annotation = [
    {
      name: "Toggles",
      components: [
        {
          componentId: "Toggles/Switch",
          preview: "SwitchButtonSticker",
          motionPreview: "SwitchTransitionMotion",
        },
      ],
    },
  ];
  const spec = [
    {
      name: "Toggles",
      components: [
        {
          componentId: "Toggles/Switch",
          preview: "SwitchButtonSticker",
          motionPreview: "SwitchSettleMotion",
        },
      ],
    },
  ];

  const merged = mergeCatalogGroups(annotation, spec);

  assert.equal(merged[0].components[0].motionPreview, "SwitchSettleMotion");
});

test("inventoryFromPreviews carries both seed-kit handles, and omits an absent referenceSet", () => {
  // `reference` is the one node parity diffs against; `referenceSet` is the family a screen's
  // sibling variant matches through. Both must reach the exported inventory, and a component
  // that names only the variant must look exactly as it did before the field existed.
  const { groups } = inventoryFromPreviews([
    component("ListItemSticker", {
      componentId: "Lists/ListItem",
      group: "Lists",
      reference: "figma:AbCdEf/51964:64241",
      referenceSet: "figma:AbCdEf/51964:63037",
    }),
    component("OutlinedButton", {
      componentId: "Button/Outlined",
      group: "Lists",
      reference: "figma:AbCdEf/10:5",
    }),
  ]);
  assert.deepEqual(groups[0].components, [
    {
      componentId: "Lists/ListItem",
      preview: "ListItemSticker",
      reference: "figma:AbCdEf/51964:64241",
      referenceSet: "figma:AbCdEf/51964:63037",
    },
    {
      componentId: "Button/Outlined",
      preview: "OutlinedButton",
      reference: "figma:AbCdEf/10:5",
    },
  ]);
});

test("inventoryFromPreviews carries a stated absence of reference, distinct from an unaudited one", () => {
  // Both of these components export no `reference`. The difference is that one has been looked at
  // and the kit has nothing worth pointing to — which is exactly what `noReference` records, and
  // is unrecoverable downstream if the inventory drops it here.
  const { groups } = inventoryFromPreviews([
    component("ScaffoldSticker", {
      componentId: "Layout/Scaffold",
      group: "Layout",
      noReference: "Kit retired this pattern in the 2025 refresh.",
    }),
    component("UnauditedSticker", { componentId: "Layout/Unaudited", group: "Layout" }),
  ]);
  assert.deepEqual(groups[0].components, [
    {
      componentId: "Layout/Scaffold",
      preview: "ScaffoldSticker",
      noReference: "Kit retired this pattern in the 2025 refresh.",
    },
    { componentId: "Layout/Unaudited", preview: "UnauditedSticker" },
  ]);
});

test("inventoryFromPreviews defaults the group to Components and carries section", () => {
  const { groups } = inventoryFromPreviews([
    component("Foo", { componentId: "Foo" }),
    component("Bar", { componentId: "Bar", group: "Widgets", section: "Components" }),
  ]);
  assert.deepEqual(
    groups.map((g) => [g.name, g.section]),
    [
      ["Components", undefined],
      ["Widgets", "Components"],
    ],
  );
});

test("inventoryFromPreviews carries the Figma contents-only annotation", () => {
  const { groups } = inventoryFromPreviews([
    {
      functionName: "FilledCard",
      catalog: {
        role: "COMPONENT",
        componentId: "Card/Filled",
        referenceContentsOnly: false,
      },
    },
  ]);

  assert.equal(groups[0].components[0].referenceContentsOnly, false);
});

test("inventoryFromPreviews dedupes a light/dark multipreview (same function, two records)", () => {
  const { groups } = inventoryFromPreviews([
    component("FilledButton", { componentId: "Button/Filled", group: "Buttons" }),
    component("FilledButton", { componentId: "Button/Filled", group: "Buttons" }),
  ]);
  assert.equal(groups[0].components.length, 1);
});

test("inventoryFromPreviews folds VARIANT entries under their parent by componentId", () => {
  const { groups, orphanVariants } = inventoryFromPreviews([
    component("FilledButton", { componentId: "Button/Filled", group: "Buttons" }),
    variant("FilledButtonPressed", {
      componentId: "Button/Filled",
      state: "pressed",
      caption: "Held press.",
    }),
    variant("FilledButtonIconLabel", {
      componentId: "Button/Filled",
      props: [{ key: "content", value: "icon+label" }],
    }),
  ]);
  assert.deepEqual(orphanVariants, []);
  assert.deepEqual(groups[0].components[0].variants, [
    { preview: "FilledButtonPressed", state: "pressed", caption: "Held press." },
    { preview: "FilledButtonIconLabel", props: { content: "icon+label" } },
  ]);
});

test("inventoryFromPreviews reports a variant whose parent component is absent", () => {
  const { groups, orphanVariants } = inventoryFromPreviews([
    variant("FilledButtonPressed", { componentId: "Button/Filled", state: "pressed" }),
  ]);
  assert.deepEqual(groups, []);
  assert.deepEqual(orphanVariants, [{ parentId: "Button/Filled", preview: "FilledButtonPressed" }]);
});

test("inventoryFromPreviews ignores previews with no catalog metadata", () => {
  const { groups } = inventoryFromPreviews([
    { functionName: "SomeHelperPreview" },
    component("Foo", { componentId: "Foo" }),
  ]);
  assert.equal(groups.length, 1);
  assert.equal(groups[0].components.length, 1);
});

test("inventoryFromPreviews (primary ++ extra-render previews): extra-only component appended, shared deduped", () => {
  // The generator concatenates the primary bundle's previews with the
  // `--extra-renders` supplement's before deriving the inventory, so an annotated
  // component that lives only in the supplement still enters spec.groups, and a
  // component in both dedupes to the primary (listed first).
  const primary = [component("FilledButton", { componentId: "Button/Filled", group: "Buttons" })];
  const extra = [
    component("FilledButton", { componentId: "Button/Filled", group: "Buttons" }), // override render, same component
    component("FocusRing", { componentId: "Button/FocusRing", group: "Buttons" }), // extra-only component
  ];
  const { groups } = inventoryFromPreviews([...primary, ...extra]);
  assert.deepEqual(
    groups[0].components.map((c) => c.componentId),
    ["Button/Filled", "Button/FocusRing"],
  );
});

test("inventoryFromPreviews keeps first-seen group and component order", () => {
  const { groups } = inventoryFromPreviews([
    component("A", { componentId: "A", group: "Second" }),
    component("B", { componentId: "B", group: "First" }),
    component("C", { componentId: "C", group: "Second" }),
  ]);
  assert.deepEqual(groups.map((g) => g.name), ["Second", "First"]);
  assert.deepEqual(groups[0].components.map((c) => c.componentId), ["A", "C"]);
});

// --- mergeCatalogGroups -------------------------------------------------------

test("mergeCatalogGroups returns the annotation groups unchanged when the spec has none", () => {
  const base = inventoryFromPreviews([
    component("FilledButton", { componentId: "Button/Filled", group: "Buttons" }),
  ]).groups;
  assert.deepEqual(mergeCatalogGroups(base, []), base);
});

test("mergeCatalogGroups lets a spec component override the annotation caption, keeping the preview", () => {
  const base = inventoryFromPreviews([
    component("FilledButton", {
      componentId: "Button/Filled",
      group: "Buttons",
      caption: "annotation caption",
    }),
  ]).groups;
  const merged = mergeCatalogGroups(base, [
    { name: "Buttons", components: [{ componentId: "Button/Filled", caption: "spec caption" }] },
  ]);
  assert.equal(merged.length, 1);
  assert.deepEqual(merged[0].components[0], {
    componentId: "Button/Filled",
    preview: "FilledButton", // filled in from the annotation
    caption: "spec caption", // spec wins
  });
});

test("mergeCatalogGroups keeps a spec-only component verbatim", () => {
  const merged = mergeCatalogGroups([], [
    { name: "Legacy", components: [{ componentId: "Old/Thing", preview: "OldThing" }] },
  ]);
  assert.deepEqual(merged, [
    { name: "Legacy", components: [{ componentId: "Old/Thing", preview: "OldThing" }] },
  ]);
});

test("mergeCatalogGroups appends an annotation-only component into its group after the spec groups", () => {
  const base = inventoryFromPreviews([
    component("FilledButton", { componentId: "Button/Filled", group: "Buttons" }),
    component("Switch", { componentId: "Switch/On", group: "Selection" }),
  ]).groups;
  const merged = mergeCatalogGroups(base, [
    { name: "Buttons", components: [{ componentId: "Button/Filled", preview: "FilledButton" }] },
  ]);
  assert.deepEqual(merged.map((g) => g.name), ["Buttons", "Selection"]);
  assert.equal(merged[1].components[0].componentId, "Switch/On");
});

test("mergeCatalogGroups places a shared component in the spec's group, not the annotation's", () => {
  const base = inventoryFromPreviews([
    component("FilledButton", { componentId: "Button/Filled", group: "Buttons" }),
  ]).groups;
  const merged = mergeCatalogGroups(base, [
    { name: "Actions", components: [{ componentId: "Button/Filled" }] },
  ]);
  assert.deepEqual(merged.map((g) => g.name), ["Actions"]);
  assert.equal(merged[0].components[0].preview, "FilledButton");
});

test("mergeCatalogGroups unions variants by preview, spec winning per function", () => {
  const base = inventoryFromPreviews([
    component("FilledButton", { componentId: "Button/Filled", group: "Buttons" }),
    variant("FilledButtonPressed", {
      componentId: "Button/Filled",
      state: "pressed",
      caption: "annotation",
    }),
  ]).groups;
  const merged = mergeCatalogGroups(base, [
    {
      name: "Buttons",
      components: [
        {
          componentId: "Button/Filled",
          variants: [
            { preview: "FilledButtonPressed", caption: "spec" }, // overrides the annotation variant
            { preview: "FilledButtonDisabled", state: "disabled" }, // spec-only variant
          ],
        },
      ],
    },
  ]);
  assert.deepEqual(merged[0].components[0].variants, [
    { preview: "FilledButtonPressed", state: "pressed", caption: "spec" },
    { preview: "FilledButtonDisabled", state: "disabled" },
  ]);
});

test("mergeCatalogGroups is a no-op shape for an all-empty input", () => {
  assert.deepEqual(mergeCatalogGroups([], []), []);
});

// --- applyGroupOrder ----------------------------------------------------------

const groupsNamed = (...names) => names.map((name) => ({ name, components: [] }));

test("applyGroupOrder sorts groups into the declared order", () => {
  const groups = groupsNamed("Lists", "Buttons", "Selection");
  const ordered = applyGroupOrder(groups, ["Buttons", "Selection", "Lists"]);
  assert.deepEqual(ordered.map((g) => g.name), ["Buttons", "Selection", "Lists"]);
});

test("applyGroupOrder keeps unlisted groups after the listed ones, in original order", () => {
  const groups = groupsNamed("Communication", "Buttons", "Theme", "Selection");
  const ordered = applyGroupOrder(groups, ["Buttons", "Selection"]);
  assert.deepEqual(
    ordered.map((g) => g.name),
    ["Buttons", "Selection", "Communication", "Theme"], // unlisted keep source order
  );
});

test("applyGroupOrder is a no-op when groupOrder is absent or empty", () => {
  const groups = groupsNamed("B", "A");
  assert.deepEqual(applyGroupOrder(groups, undefined), groups);
  assert.deepEqual(applyGroupOrder(groups, []), groups);
});

// --- @CatalogComponent(perBreakpoint = true) --------------------------------

// The breakpoints come from the RENDERS, never from the annotation — so a fixture supplies the
// `@Preview(device = …)` each expansion ran under, exactly as the bundle carries it.
const WEAR_BREAKPOINTS = [
  { size: "smallRound", device: "id:wearos_small_round", widthDp: 192 },
  { size: "largeRound", device: "id:wearos_large_round", widthDp: 227 },
  { size: "xlRound", device: "id:wearos_xl_round", widthDp: 240 },
];
const rendered = (functionName, device, catalog) => ({
  functionName,
  params: { device },
  catalog,
});

test("perBreakpoint fans out over the breakpoints the function actually rendered", () => {
  const { groups, withoutBreakpoints } = inventoryFromPreviews(
    [
      // Bundle order is large-then-small; the fan-out must follow the BREAKPOINTS table instead,
      // so the cards read small→large the way the catalog declares them.
      rendered("ListLayout", "id:wearos_large_round", {
        role: "COMPONENT",
        componentId: "Layout/List",
        group: "Layout",
        caption: "A transforming lazy column.",
        perBreakpoint: true,
      }),
      rendered("ListLayout", "id:wearos_small_round", {
        role: "COMPONENT",
        componentId: "Layout/List",
        perBreakpoint: true,
      }),
    ],
    { breakpoints: WEAR_BREAKPOINTS },
  );

  assert.deepEqual(withoutBreakpoints, []);
  assert.deepEqual(groups[0].components, [
    {
      componentId: "Layout/List/smallRound",
      preview: "ListLayout",
      caption: "A transforming lazy column.",
      select: { size: "smallRound" },
    },
    {
      componentId: "Layout/List/largeRound",
      preview: "ListLayout",
      caption: "A transforming lazy column.",
      select: { size: "largeRound" },
    },
  ]);
  // xlRound is in the table but this function never rendered it, so it mints no card.
  assert.equal(groups[0].components.length, 2);
});

test("a function rendering one breakpoint keeps its plain id", () => {
  // Suffixing here would move a published sticker's URL to say what the id already says.
  const { groups } = inventoryFromPreviews(
    [
      rendered("ListLayout", "id:wearos_large_round", {
        role: "COMPONENT",
        componentId: "Layout/List",
        perBreakpoint: true,
      }),
    ],
    { breakpoints: WEAR_BREAKPOINTS },
  );

  assert.deepEqual(groups[0].components, [
    { componentId: "Layout/List", preview: "ListLayout", select: { size: "largeRound" } },
  ]);
});

test("without the flag the renders fold onto one component, exactly as before", () => {
  const { groups } = inventoryFromPreviews(
    [
      rendered("ListLayout", "id:wearos_small_round", {
        role: "COMPONENT",
        componentId: "Layout/List",
      }),
      rendered("ListLayout", "id:wearos_large_round", {
        role: "COMPONENT",
        componentId: "Layout/List",
      }),
    ],
    { breakpoints: WEAR_BREAKPOINTS },
  );
  assert.deepEqual(groups[0].components, [
    { componentId: "Layout/List", preview: "ListLayout" },
  ]);
});

test("perBreakpoint with no resolvable breakpoint keeps the component whole and reports it", () => {
  // An undeclared device (or no breakpoints table at all) must not silently drop the component —
  // it stays one card and the export warns, so the fix is a `breakpoints` entry, not a mystery.
  const { groups, withoutBreakpoints } = inventoryFromPreviews(
    [
      rendered("ListLayout", "id:wearos_rect", {
        role: "COMPONENT",
        componentId: "Layout/List",
        perBreakpoint: true,
      }),
    ],
    { breakpoints: WEAR_BREAKPOINTS },
  );

  assert.deepEqual(withoutBreakpoints, ["Layout/List"]);
  assert.deepEqual(groups[0].components, [
    { componentId: "Layout/List", preview: "ListLayout" },
  ]);
});

test("a variant attaches to a fanned-out parent by its annotated id, and by a suffixed one", () => {
  // `@CatalogVariant(of = …)` names the parent as the ANNOTATION spells it, which the fan-out has
  // suffixed — so the plain id must still resolve, or every variant on a fanned-out component
  // silently orphans. A variant may also target one breakpoint explicitly.
  const { groups, orphanVariants } = inventoryFromPreviews(
    [
      rendered("ListLayout", "id:wearos_small_round", {
        role: "COMPONENT",
        componentId: "Layout/List",
        perBreakpoint: true,
      }),
      rendered("ListLayout", "id:wearos_large_round", {
        role: "COMPONENT",
        componentId: "Layout/List",
        perBreakpoint: true,
      }),
      rendered("ListLayoutPressed", "id:wearos_small_round", {
        role: "VARIANT",
        componentId: "Layout/List",
        state: "pressed",
      }),
      rendered("ListLayoutFocused", "id:wearos_large_round", {
        role: "VARIANT",
        componentId: "Layout/List/largeRound",
        state: "focused",
      }),
    ],
    { breakpoints: WEAR_BREAKPOINTS },
  );

  assert.deepEqual(orphanVariants, []);
  const [small, large] = groups[0].components;
  assert.deepEqual(small.variants, [{ preview: "ListLayoutPressed", state: "pressed" }]);
  assert.deepEqual(large.variants, [{ preview: "ListLayoutFocused", state: "focused" }]);
});

test("a spec entry overrides an annotation-derived select", () => {
  const base = inventoryFromPreviews(
    [
      rendered("ListLayout", "id:wearos_large_round", {
        role: "COMPONENT",
        componentId: "Layout/List",
        perBreakpoint: true,
      }),
    ],
    { breakpoints: WEAR_BREAKPOINTS },
  ).groups;
  const merged = mergeCatalogGroups(base, [
    {
      name: "Components",
      components: [{ componentId: "Layout/List", select: { size: "smallRound" } }],
    },
  ]);
  assert.deepEqual(merged[0].components[0].select, { size: "smallRound" });
  assert.equal(merged[0].components[0].preview, "ListLayout", "join key still comes from the annotation");
});
