// Headless capture of *standalone HTML page* fixtures — currently the
// `compose-preview serve` web surfaces (`ServeWeb.landingPage` /
// `viewerPage`), committed under `fixtures/pages/*.html` by the Kotlin
// `ServeWebFixtureTest`.
//
// This reuses the panel harness's *approach* (Playwright + the `_server.mjs`
// static server + per-`(fixture × theme)` PNGs in `out/`, diffed by the
// generic `vscode-preview-diff.py` bot) without its *invocation*: these
// pages are self-contained documents, so we navigate to them directly and
// drive theme via `prefers-color-scheme` emulation, instead of booting
// `scenario.html` and replaying webview messages.
//
// Output filenames match the rest of the harness (`<fixture>.<theme>.png`)
// and land in the same `out/` dir, so they're picked up by the existing
// `harness:snapshot` invocation (the `snapshot` path filter matches this
// file too) and the existing baseline/PR-comment actions — no CI change.

import { test, expect } from "@playwright/test";
import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";
import { readdirSync } from "node:fs";
import { listThemes } from "./_fixtures.mjs";

const harnessDir = dirname(fileURLToPath(import.meta.url));
const outDir = resolve(harnessDir, "out");
const pagesDir = resolve(harnessDir, "fixtures", "pages");
const serveAssetsDir = resolve(
    harnessDir,
    "..",
    "..",
    "cli",
    "src",
    "main",
    "resources",
    "ee",
    "schimke",
    "composeai",
    "cli",
    "serve",
    "assets",
);

// The serve pages point `<img>` / their viewer JS at two image lanes with no
// backend in the harness: the daemon's `/render/<id>.png` endpoint, and the
// front door's prebaked `/hero/<system>/<hash>.png` thumbnails. Stub both with
// the committed placeholder so the capture is deterministic and tiles render at
// a realistic size instead of collapsing on broken images.
const renderPlaceholder = resolve(pagesDir, "_render-placeholder.png");
const renderSvgPlaceholder = resolve(pagesDir, "_render-placeholder.svg");
const IMAGE_LANES = ["**/render/**", "**/hero/**", "**/reference/**"];
const REFERENCE_PLACEHOLDER = `
<svg xmlns="http://www.w3.org/2000/svg" width="200" height="420" viewBox="0 0 200 420">
  <rect width="200" height="420" rx="20" fill="#f4efff"/>
  <rect x="20" y="28" width="160" height="42" rx="12" fill="#7657b5"/>
  <rect x="20" y="92" width="116" height="18" rx="9" fill="#5f5968"/>
  <rect x="20" y="132" width="160" height="104" rx="18" fill="#dfd2f5"/>
  <rect x="20" y="258" width="160" height="64" rx="18" fill="#ffffff"/>
  <circle cx="100" cy="378" r="18" fill="#7657b5"/>
</svg>`;
// Fixtures captured with the REAL production CSS/JS routed in, rather than the older static-page
// contract (bare HTML). Anything whose whole point is how the page is *painted* has to be here —
// the catalog-palette pair exists to show a served design system re-theming the chrome from its own
// `tokens.dtcg.json`, which is invisible without the stylesheet the palette overrides.
const STYLED_FIXTURES = new Set([
    "serve-format-compare",
    "serve-reference-compare",
    "serve-viewer-catalog-knobs",
    "serve-landing-catalog-palette",
    "serve-viewer-catalog-palette",
    // The render-server badge is a `.cp-daemon-status` pill whose whole diffable claim is its
    // STYLING — that "not running" reads as neutral information rather than a fault. Without the
    // real stylesheet routed in, `/assets/serve/.../serve.css` 404s and the daemon captures shoot
    // an unstyled span, so a change to that styling would move no baseline at all.
    "serve-landing-declared-themes",
]);
const SERVE_ASSETS = [
    ["serve.css", "text/css"],
    ["viewer.js", "text/javascript"],
    ["viewer-groups.js", "text/javascript"],
    ["viewer-drawers.js", "text/javascript"],
    ["backend-badge.js", "text/javascript"],
    ["format-compare.js", "text/javascript"],
];

