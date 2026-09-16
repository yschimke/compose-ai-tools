import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdirSync, mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { spawnSync } from "node:child_process";

const script = join(dirname(fileURLToPath(import.meta.url)), "emit-design-pages.mjs");

test("--config selects a per-system design-page cache", () => {
  const repo = mkdtempSync(join(tmpdir(), "emit-design-pages-test-"));
  const out = join(repo, "out");
  const pages = join(repo, "design", "glimmer-pages");
  mkdirSync(out, { recursive: true });
  mkdirSync(pages, { recursive: true });

  writeFileSync(join(out, "catalog.json"), JSON.stringify({ components: [] }));
  writeFileSync(
    join(repo, "glimmer-catalog.spec.json"),
    JSON.stringify({
      referenceKits: ["https://www.figma.com/design/glimmer/Jetpack-Compose-Glimmer-UI"],
    }),
  );
  writeFileSync(
    join(repo, "glimmer-design-pages.json"),
    JSON.stringify({ fileKey: "glimmer", outDir: "design/glimmer-pages" }),
  );
  writeFileSync(
    join(pages, "pages.json"),
    JSON.stringify({
      version: 2,
      source: "figma",
      fileKey: "glimmer",
      pages: [
        {
          id: "components",
          name: "Components",
          nodeId: "8:312",
          frame: { width: 100, height: 100 },
          image: { uri: "components.svg", format: "svg" },
          nodes: [],
        },
      ],
    }),
  );
  writeFileSync(join(pages, "components.svg"), '<svg xmlns="http://www.w3.org/2000/svg"/>');

  const result = spawnSync(
    process.execPath,
    [
      script,
      "--out",
      out,
      "--repo",
      repo,
      "--spec",
      "glimmer-catalog.spec.json",
      "--config",
      "glimmer-design-pages.json",
      "--strict",
    ],
    { encoding: "utf8" },
  );

  assert.equal(result.status, 0, result.stderr || result.stdout);
  const published = JSON.parse(readFileSync(join(out, "pages", "index.json"), "utf8"));
  assert.equal(published.fileKey, "glimmer");
  assert.deepEqual(published.pages.map((page) => page.id), ["components"]);
});

// ---------------------------------------------------------------------------
// Shared background plates: the byte checks only this side can do.
//
// Content addressing is worth nothing unless someone verifies it, and the emitter is the only place
// that sees both the record and the file. The consumer sees a manifest and a bundle that agree with
// each other by construction.
// ---------------------------------------------------------------------------

import { createHash } from "node:crypto";

/** A byte string that opens as a PNG, so the signature check passes on the real bytes. */
const png = (size = 64) => {
  const bytes = Buffer.alloc(size);
  Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]).copy(bytes);
  // Vary the tail so two different sizes are genuinely different bytes.
  bytes.write(String(size), 8);
  return bytes;
};

const sha256 = (bytes) => createHash("sha256").update(bytes).digest("hex");

/**
 * Run the emitter over a one-page import carrying one plate.
 *
 * `record` overrides the manifest's asset record; `file` overrides the bytes actually written, so a
 * test can make the two disagree — which is the whole point of the checks below.
 */
function emitWithPlate({ record = {}, file = png(), placementAsset = undefined } = {}) {
  const repo = mkdtempSync(join(tmpdir(), "emit-design-pages-plates-"));
  const out = join(repo, "out");
  const pages = join(repo, "design", "pages");
  mkdirSync(out, { recursive: true });
  mkdirSync(join(pages, "raw"), { recursive: true });

  const id = record.id ?? sha256(file);
  writeFileSync(join(out, "catalog.json"), JSON.stringify({ components: [] }));
  writeFileSync(join(repo, "design-pages.json"), JSON.stringify({ fileKey: "k" }));
  // The kit guard publishes only on a positive match between the import's fileKey and a
  // referenceKit this system reproduces; without it the emitter correctly skips the whole import.
  writeFileSync(
    join(repo, "catalog.spec.json"),
    JSON.stringify({ referenceKits: ["https://www.figma.com/design/k/Kit"] }),
  );
  writeFileSync(
    join(pages, "pages.json"),
    JSON.stringify({
      version: 2,
      source: "figma",
      fileKey: "k",
      assets: [
        { id, uri: "raw/plate.png", format: "png", width: 8, height: 8, bytes: file.length, ...record },
      ],
      pages: [
        {
          id: "buttons",
          name: "Buttons",
          nodeId: "1:2",
          frame: { width: 2048, height: 1024 },
          image: { uri: "buttons.svg", format: "svg" },
          nodes: [],
          designBlend: "screen",
          background: [
            { asset: placementAsset ?? id, x: 0, y: 0, width: 2048, height: 1024 },
          ],
        },
      ],
    }),
  );
  writeFileSync(join(pages, "buttons.svg"), '<svg xmlns="http://www.w3.org/2000/svg"/>');
  writeFileSync(join(pages, "raw", "plate.png"), file);

  const result = spawnSync(process.execPath, [script, "--out", out, "--repo", repo], {
    encoding: "utf8",
  });
  return {
    result,
    id,
    out,
    manifest: JSON.parse(readFileSync(join(out, "pages", "index.json"), "utf8")),
  };
}

