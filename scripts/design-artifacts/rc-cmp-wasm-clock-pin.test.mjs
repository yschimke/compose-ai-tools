/**
 * rc-cmp-wasm-clock-pin.test.mjs — a clock-reading document must render the same pixels twice.
 *
 * Run with `node --test scripts/design-artifacts/`. Skipped unless the player distribution is built
 * (`./gradlew :rc-player-wasm:wasmPlayerDist`, or point `RC_CMP_WASM_DIST` at one) — set
 * `RC_CMP_WASM_REQUIRE=1` to turn every skip into a failure, which is how the `CMP/Wasm Frame
 * Pacing` CI job runs the browser-level guards.
 *
 * The regression this guards (#4431): `remote-m3`'s indeterminate circular progress builds its
 * sweep from a float expression over `CONTINUOUS_SEC`, which the player loads from the wall clock.
 * The capture is not caught mid-animation — measured, the pose is picked at load and then holds
 * still for at least 7 s, which is why the settle loop converges and reports nothing wrong — but
 * *which* pose depends on the second the render started at. So the parity lane scored the second
 * hand: 0.05%–0.94% for this one row across five pull requests that never touched a render path,
 * against a 0.25 pp gate.
 *
 * This is the check #4431 (and #3558 before it) asked for and never got: render one commit twice in
 * this lane and diff the two captures against each other. Unpinned they differ; pinned
 * (`rc-clock.mjs`) they are byte-identical.
 */
import { test, before, after } from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import http from "node:http";
import path from "node:path";
import { fileURLToPath } from "node:url";

import { CHROMIUM_LAUNCH_ARGS } from "./rc-chromium.mjs";
import { pinWallClock } from "./rc-clock.mjs";
import { settledScreenshot } from "./rc-settle.mjs";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const DIST = path.resolve(
  process.env.RC_CMP_WASM_DIST || path.join(HERE, "../../rc-player/wasm/build/wasmDist"),
);
// The document from the parity row that oscillates, taken out of the published `remote-m3` bundle
// — one copy, provenance recorded in that directory's README.
const FIXTURE = path.resolve(
  HERE,
  "../../rc-player/compose/src/jvmTest/resources/rc-fixtures/IndeterminateCircularProgress-400x400.rc",
);
// The parity lane renders this row at 200×200 dp, dpi 320 (deviceScaleFactor 2).
const VIEWPORT = { width: 200, height: 200 };
const DENSITY = 2;
// Long enough that an unpinned `CONTINUOUS_SEC` has moved the arc well past a rounding difference:
// the sweep completes a rotation in a couple of seconds.
const APART_MS = 1_200;
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
  if (!fs.existsSync(FIXTURE)) {
    skip = `the fixture is missing: ${FIXTURE}`;
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
      ...(process.env.RC_COMPARE_CHROMIUM
        ? { executablePath: process.env.RC_COMPARE_CHROMIUM }
        : {}),
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

/**
 * One parity-lane capture: the same navigation, flags and settle loop `rc-compare.mjs` uses, so
 * what this measures is what the lane measures.
 */
async function capture(page) {
  await page.setViewportSize(VIEWPORT);
  await page.goto(
    `${origin}/index.html?src=${encodeURIComponent("/document.rc")}&theme=light&handoffDelayMs=0`,
  );
  await page.waitForFunction(
    () => ["ready", "error"].includes(document.documentElement.dataset.rcPlayerState),
    null,
    { timeout: 60_000 },
  );
  const state = await page.evaluate(() => document.documentElement.dataset.rcPlayerState);
  assert.equal(state, "ready", "the fixture must actually render");
  return settledScreenshot(page, { screenshotOptions: { omitBackground: true } });
}

test("a pinned wall clock renders the indeterminate sweep identically twice", async (t) => {
  if (skip && REQUIRE) assert.fail(`RC_CMP_WASM_REQUIRE is set but the guard cannot run: ${skip}`);
  if (skip) return t.skip(skip);
  const context = await browser.newContext({ deviceScaleFactor: DENSITY });
  const page = await context.newPage();
  try {
    // Before the first navigation: Playwright installs the fake `Date` as an init script.
    await pinWallClock(page);
    // Warm the context so the first capture is not the one paying the player's cold load.
    await capture(page);
    const first = await capture(page);
    await new Promise((resolve) => setTimeout(resolve, APART_MS));
    const second = await capture(page);
    assert.ok(first.settled, "a document whose clock is held still must settle");
    assert.ok(second.settled, "a document whose clock is held still must settle");
    assert.ok(
      first.buffer.equals(second.buffer),
      `two captures ${APART_MS} ms apart differ (${first.buffer.length} vs ` +
        `${second.buffer.length} bytes) — the sweep is still reading a moving clock`,
    );
  } finally {
    await context.close();
  }
});

test("unpinned, the same document renders a different pose per load — the #4431 bug", async (t) => {
  if (skip && REQUIRE) assert.fail(`RC_CMP_WASM_REQUIRE is set but the guard cannot run: ${skip}`);
  if (skip) return t.skip(skip);
  const context = await browser.newContext({ deviceScaleFactor: DENSITY });
  const page = await context.newPage();
  try {
    // The control the fix is measured against. Three loads spread over ~2.5 s: each converges to a
    // stable frame — that is the trap, a settled capture looks like a measurement — and each
    // converges to a *different* one. Asserted as "not all three agree" rather than "all three
    // differ", so the vanishing chance of two loads landing on the same millisecond of the sweep
    // cannot fail the build.
    await capture(page);
    const shots = [];
    for (let i = 0; i < 3; i++) {
      if (i) await new Promise((resolve) => setTimeout(resolve, APART_MS));
      shots.push((await capture(page)).buffer);
    }
    assert.ok(
      !(shots[0].equals(shots[1]) && shots[1].equals(shots[2])),
      "three unpinned loads produced the same pixels — the sweep is not reading the wall clock, " +
        "so this control no longer reproduces what the pin fixes",
    );
  } finally {
    await context.close();
  }
});