// Runtime *states* of a page fixture that the committed HTML can't express on its own, captured as
// extra shots so they're diffed on every PR like any other fixture. Each entry names a base
// fixture, a filename suffix, and a mutation applied in-page just before the screenshot.
//
// `connecting` covers the viewer's live-lane activation badge: `openStream()` sets `data-pending`
// on `.cp-viewer` while the WebSocket comes up and the backend badge shows `◌ connecting…` (amber)
// instead of the lane label. There's no daemon in the harness, so we set the attribute the same way
// the viewer JS does and let the page's own `MutationObserver` paint the badge — the badge text and
// accent under test are produced by the real `backendBadgeScript`, not faked here.
const FIXTURE_STATES = [
    {
        // The playground editor's multi-file strip (#3017): a snippet is a list of files compiled
        // as one module, and the second file only exists after a click — the committed HTML always
        // opens on one buffer. Clicking "+ file" here means every future PR diffs the multi-file
        // editor (tab strip, active-tab styling, the Remove button appearing) for free.
        fixture: "serve-playground",
        suffix: "multifile",
        apply: async (page) => {
            await page.click("#pg-add-file");
            await page.fill("#pg-source", "val Brand = 0xFF6750A4");
        },
    },
    {
        fixture: "serve-viewer",
        suffix: "connecting",
        apply: async (page) => {
            await page.evaluate(() => {
                const root = document.querySelector(".cp-viewer");
                root.setAttribute("data-mode", "live");
                root.setAttribute("data-pending", "connecting…");
            });
        },
    },
    {
        // The render-server badge (#3274). Catalogs open their daemon on first use, so whether one
        // is up is a real question the page now answers — and "connected" is the state a visitor
        // sees while anything is warm. Stubbed rather than faked: the pill's text and styling come
        // from the real `presenceScript`, this only supplies the JSON it polls for.
        fixture: "serve-landing-declared-themes",
        suffix: "daemon-connected",
        apply: async (page) => {
            await page.route("**/api/daemons*", (route) =>
                route.fulfill({
                    status: 200,
                    contentType: "application/json",
                    body: JSON.stringify({
                        running: true,
                        instances: 2,
                        pooled: 1,
                        poolCapacity: 8,
                        activeStreams: 0,
                    }),
                }),
            );
            await page.evaluate(() => document.dispatchEvent(new Event("visibilitychange")));
            await page.waitForFunction(
                () =>
                    document
                        .getElementById("cp-daemon-status")
                        ?.getAttribute("data-cp-daemon-running") === "1",
            );
        },
    },
    {
        // The resting state, and the one most catalogs are in: registered, nobody has rendered on
        // it, so no process exists. It must read as neutral information rather than a fault, which
        // is a styling claim only a screenshot can hold honest.
        fixture: "serve-landing-declared-themes",
        suffix: "daemon-idle",
        apply: async (page) => {
            await page.route("**/api/daemons*", (route) =>
                route.fulfill({
                    status: 200,
                    contentType: "application/json",
                    body: JSON.stringify({
                        running: false,
                        instances: 0,
                        pooled: 0,
                        poolCapacity: 8,
                        activeStreams: 0,
                    }),
                }),
            );
            await page.evaluate(() => document.dispatchEvent(new Event("visibilitychange")));
            await page.waitForFunction(
                () =>
                    document
                        .getElementById("cp-daemon-status")
                        ?.getAttribute("data-cp-daemon-running") === "0",
            );
        },
    },
    {
        // Terminal failure: after the bounded retries are exhausted, the previous theme's pixels
        // stay visible but must be labelled as stale/unavailable rather than looking successfully
        // updated. Zero retries keeps this visual contract case fast while exercising the real
        // fetch-failure path.
        fixture: "serve-landing-declared-themes",
        suffix: "theme-error",
        apply: async (page) => {
            await page.route("**/render/**", (route) => {
                const url = new URL(route.request().url());
                if (!url.searchParams.has("themeProvider")) {
                    return route.fulfill({
                        path: renderPlaceholder,
                        contentType: "image/png",
                    });
                }
                return route.fulfill({ status: 503, body: "render unavailable" });
            });
            await page.evaluate(() => {
                themeRenderRetries = 0;
            });
            await page.getByRole("button", { name: "Brand Light" }).click();
            await page.waitForSelector(".cp-theme-render-error");
        },
    },
    {
        // Mid-swap: a themed render is in flight and has NOT come back yet. This is the frame the
        // visitor stared at for ~1s while it showed a broken-image glyph, and it is invisible to an
        // ordinary end-state screenshot — the finished pixels are identical either way. Hold the
        // render open and shoot: every card must still be showing its previous render under the
        // spinner, never an empty or broken image. This remains the last theme state because its
        // intentionally unresolved requests cannot be followed by another theme transition.
        fixture: "serve-landing-declared-themes",
        suffix: "theme-inflight",
        apply: async (page) => {
            await page.route("**/render/**", (route) => {
                const url = new URL(route.request().url());
                // Only the themed re-renders stall; the baked pixels must still load, since the
                // whole point is that they are what stays on screen.
                if (!url.searchParams.has("themeProvider")) {
                    return route.fulfill({
                        path: renderPlaceholder,
                        contentType: "image/png",
                    });
                }
                return new Promise(() => {});
            });
            await page.getByRole("button", { name: "Brand Dark" }).click();
            await page.waitForSelector(".cp-reloading");
        },
    },
    {
        // Keep the full-page export control expanded so its supported formats and future visual
        // changes are visible to the screenshot diff instead of hidden in a closed <details>.
        fixture: "serve-viewer-catalog-knobs",
        suffix: "scroll-full-page",
        apply: async (page) => {
            await page.click('[data-cp-group="scroll"] > summary');
        },
    },
    {
        // A preview the server has permanently given up on (#3317). The theme grid used to retry
        // any failure three times and then say "Theme preview unavailable", which reads as "still
        // working on it" for a card that will never render — a `painterResource` whose drawable was
        // pruned out of the bundle, say. The server now answers those with a terminal 409 and the
        // card retires immediately with its own wording, so capture that state: the claim is what
        // the visitor is told, which only a screenshot holds honest.
        fixture: "serve-landing-declared-themes",
        suffix: "theme-render-terminal",
        apply: async (page) => {
            // Registered after the generic image lane, so this wins for render requests.
            await page.route("**/render/**", (route) =>
                route.fulfill({
                    status: 409,
                    contentType: "text/plain",
                    body: "NotFoundException: File res/drawable/ic_play.xml",
                }),
            );
            await page.click('[data-theme-choice^="theme:"]');
            await page.waitForSelector(".cp-theme-error");
            // Hold until the workers have settled, so the shot isn't racing a spinner. A terminal
            // failure takes no retries, so this resolves without waiting out any backoff — which is
            // itself the behaviour under test.
            await page.waitForFunction(
                () => !document.querySelector('[aria-busy="true"]'),
            );
        },
    },
    {
        // The annotation layers default to off — the page's first job is the pixel diff, and boxes
        // drawn over both panels would obscure exactly what is being judged. That leaves the drawn
        // state invisible to the diff bot unless it is captured deliberately, so switch both layers
        // on here: every future change to the redline/type overlays (geometry, colours, label
        // placement) is then diffed for free.
        fixture: "serve-reference-compare",
        suffix: "annotated",
        apply: async (page) => {
            for (const kind of ["layout", "typography"]) {
                await page.check(`[data-cp-annotation-kind="${kind}"]`);
            }
            // The boxes are positioned from the image's rendered size, so hold until the panels
            // have actually laid out rather than racing the placeholder's load.
            await page.waitForFunction(() => {
                const box = document.querySelector(".cp-annotation");
                return box && box.getBoundingClientRect().width > 0;
            });
        },
    },
];

