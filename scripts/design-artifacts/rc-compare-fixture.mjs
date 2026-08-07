#!/usr/bin/env node
/**
 * rc-compare-fixture.mjs — emit a demo `rc-compare.html` from a synthetic model, so the parity
 * page can be **looked at** without a catalog bundle, a headless browser render, or the ~90-minute
 * catalog job that normally produces one.
 *
 * Why this exists: `rc-compare.html` is a visual surface, but the only way to see it used to be a
 * full `rc-compare.mjs` run against a staged `bundle.png` — which meant page-level changes shipped
 * with hand-waved descriptions instead of screenshots, and a regression like "the row shows a green
 * 0.00% for a preview that baked to nothing" is invisible in a unit test's HTML string. This writes
 * the real emitter's output over fixture rows and fixture images, so a before/after screenshot of
 * any page change is one command away.
 *
 * The fixture deliberately covers the cases that make the page's rules visible at a glance: a close
 * match, a badly diverging one (every player, scored independently), a document only some players
 * could decode, and a **blank baked reference** — the row that must read `no reference` rather than
 * a perfect score. It carries **every** player lane, so the page's default all-players wall and its
 * `Diff against` picker (which only lists lanes the run produced) are both exercised.
 *
 * Usage:
 *   node rc-compare-fixture.mjs --out <dir>      # writes <dir>/rc-compare.html + fixture images
 *
 * Open the emitted `rc-compare.html`, or screenshot it (the design-artifacts driver already ships
 * Playwright) to attach before/after evidence to a PR that touches the page.
 */
import fs from "node:fs";
import path from "node:path";
import { PNG } from "pngjs";

import { BG } from "./rc-compare-pixels.mjs";
import { renderRcCompareHtml } from "./render-rc-compare-html.mjs";

const args = process.argv.slice(2);
function arg(name, def) {
  const i = args.indexOf(`--${name}`);
  return i === -1 ? def : args[i + 1];
}
const OUT = arg("out");
if (!OUT) {
  console.error("usage: rc-compare-fixture.mjs --out <dir>");
  process.exit(2);
}

const LANES = [
  "rc",
  "rc-baked",
  "rc-diff",
  "rc-embedded",
  "rc-embedded-diff",
  "rc-embedded-jvm",
  "rc-embedded-jvm-diff",
  "rc-cmp-wasm",
  "rc-cmp-wasm-diff",
];
for (const d of LANES) fs.mkdirSync(path.join(OUT, d), { recursive: true });

// Small on purpose — the page scales cells to a fixed max width, so fixture images only need to be
// legible, not preview-sized.
const W = 220;
const H = 220;

function write(rel, fill) {
  const png = new PNG({ width: W, height: H });
  for (let y = 0; y < H; y++) {
    for (let x = 0; x < W; x++) {
      const i = (y * W + x) * 4;
      const [r, g, b, a] = fill(x, y);
      png.data[i] = r;
      png.data[i + 1] = g;
      png.data[i + 2] = b;
      png.data[i + 3] = a;
    }
  }
  fs.writeFileSync(path.join(OUT, rel), PNG.sync.write(png));
}

// Fixture images are written *already flattened onto the neutral*, matching what the driver stores:
// by the time a render reaches the page it has been composited over BG.
const bare = () => [...BG, 255];
const black = () => [0, 0, 0, 255];
/** A card on the neutral — stands in for a preview's content. */
const card = (hue) => (x, y) =>
  x > 24 && x < W - 24 && y > 70 && y < H - 70 ? [...hue, 255] : bare();
/** pixelmatch's output style: flagged pixels magenta on black. */
const flagged = (x, y) => (((x >> 3) + (y >> 3)) % 2 ? [255, 0, 255, 255] : [0, 0, 0, 255]);

const BLUE = [70, 110, 200];
const RED = [210, 90, 60];
const GREY = [90, 90, 90];

// close match in all lanes
write("rc-baked/Close.png", card(BLUE));
write("rc/Close.png", card(BLUE));
write("rc-diff/Close.png", black);
write("rc-embedded/Close.png", card(BLUE));
write("rc-embedded-diff/Close.png", black);
write("rc-embedded-jvm/Close.png", card(BLUE));
write("rc-embedded-jvm-diff/Close.png", black);
write("rc-cmp-wasm/Close.png", card(BLUE));
write("rc-cmp-wasm-diff/Close.png", black);
// far off in all lanes, worse in JS
write("rc-baked/Diverging.png", card(RED));
write("rc/Diverging.png", card(GREY));
write("rc-diff/Diverging.png", flagged);
write("rc-embedded/Diverging.png", card(GREY));
write("rc-embedded-diff/Diverging.png", flagged);
write("rc-embedded-jvm/Diverging.png", card(GREY));
write("rc-embedded-jvm-diff/Diverging.png", flagged);
write("rc-cmp-wasm/Diverging.png", card(GREY));
write("rc-cmp-wasm-diff/Diverging.png", flagged);
// only the embedded players decoded it
write("rc-baked/JsUndecodable.png", card(RED));
write("rc-embedded/JsUndecodable.png", card(RED));
write("rc-embedded-diff/JsUndecodable.png", black);
write("rc-embedded-jvm/JsUndecodable.png", card(RED));
write("rc-embedded-jvm-diff/JsUndecodable.png", black);
write("rc-cmp-wasm/JsUndecodable.png", card(RED));
write("rc-cmp-wasm-diff/JsUndecodable.png", black);
// blank baked reference: every lane is bare neutral, so every diff is empty — the false-perfect case
write("rc-baked/BlankReference.png", bare);
write("rc/BlankReference.png", bare);
write("rc-diff/BlankReference.png", black);
write("rc-embedded/BlankReference.png", bare);
write("rc-embedded-diff/BlankReference.png", black);
write("rc-embedded-jvm/BlankReference.png", bare);
write("rc-embedded-jvm-diff/BlankReference.png", black);
write("rc-cmp-wasm/BlankReference.png", bare);
write("rc-cmp-wasm-diff/BlankReference.png", black);

