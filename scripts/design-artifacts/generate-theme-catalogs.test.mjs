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
  assert.match(
    first,
    /implementation\("ee\.schimke\.composeai:preview-annotations:1\.2\.3"\)/,
  );
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
  assert.match(
    readFileSync(join(root, "build.gradle"), "utf8"),
    /implementation 'ee\.schimke\.composeai:preview-annotations:9\.9\.9'/,
  );
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
