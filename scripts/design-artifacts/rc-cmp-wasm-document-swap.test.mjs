/**
 * rc-cmp-wasm-document-swap.test.mjs — `window.rcPlayerLoad(src)`, the player's in-place document
 * handoff, from both sides that can break it: the pixels and the clock.
 *
 * Run with `node --test scripts/design-artifacts/`. Skipped unless the player distribution is
 * built (`./gradlew :rc-player-wasm:wasmPlayerDist`, or point `RC_CMP_WASM_DIST` at one); set
 * `RC_CMP_WASM_REQUIRE=1` to turn those skips into failures, as the `CMP/Wasm Frame Pacing` CI job
 * does.
 *
 * Navigating per document (#3445) threw away the instantiated Wasm module, the Compose runtime and
 * the host fonts and rebuilt all three to draw a document a few dozen operations long — a
 * per-preview floor that a 122-preview catalog pays 122 times. `rcPlayerLoad` hands the running
 * player a new source instead.
 *
 * What that trades away is isolation: a navigated render starts from a clean page by construction,
 * a swapped one does not. So the assertions here are about *equivalence*, not speed alone —
 * captured through [settledScreenshot] so that "equal pixels" is a property of the render and not a
 * race against font resolution —
 * a swapped render must be byte-identical to the navigated render of the same document, and must
 * stay so when the previous document is a different one, in either order.
 *
 * > **That equivalence is real for two documents and does not survive a corpus.** Running the 27
 * > `remote-m3` documents through one player leaves a *band* of the text-bearing ones with no text
 * > at all — shapes drawn, every glyph missing, permanently: the frame is still blank after a 5 s
 * > settle. Which band depends on the order and on the machine, which is how #3558 reached CI as a
 * > fixture scoring one of two stable values on identical input. `rc-compare.mjs` therefore
 * > navigates for every document; these tests keep guarding `rcPlayerLoad` for the hosts that still
 * > use it, and extending them to a corpus-length sequence is the open work that would let the
 * > driver go back to swapping.
 */
import { test, before, after } from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import http from "node:http";
import path from "node:path";
import { fileURLToPath } from "node:url";

