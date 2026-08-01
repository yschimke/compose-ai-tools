// Playwright config for the bundle upload e2e spec (`bundle-upload.spec.mjs`).
//
// This drives a real upload-only `compose-preview serve` started by
// bundle-upload-boot.sh. The spec posts a locally packed preview bundle to
// POST /bundles/{name}, consumes the returned URL, and proves the uploaded
// session is browsable and renderable.

import { defineConfig } from "@playwright/test";
import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";

const here = dirname(fileURLToPath(import.meta.url));
const serveUrl = process.env.SERVE_URL || "http://127.0.0.1:8728";

export default defineConfig({
  testDir: ".",
  testMatch: "bundle-upload.spec.mjs",
  outputDir: resolve(here, "test-results-bundle-upload"),
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
      args: ["--enable-unsafe-swiftshader", "--use-gl=angle"],
      ...(process.env.HARNESS_CHROMIUM
        ? { executablePath: process.env.HARNESS_CHROMIUM }
        : {}),
    },
  },
});
