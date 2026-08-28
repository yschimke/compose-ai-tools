import { test } from "node:test";
import assert from "node:assert/strict";

import {
  applyVariantParity,
  comparedVariant,
  comparedVariantIndex,
} from "./apply-variant-parity.mjs";

const comp = (componentId, extra = {}) => ({ componentId, images: [], ...extra });
const manifest = (components) => ({
  schema: "design-parity-catalog/v1",
  system: "s",
  components,
});

test("stamps a component's compared variants onto the matching manifest component", () => {
  const spec = {
    groups: [
      {
        name: "Buttons",
        components: [
          {
            componentId: "Button/Compact",
            variants: [
              {
                preview: "CompactIconOnly",
                props: { content: "icon-only" },
                parallel: "Button/Compact",
                noReference: "the kit exports no `Text=No` cell",
              },
            ],
          },
        ],
      },
    ],
  };
  const built = manifest([comp("Button/Compact")]);
  assert.equal(applyVariantParity(built, spec), 1);
  assert.deepEqual(built.components[0].variants, [
    {
      preview: "CompactIconOnly",
      props: { content: "icon-only" },
      parallel: "Button/Compact",
      noReference: "the kit exports no `Text=No` cell",
    },
  ]);
});

test("leaves out a variant that declares no kit correspondence", () => {
  // Its images are already folded onto the parent; an entry with nothing to join on would restate
  // the fold in a second shape and grow the manifest for no consumer.
  const spec = {
    groups: [
      {
        components: [
          {
            componentId: "Button/Filled",
            variants: [{ preview: "FilledPressed", state: "pressed", caption: "Held press." }],
          },
        ],
      },
    ],
  };
  const built = manifest([comp("Button/Filled")]);
  assert.equal(applyVariantParity(built, spec), 0);
  assert.equal(built.components[0].variants, undefined);
});

test("treats a blank declaration as absent, like parallelIndex does", () => {
  // `@CatalogVariant(parallel = "")` is the annotation default and means "no counterpart".
  // Publishing it as "" would make an absent pairing look like a declared one.
  assert.equal(comparedVariant({ preview: "P", state: "s", parallel: "   " }), null);
  assert.equal(comparedVariant({ preview: "P", state: "s", parallel: "" }), null);
});

test("carries the variant's identity alongside its correspondence", () => {
  assert.deepEqual(
    comparedVariant({
      preview: "OutlinedCard",
      props: { style: "outlined" },
      theme: "dark",
      caption: "Outlined treatment.",
      referenceContentsOnly: false,
      reference: "figma:FILE/1:2",
      referenceSet: "figma:FILE/1:1",
      // `select` steers the render AND identifies it — it is how the compare page picks this
      // variant's thumbnail back out of the parent's folded images — so it is carried.
      select: { size: "compact" },
      // render-time fields the manifest has no use for: the render already happened.
      capture: "static",
      priority: 3,
    }),
    {
      preview: "OutlinedCard",
      props: { style: "outlined" },
      theme: "dark",
      caption: "Outlined treatment.",
      select: { size: "compact" },
      referenceContentsOnly: false,
      reference: "figma:FILE/1:2",
      referenceSet: "figma:FILE/1:1",
    },
  );
});

test("an empty select is no selection, and is not carried as an axis", () => {
  // `selectOf` treats `{}` as "selects nothing" everywhere else; carrying it would make an empty
  // object read downstream as an axis every image must satisfy.
  assert.deepEqual(comparedVariant({ preview: "P", select: {}, parallel: "Other/P" }), {
    preview: "P",
    parallel: "Other/P",
  });
});

test("a variant distinguished by select alone still reaches the manifest with its axis", () => {
  assert.deepEqual(
    comparedVariant({ preview: "Home", select: { size: "smallRound" }, parallel: "Other/Home" }),
    { preview: "Home", select: { size: "smallRound" }, parallel: "Other/Home" },
  );
});

test("never clobbers a manifest component that already carries variants", () => {
  const spec = {
    groups: [
      {
        components: [
          {
            componentId: "Button/Compact",
            variants: [{ preview: "FromSpec", state: "a", parallel: "X" }],
          },
        ],
      },
    ],
  };
  const built = manifest([comp("Button/Compact", { variants: [{ preview: "Existing" }] })]);
  assert.equal(applyVariantParity(built, spec), 0);
  assert.deepEqual(built.components[0].variants, [{ preview: "Existing" }]);
});

test("is idempotent", () => {
  const spec = {
    groups: [
      {
        components: [
          {
            componentId: "Button/Compact",
            variants: [{ preview: "IconOnly", state: "icon-only", parallel: "Button/Compact" }],
          },
        ],
      },
    ],
  };
  const built = manifest([comp("Button/Compact")]);
  assert.equal(applyVariantParity(built, spec), 1);
  const after = JSON.stringify(built);
  assert.equal(applyVariantParity(built, spec), 0);
  assert.equal(JSON.stringify(built), after);
});

test("indexes only components that have compared variants", () => {
  const index = comparedVariantIndex({
    groups: [
      {
        components: [
          { componentId: "A", variants: [{ preview: "A1", state: "s", parallel: "X" }] },
          { componentId: "B", variants: [{ preview: "B1", state: "s" }] },
          { componentId: "C" },
        ],
      },
    ],
  });
  assert.deepEqual([...index.keys()], ["A"]);
});

test("tolerates a spec with no groups and a manifest with no components", () => {
  assert.equal(applyVariantParity({}, {}), 0);
  assert.equal(applyVariantParity(undefined, undefined), 0);
  assert.deepEqual([...comparedVariantIndex(undefined).keys()], []);
});
