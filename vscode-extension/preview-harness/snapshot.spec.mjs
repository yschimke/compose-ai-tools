// Headless capture of the preview-harness scenarios, one Playwright test
// per `(fixture × theme)`. Writes a PNG into `preview-harness/out/` —
// same filenames (`<fixture>.<theme>.png`) and same output dir the CI
// `vscode-preview-comment` / `vscode-preview-baselines` actions diff
// against `vscode-preview/main`, so this is a drop-in for the old
// `snapshot.mjs` loop.
//
// Narrow the matrix the way the old `--fixture` / `--theme` flags did via
// env:
//   HARNESS_FIXTURE=grid-default HARNESS_THEME=dark npm run harness:snapshot
// or use Playwright's own grep: `playwright test -g "grid-default"`.

import { test } from "@playwright/test";
import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";
import { listFixtures, listThemes, loadFixture } from "./_fixtures.mjs";
import { gotoFixture, replayActions, wireDiagnostics } from "./_drive.mjs";

const harnessDir = dirname(fileURLToPath(import.meta.url));
const outDir = resolve(harnessDir, "out");

for (const fixture of listFixtures()) {
    for (const theme of listThemes()) {
        test(`snapshot · ${fixture} · ${theme}`, async ({ page }) => {
            wireDiagnostics(page, `${fixture}/${theme}`);
            const data = loadFixture(fixture);

            // Deterministic web fonts. The font-browser fixture injects a
            // `<link href="fonts.googleapis.com/css2?family=…">` per browse
            // row so each family paints in its real webface. Whether those
            // sheets (and the `fonts.gstatic.com` woff2 they pull) land
            // before the screenshot is a network+timing race the in-page
            // `document.fonts.ready` settle can't close — the request may
            // still be in flight when it resolves. So the browse list
            // rendered in the real face on a fast/online run and the
            // fallback face on a slow/offline one, churning the
            // fonts-browser baseline every push (and *which* theme variant
            // lost the race flipped run-to-run). Stub the external font
            // hosts with an empty sheet so browse rows always paint in the
            // deterministic fallback, independent of network — same tactic
            // the pages spec uses to stub the daemon's `/render/` images.
            await page.route(/fonts\.(googleapis|gstatic)\.com\//, (route) =>
                route.fulfill({ contentType: "text/css", body: "" }),
            );

            await gotoFixture(page, fixture, theme);
            await replayActions(page, data.actions);

            // Optional fixed settle for surfaces the rAF/img settle can't
            // observe — e.g. the 3D `<spatial-view>` loads its quad
            // textures through three.js's own image loader, not
            // `.preview-card img`.
            if (data.settleMs) await page.waitForTimeout(data.settleMs);

            await page.screenshot({
                path: resolve(outDir, `${fixture}.${theme}.png`),
                fullPage: true,
                // Freeze CSS animations/transitions at capture so the PNG
                // isn't a mid-frame grab — replaces the in-page
                // `getAnimations().finished` wait, and unlike that wait it
                // also handles the infinite skeleton shimmer (frozen, not
                // skipped) without special-casing `iterations: Infinity`.
                animations: "disabled",
            });
        });
    }
}
