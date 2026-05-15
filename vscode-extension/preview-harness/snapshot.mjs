// Headless capture of the preview-harness scenarios.
//
// Spins up a tiny static HTTP server rooted at the extension dir (so
// the scenario page can fetch fixtures — Chromium blocks `fetch()` on
// `file://`), drives Playwright through each `(fixture × theme)` pair,
// waits for the harness's `ready` flag to flip, then writes a PNG into
// `preview-harness/out/`.
//
// Usage:
//   node esbuild.webview.mjs           # rebuild bundle first
//   node preview-harness/snapshot.mjs  # captures all scenarios
//
// Override the matrix:
//   node preview-harness/snapshot.mjs --fixture grid-default --theme dark

import { chromium } from "playwright";
import { fileURLToPath } from "node:url";
import { dirname, resolve, relative, normalize, sep } from "node:path";
import { mkdir, readdir, readFile, stat } from "node:fs/promises";
import { createServer } from "node:http";

const harnessDir = dirname(fileURLToPath(import.meta.url));
const extensionRoot = resolve(harnessDir, "..");
const outDir = resolve(harnessDir, "out");

const mimeByExt = {
    ".html": "text/html; charset=utf-8",
    ".js": "text/javascript; charset=utf-8",
    ".mjs": "text/javascript; charset=utf-8",
    ".css": "text/css; charset=utf-8",
    ".json": "application/json; charset=utf-8",
    ".png": "image/png",
    ".svg": "image/svg+xml",
    ".ttf": "font/ttf",
    ".map": "application/json; charset=utf-8",
};

function startServer(root) {
    return new Promise((resolveServer) => {
        const server = createServer(async (req, res) => {
            try {
                const url = new URL(req.url, "http://localhost");
                const rel = decodeURIComponent(url.pathname).replace(
                    /^\/+/,
                    "",
                );
                const target = normalize(resolve(root, rel));
                if (
                    relative(root, target).startsWith("..") ||
                    target === root + sep + ".." // safety
                ) {
                    res.writeHead(403);
                    res.end("forbidden");
                    return;
                }
                let filePath = target;
                try {
                    const s = await stat(filePath);
                    if (s.isDirectory()) {
                        filePath = resolve(filePath, "index.html");
                    }
                } catch {
                    res.writeHead(404);
                    res.end("not found: " + rel);
                    return;
                }
                const ext = filePath.slice(filePath.lastIndexOf("."));
                const body = await readFile(filePath);
                res.writeHead(200, {
                    "content-type":
                        mimeByExt[ext] ?? "application/octet-stream",
                    "cache-control": "no-store",
                });
                res.end(body);
            } catch (err) {
                res.writeHead(500);
                res.end(String(err));
            }
        });
        server.listen(0, "127.0.0.1", () => {
            const addr = server.address();
            resolveServer({
                origin: `http://127.0.0.1:${addr.port}`,
                close: () => new Promise((r) => server.close(r)),
            });
        });
    });
}

function arg(name, fallback) {
    const i = process.argv.indexOf("--" + name);
    return i >= 0 ? process.argv[i + 1] : fallback;
}

const explicitFixture = arg("fixture", null);
const explicitTheme = arg("theme", null);
const viewport = {
    width: Number(arg("width", 1024)),
    height: Number(arg("height", 720)),
};

async function listFixtures() {
    if (explicitFixture) return [explicitFixture];
    const entries = await readdir(resolve(harnessDir, "fixtures"));
    return entries
        .filter((e) => e.endsWith(".json"))
        .map((e) => e.replace(/\.json$/, ""));
}

const themes = explicitTheme ? [explicitTheme] : ["dark", "light"];

await mkdir(outDir, { recursive: true });
const fixtures = await listFixtures();
// Serve from the extension root so the scenario page can reach both
// `preview-harness/...` and `../media/...` via the same origin.
const server = await startServer(extensionRoot);
const browser = await chromium.launch();

try {
    for (const fixture of fixtures) {
        for (const theme of themes) {
            const context = await browser.newContext({ viewport });
            const page = await context.newPage();
            page.on("pageerror", (err) =>
                console.error(`[${fixture}/${theme}] pageerror:`, err.message),
            );
            page.on("console", (msg) => {
                if (msg.type() === "error") {
                    console.error(`[${fixture}/${theme}] console:`, msg.text());
                }
            });

            const url = new URL(
                server.origin + "/preview-harness/scenario.html",
            );
            url.searchParams.set("fixture", fixture);
            url.searchParams.set("theme", theme);
            await page.goto(url.href);

            await page.waitForFunction(
                () => window.__composePreviewHarness?.ready === true,
                { timeout: 10_000 },
            );

            const outPath = resolve(outDir, `${fixture}.${theme}.png`);
            await page.screenshot({ path: outPath, fullPage: true });
            console.log(`wrote ${outPath}`);
            await context.close();
        }
    }
} finally {
    await browser.close();
    await server.close();
}
