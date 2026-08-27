/**
 * rc-font-axis-order.test.mjs — a second document's font-variation axis still reaches the API, and
 * the frame that keeps it is measured in the face that landed.
 *
 * Run with `node --test 'scripts/design-artifacts/*.test.mjs'`.
 *
 * Both defects here were invisible to a per-document test and only appeared in a *catalog* run,
 * which is what `rc-compare.mjs` does: one page, every `ir/*.rc` in turn
 * ([#4177](https://github.com/yschimke/compose-ai-tools/issues/4177)).
 *
 * 1. **The axis request was skipped.** After the weight specimen registered `Roboto Flex` over
 *    `wght`, the page carried a variable face — and "we have a variable face" was read as "the axes
 *    can be applied", so the width specimen's `wdth` request was dropped. Its three lines then drew
 *    from a face pinning `wdth` at 100, i.e. at one width. Document *order* decided it.
 * 2. **The frame was measured in the wrong face.** Fixing (1) exposed it: the text was measured
 *    before the variable face arrived, so the widest line rendered wider than the box measured for
 *    it and was clipped mid-word — `Hamburg · wdth 1`.
 *
 * Hermetic. The stylesheet API is faked over localhost and answers a range request the way css2
 * does — one face declaring the range — served from the repo's own vendored **variable** Roboto
 * Flex. Nothing here touches fonts.googleapis.com, and the ramp it asserts is a property of the
 * file, not of the network.
 *
 * The fixtures are the `remote-m3` catalog's own two specimens, in the order the catalog renders
 * them: `VariableWeightRemote` then `VariableWidthRemote`. Rendering the width one alone passes
 * even against the bug, which is the whole point of pairing them.
 */
import { test, before, after } from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import http from "node:http";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { PNG } from "pngjs";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const BUNDLE = path.resolve(HERE, "../../cli/serve/src/main/resources/rc-player/bundle.js");
const WEIGHT_FIXTURE = path.join(HERE, "fixtures", "typeface-variable-weight.rc");
const WIDTH_FIXTURE = path.join(HERE, "fixtures", "typeface-variable-width.rc");
/** The catalog's own variable face — an `fvar` table is what makes the ramp possible at all. */
const VARIABLE_FONT = path.resolve(
  HERE,
  "../../samples/cmp-wasm-catalog/src/wasmJsMain/resources/fonts/RobotoFlex.ttf",
);
const FAMILY = "Roboto Flex";
/** Both specimens are captured 320×240 dp at dpi 320. */
const WIDTH = 640;
const HEIGHT = 480;

let chromium;
let browser;
let server;
let origin;
let skip = false;
/** Every css2 query the page asked for, so "was the second axis requested?" is a fact, not a guess. */
const stylesheetQueries = [];

before(async () => {
  try {
    ({ chromium } = await import("playwright"));
  } catch {
    skip = "playwright is not installed";
    return;
  }
  for (const file of [BUNDLE, WEIGHT_FIXTURE, WIDTH_FIXTURE, VARIABLE_FONT]) {
    if (!fs.existsSync(file)) {
      skip = `missing ${path.basename(file)}`;
      return;
    }
  }
  const font = fs.readFileSync(VARIABLE_FONT);

  server = http.createServer((req, res) => {
    const route = req.url.split("?")[0];
    if (route === "/") {
      res.writeHead(200, { "content-type": "text/html" });
      res.end("<!doctype html><html><head></head><body style='margin:0'></body></html>");
    } else if (route === "/css2") {
      stylesheetQueries.push(req.url.slice(route.length));
      // css2's answer follows the *axes asked for*, and the test turns on that: a request naming
      // only `wght` gets a face whose weight is a range and whose width is pinned — variable, and
      // still unable to draw a `wdth` ramp. Declaring both ranges whatever was asked would make the
      // page carry a width-varying face after a weight-only request, which is precisely the state
      // the bug misread, so a lax fake here hides it.
      const asked = /:([a-zA-Z,]+)@([\d.,.]+)/.exec(req.url.slice(route.length));
      const tags = asked && asked[1].includes("@") === false ? asked[1].split(",") : [];
      const spans = asked ? asked[2].split(",") : [];
      const span = (tag) => {
        const i = tags.indexOf(tag);
        return i >= 0 && spans[i] && spans[i].includes("..") ? spans[i].split("..") : null;
      };
      const weight = span("wght");
      const width = span("wdth");
      const ranged = Boolean(weight || width);
      res.writeHead(200, { "content-type": "text/css", "access-control-allow-origin": "*" });
      res.end(
        ranged
          ? `@font-face{font-family:'${FAMILY}';font-style:normal;` +
              `font-weight:${weight ? weight.join(" ") : "400"};` +
              `font-stretch:${width ? `${width[0]}% ${width[1]}%` : "100%"};` +
              `font-display:block;src:url(${origin}/variable.ttf) format('truetype');}`
          : `@font-face{font-family:'${FAMILY}';font-style:normal;font-weight:400;` +
              `font-display:block;src:url(${origin}/static.ttf) format('truetype');}`,
      );
    } else if (route === "/variable.ttf" || route === "/static.ttf") {
      res.writeHead(200, { "content-type": "font/ttf", "access-control-allow-origin": "*" });
      res.end(font);
    } else {
      res.writeHead(404).end();
    }
  });
  await new Promise((r) => server.listen(0, "127.0.0.1", r));
  origin = `http://127.0.0.1:${server.address().port}`;

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
  server?.close();
});