/** Page fixtures = `fixtures/pages/*.html`, honouring the `HARNESS_FIXTURE` narrow. */
function listPageFixtures() {
    const only = process.env.HARNESS_FIXTURE;
    const all = readdirSync(pagesDir)
        .filter((e) => e.endsWith(".html"))
        .map((e) => e.replace(/\.html$/, ""));
    return only ? all.filter((n) => n === only) : all;
}

for (const fixture of listPageFixtures()) {
    for (const theme of listThemes()) {
        test(`snapshot · ${fixture} · ${theme}`, async ({ page }) => {
            // The comparison fixture exercises production CSS and JS (including its asynchronous
            // scorer), while older static-page baselines retain their historical capture contract.
            if (STYLED_FIXTURES.has(fixture)) {
                for (const [name, contentType] of SERVE_ASSETS) {
                    await page.route(`**/assets/serve/**/${name}`, (route) =>
                        route.fulfill({
                            path: resolve(serveAssetsDir, name),
                            contentType,
                        }),
                    );
                }
            }
            for (const lane of IMAGE_LANES) {
                await page.route(lane, (route) => {
                    if (lane.includes("reference")) {
                        return route.fulfill({
                            body: REFERENCE_PLACEHOLDER,
                            contentType: "image/svg+xml",
                        });
                    }
                    const svg = new URL(route.request().url()).pathname.endsWith(".svg");
                    return route.fulfill({
                        path: svg ? renderSvgPlaceholder : renderPlaceholder,
                        contentType: svg ? "image/svg+xml" : "image/png",
                    });
                });
            }
            await page.emulateMedia({ colorScheme: theme });
            await page.goto(`/preview-harness/fixtures/pages/${fixture}.html`);

            // Wait until every (placeholder-stubbed) image has decoded so the
            // shot isn't a pre-image-load frame. Tolerant: pages with no
            // images resolve immediately.
            await page
                .waitForFunction(
                    () =>
                        Array.from(document.images).every((i) => i.complete),
                    null,
                    { timeout: 5_000 },
                )
                .catch(() => {});

            // Comparison scores are asynchronous (fetch + decode + SSIM). Capture the settled
            // fidelity state, not the initial "waiting…" skeleton.
            if (fixture === "serve-format-compare") {
                await page
                    .waitForFunction(() =>
                        Array.from(
                            document.querySelectorAll(".cp-compare-score"),
                        ).every(
                            (cell) =>
                                cell.textContent !== "waiting…" &&
                                cell.textContent !== "comparing…",
                        ),
                    )
                    .catch(() => {});
            }
            if (fixture === "serve-reference-compare") {
                await page
                    .waitForFunction(
                        () =>
                            !document
                                .querySelector(".cp-reference-result")
                                .textContent.includes("comparing"),
                    )
                    .catch(() => {});
            }

            await page.screenshot({
                path: resolve(outDir, `${fixture}.${theme}.png`),
                fullPage: true,
                animations: "disabled",
            });

            // Extra runtime states of this same fixture, shot from the already-loaded page.
            for (const state of FIXTURE_STATES.filter((s) => s.fixture === fixture)) {
                await state.apply(page);
                await page.screenshot({
                    path: resolve(
                        outDir,
                        `${fixture}-${state.suffix}.${theme}.png`,
                    ),
                    fullPage: true,
                    animations: "disabled",
                });
            }
        });
    }
}

