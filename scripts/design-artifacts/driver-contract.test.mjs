// The checks that the export driver agrees with THIS repository: its workflow, its Kotlin, its
// sample catalogs and fonts, the lock it installs the driver from, and the two schemas it serves at
// public `$schema` URLs. The driver's source and its own tests live in design-parity's
// packages/export-driver; these are the few that need this checkout to mean anything, so they run
// here, against the installed package.
//
// DRIVER_DIR names the installed driver. `.github/scripts/install-export-driver.sh` exports it in
// CI; locally:
//
//   DRIVER_DIR=$(.github/scripts/install-export-driver.sh "$(mktemp -d)") \
//     node --test scripts/design-artifacts/driver-contract.test.mjs

import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { readFile } from "node:fs/promises";
import { dirname, join, resolve } from "node:path";
import { test } from "node:test";
import { fileURLToPath, pathToFileURL } from "node:url";

const REPO_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "..", "..");
const DRIVER_DIR = process.env.DRIVER_DIR;
if (!DRIVER_DIR || !existsSync(join(DRIVER_DIR, "package.json"))) {
  throw new Error(
    "DRIVER_DIR must name an installed @design-parity/export-driver — run " +
      "`.github/scripts/install-export-driver.sh <dest>` and pass the directory it prints",
  );
}

const driver = (name) => import(pathToFileURL(join(DRIVER_DIR, name)).href);
const repoFile = (rel) => readFile(join(REPO_ROOT, rel), "utf8");

const { FONT_FACES, fontFaceCss } = await driver("rc-fonts.mjs");
const { STRUCTURAL_ROLES, FILE_TEMPLATES, TEMPLATE_DIR } = await driver("ui-builder-policy.mjs");
const {
  discoverComponentIds,
  discoverVariantFunctions,
  discoverPreviews,
  hasCatalogAnnotations,
  validateSpec,
} = await driver("catalog-spec.mjs");
const { moduleToDir, collectKotlinSources } = await driver("catalog-spec-io.mjs");

// The schemas are served from this repository's `main` — both sample specs, and every catalog
// repository that copied one, name `raw.githubusercontent.com/yschimke/compose-ai-tools/main/
// scripts/design-artifacts/catalog.spec.schema.json` — and the driver validates against its own
// copy. Two copies of one contract: they must be the same bytes, so a schema change lands in
// design-parity and is copied here in the PR that bumps the lock.
for (const schema of ["catalog.spec.schema.json", "ui-builder.policy.schema.json"]) {
  test(`${schema} is the installed driver's copy`, () => {
    assert.equal(
      readFileSync(join(REPO_ROOT, "scripts/design-artifacts", schema), "utf8"),
      readFileSync(join(DRIVER_DIR, schema), "utf8"),
      `scripts/design-artifacts/${schema} differs from the driver's — copy it from ` +
        "@design-parity/export-driver at the version .github/design-artifacts-driver/ pins",
    );
  });
}

// The Remote Compose parity lanes register these faces from the catalog's own vendored fonts. A face
// the driver names but this repository does not carry falls back to a substituted typeface, which
// reads as a small parity regression spread across every preview with text.
const FONTS_DIR = join(REPO_ROOT, "samples/cmp-wasm-catalog/src/wasmJsMain/resources/fonts");

test("every face the driver registers is vendored in samples/cmp-wasm-catalog", () => {
  for (const { file } of FONT_FACES) {
    assert.ok(existsSync(join(FONTS_DIR, file)), `${file} missing from ${FONTS_DIR}`);
  }
});

