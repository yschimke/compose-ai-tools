#!/usr/bin/env node
/**
 * rc-compare.mjs — build the PNG↔Remote-Compose parity page for a catalog.
 *
 * For every preview a catalog bundle carries as both a baked raster
 * (`previews/<id>.png`) and a Remote Compose document (`ir/<id>.rc`), this
 * renders the `.rc` client-side with the vendored TypeScript player
 * (`RC.RcdPlayer`) in headless Chromium — the exact code path the browser
 * render lane (`compose-preview serve`, viewer `rc` mode) uses — sizes the
 * canvas to the baked PNG, pixel-diffs the two (`pixelmatch`), and emits:
 *
 *   <out>/rc/<id>.png            client-side render
 *   <out>/rc-baked/<id>.png      baked PNG (copied so the page is self-contained)
 *   <out>/rc-diff/<id>.png       pixel diff
 *   <out>/rc-compare.html        the gallery (render-rc-compare-html.mjs)
 *   <out>/rc-compare-summary.json machine-readable per-preview results
 *
 * A catalog that ships no `ir/*.rc` (most non-Remote-Compose systems) is a
 * clean no-op: nothing is written and the tool exits 0, so it is safe to run
 * unconditionally in the shared reusable workflow.
 *
 * Usage:
 *   node rc-compare.mjs --bundle <bundle.png> --player <rc-player bundle.js> \
 *     --out <dir> [--system <id>] [--title <t>] [--threshold 0.1] [--theme light] \
 *     [--fonts <dir>]
 *
 * `--fonts` defaults to the vendored faces the snapshot renderer itself rasterizes with (see
 * rc-fonts.mjs). Point it elsewhere to compare against a different font set, or at a
 * non-existent path to fall back to the host's generic families — which renders every string in a
 * substituted typeface and inflates the mismatch for anything containing text.
 *
 * The polyglot `bundle.png` is a PNG with a ZIP appended; we read the ZIP's
 * `ir/*.rc` + `previews/*.png` entries directly (no external unzip).
 */
import fs from "node:fs";
import path from "node:path";
import zlib from "node:zlib";
import { PNG } from "pngjs";
import pixelmatch from "pixelmatch";
import { chromium } from "playwright";

import { renderRcCompareHtml } from "./render-rc-compare-html.mjs";
import { DEFAULT_FONTS_DIR, fontFaceCss, loadAndVerifyFonts } from "./rc-fonts.mjs";

function arg(name, def = undefined) {
  const i = process.argv.indexOf(`--${name}`);
  return i >= 0 && i + 1 < process.argv.length ? process.argv[i + 1] : def;
}

const BUNDLE = arg("bundle");
const PLAYER = arg("player");
const OUT = arg("out");
const SYSTEM = arg("system", "");
const TITLE = arg("title", SYSTEM);
const THRESHOLD = Number(arg("threshold", "0.1"));
const THEME = arg("theme", "light");
const EXEC = arg("chromium", process.env.RC_COMPARE_CHROMIUM || undefined);
const FONTS = arg("fonts", DEFAULT_FONTS_DIR);

if (!BUNDLE || !PLAYER || !OUT) {
  console.error("rc-compare: --bundle, --player and --out are required");
  process.exit(2);
}

// ---- minimal ZIP reader over the polyglot bundle (central directory walk) ----
// Entries are STORE (0) or DEFLATE (8); RC docs/PNGs are small, so we read the
// whole file into memory and slice per entry.
function readZipEntries(buf) {
  // Find End Of Central Directory record (0x06054b50), scanning from the tail.
  let eocd = -1;
  for (let i = buf.length - 22; i >= 0 && i > buf.length - 22 - 0x10000; i--) {
    if (buf.readUInt32LE(i) === 0x06054b50) { eocd = i; break; }
  }
  if (eocd < 0) throw new Error("no ZIP end-of-central-directory found");
  const count = buf.readUInt16LE(eocd + 10);
  const cdSize = buf.readUInt32LE(eocd + 12);
  const cdOffset = buf.readUInt32LE(eocd + 16);
  // Polyglot bundle: a PNG is prepended, so the archive's stored offsets are
  // relative to the start of the *ZIP*, not the file. Recover the prepend the
  // way python's zipfile does and add it to every stored offset.
  const prepend = eocd - cdSize - cdOffset;
  let off = cdOffset + prepend;
  const entries = new Map();
  for (let n = 0; n < count; n++) {
    if (buf.readUInt32LE(off) !== 0x02014b50) break; // central file header
    const method = buf.readUInt16LE(off + 10);
    const compSize = buf.readUInt32LE(off + 20);
    const nameLen = buf.readUInt16LE(off + 28);
    const extraLen = buf.readUInt16LE(off + 30);
    const commentLen = buf.readUInt16LE(off + 32);
    const localOff = buf.readUInt32LE(off + 42) + prepend;
    const name = buf.toString("utf8", off + 46, off + 46 + nameLen);
    // Local header: recompute payload start (its name/extra lengths can differ).
    const lNameLen = buf.readUInt16LE(localOff + 26);
    const lExtraLen = buf.readUInt16LE(localOff + 28);
    const dataStart = localOff + 30 + lNameLen + lExtraLen;
    const comp = buf.subarray(dataStart, dataStart + compSize);
    entries.set(name, () => (method === 8 ? zlib.inflateRawSync(comp) : Buffer.from(comp)));
    off += 46 + nameLen + extraLen + commentLen;
  }
  return entries;
}

