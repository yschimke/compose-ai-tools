// Before/after for the D3 score rebaseline, on real committed pairs.
//
// The change is a kernel swap, so what it moves is a number — but the same kernel now builds the
// normalised panels the magenta delta map is drawn from (`boxCanvas`), so it moves pixels too, and
// this is what those pixels look like. Each row is one pair from `renders/lane-parity/`: the two
// sides normalised into one box, the delta map between them, and the score the page would print.
//
//   node shoot.mjs after.png
//   node shoot.mjs before.png --before
//
// `--before` loads `format-compare.js` as it stands on `origin/main` (written to `before-asset.js`
// by the caller) rather than the built one, so both frames come from the real published assets and
// nothing here reimplements the scorer.
import { chromium } from "playwright";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const repo = path.resolve(here, "../../../..");
const [, , out = "after.png", flag] = process.argv;
const before = flag === "--before";
const asset = before
    ? path.join(here, "before-asset.js")
    : path.join(
          repo,
          "cli/src/main/resources/ee/schimke/composeai/cli/serve/assets/format-compare.js",
      );

const PAIRS = [
    ["wear-m3-card-snapshot-vs-live-after.png", "wear-m3-card-snapshot-vs-live.png"],
    ["wear-m3-edgebutton-figma-svg-scrollaway.png", "wear-m3-edgebutton-figma-svg-scrolled.png"],
    ["compose-m3-button-snapshot-vs-wasm-after.png", "compose-m3-button-snapshot-vs-wasm.png"],
];
const lane = path.join(repo, "renders/lane-parity");
const uri = (f) => `data:image/png;base64,${fs.readFileSync(f).toString("base64")}`;

const browser = await chromium.launch({
    executablePath: process.env.CHROMIUM_PATH || undefined,
    args: ["--no-sandbox"],
});
const page = await browser.newPage({ viewport: { width: 900, height: 700 }, deviceScaleFactor: 2 });
await page.setContent(`<!doctype html><meta charset="utf-8"><title>rebaseline</title>
<style>
 body { font: 13px/1.4 system-ui, sans-serif; margin: 16px; background: #fff; color: #111; }
 .row { display: flex; gap: 12px; align-items: flex-start; margin-bottom: 14px; }
 figure { margin: 0; }
 figcaption { font-size: 11px; color: #666; margin-top: 3px; }
 canvas { border: 1px solid #ddd; image-rendering: pixelated; }
 .score { font-weight: 600; }
</style><div id="out"></div>`);
await page.addScriptTag({ content: fs.readFileSync(asset, "utf8") });

await page.evaluate(
    async ([rows, label]) => {
        const api = window.ComposePreviewCompare;
        const out = document.getElementById("out");
        const heading = document.createElement("h3");
        heading.textContent = `${label} · SCORE_VERSION ${api.SCORE_VERSION ?? "(unversioned)"}`;
        out.append(heading);
        for (const [name, a, b] of rows) {
            const frames = await api.normaliseImageUrls(a, b, 220);
            const images = await Promise.all([api.loadImage(a), api.loadImage(b)]);
            const score = await api.scoreImages(images[0], images[1]);
            const diff = document.createElement("canvas");
            api.diffCanvases(frames.reference, frames.candidate, diff);
            const row = document.createElement("div");
            row.className = "row";
            for (const [canvas, caption] of [
                [frames.reference, "reference"],
                [frames.candidate, "candidate"],
                [diff, "delta"],
            ]) {
                const figure = document.createElement("figure");
                figure.append(canvas);
                const cap = document.createElement("figcaption");
                cap.textContent = caption;
                figure.append(cap);
                row.append(figure);
            }
            const text = document.createElement("div");
            text.innerHTML = `<div>${name}</div><div class="score">${score.percent.toFixed(2)}%</div>`;
            row.append(text);
            out.append(row);
        }
    },
    [
        PAIRS.map(([a, b]) => [a.replace(/\.png$/, ""), uri(path.join(lane, a)), uri(path.join(lane, b))]),
        before ? "before — drawImage kernel" : "after — portable area average",
    ],
);
await page.screenshot({ path: path.join(here, out), fullPage: true });
await browser.close();
console.log(`wrote ${out}`);
