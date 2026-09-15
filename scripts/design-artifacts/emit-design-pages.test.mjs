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
