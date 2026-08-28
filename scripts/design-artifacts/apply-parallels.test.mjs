import { test } from "node:test";
import assert from "node:assert/strict";

import { applyParallels, parallelIndex } from "./apply-parallels.mjs";

const comp = (componentId, extra = {}) => ({
  componentId,
  images: [],
  ...extra,
});
const manifest = (components) => ({
  schema: "design-parity-catalog/v1",
  system: "s",
  components,
});

test("stamps each spec component's parallel onto the matching manifest component", () => {
  const spec = {
    groups: [
      {
        name: "Buttons",
        components: [
          { componentId: "Button/Filled", parallel: "FilledButton" },
          { componentId: "Button/Outlined", parallel: "OutlinedButton" },
        ],
      },
    ],
  };
  const m = manifest([comp("Button/Filled"), comp("Button/Outlined")]);

  assert.equal(applyParallels(m, spec), 2);
  assert.equal(m.components[0].parallel, "FilledButton");
  assert.equal(m.components[1].parallel, "OutlinedButton");
});

test("is a no-op for a catalog that declares no cross-system pairing", () => {
  const spec = {
    groups: [
      { name: "Buttons", components: [{ componentId: "Button/Filled" }] },
    ],
  };
  const m = manifest([comp("Button/Filled")]);

  assert.equal(applyParallels(m, spec), 0);
  assert.equal("parallel" in m.components[0], false);
});

test("treats a blank parallel as no counterpart, not as an empty declaration", () => {
  // `@CatalogComponent(parallel = "")` is the annotation default. Publishing "" would read as a
  // declared pairing to any consumer testing for the field's presence.
  const spec = {
    groups: [
      {
        components: [
          { componentId: "A", parallel: "   " },
          { componentId: "B", parallel: "" },
        ],
      },
    ],
  };
  const m = manifest([comp("A"), comp("B")]);

  assert.equal(applyParallels(m, spec), 0);
  assert.equal("parallel" in m.components[0], false);
  assert.equal("parallel" in m.components[1], false);
});

test("trims a declaration rather than publishing its whitespace", () => {
  const spec = {
    groups: [
      { components: [{ componentId: "A", parallel: " FilledButton " }] },
    ],
  };
  const m = manifest([comp("A")]);

  assert.equal(applyParallels(m, spec), 1);
  assert.equal(m.components[0].parallel, "FilledButton");
});

test("never clobbers a parallel the exporter already carried", () => {
  // What a bumped `@design-parity/catalog-export` pin looks like: the field arrives on its own and
  // this stamp must become a no-op, not a second opinion.
  const spec = {
    groups: [{ components: [{ componentId: "A", parallel: "FromSpec" }] }],
  };
  const m = manifest([comp("A", { parallel: "FromExporter" })]);

  assert.equal(applyParallels(m, spec), 0);
  assert.equal(m.components[0].parallel, "FromExporter");
});

test("leaves manifest components absent from the spec untouched", () => {
  const spec = {
    groups: [{ components: [{ componentId: "A", parallel: "X" }] }],
  };
  const m = manifest([comp("A"), comp("Orphan/NotInSpec")]);

  applyParallels(m, spec);

  assert.equal(m.components[0].parallel, "X");
  assert.equal("parallel" in m.components[1], false);
});

test("carries a parallel verbatim without checking the sibling has such a component", () => {
  // Resolution belongs to the compare page, which has the sibling's inventory and reports an
  // unresolved pairing as an unpaired row. Dropping it here would hide a spec typo.
  const spec = {
    groups: [{ components: [{ componentId: "A", parallel: "TypoedName" }] }],
  };
  const m = manifest([comp("A")]);

  assert.equal(applyParallels(m, spec), 1);
  assert.equal(m.components[0].parallel, "TypoedName");
});

test("is idempotent — a second pass stamps nothing", () => {
  const spec = {
    groups: [{ components: [{ componentId: "A", parallel: "X" }] }],
  };
  const m = manifest([comp("A")]);

  assert.equal(applyParallels(m, spec), 1);
  assert.equal(applyParallels(m, spec), 0);
  assert.equal(m.components[0].parallel, "X");
});

test("tolerates missing/empty groups and components without throwing", () => {
  assert.equal(applyParallels({ components: [] }, {}), 0);
  assert.equal(applyParallels({}, { groups: [] }), 0);
  assert.equal(
    applyParallels({ components: [comp("X")] }, { groups: [{}] }),
    0,
  );
  assert.equal(applyParallels({ components: [comp("X")] }, undefined), 0);
});

// --- parallelIndex, the map both stampers read ------------------------------------------------
//
// A WHOLLY deferred component never reaches `manifest.components` — the generator short-circuits it
// into `deferred[]` — so `applyParallels` cannot see it and its declared counterpart was dropped,
// on a catalog that publishes `compareWith` and whose whole point is the cross-system comparison.
// The deferred records are attached after `applyParallels` runs, so they stamp from this index
// instead; exporting it is what keeps the two from growing different ideas of the blank rule.

test("indexes every component that declares a parallel", () => {
  const index = parallelIndex({
    groups: [
      { components: [{ componentId: "Button", parallel: "Button/Filled" }] },
      { components: [{ componentId: "Card", parallel: "TitleCard" }] },
    ],
  });
  assert.equal(index.get("Button"), "Button/Filled");
  assert.equal(index.get("Card"), "TitleCard");
  assert.equal(index.size, 2);
});

test("applies the same blank and trim rules the stamper does", () => {
  const index = parallelIndex({
    groups: [
      {
        components: [
          { componentId: "Blank", parallel: "" },
          { componentId: "Spaces", parallel: "   " },
          { componentId: "Padded", parallel: "  Button/Filled  " },
          { componentId: "Absent" },
          { componentId: "NotAString", parallel: 7 },
        ],
      },
    ],
  });
  assert.equal(index.has("Blank"), false);
  assert.equal(index.has("Spaces"), false);
  assert.equal(index.get("Padded"), "Button/Filled");
  assert.equal(index.has("Absent"), false);
  assert.equal(index.has("NotAString"), false);
});

test("is the index applyParallels itself stamps from", () => {
  // The property that makes exporting it worth anything: one reader, so a deferred record and a
  // published component can never disagree about what a spec entry declares.
  const spec = {
    groups: [
      {
        components: [
          { componentId: "Button", parallel: "  Button/Filled " },
          { componentId: "Blank", parallel: "" },
        ],
      },
    ],
  };
  const manifest = {
    components: [{ componentId: "Button" }, { componentId: "Blank" }],
  };
  applyParallels(manifest, spec);
  const index = parallelIndex(spec);
  for (const component of manifest.components) {
    assert.equal(component.parallel, index.get(component.componentId));
  }
});

test("tolerates a spec with no groups at all", () => {
  assert.equal(parallelIndex(undefined).size, 0);
  assert.equal(parallelIndex({}).size, 0);
  assert.equal(parallelIndex({ groups: [] }).size, 0);
});