function baseName(name, prefix, suffix) {
  return name.slice(prefix.length, name.length - suffix.length);
}

// Flatten an RGBA image onto an opaque neutral so the diff is meaningful for both
// light and dark content. The catalog PNGs are stickers on a *transparent*
// background; pixelmatch composites transparent pixels over white, so light
// content on transparent (a white icon, pale text) would read as identical to a
// blank canvas — a false 0% match. Compositing both sides over the same mid-grey
// makes light *and* dark content contrast, so a blank render always diffs.
const BG = [128, 128, 128];
function flattenOnto(png, [br, bg, bb]) {
  const d = png.data;
  for (let i = 0; i < d.length; i += 4) {
    const a = d[i + 3] / 255;
    d[i] = Math.round(d[i] * a + br * (1 - a));
    d[i + 1] = Math.round(d[i + 1] * a + bg * (1 - a));
    d[i + 2] = Math.round(d[i + 2] * a + bb * (1 - a));
    d[i + 3] = 255;
  }
  return png;
}

const bundleBuf = fs.readFileSync(BUNDLE);
const entries = readZipEntries(bundleBuf);

const rcIds = [];
for (const name of entries.keys()) {
  if (name.startsWith("ir/") && name.endsWith(".rc")) rcIds.push(baseName(name, "ir/", ".rc"));
}
rcIds.sort();

if (rcIds.length === 0) {
  console.log(`rc-compare: ${BUNDLE} ships no ir/*.rc documents — nothing to compare, skipping.`);
  process.exit(0);
}

const dirs = {
  rc: path.join(OUT, "rc"),
  baked: path.join(OUT, "rc-baked"),
  diff: path.join(OUT, "rc-diff"),
};
for (const d of Object.values(dirs)) fs.mkdirSync(d, { recursive: true });

const bundleJs = fs.readFileSync(PLAYER, "utf8");

const browser = await chromium.launch({
  headless: true,
  ...(EXEC ? { executablePath: EXEC } : {}),
  args: ["--enable-unsafe-swiftshader", "--no-sandbox"],
});
const page = await browser.newContext({ deviceScaleFactor: 1 }).then((c) => c.newPage());
const pageWarnings = [];
page.on("console", (m) => {
  if (m.type() === "warning" || m.type() === "error") pageWarnings.push(m.text());
});
const fontCss = fontFaceCss(FONTS);
await page.setContent(`<!doctype html><html><head>${fontCss}</head><body></body></html>`);
if (fontCss) await loadAndVerifyFonts(page);
await page.addScriptTag({ content: bundleJs });

