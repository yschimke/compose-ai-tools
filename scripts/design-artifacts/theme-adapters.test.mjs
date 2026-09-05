import { test } from "node:test";
import assert from "node:assert/strict";

import {
  GENERATED_PACKAGE,
  THEME_KINDS,
  classNameFor,
  renderKotlin,
  resolveThemes,
  titleCase,
} from "./theme-adapters.mjs";

const ok = (spec) => {
  const { themes, errors } = resolveThemes(spec);
  assert.deepEqual(errors, []);
  return themes;
};

test("a spec with no themes resolves to nothing, without complaint", () => {
  assert.deepEqual(resolveThemes({}), { themes: [], errors: [] });
  assert.deepEqual(resolveThemes({ themes: [] }), { themes: [], errors: [] });
});

test("enum fans one composable over its named constants — the Pocket Casts shape", () => {
  const themes = ok({
    themes: [
      {
        kind: "enum",
        group: "Pocket Casts",
        composable:
          "au.com.shiftyjelly.pocketcasts.compose.AppThemeWithBackground",
        enum: "au.com.shiftyjelly.pocketcasts.ui.theme.Theme.ThemeType",
        values: ["LIGHT", "EXTRA_DARK"],
      },
    ],
  });
  assert.deepEqual(
    themes.map((t) => [t.name, t.group, t.className, t.body]),
    [
      [
        "Light",
        "Pocket Casts",
        "ImportedTheme_Light",
        "AppThemeWithBackground(ThemeType.LIGHT) { content() }",
      ],
      [
        "Extra dark",
        "Pocket Casts",
        "ImportedTheme_ExtraDark",
        "AppThemeWithBackground(ThemeType.EXTRA_DARK) { content() }",
      ],
    ],
  );
});

test("an enum value may override its own label and group", () => {
  const [theme] = ok({
    themes: [
      {
        kind: "enum",
        composable: "com.example.AppTheme",
        enum: "com.example.Palette",
        values: [{ value: "ROSE", name: "Rosé", group: "Seasonal" }],
      },
    ],
  });
  assert.equal(theme.name, "Rosé");
  assert.equal(theme.group, "Seasonal");
});

test("enum honours a named parameter when the composable does not take it positionally", () => {
  const [theme] = ok({
    themes: [
      {
        kind: "enum",
        composable: "com.example.AppTheme",
        enum: "com.example.Palette",
        parameter: "palette",
        values: ["FOREST"],
      },
    ],
  });
  assert.equal(theme.body, "AppTheme(palette = Palette.FOREST) { content() }");
});

test("functions gives a provider per composable — the Thunderbird Bolt shape", () => {
  const themes = ok({
    themes: [
      {
        kind: "functions",
        group: "Bolt",
        composables: [
          "net.thunderbird.components.ui.bolt.theme.thunderbird.ThunderbirdBoltTheme",
          {
            composable:
              "net.thunderbird.components.ui.bolt.theme.k9mail.K9MailBoltTheme",
            name: "K-9 Mail",
          },
        ],
      },
    ],
  });
  assert.deepEqual(
    themes.map((t) => [t.name, t.body]),
    [
      ["Thunderbird bolt", "ThunderbirdBoltTheme { content() }"],
      ["K-9 Mail", "K9MailBoltTheme { content() }"],
    ],
  );
});

test("modes gives the light/dark pair, labelled so the serve host can infer the mode", () => {
  const themes = ok({
    themes: [
      {
        kind: "modes",
        composable: "io.element.android.compound.theme.ElementTheme",
        group: "Compound",
      },
    ],
  });
  assert.deepEqual(
    themes.map((t) => [t.name, t.body]),
    [
      ["Light", "ElementTheme(darkTheme = false) { content() }"],
      ["Dark", "ElementTheme(darkTheme = true) { content() }"],
    ],
  );
});

test("arguments spells a call the shorthands cannot", () => {
  const [theme] = ok({
    themes: [
      {
        kind: "arguments",
        composable: "com.example.AppTheme",
        variants: [
          {
            name: "Solarized",
            args: {
              useDarkTheme: "false",
              overriddenColorScheme: "solarizedColorScheme(false)",
            },
            imports: ["com.example.solarizedColorScheme"],
          },
        ],
      },
    ],
  });
  assert.equal(
    theme.body,
    "AppTheme(useDarkTheme = false, overriddenColorScheme = solarizedColorScheme(false)) { content() }",
  );
  assert.ok(theme.imports.includes("com.example.solarizedColorScheme"));
});

test("wrapper carries a raw body verbatim", () => {
  const [theme] = ok({
    themes: [
      {
        kind: "wrapper",
        name: "Automotive",
        wrapper: "AutomotiveTheme { content() }",
        imports: ["com.example.AutomotiveTheme"],
      },
    ],
  });
  assert.equal(theme.body, "AutomotiveTheme { content() }");
});

test("every kind is exercised above, so a new one cannot land untested", () => {
  assert.deepEqual([...THEME_KINDS].sort(), [
    "arguments",
    "enum",
    "functions",
    "modes",
    "wrapper",
  ]);
});

