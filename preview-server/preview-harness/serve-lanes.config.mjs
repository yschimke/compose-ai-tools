// Playwright config for the serve render-lane e2e spec (`serve-lanes.spec.mjs`).
//
// Unlike playwright.config.mjs (which boots the tiny static `_server.mjs`), this
// config drives a REAL, daemon-backed `compose-preview serve` that the CI job (or
// a developer) starts separately and points at via SERVE_URL. Keeping it out of
// `webServer` here is deliberate: booting serve needs a JVM, xvfb, a software-GL
// render daemon and a catalog fetch — orchestrated by the workflow / dev script,
// not Playwright's lifecycle. The spec asserts each render lane (PNG, SVG, Live
// WebSocket, Wasm) actually honors a `?knob.…` override, so it self-skips with a
// clear message when SERVE_URL is unset.

import { defineConfig } from "@playwright/test";
import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";

const here = dirname(fileURLToPath(import.meta.url));
// `||` (not `??`) so an empty SERVE_URL — e.g. an env var set to "" — falls back
// to the default instead of becoming a broken empty baseURL.
const serveUrl = process.env.SERVE_URL || "http://127.0.0.1:8725";

export default defineConfig({
  testDir: ".",
  testMatch: "serve-lanes.spec.mjs",
  outputDir: resolve(here, "test-results-serve-lanes"),
  // A cold daemon render can take a while on a CI box; keep a generous budget.
  timeout: 120_000,
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
      // Software WebGL so the in-browser Wasm (Kotlin/Wasm + Skiko) tier
      // renders headlessly. HARNESS_CHROMIUM points at a Chromium binary
      // when the bundled Playwright download isn't present.
      args: ["--enable-unsafe-swiftshader", "--use-gl=angle"],
      ...(process.env.HARNESS_CHROMIUM
        ? { executablePath: process.env.HARNESS_CHROMIUM }
        : {}),
    },
  },
});