import { CHROMIUM_LAUNCH_ARGS } from "./rc-chromium.mjs";
import { settledScreenshot } from "./rc-settle.mjs";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const DIST = path.resolve(
  process.env.RC_CMP_WASM_DIST || path.join(HERE, "../../rc-player/wasm/build/wasmDist"),
);
// Two documents that look nothing alike, so "the swap actually replaced the document" is visible in
// the pixels rather than inferred from a marker.
const DOCUMENTS = {
  "/watch.rc": path.join(HERE, "fixtures", "watch-screen-round-clip.rc"),
  "/icon.rc": path.join(HERE, "fixtures", "icon-remote-size.rc"),
};
const VIEWPORT = { width: 227, height: 227 };
const DENSITY = 2;
// A swap skips the navigation, the Wasm instantiation, the Compose start-up and the font load; only
// the fetch, decode and draw remain. Measured at ~0.15 s against ~0.5 s for a navigation in the same
// warm context. Asserted as a ratio, not a deadline, because both halves scale with machine speed —
// and at 2× a swap that quietly degraded into a full reload still fails.
const MIN_SPEEDUP = 2;
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
  const documents = new Map(
    Object.entries(DOCUMENTS).map(([route, file]) => [route, fs.readFileSync(file)]),
  );
  server = http.createServer((request, response) => {
    const pathname = decodeURIComponent(new URL(request.url, "http://localhost").pathname);
    if (documents.has(pathname)) {
      response.writeHead(200, {
        "Content-Type": "application/octet-stream",
        "Cache-Control": "no-store",
      });
      response.end(documents.get(pathname));
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

async function awaitReady(page) {
  await page.waitForFunction(
    () => ["ready", "error"].includes(document.documentElement.dataset.rcPlayerState),
    null,
    { timeout: 60_000 },
  );
  const state = await page.evaluate(() => ({
    state: document.documentElement.dataset.rcPlayerState,
    error: document.documentElement.dataset.rcPlayerError,
  }));
  assert.equal(state.state, "ready", `the fixture must render: ${state.error ?? ""}`);
}

/**
 * Captured the way the driver captures: after the pixels converge, not at `ready`.
 *
 * `ready` is three frames, and Compose's font resolution can land a redraw after them, so a raw
 * capture at `ready` is not reproducible for a document with text — comparing two of them would
 * make this file's pixel assertions flaky rather than strict. `elapsed` still measures to `ready`,
 * which is the number the lane budgets.
 */
async function capture(page, startedAt) {
  await awaitReady(page);
  const elapsed = performance.now() - startedAt;
  const { buffer } = await settledScreenshot(page);
  return { elapsed, png: buffer };
}

/** Load a document the way the driver's first render in a context does: a full navigation. */
async function navigate(page, source) {
  const startedAt = performance.now();
  await page.goto(
    `${origin}/index.html?src=${encodeURIComponent(source)}&theme=light&handoffDelayMs=0`,
  );
  return capture(page, startedAt);
}

/** Load a document the way every render after the first does: in place, no navigation. */
async function swap(page, source) {
  const startedAt = performance.now();
  await page.evaluate((src) => window.rcPlayerLoad(src), source);
  return capture(page, startedAt);
}

async function withPage(run) {
  const context = await browser.newContext({ deviceScaleFactor: DENSITY });
  const page = await context.newPage();
  await page.setViewportSize(VIEWPORT);
  try {
    return await run(page);
  } finally {
    await context.close();
  }
}

function guard(t) {
  if (skip && REQUIRE) assert.fail(`RC_CMP_WASM_REQUIRE is set but the guard cannot run: ${skip}`);
  if (skip) {
    t.skip(skip);
    return true;
  }
  return false;
}

test("a swapped document renders exactly what navigating to it renders", async (t) => {
  if (guard(t)) return;
  const { swapped, navigated } = await withPage(async (page) => {
    await navigate(page, "/watch.rc");
    const swapped = await swap(page, "/icon.rc");
    // The reference is a navigation *in the same context*, so the only difference between the two
    // is how the document arrived — not the density, the viewport or the cache.
    const navigated = await navigate(page, "/icon.rc");
    return { swapped, navigated };
  });
  assert.ok(
    swapped.png.equals(navigated.png),
    "a swapped render must be byte-identical to a navigated one",
  );
});

test("swapping back and forth leaves no trace of the outgoing document", async (t) => {
  if (guard(t)) return;
  const { first, second, reference } = await withPage(async (page) => {
    await navigate(page, "/icon.rc");
    // watch → icon → watch: the middle document is the one that could leak into the third render,
    // and the first render is the one it would have to match.
    const first = await swap(page, "/watch.rc");
    await swap(page, "/icon.rc");
    const second = await swap(page, "/watch.rc");
    const reference = await navigate(page, "/watch.rc");
    return { first, second, reference };
  });
  assert.ok(first.png.equals(second.png), "the same document must render the same after a detour");
  assert.ok(second.png.equals(reference.png), "and must match a navigated render of it");
});

test("a swap is materially cheaper than the navigation it replaces", async (t) => {
  if (guard(t)) return;
  const { navigated, swapped } = await withPage(async (page) => {
    await navigate(page, "/watch.rc"); // pay the cold start before either measurement
    // Warm navigation vs. swap, alternating the same pair, so neither measurement is the one that
    // filled the HTTP cache and neither document is inherently the cheaper one.
    await swap(page, "/icon.rc");
    const navigated = await navigate(page, "/watch.rc");
    const swapped = await swap(page, "/icon.rc");
    return { navigated, swapped };
  });
  assert.ok(
    navigated.elapsed / swapped.elapsed >= MIN_SPEEDUP,
    `swap took ${swapped.elapsed.toFixed(0)} ms against ${navigated.elapsed.toFixed(0)} ms for a ` +
      `warm navigation — the player is being rebuilt rather than handed the document`,
  );
});

test("the readiness marker clears before rcPlayerLoad returns", async (t) => {
  if (guard(t)) return;
  const marker = await withPage(async (page) => {
    await navigate(page, "/watch.rc");
    // The driver screenshots on `ready`. If the marker outlived the call by even one turn of the
    // event loop, the next `waitForFunction` would pass on the *previous* document's readiness and
    // capture whichever of the two happened to be on screen.
    return await page.evaluate((src) => {
      window.rcPlayerLoad(src);
      return document.documentElement.dataset.rcPlayerState;
    }, "/icon.rc");
  });
  assert.equal(marker, "loading", "rcPlayerLoad must retract `ready` synchronously");
});
