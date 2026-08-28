/**
 * Unit tests for the joins behind a catalog's cross-system pairing: widening `compareWith` to its
 * object form (so a sibling can live in another repository), inverting a published
 * `references/index.json` onto componentId (so the compare page can carry a design column beside
 * the two implementations), and narrowing `compareWith` to the shape the published manifest
 * carries (so a preview server can resolve a component's `parallel` counterpart).
 *
 * Run with `node --test scripts/design-artifacts/`.
 */
import assert from "node:assert/strict";
import { test } from "node:test";

import {
  manifestCompareWith,
  normalizeCompareWith,
  primaryReferencesByComponentId,
} from "./cross-system-compare.mjs";

test("a bare slug keeps meaning exactly what it did", () => {
  assert.deepEqual(normalizeCompareWith("wear-m3"), { system: "wear-m3" });
});

test("the object form carries the sibling's repository and spec path", () => {
  assert.deepEqual(
    normalizeCompareWith({
      system: "wear-m3-catalog",
      repo: "yschimke/wear-m3-catalog",
      spec: "../catalog.spec.json",
    }),
    { system: "wear-m3-catalog", repo: "yschimke/wear-m3-catalog", spec: "../catalog.spec.json" },
  );
});

test("an absent or malformed declaration is null, not a half-configured pairing", () => {
  assert.equal(normalizeCompareWith(undefined), null);
  assert.equal(normalizeCompareWith(""), null);
  // No `system` — naming a repo but not what to compare against would otherwise bake URLs
  // containing `undefined`, which 404 only after a publish.
  assert.equal(normalizeCompareWith({ repo: "yschimke/wear-m3-catalog" }), null);
});

const reference = (componentId, path, extra = {}) => ({
  id: path.replace(/^references\/|\.png$/g, ""),
  previewId: componentId.toLowerCase().replace(/\//g, "__"),
  tier: "primary",
  raster: { path, width: 172, height: 52 },
  source: {
    provider: "figma",
    uri: "figma:B24oss2tTeXAFykyeyusz0/35239:93092",
    attributes: { componentId },
  },
  ...extra,
});

test("primary references inverted onto componentId, carrying the kit node uri", () => {
  const byId = primaryReferencesByComponentId({
    schema: "compose-preview-references/v1",
    references: [
      reference("Button/Filled", "references/button-filled.png"),
      reference("Card", "references/card.png"),
    ],
  });

  assert.deepEqual(byId.get("Button/Filled"), {
    path: "references/button-filled.png",
    // Carried so a caller can check the record still corresponds to a published preview before
    // baking its URL — see the local arm in generate-design-catalog.mjs.
    previewId: "button__filled",
    uri: "figma:B24oss2tTeXAFykyeyusz0/35239:93092",
  });
  assert.deepEqual([...byId.keys()], ["Button/Filled", "Card"]);
});

test("a secondary reference never stands in for the component's primary", () => {
  // Secondaries document one cell of the variant matrix (here the disabled button). Showing one in
  // a single-thumbnail column looks entirely correct and is the wrong picture, so they are skipped
  // even when they are the only record for the component.
  const byId = primaryReferencesByComponentId({
    references: [
      reference("Button/Filled", "references/button-filled--disabled.png", {
        tier: "secondary",
        slot: "disabled",
      }),
      reference("Button/Filled", "references/button-filled.png"),
      reference("Card", "references/card--outlined.png", { tier: "secondary", slot: "outlined" }),
    ],
  });

  assert.equal(byId.get("Button/Filled").path, "references/button-filled.png");
  assert.equal(byId.has("Card"), false);
});

test("a tier-less record predates the field and is treated as primary", () => {
  const { tier, ...untiered } = reference("Icon", "references/icon.png");
  const byId = primaryReferencesByComponentId({ references: [untiered] });
  assert.equal(byId.get("Icon").path, "references/icon.png");
});

test("first primary wins, and unusable records are dropped rather than half-mapped", () => {
  const byId = primaryReferencesByComponentId({
    references: [
      reference("Button/Filled", "references/first.png"),
      reference("Button/Filled", "references/second.png"),
      { tier: "primary", raster: { path: "references/orphan.png" }, source: { attributes: {} } },
      { tier: "primary", source: { attributes: { componentId: "NoRaster" } } },
    ],
  });

  assert.equal(byId.get("Button/Filled").path, "references/first.png");
  assert.equal(byId.size, 1);
});

test("a missing or empty manifest joins to nothing instead of throwing", () => {
  assert.equal(primaryReferencesByComponentId(null).size, 0);
  assert.equal(primaryReferencesByComponentId({}).size, 0);
  assert.equal(primaryReferencesByComponentId({ references: [] }).size, 0);
});

test("an empty object-form system is rejected as hard as an empty string", () => {
  // `{ system: "" }` used to read as configured, which resolves a sibling spec at
  // `design-catalog-/catalog.spec.json` and fetches `design-artifacts//` — a pairing that is
  // invalid AND published, rather than skipped.
  assert.equal(normalizeCompareWith({ system: "" }), null);
  assert.equal(normalizeCompareWith({ system: "   " }), null);
  assert.equal(normalizeCompareWith("   "), null);
});

// --- manifestCompareWith: what reaches a consumer of the published catalog (issue #4621) -------

test("a same-repo pairing publishes the sibling slug alone", () => {
  // The common shape, and the one remote-catalog uses: a sibling in this repo, declared as a bare
  // string or as an object with no `repo`. A consumer serving both catalogs resolves the sibling
  // by slug, so that is all it needs.
  assert.deepEqual(manifestCompareWith("wear-m3-catalog"), { system: "wear-m3-catalog" });
  assert.deepEqual(manifestCompareWith({ system: "wear-m3-catalog" }), {
    system: "wear-m3-catalog",
  });
});

test("a cross-repo pairing publishes the repo too", () => {
  assert.deepEqual(manifestCompareWith({ system: "wear-m3-catalog", repo: "yschimke/other" }), {
    system: "wear-m3-catalog",
    repo: "yschimke/other",
  });
});

test("publish-time-only fields do not reach the manifest", () => {
  // `spec` is a path in the PRODUCING checkout and is meaningless to a reader of the manifest;
  // `designTitle` and `design` are `matches.html`'s presentation. Publishing them would invite a
  // consumer to depend on the build-time layout of a repository it cannot see.
  const published = manifestCompareWith({
    system: "wear-m3-catalog",
    spec: "../catalog.spec.json",
    designTitle: "M3 Wear OS kit",
    design: false,
  });
  assert.deepEqual(published, { system: "wear-m3-catalog" });
});

test("no pairing, or an unusable one, publishes nothing", () => {
  // Same rejections as normalizeCompareWith — a blank system must not travel, or a consumer would
  // resolve a sibling at the empty slug and fetch a catalog that cannot exist.
  for (const input of [null, undefined, "", "   ", { system: "" }, { system: "   " }, 42]) {
    assert.equal(manifestCompareWith(input), null, `expected null for ${JSON.stringify(input)}`);
  }
});

test("the slug is trimmed, so it is usable as a path segment", () => {
  assert.deepEqual(manifestCompareWith({ system: "  wear-m3-catalog  " }), {
    system: "wear-m3-catalog",
  });
});
