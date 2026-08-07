/**
 * rc-size-modifier.test.mjs — the player honours the size a component was *asked* for.
 *
 * Run with `node --test scripts/design-artifacts/`.
 *
 * Two measure-pass defects made a component render at a size nobody requested, and both were
 * invisible to every other check: the document parses, no opcode is unknown, nothing warns. Only
 * the pixels tell you — which is why this exercises the built bundle
 * (`cli/src/main/resources/rc-player/bundle.js`) in a real browser, the same reason
 * `rc-round-clip.test.mjs` does. A stale bundle beside fixed source is exactly the regression worth
 * catching.
 *
 * 1. **A widget's own default size clobbered the caller's.** A size modifier chain is emitted
 *    outermost-first, and `RemoteIcon(modifier = size(48.dp))` writes the caller's 48 followed by
 *    the widget's built-in 24 dp default. Compose resolves `size(48).size(24)` to 48 — the outer
 *    call fixes the constraints and the inner is coerced into them — but the player kept the *last*
 *    fixed modifier, so every icon in every document rendered at 24 dp regardless of what was
 *    asked for.
 *
 * 2. **A container forwarded its own minimum to its children.** A weighted row cell is measured
 *    with min == max so it fills its slot; passing that minimum down made every child at least as
 *    large as the cell, so a `size(20)` icon and a wrapped label both came out full-cell and
 *    overlapped their neighbours.
 *
 * The fixture is the catalog's own `IconRemote` sticker — `RemoteIcon(starIcon, size(iconSize))` on
 * a 200×200 dp canvas — paired with the AndroidX-baked PNG of the same preview from the same bundle
 * pack. Comparing against that reference rather than a hand-computed box keeps the expectation
 * honest: it asserts the player agrees with the renderer everyone else's output is measured
 * against, instead of re-stating whatever the player currently happens to do. A whole-canvas diff
 * is safe to assert *here* specifically because this sticker draws one tinted glyph and no text, so
 * there are no font metrics to drift. The regression scored 5.8% against this reference; the player
 * now matches it exactly.
 */
import { test, before, after } from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { PNG } from "pngjs";
import pixelmatch from "pixelmatch";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const BUNDLE = path.resolve(HERE, "../../cli/src/main/resources/rc-player/bundle.js");
const FIXTURE = path.join(HERE, "fixtures", "icon-remote-size.rc");
const BAKED = path.join(HERE, "fixtures", "icon-remote-size.baked.png");
// The fixture's own generation size: a 200 dp square sticker at dpi 320 (density 2).
const WIDTH = 400;
const HEIGHT = 400;
// Headroom for antialiasing only. Both defects moved whole blocks of the canvas — the icon
// rendered at the widget's 24 dp default scored 5.8% here — so anything near this bound is a real
// divergence, not noise.
const MAX_MISMATCH_PCT = 0.5;

let chromium;
let browser;
let skip = false;

before(async () => {
  try {
    ({ chromium } = await import("playwright"));
  } catch {
    skip = "playwright is not installed";
    return;
  }
  if (!fs.existsSync(BUNDLE)) {
    skip = "player bundle is not built";
    return;
  }
  try {
    browser = await chromium.launch({
      headless: true,
      ...(process.env.RC_COMPARE_CHROMIUM ? { executablePath: process.env.RC_COMPARE_CHROMIUM } : {}),
      args: ["--enable-unsafe-swiftshader", "--no-sandbox"],
    });
  } catch (e) {
    skip = `chromium unavailable: ${String(e).split("\n")[0]}`;
  }
});

after(async () => {
  if (browser) await browser.close();
});

/** The fixture as this player paints it, as raw RGBA. */
async function playerRender() {
  const page = await browser.newPage({ viewport: { width: WIDTH, height: HEIGHT } });
  try {
    await page.setContent("<html><body style='margin:0'></body></html>");
    await page.addScriptTag({ path: BUNDLE });
    const b64 = fs.readFileSync(FIXTURE).toString("base64");
    const dataUrl = await page.evaluate(
      async ({ b64, w, h }) => {
        const canvas = document.createElement("canvas");
        canvas.width = w;
        canvas.height = h;
        document.body.appendChild(canvas);
        const bin = atob(b64);
        const bytes = new Uint8Array(bin.length);
        for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
        const player = new window.RcdPlayer(canvas);
        await player.loadFromArrayBuffer(bytes.buffer);
        player.repaint();
        return canvas.toDataURL("image/png");
      },
      { b64, w: WIDTH, h: HEIGHT },
    );
    return PNG.sync.read(Buffer.from(dataUrl.split(",")[1], "base64"));
  } finally {
    await page.close();
  }
}

test("an icon renders at the size the document asked for, not the widget's default", async (t) => {
  if (skip) return t.skip(skip);

  const rendered = await playerRender();
  const baked = PNG.sync.read(fs.readFileSync(BAKED));
  assert.equal(rendered.width, baked.width, "render width does not match the baked reference");
  assert.equal(rendered.height, baked.height, "render height does not match the baked reference");

  const painted = rendered.data.reduce((n, _v, i) => (i % 4 === 3 && rendered.data[i] > 40 ? n + 1 : n), 0);
  assert.ok(painted > 0, "the document painted nothing at all");

  const differing = pixelmatch(rendered.data, baked.data, null, baked.width, baked.height, {
    threshold: 0.1,
  });
  const pct = (100 * differing) / (baked.width * baked.height);
  assert.ok(
    pct <= MAX_MISMATCH_PCT,
    `player render differs from the baked reference by ${pct.toFixed(2)}% ` +
      `(limit ${MAX_MISMATCH_PCT}%). An icon drawn at the widget's built-in 24dp default instead ` +
      `of the size the document asked for lands around 5.8% here.`,
  );
});
