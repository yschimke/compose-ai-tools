import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, mkdir, writeFile, readFile, stat } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";

import { mergeManifests, mergeCatalogSection } from "./merge-catalog-section.mjs";

const comp = (componentId, extra = {}) => ({
  componentId,
  images: [{ variant: "ideal", path: `images/${componentId}/x.png`, state: "default", width: 10, height: 10 }],
  greenlines: [],
  redlines: [],
  ...extra,
});

const manifest = (components) => ({
  schema: "design-parity-catalog/v1",
  system: "host",
  title: "Host",
  components,
});

test("mergeManifests appends borrowed components under the given section, host first", () => {
  const primary = manifest([comp("Device/Card", { section: "Components" })]);
  const borrowed = manifest([comp("Buttons/Filled", { group: "Buttons" })]);

  const merged = mergeManifests(primary, borrowed, { section: "Material 3" });

  assert.equal(merged.components.length, 2);
  // Host component untouched and first…
  assert.equal(merged.components[0].componentId, "Device/Card");
  assert.equal(merged.components[0].section, "Components");
  // …borrowed component appended, retagged to the new tab, group preserved.
  assert.equal(merged.components[1].componentId, "Buttons/Filled");
  assert.equal(merged.components[1].section, "Material 3");
  assert.equal(merged.components[1].group, "Buttons");
  // Host meta is preserved.
  assert.equal(merged.system, "host");
});

test("mergeManifests strips borrowed live-preview deep links", () => {
  const borrowed = manifest([
    comp("Buttons/Filled", {
      images: [
        {
          variant: "ideal",
          path: "images/buttons-filled/x.png",
          state: "default",
          width: 10,
          height: 10,
          livePreview: "https://preview.coo.ee/compose-m3/p/FilledButton",
        },
      ],
    }),
  ]);

  const merged = mergeManifests(manifest([]), borrowed, { section: "Material 3" });

  assert.equal("livePreview" in merged.components[0].images[0], false);
  // The rest of the image entry is preserved.
  assert.equal(merged.components[0].images[0].path, "images/buttons-filled/x.png");
});

test("mergeManifests can prefix the borrowed group", () => {
  const borrowed = manifest([comp("Buttons/Filled", { group: "Buttons" })]);
  const merged = mergeManifests(manifest([]), borrowed, {
    section: "Material 3",
    groupPrefix: "M3 · ",
  });
  assert.equal(merged.components[0].group, "M3 · Buttons");
});

test("mergeManifests throws on a componentId that exists in both catalogs", () => {
  const primary = manifest([comp("Buttons/Filled")]);
  const borrowed = manifest([comp("Buttons/Filled")]);
  assert.throws(() => mergeManifests(primary, borrowed, { section: "Material 3" }), /componentId/);
});

test("mergeManifests requires a section name", () => {
  assert.throws(() => mergeManifests(manifest([]), manifest([]), {}), /section/);
});

test("mergeCatalogSection folds assets in and rewrites catalog.json", async () => {
  const root = await mkdtemp(join(tmpdir(), "merge-catalog-"));
  const into = join(root, "into");
  const from = join(root, "from");

  // Primary catalog: one component + its baked image.
  await mkdir(join(into, "images/Device_Card"), { recursive: true });
  await writeFile(
    join(into, "catalog.json"),
    JSON.stringify(manifest([comp("Device/Card", { section: "Components" })])),
  );
  await writeFile(join(into, "images/Device_Card/x.png"), "HOST-IMG");

  // Borrowed catalog: one component, its image, and a token file that must NOT copy.
  await mkdir(join(from, "images/Buttons_Filled"), { recursive: true });
  await writeFile(join(from, "catalog.json"), JSON.stringify(manifest([comp("Buttons/Filled")])));
  await writeFile(join(from, "images/Buttons_Filled/x.png"), "M3-IMG");
  await writeFile(join(from, "tokens.dtcg.json"), "{}");

  const res = await mergeCatalogSection({ into, from, section: "Material 3" });

  assert.equal(res.componentsAdded, 1);
  assert.equal(res.filesCopied, 1); // the borrowed image, not tokens.dtcg.json

  const merged = JSON.parse(await readFile(join(into, "catalog.json"), "utf8"));
  assert.deepEqual(
    merged.components.map((c) => [c.componentId, c.section]),
    [["Device/Card", "Components"], ["Buttons/Filled", "Material 3"]],
  );
  // Borrowed image copied in; borrowed token file NOT copied.
  assert.equal(await readFile(join(into, "images/Buttons_Filled/x.png"), "utf8"), "M3-IMG");
  assert.equal(await stat(join(into, "tokens.dtcg.json")).then(() => true, () => false), false);
  // Host image untouched.
  assert.equal(await readFile(join(into, "images/Device_Card/x.png"), "utf8"), "HOST-IMG");
});

test("mergeCatalogSection refuses to overwrite a differing asset", async () => {
  const root = await mkdtemp(join(tmpdir(), "merge-catalog-collide-"));
  const into = join(root, "into");
  const from = join(root, "from");
  // Same asset relative path, different bytes → a real collision.
  await mkdir(join(into, "images/Shared"), { recursive: true });
  await mkdir(join(from, "images/Shared"), { recursive: true });
  await writeFile(join(into, "catalog.json"), JSON.stringify(manifest([comp("Host/One")])));
  await writeFile(join(from, "catalog.json"), JSON.stringify(manifest([comp("M3/Two")])));
  await writeFile(join(into, "images/Shared/x.png"), "AAAA");
  await writeFile(join(from, "images/Shared/x.png"), "BBBB");

  await assert.rejects(
    mergeCatalogSection({ into, from, section: "Material 3" }),
    /differs between the two catalogs/,
  );
});
