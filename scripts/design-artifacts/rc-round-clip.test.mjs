/**
 * rc-round-clip.test.mjs — the player's handling of a **size-relative** clip shape.
 *
 * Run with `node --test scripts/design-artifacts/`.
 *
 * `RemoteModifier.clip(RemoteCircleShape)` writes each `MODIFIER_ROUNDED_CLIP_RECT` (opcode 54)
 * corner as a NaN-encoded expression over the component's measured size, not as a dp literal. Read
 * as a plain float that payload collapses to `NaN`, `ctx.roundRect` then ignores the whole radius
 * list, and the `clip()` that follows gets an **empty path** — which hides everything drawn inside
 * the component. The round watch screen replayed as a blank canvas that way (#2930).
 *
 * Why this is a pixel assertion in a real browser rather than a unit test on the modifier: the
 * failure needs the actual Canvas2D semantics (`roundRect`'s non-finite bail-out, and what an empty
 * clip does to later draws) to reproduce at all, and it is silent everywhere else — the document
 * parses, no opcode is unknown, nothing warns. Only the pixels tell you. It exercises the built
 * bundle (`cli/src/main/resources/rc-player/bundle.js`) for the same reason `rc-webfonts.test.mjs`
 * does: that is the artifact that actually ships, and a stale bundle beside fixed source is exactly
 * the regression worth catching.
 */
import { test, before, after } from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const BUNDLE = path.resolve(HERE, "../../cli/src/main/resources/rc-player/bundle.js");
const FIXTURE = path.join(HERE, "fixtures", "watch-screen-round-clip.rc");
// The fixture's own generation size; the document is a 227dp round watch face at density 2.
const WIDTH = 454;
const HEIGHT = 454;
// The baked reference for this preview covers 78.85% of the canvas (a near-full-bleed dark disc).
// Half of that is a wide margin against text-metric drift while still being unreachable for the
// blank render this guards — and for a render that lost only the disc and kept the cards.
const MIN_COVERAGE_PCT = 40;

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
  await browser?.close();
});

/**
 * Plays the fixture and reports what landed on the canvas: the share of pixels with any alpha, and
 * the distinct colour count (a blank canvas has exactly one — nothing was drawn).
 */
async function play() {
  const page = await browser.newContext({ deviceScaleFactor: 1 }).then((c) => c.newPage());
  const warnings = [];
  page.on("console", (m) => {
    if (m.type() === "warning" || m.type() === "error") warnings.push(m.text());
  });
  await page.setContent("<!doctype html><html><head></head><body></body></html>");
  await page.addScriptTag({ content: fs.readFileSync(BUNDLE, "utf8") });
  const stats = await page.evaluate(
    async ({ b64, w, h }) => {
      const canvas = document.createElement("canvas");
      canvas.width = w;
      canvas.height = h;
      document.body.appendChild(canvas);
      const bin = atob(b64);
      const bytes = new Uint8Array(bin.length);
      for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
      const player = new window.RcdPlayer(canvas);
      player.setTheme("light");
      await player.loadFromArrayBuffer(bytes.buffer);
      player.repaint();
      await player.fontsReady();
      player.repaint();
      const data = canvas.getContext("2d").getImageData(0, 0, w, h).data;
      const colours = new Set();
      let painted = 0;
      for (let i = 0; i < data.length; i += 4) {
        if (data[i + 3] > 8) painted++;
        colours.add((data[i] << 24) | (data[i + 1] << 16) | (data[i + 2] << 8) | data[i + 3]);
      }
      canvas.remove();
      return { coveragePct: (100 * painted) / (w * h), colours: colours.size };
    },
    { b64: fs.readFileSync(FIXTURE).toString("base64"), w: WIDTH, h: HEIGHT },
  );
  await page.close();
  return { ...stats, warnings };
}

test("a circle-clipped component paints its content instead of clipping it all away", async (t) => {
  if (skip) return t.skip(skip);
  const { coveragePct, colours, warnings } = await play();
  // Stated first because it is the diagnosis, not just a symptom: a document that failed to decode
  // is a different bug from one that decoded and drew nothing.
  assert.deepEqual(
    warnings.filter((w) => /Unknown operation opcode/.test(w)),
    [],
    "the fixture must decode fully — an unknown opcode would make the pixel check meaningless",
  );
  assert.ok(colours > 1, `nothing was drawn: the canvas holds ${colours} distinct colour(s)`);
  assert.ok(
    coveragePct > MIN_COVERAGE_PCT,
    `only ${coveragePct.toFixed(2)}% of the canvas was painted, expected more than ` +
      `${MIN_COVERAGE_PCT}% — the round clip is swallowing the component's content`,
  );
});
