/**
 * Unit tests for the sticker↔render pairing checker. Run with
 * `node --test scripts/design-artifacts/`.
 */
import assert from "node:assert/strict";
import { test } from "node:test";

import {
  describeMismatchedRenders,
  mismatchedVariantRenders,
} from "./variant-render-pairing.mjs";

test("two images sharing a previewId with different dimensions are reported", () => {
  // The published shape of the catalog-breakpoint half of #2883: Jetsnack's compact sticker is a
  // 1082×315 render of the `@Preview("large font", widthDp = 412, heightDp = 120)` annotation, but
  // was stamped with the function's `_default` previewId — whose own render is 250×105.
  const mismatches = mismatchedVariantRenders([
    {
      componentId: "Foundations/Button",
      images: [
        {
          path: "images/foundations-button/ideal__default.png",
          width: 250,
          height: 105,
          previewId: "com.example.jetsnack.ui.components.ButtonKt.ButtonPreview_default",
        },
        {
          path: "images/foundations-button/ideal__default__compact.png",
          width: 1082,
          height: 315,
          size: "compact",
          previewId: "com.example.jetsnack.ui.components.ButtonKt.ButtonPreview_default",
        },
      ],
    },
  ]);

  assert.equal(mismatches.length, 1);
  assert.equal(mismatches[0].componentId, "Foundations/Button");
  assert.deepEqual(
    mismatches[0].renders.map((r) => `${r.width}x${r.height}`),
    ["250x105", "1082x315"],
  );
  assert.deepEqual(describeMismatchedRenders(mismatches), [
    "Foundations/Button — ButtonPreview_default serves 250×105 vs 1082×315",
  ]);
});

test("same previewId at the same size is fine — one render, two stickers", () => {
  // A component whose light sticker and its props-only sibling genuinely come from one render must
  // not be reported: identical dimensions are consistent with a single source.
  assert.deepEqual(
    mismatchedVariantRenders([
      {
        componentId: "Foundations/Card",
        images: [
          { path: "a.png", width: 199, height: 158, previewId: "pkg.CardPreview" },
          { path: "b.png", width: 199, height: 158, previewId: "pkg.CardPreview" },
        ],
      },
    ]),
    [],
  );
});

test("distinct previewIds are never compared against each other", () => {
  assert.deepEqual(
    mismatchedVariantRenders([
      {
        componentId: "Search/Categories",
        images: [
          { path: "a.png", width: 1050, height: 737, previewId: "pkg.Categories_default" },
          { path: "b.png", width: 1082, height: 578, previewId: "pkg.Categories_large_font" },
        ],
      },
    ]),
    [],
  );
});

test("the pre-manifest component shape is rejected, not silently passed", () => {
  // `buildCatalog` keeps captures under `variants.ideal` and carries no `images[]`; only the
  // written catalog.json has the flattened images and the previewId the stamp pass bridges on.
  // Handed the wrong one the checker would report nothing at all, however broken the export —
  // which is exactly how this check shipped inert in review.
  assert.throws(
    () =>
      mismatchedVariantRenders([
        { componentId: "Foundations/Button", variants: { ideal: [{ uri: "a.png" }] } },
      ]),
    /pass the components of the WRITTEN catalog\.json/,
  );
});

test("unbridged and dimensionless images are skipped, not crashed on", () => {
  // No previewId is a different gap (the driver counts it separately), and `width`/`height` are
  // optional in the schema — neither may throw or produce a phantom mismatch.
  assert.deepEqual(
    mismatchedVariantRenders([
      {
        componentId: "Chrome/Bar",
        images: [
          { path: "a.png", width: 100, height: 50 },
          { path: "b.png", width: 200, height: 50 },
          { path: "c.png", previewId: "pkg.Bar" },
          { path: "d.png", width: 300, height: 50, previewId: "pkg.Bar" },
        ],
      },
      { componentId: "Empty" },
      {},
    ]),
    [],
  );
  assert.deepEqual(mismatchedVariantRenders(undefined), []);
});
