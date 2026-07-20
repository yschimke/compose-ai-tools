import { test } from "node:test";
import assert from "node:assert/strict";

import { applySpecSections } from "./apply-spec-sections.mjs";

const comp = (componentId, extra = {}) => ({ componentId, images: [], ...extra });
const manifest = (components) => ({ schema: "design-parity-catalog/v1", system: "s", components });

test("stamps each spec group's section onto matching manifest components", () => {
  const spec = {
    groups: [
      { name: "Foundation", section: "Themes", components: [{ componentId: "Theme/Light" }] },
      { name: "Device", section: "Components", components: [{ componentId: "Card/Populated" }] },
      { name: "Chat", section: "Screens", components: [{ componentId: "Chat/Contact" }] },
    ],
  };
  const m = manifest([comp("Theme/Light"), comp("Card/Populated"), comp("Chat/Contact")]);

  const stamped = applySpecSections(m, spec);

  assert.equal(stamped, 3);
  assert.equal(m.components[0].section, "Themes");
  assert.equal(m.components[1].section, "Components");
  assert.equal(m.components[2].section, "Screens");
});

test("is a no-op when no spec group declares a section (compose-m3 style)", () => {
  const spec = {
    groups: [{ name: "Buttons", components: [{ componentId: "Buttons/Filled" }] }],
  };
  const m = manifest([comp("Buttons/Filled")]);

  const stamped = applySpecSections(m, spec);

  assert.equal(stamped, 0);
  assert.equal("section" in m.components[0], false);
});

test("never clobbers a section already on the component (e.g. a merged Material 3 tab)", () => {
  const spec = {
    groups: [{ name: "Foundation", section: "Themes", components: [{ componentId: "Theme/Light" }] }],
  };
  // The borrowed component already carries its own tab from the merge step.
  const m = manifest([comp("Theme/Light"), comp("Buttons/Filled", { section: "Material 3" })]);

  const stamped = applySpecSections(m, spec);

  assert.equal(stamped, 1); // only Theme/Light gets one
  assert.equal(m.components[0].section, "Themes");
  assert.equal(m.components[1].section, "Material 3"); // untouched
});

test("leaves manifest components absent from the spec untouched", () => {
  const spec = {
    groups: [{ name: "Foundation", section: "Themes", components: [{ componentId: "Theme/Light" }] }],
  };
  const m = manifest([comp("Theme/Light"), comp("Orphan/NotInSpec")]);

  applySpecSections(m, spec);

  assert.equal(m.components[0].section, "Themes");
  assert.equal("section" in m.components[1], false);
});

test("is idempotent — a second pass stamps nothing", () => {
  const spec = {
    groups: [{ name: "Foundation", section: "Themes", components: [{ componentId: "Theme/Light" }] }],
  };
  const m = manifest([comp("Theme/Light")]);

  assert.equal(applySpecSections(m, spec), 1);
  assert.equal(applySpecSections(m, spec), 0);
  assert.equal(m.components[0].section, "Themes");
});

test("tolerates missing/empty groups and components without throwing", () => {
  assert.equal(applySpecSections({ components: [] }, {}), 0);
  assert.equal(applySpecSections({}, { groups: [] }), 0);
  assert.equal(applySpecSections({ components: [comp("X")] }, { groups: [{ section: "T" }] }), 0);
});

test("reproduces the meshcore split — Themes/Components/Screens distribution", () => {
  // A trimmed stand-in for meshcore's spec shape: three tabs across several groups.
  const spec = {
    groups: [
      { name: "Foundation", section: "Themes", components: [{ componentId: "Theme/A" }, { componentId: "Theme/B" }] },
      { name: "Device", section: "Components", components: [{ componentId: "Card/A" }] },
      { name: "Contacts", section: "Components", components: [{ componentId: "Row/A" }, { componentId: "Row/B" }] },
      { name: "Scanner", section: "Screens", components: [{ componentId: "Scan/A" }] },
      { name: "Chat", section: "Screens", components: [{ componentId: "Chat/A" }, { componentId: "Chat/B" }] },
    ],
  };
  const ids = ["Theme/A", "Theme/B", "Card/A", "Row/A", "Row/B", "Scan/A", "Chat/A", "Chat/B"];
  const m = manifest(ids.map((id) => comp(id)));

  applySpecSections(m, spec);

  const dist = {};
  for (const c of m.components) dist[c.section] = (dist[c.section] ?? 0) + 1;
  assert.deepEqual(dist, { Themes: 2, Components: 3, Screens: 3 });
});