const lanes = (
  id,
  { js = true, embedded = true, embeddedJvm = true, cmpWasm = true } = {},
) => ({
  baked: `rc-baked/${id}.png`,
  rc: js ? `rc/${id}.png` : "",
  diff: js ? `rc-diff/${id}.png` : "",
  embedded: embedded ? `rc-embedded/${id}.png` : "",
  embeddedDiff: embedded ? `rc-embedded-diff/${id}.png` : "",
  embeddedJvm: embeddedJvm ? `rc-embedded-jvm/${id}.png` : "",
  embeddedJvmDiff: embeddedJvm ? `rc-embedded-jvm-diff/${id}.png` : "",
  cmpWasm: cmpWasm ? `rc-cmp-wasm/${id}.png` : "",
  cmpWasmDiff: cmpWasm ? `rc-cmp-wasm-diff/${id}.png` : "",
});

const rows = [
  {
    id: "Close",
    name: "TitleCardRemote",
    group: "Cards",
    width: 525,
    height: 525,
    rendered: true,
    mismatchPct: 0.42,
    mismatchPx: 1157,
    embeddedRendered: true,
    embeddedMismatchPct: 1.1,
    embeddedMismatchPx: 3031,
    embeddedJvmRendered: true,
    embeddedJvmMismatchPct: 0.9,
    embeddedJvmMismatchPx: 2480,
    cmpWasmRendered: true,
    cmpWasmMismatchPct: 1.4,
    cmpWasmMismatchPx: 3858,
    ...lanes("Close"),
  },
  {
    id: "Diverging",
    name: "ShaderGradientSticker",
    group: "Stickers",
    width: 525,
    height: 525,
    rendered: true,
    mismatchPct: 76.3,
    mismatchPx: 210301,
    embeddedRendered: true,
    embeddedMismatchPct: 41.5,
    embeddedMismatchPx: 114318,
    embeddedJvmRendered: true,
    embeddedJvmMismatchPct: 39.8,
    embeddedJvmMismatchPx: 109629,
    cmpWasmRendered: true,
    cmpWasmMismatchPct: 40.2,
    cmpWasmMismatchPx: 110731,
    ...lanes("Diverging"),
  },
  {
    id: "JsUndecodable",
    name: "WatchScreenRemote",
    group: "Wear",
    width: 525,
    height: 525,
    rendered: false,
    note: "player could not decode the document",
    mismatchPct: null,
    mismatchPx: null,
    embeddedRendered: true,
    embeddedMismatchPct: 8.0,
    embeddedMismatchPx: 22050,
    embeddedJvmRendered: true,
    embeddedJvmMismatchPct: 7.4,
    embeddedJvmMismatchPx: 20396,
    cmpWasmRendered: true,
    cmpWasmMismatchPct: 7.9,
    cmpWasmMismatchPx: 21774,
    ...lanes("JsUndecodable", { js: false }),
  },
  {
    id: "BlankReference",
    name: "BrandedTextRemote",
    group: "Text",
    width: 525,
    height: 525,
    rendered: true,
    // Both players rendered; neither is scored, because the reference is empty.
    referenceBlank: true,
    mismatchPct: null,
    mismatchPx: null,
    embeddedRendered: true,
    embeddedMismatchPct: null,
    embeddedMismatchPx: null,
    embeddedJvmRendered: true,
    embeddedJvmMismatchPct: null,
    embeddedJvmMismatchPx: null,
    cmpWasmRendered: true,
    cmpWasmMismatchPct: null,
    cmpWasmMismatchPx: null,
    ...lanes("BlankReference"),
  },
];

const html = renderRcCompareHtml(
  { system: "fixture", title: "rc-compare fixture", rows },
  { generatedNote: `${rows.length} fixture preview(s) · synthetic model, no browser render` },
);
fs.writeFileSync(path.join(OUT, "rc-compare.html"), html);
console.log(`rc-compare-fixture: wrote ${path.join(OUT, "rc-compare.html")} (${rows.length} rows)`);
