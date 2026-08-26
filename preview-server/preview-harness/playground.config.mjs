// Playwright config for the playground e2e spec (`playground.spec.mjs`).
//
// Like serve-lanes.config.mjs, this drives a REAL, daemon-backed
// `compose-preview serve` that the CI job (or a developer) boots separately and
// points at via SERVE_URL — here with the **playground lane** enabled
// (`--playground-android-bundle`), not a live catalog. Booting that serve needs a
// JVM, the BTA compiler, the Android/Robolectric daemon sidecar and an Android SDK,
// so it's orchestrated by the workflow / playground-boot.sh, not Playwright's
// `webServer` lifecycle. The spec self-skips with a clear message when SERVE_URL is
// unset (a local run without a playground target).
//
// The playground page is token-gated (the lane is refused under --public), so the
// spec appends `?token=<SERVE_TOKEN>` to every navigation; SERVE_TOKEN must match the
// token the boot script passed to `serve --token`.

import { defineConfig } from "@playwright/test";
import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";

const here = dirname(fileURLToPath(import.meta.url));
// `||` (not `??`) so an empty SERVE_URL — e.g. an env var set to "" — falls back to
// the default instead of becoming a broken empty baseURL.
const serveUrl = process.env.SERVE_URL || "http://127.0.0.1:8727";

export default defineConfig({
  testDir: ".",
  testMatch: "playground.spec.mjs",
  outputDir: resolve(here, "test-results-playground"),
  // A cold Android/Robolectric first-frame render blocks the compile POST: the
  // service renders the still frame synchronously before answering (up to its 180s
  // render budget), on top of the BTA compile. Budget the whole test well past that.
  timeout: 300_000,
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? "github" : "list",
  use: {
    browserName: "chromium",
    baseURL: serveUrl,
    viewport: { width: 1100, height: 820 },
    trace: "retain-on-failure",
    launchOptions: {
      // Software WebGL so any in-browser tier the /pg/ viewer opens renders
      // headlessly. HARNESS_CHROMIUM points at a Chromium binary when the bundled
      // Playwright download isn't present.
      args: ["--enable-unsafe-swiftshader", "--use-gl=angle"],
      ...(process.env.HARNESS_CHROMIUM
        ? { executablePath: process.env.HARNESS_CHROMIUM }
        : {}),
    },
  },
});
