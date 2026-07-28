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