test("contract · declared theme renders use bounded parallelism", async ({ page }) => {
    let active = 0;
    let maxActive = 0;
    let completed = 0;
    const attempts = new Map();
    let released = false;

    await page.route("**/api/theme-render-lease?*", async (route) => {
        await route.fulfill({
            status: 200,
            contentType: "application/json",
            body: JSON.stringify({ lease: "page-lease", concurrency: 5 }),
        });
    });
    await page.route("**/api/theme-render-lease/release?*", async (route) => {
        released = true;
        await route.fulfill({ status: 204, body: "" });
    });

    await page.route("**/render/**", async (route) => {
        const url = new URL(route.request().url());
        if (!url.searchParams.has("themeProvider")) {
            await route.fulfill({
                path: renderPlaceholder,
                contentType: "image/png",
            });
            return;
        }
        expect(url.searchParams.get("_themeLease")).toBe("page-lease");

        active++;
        maxActive = Math.max(maxActive, active);
        const attempt = (attempts.get(url.pathname) ?? 0) + 1;
        attempts.set(url.pathname, attempt);
        await new Promise((resolve) => setTimeout(resolve, 100));
        active--;

        // Shed every card's first request. The page must retry it without exceeding the worker cap.
        if (attempt === 1) {
            await route.fulfill({ status: 503, body: "render busy" });
        } else {
            completed++;
            await route.fulfill({
                path: renderPlaceholder,
                contentType: "image/png",
            });
        }
    });

    // Tall enough that all three cards are on screen. Off-screen cards deliberately do NOT join the
    // leased burst — they trickle in one at a time as the viewport reaches them — so a short
    // viewport would measure the deferral, not the parallelism this test is about.
    await page.setViewportSize({ width: 1280, height: 2200 });
    await page.goto(
        "/preview-harness/fixtures/pages/serve-landing-declared-themes.html",
    );
    await page.getByRole("button", { name: "Brand Light" }).click();

    await expect.poll(() => completed, { timeout: 10_000 }).toBe(3);
    expect(maxActive).toBe(3);
    expect(Array.from(attempts.values())).toEqual([2, 2, 2]);
    await expect.poll(() => released).toBe(true);
});