test("fontFaceCss inlines one @font-face per vendored face and needs no network", () => {
  const css = fontFaceCss(FONTS_DIR);
  assert.equal((css.match(/@font-face/g) ?? []).length, FONT_FACES.length);
  assert.ok(css.includes("data:font/ttf;base64,"), "faces must be inlined, not fetched");
  assert.ok(!/url\((?!data:)/.test(css), "no non-data: url() — the page has no server");
});

// v1.60.0 passed shard exclusions as one comma-separated argv line, which v1.60.1's
// --exclude-preview-id-file read as one impossible pattern, so every shard baked the full catalog
// (#4916). The planner writes one id per line; the workflow must hand the file over as a file.
test("the reusable workflow passes shard exclusions by file, not through argv", async () => {
  const workflow = await repoFile(".github/workflows/design-artifacts-reusable.yml");
  assert.match(
    workflow,
    /exclude=\(--exclude-preview-id-file "\$GITHUB_WORKSPACE\/shard-exclude\.txt"\)/,
  );
  assert.doesNotMatch(workflow, /shard_ids="\$\(cat "\$GITHUB_WORKSPACE\/shard-exclude\.txt"\)"/);
  assert.match(workflow, /--verify-exclusions/);
  assert.match(workflow, /title=Shard exclusion format skew/);
});

// Two lists in two languages, and the contract is that a policy the driver's pre-flight accepts is
// one the generator accepts. Pinned rather than trusted: they are edited months apart.
test("the builder-policy role vocabulary matches the Kotlin the generator reads", async () => {
  const kotlin = await repoFile(
    "screen/generator/src/commonMain/kotlin/ee/schimke/composeai/discovery/UiBuilderPolicy.kt",
  );
  const declared = kotlin
    .slice(kotlin.indexOf("UI_BUILDER_STRUCTURAL_ROLES"))
    .match(/setOf\(([^)]*)\)/)[1]
    .split(",")
    .map((entry) => entry.trim().replace(/^"|"$/g, ""))
    .filter(Boolean);
  assert.deepEqual(declared, STRUCTURAL_ROLES);
  assert.deepEqual(FILE_TEMPLATES, ["previews", "file"]);
  for (const name of FILE_TEMPLATES) assert.ok(!STRUCTURAL_ROLES.includes(name));
});

// `UiBuilderTemplateLookup` silently drops a template path outside its directory, so the pre-flight
// rejects one; the two must agree on what that directory is.
test("the builder-policy template directory is the one the Gradle plugin looks in", async () => {
  const lookup = await repoFile(
    "gradle-plugin/src/main/kotlin/ee/schimke/composeai/plugin/UiBuilderTemplateLookup.kt",
  );
  assert.match(lookup, new RegExp(`UI_BUILDER_DIR: String = "${TEMPLATE_DIR}"`));
});

// Every committed sample spec must resolve every `preview` against its module's @Preview
// functions, so a renamed sample preview fails here instead of in the weekly Design Artifacts run.
for (const rel of [
  "samples/design-catalog-m3/catalog.spec.json",
  "samples/design-catalog-wear-m3/catalog.spec.json",
]) {
  test(`sample spec ${rel} resolves all previews against its module`, async () => {
    const spec = JSON.parse(await repoFile(rel));
    const sources = await collectKotlinSources([resolve(REPO_ROOT, moduleToDir(spec.module))]);
    const { previews, pngLess } = discoverPreviews(sources);
    assert.ok(previews.length > 0, `discovered no @Preview functions for ${rel}`);
    const { errors } = validateSpec(spec, {
      knownPreviews: previews,
      pngLessPreviews: pngLess,
      knownComponentIds: discoverComponentIds(sources),
      variantFunctions: discoverVariantFunctions(sources),
      annotatedInventory: hasCatalogAnnotations(sources),
    });
    assert.deepEqual(errors, [], `${rel} has spec errors:\n${errors.join("\n")}`);
  });
}

// The catalog records the export engine's version as provenance, resolved through node_modules
// from the driver. Against a full install it must find the copy the lock names, not another one.
test("the driver resolves the export engine version the lock installed", async (t) => {
  const engine = "@design-parity/catalog-export";
  const lock = JSON.parse(await repoFile(".github/design-artifacts-driver/package-lock.json"));
  const locked = lock.packages[`node_modules/${engine}`]?.version;
  assert.match(locked ?? "", /^\d+\.\d+\.\d+/, `the lock names no ${engine}`);
  const { installedPackageVersion } = await driver("package-version.mjs");
  const found = installedPackageVersion(engine, pathToFileURL(join(DRIVER_DIR, "package-version.mjs")).href);
  if (found === undefined && !existsSync(join(DRIVER_DIR, "..", "..", engine))) {
    t.skip("this install has no dependencies; run against install-export-driver.sh's");
    return;
  }
  assert.equal(found, locked);
});
