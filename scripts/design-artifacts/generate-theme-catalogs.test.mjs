import { test } from "node:test";
import assert from "node:assert/strict";
import {
  mkdtempSync,
  mkdirSync,
  readFileSync,
  writeFileSync,
  existsSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

import {
  ensureAnnotationsDependency,
  configurationFor,
  ensureThemePinDependency,
  main,
  sourceSetFor,
} from "./generate-theme-catalogs.mjs";

const scratch = () => mkdtempSync(join(tmpdir(), "theme-catalogs-"));

test("sourceSetFor prefers androidMain, because PreviewWrapperProvider is androidx", () => {
  const root = scratch();
  mkdirSync(join(root, "src/commonMain/kotlin"), { recursive: true });
  mkdirSync(join(root, "src/androidMain/kotlin"), { recursive: true });
  assert.equal(sourceSetFor(root), join(root, "src/androidMain/kotlin"));
});

test("sourceSetFor falls back to src/main/kotlin when the module has no source sets yet", () => {
  const root = scratch();
  assert.equal(sourceSetFor(root), join(root, "src/main/kotlin"));
  assert.equal(
    sourceSetFor(root, "src/debug/kotlin"),
    join(root, "src/debug/kotlin"),
  );
});

test("the annotations dependency is appended once and is idempotent", () => {
  const root = scratch();
  writeFileSync(
    join(root, "build.gradle.kts"),
    'plugins { id("com.android.library") }\n',
  );
  assert.equal(ensureAnnotationsDependency(root, "1.2.3"), "added");
  const first = readFileSync(join(root, "build.gradle.kts"), "utf8");
  // Versionless, through the daemon BOM at the release version: preview-annotations publishes
  // only when it changes, so it need not exist at the daemon release version itself.
  assert.match(
    first,
    /implementation\(platform\("ee\.schimke\.composeai:compose-preview-daemon-bom:1\.2\.3"\)\)/,
  );
  assert.match(
    first,
    /implementation\("ee\.schimke\.composeai:preview-annotations"\)/,
  );
  assert.doesNotMatch(first, /preview-annotations:/);
  assert.equal(ensureAnnotationsDependency(root, "1.2.3"), "present");
  assert.equal(
    readFileSync(join(root, "build.gradle.kts"), "utf8"),
    first,
    "a re-run changes nothing",
  );
});

test("a Groovy build file gets Groovy syntax", () => {
  const root = scratch();
  writeFileSync(
    join(root, "build.gradle"),
    "apply plugin: 'com.android.library'\n",
  );
  assert.equal(ensureAnnotationsDependency(root, "9.9.9"), "added");
  const groovy = readFileSync(join(root, "build.gradle"), "utf8");
  assert.match(
    groovy,
    /implementation platform\('ee\.schimke\.composeai:compose-preview-daemon-bom:9\.9\.9'\)/,
  );
  assert.match(
    groovy,
    /implementation 'ee\.schimke\.composeai:preview-annotations'\n/,
  );
});

test("a version-catalog preview-annotations dependency is not duplicated", () => {
  const root = scratch();
  const build = join(root, "build.gradle.kts");
  const original =
    'dependencies { implementation(libs.composeai.preview.annotations) }\n';
  writeFileSync(build, original);
  assert.equal(ensureAnnotationsDependency(root, "9.9.9"), "present");
  assert.equal(readFileSync(build, "utf8"), original);
});

test("end to end: a spec's themes become a compilable-looking file in the module", () => {
  const root = scratch();
  mkdirSync(join(root, "src/main/kotlin"), { recursive: true });
  writeFileSync(
    join(root, "build.gradle.kts"),
    'plugins { id("com.android.library") }\n',
  );
  const spec = join(root, "catalog.spec.json");
  writeFileSync(
    spec,
    JSON.stringify({
      system: "pocketcasts",
      themes: [
        {
          kind: "enum",
          group: "Pocket Casts",
          composable:
            "au.com.shiftyjelly.pocketcasts.compose.AppThemeWithBackground",
          enum: "au.com.shiftyjelly.pocketcasts.ui.theme.Theme.ThemeType",
          values: ["LIGHT", "ELECTRIC"],
        },
      ],
    }),
  );
  assert.equal(
    main([
      "--spec",
      spec,
      "--module-dir",
      root,
      "--annotations-version",
      "1.2.3",
    ]),
    0,
  );
  const out = join(
    root,
    "src/main/kotlin/ee/schimke/composeai/imported/themes/ImportedThemeCatalogs.kt",
  );
  assert.ok(existsSync(out));
  const kotlin = readFileSync(out, "utf8");
  assert.match(kotlin, /class ImportedTheme_Light : PreviewWrapperProvider/);
  assert.match(kotlin, /class ImportedTheme_Electric : PreviewWrapperProvider/);
  assert.match(
    kotlin,
    /AppThemeWithBackground\(ThemeType\.ELECTRIC\) \{ content\(\) \}/,
  );
});

test("end to end: wear themes generate WearThemeCatalog providers", () => {
  const root = scratch();
  mkdirSync(join(root, "src/main/kotlin"), { recursive: true });
  writeFileSync(join(root, "build.gradle.kts"), "plugins {}\n");
  const spec = join(root, "catalog.spec.json");
  writeFileSync(
    spec,
    JSON.stringify({
      themes: [
        {
          kind: "wrapper",
          wear: true,
          name: "Material",
          wrapper: "MaterialTheme { content() }",
          imports: ["androidx.wear.compose.material3.MaterialTheme"],
        },
      ],
    }),
  );
  assert.equal(
    main([
      "--spec",
      spec,
      "--module-dir",
      root,
      "--annotations-version",
      "3.4.1",
    ]),
    0,
  );
  const kotlin = readFileSync(
    join(
      root,
      "src/main/kotlin/ee/schimke/composeai/imported/themes/ImportedThemeCatalogs.kt",
    ),
    "utf8",
  );
  assert.match(kotlin, /@WearThemeCatalog\(name = "Material"\)/);
});

test("a spec with no themes is a successful no-op, so the pipeline can call it unconditionally", () => {
  const root = scratch();
  const spec = join(root, "catalog.spec.json");
  writeFileSync(spec, JSON.stringify({ system: "x" }));
  assert.equal(main(["--spec", spec, "--module-dir", root]), 0);
  assert.ok(!existsSync(join(root, "src")), "nothing is written");
});

test("a spec error fails the step rather than generating a thinner catalog", () => {
  const root = scratch();
  const spec = join(root, "catalog.spec.json");
  writeFileSync(
    spec,
    JSON.stringify({
      themes: [
        { kind: "enum", composable: "oops", enum: "a.B", values: ["X"] },
      ],
    }),
  );
  assert.equal(
    main([
      "--spec",
      spec,
      "--module-dir",
      root,
      "--annotations-version",
      "1.2.3",
    ]),
    1,
  );
});

test("themes with no annotations version fail rather than writing code that cannot compile", () => {
  const root = scratch();
  const spec = join(root, "catalog.spec.json");
  writeFileSync(
    spec,
    JSON.stringify({
      themes: [{ kind: "wrapper", name: "T", wrapper: "T { content() }" }],
    }),
  );
  assert.equal(main(["--spec", spec, "--module-dir", root]), 1);
});

test("end to end with --theme-pin-version: providers pin and the runtime is added once", () => {
  const root = scratch();
  mkdirSync(join(root, "src/main/kotlin"), { recursive: true });
  writeFileSync(join(root, "build.gradle.kts"), 'plugins { id("com.android.library") }\n');
  const spec = join(root, "catalog.spec.json");
  writeFileSync(
    spec,
    JSON.stringify({
      system: "heron",
      themes: [
        {
          kind: "arguments",
          composable: "com.example.ui.theme.AppTheme",
          variants: [{ name: "Agami", args: { theme: "Theme.Agami" } }],
          imports: ["com.example.ui.theme.Theme"],
        },
      ],
    }),
  );
  const args = [
    "--spec",
    spec,
    "--module-dir",
    root,
    "--annotations-version",
    "1.2.3",
    "--theme-pin-version",
    "2.40.0",
  ];
  assert.equal(main(args), 0);
  assert.equal(main(args), 0, "a retried import is idempotent");
  const kotlin = readFileSync(
    join(root, "src/main/kotlin/ee/schimke/composeai/imported/themes/ImportedThemeCatalogs.kt"),
    "utf8",
  );
  assert.match(kotlin, /\{ PinMaterialTheme \{ content\(\) \} \}/);
  assert.match(kotlin, /import ee\.schimke\.composeai\.preview\.themepin\.PinMaterialTheme/);
  const build = readFileSync(join(root, "build.gradle.kts"), "utf8");
  assert.equal(
    build.split('implementation("ee.schimke.composeai:theme-pin-runtime:2.40.0")').length - 1,
    1,
  );
});

test("ensureThemePinDependency writes Groovy syntax for a Groovy build file", () => {
  const root = scratch();
  writeFileSync(join(root, "build.gradle"), "plugins { id 'com.android.library' }\n");
  assert.equal(ensureThemePinDependency(root, "2.40.0"), "added");
  assert.match(
    readFileSync(join(root, "build.gradle"), "utf8"),
    /\n  implementation 'ee\.schimke\.composeai:theme-pin-runtime:2\.40\.0'\n/,
  );
  assert.equal(ensureThemePinDependency(root, "2.40.0"), "present");
});

test("the desktop lane never generates into androidMain, which its JVM target does not compile", () => {
  const root = scratch();
  mkdirSync(join(root, "src/androidMain/kotlin"), { recursive: true });
  mkdirSync(join(root, "src/commonMain/kotlin"), { recursive: true });
  assert.equal(sourceSetFor(root, undefined, "desktop"), join(root, "src/commonMain/kotlin"));
  mkdirSync(join(root, "src/desktopMain/kotlin"), { recursive: true });
  assert.equal(sourceSetFor(root, undefined, "desktop"), join(root, "src/desktopMain/kotlin"));
  assert.equal(sourceSetFor(root), join(root, "src/androidMain/kotlin"), "android lane unchanged");
});

test("a dependency lands in the configuration that compiles the generated source set", () => {
  assert.equal(configurationFor("/m/src/main/kotlin"), "implementation");
  assert.equal(configurationFor("/m/src/main/java"), "implementation");
  assert.equal(configurationFor("/m/src/commonMain/kotlin"), "commonMainImplementation");
  assert.equal(configurationFor("/m/src/desktopMain/kotlin"), "desktopMainImplementation");
  assert.equal(configurationFor("/m/src/androidMain/kotlin"), "androidMainImplementation");
});

test("end to end on the desktop lane: a KMP module gets commonMain providers and commonMain dependencies", () => {
  const root = scratch();
  mkdirSync(join(root, "src/androidMain/kotlin"), { recursive: true });
  mkdirSync(join(root, "src/commonMain/kotlin"), { recursive: true });
  writeFileSync(join(root, "build.gradle.kts"), 'plugins { kotlin("multiplatform") }\n');
  const spec = join(root, "catalog.spec.json");
  writeFileSync(
    spec,
    JSON.stringify({
      system: "heron",
      themes: [{ kind: "wrapper", name: "Agami", wrapper: "AppTheme(Theme.Herons.Agami) { content() }" }],
    }),
  );
  assert.equal(
    main([
      "--spec", spec, "--module-dir", root, "--annotations-version", "1.2.3",
      "--theme-pin-version", "2.40.0", "--lane", "desktop",
    ]),
    0,
  );
  assert.ok(
    existsSync(join(root, "src/commonMain/kotlin/ee/schimke/composeai/imported/themes/ImportedThemeCatalogs.kt")),
  );
  assert.ok(!existsSync(join(root, "src/androidMain/kotlin/ee")));
  const build = readFileSync(join(root, "build.gradle.kts"), "utf8");
  assert.match(build, /commonMainImplementation\("ee\.schimke\.composeai:theme-pin-runtime:2\.40\.0"\)/);
  assert.match(build, /commonMainImplementation\("ee\.schimke\.composeai:preview-annotations"\)/);
  assert.doesNotMatch(build, /\n  implementation\(/);
});
