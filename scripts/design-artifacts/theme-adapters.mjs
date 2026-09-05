/**
 * Turn an import's declared themes into the `@ThemeCatalog` providers a third-party module can
 * never declare for itself.
 *
 * A catalog's theme switcher is fed by `declaredThemes` — synthesized from `@ThemeCatalog` /
 * `@WearThemeCatalog` on a `PreviewWrapperProvider` class, an `ee.schimke.composeai.preview`
 * annotation. A project imported without its maintainers' involvement has no such class, so every
 * imported catalog serves an empty Theme control no matter how many palettes the upstream actually
 * has: Pocket Casts ships nine, Twine twelve, Thunderbird's Bolt two brands crossed with light and
 * dark, and preview.coo.ee can show none of them.
 *
 * The import pipeline already edits its throwaway checkout — it appends to the module's build file,
 * adds runtime-only projects and stubs `google-services.json`. Generating the providers is the same
 * move one level up: the spec says which themes exist, this writes the Kotlin, and everything
 * downstream (discovery → THEME_CATALOG previews → `declaredThemes` → the header's theme chips →
 * `?theme=theme:<fqn>` → per-theme `themes/<fqn>.dtcg.json` token sets) works unchanged. Nothing
 * else in the pipeline learns what an import is.
 *
 * ## Why kinds rather than a line of Kotlin each
 *
 * Every theme could be a hand-written wrapper body, and the first draft of this was exactly that.
 * But across fifteen imports the upstreams reach for the same four shapes, and a shape carries
 * information a snippet throws away: which themes are the same family, which are light and which
 * are dark, and what a reviewer is agreeing to when they approve the import's pull request. A
 * `kind` is checkable — a typo'd enum constant is a spec error here rather than a Kotlin
 * compile failure twenty minutes into a render — and it means the twelfth Twine palette costs a
 * string in an array, not a paragraph of code.
 *
 * - **`enum`** — a theme composable taking one enum constant: `AppThemeWithBackground(LIGHT) {}`.
 *   Pocket Casts (`Theme.ThemeType`, nine) and Twine (`ThemeVariant`, twelve).
 * - **`functions`** — one composable per theme: `ThunderbirdBoltTheme {}` / `K9MailBoltTheme {}`.
 *   Thunderbird's Bolt.
 * - **`modes`** — one composable with a boolean dark parameter: `ElementTheme(darkTheme = true) {}`.
 *   Element X, Bitwarden, and most single-brand Material 3 systems.
 * - **`arguments`** — one composable, a named argument list per theme. The general form the three
 *   above are shorthands for; use it when a theme is a call the others cannot spell.
 * - **`wrapper`** — a raw Kotlin body, the documented escape hatch. Twine's
 *   `overriddenColorScheme = solarizedColorScheme(isDark)` is why it exists.
 *
 * Pure and dependency-free (no `@design-parity/*`, no I/O) so it unit-tests without an `npm ci`,
 * like its siblings `catalog-select.mjs` / `catalog-variants.mjs`. The file-writing half lives in
 * `generate-theme-catalogs.mjs`.
 */

/** The `kind`s a `themes[]` entry may declare. Anything else is a spec error, not a no-op. */
export const THEME_KINDS = Object.freeze([
  "enum",
  "functions",
  "modes",
  "arguments",
  "wrapper",
]);

/** Package the generated providers are declared in. Its own, so it collides with no upstream. */
export const GENERATED_PACKAGE = "ee.schimke.composeai.imported.themes";

/** Imports every generated file needs regardless of shape. */
const BASE_IMPORTS = Object.freeze([
  "androidx.compose.runtime.Composable",
  "androidx.compose.ui.tooling.preview.PreviewWrapperProvider",
  "ee.schimke.composeai.preview.ThemeCatalog",
]);

/**
 * A resolved theme: one generated provider. `name` and `group` become the `@ThemeCatalog`
 * arguments (and so the chip's label and its grouping); `body` is the Kotlin that wraps `content()`.
 */
