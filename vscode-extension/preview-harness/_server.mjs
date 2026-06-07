// Tiny static file server rooted at the extension dir. The scenario page
// needs a real origin because Chromium blocks `fetch()` on `file://` —
// the fixtures, the `media/` CSS, and the webview bundle are all fetched
// relative to this root.
//
// Two ways in:
//   - `startServer(root, port)` for in-process callers (the spatial dev
//     snapshot still drives it directly).
//   - `node preview-harness/_server.mjs` as a standalone process, which is
//     how Playwright's `webServer` config boots it for the harness specs.
//     Honours `HARNESS_PORT` (default 5599) and logs the ready URL so
//     Playwright's readiness probe has something to poll.
//
// Extracted from the old `snapshot.mjs` / `contract.mjs` copies — there
// used to be three byte-identical versions of this server.

import { fileURLToPath } from "node:url";
import { dirname, resolve, relative, normalize, sep } from "node:path";
import { readFile, stat } from "node:fs/promises";
import { createServer } from "node:http";

const harnessDir = dirname(fileURLToPath(import.meta.url));
export const extensionRoot = resolve(harnessDir, "..");

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

export function startServer(root, port = 0) {
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
                // Don't echo the error (stack trace / internal paths) back
                // to the client — log it server-side and return a generic
                // 500. (CodeQL: information exposure through a stack trace.)
                console.error("[harness] request error:", err);
                res.writeHead(500);
                res.end("internal server error");
            }
        });
        server.listen(port, "127.0.0.1", () => {
            const addr = server.address();
            resolveServer({
                origin: `http://127.0.0.1:${addr.port}`,
                port: addr.port,
                close: () => new Promise((r) => server.close(r)),
            });
        });
    });
}

// Standalone entry point for Playwright's `webServer`.
if (process.argv[1] === fileURLToPath(import.meta.url)) {
    const port = Number(process.env.HARNESS_PORT ?? 5599);
    const { origin } = await startServer(extensionRoot, port);
    // Playwright polls the configured `url`; this log is just for humans.
    console.log(`[harness] serving ${extensionRoot} at ${origin}`);
}
