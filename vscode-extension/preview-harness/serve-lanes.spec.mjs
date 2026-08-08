// End-to-end proof that every render lane of a live, daemon-backed
// `compose-preview serve` really honors a named-knob override — not just that the
// viewer HTML contains the right controls (that's ServeWebFixtureTest's job), but
// that flipping a knob actually changes the pixels/bytes each backend produces.
//
// Lanes covered for a preview that declares a `label` string knob:
//   - PNG  : GET /render/<id>.png  vs  ?knob.label=<X>  → both 200, bytes differ
//            (the daemon re-rendered).
//   - SVG  : GET /render/<id>.svg?knob.label=<X>        → 200 image/svg+xml whose
//            body literally contains <X> (the override text is in the vector).
//   - Live : open the /ws/<id>?knob.label=<X> WebSocket  → upgrades (101) and
//            pushes a frame.
//   - Wasm : the in-browser CMP iframe re-renders when the knob changes (stage
//            pixels differ before/after).
// Plus a viewer drive: switching the PNG/SVG/Live/Wasm mode radios and editing the
// knob repoints the render URLs / stream the way the backend lane expects.
//
// Requires a running serve; point SERVE_URL at it (SERVE_SYSTEM defaults to
// compose-m3). The CI job boots one daemon-backed
// (`serve --catalogs compose-m3 --allow-render-trusted --trust-store …` under
// xvfb). Self-skips with a clear message when no label-knob preview is reachable.