/**
 * Resolve a spec's `themes[]` into the flat list of providers to generate.
 *
 * Errors are collected rather than thrown, and every one names the entry it came from: a spec is
 * reviewed as a whole in a pull request, so reporting the first mistake and stopping would cost a
 * round trip per typo.
 *
 * @param {object} spec parsed `catalog.spec.json`.
 * @returns {{themes: Array<{name: string, group: string|null, className: string, body: string,
 *   imports: string[]}>, errors: string[]}}
 */
export function resolveThemes(spec) {
  const declared = Array.isArray(spec?.themes) ? spec.themes : [];
  const themes = [];
  const errors = [];
  declared.forEach((entry, index) => {
    const where = `themes[${index}]`;
    const kind = entry?.kind;
    if (!THEME_KINDS.includes(kind)) {
      errors.push(
        `${where}: unknown kind ${JSON.stringify(kind ?? null)} (expected one of ${THEME_KINDS.join(", ")})`,
      );
      return;
    }
    const resolved = RESOLVERS[kind](entry, where, errors);
    for (const theme of resolved) themes.push(theme);
  });
  return { themes: dedupe(themes, errors), errors };
}

const RESOLVERS = {
  enum: resolveEnum,
  functions: resolveFunctions,
  modes: resolveModes,
  arguments: resolveArguments,
  wrapper: resolveWrapper,
};

/**
 * `{ kind: "enum", composable, enum, values }` — one provider per named constant.
 *
 * The constants are LISTED rather than reflected off the enum. Generation happens before the
 * upstream is compiled, so there is nothing to reflect against; and an import's inventory is
 * written down by the human who read the upstream anyway, which is the same bargain `groups`
 * already makes. A listed constant that does not exist fails the module's compile with the
 * constant's own name in the message, which is a better error than a silently thinner catalog.
 */
function resolveEnum(entry, where, errors) {
  const composable = requireFqn(
    entry?.composable,
    `${where}.composable`,
    errors,
  );
  const enumFqn = requireFqn(entry?.enum, `${where}.enum`, errors);
  const values = Array.isArray(entry?.values) ? entry.values : [];
  if (values.length === 0)
    errors.push(`${where}.values: name at least one enum constant`);
  if (!composable || !enumFqn) return [];
  const enumName = simpleName(enumFqn);
  const parameter =
    typeof entry?.parameter === "string" && entry.parameter
      ? `${entry.parameter} = `
      : "";
  return values
    .map((raw) => {
      const value = typeof raw === "string" ? { value: raw } : (raw ?? {});
      const constant = value.value;
      if (typeof constant !== "string" || !constant) {
        errors.push(
          `${where}.values: each entry is an enum constant name, or an object with one`,
        );
        return null;
      }
      return theme({
        entry,
        name: value.name ?? titleCase(constant),
        group: value.group ?? entry.group,
        body: `${simpleName(composable)}(${parameter}${enumName}.${constant}) { content() }`,
        imports: [composable, enumFqn],
      });
    })
    .filter(Boolean);
}

/** `{ kind: "functions", composables: [...] }` — a whole composable per theme, no arguments. */
function resolveFunctions(entry, where, errors) {
  const list = Array.isArray(entry?.composables) ? entry.composables : [];
  if (list.length === 0)
    errors.push(`${where}.composables: name at least one composable`);
  return list
    .map((raw, i) => {
      const item = typeof raw === "string" ? { composable: raw } : (raw ?? {});
      const fqn = requireFqn(
        item.composable,
        `${where}.composables[${i}]`,
        errors,
      );
      if (!fqn) return null;
      return theme({
        entry,
        name: item.name ?? titleCase(simpleName(fqn).replace(/Theme$/, "")),
        group: item.group ?? entry.group,
        body: `${simpleName(fqn)} { content() }`,
        imports: [fqn],
      });
    })
    .filter(Boolean);
}

/**
 * `{ kind: "modes", composable, parameter }` — the single-brand light/dark pair.
 *
 * Worth a kind of its own even though `arguments` spells it, because it is the shape that makes a
 * catalog's theme control *work at all* for the majority of imports: without a declared theme the
 * control is empty, and a system whose only axis is light/dark still needs somewhere for the two
 * chips to come from when its previews bake the pair into one stacked image (Element X) instead of
 * two renders the fold can pair.
 */
