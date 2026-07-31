// End-to-end proof that the Kotlin **playground** of a live, daemon-backed
// `compose-preview serve` works the whole way through: the browser editor compiles a
// Compose snippet on the server, gets back a first-frame still, and the returned
// `/pg/<token>` capability redeems into the ordinary live viewer.
//
// This is the browser counterpart to the playground's unit tests (compile service,
// token store, redeem service): those prove each seam in isolation against fakes;
// this proves the real wiring — editor page → `POST /api/1/compiler/run` → BTA
// compile → Android/Robolectric first frame → `/pg/` live redemption → viewer — with
// a real daemon behind it, the one thing a fake can't cover.
//
// Requires a running playground serve; point SERVE_URL at it. The CI job boots one
// with `--playground-android-bundle` (a locally packed `:samples:android-live-lane`),
// token-gated (the lane is refused under --public), so the spec appends
// `?token=<SERVE_TOKEN>` to every navigation. Self-skips with a clear message when no
// SERVE_URL / playground page is reachable (a local run without a target).

import { test, expect } from "@playwright/test";

// Must match the `--token` the boot script passed to serve.
const TOKEN = process.env.SERVE_TOKEN || "playground-e2e";
// The editor's Android mode option (ServeWeb.playgroundModeChoice): compiles the
// snippet against the Android bundle and mints a live `/pg/` token.
const ANDROID_MODE = "compose-android";

// The token gates every route; carry it on each navigation.
const q = `?token=${encodeURIComponent(TOKEN)}`;

// Resolved in beforeAll: is a playground editor page actually reachable? Left false
// when SERVE_URL is unset/unreachable so the suite self-skips locally (and hard-fails
// in CI, where the boot step guarantees the page).
let playgroundUp = false;

test.beforeAll(async ({ request }) => {
  const res = await request.get(`/playground${q}`);
  playgroundUp = res.ok() && (await res.text()).includes('id="pg-source"');
});

function requirePlayground() {
  if (!playgroundUp && process.env.CI) {
    throw new Error(
      `no playground editor at /playground — the daemon-backed serve isn't exposing the lane; ` +
        `refusing to green-skip the Playground suite`,
    );
  }
  test.skip(
    !playgroundUp,
    `no playground editor reachable at /playground — is a --playground-android-bundle serve ` +
      `running at ${process.env.SERVE_URL} with token "${TOKEN}"?`,
  );
}

// After a run the status ends on one of two terminal strings ("Done." on success, or
// an error message on a compile/exception failure) — "Compiling…" is the only
// non-terminal one. Wait for a terminal status, then let the test assert which.
async function runAndAwaitTerminal(page) {
  await page.click("#pg-run");
  const status = page.locator("#pg-status");
  await expect(status).toBeVisible();
  await expect
    .poll(async () => (await status.textContent())?.trim(), {
      // The compile POST blocks on the cold Android first-frame render (synchronous,
      // up to the service's 180s budget) before it answers.
      timeout: 280_000,
    })
    .not.toBe("Compiling…");
  return (await status.textContent())?.trim();
}

test("editor page serves its controls and the Android mode", async ({
  page,
}) => {
  requirePlayground();
  await page.goto(`/playground${q}`, { waitUntil: "domcontentloaded" });

  await expect(page.locator("#pg-source"), "source editor").toBeVisible();
  await expect(page.locator("#pg-mode"), "mode selector").toBeVisible();
  await expect(page.locator("#pg-run"), "run button").toBeVisible();

  // The Android compile mode must be offered — it's the one this lane serves.
  const values = await page
    .locator("#pg-mode option")
    .evaluateAll((opts) => opts.map((o) => o.value));
  expect(values, "mode options").toContain(ANDROID_MODE);
});

test("compiles the default Android snippet to a first frame + live /pg/ handoff", async ({
  page,
}) => {
  requirePlayground();
  await page.goto(`/playground${q}`, { waitUntil: "domcontentloaded" });

  // The default sample already declares an Android @Preview; just select the mode
  // and run it — a clean compile is the happy path this test pins.
  await page.selectOption("#pg-mode", ANDROID_MODE);
  const terminal = await runAndAwaitTerminal(page);
  expect(
    terminal,
    `run should succeed on the default snippet, got status "${terminal}" ` +
      `(diagnostics: ${await page.locator("#pg-diagnostics").textContent()})`,
  ).toBe("Done.");

  // A successful CMP/Android run always mints a live preview token and surfaces its
  // "Open live preview →" handoff pointing at /pg/<token> (the still image is
  // best-effort — the token is the contract).
  const open = page.locator("#pg-open");
  await expect(open, "live-preview handoff link").toBeVisible();
  const href = await open.getAttribute("href");
  expect(href, "handoff href targets the /pg/ capability").toMatch(
    /\/pg\/pg_[A-Za-z0-9_-]+/,
  );
});

test("the /pg/ token redeems into the live viewer", async ({ page }) => {
  requirePlayground();
  await page.goto(`/playground${q}`, { waitUntil: "domcontentloaded" });
  await page.selectOption("#pg-mode", ANDROID_MODE);
  const terminal = await runAndAwaitTerminal(page);
  test.skip(
    terminal !== "Done.",
    `compile did not succeed (status "${terminal}") — nothing to redeem`,
  );

  // Follow the handoff. Redemption registers the compiled snippet as a live session
  // and 302-redirects to the ordinary viewer at /<sessionId>/p/<previewId>; the
  // browser lands there, NOT on the styled /pg/ 404 (which would mean Unavailable —
  // no live backend). So the post-navigation URL must have left /pg/ for a /p/ route.
  const open = page.locator("#pg-open");
  const href = await open.getAttribute("href");
  await page.goto(href, { waitUntil: "domcontentloaded" });

  await expect
    .poll(() => new URL(page.url()).pathname, { timeout: 30_000 })
    .toMatch(/\/p\//);
  expect(
    new URL(page.url()).pathname,
    "left the /pg/ capability route",
  ).not.toMatch(/^\/pg\//);

  // The viewer stage is present — the redeemed session resolved to the real viewer,
  // not an error page. (The live frame itself is the /ws/ lane's job, proven by the
  // serve-lanes suite; here we only assert redemption reached the viewer.)
  await expect(page.locator("#cp-img"), "viewer stage").toBeAttached();
});