/**
 * Play both fixtures through one page, in the order the catalog renders them, and report what the
 * second one asked the stylesheet API for and whether its measure was invalidated.
 */
async function playBothDocuments() {
  const page = await browser.newPage({ viewport: { width: WIDTH, height: HEIGHT } });
  try {
    await page.goto(`${origin}/`);
    await page.addScriptTag({ path: BUNDLE });
    return await page.evaluate(
      async ({ base, docs, w, h }) => {
        RC.configureWebFonts({ baseUrl: base });
        let remeasured = 0;
        for (const b64 of docs) {
          const canvas = document.createElement("canvas");
          canvas.width = w;
          canvas.height = h;
          document.body.appendChild(canvas);
          const bin = atob(b64);
          const bytes = new Uint8Array(bin.length);
          for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
          const player = new window.RcdPlayer(canvas);
          const doc = await player.loadFromArrayBuffer(bytes.buffer);
          // Count the re-measures `fontsReady()` triggers. Text laid out before a face arrives was
          // laid out in the fallback, and the caller's next move is the frame it keeps.
          const invalidate = doc.invalidateMeasure.bind(doc);
          remeasured = 0;
          doc.invalidateMeasure = () => {
            remeasured += 1;
            invalidate();
          };
          // The sequence a single-shot renderer uses: the first paint is what *discovers* the
          // families and their axes, so the wait and the kept frame both come after it.
          player.repaint();
          await player.fontsReady();
          player.repaint();
          canvas.remove();
        }
        return { remeasured };
      },
      {
        base: `${origin}/css2`,
        docs: [WEIGHT_FIXTURE, WIDTH_FIXTURE].map((f) => fs.readFileSync(f).toString("base64")),
        w: WIDTH,
        h: HEIGHT,
      },
    );
  } finally {
    await page.close();
  }
}

test("a second document's axis is requested, and its frame is re-measured", async (t) => {
  if (skip) return t.skip(skip);

  stylesheetQueries.length = 0;
  const { remeasured } = await playBothDocuments();

  // The width specimen's axis. Before the fix the page already carried a variable face — the weight
  // specimen's — and that was read as "the axes can be applied", so this request was never made and
  // the three lines drew from a face pinning `wdth` at 100. Both axes ride in one request because
  // the recorded spans accumulate per family.
  assert.ok(
    stylesheetQueries.some((q) => q.includes("wdth,wght@25..151")),
    `expected the second document's wdth range to be requested, saw: ${JSON.stringify(
      stylesheetQueries,
    )}`,
  );

  // …and the frame kept after it is measured in the face that landed. Without this the widest line
  // is painted wider than the box measured for it in the fallback, and clipped mid-word.
  assert.ok(remeasured > 0, "fontsReady() must invalidate the measure taken before the faces landed");
});