test("a verified plate is copied under its content hash and kept in the manifest", () => {
  const { result, id, out, manifest } = emitWithPlate();
  assert.equal(result.status, 0, result.stderr || result.stdout);
  assert.deepEqual(manifest.assets.map((a) => a.id), [id]);
  assert.equal(manifest.assets[0].uri, `assets/${id}.png`);
  assert.equal(manifest.pages[0].designBlend, "screen");
  // The bytes are actually on disk, at the path the manifest advertises.
  assert.equal(sha256(readFileSync(join(out, "pages", "assets", `${id}.png`))), id);
});

// The id IS the content address. A mismatch means the table and the files have drifted, and
// republishing would hand the server an immutable URL that does not name its own bytes.
test("a plate whose bytes do not hash to its id is dropped", () => {
  const { manifest } = emitWithPlate({ record: { id: "c".repeat(64) } });
  assert.equal(manifest.assets, undefined);
  assert.equal(manifest.pages[0].background, undefined);
});

test("a mislabelled payload is dropped on its signature", () => {
  const notPng = Buffer.from("GIF89a" + "x".repeat(58));
  const { manifest } = emitWithPlate({ file: notPng, record: { id: sha256(notPng) } });
  assert.equal(manifest.assets, undefined);
});

test("a plate whose length disagrees with its record is dropped", () => {
  const { manifest } = emitWithPlate({ record: { bytes: 999 } });
  assert.equal(manifest.assets, undefined);
});

// A page must never advertise bytes the bundle does not carry.
test("dropping a plate also drops the placements naming it", () => {
  const { manifest } = emitWithPlate({ record: { bytes: 999 } });
  assert.equal(manifest.pages.length, 1);
  assert.equal(manifest.pages[0].background, undefined);
  assert.equal(manifest.pages[0].id, "buttons");
});

// An import with no plates publishes exactly the manifest it always did.
test("a catalog with no plates carries no assets key", () => {
  const repo = mkdtempSync(join(tmpdir(), "emit-design-pages-noplates-"));
  const out = join(repo, "out");
  const pages = join(repo, "design", "pages");
  mkdirSync(out, { recursive: true });
  mkdirSync(pages, { recursive: true });
  writeFileSync(join(out, "catalog.json"), JSON.stringify({ components: [] }));
  writeFileSync(join(repo, "design-pages.json"), JSON.stringify({ fileKey: "k" }));
  // The kit guard publishes only on a positive match between the import's fileKey and a
  // referenceKit this system reproduces; without it the emitter correctly skips the whole import.
  writeFileSync(
    join(repo, "catalog.spec.json"),
    JSON.stringify({ referenceKits: ["https://www.figma.com/design/k/Kit"] }),
  );
  writeFileSync(
    join(pages, "pages.json"),
    JSON.stringify({
      version: 2,
      source: "figma",
      fileKey: "k",
      pages: [
        {
          id: "shape",
          name: "Shape",
          nodeId: "1:2",
          frame: { width: 10, height: 10 },
          image: { uri: "shape.svg", format: "svg" },
          nodes: [],
        },
      ],
    }),
  );
  writeFileSync(join(pages, "shape.svg"), '<svg xmlns="http://www.w3.org/2000/svg"/>');

  const result = spawnSync(process.execPath, [script, "--out", out, "--repo", repo, "--strict"], {
    encoding: "utf8",
  });
  assert.equal(result.status, 0, result.stderr || result.stdout);
  const manifest = JSON.parse(readFileSync(join(out, "pages", "index.json"), "utf8"));
  assert.equal(manifest.assets, undefined);
  assert.equal(manifest.pages[0].background, undefined);
});
