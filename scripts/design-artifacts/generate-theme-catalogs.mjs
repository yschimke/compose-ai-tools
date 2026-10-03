#!/usr/bin/env node
/**
 * Write the `@ThemeCatalog` / `@WearThemeCatalog` providers a spec's `themes[]` declares into a
 * module's source set, and put the annotation artifact on that module's compile classpath.
 *
 * The I/O half of `theme-adapters.mjs` (which is pure and holds the shapes). Runs against a
 * THROWAWAY checkout — the import pipeline's clone of somebody else's repository — before
 * discovery, because discovery scans compiled classes and the providers have to be among them.
 *
 *   node scripts/design-artifacts/generate-theme-catalogs.mjs \
 *     --spec catalog.spec.json --module-dir modules/services/compose \
 *     --annotations-version 1.2.3
 *
 * `--annotations-version` is the compose-preview-daemon RELEASE (the `composeai-preview-daemon`
 * catalog pin), not preview-annotations' own version — see [ensureAnnotationsDependency]. The flag
 * keeps its name because the reusable workflow passes it to whichever driver release
 * `.github/design-artifacts-driver-pin.txt` checks out, older ones included.
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

/**
 * Source sets for a module rendered on the desktop lane, most-specific first.
 *
 * The desktop lane compiles a KMP module's JVM target, which never sees `androidMain` — a provider
 * written there is simply absent from the render. Compose Multiplatform ships
 * `PreviewWrapperProvider` for desktop, so the JVM target's own source set, else `commonMain`, is
 * where a desktop render finds it.
 */
const DESKTOP_SOURCE_SETS = Object.freeze([
  "src/desktopMain/kotlin",
  "src/jvmMain/kotlin",
  "src/commonMain/kotlin",
  "src/main/kotlin",
  "src/main/java",
]);

/**
 * The source-set directory to generate into: the override, else the first that exists for the
 * render [lane] (`android`, the default, or `desktop`).
 */
export function sourceSetFor(moduleDir, override, lane = "android") {
  if (override) return join(moduleDir, override);
  const sets = lane === "desktop" ? DESKTOP_SOURCE_SETS : SOURCE_SETS;
  const found = sets.find((set) => existsSync(join(moduleDir, set)));
  return found ? join(moduleDir, found) : join(moduleDir, "src/main/kotlin");
}

/**
 * The dependency configuration that compiles [sourceSetDir]: `implementation` for a plain module's
 * `src/main`, `<sourceSet>Implementation` for a KMP source set — a KMP module has no top-level
 * `implementation`, and a dependency the generated source needs must reach the source set it is in.
 */
export function configurationFor(sourceSetDir) {
  const parts = sourceSetDir.split(/[\\/]/);
  const set = parts[parts.lastIndexOf("src") + 1];
  return !set || set === "main" ? "implementation" : `${set}Implementation`;
}

/**
 * Append the `preview-annotations` dependency to a module's build file.
 *
 * Versionless, through `compose-preview-daemon-bom` at [daemonVersion] — the daemon release. The
 * daemon publishes only the modules a release changes, so preview-annotations is not guaranteed to
 * exist at the release version (the 3.9.1 line mapped it to 3.8.4); pinning it there 404s on the
 * first release that skips it. The BOM is the record of which version belongs to the release. The
 * same shape `integration.yml` appends for its XR overlay.
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
  daemonVersion,
  { configuration = "implementation" } = {},
) {
  const buildFile = BUILD_FILES.map((f) => join(moduleDir, f)).find((f) =>
    existsSync(f),
  );
  if (!buildFile) return "no-build-file";
  const text = readFileSync(buildFile, "utf8");
  const catalogAlias =
    /\b(?:api|implementation|compileOnly)\s*\(\s*libs(?:\.[A-Za-z0-9_]+)*\.preview(?:\.[A-Za-z0-9_]+)*\.annotations\b/i;
  if (
    text.includes("ee.schimke.composeai:preview-annotations") ||
    catalogAlias.test(text)
  )
    return "present";
  const kts = buildFile.endsWith(".kts");
  const bom = `ee.schimke.composeai:compose-preview-daemon-bom:${daemonVersion}`;
  const coordinate = "ee.schimke.composeai:preview-annotations";
  const line = kts
    ? `  ${configuration}(platform("${bom}"))\n  ${configuration}("${coordinate}")`
    : `  ${configuration} platform('${bom}')\n  ${configuration} '${coordinate}'`;
  writeFileSync(
    buildFile,
    `${text}\n\n// compose-preview import: catalog annotations for the generated theme providers under\n` +
      `// ${GENERATED_PACKAGE}. Added to a throwaway checkout only.\ndependencies {\n${line}\n}\n`,
  );
  return "added";
}

/**
 * Append `theme-pin-runtime` at [version] — the compose-preview plugin version the render injects —
 * so the generated providers' `PinMaterialTheme` compiles. Same append-once shape as
 * [ensureAnnotationsDependency]. The plugin itself adds the runtime to the render's runtime
 * classpath; this is the compile half the generated source needs.
 *
 * @returns {"added"|"present"|"no-build-file"}
 */
export function ensureThemePinDependency(
  moduleDir,
  version,
  { configuration = "implementation" } = {},
) {
  const buildFile = BUILD_FILES.map((f) => join(moduleDir, f)).find((f) =>
    existsSync(f),
  );
  if (!buildFile) return "no-build-file";
  const text = readFileSync(buildFile, "utf8");
  const coordinate = "ee.schimke.composeai:theme-pin-runtime";
  if (text.includes(coordinate)) return "present";
  const line = buildFile.endsWith(".kts")
    ? `  ${configuration}("${coordinate}:${version}")`
    : `  ${configuration} '${coordinate}:${version}'`;
  writeFileSync(
    buildFile,
    `${text}\n\n// compose-preview import: PinMaterialTheme for the generated theme providers under\n` +
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

  const sourceSet = sourceSetFor(moduleDir, args["source-set"], args.lane);
  const configuration = configurationFor(sourceSet);
  const outFile = join(
    sourceSet,
    ...GENERATED_PACKAGE.split("."),
    "ImportedThemeCatalogs.kt",
  );
  mkdirSync(dirname(outFile), { recursive: true });
  // `--theme-pin-version` turns pinning on: the workflow passes it only once that plugin version
  // publishes theme-pin-runtime, so a provider never references a class that cannot resolve.
  const pinVersion = args["theme-pin-version"];
  const pin = typeof pinVersion === "string" && pinVersion.length > 0;
  writeFileSync(outFile, renderKotlin(themes, { pin }));
  if (pin && ensureThemePinDependency(moduleDir, pinVersion, { configuration }) === "no-build-file") {
    console.error(
      `::error::no build.gradle[.kts] in ${moduleDir}; cannot add theme-pin-runtime`,
    );
    return 1;
  }

  const dependency = ensureAnnotationsDependency(moduleDir, version, { configuration });
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
    `preview-annotations (compose-preview-daemon-bom:${version}) ${dependency === "added" ? "added to" : "already in"} ${moduleDir}`,
  );
  console.log(
    pin
      ? `theme pinning on: providers wrap content in PinMaterialTheme (theme-pin-runtime:${pinVersion})`
      : "theme pinning off: providers wrap content directly",
  );
  return 0;
}

if (import.meta.url === `file://${process.argv[1]}`) process.exit(main());
