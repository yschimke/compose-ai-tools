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

// The editor is CodeMirror when its bundle loaded and a plain <textarea> when it didn't (the
// page degrades on purpose). Drive whichever is live rather than assuming: `fromTextArea`
// hides `#pg-source`, so `fill()` on it would fail against the real page.
async function setSource(page, text) {
  await page.evaluate((value) => {
    const cm = document.querySelector(".CodeMirror");
    if (cm && cm.CodeMirror) {
      cm.CodeMirror.setValue(value);
      return;
    }
    const ta = document.getElementById("pg-source");
    ta.value = value;
    ta.dispatchEvent(new Event("input", { bubbles: true }));
  }, text);
}

/**
 * The visible editing surface — CodeMirror's wrapper, or the textarea when it's absent.
 *
 * Not a `.CodeMirror, #pg-source` locator with `.first()`: `fromTextArea` leaves the hidden
 * textarea EARLIER in the DOM than the wrapper it inserts, so first-in-document-order picks the
 * invisible one and a visibility assertion fails against a perfectly healthy editor.
 */
async function sourceLocator(page) {
  const cm = page.locator(".CodeMirror");
  return (await cm.count()) > 0 ? cm.first() : page.locator("#pg-source");
}

test("editor page serves its controls and the Android mode", async ({
  page,
}) => {
  requirePlayground();
  await page.goto(`/playground${q}`, { waitUntil: "domcontentloaded" });

  await expect(await sourceLocator(page), "source editor").toBeVisible();
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

  // A successful CMP/Android run mints a live preview token and surfaces its
  // "Open live preview →" handoff pointing at /pg/<token>.
  const open = page.locator("#pg-open");
  await expect(open, "live-preview handoff link").toBeVisible();
  const href = await open.getAttribute("href");
  expect(href, "handoff href targets the /pg/ capability").toMatch(
    /\/pg\/pg_[A-Za-z0-9_-]+/,
  );

  // The advertised first frame must actually render — the daemon drew a still and the
  // response carried it as a data:image/png URI. Asserting it (rather than treating the
  // image as best-effort) is what proves the compile→daemon→PNG path really ran, not
  // just that a token was minted; a silent render failure is otherwise invisible here.
  const image = page.locator("#pg-image");
  await expect(image, "first-frame image is shown").toBeVisible();
  const src = await image.getAttribute("src");
  expect(
    src ?? "",
    "first-frame src is an inline PNG (empty ⇒ the daemon render produced no frame; see serve log)",
  ).toMatch(/^data:image\/png/);
});

test("a multi-file snippet compiles as one module and names the preview it drew", async ({
  page,
}) => {
  requirePlayground();
  await page.goto(`/playground${q}`, { waitUntil: "domcontentloaded" });
  await page.selectOption("#pg-mode", ANDROID_MODE);

  // Split the snippet across two files, with the second declaring what the first uses. A
  // cross-file reference is the whole point: the files reach ONE compile, so this only
  // resolves if the server staged both into the same module (#3017).
  await page.click("#pg-add-file");
  await setSource(
    page,
    [
      "import androidx.compose.ui.graphics.Color",
      "",
      "val Brand = Color(0xFF6750A4)",
    ].join("\n"),
  );
  await expect(
    page.locator("[data-pg-file]"),
    "one tab per open file",
  ).toHaveCount(2);

  await page.click('[data-pg-file="Snippet.kt"]');
  await setSource(
    page,
    [
      "import androidx.compose.material3.Text",
      "import androidx.compose.runtime.Composable",
      "import androidx.compose.ui.tooling.preview.Preview",
      "",
      "@Preview",
      "@Composable",
      "fun Greeting() {",
      '    Text("Hello", color = Brand)',
      "}",
      "",
      "@Preview",
      "@Composable",
      "fun Second() {",
      '    Text("Second")',
      "}",
    ].join("\n"),
  );

  const terminal = await runAndAwaitTerminal(page);
  expect(
    terminal,
    `multi-file run should compile, got "${terminal}" ` +
      `(diagnostics: ${await page.locator("#pg-diagnostics").textContent()})`,
  ).toBe("Done.");

  // Two @Previews in the snippet: exactly one is rendered and tokenized, and the editor says
  // which — otherwise the choice is invisible.
  const note = page.locator("#pg-preview-note");
  await expect(note, "preview note for a multi-preview snippet").toBeVisible();
  expect(await note.textContent()).toMatch(/2 previews found/);
});

test("the /pg/ token redeems into the live viewer", async ({ page }) => {
  requirePlayground();
  await page.goto(`/playground${q}`, { waitUntil: "domcontentloaded" });
  await page.selectOption("#pg-mode", ANDROID_MODE);
  const terminal = await runAndAwaitTerminal(page);
  // A non-Done terminal in CI is a real regression, not a reason to skip: the boot
  // guarantees a compilable lane, so failing here (rather than green-skipping) keeps the
  // redemption chain actually covered.
  expect(
    terminal,
    `compile did not succeed (status "${terminal}") — nothing to redeem`,
  ).toBe("Done.");

  const href = await page.locator("#pg-open").getAttribute("href");

  // Hit the /pg/ capability RAW (no redirect-follow) first. Redemption must 302 to the
  // viewer at /<sessionId>/p/<previewId>; a NotFound/Unavailable serves inline HTML with NO
  // redirect. One assertion on the Location header, whose failure message carries the status
  // AND the body — the body text ("expired, or never existed" vs "Live preview isn't
  // available") says whether it was NotFound or Unavailable.
  const raw = await page.request.get(href, { maxRedirects: 0 });
  const location = raw.headers()["location"] ?? "";
  const body =
    raw.status() >= 300 && raw.status() < 400
      ? ""
      : (await raw.text()).slice(0, 300);
  expect(
    location,
    `/pg/ must 302 to the viewer /p/ route; got status ${raw.status()} ` +
      `location="${location}" body="${body}"`,
  ).toMatch(/\/p\//);

  // And the viewer actually loads (follow the redirect in a real page). The live frame
  // itself is the /ws/ lane's job (proven by the serve-lanes suite); here we only assert
  // redemption reached the real viewer shell, not an error page.
  await page.goto(href, { waitUntil: "domcontentloaded" });
  await expect
    .poll(() => new URL(page.url()).pathname, { timeout: 30_000 })
    .toMatch(/\/p\//);
  await expect(page.locator("#cp-img"), "viewer stage").toBeAttached();
});
