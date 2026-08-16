#!/usr/bin/env node
/**
 * Score the view lane against the embedded lane, document by document.
 *
 * Lives here rather than beside `scripts/rc-lane-ab/render-ab.sh`, which drives it: Node resolves
 * a bare import from the *importing file's* directory, so a scorer in `scripts/rc-lane-ab` could
 * not see `pixelmatch` and `pngjs` no matter which directory it was run from. This package is
 * where the repo's other rc-compare tooling already declares both, and one declaration of a
 * dependency is better than two that can drift.
 *
 * **Why pixelmatch and not a pixel-inequality count.** The two lanes rasterize text with different
 * stacks, so a large share of pixels differ slightly in every document that contains a glyph.
 * Counting pixels that differ *at all* buries real defects in that noise, and it is worse than
 * merely noisy — it moves the wrong way. After the ColorAttribute fix (#3977) the dark grid's
 * differing-pixel count went *up*, 41463 to 41719, while its mean channel delta fell by a third:
 * the badge had gone from absent to present-but-one-LSB-off, and both states "differ". pixelmatch's
 * anti-aliasing detection is what separates those.
 *
 * Usage: node rc-lane-ab-score.mjs <view dir> <embedded dir> [--json <out>]
 */
import fs from "node:fs";
import path from "node:path";
import { PNG } from "pngjs";
import pixelmatch from "pixelmatch";

const [viewDir, embeddedDir] = process.argv.slice(2);
if (!viewDir || !embeddedDir) {
  console.error("usage: node rc-lane-ab-score.mjs <view dir> <embedded dir> [--json <out>]");
  process.exit(2);
}
const jsonAt = process.argv.indexOf("--json");
const jsonOut = jsonAt >= 0 ? process.argv[jsonAt + 1] : null;

// Matches rc-compare.mjs. `includeAA` is left at its default (false), which is the entire reason
// this uses pixelmatch — anti-aliased pixels are detected and excluded from the count.
const THRESHOLD = 0.1;

const rows = [];
const unrendered = [];
let clean = 0;

for (const name of fs.readdirSync(viewDir).filter((n) => n.endsWith(".png")).sort()) {
  const embeddedPath = path.join(embeddedDir, name);
  if (!fs.existsSync(embeddedPath)) {
    unrendered.push(name.replace(/\.png$/, ""));
    continue;
  }
  const a = PNG.sync.read(fs.readFileSync(path.join(viewDir, name)));
  const b = PNG.sync.read(fs.readFileSync(embeddedPath));
  const id = name.replace(/\.png$/, "");
  if (a.width !== b.width || a.height !== b.height) {
    rows.push({ id, fraction: 1, differing: -1, total: -1, note: "size mismatch" });
    continue;
  }
  const total = a.width * a.height;
  const differing = pixelmatch(a.data, b.data, null, a.width, a.height, { threshold: THRESHOLD });
  if (differing === 0) clean++;
  else rows.push({ id, fraction: differing / total, differing, total });
}

rows.sort((p, q) => q.fraction - p.fraction);
const band = (lo, hi) => rows.filter((r) => r.fraction >= lo && r.fraction < hi).length;

const summary = {
  documents: rows.length + clean + unrendered.length,
  clean,
  unrendered,
  bands: {
    "under 1%": band(0, 0.01),
    "1-5%": band(0.01, 0.05),
    "over 5%": band(0.05, Infinity),
  },
  rows,
};

console.log(`documents: ${summary.documents}`);
console.log(`  no differing pixels: ${clean}`);
console.log(`  differing, under 1%: ${summary.bands["under 1%"]}`);
console.log(`  differing, 1-5%:     ${summary.bands["1-5%"]}`);
console.log(`  differing, over 5%:  ${summary.bands["over 5%"]}`);
console.log(`  unrendered on the embedded lane: ${unrendered.length}`);
for (const id of unrendered) console.log(`      ${id}`);
console.log("--- top 20 by fraction ---");
for (const r of rows.slice(0, 20)) {
  const pct = (r.fraction * 100).toFixed(2).padStart(6);
  console.log(`  ${pct}%  ${String(r.differing).padStart(7)}px  ${r.id}`);
}

if (jsonOut) fs.writeFileSync(jsonOut, JSON.stringify(summary, null, 2));
