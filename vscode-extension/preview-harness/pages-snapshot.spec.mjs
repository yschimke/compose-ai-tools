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

import { test } from "@playwright/test";
import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";
import { readdirSync } from "node:fs";
import { listThemes } from "./_fixtures.mjs";

const harnessDir = dirname(fileURLToPath(import.meta.url));
const outDir = resolve(harnessDir, "out");
const pagesDir = resolve(harnessDir, "fixtures", "pages");

// The serve pages point `<img>` / their viewer JS at two image lanes with no
// backend in the harness: the daemon's `/render/<id>.png` endpoint, and the
// front door's prebaked `/hero/<system>/<hash>.png` thumbnails. Stub both with
// the committed placeholder so the capture is deterministic and tiles render at
// a realistic size instead of collapsing on broken images.
const renderPlaceholder = resolve(pagesDir, "_render-placeholder.png");
const IMAGE_LANES = ["**/render/**", "**/hero/**"];

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
            for (const lane of IMAGE_LANES) {
                await page.route(lane, (route) =>
                    route.fulfill({
                        path: renderPlaceholder,
                        contentType: "image/png",
                    }),
                );
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

            await page.screenshot({
                path: resolve(outDir, `${fixture}.${theme}.png`),
                fullPage: true,
                animations: "disabled",
            });
        });
    }
}