test("errors name the entry and are collected, not thrown on the first", () => {
  const { themes, errors } = resolveThemes({
    themes: [
      { kind: "nonsense" },
      {
        kind: "enum",
        composable: "NotAnFqn",
        enum: "com.example.Palette",
        values: [],
      },
      { kind: "wrapper", name: "Fine", wrapper: "Theme { content() }" },
    ],
  });
  assert.equal(themes.length, 1, "the valid entry still resolves");
  assert.equal(errors.length, 3);
  assert.match(errors[0], /themes\[0\]: unknown kind "nonsense"/);
  assert.match(
    errors[1],
    /themes\[1\]\.composable: expected a fully-qualified name/,
  );
  assert.match(
    errors[2],
    /themes\[1\]\.values: name at least one enum constant/,
  );
});

test("two themes resolving to one provider is reported once, and the first wins", () => {
  const { themes, errors } = resolveThemes({
    themes: [
      { kind: "wrapper", name: "Extra Dark", wrapper: "A { content() }" },
      { kind: "wrapper", name: "extra-dark", wrapper: "B { content() }" },
      { kind: "wrapper", name: "EXTRA DARK", wrapper: "C { content() }" },
    ],
  });
  assert.deepEqual(
    themes.map((t) => t.body),
    ["A { content() }"],
  );
  assert.equal(errors.length, 1);
  assert.match(errors[0], /ImportedTheme_ExtraDark/);
});

test("classNameFor and titleCase round the awkward names", () => {
  assert.equal(classNameFor("Extra dark"), "ImportedTheme_ExtraDark");
  assert.equal(classNameFor("K-9 Mail"), "ImportedTheme_K9Mail");
  assert.equal(classNameFor("!!!"), "ImportedTheme_Unnamed");
  assert.equal(titleCase("EXTRA_DARK"), "Extra dark");
  assert.equal(titleCase("ThunderbirdBolt"), "Thunderbird bolt");
});

test("the rendered file hoists sorted imports and declares one annotated provider per theme", () => {
  const themes = ok({
    themes: [
      {
        kind: "enum",
        group: "Pocket Casts",
        composable:
          "au.com.shiftyjelly.pocketcasts.compose.AppThemeWithBackground",
        enum: "au.com.shiftyjelly.pocketcasts.ui.theme.Theme.ThemeType",
        values: ["LIGHT"],
      },
    ],
  });
  const kotlin = renderKotlin(themes);
  assert.match(kotlin, new RegExp(`^// GENERATED by compose-preview`));
  assert.match(kotlin, new RegExp(`\\npackage ${GENERATED_PACKAGE}\\n`));
  // Fully-qualifying `Theme.ThemeType.LIGHT` inline does not resolve for a nested enum, which is
  // exactly the Pocket Casts case — so the type is imported and used by simple name.
  assert.match(
    kotlin,
    /\nimport au\.com\.shiftyjelly\.pocketcasts\.ui\.theme\.Theme\.ThemeType\n/,
  );
  assert.match(
    kotlin,
    /\nimport androidx\.compose\.ui\.tooling\.preview\.PreviewWrapperProvider\n/,
  );
  assert.match(
    kotlin,
    /@ThemeCatalog\(name = "Light", group = "Pocket Casts"\)/,
  );
  assert.match(kotlin, /class ImportedTheme_Light : PreviewWrapperProvider \{/);
  assert.match(
    kotlin,
    /override fun Wrap\(content: @Composable \(\) -> Unit\) \{/,
  );
  const importLines = kotlin.split("\n").filter((l) => l.startsWith("import "));
  assert.deepEqual(importLines, [...importLines].sort(), "imports are sorted");
});

test("a theme with no group omits the argument rather than passing an empty one", () => {
  const kotlin = renderKotlin(
    ok({
      themes: [{ kind: "wrapper", name: "Solo", wrapper: "T { content() }" }],
    }),
  );
  assert.match(kotlin, /@ThemeCatalog\(name = "Solo"\)\n/);
});

test("an entry's imports reach every theme it expands to", () => {
  const themes = ok({
    themes: [
      {
        kind: "arguments",
        composable: "dev.sasikanth.rss.reader.ui.AppTheme",
        imports: [
          "androidx.compose.foundation.isSystemInDarkTheme",
          "dev.sasikanth.rss.reader.ui.getOverriddenColorScheme",
        ],
        variants: [
          {
            name: "Solarized",
            args: { useDarkTheme: "isSystemInDarkTheme()" },
          },
          { name: "Forest", args: { useDarkTheme: "isSystemInDarkTheme()" } },
        ],
      },
    ],
  });
  for (const t of themes) {
    assert.ok(
      t.imports.includes("androidx.compose.foundation.isSystemInDarkTheme"),
    );
    assert.ok(
      t.imports.includes(
        "dev.sasikanth.rss.reader.ui.getOverriddenColorScheme",
      ),
    );
  }
});
