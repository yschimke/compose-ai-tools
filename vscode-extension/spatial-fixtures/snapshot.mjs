// Headless capture of the standalone spatial-viewer dev page
// (`spatial-fixtures/index.html`). The 3D analogue of
// `preview-harness/snapshot.mjs`: serves the extension root over http (so
// `fetch()` of scene.json + texture PNGs works — Chromium blocks it on
// `file://`), renders a fixture with software WebGL, optionally focuses a
// panel, and writes a PNG to `spatial-fixtures/out/` (gitignored).
//
// Usage (rebuild the bundle first):
//   node esbuild.webview.mjs
//   node spatial-fixtures/snapshot.mjs --fixture spatial-rich
//   node spatial-fixtures/snapshot.mjs --fixture spatial-scene --focus bottom
//
// Honours SPATIAL_CHROMIUM=<path> when the default Playwright Chromium
// download isn't present (some CI sandboxes ship only the full Chromium build).

import { chromium } from "playwright";
import { fileURLToPath } from "node:url";
import { dirname, resolve, relative, normalize } from "node:path";
import { mkdir, readFile, stat } from "node:fs/promises";
import { createServer } from "node:http";

const fixturesDir = dirname(fileURLToPath(import.meta.url));
const extensionRoot = resolve(fixturesDir, "..");
const outDir = resolve(fixturesDir, "out");

const args = process.argv.slice(2);
function flag(name, fallback) {
    const i = args.indexOf(`--${name}`);
    return i >= 0 && args[i + 1] ? args[i + 1] : fallback;
}
const fixture = flag("fixture", "spatial-rich");
const focus = flag("focus", null);

const mimeByExt = {
    ".html": "text/html; charset=utf-8",
    ".js": "text/javascript; charset=utf-8",
    ".mjs": "text/javascript; charset=utf-8",
    ".css": "text/css; charset=utf-8",
    ".json": "application/json; charset=utf-8",
    ".png": "image/png",
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
                let target = normalize(resolve(root, rel));
                if (relative(root, target).startsWith("..")) {
                    res.writeHead(403);
                    res.end("forbidden");
                    return;
                }
                const s = await stat(target).catch(() => null);
                if (s?.isDirectory()) target = resolve(target, "index.html");
                const body = await readFile(target);
                const ext = target.slice(target.lastIndexOf("."));
                res.writeHead(200, {
                    "Content-Type":
                        mimeByExt[ext] ?? "application/octet-stream",
                });
                res.end(body);
            } catch {
                res.writeHead(404);
                res.end("not found");
            }
        });
        server.listen(0, "127.0.0.1", () =>
            resolveServer({ server, port: server.address().port }),
        );
    });
}

const { server, port } = await startServer(extensionRoot);
const browser = await chromium.launch({
    args: ["--enable-unsafe-swiftshader", "--use-gl=angle"],
    executablePath: process.env.SPATIAL_CHROMIUM || chromium.executablePath(),
});
try {
    const page = await browser.newPage({
        viewport: { width: 1100, height: 760 },
        deviceScaleFactor: 1,
    });
    await page.goto(
        `http://127.0.0.1:${port}/spatial-fixtures/?fixture=${fixture}`,
    );
    await page.selectOption("#fixture", fixture).catch(() => {});
    await page.waitForFunction(
        () => {
            const v = document.getElementById("view");
            return v && v.scene && v.scene.panels.length > 0;
        },
        { timeout: 15000 },
    );
    await page.waitForTimeout(1200); // textures + a few frames
    if (focus) {
        await page.evaluate(
            (id) => document.getElementById("view").focusPanel(id),
            focus,
        );
        await page.waitForTimeout(800);
    }
    await mkdir(outDir, { recursive: true });
    const file = resolve(
        outDir,
        focus ? `${fixture}.focus-${focus}.png` : `${fixture}.png`,
    );
    await page.screenshot({ path: file });
    console.log(`wrote ${relative(extensionRoot, file)}`);
} finally {
    await browser.close();
    server.close();
}
