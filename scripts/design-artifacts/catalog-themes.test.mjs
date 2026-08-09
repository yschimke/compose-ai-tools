import { test } from "node:test";
import assert from "node:assert/strict";

import { catalogThemesFromBundle } from "./catalog-themes.mjs";

/** A wear-m3-shaped bundle: three declared themes, one of them typeface-only. */
const bundle = {
  previews: [
    {
      id: "wearthemecatalog__Coral",
      params: {
        wrapperClassName: "com.example.WearCoralThemeCatalog",
        name: "Coral",
        group: "Wear",
      },
    },
    {
      id: "wearthemecatalog__Google Sans Flex",
      params: {
        wrapperClassName: "com.example.WearGoogleSansFlexThemeCatalog",
        name: "Google Sans Flex",
        group: "Wear",
      },
    },
    { id: "buttons__filled", params: {} },
  ],
};

const tokenSets = [
  {
    theme: "Coral",
    previewId: "wearthemecatalog__Coral",
    providerFqn: "com.example.WearCoralThemeCatalog",
    tokens: { colors: { primary: "#ff6f61ff" } },
  },
  {
    theme: "Google Sans Flex",
    previewId: "wearthemecatalog__Google Sans Flex",
    providerFqn: "com.example.WearGoogleSansFlexThemeCatalog",
    tokens: { typography: { titleMedium: { fontFamily: "Google Sans Flex" } } },
  },
];

test("maps each theme onto the export's shape, keyed by provider FQN", () => {
  const themes = catalogThemesFromBundle(bundle, tokenSets);
  assert.deepEqual(themes, [
    {
      id: "com.example.WearCoralThemeCatalog",
      tokens: { colors: { primary: "#ff6f61ff" } },
      name: "Coral",
      group: "Wear",
    },
    {
      id: "com.example.WearGoogleSansFlexThemeCatalog",
      // A theme that swaps only the type scale is a theme: its token set is its
      // typeface, and that is exactly what makes it distinguishable.
      tokens: { typography: { titleMedium: { fontFamily: "Google Sans Flex" } } },
      name: "Google Sans Flex",
      group: "Wear",
    },
  ]);
});

test("leaves `dark` unset, because nothing declares it", () => {
  // design-parity#307 made `dark` a declaration so it wouldn't become a luminance
  // guess at some arbitrary layer. The annotation carries a name and a group and
  // no mode, so the honest answer here is silence.
  for (const theme of catalogThemesFromBundle(bundle, tokenSets)) {
    assert.equal("dark" in theme, false);
  }
});

test("skips a theme with no resolvable provider FQN, and says so", () => {
  // The id is the whole point: a `themes[]` entry keyed on something a preview
  // server cannot address is data no consumer can attach to a theme.
  const skipped = [];
  const themes = catalogThemesFromBundle(
    bundle,
    [
      { theme: "Orphan", previewId: "wearthemecatalog__Orphan", tokens: { colors: {} } },
      ...tokenSets,
    ],
    (previewId, theme) => skipped.push(`${theme}:${previewId}`),
  );
  assert.deepEqual(
    themes.map((t) => t.id),
    [
      "com.example.WearCoralThemeCatalog",
      "com.example.WearGoogleSansFlexThemeCatalog",
    ],
  );
  assert.deepEqual(skipped, ["Orphan:wearthemecatalog__Orphan"]);
});

test("publishes one theme per provider, keeping the first of a repeat", () => {
  // Two entries with the same FQN would write two files at one slug, the second
  // overwriting the first.
  const skipped = [];
  const themes = catalogThemesFromBundle(
    bundle,
    [
      tokenSets[0],
      { ...tokenSets[0], theme: "Coral (again)", tokens: { colors: { primary: "#000000ff" } } },
    ],
    (previewId, theme) => skipped.push(theme),
  );
  assert.equal(themes.length, 1);
  assert.deepEqual(themes[0].tokens, { colors: { primary: "#ff6f61ff" } });
  assert.deepEqual(skipped, ["Coral (again)"]);
});

test("treats providers that slug to one file as one theme", () => {
  // The exporter lowercases an FQN into `themes/<slug>.dtcg.json`, so two ids
  // differing only in case are distinct strings but the SAME file — the second
  // would overwrite the first on disk.
  const skipped = [];
  const themes = catalogThemesFromBundle(
    bundle,
    [
      { theme: "Coral", previewId: "wearthemecatalog__Coral", providerFqn: "com.example.WearCoral", tokens: { colors: { primary: "#ff6f61ff" } } },
      { theme: "Coral (cased)", previewId: "wearthemecatalog__Coral", providerFqn: "com.example.wearcoral", tokens: { colors: { primary: "#000000ff" } } },
    ],
    (previewId, theme) => skipped.push(theme),
  );
  assert.equal(themes.length, 1);
  assert.equal(themes[0].tokens.colors.primary, "#ff6f61ff");
  assert.deepEqual(skipped, ["Coral (cased)"]);
});

test("falls back to the preview entry's name, and copes with no entry at all", () => {
  const themes = catalogThemesFromBundle(bundle, [
    {
      theme: "",
      previewId: "wearthemecatalog__Coral",
      providerFqn: "com.example.WearCoralThemeCatalog",
      tokens: { colors: { primary: "#ff6f61ff" } },
    },
    {
      theme: "Detached",
      previewId: "not-in-this-bundle",
      providerFqn: "com.example.Detached",
      tokens: { colors: { primary: "#123456ff" } },
    },
  ]);
  // A sidecar that declared no display name takes the one on the preview entry…
  assert.equal(themes[0].name, "Coral");
  assert.equal(themes[0].group, "Wear");
  // …and a token set whose specimen isn't in the preview list still publishes,
  // with no name/group rather than a guess.
  assert.deepEqual(themes[1], {
    id: "com.example.Detached",
    tokens: { colors: { primary: "#123456ff" } },
    name: "Detached",
  });
});

test("an empty or absent input publishes no themes", () => {
  assert.deepEqual(catalogThemesFromBundle(bundle, []), []);
  assert.deepEqual(catalogThemesFromBundle(bundle, undefined), []);
  assert.deepEqual(catalogThemesFromBundle({}, tokenSets).length, 2);
});