function resolveModes(entry, where, errors) {
  const composable = requireFqn(
    entry?.composable,
    `${where}.composable`,
    errors,
  );
  if (!composable) return [];
  const parameter =
    typeof entry?.parameter === "string" && entry.parameter
      ? entry.parameter
      : "darkTheme";
  const modes =
    Array.isArray(entry?.modes) && entry.modes.length > 0
      ? entry.modes
      : ["light", "dark"];
  return modes
    .map((mode) => {
      if (mode !== "light" && mode !== "dark") {
        errors.push(
          `${where}.modes: expected "light" or "dark", got ${JSON.stringify(mode)}`,
        );
        return null;
      }
      return theme({
        entry,
        // The chip label carries the word the mode inference in `ServeRenderHost.inferredThemeMode`
        // reads, so a generated theme reports its own light/dark rather than leaving the serve host
        // to guess from an FQN it minted itself.
        name: `${titleCase(mode)}`,
        group: entry.group,
        body: `${simpleName(composable)}(${parameter} = ${mode === "dark"}) { content() }`,
        imports: [composable],
      });
    })
    .filter(Boolean);
}

/** `{ kind: "arguments", composable, variants: [{name, args}] }` — the general named-argument call. */
function resolveArguments(entry, where, errors) {
  const composable = requireFqn(
    entry?.composable,
    `${where}.composable`,
    errors,
  );
  const variants = Array.isArray(entry?.variants) ? entry.variants : [];
  if (variants.length === 0)
    errors.push(`${where}.variants: name at least one variant`);
  if (!composable) return [];
  return variants
    .map((variant, i) => {
      const name = variant?.name;
      if (typeof name !== "string" || !name) {
        errors.push(`${where}.variants[${i}].name: required`);
        return null;
      }
      const args = Object.entries(variant?.args ?? {})
        .map(([key, value]) => `${key} = ${value}`)
        .join(", ");
      return theme({
        entry,
        name,
        group: variant.group ?? entry.group,
        body: `${simpleName(composable)}(${args}) { content() }`,
        imports: [composable, ...(variant.imports ?? [])],
      });
    })
    .filter(Boolean);
}

/** `{ kind: "wrapper", name, wrapper, imports }` — a raw Kotlin body. The escape hatch. */
function resolveWrapper(entry, where, errors) {
  const name = entry?.name;
  const body = entry?.wrapper;
  if (typeof name !== "string" || !name) errors.push(`${where}.name: required`);
  if (typeof body !== "string" || !body)
    errors.push(`${where}.wrapper: required`);
  if (!name || !body) return [];
  return [
    theme({
      entry,
      name,
      group: entry.group,
      body,
      imports: entry.imports ?? [],
    }),
  ];
}

/**
 * An entry's own `imports` are merged into every theme it expands to, so a shape whose expressions
 * all lean on the same handful of names — Twine's ten palettes each reaching for `ThemeVariant` and
 * `isSystemInDarkTheme` — declares them once instead of once per theme. Unused imports are harmless
 * in Kotlin, and the alternative is ten copies of the same three lines going stale independently.
 */
function theme({ entry, name, group, body, imports }) {
  return {
    name,
    group: typeof group === "string" && group ? group : null,
    className: classNameFor(name),
    body,
    imports: [
      ...new Set(
        [...imports, ...(entry?.imports ?? [])].filter(
          (i) => typeof i === "string" && i,
        ),
      ),
    ].sort(),
  };
}

/**
 * A generated class name is derived from the theme NAME, which is what a reviewer sees and what the
 * serve host addresses the theme by — so two themes that would generate the same class are a spec
 * mistake worth reporting rather than a collision to break with a counter. Reported once per
 * colliding name, and the first occurrence is kept so the rest of the file still generates.
 */
