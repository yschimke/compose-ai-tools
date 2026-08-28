// Reproduces the #4544 evidence: a gutter-cropped card beside its plain, gutter-less sibling, at a
// desktop width and at a narrow one (< 640px, where `serve.css` drops the thumbnail cap 240 -> 200).
//
//   node docs/evidence/serve-crop-window-cap/harness.mjs
//
// It loads the SHIPPED `serve.css` and reproduces the exact markup `ServeWeb.thumbImg` emits, so
// the pictures track the stylesheet rather than a hand-drawn mock. `before` pins the pre-fix
// window — the frozen `width:<boxW>px` the server used to bake in; `after` is what the server
// emits now. Run from the repository root; the README says what to install.
import { mkdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

// This repository's Playwright lives in ONE place — `preview-server/preview-harness` declares
// `@playwright/test`, and npm installs `playwright` beside it. An earlier version of this file
// imported it from `cli/serve-web/node_modules`, where it has never been declared, so `npm ci` in
// that workspace produced a checkout where the documented command died with ERR_MODULE_NOT_FOUND
// before the harness ran. Resolved by path rather than by bare specifier because ESM resolves a
// bare specifier relative to THIS file, which is under `docs/`.
const PLAYWRIGHT_HOME = "preview-server/preview-harness";
const { chromium } = await importPlaywright();

async function importPlaywright() {
  const candidates = [
    new URL(`../../../${PLAYWRIGHT_HOME}/node_modules/playwright/index.mjs`, import.meta.url).href,
    // A global or hoisted install, for a checkout that happens to have one.
    "playwright",
  ];
  for (const specifier of candidates) {
    try {
      return await import(specifier);
    } catch (e) {
      // Only "it isn't there" moves on; a playwright that fails to LOAD is a real error and
      // reporting it as "not installed" would send the reader to reinstall something they have.
      if (e?.code !== "ERR_MODULE_NOT_FOUND") throw e;
    }
  }
  throw new Error(
    `playwright is not installed. From the repository root:\n` +
      `  npm --prefix ${PLAYWRIGHT_HOME} ci\n` +
      `  npx --prefix ${PLAYWRIGHT_HOME} playwright install chromium\n` +
      `(or set CHROME_PATH to a Chromium you already have).`,
  );
}

const here = dirname(fileURLToPath(import.meta.url));
const repo = join(here, "..", "..", "..");
const css = readFileSync(
  join(repo, "cli/serve/src/main/resources/ee/schimke/composeai/cli/serve/assets/serve.css"),
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

// A SYSTEM CARD's hero, which is a different well: `.cp-syslist .cp-imgwrap` is a fixed 220px row
// at every width, and the plain hero beside it is exempted from the grid's image cap for exactly
// that reason. A clip window takes its cap through `--cp-thumb-cap`, so it needs the same
// exemption spelled the other way — `before` pins the value it used to inherit from the grid.
const heroSpan = (mode, cap) => {
  const inner =
    `<img alt="" src="${GUTTERED}" style="width:${pct(IMG_W, BOX_W)}%;` +
    `left:${pct(LEFT, BOX_W)}%;top:${pct(TOP, BOX_H)}%">`;
  const pinned = mode === "before" ? `--cp-thumb-cap:${cap}px;` : "";
  return (
    `<span class="cp-crop cp-crop--bleed" style="${pinned}` +
    `--cp-crop-w-per-cap:${pct(400, 300 * 100)};--cp-crop-max-w:400px;` +
    `aspect-ratio:${BOX_W}/${BOX_H}">${inner}</span>`
  );
};

const heroCard = (inner) =>
  `<div class="cp-syslist"><div class="cp-card cp-sys">` +
  `<div class="cp-imgwrap">${inner}</div></div></div>`;

const page = (mode, cap) => `<!doctype html><meta charset="utf-8">
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
  <div class="box">${heroCard(`<img alt="" src="${GUTTERED}">`)}
    <div class="cap">system-card hero, prebaked &mdash; the WHOLE guttered canvas, which is what
      <code>ServeHeroImages.bake</code> keeps for a gutter crop (<code>crop?.takeIf { it.clip }</code>),
      fitted to the row's 196px of content height</div></div>
  <div class="box">${heroCard(heroSpan(mode, cap))}
    <div class="cap">system-card hero, the cropped fallback &mdash; same row, same well</div></div>
</div>`;

mkdirSync(here, { recursive: true });
// `CHROME_PATH` lets a sandbox with a preinstalled Chromium skip playwright's own download.
const browser = await chromium.launch(
  process.env.CHROME_PATH ? { executablePath: process.env.CHROME_PATH } : {},
);
for (const mode of ["before", "after"]) {
  const file = join(here, `.${mode}.html`);
  for (const [name, width, heroCap] of [["narrow", 560, 200], ["desktop", 900, 240]]) {
    writeFileSync(file, page(mode, heroCap));
    const p = await browser.newPage({ viewport: { width, height: 620 }, deviceScaleFactor: 2 });
    await p.goto(`file://${file}`);
    await p.evaluate(() => Promise.all([...document.images].map((i) => i.decode().catch(() => {}))));
    const size = (sel) => p.evaluate((s) => {
      const r = document.querySelector(s).getBoundingClientRect();
      return `${Math.round(r.width)}x${Math.round(r.height)}`;
    }, sel);
    console.log(
      mode, name,
      "plain", await size(".cp-imgwrap img"),
      "window", await size(".cp-crop"),
      "hero", await size(".cp-syslist .cp-imgwrap > img"),
      "hero-window", await size(".cp-syslist .cp-crop"),
    );
    await p.screenshot({ path: join(here, `${name}-${mode}.png`), fullPage: true });
    await p.close();
  }
  rmSync(file);
}
await browser.close();
