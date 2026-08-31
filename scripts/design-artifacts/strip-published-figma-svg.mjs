#!/usr/bin/env node
import { readFile, readdir, writeFile } from "node:fs/promises";
import { join, relative, resolve } from "node:path";
import { parseArgs } from "node:util";

import { stripFigmaSvgSidecars } from "./bundle-strip-figma-svg.mjs";

const { values } = parseArgs({ options: { root: { type: "string" } } });
if (!values.root) {
  console.error("usage: strip-published-figma-svg --root <published bundle directory>");
  process.exit(2);
}

const root = resolve(values.root);

async function bundleFiles(dir) {
  const out = [];
  for (const entry of await readdir(dir, { withFileTypes: true })) {
    const path = join(dir, entry.name);
    if (entry.isDirectory()) out.push(...(await bundleFiles(path)));
    else if (entry.isFile() && entry.name.endsWith(".png")) out.push(path);
  }
  return out;
}

let bundles = 0;
let removedEntries = 0;
let removedBytes = 0;
for (const path of await bundleFiles(root)) {
  const stripped = stripFigmaSvgSidecars(await readFile(path));
  if (stripped.removedEntries === 0) continue;
  await writeFile(path, stripped.bytes);
  bundles += 1;
  removedEntries += stripped.removedEntries;
  removedBytes += stripped.removedBytes;
  // Name the monolith/module bundles, not each of a catalog's thousands of one-preview children.
  if (stripped.removedEntries > 1) {
    console.log(
      `deferred ${stripped.removedEntries} figma-svg sidecar(s) from ${relative(root, path)}`,
    );
  }
}

console.log(
  `deferred ${removedEntries} figma-svg sidecar(s) (${removedBytes} uncompressed B) from ` +
    `${bundles} published bundle(s); the live daemon regenerates them on request`,
);