import { test, expect } from "@playwright/test";
import { readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const HERE = dirname(fileURLToPath(import.meta.url));
const SYSTEM = process.env.SERVE_SYSTEM ?? "compose-m3";
const OVERRIDE = "E2eKnobProof";

// Resolved in beforeAll: a preview id that declares a `label` string knob.
let previewId = null;

test.beforeAll(async ({ request }) => {
  const res = await request.get(`/${SYSTEM}/api/previews`);
  if (!res.ok()) return; // leaves previewId null → every test self-skips
  const body = await res.json();
  const previews = Array.isArray(body) ? body : (body.previews ?? []);
  const hit = previews.find((p) =>
    (p.overrides ?? []).some(
      (o) => o.key === "label" && (o.kind ?? "string") === "string",
    ),
  );
  previewId = hit?.id ?? null;
});

// The declared knobs now live in a collapsed-by-default "Overrides"
// `<details class="cp-group">` section (the viewer remembers each section's
// open/closed state in localStorage, defaulting everything except Export to
// closed). Expand it before touching a `.cp-knob`, since Playwright refuses to
// fill a control inside a closed <details> ("element is not visible").
async function openOverridesGroup(page) {
  await page
    .locator('details.cp-group[data-cp-group="overrides"]')
    .evaluate((d) => {
      d.open = true;
    });
}

function requirePreview() {
  // In CI the boot step starts a known compose-m3 daemon serve, so a missing
  // label-knob preview is a real regression — FAIL, don't green-skip the required
  // suite. The skip path is only for a local run without a suitable serve target.
  if (!previewId && process.env.CI) {
    throw new Error(
      `no label-knob preview at /${SYSTEM}/api/previews — the daemon-backed serve isn't exposing the expected catalog; refusing to green-skip the Serve Lanes suite`,
    );
  }
  test.skip(
    !previewId,
    `no label-knob preview reachable at /${SYSTEM}/api/previews — is a daemon-backed serve running at ${process.env.SERVE_URL}?`,
  );
}

test("PNG lane re-renders on knob override", async ({ request }) => {
  requirePreview();
  const base = await request.get(`/${SYSTEM}/render/${previewId}.png`);
  const over = await request.get(
    `/${SYSTEM}/render/${previewId}.png?knob.label=${OVERRIDE}`,
  );
  expect(base.status(), "baseline PNG").toBe(200);
  expect(over.status(), "override PNG").toBe(200);
  const baseBuf = await base.body();
  const overBuf = await over.body();
  expect(baseBuf.length, "baseline PNG is non-empty").toBeGreaterThan(0);
  // A real re-render with a different label produces different bytes.
  expect(
    Buffer.compare(baseBuf, overBuf) !== 0,
    "override PNG bytes should differ from baseline (daemon re-rendered)",
  ).toBeTruthy();
  // #3449: the honest-render side of the same contract. These pixels DID come from
  // a daemon, so the response must carry no dropped-override signal — a refusal
  // here would mean the live lane silently stopped being used.
  expect(
    over.headers()["x-compose-preview-dropped-overrides"],
    "a real override render drops nothing",
  ).toBeUndefined();
});

test("SVG lane bakes the override text into the vector", async ({
  request,
}) => {
  requirePreview();
  const res = await request.get(
    `/${SYSTEM}/render/${previewId}.svg?knob.label=${OVERRIDE}`,
  );
  expect(res.status(), "override SVG status").toBe(200);
  expect(res.headers()["content-type"] ?? "").toContain("image/svg");
  const svg = await res.text();
  // The overridden label must appear as text in the SVG body — this is the lane
  // that regressed when the bundle daemon didn't register compose/figma-svg.
  expect(svg, "SVG body should contain the override text").toContain(OVERRIDE);
  // #3449: this vector was really rendered with the override, so the response must
  // carry no dropped-override signal. A refusal here would mean the lane had
  // silently dropped back to the catalog's baked `figma/<slug>.svg`.
  expect(
    res.headers()["x-compose-preview-dropped-overrides"],
    "a real override SVG export drops nothing",
  ).toBeUndefined();
});

test("Live WebSocket lane upgrades and pushes a frame", async ({ page }) => {
  requirePreview();
  // Same-origin: land on the viewer first so the WS is same-origin as the app.
  await page.goto(`/${SYSTEM}/p/${previewId}`, {
    waitUntil: "domcontentloaded",
  });
  const result = await page.evaluate(
    ({ system, id, override }) =>
      new Promise((resolve) => {
        const proto = location.protocol === "https:" ? "wss:" : "ws:";
        const url = `${proto}//${location.host}/${system}/ws/${encodeURIComponent(id)}?knob.label=${override}&codec=webp`;
        let ws;
        try {
          ws = new WebSocket(url);
        } catch (e) {
          resolve({ outcome: "ctor-error", error: String(e) });
          return;
        }
        let opened = false;
        const done = (r) => {
          try {
            ws.close();
          } catch {}
          resolve(r);
        };
        const t = setTimeout(() => done({ outcome: "timeout", opened }), 30000);
        ws.onopen = () => {
          opened = true;
        };
        ws.onmessage = (m) => {
          const bytes = m.data?.byteLength ?? m.data?.length ?? 0;
          clearTimeout(t);
          done({ outcome: "frame", opened, bytes });
        };
        ws.onclose = (e) => {
          clearTimeout(t);
          done({
            outcome: "closed",
            opened,
            code: e.code,
            reason: String(e.reason),
          });
        };
      }),
    { system: SYSTEM, id: previewId, override: OVERRIDE },
  );
  expect(
    result.opened,
    `WS should upgrade (got ${JSON.stringify(result)})`,
  ).toBeTruthy();
  expect(
    result.outcome,
    `WS should deliver a frame (got ${JSON.stringify(result)})`,
  ).toBe("frame");
  expect(result.bytes, "WS frame is non-empty").toBeGreaterThan(0);
});

test("viewer wires the knob into every render lane", async ({ page }) => {
  requirePreview();
  await page.goto(`/${SYSTEM}/p/${previewId}`, {
    waitUntil: "domcontentloaded",
  });

  await openOverridesGroup(page);
  const knob = page.locator('.cp-knob[data-knob-key="label"]');
  await expect(knob, "label knob control exists").toBeVisible();
  await knob.fill(OVERRIDE);
  await knob.dispatchEvent("input");
  await knob.dispatchEvent("change");

  // The copyable direct-link fields track the current knobs regardless of the
  // active mode — the clearest signal the viewer folded the override into the
  // render URLs the PNG and SVG lanes are hit with.
  await expect(page.locator("#cp-url-png")).toHaveValue(
    new RegExp(`knob\\.label=${OVERRIDE}`),
  );
  await expect(page.locator("#cp-url-svg")).toHaveValue(
    new RegExp(`knob\\.label=${OVERRIDE}`),
  );

  // PNG mode (default): the on-screen <img> was painted from the override PNG, and loads.
  //
  // Assert on `data-cp-src`, not `src`. The viewer fetches the render bytes once and hands the
  // <img> an object URL (so an override-bearing `no-store` render isn't performed twice and raced
  // through the daemon's shared override state), which makes `src` an opaque `blob:` with nothing
  // to match on. `data-cp-src` carries the /render URL those bytes actually came from and is set
  // in the same `onload` that swaps the frame in, so it still proves the *displayed* pixels are
  // the override's — which the `#cp-url-*` fields above cannot, since they track the live control
  // state whether or not a render followed.
  await expect(page.locator("#cp-img")).toHaveAttribute(
    "data-cp-src",
    new RegExp(`\\.png.*knob\\.label=${OVERRIDE}`),
  );
  // …and `src` is still the object URL. With the override assertion moved off `src`, nothing else
  // pins that the blob swap is happening at all: a regression to assigning the render URL straight
  // to `img.src` would keep `data-cp-src` matching and leave this suite green, while quietly
  // reinstating the double-fetch of an override-bearing `no-store` render that the swap exists to
  // prevent.
  await expect(page.locator("#cp-img")).toHaveAttribute("src", /^blob:/);
  await expect
    .poll(() => page.locator("#cp-img").evaluate((im) => im.naturalWidth), {
      timeout: 30000,
    })
    .toBeGreaterThan(0);

  // SVG format toggle: the same <img> repaints from the override SVG and loads.
  await page.click("#cp-svg-toggle");
  await expect(page.locator("#cp-img")).toHaveAttribute(
    "data-cp-src",
    new RegExp(`\\.svg.*knob\\.label=${OVERRIDE}`),
  );
  await expect
    .poll(() => page.locator("#cp-img").evaluate((im) => im.naturalWidth), {
      timeout: 30000,
    })
    .toBeGreaterThan(0);
  // Toggle SVG back off before exercising the live lane.
  await page.click("#cp-svg-toggle");

  // Live mode: flipping the single Static⇄Live toggle must actually open the stream. A
  // failed activation now routes through #cp-error (and CLEARS #cp-status), so checking
  // status alone would pass on a silent failure — assert the error overlay stays hidden
  // instead, which is only true once a frame has painted (the WS-frame test proves the
  // frame itself).
  await page.click("#cp-live-toggle");
  await page.waitForTimeout(6000);
  await expect(
    page.locator("#cp-error"),
    "live stream should open, not surface an activation error",
  ).toBeHidden();
});

test("Live mode surfaces a visible error when the stream can't activate", async ({
  page,
}) => {
  requirePreview();
  // Reproduce the failed-activation symptom (the coo.ee 502 left a stale frame with only tiny
  // "stream error" text): intercept the /ws/ upgrade so it closes without ever delivering a frame.
  await page.routeWebSocket(/\/ws\//, (ws) => ws.close());

  await page.goto(`/${SYSTEM}/p/${previewId}`, {
    waitUntil: "domcontentloaded",
  });
  await expect(
    page.locator("#cp-error"),
    "no error before switching modes",
  ).toBeHidden();

  await page.click("#cp-live-toggle");
  // The viewer must raise a VISIBLE error overlay (not leave a stale snapshot masquerading as live).
  await expect(page.locator("#cp-error")).toBeVisible();
  await expect(page.locator("#cp-error")).toContainText(/live preview/i);
  // And the stale seeded canvas must not be left painted over the stage as a fake render.
  await expect(page.locator("#cp-canvas")).toBeHidden();

  // Toggling back to static (Live off) clears the error.
  await page.click("#cp-live-toggle");
  await expect(page.locator("#cp-error")).toBeHidden();
});

test("Wasm iframe re-renders on knob override", async ({ page }) => {
  requirePreview();
  await page.goto(`/${SYSTEM}/p/${previewId}`, {
    waitUntil: "domcontentloaded",
  });

  const wasmToggle = page.locator("#cp-wasm-toggle");
  test.skip((await wasmToggle.count()) === 0, "no Wasm tier for this session");

  await openOverridesGroup(page);
  const knob = page.locator('.cp-knob[data-knob-key="label"]');
  await knob.fill("WasmBefore");
  await knob.dispatchEvent("input");
  await knob.dispatchEvent("change");

  // The wasm transport radio is hidden behind the single Static⇄Live toggle (which, on a
  // daemon-backed catalog, prefers the stream). Drive the wasm lane directly to exercise the
  // in-browser tier specifically: tick its radio and fire change so the transition JS enters it.
  await wasmToggle.evaluate((el) => {
    el.checked = true;
    el.dispatchEvent(new Event("change", { bubbles: true }));
  });
  const frame = page.locator("#cp-wasm");
  await expect(frame).toBeVisible();
  await expect(frame).toHaveClass(/cp-wasm-live/, { timeout: 30000 });

  const before = await frame.screenshot();

  await knob.fill("WasmAfter");
  await knob.dispatchEvent("input");
  await knob.dispatchEvent("change");

  await expect
    .poll(async () => Buffer.compare(before, await frame.screenshot()) !== 0, {
      message: "Wasm stage pixels should change after the knob override",
      timeout: 30000,
    })
    .toBeTruthy();
});

// The client-side RC lane draws a document's *generic* families through the concrete faces Android
// resolves them to (`Roboto, sans-serif`, …). That is only a request: on a page that registered no
// such faces the browser falls through to whatever the visitor's own machine calls `sans-serif`, so
// the same document renders in a different typeface, at ~4% different line metrics and without the
// Medium weight — while the PNG lane beside it used the vendored files (issue #3480). The server now
// serves those files (`/rc-fonts/…`) and the matching `@font-face` block.
//
// Driven through the **document lane** rather than a catalog preview: it needs only a `.rc` file, so
// it runs on every serve this suite boots instead of skipping wherever the served catalog happens to
// carry no `ir/` sidecars. The claim needs a real browser and a real server — `@font-face` is lazy,
// canvas neither drives a load nor repaints when one lands, and no HTML fixture can show either.
test("a Remote Compose document plays in the vendored typefaces, not the visitor's", async ({
  browser,
  request,
}) => {
  const sheet = await request.get("/rc-fonts/fonts.css");
  expect(sheet.ok(), "the server publishes the vendored @font-face block").toBeTruthy();
  const css = await sheet.text();
  for (const family of ["Roboto", "Noto Serif", "Droid Sans Mono"]) {
    expect(css, `declares ${family}`).toContain(`font-family:"${family}"`);
  }
  // The contiguous ranges are what stop an in-between weight (Wear M3 asks for 450) resolving upward
  // onto Medium, and what gives a Medium request a real file at all.
  expect(css, "Roboto Medium serves 500 and up").toContain("font-weight:500 1000");

  const doc = readFileSync(resolve(HERE, "../../scripts/design-artifacts/fixtures/watch-screen-round-clip.rc"));
  const upload = await request.post("/docs", {
    headers: { "content-type": "application/octet-stream" },
    data: doc,
  });
  test.skip(upload.status() === 404, "this serve was booted without the document lane (--accept-docs)");
  expect(upload.status(), await upload.text()).toBe(201);
  const docUrl = (await upload.json()).url;

  // Two loads of the same page: as served, and with `/rc-fonts/**` blocked — which is exactly this
  // page before the faces were served at all.
  async function play(blockFonts) {
    const ctx = await browser.newContext({ deviceScaleFactor: 2 });
    const page = await ctx.newPage();
    if (blockFonts) await page.route("**/rc-fonts/**", (route) => route.abort());
    await page.goto(docUrl, { waitUntil: "domcontentloaded" });
    // The page clears its status line once the player has painted a frame.
    await expect(page.locator("#cp-doc-status")).toHaveText("", { timeout: 60000 });
    const probe = await page.evaluate(() => {
      const loaded = [];
      document.fonts.forEach((f) => {
        if (f.status === "loaded") loaded.push(f.family);
      });
      const ctx2d = document.createElement("canvas").getContext("2d");
      // The stack the player itself asks for, measured the way it draws.
      ctx2d.font = "100px Roboto, sans-serif";
      const m = ctx2d.measureText("Hamburgefonstiv");
      return {
        loaded,
        lineBox: Math.round(m.fontBoundingBoxAscent + m.fontBoundingBoxDescent),
        width: Math.round(m.width),
      };
    });
    const shot = await page.locator("#cp-doc-mount").screenshot();
    await ctx.close();
    return { ...probe, shot };
  }

  const served = await play(false);
  const unregistered = await play(true);

  // Both Roboto faces must be *loaded* before the lane paints: a declared-but-unloaded face is
  // precisely the state canvas silently substitutes for, and 500 is the one no fallback stack has a
  // file for.
  expect(
    served.loaded.filter((f) => f === "Roboto").length,
    `both Roboto faces should be loaded, saw ${JSON.stringify(served.loaded)}`,
  ).toBeGreaterThanOrEqual(2);
  // Roboto's own metrics, not the host's generic — the residual no layout work can close.
  expect(served.lineBox, "the request resolved to the vendored face's metrics").not.toBe(
    unregistered.lineBox,
  );
  expect(
    Buffer.compare(served.shot, unregistered.shot) !== 0,
    "the painted document should differ from the unregistered-fallback rendering",
  ).toBeTruthy();
});

// ——— Address-bar state ————————————————————————————————————————————————————————————————————
//
// What a visitor picks on a catalog page — the section tab, the theme, the filter — is reflected
// into the URL, so the page on screen is the page its URL describes: bookmarkable, shareable, and
// reachable with Back. These drive the real server (not a fixture) because the claim is a
// *navigation* one: the URL has to change, the page must NOT reload, and Back has to restore the
// previous selection in place.
test("catalog selections land in the URL and Back restores them without reloading", async ({
  page,
}) => {
  await page.goto(`/${SYSTEM}/`, { waitUntil: "domcontentloaded" });
  // A reload would re-run this, so it doubles as the no-reload probe below.
  await page.evaluate(() => {
    window.__cpNavigations = (window.__cpNavigations ?? 0) + 1;
  });

  const themeChips = page.locator(".cp-theme-btn");
  test.skip(
    (await themeChips.count()) < 2,
    "catalog offers no theme control to pick from",
  );
  const chip = themeChips.nth(1);
  const chosen = await chip.getAttribute("data-theme-choice");
  await chip.click();
  await expect
    .poll(() =>
      page.evaluate(() => new URLSearchParams(location.search).get("theme")),
    )
    .toBe(chosen);

  // A tab, when the catalog authored sections — the "select Components, then a theme, and the URL
  // takes you back there" flow.
  const tabs = page.locator(".cp-tab");
  if (await tabs.count()) {
    const tab = tabs.nth(1);
    const slug = await tab.getAttribute("data-tab");
    await tab.click();
    await expect
      .poll(() =>
        page.evaluate(() => new URLSearchParams(location.search).get("tab")),
      )
      .toBe(slug);
    await expect(tab).toHaveAttribute("aria-selected", "true");
  }

  // Filtering replaces rather than pushes, so it must NOT cost a history entry: one Back from
  // here returns to the theme pick, not to a half-typed query.
  await page.fill("#cp-search", "a");
  await expect
    .poll(() =>
      page.evaluate(() => new URLSearchParams(location.search).get("q")),
    )
    .toBe("a");

  await page.goBack();
  await expect
    .poll(() =>
      page.evaluate(() => new URLSearchParams(location.search).get("q")),
    )
    .toBeNull();
  await expect(page.locator("#cp-search")).toHaveValue("");
  // Still the same document — the whole point is that Back re-points the grid rather than
  // re-fetching the catalog page.
  expect(await page.evaluate(() => window.__cpNavigations)).toBe(1);
});

test("a bookmarked catalog URL opens on the theme and tab it names", async ({
  page,
}) => {
  await page.goto(`/${SYSTEM}/`, { waitUntil: "domcontentloaded" });
  const chips = page.locator(".cp-theme-btn");
  test.skip(
    (await chips.count()) < 2,
    "catalog offers no theme control to pick from",
  );
  const chosen = await chips.nth(1).getAttribute("data-theme-choice");
  const tabs = page.locator(".cp-tab");
  const slug = (await tabs.count())
    ? await tabs.nth(1).getAttribute("data-tab")
    : null;

  const query = new URLSearchParams({ theme: chosen });
  if (slug) query.set("tab", slug);
  await page.goto(`/${SYSTEM}/?${query}`, { waitUntil: "domcontentloaded" });

  await expect(
    page.locator(`.cp-theme-btn[data-theme-choice="${chosen}"]`),
    "the bookmarked theme chip is the pressed one",
  ).toHaveAttribute("aria-pressed", "true");
  if (slug) {
    await expect(page.locator(`.cp-tab[data-tab="${slug}"]`)).toHaveAttribute(
      "aria-selected",
      "true",
    );
  }
});

test("viewer overrides ride the page URL and survive Back", async ({
  page,
}) => {
  requirePreview();
  await page.goto(`/${SYSTEM}/p/${previewId}`, {
    waitUntil: "domcontentloaded",
  });
  await openOverridesGroup(page);

  const knob = page.locator('.cp-knob[data-knob-key="label"]');
  await knob.fill(OVERRIDE);
  await knob.dispatchEvent("input");
  await expect
    .poll(() =>
      page.evaluate(() =>
        new URLSearchParams(location.search).get("knob.label"),
      ),
    )
    .toBe(OVERRIDE);

  // Reloading that URL re-opens on the override — a bookmark, not just a live control state.
  await page.reload({ waitUntil: "domcontentloaded" });
  await openOverridesGroup(page);
  await expect(page.locator('.cp-knob[data-knob-key="label"]')).toHaveValue(
    OVERRIDE,
  );
});

// ---------------------------------------------------------------------------
// Live-lane text input (issue #3491).
//
// The Live lane forwards the visitor's real keyboard and mouse into a held
// composition running in the render daemon. Two things about a text field are
// easy to get silently wrong there, and were:
//
//   - typing needs the *character* on the wire, not just the physical keycode.
//     Caret keys and Backspace map off the keycode alone, so the lane could look
//     responsive — caret moves, Backspace deletes — while no character ever
//     appeared;
//   - selecting needs the pointer's *device class*. Compose only drags out a text
//     selection for a mouse; a drag forwarded as touch is a gesture and leaves the
//     selection alone.
//
// Both are asserted the only way that can't be faked from the client side: the
// streamed frame's own pixels have to change. The daemon-side dispatch is unit
// covered (`DesktopTextInputSessionTest`, `AndroidInteractiveSessionTest`); these
// prove the browser half sends what those need.

/** A preview id that renders a text field, or null when the catalog has none. */
let textFieldPreviewId = null;

test.beforeAll(async ({ request }) => {
  const res = await request.get(`/${SYSTEM}/api/previews`);
  if (!res.ok()) return;
  const body = await res.json();
  const previews = Array.isArray(body) ? body : (body.previews ?? []);
  textFieldPreviewId =
    previews.find((p) => /^textfield-/.test(p.id ?? ""))?.id ?? null;
});

/**
 * The one catalog that is expected to carry a text field. The suite also runs against the Android
 * lane's `androidlane` system, whose bundle is a single-card fixture with no text field at all —
 * demanding one there fails a job for serving exactly what it was built to serve.
 */
const TEXT_FIELD_SYSTEM = "compose-m3";

function requireTextField() {
  if (!textFieldPreviewId && process.env.CI && SYSTEM === TEXT_FIELD_SYSTEM) {
    throw new Error(
      `no textfield-* preview at /${SYSTEM}/api/previews — refusing to green-skip the live text-input suite`,
    );
  }
  test.skip(
    !textFieldPreviewId,
    `no textfield-* preview reachable at /${SYSTEM}/api/previews`,
  );
}

/** Open the viewer on the text-field preview in Live mode with a painted frame. */
async function openLiveTextField(page) {
  await page.goto(`/${SYSTEM}/p/${textFieldPreviewId}`, {
    waitUntil: "domcontentloaded",
  });
  await page.click("#cp-live-toggle");
  // The canvas only carries a buffer once a frame has painted; input forwarding
  // is gated on exactly that (`liveActive()`), so waiting for it is also waiting
  // for the lane to be able to accept input at all.
  await expect
    .poll(() => page.locator("#cp-canvas").evaluate((c) => c.width), {
      timeout: 60000,
    })
    .toBeGreaterThan(0);
  await expect(
    page.locator("#cp-error"),
    "live stream should open cleanly before driving input",
  ).toBeHidden();
  await page.waitForTimeout(1500);
}

/** The live canvas's current pixels, as a flat RGBA array. */
function liveFrame(page) {
  return page.locator("#cp-canvas").evaluate((c) => {
    const ctx = c.getContext("2d");
    return Array.from(ctx.getImageData(0, 0, c.width, c.height).data);
  });
}

/** Percentage of pixels whose colour differs between two [liveFrame] snapshots. */
function frameDiffPct(a, b) {
  if (!a.length || a.length !== b.length) return 100;
  let changed = 0;
  for (let i = 0; i < a.length; i += 4) {
    if (a[i] !== b[i] || a[i + 1] !== b[i + 1] || a[i + 2] !== b[i + 2])
      changed++;
  }
  return (changed / (a.length / 4)) * 100;
}

/**
 * Floor for "the composition visibly reacted", in percent of the frame's pixels.
 *
 * A bare "the frame changed at all" assertion would pass on nothing: a focused
 * field blinks its caret, and a click that only *moves* the caret redraws it
 * somewhere else. Measured against this lane, with a text field focused:
 *
 *   caret blink alone .................. 0.07%
 *   caret moved, nothing typed ......... 0.13%
 *   two characters typed ............... 0.99%
 *   a drag-selection highlight ......... 0.67%
 *
 * 0.4% sits in the gap — comfortably above anything the caret can do by itself,
 * comfortably below the smallest real edit. Without it, the selection test in
 * particular would have gone green against the very bug it exists to catch.
 */
const REACTED_DIFF_PCT = 0.4;

/** Centre of the live canvas in page coordinates. */
async function liveCanvasBox(page) {
  return await page.locator("#cp-canvas").boundingBox();
}

test("Live lane types the visitor's keystrokes into the field", async ({
  page,
}) => {
  requireTextField();
  await openLiveTextField(page);

  const box = await liveCanvasBox(page);
  // Click into the field to place the caret and focus the canvas.
  await page.mouse.click(box.x + box.width / 2, box.y + box.height / 2);
  await page.waitForTimeout(2000);
  const before = await liveFrame(page);

  await page.keyboard.type("Zx", { delay: 400 });
  await expect
    .poll(async () => frameDiffPct(before, await liveFrame(page)), {
      timeout: 30000,
      message:
        "typed characters must appear in the streamed frame — the keycode alone " +
        "cannot type, the character has to ride the wire",
    })
    .toBeGreaterThan(REACTED_DIFF_PCT);
});

test("Live lane selects text on a mouse drag", async ({ page }) => {
  requireTextField();
  await openLiveTextField(page);

  const box = await liveCanvasBox(page);
  const y = box.y + box.height / 2;
  await page.mouse.click(box.x + box.width / 2, y);
  await page.waitForTimeout(2000);
  const before = await liveFrame(page);

  // Press and drag across the field's text. The press is deliberately separate
  // from the moves: the viewer defers the pointerDown until the first move so a
  // plain tap stays a click, and it is the move that promotes the gesture to a
  // drag.
  await page.mouse.move(box.x + box.width * 0.18, y);
  await page.mouse.down();
  for (let i = 1; i <= 12; i++) {
    await page.mouse.move(box.x + box.width * (0.18 + 0.02 * i), y);
    await page.waitForTimeout(80);
  }
  await page.mouse.up();

  // A selection paints a highlight behind the selected glyphs. Dispatched as
  // touch — which is what this lane used to do with every pointer — Compose
  // treats the same drag as a gesture and paints no highlight at all; the frame
  // then only differs by the caret the press moved, well under the floor.
  await expect
    .poll(async () => frameDiffPct(before, await liveFrame(page)), {
      timeout: 30000,
      message:
        "a mouse drag must paint a selection highlight — a pointer forwarded as " +
        "touch never starts one",
    })
    .toBeGreaterThan(REACTED_DIFF_PCT);
});