const rows = [];
for (const id of rcIds) {
  const pngName = `previews/${id}.png`;
  if (!entries.has(pngName)) {
    console.log(`rc-compare: no baked PNG for ${id}, skipping`);
    continue;
  }
  const baked = flattenOnto(PNG.sync.read(entries.get(pngName)()), BG);
  const rcB64 = entries.get(`ir/${id}.rc`)().toString("base64");
  const { width, height } = baked;

  pageWarnings.length = 0;
  const result = await page.evaluate(
    async ({ b64, w, h, theme }) => {
      const canvas = document.createElement("canvas");
      canvas.width = w;
      canvas.height = h;
      document.body.appendChild(canvas);
      const bin = atob(b64);
      const bytes = new Uint8Array(bin.length);
      for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
      try {
        const player = new window.RcdPlayer(canvas);
        player.setTheme(theme);
        await player.loadFromArrayBuffer(bytes.buffer);
        await new Promise((r) => setTimeout(r, 250));
        // The first paint is what *discovers* which named font families the document asks for —
        // resolution happens mid-paint, per TYPEFACE op — so the wait has to come after it. A
        // single-shot render has no later frame in which a face could appear, so without this the
        // branded text would screenshot in the fallback typeface.
        player.repaint();
        await player.fontsReady();
        player.repaint();
        return { dataUrl: canvas.toDataURL("image/png") };
      } catch (e) {
        return { error: String((e && e.stack) || e) };
      } finally {
        canvas.remove();
      }
    },
    { b64: rcB64, w: width, h: height, theme: THEME },
  );

  const name = id.split(".").pop();
  const truncated = pageWarnings.some((t) => /Unknown operation opcode/.test(t));

  if (result.error || truncated) {
    rows.push({
      id,
      name,
      group: "",
      width,
      height,
      rendered: false,
      note: truncated ? "player could not decode the document" : "render error",
      mismatchPct: null,
      mismatchPx: null,
      baked: `rc-baked/${id}.png`,
      rc: "",
      diff: "",
    });
    fs.writeFileSync(path.join(dirs.baked, `${id}.png`), PNG.sync.write(baked));
    console.log(`  ${name}: NOT RENDERED (${rows[rows.length - 1].note})`);
    continue;
  }

  const rcPng = flattenOnto(PNG.sync.read(Buffer.from(result.dataUrl.split(",")[1], "base64")), BG);
  const diff = new PNG({ width, height });
  const mismatchPx = pixelmatch(baked.data, rcPng.data, diff.data, width, height, {
    threshold: THRESHOLD,
  });
  const mismatchPct = (100 * mismatchPx) / (width * height);

  fs.writeFileSync(path.join(dirs.baked, `${id}.png`), PNG.sync.write(baked));
  fs.writeFileSync(path.join(dirs.rc, `${id}.png`), PNG.sync.write(rcPng));
  fs.writeFileSync(path.join(dirs.diff, `${id}.png`), PNG.sync.write(diff));

  rows.push({
    id,
    name,
    group: "",
    width,
    height,
    rendered: true,
    mismatchPct,
    mismatchPx,
    baked: `rc-baked/${id}.png`,
    rc: `rc/${id}.png`,
    diff: `rc-diff/${id}.png`,
  });
  console.log(`  ${name}: ${mismatchPct.toFixed(2)}% (${mismatchPx} px, ${width}×${height})`);
}

await browser.close();

const model = { system: SYSTEM, title: TITLE, rows };
const html = renderRcCompareHtml(model, {
  generatedNote: `${rows.length} Remote Compose preview(s) · pixelmatch threshold ${THRESHOLD} · theme ${THEME}`,
});
fs.writeFileSync(path.join(OUT, "rc-compare.html"), html);

const rendered = rows.filter((r) => r.rendered);
const meanPct = rendered.length ? rendered.reduce((s, r) => s + r.mismatchPct, 0) / rendered.length : null;
fs.writeFileSync(
  path.join(OUT, "rc-compare-summary.json"),
  JSON.stringify(
    {
      system: SYSTEM,
      total: rows.length,
      rendered: rendered.length,
      unsupported: rows.length - rendered.length,
      meanMismatchPct: meanPct,
      threshold: THRESHOLD,
      theme: THEME,
      rows: rows.map((r) => ({
        id: r.id,
        rendered: r.rendered,
        mismatchPct: r.mismatchPct,
        mismatchPx: r.mismatchPx,
        width: r.width,
        height: r.height,
        note: r.note ?? null,
      })),
    },
    null,
    2,
  ),
);

// Link the page from the catalog gallery. The index is generated earlier in the
// pipeline (before this step runs), so splice the nav link in next to the
// existing "PNG vs SVG compare" one rather than threading a flag through it.
const indexPath = path.join(OUT, "index.html");
if (fs.existsSync(indexPath)) {
  let index = fs.readFileSync(indexPath, "utf8");
  const anchor = '<a class="pagelink" href="compare.html">PNG vs SVG compare ↗</a>';
  const rcLink = ' <a class="pagelink" href="rc-compare.html">PNG vs Remote Compose ↗</a>';
  if (index.includes(anchor) && !index.includes('href="rc-compare.html"')) {
    index = index.replace(anchor, anchor + rcLink);
    fs.writeFileSync(indexPath, index);
    console.log("rc-compare: linked rc-compare.html from index.html");
  }
}

console.log(
  `rc-compare: wrote ${OUT}/rc-compare.html — ${rendered.length}/${rows.length} rendered` +
    (meanPct == null ? "" : `, mean mismatch ${meanPct.toFixed(2)}%`),
);
