/**
 * rc-cmp-wasm-frame-pacing.test.mjs — the CMP/Wasm player must reach readiness as fast in a short
 * viewport as in a tall one.
 *
 * Run with `node --test scripts/design-artifacts/`. Skipped unless the player distribution is
 * built (`./gradlew :rc-player-wasm:wasmPlayerDist`, or point `RC_CMP_WASM_DIST` at one) — set
 * `RC_CMP_WASM_REQUIRE=1` to make every one of those skips a failure instead, which is how the
 * `CMP/Wasm Frame Pacing` CI job runs it. A guard that can silently skip is a guard that stops
 * guarding the moment its build step moves.
 *
 * The regression this guards (#3445): Chromium paces `requestAnimationFrame` at roughly **1 fps**
 * for a page whose CSS viewport is only a few dozen pixels tall, unless the compositor's frame cap
 * is lifted. The player reports readiness after three frames, so on the `remote-m3` catalog the
 * 216×76 dp widget previews took ~6,150 ms while the 216×124 dp one beside them took ~2,040 ms —
 * and because only the first render in a browser context is labelled `cold`, that ~6.1 s landed on
 * a **warm** row and breached the lane's 5,000 ms warm budget. The rc-compare summary reported it
 * as a startup-cost problem; it was frame pacing, and it reproduces with one document, one context,
 * and no cold cache at all.
 *
 * Asserted as a *ratio* rather than an absolute ceiling: the ~1.5 s the player deliberately waits
 * before posting `ready` dominates a healthy render, and machine speed moves the rest, but throttled
 * frames put whole seconds between the two viewports. The unfixed gap is ~3×.
 */
import { test, before, after } from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import http from "node:http";
import path from "node:path";
import { fileURLToPath } from "node:url";

import { CHROMIUM_LAUNCH_ARGS } from "./rc-chromium.mjs";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const DIST = path.resolve(
  process.env.RC_CMP_WASM_DIST || path.join(HERE, "../../rc-player/wasm/build/wasmDist"),
);
const FIXTURE = path.join(HERE, "fixtures", "watch-screen-round-clip.rc");
// 76 dp is the height of the `remote-m3` widget previews that regressed; 124 dp is the sibling
// preview that never did. Same document, same context, so the only variable is the viewport.
const SHORT = { width: 216, height: 76 };
const TALL = { width: 216, height: 124 };
// Throttled pacing put ~950 ms between frames instead of ~70 ms, over three frames. 2× leaves room
// for a slow machine's noise on a ~2 s render while staying far below the ~3× the bug produced.
const MAX_RATIO = 2;
const REQUIRE = process.env.RC_CMP_WASM_REQUIRE === "1";

let chromium;
let browser;
let server;
let origin;
let skip = false;

function contentType(file) {
  if (file.endsWith(".html")) return "text/html; charset=utf-8";
  if (file.endsWith(".mjs") || file.endsWith(".js")) return "text/javascript; charset=utf-8";
  if (file.endsWith(".wasm")) return "application/wasm";
  return "application/octet-stream";
}

before(async () => {
  try {
    ({ chromium } = await import("playwright"));
  } catch {
    skip = "playwright is not installed";
    return;
  }
  if (!fs.existsSync(path.join(DIST, "index.html"))) {
    skip = "the CMP/Wasm player distribution is not built";
    return;
  }
  const document = fs.readFileSync(FIXTURE);
  server = http.createServer((request, response) => {
    const pathname = decodeURIComponent(new URL(request.url, "http://localhost").pathname);
    if (pathname === "/document.rc") {
      response.writeHead(200, { "Content-Type": "application/octet-stream" });
      response.end(document);
      return;
    }
    const file = path.resolve(DIST, pathname === "/" ? "index.html" : pathname.slice(1));
    if (!file.startsWith(DIST) || !fs.existsSync(file) || !fs.statSync(file).isFile()) {
      response.writeHead(404).end();
      return;
    }
    response.writeHead(200, { "Content-Type": contentType(file) });
    fs.createReadStream(file).pipe(response);
  });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  origin = `http://127.0.0.1:${server.address().port}`;
  try {
    browser = await chromium.launch({
      headless: true,
      ...(process.env.RC_COMPARE_CHROMIUM ? { executablePath: process.env.RC_COMPARE_CHROMIUM } : {}),
      args: [...CHROMIUM_LAUNCH_ARGS],
    });
  } catch (e) {
    skip = `chromium unavailable: ${String(e).split("\n")[0]}`;
  }
});

after(async () => {
  await browser?.close();
  await new Promise((resolve) => (server ? server.close(resolve) : resolve()));
});

/** Navigation-to-`ready` for one viewport, the same measurement rc-compare's budgets score. */
async function firstFrameMs(page, viewport) {
  await page.setViewportSize(viewport);
  const startedAt = performance.now();
  await page.goto(`${origin}/index.html?src=${encodeURIComponent("/document.rc")}&theme=light`);
  await page.waitForFunction(
    () => ["ready", "error"].includes(document.documentElement.dataset.rcPlayerState),
    null,
    { timeout: 60_000 },
  );
  const elapsed = performance.now() - startedAt;
  const state = await page.evaluate(() => document.documentElement.dataset.rcPlayerState);
  // An `error` render skips the frame wait entirely, so it would pass this test for the wrong
  // reason. Fail loudly instead.
  assert.equal(state, "ready", "the fixture must actually render");
  return elapsed;
}

test("a short viewport reaches first frame as fast as a tall one", async (t) => {
  if (skip && REQUIRE) assert.fail(`RC_CMP_WASM_REQUIRE is set but the guard cannot run: ${skip}`);
  if (skip) return t.skip(skip);
  const context = await browser.newContext({ deviceScaleFactor: 2.625 });
  const page = await context.newPage();
  try {
    // Warm the context first: the point of comparison is frame pacing, not the player's one-off
    // load, which would otherwise land entirely on whichever viewport went first.
    await firstFrameMs(page, TALL);
    const tall = await firstFrameMs(page, TALL);
    const short = await firstFrameMs(page, SHORT);
    assert.ok(
      short < tall * MAX_RATIO,
      `short viewport ${short.toFixed(0)} ms vs tall ${tall.toFixed(0)} ms — ` +
        `frames are being throttled in the short one`,
    );
  } finally {
    await context.close();
  }
});
