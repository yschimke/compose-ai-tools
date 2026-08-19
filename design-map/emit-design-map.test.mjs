// The CLI's FAILURE POSTURE, which is the part of this package a catalog's CI depends on and the
// part the pure-projection tests in design-map.test.mjs cannot reach: `--strict` decides whether a
// build goes red, and it decides it from `diagnostics`, not from the map.
import { test } from "node:test";
import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const CLI = fileURLToPath(new URL("./emit-design-map.mjs", import.meta.url));
const FILE = "AbCdEf";

/** A dark-only capture: no mode segment in the id, which is what a Wear catalog publishes. */
const component = (name, catalog) => ({
  id: `com.example.CatalogKt.${name}`,
  functionName: name,
  sourceFile: "Catalog.kt",
  catalog: { role: "COMPONENT", componentId: name, ...catalog },
});

/** Run the CLI over `previews` in a throwaway directory. */
function run(previews, ...flags) {
  const dir = mkdtempSync(path.join(tmpdir(), "emit-design-map-"));
  try {
    const manifest = path.join(dir, "previews.json");
    writeFileSync(manifest, JSON.stringify({ previews }));
    const result = spawnSync(
      process.execPath,
      [
        CLI,
        "--previews",
        manifest,
        "--out",
        path.join(dir, "design-map.json"),
        "--variants",
        path.join(dir, "design-map-variants.json"),
        ...flags,
      ],
      { encoding: "utf8" },
    );
    return { ...result, all: `${result.stdout}${result.stderr}` };
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
}

const MAPPED = component("FilledButton", { reference: `figma:${FILE}/1:1` });
const STATED = component("ButtonGroup", { noReference: "The kit publishes no button-group set." });
const SILENT = component("Mystery", {});

test("--strict fails on a stated absence, because it says there are no exceptions", () => {
  const { status, all } = run([MAPPED, STATED], "--strict");
  assert.equal(status, 1);
  assert.match(all, /ButtonGroup — The kit publishes no button-group set\./);
  // The remedy has to be discoverable from the message, or the only way to find the opt-in is to
  // read this package's source.
  assert.match(all, /--allow-stated-absence/);
});

test("--strict --allow-stated-absence accepts an absence somebody wrote down", () => {
  const { status, all } = run([MAPPED, STATED], "--strict", "--allow-stated-absence");
  assert.equal(status, 0, all);
  assert.match(all, /1 mapped component\(s\)/);
});

test("--allow-stated-absence does NOT excuse silence — that is what strictness is for", () => {
  const { status, all } = run([MAPPED, STATED, SILENT], "--strict", "--allow-stated-absence");
  assert.equal(status, 1);
  assert.match(all, /Mystery — no reference, and no reason given/);
  // The one it was told to allow must not be listed beside the one it was not.
  assert.doesNotMatch(all, /ButtonGroup/);
});

test("--allow-stated-absence alone changes nothing: absences are reported, never fatal", () => {
  const { status, all } = run([MAPPED, STATED, SILENT], "--allow-stated-absence");
  assert.equal(status, 0, all);
  assert.match(all, /1 component\(s\) have no reference for a stated reason/);
});

test("a dark-only catalog maps under --strict, having no Light capture to pair with", () => {
  const { status, all } = run([MAPPED], "--strict");
  assert.equal(status, 0, all);
  assert.match(all, /1 mapped component\(s\)/);
});

// A component publishing several modes with none of them Light is `ambiguousMode`, and
// `participates()` is false for every one of its captures. Absence used to be read inside that
// filter, so such a component fell out of the absence diagnostics entirely and was reported only as
// an ambiguous mode — making a STATED absence fatal under the flag that exists to accept it.
const statedTwoModes = (mode) => ({
  id: `com.example.CatalogKt.ButtonGroup_${mode}`,
  functionName: "ButtonGroup",
  sourceFile: "Catalog.kt",
  catalog: {
    role: "COMPONENT",
    componentId: "ButtonGroup",
    noReference: "The kit publishes no button-group set.",
  },
});

test("a stated absence survives an ambiguous mode, and the flag still accepts it", () => {
  const previews = [MAPPED, statedTwoModes("Dark"), statedTwoModes("Coral")];

  const strict = run(previews, "--strict");
  assert.equal(strict.status, 1);
  assert.match(strict.all, /ButtonGroup — The kit publishes no button-group set\./);
  // Reported ONCE, as the absence it is — not a second time as an unpairable capture.
  assert.doesNotMatch(strict.all, /none of them Light/);

  const allowed = run(previews, "--strict", "--allow-stated-absence");
  assert.equal(allowed.status, 0, allowed.all);
});

test("an unexplained absence is unmapped, not ambiguous, however many modes it draws", () => {
  const silentTwoModes = (mode) => ({
    id: `com.example.CatalogKt.Mystery_${mode}`,
    functionName: "Mystery",
    sourceFile: "Catalog.kt",
    catalog: { role: "COMPONENT", componentId: "Mystery" },
  });
  const { status, all } = run(
    [MAPPED, silentTwoModes("Dark"), silentTwoModes("Coral")],
    "--strict",
    "--allow-stated-absence",
  );
  assert.equal(status, 1);
  assert.match(all, /Mystery — no reference, and no reason given/);
  assert.doesNotMatch(all, /none of them Light/);
});

test("an ambiguous mode is still fatal when the component HAS a reference to pair", () => {
  const themed = (mode) => ({
    id: `com.example.CatalogKt.Themed_${mode}`,
    functionName: "Themed",
    sourceFile: "Catalog.kt",
    catalog: { role: "COMPONENT", componentId: "Themed", reference: `figma:${FILE}/2:2` },
  });
  const { status, all } = run(
    [MAPPED, themed("Dark"), themed("Coral")],
    "--strict",
    "--allow-stated-absence",
  );
  assert.equal(status, 1);
  assert.match(all, /none of them Light/);
});
