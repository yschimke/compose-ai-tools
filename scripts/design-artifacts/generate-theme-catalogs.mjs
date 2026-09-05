#!/usr/bin/env node
/**
 * Write the `@ThemeCatalog` providers a spec's `themes[]` declares into a module's source set, and
 * put the annotation artifact on that module's compile classpath.
 *
 * The I/O half of `theme-adapters.mjs` (which is pure and holds the shapes). Runs against a
 * THROWAWAY checkout — the import pipeline's clone of somebody else's repository — before
 * discovery, because discovery scans compiled classes and the providers have to be among them.
 *
 *   node scripts/design-artifacts/generate-theme-catalogs.mjs \
 *     --spec catalog.spec.json --module-dir modules/services/compose \
 *     --annotations-version 1.2.3
 *
 * Exits 0 having done nothing when the spec declares no themes, so the pipeline can call it
 * unconditionally rather than gating on a field it would have to parse twice.
 */

import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";

import {
  GENERATED_PACKAGE,
  renderKotlin,
  resolveThemes,
} from "./theme-adapters.mjs";

/**
 * Source sets to write into, most-specific first.
 *
 * `androidMain` beats `commonMain` deliberately: `PreviewWrapperProvider` is an androidx type, and
 * a KMP module's common source set compiles for targets that have no androidx at all — Bolt and
 * Twine are both commonMain-first modules that render on the Robolectric lane, so their generated
 * providers belong on the Android side even though the themes they wrap are common. A module with
 * neither is a plain Android module and takes `src/main`.
 */
const SOURCE_SETS = Object.freeze([
  "src/androidMain/kotlin",
  "src/main/kotlin",
  "src/main/java",
  "src/commonMain/kotlin",
]);

const BUILD_FILES = Object.freeze(["build.gradle.kts", "build.gradle"]);

function parseArgs(argv) {
  const args = {};
  for (let i = 0; i < argv.length; i += 1) {
    const key = argv[i];
    if (!key.startsWith("--")) continue;
    args[key.slice(2)] = argv[i + 1]?.startsWith("--") ? true : argv[++i];
  }
  return args;
}

/** The source-set directory to generate into: the override, else the first that exists. */
export function sourceSetFor(moduleDir, override) {
  if (override) return join(moduleDir, override);
  const found = SOURCE_SETS.find((set) => existsSync(join(moduleDir, set)));
  return found ? join(moduleDir, found) : join(moduleDir, SOURCE_SETS[1]);
}

/**
 * Append the `preview-annotations` dependency to a module's build file.
 *
 * Appended rather than merged into an existing `dependencies { }` block for the same reason the
 * pipeline appends its plugin configuration: parsing somebody else's Gradle script to splice into
 * it is a losing game, and Gradle is perfectly happy with a second `dependencies { }`. Idempotent —
 * a re-run (or a module that already depends on it) is a no-op, so a retried import does not stack
 * duplicate blocks.
 *
 * @returns {"added"|"present"|"no-build-file"}
 */
export function ensureAnnotationsDependency(
  moduleDir,
  version,
  { configuration = "implementation" } = {},
) {
  const buildFile = BUILD_FILES.map((f) => join(moduleDir, f)).find((f) =>
    existsSync(f),
  );
  if (!buildFile) return "no-build-file";
  const text = readFileSync(buildFile, "utf8");
  if (text.includes("ee.schimke.composeai:preview-annotations"))
    return "present";
  const kts = buildFile.endsWith(".kts");
  const coordinate = `ee.schimke.composeai:preview-annotations:${version}`;
  const line = kts
    ? `  ${configuration}("${coordinate}")`
    : `  ${configuration} '${coordinate}'`;
  writeFileSync(
    buildFile,
    `${text}\n\n// compose-preview import: @ThemeCatalog, for the generated theme providers under\n` +
      `// ${GENERATED_PACKAGE}. Added to a throwaway checkout only.\ndependencies {\n${line}\n}\n`,
  );
  return "added";
}

export function main(argv = process.argv.slice(2)) {
  const args = parseArgs(argv);
  const specPath = args.spec ?? "catalog.spec.json";
  const moduleDir = args["module-dir"] ?? ".";
  const spec = JSON.parse(readFileSync(specPath, "utf8"));

  const { themes, errors } = resolveThemes(spec);
  if (errors.length > 0) {
    for (const error of errors) console.error(`::error::${specPath}: ${error}`);
    return 1;
  }
  if (themes.length === 0) {
    console.log(`${specPath} declares no themes; nothing to generate.`);
    return 0;
  }

  const version = args["annotations-version"];
  if (!version) {
    console.error(
      "::error::--annotations-version is required once a spec declares themes",
    );
    return 1;
  }

  const outFile = join(
    sourceSetFor(moduleDir, args["source-set"]),
    ...GENERATED_PACKAGE.split("."),
    "ImportedThemeCatalogs.kt",
  );
  mkdirSync(dirname(outFile), { recursive: true });
  writeFileSync(outFile, renderKotlin(themes));

  const dependency = ensureAnnotationsDependency(moduleDir, version);
  if (dependency === "no-build-file") {
    console.error(
      `::error::no build.gradle[.kts] in ${moduleDir}; cannot add preview-annotations`,
    );
    return 1;
  }

  console.log(`Generated ${themes.length} theme provider(s) → ${outFile}`);
  for (const theme of themes) {
    console.log(
      `  ${theme.className}  ${theme.name}${theme.group ? ` (${theme.group})` : ""}`,
    );
  }
  console.log(
    `preview-annotations:${version} ${dependency === "added" ? "added to" : "already in"} ${moduleDir}`,
  );
  return 0;
}

if (import.meta.url === `file://${process.argv[1]}`) process.exit(main());
