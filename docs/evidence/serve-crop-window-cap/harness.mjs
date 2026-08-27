// Reproduces the #4544 evidence: a gutter-cropped card beside its plain, gutter-less sibling, at a
// desktop width and at a narrow one (< 640px, where `serve.css` drops the thumbnail cap 240 -> 200).
//
//   node docs/evidence/serve-crop-window-cap/harness.mjs
//
// It loads the SHIPPED `serve.css` and reproduces the exact markup `ServeWeb.thumbImg` emits, so
// the pictures track the stylesheet rather than a hand-drawn mock. `before` pins the pre-fix
// window — the frozen `width:<boxW>px` the server used to bake in; `after` is what the server
// emits now. Run from the repo root, with playwright resolvable (`cli/serve-web/node_modules`).
// Resolved by path: playwright is a devDependency of the `cli/serve-web` workspace, and ESM
// resolves bare specifiers relative to THIS file, not the cwd.
import { chromium } from "../../../cli/serve-web/node_modules/playwright/index.mjs";
import { mkdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const repo = join(here, "..", "..", "..");
const css = readFileSync(
  join(repo, "cli/src/main/resources/ee/schimke/composeai/cli/serve/assets/serve.css"),
  "utf8",
);

/** A stand-in render: a `w`x`h` component inset by a `pad` capture gutter on every edge. */
const render = (w, h, pad = 0) => {
  const [tw, th] = [w + 2 * pad, h + 2 * pad];
  const svg =
    `<svg xmlns="http://www.w3.org/2000/svg" width="${tw}" height="${th}" viewBox="0 0 ${tw} ${th}">` +
    `<rect x="${pad}" y="${pad}" width="${w}" height="${h}" rx="24" fill="#6750A4"/>` +
    `<text x="${pad + w / 2}" y="${pad + h / 2 + 10}" font-family="sans-serif" font-size="30" ` +
    `fill="#fff" text-anchor="middle">${w}x${h}</text></svg>`;
  return "data:image/svg+xml;utf8," + encodeURIComponent(svg);
};

const PLAIN = render(400, 300);
const GUTTERED = render(400, 300, 20);
// computeGutterCrop(20, 20, 20, 20, 440, 340): box 400x300, capped on HEIGHT 240/300 -> scale 0.8.
const [BOX_W, BOX_H, IMG_W, IMG_H, LEFT, TOP] = [320, 240, 352, 272, -16, -16];
const pct = (n, d) => Number((n * 100) / d).toFixed(4).replace(/\.?0+$/, "");

const windowSpan = (mode) => {
  const inner =
    `<img alt="" src="${GUTTERED}" style="width:${pct(IMG_W, BOX_W)}%;` +
    `left:${pct(LEFT, BOX_W)}%;top:${pct(TOP, BOX_H)}%">`;
  const sizing =
    mode === "before"
      ? `width:${BOX_W}px`
      : `--cp-crop-w-per-cap:${pct(400, 300 * 100)};--cp-crop-max-w:400px`;
  return `<span class="cp-crop cp-crop--bleed" style="${sizing};aspect-ratio:${BOX_W}/${BOX_H}">${inner}</span>`;
};

const page = (mode) => `<!doctype html><meta charset="utf-8">
<style>${css}
body { margin: 0; background: #fff; font-family: sans-serif; color: #222; }
.harness { display: block; padding: 12px; }
.box { display: block; width: 100%; max-width: 480px; margin: 0 auto 12px; border: 1px solid #ccc;
       border-radius: 12px; overflow: hidden; }
.cap { font-size: 12px; text-align: center; padding: 6px 4px; color: #444; }
</style>
<div class="harness">
  <div class="box"><div class="cp-imgwrap"><img alt="" src="${PLAIN}"></div>
    <div class="cap">plain sibling &mdash; a gutter-less 400x300 capture</div></div>
  <div class="box"><div class="cp-imgwrap">${windowSpan(mode)}</div>
    <div class="cap">same component, captured with a 20px gutter, shown through the clip window</div></div>
</div>`;

mkdirSync(here, { recursive: true });
// `CHROME_PATH` lets a sandbox with a preinstalled Chromium skip playwright's own download.
const browser = await chromium.launch(
  process.env.CHROME_PATH ? { executablePath: process.env.CHROME_PATH } : {},
);
for (const mode of ["before", "after"]) {
  const file = join(here, `.${mode}.html`);
  writeFileSync(file, page(mode));
  for (const [name, width] of [["narrow", 560], ["desktop", 900]]) {
    const p = await browser.newPage({ viewport: { width, height: 620 }, deviceScaleFactor: 2 });
    await p.goto(`file://${file}`);
    await p.evaluate(() => Promise.all([...document.images].map((i) => i.decode().catch(() => {}))));
    const size = (sel) => p.evaluate((s) => {
      const r = document.querySelector(s).getBoundingClientRect();
      return `${Math.round(r.width)}x${Math.round(r.height)}`;
    }, sel);
    console.log(mode, name, "plain", await size(".cp-imgwrap img"), "window", await size(".cp-crop"));
    await p.screenshot({ path: join(here, `${name}-${mode}.png`) });
    await p.close();
  }
  rmSync(file);
}
await browser.close();