test("contract · snapshot overrides stay composed with a declared theme", async ({
    page,
}) => {
    const requests = [];
    for (const [name, contentType] of SERVE_ASSETS) {
        await page.route(`**/assets/serve/**/${name}`, (route) =>
            route.fulfill({
                path: resolve(serveAssetsDir, name),
                contentType,
            }),
        );
    }
    await page.route("**/render/**", async (route) => {
        requests.push(new URL(route.request().url()));
        await route.fulfill({
            path: renderPlaceholder,
            contentType: "image/png",
        });
    });
    await page.goto("/preview-harness/fixtures/pages/serve-viewer-themes.html");
    await expect.poll(() => requests.length).toBeGreaterThan(0);
    await page.waitForTimeout(100);
    requests.length = 0;

    for (const group of ["appearance", "size", "locale"]) {
        await page
            .locator(`details[data-cp-group="${group}"]`)
            .evaluate((details) => {
                details.open = true;
            });
    }

    const themeProvider = "com.example.BrandLightThemeCatalog";
    const assertSingleRender = async (before, expected) => {
        await expect
            .poll(() => requests.length, { timeout: 5_000 })
            .toBe(before + 1);
        // A second assignment of the no-store render URL used to arrive immediately after the
        // preload completed. Give it a beat so an accidental duplicate cannot pass this check.
        await page.waitForTimeout(100);
        expect(requests).toHaveLength(before + 1);
        const params = requests.at(-1).searchParams;
        expect(params.get("themeProvider")).toBe(themeProvider);
        for (const [key, value] of Object.entries(expected)) {
            expect(params.get(key), key).toBe(value);
        }
    };
    const change = async (action, expected) => {
        const before = requests.length;
        await action();
        await assertSingleRender(before, expected);
    };

    await change(
        () => page.locator("#cp-theme").selectOption(`theme:${themeProvider}`),
        {},
    );
    await change(() => page.locator("#cp-background").selectOption("clear"), {
        background: "clear",
    });
    await change(
        async () => {
            await page.locator("#cp-localeTag").fill("ar-XB");
            await page.locator("#cp-localeTag").press("Tab");
        },
        { localeTag: "ar-XB" },
    );
    await change(
        () =>
            page.locator("#cp-fontScale").evaluate((slider) => {
                slider.value = "1.3";
                slider.dispatchEvent(new Event("input", { bubbles: true }));
            }),
        { fontScale: "1.3" },
    );
    await change(() => page.locator("#cp-device").selectOption("id:pixel_7"), {
        device: "id:pixel_7",
    });
    await change(
        () => page.locator("#cp-orientation").selectOption("landscape"),
        { orientation: "landscape" },
    );
});
