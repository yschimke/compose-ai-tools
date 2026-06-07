// Playwright Test config for the preview-harness specs (`snapshot.spec.mjs`,
// `contract.spec.mjs`).
//
// What the runner buys us over the old hand-rolled `node snapshot.mjs`
// loop:
//   - `webServer` owns the tiny static server's lifecycle (start, wait
//     for ready, tear down) — the ~50-line `startServer` boilerplate that
//     used to be copy-pasted into every script now lives once in
//     `_server.mjs`.
//   - per-`(fixture × theme)` test isolation, retries, an HTML report,
//     and a trace on failure (DOM + console + network snapshot) instead
//     of a bare `console.error` line in a for-loop.
//   - `page.screenshot({ animations: "disabled" })` (used in the snapshot
//     spec) freezes CSS animations at capture, replacing the in-page
//     `getAnimations().finished` settle that had to special-case infinite
//     shimmer animations.

import { defineConfig } from "@playwright/test";
import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";

const here = dirname(fileURLToPath(import.meta.url));
const PORT = Number(process.env.HARNESS_PORT ?? 5599);

export default defineConfig({
    testDir: ".",
    testMatch: "*.spec.mjs",
    // Pin artifacts under preview-harness/ regardless of the cwd the npm
    // script is invoked from, so traces/results don't scatter into the
    // extension root.
    outputDir: resolve(here, "test-results"),
    // CI sandboxes are slow + the bundle settle isn't instant; keep a
    // generous per-test budget. Individual waits set their own timeouts.
    timeout: 60_000,
    fullyParallel: true,
    forbidOnly: !!process.env.CI,
    retries: process.env.CI ? 1 : 0,
    reporter: process.env.CI ? "github" : "list",
    use: {
        browserName: "chromium",
        baseURL: `http://127.0.0.1:${PORT}`,
        // 1024×720 matches the baselines on `vscode-preview/main`; don't
        // pull in a `devices[...]` preset, which would force its own
        // viewport and shift every captured pixel.
        viewport: {
            width: Number(process.env.HARNESS_WIDTH ?? 1024),
            height: Number(process.env.HARNESS_HEIGHT ?? 720),
        },
        trace: "retain-on-failure",
        launchOptions: {
            // Software WebGL so the 3D spatial-view fixture renders
            // headlessly; inert for the 2D fixtures. `HARNESS_CHROMIUM`
            // points at a Chromium binary when the default Playwright
            // download isn't present (some CI sandboxes ship only the
            // full build, not the headless shell).
            args: ["--enable-unsafe-swiftshader", "--use-gl=angle"],
            ...(process.env.HARNESS_CHROMIUM
                ? { executablePath: process.env.HARNESS_CHROMIUM }
                : {}),
        },
    },
    webServer: {
        command: "node _server.mjs",
        url: `http://127.0.0.1:${PORT}/preview-harness/scenario.html?fixture=grid-default`,
        reuseExistingServer: !process.env.CI,
        env: { HARNESS_PORT: String(PORT) },
        stdout: "ignore",
        stderr: "pipe",
    },
});