function dedupe(themes, errors) {
  const seen = new Map();
  const out = [];
  for (const t of themes) {
    if (seen.has(t.className)) {
      if (seen.get(t.className) === false) {
        errors.push(
          `two themes resolve to the same provider ${t.className} (from name ${JSON.stringify(t.name)})`,
        );
        seen.set(t.className, true);
      }
      continue;
    }
    seen.set(t.className, false);
    out.push(t);
  }
  return out;
}

/**
 * `Extra dark` → `ImportedTheme_ExtraDark`. Prefixed so it cannot shadow an upstream class.
 *
 * Each word is CASE-NORMALIZED, not merely capitalized, so `Extra dark`, `EXTRA DARK` and
 * `extra-dark` all land on one class. That is what makes the collision check in [dedupe] mean
 * "these are the same theme written twice" rather than "these happen to match character for
 * character" — three spellings of one palette is exactly how a hand-written inventory of somebody
 * else's nine palettes goes wrong, and it should be a spec error, not two chips for one theme.
 */
export function classNameFor(name) {
  const camel = name
    .split(/[^A-Za-z0-9]+/)
    .filter(Boolean)
    .map((word) => word.charAt(0).toUpperCase() + word.slice(1).toLowerCase())
    .join("");
  return `ImportedTheme_${camel || "Unnamed"}`;
}

/** `EXTRA_DARK` → `Extra dark`; `ThunderbirdBolt` → `Thunderbird bolt`. */
export function titleCase(raw) {
  const words = String(raw)
    .replace(/([a-z0-9])([A-Z])/g, "$1 $2")
    .split(/[^A-Za-z0-9]+/)
    .filter(Boolean)
    .map((w) => w.toLowerCase());
  if (words.length === 0) return "";
  return (
    words[0].charAt(0).toUpperCase() +
    words[0].slice(1) +
    (words.length > 1 ? ` ${words.slice(1).join(" ")}` : "")
  );
}

function simpleName(fqn) {
  return fqn.slice(fqn.lastIndexOf(".") + 1);
}

function requireFqn(value, where, errors) {
  if (typeof value !== "string" || !value.includes(".")) {
    errors.push(
      `${where}: expected a fully-qualified name, got ${JSON.stringify(value ?? null)}`,
    );
    return null;
  }
  return value;
}

/**
 * Render the resolved themes as one Kotlin file.
 *
 * One file rather than one per theme because the whole set is generated and deleted together, and a
 * reviewer reading the render log wants the diff of the module's themes in one place.
 *
 * Imports are hoisted and sorted rather than written fully-qualified inline: a `Theme.ThemeType`
 * nested in a class cannot be spelled fully-qualified in an expression position without the
 * enclosing class resolving first, which is exactly the case Pocket Casts hits.
 */
export function renderKotlin(themes, { packageName = GENERATED_PACKAGE } = {}) {
  const imports = [
    ...new Set([...BASE_IMPORTS, ...themes.flatMap((t) => t.imports)]),
  ].sort();
  const header = [
    "// GENERATED by compose-preview from catalog.spec.json's `themes`. Do not edit.",
    "//",
    "// An imported project cannot declare @ThemeCatalog providers itself — it has no dependency on",
    "// this toolchain — so its themes are described in the import's spec and the providers are",
    "// written here, into a throwaway checkout, before discovery runs. See",
    "// scripts/design-artifacts/theme-adapters.mjs.",
    "",
    `package ${packageName}`,
    "",
    ...imports.map((i) => `import ${i}`),
    "",
  ];
  const bodies = themes.map((t) => {
    const args = [`name = ${JSON.stringify(t.name)}`];
    if (t.group) args.push(`group = ${JSON.stringify(t.group)}`);
    return [
      `@ThemeCatalog(${args.join(", ")})`,
      `class ${t.className} : PreviewWrapperProvider {`,
      "  @Composable",
      "  override fun Wrap(content: @Composable () -> Unit) {",
      `    ${t.body}`,
      "  }",
      "}",
    ].join("\n");
  });
  return `${header.join("\n")}\n${bodies.join("\n\n")}\n`;
}
