/**
 * FontData (opcode 189) in the shipped JS player.
 *
 * The fixture is assembled from the wire primitives here and embeds the catalog's Orbitron face.
 * Its drawable text operation deliberately follows FontData: before opcode 189 was registered the
 * decoder returned at that byte, so the canvas stayed empty. The face has visibly different
 * metrics from the browser fallback, which also proves the decoded bytes are selected by typeface
 * id rather than merely skipped without truncating the document.
 */
import { test, before, after } from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(HERE, "../..");
const BUNDLE = path.join(ROOT, "cli/serve/src/main/resources/rc-player/bundle.js");
const FONT = path.join(ROOT, "samples/design-catalog-m3/src/main/resources/fonts/orbitron-400.ttf");
const FAMILY_ID = 42;
const TEXT_ID = 43;
const SPECIMEN = "FONTDATA 0123456789";

let browser;
let skip = false;

before(async () => {
  try {
    const { chromium } = await import("playwright");
    browser = await chromium.launch({ headless: true, args: ["--no-sandbox"] });
  } catch (e) {
    skip = `chromium unavailable: ${String(e).split("\n")[0]}`;
  }
});

after(async () => browser?.close());

/** A minimal modern Remote Compose document containing canvas text after FontData. */
function fontDataFixture(font, embedded = true) {
  const bytes = [];
  const scratch = new DataView(new ArrayBuffer(4));
  const byte = (v) => bytes.push(v & 0xff);
  const short = (v) => { byte(v >>> 8); byte(v); };
  const int = (v) => {
    scratch.setInt32(0, v, false);
    for (let i = 0; i < 4; i++) byte(scratch.getUint8(i));
  };
  const floatBits = (v) => { scratch.setFloat32(0, v, false); return scratch.getInt32(0, false); };
  const buffer = (data) => { int(data.length); for (const v of data) byte(v); };
  const utf8 = (value) => buffer(new TextEncoder().encode(value));

  // Header v1.1 (document API 7, where FontData was added), 256 x 96.
  byte(0); int(0x048c0001); int(1); int(0); int(2);
  short(5); short(4); int(256);
  short(6); short(4); int(96);

  if (embedded) {
    // The family name and FontData intentionally share an id, matching AndroidX authoring.
    byte(102); int(FAMILY_ID); utf8("Embedded Orbitron");
    byte(189); int(FAMILY_ID); int(0); buffer(font);
  }

  // Everything below must survive opcode 189.
  byte(102); int(TEXT_ID); utf8(SPECIMEN);
  byte(40); int(6);
  int(4); int(0xff111111 | 0);                       // COLOR
  int(1); int(floatBits(32));                        // TEXT_SIZE
  int(16 | (400 << 16)); int(embedded ? FAMILY_ID : 0); // TYPEFACE
  byte(43); int(TEXT_ID); int(0); int(-1); int(0); int(-1);
  int(floatBits(8)); int(floatBits(52)); byte(0);     // DRAW_TEXT_RUN
  return Uint8Array.from(bytes);
}

test("FontData does not truncate following ops and its face draws canvas text", async (t) => {
  if (skip) return t.skip(skip);
  if (!fs.existsSync(BUNDLE)) return t.skip("player bundle is not built");
  if (!fs.existsSync(FONT)) return t.skip("Orbitron fixture font is not vendored");

  const page = await browser.newPage({ viewport: { width: 256, height: 96 } });
  const warnings = [];
  page.on("console", (message) => {
    if (message.type() === "warning") warnings.push(message.text());
  });
  try {
    await page.setContent("<canvas id='embedded' width='256' height='96'></canvas><canvas id='fallback' width='256' height='96'></canvas>");
    await page.addScriptTag({ path: BUNDLE });
    const fixture = fontDataFixture(fs.readFileSync(FONT));
    const fallbackFixture = fontDataFixture(new Uint8Array(), false);
    const result = await page.evaluate(async ({ fixture, fallbackFixture }) => {
      const render = async (id, bytes) => {
        const canvas = document.querySelector(`#${id}`);
        const player = new window.RcdPlayer(canvas);
        await player.loadFromArrayBuffer(Uint8Array.from(bytes).buffer);
        await player.fontsReady();
        player.repaint();
        return {
          operationCount: player.getDocument().getNumberOfOps(),
          pixels: [...canvas.getContext("2d").getImageData(0, 0, canvas.width, canvas.height).data],
        };
      };
      const embedded = await render("embedded", fixture);
      const fallback = await render("fallback", fallbackFixture);
      let painted = 0;
      let differing = 0;
      for (let i = 0; i < embedded.pixels.length; i++) {
        if (i % 4 === 3 && embedded.pixels[i] !== 0) painted++;
        if (embedded.pixels[i] !== fallback.pixels[i]) differing++;
      }
      const faces = [];
      document.fonts.forEach((face) => {
        if (face.family.includes("__rc_font_")) faces.push(`${face.family}:${face.status}`);
      });
      return {
        operationCount: embedded.operationCount,
        painted,
        differing,
        faces,
      };
    }, { fixture: [...fixture], fallbackFixture: [...fallbackFixture] });

    assert.equal(warnings.some((w) => w.includes("Unknown operation opcode: 189")), false, warnings.join("\n"));
    assert.ok(result.operationCount >= 6, `ops after FontData were lost: ${result.operationCount}`);
    assert.ok(result.painted > 0, "DrawText after FontData did not reach the canvas");
    assert.ok(result.faces.some((face) => face.endsWith(":loaded")), result.faces.join(", "));
    assert.ok(result.differing > 0, "embedded face rendered the same pixels as the fallback face");
  } finally {
    await page.close();
  }
});
