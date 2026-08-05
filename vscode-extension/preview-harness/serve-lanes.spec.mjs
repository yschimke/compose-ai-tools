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
    .poll(
      async () => Buffer.compare(before, await frame.screenshot()) !== 0,
      {
        message: "Wasm stage pixels should change after the knob override",
        timeout: 30000,
      },
    )
    .toBeTruthy();
});
