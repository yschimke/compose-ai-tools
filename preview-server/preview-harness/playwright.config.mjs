// Playwright config for the preview-server harness — `pages-snapshot.spec.mjs` (the served
// viewer's pages) and `format-compare-scorer.spec.mjs` (the viewer's compare scorer, run in a real
// browser).
//
// These specs used to run under [`preview-harness/playwright.config.mjs`](https://github.com/yschimke/compose-preview-vscode/blob/main/preview-harness/playwright.config.mjs), which is
// how 167 of that config's 205 tests came to be serve's. The lane configs beside this one
// (`serve-lanes`, `playground`, `bundle-upload`) already had their own configs because they need a
// running server; these two did not, because they only need a browser and a static file server.
//
// **The `use` block below is pixel-load-bearing and must stay byte-equivalent to the extension's.**
// Viewport and the two Chromium raster flags decide the captured pixels, and the baselines on the
// `serve-preview/main` branch were taken with these exact values. Change one and every capture
// rebaselines — see the extension config's long note on why `--disable-partial-raster` and
// `--disable-skia-runtime-opts` are in and why `--disable-lcd-text` is deliberately out.

import { defineConfig } from "@playwright/test";
import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";

const here = dirname(fileURLToPath(import.meta.url));
const PORT = Number(process.env.HARNESS_PORT ?? 5601);

export default defineConfig({
  testDir: ".",
  // Named, not a glob: the lane specs in this directory have their own configs and their own
  // servers, and a bare `*.spec.mjs` here would drag them into every page capture.
  testMatch: /(pages-snapshot|format-compare-scorer)\.spec\.mjs/,
  outputDir: resolve(here, "test-results"),
  timeout: 60_000,
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? "github" : "list",
  use: {
    browserName: "chromium",
    baseURL: `http://127.0.0.1:${PORT}`,
    viewport: {
      width: Number(process.env.HARNESS_WIDTH ?? 1024),
      height: Number(process.env.HARNESS_HEIGHT ?? 720),
    },
    trace: "retain-on-failure",
    launchOptions: {
      args: [
        "--enable-unsafe-swiftshader",
        "--use-gl=angle",
        "--disable-partial-raster",
        "--disable-skia-runtime-opts",
      ],
      ...(process.env.HARNESS_CHROMIUM
        ? { executablePath: process.env.HARNESS_CHROMIUM }
        : {}),
    },
  },
  webServer: {
    command: "node _server.mjs",
    url: `http://127.0.0.1:${PORT}/preview-harness/index.html`,
    reuseExistingServer: !process.env.CI,
    env: { HARNESS_PORT: String(PORT) },
    stdout: "ignore",
    stderr: "pipe",
  },
});
