// Regression guard: every committed sample catalog.spec.json must resolve every
// `preview` against its module's @Preview functions. If someone renames a sample
// preview without updating its spec (or vice versa), this fails here instead of
// only in the weekly Design Artifacts render. Dogfoods validate-catalog-spec's
// library against the real specs.

import { test } from "node:test";
import assert from "node:assert/strict";
import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";
import { readFile } from "node:fs/promises";

import { discoverPreviews, hasCatalogAnnotations, validateSpec } from "./catalog-spec.mjs";
import { moduleToDir, collectKotlinSources } from "./catalog-spec-io.mjs";

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..", "..");

const SAMPLE_SPECS = [
  "samples/design-catalog-m3/catalog.spec.json",
  "samples/design-catalog-wear-m3/catalog.spec.json",
  "samples/design-catalog-remote-m3/catalog.spec.json",
];

for (const rel of SAMPLE_SPECS) {
  test(`sample spec ${rel} resolves all previews against its module`, async () => {
    const specPath = resolve(repoRoot, rel);
    const spec = JSON.parse(await readFile(specPath, "utf8"));
    // spec.module is repo-root-relative; the test knows the repo root, so resolve
    // the module directory here rather than relying on cwd-based derivation.
    const moduleDir = resolve(repoRoot, moduleToDir(spec.module));
    const sources = await collectKotlinSources([moduleDir]);
    const { previews, pngLess } = discoverPreviews(sources);
    assert.ok(previews.length > 0, `discovered no @Preview functions for ${rel}`);
    // Pass whether the module carries @CatalogComponent annotations, so a cover-sheet-only spec
    // (no `groups`) is accepted iff its inventory really is annotation-supplied. `pngLess` catches
    // the other half of the same class of bug: a spec entry pointing at a GIF-only capture the
    // export drops (issue #2865).
    const { errors } = validateSpec(spec, {
      knownPreviews: previews,
      pngLessPreviews: pngLess,
      annotatedInventory: hasCatalogAnnotations(sources),
    });
    assert.deepEqual(errors, [], `${rel} has spec errors:\n${errors.join("\n")}`);
  });
}
